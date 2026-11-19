package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.environment.pojo.AllCreateNodeEnv
import java.time.Duration
import java.time.LocalDateTime

/**
 * 构建记录展示明细（T_DISPATCH_TPA_HISTORY）的一行，与 T_DISPATCH_THIRDPARTY_AGENT_BUILD 的一行一一对应。
 */
data class TpaHistoryRow(
    val id: Long,
    val projectId: String,
    val agentId: String,
    val envKey: String?,
    val pipelineId: String,
    val pipelineName: String,
    val buildId: String,
    val buildNum: Int,
    val executeCount: Int,
    val vmSeqId: String,
    val jobId: String?,
    val taskName: String,
    val stageId: String?,
    val status: Int,
    val latest: Boolean = false,
    val statusLatest: Boolean = false,
    val startUser: String?,
    val workspace: String?,
    val timeInterval: Long?,
    val createdTime: LocalDateTime,
    val updatedTime: LocalDateTime
) {
    /** 这一行计入的统计范围：所在构建机，有环境时再加所在环境 */
    fun scopes(): List<String> = listOfNotNull(TpaHistoryScope.agent(agentId), envKey?.let { TpaHistoryScope.env(it) })

    fun finished() = status == PipelineTaskStatus.DONE.status || status == PipelineTaskStatus.FAILURE.status
}

/**
 * 统计范围 SCOPE_KEY：A:构建机ID 或 E:环境范围。
 * 所有创作环境的 ID 在各项目间相同，环境范围需带上项目（与原查询按 projectId 隔离的口径一致）。
 */
object TpaHistoryScope {
    private const val AGENT_PREFIX = "A:"
    private const val ENV_PREFIX = "E:"

    fun agent(agentId: String) = "$AGENT_PREFIX$agentId"

    fun env(envKey: String) = "$ENV_PREFIX$envKey"

    fun envKey(envId: Long, projectId: String) =
        if (AllCreateNodeEnv.hasId(envId)) "$envId:$projectId" else envId.toString()

    /** @return Pair(agentId, envKey)，二者只有一个非空 */
    fun parse(scopeKey: String): Pair<String?, String?> = when {
        scopeKey.startsWith(AGENT_PREFIX) -> Pair(scopeKey.removePrefix(AGENT_PREFIX), null)
        scopeKey.startsWith(ENV_PREFIX) -> Pair(null, scopeKey.removePrefix(ENV_PREFIX))
        else -> throw IllegalArgumentException("invalid scope key $scopeKey")
    }
}

/** 构建视图的一行：一个构建（同一执行次数）在某个统计范围内的汇总 */
data class TpaBuildView(
    val scopeKey: String,
    val buildId: String,
    val executeCount: Int,
    val projectId: String,
    val pipelineId: String,
    val pipelineName: String,
    val buildNum: Int,
    val startUser: String?,
    val startTime: LocalDateTime?,
    val totalTime: Long?,
    val lastId: Long
)

data class TpaBuildKey(val scopeKey: String, val buildId: String, val executeCount: Int) : Comparable<TpaBuildKey> {
    override fun compareTo(other: TpaBuildKey) = compareValuesBy(this, other, { it.scopeKey }, { it.buildId }, { it.executeCount })
}

/** @param after 为空表示该构建已不在此范围内，删除视图行 */
data class TpaBuildChange(val key: TpaBuildKey, val after: TpaBuildView?)

data class TpaPipelineLast(val lastId: Long, val buildId: String, val pipelineName: String)

/**
 * 流水线视图的增量：本次同步的构建对 (范围, 流水线) 的影响。
 * @param countDelta 构建数变化，按构建视图行的增删计算
 * @param last 本构建在该流水线下最新的构建视图行，按"更新才覆盖"写入
 * @param removedLastId 本构建原先最新的行被移除或变旧时为原 LAST_ID，若视图行正指向它，需要重新计算最新构建
 */
data class TpaPipelineChange(
    val scopeKey: String,
    val pipelineId: String,
    val projectId: String,
    val countDelta: Int,
    val last: TpaPipelineLast?,
    val removedLastId: Long?
)

data class TpaJobLast(
    val lastId: Long,
    val createdTime: LocalDateTime,
    val vmSeqId: String,
    val stageId: String?,
    val taskName: String,
    val pipelineName: String
)

/** Job 视图的增量，含义同 [TpaPipelineChange]；耗时只统计已结束的执行 */
data class TpaJobChange(
    val scopeKey: String,
    val pipelineId: String,
    val jobId: String,
    val projectId: String,
    val runDelta: Int,
    val timeSumDelta: Long,
    val timeCountDelta: Int,
    val last: TpaJobLast?,
    val removedLastId: Long?
)

data class TpaHistoryPlan(
    val upserts: List<TpaHistoryRow>,
    val deletes: List<Long>,
    val builds: List<TpaBuildChange>,
    val pipelines: List<TpaPipelineChange>,
    val jobs: List<TpaJobChange>
) {
    fun isEmpty() = upserts.isEmpty() && deletes.isEmpty() && builds.isEmpty() && pipelines.isEmpty() && jobs.isEmpty()
}

object AgentBuildElapsed {
    /**
     * 计算一批 Job（同一构建、同一执行次数）的总耗时（秒）：
     * 只统计已结束的 Job，以 [CREATED_TIME, UPDATED_TIME] 为区间合并重叠部分后累加，避免并行 Job 被重复统计。
     * @return Pair(已结束 Job 中最早的开始时间, 总耗时)，没有已结束的 Job 时为 null
     */
    fun calc(items: Sequence<Triple<Int?, LocalDateTime?, LocalDateTime?>>): Pair<LocalDateTime, Long>? {
        val finished = setOf(PipelineTaskStatus.DONE.status, PipelineTaskStatus.FAILURE.status)
        val intervals = items
            .filter { it.first in finished }
            .mapNotNull { (_, start, end) ->
                if (start == null || end == null || end.isBefore(start)) null else start to end
            }
            .sortedBy { it.first }
            .toList()
        if (intervals.isEmpty()) return null
        var total = 0L
        var curStart = intervals[0].first
        var curEnd = intervals[0].second
        for (i in 1 until intervals.size) {
            val (s, e) = intervals[i]
            if (!s.isAfter(curEnd)) {
                if (e.isAfter(curEnd)) curEnd = e
            } else {
                total += Duration.between(curStart, curEnd).seconds
                curStart = s
                curEnd = e
            }
        }
        total += Duration.between(curStart, curEnd).seconds
        return Pair(intervals.first().first, total)
    }
}
