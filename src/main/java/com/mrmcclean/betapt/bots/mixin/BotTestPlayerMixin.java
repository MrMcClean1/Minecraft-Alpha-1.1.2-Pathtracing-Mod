package com.mrmcclean.betapt.bots.mixin;

import com.mrmcclean.betapt.bots.BotTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a bot test runs, the player takes no harm (a zombie, a fall, lava or drowning killed the player in a run and
 * the steps after it failed): every hurt is turned away, so no death screen either. Only while BotTest.ON; nothing is
 * saved, so normal play is back the moment the test ends.
 */
@Mixin(MobEntity.class)
public class BotTestPlayerMixin {
    @Inject(method = "takeDamage", at = @At("HEAD"), cancellable = true)
    private void classicbots$testSafe(Entity source, int amount, CallbackInfoReturnable<Boolean> cir) {
        if (BotTest.ON && (Object) this instanceof PlayerEntity) cir.setReturnValue(false);
    }
}
