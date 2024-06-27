package com.tencent.devops.environment.service.thirdpartyagent

import com.tencent.devops.common.api.enums.AgentAction
import com.tencent.devops.common.api.pojo.OS
import com.tencent.devops.common.api.util.SecurityUtil
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.environment.dao.thirdpartyagent.ThirdPartyAgentActionDao
import com.tencent.devops.environment.dao.thirdpartyagent.ThirdPartyAgentDao
import com.tencent.devops.environment.service.slave.SlaveGatewayService
import com.tencent.devops.environment.utils.ThirdAgentActionAddLock
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 对第三方构建机一些自身数据操作
 */
@Service
class ThirdPartAgentService @Autowired constructor(
    private val redisOperation: RedisOperation,
    private val dslContext: DSLContext,
    private val agentActionDao: ThirdPartyAgentActionDao,
    private val agentDao: ThirdPartyAgentDao,
    private val slaveGatewayService: SlaveGatewayService
) {
    fun addAgentAction(
        projectId: String,
        agentId: Long,
        action: AgentAction
    ) {
        val lock = ThirdAgentActionAddLock(redisOperation, projectId, agentId)
        if (agentActionDao.getAgentLastAction(dslContext, projectId, agentId) == action.name) {
            return
        }
        try {
            lock.lock()
            if (agentActionDao.getAgentLastAction(dslContext, projectId, agentId) == action.name) {
                return
            }
            agentActionDao.addAgentAction(
                dslContext = dslContext,
                projectId = projectId,
                agentId = agentId,
                action = action.name
            )
        } finally {
            lock.unlock()
        }
    }

    fun genLocalAgent(projectId: String, userId: String): Long {
        // 本地只能安装一个
        val exists = agentDao.listImportAgent(dslContext, projectId, OS.LINUX)
            .filter { SecurityUtil.encrypt(it.secretKey) == "local" }
        if (exists.isEmpty()) {
            return exists.first().id
        }
        val gateway = slaveGatewayService.getGateway(null)
        val fileGateway = slaveGatewayService.getFileGateway(null)
        return agentDao.add(
            dslContext = dslContext,
            userId = userId,
            projectId = projectId,
            os = OS.LINUX,
            secretKey = SecurityUtil.encrypt("local"),
            gateway = gateway,
            fileGateway = fileGateway
        )
    }
}