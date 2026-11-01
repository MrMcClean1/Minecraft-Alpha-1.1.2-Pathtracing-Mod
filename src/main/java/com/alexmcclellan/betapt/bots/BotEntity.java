package com.alexmcclellan.betapt.bots;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.item.FoodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * A bot's body: a player-shaped mob with its own 36-slot inventory, an owner, a home, and a {@link Brain} that
 * decides what it does. It never dies for good: at zero health it drops what it carries, wakes up at home (or by its
 * owner) and goes back for its things. Name and held item reach other players through the data tracker.
 */
public class BotEntity extends MobEntity {
    public final ItemStack[] inv = new ItemStack[36];
    public String owner = "";
    public int homeX, homeY, homeZ;
    public boolean hasHome;
    public final Brain brain = new Brain(this);
    private Path path;
    private int pathX = Integer.MIN_VALUE, pathY, pathZ, pathAge, stuckTicks;
    private double lastX, lastZ, bestDist = Double.MAX_VALUE;
    private int lastGoalX = Integer.MIN_VALUE, lastGoalZ;
    public int swingTicks, wallTicks, errors;
    /** The stack in hand (one of inv). */
    public ItemStack heldStack;

    public BotEntity(World world) {
        super(world);
        this.texture = "/char.png";
        this.walkSpeed = 0.7F;
        this.health = 20;
        this.setSize(0.6F, 1.8F);
        this.eyeHeight = 1.62F;
        this.stepHeight = 0.5F;
    }

    private String name = "Bot";
    public String botName() { return name; }
    public void setBotName(String n) { name = n; }
    public void hold(ItemStack st) { }

    @Override public String getTexture() { return "/char.png"; }
    @Override protected String getHurtSound() { return "random.hurt"; }
    @Override protected String getDeathSound() { return "random.hurt"; }
    @Override protected String getAmbientSound() { return null; }

    // ------------------------------------------------------------------ ticking

    @Override
    protected void aiTick() {
        Bots.tickShared(this.world);
        if (this.world.isMultiplayer) return;
        // stuck inside a block (sand fell, a door closed): climb out like a player would dig out
        if (isInWall()) { if (++wallTicks > 4) { setPosition(x, world.getHeight(MathHelper.floor(x), MathHelper.floor(z)) + 0.1, z); wallTicks = 0; } } else wallTicks = 0;
        this.sidewaysSpeed = 0;
        this.forwardSpeed = 0;
        this.jumping = false;
        try { brain.tick(); } catch (Throwable t) { if (errors++ < 3) { StringBuilder sb = new StringBuilder(); for (StackTraceElement e : t.getStackTrace()) { if (sb.length() > 400) break; sb.append(" at ").append(e); } Bots.log("brain error for " + botName() + ": " + t + sb); } }
        if (this.isSubmergedIn(net.minecraft.block.material.Material.WATER) || this.isInLava()) this.jumping = true;
        if (DEBUG && ticks % 40 == 0) Bots.log(String.format("debug %s at %.1f %.1f %.1f fwd %.2f yaw %.0f ground %s mode %s job %s path %s jobTicks %d enemy %s owner %s",
                botName(), x, y, z, forwardSpeed, yaw, onGround, brain.mode, brain.job, path != null, brain.jobTicks, brain.enemy == null ? "-" : brain.enemy.getClass().getSimpleName() + " d" + Math.round(distanceTo(brain.enemy)),
                Bots.player(world, owner) == null ? "-" : String.format("%.1f %.1f %.1f", Bots.player(world, owner).x, Bots.player(world, owner).y, Bots.player(world, owner).z)));
    }
    static final boolean DEBUG = Boolean.getBoolean("classicbots.debug");

    @Override
    public void tick() {
        if (DEBUG && ticks % 40 == 0) Bots.log("tick " + botName() + " interp " + interpolateOnly + " health " + health + " dead " + dead + " remote " + world.isMultiplayer);
        super.tick();
        if (swingTicks > 0) { swingTicks--; this.attackAnimationProgress = (8 - swingTicks) / 8.0F; } else this.attackAnimationProgress = 0;
    }

    public boolean see(Entity e) { return canSee(e); }

    public void swing() { if (swingTicks <= 0) swingTicks = 8; }

    // ------------------------------------------------------------------ movement

    /** Walk toward a spot; true once within reach. Uses Beta's own path finder, straight-line steps where it has none. */
    public boolean walkTo(double tx, double ty, double tz, double reach) {
        double dx = tx - x, dz = tz - z, dy = ty - y;
        if (dx * dx + dz * dz < reach * reach && Math.abs(dy) < 2.5) { path = null; return true; }
        int bx = MathHelper.floor(tx), by = MathHelper.floor(ty), bz = MathHelper.floor(tz);
        if (path == null || pathAge++ > 40 || bx != pathX || by != pathY || bz != pathZ) {
            try { path = world.findPath(this, bx, by, bz, 40.0F); } catch (RuntimeException e) { path = null; } // a target past the loaded area
            pathX = bx; pathY = by; pathZ = bz; pathAge = 0;
        }
        Vec3d node = null;
        if (path != null) {
            node = path.getPos(this);
            while (node != null && node.squaredDistanceTo(x, node.y, z) < 0.6 * 0.6 * 4) {
                path.advance();
                if (path.isDone()) { node = null; path = null; } else node = path.getPos(this);
            }
        }
        double gx = node != null ? node.x : tx, gz = node != null ? node.z : tz, gy = node != null ? node.y : ty;
        double h2 = dx * dx + dz * dz;
        if (h2 < 0.8 * 0.8) {
            // right above or below the spot: no amount of walking gets there, so dig down or climb up
            forwardSpeed = 0;
            if (Math.abs(dy) >= 2.5) brain.stuck(tx, ty, tz);
            return false;
        }
        steer(gx, gy, gz);
        // stuck: not getting closer for 3 seconds (walking in circles counts too)
        double d = Math.sqrt(h2) + Math.abs(dy);
        if (d < bestDist - 0.5) { bestDist = d; stuckTicks = 0; brain.progress(); } else stuckTicks++;
        if (bx != lastGoalX || bz != lastGoalZ) { lastGoalX = bx; lastGoalZ = bz; bestDist = d; stuckTicks = 0; }
        if (stuckTicks > 60) { stuckTicks = 0; bestDist = d; path = null; brain.stuck(tx, ty, tz); }
        return false;
    }

    private void steer(double gx, double gy, double gz) {
        float want = (float) (Math.atan2(gz - z, gx - x) * 180.0 / Math.PI) - 90.0F;
        float turn = want - yaw;
        while (turn < -180) turn += 360;
        while (turn >= 180) turn -= 360;
        yaw += Math.max(-40, Math.min(40, turn));
        forwardSpeed = walkSpeed;
        if (gy > shape.minY + 0.5 || collidingHorizontally) jumping = true;
    }

    public void stopMoving() { path = null; forwardSpeed = 0; }

    public void face(double tx, double ty, double tz) {
        double dx = tx - x, dz = tz - z, dy = ty - (y + getEyeHeight());
        yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
        bodyYaw = yaw;
    }

    // ------------------------------------------------------------------ inventory

    public int count(int id) {
        int n = 0;
        for (ItemStack s : inv) if (s != null && s.id == id) n += s.size;
        return n;
    }

    public int count(java.util.Set<Integer> ids) {
        int n = 0;
        for (ItemStack s : inv) if (s != null && ids.contains(s.id)) n += s.size;
        return n;
    }

    /** Put a stack in; what doesn't fit is left in s (count > 0). */
    public boolean add(ItemStack s) {
        if (s == null || s.size <= 0) return true;
        for (ItemStack t : inv) {
            if (t != null && t.id == s.id && t.metadata == s.metadata && t.size < t.getMaxSize()) {
                int m = Math.min(s.size, t.getMaxSize() - t.size);
                t.size += m; s.size -= m;
                if (s.size <= 0) return true;
            }
        }
        for (int i = 0; i < inv.length; i++) if (inv[i] == null) { inv[i] = s.copy(); s.size = 0; return true; }
        return false;
    }

    /** Take up to n of an item out; returns how many. */
    public int take(int id, int n) {
        int got = 0;
        for (int i = inv.length - 1; i >= 0 && got < n; i--) {
            ItemStack s = inv[i];
            if (s == null || s.id != id) continue;
            int m = Math.min(n - got, s.size);
            s.size -= m; got += m;
            if (s.size <= 0) inv[i] = null;
        }
        return got;
    }

    public boolean full() { for (ItemStack s : inv) if (s == null) return false; return true; }

    public int freeSlots() { int n = 0; for (ItemStack s : inv) if (s == null) n++; return n; }

    public void dropAll() {
        for (int i = 0; i < inv.length; i++) if (inv[i] != null) { drop(inv[i]); inv[i] = null; }
        hold(null);
    }

    /** Hand items to a player: into their inventory if close, else thrown to them. */
    /** Alpha's dropItem only takes an item id. */
    private ItemEntity drop(ItemStack st) {
        ItemEntity e = new ItemEntity(world, x, y + 1.0, z, st);
        e.pickUpDelay = 10;
        world.addEntity(e);
        return e;
    }

    private final java.util.Map<Entity, Boolean> tossed = new java.util.WeakHashMap<>();

    public int giveTo(PlayerEntity p, int id, int n) {
        int given = 0;
        while (given < n) {
            int m = take(id, Math.min(64, n - given));
            if (m <= 0) break;
            ItemStack s = new ItemStack(id, m, 0);
            for (ItemStack t : inv) if (t != null && t.id == id) { s = new ItemStack(id, m, t.metadata); break; }
            // close by (handing it over, not throwing): straight into their inventory, whatever doesn't fit is thrown
            if (distanceTo(p) < 6.5 && p.inventory.addItem(s)) { given += m; continue; } // true: all of it is in (an empty slot keeps the stack as is)
            ItemEntity e = drop(s);
            if (e != null) {
                tossed.put(e, Boolean.TRUE);
                double dx = p.x - x, dz = p.z - z, d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                double v = Math.min(0.9, d / 11.0); // lands about at their feet (air drag, ~13 ticks up and down)
                e.velocityX = dx / d * v; e.velocityZ = dz / d * v; e.velocityY = 0.25; e.pickUpDelay = 10;
            }
            given += m;
        }
        return given;
    }

    /** Pick up items lying within reach (like a player walking over them). */
    public void pickUp() {
        List l = world.getEntitiesOfType(ItemEntity.class, shape.grown(1.5, 0.5, 1.5));
        for (Object o : l) {
            ItemEntity e = (ItemEntity) o;
            if (e.removed || e.item == null) continue;
            if (e.pickUpDelay > 0 && e.ticks < 40 && brain.thrownByOwner(e)) continue;
            if (tossed.containsKey(e) && e.ticks < 200) continue; // what it just threw to someone
            add(e.item);
            if (e.item.size <= 0) { e.remove(); world.playSound(this, "random.pop", 0.2F, ((random.nextFloat() - random.nextFloat()) * 0.7F + 1.0F) * 2.0F); }
        }
    }

    // ------------------------------------------------------------------ fighting and fainting

    @Override
    public boolean takeDamage(Entity source, int amount) {
        if (world.isMultiplayer) return false;
        if (source instanceof PlayerEntity && ((PlayerEntity) source).name.equals(owner) && !brain.ownerCanHurt()) return false;
        if (source instanceof BotEntity && ((BotEntity) source).owner.equals(owner)) return false;
        if (health - amount <= 0 && health > 0) { Bots.log(botName() + " knocked out by " + (source == null ? (isInWall() ? "suffocation" : breath <= 0 ? "drowning" : onFireTimer > 0 ? "fire or lava" : "a fall or the world") : source.getClass().getSimpleName())); faint(); return true; }
        boolean r = super.takeDamage(source, amount);
        if (r && source instanceof MobEntity && source != this) brain.attackedBy((MobEntity) source);
        return r;
    }

    private void faint() {
        int fx = MathHelper.floor(x), fy = MathHelper.floor(y), fz = MathHelper.floor(z);
        boolean had = false;
        for (ItemStack s : inv) if (s != null) had = true;
        dropAll();
        health = 20;
        onFireTimer = 0;
        PlayerEntity o = Bots.player(world, owner);
        if (hasHome) setPositionAndAngles(homeX + 0.5, homeY + 0.1, homeZ + 0.5, yaw, 0);
        else if (o != null) setPositionAndAngles(o.x, o.y, o.z, yaw, 0);
        brain.fainted(fx, fy, fz, had);
    }

    // ------------------------------------------------------------------ owner interaction

    @Override
    public boolean interact(PlayerEntity p) {
        if (world.isMultiplayer) return true;
        ItemStack held = p.inventory.getSelectedItem();
        if (held != null && (p.name.equals(owner) || BotConfig.anyoneCommands)) {
            ItemStack copy = held.copy();
            if (add(copy) || copy.size < held.size) {
                held.size = copy.size;
                if (held.size <= 0) p.inventory.items[p.inventory.selectedSlot] = null;
                brain.say("Thanks for the " + Bots.name(held.id) + ".");
                brain.equipBest();
            } else brain.say("My pockets are full.");
            return true;
        }
        brain.say(brain.statusLine());
        return true;
    }

    // ------------------------------------------------------------------ saving

    @Override
    public void writeCustomNbt(NbtCompound nbt) {
        super.writeCustomNbt(nbt);
        nbt.putString("BotName", botName());
        nbt.putString("Owner", owner);
        nbt.putBoolean("HasHome", hasHome);
        nbt.putInt("HomeX", homeX); nbt.putInt("HomeY", homeY); nbt.putInt("HomeZ", homeZ);
        NbtList l = new NbtList();
        for (int i = 0; i < inv.length; i++) if (inv[i] != null) {
            NbtCompound c = new NbtCompound();
            c.putByte("Slot", (byte) i);
            inv[i].writeNbt(c);
            l.addElement(c);
        }
        nbt.put("Inv", l);
        brain.write(nbt);
    }

    @Override
    public void readCustomNbt(NbtCompound nbt) {
        super.readCustomNbt(nbt);
        setBotName(nbt.getString("BotName"));
        owner = nbt.getString("Owner");
        hasHome = nbt.getBoolean("HasHome");
        homeX = nbt.getInt("HomeX"); homeY = nbt.getInt("HomeY"); homeZ = nbt.getInt("HomeZ");
        NbtList l = nbt.getList("Inv");
        for (int i = 0; l != null && i < l.size(); i++) {
            NbtCompound c = (NbtCompound) l.get(i);
            int s = c.getByte("Slot") & 255;
            if (s < inv.length) inv[s] = new ItemStack(c);
        }
        brain.read(nbt);
        brain.equipBest();
    }

    public static boolean isFood(ItemStack s) { return s != null && s.id >= 256 && Item.BY_ID[s.id] instanceof FoodItem; }

    public List<ItemStack> stacks() { List<ItemStack> l = new ArrayList<>(); for (ItemStack s : inv) if (s != null) l.add(s); return l; }
}
