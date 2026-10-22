package com.alexmcclellan.betapt.render;

import net.minecraft.world.World;

/**
 * What Beta's Dimension class answered, for Alpha 1.1.2_01, which has one world and no weather: the overworld
 * always, clouds at 108, no rain, and Beta's own sunrise colour and time-of-day curve.
 */
public final class Dim {
    private Dim() {}

    public static boolean nether(World w) { return false; }
    public static int id(World w) { return 0; }
    public static float cloudHeight(World w) { return 108f; }
    public static float rain(World w, float tickDelta) { return 0f; }

    /** Beta's time of day curve for a given time (0 = sunrise... as World.getTimeOfDay). */
    public static float timeOfDay(long time, float tickDelta) {
        int i = (int) (time % 24000L);
        float f = (i + tickDelta) / 24000f - 0.25f;
        if (f < 0f) f++;
        if (f > 1f) f--;
        float g = f;
        f = 1f - (float) ((Math.cos(f * Math.PI) + 1.0) / 2.0);
        return g + (f - g) / 3f;
    }

    private static final float[] SUNRISE = new float[4];

    /** Beta's sunrise / sunset glow colour (rgb + strength), or null outside sunrise and sunset. */
    public static float[] sunrise(float timeOfDay) {
        float width = 0.4f, c = (float) Math.cos(timeOfDay * Math.PI * 2.0);
        if (c < -width || c > width) return null;
        float f = c / width * 0.5f + 0.5f;
        float a = 1f - (1f - (float) Math.sin(f * Math.PI)) * 0.99f;
        a *= a;
        SUNRISE[0] = f * 0.3f + 0.7f;
        SUNRISE[1] = f * f * 0.7f + 0.2f;
        SUNRISE[2] = f * f * 0.0f + 0.2f;
        SUNRISE[3] = a;
        return SUNRISE;
    }
}
