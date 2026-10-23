package com.alexmcclellan.betapt.gui;

import java.io.*;
import java.util.Properties;

/**
 * Alpha 1.1.2_01 always makes the menus and HUD as big as they fit (a 2560 x 1440 window gets 5x), and has no
 * GUI scale option. This adds one, as Beta has: Auto (Alpha's own), Small (1x), Normal (2x), Large (3x).
 * Kept in config/alphapt-gui.properties.
 */
public final class AlphaGui {
    private AlphaGui() {}

    public static int scale = load(); // 0 = Auto
    public static final String[] NAMES = {"Auto", "Small", "Normal", "Large"};

    private static File file() {
        try { return new File(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile(), "alphapt-gui.properties"); }
        catch (Throwable t) { return new File("config/alphapt-gui.properties"); }
    }

    private static int load() {
        try (InputStream in = new FileInputStream(file())) {
            Properties p = new Properties();
            p.load(in);
            return Math.max(0, Math.min(3, Integer.parseInt(p.getProperty("guiScale", "0").trim())));
        } catch (Throwable t) { return 0; }
    }

    public static void cycle() {
        scale = (scale + 1) % NAMES.length;
        try {
            file().getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("guiScale", Integer.toString(scale));
            try (OutputStream out = new FileOutputStream(file())) { p.store(out, "Alpha Path Tracer: GUI scale (0 Auto, 1 Small, 2 Normal, 3 Large)"); }
        } catch (Throwable ignored) {}
    }

    public static String text() { return "GUI scale: " + NAMES[scale]; }
}
