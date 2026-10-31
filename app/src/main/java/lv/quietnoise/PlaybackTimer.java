package lv.quietnoise;

import java.util.Locale;
import java.util.function.LongSupplier;

/** Elapsed playback time; its monotonic clock includes time with the screen off. */
final class PlaybackTimer {
    private final LongSupplier clock;
    private long elapsed;
    private long startedAt;
    private boolean running;

    PlaybackTimer(LongSupplier clock, long savedElapsed) {
        this.clock = clock;
        elapsed = Math.max(0, savedElapsed);
    }
    void start() {
        elapsed = 0;
        startedAt = clock.getAsLong();
        running = true;
    }
    void resume() {
        if (!running) {
            startedAt = clock.getAsLong() - elapsed;
            running = true;
        }
    }
    void stop() {
        elapsed = elapsedMillis();
        running = false;
    }
    void reset() {
        elapsed = 0;
        if (running) startedAt = clock.getAsLong();
    }
    boolean isRunning() { return running; }
    long elapsedMillis() {
        return running ? Math.max(0, clock.getAsLong() - startedAt) : elapsed;
    }
    static String format(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        return String.format(Locale.getDefault(), "%d h %02d min", minutes / 60, minutes % 60);
    }
}
