package com.mrmcclean.betapt.gui;

import com.mrmcclean.betapt.render.PhotoMode;
import com.mrmcclean.betapt.render.TimeLapse;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;

/**
 * Path Tracer > Effects > Photo, time-lapse... : photo mode's switch and the settings its stills use, then the
 * time-lapse recorder's (its pictures use the same samples, lens and so on). Saved with the main page's Done (and when
 * photo mode closes).
 */
public class PhotoOptionsScreen extends Screen {
    private final Screen parent;
    static final int[] FRAME_STEPS = {24, 48, 72, 96, 120, 180, 240, 360, 480, 600};

    public PhotoOptionsScreen(Screen parent) { this.parent = parent; }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        TimeLapse.ensureLoaded();
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        this.buttons.add(new PtButton(1, left, top, 150, 20, photoText()));
        this.buttons.add(PhotoScreen.samplesSlider(2, right, top));
        this.buttons.add(PhotoScreen.apertureSlider(3, left, top + 24));
        this.buttons.add(PhotoScreen.sunSlider(4, right, top + 24));
        this.buttons.add(new PtButton(5, left, top + 72, 150, 20, timeLapseText()));
        int last = FRAME_STEPS.length - 1, step = 0;
        for (int i = 0; i < FRAME_STEPS.length; i++) if (Math.abs(FRAME_STEPS[i] - TimeLapse.frames) < Math.abs(FRAME_STEPS[step] - TimeLapse.frames)) step = i;
        this.buttons.add(new PtSlider(6, right, top + 72, step / (float) last, last,
                v -> "Pictures: " + FRAME_STEPS[Math.round(v * last)] + " (" + FRAME_STEPS[Math.round(v * last)] / Math.max(1, TimeLapse.fps) + " s)",
                v -> TimeLapse.frames = FRAME_STEPS[Math.round(v * last)]));
        this.buttons.add(new PtButton(7, left, top + 96, 150, 20, keysText()));
        this.buttons.add(new PtButton(8, right, top + 96, 150, 20, videoText()));
        ButtonWidget record = new PtButton(9, this.width / 2 - 100, top + 120, 200, 20, "Record time-lapse");
        record.active = TimeLapse.enabled && TimeLapse.keys.size() >= 2 && this.minecraft.world != null;
        this.buttons.add(record);
        this.buttons.add(new PtButton(200, this.width / 2 - 100, top + 168, 200, 20, "Done"));
    }

    private static String photoText() { return "Photo mode: " + (PhotoMode.enabled ? "ON" : "OFF"); }
    private static String timeLapseText() { return "Time-lapse: " + (TimeLapse.enabled ? "ON" : "OFF"); }
    private static String keysText() { return "Keyframes: " + TimeLapse.keys.size() + (TimeLapse.keys.isEmpty() ? "" : " (clear)"); }
    private static String videoText() { return "Video (ffmpeg): " + (TimeLapse.video ? "ON" : "OFF"); }

    @Override
    protected void buttonClicked(ButtonWidget b) {
        switch (b.id) {
            case 1 -> { PhotoMode.enabled = !PhotoMode.enabled; b.message = photoText(); }
            case 5 -> { TimeLapse.enabled = !TimeLapse.enabled; init(); }
            case 7 -> { TimeLapse.clearKeys(); init(); }
            case 8 -> { TimeLapse.video = !TimeLapse.video; b.message = videoText(); }
            case 9 -> { com.mrmcclean.betapt.config.PtSettings.save(); this.minecraft.openScreen(null); TimeLapse.start(this.minecraft); }
            case 200 -> this.minecraft.openScreen(parent);
            default -> { }
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int top = this.height / 6;
        if (top - 20 >= 2) this.drawCenteredString(this.textRenderer, "Photo and Time-lapse", this.width / 2, top - 20, 0xFFFFFF);
        String key = org.lwjgl.input.Keyboard.getKeyName(com.mrmcclean.betapt.config.BetaPTKeys.PHOTO.keyCode);
        this.drawCenteredString(this.textRenderer, "Photo key: " + key + ". Stills go to screenshots/, focused where the crosshair points.",
                this.width / 2, top + 50, 0xA0A0A0);
        String kf = org.lwjgl.input.Keyboard.getKeyName(com.mrmcclean.betapt.config.BetaPTKeys.KEYFRAME.keyCode);
        String tl = TimeLapse.status.isEmpty() ? "Keyframe key: " + kf + " (where you stand, where you look, the time of day)." : TimeLapse.status;
        this.drawCenteredString(this.textRenderer, tl, this.width / 2, top + 146, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}
