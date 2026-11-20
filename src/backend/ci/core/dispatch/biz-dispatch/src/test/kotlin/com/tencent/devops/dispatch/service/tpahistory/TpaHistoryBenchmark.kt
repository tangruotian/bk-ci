package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.dao.TpaHistoryDao
import com.tencent.devops.dispatch.dao.TpaHistoryQueryDao
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 性能对比，不参与常规测试：在 DISPATCH_BENCH_MYSQL_URL 指向的库（含已生成数据的源表）上，
 * 先用 [TpaHistorySyncService.scanNewRows] 回填展示表，再对同一批请求分别执行原查询与展示表查询，
 * 输出中位耗时和服务端读取行数（Handler_read_*）。
 */
@EnabledIfEnvironmentVariable(named = "DISPATCH_BENCH_MYSQL_URL", matches = ".+")
class TpaHistoryBenchmark {

    private data class Case(val name: String, val run: (AgentBuildViewQuery) -> Any?)

    @Test
    fun compare() {
        mockkObject(I18nUtil)
        every { I18nUtil.getCodeLanMessage(any()) } answers { firstArg<String>() }
        val url = System.getenv("DISPATCH_BENCH_MYSQL_URL")
        val ds = HikariDataSource().apply {
            jdbcUrl = url + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&sessionVariables=sql_mode=" +
                "%27STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION%27"
            username = "root"
            password = "root"
            maximumPoolSize = 12
        }
        val dsl = DSL.using(ds, SQLDialect.MYSQL).apply { settings().isRenderSchema = false }
        val sync = TpaHistorySyncService(
            dslContext = dsl, redisOperation = mockk<RedisOperation>(relaxed = true), historyDao = TpaHistoryDao(),
            syncEnabled = false, readEnabled = true, workers = 8, scanBatchSize = 2000, scanBudgetSeconds = 36000
        )
        if (System.getenv("DISPATCH_BENCH_SKIP_BACKFILL") == null) {
            val start = System.currentTimeMillis()
            sync.scanNewRows()
            val seconds = (System.currentTimeMillis() - start) / 1000.0
            val rows = dsl.fetchCount(DSL.table("T_DISPATCH_TPA_HISTORY"))
            val builds = dsl.fetchOne("select count(distinct BUILD_ID) from T_DISPATCH_TPA_HISTORY")!!.get(0, Int::class.java)
            val source = dsl.fetchCount(DSL.table("T_DISPATCH_THIRDPARTY_AGENT_BUILD"))
            println(
                "BACKFILL rows=$rows/$source builds=$builds seconds=$seconds " +
                    "rate=${(builds / seconds).toInt()} builds/s retries=${sync.retries.get()}"
            )
        }

        val hot = AgentBuildScope("proj-0", "agent0", null)
        val cold = AgentBuildScope("proj-0", "agent30", null)
        val env = AgentBuildScope("proj-0", null, 7L)
        val none = AgentBuildFilter()
        // 以数据中最新的创建时间为准取最近 7 天，保证时间筛选命中数据
        val latest = dsl.fetchOne("select max(CREATED_TIME) from T_DISPATCH_THIRDPARTY_AGENT_BUILD")!!
            .get(0, LocalDateTime::class.java)
        val lastWeek = AgentBuildFilter(startTime = latest.minusDays(7).atZone(ZoneId.systemDefault()).toEpochSecond())
        val finished = AgentBuildFilter(status = listOf(PipelineTaskStatus.DONE, PipelineTaskStatus.FAILURE))
        val samplePipeline = "p-3"
        val sampleBuild = dsl.select(DSL.field("BUILD_ID", String::class.java))
            .from("T_DISPATCH_THIRDPARTY_AGENT_BUILD").where("AGENT_ID = 'agent0'")
            .orderBy(DSL.field("ID").desc()).limit(1).fetchOne(0, String::class.java)!!

        fun pageOpen(scope: AgentBuildScope, filter: AgentBuildFilter) = { q: AgentBuildViewQuery ->
            // 页面默认打开 BUILD 视图：三个总数 + 第一页 + 耗时
            q.countViews(scope, filter)
            val builds = q.listBuilds(scope, filter, 0, 20)
            q.buildElapsed(scope, builds.map { it.buildId!! to it.executeCount })
        }

        val cases = listOf(
            Case("打开页面(BUILD) 构建机-热点", pageOpen(hot, none)),
            Case("打开页面(BUILD) 构建机-普通", pageOpen(cold, none)),
            Case("打开页面(BUILD) 环境", pageOpen(env, none)),
            Case("BUILD 第50页 环境") { it.listBuilds(env, none, 980, 20) },
            Case("PIPELINE 视图 环境") { q -> q.countViews(env, none); q.listPipelines(env, none, 0, 20) },
            Case("JOB 视图 环境") { q -> q.countViews(env, none); q.listJobs(env, none, 0, 20) },
            Case("展开流水线 环境") { q ->
                q.countPipelineBuilds(env, samplePipeline, none)
                val b = q.listPipelineBuilds(env, samplePipeline, none, 0, 20)
                q.listBuildJobs(env, b.map { it.first }.toSet())
            },
            Case("展开 Job 环境") { q ->
                val f = AgentBuildFilter(pipelineId = samplePipeline, jobId = "job_1")
                q.countJobRuns(env, f); q.listJobRuns(env, f, 0, 20)
            },
            Case("展开构建") { q -> q.countBuildRuns(hot, sampleBuild, 1, none); q.listBuildRuns(hot, sampleBuild, 1, none, 0, 20) },
            Case("下拉 流水线 环境") { it.pipelineOptions(env, null) },
            Case("下拉 Job 环境") { it.jobOptions(env, null) },
            Case("下拉 执行人 环境") { it.creatorOptions(env, null) },
            Case("筛选 近7天 环境") { q -> q.countViews(env, lastWeek); q.listBuilds(env, lastWeek, 0, 20) },
            Case("筛选 近7天 构建机-热点") { q -> q.countViews(hot, lastWeek); q.listBuilds(hot, lastWeek, 0, 20) },
            Case("筛选 已完成 构建机-热点") { q -> q.countViews(hot, finished); q.listBuilds(hot, finished, 0, 20) },
            Case("筛选 执行人 环境") { q ->
                val f = AgentBuildFilter(creator = "user3"); q.countViews(env, f); q.listBuilds(env, f, 0, 20)
            },
            Case("节点任务列表 第1页") { q -> q.listAgentBuilds("agent0", null, null, 0, 21) },
            Case("节点任务列表 第20页+计数") { q ->
                q.listAgentBuilds("agent0", null, null, 380, 21); q.countAgentBuilds("agent0", null, null)
            },
            Case("节点任务列表 已完成 第1页") { q -> q.listAgentBuilds("agent0", 3, null, 0, 21) }
        )

        println(String.format("%-28s %12s %12s %8s %14s %14s", "场景", "原查询ms", "新表ms", "倍数", "原读取行", "新读取行"))
        val only = System.getenv("DISPATCH_BENCH_CASES")?.split(",")?.filter { it.isNotBlank() }
        cases.filter { case -> only == null || only.any { case.name.contains(it) } }.forEach { case ->
            val (oldMs, oldRows) = measure(dsl, case) { LegacyAgentBuildViewQuery(it, ThirdPartyAgentBuildDao()) }
            val (newMs, newRows) = measure(dsl, case) { HistoryAgentBuildViewQuery(it, TpaHistoryQueryDao()) }
            println(
                String.format(
                    "%-28s %12.1f %12.1f %8s %14d %14d",
                    case.name, oldMs, newMs, if (newMs > 0) String.format("%.0fx", oldMs / newMs) else "-", oldRows, newRows
                )
            )
        }
        ds.close()
    }

    /**
     * 查询对象绑定到同一个连接上执行，才能用会话级 Handler_read_* 统计服务端读取的行数。
     * 先预热一次，再取 5 次中位数；读取行数为单次执行的增量，已扣除 SHOW STATUS 自身的开销。
     */
    private fun measure(
        dsl: DSLContext,
        case: Case,
        factory: (DSLContext) -> AgentBuildViewQuery
    ): Pair<Double, Long> = dsl.connectionResult { conn ->
        val ctx = DSL.using(conn, SQLDialect.MYSQL).apply { settings().isRenderSchema = false }
        val query = factory(ctx)
        fun reads(): Long = ctx.fetch("SHOW SESSION STATUS LIKE 'Handler_read%'")
            .sumOf { it.get(1)?.toString()?.toLongOrNull() ?: 0L }
        case.run(query)
        val r0 = reads()
        val overhead = reads() - r0
        val before = reads()
        case.run(query)
        val rows = reads() - before - overhead
        val times = (1..5).map {
            val start = System.nanoTime()
            case.run(query)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        Pair(times[2], rows)
    }
}
