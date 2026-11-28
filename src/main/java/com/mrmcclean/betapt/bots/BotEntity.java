package com.mrmcclean.betapt.bots;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.item.FoodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private int stuckTicks, noMoveTicks, backOffTicks, consecutiveStucks;
    private float backStrafe;
    private double lastX, lastZ, bestDist = Double.MAX_VALUE;
    private double progX, progY, progZ, stuckOrigX, stuckOrigZ;
    private int lastGoalX = Integer.MIN_VALUE, lastGoalZ;
    private final java.util.Map<Long, Integer> avoidCells = new java.util.HashMap<>();
    public int swingTicks, wallTicks, errors;
    /** The stack in hand (one of inv). */
    public ItemStack heldStack;

    public BotEntity(World world) {
        super(world);
        this.texture = "/char.png";
        this.walkSpeed = 0.7F;
        this.health = 20;
        this.setSize(0.6F, 1.8F);
        // no eyeHeight here: in Alpha that field lifts y above the feet (players only), and a mob drawn at y floated 1.62 up
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
        if (isInWall()) { if (++wallTicks > 4) { climbOut(); wallTicks = 0; } } else wallTicks = 0;
        this.sidewaysSpeed = 0;
        this.forwardSpeed = 0;
        this.jumping = false;
        try { brain.tick(); } catch (Throwable t) { if (errors++ < 3) { StringBuilder sb = new StringBuilder(); for (StackTraceElement e : t.getStackTrace()) { if (sb.length() > 400) break; sb.append(" at ").append(e); } Bots.log("brain error for " + botName() + ": " + t + sb); } }
        walkedLast = forwardSpeed > 0;
        if (this.isSubmergedIn(net.minecraft.block.material.Material.WATER) || this.isInLava()) this.jumping = true;
        int fxw = MathHelper.floor(x), fyw = MathHelper.floor(shape.minY + 0.01), fzw = MathHelper.floor(z);
        if ((this.isSubmergedIn(net.minecraft.block.material.Material.WATER) || Nav.water(world.getBlock(fxw, fyw, fzw))) && this.collidingHorizontally && this.velocityY < 0.25) {
            this.jumping = true;
            this.velocityY = 0.30; // climb cleanly out of water onto 1-block banks
        }
        watchMovement();
        if (DEBUG && ticks % 40 == 0) Bots.log(String.format("debug %s at %.1f %.1f %.1f fwd %.2f yaw %.0f ground %s mode %s job %s path %s jobTicks %d enemy %s owner %s",
                botName(), x, y, z, forwardSpeed, yaw, onGround, brain.mode, brain.job, route != null, brain.jobTicks, brain.enemy == null ? "-" : brain.enemy.getClass().getSimpleName() + " d" + Math.round(distanceTo(brain.enemy)),
                Bots.player(world, owner) == null ? "-" : String.format("%.1f %.1f %.1f", Bots.player(world, owner).x, Bots.player(world, owner).y, Bots.player(world, owner).z)));
    }
    static final boolean DEBUG = Boolean.getBoolean("classicbots.debug") || debugFile(); // or the file .minecraft/classicbots-test/debug

    private int lastLift = -1000;
    /** Out of a block it is stuck in: up to the nearest spot within 4 blocks with room for a body, else the top. */
    private void climbOut() {
        int fx = MathHelper.floor(x), fy = MathHelper.floor(shape.minY + 0.01), fz = MathHelper.floor(z);
        // sand or gravel over its head (it would only fall in again), or lifted twice in 10 s: up to the top, as before
        boolean falling = false; for (int up = 1; up <= 6; up++) { int id = world.getBlock(fx, fy + up, fz); if (id == 12 || id == 13) falling = true; }
        boolean again = ticks - lastLift < 200; lastLift = ticks;
        Nav n = planner();
        if (!falling && !again) for (int up = 0; up <= 4; up++) if (n.passable(fx, fy + up, fz) && n.passable(fx, fy + up + 1, fz)) { setPositionAndAngles(fx + 0.5, fy + up + 0.01, fz + 0.5, yaw, pitch); Nav.lifts++; return; }
        setPositionAndAngles(x, world.getHeight(fx, fz) + 0.1, z, yaw, pitch);
        Nav.lifts++;
    }

    // odd movement, counted for the tests (and logged with debug on): turning on the spot, or a long time in the air
    private float seenYaw; private double turned, spunX, spunZ; private int airTicks;
    private void watchMovement() {
        float d = yaw - seenYaw;
        while (d < -180) d += 360;
        while (d >= 180) d -= 360;
        seenYaw = yaw; turned += Math.abs(d);
        if (ticks % 40 == 0) {
            double moved = Math.sqrt((x - spunX) * (x - spunX) + (z - spunZ) * (z - spunZ));
            if (turned > 540 && moved < 2 && walking()) { Nav.spins++; if (DEBUG) Bots.log(String.format("debug odd move %s: spinning, turned %.0f degrees in 2 s and moved %.1f, at %.1f %.1f %.1f", botName(), turned, moved, x, y, z)); }
            turned = 0; spunX = x; spunZ = z;
        }
        int fx = MathHelper.floor(x), fy = MathHelper.floor(shape.minY + 0.01), fz = MathHelper.floor(z);
        int at = world.getBlock(fx, fy, fz);
        airTicks = onGround || Nav.water(at) || Nav.lava(at) || at == 65 ? 0 : airTicks + 1;
        if (airTicks == 30) {
            int drop = 0; while (drop < 64 && fy - drop > 0 && world.getBlock(fx, fy - drop - 1, fz) == 0) drop++;
            Nav.flights++;
            if (DEBUG) Bots.log(String.format("debug odd move %s: 1.5 s in the air, %d above the ground, at %.1f %.1f %.1f", botName(), drop, x, y, z));
        }
    }

    private static boolean debugFile() {
        try { return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("classicbots-test").resolve("debug").toFile().isFile(); }
        catch (Throwable t) { return false; }
    }

    @Override
    public void tick() {
        if (DEBUG && ticks % 40 == 0) Bots.log("tick " + botName() + " interp " + interpolateOnly + " health " + health + " dead " + dead + " remote " + world.isMultiplayer);
        super.tick();
        if (swingTicks > 0) { swingTicks--; this.attackAnimationProgress = (8 - swingTicks) / 8.0F; } else this.attackAnimationProgress = 0;
    }

    public boolean see(Entity e) { return canSee(e); }

    public void swing() { if (swingTicks <= 0) swingTicks = 8; }

    // ------------------------------------------------------------------ movement

    // the bots' own path finder (Nav): planned in slices over a few ticks, kept while it still works
    private Nav search;
    private Nav.Path route;
    private Set<Long> routeKeys;
    private int routeTx = Integer.MIN_VALUE, routeTy, routeTz, routeAge;
    private int[] lastBad; // the route point the last repair went round
    private int spliceAt = -1; // >= 0: the search running repairs the route up to this point
    private boolean viaWaypoint; // the search running goes to a region route's waypoint, not the goal

    /** Adds a cell to the short-term avoidance set (15 seconds) so A* routes around stuck spots. */
    void avoidCell(int ax, int ay, int az) {
        avoidCells.put(Nav.key(ax, ay, az), ticks + 300);
        if (avoidCells.size() > 64) avoidCells.values().removeIf(exp -> exp <= ticks);
    }

    /** Immediate recovery when wedged on terrain, fences, or corners: nudges to nearest dry passable neighbor within 2..4 blocks. */
    public boolean unstickNow() {
        route = null; search = null; spliceAt = -1; routeKeys = null;
        stuckTicks = 0; noMoveTicks = 0; backOffTicks = 0; consecutiveStucks = 0;
        int fx = MathHelper.floor(x), fy = MathHelper.floor(shape.minY + 0.01), fz = MathHelper.floor(z);
        brain.openNearbyDoor();
        Nav n = planner();
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 1; r <= 4; r++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int px = fx + dx, py = fy + dy, pz = fz + dz;
                    if (py < 2 || py > 125) continue;
                    if (!n.passable(px, py, pz) || !n.passable(px, py + 1, pz) || !n.floor(px, py - 1, pz)) continue;
                    if (Nav.water(world.getBlock(px, py, pz)) || Nav.dangerous(world.getBlock(px, py - 1, pz))) continue;
                    double sc = dx * dx + dz * dz + Math.abs(dy) * 1.5 - (py >= fy ? 1.0 : 0.0);
                    if (sc < bestScore) { bestScore = sc; best = new int[]{px, py, pz}; }
                }
            }
            if (best != null) break;
        }
        if (best != null) {
            setPositionAndAngles(best[0] + 0.5, best[1] + 0.01, best[2] + 0.5, yaw, pitch);
            progX = x; progY = y; progZ = z;
            Nav.lifts++;
            return true;
        }
        return false;
    }

    /** Walk toward a spot; true once within reach. Plans with Nav (dig through, pillar, bridge, doors, ladders, water). */
    public boolean walkTo(double tx, double ty, double tz, double reach) {
        double dx = tx - x, dz = tz - z, dy = ty - y;
        // there: near enough across, and level with it (by the eyes, for a player or mob, or by the feet, for a block)
        if (dx * dx + dz * dz < reach * reach && (Math.abs(dy) < 2.5 || Math.abs(ty - shape.minY) < 1.5)) { route = null; search = null; backOffTicks = 0; return true; }
        if (backOffTicks > 0) {
            backOffTicks--;
            forwardSpeed = -walkSpeed * 0.5F;
            sidewaysSpeed = backStrafe;
            if (collidingHorizontally && onGround) jumping = true;
            return false;
        }
        int bx = MathHelper.floor(tx), by = MathHelper.floor(ty), bz = MathHelper.floor(tz);
        int fx = MathHelper.floor(x), fy = MathHelper.floor(shape.minY + 0.01), fz = MathHelper.floor(z);
        boolean moved = Math.abs(bx - routeTx) + Math.abs(by - routeTy) + Math.abs(bz - routeTz) > 2;
        routeAge++;
        avoidCells.values().removeIf(exp -> exp <= ticks);
        Set<Long> avoidSnap = avoidCells.isEmpty() ? null : new HashSet<>(avoidCells.keySet());
        // plan again when the goal moved, the route ran out or something on it changed (not on a timer)
        int bad = -1;
        if (search == null && (route == null || moved || route.done() || routeAge % 20 == 0 && (bad = planner().firstBad(route)) >= 0)) {
            List<double[]> mobs = new ArrayList<>();
            for (Object o : world.getEntitiesOfType(MobEntity.class, shape.grown(32, 16, 32)))
                if (o instanceof net.minecraft.entity.mob.monster.Monster && !((MobEntity) o).removed) { Entity e = (Entity) o; mobs.add(new double[]{e.x, e.y, e.z}); }
            int blocks = count(3) + count(4);
            spliceAt = -1; viaWaypoint = false;
            int sy = fy; // mid-jump: plan from the ground under it (planned from the air, a step up was really two)
            if (!onGround) { Nav n0 = planner(); while (sy > 1 && fy - sy < 3 && n0.passable(fx, sy - 1, fz) && !Nav.water(world.getBlock(fx, sy - 1, fz)) && !Nav.water(world.getBlock(fx, sy, fz)) && !n0.ladder(fx, sy, fz)) sy--; }
            int[] badAt = bad >= 0 && route != null ? route.points.get(bad) : null;
            if (badAt != null && DEBUG) Bots.log("debug path " + botName() + ": route point " + badAt[0] + " " + badAt[1] + " " + badAt[2] + " (" + Nav.Move.values()[badAt[3]] + ") no longer works: blocks " + world.getBlock(badAt[0], badAt[1] - 1, badAt[2]) + " " + world.getBlock(badAt[0], badAt[1], badAt[2]) + " " + world.getBlock(badAt[0], badAt[1] + 1, badAt[2]));
            boolean again = badAt != null && lastBad != null && badAt[0] == lastBad[0] && badAt[1] == lastBad[1] && badAt[2] == lastBad[2];
            lastBad = badAt;
            Set<Long> fav = moved || avoidSnap != null ? null : routeKeys;
            if (bad >= 0 && !moved && route != null && !route.done() && !again) { // the same point twice: plan afresh instead
                // a block on the route changed: plan only round it, to a few points past it, and keep the rest
                spliceAt = Math.min(route.points.size() - 1, bad + 3);
                int[] r = route.points.get(spliceAt);
                search = new Nav(world, fx, sy, fz, r[0], r[1], r[2], 0.8, brain::digTicks, blocks, 1500, fav, mobs, avoidSnap);
            } else {
                Nav full = new Nav(world, fx, sy, fz, bx, by, bz, Math.max(0.8, reach - 0.3), brain::digTicks, blocks, 6000, fav, mobs, avoidSnap);
                Nav.Path hit = avoidSnap != null ? null : full.cached(fx, sy, fz);
                int[] way = hit == null ? full.waypoint(fx, sy, fz) : null;
                if (hit != null) {
                    routeKeys = new HashSet<>();
                    for (int[] q : hit.points) routeKeys.add(Nav.key(q[0], q[1], q[2]));
                    route = full.shorten(hit); routeAge = 0;
                    if (DEBUG) Bots.log("debug path " + botName() + " to " + bx + " " + by + " " + bz + ": " + hit.points.size() + " steps from the path cache");
                } else if (way != null) {
                    viaWaypoint = true;
                    search = new Nav(world, fx, sy, fz, way[0], way[1], way[2], 2.5, brain::digTicks, blocks, 6000, fav, mobs, avoidSnap);
                } else search = full;
            }
            routeTx = bx; routeTy = by; routeTz = bz;
        }
        if (search != null && search.step(500)) {
            Nav.Path p = search.result();
            if (spliceAt >= 0) {
                if (p != null && p.complete && route != null && spliceAt < route.points.size()) {
                    Nav.repairs++;
                    if (routeKeys == null) routeKeys = new HashSet<>();
                    for (int[] q : p.points) routeKeys.add(Nav.key(q[0], q[1], q[2]));
                    route = Nav.join(search.shorten(p), new ArrayList<>(route.points.subList(spliceAt + 1, route.points.size())));
                } else route = null; // no way round close by: a whole new plan next tick
            } else if (p != null && p.complete && p.points.isEmpty() && !viaWaypoint) {
                route = null; search = null; spliceAt = -1; return true; // the planner says it is there already
            } else if (p != null) {
                routeKeys = new HashSet<>();
                for (int[] q : p.points) routeKeys.add(Nav.key(q[0], q[1], q[2]));
                route = search.shorten(p);
                if (!viaWaypoint) Nav.remember(world, bx, by, bz, p);
            } else route = null;
            routeAge = 0;
            search = null;
            if (DEBUG) Bots.log("debug path " + botName() + " to " + bx + " " + by + " " + bz + (spliceAt >= 0 ? " (repair)" : viaWaypoint ? " (via a region waypoint)" : "") + ": " + (p == null ? "none" : p.points.size() + " steps" + (p.complete ? "" : " (partial)") + ", " + (route == null ? 0 : route.points.size()) + " after shortening, " + p.searched + " searched"));
            spliceAt = -1;
        }
        int[] q = route == null ? null : route.next();
        while (q != null && Math.abs(q[1] - fy) <= 1 && (Math.abs(q[0] + 0.5 - x) < 0.35 && Math.abs(q[2] + 0.5 - z) < 0.35 || passed(q))) { route.index++; q = route.next(); stuckTicks = 0; noMoveTicks = 0; bestDist = Double.MAX_VALUE; } // a point reached is progress (on a winding route, e.g. up a hillside, the goal may get no closer for a while)
        if (q != null && work(q, fx, fy, fz)) return false; // digging or building on the way
        double gx = q != null ? q[0] + 0.5 : tx, gz = q != null ? q[2] + 0.5 : tz, gy = q != null ? q[1] : ty;
        double h2 = dx * dx + dz * dz;
        if (q == null && h2 < 0.8 * 0.8) {
            // right above or below the spot with no route: dig down or climb up
            forwardSpeed = 0;
            if (Math.abs(dy) >= 2.5) brain.stuck(tx, ty, tz);
            return false;
        }
        steer(gx, gy, gz);
        // real displacement check alongside distance-to-goal check
        double moved2 = (x - progX) * (x - progX) + (y - progY) * (y - progY) + (z - progZ) * (z - progZ);
        if (moved2 > 0.35 * 0.35) { progX = x; progY = y; progZ = z; noMoveTicks = 0; } else noMoveTicks++;
        if ((x - stuckOrigX) * (x - stuckOrigX) + (z - stuckOrigZ) * (z - stuckOrigZ) > 2.5 * 2.5) consecutiveStucks = 0;
        // stuck: not getting closer or not moving
        double d = Math.sqrt(h2) + Math.abs(dy);
        if (d < bestDist - 0.5) { bestDist = d; stuckTicks = 0; brain.progress(); } else stuckTicks++;
        if (bx != lastGoalX || bz != lastGoalZ) { lastGoalX = bx; lastGoalZ = bz; bestDist = d; stuckTicks = 0; noMoveTicks = 0; progX = x; progY = y; progZ = z; }
        int stall = Math.max(stuckTicks, noMoveTicks);
        if (stall >= 20 && stall < 44) {
            // stage 1: open any closed door nearby, hop, and sidestep gently off corners or fences
            brain.openNearbyDoor();
            if (stall % 8 == 0) jumping = true;
            sidewaysSpeed = ((consecutiveStucks + (stall / 12)) % 2 == 0 ? 0.45F : -0.45F) * walkSpeed;
        } else if (stall >= 44) {
            // stage 2: blacklist the failing waypoint, drop favored old route, back off sideways, and clear or replan
            stuckTicks = 0; noMoveTicks = 0; bestDist = d; progX = x; progY = y; progZ = z;
            if (q != null) avoidCell(q[0], q[1], q[2]);
            if (consecutiveStucks == 0) { stuckOrigX = x; stuckOrigZ = z; }
            consecutiveStucks++;
            route = null; search = null; spliceAt = -1; routeKeys = null; Nav.forget(world, bx, by, bz);
            if (DEBUG && q != null) Bots.log(String.format("debug stuck detail %s: at %.2f %.2f %.2f ground %s yaw %.0f, next point %d %d %d (%s), its column %d %d %d %d, mine %d %d %d %d", botName(), x, shape.minY, z, onGround, yaw, q[0], q[1], q[2], Nav.Move.values()[q[3]], world.getBlock(q[0], q[1] - 1, q[2]), world.getBlock(q[0], q[1], q[2]), world.getBlock(q[0], q[1] + 1, q[2]), world.getBlock(q[0], q[1] + 2, q[2]), world.getBlock(fx, fy - 1, fz), world.getBlock(fx, fy, fz), world.getBlock(fx, fy + 1, fz), world.getBlock(fx, fy + 2, fz)));
            if (consecutiveStucks >= 3 && unstickNow()) return false; // stage 3: safe local nudge out of persistent traps
            backOffTicks = 6 + Math.min(consecutiveStucks, 3) * 2;
            backStrafe = (consecutiveStucks % 2 == 0 ? 1 : -1) * walkSpeed * 0.75F;
            if (q != null) brain.stuck(q[0] + 0.5, q[1], q[2] + 0.5); else brain.stuck(tx, ty, tz);
        }
        return false;
    }

    /** Past this point already: within a block of it, on a plain step, and nearer the next point than it is. */
    private boolean passed(int[] q) {
        if (q[3] != Nav.Move.WALK.ordinal() && q[3] != Nav.Move.DIAG.ordinal() || route.index + 1 >= route.points.size()) return false;
        int[] n = route.points.get(route.index + 1);
        if (n[1] != q[1]) return false;
        double qx = q[0] + 0.5 - x, qz = q[2] + 0.5 - z;
        if (qx * qx + qz * qz > 1) return false;
        double nx = n[0] + 0.5 - x, nz = n[2] + 0.5 - z, gx = n[0] - q[0], gz = n[2] - q[2];
        return nx * nx + nz * nz < gx * gx + gz * gz;
    }

    private Nav planner() { return new Nav(world, 0, 0, 0, 0, 0, 0, 1, null, 0, 0, null, null); }

    /** The route's next step needs work first: blocks to break in the way, a pillar, or a bridge block. True while busy. */
    private boolean work(int[] q, int fx, int fy, int fz) {
        Nav.Move m = Nav.Move.values()[q[3]];
        if (m == Nav.Move.BRIDGE && Math.abs(q[0] - fx) + Math.abs(q[2] - fz) == 1 && Math.abs(q[1] - fy) <= 1) {
            int under = world.getBlock(q[0], q[1] - 1, q[2]);
            if (under == 0 || Nav.water(under)) {
                int block = count(3) > 0 ? 3 : count(4) > 0 ? 4 : 0;
                if (block != 0) {
                    forwardSpeed = 0;
                    face(q[0] + 0.5, q[1] - 0.5, q[2] + 0.5);
                    world.setBlock(q[0], q[1] - 1, q[2], block);
                    take(block, 1);
                    swing();
                    stuckTicks = 0; noMoveTicks = 0;
                    return true;
                }
            }
            return false;
        }
        if (m == Nav.Move.PILLAR && q[0] == fx && q[2] == fz && q[1] > fy) {
            int head = world.getBlock(fx, fy + 2, fz);
            if (head != 0 && Bots.block(head) != null && Bots.block(head).material.blocksMovement()) { forwardSpeed = 0; face(fx + 0.5, fy + 2.5, fz + 0.5); brain.dig(fx, fy + 2, fz); stuckTicks = 0; return true; } // room over its head first (planned)
            forwardSpeed = 0;
            if (brain.pillar(fx, fy, fz)) { stuckTicks = 0; return true; }
            return false; // it can't: steering and the stuck handling take over (it stood still for good before)
        }
        if (m != Nav.Move.DIG && m != Nav.Move.DIG_DOWN && m != Nav.Move.UP) return false;
        if (Math.abs(q[0] - fx) + Math.abs(q[2] - fz) > 1) return false; // not next to it yet
        int[][] cells = m == Nav.Move.UP ? new int[][]{{fx, fy + 2, fz}, {q[0], q[1], q[2]}, {q[0], q[1] + 1, q[2]}}
                : new int[][]{{q[0], q[1] + 2, q[2]}, {q[0], q[1] + 1, q[2]}, {q[0], q[1], q[2]}};
        for (int[] c : cells) {
            int id = world.getBlock(c[0], c[1], c[2]);
            if (id == 0 || Nav.water(id) || Nav.door(id) || Bots.block(id) == null || !Bots.block(id).material.blocksMovement()) continue;
            if (m == Nav.Move.UP && c[1] == q[1] + 2) continue;
            forwardSpeed = 0;
            face(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
            brain.dig(c[0], c[1], c[2]);
            stuckTicks = 0;
            return true;
        }
        return false;
    }

    private void steer(double gx, double gy, double gz) {
        float want = (float) (Math.atan2(gz - z, gx - x) * 180.0 / Math.PI) - 90.0F;
        float turn = want - yaw;
        while (turn < -180) turn += 360;
        while (turn >= 180) turn -= 360;
        double hd = Math.sqrt((gx - x) * (gx - x) + (gz - z) * (gz - z));
        if (hd > 0.3) yaw += Math.max(-40, Math.min(40, turn)); // right on the point: no turning (the way to it swings round as it jitters)
        forwardSpeed = hd <= 0.3 ? walkSpeed * 0.3F : walkSpeed;
        if (gy > shape.minY + 0.5 || collidingHorizontally) jumping = true;
    }

    boolean walking() { return forwardSpeed > 0; }
    /** Walked last tick (the brain runs before this tick's walking, with the speed already reset). */
    boolean walkedLast;

    // for the brain's survival reflexes (AltoClef-style)
    int airLeft() { return breath; }
    boolean burning() { return onFireTimer > 0 || isInLava(); }
    boolean underwater() { return isSubmergedIn(net.minecraft.block.material.Material.WATER); }
    void holdJump() { jumping = true; }

    public void stopMoving() { route = null; search = null; spliceAt = -1; forwardSpeed = 0; }

    public void face(double tx, double ty, double tz) {
        double dx = tx - x, dz = tz - z, dy = ty - (y + getEyeHeight());
        // the short way round from where it faces now: a yaw that jumps by 360 (atan2 gives -180..180, steering adds
        // turns up) is the same direction, but the game draws the frames in between as a whole turn: spinning on the spot
        yaw += wrap((float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F - yaw);
        pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
        bodyYaw += wrap(yaw - bodyYaw);
    }

    /** An angle as -180..180. */
    static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
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
