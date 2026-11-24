package com.mrmcclean.betapt.mixin;

import com.mrmcclean.betapt.render.BlockTable;
import com.mrmcclean.betapt.render.EntityCapture;
import com.mrmcclean.betapt.render.PathTracer;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
        int id = be.world.getBlock(be.x, be.y, be.z);
        if (!BlockTable.BER_IDS.contains(id)) return;
        if (!BlockTable.SIGN_IDS.contains(id)) { ci.cancel(); return; }
        // a sign keeps Alpha's drawing for its text; its board and post are model boxes, traced, so left out of that drawing
        betapt$wasSuppressed = EntityCapture.suppressModels;
        betapt$sign = true;
        EntityCapture.suppressModels = true;
    }

    @Unique private static boolean betapt$sign, betapt$wasSuppressed;

    @Inject(method = "render(Lnet/minecraft/block/entity/BlockEntity;F)V", at = @At("RETURN"))
    private void betapt$signDone(BlockEntity be, float tickDelta, CallbackInfo ci) {
        if (!betapt$sign) return;
        betapt$sign = false;
        EntityCapture.suppressModels = betapt$wasSuppressed;
    }
}
