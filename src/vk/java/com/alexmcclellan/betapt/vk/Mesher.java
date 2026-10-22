package com.alexmcclellan.betapt.vk;

/**
 * Turns a block grid into triangles for the hardware ray tracer: one square (2 triangles)
 * for every block face that touches air, nothing for faces hidden between two blocks.
 * A grassy hill 256 x 256 blocks wide ends up around a million triangles this way.
 */
public final class Mesher {
    public float[] verts;   // x, y, z per corner, 4 corners per face
    public int[] indices;   // 6 per face (two triangles)
    public int[] triInfo;   // per triangle: colour RGB in the low 24 bits, face direction (0..5) above
    public int faces;

    // face directions: -x, +x, -y, +y, -z, +z
    private static final int[][] DIR = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    // corners of each face on the unit cube, wound so the triangles face outwards
    private static final int[][][] CORNERS = {
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
    };

    /** ids: x + sx * (z + sz * y). solid[id]: a full cube the rays should hit. colors[id]: 0xRRGGBB. */
    public Mesher(byte[] ids, int sx, int sy, int sz, boolean[] solid, int[] colors) {
        for (int pass = 0; pass < 2; pass++) {
            int f = 0;
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    for (int x = 0; x < sx; x++) {
                        int id = ids[x + sx * (z + sz * y)] & 0xff;
                        if (!solid[id]) continue;
                        for (int d = 0; d < 6; d++) {
                            int nx = x + DIR[d][0], ny = y + DIR[d][1], nz = z + DIR[d][2];
                            if (ny < 0) continue; // the world's floor is never seen
                            boolean open = nx < 0 || nz < 0 || nx >= sx || nz >= sz || ny >= sy
                                    || !solid[ids[nx + sx * (nz + sz * ny)] & 0xff];
                            if (!open) continue;
                            if (pass == 1) {
                                int v = f * 4;
                                for (int c = 0; c < 4; c++) {
                                    verts[(v + c) * 3] = x + CORNERS[d][c][0];
                                    verts[(v + c) * 3 + 1] = y + CORNERS[d][c][1];
                                    verts[(v + c) * 3 + 2] = z + CORNERS[d][c][2];
                                }
                                int i = f * 6;
                                indices[i] = v; indices[i + 1] = v + 1; indices[i + 2] = v + 2;
                                indices[i + 3] = v; indices[i + 4] = v + 2; indices[i + 5] = v + 3;
                                int info = (colors[id] & 0xffffff) | (d << 24);
                                triInfo[f * 2] = info; triInfo[f * 2 + 1] = info;
                            }
                            f++;
                        }
                    }
            if (pass == 0) {
                faces = f;
                verts = new float[Math.max(1, f) * 12];
                indices = new int[Math.max(1, f) * 6];
                triInfo = new int[Math.max(1, f) * 2];
            }
        }
    }
}
