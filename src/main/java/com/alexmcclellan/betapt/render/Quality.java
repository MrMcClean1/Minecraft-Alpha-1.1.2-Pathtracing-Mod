package com.alexmcclellan.betapt.render;

import net.minecraft.client.Minecraft;

/**
 * Graphics quality levels, from the fastest to the best looking: render scale, trace distance, bounces, the denoiser,
 * reflections, traced mobs and leaves. They're the settings that cost frame rate; the look (the presets Beta,
 * Faithful, Realistic and the Look slider), brightness and the effects are left alone. Anything changed by hand
 * makes it Custom. Auto tries the levels from the top and keeps the best one that holds the target frame rate.
 */
public enum Quality {
    VERY_LOW("Very Low", 0.50f, 64, 1, PathTracer.DENOISER_STANDARD, PathTracer.REFLECT_OFF, false, true),
    LOW("Low", 0.60f, 96, 1, PathTracer.DENOISER_STANDARD, PathTracer.REFLECT_WATER, false, true),
    MEDIUM("Medium", 0.75f, 128, 1, PathTracer.DENOISER_ADVANCED, PathTracer.REFLECT_WATER, true, false),
    HIGH("High", 0.85f, 192, 1, PathTracer.DENOISER_ADVANCED, PathTracer.REFLECT_WATER, true, false),
    VERY_HIGH("Very High", 1.00f, 248, 2, PathTracer.DENOISER_ADVANCED, PathTracer.REFLECT_ALL, true, false),
    /** For a fast ray tracing card (an RTX 4070 / 5070 class or better): a third bounce of light. */
    ULTRA("Ultra", 1.00f, 248, 3, PathTracer.DENOISER_ADVANCED, PathTracer.REFLECT_ALL, true, false);

    public final String label;
    final float scale;
    final int distance, bounces, denoiser, reflections;
    final boolean mobs, fastLeaves;

    Quality(String label, float scale, int distance, int bounces, int denoiser, int reflections, boolean mobs, boolean fastLeaves) {
        this.label = label; this.scale = scale; this.distance = distance; this.bounces = bounces; this.denoiser = denoiser;
        this.reflections = reflections; this.mobs = mobs; this.fastLeaves = fastLeaves;
    }

    public void apply(PathTracer pt) {
        pt.renderScale = scale;
        if (scale < 1f && pt.upscaler == PathTracer.UPSCALER_OFF) pt.upscaler = PathTracer.UPSCALER_FSR; // render scale needs an upscaler
        pt.traceDistance = distance;
        pt.bounces = bounces;
        pt.denoise = true;
        pt.denoiserMode = denoiser;
        pt.reflections = reflections;
        pt.entityShadows = mobs;
        pt.fastLeaves = fastLeaves;
    }

    boolean matches(PathTracer pt) {
        return Math.abs(pt.renderScale - scale) < 0.01f && pt.traceDistance == distance && pt.bounces == bounces && pt.denoise
                && pt.denoiserMode == denoiser && pt.reflections == reflections && pt.entityShadows == mobs && pt.fastLeaves == fastLeaves;
    }

    /** The level the current settings match, or null (Custom). */
    public static Quality of(PathTracer pt) {
        for (Quality q : values()) if (q.matches(pt)) return q;
        return null;
    }

    // ---------------------------------------------------------------- Auto: pick the level by the frame rate

    /** The frame rate Auto aims for (betapt.autoQualityFps, default 60). */
    public static final int TARGET_FPS = Integer.getInteger("betapt.autoQualityFps", 60);
    private static Quality trying;
    private static long settleUntil, measureUntil;
    private static int frames;
    private static String last = "";

    public static boolean autoRunning() { return trying != null; }
    /** What Auto last found, for the button ("" before it has run). */
    public static String lastResult() { return last; }

    /** Starts Auto: the levels are tried from Ultra down, about 3 seconds each, while you play or look on. */
    public static void startAuto(PathTracer pt) {
        trying = values()[values().length - 1]; // the best level first
        trying.apply(pt);
        settleUntil = System.nanoTime() + 1_200_000_000L; // FSR, the denoiser and the GPU copy settle first
        measureUntil = 0;
        frames = 0;
        last = "testing";
    }

    public static void cancelAuto() { trying = null; last = ""; }

    /** Each frame (GameRendererMixin). */
    public static void frame(Minecraft mc) {
        if (trying == null) return;
        PathTracer pt = PathTracer.INSTANCE;
        if (!pt.enabled) { trying = null; last = ""; return; }
        long now = System.nanoTime();
        if (now < settleUntil) return;
        if (measureUntil == 0) { measureUntil = now + 2_500_000_000L; frames = 0; return; }
        frames++;
        if (now < measureUntil) return;
        double fps = frames / 2.5;
        Quality q = trying;
        boolean good = fps >= TARGET_FPS - 3; // a few frames of slack: the measurement wobbles
        if (!good && q.ordinal() > 0) {
            trying = values()[q.ordinal() - 1];
            trying.apply(pt);
            settleUntil = now + 1_200_000_000L;
            measureUntil = 0;
            return;
        }
        trying = null;
        last = q.label + ", " + Math.round(fps) + " fps";
        com.alexmcclellan.betapt.config.PtSettings.save();
        if (mc.gui != null)
            mc.gui.addChatMessage("Path tracer quality: " + q.label + " (" + Math.round(fps) + " fps" + (good ? "" : ", below " + TARGET_FPS + " even at the lowest") + ")");
    }
}
