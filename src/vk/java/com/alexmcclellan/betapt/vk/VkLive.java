package com.alexmcclellan.betapt.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * M8c: the path tracer on the GPU's ray-tracing hardware, live, every frame.
 *
 * It writes the same six pictures as the OpenGL tracer (light, sky/fog, surface colour, glow,
 * geometry, depth) into GPU memory that OpenGL is given access to as ordinary textures
 * ("external memory"); the game then denoises, upscales and shows them exactly as before.
 *
 * The world is kept as one small search tree (BLAS) per chunk, built when the game hands over a
 * chunk (new or changed), and one top tree (TLAS) over all of them, rebuilt every frame around
 * the camera. OpenGL and Vulkan take turns on the CPU for now (each finishes before the other
 * starts), which is simple and safe.
 *
 * Called by the game through VkBridge by reflection, with plain Java values only.
 */
public final class VkLive extends VkBase {
    public static final int SLOTS = 1024;
    private static final int OUTPUTS = 7; // light, sky/fog, albedo, extra, geometry A, depth, geometry B
    private static final int[] FORMATS = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT};
    private static final int[] GL_FORMATS = {GL30C.GL_RGBA16F, GL30C.GL_RGBA16F, GL30C.GL_RGBA16F, GL30C.GL_RGBA16F, GL30C.GL_RGBA16F, GL30C.GL_R32F, GL30C.GL_RGBA16F};
    private static final int INSTANCES = SLOTS * 2; // per chunk: solid blocks, and water/ice
    private static final int MOB_INDEX = INSTANCES;  // the mobs' tree comes after every chunk's
    private static final int MAX_MOB_BOXES = 4096, MAX_SKINS = 128;

    private final boolean windows = Platform.get() == Platform.WINDOWS;
    private int width, height;
    private final long[] outImage = new long[OUTPUTS], outView = new long[OUTPUTS];
    private final int[] glTex = new int[OUTPUTS], glMem = new int[OUTPUTS];
    private long atlasImage, atlasView, cloudImage, cloudView, sampler, pbrSImage, pbrSView, pbrNImage, pbrNView;
    private long setLayout, pipeLayout, module, pipeline, pool, set, queries;
    private long[] params, lightBuf, lumBuf, chunkTable, instBuf, tlas, tlasScratch, chunkScratch;
    private long tlasScratchAddr, chunkScratchAddr, chunkScratchSize;
    private double nsPerTick;
    private int[] blockInfo;
    private float[] blockBoxes;

    /**
     * Per chunk slot and part (index slot * 2 + 0 solid, + 1 water/ice): {geometry buffer, memory, BLAS buffer,
     * BLAS memory, BLAS handle, BLAS address, triangle table address, first cutout triangle}, or null.
     */
    private final long[][] slot = new long[INSTANCES][];
    private final int[] slotX = new int[SLOTS], slotZ = new int[SLOTS];
    private VkCommandBuffer pending;               // chunk builds recorded but not yet submitted
    // chunks are turned into triangles on a worker thread, so the game doesn't wait for it
    private final java.util.concurrent.ExecutorService mesher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BetaPT chunk mesher");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> meshed = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final int[] seq = new int[SLOTS]; // newest request per slot: older meshes arriving late are dropped
    // mobs: one tree rebuilt every frame from this frame's boxes
    private long[] mobGeo, mobBlas, mobScratch;
    private long mobBlasHandle, mobBlasAddr, mobScratchAddr, mobGeoAddr;
    private int mobQuads;
    private long skinImage, skinView;
    private final List<long[]> retired = new ArrayList<>(); // chunk resources to free once the GPU is done with them

    private VkLive() {}

    // ------------------------------------------------------------------ setup

    /**
     * in: width, height (the path tracer's resolution), atlas (int[] ARGB of terrain.png), atlasW, atlasH,
     * clouds (int[] ARGB, 256 x 256), blockInfo (BlockTable's table). Call on the render thread.
     */
    public static Object open(Map<String, Object> in) {
        VkLive v = new VkLive();
        try {
            v.start(in);
            return v;
        } catch (Throwable t) {
            v.close();
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    /** OpenGL textures holding the outputs (light, sky/fog, albedo, extra, geometry A, depth, geometry B). */
    public int[] glTextures() { return glTex.clone(); }
    public Map<String, Object> info() { return out; }

    private void start(Map<String, Object> in) throws Exception {
        width = (Integer) in.get("width");
        height = (Integer) in.get("height");
        blockInfo = (int[]) in.get("blockInfo");
        blockBoxes = (float[]) in.get("blockBox");

        GLCapabilities caps;
        try { caps = GL.getCapabilities(); } catch (IllegalStateException e) { caps = GL.createCapabilities(); }
        if (!caps.GL_EXT_memory_object || !(windows ? caps.GL_EXT_memory_object_win32 : caps.GL_EXT_memory_object_fd))
            throw new IllegalStateException("OpenGL can't share memory with Vulkan (GL_EXT_memory_object missing)");
        byte[] uuid = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (GL11C.glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT) > 0) {
                ByteBuffer u = stack.malloc(EXTMemoryObject.GL_UUID_SIZE_EXT);
                EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, u);
                uuid = new byte[EXTMemoryObject.GL_UUID_SIZE_EXT];
                u.get(uuid);
            }
        }
        if (Boolean.getBoolean("betapt.vk.anyDevice")) uuid = null;
        // GPU-side hand-off (semaphores) when both sides support it; otherwise the CPU waits as before
        boolean glSem = caps.GL_EXT_semaphore && (windows ? caps.GL_EXT_semaphore_win32 : caps.GL_EXT_semaphore_fd)
                && !Boolean.getBoolean("betapt.vk.cpuSync");
        String semExt = windows ? KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME
                : KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
        if (glSem) optionalExtensions = new String[]{semExt};
        createDevice(uuid, windows ? KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME : KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
        System.out.println("[BetaPT] Vulkan tracer on " + out.get("device") + ", " + out.get("vram") + " video memory; extra ray tracing hardware: " + out.get("rtExtras"));
        out.put("glRenderer", GL11C.glGetString(GL11C.GL_RENDERER));
        if (glSem && optionalEnabled.contains(semExt)) {
            try { createSemaphores(); }
            catch (RuntimeException e) { glDone = 0; out.put("syncError", e.toString()); } // fall back to the CPU wait
        }
        out.put("sync", glDone != 0 ? "GPU semaphores" : "CPU wait");

        // ---- the six shared output images ----
        int handleType = windows ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        int prevTex = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        for (int i = 0; i < OUTPUTS; i++) {
            outImage[i] = image(width, height, FORMATS[i], VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, handleType);
            outView[i] = imageView(outImage[i], FORMATS[i]);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glMem[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
                EXTMemoryObject.glMemoryObjectParameterivEXT(glMem[i], EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, stack.ints(GL11C.GL_TRUE));
                if (windows) {
                    PointerBuffer handle = stack.mallocPointer(1);
                    check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device, VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                            .memory(lastImageMemory).handleType(handleType), handle), "vkGetMemoryWin32HandleKHR");
                    EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(glMem[i], lastImageSize, EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle.get(0));
                } else {
                    IntBuffer fd = stack.mallocInt(1);
                    check(KHRExternalMemoryFd.vkGetMemoryFdKHR(device, VkMemoryGetFdInfoKHR.calloc(stack).sType$Default()
                            .memory(lastImageMemory).handleType(handleType), fd), "vkGetMemoryFdKHR");
                    EXTMemoryObjectFD.glImportMemoryFdEXT(glMem[i], lastImageSize, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.get(0));
                }
                glTex[i] = GL11C.glGenTextures();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTex[i]);
                EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, GL_FORMATS[i], width, height, glMem[i], 0);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            }
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex);
        int err = GL11C.glGetError();
        if (err != 0) throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(err) + " importing the Vulkan images");

        // ---- textures: Beta's block atlas and clouds ----
        int aw = (Integer) in.get("atlasW"), ah = (Integer) in.get("atlasH");
        atlasImage = sampledImage(aw, ah, (int[]) in.get("atlas"));
        atlasView = imageView(atlasImage);
        cloudImage = sampledImage(256, 256, (int[]) in.get("clouds"));
        cloudView = imageView(cloudImage);
        // M9: LabPBR maps (atlas-sized) or a 1x1 "none"
        int[] ps = (int[]) in.get("atlasS"), pn = (int[]) in.get("atlasN");
        pbrSImage = ps.length == aw * ah ? sampledImage(aw, ah, ps) : sampledImage(1, 1, ps);
        pbrSView = imageView(pbrSImage);
        pbrNImage = pn.length == aw * ah ? sampledImage(aw, ah, pn) : sampledImage(1, 1, pn);
        pbrNView = imageView(pbrNImage);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, lp), "sampler");
            sampler = lp.get(0);
        }

        // ---- buffers ----
        params = hostBuffer(256, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        lightBuf = hostBuffer(256 * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        int[] lum = in.get("blockLum") instanceof int[] l ? l : new int[1];
        lumBuf = hostBuffer(lum.length * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        MemoryUtil.memIntBuffer(map(lumBuf), lum.length).put(lum);
        unmap(lumBuf);
        chunkTable = fastHostBuffer((INSTANCES + 1) * 16L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        MemoryUtil.memSet(map(chunkTable), 0, (INSTANCES + 1) * 16L); unmap(chunkTable);
        int asInput = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        instBuf = hostBuffer((long) (INSTANCES + 1) * VkAccelerationStructureInstanceKHR.SIZEOF, asInput);
        createTlas();
        chunkScratchSize = 32L << 20;
        chunkScratch = buffer(chunkScratchSize + 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        chunkScratchAddr = (address(chunkScratch[0]) + 255) / 256 * 256;

        createMobs();
        createPipeline();
    }

    /** Buffers and tree for up to MAX_MOB_BOXES mob boxes, and the skin layers, made once. */
    private void createMobs() {
        long quads = MAX_MOB_BOXES * 6L;
        long vBytes = quads * 48, iBytes = quads * 24, nBytes = quads * 2 * ChunkMesher.INTS_PER_TRI * 4;
        mobGeo = fastHostBuffer(vBytes + iBytes + nBytes + 16, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        mobGeoAddr = address(mobGeo[0]);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR info = mobInfo(stack, (int) quads * 2);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, stack.ints((int) quads * 2), sizes);
            mobBlas = buffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(mobBlas[0]).size(sizes.accelerationStructureSize()).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, lp), "mob BLAS");
            mobBlasHandle = lp.get(0);
            mobBlasAddr = vkGetAccelerationStructureDeviceAddressKHR(device, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(mobBlasHandle));
            mobScratch = buffer(sizes.buildScratchSize() + 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            mobScratchAddr = (address(mobScratch[0]) + 255) / 256 * 256;

            // skins: a 64 x 64 x MAX_SKINS array, filled by setSkins
            LongBuffer lp2 = stack.mallocLong(1);
            check(vkCreateImage(device, VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM).extent(e -> e.width(64).height(64).depth(1)).mipLevels(1).arrayLayers(MAX_SKINS)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED), null, lp2), "skin image");
            skinImage = lp2.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, skinImage, req);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, lp2), "skin memory");
            buffers.add(new long[]{VK_NULL_HANDLE, lp2.get(0)});
            check(vkBindImageMemory(device, skinImage, lp2.get(0), 0), "bind skin memory");
            check(vkCreateImageView(device, VkImageViewCreateInfo.calloc(stack).sType$Default().image(skinImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(MAX_SKINS)), null, lp2), "skin view");
            skinView = lp2.get(0);
            VkCommandBuffer cb = beginOneShot();
            skinBarrier(cb, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            endOneShot(cb);
        }
    }

    private void skinBarrier(VkCommandBuffer cb, int from, int to) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default().oldLayout(from).newLayout(to).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(skinImage)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(MAX_SKINS));
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
    }

    // animated atlas tiles waiting to go up with the next frame (setTiles), and the staging buffer they go through
    private int[] tileRects, tilePx;
    private long[] tileStaging;
    private long tileStagingSize;

    /** Animated tiles (water, lava, portals...): rects as x, y, w, h in atlas pixels, then ARGB pixels tile after tile. */
    public void setTiles(int[] rects, int[] argb) {
        tileRects = rects;
        tilePx = argb;
    }

    /** Records this frame's tile copies into the atlas (called after waitFrame, so the staging buffer is free). */
    private void uploadTiles(VkCommandBuffer cb, MemoryStack stack) {
        int[] rects = tileRects, argb = tilePx;
        tileRects = null; tilePx = null;
        if (rects == null || argb == null || rects.length == 0) return;
        long bytes = (long) argb.length * 4;
        if (tileStaging == null || tileStagingSize < bytes) {
            if (tileStaging != null) freeBuffer(tileStaging);
            tileStaging = hostBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            tileStagingSize = bytes;
        }
        ByteBuffer px = MemoryUtil.memByteBuffer(map(tileStaging), (int) bytes);
        for (int c : argb) px.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        unmap(tileStaging);
        int n = rects.length / 4;
        // on the heap: a modpack can have hundreds of animated tiles, more than the small per-call stack holds
        VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(n);
        long off = 0;
        for (int i = 0; i < n; i++) {
            int x = rects[i * 4], y = rects[i * 4 + 1], w = rects[i * 4 + 2], h = rects[i * 4 + 3];
            regions.get(i).bufferOffset(off).imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                    .imageOffset(o -> o.set(x, y, 0)).imageExtent(e -> e.width(w).height(h).depth(1));
            off += (long) w * h * 4;
        }
        imageBarrier(cb, atlasImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        vkCmdCopyBufferToImage(cb, tileStaging[0], atlasImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
        regions.free(); // recorded: the command buffer keeps its own copy
        imageBarrier(cb, atlasImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    /** The mob skins, ARGB, 64 x 64 per layer, layers one after the other (EntityCapture's skin array). */
    public void setSkins(int[] argb, int layers) {
        waitFrame();
        layers = Math.min(layers, MAX_SKINS);
        if (layers <= 0) return;
        long[] staging = hostBuffer(64L * 64 * 4 * layers, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        ByteBuffer px = MemoryUtil.memByteBuffer(map(staging), 64 * 64 * 4 * layers);
        for (int i = 0; i < 64 * 64 * layers; i++) {
            int c = argb[i];
            px.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        unmap(staging);
        int n = layers;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = beginOneShot();
            skinBarrier(cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseArrayLayer(0).layerCount(n))
                    .imageExtent(e -> e.width(64).height(64).depth(1));
            vkCmdCopyBufferToImage(cb, staging[0], skinImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            skinBarrier(cb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            endOneShot(cb);
        }
        freeBuffer(staging);
    }

    private VkAccelerationStructureBuildGeometryInfoKHR mobInfo(MemoryStack stack, int triangles) {
        long quads = MAX_MOB_BOXES * 6L;
        VkAccelerationStructureGeometryKHR.Buffer g = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        g.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(0); // see-through skin pixels: not opaque
        g.get(0).geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                .vertexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(mobGeoAddr)).vertexStride(12)
                .maxVertex((int) quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32)
                .indexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(mobGeoAddr + quads * 48));
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(g);
    }

    /** This frame's mob boxes (24 floats each, positions relative to the camera's block). */
    public void setMobs(float[] boxes, int count, int[] boxEntity) {
        waitFrame(); // the mob buffers are rewritten below
        count = Math.min(count, MAX_MOB_BOXES);
        MobMesher m = new MobMesher(boxes, count, boxEntity);
        mobQuads = m.g.quads;
        if (mobQuads == 0) return;
        long quads = MAX_MOB_BOXES * 6L;
        long ptr = map(mobGeo);
        MemoryUtil.memFloatBuffer(ptr, mobQuads * 12).put(m.g.v, 0, mobQuads * 12);
        MemoryUtil.memIntBuffer(ptr + quads * 48, mobQuads * 6).put(m.g.idx, 0, mobQuads * 6);
        long nOff = (quads * 72 + 15) / 16 * 16;
        MemoryUtil.memIntBuffer(ptr + nOff, mobQuads * 2 * ChunkMesher.INTS_PER_TRI).put(m.g.tri, 0, mobQuads * 2 * ChunkMesher.INTS_PER_TRI);
        unmap(mobGeo);
        ByteBuffer t = MemoryUtil.memByteBuffer(map(chunkTable), (INSTANCES + 1) * 16);
        t.putLong(MOB_INDEX * 16, mobGeoAddr + nOff);
        t.putInt(MOB_INDEX * 16 + 8, 0);
        unmap(chunkTable);
    }

    /** An RGBA8 texture the shader samples, filled from ARGB ints. */
    private long sampledImage(int w, int h, int[] argb) {
        long img = image(w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 0);
        long[] staging = hostBuffer((long) w * h * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        ByteBuffer px = MemoryUtil.memByteBuffer(map(staging), w * h * 4);
        for (int c : argb) px.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        unmap(staging);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = beginOneShot();
            imageBarrier(cb, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e -> e.width(w).height(h).depth(1));
            vkCmdCopyBufferToImage(cb, staging[0], img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            imageBarrier(cb, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            endOneShot(cb);
        }
        freeBuffer(staging);
        return img;
    }

    private void createTlas() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR info = tlasInfo(stack);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, stack.ints(INSTANCES + 1), sizes);
            long[] storage = buffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(storage[0]).size(sizes.accelerationStructureSize()).type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR), null, lp), "TLAS");
            tlas = new long[]{storage[0], storage[1], lp.get(0)};
            tlasScratch = buffer(sizes.buildScratchSize() + 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            tlasScratchAddr = (address(tlasScratch[0]) + 255) / 256 * 256;
        }
    }

    private VkAccelerationStructureBuildGeometryInfoKHR tlasInfo(MemoryStack stack) {
        VkAccelerationStructureGeometryKHR.Buffer g = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        g.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
        g.get(0).geometry().instances().sType$Default().arrayOfPointers(false)
                .data(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(instBuf == null ? 0 : address(instBuf[0])));
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(g);
    }

    private void createPipeline() throws Exception {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int[] types = {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}; // 16: block light levels
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                sizes.get(i).type(types[i]).descriptorCount(1);
            }
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, lp), "descriptor layout");
            setLayout = lp.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)), null, lp), "pipeline layout");
            pipeLayout = lp.get(0);
            ByteBuffer spv;
            try (InputStream s = VkLive.class.getResourceAsStream("/vkpath.spv")) {
                if (s == null) throw new IllegalStateException("vkpath.spv missing");
                byte[] bytes = s.readAllBytes();
                spv = MemoryUtil.memAlloc(bytes.length); // off the 64 KB stack: the shader outgrew it
                spv.put(bytes).flip();
            }
            try {
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv), null, lp), "shader module");
            } finally {
                MemoryUtil.memFree(spv);
            }
            module = lp.get(0);
            VkComputePipelineCreateInfo.Buffer pci = VkComputePipelineCreateInfo.calloc(1, stack);
            pci.get(0).sType$Default().layout(pipeLayout).stage(VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pci, null, lp), "compute pipeline");
            pipeline = lp.get(0);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, lp), "descriptor pool");
            pool = lp.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), lp), "descriptor set");
            set = lp.get(0);

            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(types.length, stack);
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default().pAccelerationStructures(stack.longs(tlas[2]));
            w.get(0).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(types[0]);
            for (int i = 0; i < OUTPUTS; i++) {
                int binding = i < 6 ? 1 + i : 12; // geometry B is binding 12
                w.get(binding).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(outView[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            }
            w.get(7).sType$Default().dstSet(set).dstBinding(7).descriptorCount(1).descriptorType(types[7])
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(params[0]).range(VK_WHOLE_SIZE));
            w.get(8).sType$Default().dstSet(set).dstBinding(8).descriptorCount(1).descriptorType(types[8])
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(lightBuf[0]).range(VK_WHOLE_SIZE));
            w.get(9).sType$Default().dstSet(set).dstBinding(9).descriptorCount(1).descriptorType(types[9])
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(chunkTable[0]).range(VK_WHOLE_SIZE));
            w.get(10).sType$Default().dstSet(set).dstBinding(10).descriptorCount(1).descriptorType(types[10])
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(atlasView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            w.get(14).sType$Default().dstSet(set).dstBinding(14).descriptorCount(1).descriptorType(types[14])
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(pbrSView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            w.get(15).sType$Default().dstSet(set).dstBinding(15).descriptorCount(1).descriptorType(types[15])
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(pbrNView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            w.get(13).sType$Default().dstSet(set).dstBinding(13).descriptorCount(1).descriptorType(types[13])
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(skinView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            w.get(16).sType$Default().dstSet(set).dstBinding(16).descriptorCount(1).descriptorType(types[16])
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(lumBuf[0]).range(VK_WHOLE_SIZE));
            w.get(11).sType$Default().dstSet(set).dstBinding(11).descriptorCount(1).descriptorType(types[11])
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(cloudView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(device, w, null);

            check(vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(4), null, lp), "query pool");
            queries = lp.get(0);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            nsPerTick = props.limits().timestampPeriod();

            VkCommandBuffer cb = beginOneShot();
            for (long img : outImage)
                imageBarrier(cb, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            endOneShot(cb);
        }
    }

    // ------------------------------------------------------------------ chunks

    /**
     * Hands over one chunk: ids and meta as Beta stores them, biome tints per column (x * 16 + z).
     * Its two search trees (solid blocks; water and ice) are built in the next frame's commands.
     */
    /** Leaves: Fast (solid) or Fancy. Chunks handed over after this are meshed that way (the caller re-sends them all). */
    public void setFastLeaves(boolean fast) { fastLeaves = fast; }
    private volatile boolean fastLeaves;

    /**
     * Every chunk Vulkan has, by position: {slot, ids, meta, grass, foliage} (copies nobody changes). The mesher reads
     * the blocks just over a chunk's edges from its neighbours here, so a lake or a wall that runs on into the next
     * chunk has no faces along the chunk border (water there was a surface: it tinted and mirrored like the top).
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object[]> known = new java.util.concurrent.ConcurrentHashMap<>();
    private final long[] slotKey = new long[SLOTS];
    private final boolean[] slotKnown = new boolean[SLOTS];
    private static long chunkKey(int cx, int cz) { return (long) cx << 32 ^ (cz & 0xFFFFFFFFL); }
    private static final int[][] SIDES = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}; // ChunkMesher's neighbour order: west, east, north, south

    public void setChunk(int s, int cx, int cz, short[] ids, byte[] meta, int[] grass, int[] foliage) {
        // copies: the game keeps changing its own arrays while the worker reads these
        short[] i2 = ids.clone();
        byte[] m2 = meta.clone();
        int[] g2 = grass.clone(), f2 = foliage.clone();
        long key = chunkKey(cx, cz);
        forget(s, key);
        Object[] prev = known.put(key, new Object[]{s, i2, m2, g2, f2});
        slotKey[s] = key;
        slotKnown[s] = true;
        mesh(s, cx, cz, i2, m2, g2, f2);
        // a neighbour meets this chunk for the first time, or the blocks along their shared edge changed: mesh it again
        for (int k = 0; k < 4; k++) {
            Object[] nb = known.get(chunkKey(cx + SIDES[k][0], cz + SIDES[k][1]));
            if (nb == null) continue;
            if (prev != null && !edgeChanged((short[]) prev[1], (byte[]) prev[2], i2, m2, k)) continue;
            mesh((Integer) nb[0], cx + SIDES[k][0], cz + SIDES[k][1], (short[]) nb[1], (byte[]) nb[2], (int[]) nb[3], (int[]) nb[4]);
        }
    }

    /** The slot held another chunk before (or is being dropped): that chunk is gone from the neighbour table. */
    private void forget(int s, long newKey) {
        if (!slotKnown[s] || slotKey[s] == newKey) return;
        Object[] old = known.get(slotKey[s]);
        if (old != null && (Integer) old[0] == s) known.remove(slotKey[s], old);
        slotKnown[s] = false;
    }

    /** The edge this chunk shares with neighbour k (west, east, north, south): any block or meta changed there? */
    private static boolean edgeChanged(short[] a, byte[] am, short[] b, byte[] bm, int k) {
        for (int i = 0; i < 16; i++)
            for (int y = 0; y < 128; y++) {
                int x = k == 0 ? 0 : k == 1 ? 15 : i, z = k == 2 ? 0 : k == 3 ? 15 : i;
                int idx = x << 11 | z << 7 | y;
                if (a[idx] != b[idx] || am[idx >> 1] != bm[idx >> 1]) return true;
            }
        return false;
    }

    private void mesh(int s, int cx, int cz, short[] i2, byte[] m2, int[] g2, int[] f2) {
        int n = ++seq[s];
        boolean fast = fastLeaves;
        mesher.submit(() -> {
            try {
                short[][] nIds = new short[4][];
                byte[][] nMeta = new byte[4][];
                for (int k = 0; k < 4; k++) {
                    Object[] nb = known.get(chunkKey(cx + SIDES[k][0], cz + SIDES[k][1]));
                    if (nb != null) { nIds[k] = (short[]) nb[1]; nMeta[k] = (byte[]) nb[2]; }
                }
                ChunkMesher m = new ChunkMesher(i2, m2, g2, f2, blockInfo, blockBoxes, fast, nIds, nMeta);
                if (n != seq[s]) return; // already replaced: don't make buffers for it
                // the buffers and the empty search trees are made here too, off the game's thread: allocating
                // GPU memory and copying the triangles cost the game 2 to 3 ms a chunk when it did it itself
                Part solid = preparePart(new ChunkMesher.Group[]{m.opaque, m.cutout}, new boolean[]{true, false});
                Part clear = preparePart(new ChunkMesher.Group[]{m.clear}, new boolean[]{false});
                meshed.add(new Object[]{s, n, cx, cz, solid, clear});
            } catch (Throwable t) {
                out.put("meshError", t.toString());
            }
        });
    }

    /** Turns finished meshes into search trees, recorded into the frame's commands (pending). Returns how many. */
    private int takeMeshes() {
        int done = 0;
        Object[] r;
        while ((r = meshed.poll()) != null) {
            int s = (Integer) r[0];
            Part solid = (Part) r[4], clear = (Part) r[5];
            if ((Integer) r[1] != seq[s]) { discard(solid); discard(clear); continue; } // a newer version is on its way
            retire(s);
            slotX[s] = (Integer) r[2];
            slotZ[s] = (Integer) r[3];
            slot[s * 2] = recordPart(solid);
            slot[s * 2 + 1] = recordPart(clear);
            done++;
        }
        return done;
    }

    /** One chunk part made on the mesher thread: its triangles in a GPU buffer and an empty search tree to build. */
    private static final class Part {
        long[] geo, storage;
        long handle, addr, base, iOff, nOff, scratchSize;
        int quads, cutoutBase;
        int[] groupQuads;
        boolean[] opaque;
    }

    /** Never used by the GPU: freed with the next batch (on the game's thread, like every other free). */
    private void discard(Part p) {
        if (p != null) retired.add(new long[]{p.geo[0], p.geo[1], p.storage[0], p.storage[1], p.handle, p.addr});
    }

    /** The geometry descriptions for a part's groups (geometry 0 and 1). Returns the first cutout triangle. */
    private static int describe(MemoryStack stack, int[] groupQuads, boolean[] opaque, long base, long iOff, int quads,
                                VkAccelerationStructureGeometryKHR.Buffer gb, VkAccelerationStructureBuildRangeInfoKHR.Buffer range, IntBuffer counts) {
        int gi = 0, first = 0, cutoutBase = 0;
        for (int k = 0; k < groupQuads.length; k++) {
            int gq = groupQuads[k];
            if (gq > 0) {
                if (gi == 1) cutoutBase = first * 2; // the shader adds this for geometry 1
                gb.get(gi).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(opaque[k] ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
                gb.get(gi).geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                        .vertexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(base)).vertexStride(12)
                        .maxVertex(quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32)
                        .indexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(base + iOff + first * 24L));
                range.get(gi).primitiveCount(gq * 2);
                counts.put(gi, gq * 2);
                gi++;
            }
            first += gq;
        }
        // only the cutout group present: it's geometry 0, but its triangles still sit after the (empty) opaque ones, at 0
        return cutoutBase;
    }

    /** Mesher thread: the part's buffer, filled, and its empty search tree; null if all its groups are empty. */
    private Part preparePart(ChunkMesher.Group[] groups, boolean[] opaque) {
        int quads = 0;
        int[] groupQuads = new int[groups.length];
        for (int k = 0; k < groups.length; k++) { groupQuads[k] = groups[k].quads; quads += groups[k].quads; }
        if (quads == 0) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vBytes = quads * 48L, iBytes = quads * 24L, nBytes = (long) quads * 2 * ChunkMesher.INTS_PER_TRI * 4;
            long iOff = vBytes, nOff = (vBytes + iBytes + 15) / 16 * 16;
            long[] geo = chunkMem().buffer(nOff + nBytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, ChunkMemory.KIND_HOST);
            long ptr = geo[2]; // its block stays mapped
            int q0 = 0;
            for (ChunkMesher.Group g : groups) { // groups one after the other; indices shifted to the shared vertex list
                MemoryUtil.memFloatBuffer(ptr + q0 * 48L, g.quads * 12).put(g.v, 0, g.quads * 12);
                int[] shifted = new int[g.quads * 6];
                for (int i = 0; i < shifted.length; i++) shifted[i] = g.idx[i] + q0 * 4;
                MemoryUtil.memIntBuffer(ptr + iOff + q0 * 24L, g.quads * 6).put(shifted);
                MemoryUtil.memIntBuffer(ptr + nOff + q0 * 2L * ChunkMesher.INTS_PER_TRI * 4, g.quads * 2 * ChunkMesher.INTS_PER_TRI)
                        .put(g.tri, 0, g.quads * 2 * ChunkMesher.INTS_PER_TRI);
                q0 += g.quads;
            }
            long base = address(geo[0]);

            int nGeo = 0;
            for (int gq : groupQuads) if (gq > 0) nGeo++;
            VkAccelerationStructureGeometryKHR.Buffer gb = VkAccelerationStructureGeometryKHR.calloc(nGeo, stack);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(nGeo, stack);
            IntBuffer counts = stack.mallocInt(nGeo);
            int cutoutBase = describe(stack, groupQuads, opaque, base, iOff, quads, gb, range, counts);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(nGeo).pGeometries(gb);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), counts, sizes);
            long[] storage = chunkMem().buffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, ChunkMemory.KIND_DEVICE);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(storage[0]).size(sizes.accelerationStructureSize()).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, lp), "BLAS");
            Part p = new Part();
            p.geo = geo; p.storage = storage; p.handle = lp.get(0);
            p.addr = vkGetAccelerationStructureDeviceAddressKHR(device, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(p.handle));
            p.base = base; p.iOff = iOff; p.nOff = nOff; p.scratchSize = sizes.buildScratchSize();
            p.quads = quads; p.cutoutBase = cutoutBase; p.groupQuads = groupQuads; p.opaque = opaque;
            return p;
        }
    }

    /** Game thread: records the part's tree build into this frame's commands. Returns the slot entry, or null. */
    private long[] recordPart(Part p) {
        if (p == null) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int nGeo = 0;
            for (int gq : p.groupQuads) if (gq > 0) nGeo++;
            VkAccelerationStructureGeometryKHR.Buffer gb = VkAccelerationStructureGeometryKHR.calloc(nGeo, stack);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(nGeo, stack);
            IntBuffer counts = stack.mallocInt(nGeo);
            describe(stack, p.groupQuads, p.opaque, p.base, p.iOff, p.quads, gb, range, counts);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(nGeo).pGeometries(gb);
            long scratchAddr = chunkScratchAddr;
            if (p.scratchSize > chunkScratchSize) {
                long[] big = buffer(p.scratchSize + 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                retired.add(new long[]{big[0], big[1], 0});
                scratchAddr = (address(big[0]) + 255) / 256 * 256;
            }
            info.get(0).dstAccelerationStructure(p.handle).scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratchAddr));
            if (pending == null) pending = beginOneShot();
            else { // builds share the scratch space: each waits for the one before
                VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
                mb.get(0).sType$Default().srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                vkCmdPipelineBarrier(pending, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, mb, null, null);
            }
            vkCmdBuildAccelerationStructuresKHR(pending, info, stack.mallocPointer(1).put(0, range.address()));
            return new long[]{p.geo[0], p.geo[1], p.storage[0], p.storage[1], p.handle, p.addr, p.base + p.nOff, p.cutoutBase};
        }
    }

    /** Removes a chunk (unloaded). */
    public void dropChunk(int s) {
        forget(s, Long.MIN_VALUE);
        seq[s]++; // any mesh still on its way for it is stale
        retire(s);
    }

    private void retire(int s) {
        for (int part = 0; part < 2; part++) {
            long[] r = slot[s * 2 + part];
            if (r == null) continue;
            retired.add(r);
            slot[s * 2 + part] = null;
        }
    }

    /**
     * Frees chunk trees replaced in earlier frames. Called right after waitFrame(): the last frame that could
     * still use them is finished, so there's no need to stop the whole GPU (vkDeviceWaitIdle) any more.
     */
    private void freeRetired() {
        for (long[] r : retired) {
            if (r.length >= 6) {
                vkDestroyAccelerationStructureKHR(device, r[4], null);
                release(r[2], r[3]);
            }
            release(r[0], r[1]);
        }
        retired.clear();
    }

    /** Chunk buffers come from a few big blocks of memory (see ChunkMemory). */
    private volatile ChunkMemory chunkMemory;
    private ChunkMemory chunkMem() {
        ChunkMemory m = chunkMemory;
        if (m == null) synchronized (this) {
            if (chunkMemory == null) {
                final int host = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                chunkMemory = new ChunkMemory(device, (bits, kind) -> switch (kind) {
                    case ChunkMemory.KIND_HOST -> memoryType(bits, host | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, host);
                    case ChunkMemory.KIND_DEVICE -> memoryType(bits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                    default -> memoryType(bits, host);
                });
            }
            m = chunkMemory;
        }
        return m;
    }

    /** Frees a chunk buffer, pooled or not. */
    private void release(long buffer, long memory) {
        if (buffer != 0 && chunkMemory != null && chunkMemory.owns(buffer)) chunkMemory.free(buffer);
        else freeBuffer(new long[]{buffer, memory});
    }

    /** The table the shader reads: where each chunk part's triangle info is. */
    private void writeChunkTable() {
        // table the shader reads: where each chunk part's triangle info is
        ByteBuffer t = MemoryUtil.memByteBuffer(map(chunkTable), (INSTANCES + 1) * 16);
        for (int i = 0; i < INSTANCES; i++) {
            long[] r = slot[i];
            t.putLong(i * 16, r == null ? 0 : r[6]);
            t.putInt(i * 16 + 8, r == null ? 0 : (int) r[7]);
            t.putInt(i * 16 + 12, 0);
        }
        unmap(chunkTable);
        out.put("chunks", count());
        if (chunkMemory != null) out.put("chunkMemory", chunkMemory.info());
    }

    private int count() {
        int n = 0;
        for (long[] r : slot) if (r != null) n++;
        return n;
    }

    // ------------------------------------------------------------------ frame

    /**
     * One frame. f: 44 floats (11 vec4, see vkpath.comp Params), iv: 8 ints (resolution, frame, light
     * count, camera block, debug mask), lights: light count * 4 floats. Returns the GPU time in ms.
     */
    public double trace(float[] f, int[] iv, float[] lights) {
        waitFrame(); // last frame's Vulkan work is done: its buffers can be rewritten
        freeRetired(); // chunk trees replaced last frame: their last user is done now
        if (glDone == 0) GL11C.glFinish(); // no semaphores: OpenGL must be done reading last frame's images
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // new and changed chunks: their trees are built at the start of this frame's own commands (no CPU wait)
            VkCommandBuffer cb = beginOneShot();
            vkCmdResetQueryPool(cb, queries, 0, 4);
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, 0); // GPU time includes the chunk builds
            pending = cb;
            int meshedNow = takeMeshes();
            pending = null;
            out.put("meshedThisFrame", meshedNow);
            if (meshedNow > 0) {
                VkMemoryBarrier.Buffer built = VkMemoryBarrier.calloc(1, stack);
                built.get(0).sType$Default().srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
                vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, built, null, null);
            }
            writeChunkTable();
            uploadTiles(cb, stack);
            ByteBuffer p = MemoryUtil.memByteBuffer(map(params), 256);
            for (int i = 0; i < 44; i++) p.putFloat(i * 4, f[i]);
            for (int i = 0; i < Math.min(iv.length, 12); i++) p.putInt(176 + i * 4, iv[i]);
            // effects: Params.fx (4 floats) and Params.ifx (4 ints) after the first 224 bytes
            for (int i = 44; i < Math.min(f.length, 48); i++) p.putFloat(224 + (i - 44) * 4, f[i]);
            for (int i = 12; i < Math.min(iv.length, 16); i++) p.putInt(240 + (i - 12) * 4, iv[i]);
            unmap(params);
            int nl = Math.min(lights.length / 4, 256);
            if (nl > 0) { MemoryUtil.memFloatBuffer(map(lightBuf), nl * 4).put(lights, 0, nl * 4); unmap(lightBuf); }

            // the top tree: every chunk, moved so the camera's block is at the origin (as in the OpenGL tracer)
            int camX = iv[4], camY = iv[5], camZ = iv[6];
            long ip = map(instBuf);
            int n = 0;
            for (int i = 0; i < INSTANCES; i++) {
                long[] r = slot[i];
                if (r == null) continue;
                int s = i / 2;
                VkAccelerationStructureInstanceKHR inst = VkAccelerationStructureInstanceKHR.create(ip + (long) n * VkAccelerationStructureInstanceKHR.SIZEOF);
                VkTransformMatrixKHR m = inst.transform();
                for (int k = 0; k < 12; k++) m.matrix(k, 0f);
                m.matrix(0, 1f); m.matrix(5, 1f); m.matrix(10, 1f);
                m.matrix(3, slotX[s] * 16 - camX); m.matrix(7, -camY); m.matrix(11, slotZ[s] * 16 - camZ);
                inst.instanceCustomIndex(i).mask((i & 1) == 0 ? 1 : 2).instanceShaderBindingTableRecordOffset(0) // mask: solid 1, water/ice 2
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(r[5]);
                n++;
            }
            if (mobQuads > 0) { // the mobs: already relative to the camera's block
                VkAccelerationStructureInstanceKHR inst = VkAccelerationStructureInstanceKHR.create(ip + (long) n * VkAccelerationStructureInstanceKHR.SIZEOF);
                VkTransformMatrixKHR m = inst.transform();
                for (int k = 0; k < 12; k++) m.matrix(k, 0f);
                m.matrix(0, 1f); m.matrix(5, 1f); m.matrix(10, 1f);
                inst.instanceCustomIndex(MOB_INDEX).mask(1 | 4).instanceShaderBindingTableRecordOffset(0) // mask: solid 1, mobs only 4 (the hand check)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR).accelerationStructureReference(mobBlasAddr);
                n++;
            }
            unmap(instBuf);

            if (mobQuads > 0) { // this frame's mob tree, then the top tree that includes it
                VkAccelerationStructureBuildGeometryInfoKHR.Buffer mi = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
                mi.put(0, mobInfo(stack, mobQuads * 2));
                mi.get(0).dstAccelerationStructure(mobBlasHandle).scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(mobScratchAddr));
                VkAccelerationStructureBuildRangeInfoKHR.Buffer mr = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
                mr.get(0).primitiveCount(mobQuads * 2);
                vkCmdBuildAccelerationStructuresKHR(cb, mi, stack.mallocPointer(1).put(0, mr.address()));
                VkMemoryBarrier.Buffer mob = VkMemoryBarrier.calloc(1, stack);
                mob.get(0).sType$Default().srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR).dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0, mob, null, null);
            }
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer bi = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            bi.put(0, tlasInfo(stack));
            bi.get(0).dstAccelerationStructure(tlas[2]).scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(tlasScratchAddr));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(n);
            vkCmdBuildAccelerationStructuresKHR(cb, bi, stack.mallocPointer(1).put(0, range.address()));
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
            mb.get(0).sType$Default().srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR).dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries, 1);
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout, 0, stack.longs(set), null);
            vkCmdDispatch(cb, (width + 7) / 8, (height + 7) / 8, 1);
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, 2);
            VkMemoryBarrier.Buffer done = VkMemoryBarrier.calloc(1, stack);
            done.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, done, null, null);
            out.put("instances", n);
            if (glDone == 0) {
                endOneShot(cb);
                readTimes();
                return lastGpuMs;
            }
            // GPU hand-off: OpenGL signals "done with the pictures", Vulkan waits for that on the GPU, traces,
            // signals "pictures ready", and OpenGL's next commands wait for that. The CPU carries on.
            check(vkEndCommandBuffer(cb), "end");
            int n7 = OUTPUTS;
            IntBuffer texs = stack.mallocInt(n7), layouts = stack.mallocInt(n7);
            for (int i = 0; i < n7; i++) { texs.put(i, glTex[i]); layouts.put(i, EXTSemaphore.GL_LAYOUT_GENERAL_EXT); }
            EXTSemaphore.glSignalSemaphoreEXT(glDone, stack.mallocInt(0), texs, layouts);
            GL11C.glFlush(); // the signal has to reach the GPU before Vulkan waits on it
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(vkWaitGl)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                    .pCommandBuffers(stack.pointers(cb)).pSignalSemaphores(stack.longs(vkSignalGl)), frameFence), "submit");
            frameCb = cb;
            EXTSemaphore.glWaitSemaphoreEXT(glReady, stack.mallocInt(0), texs, layouts);
            return lastGpuMs; // last frame's: this one's isn't measured yet
        }
    }

    // ---- GPU hand-off between OpenGL and Vulkan ----
    private long vkWaitGl, vkSignalGl, frameFence; // Vulkan's ends of the two semaphores, and this frame's fence
    private int glDone, glReady;                   // OpenGL's ends
    private VkCommandBuffer frameCb;               // the frame in flight, if any
    private double lastGpuMs;

    private void createSemaphores() {
        int ht = windows ? VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT : VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] vk = new long[2];
            int[] gl = new int[2];
            for (int k = 0; k < 2; k++) {
                LongBuffer lp = stack.mallocLong(1);
                VkExportSemaphoreCreateInfo ex = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default().handleTypes(ht);
                check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(ex.address()), null, lp), "semaphore");
                vk[k] = lp.get(0);
                gl[k] = EXTSemaphore.glGenSemaphoresEXT();
                if (windows) {
                    PointerBuffer h = stack.mallocPointer(1);
                    check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device, VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
                            .sType$Default().semaphore(vk[k]).handleType(ht), h), "semaphore handle");
                    EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(gl[k], EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, h.get(0));
                } else {
                    IntBuffer fd = stack.mallocInt(1);
                    check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device, VkSemaphoreGetFdInfoKHR.calloc(stack)
                            .sType$Default().semaphore(vk[k]).handleType(ht), fd), "semaphore fd");
                    EXTSemaphoreFD.glImportSemaphoreFdEXT(gl[k], EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd.get(0));
                }
            }
            vkWaitGl = vk[0]; glDone = gl[0];
            vkSignalGl = vk[1]; glReady = gl[1];
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "frame fence");
            frameFence = lp.get(0);
            int err = GL11C.glGetError();
            if (err != 0) throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(err) + " importing the Vulkan semaphores");
        }
    }

    /** Waits for the frame in flight (if any), reads its GPU time and frees its command buffer. */
    private void waitFrame() {
        if (frameCb == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkWaitForFences(device, stack.longs(frameFence), true, 60_000_000_000L), "frame wait");
            vkResetFences(device, stack.longs(frameFence));
        }
        vkFreeCommandBuffers(device, commandPool, frameCb);
        frameCb = null;
        readTimes();
    }

    private void readTimes() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer ts = stack.mallocLong(3);
            check(vkGetQueryPoolResults(device, queries, 0, 3, ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "timestamps");
            out.put("tlasMs", (ts.get(1) - ts.get(0)) * nsPerTick / 1e6);
            lastGpuMs = (ts.get(2) - ts.get(0)) * nsPerTick / 1e6;
        }
    }

    public void close() {
        mesher.shutdownNow();
        try { mesher.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (device != null) {
            for (Object[] r; (r = meshed.poll()) != null; ) { discard((Part) r[4]); discard((Part) r[5]); }
            vkDeviceWaitIdle(device);
            if (frameCb != null) { vkFreeCommandBuffers(device, commandPool, frameCb); frameCb = null; }
            if (frameFence != 0) vkDestroyFence(device, frameFence, null);
            for (long sem : new long[]{vkWaitGl, vkSignalGl}) if (sem != 0) vkDestroySemaphore(device, sem, null);
            if (glDone != 0) EXTSemaphore.glDeleteSemaphoresEXT(glDone);
            if (glReady != 0) EXTSemaphore.glDeleteSemaphoresEXT(glReady);
            if (mobBlasHandle != 0) vkDestroyAccelerationStructureKHR(device, mobBlasHandle, null);
            if (skinView != 0) vkDestroyImageView(device, skinView, null);
            if (skinImage != 0) vkDestroyImage(device, skinImage, null);
            if (pending != null) { try { endOneShot(pending); } catch (Throwable ignored) {} pending = null; }
            for (int s = 0; s < SLOTS; s++) dropChunk(s);
            for (long[] r : retired) if (r.length >= 6) vkDestroyAccelerationStructureKHR(device, r[4], null);
            retired.clear();
            if (tileStaging != null) { freeBuffer(tileStaging); tileStaging = null; }
            if (tlas != null) vkDestroyAccelerationStructureKHR(device, tlas[2], null);
            if (queries != 0) vkDestroyQueryPool(device, queries, null);
            if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (module != 0) vkDestroyShaderModule(device, module, null);
            if (pipeLayout != 0) vkDestroyPipelineLayout(device, pipeLayout, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            for (long v : new long[]{atlasView, cloudView, pbrSView, pbrNView}) if (v != 0) vkDestroyImageView(device, v, null);
            for (long i : new long[]{atlasImage, cloudImage, pbrSImage, pbrNImage}) if (i != 0) vkDestroyImage(device, i, null);
            for (int i = 0; i < OUTPUTS; i++) {
                if (outView[i] != 0) vkDestroyImageView(device, outView[i], null);
                if (outImage[i] != 0) vkDestroyImage(device, outImage[i], null);
            }
        }
        for (int i = 0; i < OUTPUTS; i++) {
            if (glTex[i] != 0) GL11C.glDeleteTextures(glTex[i]);
            if (glMem[i] != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(glMem[i]);
            glTex[i] = glMem[i] = 0;
        }
        if (chunkMemory != null && device != null) { chunkMemory.close(); chunkMemory = null; } // the pooled chunk buffers and their blocks
        cleanup(); // frees every buffer and memory, the device and the instance
        device = null;
    }
}
