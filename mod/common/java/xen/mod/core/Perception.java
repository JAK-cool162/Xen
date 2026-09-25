package xen.mod.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Xen perceives (a line-by-line port of xen/perception.py, so a brain trained in Python works here).
 *
 * <p>Within {@link #NEAR} blocks Xen knows everything around it. Beyond that it only knows what it sees:
 * rays inside a 90 degree field of view, up to 128 blocks (8 chunks), stopped by opaque blocks. What it has
 * seen goes into {@link Beliefs} with a confidence that fades.
 */
public final class Perception {
	public static final int NEAR = 6;
	public static final int SIDE = 2 * NEAR + 1;
	public static final int VIEW = 128;
	public static final double FOV = Math.toRadians(90);
	public static final int RAYS_H = 16, RAYS_V = 16, N_RAYS = RAYS_H * RAYS_V;
	public static final double EYE = 1.62;
	public static final double[] LOOK_PITCH = {-Math.PI / 2 + 0.01, -0.7, 0.0};   // pitch -1, 0, 1
	public static final double[][] JITTER = {{-0.25, -0.25}, {0.25, -0.25}, {-0.25, 0.25}, {0.25, 0.25}};
	public static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};   // north, east, south, west
	public static final double[] SAMPLES;
	public static final int CHANNELS = 5, N_CELLS = 5 * 5 * 6, SECTORS = 4;
	public static final int N_NEAR_RADAR = 15, N_FAR_RADAR = 18, N_VISION = SECTORS * SECTORS * 3, N_COVERAGE = 4, N_BODY = 13;
	public static final int OBS_DIM = N_CELLS * CHANNELS + N_NEAR_RADAR + N_FAR_RADAR + N_VISION + N_COVERAGE + N_BODY;

	static {
		List<Double> s = new ArrayList<>();
		for (int i = 1; i < 32; i++) s.add(i * 0.5);                 // 0.5 .. 15.5
		for (int t = 16; t <= VIEW; t++) s.add((double) t);          // 16 .. 128
		SAMPLES = s.stream().mapToDouble(Double::doubleValue).toArray();
	}

	private Perception() {}

	/** How Xen's own body feels. */
	public static final class Body {
		public float health = 20, hunger = 20, hurt;
		public boolean night, burning, inWater, inLava;
		public int pitch, blocks, food;
	}

	/** Everything the senses deliver in one moment. */
	public static final class Sight {
		public int[] near = new int[SIDE * SIDE * SIDE];      // [dx][dy][dz] flattened, full knowledge
		public int yaw;
		public Body body = new Body();
		public int[] position = new int[3];
		public double t;
		public List<int[]> nearMobs = new ArrayList<>();      // offsets within NEAR
		public double[] rayDist;                              // POSITIVE_INFINITY = nothing
		public int[] rayCat;                                  // -1 = nothing
		public int[][] rayHit;
		public List<int[]> farMobs = new ArrayList<>();       // absolute positions of mobs it sees

		public int near(int dx, int dy, int dz) {
			return near[((dx + NEAR) * SIDE + dy + NEAR) * SIDE + dz + NEAR];
		}
	}

	public static int[] forward(int yaw) {
		return DIRS[Math.floorMod(yaw, 4)];
	}

	public static int[] right(int yaw) {
		return DIRS[Math.floorMod(yaw + 1, 4)];
	}

	/** World (dx, dz) -> egocentric {lateral, ahead}. */
	public static double[] toEgo(int yaw, double dx, double dz) {
		int[] f = forward(yaw), r = right(yaw);
		return new double[] {dx * r[0] + dz * r[1], dx * f[0] + dz * f[1]};
	}

	/** Ray directions (x, y, z) for a facing, look pitch (-1/0/1) and eye-movement phase, bottom row first. */
	public static double[][] rayDirections(int yaw, int pitch, int phase) {
		int[] f = forward(yaw), r = right(yaw);
		double[] j = JITTER[Math.floorMod(phase, JITTER.length)];
		double[][] out = new double[N_RAYS][];
		int k = 0;
		for (int i = 0; i < RAYS_V; i++) {
			double v = -FOV / 2 + (i + 0.5 + j[1]) * FOV / RAYS_V;
			double up = clamp(LOOK_PITCH[pitch + 1] + v, -Math.PI / 2 + 1e-3, Math.PI / 2 - 1e-3);
			for (int jj = 0; jj < RAYS_H; jj++) {
				double h = -FOV / 2 + (jj + 0.5 + j[0]) * FOV / RAYS_H;
				double hx = Math.cos(h) * f[0] + Math.sin(h) * r[0], hz = Math.cos(h) * f[1] + Math.sin(h) * r[1];
				out[k++] = new double[] {Math.cos(up) * hx, Math.sin(up), Math.cos(up) * hz};
			}
		}
		return out;
	}

	/** Looks up block categories; -1 = unknown (unloaded). */
	public interface World {
		int category(int x, int y, int z);
	}

	/** Cast the view rays from the eye; fills sight.rayDist/rayCat/rayHit. */
	public static void castRays(World world, double[] eye, int yaw, int pitch, int phase, Sight sight) {
		double[][] dirs = rayDirections(yaw, pitch, phase);
		sight.rayDist = new double[N_RAYS];
		sight.rayCat = new int[N_RAYS];
		sight.rayHit = new int[N_RAYS][3];
		for (int r = 0; r < N_RAYS; r++) {
			sight.rayDist[r] = Double.POSITIVE_INFINITY;
			sight.rayCat[r] = -1;
			for (double t : SAMPLES) {
				int x = (int) Math.floor(eye[0] + dirs[r][0] * t);
				int y = (int) Math.floor(eye[1] + dirs[r][1] * t);
				int z = (int) Math.floor(eye[2] + dirs[r][2] * t);
				int c = world.category(x, y, z);
				if (c < 0) break;
				if (Blocks.OPAQUE[c]) {
					sight.rayDist[r] = t;
					sight.rayCat[r] = c;
					sight.rayHit[r] = new int[] {x, y, z};
					break;
				}
			}
		}
	}

	/** Is a point inside the field of view (ignoring walls)? */
	public static boolean inView(double[] eye, int yaw, int pitch, double[] target) {
		double dx = target[0] - eye[0], dy = target[1] - eye[1], dz = target[2] - eye[2];
		double[] e = toEgo(yaw, dx, dz);
		double flat = Math.hypot(e[0], e[1]);
		if (flat + Math.abs(dy) > VIEW || e[1] <= 0) return false;
		double half = FOV / 2;
		return Math.abs(Math.atan2(e[0], e[1])) <= half && Math.abs(Math.atan2(dy, flat) - LOOK_PITCH[pitch + 1]) <= half;
	}

	/** True if no opaque block lies between the eye and the target. */
	public static boolean lineOfSight(World world, double[] eye, double[] target) {
		double dx = target[0] - eye[0], dy = target[1] - eye[1], dz = target[2] - eye[2];
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		for (double s = 0.5; s < Math.max(length - 0.5, 0.5); s += 0.5) {
			int c = world.category((int) Math.floor(eye[0] + dx * s / length), (int) Math.floor(eye[1] + dy * s / length),
					(int) Math.floor(eye[2] + dz * s / length));
			if (c < 0 || Blocks.OPAQUE[c]) return false;
		}
		return true;
	}

	// ------------------------------------------------------------------------------------ beliefs

	/** What Xen has seen beyond its senses, with a confidence that fades. */
	public static final class Beliefs {
		public double halfLife = 2400, mobHalfLife = 60;
		public int capacity = 20_000;
		public int n;
		public int[][] pos = new int[1024][];
		public int[] cat = new int[1024];
		public double[] seen = new double[1024];
		private final Map<Long, Integer> index = new HashMap<>();
		public final List<double[]> mobs = new ArrayList<>();   // {x, y, z, timeSeen}

		public static long key(int x, int y, int z) {
			return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
		}

		public void see(int x, int y, int z, int c, double t) {
			Integer i = index.get(key(x, y, z));
			if (i == null) {
				if (n == cat.length) grow();
				i = n++;
				index.put(key(x, y, z), i);
				pos[i] = new int[] {x, y, z};
			}
			cat[i] = c;
			seen[i] = t;
		}

		private void grow() {
			int size = cat.length * 2;
			pos = java.util.Arrays.copyOf(pos, size);
			cat = java.util.Arrays.copyOf(cat, size);
			seen = java.util.Arrays.copyOf(seen, size);
		}

		public void seeMobs(List<int[]> positions, double t) {
			List<double[]> fresh = new ArrayList<>();
			for (int[] p : positions) fresh.add(new double[] {p[0], p[1], p[2], t});
			List<double[]> kept = new ArrayList<>();
			for (double[] m : mobs) {
				if (mobConfidence(m[3], t) <= 0.05) continue;
				boolean far = true;
				for (double[] q : fresh) {
					if (Math.abs(m[0] - q[0]) + Math.abs(m[1] - q[1]) + Math.abs(m[2] - q[2]) <= 3) far = false;
				}
				if (far) kept.add(m);
			}
			mobs.clear();
			mobs.addAll(fresh);
			mobs.addAll(kept);
		}

		public double mobConfidence(double seenAt, double t) {
			return Math.pow(0.5, (t - seenAt) / mobHalfLife);
		}

		public double confidence(int i, double t) {
			return Math.pow(0.5, (t - seen[i]) / halfLife);
		}

		/** Up close Xen knows the truth: fix beliefs about blocks within reach of its senses. */
		public void correct(Sight s) {
			for (int i = 0; i < n; i++) {
				int dx = pos[i][0] - s.position[0], dy = pos[i][1] - s.position[1], dz = pos[i][2] - s.position[2];
				if (Math.abs(dx) <= NEAR && Math.abs(dy) <= NEAR && Math.abs(dz) <= NEAR) {
					cat[i] = s.near(dx, dy, dz);
					seen[i] = s.t;
				}
			}
		}

		/** Drop the faintest memories when memory is full. */
		public void forget(double t) {
			if (n < capacity) return;
			Integer[] order = new Integer[n];
			for (int i = 0; i < n; i++) order[i] = i;
			java.util.Arrays.sort(order, (a, b) -> Double.compare(seen[b], seen[a]));
			int keep = capacity / 2;
			int[][] p2 = new int[cat.length][];
			int[] c2 = new int[cat.length];
			double[] s2 = new double[cat.length];
			index.clear();
			for (int k = 0; k < keep; k++) {
				int i = order[k];
				p2[k] = pos[i];
				c2[k] = cat[i];
				s2[k] = seen[i];
				index.put(key(pos[i][0], pos[i][1], pos[i][2]), k);
			}
			pos = p2;
			cat = c2;
			seen = s2;
			n = keep;
		}
	}

	/** One body's senses and memory of the world. */
	public static final class Senses {
		public Beliefs beliefs = new Beliefs();
		public Sight last;

		/**
		 * The nearest place of one of these kinds that it knows about: for sure within 6 blocks, or from what it saw
		 * (at least this sure). Returns {x, y, z, 1 if known for sure else 0, what it is}, or null when it knows of none.
		 */
		public int[] nearestKnown(int[] cats, double minConfidence, java.util.Set<Long> skip) {
			return nearestKnown(cats, minConfidence, skip, 0);
		}

		/** The same, with high-up places (more than a block above its feet) counting as climb times further away. */
		public int[] nearestKnown(int[] cats, double minConfidence, java.util.Set<Long> skip, double climb) {
			Sight s = last;
			if (s == null) return null;
			boolean[] want = new boolean[Blocks.COUNT];
			for (int c : cats) want[c] = true;
			int[] best = null;
			double bestScore = Double.MAX_VALUE;
			for (int dx = -NEAR; dx <= NEAR; dx++) {
				for (int dy = -NEAR; dy <= NEAR; dy++) {
					for (int dz = -NEAR; dz <= NEAR; dz++) {
						int x = s.position[0] + dx, y = s.position[1] + dy, z = s.position[2] + dz;
						if (!want[s.near(dx, dy, dz)] || skip.contains(Beliefs.key(x, y, z))) continue;
						double d = Math.sqrt(dx * dx + dy * dy + dz * dz) + climb * Math.max(0, dy - 1);
						if (d < bestScore) {
							bestScore = d;
							best = new int[] {x, y, z, 1, s.near(dx, dy, dz)};
						}
					}
				}
			}
			if (best != null) return best;
			for (int i = 0; i < beliefs.n; i++) {
				int[] p = beliefs.pos[i];
				if (!want[beliefs.cat[i]] || skip.contains(Beliefs.key(p[0], p[1], p[2]))) continue;
				double conf = beliefs.confidence(i, s.t);
				if (conf < minConfidence) continue;
				double score = (dist(p, s.position) + climb * Math.max(0, p[1] - s.position[1] - 1)) / conf;
				if (score < bestScore) {
					bestScore = score;
					best = new int[] {p[0], p[1], p[2], 0, beliefs.cat[i]};
				}
			}
			return best;
		}

		public float[] perceive(Sight s) {
			beliefs.correct(s);
			if (s.rayCat != null) {
				for (int r = 0; r < s.rayCat.length; r++) {
					if (s.rayCat[r] >= 0) beliefs.see(s.rayHit[r][0], s.rayHit[r][1], s.rayHit[r][2], s.rayCat[r], s.t);
				}
			}
			beliefs.seeMobs(s.farMobs, s.t);
			beliefs.forget(s.t);
			last = s;
			return encode(s, beliefs);
		}

		/** What Xen knows, in plain sentences (for talking): only its senses and beliefs. */
		public String describe() {
			Sight s = last;
			if (s == null) return "You haven't looked around yet.";
			int[] kinds = {Blocks.DIAMOND, Blocks.GOLD, Blocks.IRON, Blocks.COAL, Blocks.LAVA, Blocks.LOG, Blocks.WATER};
			String[] names = {"diamond ore", "gold ore", "iron ore", "coal ore", "lava", "a tree", "water"};
			List<String> facts = new ArrayList<>();
			for (int k = 0; k < kinds.length; k++) {
				int block = kinds[k];
				double best = Double.MAX_VALUE;
				for (int dx = -NEAR; dx <= NEAR; dx++) for (int dy = -NEAR; dy <= NEAR; dy++) for (int dz = -NEAR; dz <= NEAR; dz++) {
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (d <= NEAR && s.near(dx, dy, dz) == block) best = Math.min(best, d);
				}
				if (best < Double.MAX_VALUE) {
					facts.add(String.format("You know there is %s %.0f blocks from you.", names[k], best));
					continue;
				}
				int bi = -1;
				double bestScore = Double.MAX_VALUE, bestDist = 0, bestConf = 0;
				for (int i = 0; i < beliefs.n; i++) {
					double conf = beliefs.confidence(i, s.t);
					if (beliefs.cat[i] != block || conf <= 0.02) continue;
					double d = dist(beliefs.pos[i], s.position);
					double score = d / Math.max(conf, 1e-3);
					if (score < bestScore) {
						bestScore = score;
						bi = i;
						bestDist = d;
						bestConf = conf;
					}
				}
				if (bi >= 0) {
					facts.add(bestConf > 0.6 ? String.format("You saw %s about %.0f blocks away.", names[k], bestDist)
							: String.format("You think there was %s about %.0f blocks away, but you're not sure.", names[k], bestDist));
				}
			}
			if (!s.nearMobs.isEmpty()) facts.add("There are " + s.nearMobs.size() + " hostile mobs right next to you.");
			int far = 0;
			for (double[] m : beliefs.mobs) if (beliefs.mobConfidence(m[3], s.t) > 0.3) far++;
			if (far > 0) facts.add("You saw " + far + " hostile mobs in the distance.");
			return facts.isEmpty() ? "Nothing special is around you." : String.join(" ", facts);
		}
	}

	// ----------------------------------------------------------------------------------- encoding

	private static double dist(int[] a, int[] b) {
		double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Near radar: {found, lat/6, ahead/6, dy/6, extra}; weights == null means "nearest". */
	private static void radar(int yaw, List<float[]> offsets, float[] weights, float[] out, int at) {
		if (offsets.isEmpty()) return;
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		float[] dists = new float[offsets.size()];
		for (int i = 0; i < offsets.size(); i++) {
			float[] o = offsets.get(i);
			dists[i] = (float) Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
			double score = weights == null ? -dists[i] : weights[i] / (1.0f + dists[i]);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		float[] o = offsets.get(best);
		double[] e = toEgo(yaw, o[0], o[2]);
		out[at] = 1f;
		out[at + 1] = (float) (e[0] / NEAR);
		out[at + 2] = (float) (e[1] / NEAR);
		out[at + 3] = o[1] / NEAR;
		out[at + 4] = weights == null ? (float) (1.0 - dists[best] / (NEAR * 1.8)) : weights[best];
	}

	/** Far radar from beliefs: {found, lat/32, ahead/32, dy/32, 1 - dist/VIEW, confidence}. */
	private static void beliefRadar(int yaw, List<double[]> rel, List<Double> conf, List<Double> score, float[] out, int at) {
		if (rel.isEmpty()) return;
		int best = 0;
		for (int i = 1; i < score.size(); i++) if (score.get(i) > score.get(best)) best = i;
		double[] r = rel.get(best);
		double[] e = toEgo(yaw, r[0], r[2]);
		double d = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
		out[at] = 1f;
		out[at + 1] = (float) (e[0] / 32.0);
		out[at + 2] = (float) (e[1] / 32.0);
		out[at + 3] = (float) (r[1] / 32.0);
		out[at + 4] = (float) (1.0 - d / VIEW);
		out[at + 5] = (float) (double) conf.get(best);
	}

	public static float[] encode(Sight s, Beliefs beliefs) {
		float[] obs = new float[OBS_DIM];
		int yaw = Math.floorMod(s.yaw, 4);
		int[] f = forward(yaw), r = right(yaw);

		// Window around the body: 5 x 5 x 6 cells, 5 channels.
		boolean[] mobCell = new boolean[N_CELLS];
		for (int[] m : s.nearMobs) {
			double[] e = toEgo(yaw, m[0], m[2]);
			int lat = (int) e[0], ahead = (int) e[1];
			for (int part : new int[] {m[1], m[1] + 1}) {
				if (lat >= -2 && lat <= 2 && ahead >= -2 && ahead <= 2 && part >= -2 && part <= 3) {
					mobCell[((lat + 2) * 5 + (ahead + 2)) * 6 + (part + 2)] = true;
				}
			}
		}
		int k = 0;
		for (int lat = -2; lat <= 2; lat++) {
			for (int ahead = -2; ahead <= 2; ahead++) {
				int dx = lat * r[0] + ahead * f[0], dz = lat * r[1] + ahead * f[1];
				for (int dy = -2; dy <= 3; dy++) {
					int c = s.near(dx, dy, dz);
					obs[k * CHANNELS] = Blocks.SOLID[c] ? 1f : 0f;
					obs[k * CHANNELS + 1] = c == Blocks.LAVA ? 1f : 0f;
					obs[k * CHANNELS + 2] = c == Blocks.WATER ? 1f : 0f;
					obs[k * CHANNELS + 3] = Blocks.VALUE_NORM[c];
					obs[k * CHANNELS + 4] = mobCell[k] ? 1f : 0f;
					k++;
				}
			}
		}
		int at = N_CELLS * CHANNELS;

		// Up close: full knowledge (cube order: dx, dy, dz).
		List<float[]> treasure = new ArrayList<>(), lava = new ArrayList<>(), mobs = new ArrayList<>();
		List<Float> tw = new ArrayList<>();
		for (int dx = -NEAR; dx <= NEAR; dx++) for (int dy = -NEAR; dy <= NEAR; dy++) for (int dz = -NEAR; dz <= NEAR; dz++) {
			if (Math.sqrt(dx * dx + dy * dy + dz * dz) > NEAR) continue;
			int c = s.near(dx, dy, dz);
			if (Blocks.TREASURE[c]) {
				treasure.add(new float[] {dx, dy, dz});
				tw.add(Blocks.VALUE_NORM[c]);
			}
			if (c == Blocks.LAVA) lava.add(new float[] {dx, dy, dz});
		}
		for (int[] m : s.nearMobs) mobs.add(new float[] {m[0], m[1], m[2]});
		float[] w = new float[tw.size()];
		for (int i = 0; i < w.length; i++) w[i] = tw.get(i);
		radar(yaw, treasure, w, obs, at);
		radar(yaw, lava, null, obs, at + 5);
		radar(yaw, mobs, null, obs, at + 10);
		at += N_NEAR_RADAR;

		// Further away: beliefs, weighed by how sure it is.
		List<double[]> tRel = new ArrayList<>(), lRel = new ArrayList<>(), all = new ArrayList<>();
		List<Double> tConf = new ArrayList<>(), tScore = new ArrayList<>(), lConf = new ArrayList<>(), lScore = new ArrayList<>(), allConf = new ArrayList<>();
		for (int i = 0; i < beliefs.n; i++) {
			double conf = beliefs.confidence(i, s.t);
			if (conf <= 0.02) continue;
			double[] rel = {beliefs.pos[i][0] - s.position[0], beliefs.pos[i][1] - s.position[1], beliefs.pos[i][2] - s.position[2]};
			double d = Math.sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2]);
			all.add(rel);
			allConf.add(conf);
			if (d <= NEAR) continue;
			int c = beliefs.cat[i];
			if (Blocks.TREASURE[c]) {
				tRel.add(rel);
				tConf.add(conf);
				tScore.add(Blocks.VALUE_NORM[c] * conf / (1 + d / 8));
			}
			if (c == Blocks.LAVA) {
				lRel.add(rel);
				lConf.add(conf);
				lScore.add(conf / (1 + d));
			}
		}
		List<double[]> mRel = new ArrayList<>();
		List<Double> mConf = new ArrayList<>(), mScore = new ArrayList<>();
		for (double[] m : beliefs.mobs) {
			double[] rel = {m[0] - s.position[0], m[1] - s.position[1], m[2] - s.position[2]};
			double conf = beliefs.mobConfidence(m[3], s.t);
			mRel.add(rel);
			mConf.add(conf);
			mScore.add(conf / (1 + Math.sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2]) / 8));
		}
		beliefRadar(yaw, tRel, tConf, tScore, obs, at);
		beliefRadar(yaw, lRel, lConf, lScore, obs, at + 6);
		beliefRadar(yaw, mRel, mConf, mScore, obs, at + 12);
		at += N_FAR_RADAR;

		// What its eyes see right now, in a 4 x 4 grid over the field of view.
		if (s.rayDist != null && s.rayDist.length == N_RAYS) {
			int per = RAYS_H / SECTORS;
			for (int a = 0; a < SECTORS; a++) {
				for (int b = 0; b < SECTORS; b++) {
					double depth = 0, gold = 0, hot = 0;
					for (int i = a * per; i < (a + 1) * per; i++) {
						for (int j = b * per; j < (b + 1) * per; j++) {
							int ray = i * RAYS_H + j;
							depth += Math.min(s.rayDist[ray], VIEW) / VIEW;
							int c = s.rayCat[ray];
							if (c >= 0 && Blocks.TREASURE[c]) gold++;
							if (c == Blocks.LAVA) hot++;
						}
					}
					int cell = at + (a * SECTORS + b) * 3;
					obs[cell] = (float) (depth / (per * per));
					obs[cell + 1] = (float) (gold / (per * per));
					obs[cell + 2] = (float) (hot / (per * per));
				}
			}
		}
		at += N_VISION;

		// How well it knows each direction around it.
		double[] cover = new double[4];
		for (int i = 0; i < all.size(); i++) {
			double[] rel = all.get(i);
			double[] e = toEgo(yaw, rel[0], rel[2]);
			if (Math.hypot(e[0], e[1]) > 32) continue;
			int q = Math.abs(e[0]) <= Math.abs(e[1]) ? (e[1] > 0 ? 0 : 2) : (e[0] > 0 ? 1 : 3);
			cover[q] += allConf.get(i);
		}
		for (int q = 0; q < 4; q++) obs[at + q] = (float) Math.min(1.0, cover[q] / 100.0);
		at += N_COVERAGE;

		boolean skyBlocked = false;
		for (int dy = 2; dy <= NEAR; dy++) skyBlocked |= Blocks.SOLID[s.near(0, dy, 0)];
		Body b = s.body;
		float[] stats = {b.health / 20f, b.hunger / 20f, b.night ? 1 : 0, b.burning ? 1 : 0, (float) clamp(b.hurt, 0, 1),
				b.pitch == -1 ? 1 : 0, b.pitch == 0 ? 1 : 0, b.pitch == 1 ? 1 : 0, Math.min(b.blocks, 32) / 32f,
				Math.min(b.food, 5) / 5f, b.inWater ? 1 : 0, b.inLava ? 1 : 0, skyBlocked ? 1 : 0};
		System.arraycopy(stats, 0, obs, at, stats.length);
		for (int i = 0; i < OBS_DIM; i++) obs[i] = (float) clamp(obs[i], -1, 1);
		return obs;
	}
}
