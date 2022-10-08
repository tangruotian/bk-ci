/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.stream.trigger

import com.tencent.devops.common.api.enums.ScmType
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.webhook.pojo.code.git.GitCommit
import com.tencent.devops.common.webhook.pojo.code.git.GitCommitAuthor
import com.tencent.devops.common.webhook.pojo.code.git.GitMRAttributes
import com.tencent.devops.common.webhook.pojo.code.git.GitMergeRequestEvent
import com.tencent.devops.common.webhook.pojo.code.git.GitProject
import com.tencent.devops.common.webhook.pojo.code.git.GitUser
import com.tencent.devops.process.api.service.ServicePipelineSettingResource
import com.tencent.devops.process.yaml.v2.enums.TemplateType
import com.tencent.devops.process.yaml.v2.exception.YamlFormatException
import com.tencent.devops.process.yaml.v2.models.PreScriptBuildYaml
import com.tencent.devops.process.yaml.v2.models.PreTemplateScriptBuildYaml
import com.tencent.devops.process.yaml.v2.models.Resources
import com.tencent.devops.process.yaml.v2.models.ResourcesPools
import com.tencent.devops.process.yaml.v2.models.ScriptBuildYaml
import com.tencent.devops.process.yaml.v2.models.TemplateData
import com.tencent.devops.process.yaml.v2.models.TransferTemplateData
import com.tencent.devops.process.yaml.v2.models.YamlTransferData
import com.tencent.devops.process.yaml.v2.models.YmlName
import com.tencent.devops.process.yaml.v2.utils.ScriptYmlUtils
import com.tencent.devops.project.api.service.ServiceProjectResource
import com.tencent.devops.project.pojo.Result
import com.tencent.devops.stream.config.StreamGitConfig
import com.tencent.devops.stream.dao.GitRequestEventBuildDao
import com.tencent.devops.stream.pojo.TriggerReviewSetting
import com.tencent.devops.stream.pojo.enums.TriggerReason
import com.tencent.devops.stream.service.StreamBasicSettingService
import com.tencent.devops.stream.trigger.actions.data.ActionData
import com.tencent.devops.stream.trigger.actions.data.EventCommonData
import com.tencent.devops.stream.trigger.actions.data.EventCommonDataCommit
import com.tencent.devops.stream.trigger.actions.data.StreamTriggerPipeline
import com.tencent.devops.stream.trigger.actions.data.StreamTriggerSetting
import com.tencent.devops.stream.trigger.actions.data.context.StreamTriggerContext
import com.tencent.devops.stream.trigger.actions.tgit.TGitMrActionGit
import com.tencent.devops.stream.trigger.exception.StreamTriggerBaseException
import com.tencent.devops.stream.trigger.exception.StreamTriggerException
import com.tencent.devops.stream.trigger.exception.YamlBehindException
import com.tencent.devops.stream.trigger.exception.YamlBlankException
import com.tencent.devops.stream.trigger.git.pojo.tgit.TGitProjectInfo
import com.tencent.devops.stream.trigger.git.service.TGitApiService
import com.tencent.devops.stream.trigger.parsers.PipelineDelete
import com.tencent.devops.stream.trigger.parsers.triggerMatch.TriggerBody
import com.tencent.devops.stream.trigger.parsers.triggerMatch.TriggerMatcher
import com.tencent.devops.stream.trigger.parsers.triggerMatch.TriggerResult
import com.tencent.devops.stream.trigger.parsers.yamlCheck.YamlFormat
import com.tencent.devops.stream.trigger.parsers.yamlCheck.YamlSchemaCheck
import com.tencent.devops.stream.trigger.pojo.MrYamlInfo
import com.tencent.devops.stream.trigger.pojo.YamlReplaceResult
import com.tencent.devops.stream.trigger.service.GitCheckService
import com.tencent.devops.stream.trigger.template.YamlTemplateService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any

@DisplayName("Stream Yaml触发过程测试")
internal class StreamYamlTriggerTest {

    private val client = mockk<Client>()
    private val dslContext = mockk<DSLContext>()
    private val triggerMatcher = mockk<TriggerMatcher>()
    private val yamlSchemaCheck = mockk<YamlSchemaCheck>()
    private val yamlTemplateService = mockk<YamlTemplateService>()
    private val streamBasicSettingService = mockk<StreamBasicSettingService>()
    private val yamlBuild = mockk<StreamYamlBuild>()
    private val gitRequestEventBuildDao = mockk<GitRequestEventBuildDao>()
    private val streamYamlBaseBuild = mockk<StreamYamlBaseBuild>()

    // TODO: 存在IDEA进行单元测试时报错，重试后可以恢复
    private val streamGitConfig = mockk<StreamGitConfig> {
        every { getScmType() } returns ScmType.CODE_GIT
    }

    private val trigger = spyk(
        StreamYamlTrigger(
            client = client,
            dslContext = dslContext,
            triggerMatcher = triggerMatcher,
            yamlSchemaCheck = yamlSchemaCheck,
            yamlTemplateService = yamlTemplateService,
            streamBasicSettingService = streamBasicSettingService,
            yamlBuild = yamlBuild,
            gitRequestEventBuildDao = gitRequestEventBuildDao,
            streamYamlBaseBuild = streamYamlBaseBuild,
            streamGitConfig = streamGitConfig
        )
    )

    private val apiService = mockk<TGitApiService>()
    private val pipelineDelete = mockk<PipelineDelete>()
    private val gitCheckService = mockk<GitCheckService>()

    // 测试方法中只对mr action有分支条件，所以只使用mr action对象测试
    private lateinit var action: TGitMrActionGit

    private lateinit var actionData: ActionData

    // 每次都init一次新的action，保证每个测试可以随意修改
    @BeforeEach
    fun initAction() {
        actionData = ActionData(
            event = GitMergeRequestEvent(
                user = GitUser("", ""),
                manual_unlock = false,
                GitMRAttributes(
                    id = 0L,
                    target_branch = "",
                    source_branch = "",
                    author_id = 0L,
                    assignee_id = 0L,
                    title = "",
                    created_at = "",
                    updated_at = "",
                    state = "",
                    merge_status = "",
                    target_project_id = 0L,
                    source_project_id = 0L,
                    iid = 0L,
                    description = null,
                    source = GitProject("", "", "", "", "", 0),
                    target = GitProject("", "", "", "", "", 0),
                    last_commit = GitCommit("", "", "", GitCommitAuthor("", ""), null, null, null),
                    url = null,
                    action = null,
                    extension_action = null
                )
            ),
            context = StreamTriggerContext(
                pipeline = StreamTriggerPipeline(
                    gitProjectId = "",
                    pipelineId = "",
                    filePath = "",
                    displayName = "",
                    enabled = true,
                    creator = null
                ),
                changeSet = setOf(""),
                originYaml = "",
                requestEventId = 0L
            )
        ).also {
            it.eventCommon = EventCommonData(
                gitProjectId = "",
                scmType = null,
                branch = "",
                commit = EventCommonDataCommit("", null, null, null),
                userId = "",
                gitProjectName = null
            )
            it.setting = StreamTriggerSetting(
                enableCi = true,
                buildPushedBranches = true,
                buildPushedPullRequest = true,
                enableUser = "",
                gitHttpUrl = "",
                projectCode = null,
                enableCommitCheck = true,
                enableMrBlock = true,
                name = "",
                enableMrComment = true,
                homepage = "",
                triggerReviewSetting = TriggerReviewSetting()
            )
        }
        action = spyk(
            spyk(
                TGitMrActionGit(
                    dslContext = dslContext,
                    streamSettingDao = mockk(),
                    apiService = apiService,
                    mrConflictCheck = mockk(),
                    pipelineDelete = pipelineDelete,
                    gitCheckService = gitCheckService,
                    streamTriggerTokenService = mockk()
                ).also { it.data = actionData }
            )
        )
    }

    @Nested
    @DisplayName("checkAndTrigger 测试组")
    inner class CheckAndTriggerTestCases {

        @Test
        @DisplayName("使用触发器缓存 | 分支测试")
        fun test_1() {
            val triggerStr = "trigger"
            every { triggerMatcher.isMatch(action, triggerStr) } returns Pair(
                null,
                TriggerResult(
                    trigger = TriggerBody(trigger = false),
                    startParams = emptyMap(),
                    timeTrigger = true,
                    deleteTrigger = true
                )
            )
            Assertions.assertThrows(StreamTriggerException::class.java) {
                trigger.checkAndTrigger(action, triggerStr)
            }
        }

        @Test
        @DisplayName("流水线未启用 | 分支测试")
        fun test_2() {
            actionData.context.pipeline = actionData.context.pipeline?.copy(enabled = false)
            Assertions.assertThrows(StreamTriggerException::class.java) {
                trigger.checkAndTrigger(action, null)
            }
        }

        @Test
        @DisplayName("获取文件为空 | 分支测试")
        fun test_3() {
            every { action.getYamlContent("") } returns MrYamlInfo("", "", "")
            Assertions.assertThrows(StreamTriggerException::class.java) {
                trigger.checkAndTrigger(action, null)
            }
        }

        @Test
        @DisplayName("整体覆盖测试")
        fun test_4() {
            every { action.getYamlContent("") } returns MrYamlInfo("ref", "content", "blobId")

            justRun { yamlSchemaCheck.check(action = action, templateType = null, isCiFile = true) }

            // Mr 类型分支测试 打点判断
            every { trigger.trigger(action, any()) } answers {
                Assertions.assertEquals(action.data.context.triggerCache?.pipelineFileBranch, "ref")
                Assertions.assertEquals(action.data.context.triggerCache?.blobId, "blobId")
            }

            trigger.checkAndTrigger(action, null)
        }
    }

    @Test
    @DisplayName("trigger 测试")
    fun trigger() {
        every { trigger.triggerBuild(action, null) } returns true
        trigger.trigger(action, null)
    }

    @Nested
    @DisplayName("triggerBuild 测试组")
    inner class TriggerBuildTest {

        @Test
        @DisplayName("获取pipelineAsCodeSetting抛出异常，不触发 | 分支测试")
        fun test_1() {
            actionData.context.pipeline = actionData.context.pipeline?.copy(
                pipelineId = "p_123",
                gitProjectId = "123"
            )

            justRun { action.updatePipelineLastBranchAndDisplayName(any(), any(), any()) }

            // 获取pipelineAsCodeSetting抛出异常 覆盖测试
            every {
                client.get(ServicePipelineSettingResource::class).getPipelineSetting(any(), any(), any())
            } throws RuntimeException()

            every { apiService.getGitProjectInfo(any(), any(), any()) } returns TGitProjectInfo(
                gitProjectId = "",
                defaultBranch = null,
                gitHttpUrl = "",
                name = "",
                gitSshUrl = null,
                homepage = null,
                gitHttpsUrl = null,
                description = null,
                avatarUrl = null,
                pathWithNamespace = null,
                nameWithNamespace = ""
            )

            Assertions.assertThrows(StreamTriggerException::class.java) {
                trigger.triggerBuild(
                    action,
                    Pair(
                        null,
                        TriggerResult(
                            trigger = TriggerBody(false),
                            startParams = emptyMap(),
                            timeTrigger = false,
                            deleteTrigger = false
                        )
                    )
                )
            }
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "流水线ID为空 | 分支测试",
                "流水线ID为空，yamlObject.name.isBlank | 分支测试",
            ]
        )
        fun test_2(param: String) {
            actionData.context.pipeline = actionData.context.pipeline?.copy(
                gitProjectId = "123"
            )

            justRun { streamYamlBaseBuild.createNewPipeLine(any(), any(), any()) }

            // 获取pipelineAsCodeSetting抛出异常 覆盖测试
            every {
                client.get(ServiceProjectResource::class).get(any())
            } returns Result(null)

            every { apiService.getGitProjectInfo(any(), any(), any()) } returns TGitProjectInfo(
                gitProjectId = "123",
                defaultBranch = null,
                gitHttpUrl = "",
                name = "",
                gitSshUrl = null,
                homepage = null,
                gitHttpsUrl = null,
                description = null,
                avatarUrl = null,
                pathWithNamespace = null,
                nameWithNamespace = ""
            )

            every { triggerMatcher.isMatch(action) } returns TriggerResult(
                trigger = TriggerBody(true),
                startParams = emptyMap(),
                timeTrigger = true,
                deleteTrigger = true,
                repoHookName = listOf("")
            )

            every { trigger.prepareCIBuildYaml(action) } returns YamlReplaceResult(
                PreScriptBuildYaml(
                    version = null,
                    name = null,
                    label = null,
                    triggerOn = null,
                    variables = null,
                    stages = null,
                    jobs = null,
                    steps = null,
                    extends = null,
                    resources = null,
                    notices = null,
                    finally = null,
                    concurrency = null
                ),
                ScriptBuildYaml(
                    version = null,
                    name = if (param == "流水线ID为空，yamlObject.name.isBlank | 分支测试") {
                        ""
                    } else {
                        null
                    },
                    label = null,
                    triggerOn = null,
                    variables = null,
                    stages = listOf(),
                    extends = null,
                    resource = null,
                    notices = null,
                    finally = null,
                    concurrency = null
                ),
                null
            )

            action.data.context.originYaml = "yaml"
            mockkObject(ScriptYmlUtils)
            every { ScriptYmlUtils.parseName("yaml") } returns YmlName("name")

            every { streamBasicSettingService.updateProjectInfo(any(), any()) } returns true

            every { yamlBuild.gitStartBuild(any(), any(), any(), any(), any(), any(), any()) } returns null

            every {
                gitRequestEventBuildDao.save(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any()
                )
            } returns 0L

            Assertions.assertTrue(trigger.triggerBuild(action, null))
        }
    }

    @Nested
    @DisplayName("prepareCIBuildYaml 测试组")
    inner class PrepareCIBuildYamlTest {

        @DisplayName("originYaml 为空 | 分支测试")
        @Test
        fun test_1() {
            actionData.context.originYaml = ""
            Assertions.assertEquals(null, trigger.prepareCIBuildYaml(action))
        }

        @DisplayName("整体覆盖测试")
        @Test
        fun test_2() {
            mockkObject(YamlFormat)
            mockkObject(ScriptYmlUtils)

            action.data.context.originYaml = "version: v2.0"

            every { YamlFormat.formatYaml(action) } returns PreTemplateScriptBuildYaml(
                version = null,
                name = null,
                label = null,
                triggerOn = null,
                variables = null,
                stages = null,
                jobs = null,
                steps = null,
                extends = null,
                resources = Resources(
                    null, pools = listOf(ResourcesPools("", ""))
                ),
                notices = null,
                finally = null,
                concurrency = null
            )

            // 测试1：正常返回，分支覆盖
            val expectData = YamlTransferData(
                TemplateData(
                    transferDataMap = mutableMapOf(
                        "" to TransferTemplateData("", TemplateType.VARIABLE, "")
                    )
                )
            )

            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } returns Pair(
                ScriptBuildYaml(
                    version = null,
                    name = null,
                    label = null,
                    triggerOn = null,
                    variables = null,
                    stages = emptyList(),
                    extends = null,
                    resource = null,
                    notices = null,
                    finally = null,
                    concurrency = null
                ),
                expectData
            )

            Assertions.assertTrue(trigger.prepareCIBuildYaml(action)!!.yamlTransferData == expectData)

            // 测试2：正常返回，transferData.templateData.transferDataMap.isEmpty()，分支覆盖
            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } returns Pair(
                ScriptBuildYaml(
                    version = null,
                    name = null,
                    label = null,
                    triggerOn = null,
                    variables = null,
                    stages = emptyList(),
                    extends = null,
                    resource = null,
                    notices = null,
                    finally = null,
                    concurrency = null
                ),
                YamlTransferData()
            )

            Assertions.assertTrue(trigger.prepareCIBuildYaml(action)!!.yamlTransferData == null)

            // 测试3：抛出 YamlBlankException 异常，分支覆盖
            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } throws YamlBlankException("", "")

            Assertions.assertEquals(
                TriggerReason.CI_YAML_CONTENT_NULL,
                Assertions.assertThrows(StreamTriggerException::class.java) {
                    trigger.prepareCIBuildYaml(action)
                }.triggerReason
            )

            // 测试4：抛出 YamlBehindException 异常，分支覆盖
            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } throws YamlBehindException("")

            Assertions.assertEquals(
                TriggerReason.CI_YAML_NEED_MERGE_OR_REBASE,
                Assertions.assertThrows(StreamTriggerException::class.java) {
                    trigger.prepareCIBuildYaml(action)
                }.triggerReason
            )

            // 测试5：抛出 YamlFormatException 异常，分支覆盖
            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } throws YamlFormatException("")

            Assertions.assertEquals(
                TriggerReason.CI_YAML_TEMPLATE_ERROR,
                Assertions.assertThrows(StreamTriggerException::class.java) {
                    trigger.prepareCIBuildYaml(action)
                }.triggerReason
            )

            // 测试6：抛出 StackOverflowError 异常，分支覆盖
            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } throws StackOverflowError()

            Assertions.assertEquals(
                TriggerReason.CI_YAML_TEMPLATE_ERROR,
                Assertions.assertThrows(StreamTriggerException::class.java) {
                    trigger.prepareCIBuildYaml(action)
                }.triggerReason
            )

            // 测试7：抛出 StreamTriggerBaseException 异常，分支覆盖
            every {
                ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any())
            } throws StreamTriggerBaseException(action, null, null)

            Assertions.assertThrows(StreamTriggerBaseException::class.java) { trigger.prepareCIBuildYaml(action) }

            // 测试8：抛出 未知异常，分支覆盖
            every { ScriptYmlUtils.normalizeGitCiYaml(any(), any(), any()) } throws RuntimeException()

            Assertions.assertEquals(
                TriggerReason.UNKNOWN_ERROR,
                Assertions.assertThrows(StreamTriggerException::class.java) {
                    trigger.prepareCIBuildYaml(action)
                }.triggerReason
            )
        }
    }

    @DisplayName("补齐行覆盖率")
    @Test
    fun demoTest() {
        Assertions.assertEquals("v2.0", StreamYamlTrigger.ymlVersion)
        Assertions.assertEquals("STREAM_TEMPLATE_ROOT_FILE", StreamYamlTrigger.STREAM_TEMPLATE_ROOT_FILE)
    }
}
