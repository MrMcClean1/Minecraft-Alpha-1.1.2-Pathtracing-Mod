package com.alexmcclellan.betapt.render;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLSync;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * The Vulkan tracer has its own copy of the block atlas, uploaded once. This keeps its animated tiles (water,
 * lava, fire, portals and modded ones like the Aether portal) moving: a few times a second the animated tiles
 * are read back from the OpenGL atlas into a pixel buffer, without waiting for the GPU, and handed over the
 * next time round (VkLive.setTiles).
 */
final class AnimatedTiles {
    private AnimatedTiles() {}

    // Beta's own animated tiles in terrain.png: portal, fire (2), still and flowing water, still and flowing lava
    private static final int[] VANILLA = {14, 31, 47, 205, 206, 207, 222, 223, 237, 238, 239, 254, 255};
    private static final int EVERY = 3; // frames between readbacks

    private static int fbo, pbo, frame, pendingTile, grid, gridW, gridH, cols;
    private static int[] pendingXY;
    private static GLSync fence;

    /** {rects (x, y, w, h per tile), pixels (ARGB, tile after tile)} when a fresh set is ready, else null. */
    static int[][] poll(Minecraft mc) {
        if (++frame % EVERY != 0 || BlockPalette.ATLAS == null) return null;
        int[][] ready = null;
        if (fence != null) {
            int r = GL32.glClientWaitSync(fence, 0, 0L);
            if (r != GL32.GL_ALREADY_SIGNALED && r != GL32.GL_CONDITION_SATISFIED) return null; // not done yet: next time
            GL32.glDeleteSync(fence);
            fence = null;
            ready = collect();
        }
        request(mc);
        return ready;
    }

    private static int[][] collect() {
        int n = pendingXY.length / 2, t = pendingTile;
        int[] rects = new int[n * 4], px = new int[n * t * t];
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, (long) gridW * gridH * 4, null);
        if (mapped != null) {
            // the grid (cols tiles a row) back into tile after tile
            IntBuffer ints = mapped.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            for (int i = 0; i < n; i++) {
                int gx = (i % cols) * t, gy = (i / cols) * t;
                for (int y = 0; y < t; y++) { ints.position((gy + y) * gridW + gx); ints.get(px, (i * t + y) * t, t); }
            }
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        if (mapped == null) return null;
        for (int i = 0; i < n; i++) { rects[i * 4] = pendingXY[i * 2]; rects[i * 4 + 1] = pendingXY[i * 2 + 1]; rects[i * 4 + 2] = t; rects[i * 4 + 3] = t; }
        return new int[][]{rects, px};
    }

    private static void request(Minecraft mc) {
        int tex, tile;
        int[] xy;
        if (StationCompat.gridTexture != 0) {
            tex = StationCompat.gridTexture;
            tile = StationCompat.gridTile;
            xy = StationCompat.animatedTiles();
        } else {
            tex = mc.textureManager.load("/terrain.png");
            tile = BlockPalette.ATLAS_W / 16;
            xy = new int[VANILLA.length * 2];
            for (int i = 0; i < VANILLA.length; i++) { xy[i * 2] = (VANILLA[i] & 15) * tile; xy[i * 2 + 1] = (VANILLA[i] >> 4) * tile; }
        }
        if (xy.length == 0 || tile <= 0) return;
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (w != BlockPalette.ATLAS_W || h != BlockPalette.ATLAS_H) return; // the Vulkan copy is a different layout: leave it
        int n = xy.length / 2;
        // every animated tile copied on the GPU into one small grid texture, then that read back in one go: one
        // glReadPixels per tile was a fifth of a frame in a big base on NVIDIA (driver work per call)
        int c = Math.max(1, Math.min(n, 4096 / tile)), gw = c * tile, gh = ((n + c - 1) / c) * tile;
        if (fbo == 0) { fbo = GL30.glGenFramebuffers(); pbo = GL15.glGenBuffers(); }
        if (grid == 0 || gw != gridW || gh != gridH) {
            if (grid != 0) GL11.glDeleteTextures(grid);
            grid = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, grid);
            org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, gw, gh);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            gridW = gw; gridH = gh;
        }
        cols = c;
        for (int i = 0; i < n; i++)
            org.lwjgl.opengl.GL43.glCopyImageSubData(tex, GL11.GL_TEXTURE_2D, 0, xy[i * 2], xy[i * 2 + 1], 0,
                    grid, GL11.GL_TEXTURE_2D, 0, (i % c) * tile, (i / c) * tile, 0, tile, tile, 1);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) gw * gh * 4, GL15.GL_STREAM_READ);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, grid, 0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        int prevAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0); GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glReadPixels(0, 0, gw, gh, org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlign);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        pendingXY = xy;
        pendingTile = tile;
    }

    /** Forget the GL objects' state (the Vulkan tracer was restarted or the atlas rebuilt). */
    static void reset() {
        if (fence != null) { GL32.glDeleteSync(fence); fence = null; }
    }
}
