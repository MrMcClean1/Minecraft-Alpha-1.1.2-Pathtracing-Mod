package com.alexmcclellan.betapt.bots.mixin;

import net.minecraft.entity.Entities;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entities.class)
public interface EntityRegistryInvoker {
    @Invoker("register")
    static void classicbots$register(Class<? extends Entity> c, String id, int rawId) { throw new AssertionError(); }
}
