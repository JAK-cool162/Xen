package xen.mod;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the server's time goes for the Xens (/xen perf): nanoseconds per part of a Xen's tick (its eyes, walking,
 * choosing...) and per Xen, over the last half minute.
 */
final class Perf {
	private Perf() {}

	private static final long WINDOW = 20 * 30;                         // ticks in a window
	private static Map<String, Long> parts = new HashMap<>(), lastParts = new HashMap<>();
	private static Map<String, Long> xens = new HashMap<>(), lastXens = new HashMap<>();
	private static long ticks, lastTicks, windowStart = -1;

	static long now() {
		return System.nanoTime();
	}

	/** Time since t0 goes to that part of a Xen's tick. */
	static void add(String part, long t0) {
		parts.merge(part, System.nanoTime() - t0, Long::sum);
	}

	/** Time since t0 goes to that Xen (its whole tick). */
	static void xen(String name, long t0) {
		xens.merge(name, System.nanoTime() - t0, Long::sum);
	}

	/** A server tick has gone by (each window, the last one is kept to show). */
	static void tick(long gameTick) {
		if (windowStart < 0) windowStart = gameTick;
		ticks++;
		if (gameTick - windowStart >= WINDOW) {
			lastParts = parts;
			lastXens = xens;
			lastTicks = ticks;
			parts = new HashMap<>();
			xens = new HashMap<>();
			ticks = 0;
			windowStart = gameTick;
		}
	}

	/** What /xen perf says: the last half minute (or what there is so far). */
	static String report() {
		Map<String, Long> p = lastTicks > 0 ? lastParts : parts, x = lastTicks > 0 ? lastXens : xens;
		long n = Math.max(1, lastTicks > 0 ? lastTicks : ticks);
		long all = 0;
		for (long v : x.values()) all += v;
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(java.util.Locale.ROOT, "Xens: %.2f ms a tick (%.0f%% of the 50 ms a tick has), %d Xens, %.2f ms each",
				all / 1e6 / n, all / 1e6 / n / 50 * 100, x.size(), x.isEmpty() ? 0 : all / 1e6 / n / x.size()));
		Map<String, Long> sorted = new LinkedHashMap<>();
		p.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> sorted.put(e.getKey(), e.getValue()));
		sb.append("\nBy part:");
		for (var e : sorted.entrySet()) sb.append(String.format(java.util.Locale.ROOT, " %s %.2f,", e.getKey(), e.getValue() / 1e6 / n));
		var worst = x.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(5).toList();
		sb.append("\nBusiest:");
		for (var e : worst) sb.append(String.format(java.util.Locale.ROOT, " %s %.2f,", e.getKey(), e.getValue() / 1e6 / n));
		return sb.substring(0, sb.length() - 1) + " (ms a tick)";
	}
}
