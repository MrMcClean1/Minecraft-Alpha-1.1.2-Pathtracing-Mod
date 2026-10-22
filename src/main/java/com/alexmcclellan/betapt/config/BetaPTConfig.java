package com.alexmcclellan.betapt.config;

import com.alexmcclellan.betapt.BetaPT;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings read from .minecraft/config/betapt.properties at startup.
 * Missing file or keys fall back to the defaults below.
 */
public final class BetaPTConfig {
    public static boolean startEnabled = true;
    public static boolean borderless = false; // fullscreen windowed on the primary monitor

    // Automated test run: load a fixed world, look a fixed way, measure, screenshot, quit.
    public static boolean autotest = false;
    public static String autotestWorld = "PTTest";
    public static long autotestSeed = 1234567890L;
    public static Integer autotestX, autotestZ; // null = use the world spawn
    public static float autotestYaw = Float.NaN; // NaN = "auto": pick the most open view from spawn
    public static float autotestPitch = 8f;
    public static double autotestWarmupSeconds = 3;
    public static double autotestSampleSeconds = 5;
    public static boolean autotestQuit = true;
    /** host:port of a b1.7.3 server the autotest joins at the end to check multiplayer; empty = skip. */
    public static String autotestServer = "";
    /** Skip the singleplayer stages and only run the multiplayer check (for testing a server). */
    public static boolean autotestMpOnly = false;
    /** The modpack test (test/ModTest): every modded block, item, light and dimension. */
    public static boolean modtest = false, modtestQuit = false, modtestQuick = false;
    /** modtest.once: switch the test back off when it finishes (tests started from the menu) */
    public static boolean modtestOnce = false;
    /** autotest.once: switch the benchmark back off when it finishes (started from the menu) */
    public static boolean autotestOnce = false;
    /** test.server: the server the menu's "Multiplayer" test joins (remembered while the test is off) */
    public static String testServer = "";
    /** modtest.only: run just some parts of the modpack test, e.g. "dims" or "gallery,lights" (empty = everything) */
    public static String modtestOnly = "";
    /** modtest.server: host:port of a modpack server to run the multiplayer test on instead (you need to be an op there) */
    public static String modtestServer = "";
    /** false: the multiplayer check runs with Beta's renderer (slow test machines). */
    public static boolean autotestMpTracer = true;
    public static boolean autotestFullscreen = false; // switch to fullscreen (native monitor resolution) for the test

    private BetaPTConfig() {}

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static void load() {
        Path file = gameDir().resolve("config").resolve("betapt.properties");
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException e) {
            BetaPT.LOGGER.error("Could not read {}", file, e);
            return;
        }
        startEnabled = bool(p, "enabled", startEnabled);
        borderless = bool(p, "borderless", borderless);
        autotest = bool(p, "autotest", autotest);
        autotestWorld = p.getProperty("autotest.world", autotestWorld);
        // Optional fixed test spot (block x/z). Beta picks a random spawn point even for the same
        // seed, so pinning the spot keeps the test scene identical across fresh copies of the world.
        String ax = p.getProperty("autotest.x"), az = p.getProperty("autotest.z");
        autotestX = ax == null || ax.isBlank() ? null : Integer.valueOf(ax.trim());
        autotestZ = az == null || az.isBlank() ? null : Integer.valueOf(az.trim());
        autotestSeed = Long.parseLong(p.getProperty("autotest.seed", Long.toString(autotestSeed)).trim());
        String yaw = p.getProperty("autotest.yaw", "auto").trim();
        autotestYaw = yaw.equalsIgnoreCase("auto") ? Float.NaN : Float.parseFloat(yaw);
        autotestPitch = Float.parseFloat(p.getProperty("autotest.pitch", Float.toString(autotestPitch)).trim());
        autotestWarmupSeconds = Double.parseDouble(p.getProperty("autotest.warmupSeconds", Double.toString(autotestWarmupSeconds)).trim());
        autotestSampleSeconds = Double.parseDouble(p.getProperty("autotest.sampleSeconds", Double.toString(autotestSampleSeconds)).trim());
        autotestQuit = bool(p, "autotest.quit", autotestQuit);
        autotestServer = p.getProperty("autotest.server", autotestServer).trim();
        autotestMpOnly = bool(p, "autotest.mpOnly", autotestMpOnly);
        modtest = bool(p, "modtest", modtest);
        modtestQuit = bool(p, "modtest.quit", modtestQuit);
        modtestQuick = bool(p, "modtest.quick", modtestQuick);
        modtestOnly = p.getProperty("modtest.only", modtestOnly).trim().toLowerCase(java.util.Locale.ROOT);
        modtestServer = p.getProperty("modtest.server", modtestServer).trim();
        modtestOnce = bool(p, "modtest.once", modtestOnce);
        autotestOnce = bool(p, "autotest.once", autotestOnce);
        testServer = p.getProperty("test.server", "").trim();
        if (testServer.isEmpty()) testServer = !modtestServer.isEmpty() ? modtestServer : p.getProperty("autotest.server", "").trim();
        autotestMpTracer = bool(p, "autotest.mpTracer", autotestMpTracer);
        autotestFullscreen = bool(p, "autotest.fullscreen", autotestFullscreen);
        BetaPT.LOGGER.info("Loaded {} (autotest={})", file, autotest);
    }

    // ------------------------------------------------------------------ the menu's test switch

    public enum TestMode { OFF("Off"), QUICK("Quick"), FULL("Full"), MULTIPLAYER("Multiplayer"), BENCHMARK("Benchmark");
        public final String label; TestMode(String l) { label = l; } }

    /** The test that runs at the next title screen, as the menu shows it. */
    public static TestMode testMode() {
        if (autotest) return TestMode.BENCHMARK;
        if (!modtest) return TestMode.OFF;
        if (!modtestServer.isEmpty()) return TestMode.MULTIPLAYER;
        return modtestQuick ? TestMode.QUICK : TestMode.FULL;
    }

    /**
     * Sets the test for the next title screen (Quick: a few minutes; Full: everything; Multiplayer: joins
     * test.server, quick) and writes it to betapt.properties. Started from the menu, a test runs once:
     * it switches itself back off when it finishes, and the game stays open.
     */
    public static void setTestMode(TestMode m) {
        boolean bench = m == TestMode.BENCHMARK;
        if (bench || autotest) {
            // the benchmark (AutoTest: timings, cost probes, time-of-day shots; joins autotest.server if set)
            autotest = bench; autotestOnce = bench; autotestQuit = false;
            java.util.Map<String, String> a = new java.util.LinkedHashMap<>();
            a.put("autotest", Boolean.toString(bench)); a.put("autotest.once", Boolean.toString(bench)); a.put("autotest.quit", "false");
            setProperties(a);
            // (a benchmark that already ran this session runs again at the next launch)
            if (bench) { modtest = false; setProperties(java.util.Map.of("modtest", "false")); return; }
        }
        modtest = m != TestMode.OFF;
        modtestQuick = m == TestMode.QUICK || m == TestMode.MULTIPLAYER;
        modtestServer = m == TestMode.MULTIPLAYER ? testServer : "";
        modtestOnce = true;
        modtestQuit = false;
        modtestOnly = "";
        java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("modtest", Boolean.toString(modtest));
        v.put("modtest.quick", Boolean.toString(modtestQuick));
        v.put("modtest.server", modtestServer);
        v.put("modtest.once", "true");
        v.put("modtest.quit", "false");
        v.put("modtest.only", "");
        if (!testServer.isEmpty()) v.put("test.server", testServer);
        if (modtest) { autotest = false; v.put("autotest", "false"); }
        if (m == TestMode.OFF) { autotest = false; v.put("autotest", "false"); }
        setProperties(v);
        
    }

    /** Changes (or adds) these keys in betapt.properties, keeping every other line and comment as it is. */
    public static void setProperties(java.util.Map<String, String> values) {
        Path file = gameDir().resolve("config").resolve("betapt.properties");
        try {
            java.util.List<String> lines = Files.exists(file) ? new java.util.ArrayList<>(Files.readAllLines(file)) : new java.util.ArrayList<>();
            java.util.Set<String> done = new java.util.HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.startsWith("#") || !l.contains("=")) continue;
                String k = l.substring(0, l.indexOf('=')).trim();
                if (values.containsKey(k)) { lines.set(i, k + "=" + values.get(k)); done.add(k); }
            }
            for (var e : values.entrySet()) if (!done.contains(e.getKey())) lines.add(e.getKey() + "=" + e.getValue());
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            BetaPT.LOGGER.error("Could not write {}", file, e);
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(def)).trim());
    }
}
