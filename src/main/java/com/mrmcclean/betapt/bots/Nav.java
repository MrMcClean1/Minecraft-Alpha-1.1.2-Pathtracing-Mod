package com.mrmcclean.betapt.bots;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import java.util.*;

/**
 * The bots' own path finder: A* over feet positions with costs in game ticks, in place of the mob path finder (which
 * looked 40 blocks ahead, never dug or built and went round in circles). Moves: walk, diagonals past open corners,
 * step up, drop (up to 3, any depth into water), swim, ladders, wooden doors, and when allowed: dig through, dig a
 * step down, pillar up.
 *
 * Ideas taken from open source path finders, written fresh here:
 * - Baritone (LGPL-3.0, ideas only): move costs as real ticks (walking 4.317 blocks/s, falls from gravity, ladders,
 *   swimming); the path it is already on costs half (it doesn't flip between two equal routes); a weighted
 *   heuristic; extra cost near hostile mobs; a best partial path when the goal is out of reach or unloaded.
 * - mineflayer-pathfinder (MIT, PrismarineJS): the search runs in slices over several ticks; finished paths are
 *   shortened to the corners a straight walk can't skip; a path is only replanned when a block on it changes.
 */
final class Nav {
    // ticks per move (Baritone's figures: 20 ticks / blocks per second)
    static final double WALK = 20 / 4.317, DIAG = WALK * Math.sqrt(2), SWIM = 20 / 2.86, LADDER_UP = 20 / 2.35, LADDER_DOWN = 20 / 3.0;
    static final double JUMP = WALK + 2.5, PLACE = 12, DOOR = 3, MOB = 1.5, FAVOR = 0.5;
    /** Ticks to fall n blocks, simulated with the game's gravity (0.08 a tick, 2% drag). */
    static final double[] FALL = new double[257];
    static {
        double v = 0, d = 0; int t = 0, n = 1;
        while (n < FALL.length) { v = (v - 0.08) * 0.98; d -= v; t++; while (n < FALL.length && d >= n) FALL[n++] = t; }
    }

    enum Move { WALK, DIAG, UP, DOWN, SWIM, CLIMB, DIG, DIG_DOWN, PILLAR }

    /** How long breaking a block takes (ticks), negative if it may not be broken. */
    interface Breaker { double ticks(int x, int y, int z, int id); }

    static final class Path {
        final List<int[]> points = new ArrayList<>(); // {x, y, z, move ordinal}
        boolean complete;
        int searched;
        int index;
        int[] next() { return index < points.size() ? points.get(index) : null; }
        boolean done() { return index >= points.size(); }
    }

    private static final class Node {
        final int x, y, z; double g, h, f; Node parent; Move move; boolean closed;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private final World w;
    private final int tx, ty, tz;
    private final double reach2;
    private final Breaker breaker;
    private final int blocks;
    private final int maxNodes;
    private final Set<Long> favored;
    private final List<double[]> mobs;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.f));
    private final Map<Long, Node> seen = new HashMap<>();
    private Node best;
    private int expanded;
    private Path result;

    /**
     * @param breaker null: no digging. @param blocks how many blocks it may place for pillars.
     * @param favored the path it is on now (its nodes cost half), or null. @param mobs hostile mob positions.
     */
    Nav(World w, int sx, int sy, int sz, int tx, int ty, int tz, double reach, Breaker breaker, int blocks, int maxNodes, Set<Long> favored, List<double[]> mobs) {
        this.w = w; this.tx = tx; this.ty = ty; this.tz = tz; this.reach2 = reach * reach;
        this.breaker = breaker; this.blocks = blocks; this.maxNodes = maxNodes; this.favored = favored; this.mobs = mobs;
        Node s = new Node(sx, sy, sz);
        s.h = h(sx, sy, sz); s.f = s.h;
        open.add(s);
        seen.put(key(sx, sy, sz), s);
        best = s;
    }

    static long key(int x, int y, int z) { return ((long) (x + 30000000) << 34) | ((long) (z + 30000000) << 8) | (y & 255); }

    private double h(int x, int y, int z) {
        double dx = x - tx, dy = y - ty, dz = z - tz;
        return Math.sqrt(dx * dx + dz * dz) * WALK + Math.abs(dy) * (dy < 0 ? JUMP : 2);
    }

    boolean done() { return result != null; }
    Path result() { return result; }

    /** Looks at up to n more nodes; true once finished (result() then holds the path, possibly partial, or null). */
    boolean step(int n) {
        if (result != null) return true;
        while (n-- > 0) {
            Node c = open.poll();
            if (c == null) { finish(best, false); return true; }
            if (c.closed) continue;
            c.closed = true;
            double dx = c.x + 0.5 - (tx + 0.5), dy = c.y - ty, dz = c.z + 0.5 - (tz + 0.5);
            if (dx * dx + dz * dz <= reach2 && Math.abs(dy) < 2) { finish(c, true); return true; }
            if (c.h < best.h) best = c;
            if (++expanded >= maxNodes) { finish(best, false); return true; }
            expand(c);
        }
        return false;
    }

    private void finish(Node end, boolean complete) {
        Path p = new Path();
        p.complete = complete; p.searched = expanded;
        LinkedList<Node> chain = new LinkedList<>();
        for (Node x = end; x != null && x.parent != null; x = x.parent) chain.addFirst(x);
        for (Node x : chain) p.points.add(new int[]{x.x, x.y, x.z, x.move.ordinal()});
        result = p.points.isEmpty() && !complete ? null : p;
    }

    private void add(Node from, int x, int y, int z, double cost, Move m) {
        if (cost < 0 || y < 1 || y > 126) return;
        long k = key(x, y, z);
        if (favored != null && favored.contains(k)) cost *= FAVOR;
        if (mobs != null) for (double[] mob : mobs) { double ex = mob[0] - x, ey = mob[1] - y, ez = mob[2] - z; if (ex * ex + ey * ey + ez * ez < 64) { cost *= MOB; break; } }
        if (nearLava(x, y, z)) cost += 40;
        Node n = seen.get(k);
        double g = from.g + cost;
        if (n == null) { n = new Node(x, y, z); n.h = h(x, y, z); seen.put(k, n); }
        else if (n.closed || g >= n.g - 0.01) return; // only a real improvement reopens a node
        n.g = g; n.f = g + n.h; n.parent = from; n.move = m;
        open.add(n);
    }

    // ---- the world, as the bot sees it

    private int id(int x, int y, int z) { return w.getBlock(x, y, z); }
    static boolean water(int id) { return id == 8 || id == 9; }
    static boolean lava(int id) { return id == 10 || id == 11; }
    static boolean door(int id) { return id == 64; } // wooden: it opens them on the way
    static boolean dangerous(int id) { return lava(id) || id == 51 || id == 81; } // lava, fire, cactus

    /** A body can be in it: air, plants, torches, water, an open or wooden door. */
    boolean passable(int x, int y, int z) {
        int id = id(x, y, z);
        if (id == 0 || water(id) || door(id)) return true;
        if (lava(id) || id == 51) return false;
        Block b = Bots.block(id);
        return b == null || !b.material.blocksMovement();
    }

    /** Something to stand on (not a fence: it's too tall to step onto). */
    boolean floor(int x, int y, int z) {
        int id = id(x, y, z);
        if (id == 0 || water(id) || lava(id) || id == 85 || door(id)) return false;
        Block b = Bots.block(id);
        return b != null && b.material.blocksMovement();
    }

    boolean ladder(int x, int y, int z) { return id(x, y, z) == 65; }

    private boolean loaded(int x, int y, int z) { return w.isChunkLoaded(x, y, z); }

    private boolean nearLava(int x, int y, int z) {
        return lava(id(x + 1, y, z)) || lava(id(x - 1, y, z)) || lava(id(x, y, z + 1)) || lava(id(x, y, z - 1)) || lava(id(x, y - 1, z));
    }

    /** Ticks to clear these blocks (0 if clear already), negative if one can't be cleared. */
    private double clear(int[]... ps) {
        double t = 0;
        for (int[] p : ps) {
            if (passable(p[0], p[1], p[2])) continue;
            if (breaker == null) return -1;
            int id = id(p[0], p[1], p[2]);
            if (dangerous(id) || water(id)) return -1;
            int above = id(p[0], p[1] + 1, p[2]);
            if (above == 12 || above == 13 || lava(above) || water(above)) return -1; // something would pour or fall in
            double b = breaker.ticks(p[0], p[1], p[2], id);
            if (b < 0) return -1;
            t += b + 4;
        }
        return t;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}, CORNERS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private void expand(Node c) {
        int x = c.x, y = c.y, z = c.z;
        boolean inWater = water(id(x, y, z)), onLadder = ladder(x, y, z);
        for (int[] s : SIDES) {
            int nx = x + s[0], nz = z + s[1];
            if (!loaded(nx, y, nz)) continue;
            boolean feet = passable(nx, y, nz), head = passable(nx, y + 1, nz);
            if (feet && head) {
                int nid = id(nx, y, nz);
                if (floor(nx, y - 1, nz) || water(nid) || ladder(nx, y, nz)) {
                    if (!dangerous(id(nx, y - 1, nz))) add(c, nx, y, nz, (water(nid) ? SWIM : WALK) + (door(nid) || door(id(nx, y + 1, nz)) ? DOOR : 0), Move.WALK);
                } else {
                    // a drop: the first floor below, up to 3 (any depth into water)
                    int d = 0, fy = y - 1;
                    while (d < 64 && fy > 0 && passable(nx, fy, nz) && !water(id(nx, fy, nz))) { fy--; d++; }
                    boolean wet = water(id(nx, fy, nz));
                    int land = wet ? fy : fy + 1;
                    if (d >= 1 && (d <= 3 || wet) && (wet || floor(nx, land - 1, nz)) && !dangerous(id(nx, land - 1, nz)))
                        add(c, nx, land, nz, WALK * 0.8 + FALL[Math.min(d, 256)], Move.DOWN);
                }
            } else if (breaker != null && floor(nx, y - 1, nz)) {
                double t = clear(new int[]{nx, y, nz}, new int[]{nx, y + 1, nz});
                if (t >= 0) add(c, nx, y, nz, WALK + t, Move.DIG);
            }
            // up a step onto (nx, y, nz)
            if (floor(nx, y, nz) && !dangerous(id(nx, y, nz))) {
                if (passable(x, y + 2, z) && passable(nx, y + 1, nz) && passable(nx, y + 2, nz)) add(c, nx, y + 1, nz, JUMP, Move.UP);
                else if (breaker != null) {
                    double t = clear(new int[]{x, y + 2, z}, new int[]{nx, y + 1, nz}, new int[]{nx, y + 2, nz});
                    if (t >= 0) add(c, nx, y + 1, nz, JUMP + t, Move.UP);
                }
            }
            // a step down by digging (a staircase): the block ahead and the one under it
            if (breaker != null && !feet && floor(nx, y - 2, nz) && !dangerous(id(nx, y - 2, nz))) {
                double t = clear(new int[]{nx, y + 1, nz}, new int[]{nx, y, nz}, new int[]{nx, y - 1, nz});
                if (t >= 0) add(c, nx, y - 1, nz, WALK + t, Move.DIG);
            }
        }
        // diagonals, only with both sides open (a 0.6 wide body can't squeeze past a corner)
        for (int[] d : CORNERS) {
            int ax = d[0], bz = d[1], nx = x + ax, nz = z + bz;
            if (!loaded(nx, y, nz)) continue;
            if (passable(nx, y, nz) && passable(nx, y + 1, nz) && floor(nx, y - 1, nz) && !water(id(nx, y, nz)) && !dangerous(id(nx, y - 1, nz))
                    && passable(x + ax, y, z) && passable(x + ax, y + 1, z) && passable(x, y, z + bz) && passable(x, y + 1, z + bz)
                    && !door(id(x + ax, y, z)) && !door(id(x, y, z + bz)))
                add(c, nx, y, nz, DIAG, Move.DIAG);
        }
        // up and down in water and on ladders
        if (inWater || onLadder) {
            if (passable(x, y + 1, z) && passable(x, y + 2, z)) add(c, x, y + 1, z, inWater ? SWIM : LADDER_UP, inWater ? Move.SWIM : Move.CLIMB);
            if (passable(x, y - 1, z) && (water(id(x, y - 1, z)) || ladder(x, y - 1, z))) add(c, x, y - 1, z, inWater ? SWIM : LADDER_DOWN, inWater ? Move.SWIM : Move.CLIMB);
        }
        if (breaker == null) return;
        // dig straight down (never into lava, water or a long drop)
        if (!inWater && !passable(x, y - 1, z) && floor(x, y - 2, z) && !dangerous(id(x, y - 2, z)) && !water(id(x, y - 2, z)) && !nearLava(x, y - 1, z)) {
            double t = clear(new int[]{x, y - 1, z});
            if (t >= 0) add(c, x, y - 1, z, t + 2, Move.DIG_DOWN);
        }
        // pillar up: jump and put a block where the feet were
        if (blocks > 0 && !inWater && !onLadder && floor(x, y - 1, z)) {
            double t = clear(new int[]{x, y + 2, z});
            if (t >= 0) add(c, x, y + 1, z, JUMP + PLACE + t, Move.PILLAR);
        }
    }

    /**
     * The corners of a path a straight walk can't skip (mineflayer-pathfinder's shortcut): runs of plain walking on one
     * level are cut to their ends when the line between them is clear and floored all the way.
     */
    Path shorten(Path p) {
        if (p == null || p.points.size() < 3) return p;
        Path out = new Path();
        out.complete = p.complete; out.searched = p.searched;
        int i = 0;
        while (i < p.points.size()) {
            int[] a = p.points.get(i);
            int j = i;
            if (plain(a)) while (j + 1 < p.points.size() && plain(p.points.get(j + 1)) && p.points.get(j + 1)[1] == a[1] && straight(a, p.points.get(j + 1))) j++;
            out.points.add(p.points.get(j));
            i = j + 1;
        }
        return out;
    }

    private static boolean plain(int[] p) { return p[3] == Move.WALK.ordinal() || p[3] == Move.DIAG.ordinal(); }

    /** Every block under a straight line from a to b (same level) is floor with room above, and no water, doors or danger. */
    private boolean straight(int[] a, int[] b) {
        double dx = b[0] - a[0], dz = b[2] - a[2], len = Math.sqrt(dx * dx + dz * dz);
        int steps = (int) Math.ceil(len * 4);
        for (int s = 0; s <= steps; s++) {
            double fx = a[0] + 0.5 + dx * s / Math.max(1, steps), fz = a[2] + 0.5 + dz * s / Math.max(1, steps);
            for (double ox = -0.3; ox <= 0.3; ox += 0.6) for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
                int x = (int) Math.floor(fx + ox), z = (int) Math.floor(fz + oz);
                int idf = id(x, a[1], z);
                if (!passable(x, a[1], z) || !passable(x, a[1] + 1, z) || water(idf) || door(idf) || !floor(x, a[1] - 1, z) || dangerous(id(x, a[1] - 1, z))) return false;
            }
        }
        return true;
    }

    /** Still walkable as planned? (Only a change on the path makes it plan again: mineflayer-pathfinder's rule.) */
    boolean stillGood(Path p) {
        for (int i = p.index; i < Math.min(p.points.size(), p.index + 12); i++) {
            int[] q = p.points.get(i);
            Move m = Move.values()[q[3]];
            if (m == Move.DIG || m == Move.DIG_DOWN || m == Move.PILLAR) continue; // it changes those itself
            if (!passable(q[0], q[1], q[2]) || !passable(q[0], q[1] + 1, q[2])) return false;
            if ((m == Move.WALK || m == Move.DIAG || m == Move.UP) && !floor(q[0], q[1] - 1, q[2]) && !water(id(q[0], q[1], q[2])) && !ladder(q[0], q[1], q[2])) return false;
        }
        return true;
    }
}
