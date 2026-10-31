package lv.quietnoise;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int INK = Color.BLACK;
    private static final int MINT = Color.rgb(160, 178, 166);
    private PlaybackService service;
    private boolean bound;
    private Button toggle;
    private TextView status;
    private TextView volumeLabel;
    private SeekBar volume;
    private TextView timerValue;
    private Button resetTimer;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerTick = this::renderTimer;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            service.setListener(MainActivity.this::render);
            volume.setProgress(service.getVolume());
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; render(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.setBackgroundColor(INK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(32) + insets.getSystemWindowInsetLeft(), dp(24) + insets.getSystemWindowInsetTop(),
                    dp(32) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        TextView title = text("Gentle rain", 34, MINT);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        status = text("Ready when you are", 16, Color.rgb(154, 177, 166));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(12), 0, dp(24));
        root.addView(status, statusParams);
        toggle = new Button(this);
        toggle.setTextSize(36);
        toggle.setTextColor(MINT);
        toggle.setAllCaps(false);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(24, 32, 28));
        circle.setStroke(dp(1), Color.rgb(67, 82, 72));
        toggle.setBackground(circle);
        root.addView(toggle, new LinearLayout.LayoutParams(dp(260), dp(260)));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int size = Math.min(dp(260), view.getWidth() - view.getPaddingLeft() - view.getPaddingRight());
            if (size > 0 && toggle.getLayoutParams().width != size) {
                toggle.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            }
        });
        toggle.setEnabled(false);
        toggle.setOnClickListener(view -> {
            if (service == null) return;
            if (service.isPlaying()) service.stopPlayback();
            else startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY));
        });
        TextView timerLabel = text("Playback time", 15, Color.rgb(154, 177, 166));
        LinearLayout.LayoutParams timerLabelParams = new LinearLayout.LayoutParams(-1, -2);
        timerLabelParams.setMargins(0, dp(24), 0, dp(4));
        root.addView(timerLabel, timerLabelParams);
        timerValue = text("0 h 00 min", 36.8f, MINT);
        timerValue.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        root.addView(timerValue, new LinearLayout.LayoutParams(-1, -2));
        resetTimer = new Button(this);
        resetTimer.setText("Reset timer");
        resetTimer.setTextSize(14);
        resetTimer.setTextColor(MINT);
        resetTimer.setAllCaps(false);
        resetTimer.setMinHeight(dp(48));
        GradientDrawable resetBackground = new GradientDrawable();
        resetBackground.setColor(INK);
        resetBackground.setCornerRadius(dp(12));
        resetBackground.setStroke(dp(1), Color.rgb(67, 82, 72));
        resetTimer.setBackground(resetBackground);
        resetTimer.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-2, -2);
        resetParams.topMargin = dp(8);
        root.addView(resetTimer, resetParams);
        resetTimer.setOnClickListener(view -> {
            if (service != null) service.resetTimer();
        });
        volumeLabel = text("Volume · 60%", 17, MINT);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.setMargins(0, dp(24), 0, dp(12));
        root.addView(volumeLabel, labelParams);
        volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(getSharedPreferences("settings", MODE_PRIVATE).getInt("volume", 60));
        volume.setContentDescription("Gentle rain volume");
        root.addView(volume, new LinearLayout.LayoutParams(-1, dp(48)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeLabel.setText("Volume · " + progress + "%");
                if (fromUser && service != null) service.setVolume(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        volumeLabel.setText("Volume · " + volume.getProgress() + "%");
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void render() {
        boolean playing = service != null && service.isPlaying();
        toggle.setEnabled(service != null);
        toggle.setText(playing ? "■  Stop" : "▶  Play");
        status.setText(service != null && service.getError() != null ? service.getError() : service != null && service.isStopping() ? "Fading out…" : playing ? "Playing" : "Ready when you are");
        renderTimer();
    }
    private void renderTimer() {
        timerHandler.removeCallbacks(timerTick);
        long elapsed = service != null ? service.getElapsedMillis()
                : getSharedPreferences("settings", MODE_PRIVATE).getLong(PlaybackService.TIMER_ELAPSED, 0);
        timerValue.setText(PlaybackTimer.format(elapsed));
        resetTimer.setEnabled(service != null);
        // Refresh only when a displayed minute changes, and only while visible.
        if (service != null && service.isTimerRunning()) {
            timerHandler.postDelayed(timerTick, 60_000 - elapsed % 60_000);
        }
    }
    @Override protected void onStart() {
        super.onStart();
        render();
        startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.SHOW));
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        timerHandler.removeCallbacks(timerTick);
        if (service != null) service.setListener(null);
        if (bound) { unbindService(connection); bound = false; }
        service = null;
        super.onStop();
    }
}
