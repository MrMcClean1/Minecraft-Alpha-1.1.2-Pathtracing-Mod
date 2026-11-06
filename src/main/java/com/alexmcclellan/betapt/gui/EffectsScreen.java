package com.alexmcclellan.betapt.gui;

import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Path Tracer > Effects... : the optional effects, each its own switch. They belong to the same settings as the
 * main page (shared, or this dimension's own) and the presets set them too: Beta none, Faithful wet surfaces and
 * god rays, Realistic all six. Saved with the main page's Done.
 */
public class EffectsScreen extends Screen {
    private final Screen parent;

    public EffectsScreen(Screen parent) { this.parent = parent; }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(1, left, top, 150, 20, text(1)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(2, right, top, 150, 20, text(2)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(3, left, top + 24, 150, 20, text(3)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(4, right, top + 24, 150, 20, text(4)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(5, left, top + 48, 150, 20, text(5)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(6, right, top + 48, 150, 20, text(6)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(8, left, top + 72, 150, 20, text(8)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(9, right, top + 72, 150, 20, text(9)));
        // the test switch: what runs at the next title screen (then it switches itself off)
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(7, this.width / 2 - 100, top + 96, 200, 20, testText()));
        // particles and rain (moved here from the main page, which has the Quality button now)
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(10, this.width / 2 - 100, top + 120, 200, 20, text(10)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(11, this.width / 2 - 100, top + 144, 200, 20, text(11)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(12, this.width / 2 + 5, top + 168, 150, 20, "NVIDIA RTX..."));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(200, this.width / 2 - 155, top + 168, 150, 20, "Done"));
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private static String testText() {
        com.alexmcclellan.betapt.config.BetaPTConfig.TestMode m = com.alexmcclellan.betapt.config.BetaPTConfig.testMode();
        return "Test at title screen: " + m.label;
    }

    private String text(int id) {
        PathTracer pt = PathTracer.INSTANCE;
        return switch (id) {
            case 1 -> "Wet surfaces: " + onOff(pt.fxWet);
            case 2 -> "Rain haze: " + onOff(pt.fxHaze);
            case 3 -> "God rays: " + onOff(pt.fxGodRays);
            case 4 -> "Snow sparkle: " + onOff(pt.fxSparkle);
            case 5 -> "Waving plants: " + onOff(pt.fxWaving);
            case 6 -> "Water waves: " + onOff(pt.fxWaves);
            case 8 -> "Nights: " + (pt.darkNights ? "Dark (Beta)" : "Bright");
            case 9 -> "Hand collision: " + onOff(pt.handCollide);
            case 10 -> "Particles, rain: " + (pt.particleLight ? "Traced light" : "Beta");
            case 11 -> "Far terrain: " + onOff(com.alexmcclellan.betapt.render.FarTerrain.enabled) + "...";
            default -> "";
        };
    }

    private static final String[] HELP = {
            "",
            "Rain wets what's open to the sky; puddles reflect.",
            "Rain pulls the fog in, like a storm.",
            "Sun shafts through leaves. Up to 4 extra rays a pixel.",
            "Snow and ice glint in the sun.",
            "Leaves, grass, flowers and crops sway, more in rain.",
            "Water ripples, so reflections shimmer.",
            "Runs once from the title screen, then turns itself off.",
            "Dark dims the picture at night, as Beta's nights are.",
            "OFF: walls you stand at don't hide the traced hand (Beta).",
            "Traced light: smoke, bits and rain lit by the path tracer.",
            "Land past the tracer's range: distance, shadows, water...",
    };
    private int hovered;

    @Override
    protected void buttonClicked(ButtonWidget b) {
        PathTracer pt = PathTracer.INSTANCE;
        switch (b.id) {
            case 1 -> pt.fxWet = !pt.fxWet;
            case 2 -> pt.fxHaze = !pt.fxHaze;
            case 3 -> pt.fxGodRays = !pt.fxGodRays;
            case 4 -> pt.fxSparkle = !pt.fxSparkle;
            case 5 -> pt.fxWaving = !pt.fxWaving;
            case 6 -> pt.fxWaves = !pt.fxWaves;
            case 8 -> pt.darkNights = !pt.darkNights;
            case 9 -> pt.handCollide = !pt.handCollide;
            case 10 -> pt.particleLight = !pt.particleLight;
            case 11 -> { this.minecraft.openScreen(new FarScreen(this)); return; }
            case 7 -> { // Off -> Quick -> Full -> Multiplayer (if a server is known) -> Benchmark -> Off
                var all = com.alexmcclellan.betapt.config.BetaPTConfig.TestMode.values();
                var next = all[(com.alexmcclellan.betapt.config.BetaPTConfig.testMode().ordinal() + 1) % all.length];
                if (next == com.alexmcclellan.betapt.config.BetaPTConfig.TestMode.MULTIPLAYER && com.alexmcclellan.betapt.config.BetaPTConfig.testServer.isEmpty())
                    next = com.alexmcclellan.betapt.config.BetaPTConfig.TestMode.BENCHMARK;
                com.alexmcclellan.betapt.config.BetaPTConfig.setTestMode(next);
                b.message = testText();
                return;
            }
            case 12 -> { this.minecraft.openScreen(new RtxScreen(this)); return; }
            case 200 -> { this.minecraft.openScreen(this.parent); return; }
            default -> { return; }
        }
        b.message = text(b.id);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int top = this.height / 6;
        if (top - 20 >= 2) this.drawCenteredString(this.textRenderer, "Path Tracer Effects", this.width / 2, top - 20, 0xFFFFFF);
        hovered = 0;
        for (Object o : this.buttons) {
            ButtonWidget w = (ButtonWidget) o;
            int bw = w.id == 7 || w.id == 10 || w.id == 11 ? 200 : 150;
            if (w.id >= 1 && w.id <= 11 && mouseX >= w.x && mouseY >= w.y && mouseX < w.x + bw && mouseY < w.y + 20) hovered = w.id;
        }
        String help = hovered > 0 ? HELP[hovered] : "Presets set these too. Done on the main page saves.";
        this.drawCenteredString(this.textRenderer, help, this.width / 2, Math.min(this.height - 10, top + 194), 0xA0A0A0); // under Done
        super.render(mouseX, mouseY, delta);
    }
}
