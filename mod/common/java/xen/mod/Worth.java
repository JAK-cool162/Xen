package xen.mod;

import java.util.Map;

/**
 * What things are for: the game knowledge a player has who knows the game. Each thing, what it's for on the way to the
 * Nether and the dragon, and how many of it a Xen wants by when (with stone tools, with an iron pickaxe, a diamond
 * one), so it takes what it will need (sugar cane now for the books an enchanting table needs later; from a village
 * chest the bread and iron, not the junk) and leaves the rest.
 */
final class Worth {
	/** One thing: what it's for, how many it wants, and from which pickaxe on (0 wood or none, 1 stone, 2 iron, 3 diamond). */
	record Use(String why, int want, int from) {}

	static final Map<String, Use> ALL = Map.ofEntries(
			Map.entry("food", new Use("food: hunger is a slow enemy", 24, 0)),
			Map.entry("hay_block", new Use("nine wheat each: bread", 4, 0)),
			Map.entry("wheat", new Use("bread", 12, 0)),
			Map.entry("log", new Use("planks, sticks, tools, a crafting table", 16, 0)),
			Map.entry("cobblestone", new Use("stone tools, a furnace, blocks to build with in a pinch", 64, 0)),
			Map.entry("coal", new Use("smelting and torches", 16, 0)),
			Map.entry("charcoal", new Use("smelting and torches", 16, 0)),
			Map.entry("torch", new Use("light underground: monsters don't spawn by them", 32, 0)),
			Map.entry("raw_iron", new Use("an iron pickaxe, armor, a bucket, a shield", 24, 0)),
			Map.entry("iron_ingot", new Use("an iron pickaxe, armor, a bucket, a shield", 24, 0)),
			Map.entry("iron_nugget", new Use("nine make an ingot", 18, 0)),
			Map.entry("bucket", new Use("water for obsidian (water on lava) and a safe landing", 1, 1)),
			Map.entry("water_bucket", new Use("obsidian from lava, a safe landing", 1, 1)),
			Map.entry("sugar_cane", new Use("paper for books: an enchanting table needs one, bookshelves three each", 6, 1)),
			Map.entry("paper", new Use("books for enchanting", 9, 1)),
			Map.entry("leather", new Use("books (paper and leather) and armor", 3, 1)),
			Map.entry("book", new Use("an enchanting table, bookshelves", 3, 1)),
			Map.entry("diamond", new Use("a diamond pickaxe (for obsidian), an enchanting table, diamond armor and a sword", 34, 2)),
			Map.entry("obsidian", new Use("an enchanting table (4) and a Nether portal (10)", 14, 2)),
			Map.entry("lapis_lazuli", new Use("enchanting", 9, 2)),
			Map.entry("flint_and_steel", new Use("lighting the Nether portal", 1, 2)),
			Map.entry("flint", new Use("flint and steel, for the portal", 1, 2)),
			Map.entry("gold_ingot", new Use("gold armor for the piglins, trading with them", 6, 2)),
			Map.entry("golden_apple", new Use("a fight it might lose", 4, 0)),
			Map.entry("enchanted_golden_apple", new Use("a fight it might lose", 4, 0)),
			Map.entry("emerald", new Use("trading with villagers", 16, 0)),
			Map.entry("string", new Use("a bow, wool for a bed", 3, 0)),
			Map.entry("arrow", new Use("its bow", 32, 0)),
			Map.entry("bow", new Use("monsters it can't reach, the dragon's crystals", 1, 1)),
			Map.entry("shield", new Use("skeletons, creepers, anything that hits hard", 1, 1)),
			Map.entry("ender_pearl", new Use("eyes of ender for the stronghold, a quick way out", 12, 2)),
			Map.entry("blaze_rod", new Use("eyes of ender (blaze powder)", 7, 3)),
			Map.entry("enchanted_book", new Use("better gear at an anvil", 2, 2)),
			Map.entry("experience_bottle", new Use("levels for enchanting", 16, 2)),
			Map.entry("totem_of_undying", new Use("cheats death once", 2, 0)),
			Map.entry("saddle", new Use("a horse: quick travel", 1, 0)));

	private Worth() {}

	/** How many more of this it wants now (0: none, it leaves it). Item ids; food and logs count as their kind. */
	static int want(Companion c, String id, String key) {
		Use u = ALL.get(key);
		if (u == null) u = ALL.get(id);
		if (u == null) {
			if (id.endsWith("_bed")) return c.bedAt == null && !c.items().keySet().stream().anyMatch(k -> k.endsWith("_bed")) ? 1 : 0;
			if (id.endsWith("_wool")) return 3;
			if (id.startsWith("iron_") || id.startsWith("diamond_")) return better(c, id) ? 1 : 0;   // (armor, tools: if it's better than its own)
			return 0;
		}
		int tier = Math.max(0, c.crafter.pickTier() - 1);
		if (tier < u.from() - 1) return 0;                                       // (not yet: it'll be ready for it later)
		Integer have = c.items().get(key);
		if (have == null) have = c.items().getOrDefault(id, 0);
		return Math.max(0, u.want() - have);
	}

	/** What it's for, in a few words (null: nothing it needs). */
	static String why(String id) {
		Use u = ALL.get(id);
		return u == null ? null : u.why();
	}

	/** An iron or diamond tool or piece of armor it hasn't got one of as good. */
	private static boolean better(Companion c, String id) {
		String kind = id.substring(id.indexOf('_') + 1);
		return !c.crafter.has("diamond_" + kind) && (id.startsWith("diamond_") || !c.crafter.has("iron_" + kind));
	}
}
