package com.alexmcclellan.betapt.render;

import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

/** The first-person hand and held item lit by the traced light (shared by the vanilla and StationAPI hand renderers). */
public final class HeldLight {
    private HeldLight() {}

    private static boolean traced() {
        PathTracer pt = PathTracer.INSTANCE;
        return pt.active() && pt.pathView() && pt.handLightValid;
    }

    /** Beta's own light level at your feet: replaced by 1, the traced colour is applied in colour() instead. */
    public static float brightness(World world, int x, int y, int z) {
        return traced() || EntityCapture.active ? 1f : world.getBrightness(x, y, z);
    }

    public static void colour(float r, float g, float b, float a) {
        if (traced() && !EntityCapture.active) {
            float[] l = PathTracer.INSTANCE.handLight;
            GL11.glColor4f(r * l[0], g * l[1], b * l[2], a);
        } else GL11.glColor4f(r, g, b, a);
    }
}
