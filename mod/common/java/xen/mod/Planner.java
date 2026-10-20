package xen.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import xen.mod.core.Mind;

/**
 * Xen Planner: what it's after next, two to four things ahead, the way a player thinks "a shield before night, then
 * coal for torches, then food". It isn't the mind (that decides) and it isn't the agenda (how players get on, in
 * order): it leans the mind's choices toward the first of them, and the second a little, and the mind can always do
 * something else.
 * <p>And it changes its mind when something turns up, like a player does: coal in a wall it's walking past, animals
 * about when it's low on food, a village spotted while it's out looking for a cave (it goes and has a look first, and
 * back to the cave later). What turns up is what it saw itself.
 * <p>How much it leans on each kind of aim, it learns: how often an aim at the top of its list came to something
 * within ten minutes (all Xens together, kept with the world).
 */
final class Planner {
	enum Aim {
		SHIELD("a shield before night"), COAL("coal for torches"), FOOD("food"), IRON("iron"), VILLAGE("a look at that village"), DIAMONDS("diamonds");

		final String what;

		Aim(String what) {
			this.what = what;
		}
	}

	// ------------------------------------------------------------------------- learned, for every Xen
	private static final EnumMap<Aim, int[]> TRACK = new EnumMap<>(Aim.class);   // (came to something, didn't)
	private static Path file;

	static synchronized void load(Path f) {
		file = f;
		TRACK.clear();
		if (f == null || !Files.exists(f)) return;
		try {
			for (String l : Files.readAllLines(f)) {
				String[] p = l.split("\t");
				if (p.length != 3) continue;
				try {
					TRACK.put(Aim.valueOf(p[0]), new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])});
				} catch (IllegalArgumentException ignored) {
				}
			}
		} catch (IOException e) {
			XenMod.LOG.warn("xen: couldn't read {}: {}", f, e.toString());
		}
	}

	static synchronized void save() {
		if (file == null || TRACK.isEmpty()) return;
		StringBuilder sb = new StringBuilder();
		for (var e : TRACK.entrySet()) sb.append(e.getKey().name()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
		try {
			Files.writeString(file, sb.toString());
		} catch (IOException e) {
			XenMod.LOG.warn("xen: couldn't write {}: {}", file, e.toString());
		}
	}

	/** How often an aim like this came to something (0.5 for one it knows nothing about). */
	static synchronized float worth(Aim a) {
		int[] t = TRACK.get(a);
		return t == null ? 0.5f : (t[0] + 1f) / (t[0] + t[1] + 2f);
	}

	private static synchronized void track(Aim a, boolean worked) {
		TRACK.computeIfAbsent(a, k -> new int[2])[worked ? 0 : 1]++;
	}

	// ------------------------------------------------------------------------- one Xen's
	private final Companion c;
	/** Its aims, first first (two to four of them). */
	final List<Aim> aims = new ArrayList<>();
	/** What turned up, and till when it counts. */
	private final EnumMap<Aim, Long> turnedUp = new EnumMap<>(Aim.class);
	private final EnumMap<Aim, String> because = new EnumMap<>(Aim.class);
	/** The first aim since when. */
	private Aim first;
	private long firstAt, nextThink;
	private BlockPos village;
	int changed, cameTo;

	Planner(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** It saw something that changes what's first (it found coal, a village...): for a few minutes. */
	void turnedUp(Aim a, String what) {
		if (c.player == null) return;
		Long was = turnedUp.get(a);
		turnedUp.put(a, now() + 20 * 60 * 4);
		because.put(a, what);
		if (was == null || was < now()) nextThink = 0;
	}

	/** A village it just saw (Places): if it was off looking for a cave, it may go and have a look first. */
	void sawVillage(BlockPos at) {
		if (c.player == null || c.visits.wasThere(at)) return;
		village = at.immutable();
		turnedUp(Aim.VILLAGE, "spotted a village");
		if (c.chores.lookingForCave() && c.random().nextFloat() < 0.4f + 0.5f * c.personality.curiosity) {   // (its own call: a curious one nearly always)
			c.journal("thinks", "spotted a village while looking for a cave: a look at the village first, the cave later");
			c.chores.cancel();
			c.focus.forget();                                                    // (the village now; its mind picks the mining again after)
			c.visits.soon();
		}
	}

	private boolean wanted(Aim a, Map<String, Integer> items, int tier) {
		int iron = items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0);
		return switch (a) {
			case SHIELD -> tier >= 2 && !c.crafter.hasShield();
			case COAL -> items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0) < 4 && items.getOrDefault("torch", 0) < 12 && tier >= 1;
			case FOOD -> items.getOrDefault("food", 0) < 3;
			case IRON -> tier == 2 && iron < 3;
			case VILLAGE -> village != null && !c.visits.wasThere(village) && village.closerThan(c.player.blockPosition(), 192);
			case DIAMONDS -> tier == 3 && items.getOrDefault("diamond", 0) < 3;
		};
	}

	private float priority(Aim a, Map<String, Integer> items) {
		long day = Compat.timeOfDay(c.player.level()) % 24000;
		boolean afternoon = day >= 6000 && day < 12500;
		float p = switch (a) {
			case SHIELD -> afternoon ? 0.9f : 0.55f;                           // (one iron, before the night: it stops nearly everything)
			case COAL -> 0.35f;
			case FOOD -> c.player.getFoodData().getFoodLevel() < 14 ? 0.8f : 0.4f;
			case IRON -> items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0) == 0 ? 0.95f : 0.6f;   // (its first iron: what every player goes for once they have stone tools)
			case VILLAGE -> c.goals.home == null ? 0.55f : 0.35f;
			case DIAMONDS -> 0.5f;
		};
		Long t = turnedUp.get(a);
		if (t != null && t > now()) p += 0.5f;                                 // (it just turned up: now's the time)
		return p * (0.6f + 0.8f * worth(a));
	}

	/** Every few seconds: what's first now. */
	void think() {
		if (c.player == null || c.minion || now() < nextThink) return;
		nextThink = now() + 100;
		var items = c.items();
		int tier = c.crafter.pickTier();
		if (items.getOrDefault("food", 0) < 3 && seesAnimals()) turnedUp(Aim.FOOD, "animals about");
		if (tier >= 1 && c.eyes.nearest("coal", 20, null) != null && items.getOrDefault("coal", 0) < 4) turnedUp(Aim.COAL, "coal in a wall there");
		List<Aim> next = new ArrayList<>();
		for (Aim a : Aim.values()) if (wanted(a, items, tier)) next.add(a);
		next.sort((x, y) -> Float.compare(priority(y, items), priority(x, items)));
		while (next.size() > 4) next.remove(next.size() - 1);
		if (first != null && !next.contains(first)) {                           // the first is done (or no longer wanted)
			boolean worked = !wanted(first, items, tier) && first != Aim.VILLAGE || first == Aim.VILLAGE && village != null && c.visits.wasThere(village);
			if (now() - firstAt > 200) track(first, worked);
			if (worked) cameTo++;
		} else if (first != null && now() - firstAt > 20 * 60 * 10) {           // ten minutes first and nothing: not worth as much
			track(first, false);
			firstAt = now();
		}
		Aim top = next.isEmpty() ? null : next.get(0);
		if (top != first) {
			first = top;
			firstAt = now();
			if (top != null) {
				changed++;
				String why = because.get(top);
				Long t = turnedUp.get(top);
				StringBuilder sb = new StringBuilder("plans: ").append(top.what);
				for (int i = 1; i < next.size(); i++) sb.append(i == next.size() - 1 ? ", then " : ", ").append(next.get(i).what);
				if (why != null && t != null && t > now()) sb.append(" (").append(why).append(')');
				c.journal("plans", sb.toString());
			}
		}
		aims.clear();
		aims.addAll(next);
	}

	private long animalsAt = -1;
	private boolean animals;

	private boolean seesAnimals() {
		if (now() - animalsAt < 200) return animals;
		animalsAt = now();
		animals = false;
		for (var a : c.player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, c.player.getBoundingBox().inflate(20), x -> x.isAlive() && !x.isBaby())) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath();
			if ((id.equals("cow") || id.equals("pig") || id.equals("sheep") || id.equals("chicken") || id.equals("rabbit")) && c.player.hasLineOfSight(a)) {
				animals = true;
				break;
			}
		}
		return animals;
	}

	/** Its leanings: the first aim's way of getting there (the next step of it), the second's a little. */
	void lean(float[] b) {
		if (c.player == null || aims.isEmpty()) return;
		var items = c.items();
		for (int i = 0; i < Math.min(2, aims.size()); i++) {
			float s = (i == 0 ? 1f : 0.4f) * (0.5f + worth(aims.get(i)));
			switch (aims.get(i)) {
				case SHIELD -> {
					int ingots = items.getOrDefault("iron_ingot", 0), raw = items.getOrDefault("raw_iron", 0);
					if (ingots >= 1) b[Mind.CRAFT] += 0.5f * s;
					else if (raw >= 1) b[Mind.SMELT] += 0.45f * s;
					else b[Mind.MINE] += 0.3f * s;
				}
				case IRON -> b[Mind.MINE] += (items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0) == 0 ? 0.5f : 0.3f) * s;
				case COAL, DIAMONDS -> b[Mind.MINE] += 0.3f * s;
				case FOOD -> b[Mind.FOOD] += 0.35f * s;
				case VILLAGE -> {                                                // (free, it goes and has a look: Visits)
					b[Mind.MINE] -= 0.2f * s;
					b[Mind.EXPLORE] += 0.15f * s;
				}
			}
		}
	}

	/** For /xen status. */
	String describe() {
		if (aims.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("plans: ");
		for (int i = 0; i < aims.size(); i++) sb.append(i == 0 ? "" : ", then ").append(aims.get(i).what);
		return sb.toString();
	}
}
