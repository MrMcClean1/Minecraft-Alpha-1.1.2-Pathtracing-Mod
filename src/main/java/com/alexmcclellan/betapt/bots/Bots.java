package com.alexmcclellan.betapt.bots;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Function;

/**
 * The bots' glue: chat in (orders and /bot commands), chat out, spawning, and shared per-tick work. Single player and
 * server alike; the client-only and server-only halves live in BotsClient and the server mixin.
 */
public final class Bots {
    private Bots() {}

    public static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("ClassicBots");
    /** Makes bot entities; the modpack swaps in one that also syncs to multiplayer clients. */
    public static Function<World, BotEntity> factory = BotEntity::new;
    /** Sends a line to everyone on a server (set by the server side); null in single player. */
    public static java.util.function.Consumer<String> serverChat;
    private static boolean loaded;
    private static long lastTick = -1;

    public static void init() {
        if (loaded) return;
        loaded = true;
        BotConfig.load(new java.io.File(FabricLoader.getInstance().getConfigDir().toFile(), ""));
        LOG.info("Classic Bots {} (say /bot help in chat)", BotConfig.enabled ? "on" : "off; /bot on or enabled=true in config/classicbots.properties");
    }

    /** Called once from the mod's initializer: the bot's entity class (saved as "ClassicBot") and how to make one. */
    public static void setup(Class<? extends BotEntity> cls, Function<World, BotEntity> make) {
        factory = make;
        com.alexmcclellan.betapt.bots.mixin.EntityRegistryInvoker.classicbots$register(cls, "ClassicBot", 120);
        init();
    }

    public static void log(String s) { LOG.info(s); }

    /** The block for an id, or null (StationAPI worlds can hold ids past the table). */
    public static Block block(int id) { return id > 0 && id < Block.BY_ID.length ? Block.BY_ID[id] : null; }

    static void tickShared(World w) {
        long t = w.ticks;
        if (t == lastTick) return;
        lastTick = t;
        Model.poll();
        if (BotConfig.showOnMap && client() && t % 40 == 0) try { BotsClient.markers(w); } catch (Throwable ignored) {}
    }

    static boolean client() { return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT; }

    public static void broadcast(World w, String msg) {
        if (serverChat != null && !client()) serverChat.accept(msg);
        else if (client()) BotsClient.show(msg);
        LOG.info(msg);
    }

    /** To one player (a command's answer). */
    static void tell(PlayerEntity p, String msg) {
        if (p == null) { LOG.info(msg); return; }
        Names.tell(p, msg);
    }

    public static PlayerEntity player(World w, String name) {
        if (name == null || name.isEmpty()) return null;
        for (Object o : w.players) if (name.equals(((PlayerEntity) o).name)) return (PlayerEntity) o;
        return null;
    }

    public static List<BotEntity> bots(World w) {
        List<BotEntity> l = new ArrayList<>();
        for (Object o : w.entities) if (o instanceof BotEntity && !((BotEntity) o).removed) l.add((BotEntity) o);
        return l;
    }

    // ------------------------------------------------------------------ names

    /** A block's or item's name key, lowercase (see Names). */
    public static String key(int id) { return Names.key(id); }

    /** What players call it. */
    public static String name(int id) { return Names.name(id); }

    // ------------------------------------------------------------------ chat in

    /** A player said something. True if it was a /bot command (and shouldn't go further). */
    public static boolean chat(PlayerEntity p, String msg) {
        init();
        String m = msg.trim();
        if (m.toLowerCase(Locale.ROOT).startsWith("/bot")) { command(p, m.substring(4).trim()); return true; }
        if (!BotConfig.enabled || p == null) return false;
        List<BotEntity> mine = bots(Names.world(p));
        if (mine.isEmpty()) return false;
        Map<String, BotEntity> byName = new LinkedHashMap<>();
        for (BotEntity b : mine) byName.put(b.botName(), b);
        String to = Orders.addressee(m, byName.keySet());
        if (to == null) return false;
        String text = Orders.strip(m, to.equals("all") ? m.toLowerCase(Locale.ROOT).startsWith("bots") ? "bots" : m.toLowerCase(Locale.ROOT).startsWith("all bots") ? "all bots" : "everyone" : to);
        Orders.Order o = Orders.parse(text);
        for (BotEntity b : mine) {
            if (!to.equals("all") && !b.botName().equals(to)) continue;
            if (to.equals("all") && !b.owner.equals(p.name) && !BotConfig.anyoneCommands) continue;
            boolean mayOrder = b.owner.equals(p.name) || BotConfig.anyoneCommands;
            if (o.kind != Orders.Kind.NONE) {
                if (mayOrder) b.brain.order(p, o, text);
                else b.brain.say("Sorry, I only take orders from " + b.owner + ".");
            } else if (!to.equals("all")) b.brain.chat(p, text);
        }
        return false;
    }

    private static void command(PlayerEntity p, String args) {
        String[] a = args.isEmpty() ? new String[]{"help"} : args.split("\\s+");
        String c = a[0].toLowerCase(Locale.ROOT);
        World w = p != null ? Names.world(p) : null;
        switch (c) {
            case "on": case "off":
                BotConfig.enabled = c.equals("on"); BotConfig.save();
                tell(p, "Bots are " + c + "." + (BotConfig.enabled ? " /bot spawn [name] to make one." : ""));
                return;
            case "spawn": case "new": case "add": {
                if (!BotConfig.enabled) { tell(p, "Bots are off. /bot on first."); return; }
                if (p == null) return;
                int mineCount = 0;
                Set<String> used = new HashSet<>();
                for (BotEntity b : bots(w)) { used.add(b.botName().toLowerCase(Locale.ROOT)); if (b.owner.equals(p.name)) mineCount++; }
                if (mineCount >= BotConfig.maxBotsPerPlayer) { tell(p, "You have " + mineCount + " bots already (maxBotsPerPlayer)."); return; }
                String name = a.length > 1 ? a[1].replaceAll("[^A-Za-z0-9_]", "") : null;
                if (name == null || name.isEmpty()) for (String n : Orders.NAMES) if (!used.contains(n.toLowerCase(Locale.ROOT))) { name = n; break; }
                if (name == null || used.contains(name.toLowerCase(Locale.ROOT))) { tell(p, "That name is taken."); return; }
                BotEntity b = factory.apply(w);
                b.setBotName(name);
                b.owner = p.name;
                double ang = Math.toRadians(p.yaw);
                b.setPositionAndAngles(p.x - Math.sin(ang) * 2, p.y - p.eyeHeight + 0.1, p.z + Math.cos(ang) * 2, p.yaw + 180, 0);
                if (w.isMultiplayer) { tell(p, "Bots run on the server; this server doesn't have them."); return; }
                w.addEntity(b);
                b.brain.say("Hi " + p.name + "! Tell me what to do, like \"" + name + " follow me\" or \"" + name + " bring me 16 wood\".");
                return;
            }
            case "list": {
                List<BotEntity> l = bots(w);
                if (l.isEmpty()) { tell(p, "No bots nearby."); return; }
                for (BotEntity b : l) tell(p, b.botName() + " (" + b.owner + ") at " + (int) b.x + ", " + (int) b.y + ", " + (int) b.z + ": " + b.brain.statusLine());
                return;
            }
            case "remove": case "dismiss": case "kill": {
                for (BotEntity b : bots(w)) if (a.length > 1 && b.botName().equalsIgnoreCase(a[1])) {
                    if (p != null && !b.owner.equals(p.name) && !BotConfig.anyoneCommands) { tell(p, "That's " + b.owner + "'s bot."); return; }
                    b.dropAll(); b.remove(); tell(p, b.botName() + " left (dropped what it carried)."); return;
                }
                tell(p, "No bot called that nearby.");
                return;
            }
            case "tp": case "bring": {
                for (BotEntity b : bots(w)) if (p != null && a.length > 1 && b.botName().equalsIgnoreCase(a[1])) { b.setPositionAndAngles(p.x, p.y - p.eyeHeight + 0.1, p.z, p.yaw, 0); return; }
                tell(p, "No bot called that in this world.");
                return;
            }
            case "model": {
                if (a.length > 1) { BotConfig.model = a[1]; if (a.length > 2) BotConfig.modelUrl = a[2]; BotConfig.modelOn = !a[1].equalsIgnoreCase("off"); BotConfig.save(); }
                tell(p, "Model: " + (BotConfig.modelOn ? BotConfig.model + " at " + BotConfig.modelUrl + " (" + Model.status + ")" : "off") + ". Without it the bots still work by rules.");
                return;
            }
            case "grief": BotConfig.allowGrief = flag(a, BotConfig.allowGrief); BotConfig.save(); tell(p, "Bots may break built blocks: " + BotConfig.allowGrief); return;
            case "chests": BotConfig.othersChests = flag(a, BotConfig.othersChests); BotConfig.save(); tell(p, "Bots may use chests away from home: " + BotConfig.othersChests); return;
            case "anyone": BotConfig.anyoneCommands = flag(a, BotConfig.anyoneCommands); BotConfig.save(); tell(p, "Anyone may give bots orders: " + BotConfig.anyoneCommands); return;
            case "map": BotConfig.showOnMap = flag(a, BotConfig.showOnMap); BotConfig.save(); tell(p, "Bots on the minimap: " + BotConfig.showOnMap); return;
            default:
                tell(p, "/bot on|off, spawn [name], list, remove <name>, tp <name>, model [name] [url], grief|chests|anyone|map on|off");
                tell(p, "Talk to a bot by name: \"Ezra follow me\", \"Ezra bring me 16 iron\", \"Ezra set up a base here\",");
                tell(p, "\"Ezra keep me stocked with 32 coal\", \"Ezra put things away\", \"bots guard\". Right-click a bot to give it what you hold.");
        }
    }

    private static boolean flag(String[] a, boolean cur) { return a.length > 1 ? a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true") : !cur; }
}
