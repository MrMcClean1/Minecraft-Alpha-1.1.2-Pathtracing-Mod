package com.mrmcclean.betapt.mixin;

import com.mrmcclean.betapt.render.PathTracer;
import com.mrmcclean.betapt.test.AutoTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.entity.mob.MobEntity;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The core hook: swaps Beta's world rendering for the path tracer.
 *
 * M6 ("hybrid"): Beta's frame runs as normal, except that the parts the path tracer
 * replaces are skipped: the screen clear, the sky, and the terrain passes. Right after
 * Beta sets up its camera (renderWorld), the path tracer draws the world using that exact
 * camera and fills in the depth buffer from its own ray hits. Beta then draws entities,
 * particles, the block outline, rain/snow, clouds and your hand as usual, and they sort
 * correctly in front of or behind path-traced blocks because the depth is real.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow private Minecraft minecraft;
    @Shadow private void applyHurtCam(float tickDelta) {}
    @Shadow private void applyViewBobbing(float tickDelta) {}
    @Unique private static boolean betapt$toggleHeld;
    @Unique private static int betapt$compileFrame;
    @Unique private static long betapt$compileLast;
    @Unique private static double betapt$compileX, betapt$compileY, betapt$compileZ;
    /** Frames slower than this skip Alpha's mesh building while tracing (-Dbetapt.meshTrickleMs, default 12; 0 = never build). */
    @Unique private static final long betapt$TRICKLE_NS = Long.getLong("betapt.meshTrickleMs", 12L) * 1_000_000L;
    @Unique private static boolean betapt$cutawayHeld;
    @Unique private static boolean betapt$denoiseHeld;
    @Unique private static boolean betapt$lookHeld;
    @org.spongepowered.asm.mixin.Unique private static boolean betapt$presetHeld;
    @org.spongepowered.asm.mixin.Unique private static boolean betapt$photoHeld;
    @org.spongepowered.asm.mixin.Unique private static boolean betapt$keyframeHeld;

    /**
     * Autotest cost probe only ("old frame order"): draw the way M4/M5 did, cancelling Beta's
     * frame at its first glViewport, so the probe can compare the two arrangements.
     */
    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V", ordinal = 0), cancellable = true)
    private void betapt$legacyFrame(float tickDelta, CallbackInfo ci) {
        if (PathTracer.INSTANCE.active() && PathTracer.INSTANCE.legacyFrame) {
            PathTracer.INSTANCE.renderFrame(this.minecraft, tickDelta);
            ci.cancel();
        }
    }

    /** The first glClear wipes colour and depth before Beta draws: the path tracer fills both itself. */
    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClear(I)V", ordinal = 0))
    private void betapt$clear(int mask) {
        if (!PathTracer.INSTANCE.active()) GL11.glClear(mask);
    }

    /** Camera matrices are set: trace the world now, with exactly Alpha's camera (bobbing, F5 view and all). */
    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;setupCamera(FI)V", shift = At.Shift.AFTER))
    private void betapt$traceWorld(float tickDelta, CallbackInfo ci) {
        if (!PathTracer.INSTANCE.active()) return;
        // Read this frame's camera matrices BEFORE the dry entity pass so EntityCapture.setView() uses THIS frame's pose,
        // not last frame's (which shifted every traced mob, bot, sign and hand by 1 frame of camera movement).
        com.mrmcclean.betapt.render.Camera cam = com.mrmcclean.betapt.render.Camera.fromGl(this.minecraft.player, tickDelta);
        boolean mobs = PathTracer.INSTANCE.tracesMobs();
        {
            // M6: run Beta's entity pass once "dry" (nothing reaches the screen) to record every mob's
            // boxes for this very frame. Every mob is included, not just ones on screen: an off-screen
            // mob can still cast a shadow into view. Blocks drawn by block entity renderers (BuildCraft's
            // engines) are recorded the same way, whether mobs are traced or not.
            com.mrmcclean.betapt.render.EntityCapture.begin();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            if (mobs) this.minecraft.worldRenderer.renderEntities(this.minecraft.player.getPosition(tickDelta), betapt$ALL, tickDelta);
            com.mrmcclean.betapt.render.EntityCapture.renderBlockEntities(this.minecraft, tickDelta);
            if (mobs && PathTracer.INSTANCE.tracesHand(this.minecraft)) {
                // your hand and held item, recorded the same way (eye space + view bobbing + hurt tilt, as Alpha draws them)
                com.mrmcclean.betapt.render.EntityCapture.beginHand();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                this.applyHurtCam(tickDelta);
                if (this.minecraft.options.viewBobbing) this.applyViewBobbing(tickDelta);
                try {
                    ((net.minecraft.client.render.GameRenderer) (Object) this).itemInHandRenderer.renderHand(tickDelta);
                } finally {
                    GL11.glPopMatrix();
                    com.mrmcclean.betapt.render.EntityCapture.endHand();
                }
            }
            GL11.glPopAttrib();
            com.mrmcclean.betapt.render.EntityCapture.end();
        }
        PathTracer.INSTANCE.renderFrame(this.minecraft, tickDelta, cam);
    }

    /** A "culler" that keeps everything: the dry pass wants off-screen mobs too. */
    @Unique private static final net.minecraft.client.render.Culler betapt$ALL = new net.minecraft.client.render.Culler() {
        @Override public boolean isVisible(net.minecraft.util.math.Box box) { return true; }
        @Override public void prepare(double x, double y, double z) {}
    };

    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/world/WorldRenderer;renderSky(F)V"))
    private void betapt$sky(WorldRenderer wr, float tickDelta) {
        if (!PathTracer.INSTANCE.active()) wr.renderSky(tickDelta);
    }

    /** Beta's entity pass (the real one, after the path tracer). */
    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/world/WorldRenderer;renderEntities(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/client/render/Culler;F)V"))
    private void betapt$entities(WorldRenderer wr, net.minecraft.util.math.Vec3d cameraPos, net.minecraft.client.render.Culler culler, float tickDelta) {
        // The real pass: mob models are skipped (the path tracer drew them); held items, name tags,
        // arrows, dropped items, paintings and anything else not built from model boxes still draw here.
        boolean traced = PathTracer.INSTANCE.active() && PathTracer.INSTANCE.tracesMobs();
        com.mrmcclean.betapt.render.EntityCapture.suppressModels = traced;
        try {
            wr.renderEntities(cameraPos, culler, tickDelta);
        } finally {
            com.mrmcclean.betapt.render.EntityCapture.suppressModels = false;
        }
    }

    /** Beta's terrain passes (solid, then water): skipped, the path tracer drew the world. */
    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/world/WorldRenderer;render(Lnet/minecraft/entity/mob/player/PlayerEntity;ID)I"))
    private int betapt$terrain(WorldRenderer wr, net.minecraft.entity.mob.player.PlayerEntity camera, int pass, double tickDelta) {
        return PathTracer.INSTANCE.active() ? 0 : wr.render(camera, pass, tickDelta);
    }

    /**
     * Beta's chunk meshes aren't drawn while the path tracer is on, so they are built in spare time only: one call
     * every second frame, and only while frames take under 25 ms (a slow PC, or llvmpipe in the tests, skips them as
     * before). Skipping them always saved a little CPU but left every chunk to rebuild at once when F6 turned the
     * tracer off: seconds of a slow, half-drawn world.
     */
    @Redirect(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/world/WorldRenderer;compileChunks(Lnet/minecraft/entity/mob/player/PlayerEntity;Z)Z"))
    private boolean betapt$compile(WorldRenderer wr, net.minecraft.entity.mob.player.PlayerEntity camera, boolean all) {
        long now = System.nanoTime(), frame = now - betapt$compileLast;
        betapt$compileLast = now;
        if (!PathTracer.INSTANCE.active()) return wr.compileChunks(camera, all);
        boolean still = camera != null && Math.abs(camera.x - betapt$compileX) + Math.abs(camera.y - betapt$compileY) + Math.abs(camera.z - betapt$compileZ) < 0.01;
        if (camera != null) { betapt$compileX = camera.x; betapt$compileY = camera.y; betapt$compileZ = camera.z; }
        if (still && frame < betapt$TRICKLE_NS && ++betapt$compileFrame % 8 == 0) wr.compileChunks(camera, false);
        return true; // ends Alpha's loop: never stutter while moving
    }

    @Inject(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(F)V", shift = At.Shift.AFTER)
    )
    private void betapt$afterWorld(float tickDelta, CallbackInfo ci) {
        com.mrmcclean.betapt.config.PtSettings.onDimension(0, "Overworld", false); // Alpha 1.1.2_01 has only the overworld
        // F7 switches between the path tracer and vanilla rendering.
        boolean keysAllowed = this.minecraft.screen == null && !AutoTest.running() && !com.mrmcclean.betapt.test.ModTest.running();
        boolean down = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.TOGGLE.keyCode);
        if (down && !betapt$toggleHeld) PathTracer.INSTANCE.enabled = !PathTracer.INSTANCE.enabled;
        betapt$toggleHeld = down;

        // The view key (Home) cycles the traced view and the two debug maps.
        boolean f8 = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.VIEW.keyCode);
        if (f8 && !betapt$cutawayHeld) {
            PathTracer.View[] views = PathTracer.View.values();
            PathTracer.INSTANCE.view = views[(PathTracer.INSTANCE.view.ordinal() + 1) % views.length];
        }
        betapt$cutawayHeld = f8;

        // F9 turns the denoiser on and off (to compare with the raw grainy image).
        boolean f9 = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.DENOISER.keyCode);
        if (f9 && !betapt$denoiseHeld) PathTracer.INSTANCE.denoise = !PathTracer.INSTANCE.denoise;
        betapt$denoiseHeld = f9;

        // F10 steps the look slider: 0% (Beta) -> 25% (default) -> 50% -> 75% -> 100% (realistic) -> 0%.
        boolean f10 = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.LOOK.keyCode);
        if (f10 && !betapt$lookHeld) {
            float r = Math.round(PathTracer.INSTANCE.realism * 4f) / 4f + 0.25f;
            PathTracer.INSTANCE.realism = r > 1.001f ? 0f : r;
            com.mrmcclean.betapt.config.PtSettings.save();
        }
        betapt$lookHeld = f10;

        // F4 steps through the presets (Off -> Beta -> Faithful -> Realistic -> Off), saved, named on screen
        boolean f4 = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.PRESET.keyCode);
        if (f4 && !betapt$presetHeld) {
            PathTracer.Preset p = PathTracer.INSTANCE.preset();
            PathTracer.Preset[] all = PathTracer.Preset.values();
            PathTracer.Preset next = p == null ? PathTracer.Preset.FAITHFUL : all[(p.ordinal() + 1) % all.length];
            PathTracer.INSTANCE.apply(next);
            com.mrmcclean.betapt.config.PtSettings.save();
            if (this.minecraft.gui != null) this.minecraft.gui.addChatMessage("Path tracer preset: " + next.label
                    + (next == PathTracer.Preset.OFF ? " (Alpha's renderer)" : ""));
        }
        betapt$presetHeld = f4;

        // the photo key opens photo mode (when it is switched on in Effects > Photo, time-lapse...)
        boolean photo = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.PHOTO.keyCode);
        if (photo && !betapt$photoHeld) {
            if (!com.mrmcclean.betapt.render.PhotoMode.enabled) {
                if (this.minecraft.gui != null) this.minecraft.gui.addChatMessage("Photo mode is off: Path Tracer > Effects... > Photo, time-lapse...");
            } else if (PathTracer.INSTANCE.active() && PathTracer.INSTANCE.pathView() && this.minecraft.world != null) {
                this.minecraft.openScreen(new com.mrmcclean.betapt.gui.PhotoScreen());
            }
        }
        betapt$photoHeld = photo;
        // the keyframe key: a time-lapse keyframe where you are (when the time-lapse is switched on)
        boolean kf = keysAllowed && Keyboard.isKeyDown(com.mrmcclean.betapt.config.BetaPTKeys.KEYFRAME.keyCode);
        if (kf && !betapt$keyframeHeld && this.minecraft.gui != null)
            this.minecraft.gui.addChatMessage(com.mrmcclean.betapt.render.TimeLapse.enabled ? com.mrmcclean.betapt.render.TimeLapse.addKey(this.minecraft)
                    : "Time-lapse is off: Path Tracer > Effects... > Photo, time-lapse...");
        betapt$keyframeHeld = kf;

        AutoTest.afterWorld(this.minecraft);
        com.mrmcclean.betapt.test.ModTest.afterWorld(this.minecraft);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void betapt$frameStart(float tickDelta, CallbackInfo ci) {
        com.mrmcclean.betapt.render.TimeLapse.beforeFrame(this.minecraft); // a recording time-lapse sets the camera
        // a test never holds the mouse: the player's cursor stays theirs while it runs behind other windows
        if ((com.mrmcclean.betapt.test.AlphaTest.running() || com.mrmcclean.betapt.test.WorldShots.running()) && org.lwjgl.input.Mouse.isCreated() && org.lwjgl.input.Mouse.isGrabbed())
            org.lwjgl.input.Mouse.setGrabbed(false);
        PathTracer.INSTANCE.beginFrame(); // wait for the previous frame before the mouse is read
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void betapt$afterHud(float tickDelta, CallbackInfo ci) {
        AutoTest.afterHud(this.minecraft);
        com.mrmcclean.betapt.test.ModTest.afterHud(this.minecraft);
        com.mrmcclean.betapt.render.Quality.frame(this.minecraft);
        com.mrmcclean.betapt.render.Screenshots.frame(this.minecraft);
        PathTracer.INSTANCE.endFrame();
    }

    /** Hand traced: Beta doesn't draw it on top (its screen overlays, like being underwater, still draw). */
    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/ItemInHandRenderer;renderHand(F)V"))
    private void betapt$hand(net.minecraft.client.render.ItemInHandRenderer r, float tickDelta) {
        if (!PathTracer.INSTANCE.tracesHand(this.minecraft)) r.renderHand(tickDelta);
    }

    /** Rain and snow: drawn by Beta in the brightness of the traced light where they fall. */
    @Redirect(method = "renderRain", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBrightness(III)F"), require = 0)
    private float betapt$rainLight(net.minecraft.world.World world, int x, int y, int z) {
        PathTracer pt = PathTracer.INSTANCE;
        if (pt.particleLightActive()) {
            float b = pt.lightAt(x + 0.5, y + 0.5, z + 0.5);
            if (b >= 0) return b;
        }
        return world.getBrightness(x, y, z);
    }
}
