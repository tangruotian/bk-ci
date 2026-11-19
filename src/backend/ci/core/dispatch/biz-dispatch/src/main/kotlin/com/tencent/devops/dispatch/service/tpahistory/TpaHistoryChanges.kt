package com.tencent.devops.dispatch.service.tpahistory

import java.util.concurrent.ConcurrentHashMap

/**
 * 源表 T_DISPATCH_THIRDPARTY_AGENT_BUILD 写入后记录发生变化的构建，由 [TpaHistorySyncService] 异步同步到展示表。
 * 只在内存中暂存，写入方不会被阻塞；进程重启或积压超过上限时丢失的变化，由同步服务的定时扫描补齐。
 */
object TpaHistoryChanges {
    private const val MAX_PENDING = 100_000

    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun mark(buildId: String?) {
        if (buildId.isNullOrBlank() || pending.size >= MAX_PENDING) {
            return
        }
        pending.add(buildId)
    }

    fun drain(max: Int): List<String> {
        val drained = ArrayList<String>(minOf(max, pending.size))
        val iterator = pending.iterator()
        while (iterator.hasNext() && drained.size < max) {
            drained.add(iterator.next())
            iterator.remove()
        }
        return drained
    }

    fun size() = pending.size
}
