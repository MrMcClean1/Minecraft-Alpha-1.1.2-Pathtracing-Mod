package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import com.alexmcclellan.betapt.config.BetaPTConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * M8b/M8c: the game's door to the Vulkan code in src/vk (the ray-tracing probe and the live
 * hardware tracer).
 *
 * The game uses LWJGL 2; Vulkan needs LWJGL 3, and both put classes in the same package
 * names. So the Vulkan code and LWJGL 3 ship inside our jar as resources, get unpacked to
 * .minecraft/betapt/vk/, and load in a class loader of their own that can't see the game's
 * classes at all. The two sides only pass plain Java values (arrays, numbers, strings, maps).
 */
public final class VkBridge {
    private VkBridge() {}

    private static final String[] FILES = {"betapt-vk", "lwjgl", "lwjgl-natives-windows", "lwjgl-vulkan", "lwjgl-opengl", "lwjgl-opengl-natives-windows"};
    private static final int GRID_X = 256, GRID_Y = 128, GRID_Z = 256;
    private static URLClassLoader loader;

    /** Unpacks the Vulkan side once and opens its class loader. */
    private static synchronized ClassLoader loader() throws Exception {
        if (loader != null) return loader;
        Path dir = BetaPTConfig.gameDir().resolve("betapt").resolve("vk");
        Files.createDirectories(dir);
        URL[] urls = new URL[FILES.length];
        for (int i = 0; i < FILES.length; i++) {
            Path target = dir.resolve(FILES[i] + ".jar");
            try (InputStream s = VkBridge.class.getResourceAsStream("/assets/betapt/vk/" + FILES[i] + ".bin")) {
                if (s == null) throw new IllegalStateException("missing " + FILES[i] + " in the mod jar");
                Files.copy(s, target, StandardCopyOption.REPLACE_EXISTING);
            }
            urls[i] = target.toUri().toURL();
        }
        loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        return loader;
    }

    /**
     * LWJGL 2's natives folder (org.lwjgl.librarypath) also holds a 32-bit "lwjgl.dll" that LWJGL 3
     * would try to load; hide that setting while LWJGL 3 loads its native code, so it unpacks its own.
     */
    private interface Call<T> { T run() throws Exception; }
    private static <T> T withLwjgl3(Call<T> c) throws Exception {
        String lwjgl2Path = System.getProperty("org.lwjgl.librarypath");
        System.clearProperty("org.lwjgl.librarypath");
        try {
            return c.run();
        } finally {
            if (lwjgl2Path != null) System.setProperty("org.lwjgl.librarypath", lwjgl2Path);
        }
    }

    /** The blocks around the camera as the Vulkan side wants them; also returns the grid's corner. */
    private static Map<String, Object> blocks(Minecraft mc, Camera cam, int[] originOut) {
        int ox = (int) Math.floor(cam.x) - GRID_X / 2, oz = (int) Math.floor(cam.z) - GRID_Z / 2;
        byte[] ids = new byte[GRID_X * GRID_Y * GRID_Z];
        for (int y = 0; y < GRID_Y; y++)
            for (int z = 0; z < GRID_Z; z++)
                for (int x = 0; x < GRID_X; x++)
                    ids[x + GRID_X * (z + GRID_Z * y)] = (byte) mc.world.getBlock(ox + x, y, oz + z);
        boolean[] solid = new boolean[256];
        for (int id = 1; id < 256; id++) {
            Block b = Block.BY_ID[id];
            solid[id] = b != null && b.isCube() && b.material != Material.WATER && b.material != Material.LAVA;
        }
        Map<String, Object> in = new HashMap<>();
        in.put("ids", ids); in.put("sx", GRID_X); in.put("sy", GRID_Y); in.put("sz", GRID_Z);
        in.put("solid", solid); in.put("colors", BlockPalette.RGB.clone());
        originOut[0] = ox; originOut[1] = oz;
        return in;
    }

    private static float[] camera(Camera cam, int ox, int oz, float tanHalfFov, float[] sunDir, float maxDist) {
        return new float[]{(float) (cam.x - ox), (float) cam.y, (float) (cam.z - oz), cam.fx, cam.fy, cam.fz, cam.rx, cam.ry, cam.rz,
                cam.ux, cam.uy, cam.uz, tanHalfFov, sunDir[0], sunDir[1], sunDir[2], maxDist};
    }

    // ------------------------------------------------------------------ M8b probe

    /** Traces the blocks around the camera with Vulkan ray queries; returns the probe's results (see VkProbe.run). */
    public static Map<String, Object> probe(Minecraft mc, Camera cam, float tanHalfFov, float[] sunDir, float maxDist,
                                            int width, int height, int frames, Path png) {
        Map<String, Object> result;
        try {
            int[] o = new int[2];
            Map<String, Object> in = blocks(mc, cam, o);
            in.put("cam", camera(cam, o[0], o[1], tanHalfFov, sunDir, maxDist));
            in.put("width", width); in.put("height", height); in.put("frames", frames);
            in.put("png", png == null ? null : png.toString());
            ClassLoader cl = loader();
            result = withLwjgl3(() -> {
                Method run = cl.loadClass("com.alexmcclellan.betapt.vk.VkProbe").getMethod("run", Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) run.invoke(null, in);
                return r;
            });
        } catch (Throwable t) {
            result = new HashMap<>();
            result.put("error", t.toString());
        }
        if (result.containsKey("error")) BetaPT.LOGGER.error("Vulkan probe: {}\n{}", result.get("error"), result.getOrDefault("stack", ""));
        else BetaPT.LOGGER.info("Vulkan probe: {}", result);
        return result;
    }

    // ------------------------------------------------------------------ M8c live tracer

    private static Object live;
    private static Method mTrace, mSetChunk, mDropChunk, mTextures, mClose, mInfo, mSetMobs, mSetSkins, mSetTiles, mSetFastLeaves;
    private static int sentSkins;
    private static boolean sentFastLeaves;
    private static int liveW, liveH;
    private static final int[] sentVersion = new int[VoxelWorld.SLOTS * VoxelWorld.SLOTS];
    /** The chunk each slot held when it was last sent: same chunk again = a block changed in it, not a new chunk. */
    private static final Object[] sentChunk = new Object[VoxelWorld.SLOTS * VoxelWorld.SLOTS];
    /**
     * Changed chunks re-meshed a frame (beyond the ones next to you, which always go at once). Every block change
     * re-meshes a whole chunk (about 3 ms of CPU each), and the world never stops changing: snow settling while
     * it snows, leaves decaying, crops, water, machines switching on and off. The vanilla test showed 40 chunks a
     * frame and 7 fps while snow fell. A far chunk whose snow layer shows a second late costs nothing to look at.
     */
    private static final int CHANGES_PER_FRAME = 3;
    private static int changeStart;
    private static boolean liveFailed;
    /** True once the Vulkan tracer couldn't start or stopped; the OpenGL tracer is used from then on. */
    public static boolean failed() { return liveFailed || simulated; }
    private static boolean simulated;
    /** Autotest: behave as if this PC had no Vulkan ray tracing (on), or go back to normal (off). */
    public static void simulateUnavailable(boolean on) {
        simulated = on;
        liveError = on ? "Vulkan ray tracing not available (simulated by the autotest)" : null;
    }
    /** The textures changed (texture pack): the Vulkan tracer starts over with the new ones next frame. */
    public static void reopen() { liveW = -1; }
    /** Why the Vulkan tracer stopped (shown on F3), or null. */
    public static String liveError;
    /** GPU time of the last Vulkan frame (top tree + trace), ms. */
    public static double liveGpuMs;
    /** Device names and scene numbers, for F3 and the test report. */
    public static String liveInfo = "";
    /** Chunks sent to Vulkan last frame. */
    public static int chunksSent;
    /** Chunks whose triangles Vulkan finished (on its worker thread) and put in the scene last frame. */
    public static int chunksMeshed;
    /** Main-thread time last frame: handing over chunks, mobs and skins / the whole Vulkan call, ms. */
    public static double liveSendMs, liveCpuMs;
    // chunks are only copied here; the triangles are made on Vulkan's own worker thread
    private static final int CHUNKS_PER_FRAME = 256;

    /**
     * Traces one frame with the hardware tracer. Sends Vulkan any chunks that changed since last
     * time (up to CHUNKS_PER_FRAME), then traces. Returns the OpenGL textures it wrote (light, sky/fog,
     * albedo, extra, geometry A, depth, geometry B) or null if it can't run (reason in liveError).
     */
    public static int[] liveTrace(Minecraft mc, VoxelWorld voxels, int[] blockInfo, float[] blockBoxes, int w, int h,
                                  float[] params, int[] iparams, float[] lights, float[] mobBoxes, int mobCount, int[] mobEntity) {
        if (liveFailed || simulated) return null;
        long t0 = System.nanoTime();
        // new mob skins are read from OpenGL here (LWJGL 2), outside the Vulkan side
        int skins = EntityCapture.skinVersion();
        int[] skinPx = live != null && skins != sentSkins ? EntityCapture.skinPixels() : null;
        try {
            return withLwjgl3(() -> {
                if (live == null || w != liveW || h != liveH) {
                    closeLive();
                    Class<?> c = loader().loadClass("com.alexmcclellan.betapt.vk.VkLive");
                    Map<String, Object> in = new HashMap<>();
                    in.put("width", w); in.put("height", h);
                    in.put("atlas", BlockPalette.ATLAS); in.put("atlasW", BlockPalette.ATLAS_W); in.put("atlasH", BlockPalette.ATLAS_H);
                    in.put("clouds", clouds(mc));
                    in.put("atlasS", BlockPalette.PBR_S != null ? BlockPalette.PBR_S : new int[]{0xFF000000});
                    in.put("atlasN", BlockPalette.PBR_N != null ? BlockPalette.PBR_N : new int[]{0xFF8080FF});
                    in.put("blockInfo", blockInfo);
                    in.put("blockBox", blockBoxes);
                    in.put("blockLum", blockLum());
                    PathTracer nvp = PathTracer.INSTANCE; // NVIDIA RTX extras, each only if switched on
                    in.put("dlss", nvp.upscaler == PathTracer.UPSCALER_DLSS);
                    in.put("reflex", nvp.lowLatency == 2);
                    in.put("ser", nvp.ser);
                    in.put("omm", nvp.omm);
                    live = c.getMethod("open", Map.class).invoke(null, in);
                    mTrace = c.getMethod("trace", float[].class, int[].class, float[].class);
                    mSetChunk = c.getMethod("setChunk", int.class, int.class, int.class, short[].class, byte[].class, int[].class, int[].class);
                    mDropChunk = c.getMethod("dropChunk", int.class);
                    mTextures = c.getMethod("glTextures");
                    mClose = c.getMethod("close");
                    mInfo = c.getMethod("info");
                    mSetMobs = c.getMethod("setMobs", float[].class, int.class, int[].class);
                    mSetSkins = c.getMethod("setSkins", int[].class, int.class);
                    mSetTiles = c.getMethod("setTiles", int[].class, int[].class);
                    mSetFastLeaves = c.getMethod("setFastLeaves", boolean.class);
                    mDlss = c.getMethod("dlss", int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, float[].class, boolean.class);
                    mDlssSizes = c.getMethod("dlssSizes", int.class, int.class, int.class);
                    mDlssReady = c.getMethod("dlssReady");
                    mReflex = c.getMethod("reflexFrame", int.class);
                    mReflexOn = c.getMethod("reflexOn");
                    mNvStatus = c.getMethod("nvStatus");
                    nvStatus = (String) mNvStatus.invoke(live);
                    System.out.println("[BetaPT] NVIDIA features: " + nvStatus);
                    sentFastLeaves = false;
                    AnimatedTiles.reset();
                    liveW = w; liveH = h;
                    java.util.Arrays.fill(sentVersion, -1);
                    java.util.Arrays.fill(sentChunk, null);
                    sentSkins = 0;
                }
                if (skinPx != null) { if (skinPx.length > 0) mSetSkins.invoke(live, skinPx, skinPx.length / (64 * 64)); sentSkins = skins; }
                mSetMobs.invoke(live, mobBoxes, mobCount, mobEntity);
                voxels.readsWanted = System.nanoTime();
                boolean fast = PathTracer.INSTANCE.fastLeaves;
                if (fast != sentFastLeaves) { // leaves are meshed solid or see-through: mesh every chunk again
                    mSetFastLeaves.invoke(live, fast);
                    sentFastLeaves = fast;
                    java.util.Arrays.fill(sentVersion, -1);
                    java.util.Arrays.fill(sentChunk, null); // every chunk again, all at once
                }
                // chunks that are new, changed or gone since we last sent them
                int sent = 0, changes = 0;
                int pcx = mc.player != null ? (int) Math.floor(mc.player.x) >> 4 : 0, pcz = mc.player != null ? (int) Math.floor(mc.player.z) >> 4 : 0;
                int n = sentVersion.length;
                changeStart = (changeStart + 37) % n; // a different first slot each frame, so far changes take turns
                for (int i = 0; i < n && sent < CHUNKS_PER_FRAME; i++) {
                    int s = (changeStart + i) % n;
                    int v = voxels.slotVersion[s];
                    if (v == sentVersion[s]) continue;
                    net.minecraft.world.chunk.WorldChunk ch = voxels.chunkIn(s);
                    if (ch != null && ch == sentChunk[s]) { // a change in a chunk Vulkan already has
                        boolean near = Math.abs(ch.chunkX - pcx) <= 2 && Math.abs(ch.chunkZ - pcz) <= 2;
                        if (!near && changes >= CHANGES_PER_FRAME) continue; // next frame
                        if (!near) changes++;
                    }
                    sentChunk[s] = ch;
                    if (ch == null) mDropChunk.invoke(live, s);
                    else {
                        Object[] read = voxels.takeRead(s); // already read this frame for the OpenGL copy
                        short[] ids = read != null ? (short[]) read[0] : new short[32768];
                        byte[] meta = read != null ? (byte[]) read[1] : new byte[16384];
                        if (read == null) StationCompat.read(ch, ids, meta);
                        mSetChunk.invoke(live, s, ch.chunkX, ch.chunkZ, ids, meta, voxels.slotGrass[s], voxels.slotFoliage[s]);
                    }
                    sentVersion[s] = v;
                    sent++;
                }
                chunksSent = sent;
                liveSendMs = (System.nanoTime() - t0) / 1e6;
                int[][] tiles = AnimatedTiles.poll(mc); // water, lava, portals: keep Vulkan's copy of the atlas moving
                if (tiles != null) mSetTiles.invoke(live, tiles[0], tiles[1]);
                liveGpuMs = (Double) mTrace.invoke(live, params, iparams, lights);
                @SuppressWarnings("unchecked")
                Map<String, Object> info = (Map<String, Object>) mInfo.invoke(live);
                Object m = info.get("meshedThisFrame");
                chunksMeshed = m instanceof Integer ? (Integer) m : 0;
                liveInfo = String.valueOf(info);
                nvStatus = (String) mNvStatus.invoke(live);
                liveCpuMs = (System.nanoTime() - t0) / 1e6;
                return (int[]) mTextures.invoke(live);
            });
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            liveError = cause.toString();
            liveFailed = true;
            BetaPT.LOGGER.error("Vulkan tracer stopped", cause);
            try { closeLive(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /**
     * Each block ID's light level (0-15, low 4 bits): modded light blocks glow by it (see emission() in pt_base.glsl).
     * Modded ones also carry their texture's average colour (bits 8-31, RRGGBB) so they shine in their own colour:
     * BuildCraft's milk white, RedPower's lamps in theirs, instead of all a torch's warm orange.
     */
    public static int[] blockLum() {
        int[] l = new int[StationCompat.MAX_ID];
        for (int id = 0; id < l.length; id++) {
            int lum = StationCompat.luminance(id) & 15;
            int rgb = id >= 97 && lum > 0 && id < BlockPalette.RGB.length ? BlockPalette.RGB[id] & 0xFFFFFF : 0;
            l[id] = lum | rgb << 8;
        }
        return l;
    }

    /** Beta's clouds.png as ARGB pixels (256 x 256). */
    private static int[] clouds(Minecraft mc) {
        int tex = mc.textureManager.load("/clouds.png");
        int prev = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, tex);
        int cw = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
        int chh = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(cw * chh * 4);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
        org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prev);
        int[] out = new int[256 * 256];
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) {
                int i = ((y * chh / 256) * cw + (x * cw / 256)) * 4; // same picture if a texture pack made it bigger
                out[y * 256 + x] = (px.get(i + 3) & 255) << 24 | (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | (px.get(i + 2) & 255);
            }
        return out;
    }

    // ---- NVIDIA RTX extras (VkLive / NvFeatures)
    private static Method mDlss, mDlssSizes, mDlssReady, mReflex, mReflexOn, mNvStatus;
    /** What the NVIDIA extras are doing (Effects > NVIDIA RTX shows it). */
    public static String nvStatus = "";

    public static boolean dlssReady() {
        try { return live != null && (Boolean) mDlssReady.invoke(live); } catch (Throwable t) { return false; }
    }

    public static int[] dlssSizes(int mode, int ow, int oh) {
        if (live == null || mDlssSizes == null) return null;
        try { return (int[]) withLwjgl3(() -> mDlssSizes.invoke(live, mode, ow, oh)); } catch (Throwable t) { return null; }
    }

    /** DLSS on the Vulkan side: the OpenGL texture with the window-sized picture, or 0 (then FSR). */
    public static int dlss(int color, int depth, int motion, int rw, int rh, int ow, int oh, int mode, float[] c, boolean reset) {
        if (live == null || liveFailed || mDlss == null) return 0;
        try { return (Integer) withLwjgl3(() -> mDlss.invoke(live, color, depth, motion, rw, rh, ow, oh, mode, c, reset)); }
        catch (Throwable t) { nvStatus = "DLSS: " + t; return 0; }
    }

    public static boolean reflexOn() {
        try { return live != null && mReflexOn != null && (Boolean) mReflexOn.invoke(live); } catch (Throwable t) { return false; }
    }

    public static void reflexFrame(int phase) {
        if (live == null || mReflex == null) return;
        try { mReflex.invoke(live, phase); } catch (Throwable ignored) {}
    }

    private static void closeLive() throws Exception {
        if (live != null) mClose.invoke(live);
        live = null;
    }
}
