package com.mrmcclean.betapt.bots.mixin;

import com.mrmcclean.betapt.bots.ChatWrap;
import net.minecraft.client.gui.screen.game.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The chat box takes a long line (a full order to a bot: up to ChatWrap.MAX_TYPED characters in single player; a
 * server takes the game's own 100) and shows it on as many lines as it needs, growing upward, instead of running
 * off the right edge.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen implements ChatWrap.Keys {
    @Shadow private String lastChatMessage;
    @Shadow private int ticks;

    @Override public void classicbots$type(char c, int key) { this.keyPressed(c, key); }
    @Override public String classicbots$text() { return this.lastChatMessage; }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void classicbots$longer(char c, int key, CallbackInfo ci) {
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN || key == Keyboard.KEY_BACK) return;
        int max = this.minecraft.isMultiplayer() ? 100 : ChatWrap.MAX_TYPED;
        if (this.lastChatMessage.length() >= 100 && this.lastChatMessage.length() < max && c >= ' ' && c < 127) { this.lastChatMessage = this.lastChatMessage + c; ci.cancel(); }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void classicbots$wrapped(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        List<String> l = ChatWrap.wrap(this.textRenderer::getWidth, "> " + this.lastChatMessage + (this.ticks / 6 % 2 == 0 ? "_" : ""), this.width - 8);
        int top = this.height - 2 - 12 * l.size();
        this.fill(2, top, this.width - 2, this.height - 2, Integer.MIN_VALUE);
        for (int i = 0; i < l.size(); i++) this.drawString(this.textRenderer, l.get(i), 4, top + 2 + 12 * i, 14737632);
        ChatWrap.typedLines = l.size();
        if (ChatWrap.grabTo != null) ChatWrap.grab(this.minecraft.width, this.minecraft.height);
        ci.cancel();
    }
}
