package com.alexmcclellan.betapt.bots;

import net.minecraft.block.Block;

import java.util.*;

/**
 * What a word like "iron" means to a bot: which blocks to mine, which items count toward the goal, and whether it has
 * to smelt (iron ore into ingots) or hunt (pork, wool, leather). Unknown words are matched against block names, so
 * modded ores (IndustrialCraft copper, tin, uranium...) work too.
 */
public final class Want {
    public final String label;
    public final Set<Integer> mine = new HashSet<>(), items = new HashSet<>();
    public int smeltFrom, smeltTo;
    public String[] prey;

    private Want(String label) { this.label = label; }

    public boolean food() { return prey != null; }

    public boolean prey(String cls) {
        if (prey == null) return false;
        for (String p : prey) if (cls.contains(p)) return true;
        return false;
    }

    private static Want w(String label, int[] mine, int... items) {
        Want w = new Want(label);
        for (int m : mine) w.mine.add(m);
        for (int i : items) w.items.add(i);
        return w;
    }

    private static Want hunt(String label, String[] prey, int... items) {
        Want w = new Want(label);
        w.prey = prey;
        for (int i : items) w.items.add(i);
        return w;
    }

    public static Want of(String word) {
        if (word == null) return null;
        String t = word.toLowerCase(Locale.ROOT).trim().replaceAll("s$", "").replace("blocks", "").replace("block", "").trim();
        switch (t) {
            case "wood": case "log": case "lumber": case "timber": case "tree": case "oak": return w("wood", new int[]{17}, 17);
            case "plank": case "wooden plank": case "wood plank": { Want w = w("wood", new int[]{17}, 17); return w; }
            case "stone": case "cobble": case "cobblestone": case "rock": return w("cobblestone", new int[]{1, 4}, 4);
            case "dirt": case "soil": return w("dirt", new int[]{2, 3}, 3);
            case "sand": return w("sand", new int[]{12}, 12);
            case "gravel": return w("gravel", new int[]{13}, 13);
            case "flint": return w("flint", new int[]{13}, 318);
            case "clay": return w("clay", new int[]{82}, 337);
            case "coal": case "coal ore": return w("coal", new int[]{16}, 263);
            case "iron ore": return w("iron ore", new int[]{15}, 15);
            case "iron": case "iron ingot": { Want w = w("iron", new int[]{15}, 265); w.smeltFrom = 15; w.smeltTo = 265; return w; }
            case "gold ore": return w("gold ore", new int[]{14}, 14);
            case "gold": case "gold ingot": { Want w = w("gold", new int[]{14}, 266); w.smeltFrom = 14; w.smeltTo = 266; return w; }
            case "glass": { Want w = w("glass", new int[]{12}, 20); w.smeltFrom = 12; w.smeltTo = 20; return w; }
            case "diamond": case "diamond ore": return w("diamonds", new int[]{56}, 264);
            case "redstone": case "redstone ore": return w("redstone", new int[]{73, 74}, 331);
            case "lapi": case "lapis": case "lapis lazuli": return w("lapis", new int[]{21}, 351);
            case "obsidian": return w("obsidian", new int[]{49}, 49);
            case "sugar cane": case "sugarcane": case "reed": case "cane": return w("sugar cane", new int[]{83}, 338);
            case "cactu": case "cactus": return w("cactus", new int[]{81}, 81);
            case "snow": case "snowball": return w("snowballs", new int[]{78, 80}, 332);
            case "glowstone": case "glowstone dust": return w("glowstone", new int[]{89}, 348);
            case "netherrack": return w("netherrack", new int[]{87}, 87);
            case "soul sand": return w("soul sand", new int[]{88}, 88);
            case "pumpkin": return w("pumpkins", new int[]{86}, 86);
            case "mushroom": return w("mushrooms", new int[]{39, 40}, 39, 40);
            case "flower": case "rose": case "dandelion": return w("flowers", new int[]{37, 38}, 37, 38);
            case "sapling": return w("saplings", new int[]{18}, 6);
            case "apple": return w("apples", new int[]{18}, 260);
            case "food": case "meat": case "pork": case "porkchop": case "pork chop": return hunt("pork", new String[]{"pig"}, 319, 320);
            case "wool": return hunt("wool", new String[]{"sheep"}, 35);
            case "leather": return hunt("leather", new String[]{"cow"}, 334);
            case "feather": return hunt("feathers", new String[]{"chicken"}, 288);
            case "egg": return hunt("eggs", new String[]{"chicken"}, 344);
            case "string": return hunt("string", new String[]{"spider"}, 287);
            case "bone": return hunt("bones", new String[]{"skeleton"}, 352);
            case "gunpowder": case "sulphur": case "sulfur": return hunt("gunpowder", new String[]{"creeper"}, 289);
            case "slime": case "slimeball": case "slime ball": return hunt("slime balls", new String[]{"slime"}, 341);
        }
        // anything else: blocks whose name has the word (modded ores included)
        String key = t.replace(" ", "");
        Want w = new Want(word);
        Random r = new Random(1);
        for (int id = 1; id < Block.BY_ID.length; id++) {
            Block b = Bots.block(id);
            if (b == null) continue;
            String k = Bots.key(id).replace(" ", "");
            if (!k.contains(key)) continue;
            if (key.length() < 3 && !k.equals(key)) continue;
            w.mine.add(id);
            int drop = b.getDropItem(0, r);
            w.items.add(drop > 0 ? drop : id);
        }
        if (w.mine.isEmpty()) return null;
        // prefer ores when the word names a material ("copper" -> copper ore, not copper blocks someone built)
        Set<Integer> ores = new HashSet<>();
        for (int id : w.mine) if (Bots.key(id).contains("ore")) ores.add(id);
        if (!ores.isEmpty()) { w.mine.retainAll(ores); }
        return w;
    }
}
