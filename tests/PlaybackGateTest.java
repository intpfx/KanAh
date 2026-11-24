import com.live.tv.PlaybackGate;

/** Standalone behavior checks: javac PlaybackGate.java PlaybackGateTest.java. */
public final class PlaybackGateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static boolean sample(PlaybackGate gate, long token, int instance, double time,
                                  boolean frameApi, int frames) {
        return gate.sample(token, instance, true, 4, false, 0, time, frameApi, frames);
    }
    public static void main(String[] args) {
        PlaybackGate gate = new PlaybackGate();
        gate.setActive(true);
        long first = gate.begin();
        check(!sample(gate, first, 1, 5, true, 1), "A single sample cannot establish progress");
        check(sample(gate, first, 1, 5.3, true, 2), "Moving decoded video can reveal");

        long second = gate.begin();
        check(!sample(gate, first, 1, 100, true, 3), "Old navigation callbacks must be rejected");
        check(!sample(gate, second, 1, 6, true, 1), "Old token must not seed current progress");
        check(sample(gate, second, 1, 6.3, true, 2), "Current token can establish its own progress");

        gate.setActive(false);
        check(!gate.accepts(second), "Background lifecycle rejects callbacks");
        check(!sample(gate, second, 1, 7, true, 3), "Background video must not reveal");
        long resumed = gate.begin(); // Activity invalidates its generation on pause.
        gate.setActive(true);
        check(!sample(gate, second, 1, 8, true, 3), "Pre-pause callback is stale after resume");
        check(!sample(gate, resumed, 1, 9, true, 1), "Resume requires fresh progress");
        check(sample(gate, resumed, 1, 9.3, true, 2), "Foreground playback may reveal again");

        check(!sample(gate, resumed, 2, 100, true, 1), "Replacement video cannot inherit prior progress");
        check(sample(gate, resumed, 2, 100.3, true, 2), "Replacement needs its own progressing samples");
        check(!sample(gate, resumed, 2, 100.3, true, 3), "A frozen video must fail the gate");

        long noFrames = gate.begin();
        check(!sample(gate, noFrames, 1, 0, true, 0), "No presented frames at initial sample");
        check(!sample(gate, noFrames, 1, 1, true, 0), "Clock progress without frame callback is insufficient");
        check(sample(gate, noFrames, 1, 2, true, 1), "A presented frame and clock progress permit reveal");
        check(!gate.sample(noFrames,1,false,4,false,0,3,true,2), "Unprepared fullscreen layout blocks reveal");
        check(!gate.sample(noFrames,1,true,1,false,0,4,true,2), "Insufficient media data blocks reveal");
        check(!gate.sample(noFrames,1,true,4,true,0,5,true,2), "Paused video blocks reveal");
        check(!gate.sample(noFrames,1,true,4,false,3,6,true,2), "Media errors block reveal");

        long fallback = gate.begin();
        check(!sample(gate,fallback,1,0,false,0), "Fallback also needs two samples");
        check(sample(gate,fallback,1,1,false,0), "Clock progress supports browsers without frame callback API");
        check(!sample(gate,fallback,1,Double.NaN,false,0), "Nonfinite clock is never ready");
        System.out.println("Playback gate navigation, lifecycle, video replacement, frame and fallback tests passed.");
    }
}
