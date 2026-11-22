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

    @Override
    @SuppressWarnings("unchecked")
    public void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, top = this.height / 6;
        this.buttons.clear();
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(1, left, top, 150, 20, text(1)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(2, right, top, 150, 20, text(2)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(3, left, top + 24, 150, 20, text(3)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(4, right, top + 24, 150, 20, text(4)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(5, left, top + 48, 150, 20, text(5)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(6, right, top + 48, 150, 20, text(6)));
        // optional features, off until switched on here (or with /bot town|doctor|voice on)
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(7, left, top + 96, 150, 20, text(7)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(8, right, top + 96, 150, 20, text(8)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(9, left, top + 120, 150, 20, text(9)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(10, right, top + 120, 150, 20, text(10)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(11, left, top + 144, 150, 20, text(11)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(12, right, top + 144, 150, 20, text(12)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(13, left, top + 168, 150, 20, text(13)));
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(200, this.width / 2 - 100, top + 196, 200, 20, "Done"));
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
        int top = this.height / 6;
        if (top - 20 >= 2) this.drawCenteredString(this.textRenderer, "Bots", this.width / 2, top - 20, 0xFFFFFF);
        this.drawCenteredString(this.textRenderer, "What a bot puts into a hut or house it builds. Tab: hold it to see your bots.", this.width / 2, top + 76, 0xA0A0A0);
        this.drawCenteredString(this.textRenderer, "Towns (/bot town start) and the guide (\"what next?\") are on; furnace help and voice (hold V) are optional.", this.width / 2, top + 84, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }
}