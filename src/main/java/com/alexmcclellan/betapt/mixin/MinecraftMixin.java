package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.gl.GlProbe;
import com.alexmcclellan.betapt.test.AutoTest;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Minecraft.init() right after Display.create() has made the OpenGL context.
 *
 * We target the first line after window creation (the getRunDirectory() call)
 * rather than Display.create() itself, because init() calls Display.create() twice
 * (once normally, once in a retry path) and we only want to run once.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "init", at = @At("HEAD"))
    private void betapt$beforeWindowSetup(CallbackInfo ci) {
        if (com.alexmcclellan.betapt.config.BetaPTConfig.borderless) {
            com.alexmcclellan.betapt.render.Borderless.apply(((Minecraft) (Object) this).canvas);
        }
    }

    @Inject(
            method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getWorkingDirectory()Ljava/io/File;")
    )
    private void betapt$afterDisplayCreate(CallbackInfo ci) {
        GlProbe.probe();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void betapt$tick(CallbackInfo ci) {
        AutoTest.onTick((Minecraft) (Object) this);
        com.alexmcclellan.betapt.test.ModTest.onTick((Minecraft) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void betapt$afterTick(CallbackInfo ci) {
        AutoTest.afterTick((Minecraft) (Object) this);
        com.alexmcclellan.betapt.test.ModTest.afterTick((Minecraft) (Object) this);
    }

    /** The frame's lighting updates: a few milliseconds' worth, not up to 500 big ones (ClientLighting). */
    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;doLightUpdates()Z"))
    private boolean betapt$lighting(net.minecraft.world.World world) {
        return com.alexmcclellan.betapt.render.ClientLighting.run(world);
    }
}
