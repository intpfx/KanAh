package com.live.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.live.tv.LivePlaybackRecovery.State.*;

public class LivePlaybackRecoveryTest {
    @Test public void startupHasItsOwnDeadline() {
        LivePlaybackRecovery r = new LivePlaybackRecovery(); r.beginAttempt(100);
        assertEquals(STARTING, r.state(40_099));
        assertEquals(TIMED_OUT, r.state(40_100));
    }
    @Test public void lateBufferingNeverReusesStartupDeadline() {
        LivePlaybackRecovery r = new LivePlaybackRecovery(); r.beginAttempt(0); r.progress(100_000);
        assertEquals(BUFFERING, r.state(110_000));
        r.progress(115_000);
        assertEquals(PLAYING, r.state(115_000));
        assertEquals(BUFFERING, r.state(144_999));
        assertEquals(TIMED_OUT, r.state(145_000));
    }
    @Test public void rebuildingAndBriefProgressDoNotResetRetryBudget() {
        LivePlaybackRecovery r = new LivePlaybackRecovery(); r.beginAttempt(0);
        assertEquals(2_000, r.nextRetryDelay());
        r.beginAttempt(42_000); r.progress(43_000);
        assertEquals(4_000, r.nextRetryDelay());
        r.beginAttempt(80_000); r.progress(81_000);
        assertEquals(-1, r.nextRetryDelay());
        r.reset(); r.beginAttempt(90_000);
        assertEquals(2_000, r.nextRetryDelay());
    }
    @Test public void onlySustainedProgressReplenishesRetries() {
        LivePlaybackRecovery r = new LivePlaybackRecovery(); r.beginAttempt(0);
        r.nextRetryDelay(); r.nextRetryDelay(); r.progress(1_000);
        r.progress(20_000); // Long gap starts a new stability interval.
        assertEquals(-1, r.nextRetryDelay());
        for (int i=1; i<=60; i++) r.progress(20_000+i*1_000L);
        assertEquals(2_000, r.nextRetryDelay());
    }
}
