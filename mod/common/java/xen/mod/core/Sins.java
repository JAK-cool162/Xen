package xen.mod.core;

import java.util.Random;

/**
 * The seven deadly sins, as a Xen's nature: each 0 to 1, given at birth and never changed (a child gets a mix of its
 * parents'). Every Xen has one that stands out and one a little behind it, so no two are quite alike:
 * <ul>
 *   <li><b>Pride</b>: little fear, doesn't back down or run, builds big, boasts, stands tall when someone comes.</li>
 *   <li><b>Greed</b>: mines and hoards, trades hard, shares little, eyes what others carry.</li>
 *   <li><b>Lust</b>: craves company and pretty things: seeks people out, greets everyone, likes gold and gems.</li>
 *   <li><b>Envy</b>: compares its gear with everyone's, gears up, resents the better-off, wary of strangers.</li>
 *   <li><b>Gluttony</b>: eats early and often, keeps a lot of food, farms and hunts.</li>
 *   <li><b>Wrath</b>: quick to fight, slow to forgive, stands its ground, stares others down.</li>
 *   <li><b>Sloth</b>: rests a lot, gives up sooner, sleeps early, stays close to home.</li>
 * </ul>
 */
public final class Sins {
	public static final String[] NAMES = {"pride", "greed", "lust", "envy", "gluttony", "wrath", "sloth"};
	/** How it sounds, for "proud and greedy". */
	public static final String[] ADJECTIVES = {"proud", "greedy", "lustful", "envious", "gluttonous", "wrathful", "lazy"};
	public static final int PRIDE = 0, GREED = 1, LUST = 2, ENVY = 3, GLUTTONY = 4, WRATH = 5, SLOTH = 6, N = 7;

	private Sins() {
	}

	/** A newborn's sins: one strong (0.7 to 1), one behind it (0.4 to 0.7), the rest small (up to 0.35). */
	public static float[] random(Random r) {
		float[] s = new float[N];
		for (int i = 0; i < N; i++) s[i] = 0.35f * r.nextFloat();
		int top = r.nextInt(N), second = (top + 1 + r.nextInt(N - 1)) % N;
		s[top] = 0.7f + 0.3f * r.nextFloat();
		s[second] = 0.4f + 0.3f * r.nextFloat();
		return s;
	}

	/** A child's: each from one of its parents, a little changed. */
	public static float[] mix(float[] a, float[] b, Random r) {
		float[] s = new float[N];
		for (int i = 0; i < N; i++) s[i] = clamp((r.nextBoolean() ? a[i] : b[i]) + (float) r.nextGaussian() * 0.08f);
		return s;
	}

	public static int top(float[] s) {
		int best = 0;
		for (int i = 1; i < N; i++) if (s[i] > s[best]) best = i;
		return best;
	}

	public static int second(float[] s) {
		int top = top(s), best = top == 0 ? 1 : 0;
		for (int i = 0; i < N; i++) if (i != top && s[i] > s[best]) best = i;
		return best;
	}

	/** "proud, and a little greedy". */
	public static String describe(float[] s) {
		int a = top(s), b = second(s);
		return ADJECTIVES[a] + (s[b] >= 0.4f ? ", and a little " + ADJECTIVES[b] : "");
	}

	/** Each as a number: "pride 0.91, greed 0.52, ...". */
	public static String numbers(float[] s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < N; i++) sb.append(i == 0 ? "" : ", ").append(NAMES[i]).append(String.format(java.util.Locale.ROOT, " %.2f", s[i]));
		return sb.toString();
	}

	public static int index(String name) {
		for (int i = 0; i < N; i++) if (NAMES[i].equalsIgnoreCase(name)) return i;
		return -1;
	}

	/** What its sins lean it toward, among its mind's choices (added to what each is worth). */
	public static float[] bias(float[] s) {
		float[] b = new float[Mind.N];
		float pride = s[PRIDE], greed = s[GREED], lust = s[LUST], envy = s[ENVY], glut = s[GLUTTONY], wrath = s[WRATH], sloth = s[SLOTH];
		b[Mind.HOUSE] += 0.2f * pride - 0.1f * sloth;
		b[Mind.ENCHANT] += 0.2f * pride + 0.1f * envy;
		b[Mind.ADVENTURE] += 0.3f * pride - 0.2f * sloth;
		b[Mind.FLEE] -= 0.3f * pride + 0.2f * wrath;
		b[Mind.HELP] -= 0.1f * pride + 0.3f * greed;
		b[Mind.MINE] += 0.3f * greed + 0.1f * envy - 0.1f * sloth;
		b[Mind.TRADE] += 0.2f * greed + 0.1f * lust;
		b[Mind.STORE] += 0.15f * greed;
		b[Mind.FOLLOW] += 0.3f * lust;
		b[Mind.EXPLORE] += 0.1f * lust - 0.2f * sloth;
		b[Mind.CRAFT] += 0.25f * envy;
		b[Mind.FIGHT] += 0.1f * pride + 0.1f * envy + 0.4f * wrath;
		b[Mind.GUARD] += 0.2f * wrath;
		b[Mind.FOOD] += 0.3f * glut;
		b[Mind.FARM] += 0.3f * glut;
		b[Mind.EAT] += 0.3f * glut;
		b[Mind.REST] += 0.4f * sloth;
		b[Mind.SLEEP] += 0.2f * sloth;
		return b;
	}

	private static float clamp(float x) {
		return Math.max(0f, Math.min(1f, x));
	}
}
