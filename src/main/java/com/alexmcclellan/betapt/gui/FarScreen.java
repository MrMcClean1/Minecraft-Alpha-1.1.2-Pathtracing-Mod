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
        int x = this.width / 2 - 100, top = this.height / 6;
        this.buttons.clear();
        for (int id = 1; id <= 5; id++) this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(id, x, top + (id - 1) * 24, 200, 20, text(id)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(200, x, top + 132, 200, 20, "Done"));
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private static String text(int id) {
        return switch (id) {
            case 1 -> "Far terrain: " + onOff(FarTerrain.enabled);
            case 2 -> "Distance: " + FarTerrain.distance + " blocks";
            case 3 -> "Land not visited: " + (FarTerrain.generate ? "Generated" : "Hidden");
            case 4 -> "Shadows: " + onOff(FarTerrain.shadows);
            case 5 -> "Water reflections: " + onOff(FarTerrain.water);
            default -> "";
        };
    }

    private static final String[] HELP = {
            "",
            "Land past the path tracer's range, drawn over the sky.",
            "How far it reaches. 2000: 4x the land to make and 64 MB of video memory.",
            "Generated: made from the seed (shape only, no trees). Not in packs.",
            "Hills shade the land behind them from the sun.",
            "Far water shows the sky and the sun's glint.",
    };

    @Override
    protected void buttonClicked(ButtonWidget b) {
        switch (b.id) {
            case 1 -> FarTerrain.enabled = !FarTerrain.enabled;
            case 2 -> FarTerrain.distance = FarTerrain.distance >= 2000 ? 500 : FarTerrain.distance * 2;
            case 3 -> FarTerrain.generate = !FarTerrain.generate;
            case 4 -> FarTerrain.shadows = !FarTerrain.shadows;
            case 5 -> FarTerrain.water = !FarTerrain.water;
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
            if (w.id >= 1 && w.id <= 5 && mouseX >= w.x && mouseY >= w.y && mouseX < w.x + 200 && mouseY < w.y + 20) hovered = w.id;
        }
        int y = Math.min(this.height - 22, top + 158);
        this.drawCenteredString(this.textRenderer, hovered > 0 ? HELP[hovered] : "Now: " + FarTerrain.status, this.width / 2, y, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}
