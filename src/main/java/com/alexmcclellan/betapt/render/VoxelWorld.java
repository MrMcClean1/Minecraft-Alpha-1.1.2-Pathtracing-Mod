package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The GPU's copy of the world: every loaded block within 256 blocks of the player.
 *
 * Storage is one 3D texture, 512 x 128 x 512 blocks (32 x 32 chunks), 16 bits per
 * block: the block ID in the low 8 bits and its metadata (wool colour, log type,
 * water level...) in the next 4. That's 64 MB on a 20 GB card.
 *
 * The texture wraps around ("toroidal" addressing): world block (x, y, z) always
 * lives at texel (x mod 512, y, z mod 512). When the player walks, nothing shifts,
 * the chunks that come into range simply overwrite the slots of the ones that left.
 * A small table records which chunk each of the 32 x 32 slots currently holds, so the
 * shader can tell real data from a stale or empty slot.
 */
public final class VoxelWorld {
    public static final int SLOTS = 32;              // chunks per side
    public static final int SIZE = SLOTS * 16;       // blocks per side
    public static final int HEIGHT = 128;
    private static final int RADIUS = SLOTS / 2;     // chunks each way from the player
    private static final int UPLOADS_PER_FRAME = 96; // spreads a full reload over a few frames
    private static final int EMPTY = Integer.MIN_VALUE;

    private static final Set<WorldChunk> DIRTY = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private int texture;
    private int grassTexture, foliageTexture; // biome tint per column, 512 x 512, same wrapping as the blocks
    private int slotBuffer;
    private final WorldChunk[] slotChunk = new WorldChunk[SLOTS * SLOTS];
    /** M8c: bumped whenever a slot gets a new or changed chunk (or loses it), so the Vulkan tracer knows what to rebuild. */
    public final int[] slotVersion = new int[SLOTS * SLOTS];
    /** M8c: biome tints per slot, 0xRRGGBB per column (x * 16 + z), for the Vulkan tracer. */
    public final int[][] slotGrass = new int[SLOTS * SLOTS][256], slotFoliage = new int[SLOTS * SLOTS][256];
    public WorldChunk chunkIn(int slot) { return slotChunk[slot]; }
    /** M8c: this frame's light list, 4 floats per light (as uploaded to the shader). */
    public float[] lightFloats() {
        float[] f = new float[lightCount * 4];
        for (int i = 0; i < f.length; i++) f[i] = lightUpload.get(i);
        return f;
    }
    /** Light-emitting blocks in each slot's chunk, packed as x | z << 4 | y << 8 | id << 16. */
    private final int[][] slotLights = new int[SLOTS * SLOTS][];
    private final int[] lightScratch = new int[16 * 16 * HEIGHT];
    public static final int MAX_LIGHTS = 256;
    private int lightBuffer;
    public int lightCount;
    private final java.nio.FloatBuffer lightUpload = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
    private final int[] slotCoords = new int[SLOTS * SLOTS * 2]; // chunk x, chunk z per slot
    private final IntBuffer slotUpload = BufferUtils.createIntBuffer(SLOTS * SLOTS * 2);
    private final ShortBuffer chunkData = BufferUtils.createShortBuffer(16 * HEIGHT * 16);
    private final java.nio.ByteBuffer grassData = BufferUtils.createByteBuffer(16 * 16 * 4);
    private final java.nio.ByteBuffer foliageData = BufferUtils.createByteBuffer(16 * 16 * 4);
    private World lastWorld;

    public int uploadsLastFrame;
    /**
     * The Vulkan tracer wants every chunk this copy reads, too. Reading a StationAPI chunk goes block by
     * block and is slow, so the arrays read here are kept for it (by slot) instead of reading it twice.
     * readsWanted: when it last asked (System.nanoTime); nothing is kept once it stops asking.
     */
    public long readsWanted;
    private final java.util.HashMap<Integer, Object[]> reads = new java.util.HashMap<>();
    /** The ids and meta last read for this slot, if the Vulkan side hasn't taken them yet; else null. */
    public Object[] takeRead(int slot) { return reads.remove(slot); }
    public long totalUploads;
    public int loadedSlots;

    /** Called from ChunkMixin whenever a block or its metadata changes. */
    public static void markDirty(WorldChunk chunk) {
        if (DEBUG_DIRTY) DIRTY_COUNTS[0]++;
        if (chunk == lastMarked) return; // world generation sets blocks one by one: skip the set for runs in one chunk
        lastMarked = chunk;
        if (DIRTY.add(chunk)) CHANGES[0]++;
    }
    private static volatile WorldChunk lastMarked;

    /** Chunks by position (cx, cz packed): from Beta's renderer notifications, which also cover StationAPI's chunks. */
    private static final Set<Long> DIRTY_AT = Collections.synchronizedSet(new java.util.HashSet<>());
    private static long key(int cx, int cz) { return (long) cx << 32 | (cz & 0xFFFFFFFFL); }

    /** One whole chunk by position (StationAPI's chunk-section packets). */
    public static void markDirtyChunk(int cx, int cz) { if (DEBUG_DIRTY) DIRTY_COUNTS[1]++; DIRTY_AT.add(key(cx, cz)); }

    /** betapt.debugDirty: every 5 s, how often chunks were flagged changed and from where, and how many were re-read. */
    static final boolean DEBUG_DIRTY = Boolean.getBoolean("betapt.debugDirty");
    /**
     * Always counted (the test reports them): chunks flagged by a block change, chunks flagged by Beta's renderer
     * notices, flagged chunks re-read that had changed, and ones re-read that hadn't (skipped: nothing to send).
     */
    public static final long[] CHANGES = new long[4];
    static final long[] DIRTY_COUNTS = new long[7]; // block setters, chunk packets, renderer regions, re-reads; re-reads that were new, a replaced chunk object, flagged
    private static long dirtyLogAt;

    /** Called from WorldRendererMixin when blocks in this box changed (one block, or a region from the server). */
    public static void markDirty(int minX, int minZ, int maxX, int maxZ) {
        if (DEBUG_DIRTY) DIRTY_COUNTS[2]++;
        for (int cx = (minX - 1) >> 4; cx <= (maxX + 1) >> 4; cx++)
            for (int cz = (minZ - 1) >> 4; cz <= (maxZ + 1) >> 4; cz++) if (DIRTY_AT.add(key(cx, cz))) CHANGES[1]++;
    }

    public int texture() { return texture; }
    public int brickTexture() { return brickTexture; }

    /**
     * M7: a coarse "is anything in here?" map, one byte per 4 x 4 x 4 block brick (128 x 32 x 128).
     * Rays crossing open air check this first and jump a whole brick at a time instead of
     * stepping block by block: a sun ray climbing 50 blocks of sky takes ~13 hops, not 50.
     */
    private int brickTexture;
    private final java.nio.ByteBuffer brickData = org.lwjgl.BufferUtils.createByteBuffer(4 * (HEIGHT / 4) * 4);
    private final byte[] brickScratch = new byte[4 * (HEIGHT / 4) * 4];
    public int lightBuffer() { return lightBuffer; }
    public int grassTexture() { return grassTexture; }
    public int foliageTexture() { return foliageTexture; }
    public int slotBuffer() { return slotBuffer; }

    private void init() {
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_R16UI, SIZE, HEIGHT, SIZE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

        brickTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, brickTexture);
        GL42.glTexStorage3D(GL12.GL_TEXTURE_3D, 1, GL30.GL_R8UI, SIZE / 4, HEIGHT / 4, SIZE / 4);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

        grassTexture = makeTint();
        foliageTexture = makeTint();

        slotBuffer = GL15.glGenBuffers();
        lightBuffer = GL15.glGenBuffers();
        clearSlots();
        BetaPT.LOGGER.info("Voxel world allocated: {}x{}x{} blocks, {} MB", SIZE, HEIGHT, SIZE, SIZE * HEIGHT * SIZE * 2 / (1024 * 1024));
    }

    private static int makeTint() {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, SIZE, SIZE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    private void clearSlots() {
        for (int i = 0; i < slotChunk.length; i++) if (slotChunk[i] != null) slotVersion[i]++;
        java.util.Arrays.fill(slotChunk, null);
        java.util.Arrays.fill(slotLights, null);
        java.util.Arrays.fill(slotCoords, EMPTY);
    }

    /** Bring the GPU copy up to date around the given block position. Call once per frame. */
    public void update(World world, double camX, double camZ) {
        if (texture == 0) init();
        if (world != lastWorld) {
            clearSlots();
            DIRTY.clear();
            DIRTY_AT.clear();
            lastMarked = null;
            lastWorld = world;
        }
        boolean keep = System.nanoTime() - readsWanted < 1_000_000_000L;
        if (!keep && !reads.isEmpty()) reads.clear();
        int pcx = (int) Math.floor(camX) >> 4;
        int pcz = (int) Math.floor(camZ) >> 4;
        int budget = UPLOADS_PER_FRAME;
        int changeBudget = 8; // changed chunks more than 2 away (snow settling, leaf decay...): a few a frame, the rest later
        long frameStart = System.nanoTime();
        uploadsLastFrame = 0;
        loadedSlots = 0;

        int prev2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); // Beta tracks its own bound texture; put it back after
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

        // Walk outward from the player so nearby chunks always upload first.
        for (int ring = 0; ring <= RADIUS; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (dx >= RADIUS || dz >= RADIUS) continue; // window is [-16, 15]
                    int cx = pcx + dx, cz = pcz + dz;
                    int slot = (cx & (SLOTS - 1)) + (cz & (SLOTS - 1)) * SLOTS;

                    // isChunkLoaded first: getChunk() on an unloaded chunk would load or generate it.
                    WorldChunk chunk = world.isChunkLoaded(cx << 4, 64, cz << 4) ? world.getChunkAt(cx, cz) : null; // Alpha: getChunk takes block coordinates
                    if (chunk == null || chunk.chunkX != cx || chunk.chunkZ != cz) {
                        if (slotChunk[slot] != null) slotVersion[slot]++;
                        slotChunk[slot] = null;
                        slotCoords[slot * 2] = EMPTY;
                        slotCoords[slot * 2 + 1] = EMPTY;
                        continue;
                    }
                    boolean stale = slotChunk[slot] != chunk || DIRTY.contains(chunk) || DIRTY_AT.contains(key(cx, cz));
                    if (stale && slotChunk[slot] == chunk && ring > 2) { // a change, not a new chunk: stays flagged until its turn
                        if (changeBudget <= 0) stale = false;
                        else changeBudget--;
                    }
                    // StationAPI chunks are read block by block: cap the time spent per frame instead of the count
                    if (stale && budget > 0 && !StationCompat.legacy(chunk) && System.nanoTime() - frameStart > 4_000_000L) budget = 0;
                    if (stale && budget > 0) {
                        boolean wasNull = slotChunk[slot] == null, wasOther = !wasNull && slotChunk[slot] != chunk;
                        DIRTY.remove(chunk);
                        if (lastMarked == chunk) lastMarked = null;
                        DIRTY_AT.remove(key(cx, cz));
                        budget--;
                        // a change in a chunk already here: if what's in it is the same (a light change, a leaf's decay
                        // check, a block set to what it was), nothing goes to the GPU and Vulkan doesn't rebuild it
                        int[] lit = upload(chunk, cx & (SLOTS - 1), cz & (SLOTS - 1), slot, slotChunk[slot] == chunk);
                        if (slotChunk[slot] == chunk) CHANGES[lit != null ? 2 : 3]++;
                        if (lit == null) { if (slotChunk[slot] == chunk) loadedSlots++; continue; }
                        slotLights[slot] = lit;
                        slotChunk[slot] = chunk;
                        slotVersion[slot]++;
                        slotCoords[slot * 2] = cx;
                        slotCoords[slot * 2 + 1] = cz;
                        uploadsLastFrame++;
                        if (DEBUG_DIRTY) DIRTY_COUNTS[3]++;
                        if (DEBUG_DIRTY) DIRTY_COUNTS[wasNull ? 4 : wasOther ? 5 : 6]++;
                    }
                    if (slotChunk[slot] == chunk) loadedSlots++;
                }
            }
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev2D);
        if (DEBUG_DIRTY && System.nanoTime() - dirtyLogAt > 5_000_000_000L) {
            dirtyLogAt = System.nanoTime();
            BetaPT.LOGGER.info("dirty in 5 s: {} block sets, {} chunk packets, {} renderer regions -> {} chunk re-reads ({} new, {} replaced, {} flagged); {} still waiting, {} slots loaded",
                    DIRTY_COUNTS[0], DIRTY_COUNTS[1], DIRTY_COUNTS[2], DIRTY_COUNTS[3], DIRTY_COUNTS[4], DIRTY_COUNTS[5], DIRTY_COUNTS[6],
                    DIRTY.size() + DIRTY_AT.size(), loadedSlots);
            java.util.Arrays.fill(DIRTY_COUNTS, 0);
        }

        slotUpload.clear();
        slotUpload.put(slotCoords).flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, slotBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, slotUpload, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Grass and leaf colours for the chunk's 16 x 16 columns, the same way GrassBlock and
     * LeavesBlock compute them: temperature and rainfall from the biome source, looked up
     * in grasscolor.png / foliagecolor.png.
     */
    private void uploadBiomeTint(WorldChunk chunk, int sx, int sz) {
        grassData.clear();
        foliageData.clear();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int g = 0xFFFFFF, f = 0xFFFFFF; // Alpha 1.1.2_01 has no biome colours: its grass and leaf tiles are already green
                putRgb(grassData, g);
                putRgb(foliageData, f);
                slotGrass[sx + sz * SLOTS][x * 16 + z] = g;
                slotFoliage[sx + sz * SLOTS][x * 16 + z] = f;
            }
        }
        grassData.flip();
        foliageData.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, grassTexture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, sx * 16, sz * 16, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, grassData);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, foliageTexture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, sx * 16, sz * 16, 16, 16, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, foliageData);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture); // the caller is still uploading chunks
    }

    private static void putRgb(java.nio.ByteBuffer b, int rgb) {
        b.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
    }

    /**
     * Copy one 16 x 128 x 16 chunk into its slot. Beta stores blocks as [x][z][y]; the
     * texture wants x fastest, then y, then z. Returns the chunk's light-emitting blocks.
     */
    private final short[] readIds = new short[32768];
    private final byte[] readMeta = new byte[16384];

    private int[] upload(WorldChunk chunk, int sx, int sz, int slot, boolean onlyIfChanged) {
        short[] ids = readIds;
        byte[] meta = readMeta;
        StationCompat.read(chunk, ids, meta);
        long hash = contentHash(ids, meta);
        if (onlyIfChanged && hash == slotHash[slot]) return null;
        slotHash[slot] = hash;
        if (System.nanoTime() - readsWanted < 1_000_000_000L) reads.put(sx + sz * SLOTS, new Object[]{ids.clone(), meta.clone()});
        int lights = 0;
        chunkData.clear();
        java.util.Arrays.fill(brickScratch, (byte) 0);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < 16; x++) {
                    int i = x << 11 | z << 7 | y;
                    int m = (i & 1) == 0 ? meta[i >> 1] & 15 : meta[i >> 1] >> 4 & 15;
                    int id = ids[i] & 0xFFFF;
                    chunkData.put((short) (id | m << 12)); // 12-bit block ID, 4-bit metadata
                    if (id != 0) brickScratch[(x >> 2) + (y >> 2) * 4 + (z >> 2) * 4 * (HEIGHT / 4)] = 1;
                    if (StationCompat.luminance(id) > 0 && exposed(ids, x, y, z, id)) {
                        lightScratch[lights++] = x | z << 4 | y << 8 | id << 16;
                    }
                }
            }
        }
        chunkData.flip();
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, sx * 16, 0, sz * 16, 16, HEIGHT, 16,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, chunkData);
        brickData.clear();
        brickData.put(brickScratch).flip();
        int bound = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, brickTexture);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, sx * 4, 0, sz * 4, 4, HEIGHT / 4, 4,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, brickData);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, bound);
        uploadBiomeTint(chunk, sx, sz);
        totalUploads++;
        return java.util.Arrays.copyOf(lightScratch, lights);
    }

    private static long dbgAt;
    private final long[] slotHash = new long[SLOTS * SLOTS];

    /** What the tracers see of a chunk: ids, and metadata (for leaves only the kind: the decay-check bits don't show). */
    private static long contentHash(short[] ids, byte[] meta) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i] & 0xFFFF;
            int m = (i & 1) == 0 ? meta[i >> 1] & 15 : meta[i >> 1] >> 4 & 15;
            if (id == 18) m &= 3;
            h = (h ^ (id | m << 16)) * 0x100000001b3L;
        }
        return h;
    }

    /**
     * A light block only matters if light can get out of it: at least one neighbour is
     * see-through and not the same kind of block. This drops the inside of lava lakes
     * and buried lava, which otherwise flood the light list with thousands of entries.
     * Neighbours in other chunks count as exposed.
     */
    private static boolean exposed(short[] ids, int x, int y, int z, int id) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) return true;
            if (ny < 0 || ny >= HEIGHT) continue;
            int n = ids[nx << 11 | nz << 7 | ny] & 0xFFFF;
            boolean sameLiquid = (id == 10 || id == 11) && (n == 10 || n == 11);
            if (n != id && !sameLiquid && !StationCompat.opaque(n)) return true;
        }
        return false;
    }

    /**
     * Collect the light-emitting blocks within 64 blocks of the camera into a GPU list,
     * nearest first, positions relative to the camera's block. The shader picks from
     * this list to aim shadow rays at torches, lava, glowstone and so on.
     */
    public void gatherLights(int camBX, int camBY, int camBZ) { gatherLights(camBX, camBY, camBZ, new float[0]); }

    /** extra: moving lights (held torches...), 4 floats each: exact position relative to the camera block, block id. */
    public void gatherLights(int camBX, int camBY, int camBZ, float[] extra) {
        int pcx = camBX >> 4, pcz = camBZ >> 4;
        java.util.ArrayList<float[]> found = new java.util.ArrayList<>();
        for (int i = 0; i + 3 < extra.length; i += 4) {
            float x = extra[i], y = extra[i + 1], z = extra[i + 2];
            // the shader spreads a block light over (0.3..0.7, 0.2..0.7, 0.3..0.7) of its cell: centre it on the point
            found.add(new float[]{x - 0.5f, y - 0.45f, z - 0.5f, extra[i + 3], x * x + y * y + z * z});
        }
        for (int dz = -4; dz <= 4; dz++) {
            for (int dx = -4; dx <= 4; dx++) {
                int cx = pcx + dx, cz = pcz + dz;
                int slot = (cx & (SLOTS - 1)) + (cz & (SLOTS - 1)) * SLOTS;
                if (slotCoords[slot * 2] != cx || slotCoords[slot * 2 + 1] != cz || slotLights[slot] == null) continue;
                for (int packed : slotLights[slot]) {
                    float x = cx * 16 + (packed & 15) - camBX;
                    float z = cz * 16 + (packed >> 4 & 15) - camBZ;
                    float y = (packed >> 8 & 127) - camBY;
                    float d2 = x * x + y * y + z * z;
                    if (d2 > 64 * 64) continue;
                    found.add(new float[]{x, y, z, packed >>> 16, d2});
                }
            }
        }
        found.sort((a, b) -> Float.compare(a[4], b[4])); // nearest first: the shader favours the start of the list
        lightCount = Math.min(found.size(), MAX_LIGHTS);
        lightUpload.clear();
        for (int i = 0; i < lightCount; i++) {
            float[] l = found.get(i);
            lightUpload.put(l[0]).put(l[1]).put(l[2]).put(l[3]);
        }
        if (lightCount == 0) lightUpload.put(new float[]{0, -999, 0, 0});
        lightUpload.flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, lightBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, lightUpload, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }
}
