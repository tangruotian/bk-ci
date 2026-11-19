package com.tencent.devops.dispatch.service.tpahistory

/**
 * 根据一个构建在明细表中的旧数据和源表中的新数据，计算需要写入的明细和各视图的增量。
 * 纯计算，不访问数据库。同步以构建为单位：同一构建的所有行一起比较，构建视图可以整体重算，
 * 流水线和 Job 视图是多个构建共享的，只写本构建带来的变化量，因此同一份数据重复同步不会重复计数。
 */
object TpaHistoryPlanner {

    /** 计算 LATEST / STATUS_LATEST，口径与原查询的 "同构建机同 Job 不存在更新的行" 一致 */
    fun withLatestFlags(rows: List<TpaHistoryRow>): List<TpaHistoryRow> {
        val latestIds = rows.groupBy { Triple(it.agentId, it.buildId, it.vmSeqId) }
            .values.map { group -> group.maxOf { it.id } }.toSet()
        val statusLatestIds = rows.groupBy { listOf(it.agentId, it.buildId, it.vmSeqId, it.status) }
            .values.map { group -> group.maxOf { it.id } }.toSet()
        return rows.map { it.copy(latest = it.id in latestIds, statusLatest = it.id in statusLatestIds) }
    }

    fun plan(old: List<TpaHistoryRow>, new: List<TpaHistoryRow>): TpaHistoryPlan {
        val oldById = old.associateBy { it.id }
        val newIds = new.map { it.id }.toSet()
        val upserts = new.filter { oldById[it.id] != it }
        val deletes = old.filter { it.id !in newIds }.map { it.id }

        val beforeBuilds = buildViews(old)
        val afterBuilds = buildViews(new)
        val builds = (beforeBuilds.keys + afterBuilds.keys).sorted().mapNotNull { key ->
            val after = afterBuilds[key]
            if (beforeBuilds[key] == after) null else TpaBuildChange(key, after)
        }
        return TpaHistoryPlan(
            upserts = upserts,
            deletes = deletes,
            builds = builds,
            pipelines = pipelineChanges(beforeBuilds.values, afterBuilds.values),
            jobs = jobChanges(old, new)
        )
    }

    private fun buildViews(rows: List<TpaHistoryRow>): Map<TpaBuildKey, TpaBuildView> {
        val grouped = mutableMapOf<TpaBuildKey, MutableList<TpaHistoryRow>>()
        rows.forEach { row ->
            row.scopes().forEach { scope ->
                grouped.getOrPut(TpaBuildKey(scope, row.buildId, row.executeCount)) { mutableListOf() }.add(row)
            }
        }
        return grouped.mapValues { (key, group) ->
            val latest = group.maxBy { it.id }
            val elapsed = AgentBuildElapsed.calc(
                group.asSequence().map { Triple(it.status, it.createdTime, it.updatedTime) }
            )
            TpaBuildView(
                scopeKey = key.scopeKey,
                buildId = key.buildId,
                executeCount = key.executeCount,
                projectId = latest.projectId,
                pipelineId = latest.pipelineId,
                pipelineName = latest.pipelineName,
                buildNum = latest.buildNum,
                startUser = latest.startUser,
                startTime = elapsed?.first,
                totalTime = elapsed?.second,
                lastId = latest.id
            )
        }
    }

    private fun pipelineChanges(
        before: Collection<TpaBuildView>,
        after: Collection<TpaBuildView>
    ): List<TpaPipelineChange> {
        val beforeGroups = before.groupBy { it.scopeKey to it.pipelineId }
        val afterGroups = after.groupBy { it.scopeKey to it.pipelineId }
        return (beforeGroups.keys + afterGroups.keys)
            .sortedWith(compareBy({ it.first }, { it.second }))
            .mapNotNull { key ->
                val b = beforeGroups[key].orEmpty()
                val a = afterGroups[key].orEmpty()
                val bLast = b.maxByOrNull { it.lastId }
                val aLast = a.maxByOrNull { it.lastId }
                val countDelta = a.size - b.size
                val last = aLast?.let { TpaPipelineLast(it.lastId, it.buildId, it.pipelineName) }
                val beforeLast = bLast?.let { TpaPipelineLast(it.lastId, it.buildId, it.pipelineName) }
                val removedLastId = bLast?.lastId?.takeIf { aLast == null || aLast.lastId < it }
                if (countDelta == 0 && last == beforeLast && removedLastId == null) {
                    null
                } else {
                    TpaPipelineChange(
                        scopeKey = key.first,
                        pipelineId = key.second,
                        projectId = (aLast ?: bLast)!!.projectId,
                        countDelta = countDelta,
                        last = last,
                        removedLastId = removedLastId
                    )
                }
            }
    }

    private data class JobKey(val scopeKey: String, val pipelineId: String, val jobId: String)

    private class JobAgg(var runs: Int = 0, var timeSum: Long = 0, var timeCount: Int = 0, var last: TpaHistoryRow? = null)

    private fun jobAggs(rows: List<TpaHistoryRow>): Map<JobKey, JobAgg> {
        val aggs = mutableMapOf<JobKey, JobAgg>()
        rows.forEach { row ->
            val jobId = row.jobId ?: return@forEach // 没有 jobId 的历史数据不计入 Job 视图
            row.scopes().forEach { scope ->
                val agg = aggs.getOrPut(JobKey(scope, row.pipelineId, jobId)) { JobAgg() }
                agg.runs++
                if (row.finished() && row.timeInterval != null) {
                    agg.timeSum += row.timeInterval
                    agg.timeCount++
                }
                if (agg.last == null || row.id > agg.last!!.id) agg.last = row
            }
        }
        return aggs
    }

    private fun jobChanges(old: List<TpaHistoryRow>, new: List<TpaHistoryRow>): List<TpaJobChange> {
        val before = jobAggs(old)
        val after = jobAggs(new)
        return (before.keys + after.keys)
            .sortedWith(compareBy({ it.scopeKey }, { it.pipelineId }, { it.jobId }))
            .mapNotNull { key ->
                val b = before[key]
                val a = after[key]
                val runDelta = (a?.runs ?: 0) - (b?.runs ?: 0)
                val timeSumDelta = (a?.timeSum ?: 0) - (b?.timeSum ?: 0)
                val timeCountDelta = (a?.timeCount ?: 0) - (b?.timeCount ?: 0)
                val last = a?.last?.toJobLast()
                val beforeLast = b?.last?.toJobLast()
                val removedLastId = b?.last?.id?.takeIf { a?.last == null || a.last!!.id < it }
                if (runDelta == 0 && timeSumDelta == 0L && timeCountDelta == 0 && last == beforeLast &&
                    removedLastId == null
                ) {
                    null
                } else {
                    TpaJobChange(
                        scopeKey = key.scopeKey,
                        pipelineId = key.pipelineId,
                        jobId = key.jobId,
                        projectId = (a?.last ?: b?.last)!!.projectId,
                        runDelta = runDelta,
                        timeSumDelta = timeSumDelta,
                        timeCountDelta = timeCountDelta,
                        last = last,
                        removedLastId = removedLastId
                    )
                }
            }
    }

    private fun TpaHistoryRow.toJobLast() = TpaJobLast(
        lastId = id,
        createdTime = createdTime,
        vmSeqId = vmSeqId,
        stageId = stageId,
        taskName = taskName,
        pipelineName = pipelineName
    )
}
