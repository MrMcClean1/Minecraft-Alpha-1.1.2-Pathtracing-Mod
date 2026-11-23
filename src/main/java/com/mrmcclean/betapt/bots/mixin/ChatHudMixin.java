package com.mrmcclean.betapt.bots.mixin;

import com.mrmcclean.betapt.bots.ChatWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** A long chat line (a bot's reply) breaks at spaces to fit the chat's 320 pixels; the game itself cuts mid-word. */
@Mixin(GameGui.class)
public class ChatHudMixin {
    @Shadow private Minecraft minecraft;

    @Inject(method = "addChatMessage", at = @At("HEAD"), cancellable = true)
    private void classicbots$wrap(String message, CallbackInfo ci) {
        if (message == null || this.minecraft == null || this.minecraft.textRenderer == null || this.minecraft.textRenderer.getWidth(message) <= 320) return;
        List<String> l = ChatWrap.wrap(this.minecraft.textRenderer::getWidth, message, 320);
        ChatWrap.pieces = l.size();
        ci.cancel();
        for (String s : l) ((GameGui) (Object) this).addChatMessage(s);
    }
}
