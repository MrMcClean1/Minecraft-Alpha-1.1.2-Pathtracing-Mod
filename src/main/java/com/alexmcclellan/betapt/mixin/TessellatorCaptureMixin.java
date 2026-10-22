package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.BlockCapture;
import net.minecraft.client.render.vertex.Tesselator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While BlockCapture is listening, note each vertex a block renderer emits (position and texture coordinate). */
@Mixin(Tesselator.class)
public abstract class TessellatorCaptureMixin {
    @Shadow private double u;
    @Shadow private double v;

    @Inject(method = "vertex(DDD)V", at = @At("HEAD"))
    private void betapt$capture(double x, double y, double z, CallbackInfo ci) {
        if (BlockCapture.listening) BlockCapture.vertex(x, y, z, u, v);
    }
}
