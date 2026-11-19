package com.tencent.devops.dispatch.dao

import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.model.dispatch.tables.TDispatchThirdpartyAgentBuild
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.tools.jdbc.MockConnection
import org.jooq.tools.jdbc.MockDataProvider
import org.jooq.tools.jdbc.MockResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * OpenAPI 专用查询的 SQL 形态：索引提示、去重条件和查询列，防止后续改动让空闲 agent 的查询退化为扫全量历史。
 */
class OpenApiAgentBuildQueryTest {
    private val dao = ThirdPartyAgentBuildDao()
    private val sqlList = mutableListOf<String>()
    private val bindingsList = mutableListOf<List<Any?>>()
    private val dslContext = DSL.using(
        MockConnection(
            MockDataProvider { context ->
                sqlList.add(context.sql())
                bindingsList.add(context.bindings().toList())
                arrayOf(emptyResult(context.sql()))
            }
        ),
        SQLDialect.MYSQL
    )

    @Test
    fun activeStatusesUseTheAgentStatusIndex() {
        listOf(PipelineTaskStatus.QUEUE, PipelineTaskStatus.RUNNING).forEach { status ->
            listOf(null, "pipeline").forEach { pipelineId ->
                val (sql, bindings) = list(status.status, pipelineId)
                assertTrue(hint("IDX_AGENTID_STATUS_UPDATE").containsMatchIn(sql), sql)
                assertFalse(hint("idx_agent_id").containsMatchIn(sql), sql)
                // 外层和 NOT EXISTS 里都按同一状态过滤，取"该状态下的最新一行"
                assertEquals(2, bindings.count { it == status.status }, sql)
                assertEquals(pipelineId != null, "pipeline" in bindings, sql)
            }
        }
    }

    @Test
    fun historyScansTheAgentIndexBackwardUnlessFilteredByPipeline() {
        listOf(null, PipelineTaskStatus.DONE.status).forEach { status ->
            val (sql, bindings) = list(status, null)
            assertTrue(hint("idx_agent_id").containsMatchIn(sql), sql)
            assertFalse(hint("IDX_AGENTID_STATUS_UPDATE").containsMatchIn(sql), sql)
            assertEquals(if (status == null) 0 else 2, bindings.count { it == status }, sql)

            val (withPipeline, _) = list(status, "pipeline")
            assertFalse(Regex("force index", RegexOption.IGNORE_CASE).containsMatchIn(withPipeline), withPipeline)
        }
    }

    @Test
    fun sparseFailuresUseTheStatusIndexWhenSearchingAllPipelines() {
        val (sql, bindings) = list(PipelineTaskStatus.FAILURE.status, null)
        assertTrue(hint("IDX_AGENTID_STATUS_UPDATE").containsMatchIn(sql), sql)
        assertFalse(hint("idx_agent_id").containsMatchIn(sql), sql)
        assertEquals(2, bindings.count { it == PipelineTaskStatus.FAILURE.status })
        val (filtered, _) = list(PipelineTaskStatus.FAILURE.status, "pipeline")
        assertFalse(Regex("force index", RegexOption.IGNORE_CASE).containsMatchIn(filtered), filtered)
    }

    @Test
    fun listKeepsTheLatestRowDedupAndOrderButSkipsLargeColumns() {
        val (sql, bindings) = list(PipelineTaskStatus.RUNNING.status, null)
        assertTrue(Regex("not exists", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
        assertTrue(Regex("order by\\s+`t`\\.`ID` desc", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
        assertTrue("agent" in bindings, sql)
        listOf("DOCKER_INFO", "IGNORE_ENV_AGENT_IDS", "ENV_ID", "AGENT_IP").forEach {
            assertFalse(sql.contains("`t`.`$it`"), "$it should not be selected: $sql")
        }
    }

    @Test
    fun countUsesTheStatusIndexWhenFilteredByStatus() {
        sqlList.clear()
        dao.countOpenApiBuilds(dslContext, "agent", PipelineTaskStatus.DONE.status, null)
        val filtered = sqlList.single()
        assertTrue(Regex("count\\(distinct", RegexOption.IGNORE_CASE).containsMatchIn(filtered), filtered)
        assertTrue(hint("IDX_AGENTID_STATUS_UPDATE").containsMatchIn(filtered), filtered)

        sqlList.clear()
        dao.countOpenApiBuilds(dslContext, "agent", null, null)
        val unfiltered = sqlList.single()
        assertFalse(Regex("force index", RegexOption.IGNORE_CASE).containsMatchIn(unfiltered), unfiltered)
    }

    private fun list(status: Int?, pipelineId: String?): Pair<String, List<Any?>> {
        sqlList.clear()
        bindingsList.clear()
        dao.listOpenApiBuilds(dslContext, "agent", status, pipelineId, offset = 0, limit = 10)
        assertEquals(1, sqlList.size)
        return Pair(sqlList.single(), bindingsList.single())
    }

    private fun hint(index: String) = Regex("force index\\s*\\(`?$index`?\\)", RegexOption.IGNORE_CASE)

    private fun emptyResult(sql: String): MockResult {
        val ctx = DSL.using(SQLDialect.MYSQL)
        if (sql.startsWith("select count", ignoreCase = true)) {
            val count = DSL.field("count", Long::class.javaObjectType)
            return MockResult(1, ctx.newResult(count).apply { add(ctx.newRecord(count).values(0L)) })
        }
        return MockResult(0, ctx.newResult(TDispatchThirdpartyAgentBuild.T_DISPATCH_THIRDPARTY_AGENT_BUILD))
    }
}
