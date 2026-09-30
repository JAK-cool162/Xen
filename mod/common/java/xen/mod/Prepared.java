package xen.mod;

import xen.mod.core.Mind;

import java.util.Map;

/**
 * Ready for the worst, like a careful player. Before a trip that can go wrong (down a mine, far out exploring, an
 * adventure) it packs what gets it out of trouble: food, blocks to pillar up or wall itself in, a spare pickaxe for a
 * long dig. Before the risky ones (diamonds, the Nether, the End) it leaves what it can't afford to lose (diamonds,
 * emeralds, gold, a second set of tools) in its chest at home. And if it dies anyway and its things are gone, it
 * doesn't start again from nothing: it goes home and takes its spares out of that chest.
 */
final class Prepared {
	private final Companion c;
	/** When it last left its valuables at home, and last looked for its spares after dying. */
	private long stashedAt = -1_000_000, rearmedAt = -1_000_000, foodAt = -1_000_000, blocksAt = -1_000_000;
	/** It died and hasn't got back on its feet (its things back, or spares from home) yet; it's fetching them now. */
	private boolean lostAll, fetching;

	Prepared(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** What to see to first (a Mind option, and why), or null: it's ready. */
	record Step(int option, String why) {}

	/**
	 * Before a trip (MINE, EXPLORE, ADVENTURE): food packed (3 meals), blocks for a trip in the open (16), a spare
	 * pickaxe for a dig (made at once, if it has the stone and sticks), and before a risky one its valuables and
	 * spare tools left at home (once in a while, if its chest isn't far).
	 */
	Step before(int trip, boolean deep, boolean[] can, Map<String, Integer> items) {
		int meals = items.getOrDefault("food", 0);
		if (meals < 3 && can[Mind.FOOD] && now() - foodAt > 20 * 60 * 8) {       // (tried lately and found none: it goes anyway)
			foodAt = now();
			c.chores.forWool = false;
			return new Step(Mind.FOOD, "food packed before it goes");
		}
		int blocks = items.getOrDefault("cobblestone", 0) + items.getOrDefault("dirt", 0);
		if ((trip == Mind.EXPLORE || trip == Mind.ADVENTURE) && blocks < 16 && can[Mind.STONE] && now() - blocksAt > 20 * 60 * 8) {
			blocksAt = now();
			return new Step(Mind.STONE, "blocks in case (to pillar up, to wall off)");
		}
		if (trip == Mind.MINE && pickaxes() == 1 && items.getOrDefault("cobblestone", 0) >= 3 && !c.crafter.hasOrder()
				&& (items.getOrDefault("stick", 0) >= 2 || MindSense.count(c, n -> n.endsWith("_planks")) >= 2 || items.getOrDefault("log", 0) >= 1)) {
			c.crafter.orderRecipe("stone_pickaxe", 1);                               // (a pickaxe breaks at the bottom of a mine: a spare)
			c.journal("does", "packs a spare pickaxe before going down");
		}
		boolean risky = deep || trip == Mind.ADVENTURE;
		if (risky && now() - stashedAt > 20 * 60 * 10 && !c.storage.busy() && c.storage.stash()) {
			stashedAt = now();
			c.journal("does", "leaves its valuables and spare tools at home before a risky trip");
			c.chatter(c.pick3("Diamonds stay at home. Just in case.", "Leaving the good stuff in the chest first.",
					"If this goes wrong, at least my stuff is safe."), false);
			return new Step(Mind.STORE, "its valuables left at home first");
		}
		return null;
	}

	private int pickaxes() {
		return MindSense.count(c, n -> n.endsWith("_pickaxe"));
	}

	/**
	 * Every decision (before whatever it was doing when it died: a death makes you think again): fetching its spares
	 * from home, or starting to. The step, or null.
	 */
	xen.mod.core.Action next() {
		if (fetching && !c.storage.busy()) fetching = false;
		if (!fetching && rearm() == null) return null;
		xen.mod.core.Action a = c.storage.next();
		if (!c.storage.doing.isEmpty()) c.goals.instant = c.storage.doing + " (its spares)";
		return a != null ? a : xen.mod.core.Action.IDLE;
	}

	/** It died (its things are lying where it fell, for a while). */
	void died() {
		lostAll = true;
	}

	/**
	 * Back on its feet? With nothing (its things lost: it couldn't get back to them in time), it takes its spares from
	 * the chest at home: a pickaxe, a sword, food. The step for that, or null.
	 */
	Step rearm() {
		if (!lostAll || c.player == null) return null;
		if (c.lostThings()) return null;                                              // (still going back for them)
		if (c.crafter.pickTier() >= 2 || now() - rearmedAt < 60 || c.storage.busy()) {
			if (c.crafter.pickTier() >= 2) lostAll = false;
			return null;
		}
		rearmedAt = now();
		for (String key : new String[] {"diamond_pickaxe", "iron_pickaxe", "stone_pickaxe", "diamond_sword", "iron_sword", "stone_sword", "food"}) {
			if (key.endsWith("_pickaxe") && c.crafter.pickTier() >= 2) continue;
			if (key.endsWith("_sword") && MindSense.count(c, k -> k.endsWith("_sword")) > 0) continue;
			if (key.equals("food") && c.items().getOrDefault("food", 0) >= 3) continue;
			String plan = c.storage.take(key, key.equals("food") ? 8 : 1);
			if (plan == null) continue;
			fetching = true;
			c.journal("does", "back on its feet: gets its spare " + key.replace('_', ' ') + " from the chest at home");
			c.chatter(c.pick3("Good thing I left spares at home.", "Back home for my spare gear.", "Not starting from zero: I've got spares at home."), false);
			return new Step(Mind.STORE, "its spares from the chest at home");
		}
		lostAll = false;                                                              // (no spares at home: it starts over)
		return null;
	}
}
