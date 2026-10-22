package com.alexmcclellan.betapt.mixin;

import net.minecraft.client.render.vertex.Tesselator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets BlockCapture throw away what it rendered instead of drawing it. */
@Mixin(Tesselator.class)
public interface TessellatorAccessor {
    @Accessor("tesselating") boolean betapt$isDrawing();
    @Accessor("tesselating") void betapt$setDrawing(boolean value);
    @Invoker("clear") void betapt$reset();
    @Accessor("vertexBuffer") int[] betapt$buffer();
    @Accessor("vertexCount") int betapt$vertexCount();
    @Accessor("offsetX") double betapt$xOffset();
    @Accessor("offsetY") double betapt$yOffset();
    @Accessor("offsetZ") double betapt$zOffset();
}
