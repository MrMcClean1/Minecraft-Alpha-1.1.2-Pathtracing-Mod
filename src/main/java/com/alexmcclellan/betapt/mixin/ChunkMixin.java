package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.VoxelWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change in singleplayer goes through one of these three WorldChunk methods.
 * We flag the chunk so VoxelWorld re-uploads it on the next frame.
 *
 * In multiplayer, whole chunks (and big regions after explosions) arrive from the
 * server through loadFromPacket, which writes straight into the block arrays without
 * going through setBlock, so that is hooked as well.
 */
@Mixin(WorldChunk.class)
public class ChunkMixin {
    @Inject(method = "setBlockWithMetadataAt(IIIII)Z", at = @At("HEAD"))
    private void betapt$setBlockMeta(int x, int y, int z, int id, int meta, CallbackInfoReturnable<Boolean> cir) {
        VoxelWorld.markDirty((WorldChunk) (Object) this);
    }

    @Inject(method = "setBlockAt(IIII)Z", at = @At("HEAD"))
    private void betapt$setBlock(int x, int y, int z, int id, CallbackInfoReturnable<Boolean> cir) {
        VoxelWorld.markDirty((WorldChunk) (Object) this);
    }

    @Inject(method = "setBlockMetadataAt", at = @At("HEAD"))
    private void betapt$setMeta(int x, int y, int z, int meta, CallbackInfo ci) {
        VoxelWorld.markDirty((WorldChunk) (Object) this);
    }

    @Inject(method = "unpackChunkData", at = @At("RETURN"))
    private void betapt$fromServer(byte[] bytes, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int offset,
                                   CallbackInfoReturnable<Integer> cir) {
        VoxelWorld.markDirty((WorldChunk) (Object) this);
    }
}
