package com.mrmcclean.betapt.bots;

import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

/**
 * The progression guide (on by default: progressionGuide=true, /bot guide on|off): the steps of this version from
 * punching a tree on, as a list, one shared by Classic 0.30, Indev, Alpha, Beta and the modpack. A step whose items
 * this version doesn't have is left out (no crafting in Classic 0.30, no beds before Beta, no Nether before Alpha
 * 1.2, the modpack's mods only where their recipes are). A step is done once the player has carried what it asks
 * for; that is remembered per player (config/classicbots-guide.properties). Ask a bot "what next?" (or /bot next),
 * say "help me with that" and it fetches what the step needs; when you finish a step a bot of yours says so and what
 * comes next.
 */
public final class Guide {
    private Guide() {}

    /** A step: what to say, the items that count (any of them), how many, and what a bot fetches for it. */
    static final class Step {
        final String text, fetch; final int[] ids; final int amount; final int dim;
        Step(String text, String fetch, int amount, int dim, int... ids) { this.text = text; this.fetch = fetch; this.amount = amount; this.dim = dim; this.ids = ids; }
    }

    static Step s(String text, String fetch, int count, int... ids) { return new Step(text, fetch, count, 0, ids); }

    /** Items from mods, by the name the game gives them (0 where there's no such recipe). */
    static int named(String n) { return 0; } // no mod recipes to look up in Alpha, Indev or Classic

    static List<Step> steps;
    // (here bots gather but don't make named things: a crafted step's "help" fetches what it's made of)

    /** This version's steps, worked out once. */
    static List<Step> steps() {
        if (steps != null) return steps;
        List<Step> all = new ArrayList<>(Arrays.asList(
                s("get some wood: punch a tree for 4 logs", "wood", 4, 17),
                s("make a crafting table from planks", "wood", 1, 58),
                s("make a wooden pickaxe", "wood", 1, 270),
                s("gather cobblestone", "cobblestone", 16, 4),
                s("make a stone pickaxe", "cobblestone", 1, 274),
                s("make a furnace from 8 cobblestone", "cobblestone", 1, 61),
                s("find coal and make torches", "coal", 4, 50),
                s("gather sand for glass, and gravel", "sand", 8, 12, 13),
                s("find coal, iron and gold ore", "ores", 1, 14, 15, 16, 263),
                s("make a bed from wool and planks, and sleep through the night", "wool", 1, 355),
                s("start a farm: a hoe, seeds, then bake bread", "seeds", 1, 297),
                s("mine iron ore and smelt it into ingots", "iron", 3, 265),
                s("make an iron pickaxe", "iron", 1, 257),
                s("make iron armour", "iron", 1, 306, 307, 308, 309),
                s("find diamonds (deep down, below y 16)", "diamond", 1, 264),
                s("make a diamond pickaxe", "diamond", 1, 278),
                s("mine obsidian (pour water on lava, then a diamond pickaxe)", "obsidian", 10, 49),
                s("make flint and steel to light a Nether portal", "iron", 1, 259),
                new Step("build a Nether portal (obsidian 4 x 5) and go through", null, 1, -1, 90)));
        // the Beta modpack's mods, in the order they build on each other (left out where the mod isn't there)
        String[][] mods = {
                {"IndustrialCraft: make a generator (it burns coal into power)", "generator"},
                {"IndustrialCraft: make a macerator (two dusts from every ore)", "macerator"},
                {"IndustrialCraft: an electric furnace", "electric furnace"},
                {"IndustrialCraft: a BatBox to store power", "batbox"},
                {"BuildCraft: a redstone engine", "redstone engine"},
                {"BuildCraft: a mining well, then a quarry", "quarry"},
                {"RedPower: red alloy wire", "red alloy wire"},
                {"Iron chests: an iron chest", "iron chest"},
                {"Equivalent Exchange: a philosopher's stone", "philosopher's stone"},
                {"Equivalent Exchange: an energy collector", "energy collector"},
                {"Equivalent Exchange: an energy condenser", "energy condenser"},
                {"IndustrialCraft: a mining drill", "mining drill"},
                {"IndustrialCraft: a mass fabricator for UU-matter", "mass fabricator"}};
        for (String[] m : mods) { int id = named(m[1]); if (id > 0) all.add(s(m[0], m[1], 1, id)); }
        List<Step> here = new ArrayList<>();
        for (Step st : all) { boolean any = false; for (int id : st.ids) if (exists(id)) any = true; if (any) here.add(st); }
        return steps = here;
    }

    static boolean exists(int id) {
        if (id <= 0) return false;
        if (id < 256) return Bots.block(id) != null;
        return id < Item.BY_ID.length && Item.BY_ID[id] != null;
    }

    // ------------------------------------------------------------------ the player's progress

    static final Map<String, Set<Integer>> done = new HashMap<>();
    private static File file;

    static void load(File configDir) {
        file = new File(configDir, "classicbots-guide.properties");
        Properties p = new Properties();
        if (file.isFile()) try (FileInputStream in = new FileInputStream(file)) { p.load(in); } catch (Exception ignored) {}
        for (String k : p.stringPropertyNames()) {
            Set<Integer> d = new HashSet<>();
            for (String v : p.getProperty(k).split(",")) try { d.add(Integer.parseInt(v.trim())); } catch (Exception ignored) {}
            done.put(k, d);
        }
    }

    static void save() {
        if (file == null) return;
        Properties p = new Properties();
        for (Map.Entry<String, Set<Integer>> e : done.entrySet()) { StringBuilder s = new StringBuilder(); for (int i : e.getValue()) s.append(s.length() == 0 ? "" : ",").append(i); p.setProperty(e.getKey(), s.toString()); }
        try (FileOutputStream out = new FileOutputStream(file)) { p.store(out, "Classic Bots: the progression guide's steps each player has done"); } catch (Exception ignored) {}
    }

    static int has(PlayerEntity p, int id) {
        int n = 0;
        for (ItemStack s : p.inventory.items) if (s != null && s.id == id) n += s.size;
        return n;
    }

    /** Marks what the player has reached now; the steps newly done (by index). */
    static List<Integer> update(PlayerEntity p) {
        List<Step> st = steps();
        Set<Integer> d = done.computeIfAbsent(p.name, k -> new HashSet<>());
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < st.size(); i++) {
            if (d.contains(i)) continue;
            Step s = st.get(i);
            boolean ok;
            if (s.dim != 0) ok = false; // no other dimensions here (the portal step is left out anyway)
            else { int n = 0; for (int id : s.ids) n += has(p, id); ok = n >= s.amount; }
            if (ok) { d.add(i); fresh.add(i); }
        }
        if (!fresh.isEmpty()) save();
        return fresh;
    }

    /** The player's next step (index), or -1 when every step is done. */
    static int next(PlayerEntity p) {
        update(p);
        Set<Integer> d = done.getOrDefault(p.name, Collections.emptySet());
        for (int i = 0; i < steps().size(); i++) if (!d.contains(i)) return i;
        return -1;
    }

    /** "Next: ..." for the player, in a line. */
    static String nextLine(PlayerEntity p) {
        if (!BotConfig.progressionGuide) return "The progression guide is off (/bot guide on).";
        int i = next(p);
        if (i < 0) return "You've done every step I know for this version. Well played!";
        return "Next: " + steps().get(i).text + " (step " + (i + 1) + " of " + steps().size() + ")." + (steps().get(i).fetch != null ? " Say \"help me with that\" and I'll get it." : "");
    }

    /** What a bot fetches for the player's next step (null: nothing it can fetch). */
    static Want fetchFor(PlayerEntity p) {
        int i = next(p);
        if (i < 0 || steps().get(i).fetch == null) return null;
        return Want.of(steps().get(i).fetch);
    }

    static int need(PlayerEntity p) {
        int i = next(p);
        if (i < 0) return 1;
        Step st = steps().get(i);
        // a crafted step fetches what it's made of: enough for the recipe
        int mat = st.ids[0] == 61 ? 8 : st.ids[0] == 306 ? 8 : st.ids[0] == 58 || st.ids[0] == 270 ? 3 : st.ids[0] == 257 || st.ids[0] == 278 || st.ids[0] == 355 || st.ids[0] == 297 ? 3 : st.ids[0] == 274 ? 3 : st.ids[0] == 50 ? 2 : 1;
        return Math.max(st.amount, mat);
    }

    // ------------------------------------------------------------------ a bot's own progress (when you've given it nothing to do)

    /**
     * A bot with no objective from you (keeping house, or you're not around) works through the guide itself: the
     * first step it hasn't done, fetched or made and kept (not handed over). Steps it has done are kept in its save.
     * True if it started on one.
     */
    static boolean work(Brain br) {
        if (!BotConfig.progressionGuide || br.job != Brain.Job.NONE) return false;
        BotEntity b = br.b;
        if (br.guideRest > 0) { br.guideRest--; return false; }
        PlayerEntity o = Bots.player(Names.world(b), b.owner);
        boolean free = br.mode == Brain.Mode.HOME || o == null || b.distanceTo(o) > 48;
        if (!free) return false;
        List<Step> st = steps();
        for (int i = 0; i < st.size(); i++) {
            Step s = st.get(i);
            if (br.guideDone.contains(i) || s.fetch == null || s.dim != 0) continue;
            int n = 0; for (int id : s.ids) n += b.count(id);
            if (n >= s.amount) { br.guideDone.add(i); br.guideTries = 0; continue; }
            Want w = Want.of(s.fetch);
            if (w == null) { br.guideDone.add(i); continue; } // nothing it can fetch here: on to the next
            if (br.guideTries > 2) { br.guideDone.add(i); br.guideTries = 0; continue; } // tried and failed: leave it
            br.guideTries++;
            br.want = w; br.wantCount = s.amount; br.deliverToHome = false; br.after = Brain.Job.GO_HOME; // kept, not handed over
            br.startJob(w.food() ? Brain.Job.HUNT : Brain.Job.GATHER);
            br.guideRest = 200;
            if (br.b.ticks % 3 == 0) br.say("Nothing to do, so I'm working on the guide: " + s.text + ".");
            return true;
        }
        return routine(br);
    }

    /**
     * Infinite progression: every step done, still nothing from you, so routines in turn: mine for ores, chop wood
     * (both kept, or put in its home chest), add a house or hut beside its home (four at most), explore.
     */
    static boolean routine(Brain br) {
        BotEntity b = br.b;
        br.guideRest = 600; // half a minute between rounds
        int r = br.routineStep++ % 5;
        if (r == 2 && (!b.hasHome || br.routineHouses >= 4)) r = 4;
        int mod = r == 3 ? modItem(br) : 0;
        if (r == 3 && mod == 0) r = 4; // no mods here (or every modded thing it can make, made)
        String what;
        switch (r) {
            case 0: case 1: {
                Want w = Want.of(r == 0 ? "ores" : "wood");
                if (w == null) return false;
                br.want = w; br.wantCount = b.count(w.items) + (r == 0 ? 8 : 16); br.deliverToHome = b.hasHome; br.after = b.hasHome ? Brain.Job.NONE : Brain.Job.GO_HOME;
                br.startJob(Brain.Job.GATHER);
                what = r == 0 ? "mining for ores" : "chopping wood";
                break;
            }
            case 2: {
                Blueprint p = Blueprint.of(br.routineHouses % 2 == 0 ? "house" : "hut", 0);
                br.plan = p; br.planX = b.homeX + 10 + br.routineHouses * 10; br.planZ = b.homeZ + 4; br.planY = Names.world(b).getHeight(br.planX, br.planZ); br.planF = 0; br.planIndex = 0; br.planShort = 0;
                br.routineHouses++;
                br.startJob(Brain.Job.BUILD);
                what = "building another " + p.name + " by home";
                break;
            }
            case 3: {
                // the pack's mods: the next modded thing it can make from what it can gather, simplest first, remembered
                br.routineMade.add(mod);
                br.want = null; br.wantCount = b.count(mod) + 1; br.deliverToHome = b.hasHome; br.after = b.hasHome ? Brain.Job.NONE : Brain.Job.GO_HOME;
                br.startJob(Brain.Job.GATHER);
                what = "making a " + Bots.name(mod) + " (working through the mods)";
                break;
            }
            default: {
                double a = Math.random() * Math.PI * 2;
                br.tx = (int) Math.floor(b.x + Math.cos(a) * 64); br.tz = (int) Math.floor(b.z + Math.sin(a) * 64); br.ty = Names.world(b).getHeight(br.tx, br.tz);
                br.startJob(Brain.Job.EXPLORE);
                what = "exploring";
            }
        }
        if (b.ticks % 4 == 0) br.say("All caught up on the guide, so I'm " + what + ".");
        return true;
    }

    /** The next modded item this bot hasn't made whose parts are all plain things it can gather (0: none). */
    static int modItem(Brain br) { return 0; } // no mods in Alpha, Indev or Classic

    /** Now and then, for each bot: if its owner just finished a step, the first of the owner's bots says so. */
    static void watch(Brain br) {
        if (!BotConfig.progressionGuide || br.b.ticks % 100 != 0) return;
        PlayerEntity o = Bots.player(Names.world(br.b), br.b.owner);
        if (o == null) return;
        for (BotEntity x : Bots.bots(Names.world(br.b))) if (x.owner.equals(br.b.owner)) { if (x != br.b) return; break; } // one bot per owner speaks
        List<Integer> fresh = update(o);
        if (fresh.isEmpty()) return;
        String doneText = steps().get(fresh.get(fresh.size() - 1)).text;
        int n = next(o);
        br.say("Nice, " + o.name + ": done \"" + doneText + "\"." + (n < 0 ? " That's every step!" : " Next: " + steps().get(n).text + "."));
    }
}
