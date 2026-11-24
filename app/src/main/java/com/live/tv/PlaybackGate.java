package com.live.tv;

/** A reveal belongs to one navigation, video element, and foreground lifecycle. */
public final class PlaybackGate {
    private long generation;
    private int instance = -1;
    private double previousTime = -1;
    private boolean active;

    public long begin() {
        generation++;
        instance = -1;
        previousTime = -1;
        return generation;
    }

    public void setActive(boolean value) { active = value; }
    public boolean accepts(long token) { return active && token == generation; }
    public long token() { return generation; }

    public boolean sample(long token, int videoInstance, boolean layoutReady,
                          int readyState, boolean paused, int error, double time,
                          boolean frameCallbackAvailable, int frames) {
        if (!accepts(token)) return false;
        if (instance != videoInstance) {
            instance = videoInstance;
            previousTime = -1;
        }
        boolean progressing = Double.isFinite(time) && previousTime >= 0 && time > previousTime;
        previousTime = time;
        return layoutReady && readyState >= 3 && !paused && error == 0
                && progressing && (!frameCallbackAvailable || frames > 0);
    }
}
