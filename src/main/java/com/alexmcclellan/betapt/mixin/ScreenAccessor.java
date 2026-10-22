package com.alexmcclellan.betapt.mixin;

import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the modpack test list a screen's buttons (Screen.buttons is protected). */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Accessor("buttons")
    java.util.List<Object> betapt$buttons();
}
