package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.dao.TpaHistoryDao
import com.tencent.devops.dispatch.dao.TpaHistoryQueryDao
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuild
import com.tencent.devops.model.dispatch.tables.TDispatchThirdpartyAgentBuild
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistory
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryBuild
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryJob
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryPipeline
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistorySync
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.Table
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 在真实 MySQL 上验证构建记录展示表：同一批源数据下，展示表查询与原始查询结果一致；增量维护与全量重建一致；
 * 回填、复查、清理三个补齐任务能收敛到正确结果。
 * 需要已建好 devops_ci_dispatch 库（含 T_DISPATCH_TPA_HISTORY* 表），通过环境变量启用：
 * DISPATCH_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3307/devops_ci_dispatch，可选 DISPATCH_TEST_MYSQL_USER/PASSWORD。
 * 测试数据使用独立的项目ID，结束后清理；回填测试会临时清空并恢复同步进度表。
 */
@EnabledIfEnvironmentVariable(named = "DISPATCH_TEST_MYSQL_URL", matches = ".+")
class TpaHistoryMysqlTest {
    private val run = (System.nanoTime() % 1_000_000).toString()
    private val project = "it-tpa-$run"
    private val a1 = "it${run}a1"
    private val a2 = "it${run}a2"
    private val a3 = "it${run}a3"
    private val envId = 900_000_000L + run.toLong()
    private val p1 = "it${run}p1"
    private val p2 = "it${run}p2"
    private val base = LocalDateTime.now().minusDays(1).withNano(0)

    private val buildDao = ThirdPartyAgentBuildDao()
    private val legacy = LegacyAgentBuildViewQuery(dsl, buildDao)
    private val history = HistoryAgentBuildViewQuery(dsl, TpaHistoryQueryDao())
    private val sync = TpaHistorySyncService(
        dslContext = dsl,
        redisOperation = mockk<RedisOperation>(relaxed = true),
        historyDao = TpaHistoryDao(),
        syncEnabled = false,
        readEnabled = true,
        workers = 2,
        scanBatchSize = 3,
        scanBudgetSeconds = 30
    )
    private var clock = 0L
    private val builds = mutableSetOf<String>()

    @BeforeEach
    fun prepare() = cleanup()

    @AfterEach
    fun cleanup() {
        listOf<Table<*>>(S, H, B, P, J).forEach { table ->
            dsl.deleteFrom(table).where(DSL.field("PROJECT_ID", String::class.java).eq(project)).execute()
        }
    }

    /** 写入一个 Job 并设置可控的时间：ID 越大创建时间越晚，与线上一致 */
    private fun job(
        agent: String,
        build: String,
        vmSeqId: String,
        jobId: String?,
        executeCount: Int = 1,
        pipeline: String = p1,
        env: Long? = envId,
        user: String = "u1",
        status: PipelineTaskStatus = PipelineTaskStatus.DONE,
        seconds: Long = 60,
        interval: Long? = seconds
    ): Long {
        val buildId = "it$run-$build"
        builds.add(buildId)
        val existed = buildDao.get(dsl, buildId, vmSeqId, executeCount) != null
        buildDao.add(
            dslContext = dsl, projectId = project, agentId = agent, pipelineId = pipeline, buildId = buildId,
            vmSeqId = vmSeqId, thirdPartyAgentWorkspace = "/ws/$vmSeqId", pipelineName = "name-$pipeline",
            buildNum = build.filter { it.isDigit() }.toIntOrNull() ?: 0, taskName = "task-$jobId", agentIp = "1.1.1.1",
            nodeId = 1L, dockerInfo = null, executeCount = executeCount, containerHashId = "c$vmSeqId", envId = env,
            ignoreEnvAgentIds = null, jobId = jobId, startUser = user, stageId = "stage-2"
        )
        val record = buildDao.get(dsl, buildId, vmSeqId, executeCount)!!
        clock += 30
        // 重新入队（改派、重试）时 add 只更新原记录，创建时间不变
        val created = if (existed) record.createdTime else base.plusSeconds(clock)
        dsl.update(S)
            .set(S.STATUS, status.status)
            .set(S.CREATED_TIME, created)
            .set(S.UPDATED_TIME, base.plusSeconds(clock + seconds))
            .set(S.TIME_INTERVAL, interval)
            .where(S.ID.eq(record.id)).execute()
        return record.id
    }

    private fun seed() {
        // b1：两个并行 Job 分别在 a1/a2，时间重叠；随后在 a1 上重试一次失败
        job(a1, "b1", "1", "job_1", seconds = 120)
        job(a2, "b1", "2", "job_2", seconds = 40)
        job(a1, "b1", "1", "job_1", executeCount = 2, status = PipelineTaskStatus.FAILURE, seconds = 30)
        // b2：一个运行中，一个排队中
        job(a1, "b2", "1", "job_1", status = PipelineTaskStatus.RUNNING, interval = null)
        job(a2, "b2", "2", "job_2", status = PipelineTaskStatus.QUEUE, interval = null)
        // b3/b4：另一条流水线，b4 的构建机不在环境中
        job(a1, "b3", "1", "job_x", pipeline = p2, user = "u2")
        job(a3, "b4", "1", "job_x", pipeline = p2, env = null, user = "u2")
        job(a2, "b6", "1", "job_1", user = "u3", status = PipelineTaskStatus.FAILURE)
        syncAll()
    }

    private fun syncAll() = builds.forEach { sync.syncBuild(it) }

    private val scopes get() = listOf(
        AgentBuildScope(project, a1, null),
        AgentBuildScope(project, a2, null),
        AgentBuildScope(project, a3, null),
        AgentBuildScope(project, null, envId),
        AgentBuildScope(project, a1, envId)
    )

    private val filters get() = listOf(
        AgentBuildFilter(),
        AgentBuildFilter(pipelineId = p1),
        AgentBuildFilter(jobId = "job_1"),
        AgentBuildFilter(pipelineId = p2, jobId = "job_x"),
        AgentBuildFilter(status = listOf(PipelineTaskStatus.DONE, PipelineTaskStatus.FAILURE)),
        AgentBuildFilter(status = listOf(PipelineTaskStatus.RUNNING)),
        AgentBuildFilter(creator = "u2"),
        AgentBuildFilter(startTime = epoch(base.plusSeconds(60)), endTime = epoch(base.plusSeconds(150)))
    )

    /** 原始查询与展示表查询逐个对比 */
    private fun assertSameAsLegacy() {
        scopes.forEach { scope ->
            filters.forEach { filter ->
                val tag = "$scope|$filter"
                assertEquals(legacy.countViews(scope, filter), history.countViews(scope, filter), "countViews $tag")
                assertEquals(
                    legacy.listPipelines(scope, filter, 0, 100),
                    history.listPipelines(scope, filter, 0, 100),
                    "listPipelines $tag"
                )
                assertEquals(
                    legacy.listJobs(scope, filter, 0, 100).map { it.withoutAvg() },
                    history.listJobs(scope, filter, 0, 100).map { it.withoutAvg() },
                    "listJobs $tag"
                )
                val legacyBuilds = legacy.listBuilds(scope, filter, 0, 100)
                assertEquals(legacyBuilds, history.listBuilds(scope, filter, 0, 100), "listBuilds $tag")
                val buildKeys = legacyBuilds.map { it.buildId!! to it.executeCount }
                assertEquals(legacy.buildElapsed(scope, buildKeys), history.buildElapsed(scope, buildKeys), "elapsed $tag")
                // 分页
                assertEquals(
                    legacy.listBuilds(scope, filter, 1, 2),
                    history.listBuilds(scope, filter, 1, 2),
                    "listBuilds page $tag"
                )
            }
            listOf(p1 to "job_1", p1 to "job_2", p2 to "job_x").forEach { (pipeline, jobId) ->
                listOf(
                    AgentBuildFilter(pipelineId = pipeline, jobId = jobId),
                    AgentBuildFilter(pipelineId = pipeline, jobId = jobId, startTime = epoch(base.plusSeconds(100)))
                ).forEach { filter ->
                    assertEquals(legacy.countJobRuns(scope, filter), history.countJobRuns(scope, filter), "jobRuns $scope")
                    assertEquals(
                        legacy.listJobRuns(scope, filter, 0, 100).map { it.comparable() },
                        history.listJobRuns(scope, filter, 0, 100).map { it.comparable() },
                        "listJobRuns $scope $filter"
                    )
                }
            }
            listOf(p1, p2).forEach { pipeline ->
                listOf(
                    AgentBuildFilter(),
                    AgentBuildFilter(status = listOf(PipelineTaskStatus.DONE)),
                    AgentBuildFilter(endTime = epoch(base.plusSeconds(100)))
                ).forEach { filter ->
                    assertEquals(
                        legacy.countPipelineBuilds(scope, pipeline, filter),
                        history.countPipelineBuilds(scope, pipeline, filter),
                        "countPipelineBuilds $scope $pipeline $filter"
                    )
                    assertEquals(
                        legacy.listPipelineBuilds(scope, pipeline, filter, 0, 100),
                        history.listPipelineBuilds(scope, pipeline, filter, 0, 100),
                        "listPipelineBuilds $scope $pipeline $filter"
                    )
                }
            }
            assertEquals(
                legacy.listBuildJobs(scope, builds).map { it.comparable() }.sortedBy { it.first() as Long },
                history.listBuildJobs(scope, builds).map { it.comparable() }.sortedBy { it.first() as Long },
                "listBuildJobs $scope"
            )
            builds.forEach { buildId ->
                listOf(1, 2).forEach { executeCount ->
                    listOf(AgentBuildFilter(), AgentBuildFilter(status = listOf(PipelineTaskStatus.FAILURE))).forEach { f ->
                        assertEquals(
                            legacy.countBuildRuns(scope, buildId, executeCount, f),
                            history.countBuildRuns(scope, buildId, executeCount, f),
                            "countBuildRuns $scope $buildId"
                        )
                        assertEquals(
                            legacy.listBuildRuns(scope, buildId, executeCount, f, 0, 100).map { it.comparable() },
                            history.listBuildRuns(scope, buildId, executeCount, f, 0, 100).map { it.comparable() },
                            "listBuildRuns $scope $buildId"
                        )
                    }
                }
            }
            listOf(null, "one", "NAME").forEach { keyword ->
                assertEquals(
                    legacy.pipelineOptions(scope, keyword).toSet(),
                    history.pipelineOptions(scope, keyword).toSet(),
                    "pipelineOptions $scope $keyword"
                )
            }
            listOf(null, "job_x", "TASK").forEach { keyword ->
                assertEquals(
                    legacy.jobOptions(scope, keyword).toSet(),
                    history.jobOptions(scope, keyword).toSet(),
                    "jobOptions $scope $keyword"
                )
            }
            listOf(null, "u2", "U").forEach { keyword ->
                assertEquals(
                    legacy.creatorOptions(scope, keyword).toSet(),
                    history.creatorOptions(scope, keyword).toSet(),
                    "creatorOptions $scope $keyword"
                )
            }
        }
        listOf(a1, a2, a3).forEach { agent ->
            listOf(null, 1, 2, 3, 4).forEach { status ->
                listOf(null, p1).forEach { pipeline ->
                    assertEquals(
                        legacy.countAgentBuilds(agent, status, pipeline),
                        history.countAgentBuilds(agent, status, pipeline),
                        "countAgentBuilds $agent $status $pipeline"
                    )
                    assertEquals(
                        legacy.listAgentBuilds(agent, status, pipeline, 0, 100).map { it.responseFields() },
                        history.listAgentBuilds(agent, status, pipeline, 0, 100).map { it.responseFields() },
                        "listAgentBuilds $agent $status $pipeline"
                    )
                }
            }
        }
    }

    @Test
    fun historyQueriesMatchTheOriginalQueries() {
        seed()
        assertSameAsLegacy()
        assertMatchesRebuild()
    }

    @Test
    fun incrementalChangesStayConsistent() {
        seed()
        // 改派：b2 的第二个 Job 重新排队到 a1（add 会更新原记录的构建机）
        job(a1, "b2", "2", "job_2", status = PipelineTaskStatus.QUEUE, interval = null)
        // 领取并完成
        dsl.update(S).set(S.STATUS, PipelineTaskStatus.DONE.status).set(S.TIME_INTERVAL, 45L)
            .where(S.BUILD_ID.eq("it$run-b2")).execute()
        // misc 清理构建数据
        dsl.deleteFrom(S).where(S.BUILD_ID.eq("it$run-b3")).execute()
        // 同一构建重复同步、乱序同步都不影响结果
        syncAll()
        syncAll()
        assertSameAsLegacy()
        assertMatchesRebuild()

        // 清空构建 b1 在 a2 上的执行：a2 的流水线最新构建需要重新计算
        dsl.deleteFrom(S).where(S.BUILD_ID.eq("it$run-b6")).execute()
        syncAll()
        assertSameAsLegacy()
        assertMatchesRebuild()
    }

    @Test
    fun jobAverageOnlyCountsFinishedRuns() {
        seed()
        val running = history.listJobs(AgentBuildScope(project, a1, null), AgentBuildFilter(pipelineId = p1), 0, 10)
            .single { it.jobId == "job_1" }
        // a1 上 job_1：b1 两次执行 120/30 秒，b2 运行中不计入
        assertEquals(3, running.buildCount)
        assertEquals(75L, running.avgTimeInterval)
    }

    @Test
    fun rowsWithoutJobIdOnlyShowInPipelineAndBuildViews() {
        seed()
        job(a1, "b7", "1", null)
        syncAll()
        val scope = AgentBuildScope(project, a1, null)
        val (pipelines, jobs, buildCount) = history.countViews(scope, AgentBuildFilter())
        assertEquals(legacy.listPipelines(scope, AgentBuildFilter(), 0, 100).size.toLong(), pipelines)
        assertEquals(legacy.listJobs(scope, AgentBuildFilter(), 0, 100).size.toLong(), jobs)
        // 总数与列表口径一致，包含没有 jobId 的构建
        assertEquals(legacy.listBuilds(scope, AgentBuildFilter(), 0, 100).size.toLong(), buildCount)
        assertEquals(history.listBuilds(scope, AgentBuildFilter(), 0, 100).size.toLong(), buildCount)
    }

    @Test
    fun backgroundTasksConvergeFromScratch() {
        val savedProgress = dsl.selectFrom(SYNC).fetch()
        try {
            seed()
            val incremental = snapshot()
            dsl.deleteFrom(SYNC).where(SYNC.SYNC_KEY.isNotNull).execute()
            listOf<Table<*>>(H, B, P, J).forEach { table ->
                dsl.deleteFrom(table).where(DSL.field("PROJECT_ID", String::class.java).eq(project)).execute()
            }
            sync.refreshReadable()
            assertFalse(sync.readable())
            // 回填：扫描源表全部数据，追上目标后可读
            sync.scanNewRows()
            sync.refreshReadable()
            assertTrue(sync.readable())
            assertEquals(incremental, snapshot())

            // 复查：源表状态变了但没有标记（例如节点重启丢失），一分钟未更新的进行中行会被补齐
            dsl.update(H).set(H.UPDATED_TIME, LocalDateTime.now().minusMinutes(5))
                .where(H.BUILD_ID.eq("it$run-b2")).execute()
            dsl.update(S).set(S.STATUS, PipelineTaskStatus.DONE.status).where(S.BUILD_ID.eq("it$run-b2")).execute()
            repeat(3) { sync.reconcileActive() }
            assertEquals(
                setOf(PipelineTaskStatus.DONE.status),
                dsl.select(H.STATUS).from(H).where(H.BUILD_ID.eq("it$run-b2")).fetchSet(H.STATUS)
            )

            // 清理：源表中已删除的构建从展示表移除
            dsl.deleteFrom(S).where(S.BUILD_ID.eq("it$run-b4")).execute()
            dsl.deleteFrom(SYNC).where(SYNC.SYNC_KEY.eq(TpaHistorySyncService.ORPHAN_CURSOR)).execute()
            repeat(5) { sync.sweepOrphans() }
            assertEquals(0, dsl.fetchCount(H, H.BUILD_ID.eq("it$run-b4")))
            assertEquals(0, dsl.fetchCount(P, P.SCOPE_KEY.eq(TpaHistoryScope.agent(a3))))
            assertSameAsLegacy()
            assertMatchesRebuild()
        } finally {
            dsl.deleteFrom(SYNC).where(SYNC.SYNC_KEY.isNotNull).execute()
            savedProgress.forEach { dsl.insertInto(SYNC).set(it).execute() }
        }
    }

    /** 增量维护的汇总与从零全量重建一致 */
    private fun assertMatchesRebuild() {
        val incremental = snapshot()
        listOf<Table<*>>(H, B, P, J).forEach { table ->
            dsl.deleteFrom(table).where(DSL.field("PROJECT_ID", String::class.java).eq(project)).execute()
        }
        syncAll()
        assertEquals(incremental, snapshot())
    }

    private fun snapshot(): List<List<Any?>> = listOf<Table<*>>(H, B, P, J).flatMap { table ->
        dsl.selectFrom(table).where(DSL.field("PROJECT_ID", String::class.java).eq(project)).fetch()
            .map { record -> listOf(table.name) + record.intoList() }
            .sortedBy { it.toString() }
    }

    private fun TDispatchThirdpartyAgentBuildRecord.comparable(): List<Any?> = listOf(
        id, buildId, executeCount, vmSeqId, status, createdTime, updatedTime, buildNum, startUser, agentId,
        taskName, stageId, pipelineId, pipelineName, projectId, workspace, jobId
    )

    /** 构建机任务列表接口返回的字段，原查询只查了这些列 */
    private fun TDispatchThirdpartyAgentBuildRecord.responseFields(): List<Any?> = listOf(
        id, projectId, agentId, pipelineId, pipelineName, buildId, buildNum, vmSeqId, taskName, status,
        createdTime, updatedTime, workspace
    )

    private fun TPAPipelineBuild.withoutAvg() = copy(avgTimeInterval = null)

    private fun epoch(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toEpochSecond()

    companion object {
        private val S = TDispatchThirdpartyAgentBuild.T_DISPATCH_THIRDPARTY_AGENT_BUILD
        private val H = TDispatchTpaHistory.T_DISPATCH_TPA_HISTORY
        private val B = TDispatchTpaHistoryBuild.T_DISPATCH_TPA_HISTORY_BUILD
        private val P = TDispatchTpaHistoryPipeline.T_DISPATCH_TPA_HISTORY_PIPELINE
        private val J = TDispatchTpaHistoryJob.T_DISPATCH_TPA_HISTORY_JOB
        private val SYNC = TDispatchTpaHistorySync.T_DISPATCH_TPA_HISTORY_SYNC

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @JvmStatic
        @BeforeAll
        fun connect() {
            mockkObject(I18nUtil)
            every { I18nUtil.getCodeLanMessage(any()) } answers { firstArg<String>() }
            val url = System.getenv("DISPATCH_TEST_MYSQL_URL")
            dataSource = HikariDataSource().apply {
                // 与服务配置一致：去掉 ONLY_FULL_GROUP_BY，原始查询依赖它
                jdbcUrl = url + (if (url.contains("?")) "&" else "?") +
                    "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&sessionVariables=sql_mode=" +
                    "%27STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO," +
                    "NO_ENGINE_SUBSTITUTION%27"
                username = System.getenv("DISPATCH_TEST_MYSQL_USER") ?: "root"
                password = System.getenv("DISPATCH_TEST_MYSQL_PASSWORD") ?: "root"
                maximumPoolSize = 8
            }
            dsl = DSL.using(dataSource, SQLDialect.MYSQL).apply { settings().isRenderSchema = false }
        }

        @JvmStatic
        @AfterAll
        fun disconnect() {
            dataSource.close()
            unmockkObject(I18nUtil)
        }
    }
}
