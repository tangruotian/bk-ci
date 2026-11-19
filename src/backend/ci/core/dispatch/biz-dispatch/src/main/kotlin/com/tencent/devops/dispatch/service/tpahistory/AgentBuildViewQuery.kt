package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuild
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import java.time.LocalDateTime

/**
 * 节点/环境构建记录的查询范围。普通环境和节点支持跨项目查询，所有创作环境按 projectId 隔离。
 */
data class AgentBuildScope(val projectId: String, val agentId: String?, val envId: Long?) {
    val agent: String? = agentId?.takeIf { it.isNotBlank() }
    fun isEmpty() = agent == null && envId == null
}

data class AgentBuildFilter(
    val startTime: Long? = null,
    val endTime: Long? = null,
    val pipelineId: String? = null,
    val jobId: String? = null,
    val creator: String? = null,
    val status: List<PipelineTaskStatus>? = null
)

/**
 * 构建记录展示所需的查询。[LegacyAgentBuildViewQuery] 直接查源表，[HistoryAgentBuildViewQuery] 查展示表；
 * 返回结构相同，由 [TpaHistorySyncService.readable] 决定使用哪一个。
 */
interface AgentBuildViewQuery {
    /** @return Triple(流水线数, Job 数, 构建数) */
    fun countViews(scope: AgentBuildScope, filter: AgentBuildFilter): Triple<Long, Long, Long>

    fun listPipelines(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int): List<TPAPipelineBuild>

    fun listJobs(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int): List<TPAPipelineBuild>

    fun listBuilds(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int): List<TPAPipelineBuild>

    /** 构建（构建ID + 执行次数）的开始时间和总耗时 */
    fun buildElapsed(
        scope: AgentBuildScope,
        builds: Collection<Pair<String, Int?>>
    ): Map<Pair<String, Int?>, Pair<LocalDateTime, Long>>

    /** Job 视图展开：某个 Job 的执行记录，filter 使用 pipelineId/jobId/时间 */
    fun countJobRuns(scope: AgentBuildScope, filter: AgentBuildFilter): Long

    fun listJobRuns(
        scope: AgentBuildScope,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<TDispatchThirdpartyAgentBuildRecord>

    /** 流水线视图展开：某条流水线的构建（构建ID + 执行次数），filter 使用状态/时间 */
    fun countPipelineBuilds(scope: AgentBuildScope, pipelineId: String, filter: AgentBuildFilter): Long

    fun listPipelineBuilds(
        scope: AgentBuildScope,
        pipelineId: String,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<Pair<String, Int?>>

    /** 构建下的 Job 明细 */
    fun listBuildJobs(scope: AgentBuildScope, buildIds: Set<String>): List<TDispatchThirdpartyAgentBuildRecord>

    /** 构建视图展开：某次构建的 Job 明细，filter 使用状态/时间 */
    fun countBuildRuns(scope: AgentBuildScope, buildId: String, executeCount: Int?, filter: AgentBuildFilter): Long

    fun listBuildRuns(
        scope: AgentBuildScope,
        buildId: String,
        executeCount: Int?,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<TDispatchThirdpartyAgentBuildRecord>

    fun pipelineOptions(scope: AgentBuildScope, keyword: String?): List<Pair<String, String>>

    fun jobOptions(scope: AgentBuildScope, keyword: String?): List<Pair<String, String>>

    fun creatorOptions(scope: AgentBuildScope, keyword: String?): List<String>

    /** 单个构建机的任务列表：同构建机、同构建、同 Job 只取最新一行，带状态时取该状态下的最新一行 */
    fun countAgentBuilds(agentId: String, status: Int?, pipelineId: String?): Long

    fun listAgentBuilds(
        agentId: String,
        status: Int?,
        pipelineId: String?,
        offset: Int,
        limit: Int
    ): List<TDispatchThirdpartyAgentBuildRecord>
}
