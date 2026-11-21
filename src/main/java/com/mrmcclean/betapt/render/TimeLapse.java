package com.mrmcclean.betapt.render;

import com.mrmcclean.betapt.BetaPT;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Time-lapse recorder: camera keyframes (where you stand, where you look, the time of day), then a picture per frame
 * at photo mode's quality along a smooth path through them, as numbered PNGs in screenshots/timelapse-<date>/, and an
 * MP4 next to them when ffmpeg is on the PATH.
 *
 * The path: a Catmull-Rom spline through the keyframe positions (it passes through every one), the look direction
 * turned along the shortest arc between keyframes (slerp), the time of day straight from one keyframe to the next,
 * always forward (6000 then 18000 sweeps noon to midnight; 18000 then 6000 goes on through the night to the next noon).
 * While it records the game is paused (TimeLapseScreen) and the camera is moved on the client only.
 *
 * Off by default (Effects > Photo, time-lapse...): the keyframe key does nothing until it is switched on.
 */
public final class TimeLapse {
    private TimeLapse() {}

    // ---- settings (betapt-settings.properties: timelapse.*)
    public static boolean enabled = false;
    /** Pictures in the whole time-lapse. */
    public static int frames = 120;
    public static int fps = 30;
    /** Make an MP4 with ffmpeg afterwards (when it is on the PATH). */
    public static boolean video = true;

    /** A keyframe: the player's position fields (feet in this version), view angles, time of day in ticks. */
    public static final class Key {
        public final double x, y, z;
        public final float yaw, pitch;
        public final long time;
        public Key(double x, double y, double z, float yaw, float pitch, long time) { this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.time = time; }
    }
    public static final List<Key> keys = new ArrayList<>();

    // ---- a recording
    private static boolean running;
    private static int index;
    private static File dir;
    private static Key kept;
    private static volatile boolean advance;
    /** What the last recording made: the folder, and the video (null if none). */
    public static volatile File lastDir, lastVideo;
    public static volatile String status = "";
    /** True while ffmpeg is still encoding the last recording. */
    public static volatile boolean encoding;

    public static boolean running() { return running; }
    public static int index() { return index; }

    // ---- version-specific bits
    private static File gameDir() { return com.mrmcclean.betapt.config.BetaPTConfig.gameDir().toFile(); }
    private static Key here(Minecraft mc) {
        var p = mc.player;
        return new Key(p.x, p.y, p.z, p.yaw, p.pitch, mc.world.ticks);
    }
    private static void place(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        var p = mc.player;
        p.setPositionAndAngles(x, y, z, yaw, pitch);
        if (p.y != y) p.setPositionAndAngles(x, y - (p.y - y), z, yaw, pitch); // Alpha adds the eye height to y: take it off again
        p.lastX = x; p.lastY = p.y; p.lastZ = z;
        p.lastYaw = yaw; p.lastPitch = pitch;
        p.velocityX = p.velocityY = p.velocityZ = 0;
    }
    private static void openScreen(Minecraft mc, boolean on) {
        mc.openScreen(on ? new com.mrmcclean.betapt.gui.TimeLapseScreen() : null);
        if (!on) mc.lockMouse();
    }
    private static boolean timeLapseScreenOpen(Minecraft mc) { return mc.screen instanceof com.mrmcclean.betapt.gui.TimeLapseScreen; }

    // ---- keyframes
    /** Adds a keyframe where the camera is now. Returns a line for the chat. */
    public static String addKey(Minecraft mc) {
        ensureLoaded();
        if (mc.player == null || mc.world == null) return "No world";
        Key k = here(mc);
        keys.add(k);
        save();
        return String.format(Locale.ROOT, "Time-lapse keyframe %d at %.0f %.0f %.0f, %s", keys.size(), k.x, k.y, k.z, PhotoMode.clock(k.time));
    }

    public static void clearKeys() { ensureLoaded(); keys.clear(); save(); }

    private static boolean loaded;
    /** The saved keyframes, read the first time they are needed. */
    public static void ensureLoaded() { if (!loaded) { loaded = true; load(); } }

    private static File keyFile() { return new File(new File(gameDir(), "config"), "betapt-timelapse.txt"); }

    /** Keyframes are kept in config/betapt-timelapse.txt: x y z yaw pitch time, one a line. */
    public static void save() {
        StringBuilder sb = new StringBuilder("# time-lapse keyframes: x y z yaw pitch time-of-day-ticks\n");
        for (Key k : keys) sb.append(String.format(Locale.ROOT, "%.4f %.4f %.4f %.3f %.3f %d%n", k.x, k.y, k.z, k.yaw, k.pitch, k.time));
        try { File f = keyFile(); f.getParentFile().mkdirs(); java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8")); }
        catch (Exception e) { BetaPT.LOGGER.warn("Time-lapse: could not save keyframes: {}", e.toString()); }
    }

    public static void load() {
        keys.clear();
        try {
            File f = keyFile();
            if (!f.isFile()) return;
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 6) continue;
                keys.add(new Key(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]), Long.parseLong(p[5])));
            }
        } catch (Exception e) { BetaPT.LOGGER.warn("Time-lapse: could not read keyframes: {}", e.toString()); }
    }

    // ---- the path
    /** The camera at u (0..1 along the whole time-lapse): position, yaw, pitch, time of day. */
    public static double[] at(double u) {
        int n = keys.size();
        if (n == 1) { Key k = keys.get(0); return new double[]{k.x, k.y, k.z, k.yaw, k.pitch, k.time}; }
        double s = Math.max(0, Math.min(1, u)) * (n - 1);
        int i = Math.min(n - 2, (int) Math.floor(s));
        double t = s - i;
        Key k0 = keys.get(Math.max(0, i - 1)), k1 = keys.get(i), k2 = keys.get(i + 1), k3 = keys.get(Math.min(n - 1, i + 2));
        double x = catmullRom(k0.x, k1.x, k2.x, k3.x, t), y = catmullRom(k0.y, k1.y, k2.y, k3.y, t), z = catmullRom(k0.z, k1.z, k2.z, k3.z, t);
        double[] a = dir(k1.yaw, k1.pitch), b = dir(k2.yaw, k2.pitch), d = slerp(a, b, t);
        float yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[2]));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, d[1]))));
        return new double[]{x, y, z, yaw, pitch, Math.round(timeAt(i) + (timeAt(i + 1) - timeAt(i)) * t)};
    }

    /** Keyframe i's time of day, unwrapped so time always runs forward from the first keyframe. */
    private static long timeAt(int i) {
        long t = keys.get(0).time;
        for (int k = 1; k <= i; k++) {
            long next = keys.get(k).time, mod = ((t % 24000) + 24000) % 24000, step = ((next % 24000) + 24000) % 24000 - mod;
            if (step < 0) step += 24000;
            t += step;
        }
        return t;
    }

    static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    /** Minecraft's look direction for yaw and pitch in degrees. */
    static double[] dir(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        return new double[]{-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
    }

    /** Turns from a to b (unit vectors) along the shortest arc at a steady speed. */
    static double[] slerp(double[] a, double[] b, double t) {
        double c = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
        double th = Math.acos(c);
        if (th < 1e-4) return a;
        double sa = Math.sin((1 - t) * th) / Math.sin(th), sb = Math.sin(t * th) / Math.sin(th);
        return new double[]{a[0] * sa + b[0] * sb, a[1] * sa + b[1] * sb, a[2] * sa + b[2] * sb};
    }

    // ---- recording
    /** Starts recording (needs two keyframes): the game pauses and the pictures are made one by one. */
    public static String start(Minecraft mc) {
        ensureLoaded();
        if (keys.size() < 2) return "A time-lapse needs at least two keyframes (key: " + keyName() + ")";
        if (mc.player == null || mc.world == null) return "No world";
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new java.util.Date());
        dir = new File(new File(gameDir(), "screenshots"), "timelapse-" + stamp);
        dir.mkdirs();
        kept = here(mc);
        index = 0; advance = false; lastVideo = null; lastDir = dir;
        running = true;
        openScreen(mc, true); // PhotoMode.begin happens as it opens
        setup(mc);
        status = "Recording " + frames + " pictures to " + dir.getName();
        BetaPT.LOGGER.info("Time-lapse: {} pictures through {} keyframes into {}", frames, keys.size(), dir);
        return status;
    }

    static String keyName() { return org.lwjgl.input.Keyboard.getKeyName(com.mrmcclean.betapt.config.BetaPTKeys.KEYFRAME.keyCode); }

    private static double[] pose;

    private static void setup(Minecraft mc) {
        pose = at(frames <= 1 ? 0 : index / (double) (frames - 1));
        place(mc, pose[0], pose[1], pose[2], (float) pose[3], (float) pose[4]);
        PhotoMode.timeOverride = (long) pose[5] % 24000;
        PhotoMode.wantFocus = true;
        PhotoMode.next(new File(dir, String.format(Locale.ROOT, "frame_%04d.png", index + 1)), f -> advance = true);
    }

    /** Every frame before the camera is set up: the next picture once the last one is saved, the camera held on the path. */
    public static void beforeFrame(Minecraft mc) {
        if (!running || mc.player == null) return;
        if (advance) {
            advance = false;
            if (++index >= frames) { finish(mc, true); return; }
            setup(mc);
        }
        place(mc, pose[0], pose[1], pose[2], (float) pose[3], (float) pose[4]);
    }

    /** Stops recording (done, or Esc): the camera goes back, then ffmpeg makes the video if wanted. */
    public static void finish(Minecraft mc, boolean done) {
        if (!running) return;
        running = false;
        if (kept != null && mc.player != null) place(mc, kept.x, kept.y, kept.z, kept.yaw, kept.pitch);
        if (timeLapseScreenOpen(mc)) openScreen(mc, false);
        status = (done ? "Time-lapse done: " : "Time-lapse stopped: ") + index + " pictures in " + dir.getName();
        BetaPT.LOGGER.info("Time-lapse: {}", status);
        if (done && video && index > 1) encode(dir, fps);
    }

    /** ffmpeg (from the PATH) turns the numbered pictures into timelapse.mp4, on a background thread. */
    static void encode(File d, int rate) {
        encoding = true;
        Thread t = new Thread(() -> {
            File out = new File(d, "timelapse.mp4");
            try {
                Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", Integer.toString(rate), "-i",
                        new File(d, "frame_%04d.png").getPath(), "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-pix_fmt", "yuv420p", out.getPath())
                        .redirectErrorStream(true).redirectOutput(new File(d, "ffmpeg.log")).start();
                int code = p.waitFor();
                if (code == 0 && out.isFile()) { lastVideo = out; status = "Time-lapse video: " + out.getName() + " in " + d.getName(); }
                else status = "ffmpeg failed (exit " + code + "): see ffmpeg.log in " + d.getName();
            } catch (java.io.IOException e) {
                status = "No ffmpeg on the PATH: the pictures are in " + d.getName() + " (no video)";
            } catch (Exception e) {
                status = "ffmpeg: " + e;
            }
            BetaPT.LOGGER.info("Time-lapse: {}", status);
            encoding = false;
        }, "betapt-timelapse-ffmpeg");
        t.setDaemon(true);
        t.start();
    }
}
