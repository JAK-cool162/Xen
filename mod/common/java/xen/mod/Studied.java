package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a Xen learns from a schematic it studies (or builds), not just the copy. From a house: its style, which kind of
 * material goes where (the walls, the frame, the base, the roof, the floor, the windows, the door, the lights), and what
 * was inside (beds, chests, barrels, lanterns...). From a machine or a farm: how its parts join, as rules ("a hopper
 * pointing down sits on a furnace", "a chest sits on a hopper"), counted. No neural network: counts, kept with the rest
 * of its experience ({@link BtbLearner}).
 *
 * <p>Its own houses then use a style it learned (in survival, each part from what it can get: stone where the style has
 * stone, its own wood where it has wood), furnished the way it saw; its own machines use the rules (see the smelter).
 */
final class Studied {
	private Studied() {}

	/** The parts of a style, in the order of {@link Architect.Palette}'s fields after the name. */
	static final String[] PARTS = {"wall", "frame", "base", "roof", "floor", "window", "door", "fence", "trapdoor", "light"};

	private static String bare(String spec) {
		int b = spec.indexOf('[');
		return b < 0 ? spec : spec.substring(0, b);
	}

	private static String prop(String spec, String key) {
		int i = spec.indexOf(key + "=");
		if (i < 0) return null;
		int e = spec.indexOf(',', i);
		if (e < 0) e = spec.indexOf(']', i);
		return spec.substring(i + key.length() + 1, e < 0 ? spec.length() : e);
	}

	private static boolean log(String n) {
		return n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_stem") || n.endsWith("_hyphae") || n.equals("bamboo_block");
	}

	private static boolean terrain(String n) {
		return switch (n) {
			case "grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt", "sand", "red_sand", "gravel", "clay", "snow", "snow_block",
					"stone", "diorite", "andesite", "granite", "deepslate", "tuff", "water", "lava", "short_grass", "tall_grass", "dirt_path", "farmland", "ice" -> true;
			default -> n.endsWith("_leaves") || n.endsWith("_ore") || n.endsWith("fern") || n.endsWith("_flower") || n.equals("poppy") || n.equals("dandelion");
		};
	}

	private static <K> K most(Map<K, Integer> m) {
		K best = null;
		int n = 0;
		for (var e : m.entrySet()) {
			if (e.getValue() > n) {
				n = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/**
	 * Its style, or null if it isn't a building (no door, no windows and no roof to speak of: a machine, a farm). Each
	 * part is the block it used most for that part: the base in the lowest third, the walls in the middle, the roof's
	 * stairs and slabs up top.
	 */
	static String[] style(Schematic s) {
		Map<String, Integer> base = new HashMap<>(), wall = new HashMap<>(), frame = new HashMap<>(), roof = new HashMap<>(), floor = new HashMap<>(),
				window = new HashMap<>(), door = new HashMap<>(), fence = new HashMap<>(), trap = new HashMap<>(), light = new HashMap<>();
		int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
		for (Schematic.Block b : s.blocks) {
			if (terrain(bare(b.spec()))) continue;
			lowest = Math.min(lowest, b.at().getY());
			highest = Math.max(highest, b.at().getY());
		}
		if (lowest == Integer.MAX_VALUE) return null;
		int span = Math.max(1, highest - lowest + 1);
		for (Schematic.Block b : s.blocks) {
			String spec = b.spec(), n = bare(spec);
			if (terrain(n)) continue;
			double h = (b.at().getY() - lowest) / (double) span;
			var st = Architect.state(spec);
			boolean full = st != null && st.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
					&& !(st.getBlock() instanceof net.minecraft.world.level.block.EntityBlock);
			if (n.endsWith("_door") && !n.endsWith("trapdoor")) door.merge(n, 1, Integer::sum);
			else if (n.endsWith("_trapdoor")) trap.merge(n, 1, Integer::sum);
			else if (n.endsWith("_fence")) fence.merge(n, 1, Integer::sum);
			else if (n.contains("glass")) window.merge(n, 1, Integer::sum);
			else if (n.equals("lantern") || n.equals("soul_lantern") || n.equals("torch") || n.equals("wall_torch") || n.equals("sea_lantern") || n.equals("glowstone")
					|| n.equals("end_rod") || n.equals("froglight") || n.endsWith("_froglight")) light.merge(n.equals("wall_torch") ? "torch" : n, 1, Integer::sum);
			else if ((n.endsWith("_stairs") || n.endsWith("_slab")) && h >= 0.5) roof.merge(n.substring(0, n.lastIndexOf('_')), 1, Integer::sum);
			else if (n.endsWith("_planks") || n.endsWith("_slab") && h < 0.34) floor.merge(n.endsWith("_slab") ? n.substring(0, n.length() - 5) + (n.contains("stone") || n.contains("brick") || n.contains("deepslate") ? "" : "_planks") : n, 1, Integer::sum);
			if (!full) continue;
			if (log(n)) frame.merge(n, 1, Integer::sum);
			if (h < 0.34) base.merge(n, 1, Integer::sum);
			else if (h < 0.75) wall.merge(n, 1, Integer::sum);
		}
		if (door.isEmpty() && window.isEmpty() && roof.isEmpty()) return null;
		String fr = most(frame), w = most(wall), bs = most(base);
		if (w != null && w.equals(fr) && wall.size() > 1) {                    // (the frame's logs are the most of the walls: the walls are what's between them)
			Map<String, Integer> rest = new HashMap<>(wall);
			rest.remove(fr);
			w = most(rest);
		}
		String rf = most(roof);
		return new String[] {w, fr, bs, rf, most(floor), window.isEmpty() ? "air" : most(window), most(door), most(fence), most(trap), most(light)};
	}

	/** What was inside: furniture and fittings it saw (beds, chests, barrels, lanterns, carpets, pots...), counted. */
	static Map<String, Integer> interior(Schematic s) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Schematic.Block b : s.blocks) {
			String spec = b.spec(), n = bare(spec);
			if (spec.contains("part=head") || spec.contains("half=upper") && !n.endsWith("_trapdoor") && !n.endsWith("_stairs")) continue;
			boolean fitting = n.endsWith("_bed") || n.equals("chest") || n.equals("barrel") || n.equals("crafting_table") || n.equals("furnace") || n.equals("smoker")
					|| n.equals("blast_furnace") || n.equals("bookshelf") || n.equals("lectern") || n.equals("lantern") || n.endsWith("_carpet") || n.startsWith("potted_")
					|| n.equals("flower_pot") || n.equals("campfire") || n.equals("anvil") || n.equals("cartography_table") || n.equals("loom") || n.equals("composter")
					|| n.equals("brewing_stand") || n.equals("enchanting_table") || n.equals("jukebox") || n.equals("armor_stand") || n.endsWith("_banner") || n.equals("cauldron");
			if (fitting) out.merge(n, 1, Integer::sum);
		}
		return out;
	}

	/** A block in a rule: its name, and where it points if it has a facing ("hopper>down"). */
	private static String part(String spec) {
		String n = bare(spec), f = prop(spec, "facing");
		return f == null ? n : n + ">" + f;
	}

	private static final java.util.Set<String> MACHINE = java.util.Set.of("hopper", "chest", "trapped_chest", "barrel", "furnace", "blast_furnace", "smoker",
			"dropper", "dispenser", "observer", "piston", "sticky_piston", "redstone_wire", "repeater", "comparator", "redstone_torch", "redstone_wall_torch",
			"lever", "stone_button", "redstone_block", "redstone_lamp", "composter", "farmland", "water", "brewing_stand", "crafter", "rail", "powered_rail",
			"detector_rail", "hopper_minecart", "note_block", "target", "daylight_detector", "tripwire_hook", "cauldron");

	/** How its machine parts join: "hopper>down above furnace>east" and the like, counted (two machine parts side by side). */
	static Map<String, Integer> rules(Schematic s) {
		Map<BlockPos, String> at = new HashMap<>();
		for (Schematic.Block b : s.blocks) at.put(b.at(), b.spec());
		Map<String, Integer> out = new HashMap<>();
		for (Schematic.Block b : s.blocks) {
			if (!MACHINE.contains(bare(b.spec()))) continue;
			for (Direction d : Direction.values()) {
				String other = at.get(b.at().relative(d));
				if (other == null || !MACHINE.contains(bare(other))) continue;
				String where = d == Direction.UP ? "below" : d == Direction.DOWN ? "above" : "beside";   // (b is below what's up from it...)
				if (d == Direction.UP) out.merge(part(other) + " above " + part(b.spec()), 1, Integer::sum);
				else if (d == Direction.DOWN) continue;                        // (the same pair, counted from the other side)
				else if (d == Direction.EAST || d == Direction.SOUTH) out.merge(part(b.spec()) + " " + where + " " + part(other) + " (" + d.getName() + ")", 1, Integer::sum);
			}
		}
		return out;
	}

	/** Studies it: into its experience; what it learned, in a line. */
	static String study(Schematic s, BtbLearner learner) {
		StringBuilder sb = new StringBuilder("studies the " + s.name + " schematic");
		String[] st = style(s);
		if (st != null) {
			learner.learnStyle(s.name, st);
			sb.append(String.format(Locale.ROOT, "; style: walls %s, frame %s, base %s, roof %s, floor %s, windows %s, door %s, light %s",
					st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[9]));
		}
		Map<String, Integer> in = interior(s);
		if (!in.isEmpty()) {
			learner.learnInterior(s.name, in);
			sb.append("; inside: ").append(in.entrySet().stream().limit(8).map(e -> e.getValue() + " " + e.getKey().replace('_', ' ')).reduce((a, b) -> a + ", " + b).orElse(""));
		}
		Map<String, Integer> rules = rules(s);
		if (!rules.isEmpty()) {
			learner.learnRules(s.name, rules);
			sb.append("; ").append(rules.size()).append(" ways its parts join (")
					.append(rules.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3).map(e -> e.getKey() + " x" + e.getValue())
							.reduce((a, b) -> a + "; " + b).orElse("")).append(")");
		}
		if (st == null && in.isEmpty() && rules.isEmpty()) sb.append(": nothing in it it could use");
		return sb.toString();
	}

	private static boolean stoneish(String n) {
		return n != null && (n.contains("stone") || n.contains("brick") || n.contains("deepslate") || n.contains("tuff") || n.contains("andesite")
				|| n.contains("diorite") || n.contains("granite") || n.contains("blackstone") || n.contains("calcite") || n.contains("quartz") || n.contains("prismarine"));
	}

	/**
	 * A learned style as a palette. Creative: its own blocks. Survival: the same choices from what it can get (stone
	 * where the style has stone, if it has the cobblestone; its own wood where it has wood; a stripped log frame stays
	 * stripped; glass only if it has glass; torches for its lights).
	 */
	static Architect.Palette palette(String name, String[] st, boolean creative, String wood, boolean stone, boolean glass) {
		Architect.Palette own = Architect.ofWood(wood, stone, glass);
		if (creative) {
			return new Architect.Palette("learned " + name, or(st[0], own.wall()), or(st[1], own.frame()), or(st[2], own.base()), or(st[3], own.roof()),
					or(st[4], own.floor()), or(st[5], own.window()), or(st[6], own.door()), or(st[7], own.fence()), or(st[8], own.trapdoor()), or(st[9], own.light()));
		}
		String logName = own.frame();
		String wall = stoneish(st[0]) && stone ? "cobblestone" : st[0] != null && log(st[0]) ? logName : own.wall();
		String frame = st[1] == null ? logName : st[1].startsWith("stripped_") && !logName.startsWith("stripped_") ? "stripped_" + logName : logName;
		String base = stoneish(st[2]) && stone ? "cobblestone" : st[2] != null && log(st[2]) ? logName : own.base();
		String roof = stoneish(st[3]) && stone ? "cobblestone" : own.roof();
		String floor = stoneish(st[4]) && stone ? "cobblestone" : own.floor();
		String window = "air".equals(st[5]) ? "air" : own.window();
		return new Architect.Palette("learned " + name, wall, frame, base, roof, floor, window, own.door(), own.fence(), own.trapdoor(), "torch");
	}

	private static String or(String a, String b) {
		return a == null ? b : a;
	}

	/**
	 * Furnishes a house plan the way it saw: along the inside of its walls, on the floor, not by the door, a few of the
	 * fittings it learned (the most common first, two of each at most, one per four floor blocks). They are decoration:
	 * a survival Xen leaves out what it can't make.
	 */
	static Architect.Plan furnish(Architect.Plan plan, Map<String, Integer> learned) {
		if (learned == null || learned.isEmpty() || plan.inside() == null || plan.inside().getXsize() < 2 || plan.inside().getZsize() < 2) return plan;
		Map<BlockPos, Architect.Step> at = new HashMap<>();
		for (Architect.Step st : plan.steps()) at.put(st.pos(), st);
		var box = plan.inside();
		int y = (int) Math.floor(box.minY);
		java.util.List<BlockPos> spots = new java.util.ArrayList<>();
		for (int x = (int) Math.floor(box.minX); x < (int) Math.ceil(box.maxX); x++)
			for (int z = (int) Math.floor(box.minZ); z < (int) Math.ceil(box.maxZ); z++) {
				BlockPos q = new BlockPos(x, y, z);
				Architect.Step here = at.get(q), under = at.get(q.below());
				if (here != null && !here.dig() || under == null || under.dig()) continue;   // (taken, or no floor planned under it)
				if (plan.door() != null && q.distManhattan(plan.door()) <= 2) continue;
				boolean byWall = false;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					Architect.Step w = at.get(q.relative(d));
					byWall |= w != null && !w.dig() && w.phase() <= Architect.WALLS;
				}
				if (byWall) spots.add(q);
			}
		if (spots.isEmpty()) return plan;
		int room = Math.max(1, spots.size() / 4);
		List<Architect.Step> steps = new java.util.ArrayList<>(plan.steps());
		int used = 0;
		for (var e : learned.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList()) {
			String n = e.getKey();
			if (n.endsWith("_bed") || n.equals("campfire") || n.endsWith("_banner") || n.equals("armor_stand")) continue;   // (two blocks, fire, or not a block)
			for (int k = 0; k < Math.min(2, e.getValue()) && used < room && used < spots.size(); k++) {
				var st = Architect.state(n);
				if (st == null) continue;
				steps.add(new Architect.Step(spots.get(used * spots.size() / room % spots.size()), st, Architect.INSIDE, true));
				used++;
			}
		}
		return new Architect.Plan(plan.name(), steps, plan.door(), plan.middle(), plan.front(), plan.inside());
	}
}
