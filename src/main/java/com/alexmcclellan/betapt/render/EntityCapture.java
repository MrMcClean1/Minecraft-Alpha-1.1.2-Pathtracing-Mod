package com.alexmcclellan.betapt.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * M6: mobs in the path-traced world.
 *
 * Every Beta mob is a handful of boxes (ModelPart): head, body, legs. Each frame, before the
 * path tracer runs, Beta's entity pass is run once "dry" (nothing drawn to the screen) and
 * ModelPartMixin reports each box as it would be drawn, together with OpenGL's current
 * transform, which already has the walk animation, body turn, creeper swell and so on baked
 * in. We turn that into a box floating in the world (centre, axes, size) plus where its skin
 * sits on the mob's texture, and the path tracer draws, lights and shadows those boxes like
 * the rest of the world. Beta's own drawing of the boxes is then switched off.
 *
 * Boxes are grouped per entity with a bounding box around each group, so a ray only looks
 * at a mob's parts if it passes near that mob at all.
 */
public final class EntityCapture {
    private EntityCapture() {}

    public static boolean active;               // true only during the dry capture pass
    public static boolean suppressModels;       // true during Beta's real entity pass: boxes are path traced instead
    private static final int MAX_ENTITIES = 256, MAX_BOXES = 4096;
    private static final double MAX_DISTANCE = 160;

    // this frame's camera, set by PathTracer when it reads Beta's matrices
    private static final double[] viewRows = new double[9];  // rows of the view rotation R
    private static final double[] viewT = new double[3];
    private static double camX, camY, camZ;                   // camera entity position the matrices are relative to

    // collected boxes: centre (world), inverse axes (9), half size (3), albedo (3),
    // skin layer, texture offset u/v, mirror flag, box size in texels (packed)
    private static final double[][] boxes = new double[MAX_BOXES][];
    private static int boxCount;
    private static final int[] entFirst = new int[MAX_ENTITIES], entCount = new int[MAX_ENTITIES];
    private static int entityCount;
    private static boolean inEntity;
    // M8d: motion. Each mob is matched to itself last frame (by object identity; the hand is key -1),
    // and how far the middle of its boxes moved goes to the denoiser and FSR.
    private static final int[] entKey = new int[MAX_ENTITIES];
    private static int nextKey;
    private static java.util.HashMap<Integer, double[]> lastCentres = new java.util.HashMap<>();
    private static final float[] entMotion = new float[MAX_ENTITIES * 4];
    /** Entities that are block entities (engines): fixed in place, so they get no motion (see end()). */
    private static final boolean[] entBer = new boolean[MAX_ENTITIES];
    private static boolean nextBer;
    private static int motionCount, motionBuffer;
    private static final int[] lastBoxEntity = new int[MAX_BOXES];

    // finished list from the previous frame, ready to upload
    private static final double[][] readyBoxes = new double[MAX_BOXES][];
    private static int readyBoxCount, readyEntityCount;
    private static final int[] readyFirst = new int[MAX_ENTITIES], readyCount = new int[MAX_ENTITIES];

    private static final FloatBuffer mvBuf = BufferUtils.createFloatBuffer(16);
    private static final Map<Integer, float[]> albedoCache = new HashMap<>();
    private static int boxBuffer, entityBuffer;

    /** Called from Camera.fromGl with the view matrix it just read. */
    static void setView(float[] m, double entX, double entY, double entZ) {
        viewRows[0] = m[0]; viewRows[1] = m[4]; viewRows[2] = m[8];
        viewRows[3] = m[1]; viewRows[4] = m[5]; viewRows[5] = m[9];
        viewRows[6] = m[2]; viewRows[7] = m[6]; viewRows[8] = m[10];
        viewT[0] = m[12]; viewT[1] = m[13]; viewT[2] = m[14];
        camX = entX; camY = entY; camZ = entZ;
    }

    /** Start of Beta's entity pass. */
    public static void begin() {
        boxCount = 0;
        entityCount = 0;
        active = true;
    }

    /**
     * Blocks a block entity renderer draws (BuildCraft's engines and chutes, BlockTable.BER_IDS): their renderers
     * run here, inside the dry pass, so their model boxes are recorded like a mob's, with the engine's moving
     * piston and colour. Beta only draws block entities from chunks it has built, which it doesn't while the
     * path tracer runs, so this goes through the world's whole list (near the camera).
     */
    private static final boolean DEBUG_BER = Boolean.getBoolean("betapt.debugBer");
    private static boolean berDebug;
    private static int berLogged;

    public static void renderBlockEntities(net.minecraft.client.Minecraft mc, float tickDelta) {
        if (!active || mc.world == null || com.alexmcclellan.betapt.render.BlockTable.BER_IDS.isEmpty()) return;
        net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher d = net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher.INSTANCE;
        net.minecraft.entity.mob.player.PlayerEntity cam = mc.player;
        d.prepare(mc.world, mc.textureManager, mc.textRenderer, cam, tickDelta);
        double ox = cam.prevX + (cam.x - cam.prevX) * tickDelta, oy = cam.prevY + (cam.y - cam.prevY) * tickDelta,
                oz = cam.prevZ + (cam.z - cam.prevZ) * tickDelta;
        for (Object o : new java.util.ArrayList<>(mc.world.blockEntities)) {
            if (!(o instanceof net.minecraft.block.entity.BlockEntity be)) continue;
            double dx = be.x - ox, dy = be.y - oy, dz = be.z - oz;
            if (dx * dx + dy * dy + dz * dz > 96 * 96) continue;
            if (!com.alexmcclellan.betapt.render.BlockTable.BER_IDS.contains(mc.world.getBlock(be.x, be.y, be.z))) continue;
            nextKey = System.identityHashCode(be);
            entityBrightness = 1f;
            nextBer = true;
            beginEntity(dx + 0.5, dy + 0.5, dz + 0.5);
            berDebug = DEBUG_BER && be.getClass().getSimpleName().startsWith("Redstone") && (berLogged++ % 60) == 0;
            if (berDebug) com.alexmcclellan.betapt.BetaPT.LOGGER.info("ber {} at {} {} {}", be.getClass().getSimpleName(), be.x, be.y, be.z);
            try { d.render(be, dx, dy, dz, tickDelta); } catch (Throwable ignored) {}
            berDebug = false;
            endEntity();
        }
    }

    /** End of Beta's entity pass: hand the list to the next frame. */
    public static void end() {
        active = false;
        java.util.HashMap<Integer, double[]> centres = new java.util.HashMap<>();
        for (int e = 0; e < entityCount; e++) {
            double cx = 0, cy = 0, cz = 0;
            int n = 0;
            for (int k = entFirst[e]; k < entFirst[e] + entCount[e]; k++) {
                if (boxes[k][18] < -1.5) continue; // flash overlays repeat a box
                cx += boxes[k][0]; cy += boxes[k][1]; cz += boxes[k][2]; n++;
            }
            if (n > 0) { cx /= n; cy /= n; cz /= n; }
            double[] was = entKey[e] != 0 ? lastCentres.get(entKey[e]) : null;
            // An engine stays where it is; only its piston moves. The boxes' average position follows the piston, and as the
            // whole engine's motion it made the still parts reproject from the wrong place and shimmer. No motion for them.
            boolean ok = !entBer[e] && was != null && Math.abs(was[0] - cx) + Math.abs(was[1] - cy) + Math.abs(was[2] - cz) < 4; // not a teleport
            entMotion[e * 4] = ok ? (float) (cx - was[0]) : 0f;
            entMotion[e * 4 + 1] = ok ? (float) (cy - was[1]) : 0f;
            entMotion[e * 4 + 2] = ok ? (float) (cz - was[2]) : 0f;
            if (entKey[e] != 0) centres.put(entKey[e], new double[]{cx, cy, cz});
        }
        lastCentres = centres;
        java.util.HashMap<Integer, Integer> per = new java.util.HashMap<>();
        for (int e = 0; e < entityCount; e++) if (entKey[e] != 0) per.merge(entKey[e], entCount[e], Integer::sum);
        lastPerEntity = per;
        motionCount = entityCount;
        for (int i = 0; i < boxCount; i++) readyBoxes[i] = boxes[i];
        readyBoxCount = boxCount;
        System.arraycopy(entFirst, 0, readyFirst, 0, entityCount);
        System.arraycopy(entCount, 0, readyCount, 0, entityCount);
        readyEntityCount = entityCount;
        readyHand = 0;
        for (int e = 0; e < entityCount; e++) if (entKey[e] == -1) readyHand = e + 1;
    }

    private static int readyHand;
    /** 1 + the first-person hand's index among the mobs handed to the tracer (upload), or 0 when it isn't traced. */
    public static int handEntity() { return readyHand; }

    private static float entityBrightness = 1f;
    private static final FloatBuffer colorBuf = BufferUtils.createFloatBuffer(16);

    /**
     * Beta passes its own lighting (the mob's light level) through the drawing colour for
     * overlays like sheep wool and the red hurt flash. We divide it back out, because the
     * path tracer does its own lighting.
     */
    public static void beginEntity(net.minecraft.entity.Entity entity, float tickDelta, double x, double y, double z) {
        entityBrightness = 1f;
        nextKey = entity == null ? 0 : System.identityHashCode(entity);
        if (active && entity != null) {
            try { entityBrightness = Math.max(0.05f, entity.getBrightness(tickDelta)); } catch (Throwable ignored) {}
        }
        beginEntity(x, y, z);
    }

    public static void beginEntity(double x, double y, double z) {
        inEntity = false;
        boolean ber = nextBer;
        nextBer = false;
        if (!active || entityCount >= MAX_ENTITIES) return;
        // x, y, z are relative to the camera; skip far-away mobs
        if (x * x + y * y + z * z > MAX_DISTANCE * MAX_DISTANCE) return;
        entFirst[entityCount] = boxCount;
        entCount[entityCount] = 0;
        entKey[entityCount] = nextKey;
        entBer[entityCount] = ber;
        nextKey = 0;
        inEntity = true;
    }

    public static void endEntity() {
        if (!inEntity) return;
        inEntity = false;
        if (entCount[entityCount] > 0) entityCount++;
    }

    /**
     * One model box being drawn. (x0..x1 etc.) is the box in model units, scale is Beta's
     * 1/16, pivot and angles are the part's own, exactly as ModelPart.render applies them.
     */
    public static void addPart(float x0, float y0, float z0, float x1, float y1, float z1, float scale,
                               float px, float py, float pz, float pitch, float yaw, float roll,
                               int texU, int texV, int sx, int sy, int sz, boolean mirror) {
        addPart(x0, y0, z0, x1, y1, z1, scale, px, py, pz, pitch, yaw, roll, texU, texV, sx, sy, sz, mirror, 32);
    }

    /** As above, for a model whose texture coordinates run over texHeight rows (32 in Beta; 64 for MojangFix's
     *  player model, made for today's 64 x 64 skins). */
    public static void addPart(float x0, float y0, float z0, float x1, float y1, float z1, float scale,
                               float px, float py, float pz, float pitch, float yaw, float roll,
                               int texU, int texV, int sx, int sy, int sz, boolean mirror, int texHeight) {
        if (!inEntity || boxCount >= MAX_BOXES) return;
        if (berDebug) com.alexmcclellan.betapt.BetaPT.LOGGER.info("  part {} {} {} - {} {} {} pivot {} {} {} angles {} {} {}", x0, y0, z0, x1, y1, z1, px, py, pz, pitch, yaw, roll);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        double[] m = new double[16];
        for (int i = 0; i < 16; i++) m[i] = mvBuf.get(i);
        // M = MV * T(pivot * scale) * Rz(roll) * Ry(yaw) * Rx(pitch) * S(scale), same order as ModelPart.render
        m = mul(m, translate(px * scale, py * scale, pz * scale));
        if (roll != 0) m = mul(m, rotZ(roll));
        if (yaw != 0) m = mul(m, rotY(yaw));
        if (pitch != 0) m = mul(m, rotX(pitch));
        m = mul(m, scaleM(scale));
        addBox(m, x0, y0, z0, x1, y1, z1, texU, texV, sx, sy, sz, mirror, -1, texHeight);
    }

    /** One box given the matrix from its own units to eye space (as OpenGL's modelview); layerOverride >= 0 forces a skin layer. */
    private static void addBox(double[] m, double x0, double y0, double z0, double x1, double y1, double z1,
                               int texU, int texV, int sx, int sy, int sz, boolean mirror, int layerOverride) {
        addBox(m, x0, y0, z0, x1, y1, z1, texU, texV, sx, sy, sz, mirror, layerOverride, 0);
    }

    /** texHeight: the rows the model's texture coordinates run over (0: they're the layer's own rows). */
    private static void addBox(double[] m, double x0, double y0, double z0, double x1, double y1, double z1,
                               int texU, int texV, int sx, int sy, int sz, boolean mirror, int layerOverride, int texHeight) {
        // into the world: worldRel = R^T (M q - t); A = R^T * M3 maps box directions into the world
        double[] a = new double[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            double s = 0;
            for (int k = 0; k < 3; k++) s += viewRows[k * 3 + r] * m[c * 4 + k];
            a[r * 3 + c] = s;
        }
        double cx = (x0 + x1) * 0.5, cy = (y0 + y1) * 0.5, cz = (z0 + z1) * 0.5;
        double[] mc = new double[3];
        for (int k = 0; k < 3; k++) mc[k] = m[k] * cx + m[4 + k] * cy + m[8 + k] * cz + m[12 + k] - viewT[k];
        double wx = camX, wy = camY, wz = camZ;
        for (int k = 0; k < 3; k++) {
            wx += viewRows[k * 3] * mc[k];
            wy += viewRows[k * 3 + 1] * mc[k];
            wz += viewRows[k * 3 + 2] * mc[k];
        }
        double[] inv = invert3(a);
        if (inv == null) return;
        // the drawing colour, with Beta's lighting divided back out (dyed wool, hurt flash, creeper flash)
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colorBuf);
        double tr = Math.min(1.0, colorBuf.get(0) / entityBrightness), tg = Math.min(1.0, colorBuf.get(1) / entityBrightness),
                tb = Math.min(1.0, colorBuf.get(2) / entityBrightness), alpha = colorBuf.get(3);
        tr = Math.pow(tr, 2.2); tg = Math.pow(tg, 2.2); tb = Math.pow(tb, 2.2);
        boolean textured = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        double layer, cr, cg, cb;
        if (layerOverride >= 0) {
            layer = layerOverride; cr = 1; cg = 1; cb = 1;
        } else if (!textured) {
            // an untextured overlay drawn over the mob (red hurt flash, white creeper flash): a tint, not a box of its own
            layer = -2; cr = tr; cg = tg; cb = tb;
        } else {
            layer = skinLayerOfBoundTexture();
            if (layer < 0) {
                float[] alb = albedoOfBoundTexture();
                cr = alb[0] * tr; cg = alb[1] * tg; cb = alb[2] * tb;
            } else {
                cr = tr; cg = tg; cb = tb; // multiplies the skin's own colours
            }
        }
        if (berDebug) com.alexmcclellan.betapt.BetaPT.LOGGER.info("  -> world centre {} {} {} rel cam {} {} {}; A {}", wx - camX, wy - camY, wz - camZ, camX, camY, camZ, java.util.Arrays.toString(a));
        // texture rows to layer rows: the texture fills the layer's width and as many rows as its shape gives
        // (32 for a 64 x 32 mob skin, 64 for a 64 x 64 player skin); a model's v runs over texHeight rows of it.
        // Carried in the mirror slot: its size, negative for a mirrored part.
        double vScale = layer >= 0 && texHeight > 0 && layerOverride < 0 ? (double) skinRows[(int) layer] / texHeight : 1;
        boxes[boxCount++] = new double[]{wx, wy, wz, inv[0], inv[1], inv[2], inv[3], inv[4], inv[5], inv[6], inv[7], inv[8],
                Math.abs(x1 - x0) * 0.5, Math.abs(y1 - y0) * 0.5, Math.abs(z1 - z0) * 0.5, cr, cg, cb,
                layer, texU, texV, mirror ? -vScale : vScale, sx + sy * 256 + sz * 65536, alpha};
        entCount[entityCount]++;
    }

    /**
     * Upload last frame's boxes, positioned relative to this frame's camera block.
     * Box buffer: 6 vec4 per box (centre + half x, inverse axes rows + half y/z, albedo + skin
     * layer, texture u/v + mirror + packed size).
     * Entity buffer: 2 vec4 per entity (bounding box min + first box, max + box count).
     */
    public static int upload(int camBlockX, int camBlockY, int camBlockZ) {
        if (boxBuffer == 0) { boxBuffer = GL15.glGenBuffers(); entityBuffer = GL15.glGenBuffers(); }
        FloatBuffer b = BufferUtils.createFloatBuffer(Math.max(1, readyBoxCount) * 24);
        FloatBuffer e = BufferUtils.createFloatBuffer(Math.max(1, readyEntityCount) * 8);
        if (lastBoxes.length < readyBoxCount * 24) lastBoxes = new float[Math.max(readyBoxCount, 64) * 24 * 2];
        for (int i = 0; i < readyBoxCount; i++) {
            double[] x = readyBoxes[i];
            b.put((float) (x[0] - camBlockX)).put((float) (x[1] - camBlockY)).put((float) (x[2] - camBlockZ)).put((float) x[12]);
            b.put((float) x[3]).put((float) x[4]).put((float) x[5]).put((float) x[13]);
            b.put((float) x[6]).put((float) x[7]).put((float) x[8]).put((float) x[14]);
            b.put((float) x[9]).put((float) x[10]).put((float) x[11]).put((float) x[23]); // .w: overlay opacity
            b.put((float) x[15]).put((float) x[16]).put((float) x[17]).put((float) x[18]);
            b.put((float) x[19]).put((float) x[20]).put((float) x[21]).put((float) x[22]);
        }
        for (int i = 0; i < readyEntityCount; i++) {
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            for (int k = readyFirst[i]; k < readyFirst[i] + readyCount[i]; k++) {
                double[] x = readyBoxes[k];
                // a box's reach along each world axis: |A| * half size, where A is the inverse of the stored inverse
                double r = Math.sqrt(x[12] * x[12] + x[13] * x[13] + x[14] * x[14]) / minScale(x);
                for (int c = 0; c < 3; c++) { lo[c] = Math.min(lo[c], x[c] - r); hi[c] = Math.max(hi[c], x[c] + r); }
            }
            e.put((float) (lo[0] - camBlockX)).put((float) (lo[1] - camBlockY)).put((float) (lo[2] - camBlockZ)).put(readyFirst[i]);
            e.put((float) (hi[0] - camBlockX)).put((float) (hi[1] - camBlockY)).put((float) (hi[2] - camBlockZ)).put(readyCount[i]);
        }
        b.flip();
        b.get(lastBoxes, 0, readyBoxCount * 24);
        for (int i = 0; i < readyEntityCount; i++)
            for (int k = readyFirst[i]; k < readyFirst[i] + readyCount[i]; k++) lastBoxEntity[k] = i;
        if (motionBuffer == 0) motionBuffer = GL15.glGenBuffers();
        FloatBuffer mb = BufferUtils.createFloatBuffer(Math.max(1, motionCount) * 4);
        mb.put(entMotion, 0, Math.max(1, motionCount) * 4).flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, motionBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, mb, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        lastBoxCount = readyBoxCount;
        b.rewind();
        e.flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, boxBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, b, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, entityBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, e, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return readyEntityCount;
    }

    public static int boxBuffer() { return boxBuffer; }

    // M8c: the same boxes for the Vulkan tracer (24 floats each, as in the box buffer)
    private static float[] lastBoxes = new float[64 * 24];
    private static int lastBoxCount;
    public static float[] lastBoxes() { return lastBoxes; }
    public static int lastUploadedCount() { return lastBoxCount; }
    private static java.util.Map<Integer, Integer> lastPerEntity = java.util.Map.of();
    /** How many model boxes this entity gave the tracer in the last frame (0: not traced, Beta drew it). */
    public static int boxesFor(net.minecraft.entity.Entity e) { return e == null ? 0 : lastPerEntity.getOrDefault(System.identityHashCode(e), 0); }
    public static int skinCount() { return skinCount; }
    /** Per box: which mob it belongs to (index into this frame's mob list), for the Vulkan tracer's motion. */
    public static int[] lastBoxEntity() { return lastBoxEntity; }
    /** How far each mob moved since last frame (vec4 per mob), and how many. */
    public static int motionBuffer() { return motionBuffer; }
    public static int motionCount() { return motionCount; }
    /** Goes up whenever a skin layer changes (new mob skin, different held item). */
    public static int skinVersion() { return skinVersion; }
    private static int skinVersion;

    /** Every skin copied in so far, ARGB, 64 x 64 per layer, layers one after the other (for the Vulkan tracer). */
    public static int[] skinPixels() {
        if (skinArray == 0 || (skinCount == 0 && !heldLayerUsed)) return new int[0];
        java.nio.ByteBuffer px = BufferUtils.createByteBuffer(SKIN_W * SKIN_H * 4 * MAX_SKINS);
        int bound = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, skinArray);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, bound);
        int[] out = new int[SKIN_W * SKIN_H * (heldLayerUsed ? MAX_SKINS : skinCount)];
        for (int i = 0; i < out.length; i++)
            out[i] = (px.get(i * 4 + 3) & 255) << 24 | (px.get(i * 4) & 255) << 16 | (px.get(i * 4 + 1) & 255) << 8 | (px.get(i * 4 + 2) & 255);
        return out;
    }
    public static int skinArray() { return skinArray; }

    // ---- skins: every mob texture copied into one texture array the tracer can read ----
    // 64 x 64 layers: Beta's 64 x 32 mob skins fill the top half, today's 64 x 64 player skins (MojangFix) all of it
    private static final int SKIN_W = 64, SKIN_H = 64, MAX_SKINS = 128;
    private static int skinArray, skinCount;
    private static final Map<Integer, Integer> skinLayers = new HashMap<>(); // GL texture -> layer (-1: not a skin)
    private static final int[] skinRows = new int[MAX_SKINS];              // rows of its layer each texture fills
    private static final java.util.ArrayDeque<Integer> freeLayers = new java.util.ArrayDeque<>();

    /**
     * Beta deleted a texture or loaded a new picture into it (a player's skin arriving, a texture pack change, a
     * player leaving): its layer is copied again next time, or freed, as the texture's number can come back for
     * something else.
     */
    public static void forgetTexture(int tex) {
        Integer layer = skinLayers.remove(tex);
        albedoCache.remove(tex);
        if (layer != null && layer >= 0) freeLayers.add(layer);
    }

    /** Layer of the texture Beta has bound right now (copied in the first time), or -1 if it isn't a 64 x 32 skin. */
    private static void ensureSkinArray() {
        if (skinArray != 0) return;
            skinArray = GL11.glGenTextures();
            int bound = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, skinArray);
            org.lwjgl.opengl.GL42.glTexStorage3D(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 1, GL11.GL_RGBA8, SKIN_W, SKIN_H, MAX_SKINS);
            GL11.glTexParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, bound);
    }

    private static int skinLayerOfBoundTexture() {
        int tex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        Integer known = skinLayers.get(tex);
        if (known != null) return known;
        int layer = -1;
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        // any size: Beta maps a model's texture coordinates onto the whole texture, so a bigger one (an HD pack, the
        // Aether's whale) is the same layout scaled up, and copySkin scales it back down to a layer
        if (w > 0 && h > 0 && w <= 1024 && h <= 1024 && (!freeLayers.isEmpty() || skinCount < MAX_SKINS - 1)) { // the last layer is the held item's
            ensureSkinArray();
            layer = !freeLayers.isEmpty() ? freeLayers.poll() : skinCount++;
            skinVersion++;
            skinRows[layer] = Math.max(1, Math.min(SKIN_H, Math.round(SKIN_W * (float) h / w)));
            copySkin(tex, layer, skinRows[layer]);
        }
        skinLayers.put(tex, layer);
        return layer;
    }
    private static int skinReadFbo, skinDrawFbo;

    /** Skin texture -> array layer with a framebuffer blit (works whatever internal format the skin was loaded with). */
    private static void copySkin(int tex, int layer, int rows) {
        if (skinReadFbo == 0) { skinReadFbo = org.lwjgl.opengl.GL30.glGenFramebuffers(); skinDrawFbo = org.lwjgl.opengl.GL30.glGenFramebuffers(); }
        int prevRead = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, skinReadFbo);
        org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, skinDrawFbo);
        org.lwjgl.opengl.GL30.glFramebufferTextureLayer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, skinArray, 0, layer);
        // the whole texture, scaled to a skin layer: HD packs and mods with bigger mob textures (the Aether's whale)
        // keep the same layout at a higher resolution
        int[] size = {org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_WIDTH),
                org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_HEIGHT)};
        int sw = size[0] > 0 ? size[0] : SKIN_W, sh = size[1] > 0 ? size[1] : rows;
        // a reused layer may still hold an old skin below this one: clear it first
        org.lwjgl.opengl.GL30.glClearBuffer(GL11.GL_COLOR, 0, (java.nio.FloatBuffer) BufferUtils.createFloatBuffer(4).put(new float[]{0, 0, 0, 0}).flip());
        org.lwjgl.opengl.GL30.glBlitFramebuffer(0, 0, sw, sh, 0, 0, SKIN_W, rows, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevRead);
        org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
    }

    public static int entityBuffer() { return entityBuffer; }

    // ---- M8d: the first-person hand and held item as traced boxes ----
    /** True while the hand is being captured (HeldItemRendererMixin hands the held item over instead of drawing it). */
    public static boolean capturingHand;
    private static final double[] savedViewT = new double[3];
    private static double savedCamX, savedCamY, savedCamZ;

    /** The hand is drawn in eye space (modelview starts at identity): place it at the eye, same view rotation. */
    public static void beginHand() {
        if (!active) return;
        double ex = camX, ey = camY, ez = camZ;
        for (int k = 0; k < 3; k++) { // eye = camera entity - R^T t
            ex -= viewRows[k * 3] * viewT[k]; ey -= viewRows[k * 3 + 1] * viewT[k]; ez -= viewRows[k * 3 + 2] * viewT[k];
        }
        System.arraycopy(viewT, 0, savedViewT, 0, 3);
        savedCamX = camX; savedCamY = camY; savedCamZ = camZ;
        viewT[0] = viewT[1] = viewT[2] = 0;
        camX = ex; camY = ey; camZ = ez;
        entityBrightness = 1f;
        nextKey = -1; // the hand: always the same "mob"
        beginEntity(0, 0, 0);
        capturingHand = true;
    }

    public static void endHand() {
        if (!capturingHand) return;
        capturingHand = false;
        endEntity();
        System.arraycopy(savedViewT, 0, viewT, 0, 3);
        camX = savedCamX; camY = savedCamY; camZ = savedCamZ;
    }

    private static int heldKey = -1;
    private static boolean heldLayerUsed;
    private static int[] itemsAtlas;
    private static int itemsAtlasW;

    /**
     * The held item, at the point Beta would draw it (ItemInHandRenderer.renderItem, current modelview):
     * one box with its own skin layer, laid out the way skins wrap boxes. A block is a cube with its
     * six faces; anything else is Beta's flat sprite, 1/16 thick (see-through pixels let rays through).
     */
    public static void captureHeldItem(net.minecraft.item.ItemStack stack, net.minecraft.client.Minecraft mc) {
        if (!inEntity || stack == null) return;
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuf);
        double[] m = new double[16];
        for (int i = 0; i < 16; i++) m[i] = mvBuf.get(i);
        int id = stack.id;
        net.minecraft.block.Block block = heldBlock(id);
        boolean cube = block != null && net.minecraft.client.render.block.BlockRenderer.isItem3d(block.getRenderType());
        int key = id * 65536 + stack.metadata * 2 + (cube ? 1 : 0);
        if (key != heldKey) { buildHeldLayer(stack, block, cube, mc); heldKey = key; }
        if (cube) {
            addBox(m, block.minX - 0.5, block.minY - 0.5, block.minZ - 0.5, block.maxX - 0.5, block.maxY - 0.5, block.maxZ - 0.5,
                    0, 0, 16, 16, 16, false, MAX_SKINS - 1);
        } else {
            // ItemInHandRenderer.renderItem's transform for sprites, then the sprite spans x, y 0..1 at z -1/16..0
            m = mul(m, translate(0, -0.3, 0));
            m = mul(m, scaleM(1.5));
            m = mul(m, rotY(Math.toRadians(50)));
            m = mul(m, rotZ(Math.toRadians(335)));
            m = mul(m, translate(-0.9375, -0.0625, 0));
            addBox(m, 0, 0, -0.0625, 1, 1, 0, 0, 0, 16, 16, 1, false, MAX_SKINS - 1);
        }
    }

    /** Paints the held item's skin layer: for every texel of each face's area (as MobMesher / skinTexel map
     *  them), the colour of the item at that spot on the box. */
    private static void buildHeldLayer(net.minecraft.item.ItemStack stack, net.minecraft.block.Block block, boolean cube,
                                       net.minecraft.client.Minecraft mc) {
        ensureSkinArray();
        int dx = 16, dy = 16, dz = cube ? 16 : 1;
        int[] px = new int[SKIN_W * SKIN_H];
        int spriteIdx = 0;
        int[] atlas = BlockPalette.ATLAS; int aw = BlockPalette.ATLAS_W;
        if (!cube) {
            spriteIdx = stack.getSprite();
            if (block != null) spriteIdx = StationCompat.terrainTile(block, spriteIdx); // a block drawn flat (flowers, saplings)
            else if (StationCompat.itemGrid != null) { // StationAPI: every item's texture, modded ones included
                atlas = StationCompat.itemGrid; aw = StationCompat.itemGridW;
                spriteIdx = StationCompat.itemTile(net.minecraft.item.Item.BY_ID[stack.id], spriteIdx);
            } else { atlas = itemsAtlas(mc); aw = itemsAtlasW; }
        }
        int ts = aw / 16;
        for (int f = 0; f < 6; f++) {
            for (int a = 0; a < 64; a++) for (int b = 0; b < 64; b++) {
                double s = (a + 0.5) / 64, t = (b + 0.5) / 64;
                double fx, fy, fz; double u, v;
                switch (f) {
                    case 0 -> { fx = 0; fz = s; fy = t; u = (1 - fz) * dz; v = dz + fy * dy; }              // -x
                    case 1 -> { fx = 1; fz = s; fy = t; u = dz + dx + fz * dz; v = dz + fy * dy; }          // +x
                    case 2 -> { fy = 0; fx = s; fz = t; u = dz + fx * dx; v = (1 - fz) * dz; }              // -y
                    case 3 -> { fy = 1; fx = s; fz = t; u = dz + dx + fx * dx; v = fz * dz; }               // +y
                    case 4 -> { fz = 0; fx = s; fy = t; u = dz + fx * dx; v = dz + fy * dy; }               // -z
                    default -> { fz = 1; fx = s; fy = t; u = 2 * dz + dx + (1 - fx) * dx; v = dz + fy * dy; } // +z
                }
                int iu = (int) Math.floor(u), iv = (int) Math.floor(v);
                if (iu < 0 || iv < 0 || iu >= SKIN_W || iv >= SKIN_H) continue;
                int c;
                if (cube) {
                    // block face: Beta's sides 0 bottom, 1 top, 2..5 the four walls; texture v runs down the wall
                    int side = f == 2 ? 0 : f == 3 ? 1 : f == 4 ? 2 : f == 5 ? 3 : f == 0 ? 4 : 5;
                    int tile = StationCompat.terrainTile(block, block.getSprite(side, stack.metadata));
                    double tu = f <= 1 ? fz : fx, tv = f == 2 || f == 3 ? fz : 1 - fy;
                    if (f == 1 || f == 4) tu = 1 - tu;
                    c = tileTexel(atlas, aw, ts, tile, tu, tv);
                } else {
                    // the sprite: x = 0 is its right edge (Beta mirrors it), y = 0 its bottom
                    c = tileTexel(atlas, aw, ts, spriteIdx, 1 - fx, 1 - fy);
                }
                px[iv * SKIN_W + iu] = c;
            }
        }
        java.nio.ByteBuffer buf = BufferUtils.createByteBuffer(SKIN_W * SKIN_H * 4);
        for (int c : px) buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        buf.flip();
        int bound = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, skinArray);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        org.lwjgl.opengl.GL12.glTexSubImage3D(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, MAX_SKINS - 1, SKIN_W, SKIN_H, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glBindTexture(org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY, bound);
        heldLayerUsed = true;
        skinVersion++;
    }

    private static int tileTexel(int[] atlas, int aw, int ts, int tile, double u, double v) {
        int x = (tile & 15) * ts + Math.min(ts - 1, (int) (u * ts)), y = (tile >> 4) * ts + Math.min(ts - 1, (int) (v * ts));
        int i = y * aw + x;
        return i >= 0 && i < atlas.length ? atlas[i] : 0;
    }

    /** The block an item places, or null (Beta's blocks are their own item IDs; StationAPI's modded ones aren't). */
    public static net.minecraft.block.Block heldBlock(int itemId) {
        if (itemId < 256) return itemId < net.minecraft.block.Block.BY_ID.length ? net.minecraft.block.Block.BY_ID[itemId] : null;
        net.minecraft.item.Item item = itemId < net.minecraft.item.Item.BY_ID.length ? net.minecraft.item.Item.BY_ID[itemId] : null;
        if (!(item instanceof net.minecraft.item.BlockItem)) return null;
        try { // StationAPI's block items (BlockItemForm.getBlock)
            java.lang.reflect.Method m = item.getClass().getMethod("getBlock");
            if (m.invoke(item) instanceof net.minecraft.block.Block b) return b;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Beta's items.png as ARGB, read once. */
    private static int[] itemsAtlas(net.minecraft.client.Minecraft mc) {
        if (itemsAtlas != null) return itemsAtlas;
        int tex = mc.textureManager.load("/gui/items.png");
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        java.nio.ByteBuffer b = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++)
            out[i] = (b.get(i * 4 + 3) & 255) << 24 | (b.get(i * 4) & 255) << 16 | (b.get(i * 4 + 1) & 255) << 8 | (b.get(i * 4 + 2) & 255);
        itemsAtlasW = w;
        return itemsAtlas = out;
    }
    public static int lastBoxCount() { return readyBoxCount; }

    /** Smallest stretch of the inverse axes, i.e. 1 / largest world size per local unit (for a safe bounding box). */
    private static double minScale(double[] x) {
        double s = Double.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            double l = Math.sqrt(x[3 + r * 3] * x[3 + r * 3] + x[4 + r * 3] * x[4 + r * 3] + x[5 + r * 3] * x[5 + r * 3]);
            s = Math.min(s, l);
        }
        return Math.max(s, 1e-6);
    }

    /** Average colour of the mob's skin (linear light), worked out once per texture. */
    private static float[] albedoOfBoundTexture() {
        int tex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        float[] cached = albedoCache.get(tex);
        if (cached != null) return cached;
        float[] out = {0.5f, 0.5f, 0.5f};
        try {
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w > 0 && h > 0 && w * h <= 1024 * 1024) {
                ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, px);
                double r = 0, g = 0, bl = 0, n = 0;
                for (int i = 0; i < w * h; i++) {
                    int a = px.get(i * 4 + 3) & 255;
                    if (a < 128) continue;
                    bl += Math.pow((px.get(i * 4) & 255) / 255.0, 2.2);
                    g += Math.pow((px.get(i * 4 + 1) & 255) / 255.0, 2.2);
                    r += Math.pow((px.get(i * 4 + 2) & 255) / 255.0, 2.2);
                    n++;
                }
                if (n > 0) out = new float[]{(float) (r / n), (float) (g / n), (float) (bl / n)};
            }
        } catch (Throwable ignored) {
        }
        albedoCache.put(tex, out);
        return out;
    }

    // ---- small matrix helpers (column-major 4x4, like OpenGL) ----
    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++) {
            double s = 0;
            for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
            r[c * 4 + row] = s;
        }
        return r;
    }
    private static double[] translate(double x, double y, double z) {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1};
    }
    private static double[] scaleM(double s) {
        return new double[]{s, 0, 0, 0, 0, s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1};
    }
    private static double[] rotX(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1}; }
    private static double[] rotY(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1}; }
    private static double[] rotZ(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }

    /** Inverse of a row-major 3x3 matrix, or null if it's flat. */
    private static double[] invert3(double[] a) {
        double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        if (Math.abs(det) < 1e-12) return null;
        double id = 1.0 / det;
        return new double[]{
                (a[4] * a[8] - a[5] * a[7]) * id, (a[2] * a[7] - a[1] * a[8]) * id, (a[1] * a[5] - a[2] * a[4]) * id,
                (a[5] * a[6] - a[3] * a[8]) * id, (a[0] * a[8] - a[2] * a[6]) * id, (a[2] * a[3] - a[0] * a[5]) * id,
                (a[3] * a[7] - a[4] * a[6]) * id, (a[1] * a[6] - a[0] * a[7]) * id, (a[0] * a[4] - a[1] * a[3]) * id};
    }
}
