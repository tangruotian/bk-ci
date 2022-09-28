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

package com.tencent.devops.stream.service

import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.client.Client
import com.tencent.devops.environment.api.thirdPartyAgent.UserThirdPartyAgentResource
import com.tencent.devops.environment.pojo.thirdPartyAgent.AgentBuildDetail
import com.tencent.devops.model.stream.tables.records.TGitPipelineResourceRecord
import com.tencent.devops.stream.config.StreamGitConfig
import com.tencent.devops.stream.dao.GitPipelineResourceDao
import com.tencent.devops.stream.dao.StreamBasicSettingDao
import io.mockk.every
import io.mockk.mockk
import java.lang.RuntimeException
import java.time.LocalDateTime
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested


@DisplayName("Stream项目设置Service测试")
internal class StreamBasicSettingServiceTest {
    // mockk生成对象写法
    private val dslContext = mockk<DSLContext>()
    private val client = mockk<Client>()
    private val streamBasicSettingDao = mockk<StreamBasicSettingDao>()
    private val pipelineResourceDao = mockk<GitPipelineResourceDao>()
    private val streamGitTransferService = mockk<StreamGitTransferService>()
    private val streamGitConfig = mockk<StreamGitConfig>()

    private val service = StreamBasicSettingService(
        dslContext = dslContext,
        client = client,
        streamBasicSettingDao = streamBasicSettingDao,
        pipelineResourceDao = pipelineResourceDao,
        streamGitTransferService = streamGitTransferService,
        streamGitConfig = streamGitConfig
    )

    @Nested
    @DisplayName("listAgentBuilds方法测试组")
    inner class ListAgentBuildsTestCases {
        private val userId = "testU"
        private val projectId = "git_123"
        private val nodeHashId = "testHashId"
        private val projectIdL = 123L

        @Test
        @DisplayName("测试 agentBuilds 请求失败异常抛出")
        fun test_1() {
            every {
                client.get(UserThirdPartyAgentResource::class)
                    .listAgentBuilds(userId, projectId, nodeHashId, null, null)
            } returns Result(1, null, null)

            Assertions.assertThrows(RuntimeException::class.java) {
                service.listAgentBuilds(userId, projectId, nodeHashId, null, null)
            }
        }

        @Test
        @DisplayName("测试 pipelineResourceDao 返回为空时")
        fun test_2() {
            every {
                client.get(UserThirdPartyAgentResource::class)
                    .listAgentBuilds(userId, projectId, nodeHashId, null, null)
            } returns Result(0, null, Page(0, 0, 0, emptyList()))

            every {
                pipelineResourceDao.getPipelinesInIds(
                    dslContext = dslContext,
                    gitProjectId = projectIdL,
                    pipelineIds = emptyList()
                )
            } returns emptyList()

            Assertions.assertEquals(
                service.listAgentBuilds(userId, projectId, nodeHashId, null, null),
                Page(0, 0, 0, emptyList<AgentBuildDetail>())
            )
        }

        @Test
        @DisplayName("测试正常执行")
        fun test_3() {
            every {
                client.get(UserThirdPartyAgentResource::class)
                    .listAgentBuilds(userId, projectId, nodeHashId, null, null)
            } returns Result(
                0, null, Page(
                    0, 0, 0, listOf(
                        AgentBuildDetail(
                            nodeId = "1",
                            agentId = "1",
                            projectId = "1",
                            pipelineId = "1",
                            pipelineName = "oldName",
                            buildId = "1",
                            buildNumber = 1,
                            vmSetId = "1",
                            taskName = "1",
                            status = "1",
                            createdTime = 1L,
                            updatedTime = 1L,
                            workspace = "1",
                            agentTask = null
                        ),
                        AgentBuildDetail(
                            nodeId = "1",
                            agentId = "1",
                            projectId = "1",
                            pipelineId = "2",
                            pipelineName = "oldName",
                            buildId = "1",
                            buildNumber = 1,
                            vmSetId = "1",
                            taskName = "1",
                            status = "1",
                            createdTime = 1L,
                            updatedTime = 1L,
                            workspace = "1",
                            agentTask = null
                        )
                    )
                )
            )

            every {
                pipelineResourceDao.getPipelinesInIds(
                    dslContext = dslContext,
                    gitProjectId = projectIdL,
                    pipelineIds = listOf("1", "2")
                )
            } returns listOf(
                TGitPipelineResourceRecord(
                    /* id = */ 1L,
                    /* gitProjectId = */ 1L,
                    /* filePath = */ "1",
                    /* pipelineId = */ "1",
                    /* displayName = */ "newName",
                    /* creator = */ "1",
                    /* enabled = */ true,
                    /* latestBuildId = */ "1",
                    /* createTime = */ LocalDateTime.now(),
                    /* updateTime = */ LocalDateTime.now(),
                    /* version = */ "1",
                    /* originYml = */ "1",
                    /* modifier = */ "1",
                    /* directory = */ "1",
                    /* lastUpdateBranch = */ "1",
                    /* scmType = */ "1",
                    /* lastEditModelMd5 = */ "1"
                )
            )

            Assertions.assertEquals(
                service.listAgentBuilds(userId, projectId, nodeHashId, null, null),
                Page(
                    0, 0, 0, listOf(
                        AgentBuildDetail(
                            nodeId = "1",
                            agentId = "1",
                            projectId = "1",
                            pipelineId = "1",
                            pipelineName = "newName",
                            buildId = "1",
                            buildNumber = 1,
                            vmSetId = "1",
                            taskName = "1",
                            status = "1",
                            createdTime = 1L,
                            updatedTime = 1L,
                            workspace = "1",
                            agentTask = null
                        ),
                        AgentBuildDetail(
                            nodeId = "1",
                            agentId = "1",
                            projectId = "1",
                            pipelineId = "2",
                            pipelineName = "oldName",
                            buildId = "1",
                            buildNumber = 1,
                            vmSetId = "1",
                            taskName = "1",
                            status = "1",
                            createdTime = 1L,
                            updatedTime = 1L,
                            workspace = "1",
                            agentTask = null
                        )
                    )
                )
            )
        }
    }
}
