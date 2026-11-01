package lv.quietnoise;

import android.media.AudioTrack;
import android.os.Process;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Xiaomi fallback: gain is already in each PCM sample, never in a hardware ramp. */
final class PcmStreamLoop {
    private static final int RATE = 44100;
    private final AudioTrack track;
    private final PcmFadeRenderer renderer;
    private final java.util.function.Consumer<PcmStreamLoop> onFailure;
    private volatile boolean running;
    private volatile long written;

    PcmStreamLoop(AudioTrack track, byte[] pcm, java.util.function.Consumer<PcmStreamLoop> onFailure) {
        this.track = track;
        this.onFailure = onFailure;
        short[] samples = new short[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        renderer = new PcmFadeRenderer(samples);
    }
    void setVolume(float volume) { renderer.setVolume(volume); }
    void fadeTo(boolean audible, long millis) { renderer.fadeTo(audible ? 1 : 0, RATE * millis / 1000); }
    void start() {
        running = true;
        track.play();
        new Thread(this::writeAudio, "Rain PCM playback").start();
    }
    private void writeAudio() {
        short[] block = new short[2048];
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (running) {
                renderer.render(block);
                int offset = 0;
                while (running && offset < block.length) {
                    int count = track.write(block, offset, block.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (count <= 0) {
                        if (running) throw new IllegalStateException("Audio write failed: " + count);
                        break;
                    }
                    offset += count;
                    written += count;
                }
            }
        } catch (RuntimeException error) {
            if (running) { running = false; onFailure.accept(this); }
        } finally {
            // This thread owns release, after any blocking write has returned.
            track.release();
        }
    }
    boolean reachedSilence() {
        long end = renderer.silenceFrame();
        if (end < 0 || written < end) return false;
        long head = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
        // Extend Android's 32-bit playback head across multi-day sessions.
        long played = (written & ~0xffffffffL) | head;
        if (played > written) played -= 1L << 32;
        return played >= end;
    }
    void close() {
        running = false;
        // Pause interrupts a blocking write; flush discards only inaudible data
        // after a completed stop fade, or immediately after focus loss.
        try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {}
    }
}
