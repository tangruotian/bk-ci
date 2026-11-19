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
package com.tencent.devops.common.redis

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.script.DefaultRedisScript

/**
 * 短暂记住一次成功查询得出的空结果。不同队列用各自的 [prefix]，Redis 中只保存标记或正在查询的 UUID。
 * [ttlSeconds] 小于等于零时直接回源。写入任务的副本仍应调用 [invalidate]，使其他副本已有的标记失效。
 */
class RedisNegativeCache(
    private val redis: RedisOperation,
    private val prefix: String,
    private val ttlSeconds: Long
) {
    val enabled: Boolean get() = ttlSeconds > 0

    fun readMarker(id: String): String? {
        if (!enabled) return null
        return try {
            redis.get(key(id))
        } catch (e: Exception) {
            logger.debug("Unable to read negative cache {}", prefix, e)
            null
        }
    }

    fun isEmpty(id: String) = readMarker(id) == EMPTY

    fun <T : Any> query(id: String, marker: String = EMPTY, load: () -> T?): T? =
        rememberIf(id, marker, { it == null }, load)

    /** 先登记本次查询，只有回源成功并满足 [cacheIf] 才确认标记。回源异常直接交给调用方。 */
    fun <T> rememberIf(id: String, marker: String, cacheIf: (T) -> Boolean, load: () -> T): T {
        if (!enabled) return load()
        val key = key(id)
        val probe = UUID.randomUUID().toString()
        try {
            redis.set(key, probe, ttlSeconds)
        } catch (e: Exception) {
            logger.debug("Unable to register negative cache query {}", prefix, e)
            return load()
        }
        val result = load()
        if (cacheIf(result)) {
            try {
                redis.execute(confirm, listOf(key), probe, marker, ttlSeconds.toString())
            } catch (e: Exception) {
                logger.debug("Unable to confirm negative cache {}", prefix, e)
            }
        }
        return result
    }

    fun invalidate(id: String) {
        try {
            redis.delete(key(id))
        } catch (e: Exception) {
            logger.warn("Unable to invalidate negative cache {} for {}", prefix, id, e)
        }
    }

    private fun key(id: String) = redis.getKeyByRedisName("$prefix:$id")

    companion object {
        private const val EMPTY = "EMPTY"
        private val logger = LoggerFactory.getLogger(RedisNegativeCache::class.java)

        // 查询 A 查空后，任务可能已入队并删除 key，或查询 B 已写入自己的 UUID。
        // 原子比对和写回保证只有仍持有该 UUID 的查询可以确认空结果。
        private val confirm = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """.trimIndent(), Long::class.javaObjectType
        )
    }
}
