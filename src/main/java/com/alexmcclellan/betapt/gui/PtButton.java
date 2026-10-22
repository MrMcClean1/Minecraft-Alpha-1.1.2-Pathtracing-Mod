package com.alexmcclellan.betapt.gui;

import net.minecraft.client.gui.widget.ButtonWidget;

/** Alpha's sized-button constructor is protected: this opens it to our screens. */
public class PtButton extends ButtonWidget {
    public PtButton(int id, int x, int y, String text) { super(id, x, y, text); }
    public PtButton(int id, int x, int y, int width, int height, String text) { super(id, x, y, width, height, text); }
}
