package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particles (smoke, splashes, block bits, bubbles...) take their brightness from the traced light
 * where they are instead of Beta's light level. Glowing ones (flame, lava, portal) have their own
 * brightness method and stay full bright, as in Beta.
 */
@Mixin(Entity.class)
public abstract class EntityLightMixin {
    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private void betapt$particleLight(float tickDelta, CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof net.minecraft.client.entity.particle.Particle)) return;
        PathTracer pt = PathTracer.INSTANCE;
        if (!pt.particleLightActive()) return;
        Entity e = (Entity) (Object) this;
        float b = pt.lightAt(e.x, e.y, e.z);
        if (b >= 0) cir.setReturnValue(b);
    }
}
