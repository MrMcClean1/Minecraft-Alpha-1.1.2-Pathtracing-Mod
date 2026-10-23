package com.alexmcclellan.betapt.test;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;

/**
 * Alpha's test scene (-Dalphapt.scene=true): a row of every block on a stone platform high in the air, and the
 * camera held still looking at it. It turns every 20 seconds so all faces show: south, top, north, east, west.
 * The modpack test of the Beta version isn't ported.
 */
public final class ModTest {
    private ModTest() {}
    public static float forcedMining = -1f;
    private static final boolean SCENE = Boolean.getBoolean("alphapt.scene");
    private static boolean built;
    private static int ticks, ox, oz;
    private static final int OY = 100;

    public static boolean running() { return false; }
    public static String status() { return ""; }
    public static void onChat(String message) {}
    public static void afterTick(Minecraft mc) {}
    public static void afterWorld(Minecraft mc) {}
    public static void afterHud(Minecraft mc) {}

    public static void onTick(Minecraft mc) {
        if (!SCENE || mc.world == null || mc.player == null) return;
        if (!built) {
            built = true;
            ox = (int) Math.floor(mc.player.x);
            oz = (int) Math.floor(mc.player.z);
            int n = 0;
            for (int dx = -2; dx <= 44; dx++) for (int dz = -8; dz <= 8; dz++) {
                mc.world.setBlock(ox + dx, OY, oz + dz, 1);
                for (int dy = 1; dy <= 6; dy++) mc.world.setBlock(ox + dx, OY + dy, oz + dz, 0);
            }
            for (int id = 1; id < 256; id++) {
                Block b = Block.BY_ID[id];
                if (b == null || id == 8 || id == 9 || id == 10 || id == 11 || id == 51) continue; // liquids and fire spread
                int k = n++;
                mc.world.setBlockWithMetadata(ox + (k % 21) * 2, OY + 1, oz + (k / 21) * 3, id, 0);
            }
            com.alexmcclellan.betapt.BetaPT.LOGGER.info("Test scene: {} blocks at {} {} {}", n, ox, OY + 1, oz);
        }
        ticks++;
        int view = Integer.getInteger("alphapt.sceneView", -1);
        if (view < 0) view = (ticks / 400) % 5;
        double cx = ox + 20.5, cz = oz + 3.5;
        double[][] pos = {{cx, OY + 4.5, oz - 6.0, 0, 25}, {cx, OY + 14.0, cz, 0, 89}, {cx, OY + 4.5, oz + 13.0, 180, 25},
                {ox + 46.0, OY + 4.5, cz, 90, 25}, {ox - 4.0, OY + 4.5, cz, 270, 25}, {ox + 3.5, OY + 2.6, oz - 2.5, 20, 25}};
        double[] p = pos[view];
        mc.player.setPositionAndAngles(p[0], p[1], p[2], (float) p[3], (float) p[4]);
        mc.player.velocityX = mc.player.velocityY = mc.player.velocityZ = 0;
        ((com.alexmcclellan.betapt.mixin.EntityAccessor) mc.player).betapt$setFallDistance(0f);
        mc.world.ticks = 6000; // noon
    }
}
