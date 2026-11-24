package com.live.tv.media

import okhttp3.Call
import java.io.IOException

/** One cancellable user operation, including any detail and media probe requests. */
class VodRequest private constructor(private val parent: VodRequest?, private val deadlineNanos: Long) {
    constructor() : this(null, Long.MAX_VALUE)
    private val calls = mutableSetOf<Call>()
    @Volatile private var cancelled = false

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
    }

    fun isCancelled(): Boolean = cancelled || parent?.isCancelled() == true

    internal fun bounded(milliseconds: Long): VodRequest = VodRequest(this,
        minOf(deadlineNanos, System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds)))

    internal fun expired(): Boolean = System.nanoTime() >= deadlineNanos

    internal fun check() {
        parent?.check()
        if (cancelled || Thread.currentThread().isInterrupted) throw VodException("CANCELLED", "请求已取消")
        if (expired()) throw VodException("SEARCH_TIMEOUT", "此来源搜索超时，请重试")
    }

    @Synchronized internal fun attach(call: Call) {
        check()
        if (deadlineNanos != Long.MAX_VALUE) {
            val timeout = call.timeout()
            if (!timeout.hasDeadline() || timeout.deadlineNanoTime() > deadlineNanos)
                timeout.deadlineNanoTime(deadlineNanos)
        }
        parent?.attach(call)
        calls.add(call)
    }

    @Synchronized internal fun detach(call: Call) { calls.remove(call); parent?.detach(call) }
}

/** Deliberately excludes remote bodies, URLs and exception causes from public errors. */
class VodException(val code: String, message: String) : IOException(message)
