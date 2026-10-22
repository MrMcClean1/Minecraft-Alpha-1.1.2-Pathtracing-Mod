package com.alexmcclellan.betapt.config;

import net.minecraft.client.options.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * The path tracer's keys, listed in Options, Controls so they can be changed there (and saved in options.txt).
 * Defaults F7, F9 and F10, Home for the view (F8 is Beta's own smooth camera, which slowed the mouse), and F4 for the presets. If another mod already uses one of them (in the modpack, Block Helper takes F7 and F9),
 * ours moves to a free key (F6, F12, then End) and the log says which.
 */
public final class BetaPTKeys {
    private BetaPTKeys() {}

    public static final KeyBinding TOGGLE = new KeyBinding("Path tracer on/off", Keyboard.KEY_F7);
    public static final KeyBinding VIEW = new KeyBinding("Path tracer view", Keyboard.KEY_HOME);
    public static final KeyBinding DENOISER = new KeyBinding("Path tracer denoiser", Keyboard.KEY_F9);
    public static final KeyBinding LOOK = new KeyBinding("Path tracer look", Keyboard.KEY_F10);
    /** Steps through the presets: Off (Beta's renderer), Beta, Faithful, Realistic. */
    public static final KeyBinding PRESET = new KeyBinding("Path tracer preset", Keyboard.KEY_F4);
    public static final KeyBinding[] ALL = {TOGGLE, VIEW, DENOISER, LOOK, PRESET};
    private static final int[] SPARE = {Keyboard.KEY_F6, Keyboard.KEY_F12, Keyboard.KEY_END, Keyboard.KEY_INSERT, Keyboard.KEY_PRIOR};

    private static boolean ours(KeyBinding k) {
        for (KeyBinding o : ALL) if (o == k) return true;
        return false;
    }

    /** Adds ours to the option list (before options.txt is read, so saved keys apply). */
    public static KeyBinding[] withOurs(KeyBinding[] keys) {
        for (KeyBinding k : keys) if (ours(k)) return keys;
        KeyBinding[] out = java.util.Arrays.copyOf(keys, keys.length + ALL.length);
        System.arraycopy(ALL, 0, out, keys.length, ALL.length);
        return out;
    }

    /** After options.txt is read: move any of ours that clashes with another mod's key. */
    public static void resolveClashes(KeyBinding[] keys) {
        for (KeyBinding mine : ALL) {
            if (!taken(keys, mine, mine.keyCode)) continue;
            for (int spare : SPARE) {
                if (taken(keys, mine, spare)) continue;
                com.alexmcclellan.betapt.BetaPT.LOGGER.info("Key {} is used by another mod: \"{}\" moved to {}",
                        Keyboard.getKeyName(mine.keyCode), mine.name, Keyboard.getKeyName(spare));
                mine.keyCode = spare;
                break;
            }
        }
    }

    private static boolean taken(KeyBinding[] keys, KeyBinding mine, int code) {
        for (KeyBinding k : keys) if (k != mine && k.keyCode == code) return true;
        return false;
    }
}
