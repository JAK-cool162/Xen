package xen.mod.core;

import java.util.Locale;
import java.util.Random;

/**
 * A Xen's own game plan: not given to it, it works it out. There are eight ways people play (rush the dragon, build a
 * home first, settle down and farm, get rich underground, see the world, trade, fight, play it safe). A Xen picks one
 * from what its sins pull it toward and what has worked for Xens like it (the {@link Book}: every Xen's days are
 * scored by the progress they made, per plan and per nature); it keeps to it, and when it gets nowhere for a couple of
 * days it thinks again and changes plans, like a player who sees their plan isn't working. Its mind knows its plan (an
 * input), and its plan leans its choices.
 */
public final class Strategy {
	public static final String[] NAMES = {"speedrun", "builder", "settler", "miner", "explorer", "trader", "warrior", "survivor"};
	public static final int SPEEDRUN = 0, BUILDER = 1, SETTLER = 2, MINER = 3, EXPLORER = 4, TRADER = 5, WARRIOR = 6, SURVIVOR = 7, N = 8;
	/** The plan in its own words. */
	public static final String[] WORDS = {
			"beat the game fast: iron, diamonds, the Nether, the dragon. A house can wait.",
			"a home first, a good one, then everything else.",
			"a good life first: a farm, animals, a warm house. The End can wait.",
			"get rich down below: ore, and lots of it.",
			"see the world: villages, temples, whatever's out there.",
			"villagers, emeralds and good deals.",
			"gear up and fight. The strongest wins.",
			"safety first: armor, a bed, never out at night.",
	};
	private static final float[][] AFFINITY = new float[N][Sins.N];
	private static final float[][] BIAS = new float[N][Mind.N];

	static {
		// (balanced: every nature has a plan or two it leans to, and every plan draws about as many)
		aff(SPEEDRUN, Sins.PRIDE, 0.6f, Sins.ENVY, 0.3f, Sins.WRATH, 0.3f, Sins.SLOTH, -0.5f);
		aff(BUILDER, Sins.PRIDE, 0.5f, Sins.LUST, 0.4f);
		aff(SETTLER, Sins.GLUTTONY, 0.6f, Sins.SLOTH, 0.4f);
		aff(MINER, Sins.GREED, 0.6f, Sins.ENVY, 0.3f);
		aff(EXPLORER, Sins.LUST, 0.65f, Sins.ENVY, 0.25f, Sins.SLOTH, -0.3f);
		aff(TRADER, Sins.GREED, 0.58f, Sins.LUST, 0.35f);
		aff(WARRIOR, Sins.WRATH, 0.6f, Sins.PRIDE, 0.3f);
		aff(SURVIVOR, Sins.SLOTH, 0.45f, Sins.GLUTTONY, 0.25f, Sins.ENVY, 0.25f);
		bias(SPEEDRUN, Mind.MINE, 0.2f, Mind.CRAFT, 0.2f, Mind.ADVENTURE, 0.5f, Mind.HOUSE, -0.2f, Mind.FARM, -0.3f, Mind.EXPLORE, -0.1f);
		bias(BUILDER, Mind.HOUSE, 0.4f, Mind.WOOD, 0.2f, Mind.STONE, 0.1f);
		bias(SETTLER, Mind.FARM, 0.4f, Mind.FOOD, 0.2f, Mind.HOUSE, 0.2f, Mind.ADVENTURE, -0.2f);
		bias(MINER, Mind.MINE, 0.4f, Mind.SMELT, 0.1f, Mind.STORE, 0.1f);
		bias(EXPLORER, Mind.EXPLORE, 0.4f, Mind.TRADE, 0.1f, Mind.ADVENTURE, 0.1f);
		bias(TRADER, Mind.TRADE, 0.4f, Mind.EXPLORE, 0.1f);
		bias(WARRIOR, Mind.FIGHT, 0.3f, Mind.CRAFT, 0.2f, Mind.GUARD, 0.2f, Mind.ENCHANT, 0.1f);
		bias(SURVIVOR, Mind.SHELTER, 0.2f, Mind.SLEEP, 0.2f, Mind.CRAFT, 0.1f, Mind.FLEE, 0.1f);
	}

	private static void aff(int s, Object... pairs) {
		for (int i = 0; i < pairs.length; i += 2) AFFINITY[s][(Integer) pairs[i]] = (Float) pairs[i + 1];
	}

	private static void bias(int s, Object... pairs) {
		for (int i = 0; i < pairs.length; i += 2) BIAS[s][(Integer) pairs[i]] = (Float) pairs[i + 1];
	}

	private Strategy() {
	}

	public static int index(String name) {
		for (int i = 0; i < N; i++) if (NAMES[i].equalsIgnoreCase(name)) return i;
		return -1;
	}

	/** Its plan's lean on each of its mind's choices. */
	public static float[] bias(int s) {
		return s < 0 ? new float[Mind.N] : BIAS[s].clone();
	}

	/** How much its sins pull it toward a plan. */
	public static float pull(int s, float[] sins) {
		float x = 0;
		for (int i = 0; i < Sins.N; i++) x += AFFINITY[s][i] * sins[i];
		return x;
	}

	/**
	 * Pick a plan: its sins' pull, plus what the book says worked for Xens like it (each plan's average progress a day),
	 * plus a little chance; a plan never tried by its kind gets the benefit of the doubt. Not the one it gives up on.
	 */
	public static int choose(float[] sins, Book book, int not, Random r) {
		int kind = Sins.top(sins), best = 0;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int s = 0; s < N; s++) {
			if (s == not) continue;
			double learned = book == null ? 0 : book.value(kind, s);
			int tried = book == null ? 0 : book.count(kind, s);
			double score = 1.5 * pull(s, sins) + 0.25 * learned + (tried < 3 ? 0.3 : 0) + 0.35 * r.nextDouble();
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		return best;
	}

	/** What every Xen's plans led to: the average progress a day, for each nature (top sin) and plan. */
	public static final class Book {
		private final double[][] sum = new double[Sins.N][N];
		private final int[][] n = new int[Sins.N][N];

		public synchronized void add(int kind, int s, double progress) {
			sum[kind][s] += progress;
			n[kind][s]++;
			if (n[kind][s] > 200) {                                              // (recent days count most)
				sum[kind][s] *= 0.5;
				n[kind][s] /= 2;
			}
		}

		public synchronized double value(int kind, int s) {
			return n[kind][s] == 0 ? 0 : sum[kind][s] / n[kind][s];
		}

		public synchronized int count(int kind, int s) {
			return n[kind][s];
		}

		/** One line per nature and plan: "pride speedrun 12 3.40". */
		public synchronized String save() {
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < Sins.N; k++) {
				for (int s = 0; s < N; s++) {
					if (n[k][s] > 0) sb.append(String.format(Locale.ROOT, "%s %s %d %.4f%n", Sins.NAMES[k], NAMES[s], n[k][s], sum[k][s] / n[k][s]));
				}
			}
			return sb.toString();
		}

		public synchronized void load(String text) {
			for (String line : text.split("\n")) {
				String[] p = line.trim().split(" ");
				if (p.length != 4) continue;
				int k = Sins.index(p[0]), s = index(p[1]);
				if (k < 0 || s < 0) continue;
				try {
					n[k][s] = Integer.parseInt(p[2]);
					sum[k][s] = Double.parseDouble(p[3]) * n[k][s];
				} catch (NumberFormatException ignored) {
				}
			}
		}

		/** For "what works?": the best plan so far for each nature. */
		public synchronized String summary() {
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < Sins.N; k++) {
				int best = -1;
				for (int s = 0; s < N; s++) if (n[k][s] >= 2 && (best < 0 || value(k, s) > value(k, best))) best = s;
				if (best >= 0) sb.append(sb.length() == 0 ? "" : "; ").append(Sins.ADJECTIVES[k]).append(": ").append(NAMES[best])
						.append(String.format(Locale.ROOT, " (%.1f a day)", value(k, best)));
			}
			return sb.length() == 0 ? "nothing yet" : sb.toString();
		}
	}
}
