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

    /** The test: the file .minecraft/classicbots-test/showlist keeps the list up without the key (for a picture of it). */
    private static final boolean SHOW_LIST = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "classicbots-test/showlist").isFile();

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

    /**
     * Hold Tab in single player: the bots, laid out like the multiplayer player list (centred at the top, one row
     * each: name, what it is doing, health and distance). Closed unless Tab is held; the Bots page can turn it off.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void betapt$botList(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
        Minecraft mc = this.minecraft;
        boolean held = com.alexmcclellan.betapt.bots.ActiveBots.flag("tabList") && org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_TAB);
        if (mc.world == null || mc.player == null || mc.world.isMultiplayer || mc.screen != null || !(held || SHOW_LIST && com.alexmcclellan.betapt.bots.BotTest.ON)) return;
        java.util.List<com.alexmcclellan.betapt.bots.ActiveBots.Line> bots = com.alexmcclellan.betapt.bots.ActiveBots.list(mc.world); // this mod's bots, or the modpack's copy
        TextRenderer text = mc.textRenderer;
        int sw = new net.minecraft.client.render.Window(mc.width, mc.height).getWidth();
        String title = bots.isEmpty() ? "No bots in this world (/bot spawn)" : "Bots (" + bots.size() + ")";
        int nameW = 0, rightW = 0, statusW = 0;
        String[] rights = new String[bots.size()];
        for (int i = 0; i < bots.size(); i++) {
            com.alexmcclellan.betapt.bots.ActiveBots.Line b = bots.get(i);
            int hp = Math.max(0, b.health);
            rights[i] = hp / 2 + (hp % 2 == 1 ? ".5" : "") + "/10  " + (int) distOf(mc.player, b.entity) + " m";
            nameW = Math.max(nameW, text.getWidth(b.name));
            rightW = Math.max(rightW, text.getWidth(rights[i]));
            statusW = Math.max(statusW, text.getWidth(b.status));
        }
        statusW = Math.max(0, Math.min(statusW, sw - 40 - nameW - rightW - 24));
        int total = Math.max(text.getWidth(title) + 8, nameW + statusW + rightW + 24);
        int x0 = sw / 2 - total / 2, y0 = 10, rows = bots.size();
        this.fill(x0 - 1, y0 - 1, x0 + total + 1, y0 + 11 + rows * 9 + 1, 0x80000000);
        this.drawString(text, title, sw / 2 - text.getWidth(title) / 2, y0 + 1, 0xFFFFFF);
        for (int i = 0; i < rows; i++) {
            com.alexmcclellan.betapt.bots.ActiveBots.Line b = bots.get(i);
            int y = y0 + 11 + i * 9, hp = Math.max(0, b.health);
            this.fill(x0, y, x0 + total, y + 8, 0x20FFFFFF);
            this.drawString(text, b.name, x0 + 2, y, 0xFFFFFF);
            String st = b.status.replaceAll(",? ?health \\d+/20\\.?", "").replaceAll("\\.$", ""); // health is in its own column
            if (text.getWidth(st) > statusW) { while (st.length() > 1 && text.getWidth(st + "...") > statusW) st = st.substring(0, st.length() - 1); st = statusW > 12 ? st + "..." : ""; }
            this.drawString(text, st, x0 + nameW + 10, y, 0xAAAAAA);
            this.drawString(text, rights[i], x0 + total - 2 - text.getWidth(rights[i]), y, hp <= 6 ? 0xFF5555 : hp <= 12 ? 0xFFFF55 : 0x55FF55);
        }
    }
    private static double distOf(net.minecraft.entity.Entity a, net.minecraft.entity.Entity b) { return a.distanceTo(b); }
}
