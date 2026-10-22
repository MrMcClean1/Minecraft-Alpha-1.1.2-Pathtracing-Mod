package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import com.alexmcclellan.betapt.mixin.TessellatorAccessor;
import net.minecraft.block.Block;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.block.BlockRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks Beta's own block renderer to draw one block (at 0, 64, 0 in a pretend world, nothing around it) and listens
 * to what it hands the Tesselator. Modded blocks with their own renderers (RedPower gates and lamps, IC2 resin,
 * BuildCraft landmarks...) and blocks whose face textures only the renderer knows (Equivalent Exchange's Nova
 * Catalyst) then trace as they look in Beta: each face's texture from the texture coordinates actually drawn, and
 * the shape from pairs of opposite faces (a box is the space between a face and the one looking the other way).
 * Nothing is drawn: the Tesselator's buffer is thrown away afterwards.
 */
public final class BlockCapture {
    private BlockCapture() {}

    /** Set while a block is being captured (TessellatorCaptureMixin notes vertices; the buffer is read afterwards). */
    public static boolean listening;
    static String lastFailure = "";
    private static float[] verts = new float[5 * 4096];
    private static int count;

    public static void vertex(double x, double y, double z, double u, double v) {
        if (count * 5 + 5 > verts.length) { if (verts.length >= 5 * 65536) return; verts = java.util.Arrays.copyOf(verts, verts.length * 2); }
        int o = count * 5;
        verts[o] = (float) x; verts[o + 1] = (float) (y - 64); verts[o + 2] = (float) z; verts[o + 3] = (float) u; verts[o + 4] = (float) v;
        count++;
    }

    private static Boolean triangles;

    /** Tesselator.TRIANGLE_MODE (Beta's first static boolean there): quads stored as triangle pairs. */
    private static boolean triangleMode() {
        if (triangles == null) {
            triangles = true;
            for (java.lang.reflect.Field f : Tesselator.class.getDeclaredFields())
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == boolean.class) {
                    try { f.setAccessible(true); triangles = f.getBoolean(null); } catch (Throwable ignored) {}
                    break;
                }
        }
        return triangles;
    }

    /** What a capture found: a tile per Beta face (0 down, 1 up, 2 north, 3 south, 4 west, 5 east; -1 none) and boxes. */
    public record Result(int[] faceTile, List<float[]> boxes, int quads, int[] faceOrient) {}

    /** The tracers' texture position on a face for a point on it (pathtrace.comp's faceUV, ChunkMesher.faceUV). */
    private static float[] faceUV(int face, float x, float y, float z) {
        switch (face) {
            case 0: case 1: return new float[]{x, z};
            case 2: return new float[]{1 - x, 1 - y};
            case 3: return new float[]{x, 1 - y};
            case 4: return new float[]{z, 1 - y};
            default: return new float[]{1 - z, 1 - y};
        }
    }

    /**
     * How a drawn quad's texture sits on its face compared with faceUV: bit 1 swaps u and v, bit 2 flips u, bit 4
     * flips v (after the swap). StationAPI models can turn a face's texture (BuildCraft's Builder and Architect Table
     * tops), which the tracers otherwise drew the default way round.
     */
    private static int orientation(int side, int o) {
        float[] e0 = faceUV(side, verts[o], verts[o + 1], verts[o + 2]);
        double suu = 0, suv = 0, svu = 0, svv = 0; // d(actual u, v) against d(expected u, v), summed over the edges
        for (int k = 1; k < 4; k++) {
            int p = o + k * 5;
            float[] e = faceUV(side, verts[p], verts[p + 1], verts[p + 2]);
            double deu = e[0] - e0[0], dev = e[1] - e0[1], dau = verts[p + 3] - verts[o + 3], dav = verts[p + 4] - verts[o + 4];
            suu += dau * deu; suv += dau * dev; svu += dav * deu; svv += dav * dev;
        }
        boolean swap = Math.abs(suv) > Math.abs(suu);
        double du = swap ? suv : suu, dv = swap ? svu : svv;
        return (swap ? 1 : 0) | (du < 0 ? 2 : 0) | (dv < 0 ? 4 : 0);
    }

    /** Renders block b with metadata meta through Beta's renderer. Null if nothing came out or rendering failed. */
    static Result capture(Block b, SingleBlockView view) {
        Tesselator t = Tesselator.INSTANCE;
        TessellatorAccessor acc = (TessellatorAccessor) t;
        if (acc.betapt$isDrawing()) { lastFailure = "tessellator busy"; return null; } // someone is mid-draw: not now
        count = 0;
        lastFailure = "no vertices";
        double[] bounds = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        try {
            t.begin();
            listening = true;
            int texBefore = DEBUG_UV ? org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D) : 0;
            int pass = renderPass(0);
            try {
                new BlockRenderer(worldFor(view)).tesselateInWorld(b, 0, 64, 0);
            } finally {
                renderPass(pass);
            }
            if (DEBUG_UV) {
                int texAfter = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
                BetaPT.LOGGER.info("capture {} texture before {} after {} ({}x{})", b.id, texBefore, texAfter,
                        org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH),
                        org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT));
            }
        } catch (Throwable e) {
            Throwable c = e;
            while (c.getCause() != null && c.getCause() != c && (c instanceof java.lang.reflect.UndeclaredThrowableException || c instanceof java.lang.reflect.InvocationTargetException)) c = c.getCause();
            lastFailure = c + (c.getStackTrace().length > 0 ? " at " + c.getStackTrace()[0] : "") + " after " + count + " vertices";
            count = 0; // a renderer that needs a real world (tile entities etc.): no capture
        } finally {
            listening = false;
            // Read back what the renderer put in the Tesselator's buffer. That holds both Beta-style blocks (vertex()
            // calls) and StationAPI's model blocks, which copy whole quads in without going through vertex().
            try {
                if (count > 0 || lastFailure.equals("no vertices")) {
                    int n = acc.betapt$vertexCount();
                    int[] buf = acc.betapt$buffer();
                    double ox = acc.betapt$xOffset(), oy = acc.betapt$yOffset(), oz = acc.betapt$zOffset();
                    count = 0;
                    // Beta stores each quad as two triangles (v0 v1 v2, v0 v2 v3) when TRIANGLE_MODE is on: 6 vertices
                    boolean tri = triangleMode();
                    int[] pick = tri ? new int[]{0, 1, 2, 5} : new int[]{0, 1, 2, 3};
                    int per = tri ? 6 : 4;
                    for (int q = 0; q + per <= n && (q + per) * 8 <= buf.length; q += per)
                        for (int k : pick) {
                            int o = (q + k) * 8;
                            vertex(Float.intBitsToFloat(buf[o]) - ox, Float.intBitsToFloat(buf[o + 1]) - oy, Float.intBitsToFloat(buf[o + 2]) - oz,
                                    Float.intBitsToFloat(buf[o + 3]), Float.intBitsToFloat(buf[o + 4]));
                        }
                }
            } catch (Throwable ignored) {}
            try { acc.betapt$reset(); acc.betapt$setDrawing(false); } catch (Throwable ignored) {}
            b.setShape((float) bounds[0], (float) bounds[1], (float) bounds[2], (float) bounds[3], (float) bounds[4], (float) bounds[5]);
        }
        int quads = count / 4;
        if (quads == 0) return null;
        int[] tile = {-1, -1, -1, -1, -1, -1}, orient = new int[6];
        float[] best = new float[6];
        // axis-aligned quads by axis and direction: plane position and rectangle on the other two axes
        List<float[]> faces = new ArrayList<>(); // {axis, dir(+1/-1), plane, a0, b0, a1, b1}
        for (int q = 0; q < quads; q++) {
            int o = q * 20;
            float ax = verts[o + 5] - verts[o], ay = verts[o + 6] - verts[o + 1], az = verts[o + 7] - verts[o + 2];
            float bx = verts[o + 15] - verts[o], by = verts[o + 16] - verts[o + 1], bz = verts[o + 17] - verts[o + 2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) continue;
            nx /= len; ny /= len; nz /= len;
            int axis = Math.abs(nx) > 0.99f ? 0 : Math.abs(ny) > 0.99f ? 1 : Math.abs(nz) > 0.99f ? 2 : -1;
            float n = axis == 0 ? nx : axis == 1 ? ny : nz;
            int dir = n > 0 ? 1 : -1; // Beta winds its faces so this normal points out of the block
            float u = 0, v = 0, lo0 = 1e9f, lo1 = 1e9f, hi0 = -1e9f, hi1 = -1e9f, plane = 0;
            for (int k = 0; k < 4; k++) {
                int p = o + k * 5;
                u += verts[p + 3] / 4; v += verts[p + 4] / 4;
                if (axis < 0) continue;
                float c0 = verts[p + (axis == 0 ? 1 : 0)], c1 = verts[p + (axis == 2 ? 1 : 2)];
                lo0 = Math.min(lo0, c0); hi0 = Math.max(hi0, c0); lo1 = Math.min(lo1, c1); hi1 = Math.max(hi1, c1);
                plane = verts[p + axis];
            }
            if (axis < 0) continue;
            int side = axis == 1 ? (dir < 0 ? 0 : 1) : axis == 2 ? (dir < 0 ? 2 : 3) : (dir < 0 ? 4 : 5);
            float area = (hi0 - lo0) * (hi1 - lo1);
            int tl = StationCompat.tileAtUV(u, v);
            if (DEBUG_UV && q < 3) BetaPT.LOGGER.info("capture uv {} {} -> tile {}", u, v, tl);
            if (tl >= 0 && area > best[side]) { best[side] = area; tile[side] = tl; orient[side] = orientation(side, o); }
            faces.add(new float[]{axis, dir, plane, lo0, lo1, hi0, hi1, tl});
        }
        return new Result(tile, boxes(faces), quads, orient);
    }

    private static final boolean DEBUG_UV = Boolean.getBoolean("betapt.debugCaptureUv");
    private static java.lang.reflect.Field passField;
    private static boolean passTried;

    /**
     * BuildCraft draws its pipes in two passes and keeps which one Beta is on in RenderHelper.currentRenderPass; in
     * the second (see-through) pass an uncoloured pipe draws nothing. That number stays at whatever the last chunk
     * Beta built left it, so a capture made after a second pass found no pipe at all and pipes were missing from
     * the path traced view. Captures run in the first pass. Returns the pass it was on (-1 without BuildCraft).
     */
    private static int renderPass(int pass) {
        if (!passTried) {
            passTried = true;
            try {
                passField = Class.forName("net.danygames2014.buildcraft.util.RenderHelper").getField("currentRenderPass");
            } catch (Throwable ignored) {}
        }
        if (passField == null || pass < 0) return -1;
        try {
            int was = passField.getInt(null);
            passField.setInt(null, pass);
            return was;
        } catch (Throwable t) {
            return -1;
        }
    }
    private static net.minecraft.world.WorldView proxy;
    private static boolean proxyTried;

    /**
     * StationAPI's renderer reads block states, not just IDs: the pretend world must also be one of its
     * BlockStateViews. Built as a proxy so the mod still runs without StationAPI.
     */
    private static net.minecraft.world.WorldView worldFor(SingleBlockView view) {
        if (!StationCompat.PRESENT) return view;
        if (!proxyTried) {
            proxyTried = true;
            try {
                Class<?> stateView = Class.forName("net.modificationstation.stationapi.api.world.BlockStateView");
                Object air = ((java.util.function.Supplier<?>) Class.forName("net.modificationstation.stationapi.api.block.States").getField("AIR").get(null)).get();
                proxy = (net.minecraft.world.WorldView) java.lang.reflect.Proxy.newProxyInstance(BlockCapture.class.getClassLoader(),
                        new Class<?>[]{net.minecraft.world.WorldView.class, stateView}, (self, m, args) -> {
                            if (m.getName().equals("getBlockState") && args != null && args.length == 3) {
                                int x = (Integer) args[0], y = (Integer) args[1], z = (Integer) args[2];
                                if (x != 0 || y != 64 || z != 0) return air;
                                Block blk = view.id > 0 && view.id < Block.BY_ID.length ? Block.BY_ID[view.id] : null;
                                Object st = Orientations.state(view.id, view.meta);
                                return blk == null ? air : st != null ? st : blk.getClass().getMethod("getDefaultState").invoke(blk);
                            }
                            if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(self, m, args);
                            if (m.getDeclaringClass() == Object.class) return m.getName().equals("hashCode") ? System.identityHashCode(self) : m.getName().equals("equals") ? self == args[0] : "BlockCapture view";
                            return m.invoke(view, args);
                        });
            } catch (Throwable t) {
                BetaPT.LOGGER.warn("Block capture: no StationAPI block-state view ({})", t.toString());
            }
        }
        return proxy != null ? proxy : view;
    }

    /** A box between each face and the nearest face looking the other way over the same rectangle. */
    private static List<float[]> boxes(List<float[]> faces) {
        List<float[]> out = new ArrayList<>();
        for (float[] f : faces) {
            if (f[1] < 0) continue; // start from the faces looking towards +axis (the box's far side)
            float[] near = null;
            for (float[] g : faces)
                if (g[0] == f[0] && g[1] < 0 && g[2] < f[2] - 1e-4f && same(g, f) && (near == null || g[2] > near[2])) near = g;
            if (near == null) continue;
            int axis = (int) f[0];
            float[] lo = new float[3], hi = new float[3];
            lo[axis] = near[2]; hi[axis] = f[2];
            int a0 = axis == 0 ? 1 : 0, a1 = axis == 2 ? 1 : 2;
            lo[a0] = f[3]; hi[a0] = f[5]; lo[a1] = f[4]; hi[a1] = f[6];
            float[] box = {lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], f[7]}; // [6]: the far face's tile
            boolean dup = false;
            for (float[] b : out) { boolean eq = true; for (int i = 0; i < 6; i++) if (Math.abs(b[i] - box[i]) > 1e-3f) eq = false; if (eq) { dup = true; break; } }
            if (!dup) out.add(box);
        }
        return out;
    }

    private static boolean same(float[] a, float[] b) {
        for (int i = 3; i < 7; i++) if (Math.abs(a[i] - b[i]) > 1e-3f) return false;
        return true;
    }

    static void log(int id, int meta, Block b, Result r) {
        StringBuilder s = new StringBuilder();
        if (r != null) for (float[] x : r.boxes) s.append(java.util.Arrays.toString(x));
        BetaPT.LOGGER.info("capture {}:{} {} render {} -> {}", id, meta, b.getClass().getSimpleName(), b.getRenderType(),
                r == null ? "nothing (" + lastFailure + ")" : r.quads + " quads, tiles " + java.util.Arrays.toString(r.faceTile) + ", boxes " + s);
    }
}
