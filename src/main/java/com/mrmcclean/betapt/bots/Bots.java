package com.mrmcclean.betapt.bots;

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
        Guide.load(new java.io.File(FabricLoader.getInstance().getConfigDir().toFile(), ""));
        LOG.info("Classic Bots {} (say /bot help in chat)", BotConfig.enabled ? "on" : "off; /bot on or enabled=true in config/classicbots.properties");
    }

    /** Called once from the mod's initializer: the bot's entity class (saved as "ClassicBot") and how to make one. */
    public static void setup(Class<? extends BotEntity> cls, Function<World, BotEntity> make) {
        factory = make;
        com.mrmcclean.betapt.bots.mixin.EntityRegistryInvoker.classicbots$register(cls, "ClassicBot", 120);
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
    /** The last line a player typed, whole (the bot test checks a long one arrives in one piece). */
    public static String lastTyped = "";

    public static boolean chat(PlayerEntity p, String msg) {
        if (msg != null) lastTyped = msg;
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
            if (BotConfig.machineDoctor && MachineDoctor.asked(text)) { if (!to.equals("all")) b.brain.say(MachineDoctor.answer(p)); continue; }
            if (o.kind != Orders.Kind.NONE) {
                if (mayOrder) b.brain.order(p, o, text);
                else b.brain.say("Sorry, I only take orders from " + b.owner + ".");
            } else if (!to.equals("all")) b.brain.chat(p, text);
        }
        return false;
    }

    private static Object autoSpawnedIn;
    private static int autoSpawnWait;

    /**
     * Bots join you by themselves (autoSpawn, 3 by default, as Tekkit): a few seconds after you enter a world where
     * you have no bots yet, once per world. Not during the bot test (it makes its own).
     */
    public static void autoSpawn(PlayerEntity p, Object world) {
        if (p == null || world == null || !BotConfig.enabled || BotTest.ON || otherTest()) return;
        if (BotConfig.autoSpawn > 0 && autoSpawnedIn != world) {
            if (++autoSpawnWait < 100) return; // let the world settle first
            autoSpawnWait = 0;
            autoSpawnedIn = world;
            spawnUpTo(p);
            return;
        }
        if (++autoTownWait >= 600) { autoTownWait = 0; autoTown(p); } // every half a minute
    }

    private static int autoTownWait;
    private static java.lang.reflect.Method tracerTest;

    /** A test of the game itself (the path tracer's) is running: no bots join or found towns then (they'd walk into its pictures). */
    static boolean otherTest() {
        if (TestQuiet.on()) return true;
        try {
            if (tracerTest == null) { tracerTest = Class.forName("com.mrmcclean.betapt.test.AlphaTest", false, Bots.class.getClassLoader()).getDeclaredMethod("running"); tracerTest.setAccessible(true); }
            return (Boolean) tracerTest.invoke(null);
        } catch (Throwable t) { return false; }
    }

    /**
     * Towns found themselves (towns on, the default): three or more of your bots on their own, none of them in a town
     * or called off one (/bot town stop), found one where the first of them stands. What it said, or null.
     */
    static String autoTown(PlayerEntity p) {
        if (!BotConfig.towns || p == null) return null;
        List<BotEntity> free = new ArrayList<>();
        for (BotEntity b : bots(Names.world(p))) {
            if (!b.owner.equals(p.name)) continue;
            if (b.brain.town != null || b.brain.townLeft) return null;
            if (b.brain.mode == Brain.Mode.AUTO) free.add(b);
        }
        if (free.size() < 3) return null;
        BotEntity c = free.get(0);
        for (BotEntity b : free) if (b.hasHome) { c = b; break; } // centred on a home one of them has, or where the first stands
        String s = Town.found(Names.world(p), c.hasHome ? c.homeX : (int) Math.floor(c.x), c.hasHome ? c.homeY : (int) Math.floor(c.shape.minY + 0.01), c.hasHome ? c.homeZ : (int) Math.floor(c.z), free);
        tell(p, s + ". They did it on their own: /bot town stop calls it off.");
        return s;
    }

    private static double[] safeSpot;

    /**
     * Player invulnerability (invulnerable=false by default): every hurt is turned away (BotTestPlayerMixin); here, each
     * client tick, health back to full and out of the void: back up where it last stood.
     */
    public static void keepSafe(PlayerEntity p) {
        if (p == null || !BotConfig.invulnerable) return;
        if (p.health > 0 && p.health < 20) p.health = 20;
        if (p.onGround && p.y > 0) safeSpot = new double[]{p.x, p.y, p.z};
        else if (p.y < -32 && safeSpot != null) { p.setPosition(safeSpot[0], safeSpot[1] + 1, safeSpot[2]); p.velocityY = 0; }
    }

    /** Your bots made up to autoSpawn (the bot test checks it); how many it made. */
    static int spawnUpTo(PlayerEntity p) {
        int mine = 0, made = 0;
        for (BotEntity b : bots(Names.world(p))) if (b.owner.equals(p.name)) mine++;
        for (int i = mine; i < Math.min(BotConfig.autoSpawn, BotConfig.maxBotsPerPlayer); i++) { command(p, "spawn"); made++; }
        return made;
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
                b.brain.say("Hi " + p.name + "! I'll get on with things myself. Tell me what to do any time, like \"" + name + " follow me\" or \"" + name + " bring me 16 wood\", and \"" + name + " do your own thing\" sends me back to it.");
                return;
            }
            case "next": { // the progression guide's next step for you
                if (p != null) tell(p, Guide.nextLine(p));
                return;
            }
            case "chat": { // what bots say: /bot chat all|replies|off
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("all") || t.equals("replies") || t.equals("off")) { BotConfig.botChat = t; BotConfig.save(); }
                tell(p, "Bots' chat: " + BotConfig.botChat + (BotConfig.botChat.equals("all") ? " (answers and updates)" : BotConfig.botChat.equals("replies") ? " (only when you speak to them)" : " (silent)") + ". /bot chat all|replies|off");
                return;
            }
            case "invulnerable": { // the player takes no harm: /bot invulnerable on|off (off by default)
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("on") || t.equals("off")) { BotConfig.invulnerable = t.equals("on"); BotConfig.save(); }
                tell(p, "Player invulnerable: " + (BotConfig.invulnerable ? "on (nothing hurts you)" : "off") + ".");
                return;
            }
            case "nametags": { // names over the bots: /bot nametags on|off (on by default)
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("on") || t.equals("off")) { BotConfig.nametags = t.equals("on"); BotConfig.save(); }
                tell(p, "Names over the bots: " + (BotConfig.nametags ? "on" : "off") + ".");
                return;
            }
            case "guide": { // /bot guide on|off
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("on") || t.equals("off")) { BotConfig.progressionGuide = t.equals("on"); BotConfig.save(); }
                tell(p, "Progression guide: " + (BotConfig.progressionGuide ? "on (" + Guide.steps().size() + " steps here). Ask a bot \"what next?\" or /bot next." : "off (/bot guide on)."));
                return;
            }
            case "voice": { // optional voice chat: /bot voice on|off
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("on") || t.equals("off")) { BotConfig.voiceChat = t.equals("on"); BotConfig.save(); }
                tell(p, BotConfig.voiceChat ? Voice.describe(VoiceChat.gameDir) : "Voice chat is off (/bot voice on).");
                return;
            }
            case "doctor": { // optional machine help: /bot doctor on|off
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("on") || t.equals("off")) { BotConfig.machineDoctor = t.equals("on"); BotConfig.save(); }
                tell(p, "Machine help is " + (BotConfig.machineDoctor ? "on: look at a furnace and ask a bot \"why isn't this working?\"" : "off (/bot doctor on)") + ".");
                return;
            }
            case "town": { // optional bot towns: /bot town on|off|start|stop, or the status
                String t = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (t.equals("on") || t.equals("off")) { BotConfig.towns = t.equals("on"); BotConfig.save(); tell(p, "Bot towns " + t + "."); return; }
                if (!BotConfig.towns) { tell(p, "Bot towns are off. /bot town on first."); return; }
                List<BotEntity> mine = new ArrayList<>();
                for (BotEntity b : bots(w)) if (p != null && (b.owner.equals(p.name) || BotConfig.anyoneCommands)) mine.add(b);
                if (t.equals("start") && p != null) { if (mine.isEmpty()) tell(p, "No bots of yours here."); else tell(p, Town.found(p, mine)); return; }
                if (t.equals("stop")) { for (BotEntity b : mine) { Town.leave(b); b.brain.townLeft = true; } tell(p, "The bots stop working on the town (its buildings stay)."); return; }
                tell(p, Town.status(w, bots(w)));
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
                // "model off" frees the model's graphics memory for the path tracer; "model on" brings it back (the name stays)
                if (a.length > 1 && (a[1].equalsIgnoreCase("off") || a[1].equalsIgnoreCase("on"))) {
                    BotConfig.modelOn = a[1].equalsIgnoreCase("on"); BotConfig.save();
                    if (!BotConfig.modelOn) Model.unload();
                } else if (a.length > 1) { BotConfig.model = a[1]; if (a.length > 2) BotConfig.modelUrl = a[2]; BotConfig.modelOn = true; BotConfig.save(); }
                tell(p, "Model: " + (BotConfig.modelOn ? BotConfig.model + " at " + BotConfig.modelUrl + " (" + Model.status + ")" : "off") + ". Without it the bots still work by rules.");
                return;
            }
            case "grief": BotConfig.allowGrief = flag(a, BotConfig.allowGrief); BotConfig.save(); tell(p, "Bots may break built blocks: " + BotConfig.allowGrief); return;
            case "chests": BotConfig.othersChests = flag(a, BotConfig.othersChests); BotConfig.save(); tell(p, "Bots may use chests away from home: " + BotConfig.othersChests); return;
            case "anyone": BotConfig.anyoneCommands = flag(a, BotConfig.anyoneCommands); BotConfig.save(); tell(p, "Anyone may give bots orders: " + BotConfig.anyoneCommands); return;
            case "map": BotConfig.showOnMap = flag(a, BotConfig.showOnMap); BotConfig.save(); tell(p, "Bots on the minimap: " + BotConfig.showOnMap); return;
            case "timber": BotConfig.timber = flag(a, BotConfig.timber); BotConfig.save(); tell(p, "Bot timber (verified trees): " + (BotConfig.timber ? "on" : "off") + "."); return;
            default:
                tell(p, "/bot on|off, spawn [name], list, remove <name>, tp <name>, model [name] [url] | model off|on, grief|chests|anyone|map|timber on|off, town [on|off|start|stop], doctor [on|off], voice [on|off], next, guide [on|off], nametags [on|off], chat [all|replies|off], invulnerable [on|off]");
                tell(p, "Talk to a bot by name: \"Ezra follow me\", \"Ezra bring me 16 iron\", \"Ezra set up a base here\",");
                tell(p, "\"Ezra keep me stocked with 32 coal\", \"Ezra put things away\", \"bots guard\". Right-click a bot to give it what you hold.");
        }
    }

    private static boolean flag(String[] a, boolean cur) { return a.length > 1 ? a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true") : !cur; }
}
