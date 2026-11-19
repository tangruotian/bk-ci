package com.tencent.devops.environment.service.thirdpartyagent

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.SecurityUtil
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.environment.dao.thirdpartyagent.ThirdPartyAgentDao
import com.tencent.devops.environment.dao.thirdpartyagent.ThirdPartyAgentPipelineDao
import com.tencent.devops.environment.pojo.thirdpartyagent.pipeline.CommandPipelineCreate
import com.tencent.devops.environment.pojo.thirdpartyagent.pipeline.PipelineResponse
import com.tencent.devops.environment.pojo.thirdpartyagent.pipeline.PipelineStatus
import com.tencent.devops.model.environment.tables.records.TEnvironmentAgentPipelineRecord
import com.tencent.devops.model.environment.tables.records.TEnvironmentThirdpartyAgentRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentPipelineEmptyCacheTest {
    @Test
    fun emptyPollsSkipDatabaseAndNewOrRequeuedCommandsAreVisible() {
        val redis = mockk<RedisOperation>()
        val keys = mutableMapOf<String, String>()
        every { redis.getKeyByRedisName(any()) } answers { firstArg() }
        every { redis.get(any()) } answers { keys[firstArg()] }
        every { redis.set(any(), any(), 20L) } answers { keys[firstArg()] = secondArg() }
        every { redis.delete(any<String>()) } answers { keys.remove(firstArg()) != null }
        every { redis.execute<Long>(any(), any(), *anyVararg()) } answers {
            val args = arg<Array<Any?>>(2)
            if (keys.replace(secondArg<List<String>>().single(), args[0] as String, args[1] as String)) 1L else 0L
        }

        val agentDao = mockk<ThirdPartyAgentDao>()
        val pipelineDao = mockk<ThirdPartyAgentPipelineDao>()
        val mapper = JsonUtil.getObjectMapper()
        val service = ThirdPartyAgentPipelineService(mockk(), agentDao, pipelineDao, mapper, redis, 20)
        val agentHash = HashUtil.encodeLongId(11L)
        val nodeHash = HashUtil.encodeLongId(22L)
        val taskHash = HashUtil.encodeLongId(33L)
        val command = CommandPipelineCreate("test")
        val task = TEnvironmentAgentPipelineRecord().apply {
            id = 33L
            pipeline = mapper.writeValueAsString(command)
        }
        val agent = TEnvironmentThirdpartyAgentRecord().apply {
            id = 11L
            secretKey = SecurityUtil.encrypt("key")
        }
        every { agentDao.getAgent(any(), 11L) } returns agent
        every { agentDao.getAgentByNodeId(any(), 22L, "p") } returns agent
        var pending: TEnvironmentAgentPipelineRecord? = null
        every { pipelineDao.getPipeline(any(), 11L, PipelineStatus.PENDING) } answers { pending }
        every { pipelineDao.add(any(), 11L, "p", "user", PipelineStatus.PENDING, any()) } answers {
            pending = task
            33L
        }
        every { pipelineDao.updateStatus(any(), 33L, 11L, PipelineStatus.PENDING, "") } answers {
            pending = task
            1
        }

        repeat(2) { assertNull(service.getPipelines("p", agentHash, "key", fromAsk = true)) }
        verify(exactly = 1) { agentDao.getAgent(any(), 11L) }
        verify(exactly = 1) { pipelineDao.getPipeline(any(), 11L, PipelineStatus.PENDING) }
        assertThrows(ErrorCodeException::class.java) { service.getPipelines("p", agentHash, "revoked") }
        assertEquals(taskHash, service.addPipeline("p", nodeHash, "user", command))
        assertEquals(taskHash, service.getPipelines("p", agentHash, "key", fromAsk = true)?.seqId)

        pending = null
        assertNull(service.getPipelines("p", agentHash, "key", fromAsk = true))
        val requeued = PipelineResponse(taskHash, PipelineStatus.PENDING, "")
        assertTrue(service.updatePipelineStatus("p", agentHash, "key", requeued))
        assertEquals(taskHash, service.getPipelines("p", agentHash, "key", fromAsk = true)?.seqId)
    }
}
