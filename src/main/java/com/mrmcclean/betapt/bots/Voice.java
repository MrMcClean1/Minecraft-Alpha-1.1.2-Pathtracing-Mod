package com.mrmcclean.betapt.bots;


import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voice chat's parts that aren't tied to the game window (optional, BotConfig.voiceChat): speech to text and text to
 * speech as programs on this PC, nothing online. Files live in .minecraft/classicbots-voice.
 *
 * Speech in: whisper.cpp (voiceWhisper + voiceWhisperModel in that folder) when they're there, otherwise the speech
 * recognition built into Windows (System.Speech, through Windows PowerShell). Speech out: Piper (voicePiper +
 * voiceTtsModel) when it's there, otherwise the Windows voice. "windows" or "whisper"/"piper" in voiceStt/voiceTts
 * picks one; "auto" takes the better one that's installed.
 */
public final class Voice {
    private Voice() {}

    public static File dir(File gameDir) { File d = new File(gameDir, "classicbots-voice"); d.mkdirs(); return d; }

    /** A program or model in the voice folder, or in its whisper/ or piper/ subfolder (as the releases unpack). */
    static File find(File d, String name) {
        for (String sub : new String[]{"", "whisper", "piper"}) { File f = new File(sub.isEmpty() ? d : new File(d, sub), name); if (f.isFile()) return f; }
        return new File(d, name);
    }
    /** The configured file, else the release's own name (an older config may name something not there). */
    static File find(File d, String name, String standard) { File f = find(d, name); return f.isFile() ? f : find(d, standard); }
    static File whisperExe(File d) { return find(d, BotConfig.voiceWhisper, "whisper-cli.exe"); }
    static File whisperModel(File d) { return find(d, BotConfig.voiceWhisperModel, "ggml-base.en.bin"); }
    static File piperExe(File d) { return find(d, BotConfig.voicePiper, "piper.exe"); }
    static File piperVoice(File d) { return find(d, BotConfig.voiceTtsModel, "en_US-libritts_r-medium.onnx"); }
    static boolean whisperThere(File d) { return whisperExe(d).isFile() && whisperModel(d).isFile(); }
    static boolean piperThere(File d) { return piperExe(d).isFile() && piperVoice(d).isFile(); }

    /** Speakers in the Piper voice (its .onnx.json says), else the config's number. */
    static int speakers(File d) {
        try {
            String j = new String(java.nio.file.Files.readAllBytes(new File(piperVoice(d).getPath() + ".json").toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"num_speakers\"\\s*:\\s*(\\d+)").matcher(j);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {}
        return BotConfig.voicePiperSpeakers;
    }
    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    public static String sttEngine(File d) {
        String m = BotConfig.voiceStt.toLowerCase(Locale.ROOT);
        if (m.equals("whisper")) return whisperThere(d) ? "whisper" : "none";
        if (m.equals("windows")) return windows() ? "windows" : "none";
        return whisperThere(d) ? "whisper" : windows() ? "windows" : "none";
    }

    public static String ttsEngine(File d) {
        String m = BotConfig.voiceTts.toLowerCase(Locale.ROOT);
        if (m.equals("piper")) return piperThere(d) ? "piper" : "none";
        if (m.equals("windows")) return windows() ? "windows" : "none";
        return piperThere(d) ? "piper" : windows() ? "windows" : "none";
    }

    /** What's set up, for /bot voice. */
    public static String describe(File gameDir) {
        File d = dir(gameDir);
        return "Voice chat: hold " + BotConfig.voiceKey + " and speak to the bot you look at. Speech in: " + sttEngine(d)
                + (whisperThere(d) ? "" : " (for Whisper put " + BotConfig.voiceWhisper + " and " + BotConfig.voiceWhisperModel + " in " + d + ")")
                + ". Speech out: " + ttsEngine(d) + (piperThere(d) ? "" : " (for Piper put " + BotConfig.voicePiper + " and " + BotConfig.voiceTtsModel + " in " + d + ")") + ".";
    }

    // ------------------------------------------------------------------ speech to text

    /** The words in a 16 kHz mono WAV, or "" (nothing understood, or no engine). */
    public static String transcribe(File gameDir, File wav) {
        File d = dir(gameDir);
        switch (sttEngine(d)) {
            case "whisper": {
                String out = run(Arrays.asList(whisperExe(d).getPath(), "-m", whisperModel(d).getPath(),
                        "-f", wav.getPath(), "-nt", "-np", "-l", "en"), null, 60);
                return clean(out);
            }
            case "windows":
                return clean(ps("Add-Type -AssemblyName System.Speech; $r = New-Object System.Speech.Recognition.SpeechRecognitionEngine([Globalization.CultureInfo]'en-US');"
                        + " $r.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar)); $r.SetInputToWaveFile('" + wav.getAbsolutePath().replace("'", "''") + "');"
                        + " $t = @(); while ($true) { $x = $r.Recognize(); if ($x -eq $null) { break }; $t += $x.Text }; $t -join ' '", null, 60));
            default: return "";
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
        return s.equalsIgnoreCase("you") ? "" : s; // Whisper's guess on silence
    }

    // ------------------------------------------------------------------ text to speech

    /** Speech for a line into a WAV file; each bot keeps the same voice (from its name). True if the file was made. */
    public static boolean synthesize(File gameDir, String text, String who, File out) {
        File d = dir(gameDir);
        out.delete();
        switch (ttsEngine(d)) {
            case "piper": {
                List<String> cmd = new ArrayList<>(Arrays.asList(piperExe(d).getPath(), "-m", piperVoice(d).getPath(), "-f", out.getPath()));
                int speakers = speakers(d);
                if (speakers > 1) { cmd.add("-s"); cmd.add(String.valueOf(Math.abs(who.hashCode()) % speakers)); }
                run(cmd, text, 60);
                break;
            }
            case "windows":
                ps("Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $v = @($s.GetInstalledVoices() | Where-Object { $_.Enabled });"
                        + " if ($v.Count -gt 0) { $s.SelectVoice($v[" + Math.abs(who.hashCode()) + " % $v.Count].VoiceInfo.Name) }; $s.Rate = 1;"
                        + " $s.SetOutputToWaveFile('" + out.getAbsolutePath().replace("'", "''") + "'); $s.Speak([Console]::In.ReadToEnd()); $s.Dispose()", text, 60);
                break;
            default: return false;
        }
        return out.isFile() && out.length() > 1000;
    }

    /** Plays a WAV (on the calling thread, until it ends). */
    public static void play(File wav, float volume) {
        if (BotTest.ON || TestQuiet.on()) return; // never a sound while a test runs (a Clip goes past the game's own volume)
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav)) {
            Clip c = AudioSystem.getClip();
            c.open(in);
            try { FloatControl g = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN); g.setValue(Math.max(g.getMinimum(), (float) (20 * Math.log10(Math.max(1e-4, volume))))); } catch (Throwable ignored) {}
            c.start();
            Thread.sleep(Math.max(100, c.getMicrosecondLength() / 1000 + 100));
            c.close();
        } catch (Throwable e) { Bots.log("Voice playback: " + e); }
    }

    // ------------------------------------------------------------------ chat lines

    private static final Pattern LINE = Pattern.compile("^<([A-Za-z0-9_]{1,16})> (.+)$");

    /** {bot name, what it said} for a bot's chat line, or null for anyone else's. */
    public static String[] botLine(String chat, Collection<String> bots) {
        Matcher m = LINE.matcher(chat.trim());
        if (!m.matches()) return null;
        for (String b : bots) if (b.equalsIgnoreCase(m.group(1))) return new String[]{m.group(1), m.group(2)};
        return null;
    }

    /** What gets said aloud: the words, without colour codes and the like. */
    public static String speakable(String s) { return s.replaceAll("§.", "").replaceAll("[<>*_#]", " ").replaceAll("\\s+", " ").trim(); }

    // ------------------------------------------------------------------ WAV and processes

    public static final AudioFormat MIC = new AudioFormat(16000f, 16, 1, true, false);

    public static void writeWav(byte[] pcm, File out) throws IOException {
        try (AudioInputStream s = new AudioInputStream(new ByteArrayInputStream(pcm), MIC, pcm.length / MIC.getFrameSize())) {
            AudioSystem.write(s, AudioFileFormat.Type.WAVE, out);
        }
    }

    /** Converts any WAV to 16 kHz mono 16-bit (what Whisper and the recognizer want). */
    public static File toMic(File in, File out) {
        try (AudioInputStream src = AudioSystem.getAudioInputStream(in)) {
            AudioInputStream conv = AudioSystem.getAudioInputStream(MIC, src);
            AudioSystem.write(conv, AudioFileFormat.Type.WAVE, out);
            return out;
        } catch (Throwable e) { return in; }
    }

    static String ps(String script, String stdin, int seconds) {
        String enc = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return run(Arrays.asList("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", enc), stdin, seconds);
    }

    static String run(List<String> cmd, String stdin, int seconds) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            if (stdin != null) try (OutputStream o = p.getOutputStream()) { o.write(stdin.getBytes(StandardCharsets.UTF_8)); } else p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread t = new Thread(() -> { try (InputStream i = p.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = i.read(b)) > 0) out.write(b, 0, n); } catch (IOException ignored) {} }, "voice-out");
            Thread e = new Thread(() -> { try (InputStream i = p.getErrorStream()) { byte[] b = new byte[4096]; while (i.read(b) > 0) { } } catch (IOException ignored) {} }, "voice-err");
            t.setDaemon(true); e.setDaemon(true); t.start(); e.start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
            t.join(2000);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) { Bots.log("Voice: " + cmd.get(0) + " failed: " + e); return ""; }
    }
}
