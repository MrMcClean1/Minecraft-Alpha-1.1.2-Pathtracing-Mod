package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.HeldLight;
import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Beta still draws your first-person hand and held item (sharp, and never poking into walls),
 * but with the path tracer on, their brightness and colour come from the traced light where the
 * hand is (PathTracer.handLight): dark in shadow, warm next to lava, bright with a torch in hand.
 * StationAPI replaces render() with its own renderer (see compat.ArsenicOverlayRendererMixin), so the
 * redirects here are optional, and the priority is above StationAPI's so the HEAD hook still applies.
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1100)
public abstract class HeldItemRendererMixin {
    @org.spongepowered.asm.mixin.Shadow private net.minecraft.client.Minecraft minecraft;

    // Hand traced: while its boxes are being recorded, the held item is handed over as a box instead of drawn
    @org.spongepowered.asm.mixin.injection.Inject(method = "render(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
    private void betapt$item(net.minecraft.item.ItemStack stack,
                             org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!com.alexmcclellan.betapt.render.EntityCapture.capturingHand) return;
        com.alexmcclellan.betapt.render.EntityCapture.captureHeldItem(stack, this.minecraft);
        ci.cancel();
    }

    @Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBrightness(III)F"), require = 0)
    private float betapt$brightness(World world, int x, int y, int z) {
        return HeldLight.brightness(world, x, y, z);
    }

    @Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V", remap = false), require = 0)
    private void betapt$colour(float r, float g, float b, float a) {
        HeldLight.colour(r, g, b, a);
    }
}
