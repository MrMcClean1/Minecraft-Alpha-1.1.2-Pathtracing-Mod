package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * StationAPI (the modpack's mod loader) changes two things the tracer reads directly:
 *  - terrain.png is no longer a 16x16 grid of tiles: every texture is packed into one big "game atlas"
 *    wherever it fits. We rebuild a 16x16 grid copy (tile index -> StationAPI sprite) so all our shaders
 *    keep working, and copy the tiles again on the GPU every other frame so water and lava stay animated.
 *  - chunks keep their blocks in sections instead of Beta's byte array, so blocks are read one by one.
 * Without StationAPI none of this runs. StationAPI is reached by reflection: it isn't a build dependency.
 */
public final class StationCompat {
    private StationCompat() {}

    public static final boolean PRESENT = classExists("net.modificationstation.stationapi.api.client.texture.atlas.Atlases");

    private static boolean classExists(String n) {
        try { Class.forName(n, false, StationCompat.class.getClassLoader()); return true; } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ atlas

    /** Our 16-tiles-wide copy of the terrain textures (0 = not built). */
    public static int gridTexture;
    public static int gridTile;
    private static int[] copies = new int[0]; // per tile: srcX, srcY, dstX, dstY, srcW, srcH (a sprite wider or taller than a tile, like the flowing lava and water, is scaled into its tile)
    private static int copyCount, frame;
    /** The game atlas the grid was built from; StationAPI rebuilds it on resource reloads (joining a server, for one). */
    private static int builtAtlas, builtW, builtH, readFbo, drawFbo;
    /** Set when the game atlas changed: PathTracer then rebuilds the palette (and with it the grid). */
    public static volatile boolean stale;

    /** Highest block ID + 1 the tracers handle (12 bits; StationAPI hands out IDs from 256 up for modded blocks). */
    public static final int MAX_ID = 4096;
    /** Rows of 16 tiles in the terrain grid (16 for Beta's terrain.png; more with StationAPI's modded textures). */
    public static int gridRows = 16;
    /** Modded items' textures as a grid like gui/items.png (ARGB, 16 tiles wide), for the traced hand; null without StationAPI. */
    public static int[] itemGrid;
    public static int itemGridW;

    private static Object atlas(String getter) throws Exception {
        Class<?> atlases = Class.forName("net.modificationstation.stationapi.api.client.texture.atlas.Atlases", true, StationCompat.class.getClassLoader());
        return atlases.getMethod(getter).invoke(null);
    }

    private static int atlasSize(Object atlas) {
        for (Class<?> c = atlas.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("size");
                f.setAccessible(true);
                return f.getInt(atlas);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) { break; }
        }
        return 256;
    }

    /**
     * Copies every sprite of a StationAPI atlas into a grid 16 tiles wide (tile index t at column t % 16,
     * row t / 16), from the game atlas pixels (RGBA, aw x ah). copiesOut gets {srcX, srcY, dstX, dstY, srcW, srcH} for
     * the tiles that can be re-copied as they are (same size). Returns {width, height, tile size} or null.
     */
    private static int[] gridFrom(Object atl, ByteBuffer atlasPx, int aw, int ah, ByteBuffer out, int tile, int rows, int[] copiesOut, int[] countOut) throws Exception {
        return gridFrom(atl, atlasPx, aw, ah, out, tile, rows, copiesOut, countOut, 0, rows * 16, null);
    }

    /** As above, reading the atlas's sprites 0..count-1 into grid tiles first..first+count-1 (cells: shared map, or null to make one). */
    private static int[] gridFrom(Object atl, ByteBuffer atlasPx, int aw, int ah, ByteBuffer out, int tile, int rows, int[] copiesOut, int[] countOut,
                                  int first, int count, int[] cellsIn) throws Exception {
        Method get = atl.getClass().getMethod("getTexture", int.class);
        int n = 0, missing = 0, w = tile * 16;
        Method gx = null, gy = null, gw = null, gh = null;
        int[] cells = cellsIn;
        int cw = aw, ch = ah; // one cell per atlas pixel: StationAPI's sprites aren't aligned to the tile size
        if (copiesOut != null && cells == null) { cells = new int[cw * ch]; java.util.Arrays.fill(cells, -1); }
        int n0 = countOut[0];
        n = n0;
        for (int i = 0; i < count; i++) {
            int t = first + i;
            if (t >= rows * 16) break;
            Object s;
            try { s = get.invoke(atl, i); } catch (Throwable e) { s = null; }
            if (s == null) { missing++; continue; }
            if (gx == null) { Class<?> sc = s.getClass(); gx = sc.getMethod("getX"); gy = sc.getMethod("getY"); gw = sc.getMethod("getWidth"); gh = sc.getMethod("getHeight"); }
            int sx, sy, sw, sh;
            try { sx = (Integer) gx.invoke(s); sy = (Integer) gy.invoke(s); sw = (Integer) gw.invoke(s); sh = (Integer) gh.invoke(s); }
            catch (Throwable e) { missing++; continue; } // a slot a mod reserved without a picture (BuildCraft's StationAPI port has some)
            if (sw <= 0 || sh <= 0 || sx < 0 || sy < 0 || sx + sw > aw || sy + sh > ah) { missing++; continue; }
            int dx = (t & 15) * tile, dy = (t >> 4) * tile;
            if (Boolean.getBoolean("betapt.debugCaptureUv")) BetaPT.LOGGER.info("sprite {} -> tile {} at {},{} {}x{}", i, t, sx, sy, sw, sh);
            if (cells != null) // which grid tile each spot of the game atlas belongs to (BlockCapture's texture lookups)
                for (int cy = sy; cy < Math.min(ch, sy + sh); cy++)
                    for (int cx = sx; cx < Math.min(cw, sx + sw); cx++) cells[cy * cw + cx] = t;
            for (int y = 0; y < tile; y++)
                for (int x = 0; x < tile; x++) {
                    int src = ((sy + y * sh / tile) * aw + sx + x * sw / tile) * 4, dst = ((dy + y) * w + dx + x) * 4;
                    for (int c = 0; c < 4; c++) out.put(dst + c, atlasPx.get(src + c));
                }
            if (copiesOut != null && sw >= tile && sh >= tile && sw <= tile * 4 && sh <= tile * 4 && n * 6 + 5 < copiesOut.length) {
                copiesOut[n * 6] = sx; copiesOut[n * 6 + 1] = sy; copiesOut[n * 6 + 2] = dx; copiesOut[n * 6 + 3] = dy; copiesOut[n * 6 + 4] = sw; copiesOut[n * 6 + 5] = sh; n++;
            }
        }
        if (cells != null) { atlasCells = cells; atlasCellsW = cw; atlasCellsH = ch; }
        countOut[0] = n; countOut[1] += missing;
        return new int[]{w, rows * tile, tile};
    }

    /**
     * Rebuilds the terrain grid (every sprite of StationAPI's terrain atlas, Beta's 256 tiles first, then the
     * modded ones) and the items grid, from the game atlas pixels. Returns the terrain grid's RGBA pixels
     * (16 * tile wide, gridRows * tile high), or null if StationAPI's atlas couldn't be read.
     */
    public static ByteBuffer buildGrid(int atlasTex, ByteBuffer atlasPx, int aw, int ah, int srcFormat) {
        builtAtlas = atlasTex; builtW = aw; builtH = ah; stale = false;
        try {
            Object terrain = atlas("getTerrain");
            Object s0 = terrain.getClass().getMethod("getTexture", int.class).invoke(terrain, 0);
            int tile = (Integer) s0.getClass().getMethod("getWidth").invoke(s0);
            if (tile <= 0 || tile > 512) return null;
            int count = Math.max(256, atlasSize(terrain));
            // Textures only StationAPI's JSON block models use (the StationAPI BuildCraft, say) have no tile number: they
            // sit in the game atlas by name. They get tiles after the numbered ones; only BlockCapture's lookups reach them.
            java.util.Set<Long> numbered = new java.util.HashSet<>();
            Method gt = terrain.getClass().getMethod("getTexture", int.class);
            for (int i = 0; i < count; i++) {
                try { Object sp = gt.invoke(terrain, i); if (sp != null) numbered.add(spot(sp)); } catch (Throwable ignored) {}
            }
            List<int[]> named = new ArrayList<>();
            for (int[] r : namedSprites(aw, ah)) if (!numbered.contains((long) r[0] << 32 | r[1])) named.add(r);
            int rows = (count + named.size() + 15) / 16;
            ByteBuffer out = BufferUtils.createByteBuffer(16 * tile * rows * tile * 4);
            int[] cp = new int[rows * 16 * 6], cnt = new int[2];
            int[] dims = gridFrom(terrain, atlasPx, aw, ah, out, tile, rows, cp, cnt, 0, count, null);
            int w0 = tile * 16;
            // tiles that are the atlas's purple "no texture" fill: a block capture that lands on one has the wrong UVs
            boolean[] miss = new boolean[count];
            for (int i = 0; i < count; i++) {
                int o = (((i >> 4) * tile + tile / 2) * w0 + (i & 15) * tile + tile / 2) * 4;
                miss[i] = (out.get(o) & 255) == 214 && (out.get(o + 1) & 255) == 127 && (out.get(o + 2) & 255) == 255;
            }
            missingTiles = miss;
            for (int k = 0; k < named.size(); k++) {
                int[] r = named.get(k);
                int t = count + k, dx = (t & 15) * tile, dy = (t >> 4) * tile, sx = r[0], sy = r[1], sw = r[2], sh = r[3];
                for (int y = 0; y < tile; y++)
                    for (int x = 0; x < tile; x++) {
                        int src = ((sy + y * sh / tile) * aw + sx + x * sw / tile) * 4, dst = ((dy + y) * w0 + dx + x) * 4;
                        for (int c = 0; c < 4; c++) out.put(dst + c, atlasPx.get(src + c));
                    }
                if (Boolean.getBoolean("betapt.debugCaptureUv")) BetaPT.LOGGER.info("named sprite -> tile {} at {},{} {}x{}", t, sx, sy, sw, sh);
                if (atlasCells != null)
                    for (int cy = sy; cy < Math.min(atlasCellsH, sy + sh); cy++)
                        for (int cx = sx; cx < Math.min(atlasCellsW, sx + sw); cx++) atlasCells[cy * atlasCellsW + cx] = t;
                if (sw >= tile && sh >= tile && sw <= tile * 4 && sh <= tile * 4 && cnt[0] * 6 + 5 < cp.length) { // can animate too
                    cp[cnt[0] * 6] = sx; cp[cnt[0] * 6 + 1] = sy; cp[cnt[0] * 6 + 2] = dx; cp[cnt[0] * 6 + 3] = dy; cp[cnt[0] * 6 + 4] = sw; cp[cnt[0] * 6 + 5] = sh; cnt[0]++;
                }
            }
            if (!named.isEmpty()) BetaPT.LOGGER.info("StationAPI: {} model textures added after the numbered ones", named.size());
            copies = cp; copyCount = cnt[0]; gridTile = tile; gridRows = rows;
            // which tiles actually animate is found by watching the game atlas for a moment (startAnimationScan)
            ByteBuffer base = BufferUtils.createByteBuffer(atlasPx.capacity());
            base.put(atlasPx.duplicate().clear()).flip();
            scanBase = base; scanFrame = 0; scanChanged = new boolean[copyCount];
            int w = dims[0], h = dims[1];
            dumpGrid(out, w, h, "stationapi-terrain.png");
            if (Boolean.getBoolean("betapt.debugAtlas")) dumpGrid(atlasPx, aw, ah, "game-atlas.png");
            if (gridTexture == 0) gridTexture = GL11.glGenTextures();
            int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, gridTexture);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            BetaPT.LOGGER.info("StationAPI: {} terrain textures rebuilt as a {}x{} grid from the {}x{} game atlas ({} missing)",
                    rows * 16 - cnt[1], w, h, aw, ah, cnt[1]);
            buildItemGrid(atlasPx, aw, ah);
            return out;
        } catch (Throwable e) {
            BetaPT.LOGGER.warn("StationAPI: could not read the terrain atlas: {}", e.getCause() != null ? e.getCause().toString() : e.toString(), e);
            return null;
        }
    }

    private static void buildItemGrid(ByteBuffer atlasPx, int aw, int ah) {
        try {
            Object items = atlas("getGuiItems");
            Object s0 = items.getClass().getMethod("getTexture", int.class).invoke(items, 0);
            int tile = (Integer) s0.getClass().getMethod("getWidth").invoke(s0);
            int rows = (Math.max(256, atlasSize(items)) + 15) / 16;
            ByteBuffer out = BufferUtils.createByteBuffer(16 * tile * rows * tile * 4);
            int[] dims = gridFrom(items, atlasPx, aw, ah, out, tile, rows, null, new int[2]);
            int[] argb = new int[dims[0] * dims[1]];
            for (int i = 0; i < argb.length; i++)
                argb[i] = (out.get(i * 4 + 3) & 255) << 24 | (out.get(i * 4) & 255) << 16 | (out.get(i * 4 + 1) & 255) << 8 | (out.get(i * 4 + 2) & 255);
            itemGrid = argb;
            itemGridW = dims[0];
        } catch (Throwable e) {
            BetaPT.LOGGER.warn("StationAPI: could not read the item atlas: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ texture indices of modded blocks and items

    private static Method spriteSheetGet, sheetIndex;
    private static final java.util.Map<Class<?>, Method> textureFile = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Method NONE;
    static { Method m = null; try { m = Object.class.getMethod("hashCode"); } catch (Exception ignored) {} NONE = m; }

    /**
     * Forge mods (IndustrialCraft 2, BuildCraft, RedPower, Equivalent Exchange) draw from their own texture
     * sheets (getTextureFile()); Apron packs each sheet into StationAPI's atlas. This turns a block's or item's
     * own texture index into the atlas index, the way Apron does when Beta draws it. Anything else: unchanged.
     */
    private static int mapIndex(Object owner, int raw, String atlasGetter) {
        if (!PRESENT || owner == null) return raw;
        try {
            Method tf = textureFile.computeIfAbsent(owner.getClass(), c -> {
                try { Method m = c.getMethod("getTextureFile"); return m.getReturnType() == String.class ? m : NONE; }
                catch (NoSuchMethodException e) { return NONE; }
            });
            if (tf == NONE) return raw;
            String file = (String) tf.invoke(owner);
            if (file == null || file.equals("/terrain.png") || file.equals("/gui/items.png")) return raw;
            if (spriteSheetGet == null) {
                Class<?> client = Class.forName("io.github.fabriccompatibilitylayers.forge.stapi.client.ForgeStAPIClient", true, StationCompat.class.getClassLoader());
                spriteSheetGet = client.getMethod("getSpriteSheet", String.class);
            }
            Object sheet = spriteSheetGet.invoke(null, file);
            if (sheet == null) return raw;
            if (sheetIndex == null)
                for (Method m : sheet.getClass().getMethods()) if (m.getName().equals("getTextureIndex") && m.getParameterCount() == 2) sheetIndex = m;
            return (Integer) sheetIndex.invoke(sheet, atlas(atlasGetter), raw);
        } catch (Throwable t) {
            return raw;
        }
    }

    /** A block face's tile in the terrain grid. */
    public static int terrainTile(Object block, int raw) { return mapIndex(block, raw, "getTerrain"); }

    /** An item's tile in the items grid. */
    public static int itemTile(Object item, int raw) { return mapIndex(item, raw, "getGuiItems"); }

    // ------------------------------------------------------------------ block tables beyond Beta's 256

    public static int blockCount() { return Math.min(MAX_ID, net.minecraft.block.Block.BY_ID.length); }

    public static int luminance(int id) {
        int[] l = net.minecraft.block.Block.LIGHT;
        id = Orientations.original(id);
        return id >= 0 && id < l.length ? l[id] : 0;
    }

    public static boolean opaque(int id) {
        boolean[] o = net.minecraft.block.Block.IS_SOLID;
        id = Orientations.original(id);
        return id >= 0 && id < o.length && o[id];
    }

    /** Saves the rebuilt grid as .minecraft/betapt/stationapi-terrain.png, to check tiles against terrain.png. */
    private static void dumpGrid(ByteBuffer px, int w, int h, String name) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < w * h; i++)
                img.setRGB(i % w, i / w, (px.get(i * 4 + 3) & 255) << 24 | (px.get(i * 4) & 255) << 16 | (px.get(i * 4 + 1) & 255) << 8 | (px.get(i * 4 + 2) & 255));
            java.nio.file.Path dir = com.alexmcclellan.betapt.config.BetaPTConfig.gameDir().resolve("betapt");
            java.nio.file.Files.createDirectories(dir);
            javax.imageio.ImageIO.write(img, "png", dir.resolve(name).toFile());
        } catch (Throwable t) {
            BetaPT.LOGGER.warn("StationAPI: could not save the tile grid: {}", t.toString());
        }
    }

    // Finding the animated tiles: a modpack atlas holds thousands of tiles, but only a few dozen move (water, lava,
    // fire, portals, a mod's machines). Re-copying all of them every other frame cost several milliseconds and the
    // Vulkan copy overflowed, so after a rebuild the game atlas is read twice and compared with the first copy.
    private static ByteBuffer scanBase;
    // frames after a rebuild at which the game atlas is compared (about 0.5 and 2 s at 60 fps; betapt.animScan=a,b)
    private static final int SCAN_FIRST, SCAN_LAST;
    static {
        int a = 30, b = 120;
        try { String[] v = System.getProperty("betapt.animScan", "").split(","); if (v.length == 2) { a = Integer.parseInt(v[0].trim()); b = Integer.parseInt(v[1].trim()); } } catch (Exception ignored) {}
        SCAN_FIRST = a; SCAN_LAST = Math.max(a + 1, b);
    }
    private static int scanFrame;
    private static boolean[] scanChanged;

    private static void scanAnimations(int betaTerrain) {
        scanFrame++;
        if (scanFrame != SCAN_FIRST && scanFrame != SCAN_LAST) return;
        int w = builtW, h = builtH;
        ByteBuffer now = BufferUtils.createByteBuffer(w * h * 4);
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, betaTerrain);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, now);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        int t = gridTile;
        if (Boolean.getBoolean("betapt.debugAnimScan")) {
            int diff = 0, firstX = -1, firstY = -1;
            for (int i = 0; i < w * h; i++) if (now.getInt(i * 4) != scanBase.getInt(i * 4)) { if (diff++ == 0) { firstX = i % w; firstY = i / w; } }
            BetaPT.LOGGER.info("anim scan frame {}: {} pixels differ in the {}x{} atlas, first at {} {}; texture {} built from {}", scanFrame, diff, w, h, firstX, firstY, betaTerrain, builtAtlas);
        }
        for (int i = 0; i < copyCount; i++) {
            if (scanChanged[i]) continue;
            int sx = copies[i * 6], sy = copies[i * 6 + 1], cw = copies[i * 6 + 4], ch = copies[i * 6 + 5];
            search:
            for (int y = 0; y < ch; y++)
                for (int x = 0; x < cw; x++) {
                    int o = ((sy + y) * w + sx + x) * 4;
                    if (now.getInt(o) != scanBase.getInt(o)) { scanChanged[i] = true; break search; }
                }
        }
        if (scanFrame < SCAN_LAST) return;
        int n = 0;
        for (boolean c : scanChanged) if (c) n++;
        if (n == 0) { // nothing moved: the game was paused (water and lava always animate). Look again later.
            scanBase = now; scanFrame = 0;
            return;
        }
        n = 0;
        int[] kept = new int[copyCount * 6];
        for (int i = 0; i < copyCount; i++)
            if (scanChanged[i]) { System.arraycopy(copies, i * 6, kept, n * 6, 6); n++; }
        BetaPT.LOGGER.info("StationAPI: {} of {} tiles animate; only those are copied each frame", n, copyCount);
        copies = kept; copyCount = n; scanBase = null; scanChanged = null;
    }

    private static long spot(Object sprite) throws Exception {
        return (long) (Integer) sprite.getClass().getMethod("getX").invoke(sprite) << 32 | (Integer) sprite.getClass().getMethod("getY").invoke(sprite);
    }

    /** Every sprite in StationAPI's game atlas by name: {x, y, width, height}. Empty if it can't be read. */
    private static List<int[]> namedSprites(int aw, int ah) {
        List<int[]> out = new ArrayList<>();
        try {
            ClassLoader cl = StationCompat.class.getClassLoader();
            Object manager = Class.forName("net.modificationstation.stationapi.api.client.StationRenderAPI", true, cl).getMethod("getBakedModelManager").invoke(null);
            Object id = Class.forName("net.modificationstation.stationapi.api.client.texture.atlas.Atlases", true, cl).getField("GAME_ATLAS_TEXTURE").get(null);
            Object atlasTex = null;
            for (Method m : manager.getClass().getMethods()) if (m.getName().equals("getAtlas") && m.getParameterCount() == 1) atlasTex = m.invoke(manager, id);
            if (atlasTex == null) return out;
            java.lang.reflect.Field f = atlasTex.getClass().getDeclaredField("sprites");
            f.setAccessible(true);
            for (Object sp : ((java.util.Map<?, ?>) f.get(atlasTex)).values()) {
                Object contents = sp.getClass().getMethod("getContents").invoke(sp);
                int x = (Integer) sp.getClass().getMethod("getX").invoke(sp), y = (Integer) sp.getClass().getMethod("getY").invoke(sp);
                int w = (Integer) contents.getClass().getMethod("getWidth").invoke(contents), h = (Integer) contents.getClass().getMethod("getHeight").invoke(contents);
                if (w > 0 && h > 0 && x >= 0 && y >= 0 && x + w <= aw && y + h <= ah) out.add(new int[]{x, y, w, h});
            }
        } catch (Throwable t) {
            BetaPT.LOGGER.warn("StationAPI: couldn't list the atlas's named textures: {}", t.toString());
        }
        return out;
    }

    private static java.util.Map<String, int[]> spriteByName;

    /**
     * The grid tile of a texture StationAPI knows by name (a BuildCraft pipe's per-direction texture, say), found by the
     * last part of its name; -1 if it isn't there. Needs the grid built (atlasCells).
     */
    public static int tileOfName(Object identifier) {
        if (identifier == null || atlasCells == null) return -1;
        try {
            String want = identifier.toString();
            want = want.substring(want.lastIndexOf(':') + 1);
            want = want.substring(want.lastIndexOf('/') + 1);
            if (spriteByName == null) {
                java.util.Map<String, int[]> m = new java.util.HashMap<>();
                ClassLoader cl = StationCompat.class.getClassLoader();
                Object manager = Class.forName("net.modificationstation.stationapi.api.client.StationRenderAPI", true, cl).getMethod("getBakedModelManager").invoke(null);
                Object id = Class.forName("net.modificationstation.stationapi.api.client.texture.atlas.Atlases", true, cl).getField("GAME_ATLAS_TEXTURE").get(null);
                Object atlasTex = null;
                for (Method mm : manager.getClass().getMethods()) if (mm.getName().equals("getAtlas") && mm.getParameterCount() == 1) atlasTex = mm.invoke(manager, id);
                java.lang.reflect.Field f = atlasTex.getClass().getDeclaredField("sprites");
                f.setAccessible(true);
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) f.get(atlasTex)).entrySet()) {
                    Object sp = e.getValue(), contents = sp.getClass().getMethod("getContents").invoke(sp);
                    int x = (Integer) sp.getClass().getMethod("getX").invoke(sp), y = (Integer) sp.getClass().getMethod("getY").invoke(sp);
                    int w = (Integer) contents.getClass().getMethod("getWidth").invoke(contents), h = (Integer) contents.getClass().getMethod("getHeight").invoke(contents);
                    String k = e.getKey().toString();
                    m.putIfAbsent(k.substring(Math.max(k.lastIndexOf('/'), k.lastIndexOf(':')) + 1), new int[]{x, y, w, h});
                }
                spriteByName = m;
            }
            int[] r = spriteByName.get(want);
            if (r == null) return -1;
            int cx = r[0] + r[2] / 2, cy = r[1] + r[3] / 2;
            if (cx >= atlasCellsW || cy >= atlasCellsH) return -1;
            return atlasCells[cy * atlasCellsW + cx];
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int[] atlasCells;
    private static int atlasCellsW, atlasCellsH;
    private static boolean[] missingTiles = new boolean[0];

    /**
     * The terrain tile under a texture coordinate as a block renderer gives it to the Tesselator: the game atlas
     * with StationAPI, terrain.png's 16 x 16 tiles without. -1 if it isn't on a known tile.
     */
    public static int tileAtUV(double u, double v) {
        if (u < 0 || v < 0 || u >= 1 || v >= 1) return -1;
        if (!PRESENT) return (int) (u * 16) + 16 * (int) (v * 16);
        if (atlasCells == null) return -1;
        int t = atlasCells[(int) (v * atlasCellsH) * atlasCellsW + (int) (u * atlasCellsW)];
        return t >= 0 && t < missingTiles.length && missingTiles[t] ? -1 : t;
    }

    /** The animated tiles in our grid (water, lava, fire, portals, modded ones): x, y per tile, gridTile pixels square. */
    public static int[] animatedTiles() {
        if (gridTexture == 0 || stale || scanBase != null) return new int[0]; // still finding out which ones move
        int[] out = new int[copyCount * 2];
        for (int i = 0; i < copyCount; i++) { out[i * 2] = copies[i * 6 + 2]; out[i * 2 + 1] = copies[i * 6 + 3]; }
        return out;
    }

    /** The terrain texture the tracer should sample: our grid with StationAPI, Beta's terrain.png otherwise. */
    public static int terrainTexture(int betaTerrain) {
        if (gridTexture == 0) return betaTerrain;
        if ((frame & 31) == 0 && (betaTerrain != builtAtlas
                || org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(betaTerrain, 0, GL11.GL_TEXTURE_WIDTH) != builtW
                || org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(betaTerrain, 0, GL11.GL_TEXTURE_HEIGHT) != builtH)) stale = true;
        if (stale) { frame++; return gridTexture; } // tiles moved: no copies until the grid is rebuilt
        if (scanBase != null) scanAnimations(betaTerrain);
        // animated tiles (water, lava, fire, portal) are redrawn into the game atlas: copy them over on the GPU
        // (every other frame; every 8th while it's still unknown which of the thousands of tiles animate)
        if ((frame++ & (scanBase != null ? 7 : 1)) == 0 && copyCount > 0) {
            // a framebuffer blit, not glCopyImageSubData: the game atlas's internal format can differ from ours
            if (readFbo == 0) { readFbo = org.lwjgl.opengl.GL30.glGenFramebuffers(); drawFbo = org.lwjgl.opengl.GL30.glGenFramebuffers(); }
            int prevRead = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            int prevDraw = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, readFbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, betaTerrain, 0);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, gridTexture, 0);
            for (int i = 0; i < copyCount; i++) {
                int sx = copies[i * 6], sy = copies[i * 6 + 1], dx = copies[i * 6 + 2], dy = copies[i * 6 + 3], sw = copies[i * 6 + 4], sh = copies[i * 6 + 5];
                org.lwjgl.opengl.GL30.glBlitFramebuffer(sx, sy, sx + sw, sy + sh, dx, dy, dx + gridTile, dy + gridTile,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            }
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevRead);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
        return gridTexture;
    }

    // ------------------------------------------------------------------ chunks

    /** True when the chunk keeps Beta's own block arrays (no StationAPI). */
    private static long readNanos; private static int reads;

    public static boolean legacy(WorldChunk c) { return c.blocks != null && c.blocks.length >= 32768 && c.blockMetadata != null && !PRESENT; }

    /**
     * The chunk's blocks in Beta's layout (index x << 11 | z << 7 | y, metadata as nibbles).
     * IDs up to MAX_ID - 1 (StationAPI's modded blocks are 256 and up); anything higher shows as stone.
     * A chest's metadata is the way it looks (ChestLooks): Beta works that out from its neighbours.
     */
    public static void read(WorldChunk c, short[] ids, byte[] meta) {
        if (legacy(c)) {
            byte[] b = c.blocks;
            for (int i = 0; i < 32768; i++) ids[i] = (short) (b[i] & 255);
            System.arraycopy(c.blockMetadata.data, 0, meta, 0, 16384);
            ChestLooks.mark(c, ids, meta);
            Orientations.mark(c, ids, meta);
            return;
        }
        long t0 = System.nanoTime();
        java.util.Arrays.fill(meta, (byte) 0);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < 128; y++) {
                    int i = x << 11 | z << 7 | y;
                    int id = c.getBlockAt(x, y, z);
                    if (id >= MAX_ID || id < 0) id = 1;
                    ids[i] = (short) id;
                    if (id != 0) {
                        int m = c.getBlockMetadataAt(x, y, z) & 15;
                        meta[i >> 1] |= (byte) ((i & 1) == 0 ? m : m << 4);
                    }
                }
        ChestLooks.mark(c, ids, meta);
        Orientations.mark(c, ids, meta);
        readNanos += System.nanoTime() - t0;
        if (++reads == 256) BetaPT.LOGGER.info("StationAPI: reading a chunk takes {} ms on average", String.format("%.2f", readNanos / 256 / 1e6));
    }
}
