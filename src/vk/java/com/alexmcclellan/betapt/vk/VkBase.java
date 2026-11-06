package com.alexmcclellan.betapt.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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
 * Shared Vulkan plumbing for the probe and the live tracer: device, memory, acceleration
 * structures (the hardware's search trees), images and one-shot command buffers.
 */
abstract class VkBase {
    protected VkInstance instance;
    protected VkPhysicalDevice physical;
    protected VkDevice device;
    protected VkQueue queue;
    protected int queueFamily;
    protected long commandPool;
    protected VkPhysicalDeviceMemoryProperties memProps;
    // {buffer, memory}; synchronized: chunk buffers are made on the mesher thread
    protected final List<long[]> buffers = java.util.Collections.synchronizedList(new ArrayList<>());
    protected final Map<String, Object> out = new HashMap<>();

    protected static final boolean DEBUG = Boolean.getBoolean("betapt.vk.debug");
    protected static void check(int r, String what) {
        if (DEBUG) System.err.println("[vk] " + what + " -> " + r);
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    // ---------------------------------------------------------------- device

    protected void createDevice() { createDevice(null); }

    /** Device extensions turned on only if the chosen GPU has them (set before createDevice); optionalEnabled = the ones it had. */
    protected String[] optionalExtensions = {};
    protected final java.util.Set<String> optionalEnabled = new java.util.HashSet<>();

    /**
     * @param uuid  if not null, only the Vulkan device with this UUID (the one OpenGL is running on,
     *              so the two can share memory); extra device extensions follow
     */
    protected void createDevice(byte[] uuid, String... extraExtensions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("BetaPT RT probe")).apiVersion(VK_API_VERSION_1_2);
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkCreateInstance(VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app), null, pp), "vkCreateInstance");
            instance = new VkInstance(pp.get(0), VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app));

            IntBuffer n = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = stack.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            StringBuilder seen = new StringBuilder();
            VkPhysicalDevice best = null;
            int bestScore = -1;
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice d = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(d, p);
                boolean rt = hasExtensions(d, KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                        KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME, KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
                seen.append(p.deviceNameString()).append(rt ? " (ray tracing)" : " (no ray tracing)").append("; ");
                if (uuid != null && !java.util.Arrays.equals(uuid, deviceUuid(d))) continue; // not the GPU OpenGL is on
                if (extraExtensions.length > 0 && !hasExtensions(d, extraExtensions)) continue;
                int score = !rt ? -1 : p.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 3 : p.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2 : 1;
                if (score > bestScore) { bestScore = score; best = d; }
            }
            out.put("devices", seen.toString());
            if (best == null || bestScore < 0) throw new IllegalStateException("no Vulkan device with ray queries: " + seen);
            physical = best;
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, p);
            out.put("device", p.deviceNameString() + ", Vulkan " + VK_API_VERSION_MAJOR(p.apiVersion()) + "." + VK_API_VERSION_MINOR(p.apiVersion())
                    + "." + VK_API_VERSION_PATCH(p.apiVersion()) + ", driver " + p.driverVersion());
            memProps = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physical, memProps);
            long vram = 0;
            for (int h = 0; h < memProps.memoryHeapCount(); h++)
                if ((memProps.memoryHeaps(h).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) vram = Math.max(vram, memProps.memoryHeaps(h).size());
            out.put("vram", (vram >> 20) + " MB");
            // hardware features past the basic ray queries, for the log (what a newer card could use)
            String[][] extras = {
                    {"VK_EXT_opacity_micromap", "opacity micromaps"},
                    {"VK_NV_ray_tracing_invocation_reorder", "shader execution reordering"},
                    {"VK_EXT_ray_tracing_invocation_reorder", "shader execution reordering (EXT)"},
                    {"VK_KHR_ray_tracing_position_fetch", "ray hit position fetch"},
                    {"VK_NV_cluster_acceleration_structure", "cluster acceleration structures (Mega Geometry)"},
                    {"VK_NV_ray_tracing_linear_swept_spheres", "swept spheres"},
                    {"VK_KHR_cooperative_matrix", "cooperative matrices (tensor cores)"},
                    {"VK_NV_cooperative_vector", "cooperative vectors"},
                    {"VK_NV_low_latency2", "Reflex low latency"}};
            StringBuilder has = new StringBuilder();
            for (String[] e : extras) if (hasExtensions(physical, e[0])) has.append(has.length() > 0 ? ", " : "").append(e[1]);
            out.put("rtExtras", has.length() > 0 ? has.toString() : "none");

            vkGetPhysicalDeviceQueueFamilyProperties(physical, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, n, qf);
            queueFamily = -1;
            for (int i = 0; i < qf.capacity(); i++)
                if ((qf.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && qf.get(i).timestampValidBits() > 0) { queueFamily = i; break; }
            if (queueFamily < 0) throw new IllegalStateException("no compute queue with timestamps");

            VkPhysicalDeviceRayQueryFeaturesKHR rq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().rayQuery(true);
            VkPhysicalDeviceAccelerationStructureFeaturesKHR as = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                    .sType$Default().accelerationStructure(true).pNext(rq.address());
            VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                    .bufferDeviceAddress(true).pNext(as.address());
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f));
            optionalEnabled.clear();
            for (String e : optionalExtensions) if (hasExtensions(physical, e)) optionalEnabled.add(e);
            PointerBuffer exts = stack.mallocPointer(3 + extraExtensions.length + optionalEnabled.size());
            exts.put(stack.UTF8(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME))
                    .put(stack.UTF8(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME))
                    .put(stack.UTF8(KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME));
            for (String e : extraExtensions) exts.put(stack.UTF8(e));
            for (String e : optionalEnabled) exts.put(stack.UTF8(e));
            exts.flip();
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physical, supported);
            VkPhysicalDeviceFeatures enable = VkPhysicalDeviceFeatures.calloc(stack).shaderInt64(supported.shaderInt64());
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(v12.address()).pEnabledFeatures(enable)
                    .pQueueCreateInfos(qci).ppEnabledExtensionNames(exts);
            check(vkCreateDevice(physical, dci, null, pp), "vkCreateDevice");
            device = new VkDevice(pp.get(0), physical, dci, VK_API_VERSION_1_2);
            vkGetDeviceQueue(device, queueFamily, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(queueFamily).flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT), null, lp), "command pool");
            commandPool = lp.get(0);
        }
    }

    protected static byte[] deviceUuid(VkPhysicalDevice d) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            vkGetPhysicalDeviceProperties2(d, VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address()));
            byte[] u = new byte[VK_UUID_SIZE];
            id.deviceUUID().get(u);
            return u;
        }
    }

    protected static boolean hasExtensions(VkPhysicalDevice d, String... names) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(d, (ByteBuffer) null, n, null);
            VkExtensionProperties.Buffer ext = VkExtensionProperties.malloc(n.get(0), stack);
            vkEnumerateDeviceExtensionProperties(d, (ByteBuffer) null, n, ext);
            outer:
            for (String name : names) {
                for (int i = 0; i < ext.capacity(); i++) if (ext.get(i).extensionNameString().equals(name)) continue outer;
                return false;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- memory

    /** First memory type with all of 'preferred', else one with all of 'required'. */
    protected int memoryType(int bits, int preferred, int required) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & preferred) == preferred) return i;
        return memoryType(bits, required);
    }

    protected int memoryType(int bits, int flags) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & flags) == flags) return i;
        throw new IllegalStateException("no memory type for flags " + flags);
    }

    /** {buffer, memory} */
    protected long[] buffer(long size, int usage, int memFlags) { return buffer(size, usage, memFlags, memFlags); }

    /** A buffer the CPU writes and the GPU reads often: in video memory the CPU can reach, if the card has it. */
    protected long[] fastHostBuffer(long size, int usage) {
        int host = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        return buffer(size, usage, host | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, host);
    }

    protected long[] buffer(long size, int usage, int memFlags, int fallbackFlags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(Math.max(16, size)).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lp), "vkCreateBuffer");
            long buf = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buf, req);
            VkMemoryAllocateFlagsInfo flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
                    .flags((usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0 ? VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT : 0);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(flags.address())
                    .allocationSize(req.size()).memoryTypeIndex(memoryType(req.memoryTypeBits(), memFlags, fallbackFlags)), null, lp), "vkAllocateMemory");
            long mem = lp.get(0);
            check(vkBindBufferMemory(device, buf, mem, 0), "vkBindBufferMemory");
            long[] r = {buf, mem};
            buffers.add(r);
            return r;
        }
    }

    /** Frees a buffer from buffer() before the end (scene rebuilds). */
    protected void freeBuffer(long[] b) {
        if (b == null) return;
        buffers.removeIf(x -> x[0] == b[0] && x[1] == b[1]);
        if (b[0] != VK_NULL_HANDLE) vkDestroyBuffer(device, b[0], null);
        vkFreeMemory(device, b[1], null);
    }

    protected long[] hostBuffer(long size, int usage) {
        return buffer(size, usage, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    protected long map(long[] b) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkMapMemory(device, b[1], 0, VK_WHOLE_SIZE, 0, pp), "vkMapMemory");
            return pp.get(0);
        }
    }

    protected void unmap(long[] b) { vkUnmapMemory(device, b[1]); }

    protected long address(long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
        }
    }

    // ---------------------------------------------------------------- acceleration structures

    /** Builds and waits. Returns {buffer, memory, handle, deviceAddress, sizeBytes}. */
    protected long[] buildAs(int type, VkAccelerationStructureGeometryKHR.Buffer geo, int primitives) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).sType$Default().type(type).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geo.remaining()).pGeometries(geo); // pGeometries doesn't set the count
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), stack.ints(primitives), sizes);

            long[] storage = buffer(sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(storage[0]).size(sizes.accelerationStructureSize()).type(type), null, lp), "vkCreateAccelerationStructureKHR");
            long handle = lp.get(0);

            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(asProps.address());
            vkGetPhysicalDeviceProperties2(physical, p2);
            long align = Math.max(1, asProps.minAccelerationStructureScratchOffsetAlignment());
            long[] scratch = buffer(sizes.buildScratchSize() + align,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            long scratchAddr = (address(scratch[0]) + align - 1) / align * align;

            info.get(0).dstAccelerationStructure(handle).scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratchAddr));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(primitives).primitiveOffset(0).firstVertex(0).transformOffset(0);
            VkCommandBuffer cb = beginOneShot();
            vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range.address()));
            endOneShot(cb);
            freeBuffer(scratch); // only needed during the build

            long addr = vkGetAccelerationStructureDeviceAddressKHR(device, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType$Default().accelerationStructure(handle));
            return new long[]{storage[0], storage[1], handle, addr, sizes.accelerationStructureSize()};
        }
    }

    // ---------------------------------------------------------------- images and commands

    protected long image(int w, int h) { return image(w, h, 0); }

    /** Size of the memory behind the last image() call (needed to share it). */
    protected long lastImageMemory, lastImageSize;

    /** @param exportType 0, or the external handle type so OpenGL can use the same memory */
    protected long image(int w, int h, int exportType) {
        return image(w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, exportType);
    }

    protected long image(int w, int h, int format, int usage, int exportType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            VkExternalMemoryImageCreateInfo ext = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(exportType);
            check(vkCreateImage(device, VkImageCreateInfo.calloc(stack).sType$Default().pNext(exportType != 0 ? ext.address() : 0).imageType(VK_IMAGE_TYPE_2D)
                    .format(format).extent(e -> e.width(w).height(h).depth(1)).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED), null, lp), "vkCreateImage");
            long img = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, img, req);
            // shared memory: one allocation just for this image ("dedicated"), marked exportable
            VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(img);
            VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default().handleTypes(exportType).pNext(dedicated.address());
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(exportType != 0 ? export.address() : 0)
                    .allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, lp), "image memory");
            buffers.add(new long[]{VK_NULL_HANDLE, lp.get(0)});
            lastImageMemory = lp.get(0);
            lastImageSize = req.size();
            check(vkBindImageMemory(device, img, lp.get(0), 0), "vkBindImageMemory");
            return img;
        }
    }

    protected long imageView(long image) { return imageView(image, VK_FORMAT_R8G8B8A8_UNORM); }

    protected long imageView(long image, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateImageView(device, VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)), null, lp), "vkCreateImageView");
            return lp.get(0);
        }
    }

    protected void imageBarrier(VkCommandBuffer cb, long image, int from, int to, int srcAccess, int dstAccess, int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default().oldLayout(from).newLayout(to).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
        }
    }

    protected VkCommandBuffer beginOneShot() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "command buffer");
            VkCommandBuffer cb = new VkCommandBuffer(pp.get(0), device);
            check(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
            return cb;
        }
    }

    protected void endOneShot(VkCommandBuffer cb) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkEndCommandBuffer(cb), "end");
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "fence");
            long fence = lp.get(0);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb)), fence), "submit");
            check(vkWaitForFences(device, lp, true, 60_000_000_000L), "wait");
            vkDestroyFence(device, fence, null);
            vkFreeCommandBuffers(device, commandPool, cb);
        }
    }

    protected void cleanup() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            for (long[] b : buffers) {
                if (b[0] != VK_NULL_HANDLE) vkDestroyBuffer(device, b[0], null);
                vkFreeMemory(device, b[1], null);
            }
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            vkDestroyDevice(device, null);
        }
        if (memProps != null) memProps.free();
        if (instance != null) vkDestroyInstance(instance, null);
    }
}
