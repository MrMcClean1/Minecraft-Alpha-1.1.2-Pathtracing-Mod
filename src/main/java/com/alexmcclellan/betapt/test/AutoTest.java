package com.alexmcclellan.betapt.test;

import net.minecraft.client.Minecraft;

/** The benchmark isn't ported to Alpha yet: these do nothing. */
public final class AutoTest {
    private AutoTest() {}
    public static boolean running() { return false; }
    public static String status() { return ""; }
    public static void onTick(Minecraft mc) {}
    public static void afterTick(Minecraft mc) {}
    public static void afterWorld(Minecraft mc) {}
    public static void afterHud(Minecraft mc) {}
}
