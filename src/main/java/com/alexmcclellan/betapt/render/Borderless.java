package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import javax.swing.SwingUtilities;

/**
 * Borderless "fullscreen windowed" mode: the game window loses its title bar and
 * borders and is sized to cover the primary monitor exactly.
 *
 * Beta draws into a canvas inside an AWT window. A window can only drop its borders
 * while it is hidden, so this has to run before Beta attaches OpenGL to that canvas
 * (the start of Minecraft.init). Doing it later would destroy the OpenGL context.
 */
public final class Borderless {
    /** True while the window is being rebuilt; AppletCanvasMixin uses it to keep the game alive. */
    public static volatile boolean rebuilding;

    private Borderless() {}

    public static void apply(Component canvas) {
        if (canvas == null) return;
        Frame frame = (Frame) SwingUtilities.getAncestorOfClass(Frame.class, canvas);
        if (frame == null) return;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        try {
            EventQueue.invokeAndWait(() -> {
                rebuilding = true;
                try {
                    frame.dispose();
                    frame.setUndecorated(true);
                    frame.setBounds(screen);
                    frame.setVisible(true);
                    frame.toFront();
                } finally {
                    rebuilding = false;
                }
            });
            BetaPT.LOGGER.info("Borderless window: {}x{} at {},{}", screen.width, screen.height, screen.x, screen.y);
        } catch (Exception e) {
            BetaPT.LOGGER.error("Could not switch to a borderless window", e);
        }
    }
}
