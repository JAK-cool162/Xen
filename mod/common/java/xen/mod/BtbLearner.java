package xen.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the Back to Basics brain ({@link Btb}) learns from playing, without a neural network: a Q-value for each task in
 * each kind of spot (tier, health, food, night, under the ground, danger), and a memory of how each task went (tries,
 * how many worked, deaths, how long). Every Xen adds to the same book; it is kept in config/xen/btb.json and read back
 * at the next start, so the Xens of a world get better at it over the days.
 *
 * <p>Q-learning as usual: Q(s,a) += alpha * (reward + gamma * max Q(s',.) - Q(s,a)). The rules propose the sensible
 * tasks; what it has learned only shifts them (a bias of a few points), so a bad run can't make it do something silly.
 */
final class BtbLearner {
	private static final float ALPHA = 0.2f, GAMMA = 0.8f, SCALE = 0.05f, CAP = 1.5f;
	/** Q-values: state key, then task name. */
	private Map<String, Map<String, Float>> q = new HashMap<>();
	/** What happened when it did each task: tries, worked, deaths, seconds in all. */
	private Map<String, double[]> memory = new HashMap<>();
	/** How much sooner to deal with each kind of mob in a fight (1 to start; the ones that hurt it most go up). */
	private Map<String, Float> mob = new HashMap<>();
	/** Each schematic it built: times, blocks placed, left out, seconds in all (and what it made, kept by its user). */
	private Map<String, double[]> builds = new HashMap<>();
	/** From the schematics it studied: styles (by schematic), what was inside, and how machine parts join (all counted). */
	private Map<String, String[]> styles = new HashMap<>();
	private Map<String, Integer> interior = new HashMap<>(), rules = new HashMap<>();
	private Path file;
	private boolean dirty;

	synchronized void load(Path dir) {
		file = dir.resolve("btb.json");
		try {
			if (!Files.exists(file)) return;
			JsonObject o = new Gson().fromJson(Files.readString(file), JsonObject.class);
			if (o == null) return;
			if (o.has("q")) q = new Gson().fromJson(o.get("q"), new TypeToken<Map<String, Map<String, Float>>>() {}.getType());
			if (o.has("memory")) memory = new Gson().fromJson(o.get("memory"), new TypeToken<Map<String, double[]>>() {}.getType());
			if (q == null) q = new HashMap<>();
			if (memory == null) memory = new HashMap<>();
			if (o.has("mob")) mob = new Gson().fromJson(o.get("mob"), new TypeToken<Map<String, Float>>() {}.getType());
			if (mob == null) mob = new HashMap<>();
			if (o.has("builds")) builds = new Gson().fromJson(o.get("builds"), new TypeToken<Map<String, double[]>>() {}.getType());
			if (builds == null) builds = new HashMap<>();
			if (o.has("styles")) styles = new Gson().fromJson(o.get("styles"), new TypeToken<Map<String, String[]>>() {}.getType());
			if (o.has("interior")) interior = new Gson().fromJson(o.get("interior"), new TypeToken<Map<String, Integer>>() {}.getType());
			if (o.has("rules")) rules = new Gson().fromJson(o.get("rules"), new TypeToken<Map<String, Integer>>() {}.getType());
			if (styles == null) styles = new HashMap<>();
			if (interior == null) interior = new HashMap<>();
			if (rules == null) rules = new HashMap<>();
			XenMod.LOG.info("Xen BTB: learned values for {} spots, {} tasks remembered", q.size(), memory.size());
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Xen BTB: couldn't read what it learned ({}), starting fresh", e.toString());
			q = new HashMap<>();
			memory = new HashMap<>();
			mob = new HashMap<>();
		}
	}

	synchronized void save() {
		if (file == null || !dirty) return;
		try {
			Files.createDirectories(file.getParent());
			JsonObject o = new JsonObject();
			Gson g = new Gson();
			o.add("q", g.toJsonTree(q));
			o.add("memory", g.toJsonTree(memory));
			o.add("mob", g.toJsonTree(mob));
			o.add("builds", g.toJsonTree(builds));
			o.add("styles", g.toJsonTree(styles));
			o.add("interior", g.toJsonTree(interior));
			o.add("rules", g.toJsonTree(rules));
			Files.writeString(file, g.toJson(o));
			dirty = false;
		} catch (IOException e) {
			XenMod.LOG.warn("Xen BTB: couldn't keep what it learned: {}", e.toString());
		}
	}

	/** How much its learning leans toward this task in this spot (a few points either way at most). */
	synchronized float bias(String state, String task) {
		Map<String, Float> row = q.get(state);
		float v = row == null ? 0 : row.getOrDefault(task, 0f);
		double[] m = memory.get(task);
		float failing = 0;
		if (m != null && m[0] >= 4) failing = (float) (0.5 - m[1] / m[0]);           // (a task that mostly comes to nothing: a little less keen)
		return Math.max(-CAP, Math.min(CAP, v * SCALE - failing));
	}

	/** How often it tries one of the other sensible tasks to find out (less, the more it knows). */
	synchronized float explore() {
		return q.size() < 30 ? 0.08f : 0.04f;
	}

	/** A task is over: its reward goes into the book. */
	synchronized void learn(String state, String task, float reward, String next, boolean worked, double seconds, boolean died) {
		Map<String, Float> row = q.computeIfAbsent(state, k -> new HashMap<>());
		float old = row.getOrDefault(task, 0f);
		Map<String, Float> after = q.get(next);
		float best = 0;
		if (after != null) for (float v : after.values()) best = Math.max(best, v);
		row.put(task, old + ALPHA * (reward + GAMMA * best - old));
		double[] m = memory.computeIfAbsent(task, k -> new double[4]);
		m[0]++;
		if (worked) m[1]++;
		if (died) m[2]++;
		m[3] += seconds;
		dirty = true;
	}

	/** What it learned from a schematic (studying it twice doesn't count it twice: the latest look replaces the last). */
	private final java.util.Set<String> studied = new java.util.HashSet<>();

	synchronized void learnStyle(String from, String[] style) {
		styles.put(from, style);
		dirty = true;
	}

	synchronized void learnInterior(String from, Map<String, Integer> seen) {
		if (!studied.add("in:" + from)) return;
		seen.forEach((k, v) -> interior.merge(k, v, Integer::sum));
		dirty = true;
	}

	synchronized void learnRules(String from, Map<String, Integer> seen) {
		if (!studied.add("rules:" + from)) return;
		seen.forEach((k, v) -> rules.merge(k, v, Integer::sum));
		dirty = true;
	}

	/** One of the styles it learned (null: none yet), and which. */
	synchronized Map.Entry<String, String[]> style(java.util.Random random) {
		if (styles.isEmpty()) return null;
		var all = new java.util.ArrayList<>(styles.entrySet());
		return all.get(random.nextInt(all.size()));
	}

	synchronized Map<String, Integer> interior() {
		return new HashMap<>(interior);
	}

	/** How often it saw this join ("hopper>down above furnace>east"...): 0 if never. */
	synchronized int rule(String key) {
		return rules.getOrDefault(key, 0);
	}

	synchronized Map<String, Integer> rules() {
		return new HashMap<>(rules);
	}

	/** A schematic built: into its experience. */
	synchronized void built(String name, int placed, int left, double seconds) {
		double[] b = builds.computeIfAbsent(name, k -> new double[5]);
		b[0]++;
		b[1] += placed;
		b[2] += left;
		b[3] += seconds;
		dirty = true;
	}

	/** Something a build of it made (taken out of its output): counted with it. */
	synchronized void made(String name, int items) {
		builds.computeIfAbsent(name, k -> new double[5])[4] += items;
		dirty = true;
	}

	/** How soon this kind of mob is dealt with in a fight (0.6 to 2). */
	synchronized float mobWeight(String kind) {
		return mob.getOrDefault(kind, 1f);
	}

	/** Hurt by this kind: next time it's dealt with a little sooner. */
	synchronized void mobHurt(String kind, float amount) {
		mob.put(kind, Math.min(2f, mob.getOrDefault(kind, 1f) + 0.01f * amount));
		dirty = true;
	}

	/** Beat one: its weight drifts back toward 1. */
	synchronized void mobBeaten(String kind) {
		float w = mob.getOrDefault(kind, 1f);
		mob.put(kind, Math.max(0.6f, w + (1 - w) * 0.05f));
		dirty = true;
	}

	/** For /xen status and the tests: how each task has gone. */
	synchronized String summary() {
		StringBuilder sb = new StringBuilder();
		memory.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
			double[] m = e.getValue();
			sb.append(String.format(Locale.ROOT, "%s %d/%d%s ~%.0fs; ", e.getKey().toLowerCase(Locale.ROOT), (int) m[1], (int) m[0],
					m[2] > 0 ? " (" + (int) m[2] + " died)" : "", m[3] / Math.max(1, m[0])));
		});
		return sb.length() == 0 ? "nothing learned yet" : q.size() + " spots; " + sb;
	}
}
