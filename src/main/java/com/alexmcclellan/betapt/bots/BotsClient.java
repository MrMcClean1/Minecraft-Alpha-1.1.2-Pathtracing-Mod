package com.alexmcclellan.betapt.bots;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/** Client bits on Alpha: chat lines on screen (Alpha has no names or minimap to feed). */
final class BotsClient {
    private BotsClient() {}

    static Minecraft mc() { return (Minecraft) net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance(); }

    static void show(String msg) {
        Minecraft mc = mc();
        if (mc != null && mc.gui != null) mc.gui.addChatMessage(msg);
    }

    static String name(int id) { return null; }

    static void markers(World w) {}
}
