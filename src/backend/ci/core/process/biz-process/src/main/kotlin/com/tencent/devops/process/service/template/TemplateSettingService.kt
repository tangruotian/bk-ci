package com.tencent.devops.process.service.template

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.pojo.setting.PipelineSetting
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.dao.PipelineSettingDao
import com.tencent.devops.process.dao.PipelineSettingVersionDao
import com.tencent.devops.process.engine.dao.PipelineInfoDao
import com.tencent.devops.process.engine.dao.template.TemplateDao
import com.tencent.devops.process.engine.service.PipelineInfoExtService
import com.tencent.devops.process.engine.service.PipelineRepositoryService
import com.tencent.devops.process.service.label.PipelineGroupService
import com.tencent.devops.process.utils.PipelineVersionUtils
import com.tencent.devops.project.api.service.ServiceAllocIdResource
import com.tencent.devops.project.api.service.ServiceProjectResource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 将 template 中 setting 的改动抽出，因为 PAC改动会上新的版本逻辑，可能会涉及
 */
@Service
class TemplateSettingService @Autowired constructor(
    private val dslContext: DSLContext,
    private val client: Client,
    private val pipelineGroupService: PipelineGroupService,
    private val pipelineInfoDao: PipelineInfoDao,
    private val pipelineSettingVersionDao: PipelineSettingVersionDao,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val pipelineSettingDao: PipelineSettingDao,
    private val pipelineInfoExtService: PipelineInfoExtService,
    private val templateCommonService: TemplateCommonService,
    private val templateDao: TemplateDao
) {
    fun updateTemplateSetting(
        projectId: String,
        userId: String,
        templateId: String,
        setting: PipelineSetting
    ): Boolean {
        logger.info("Start to update the template setting - [$projectId|$userId|$templateId]")
        templateCommonService.checkPermission(projectId, userId)
        dslContext.transaction { configuration ->
            val context = DSL.using(configuration)
            templateCommonService.checkTemplateName(
                dslContext = context,
                name = setting.pipelineName,
                projectId = projectId,
                templateId = templateId
            )
            templateDao.updateNameAndDescById(
                dslContext = dslContext,
                projectId = projectId,
                templateId = templateId,
                name = setting.pipelineName,
                desc = setting.desc
            )
            saveTemplatePipelineSetting(userId, setting, true)
        }
        return true
    }

    fun saveTemplatePipelineSetting(
        userId: String,
        setting: PipelineSetting,
        isTemplate: Boolean = false
    ): Int {
        pipelineGroupService.updatePipelineLabel(
            userId = userId,
            projectId = setting.projectId,
            pipelineId = setting.pipelineId,
            labelIds = setting.labels
        )
        pipelineInfoDao.update(
            dslContext = dslContext,
            projectId = setting.projectId,
            pipelineId = setting.pipelineId,
            userId = userId,
            updateVersion = false,
            pipelineName = setting.pipelineName,
            pipelineDesc = setting.desc
        )

        val originSetting = pipelineRepositoryService.getSetting(
            projectId = setting.projectId,
            pipelineId = setting.pipelineId
        )
        if (originSetting != null) {
            val settingVersion = PipelineVersionUtils.getSettingVersion(originSetting.version, originSetting, setting)
            // 版本不一样说明变动了，保存下版本信息
            if (settingVersion != originSetting.version) {
                pipelineSettingVersionDao.deleteEarlyVersion(
                    dslContext = dslContext,
                    projectId = setting.projectId,
                    pipelineId = setting.pipelineId,
                    currentVersion = settingVersion,
                    maxPipelineResNum = originSetting.maxPipelineResNum
                )
                pipelineSettingVersionDao.saveSetting(
                    dslContext = dslContext,
                    setting = setting,
                    version = settingVersion,
                    id = client.get(ServiceAllocIdResource::class).generateSegmentId(
                        PIPELINE_SETTING_VERSION_BIZ_TAG_NAME
                    ).data
                )
            }
        }

        logger.info("Save the template pipeline setting - ($setting)")
        return pipelineSettingDao.saveSetting(dslContext, setting, isTemplate)
    }

    fun insertTemplateSetting(
        context: DSLContext,
        projectId: String,
        templateId: String,
        pipelineName: String,
        isTemplate: Boolean
    ) {
        pipelineSettingDao.insertNewSetting(
            dslContext = context,
            projectId = projectId,
            pipelineId = templateId,
            pipelineName = pipelineName,
            isTemplate = isTemplate,
            failNotifyTypes = pipelineInfoExtService.failNotifyChannel(),
            pipelineAsCodeSettings = try {
                client.get(ServiceProjectResource::class).get(projectId).data
                    ?.properties?.pipelineAsCodeSettings
            } catch (ignore: Throwable) {
                logger.warn("[$projectId]|Failed to sync project|templateId=$templateId", ignore)
                null
            },
            settingVersion = 1
        )
    }

    fun copySetting(setting: PipelineSetting, pipelineId: String, templateName: String): PipelineSetting {
        with(setting) {
            return PipelineSetting(
                projectId = projectId,
                pipelineId = pipelineId,
                pipelineName = templateName,
                desc = desc,
                runLockType = runLockType,
                successSubscription = successSubscription,
                failSubscription = failSubscription,
                successSubscriptionList = successSubscriptionList,
                failSubscriptionList = failSubscriptionList,
                labels = labels,
                waitQueueTimeMinute = waitQueueTimeMinute,
                maxQueueSize = maxQueueSize,
                concurrencyGroup = concurrencyGroup,
                hasPermission = hasPermission,
                maxPipelineResNum = maxPipelineResNum,
                maxConRunningQueueSize = maxConRunningQueueSize,
                pipelineAsCodeSettings = pipelineAsCodeSettings
            )
        }
    }

    fun getTemplateSetting(projectId: String, userId: String, templateId: String): PipelineSetting {
        val setting = pipelineRepositoryService.getSetting(projectId, templateId)
        if (setting == null) {
            logger.warn("Fail to get the template setting - [$projectId|$userId|$templateId]")
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.PIPELINE_SETTING_NOT_EXISTS
            )
        }
        val hasPermission = templateCommonService.hasManagerPermission(userId = userId, projectId = projectId)
        val groups = pipelineGroupService.getGroups(userId, projectId, templateId)
        val labels = ArrayList<String>()
        groups.forEach {
            labels.addAll(it.labels)
        }
        setting.labels = labels
        setting.hasPermission = hasPermission
        return setting
    }

    companion object {
        private const val PIPELINE_SETTING_VERSION_BIZ_TAG_NAME = "PIPELINE_SETTING_VERSION"
        private val logger = LoggerFactory.getLogger(TemplateSettingService::class.java)
    }
}
