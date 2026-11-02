package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Far terrain (the Distant Horizons idea, for Beta and Alpha): past the path tracer's own range, the land is drawn
 * from the world's saved chunks as a height map, two blocks to a cell, out to about 1000 blocks. A background thread
 * reads the region files (Beta's .mcr, or Alpha's c.x.z.dat chunk files), keeps the top block's height and colour of
 * each cell, and the picture is drawn over the sky where the tracer's rays found nothing (far.frag), fading into the
 * fog. Only ground that has been generated shows: Beta and Alpha make new land as you walk, not ahead of time.
 * Single player and the overworld only (a server's chunks aren't on this computer). Same file in Beta and Alpha.
 */
public final class FarTerrain {
    private FarTerrain() {}

    public static boolean enabled = load();
    /** Cells per side, blocks per cell: 1024 x 2 = 2048 blocks across, about 1000 out from the middle. */
    static final int N = 1024, CELL = 2;
    private static final int CHUNKS = N * CELL / 16; // chunks per side

    private static int tex, program;
    private static volatile int[] ready;      // a finished map waiting to be uploaded (ARGB: colour, alpha = height)
    private static volatile int readyX, readyZ; // its north-west corner in blocks
    private static int mapX = Integer.MIN_VALUE, mapZ;
    private static volatile boolean building;
    private static long lastBuild;
    private static File lastDir;
    public static String status = "off";

    // ---- the setting (config/betapt-far.properties, the same in every version)

    private static File configFile() { return new File("config", "betapt-far.properties"); }

    private static boolean load() {
        try (InputStream in = new FileInputStream(configFile())) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            return Boolean.parseBoolean(p.getProperty("enabled", "false"));
        } catch (Exception e) { return false; }
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        try {
            configFile().getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(configFile())) {
                java.util.Properties p = new java.util.Properties();
                p.setProperty("enabled", Boolean.toString(on));
                p.store(out, "Far terrain past the path tracer's range (drawn from saved chunks)");
            }
        } catch (Exception ignored) {}
        if (!on) { mapX = Integer.MIN_VALUE; status = "off"; }
    }

    // ---- where the world is saved: the first File field of the world or of its storage (Beta and Alpha alike)

    static File saveDir(Object world) {
        if (world == null) return null;
        try {
            for (Class<?> c = world.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(world);
                    if (v instanceof File && new File((File) v, "level.dat").isFile()) return (File) v;
                    if (v != null && !(v instanceof File) && !v.getClass().getName().startsWith("java.") && !v.getClass().isArray()) { // the world's storage (names are obfuscated at runtime)
                        for (Class<?> s = v.getClass(); s != null && s != Object.class; s = s.getSuperclass())
                            for (java.lang.reflect.Field g : s.getDeclaredFields()) {
                                if (g.getType() != File.class || java.lang.reflect.Modifier.isStatic(g.getModifiers())) continue;
                                g.setAccessible(true);
                                Object d = g.get(v);
                                if (d instanceof File && new File((File) d, "level.dat").isFile()) return (File) d;
                            }
                    }
                }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Once a frame from the tracer: start a new map when needed (moved far, or a minute old), upload a finished one. */
    public static void tick(Object world, boolean overworld, double camX, double camZ) {
        if (!enabled) return;
        if (!overworld) { status = "overworld only"; return; }
        File dir = saveDir(world);
        if (dir == null) { status = "single player only"; return; }
        int[] map = ready;
        if (map != null) {
            ready = null;
            upload(map);
            mapX = readyX; mapZ = readyZ;
            BetaPT.LOGGER.info("Far terrain: {} around {}, {}", status, (int) camX, (int) camZ);
        }
        boolean moved = mapX == Integer.MIN_VALUE || !dir.equals(lastDir)
                || Math.abs(camX - (mapX + N * CELL / 2.0)) > 256 || Math.abs(camZ - (mapZ + N * CELL / 2.0)) > 256;
        if (!building && (moved || System.currentTimeMillis() - lastBuild > 60_000)) {
            building = true;
            lastBuild = System.currentTimeMillis();
            lastDir = dir;
            final int cx = (int) Math.floor(camX / 16) - CHUNKS / 2, cz = (int) Math.floor(camZ / 16) - CHUNKS / 2;
            Thread t = new Thread(() -> {
                try { build(dir, cx, cz); } catch (Throwable e) { status = "error: " + e; BetaPT.LOGGER.warn("Far terrain: {}", e.toString()); }
                finally { building = false; }
            }, "BetaPT far terrain");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    // ---- reading the saved chunks

    private static void build(File dir, int cx0, int cz0) throws IOException {
        long t0 = System.nanoTime();
        int[] map = new int[N * N];
        int[] rgb = BlockPalette.RGB;
        File region = new File(dir, "region");
        int chunks = 0;
        if (region.isDirectory()) {
            // Beta: 32 x 32 chunks a region file
            for (int rx = Math.floorDiv(cx0, 32); rx <= Math.floorDiv(cx0 + CHUNKS - 1, 32); rx++)
                for (int rz = Math.floorDiv(cz0, 32); rz <= Math.floorDiv(cz0 + CHUNKS - 1, 32); rz++) {
                    File f = new File(region, "r." + rx + "." + rz + ".mcr");
                    if (!f.isFile()) continue;
                    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                        if (r.length() < 4096) continue;
                        byte[] head = new byte[4096];
                        r.readFully(head);
                        for (int i = 0; i < 1024; i++) {
                            int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                            if (cx < cx0 || cz < cz0 || cx >= cx0 + CHUNKS || cz >= cz0 + CHUNKS) continue;
                            int off = (head[i * 4] & 255) << 16 | (head[i * 4 + 1] & 255) << 8 | (head[i * 4 + 2] & 255);
                            if (off == 0) continue;
                            try {
                                r.seek(off * 4096L);
                                int len = r.readInt(), type = r.readByte();
                                if (len <= 1 || len > 1 << 20) continue;
                                byte[] data = new byte[len - 1];
                                r.readFully(data);
                                InputStream in = type == 1 ? new GZIPInputStream(new ByteArrayInputStream(data)) : new InflaterInputStream(new ByteArrayInputStream(data));
                                if (chunk(new DataInputStream(new BufferedInputStream(in)), map, cx - cx0, cz - cz0, rgb)) chunks++;
                            } catch (Exception ignored) {} // being written right now, or damaged: skip it
                        }
                    } catch (Exception ignored) {}
                }
        } else {
            // Alpha (and Beta worlds never converted): one gzip file a chunk, in folders by x & 63, z & 63
            for (int cx = cx0; cx < cx0 + CHUNKS; cx++)
                for (int cz = cz0; cz < cz0 + CHUNKS; cz++) {
                    File f = new File(new File(new File(dir, Integer.toString(cx & 63, 36)), Integer.toString(cz & 63, 36)),
                            "c." + Integer.toString(cx, 36) + "." + Integer.toString(cz, 36) + ".dat");
                    if (!f.isFile()) continue;
                    try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                        if (chunk(new DataInputStream(in), map, cx - cx0, cz - cz0, rgb)) chunks++;
                    } catch (Exception ignored) {}
                }
        }
        readyX = cx0 * 16; readyZ = cz0 * 16;
        ready = map;
        status = chunks + " chunks, " + (System.nanoTime() - t0) / 1_000_000 + " ms";
    }

    /** One chunk's NBT: its HeightMap and Blocks into the map cells it covers (chunk position in chunks from the map's corner). */
    private static boolean chunk(DataInputStream in, int[] map, int ccx, int ccz, int[] rgb) throws IOException {
        byte[][] found = new byte[2][]; // Blocks, HeightMap
        if (in.readByte() != 10) return false;
        in.readUTF();
        readCompound(in, found, 0);
        byte[] blocks = found[0], height = found[1];
        if (blocks == null || height == null || blocks.length < 32768 || height.length < 256) return false;
        for (int lx = 0; lx < 16; lx += CELL)
            for (int lz = 0; lz < 16; lz += CELL) {
                int y = height[lz << 4 | lx] & 255;
                int id = 0;
                // the heightmap is the first block light can't pass: step down past air and plants to what's on top
                for (int k = 0; k < 6 && y > 0; k++) {
                    id = blocks[(lx << 11) | (lz << 7) | (y - 1)] & 255;
                    if (id != 0 && id != 31 && id != 37 && id != 38 && id != 39 && id != 40 && id != 50 && id != 78 && id != 6) break;
                    y--;
                }
                if (y <= 0 || id == 0) continue;
                int c = id < rgb.length ? rgb[id] : 0x808080;
                if (id == 8 || id == 9) c = 0x2F5BC8;  // water: its texture is grey, tinted blue in game
                if (c == 0) c = 0x808080;
                int mx = (ccx * 16 + lx) / CELL, mz = (ccz * 16 + lz) / CELL;
                if (mx < 0 || mz < 0 || mx >= N || mz >= N) continue;
                map[mz * N + mx] = Math.min(255, y) << 24 | (c & 0xFFFFFF);
            }
        return true;
    }

    /** Walks a compound (and the ones inside it) keeping the byte arrays named Blocks and HeightMap. */
    private static void readCompound(DataInputStream in, byte[][] found, int depth) throws IOException {
        while (true) {
            int type = in.readByte();
            if (type == 0) return;
            String name = in.readUTF();
            readTag(in, type, name, found, depth);
        }
    }

    private static void readTag(DataInputStream in, int type, String name, byte[][] found, int depth) throws IOException {
        switch (type) {
            case 1: in.readByte(); break;
            case 2: in.readShort(); break;
            case 3: in.readInt(); break;
            case 4: in.readLong(); break;
            case 5: in.readFloat(); break;
            case 6: in.readDouble(); break;
            case 7: {
                int n = in.readInt();
                byte[] b = new byte[n];
                in.readFully(b);
                if ("Blocks".equals(name)) found[0] = b;
                else if ("HeightMap".equals(name)) found[1] = b;
                break;
            }
            case 8: in.readUTF(); break;
            case 9: {
                int t = in.readByte(), n = in.readInt();
                for (int i = 0; i < n; i++) readTag(in, t, null, found, depth + 1);
                break;
            }
            case 10: if (depth < 8) readCompound(in, found, depth + 1); else throw new IOException("too deep"); break;
            case 11: { int n = in.readInt(); for (int i = 0; i < n; i++) in.readInt(); break; }
            default: throw new IOException("tag " + type);
        }
    }

    // ---- on the GPU

    private static void upload(int[] map) {
        ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(N * N * 4);
        for (int c : map) b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        b.flip();
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (tex == 0) {
            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F); // clamp to edge
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, N, N, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    /**
     * Draws the far land over the sky (call inside the tracer's present pass, its depth image bound to image unit 0
     * and the full-screen triangle's vertex array bound). near: where the tracer's own picture ends.
     */
    public static void draw(Camera cam, int width, int height, int outW, int outH, float tanX, float tanY,
                            float near, float[] sun, float[] fog, float daylight, int worldTop) {
        if (!enabled || tex == 0 || mapX == Integer.MIN_VALUE || cam == null) return;
        if (program == 0) {
            program = ShaderUtil.loadGraphics("present.vert", "far.frag");
            if (program == 0) { enabled = false; status = "shader failed"; return; }
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false); // the sky's depth stays: clouds drawn after still show in front
        GL20.glUseProgram(program);
        GL20.glUniform2i(GL20.glGetUniformLocation(program, "uResolution"), width, height);
        GL20.glUniform2i(GL20.glGetUniformLocation(program, "uOutSize"), outW, outH);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "uTan"), tanX, tanY);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uCam"), (float) (cam.x - mapX), (float) cam.y, (float) (cam.z - mapZ));
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uFwd"), cam.fx, cam.fy, cam.fz);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uRight"), cam.rx, cam.ry, cam.rz);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uUp"), cam.ux, cam.uy, cam.uz);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uNear"), near);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uFar"), N * CELL * 0.5f);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uCell"), CELL);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uWorldTop"), worldTop);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uSun"), sun[0], sun[1], sun[2]);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uFog"), fog[0], fog[1], fog[2]);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uDaylight"), daylight);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "uMap"), 1);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(0);
    }
}
