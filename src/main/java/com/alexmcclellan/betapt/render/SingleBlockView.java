package com.alexmcclellan.betapt.render;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.world.WorldView;

/**
 * A pretend world containing one block (at 0, 64, 0) with a chosen metadata and air
 * everywhere else. Some blocks (grass, furnace, dispenser, chests, stairs) only
 * report their correct face textures when asked with a world, so BlockTable asks them
 * through this.
 */
final class SingleBlockView implements WorldView {
    int id, meta;

    private boolean isTheBlock(int x, int y, int z) {
        return x == 0 && y == 64 && z == 0;
    }

    /** Blocks around the one block, for ChestLooks (a wall behind a chest, the other half of a double chest). */
    private final java.util.Map<Long, Integer> around = new java.util.HashMap<>();

    private static long key(int x, int y, int z) { return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | (long) z & 0xFFFFF; }
    void put(int x, int y, int z, int block) { around.put(key(x, y, z), block); }
    void clearAround() { around.clear(); }

    @Override public int getBlock(int x, int y, int z) {
        if (isTheBlock(x, y, z)) return id;
        return around.isEmpty() ? 0 : around.getOrDefault(key(x, y, z), 0);
    }
    private final java.util.Map<Integer, BlockEntity> entities = new java.util.HashMap<>();

    /**
     * Modded machines keep their facing and on/off state in a block entity and their renderers expect one:
     * a fresh one with default settings, made by the block itself, one per block type.
     */
    @Override public BlockEntity getBlockEntity(int x, int y, int z) {
        if (!isTheBlock(x, y, z) || id <= 0 || id >= net.minecraft.block.Block.BY_ID.length) return null;
        net.minecraft.block.Block b = net.minecraft.block.Block.BY_ID[id];
        if (!(b instanceof net.minecraft.block.BlockWithBlockEntity bwe)) return null;
        return entities.computeIfAbsent(id << 4 | meta, k -> {
            // Forge's blocks make theirs per metadata (getBlockEntity(int)); Beta's with no arguments
            BlockEntity e = null;
            try {
                for (int args = 1; args >= 0 && e == null; args--)
                    for (Class<?> c = bwe.getClass(); c != null && e == null; c = c.getSuperclass())
                        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                            if (m.getParameterCount() != args || !BlockEntity.class.isAssignableFrom(m.getReturnType())) continue;
                            if (args == 1 && m.getParameterTypes()[0] != int.class) continue;
                            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                            m.setAccessible(true);
                            try { e = (BlockEntity) (args == 1 ? m.invoke(bwe, meta) : m.invoke(bwe)); } catch (Throwable ignored) {}
                            if (e != null) break;
                        }
            } catch (Throwable ignored) {}
            if (e != null) {
                e.x = 0; e.y = 64; e.z = 0;
                // some renderers ask the block entity's world whether it's a client: lend them the game's
                try { e.world = ((net.minecraft.client.Minecraft) net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance()).world; } catch (Throwable ignored) {}
                // BuildCraft's pipes only know which texture to draw once told their pipe kind (it happens when one is placed)
                // (looked up by exact signature: listing all its methods fails, one of them names a server-only class)
                for (Class<?> pc = b.getClass(); pc != null && pc != Object.class; pc = pc.getSuperclass())
                    try {
                        java.lang.invoke.MethodHandles.publicLookup().findVirtual(e.getClass(), "setDefaultTexturesForClient",
                                java.lang.invoke.MethodType.methodType(void.class, pc)).invoke(e, b);
                        break;
                    } catch (Throwable ignored) {}
            }
            return e;
        });
    }
    @Override public float getBrightness(int x, int y, int z) { return 1f; }
    @Override public int getBlockMetadata(int x, int y, int z) { return isTheBlock(x, y, z) ? meta : 0; }
    @Override public Material getMaterial(int x, int y, int z) { return Material.AIR; }
    @Override public boolean isSolidBlock(int x, int y, int z) { return false; }
}
