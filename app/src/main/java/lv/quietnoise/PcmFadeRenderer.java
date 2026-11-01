package lv.quietnoise;

/** Sample-domain linear fades, independent of a device's volume controls. */
final class PcmFadeRenderer {
    private final short[] loop;
    private int position;
    private double gain;
    private double from;
    private double target;
    private long rampLength;
    private long rampPosition;
    private long frames;
    private long silenceFrame = -1;
    private float volume = 1f;

    PcmFadeRenderer(short[] loop) {
        if (loop.length == 0) throw new IllegalArgumentException("Empty audio loop");
        this.loop = loop;
    }
    synchronized void setVolume(float value) { volume = Math.max(0f, Math.min(1f, value)); }
    synchronized void fadeTo(double value, long durationFrames) {
        from = gain;
        target = Math.max(0, Math.min(1, value));
        rampLength = Math.max(2, durationFrames);
        rampPosition = 0;
        silenceFrame = target == 0 ? frames + rampLength : -1;
    }
    synchronized long silenceFrame() { return silenceFrame; }
    synchronized long framesRendered() { return frames; }
    synchronized void render(short[] output) {
        for (int i = 0; i < output.length; i++) {
            if (rampPosition < rampLength) {
                gain = from + (target - from) * rampPosition / (rampLength - 1);
                rampPosition++;
            } else gain = target;
            output[i] = (short) Math.round(loop[position] * gain * volume);
            if (++position == loop.length) position = 0;
            frames++;
        }
    }
}
