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

import com.tencent.devops.common.client.Client
import com.tencent.devops.common.webhook.pojo.code.git.GitCommitRepository
import com.tencent.devops.common.webhook.pojo.code.git.GitPushEvent
import com.tencent.devops.stream.config.StreamGitConfig
import com.tencent.devops.stream.dao.GitPipelineResourceDao
import com.tencent.devops.stream.dao.GitRequestEventBuildDao
import com.tencent.devops.stream.pojo.TriggerReviewSetting
import com.tencent.devops.stream.service.StreamBasicSettingService
import com.tencent.devops.stream.service.StreamPipelineBranchService
import com.tencent.devops.stream.trigger.actions.data.ActionData
import com.tencent.devops.stream.trigger.actions.data.EventCommonData
import com.tencent.devops.stream.trigger.actions.data.EventCommonDataCommit
import com.tencent.devops.stream.trigger.actions.data.StreamTriggerPipeline
import com.tencent.devops.stream.trigger.actions.data.StreamTriggerSetting
import com.tencent.devops.stream.trigger.actions.data.context.StreamTriggerContext
import com.tencent.devops.stream.trigger.actions.tgit.TGitPushActionGit
import com.tencent.devops.stream.trigger.exception.StreamTriggerException
import com.tencent.devops.stream.trigger.git.service.TGitApiService
import com.tencent.devops.stream.trigger.parsers.PipelineDelete
import com.tencent.devops.stream.trigger.parsers.triggerMatch.TriggerBody
import com.tencent.devops.stream.trigger.parsers.triggerMatch.TriggerMatcher
import com.tencent.devops.stream.trigger.parsers.triggerMatch.TriggerResult
import com.tencent.devops.stream.trigger.parsers.yamlCheck.YamlSchemaCheck
import com.tencent.devops.stream.trigger.service.DeleteEventService
import com.tencent.devops.stream.trigger.service.GitCheckService
import com.tencent.devops.stream.trigger.service.StreamEventService
import com.tencent.devops.stream.trigger.template.YamlTemplateService
import com.tencent.devops.stream.trigger.timer.service.StreamTimerService
import io.mockk.every
import io.mockk.mockk
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

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
    private val streamGitConfig = mockk<StreamGitConfig>()

    private val trigger = StreamYamlTrigger(
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

    @Nested
    @DisplayName("检查触发函数测试组")
    inner class CheckAndTriggerTestCases {

        private val apiService = mockk<TGitApiService>()
        private val streamEventService = mockk<StreamEventService>()
        private val streamTimerService = mockk<StreamTimerService>()
        private val streamPipelineBranchService = mockk<StreamPipelineBranchService>()
        private val streamDeleteEventService = mockk<DeleteEventService>()
        private val gitPipelineResourceDao = mockk<GitPipelineResourceDao>()
        private val pipelineDelete = mockk<PipelineDelete>()
        private val gitCheckService = mockk<GitCheckService>()

        private val actionData = ActionData(
            event = GitPushEvent(
                before = "",
                after = "",
                ref = "",
                checkout_sha = "",
                user_name = "",
                project_id = 0L,
                repository = GitCommitRepository(
                    name = "",
                    url = "",
                    git_http_url = "",
                    git_ssh_url = "",
                    homepage = ""
                ),
                commits = null,
                total_commits_count = 0,
                operation_kind = null,
                action_kind = null,
                push_options = null,
                create_and_update = null
            ),
            context = StreamTriggerContext(
                pipeline = StreamTriggerPipeline(
                    gitProjectId = "",
                    pipelineId = "",
                    filePath = "",
                    displayName = "",
                    enabled = true,
                    creator = null
                )
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

        private val action = TGitPushActionGit(
            dslContext = dslContext,
            apiService = apiService,
            streamEventService = streamEventService,
            streamTimerService = streamTimerService,
            streamPipelineBranchService = streamPipelineBranchService,
            streamDeleteEventService = streamDeleteEventService,
            gitPipelineResourceDao = gitPipelineResourceDao,
            pipelineDelete = pipelineDelete,
            gitCheckService = gitCheckService
        ).also { it.data = actionData }

        @Test
        @DisplayName("使用触发器缓存相关测试")
        fun test_1() {
            val triggerStr = "trigger"
            every { triggerMatcher.isMatch(action, triggerStr) } returns Pair(
                null, TriggerResult(
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
    }
}
