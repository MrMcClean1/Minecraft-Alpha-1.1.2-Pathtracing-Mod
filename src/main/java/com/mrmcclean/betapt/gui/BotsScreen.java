package com.mrmcclean.betapt.gui;

import com.mrmcclean.betapt.bots.BotConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Options > Bots...: the bot list on Tab, and what bots put into a hut or house they build. Saved
 * straight away (config/classicbots.properties).
 */
public class BotsScreen extends Screen {
    private final Screen parent;

    public BotsScreen(Screen parent) { this.parent = parent; }

    private static final int COUNT = 14, ROW = 22;

    /** The first row: the block (toggles, a gap, Done) centred, with room for the title above when there is. */
    private static int top(int h) { int block = (COUNT + 1) / 2 * ROW + 4 + 20; return Math.max(14, Math.min(h / 6, (h - block) / 2)); }

    private static int doneY(int h) { return top(h) + (COUNT + 1) / 2 * ROW + 4; }

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        // two columns, rows 22 apart, the whole block centred by the screen's own height: Done stays on screen at
        // every window size and GUI scale (the smallest is 320 x 240)
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = top(this.height);
        this.buttons.clear();
        for (int id = 1; id <= COUNT; id++)
            this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(id, (id - 1) % 2 == 0 ? left : right, top + (id - 1) / 2 * ROW, 150, 20, text(id)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(200, this.width / 2 - 100, doneY(this.height), 200, 20, "Done"));
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private static String text(int id) {
        return switch (id) {
            case 1 -> "Bot list (Tab): " + onOff(BotConfig.tabList);
            case 2 -> "Doors in houses: " + onOff(BotConfig.houseDoor);
            case 3 -> "Torches: " + onOff(BotConfig.houseTorches);
            case 4 -> "Chests: " + onOff(BotConfig.houseChest);
            case 5 -> "Furnaces: " + onOff(BotConfig.houseFurnace);
            case 6 -> "Beds: " + onOff(BotConfig.houseBed);
            case 7 -> "Bot towns: " + onOff(BotConfig.towns);
            case 8 -> "Machine help: " + onOff(BotConfig.machineDoctor);
            case 9 -> "Voice chat (V): " + onOff(BotConfig.voiceChat);
            case 10 -> "Progression guide: " + onOff(BotConfig.progressionGuide);
            case 11 -> "Names over bots: " + onOff(BotConfig.nametags);
            case 12 -> "Bots (all of it): " + onOff(BotConfig.enabled);
            case 13 -> "Bot chat: " + BotConfig.botChat;
            case 14 -> "Player invulnerable: " + onOff(BotConfig.invulnerable);
            default -> "";
        };
    }

    @Override
    protected void buttonClicked(ButtonWidget b) {
        switch (b.id) {
            case 1 -> BotConfig.tabList = !BotConfig.tabList;
            case 2 -> BotConfig.houseDoor = !BotConfig.houseDoor;
            case 3 -> BotConfig.houseTorches = !BotConfig.houseTorches;
            case 4 -> BotConfig.houseChest = !BotConfig.houseChest;
            case 5 -> BotConfig.houseFurnace = !BotConfig.houseFurnace;
            case 6 -> BotConfig.houseBed = !BotConfig.houseBed;
            case 7 -> BotConfig.towns = !BotConfig.towns;
            case 8 -> BotConfig.machineDoctor = !BotConfig.machineDoctor;
            case 9 -> BotConfig.voiceChat = !BotConfig.voiceChat;
            case 10 -> BotConfig.progressionGuide = !BotConfig.progressionGuide;
            case 11 -> BotConfig.nametags = !BotConfig.nametags;
            case 12 -> BotConfig.enabled = !BotConfig.enabled;
            case 14 -> BotConfig.invulnerable = !BotConfig.invulnerable;
            case 13 -> BotConfig.botChat = BotConfig.botChat.equals("all") ? "replies" : BotConfig.botChat.equals("replies") ? "off" : "all";
            case 200 -> { this.minecraft.openScreen(this.parent); return; }
            default -> { return; }
        }
        BotConfig.save();
        b.message = text(b.id);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        int top = top(this.height), below = doneY(this.height) + 24;
        if (top - 12 >= 2) this.drawCenteredString(this.textRenderer, "Bots", this.width / 2, top - 12, 0xFFFFFF);
        if (below + 8 <= this.height) this.drawCenteredString(this.textRenderer, "Doors to beds: what a bot puts into a house it builds. Tab: hold it to see your bots.", this.width / 2, below, 0xA0A0A0);
        if (below + 18 <= this.height) this.drawCenteredString(this.textRenderer, "Towns, the guide (\"what next?\") and invulnerable are on; furnace help and voice (hold V) are optional.", this.width / 2, below + 10, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}