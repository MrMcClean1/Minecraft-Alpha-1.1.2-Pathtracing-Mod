package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.EntityCapture;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** M6: marks where one entity's boxes start and end, so they can be grouped per mob. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "render(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"))
    private void betapt$entityStart(Entity entity, double x, double y, double z, float yaw, float tickDelta, CallbackInfo ci) {
        EntityCapture.beginEntity(entity, tickDelta, x, y, z);
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("RETURN"))
    private void betapt$entityEnd(Entity entity, double x, double y, double z, float yaw, float tickDelta, CallbackInfo ci) {
        EntityCapture.endEntity();
    }
}
