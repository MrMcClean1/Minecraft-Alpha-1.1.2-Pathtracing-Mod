package com.alexmcclellan.betapt.bots;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.monster.ZombieEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

/**
 * The bots' own test (-Dclassicbots.test=true): makes a fresh single player world, turns bots on, and checks each
 * thing a bot does through real chat orders. Lines go to the log as "BOTTEST PASS/FAIL ...", then "BOTTEST Done".
 */
public final class BotTest {
    private BotTest() {}

    public static final boolean ON = Boolean.getBoolean("classicbots.test");
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
            try { Object pt = Class.forName("com.alexmcclellan.betapt.render.PathTracer").getField("INSTANCE").get(null); pt.getClass().getField("enabled").setBoolean(pt, false); } catch (Throwable ignored) {}
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
                if (bot != null && bot.owner.equals(p.name) && bot.distanceTo(p) < 5) pass("spawn: Tess is next to " + p.name);
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
                for (int r = 8; r < 120; r += 3) {
                    int fx = (int) Math.floor(bot.x) + r, fz = (int) Math.floor(bot.z) - r / 2, top = mc.world.getHeight(fx, fz);
                    if (mc.world.getBlock(fx, top - 1, fz) != 2 || Math.abs(top - Math.floor(bot.y)) > 2) continue;
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
            case 28: say(p, "Tess follow me"); say(p, "/bot list"); next(); return;
            case 29:
                Bots.log("BOTTEST Done: " + passed + " passed, " + failed + " failed in " + (System.currentTimeMillis() - t0) / 1000 + " s (model " + Model.status + ")");
                next();
                if (!Boolean.getBoolean("classicbots.test.stay")) mc.stop();
        }
    }

    private static ZombieEntity zombie;
    private static Blueprint testPlan;
    private static int tpx, tpy, tpz, tpf, mineY, oresBefore;
}
