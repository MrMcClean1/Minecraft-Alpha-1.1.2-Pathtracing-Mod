package com.alexmcclellan.betapt.bots.mixin;

import com.alexmcclellan.betapt.bots.BotEntity;
import com.alexmcclellan.betapt.bots.BotRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(EntityRenderDispatcher.class)
public class BotRenderersMixin {
    @Shadow private Map<Class<? extends Entity>, EntityRenderer<? extends Entity>> renderers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void classicbots$renderer(CallbackInfo ci) {
        BotRenderer r = new BotRenderer();
        r.init((EntityRenderDispatcher) (Object) this);
        renderers.put(BotEntity.class, r);
    }
}
