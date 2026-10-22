package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.EntityCapture;
import net.minecraft.client.render.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.image.BufferedImage;

/**
 * Tells the path tracer when Beta deletes a texture or loads a new picture into one (a player's skin arriving
 * after the default one, a texture pack change, a player leaving), so it copies that skin again instead of
 * showing the old one, and frees the layer of a texture that's gone.
 */
@Mixin(TextureManager.class)
public class TextureManagerMixin {
    @Inject(method = "release(I)V", at = @At("HEAD"))
    private void betapt$deleted(int id, CallbackInfo ci) {
        EntityCapture.forgetTexture(id);
    }

    @Inject(method = "load(Ljava/awt/image/BufferedImage;I)V", at = @At("HEAD"))
    private void betapt$reloaded(BufferedImage image, int id, CallbackInfo ci) {
        EntityCapture.forgetTexture(id);
    }
}
