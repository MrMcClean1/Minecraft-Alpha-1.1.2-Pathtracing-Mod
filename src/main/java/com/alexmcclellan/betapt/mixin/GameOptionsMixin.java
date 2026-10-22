package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.config.BetaPTKeys;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The path tracer's keys in Options, Controls (see BetaPTKeys). */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
    @Shadow public KeyBinding[] keyBindings;

    @Inject(method = "load", at = @At("HEAD"))
    private void betapt$addKeys(CallbackInfo ci) {
        keyBindings = BetaPTKeys.withOurs(keyBindings);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void betapt$clashes(CallbackInfo ci) {
        BetaPTKeys.resolveClashes(keyBindings);
    }
}
