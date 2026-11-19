package com.tencent.devops.dispatch.service.tpahistory

import com.tencent.devops.dispatch.dao.TpaHistoryQueryDao
import com.tencent.devops.dispatch.dao.TpaHistoryWhere
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuild
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import com.tencent.devops.model.dispatch.tables.records.TDispatchTpaHistoryJobRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 查询展示表。只按构建机或只按环境查看、且没有状态/时间/执行人筛选时（页面默认视图）读汇总表；
 * 其余组合在明细表上按原口径分组。口径差异：没有执行次数的历史数据不展示，总数与列表一样包含没有 jobId 的数据，
 * Job 平均耗时只统计已结束的执行。
 */
@Component
class HistoryAgentBuildViewQuery @Autowired constructor(
    private val dslContext: DSLContext,
    private val dao: TpaHistoryQueryDao
) : AgentBuildViewQuery {

    override fun countViews(scope: AgentBuildScope, filter: AgentBuildFilter): Triple<Long, Long, Long> {
        val scopeKey = scopeKey(scope)
        if (scopeKey == null || !filter.aggregatable()) {
            return dao.countViews(dslContext, where(scope, filter))
        }
        val pipelineId = filter.pipelineId.orNullIfBlank()
        val jobId = filter.jobId.orNullIfBlank()
        if (jobId != null) {
            val pipelineIds = dao.listJobViews(dslContext, scopeKey, pipelineId, jobId, null).map { it.pipelineId }
            val builds = dao.countJobBuilds(dslContext, where(scope, AgentBuildFilter()), pipelineIds, jobId)
            return Triple(pipelineIds.size.toLong(), pipelineIds.size.toLong(), builds)
        }
        if (pipelineId != null) {
            val pipeline = dao.getPipelineView(dslContext, scopeKey, pipelineId) ?: return Triple(0, 0, 0)
            return Triple(1, dao.countJobViews(dslContext, scopeKey, pipelineId), pipeline.buildCount.toLong())
        }
        val (pipelines, builds) = dao.countPipelineViews(dslContext, scopeKey)
        return Triple(pipelines, dao.countJobViews(dslContext, scopeKey, null), builds)
    }

    override fun listPipelines(
        scope: AgentBuildScope,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<TPAPipelineBuild> {
        // 流水线视图不按 Job 过滤，与原接口一致
        val scopeKey = scopeKey(scope)
        if (scopeKey == null || !filter.aggregatable()) {
            return dao.listPipelinesByRows(dslContext, where(scope, filter.copy(jobId = null)), offset, limit)
        }
        return dao.listPipelineViews(dslContext, scopeKey, filter.pipelineId.orNullIfBlank(), null, offset, limit)
            .map { TpaHistoryQueryDao.pipelineView(it.projectId, it.pipelineId, it.pipelineName, it.buildCount, it.lastBuildId) }
    }

    override fun listJobs(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int): List<TPAPipelineBuild> {
        val scopeKey = scopeKey(scope)
        if (scopeKey == null || !filter.aggregatable()) {
            return dao.listJobsByRows(dslContext, where(scope, filter), offset, limit)
        }
        return dao.listJobViews(
            dslContext, scopeKey, filter.pipelineId.orNullIfBlank(), filter.jobId.orNullIfBlank(), null, offset, limit
        ).map { it.toJobView() }
    }

    override fun listBuilds(
        scope: AgentBuildScope,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<TPAPipelineBuild> {
        // 构建视图不按 Job 过滤，与原接口一致
        val scopeKey = scopeKey(scope)
        if (scopeKey == null || !filter.aggregatable()) {
            return dao.listBuildsByRows(dslContext, where(scope, filter.copy(jobId = null)), offset, limit)
        }
        return dao.listBuildViews(dslContext, scopeKey, filter.pipelineId.orNullIfBlank(), offset, limit).map {
            TpaHistoryQueryDao.buildView(
                it.projectId, it.pipelineId, it.pipelineName, it.buildId, it.executeCount, it.buildNum
            )
        }
    }

    override fun buildElapsed(
        scope: AgentBuildScope,
        builds: Collection<Pair<String, Int?>>
    ): Map<Pair<String, Int?>, Pair<LocalDateTime, Long>> {
        if (builds.isEmpty()) return emptyMap()
        val wanted = builds.toSet()
        val buildIds = builds.map { it.first }.toSet()
        val scopeKey = scopeKey(scope)
        if (scopeKey != null) {
            // 耗时在同步时已按构建算好
            return dao.getBuildViews(dslContext, scopeKey, buildIds).mapNotNull { view ->
                val key = Pair<String, Int?>(view.buildId, view.executeCount)
                val startTime = view.startTime
                val totalTime = view.totalTime
                if (key !in wanted || startTime == null || totalTime == null) null else key to Pair(startTime, totalTime)
            }.toMap()
        }
        return listBuildJobs(scope, buildIds)
            .groupBy { Pair(it.buildId, it.executeCount) }
            .filterKeys { it in wanted }
            .mapNotNull { (key, rows) ->
                AgentBuildElapsed.calc(rows.asSequence().map { Triple(it.status, it.createdTime, it.updatedTime) })
                    ?.let { key to it }
            }.toMap()
    }

    override fun countJobRuns(scope: AgentBuildScope, filter: AgentBuildFilter) =
        dao.countRows(dslContext, where(scope, jobRunFilter(filter)))

    override fun listJobRuns(scope: AgentBuildScope, filter: AgentBuildFilter, offset: Int, limit: Int) =
        dao.listRows(dslContext, where(scope, jobRunFilter(filter)), offset, limit)

    override fun countPipelineBuilds(scope: AgentBuildScope, pipelineId: String, filter: AgentBuildFilter): Long {
        val scopeKey = scopeKey(scope)
        val buildFilter = AgentBuildFilter(
            startTime = filter.startTime, endTime = filter.endTime, pipelineId = pipelineId, status = filter.status
        )
        if (scopeKey == null || !buildFilter.aggregatable()) {
            return dao.countBuildGroups(dslContext, where(scope, buildFilter))
        }
        return dao.getPipelineView(dslContext, scopeKey, pipelineId)?.buildCount?.toLong() ?: 0L
    }

    override fun listPipelineBuilds(
        scope: AgentBuildScope,
        pipelineId: String,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<Pair<String, Int?>> {
        val scopeKey = scopeKey(scope)
        val buildFilter = AgentBuildFilter(
            startTime = filter.startTime, endTime = filter.endTime, pipelineId = pipelineId, status = filter.status
        )
        if (scopeKey == null || !buildFilter.aggregatable()) {
            return dao.listBuildGroups(dslContext, where(scope, buildFilter), offset, limit)
        }
        return dao.listBuildViews(dslContext, scopeKey, pipelineId, offset, limit).map { Pair(it.buildId, it.executeCount) }
    }

    override fun listBuildJobs(scope: AgentBuildScope, buildIds: Set<String>): List<TDispatchThirdpartyAgentBuildRecord> {
        if (buildIds.isEmpty()) return emptyList()
        return dao.listRows(dslContext, where(scope, buildIds = buildIds))
    }

    override fun countBuildRuns(
        scope: AgentBuildScope,
        buildId: String,
        executeCount: Int?,
        filter: AgentBuildFilter
    ): Long {
        // 没有执行次数的历史数据不在展示表中
        executeCount ?: return 0L
        return dao.countRows(dslContext, buildRunWhere(scope, buildId, executeCount, filter))
    }

    override fun listBuildRuns(
        scope: AgentBuildScope,
        buildId: String,
        executeCount: Int?,
        filter: AgentBuildFilter,
        offset: Int,
        limit: Int
    ): List<TDispatchThirdpartyAgentBuildRecord> {
        executeCount ?: return emptyList()
        return dao.listRows(dslContext, buildRunWhere(scope, buildId, executeCount, filter), offset, limit)
    }

    override fun pipelineOptions(scope: AgentBuildScope, keyword: String?): List<Pair<String, String>> {
        val scopeKey = scopeKey(scope) ?: return dao.pipelineOptionsByRows(dslContext, where(scope), keyword)
        return dao.listPipelineViews(dslContext, scopeKey, null, keyword).map { Pair(it.pipelineId, it.pipelineName) }
    }

    override fun jobOptions(scope: AgentBuildScope, keyword: String?): List<Pair<String, String>> {
        val scopeKey = scopeKey(scope) ?: return dao.jobOptionsByRows(dslContext, where(scope), keyword)
        // 同一 jobId 可能出现在多条流水线中，按最新的一条去重，与原接口按 jobId 分组一致
        return dao.listJobViews(dslContext, scopeKey, null, null, keyword)
            .distinctBy { it.jobId }
            .map { Pair(it.jobId, it.taskName) }
    }

    override fun creatorOptions(scope: AgentBuildScope, keyword: String?): List<String> {
        val scopeKey = scopeKey(scope) ?: return dao.creatorOptionsByRows(dslContext, where(scope), keyword)
        val creators = dao.listCreators(dslContext, scopeKey)
        return if (keyword == null) creators else creators.filter { it.contains(keyword, ignoreCase = true) }
    }

    override fun countAgentBuilds(agentId: String, status: Int?, pipelineId: String?) =
        dao.countAgentRows(dslContext, agentId, status, pipelineId)

    override fun listAgentBuilds(agentId: String, status: Int?, pipelineId: String?, offset: Int, limit: Int) =
        dao.listAgentRows(dslContext, agentId, status, pipelineId, offset, limit)

    private fun jobRunFilter(filter: AgentBuildFilter) = AgentBuildFilter(
        startTime = filter.startTime, endTime = filter.endTime, pipelineId = filter.pipelineId, jobId = filter.jobId
    )

    private fun buildRunWhere(scope: AgentBuildScope, buildId: String, executeCount: Int, filter: AgentBuildFilter) =
        where(
            scope = scope,
            filter = AgentBuildFilter(startTime = filter.startTime, endTime = filter.endTime, status = filter.status),
            buildIds = listOf(buildId),
            executeCount = executeCount
        )

    /** 只按构建机或只按环境查看时才有汇总数据 */
    private fun scopeKey(scope: AgentBuildScope): String? = when {
        scope.agent != null && scope.envId == null -> TpaHistoryScope.agent(scope.agent)
        scope.agent == null && scope.envId != null -> TpaHistoryScope.env(TpaHistoryScope.envKey(scope.envId, scope.projectId))
        else -> null
    }

    private fun where(
        scope: AgentBuildScope,
        filter: AgentBuildFilter = AgentBuildFilter(),
        buildIds: Collection<String>? = null,
        executeCount: Int? = null
    ) = TpaHistoryWhere(
        agentId = scope.agent,
        envKey = scope.envId?.let { TpaHistoryScope.envKey(it, scope.projectId) },
        startTime = filter.startTime,
        endTime = filter.endTime,
        pipelineId = filter.pipelineId,
        jobId = filter.jobId,
        creator = filter.creator,
        status = filter.status?.map { it.status },
        buildIds = buildIds,
        executeCount = executeCount
    )

    private fun AgentBuildFilter.aggregatable() =
        startTime == null && endTime == null && creator.isNullOrBlank() && status.isNullOrEmpty()

    private fun String?.orNullIfBlank() = this?.takeIf { it.isNotBlank() }

    private fun TDispatchTpaHistoryJobRecord.toJobView() = TPAPipelineBuild(
        projectId = projectId,
        pipelineId = pipelineId,
        pipelineName = pipelineName,
        jobId = jobId,
        jobName = taskName,
        buildCount = runCount,
        lastBuildTime = lastCreatedTime,
        avgTimeInterval = if (timeCount > 0) timeSum / timeCount else null,
        lastContainerId = lastVmSeqId.toLongOrNull(),
        stageId = lastStageId,
        stageNumb = null,
        buildId = null,
        executeCount = null
    )
}
