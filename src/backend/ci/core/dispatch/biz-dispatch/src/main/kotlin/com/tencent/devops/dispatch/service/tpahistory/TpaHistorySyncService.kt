package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.dispatch.dao.TpaHistoryDao
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

/**
 * 把 T_DISPATCH_THIRDPARTY_AGENT_BUILD 同步到构建记录展示表。
 * - 实时：源表写入后通过 [TpaHistoryChanges] 标记构建，每个节点每秒同步一次本节点标记的构建；
 * - 补齐（只在一个节点执行）：按主键扫描源表新行（首次部署时即为历史数据回填）、复查仍在排队/运行中的明细、
 *   清理源表中已删除的构建（misc 按构建清理数据）。
 * 同步以构建为单位，每次都重新读取源表，结果与标记次数和顺序无关。回填追上部署时的源表进度后，查询才切换到展示表。
 * 同步不影响领取任务和调度：写入方只在内存里记一个构建ID，同步使用独立线程池，失败只记录日志。
 */
@Service
class TpaHistorySyncService @Autowired constructor(
    private val dslContext: DSLContext,
    private val redisOperation: RedisOperation,
    private val historyDao: TpaHistoryDao,
    @Value("\${dispatch.tpaHistory.syncEnabled:true}")
    private val syncEnabled: Boolean,
    @Value("\${dispatch.tpaHistory.readEnabled:false}")
    private val readEnabled: Boolean,
    @Value("\${dispatch.tpaHistory.workers:2}")
    private val workers: Int,
    @Value("\${dispatch.tpaHistory.scanBatchSize:500}")
    private val scanBatchSize: Int,
    @Value("\${dispatch.tpaHistory.scanBudgetSeconds:20}")
    private val scanBudgetSeconds: Long
) {
    @Volatile
    private var readable = false

    // 复查进行中行的游标，只在持有锁的节点上推进，换节点后从头开始即可
    @Volatile
    private var activeCursor = 0L

    private val timer: ScheduledExecutorService = Executors.newScheduledThreadPool(TIMER_THREADS, named("tpa-history-timer"))

    private val pool = ThreadPoolExecutor(
        workers.coerceAtLeast(1), workers.coerceAtLeast(1), 60, TimeUnit.SECONDS,
        LinkedBlockingQueue(WORKER_QUEUE), named("tpa-history-sync"), ThreadPoolExecutor.CallerRunsPolicy()
    )

    /** 回填已追上部署时的源表进度，查询可以使用展示表 */
    fun readable() = readEnabled && readable

    @PostConstruct
    fun start() {
        // 先建表并回填核对，再显式开启展示表读取。只同步期间，查询始终走原表。
        if (readEnabled) {
            timer.scheduleWithFixedDelay({ guard("refreshReadable") { refreshReadable() } }, 0, 30, TimeUnit.SECONDS)
        }
        if (!syncEnabled) {
            logger.info("tpa history sync disabled")
            return
        }
        timer.scheduleWithFixedDelay({ guard("drain") { drainChanges() } }, 5, 1, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ guard("scan") { leader(SCAN_LOCK) { scanNewRows() } } }, 10, 5, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay(
            { guard("active") { leader(ACTIVE_LOCK) { reconcileActive() } } }, 30, 30, TimeUnit.SECONDS
        )
        timer.scheduleWithFixedDelay(
            { guard("orphan") { leader(ORPHAN_LOCK) { sweepOrphans() } } }, 60, 30, TimeUnit.SECONDS
        )
    }

    @PreDestroy
    fun stop() {
        timer.shutdownNow()
        pool.shutdown()
    }

    /**
     * 在一个事务内同步一个构建：先锁住明细中该构建的行，再读取源表，比较后写入明细和各视图的增量。
     * 死锁、锁超时、主键冲突（并发同步同一个新构建）时整体重试。
     */
    fun syncBuild(buildId: String) {
        var attempt = 0
        while (true) {
            try {
                dslContext.transaction { configuration ->
                    val context = DSL.using(configuration)
                    val old = historyDao.lockBuildRows(context, buildId)
                    val new = TpaHistoryPlanner.withLatestFlags(historyDao.fetchSourceRows(context, buildId))
                    val plan = TpaHistoryPlanner.plan(old, new)
                    if (!plan.isEmpty()) {
                        historyDao.apply(context, plan)
                    }
                }
                return
            } catch (e: Exception) {
                if (++attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    throw e
                }
            }
        }
    }

    /** 并发同步一批构建并等待完成，单个构建失败只记录日志 */
    fun syncBuilds(buildIds: Collection<String>) {
        if (buildIds.isEmpty()) return
        CompletableFuture.allOf(
            *buildIds.map { buildId ->
                CompletableFuture.runAsync({
                    try {
                        syncBuild(buildId)
                    } catch (e: Exception) {
                        logger.warn("tpa history sync failed|$buildId", e)
                    }
                }, pool)
            }.toTypedArray()
        ).join()
    }

    internal fun drainChanges() {
        while (true) {
            val buildIds = TpaHistoryChanges.drain(DRAIN_BATCH)
            if (buildIds.isEmpty()) return
            syncBuilds(buildIds)
        }
    }

    /**
     * 按主键扫描源表的新行：首次运行记录当时的最大 ID 作为回填目标，游标追上目标后查询切换到展示表。
     * 之后持续扫描，补齐实时同步丢失的新行（例如节点重启）。单个构建失败不阻塞游标。
     */
    internal fun scanNewRows() {
        val target = historyDao.getProgress(dslContext, BACKFILL_TARGET)
            ?: historyDao.maxSourceId(dslContext).also { historyDao.setProgress(dslContext, BACKFILL_TARGET, it) }
        var cursor = historyDao.getProgress(dslContext, SCAN_CURSOR) ?: 0L
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(scanBudgetSeconds)
        while (System.currentTimeMillis() < deadline) {
            val rows = historyDao.fetchSourceIdsAfter(
                dslContext = dslContext,
                afterId = cursor,
                before = LocalDateTime.now().minusSeconds(SCAN_DELAY_SECONDS),
                limit = scanBatchSize
            )
            if (rows.isEmpty()) break
            syncBuilds(rows.map { it.second }.distinct())
            cursor = rows.last().first
            historyDao.setProgress(dslContext, SCAN_CURSOR, cursor)
            if (cursor < target) {
                logger.info("tpa history backfill|$cursor/$target")
            }
            if (rows.size < scanBatchSize) break
        }
    }

    /** 复查明细中仍在排队/运行中、一分钟以上没有更新的行，补齐丢失的状态变化 */
    internal fun reconcileActive() {
        val rows = historyDao.fetchActiveRowsAfter(dslContext, activeCursor, ACTIVE_BATCH)
        activeCursor = if (rows.size < ACTIVE_BATCH) 0L else rows.last().first
        val staleBefore = LocalDateTime.now().minusSeconds(ACTIVE_STALE_SECONDS)
        syncBuilds(rows.filter { it.third.isBefore(staleBefore) }.map { it.second }.distinct())
    }

    /** 分批检查明细中的构建在源表中是否还存在，已删除的重新同步一次即可清掉，游标循环推进 */
    internal fun sweepOrphans() {
        if (!readable) return
        val cursor = historyDao.getProgress(dslContext, ORPHAN_CURSOR) ?: 0L
        val rows = historyDao.fetchHistoryBuildsAfter(dslContext, cursor, ORPHAN_BATCH)
        if (rows.isEmpty()) {
            historyDao.setProgress(dslContext, ORPHAN_CURSOR, 0L)
            return
        }
        val buildIds = rows.map { it.second }.distinct()
        val existing = historyDao.existingSourceBuilds(dslContext, buildIds)
        syncBuilds(buildIds.filterNot { it in existing })
        historyDao.setProgress(dslContext, ORPHAN_CURSOR, rows.last().first)
    }

    internal fun refreshReadable() {
        val target = historyDao.getProgress(dslContext, BACKFILL_TARGET)
        val cursor = historyDao.getProgress(dslContext, SCAN_CURSOR) ?: 0L
        val ready = target != null && cursor >= target
        if (ready != readable) {
            logger.info("tpa history readable changed to $ready|$cursor/$target")
        }
        readable = ready
    }

    private fun leader(lockKey: String, action: () -> Unit) {
        val lock = RedisLock(redisOperation, lockKey, LOCK_SECONDS)
        if (!lock.tryLock()) return
        try {
            action()
        } finally {
            lock.unlock()
        }
    }

    private fun guard(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            // 定时任务抛出异常后不会再被调度，这里必须吞掉
            logger.warn("tpa history $name failed", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TpaHistorySyncService::class.java)

        const val BACKFILL_TARGET = "BACKFILL_TARGET"
        const val SCAN_CURSOR = "SCAN_CURSOR"
        const val ORPHAN_CURSOR = "ORPHAN_CURSOR"

        private const val SCAN_LOCK = "dispatch:tpaHistory:scan"
        private const val ACTIVE_LOCK = "dispatch:tpaHistory:active"
        private const val ORPHAN_LOCK = "dispatch:tpaHistory:orphan"
        private const val LOCK_SECONDS = 120L

        private const val MAX_ATTEMPTS = 3
        private const val TIMER_THREADS = 4
        private const val WORKER_QUEUE = 2000
        private const val DRAIN_BATCH = 200
        private const val SCAN_DELAY_SECONDS = 10L
        private const val ACTIVE_BATCH = 500
        private const val ACTIVE_STALE_SECONDS = 60L
        private const val ORPHAN_BATCH = 1000

        // MySQL：1213 死锁，1205 锁等待超时，1062 主键冲突
        private val RETRYABLE_ERROR_CODES = setOf(1213, 1205, 1062)

        fun isRetryable(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is PessimisticLockingFailureException || cause is DuplicateKeyException) return true
                if (cause is SQLException && cause.errorCode in RETRYABLE_ERROR_CODES) return true
                cause = cause.cause.takeIf { it !== cause }
            }
            return false
        }

        private fun named(prefix: String) = object : java.util.concurrent.ThreadFactory {
            private val index = AtomicInteger()
            override fun newThread(r: Runnable) = Thread(r, "$prefix-${index.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
