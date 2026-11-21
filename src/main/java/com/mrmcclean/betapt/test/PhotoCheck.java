package com.mrmcclean.betapt.test;

import com.mrmcclean.betapt.render.PhotoMode;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The Alpha test's photo mode stage: stills of the test view added up from many samples, checked for noise (two
 * stills of 32 samples must differ far less than two of 4), depth of field (a wide lens changes the picture) and the
 * shot's own time of day (held while photo mode is open, the world's own back after). Report lines PASS/FAIL.
 */
final class PhotoCheck {
    private PhotoCheck() {}

    private static int step;
    private static long deadline;
    private static File last;
    private static final File[] shots = new File[6];
    private static double noise32;
    private static long keptTime;
    private static boolean keptEnabled;
    private static int keptSamples, keptSun;
    private static float keptAperture;

    static void start(Minecraft mc) {
        keptEnabled = PhotoMode.enabled; keptSamples = PhotoMode.samples; keptAperture = PhotoMode.aperture; keptSun = PhotoMode.sunTime;
        keptTime = mc.world.ticks;
        PhotoMode.enabled = true; PhotoMode.samples = 32; PhotoMode.aperture = 0f; PhotoMode.sunTime = -1;
        last = PhotoMode.lastSaved;
        step = 0;
        deadline = System.nanoTime() + 60_000_000_000L;
        mc.openScreen(new com.mrmcclean.betapt.gui.PhotoScreen());
        AlphaTest.section("Photo mode");
        line(PhotoMode.active(), "photo mode opens", String.format(java.util.Locale.ROOT, "focus %.1f blocks", PhotoMode.focus));
    }

    /** Every frame while the stage runs; true when it is over. */
    static boolean frame(Minecraft mc) {
        File now = PhotoMode.lastSaved;
        if (now == last) {
            if (System.nanoTime() < deadline) return false;
            line(false, "photo still saved in time", "step " + step + " never finished");
            return end(mc);
        }
        last = now;
        shots[step] = now;
        deadline = System.nanoTime() + 60_000_000_000L;
        switch (step++) {
            case 0 -> {
                BufferedImage a = read(now);
                line(a != null && a.getWidth() == mc.width && a.getHeight() == mc.height, "photo saved at the window's size",
                        now.getName() + (a == null ? " unreadable" : ", " + a.getWidth() + "x" + a.getHeight()));
                PhotoMode.restart(); // the same picture again, from new random samples
            }
            case 1 -> { noise32 = diff(read(shots[0]), read(shots[1])); PhotoMode.samples = 4; PhotoMode.restart(); }
            case 2 -> PhotoMode.restart();
            case 3 -> {
                double d4 = diff(read(shots[2]), read(shots[3]));
                line(d4 > 0 && noise32 < d4 / 2.0, "photo adds up to a noise-free still",
                        String.format(java.util.Locale.ROOT, "difference between two stills: %.4f at 32 samples, %.4f at 4", noise32, d4));
                PhotoMode.samples = 32; PhotoMode.aperture = 0.2f; PhotoMode.restart();
            }
            case 4 -> {
                double d = diff(read(shots[0]), read(shots[4]));
                line(d > Math.max(0.004, noise32 * 2), "depth of field changes the still", String.format(java.util.Locale.ROOT, "difference to the sharp still %.4f", d));
                PhotoMode.sunTime = 12000; PhotoMode.timeOverride = 12000;
            }
            case 5 -> {
                line(mc.world.ticks == 12000, "photo holds its own time of day", "world time " + mc.world.ticks);
                double d = diff(read(shots[4]), read(shots[5]));
                line(d > 0.02, "the sun setting changes the still", String.format(java.util.Locale.ROOT, "difference %.4f", d));
                return end(mc);
            }
            default -> { return end(mc); }
        }
        return false;
    }

    private static boolean end(Minecraft mc) {
        mc.openScreen(null);
        line(!PhotoMode.active(), "photo mode closes", "");
        line(Math.abs(mc.world.ticks - keptTime) < 100, "the world's time comes back after the photo", "world time " + mc.world.ticks + ", was " + keptTime);
        PhotoMode.enabled = keptEnabled; PhotoMode.samples = keptSamples; PhotoMode.aperture = keptAperture; PhotoMode.sunTime = keptSun;
        try {
            java.nio.file.Path dir = AlphaTest.dir(mc).toPath();
            for (int i = 0; i < shots.length; i++)
                if (shots[i] != null && shots[i].isFile()) java.nio.file.Files.copy(shots[i].toPath(), dir.resolve("photo-" + (i + 1) + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
        return true;
    }

    private static void line(boolean ok, String what, String detail) {
        AlphaTest.check(ok, what + (detail.isEmpty() ? "" : ": " + detail));
    }

    private static BufferedImage read(File f) {
        try { return f == null ? null : javax.imageio.ImageIO.read(f); } catch (Exception e) { return null; }
    }

    /** Mean difference per channel (0..1) between two pictures of the same size; 1 if one is missing. */
    static double diff(BufferedImage a, BufferedImage b) {
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return 1;
        double s = 0; long n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                s += Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
                n += 3;
            }
        return s / 255.0 / Math.max(1, n);
    }
}
