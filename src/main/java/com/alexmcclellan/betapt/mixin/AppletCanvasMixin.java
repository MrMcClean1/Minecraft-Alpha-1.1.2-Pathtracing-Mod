package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.Borderless;
import net.minecraft.client.MinecraftApplet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Beta's game canvas stops the game thread when it is removed from its window and
 * starts it when it is added back. Making the window borderless briefly removes and
 * re-adds the canvas, which would shut the game down (that was the first crash), so
 * while Borderless is rebuilding the window those two calls are skipped.
 */
@Mixin(targets = "net.minecraft.client.MinecraftApplet$72207753")
public class AppletCanvasMixin {
    @Redirect(method = "removeNotify", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftApplet;stopThread()V"))
    private void betapt$keepRunningOnRemove(MinecraftApplet applet) {
        if (!Borderless.rebuilding) applet.stopThread();
    }

    @Redirect(method = "addNotify", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftApplet;startThread()V"))
    private void betapt$noSecondThreadOnAdd(MinecraftApplet applet) {
        if (!Borderless.rebuilding) applet.startThread();
    }
}
