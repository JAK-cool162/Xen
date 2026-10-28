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
			XenMod.LOG.info("Xen BTB: learned values for {} spots, {} tasks remembered", q.size(), memory.size());
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Xen BTB: couldn't read what it learned ({}), starting fresh", e.toString());
			q = new HashMap<>();
			memory = new HashMap<>();
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
