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
        this.buttons.add(new com.mrmcclean.betapt.gui.PtButton(200, this.width / 2 - 100, top + 96, 200, 20, "Done"));
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
        super.render(mouseX, mouseY, delta);
    }
}