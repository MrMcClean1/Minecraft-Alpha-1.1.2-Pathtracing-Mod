package com.alexmcclellan.betapt.gui;

import com.alexmcclellan.betapt.render.PathTracer;
import com.alexmcclellan.betapt.render.VkBridge;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Path Tracer > Effects > NVIDIA RTX...: extras an NVIDIA RTX card can use, all off unless switched on here. DLSS is
 * picked with the main page's Upscaler button. DLSS, SER and micromaps need the Vulkan tracer (Hardware RT: ON);
 * switching one restarts the Vulkan tracer. Saved with the main page's Done.
 */
public class RtxScreen extends Screen {
    private final Screen parent;

    public RtxScreen(Screen parent) { this.parent = parent; }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(1, left, top, 150, 20, text(1)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(2, right, top, 150, 20, text(2)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(3, left, top + 24, 150, 20, text(3)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(4, right, top + 24, 150, 20, text(4)));
        this.buttons.add(new com.alexmcclellan.betapt.gui.PtButton(200, this.width / 2 - 100, top + 108, 200, 20, "Done"));
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private static String text(int id) {
        PathTracer pt = PathTracer.INSTANCE;
        return switch (id) {
            case 1 -> "Upscaler DLSS: " + (pt.upscaler == PathTracer.UPSCALER_DLSS ? "ON" : "OFF");
            case 2 -> "Low latency: " + (pt.lowLatency == 2 ? "NVIDIA Reflex" : pt.lowLatency == 1 ? "ON" : "OFF");
            case 3 -> "Ray reordering (SER): " + onOff(pt.ser);
            case 4 -> "Opacity micromaps: " + onOff(pt.omm);
            default -> "";
        };
    }

    private static final String[] HELP = {
            "",
            "DLSS upscales instead of FSR. Render scale picks the mode.",
            "ON: no frames wait in line (any GPU). Reflex: NVIDIA's.",
            "RTX 40/50: regroups rays by what they hit. Same picture.",
            "RTX 40/50: plants' see-through pixels skipped in hardware.",
    };

    @Override
    protected void buttonClicked(ButtonWidget b) {
        PathTracer pt = PathTracer.INSTANCE;
        switch (b.id) {
            case 1 -> { pt.upscaler = pt.upscaler == PathTracer.UPSCALER_DLSS ? PathTracer.UPSCALER_FSR : PathTracer.UPSCALER_DLSS; VkBridge.reopen(); }
            case 2 -> { pt.lowLatency = (pt.lowLatency + 1) % 3; VkBridge.reopen(); }
            case 3 -> { pt.ser = !pt.ser; VkBridge.reopen(); }
            case 4 -> { pt.omm = !pt.omm; VkBridge.reopen(); }
            case 200 -> { this.minecraft.openScreen(this.parent); return; }
            default -> { return; }
        }
        b.message = text(b.id);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int top = this.height / 6;
        if (top - 20 >= 2) this.drawCenteredString(this.textRenderer, "NVIDIA RTX", this.width / 2, top - 20, 0xFFFFFF);
        int hovered = 0;
        for (Object o : this.buttons) {
            ButtonWidget w = (ButtonWidget) o;
            if (w.id >= 1 && w.id <= 4 && mouseX >= w.x && mouseY >= w.y && mouseX < w.x + 150 && mouseY < w.y + 20) hovered = w.id;
        }
        String status = VkBridge.nvStatus.isEmpty() ? "Status shows once the Vulkan tracer runs (Hardware RT: ON)." : VkBridge.nvStatus;
        this.drawCenteredString(this.textRenderer, hovered > 0 ? HELP[hovered] : "", this.width / 2, top + 54, 0xA0A0A0);
        // the status can be long: split it over lines that fit
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        int maxChars = Math.max(20, (this.width - 20) / 6); // about 6 pixels a letter
        for (String word : status.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) { lines.add(line.toString()); line.setLength(0); }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        lines.add(line.toString());
        for (int i = 0; i < Math.min(3, lines.size()); i++)
            this.drawCenteredString(this.textRenderer, lines.get(i), this.width / 2, top + 68 + i * 11, 0x80FF80);
        super.render(mouseX, mouseY, delta);
    }
}
