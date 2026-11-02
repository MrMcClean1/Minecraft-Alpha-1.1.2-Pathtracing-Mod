package com.alexmcclellan.betapt.render;

import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.OverworldChunkGenerator;

/**
 * Alpha's part of FarTerrain: a world's name and seed, and a private copy of the overworld generator for land not
 * made yet. Only its terrain and surface steps run, into a block array of our own: making a WorldChunk would light
 * it, and lighting queues updates on the world the game is using (a second thread there crashed Alpha's frame).
 */
public final class FarGen {
    private FarGen() {}

    /** A server's world in the cache: named by its spawn point (what a client knows that stays the same). */
    public static String key(World w) { return "server-" + w.spawnPointX + "_" + w.spawnPointZ; }

    public static long seed(World w) { return w.seed; }

    /** Called on the far terrain thread. */
    public static FarTerrain.Gen create(World world, long seed) throws Exception {
        OverworldChunkGenerator g = new OverworldChunkGenerator(world, seed);
        java.util.Random random = null;
        for (java.lang.reflect.Field f : OverworldChunkGenerator.class.getDeclaredFields())
            if (f.getType() == java.util.Random.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); random = (java.util.Random) f.get(g); }
        if (random == null) throw new IllegalStateException("no random in the generator");
        final java.util.Random rnd = random;
        byte[] blocks = new byte[32768];
        short[] ids = new short[32768];
        return (cx, cz) -> {
            // OverworldChunkGenerator.getChunk, without the WorldChunk (and its lighting) and the caves
            rnd.setSeed(cx * 341873128712L + cz * 132897987541L);
            java.util.Arrays.fill(blocks, (byte) 0);
            g.buildTerrain(cx, cz, blocks);
            g.buildSurfaces(cx, cz, blocks);
            for (int i = 0; i < 32768; i++) ids[i] = (short) (blocks[i] & 255);
            return ids;
        };
    }
}
