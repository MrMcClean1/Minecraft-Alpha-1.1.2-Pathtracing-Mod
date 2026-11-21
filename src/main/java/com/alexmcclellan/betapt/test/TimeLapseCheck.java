package com.alexmcclellan.betapt.test;

import com.alexmcclellan.betapt.render.PhotoMode;
import com.alexmcclellan.betapt.render.TimeLapse;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The Alpha test's time-lapse stage: three keyframes from the test view, noon to midnight, six pictures of 8 samples.
 * Checks the numbered pictures, that the last is darker than the first, that the camera and the time of day come
 * back, and the MP4 (when ffmpeg is on the PATH). Report lines PASS/FAIL; the player's own keyframes are left alone.
 */
final class TimeLapseCheck {
    private TimeLapseCheck() {}

    private static final List<TimeLapse.Key> keptKeys = new ArrayList<>();
    private static boolean keptEnabled, keptVideo;
    private static int keptFrames, keptSamples, keptSun, phase;
    private static float keptAperture;
    private static double sx, sy, sz;
    private static long keptTime, deadline;

    static void start(Minecraft mc) {
        TimeLapse.ensureLoaded();
        keptKeys.clear(); keptKeys.addAll(TimeLapse.keys);
        keptEnabled = TimeLapse.enabled; keptVideo = TimeLapse.video; keptFrames = TimeLapse.frames;
        keptSamples = PhotoMode.samples; keptSun = PhotoMode.sunTime; keptAperture = PhotoMode.aperture;
        TimeLapse.enabled = true; TimeLapse.video = true; TimeLapse.frames = 6;
        PhotoMode.samples = 8; PhotoMode.sunTime = -1; PhotoMode.aperture = 0f;
        var p = mc.player;
        sx = p.x; sy = p.y; sz = p.z;
        keptTime = mc.world.ticks;
        double yaw = Math.toRadians(p.yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        TimeLapse.keys.clear();
        TimeLapse.keys.add(new TimeLapse.Key(sx, sy, sz, p.yaw, p.pitch, 6000));
        TimeLapse.keys.add(new TimeLapse.Key(sx + fx * 3, sy + 1, sz + fz * 3, p.yaw + 20, p.pitch, 12000));
        TimeLapse.keys.add(new TimeLapse.Key(sx + fx * 6 - fz * 2, sy + 2, sz + fz * 6 + fx * 2, p.yaw - 25, p.pitch + 5, 18000));
        AlphaTest.section("Time-lapse");
        String m = TimeLapse.start(mc);
        line(TimeLapse.running(), "time-lapse starts", m);
        phase = 0;
        deadline = System.nanoTime() + 180_000_000_000L;
    }

    /** Every frame while the stage runs; true when it is over. */
    static boolean frame(Minecraft mc) {
        boolean late = System.nanoTime() > deadline;
        if (phase == 0) {
            if (TimeLapse.running() && !late) return false;
            if (TimeLapse.running()) TimeLapse.finish(mc, false);
            File d = TimeLapse.lastDir;
            int n = 0;
            double first = -1, last = -1;
            for (int i = 1; i <= 6; i++) {
                File f = new File(d, String.format(java.util.Locale.ROOT, "frame_%04d.png", i));
                BufferedImage img = read(f);
                if (img == null) continue;
                n++;
                if (i == 1) first = lum(img);
                if (i == 6) last = lum(img);
                try { java.nio.file.Files.copy(f.toPath(), AlphaTest.dir(mc).toPath().resolve("timelapse-" + i + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
            }
            line(n == 6, "time-lapse pictures saved, numbered", n + " of 6 (" + TimeLapse.status + ")");
            line(first > 0 && last >= 0 && last < first * 0.8, "the time-lapse goes from noon to midnight", String.format(java.util.Locale.ROOT, "mean brightness first %.2f, last %.2f", first, last));
            var p = mc.player;
            double moved = Math.abs(p.x - sx) + Math.abs(p.y - sy) + Math.abs(p.z - sz);
            line(moved < 0.01, "the camera is back where it was", String.format(java.util.Locale.ROOT, "%.3f blocks off", moved));
            line(Math.abs(mc.world.ticks - keptTime) < 100, "the world's time comes back after the time-lapse", "world time " + mc.world.ticks + ", was " + keptTime);
            phase = 1;
            deadline = System.nanoTime() + 90_000_000_000L;
            return false;
        }
        if (TimeLapse.encoding && !late) return false;
        File v = TimeLapse.lastVideo;
        String st = TimeLapse.status;
        if (st.startsWith("No ffmpeg")) AlphaTest.section("       time-lapse video: " + st);
        else line(v != null && v.isFile() && v.length() > 1000, "time-lapse video made by ffmpeg", st + (v != null ? ", " + v.length() / 1024 + " KB" : ""));
        TimeLapse.keys.clear(); TimeLapse.keys.addAll(keptKeys);
        TimeLapse.enabled = keptEnabled; TimeLapse.video = keptVideo; TimeLapse.frames = keptFrames;
        PhotoMode.samples = keptSamples; PhotoMode.sunTime = keptSun; PhotoMode.aperture = keptAperture;
        return true;
    }

    private static void line(boolean ok, String what, String detail) {
        AlphaTest.check(ok, what + (detail.isEmpty() ? "" : ": " + detail));
    }

    private static BufferedImage read(File f) {
        try { return f == null || !f.isFile() ? null : javax.imageio.ImageIO.read(f); } catch (Exception e) { return null; }
    }

    private static double lum(BufferedImage img) {
        double s = 0; int n = 0;
        for (int y = 0; y < img.getHeight(); y += 4) for (int x = 0; x < img.getWidth(); x += 4) {
            int c = img.getRGB(x, y);
            s += (0.2126 * (c >> 16 & 255) + 0.7152 * (c >> 8 & 255) + 0.0722 * (c & 255)) / 255.0; n++;
        }
        return s / Math.max(1, n);
    }
}
