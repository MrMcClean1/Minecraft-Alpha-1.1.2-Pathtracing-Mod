package com.alexmcclellan.betapt.test;

import com.alexmcclellan.betapt.BetaPT;
import com.alexmcclellan.betapt.gui.AlphaGui;
import com.alexmcclellan.betapt.mixin.ButtonWidgetAccessor;
import com.alexmcclellan.betapt.mixin.ScreenAccessor;
import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Alpha's quick test (-Dalphapt.test=true, about 2 minutes on a real GPU). It opens (or makes) the world
 * "alphapt-test", builds a row of every block high in the air, then:
 *  - checks the path tracer is running (shaders, voxel chunks, block table), the Alpha fixes (no grass overlay,
 *    leaves read as oak whatever their decay data, the GUI scale cap), the menus' layouts at several window sizes
 *    and GUI scales (no button overlapping another or off the screen), and F2's screenshot;
 *  - looks at the blocks from six sides with the path tracer and with Alpha's renderer, and compares the colours
 *    (not the brightness: shadows and light differ on purpose) in a grid. A cell whose colour is far off is a wrong
 *    texture (grey grass sides, purple leaves).
 * Writes alphapt-test/report.txt and the pictures (traced | Alpha, side by side) next to it, then quits.
 */
public final class AlphaTest {
    private AlphaTest() {}

    public static final boolean ON = Boolean.getBoolean("alphapt.test");
    private static final int OY = 100;
    private static final List<String> lines = new ArrayList<>();
    private static int passed, failed;
    private static int stage, wait, view, ox, oz, frames;
    private static boolean started, built, quit;
    private static BufferedImage traced;
    private static long screenshotAt;
    private static File screenshotDir;
    private static int screenshotsBefore;
    private static String status = "Alpha test: starting";

    public static boolean running() { return ON && !quit; }
    public static String status() { return status; }

    private static void check(boolean ok, String what) {
        if (ok) passed++; else failed++;
        lines.add((ok ? "  PASS " : "  FAIL ") + what);
        BetaPT.LOGGER.info("Alpha test: {} {}", ok ? "PASS" : "FAIL", what);
    }

    private static void note(String s) { lines.add("       " + s); }

    static final double[][] VIEWS = {
            {20.5, 4.5, -6.0, 0, 25}, {20.5, 14.0, 3.5, 0, 89}, {20.5, 4.5, 13.0, 180, 25},
            {46.0, 4.5, 3.5, 90, 25}, {-4.0, 4.5, 3.5, 270, 25}, {3.5, 2.6, -2.5, 20, 25}};
    static final String[] VIEW_NAMES = {"south", "top", "north", "east", "west", "close-up"};

    private static void pose(Minecraft mc) {
        double[] p = VIEWS[Math.min(view, VIEWS.length - 1)];
        mc.player.setPositionAndAngles(ox + p[0], OY + p[1], oz + p[2], (float) p[3], (float) p[4]);
        mc.player.velocityX = mc.player.velocityY = mc.player.velocityZ = 0;
        ((com.alexmcclellan.betapt.mixin.EntityAccessor) mc.player).betapt$setFallDistance(0f);
        mc.world.ticks = 6000;
    }

    /** Every tick (Minecraft.tick, before the game's). */
    public static void tick(Minecraft mc) {
        if (!ON || quit) return;
        if (!started) {
            if (mc.screen instanceof net.minecraft.client.gui.screen.menu.TitleScreen) { started = true; mc.interactionManager = new net.minecraft.client.SurvivalInteractionManager(mc); mc.startGame("alphapt-test"); mc.openScreen(null); }
            return;
        }
        if (mc.world == null || mc.player == null) return;
        if (!built) {
            if (buildX == Integer.MIN_VALUE) { ox = (int) Math.floor(mc.player.x); oz = (int) Math.floor(mc.player.z); buildX = -2; }
            // the platform a column a tick: all at once overflows Alpha's recursive lighting
            if (buildX <= 44) { column(mc, buildX++); status = "Alpha test: building " + buildX + "/44"; return; }
            build(mc); built = true; wait = 100; stage = 1; status = "Alpha test: warming up";
        }
        pose(mc);
    }

    private static int buildX = Integer.MIN_VALUE;

    private static void column(Minecraft mc, int dx) {
        for (int dz = -8; dz <= 8; dz++) {
            if (mc.world.getBlock(ox + dx, OY, oz + dz) != 1) mc.world.setBlock(ox + dx, OY, oz + dz, 1);
            for (int dy = 1; dy <= 6; dy++) if (mc.world.getBlock(ox + dx, OY + dy, oz + dz) != 0) mc.world.setBlock(ox + dx, OY + dy, oz + dz, 0);
        }
    }

    private static void build(Minecraft mc) {
        int n = 0;
        for (int id = 1; id < 256; id++) {
            Block b = Block.BY_ID[id];
            if (b == null || id == 8 || id == 9 || id == 10 || id == 11 || id == 51) continue;
            int k = n++;
            mc.world.setBlockWithMetadata(ox + (k % 21) * 2, OY + 1, oz + (k / 21) * 3, id, 0);
        }
        for (int k = 0; k < 4; k++) mc.world.setBlockWithMetadata(ox + 6 + k, OY + 1, oz - 1, 18, new int[]{1, 2, 3, 9}[k]);
        note(n + " blocks placed at " + ox + " " + (OY + 1) + " " + oz);
    }

    /** Every frame, after the HUD (GameRenderer.render): the steps that need a drawn frame. */
    public static void frame(Minecraft mc) {
        if (!ON || quit || stage == 0) return;
        PathTracer pt = PathTracer.INSTANCE;
        frames++;
        if (wait > 0) { wait--; return; }
        switch (stage) {
            case 1 -> { checks(mc); stage = 2; view = 0; pt.enabled = true; wait = settle(); }
            case 2 -> { traced = grab(mc); pt.enabled = false; stage = 3; wait = 4; }
            case 3 -> {
                compare(mc, traced, grab(mc));
                pt.enabled = true;
                view++;
                status = "Alpha test: view " + view + " of " + VIEWS.length;
                if (view < VIEWS.length) { stage = 2; wait = settle(); }
                else { stage = 4; startScreenshot(mc); wait = 40; }
            }
            case 4 -> { finishScreenshot(); stage = 5; }
            case 5 -> finish(mc);
        }
    }

    /** Frames to let the traced picture settle after the camera moves (fewer on a slow software renderer). */
    private static int settle() { return PathTracer.INSTANCE.frameMs > 200 ? 8 : 60; }

    private static void checks(Minecraft mc) {
        PathTracer pt = PathTracer.INSTANCE;
        lines.add("Path tracer");
        check(!pt.broken, "shaders compiled, tracer not broken");
        check(pt.active(), "path tracer on");
        note("renderer: " + (pt.vkActive ? "Vulkan" : "OpenGL") + ", " + String.format("%.1f", pt.frameMs) + " ms a frame");
        check(pt.voxels.loadedSlots >= 9, "voxel world holds the chunks around the camera (" + pt.voxels.loadedSlots + ")");
        check(com.alexmcclellan.betapt.render.BlockTable.BER_IDS != null, "block table built");

        lines.add("Alpha fixes");
        check(pt.grassOverlay == 0, "no Beta grass overlay (Alpha's tile 38 is iron)");
        try {
            short[] ids = new short[32768];
            byte[] meta = new byte[16384];
            int bad = 0, leaves = 0;
            for (int k = 0; k < 4; k++) {
                int x = ox + 6 + k, z = oz - 1;
                net.minecraft.world.chunk.WorldChunk c = mc.world.getChunkAt(x >> 4, z >> 4);
                com.alexmcclellan.betapt.render.StationCompat.read(c, ids, meta);
                int i = (x & 15) << 11 | (z & 15) << 7 | (OY + 1);
                if (ids[i] != 18) continue;
                leaves++;
                int m = (i & 1) == 0 ? meta[i >> 1] & 15 : meta[i >> 1] >> 4 & 15;
                if (m != 0) bad++;
            }
            check(leaves == 4 && bad == 0, "leaves with decay data read as oak (" + leaves + " leaves, " + bad + " not)");
        } catch (Throwable t) { check(false, "leaf check: " + t); }
        int saved = AlphaGui.scale;
        try {
            AlphaGui.scale = 2;
            net.minecraft.client.render.Window w = new net.minecraft.client.render.Window(2560, 1417);
            check(w.scale == 2 && w.getWidth() == 1280, "GUI scale Normal gives 2x at 2560 x 1417 (got " + w.scale + "x)");
            AlphaGui.scale = 0;
            w = new net.minecraft.client.render.Window(2560, 1417);
            check(w.scale == 5, "GUI scale Auto is Alpha's own (5x at 2560 x 1417, got " + w.scale + "x)");
        } finally { AlphaGui.scale = saved; }

        lines.add("Menus (no button on another or off the screen)");
        int[][] sizes = {{854, 480}, {1280, 720}, {1920, 1017}, {2560, 1417}, {3840, 2080}};
        for (int s = 0; s <= 3; s++) {
            AlphaGui.scale = s;
            for (int[] size : sizes) {
                net.minecraft.client.render.Window w = new net.minecraft.client.render.Window(size[0], size[1]);
                layout(mc, new net.minecraft.client.gui.screen.menu.options.OptionsScreen(null, mc.options), "Options", w, size, s);
                layout(mc, new com.alexmcclellan.betapt.gui.PathTracerOptionsScreen(null), "Path Tracer", w, size, s);
                layout(mc, new com.alexmcclellan.betapt.gui.EffectsScreen(null), "Effects", w, size, s);
            }
        }
        AlphaGui.scale = saved;
        if (menuProblems.isEmpty()) check(true, "Options, Path Tracer and Effects screens at 5 window sizes x 4 GUI scales");
        else { for (String p : menuProblems) note(p); check(false, menuProblems.size() + " menu layout problems"); }
        boolean ptButton = false;
        Screen o = new net.minecraft.client.gui.screen.menu.options.OptionsScreen(null, mc.options);
        o.init(mc, 427, 240);
        for (Object b : ((ScreenAccessor) o).betapt$buttons()) if ("Path Tracer...".equals(((ButtonWidget) b).message)) ptButton = true;
        check(ptButton, "Options has the Path Tracer... button");
        lines.add("Pictures (traced vs Alpha's renderer, colour per grid cell)");
    }

    private static final List<String> menuProblems = new ArrayList<>();

    private static void layout(Minecraft mc, Screen screen, String name, net.minecraft.client.render.Window w, int[] size, int scale) {
        try {
            screen.init(mc, w.getWidth(), w.getHeight());
            List<Object> bs = ((ScreenAccessor) screen).betapt$buttons();
            String where = name + " at " + size[0] + "x" + size[1] + " GUI " + AlphaGui.NAMES[scale];
            for (int i = 0; i < bs.size(); i++) {
                ButtonWidget a = (ButtonWidget) bs.get(i);
                int aw = ((ButtonWidgetAccessor) a).betapt$width(), ah = ((ButtonWidgetAccessor) a).betapt$height();
                if (a.x < 0 || a.y < 0 || a.x + aw > w.getWidth() || a.y + ah > w.getHeight())
                    menuProblems.add(where + ": \"" + a.message + "\" goes off the screen");
                for (int j = i + 1; j < bs.size(); j++) {
                    ButtonWidget b = (ButtonWidget) bs.get(j);
                    int bw = ((ButtonWidgetAccessor) b).betapt$width(), bh = ((ButtonWidgetAccessor) b).betapt$height();
                    if (a.x < b.x + bw && b.x < a.x + aw && a.y < b.y + bh && b.y < a.y + ah)
                        menuProblems.add(where + ": \"" + a.message + "\" overlaps \"" + b.message + "\"");
                }
            }
        } catch (Throwable t) { menuProblems.add(name + ": " + t); }
    }

    private static BufferedImage grab(Minecraft mc) {
        int w = mc.width, h = mc.height;
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 3);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - 1 - y) * w + x) * 3;
            img.setRGB(x, y, (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | buf.get(i + 2) & 255);
        }
        return img;
    }

    /**
     * Colour per cell of a 48 x 27 grid (HUD rows left out), as chromaticity (r, g over r + g + b) so shadows and
     * brighter light don't count. A cell is off when its colour moved by more than 0.06 and isn't near-black or
     * near-white in either picture (no colour to compare).
     */
    private static void compare(Minecraft mc, BufferedImage a, BufferedImage b) {
        int gw = 48, gh = 27, w = a.getWidth(), h = a.getHeight(), off = 0, cells = 0;
        BufferedImage marked = new BufferedImage(w * 2, h, BufferedImage.TYPE_INT_RGB);
        marked.getGraphics().drawImage(a, 0, 0, null);
        marked.getGraphics().drawImage(b, w, 0, null);
        java.awt.Graphics g = marked.getGraphics();
        g.setColor(java.awt.Color.MAGENTA);
        for (int cy = 2; cy < gh - 4; cy++) for (int cx = 0; cx < gw; cx++) {
            int x0 = cx * w / gw, x1 = (cx + 1) * w / gw, y0 = cy * h / gh, y1 = (cy + 1) * h / gh;
            double[] ca = chroma(a, x0, y0, x1, y1), cb = chroma(b, x0, y0, x1, y1);
            if (ca == null || cb == null) continue;
            cells++;
            if (Math.abs(ca[0] - cb[0]) + Math.abs(ca[1] - cb[1]) > 0.06) { off++; g.drawRect(x0, y0, x1 - x0 - 1, y1 - y0 - 1); }
        }
        double pct = cells == 0 ? 0 : 100.0 * off / cells;
        try {
            File dir = dir(mc);
            javax.imageio.ImageIO.write(marked, "png", new File(dir, "view-" + VIEW_NAMES[view] + ".png"));
        } catch (Throwable ignored) {}
        check(pct < 5.0, String.format("%s view: %.1f%% of %d cells a different colour (pictures/view-%s.png)", VIEW_NAMES[view], pct, cells, VIEW_NAMES[view]));
    }

    private static double[] chroma(BufferedImage im, int x0, int y0, int x1, int y1) {
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            int c = im.getRGB(x, y);
            r += c >> 16 & 255; g += c >> 8 & 255; b += c & 255; n++;
        }
        double s = r + g + b;
        if (n == 0 || s / n < 60 || s / n > 700) return null;
        if (b > r * 1.15 && b > g * 1.05) return null; // sky or distant water: Alpha's own picture may not have built far chunks yet
        double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if ((mx - mn) / s < 0.04) return new double[]{1 / 3.0, 1 / 3.0}; // grey: compare as grey
        return new double[]{r / s, g / s};
    }

    private static File dir(Minecraft mc) {
        File d = new File(mc.gameDir, "alphapt-test");
        d.mkdirs();
        return d;
    }

    private static void startScreenshot(Minecraft mc) {
        lines.add("Screenshots");
        screenshotDir = new File(mc.gameDir, "screenshots");
        String[] l = screenshotDir.list();
        screenshotsBefore = l == null ? 0 : l.length;
        com.alexmcclellan.betapt.render.Screenshots.take(mc);
        screenshotAt = System.currentTimeMillis();
    }

    private static void finishScreenshot() {
        String[] l = screenshotDir.list();
        int now = l == null ? 0 : l.length;
        check(now > screenshotsBefore, "F2 screenshot saved to .minecraft/screenshots (" + (System.currentTimeMillis() - screenshotAt) + " ms)");
    }

    private static void finish(Minecraft mc) {
        quit = true;
        lines.add("");
        lines.add("Passed " + passed + ", failed " + failed);
        try (PrintWriter w = new PrintWriter(new File(dir(mc), "report.txt"), "UTF-8")) {
            w.println("Alpha Path Tracer test, " + new java.util.Date());
            for (String s : lines) w.println(s);
        } catch (Throwable t) { BetaPT.LOGGER.warn("Alpha test: couldn't write the report: {}", t.toString()); }
        BetaPT.LOGGER.info("Alpha test finished: passed {}, failed {} (alphapt-test/report.txt)", passed, failed);
        if (!Boolean.getBoolean("alphapt.test.stay")) {
            mc.running = false;
            Thread t = new Thread(() -> { try { Thread.sleep(15000); } catch (InterruptedException ignored) {} System.exit(failed == 0 ? 0 : 1); }, "Alpha test exit");
            t.setDaemon(true);
            t.start();
        }
    }
}
