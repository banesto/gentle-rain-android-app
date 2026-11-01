package lv.quietnoise;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
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
            result.putString("stream", "PASS: Play, five-second sample fade, sustained playback, Stop, and drained fade-out\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
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
