package lv.quietnoise;

import android.app.*;
import android.annotation.SuppressLint;
import android.content.*;
import android.media.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.session.*;
import android.os.*;
import java.io.*;
import java.nio.*;

/** Owns playback independently of the screen. All state changes run on the main thread. */
public final class PlaybackService extends Service {
    public static final String PLAY = "lv.quietnoise.PLAY";
    public static final String SHOW = "lv.quietnoise.SHOW";
    public static final String STOP = "lv.quietnoise.STOP";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 1;
    private final LocalBinder binder = new LocalBinder();
    private AudioTrack player;
    private VolumeShaper fade;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long FADE_MS = 5000;
    private boolean stopping;
    private boolean closing;
    private boolean sessionStarted;
    private Bitmap artwork;
    private float duck = 1f;
    private final Runnable finishStop = this::releasePlayback;
    private final Runnable notificationTick = this::updateNotification;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Runnable listener;
    private boolean playing;
    private boolean hasFocus;
    private String error;
    private PlaybackTimer timer;
    static final String TIMER_ELAPSED = "timerElapsed";

    public long getElapsedMillis() { return timer.elapsedMillis(); }
    public boolean isTimerRunning() { return timer.isRunning(); }
    public void resetTimer() {
        timer.reset();
        saveTimer();
        updateNotification();
        notifyUi();
    }
    private void saveTimer() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putLong(TIMER_ELAPSED, timer.elapsedMillis()).apply();
    }
    private void freezeTimer() {
        if (timer.isRunning()) {
            timer.stop();
            saveTimer();
        }
    }

    public final class LocalBinder extends Binder {
        public PlaybackService service() { return PlaybackService.this; }
    }
    public boolean isPlaying() { return playing && !stopping; }
    public boolean needsSessionStart() { return !sessionStarted || closing; }
    public boolean isStopping() { return stopping; }
    public String getError() { return error; }
    public void setListener(Runnable listener) { this.listener = listener; }
    private void notifyUi() { if (listener != null) listener.run(); }

    @Override public void onCreate() {
        super.onCreate();
        // Apply the new default once on upgrade; remember later slider changes.
        SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        if (!settings.getBoolean("volumeDefault60Applied", false)) {
            settings.edit().putInt("volume", 60).putBoolean("volumeDefault60Applied", true).apply();
        }
        timer = new PlaybackTimer(SystemClock::elapsedRealtime,
                getSharedPreferences("settings", MODE_PRIVATE).getLong(TIMER_ELAPSED, 0));
        artwork = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        artwork.eraseColor(Color.BLACK);
        audio = getSystemService(AudioManager.class);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietNoise:Playback");
        wakeLock.setReferenceCounted(false);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    // Do not unexpectedly restart after calls or another app takes audio focus.
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) releasePlayback();
                    else if (player != null && playing) {
                        duck = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? 0.2f : 1f;
                        applyVolume();
                    }
                }).build();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Audio playback", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        session = new MediaSession(this, "Quiet Noise");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { startForegroundService(new Intent(PlaybackService.this, PlaybackService.class).setAction(PLAY)); }
            @Override public void onPause() { stopPlayback(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (STOP.equals(action)) stopPlayback();
            }
            @Override public void onStop() { stopPlayback(); }
        });
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { releasePlayback(); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Reopening during the five-second close fade keeps the app available.
        if (closing && intent != null && (SHOW.equals(intent.getAction()) || PLAY.equals(intent.getAction()))) closing = false;
        if (closing) return START_NOT_STICKY;
        // Foreground first: required before requesting focus on Android 15+.
        if (!sessionStarted) {
            startForeground(NOTIFICATION, notification());
            session.setActive(true);
            sessionStarted = true;
        }
        if (intent != null && PLAY.equals(intent.getAction())) startPlayback();
        else if (intent != null && STOP.equals(intent.getAction())) stopPlayback();
        else updateNotification();
        return START_NOT_STICKY;
    }
    public void playFromScreen() {
        if (!sessionStarted) {
            startForegroundService(new Intent(this, PlaybackService.class).setAction(PLAY));
        } else startPlayback();
    }
    private void startPlayback() {
        if (playing) {
            if (stopping) {
                // Reverse direction at the current native gain: never start a
                // second AudioTrack or jump to full volume during a stop fade.
                handler.removeCallbacks(finishStop);
                stopping = false;
                timer.start();
                saveTimer();
                fade.replace(fadeConfiguration(true), VolumeShaper.Operation.PLAY, true);
                updateNotification();
                notifyUi();
            }
            return;
        }
        error = null;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = "Audio is in use. Try again in a moment.";
            stopPlayback();
            return;
        }
        hasFocus = true;
        try {
            byte[] pcm = loadPcm();
            player = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                    .build();
            if (player.write(pcm, 0, pcm.length) != pcm.length
                    || player.setLoopPoints(0, pcm.length / 2, -1) != AudioTrack.SUCCESS) {
                throw new IOException("Could not load continuous loop");
            }
            duck = 1f;
            applyVolume();
            fade = player.createVolumeShaper(fadeConfiguration(true));
            fade.apply(VolumeShaper.Operation.PLAY);
            wakeLock.acquire();
            player.play();
            timer.start();
            saveTimer();
            playing = true;
            updateNotification();
            notifyUi();
        } catch (Exception exception) {
            error = "Could not play audio. Tap Play to try again.";
            releasePlayback();
        }
    }
    // This app bundles a canonical 44-byte-header, mono 44.1 kHz PCM WAV.
    // Validate the exact format before handing its sample buffer to AudioTrack.
    private byte[] loadPcm() throws IOException {
        try (DataInputStream input = new DataInputStream(getResources().openRawResource(R.raw.gentle_rain))) {
            byte[] header = new byte[44];
            input.readFully(header);
            ByteBuffer data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (data.getInt(0) != 0x46464952 || data.getInt(8) != 0x45564157
                    || data.getInt(12) != 0x20746d66 || data.getInt(16) != 16
                    || data.getShort(20) != 1 || data.getShort(22) != 1
                    || data.getInt(24) != 44100 || data.getShort(34) != 16
                    || data.getInt(36) != 0x61746164) throw new IOException("Unsupported audio format");
            int length = data.getInt(40);
            if (length <= 0 || length > 16 * 1024 * 1024 || length % 2 != 0) throw new IOException("Invalid audio length");
            byte[] pcm = new byte[length];
            input.readFully(pcm);
            return pcm;
        }
    }
    public void setVolume(int value) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("volume", value).apply();
        applyVolume();
    }
    public int getVolume() { return getSharedPreferences("settings", MODE_PRIVATE).getInt("volume", 60); }
    private void applyVolume() {
        if (player != null) {
            float level = getVolume() / 100f * duck;
            player.setVolume(level);
        }
    }
    private VolumeShaper.Configuration fadeConfiguration(boolean fadeIn) {
        return new VolumeShaper.Configuration.Builder()
                .setDuration(FADE_MS)
                .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                .setCurve(new float[]{0f, 1f}, fadeIn ? new float[]{0f, 1f} : new float[]{1f, 0f})
                .build();
    }
    public void stopPlayback() {
        if (player == null || !playing) { releasePlayback(); return; }
        if (stopping) return;
        stopping = true;
        // The displayed session ends at the Stop press, not after the fade.
        freezeTimer();
        // Join at the current level so even a stop during fade-in takes five
        // seconds to reach silence, without a volume jump or a Java update loop.
        fade.replace(fadeConfiguration(false), VolumeShaper.Operation.PLAY, true);
        handler.postDelayed(finishStop, FADE_MS);
        updateNotification();
        notifyUi();
    }
    private void releasePlayback() {
        handler.removeCallbacks(finishStop);
        handler.removeCallbacks(notificationTick);
        freezeTimer();
        stopping = false;
        playing = false;
        if (player != null) player.setVolume(0f);
        if (fade != null) { fade.close(); fade = null; }
        if (player != null) { player.release(); player = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (hasFocus) { audio.abandonAudioFocusRequest(focus); hasFocus = false; }
        if (closing) {
            session.setActive(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            // Keep only the notification/session alive. No audio or wake lock
            // remains, and there are no periodic callbacks while stopped.
            updateNotification();
        }
        notifyUi();
    }
    private PendingIntent command(String action, int id) {
        return PendingIntent.getService(this, id, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    // Media-session notifications are exempt from POST_NOTIFICATIONS permission.
    @SuppressLint("NotificationPermission")
    private void updateNotification() {
        handler.removeCallbacks(notificationTick);
        if (closing) return;
        String elapsed = "Playback time · " + PlaybackTimer.format(timer.elapsedMillis());
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Gentle rain")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Gentle rain")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, elapsed)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, elapsed)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork).build());
        if (Build.VERSION.SDK_INT < 33) {
            // Older Android/MIUI expects a complete state. Its media notification
            // controls come from our action list, so this still shows Play/Stop.
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_STOP)
                    .setState(playing && !stopping ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing && !stopping ? 1f : 0f).build());
        } else {
            // Android 13+ otherwise substitutes Pause for the requested Stop.
            session.setPlaybackState(null);
        }
        if (sessionStarted) {
            // Update the existing card without re-promoting the service every
            // time the main button is pressed or a displayed minute changes.
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        }
        // One update per displayed minute while audio is active; no second ticks.
        if (timer.isRunning()) handler.postDelayed(notificationTick, 60_000 - timer.elapsedMillis() % 60_000);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String state = stopping ? "Fading out" : playing ? "Playing" : "Stopped";
        boolean showStop = playing && !stopping;
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_noise)
                .setContentTitle("Gentle rain")
                .setContentText(state + " · " + PlaybackTimer.format(timer.elapsedMillis()))
                .setSubText("Playback time · " + PlaybackTimer.format(timer.elapsedMillis()))
                .setColor(Color.BLACK).setColorized(true).setLargeIcon(artwork)
                .setShowWhen(false).setCategory(Notification.CATEGORY_TRANSPORT)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(showStop ? R.drawable.ic_stop : android.R.drawable.ic_media_play,
                        showStop ? "Stop" : "Play", command(showStop ? STOP : PLAY, showStop ? 1 : 2)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0)).build();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // Home/locking keeps playback available; removing the app from Recents
        // explicitly closes it. Finish the same smooth fade before removing it.
        closing = true;
        if (playing) stopPlayback();
        else releasePlayback();
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        closing = true;
        listener = null;
        releasePlayback();
        unregisterReceiver(noisyReceiver);
        session.release();
        super.onDestroy();
    }
}
