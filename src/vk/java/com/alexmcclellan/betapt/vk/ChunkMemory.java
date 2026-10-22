package com.alexmcclellan.betapt.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Chunk buffers from a few big blocks of video memory instead of one allocation each. Windows drivers cap how
 * many allocations a program may hold (often 4096), and at Far render distance a thousand chunks with up to four
 * buffers each came close. Blocks are 64 MB; a buffer bigger than a quarter of that gets a block of its own.
 * The chunk mesher thread allocates and the game thread frees, so everything here is synchronized.
 */
final class ChunkMemory {
    private static final long BLOCK = 64L << 20;

    private final VkDevice device;
    private final java.util.function.IntBinaryOperator typeFor; // (memoryTypeBits, kind) -> memory type index
    /** One pool per memory type; host-visible ones stay mapped. */
    private final Map<Integer, List<Block>> pools = new HashMap<>();
    /** buffer handle -> its allocation */
    private final Map<Long, long[]> live = new HashMap<>(); // {block index in its pool, offset, size, memory type}
    private int blocksAllocated;

    private static final class Block {
        long memory, mapped, size;
        final TreeMap<Long, Long> free = new TreeMap<>(); // offset -> length
        int used;
    }

    static final int KIND_HOST = 0, KIND_DEVICE = 1, KIND_HOST_PLAIN = 2;

    ChunkMemory(VkDevice device, java.util.function.IntBinaryOperator typeFor) {
        this.device = device;
        this.typeFor = typeFor;
    }

    /**
     * A new buffer bound into a pooled block: {buffer, memory, mapped pointer (0 if not host-visible)}.
     * kind: KIND_HOST (the CPU writes it: triangles) or KIND_DEVICE (search trees).
     */
    long[] buffer(long size, int usage, int kind) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            VkBase.check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(Math.max(16, size)).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lp), "vkCreateBuffer");
            long buf = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buf, req);
            int type = typeFor.applyAsInt(req.memoryTypeBits(), kind);
            long need = req.size(), align = Math.max(256, req.alignment());
            synchronized (this) {
                List<Block> pool = pools.computeIfAbsent(type, t -> new ArrayList<>());
                for (int bi = 0; bi < pool.size(); bi++) {
                    long off = take(pool.get(bi), need, align);
                    if (off >= 0) return bind(buf, pool.get(bi), bi, off, need, type);
                }
                Block b;
                try { b = newBlock(type, kind, Math.max(BLOCK, (need + align + 0xFFFF) & ~0xFFFFL)); }
                catch (RuntimeException full) {
                    if (kind != KIND_HOST) throw full;
                    // video memory the CPU can write is often only 256 MB: carry on in system memory
                    type = typeFor.applyAsInt(req.memoryTypeBits(), KIND_HOST_PLAIN);
                    pool = pools.computeIfAbsent(type, t -> new ArrayList<>());
                    for (int bi = 0; bi < pool.size(); bi++) {
                        long off = take(pool.get(bi), need, align);
                        if (off >= 0) return bind(buf, pool.get(bi), bi, off, need, type);
                    }
                    b = newBlock(type, kind, Math.max(BLOCK, (need + align + 0xFFFF) & ~0xFFFFL));
                }
                pool.add(b);
                int bi = pool.size() - 1;
                long off = take(b, need, align);
                return bind(buf, b, bi, off, need, type);
            }
        }
    }

    /** True if this buffer came from here (then free it with free()). */
    synchronized boolean owns(long buffer) { return live.containsKey(buffer); }

    /** Destroys the buffer and gives its space back. Only once the GPU is done with it. */
    void free(long buffer) {
        long[] a;
        synchronized (this) { a = live.remove(buffer); }
        if (a == null) return;
        vkDestroyBuffer(device, buffer, null);
        synchronized (this) {
            Block b = pools.get((int) a[3]).get((int) a[0]);
            give(b, a[1], a[2]);
            b.used--;
            if (b.used == 0) { // an empty block goes back to the driver, unless it's the pool's last one
                int open = 0;
                for (Block o : pools.get((int) a[3])) if (o.memory != 0) open++;
                if (open > 1) {
                    if (b.mapped != 0) vkUnmapMemory(device, b.memory);
                    vkFreeMemory(device, b.memory, null);
                    b.memory = 0; b.mapped = 0; b.free.clear();
                }
            }
        }
    }

    synchronized String info() {
        long total = 0;
        int n = 0;
        for (List<Block> p : pools.values()) for (Block b : p) if (b.memory != 0) { total += b.size; n++; }
        return live.size() + " buffers in " + n + " blocks (" + (total >> 20) + " MB)";
    }

    /** Everything, at shutdown (after the GPU is idle). */
    synchronized void close() {
        for (Long buf : live.keySet()) vkDestroyBuffer(device, buf, null);
        live.clear();
        for (List<Block> p : pools.values())
            for (Block b : p) if (b.memory != 0) { if (b.mapped != 0) vkUnmapMemory(device, b.memory); vkFreeMemory(device, b.memory, null); b.memory = 0; }
        pools.clear();
    }

    // ---- inside a block: first fit, merging free neighbours ----

    private static long take(Block b, long need, long align) {
        if (b.memory == 0) return -1;
        for (Map.Entry<Long, Long> e : b.free.entrySet()) {
            long start = e.getKey(), len = e.getValue();
            long at = (start + align - 1) / align * align;
            if (at + need > start + len) continue;
            b.free.remove(start);
            if (at > start) b.free.put(start, at - start);
            if (at + need < start + len) b.free.put(at + need, start + len - at - need);
            b.used++;
            return at;
        }
        return -1;
    }

    private static void give(Block b, long off, long len) {
        Map.Entry<Long, Long> before = b.free.floorEntry(off), after = b.free.ceilingEntry(off);
        if (before != null && before.getKey() + before.getValue() == off) { off = before.getKey(); len += before.getValue(); b.free.remove(before.getKey()); }
        if (after != null && off + len == after.getKey()) { len += after.getValue(); b.free.remove(after.getKey()); }
        b.free.put(off, len);
    }

    private long[] bind(long buf, Block b, int bi, long off, long size, int type) {
        VkBase.check(vkBindBufferMemory(device, buf, b.memory, off), "vkBindBufferMemory");
        live.put(buf, new long[]{bi, off, size, type});
        return new long[]{buf, b.memory, b.mapped == 0 ? 0 : b.mapped + off};
    }

    private Block newBlock(int type, int kind, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryAllocateFlagsInfo flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            LongBuffer lp = stack.mallocLong(1);
            VkBase.check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(flags.address())
                    .allocationSize(size).memoryTypeIndex(type), null, lp), "vkAllocateMemory (chunk block)");
            Block b = new Block();
            b.memory = lp.get(0);
            b.size = size;
            b.free.put(0L, size);
            if (kind == KIND_HOST) {
                PointerBuffer pp = stack.mallocPointer(1);
                VkBase.check(vkMapMemory(device, b.memory, 0, VK_WHOLE_SIZE, 0, pp), "vkMapMemory (chunk block)");
                b.mapped = pp.get(0);
            }
            blocksAllocated++;
            return b;
        }
    }
}
