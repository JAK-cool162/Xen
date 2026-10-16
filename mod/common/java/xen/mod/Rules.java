package xen.mod;

import net.minecraft.core.BlockPos;
import xen.mod.core.Mind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rules a Xen writes for itself, from what happened to it: "if it's night and I'm underground, then make a shelter
 * (worked 4 of 5)", "if I have no pickaxe, then don't go mining (failed 3 of 3)". Every choice it finishes is counted
 * against what was true when it chose (night, underground, hurt, no food, its pickaxe, stuck...), one thing at a time
 * and two at a time. When a choice clearly goes better, or worse, in some situation than it usually does, and it has
 * seen that often enough, it writes it down as a rule (and says so in its journal); when later times don't bear a rule
 * out, it crosses it out. Its rules are hints to its mind, not orders: a rule that holds right now leans its choice, as
 * much as it's sure of it.
 * <p>Going round in circles: the same choice again and again with nothing to show for it, or minutes in one spot with
 * nothing gained, and it knows it's stuck. That choice is off for a while, it leans toward what it hasn't tried this
 * time, and whatever gets it out becomes a rule for being stuck ("if I'm stuck, then explore"). It tells other Xens its
 * rules when they talk; they take them on as hearsay (less sure than their own) until their own times say otherwise.
 * Kept with the world (rules.tsv), so what it worked out outlives a restart. {@code /xen rules} shows them.
 */
final class Rules {
	static final String[] CONDS = {"night", "dusk", "underground", "in water", "hurt", "hungry", "no food", "no pickaxe", "wooden pickaxe",
			"no logs", "no trees", "monsters", "no home", "no bed", "full bag", "stuck",
			"cave in sight", "drop ahead", "buildings in sight", "dark ground", "hidden monster", "mess at home"};
	private static final String[] SAYS = {"it's night", "it's getting dark", "I'm underground", "I'm in water", "I'm hurt", "I'm hungry",
			"I have no food", "I have no pickaxe", "I only have a wooden pickaxe", "I have no logs", "there are no trees around", "monsters are about",
			"I have no home", "I have no bed", "my bag is full", "I'm stuck",
			"I see a cave", "there's a drop ahead", "I see buildings", "the ground's dark (monsters spawn there)", "a monster's hiding nearby", "my home's a mess"};
	private static final int STUCK = 15;
	/** Seen this often before it writes a rule (hearsay counts too, a bit). */
	private static final float MIN_SEEN = 3;

	/** A rule: the situation (one or two things), the choice, do it or don't, how sure (0..1), and in words. */
	record Rule(String key, int option, boolean yes, float sure, float seen, float rate, String says) {}

	// ------------------------------------------------------------------------- kept with the world, for every Xen
	private static final Map<String, Map<String, float[]>> STORE = new HashMap<>();
	private static Path file;

	static synchronized void load(Path f) {
		file = f;
		STORE.clear();
		if (f == null || !Files.exists(f)) return;
		try {
			for (String l : Files.readAllLines(f)) {
				String[] p = l.split("\t");
				if (p.length < 5) continue;
				STORE.computeIfAbsent(p[0], k -> new LinkedHashMap<>()).put(p[1],
						new float[] {Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])});
			}
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("xen: couldn't read {}: {}", f, e.toString());
		}
	}

	static synchronized void save() {
		if (file == null) return;
		StringBuilder sb = new StringBuilder();
		for (var who : STORE.entrySet()) {
			for (var e : who.getValue().entrySet()) {
				float[] s = e.getValue();
				sb.append(who.getKey()).append('\t').append(e.getKey()).append('\t').append(String.format(Locale.ROOT, "%.2f\t%.2f\t%.2f", s[0], s[1], s[2])).append('\n');
			}
		}
		try {
			Files.writeString(file, sb.toString());
		} catch (IOException e) {
			XenMod.LOG.warn("xen: couldn't write {}: {}", file, e.toString());
		}
	}

	// ------------------------------------------------------------------------- one Xen's
	private final Companion c;
	private Map<String, float[]> stats;
	private final Map<String, Rule> written = new LinkedHashMap<>();
	/** What was true when it made its current choice (null: not one of its own). */
	private boolean[] startedWith;
	private int startedOption = -1;
	/** What's true now (looked at every few seconds, for its leanings). */
	private boolean[] now = new boolean[CONDS.length];
	/** Stuck: since when, where, what it's tried since; the choice it keeps going round with, and until when it's off. */
	boolean stuck;
	private long stuckSince;
	private BlockPos stuckAt;
	private final Set<Integer> tried = new HashSet<>();
	private int loopOption = -1;
	private long loopUntil;
	private final ArrayDeque<int[]> recent = new ArrayDeque<>();
	/** Where it was, what it carried, two minutes ago. */
	private BlockPos markPos;
	private int markHaul = -1;
	private long markAt;

	Rules(Companion c) {
		this.c = c;
	}

	private Map<String, float[]> stats() {
		if (stats == null) {
			synchronized (Rules.class) {
				stats = STORE.computeIfAbsent(c.name, k -> new LinkedHashMap<>());
			}
			for (String k : new ArrayList<>(stats.keySet())) if (!k.startsWith("*")) refresh(k, false);
		}
		return stats;
	}

	static boolean[] conds(float[] f, boolean stuck) {
		boolean[] h = new boolean[CONDS.length];
		h[0] = f[Mind.NIGHT] > 0.5f;
		h[1] = f[Mind.DUSK] > 0.5f;
		h[2] = f[Mind.UNDERGROUND] > 0.5f;
		h[3] = f[Mind.INWATER] > 0.5f;
		h[4] = f[Mind.HEALTH] < 0.5f;
		h[5] = f[Mind.FOODLVL] < 0.4f;
		h[6] = f[Mind.FOODITEMS] <= 0;
		h[7] = f[Mind.PICK] <= 0;
		h[8] = f[Mind.PICK] > 0 && f[Mind.PICK] <= 0.25f;
		h[9] = f[Mind.LOGS] <= 0;
		h[10] = f[Mind.TREES] <= 0;
		h[11] = f[Mind.HOSTILES] > 0;
		h[12] = f[Mind.HOME] <= 0;
		h[13] = f[Mind.BED] <= 0;
		h[14] = f[Mind.BAGFULL] > 0.85f;
		h[STUCK] = stuck;
		for (int k = 0; k < 6; k++) h[STUCK + 1 + k] = f[Mind.VIS0 + k] > 0.3f;   // (what its eyes make of its view)
		return h;
	}

	private static int cond(String name) {
		for (int i = 0; i < CONDS.length; i++) if (CONDS[i].equals(name)) return i;
		return -1;
	}

	private static int option(String name) {
		for (int i = 0; i < Mind.N; i++) if (Mind.OPTIONS[i].equals(name)) return i;
		return -1;
	}

	/** The rule's situation holds in this one? ("night+underground|shelter": both night and underground.) */
	private static boolean holds(String key, boolean[] h) {
		for (String part : key.substring(0, key.indexOf('|')).split("\\+")) {
			int i = cond(part);
			if (i < 0 || !h[i]) return false;
		}
		return true;
	}

	private static String says(String key, int o, boolean yes) {
		String[] parts = key.substring(0, key.indexOf('|')).split("\\+");
		StringBuilder sb = new StringBuilder("if ");
		for (int i = 0; i < parts.length; i++) {
			int k = cond(parts[i]);
			sb.append(i > 0 ? " and " : "").append(k < 0 ? parts[i] : SAYS[k]);
		}
		return sb.append(yes ? ", then " : ", then don't ").append(Mind.SAYS[o]).toString();
	}

	// ------------------------------------------------------------------------- what happened
	/** It chose one of its own kind of choices: what was true then. */
	void started(int o, float[] f) {
		startedOption = o;
		startedWith = conds(f, stuck);
	}

	/**
	 * How it went (what it got, the harm it came to, a death): counted against what was true when it chose, then any rule
	 * that's now clear is written (or crossed out). It went wrong if it got hurt or died; it worked if it got something
	 * out of it, or (a shelter, its bed, running, eating, a breather: things for staying safe) if it came to no harm.
	 */
	void finished(int o, float reward, float harm, boolean died) {
		if (o != startedOption || startedWith == null || c.player == null) return;
		boolean bad = died || harm > 0.1f;
		boolean good = !bad && (reward > 0.05f || harm <= 0.01f && (o == Mind.SHELTER || o == Mind.SLEEP || o == Mind.FLEE || o == Mind.EAT || o == Mind.REST));
		boolean[] h = startedWith;
		startedWith = null;
		startedOption = -1;
		String name = Mind.OPTIONS[o];
		count("*|" + name, good, bad, 1);
		List<String> touched = new ArrayList<>(), pairs = new ArrayList<>();
		for (int i = 0; i < CONDS.length; i++) {
			if (!h[i]) continue;
			touched.add(CONDS[i] + "|" + name);
			for (int j = i + 1; j < CONDS.length; j++) if (h[j]) pairs.add(CONDS[i] + "+" + CONDS[j] + "|" + name);
		}
		touched.addAll(pairs);                                                   // (one thing at a time first: two together only if they say more)
		for (String k : touched) count(k, good, bad, 1);
		for (String k : touched) refresh(k, true);
		recent.addLast(new int[] {o, good ? 1 : 0});
		while (recent.size() > 5) recent.removeFirst();
		long t = c.player.level().getGameTime();
		if (stuck) {
			tried.add(o);
			if (good) unstuck("by choosing to " + Mind.SAYS[o]);
		}
		if (!good && recent.size() >= 4 && o != Mind.SLEEP && o != Mind.SHELTER && o != Mind.EAT && o != Mind.FLEE) {   // the same thing, again and again, nothing to show for it
			int same = 0;
			boolean any = false;
			for (int[] r : recent) {
				if (r[0] == o) same++;
				if (r[1] == 1) any = true;
			}
			if (same >= 4 && !any && loopOption != o) {
				loopOption = o;
				loopUntil = t + 20 * 60 * 3;
				recent.clear();
				if (!stuck) {
					stuck = true;
					stuckSince = t;
					stuckAt = c.player.blockPosition().immutable();
					tried.clear();
				}
				c.journal("thinks", "notices it's going round in circles: tried to " + Mind.SAYS[o] + " " + same
						+ " times in a row and got nowhere. Something else for a while.");
			}
		}
	}

	/** Times seen, times it worked, times it went wrong (hurt or dead). */
	private void count(String key, boolean good, boolean bad, float w) {
		float[] s = stats().computeIfAbsent(key, k -> new float[3]);
		s[0] += w;
		if (good) s[1] += w;
		if (bad) s[2] += w;
	}

	/** Is there a rule in this now (and is it the same one)? Written or crossed out, in its journal when it says so. */
	private void refresh(String key, boolean say) {
		float[] s = stats.get(key);
		int bar = key.indexOf('|');
		int o = option(key.substring(bar + 1));
		float[] base = stats.get("*" + key.substring(bar));
		if (s == null || o < 0 || base == null) return;
		Rule had = written.get(key);
		Rule r = judge(key, o, s, base, had);
		if (r != null && key.contains("+")) {                                   // two things together: only if neither alone says it already
			for (String part : key.substring(0, bar).split("\\+")) {
				Rule one = written.get(part + key.substring(bar));
				if (one != null && one.yes() == r.yes()) r = null;
				if (r == null) break;
			}
		}
		if (r == null) {
			if (had != null) {
				written.remove(key);
				if (say) c.journal("learns", "crosses out its rule \"" + had.says() + "\": it hasn't held up");
			}
			return;
		}
		written.put(key, r);
		if (!key.contains("+")) {                                               // (one thing says it: the same with another thing too says nothing more)
			String part = key.substring(0, bar), rest = key.substring(bar);
			final Rule one = r;
			written.entrySet().removeIf(e -> e.getKey().contains("+") && e.getKey().endsWith(rest) && e.getValue().yes() == one.yes()
					&& java.util.Arrays.asList(e.getKey().substring(0, e.getKey().indexOf('|')).split("\\+")).contains(part));
		}
		if (say && (had == null || had.yes() != r.yes())) c.journal("learns", String.format(Locale.ROOT, "writes itself a rule: %s (%s %.0f of %.0f times)",
				r.says(), r.yes() ? "worked" : "didn't work", r.yes() ? s[1] : s[0] - s[1], s[0]));
	}

	private static Rule judge(String key, int o, float[] s, float[] base, Rule had) {
		if (s[0] < MIN_SEEN) return null;
		float rate = (s[1] + 0.5f) / (s[0] + 1), usual = (base[1] + 0.5f) / (base[0] + 1);   // (a little doubt: three of three isn't certain)
		float hurt = (s[2] + 0.25f) / (s[0] + 1), usuallyHurt = (base[2] + 0.25f) / (base[0] + 1);
		float ease = had == null ? 0 : 0.1f;                                     // (a rule it has stays unless it's clearly wrong now)
		boolean stuck = key.startsWith("stuck|") || key.contains("+stuck|");
		float sure = s[0] / (s[0] + 4);
		if (rate >= 0.6f - ease && (stuck || rate - usual >= 0.2f - ease)) return new Rule(key, o, true, sure * rate, s[0], rate, says(key, o, true));
		if (hurt >= 0.34f - ease && hurt - usuallyHurt >= 0.2f - ease || rate <= 0.4f + ease && usual - rate >= 0.25f - ease)
			return new Rule(key, o, false, sure * Math.max(hurt, 1 - rate), s[0], rate, says(key, o, false));
		return null;
	}

	// ------------------------------------------------------------------------- every few seconds
	private int haul() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) n += inv.getItem(i).getCount() * 31 + (inv.getItem(i).isEmpty() ? 0 : i);
		return n;
	}

	void tick() {
		var p = c.player;
		if (p == null || c.minion || c.inArena || !p.isAlive()) return;
		long t = p.level().getGameTime();
		now = conds(MindSense.features(c), stuck);
		if (stuck && stuckAt != null && !p.blockPosition().closerThan(stuckAt, 16)) unstuck("by getting away from where it was");
		if (stuck && t - stuckSince > 20 * 60 * 6) unstuck("in the end (six minutes)");   // (it lets it go: not stuck forever)
		if (t - markAt < 2400) return;
		int haul = haul();
		int o = c.goals.option;
		boolean excused = p.isSleeping() || o == Mind.SLEEP || o == Mind.SHELTER || o == Mind.FOLLOW || o == Mind.GUARD || o == Mind.TRADE
				|| c.mode == Companion.Mode.STAY || c.mode == Companion.Mode.FOLLOW && c.owner != null && c.server.getPlayerList().getPlayer(c.owner) != null
				|| c.builder.busy() || c.talker.busy() || now[0] && (now[2] || o == Mind.REST);
		if (!stuck && !excused && markPos != null && p.blockPosition().closerThan(markPos, 6) && haul == markHaul) {
			stuck = true;
			stuckSince = t;
			stuckAt = p.blockPosition().immutable();
			tried.clear();
			if (o >= 0) tried.add(o);
			c.journal("thinks", "notices it's stuck: two minutes in the same spot with nothing to show for it");
		}
		markAt = t;
		markPos = p.blockPosition().immutable();
		markHaul = haul;
	}

	private void unstuck(String how) {
		stuck = false;
		tried.clear();
		c.journal("thinks", "got itself unstuck " + how);
	}

	/** Its rules that hold now lean its choice (do it, don't); stuck, it leans away from what it's tried. */
	void lean(float[] b) {
		if (c.player == null) return;
		stats();
		for (Rule r : written.values()) if (holds(r.key(), now)) b[r.option()] += (r.yes() ? 0.5f : -0.6f) * r.sure();
		if (stuck) for (int o : tried) b[o] -= 0.4f;
		if (loopOption >= 0 && c.player.level().getGameTime() < loopUntil) b[loopOption] -= 1f;
	}

	// ------------------------------------------------------------------------- telling others
	/** Its surest rule the other one hasn't got, or null. */
	Rule tell(Rules other) {
		stats();
		other.stats();
		Rule best = null;
		for (Rule r : written.values()) {
			if (r.sure() < 0.3f) continue;
			Rule theirs = other.written.get(r.key());
			if (theirs != null && theirs.yes() == r.yes()) continue;
			if (best == null || r.sure() > best.sure()) best = r;
		}
		return best;
	}

	/** Another Xen told it a rule: hearsay, weighed by how much it trusts them; its own times can still overrule it. */
	boolean heard(Rule r, String from, float trust) {
		float w = 2f * Math.max(0.1f, 0.5f + 0.5f * trust);
		float[] mine = stats().get(r.key());
		if (mine != null && mine[0] >= 6 && Math.abs((mine[1] + 0.5f) / (mine[0] + 1) - r.rate()) > 0.4f) return false;   // (its own times say otherwise)
		String opt = r.key().substring(r.key().indexOf('|'));
		float[] base = stats().computeIfAbsent("*" + opt, k -> new float[3]);
		if (base[0] < 1) {                                                         // (never chosen it: what it heard is all it knows of it)
			base[0] += 1;
			base[1] += 0.5f;
		}
		count(r.key(), false, false, w);
		stats.get(r.key())[1] += w * r.rate();
		Rule before = written.get(r.key());
		refresh(r.key(), false);
		Rule after = written.get(r.key());
		boolean took = after != null && (before == null || before.yes() != after.yes());
		c.journal("learns", (took ? "takes on " : "hears ") + from + "'s rule: " + r.says());
		return took;
	}

	// ------------------------------------------------------------------------- /xen rules
	String describe() {
		stats();
		if (written.isEmpty()) return c.name + ": no rules of its own yet" + (stuck ? " (stuck right now)" : "") + ".";
		List<Rule> list = new ArrayList<>(written.values());
		list.sort((a, b) -> Float.compare(b.sure(), a.sure()));
		StringBuilder sb = new StringBuilder(c.name + ": " + list.size() + (list.size() == 1 ? " rule" : " rules") + (stuck ? " (stuck right now)" : ""));
		for (int i = 0; i < list.size() && i < 12; i++) {
			Rule r = list.get(i);
			sb.append(String.format(Locale.ROOT, "\n  %s (%.0f%% sure, %.0f times)", r.says(), 100 * r.sure(), r.seen()));
		}
		if (list.size() > 12) sb.append("\n  ...and ").append(list.size() - 12).append(" more");
		return sb.toString();
	}

	int count() {
		stats();
		return written.size();
	}
}
