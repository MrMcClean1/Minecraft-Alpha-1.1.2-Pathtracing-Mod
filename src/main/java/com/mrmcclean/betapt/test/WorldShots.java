package com.mrmcclean.betapt.test;

import com.mrmcclean.betapt.BetaPT;
import com.mrmcclean.betapt.render.PathTracer;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Pictures of a save from given spots, game and traced, with no input (for the history museum in
 * tekkitsmp-pt/tools/museum); the same as Beta PT's WorldShots. Armed for one launch by .minecraft/alphapt-shots/run:
 *   world <save folder>                          opened from the title screen (Alpha lists World1..World5)
 *   view <x> <y> <z> <yaw> <pitch> <name>        any number; none: just let the spawn area generate, then quit
 * Each view: Alpha's own picture, then the path tracer's after it settles: <name>-game.png, <name>-traced.png and
 * <name>.png (side by side) in alphapt-shots/. Muted, never takes the mouse, closes the game at the end.
 */
public final class WorldShots {
    private WorldShots() {}

    private static boolean on, opened, done;
    private static String world;
    private static final List<String[]> views = new ArrayList<>();
    private static int view, stage, wait, ticks;
    private static BufferedImage game;
    private static boolean tracerWas;

    static {
        try {
            File run = new File(dir(), "run");
            if (run.isFile()) {
                for (String line : Files.readAllLines(run.toPath())) {
                    String[] w = line.trim().split("\\s+");
                    if (w[0].equals("world") && w.length > 1) world = w[1];
                    if (w[0].equals("view") && w.length >= 7) views.add(w);
                }
                on = world != null && run.delete();
            }
        } catch (Throwable t) { BetaPT.LOGGER.warn("Alpha shots: {}", t.toString()); }
    }

    private static File dir() { return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("alphapt-shots").toFile(); }

    public static boolean running() { return on && !done; }

    /** Each tick (AutoTest.onTick). */
    public static void tick(Minecraft mc) {
        if (!running()) return;
        if (!opened) {
            if (mc.screen instanceof net.minecraft.client.gui.screen.menu.TitleScreen) {
                opened = true;
                com.mrmcclean.betapt.bots.TestQuiet.mute(mc.options);
                tracerWas = PathTracer.INSTANCE.enabled;
                mc.interactionManager = new net.minecraft.client.SurvivalInteractionManager(mc);
                mc.startGame(world);
                mc.openScreen(null);
                BetaPT.LOGGER.info("Alpha shots: opening {}, {} views", world, views.size());
            }
            return;
        }
        if (mc.world == null || mc.player == null) return;
        if (mc.screen != null) mc.openScreen(null); // the pause menu a background window gets
        ticks++;
        if (views.isEmpty()) { if (ticks > 400) finish(mc); return; } // the spawn area has generated
        if (view < views.size()) {
            String[] v = views.get(view);
            mc.player.setPositionAndAngles(Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5]));
            mc.player.velocityX = mc.player.velocityY = mc.player.velocityZ = 0;
            mc.world.ticks = 6000;
        }
    }

    /** Each frame after the world, before the HUD (AutoTest.afterWorld). */
    public static void frame(Minecraft mc) {
        if (!running() || !opened || mc.world == null || views.isEmpty() || ticks < 200) return;
        PathTracer pt = PathTracer.INSTANCE;
        wait++;
        if (stage == 0) { pt.enabled = false; if (wait > 60) { game = grab(mc); pt.enabled = true; stage = 1; wait = 0; } }
        else if (wait > 240) {
            BufferedImage traced = grab(mc);
            String name = views.get(view)[6];
            try {
                File d = dir();
                ImageIO.write(game, "png", new File(d, name + "-game.png"));
                ImageIO.write(traced, "png", new File(d, name + "-traced.png"));
                BufferedImage both = new BufferedImage(game.getWidth() * 2, game.getHeight(), BufferedImage.TYPE_INT_RGB);
                both.getGraphics().drawImage(game, 0, 0, null);
                both.getGraphics().drawImage(traced, game.getWidth(), 0, null);
                ImageIO.write(both, "png", new File(d, name + ".png"));
                BetaPT.LOGGER.info("Alpha shots: {} saved", name);
            } catch (Throwable t) { BetaPT.LOGGER.warn("Alpha shots: couldn't save {}: {}", name, t.toString()); }
            view++;
            stage = 0;
            wait = 0;
            if (view >= views.size()) finish(mc);
        }
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

    /** As the Alpha test closes the game: let Alpha save and stop, then exit if a thread keeps it open. */
    private static void finish(Minecraft mc) {
        done = true;
        PathTracer.INSTANCE.enabled = tracerWas;
        com.mrmcclean.betapt.bots.TestQuiet.restore();
        BetaPT.LOGGER.info("Alpha shots: done");
        mc.running = false;
        Thread t = new Thread(() -> { try { Thread.sleep(15000); } catch (InterruptedException ignored) {} System.exit(0); }, "Alpha shots exit");
        t.setDaemon(true);
        t.start();
    }
}
