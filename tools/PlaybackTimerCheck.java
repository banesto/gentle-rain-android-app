package lv.quietnoise;

/** Run with the project's PlaybackTimer.java; no Android device required. */
public final class PlaybackTimerCheck {
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long[] now = {100_000};
        PlaybackTimer timer = new PlaybackTimer(() -> now[0], 65_000);
        expect(timer.elapsedMillis() == 65_000 && !timer.isRunning(), "Restore stopped session");
        timer.start();
        now[0] += 59_999;
        expect(PlaybackTimer.format(timer.elapsedMillis()).equals("0 h 00 min"), "No seconds or early rounding");
        now[0] += 1;
        expect(PlaybackTimer.format(timer.elapsedMillis()).equals("0 h 01 min"), "Minute boundary");
        now[0] += 3_600_000;
        expect(PlaybackTimer.format(timer.elapsedMillis()).equals("1 h 01 min"), "Includes time while UI absent");
        timer.stop();
        long stopped = timer.elapsedMillis();
        now[0] += 5_000;
        timer.stop();
        expect(timer.elapsedMillis() == stopped, "Stop stays frozen through fade and repeated cleanup");
        PlaybackTimer restored = new PlaybackTimer(() -> now[0], stopped);
        expect(restored.elapsedMillis() == stopped, "Saved result survives timer recreation");
        timer.resume();
        now[0] += 10_000;
        expect(timer.elapsedMillis() == stopped + 10_000, "Resume retains elapsed time and excludes pause");
        timer.resume();
        now[0] += 1_000;
        expect(timer.elapsedMillis() == stopped + 11_000, "Repeated resume does not reset time");
        timer.stop();
        timer.reset();
        expect(timer.elapsedMillis() == 0 && !timer.isRunning(), "Reset while stopped");
        timer.start();
        now[0] += 80_000;
        timer.reset();
        now[0] += 61_000;
        expect(timer.elapsedMillis() == 61_000 && timer.isRunning(), "Reset during playback keeps counting");
        timer.start();
        expect(timer.elapsedMillis() == 0, "New play resets session");
        expect(PlaybackTimer.format(90_000_000).equals("25 h 00 min"), "Hours do not wrap at 24");
        System.out.println("PASS: timer start, stop, persistence, resets, minute/hour boundaries, and background elapsed time");
    }
}
