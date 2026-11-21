package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.client.Minecraft;

import java.io.File;

/**
 * Photo mode: the game pauses (PhotoScreen) and the tracer adds up hundreds of samples per pixel of the still
 * scene into one noise-free picture, saved as a PNG in screenshots/. Each sample is a full trace of the window at
 * native size with the camera nudged by a random fraction of a pixel (smooth edges) and, with depth of field on, the
 * ray started from a random spot on a lens and aimed through the plane in focus. The denoiser and the upscalers are
 * off meanwhile: averaging many samples does their job. The shot can also have its own time of day (sun angle).
 *
 * Off by default (Path Tracer > Effects > Photo, time-lapse...): the photo key does nothing until it is switched on.
 */
public final class PhotoMode {
    private PhotoMode() {}

    // ---- settings (betapt-settings.properties: photo.*)
    /** The photo key opens photo mode only when this is on. */
    public static boolean enabled = false;
    /** Samples per pixel for a finished still. */
    public static int samples = 256;
    public static final int[] SAMPLE_STEPS = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096};
    /** Lens radius in blocks: 0 = everything sharp (no depth of field). */
    public static float aperture = 0f;
    public static final float MAX_APERTURE = 0.5f;
    /** Time of day for the shot in ticks (0 = 6:00, 6000 = noon), or -1 for the world's own. */
    public static int sunTime = -1;

    // ---- one shot
    private static boolean active;
    /** Samples added up so far for the picture on screen. */
    public static int count;
    /** Distance to what the crosshair points at (blocks), where the picture is sharp. */
    public static float focus = 16f;
    /** The next photo frame measures the focus: the tracer's own ray distance at the centre of the picture. */
    public static boolean wantFocus;
    /** The time of day this shot (or a time-lapse frame) is drawn at, -1 = the world's own; set by TimeLapse too. */
    public static long timeOverride = -1;
    private static long keptTime = Long.MIN_VALUE;
    private static double cx, cy, cz;
    private static float cfx, cfy, cfz, keyAperture = -1, keyFocus = -1;
    private static long keyTime = Long.MIN_VALUE;
    private static boolean saved;
    /** Where the picture goes when it is finished (null: screenshots/<date>_photo.png). */
    private static File target;
    private static java.util.function.Consumer<File> onSaved;
    /** The last picture written, and a line for the screen about it. */
    public static volatile File lastSaved;
    public static volatile String status = "";

    public static boolean active() { return active; }
    /** True once the current picture has its full sample count and is saved (or being written). */
    public static boolean finished() { return saved; }

    /** Photo mode on: the scene starts adding up from the next frame. */
    public static void begin(Minecraft mc) {
        active = true;
        count = 0;
        saved = false;
        target = null; onSaved = null;
        keptTime = mc.world != null ? mc.world.ticks : Long.MIN_VALUE;
        timeOverride = sunTime;
        focusFromCrosshair(mc);
        wantFocus = true; // and then exactly, from the traced picture
        status = "";
    }

    /** Photo mode off: the world's own time comes back. */
    public static void end(Minecraft mc) {
        if (!active) return;
        active = false;
        count = 0;
        if (keptTime != Long.MIN_VALUE && mc.world != null) mc.world.ticks = keptTime;
        keptTime = Long.MIN_VALUE;
        timeOverride = -1;
    }

    /** The next finished picture goes to this file, then the callback runs (TimeLapse: one numbered frame each). */
    public static void next(File file, java.util.function.Consumer<File> then) {
        target = file; onSaved = then; saved = false; count = 0;
    }

    /** Start the picture over (a setting changed). */
    public static void restart() { count = 0; saved = false; }

    /** Every frame before the trace: hold the shot's time of day (a server's time packets would move it). */
    public static void applyTime(Minecraft mc) {
        if (active && timeOverride >= 0 && mc.world != null) mc.world.ticks = timeOverride;
    }

    /** Focus on what the crosshair points at (the first block that isn't air along the view), up to the tracer's range. */
    public static void focusFromCrosshair(Minecraft mc) {
        if (mc.player == null || mc.world == null) return;
        // your y is at eye height in Alpha
        double yaw = Math.toRadians(mc.player.yaw), pitch = Math.toRadians(mc.player.pitch);
        double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
        net.minecraft.world.World w = mc.world;
        focus = firstHit(mc.player.x, mc.player.y, mc.player.z, dx, dy, dz, Math.max(16f, PathTracer.INSTANCE.maxDist),
                (x, y, z) -> y >= 0 && y < 128 && w.getBlock(x, y, z) != 0);
    }

    /** The traced distance at the picture's centre (-1: sky, then the far end of the range). */
    static void focusFromDepth(float t, float range) {
        wantFocus = false;
        focus = t > 0.05f && t < range * 0.99f ? t : Math.max(16f, range);
    }

    interface Solid { boolean at(int x, int y, int z); }

    /** Distance along a ray to the first cell where solid says yes (grid walk, exact cell entry), or range. */
    static float firstHit(double ox, double oy, double oz, double dx, double dy, double dz, float range, Solid solid) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
        double tx = (dx > 0 ? x + 1 - ox : ox - x) * tdx, ty = (dy > 0 ? y + 1 - oy : oy - y) * tdy, tz = (dz > 0 ? z + 1 - oz : oz - z) * tdz;
        double t = 0;
        while (t < range) {
            if (t > 0 && solid.at(x, y, z)) return (float) Math.max(0.3, t);
            if (tx < ty && tx < tz) { t = tx; tx += tdx; x += sx; }
            else if (ty < tz) { t = ty; ty += tdy; y += sy; }
            else { t = tz; tz += tdz; z += sz; }
        }
        return range;
    }

    /**
     * Called by the tracer at the start of each photo frame: starts over when the camera, the lens or the time of day
     * changed, then counts this sample. Returns the sample number (1 = the first of a new picture).
     */
    static int frame(Camera cam) {
        boolean moved = Math.abs(cam.x - cx) + Math.abs(cam.y - cy) + Math.abs(cam.z - cz) > 1e-4
                || Math.abs(cam.fx - cfx) + Math.abs(cam.fy - cfy) + Math.abs(cam.fz - cfz) > 1e-4;
        if (moved || aperture != keyAperture || focus != keyFocus || timeOverride != keyTime) {
            cx = cam.x; cy = cam.y; cz = cam.z; cfx = cam.fx; cfy = cam.fy; cfz = cam.fz;
            keyAperture = aperture; keyFocus = focus; keyTime = timeOverride;
            count = 0; saved = false;
        }
        return ++count;
    }

    /** Random nudge within the pixel for this sample (smooth edges once added up). */
    static float jitter() { return (float) (Math.random() - 0.5); }

    /**
     * After the tone map: once the picture has its samples, the tone-mapped image (window size, no HUD) is read back
     * and written as a PNG on a background thread.
     */
    static void maybeSave(int texture, int w, int h) {
        if (saved || count < samples) return;
        saved = true;
        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
        org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1);
        org.lwjgl.opengl.GL11.glGetTexImage(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) // OpenGL's rows run bottom-up
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 4;
                rgb[y * w + x] = (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | (buf.get(i + 2) & 255);
            }
        File f = target != null ? target : newShotFile();
        java.util.function.Consumer<File> then = onSaved;
        int n = count;
        Thread t = new Thread(() -> {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, w, h, rgb, 0, w);
                f.getParentFile().mkdirs();
                javax.imageio.ImageIO.write(img, "png", f);
                lastSaved = f;
                status = "Saved " + f.getName() + " (" + n + " samples)";
                BetaPT.LOGGER.info("Photo mode: saved {} ({}x{}, {} samples)", f, w, h, n);
            } catch (Exception e) {
                status = "Could not save the picture: " + e;
                BetaPT.LOGGER.error("Photo mode: could not save {}", f, e);
            }
            if (then != null) then.accept(f);
        }, "alphapt-photo-png");
        t.setDaemon(true);
        t.start();
    }

    private static File newShotFile() {
        File dir = com.alexmcclellan.betapt.config.BetaPTConfig.gameDir().resolve("screenshots").toFile();
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new java.util.Date());
        File f = new File(dir, stamp + "_photo.png");
        for (int i = 2; f.exists(); i++) f = new File(dir, stamp + "_photo_" + i + ".png");
        return f;
    }

    /** The settings slider's step for a sample count, and back. */
    public static int sampleStep(int n) {
        int best = 0;
        for (int i = 0; i < SAMPLE_STEPS.length; i++) if (Math.abs(SAMPLE_STEPS[i] - n) < Math.abs(SAMPLE_STEPS[best] - n)) best = i;
        return best;
    }

    /** "12:00" for a time of day in ticks (0 ticks = 6:00). */
    public static String clock(long ticks) {
        long t = ((ticks % 24000) + 24000) % 24000;
        int minutes = (int) ((t * 60 / 1000 + 6 * 60) % (24 * 60));
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }
}
