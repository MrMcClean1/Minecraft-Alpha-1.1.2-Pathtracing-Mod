package com.alexmcclellan.betapt.bots.mixin;

import com.alexmcclellan.betapt.bots.BotTest;
import com.alexmcclellan.betapt.bots.Bots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.game.ChatScreen;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The bots' test, and chat in single player (Alpha only opens chat in multiplayer; the bots need it to hear orders). */
@Mixin(Minecraft.class)
public class BotTestTickMixin {
    private boolean classicbots$chatDown;

    @Inject(method = "tick", at = @At("TAIL"))
    private void classicbots$tick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (BotTest.ON) BotTest.tick(mc);
        boolean down = mc.world != null && !mc.isMultiplayer() && mc.screen == null && Keyboard.isKeyDown(mc.options.chatKey.keyCode);
        if (down && !classicbots$chatDown) mc.openScreen(new ChatScreen());
        classicbots$chatDown = down;
    }
}
