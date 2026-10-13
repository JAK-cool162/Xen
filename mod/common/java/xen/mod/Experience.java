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
 * <p>No weights change: it is memory, looked up when it decides (easy to see and to test). Kept with the world, a few
 * hundred cases a Xen, the oldest let go first.
 */
final class Experience {
	/** One time it chose something: the situation, the choice, and how it went (-1 bad ... +1 good). */
	record Case(float[] s, int option, float outcome, boolean died, float harm, float minutes, long cell, long when) {}

	/** What it makes of the cases for a choice: what it expects (-1..1), how sure (0..1), how many, and in words. */
	record Guess(float expect, float sure, int n, String says) {}

	static final int KEEP = 400;
	/** The situation, as it remembers it: the things that change how a choice goes. */
	static final String[] PARTS = {"night", "dusk", "underground", "in water", "danger", "health", "food", "pickaxe", "sword", "armor", "iron",
			"home near", "food on it", "blocks"};
	private final Companion c;
	final ArrayDeque<Case> cases = new ArrayDeque<>();

	Experience(Companion c) {
		this.c = c;
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
		return s;
	}

	/** Where it is, as a cell (32 blocks a side, 16 high): "here" for a case. */
	static long cell(BlockPos p) {
		return ((long) (p.getX() >> 5) & 0x1FFFFF) << 42 | ((long) (p.getZ() >> 5) & 0x1FFFFF) << 21 | ((long) (p.getY() >> 4) & 0x1FFFFF);
	}

	/** A choice finished: how it went, from what Xen 2.0's critics made of it (reward, harm) and whether it died. */
	void record(float[] before, int option, float reward, float harm, boolean died, float minutes, BlockPos where) {
		if (before == null || option < 0) return;
		float outcome = (float) Math.tanh(reward / 2.0) - Math.min(1f, harm) - (died ? 1.5f : 0);
		outcome = Math.max(-1f, Math.min(1f, outcome));
		cases.addLast(new Case(before, option, outcome, died, harm, minutes, cell(where), c.player.level().getGameTime()));
		while (cases.size() > KEEP) cases.removeFirst();
	}

	/**
	 * What the cases most like now say about this choice: the nearest ones (by situation, the same spot counting
	 * for more), weighted by how alike they are; recent ones a little more. Nothing like it: no guess (sure 0).
	 */
	Guess guess(float[] now, int option, BlockPos where) {
		long here = cell(where);
		double sumW = 0, sum = 0;
		int n = 0, deaths = 0, hurt = 0, good = 0;
		long t = c.player.level().getGameTime();
		for (Case k : cases) {
			if (k.option() != option) continue;
			double d = 0;
			for (int i = 0; i < now.length && i < k.s().length; i++) d += (now[i] - k.s()[i]) * (now[i] - k.s()[i]);
			if (k.cell() == here) d *= 0.5;                                  // (right here: it counts for more)
			double w = Math.exp(-d / 0.35) * (1 + 0.5 * Math.exp(-(t - k.when()) / 72000.0));   // (alike, and recent: a few game days)
			if (w < 0.05) continue;
			sumW += w;
			sum += w * k.outcome();
			n++;
			if (k.died()) deaths++;
			else if (k.harm() > 0.3f) hurt++;
			else if (k.outcome() > 0.2f) good++;
		}
		if (n == 0) return new Guess(0, 0, 0, "");
		float expect = (float) (sum / sumW), sure = (float) (sumW / (sumW + 1.5));
		String says = n + (n == 1 ? " time" : " times") + " like this" + (deaths > 0 ? ": died " + deaths : "") + (hurt > 0 ? (deaths > 0 ? ", " : ": ") + "hurt " + hurt : "")
				+ (good > 0 ? (deaths + hurt > 0 ? ", " : ": ") + "went well " + good : "");
		return new Guess(expect, sure, n, says);
	}

	/** How familiar the situation is (0: never like this, 1: often): from all the cases, whatever was chosen. */
	float familiar(float[] now) {
		double sumW = 0;
		for (Case k : cases) {
			double d = 0;
			for (int i = 0; i < now.length && i < k.s().length; i++) d += (now[i] - k.s()[i]) * (now[i] - k.s()[i]);
			sumW += Math.exp(-d / 0.35);
		}
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
			a.add(o);
		}
		return a;
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
						o.get("harm").getAsFloat(), o.get("min").getAsFloat(), o.get("cell").getAsLong(), o.get("t").getAsLong()));
			} catch (RuntimeException ignored) {
				// (a case it can't read: let go)
			}
		}
		while (cases.size() > KEEP) cases.removeFirst();
	}
}
