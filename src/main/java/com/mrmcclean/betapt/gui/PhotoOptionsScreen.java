package com.mrmcclean.betapt.gui;

import com.mrmcclean.betapt.render.PhotoMode;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Path Tracer > Effects > Photo, time-lapse... : photo mode's switch and the settings its stills use. Saved with
 * the main page's Done (and when photo mode closes).
 */
public class PhotoOptionsScreen extends Screen {
    private final Screen parent;

    public PhotoOptionsScreen(Screen parent) { this.parent = parent; }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        this.buttons.add(new PtButton(1, left, top, 150, 20, photoText()));
        this.buttons.add(PhotoScreen.samplesSlider(2, right, top));
        this.buttons.add(PhotoScreen.apertureSlider(3, left, top + 24));
        this.buttons.add(PhotoScreen.sunSlider(4, right, top + 24));
        this.buttons.add(new PtButton(200, this.width / 2 - 100, top + 168, 200, 20, "Done"));
    }

    private static String photoText() { return "Photo mode: " + (PhotoMode.enabled ? "ON" : "OFF"); }

    @Override
    protected void buttonClicked(ButtonWidget b) {
        if (b.id == 1) { PhotoMode.enabled = !PhotoMode.enabled; b.message = photoText(); }
        if (b.id == 200) this.minecraft.openScreen(parent);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int top = this.height / 6;
        if (top - 20 >= 2) this.drawCenteredString(this.textRenderer, "Photo and Time-lapse", this.width / 2, top - 20, 0xFFFFFF);
        String key = org.lwjgl.input.Keyboard.getKeyName(com.mrmcclean.betapt.config.BetaPTKeys.PHOTO.keyCode);
        this.drawCenteredString(this.textRenderer, "Photo key: " + key + ". Stills go to screenshots/, focused where the crosshair points.",
                this.width / 2, top + 50, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}
