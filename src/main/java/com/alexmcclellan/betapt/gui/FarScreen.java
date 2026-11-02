package com.alexmcclellan.betapt.gui;

import com.alexmcclellan.betapt.render.FarTerrain;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Path Tracer > Effects > Far terrain...: its switches, saved straight away (config/betapt-far.properties). */
public class FarScreen extends Screen {
    private final Screen parent;

    public FarScreen(Screen parent) { this.parent = parent; }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        for (int id = 1; id <= 8; id++)
            this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(id, (id & 1) == 1 ? left : right, top + (id - 1) / 2 * 24, 150, 20, text(id)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(200, this.width / 2 - 100, top + 108, 200, 20, "Done"));
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private static String text(int id) {
        return switch (id) {
            case 1 -> "Far terrain: " + onOff(FarTerrain.enabled);
            case 2 -> "Distance: " + FarTerrain.distance;
            case 3 -> "Haze: " + (FarTerrain.haze == 0 ? "Off" : FarTerrain.haze == 1 ? "Light" : "Normal");
            case 4 -> "Shape: " + (FarTerrain.blocky ? "Blocky" : "Smooth");
            case 5 -> "Texture: " + onOff(FarTerrain.texture);
            case 6 -> "Shadows: " + onOff(FarTerrain.shadows);
            case 7 -> "Water: " + (FarTerrain.water ? "Reflective" : "Flat");
            case 8 -> "Unvisited: " + (FarTerrain.generate ? "Generated" : "Hidden");
            default -> "";
        };
    }

    private static final String[] HELP = {
            "",
            "Land past the path tracer's range, drawn over the sky.",
            "Blocks out. 2000: 4x the land to make, 64 MB of video memory.",
            "Off and Light keep far land clear; Normal fades it into the fog.",
            "Blocky: flat block tops and walls. Smooth: slopes.",
            "Each block speckled a little, like its texture.",
            "Hills shade the land behind them from the sun.",
            "Reflective: far water shows the sky and the sun's glint.",
            "Generated: land not visited, from the seed (no trees). Not in packs.",
    };

    @Override
    protected void buttonClicked(ButtonWidget b) {
        switch (b.id) {
            case 1 -> FarTerrain.enabled = !FarTerrain.enabled;
            case 2 -> FarTerrain.distance = FarTerrain.distance >= 2000 ? 500 : FarTerrain.distance * 2;
            case 3 -> FarTerrain.haze = (FarTerrain.haze + 2) % 3; // Light -> Off -> Normal -> Light
            case 4 -> FarTerrain.blocky = !FarTerrain.blocky;
            case 5 -> FarTerrain.texture = !FarTerrain.texture;
            case 6 -> FarTerrain.shadows = !FarTerrain.shadows;
            case 7 -> FarTerrain.water = !FarTerrain.water;
            case 8 -> FarTerrain.generate = !FarTerrain.generate;
            case 200 -> { this.minecraft.openScreen(this.parent); return; }
            default -> { return; }
        }
        FarTerrain.save();
        b.message = text(b.id);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int top = this.height / 6;
        if (top - 20 >= 2) this.drawCenteredString(this.textRenderer, "Far Terrain", this.width / 2, top - 20, 0xFFFFFF);
        int hovered = 0;
        for (Object o : this.buttons) {
            ButtonWidget w = (ButtonWidget) o;
            if (w.id >= 1 && w.id <= 8 && mouseX >= w.x && mouseY >= w.y && mouseX < w.x + 150 && mouseY < w.y + 20) hovered = w.id;
        }
        int y = Math.min(this.height - 10, top + 134);
        this.drawCenteredString(this.textRenderer, hovered > 0 ? HELP[hovered] : "Now: " + FarTerrain.status, this.width / 2, y, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}
