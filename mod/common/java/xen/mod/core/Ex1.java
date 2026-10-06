package xen.mod.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Xen Ex1: the first Xen brain trained on a person's recorded play (xen/ex1/train.py; it ships as
 * assets/xen/ex1.json). 119 senses (the same ones the recorder wrote, see Ex1Senses) go through two layers of 64 to
 * four heads: the keys a player would press now, how it would turn in the next quarter second, where it would look,
 * and danger (how likely it is to get hurt in the next two seconds).
 *
 * Its danger head keeps learning in the game, from every Xen's own hurts (they share one Ex1): each hurt teaches it
 * that what the Xen sensed in the seconds before was dangerous, and now and then a calm moment teaches it what's safe.
 * What it learned is saved with the world (xen/ex1-learned.json). Nothing about lava, fire or monsters is written into
 * it: it learns what hurts.
 */
public final class Ex1 {
	public static final int N = 119;
	public static final String[] KEYS = {"forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use"};
	public static final int FORWARD = 0, BACK = 1, LEFT = 2, RIGHT = 3, JUMP = 4, SNEAK = 5, SPRINT = 6, ATTACK = 7, USE = 8;
	public static final int AIR = 0, SOLID = 1, WATER = 2, LAVA = 3, SMALL = 4, OTHER = 5;

	final float[] mean, std;
	final float[][] w1, w2, wKeys, wYaw, wPitch, wDanger;
	final float[] b1, b2, bKeys, bYaw, bPitch, bDanger;
	public final int hidden;
	/** How many hurts and calm moments it has learned from in the game. */
	public long learnedHurts, learnedCalm;
	public final JsonObject report;

	/** What Ex1 makes of a moment. */
	public static final class Out {
		public final float[] keys = new float[KEYS.length];
		public final float[] yaw = new float[5];
		public float pitch, danger;
		final float[] h2;

		Out(int hidden) {
			h2 = new float[hidden];
		}

		/** A key it would press (more likely than not). */
		public boolean presses(int key) {
			return keys[key] > 0.5f;
		}

		/** Its pitch, in degrees (down is +). */
		public float pitchDegrees() {
			return pitch * 90f;
		}
	}

	private Ex1(JsonObject m) {
		hidden = m.get("hidden").getAsInt();
		mean = vec(m.getAsJsonArray("mean"));
		std = vec(m.getAsJsonArray("std"));
		w1 = mat(m, "l1");
		b1 = bias(m, "l1");
		w2 = mat(m, "l2");
		b2 = bias(m, "l2");
		wKeys = mat(m, "keys");
		bKeys = bias(m, "keys");
		wYaw = mat(m, "yaw");
		bYaw = bias(m, "yaw");
		wPitch = mat(m, "pitch");
		bPitch = bias(m, "pitch");
		wDanger = mat(m, "danger");
		bDanger = bias(m, "danger");
		report = m.has("report") ? m.getAsJsonObject("report") : new JsonObject();
		if (mean.length != N || w1.length != N) throw new IllegalArgumentException("Ex1 made for " + mean.length + " senses, not " + N);
	}

	public static Ex1 read(Reader in) {
		return new Ex1(new Gson().fromJson(in, JsonObject.class));
	}

	/** The one that ships (null if it can't be read). */
	public static Ex1 bundled() {
		try (InputStream in = Ex1.class.getResourceAsStream("/assets/xen/ex1.json")) {
			if (in == null) return null;
			return read(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static float[] vec(JsonArray a) {
		float[] v = new float[a.size()];
		for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsFloat();
		return v;
	}

	private static float[][] mat(JsonObject m, String layer) {
		JsonArray rows = m.getAsJsonObject(layer).getAsJsonArray("w");
		float[][] w = new float[rows.size()][];
		for (int i = 0; i < w.length; i++) w[i] = vec(rows.get(i).getAsJsonArray());
		return w;
	}

	private static float[] bias(JsonObject m, String layer) {
		return vec(m.getAsJsonObject(layer).getAsJsonArray("b"));
	}

	private static float[] layer(float[] x, float[][] w, float[] b, boolean tanh) {
		float[] out = b.clone();
		for (int i = 0; i < x.length; i++) {
			float xi = x[i];
			if (xi == 0f) continue;
			float[] row = w[i];
			for (int j = 0; j < out.length; j++) out[j] += xi * row[j];
		}
		if (tanh) for (int j = 0; j < out.length; j++) out[j] = (float) Math.tanh(out[j]);
		return out;
	}

	static float sigmoid(float z) {
		return (float) (1 / (1 + Math.exp(-Math.max(-30, Math.min(30, z)))));
	}

	/** What it makes of these 119 senses. */
	public synchronized Out run(float[] x) {
		float[] xn = new float[N];
		for (int i = 0; i < N; i++) xn[i] = (x[i] - mean[i]) / std[i];
		float[] h1 = layer(xn, w1, b1, true), h2 = layer(h1, w2, b2, true);
		Out o = new Out(hidden);
		System.arraycopy(h2, 0, o.h2, 0, hidden);
		float[] k = layer(h2, wKeys, bKeys, false);
		for (int i = 0; i < k.length; i++) o.keys[i] = sigmoid(k[i]);
		float[] y = layer(h2, wYaw, bYaw, false);
		float max = Float.NEGATIVE_INFINITY, sum = 0;
		for (float v : y) max = Math.max(max, v);
		for (int i = 0; i < y.length; i++) sum += o.yaw[i] = (float) Math.exp(y[i] - max);
		for (int i = 0; i < y.length; i++) o.yaw[i] /= sum;
		o.pitch = (float) Math.tanh(layer(h2, wPitch, bPitch, false)[0]);
		o.danger = sigmoid(layer(h2, wDanger, bDanger, false)[0]);
		return o;
	}

	/**
	 * Learn from the game: what it sensed then (o, from run) was followed by a hurt, or wasn't. Only the danger head
	 * learns (one small step), so what it learned from the recording stays.
	 */
	public synchronized void learn(Out o, boolean hurt, float rate) {
		float y = hurt ? 1f : 0f;
		float g = (o.danger - y) * rate;
		for (int j = 0; j < hidden; j++) wDanger[j][0] -= g * o.h2[j];
		bDanger[0] -= g;
		if (hurt) learnedHurts++;
		else learnedCalm++;
	}

	/** Its danger head as learned in this world (for xen/ex1-learned.json). */
	public synchronized JsonObject learned() {
		JsonObject o = new JsonObject();
		JsonArray w = new JsonArray();
		for (float[] row : wDanger) w.add(row[0]);
		o.add("w", w);
		o.addProperty("b", bDanger[0]);
		o.addProperty("hurts", learnedHurts);
		o.addProperty("calm", learnedCalm);
		return o;
	}

	/** What a world's Xens taught it before (ignored if it doesn't fit). */
	public synchronized void restore(JsonObject o) {
		JsonArray w = o.getAsJsonArray("w");
		if (w == null || w.size() != hidden) return;
		for (int j = 0; j < hidden; j++) wDanger[j][0] = w.get(j).getAsFloat();
		bDanger[0] = o.get("b").getAsFloat();
		learnedHurts = o.has("hurts") ? o.get("hurts").getAsLong() : 0;
		learnedCalm = o.has("calm") ? o.get("calm").getAsLong() : 0;
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, learned().toString());
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}

	// ------------------------------------------------------------- the same rules as xen/ex1/features.py
	private static final String[] SMALL_ENDS = {"grass", "fern", "flower", "_tulip", "dandelion", "poppy", "orchid", "allium", "bluet", "daisy",
			"cornflower", "lily_of_the_valley", "_sapling", "litter", "lichen", "vine", "vines", "torch", "carpet", "_petals", "bush", "cane",
			"seagrass", "kelp", "kelp_plant", "mushroom", "roots", "sprouts", "lily_pad", "button", "rail", "pressure_plate", "redstone_wire",
			"snow", "lantern", "web", "wildflowers", "leaf_litter", "firefly_bush", "dripleaf"};
	private static final String[] OTHER_ENDS = {"_leaves", "crafting_table", "furnace", "chest", "barrel", "_bed", "_door", "_fence", "_fence_gate",
			"_slab", "_stairs", "_wall", "_trapdoor", "glass_pane", "ladder", "_sign", "smoker", "anvil", "bell", "campfire", "bookshelf", "cactus"};
	private static final java.util.Set<String> FOODS = java.util.Set.of("apple", "bread", "beef", "porkchop", "mutton", "chicken", "rabbit", "cod",
			"salmon", "carrot", "potato", "baked_potato", "melon_slice", "sweet_berries", "glow_berries", "cookie", "pumpkin_pie", "golden_apple",
			"enchanted_golden_apple", "golden_carrot", "beetroot", "beetroot_soup", "mushroom_stew", "rabbit_stew", "dried_kelp", "tropical_fish",
			"honey_bottle");
	private static final String[] BLOCK_ENDS = {"_planks", "_log", "_wood", "_wool", "stone", "dirt", "cobblestone", "deepslate", "sand", "gravel",
			"_bricks", "bricks", "netherrack", "andesite", "diorite", "granite", "tuff", "_terracotta", "glass", "clay", "mud", "_block"};

	static String bare(String id) {
		if (id == null) return "air";
		int c = id.indexOf(':');
		return (c >= 0 ? id.substring(c + 1) : id).toLowerCase(Locale.ROOT);
	}

	private static boolean endsWithAny(String n, String[] ends) {
		for (String e : ends) if (n.endsWith(e)) return true;
		return false;
	}

	/** The kind of block by its name: air, solid, water, lava, small (grass, leaf litter, glow lichen, torches...), other. */
	public static int category(String id) {
		String n = bare(id);
		if (n.isEmpty() || n.equals("air") || n.equals("cave_air") || n.equals("void_air") || n.equals("empty") || n.equals("none")) return AIR;
		if (n.equals("water") || n.equals("bubble_column")) return WATER;
		if (n.equals("lava")) return LAVA;
		if (n.equals("short_grass") || n.equals("tall_grass") || endsWithAny(n, SMALL_ENDS) && !n.endsWith("_block") && !n.equals("snow_block")) return SMALL;
		if (endsWithAny(n, OTHER_ENDS)) return OTHER;
		return SOLID;
	}

	/** What's in its hand: 0 empty, 1 sword, 2 pickaxe, 3 axe, 4 shovel, 5 food, 6 block, 7 other. */
	public static int hand(String id) {
		String n = bare(id);
		if (n.isEmpty() || n.equals("empty") || n.equals("air")) return 0;
		if (n.endsWith("_sword")) return 1;
		if (n.endsWith("_pickaxe")) return 2;
		if (n.endsWith("_axe")) return 3;
		if (n.endsWith("_shovel")) return 4;
		if (FOODS.contains(n) || n.startsWith("cooked_")) return 5;
		if (endsWithAny(n, BLOCK_ENDS)) return 6;
		return 7;
	}
}
