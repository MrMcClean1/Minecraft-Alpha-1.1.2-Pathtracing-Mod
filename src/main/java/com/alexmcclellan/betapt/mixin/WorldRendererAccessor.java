package com.alexmcclellan.betapt.mixin;

import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Beta's tick counter that slides the clouds along; cloud shadows follow the same clock. */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
    @Accessor("ticks")
    int betapt$getTicks();

    /** WorldChunk sections Beta still has to rebuild (the test waits for none before Beta's reference shots). */
    @Accessor("dirtyChunks")
    java.util.List<?> betapt$getDirtyChunks();
}
