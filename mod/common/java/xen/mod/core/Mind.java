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
			"xp", "obsidian", "lapis", "raw food", "furnace", "dragon beaten", "sheltered", "has friend",
			// Xen 6.0: its sins (fixed at birth) and its own plan (one of Strategy.N)
			"pride", "greed", "lust", "envy", "gluttony", "wrath", "sloth",
			"plan speedrun", "plan builder", "plan settler", "plan miner", "plan explorer", "plan trader", "plan warrior", "plan survivor"};
	public static final int HEALTH = 0, FOODLVL = 1, NIGHT = 2, DUSK = 3, DANGER = 4, HOSTILES = 5, ENEMY = 6, UNDERGROUND = 7, INWATER = 8,
			LOGS = 9, PLANKS = 10, COBBLE = 11, COAL = 12, RAWIRON = 13, IRON = 14, DIAMOND = 15, FOODITEMS = 16, TORCHES = 17, EMERALD = 18,
			GOLD = 19, PICK = 20, AXE = 21, SWORD = 22, ARMOR = 23, HOE = 24, BUCKET = 25, BED = 26, HOME = 27, FARMED = 28, CHEST = 29,
			MINED = 30, ENCHANTED = 31, TREES = 32, VILLAGER = 33, BAGFULL = 34, FRIENDNEAR = 35, ASKEDFOLLOW = 36, FRIENDNEEDS = 37,
			TRIBEDANGER = 38, TRIBE = 39, TRUST = 40, KINDNESS = 41, LOYALTY = 42, POWER = 43, MONEY = 44, AGGRESSIVE = 45, PASSIVE = 46,
			BRAVERY = 47, CURIOSITY = 48, DILIGENCE = 49, XP = 50, OBSIDIAN = 51, LAPIS = 52, RAWFOOD = 53, FURNACE = 54, DRAGON = 55,
			SHELTERED = 56, HASFRIEND = 57, SIN0 = 58, PLAN0 = SIN0 + Sins.N;
	public static final int F = FEATURES.length;
	/** The inputs Xen 5.2 had (a 5.2 mind is carried over: the new inputs start with no say, then it learns them). */
	static final int F52 = 58;
	/** Its version, for the log and "who are you". */
	public static final String VERSION = "Xen 2.0 beta 21 (mind 6, Ex1 v3, words)";
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
		r += sins(b, a, option, minutes) + plan(b, a, option, minutes);
		return r;
	}

	/**
	 * How its nature shapes what feels good (Xen 6.0): 1 (bonuses for the progress its sins and plan care about), 0
	 * (none: Xen 5.2's rewards). Only bonuses for getting somewhere, never a cost per minute: a cost per minute makes
	 * every long job (mining, a trip) look bad, and it learned to rest instead (in SimLife: iron in 47% of lives, not 89%).
	 */
	public static int shaping = 1;

	/** Xen 6.0: what its sins make of it (the same ore feels better to a greedy Xen, the same armor to a proud one). */
	private static float sins(float[] b, float[] a, int option, float minutes) {
		if (shaping == 0) return 0;
		float pride = b[SIN0 + Sins.PRIDE], greed = b[SIN0 + Sins.GREED], lust = b[SIN0 + Sins.LUST], envy = b[SIN0 + Sins.ENVY];
		float glut = b[SIN0 + Sins.GLUTTONY], wrath = b[SIN0 + Sins.WRATH];
		float r = 0;
		r += 0.8f * greed * (2f * (v(a[DIAMOND]) - v(b[DIAMOND])) + v(a[EMERALD]) - v(b[EMERALD]) + v(a[GOLD]) - v(b[GOLD]) + v(a[IRON]) - v(b[IRON])
				+ 0.3f * (v(a[COAL]) - v(b[COAL])));
		if (option == HELP) r -= 0.5f * greed;                                  // (giving hurts a greedy one, a little)
		r += pride * (3f * (a[ARMOR] - b[ARMOR]) + 2f * (a[SWORD] - b[SWORD]) + 2f * (a[HOME] - b[HOME]) + 2f * (a[ENCHANTED] - b[ENCHANTED])
				+ 10f * (a[DRAGON] - b[DRAGON]));
		if (option == FLEE && b[HEALTH] > 0.5f) r -= 0.5f * pride;              // (running while it could still fight hurts its pride)
		boolean won = (b[ENEMY] > 0.5f || b[DANGER] > 0.3f) && a[ENEMY] < 0.5f && a[DANGER] < 0.3f;
		if (option == FIGHT && won) r += wrath;
		r += envy * (2f * (a[ARMOR] - b[ARMOR]) + 2f * (a[PICK] - b[PICK]) + 1.5f * (a[SWORD] - b[SWORD]));
		r += glut * (v(a[FOODITEMS]) - v(b[FOODITEMS]) + 2f * (a[FARMED] - b[FARMED]));
		r += lust * (v(a[GOLD]) - v(b[GOLD]) + v(a[EMERALD]) - v(b[EMERALD]));
		return r;
	}

	/** Its plan: what counts as getting on (a speedrunner and a settler want different things from the same day). */
	public static int planOf(float[] f) {
		for (int i = 0; i < Strategy.N; i++) if (f[PLAN0 + i] > 0.5f) return i;
		return -1;
	}

	private static float plan(float[] b, float[] a, int option, float minutes) {
		if (shaping == 0) return 0;
		boolean won = (b[ENEMY] > 0.5f || b[DANGER] > 0.3f) && a[ENEMY] < 0.5f && a[DANGER] < 0.3f;
		return switch (planOf(b)) {                                          // (what counts as getting on, for each plan: bonuses only)
			case Strategy.SPEEDRUN -> 4f * (a[PICK] - b[PICK]) + 3f * (a[SWORD] - b[SWORD]) + 2f * (a[ARMOR] - b[ARMOR]) + 15f * (a[DRAGON] - b[DRAGON]);
			case Strategy.BUILDER -> 5f * (a[HOME] - b[HOME]) + 0.3f * (v(a[PLANKS]) - v(b[PLANKS]) + v(a[COBBLE]) - v(b[COBBLE]));
			case Strategy.SETTLER -> 4f * (a[FARMED] - b[FARMED]) + 2f * (a[HOME] - b[HOME]) + v(a[FOODITEMS]) - v(b[FOODITEMS]) + a[BED] - b[BED];
			case Strategy.MINER -> 0.8f * (v(a[RAWIRON]) - v(b[RAWIRON]) + v(a[COAL]) - v(b[COAL]) + 2f * (v(a[DIAMOND]) - v(b[DIAMOND]))) + a[MINED] - b[MINED];
			case Strategy.EXPLORER -> (option == EXPLORE ? 0.1f * minutes : 0) + a[VILLAGER] - b[VILLAGER];
			case Strategy.TRADER -> 1.5f * (v(a[EMERALD]) - v(b[EMERALD])) + (option == TRADE ? 0.3f : 0);
			case Strategy.WARRIOR -> 3f * (a[SWORD] - b[SWORD]) + 3f * (a[ARMOR] - b[ARMOR]) + (option == FIGHT && won ? 0.5f : 0);
			case Strategy.SURVIVOR -> 3f * (a[ARMOR] - b[ARMOR]) + 1.5f * (a[BED] - b[BED]) + 2f * (a[HOME] - b[HOME]);
			default -> 0f;
		};
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
	/** Carried over from an older version (Xen 5.2): the one that ships is newer. */
	public boolean carried;
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

	/** It starts learning once it remembers this many choices (in the game: enough to learn from, not the last few). */
	public int minMemory;

	/**
	 * How big its values are on some ordinary moments (see {@link SimLife#probe}): the mean size of what it thinks each
	 * choice is worth. A mind whose values run away (they grow and grow as it learns) is broken; this finds it.
	 */
	public float scale(float[][] probe) {
		double sum = 0;
		int n = 0;
		for (float[] q : striatum.values(probe)) for (float x : q) {
			sum += Math.abs(x);
			n++;
		}
		return n == 0 ? 0 : (float) (sum / n);
	}

	/** Learn from a batch of what it remembers (double Q: the net picks the next choice, its target rates it). */
	public void learn(int batch, Random r) {
		float[][] o, nx;
		int[] a, na;
		float[] rw, hm, dn, st;
		synchronized (this) {
			if (size < Math.max(batch * 4, minMemory)) return;
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

	/**
	 * A saved mind, or null if the file is from another version (different choices, or inputs it can't carry over).
	 * A Xen 5.2 mind (the same choices, the first 58 inputs) becomes a Xen 6.0 one: every weight it learned is kept, the
	 * new inputs (sins, plan) start with no say at all, so it chooses just as it did until it learns what they mean.
	 */
	public static Mind load(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) return null;
		int oldF = in.readInt();
		if (in.readInt() != N || oldF > F || oldF != F && oldF != F52) return null;
		int[] hidden = new int[in.readInt()];
		for (int i = 0; i < hidden.length; i++) hidden[i] = in.readInt();
		Mind m = new Mind(hidden, 3e-4f, 1);
		m.carried = oldF != F;
		m.updates = in.readLong();
		m.world.updates = in.readLong();
		Mlp[] nets = {m.striatum.net, m.striatum.target, m.amygdala.net, m.amygdala.target, m.world.net};
		for (int k = 0; k < nets.length; k++) {
			Mlp net = nets[k];
			float[] old = new float[in.readInt()];
			for (int i = 0; i < old.length; i++) old[i] = in.readFloat();
			float[] flat = oldF == F ? old : k < 4 ? widenCritic(old, oldF, hidden[0]) : widenWorld(old, oldF, hidden);
			if (flat == null || flat.length != net.flat.length) return null;
			System.arraycopy(flat, 0, net.flat, 0, flat.length);
		}
		return m;
	}

	/** A critic from fewer inputs: the first layer gets rows for the new inputs (zeros); the rest is the same. */
	static float[] widenCritic(float[] old, int oldF, int h0) {
		float[] out = new float[old.length + (F - oldF) * h0];
		System.arraycopy(old, 0, out, 0, oldF * h0);
		System.arraycopy(old, oldF * h0, out, F * h0, old.length - oldF * h0);
		return out;
	}

	/**
	 * The world model from fewer inputs: its input is [inputs, choice], its output [change in each input, reward, harm,
	 * end]; the new inputs get zero rows in, zero columns out (it predicts they don't change, which is right: they're
	 * its nature and its plan).
	 */
	static float[] widenWorld(float[] old, int oldF, int[] hidden) {
		int h0 = hidden[0], hl = hidden[hidden.length - 1], oldIn = oldF + N, oldOut = oldF + 3, newOut = F + 3;
		int layer0 = oldIn * h0 + h0, middle = 0;
		for (int i = 0; i + 1 < hidden.length; i++) middle += hidden[i] * hidden[i + 1] + hidden[i + 1];
		if (old.length != layer0 + middle + hl * oldOut + oldOut) return null;
		float[] out = new float[(F + N) * h0 + h0 + middle + hl * newOut + newOut];
		System.arraycopy(old, 0, out, 0, oldF * h0);                                  // input rows: its old inputs
		System.arraycopy(old, oldF * h0, out, F * h0, N * h0);                        // the choice rows, after the new inputs
		System.arraycopy(old, oldIn * h0, out, (F + N) * h0, h0 + middle);            // first bias and the middle layers
		int oldW = layer0 + middle, newW = (F + N) * h0 + h0 + middle;
		for (int i = 0; i < hl; i++) {
			for (int o = 0; o < oldOut; o++) out[newW + i * newOut + (o < oldF ? o : F + o - oldF)] = old[oldW + i * oldOut + o];
		}
		int oldB = oldW + hl * oldOut, newB = newW + hl * newOut;
		for (int o = 0; o < oldOut; o++) out[newB + (o < oldF ? o : F + o - oldF)] = old[oldB + o];
		return out;
	}
}
