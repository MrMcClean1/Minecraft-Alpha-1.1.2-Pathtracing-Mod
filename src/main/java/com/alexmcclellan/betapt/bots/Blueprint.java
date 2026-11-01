package com.alexmcclellan.betapt.bots;

import java.util.*;

/**
 * What a bot can build, as plain block lists (no Minecraft classes: the same plans in every version). Each block is
 * {right, up, forward, id} from the front-left corner, with id 0 for a spot that must be cleared (inside rooms and
 * doorways). Blocks: 4 cobblestone, 5 planks, 17 log. Nothing is taller than 9, so a bot standing on the ground
 * (reaching up like a player on a step ladder) can put every block.
 */
public final class Blueprint {
    public final String name;
    public final List<int[]> blocks = new ArrayList<>();
    public final int width, depth, height;

    private Blueprint(String name, int w, int d, int h) { this.name = name; width = w; depth = d; height = h; }

    public static final int STONE = 4, PLANKS = 5, LOG = 17;

    /** "hut", "house", "tower", "wall" (length n), "castle"; null for anything else. */
    public static Blueprint of(String word, int n) {
        if (word == null) return null;
        String w = word.toLowerCase(Locale.ROOT);
        if (w.contains("castle") || w.contains("fort") || w.contains("keep")) return castle();
        if (w.contains("tower")) return tower(5, 8);
        if (w.contains("wall")) return wall(n > 1 ? Math.min(n, 64) : 9);
        if (w.contains("house") || w.contains("home") || w.contains("cabin")) return house();
        if (w.contains("hut") || w.contains("shelter") || w.contains("shack")) return hut();
        return null;
    }

    public static String names() { return "a hut, a house, a tower, a wall (say how long) or a castle"; }

    /** How many of each block id it takes (cleared spots not counted). */
    public Map<Integer, Integer> needs() {
        Map<Integer, Integer> m = new TreeMap<>();
        for (int[] b : blocks) if (b[3] != 0) m.merge(b[3], 1, Integer::sum);
        return m;
    }

    // ---- the plans

    static Blueprint hut() {
        Blueprint p = new Blueprint("hut", 5, 5, 4);
        p.room(0, 0, 5, 5, 3, STONE, STONE);
        p.roof(0, 0, 5, 5, 3, PLANKS);
        p.door(2, 0);
        return p.sorted();
    }

    static Blueprint house() {
        Blueprint p = new Blueprint("house", 7, 7, 5);
        p.room(0, 0, 7, 7, 4, PLANKS, LOG);
        p.roof(0, 0, 7, 7, 4, PLANKS);
        p.door(3, 0);
        p.window(0, 2, 3); p.window(6, 2, 3); p.window(3, 2, 6);
        return p.sorted();
    }

    static Blueprint tower(int size, int h) {
        Blueprint p = new Blueprint("tower", size, size, h + 1);
        p.addTower(0, 0, size, h, true);
        return p.sorted();
    }

    static Blueprint wall(int len) {
        Blueprint p = new Blueprint("wall", len, 1, 5);
        for (int x = 0; x < len; x++) {
            for (int y = 0; y < 4; y++) p.put(x, y, 0, STONE);
            if (x % 2 == 0) p.put(x, 4, 0, STONE);
        }
        return p.sorted();
    }

    /** A 15 x 15 courtyard: walls 4 high with battlements, a 5 x 5 tower on each corner, a gate in front. */
    static Blueprint castle() {
        int s = 15, wallH = 4;
        Blueprint p = new Blueprint("castle", s, s, 9);
        for (int i = 0; i < s; i++)
            for (int[] c : new int[][]{{i, 0}, {i, s - 1}, {0, i}, {s - 1, i}}) {
                for (int y = 0; y < wallH; y++) p.put(c[0], y, c[1], STONE);
                if (i % 2 == 0) p.put(c[0], wallH, c[1], STONE);
            }
        // the gate: 3 wide, 3 high in the middle of the front wall
        for (int x = s / 2 - 1; x <= s / 2 + 1; x++) for (int y = 0; y < 3; y++) p.put(x, y, 0, 0);
        for (int[] c : new int[][]{{0, 0}, {s - 5, 0}, {0, s - 5}, {s - 5, s - 5}}) p.addTower(c[0], c[1], 5, 7, false);
        return p.sorted();
    }

    // ---- pieces

    private final Map<Long, int[]> at = new LinkedHashMap<>();

    private void put(int x, int y, int z, int id) { at.put(((long) x << 40) ^ ((long) y << 20) ^ z, new int[]{x, y, z, id}); }

    /** Four walls (corner posts of another block), clearing the inside. */
    private void room(int x0, int z0, int w, int d, int h, int wallId, int cornerId) {
        for (int x = x0; x < x0 + w; x++)
            for (int z = z0; z < z0 + d; z++)
                for (int y = 0; y < h; y++) {
                    boolean edgeX = x == x0 || x == x0 + w - 1, edgeZ = z == z0 || z == z0 + d - 1;
                    put(x, y, z, edgeX && edgeZ ? cornerId : edgeX || edgeZ ? wallId : 0);
                }
    }

    private void roof(int x0, int z0, int w, int d, int y, int id) {
        for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) put(x, y, z, id);
    }

    private void door(int x, int z) { put(x, 0, z, 0); put(x, 1, z, 0); }

    private void window(int x, int y, int z) { put(x, y, z, 0); }

    /** A hollow square tower with battlements on top and a door at the front (or none, inside a castle wall). */
    private void addTower(int x0, int z0, int size, int h, boolean door) {
        for (int x = x0; x < x0 + size; x++)
            for (int z = z0; z < z0 + size; z++) {
                boolean edge = x == x0 || x == x0 + size - 1 || z == z0 || z == z0 + size - 1;
                for (int y = 0; y < h; y++) put(x, y, z, edge ? STONE : 0);
                if (edge && ((x - x0) + (z - z0)) % 2 == 0) put(x, h, z, STONE);
            }
        // a floor on top to stand on, with a gap to climb through
        for (int x = x0 + 1; x < x0 + size - 1; x++) for (int z = z0 + 1; z < z0 + size - 1; z++) put(x, h - 1, z, PLANKS);
        put(x0 + size / 2, h - 1, z0 + size / 2, 0);
        if (door) { put(x0 + size / 2, 0, z0, 0); put(x0 + size / 2, 1, z0, 0); }
    }

    /** Clearing first, then bottom to top, each layer from the middle out (so the bot isn't walled in early). */
    private Blueprint sorted() {
        blocks.clear();
        blocks.addAll(at.values());
        final double cx = (width - 1) / 2.0, cz = (depth - 1) / 2.0;
        blocks.sort((a, b) -> {
            if ((a[3] == 0) != (b[3] == 0)) return a[3] == 0 ? -1 : 1;
            if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
            double da = Math.abs(a[0] - cx) + Math.abs(a[2] - cz), db = Math.abs(b[0] - cx) + Math.abs(b[2] - cz);
            return Double.compare(db, da); // the outside ring first: the walls go up around the bot, then it walks out of the door
        });
        return this;
    }

    /**
     * World position of a plan block, the plan's front-left corner at (ox, oy, oz), facing f (0 south +z, 1 west -x,
     * 2 north -z, 3 east +x: Minecraft's own numbering of where a player looks).
     */
    public static int[] place(int[] b, int ox, int oy, int oz, int f) {
        int rx = b[0], fz = b[2];
        switch (f & 3) {
            case 0: return new int[]{ox - rx, oy + b[1], oz + fz};   // looking south: right is west
            case 1: return new int[]{ox - fz, oy + b[1], oz - rx};   // west: right is north
            case 2: return new int[]{ox + rx, oy + b[1], oz - fz};   // north: right is east
            default: return new int[]{ox + fz, oy + b[1], oz + rx};  // east: right is south
        }
    }
}
