package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.BlockTable;
import com.alexmcclellan.betapt.render.EntityCapture;
import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks the path tracer traces from their block entity renderer's boxes (BuildCraft's engines and chutes,
 * see EntityCapture.renderBlockEntities): Beta's own drawing of them is skipped while the path tracer is on,
 * or they'd show twice.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {
    @Inject(method = "render(Lnet/minecraft/block/entity/BlockEntity;F)V", at = @At("HEAD"), cancellable = true)
    private void betapt$skipTraced(BlockEntity be, float tickDelta, CallbackInfo ci) {
        if (EntityCapture.active || !PathTracer.INSTANCE.active() || be == null || be.world == null) return;
        if (BlockTable.BER_IDS.contains(be.world.getBlock(be.x, be.y, be.z))) ci.cancel();
    }
}
