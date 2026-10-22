package com.alexmcclellan.betapt.vk;

/**
 * One chunk (16 x 128 x 16) to triangles for the hardware tracer, block shapes as the OpenGL
 * tracer sees them (BlockTable): full cubes, plants (two crossed squares), multi-box blocks
 * (torches, slabs, stairs, fences, doors...) and liquids with their lowered surface.
 *
 * Three groups, which the tracer treats differently:
 *   opaque   - full cubes and lava: the hardware just takes the nearest one;
 *   cutout   - anything whose texture has holes (leaves, glass, plants, torches, fences...): each
 *              hit is handed back to the shader, which keeps it only where the texture pixel is solid;
 *   clear    - water and ice: never stop a ray, only tint and dim what's behind them.
 *
 * Per triangle, 12 ints (vkpath.comp's Tri): block id (12 bits) | kind << 12 | texture tile << 16, tint,
 * packed normal, spare, then the texture position (0..1 inside the tile) at its three corners.
 */
final class ChunkMesher {
    static final int INTS_PER_TRI = 12;
    static final int CUBE = 1, CROSS = 2, BOX = 3, LIQUID = 4;
    static final int KIND_FACE = 0, KIND_CROSS = 1, KIND_FACE2 = 3; // 2 is mobs (MobMesher)
    /** BlockTable's FLAG_CUTOUT: a modded full cube with see-through pixels (its leaves, glass). */
    static final int FLAG_CUTOUT = 4;

    /** One group: corners (x, y, z), 6 indices per square, 2 x 12 ints per square. */
    static final class Group {
        float[] v = new float[3 * 4 * 256];
        int[] idx = new int[6 * 256];
        int[] tri = new int[2 * INTS_PER_TRI * 256];
        int quads;

        void quad(float[][] p, float[][] uv, int id, int tile, int kind, int tint, float nx, float ny, float nz) {
            if ((quads + 1) * 12 > v.length) {
                v = java.util.Arrays.copyOf(v, v.length * 2);
                idx = java.util.Arrays.copyOf(idx, idx.length * 2);
                tri = java.util.Arrays.copyOf(tri, tri.length * 2);
            }
            int base = quads * 4;
            for (int c = 0; c < 4; c++) { v[(base + c) * 3] = p[c][0]; v[(base + c) * 3 + 1] = p[c][1]; v[(base + c) * 3 + 2] = p[c][2]; }
            int i = quads * 6;
            idx[i] = base; idx[i + 1] = base + 1; idx[i + 2] = base + 2;
            idx[i + 3] = base; idx[i + 4] = base + 2; idx[i + 5] = base + 3;
            int n = packNormal(nx, ny, nz);
            int[][] corners = {{0, 1, 2}, {0, 2, 3}};
            for (int t = 0; t < 2; t++) {
                int o = (quads * 2 + t) * INTS_PER_TRI;
                tri[o] = id | kind << 12 | tile << 16;
                tri[o + 1] = tint;
                tri[o + 2] = n;
                tri[o + 3] = 0;
                for (int k = 0; k < 3; k++) {
                    tri[o + 4 + k * 2] = Float.floatToRawIntBits(uv[corners[t][k]][0]);
                    tri[o + 5 + k * 2] = Float.floatToRawIntBits(uv[corners[t][k]][1]);
                }
                tri[o + 10] = 0;
                tri[o + 11] = 0;
            }
            quads++;
        }
    }

    final Group opaque = new Group(), cutout = new Group(), clear = new Group();

    private final short[] ids;
    private final byte[] meta;
    private final int[] info;
    private final float[] boxes;
    /** Leaves: Fast (solid, like Beta's Fast graphics): they hide each other's faces and need no texture test. */
    private final boolean fastLeaves;

    static int packNormal(float x, float y, float z) {
        return (Math.round(x * 127) & 255) | (Math.round(y * 127) & 255) << 8 | (Math.round(z * 127) & 255) << 16;
    }

    /** The blocks of the four chunks around this one (west, east, north, south; null: not loaded), for faces on its edges. */
    private final short[][] nIds;
    private final byte[][] nMeta;

    /** Which neighbour holds x, z (just over an edge: -1 or 16), or -1 inside this chunk or past a corner. */
    private static int side(int x, int z) {
        boolean xin = x >= 0 && x <= 15, zin = z >= 0 && z <= 15;
        if (xin && zin) return -1;
        if (zin) return x < 0 ? 0 : 1;
        if (xin) return z < 0 ? 2 : 3;
        return -2;
    }
    private int id(int x, int y, int z) {
        if (y < 0 || y > 127) return 0;
        int k = side(x, z);
        if (k == -1) return ids[x << 11 | z << 7 | y] & 0xFFFF;
        if (k < 0 || nIds == null || nIds[k] == null) return 0;
        return nIds[k][(x & 15) << 11 | (z & 15) << 7 | y] & 0xFFFF;
    }
    private int meta(int x, int y, int z) {
        int k = side(x, z);
        byte[] arr = k == -1 ? meta : k >= 0 && nMeta != null ? nMeta[k] : null;
        if (arr == null || y < 0 || y > 127) return 0;
        int i = (x & 15) << 11 | (z & 15) << 7 | y;
        return (i & 1) == 0 ? arr[i >> 1] & 15 : (arr[i >> 1] >> 4) & 15;
    }
    private int shapeAt(int x, int y, int z) {
        int id = id(x, y, z);
        if (id == 0) return 0;
        return info[(id * 16 + meta(x, y, z)) * 9] & 15;
    }
    /** Pipes and cables: an arm toward a neighbour it would join (same as armOk in the OpenGL shaders). */
    private boolean armOk(int cond, int x, int y, int z, int id) {
        int n = cond == 7 ? id(x - 1, y, z) : cond == 8 ? id(x + 1, y, z) : cond == 9 ? id(x, y, z - 1)
                : cond == 10 ? id(x, y, z + 1) : cond == 11 ? id(x, y - 1, z) : id(x, y + 1, z);
        return n != 0 && (n == id || n >= 97 || n == 23 || n == 54 || n == 61 || n == 62);
    }
    private static boolean clearId(int id) { return id == 8 || id == 9 || id == 79; }
    /** BlockTable's FLAG_TRANSLUCENT on a modded cube (Aether's Quicksoil Glass): blended like ice, so it tints rather than blocks. */
    private boolean clearAt(int id, int word) { return clearId(id) || (id >= 97 && ((word >> 16) & 1) != 0); }
    static boolean holes(int id) { return id == 18 || id == 20 || id == 52; } // leaves, glass, spawner: see-through pixels
    /** See-through pixels at this block: the vanilla ones above (leaves only on Fancy), or a modded cube BlockTable flagged. */
    private boolean holesAt(int id, int word) {
        if (id == 18) return !fastLeaves;
        return holes(id) || ((word >> 16) & FLAG_CUTOUT) != 0;
    }
    /** True if a full, solid cube is at x,y,z (hides the faces touching it). */
    /**
     * The same liquid at x, y, z as block id: water's two ids, lava's two, or a modded liquid's still and flowing
     * blocks (neighbouring ids, like BuildCraft's oil). Stacked, it's drawn full height with no faces between.
     */
    private boolean sameLiquid(int id, int x, int y, int z) {
        if (y < 0 || y > 127) return false;
        int n = id(x, y, z);
        if (id == 8 || id == 9) return n == 8 || n == 9;
        if (id == 10 || id == 11) return n == 10 || n == 11;
        return Math.abs(n - id) <= 1 && n != 0 && (info[(n * 16 + meta(x, y, z)) * 9] & 15) == LIQUID;
    }

    /** Height of the liquid's surface in the block at x, y, z (1 under more of it). */
    private float liquidTop(int x, int y, int z) {
        int n = id(x, y, z);
        float top = boxes[(info[(n * 16 + meta(x, y, z)) * 9 + 7] >>> 4) * 20 + 5];
        return sameLiquid(n, x, y + 1, z) ? 1f : top;
    }

    private boolean solidCube(int x, int y, int z) {
        int id = id(x, y, z);
        if (id == 0 || clearId(id)) return false;
        int word = info[(id * 16 + meta(x, y, z)) * 9];
        return (word & 15) == CUBE && !holesAt(id, word) && !clearAt(id, word);
    }

    // the six faces: Beta's face number, normal, corners on the unit cube
    private static final int[] FACE = {0, 1, 2, 3, 4, 5};
    private static final float[][] N = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final int[][][] CORNER = {
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},   // bottom
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},   // top
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},   // north (-z)
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},   // south (+z)
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},   // west (-x)
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},   // east (+x)
    };

    /** pathtrace.comp's orientUV: bits from BlockCapture.orientation (1 swap, 2 flip u, 4 flip v). */
    static float[] orient(float[] uv, int o) {
        if (o == 0) return uv;
        float u = uv[0], v = uv[1];
        if ((o & 1) != 0) { float t = u; u = v; v = t; }
        if ((o & 2) != 0) u = 1 - u;
        if ((o & 4) != 0) v = 1 - v;
        return new float[]{u, v};
    }

    /** pathtrace.comp's faceUV: where a block-space point lands on the face's texture. */
    static float[] faceUV(int face, float x, float y, float z) {
        switch (face) {
            case 0: case 1: return new float[]{x, z};
            case 2: return new float[]{1 - x, 1 - y};
            case 3: return new float[]{x, 1 - y};
            case 4: return new float[]{z, 1 - y};
            default: return new float[]{1 - z, 1 - y};
        }
    }

    private int tint(int word, int face, int x, int z, int[] grass, int[] foliage) { return tint(word, face, x, z, grass, foliage, -1); }

    private int tint(int word, int face, int x, int z, int[] grass, int[] foliage, int entry) {
        int tintType = (word >> 4) & 15, tintMask = (word >> 8) & 63;
        if ((tintMask & (1 << face)) == 0) return 0;
        int rgb = switch (tintType) {
            case 5 -> entry >= 0 ? info[entry * 9 + 8] : 0xFFFFFF; // a modded block's own colour
            case 1 -> grass[x * 16 + z];
            case 2 -> foliage[x * 16 + z];
            case 3 -> 0x619961;
            case 4 -> 0x80A755;
            default -> 0xFFFFFF;
        };
        return (rgb & 0xFFFFFF) | 1 << 24;
    }

    /**
     * ids, meta: Beta's chunk arrays (index x << 11 | z << 7 | y; meta 4 bits per block). grass, foliage:
     * 0xRRGGBB per column (x * 16 + z). blockInfo, blockBoxes: BlockTable's tables.
     */
    ChunkMesher(short[] ids, byte[] meta, int[] grass, int[] foliage, int[] blockInfo, float[] blockBoxes, boolean fastLeaves) {
        this(ids, meta, grass, foliage, blockInfo, blockBoxes, fastLeaves, null, null);
    }

    ChunkMesher(short[] ids, byte[] meta, int[] grass, int[] foliage, int[] blockInfo, float[] blockBoxes, boolean fastLeaves,
                short[][] nIds, byte[][] nMeta) {
        this.nIds = nIds;
        this.nMeta = nMeta;
        this.fastLeaves = fastLeaves;
        this.ids = ids;
        this.meta = meta;
        this.info = blockInfo;
        this.boxes = blockBoxes;
        float[][] p = new float[4][3], uv = new float[4][2];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < 128; y++) {
                    int id = ids[x << 11 | z << 7 | y] & 0xFFFF;
                    if (id == 0) continue;
                    int m = meta(x, y, z);
                    int entry = id * 16 + m;
                    int word = info[entry * 9];
                    int shape = word & 15;
                    if (shape == CUBE) {
                        boolean clr = clearAt(id, word), see = !clr && holesAt(id, word);
                        Group g = clr ? clear : see ? cutout : opaque;
                        for (int f = 0; f < 6; f++) {
                            int nx = x + (int) N[f][0], ny = y + (int) N[f][1], nz = z + (int) N[f][2];
                            if (ny < 0) continue;
                            if (solidCube(nx, ny, nz)) continue;
                            if (clr && id(nx, ny, nz) == id) continue; // inside a run of ice (or glass like it): no face
                            // Two see-through blocks of the same kind touching (a mass of leaves): their two faces
                            // there lie in one plane, and a ray only ever counts the one it enters through. One
                            // two-sided square does for both, which halves what rays have to test inside a canopy.
                            int kind = KIND_FACE;
                            if (see && id(nx, ny, nz) == id && meta(nx, ny, nz) == m) {
                                if ((f & 1) == 0) continue; // the neighbour's square covers this side
                                kind = KIND_FACE2;
                            }
                            int tile = info[entry * 9 + 1 + f] & 0xFFFF, turn = info[entry * 9 + 1 + f] >>> 16 & 7;
                            if (id == 18 && fastLeaves) tile++; // Beta's solid leaf texture sits next to the see-through one
                            if (id == 2 && f >= 2 && (id(x, y + 1, z) == 78 || id(x, y + 1, z) == 80)) tile = 68; // snowy grass side
                            for (int c = 0; c < 4; c++) {
                                p[c][0] = x + CORNER[f][c][0]; p[c][1] = y + CORNER[f][c][1]; p[c][2] = z + CORNER[f][c][2];
                                uv[c] = orient(faceUV(f, CORNER[f][c][0], CORNER[f][c][1], CORNER[f][c][2]), turn);
                            }
                            int faceTint = tint(word, f, x, z, grass, foliage, entry);
                            // a grass side: the biome's grass colour and bit 25, for Fancy graphics' grass edge (vkpath.comp's texel)
                            if (id == 2 && f >= 2 && tile != 68 && faceTint == 0) faceTint = (grass[x * 16 + z] & 0xFFFFFF) | 1 << 25;
                            g.quad(p, uv, id, tile, kind, faceTint, N[f][0], N[f][1], N[f][2]);
                        }
                    } else if (shape == LIQUID) {
                        boolean water = id == 8 || id == 9;
                        Group g = water ? clear : opaque;
                        int b = (info[entry * 9 + 7] >>> 4) * 20;
                        float top = blockBoxes[b + 5];
                        boolean sameAbove = sameLiquid(id, x, y + 1, z);
                        if (sameAbove) top = 1f;
                        for (int f = 0; f < 6; f++) {
                            int nx = x + (int) N[f][0], ny = y + (int) N[f][1], nz = z + (int) N[f][2];
                            if (ny < 0) continue;
                            boolean sameLiquid = sameLiquid(id, nx, ny, nz);
                            // beside the same liquid at a lower level (flowing water steps down): only the strip above
                            // its surface, or a ray looks through the step into the water with no face to tint it
                            // (lighter seams along a stream, and a dark wall when looking out from under water)
                            float bottom = 0f;
                            if (sameLiquid && f >= 2) {
                                bottom = liquidTop(nx, ny, nz);
                                if (bottom >= top - 1e-4f) continue;
                            } else if (sameLiquid && !(f == 1 && top < 1f)) continue; // inside the lake: no face (a lowered top always shows)
                            if (solidCube(nx, ny, nz) && !(f == 1 && top < 1f)) continue;
                            int tile = info[entry * 9 + 1 + f] & 0xFFFF;
                            for (int c = 0; c < 4; c++) {
                                float cy = f == 1 ? top : f == 0 ? 0f : bottom + CORNER[f][c][1] * (top - bottom);
                                p[c][0] = x + CORNER[f][c][0]; p[c][1] = y + cy; p[c][2] = z + CORNER[f][c][2];
                                uv[c] = faceUV(f, CORNER[f][c][0], cy, CORNER[f][c][2]);
                            }
                            g.quad(p, uv, id, tile, KIND_FACE, tint(word, f, x, z, grass, foliage, entry), N[f][0], N[f][1], N[f][2]);
                        }
                    } else if (shape == CROSS) {
                        int tile = info[entry * 9 + 3] & 0xFFFF;
                        int t = tint(word, 2, x, z, grass, foliage, entry);
                        // two upright squares along the block's diagonals (pathtrace.comp's CROSS)
                        float[][][] diag = {{{0, 0, 0}, {1, 0, 1}, {1, 1, 1}, {0, 1, 0}}, {{0, 0, 1}, {1, 0, 0}, {1, 1, 0}, {0, 1, 1}}};
                        for (int k = 0; k < 2; k++) {
                            for (int c = 0; c < 4; c++) {
                                float lx = diag[k][c][0], ly = diag[k][c][1], lz = diag[k][c][2];
                                p[c][0] = x + lx; p[c][1] = y + ly; p[c][2] = z + lz;
                                uv[c][0] = k == 0 ? lx : 1 - lx;
                                uv[c][1] = 1 - ly;
                            }
                            cutout.quad(p, uv, id, tile, KIND_CROSS, t, 0, 1, 0); // lit as if facing up, like the OpenGL tracer
                        }
                    } else if (shape == BOX) {
                        int n = info[entry * 9 + 7] & 15, first = info[entry * 9 + 7] >>> 4;
                        boolean boxUvFlag = ((word >> 16) & 2) != 0;
                        // see-through boxes (a portal's thin slab, Aether portals) tint what's behind, as in the OpenGL
                        // tracer; they used to go in with the cut-out ones and came out as solid coloured glass
                        Group bg = clearAt(id, word) ? clear : cutout;
                        for (int k = 0; k < n; k++) {
                            int b = (first + k) * 20;
                            int aw = Math.round(blockBoxes[b + 3]);
                            int cond = aw & 15;
                            if (cond != 0) {
                                boolean w = id(x - 1, y, z) == id, e = id(x + 1, y, z) == id;
                                boolean ok = cond == 1 ? w : cond == 2 ? e : cond == 3 ? id(x, y, z - 1) == id : cond == 4 ? id(x, y, z + 1) == id
                                        : cond == 5 ? (w || e) : cond == 6 ? !(w || e) : armOk(cond, x, y, z, id);
                                if (!ok) continue;
                            }
                            float[] lo = {blockBoxes[b], blockBoxes[b + 1], blockBoxes[b + 2]}, hi = {blockBoxes[b + 4], blockBoxes[b + 5], blockBoxes[b + 6]};
                            int tileOverride = blockBoxes[b + 7] > 0.5f ? Math.round(blockBoxes[b + 7]) - 1 : -1;
                            float[] m0 = {blockBoxes[b + 8], blockBoxes[b + 9], blockBoxes[b + 10], blockBoxes[b + 11]};
                            float[] m1 = {blockBoxes[b + 12], blockBoxes[b + 13], blockBoxes[b + 14], blockBoxes[b + 15]};
                            float[] m2 = {blockBoxes[b + 16], blockBoxes[b + 17], blockBoxes[b + 18], blockBoxes[b + 19]};
                            boolean tilted = m0[0] != 0 || m0[1] != 0 || m0[2] != 0;
                            double[] inv = tilted ? invert(m0, m1, m2) : null;
                            boolean stick = (aw & 16) != 0 || boxUvFlag;
                            float ccx = (lo[0] + hi[0]) * 0.5f, ccz = (lo[2] + hi[2]) * 0.5f;
                            for (int f = 0; f < 6; f++) {
                                int tile = tileOverride >= 0 ? tileOverride : info[entry * 9 + 1 + f] & 0xFFFF;
                                int turn = tileOverride >= 0 ? 0 : info[entry * 9 + 1 + f] >>> 16 & 7;
                                for (int c = 0; c < 4; c++) {
                                    float lx = CORNER[f][c][0] == 0 ? lo[0] : hi[0];
                                    float ly = CORNER[f][c][1] == 0 ? lo[1] : hi[1];
                                    float lz = CORNER[f][c][2] == 0 ? lo[2] : hi[2];
                                    float hx = lx, hy = ly, hz = lz;
                                    if (stick) { hx = lx - ccx + 0.5f; hy = ly - lo[1]; hz = lz - ccz + 0.5f; }
                                    uv[c] = orient(faceUV(f, hx, hy, hz), turn);
                                    if (tilted) { // block space = M^-1 * local + pivot
                                        float wx = (float) (inv[0] * lx + inv[1] * ly + inv[2] * lz) + m0[3];
                                        float wy = (float) (inv[3] * lx + inv[4] * ly + inv[5] * lz) + m1[3];
                                        float wz = (float) (inv[6] * lx + inv[7] * ly + inv[8] * lz) + m2[3];
                                        lx = wx; ly = wy; lz = wz;
                                    }
                                    p[c][0] = x + lx; p[c][1] = y + ly; p[c][2] = z + lz;
                                }
                                float nx = N[f][0], ny = N[f][1], nz = N[f][2];
                                if (tilted) { // normal turned back with M's transpose (as the OpenGL tracer does)
                                    float tx = m0[0] * nx + m1[0] * ny + m2[0] * nz, ty = m0[1] * nx + m1[1] * ny + m2[1] * nz, tz = m0[2] * nx + m1[2] * ny + m2[2] * nz;
                                    float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                                    nx = tx / len; ny = ty / len; nz = tz / len;
                                }
                                bg.quad(p, uv, id, tile, KIND_FACE, tint(word, f, x, z, grass, foliage, entry), nx, ny, nz);
                            }
                        }
                    }
                }
    }

    /** Inverse of the 3 x 3 matrix with rows m0, m1, m2 (xyz), row-major. */
    private static double[] invert(float[] r0, float[] r1, float[] r2) {
        double a = r0[0], b = r0[1], c = r0[2], d = r1[0], e = r1[1], f = r1[2], g = r2[0], h = r2[1], i = r2[2];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-9) det = 1e-9;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }
}
