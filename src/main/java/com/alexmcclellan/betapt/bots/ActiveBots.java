package com.alexmcclellan.betapt.bots;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The bots in this world, read the same way as in Beta PT (where the modpack carries its own copy), which
 * then takes over (BotsMixinPlugin). The Tab list and the Bots page go through here so they work with either.
 */
public final class ActiveBots {
    private ActiveBots() {}

    private static final String PKG = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("eebalance")
            ? "com.alexmcclellan.eebalance.bots." : "com.alexmcclellan.betapt.bots.";

    private static Class<?> cls(String n) throws ClassNotFoundException { return Class.forName(PKG + n); }

    /** One bot for the list. */
    public static final class Line { public String name, status; public int health; public Entity entity; }

    private static boolean warned;

    public static List<Line> list(World w) {
        List<Line> out = new ArrayList<>();
        if (!PKG.contains("eebalance")) { // this mod's own bots: no reflection needed
            for (BotEntity b : Bots.bots(w)) { Line l = new Line(); l.entity = b; l.name = b.botName(); l.health = b.health; l.status = b.brain.statusLine(); out.add(l); }
            return out;
        }
        try {
            for (Object o : (List<?>) cls("Bots").getMethod("bots", World.class).invoke(null, w)) {
                Line l = new Line();
                l.entity = (Entity) o;
                l.name = (String) o.getClass().getMethod("botName").invoke(o);
                l.health = ((net.minecraft.entity.mob.MobEntity) o).health; // a Minecraft field: its name is different at run time, so not by reflection
                Object brain = o.getClass().getField("brain").get(o);
                l.status = (String) brain.getClass().getMethod("statusLine").invoke(brain);
                out.add(l);
            }
        } catch (Throwable t) { if (!warned) { warned = true; Bots.log("Bot list: couldn't read the modpack's bots: " + t); } }
        return out;
    }

    public static boolean flag(String field) {
        try { return cls("BotConfig").getField(field).getBoolean(null); } catch (Throwable t) { return true; }
    }

    public static void toggle(String field) {
        try {
            Class<?> c = cls("BotConfig");
            c.getField(field).setBoolean(null, !c.getField(field).getBoolean(null));
            c.getMethod("save").invoke(null);
        } catch (Throwable ignored) {}
    }
}
