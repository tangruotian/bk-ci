package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuild
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 直接查询源表 T_DISPATCH_THIRDPARTY_AGENT_BUILD，展示表的历史数据尚未补齐时使用。
 */
@Component
class LegacyAgentBuildViewQuery @Autowired constructor(
    private val dslContext: DSLContext,
    private val dao: ThirdPartyAgentBuildDao
) : AgentBuildViewQuery {

    override fun countViews(scope: AgentBuildScope, filter: AgentBuildFilter): Triple<Long, Long, Long> =
        dao.countAgentBuildPipelineJob(
            dslContext = dslContext,
            projectId = scope.projectId,
            agentId = scope.agentId,
            envId = scope.envId,
            startTime = filter.startTime,
            endTime = filter.endTime,
            pipelineId = filter.pipelineId,
            jobId = filter.jobId,
            creator = filter.creator,
            status = filter.status
        )

    override fun listPipelines(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int) =
        dao.fetchAgentBuildPipeline(
            dslContext = dslContext,
            projectId = scope.projectId,
            agentId = scope.agentId,
            envId = scope.envId,
            limit = limit,
            offset = offset,
            startTime = filter.startTime,
            endTime = filter.endTime,
            pipelineId = filter.pipelineId,
            creator = filter.creator,
            status = filter.status
        )

    override fun listJobs(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int) =
        dao.fetchAgentBuildPipelineJob(
            dslContext = dslContext,
            projectId = scope.projectId,
            agentId = scope.agentId,
            envId = scope.envId,
            limit = limit,
            offset = offset,
            startTime = filter.startTime,
            endTime = filter.endTime,
            pipelineId = filter.pipelineId,
            jobId = filter.jobId,
            creator = filter.creator,
            status = filter.status
        )

    override fun listBuilds(
        scope: AgentBuildScope,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<TPAPipelineBuild> = dao.fetchAgentBuildPipelineBuild(
        dslContext = dslContext,
        projectId = scope.projectId,
        agentId = scope.agentId,
        envId = scope.envId,
        limit = limit,
        offset = offset,
        startTime = filter.startTime,
        endTime = filter.endTime,
        pipelineId = filter.pipelineId,
        creator = filter.creator,
        status = filter.status
    )

    override fun buildElapsed(
        scope: AgentBuildScope,
        builds: Collection<Pair<String, Int?>>
    ): Map<Pair<String, Int?>, Pair<LocalDateTime, Long>> {
        if (builds.isEmpty()) return emptyMap()
        val wanted = builds.toSet()
        return listBuildJobs(scope, builds.map { it.first }.toSet())
            .groupBy { Pair(it.buildId, it.executeCount) }
            .filterKeys { it in wanted }
            .mapNotNull { (key, rows) ->
                AgentBuildElapsed.calc(rows.asSequence().map { Triple(it.status, it.createdTime, it.updatedTime) })
                    ?.let { key to it }
            }.toMap()
    }

    override fun countJobRuns(scope: AgentBuildScope, filter: AgentBuildFilter) = dao.countAgentBuildsByJob(
        dslContext = dslContext,
        projectId = scope.projectId,
        agentId = scope.agentId,
        envId = scope.envId,
        pipelineId = filter.pipelineId,
        jobId = filter.jobId,
        startTime = filter.startTime,
        endTime = filter.endTime
    )

    override fun listJobRuns(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int) =
        dao.listAgentBuildsByJob(
            dslContext = dslContext,
            projectId = scope.projectId,
            agentId = scope.agentId,
            envId = scope.envId,
            pipelineId = filter.pipelineId,
            jobId = filter.jobId,
            offset = offset,
            limit = limit,
            startTime = filter.startTime,
            endTime = filter.endTime
        )

    override fun countPipelineBuilds(scope: AgentBuildScope, pipelineId: String, filter: AgentBuildFilter) =
        dao.countAgentBuildGroupsByPipeline(
            dslContext = dslContext,
            projectId = scope.projectId,
            agentId = scope.agentId,
            envId = scope.envId,
            pipelineId = pipelineId,
            status = filter.status,
            startTime = filter.startTime,
            endTime = filter.endTime
        )

    override fun listPipelineBuilds(
        scope: AgentBuildScope,
        pipelineId: String,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<Pair<String, Int?>> = dao.listAgentBuildGroupsByPipeline(
        dslContext = dslContext,
        projectId = scope.projectId,
        agentId = scope.agentId,
        envId = scope.envId,
        pipelineId = pipelineId,
        offset = offset,
        limit = limit,
        status = filter.status,
        startTime = filter.startTime,
        endTime = filter.endTime
    ).map { Pair(it.first, it.second as Int?) }

    override fun listBuildJobs(scope: AgentBuildScope, buildIds: Set<String>): List<TDispatchThirdpartyAgentBuildRecord> {
        if (buildIds.isEmpty()) return emptyList()
        return dao.fetchAgentBuildsByBuildId(
            dslContext = dslContext,
            projectId = scope.projectId,
            agentId = scope.agentId,
            envId = scope.envId,
            buildIdList = buildIds
        )
    }

    override fun countBuildRuns(
        scope: AgentBuildScope,
        buildId: String,
        executeCount: Int?,
        filter: AgentBuildFilter
    ) = dao.countAgentBuildGroupsByBuild(
        dslContext = dslContext,
        projectId = scope.projectId,
        agentId = scope.agentId,
        envId = scope.envId,
        buildId = buildId,
        executeCount = executeCount,
        status = filter.status,
        startTime = filter.startTime,
        endTime = filter.endTime
    )

    override fun listBuildRuns(
        scope: AgentBuildScope,
        buildId: String,
        executeCount: Int?,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ) = dao.listAgentBuildGroupsByBuild(
        dslContext = dslContext,
        projectId = scope.projectId,
        agentId = scope.agentId,
        envId = scope.envId,
        buildId = buildId,
        executeCount = executeCount,
        offset = offset,
        limit = limit,
        status = filter.status,
        startTime = filter.startTime,
        endTime = filter.endTime
    )

    override fun pipelineOptions(scope: AgentBuildScope, keyword: String?) =
        dao.fetchPipelineIdAndName(dslContext, scope.projectId, scope.agentId, scope.envId, keyword)

    override fun jobOptions(scope: AgentBuildScope, keyword: String?) =
        dao.fetchJobIdAndName(dslContext, scope.projectId, scope.agentId, scope.envId, keyword)

    override fun creatorOptions(scope: AgentBuildScope, keyword: String?) =
        dao.fetchCreator(dslContext, scope.projectId, scope.agentId, scope.envId, keyword)

    override fun countAgentBuilds(agentId: String, status: Int?, pipelineId: String?) =
        dao.countOpenApiBuilds(dslContext, agentId, status, pipelineId)

    override fun listAgentBuilds(agentId: String, status: Int?, pipelineId: String?, offset: Int, limit: Int) =
        dao.listOpenApiBuilds(dslContext, agentId, status, pipelineId, offset, limit)
}
