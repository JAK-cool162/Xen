package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * What a Xen remembers of the world, like a player does: places (its home, the portal, the fortress, the stronghold,
 * a trial chamber, a village...) and the ways it walked between them. Every dozen blocks or so it walks, it keeps a
 * crumb of the way (per dimension), linked to the one before; crumbs close to an old one join it, so the ways it takes
 * again and again become roads it knows. Going somewhere far, it follows the known road there (its legs only plan
 * a few dozen blocks ahead at a time), the way you'd go back home the way you came instead of cutting through a
 * mountain you've never seen. Kept with the Xen.
 */
final class Places {
	/** A remembered place: what it is, in which world, where. */
	record Place(String name, String dim, BlockPos pos, long day) {}

	private static final int GAP = 12, JOIN = 6, MAX_CRUMBS = 800;

	private final Companion c;
	final Map<String, Place> places = new LinkedHashMap<>();
	/** Crumbs of the ways it walked, per dimension: where, and which crumbs they lead to. */
	private final Map<String, List<BlockPos>> crumbs = new HashMap<>();
	private final Map<String, Map<Integer, List<Integer>>> links = new HashMap<>();
	private int lastCrumb = -1;
	private String lastDim = "";

	Places(Companion c) {
		this.c = c;
	}

	static String dim(net.minecraft.world.level.Level level) {
		return level.dimension().identifier().getPath();
	}

	private String here() {
		return dim(c.player.level());
	}

	/** Remember a place (again: it moved, or it's the same). */
	void remember(String name, BlockPos pos) {
		if (c.player == null || pos == null) return;
		String dim = here();
		Place old = places.get(key(name, dim));
		places.put(key(name, dim), new Place(name, dim, pos.immutable(), c.player.level().getGameTime() / 24000));
		if (old == null || old.pos().distSqr(pos) > 64) c.journal("remembers", name + " at " + pos.toShortString() + " (" + dim + ")");
	}

	/** Remember a place in a given world (the other end of a portal it just came through). */
	void remember(String name, String dim, BlockPos pos) {
		if (c.player == null || pos == null) return;
		Place old = places.get(key(name, dim));
		places.put(key(name, dim), new Place(name, dim, pos.immutable(), c.player.level().getGameTime() / 24000));
		if (old == null || old.pos().distSqr(pos) > 64) c.journal("remembers", name + " at " + pos.toShortString() + " (" + dim + ")");
	}

	void forget(String name) {
		places.remove(key(name, here()));
	}

	private static String key(String name, String dim) {
		return name + "|" + dim;
	}

	/** A place it remembers in the world it's in now (null if none). */
	BlockPos get(String name) {
		Place p = places.get(key(name, here()));
		return p == null ? null : p.pos();
	}

	/** A place it remembers in another world ("overworld", "the_nether", "the_end"). */
	BlockPos get(String name, String dim) {
		Place p = places.get(key(name, dim));
		return p == null ? null : p.pos();
	}

	/** The nearest remembered place whose name starts with this (e.g. "portal"), in this world. */
	BlockPos nearest(String prefix) {
		BlockPos best = null, feet = c.player.blockPosition();
		for (Place p : places.values()) {
			if (!p.dim().equals(here()) || !p.name().startsWith(prefix)) continue;
			if (best == null || p.pos().distSqr(feet) < best.distSqr(feet)) best = p.pos();
		}
		return best;
	}

	// ----------------------------------------------------------------------------------- the ways it walked
	/** Every few ticks: a new crumb when it has come a dozen blocks from the last (on its feet, not flying or falling). */
	void tick() {
		var p = c.player;
		if (p == null || p.getAbilities().flying || !(p.onGround() || p.isInWater()) || c.inArena) return;
		String dim = here();
		List<BlockPos> list = crumbs.computeIfAbsent(dim, k -> new ArrayList<>());
		Map<Integer, List<Integer>> link = links.computeIfAbsent(dim, k -> new HashMap<>());
		if (!dim.equals(lastDim)) {
			lastDim = dim;
			lastCrumb = -1;
		}
		BlockPos feet = p.blockPosition();
		if (lastCrumb >= 0 && lastCrumb < list.size() && list.get(lastCrumb).distSqr(feet) < GAP * GAP) return;
		int near = nearestCrumb(list, feet, JOIN);                        // an old crumb right here: this way is known
		int now;
		if (near >= 0) {
			now = near;
		} else {
			if (list.size() >= MAX_CRUMBS) {                                // full: it forgets the oldest ways
				list.clear();
				link.clear();
				lastCrumb = -1;
			}
			list.add(feet.immutable());
			now = list.size() - 1;
		}
		if (lastCrumb >= 0 && lastCrumb != now && lastCrumb < list.size() && list.get(lastCrumb).distSqr(feet) < 4 * GAP * GAP) {
			connect(link, lastCrumb, now);
		}
		lastCrumb = now;
	}

	private static void connect(Map<Integer, List<Integer>> link, int a, int b) {
		List<Integer> la = link.computeIfAbsent(a, k -> new ArrayList<>()), lb = link.computeIfAbsent(b, k -> new ArrayList<>());
		if (!la.contains(b)) la.add(b);
		if (!lb.contains(a)) lb.add(a);
	}

	private static int nearestCrumb(List<BlockPos> list, BlockPos at, double within) {
		int best = -1;
		double bestD = within * within;
		for (int i = 0; i < list.size(); i++) {
			double d = list.get(i).distSqr(at);
			if (d <= bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/**
	 * Going far: the next crumb of the known way there (about 20 to 40 blocks on), or null when it knows no way (or
	 * it's close: then its legs find the way by themselves).
	 */
	Vec3 via(Vec3 goal) {
		var p = c.player;
		if (p == null || goal.distanceTo(p.position()) < 56) return null;
		List<BlockPos> list = crumbs.get(here());
		Map<Integer, List<Integer>> link = links.get(here());
		if (list == null || link == null || list.size() < 3) return null;
		int from = nearestCrumb(list, p.blockPosition(), 24), to = nearestCrumb(list, BlockPos.containing(goal), 32);
		if (from < 0 || to < 0 || from == to) return null;
		// Dijkstra over the crumbs (a few hundred at most)
		double[] dist = new double[list.size()];
		int[] prev = new int[list.size()];
		java.util.Arrays.fill(dist, Double.MAX_VALUE);
		java.util.Arrays.fill(prev, -1);
		dist[from] = 0;
		PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
		open.add(new double[] {0, from});
		while (!open.isEmpty()) {
			double[] n = open.poll();
			int i = (int) n[1];
			if (n[0] > dist[i]) continue;
			if (i == to) break;
			for (int j : link.getOrDefault(i, List.of())) {
				double d = dist[i] + Math.sqrt(list.get(i).distSqr(list.get(j)));
				if (d < dist[j]) {
					dist[j] = d;
					prev[j] = i;
					open.add(new double[] {d, j});
				}
			}
		}
		if (prev[to] < 0) return null;
		ArrayDeque<Integer> way = new ArrayDeque<>();
		for (int i = to; i >= 0; i = prev[i]) way.addFirst(i);
		for (int i : way) {                                                 // the first crumb 20+ blocks on
			BlockPos b = list.get(i);
			if (b.distSqr(p.blockPosition()) >= 20 * 20) return Vec3.atBottomCenterOf(b);
		}
		return null;
	}

	/** How many crumbs of ways it knows (for /xen status). */
	int known() {
		int n = 0;
		for (List<BlockPos> l : crumbs.values()) n += l.size();
		return n;
	}

	/** For its notes (and "where is the portal?"): the places it remembers, in words. */
	String describe() {
		if (places.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("You remember these places:");
		int n = 0;
		for (Place p : places.values()) {
			if (n++ >= 8) break;
			sb.append(' ').append(p.name()).append(" at ").append(p.pos().getX()).append(' ').append(p.pos().getY()).append(' ').append(p.pos().getZ())
					.append(p.dim().equals("overworld") ? "" : " (" + p.dim().replace("the_", "the ") + ")").append(';');
		}
		sb.setCharAt(sb.length() - 1, '.');
		return sb.toString();
	}

	/** "Where is the portal?": what it remembers of it, or null. */
	String where(String what) {
		String w = what.toLowerCase(Locale.ROOT);
		for (Place p : places.values()) {
			if (!w.contains(p.name().split(" ")[0])) continue;
			return String.format(Locale.ROOT, "The %s is at %d %d %d%s.", p.name(), p.pos().getX(), p.pos().getY(), p.pos().getZ(),
					p.dim().equals("overworld") ? "" : " in " + p.dim().replace("the_", "the ").replace("nether", "Nether").replace("end", "End"));
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ keeping
	JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonArray ps = new JsonArray();
		for (Place p : places.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("name", p.name());
			j.addProperty("dim", p.dim());
			j.addProperty("x", p.pos().getX());
			j.addProperty("y", p.pos().getY());
			j.addProperty("z", p.pos().getZ());
			j.addProperty("day", p.day());
			ps.add(j);
		}
		o.add("places", ps);
		JsonObject ways = new JsonObject();
		for (var e : crumbs.entrySet()) {
			JsonArray a = new JsonArray();
			Map<Integer, List<Integer>> link = links.getOrDefault(e.getKey(), Map.of());
			for (int i = 0; i < e.getValue().size(); i++) {
				BlockPos b = e.getValue().get(i);
				JsonArray one = new JsonArray();
				one.add(b.getX());
				one.add(b.getY());
				one.add(b.getZ());
				for (int j : link.getOrDefault(i, List.of())) if (j > i) one.add(-1 - j);   // links forward, as negatives
				a.add(one);
			}
			ways.add(e.getKey(), a);
		}
		o.add("ways", ways);
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		try {
			if (o.has("places")) {
				for (var e : o.getAsJsonArray("places")) {
					JsonObject j = e.getAsJsonObject();
					Place p = new Place(j.get("name").getAsString(), j.get("dim").getAsString(),
							new BlockPos(j.get("x").getAsInt(), j.get("y").getAsInt(), j.get("z").getAsInt()), j.has("day") ? j.get("day").getAsLong() : 0);
					places.put(key(p.name(), p.dim()), p);
				}
			}
			if (o.has("ways")) {
				for (var e : o.getAsJsonObject("ways").entrySet()) {
					List<BlockPos> list = new ArrayList<>();
					Map<Integer, List<Integer>> link = new HashMap<>();
					JsonArray a = e.getValue().getAsJsonArray();
					for (int i = 0; i < a.size(); i++) {
						JsonArray one = a.get(i).getAsJsonArray();
						list.add(new BlockPos(one.get(0).getAsInt(), one.get(1).getAsInt(), one.get(2).getAsInt()));
						for (int k = 3; k < one.size(); k++) connect(link, i, -1 - one.get(k).getAsInt());
					}
					crumbs.put(e.getKey(), list);
					links.put(e.getKey(), link);
				}
			}
		} catch (RuntimeException e) {
			XenMod.LOG.warn("{} couldn't recall its places: {}", c.name, e.toString());
		}
	}
}
