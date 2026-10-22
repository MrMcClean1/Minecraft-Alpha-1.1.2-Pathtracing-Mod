package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** M6: with the path tracer on, mobs cast real shadows, so Beta's round fake ones are switched off. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Shadow private void renderShadow(Entity entity, double x, double y, double z, float alpha, float tickDelta) {}

    @Redirect(method = "postRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/entity/EntityRenderer;renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void betapt$shadow(EntityRenderer self, Entity entity, double x, double y, double z, float alpha, float tickDelta) {
        if (!PathTracer.INSTANCE.active() || !PathTracer.INSTANCE.tracesMobs()) this.renderShadow(entity, x, y, z, alpha, tickDelta);
    }
}
