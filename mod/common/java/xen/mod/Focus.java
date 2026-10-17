package xen.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Xen Focus: the part of it that keeps its eye on the goal. Its mind picks what to do (wood, a mine, food, a shelter);
 * a chore does it; and a lot of things cut a chore short on the way: a fight, a hit, its gut, something lying on the
 * ground, someone talking to it. Before Focus, a chore cut short was the end of the goal: the Xen forgot what it was
 * doing and chose again, often something else (in a test, 57 tries at food came to something 4% of the time).
 * <p>Focus tells a goal that's over (the chore got it done, or gave up on it) from one that was cut off, holds on to
 * the cut-off one while the interruption lasts, and goes back to it when things are quiet again, if it's still worth
 * it. Whether it's worth it, it learns: a small model for each kind of goal of how likely a goal like this comes to
 * something (how long it's been at it, how much ground it has covered, how many times it was cut off, night, danger,
 * health, hunger, underground or not), trained on how every goal ended, shared by all Xens and kept with the world.
 * And a goal that comes to nothing the moment it starts (no animals about to hunt) isn't picked again straight away:
 * the more often it does that, the longer it waits.
 */
final class Focus {
	static final String[] FEATURES = {"bias", "minutes in", "ground covered", "times cut off", "night", "danger", "health", "hunger", "underground"};
	private static final int N = FEATURES.length;
	/** The goals it keeps its eye on: the ones a chore does. */
	static final java.util.Set<Goals.Short> WATCHED = java.util.EnumSet.of(Goals.Short.FOOD, Goals.Short.SHELTER, Goals.Short.WOOD, Goals.Short.STONE,
			Goals.Short.ORE, Goals.Short.MINE, Goals.Short.SMELT);
	/** It lets go below this chance, after this many cut-offs, or after waiting this long for things to quieten down. */
	static final float LET_GO = 0.25f;
	static final int MOST_CUTS = 4, MOST_WAIT = 20 * 20;

	// ------------------------------------------------------------------------- kept with the world, for every Xen
	private static final Map<String, float[]> W = new HashMap<>();
	private static final Map<String, Integer> SEEN = new HashMap<>();
	private static Path file;

	static synchronized void load(Path f) {
		file = f;
		W.clear();
		SEEN.clear();
		if (f == null || !Files.exists(f)) return;
		try {
			for (String l : Files.readAllLines(f)) {
				String[] p = l.split("\t");
				if (p.length != N + 2) continue;
				float[] w = new float[N];
				for (int i = 0; i < N; i++) w[i] = Float.parseFloat(p[2 + i]);
				W.put(p[0], w);
				SEEN.put(p[0], Integer.parseInt(p[1]));
			}
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("xen: couldn't read {}: {}", f, e.toString());
		}
	}

	static synchronized void save() {
		if (file == null || W.isEmpty()) return;
		StringBuilder sb = new StringBuilder();
		for (var e : W.entrySet()) {
			sb.append(e.getKey()).append('\t').append(SEEN.getOrDefault(e.getKey(), 0));
			for (float v : e.getValue()) sb.append('\t').append(String.format(Locale.ROOT, "%.4f", v));
			sb.append('\n');
		}
		try {
			Files.writeString(file, sb.toString());
		} catch (IOException e) {
			XenMod.LOG.warn("xen: couldn't write {}: {}", file, e.toString());
		}
	}

	/** How likely a goal of this kind, like this, comes to something (0.5 for one it knows nothing about yet). */
	static synchronized float chance(Goals.Short g, float[] x) {
		float[] w = W.get(g.name());
		if (w == null) return 0.5f;
		double z = 0;
		for (int i = 0; i < N; i++) z += w[i] * x[i];
		return (float) (1 / (1 + Math.exp(-z)));
	}

	/** One goal's end: each moment it was weighed, against how it really went (logistic regression, a step each). */
	static synchronized void learn(Goals.Short g, List<float[]> moments, boolean worked) {
		float[] w = W.computeIfAbsent(g.name(), k -> new float[N]);
		int seen = SEEN.merge(g.name(), 1, Integer::sum);
		float rate = Math.max(0.05f, 0.5f / (float) Math.sqrt(seen));
		for (float[] x : moments) {
			double z = 0;
			for (int i = 0; i < N; i++) z += w[i] * x[i];
			float err = (worked ? 1f : 0f) - (float) (1 / (1 + Math.exp(-z)));
			for (int i = 0; i < N; i++) w[i] += rate * (err * x[i] - 0.001f * w[i]);
		}
	}

	// ------------------------------------------------------------------------- one Xen's
	private final Companion c;
	/** The goal it has its eye on (null: none), where it set off from, when, how far it's got, how many times it was cut off. */
	Goals.Short goal;
	private BlockPos from;
	private long since;
	private double covered;
	int cuts;
	/** The chore it started for the goal (Chores.serial). */
	private int serial;
	/** Holding on to it (cut off): from when, and when it may go back to it (-1: not holding). */
	private long heldAt = -1, backAt = -1;
	/** The moments it weighed this goal (what it was like each time), to learn from when the goal ends. */
	private final List<float[]> moments = new ArrayList<>();
	/** Goals that came to nothing at once: how many times in a row, and until when it doesn't pick them again. */
	private final EnumMap<Goals.Short, long[]> nothing = new EnumMap<>(Goals.Short.class);
	/** What it did with goals cut short (for /xen status and the tests). */
	int held, backTo, letGo;

	Focus(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** A goal begins (its chore just started). */
	void started(Goals.Short g) {
		goal = WATCHED.contains(g) ? g : null;
		moments.clear();
		if (goal == null) return;
		from = c.player.blockPosition().immutable();
		since = now();
		covered = 0;
		cuts = 0;
		serial = c.chores.serial;
		heldAt = backAt = -1;
		moments.add(features());
	}

	/** While it's going: how far it has got. */
	void watch() {
		if (goal != null && from != null) covered = Math.max(covered, Math.sqrt(c.player.blockPosition().distSqr(from)));
	}

	/** Holding on to a goal that was cut off? */
	boolean holding() {
		return goal != null && heldAt >= 0;
	}

	/** Its chore isn't going any more: was the goal cut off (another chore started over it, or something stopped it), not over? */
	boolean cutOff(Goals.Short g) {
		return goal == g && (c.chores.serial != serial || !c.chores.endedItself);
	}

	/** What it's like now, for the model. */
	float[] features() {
		var p = c.player;
		float danger = c.ex1Out == null ? 0f : c.ex1Out.danger;
		return new float[] {1f, Math.min(1f, (now() - since) / 1200f / 10f), (float) Math.min(1.0, covered / 64), Math.min(1f, cuts / (float) MOST_CUTS),
				p.level().isDarkOutside() ? 1f : 0f, danger, p.getHealth() / p.getMaxHealth(), 1f - p.getFoodData().getFoodLevel() / 20f,
				p.level().canSeeSky(p.blockPosition().above()) ? 0f : 1f};
	}

	/** Cut off: hold on to it (true) or let it go (false), by how likely it still comes to something. */
	boolean hold(String what) {
		if (goal == null) return false;
		cuts++;
		float[] x = features();
		moments.add(x);
		float chance = chance(goal, x);
		if (chance < LET_GO || cuts > MOST_CUTS) {
			letGo++;
			c.journal("focus", String.format(Locale.ROOT, "lets go of %s: cut off %d times, %.0f%% it still comes to something", what, cuts, 100 * chance));
			return false;
		}
		held++;
		heldAt = now();
		backAt = heldAt + 30;                                                   // (a moment: whatever cut it off has its turn first)
		c.journal("focus", String.format(Locale.ROOT, "cut off from %s (%d so far), %.0f%% it still comes to something: back to it when it's quiet", what, cuts, 100 * chance));
		return true;
	}

	/** Holding: quiet again, time to go back to it? */
	boolean quiet() {
		if (!holding()) return false;
		long now = now();
		if (now < backAt || c.chores.busy() || c.fightingNow() || c.hands.busy()) return false;
		return c.ex1Out == null || c.ex1Out.danger < 0.5f;
	}

	/** Not worth holding on to any more: held too long (its mind has other things to weigh), or night fell on work out in the open. */
	String stale() {
		if (!holding()) return null;
		if (now() - heldAt > MOST_WAIT) return "waited too long to get back to it";
		var p = c.player;
		boolean outdoor = goal != Goals.Short.MINE && goal != Goals.Short.SMELT && goal != Goals.Short.SHELTER;
		if (outdoor && p.level().isDarkOutside() && p.level().canSeeSky(p.blockPosition().above())) return "night fell: that can wait for the morning";
		return null;
	}

	/** Back at it: its chore started again. */
	void back(String what) {
		backTo++;
		heldAt = backAt = -1;
		serial = c.chores.serial;
		c.journal("focus", "back to " + what + " (cut off " + cuts + " time" + (cuts == 1 ? "" : "s") + ")");
	}

	/** It dropped the goal on purpose (asked for something else, night fell, bored of it, died): nothing to go back to. */
	void forget() {
		goal = null;
		heldAt = backAt = -1;
		moments.clear();
	}

	/** The goal is over: what it learned (did it come to something?), and a goal that came to nothing at once waits a while. */
	void ended(Goals.Short g, boolean worked, long tookTicks) {
		if (goal == g && !moments.isEmpty()) learn(g, new ArrayList<>(moments), worked);
		long[] n = nothing.computeIfAbsent(g, k -> new long[2]);
		if (worked) n[0] = 0;
		else if (tookTicks < 60) {
			n[0]++;
			n[1] = now() + (400L << Math.min(4, n[0] - 1));                     // (20 s, 40 s, ... up to 5 min: it keeps coming to nothing)
			if (n[0] >= 2) c.journal("focus", String.format(Locale.ROOT, "%s came to nothing at once %d times in a row: not again for %.0f s", g.what, n[0], (n[1] - now()) / 20f));
		}
		forget();
	}

	/** Not picking this one again yet (it came to nothing at once, just now)? */
	boolean resting(Goals.Short g) {
		long[] n = nothing.get(g);
		return n != null && now() < n[1];
	}

	/** For /xen status. */
	String describe() {
		return held == 0 && letGo == 0 ? "" : String.format(Locale.ROOT, "focus: cut off %d times, went back %d, let go %d%s", held + letGo, backTo, letGo,
				holding() ? " (holding on to " + goal.what + ")" : "");
	}
}
