package xen.mod.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Xen 2.0's mind: what to do next. Not which key to press (its body does that, and its old brain still helps with
 * fear), but which of the things a player does: get wood, go mining, build a house, eat, sleep, help a friend, guard
 * the village, fight, run, explore, trade...
 *
 * It is built like the old brain, one level up: a critic that learns what each choice brings (the striatum, TD
 * learning), a second one that learns what each choice costs in harm (the amygdala: its fear), and a world model
 * that imagines what a choice would lead to, so when it's unsure it thinks a couple of steps ahead. What it sees is
 * only what the Xen could know (its body, its bag, what it has built, who is near, its own motives and temper).
 *
 * It is trained from nothing in {@link SimLife} (a fast model of a survival life: days and nights, hunger, monsters,
 * tools, ores, homes, friends), and it keeps learning in the game from how its choices turn out. Minions keep the
 * old, small brain.
 */
public final class Mind {
	// ------------------------------------------------------------------------------ choices
	public static final String[] OPTIONS = {"rest", "wood", "stone", "craft", "food", "eat", "shelter", "sleep", "house", "farm", "mine", "smelt",
			"store", "explore", "trade", "follow", "help", "guard", "fight", "flee", "adventure", "enchant"};
	public static final int REST = 0, WOOD = 1, STONE = 2, CRAFT = 3, FOOD = 4, EAT = 5, SHELTER = 6, SLEEP = 7, HOUSE = 8, FARM = 9, MINE = 10,
			SMELT = 11, STORE = 12, EXPLORE = 13, TRADE = 14, FOLLOW = 15, HELP = 16, GUARD = 17, FIGHT = 18, FLEE = 19, ADVENTURE = 20,
			ENCHANT = 21;
	public static final int N = OPTIONS.length;
	/** In words, for its thoughts. */
	public static final String[] SAYS = {"take a breather", "get some wood", "get some stone", "craft better gear", "find food", "eat",
			"make a shelter for the night", "sleep in my bed", "build a house", "make a farm", "go mining", "smelt what I've got",
			"put my things away", "explore", "trade", "stay with my friend", "help out", "guard the village", "fight", "get away",
			"go on an adventure", "enchant my gear"};
	/** The choices that take it away from where it is (a friend it follows is left behind). */
	public static boolean far(int o) {
		return o == EXPLORE || o == MINE || o == HOUSE || o == ADVENTURE || o == TRADE || o == FARM;
	}

	// ----------------------------------------------------------------------------- what it knows
	public static final String[] FEATURES = {"health", "food", "night", "dusk", "danger", "hostiles", "enemy", "underground", "in water",
			"logs", "planks", "cobblestone", "coal", "raw iron", "iron", "diamonds", "food items", "torches", "emeralds", "gold",
			"pickaxe", "axe", "sword", "armor", "hoe", "bucket", "bed", "home", "farm", "chest", "mine", "enchanted",
			"trees known", "villager near", "bag full", "friend near", "asked to follow", "friend needs", "tribe in danger", "tribe",
			"trust", "kindness", "loyalty", "power", "money", "aggressive", "passive", "bravery", "curiosity", "diligence",
			"xp", "obsidian", "lapis", "raw food", "furnace", "dragon beaten", "sheltered", "has friend"};
	public static final int HEALTH = 0, FOODLVL = 1, NIGHT = 2, DUSK = 3, DANGER = 4, HOSTILES = 5, ENEMY = 6, UNDERGROUND = 7, INWATER = 8,
			LOGS = 9, PLANKS = 10, COBBLE = 11, COAL = 12, RAWIRON = 13, IRON = 14, DIAMOND = 15, FOODITEMS = 16, TORCHES = 17, EMERALD = 18,
			GOLD = 19, PICK = 20, AXE = 21, SWORD = 22, ARMOR = 23, HOE = 24, BUCKET = 25, BED = 26, HOME = 27, FARMED = 28, CHEST = 29,
			MINED = 30, ENCHANTED = 31, TREES = 32, VILLAGER = 33, BAGFULL = 34, FRIENDNEAR = 35, ASKEDFOLLOW = 36, FRIENDNEEDS = 37,
			TRIBEDANGER = 38, TRIBE = 39, TRUST = 40, KINDNESS = 41, LOYALTY = 42, POWER = 43, MONEY = 44, AGGRESSIVE = 45, PASSIVE = 46,
			BRAVERY = 47, CURIOSITY = 48, DILIGENCE = 49, XP = 50, OBSIDIAN = 51, LAPIS = 52, RAWFOOD = 53, FURNACE = 54, DRAGON = 55,
			SHELTERED = 56, HASFRIEND = 57;
	public static final int F = FEATURES.length;
	/** How a count becomes a feature: count / scale, at most 1. */
	public static float count(int n, float scale) {
		return Math.min(1f, n / scale);
	}

	// --------------------------------------------------------------------------------- reward
	private static float v(float x) {
		return (float) Math.log1p(4 * Math.max(0, x));
	}

	/**
	 * What a choice brought, from what it knew before and after (the same in the simulation and the game), weighed
	 * by what moves this Xen (its kindness, loyalty, power, money, its temper and curiosity). minutes: how long it took.
	 */
	public static float reward(float[] b, float[] a, int option, float minutes) {
		float kind = b[KINDNESS], loyal = b[LOYALTY], power = b[POWER], money = b[MONEY];
		float r = 0;
		r += 1.2f * (v(a[LOGS]) - v(b[LOGS])) + 0.3f * (v(a[PLANKS]) - v(b[PLANKS])) + 0.8f * (v(a[COBBLE]) - v(b[COBBLE]));
		r += 1.0f * (v(a[COAL]) - v(b[COAL])) + 1.5f * (v(a[RAWIRON]) - v(b[RAWIRON])) + 2.0f * (v(a[IRON]) - v(b[IRON]));
		r += (3f + 2f * money) * (v(a[DIAMOND]) - v(b[DIAMOND])) + (0.5f + 2f * money) * (v(a[EMERALD]) - v(b[EMERALD]));
		r += (0.3f + money) * (v(a[GOLD]) - v(b[GOLD])) + 0.8f * (v(a[FOODITEMS]) - v(b[FOODITEMS])) + 0.4f * (v(a[RAWFOOD]) - v(b[RAWFOOD]));
		r += 0.3f * (v(a[TORCHES]) - v(b[TORCHES])) + 0.4f * (v(a[OBSIDIAN]) - v(b[OBSIDIAN])) + 0.3f * (v(a[LAPIS]) - v(b[LAPIS]));
		r += 10f * (a[PICK] - b[PICK]) + 4f * (a[AXE] - b[AXE]) + (4f + 8f * power) * (a[SWORD] - b[SWORD]) + (6f + 8f * power) * (a[ARMOR] - b[ARMOR]);
		r += 0.5f * (a[HOE] - b[HOE]) + 0.5f * (a[BUCKET] - b[BUCKET]) + 1f * (a[BED] - b[BED]) + 0.5f * (a[FURNACE] - b[FURNACE]);
		r += 6f * (a[HOME] - b[HOME]) + 3f * (a[FARMED] - b[FARMED]) + 1f * (a[CHEST] - b[CHEST]) + 1.5f * (a[MINED] - b[MINED]);
		r += 3f * (a[ENCHANTED] - b[ENCHANTED]) + 20f * (a[DRAGON] - b[DRAGON]);
		if (b[FOODLVL] < 0.8f) r += 2f * Math.max(0, a[FOODLVL] - b[FOODLVL]);   // eating when hungry
		if (a[FOODLVL] < 0.3f) r -= 0.3f * minutes;                            // hungry: it feels it
		if (b[NIGHT] > 0.5f) r += 0.2f * minutes * (a[SHELTERED] - 0.5f);        // a safe night feels good; out in it, not
		if (b[ASKEDFOLLOW] > 0.5f && b[HASFRIEND] > 0.5f) r += loyal * (a[FRIENDNEAR] > 0.5f ? 0.3f * minutes : -1.5f);
		if (option == HELP && b[FRIENDNEEDS] > 0.5f && a[FRIENDNEEDS] < 0.5f) r += 0.5f + 2.5f * kind;
		if (option == GUARD && b[TRIBEDANGER] > 0.5f && a[TRIBEDANGER] < 0.5f) r += 0.5f + 1.5f * (loyal + kind) / 2;
		if (option == FIGHT && (b[ENEMY] > 0.5f || b[DANGER] > 0.3f) && a[ENEMY] < 0.5f && a[DANGER] < 0.3f) {
			r += 0.3f + 2f * power + (b[AGGRESSIVE] > 0.5f ? 1f : 0) - (b[PASSIVE] > 0.5f ? 1f : 0);
		}
		if (option == EXPLORE) r += 0.3f * b[CURIOSITY] * minutes / 1.5f;
		if (option == REST && b[NIGHT] < 0.5f) r -= 0.05f * minutes * (0.5f + b[DILIGENCE]);   // idle in daylight: a waste
		r -= 0.02f * minutes;
		return r;
	}

	/** What it cost: health lost (a full bar is 5), dying on top. */
	public static float harm(float[] b, float[] a, boolean died) {
		return Math.max(0, b[HEALTH] - a[HEALTH]) * 5f + (died ? 5f : 0f);
	}

	// ---------------------------------------------------------------------------------- nets
	public final int[] hidden;
	public final Critic striatum, amygdala;
	public final WorldModel world;
	public final float gamma = 0.97f, fearGamma = 0.8f;
	public long updates, choices;
	/** Think ahead with the world model when unsure (off while it trains, for speed). */
	public boolean think = true;

	public Mind(int[] hidden, float lr, long seed) {
		this.hidden = hidden.clone();
		striatum = new Critic(F, N, hidden, lr, gamma, 0.01f, seed);
		amygdala = new Critic(F, N, hidden, lr, fearGamma, 0.01f, seed + 1);
		world = new WorldModel(F, N, hidden, lr, seed + 2);
	}

	/** Xen 2.0's size. */
	public static Mind standard(long seed) {
		return new Mind(new int[] {256, 256}, 3e-4f, seed);
	}

	/** One choice and why. */
	public static final class Choice {
		public int option;
		public float[] value, fear;
		public String how = "";
	}

	/**
	 * Choose. allowed: what it can do right now (can't sleep with no bed, can't fight nobody); caution: how much fear
	 * weighs (timid 1.6, brave 0.4); explore: the chance it tries something else (curiosity).
	 */
	public Choice choose(float[] f, boolean[] allowed, float caution, float explore, Random r) {
		return choose(f, allowed, caution, explore, r, null);
	}

	/** The same, with a lean toward some choices (its job, its village's rules): bias is added to what each is worth. */
	public Choice choose(float[] f, boolean[] allowed, float caution, float explore, Random r, float[] bias) {
		Choice c = new Choice();
		float[] q = striatum.values(f), h = amygdala.values(f);
		float[] util = new float[N];
		int best = -1, second = -1;
		for (int i = 0; i < N; i++) {
			util[i] = allowed[i] ? q[i] - caution * h[i] + (bias == null ? 0 : bias[i]) : Float.NEGATIVE_INFINITY;
			if (!allowed[i]) continue;
			if (best < 0 || util[i] > util[best]) {
				second = best;
				best = i;
			} else if (second < 0 || util[i] > util[second]) {
				second = i;
			}
		}
		c.value = q;
		c.fear = h;
		choices++;
		if (best < 0) {
			c.option = REST;
			c.how = "nothing I can do";
			return c;
		}
		if (r.nextFloat() < explore) {
			int n = 0;
			for (boolean a : allowed) if (a) n++;
			int k = r.nextInt(n);
			for (int i = 0; i < N; i++) if (allowed[i] && k-- == 0) c.option = i;
			c.how = "let me try something different";
			return c;
		}
		c.option = best;
		boolean unsure = second >= 0 && util[best] - util[second] < 0.25f, afraid = h[best] > 0.5f;
		if ((unsure || afraid) && think && world.updates > 1000) {                    // not sure: it imagines where each would lead
			int[] top = top(util, 3);
			double bestScore = Double.NEGATIVE_INFINITY;
			StringBuilder sb = new StringBuilder("thinking it through: ");
			for (int a : top) {
				if (a < 0) continue;
				float[] im = world.imagine(f, a);
				float[] next = java.util.Arrays.copyOf(im, F);
				float[] q2 = striatum.values(next), h2 = amygdala.values(next);
				float after = Float.NEGATIVE_INFINITY;
				for (int i = 0; i < N; i++) if (allowed[i]) after = Math.max(after, q2[i] - caution * h2[i]);
				double score = im[F] - caution * im[F + 1] + gamma * (1 - im[F + 2]) * after;
				sb.append(OPTIONS[a]).append(String.format(java.util.Locale.ROOT, " %+.1f ", score));
				if (score > bestScore) {
					bestScore = score;
					c.option = a;
				}
			}
			c.how = sb.toString().trim();
		} else {
			c.how = String.format(java.util.Locale.ROOT, "%s looks best (%+.1f)", OPTIONS[best], util[best]);
		}
		return c;
	}

	private static int[] top(float[] util, int k) {
		int[] out = new int[k];
		java.util.Arrays.fill(out, -1);
		for (int i = 0; i < util.length; i++) {
			if (util[i] == Float.NEGATIVE_INFINITY) continue;
			for (int j = 0; j < k; j++) {
				if (out[j] < 0 || util[i] > util[out[j]]) {
					System.arraycopy(out, j, out, j + 1, k - j - 1);
					out[j] = i;
					break;
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------- memory
	private final int capacity;
	private float[][] obs, next;
	private int[] act;
	private float[] rew, hrm, done, mins;
	private long[] nextAllowed;
	private int size, head;

	{
		capacity = 100_000;
	}

	private void ensure() {
		if (obs != null) return;
		obs = new float[capacity][];
		next = new float[capacity][];
		act = new int[capacity];
		rew = new float[capacity];
		hrm = new float[capacity];
		done = new float[capacity];
		mins = new float[capacity];
		nextAllowed = new long[capacity];
	}

	public static long bits(boolean[] allowed) {
		long b = 0;
		for (int i = 0; i < allowed.length; i++) if (allowed[i]) b |= 1L << i;
		return b;
	}

	/** One choice and how it turned out. */
	public synchronized void remember(float[] before, int option, float reward, float harm, float[] after, boolean died, float minutes, long allowedAfter) {
		ensure();
		obs[head] = before;
		next[head] = after;
		act[head] = option;
		rew[head] = reward;
		hrm[head] = harm;
		done[head] = died ? 1 : 0;
		mins[head] = Math.max(0.05f, minutes);
		nextAllowed[head] = allowedAfter == 0 ? -1L : allowedAfter;
		head = (head + 1) % capacity;
		size = Math.min(size + 1, capacity);
	}

	public int remembered() {
		return size;
	}

	/** Learn from a batch of what it remembers (double Q: the net picks the next choice, its target rates it). */
	public void learn(int batch, Random r) {
		float[][] o, nx;
		int[] a, na;
		float[] rw, hm, dn, st;
		synchronized (this) {
			if (size < batch * 4) return;
			o = new float[batch][];
			nx = new float[batch][];
			a = new int[batch];
			rw = new float[batch];
			hm = new float[batch];
			dn = new float[batch];
			st = new float[batch];
			long[] al = new long[batch];
			for (int i = 0; i < batch; i++) {
				int k = r.nextInt(size);
				o[i] = obs[k];
				nx[i] = next[k];
				a[i] = act[k];
				rw[i] = rew[k];
				hm[i] = hrm[k];
				dn[i] = done[k];
				st[i] = mins[k];
				al[i] = nextAllowed[k];
			}
			na = new int[batch];
			float[][] qn = striatum.values(nx);
			for (int i = 0; i < batch; i++) {
				int bestA = 0;
				float bq = Float.NEGATIVE_INFINITY;
				for (int j = 0; j < N; j++) {
					if ((al[i] & (1L << j)) == 0) continue;
					if (qn[i][j] > bq) {
						bq = qn[i][j];
						bestA = j;
					}
				}
				na[i] = bestA;
			}
		}
		// the three learn at once (each has its own net): a third of the time
		var fear = java.util.concurrent.CompletableFuture.runAsync(() -> amygdala.learn(o, a, hm, nx, dn, na, st));
		var imagine = java.util.concurrent.CompletableFuture.runAsync(() -> world.learn(o, a, rw, hm, nx, dn));
		striatum.learn(o, a, rw, nx, dn, na, st);
		fear.join();
		imagine.join();
		updates++;
	}

	// ---------------------------------------------------------------------------------- file
	private static final int MAGIC = 0x58454e32;                               // "XEN2"

	public void save(DataOutputStream out) throws IOException {
		out.writeInt(MAGIC);
		out.writeInt(F);
		out.writeInt(N);
		out.writeInt(hidden.length);
		for (int h : hidden) out.writeInt(h);
		out.writeLong(updates);
		out.writeLong(world.updates);
		for (Mlp m : new Mlp[] {striatum.net, striatum.target, amygdala.net, amygdala.target, world.net}) {
			out.writeInt(m.flat.length);
			for (float x : m.flat) out.writeFloat(x);
		}
	}

	/** A saved mind, or null if the file is from another version (different features or choices). */
	public static Mind load(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC || in.readInt() != F || in.readInt() != N) return null;
		int[] hidden = new int[in.readInt()];
		for (int i = 0; i < hidden.length; i++) hidden[i] = in.readInt();
		Mind m = new Mind(hidden, 3e-4f, 1);
		m.updates = in.readLong();
		m.world.updates = in.readLong();
		for (Mlp net : new Mlp[] {m.striatum.net, m.striatum.target, m.amygdala.net, m.amygdala.target, m.world.net}) {
			int n = in.readInt();
			if (n != net.flat.length) return null;
			for (int i = 0; i < n; i++) net.flat[i] = in.readFloat();
		}
		return m;
	}
}
