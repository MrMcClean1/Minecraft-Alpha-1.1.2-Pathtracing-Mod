package com.mrmcclean.betapt.bots;

import net.minecraft.entity.mob.player.PlayerEntity;
import org.lwjgl.input.Keyboard;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice chat (optional: voiceChat=true in classicbots.properties, or /bot voice on), the same as the Tekkit bots'.
 * Hold the voice key (V) in the game and speak; the words go to the bot you look at as if typed ("Ash, follow me").
 * Bots' lines shown in chat are spoken, each bot with its own voice. Nothing is heard or said during the bot test.
 * The engines are in Voice (whisper.cpp and Piper in .minecraft/classicbots-voice, else Windows' own speech).
 * Each game calls tick() every client tick and shown() for each chat line it shows.
 */
public final class VoiceChat {
    private VoiceChat() {}

    static final ExecutorService HEAR = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Bot voice in"); t.setDaemon(true); return t; });
    static final ExecutorService SPEAK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Bot voice out"); t.setDaemon(true); return t; });
    static final Queue<String> heard = new ConcurrentLinkedQueue<>();
    static volatile boolean recording;
    static volatile String target;
    static File gameDir = new File(".");
    static int clip, ticks;
    /** Bots' names in this world (refreshed now and then), for telling their chat lines from players'. */
    static final Set<String> known = Collections.synchronizedSet(new HashSet<>(Arrays.asList(Orders.NAMES)));

    /** Every client tick: the key held starts and stops recording; words heard go into chat on the game's thread. */
    public static void tick(boolean inGame, PlayerEntity p, File dir) {
        Bots.keepSafe(p); // the player can't be hurt (invulnerable=true by default)
        if (inGame && p != null) Bots.autoSpawn(p, Names.world(p)); // bots that join you by themselves (autoSpawn), every client tick's chance
        if (!BotConfig.voiceChat || BotTest.ON) return;
        gameDir = dir;
        if (p != null && ticks++ % 40 == 0) for (BotEntity b : Bots.bots(Names.world(p))) known.add(b.botName());
        int code = Keyboard.getKeyIndex(BotConfig.voiceKey.trim().toUpperCase(Locale.ROOT));
        boolean down = inGame && p != null && Keyboard.isCreated() && Keyboard.isKeyDown(code == Keyboard.KEY_NONE ? Keyboard.KEY_V : code);
        if (down && !recording) start(p);
        else if (!down && recording) recording = false;
        for (String s; p != null && (s = heard.poll()) != null; ) {
            if (s.isEmpty()) { BotsClient.show("(voice: didn't catch that)"); continue; }
            Bots.broadcast(Names.world(p), "<" + p.name + "> " + s);
            Bots.chat(p, s);
        }
    }

    /** The bot the player looks at (the nearest one in front, within 32 blocks), or null. */
    static String lookedAt(PlayerEntity p) {
        double yaw = Math.toRadians(p.yaw + Names.YAW_OFFSET), pitch = Math.toRadians(p.pitch);
        double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
        String best = null; double bd = 0.95;
        for (BotEntity b : Bots.bots(Names.world(p))) {
            double dx = b.x - p.x, dy = b.y - p.y, dz = b.z - p.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 32 || d < 0.1) continue;
            double dot = (dx * lx + dy * ly + dz * lz) / d;
            if (dot > bd) { bd = dot; best = b.botName(); }
        }
        return best;
    }

    static void start(PlayerEntity p) {
        target = lookedAt(p);
        try {
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, Voice.MIC));
            line.open(Voice.MIC);
            line.start();
            recording = true;
            BotsClient.show("(voice: listening" + (target != null ? " to " + target : "") + "...)");
            Thread t = new Thread(() -> {
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                byte[] buf = new byte[3200];
                while (recording && pcm.size() < 16000 * 2 * 30) { int n = line.read(buf, 0, buf.length); if (n > 0) pcm.write(buf, 0, n); }
                line.stop(); line.close();
                byte[] audio = pcm.toByteArray();
                if (audio.length < 16000 * 2 / 3) return; // a tap, not speech
                String to = target;
                HEAR.submit(() -> {
                    try {
                        File wav = new File(Voice.dir(gameDir), "heard.wav");
                        Voice.writeWav(audio, wav);
                        String text = Voice.transcribe(gameDir, wav);
                        heard.add(text.isEmpty() ? "" : to != null ? to + ", " + text : text);
                    } catch (Throwable e) { Bots.log("Voice chat: couldn't hear that: " + e); }
                });
            }, "Bot voice mic");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            recording = false;
            BotsClient.show("(voice: no microphone found)");
            Bots.log("Voice chat: no microphone (" + e + ")");
        }
    }

    /** For the bot tests: set when testVoice() is done, "PASS ..." or "FAIL ...". */
    public static volatile String testResult;

    /** For the bot tests: a bot's line to speech and back to words (files only, no speakers or microphone), off the game's thread. */
    public static void testVoice(File dir, String who) {
        testResult = null;
        Thread t = new Thread(() -> {
            try {
                File d = Voice.dir(dir), wav = new File(d, "test-said.wav");
                long t0 = System.nanoTime();
                boolean said = Voice.synthesize(dir, "The iron is in the chest by the furnace.", who, wav);
                long t1 = System.nanoTime();
                String back = said ? Voice.transcribe(dir, wav) : "";
                long t2 = System.nanoTime();
                wav.delete();
                String l = back.toLowerCase(Locale.ROOT);
                boolean ok = said && l.contains("iron") && l.contains("furnace") && Voice.sttEngine(d).equals("whisper") && Voice.ttsEngine(d).equals("piper")
                        && Voice.botLine("<" + who + "> On my way.", Arrays.asList(who)) != null && Voice.botLine("<Someone> hi", Arrays.asList(who)) == null;
                testResult = (ok ? "PASS" : "FAIL") + " voice: " + Voice.ttsEngine(d) + " said it in " + (t1 - t0) / 1_000_000 + " ms, "
                        + Voice.sttEngine(d) + " heard \"" + back + "\" in " + (t2 - t1) / 1_000_000 + " ms";
            } catch (Throwable e) { testResult = "FAIL voice: " + e; }
        }, "Bot voice test");
        t.setDaemon(true);
        t.start();
    }

    /** A line shown in chat: a bot's is spoken. */
    static void shown(String line) {
        if (!BotConfig.voiceChat || BotTest.ON || line == null) return;
        String[] l;
        synchronized (known) { l = Voice.botLine(line, new ArrayList<>(known)); }
        if (l == null) return;
        String text = Voice.speakable(l[1]), who = l[0];
        File out = new File(Voice.dir(gameDir), "said-" + (clip++ % 4) + ".wav");
        SPEAK.submit(() -> { if (Voice.synthesize(gameDir, text, who, out)) Voice.play(out, 1f); });
    }
}
