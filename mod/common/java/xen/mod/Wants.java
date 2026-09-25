package xen.mod;

import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * What a Xen wants, on its own. When it's free (nobody to follow, nothing asked of it), every so often it weighs what
 * it could want (food, a shelter for the night, wood, stone, ore, or just to explore) by three things: what it needs
 * right now (hungry with no food? dusk coming? few blocks?), its personality (patient Xens like work, brave ones go for
 * ore, curious ones explore), and how well each want has turned out for it before. It goes for the best one with the
 * same chores a player can ask for, says what it wants, and learns from how it went: wants that pay off become ones it
 * likes. Each Xen's likes are its own, and are kept with it in the roster.
 */
final class Wants {
	enum Want {
		FOOD("find some food", "you are hungry and have no food"),
		SHELTER("build a shelter for the night", "it's dark and you have no roof"),
		WOOD("get some wood", "you have little wood"),
		STONE("get some stone", "you have few blocks"),
		ORE("look for ore", "you would like to find treasure"),
		EXPLORE("explore", "you are curious");

		final String what, why;

		Want(String what, String why) {
			this.what = what;
			this.why = why;
		}
	}

	/** How often it thinks about what it wants (ticks), and how long it just explores. */
	static final int THINK = 400, EXPLORE_TICKS = 1200;

	private final Companion c;
	private final Random random = new Random();
	/** How well each want has turned out for it, 0 to 1 (learned). */
	final Map<Want, Float> liking = new EnumMap<>(Want.class);
	Want current;
	private long next, until;
	private float rewardAtStart;

	Wants(Companion c) {
		this.c = c;
		for (Want w : Want.values()) liking.put(w, 0.5f);
	}

	/**
	 * Called when it's free. While a want is on, it follows it (via its chore) or finishes it; otherwise, every so
	 * often, it picks a new one. True if a chore is now going (so the chore decides this moment).
	 */
	boolean think() {
		long now = c.player.level().getGameTime();
		if (current != null) {
			boolean over = current == Want.EXPLORE ? now > until : !c.chores.busy();
			if (!over) return c.chores.busy();
			learn();
		}
		if (now < next) return false;
		next = now + THINK;
		Want best = null;
		float bestScore = 0.25f;                                         // below this it's happy doing whatever it does
		for (Want w : Want.values()) {
			float score = urgency(w) * (0.5f + liking.get(w)) + random.nextFloat() * 0.15f * c.personality.curiosity;
			if (score > bestScore) {
				bestScore = score;
				best = w;
			}
		}
		if (best == null) return false;
		String plan = switch (best) {
			case FOOD -> c.chores.hunt(2);
			case SHELTER -> c.chores.shelter();
			case WOOD -> c.chores.gather("wood", 4);
			case STONE -> c.chores.gather("stone", 8);
			case ORE -> c.chores.gather("ore", 2);
			case EXPLORE -> "";
		};
		if (plan.startsWith("You can't") || plan.startsWith("You have no") || plan.startsWith("You don't have")) {
			liking.merge(best, -0.05f, (a, b) -> Math.max(0f, a + b));       // it can't right now: a little less keen
			c.chores.cancel();
			return false;
		}
		current = best;
		rewardAtStart = c.genReward;
		until = now + EXPLORE_TICKS;
		c.chores.own = true;
		c.chatter("I want to " + best.what + ".", false);
		return c.chores.busy();
	}

	/** How much it needs a want right now, 0 to about 1, shaped by its personality. */
	private float urgency(Want w) {
		var p = c.personality;
		var items = c.items();
		int food = c.player.getFoodData().getFoodLevel();
		var level = c.player.level();
		boolean outInTheDark = level.isDarkOutside() && level.canSeeSky(c.player.blockPosition().above());   // no roof yet
		return switch (w) {
			case FOOD -> items.getOrDefault("food", 0) > 0 ? 0 : Math.max(0, (16 - food) / 16f) * 1.2f;
			case SHELTER -> outInTheDark && items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0) >= 10
					? 0.9f * (1.2f - 0.6f * p.bravery) : 0;
			case WOOD -> items.getOrDefault("log", 0) < 4 ? 0.5f * (0.5f + p.diligence) : 0;
			case STONE -> items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0) < 10 ? 0.4f * (0.5f + p.diligence) : 0;
			case ORE -> 0.35f * (0.5f + p.bravery);
			case EXPLORE -> 0.3f * (0.5f + p.curiosity);
		};
	}

	/** How did it go? What it gained (or, for a shelter, whether it got built) moves its liking for that want. */
	private void learn() {
		float outcome = current == Want.SHELTER ? (c.chores.shelterBuilt ? 1f : 0f) : Math.min(1f, Math.max(0f, (c.genReward - rewardAtStart) / 2f));
		liking.merge(current, 0f, (a, b) -> a + 0.2f * (outcome - a));
		c.chores.own = false;
		current = null;
	}

	/** Someone asked it for something: that comes first. */
	void drop() {
		current = null;
		c.chores.own = false;
	}

	/** For its notes and status: "You want to get some wood, because you have little wood." */
	String describe() {
		return current == null ? "" : "You want to " + current.what + ", because " + current.why + ".";
	}

	/** Its likes in words: "likes exploring and wood most". */
	String likes() {
		Want a = null, b = null;
		for (Want w : Want.values()) {
			if (a == null || liking.get(w) > liking.get(a)) {
				b = a;
				a = w;
			} else if (b == null || liking.get(w) > liking.get(b)) {
				b = w;
			}
		}
		return "likes to " + a.what + " and " + b.what + " most";
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		liking.forEach((w, v) -> o.addProperty(w.name().toLowerCase(java.util.Locale.ROOT), v));
		return o;
	}

	void load(JsonObject o) {
		for (Want w : Want.values()) {
			String k = w.name().toLowerCase(java.util.Locale.ROOT);
			if (o.has(k)) liking.put(w, o.get(k).getAsFloat());
		}
	}
}
