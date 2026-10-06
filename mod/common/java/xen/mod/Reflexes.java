package xen.mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Xen 2.0: reaction time, like a player's. Something new (a monster in view, a bobber going under, lava at its feet,
 * a hit from behind) isn't acted on the moment it happens: it is noticed a reaction time later, about a quarter of a
 * second (a log-normal around 230 ms, rarely under 150 or over 450), slower for what it didn't see coming (it has to
 * turn first), when it's tired or confused, quicker when it's already in a fight and focused. The {@code reaction}
 * setting makes all of it faster or slower. Its turns are a hand on a mouse: quick through the middle, slowing into
 * the target (see {@link Hands}), at most as fast as a player flicks (from a recorded game: a quarter turn in about a
 * quarter second, a full flick of 180 degrees at the very most).
 */
final class Reflexes {
	private final Companion c;
	private final Random random = new Random();
	/** For each thing it noticed: the tick it can act on it. */
	private final Map<Object, long[]> noticed = new HashMap<>();
	/** The last reaction times (ms), for the journal and the tests. */
	int lastMs;
	long reactions;
	long sumMs;

	Reflexes(Companion c) {
		this.c = c;
	}

	/** The setting: fast 0.7, human 1, slow 1.4 (times the reaction time). */
	static float scale(String setting) {
		return switch (setting == null ? "" : setting) {
			case "fast" -> 0.7f;
			case "slow" -> 1.4f;
			case "instant" -> 0f;
			default -> 1f;
		};
	}

	/**
	 * One reaction time in ms: the log-normal around 230 ms, plus what it didn't see coming, plus tiredness and
	 * confusion, minus focus. Pure arithmetic (no game in it: the tests run it).
	 */
	static int sampleMs(Random r, boolean unseen, boolean focused, boolean tired, float confusion, float scale) {
		double ms = Math.exp(Math.log(230) + 0.22 * r.nextGaussian());
		if (unseen) ms += 50 + 100 * r.nextDouble();                       // (recorded fights: a player turns to whoever hit them in a median 251 ms)
		if (tired) ms += 40;
		ms += 180 * Math.max(0, Math.min(1, confusion));
		if (focused) ms -= 50;
		ms = Math.max(120, Math.min(750, ms));
		return (int) Math.round(ms * scale);
	}

	int sampleMs(boolean unseen) {
		boolean tired = c.player != null && c.player.level().isDarkOutside() && c.player.tickCount > 20 * 60 * 15;
		float own = 1.25f - 0.5f * c.personality.hidden(Personality.REFLEXES);       // (its hidden reflexes: 0.75x to 1.25x)
		int ms = sampleMs(random, unseen, c.fightingNow(), tired, c.confusion.level, scale(c.mod.config.reaction) * own);
		lastMs = ms;
		reactions++;
		sumMs += ms;
		return ms;
	}

	/** In ticks (a tick is 50 ms). */
	int sampleTicks(boolean unseen) {
		return Math.round(sampleMs(unseen) / 50f);
	}

	/**
	 * Has it noticed this yet? The first time something is seen it starts its reaction time; true once that's up. Things
	 * not asked about for 10 seconds are forgotten (seen again later: a new reaction).
	 */
	boolean ready(Object what, boolean unseen) {
		long now = c.player.level().getGameTime();
		long[] t = noticed.get(what);
		if (t == null || now - t[1] > 200) {
			if (noticed.size() > 64) noticed.values().removeIf(v -> now - v[1] > 200);
			t = new long[] {now + sampleTicks(unseen), now};
			noticed.put(what, t);
		}
		t[1] = now;
		return now >= t[0];
	}

	/** How fast it turns, from the setting (a slower reaction is a slower hand too). */
	float turnScale() {
		float s = scale(c.mod.config.reaction);
		return s <= 0 ? 2f : Math.max(0.6f, Math.min(1.5f, 1f / s));
	}

	/** Its average reaction time so far (ms), for the journal. */
	int averageMs() {
		return reactions == 0 ? 0 : (int) (sumMs / reactions);
	}
}
