package com.alexmcclellan.betapt.bots.mixin;

import com.alexmcclellan.betapt.bots.Bots;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Single player: Alpha drops chat on the floor; show it and let the bots hear it (and /bot commands). */
@Mixin(ClientPlayerEntity.class)
public class BotChatClientMixin {
    @Inject(method = "sendChat", at = @At("HEAD"))
    private void classicbots$chat(String message, CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        Bots.init();
        if (!message.trim().toLowerCase().startsWith("/bot")) Bots.broadcast(com.alexmcclellan.betapt.bots.Names.world(self), "<" + self.name + "> " + message);
        Bots.chat(self, message);
    }
}
