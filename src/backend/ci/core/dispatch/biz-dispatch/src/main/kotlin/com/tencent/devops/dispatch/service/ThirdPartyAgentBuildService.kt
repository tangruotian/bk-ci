package com.tencent.devops.dispatch.service

import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.api.util.timestamp
import com.tencent.devops.common.client.Client
import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.thirdpartyagent.AgentBuildInfo
import com.tencent.devops.dispatch.pojo.thirdpartyagent.AgentPipelineBuildTask
import com.tencent.devops.dispatch.pojo.thirdpartyagent.AgentPipelineContainerBuild
import com.tencent.devops.dispatch.pojo.thirdpartyagent.JobIdAndName
import com.tencent.devops.dispatch.pojo.thirdpartyagent.NodeInfo
import com.tencent.devops.dispatch.pojo.thirdpartyagent.PipelineIdAndName
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuildCountResp
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuildHistory
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuildView
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineReq
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineSearchReq
import com.tencent.devops.dispatch.service.tpahistory.AgentBuildFilter
import com.tencent.devops.dispatch.service.tpahistory.AgentBuildScope
import com.tencent.devops.dispatch.service.tpahistory.AgentBuildViewQuery
import com.tencent.devops.dispatch.service.tpahistory.HistoryAgentBuildViewQuery
import com.tencent.devops.dispatch.service.tpahistory.LegacyAgentBuildViewQuery
import com.tencent.devops.dispatch.service.tpahistory.TpaHistorySyncService
import com.tencent.devops.environment.api.thirdpartyagent.ServiceThirdPartyAgentResource
import com.tencent.devops.environment.pojo.thirdpartyagent.BatchFetchNodeInfoData
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.process.pojo.BatchFetchBuildRecordData
import com.tencent.devops.process.pojo.BatchFetchContainerRecordData
import com.tencent.devops.process.utils.KEY_STAGE
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 节点/环境的构建记录展示。展示表回填完成后查询展示表（[HistoryAgentBuildViewQuery]），之前直接查源表。
 */
@Suppress("NestedBlockDepth")
@Service
class ThirdPartyAgentBuildService @Autowired constructor(
    private val dslContext: DSLContext,
    private val client: Client,
    private val thirdPartyAgentBuildDao: ThirdPartyAgentBuildDao,
    private val legacyQuery: LegacyAgentBuildViewQuery,
    private val historyQuery: HistoryAgentBuildViewQuery,
    private val historySync: TpaHistorySyncService
) {
    private fun viewQuery(): AgentBuildViewQuery = if (historySync.readable()) historyQuery else legacyQuery

    // 普通环境和节点支持跨项目查询，所有创作环境按 projectId 隔离
    fun fetchBuildPipelineView(
        userId: String,
        projectId: String,
        envId: Long?,
        data: TPAPipelineReq
    ): TPAPipelineBuildCountResp {
        if (data.agentId.isNullOrBlank() && data.envId == null) {
            return TPAPipelineBuildCountResp(0L, 0L, 0L, Page(0, 0, 0, emptyList()))
        }
        val query = viewQuery()
        val scope = AgentBuildScope(projectId, data.agentId, envId)
        val pageNotNull = data.page ?: 0
        val pageSizeNotNull = data.pageSize ?: 10
        val sqlLimit = if (pageSizeNotNull != -1) {
            PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull)
        } else {
            null
        }
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 100
        val filter = AgentBuildFilter(
            startTime = data.startTime,
            endTime = data.endTime,
            pipelineId = data.pipelineId,
            jobId = data.jobId,
            creator = data.creator,
            status = data.taskStatusList
        )
        // 只有 Job 视图的统计按 Job 过滤
        val (pipelineCount, jobCount, buildCount) = query.countViews(
            scope = scope,
            filter = if (data.view == TPAPipelineBuildView.JOB) filter else filter.copy(jobId = null)
        )
        val records = when (data.view) {
            TPAPipelineBuildView.PIPELINE -> {
                val buildRecord = query.listPipelines(scope, filter, offset, limit)
                // 查询最近的构建的开始时间
                val buildHistoryMap = client.get(ServiceBuildResource::class).batchFetchBuildRecordStatus(
                    data = BatchFetchBuildRecordData(buildIds = buildRecord.filter { !it.buildId.isNullOrBlank() }
                        .map { it.buildId!! }, executeCount = null)
                ).data?.sortedByDescending { it.startTime }?.groupBy { it.buildId } ?: emptyMap()
                buildRecord.forEach {
                    it.lastBuildTime = buildHistoryMap[it.buildId]?.firstOrNull()?.startTime
                }
                buildRecord
            }

            TPAPipelineBuildView.JOB -> query.listJobs(scope, filter, offset, limit).let { list ->
                // 处理下stage序号计算
                list.forEach { item ->
                    item.stageNumb = item.stageId?.toStageNumb() ?: item.stageId
                }
                list
            }

            TPAPipelineBuildView.BUILD -> {
                val buildRecord = query.listBuilds(scope, filter, offset, limit)
                // 用来计算耗时
                val elapsedMap = query.buildElapsed(
                    scope = scope,
                    builds = buildRecord.filter { !it.buildId.isNullOrBlank() }.map { it.buildId!! to it.executeCount }
                )
                val buildHistoryMap = client.get(ServiceBuildResource::class).batchFetchBuildRecordStatus(
                    data = BatchFetchBuildRecordData(buildIds = buildRecord.filter { !it.buildId.isNullOrBlank() }
                        .map { it.buildId!! }, executeCount = null)
                ).data?.groupBy { it.buildId } ?: emptyMap()
                buildRecord.forEach { record ->
                    val histories = buildHistoryMap[record.buildId]
                    val bh = histories?.let { list ->
                        if (record.executeCount == null) {
                            // record 的 executeCount 为空 则取 executeCount 最大的那条
                            list.maxByOrNull { it.executeCount }
                        } else {
                            // record 有 executeCount：一一对应，精确匹配相同 executeCount
                            list.firstOrNull { it.executeCount == record.executeCount }
                        }
                    }

                    record.buildHistory = bh?.let {
                        val startTimeAndTotal = elapsedMap[Pair(record.buildId!!, record.executeCount)]
                        TPAPipelineBuildHistory(
                            userId = it.startUser,
                            buildNum = record.buildNum,
                            status = it.status,
                            totalTime = startTimeAndTotal?.second,
                            startTime = startTimeAndTotal?.first,
                            executeCount = it.executeCount
                        )
                    }
                }
                buildRecord
            }
        }
        val count = when (data.view) {
            TPAPipelineBuildView.PIPELINE -> pipelineCount
            TPAPipelineBuildView.JOB -> jobCount
            TPAPipelineBuildView.BUILD -> buildCount
        }
        return TPAPipelineBuildCountResp(
            pipelineCount = pipelineCount,
            jobCount = jobCount,
            buildCount = buildCount,
            result = Page(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = count,
                records = records
            )
        )
    }

    @Deprecated("fetchBuildPipelineView")
    fun fetchBuildPipeline(
        projectId: String,
        agentId: String?,
        envId: Long?,
        page: Int?,
        pageSize: Int?,
        startTime: Long?,
        endTime: Long?,
        pipelineId: String?,
        jobId: String?,
        creator: String?,
        status: PipelineTaskStatus?
    ): TPAPipelineBuildCountResp {
        if (agentId.isNullOrBlank() && envId == null) {
            return TPAPipelineBuildCountResp(0L, 0L, 0L, Page(0, 0, 0, emptyList()))
        }
        val query = viewQuery()
        val scope = AgentBuildScope(projectId, agentId, envId)
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: 10
        val sqlLimit = if (pageSizeNotNull != -1) {
            PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull)
        } else {
            null
        }
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 100
        val filter = AgentBuildFilter(
            startTime = startTime,
            endTime = endTime,
            pipelineId = pipelineId,
            jobId = jobId,
            creator = creator,
            status = status?.let { listOf(it) }
        )
        val (pipelineCount, jobCount, buildCount) = query.countViews(scope, filter)
        return TPAPipelineBuildCountResp(
            pipelineCount = pipelineCount,
            jobCount = jobCount,
            buildCount = buildCount,
            result = Page(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = jobCount,
                records = query.listJobs(scope, filter, offset, limit).let { list ->
                    // 处理下stage序号计算
                    list.forEach { item ->
                        item.stageNumb = item.stageId?.toStageNumb() ?: item.stageId
                    }
                    list
                }
            )
        )
    }

    fun fetchAgentBuildsByJob(
        userId: String,
        projectId: String,
        envId: Long?,
        data: TPAPipelineSearchReq
    ): Page<AgentPipelineContainerBuild> {
        val pageNotNull = data.page ?: 0
        val pageSizeNotNull = data.pageSize ?: PageUtil.MAX_PAGE_SIZE
        if (data.agentId.isNullOrBlank() && envId == null) {
            return Page(pageNotNull, pageSizeNotNull, 0, emptyList())
        }
        val sqlLimit = PageUtil.convertPageSizeToSQLMAXLimit(pageNotNull, pageSizeNotNull)
        val query = viewQuery()
        val scope = AgentBuildScope(projectId, data.agentId, envId)
        val filter = AgentBuildFilter(
            startTime = data.startTime,
            endTime = data.endTime,
            pipelineId = data.pipelineId,
            jobId = data.jobId
        )
        val agentBuildCount = query.countJobRuns(scope, filter)
        val agentBuilds = query.listJobRuns(scope, filter, sqlLimit.offset, sqlLimit.limit)
        // 获取展示信息，不走鉴权，即使看到了跳转也没权限
        val builds = client.get(ServiceBuildResource::class).batchFetchBuildRecordStatus(
            data = BatchFetchBuildRecordData(buildIds = agentBuilds.map { it.buildId }, executeCount = null)
        ).data?.groupBy { it.buildId }
        val result = mutableListOf<AgentPipelineContainerBuild>()
        // 环境详情需要返回节点信息
        val nodeInfoMap = fetchNodeInfo(projectId, envId, agentBuilds)
        agentBuilds.forEach { record ->
            val buildWithExecuteCount = builds?.get(record.buildId) ?: return@forEach
            val build = buildWithExecuteCount.let { list ->
                if (record.executeCount == null) {
                    // record 的 executeCount 为空 则取 executeCount 最大的那条
                    list.maxByOrNull { it.executeCount }
                } else {
                    // record 有 executeCount：一一对应，精确匹配相同 executeCount
                    list.firstOrNull { it.executeCount == record.executeCount }
                }
            } ?: return@forEach
            result.add(
                AgentPipelineContainerBuild(
                    buildId = build.buildId,
                    projectId = projectId,
                    pipelineId = data.pipelineId,
                    containerId = (record.vmSeqId ?: 0).toString(),
                    executeCount = build.executeCount,
                    status = build.status,
                    startTime = record.createdTime,
                    endTime = record.updatedTime,
                    buildNum = record.buildNum ?: 0,
                    creator = record.startUser,
                    tasks = null,
                    nodeInfo = nodeInfoMap?.get(record.agentId)
                )
            )
        }
        return Page(pageNotNull, pageSizeNotNull, agentBuildCount, result)
    }

    fun fetchAgentBuildsByPipeline(
        userId: String,
        projectId: String,
        envId: Long?,
        pipelineId: String,
        data: TPAPipelineSearchReq
    ): Page<AgentPipelineContainerBuild> {
        val pageNotNull = data.page ?: 0
        val pageSizeNotNull = data.pageSize ?: PageUtil.MAX_PAGE_SIZE
        if (data.agentId.isNullOrBlank() && envId == null) {
            return Page(pageNotNull, pageSizeNotNull, 0, emptyList())
        }
        val sqlLimit = PageUtil.convertPageSizeToSQLMAXLimit(pageNotNull, pageSizeNotNull)
        val query = viewQuery()
        val scope = AgentBuildScope(projectId, data.agentId, envId)
        val filter = AgentBuildFilter(startTime = data.startTime, endTime = data.endTime, status = data.taskStatusList)
        val agentBuildCount = query.countPipelineBuilds(scope, pipelineId, filter)
        // buildId维度的
        val pipelineBuilds = query.listPipelineBuilds(scope, pipelineId, filter, sqlLimit.offset, sqlLimit.limit)
            .toHashSet()
        if (pipelineBuilds.isEmpty()) {
            return Page(pageNotNull, pageSizeNotNull, agentBuildCount, emptyList())
        }
        // 带上job维度的
        val agentBuildRecords = query.listBuildJobs(scope, pipelineBuilds.map { it.first }.toSet())
            .sortedByDescending { it.id }
        // 这里还要再用pipeline的数据筛一遍，防止过滤不对
        val agentBuilds = agentBuildRecords.filter { Pair(it.buildId, it.executeCount) in pipelineBuilds }
        // 获取展示信息，不走鉴权，即使看到了跳转也没权限
        val builds = client.get(ServiceBuildResource::class).batchFetchBuildRecordStatus(
            data = BatchFetchBuildRecordData(buildIds = agentBuilds.map { it.buildId }, executeCount = null)
        ).data?.groupBy { it.buildId }
        val result = mutableListOf<AgentPipelineContainerBuild>()
        // 环境详情需要返回节点信息
        val nodeInfoMap = fetchNodeInfo(projectId, envId, agentBuilds)
        agentBuilds.groupBy { it.buildId }.forEach { (buildId, buildRecords) ->
            buildRecords.groupBy { it.executeCount }.forEach buildRecords@{ (executeCount, records) ->
                val buildWithExecuteCount = builds?.get(buildId) ?: return@forEach
                val build = buildWithExecuteCount.let { list ->
                    if (executeCount == null) {
                        // record 的 executeCount 为空 则取 executeCount 最大的那条
                        list.maxByOrNull { it.executeCount }
                    } else {
                        // record 有 executeCount：一一对应，精确匹配相同 executeCount
                        list.firstOrNull { it.executeCount == executeCount }
                    }
                } ?: return@buildRecords
                val record = records.first()
                result.add(
                    AgentPipelineContainerBuild(
                        buildId = build.buildId,
                        projectId = projectId,
                        pipelineId = data.pipelineId,
                        containerId = (record.vmSeqId ?: 0).toString(),
                        executeCount = build.executeCount,
                        status = build.status,
                        startTime = build.startTime,
                        endTime = build.endTime,
                        buildNum = record.buildNum ?: 0,
                        creator = record.startUser,
                        tasks = records.map {
                            AgentPipelineBuildTask(
                                taskName = it.taskName,
                                vmSeqId = it.vmSeqId,
                                stageId = it.stageId,
                                stageNumb = it.stageId?.toStageNumb() ?: it.stageId
                            )
                        },
                        nodeInfo = nodeInfoMap?.get(record.agentId)
                    )
                )
            }
        }
        return Page(pageNotNull, pageSizeNotNull, agentBuildCount, result)
    }

    fun fetchAgentBuildsByBuild(
        userId: String,
        projectId: String,
        envId: Long?,
        buildId: String,
        executeCount: Int?,
        data: TPAPipelineSearchReq
    ): Page<AgentPipelineContainerBuild> {
        val pageNotNull = data.page ?: 0
        val pageSizeNotNull = data.pageSize ?: PageUtil.MAX_PAGE_SIZE
        val sqlLimit = PageUtil.convertPageSizeToSQLMAXLimit(pageNotNull, pageSizeNotNull)
        val query = viewQuery()
        val scope = AgentBuildScope(projectId, data.agentId, envId)
        val filter = AgentBuildFilter(startTime = data.startTime, endTime = data.endTime, status = data.taskStatusList)
        val agentBuildCount = query.countBuildRuns(scope, buildId, executeCount, filter)
        val agentBuilds = query.listBuildRuns(scope, buildId, executeCount, filter, sqlLimit.offset, sqlLimit.limit)
        if (agentBuilds.isEmpty()) {
            return Page(pageNotNull, pageSizeNotNull, agentBuildCount, emptyList())
        }
        // 获取展示信息，不走鉴权，即使看到了跳转也没权限，这里展示job的状态
        val builds = client.get(ServiceBuildResource::class).fetchContainerRecordStatus(
            data = BatchFetchContainerRecordData(
                buildId = buildId,
                containerIds = agentBuilds.map { it.vmSeqId.toString() },
                executeCount = executeCount ?: 1
            )
        ).data?.associateBy { it.containerId }
        // 环境详情需要返回节点信息
        val nodeInfoMap = fetchNodeInfo(projectId, envId, agentBuilds)
        val result = mutableListOf<AgentPipelineContainerBuild>()
        agentBuilds.forEach { record ->
            val build = builds?.get(record.vmSeqId.toString()) ?: return@forEach
            result.add(
                AgentPipelineContainerBuild(
                    buildId = buildId,
                    projectId = projectId,
                    pipelineId = record.pipelineId,
                    containerId = (record.vmSeqId ?: 0).toString(),
                    executeCount = build.executeCount,
                    status = build.status,
                    startTime = record.createdTime,
                    endTime = record.updatedTime,
                    buildNum = record.buildNum ?: 0,
                    creator = record.startUser,
                    tasks = listOf(
                        AgentPipelineBuildTask(
                            taskName = record.taskName,
                            vmSeqId = record.vmSeqId,
                            stageId = record.stageId,
                            stageNumb = record.stageId?.toStageNumb() ?: record.stageId
                        )
                    ),
                    nodeInfo = nodeInfoMap?.get(record.agentId)
                )
            )
        }

        return Page(pageNotNull, pageSizeNotNull, agentBuildCount, result)
    }

    private fun fetchNodeInfo(
        projectId: String,
        envId: Long?,
        records: List<TDispatchThirdpartyAgentBuildRecord>
    ): Map<String, NodeInfo>? {
        if (envId == null) return null
        return client.get(ServiceThirdPartyAgentResource::class).batchFetchNodeInfo(
            projectId = projectId,
            data = BatchFetchNodeInfoData(records.map { it.agentId }.toSet())
        ).data?.associate {
            it.agentHashId to NodeInfo(agentHashId = it.agentHashId, nodeHashId = it.nodeHashId, displayName = it.nodeName)
        }
    }

    /**
     * 单个构建机的任务列表（节点详情页、OpenAPI 共用），同构建机同 Job 只取最新一行，带状态时取该状态下的最新一行。
     * @param realtimeActive 为 true 时，排队中/运行中的查询直接读源表，结果实时。OpenAPI 用于定时判断构建机是否空闲
     */
    fun listAgentBuilds(
        agentId: String,
        status: String?,
        pipelineId: String?,
        page: Int?,
        pageSize: Int?,
        realtimeActive: Boolean = false
    ): Page<AgentBuildInfo> {
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: PageUtil.MAX_PAGE_SIZE
        val sqlLimit = PageUtil.convertPageSizeToSQLMAXLimit(pageNotNull, pageSizeNotNull)
        val taskStatus = status?.let { PipelineTaskStatus.parse(it) }
        val activeStatus = taskStatus?.takeIf { it == PipelineTaskStatus.QUEUE || it == PipelineTaskStatus.RUNNING }
        val (count, records) = if (realtimeActive && activeStatus != null) {
            listActiveBuilds(agentId, activeStatus.status, pipelineId, sqlLimit.offset, sqlLimit.limit)
        } else {
            listBuildsWithCount(viewQuery(), agentId, taskStatus?.status, pipelineId, sqlLimit.offset, sqlLimit.limit)
        }
        return Page(pageNotNull, pageSizeNotNull, count, records.map { it.toAgentBuildInfo() })
    }

    /** 活跃行很少：一条 SQL 取出全部后在内存分页和计数，空闲的构建机直接返回空 */
    private fun listActiveBuilds(
        agentId: String,
        status: Int,
        pipelineId: String?,
        offset: Int,
        limit: Int
    ): Pair<Long, List<TDispatchThirdpartyAgentBuildRecord>> {
        val all = thirdPartyAgentBuildDao.listOpenApiBuilds(
            dslContext, agentId, status, pipelineId, offset = 0, limit = OPENAPI_ACTIVE_MAX + 1
        )
        if (all.size <= OPENAPI_ACTIVE_MAX) {
            return Pair(all.size.toLong(), all.drop(offset).take(limit))
        }
        // 活跃行异常多（例如残留大量未结束的记录）时退回 SQL 分页和计数，两条 SQL 仍只读这个 agent 该状态的行
        logger.warn("listAgentBuilds|$agentId|$status|active builds exceed $OPENAPI_ACTIVE_MAX")
        return Pair(
            thirdPartyAgentBuildDao.countOpenApiBuilds(dslContext, agentId, status, pipelineId),
            thirdPartyAgentBuildDao.listOpenApiBuilds(dslContext, agentId, status, pipelineId, offset, limit)
        )
    }

    /** 多取一行判断是否还有下一页：首页不满一页或非空的末页时总数可直接算出，其余情况才计数 */
    private fun listBuildsWithCount(
        query: AgentBuildViewQuery,
        agentId: String,
        status: Int?,
        pipelineId: String?,
        offset: Int,
        limit: Int
    ): Pair<Long, List<TDispatchThirdpartyAgentBuildRecord>> {
        val rows = query.listAgentBuilds(agentId, status, pipelineId, offset, limit + 1)
        val count = if (rows.size <= limit && (offset == 0 || rows.isNotEmpty())) {
            offset.toLong() + rows.size
        } else {
            query.countAgentBuilds(agentId, status, pipelineId)
        }
        return Pair(count, rows.take(limit))
    }

    fun fetchPipelineIdAndName(
        projectId: String,
        agentId: String?,
        envId: Long?,
        pipelineName: String?
    ): List<PipelineIdAndName> {
        if (agentId.isNullOrBlank() && envId == null) {
            return emptyList()
        }
        return viewQuery().pipelineOptions(AgentBuildScope(projectId, agentId, envId), pipelineName.orNullIfBlank())
            .map { PipelineIdAndName(it.first, it.second) }
    }

    fun fetchJobIdAndName(
        projectId: String,
        agentId: String?,
        envId: Long?,
        jobName: String?
    ): List<JobIdAndName> {
        if (agentId.isNullOrBlank() && envId == null) {
            return emptyList()
        }
        return viewQuery().jobOptions(AgentBuildScope(projectId, agentId, envId), jobName.orNullIfBlank())
            .map { JobIdAndName(it.first, it.second) }
    }

    fun fetchCreator(
        projectId: String,
        agentId: String?,
        envId: Long?,
        creator: String?
    ): List<String> {
        if (agentId.isNullOrBlank() && envId == null) {
            return emptyList()
        }
        return viewQuery().creatorOptions(AgentBuildScope(projectId, agentId, envId), creator.orNullIfBlank())
    }

    companion object {
        // 查询排队中/运行中任务时一次取出的上限，正常 agent 的活跃任务远少于此
        private const val OPENAPI_ACTIVE_MAX = 1000

        private fun String.toStageNumb() =
            this.removePrefix("$KEY_STAGE-").toIntOrNull()?.let { it - 1 }?.toString()

        private fun String?.orNullIfBlank() = this?.takeIf { it.isNotBlank() }

        private fun TDispatchThirdpartyAgentBuildRecord.toAgentBuildInfo() = AgentBuildInfo(
            projectId = projectId,
            agentId = agentId,
            pipelineId = pipelineId,
            pipelineName = pipelineName,
            buildId = buildId,
            buildNum = buildNum,
            vmSeqId = vmSeqId,
            taskName = taskName,
            status = PipelineTaskStatus.toStatus(status).name,
            createdTime = createdTime.timestamp(),
            updatedTime = updatedTime.timestamp(),
            workspace = workspace ?: ""
        )

        private val logger = LoggerFactory.getLogger(ThirdPartyAgentBuildService::class.java)
    }
}
