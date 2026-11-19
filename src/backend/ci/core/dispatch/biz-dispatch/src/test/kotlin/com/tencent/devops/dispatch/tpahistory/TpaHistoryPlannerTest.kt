package com.tencent.devops.dispatch.tpahistory

import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.service.tpahistory.TpaHistoryPlanner
import com.tencent.devops.dispatch.service.tpahistory.TpaHistoryRow
import com.tencent.devops.dispatch.service.tpahistory.TpaHistoryScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class TpaHistoryPlannerTest {
    private val t0 = LocalDateTime.of(2026, 11, 19, 10, 0, 0)
    private val a1 = TpaHistoryScope.agent("a1")
    private val a2 = TpaHistoryScope.agent("a2")
    private val env = TpaHistoryScope.env("7")

    private fun row(
        id: Long,
        agentId: String = "a1",
        buildId: String = "b1",
        executeCount: Int = 1,
        vmSeqId: String = "1",
        jobId: String? = "job_1",
        status: PipelineTaskStatus = PipelineTaskStatus.QUEUE,
        start: Long = 0,
        end: Long = 0,
        interval: Long? = null,
        envKey: String? = "7"
    ) = TpaHistoryRow(
        id = id, projectId = "p", agentId = agentId, envKey = envKey, pipelineId = "pipe", pipelineName = "pipeline",
        buildId = buildId, buildNum = 3, executeCount = executeCount, vmSeqId = vmSeqId, jobId = jobId,
        taskName = "task-$vmSeqId", stageId = "stage-2", status = status.status, startUser = "u", workspace = "/w",
        timeInterval = interval, createdTime = t0.plusSeconds(start), updatedTime = t0.plusSeconds(end)
    )

    private fun plan(old: List<TpaHistoryRow>, new: List<TpaHistoryRow>) =
        TpaHistoryPlanner.plan(TpaHistoryPlanner.withLatestFlags(old), TpaHistoryPlanner.withLatestFlags(new))

    @Test
    fun latestFlagsFollowTheOriginalNotExistsNewerRowRule() {
        val flagged = TpaHistoryPlanner.withLatestFlags(
            listOf(
                row(1, executeCount = 1, status = PipelineTaskStatus.FAILURE),
                row(2, executeCount = 2, status = PipelineTaskStatus.DONE),
                row(3, executeCount = 3, status = PipelineTaskStatus.FAILURE),
                row(4, agentId = "a2", executeCount = 4, status = PipelineTaskStatus.FAILURE)
            )
        ).associateBy { it.id }
        assertEquals(listOf(false, false, true, true), (1L..4L).map { flagged[it]!!.latest })
        // 按状态时取该状态下的最新一行，不同构建机分别计算
        assertEquals(listOf(false, true, true, true), (1L..4L).map { flagged[it]!!.statusLatest })
    }

    @Test
    fun newBuildCountsOncePerScopeAndRepeatedSyncIsANoop() {
        val rows = listOf(row(1, vmSeqId = "1"), row(2, vmSeqId = "2", jobId = "job_2"))
        val first = plan(emptyList(), rows)
        assertEquals(2, first.upserts.size)
        assertEquals(setOf(a1, env), first.builds.map { it.key.scopeKey }.toSet())
        assertEquals(listOf(1, 1), first.pipelines.map { it.countDelta })
        assertTrue(first.pipelines.all { it.last?.lastId == 2L && it.last?.buildId == "b1" && it.removedLastId == null })
        assertEquals(4, first.jobs.size)
        assertTrue(first.jobs.all { it.runDelta == 1 && it.timeCountDelta == 0 })

        assertTrue(plan(rows, rows).isEmpty())
    }

    @Test
    fun claimOnlyTouchesTheDetailRow() {
        val queued = listOf(row(1))
        val running = listOf(row(1, status = PipelineTaskStatus.RUNNING, end = 5))
        val change = plan(queued, running)
        assertEquals(1, change.upserts.size)
        assertTrue(change.builds.isEmpty() && change.pipelines.isEmpty() && change.jobs.isEmpty())
    }

    @Test
    fun finishingRecomputesTheBuildTimeAndAddsJobTime() {
        val running = listOf(
            row(1, vmSeqId = "1", status = PipelineTaskStatus.RUNNING, start = 0),
            row(2, vmSeqId = "2", jobId = "job_2", status = PipelineTaskStatus.DONE, start = 30, end = 90, interval = 60)
        )
        val finished = listOf(
            row(1, vmSeqId = "1", status = PipelineTaskStatus.FAILURE, start = 0, end = 60, interval = 60),
            running[1]
        )
        val change = plan(running, finished)
        val build = change.builds.single { it.key.scopeKey == a1 }.after!!
        // 两个 Job 的区间 [0,60] 与 [30,90] 重叠，合并后为 90 秒
        assertEquals(t0, build.startTime)
        assertEquals(90L, build.totalTime)
        assertTrue(change.pipelines.isEmpty())
        val job = change.jobs.single { it.scopeKey == a1 }
        assertEquals("job_1", job.jobId)
        assertEquals(0, job.runDelta)
        assertEquals(60L, job.timeSumDelta)
        assertEquals(1, job.timeCountDelta)
    }

    @Test
    fun driftMovesTheBuildBetweenAgentsWithoutTouchingTheEnvironment() {
        val before = listOf(row(1, agentId = "a1"))
        val after = listOf(row(1, agentId = "a2"))
        val change = plan(before, after)
        assertNull(change.builds.single { it.key.scopeKey == a1 }.after)
        assertEquals(a2, change.builds.single { it.after != null }.key.scopeKey)
        assertFalse(change.builds.any { it.key.scopeKey == env })

        val old = change.pipelines.single { it.scopeKey == a1 }
        assertEquals(-1, old.countDelta)
        assertEquals(1L, old.removedLastId)
        assertNull(old.last)
        assertEquals(1, change.pipelines.single { it.scopeKey == a2 }.countDelta)
        assertFalse(change.pipelines.any { it.scopeKey == env })

        assertEquals(-1, change.jobs.single { it.scopeKey == a1 }.runDelta)
        assertEquals(1L, change.jobs.single { it.scopeKey == a1 }.removedLastId)
        assertEquals(1, change.jobs.single { it.scopeKey == a2 }.runDelta)
    }

    @Test
    fun retryAddsABuildAndMovesTheLatest() {
        val first = listOf(row(1, status = PipelineTaskStatus.FAILURE, end = 10, interval = 10))
        val retried = first + row(5, executeCount = 2)
        val change = plan(first, retried)
        assertEquals(listOf(2, 2), change.builds.map { it.key.executeCount })
        val pipeline = change.pipelines.single { it.scopeKey == a1 }
        assertEquals(1, pipeline.countDelta)
        assertEquals(5L, pipeline.last?.lastId)
        val job = change.jobs.single { it.scopeKey == a1 }
        assertEquals(1, job.runDelta)
        assertEquals(5L, job.last?.lastId)
        // 原来的行仍在，最新行被新行覆盖，不需要重新计算
        assertNull(job.removedLastId)
    }

    @Test
    fun deletedBuildIsRemovedEverywhere() {
        val rows = listOf(
            row(1, status = PipelineTaskStatus.DONE, end = 10, interval = 10),
            row(2, vmSeqId = "2", jobId = null)
        )
        val change = plan(rows, emptyList())
        assertEquals(listOf(1L, 2L), change.deletes.sorted())
        assertTrue(change.builds.all { it.after == null })
        assertTrue(change.pipelines.all { it.countDelta == -1 && it.removedLastId == 2L })
        // 没有 jobId 的行不计入 Job 视图
        val jobs = change.jobs
        assertEquals(setOf("job_1"), jobs.map { it.jobId }.toSet())
        assertTrue(jobs.all { it.runDelta == -1 && it.timeSumDelta == -10L && it.timeCountDelta == -1 })
    }

    @Test
    fun rowsWithoutEnvironmentOnlyCountForTheAgent() {
        val change = plan(emptyList(), listOf(row(1, envKey = null)))
        assertEquals(setOf(a1), change.builds.map { it.key.scopeKey }.toSet())
        assertEquals(setOf(a1), change.pipelines.map { it.scopeKey }.toSet())
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun mockNames() {
            mockkObject(I18nUtil)
            every { I18nUtil.getCodeLanMessage(any()) } answers { firstArg<String>() }
        }

        @JvmStatic
        @AfterAll
        fun restoreNames() {
            unmockkObject(I18nUtil)
        }
    }
}
