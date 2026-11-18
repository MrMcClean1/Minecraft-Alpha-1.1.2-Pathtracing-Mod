package com.mrmcclean.betapt.bots;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tests run muted: music and sound go to 0 while one runs, and back to the player's own levels before the game
 * closes (only in memory: the options file isn't written, so a test can never leave the game silent).
 */
public final class TestQuiet {
    private TestQuiet() {}

    private static final Map<Field, Float> saved = new LinkedHashMap<>();
    private static Object options;

    /** The volume fields: those named music or sound, else (unnamed builds) the first two floats, which are those two. */
    private static Field[] fields(Object o) {
        java.util.List<Field> named = new java.util.ArrayList<>(), floats = new java.util.ArrayList<>();
        for (Field f : o.getClass().getFields()) {
            if (f.getType() != float.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            floats.add(f);
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("music") || n.contains("sound")) named.add(f);
        }
        if (named.isEmpty()) named = floats.subList(0, Math.min(2, floats.size()));
        return named.toArray(new Field[0]);
    }

    public static void mute(Object gameOptions) {
        if (gameOptions == null || options != null) return;
        options = gameOptions;
        try { for (Field f : fields(gameOptions)) { saved.put(f, f.getFloat(gameOptions)); f.setFloat(gameOptions, 0); } } catch (Throwable ignored) {}
        Bots.log("Test: music and sound off until it ends (were " + saved.values() + ")");
    }

    public static void restore() {
        if (options == null) return;
        try { for (Map.Entry<Field, Float> e : saved.entrySet()) e.getKey().setFloat(options, e.getValue()); } catch (Throwable ignored) {}
        saved.clear(); options = null;
    }
}
