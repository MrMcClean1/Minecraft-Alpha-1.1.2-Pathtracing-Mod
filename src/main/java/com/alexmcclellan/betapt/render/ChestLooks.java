package com.alexmcclellan.betapt.render;

import net.minecraft.block.Block;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chests keep nothing in their metadata: Beta turns a chest's front away from a wall and draws two side by side as one
 * double chest, working that out from the blocks around it each time it draws one (ChestBlock.getTextureId with a
 * world). The block table is built per metadata in a world holding nothing but the block, so every traced chest faced
 * south and a double chest showed two single fronts.
 *
 * Each way a chest can look (its six face textures) gets a number here. Chunks are read with that number in each
 * chest's metadata (StationCompat.read), and the block table gives that metadata those faces. Look 0 is a chest on its
 * own with nothing around it, what the table had before.
 */
final class ChestLooks {
    static final int CHEST = 54;
    private static final int STONE = 1; // any opaque block: a wall behind the chest

    private static int[][] looks;

    private ChestLooks() {}

    /** Every way a chest can look: on its own (open, or a wall on one side), and either half of a double chest facing either way. */
    static synchronized int[][] all() {
        if (looks != null) return looks;
        List<int[]> out = new ArrayList<>();
        Block b = CHEST < Block.BY_ID.length ? Block.BY_ID[CHEST] : null;
        if (b != null) {
            SingleBlockView v = new SingleBlockView();
            v.id = CHEST;
            int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}}; // north, south, west, east
            add(out, b, v);
            for (int[] d : dirs) {
                v.clearAround();
                v.put(d[0], 64, d[1], STONE);
                add(out, b, v);
            }
            for (int[] half : dirs) { // the other half of a double chest on this side
                v.clearAround();
                v.put(half[0], 64, half[1], CHEST);
                add(out, b, v);
                for (int[] wall : dirs) {
                    if (wall[0] * half[0] + wall[1] * half[1] != 0) continue; // a wall beside the pair, not at its ends
                    v.clearAround();
                    v.put(half[0], 64, half[1], CHEST);
                    v.put(wall[0], 64, wall[1], STONE);
                    v.put(half[0] + wall[0], 64, half[1] + wall[1], STONE);
                    add(out, b, v);
                }
            }
        }
        if (out.size() > 16) out = out.subList(0, 16); // metadata has 4 bits
        looks = out.toArray(new int[0][]);
        return looks;
    }

    private static void add(List<int[]> out, Block b, WorldView v) {
        int[] t = faces(b, v, 0, 64, 0);
        if (t == null) return;
        for (int[] o : out) if (Arrays.equals(o, t)) return;
        out.add(t);
    }

    /** The six face textures Beta gives the chest at x, y, z of this world, or null if it can't say. */
    private static int[] faces(Block b, WorldView w, int x, int y, int z) {
        int[] t = new int[6];
        try {
            for (int s = 0; s < 6; s++) t[s] = b.getSprite(w, x, y, z, s);
        } catch (Throwable e) {
            return null;
        }
        return t;
    }

    /** Writes the look of each chest in this chunk into its metadata (which Beta leaves at 0 for chests). */
    static void mark(net.minecraft.world.chunk.WorldChunk c, short[] ids, byte[] meta) {
        WorldView w = world();
        if (w == null) return;
        int[][] all = null;
        Block b = null;
        int bx = c.chunkX << 4, bz = c.chunkZ << 4;
        for (int i = 0; i < 32768; i++) {
            if (ids[i] != CHEST) continue;
            if (all == null) {
                all = all();
                b = Block.BY_ID[CHEST];
                if (all.length < 2 || b == null) return;
            }
            int[] t = faces(b, w, bx + (i >> 11), i & 127, bz + (i >> 7 & 15));
            int m = 0;
            if (t != null) for (int k = 0; k < all.length; k++) if (Arrays.equals(all[k], t)) { m = k; break; }
            int j = i >> 1;
            meta[j] = (byte) ((i & 1) == 0 ? (meta[j] & 0xF0) | m : (meta[j] & 0x0F) | m << 4);
        }
    }

    private static WorldView world() {
        try {
            return ((net.minecraft.client.Minecraft) net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance()).world;
        } catch (Throwable t) {
            return null;
        }
    }
}
