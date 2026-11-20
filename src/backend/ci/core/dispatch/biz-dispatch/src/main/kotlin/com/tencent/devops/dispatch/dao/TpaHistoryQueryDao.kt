package com.tencent.devops.dispatch.dao

import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.thirdpartyagent.TPAPipelineBuild
import com.tencent.devops.model.dispatch.tables.TDispatchThirdpartyAgentBuild
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistory
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryBuild
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryJob
import com.tencent.devops.model.dispatch.tables.TDispatchTpaHistoryPipeline
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import com.tencent.devops.model.dispatch.tables.records.TDispatchTpaHistoryBuildRecord
import com.tencent.devops.model.dispatch.tables.records.TDispatchTpaHistoryJobRecord
import com.tencent.devops.model.dispatch.tables.records.TDispatchTpaHistoryPipelineRecord
import com.tencent.devops.model.dispatch.tables.records.TDispatchTpaHistoryRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SelectLimitStep
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 明细表的查询条件。构建机、环境、构建ID 至少有一个，避免扫描全表。
 */
data class TpaHistoryWhere(
    val agentId: String?,
    val envKey: String?,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val pipelineId: String? = null,
    val jobId: String? = null,
    val creator: String? = null,
    val status: List<Int>? = null,
    val buildIds: Collection<String>? = null,
    val executeCount: Int? = null
) {
    init {
        require(agentId != null || envKey != null || buildIds != null) { "agentId, envKey or buildIds is required" }
    }
}

/**
 * 构建记录展示表的查询。没有其他筛选的视图直接读汇总表，只读一页的行；
 * 按状态、时间、执行人等筛选时在明细表上分组，明细表只有展示需要的列，并且按范围建了索引。
 */
@Repository
class TpaHistoryQueryDao {

    // ---------------------------- 明细表 ----------------------------

    /** @return Triple(流水线数, Job 数, 构建数)，Job 数不含没有 jobId 的历史数据 */
    fun countViews(dslContext: DSLContext, where: TpaHistoryWhere): Triple<Long, Long, Long> {
        val record = dslContext.select(
            DSL.countDistinct(H.PIPELINE_ID),
            DSL.countDistinct(H.PIPELINE_ID, H.JOB_ID),
            DSL.countDistinct(H.BUILD_ID, H.EXECUTE_COUNT)
        ).from(where.source()).where(where.conditions()).fetchOne()
        return Triple(
            record?.value1()?.toLong() ?: 0L,
            record?.value2()?.toLong() ?: 0L,
            record?.value3()?.toLong() ?: 0L
        )
    }

    fun listPipelinesByRows(
        dslContext: DSLContext,
        where: TpaHistoryWhere,
        offset: Int,
        limit: Int
    ): List<TPAPipelineBuild> {
        return dslContext.select(
            H.PIPELINE_ID,
            DSL.max(H.PIPELINE_NAME),
            DSL.countDistinct(H.BUILD_ID, H.EXECUTE_COUNT),
            DSL.max(H.PROJECT_ID),
            latestOf(H.BUILD_ID)
        ).from(where.source()).where(where.conditions())
            .groupBy(H.PIPELINE_ID)
            .orderBy(DSL.max(H.ID).desc())
            .limit(limit).offset(offset)
            .fetch().map {
                pipelineView(it.value4(), it.value1(), it.value2(), it.value3(), it.value5())
            }
    }

    fun listJobsByRows(
        dslContext: DSLContext,
        where: TpaHistoryWhere,
        offset: Int,
        limit: Int
    ): List<TPAPipelineBuild> {
        val finishedInterval = DSL.`when`(H.STATUS.`in`(FINISHED), H.TIME_INTERVAL)
        return dslContext.select(
            H.PIPELINE_ID,
            DSL.max(H.PIPELINE_NAME),
            H.JOB_ID,
            DSL.max(H.TASK_NAME),
            DSL.count(),
            DSL.max(H.CREATED_TIME),
            DSL.avg(finishedInterval),
            latestOf(H.VM_SEQ_ID),
            latestOf(H.STAGE_ID),
            DSL.max(H.PROJECT_ID)
        ).from(where.source()).where(where.conditions()).and(H.JOB_ID.isNotNull)
            .groupBy(H.PIPELINE_ID, H.JOB_ID)
            .orderBy(DSL.max(H.ID).desc())
            .limit(limit).offset(offset)
            .fetch().map {
                TPAPipelineBuild(
                    projectId = it.value10(),
                    pipelineId = it.value1(),
                    pipelineName = it.value2(),
                    jobId = it.value3(),
                    jobName = it.value4(),
                    buildCount = it.value5(),
                    lastBuildTime = it.value6(),
                    avgTimeInterval = it.value7()?.toLong(),
                    lastContainerId = it.value8()?.toLongOrNull(),
                    stageId = it.value9(),
                    stageNumb = null,
                    buildId = null,
                    executeCount = null
                )
            }
    }

    fun listBuildsByRows(
        dslContext: DSLContext,
        where: TpaHistoryWhere,
        offset: Int,
        limit: Int
    ): List<TPAPipelineBuild> {
        return dslContext.select(
            H.BUILD_ID,
            H.EXECUTE_COUNT,
            DSL.max(H.PIPELINE_ID),
            DSL.max(H.PIPELINE_NAME),
            DSL.max(H.BUILD_NUM),
            DSL.max(H.PROJECT_ID)
        ).from(where.source()).where(where.conditions())
            .groupBy(H.BUILD_ID, H.EXECUTE_COUNT)
            .orderBy(DSL.max(H.ID).desc())
            .limit(limit).offset(offset)
            .fetch().map {
                buildView(it.value6(), it.value3(), it.value4(), it.value1(), it.value2(), it.value5())
            }
    }

    fun countRows(dslContext: DSLContext, where: TpaHistoryWhere): Long {
        return dslContext.selectCount().from(where.source()).where(where.conditions()).fetchOne(0, Long::class.java) ?: 0L
    }

    /** 按 ID 倒序取明细，转换为源表的记录类型，字段同名 */
    fun listRows(
        dslContext: DSLContext,
        where: TpaHistoryWhere,
        offset: Int? = null,
        limit: Int? = null
    ): List<TDispatchThirdpartyAgentBuildRecord> {
        val select = dslContext.selectFrom(where.source()).where(where.conditions()).orderBy(H.ID.desc())
        return select.page(offset, limit).fetch().map { it.into(S) }
    }

    fun countBuildGroups(dslContext: DSLContext, where: TpaHistoryWhere): Long {
        return dslContext.select(DSL.countDistinct(H.BUILD_ID, H.EXECUTE_COUNT)).from(where.source())
            .where(where.conditions())
            .fetchOne(0, Long::class.java) ?: 0L
    }

    fun listBuildGroups(
        dslContext: DSLContext,
        where: TpaHistoryWhere,
        offset: Int,
        limit: Int
    ): List<Pair<String, Int?>> {
        return dslContext.select(H.BUILD_ID, H.EXECUTE_COUNT).from(where.source())
            .where(where.conditions())
            .groupBy(H.BUILD_ID, H.EXECUTE_COUNT)
            .orderBy(DSL.max(H.ID).desc())
            .limit(limit).offset(offset)
            .fetch().map { Pair(it.value1(), it.value2()) }
    }

    fun pipelineOptionsByRows(dslContext: DSLContext, where: TpaHistoryWhere, keyword: String?): List<Pair<String, String>> {
        return dslContext.select(H.PIPELINE_ID, DSL.max(H.PIPELINE_NAME)).from(where.source())
            .where(where.conditions())
            .and(keyword?.let { H.PIPELINE_NAME.contains(it) } ?: DSL.noCondition())
            .groupBy(H.PIPELINE_ID)
            .orderBy(DSL.max(H.ID).desc())
            .fetch().map { Pair(it.value1(), it.value2()) }
    }

    fun jobOptionsByRows(dslContext: DSLContext, where: TpaHistoryWhere, keyword: String?): List<Pair<String, String>> {
        return dslContext.select(H.JOB_ID, DSL.max(H.TASK_NAME)).from(where.source())
            .where(where.conditions())
            .and(H.JOB_ID.isNotNull)
            .and(keyword?.let { H.TASK_NAME.contains(it) } ?: DSL.noCondition())
            .groupBy(H.JOB_ID)
            .orderBy(DSL.max(H.ID).desc())
            .fetch().map { Pair(it.value1(), it.value2()) }
    }

    fun creatorOptionsByRows(dslContext: DSLContext, where: TpaHistoryWhere, keyword: String?): List<String> {
        return dslContext.selectDistinct(H.START_USER).from(where.source())
            .where(where.conditions())
            .and(H.START_USER.isNotNull)
            .and(keyword?.let { H.START_USER.contains(it) } ?: DSL.noCondition())
            .fetch(H.START_USER)
    }

    /** 构建机任务数：同构建机、同构建、同 Job 只计最新一行，带状态时计该状态下的最新一行 */
    fun countAgentRows(dslContext: DSLContext, agentId: String, status: Int?, pipelineId: String?): Long {
        return dslContext.selectCount().from(H)
            .where(agentConditions(agentId, status, pipelineId))
            .fetchOne(0, Long::class.java) ?: 0L
    }

    /**
     * 构建机任务列表，按 ID 倒序。不带状态时按 (CREATED_TIME, ID) 倒序，与 ID 倒序一致，可沿 IDX_AGENT_TIME 倒序扫描；
     * 带状态时沿 IDX_AGENT_STATUS(AGENT_ID, STATUS, STATUS_LATEST, 隐含 ID) 倒序扫描。
     */
    fun listAgentRows(
        dslContext: DSLContext,
        agentId: String,
        status: Int?,
        pipelineId: String?,
        offset: Int,
        limit: Int
    ): List<TDispatchThirdpartyAgentBuildRecord> {
        val order = if (status == null) listOf(H.CREATED_TIME.desc(), H.ID.desc()) else listOf(H.ID.desc())
        return dslContext.selectFrom(H)
            .where(agentConditions(agentId, status, pipelineId))
            .orderBy(order)
            .limit(limit).offset(offset)
            .fetch().map { it.into(S) }
    }

    private fun agentConditions(agentId: String, status: Int?, pipelineId: String?): List<Condition> = listOfNotNull(
        H.AGENT_ID.eq(agentId),
        if (status == null) H.LATEST.eq(FLAG) else H.STATUS.eq(status).and(H.STATUS_LATEST.eq(FLAG)),
        pipelineId?.let { H.PIPELINE_ID.eq(it) }
    )

    /** 某个 Job 在若干流水线下的构建数（构建ID + 执行次数） */
    fun countJobBuilds(
        dslContext: DSLContext,
        where: TpaHistoryWhere,
        pipelineIds: Collection<String>,
        jobId: String
    ): Long {
        if (pipelineIds.isEmpty()) return 0L
        return dslContext.select(DSL.countDistinct(H.BUILD_ID, H.EXECUTE_COUNT)).from(where.source())
            .where(where.conditions())
            .and(H.PIPELINE_ID.`in`(pipelineIds))
            .and(H.JOB_ID.eq(jobId))
            .fetchOne(0, Long::class.java) ?: 0L
    }

    // ---------------------------- 汇总表 ----------------------------

    /** @return Pair(流水线数, 构建数) */
    fun countPipelineViews(dslContext: DSLContext, scopeKey: String): Pair<Long, Long> {
        val record = dslContext.select(DSL.count(), DSL.sum(P.BUILD_COUNT)).from(P)
            .where(P.SCOPE_KEY.eq(scopeKey))
            .fetchOne()
        return Pair(record?.value1()?.toLong() ?: 0L, record?.value2()?.toLong() ?: 0L)
    }

    fun getPipelineView(dslContext: DSLContext, scopeKey: String, pipelineId: String): TDispatchTpaHistoryPipelineRecord? {
        return dslContext.selectFrom(P)
            .where(P.SCOPE_KEY.eq(scopeKey)).and(P.PIPELINE_ID.eq(pipelineId))
            .fetchOne()
    }

    fun listPipelineViews(
        dslContext: DSLContext,
        scopeKey: String,
        pipelineId: String?,
        keyword: String?,
        offset: Int? = null,
        limit: Int? = null
    ): List<TDispatchTpaHistoryPipelineRecord> {
        val select = dslContext.selectFrom(P)
            .where(P.SCOPE_KEY.eq(scopeKey))
            .and(pipelineId?.let { P.PIPELINE_ID.eq(it) } ?: DSL.noCondition())
            .and(keyword?.let { P.PIPELINE_NAME.contains(it) } ?: DSL.noCondition())
            .orderBy(P.LAST_ID.desc())
        return select.page(offset, limit).fetch()
    }

    fun countJobViews(dslContext: DSLContext, scopeKey: String, pipelineId: String?): Long {
        return dslContext.selectCount().from(J)
            .where(J.SCOPE_KEY.eq(scopeKey))
            .and(pipelineId?.let { J.PIPELINE_ID.eq(it) } ?: DSL.noCondition())
            .fetchOne(0, Long::class.java) ?: 0L
    }

    fun listJobViews(
        dslContext: DSLContext,
        scopeKey: String,
        pipelineId: String?,
        jobId: String?,
        keyword: String?,
        offset: Int? = null,
        limit: Int? = null
    ): List<TDispatchTpaHistoryJobRecord> {
        val select = dslContext.selectFrom(J)
            .where(J.SCOPE_KEY.eq(scopeKey))
            .and(pipelineId?.let { J.PIPELINE_ID.eq(it) } ?: DSL.noCondition())
            .and(jobId?.let { J.JOB_ID.eq(it) } ?: DSL.noCondition())
            .and(keyword?.let { J.TASK_NAME.contains(it) } ?: DSL.noCondition())
            .orderBy(J.LAST_ID.desc())
        return select.page(offset, limit).fetch()
    }

    fun listBuildViews(
        dslContext: DSLContext,
        scopeKey: String,
        pipelineId: String?,
        offset: Int,
        limit: Int
    ): List<TDispatchTpaHistoryBuildRecord> {
        return dslContext.selectFrom(B)
            .where(B.SCOPE_KEY.eq(scopeKey))
            .and(pipelineId?.let { B.PIPELINE_ID.eq(it) } ?: DSL.noCondition())
            .orderBy(B.LAST_ID.desc())
            .limit(limit).offset(offset)
            .fetch()
    }

    fun getBuildViews(
        dslContext: DSLContext,
        scopeKey: String,
        buildIds: Collection<String>
    ): List<TDispatchTpaHistoryBuildRecord> {
        if (buildIds.isEmpty()) return emptyList()
        return dslContext.selectFrom(B)
            .where(B.SCOPE_KEY.eq(scopeKey))
            .and(B.BUILD_ID.`in`(buildIds))
            .fetch()
    }

    /** 范围内的执行人，沿 IDX_SCOPE_USER 去重，代价与执行人数相关，与构建数无关 */
    fun listCreators(dslContext: DSLContext, scopeKey: String): List<String> {
        return dslContext.selectDistinct(B.START_USER).from(B)
            .where(B.SCOPE_KEY.eq(scopeKey))
            .and(B.START_USER.isNotNull)
            .fetch(B.START_USER)
    }

    /**
     * 按时间筛选时强制走 (范围, CREATED_TIME) 索引：范围内的数据都在同一个 AGENT_ID / ENV_KEY 下，
     * 优化器会改用 IDX_*_PIPELINE_JOB 的等值查找，读出整个范围后再按时间过滤（实测 7 天窗口多读 7 倍以上的行）。
     * 指定了构建、流水线或 Job 时这些条件更精确，交给优化器选择。
     */
    private fun TpaHistoryWhere.source(): Table<TDispatchTpaHistoryRecord> {
        if ((startTime == null && endTime == null) || buildIds != null ||
            !pipelineId.isNullOrBlank() || !jobId.isNullOrBlank()
        ) {
            return H
        }
        return H.forceIndex(if (agentId != null) IDX_AGENT_TIME else IDX_ENV_TIME)
    }

    private fun TpaHistoryWhere.conditions(): List<Condition> = listOfNotNull(
        agentId?.let { H.AGENT_ID.eq(it) },
        envKey?.let { H.ENV_KEY.eq(it) },
        startTime?.let { H.CREATED_TIME.ge(it.toTime()) },
        endTime?.let { H.CREATED_TIME.le(it.toTime()) },
        pipelineId?.takeIf { it.isNotBlank() }?.let { H.PIPELINE_ID.eq(it) },
        jobId?.takeIf { it.isNotBlank() }?.let { H.JOB_ID.eq(it) },
        creator?.takeIf { it.isNotBlank() }?.let { H.START_USER.eq(it) },
        status?.let { H.STATUS.`in`(it) },
        buildIds?.let { H.BUILD_ID.`in`(it) },
        executeCount?.let { H.EXECUTE_COUNT.eq(it) }
    )

    private fun <R : org.jooq.Record> SelectLimitStep<R>.page(offset: Int?, limit: Int?) =
        if (limit == null) this else this.limit(limit).offset(offset ?: 0)

    private fun Long.toTime() = LocalDateTime.ofInstant(Instant.ofEpochSecond(this), ZoneId.systemDefault())

    companion object {
        private val S = TDispatchThirdpartyAgentBuild.T_DISPATCH_THIRDPARTY_AGENT_BUILD
        private val H = TDispatchTpaHistory.T_DISPATCH_TPA_HISTORY
        private val B = TDispatchTpaHistoryBuild.T_DISPATCH_TPA_HISTORY_BUILD
        private val P = TDispatchTpaHistoryPipeline.T_DISPATCH_TPA_HISTORY_PIPELINE
        private val J = TDispatchTpaHistoryJob.T_DISPATCH_TPA_HISTORY_JOB
        private const val FLAG: Byte = 1
        private const val IDX_AGENT_TIME = "IDX_AGENT_TIME"
        private const val IDX_ENV_TIME = "IDX_ENV_TIME"
        private val FINISHED = listOf(PipelineTaskStatus.DONE.status, PipelineTaskStatus.FAILURE.status)

        /** 分组内按 ID 倒序取第一个值，即最新一行的值 */
        private fun latestOf(field: Field<String>): Field<String> = DSL.field(
            "SUBSTRING_INDEX(GROUP_CONCAT({0} ORDER BY {1} DESC SEPARATOR ','), ',', 1)",
            String::class.java,
            field,
            H.ID
        )

        fun pipelineView(
            projectId: String,
            pipelineId: String,
            pipelineName: String,
            buildCount: Int,
            lastBuildId: String?
        ) = TPAPipelineBuild(
            projectId = projectId,
            pipelineId = pipelineId,
            pipelineName = pipelineName,
            jobId = null,
            jobName = null,
            buildCount = buildCount,
            lastBuildTime = null,
            avgTimeInterval = null,
            lastContainerId = null,
            stageId = null,
            stageNumb = null,
            buildId = lastBuildId,
            executeCount = null
        )

        fun buildView(
            projectId: String,
            pipelineId: String,
            pipelineName: String,
            buildId: String,
            executeCount: Int?,
            buildNum: Int?
        ) = TPAPipelineBuild(
            projectId = projectId,
            pipelineId = pipelineId,
            pipelineName = pipelineName,
            jobId = null,
            jobName = null,
            buildCount = 0,
            lastBuildTime = null,
            avgTimeInterval = null,
            lastContainerId = null,
            stageId = null,
            stageNumb = null,
            buildId = buildId,
            executeCount = executeCount,
            buildNum = buildNum
        )
    }
}
