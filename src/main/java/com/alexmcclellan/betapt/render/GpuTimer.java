package com.alexmcclellan.betapt.render;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Measures how long the GPU spends on our work each frame.
 *
 * GPU timer results arrive a frame or two late, so we rotate through a few query
 * objects and read whichever finished. lastMs is smoothed so the F3 number is readable.
 */
public final class GpuTimer {
    private static final int RING = 4;
    private final int[] queries = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int index;
    public double lastMs;
    public double smoothedMs;

    public GpuTimer() {
        for (int i = 0; i < RING; i++) queries[i] = GL15.glGenQueries();
    }

    public void begin() {
        collect();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[index]);
    }

    public void end() {
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending[index] = true;
        index = (index + 1) % RING;
    }

    private void collect() {
        for (int i = 0; i < RING; i++) {
            if (pending[i] && GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                lastMs = GL33.glGetQueryObjectui64(queries[i], GL15.GL_QUERY_RESULT) / 1.0e6;
                smoothedMs = smoothedMs == 0 ? lastMs : smoothedMs * 0.95 + lastMs * 0.05;
                pending[i] = false;
            }
        }
    }
}
