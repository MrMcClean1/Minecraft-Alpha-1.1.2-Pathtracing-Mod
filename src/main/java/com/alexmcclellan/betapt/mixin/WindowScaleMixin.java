package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.gui.AlphaGui;
import net.minecraft.client.render.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The GUI scale option (AlphaGui): no bigger than the chosen scale. Alpha works it out anew every frame. */
@Mixin(Window.class)
public abstract class WindowScaleMixin {
    @Shadow private int width;
    @Shadow private int height;
    @Shadow public int scale;

    @Inject(method = "<init>(II)V", at = @At("TAIL"))
    private void alphapt$cap(int w, int h, CallbackInfo ci) {
        int cap = AlphaGui.scale;
        if (cap <= 0 || scale <= cap) return;
        scale = cap;
        width = w / cap;
        height = h / cap;
    }
}
