package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.block.Block;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Everything the shader needs to know about each block type, for all 256 IDs x 16
 * metadata values: what shape it is, which terrain.png tile each of its 6 faces uses,
 * whether those faces get the biome grass/leaf tint, and its box size if it isn't a
 * full cube. Built once from Beta's own Block classes, so modded or retextured blocks
 * follow automatically.
 *
 * Shapes the tracer understands:
 *   CUBE   full block
 *   CROSS  two diagonal planes (flowers, tall grass, saplings, sugar cane, crops, fire, cobweb)
 *   BOX    one or more boxes smaller than the block (M6: up to 12 per block, so stairs are
 *          two boxes, a fence is a post plus bars, a lever is a base plus a handle...)
 *   LIQUID water and lava, see-through with a lowered surface
 *
 * A box can carry a condition, checked by the shader against the neighbouring blocks, so
 * shapes that depend on their surroundings (fence bars, portal orientation) need no
 * per-block data on the GPU: e.g. "only if the block to the west is the same block".
 * A box can also use its own texture tile (the lever's cobblestone base).
 */
public final class BlockTable {
    public static final int AIR = 0, CUBE = 1, CROSS = 2, BOX = 3, LIQUID = 4, SKIP = 5;
    // tint types: which biome colour multiplies the tinted faces
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_SPRUCE = 3, TINT_BIRCH = 4, TINT_CONSTANT = 5;
    // flags
    public static final int FLAG_TRANSLUCENT = 1; // blend and keep going (water, ice)
    public static final int FLAG_BOX_UV = 2;      // texture follows the box, not the block (torches)
    public static final int FLAG_CUTOUT = 4;      // a modded full cube with see-through pixels (its leaves): Vulkan tests its texture

    private static final int ENTRIES = StationCompat.MAX_ID * 16; // 4096 IDs (StationAPI's modded blocks are 256 and up) x 16 metadata
    private static final int INTS = 9; // info, 6 face textures, box count + first box, a constant tint colour
    public static final int MAX_BOXES = 12;
    private static final int BED = 26;
    private static final boolean DEBUG_TEXTURES = Boolean.getBoolean("betapt.debugTextures");
    private static final boolean DEBUG_CAPTURE = Boolean.getBoolean("betapt.debugCapture");
    /** betapt.captureBlocks=false turns BlockCapture off (falls back to texture numbers and class-name shapes). */
    private static final boolean CAPTURE = !"false".equals(System.getProperty("betapt.captureBlocks"));
    private static BlockCapture.Result captured;
    private static final java.util.List<String> TURNED = new java.util.ArrayList<>(); // the current block's capture, for partsFor

    // box conditions (see class comment)
    public static final int IF_ALWAYS = 0, IF_SAME_WEST = 1, IF_SAME_EAST = 2, IF_SAME_NORTH = 3, IF_SAME_SOUTH = 4,
            IF_SAME_EAST_OR_WEST = 5, IF_NOT_SAME_EAST_OR_WEST = 6,
            // pipes and cables: an arm toward a neighbour they'd join (the same block, a modded block, a chest or furnace)
            IF_JOINS_WEST = 7, IF_JOINS_EAST = 8, IF_JOINS_NORTH = 9, IF_JOINS_SOUTH = 10, IF_JOINS_DOWN = 11, IF_JOINS_UP = 12;

    /**
     * One box: corners, when it applies, an optional texture tile, and optionally a tilt.
     *
     * A tilted box (wall torch, lever handle) is stored as a plain box in its own little
     * coordinate system plus the 3x3 matrix M and pivot that take a block-space point
     * there: local = M * (point - pivot). The shader moves the ray into that system, hits
     * the plain box, and turns the surface normal back. boxUv: the texture follows the
     * box (centred on a stick) instead of the block.
     */
    private record Part(float x0, float y0, float z0, float x1, float y1, float z1, int cond, int tile, float[] m, boolean boxUv) {
        Part(float x0, float y0, float z0, float x1, float y1, float z1, int cond, int tile) { this(x0, y0, z0, x1, y1, z1, cond, tile, null, false); }
        Part(float x0, float y0, float z0, float x1, float y1, float z1) { this(x0, y0, z0, x1, y1, z1, IF_ALWAYS, -1); }
        Part(float[] b) { this(b[0], b[1], b[2], b[3], b[4], b[5]); }
        boolean full() { return x0 == 0 && y0 == 0 && z0 == 0 && x1 == 1 && y1 == 1 && z1 == 1 && cond == IF_ALWAYS && m == null; }
    }

    /** A stick of half-width w and the given length along its own y axis, starting at 'start' below its pivot. */
    private static Part stick(float w, float start, float length, float[] m, int tile) {
        return new Part(-w, start, -w, w, start + length, w, IF_ALWAYS, tile, m, true);
    }

    /** Shear: x and z drift by sx, sz per block of height above the pivot (Beta's slanted wall torch). */
    private static float[] shear(float sx, float sz, float px, float py, float pz) {
        return new float[]{1, -sx, 0, px, 0, 1, 0, py, 0, -sz, 1, pz};
    }

    /** Rotation that points the box's y axis along direction h (lever handle). Rows of M are the local axes. */
    private static float[] pointAlong(double hx, double hy, double hz, float px, float py, float pz) {
        double l = Math.sqrt(hx * hx + hy * hy + hz * hz); hx /= l; hy /= l; hz /= l;
        double rx = Math.abs(hz) < 0.9 ? 0 : 1, rz = Math.abs(hz) < 0.9 ? 1 : 0; // reference axis not parallel to h
        double ax = hy * rz, ay = hz * rx - hx * rz, az = -hy * rx;               // a = h x ref
        double al = Math.sqrt(ax * ax + ay * ay + az * az); ax /= al; ay /= al; az /= al;
        double bx = ay * hz - az * hy, by = az * hx - ax * hz, bz = ax * hy - ay * hx; // b = a x h
        return new float[]{(float) ax, (float) ay, (float) az, px, (float) hx, (float) hy, (float) hz, py, (float) bx, (float) by, (float) bz, pz};
    }

    private int infoBuffer, boxBuffer;

    public int infoBuffer() { return infoBuffer; }
    public int boxBuffer() { return boxBuffer; }

    /** M8c: the same table on the CPU side, for the Vulkan tracer's mesher. */
    public int[] cpuInfo;
    public float[] cpuBoxes;

    public void build() {
        SingleBlockView view = new SingleBlockView();
        IntBuffer info = BufferUtils.createIntBuffer(ENTRIES * INTS);
        // boxes are stored one after another; each entry's info word 7 says where its run starts (see below)
        java.util.ArrayList<float[]> boxList = new java.util.ArrayList<>();
        int counted = 0, multi = 0, nBlocks = StationCompat.blockCount();
        for (int id = 0; id < StationCompat.MAX_ID; id++) {
            final int src = Orientations.original(id); // an extra ID (a turned IC2 machine) draws its block
            Block b = src < nBlocks ? Block.BY_ID[src] : null;
            for (int meta = 0; meta < 16; meta++) {
                int shape = AIR, tint = TINT_NONE, tintMask = 0, flags = 0, constTint = 0xFFFFFF;
                int[] tex = new int[6], raw = new int[6];
                java.util.List<Part> parts = java.util.List.of(new Part(0, 0, 0, 1, 1, 1));
                if (b != null && id != 0) {
                    counted++;
                    int render = b.getRenderType();
                    shape = switch (render) {
                        case 0, 13, 14, 15, 16, 17, 10, 7, 8, 9, 2, 5, 11, 12 -> CUBE;
                        case 1, 3, 6 -> CROSS;
                        case 4 -> LIQUID;
                        // a modded block drawn by its own renderer: its bounds as a box (better than leaving a hole)
                        default -> render < 0 ? SKIP : CUBE;
                    };
                    view.id = src;
                    view.meta = meta;
                    Orientations.apply(view, id);
                    // chests: the metadata is which way the chest looks, from its neighbours (ChestLooks)
                    int[][] chest = id == ChestLooks.CHEST ? ChestLooks.all() : null;
                    int mirrored = 0;
                    for (int side = 0; side < 6; side++) {
                        // Prefer the world-aware lookup: grass, furnaces, dispensers, chests and stairs
                        // only give the right face textures that way.
                        try {
                            int t = chest != null && meta < chest.length ? chest[meta][side] : b.getSprite(view, 0, 64, 0, side);
                            if (t < 0) mirrored |= 1 << side; // doors return a negative tile to mean "mirrored"
                            tex[side] = Math.abs(t);
                        } catch (Throwable t) {
                            try {
                                tex[side] = b.getSprite(side, meta);
                            } catch (Throwable t2) {
                                tex[side] = b.sprite;
                            }
                        }
                        // modded blocks: their own texture sheet's index -> the tile in our grid (StationCompat)
                        raw[side] = tex[side];
                        tex[side] = StationCompat.terrainTile(b, tex[side]) & 0xFFFF;
                        if ((mirrored >> side & 1) != 0) tex[side] |= 2 << 16; // Beta mirrors it: flip u (BlockCapture.orientation's bits)
                    }
                    // modded blocks: what Beta's own renderer actually draws (BlockCapture) beats the texture numbers.
                    // Beds too: Beta's bed renderer turns the top to point the pillow at the head and mirrors some sides,
                    // which the texture numbers don't say, so traced beds could lie the wrong way round.
                    BlockCapture.Result cap = null;
                    if ((id >= 97 || id == BED || (DEBUG_CAPTURE && id == 1)) && CAPTURE) {
                        cap = BlockCapture.capture(b, view);
                        if (DEBUG_CAPTURE && meta == 0) BlockCapture.log(id, meta, b, cap);
                        // tile 0 (grass top) from a modded block is a renderer drawing from its own texture, not the atlas: skip
                        // (bits 16-18: how the texture is turned on that face, see BlockCapture.orientation)
                        if (cap != null) for (int side = 0; side < 6; side++)
                            if (cap.faceTile()[side] > 0) tex[side] = cap.faceTile()[side] & 0xFFFF | (cap.faceOrient()[side] & 7) << 16;
                        if (cap != null && meta == 0) for (int side = 0; side < 6; side++)
                            if (cap.faceTile()[side] > 0 && cap.faceOrient()[side] != 0) { TURNED.add(id + ":" + side + "=" + cap.faceOrient()[side]); }
                    }
                    captured = cap;
                    // drawn only by a block entity renderer (BuildCraft's engines and chutes: model boxes with their own
                    // texture files): no shape here; EntityCapture records those boxes each frame, like a mob's
                    if (id >= 97 && CAPTURE && cap == null && "no vertices".equals(BlockCapture.lastFailure) && rendererDrawn(view)) {
                        shape = SKIP;
                        BER_IDS.add(id);
                    }
                    if (DEBUG_TEXTURES && id >= 97 && meta == 0) {
                        String file = null;
                        try { file = String.valueOf(b.getClass().getMethod("getTextureFile").invoke(b)); } catch (Throwable ignored) {}
                        BetaPT.LOGGER.info("texdebug {} {} render {} file {} raw {} mapped {} meta-tex {}", id, b.getClass().getName(), render, file,
                                java.util.Arrays.toString(raw), java.util.Arrays.toString(tex), b.getSprite(2, 0));
                    }
                    if (shape == CUBE) {
                        parts = partsFor(b, src, meta, render, view);
                        if (parts.size() != 1 || !parts.get(0).full()) shape = BOX;
                        if (parts.size() > 1) multi++;
                    }
                    if (shape == LIQUID) {
                        int m = meta >= 8 ? 0 : meta;
                        parts = java.util.List.of(new Part(0, 0, 0, 1, 1f - (m + 1) / 9f, 1));
                        if (id == 8 || id == 9) flags |= FLAG_TRANSLUCENT;
                    }
                    if (id == 79) flags |= FLAG_TRANSLUCENT; // ice
                    if (id >= 97 && (shape == CUBE || shape == BOX)) { // a modded see-through block Beta blends (Aether's quicksoil glass, aerogel)
                        // BuildCraft's pipes draw their second pass in layer 1, but a pipe is a frame with a clear middle: tinting it
                        // as glass (Vulkan's blended group) filled the middle with a dark film that hid the items inside
                        boolean pipe = b.getClass().getName().contains("Pipe");
                        try { if (b.getRenderLayer() == 1 && !pipe) flags |= FLAG_TRANSLUCENT; } catch (Throwable ignored) {}
                        // not opaque but not blended either: holes in the texture, like vanilla leaves and glass
                        // (the OpenGL tracer tests every texture pixel anyway; Vulkan only tests blocks marked so)
                        if (shape == CUBE && (flags & FLAG_TRANSLUCENT) == 0
                                && src < net.minecraft.block.Block.IS_SOLID.length && !net.minecraft.block.Block.IS_SOLID[src]) flags |= FLAG_CUTOUT;
                    }
                    if (render == 2) flags |= FLAG_BOX_UV;   // torches: texture centred on the stick

                    if (id == 2) { tint = TINT_GRASS; tintMask = 1 << 1; }                      // grass block, top only
                    if (id == 18) {                                                           // leaves
                        tint = (meta & 1) == 1 ? TINT_SPRUCE : (meta & 2) == 2 ? TINT_BIRCH : TINT_FOLIAGE;
                        tintMask = 63;
                        // Always the fancy (see-through) leaf texture, whatever the Graphics setting:
                        // tile 52 for oak and birch, 132 for spruce. Fast graphics' solid leaves would
                        // block all the dappled light. (Fast is tile 53/133.)
                        java.util.Arrays.fill(tex, (meta & 3) == 1 ? 132 : 52);
                    }
                    if (id == 31 && meta != 0) { tint = TINT_GRASS; tintMask = 63; }          // tall grass and fern
                    if (id >= 97 && tint == TINT_NONE) { // a modded block Beta draws in a colour (Aether clouds, coloured lamps)
                        int c = 0xFFFFFF;
                        
                        if ((c & 0xFFFFFF) == 0xFFFFFF) try { c = b.getColorTint(view, 0, 64, 0); } catch (Throwable ignored) {}
                        if ((c & 0xFFFFFF) != 0xFFFFFF) { tint = TINT_CONSTANT; tintMask = 63; constTint = c & 0xFFFFFF; }
                    }
                }
                // a modded cube Beta calls solid whose texture still has see-through pixels (BuildCraft's render block):
                // Beta's alpha test lets you see through them, so Vulkan has to test its pixels too
                if (id >= 97 && shape == CUBE && (flags & (FLAG_TRANSLUCENT | FLAG_CUTOUT)) == 0 && hasHoles(tex)) flags |= FLAG_CUTOUT;
                if (b != null && id != 0) Orientations.restore(view, id);
                if (shape == AIR || shape == SKIP) parts = java.util.List.of();
                if (parts.size() > MAX_BOXES) parts = parts.subList(0, MAX_BOXES);
                info.put(shape | tint << 4 | tintMask << 8 | flags << 16);
                info.put(tex);
                info.put(parts.size() | boxList.size() << 4); // box count (up to 12), and the first box's index
                info.put(constTint);
                for (Part p : parts) {
                    // min.w = condition (+16 if the texture follows the box), max.w = texture tile + 1 (0 = the
                    // block's own faces), then the tilt matrix rows with the pivot in .w (all zero = no tilt)
                    float[] box = new float[20];
                    System.arraycopy(new float[]{p.x0, p.y0, p.z0, p.cond + (p.boxUv ? 16 : 0), p.x1, p.y1, p.z1, p.tile + 1}, 0, box, 0, 8);
                    if (p.m != null) System.arraycopy(p.m, 0, box, 8, 12);
                    boxList.add(box);
                }
            }
        }
        FloatBuffer boxes = BufferUtils.createFloatBuffer(Math.max(1, boxList.size()) * 20);
        for (float[] box : boxList) boxes.put(box);
        if (boxList.isEmpty()) boxes.put(new float[20]);
        info.flip();
        boxes.flip();
        cpuInfo = new int[info.remaining()];
        info.get(cpuInfo);
        info.rewind();
        cpuBoxes = new float[boxes.remaining()];
        boxes.get(cpuBoxes);
        boxes.rewind();
        infoBuffer = upload(info, null);
        boxBuffer = upload(null, boxes);
        BetaPT.LOGGER.info("Block table built for {} block types ({} multi-box shapes, {} boxes)", counted / 16, multi, boxList.size());
        if (!TURNED.isEmpty()) BetaPT.LOGGER.info("Block table: {} faces drawn with their texture turned (id:face=turn): {}", TURNED.size(),
                TURNED.size() > 40 ? TURNED.subList(0, 40) + " ..." : TURNED);
        TURNED.clear();
    }

    /** True if any of these atlas tiles has a pixel Beta's alpha test drops (alpha 0.1 or less). */
    private static boolean hasHoles(int[] tiles) {
        int[] atlas = BlockPalette.ATLAS;
        if (atlas == null || BlockPalette.ATLAS_W < 16) return false;
        int ts = BlockPalette.ATLAS_W / 16;
        for (int t : tiles) {
            int x0 = (t & 15) * ts, y0 = (t >> 4) * ts;
            if (y0 + ts > BlockPalette.ATLAS_H) continue;
            for (int y = 0; y < ts; y++)
                for (int x = 0; x < ts; x++)
                    if ((atlas[(y0 + y) * BlockPalette.ATLAS_W + x0 + x] >>> 24) <= 25) return true;
        }
        return false;
    }

    /** The boxes that make up a block's shape. Multi-part shapes are built here; single boxes come from boundsFor. */
    private static java.util.List<Part> partsFor(Block b, int id, int meta, int render, SingleBlockView view) {
        java.util.List<Part> p = new java.util.ArrayList<>();
        final float H = 0.5f;
        switch (render) {
            case 2 -> { // torch: on a wall Beta slants it, foot against the wall, leaning out 0.4 blocks per block of height
                float w = 1 / 16f, len = 10 / 16f, s = 0.4f;
                switch (meta & 7) {
                    case 1 -> p.add(stick(w, 0, len, shear(s, 0, 0, 0.2f, 0.5f), -1));
                    case 2 -> p.add(stick(w, 0, len, shear(-s, 0, 1, 0.2f, 0.5f), -1));
                    case 3 -> p.add(stick(w, 0, len, shear(0, s, 0.5f, 0.2f, 0), -1));
                    case 4 -> p.add(stick(w, 0, len, shear(0, -s, 0.5f, 0.2f, 1), -1));
                    default -> p.add(stick(w, 0, len, shear(0, 0, 0.5f, 0, 0.5f), -1));
                }
                return p;
            }
            case 10 -> { // stairs: a half-height slab plus a full-height back half (StairsBlock, Beta has no upside-down stairs)
                switch (meta & 3) {
                    case 0 -> { p.add(new Part(0, 0, 0, H, H, 1)); p.add(new Part(H, 0, 0, 1, 1, 1)); }
                    case 1 -> { p.add(new Part(0, 0, 0, H, 1, 1)); p.add(new Part(H, 0, 0, 1, H, 1)); }
                    case 2 -> { p.add(new Part(0, 0, 0, 1, H, H)); p.add(new Part(0, 0, H, 1, 1, 1)); }
                    default -> { p.add(new Part(0, 0, 0, 1, 1, H)); p.add(new Part(0, 0, H, 1, H, 1)); }
                }
                return p;
            }
            case 11 -> { // fence: post, plus two bars toward each neighbouring fence (BlockRenderer.renderFence)
                float a = 6 / 16f, c = 10 / 16f, b0 = 7 / 16f, b1 = 9 / 16f;
                p.add(new Part(a, 0, a, c, 1, c));
                float[][] bars = {{12 / 16f, 15 / 16f}, {6 / 16f, 9 / 16f}};
                for (float[] y : bars) {
                    p.add(new Part(0, y[0], b0, a, y[1], b1, IF_SAME_WEST, -1));
                    p.add(new Part(c, y[0], b0, 1, y[1], b1, IF_SAME_EAST, -1));
                    p.add(new Part(b0, y[0], 0, b1, y[1], a, IF_SAME_NORTH, -1));
                    p.add(new Part(b0, y[0], c, b1, y[1], 1, IF_SAME_SOUTH, -1));
                }
                return p;
            }
            case 12 -> { // lever: cobblestone base plate plus a handle tilted 40 degrees; flipping it swings the handle over
                int m = meta & 7;
                boolean on = (meta & 8) != 0;
                float h = 3 / 16f;                                   // base thickness
                double sin = Math.sin(Math.toRadians(40)), cos = Math.cos(Math.toRadians(40));
                double up = on ? -cos : cos;                          // wall levers: handle up when off, down when on
                double lean = on ? sin : -sin;                        // floor levers: lean one way or the other
                float w = 1 / 16f, len = 0.55f;
                switch (m) {
                    case 1 -> { p.add(new Part(0, 0.3125f, 0.25f, h, 0.6875f, 0.75f, IF_ALWAYS, 16)); p.add(stick(w, -0.06f, len, pointAlong(sin, up, 0, h, 0.5f, 0.5f), -1)); }
                    case 2 -> { p.add(new Part(1 - h, 0.3125f, 0.25f, 1, 0.6875f, 0.75f, IF_ALWAYS, 16)); p.add(stick(w, -0.06f, len, pointAlong(-sin, up, 0, 1 - h, 0.5f, 0.5f), -1)); }
                    case 3 -> { p.add(new Part(0.25f, 0.3125f, 0, 0.75f, 0.6875f, h, IF_ALWAYS, 16)); p.add(stick(w, -0.06f, len, pointAlong(0, up, sin, 0.5f, 0.5f, h), -1)); }
                    case 4 -> { p.add(new Part(0.25f, 0.3125f, 1 - h, 0.75f, 0.6875f, 1, IF_ALWAYS, 16)); p.add(stick(w, -0.06f, len, pointAlong(0, up, -sin, 0.5f, 0.5f, 1 - h), -1)); }
                    case 6 -> { p.add(new Part(0.3125f, 0, 0.25f, 0.6875f, h, 0.75f, IF_ALWAYS, 16)); p.add(stick(w, -0.06f, len, pointAlong(lean, cos, 0, 0.5f, h, 0.5f), -1)); }
                    default -> { p.add(new Part(0.25f, 0, 0.3125f, 0.75f, h, 0.6875f, IF_ALWAYS, 16)); p.add(stick(w, -0.06f, len, pointAlong(0, cos, lean, 0.5f, h, 0.5f), -1)); }
                }
                return p;
            }
            case 17 -> { // piston head: the plate (Beta's own bounds) plus the rod behind it
                float[] plate = beta(b, meta, view);
                p.add(new Part(plate));
                float r0 = 6 / 16f, r1 = 10 / 16f;
                switch (meta & 7) {
                    // the rod uses the piston's side texture (tile 108), like Beta draws it
                    case 0 -> p.add(new Part(r0, 0.25f, r0, r1, 1, r1, IF_ALWAYS, 108));
                    case 1 -> p.add(new Part(r0, 0, r0, r1, 0.75f, r1, IF_ALWAYS, 108));
                    case 2 -> p.add(new Part(r0, r0, 0.25f, r1, r1, 1, IF_ALWAYS, 108));
                    case 3 -> p.add(new Part(r0, r0, 0, r1, r1, 0.75f, IF_ALWAYS, 108));
                    case 4 -> p.add(new Part(0.25f, r0, r0, 1, r1, r1, IF_ALWAYS, 108));
                    default -> p.add(new Part(0, r0, r0, 0.75f, r1, r1, IF_ALWAYS, 108));
                }
                return p;
            }
            default -> {}
        }
        if (id >= 97 && (render > 17 || render == 0)) { // a modded block with its own renderer or a StationAPI model
            String cn = b.getClass().getSimpleName();
            float h = 0;
            if (cn.contains("Pipe") || cn.equals("BlockFrame") || cn.equals("FrameBlock")) h = 0.25f;        // BuildCraft pipes and quarry frames: 4/16 to 12/16
            else if (cn.contains("Cable")) h = cableHalfWidth(b, meta);            // IC2 cables: thickness by type
            if (h > 0) {
                float lo = 0.5f - h, hi = 0.5f + h;
                // BuildCraft's diamond pipes (and Logistics Pipes') have a texture per direction, coloured by where the filters
                // send things; each arm takes the one for its direction (down, up, north, south, west, east)
                int[] dt = pipeSideTiles(b);
                p.add(new Part(lo, lo, lo, hi, hi, hi));
                p.add(new Part(0, lo, lo, lo, hi, hi, IF_JOINS_WEST, dt != null ? dt[4] : -1));
                p.add(new Part(hi, lo, lo, 1, hi, hi, IF_JOINS_EAST, dt != null ? dt[5] : -1));
                p.add(new Part(lo, lo, 0, hi, hi, lo, IF_JOINS_NORTH, dt != null ? dt[2] : -1));
                p.add(new Part(lo, lo, hi, hi, hi, 1, IF_JOINS_SOUTH, dt != null ? dt[3] : -1));
                p.add(new Part(lo, 0, lo, hi, lo, hi, IF_JOINS_DOWN, dt != null ? dt[0] : -1));
                p.add(new Part(lo, hi, lo, hi, 1, hi, IF_JOINS_UP, dt != null ? dt[1] : -1));
                return p;
            }
            // the boxes Beta's renderer drew (opposite faces paired up), when there are a few and they aren't one full cube
            java.util.List<float[]> solid = new java.util.ArrayList<>();
            java.util.List<float[]> torches = captured != null ? torches(captured.boxes()) : java.util.List.of();
            if (captured != null) for (float[] q : captured.boxes()) if (!billboard(q) && !torches.contains(q)) solid.add(q);
            // torches drawn on the block (RedPower gates): Beta draws each as crossed full-width pictures; trace the stick
            for (int k = 0; k + 1 < torches.size(); k += 2) {
                float[] tx = torches.get(k), tz = torches.get(k + 1);
                float cx = (tx[0] + tx[3]) / 2, cz = (tz[2] + tz[5]) / 2, y0 = tx[1], top = tx[1] + 0.625f * (tx[4] - tx[1]);
                int tl = tx.length > 6 ? (int) tx[6] : -1;
                p.add(new Part(clamp01(cx - 1 / 16f), clamp01(y0), clamp01(cz - 1 / 16f), clamp01(cx + 1 / 16f), clamp01(top), clamp01(cz + 1 / 16f),
                        IF_ALWAYS, tl > 0 ? tl : -1, null, true));
            }
            if (!solid.isEmpty() || !torches.isEmpty()) {
                java.util.List<float[]> bx = solid;
                if (bx.size() > MAX_BOXES) { // too many parts: their outline instead
                    float[] u = {1, 1, 1, 0, 0, 0};
                    for (float[] q : bx) for (int i = 0; i < 3; i++) { u[i] = Math.min(u[i], q[i]); u[i + 3] = Math.max(u[i + 3], q[i + 3]); }
                    bx = java.util.List.of(u);
                }
                for (float[] q : bx) p.add(new Part(clamp01(q[0]), clamp01(q[1]), clamp01(q[2]), clamp01(q[3]), clamp01(q[4]), clamp01(q[5])));
                return p;
            }
            if (cn.contains("Logic")) { p.add(new Part(0, 0, 0, 1, 2 / 16f, 1)); return p; } // RedPower gates: a flat plate
            if (cn.equals("BlockEngine") || cn.endsWith("EngineBlock")) {
                // BuildCraft engines are drawn by a model with its own texture files, nothing a block capture can see:
                // base plate, trunk and piston plate in the engine's material (planks, cobblestone, iron)
                int mat = cn.startsWith("Stirling") ? 16 : cn.startsWith("Combustion") || cn.startsWith("Creative") ? 22 : cn.startsWith("Redstone") ? 4
                        : meta == 1 ? 16 : meta == 2 ? 22 : 4;
                p.add(new Part(0, 0, 0, 1, 0.25f, 1, IF_ALWAYS, mat));
                p.add(new Part(0.3125f, 0.25f, 0.3125f, 0.6875f, 1, 0.6875f, IF_ALWAYS, 1));
                p.add(new Part(0.0625f, 0.5f, 0.0625f, 0.9375f, 0.75f, 0.9375f, IF_ALWAYS, mat));
                return p;
            }
        }
        if (id == 90 || (id >= 97 && b.getClass().getSimpleName().contains("Portal"))) {
            // nether portal (and modded portals like the Aether's): a thin pane, turned to match the frame it sits in (NetherPortalBlock)
            p.add(new Part(0, 0, 0.375f, 1, 1, 0.625f, IF_SAME_EAST_OR_WEST, -1));
            p.add(new Part(0.375f, 0, 0, 0.625f, 1, 1, IF_NOT_SAME_EAST_OR_WEST, -1));
            return p;
        }
        p.add(new Part(boundsFor(b, id, meta, render, view)));
        return p;
    }

    /** Blocks whose look comes from a block entity renderer (traced through EntityCapture, not the block table). */
    public static final java.util.Set<Integer> BER_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean rendererDrawn(SingleBlockView view) {
        try {
            net.minecraft.block.entity.BlockEntity be = view.getBlockEntity(0, 64, 0);
            return be != null && net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher.INSTANCE.hasRenderer(be);
        } catch (Throwable t) { return false; }
    }

    /** A BuildCraft pipe's texture tile for each direction (down, up, north, south, west, east) when they differ, else null. */
    private static int[] pipeSideTiles(net.minecraft.block.Block b) {
        try {
            Class<?> dir = Class.forName("net.modificationstation.stationapi.api.util.math.Direction");
            Class<?> be = Class.forName("net.danygames2014.buildcraft.block.entity.pipe.PipeBlockEntity");
            Class<?> conn = Class.forName("net.danygames2014.buildcraft.block.entity.pipe.PipeConnectionType");
            java.lang.reflect.Method m = b.getClass().getMethod("getTextureIdentifierForSide", be, dir, conn);
            @SuppressWarnings({"unchecked", "rawtypes"}) Object normal = Enum.valueOf((Class) conn, "NORMAL");
            int[] out = new int[6];
            boolean differ = false;
            String[] names = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
            for (int i = 0; i < 6; i++) {
                @SuppressWarnings({"unchecked", "rawtypes"}) Object d = Enum.valueOf((Class) dir, names[i]);
                out[i] = StationCompat.tileOfName(m.invoke(b, null, d, normal));
                if (out[i] < 0) return null;
                if (out[i] != out[0]) differ = true;
            }
            return differ ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }

    /**
     * Beta draws a torch as four full-width pictures around its stick: paired up they're one thin slab along x and
     * one along z, crossing at the stick, the same height. Returns those slabs in pairs (x slab, then its z slab).
     */
    private static java.util.List<float[]> torches(java.util.List<float[]> boxes) {
        java.util.List<float[]> out = new java.util.ArrayList<>();
        for (float[] a : boxes) {
            if (!(a[3] - a[0] < 0.2f && a[5] - a[2] > 0.99f)) continue; // thin in x, across the block in z
            for (float[] b : boxes) {
                if (!(b[5] - b[2] < 0.2f && b[3] - b[0] > 0.99f)) continue; // thin in z, across in x
                if (Math.abs(a[1] - b[1]) > 0.01f || Math.abs(a[4] - b[4]) > 0.01f || out.contains(b)) continue;
                out.add(a); out.add(b);
                break;
            }
        }
        return out;
    }

    /**
     * Torch-like renderers draw flat pictures through the block (crossed planes with see-through pixels); paired up
     * they look like thin slabs spanning the block. Such a slab: thin along x or z without touching the block's
     * side, and running the block's full width the other way. Those are dropped; real plates and doors stay.
     */
    private static boolean billboard(float[] b) {
        for (int thin = 0; thin <= 2; thin += 2) {
            int other = 2 - thin;
            if (b[thin + 3] - b[thin] < 0.2f && b[thin] > 0.01f && b[thin + 3] < 0.99f && b[other + 3] - b[other] > 0.99f) return true;
        }
        return false;
    }

    /** Half the width of an IC2 cable (BlockCable.getCableThickness when it has one), else 3/16. */
    private static float cableHalfWidth(Block b, int meta) {
        for (java.lang.reflect.Method m : b.getClass().getMethods())
            if (m.getName().toLowerCase(java.util.Locale.ROOT).contains("thickness") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class)
                try {
                    Object r = m.invoke(java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : b, meta);
                    if (r instanceof Number n && n.floatValue() > 0 && n.floatValue() <= 1) return n.floatValue() / 2;
                } catch (Throwable ignored) {}
        return 3 / 16f;
    }

    /**
     * Ask the block itself (Block.updateBoundingBox) what its box is for this metadata.
     * That covers buttons, pressure plates, trapdoors, cake, pistons and anything else whose
     * size depends on its state. The block's shared bounds are put back afterwards.
     */
    private static float[] beta(Block b, int meta, SingleBlockView view) {
        double[] saved = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        try {
            view.meta = meta;
            b.updateShape(view, 0, 64, 0);
            return new float[]{(float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ};
        } catch (Throwable t) {
            return new float[]{(float) saved[0], (float) saved[1], (float) saved[2], (float) saved[3], (float) saved[4], (float) saved[5]};
        } finally {
            b.setShape((float) saved[0], (float) saved[1], (float) saved[2], (float) saved[3], (float) saved[4], (float) saved[5]);
        }
    }

    /** Box size for blocks that aren't full cubes. Most come from Beta's own bounds; a few depend on metadata. */
    private static float[] boundsFor(Block b, int id, int meta, int render, SingleBlockView view) {
        switch (id) {
            case 78: { float h = 2 * (1 + (meta & 7)) / 16f; return new float[]{0, 0, 0, 1, h, 1}; } // snow layer
            case 44: return new float[]{0, 0, 0, 1, 0.5f, 1};                                       // slab
            case 60: return new float[]{0, 0, 0, 1, 15 / 16f, 1};                                   // farmland
            default: break;
        }
        switch (render) {
            case 5: case 9: return new float[]{0, 0, 0, 1, 1 / 16f, 1};                     // redstone wire, rails
            case 13: return new float[]{1 / 16f, 0, 1 / 16f, 15 / 16f, 1, 15 / 16f};        // cactus
            case 14: return new float[]{0, 0, 0, 1, 9 / 16f, 1};                            // bed
            case 15: return new float[]{0, 0, 0, 1, 2 / 16f, 1};                            // repeater
            case 7: {                                                                        // door, same rule as DoorBlock.rotate
                float f = 3 / 16f;
                int r = (meta & 4) == 0 ? (meta - 1) & 3 : meta & 3;
                return switch (r) {
                    case 0 -> new float[]{0, 0, 0, 1, 1, f};
                    case 1 -> new float[]{1 - f, 0, 0, 1, 1, 1};
                    case 2 -> new float[]{0, 0, 1 - f, 1, 1, 1};
                    default -> new float[]{0, 0, 0, f, 1, 1};
                };
            }
            case 8: {                                                                        // ladder, same rule as LadderBlock
                float f = 2 / 16f;
                return switch (meta) {
                    case 2 -> new float[]{0, 0, 1 - f, 1, 1, 1};
                    case 3 -> new float[]{0, 0, 0, 1, 1, f};
                    case 4 -> new float[]{1 - f, 0, 0, 1, 1, 1};
                    case 5 -> new float[]{0, 0, 0, f, 1, 1};
                    default -> new float[]{0, 0, 0, 1, 1, 1};
                };
            }
            default: break;
        }
        return beta(b, meta, view);
    }

    private static int upload(IntBuffer ints, FloatBuffer floats) {
        int buf = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buf);
        if (ints != null) GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, ints, GL15.GL_STATIC_DRAW);
        else GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, floats, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return buf;
    }
}
