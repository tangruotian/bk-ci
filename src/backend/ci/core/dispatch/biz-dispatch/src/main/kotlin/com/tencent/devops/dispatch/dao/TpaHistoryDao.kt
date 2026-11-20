package com.tencent.devops.dispatch.dao

import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.service.tpahistory.TpaBuildChange
import com.tencent.devops.dispatch.service.tpahistory.TpaHistoryPlan
import com.tencent.devops.dispatch.service.tpahistory.TpaHistoryRow
import com.tencent.devops.dispatch.service.tpahistory.TpaHistoryScope
import com.tencent.devops.dispatch.service.tpahistory.TpaJobChange
import com.tencent.devops.dispatch.service.tpahistory.TpaPipelineChange
import com.tencent.devops.model.dispatch.tables.TDispatchThirdpartyAgentBuild
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistory
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryBuild
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryJob
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryPipeline
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistorySync
import com.tencent.devops.model.dispatch.tables.records.TDispatchTpaHistoryRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 构建记录展示表的同步：读取源表、锁定并改写明细和各视图。读取展示数据见 [TpaHistoryQueryDao]。
 */
@Repository
class TpaHistoryDao {

    /** 锁定构建在明细表中的全部行，同一构建的同步因此串行执行，也会阻塞并发插入同一构建的新行 */
    fun lockBuildRows(dslContext: DSLContext, buildId: String): List<TpaHistoryRow> {
        return dslContext.selectFrom(H).where(H.BUILD_ID.eq(buildId)).forUpdate().fetch().map { it.toRow() }
    }

    /** 读取源表中构建的全部行。没有执行次数的历史数据不同步 */
    fun fetchSourceRows(dslContext: DSLContext, buildId: String): List<TpaHistoryRow> {
        return dslContext.select(
            S.ID, S.PROJECT_ID, S.AGENT_ID, S.ENV_ID, S.PIPELINE_ID, S.PIPELINE_NAME, S.BUILD_ID, S.BUILD_NUM,
            S.EXECUTE_COUNT, S.VM_SEQ_ID, S.JOB_ID, S.TASK_NAME, S.STAGE_ID, S.STATUS, S.START_USER, S.WORKSPACE,
            S.TIME_INTERVAL, S.CREATED_TIME, S.UPDATED_TIME
        ).from(S)
            .where(S.BUILD_ID.eq(buildId))
            .and(S.EXECUTE_COUNT.isNotNull)
            .fetch()
            .map { r ->
                val projectId = r[S.PROJECT_ID]
                TpaHistoryRow(
                    id = r[S.ID],
                    projectId = projectId,
                    agentId = r[S.AGENT_ID],
                    envKey = r[S.ENV_ID]?.let { TpaHistoryScope.envKey(it, projectId) },
                    pipelineId = r[S.PIPELINE_ID] ?: "",
                    pipelineName = r[S.PIPELINE_NAME] ?: "",
                    buildId = r[S.BUILD_ID],
                    buildNum = r[S.BUILD_NUM] ?: 0,
                    executeCount = r[S.EXECUTE_COUNT],
                    vmSeqId = r[S.VM_SEQ_ID],
                    jobId = r[S.JOB_ID]?.takeIf { it.isNotBlank() },
                    taskName = r[S.TASK_NAME] ?: "",
                    stageId = r[S.STAGE_ID],
                    status = r[S.STATUS],
                    startUser = r[S.START_USER],
                    workspace = r[S.WORKSPACE],
                    timeInterval = r[S.TIME_INTERVAL],
                    createdTime = r[S.CREATED_TIME],
                    updatedTime = r[S.UPDATED_TIME]
                )
            }
    }

    fun apply(dslContext: DSLContext, plan: TpaHistoryPlan) {
        if (plan.deletes.isNotEmpty()) {
            dslContext.deleteFrom(H).where(H.ID.`in`(plan.deletes)).execute()
        }
        plan.upserts.forEach { upsertRow(dslContext, it) }
        plan.builds.forEach { applyBuild(dslContext, it) }
        plan.pipelines.forEach { applyPipeline(dslContext, it) }
        plan.jobs.forEach { applyJob(dslContext, it) }
    }

    private fun upsertRow(dslContext: DSLContext, row: TpaHistoryRow) {
        val values = linkedMapOf<Field<*>, Any?>(
            H.PROJECT_ID to row.projectId,
            H.AGENT_ID to row.agentId,
            H.ENV_KEY to row.envKey,
            H.PIPELINE_ID to row.pipelineId,
            H.PIPELINE_NAME to row.pipelineName,
            H.BUILD_ID to row.buildId,
            H.BUILD_NUM to row.buildNum,
            H.EXECUTE_COUNT to row.executeCount,
            H.VM_SEQ_ID to row.vmSeqId,
            H.JOB_ID to row.jobId,
            H.TASK_NAME to row.taskName,
            H.STAGE_ID to row.stageId,
            H.STATUS to row.status,
            H.LATEST to row.latest.toFlag(),
            H.STATUS_LATEST to row.statusLatest.toFlag(),
            H.START_USER to row.startUser,
            H.WORKSPACE to row.workspace,
            H.TIME_INTERVAL to row.timeInterval,
            H.CREATED_TIME to row.createdTime,
            H.UPDATED_TIME to row.updatedTime
        )
        dslContext.insertInto(H)
            .set(H.ID, row.id)
            .set(values)
            .onDuplicateKeyUpdate()
            .set(values)
            .execute()
    }

    private fun applyBuild(dslContext: DSLContext, change: TpaBuildChange) {
        val key = B.SCOPE_KEY.eq(change.key.scopeKey)
            .and(B.BUILD_ID.eq(change.key.buildId))
            .and(B.EXECUTE_COUNT.eq(change.key.executeCount))
        val view = change.after ?: run {
            dslContext.deleteFrom(B).where(key).execute()
            return
        }
        val values = linkedMapOf<Field<*>, Any?>(
            B.PROJECT_ID to view.projectId,
            B.PIPELINE_ID to view.pipelineId,
            B.PIPELINE_NAME to view.pipelineName,
            B.BUILD_NUM to view.buildNum,
            B.START_USER to view.startUser,
            B.START_TIME to view.startTime,
            B.TOTAL_TIME to view.totalTime,
            B.LAST_ID to view.lastId
        )
        dslContext.insertInto(B)
            .set(B.SCOPE_KEY, view.scopeKey)
            .set(B.BUILD_ID, view.buildId)
            .set(B.EXECUTE_COUNT, view.executeCount)
            .set(values)
            .onDuplicateKeyUpdate()
            .set(values)
            .execute()
    }

    private fun applyPipeline(dslContext: DSLContext, change: TpaPipelineChange) {
        val key = P.SCOPE_KEY.eq(change.scopeKey).and(P.PIPELINE_ID.eq(change.pipelineId))
        val last = change.last
        // MySQL 按顺序执行 ON DUPLICATE KEY UPDATE 的赋值，LAST_ID 必须最后更新，前面的判断才能用到旧值
        val updates = linkedMapOf<Field<*>, Any?>()
        if (last != null) {
            val newer = P.LAST_ID.le(last.lastId)
            updates[P.PIPELINE_NAME] = DSL.`when`(newer, DSL.`val`(last.pipelineName)).otherwise(P.PIPELINE_NAME)
            updates[P.LAST_BUILD_ID] = DSL.`when`(newer, DSL.`val`(last.buildId)).otherwise(P.LAST_BUILD_ID)
            updates[P.LAST_ID] = DSL.greatest(P.LAST_ID, DSL.`val`(last.lastId))
        }
        updates[P.BUILD_COUNT] = P.BUILD_COUNT.plus(change.countDelta)
        dslContext.insertInto(P)
            .set(P.SCOPE_KEY, change.scopeKey)
            .set(P.PIPELINE_ID, change.pipelineId)
            .set(P.PROJECT_ID, change.projectId)
            .set(P.PIPELINE_NAME, last?.pipelineName ?: "")
            .set(P.BUILD_COUNT, change.countDelta)
            .set(P.LAST_BUILD_ID, last?.buildId ?: "")
            .set(P.LAST_ID, last?.lastId ?: 0L)
            .onDuplicateKeyUpdate()
            .set(updates)
            .execute()

        change.removedLastId?.let { removed ->
            // 原最新构建已移出此范围：按构建视图重新取最新的一个，只在视图仍指向原构建时更新，避免覆盖并发写入的更新值。
            // 必须用加锁读取：事务的一致性快照在读源表时就已建立，普通查询看不到之后其他构建提交的移入、移出，
            // 可能把指针指向已经移走的构建。加锁读取读最新提交的数据，并等待正在修改这些行的事务结束。
            val top = dslContext.select(B.BUILD_ID, B.PIPELINE_NAME, B.LAST_ID).from(B)
                .where(B.SCOPE_KEY.eq(change.scopeKey)).and(B.PIPELINE_ID.eq(change.pipelineId))
                .orderBy(B.LAST_ID.desc()).limit(1).forUpdate().fetchOne()
            if (top != null) {
                dslContext.update(P)
                    .set(P.PIPELINE_NAME, top[B.PIPELINE_NAME])
                    .set(P.LAST_BUILD_ID, top[B.BUILD_ID])
                    .set(P.LAST_ID, top[B.LAST_ID])
                    .where(key).and(P.LAST_ID.eq(removed))
                    .execute()
            }
        }
        if (change.countDelta < 0) {
            dslContext.deleteFrom(P).where(key).and(P.BUILD_COUNT.le(0)).execute()
        }
    }

    private fun applyJob(dslContext: DSLContext, change: TpaJobChange) {
        val key = J.SCOPE_KEY.eq(change.scopeKey)
            .and(J.PIPELINE_ID.eq(change.pipelineId))
            .and(J.JOB_ID.eq(change.jobId))
        val last = change.last
        val updates = linkedMapOf<Field<*>, Any?>()
        if (last != null) {
            val newer = J.LAST_ID.le(last.lastId)
            updates[J.PIPELINE_NAME] = DSL.`when`(newer, DSL.`val`(last.pipelineName)).otherwise(J.PIPELINE_NAME)
            updates[J.TASK_NAME] = DSL.`when`(newer, DSL.`val`(last.taskName)).otherwise(J.TASK_NAME)
            updates[J.LAST_CREATED_TIME] =
                DSL.`when`(newer, DSL.`val`(last.createdTime)).otherwise(J.LAST_CREATED_TIME)
            updates[J.LAST_VM_SEQ_ID] = DSL.`when`(newer, DSL.`val`(last.vmSeqId)).otherwise(J.LAST_VM_SEQ_ID)
            updates[J.LAST_STAGE_ID] = DSL.`when`(newer, DSL.`val`(last.stageId, J.LAST_STAGE_ID))
                .otherwise(J.LAST_STAGE_ID)
            updates[J.LAST_ID] = DSL.greatest(J.LAST_ID, DSL.`val`(last.lastId))
        }
        updates[J.RUN_COUNT] = J.RUN_COUNT.plus(change.runDelta)
        updates[J.TIME_SUM] = J.TIME_SUM.plus(change.timeSumDelta)
        updates[J.TIME_COUNT] = J.TIME_COUNT.plus(change.timeCountDelta)
        dslContext.insertInto(J)
            .set(J.SCOPE_KEY, change.scopeKey)
            .set(J.PIPELINE_ID, change.pipelineId)
            .set(J.JOB_ID, change.jobId)
            .set(J.PROJECT_ID, change.projectId)
            .set(J.PIPELINE_NAME, last?.pipelineName ?: "")
            .set(J.TASK_NAME, last?.taskName ?: "")
            .set(J.RUN_COUNT, change.runDelta)
            .set(J.TIME_SUM, change.timeSumDelta)
            .set(J.TIME_COUNT, change.timeCountDelta)
            .set(J.LAST_CREATED_TIME, last?.createdTime)
            .set(J.LAST_VM_SEQ_ID, last?.vmSeqId ?: "")
            .set(J.LAST_STAGE_ID, last?.stageId)
            .set(J.LAST_ID, last?.lastId ?: 0L)
            .onDuplicateKeyUpdate()
            .set(updates)
            .execute()

        change.removedLastId?.let { removed ->
            // 同上，加锁读取最新提交的明细
            val top = dslContext.select(H.ID, H.CREATED_TIME, H.VM_SEQ_ID, H.STAGE_ID, H.TASK_NAME, H.PIPELINE_NAME)
                .from(H)
                .where(scopeCondition(change.scopeKey))
                .and(H.PIPELINE_ID.eq(change.pipelineId))
                .and(H.JOB_ID.eq(change.jobId))
                .orderBy(H.ID.desc()).limit(1).forUpdate().fetchOne()
            if (top != null) {
                dslContext.update(J)
                    .set(J.PIPELINE_NAME, top[H.PIPELINE_NAME])
                    .set(J.TASK_NAME, top[H.TASK_NAME])
                    .set(J.LAST_CREATED_TIME, top[H.CREATED_TIME])
                    .set(J.LAST_VM_SEQ_ID, top[H.VM_SEQ_ID])
                    .set(J.LAST_STAGE_ID, top[H.STAGE_ID])
                    .set(J.LAST_ID, top[H.ID])
                    .where(key).and(J.LAST_ID.eq(removed))
                    .execute()
            }
        }
        if (change.runDelta < 0) {
            dslContext.deleteFrom(J).where(key).and(J.RUN_COUNT.le(0)).execute()
        }
    }

    /** 新数据扫描：按主键顺序取游标之后、创建时间早于 [before] 的行（留出时间给并发写入的较小 ID 提交） */
    fun fetchSourceIdsAfter(
        dslContext: DSLContext,
        afterId: Long,
        before: LocalDateTime,
        limit: Int
    ): List<Pair<Long, String>> {
        return dslContext.select(S.ID, S.BUILD_ID).from(S)
            .where(S.ID.gt(afterId))
            .and(S.CREATED_TIME.lt(before))
            .orderBy(S.ID.asc())
            .limit(limit)
            .fetch()
            .map { Pair(it.value1(), it.value2()) }
    }

    fun maxSourceId(dslContext: DSLContext): Long {
        return dslContext.select(DSL.max(S.ID)).from(S).where(S.ID.gt(0L)).fetchOne(0, Long::class.java) ?: 0L
    }

    /** 明细中仍在排队/运行中的行，用于补齐丢失的状态变化 */
    fun fetchActiveRowsAfter(
        dslContext: DSLContext,
        afterId: Long,
        limit: Int
    ): List<Triple<Long, String, LocalDateTime>> {
        return dslContext.select(H.ID, H.BUILD_ID, H.UPDATED_TIME).from(H)
            .where(H.STATUS.`in`(PipelineTaskStatus.QUEUE.status, PipelineTaskStatus.RUNNING.status))
            .and(H.ID.gt(afterId))
            .orderBy(H.ID.asc())
            .limit(limit)
            .fetch()
            .map { Triple(it.value1(), it.value2(), it.value3()) }
    }

    fun fetchHistoryBuildsAfter(dslContext: DSLContext, afterId: Long, limit: Int): List<Pair<Long, String>> {
        return dslContext.select(H.ID, H.BUILD_ID).from(H)
            .where(H.ID.gt(afterId))
            .orderBy(H.ID.asc())
            .limit(limit)
            .fetch()
            .map { Pair(it.value1(), it.value2()) }
    }

    fun existingSourceBuilds(dslContext: DSLContext, buildIds: Collection<String>): Set<String> {
        if (buildIds.isEmpty()) return emptySet()
        return dslContext.selectDistinct(S.BUILD_ID).from(S)
            .where(S.BUILD_ID.`in`(buildIds))
            .fetch(S.BUILD_ID).toSet()
    }

    fun getProgress(dslContext: DSLContext, key: String): Long? {
        return dslContext.select(SYNC.PROGRESS).from(SYNC).where(SYNC.SYNC_KEY.eq(key)).fetchOne(SYNC.PROGRESS)
    }

    fun setProgress(dslContext: DSLContext, key: String, progress: Long) {
        val now = LocalDateTime.now()
        dslContext.insertInto(SYNC)
            .set(SYNC.SYNC_KEY, key)
            .set(SYNC.PROGRESS, progress)
            .set(SYNC.UPDATED_TIME, now)
            .onDuplicateKeyUpdate()
            .set(SYNC.PROGRESS, progress)
            .set(SYNC.UPDATED_TIME, now)
            .execute()
    }

    private fun Boolean.toFlag(): Byte = if (this) 1 else 0

    private fun TDispatchTpaHistoryRecord.toRow() = TpaHistoryRow(
        id = id,
        projectId = projectId,
        agentId = agentId,
        envKey = envKey,
        pipelineId = pipelineId,
        pipelineName = pipelineName,
        buildId = buildId,
        buildNum = buildNum,
        executeCount = executeCount,
        vmSeqId = vmSeqId,
        jobId = jobId,
        taskName = taskName,
        stageId = stageId,
        status = status,
        latest = latest.toInt() == 1,
        statusLatest = statusLatest.toInt() == 1,
        startUser = startUser,
        workspace = workspace,
        timeInterval = timeInterval,
        createdTime = createdTime,
        updatedTime = updatedTime
    )

    companion object {
        private val S = TDispatchThirdpartyAgentBuild.T_DISPATCH_THIRDPARTY_AGENT_BUILD
        private val H = TDispatchTpaHistory.T_DISPATCH_TPA_HISTORY
        private val B = TDispatchTpaHistoryBuild.T_DISPATCH_TPA_HISTORY_BUILD
        private val P = TDispatchTpaHistoryPipeline.T_DISPATCH_TPA_HISTORY_PIPELINE
        private val J = TDispatchTpaHistoryJob.T_DISPATCH_TPA_HISTORY_JOB
        private val SYNC = TDispatchTpaHistorySync.T_DISPATCH_TPA_HISTORY_SYNC

        /** 统计范围对应的明细条件 */
        fun scopeCondition(scopeKey: String): Condition {
            val (agentId, envKey) = TpaHistoryScope.parse(scopeKey)
            return if (agentId != null) H.AGENT_ID.eq(agentId) else H.ENV_KEY.eq(envKey)
        }
    }
}
