package com.alexmcclellan.betapt.mixin;

import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A button's size (protected in Alpha), for the Alpha test's layout checks. */
@Mixin(ButtonWidget.class)
public interface ButtonWidgetAccessor {
    @Accessor("width") int betapt$width();
    @Accessor("height") int betapt$height();
}
