/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
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
package com.tencent.devops.dispatch.utils.redis

import com.tencent.devops.common.api.enums.AgentStatus
import com.tencent.devops.common.api.pojo.AgentResult
import com.tencent.devops.common.api.pojo.agent.AgentPropsInfo
import com.tencent.devops.common.api.pojo.agent.UpgradeItem
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.dispatch.pojo.thirdpartyagent.BuildJobType
import com.tencent.devops.environment.pojo.thirdpartyagent.ThirdPartyAgentUpgradeByVersionInfo
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * 每台 agent 的构建空队列和无需升级结果共用一个 Redis Hash。只有两项都启用时才需要 HMGET，
 * 单项用 HGET，两项都不需要时跳过读取。字段各自保存截止时间，整个 Hash 的 TTL 负责回收离线 agent 的 key。
 */
@Component
class AgentAskCache(
    // Lua 的 ARGV 使用字符串编码；HGET、HMGET、HDEL 必须以相同字节编码字段和值。
    @param:Qualifier("redisStringHashOperation") private val redis: RedisOperation,
    @Value("\${dispatch.agent.emptyQueueCacheSeconds:20}") private val buildTtlSeconds: Long,
    @Value("\${dispatch.agent.noUpgradeCacheSeconds:20}") private val upgradeTtlSeconds: Long
) {
    data class Hits(val buildEmpty: Boolean = false, val noUpgrade: Boolean = false)

    fun read(agentId: String, buildType: BuildJobType, upgradeMarker: String? = null): Hits {
        val build = buildType != BuildJobType.NONE && buildTtlSeconds > 0
        val upgrade = upgradeMarker != null && upgradeTtlSeconds > 0
        if (!build && !upgrade) return Hits()
        val key = key(agentId)
        val markers = try {
            when {
                build && upgrade -> {
                    // Redis 对缺失字段按原顺序返回 nil，只使缺失的那一项回源。
                    val values = redis.hmGet(key, fields)
                    values.getOrNull(0) to values.getOrNull(1)
                }
                build -> redis.hget(key, BUILD) to null
                else -> null to redis.hget(key, UPGRADE)
            }
        } catch (e: Exception) {
            logger.debug("Unable to read ask cache for agent {}", agentId, e)
            return Hits()
        }
        val now = System.currentTimeMillis()
        val buildMarker = activeMarker(markers.first, now)
        return Hits(
            buildEmpty = build && (buildMarker == BuildJobType.ALL.name || buildMarker == buildType.name),
            noUpgrade = upgrade && activeMarker(markers.second, now) == upgradeMarker
        )
    }

    private fun activeMarker(value: String?, now: Long): String? {
        if (value == null) return null
        val separator = value.indexOf(':')
        if (separator < 0) return null // 查询中的 UUID
        val expiresAt = value.substring(0, separator).toLongOrNull() ?: return null
        return if (now < expiresAt) value.substring(separator + 1) else null
    }

    /** 普通、Docker 与 ALL 共用 build 字段。只有领取查询正常返回空时才确认该类型的空标记。 */
    fun <T : Any> queryAndRememberEmpty(agentId: String, type: BuildJobType, claim: () -> T?): T? =
        rememberIf(agentId, BUILD, buildTtlSeconds, type.name, { it == null }, claim)

    /** 入队、改派或超时回队会清除目标 agent 的构建标记，同时作废正在进行的查空确认。 */
    fun invalidateBuild(agentId: String) {
        try {
            redis.hdelete(key(agentId), BUILD)
        } catch (e: Exception) {
            logger.warn("Unable to invalidate empty build queue for agent {}", agentId, e)
        }
    }

    /** 仅对启用了升级检查的报告计算摘要。项目、密钥、上报版本、平台任一变化都会触发复查。 */
    fun upgradeMarker(
        projectId: String,
        secretKey: String,
        report: ThirdPartyAgentUpgradeByVersionInfo,
        props: AgentPropsInfo?
    ): String? {
        if (upgradeTtlSeconds <= 0) return null
        return try {
            val inputs = listOf(projectId, DigestUtils.sha256Hex(secretKey), report, props?.arch, props?.osVersion)
            // 32 字节摘要编码为 43 个 ASCII 字符，加上毫秒截止时间也能控制在 64 字节内。
            encoder.encodeToString(DigestUtils.sha256(JsonUtil.toJson(inputs, formatted = false)))
        } catch (e: Exception) {
            // 无法生成摘要时仍按原流程检查升级，也不影响同一 ask 领取构建。
            logger.debug("Unable to compute agent upgrade cache marker", e)
            null
        }
    }

    fun checkUpgradeOnMiss(
        agentId: String,
        marker: String?,
        load: () -> AgentResult<UpgradeItem>
    ): AgentResult<UpgradeItem> {
        if (marker == null) return load()
        return rememberIf(agentId, UPGRADE, upgradeTtlSeconds, marker, { result ->
            !result.isNotOk() && result.agentStatus == AgentStatus.IMPORT_OK && result.data == NO_UPGRADE.data
        }, load)
    }

    private fun <T> rememberIf(
        agentId: String,
        field: String,
        ttlSeconds: Long,
        marker: String,
        cacheIf: (T) -> Boolean,
        load: () -> T
    ): T {
        if (ttlSeconds <= 0) return load()
        val key = key(agentId)
        val probe = UUID.randomUUID().toString()
        val ttl = TimeUnit.SECONDS.toMillis(ttlSeconds)
        val start = System.currentTimeMillis()
        val deadline = start + ttl.coerceAtMost(Long.MAX_VALUE - start)
        try {
            redis.execute(register, listOf(key), field, probe, ttl.toString())
        } catch (e: Exception) {
            logger.debug("Unable to register ask cache probe for {}", field, e)
            return load()
        }
        val result = load()
        // 有效期从查询开始算。慢查询即使在另一个字段延长了整个 key 的 TTL 后才返回，也不会重新生效。
        if (cacheIf(result) && System.currentTimeMillis() < deadline) {
            try {
                redis.execute(confirm, listOf(key), field, probe, "$deadline:$marker")
            } catch (e: Exception) {
                logger.debug("Unable to confirm ask cache marker for {}", field, e)
            }
        }
        return result
    }

    private fun key(agentId: String) = redis.getKeyByRedisName("dispatch:agent:askCache:$agentId")

    companion object {
        val NO_UPGRADE = AgentResult(AgentStatus.IMPORT_OK, UpgradeItem(false, false, false, false))
        private const val BUILD = "build"
        private const val UPGRADE = "upgrade"
        private val fields = listOf(BUILD, UPGRADE)
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val logger = LoggerFactory.getLogger(AgentAskCache::class.java)

        // 只在写入时延长物理 TTL，避免短 TTL 字段提前淘汰另一个字段。热读不会续期。
        private val register = DefaultRedisScript(
            """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[3]) then
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            return 1
            """.trimIndent(), Long::class.javaObjectType
        )

        // 只比较本字段的 UUID。入队的 HDEL 或同字段的下一次查询都能阻止旧结果晚到回写。
        private val confirm = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """.trimIndent(), Long::class.javaObjectType
        )
    }
}
