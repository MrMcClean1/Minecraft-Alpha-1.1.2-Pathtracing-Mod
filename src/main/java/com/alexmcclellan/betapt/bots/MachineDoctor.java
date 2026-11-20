package com.alexmcclellan.betapt.bots;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import java.util.*;
import java.util.regex.Pattern;

/**
 * "Why isn't my furnace working?" (optional: machineDoctor=true in classicbots.properties, or /bot doctor on). The bot
 * looks at the furnace the player is looking at (the nearest one in front of them, within 6 blocks) and says what's
 * wrong in plain words: nothing to smelt, something that doesn't smelt, no fuel, something that isn't fuel, a full or
 * blocked output slot. The same questions as the Tekkit bots' machine help, for the furnace, the one machine here.
 */
public final class MachineDoctor {
    private MachineDoctor() {}

    private static final Pattern ASK = Pattern.compile(
            ".*\\b(why (is ?n.?t|isnt|is not|won.?t|wont|does ?n.?t|doesnt|does not|aint|ain.?t|has it stopped|did it stop)\\b.*\\b(work|working|run|running|go|going|on|smelt|smelting|doing anything|do anything|make|making|stop|stopped)"
                    + "|what.?s (wrong|up) with (this|that|my|the)\\b"
                    + "|(check|look at|fix) (this|that|my|the) (furnace|machine|oven)).*");

    static boolean asked(String text) { return ASK.matcher(" " + text.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ").replaceAll("\\s+", " ") + " ").matches(); }

    /** The furnace the player looks at: {x, y, z}, or null. */
    static int[] lookedAt(PlayerEntity p) {
        World w = Names.world(p);
        double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
        double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
        int px = (int) Math.floor(p.x), py = (int) Math.floor(p.y), pz = (int) Math.floor(p.z);
        int[] best = null; double bestDot = 0.6;
        for (int x = px - 6; x <= px + 6; x++) for (int y = py - 4; y <= py + 3; y++) for (int z = pz - 6; z <= pz + 6; z++) {
            int id = w.getBlock(x, y, z);
            if (id != 61 && id != 62) continue;
            double dx = x + 0.5 - p.x, dy = y + 0.5 - p.y, dz = z + 0.5 - p.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 6.5 || d < 0.1) continue;
            double dot = (dx * lx + dy * ly + dz * lz) / d;
            if (dot > bestDot) { bestDot = dot; best = new int[]{x, y, z}; }
        }
        return best;
    }

    static String answer(PlayerEntity p) {
        int[] f = lookedAt(p);
        if (f == null) return "Look at the furnace you mean (within 6 blocks) and ask me again.";
        List<String> problems = new ArrayList<>();
        String fine = diagnose(Names.world(p), f[0], f[1], f[2], problems);
        String where = "The furnace at " + f[0] + " " + f[1] + " " + f[2];
        return problems.isEmpty() ? where + ": nothing's wrong, " + fine + "." : where + ": " + String.join("; ", problems) + ".";
    }

    /** What's wrong with the furnace at x y z (into problems); what's fine otherwise. */
    static String diagnose(World w, int x, int y, int z, List<String> problems) {
        BlockEntity be = w.getBlockEntity(x, y, z);
        if (!(be instanceof Inventory) || ((Inventory) be).getSize() < 3) { problems.add("I can't see inside it"); return ""; }
        Inventory inv = (Inventory) be;
        ItemStack in = inv.getItem(0), fuel = inv.getItem(1), out = inv.getItem(2);
        boolean burning = w.getBlock(x, y, z) == 62;
        int result = in == null ? 0 : smeltsTo(in.id);
        if (in == null) problems.add("there's nothing in it to smelt (the top slot)");
        else if (result == 0) problems.add(Bots.name(in.id) + " can't be smelted");
        if (out != null && result != 0 && (out.id != result || out.size >= out.getMaxSize()))
            problems.add(out.id == result ? "the output slot is full (" + out.size + " " + Bots.name(out.id) + "): take them out" : "the output slot holds " + Bots.name(out.id) + ", so the " + Bots.name(result) + " has nowhere to go");
        if (!burning) {
            if (fuel == null) problems.add("it has no fuel: put coal or wood in the bottom slot");
            else if (!isFuel(fuel.id)) problems.add(Bots.name(fuel.id) + " isn't fuel");
        }
        return burning ? "it's burning" : "it's ready to go";
    }

    /** What a furnace makes of this item, 0 if nothing (Alpha 1.1.2's furnace recipes). */
    static int smeltsTo(int id) {
        switch (id) {
            case 15: return 265; case 14: return 266; case 56: return 264; case 12: return 20; case 4: return 1;
            case 319: return 320; case 349: return 350; case 337: return 336; default: return 0;
        }
    }

    /** Burns in a furnace: anything wooden, coal, lava, a sapling, a stick, a blaze rod. */
    static boolean isFuel(int id) {
        Object b = Bots.block(id);
        if (b != null && ((net.minecraft.block.Block) b).material == net.minecraft.block.material.Material.WOOD) return true;
        return id == 263 || id == 280 || id == 327 || id == 6 || id == 369;
    }
}
