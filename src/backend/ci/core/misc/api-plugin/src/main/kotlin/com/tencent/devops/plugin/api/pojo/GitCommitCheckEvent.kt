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

package com.tencent.devops.plugin.api.pojo

import com.tencent.devops.common.api.enums.RepositoryConfig
import com.tencent.devops.common.event.annotation.Event
import com.tencent.devops.common.event.dispatcher.pipeline.mq.MQ
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.event.pojo.pipeline.IPipelineEvent

/**
 * TGit提交检查事件
 */
@Event(MQ.EXCHANGE_GIT_COMMIT_CHECK, MQ.ROUTE_GIT_COMMIT_CHECK)
data class GitCommitCheckEvent(
    override val projectId: String,
    override val pipelineId: String,
    val buildId: String,
    val repositoryConfig: RepositoryConfig,
    val commitId: String,
    val state: String,
    val block: Boolean,
    val status: String = "",
    val triggerType: String = "",
    val startTime: Long = 0L,
    val mergeRequestId: Long? = null,
    val targetBranch: String?,
    override var actionType: ActionType = ActionType.REFRESH,
    override val source: String,
    override val userId: String,
    override var delayMills: Int = 0,
    override var retryTime: Int = 3
) : IPipelineEvent(actionType, source, projectId, pipelineId, userId, delayMills, retryTime)