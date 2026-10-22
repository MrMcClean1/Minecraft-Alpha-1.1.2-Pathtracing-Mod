package com.alexmcclellan.betapt.vk;

/**
 * Mobs to triangles for the hardware tracer, every frame. The game's EntityCapture already
 * turns each model part into a box floating in the world (centre, axes, size, skin position);
 * here each box becomes 12 triangles, with the skin pixel position at every corner, laid out
 * the way Beta wraps a skin around a box (pathtrace.comp's skinTexel).
 *
 * Box input, 24 floats each (EntityCapture.upload): centre xyz + half x, inverse axes rows (+ half y,
 * half z, overlay opacity), colour (linear) + skin layer, skin u, v, row scale (negative: mirrored), size in texels packed
 * as x + y * 256 + z * 65536. A box with layer -2 is a hurt/creeper flash laid over the box just like it.
 */
final class MobMesher {
    static final int KIND_MOB = 2;
    final ChunkMesher.Group g = new ChunkMesher.Group();

    private static final int[][][] CORNER = {
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
    };
    private static final float[][] LN = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    static int srgb8(float r, float g, float b) {
        return (int) (Math.pow(clamp(r), 1 / 2.2) * 255 + 0.5) << 16 | (int) (Math.pow(clamp(g), 1 / 2.2) * 255 + 0.5) << 8
                | (int) (Math.pow(clamp(b), 1 / 2.2) * 255 + 0.5);
    }
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** ent: per box, which mob it belongs to; stored (+1, as a float) in each triangle's spare slot for motion. */
    MobMesher(float[] b, int count, int[] ent) {
        int[] firstQuad = new int[count];
        float[][] p = new float[4][3], uv = new float[4][2];
        for (int i = 0; i < count; i++) {
            int o = i * 24;
            firstQuad[i] = -1;
            float layer = b[o + 19];
            if (layer < -1.5f) { // flash overlay: colour every matching box's triangles
                // (a spider is drawn twice, body then glowing eyes, so the same box can appear more than once)
                int over = srgb8(b[o + 16], b[o + 17], b[o + 18]) | Math.round(clamp(b[o + 15]) * 255) << 24;
                for (int j = i - 1; j >= Math.max(0, i - 64); j--) {
                    if (firstQuad[j] < 0 || !sameBox(b, o, j * 24)) continue;
                    for (int q = firstQuad[j]; q < firstQuad[j] + 6; q++)
                        for (int t = 0; t < 2; t++) g.tri[(q * 2 + t) * ChunkMesher.INTS_PER_TRI + 3] = over;
                }
                continue;
            }
            float cx = b[o], cy = b[o + 1], cz = b[o + 2];
            float hx = b[o + 3], hy = b[o + 7], hz = b[o + 11];
            float[] r0 = {b[o + 4], b[o + 5], b[o + 6]}, r1 = {b[o + 8], b[o + 9], b[o + 10]}, r2 = {b[o + 12], b[o + 13], b[o + 14]};
            double[] a = invert(r0, r1, r2); // local -> world axes
            boolean mir = b[o + 22] < 0; // the mirror slot: the row scale, negative for a mirrored part
            float vScale = Math.abs(b[o + 22]);
            int packed = Math.round(b[o + 23]);
            float dx = packed & 255, dy = (packed >> 8) & 255, dz = packed >> 16, u0 = b[o + 20], v0 = b[o + 21];
            int skin = layer >= 0 ? Math.round(layer) + 1 : 0;
            int tint = srgb8(b[o + 16], b[o + 17], b[o + 18]);
            firstQuad[i] = g.quads;
            for (int f = 0; f < 6; f++) {
                float[] ln = LN[f];
                for (int c = 0; c < 4; c++) {
                    float fx = CORNER[f][c][0], fy = CORNER[f][c][1], fz = CORNER[f][c][2];
                    float lx = (fx * 2 - 1) * hx, ly = (fy * 2 - 1) * hy, lz = (fz * 2 - 1) * hz;
                    p[c][0] = cx + (float) (a[0] * lx + a[1] * ly + a[2] * lz);
                    p[c][1] = cy + (float) (a[3] * lx + a[4] * ly + a[5] * lz);
                    p[c][2] = cz + (float) (a[6] * lx + a[7] * ly + a[8] * lz);
                    float gx = mir ? 1 - fx : fx;
                    float u, v;
                    if (ln[0] != 0) {
                        boolean face0 = (ln[0] > 0) != mir;
                        u = face0 ? u0 + dz + dx + fz * dz : u0 + (1 - fz) * dz;
                        v = v0 + dz + fy * dy;
                    } else if (ln[1] < 0) { u = u0 + dz + gx * dx; v = v0 + (1 - fz) * dz; }
                    else if (ln[1] > 0) { u = u0 + dz + dx + gx * dx; v = v0 + fz * dz; }
                    else if (ln[2] < 0) { u = u0 + dz + gx * dx; v = v0 + dz + fy * dy; }
                    else { u = u0 + 2 * dz + dx + (1 - gx) * dx; v = v0 + dz + fy * dy; }
                    uv[c][0] = u; uv[c][1] = v * vScale;
                }
                // face normal back into the world: transpose of the inverse axes, as the OpenGL tracer
                float nx = r0[0] * ln[0] + r1[0] * ln[1] + r2[0] * ln[2];
                float ny = r0[1] * ln[0] + r1[1] * ln[1] + r2[1] * ln[2];
                float nz = r0[2] * ln[0] + r1[2] * ln[1] + r2[2] * ln[2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-12f;
                g.quad(p, uv, skin, 0, KIND_MOB, tint, nx / len, ny / len, nz / len);
                int mob = Float.floatToIntBits(ent != null && i < ent.length ? ent[i] + 1 : 0);
                for (int t = 0; t < 2; t++) g.tri[((g.quads - 1) * 2 + t) * ChunkMesher.INTS_PER_TRI + 10] = mob;
            }
        }
    }

    private static boolean sameBox(float[] b, int o1, int o2) {
        for (int k = 0; k < 15; k++) if (Math.abs(b[o1 + k] - b[o2 + k]) > 1e-4f) return false;
        return true;
    }

    private static double[] invert(float[] r0, float[] r1, float[] r2) {
        double a = r0[0], b = r0[1], c = r0[2], d = r1[0], e = r1[1], f = r1[2], g = r2[0], h = r2[1], i = r2[2];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12) det = 1e-12;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }
}
