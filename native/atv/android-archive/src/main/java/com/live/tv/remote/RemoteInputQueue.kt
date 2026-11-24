package com.live.tv.remote

import java.util.ArrayDeque

/** Never replay a backlog of navigation/power keys after ADB reconnects. */
internal class RemoteInputQueue(
    private val capacity: Int = 24,
    private val maxAgeMillis: Long = 600,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry(val key: RemoteKey, val createdAt: Long)
    private val entries = ArrayDeque<Entry>()

    @Synchronized fun offer(key: RemoteKey): Boolean {
        dropExpired()
        if (entries.size >= capacity) return false
        entries.addLast(Entry(key, now()))
        return true
    }

    @Synchronized fun poll(): RemoteKey? {
        dropExpired()
        return entries.pollFirst()?.key
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int { dropExpired(); return entries.size }

    private fun dropExpired() {
        val time = now()
        while (entries.peekFirst()?.let { time - it.createdAt > maxAgeMillis } == true) entries.removeFirst()
    }
}
