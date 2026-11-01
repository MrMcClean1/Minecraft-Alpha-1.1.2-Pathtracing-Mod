package com.alexmcclellan.betapt.bots;

import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.monster.Monster;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.FoodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.MathHelper;

import java.util.*;

/**
 * What a bot does. Modes say what it does when it has nothing else to do (follow its owner, stay, guard, keep
 * house); jobs are errands (gather 16 iron, hunt, put things away, go home, fetch its things after fainting).
 * Fighting comes first, then eating, then the job, then the mode. Chores are standing errands checked once a minute:
 * "keep me stocked with 32 coal" keeps 32 coal in the home chest.
 */
public class Brain {
    public enum Mode { FOLLOW, STAY, GUARD, HOME }
    public enum Job { NONE, GATHER, HUNT, DELIVER, DEPOSIT, GO_HOME, COME, EXPLORE, RECOVER }

    final BotEntity b;
    public Mode mode = Mode.FOLLOW;
    public Job job = Job.NONE;
    Want want;
    int wantCount, jobTicks, idle, eatCooldown, attackCooldown, choreTimer = 600, thinkTimer;
    boolean deliverToHome;
    int[] target;          // block being mined
    float digProgress;
    int tx, ty, tz;        // job spot (explore, recover, come)
    MobEntity enemy;
    int enemyTicks;
    final List<Chore> chores = new ArrayList<>();
    String lastReport = "";
    private final Random rnd = new Random();

    static final class Chore { final String what; final int count; Chore(String w, int c) { what = w; count = c; } }

    Brain(BotEntity b) { this.b = b; }

    // ------------------------------------------------------------------ the loop

    void tick() {
        if (attackCooldown > 0) attackCooldown--;
        if (eatCooldown > 0) eatCooldown--;
        b.pickUp();
        PlayerEntity owner = Bots.player(Names.world(b), b.owner);
        if (fight(owner)) return;
        if (b.health < 14 && eatCooldown == 0) eat();
        if (job != Job.NONE) { jobTicks++; runJob(owner); return; }
        if (--choreTimer <= 0) { choreTimer = 1200; if (startChore()) return; }
        switch (mode) {
            case FOLLOW:
                if (owner == null) { if (b.hasHome) goHomeQuietly(); return; }
                double d = b.distanceTo(owner);
                if (d > 48 && owner.onGround) { b.setPositionAndAngles(owner.x + rnd.nextInt(3) - 1, owner.y, owner.z + rnd.nextInt(3) - 1, b.yaw, 0); return; }
                if (d > 4) b.walkTo(owner.x, owner.y, owner.z, 3);
                else { b.stopMoving(); b.face(owner.x, owner.y + 1.5, owner.z); }
                break;
            case STAY:
                b.stopMoving();
                if (owner != null && b.distanceTo(owner) < 8) b.face(owner.x, owner.y + 1.5, owner.z);
                break;
            case GUARD:
                if (owner != null && b.distanceTo(owner) > 6) b.walkTo(owner.x, owner.y, owner.z, 4); else b.stopMoving();
                break;
            case HOME:
                goHomeQuietly();
                break;
        }
    }

    private void goHomeQuietly() {
        if (!b.hasHome) { b.stopMoving(); return; }
        if (b.distanceTo(b.homeX + 0.5, b.homeY, b.homeZ + 0.5) > 6) b.walkTo(b.homeX + 0.5, b.homeY, b.homeZ + 0.5, 4);
        else if (++idle % 200 == 0) b.walkTo(b.homeX + rnd.nextInt(9) - 4, b.homeY, b.homeZ + rnd.nextInt(9) - 4, 1.5);
    }

    // ------------------------------------------------------------------ fighting

    private boolean fight(PlayerEntity owner) {
        if (enemy != null && (enemy.removed || enemy.health <= 0 || b.distanceTo(enemy) > 24 || ++enemyTicks > 600)) enemy = null;
        if (enemy == null && b.ticks % 10 == 0) {
            double r = mode == Mode.GUARD ? 14 : 7;
            double best = r * r;
            for (Object o : Names.world(b).getEntitiesOfType(MobEntity.class, b.shape.grown(r, 4, r))) {
                MobEntity e = (MobEntity) o;
                if (!(e instanceof Monster) || e.removed || e.health <= 0) continue;
                double d = b.squaredDistanceTo(e);
                if (d < best && b.see(e)) { best = d; enemy = e; enemyTicks = 0; }
            }
            // anything going for the owner
            if (enemy == null && owner != null && b.distanceTo(owner) < 16)
                for (Object o : Names.world(b).getEntitiesOfType(MobEntity.class, owner.shape.grown(6, 3, 6)))
                    if (o instanceof Monster && !((MobEntity) o).removed) { enemy = (MobEntity) o; enemyTicks = 0; break; }
        }
        if (enemy == null) return false;
        if (b.health <= 4 && !hasFood()) { // run home or to the owner
            double ax = b.x - (enemy.x - b.x), az = b.z - (enemy.z - b.z);
            b.walkTo(ax, b.y, az, 1);
            return true;
        }
        equipWeapon();
        double d = b.distanceTo(enemy);
        if (d > 2.3) b.walkTo(enemy.x, enemy.y, enemy.z, 1.8);
        else b.stopMoving();
        b.face(enemy.x, enemy.y + Names.eye(enemy), enemy.z);
        if (d < 2.8 && attackCooldown == 0) {
            ItemStack w = held();
            int dmg = w != null ? w.getAttackDamage(enemy) : 1;
            b.swing();
            if (enemy.takeDamage(b, dmg) && w != null) { w.takeDamageAndBreak(toolKind(w) == 3 ? 1 : 2); tidyBroken(); }
            attackCooldown = 12;
        }
        return true;
    }

    void attackedBy(MobEntity e) {
        if (e instanceof PlayerEntity) { if (!((PlayerEntity) e).name.equals(b.owner)) say("Hey! Cut it out."); return; }
        if (!(e instanceof BotEntity)) { enemy = e; enemyTicks = 0; }
    }

    boolean ownerCanHurt() { return false; }

    boolean thrownByOwner(ItemEntity e) {
        PlayerEntity o = Bots.player(Names.world(b), b.owner);
        return o != null && e.distanceTo(o) < 2.5;
    }

    // ------------------------------------------------------------------ eating

    private boolean hasFood() { for (ItemStack s : b.inv) if (BotEntity.isFood(s)) return true; return false; }

    private void eat() {
        for (int i = 0; i < b.inv.length; i++) {
            ItemStack s = b.inv[i];
            if (!BotEntity.isFood(s)) continue;
            b.heal(Names.food(s.id));
            if (s.id == 282) b.inv[i] = new ItemStack(Item.BOWL); // stew: the bowl stays
            else if (--s.size <= 0) b.inv[i] = null;
            Names.world(b).playSound(b, "random.eat", 0.5F, 1.0F);
            eatCooldown = 60;
            return;
        }
    }

    // ------------------------------------------------------------------ orders

    /** A player told this bot something. Returns true if it was an order it understood. */
    public boolean order(PlayerEntity from, Orders.Order o, String text) {
        String who = from != null ? from.name : b.owner;
        switch (o.kind) {
            case FOLLOW: mode = Mode.FOLLOW; job = Job.NONE; ack(who, text, "Right behind you."); return true;
            case STAY: mode = Mode.STAY; job = Job.NONE; b.stopMoving(); ack(who, text, "I'll wait here."); return true;
            case COME:
                if (from == null) return false;
                job = Job.COME; jobTicks = 0; tx = MathHelper.floor(from.x); ty = MathHelper.floor(from.y); tz = MathHelper.floor(from.z);
                ack(who, text, "Coming."); return true;
            case GUARD: mode = Mode.GUARD; job = Job.NONE; ack(who, text, "I've got your back."); return true;
            case HOME_SET: setHome(from); return true;
            case HOME_GO:
                if (!b.hasHome) { say("I don't have a home yet. Say \"set up a base here\" where you want it."); return true; }
                mode = Mode.HOME; startJob(Job.GO_HOME); ack(who, text, "Heading home."); return true;
            case GATHER: {
                Want w = Want.of(o.what);
                if (w == null) { say("I don't know how to get " + o.what + "."); return true; }
                want = w; wantCount = Math.min(o.count, 64 * 20); deliverToHome = false;
                startJob(w.food() ? Job.HUNT : Job.GATHER);
                ack(who, text, "On it: " + wantCount + " " + w.label + ".");
                return true;
            }
            case HUNT: want = Want.of("food"); wantCount = o.count; deliverToHome = false; startJob(Job.HUNT); ack(who, text, "Going hunting."); return true;
            case CHORE: {
                Want w = o.what == null ? null : Want.of(o.what);
                if (w == null) { say("Keep you stocked with what?"); return true; }
                if (!b.hasHome) { say("I need a home with a chest first. Say \"set up a base here\"."); return true; }
                chores.removeIf(c -> c.what.equals(w.label));
                chores.add(new Chore(w.label, o.count));
                choreTimer = 20;
                ack(who, text, "I'll keep " + o.count + " " + w.label + " in the chest at home.");
                return true;
            }
            case CHORES_CLEAR: chores.clear(); ack(who, text, "No more chores."); return true;
            case INVENTORY: say(inventoryLine()); return true;
            case GIVE: {
                if (from == null) return false;
                Want w = Want.of(o.what);
                int given = 0;
                if (w != null) for (int id : w.items) given += b.giveTo(from, id, o.count > 0 ? o.count - given : 64 * 36);
                say(given > 0 ? "Here you go." : "I don't have any " + o.what + ".");
                return true;
            }
            case GIVE_ALL:
                if (from == null) return false;
                for (ItemStack s : b.stacks()) b.giveTo(from, s.id, s.size);
                b.hold(null); say("That's everything."); return true;
            case DEPOSIT:
                if (!b.hasHome) { say("I don't have a home chest yet."); return true; }
                startJob(Job.DEPOSIT); ack(who, text, "Putting things away."); return true;
            case EXPLORE: {
                double a = rnd.nextDouble() * Math.PI * 2;
                tx = MathHelper.floor(b.x + Math.cos(a) * 64); tz = MathHelper.floor(b.z + Math.sin(a) * 64); ty = Names.world(b).getHeight(tx, tz);
                startJob(Job.EXPLORE); ack(who, text, "I'll have a look around."); return true;
            }
            case STATUS: ack(who, text, statusLine()); return true;
            case CRAFT_TOOLS: craftTools(true); say(toolsLine()); return true;
            default: return false;
        }
    }

    void startJob(Job j) { job = j; jobTicks = 0; target = null; digProgress = 0; skip.clear(); }

    private void endJob(String report) { job = Job.NONE; target = null; b.stopMoving(); if (report != null) say(report); }

    private void setHome(PlayerEntity from) {
        b.hasHome = true;
        b.homeX = MathHelper.floor(b.x); b.homeY = MathHelper.floor(b.shape.minY); b.homeZ = MathHelper.floor(b.z);
        mode = Mode.HOME;
        int[] c = findNear(new int[]{54}, b.homeX, b.homeY, b.homeZ, 6, false);
        if (c == null) {
            if (b.count(54) == 0 && b.count(5) < 8 && b.count(17) >= 2) craft(5, 4, new int[]{17, 1});
            if (b.count(54) == 0 && b.count(5) >= 8) craft(54, 1, new int[]{5, 8});
            if (b.count(54) > 0) {
                int[] spot = freeSpotNear(b.homeX, b.homeY, b.homeZ);
                if (spot != null) { b.take(54, 1); Names.world(b).setBlock(spot[0], spot[1], spot[2], 54); say("Base set. I put a chest here for our things."); return; }
            }
            say("Base set. I'll need a chest here: give me one, or 8 planks and I'll make it.");
            return;
        }
        say("Base set, next to the chest.");
    }

    // ------------------------------------------------------------------ jobs

    private void runJob(PlayerEntity owner) {
        switch (job) {
            case COME:
                if (b.walkTo(tx + 0.5, ty, tz + 0.5, 2.5) || jobTicks > 900) endJob(null);
                break;
            case GO_HOME:
                if (b.walkTo(b.homeX + 0.5, b.homeY, b.homeZ + 0.5, 2.5) || jobTicks > 3000) endJob(null);
                break;
            case EXPLORE:
                if (b.walkTo(tx + 0.5, ty, tz + 0.5, 3) || jobTicks > 2400) { endJob(seen()); }
                break;
            case RECOVER:
                if (b.walkTo(tx + 0.5, ty, tz + 0.5, 1.5) || jobTicks > 3600) {
                    endJob(jobTicks > 3600 ? "I couldn't get back to my things." : "Got my things back.");
                    if (resumeJob != Job.NONE && want != null) { Job r = resumeJob; resumeJob = Job.NONE; startJob(r); craftTools(false); }
                }
                break;
            case GATHER: gather(owner); break;
            case HUNT: hunt(owner); break;
            case DELIVER: deliver(owner); break;
            case DEPOSIT: deposit(); break;
            default: job = Job.NONE;
        }
    }

    private void gather(PlayerEntity owner) {
        int have = b.count(want.items);
        if (have >= wantCount || b.freeSlots() == 0) { done(have); return; }
        if (want.smeltFrom != 0 && b.count(want.smeltFrom) > 0 && smelt()) return;
        if (jobTicks > 20 * 60 * 6) { endJob("I only found " + have + " " + want.label + ". " + (have > 0 ? "Bringing them." : "")); if (have > 0) startDeliver(); return; }
        if (target != null && !want.mine.contains(Names.world(b).getBlock(target[0], target[1], target[2]))) target = null;
        if (target == null && jobTicks % 20 == 1) {
            target = findNear(toArray(want.mine), MathHelper.floor(b.x), MathHelper.floor(b.y), MathHelper.floor(b.z), 28, true);
            if (target == null) { // look further away
                if (jobTicks % 400 == 1) { double a = rnd.nextDouble() * Math.PI * 2; tx = MathHelper.floor(b.x + Math.cos(a) * 40); tz = MathHelper.floor(b.z + Math.sin(a) * 40); ty = Names.world(b).getHeight(tx, tz); }
                b.walkTo(tx + 0.5, ty, tz + 0.5, 3);
                return;
            }
        }
        if (target == null) return;
        Block blk = Bots.block(Names.world(b).getBlock(target[0], target[1], target[2]));
        if (blk != null && !Names.handMineable(blk) && !canHarvest(blk)) {
            craftTools(false);
            if (!canHarvest(blk)) {
                if (needsPick(blk) && b.count(4) < 3 && !want.label.equals("cobblestone")) { subGoal("cobblestone", 3 - b.count(4)); return; }
                if (b.count(17) + b.count(5) / 4 < 2 && !want.label.equals("wood")) { subGoal("wood", 3); return; }
                endJob("I need a better pickaxe for " + want.label + ".");
                return;
            }
        }
        mineAt(target);
    }

    private final Deque<Object[]> stack = new ArrayDeque<>();

    /** Get something needed first (cobblestone for a pickaxe), then carry on. */
    private void subGoal(String what, int n) {
        stack.push(new Object[]{want, wantCount, deliverToHome});
        want = Want.of(what); wantCount = Math.max(n, 1);
        target = null; jobTicks = 0;
        say("Need " + want.label + " for tools first.");
    }

    private void done(int have) {
        if (!stack.isEmpty()) {
            Object[] s = stack.pop();
            want = (Want) s[0]; wantCount = (Integer) s[1]; deliverToHome = (Boolean) s[2];
            craftTools(false); target = null; jobTicks = 0;
            return;
        }
        startDeliver();
    }

    private boolean teleportTo(PlayerEntity o) { b.setPositionAndAngles(o.x, o.y - o.eyeHeight + 0.1, o.z, b.yaw, 0); return true; }

    private void startDeliver() { job = Job.DELIVER; jobTicks = 0; }

    private void deliver(PlayerEntity owner) {
        if (deliverToHome || owner == null) { startJob(Job.DEPOSIT); return; }
        // close enough to toss it over, or walked a while: hand it over (thrown if not right next to them)
        if (b.walkTo(owner.x, owner.y, owner.z, 2.5) || (b.distanceTo(owner) < 8 && jobTicks > 200) || (jobTicks > 1200 && owner.onGround && teleportTo(owner))) {
            int n = 0;
            for (int id : want.items) n += b.giveTo(owner, id, b.count(id));
            endJob(n > 0 ? "Here's " + n + " " + want.label + "." : null);
        } else if (jobTicks > 2400) { startJob(Job.DEPOSIT); }
    }

    private void hunt(PlayerEntity owner) {
        int have = b.count(want.items);
        if (have >= wantCount || jobTicks > 20 * 60 * 4) { if (have == 0) endJob("Couldn't find any animals for " + want.label + "."); else startDeliver(); return; }
        MobEntity prey = null;
        double best = 32 * 32;
        for (Object o : Names.world(b).getEntitiesOfType(MobEntity.class, b.shape.grown(32, 8, 32))) {
            MobEntity e = (MobEntity) o;
            if (e.removed || e.health <= 0 || e instanceof PlayerEntity || e instanceof BotEntity) continue;
            if (!want.prey(kind(e))) continue;
            double d = b.squaredDistanceTo(e);
            if (d < best) { best = d; prey = e; }
        }
        if (prey == null) {
            if (jobTicks % 400 == 1) { double a = rnd.nextDouble() * Math.PI * 2; tx = MathHelper.floor(b.x + Math.cos(a) * 40); tz = MathHelper.floor(b.z + Math.sin(a) * 40); ty = Names.world(b).getHeight(tx, tz); }
            b.walkTo(tx + 0.5, ty, tz + 0.5, 3);
            return;
        }
        equipWeapon();
        if (b.distanceTo(prey) > 2.2) b.walkTo(prey.x, prey.y, prey.z, 1.8);
        else {
            b.face(prey.x, prey.y + 0.5, prey.z);
            if (attackCooldown == 0) { ItemStack w = held(); b.swing(); prey.takeDamage(b, w != null ? w.getAttackDamage(prey) : 1); attackCooldown = 12; }
        }
    }

    /** What kind of animal (Minecraft's classes carry obfuscated names at runtime, so the vanilla ones are checked by type). */
    static String kind(Entity e) {
        if (e instanceof net.minecraft.entity.mob.animal.PigEntity) return "pig";
        if (e instanceof net.minecraft.entity.mob.animal.SheepEntity) return "sheep";
        if (e instanceof net.minecraft.entity.mob.animal.CowEntity) return "cow";
        if (e instanceof net.minecraft.entity.mob.animal.ChickenEntity) return "chicken";
        if (e instanceof net.minecraft.entity.mob.monster.SpiderEntity) return "spider";
        if (e instanceof net.minecraft.entity.mob.monster.SkeletonEntity) return "skeleton";
        if (e instanceof net.minecraft.entity.mob.monster.CreeperEntity) return "creeper";
        if (e instanceof net.minecraft.entity.mob.monster.SlimeEntity) return "slime";
        if (e instanceof net.minecraft.entity.mob.monster.ZombieEntity) return "zombie";
        return e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private void deposit() {
        int[] chest = homeChest();
        if (chest == null) { endJob("There's no chest at home."); return; }
        if (!b.walkTo(chest[0] + 0.5, chest[1], chest[2] + 0.5, 2.5)) { if (jobTicks > 3000) endJob("I can't reach the chest at home."); return; }
        BlockEntity be = Names.world(b).getBlockEntity(chest[0], chest[1], chest[2]);
        if (!(be instanceof Inventory)) { endJob("The chest at home is gone."); return; }
        Inventory c = (Inventory) be;
        Set<ItemStack> keep = keepers();
        int moved = 0;
        for (int i = 0; i < b.inv.length; i++) {
            ItemStack s = b.inv[i];
            if (s == null || keep.contains(s)) continue;
            int before = s.size;
            for (int k = 0; k < c.getSize() && s.size > 0; k++) {
                ItemStack t = c.getItem(k);
                if (t == null) { c.setItem(k, s.copy()); s.size = 0; }
                else if (t.id == s.id && t.metadata == s.metadata && t.size < t.getMaxSize()) { int m = Math.min(s.size, t.getMaxSize() - t.size); t.size += m; s.size -= m; }
            }
            moved += before - s.size;
            if (s.size <= 0) b.inv[i] = null;
        }
        c.markDirty();
        endJob(moved > 0 ? "Put " + moved + " things in the chest." : null);
    }

    /** Tools, a weapon, some food and torches stay in the pockets. */
    private Set<ItemStack> keepers() {
        Set<ItemStack> k = Collections.newSetFromMap(new IdentityHashMap<>());
        ItemStack[] best = new ItemStack[4];
        int food = 0, torches = 0;
        for (ItemStack s : b.inv) {
            if (s == null) continue;
            int kind = toolKind(s);
            if (kind >= 0 && (best[kind] == null || toolTier(s) > toolTier(best[kind]))) best[kind] = s;
            if (BotEntity.isFood(s) && food < 8) { k.add(s); food += s.size; }
            if (s.id == 50 && torches < 16) { k.add(s); torches += s.size; }
        }
        for (ItemStack s : best) if (s != null) k.add(s);
        return k;
    }

    private boolean startChore() {
        if (chores.isEmpty() || !b.hasHome) return false;
        int[] chest = homeChest();
        if (chest == null) return false;
        BlockEntity be = Names.world(b).getBlockEntity(chest[0], chest[1], chest[2]);
        if (!(be instanceof Inventory)) return false;
        Inventory c = (Inventory) be;
        for (Chore ch : chores) {
            Want w = Want.of(ch.what);
            if (w == null) continue;
            int n = 0;
            for (int k = 0; k < c.getSize(); k++) { ItemStack t = c.getItem(k); if (t != null && w.items.contains(t.id)) n += t.size; }
            if (n < ch.count) {
                want = w; wantCount = Math.min(ch.count - n, 64 * 10); deliverToHome = true;
                startJob(w.food() ? Job.HUNT : Job.GATHER);
                say("The chest is low on " + w.label + " (" + n + "/" + ch.count + "). Getting more.");
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ mining

    private void mineAt(int[] t) {
        double cx = t[0] + 0.5, cy = t[1] + 0.5, cz = t[2] + 0.5;
        double ex = b.x, ey = b.y + Names.eye(b), ez = b.z;
        double d2 = (cx - ex) * (cx - ex) + (cy - ey) * (cy - ey) + (cz - ez) * (cz - ez);
        if (BotEntity.DEBUG && b.ticks % 40 == 0) Bots.log("debug mine " + t[0] + " " + t[1] + " " + t[2] + " id " + Names.world(b).getBlock(t[0], t[1], t[2]) + " dist " + Math.round(Math.sqrt(d2) * 10) / 10.0 + " progress " + digProgress + " stand " + java.util.Arrays.toString(standNear(t)));
        long k = key(t[0], t[1], t[2]);
        if (k != reachKey) { reachKey = k; reachTicks = 0; }
        if (d2 > 4.6 * 4.6) {
            // out of reach for 10 s (a log high in the crown with leaves all around): another one will do
            if (++reachTicks > 200) { skip.add(k); target = null; reachTicks = 0; b.stopMoving(); return; }
            int[] stand = standNear(t);
            if (stand != null) b.walkTo(stand[0] + 0.5, stand[1], stand[2] + 0.5, 0.8);
            else b.walkTo(cx, t[1], cz, 2.5);
            return;
        }
        b.stopMoving();
        b.face(cx, cy, cz);
        if (dig(t[0], t[1], t[2])) target = null;
    }

    /** One tick of breaking a block; true when it's broken (its drops go on the ground to be picked up). */
    boolean dig(int x, int y, int z) {
        int id = Names.world(b).getBlock(x, y, z);
        if (id == 0) { digProgress = 0; return true; }
        Block blk = Bots.block(id);
        if (blk == null || Names.hardness(blk) < 0 || !mayBreak(x, y, z, id)) { digProgress = 0; return true; }
        equipFor(blk);
        ItemStack h = held();
        float speed = h != null ? h.getMiningSpeed(blk) : 1;
        if (b.isSubmergedIn(net.minecraft.block.material.Material.WATER)) speed /= 5;
        boolean ok = Names.handMineable(blk) || (h != null && h.canMineBlock(blk));
        float hard = Names.hardness(blk);
        digProgress += hard == 0 ? 1 : ok ? speed / hard / 30F : 1F / hard / 100F;
        if (b.ticks % 5 == 0) b.swing();
        if (digProgress < 1) return false;
        digProgress = 0;
        int meta = Names.world(b).getBlockMetadata(x, y, z);
        Names.breakEffect(Names.world(b), x, y, z, id, meta);
        if (Names.world(b).setBlock(x, y, z, 0)) {
            if (ok) blk.dropItems(Names.world(b), x, y, z, meta);
            blk.onBroken(Names.world(b), x, y, z, meta);
            if (h != null && toolKind(h) >= 0) { h.takeDamageAndBreak(1); tidyBroken(); }
        }
        return true;
    }

    private static final Set<Integer> BUILT = new HashSet<>(Arrays.asList(4, 5, 20, 22, 23, 24, 25, 26, 27, 28, 29, 33, 34, 35, 41, 42, 43, 44, 45, 47, 50, 53, 54, 55, 57, 58, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 75, 76, 77, 85, 89, 91, 92, 93, 94, 96));

    /** Natural blocks only (unless allowGrief), and never one holding a block entity (chests, machines). Cobblestone is fine underground. */
    boolean mayBreak(int x, int y, int z, int id) {
        if (Names.world(b).getBlockEntity(x, y, z) != null) return false;
        if (BotConfig.allowGrief) return true;
        if (id == 4 && (y < Names.world(b).getHeight(x, z) - 3 || want != null && want.mine.contains(4) && !nearBuilt(x, y, z))) return true;
        return id > 0 && !BUILT.contains(id) && (id <= 96 || Bots.block(id) == null || natural(id));
    }

    /** Modded blocks: ores and stone-like blocks are natural, anything else counts as built. */
    private boolean natural(int id) {
        String k = Bots.key(id);
        return k.contains("ore") || k.contains("stone") || k.contains("dirt") || k.contains("log") || k.contains("leaves") || k.contains("rock") || k.contains("rubber") || k.contains("sand") || k.contains("grass");
    }

    private boolean nearBuilt(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int id = Names.world(b).getBlock(x + dx, y + dy, z + dz);
            if (id == 5 || id == 20 || id == 35 || id == 45 || id == 54 || id == 58 || id == 61 || id == 50) return true;
        }
        return false;
    }

    /** Stuck on the way somewhere: break through (feet, head, and step down toward a lower target), or pillar up. */
    private int stuckCount;
    private final Set<Long> skip = new HashSet<>();
    private long reachKey; private int reachTicks;
    void progress() { stuckCount = 0; }

    void stuck(double gx, double gy, double gz) {
        int x = MathHelper.floor(b.x), y = MathHelper.floor(b.shape.minY), z = MathHelper.floor(b.z);
        if (++stuckCount >= 3) { // the third time here without getting anywhere: give up on this way
            stuckCount = 0;
            PlayerEntity o = Bots.player(Names.world(b), b.owner);
            if ((job == Job.NONE && mode == Mode.FOLLOW || job == Job.DELIVER) && o != null && o.onGround) { teleportTo(o); return; }
            if (target != null) { skip.add(key(target[0], target[1], target[2])); target = null; return; }
            if (job == Job.RECOVER || job == Job.COME) { endJob(job == Job.RECOVER ? "I can't reach my things from here." : null); return; }
        }
        double dx = gx - b.x, dz = gz - b.z;
        int sx = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 1 : -1) : 0, sz = sx == 0 ? (dz > 0 ? 1 : -1) : 0;
        int[][] cand = gy < y - 1 ? new int[][]{{x + sx, y + 1, z + sz}, {x + sx, y, z + sz}, {x + sx, y - 1, z + sz}} : new int[][]{{x + sx, y + 1, z + sz}, {x + sx, y, z + sz}};
        if (gy > y + 2 && Math.abs(dx) < 2 && Math.abs(dz) < 2) { pillar(x, y, z); return; }
        if (gy < y - 1 && Math.abs(dx) < 0.8 && Math.abs(dz) < 0.8) { // straight below: dig down, never into lava or water
            int below = Names.world(b).getBlock(x, y - 1, z), under = Names.world(b).getBlock(x, y - 2, z);
            if (below != 0 && !liquidNext(x, y - 1, z) && !(under >= 8 && under <= 11)) {
                for (int i = 0; i < 400 && !dig(x, y - 1, z); i++) { }
                b.setPosition(x + 0.5, b.y, z + 0.5);
            }
            return;
        }
        for (int[] c : cand) {
            int id = Names.world(b).getBlock(c[0], c[1], c[2]);
            if (id == 0 || Bots.block(id) == null || !Bots.block(id).material.blocksMovement()) continue;
            if (liquidNext(c[0], c[1], c[2])) continue;
            int above = Names.world(b).getBlock(c[0], c[1] + 1, c[2]);
            if (above == 12 || above == 13) continue; // sand or gravel would fall in
            for (int i = 0; i < 400 && !dig(c[0], c[1], c[2]); i++) { /* a stuck bot digs at full effort: done over ticks would be nicer, this keeps it simple */ }
        }
    }

    private void pillar(int x, int y, int z) {
        int block = b.count(3) > 0 ? 3 : b.count(4) > 0 ? 4 : 0;
        if (block == 0 || Names.world(b).getBlock(x, y + 2, z) != 0) return;
        b.setPosition(b.x, b.y + 1.1, b.z);
        if (Names.world(b).getBlock(x, y, z) == 0) { Names.world(b).setBlock(x, y, z, block); b.take(block, 1); }
    }

    private boolean liquidNext(int x, int y, int z) {
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) { int id = Names.world(b).getBlock(x + d[0], y + d[1], z + d[2]); if (id >= 8 && id <= 11) return true; }
        return false;
    }

    /** An air spot with a floor next to the block (or the block's top), where the bot can stand and reach it. */
    private int[] standNear(int[] t) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}, {0, 1, 0}};
        for (int[] o : d) {
            int x = t[0] + o[0], y = t[1] + o[1], z = t[2] + o[2];
            if (air(x, y, z) && air(x, y + 1, z) && solid(x, y - 1, z)) return new int[]{x, y, z};
        }
        return null;
    }

    boolean air(int x, int y, int z) { int id = Names.world(b).getBlock(x, y, z); return id == 0 || Bots.block(id) == null || !Bots.block(id).material.blocksMovement(); }
    boolean solid(int x, int y, int z) { int id = Names.world(b).getBlock(x, y, z); return id != 0 && Bots.block(id) != null && Bots.block(id).material.blocksMovement(); }

    /** The nearest block of these kinds; exposed ones (air beside them) count as much closer. */
    int[] findNear(int[] ids, int cx, int cy, int cz, int r, boolean preferExposed) {
        boolean[] want = new boolean[Math.max(256, Block.BY_ID.length)];
        for (int i : ids) if (i > 0 && i < want.length) want[i] = true;
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -r / 2; dy <= r / 2; dy++) {
            int y = cy + dy;
            if (y < 1 || y > 126) continue;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                int x = cx + dx, z = cz + dz;
                int id = Names.world(b).getBlock(x, y, z);
                if (id <= 0 || id >= want.length || !want[id]) continue;
                double d = dx * dx + dy * dy * 2.0 + dz * dz;
                if (preferExposed && !(air(x + 1, y, z) || air(x - 1, y, z) || air(x, y + 1, z) || air(x, y, z + 1) || air(x, y, z - 1))) d = d * 2 + 100;
                if (preferExposed && liquidNext(x, y, z)) continue;
                if (preferExposed && !mayBreak(x, y, z, id)) continue;
                if (!skip.isEmpty() && skip.contains(key(x, y, z))) continue;
                if (d < bestD) { bestD = d; best = new int[]{x, y, z}; }
            }
        }
        return best;
    }

    private int[] homeChest() { return b.hasHome ? findNear(new int[]{54}, b.homeX, b.homeY, b.homeZ, 6, false) : null; }

    private int[] freeSpotNear(int x, int y, int z) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {1, 0, 1}, {-1, 0, -1}};
        for (int[] o : d) if (Names.world(b).getBlock(x + o[0], y, z + o[2]) == 0 && solid(x + o[0], y - 1, z + o[2])) return new int[]{x + o[0], y, z + o[2]};
        return null;
    }

    // ------------------------------------------------------------------ tools, crafting, smelting

    ItemStack held() { return b.heldStack; }

    private static int toolKind(ItemStack s) {
        switch (s.id) {
            case 270: case 274: case 257: case 285: case 278: return 0; // pickaxes
            case 271: case 275: case 258: case 286: case 279: return 1; // axes
            case 269: case 273: case 256: case 284: case 277: return 2; // shovels
            case 268: case 272: case 267: case 283: case 276: return 3; // swords
            default: return -1;
        }
    }

    private static int toolTier(ItemStack s) {
        switch (s.id) {
            case 270: case 271: case 269: case 268: return 1;
            case 285: case 286: case 284: case 283: return 2;
            case 274: case 275: case 273: case 272: return 3;
            case 257: case 258: case 256: case 267: return 4;
            case 278: case 279: case 277: case 276: return 5;
            default: return 0;
        }
    }

    private boolean needsPick(Block blk) { return blk.material == net.minecraft.block.material.Material.STONE || blk.material == net.minecraft.block.material.Material.IRON; }

    private boolean canHarvest(Block blk) {
        for (ItemStack s : b.inv) if (s != null && s.canMineBlock(blk)) return true;
        return false;
    }

    void equipFor(Block blk) {
        ItemStack best = null;
        float bs = 1;
        for (ItemStack s : b.inv) if (s != null) { float v = s.getMiningSpeed(blk); if (v > bs || (v == bs && best != null && s.canMineBlock(blk) && !best.canMineBlock(blk))) { bs = v; best = s; } }
        if (best == null) for (ItemStack s : b.inv) if (s != null && s.canMineBlock(blk)) { best = s; break; }
        setHeld(best);
    }

    void equipWeapon() {
        ItemStack best = null;
        int bd = 1;
        for (ItemStack s : b.inv) if (s != null) { int v = s.getAttackDamage(b); if (v > bd) { bd = v; best = s; } }
        setHeld(best);
    }

    public void equipBest() { equipWeapon(); }

    private void setHeld(ItemStack s) { b.heldStack = s; b.hold(s); }

    private void tidyBroken() {
        for (int i = 0; i < b.inv.length; i++) if (b.inv[i] != null && b.inv[i].size <= 0) { if (b.inv[i] == b.heldStack) setHeld(null); b.inv[i] = null; }
    }

    /** Hand crafting without a table window: the bot just knows the recipes for its own tools. */
    boolean craft(int out, int outCount, int[] needs) {
        for (int i = 0; i < needs.length; i += 2) if (b.count(needs[i]) < needs[i + 1]) return false;
        for (int i = 0; i < needs.length; i += 2) b.take(needs[i], needs[i + 1]);
        b.add(new ItemStack(out, outCount, 0));
        return true;
    }

    /** Planks, sticks, then the best pickaxe, axe and sword it can make (wood, stone, iron, diamond). */
    void craftTools(boolean tell) {
        if (b.count(17) > 0 && b.count(5) < 12) craft(5, 4, new int[]{17, 1});
        if (b.count(17) > 0 && b.count(5) < 12) craft(5, 4, new int[]{17, 1});
        if (b.count(280) < 4 && b.count(5) >= 2) craft(280, 4, new int[]{5, 2});
        int[][] mats = {{264, 278, 279, 276}, {265, 257, 258, 267}, {4, 274, 275, 272}, {5, 270, 271, 268}};
        boolean[] got = new boolean[3];
        for (ItemStack s : b.inv) if (s != null) { int k = toolKind(s); if (k == 0) got[0] = true; if (k == 1) got[1] = true; if (k == 3) got[2] = true; }
        int bestPick = 0;
        for (ItemStack s : b.inv) if (s != null && toolKind(s) == 0) bestPick = Math.max(bestPick, toolTier(s));
        for (int[] m : mats) {
            int tier = m[0] == 264 ? 5 : m[0] == 265 ? 4 : m[0] == 4 ? 3 : 1;
            if (tier > bestPick && b.count(280) >= 2 && craft(m[1], 1, new int[]{m[0], 3, 280, 2})) { bestPick = tier; got[0] = true; }
            if (!got[1] && b.count(280) >= 2 && craft(m[2], 1, new int[]{m[0], 3, 280, 2})) got[1] = true;
            if (!got[2] && b.count(280) >= 1 && craft(m[3], 1, new int[]{m[0], 2, 280, 1})) got[2] = true;
        }
        if (b.count(263) > 0 && b.count(280) > 0 && b.count(50) < 8) craft(50, 4, new int[]{263, 1, 280, 1});
        equipWeapon();
    }

    /** Smelt ore it carries at a furnace within 16 blocks (or one it builds from 8 cobblestone), with its own fuel. */
    private boolean smelt() {
        int[] f = findNear(new int[]{61, 62}, MathHelper.floor(b.x), MathHelper.floor(b.y), MathHelper.floor(b.z), 16, false);
        if (f == null && b.count(4) >= 8) {
            int[] spot = freeSpotNear(MathHelper.floor(b.x), MathHelper.floor(b.shape.minY), MathHelper.floor(b.z));
            if (spot != null) { b.take(4, 8); Names.world(b).setBlock(spot[0], spot[1], spot[2], 61); f = spot; say("Built a furnace."); }
        }
        if (f == null) return false;
        int fuel = b.count(263) * 8 + b.count(5) * 3 / 2 + b.count(17) * 3 / 2;
        if (fuel <= 0) return false;
        if (!b.walkTo(f[0] + 0.5, f[1], f[2] + 0.5, 2.5)) return true;
        if (jobTicks % 40 != 0) return true; // two seconds an item
        if (b.take(want.smeltFrom, 1) == 1) {
            if (b.count(263) > 0 && rnd.nextInt(8) == 0) b.take(263, 1);
            else if (b.count(263) == 0 && rnd.nextInt(3) < 2) { if (b.take(5, 1) == 0) b.take(17, 1); }
            b.add(new ItemStack(want.smeltTo, 1, 0));
        }
        return true;
    }

    // ------------------------------------------------------------------ fainting

    private Job resumeJob = Job.NONE;

    void fainted(int x, int y, int z, boolean hadThings) {
        enemy = null; target = null;
        // an errand cut short (gathering, hunting) carries on once its things are back
        if (job == Job.GATHER || job == Job.HUNT || job == Job.DELIVER || job == Job.DEPOSIT) resumeJob = job;
        boolean wet = false;
        for (int dy = -1; dy <= 1; dy++) { int id = Names.world(b).getBlock(x, y + dy, z); if (id >= 8 && id <= 11) wet = true; }
        if (wet) { hadThings = false; resumeJob = Job.NONE; job = Job.NONE; say("Ouch, I got knocked out in the water. My things are gone, I'm not going back in."); return; }
        if (hadThings) { tx = x; ty = y; tz = z; startJob(Job.RECOVER); say("Ouch, I got knocked out. Going back for my things at " + x + ", " + y + ", " + z + "."); }
        else { job = Job.NONE; say("Ouch, I got knocked out."); }
    }

    // ------------------------------------------------------------------ talking

    public void say(String text) {
        if (text == null || text.isEmpty()) return;
        lastReport = text;
        Bots.broadcast(Names.world(b), "<" + b.botName() + "> " + text);
    }

    /** Acknowledge an order: the model words it if it's running, else the plain line. */
    private void ack(String who, String asked, String plain) {
        if (!Model.ready()) { say(plain); return; }
        Model.ask(persona(), who + " said to you: \"" + asked + "\". You will do this: " + plain + " Reply in one short, friendly sentence, no quotes.", this::say, () -> say(plain));
    }

    /** Chat that isn't an order: the model answers (and may turn it into an order), or a plain reply. */
    public void chat(PlayerEntity from, String text) {
        String who = from != null ? from.name : "someone";
        if (!Model.ready()) { say(plainReply(text)); return; }
        Model.ask(persona() + " If the player wants you to do something, start your reply with ORDER: and the request in plain words"
                        + " (like ORDER: bring me 16 coal), then a newline and a short reply. Things you can do: follow, stay, come here, guard,"
                        + " gather or mine any block, hunt for food, set up a base, go home, put things away, keep the chest stocked, give items, explore.",
                who + " says: \"" + text + "\"\nYou carry: " + inventoryLine() + "\nYou're doing: " + statusLine(),
                a -> {
                    String reply = a;
                    if (a.toUpperCase(Locale.ROOT).startsWith("ORDER:")) {
                        String[] parts = a.split("(?<=\\S)\\s{2,}|\\. ", 2);
                        String req = parts[0].substring(6).trim();
                        Orders.Order o = Orders.parse(req);
                        if (o.kind != Orders.Kind.NONE && (from == null || from.name.equals(b.owner) || BotConfig.anyoneCommands)) { order(from, o, req); return; }
                        reply = parts.length > 1 ? parts[1] : plainReply(text);
                    }
                    say(reply);
                }, () -> say(plainReply(text)));
    }

    private String persona() {
        return "You are " + b.botName() + ", a friendly player in Minecraft Beta 1.7.3 (2011), helping " + b.owner + ". Talk like a casual"
                + " player in chat: short, plain, no emoji, no quotes, never mention being an AI or a model. One sentence, under 15 words.";
    }

    private String plainReply(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("hi") || t.contains("hello") || t.contains("hey")) return "Hey!";
        if (t.contains("thank")) return "Anytime.";
        if (t.contains("?")) return "Not sure. Say \"/bot help\" for what I can do.";
        return "Okay.";
    }

    public String statusLine() {
        String s;
        switch (job) {
            case GATHER: s = "getting " + wantCount + " " + want.label + " (" + b.count(want.items) + " so far)"; break;
            case HUNT: s = "hunting for " + want.label; break;
            case DELIVER: s = "bringing you " + want.label; break;
            case DEPOSIT: s = "putting things away"; break;
            case GO_HOME: s = "heading home"; break;
            case EXPLORE: s = "exploring"; break;
            case RECOVER: s = "going back for my things"; break;
            case COME: s = "coming over"; break;
            default: s = mode == Mode.FOLLOW ? "following you" : mode == Mode.STAY ? "waiting here" : mode == Mode.GUARD ? "on guard" : "keeping house";
        }
        return "I'm " + s + ", health " + b.health + "/20" + (chores.isEmpty() ? "" : ", " + chores.size() + " chore" + (chores.size() > 1 ? "s" : "")) + ".";
    }

    String inventoryLine() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : b.inv) if (s != null) m.merge(Bots.name(s.id), s.size, Integer::sum);
        if (m.isEmpty()) return "nothing";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) { if (sb.length() > 70) { sb.append(", ..."); break; } if (sb.length() > 0) sb.append(", "); sb.append(e.getValue()).append(' ').append(e.getKey()); }
        return sb.toString();
    }

    private String toolsLine() {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : b.inv) if (s != null && toolKind(s) >= 0) { if (sb.length() > 0) sb.append(", "); sb.append(Bots.name(s.id)); }
        return sb.length() == 0 ? "I couldn't make any tools. I need wood (and cobblestone or iron for better ones)." : "I have: " + sb + ".";
    }

    private String seen() {
        Map<String, Integer> found = new LinkedHashMap<>();
        int x0 = MathHelper.floor(b.x), y0 = MathHelper.floor(b.y), z0 = MathHelper.floor(b.z);
        for (int dx = -12; dx <= 12; dx++) for (int dy = -10; dy <= 6; dy++) for (int dz = -12; dz <= 12; dz++) {
            int id = Names.world(b).getBlock(x0 + dx, y0 + dy, z0 + dz);
            if (id == 14 || id == 15 || id == 16 || id == 56 || id == 73 || id == 21 || id == 54 || id == 52 || id == 49 || (id > 96 && id < 256 && Bots.key(id).contains("ore")))
                found.merge(Bots.name(id), 1, Integer::sum);
        }
        return "Back from exploring" + (found.isEmpty() ? ", nothing special nearby." : ". Spotted: " + String.join(", ", found.keySet()) + ".");
    }

    // ------------------------------------------------------------------ saving

    void write(NbtCompound n) {
        n.putString("Mode", mode.name());
        NbtList l = new NbtList();
        for (Chore c : chores) { NbtCompound t = new NbtCompound(); t.putString("What", c.what); t.putInt("Count", c.count); l.addElement(t); }
        n.put("Chores", l);
    }

    void read(NbtCompound n) {
        try { mode = Mode.valueOf(n.getString("Mode")); } catch (Exception e) { mode = Mode.FOLLOW; }
        chores.clear();
        NbtList l = n.getList("Chores");
        for (int i = 0; l != null && i < l.size(); i++) { NbtCompound t = (NbtCompound) l.get(i); chores.add(new Chore(t.getString("What"), t.getInt("Count"))); }
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF); }

    private static int[] toArray(Set<Integer> s) { int[] a = new int[s.size()]; int i = 0; for (int v : s) a[i++] = v; return a; }
}
