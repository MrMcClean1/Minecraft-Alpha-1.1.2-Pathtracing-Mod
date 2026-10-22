package com.alexmcclellan.betapt.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A world's queued lighting updates (ClientLighting works through them a few milliseconds a frame). */
@Mixin(World.class)
public interface WorldLightQueueAccessor {
    @Accessor("lightUpdates")
    java.util.List<Object> betapt$lightingQueue();
}
