package lv.quietnoise;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.media.AudioTrack;
import java.lang.reflect.Field;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/** Framework-only device smoke test; also exercises the Xiaomi path on emulator. */
public final class PlaybackSmokeTest extends Instrumentation {
    private Activity activity;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Wait for the asynchronous service connection, not arbitrary clicks.
            long deadline = SystemClock.uptimeMillis() + 10000;
            boolean[] ready = {false};
            while (!ready[0] && SystemClock.uptimeMillis() < deadline) {
                runOnMainSync(() -> { View button = find("▶  Play"); ready[0] = button != null && button.isEnabled(); });
                SystemClock.sleep(50);
            }
            check(ready[0], "Service did not bind");
            runOnMainSync(() -> ((Button) find("▶  Play")).performClick());
            SystemClock.sleep(6000);
            runOnMainSync(() -> {
                check(find("Playing") != null && find("■  Stop") != null, "Playback failed during fade-in");
                ((Button) find("■  Stop")).performClick();
            });
            SystemClock.sleep(6500);
            runOnMainSync(() -> check(find("Ready when you are") != null && find("▶  Play") != null,
                    "Fade-out did not complete and release audio"));
            stressSinglePlayback();
            result.putString("stream", "PASS: rapid screen taps, repeated notification commands, fade reversal, player identity, writer count, full stop/restart; Play, five-second sample fade, sustained playback, Stop, and drained fade-out\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private PlaybackService service() {
        return (PlaybackService) field(activity, "service");
    }
    private static Object field(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void checkPlayer(PlaybackService service, Object expected) {
        check(field(service, "player") == expected, "Repeated command created a different player");
        check(service.getError() == null, "Playback reported an error: " + service.getError());
        long writers = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals("Rain PCM playback")).count();
        check(writers <= 1, "Multiple PCM playback writers: " + writers);
    }
    private void stressSinglePlayback() {
        Object[] track = {null};
        runOnMainSync(() -> {
            PlaybackService service = service();
            service.playFromScreen();
            track[0] = field(service, "player");
            check(track[0] != null, "No player created");
            // Both repeated Play and rapid alternating screen taps must reuse it.
            for (int i=0; i<100; i++) {
                service.playFromScreen();
                ((Button) find("■  Stop")).performClick();
                ((Button) find("▶  Play")).performClick();
                checkPlayer(service, track[0]);
            }
        });
        // Exercise the service command path used by notification actions too.
        for (int i=0; i<20; i++) {
            getTargetContext().startService(new Intent(getTargetContext(), PlaybackService.class)
                    .setAction(PlaybackService.STOP));
            getTargetContext().startService(new Intent(getTargetContext(), PlaybackService.class)
                    .setAction(PlaybackService.PLAY));
            getTargetContext().startService(new Intent(getTargetContext(), PlaybackService.class)
                    .setAction(PlaybackService.PLAY));
        }
        waitForIdleSync();
        SystemClock.sleep(6500); // Old delayed Stop callbacks must not stop resumed audio.
        runOnMainSync(() -> {
            checkPlayer(service(), track[0]);
            check(service().isPlaying(), "Stale Stop callback interrupted resumed playback");
            check(((AudioTrack) track[0]).getPlayState() == AudioTrack.PLAYSTATE_PLAYING,
                    "AudioTrack is not playing");
            service().stopPlayback();
            service().stopPlayback();
        });
        SystemClock.sleep(6500);
        runOnMainSync(() -> {
            check(field(service(), "player") == null, "Stopped player was not released");
            check(field(service(), "pcmLoop") == null, "Stopped writer was not cleared");
            check(!service().isPlaying() && !service().isStopping(), "Stop did not finish");
            service().playFromScreen();
            Object next = field(service(), "player");
            check(next != null && next != track[0], "Restart did not create a fresh player");
            checkPlayer(service(), next);
            service().stopPlayback();
        });
        SystemClock.sleep(6500);
        runOnMainSync(() -> check(field(service(), "player") == null, "Final stop leaked player"));
        check(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().equals("Rain PCM playback")),
                "PCM writer leaked after Stop");
    }
    private View find(String text) { return findIn(activity.getWindow().getDecorView(), text); }
    private View findIn(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0;i<group.getChildCount();i++) {
                View found = findIn(group.getChildAt(i),text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
