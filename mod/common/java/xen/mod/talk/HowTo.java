package xen.mod.talk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How things are made or found, the way a player would tell a friend ("a bed? 3 wool and 3 planks"). What every
 * player knows; asked "how do I make a bed?", a Xen tells this in its own words.
 */
public final class HowTo {
	private HowTo() {}

	private static final Map<String, String> HOW = new LinkedHashMap<>();

	private static void add(String what, String how, String... also) {
		HOW.put(what, how);
		for (String a : also) HOW.put(a, how);
	}

	static {
		add("bed", "3 wool of one color on top and 3 planks under it, on a crafting table. Wool comes from sheep");
		add("crafting table", "4 planks in a square", "table", "workbench");
		add("plank", "put a log in your crafting grid, one log makes 4 planks", "planks", "wood plank");
		add("stick", "2 planks, one on top of the other", "sticks");
		add("torch", "a stick with coal or charcoal on top, that makes 4", "torches");
		add("furnace", "8 cobblestone in a ring, on a crafting table");
		add("chest", "8 planks in a ring, on a crafting table");
		add("pickaxe", "3 of the material across the top and 2 sticks down the middle", "pick");
		add("wooden pickaxe", "3 planks across the top and 2 sticks down the middle", "wood pickaxe");
		add("stone pickaxe", "3 cobblestone across the top and 2 sticks down the middle");
		add("iron pickaxe", "3 iron ingots across the top and 2 sticks down the middle. Smelt raw iron for the ingots");
		add("diamond pickaxe", "3 diamonds across the top and 2 sticks down the middle");
		add("sword", "2 of the material on top of a stick");
		add("axe", "3 of the material in an L at the top and 2 sticks down the middle");
		add("shovel", "1 of the material on top of 2 sticks", "spade");
		add("hoe", "2 of the material across the top and 2 sticks down the middle");
		add("shield", "6 planks and 1 iron ingot, the iron top middle");
		add("bucket", "3 iron ingots in a V");
		add("boat", "5 planks in a U");
		add("door", "6 planks in two columns, that makes 3");
		add("ladder", "7 sticks in an H, that makes 3");
		add("fence", "4 planks and 2 sticks");
		add("bread", "3 wheat in a row");
		add("bowl", "3 planks in a V");
		add("bow", "3 sticks and 3 string");
		add("arrow", "flint, a stick and a feather, that makes 4");
		add("fishing rod", "3 sticks going up diagonally and 2 string hanging down");
		add("shear", "2 iron ingots, diagonal", "shears");
		add("flint and steel", "an iron ingot and a flint");
		add("compass", "4 iron ingots around a redstone dust");
		add("clock", "4 gold ingots around a redstone dust");
		add("book", "3 paper and 1 leather");
		add("paper", "3 sugar cane in a row");
		add("bookshelf", "6 planks and 3 books");
		add("enchanting table", "4 obsidian, 2 diamonds and a book");
		add("anvil", "3 iron blocks on top and 4 iron ingots below");
		add("iron block", "9 iron ingots");
		add("iron armor", "iron ingots: 5 for a helmet, 8 for a chestplate, 7 for leggings, 4 for boots", "armor", "iron chestplate", "chestplate");
		add("diamond armor", "diamonds: 5 for a helmet, 8 for a chestplate, 7 for leggings, 4 for boots");
		add("tnt", "5 gunpowder and 4 sand in a checkerboard");
		add("piston", "3 planks, 4 cobblestone, 1 iron ingot and 1 redstone");
		add("rail", "6 iron ingots and a stick, that makes 16", "rails");
		add("campfire", "3 sticks, a coal and 3 logs");
		add("lantern", "a torch in the middle of 8 iron nuggets");
		add("glass", "smelt sand in a furnace");
		add("charcoal", "smelt logs in a furnace");
		add("iron ingot", "smelt raw iron in a furnace");
		add("nether portal", "10 obsidian in a frame 4 wide and 5 tall, then light it with flint and steel", "portal");
		add("eye of ender", "an ender pearl and blaze powder", "ender eye");
		add("blaze powder", "put a blaze rod in the crafting grid");
		add("golden apple", "an apple in the middle of 8 gold ingots");
		add("cake", "3 milk, 2 sugar, an egg and 3 wheat");
		add("wool", "shear a sheep for 1 to 3, or kill it for 1");
		add("iron", "mine iron ore with a stone pickaxe or better, around y 16 is good, then smelt it", "raw iron");
		add("coal", "mine coal ore, it's everywhere in the hills and caves");
		add("diamond", "dig down to around y -58 with an iron pickaxe and look around");
		add("gold", "mine gold ore with an iron pickaxe, deep down or in the Nether");
		add("redstone", "mine redstone ore deep down with an iron pickaxe");
		add("obsidian", "pour water on still lava, then mine it with a diamond pickaxe");
		add("leather", "cows drop it");
		add("string", "spiders drop it, or break cobwebs with a sword");
		add("feather", "chickens drop them");
		add("gunpowder", "creepers drop it, if you're quick");
		add("ender pearl", "endermen drop them, don't look them in the eyes");
		add("blaze rod", "kill blazes in a Nether fortress");
		add("netherite", "ancient debris deep in the Nether, smelted, 4 scraps and 4 gold ingots");
		add("food", "hunt cows, pigs or chickens and cook the meat, or farm wheat for bread");
		add("sugar cane", "it grows next to water");
		add("flint", "dig gravel, sometimes you get flint");
		add("sand", "deserts and beaches");
		add("clay", "look in shallow water, it's gray");
		add("slime ball", "slimes in swamps, or deep down in slime chunks");
		add("xp", "mine ores, kill mobs, smelt things", "experience");
	}

	/** Which of two is better, and why (what players say), or null (it has no fact for those two). */
	private static final String[][] BETTER = {
			{"sword", "axe", "a sword for fighting, it's faster. An axe hits harder but slow, and it chops trees"},
			{"bow", "crossbow", "a bow, you can charge it. A crossbow hits hard but reloads slow"},
			{"cow", "pig", "cows, they give leather too"},
			{"sheep", "pig", "sheep, you get wool for a bed"},
			{"sheep", "cow", "both are good: wool for beds, leather for books"},
			{"zombie", "creeper", "zombies, a creeper blows up your stuff"},
			{"skeleton", "zombie", "zombies, skeletons shoot you from far away"},
			{"day", "night", "day, the mobs come out at night"},
			{"nether", "end", "the Nether has more stuff, the End has the dragon"},
			{"cave", "strip mining", "strip mining is safer, caves are faster"},
			{"shield", "totem", "a totem saves your life once, a shield every fight"},
			{"bed", "shelter", "a bed, it skips the whole night"},
			{"village", "house", "your own house, villagers take your stuff... well, they trade"},
			{"steak", "bread", "steak, it fills you up more"},
			{"torch", "lantern", "torches are cheaper, lanterns look nicer"},
			{"spruce", "oak", "spruce looks nicer for houses, oak is everywhere"},
	};

	public static String better(String a, String b) {
		if (a == null || b == null) return null;
		for (String[] f : BETTER) {
			if (a.endsWith(f[0]) && b.endsWith(f[1]) || a.endsWith(f[1]) && b.endsWith(f[0])) return f[2];
		}
		return null;
	}

	/** How to make or find it, or null (it doesn't know that one). */
	public static String of(String what) {
		if (what == null || what.isEmpty()) return null;
		String t = what.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').trim();
		String how = HOW.get(t);
		if (how == null && t.contains(" ")) {                                // "white bed" is a bed, "an iron sword" a sword
			String last = t.substring(t.lastIndexOf(' ') + 1);
			how = HOW.get(last);
			if (how != null && how.contains("the material")) how = how.replace("the material", material(t.substring(0, t.lastIndexOf(' '))));
		}
		if (how == null && t.endsWith("s")) how = HOW.get(t.substring(0, t.length() - 1));
		return how == null ? null : how.replace("of the material", "of your material (planks, cobblestone, iron or diamonds)");
	}

	private static String material(String m) {
		return switch (m) {
			case "wooden", "wood" -> "planks";
			case "stone" -> "cobblestone";
			case "iron" -> "iron ingots";
			case "golden", "gold" -> "gold ingots";
			case "diamond" -> "diamonds";
			default -> "the material";
		};
	}
}
