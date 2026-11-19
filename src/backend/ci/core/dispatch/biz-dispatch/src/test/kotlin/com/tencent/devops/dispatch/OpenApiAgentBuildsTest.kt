package com.tencent.devops.dispatch

import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.service.ThirdPartyAgentBuildService
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class OpenApiAgentBuildsTest {
    private val dao = mockk<ThirdPartyAgentBuildDao>()
    private val service = ThirdPartyAgentBuildService(mockk(), mockk(), dao)
    private val running = PipelineTaskStatus.RUNNING.status
    private val done = PipelineTaskStatus.DONE.status

    @Test
    fun activeStatusIsOneQueryWithExactCountAndInMemoryPaging() {
        every { dao.listOpenApiBuilds(any(), "a", running, null, 0, 1001) } returns rows(30, 20, 10)

        val page = service.listAgentBuilds("a", "RUNNING", null, page = 2, pageSize = 2, realtimeActive = true)

        assertEquals(3L, page.count)
        assertEquals(listOf("b10"), page.records.map { it.buildId })
        assertEquals("RUNNING", page.records.single().status)
        verify(exactly = 1) { dao.listOpenApiBuilds(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { dao.countOpenApiBuilds(any(), any(), any(), any()) }
    }

    @Test
    fun idleAgentReturnsEmptyWithoutCounting() {
        every { dao.listOpenApiBuilds(any(), "a", PipelineTaskStatus.QUEUE.status, "p", 0, 1001) } returns emptyList()

        val page = service.listAgentBuilds("a", "QUEUE", "p", page = 1, pageSize = 20, realtimeActive = true)

        assertEquals(0L, page.count)
        assertEquals(0, page.records.size)
        verify(exactly = 0) { dao.countOpenApiBuilds(any(), any(), any(), any()) }
    }

    @Test
    fun tooManyActiveRowsFallBackToSqlPaging() {
        every { dao.listOpenApiBuilds(any(), "a", running, null, 0, 1001) } returns rows(*LongArray(1001) { it + 1L })
        every { dao.listOpenApiBuilds(any(), "a", running, null, 20, 20) } returns rows(5)
        every { dao.countOpenApiBuilds(any(), "a", running, null) } returns 1500L

        val page = service.listAgentBuilds("a", "RUNNING", null, page = 2, pageSize = 20, realtimeActive = true)

        assertEquals(1500L, page.count)
        assertEquals(listOf("b5"), page.records.map { it.buildId })
    }

    @Test
    fun historyDerivesTheCountOnTheLastPage() {
        // 首页不满：总数直接得出
        every { dao.listOpenApiBuilds(any(), "a", null, null, 0, 3) } returns rows(9, 8)
        assertEquals(2L, service.listAgentBuilds("a", null, null, page = 1, pageSize = 2, realtimeActive = true).count)
        verify(exactly = 0) { dao.countOpenApiBuilds(any(), any(), any(), any()) }

        // 满页需要计数；非空末页直接得出
        every { dao.listOpenApiBuilds(any(), "a", done, null, 0, 3) } returns rows(9, 8, 7)
        every { dao.listOpenApiBuilds(any(), "a", done, null, 2, 3) } returns rows(7, 6, 5)
        every { dao.listOpenApiBuilds(any(), "a", done, null, 4, 3) } returns rows(5)
        every { dao.countOpenApiBuilds(any(), "a", done, null) } returns 5L
        val first = service.listAgentBuilds("a", "DONE", null, page = 1, pageSize = 2, realtimeActive = true)
        val second = service.listAgentBuilds("a", "DONE", null, page = 2, pageSize = 2, realtimeActive = true)
        val last = service.listAgentBuilds("a", "DONE", null, page = 3, pageSize = 2, realtimeActive = true)
        assertEquals(listOf(5L, 5L, 5L), listOf(first.count, second.count, last.count))
        assertEquals(listOf("b9", "b8"), first.records.map { it.buildId })
        assertEquals(listOf("b5"), last.records.map { it.buildId })
        verify(exactly = 2) { dao.countOpenApiBuilds(any(), "a", done, null) }
    }

    @Test
    fun emptyPageBeyondTheEndStillReportsTheRealCount() {
        every { dao.listOpenApiBuilds(any(), "a", done, null, 40, 21) } returns emptyList()
        every { dao.countOpenApiBuilds(any(), "a", done, null) } returns 7L
        // 未知状态与原接口一致按 DONE 处理
        val page = service.listAgentBuilds("a", "unknown", null, page = 3, pageSize = 20, realtimeActive = true)
        assertEquals(7L, page.count)
        assertEquals(0, page.records.size)
    }

    private fun rows(vararg ids: Long): List<TDispatchThirdpartyAgentBuildRecord> = ids.map { id ->
        TDispatchThirdpartyAgentBuildRecord().apply {
            this.id = id
            projectId = "project"
            agentId = "a"
            pipelineId = "p"
            pipelineName = "pipeline"
            buildId = "b$id"
            buildNum = id.toInt()
            vmSeqId = "1"
            taskName = "job"
            status = PipelineTaskStatus.RUNNING.status
            createdTime = LocalDateTime.of(2026, 11, 19, 12, 0)
            updatedTime = LocalDateTime.of(2026, 11, 19, 12, 1)
            workspace = "/data"
        }
    }
}
