package xen.mod;

import com.google.gson.JsonObject;

/**
 * Survival tips: what players tell a new player ("hurt at night? dig in and heal first"), not rules. A tip comes to mind
 * when its situation does, and the Xen weighs it: how much it has come to trust that tip, how badly the situation calls
 * for it, its nerve (cautious: it listens; brave, proud, wrathful: it goes its own way more), and it takes the tip or
 * doesn't. Then it sees what happened: it took it and came out fine, it trusts the tip more; it went its own way and got
 * badly hurt or died, it trusts it more too ("should've listened"); it went its own way and was fine, a little less. So
 * each Xen ends up with its own idea of which tips are worth it. Kept with the Xen.
 */
final class Tips {
	enum Tip {
		CANT_WIN("can't win this fight: get out of reach (two blocks up) or away, heal, then come back"),
		NIGHTFALL("night's here and you're out working: a roof (or a hole) before it gets worse"),
		HURT_IN_DARK("hurt and out in the dark: a roof first, heal, then go on"),
		NO_SWORD_NIGHT("no sword and it's night: let your things wait till morning");

		final String says;

		Tip(String says) {
			this.says = says;
		}
	}

	private final Companion c;
	private final java.util.Random random = new java.util.Random();
	/** How much it trusts each tip, 0.05 to 0.95 (-1: not yet: from its nerve, the first time). */
	private final float[] trust = new float[Tip.values().length];
	/** Times it took each tip, went its own way, and what happened (the tip was right, the tip was wrong). */
	private final int[] taken = new int[trust.length], ignored = new int[trust.length], right = new int[trust.length], wrong = new int[trust.length];
	/** Its call on each tip, till when it sticks to it, and when (and from what health) it sees how it went. */
	private final boolean[] took = new boolean[trust.length];
	private final long[] decidedUntil = new long[trust.length], judgeAt = new long[trust.length];
	private final float[] healthThen = new float[trust.length];

	Tips(Companion c) {
		this.c = c;
		java.util.Arrays.fill(trust, -1);
		java.util.Arrays.fill(judgeAt, -1);
	}

	private float trust(Tip t) {
		int i = t.ordinal();
		if (trust[i] < 0) {                                                   // the first time: what its nerve makes of tips
			var who = c.personality;
			trust[i] = clamp(0.35f + 0.35f * (1 - who.bravery) - 0.15f * who.sin(xen.mod.core.Sins.PRIDE) + 0.1f * random.nextFloat());
		}
		return trust[i];
	}

	private static float clamp(float v) {
		return Math.max(0.05f, Math.min(0.95f, v));
	}

	/**
	 * The tip comes to mind: does it take it? pull: how badly the situation calls for it (0 to 1). why: the situation, in
	 * a few words (for its thoughts). Its call sticks for a while (hold, in ticks), so it doesn't dither every moment.
	 */
	boolean take(Tip t, float pull, String why, int hold) {
		int i = t.ordinal();
		long now = c.player.level().getGameTime();
		judge(now);
		if (now < decidedUntil[i]) return took[i];
		var who = c.personality;
		float nerve = 0.3f * who.bravery + 0.25f * who.sin(xen.mod.core.Sins.PRIDE) + 0.15f * who.sin(xen.mod.core.Sins.WRATH);
		if (t == Tip.CANT_WIN && who.believes("warrior_never_runs")) nerve += 0.5f;      // (its beliefs: a warrior stands its ground...)
		if (t == Tip.CANT_WIN && who.believes("live_to_fight")) nerve -= 0.3f;           // (...one who lives to fight again backs off)
		if (t != Tip.CANT_WIN && who.believes("night_belongs_to_monsters")) nerve -= 0.2f;   // (the night is theirs: it listens)
		float score = 0.9f * trust(t) + 0.8f * Math.max(0, Math.min(1, pull)) - nerve;
		float p = 1f / (1f + (float) Math.exp(-6 * (score - 0.55f)));
		boolean yes = random.nextFloat() < p;
		took[i] = yes;
		decidedUntil[i] = now + hold;
		if (judgeAt[i] < 0) {                                                  // (one judgement at a time per tip)
			judgeAt[i] = now + Math.max(200, 2L * hold);
			healthThen[i] = c.player.getHealth();
		}
		if (yes) taken[i]++;
		else ignored[i]++;
		c.journal("thinks", String.format(java.util.Locale.ROOT, "a tip comes to mind (%s): \"%s\" -> %s (trusts it %.0f%%)", why, t.says,
				yes ? "takes it" : "goes its own way" + (nerve > 0.35f ? " (it's got the nerve)" : ""), 100 * trust(t)));
		return yes;
	}

	/** It died: whatever tips it was weighing get judged now. */
	void died() {
		long now = c.player.level().getGameTime();
		for (int i = 0; i < trust.length; i++) if (judgeAt[i] >= 0) settle(i, true, 20);
		java.util.Arrays.fill(decidedUntil, now);
	}

	/** Every so often (from its ticks): how did taking (or not taking) each tip go? */
	void tick() {
		if (c.player != null) judge(c.player.level().getGameTime());
	}

	private void judge(long now) {
		for (int i = 0; i < trust.length; i++) {
			if (judgeAt[i] < 0 || now < judgeAt[i]) continue;
			settle(i, false, healthThen[i] - c.player.getHealth());
		}
	}

	private void settle(int i, boolean died, float lost) {
		Tip t = Tip.values()[i];
		float was = trust(t);
		boolean bad = died || lost >= 8;
		if (took[i]) {
			if (died) wrong[i]++;
			else right[i]++;
			trust[i] = clamp(was + (died ? -0.06f : 0.04f));
		} else {
			if (bad) right[i]++;
			else wrong[i]++;
			trust[i] = clamp(was + (bad ? 0.12f : -0.05f));
		}
		judgeAt[i] = -1;
		if (Math.abs(trust[i] - was) >= 0.04f) c.journal("learns", String.format(java.util.Locale.ROOT, "\"%s\": %s -> trusts it %.0f%% (was %.0f%%)", t.says,
				took[i] ? (died ? "took it and died anyway" : "took it and came out fine") : (bad ? "went its own way and " + (died ? "died" : "got badly hurt") + ": should've listened" : "went its own way and was fine"),
				100 * trust[i], 100 * was));
	}

	/** For /xen tips: each tip, how much it trusts it, and how it went. */
	String describe() {
		StringBuilder sb = new StringBuilder(c.name).append("'s tips:");
		for (Tip t : Tip.values()) {
			int i = t.ordinal();
			sb.append(String.format(java.util.Locale.ROOT, " \"%s\" %.0f%% (took %d, own way %d; right %d, wrong %d);", t.says, 100 * trust(t), taken[i], ignored[i], right[i], wrong[i]));
		}
		return sb.toString();
	}

	JsonObject save() {
		JsonObject o = new JsonObject();
		for (Tip t : Tip.values()) {
			int i = t.ordinal();
			if (trust[i] < 0) continue;
			com.google.gson.JsonArray a = new com.google.gson.JsonArray();
			a.add(Math.round(trust[i] * 100) / 100f);
			a.add(taken[i]);
			a.add(ignored[i]);
			a.add(right[i]);
			a.add(wrong[i]);
			o.add(t.name().toLowerCase(java.util.Locale.ROOT), a);
		}
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		for (Tip t : Tip.values()) {
			String k = t.name().toLowerCase(java.util.Locale.ROOT);
			if (!o.has(k)) continue;
			try {
				var a = o.getAsJsonArray(k);
				int i = t.ordinal();
				trust[i] = clamp(a.get(0).getAsFloat());
				taken[i] = a.get(1).getAsInt();
				ignored[i] = a.get(2).getAsInt();
				right[i] = a.get(3).getAsInt();
				wrong[i] = a.get(4).getAsInt();
			} catch (RuntimeException ignoredBad) {
				// (one it can't read: it starts over on that tip)
			}
		}
	}
}
