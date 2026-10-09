package xen.mod;

/**
 * How much a Xen believes in itself, 0.05 to 0.95. It starts from who it is (bravery, pride) and then is earned: its
 * own calls that work out raise it (it went against its gut and was right, it finished what it set out to do, it won a
 * fight), the ones that don't lower it (hurt after going against its gut, a lost fight, a death). Bad days fade: it
 * comes back a little toward who it is, every few minutes.
 * <p>What it changes: in an argument with its gut its mind's case counts for more (or less); it hesitates less when
 * it's confused; and it says so now and then ("I've got this."). Saved with the world.
 */
final class SelfBelief {
	private final Companion c;
	/** -1: not yet (it's worked out from its personality the first time it's asked). */
	float value = -1;
	private long recoveredAt;
	private boolean saidHigh, saidLow;

	SelfBelief(Companion c) {
		this.c = c;
	}

	/** Who it is: braver and prouder, more sure of itself. */
	float baseline() {
		var p = c.personality;
		return clamp(0.5f + 0.3f * (p.bravery - 0.5f) + 0.15f * p.sin(xen.mod.core.Sins.PRIDE) - 0.1f * p.sin(xen.mod.core.Sins.SLOTH));
	}

	float get() {
		if (value < 0) value = baseline();
		return value;
	}

	private static float clamp(float v) {
		return Math.max(0.05f, Math.min(0.95f, v));
	}

	/** Something it did worked out (by how much: 0.01 for a chore, more for a hard call it made against its gut). */
	void up(float by, String why) {
		float before = get();
		value = clamp(before + by * (1 - before));                          // (harder to raise the surer it is)
		if (value >= 0.8f && before < 0.8f && !saidHigh) {
			saidHigh = true;
			saidLow = false;
			c.chatter(c.pick3("I'm getting good at this.", "Okay, I've got this.", "I trust myself more now."), false);
		}
		if (by >= 0.03f) c.journal("believes", String.format(java.util.Locale.ROOT, "believes in itself more (%.2f): %s", value, why));
	}

	/** Something it did went wrong. */
	void down(float by, String why) {
		float before = get();
		value = clamp(before - by * before);
		if (value <= 0.25f && before > 0.25f && !saidLow) {
			saidLow = true;
			saidHigh = false;
			c.chatter(c.pick3("Maybe I'm not as good at this as I thought.", "I keep messing up...", "Okay, I need to be more careful."), false);
		}
		if (by >= 0.03f) c.journal("believes", String.format(java.util.Locale.ROOT, "believes in itself less (%.2f): %s", value, why));
	}

	/** Every so often: a bad day fades (and a lucky streak settles), a tenth of the way back to who it is. */
	void tick(long now) {
		if (now - recoveredAt < 20 * 60 * 5) return;
		recoveredAt = now;
		float v = get();
		value = clamp(v + 0.1f * (baseline() - v));
	}
}
