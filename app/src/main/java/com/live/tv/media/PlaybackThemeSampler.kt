package com.live.tv.media

import android.os.Handler

/** Wait for a usable frame, including long black intros; at most one tiny copy is in flight. */
internal class PlaybackThemeSampler(private val handler: Handler) {
    private var generation = 0
    private var inFlight = false
    private var pending: Runnable? = null

    fun stop() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    fun start(isCurrent: () -> Boolean, readFrame: ((Int?) -> Unit) -> Unit, applyColor: (Int) -> Unit) {
        stop()
        val request = generation
        val retry = object : Runnable {
            override fun run() {
                if (request != generation || !isCurrent()) return
                if (inFlight) { schedule(this); return }
                inFlight = true
                readFrame { color ->
                    inFlight = false
                    if (request == generation && isCurrent()) {
                        if (color != null) { pending = null; applyColor(color) }
                        else schedule(this)
                    }
                }
            }
        }
        pending = retry
        retry.run()
    }

    private fun schedule(retry: Runnable) {
        pending = retry
        handler.postDelayed(retry, 1000)
    }
}
