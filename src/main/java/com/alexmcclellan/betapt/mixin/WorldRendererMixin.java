package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.VoxelWorld;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Beta tells its renderer about regions the server sends (whole chunks, multi-block changes, explosions).
 * Listening here keeps the tracer's copy current when a mod (StationAPI) stores chunks its own way.
 * Not the single-block notice (blockUpdate): Beta sends that for every light change too, which made the
 * tracer re-read whole chunks many times a second in multiplayer. Single block changes on StationAPI chunks
 * are caught in compat.FlattenedChunkMixin instead.
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    @Inject(method = "markDirty", at = @At("HEAD"), require = 0)
    private void betapt$region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        VoxelWorld.markDirty(minX, minZ, maxX, maxZ);
    }

}
