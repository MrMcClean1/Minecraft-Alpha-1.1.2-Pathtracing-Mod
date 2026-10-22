package com.alexmcclellan.betapt.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the autotest reset Entity.fallDistance, which is protected. */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("fallDistance")
    void betapt$setFallDistance(float value);
}
