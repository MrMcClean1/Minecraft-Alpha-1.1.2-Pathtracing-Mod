package com.alexmcclellan.betapt.gui;

import com.alexmcclellan.betapt.render.PhotoMode;
import com.alexmcclellan.betapt.render.TimeLapse;
import net.minecraft.client.gui.screen.Screen;

/**
 * On screen while a time-lapse records (TimeLapse.start): the game is paused, the HUD hidden, each picture adds up
 * photo mode's samples and is saved before the camera moves on. Esc stops it (the pictures so far stay).
 */
public class TimeLapseScreen extends Screen {

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        if (!PhotoMode.active()) {
            PhotoMode.begin(this.minecraft);
        }
    }

    @Override
    public void removed() {
        TimeLapse.finish(this.minecraft, false); // Esc: stop where it is (after the last picture this does nothing)
        PhotoMode.end(this.minecraft);
    }

    @Override
    public boolean isPauseScreen() { return true; }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        String s = String.format(java.util.Locale.ROOT, "Time-lapse: picture %d of %d, %d / %d samples. Esc stops.",
                Math.min(TimeLapse.index() + 1, TimeLapse.frames), TimeLapse.frames, Math.min(PhotoMode.count, PhotoMode.samples), PhotoMode.samples);
        this.drawString(this.textRenderer, s, 6, 6, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }
}
