package com.alexmcclellan.betapt.render;

import net.minecraft.world.World;
import net.minecraft.world.LightUpdate;

import java.util.List;

/**
 * Alpha's client works through up to 500 queued lighting updates every frame, each up to 32,768 blocks, however
 * long that takes. Arriving somewhere new (a teleport, a fast boat, a new dimension) queues hundreds of thousands,
 * and the game froze for seconds; on a slow machine for over 30 s, long enough for the server to drop the player.
 *
 * Here: at most 6 ms a frame (or Beta's 500, whichever comes first), big updates cut into slices of at most
 * 4,096 blocks so one can't blow the budget. The same blocks get lit, spread over a few more frames.
 */
public final class ClientLighting {
    private ClientLighting() {}

    private static final long BUDGET_NANOS = 6_000_000L;
    private static final int MAX_CELLS = 4096;

    /** Replaces the frame's World.doLightingUpdates() (MinecraftMixin). True while updates are left. */
    @SuppressWarnings("unchecked")
    public static boolean run(World world) {
        List<Object> q;
        try { q = ((com.alexmcclellan.betapt.mixin.WorldLightQueueAccessor) world).betapt$lightingQueue(); }
        catch (Throwable t) { return world.doLightUpdates(); }
        long end = System.nanoTime() + BUDGET_NANOS;
        for (int n = 0; n < 500 && !q.isEmpty(); n++) {
            LightUpdate u = (LightUpdate) q.remove(q.size() - 1);
            long sx = u.maxX - u.minX + 1L, sy = u.maxY - u.minY + 1L, sz = u.maxZ - u.minZ + 1L;
            if (sx > 0 && sy > 1 && sz > 0 && sx * sy * sz > MAX_CELLS) {
                // a big one: slices along y, the rest back on the queue for later
                int rows = (int) Math.max(1, MAX_CELLS / (sx * sz));
                for (int y = u.minY + rows; y <= u.maxY; y += rows)
                    q.add(new LightUpdate(u.type, u.minX, y, u.minZ, u.maxX, Math.min(u.maxY, y + rows - 1), u.maxZ));
                u = new LightUpdate(u.type, u.minX, u.minY, u.minZ, u.maxX, Math.min(u.maxY, u.minY + rows - 1), u.maxZ);
            }
            u.run(world);
            if (System.nanoTime() > end) break;
        }
        return !q.isEmpty();
    }
}
