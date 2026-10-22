package com.alexmcclellan.betapt.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.lwjgl.opengl.GL11;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A Beta-style slider for our own settings. Beta's SliderWidget only works with Beta's
 * built-in options, so this copies how it looks and behaves: click or drag along the bar,
 * the knob follows the mouse, and the label shows the value.
 */
public class PtSlider extends ButtonWidget {
    public float value;           // 0..1
    private boolean dragging;
    private final float steps;    // snap to this many steps (0 = smooth)
    private final Function<Float, String> label;
    private final Consumer<Float> onChange;

    public PtSlider(int id, int x, int y, float value, float steps, Function<Float, String> label, Consumer<Float> onChange) {
        super(id, x, y, 150, 20, "");
        this.value = value;
        this.steps = steps;
        this.label = label;
        this.onChange = onChange;
        this.message = label.apply(value);
    }

    private void setFromMouse(int mouseX) {
        float v = (mouseX - (this.x + 4)) / (float) (this.width - 8);
        v = Math.max(0f, Math.min(1f, v));
        if (steps > 0) v = Math.round(v * steps) / steps;
        if (v != value) {
            value = v;
            onChange.accept(v);
        }
        this.message = label.apply(value);
    }

    @Override
    protected int getYImage(boolean hovered) {
        return 0; // the darker "track" look, like Beta's sliders
    }

    @Override
    protected void renderBackground(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        if (dragging) setFromMouse(mouseX);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        int knob = this.x + (int) (value * (this.width - 8));
        this.drawTexture(knob, this.y, 0, 66, 4, 20);
        this.drawTexture(knob + 4, this.y, 196, 66, 4, 20);
    }

    @Override
    public boolean mouseClicked(Minecraft mc, int mouseX, int mouseY) {
        if (super.mouseClicked(mc, mouseX, mouseY)) {
            setFromMouse(mouseX);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        dragging = false;
    }
}
