package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.gui.PathTracerOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.options.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Path Tracer..." button to Beta's Video Settings, just above Done. */
@Mixin(OptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    private static final int BUTTON_ID = 301;

    @Inject(method = "init", at = @At("TAIL"))
    @SuppressWarnings("unchecked")
    private void betapt$addButton(CallbackInfo ci) {
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(BUTTON_ID, this.width / 2 - 100, this.height / 6 + 144, 200, 20, "Path Tracer..."));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void betapt$click(ButtonWidget button, CallbackInfo ci) {
        if (button.active && button.id == BUTTON_ID) {
            this.minecraft.openScreen(new PathTracerOptionsScreen(this));
            ci.cancel();
        }
    }
}
