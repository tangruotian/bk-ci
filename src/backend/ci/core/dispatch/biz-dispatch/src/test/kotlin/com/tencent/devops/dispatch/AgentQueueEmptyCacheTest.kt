package com.tencent.devops.dispatch

import com.tencent.devops.common.api.enums.AgentStatus
import com.tencent.devops.common.api.pojo.AgentResult
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.pojo.agent.AgentPropsInfo
import com.tencent.devops.common.api.pojo.agent.DockerInitFileInfo
import com.tencent.devops.common.api.pojo.agent.UpgradeItem
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.redis.RedisAutoConfiguration
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.dispatch.dao.ThirdPartyAgentBuildDao
import com.tencent.devops.dispatch.dao.ThirdPartyAgentDockerDebugDao
import com.tencent.devops.dispatch.pojo.enums.PipelineTaskStatus
import com.tencent.devops.dispatch.pojo.thirdpartyagent.AskEnable
import com.tencent.devops.dispatch.pojo.thirdpartyagent.BuildJobType
import com.tencent.devops.dispatch.pojo.thirdpartyagent.ThirdPartyAskInfo
import com.tencent.devops.dispatch.service.ThirdPartyAgentDockerService
import com.tencent.devops.dispatch.service.ThirdPartyAgentService
import com.tencent.devops.dispatch.utils.redis.AgentAskCache
import com.tencent.devops.environment.api.ServiceNodeResource
import com.tencent.devops.environment.api.thirdpartyagent.ServiceThirdPartyAgentResource
import com.tencent.devops.environment.pojo.thirdpartyagent.AskHeartbeatResponse
import com.tencent.devops.environment.pojo.thirdpartyagent.ThirdPartyAgent
import com.tencent.devops.environment.pojo.thirdpartyagent.ThirdPartyAgentUpgradeByVersionInfo
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentBuildRecord
import com.tencent.devops.model.dispatch.tables.records.TDispatchThirdpartyAgentDockerDebugRecord
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.NotFoundException
import org.jooq.JSON
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor

class AgentQueueEmptyCacheTest {
    private val redis = mockk<RedisOperation>()
    private val cache = AgentAskCache(redis, 20, 20)
    private val keys = mutableMapOf<String, String>()
    private val hashes = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val agentKey = "dispatch:agent:askCache:a"

    @BeforeEach
    fun prepare() {
        every { redis.getKeyByRedisName(any()) } answers { firstArg() }
        every { redis.get(any()) } answers { keys[firstArg()] }
        every { redis.set(any(), any(), 20L) } answers { keys[firstArg()] = secondArg() }
        every { redis.delete(any<String>()) } answers { keys.remove(firstArg()) != null }
        every { redis.hget(any(), any()) } answers { hashes[firstArg<String>()]?.get(secondArg<String>()) }
        every { redis.hdelete(any(), any<String>()) } answers {
            hashes[firstArg<String>()]?.remove(secondArg<String>())
            Unit
        }
        every { redis.hmGet(any(), any()) } answers {
            val hash = hashes[firstArg<String>()]
            // Spring 返回的列表会为缺失字段保留 null，RedisOperation 的返回类型没有标注元素可空。
            @Suppress("UNCHECKED_CAST")
            (secondArg<Collection<String>>().map { hash?.get(it) } as List<String>)
        }
        // 模拟 Redis 对一次脚本的原子执行，测试可以在真实查询中插入入队或第二次探测。
        every { redis.execute<Long>(any(), any(), *anyVararg()) } answers {
            val key = secondArg<List<String>>().single()
            val args = arg<Array<Any?>>(2)
            val script = firstArg<RedisScript<Long>>().scriptAsString
            when {
                "'PTTL'" in script -> {
                    hashes.computeIfAbsent(key) { ConcurrentHashMap() }[args[0] as String] = args[1] as String
                    1L
                }
                "'HGET'" in script -> {
                    val replaced = hashes[key]?.replace(args[0] as String, args[1] as String, args[2] as String)
                    if (replaced == true) 1L else 0L
                }
                else -> if (keys.replace(key, args[0] as String, args[1] as String)) 1L else 0L
            }
        }
    }

    @Test
    fun springWiresHashFieldsAndScriptArgumentsWithIdenticalBytes() {
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("redisConnectionFactory", mockk<RedisConnectionFactory>())
            context.beanFactory.registerSingleton("meterRegistry", SimpleMeterRegistry())
            context.register(RedisAutoConfiguration::class.java, AgentAskCache::class.java)
            context.refresh()
            val operation = ReflectionTestUtils.getField(context.getBean(AgentAskCache::class.java), "redis")
            assertSame(context.getBean("redisStringHashOperation"), operation)
            val template = ReflectionTestUtils.getField(operation!!, "masterRedisTemplate") as RedisTemplate<*, *>
            // 脚本参数、Hash 字段和 Hash 值分别经不同 serializer；三者均需和 Lua 的原始字节互通。
            for (serializer in listOf(template.valueSerializer, template.hashKeySerializer, template.hashValueSerializer)) {
                @Suppress("UNCHECKED_CAST")
                val strings = serializer as RedisSerializer<String>
                for (value in listOf("build", "upgrade", "123456:ALL")) {
                    assertArrayEquals(value.toByteArray(), strings.serialize(value))
                }
            }
        }
    }

    @Test
    fun knownEmptySkipsOnlyCompatiblePolls() {
        assertFalse(cache.read("a", BuildJobType.ALL).buildEmpty)
        assertNull(cache.queryAndRememberEmpty<String>("a", BuildJobType.BINARY) { null })
        assertTrue(cache.read("a", BuildJobType.BINARY).buildEmpty)
        assertFalse(cache.read("a", BuildJobType.DOCKER).buildEmpty)
        assertFalse(cache.read("a", BuildJobType.ALL).buildEmpty)
        assertEquals("docker", cache.queryAndRememberEmpty("a", BuildJobType.DOCKER) { "docker" })
        assertNull(cache.queryAndRememberEmpty<String>("a", BuildJobType.ALL) { null })
        assertTrue(cache.read("a", BuildJobType.DOCKER).buildEmpty)
        assertFalse(cache.read("b", BuildJobType.ALL).buildEmpty)
        cache.invalidateBuild("a")
        assertFalse(cache.read("a", BuildJobType.ALL).buildEmpty)
    }

    @Test
    fun staleEmptyQueryCannotOverwriteEnqueueOrAnotherProbe() {
        // 外层 ALL 查询最后才返回空。期间任务入队，另一次 Docker 轮询已经领到它并换掉了 UUID。
        // 如果外层还能写回 ALL，接下来的轮询就会错误地跳过所有任务类型的查询。
        cache.queryAndRememberEmpty<String>("a", BuildJobType.ALL) {
            cache.invalidateBuild("a")
            assertEquals("job", cache.queryAndRememberEmpty("a", BuildJobType.DOCKER) { "job" })
            null
        }
        assertFalse(cache.read("a", BuildJobType.ALL).buildEmpty)
    }

    @Test
    fun fieldsExpireIndependentlyAndEnqueuePreservesTheUpgradeProbe() {
        val marker = "version"
        cache.queryAndRememberEmpty<String>("a", BuildJobType.ALL) { null }
        cache.checkUpgradeOnMiss("a", marker) {
            // 构建入队可以发生在升级 RPC 返回之前，只应清掉 build。
            cache.invalidateBuild("a")
            AgentAskCache.NO_UPGRADE
        }
        assertEquals(AgentAskCache.Hits(noUpgrade = true), cache.read("a", BuildJobType.ALL, marker))
        val hash = hashes.getValue(agentKey)
        val originalUpgrade = hash.getValue("upgrade")
        cache.queryAndRememberEmpty<String>("a", BuildJobType.ALL) { null }
        assertEquals(originalUpgrade, hash["upgrade"])
        assertEquals(setOf(agentKey), hashes.keys)
        assertEquals(setOf("build", "upgrade"), hash.keys)
        val originalBuild = hash.getValue("build")
        hash["upgrade"] = "1:$marker"
        assertEquals(AgentAskCache.Hits(buildEmpty = true), cache.read("a", BuildJobType.ALL, marker))
        hash["upgrade"] = originalUpgrade
        hash["build"] = "1:ALL"
        assertEquals(AgentAskCache.Hits(noUpgrade = true), cache.read("a", BuildJobType.ALL, marker))
        hash["build"] = originalBuild
        assertEquals(AgentAskCache.Hits(true, true), cache.read("a", BuildJobType.ALL, marker))
    }

    @Test
    fun disabledAndUnrequestedFieldsDoNotAddRedisReads() {
        val buildOnly = AgentAskCache(redis, 20, 0)
        val upgradeOnly = AgentAskCache(redis, 0, 20)
        buildOnly.read("a", BuildJobType.ALL, "version")
        upgradeOnly.read("a", BuildJobType.ALL, "version")
        cache.read("a", BuildJobType.NONE)
        AgentAskCache(redis, 0, 0).read("a", BuildJobType.ALL, "version")
        verify(exactly = 1) { redis.hget(agentKey, "build") }
        verify(exactly = 1) { redis.hget(agentKey, "upgrade") }
        verify(exactly = 0) { redis.hmGet(any(), any()) }
    }

    @Test
    fun cacheFailureAndDisabledCacheFallBackToTheClaim() {
        every { redis.hget(any(), any()) } throws IllegalStateException("offline")
        every { redis.execute<Long>(any(), any(), *anyVararg()) } throws IllegalStateException("offline")
        assertFalse(cache.read("a", BuildJobType.ALL).buildEmpty)
        assertEquals("job", cache.queryAndRememberEmpty("a", BuildJobType.ALL) { "job" })
        val disabled = AgentAskCache(mockk(), 0, 0)
        assertFalse(disabled.read("a", BuildJobType.ALL).buildEmpty)
        assertEquals("job", disabled.queryAndRememberEmpty("a", BuildJobType.ALL) { "job" })
    }

    @Test
    fun askSkipsOnlyTheBuildLookupOnEmptyHits() {
        val client = mockk<Client>()
        val environment = mockk<ServiceThirdPartyAgentResource>()
        val dao = mockk<ThirdPartyAgentBuildDao>()
        val agent = mockk<ThirdPartyAgent>()
        val heartbeat = mockk<AskHeartbeatResponse>()
        val request = ThirdPartyAskInfo(AskEnable("ALL", false, false, false), mockk(), null)
        val service = ThirdPartyAgentService(
            mockk(), cache, mockk(), client, redis, dao, mockk(), mockk(), mockk(), mockk()
        )
        every { client.get(ServiceThirdPartyAgentResource::class) } returns environment
        every { environment.getAgentById("p", "a") } returns AgentResult(AgentStatus.IMPORT_OK, agent)
        every { agent.secretKey } returns "current"
        every { agent.status } returns AgentStatus.IMPORT_OK
        every { heartbeat.agentStatus } returns AgentStatus.IMPORT_OK.name
        every { environment.newHeartbeat("p", "a", "current", request.heartbeat) } returns Result(heartbeat)
        every { redis.setNxEx(any(), any(), any(), true) } returns true
        every { redis.execute<Long>(any(), any(), *anyVararg(), isRedisLock = true) } returns 1L
        every { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) } returns null

        try {
            // 三次 ask 只有首次需要查 agent 和队列，后两次通过心跳分别验证节点删除和密钥失效仍能反映在响应中。
            assertEquals(AgentStatus.IMPORT_OK, service.ask("p", "a", "current", request).agentStatus)
            every { heartbeat.agentStatus } returns AgentStatus.DELETE.name
            val deleted = service.ask("p", "a", "current", request)
            assertEquals(AgentStatus.DELETE, deleted.agentStatus)
            assertNull(deleted.data?.build)
            every { environment.newHeartbeat("p", "a", "revoked", request.heartbeat) } throws
                IllegalStateException("invalid credential")
            assertTrue(service.ask("p", "a", "revoked", request).isNotOk())
            verify(exactly = 3) { environment.newHeartbeat("p", "a", any(), request.heartbeat) }
            verify(exactly = 1) { environment.getAgentById("p", "a") }
            verify(exactly = 1) { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) }

            // 独立领取接口没有心跳，即使缓存命中也校验密钥。
            assertThrows(NotFoundException::class.java) { service.startBuild("p", "a", "revoked", BuildJobType.ALL) }
            every { environment.getAgentById("p", "a") } returns AgentResult(AgentStatus.DELETE, null)
            assertEquals(AgentStatus.DELETE, service.startBuild("p", "a", "current", BuildJobType.ALL).agentStatus)
            verify(exactly = 3) { environment.getAgentById("p", "a") }

            // 模拟入队清除缓存。此时应重新查 agent，发现节点已删除就结束领取，避免把节点删除误记为队列查空。
            cache.invalidateBuild("a")
            service.ask("p", "a", "current", request)
            verify(exactly = 4) { environment.getAgentById("p", "a") }
            verify(exactly = 1) { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) }
            assertFalse(cache.read("a", BuildJobType.ALL).buildEmpty)
        } finally {
            (ReflectionTestUtils.getField(service, "askExecutor") as ExecutorService).shutdownNow()
        }
    }

    @Test
    fun buildAdmissionIsExplicitAndOptionalRejectionPreservesClaimedWork() {
        val client = mockk<Client>()
        val environment = mockk<ServiceThirdPartyAgentResource>(relaxed = true)
        val dao = mockk<ThirdPartyAgentBuildDao>(relaxed = true)
        val service = ThirdPartyAgentService(
            mockk(), cache, mockk(), client, redis, dao, mockk(), mockk(), mockk(), mockk(relaxUnitFun = true)
        )
        val request = ThirdPartyAskInfo(AskEnable("ALL", false, false, true), mockk(), null)
        val agent = mockk<ThirdPartyAgent>()
        val heartbeat = mockk<AskHeartbeatResponse>()
        every { client.get(ServiceThirdPartyAgentResource::class) } returns environment
        every { environment.newHeartbeat("p", "a", "key", request.heartbeat) } returns Result(heartbeat)
        every { heartbeat.agentStatus } returns AgentStatus.IMPORT_OK.name
        every { environment.getAgentById("p", "a") } returns AgentResult(AgentStatus.IMPORT_OK, agent)
        every { agent.status } returns AgentStatus.IMPORT_OK
        every { agent.secretKey } returns "key"
        every { redis.setNxEx(any(), any(), any(), true) } returns true
        every { redis.execute<Long>(any(), any(), *anyVararg(), isRedisLock = true) } returns 1L
        every { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) } returns TDispatchThirdpartyAgentBuildRecord().apply {
            id = 7L
            agentId = "a"
            projectId = "p"
            pipelineId = "pipeline"
            buildId = "build"
            vmSeqId = "1"
            workspace = "work"
            agentIp = "127.0.0.1"
            executeCount = 1
        }

        // 可重复地模拟线程池刚好只接收心跳，随后只接收心跳和领取的两种情况。
        var permits = 1
        val executor = mockk<ThreadPoolExecutor>()
        every { executor.execute(any()) } answers {
            if (permits-- <= 0) throw RejectedExecutionException("full")
            firstArg<Runnable>().run()
        }
        ReflectionTestUtils.setField(service, "askExecutor", executor)
        val busy = service.ask("p", "a", "key", request)
        assertTrue(busy.isNotOk())
        assertEquals("ask executor is busy, retry later", busy.message)
        verify(exactly = 0) { dao.fetchOneQueueBuild(any(), any(), any()) }

        permits = 2
        val claimed = service.ask("p", "a", "key", request)
        assertEquals("build", claimed.data?.build?.buildId)
        verify(exactly = 1) { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) }
        verify(exactly = 0) { environment.getPipelines(any(), any(), any(), any()) }
    }

    @Test
    fun onlySuccessfulNoUpgradeForTheSameReportIsReused() {
        val report = ThirdPartyAgentUpgradeByVersionInfo("w", "g", listOf("17"), DockerInitFileInfo("md5", false))
        val props = AgentPropsInfo("amd64", null, null, "linux")
        val noUpgrade = AgentResult(AgentStatus.IMPORT_OK, UpgradeItem(false, false, false, false))
        fun check(
            input: ThirdPartyAgentUpgradeByVersionInfo = report,
            project: String = "p",
            key: String = "credential",
            platform: AgentPropsInfo = props,
            load: () -> AgentResult<UpgradeItem>
        ): AgentResult<UpgradeItem> {
            val marker = cache.upgradeMarker(project, key, input, platform)
            if (cache.read("a", BuildJobType.NONE, marker).noUpgrade) return AgentAskCache.NO_UPGRADE
            return cache.checkUpgradeOnMiss("a", marker, load)
        }

        assertEquals(noUpgrade, check { noUpgrade })
        assertEquals(noUpgrade, check { error("同一份报告应命中") })
        verify(exactly = 2) { redis.hget(agentKey, "upgrade") }
        verify(exactly = 2) { redis.execute<Long>(any(), any(), *anyVararg()) }
        // 模拟 Redis 过期后服务端发布了新策略，同一份报告也必须重新检查。
        hashes.clear()
        val workerUpgrade = AgentResult(AgentStatus.IMPORT_OK, UpgradeItem(false, true, false, false))
        assertEquals(workerUpgrade, check { workerUpgrade })
        check { noUpgrade }
        val changed = report.copy(dockerInitFileInfo = DockerInitFileInfo("md5", true))
        val dockerOnly = AgentResult(AgentStatus.IMPORT_OK, UpgradeItem(false, false, false, true))
        assertEquals(dockerOnly, check(changed) { dockerOnly })
        val failed = AgentResult(AgentStatus.IMPORT_EXCEPTION, noUpgrade.data!!)
        assertEquals(failed, check(changed) { failed })
        val deleted = AgentResult(AgentStatus.DELETE, noUpgrade.data!!)
        assertEquals(deleted, check(changed) { deleted })
        val rpcError = AgentResult<UpgradeItem>(1, "unavailable")
        assertEquals(rpcError, check(changed) { rpcError })
        assertEquals(noUpgrade, check(changed) { noUpgrade })
        // 如果身份、JDK 或架构变化时错误命中，将直接返回 noUpgrade，以下断言会失败。
        assertEquals(dockerOnly, check(changed, project = "other") { dockerOnly })
        check(changed) { noUpgrade }
        assertEquals(dockerOnly, check(changed, key = "rotated") { dockerOnly })
        check(changed) { noUpgrade }
        assertEquals(dockerOnly, check(changed.copy(jdkVersion = null)) { dockerOnly })
        check(changed) { noUpgrade }
        assertEquals(dockerOnly, check(changed, platform = props.copy(arch = "arm64")) { dockerOnly })
    }

    @Test
    fun idleAskReadsBothFieldsOnceAndOnlyCallsTheBranchesThatMiss() {
        val client = mockk<Client>()
        val environment = mockk<ServiceThirdPartyAgentResource>()
        val heartbeat = mockk<AskHeartbeatResponse>()
        val report = ThirdPartyAgentUpgradeByVersionInfo("w", "g", null, null)
        val request = ThirdPartyAskInfo(AskEnable("ALL", true, false, false), mockk(), report)
        val dao = mockk<ThirdPartyAgentBuildDao>()
        val agent = mockk<ThirdPartyAgent>()
        val service = ThirdPartyAgentService(
            mockk(), cache, mockk(), client, redis, dao, mockk(), mockk(), mockk(), mockk()
        )
        val noUpgrade = UpgradeItem(false, false, false, false)
        val dockerOnly = UpgradeItem(false, false, false, true)
        every { client.get(ServiceThirdPartyAgentResource::class) } returns environment
        every { environment.getAgentById("p", "a") } returns AgentResult(AgentStatus.IMPORT_OK, agent)
        every { agent.status } returns AgentStatus.IMPORT_OK
        every { agent.secretKey } returns "key"
        every { redis.setNxEx(any(), any(), any(), true) } returns true
        every { redis.execute<Long>(any(), any(), *anyVararg(), isRedisLock = true) } returns 1L
        every { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) } returns null
        every { request.heartbeat.props } returns null
        every { heartbeat.agentStatus } returns AgentStatus.IMPORT_OK.name
        every { environment.newHeartbeat("p", "a", "key", request.heartbeat) } returns Result(heartbeat)
        every { environment.upgradeByVersionNew("p", "a", "key", any()) } returnsMany listOf(
            AgentResult(AgentStatus.IMPORT_OK, noUpgrade), AgentResult(AgentStatus.IMPORT_OK, dockerOnly)
        )
        try {
            repeat(2) { assertEquals(noUpgrade, service.ask("p", "a", "key", request).data?.upgrade) }
            verify(exactly = 1) { environment.upgradeByVersionNew("p", "a", "key", report) }
            val changed = request.copy(upgrade = report.copy(workerVersion = "new"))
            assertEquals(dockerOnly, service.ask("p", "a", "key", changed).data?.upgrade)
            verify(exactly = 3) { redis.hmGet(agentKey, listOf("build", "upgrade")) }
            verify(exactly = 0) { redis.hget(any(), any()) }
            verify(exactly = 3) { environment.newHeartbeat("p", "a", "key", request.heartbeat) }
            verify(exactly = 1) { environment.getAgentById("p", "a") }
            verify(exactly = 1) { dao.fetchOneQueueBuild(any(), "a", BuildJobType.ALL) }
            verify(exactly = 2) { environment.upgradeByVersionNew("p", "a", "key", any()) }
        } finally {
            (ReflectionTestUtils.getField(service, "askExecutor") as ExecutorService).shutdownNow()
        }
    }

    @Test
    fun dockerDebugEnqueueInvalidatesAnEmptyPollAndOnlyRealEmptyRowsAreCached() {
        val client = mockk<Client>()
        val environment = mockk<ServiceThirdPartyAgentResource>()
        val node = mockk<ServiceNodeResource>()
        val dao = mockk<ThirdPartyAgentDockerDebugDao>(relaxUnitFun = true)
        val builds = mockk<ThirdPartyAgentBuildDao>()
        val service = ThirdPartyAgentDockerService(client, mockk(), redis, dao, builds, 20)
        val agent = mockk<ThirdPartyAgent>()
        every { client.get(ServiceThirdPartyAgentResource::class) } returns environment
        every { client.get(ServiceNodeResource::class) } returns node
        every { environment.getAgentById("p", "a") } returns AgentResult(AgentStatus.IMPORT_OK, agent)
        every { agent.status } returns AgentStatus.IMPORT_OK
        every { agent.secretKey } returns "current"
        every { redis.setNxEx(any(), any(), any(), true) } returns true
        every { redis.execute<Long>(any(), any(), *anyVararg(), isRedisLock = true) } returns 1L
        every { dao.fetchOneQueueBuild(any(), "a") } returns null

        repeat(2) { assertNull(service.startDockerDebug("p", "a", "current", fromAsk = true).data) }
        verify(exactly = 1) { environment.getAgentById("p", "a") }
        verify(exactly = 1) { dao.fetchOneQueueBuild(any(), "a") }
        assertThrows(NotFoundException::class.java) { service.startDockerDebug("p", "a", "revoked") }

        val history = TDispatchThirdpartyAgentBuildRecord().apply {
            agentId = "a"
            buildId = "build"
            workspace = "work"
            nodeId = 1L
            dockerInfo = JSON.json("{}")
        }
        every { builds.getDockerBuild(any(), "build", "1") } returns history
        every { node.checkNodePermission(any(), any(), any(), any()) } returns Result(true)
        every { dao.getDebug(any(), "build", "1", "u", true) } returns null
        every { dao.add(any(), "p", "a", "pipeline", "build", "1", "u", "work", any()) } returns 7L
        every { dao.getDoneDebugById(any(), 7L) } returns TDispatchThirdpartyAgentDockerDebugRecord().apply {
            status = PipelineTaskStatus.DONE.status
            debugUrl = "ready"
        }
        assertEquals("ready", service.createThirdDockerDebugUrl("u", "p", "pipeline", "build", "1"))
        every { dao.fetchOneQueueBuild(any(), "a") } returns TDispatchThirdpartyAgentDockerDebugRecord().apply { id = 7L }
        repeat(2) { assertNull(service.startDockerDebug("p", "a", "current", fromAsk = true).data) }
        verify(exactly = 3) { dao.fetchOneQueueBuild(any(), "a") }
    }
}
