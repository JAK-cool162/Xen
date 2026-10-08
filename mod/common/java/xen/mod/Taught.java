package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a player showed Xen with the Build Axe and we trained it on (assets/xen/taught: the lines of JSON the axe
 * writes): builds, furniture, the things round a house. Every Xen knows them:
 * <ul>
 *   <li>the houses are houses it may build: on its own, when it likes copying (with the village houses it has seen),
 *   or asked for by name ("build a 2 story house");</li>
 *   <li>anything shown can be asked for by name: "build a pool", "build the bar", "make a couch";</li>
 *   <li>the furniture (a couch, a table, a bar, a counter) goes in a house it finishes, if a piece fits.</li>
 * </ul>
 * The ground a build was shown on (grass and dirt under it, the grass growing on it) isn't part of it: it goes on the
 * ground where it's built.
 */
public final class Taught {
	private Taught() {}

	/** A thing it was shown: its kind and name, size, blocks (air too: inside a house, dug out), ground left off. */
	record Piece(String kind, String name, int sx, int sy, int sz, List<VillageHouses.Block> blocks, VillageHouses.House house, String biome) {
		boolean furniture() {
			return kind.equals("decoration") && sy <= 4 && !water() || FURNITURE.matcher(name).find() && sy <= 3 && !water();
		}

		boolean water() {
			for (VillageHouses.Block b : blocks) if (b.name().equals("water")) return true;
			return false;
		}
	}

	/** Ground it was standing on, and what grows on it: not the build's. */
	private static final Set<String> GROUND = Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "mycelium", "mud", "sand", "gravel");
	private static final Set<String> PLANTS = Set.of("short_grass", "tall_grass", "fern", "large_fern");
	private static final Pattern FURNITURE = Pattern.compile("\\b(table|bar|couch|crouch|sofa|chair|bench|counter|shelf|desk|bed)\\b");
	/** Names it answers to as well (a sofa is a couch). */
	private static final Map<String, String> ALSO = Map.of("crouch", "couch", "sofa", "couch", "staircase", "stair case", "stairs", "stair case");
	/** Names too plain to mean one of these ("build a house" is its own house). */
	private static final Set<String> PLAIN = Set.of("house", "home", "base", "farm", "tower", "hut", "shelter");

	private static List<Piece> pieces;

	static synchronized List<Piece> pieces() {
		if (pieces == null) pieces = load();
		return pieces;
	}

	private static List<Piece> load() {
		List<Piece> out = new ArrayList<>();
		try (InputStream index = Taught.class.getResourceAsStream("/assets/xen/taught/index.txt")) {
			if (index == null) return out;
			for (String file : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\s+")) {
				if (file.isBlank()) continue;
				try (InputStream in = Taught.class.getResourceAsStream("/assets/xen/taught/" + file)) {
					if (in == null) continue;
					BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
					for (String line; (line = r.readLine()) != null; ) {
						if (line.isBlank()) continue;
						try {
							Piece p = piece(JsonParser.parseString(line).getAsJsonObject());
							if (p != null) out.add(p);
						} catch (RuntimeException e) {
							XenMod.LOG.warn("Xen: a line of {} it can't read: {}", file, e.toString());
						}
					}
				}
			}
		} catch (java.io.IOException e) {
			XenMod.LOG.warn("Xen: couldn't read what it was shown: {}", e.toString());
		}
		XenMod.LOG.info("Xen knows {} things it was shown ({} houses, {} pieces of furniture)", out.size(),
				out.stream().filter(p -> p.house() != null).count(), out.stream().filter(Piece::furniture).count());
		return out;
	}

	/** A line of the Build Axe's JSON as a piece: its palette and an index per block (x fastest), the ground left off. */
	private static Piece piece(JsonObject o) {
		JsonArray size = o.getAsJsonArray("size"), pal = o.getAsJsonArray("palette"), cells = o.getAsJsonArray("blocks");
		int sx = size.get(0).getAsInt(), sy = size.get(1).getAsInt(), sz = size.get(2).getAsInt();
		String[] names = new String[pal.size()], props = new String[pal.size()];
		for (int i = 0; i < pal.size(); i++) {
			String k = pal.get(i).getAsString();
			int b = k.indexOf('[');
			names[i] = b < 0 ? k : k.substring(0, b);
			props[i] = b < 0 ? "" : k.substring(b + 1, k.length() - 1);
		}
		int[] at = new int[sx * sy * sz];
		for (int i = 0; i < at.length; i++) at[i] = cells.get(i).getAsInt();
		boolean[] ground = new boolean[at.length];
		for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
			for (int y = 0; y < sy; y++) {                                          // the ground it stood on: from the bottom up
				int c = (y * sz + z) * sx + x;
				if (!GROUND.contains(names[at[c]])) break;
				ground[c] = true;
			}
		}
		List<VillageHouses.Block> blocks = new ArrayList<>();
		for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
			int c = (y * sz + z) * sx + x, i = at[c];
			if (ground[c] || PLANTS.contains(names[i])) continue;
			blocks.add(new VillageHouses.Block(x, y, z, names[i].equals("cave_air") ? "air" : names[i], props[i]));
		}
		int[] grown = {sy};
		blocks = mendDoor(blocks, grown);                                         // (marked a block too high: the door's lower half and the bottom row of wall)
		sy = grown[0];
		String kind = o.get("kind").getAsString(), name = o.get("name").getAsString().trim().toLowerCase(Locale.ROOT);
		if (name.equals("crouch")) name = "couch";                               // (what was meant)
		boolean solid = blocks.stream().anyMatch(b -> !b.name().equals("air"));
		if (!solid) return null;
		boolean houseLike = !kind.equals("tree") && (blocks.stream().anyMatch(b -> b.name().endsWith("_door")) || name.matches(".*\\bhouse\\b.*"));
		VillageHouses.House h = VillageHouses.house("taught/" + name, "taught", blocks, sx, sy, sz, true);
		String biome = VillageHouses.kindOf(o.has("biome") ? o.get("biome").getAsString() : "plains");   // (plains, desert, savanna, taiga, snowy)
		return new Piece(kind, name, sx, sy, sz, blocks, houseLike ? h : null, biome);
	}

	// ------------------------------------------------------------------------------ a style, not a copy
	/**
	 * What it learned from the things it was shown for building its own (not copies of them): what a road is made of
	 * (and its slab), a wall (its main block, its accent for posts, its cap), the light, the post a lamp stands on, a
	 * bench and a table top, a bush, a pool's rim and floor. Its wood goes for whatever is wooden.
	 */
	record Style(String road, String roadSlab, String wall, String accent, String cap, String light, String post, String bench, String top,
			String bush, String rim, String floor) {
		/** The same in its own wood (fences, stairs, plates, planks and logs of another wood: its own). */
		Style in(String wood) {
			return new Style(VillageHouses.adaptWood(road, wood), VillageHouses.adaptWood(roadSlab, wood), VillageHouses.adaptWood(wall, wood),
					VillageHouses.adaptWood(accent, wood), VillageHouses.adaptWood(cap, wood), light, VillageHouses.adaptWood(post, wood),
					VillageHouses.adaptWood(bench, wood), VillageHouses.adaptWood(top, wood), bush, rim, floor);
		}
	}

	private static final Map<String, Style> STYLES = new java.util.HashMap<>();

	/** Its style for a kind of place (plains, desert, savanna, taiga, snowy: VillageHouses.kindOf): learned from what it was shown there first. */
	static synchronized Style style(String kind) {
		return STYLES.computeIfAbsent(kind, Taught::learnStyle);
	}

	private static boolean full(String n) {
		return !(n.equals("air") || n.endsWith("_slab") || n.endsWith("_stairs") || n.endsWith("_fence") || n.endsWith("_wall") || n.endsWith("_trapdoor")
				|| n.endsWith("_door") || n.endsWith("_pressure_plate") || n.endsWith("_carpet") || n.endsWith("_sign") || n.endsWith("_button")
				|| n.contains("pot") || n.contains("lantern") || n.contains("torch") || n.endsWith("_leaves") || n.endsWith("_pane") || n.equals("water")
				|| n.contains("chain") || n.equals("glowstone") || n.equals("crafting_table") || n.equals("furnace") || GROUND.contains(n) || PLANTS.contains(n)
				|| n.equals("moss_block") || n.equals("fern") || n.equals("snow") || n.equals("powder_snow") || n.contains("ice"));
	}

	/** The most common block in these pieces that passes the test (or null). */
	private static String most(List<Piece> from, java.util.function.Predicate<String> ok, int skip) {
		Map<String, Integer> n = new java.util.HashMap<>();
		for (Piece p : from) for (VillageHouses.Block b : p.blocks()) if (ok.test(b.name())) n.merge(b.name(), 1, Integer::sum);
		return n.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).skip(skip).map(Map.Entry::getKey).findFirst().orElse(null);
	}

	/** A block's slab, or the full block of a slab ("andesite" and "andesite_slab", "stone_bricks" and "stone_brick_slab", "oak_planks" and "oak_slab"). */
	static String slabOf(String n) {
		if (n == null) return null;
		String base = n.endsWith("_planks") ? n.substring(0, n.length() - 7) : n.endsWith("_bricks") ? n.substring(0, n.length() - 1) : n.endsWith("_tiles") ? n.substring(0, n.length() - 1) : n;
		return Architect.state(base + "_slab") != null ? base + "_slab" : null;
	}

	static String fullOf(String slab) {
		if (slab == null || !slab.endsWith("_slab")) return slab;
		String base = slab.substring(0, slab.length() - 5);
		for (String t : new String[] {base, base + "s", base + "_planks"}) if (Architect.state(t) != null) return t;
		return "cobblestone";
	}

	/** The pieces of this kind of place if there are any, else all of them. */
	private static List<Piece> here(List<Piece> from, String kind) {
		List<Piece> h = from.stream().filter(p -> p.biome().equals(kind)).toList();
		return h.isEmpty() ? from : h;
	}

	private static Style learnStyle(String kind) {
		List<Piece> all = pieces(), roads = new ArrayList<>(), walls = new ArrayList<>(), pools = new ArrayList<>(), outdoor = new ArrayList<>();
		for (Piece p : all) {
			if (p.kind().equals("tree")) continue;
			if (p.name().contains("road") || p.name().contains("path")) roads.add(p);
			if (p.name().contains("wall") || p.name().contains("entrance") || p.name().contains("gate")) walls.add(p);
			if (p.name().contains("pool")) pools.add(p);
			if (p.house() == null) outdoor.add(p);
		}
		if (pools.isEmpty()) for (Piece p : all) if (p.water()) pools.add(p);
		List<Piece> built = all.stream().filter(p -> p.biome().equals(kind) && !p.kind().equals("tree")).toList();
		roads = here(roads, kind);
		outdoor = here(outdoor, kind);
		pools = here(pools, kind);
		boolean ownWalls = walls.stream().anyMatch(p -> p.biome().equals(kind));
		walls = ownWalls || built.isEmpty() ? here(walls, kind) : built;          // (no wall shown here: what the buildings here are made of)
		String r = most(roads, n -> !n.equals("air") && !GROUND.contains(n) && !PLANTS.contains(n), 0);
		String road = r == null ? "gravel" : r.endsWith("_slab") ? fullOf(r) : r, roadSlab = r != null && r.endsWith("_slab") ? r : slabOf(road);
		String wall = most(walls, Taught::full, 0), accent = most(walls, Taught::full, 1);
		if (wall == null) wall = "cobblestone";
		if (accent == null) accent = Architect.state("polished_" + wall) != null ? "polished_" + wall : wall;
		String cap = slabOf(wall) != null ? slabOf(wall) : roadSlab;
		String light = most(all, n -> n.equals("lantern") || n.equals("glowstone") || n.endsWith("torch") || n.equals("sea_lantern") || n.equals("soul_lantern"), 0);
		if (light != null) light = light.replace("wall_torch", "torch");                // (on top of a post: a torch that stands)
		String post = most(all, n -> n.endsWith("_fence"), 0), bench = most(outdoor, n -> n.endsWith("_stairs"), 0);
		String top = most(outdoor, n -> n.endsWith("_pressure_plate") || n.endsWith("_carpet"), 0), bush = most(all, n -> n.endsWith("_leaves") || n.equals("moss_block"), 0);
		String rim = most(pools, n -> Taught.full(n) || n.endsWith("glazed_terracotta"), 0), floor = most(pools, n -> Taught.full(n) || n.endsWith("glazed_terracotta"), 1);
		Style s = new Style(road, roadSlab == null ? "cobblestone_slab" : roadSlab, wall, accent, cap == null ? "cobblestone_slab" : cap, light == null ? "lantern" : light,
				post == null ? "oak_fence" : post, bench == null ? "oak_stairs" : bench, top == null ? "oak_pressure_plate" : top, bush == null ? "oak_leaves" : bush,
				rim == null ? "stone_bricks" : rim, floor == null ? (rim == null ? "stone_bricks" : rim) : floor);
		XenMod.LOG.info("Xen's style for {} from what it was shown: {}", kind, s);
		return s;
	}

	// ------------------------------------------------------------------------------ trees: their shape, not a copy
	/** A tree's shape, learned from one it was shown: its log and leaves, the trunk's height, and how wide the leaves are at each height from the trunk's foot. */
	record TreeShape(String log, String leaves, int trunk, int[] radius, String biome) {}

	private static List<TreeShape> trees;

	static synchronized List<TreeShape> trees() {
		if (trees != null) return trees;
		trees = new ArrayList<>();
		for (Piece p : pieces()) {
			if (!p.kind().equals("tree")) continue;
			Map<Long, Integer> logs = new java.util.HashMap<>();
			String log = null, leaves = null;
			for (VillageHouses.Block b : p.blocks()) {
				if (b.name().endsWith("_log") || b.name().endsWith("_stem")) {
					logs.merge(BlockPos.asLong(b.x(), 0, b.z()), 1, Integer::sum);
					log = b.name();
				}
				if (b.name().endsWith("_leaves") || b.name().endsWith("_wart_block")) leaves = b.name();
			}
			if (log == null || leaves == null) continue;
			long col = logs.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
			int tx = BlockPos.getX(col), tz = BlockPos.getZ(col), foot = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
			for (VillageHouses.Block b : p.blocks()) {
				if (b.x() == tx && b.z() == tz && b.name().equals(log)) foot = Math.min(foot, b.y());
				if (b.name().equals(leaves)) top = Math.max(top, b.y());
			}
			if (foot == Integer.MAX_VALUE || top < foot) continue;
			int[] radius = new int[top - foot + 1];
			Arrays.fill(radius, -1);
			for (VillageHouses.Block b : p.blocks()) {
				if (!b.name().equals(leaves) || b.y() < foot) continue;
				radius[b.y() - foot] = Math.max(radius[b.y() - foot], Math.max(Math.abs(b.x() - tx), Math.abs(b.z() - tz)));
			}
			TreeShape t = new TreeShape(log, leaves, logs.get(col), radius, p.biome());
			trees.add(t);
			XenMod.LOG.info("Xen learned a tree's shape: {} {} high, leaves {}", log, t.trunk(), Arrays.toString(radius));
		}
		return trees;
	}

	/**
	 * A door with only its upper half in the bottom layer: the box was marked a block too high, the door's lower half and
	 * the bottom row of the walls were left out. It puts them back (each solid block of the bottom layer once more under
	 * it, the door whole), or the house is built with a hole a block high for a door: no way in or out.
	 */
	private static List<VillageHouses.Block> mendDoor(List<VillageHouses.Block> blocks, int[] sy) {
		int lowest = Integer.MAX_VALUE;
		for (VillageHouses.Block b : blocks) if (!b.name().equals("air")) lowest = Math.min(lowest, b.y());
		boolean cut = false;
		for (VillageHouses.Block b : blocks) {
			if (!b.name().endsWith("_door") || !b.props().contains("half=upper") || b.y() != lowest) continue;
			boolean below = false;
			for (VillageHouses.Block o : blocks) below |= o.x() == b.x() && o.z() == b.z() && o.y() == b.y() - 1 && o.name().endsWith("_door");
			cut |= !below;
		}
		if (!cut) return blocks;
		int shift = lowest == 0 ? 1 : 0;
		List<VillageHouses.Block> out = new ArrayList<>();
		for (VillageHouses.Block b : blocks) {
			out.add(new VillageHouses.Block(b.x(), b.y() + shift, b.z(), b.name(), b.props()));
			if (b.y() != lowest) continue;
			int y = lowest - 1 + shift;
			if (b.name().endsWith("_door")) out.add(new VillageHouses.Block(b.x(), y, b.z(), b.name(), b.props().replace("half=upper", "half=lower")));
			else if (full(b.name()) || b.name().endsWith("_log") || b.name().endsWith("_planks")) out.add(new VillageHouses.Block(b.x(), y, b.z(), b.name(), b.props()));
			else if (b.name().equals("air")) out.add(new VillageHouses.Block(b.x(), y, b.z(), "air", ""));
		}
		sy[0] += shift;
		return out;
	}

	/** The houses it was shown, to build like the village houses it saw. */
	static List<VillageHouses.House> houses() {
		List<VillageHouses.House> out = new ArrayList<>();
		for (Piece p : pieces()) if (p.house() != null) out.add(p.house());
		return out;
	}

	/** The houses it was shown in this kind of place (a desert house in the desert), or all of them if none. */
	static List<VillageHouses.House> houses(String kind) {
		List<VillageHouses.House> out = new ArrayList<>();
		for (Piece p : pieces()) if (p.house() != null && p.biome().equals(kind)) out.add(p.house());
		return out.isEmpty() ? houses() : out;
	}

	/** Anything it was shown, as a build (a house, or a pool, a bar, a road), asked for by name: null if it knows none by that name. */
	static VillageHouses.House find(String what) {
		if (what == null) return null;
		String w = what.toLowerCase(Locale.ROOT).replaceFirst("^taught:", "").trim();
		for (Map.Entry<String, String> e : ALSO.entrySet()) if (w.equals(e.getKey())) w = e.getValue();
		List<Piece> fits = new ArrayList<>();
		for (Piece p : pieces()) if (p.name().equals(w) && !p.kind().equals("tree")) fits.add(p);   // (a tree it grows its own: Taught.trees)
		if (fits.isEmpty()) return null;
		Piece p = fits.get(new Random().nextInt(fits.size()));                    // (two couches: either)
		return VillageHouses.house("taught/" + p.name(), "taught", p.blocks(), p.sx(), p.sy(), p.sz(), true);
	}

	/**
	 * Its own version of a build it was shown, not a copy: at some sites the other way round (mirrored: left is right),
	 * always the same at the same site (so a house it comes back to finish is the same house). Its wood is its own too
	 * (see VillageHouses.adaptWood).
	 */
	static VillageHouses.House vary(VillageHouses.House h, BlockPos corner) {
		if (h == null || !h.kind().equals("taught") || ((corner.asLong() * 31 + h.name().hashCode()) & 2) == 0) return h;
		List<VillageHouses.Block> out = new ArrayList<>();
		for (VillageHouses.Block b : h.blocks()) {
			String props = b.props();
			BlockState st = b.name().equals("air") ? null : Architect.state(b.name() + (props.isEmpty() ? "" : "[" + props + "]"));
			if (st != null) {
				String t = st.mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK).toString();   // Block{minecraft:oak_stairs}[facing=west,...]
				int i = t.indexOf("}["), j = t.lastIndexOf(']');
				props = i < 0 || j <= i ? "" : t.substring(i + 2, j);
			}
			out.add(new VillageHouses.Block(h.sx() - 1 - b.x(), b.y(), b.z(), b.name(), props));
		}
		return VillageHouses.house(h.id(), h.kind(), out, h.sx(), h.sy(), h.sz(), true);
	}

	/** The name of a thing it was shown that the words ask for ("build a pool", "make the 2 story house"), else null. */
	public static String nameIn(String words) {
		String w = words.toLowerCase(Locale.ROOT), best = null;
		for (Piece p : pieces()) {
			if (PLAIN.contains(p.name()) || p.kind().equals("tree")) continue;
			if (Pattern.compile("\\b" + Pattern.quote(p.name()) + "\\b").matcher(w).find() && (best == null || p.name().length() > best.length())) best = p.name();
		}
		if (best == null) for (Map.Entry<String, String> e : ALSO.entrySet()) if (Pattern.compile("\\b" + e.getKey() + "\\b").matcher(w).find()) best = e.getKey();
		return best;
	}

	/**
	 * A piece of furniture it was shown for the house it just finished: somewhere on the floor inside where all of it
	 * fits (in the air there, on the floor, not in front of the door), turned either way. Its blocks go in if it has
	 * them (in survival, what it can't make stays out). Null: none fits.
	 */
	static Architect.Plan furnish(ServerLevel level, Architect.Plan house, Random r) {
		AABB in = house.inside();
		if (in == null) return null;
		List<Piece> all = new ArrayList<>();
		for (Piece p : pieces()) if (p.furniture()) all.add(p);
		java.util.Collections.shuffle(all, r);
		int x0 = (int) Math.floor(in.minX), x1 = (int) Math.floor(in.maxX), z0 = (int) Math.floor(in.minZ), z1 = (int) Math.floor(in.maxZ), y0 = (int) Math.floor(in.minY);
		for (Piece p : all) {
			for (Rotation rot : new Rotation[] {Rotation.NONE, Rotation.CLOCKWISE_90}) {
				int w = rot == Rotation.NONE ? p.sx() : p.sz(), d = rot == Rotation.NONE ? p.sz() : p.sx();
				List<int[]> spots = new ArrayList<>();
				for (int x = x0; x + w - 1 <= x1; x++) for (int z = z0; z + d - 1 <= z1; z++) spots.add(new int[] {x, z});
				java.util.Collections.shuffle(spots, r);
				for (int[] s : spots) {
					for (int y = y0 - 1; y <= y0 + 1; y++) {                            // (a house with no floor of its own stands on the ground: a layer lower)
						List<Architect.Step> steps = place(level, p, rot, new BlockPos(s[0], y, s[1]), house.door());
						if (steps != null) return new Architect.Plan(p.name(), steps, house.door(), house.middle(), house.front(), in);
					}
				}
			}
		}
		return null;
	}

	/** The piece at that corner, if all of it fits there; else null. */
	private static List<Architect.Step> place(ServerLevel level, Piece p, Rotation rot, BlockPos corner, BlockPos door) {
		List<Architect.Step> steps = new ArrayList<>();
		int lowest = Integer.MAX_VALUE;
		for (VillageHouses.Block b : p.blocks()) if (!b.name().equals("air")) lowest = Math.min(lowest, b.y());
		for (VillageHouses.Block b : p.blocks()) {
			if (b.name().equals("air")) continue;
			int x = rot == Rotation.NONE ? b.x() : p.sz() - 1 - b.z(), z = rot == Rotation.NONE ? b.z() : b.x();
			BlockPos at = corner.offset(x, b.y() - lowest, z);
			if (!level.getBlockState(at).isAir() || at.distManhattan(door) <= 2) return null;   // (something there, or by the door)
			if (b.y() == lowest && level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty()) return null;   // (on the floor)
			BlockState st = Architect.state(b.name() + (b.props().isEmpty() ? "" : "[" + b.props() + "]"));
			if (st == null) continue;
			steps.add(new Architect.Step(at, st.rotate(rot), Architect.INSIDE, true));
		}
		return steps.isEmpty() ? null : steps;
	}
}
