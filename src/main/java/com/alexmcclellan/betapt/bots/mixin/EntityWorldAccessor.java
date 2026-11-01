package com.alexmcclellan.betapt.bots.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityWorldAccessor {
    @Accessor("world")
    World classicbots$world();
}
