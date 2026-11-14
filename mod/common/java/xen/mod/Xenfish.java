package xen.mod;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Xenfish: its plan for its goal, worked out like a chess engine weighs moves, then kept to. For each thing its goal
 * needs (four obsidian, a book, lapis, levels for the enchanting table) there are ways to get it (obsidian: a village's
 * chests, a ruined portal, a lava pool and its water bucket, lava under the rock), and each way has a price: how long it
 * would take from here (the walk there, the work there) over how often that way has worked for it (Xens of this world,
 * every try remembered), with a death counted as five minutes lost. The cheapest way to each is its plan; it keeps to
 * it till that way is done or has come to nothing (then the next cheapest), not changing its mind on the way.
 * <p>What it learns: each try of a way (worked or not, how long, died or not) goes into its brain's book
 * ({@link BtbLearner#record}), so the prices are its own, not guesses, after a few tries.
 */
final class Xenfish {
	private final Companion c;

	Xenfish(Companion c) {
		this.c = c;
	}

	/** A way to get something: its name ("obsidian:lava"), what it says, where, its price in seconds. */
	record Way(String key, String says, BlockPos at, double seconds, double tries, double worked) {}

	/** The ways to four obsidian it has now, cheapest first (only ones it can start from here). */
	List<Way> obsidianWays() {
		List<Way> out = new ArrayList<>();
		if (c.player == null) return out;
		BlockPos feet = c.player.blockPosition();
		BlockPos village = c.visits.nearest("village"), portal = c.visits.nearest("ruined portal");
		if (village != null) out.add(way("obsidian:village", "the village's chests (the weaponsmith's has obsidian)", village, flat(feet, village) / 5.0 + 120));
		if (portal != null) out.add(way("obsidian:portal", "the ruined portal (its own obsidian, its chest)", portal, flat(feet, portal) / 5.0 + 60));
		java.util.List<BlockPos> lava = c.hunch.oresNear("lava", feet, 128, 160, 1);
		if (!lava.isEmpty()) {
			BlockPos l = lava.get(0);
			double down = Math.max(0, feet.getY() - l.getY());
			out.add(way("obsidian:lava", "a lava pool and its water bucket", l, flat(feet, l) / 3.0 + down / 1.5 + 90));
		}
		out.sort(java.util.Comparator.comparingDouble(Way::seconds));
		return out;
	}

	/**
	 * A task's price from its record (every Xen's tries of it, kept in its brain's book): the time its tries took over
	 * how often it worked, a death five minutes lost. NaN: too few tries to say.
	 */
	double price(String task) {
		return price(task, null, Double.NaN);
	}

	/**
	 * A task's price here: its time from where it is (what it sees and has: the walk to the ore it knows of, the animals
	 * in sight, its table; NaN if it can't tell) and how long its tries took in this same spot (its record there, five
	 * tries or more; else every try of it), half and half, over how often it worked; a death five minutes lost.
	 */
	double price(String task, String state, double here) {
		double[] m = state == null ? new double[4] : c.mod.btbLearner.stats(task + "@" + state);
		if (m[0] < 5) m = c.mod.btbLearner.stats(task);
		if (m[0] < 5 && Double.isNaN(here)) return Double.NaN;
		double p = (m[1] + 1) / (m[0] + 2), deaths = m[0] > 0 ? m[2] / m[0] : 0;
		double took = m[0] > 0 ? m[3] / m[0] : here;
		double t = Double.isNaN(here) ? took : m[0] >= 5 ? 0.5 * here + 0.5 * took : here;
		return t / Math.max(0.1, p) + 300 * deaths;
	}

	private static double flat(BlockPos a, BlockPos b) {
		return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
	}

	/** A way's price: its time from here over how often it's worked (a guess at first: half), a death five minutes lost. */
	private Way way(String key, String says, BlockPos at, double seconds) {
		double[] m = c.mod.btbLearner.stats(key);
		double p = (m[1] + 1) / (m[0] + 2);                                      // (worked of tries, a fair start: half)
		double deaths = m[0] > 0 ? m[2] / m[0] : 0;
		double took = m[1] > 0 ? m[3] / m[0] : seconds;                          // (how long its tries took, once it has some)
		double t = 0.5 * seconds + 0.5 * took;
		return new Way(key, says, at, t / Math.max(0.1, p) + 300 * deaths, m[0], m[1]);
	}

	// ------------------------------------------------------------------------------ the way it's on, and how it went
	/** The way it's trying now, since when, and how much obsidian it had then. */
	private String trying;
	private long tryingSince;
	private int obsidianThen;
	private boolean diedMeanwhile;
	private int deathsThen;

	/** It starts on a way (its plan's cheapest): kept to till it's done or comes to nothing. */
	void trying(String key) {
		if (key.equals(trying)) return;
		settle();
		trying = key;
		tryingSince = now();
		obsidianThen = c.items().getOrDefault("obsidian", 0);
		deathsThen = c.genDeaths;
		c.journal("thinks", "xenfish: obsidian by " + key.substring(key.indexOf(':') + 1) + " (" + priceOf(key) + ")");
	}

	/** The way it was on is over (done, given up, its brain on to something else): how it went, to its book. */
	void settle() {
		if (trying == null) return;
		double seconds = (now() - tryingSince) / 20.0;
		int got = c.items().getOrDefault("obsidian", 0) - obsidianThen;
		boolean died = c.genDeaths > deathsThen;
		boolean worked = got > 0 && !died;
		if (seconds < 15 && !worked && !died) {                                  // (begun and dropped the same moment, its brain on to something else: no try at all, not one that failed; Ann's flips between her table and a look round counted thirty)
			trying = null;
			return;
		}
		c.mod.btbLearner.record(trying, worked, seconds, died);
		c.journal("learns", String.format(Locale.ROOT, "xenfish: obsidian by %s %s (%d got, %.0f s%s)", trying.substring(trying.indexOf(':') + 1),
				worked ? "worked" : "came to nothing", Math.max(0, got), seconds, died ? ", died" : ""));
		trying = null;
	}

	String trying() {
		return trying;
	}

	private String priceOf(String key) {
		for (Way w : obsidianWays()) if (w.key().equals(key)) return describe(w);
		return "no price";
	}

	private static String describe(Way w) {
		return String.format(Locale.ROOT, "~%.1f min, %s", w.seconds() / 60.0, w.tries() == 0 ? "never tried" : String.format(Locale.ROOT, "worked %.0f of %.0f", w.worked(), w.tries()));
	}

	/** Its plan, for the live view and "what are you doing": what the goal still needs, and the way to each. */
	String show() {
		if (c.player == null || !c.btb.on()) return "";
		var it = c.items();
		StringBuilder sb = new StringBuilder();
		String goal = c.btb.goal;
		if (goal.equals("enchant")) {
			boolean tabled = c.enchanter.table() != null || c.crafter.has("enchanting_table");
			sb.append("goal: enchanted gear. ");
			sb.append("lapis ").append(Math.min(3, it.getOrDefault("lapis_lazuli", 0))).append("/3").append(it.getOrDefault("lapis_lazuli", 0) >= 3 ? " done" : "").append("; ");
			if (tabled) sb.append("table done; ");
			else {
				int obs = it.getOrDefault("obsidian", 0);
				sb.append("obsidian ").append(Math.min(4, obs)).append("/4");
				if (obs < 4) {
					List<Way> ways = obsidianWays();
					if (trying != null) sb.append(" by ").append(trying.substring(trying.indexOf(':') + 1));
					else if (!ways.isEmpty()) sb.append(" by ").append(ways.get(0).key().substring(9));
					sb.append(" [");
					for (int i = 0; i < ways.size() && i < 3; i++) {
						Way w = ways.get(i);
						if (i > 0) sb.append(" | ");
						sb.append(w.key().substring(9)).append(' ').append(describe(w));
					}
					sb.append("]");
				} else sb.append(" done");
				sb.append("; book ").append(it.getOrDefault("book", 0) > 0 ? "done" : "(paper " + it.getOrDefault("paper", 0) + "/3, leather " + it.getOrDefault("leather", 0) + "/1)");
				sb.append("; then the table; ");
			}
			sb.append("levels ").append(c.player.experienceLevel).append(" (enchanting takes 1 to 3)");
		} else if (goal.equals("gear")) {
			sb.append("goal: diamond gear. diamonds ").append(it.getOrDefault("diamond", 0)).append(", ").append(c.goals.diamondsWanted()).append(" still wanted");
		} else sb.append("goal: ").append(goal);
		return sb.toString();
	}

	private long now() {
		return c.player.level().getGameTime();
	}
}
