package lv.quietnoise;

import java.util.Arrays;

/** Verifies the actual sample renderer independently of Android/OEM volume APIs. */
public final class PcmFadeRendererCheck {
    static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int duration = 44100 * 5;
        PcmFadeRenderer renderer = new PcmFadeRenderer(new short[]{30000});
        renderer.fadeTo(1, duration);
        short[] up = new short[duration];
        renderer.render(up);
        expect(up[0] == 0 && up[duration-1] == 30000, "Starts at zero and reaches target after five seconds");
        for (int i=1; i<duration; i++) {
            expect(up[i] >= up[i-1] && up[i]-up[i-1] <= 1, "No gain steps in fade-in");
            expect(up[i] == Math.round(30000.0*i/(duration-1)), "Exact linear amplitude ramp");
        }
        renderer.fadeTo(0, duration);
        short[] down = new short[duration];
        renderer.render(down);
        expect(down[0] == 30000 && down[duration-1] == 0, "Fade-out endpoints");
        for (int i=1; i<duration; i++) expect(down[i-1]-down[i] >= 0 && down[i-1]-down[i] <= 1, "No gain steps in fade-out");
        short[] zeros = new short[2048];
        renderer.render(zeros);
        for (short sample: zeros) expect(sample==0, "No sound after fade completes");
        renderer.fadeTo(1, duration);
        short[] partial = new short[44100];
        renderer.render(partial);
        renderer.fadeTo(0, duration);
        short[] reversed = new short[2048];
        renderer.render(reversed);
        expect(reversed[0] == partial[partial.length-1], "Retarget without jump");
        PcmFadeRenderer chunked = new PcmFadeRenderer(new short[]{30000});
        chunked.fadeTo(1, duration);
        short[] reconstructed = new short[duration];
        for (int offset=0;offset<duration;) {
            short[] block = new short[Math.min(2048,duration-offset)];
            chunked.render(block);
            System.arraycopy(block,0,reconstructed,offset,block.length);
            offset+=block.length;
        }
        expect(Arrays.equals(up,reconstructed), "Block boundaries cannot cause steps");
        PcmFadeRenderer loop = new PcmFadeRenderer(new short[]{100,-200,300});
        loop.fadeTo(1,2);
        loop.render(new short[2]);
        short[] repeated = new short[300];
        loop.render(repeated);
        for (int i=0;i<repeated.length;i++) expect(repeated[i]==new short[]{300,100,-200}[i%3], "Loop has no gap or repeated frame");
        System.out.println("PASS: exact five-second fades, silence at start/end, no coarse steps, chunk continuity, reversals, and looping");
    }
}
