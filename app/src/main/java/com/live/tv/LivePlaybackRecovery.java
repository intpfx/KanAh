package com.live.tv;

/** Playback health is independent of whether the loading curtain is visible. */
final class LivePlaybackRecovery {
    enum State { STARTING, PLAYING, BUFFERING, TIMED_OUT }
    private long attemptAt, lastProgress, stableSince;
    private boolean played;
    private int retries;

    void reset() { retries = 0; }

    /** A new document gets its own startup clock, but keeps the session's retry budget. */
    void beginAttempt(long now) {
        attemptAt = lastProgress = now;
        stableSince = -1;
        played = false;
    }

    void progress(long now) {
        if (!played || now - lastProgress >= 8_000) stableSince = now;
        played = true;
        lastProgress = now;
        // Briefly showing a frame must not create an endless restart loop.
        if (now - stableSince >= 60_000) retries = 0;
    }

    State state(long now) {
        if (!played) return now - attemptAt >= 40_000 ? State.TIMED_OUT : State.STARTING;
        long stalled = now - lastProgress;
        if (stalled >= 30_000) return State.TIMED_OUT;
        return stalled >= 8_000 ? State.BUFFERING : State.PLAYING;
    }

    /** Two automatic retries; manual retry/channel selection begins a fresh budget. */
    long nextRetryDelay() { return retries < 2 ? 2_000L << retries++ : -1; }
}
