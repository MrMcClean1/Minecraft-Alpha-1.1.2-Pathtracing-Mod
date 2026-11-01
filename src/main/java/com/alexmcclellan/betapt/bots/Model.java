package com.alexmcclellan.betapt.bots;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The optional language model (Ollama, or an OpenAI-style server such as LM Studio): it only words what bots say and
 * reads requests the rules don't understand. One background thread; answers come back through {@link #poll()} on the
 * game thread. Off, missing, slow or busy: the caller's fallback runs instead. Nothing here costs credits.
 */
public final class Model {
    private Model() {}

    private static final class Job {
        final String system, user; final Consumer<String> onAnswer; final Runnable onFail; final long deadline;
        Job(String system, String user, Consumer<String> a, Runnable f) { this.system = system; this.user = user; onAnswer = a; onFail = f; deadline = System.currentTimeMillis() + 30000; }
    }
    private static final LinkedBlockingQueue<Job> jobs = new LinkedBlockingQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> done = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<Long> recent = new ArrayDeque<>();
    private static volatile boolean available, checked;
    public static volatile String status = "not checked";
    private static Thread worker;

    public static boolean ready() { return BotConfig.modelOn && !BotConfig.modelUrl.isEmpty() && available; }

    /** Ask; onAnswer or onFail runs later on the game thread (from poll()). */
    public static void ask(String system, String user, Consumer<String> onAnswer, Runnable onFail) {
        start();
        long now = System.currentTimeMillis();
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 60000) recent.pollFirst();
            if (!ready() || recent.size() >= BotConfig.modelPerMinute || jobs.size() > 3) { done.add(onFail); return; }
            recent.addLast(now);
        }
        jobs.add(new Job(system, user, onAnswer, onFail));
    }

    /** Run finished answers; call once a tick from the game thread. */
    public static void poll() {
        start();
        Runnable r;
        int n = 0;
        while (n++ < 8 && (r = done.poll()) != null) try { r.run(); } catch (Throwable ignored) {}
    }

    private static synchronized void start() {
        if (worker != null) return;
        worker = new Thread(Model::work, "Classic Bots model");
        worker.setDaemon(true);
        worker.start();
    }

    private static void work() {
        long lastCheck = 0;
        while (true) {
            try {
                if (System.currentTimeMillis() - lastCheck > (available ? 300000 : 60000)) { check(); lastCheck = System.currentTimeMillis(); }
                Job j = jobs.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                if (j == null) continue;
                if (System.currentTimeMillis() > j.deadline) { done.add(j.onFail); continue; }
                String a;
                try { a = chat(j.system, j.user); } catch (Exception e) { a = null; available = false; status = "error: " + e.getMessage(); }
                a = clean(a);
                if (a == null || a.isEmpty()) done.add(j.onFail);
                else { final String ans = a; done.add(() -> j.onAnswer.accept(ans)); }
            } catch (InterruptedException ie) { return; } catch (Throwable ignored) {}
        }
    }

    private static void check() {
        checked = true;
        if (!BotConfig.modelOn || BotConfig.modelUrl.isEmpty()) { available = false; status = "off"; return; }
        try {
            String tags = http(BotConfig.modelUrl + ("openai".equalsIgnoreCase(BotConfig.modelApi) ? "/v1/models" : "/api/tags"), null, 4000);
            available = tags != null && (tags.contains(BotConfig.model) || "openai".equalsIgnoreCase(BotConfig.modelApi));
            status = available ? "using " + BotConfig.model : tags == null ? "no model server at " + BotConfig.modelUrl : BotConfig.model + " isn't installed (ollama pull " + BotConfig.model + ")";
        } catch (Exception e) { available = false; status = "no model server at " + BotConfig.modelUrl; }
    }

    private static String chat(String system, String user) throws Exception {
        boolean openai = "openai".equalsIgnoreCase(BotConfig.modelApi);
        String msgs = "[{\"role\":\"system\",\"content\":" + q(system) + "},{\"role\":\"user\",\"content\":" + q(user) + "}]";
        String body = openai
                ? "{\"model\":" + q(BotConfig.model) + ",\"messages\":" + msgs + ",\"max_tokens\":120,\"temperature\":0.7}"
                : "{\"model\":" + q(BotConfig.model) + ",\"messages\":" + msgs + ",\"stream\":false,\"think\":false,\"options\":{\"num_predict\":120,\"temperature\":0.7}}";
        String r = http(BotConfig.modelUrl + (openai ? "/v1/chat/completions" : "/api/chat"), body, BotConfig.modelTimeoutMs);
        if (r == null) return null;
        Matcher m = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(r);
        return m.find() ? unq(m.group(1)) : null;
    }

    // ------------------------------------------------------------------ text safety

    private static final Pattern NEVER = Pattern.compile("\\b(nigg\\w*|fag\\w*|retard\\w*|tranny\\w*|kike\\w*|spic\\w*|chink\\w*|cunt\\w*|slut\\w*|whore\\w*|rape\\w*|kill yourself|kys|suicide|porn\\w*|sex\\w*|nude\\w*)\\b", Pattern.CASE_INSENSITIVE);

    /** One short line Minecraft can show: no thinking blocks, quotes, emoji or bad words. */
    public static String clean(String s) {
        if (s == null) return null;
        s = s.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[\\r\\n]+", " ").trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= 32 && c < 127) b.append(c);
        s = b.toString().replace("—", ", ").replace(" - ", ", ").trim();
        if (NEVER.matcher(s).find()) return null;
        if (s.length() > 95) { int cut = s.lastIndexOf(' ', 95); s = s.substring(0, cut > 40 ? cut : 95); }
        return s;
    }

    // ------------------------------------------------------------------ http + json bits

    private static String http(String url, String body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(Math.min(timeout, 4000));
        c.setReadTimeout(timeout);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        if (c.getResponseCode() / 100 != 2) return null;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String unq(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': b.append('\n'); break;
                case 't': b.append(' '); break;
                case 'u': if (i + 4 < s.length()) { try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); } catch (Exception ignored) {} i += 4; } break;
                default: b.append(n);
            }
        }
        return b.toString();
    }
}
