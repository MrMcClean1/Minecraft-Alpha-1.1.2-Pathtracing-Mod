package com.alexmcclellan.betapt.gui;

import com.alexmcclellan.betapt.config.PtSettings;
import com.alexmcclellan.betapt.render.PathTracer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Options > Video Settings > Path Tracer... : the mod's own settings page, styled like
 * Beta's. Changes apply immediately (watch the world behind the menu) and are saved to
 * .minecraft/config/betapt-settings.properties when you press Done.
 */
public class PathTracerOptionsScreen extends Screen {
    private final Screen parent;

    public PathTracerOptionsScreen(Screen parent) {
        this.parent = parent;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        PathTracer pt = PathTracer.INSTANCE;
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        // whose settings these are: shared by every dimension, or this dimension's own (saved per dimension)
        ButtonWidget dimButton = new com.alexmcclellan.betapt.gui.PtButton(19, this.width / 2 - 110, top - 24, 220, 20, dimText());
        dimButton.active = PtSettings.inDimension() && this.minecraft.world != null;
        this.buttons.add(dimButton);
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(1, left, top, 150, 20, enabledText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(2, right, top, 150, 20, denoiseText()));
        this.buttons.add(new PtSlider(3, left, top + 24, pt.realism, 20,
                v -> "Look: " + lookName(v), v -> pt.realism = v));
        this.buttons.add(new PtSlider(4, right, top + 24, (pt.brightness - 0.5f) / 1.5f, 30,
                v -> "Brightness: " + Math.round((0.5f + v * 1.5f) * 100) + "%", v -> pt.brightness = 0.5f + v * 1.5f));
        // render scale 50..100% in 5% steps: the path tracer draws at this fraction of the window, then upscales
        this.buttons.add(new PtSlider(6, left, top + 48, (pt.renderScale - 0.5f) / 0.5f, 10,
                v -> "Render scale: " + Math.round((0.5f + v * 0.5f) * 100) + "%", v -> pt.renderScale = 0.5f + v * 0.5f));
        this.buttons.add(new PtSlider(7, right, top + 48, pt.sharpen, 20,
                v -> "Sharpening: " + (v < 0.001f ? "OFF" : Math.round(v * 100) + "%"), v -> pt.sharpen = v));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(8, left, top + 72, 150, 20, mobText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(9, right, top + 72, 150, 20, upscalerText()));
        // cloud shadow darkness 0..100% in 5% steps; all the way left is off
        this.buttons.add(new PtSlider(10, left, top + 96, pt.cloudShadows, 20,
                v -> "Cloud shadows: " + (v < 0.001f ? "OFF" : Math.round(v * 100) + "%"), v -> pt.cloudShadows = v));
        // trace distance: Auto (Beta's render distance), then 32 .. 240 blocks in 16s, then 248 (the most the GPU copy holds)
        this.buttons.add(new PtSlider(11, right, top + 96, distanceToSlider(pt.traceDistance), 15,
                v -> "Trace distance: " + distanceName(sliderToDistance(v)), v -> pt.traceDistance = sliderToDistance(v)));
        // presets: Beta -> Faithful -> Realistic; shows "Custom" once something is changed by hand
        presetButton = new com.alexmcclellan.betapt.gui.PtButton(12, left, top + 120, 150, 20, presetText());
        // quality: Very Low .. Very High (what costs frame rate), Auto (tries them and keeps the best that holds 60 fps)
        qualityButton = new com.alexmcclellan.betapt.gui.PtButton(21, right, top + 120, 150, 20, qualityText());
        this.buttons.add(qualityButton);
        this.buttons.add(presetButton);
        // Reset and Done share the last row: at 2560x1440 with GUI Scale on Auto the screen is only
        // 240 units tall, and a separate Done row below the help text would fall off the bottom
        // four to this row: Reset, Bounces, Leaves, Done
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(5, left, top + 144, 74, 20, "Reset"));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(17, left + 78, top + 144, 76, 20, bounceText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(18, left + 158, top + 144, 76, 20, leavesText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(200, left + 238, top + 144, 72, 20, "Done"));
        // below Done: newer options (the help text that used to sit here is on F3 / in the README)
        // four to a row, like the row above: torch light, hand, reflections, the Effects page
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(13, left, top + 168, 74, 20, heldText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(14, left + 78, top + 168, 76, 20, handText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(16, left + 158, top + 168, 76, 20, reflectText()));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(20, left + 238, top + 168, 72, 20, "Effects..."));
    }

    private String dimName() { return this.minecraft.world == null ? "this dimension" : PtSettings.dimensionName(null); }
    private String dimText() {
        if (!PtSettings.inDimension() || this.minecraft.world == null) return "Settings for: every dimension";
        return PtSettings.ownSettings() ? "Settings for: " + dimName() + " only" : "Settings for: every dimension";
    }
    private String leavesText() { return "Leaves: " + (PathTracer.INSTANCE.fastLeaves ? "Fast" : "Fancy"); }
    private String bounceText() { return "Bounces: " + PathTracer.INSTANCE.bounces; }
    private ButtonWidget qualityButton;
    private String qualityText() {
        if (com.alexmcclellan.betapt.render.Quality.autoRunning()) return "Quality: Auto (testing...)";
        com.alexmcclellan.betapt.render.Quality q = com.alexmcclellan.betapt.render.Quality.of(PathTracer.INSTANCE);
        return "Quality: " + (q == null ? "Custom" : q.label);
    }
    private String handText() { return "Hand: " + (PathTracer.INSTANCE.handMode == PathTracer.HAND_TRACED ? "Traced" : "Beta"); }
    private String reflectText() {
        int r = PathTracer.INSTANCE.reflections;
        return "Reflect: " + (r == PathTracer.REFLECT_ALL ? "All" : r == PathTracer.REFLECT_WATER ? "Water" : "OFF");
    }

    private String heldText() {
        int h = PathTracer.INSTANCE.heldLights;
        return "Torch: " + (h == PathTracer.HELD_EVERYONE ? "All" : h == PathTracer.HELD_MINE ? "Yours" : "OFF");
    }

    private ButtonWidget presetButton;

    private String presetText() {
        PathTracer.Preset p = PathTracer.INSTANCE.preset();
        return "Style: " + (p == null ? "Custom" : p.label);
    }

    static int sliderToDistance(float v) {
        int i = Math.round(v * 15);
        return i == 0 ? 0 : i == 15 ? 248 : 32 + (i - 1) * 16;
    }
    static float distanceToSlider(int d) {
        if (d <= 0) return 0f;
        if (d >= 248) return 1f;
        return (1 + Math.round((d - 32) / 16f)) / 15f;
    }
    static String distanceName(int d) { return d == 0 ? "Auto" : d + " blocks"; }

    static String lookName(float v) {
        int p = Math.round(v * 100);
        if (p == 0) return "Beta";
        if (p == 100) return "Realistic";
        return p + "% realistic";
    }

    // Path tracer: RT hardware (Vulkan) -> OpenGL -> OFF (Beta's own renderer)
    private String enabledText() {
        PathTracer pt = PathTracer.INSTANCE;
        if (!pt.enabled) return "Path tracer: OFF";
        if (!pt.hardwareRt) return "Path tracer: OpenGL";
        return com.alexmcclellan.betapt.render.VkBridge.failed() ? "Path tracer: RT (n/a, OpenGL)" : "Path tracer: RT hardware";
    }
    private String mobText() { return "Path-traced mobs: " + (PathTracer.INSTANCE.entityShadows ? "ON" : "OFF"); }
    private String upscalerText() {
        int u = PathTracer.INSTANCE.upscaler;
        return "Upscaler: " + (u == PathTracer.UPSCALER_FSR ? "AMD FSR 3.1" : u == PathTracer.UPSCALER_BASIC ? "Basic" : "OFF (native)");
    }
    private String denoiseText() {
        PathTracer pt = PathTracer.INSTANCE;
        return "Denoiser: " + (!pt.denoise ? "OFF" : pt.denoiserMode == PathTracer.DENOISER_ADVANCED ? "Advanced" : "Standard");
    }

    @Override
    protected void buttonClicked(ButtonWidget b) {
        PathTracer pt = PathTracer.INSTANCE;
        switch (b.id) {
            case 1 -> {
                if (pt.enabled && pt.hardwareRt) pt.hardwareRt = false;
                else if (pt.enabled) pt.enabled = false;
                else { pt.enabled = true; pt.hardwareRt = true; }
                b.message = enabledText();
            }
            case 2 -> { // Advanced -> Standard -> OFF
                if (!pt.denoise) { pt.denoise = true; pt.denoiserMode = PathTracer.DENOISER_ADVANCED; }
                else if (pt.denoiserMode == PathTracer.DENOISER_ADVANCED) pt.denoiserMode = PathTracer.DENOISER_STANDARD;
                else pt.denoise = false;
                b.message = denoiseText();
            }
            case 8 -> { pt.entityShadows = !pt.entityShadows; b.message = mobText(); }
            case 20 -> this.minecraft.openScreen(new EffectsScreen(this));
            case 17 -> { pt.bounces = pt.bounces == 1 ? 2 : 1; b.message = bounceText(); }
            case 19 -> { // this dimension's own settings on (a copy of what's showing) or off (back to the shared ones)
                PtSettings.setOwnSettings(!PtSettings.ownSettings(), dimName());
                this.init();
            }
            case 18 -> { pt.fastLeaves = !pt.fastLeaves; b.message = leavesText(); }
            case 16 -> { pt.reflections = (pt.reflections + 2) % 3; b.message = reflectText(); } // All -> Water -> OFF
            case 21 -> { // Very Low -> Low -> Medium -> High -> Very High -> Auto -> Very Low; Custom goes to Medium
                com.alexmcclellan.betapt.render.Quality q = com.alexmcclellan.betapt.render.Quality.of(pt);
                com.alexmcclellan.betapt.render.Quality[] all = com.alexmcclellan.betapt.render.Quality.values();
                if (com.alexmcclellan.betapt.render.Quality.autoRunning()) { com.alexmcclellan.betapt.render.Quality.cancelAuto(); all[0].apply(pt); }
                else if (q == null) com.alexmcclellan.betapt.render.Quality.MEDIUM.apply(pt);
                else if (q.ordinal() == all.length - 1) com.alexmcclellan.betapt.render.Quality.startAuto(pt);
                else all[q.ordinal() + 1].apply(pt);
                this.init(); // the sliders move to the level's values
            }
            case 14 -> { pt.handMode = 1 - pt.handMode; b.message = handText(); }
            case 13 -> { pt.heldLights = (pt.heldLights + 2) % 3; b.message = heldText(); } // Everyone -> Yours -> OFF
            case 12 -> {
                PathTracer.Preset p = pt.preset();
                PathTracer.Preset[] all = PathTracer.Preset.values();
                pt.apply(p == null ? PathTracer.Preset.FAITHFUL : all[(p.ordinal() + 1) % all.length]);
                this.init(); // the sliders move to the preset's values
            }
            case 9 -> { pt.upscaler = (pt.upscaler + 1) % 3; b.message = upscalerText(); } // FSR -> Basic -> OFF
            case 5 -> {
                pt.realism = PathTracer.DEFAULT_REALISM;
                pt.brightness = 1f;
                pt.denoise = true;
                pt.renderScale = 1f;
                pt.sharpen = 0.5f;
                pt.entityShadows = true;
                pt.upscaler = PathTracer.UPSCALER_FSR;
                pt.cloudShadows = PathTracer.DEFAULT_CLOUD_SHADOWS;
                pt.traceDistance = 0;
                pt.fastLeaves = false;
                pt.hardwareRt = true;
                pt.heldLights = PathTracer.HELD_EVERYONE;
                pt.handMode = PathTracer.HAND_BETA;
                pt.particleLight = true;
                pt.denoiserMode = PathTracer.DENOISER_ADVANCED;
                pt.reflections = PathTracer.REFLECT_WATER;
                pt.bounces = 1;
                pt.setEffects(PathTracer.FX_WET | PathTracer.FX_GODRAYS);
                this.init(); // rebuild the widgets so they show the new values
            }
            case 200 -> {
                PtSettings.save();
                this.minecraft.openScreen(this.parent);
            }
            default -> {}
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int titleY = this.height / 6 - 36; // above the dimension button
        if (titleY >= 2) this.drawCenteredString(this.textRenderer, "Path Tracer Settings", this.width / 2, titleY, 0xFFFFFF);
        // under the buttons: which other dimensions keep their own settings
        java.util.List<String> others = PtSettings.otherDimensionsWithOwnSettings();
        if (!others.isEmpty())
            this.drawCenteredString(this.textRenderer, "Own settings also in: " + String.join(", ", others),
                    this.width / 2, Math.min(this.height - 10, this.height / 6 + 192), 0xA0A0A0);
        if (presetButton != null) presetButton.message = presetText(); // turns to "Custom" as soon as a slider moves
        if (qualityButton != null) qualityButton.message = qualityText();
        super.render(mouseX, mouseY, delta);
    }
}
