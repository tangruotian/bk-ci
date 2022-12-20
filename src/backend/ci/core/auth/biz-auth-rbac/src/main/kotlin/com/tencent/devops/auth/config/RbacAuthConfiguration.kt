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
 *
 */

package com.tencent.devops.auth.config

import com.tencent.bk.sdk.iam.config.IamConfiguration
import com.tencent.bk.sdk.iam.service.impl.ApigwHttpClientServiceImpl
import com.tencent.bk.sdk.iam.service.v2.V2ManagerService
import com.tencent.bk.sdk.iam.service.v2.impl.V2GrantServiceImpl
import com.tencent.bk.sdk.iam.service.v2.impl.V2ManagerServiceImpl
import com.tencent.bk.sdk.iam.service.v2.impl.V2PolicyServiceImpl
import com.tencent.devops.auth.dao.AuthDefaultGroupDao
import com.tencent.devops.auth.service.AuthGroupService
import com.tencent.devops.auth.service.AuthResourceService
import com.tencent.devops.auth.service.RbacPermissionExtService
import com.tencent.devops.auth.service.RbacPermissionResourceService
import com.tencent.devops.auth.service.ResourceGroupService
import com.tencent.devops.auth.service.StrategyService
import com.tencent.devops.auth.service.iam.PermissionScopesService
import com.tencent.devops.auth.service.iam.PermissionResourceService
import com.tencent.devops.common.client.Client
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "auth", name = ["idProvider"], havingValue = "rbac")
class RbacAuthConfiguration {

    @Value("\${auth.url:}")
    val iamBaseUrl = ""

    @Value("\${auth.iamSystem:}")
    val systemId = ""

    @Value("\${auth.appCode:}")
    val appCode = ""

    @Value("\${auth.appSecret:}")
    val appSecret = ""

    @Value("\${auth.apigwUrl:#{null}}")
    val iamApigw = ""

    @Bean
    @ConditionalOnMissingBean
    fun iamConfiguration() = IamConfiguration(systemId, appCode, appSecret, iamBaseUrl, iamApigw)

    @Bean
    fun apigwHttpClientServiceImpl() = ApigwHttpClientServiceImpl(iamConfiguration())

    @Bean
    fun iamV2ManagerService() = V2ManagerServiceImpl(apigwHttpClientServiceImpl(), iamConfiguration())

    @Bean
    fun iamV2PolicyService() = V2PolicyServiceImpl(apigwHttpClientServiceImpl(), iamConfiguration())

    @Bean
    fun grantV2Service() = V2GrantServiceImpl(apigwHttpClientServiceImpl(), iamConfiguration())

    @Bean
    @SuppressWarnings("LongParameterList")
    fun permissionResourceService(
        client: Client,
        permissionScopesService: PermissionScopesService,
        iamV2ManagerService: V2ManagerService,
        authResourceService: AuthResourceService,
        groupService: AuthGroupService,
        strategyService: StrategyService,
        dslContext: DSLContext,
        authDefaultGroupDao: AuthDefaultGroupDao,
        resourceGroupService: ResourceGroupService
    ) = RbacPermissionResourceService(
        client = client,
        permissionScopesService = permissionScopesService,
        iamV2ManagerService = iamV2ManagerService,
        authResourceService = authResourceService,
        groupService = groupService,
        strategyService = strategyService,
        dslContext = dslContext,
        authDefaultGroupDao = authDefaultGroupDao,
        resourceGroupService = resourceGroupService
    )

    @Bean
    fun rbacPermissionExtService(
        permissionResourceService: PermissionResourceService
    ) = RbacPermissionExtService(
        permissionResourceService = permissionResourceService,
    )
}
