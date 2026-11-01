package com.alexmcclellan.betapt.bots;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Settings for the bots, in config/classicbots.properties. Bots are off until enabled=true (or "/bot on" in chat).
 * The same file works in single player and on a server; the server's copy decides there.
 */
public final class BotConfig {
    private BotConfig() {}

    public static boolean enabled = false;
    /** Anyone may give orders to any bot (false: only the player who made it). */
    public static boolean anyoneCommands = false;
    /** Bots may take from and put into chests away from their home (their owner's base). */
    public static boolean othersChests = false;
    /** Bots may break built blocks (planks, cobblestone, glass, wool...), not just natural ones. */
    public static boolean allowGrief = false;
    /** Show bots on the minimap (Rei's Minimap waypoints, where the pack has it). */
    public static boolean showOnMap = true;
    public static int maxBotsPerPlayer = 4;
    /** Language model: an Ollama (or OpenAI-style) server. Empty url = no model; the bots still work by rules. */
    public static String modelUrl = "http://127.0.0.1:11434";
    public static String model = "qwen3.5:9b";
    public static String modelApi = "ollama";
    public static int modelTimeoutMs = 20000;
    public static int modelPerMinute = 12;
    public static boolean modelOn = true;

    private static File file;

    public static void load(File configDir) {
        file = new File(configDir, "classicbots.properties");
        Properties p = new Properties();
        if (file.isFile()) try (FileInputStream in = new FileInputStream(file)) { p.load(in); } catch (Exception ignored) {}
        enabled = b(p, "enabled", enabled);
        anyoneCommands = b(p, "anyoneCommands", anyoneCommands);
        othersChests = b(p, "othersChests", othersChests);
        allowGrief = b(p, "allowGrief", allowGrief);
        showOnMap = b(p, "showOnMap", showOnMap);
        maxBotsPerPlayer = i(p, "maxBotsPerPlayer", maxBotsPerPlayer);
        modelOn = b(p, "model.enabled", modelOn);
        modelUrl = p.getProperty("model.url", modelUrl).trim();
        model = p.getProperty("model.name", model).trim();
        modelApi = p.getProperty("model.api", modelApi).trim();
        modelTimeoutMs = i(p, "model.timeoutMs", modelTimeoutMs);
        modelPerMinute = i(p, "model.perMinute", modelPerMinute);
        save();
    }

    public static void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("enabled", "" + enabled);
        p.setProperty("anyoneCommands", "" + anyoneCommands);
        p.setProperty("othersChests", "" + othersChests);
        p.setProperty("allowGrief", "" + allowGrief);
        p.setProperty("showOnMap", "" + showOnMap);
        p.setProperty("maxBotsPerPlayer", "" + maxBotsPerPlayer);
        p.setProperty("model.enabled", "" + modelOn);
        p.setProperty("model.url", modelUrl);
        p.setProperty("model.name", model);
        p.setProperty("model.api", modelApi);
        p.setProperty("model.timeoutMs", "" + modelTimeoutMs);
        p.setProperty("model.perMinute", "" + modelPerMinute);
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, "Classic Bots: AI helpers. enabled=true (or say /bot on) to use them; /bot help in chat");
        } catch (Exception ignored) {}
    }

    private static boolean b(Properties p, String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, "" + d).trim()); }
    private static int i(Properties p, String k, int d) { try { return Integer.parseInt(p.getProperty(k, "" + d).trim()); } catch (Exception e) { return d; } }
}
