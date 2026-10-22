package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * One average colour per block ID and metadata, taken from the top face of each block
 * in terrain.png (so it follows whatever texture pack is active).
 *
 * Only used by the M2 debug map. M3 replaces it with real per-pixel texturing.
 * Grass, leaves and tall grass are stored grey in terrain.png and tinted green at
 * draw time by Beta, so we apply a fixed green here.
 */
public final class BlockPalette {
    private int buffer;
    /** M8b: each block's average colour (meta 0) as 0xRRGGBB, for the Vulkan probe. */
    public static final int[] RGB = new int[StationCompat.MAX_ID];
    /**
     * M9: the texture pack's LabPBR maps for terrain.png (terrain_s.png: smoothness, F0/metal, glow;
     * terrain_n.png: bump normals), as ARGB scaled to the atlas size, or null when the pack has none.
     */
    public static int[] PBR_S, PBR_N;
    private int pbrSTex, pbrNTex;
    public int pbrSpecular() { return pbrSTex; }
    public int pbrNormal() { return pbrNTex; }
    public static boolean hasPbr() { return PBR_S != null || PBR_N != null; }

    private static int[] loadPackImage(Minecraft mc, String name, int w, int h) {
        try (java.io.InputStream in = Minecraft.class.getResourceAsStream(name)) { // Alpha 1.1.2_01 has no texture packs: the game jar's own files
            if (in == null) return null;
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            if (img == null) return null;
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) out[y * w + x] = img.getRGB(x * img.getWidth() / w, y * img.getHeight() / h);
            return out;
        } catch (Throwable t) {
            BetaPT.LOGGER.warn("Could not read {} from the texture pack: {}", name, t.toString());
            return null;
        }
    }

    /** An RGBA8 texture from ARGB pixels (nearest filtering), reusing tex if given. */
    private static int texture(int tex, int[] argb, int w, int h) {
        if (tex == 0) tex = GL11.glGenTextures();
        ByteBuffer b = BufferUtils.createByteBuffer(w * h * 4);
        for (int c : argb) b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        b.flip();
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        return tex;
    }

    /** M8c: terrain.png as ARGB pixels, for the Vulkan tracer. */
    public static int[] ATLAS;
    public static int ATLAS_W, ATLAS_H;

    public int buffer() { return buffer; }

    public void build(Minecraft mc) {
        int tex = mc.textureManager.load("/terrain.png");
        int prev2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        int srcFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if (StationCompat.PRESENT) { // StationAPI packs all textures into one atlas: use our 16x16 grid copy
            ByteBuffer grid = StationCompat.buildGrid(tex, px, w, h, srcFormat);
            if (grid != null) { px = grid; w = 16 * StationCompat.gridTile; h = StationCompat.gridRows * StationCompat.gridTile; }
        }
        int tile = w / 16;
        ATLAS_W = w;
        ATLAS_H = h;
        ATLAS = new int[w * h];
        for (int i = 0; i < w * h; i++)
            ATLAS[i] = (px.get(i * 4 + 3) & 255) << 24 | (px.get(i * 4) & 255) << 16 | (px.get(i * 4 + 1) & 255) << 8 | (px.get(i * 4 + 2) & 255);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev2D);
        // M9: LabPBR maps from the active texture pack; "none" = 1x1 flat normal / no specular
        PBR_S = loadPackImage(mc, "/terrain_s.png", w, h);
        PBR_N = loadPackImage(mc, "/terrain_n.png", w, h);
        pbrSTex = PBR_S != null ? texture(pbrSTex, PBR_S, w, h) : texture(pbrSTex, new int[]{0xFF000000}, 1, 1);
        pbrNTex = PBR_N != null ? texture(pbrNTex, PBR_N, w, h) : texture(pbrNTex, new int[]{0xFF8080FF}, 1, 1);
        if (hasPbr()) BetaPT.LOGGER.info("LabPBR maps found in the texture pack: specular {}, normal {}", PBR_S != null, PBR_N != null);

        FloatBuffer colors = BufferUtils.createFloatBuffer(StationCompat.MAX_ID * 16 * 4);
        for (int id = 0; id < StationCompat.MAX_ID; id++) {
            int src = Orientations.original(id);
            Block block = src < StationCompat.blockCount() ? Block.BY_ID[src] : null;
            for (int meta = 0; meta < 16; meta++) {
                float[] c = {0, 0, 0, 0};
                if (block != null) {
                    int t;
                    try {
                        t = block.getSprite(1, meta);
                    } catch (Throwable e) {
                        t = block.sprite;
                    }
                    c = average(px, w, tile, StationCompat.terrainTile(block, t));
                    if (id == 2 || id == 18 || id == 31) { // grass block top, leaves, tall grass
                        c[0] *= 0.55f; c[1] *= 0.85f; c[2] *= 0.40f;
                    }
                }
                if (meta == 0) RGB[id] = (Math.min(255, (int) (c[0] * 255)) << 16) | (Math.min(255, (int) (c[1] * 255)) << 8) | Math.min(255, (int) (c[2] * 255));
                colors.put(c);
            }
        }
        colors.flip();
        buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, colors, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        BetaPT.LOGGER.info("Block palette built from terrain.png ({}x{})", w, h);
    }

    private static float[] average(ByteBuffer px, int w, int tile, int t) {
        int ox = (t & 15) * tile, oy = (t >> 4) * tile;
        if (t < 0 || (oy + tile) * w * 4 > px.capacity()) return new float[]{0.5f, 0.5f, 0.5f, 1}; // tile outside the atlas
        float r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = 0; y < tile; y++) {
            for (int x = 0; x < tile; x++) {
                int i = ((oy + y) * w + ox + x) * 4;
                int a = px.get(i + 3) & 255;
                if (a < 128) continue;
                r += (px.get(i) & 255) / 255f;
                g += (px.get(i + 1) & 255) / 255f;
                b += (px.get(i + 2) & 255) / 255f;
                n++;
            }
        }
        if (n == 0) return new float[]{0, 0, 0, 0};
        return new float[]{r / n, g / n, b / n, 1};
    }
}
