package com.alexmcclellan.betapt.mixin;

import net.minecraft.entity.mob.monster.CreeperEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the autotest hold a creeper part-way through its fuse, so its white flash can be photographed. */
@Mixin(CreeperEntity.class)
public interface CreeperAccessor {
    @Accessor("fuse")
    void betapt$setFuseTime(int value);

    @Accessor("lastFuse")
    void betapt$setLastFuseTime(int value);
}
