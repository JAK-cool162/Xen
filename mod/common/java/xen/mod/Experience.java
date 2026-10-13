package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import xen.mod.core.Mind;

import java.util.ArrayDeque;

/**
 * What happened the times it chose something: outcome memory (Xen 2.0's "I tried that before"). Every choice it
 * finishes is kept as a case: the situation it chose in (a few things that matter: night, underground, its pickaxe,
 * its health, danger about, where it was), what it chose, and how it went (how long, what it got, what it cost, a death).
 * Before it chooses again it looks up the cases most like now for each choice it could make and weighs what they
 * say: mining at night here killed it twice, so not that again; the river took a minute to swim and nothing went wrong,
 * so swimming's fine.
 * <p>Predict, then reflect: before it starts a choice it says what it expects (how well, how risky, how long); when the
 * choice is over it holds that against what happened. Way off, it works out why: what changed while it was at it (night
 * came, monsters came, it ended up in the water) and what it hadn't weighed (the times that went fine weren't at night):
 * that thing counts for more, for that choice, from then on. So what it learns is how to size up a situation, not one
 * route: "mining at dusk goes badly" carries to every mine. Kept with the world, a few hundred cases a Xen, the oldest let
 * go first; what it weighs, and how far off its guesses have been, too.
 */
final class Experience {
	/** One time it chose something: the situation, the choice, and how it went (-1 bad ... +1 good). */
	record Case(float[] s, int option, float outcome, boolean died, float harm, float minutes, long cell, long when, boolean followed) {}

	/** What it makes of the cases for a choice: what it expects (-1..1), how sure (0..1), how many, in words; and how often it went well, how often badly, how long. */
	record Guess(float expect, float sure, int n, String says, float good, float risk, float minutes) {}

	/** What it expected of the choice it's on (made when it chose: the cases and its mind's critics together). */
	record Prediction(float expect, float good, float risk, float minutes) {}

	static final int KEEP = 400;
	/** The situation, as it remembers it: the things that change how a choice goes. */
	static final String[] PARTS = {"night", "dusk", "underground", "in water", "danger", "health", "food", "pickaxe", "sword", "armor", "iron",
			"home near", "food on it", "blocks", "water near", "a big drop near", "a roof", "unseen"};
	/** The same, as it would say it ("I didn't think about ..."). */
	static final String[] PART_WORDS = {"it being night", "it getting dark", "being underground", "being in the water", "the monsters about",
			"how hurt I was", "how hungry I was", "my pickaxe", "having a sword", "my armor", "my iron", "how close home was", "the food I had",
			"the blocks I had", "the water nearby", "the drop nearby", "the roof over me", "how little of the place I'd seen"};
	/** How much each part of the situation counts, for each choice (1 to start; learned from its own wrong guesses). */
	final float[][] weight = new float[Mind.N][PARTS.length];
	/** How far off its guesses were (|expected - what happened|), the latest first let go: is it getting better at sizing things up? */
	final ArrayDeque<Float> errors = new ArrayDeque<>();
	float firstError = -1;
	int predicted;
	/** The last thing it worked out from a wrong guess (and when): it may say so. */
	String lesson = "";
	long lessonAt = -1_000_000;
	private final Companion c;
	final ArrayDeque<Case> cases = new ArrayDeque<>();

	Experience(Companion c) {
		this.c = c;
		for (float[] w : weight) java.util.Arrays.fill(w, 1f);
	}

	/** How unlike two situations are, as this choice weighs them. */
	private double distance(float[] now, float[] then, float[] w) {
		double d = 0;
		for (int i = 0; i < now.length && i < then.length; i++) d += w[i] * (now[i] - then[i]) * (now[i] - then[i]);
		return d;
	}

	/** The situation now, as the cases are kept (0..1 each). */
	float[] situation() {
		var p = c.player;
		float[] f = MindSense.features(c);
		var items = c.items();
		float[] s = new float[PARTS.length];
		s[0] = f[Mind.NIGHT];
		s[1] = f[Mind.DUSK];
		s[2] = f[Mind.UNDERGROUND];
		s[3] = f[Mind.INWATER];
		s[4] = Math.min(1, f[Mind.DANGER]);
		s[5] = p.getHealth() / p.getMaxHealth();
		s[6] = p.getFoodData().getFoodLevel() / 20f;
		s[7] = c.crafter.pickTier() / 4f;
		s[8] = f[Mind.SWORD] > 0 ? 1 : 0;
		s[9] = Math.min(1, f[Mind.ARMOR]);
		s[10] = Math.min(1, (items.getOrDefault("iron_ingot", 0) + items.getOrDefault("raw_iron", 0)) / 3f);
		s[11] = c.goals.home != null && p.blockPosition().closerThan(c.goals.home, 48) ? 1 : 0;
		s[12] = Math.min(1, items.getOrDefault("food", 0) / 4f);
		s[13] = Math.min(1, (items.getOrDefault("cobblestone", 0) + items.getOrDefault("dirt", 0)) / 16f);
		G1X.Around a = c.g1x.around();                                       // G1X: the shape of the place (a river ahead, a cliff, a roof, how much is unseen)
		boolean water = false, drop = false;
		for (int i = 0; i < 8; i++) {
			water |= a.water()[i];
			drop |= a.drop()[i] > 6;
		}
		s[14] = water ? 1 : 0;
		s[15] = drop ? 1 : 0;
		s[16] = a.roof() ? 1 : 0;
		s[17] = a.unseenAll();
		return s;
	}

	/** Where it is, as a cell (32 blocks a side, 16 high): "here" for a case. */
	static long cell(BlockPos p) {
		return ((long) (p.getX() >> 5) & 0x1FFFFF) << 42 | ((long) (p.getZ() >> 5) & 0x1FFFFF) << 21 | ((long) (p.getY() >> 4) & 0x1FFFFF);
	}

	/** How a choice went, on its scale (-1 bad ... +1 good): what it brought, what it cost, a death. */
	static float outcome(float[] before, int option, float reward, float harm, boolean died) {
		float outcome = (float) Math.tanh(reward / 2.0) - Math.min(1f, harm) - (died ? 1.5f : 0);
		if (option == Mind.REST && before.length > 5 && before[5] > 0.7f && before[4] < 0.3f) outcome -= 0.25f;   // (healthy, nothing after it, and it sat about: time gone, nothing done)
		return Math.max(-1f, Math.min(1f, outcome));
	}

	/**
	 * A choice finished: how it went, from what Xen 2.0's critics made of it (reward, harm) and whether it died. It
	 * holds that against what it expected (reflects) and keeps the case. Back: what it worked out, in words, when it
	 * was way off (or null).
	 */
	String record(float[] before, int option, float reward, float harm, boolean died, float minutes, BlockPos where, boolean followed,
			Prediction expected, float[] after) {
		if (before == null || option < 0) return null;
		float outcome = outcome(before, option, reward, harm, died);
		String said = reflect(before, option, outcome, died, where, expected, after);
		cases.addLast(new Case(before, option, outcome, died, harm, minutes, cell(where), c.player.level().getGameTime(), followed));
		while (cases.size() > KEEP) cases.removeFirst();
		return said;
	}

	/**
	 * Reflect: what it expected against what happened. Its cases' guess is put right (the parts of the situation that
	 * told the cases that misled it apart count for more for this choice; the ones that didn't, a little less), how far
	 * off it was is kept, and when it was way off it works out why in words: what changed while it was at it, or what it
	 * hadn't weighed.
	 */
	private String reflect(float[] before, int option, float outcome, boolean died, BlockPos where, Prediction expected, float[] after) {
		float[] w = weight[option];
		long here = cell(where), t = c.player.level().getGameTime();
		int m = Math.min(before.length, w.length);
		double sumK = 0, sumKY = 0;
		double[] dK = new double[m], dKY = new double[m];
		for (Case k : cases) {                                               // the cases' guess, as it was before this one (and how it moves with each weight)
			if (k.option() != option) continue;
			double scale = k.cell() == here ? 0.5 : 1;
			double kk = Math.exp(-distance(before, k.s(), w) * scale / 0.35) * (1 + 0.5 * Math.exp(-(t - k.when()) / 72000.0));
			if (kk < 0.02) continue;
			sumK += kk;
			sumKY += kk * k.outcome();
			for (int i = 0; i < m && i < k.s().length; i++) {
				double g = -kk * scale * (before[i] - k.s()[i]) * (before[i] - k.s()[i]) / 0.35;
				dK[i] += g;
				dKY[i] += g * k.outcome();
			}
		}
		int missed = -1;
		if (sumK > 0.1) {
			double guess = sumKY / sumK, err = guess - outcome;
			double grow = 0;
			for (int i = 0; i < m; i++) {
				double dGuess = (dKY[i] - guess * dK[i]) / sumK;               // (how the guess moves as this part counts for more)
				double step = Math.max(-0.5, Math.min(0.5, -0.3 * err * dGuess));   // (a nudge a time: one odd case mustn't turn it round)
				float was = w[i];
				w[i] = (float) Math.max(0.2, Math.min(5, w[i] + step));
				if (w[i] - was > grow) {
					grow = w[i] - was;
					missed = i;
				}
			}
			if (grow < 0.05) missed = -1;
		}
		if (expected == null) return null;
		float off = Math.abs(expected.expect() - outcome);
		predicted++;
		errors.addLast(off);
		while (errors.size() > 50) errors.removeFirst();
		if (predicted == 30) firstError = meanError();                       // (how good its guesses were at first: to see it get better)
		if (off < 0.6f || after == null) return null;
		boolean worse = outcome < expected.expect();
		String changed = null;
		if (worse) {                                                          // what changed while it was at it
			if (before[0] < 0.5f && after[0] > 0.5f) changed = "night came while I was at it";
			else if (after[4] - before[4] > 0.4f) changed = "monsters came";
			else if (before[3] < 0.5f && after[3] > 0.5f) changed = "I ended up in the water";
			else if (before[7] > 0 && after[7] < before[7]) changed = "I lost my pickaxe";
			else if (before[5] - after[5] > 0.35f || died) changed = died ? "it killed me" : "I got hurt badly";
			else if (before[6] - after[6] > 0.35f) changed = "I got hungry";
		}
		String what = doing(option);
		String line;
		if (worse) {
			line = what + " went worse than I thought"
					+ (changed != null ? ": " + changed : "")
					+ (missed >= 0 ? (changed != null ? ". I didn't think about " : ". I didn't think enough about ") + PART_WORDS[missed] + ". I will next time." : ".");
		} else {
			line = what + " went better than I thought"
					+ (missed >= 0 ? ". " + Character.toUpperCase(PART_WORDS[missed].charAt(0)) + PART_WORDS[missed].substring(1) + " matters more than I knew." : ".");
		}
		if (worse && (changed != null || missed >= 0)) {
			lesson = line;
			lessonAt = t;
		}
		return line;
	}

	/** How far off its guesses have been lately (0: spot on ... 2), or -1: none yet. */
	float meanError() {
		if (errors.isEmpty()) return -1;
		float s = 0;
		for (float e : errors) s += e;
		return s / errors.size();
	}

	/**
	 * What it expects of a choice: the cases like now and, as much as they're few, how that choice usually goes for it
	 * anywhere (and, never done, how its choices go at all; nothing yet: no idea, even odds).
	 */
	Prediction predict(Guess g, int option) {
		double n = 0, sum = 0, good = 0, bad = 0, min = 0, nAll = 0, sumAll = 0, goodAll = 0, badAll = 0;
		for (Case k : cases) {
			boolean b = k.died() || k.harm() > 0.3f;
			nAll++;
			sumAll += k.outcome();
			if (k.outcome() > 0) goodAll++;
			if (b) badAll++;
			if (k.option() != option) continue;
			n++;
			sum += k.outcome();
			if (k.outcome() > 0) good++;
			if (b) bad++;
			min += k.minutes();
		}
		float pE = 0, pG = 0.5f, pR = 0.25f;                                   // (no idea: even odds, some risk, as anything new)
		if (nAll > 0) {                                                      // (how its choices go at all, a few cases' worth)
			double a = nAll / (nAll + 3);
			pE = (float) (a * sumAll / nAll);
			pG = (float) (a * goodAll / nAll + (1 - a) * 0.5);
			pR = (float) (a * badAll / nAll + (1 - a) * 0.25);
		}
		if (n > 0) {                                                         // (how this one usually goes)
			double a = n / (n + 3);
			pE = (float) (a * sum / n + (1 - a) * pE);
			pG = (float) (a * good / n + (1 - a) * pG);
			pR = (float) (a * bad / n + (1 - a) * pR);
		}
		float s = g == null || g.n() == 0 ? 0 : g.sure();
		float expect = g == null || g.n() == 0 ? pE : s * g.expect() + (1 - s) * pE;
		float gd = g == null || g.n() == 0 ? pG : s * g.good() + (1 - s) * pG;
		float risk = g == null || g.n() == 0 ? pR : s * g.risk() + (1 - s) * pR;
		float minutes = g != null && g.n() > 0 ? g.minutes() : n > 0 ? (float) (min / n) : -1;
		return new Prediction(expect, Math.max(0, Math.min(1, gd)), Math.max(0, Math.min(1, risk)), minutes);
	}

	/** A choice as a doing ("Mining", "Getting wood"), to say how it went. */
	static String doing(int option) {
		String[] ing = {"Resting", "Getting wood", "Getting stone", "Crafting", "Finding food", "Eating", "Making a shelter", "Sleeping", "Building the house",
				"Making a farm", "Mining", "Smelting", "Putting things away", "Exploring", "Trading", "Staying with my friend", "Helping out", "Guarding",
				"Fighting", "Getting away", "The adventure", "Enchanting"};
		return option >= 0 && option < ing.length ? ing[option] : Mind.SAYS[option];
	}

	/**
	 * What the cases most like now say about this choice: the nearest ones (by situation, the same spot counting
	 * for more), weighted by how alike they are; recent ones a little more. Nothing like it: no guess (sure 0).
	 */
	Guess guess(float[] now, int option, BlockPos where) {
		long here = cell(where);
		double sumW = 0, sum = 0, sumGood = 0, sumBad = 0, sumMin = 0;
		int n = 0, deaths = 0, hurt = 0, good = 0;
		long t = c.player.level().getGameTime();
		float[] wt = weight[option];
		for (Case k : cases) {
			if (k.option() != option) continue;
			double d = distance(now, k.s(), wt);                              // (as it has learned to weigh them, for this choice)
			if (k.cell() == here) d *= 0.5;                                  // (right here: it counts for more)
			double w = Math.exp(-d / 0.35) * (1 + 0.5 * Math.exp(-(t - k.when()) / 72000.0));   // (alike, and recent: a few game days)
			if (w < 0.05) continue;
			sumW += w;
			sum += w * k.outcome();
			sumMin += w * k.minutes();
			if (k.outcome() > 0) sumGood += w;
			if (k.died() || k.harm() > 0.3f) sumBad += w;
			n++;
			if (k.died()) deaths++;
			else if (k.harm() > 0.3f) hurt++;
			else if (k.outcome() > 0.2f) good++;
		}
		if (n == 0) return new Guess(0, 0, 0, "", 0, 0, -1);
		float expect = (float) (sum / sumW), sure = (float) (sumW / (sumW + 1.5));
		String says = n + (n == 1 ? " time" : " times") + " like this" + (deaths > 0 ? ": died " + deaths : "") + (hurt > 0 ? (deaths > 0 ? ", " : ": ") + "hurt " + hurt : "")
				+ (good > 0 ? (deaths + hurt > 0 ? ", " : ": ") + "went well " + good : "");
		return new Guess(expect, sure, n, says, (float) (sumGood / sumW), (float) (sumBad / sumW), (float) (sumMin / sumW));
	}

	/**
	 * How much it goes by the usual plan, from how that has worked out for it: the times it followed the plan against
	 * the times it went its own way. Few times either way: a newcomer listens to the plan (1.6); followed and it went
	 * well, more (up to 2.5); its own calls went better, less (down to 0.5). Learned, not set.
	 */
	float planTrust() {
		double sumP = 0, sumO = 0;
		int nP = 0, nO = 0;
		for (Case k : cases) {
			if (k.option() == Mind.REST) continue;                           // (resting says little about whose calls work)
			if (k.followed()) { sumP += k.outcome(); nP++; } else { sumO += k.outcome(); nO++; }
		}
		if (nP + nO < 8) return 1.6f;
		double p = nP == 0 ? 0 : sumP / nP, o = nO == 0 ? 0 : sumO / nO;
		double sure = Math.min(nP, nO) / (Math.min(nP, nO) + 6.0);
		return (float) Math.max(0.5, Math.min(2.5, 1.6 + 2.5 * sure * (p - o)));
	}

	/** How familiar the situation is (0: never like this, 1: often): from all the cases, whatever was chosen. */
	float familiar(float[] now) {
		double sumW = 0;
		for (Case k : cases) sumW += Math.exp(-distance(now, k.s(), weight[k.option()]) / 0.35);
		return (float) (sumW / (sumW + 4));
	}

	// ---------------------------------------------------------------------------------- saved with the world
	JsonArray save() {
		JsonArray a = new JsonArray();
		for (Case k : cases) {
			JsonObject o = new JsonObject();
			JsonArray s = new JsonArray();
			for (float v : k.s()) s.add(Math.round(v * 100) / 100f);
			o.add("s", s);
			o.addProperty("o", Mind.OPTIONS[k.option()]);
			o.addProperty("out", Math.round(k.outcome() * 100) / 100f);
			if (k.died()) o.addProperty("died", true);
			o.addProperty("harm", Math.round(k.harm() * 100) / 100f);
			o.addProperty("min", Math.round(k.minutes() * 10) / 10f);
			o.addProperty("cell", k.cell());
			o.addProperty("t", k.when());
			if (k.followed()) o.addProperty("plan", true);
			a.add(o);
		}
		return a;
	}

	/** What it has learned to weigh (only what moved off 1) and how far off its guesses have been. */
	JsonObject saveLearned() {
		JsonObject o = new JsonObject();
		JsonObject ws = new JsonObject();
		for (int i = 0; i < weight.length; i++) {
			boolean moved = false;
			for (float v : weight[i]) moved |= Math.abs(v - 1) > 0.01f;
			if (!moved) continue;
			JsonArray a = new JsonArray();
			for (float v : weight[i]) a.add(Math.round(v * 100) / 100f);
			ws.add(Mind.OPTIONS[i], a);
		}
		o.add("weights", ws);
		JsonArray e = new JsonArray();
		for (float v : errors) e.add(Math.round(v * 100) / 100f);
		o.add("errors", e);
		o.addProperty("predicted", predicted);
		o.addProperty("firstError", firstError);
		return o;
	}

	void loadLearned(JsonObject o) {
		if (o == null) return;
		try {
			if (o.has("weights")) for (var e : o.getAsJsonObject("weights").entrySet()) {
				int i = java.util.Arrays.asList(Mind.OPTIONS).indexOf(e.getKey());
				if (i < 0) continue;
				JsonArray a = e.getValue().getAsJsonArray();
				for (int j = 0; j < weight[i].length && j < a.size(); j++) weight[i][j] = Math.max(0.2f, Math.min(5f, a.get(j).getAsFloat()));
			}
			errors.clear();
			if (o.has("errors")) for (var v : o.getAsJsonArray("errors")) errors.addLast(v.getAsFloat());
			if (o.has("predicted")) predicted = o.get("predicted").getAsInt();
			if (o.has("firstError")) firstError = o.get("firstError").getAsFloat();
		} catch (RuntimeException ignored) {
			// (what it can't read: it starts over on that)
		}
	}

	/** What it has learned to weigh most, in words (for /xen predict): the parts that count most for a choice. */
	String weighs(int option) {
		Integer[] idx = new Integer[PARTS.length];
		for (int i = 0; i < idx.length; i++) idx[i] = i;
		float[] w = weight[option];
		java.util.Arrays.sort(idx, (x, y) -> Float.compare(w[y], w[x]));
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 2; i++) if (w[idx[i]] > 1.15f) sb.append(sb.length() == 0 ? "" : ", ").append(PARTS[idx[i]]).append(String.format(java.util.Locale.ROOT, " x%.1f", w[idx[i]]));
		return sb.toString();
	}

	void load(JsonArray a) {
		cases.clear();
		if (a == null) return;
		for (var e : a) {
			try {
				JsonObject o = e.getAsJsonObject();
				int option = java.util.Arrays.asList(Mind.OPTIONS).indexOf(o.get("o").getAsString());
				if (option < 0) continue;
				JsonArray s = o.getAsJsonArray("s");
				float[] v = new float[PARTS.length];
				for (int i = 0; i < v.length && i < s.size(); i++) v[i] = s.get(i).getAsFloat();
				cases.addLast(new Case(v, option, o.get("out").getAsFloat(), o.has("died") && o.get("died").getAsBoolean(),
						o.get("harm").getAsFloat(), o.get("min").getAsFloat(), o.get("cell").getAsLong(), o.get("t").getAsLong(),
						o.has("plan") && o.get("plan").getAsBoolean()));
			} catch (RuntimeException ignored) {
				// (a case it can't read: let go)
			}
		}
		while (cases.size() > KEEP) cases.removeFirst();
	}
}
