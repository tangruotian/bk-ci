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

package com.tencent.devops.process.service.template

import com.tencent.devops.model.process.tables.TTemplate
import com.tencent.devops.process.engine.dao.template.TemplateDao
import com.tencent.devops.process.pojo.template.TemplateListModel
import com.tencent.devops.process.pojo.template.TemplateModel
import com.tencent.devops.process.pojo.template.TemplateScopeType
import com.tencent.devops.process.pojo.template.TemplateStatus
import com.tencent.devops.process.pojo.template.TemplateType
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 因为 PAC 的改动较多，所以将pac中的的代码较大专门抽出来，方便排查错误以及不要影响历史接口
 */
@Service
class TemplatePACService @Autowired constructor(
    private val facadeService: TemplateFacadeService,
    private val templateDao: TemplateDao,
    private val dslContext: DSLContext
) {
    // 针对蓝盾界面用户查看的模板列表接口，相较于 listTemplate 函数实现上会有区别，所以抽出
    // 需要实现 如果只有草稿版本则返回草稿版本，如果同时有草稿和发布版本，则需要搜索发布版本中的最新版本
    // 需要先搜索所有的，然后搜索已经被搜索出来的草稿版本的有没有发布版本
    fun listUserTemplate(
        projectId: String,
        userId: String,
        templateType: TemplateType?,
        storeFlag: Boolean?,
        page: Int? = null,
        pageSize: Int? = null,
        filterByTemplateName: String? = null,
        filterByTemplateDesc: String? = null,
        filterByTemplateScopeType: TemplateScopeType? = null,
        filterByTemplateUpdateUser: String? = null
    ): TemplateListModel {
        val hasManagerPermission = facadeService.hasManagerPermission(projectId, userId)
        val result = ArrayList<TemplateModel>()
        // 老接口目前的状态都使用已发布和旧数据统一
        val count = templateDao.countTemplate(
            dslContext = dslContext,
            projectId = projectId,
            includePublicFlag = null,
            templateType = templateType,
            templateName = null,
            storeFlag = storeFlag,
            filterByTemplateName = filterByTemplateName,
            filterByTemplateDesc = filterByTemplateDesc,
            filterByTemplateScopeType = filterByTemplateScopeType,
            filterByTemplateUpdateUser = filterByTemplateUpdateUser,
            templateStatus = null
        )
        val templates = templateDao.listTemplate(
            dslContext = dslContext,
            projectId = projectId,
            includePublicFlag = null,
            templateType = templateType,
            templateIdList = null,
            storeFlag = storeFlag,
            page = page,
            pageSize = pageSize,
            queryModelFlag = false,
            filterByTemplateName = filterByTemplateName,
            filterByTemplateDesc = filterByTemplateDesc,
            filterByTemplateScopeType = filterByTemplateScopeType,
            filterByTemplateUpdateUser = filterByTemplateUpdateUser,
            templateStatus = null
        )?.toMutableList()
        if (templates.isNullOrEmpty()) {
            return TemplateListModel(projectId, hasManagerPermission, result, count)
        }
        // 将全部的实例都搜索出来
        val tTemplate = TTemplate.T_TEMPLATE
        val allTemplateIds = templates.filter { it[tTemplate.STATUS] == TemplateStatus.COMMITTING.name }
            .map { it[tTemplate.ID] }
            .toSet()
        // 再查询一次发布版本的
        val releaseT = templateDao.listTemplate(
            dslContext = dslContext,
            projectId = projectId,
            includePublicFlag = null,
            templateType = templateType,
            templateIdList = allTemplateIds,
            storeFlag = storeFlag,
            page = page,
            pageSize = pageSize,
            queryModelFlag = false,
            filterByTemplateName = filterByTemplateName,
            filterByTemplateDesc = filterByTemplateDesc,
            filterByTemplateScopeType = filterByTemplateScopeType,
            filterByTemplateUpdateUser = filterByTemplateUpdateUser,
            templateStatus = TemplateStatus.RELEASED
        )?.map { it[tTemplate.ID] to it }?.toMap()
        // 如果为空直接返回，说明全是草稿模板
        if (releaseT.isNullOrEmpty()) {
            facadeService.fillResult(
                context = dslContext,
                templates = templates,
                hasManagerPermission = hasManagerPermission,
                userId = userId,
                templateType = templateType,
                storeFlag = storeFlag,
                page = page,
                pageSize = pageSize,
                keywords = null,
                result = result,
                projectId = projectId
            )
            return TemplateListModel(projectId, hasManagerPermission, result, count)
        }
        // 对比两次版本的
        val resultTemplates = mutableMapOf<Int, String>()
        templates.forEachIndexed { index, t ->
            val templateId = t[tTemplate.ID]
            // 如果存在最新版是未发布的版本且同时存在发布的版本则使用发布的版本做展示
            if (t[tTemplate.STATUS] == TemplateStatus.COMMITTING.name && releaseT.contains(templateId)) {
                resultTemplates[index] = templateId
                return@forEachIndexed
            }
        }
        resultTemplates.forEach { (index, templateId) ->
            templates[index] = releaseT[templateId]
        }
        facadeService.fillResult(
            context = dslContext,
            templates = templates,
            hasManagerPermission = hasManagerPermission,
            userId = userId,
            templateType = templateType,
            storeFlag = storeFlag,
            page = page,
            pageSize = pageSize,
            keywords = null,
            result = result,
            projectId = projectId
        )
        return TemplateListModel(projectId, hasManagerPermission, result, count)
    }

    // 同步代码库过来的模板
    fun syncGitTemplate(

    ) {
        // 根据代码库信息取模板内容
        // 根据内容进行模板替换为完整模板

        // 将完整模板转换为 json
        // 将模板保存入库
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TemplatePACService::class.java)
    }
}
