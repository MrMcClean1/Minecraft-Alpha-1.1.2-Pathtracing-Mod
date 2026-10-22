package com.alexmcclellan.betapt.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The multiplayer test reads the server's answers to its commands from chat. */
@Mixin(GameGui.class)
public class ChatCaptureMixin {
    @Inject(method = "addChatMessage", at = @At("HEAD"))
    private void betapt$chat(String message, CallbackInfo ci) {
        com.alexmcclellan.betapt.test.ModTest.onChat(message);
    }
}
