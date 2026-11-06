package com.alexmcclellan.betapt.config;

import com.alexmcclellan.betapt.BetaPT;
import com.alexmcclellan.betapt.render.PathTracer;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * In-game settings (look, brightness, denoiser), kept separate from betapt.properties so
 * saving them never rewrites the hand-edited test and startup options there.
 * File: .minecraft/config/betapt-settings.properties
 */
public final class PtSettings {
    private PtSettings() {}

    private static Path file() {
        return BetaPTConfig.gameDir().resolve("config").resolve("betapt-settings.properties");
    }

    /*
     * Per-dimension settings. "global" holds the settings for every dimension that has none of its own;
     * a dimension can have its own full set (dims, keyed by Beta's dimension number). Whichever set
     * belongs to the dimension the player is in is the one on screen, edited by the menu and saved by
     * Done. In the file, a dimension's settings are the same keys with "dim.<number>." in front.
     */
    private static Properties global = new Properties();
    private static final java.util.Map<String, Properties> dims = new java.util.TreeMap<>();
    private static String activeDim; // the dimension whose settings are showing; null before the first world

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) { global = capture(); return; }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            p.load(r);
        } catch (Exception e) {
            BetaPT.LOGGER.error("Could not read {}", f, e);
            return;
        }
        Properties g = new Properties();
        fromFile(p, g, dims);
        apply(g);
        global = capture(); // whatever the file left out: the defaults
    }

    /** Splits the file's keys into the shared settings and each dimension's own. */
    private static void fromFile(Properties p, Properties g, java.util.Map<String, Properties> d) {
        for (String k : p.stringPropertyNames()) {
            if (!k.startsWith("dim.")) { g.setProperty(k, p.getProperty(k)); continue; }
            int dot = k.indexOf('.', 4);
            if (dot < 0) continue;
            d.computeIfAbsent(k.substring(4, dot), x -> new Properties()).setProperty(k.substring(dot + 1), p.getProperty(k));
        }
    }

    /** What goes in the file: the shared settings as they are, each dimension's with "dim.<number>." in front. */
    private static Properties toFile() {
        Properties out = new Properties();
        for (String k : global.stringPropertyNames()) if (!k.equals("enabled")) out.setProperty(k, global.getProperty(k)); // on/off at start: betapt.properties
        for (var e : dims.entrySet())
            for (String k : e.getValue().stringPropertyNames()) out.setProperty("dim." + e.getKey() + "." + k, e.getValue().getProperty(k));
        return out;
    }

    /** Puts a set of saved settings on screen (anything missing keeps its current value). */
    private static void apply(Properties p) {
        PathTracer pt = PathTracer.INSTANCE;
        try {
            if (p.getProperty("enabled") != null) pt.enabled = Boolean.parseBoolean(p.getProperty("enabled"));
            pt.realism = clamp(Float.parseFloat(p.getProperty("look.realism", Float.toString(pt.realism))), 0f, 1f);
            pt.brightness = clamp(Float.parseFloat(p.getProperty("brightness", Float.toString(pt.brightness))), 0.5f, 2f);
            pt.denoise = Boolean.parseBoolean(p.getProperty("denoise", Boolean.toString(pt.denoise)));
            pt.renderScale = clamp(Float.parseFloat(p.getProperty("renderScale", Float.toString(pt.renderScale))), 0.5f, 1f);
            pt.entityShadows = Boolean.parseBoolean(p.getProperty("mobShadows", Boolean.toString(pt.entityShadows)));
            pt.hardwareRt = Boolean.parseBoolean(p.getProperty("hardwareRt", Boolean.toString(pt.hardwareRt)));
            pt.handMode = "traced".equalsIgnoreCase(p.getProperty("hand", pt.handMode == PathTracer.HAND_TRACED ? "traced" : "beta").trim()) ? PathTracer.HAND_TRACED : PathTracer.HAND_BETA;
            pt.denoiserMode = "standard".equalsIgnoreCase(p.getProperty("denoiser", pt.denoiserMode == PathTracer.DENOISER_STANDARD ? "standard" : "advanced").trim()) ? PathTracer.DENOISER_STANDARD : PathTracer.DENOISER_ADVANCED;
            pt.reflections = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("reflections", Integer.toString(pt.reflections)).trim())));
            pt.bounces = Math.max(1, Math.min(3, Integer.parseInt(p.getProperty("bounces", Integer.toString(pt.bounces)).trim())));
            pt.particleLight = Boolean.parseBoolean(p.getProperty("particleLight", Boolean.toString(pt.particleLight)));
            pt.heldLights = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("heldLights", Integer.toString(pt.heldLights)).trim())));
            String up = p.getProperty("upscaler", pt.upscaler == PathTracer.UPSCALER_BASIC ? "basic" : pt.upscaler == PathTracer.UPSCALER_OFF ? "off" : pt.upscaler == PathTracer.UPSCALER_DLSS ? "dlss" : "fsr");
            pt.upscaler = "dlss".equalsIgnoreCase(up) ? PathTracer.UPSCALER_DLSS : "basic".equalsIgnoreCase(up) ? PathTracer.UPSCALER_BASIC : "off".equalsIgnoreCase(up) ? PathTracer.UPSCALER_OFF : PathTracer.UPSCALER_FSR;
            pt.lowLatency = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("lowLatency", Integer.toString(pt.lowLatency)).trim())));
            pt.ser = Boolean.parseBoolean(p.getProperty("ser", Boolean.toString(pt.ser)).trim());
            pt.omm = Boolean.parseBoolean(p.getProperty("micromaps", Boolean.toString(pt.omm)).trim());
            pt.traceDistance = Math.max(0, Math.min(248, Integer.parseInt(p.getProperty("traceDistance", Integer.toString(pt.traceDistance)).trim())));
            pt.fastLeaves = "fast".equalsIgnoreCase(p.getProperty("leaves", pt.fastLeaves ? "fast" : "fancy").trim());
            String cs = p.getProperty("cloudShadows", Float.toString(pt.cloudShadows));
            // older settings files saved an on/off switch
            pt.cloudShadows = cs.equals("true") ? PathTracer.DEFAULT_CLOUD_SHADOWS : cs.equals("false") ? 0f : clamp(Float.parseFloat(cs), 0f, 1f);
            pt.sharpen = clamp(Float.parseFloat(p.getProperty("sharpen", Float.toString(pt.sharpen))), 0f, 1f);
            // effects (Effects... page)
            pt.fxWet = Boolean.parseBoolean(p.getProperty("fx.wet", Boolean.toString(pt.fxWet)));
            pt.fxHaze = Boolean.parseBoolean(p.getProperty("fx.rainHaze", Boolean.toString(pt.fxHaze)));
            pt.fxSparkle = Boolean.parseBoolean(p.getProperty("fx.snowSparkle", Boolean.toString(pt.fxSparkle)));
            pt.fxGodRays = Boolean.parseBoolean(p.getProperty("fx.godRays", Boolean.toString(pt.fxGodRays)));
            pt.fxWaving = Boolean.parseBoolean(p.getProperty("fx.wavingPlants", Boolean.toString(pt.fxWaving)));
            pt.fxWaves = Boolean.parseBoolean(p.getProperty("fx.waterWaves", Boolean.toString(pt.fxWaves)));
            pt.darkNights = Boolean.parseBoolean(p.getProperty("nights.dark", Boolean.toString(pt.darkNights)));
            pt.handCollide = Boolean.parseBoolean(p.getProperty("hand.collide", Boolean.toString(pt.handCollide)));
        } catch (Exception e) {
            BetaPT.LOGGER.error("Bad value in {}", file(), e);
        }
    }

    /** The settings on screen now, as a set. */
    private static Properties capture() {
        PathTracer pt = PathTracer.INSTANCE;
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(pt.enabled));
        p.setProperty("look.realism", Float.toString(pt.realism));
        p.setProperty("brightness", Float.toString(pt.brightness));
        p.setProperty("denoise", Boolean.toString(pt.denoise));
        p.setProperty("renderScale", Float.toString(pt.renderScale));
        p.setProperty("sharpen", Float.toString(pt.sharpen));
        p.setProperty("lowLatency", Integer.toString(pt.lowLatency));
        p.setProperty("ser", Boolean.toString(pt.ser));
        p.setProperty("micromaps", Boolean.toString(pt.omm));
        p.setProperty("upscaler", pt.upscaler == PathTracer.UPSCALER_BASIC ? "basic" : pt.upscaler == PathTracer.UPSCALER_OFF ? "off" : pt.upscaler == PathTracer.UPSCALER_DLSS ? "dlss" : "fsr");
        p.setProperty("cloudShadows", Float.toString(pt.cloudShadows));
        p.setProperty("traceDistance", Integer.toString(pt.traceDistance));
        p.setProperty("leaves", pt.fastLeaves ? "fast" : "fancy");
        p.setProperty("mobShadows", Boolean.toString(pt.entityShadows));
        p.setProperty("hardwareRt", Boolean.toString(pt.hardwareRt));
        p.setProperty("heldLights", Integer.toString(pt.heldLights));
        p.setProperty("particleLight", Boolean.toString(pt.particleLight));
        p.setProperty("reflections", Integer.toString(pt.reflections));
        p.setProperty("bounces", Integer.toString(pt.bounces));
        p.setProperty("denoiser", pt.denoiserMode == PathTracer.DENOISER_STANDARD ? "standard" : "advanced");
        p.setProperty("hand", pt.handMode == PathTracer.HAND_TRACED ? "traced" : "beta");
        p.setProperty("fx.wet", Boolean.toString(pt.fxWet));
        p.setProperty("fx.rainHaze", Boolean.toString(pt.fxHaze));
        p.setProperty("fx.snowSparkle", Boolean.toString(pt.fxSparkle));
        p.setProperty("fx.godRays", Boolean.toString(pt.fxGodRays));
        p.setProperty("fx.wavingPlants", Boolean.toString(pt.fxWaving));
        p.setProperty("fx.waterWaves", Boolean.toString(pt.fxWaves));
        p.setProperty("nights.dark", Boolean.toString(pt.darkNights));
        p.setProperty("hand.collide", Boolean.toString(pt.handCollide));
        return p;
    }

    /**
     * Called every frame with the dimension the player is in. On a change, the settings showing are kept
     * (in memory) for the dimension being left, and the new dimension's own settings, or the shared ones,
     * go on screen. Paused while a test runs, so tests measure what they set.
     */
    public static void onDimension(int id, String name, boolean paused) {
        String key = Integer.toString(id);
        if (paused || key.equals(activeDim)) return;
        if (activeDim != null) {
            if (dims.containsKey(activeDim)) dims.put(activeDim, keepName(capture(), dims.get(activeDim)));
            else global = capture();
        }
        activeDim = key;
        Properties own = dims.get(key);
        if (own != null) {
            if (name != null) own.setProperty("name", name);
            apply(own);
            if (!selfTesting) BetaPT.LOGGER.info("Path tracer: {} has its own settings", name);
        } else apply(global);
    }

    private static Properties keepName(Properties p, Properties old) {
        if (old != null && old.getProperty("name") != null) p.setProperty("name", old.getProperty("name"));
        return p;
    }

    /** True when the dimension the player is in has its own settings. */
    public static boolean ownSettings() { return activeDim != null && dims.containsKey(activeDim); }
    /** True once the player is in a world (so there's a dimension to give settings to). */
    public static boolean inDimension() { return activeDim != null; }

    /**
     * Own settings for this dimension on or off. On: they start as a copy of what's showing. Off: they're
     * dropped and the shared settings come back on screen.
     */
    public static void setOwnSettings(boolean own, String name) {
        if (activeDim == null) return;
        if (own) {
            Properties p = capture();
            if (name != null) p.setProperty("name", name);
            dims.put(activeDim, p);
        } else {
            dims.remove(activeDim);
            apply(global);
        }
    }

    /** Names of the other dimensions that have their own settings (for the menu). */
    public static java.util.List<String> otherDimensionsWithOwnSettings() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var e : dims.entrySet()) if (!e.getKey().equals(activeDim)) out.add(e.getValue().getProperty("name", "dimension " + e.getKey()));
        return out;
    }

    private static void storeShowing() {
        if (ownSettings()) dims.put(activeDim, keepName(capture(), dims.get(activeDim)));
        else global = capture();
    }

    /**
     * For the tests: walks through giving a dimension its own settings, leaving and coming back, a round trip
     * through the file format, and turning them off again; then puts everything back as it was. Never writes
     * the file. Returns "OK" or what went wrong.
     */
    private static boolean selfTesting;
    public static String selfTest() {
        selfTesting = true;
        PathTracer pt = PathTracer.INSTANCE;
        Properties keepGlobal = (Properties) global.clone(), live = capture();
        java.util.Map<String, Properties> keepDims = new java.util.TreeMap<>();
        for (var e : dims.entrySet()) keepDims.put(e.getKey(), (Properties) e.getValue().clone());
        String keepActive = activeDim;
        String result = "OK";
        try {
            dims.clear();
            activeDim = null;
            global = (Properties) live.clone();
            onDimension(0, "Overworld", false);
            pt.renderScale = 1f; pt.fastLeaves = false; pt.traceDistance = 0;
            storeShowing();
            onDimension(7, "Twilight Forest", false);
            if (ownSettings()) return result = "a dimension had its own settings before any were made";
            setOwnSettings(true, "Twilight Forest");
            pt.renderScale = 0.75f; pt.fastLeaves = true; pt.traceDistance = 128;
            storeShowing();
            onDimension(0, "Overworld", false);
            if (pt.renderScale != 1f || pt.fastLeaves || pt.traceDistance != 0) return result = "the overworld kept the Twilight Forest's settings";
            onDimension(7, "Twilight Forest", false);
            if (pt.renderScale != 0.75f || !pt.fastLeaves || pt.traceDistance != 128) return result = "the Twilight Forest's own settings didn't come back";
            Properties g2 = new Properties();
            java.util.Map<String, Properties> d2 = new java.util.TreeMap<>();
            fromFile(toFile(), g2, d2);
            if (!d2.containsKey("7") || !"0.75".equals(d2.get("7").getProperty("renderScale")) || !"fast".equals(d2.get("7").getProperty("leaves"))
                    || !"Twilight Forest".equals(d2.get("7").getProperty("name")) || !"1.0".equals(g2.getProperty("renderScale")))
                return result = "the file format lost a dimension's settings: " + toFile();
            if (!otherDimensionsWithOwnSettings().isEmpty()) return result = "the menu lists the current dimension as another one";
            setOwnSettings(false, null);
            if (ownSettings() || pt.renderScale != 1f || pt.fastLeaves) return result = "turning own settings off didn't bring the shared ones back";
            return result;
        } catch (Throwable t) {
            return result = "failed: " + t;
        } finally {
            global = keepGlobal;
            dims.clear();
            dims.putAll(keepDims);
            activeDim = keepActive;
            apply(live);
            selfTesting = false;
            if (!"OK".equals(result)) BetaPT.LOGGER.warn("Per-dimension settings self-test: {}", result);
        }
    }

    /** Stores what's showing as the current dimension's settings (its own, or the shared ones) and writes the file. */
    public static void save() {
        storeShowing();
        Properties out = toFile();
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                out.store(w, "Beta Path Tracer in-game settings (Options > Video Settings > Path Tracer...). dim.<number>.* = one dimension's own settings");
            }
        } catch (Exception e) {
            BetaPT.LOGGER.error("Could not save {}", file(), e);
        }
    }

    /** A name people know a dimension by: Overworld, Nether, Skylands, or the mod's class name in words (Twilight Forest, Aether). */
    public static String dimensionName(Object d) { return "Overworld"; } // Alpha 1.1.2_01 has one dimension

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
