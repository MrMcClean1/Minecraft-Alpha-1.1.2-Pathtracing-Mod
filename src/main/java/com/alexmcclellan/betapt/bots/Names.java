package com.alexmcclellan.betapt.bots;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

/** Alpha 1.1.2_01 has no block or item names at all, so the bots carry their own; plus the block facts they need. */
public final class Names {
    private Names() {}

    private static final String[] NAMES = new String[400];
    static {
        String[] b = {"", "stone", "grass", "dirt", "cobblestone", "planks", "sapling", "bedrock", "water", "water", "lava", "lava", "sand", "gravel",
                "gold ore", "iron ore", "coal ore", "wood", "leaves", "sponge", "glass", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "wool",
                "", "dandelion", "rose", "brown mushroom", "red mushroom", "gold block", "iron block", "double slab", "slab", "bricks", "tnt", "bookshelf",
                "mossy cobblestone", "obsidian", "torch", "fire", "mob spawner", "wooden stairs", "chest", "redstone wire", "diamond ore", "diamond block",
                "crafting table", "crops", "farmland", "furnace", "furnace", "sign", "wooden door", "ladder", "rail", "cobblestone stairs", "sign", "lever",
                "pressure plate", "iron door", "pressure plate", "redstone ore", "redstone ore", "redstone torch", "redstone torch", "button", "snow",
                "ice", "snow block", "cactus", "clay", "sugar cane", "jukebox", "fence", "pumpkin", "netherrack", "soul sand", "glowstone", "portal",
                "jack o lantern"};
        System.arraycopy(b, 0, NAMES, 0, b.length);
        String[] it = {"iron shovel", "iron pickaxe", "iron axe", "flint and steel", "apple", "bow", "arrow", "coal", "diamond", "iron ingot",
                "gold ingot", "iron sword", "wooden sword", "wooden shovel", "wooden pickaxe", "wooden axe", "stone sword", "stone shovel",
                "stone pickaxe", "stone axe", "diamond sword", "diamond shovel", "diamond pickaxe", "diamond axe", "stick", "bowl", "mushroom stew",
                "gold sword", "gold shovel", "gold pickaxe", "gold axe", "string", "feather", "gunpowder", "wooden hoe", "stone hoe", "iron hoe",
                "diamond hoe", "gold hoe", "seeds", "wheat", "bread", "leather cap", "leather tunic", "leather pants", "leather boots", "chain helmet",
                "chain chestplate", "chain leggings", "chain boots", "iron helmet", "iron chestplate", "iron leggings", "iron boots", "diamond helmet",
                "diamond chestplate", "diamond leggings", "diamond boots", "gold helmet", "gold chestplate", "gold leggings", "gold boots", "flint",
                "porkchop", "cooked porkchop", "painting", "golden apple", "sign", "wooden door", "bucket", "water bucket", "lava bucket", "minecart",
                "saddle", "iron door", "redstone", "snowball", "boat", "leather", "milk bucket", "brick", "clay", "sugar cane", "paper", "book",
                "slimeball", "storage minecart", "powered minecart", "egg", "compass", "fishing rod", "clock", "glowstone dust", "fish", "cooked fish"};
        System.arraycopy(it, 0, NAMES, 256, it.length);
    }

    static String key(int id) { return id >= 0 && id < NAMES.length && NAMES[id] != null ? NAMES[id] : ""; }

    static String name(int id) { String k = key(id); return k.isEmpty() ? "thing " + id : k; }

    /** How long a block takes to break (Alpha keeps it protected). */
    static float hardness(Block b) { return ((com.alexmcclellan.betapt.bots.mixin.BlockAccessor) b).classicbots$miningTime(); }

    /** Breaks by hand with drops (anything but stone, metal and snow). */
    static boolean handMineable(Block b) {
        Material m = b.material;
        return m != Material.STONE && m != Material.IRON && m != Material.SNOW && m != Material.SNOW_LAYER;
    }

    static void breakEffect(net.minecraft.world.World w, int x, int y, int z, int id, int meta) {
        Block b = id > 0 && id < Block.BY_ID.length ? Block.BY_ID[id] : null;
        if (b != null) w.playSound(x + 0.5, y + 0.5, z + 0.5, b.sounds.getBreakSound(), (b.sounds.volume + 1.0F) / 2.0F, b.sounds.pitch * 0.8F);
    }

    /** Alpha single player: an answer to a command shows in chat. */
    static void tell(net.minecraft.entity.mob.player.PlayerEntity p, String msg) { BotsClient.show(msg); }

    public static float eye(net.minecraft.entity.Entity e) { return e.height * 0.85F; }

    public static net.minecraft.world.World world(net.minecraft.entity.Entity e) { return ((com.alexmcclellan.betapt.bots.mixin.EntityWorldAccessor) e).classicbots$world(); }

    private static final int[] HUNGER = new int[400];
    static { HUNGER[260] = 4; HUNGER[282] = 10; HUNGER[297] = 5; HUNGER[319] = 3; HUNGER[320] = 8; HUNGER[322] = 42; HUNGER[349] = 2; HUNGER[350] = 5; }
    static int food(int id) { return id >= 0 && id < HUNGER.length ? HUNGER[id] : 0; }
}
