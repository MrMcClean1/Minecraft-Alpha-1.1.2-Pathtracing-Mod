package com.mrmcclean.betapt.bots;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.monster.ZombieEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import java.util.*;

/**
 * The bots' own test (-Dclassicbots.test=true): makes a fresh single player world, turns bots on, and checks each
 * thing a bot does through real chat orders. Lines go to the log as "BOTTEST PASS/FAIL ...", then "BOTTEST Done".
 */
public final class BotTest {
    private BotTest() {}

    /** On with -Dclassicbots.test=true, or when the file .minecraft/classicbots-test/run exists (used up when read). */
    public static final boolean ON = Boolean.getBoolean("classicbots.test") || trigger();

    private static boolean trigger() {
        try {
            java.io.File f = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("classicbots-test").resolve("run").toFile();
            if (!f.isFile()) return false;
            try { String w = new String(java.nio.file.Files.readAllBytes(f.toPath())); ONLY_TOWN = w.contains("town"); ONLY_DOCTOR = w.contains("doctor"); ONLY_NAV = w.contains("nav"); ONLY_VOICE = w.contains("voice"); ONLY_GUIDE = w.contains("guide"); ONLY_TAGS = w.contains("nametags"); } catch (Throwable ignored) {}
            return f.delete();
        } catch (Throwable t) { return false; }
    }
    /** "town" in the run file: spawn, then straight to the town steps. */
    private static boolean ONLY_TOWN, ONLY_DOCTOR, ONLY_NAV, ONLY_VOICE, ONLY_GUIDE, ONLY_TAGS;
    private static int step, wait, passed, failed;
    private static BotEntity bot;
    private static long t0;

    private static void pass(String s) { passed++; Bots.log("BOTTEST PASS " + s); next(); }
    private static void fail(String s) { failed++; Bots.log("BOTTEST FAIL " + s); next(); }
    private static void next() { step++; wait = 0; }

    private static void say(PlayerEntity p, String msg) { ((net.minecraft.client.entity.mob.player.ClientPlayerEntity) p).sendChat(msg); }

    private static int has(PlayerEntity p, int id) {
        int n = 0;
        for (ItemStack s : p.inventory.items) if (s != null && s.id == id) n += s.size;
        return n;
    }

    public static void tick(Minecraft mc) {
        if (!ON) return;
        wait++;
        if (step == 0) {
            if (!(mc.screen instanceof net.minecraft.client.gui.screen.menu.TitleScreen)) return;
            if (wait < 40) return;
            TestQuiet.mute(mc.options);
            mc.options.difficulty = 0;
            mc.interactionManager = new net.minecraft.client.SurvivalInteractionManager(mc);
            mc.startGame("BotTest" + (System.currentTimeMillis() / 1000 % 100000));
            t0 = System.currentTimeMillis();
            next();
            return;
        }
        PlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (step == 1 && wait == 1 && Boolean.getBoolean("classicbots.test.notracer")) {
            // a software-rendered test machine: the path tracer would hold the game to a tick or two a second
            try { Object pt = Class.forName("com.mrmcclean.betapt.render.PathTracer").getField("INSTANCE").get(null); pt.getClass().getField("enabled").setBoolean(pt, false); } catch (Throwable ignored) {}
        }
        // no window focus under a test display: Beta pauses single player behind the game menu, and nothing would move
        if (mc.screen instanceof net.minecraft.client.gui.screen.menu.GameMenuScreen || mc.screen instanceof net.minecraft.client.gui.screen.menu.TitleScreen) mc.openScreen(null);
        if (wait % 600 == 0) Bots.log("BOTTEST status: player@" + System.identityHashCode(p) + " step " + step + ", " + wait + " ticks in, player health " + p.health + ", screen " + (mc.screen == null ? "none" : mc.screen.getClass().getName()) + ", player has " + has(p, 17) + " logs " + has(p, 4) + " cobblestone");
        try { run(mc, p); } catch (Throwable t) { fail("step " + step + " threw " + t); }
    }

    private static void run(Minecraft mc, PlayerEntity p) {
        switch (step) {
            case 1: if (wait > 100) { mc.world.setTime(1000); say(p, "/bot on"); say(p, "/bot spawn Tess"); next(); } return;
            case 2:
                for (BotEntity b : Bots.bots(mc.world)) if (b.botName().equals("Tess")) bot = b;
                if (bot != null && bot.owner.equals(p.name) && bot.distanceTo(p) < 5) { pass("spawn: Tess is next to " + p.name); if (ONLY_TOWN) step = 32; if (ONLY_DOCTOR) step = 42; if (ONLY_NAV) step = 45; if (ONLY_VOICE) step = 48; if (ONLY_GUIDE) step = 50; if (ONLY_TAGS) step = 55; }
                else if (wait > 40) fail("spawn: no bot (" + Bots.bots(mc.world).size() + " bots)");
                return;
            case 3: {
                say(p, "Tess follow me");
                // 10 blocks off, on dry ground (a lake there would drown them both)
                int[][] offs = {{10, 0}, {0, 10}, {-10, 0}, {0, -10}, {10, 10}, {-10, -10}, {10, -10}, {-10, 10}};
                for (int[] o : offs) {
                    int x = (int) Math.floor(p.x) + o[0], z = (int) Math.floor(p.z) + o[1], y = mc.world.getHeight(x, z);
                    int below = mc.world.getBlock(x, y - 1, z);
                    if (below >= 8 && below <= 11 || below == 0) continue;
                    p.setPosition(x + 0.5, y + p.eyeHeight + 0.1, z + 0.5);
                    break;
                }
                next();
                return;
            }
            case 4:
                if (bot.distanceTo(p) < 5) pass("follow: caught up in " + wait + " ticks");
                else if (wait > 400) fail("follow: still " + (int) bot.distanceTo(p) + " blocks away");
                return;
            case 5: say(p, "Tess bring me 4 wood"); next(); return;
            case 6:
                if (has(p, 17) >= 4) pass("gather wood: got " + has(p, 17) + " logs in " + wait / 20 + " s");
                else if (wait > 20 * 240) fail("gather wood: " + has(p, 17) + " logs; bot: " + bot.brain.statusLine() + " carrying " + bot.brain.inventoryLine());
                return;
            case 7: say(p, "Tess bring me 3 cobblestone"); next(); return;
            case 8:
                if (has(p, 4) >= 3) pass("gather cobblestone (made its own pickaxe): " + has(p, 4) + " in " + wait / 20 + " s, tools: " + bot.brain.inventoryLine());
                else if (wait > 20 * 360) fail("gather cobblestone: " + has(p, 4) + "; bot: " + bot.brain.statusLine() + " carrying " + bot.brain.inventoryLine());
                return;
            case 9: bot.add(new ItemStack(5, 8, 0)); say(p, "Tess set up a base here"); next(); return;
            case 10: {
                int[] c = bot.brain.findNear(new int[]{54}, bot.homeX, bot.homeY, bot.homeZ, 6, false);
                if (bot.hasHome && c != null) pass("base: home set, chest at " + c[0] + " " + c[1] + " " + c[2]);
                else if (wait > 60) fail("base: home " + bot.hasHome + ", chest " + (c != null));
                return;
            }
            case 11: say(p, "Tess keep me stocked with 4 dirt"); next(); return;
            case 12: {
                int[] c = bot.brain.findNear(new int[]{54}, bot.homeX, bot.homeY, bot.homeZ, 6, false);
                int n = 0;
                if (c != null) { BlockEntity be = mc.world.getBlockEntity(c[0], c[1], c[2]); if (be instanceof Inventory) for (int i = 0; i < ((Inventory) be).getSize(); i++) { ItemStack s = ((Inventory) be).getItem(i); if (s != null && s.id == 3) n += s.size; } }
                if (n >= 4) pass("chore: chest stocked with " + n + " dirt in " + wait / 20 + " s");
                else if (wait > 20 * 240) fail("chore: chest has " + n + " dirt; bot: " + bot.brain.statusLine());
                return;
            }
            case 13: say(p, "Tess stop chores"); say(p, "Tess guard"); mc.options.difficulty = 1; mc.world.difficulty = 1; next(); return;
            case 14: {
                ZombieEntity z = new ZombieEntity(mc.world);
                z.setPositionAndAngles(bot.x + 5, bot.y, bot.z, 0, 0);
                mc.world.addEntity(z);
                zombie = z;
                next();
                return;
            }
            case 15:
                if (zombie.removed || zombie.health <= 0) pass("guard: zombie killed in " + wait / 20 + " s, bot health " + bot.health);
                else if (wait > 20 * 40) fail("guard: zombie still alive, health " + zombie.health);
                return;
            case 16: { mc.options.difficulty = 0; mc.world.difficulty = 0; String before = bot.brain.lastReport; say(p, "Tess what do you have"); if (!bot.brain.lastReport.equals(before)) pass("inventory talk: " + bot.brain.lastReport); else fail("inventory talk: no answer"); return; }
            case 17: {
                bot.add(new ItemStack(263, 5, 0));
                int before = has(p, 263);
                p.setPosition(bot.x + 1, bot.y + 0.5, bot.z);
                say(p, "Tess give me 5 coal");
                if (has(p, 263) - before >= 5 || wait > 0) { next(); }
                return;
            }
            case 18:
                if (has(p, 263) >= 5) pass("give: got 5 coal");
                else if (wait > 100) fail("give: player has " + has(p, 263) + " coal");
                return;
            case 19: {
                bot.add(new ItemStack(3, 10, 0));
                bot.takeDamage(null, 100);
                if (bot.health == 20 && bot.brain.job == Brain.Job.RECOVER) pass("knocked out: woke up, going back for its things");
                else if (bot.health == 20 && bot.brain.lastReport.contains("not going back")) pass("knocked out: woke up; its things went into water, so it stays out (as meant)");
                else fail("knocked out: health " + bot.health + ", job " + bot.brain.job);
                return;
            }
            case 20: {
                NbtCompound n = new NbtCompound();
                bot.add(new ItemStack(264, 2, 0));
                bot.writeNbt(n);
                BotEntity c = Bots.factory.apply(mc.world);
                c.readNbt(n);
                if (c.botName().equals("Tess") && c.owner.equals(p.name) && c.count(264) == 2 && c.hasHome == bot.hasHome) pass("save and load: name, owner, home, inventory kept");
                else fail("save and load: " + c.botName() + " " + c.owner + " " + c.count(264));
                return;
            }
            case 21: {
                Orders.Order o = Orders.parse("could you bring me a stack of iron please");
                Want w = Want.of(o.what);
                if (o.kind == Orders.Kind.GATHER && o.count == 64 && w != null && w.smeltTo == 265) pass("orders: \"bring me a stack of iron\" -> 64 iron ingots (smelted)");
                else fail("orders: " + o + " want " + (w == null ? null : w.label));
                return;
            }
            case 22: {
                bot.brain.mode = Brain.Mode.STAY;
                // on the surface: an earlier step may have left you next to the bot down a cave or its mine
                { int sx = (int) Math.floor(p.x), sz = (int) Math.floor(p.z); p.setPosition(sx + 0.5, mc.world.getHeight(sx, sz) + p.eyeHeight + 0.1, sz + 0.5); }
                // each part on its own: the bot starts next to you
                bot.setPositionAndAngles(p.x + 1, p.y - p.eyeHeight + 0.1, p.z, 0, 0);
                bot.add(new ItemStack(4, 64, 0)); bot.add(new ItemStack(5, 32, 0));
                say(p, "Tess build a hut here");
                testPlan = bot.brain.plan; tpx = bot.brain.planX; tpy = bot.brain.planY; tpz = bot.brain.planZ; tpf = bot.brain.planF;
                next();
                return;
            }
            case 23: {
                if (testPlan == null) { fail("build: no plan made"); return; }
                int ok = 0, total = 0;
                for (int[] pb : testPlan.blocks) { if (pb[3] == 0) continue; total++; int[] w = Blueprint.place(pb, tpx, tpy, tpz, tpf); if (mc.world.getBlock(w[0], w[1], w[2]) == pb[3]) ok++; }
                if (bot.brain.plan == null && ok * 10 >= total * 9) pass("build: hut finished, " + ok + " of " + total + " blocks in place in " + wait / 20 + " s");
                else if (wait > 20 * 150) fail("build: " + ok + " of " + total + " blocks; bot: " + bot.brain.statusLine() + " carrying " + bot.brain.inventoryLine());
                return;
            }
            case 24: {
                bot.add(new ItemStack(290, 1, 0)); bot.add(new ItemStack(295, 12, 0));
                // the field: beside the hut, where the bot stands
                // open grass nearby (not a tree top, not the hut)
                // out in 8 directions in turn, nearest first (one line out could miss every field in a sandy or wet world)
                for (int r8 = 0; r8 < 8 * 38; r8++) {
                    int r = 8 + (r8 / 8) * 3, dir = r8 % 8;
                    int fx = (int) Math.floor(bot.x) + r * new int[]{1, 1, 0, -1, -1, -1, 0, 1}[dir], fz = (int) Math.floor(bot.z) + r * new int[]{0, 1, 1, 1, 0, -1, -1, -1}[dir], top = mc.world.getHeight(fx, fz);
                    if (mc.world.getBlock(fx, top - 1, fz) != 2 || Math.abs(top - mc.world.getHeight((int) Math.floor(bot.x), (int) Math.floor(bot.z))) > 4) continue; // near the ground level where the bot is (it may stand on the hut it just built)
                    int grass = 0; // a field's worth of open grass around it, not a beach or a forest
                    for (int gx = -4; gx <= 4; gx++) for (int gz = -4; gz <= 4; gz++) { int t2 = mc.world.getHeight(fx + gx, fz + gz); if (Math.abs(t2 - top) <= 1 && mc.world.getBlock(fx + gx, t2 - 1, fz + gz) == 2) grass++; }
                    if (grass < 40) continue;
                    p.setPosition(fx + 0.5, top + p.eyeHeight + 0.1, fz + 0.5);
                    bot.setPositionAndAngles(fx + 2.5, top + 0.1, fz + 0.5, 0, 0);
                    break;
                }
                say(p, "Tess farm here");
                next();
                return;
            }
            case 25: {
                int land = bot.brain.countFarmland(), crops = bot.brain.countCrops();
                if (land >= 20 && crops >= 8) pass("farm: " + land + " farmland, " + crops + " planted in " + wait / 20 + " s");
                else if (wait > 20 * 150) fail("farm: " + land + " farmland, " + crops + " planted; bot: " + bot.brain.statusLine() + " carrying " + bot.brain.inventoryLine());
                return;
            }
            case 26: {
                bot.brain.hasFarm = false;
                bot.add(new ItemStack(274, 1, 0)); bot.add(new ItemStack(50, 16, 0));
                mineY = (int) Math.floor(bot.y);
                oresBefore = bot.count(Want.of("ores").items);
                say(p, "Tess go mining");
                next();
                return;
            }
            case 27: {
                int down = mineY - (int) Math.floor(bot.y);
                int ores = bot.count(Want.of("ores").items) - oresBefore;
                if (down >= 6 || ores >= 2) pass("mining: dug down " + down + " blocks and mined " + ores + " ore in " + wait / 20 + " s, carrying " + bot.brain.inventoryLine());
                else if (wait > 20 * 150) fail("mining: only " + down + " blocks down; bot: " + bot.brain.statusLine());
                return;
            }
            case 28: case 29: case 30: case 31: step = 32; wait = 0; return; // on to the town steps
            case 32: case 33: case 34: case 35: case 36: case 37: case 38: case 39: case 40: case 41:
                town(mc, p);
                return;
            case 42: {
                // machine help: a furnace two blocks in front of you with ore and no fuel
                doctorWas = BotConfig.machineDoctor; BotConfig.machineDoctor = true;
                int fx = (int) Math.floor(p.x), fz = (int) Math.floor(p.z) + 2, fy = (int) Math.floor(p.y - p.eyeHeight) + 1;
                mc.world.setBlock(fx, fy - 1, fz, 1); mc.world.setBlock(fx, fy, fz, 61);
                p.yaw = 0; p.pitch = 0;
                BlockEntity be = mc.world.getBlockEntity(fx, fy, fz);
                if (be instanceof Inventory) ((Inventory) be).setItem(0, new ItemStack(15, 4, 0));
                furnace = new int[]{fx, fy, fz};
                bot.brain.lastReport = "";
                say(p, "Tess why isn't this furnace working?");
                next();
                return;
            }
            case 43:
                if (bot.brain.lastReport.contains("no fuel")) { pass("machine help: \"" + bot.brain.lastReport + "\""); BlockEntity be = mc.world.getBlockEntity(furnace[0], furnace[1], furnace[2]); if (be instanceof Inventory) { ((Inventory) be).setItem(0, null); ((Inventory) be).setItem(1, new ItemStack(263, 4, 0)); } bot.brain.lastReport = ""; say(p, "Tess what's wrong with this furnace"); }
                else if (wait > 200) { fail("machine help: \"" + bot.brain.lastReport + "\""); bot.brain.lastReport = ""; say(p, "Tess what's wrong with this furnace"); }
                return;
            case 44:
                if (bot.brain.lastReport.contains("nothing in it to smelt")) { pass("machine help, empty furnace: \"" + bot.brain.lastReport + "\""); }
                else if (wait > 200) fail("machine help, empty furnace: \"" + bot.brain.lastReport + "\"");
                else return;
                mc.world.setBlock(furnace[0], furnace[1], furnace[2], 0);
                BotConfig.machineDoctor = doctorWas;
                return;
            case 45: {
                // a navigation course beside you, along +x on a stone floor: a wall with a wooden door, a water channel
                // two deep, a ledge one up then down again, and a stone wall with no way round (dig through it)
                bot.brain.mode = Brain.Mode.STAY; bot.brain.job = Brain.Job.NONE;
                int x0 = (int) Math.floor(p.x) + 4, z0 = (int) Math.floor(p.z), y0 = mc.world.getHeight(x0, z0);
                for (int x = x0 - 1; x <= x0 + 22; x++) for (int z = z0 - 3; z <= z0 + 3; z++) {
                    for (int y = y0 - 4; y < y0; y++) mc.world.setBlock(x, y, z, 1);
                    for (int y = y0; y <= y0 + 5; y++) mc.world.setBlock(x, y, z, Math.abs(z - z0) == 3 && y <= y0 + 4 ? 1 : 0); // side walls: no way round
                }
                for (int z = z0 - 2; z <= z0 + 2; z++) for (int y = y0; y <= y0 + 2; y++) mc.world.setBlock(x0 + 4, y, z, 4); // wall with a door
                mc.world.setBlockWithMetadata(x0 + 4, y0, z0, 64, 0); mc.world.setBlockWithMetadata(x0 + 4, y0 + 1, z0, 64, 8);
                for (int x = x0 + 8; x <= x0 + 9; x++) for (int z = z0 - 2; z <= z0 + 2; z++) { mc.world.setBlock(x, y0 - 1, z, 9); mc.world.setBlock(x, y0 - 2, z, 9); } // water
                for (int x = x0 + 13; x <= x0 + 15; x++) for (int z = z0 - 2; z <= z0 + 2; z++) mc.world.setBlock(x, y0, z, 1); // a ledge
                for (int z = z0 - 2; z <= z0 + 2; z++) for (int y = y0; y <= y0 + 2; y++) mc.world.setBlock(x0 + 18, y, z, 1); // stone wall
                bot.setPositionAndAngles(x0 + 0.5, y0 + 0.1, z0 + 0.5, 0, 0);
                bot.add(new ItemStack(274, 1, 0));
                p.setPosition(x0 + 21.5, y0 + p.eyeHeight + 0.1, z0 + 0.5);
                Nav.resetStats(); courseStart = System.currentTimeMillis();
                say(p, "Tess come here");
                next();
                return;
            }
            case 46: {
                double d = bot.distanceTo(p);
                if (d < 4) pass("navigation course (door, water, ledge, dig through): across in " + (System.currentTimeMillis() - courseStart) / 1000 + " s, " + Nav.stats());
                else if (wait > 20 * 90) fail("navigation course: still " + (int) d + " blocks away at " + (int) Math.floor(bot.x) + " " + (int) Math.floor(bot.y) + " " + (int) Math.floor(bot.z) + "; bot: " + bot.brain.statusLine() + ", " + Nav.stats());
                else return;
                // then a long walk over open country: you 60 blocks off on dry ground, "come here"
                int[][] offs = {{60, 0}, {0, 60}, {-60, 0}, {0, -60}, {42, 42}, {-42, -42}, {42, -42}, {-42, 42}};
                for (int[] o : offs) {
                    int x = (int) Math.floor(p.x) + o[0], z = (int) Math.floor(p.z) + o[1], y = mc.world.getHeight(x, z);
                    int below = mc.world.getBlock(x, y - 1, z);
                    if (below >= 8 && below <= 11 || below == 0) continue;
                    p.setPosition(x + 0.5, y + p.eyeHeight + 0.1, z + 0.5);
                    break;
                }
                Nav.resetStats(); courseStart = System.currentTimeMillis(); wait = 0;
                return;
            }
            case 47:
                if (wait == 60) say(p, "Tess come here"); // the chunks around you load first
                if (wait <= 60) return;
                if (bot.distanceTo(p) < 4) pass("long walk (60 blocks over open country): " + (System.currentTimeMillis() - courseStart) / 1000 + " s, " + Nav.stats());
                else if (wait > 20 * 125) fail("long walk: still " + (int) bot.distanceTo(p) + " blocks away; bot: " + bot.brain.statusLine() + ", " + Nav.stats());
                else return;
                say(p, "Tess follow me");
                return;
            case 48: { VoiceChat.testVoice(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "Tess"); next(); return; }
            case 49: {
                // voice chat: a line to speech (Piper) and back to words (Whisper), from .minecraft/classicbots-voice
                String r = VoiceChat.testResult;
                if (r == null && wait < 20 * 60) return;
                if (r != null && r.startsWith("PASS")) pass(r.substring(5)); else fail(r == null ? "voice: no answer in 60 s" : r.substring(5));
                return;
            }
            case 50: { // the progression guide (on by default): "what next?", "help me with that", and the bot's "Nice" once the step is done
                guideWas = BotConfig.progressionGuide; BotConfig.progressionGuide = true;
                bot.brain.mode = Brain.Mode.FOLLOW; bot.brain.job = Brain.Job.NONE;
                bot.brain.lastReport = "";
                say(p, "Tess what next?");
                next();
                return;
            }
            case 51:
                if (bot.brain.lastReport.startsWith("Next:") || bot.brain.lastReport.contains("every step")) { pass("guide: \"" + bot.brain.lastReport + "\" (" + Guide.steps().size() + " steps in this version)"); bot.brain.lastReport = ""; guideIdx = Guide.next(p); guideHas = bot.count(Guide.steps().get(Math.max(0, guideIdx)).ids[0]); say(p, "Tess help me with that"); }
                else if (wait > 100) { fail("guide: no next step said: \"" + bot.brain.lastReport + "\""); say(p, "Tess help me with that"); }
                return;
            case 52:
                // fetching, or already done (it had the parts and made it on the spot)
                if (bot.brain.job == Brain.Job.GATHER || bot.brain.job == Brain.Job.HUNT || Guide.next(p) != guideIdx || guideIdx >= 0 && bot.count(Guide.steps().get(guideIdx).ids[0]) > guideHas) pass("guide: \"help me with that\" sets it fetching: " + bot.brain.statusLine());
                else if (wait > 100) fail("guide: \"help me with that\": " + bot.brain.lastReport + "; " + bot.brain.statusLine());
                else return;
                bot.brain.lastReport = "";
                { int gi = Guide.next(p); if (gi >= 0 && Guide.steps().get(gi).dim == 0) { int gid = Guide.steps().get(gi).ids[0], gn = Guide.steps().get(gi).amount; bot.brain.job = Brain.Job.NONE; bot.add(new ItemStack(gid, gn, 0)); bot.giveTo(p, gid, gn); } } // as if you'd made it: handed over, so the step is done
                return;
            case 53:
                if (bot.brain.lastReport.startsWith("Nice")) { pass("guide: the step done is noticed: \"" + bot.brain.lastReport + "\" in " + wait / 20 + " s"); BotConfig.progressionGuide = guideWas; }
                else if (wait > 20 * 240) { fail("guide: no \"Nice\" after the step: " + bot.brain.lastReport + "; " + bot.brain.statusLine()); BotConfig.progressionGuide = guideWas; }
                return;
            case 54: { // with nothing from you (keeping house), a bot works through the guide on its own
                if (wait == 1) { bot.brain.mode = Brain.Mode.HOME; bot.brain.job = Brain.Job.NONE; bot.brain.guideDone.clear(); bot.brain.guideRest = 0; bot.brain.guideTries = 0; return; }
                if (bot.brain.job == Brain.Job.GATHER || bot.brain.job == Brain.Job.HUNT) {
                    String first = bot.brain.statusLine();
                    // infinite progression: with every step done it keeps going (mining, wood, building by home, exploring)
                    bot.brain.job = Brain.Job.NONE;
                    for (int i = 0; i < Guide.steps().size(); i++) bot.brain.guideDone.add(i);
                    bot.brain.guideRest = 0;
                    boolean more = Guide.work(bot.brain) && bot.brain.job != Brain.Job.NONE;
                    String then = bot.brain.statusLine();
                    bot.brain.job = Brain.Job.NONE; bot.brain.mode = Brain.Mode.STAY;
                    if (more) pass("guide: left to itself it works on its own next step (" + first + "), and with every step done it keeps going (" + then + ")");
                    else fail("guide: with every step done it stopped: " + then);
                }
                else if (wait > 200) { fail("guide: left to itself it did nothing: " + bot.brain.statusLine()); bot.brain.mode = Brain.Mode.STAY; }
                return;
            }
            case 55: { // names over the bots (on by default): drawn with the switch on, none with it off
                tagsWas = BotConfig.nametags; BotConfig.nametags = true;
                bot.brain.mode = Brain.Mode.STAY; bot.brain.job = Brain.Job.NONE;
                int bx = (int) Math.floor(p.x), bz = (int) Math.floor(p.z) + 4, by = mc.world.getHeight(bx, bz);
                bot.setPositionAndAngles(bx + 0.5, by + 0.1, bz + 0.5, 180, 0);
                p.yaw = 0; p.pitch = 0;
                tagsBefore = BotRenderer.tagsDrawn;
                next();
                return;
            }
            case 56: {
                p.yaw = 0; p.pitch = 0;
                if (wait == 60) { tagsOn = BotRenderer.tagsDrawn - tagsBefore; BotConfig.nametags = false; tagsBefore = BotRenderer.tagsDrawn; }
                if (wait < 120) return;
                int off = BotRenderer.tagsDrawn - tagsBefore;
                BotConfig.nametags = tagsWas;
                if (tagsOn > 0 && off == 0) pass("names over the bots: drawn " + tagsOn + " times in 3 s with the switch on, none with it off");
                else fail("names over the bots: " + tagsOn + " drawn with the switch on, " + off + " with it off");
                return;
            }
            case 57: { // bot chat levels: replies lets an answer through but not an update; off lets nothing through
                chatWas = BotConfig.botChat; BotConfig.botChat = "replies";
                bot.brain.replyUntil = 0;
                spokenBefore = bot.brain.spoken;
                bot.brain.say("Just an update on what I'm doing.");
                updateLeaked = bot.brain.spoken != spokenBefore;
                spokenBefore = bot.brain.spoken;
                say(p, "Tess what next?");
                next();
                return;
            }
            case 58: {
                if (wait == 1) { answered = bot.brain.spoken > spokenBefore; BotConfig.botChat = "off"; spokenBefore = bot.brain.spoken; say(p, "Tess what next?"); return; }
                if (wait < 60) return;
                boolean silent = bot.brain.spoken == spokenBefore;
                BotConfig.botChat = chatWas;
                if (!updateLeaked && answered && silent) pass("bot chat: \"replies\" keeps updates quiet and answers you; \"off\" says nothing");
                else fail("bot chat: update said " + updateLeaked + ", answered with replies " + answered + ", silent with off " + silent);
                return;
            }
            case 59: { // bots join you by themselves (autoSpawn, 3 as in Tekkit): your bots made up to 3, then the extras sent off
                Bots.spawnUpTo(p);
                List<String> mine = new ArrayList<>();
                for (BotEntity x : Bots.bots(mc.world)) if (x.owner.equals(p.name)) mine.add(x.botName());
                boolean ok = mine.size() >= Math.min(BotConfig.autoSpawn, BotConfig.maxBotsPerPlayer);
                for (String n : mine) if (!n.equals("Tess")) say(p, "/bot remove " + n);
                if (ok) pass("auto spawn: " + mine.size() + " bots of yours (" + String.join(", ", mine) + "), autoSpawn " + BotConfig.autoSpawn); else fail("auto spawn: only " + mine);
                return;
            }
            case 60: say(p, "Tess follow me"); say(p, "/bot list"); next(); return;
            case 61:
                Bots.log("BOTTEST Done: " + passed + " passed, " + failed + " failed in " + (System.currentTimeMillis() - t0) / 1000 + " s (model " + Model.status + ")");
                next();
                TestQuiet.restore();
                if (!Boolean.getBoolean("classicbots.test.stay")) { // as the Alpha test ends: the loop stops, and the process goes 15 s later if anything still holds it
                    mc.running = false;
                    Thread t = new Thread(() -> { try { Thread.sleep(15000); } catch (InterruptedException ignored) {} System.exit(failed == 0 ? 0 : 1); }, "Bot test exit");
                    t.setDaemon(true);
                    t.start();
                }
        }
    }

    // ------------------------------------------------------------------ bot towns (optional feature)

    private static boolean townsWas;
    private static int maxWas, tcx, tcy, tcz;
    private static final String[] EXTRA = {"Ada", "Brock", "Cleo", "Dax"};

    /** The street's ground, below and at the town's level, for the path check's report: "z:below/at". */
    private static String streetIds(net.minecraft.world.World w) {
        StringBuilder sb = new StringBuilder();
        for (int z = 1; z <= 6; z++) sb.append(z == 1 ? "" : " ").append(-z).append(':').append(w.getBlock(tcx, tcy - 1, tcz - z)).append('/').append(w.getBlock(tcx, tcy, tcz - z)).append("^").append(w.getHeight(tcx, tcz - z));
        return sb.toString();
    }

    private static BotEntity byJob(net.minecraft.world.World w, Town.Job j) {
        for (BotEntity b : Bots.bots(w)) if (b.brain.town != null && b.brain.town.job == j) return b;
        return null;
    }

    private static void town(Minecraft mc, PlayerEntity p) {
        net.minecraft.world.World w = mc.world;
        switch (step) {
            case 32: {
                // five bots (one for each job) on a flat grass patch 40 blocks east, from the field to the second row of plots
                if (wait == 1) {
                    townsWas = BotConfig.towns; maxWas = BotConfig.maxBotsPerPlayer;
                    BotConfig.maxBotsPerPlayer = Math.max(5, maxWas);
                    say(p, "/bot town on");
                    int px = (int) Math.floor(p.x) + 40, pz = (int) Math.floor(p.z), top = w.getHeight(px, pz);
                    tcx = px; tcz = pz; tcy = Math.max(64, Math.min(top, 90));
                    for (int x = -12; x <= 12; x++) for (int z = -26; z <= 16; z++) {
                        for (int k = 1; k <= 4; k++) w.setBlock(tcx + x, tcy - k, tcz + z, k == 1 ? 2 : 3);
                        for (int k = 0; k < 14; k++) w.setBlock(tcx + x, tcy + k, tcz + z, 0);
                    }
                    p.setPosition(tcx + 0.5, tcy + p.eyeHeight + 0.1, tcz + 3.5);
                    for (String n : EXTRA) say(p, "/bot spawn " + n);
                    return;
                }
                if (wait == 20) {
                    for (BotEntity b : Bots.bots(w)) { b.add(new ItemStack(291, 1, 0)); b.add(new ItemStack(295, 16, 0)); } // a hoe and seeds each, before the jobs are given (the farmer starts at once)
                    for (BotEntity b : Bots.bots(w)) { b.brain.mode = Brain.Mode.STAY; b.brain.job = Brain.Job.NONE; b.brain.plan = null; b.brain.hasFarm = false; b.setPositionAndAngles(tcx + 0.5, tcy + 0.1, tcz + 2.5, 0, 0); }
                    p.setPosition(tcx + 0.5, tcy + p.eyeHeight + 0.1, tcz + 0.5);
                    say(p, "/bot town start");
                    return;
                }
                if (wait < 40) return;
                p.setPosition(tcx + 0.5, tcy + p.eyeHeight + 0.1, tcz + 4.5);
                int in = 0;
                for (BotEntity b : Bots.bots(w)) if (b.brain.town != null) in++;
                BotEntity builder = byJob(w, Town.Job.BUILDER), miner = byJob(w, Town.Job.MINER), farmer = byJob(w, Town.Job.FARMER);
                if (in >= 5 && builder != null && miner != null && farmer != null) {
                    // a start, as if they had worked a while: the builder's blocks, the farmer's hoe and seeds, the miner's pick and a load
                    builder.add(new ItemStack(54, 1, 0)); builder.add(new ItemStack(4, 64, 0)); builder.add(new ItemStack(4, 64, 0)); builder.add(new ItemStack(5, 64, 0));
                    farmer.add(new ItemStack(291, 1, 0)); farmer.add(new ItemStack(295, 16, 0));
                    miner.add(new ItemStack(274, 1, 0)); miner.add(new ItemStack(4, 40, 0));
                    pass("town: founded with " + in + " bots: " + Town.status(w, Bots.bots(w)));
                } else fail("town: founded with " + in + " bots (" + Bots.bots(w).size() + " here, towns " + BotConfig.towns + ")");
                return;
            }
            case 33: {
                BotEntity b = byJob(w, Town.Job.BUILDER);
                if (b != null && b.brain.findNear(new int[]{54}, tcx, tcy, tcz, 2, false) != null) pass("town: the builder put the market chest down in " + wait / 20 + " s");
                else if (wait > 20 * 90) fail("town: no market chest; builder " + (b == null ? "none" : b.brain.statusLine() + " carrying " + b.brain.inventoryLine()));
                return;
            }
            case 34: {
                BotEntity b = byJob(w, Town.Job.TRADER);
                if (Town.trades > 0) pass("town: the trader collected a member's surplus (" + Town.trades + " trades) in " + wait / 20 + " s");
                else if (wait > 20 * 120) fail("town: no trades; trader " + (b == null ? "none" : b.brain.statusLine()));
                return;
            }
            case 35: {
                BotEntity b = byJob(w, Town.Job.FARMER);
                int land = 0;
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -2; y <= 1; y++) if (w.getBlock(tcx + x, tcy + y, tcz + 9 + z) == 60) land++;
                if (land >= 6) pass("town: the farmer tilled " + land + " squares of the field in " + wait / 20 + " s");
                else if (wait > 20 * 180) fail("town: " + land + " farmland; farmer " + (b == null ? "none" : b.brain.statusLine() + " carrying " + b.brain.inventoryLine()));
                return;
            }
            case 36: {
                BotEntity b = byJob(w, Town.Job.BUILDER);
                if (b != null && Town.built(w, b.brain.town, 0)) pass("town: the builder finished the hut in " + wait / 20 + " s");
                else if (wait > 20 * 300) fail("town: hut not finished; builder " + (b == null ? "none" : b.brain.statusLine() + " carrying " + b.brain.inventoryLine()));
                return;
            }
            case 37: {
                BotEntity b = byJob(w, Town.Job.BUILDER);
                int cob = 0;
                for (int z = 1; z <= 6; z++) if (w.getBlock(tcx, tcy - 1, tcz - z) == 4) cob++;
                if (b != null && Town.pathDone(w, b.brain.town, 0) && cob >= 5) pass("town: a cobblestone path to the hut (" + cob + " of 6 street blocks) in " + wait / 20 + " s");
                else if (wait > 20 * 180) fail("town: path not done (" + cob + " of 6 street blocks: " + streetIds(w) + "); builder " + (b == null ? "none" : b.brain.statusLine() + " carrying " + b.brain.inventoryLine()));
                return;
            }
            case 38: {
                BotEntity b = byJob(w, Town.Job.BUILDER);
                if (wait == 1) { w.setTime(w.ticks + 24000); return; }
                if (b != null && Town.plots(w, b.brain.town) >= 2) pass("town: the next day it plans another building (" + Town.plots(w, b.brain.town) + " plots)");
                else if (wait > 100) fail("town: still " + (b == null ? 0 : Town.plots(w, b.brain.town)) + " plots the next day");
                return;
            }
            case 39: {
                BotEntity g = byJob(w, Town.Job.GUARD), b = byJob(w, Town.Job.BUILDER);
                if (wait == 1) {
                    w.setTime(w.ticks / 24000 * 24000 + 14000);
                    for (BotEntity x : Bots.bots(w)) { x.brain.job = Brain.Job.NONE; x.brain.plan = null; }
                    return;
                }
                if (g != null && b != null && g.brain.town.walk > 0 && Town.indoorsNow(b)) pass("town: at night the guard walks the street and the builder is indoors (" + wait / 20 + " s)");
                else if (wait > 20 * 60) fail("town: night: guard " + (g == null ? "none" : g.brain.statusLine() + " walk " + g.brain.town.walk) + ", builder indoors " + (b != null && Town.indoorsNow(b)) + (b == null ? "" : " (builder at " + (int) Math.floor(b.x) + " " + (int) Math.floor(b.y) + " " + (int) Math.floor(b.z) + ", hut built " + Town.built(w, b.brain.town, 0) + ", hut plot " + java.util.Arrays.toString(Town.plot(w, b.brain.town, 0)) + ")"));
                return;
            }
            case 40: {
                String s = Town.status(w, Bots.bots(w));
                if (s.startsWith("Town at")) pass("town: status line: " + s); else fail("town: status: " + s);
                return;
            }
            case 41: {
                say(p, "/bot town stop");
                for (String n : EXTRA) say(p, "/bot remove " + n);
                w.setTime(1000);
                BotConfig.towns = townsWas; BotConfig.maxBotsPerPlayer = maxWas; BotConfig.save();
                bot.brain.mode = Brain.Mode.FOLLOW;
                next();
                return;
            }
        }
    }

    private static boolean doctorWas;
    private static int[] furnace;
    private static int guideIdx, guideHas;
    private static boolean guideWas, tagsWas, updateLeaked, answered;
    private static String chatWas;
    private static int spokenBefore;
    private static int tagsBefore, tagsOn;
    private static ZombieEntity zombie;
    private static long courseStart;
    private static Blueprint testPlan;
    private static int tpx, tpy, tpz, tpf, mineY, oresBefore;
}
