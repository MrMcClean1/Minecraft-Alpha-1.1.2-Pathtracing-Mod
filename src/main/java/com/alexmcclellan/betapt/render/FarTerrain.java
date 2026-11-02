package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Far terrain (the Distant Horizons idea, for Beta and Alpha): past the path tracer's own range, the land out to
 * about 1000 blocks is drawn as a height map, one block to a cell (far.frag marches it over the sky, with smooth
 * slopes, sun shadows and water).
 *
 * Where each chunk's top blocks come from, best first:
 *  - chunks the game has loaded (single player, servers and StationAPI packs alike: VoxelWorld hands every chunk
 *    it reads to noteChunk), kept in a cache on disk (.minecraft/betapt-far/<world>/) so a server's land stays
 *    after you leave it;
 *  - the world's saved chunks (single player: Beta's .mcr region files, Alpha's c.x.z.dat files);
 *  - land not made yet, generated from the world's seed by a private copy of the terrain generator (FarGen; the
 *    shape only: no trees or ores, the game still makes the real chunk when you get there). On a server the seed
 *    isn't sent, so this needs serverSeed in config/betapt-far.properties.
 * Same file in Beta and Alpha; FarGen is each version's part.
 */
public final class FarTerrain {
    private FarTerrain() {}

    /** The map: N x N cells of one block, kept as a ring (a chunk's cells always sit at (x & N-1, z & N-1)). */
    static final int N = 2048, CH = N / 16, HALF = CH / 2, RANGE = 1000;

    public static boolean enabled, generate = true;
    public static Long serverSeed;
    static { load(); }

    /** A version's terrain generator: block ids of a new chunk (x << 11 | z << 7 | y), or null. */
    public interface Gen { short[] chunk(int cx, int cz); }

    private static final ConcurrentHashMap<Long, int[]> cols = new ConcurrentHashMap<>();     // chunk -> 256 columns + source
    private static final ConcurrentHashMap<Long, int[]> genCache = new ConcurrentHashMap<>(); // generated, from the disk cache
    private static final ConcurrentLinkedQueue<Long> dirty = new ConcurrentLinkedQueue<>();
    private static final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();
    private static final Set<Long> loadedRegions = ConcurrentHashMap.newKeySet();
    private static final Set<Long> tried = ConcurrentHashMap.newKeySet();
    private static final int LIVE = 1, GENERATED = 2;

    private static volatile String key;            // the world's name in the cache
    private static volatile File saveDir;          // single player: where its chunks are saved
    private static volatile java.util.function.Supplier<Gen> genFactory;
    private static volatile Gen gen;               // the worker's generator (null: none, or it failed)
    private static volatile int centerX, centerZ;  // the player's chunk
    private static volatile boolean live;          // overworld and on: loaded chunks are noted
    private static Thread worker;
    public static volatile String status = "off";
    private static volatile int generated, fromSave, fromCache, noted;

    // ---- the settings (config/betapt-far.properties, the same in every version)

    private static File configFile() { return new File("config", "betapt-far.properties"); }

    private static Properties props() {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(configFile())) { p.load(in); } catch (Exception ignored) {}
        return p;
    }

    private static void load() {
        Properties p = props();
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
        generate = Boolean.parseBoolean(p.getProperty("generate", "true"));
        String s = p.getProperty("serverSeed", "").trim();
        try { serverSeed = s.isEmpty() ? null : Long.parseLong(s); } catch (NumberFormatException e) { serverSeed = (long) s.hashCode(); } // a text seed, as the game takes it
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        Properties p = props();
        p.setProperty("enabled", Boolean.toString(on));
        p.putIfAbsent("generate", Boolean.toString(generate));
        p.putIfAbsent("serverSeed", "");
        try {
            configFile().getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(configFile())) {
                p.store(out, "Far terrain past the path tracer's range. generate: make land you haven't been to from the seed. serverSeed: a server's seed, if you know it");
            }
        } catch (Exception ignored) {}
        if (!on) { live = false; status = "off"; }
    }

    // ---- where a single player world is saved: the first File field of the world or of its storage

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

    private static Object lastWorld;

    /**
     * Once a frame from the tracer. mpKey: the world's name when it isn't saved here (a server); seed: the world's
     * seed in single player (a server's is serverSeed); gen: makes this version's generator for a seed.
     */
    public static void tick(Object world, boolean overworld, double camX, double camZ, String mpKey, long seed,
                            java.util.function.LongFunction<Gen> genFor) {
        live = enabled && overworld && world != null;
        if (!live) { status = enabled ? "overworld only (the Nether has no sky to show it in)" : "off"; return; }
        if (world != lastWorld) {
            lastWorld = world;
            File dir = saveDir(world);
            String k = (dir != null ? "sp-" + dir.getName() : mpKey).replaceAll("[^A-Za-z0-9._-]", "_");
            Long s = dir != null ? Long.valueOf(seed) : serverSeed;
            if (!k.equals(key)) {
                saveDir = dir;
                genFactory = generate && s != null && genFor != null ? () -> genFor.apply(s) : null;
                Arrays.fill(slotOwner, Long.MIN_VALUE); // every slot gets this world's chunk (or nothing)
                key = k;
            }
        }
        centerX = (int) Math.floor(camX) >> 4;
        centerZ = (int) Math.floor(camZ) >> 4;
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(FarTerrain::work, "BetaPT far terrain");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        uploadSome();
    }

    // ---- a chunk's columns: 256 x (height | water << 7) << 24 | colour, then where it came from

    private static long ck(int cx, int cz) { return (long) cx << 32 | (cz & 0xFFFFFFFFL); }
    private static int ckx(long k) { return (int) (k >> 32); }
    private static int ckz(long k) { return (int) k; }

    /** From a chunk's block ids (x << 11 | z << 7 | y, any height). */
    static int[] columns(short[] ids, int source) {
        int h = ids.length / 256;
        int[] rgb = BlockPalette.RGB;
        int[] out = new int[257];
        boolean any = false;
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int base = x * 16 * h + z * h, y = h - 1, id = 0;
                for (; y >= 0; y--) {
                    id = ids[base + y] & 0xFFFF;
                    if (id == 0 || id == 31 || id == 32 || id == 37 || id == 38 || id == 39 || id == 40 || id == 50 || id == 6
                            || id == 55 || id == 59 || id == 66 || id == 75 || id == 76 || id == 83 || id == 106) continue;
                    break;
                }
                if (y < 0) continue;
                boolean water = id == 8 || id == 9;
                int c;
                if (water) c = 0x2F5BC8;            // its texture is grey, tinted blue in game
                else if (id == 2) c = 0x6A9A3C;     // grass and leaves: tinted by the biome in game
                else if (id == 18) c = 0x3C6E28;
                else if (id == 78 || id == 80) c = 0xF0F4F8;
                else c = id < rgb.length ? rgb[id] & 0xFFFFFF : 0x808080;
                if (c == 0) c = 0x808080;
                int top = Math.max(1, Math.min(127, y + 1));
                out[z * 16 + x] = (top | (water ? 128 : 0)) << 24 | c;
                any = true;
            }
        out[256] = source;
        return any ? out : null;
    }

    /** Every chunk VoxelWorld reads (what the game has loaded: the real thing, trees and buildings and all). */
    public static void noteChunk(int cx, int cz, short[] ids) {
        if (!live || key == null) return;
        int[] c = columns(ids, LIVE);
        if (c == null) return;
        long k = ck(cx, cz);
        int[] old = cols.put(k, c);
        if (old == null || !Arrays.equals(old, c)) {
            dirty.add(k);
            dirtyRegions.add(ck(cx >> 5, cz >> 5));
            noted++;
        }
    }

    // ---- the background thread: fills in the chunks around the player, nearest first

    private static void work() {
        String doing = null;
        long lastFlush = System.currentTimeMillis();
        int lastCx = Integer.MIN_VALUE, lastCz = 0, ring = 0;
        while (true) {
            try {
                if (!live) { Thread.sleep(500); continue; }
                String k = key;
                if (!Objects.equals(k, doing)) { // another world: save the last one's cache, start over
                    if (doing != null) flush(doing);
                    cols.clear(); genCache.clear(); loadedRegions.clear(); tried.clear(); dirtyRegions.clear(); regionHeads.clear();
                    doing = k; gen = null; ring = 0; generated = fromSave = fromCache = noted = 0;
                    java.util.function.Supplier<Gen> f = genFactory;
                    if (f != null) {
                        try { gen = f.get(); } catch (Throwable e) { BetaPT.LOGGER.warn("Far terrain: no generator ({})", e.toString()); }
                    }
                }
                int cx0 = centerX, cz0 = centerZ;
                if (Math.abs(cx0 - lastCx) > 1 || Math.abs(cz0 - lastCz) > 1) { lastCx = cx0; lastCz = cz0; ring = 0; }
                // the nearest ring that still has a chunk missing
                boolean did = false;
                while (ring < HALF && !did) {
                    boolean left = false;
                    for (int dz = -ring; dz <= ring && !did; dz++)
                        for (int dx = -ring; dx <= ring && !did; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || dx >= HALF || dz >= HALF) continue;
                            long ck = ck(cx0 + dx, cz0 + dz);
                            if (cols.containsKey(ck) || tried.contains(ck)) continue;
                            did = fill(cx0 + dx, cz0 + dz, ck);
                            left = true;
                        }
                    if (!did && !left) ring++;
                }
                if (System.currentTimeMillis() - lastFlush > 30_000) {
                    lastFlush = System.currentTimeMillis();
                    flush(doing);
                    if (cols.size() > 40_000) trim(cx0, cz0);
                    BetaPT.LOGGER.info("Far terrain: {}", status);
                }
                status = cols.size() + " chunks (" + noted + " visited, " + fromSave + " saved, " + fromCache + " cached, " + generated + " generated"
                        + (gen == null ? (saveDir == null && serverSeed == null ? "; set serverSeed to generate the rest" : generate ? "" : "; generating off") : "") + ")";
                if (!did) Thread.sleep(400);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                BetaPT.LOGGER.warn("Far terrain: {}", e.toString());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** One missing chunk: from the cache, the save, or the generator. True if it found one. */
    private static boolean fill(int cx, int cz, long ck) {
        long rk = ck(cx >> 5, cz >> 5);
        if (loadedRegions.add(rk)) loadRegion(cx >> 5, cz >> 5);
        if (cols.containsKey(ck)) return true;
        File dir = saveDir;
        int[] c = null;
        if (dir != null) {
            short[] ids = readSaved(dir, cx, cz);
            if (ids != null && (c = columns(ids, LIVE)) != null) fromSave++;
        }
        if (c == null && (c = genCache.remove(ck)) != null) fromCache++;
        Gen g = gen;
        if (c == null && g != null) {
            try {
                short[] ids = g.chunk(cx, cz);
                if (ids != null && (c = columns(ids, GENERATED)) != null) generated++;
            } catch (Throwable e) {
                BetaPT.LOGGER.warn("Far terrain: the generator failed, land you haven't visited won't show", e);
                gen = null;
            }
        }
        if (c == null) { tried.add(ck); return false; }
        if (cols.putIfAbsent(ck, c) == null) {
            dirty.add(ck);
            dirtyRegions.add(rk);
        }
        return true;
    }

    private static void trim(int cx0, int cz0) {
        for (Iterator<Long> it = cols.keySet().iterator(); it.hasNext(); ) {
            long k = it.next();
            if (Math.abs(ckx(k) - cx0) > HALF + 16 || Math.abs(ckz(k) - cz0) > HALF + 16) it.remove();
        }
        genCache.clear();
        loadedRegions.clear();
        tried.clear();
    }

    // ---- the cache on disk: one gzip file per 32 x 32 chunks

    private static File cacheDir(String k) { return new File("betapt-far", k); }

    private static void loadRegion(int rx, int rz) {
        String k = key;
        if (k == null) return;
        File f = new File(cacheDir(k), "r." + rx + "." + rz + ".bin");
        if (!f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            if (in.readInt() != 0x46415231) return; // "FAR1"
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int idx = in.readShort(), src = in.readByte();
                int[] c = new int[257];
                for (int j = 0; j < 256; j++) c[j] = in.readInt();
                c[256] = src;
                long ck = ck(rx * 32 + (idx & 31), rz * 32 + (idx >> 5 & 31));
                // single player: generated land only stands in for what the save may have by now
                if (src == GENERATED && saveDir != null) genCache.put(ck, c);
                else if (cols.putIfAbsent(ck, c) == null) { dirty.add(ck); fromCache++; }
            }
        } catch (Exception e) {
            BetaPT.LOGGER.warn("Far terrain: cache {} unreadable ({})", f.getName(), e.toString());
        }
    }

    private static void flush(String k) {
        if (k == null) return;
        File dir = cacheDir(k);
        for (Iterator<Long> it = dirtyRegions.iterator(); it.hasNext(); ) {
            long rk = it.next();
            it.remove();
            int rx = ckx(rk), rz = ckz(rk);
            List<int[]> list = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                long ck = ck(rx * 32 + (i & 31), rz * 32 + (i >> 5));
                int[] c = cols.get(ck);
                if (c == null) c = genCache.get(ck);
                if (c != null) { list.add(c); idx.add(i); }
            }
            if (list.isEmpty()) continue;
            try {
                dir.mkdirs();
                File tmp = new File(dir, "r." + rx + "." + rz + ".tmp"), f = new File(dir, "r." + rx + "." + rz + ".bin");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))))) {
                    out.writeInt(0x46415231);
                    out.writeInt(list.size());
                    for (int i = 0; i < list.size(); i++) {
                        int[] c = list.get(i);
                        out.writeShort(idx.get(i));
                        out.writeByte(c[256]);
                        for (int j = 0; j < 256; j++) out.writeInt(c[j]);
                    }
                }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            } catch (Exception e) {
                BetaPT.LOGGER.warn("Far terrain: couldn't save its cache ({})", e.toString());
            }
        }
    }

    // ---- a saved chunk (single player)

    private static final Map<Long, int[]> regionHeads = new ConcurrentHashMap<>();

    private static short[] readSaved(File dir, int cx, int cz) {
        File region = new File(dir, "region");
        try {
            if (region.isDirectory()) { // Beta: 32 x 32 chunks a region file
                int rx = cx >> 5, rz = cz >> 5;
                File f = new File(region, "r." + rx + "." + rz + ".mcr");
                long rk = ck(rx, rz);
                int[] head = regionHeads.get(rk);
                if (head == null) {
                    head = new int[1024];
                    if (f.isFile() && f.length() >= 4096)
                        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 4096))) {
                            for (int i = 0; i < 1024; i++) head[i] = in.readInt() >>> 8;
                        }
                    regionHeads.put(rk, head);
                }
                int off = head[(cx & 31) + (cz & 31) * 32];
                if (off == 0) return null;
                try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                    r.seek(off * 4096L);
                    int len = r.readInt(), type = r.readByte();
                    if (len <= 1 || len > 1 << 20) return null;
                    byte[] data = new byte[len - 1];
                    r.readFully(data);
                    InputStream in = type == 1 ? new GZIPInputStream(new ByteArrayInputStream(data)) : new InflaterInputStream(new ByteArrayInputStream(data));
                    return nbtBlocks(new DataInputStream(new BufferedInputStream(in)));
                }
            }
            // Alpha (and Beta worlds never converted): one gzip file a chunk, in folders by x & 63, z & 63
            File f = new File(new File(new File(dir, Integer.toString(cx & 63, 36)), Integer.toString(cz & 63, 36)),
                    "c." + Integer.toString(cx, 36) + "." + Integer.toString(cz, 36) + ".dat");
            if (!f.isFile()) return null;
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                return nbtBlocks(new DataInputStream(in));
            }
        } catch (Exception e) {
            return null; // being written right now, or damaged: skip it
        }
    }

    /** A saved chunk's block ids: Beta and Alpha's Blocks array, or StationAPI's sections (a palette of block names). */
    @SuppressWarnings("unchecked")
    private static short[] nbtBlocks(DataInputStream in) throws IOException {
        if (in.readByte() != 10) return null;
        in.readUTF();
        Map<String, Object> root = (Map<String, Object>) tag(in, 10, 0);
        Map<String, Object> level = root.get("Level") instanceof Map ? (Map<String, Object>) root.get("Level") : root;
        Object b = level.get("Blocks");
        if (b instanceof byte[] && ((byte[]) b).length >= 32768) {
            byte[] bb = (byte[]) b;
            short[] ids = new short[bb.length];
            for (int i = 0; i < bb.length; i++) ids[i] = (short) (bb[i] & 255);
            return ids;
        }
        Object secs = level.get("stationapi:sections");
        if (!(secs instanceof List)) return null;
        short[] ids = new short[32768];
        for (Object o : (List<Object>) secs) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> sec = (Map<String, Object>) o;
            int sy = sec.get("y") instanceof Number ? ((Number) sec.get("y")).intValue() : -1;
            if (sy < 0 || sy > 7 || !(sec.get("block_states") instanceof Map)) continue;
            Map<String, Object> bs = (Map<String, Object>) sec.get("block_states");
            List<Object> pal = bs.get("palette") instanceof List ? (List<Object>) bs.get("palette") : null;
            if (pal == null || pal.isEmpty()) continue;
            short[] pid = new short[pal.size()];
            for (int i = 0; i < pid.length; i++)
                pid[i] = (short) (pal.get(i) instanceof Map ? idOf(String.valueOf(((Map<String, Object>) pal.get(i)).get("Name"))) : 0);
            long[] data = bs.get("data") instanceof long[] ? (long[]) bs.get("data") : null;
            int bits = data == null || data.length == 0 ? 0 : Math.max(1, data.length * 64 / 4096), per = bits == 0 ? 0 : 64 / bits;
            for (int i = 0; i < 4096; i++) {
                int p = 0;
                if (bits > 0) {
                    int w = i / per;
                    if (w < data.length) p = (int) (data[w] >>> ((i % per) * bits) & ((1L << bits) - 1));
                }
                int id = p < pid.length ? pid[p] : 0;
                int x = i & 15, z = i >> 4 & 15, y = sy * 16 + (i >> 8);
                ids[x << 11 | z << 7 | y] = (short) id;
            }
        }
        return ids;
    }

    private static final Map<String, Integer> NAMES = new HashMap<>();
    static {
        String[] n = {"air", "stone", "grass_block", "dirt", "cobblestone", "oak_planks", "sapling", "bedrock", "flowing_water", "water",
                "flowing_lava", "lava", "sand", "gravel", "gold_ore", "iron_ore", "coal_ore", "log", "leaves", "sponge", "glass", "lapis_ore",
                "lapis_block", "dispenser", "sandstone", "note_block", "bed", "powered_rail", "detector_rail", "sticky_piston", "cobweb",
                "grass", "dead_bush", "piston", "piston_head", "wool", "moving_piston", "dandelion", "rose", "brown_mushroom", "red_mushroom",
                "gold_block", "iron_block", "double_slab", "slab", "bricks", "tnt", "bookshelf", "mossy_cobblestone", "obsidian", "torch",
                "fire", "spawner", "oak_stairs", "chest", "redstone_wire", "diamond_ore", "diamond_block", "crafting_table", "wheat",
                "farmland", "furnace", "lit_furnace", "sign", "oak_door", "ladder", "rail", "cobblestone_stairs", "wall_sign", "lever",
                "stone_pressure_plate", "iron_door", "wooden_pressure_plate", "redstone_ore", "lit_redstone_ore", "redstone_torch",
                "lit_redstone_torch", "stone_button", "snow", "ice", "snow_block", "cactus", "clay", "sugar_cane", "jukebox", "oak_fence",
                "pumpkin", "netherrack", "soul_sand", "glowstone", "nether_portal", "jack_o_lantern", "cake"};
        for (int i = 0; i < n.length; i++) NAMES.put("minecraft:" + n[i], i);
        NAMES.put("minecraft:tall_grass", 31); NAMES.put("minecraft:fern", 31); NAMES.put("minecraft:snow_layer", 78);
        NAMES.put("minecraft:planks", 5); NAMES.put("minecraft:fence", 85); NAMES.put("minecraft:reeds", 83);
    }

    /** A StationAPI block name to Beta's id: vanilla by name, a mod's numbered blocks (mod_IC2:249) by their number. */
    static int idOf(String name) {
        Integer v = NAMES.get(name);
        if (v != null) return v;
        int c = name.lastIndexOf(':');
        try { return Integer.parseInt(name.substring(c + 1)) & 4095; } catch (NumberFormatException e) { return name.isEmpty() ? 0 : 1; }
    }

    /** A whole tag as plain Java: compounds as maps, lists as lists, arrays as arrays. */
    private static Object tag(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 16) throw new IOException("too deep");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readByte(), n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(tag(in, t, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) return m;
                    String name = in.readUTF();
                    m.put(name, tag(in, t, depth + 1));
                }
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("tag " + type);
        }
    }

    // ---- on the GPU: one N x N texture, each chunk at its ring slot

    private static int tex, program;
    private static final long[] slotOwner = new long[CH * CH];
    private static int sweep;
    private static ByteBuffer one;
    static { Arrays.fill(slotOwner, Long.MIN_VALUE); }

    private static void uploadSome() {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (tex == 0) {
            tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, N, N, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, org.lwjgl.BufferUtils.createByteBuffer(N * N * 4));
            one = org.lwjgl.BufferUtils.createByteBuffer(16 * 16 * 4);
        } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        int cx0 = centerX - HALF, cz0 = centerZ - HALF, budget = 256;
        // chunks that changed
        for (Long k; budget > 0 && (k = dirty.poll()) != null; ) {
            int cx = ckx(k), cz = ckz(k);
            if (cx < cx0 || cz < cz0 || cx >= cx0 + CH || cz >= cz0 + CH) continue;
            put(cx, cz, k);
            budget--;
        }
        // slots still holding a chunk from the other side of the ring (the player moved, or another world)
        for (int n = 0; n < 2048 && budget > 0; n++) {
            int s = sweep = (sweep + 1) % (CH * CH);
            int sx = s % CH, sz = s / CH;
            int cx = cx0 + ((sx - cx0) & (CH - 1)), cz = cz0 + ((sz - cz0) & (CH - 1));
            long k = ck(cx, cz);
            if (slotOwner[s] != k) { put(cx, cz, k); budget--; }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    private static void put(int cx, int cz, long k) {
        int[] c = cols.get(k);
        one.clear();
        for (int i = 0; i < 256; i++) {
            int v = c == null ? 0 : c[i];
            one.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v).put((byte) (v >>> 24));
        }
        one.flip();
        int sx = cx & (CH - 1), sz = cz & (CH - 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, sx * 16, sz * 16, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, one);
        slotOwner[sz * CH + sx] = k;
    }

    /**
     * Draws the far land over the sky (call inside the tracer's present pass, its depth image bound to image unit 0
     * and the full-screen triangle's vertex array bound). near: where the tracer's own picture ends.
     */
    public static void draw(Camera cam, int width, int height, int outW, int outH, float tanX, float tanY,
                            float near, float[] sun, float[] fog, float daylight, int worldTop) {
        if (!enabled || !live || tex == 0 || cam == null) return;
        if (program == 0) {
            program = ShaderUtil.loadGraphics("present.vert", "far.frag");
            if (program == 0) { enabled = false; status = "shader failed"; return; }
        }
        // the camera in the map's own coordinates: block coordinates less a whole number of maps (small floats)
        double ox = Math.floor(cam.x / N) * N, oz = Math.floor(cam.z / N) * N;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false); // the sky's depth stays: clouds drawn after still show in front
        GL20.glUseProgram(program);
        GL20.glUniform2i(GL20.glGetUniformLocation(program, "uResolution"), width, height);
        GL20.glUniform2i(GL20.glGetUniformLocation(program, "uOutSize"), outW, outH);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "uTan"), tanX, tanY);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uCam"), (float) (cam.x - ox), (float) cam.y, (float) (cam.z - oz));
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uFwd"), cam.fx, cam.fy, cam.fz);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uRight"), cam.rx, cam.ry, cam.rz);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "uUp"), cam.ux, cam.uy, cam.uz);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uNear"), near);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "uFar"), RANGE);
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
