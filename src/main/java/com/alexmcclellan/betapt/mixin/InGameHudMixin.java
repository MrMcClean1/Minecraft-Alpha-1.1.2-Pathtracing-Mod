package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.BetaPT;
import com.alexmcclellan.betapt.gl.GlProbe;
import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds our status lines to the F3 debug screen.
 *
 * Runtime.getRuntime() is first called inside the "if (debugHud)" block of
 * GameGui.render(), so injecting there means our text only draws while F3 is open.
 */
@Mixin(GameGui.class)
public abstract class InGameHudMixin extends GuiElement {
    @Shadow private Minecraft minecraft;

    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = "Ljava/lang/Runtime;getRuntime()Ljava/lang/Runtime;", ordinal = 0)
    )
    private void betapt$drawDebugLines(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
        TextRenderer text = this.minecraft.textRenderer;
        int ok = 0x55FF55, bad = 0xFF5555, info = 0xE0E0E0;
        int y = 100;
        this.drawString(text, "Beta Path Tracer " + BetaPT.version, 2, y, info);
        this.drawString(text, "GL " + GlProbe.glVersion, 2, y += 10, info);
        this.drawString(text, GlProbe.renderer, 2, y += 10, info);
        this.drawString(text, "GL 4.3: " + yesNo(GlProbe.hasGl43) + "  GL 4.5: " + yesNo(GlProbe.hasGl45),
                2, y += 10, GlProbe.hasGl43 ? ok : bad);
        this.drawString(text, "Compute test: " + GlProbe.computeResult,
                2, y += 10, GlProbe.computeOk ? ok : bad);
        PathTracer pt = PathTracer.INSTANCE;
        String mode = pt.broken ? "path tracer FAILED, see log" : pt.enabled ? "path tracer" : "vanilla";
        this.drawString(text, "Renderer: " + mode + " (" + org.lwjgl.input.Keyboard.getKeyName(com.alexmcclellan.betapt.config.BetaPTKeys.TOGGLE.keyCode) + " to switch)", 2, y += 10, pt.broken ? bad : info);
        if (pt.active()) {
            this.drawString(text, String.format("Frame %.2f ms, our GPU pass %.3f ms, %dx%d, waited %.2f ms for GPU",
                    pt.frameMs, pt.gpuMs(), this.minecraft.width, this.minecraft.height, pt.fenceWaitMs), 2, y += 10, info);
            this.drawString(text, String.format("Voxels: %d/1024 chunks on GPU, %d uploads this frame, %d total",
                    pt.voxels.loadedSlots, pt.voxels.uploadsLastFrame, pt.voxels.totalUploads), 2, y += 10, info);
            String v = switch (pt.view) {
                case PATH -> pt.vkActive ? "path traced on RT hardware (Vulkan)" : "path traced (OpenGL)";
                case FLAT -> "flat (M3 look)";
                case MAP_SURFACE -> "debug map, surface";
                case MAP_CUTAWAY -> "debug map, cutaway at eye level";
                case VULKAN -> "path traced on RT hardware (Vulkan)";
            };
            this.drawString(text, "View: " + v + " (" + org.lwjgl.input.Keyboard.getKeyName(com.alexmcclellan.betapt.config.BetaPTKeys.VIEW.keyCode) + " to switch), " + pt.voxels.lightCount + " light blocks nearby", 2, y += 10, info);
            this.drawString(text, "Denoiser: " + (!pt.denoise ? "off" : pt.denoiserMode == PathTracer.DENOISER_ADVANCED ? "advanced" : "standard") + " (" + org.lwjgl.input.Keyboard.getKeyName(com.alexmcclellan.betapt.config.BetaPTKeys.DENOISER.keyCode) + " to switch), look: "
                    + Math.round(pt.realism * 100) + "% realistic (" + org.lwjgl.input.Keyboard.getKeyName(com.alexmcclellan.betapt.config.BetaPTKeys.LOOK.keyCode) + "), brightness " + Math.round(pt.brightness * 100) + "%", 2, y += 10, info);
            if (pt.vkActive || com.alexmcclellan.betapt.render.VkBridge.liveError != null)
                this.drawString(text, com.alexmcclellan.betapt.render.VkBridge.liveError != null
                        ? "Vulkan: " + com.alexmcclellan.betapt.render.VkBridge.liveError
                        : String.format("Vulkan %.2f ms GPU, %.2f ms CPU, chunks %d sent / %d built", com.alexmcclellan.betapt.render.VkBridge.liveGpuMs,
                        com.alexmcclellan.betapt.render.VkBridge.liveCpuMs, com.alexmcclellan.betapt.render.VkBridge.chunksSent,
                        com.alexmcclellan.betapt.render.VkBridge.chunksMeshed), 2, y += 10, info);
            if (pt.pathView()) this.drawString(text, "GPU stages: " + pt.stageSummary(), 2, y += 10, info);
            if (pt.pathView()) this.drawString(text, "Upscaler: " + (pt.upscaler == PathTracer.UPSCALER_OFF ? "off (native)" : (pt.fsrActive ? "AMD FSR 3.1" : "basic")
                    + ", render scale " + Math.round(pt.renderScale * 100) + "%") + ", trace distance " + Math.round(pt.maxDist) + " blocks"
                    + (pt.traceDistance == 0 ? " (auto)" : ""), 2, y += 10, info);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void betapt$drawAutotestBanner(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
        if (com.alexmcclellan.betapt.test.AutoTest.running() || com.alexmcclellan.betapt.test.ModTest.running()) {
            String s = com.alexmcclellan.betapt.test.AutoTest.running() ? com.alexmcclellan.betapt.test.AutoTest.status() : com.alexmcclellan.betapt.test.ModTest.status();
            this.drawString(this.minecraft.textRenderer, s, 2, 2 + 10 * 20, 0xFFFF55);
        }
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "NO";
    }
}
