package com.mrmcclean.betapt.bots;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

import java.util.*;

/**
 * Bot towns (optional: towns=true in classicbots.properties, or /bot town on). "/bot town start" founds one where you
 * stand with your bots, each given a job in turn: builder, farmer, miner, trader, guard. The town is the same in
 * every Classic copy and in Tekkit: the market chest on the centre (it is the members' home, so "put things away"
 * fills it), a 9 x 9 field 9 blocks south, and a street running north with plots either side, doors onto the street:
 * first a hut, then a house every townDaysPerHouse days. The builder builds them (furnished as usual) and lays a
 * cobblestone path to each door; the farmer farms; the miner brings cobblestone to the market; the trader carries
 * other members' surplus there and hands out food; the guard walks the street at night while the others wait indoors.
 * Each member keeps the town in its own save (centre, founding day, job), so nothing else needs saving: what is built
 * is read from the world.
 */
public final class Town {
    private Town() {}

    public enum Job { BUILDER, FARMER, MINER, TRADER, GUARD }

    /**
     * A town: its centre, the day it was founded, and its plots once planned ({origin x, y, z, facing, door x, door z,
     * slot}). One object per town, shared by its members (ALL), so the plots are planned once, on the ground as it was.
     */
    static final class Data {
        int x, y, z; long day0;
        final List<int[]> plots = new ArrayList<>();
    }
    static final Map<String, Data> ALL = new HashMap<>();
    static Data data(int x, int y, int z, long day0) {
        return ALL.computeIfAbsent(x + "," + y + "," + z, k -> { Data d = new Data(); d.x = x; d.y = y; d.z = z; d.day0 = day0; return d; });
    }

    /** A member: its town and its job there. */
    static final class Member {
        Data d; Job job; int walk;
        int x, y, z; // the centre (the same as d's)
        Member(Data d, Job job) { this.d = d; this.job = job; x = d.x; y = d.y; z = d.z; }
    }

    static boolean night(World w) { long t = w.ticks % 24000; return t > 13000 && t < 23000; }
    static long day(World w) { return w.ticks / 24000; }

    /** Founds a town at the player's feet with these bots. */
    static String found(PlayerEntity p, List<BotEntity> bots) {
        return found(Names.world(p), (int) Math.floor(p.x), (int) Math.floor(p.y - p.eyeHeight + 0.01), (int) Math.floor(p.z), bots);
    }

    /** Founds a town at x y z with these bots (their own, when three or more are on their own: Bots.autoTown). */
    static String found(World w, int x, int y, int z, List<BotEntity> bots) {
        Job[] order = Job.values();
        int i = 0;
        StringBuilder s = new StringBuilder("Town founded at " + x + " " + y + " " + z + ":");
        ALL.remove(x + "," + y + "," + z);
        Data d = data(x, y, z, day(w));
        for (BotEntity b : bots) {
            Member m = new Member(d, order[i++ % order.length]);
            b.brain.town = m;
            b.hasHome = true; b.homeX = x; b.homeY = y; b.homeZ = z;
            if (b.brain.mode != Brain.Mode.AUTO) b.brain.mode = Brain.Mode.HOME;
            b.brain.townLeft = false;
            b.brain.job = Brain.Job.NONE; b.brain.after = Brain.Job.NONE; b.brain.plan = null; // nothing left over from its own work
            s.append(' ').append(b.botName()).append(" the ").append(m.job.name().toLowerCase(Locale.ROOT));
        }
        return s.toString();
    }

    static void leave(BotEntity b) {
        Data d = b.brain.town == null ? null : b.brain.town.d;
        b.brain.town = null; b.brain.hasFarm = false;
        if (d == null) return;
        for (BotEntity o : Bots.bots(Names.world(b))) if (o.brain.town != null && o.brain.town.d == d) return;
        ALL.values().remove(d); // the last member gone: its blocks are no longer kept from bots
    }

    // ------------------------------------------------------------------ layout (the same as Tekkit's town.Town)

    /** How many plots the town has by today (one at the start, one more every townDaysPerHouse days), planned as needed. */
    static int plots(World w, Member m) {
        Data d = m.d;
        int want = (int) Math.min(12, 1 + (day(w) - d.day0) / Math.max(1, BotConfig.townDaysPerHouse));
        int slot = d.plots.isEmpty() ? 0 : d.plots.get(d.plots.size() - 1)[6] + 1;
        while (d.plots.size() < want && slot < 24) {
            // the next slot up the street with flat, dry ground under the whole building (else the one after)
            int[] p = slotAt(w, d, slot++, d.plots.isEmpty());
            if (flat(w, p, d.plots.isEmpty())) d.plots.add(p);
        }
        return d.plots.size();
    }

    /** Slot i: {origin x, y, z, facing, door x, door z, slot}; even slots east of the street, odd west, rows 10 apart. */
    static int[] slotAt(World w, Data d, int i, boolean hut) {
        int half = hut ? 2 : 3;
        int rowZ = d.z - 6 - (i / 2) * 10;
        boolean east = i % 2 == 0;
        int doorX = east ? d.x + 3 : d.x - 3;
        int y = w.getHeight(doorX, rowZ);
        return east ? new int[]{doorX, y, rowZ - half, 3, doorX, rowZ, i} : new int[]{doorX, y, rowZ + half, 1, doorX, rowZ, i};
    }

    /** Flat enough: the ground under every block of the plan within one block of the door's, and no water or lava. */
    static boolean flat(World w, int[] o, boolean hut) {
        for (int[] pb : Blueprint.of(hut ? "hut" : "house", 0).blocks) {
            if (pb[1] != 0) continue;
            int[] q = Blueprint.place(pb, o[0], o[1], o[2], o[3]);
            int top = w.getHeight(q[0], q[2]), below = w.getBlock(q[0], top - 1, q[2]);
            if (Math.abs(top - o[1]) > 1 || below >= 8 && below <= 11) return false;
        }
        return true;
    }

    /** A town's own blocks (its buildings, street, chest and field) inside its area: no bot breaks them. */
    static boolean protects(int x, int y, int z, int id) {
        if (!BotConfig.towns || ALL.isEmpty()) return false;
        if (id != 4 && id != 5 && id != 17 && id != 54 && id != 60 && id != 64 && id != 50) return false; // the ground stays diggable (bots dig steps to get about)
        // the strip from the field (south) to the last row of plots planned so far (north), 12 either side of the street
        for (Data d : ALL.values()) { int dx = x - d.x, dz = z - d.z, rows = Math.max(1, (d.plots.size() + 1) / 2); if (Math.abs(dx) <= 12 && dz <= 14 && dz >= -(6 + rows * 10 + 4)) return true; }
        return false;
    }

    static int[] plot(World w, Member m, int i) { plots(w, m); return m.d.plots.get(i); }

    static Blueprint planOf(int i) { return Blueprint.of(i == 0 ? "hut" : "house", 0); }


    /** Built: 90% of the plan's blocks are there. */
    static boolean built(World w, Member m, int i) {
        int[] o = plot(w, m, i);
        Blueprint p = planOf(i);
        int ok = 0, total = 0;
        for (int[] pb : p.blocks) { if (pb[3] == 0) continue; total++; int[] q = Blueprint.place(pb, o[0], o[1], o[2], o[3]); if (w.getBlock(q[0], q[1], q[2]) == pb[3]) ok++; }
        return ok * 10 >= total * 9;
    }

    /** The path cells to plot i's door: up the street from the centre, then across. Each is {x, z}. */
    static List<int[]> path(World w, Member m, int i) {
        int[] o = plot(w, m, i);
        List<int[]> l = new ArrayList<>();
        for (int z = m.z - 1; z >= o[5]; z--) l.add(new int[]{m.x, z});
        int dx = Integer.signum(o[4] - m.x);
        for (int x = m.x + dx; x != o[4]; x += dx) l.add(new int[]{x, o[5]});
        return l;
    }

    static boolean pathDone(World w, Member m, int i) {
        for (int[] c : path(w, m, i)) { int top = w.getHeight(c[0], c[1]) - 1; int id = w.getBlock(c[0], top, c[1]); if (id == 2 || id == 3) return false; }
        return true;
    }

    // ------------------------------------------------------------------ each tick with no job of its own

    /** Town work for a bot with no job in hand. True if it did something this tick. */
    static boolean tick(Brain br) {
        Member m = br.town;
        if (m == null || !BotConfig.towns) return false;
        BotEntity b = br.b;
        World w = Names.world(b);
        b.hasHome = true; b.homeX = m.x; b.homeY = m.y; b.homeZ = m.z;
        if (night(w) && m.job != Job.GUARD) { indoors(br, m); return true; }
        switch (m.job) {
            case BUILDER: return builder(br, m);
            case FARMER:
                if (!br.hasFarm) { br.hasFarm = true; br.farmX = m.x; br.farmY = m.y; br.farmZ = m.z + 9; br.farmTimer = 1; }
                if (b.count(296) >= 16 && market(br, m)) { br.startJob(Brain.Job.DEPOSIT); return true; }
                return false; // the brain's own farm timer runs the field
            case MINER:
                if (b.count(4) >= 64 && market(br, m)) { br.startJob(Brain.Job.DEPOSIT); return true; }
                return quarry(br, m, 32);
            case TRADER: return trader(br, m);
            case GUARD: return guard(br, m);
        }
        return false;
    }

    /** Stone for the town: at the quarry 32 blocks east of the centre, never under the town itself. */
    private static boolean quarry(Brain br, Member m, int n) {
        BotEntity b = br.b; World w = Names.world(b);
        if (flat(b, m.x + 32.5, m.z + 0.5) > 6) { b.walkTo(m.x + 32.5, w.getHeight(m.x + 32, m.z), m.z + 0.5, 4); return true; }
        br.want = Want.of("cobblestone"); br.wantCount = b.count(4) + n; br.deliverToHome = market(br, m);
        if (!br.deliverToHome) br.after = Brain.Job.GO_HOME; // no market yet: back to the town with it (not off to the player)
        br.startJob(Brain.Job.GATHER);
        return true;
    }

    /** The market chest is down (else "put it away" would only say there's no chest at home, over and over). */
    static boolean market(Brain br, Member m) { return br.findNear(new int[]{54}, m.x, m.y, m.z, 2, false) != null; }

    static int trades;
    private static final Random RND = new Random();

    private static boolean builder(Brain br, Member m) {
        BotEntity b = br.b; World w = Names.world(b);
        if (br.findNear(new int[]{54}, m.x, m.y, m.z, 2, false) == null) {
            if (b.count(54) == 0) {
                if (b.count(5) < 8 && b.count(17) >= 2) br.craft(5, 4, new int[]{17, 1});
                if (b.count(5) >= 8) br.craft(54, 1, new int[]{5, 8});
                if (b.count(54) == 0) { br.want = Want.of("wood"); br.wantCount = b.count(17) + 3; br.deliverToHome = false; br.after = Brain.Job.GO_HOME; br.startJob(Brain.Job.GATHER); return true; } // the wood comes back to the town, not to the player
            }
            if (flat(b, m.x + 0.5, m.z + 0.5) > 4) { b.walkTo(m.x + 0.5, m.y, m.z + 2.5, 1.5); return true; }
            int cy = w.getHeight(m.x, m.z);
            w.setBlock(m.x, cy, m.z, 54);
            if (w.getBlock(m.x, cy, m.z) == 54) { b.take(54, 1); br.say("The market chest is down."); }
            return true;
        }
        int n = plots(w, m);
        for (int i = 0; i < n; i++) {
            if (built(w, m, i)) continue;
            // building blocks from the market first
            if (b.count(4) + b.count(5) < 32 && takeFromMarket(br, m, new int[]{4, 5, 17}, 128) > 0) return true;
            int[] o = plot(w, m, i);
            br.plan = planOf(i); br.planX = o[0]; br.planY = o[1]; br.planZ = o[2]; br.planF = o[3]; br.planIndex = 0; br.planShort = 0;
            br.startJob(Brain.Job.BUILD);
            br.say("Building the town's " + br.plan.name + ".");
            return true;
        }
        for (int i = 0; i < n; i++) {
            if (pathDone(w, m, i)) continue;
            if (b.count(4) == 0 && takeFromMarket(br, m, new int[]{4}, 64) == 0) {
                return quarry(br, m, 24);
            }
            for (int[] c : path(w, m, i)) {
                int top = w.getHeight(c[0], c[1]) - 1, id = w.getBlock(c[0], top, c[1]);
                if (id != 2 && id != 3) continue;
                if (flat(b, c[0] + 0.5, c[1] + 0.5) > 3.5) { if (++m.walk > 400) { w.setBlock(c[0], top, c[1], 4); m.walk = 0; } else b.walkTo(c[0] + 0.5, top + 1, c[1] + 0.5, 2.5); return true; }
                m.walk = 0;
                b.stopMoving();
                if (b.ticks % 4 != 0) return true;
                b.swing();
                w.setBlock(c[0], top, c[1], 4);
                b.take(4, 1);
                return true;
            }
        }
        // nothing to build: put spare stone in the market, then wait for the next plot
        if (b.count(4) >= 64 && market(br, m)) { br.startJob(Brain.Job.DEPOSIT); return true; }
        idle(br, m);
        return true;
    }

    private static boolean trader(Brain br, Member m) {
        BotEntity b = br.b; World w = Names.world(b);
        for (BotEntity o : Bots.bots(w)) {
            if (!market(br, m)) break; // nowhere to take it yet (the builder puts the chest down first)
            if (o == b || o.brain.town == null || o.brain.town.x != m.x || o.brain.town.z != m.z) continue;
            int id = o.brain.town.job == Job.MINER ? 4 : o.brain.town.job == Job.FARMER ? 296 : 0;
            if (id == 0 || o.count(id) < 32 && !(id == 296 && o.count(id) >= 16)) continue;
            if (b.distanceTo(o) > 3) { b.walkTo(o.x, o.y, o.z, 2.5); return true; }
            int n = o.count(id);
            o.take(id, n);
            b.add(new ItemStack(id, n, 0));
            trades++;
            br.say(o.botName() + ", I'll take your " + n + " " + Bots.name(id) + " to the market.");
            br.startJob(Brain.Job.DEPOSIT);
            return true;
        }
        for (BotEntity o : Bots.bots(w)) {
            if (o == b || o.brain.town == null || o.brain.town.x != m.x || hasFood(o)) continue;
            if (b.count(297) == 0 && b.count(296) >= 3) br.craft(297, 1, new int[]{296, 3});
            if (b.count(297) == 0) break;
            if (b.distanceTo(o) > 3) { b.walkTo(o.x, o.y, o.z, 2.5); return true; }
            b.take(297, 1); o.add(new ItemStack(297, 1, 0)); trades++;
            br.say("Here, " + o.botName() + ", some bread.");
            return true;
        }
        PlayerEntity p = Bots.player(w, b.owner);
        if (p != null && b.distanceTo(p) < 12 && b.ticks % 2400 == 0) {
            int[] c = br.findNear(new int[]{54}, m.x, m.y, m.z, 2, false);
            br.say("The market has " + marketCount(w, c, 4) + " cobblestone and " + marketCount(w, c, 296) + " wheat to spare. Ask and I'll bring you some.");
        }
        idle(br, m);
        return true;
    }

    private static boolean guard(Brain br, Member m) {
        BotEntity b = br.b; World w = Names.world(b);
        if (!night(w)) { idle(br, m); return true; }
        if (m.walk <= 0 || flat(b, br.tx + 0.5, br.tz + 0.5) < 2) {
            int rows = Math.max(1, (plots(w, m) + 1) / 2);
            br.tx = m.x + RND.nextInt(7) - 3; br.tz = m.z + 8 - RND.nextInt(rows * 10 + 12); m.walk = 400;
        }
        m.walk--;
        b.walkTo(br.tx + 0.5, w.getHeight(br.tx, br.tz), br.tz + 0.5, 1.5);
        return true;
    }

    /** Night: into the nearest finished building and stay. */
    private static void indoors(Brain br, Member m) {
        BotEntity b = br.b; World w = Names.world(b);
        int best = -1; double bd = 1e9;
        for (int i = 0; i < plots(w, m); i++) {
            if (!built(w, m, i)) continue;
            int[] o = plot(w, m, i);
            double d = flat(b, o[4] + 0.5, o[5] + 0.5);
            if (d < bd) { bd = d; best = i; }
        }
        if (best < 0) { idle(br, m); return; }
        int half = best == 0 ? 2 : 3;
        int[] mid = Blueprint.place(new int[]{half, 0, half, 0}, plot(w, m, best)[0], plot(w, m, best)[1], plot(w, m, best)[2], plot(w, m, best)[3]);
        if (flat(b, mid[0] + 0.5, mid[2] + 0.5) > 1.5) b.walkTo(mid[0] + 0.5, mid[1], mid[2] + 0.5, 1);
        else b.stopMoving();
    }

    static boolean indoorsNow(BotEntity b) {
        Member m = b.brain.town;
        if (m == null) return false;
        for (int i = 0; i < plots(Names.world(b), m); i++) {
            int[] o = plot(Names.world(b), m, i);
            int half = i == 0 ? 2 : 3;
            int[] mid = Blueprint.place(new int[]{half, 0, half, 0}, o[0], o[1], o[2], o[3]);
            if (flat(b, mid[0] + 0.5, mid[2] + 0.5) < 2.5) return true;
        }
        return false;
    }

    private static void idle(Brain br, Member m) {
        BotEntity b = br.b;
        if (flat(b, m.x + 0.5, m.z + 0.5) > 6) b.walkTo(m.x + 0.5, m.y, m.z - 1.5, 4);
        else b.stopMoving();
    }

    /** Sideways distance (a bot's y is at its eyes, so a 3D distance to a block never gets small). */
    static double flat(BotEntity b, double x, double z) { return Math.sqrt((b.x - x) * (b.x - x) + (b.z - z) * (b.z - z)); }

    private static boolean hasFood(BotEntity o) { for (ItemStack s : o.inv) if (BotEntity.isFood(s)) return true; return false; }

    static int marketCount(World w, int[] c, int id) {
        if (c == null) return 0;
        BlockEntity be = w.getBlockEntity(c[0], c[1], c[2]);
        if (!(be instanceof Inventory)) return 0;
        int n = 0;
        for (int k = 0; k < ((Inventory) be).getSize(); k++) { ItemStack t = ((Inventory) be).getItem(k); if (t != null && t.id == id) n += t.size; }
        return n;
    }

    /** Takes up to n of these from the market chest when the bot stands by it (walks there first). How many it took. */
    private static int takeFromMarket(Brain br, Member m, int[] ids, int n) {
        BotEntity b = br.b; World w = Names.world(b);
        int[] c = br.findNear(new int[]{54}, m.x, m.y, m.z, 2, false);
        if (c == null) return 0;
        int there = 0;
        for (int id : ids) there += marketCount(w, c, id);
        if (there == 0) return 0;
        if (flat(b, c[0] + 0.5, c[2] + 0.5) > 3) { b.walkTo(c[0] + 0.5, c[1], c[2] + 0.5, 2.5); return 1; }
        Inventory inv = (Inventory) w.getBlockEntity(c[0], c[1], c[2]);
        int got = 0;
        for (int k = 0; k < inv.getSize() && got < n; k++) {
            ItemStack t = inv.getItem(k);
            if (t == null) continue;
            boolean want = false;
            for (int id : ids) if (t.id == id) want = true;
            if (!want) continue;
            int take = Math.min(n - got, t.size);
            b.add(new ItemStack(t.id, take, t.metadata));
            t.size -= take; got += take;
            if (t.size <= 0) inv.setItem(k, null);
        }
        inv.markDirty();
        return Math.max(got, 1);
    }

    static String status(World w, List<BotEntity> bots) {
        Member m = null;
        for (BotEntity b : bots) if (b.brain.town != null) { m = b.brain.town; break; }
        if (m == null) return "No town. /bot town start founds one where you stand (with towns on).";
        int built = 0, paths = 0, n = plots(w, m);
        for (int i = 0; i < n; i++) { if (built(w, m, i)) built++; if (pathDone(w, m, i)) paths++; }
        StringBuilder s = new StringBuilder("Town at " + m.x + " " + m.y + " " + m.z + ", day " + (day(w) - m.d.day0) + ": " + built + " of " + n + " buildings up, " + paths + " paths, " + trades + " trades. Jobs:");
        for (BotEntity b : bots) if (b.brain.town != null) s.append(' ').append(b.botName()).append('=').append(b.brain.town.job.name().toLowerCase(Locale.ROOT));
        return s.toString();
    }

    // ------------------------------------------------------------------ saving (in the bot's own save)

    static void write(Brain br, NbtCompound n) {
        Member m = br.town;
        n.putBoolean("InTown", m != null);
        if (m == null) return;
        n.putInt("TownX", m.x); n.putInt("TownY", m.y); n.putInt("TownZ", m.z); n.putLong("TownDay", m.d.day0); n.putString("TownJob", m.job.name());
        // the plots as planned, 7 numbers each
        StringBuilder sb = new StringBuilder();
        for (int[] p : m.d.plots) for (int v : p) sb.append(v).append(',');
        n.putString("TownPlots", sb.toString());
    }

    static void read(Brain br, NbtCompound n) {
        if (!n.getBoolean("InTown")) { br.town = null; return; }
        Data d = data(n.getInt("TownX"), n.getInt("TownY"), n.getInt("TownZ"), n.getLong("TownDay"));
        if (d.plots.isEmpty()) {
            String[] v = n.getString("TownPlots").split(",");
            for (int i = 0; i + 7 <= v.length; i += 7) { int[] p = new int[7]; for (int k = 0; k < 7; k++) p[k] = Integer.parseInt(v[i + k].trim()); d.plots.add(p); }
        }
        Job j;
        try { j = Job.valueOf(n.getString("TownJob")); } catch (Exception e) { j = Job.BUILDER; }
        br.town = new Member(d, j);
    }
}
