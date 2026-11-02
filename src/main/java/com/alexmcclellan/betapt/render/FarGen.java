package com.alexmcclellan.betapt.render;

import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.OverworldChunkGenerator;

/**
 * Alpha's part of FarTerrain: a world's name and seed, and a private copy of the overworld generator for land not
 * made yet (Alpha's generator only reads the world, so a copy of its own is safe on the far terrain thread).
 */
public final class FarGen {
    private FarGen() {}

    /** A server's world in the cache: named by its spawn point (what a client knows that stays the same). */
    public static String key(World w) { return "server-" + w.spawnPointX + "_" + w.spawnPointZ; }

    public static long seed(World w) { return w.seed; }

    /** Called on the far terrain thread. */
    public static FarTerrain.Gen create(World world, long seed) {
        OverworldChunkGenerator g = new OverworldChunkGenerator(world, seed);
        short[] ids = new short[32768];
        return (cx, cz) -> {
            byte[] b = g.getChunk(cx, cz).blocks;
            for (int i = 0; i < 32768; i++) ids[i] = (short) (b[i] & 255);
            return ids;
        };
    }
}
