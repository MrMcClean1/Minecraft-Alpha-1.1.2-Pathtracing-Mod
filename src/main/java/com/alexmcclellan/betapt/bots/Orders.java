package com.alexmcclellan.betapt.bots;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads what a player asks of a bot in plain words. Unknown requests go to the model (if it's running). */
public final class Orders {
    private Orders() {}

    public enum Kind { FOLLOW, STAY, COME, GUARD, GATHER, HOME_SET, HOME_GO, CHORE, CHORES_CLEAR, GIVE, GIVE_ALL, INVENTORY, DEPOSIT, EXPLORE, STATUS, HUNT, CRAFT_TOOLS, BUILD, FARM, FARM_STOP, NONE }

    public static final class Order {
        public final Kind kind; public final String what; public final int count;
        Order(Kind k, String what, int count) { this.kind = k; this.what = what; this.count = count; }
        @Override public String toString() { return kind + (what != null ? " " + what : "") + (count > 0 ? " x" + count : ""); }
    }

    private static final Pattern NUM = Pattern.compile("\\b(\\d{1,4})\\b");
    private static final String[] WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"};

    public static Order parse(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
        int n = number(t);
        if (has(t, "keep me stocked", "keep stocked", "keep us stocked", "always have", "keep the chest")) {
            String w = thing(t, "stocked with", "stocked on", "always have", "with", "on");
            return new Order(Kind.CHORE, w, n > 0 ? n : 32);
        }
        if (has(t, "stop chores", "forget chores", "no more chores", "clear chores", "stop stocking")) return new Order(Kind.CHORES_CLEAR, null, 0);
        if (has(t, "build", "construct", "put up", "make a", "make us", "make me")) {
            for (String plan : new String[]{"castle", "fort", "tower", "wall", "house", "cabin", "hut", "shelter"})
                if (t.contains(" " + plan)) return new Order(Kind.BUILD, plan, n);
            if (has(t, "build", "construct")) return new Order(Kind.BUILD, "?", n);
        }
        if (has(t, "stop farming", "forget the farm", "no more farming", "leave the farm")) return new Order(Kind.FARM_STOP, null, 0);
        if (has(t, "farm", "plant wheat", "plant crops", "plant seeds", "grow wheat", "grow food", "harvest", "tend the", "till")) return new Order(Kind.FARM, null, 0);
        if (has(t, "go mining", "strip mine", "dig for ore", "dig for ores", "mine for ore", "mine for ores", "find ore", "find ores", "get ores", "mine some ore", "mine ores", "mine ore"))
            return new Order(Kind.GATHER, "ores", n > 0 ? n : 16);
        if (has(t, "set up a base", "make a base", "base here", "set home", "home here", "this is home", "live here", "make camp")) return new Order(Kind.HOME_SET, null, 0);
        if (has(t, "go home", "back to base", "return home", "go to base")) return new Order(Kind.HOME_GO, null, 0);
        if (has(t, "follow", "come with me", "with me", "lets go", "let s go")) return new Order(Kind.FOLLOW, null, 0);
        if (has(t, "stay", "wait here", "stop", "hold on", "halt", "dont move", "don t move")) return new Order(Kind.STAY, null, 0);
        if (has(t, "come here", "over here", "come to me", " come ")) return new Order(Kind.COME, null, 0);
        if (has(t, "guard", "protect", "defend", "watch my back", "fight", "kill the", "attack")) return new Order(Kind.GUARD, null, 0);
        if (has(t, "what do you have", "inventory", "what are you carrying", "what have you got", "show me your")) return new Order(Kind.INVENTORY, null, 0);
        if (has(t, "give me everything", "give me all", "empty your", "drop everything", "hand it all")) return new Order(Kind.GIVE_ALL, null, 0);
        if (has(t, "put away", "deposit", "store your", "stash", "unload")) return new Order(Kind.DEPOSIT, null, 0);
        if (has(t, "explore", "look around", "scout", "wander")) return new Order(Kind.EXPLORE, null, 0);
        if (has(t, "how are you", "status", "what are you doing", "whats up", "what s up", "you ok")) return new Order(Kind.STATUS, null, 0);
        if (has(t, "make tools", "craft tools", "make a pickaxe", "craft a pickaxe", "get tools", "make yourself")) return new Order(Kind.CRAFT_TOOLS, null, 0);
        if (has(t, "hunt", "get food", "find food", "get meat", "kill animals", "bring food", "get some food")) return new Order(Kind.HUNT, "food", n > 0 ? n : 8);
        if (has(t, "give me", "hand me", "toss me", "pass me", "drop me")) {
            String w = thing(t, "give me", "hand me", "toss me", "pass me", "drop me");
            if (w != null) return new Order(Kind.GIVE, w, n);
        }
        if (has(t, "bring me", "get me", "fetch", "gather", "collect", "mine", "dig", "chop", "get some", "find me", "find some", "go get", "we need", "i need")) {
            String w = thing(t, "bring me", "get me", "fetch", "gather", "collect", "mine", "dig", "chop", "get some", "find me", "find some", "go get", "we need", "i need");
            if (has(t, "chop", "wood", "logs", "lumber", "timber") && (w == null || w.isEmpty())) w = "wood";
            if (w != null && !w.isEmpty()) return new Order(Kind.GATHER, w, n > 0 ? n : 16);
        }
        return new Order(Kind.NONE, null, 0);
    }

    private static boolean has(String t, String... ks) { for (String k : ks) if (t.contains(" " + k.trim() + " ") || (k.startsWith(" ") && t.contains(k))) return true; return false; }

    private static int number(String t) {
        Matcher m = NUM.matcher(t);
        if (m.find()) return Integer.parseInt(m.group(1));
        if (t.contains(" a stack ") || t.contains(" stack of ")) return 64;
        if (t.contains(" a few ")) return 5;
        if (t.contains(" some ")) return 16;
        for (int i = 1; i < WORDS.length; i++) if (t.contains(" " + WORDS[i] + " ")) return i;
        return 0;
    }

    private static final String STOP = " me some the a an of more few please now bot bots stack stacks pieces piece bit lots lot and for us to with on x ";

    /** The words after one of the keys, minus numbers and filler: "bring me 16 iron ore please" -> "iron ore". */
    private static String thing(String t, String... keys) {
        for (String k : keys) {
            int i = t.indexOf(" " + k + " ");
            if (i < 0) continue;
            String rest = t.substring(i + k.length() + 2);
            StringBuilder b = new StringBuilder();
            for (String w : rest.trim().split(" ")) {
                if (w.isEmpty() || w.matches("\\d+") || STOP.contains(" " + w + " ")) { if (b.length() > 0 && (w.equals("please") || w.equals("and") || w.equals("for"))) break; continue; }
                boolean num = false;
                for (String nw : WORDS) if (nw.equals(w)) num = true;
                if (num) continue;
                if (b.length() > 0) b.append(' ');
                b.append(w);
                if (b.toString().split(" ").length >= 3) break;
            }
            if (b.length() > 0) return b.toString();
        }
        return null;
    }

    /** Is this chat line meant for bots? Returns the bot name it starts with ("all" for "bots"/"everyone"), or null. */
    public static String addressee(String text, Iterable<String> names) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (t.startsWith(l + " ") || t.startsWith(l + ",") || t.startsWith(l + ":") || t.equals(l) || t.startsWith("@" + l)
                    || t.endsWith(" " + l) || t.endsWith(", " + l) || t.contains("hey " + l)) return n;
        }
        if (t.startsWith("bots") || t.startsWith("everyone") || t.startsWith("all bots") || t.startsWith("team,")) return "all";
        return null;
    }

    /** The text with the bot's name taken off. */
    public static String strip(String text, String name) {
        String t = text.trim();
        if (name == null) return t;
        String l = t.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
        if (l.startsWith("@")) { t = t.substring(1); l = l.substring(1); }
        if (l.startsWith(n)) t = t.substring(n.length());
        else if (l.endsWith(n)) t = t.substring(0, t.length() - n.length());
        else if (l.startsWith("all bots")) t = t.substring(8);
        else if (l.startsWith("bots")) t = t.substring(4);
        else if (l.startsWith("everyone")) t = t.substring(8);
        return t.replaceAll("^[ ,:]+|[ ,:]+$", "").trim();
    }

    public static final String[] NAMES = {"Steve", "Ada", "Brock", "Cleo", "Dax", "Elsie", "Gus", "Ezra", "Mira", "Otto", "Juno", "Rex",
            "Wren", "Bram", "Ivy", "Cole", "Nell", "Finn", "Tess", "Hugo", "Lena", "Moss", "Pip", "Rook"};
}
