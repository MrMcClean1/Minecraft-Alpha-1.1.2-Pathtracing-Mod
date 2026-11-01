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
        // no window focus under a test display: Beta pauses single player behind the game menu, and nothing would move
        if (mc.screen instanceof net.minecraft.client.gui.screen.menu.GameMenuScreen || mc.screen instanceof net.minecraft.client.gui.screen.menu.TitleScreen) mc.openScreen(null);
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
            case 3: say(p, "Tess follow me"); p.setPosition(p.x + 10, p.y + 1, p.z); next(); return;
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
                int[] c = bot.brain.findNear(new int[]{54}, (int) Math.floor(bot.x), (int) Math.floor(bot.y), (int) Math.floor(bot.z), 6, false);
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
            case 22: say(p, "Tess follow me"); say(p, "/bot list"); next(); return;
            case 23:
                Bots.log("BOTTEST Done: " + passed + " passed, " + failed + " failed in " + (System.currentTimeMillis() - t0) / 1000 + " s (model " + Model.status + ")");
                next();
                if (!Boolean.getBoolean("classicbots.test.stay")) mc.stop();
        }
    }

    private static ZombieEntity zombie;
}
