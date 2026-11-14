package xen.mod;

import java.util.Locale;

/**
 * A Xen's own idea, in its words (from its language model), made into something it can do: a build (a house, a base,
 * a farm, a pen for animals, a mob farm, any of the player's schematics by its name, its team's fortress) or a job
 * (diamonds, enchanting, trading, the village, exploring, fishing and hunting, smelting, sleeping). What it can't do
 * yet stays a wish: said, kept, never forced.
 */
final class Ideas {
	private Ideas() {}

	/** What it comes to: "build" and the builder's word (or "schematic:Name"), or "task" and a job; null: a wish only. */
	record Doing(String kind, String arg, String label) {}

	static Doing interpret(String idea) {
		String t = " " + idea.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ") + " ";
		for (String name : Schematic.list()) {                                    // the player's schematics, by a word of their name ("tower", "staircase", "wall")
			for (String w : name.toLowerCase(Locale.ROOT).split("[_\\- ]+")) {
				if (w.length() < 4 || w.equals("from") || w.equals("with") || w.equals("small") || w.equals("house")) continue;
				if (t.contains(" " + w + " ") || t.contains(" " + w + "s ") || w.equals("stair") && t.contains("staircase"))
					return new Doing("build", "schematic:" + name, "build the " + name.replace('_', ' '));
			}
		}
		if (has(t, "trial", "trials", "chamber", "vault", "vaults", "mace", "breeze", "key", "keys")) return new Doing("task", "TRIALS", "take on a trial chamber");
		if (has(t, "fortress", "castle", "keep", "walls", "wall", "tower")) return new Doing("task", "FORT", "the team's fortress");
		if (has(t, "mob farm", "xp farm", "grinder")) return new Doing("build", "mob farm", "build a mob farm");
		if (has(t, "pen", "breed", "cows", "sheep", "pigs", "chickens", "animal")) return new Doing("build", "pen", "build a pen for animals");
		if (has(t, "farm", "wheat", "crops", "garden", "carrots", "potatoes")) return new Doing("build", "farm", "build a farm");
		if (has(t, "house", "home", "cottage", "cabin", "hut", "shelter")) return new Doing("build", "house", "build a house");
		if (has(t, "base", "camp", "outpost")) return new Doing("build", "base", "build a base");
		if (has(t, "diamond", "diamonds")) return new Doing("task", "DIAMONDS", "go for diamonds");
		if (has(t, "enchant", "enchanting", "enchanted")) return new Doing("task", "ENCHANT", "enchant its gear");
		if (has(t, "trade", "trading", "villager", "villagers", "emerald", "emeralds")) return new Doing("task", "TRADE", "trade with the villagers");
		if (has(t, "village")) return new Doing("task", "VISIT", "visit the village");
		if (has(t, "fish", "fishing", "hunt", "hunting", "food", "cook", "eat", "meat", "bread")) return new Doing("task", "FOOD", "get food");
		if (has(t, "smelt", "furnace", "ingots")) return new Doing("task", "SMELT", "smelt");
		if (has(t, "iron")) return new Doing("task", "IRON", "go for iron");
		if (has(t, "explore", "adventure", "discover", "travel", "journey", "map", "find", "cave", "caves")) return new Doing("task", "EXPLORE", "explore");
		if (has(t, "sleep", "bed", "rest")) return new Doing("task", "SLEEP", "sleep");
		return null;
	}

	private static boolean has(String t, String... words) {
		for (String w : words) if (t.contains(" " + w + " ")) return true;
		return false;
	}
}
