package com.alexmcclellan.betapt.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * F2 takes a screenshot, as in Beta (Alpha 1.1.2_01 has none): .minecraft/screenshots/<date>_<time>.png.
 * Read from the finished frame, written on a background thread.
 */
public final class Screenshots {
    private Screenshots() {}
    private static boolean held;

    public static void frame(net.minecraft.client.Minecraft mc) {
        boolean down = Keyboard.isKeyDown(Keyboard.KEY_F2);
        if (down && !held) take(mc);
        held = down;
    }

    public static void take(net.minecraft.client.Minecraft mc) {
        int w = mc.width, h = mc.height;
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 3);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
        File dir = new File(mc.gameDir, "screenshots");
        String name = new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new java.util.Date());
        new Thread(() -> {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 3;
                    img.setRGB(x, y, (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | buf.get(i + 2) & 255);
                }
                dir.mkdirs();
                File f = new File(dir, name + ".png");
                for (int n = 1; f.exists(); n++) f = new File(dir, name + "_" + n + ".png");
                javax.imageio.ImageIO.write(img, "png", f);
                com.alexmcclellan.betapt.BetaPT.LOGGER.info("Saved screenshot {}", f.getName());
            } catch (Throwable t) { com.alexmcclellan.betapt.BetaPT.LOGGER.warn("Screenshot failed: {}", t.toString()); }
        }, "Screenshot").start();
        if (mc.gui != null) mc.gui.addChatMessage("Saved screenshot as " + name + ".png");
    }
}
