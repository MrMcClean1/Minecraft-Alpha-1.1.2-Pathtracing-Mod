package com.mrmcclean.betapt.test;

import net.minecraft.client.Minecraft;

/** The benchmark isn't ported to Alpha yet; its hooks run WorldShots (the history museum's pictures). */
public final class AutoTest {
    private AutoTest() {}
    public static boolean running() { return false; }
    public static String status() { return ""; }
    public static void onTick(Minecraft mc) { WorldShots.tick(mc); }
    public static void afterTick(Minecraft mc) {}
    public static void afterWorld(Minecraft mc) { WorldShots.frame(mc); }
    public static void afterHud(Minecraft mc) {}
}
