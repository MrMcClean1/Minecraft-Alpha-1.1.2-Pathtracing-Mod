package com.alexmcclellan.betapt.gui;

import com.alexmcclellan.betapt.render.PhotoMode;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Photo mode on screen (the photo key, when Photo mode is on in Path Tracer > Effects > Photo, time-lapse...). The
 * game pauses (Alpha has no hide-HUD switch: the HUD stays on screen but never in the saved still), and the picture adds up its samples; when it has them all it is saved to
 * screenshots/. The sliders change the sample count, the lens (depth of field, focused where the crosshair pointed)
 * and the time of day; any change starts the picture over. F1 hides the controls, Esc leaves.
 */
public class PhotoScreen extends Screen {
    private boolean controls = true;

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        if (!PhotoMode.active()) {
            PhotoMode.begin(this.minecraft);
        }
        this.buttons.clear();
        int y = this.height - 48, x = this.width / 2 - 230;
        this.buttons.add(samplesSlider(1, x, y));
        this.buttons.add(apertureSlider(2, x + 155, y));
        this.buttons.add(sunSlider(3, x + 310, y));
        this.buttons.add(new PtButton(4, this.width / 2 - 155, y + 24, 150, 20, "Focus on crosshair"));
        this.buttons.add(new PtButton(5, this.width / 2 + 5, y + 24, 150, 20, "Done"));
        for (Object o : this.buttons) ((ButtonWidget) o).visible = controls;
    }

    static PtSlider samplesSlider(int id, int x, int y) {
        int last = PhotoMode.SAMPLE_STEPS.length - 1;
        return new PtSlider(id, x, y, PhotoMode.sampleStep(PhotoMode.samples) / (float) last, last,
                v -> "Samples: " + PhotoMode.SAMPLE_STEPS[Math.round(v * last)],
                v -> { PhotoMode.samples = PhotoMode.SAMPLE_STEPS[Math.round(v * last)]; if (PhotoMode.count > PhotoMode.samples) PhotoMode.restart(); });
    }

    /** Lens radius 0..0.5 blocks on a squared scale (small apertures need the finer steps). */
    static PtSlider apertureSlider(int id, int x, int y) {
        return new PtSlider(id, x, y, (float) Math.sqrt(PhotoMode.aperture / PhotoMode.MAX_APERTURE), 50,
                v -> v <= 0f ? "Depth of field: Off" : String.format(java.util.Locale.ROOT, "Aperture: %.3f", PhotoMode.MAX_APERTURE * v * v),
                v -> PhotoMode.aperture = PhotoMode.MAX_APERTURE * v * v);
    }

    /** The world's own time, or a time of day every half hour. */
    static PtSlider sunSlider(int id, int x, int y) {
        return new PtSlider(id, x, y, PhotoMode.sunTime < 0 ? 0f : (PhotoMode.sunTime / 500 + 1) / 48f, 48,
                v -> { int k = Math.round(v * 48); return k == 0 ? "Sun: the world's" : "Sun: " + PhotoMode.clock((k - 1) * 500L); },
                v -> {
                    int k = Math.round(v * 48);
                    PhotoMode.sunTime = k == 0 ? -1 : (k - 1) * 500;
                    if (PhotoMode.active()) PhotoMode.timeOverride = PhotoMode.sunTime;
                });
    }

    @Override
    protected void buttonClicked(ButtonWidget b) {
        if (b.id == 4) { PhotoMode.focusFromCrosshair(this.minecraft); PhotoMode.wantFocus = true; }
        if (b.id == 5) close();
    }

    private void close() {
        this.minecraft.openScreen(null);
        this.minecraft.lockMouse();
    }

    @Override
    protected void keyPressed(char c, int key) {
        if (key == org.lwjgl.input.Keyboard.KEY_F1) {
            controls = !controls;
            for (Object o : this.buttons) ((ButtonWidget) o).visible = controls;
            return;
        }
        if (key == com.alexmcclellan.betapt.config.BetaPTKeys.PHOTO.keyCode) { close(); return; }
        super.keyPressed(c, key);
    }

    @Override
    public void removed() {
        PhotoMode.end(this.minecraft);
        com.alexmcclellan.betapt.config.PtSettings.save();
    }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        // no background: the picture is the world behind this screen
        if (controls) {
            String s = PhotoMode.finished() ? (PhotoMode.status.isEmpty() ? "Saving..." : PhotoMode.status)
                    : "Photo: " + Math.min(PhotoMode.count, PhotoMode.samples) + " / " + PhotoMode.samples + " samples";
            this.drawString(this.textRenderer, s, 6, 6, 0xFFFFFF);
            this.drawString(this.textRenderer, String.format(java.util.Locale.ROOT, "Focus %.1f blocks. F1 hides this, Esc leaves.", PhotoMode.focus), 6, 18, 0xA0A0A0);
        }
        super.render(mouseX, mouseY, delta);
    }
}
