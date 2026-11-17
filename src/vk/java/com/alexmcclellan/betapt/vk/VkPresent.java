package com.alexmcclellan.betapt.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Shows Minecraft's finished frame (world and GUI, its own framebuffer) through a Vulkan swap chain, for NVIDIA Frame
 * Generation: DLSS-G only works on frames a swap chain made through Streamline presents. The swap chain sits on a
 * child window covering the game window's inside. The child is disabled, so Windows sends its mouse input to the game
 * window, and it never takes the keyboard. Minecraft's own OpenGL picture is still swapped underneath it.
 * One per VkLive (the kept device's surface and swap chain go when the tracer closes, and the game's picture shows again).
 */
final class VkPresent {
    private final VkLive v;
    String status = "";

    private static NvFeatures.Fn createWindowEx, destroyWindow, getClientRect, setWindowPos, windowFromDC, getModuleHandle, wglGetCurrentDC;
    private static boolean loaded;

    private long parent, child, surface, swapchain;
    private long[] images = new long[0], done = new long[0];
    private int sw, sh, swapFormat;
    private boolean swapVsync, stale;
    private long[] shared; // VkLive.sharedImage: Minecraft's frame, copied from OpenGL
    private long acquired, fence;
    private VkCommandBuffer cb;

    VkPresent(VkLive v) { this.v = v; }

    private static void load() {
        if (loaded) return;
        SharedLibrary user32 = org.lwjgl.system.APIUtil.apiCreateLibrary("user32.dll");
        SharedLibrary kernel32 = org.lwjgl.system.APIUtil.apiCreateLibrary("kernel32.dll");
        SharedLibrary gl = org.lwjgl.system.APIUtil.apiCreateLibrary("opengl32.dll");
        createWindowEx = new NvFeatures.Fn(user32.getFunctionAddress("CreateWindowExW"), "IPPIIIIIPPPP", true);
        destroyWindow = new NvFeatures.Fn(user32.getFunctionAddress("DestroyWindow"), "P");
        getClientRect = new NvFeatures.Fn(user32.getFunctionAddress("GetClientRect"), "PP");
        setWindowPos = new NvFeatures.Fn(user32.getFunctionAddress("SetWindowPos"), "PPIIIII");
        windowFromDC = new NvFeatures.Fn(user32.getFunctionAddress("WindowFromDC"), "P", true);
        getModuleHandle = new NvFeatures.Fn(kernel32.getFunctionAddress("GetModuleHandleW"), "P", true);
        wglGetCurrentDC = new NvFeatures.Fn(gl.getFunctionAddress("wglGetCurrentDC"), "", true);
        loaded = true;
    }

    private static final int WS_CHILD = 0x40000000, WS_VISIBLE = 0x10000000, WS_DISABLED = 0x08000000, SS_BLACKRECT = 0x4,
            WS_EX_NOPARENTNOTIFY = 0x4, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;

    /**
     * Shows OpenGL texture glTex (w x h, RGBA8, bottom row first) in the window. vsync: wait for the screen's refresh.
     * Returns false if it couldn't (status says why); then the caller stops asking until the tracer restarts.
     */
    boolean present(int glTex, int w, int h, boolean vsync) {
        try {
            if (!window()) return false;
            if (swapchain == 0 || stale || vsync != swapVsync) { if (!swapchain(vsync)) return true; } // minimized: nothing to show
            if (shared == null || shared[4] != w || shared[5] != h) {
                waitDone();
                if (shared != null) v.freeShared(shared);
                shared = v.sharedImage(w, h, VK_FORMAT_R8G8B8A8_UNORM, GL11C.GL_RGBA8);
            }
            waitDone();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer idx = stack.mallocInt(1);
                int r = vkAcquireNextImageKHR(v.device, swapchain, 1_000_000_000L, acquired, VK_NULL_HANDLE, idx);
                if (r == VK_ERROR_OUT_OF_DATE_KHR) { stale = true; return true; }
                if (r == VK_SUBOPTIMAL_KHR) stale = true;
                else VkBase.check(r, "vkAcquireNextImageKHR");
                int i = idx.get(0);
                GL43C.glCopyImageSubData(glTex, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, (int) shared[7], GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
                cb = v.beginOneShot();
                v.imageBarrier(cb, images[i], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkMemoryBarrier.Buffer all = VkMemoryBarrier.calloc(1, stack);
                all.get(0).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, all, null, null);
                // OpenGL's rows run bottom-up: the blit turns the picture the right way up (and scales, if the sizes differ)
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.get(0).srcSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                        .dstSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
                blit.get(0).srcOffsets(0).set(0, 0, 0); blit.get(0).srcOffsets(1).set(w, h, 1);
                blit.get(0).dstOffsets(0).set(0, sh, 0); blit.get(0).dstOffsets(1).set(sw, 0, 1);
                vkCmdBlitImage(cb, shared[0], VK_IMAGE_LAYOUT_GENERAL, images[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit,
                        w == sw && h == sh ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
                v.imageBarrier(cb, images[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                VkBase.check(vkEndCommandBuffer(cb), "end");
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cb));
                IntBuffer texs = stack.ints((int) shared[7]), layouts = stack.ints(EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
                boolean sem = v.semaphores();
                if (sem) {
                    EXTSemaphore.glSignalSemaphoreEXT(v.glDone, stack.mallocInt(0), texs, layouts);
                    GL11C.glFlush();
                    submit.waitSemaphoreCount(2).pWaitSemaphores(stack.longs(v.vkWaitGl, acquired))
                            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                            .pSignalSemaphores(stack.longs(v.vkSignalGl, done[i]));
                } else {
                    GL11C.glFinish(); // OpenGL's copy is done
                    submit.waitSemaphoreCount(1).pWaitSemaphores(stack.longs(acquired)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                            .pSignalSemaphores(stack.longs(done[i]));
                }
                VkBase.check(vkQueueSubmit(v.queue, submit, fence), "present submit");
                if (sem) EXTSemaphore.glWaitSemaphoreEXT(v.glReady, stack.mallocInt(0), texs, layouts);
                else waitDone(); // the next frame's copy mustn't overwrite the picture before the blit read it
                if (v.nv != null) v.nv.fgBeforePresent(sw, sh, images.length, swapFormat); // Frame Generation: on, "present start"
                r = vkQueuePresentKHR(v.queue, VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(done[i]))
                        .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(i)));
                if (v.nv != null) v.nv.fgAfterPresent();
                if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) stale = true;
                else VkBase.check(r, "vkQueuePresentKHR");
            }
            return true;
        } catch (Throwable t) {
            status = "Vulkan window stopped: " + t;
            System.out.println("[BetaPT] " + status);
            close();
            return false;
        }
    }

    private void waitDone() {
        if (cb == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBase.check(vkWaitForFences(v.device, stack.longs(fence), true, 10_000_000_000L), "present wait");
            vkResetFences(v.device, stack.longs(fence));
        }
        vkFreeCommandBuffers(v.device, v.commandPool, cb);
        cb = null;
    }

    /** The child window over the game window's inside, made once and kept at its size. */
    private boolean window() {
        load();
        long dc = wglGetCurrentDC.callP();
        long p = dc != 0 ? windowFromDC.callP(dc) : 0;
        if (p == 0) { status = "Vulkan window: no game window"; return false; }
        if (p != parent && child != 0) close(); // the game made a new window (full screen switch)
        parent = p;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long rect = stack.ncalloc(4, 16, 1);
            getClientRect.call(parent, rect);
            int cw = MemoryUtil.memGetInt(rect + 8), ch = MemoryUtil.memGetInt(rect + 12);
            if (child == 0) {
                long hinst = getModuleHandle.callP(0);
                child = createWindowEx.callP(WS_EX_NOPARENTNOTIFY, MemoryUtil.memAddress(stack.UTF16("STATIC")), MemoryUtil.memAddress(stack.UTF16("BetaPT")),
                        WS_CHILD | WS_VISIBLE | WS_DISABLED | SS_BLACKRECT, 0, 0, cw, ch, parent, 0, hinst, 0);
                if (child == 0) { status = "Vulkan window: CreateWindowEx failed"; return false; }
                LongBuffer lp = stack.mallocLong(1);
                VkBase.check(KHRWin32Surface.vkCreateWin32SurfaceKHR(v.instance, VkWin32SurfaceCreateInfoKHR.calloc(stack).sType$Default()
                        .hinstance(hinst).hwnd(child), null, lp), "vkCreateWin32SurfaceKHR");
                surface = lp.get(0);
                IntBuffer ok = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(v.physical, v.queueFamily, surface, ok);
                if (ok.get(0) == 0) { status = "Vulkan window: the tracer's queue can't present"; close(); return false; }
                VkBase.check(vkCreateSemaphore(v.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, lp), "semaphore");
                acquired = lp.get(0);
                VkBase.check(vkCreateFence(v.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "fence");
                fence = lp.get(0);
                status = "Vulkan window: on";
            } else if (cw != sw || ch != sh) {
                setWindowPos.call(child, 0, 0, 0, cw, ch, SWP_NOZORDER | SWP_NOACTIVATE);
                stale = true;
            }
        }
        return true;
    }

    /** (Re)makes the swap chain at the window's size; false while the window has no size (minimized). */
    private boolean swapchain(boolean vsync) {
        if (v.nv != null) v.nv.fgOff(); // NVIDIA: Frame Generation off while the swap chain changes (back on at the next present)
        waitDone();
        vkQueueWaitIdle(v.queue);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            VkBase.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(v.physical, surface, caps), "surface capabilities");
            int w = caps.currentExtent().width(), h = caps.currentExtent().height();
            if (w == 0 || h == 0 || w == -1) return false;
            IntBuffer n = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(v.physical, surface, n, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(n.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(v.physical, surface, n, formats);
            int format = formats.get(0).format(), space = formats.get(0).colorSpace();
            for (int i = 0; i < formats.capacity(); i++)
                if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM || formats.get(i).format() == VK_FORMAT_R8G8B8A8_UNORM) {
                    format = formats.get(i).format(); space = formats.get(i).colorSpace(); break;
                }
            vkGetPhysicalDeviceSurfacePresentModesKHR(v.physical, surface, n, null);
            IntBuffer modes = stack.mallocInt(n.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(v.physical, surface, n, modes);
            int mode = VK_PRESENT_MODE_FIFO_KHR; // always there
            if (!vsync) for (int i = 0; i < modes.capacity(); i++) if (modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
            int count = Math.max(caps.minImageCount(), 3);
            if (caps.maxImageCount() > 0) count = Math.min(count, caps.maxImageCount());
            long old = swapchain;
            LongBuffer lp = stack.mallocLong(1);
            VkBase.check(vkCreateSwapchainKHR(v.device, VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(surface)
                    .minImageCount(count).imageFormat(format).imageColorSpace(space).imageExtent(e -> e.set(w, h)).imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform()).compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(mode).clipped(true)
                    .oldSwapchain(old), null, lp), "vkCreateSwapchainKHR");
            swapchain = lp.get(0);
            if (old != 0) vkDestroySwapchainKHR(v.device, old, null);
            for (long s : done) vkDestroySemaphore(v.device, s, null);
            vkGetSwapchainImagesKHR(v.device, swapchain, n, null);
            LongBuffer imgs = stack.mallocLong(n.get(0));
            vkGetSwapchainImagesKHR(v.device, swapchain, n, imgs);
            images = new long[n.get(0)];
            done = new long[n.get(0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = imgs.get(i);
                VkBase.check(vkCreateSemaphore(v.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, lp), "semaphore");
                done[i] = lp.get(0);
            }
            sw = w; sh = h; swapFormat = format; swapVsync = vsync; stale = false;
            status = "Vulkan window: " + w + "x" + h + (mode == VK_PRESENT_MODE_IMMEDIATE_KHR ? ", no vsync" : ", vsync");
            System.out.println("[BetaPT] " + status);
            return true;
        }
    }

    void close() {
        if (v.nv != null) try { v.nv.fgOff(); } catch (Throwable ignored) {}
        if (v.device != null) {
            try { waitDone(); } catch (Throwable ignored) {}
            vkQueueWaitIdle(v.queue);
            if (swapchain != 0) vkDestroySwapchainKHR(v.device, swapchain, null);
            for (long s : done) vkDestroySemaphore(v.device, s, null);
            if (acquired != 0) vkDestroySemaphore(v.device, acquired, null);
            if (fence != 0) vkDestroyFence(v.device, fence, null);
            if (shared != null) v.freeShared(shared);
            if (surface != 0) vkDestroySurfaceKHR(v.instance, surface, null);
        }
        swapchain = surface = acquired = fence = 0;
        done = new long[0]; images = new long[0]; shared = null;
        if (child != 0) { destroyWindow.call(child); child = 0; }
    }
}
