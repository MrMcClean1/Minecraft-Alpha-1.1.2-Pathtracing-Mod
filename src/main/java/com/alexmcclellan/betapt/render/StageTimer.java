package com.alexmcclellan.betapt.render;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * GPU time per stage of the frame (trace, denoise, ...), to see where the milliseconds go.
 *
 * Drops a GPU timestamp at each stage boundary; the gap between two timestamps is how
 * long the stage between them took. Results arrive a frame or two late, so a few frames
 * of query objects are kept in rotation, and the averages are smoothed for display.
 */
public final class StageTimer {
    public static final String[] STAGES = {"trace", "temporal", "spatial", "exposure+tonemap", "upscale+sharpen"};
    private static final int MARKS = STAGES.length + 1, RING = 4;
    private final int[][] queries = new int[RING][MARKS];
    private final boolean[] pending = new boolean[RING];
    private final double[] smoothed = new double[STAGES.length];
    private final double[] windowSum = new double[STAGES.length];
    private int windowFrames;
    private int frame, mark;

    public StageTimer() {
        for (int f = 0; f < RING; f++) for (int m = 0; m < MARKS; m++) queries[f][m] = GL15.glGenQueries();
    }

    /** Start of the frame's work: also collects finished results from earlier frames. */
    public void begin() {
        collect();
        mark = 0;
        stamp();
    }

    /** End of a stage (call once per stage, in STAGES order). Stages that didn't run (denoiser off) still stamp. */
    public void stamp() {
        if (mark < MARKS) GL33.glQueryCounter(queries[frame][mark++], GL33.GL_TIMESTAMP);
        if (mark == MARKS) {
            pending[frame] = true;
            frame = (frame + 1) % RING;
            mark = MARKS + 1; // ignore extra stamps until the next begin()
        }
    }

    private void collect() {
        for (int f = 0; f < RING; f++) {
            if (!pending[f] || GL15.glGetQueryObjecti(queries[f][MARKS - 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long prev = GL33.glGetQueryObjectui64(queries[f][0], GL15.GL_QUERY_RESULT);
            for (int s = 0; s < STAGES.length; s++) {
                long t = GL33.glGetQueryObjectui64(queries[f][s + 1], GL15.GL_QUERY_RESULT);
                double ms = (t - prev) / 1.0e6;
                smoothed[s] = smoothed[s] == 0 ? ms : smoothed[s] * 0.95 + ms * 0.05;
                windowSum[s] += ms;
                prev = t;
            }
            pending[f] = false;
            windowFrames++;
        }
    }

    /** Start a fresh exact average (for the autotest's cost probe). */
    public void resetWindow() {
        java.util.Arrays.fill(windowSum, 0);
        windowFrames = 0;
    }

    /** Exact average per stage since resetWindow, plus the total. */
    public String windowSummary() {
        if (windowFrames == 0) return "no frames";
        StringBuilder sb = new StringBuilder();
        double total = 0;
        for (int s = 0; s < STAGES.length; s++) {
            double ms = windowSum[s] / windowFrames;
            total += ms;
            sb.append(s > 0 ? ", " : "").append(STAGES[s]).append(String.format(" %.2f", ms));
        }
        return String.format("total %.2f (%s) over %d frames", total, sb, windowFrames);
    }

    /** e.g. "trace 6.12, temporal 0.21, spatial 0.55, exposure+tonemap 0.08" */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < STAGES.length; s++) {
            if (s > 0) sb.append(", ");
            sb.append(STAGES[s]).append(String.format(" %.2f", smoothed[s]));
        }
        return sb.append(" ms").toString();
    }
}
