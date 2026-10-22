package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.block.Block;
import net.minecraft.block.BlockWithBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Which way a placed block faces, for the tracers. The block table has one entry per block ID and metadata, but some
 * modded blocks keep their facing elsewhere, so every one of them traced facing the same way:
 * <ul>
 * <li>StationAPI blocks with a "facing" block state (Iron Chests): their metadata stays 0, so the tracers' copy of the
 *     world gets the state's number instead (a block with at most 16 states), and the table draws each state.</li>
 * <li>IndustrialCraft's machines (and Advanced Machines) keep facing and "active" (running: the lit front) in their
 *     block entity, metadata being which machine: each such block gets 12 more table IDs past the registered blocks
 *     (6 facings x off/on), the copy of the world uses those, and the table draws each with its block entity turned.</li>
 * </ul>
 */
public final class Orientations {
    private Orientations() {}

    private static boolean ready;
    private static final int MAX = StationCompat.MAX_ID;
    /** Per ID: the first of its 12 extra table IDs, or 0. */
    private static final int[] synBase = new int[MAX];
    /** Per extra table ID: the block it draws (0 for a real ID). */
    private static final int[] synOrig = new int[MAX];
    private static final Field[] facingField = new Field[MAX], activeField = new Field[MAX];
    /** Per ID: its block states when it has a facing state and 16 or fewer states, else null. */
    private static final List<?>[] states = new List<?>[MAX];
    private static boolean anySyn, anyStates;
    private static Method chunkState;

    static synchronized void init() {
        if (ready) return;
        ready = true;
        int n = StationCompat.blockCount(), next = MAX - 1;
        SingleBlockView view = new SingleBlockView();
        for (int id = 97; id < n; id++) {
            Block b = Block.BY_ID[id];
            if (b == null) continue;
            try { // StationAPI block states with a facing
                Object sm = b.getClass().getMethod("getStateManager").invoke(b);
                List<?> st = (List<?>) sm.getClass().getMethod("getStates").invoke(sm);
                if (st.size() > 1 && st.size() <= 16) {
                    Object props = sm.getClass().getMethod("getProperties").invoke(sm);
                    for (Object p : (java.util.Collection<?>) props)
                        if ("facing".equals(p.getClass().getMethod("getName").invoke(p))) { states[id] = st; anyStates = true; break; }
                }
            } catch (Throwable ignored) {}
            if (!(b instanceof BlockWithBlockEntity)) continue;
            view.id = id; view.meta = 0;
            BlockEntity e;
            try { e = view.getBlockEntity(0, 64, 0); } catch (Throwable t) { e = null; }
            if (e == null) continue;
            Field f = field(e.getClass(), "facing", short.class, byte.class, int.class);
            if (f == null) continue;
            if (next - 11 <= n) break;
            int base = next - 11;
            for (int k = 0; k < 12; k++) if (Block.BY_ID.length > base + k && Block.BY_ID[base + k] != null) { base = -1; break; }
            if (base < 0) continue;
            synBase[id] = base;
            for (int k = 0; k < 12; k++) synOrig[base + k] = id;
            facingField[id] = f;
            activeField[id] = field(e.getClass(), "active", boolean.class);
            next = base - 1;
            anySyn = true;
        }
        int nSyn = 0, nSt = 0;
        for (int id = 0; id < MAX; id++) { if (synBase[id] != 0) nSyn++; if (states[id] != null) nSt++; }
        if (nSyn + nSt > 0) BetaPT.LOGGER.info("Facing: {} blocks turn by their block entity (IC2's machines), {} by their block state (Iron Chests)", nSyn, nSt);
    }

    private static Field field(Class<?> c, String name, Class<?>... types) {
        for (; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (f.getName().equals(name) && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    for (Class<?> t : types) if (f.getType() == t) { f.setAccessible(true); return f; }
        return null;
    }

    /** The block a table ID draws: itself, or for an extra ID the block it was made for. */
    public static int original(int id) {
        if (!ready) init();
        return id > 0 && id < MAX && synOrig[id] != 0 ? synOrig[id] : id;
    }

    /** The block state the table should draw for this ID and metadata (null: the block's default). */
    static Object state(int id, int meta) {
        if (!ready) init();
        List<?> st = id > 0 && id < MAX ? states[id] : null;
        return st != null && meta < st.size() ? st.get(meta) : null;
    }

    private static Object[] saved = new Object[2];

    /** Before the table draws an extra ID: turn the pretend block entity (and switch it on) to match. */
    static void apply(SingleBlockView view, int tableId) {
        int id = original(tableId);
        if (id == tableId) return;
        int k = tableId - synBase[id];
        try {
            BlockEntity e = view.getBlockEntity(0, 64, 0);
            if (e == null) return;
            saved[0] = facingField[id].get(e);
            setNumber(facingField[id], e, k >> 1);
            if (activeField[id] != null) { saved[1] = activeField[id].get(e); activeField[id].setBoolean(e, (k & 1) == 1); }
        } catch (Throwable ignored) {}
    }

    /** ...and back afterwards (the same pretend block entity draws the real ID). */
    static void restore(SingleBlockView view, int tableId) {
        int id = original(tableId);
        if (id == tableId) return;
        try {
            BlockEntity e = view.getBlockEntity(0, 64, 0);
            if (e == null) return;
            facingField[id].set(e, saved[0]);
            if (activeField[id] != null) activeField[id].set(e, saved[1]);
        } catch (Throwable ignored) {}
    }

    private static void setNumber(Field f, Object o, int v) throws IllegalAccessException {
        if (f.getType() == short.class) f.setShort(o, (short) v);
        else if (f.getType() == byte.class) f.setByte(o, (byte) v);
        else f.setInt(o, v);
    }

    /** After a chunk is read for the tracers: facing blocks get their extra ID or their state's number. */
    static void mark(WorldChunk c, short[] ids, byte[] meta) {
        if (!ready) init();
        if (anySyn && c.blockEntities != null) {
            try {
                for (Object o : c.blockEntities.values()) {
                    if (!(o instanceof BlockEntity e)) continue;
                    int lx = e.x & 15, lz = e.z & 15, y = e.y;
                    if (y < 0 || y >= 128) continue;
                    int i = lx << 11 | lz << 7 | y;
                    int id = ids[i] & 0xFFFF;
                    if (id >= MAX || synBase[id] == 0 || facingField[id] == null || !facingField[id].getDeclaringClass().isInstance(e)) continue;
                    int f = ((Number) facingField[id].get(e)).intValue();
                    if (f < 0 || f > 5) continue;
                    boolean on = activeField[id] != null && activeField[id].getDeclaringClass().isInstance(e) && activeField[id].getBoolean(e);
                    ids[i] = (short) (synBase[id] + f * 2 + (on ? 1 : 0));
                }
            } catch (Throwable ignored) {} // (the map changing under us: the next read gets it)
        }
        if (anyStates) {
            for (int i = 0; i < 32768; i++) {
                int id = ids[i] & 0xFFFF;
                if (id >= MAX || states[id] == null) continue;
                int m = (i & 1) == 0 ? meta[i >> 1] & 15 : meta[i >> 1] >> 4 & 15;
                if (m != 0) continue;
                try {
                    if (chunkState == null) chunkState = c.getClass().getMethod("getBlockState", int.class, int.class, int.class);
                    Object s = chunkState.invoke(c, i >> 11, i & 127, i >> 7 & 15);
                    int k = states[id].indexOf(s);
                    if (k > 0 && k < 16) meta[i >> 1] |= (byte) ((i & 1) == 0 ? k : k << 4);
                } catch (Throwable t) { anyStates = false; BetaPT.LOGGER.warn("Facing: can't read block states ({})", t.toString()); return; }
            }
        }
    }
}
