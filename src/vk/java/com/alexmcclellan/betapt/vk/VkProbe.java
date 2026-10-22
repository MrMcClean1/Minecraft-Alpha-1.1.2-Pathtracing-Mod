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
 * M8b: can the GPU's ray-tracing hardware beat our OpenGL grid walk?
 *
 * A one-off Vulkan program, run beside the game (its own Vulkan device, nothing on screen):
 * it builds a hardware acceleration structure ("BVH", a tree of boxes the RT units search)
 * from the blocks around the test spot, traces the same kinds of rays as the path tracer at
 * the same resolution, times them with GPU timestamps, and saves a picture to check that
 * it saw the right world.
 *
 * Loaded in its own class loader with LWJGL 3 (the game runs on LWJGL 2, and the two can't
 * share one), and called through {@link #run(Map)} with plain Java values only.
 */
public final class VkProbe extends VkBase {
    private VkProbe() {}

    /**
     * in: ids (byte[], x + sx*(z + sz*y)), sx, sy, sz, solid (boolean[256]), colors (int[256]),
     *     cam (float[17]: pos xyz, forward xyz, right xyz, up xyz, tanHalfFovY, sun xyz, maxDist), width, height,
     *     frames (timing repeats), png (output path or null)
     * out: device, error, triangles, meshMs, buildMs, bvhMB, ms.cameraSun, ms.full
     */
    public static Map<String, Object> run(Map<String, Object> in) {
        VkProbe p = new VkProbe();
        try {
            p.go(in);
        } catch (Throwable t) {
            p.out.put("error", t.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            p.out.put("stack", sw.toString());
        } finally {
            p.cleanup();
        }
        return p.out;
    }

    private void go(Map<String, Object> in) throws Exception {
        byte[] ids = (byte[]) in.get("ids");
        int sx = (Integer) in.get("sx"), sy = (Integer) in.get("sy"), sz = (Integer) in.get("sz");
        float[] cam = (float[]) in.get("cam");
        int w = (Integer) in.get("width"), h = (Integer) in.get("height"), frames = (Integer) in.get("frames");
        String png = (String) in.get("png");

        long t0 = System.nanoTime();
        Mesher mesh = new Mesher(ids, sx, sy, sz, (boolean[]) in.get("solid"), (int[]) in.get("colors"));
        out.put("triangles", mesh.faces * 2);
        out.put("meshMs", (System.nanoTime() - t0) / 1e6);

        createDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ---- geometry buffers (read by the BVH build) ----
            int asInput = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            long[] vbuf = hostBuffer((long) mesh.verts.length * 4, asInput);
            MemoryUtil.memFloatBuffer(map(vbuf), mesh.verts.length).put(mesh.verts); unmap(vbuf);
            long[] ibuf = hostBuffer((long) mesh.indices.length * 4, asInput);
            MemoryUtil.memIntBuffer(map(ibuf), mesh.indices.length).put(mesh.indices); unmap(ibuf);
            long[] infoBuf = hostBuffer((long) mesh.triInfo.length * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            MemoryUtil.memIntBuffer(map(infoBuf), mesh.triInfo.length).put(mesh.triInfo); unmap(infoBuf);

            // ---- bottom level: the triangles ----
            long t1 = System.nanoTime();
            VkAccelerationStructureGeometryKHR.Buffer geo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geo.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geo.get(0).geometry().triangles().sType$Default()
                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(address(vbuf[0])))
                    .vertexStride(12)
                    .maxVertex(mesh.faces * 4 - 1)
                    .indexType(VK_INDEX_TYPE_UINT32)
                    .indexData(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(address(ibuf[0])));
            long[] blas = buildAs(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, geo, mesh.faces * 2);

            // ---- top level: one instance of it ----
            long[] instBuf = hostBuffer(VkAccelerationStructureInstanceKHR.SIZEOF, asInput);
            VkAccelerationStructureInstanceKHR inst = VkAccelerationStructureInstanceKHR.create(map(instBuf));
            inst.transform().matrix(stack.floats(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0));
            inst.instanceCustomIndex(0).mask(0xFF).instanceShaderBindingTableRecordOffset(0)
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(blas[3]);

            unmap(instBuf);
            VkAccelerationStructureGeometryKHR.Buffer tgeo = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            tgeo.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            tgeo.get(0).geometry().instances().sType$Default().arrayOfPointers(false)
                    .data(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(address(instBuf[0])));
            long[] tlas = buildAs(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, tgeo, 1);
            out.put("buildMs", (System.nanoTime() - t1) / 1e6);
            out.put("bvhMB", (blas[4] + tlas[4]) / 1048576.0);

            // ---- output image + readback buffer ----
            long image = image(w, h);
            long view = imageView(image);
            long[] readback = hostBuffer((long) w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);

            // ---- pipeline ----
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(3, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, lp), "descriptor layout");
            long setLayout = lp.get(0);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(96);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, lp), "pipeline layout");
            long pipeLayout = lp.get(0);
            ByteBuffer spv;
            try (InputStream s = VkProbe.class.getResourceAsStream("/vkprobe.spv")) {
                if (s == null) throw new IllegalStateException("vkprobe.spv missing");
                byte[] bytes = s.readAllBytes();
                spv = stack.malloc(bytes.length);
                spv.put(bytes).flip();
            }
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv), null, lp), "shader module");
            long module = lp.get(0);
            VkComputePipelineCreateInfo.Buffer pci = VkComputePipelineCreateInfo.calloc(1, stack);
            pci.get(0).sType$Default().layout(pipeLayout).stage(VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pci, null, lp), "compute pipeline");
            long pipeline = lp.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, lp), "descriptor pool");
            long pool = lp.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), lp), "descriptor set");
            long set = lp.get(0);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default().pAccelerationStructures(stack.longs(tlas[2]));
            writes.get(0).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(infoBuf[0]).offset(0).range(VK_WHOLE_SIZE));
            vkUpdateDescriptorSets(device, writes, null);

            int slots = 2 * frames;
            check(vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots), null, lp), "query pool");
            long queries = lp.get(0);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            double nsPerTick = props.limits().timestampPeriod();

            // image to GENERAL once
            VkCommandBuffer cb = beginOneShot();
            imageBarrier(cb, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            endOneShot(cb);

            // ---- time each mode ----
            // All frames of a mode go in one submission, back to back, so the GPU stays at full clock
            // (one frame per submission lets it idle and clock down between frames). Each frame gets
            // its own pair of timestamps; the median frame is reported.
            ByteBuffer pushData = stack.malloc(96);
            int[] modes = {2, 0, 1};
            String[] names = {"ms.camera", "ms.cameraSun", "ms.full"};
            VkMemoryBarrier.Buffer serial = VkMemoryBarrier.calloc(1, stack);
            serial.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            for (int mi = 0; mi < modes.length; mi++) {
                int mode = modes[mi];
                cb = beginOneShot();
                vkCmdResetQueryPool(cb, queries, 0, slots);
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout, 0, stack.longs(set), null);
                for (int f = -10; f < frames; f++) { // 10 untimed frames first to warm up clocks and caches
                    pushData.clear();
                    pushData.putFloat(cam[0]).putFloat(cam[1]).putFloat(cam[2]).putFloat(cam[12]);
                    pushData.putFloat(cam[3]).putFloat(cam[4]).putFloat(cam[5]).putFloat(cam[16]);
                    pushData.putFloat(cam[6]).putFloat(cam[7]).putFloat(cam[8]).putFloat(0);
                    pushData.putFloat(cam[9]).putFloat(cam[10]).putFloat(cam[11]).putFloat(0);
                    pushData.putFloat(cam[13]).putFloat(cam[14]).putFloat(cam[15]).putFloat(0);
                    pushData.putInt(mode).putInt(f + 10).putInt(w).putInt(h);
                    pushData.flip();
                    vkCmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
                    if (f >= 0) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, 2 * f);
                    vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
                    if (f >= 0) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, 2 * f + 1);
                    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, serial, null, null);
                }
                endOneShot(cb);
                LongBuffer ts = stack.mallocLong(slots);
                check(vkGetQueryPoolResults(device, queries, 0, slots, ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "timestamps");
                double[] ms = new double[frames];
                for (int f = 0; f < frames; f++) ms[f] = (ts.get(2 * f + 1) - ts.get(2 * f)) * nsPerTick / 1e6;
                java.util.Arrays.sort(ms);
                out.put(names[mi], ms[frames / 2]);
            }

            // ---- picture of the last (full) frame ----
            if (png != null) {
                cb = beginOneShot();
                imageBarrier(cb, image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                        .imageExtent(e -> e.width(w).height(h).depth(1));
                vkCmdCopyImageToBuffer(cb, image, VK_IMAGE_LAYOUT_GENERAL, readback[0], region);
                endOneShot(cb);
                ByteBuffer px = MemoryUtil.memByteBuffer(map(readback), w * h * 4);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 4;
                        img.setRGB(x, y, ((px.get(i) & 0xff) << 16) | ((px.get(i + 1) & 0xff) << 8) | (px.get(i + 2) & 0xff));
                    }
                unmap(readback);
                ImageIO.write(img, "png", new File(png));
            }

            vkDeviceWaitIdle(device);
            vkDestroyQueryPool(device, queries, null);
            vkDestroyDescriptorPool(device, pool, null);
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyShaderModule(device, module, null);
            vkDestroyPipelineLayout(device, pipeLayout, null);
            vkDestroyDescriptorSetLayout(device, setLayout, null);
            vkDestroyImageView(device, view, null);
            vkDestroyImage(device, image, null);
            vkDestroyAccelerationStructureKHR(device, tlas[2], null);
            vkDestroyAccelerationStructureKHR(device, blas[2], null);
        }
    }

}
