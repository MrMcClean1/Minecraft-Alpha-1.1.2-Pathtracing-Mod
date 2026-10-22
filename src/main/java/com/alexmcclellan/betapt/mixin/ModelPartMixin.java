package com.alexmcclellan.betapt.mixin;

import com.alexmcclellan.betapt.render.EntityCapture;
import net.minecraft.client.render.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M6: remembers each model box's size when it's built; reports the box to EntityCapture
 * when the dry capture pass draws it (with the current animation pose); and skips Beta's
 * own drawing of it in the real pass, because the path tracer draws it instead.
 */
@Mixin(ModelPart.class)
public class ModelPartMixin {
    @Shadow public float x, y, z, rotationX, rotationY, rotationZ;
    @Shadow public boolean visible, invisible, flipped;
    @Shadow private int u, v;
    @Unique private float betapt$x0, betapt$y0, betapt$z0, betapt$x1, betapt$y1, betapt$z1;
    @Unique private int betapt$sx, betapt$sy, betapt$sz;
    @Unique private boolean betapt$hasBox;

    @Inject(method = "addBox(FFFIIIF)V", at = @At("HEAD"))
    private void betapt$rememberBox(float x, float y, float z, int sx, int sy, int sz, float dilation, CallbackInfo ci) {
        betapt$x0 = x - dilation; betapt$y0 = y - dilation; betapt$z0 = z - dilation;
        betapt$x1 = x + sx + dilation; betapt$y1 = y + sy + dilation; betapt$z1 = z + sz + dilation;
        betapt$sx = sx; betapt$sy = sy; betapt$sz = sz;
        betapt$hasBox = true;
    }

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void betapt$capture(float scale, CallbackInfo ci) {
        if (EntityCapture.suppressModels && betapt$hasBox) { ci.cancel(); return; } // the path tracer draws it
        if (!EntityCapture.active || invisible || !visible || !betapt$hasBox) return;
        EntityCapture.addPart(betapt$x0, betapt$y0, betapt$z0, betapt$x1, betapt$y1, betapt$z1, scale,
                this.x, this.y, this.z, rotationX, rotationY, rotationZ, u, v, betapt$sx, betapt$sy, betapt$sz, flipped, betapt$textureHeight());
    }

    /** MojangFix gives model parts a texture height (64 for its player model, for today's 64 x 64 skins); Beta's are 32. */
    @Unique private static final java.lang.reflect.Field BETAPT$TEX_H = betapt$findTextureHeight();

    @Unique
    private static java.lang.reflect.Field betapt$findTextureHeight() {
        try {
            java.lang.reflect.Field f = ModelPart.class.getDeclaredField("textureHeight");
            f.setAccessible(true);
            return f.getType() == int.class ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private int betapt$textureHeight() {
        try {
            int h = BETAPT$TEX_H == null ? 32 : BETAPT$TEX_H.getInt(this);
            return h > 0 ? h : 32;
        } catch (Throwable t) {
            return 32;
        }
    }
}
