package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;

/**
 * Xen's designs: a cottage and an underground base, built the way good builders do it (from building guides): a stone
 * base, a log frame with posts every few blocks, plank walls with the windows set between the posts, a steep stair
 * roof that overhangs the walls, a beam across the room with a lantern under it; inside a bed, a crafting table, a
 * furnace and a chest, a lamp, a table and a chair made of a fence, a pressure plate and stairs; outside shutters by
 * the windows (open trapdoors), a step up to the door, lamp posts and flowers. The underground base is a staircase down
 * to a room carved out of the stone, with log pillars in the corners, ceiling beams with lanterns under them, a plank
 * floor, and its storage, workshop and bed along the walls.
 * <p>A plan is only blocks and where they go, in the order a builder would do them. {@link Builder} does the work,
 * with the Xen's own hands.
 */
final class Architect {
	/** Work in the order a builder does it. */
	static final int DIG = 0, SUPPORT = 1, FRAME = 2, WALLS = 3, ROOF = 4, INSIDE = 5, OUTSIDE = 6, DOORS = 7;
	static final String[] PHASES = {"clearing the ground", "laying the foundation", "putting up the frame", "building the walls",
			"putting on the roof", "furnishing it", "decorating outside", "hanging the door"};   // (the door last: it works in and out till then)

	/**
	 * One block of a plan: where, what (null means dig it out), when, and whether it's only decoration (a survival Xen
	 * leaves decoration out when it has nothing to make it from).
	 */
	record Step(BlockPos pos, BlockState state, int phase, boolean decor) {
		boolean dig() {
			return state == null;
		}
	}

	/**
	 * A plan: its steps, what it is, where its door and middle are (to stand, to go home to), and the space inside
	 * (under the roof: a builder puts the roof on from outside, not from the attic, or it shuts itself in).
	 */
	record Plan(String name, List<Step> steps, BlockPos door, BlockPos middle, Direction front, net.minecraft.world.phys.AABB inside) {}

	/**
	 * Materials that go together (a guide's rule: two families and an accent): walls, the frame (a log), the base,
	 * the roof (stairs and slabs of it), the floor, the window, the door, fences and trapdoors, and the light.
	 */
	record Palette(String name, String wall, String frame, String base, String roof, String floor, String window, String door,
			String fence, String trapdoor, String light) {}

	static final Map<String, Palette> PALETTES = new LinkedHashMap<>();
	static {
		PALETTES.put("oak", new Palette("oak", "oak_planks", "oak_log", "cobblestone", "spruce", "spruce_planks", "glass_pane", "spruce_door",
				"spruce_fence", "spruce_trapdoor", "lantern"));
		PALETTES.put("spruce", new Palette("spruce", "spruce_planks", "stripped_spruce_log", "stone_bricks", "dark_oak", "dark_oak_planks",
				"glass_pane", "dark_oak_door", "dark_oak_fence", "dark_oak_trapdoor", "lantern"));
		PALETTES.put("birch", new Palette("birch", "birch_planks", "birch_log", "stone_bricks", "oak", "oak_planks", "glass_pane", "birch_door",
				"oak_fence", "oak_trapdoor", "lantern"));
		PALETTES.put("medieval", new Palette("medieval", "white_terracotta", "dark_oak_log", "cobblestone", "spruce", "spruce_planks", "glass_pane",
				"spruce_door", "spruce_fence", "spruce_trapdoor", "lantern"));
		PALETTES.put("stone", new Palette("stone", "stone_bricks", "dark_oak_log", "cobblestone", "deepslate_tile", "spruce_planks", "glass_pane",
				"dark_oak_door", "dark_oak_fence", "dark_oak_trapdoor", "lantern"));
		PALETTES.put("desert", new Palette("desert", "smooth_sandstone", "stripped_jungle_log", "sandstone", "smooth_sandstone", "birch_planks",
				"glass_pane", "jungle_door", "jungle_fence", "jungle_trapdoor", "lantern"));
	}

	/**
	 * A palette from one kind of wood (what a survival Xen can make itself): planks walls, the log for the frame,
	 * cobblestone for the base when it has some, the same wood's stairs on the roof, no glass (open windows) unless it
	 * has glass panes, torches for light.
	 */
	static Palette ofWood(String wood, boolean stoneBase, boolean glass) {
		String log = wood.equals("crimson") || wood.equals("warped") ? wood + "_stem" : wood.equals("bamboo") ? "bamboo_block" : wood + "_log";
		return new Palette(wood, wood + "_planks", log, stoneBase ? "cobblestone" : log, wood, wood + "_planks", glass ? "glass_pane" : "air",
				wood + "_door", wood + "_fence", wood + "_trapdoor", "torch");
	}

	private Architect() {}

	// ------------------------------------------------------------------------------------ blocks
	/** "oak_stairs[facing=north,half=top]" as a block state (unknown names or properties: left as they come). */
	static BlockState state(String spec) {
		String name = spec, props = "";
		int b = spec.indexOf('[');
		if (b >= 0) {
			name = spec.substring(0, b);
			props = spec.substring(b + 1, spec.length() - 1);
		}
		if (name.equals("air")) return null;
		var block = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).orElse(Blocks.COBBLESTONE);
		BlockState s = block.defaultBlockState();
		for (String kv : props.isEmpty() ? new String[0] : props.split(",")) {
			String[] p = kv.split("=");
			if (p.length == 2) s = with(s, p[0].trim(), p[1].trim());
		}
		return s;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState with(BlockState s, String key, String value) {
		Property property = s.getBlock().getStateDefinition().getProperty(key);
		if (property == null) return s;
		var v = property.getValue(value);
		return v.isPresent() ? s.setValue(property, (Comparable) v.get()) : s;
	}

	/** Builds a plan in local coordinates: u to the right along the front, v into the building, y up. */
	private static final class Layout {
		final BlockPos origin;
		final Direction front, back, right, left;
		final Map<BlockPos, Step> at;

		Layout(BlockPos origin, Direction front) {
			this.origin = origin;
			this.front = front;
			this.back = front.getOpposite();
			this.right = front.getCounterClockWise();
			this.left = right.getOpposite();
			this.at = new LinkedHashMap<>();
			this.dug = new LinkedHashSet<>();
		}

		private Layout(Layout of, int dy) {
			this.origin = of.origin.above(dy);
			this.front = of.front;
			this.back = of.back;
			this.right = of.right;
			this.left = of.left;
			this.at = of.at;
			this.dug = of.dug;
		}

		/** The same plan seen from dy higher (or lower): for what stands on the ground under a raised house. */
		Layout shifted(int dy) {
			return new Layout(this, dy);
		}

		BlockPos pos(int u, int v, int y) {
			return origin.relative(right, u).relative(back, v).above(y);
		}

		/** Local direction names in a spec ({F}, {B}, {R}, {L}) as world directions. */
		String dir(String spec) {
			return spec.replace("{F}", front.getName()).replace("{B}", back.getName()).replace("{R}", right.getName()).replace("{L}", left.getName())
					.replace("{U}", right.getAxis() == Direction.Axis.X ? "x" : "z").replace("{V}", back.getAxis() == Direction.Axis.X ? "x" : "z");
		}

		void put(int u, int v, int y, String spec, int phase) {
			put(u, v, y, spec, phase, false);
		}

		void put(int u, int v, int y, String spec, int phase, boolean decor) {
			BlockPos p = pos(u, v, y);
			at.put(p, new Step(p, state(dir(spec)), phase, decor));
		}

		/** Where it digs out first, even where a block goes later (a door in the way in: dug now, the door last). */
		final Set<BlockPos> dug;

		/** Is a block planned here already? */
		boolean has(int u, int v, int y) {
			Step s = at.get(pos(u, v, y));
			return s != null && s.state() != null;
		}

		void dig(int u, int v, int y) {
			BlockPos p = pos(u, v, y);
			dug.add(p);
			if (!at.containsKey(p)) at.put(p, new Step(p, null, DIG, false));
		}

		List<Step> steps() {
			List<Step> out = new ArrayList<>(at.values());
			for (BlockPos p : dug) if (at.get(p).phase() != DIG) out.add(new Step(p, null, DIG, false));
			out.sort((a, b) -> a.phase != b.phase ? Integer.compare(a.phase, b.phase)
					: a.phase == DIG ? Integer.compare(b.pos.getY(), a.pos.getY())          // digging: from the top down
					: Integer.compare(a.pos.getY(), b.pos.getY()));                           // building: from the ground up
			return out;
		}
	}

	// ---------------------------------------------------------------------------------- cottage
	/**
	 * A cottage w wide (along its front) and d deep, walls 3 high, its door in the middle of the front, facing front.
	 * origin is its front left corner at floor level (the floor is one block above the ground, on a stone base).
	 */
	static Plan cottage(BlockPos origin, Direction front, int w, int d, Palette p, boolean fancy, Random random) {
		return cottage(origin, front, w, d, p, fancy, false, random);
	}

	/**
	 * starter: a survival player's first house, built from what a few trees give (about 30 logs): the frame only at
	 * the corners, a plank top plate and a flat roof of slabs instead of a pitched one.
	 */
	static Plan cottage(BlockPos origin, Direction front, int w, int d, Palette p, boolean fancy, boolean starter, Random random) {
		Layout L = new Layout(origin, front);
		int h = 3, c = w / 2;
		// the ground, levelled like a player does it: what's in the way dug out (inside, and a strip around it to walk
		// on), and holes under the foundation filled up with dirt from the bottom (only where the ground is missing)
		for (int u = -1; u <= w; u++) {
			for (int v = -2; v <= d; v++) {
				boolean around = u < 0 || v < 0 || u >= w || v >= d;
				for (int y = around ? 0 : 1; y <= (around ? h : h + 1 + w / 2); y++) {
					if (around || u > 0 && u < w - 1 && v > 0 && v < d - 1 || y > h + 1) L.dig(u, v, y);
				}
				if (!around) for (int y = -4; y <= -1; y++) L.put(u, v, y, "dirt", SUPPORT);
			}
		}
		// the floor: a stone base around the edge, planks inside
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < d; v++) {
				boolean edge = u == 0 || v == 0 || u == w - 1 || v == d - 1;
				L.put(u, v, 0, edge ? p.base() : p.floor(), SUPPORT);
			}
		}
		// the frame: log posts at the corners and every 3 or 4 blocks (depth, like the guides say), a top plate of logs
		List<Integer> postsU = starter ? List.of(0, w - 1) : posts(w), postsV = starter ? List.of(0, d - 1) : posts(d), postsFront = new ArrayList<>(postsU);
		if (postsFront.remove(Integer.valueOf(c))) {                          // not in the doorway: a post each side of the door
			postsFront.add(c - 1);
			postsFront.add(c + 1);
			java.util.Collections.sort(postsFront);
		}
		for (int y = 1; y <= h; y++) {
			for (int u : postsFront) L.put(u, 0, y, p.frame() + "[axis=y]", FRAME);
			for (int u : postsU) L.put(u, d - 1, y, p.frame() + "[axis=y]", FRAME);
			for (int v : postsV) {
				L.put(0, v, y, p.frame() + "[axis=y]", FRAME);
				L.put(w - 1, v, y, p.frame() + "[axis=y]", FRAME);
			}
		}
		for (int u = 0; u < w; u++) {
			L.put(u, 0, h + 1, starter ? p.wall() : p.frame() + "[axis={U}]", FRAME);
			L.put(u, d - 1, h + 1, starter ? p.wall() : p.frame() + "[axis={U}]", FRAME);
		}
		for (int v = 1; v < d - 1; v++) {
			L.put(0, v, h + 1, starter ? p.wall() : p.frame() + "[axis={V}]", FRAME);
			L.put(w - 1, v, h + 1, starter ? p.wall() : p.frame() + "[axis={V}]", FRAME);
		}
		// the walls, with windows in the middle of each bay and the door in the middle of the front
		List<int[]> windows = new ArrayList<>();
		for (int y = 1; y <= h; y++) {
			for (int u = 0; u < w; u++) {
				for (int v : new int[] {0, d - 1}) {
					List<Integer> posts = v == 0 ? postsFront : postsU;
					if (posts.contains(u)) continue;
					boolean door = v == 0 && u == c && y <= 2;
					boolean window = y == 2 && isWindow(u, posts) && !(v == 0 && Math.abs(u - c) <= 0);
					if (door) continue;
					if (window) {
						if (!p.window().equals("air")) L.put(u, v, y, p.window(), WALLS);
						else L.dig(u, v, y);
						windows.add(new int[] {u, v});
					} else {
						L.put(u, v, y, p.wall(), WALLS);
					}
				}
			}
			for (int v = 1; v < d - 1; v++) {
				for (int u : new int[] {0, w - 1}) {
					if (postsV.contains(v)) continue;
					boolean window = y == 2 && isWindow(v, postsV);
					if (window) {
						if (!p.window().equals("air")) L.put(u, v, y, p.window(), WALLS);
						else L.dig(u, v, y);
					} else {
						L.put(u, v, y, p.wall(), WALLS);
					}
				}
			}
		}
		// a beam across the room, for a lantern to hang from
		int mid = d / 2;
		if (!starter) for (int u = 1; u < w - 1; u++) L.put(u, mid, h + 1, p.frame() + "[axis={U}]", FRAME, true);
		// the roof: stairs up both sides, one block over the walls all round, a slab along the ridge
		String roofStairs = stairsOf(p.roof()), roofSlab = slabOf(p.roof());
		if (starter) {                                                        // a flat roof: slabs over the room and the top plate
			for (int u = -1; u <= w; u++) {
				for (int v = -1; v <= d; v++) {
					boolean plate = (u == 0 || u == w - 1) && v >= 0 && v < d || (v == 0 || v == d - 1) && u >= 0 && u < w;
					L.put(u, v, plate ? h + 2 : h + 1, roofSlab + "[type=bottom]", ROOF);
				}
			}
		}
		for (int k = 0; !starter; k++) {
			int ul = -1 + k, ur = w - k, y = h + 1 + k;
			if (ul > ur) break;
			for (int v = -1; v <= d; v++) {
				if (ul == ur) {
					L.put(ul, v, y, roofSlab + "[type=bottom]", ROOF);
				} else {
					L.put(ul, v, y, roofStairs + "[facing={R},half=bottom,shape=straight]", ROOF);
					L.put(ur, v, y, roofStairs + "[facing={L},half=bottom,shape=straight]", ROOF);
				}
			}
			if (ul == ur) break;
			for (int u = ul + 1; u < ur; u++) {                                 // the gables, front and back
				if (k == 0) continue;
				for (int v : new int[] {0, d - 1}) {
					boolean gableWindow = k == 1 && u == c && !p.window().equals("air");
					L.put(u, v, y, gableWindow ? p.window() : p.wall(), ROOF);
				}
			}
		}
		// the door and a step up to it
		L.put(c, 0, 1, p.door() + "[facing={B},half=lower,hinge=left]", DOORS);
		L.put(c, -1, 0, stairsOf(p.floor().replace("_planks", "")) + "[facing={B},half=bottom,shape=straight]", DOORS, true);
		// inside: a bed, a crafting table, a furnace, a chest, a lamp, a table and a chair, a lantern under the beam
		L.put(1, d - 3, 1, "red_bed[facing={B},part=foot]", INSIDE, true);
		L.put(w - 2, d - 2, 1, "crafting_table", INSIDE, true);
		L.put(w - 3, d - 2, 1, "furnace[facing={F}]", INSIDE, true);
		if (w - 4 > 1) L.put(w - 4, d - 2, 1, "chest[facing={F}]", INSIDE, true);
		L.put(w - 2, 1, 1, p.fence(), INSIDE, true);                           // a lamp in the front corner
		L.put(w - 2, 1, 2, p.light().equals("torch") ? "torch" : "lantern[hanging=false]", INSIDE, true);
		if (w >= 9 && d >= 7) {
			L.put(w - 3, mid, 1, p.fence(), INSIDE, true);                         // a table...
			L.put(w - 3, mid, 2, p.floor().replace("_planks", "") + "_pressure_plate", INSIDE, true);
			L.put(w - 4, mid, 1, stairsOf(p.floor().replace("_planks", "")) + "[facing={L},half=bottom,shape=straight]", INSIDE, true);   // ...and a chair
		}
		if (!p.light().equals("torch")) L.put(c, mid, h, "lantern[hanging=true]", INSIDE, true);
		else L.put(1, 1, 2, "wall_torch[facing={B}]", INSIDE, true);
		if (fancy) {
			L.put(1, 1, 1, "bookshelf", INSIDE, true);
			for (int u = 2; u < w - 3; u++) for (int v = 2; v < d - 3; v++) if (u != 1) L.put(u, v, 1, "red_carpet", INSIDE, true);
		}
		// outside: shutters by the front windows, lamp posts, a path and flowers
		for (int[] wdw : windows) {
			if (wdw[1] != 0) continue;
			for (int du : new int[] {-1, 1}) {
				int u = wdw[0] + du;
				if (u <= 0 || u >= w - 1 || u == c) continue;
				L.put(u, -1, 2, p.trapdoor() + "[facing={F},half=top,open=true]", OUTSIDE, true);
			}
		}
		for (int du : new int[] {-2, 2}) {
			L.put(c + du, -3, 0, p.fence(), OUTSIDE, true);
			L.put(c + du, -3, 1, p.fence(), OUTSIDE, true);
			L.put(c + du, -3, 2, p.light().equals("torch") ? "torch" : "lantern[hanging=false]", OUTSIDE, true);
		}
		if (fancy) {
			for (int v = -2; v >= -5; v--) L.put(c, v, -1, "dirt_path", OUTSIDE, true);
			String[] flowers = {"poppy", "dandelion", "cornflower", "oxeye_daisy", "allium", "azure_bluet"};
			for (int u = 1; u < w - 1; u++) if (u != c) L.put(u, -1, 0, flowers[random.nextInt(flowers.length)], OUTSIDE, true);
		}
		net.minecraft.world.phys.AABB inside = new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, 0, 1)), net.minecraft.world.phys.Vec3.atCenterOf(L.pos(w - 1, d - 1, h + 1 + w / 2)));
		return new Plan(fancy ? "cottage" : "house", L.steps(), L.pos(c, 0, 1), L.pos(c, d / 2, 1), front, inside);
	}

	// ---------------------------------------------------------------------------------- designed
	/**
	 * A house from a Xen's own design ({@link Taste}): not one plan for every house but the choices it makes (and learns),
	 * put together the way builders do it: the ground levelled, a floor (on a stone plinth if it likes), walls (a stone
	 * bottom row, a log frame at the corners and every few blocks, windows in the middle of each bay, shutters), a top
	 * plate, the roof (a gable either way, a hipped roof, or a flat one, overhanging), a porch or a step to the door,
	 * lanterns, a chimney with smoke, bushes along the front, and inside: a bed (up in a loft in a tall house), a crafting
	 * table, a furnace, storage, a table and a chair, a carpet and a hanging lantern. origin is its front left corner at
	 * floor level; the door faces front.
	 */
	static Plan designed(BlockPos origin, Direction front, Taste.Design ds, Palette p, Random random) {
		Taste.Style style = ds.style();
		boolean modern = style == Taste.Style.MODERN, stilt = style == Taste.Style.STILT, tower = style == Taste.Style.TOWER;
		int raise = stilt ? 3 : 0;
		Layout L = new Layout(origin.above(raise), front);
		Layout G = raise > 0 ? L.shifted(-raise) : L;                           // (the ground's level: what stands outside stands there)
		int w = ds.w(), d = ds.d(), storey = ds.wallH(), c = w / 2, o = 1;
		int floors = tower ? (w >= 9 ? 3 : 2) : 1, h = floors * (storey + 1) - 1;   // (a tower: floors on floors)
		Taste.Roof roofKind = modern ? Taste.Roof.FLAT : ds.roof();
		int span = roofKind == Taste.Roof.FLAT ? 1 : roofKind == Taste.Roof.HIP ? Math.min(w, d) : ds.ridgeAlongWidth() ? d : w;
		int top = h + 2 + (span + 2 * o) / 2;                                    // (the roof's highest block, about)
		String base = p.base(), wall = p.wall(), frame = p.frame(), floor = p.floor();
		String stairs = stairsOf(p.roof()), slab = slabOf(p.roof());
		boolean anyBlock = p.light().equals("lantern");                             // (a creative palette: any block it likes)
		if (modern) {                                                               // white walls, a pale frame, a flat roof, big windows
			wall = anyBlock ? "white_concrete" : wall;
			frame = anyBlock ? "light_gray_concrete" : wall;
			floor = anyBlock ? "polished_andesite" : floor;
			slab = anyBlock ? "smooth_quartz_slab" : slab;
		}
		// the ground: cleared (with a strip around to walk on), holes under it filled
		for (int u = -2; u <= w + 1; u++) {
			for (int v = -3; v <= d + 1; v++) {
				boolean out = u < 0 || v < 0 || u >= w || v >= d;
				if (out) {
					for (int y = 0; y <= top + 1 + raise; y++) G.dig(u, v, y);
				} else {
					for (int y = 1; y <= top + 1; y++) L.dig(u, v, y);
					if (raise > 0) for (int y = 0; y < raise; y++) G.dig(u, v, y);      // (under a house on stilts: open)
					else for (int y = -4; y <= -1; y++) L.put(u, v, y, ds.base() && y == -1 && (u == 0 || v == 0 || u == w - 1 || v == d - 1) ? base : "dirt", SUPPORT);
				}
			}
		}
		// the floor
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < d; v++) {
				boolean edge = u == 0 || v == 0 || u == w - 1 || v == d - 1;
				L.put(u, v, 0, edge && ds.base() ? base : floor, SUPPORT);
			}
		}
		if (raise > 0) {                                                            // the stilts it stands on, in the ground
			List<Integer> su = w >= 9 ? List.of(0, c, w - 1) : List.of(0, w - 1), sv = d >= 9 ? List.of(0, d / 2, d - 1) : List.of(0, d - 1);
			for (int u : su) {
				for (int v : sv) {
					ground(G, u, v);
					for (int y = -raise; y <= -1; y++) L.put(u, v, y, frame + "[axis=y]", SUPPORT);
				}
			}
		}
		// the frame: posts at the corners (and every few blocks with a log frame), a top plate
		List<Integer> pu = ds.frame() ? posts(w) : List.of(0, w - 1), pv = ds.frame() ? posts(d) : List.of(0, d - 1);
		List<Integer> front0 = new ArrayList<>(pu);
		if (front0.remove(Integer.valueOf(c))) {
			front0.add(c - 1);
			front0.add(c + 1);
			java.util.Collections.sort(front0);
		}
		String post = frame + "[axis=y]";                                        // (logs at the corners, always: what a house stands on)
		for (int y = 1; y <= h; y++) {
			for (int u : front0) L.put(u, 0, y, post, FRAME);
			for (int u : pu) L.put(u, d - 1, y, post, FRAME);
			for (int v : pv) {
				L.put(0, v, y, post, FRAME);
				L.put(w - 1, v, y, post, FRAME);
			}
		}
		for (int u = 0; u < w; u++) {
			L.put(u, 0, h + 1, ds.frame() ? frame + "[axis={U}]" : wall, FRAME);
			L.put(u, d - 1, h + 1, ds.frame() ? frame + "[axis={U}]" : wall, FRAME);
		}
		for (int v = 1; v < d - 1; v++) {
			L.put(0, v, h + 1, ds.frame() ? frame + "[axis={V}]" : wall, FRAME);
			L.put(w - 1, v, h + 1, ds.frame() ? frame + "[axis={V}]" : wall, FRAME);
		}
		// the walls: stone at the bottom (if it likes), windows in the bays, the door in the middle of the front
		int mid = d / 2;
		List<int[]> windows = new ArrayList<>();
		for (int y = 1; y <= h; y++) {
			String fill = y == 1 && ds.lowerStone() ? base : wall;
			int yy = (y - 1) % (storey + 1) + 1;                                       // (the height within its floor)
			boolean between = tower && y % (storey + 1) == 0;                          // (a tower's floor level: a solid band)
			if (between) fill = ds.frame() ? frame + "[axis={U}]" : wall;
			boolean windowRow = !between && (modern ? yy <= storey - 1 : yy == 2 || yy == 3 && storey >= 4);
			for (int u = 1; u < w - 1; u++) {
				for (int v : new int[] {0, d - 1}) {
					if ((v == 0 ? front0 : pu).contains(u)) continue;
					if (v == 0 && u == c && y <= 2) continue;                          // the doorway
					boolean window = windowRow && (modern || isWindow(u, v == 0 ? front0 : pu, true)) && !(v == 0 && u == c);
					if (window) {
						if (!p.window().equals("air")) L.put(u, v, y, p.window(), WALLS);   // (no glass yet: an open window)
						if (y == 2) windows.add(new int[] {u, v});
					} else {
						L.put(u, v, y, fill, WALLS);
					}
				}
			}
			for (int v = 1; v < d - 1; v++) {
				for (int u : new int[] {0, w - 1}) {
					if (pv.contains(v)) continue;
					boolean window = windowRow && (modern || isWindow(v, pv)) && !(ds.loft() && u == 0 && v == mid - 1) && !(tower && u == 0 && v == 1);   // (not behind a ladder)
					if (!window) L.put(u, v, y, fill, WALLS);
					else if (!p.window().equals("air")) L.put(u, v, y, p.window(), WALLS);
				}
			}
		}
		// the roof
		switch (roofKind) {
			case FLAT -> {
				for (int u = -o; u < w + o; u++) {
					for (int v = -o; v < d + o; v++) {
						boolean plate = (u == 0 || u == w - 1) && v >= 0 && v < d || (v == 0 || v == d - 1) && u >= 0 && u < w;
						L.put(u, v, plate ? h + 2 : h + 1, slab + "[type=bottom]", ROOF);
					}
				}
			}
			case HIP -> {
				for (int k = 0; ; k++) {
					int u0 = -o + k, u1 = w - 1 + o - k, v0 = -o + k, v1 = d - 1 + o - k, y = h + 1 + k;
					if (u0 > u1 || v0 > v1) break;
					if (u0 == u1 || v0 == v1) {
						for (int u = u0; u <= u1; u++) for (int v = v0; v <= v1; v++) {
							if (k >= 2 && !L.has(u, v, y - 1)) L.put(u, v, y - 1, wall, ROOF);   // (something under it to build on)
							L.put(u, v, y, slab + "[type=bottom]", ROOF);
						}
						break;
					}
					if (k >= 2) {                                                          // the ring under it holds this one up
						for (int u = u0; u <= u1; u++) for (int v : new int[] {v0, v1}) if (!L.has(u, v, y - 1)) L.put(u, v, y - 1, wall, ROOF);
						for (int v = v0 + 1; v < v1; v++) for (int u : new int[] {u0, u1}) if (!L.has(u, v, y - 1)) L.put(u, v, y - 1, wall, ROOF);
					}
					for (int u = u0; u <= u1; u++) {
						L.put(u, v0, y, stairs + "[facing={B},half=bottom,shape=straight]", ROOF);
						L.put(u, v1, y, stairs + "[facing={F},half=bottom,shape=straight]", ROOF);
					}
					for (int v = v0 + 1; v < v1; v++) {
						L.put(u0, v, y, stairs + "[facing={R},half=bottom,shape=straight]", ROOF);
						L.put(u1, v, y, stairs + "[facing={L},half=bottom,shape=straight]", ROOF);
					}
				}
			}
			default -> {                                                             // a gable: two slopes and a ridge
				boolean alongW = ds.ridgeAlongWidth();
				int n = alongW ? d : w, m = alongW ? w : d;
				for (int k = 0; ; k++) {
					int a = -o + k, b = n - 1 + o - k, y = h + 1 + k;
					if (a > b) break;
					for (int j = -o; j < m + o; j++) {
						if (a == b) {
							put2(L, alongW, j, a, y, slab + "[type=bottom]", ROOF);
						} else {
							put2(L, alongW, j, a, y, stairs + (alongW ? "[facing={B}" : "[facing={R}") + ",half=bottom,shape=straight]", ROOF);
							put2(L, alongW, j, b, y, stairs + (alongW ? "[facing={F}" : "[facing={L}") + ",half=bottom,shape=straight]", ROOF);
						}
					}
					if (a == b) break;
					if (k == 0) continue;
					for (int i = a + 1; i < b; i++) {                                   // the gable ends, filled with wall
						for (int j : new int[] {0, m - 1}) put2(L, alongW, j, i, y, k == 1 && i == (a + b) / 2 && !p.window().equals("air") ? p.window() : wall, ROOF);
					}
				}
			}
		}
		if (roofKind != Taste.Roof.FLAT) {                                          // a beam across the middle (the lamp hangs from it)
			for (int u = 1; u < w - 1; u++) L.put(u, mid, h + 1, ds.frame() ? frame + "[axis={U}]" : wall, FRAME);
		}
		if (tower) {                                                                // the floors between, a ladder up through them
			for (int k = 1; k < floors; k++) {
				int y = k * (storey + 1);
				for (int u = 1; u < w - 1; u++) for (int v = 1; v < d - 1; v++) if (!(u == 1 && v == 1)) L.put(u, v, y, floor, WALLS);
				L.put(c, mid, y - 1, "lantern[hanging=true]", INSIDE, true);
			}
			for (int y = 1; y <= (floors - 1) * (storey + 1) + 1; y++) L.put(1, 1, y, "ladder[facing={R}]", INSIDE);
		}
		// ---- outside. The floor is a step up from the ground: out here the ground is y -1, and what stands outside stands
		// on it (from y 0 up), with the ground made good under it first (nothing floats)
		String light = p.light().equals("torch") ? "torch" : "lantern[hanging=false]";
		String leaves = (p.name().contains("spruce") || p.name().contains("dark_oak") ? "spruce" : p.name().contains("birch") ? "birch" : "oak")
				+ "_leaves[persistent=true]";
		String[] flowers = {"poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "red_tulip", "lily_of_the_valley"};
		L.put(c, 0, 1, p.door() + "[facing={B},half=lower,hinge=left]", DOORS);
		int way;                                                                   // where the way in meets the ground
		if (ds.porch()) {                                          // a deck level with the floor, posts, a roof against the wall, a step
			for (int u = c - 2; u <= c + 2; u++) {
				for (int v = -2; v <= -1; v++) {
					ground(G, u, v);
					G.put(u, v, 0, floor, SUPPORT);
					if (!G.has(u, v, h + 1)) G.put(u, v, h + 1, slab + "[type=bottom]", OUTSIDE, true);   // (the eaves are there already)
				}
			}
			for (int u : new int[] {c - 2, c + 2}) for (int y = 1; y <= h; y++) G.put(u, -2, y, p.fence(), OUTSIDE, true);
			G.put(c - 1, -2, 1, p.fence(), OUTSIDE, true);                              // a railing each side, a lamp on one
			G.put(c + 1, -2, 1, p.fence(), OUTSIDE, true);
			G.put(c - 1, -2, 2, light, OUTSIDE, true);
			ground(G, c, -3);
			G.put(c, -3, 0, stairsOf(floor) + "[facing={B},half=bottom,shape=straight]", OUTSIDE, true);
			way = -4;
		} else if (raise > 0) {                                     // stairs down from the door to the ground, a step held up under each
			for (int k = 0; k < raise; k++) {
				L.put(c, -1 - k, -k, stairsOf(floor) + "[facing={B},half=bottom,shape=straight]", SUPPORT);
				L.put(c, -1 - k, -1 - k, floor, SUPPORT);
			}
			ground(G, c, -raise);
			way = -1 - raise;
		} else {
			ground(G, c, -1);
			G.put(c, -1, 0, stairsOf(floor) + "[facing={B},half=bottom,shape=straight]", DOORS, true);
			for (int du : new int[] {-1, 1}) {                                         // a lamp on a post each side of the door
				ground(G, c + du, -1);
				G.put(c + du, -1, 0, p.fence(), OUTSIDE, true);
				G.put(c + du, -1, 1, light, OUTSIDE, true);
			}
			way = -2;
		}
		int keepClear = ds.porch() ? 2 : 1;
		// a path out from the door (a shovel on the grass), lamp posts where it starts
		int len = 4 + w / 4, end = way - len + 1;
		for (int v = way; v >= end; v--) {
			G.put(c, v, -1, "dirt_path", OUTSIDE, true);
			G.dig(c, v, 0);
			G.dig(c, v, 1);
		}
		for (int du : new int[] {-1, 1}) {
			ground(G, c + du, end);
			G.put(c + du, end, 0, p.fence(), OUTSIDE, true);
			G.put(c + du, end, 1, p.fence(), OUTSIDE, true);
			G.put(c + du, end, 2, light, OUTSIDE, true);
		}
		// shutters by the front windows
		if (ds.shutters()) {
			for (int[] wdw : windows) {
				if (wdw[1] != 0) continue;
				for (int du : new int[] {-1, 1}) {
					int u = wdw[0] + du;
					if (u <= 0 || u >= w - 1 || Math.abs(u - c) <= keepClear) continue;
					L.put(u, -1, 2, p.trapdoor() + "[facing={F},half=top,open=true]", OUTSIDE, true);
				}
			}
		}
		// a chimney up the side, with smoke
		if (ds.chimney()) {
			for (int y = -1; y <= top + raise; y++) G.put(w, d - 2, y, "cobblestone", OUTSIDE, true);
			G.put(w, d - 2, top + raise + 1, "campfire[lit=true]", OUTSIDE, true);
		}
		// bushes along the front (bigger ones at the corners), flower beds between them and along the path
		for (int u = 0; u < w; u++) {
			if (Math.abs(u - c) <= keepClear) continue;
			boolean bush = ds.bushes() && (!ds.garden() || u % 2 == 0);
			if (!bush && !ds.garden()) continue;
			ground(G, u, -1);
			G.put(u, -1, 0, bush ? leaves : flowers[random.nextInt(flowers.length)], OUTSIDE, true);
		}
		if (ds.bushes()) {
			for (int[] at : new int[][] {{-1, -1}, {w, -1}, {-1, d}, {w, d}}) {
				if (ds.chimney() && at[0] == w && at[1] == d) continue;
				ground(G, at[0], at[1]);
				G.put(at[0], at[1], 0, leaves, OUTSIDE, true);
				if (at[1] < 0) G.put(at[0], at[1], 1, leaves, OUTSIDE, true);
			}
		}
		if (ds.garden()) {
			for (int v = way - 1; v > end; v--) {
				for (int du : new int[] {-1, 1}) {
					ground(G, c + du, v);
					G.put(c + du, v, 0, flowers[random.nextInt(flowers.length)], OUTSIDE, true);
				}
			}
			for (int v = 1; v < d - 1; v += 2) {                                       // and along the right side
				if (ds.chimney() && v == d - 2) continue;
				ground(G, w, v);
				G.put(w, v, 0, flowers[random.nextInt(flowers.length)], OUTSIDE, true);
			}
		}
		// a woodpile and a barrel by the left wall (a bigger house)
		if (w >= 9 && !ds.workshop()) {
			for (int v = 1; v <= 2; v++) {
				ground(G, -1, v);
				G.put(-1, v, 0, frame + "[axis={V}]", OUTSIDE, true);
			}
			G.put(-1, 1, 1, frame + "[axis={V}]", OUTSIDE, true);
			ground(G, -1, 3);
			G.put(-1, 3, 0, "barrel[facing=up]", OUTSIDE, true);
		}
		// a fenced yard round it all, a gate where the path goes out (lamps on the gate posts)
		if (ds.yard()) {
			int u0 = -3, u1 = w + 2, v0 = end, v1 = d + 2;
			for (int u = u0; u <= u1; u++) {
				for (int v = v0; v <= v1; v++) {
					if (u != u0 && u != u1 && v != v0 && v != v1) continue;
					if (G.has(u, v, 0) && !(v == v0 && Math.abs(u - c) == 1)) continue;
					ground(G, u, v);
					G.dig(u, v, 1);
					G.put(u, v, 0, u == c && v == v0 ? p.fence() + "_gate[facing={B}]" : p.fence(), OUTSIDE, true);
				}
			}
			for (int u : new int[] {u0, u1}) {
				G.put(u, v0, 1, light, OUTSIDE, true);
			}
		}
		if (ds.pond()) {                                                            // a little pond with lily pads, a pergola with a bench by it
			int ponU = c + 3, ponV = way - 3;
			for (int u = ponU; u <= ponU + 2; u++) {
				for (int v = ponV; v >= ponV - 1; v--) {
					G.put(u, v, -2, "dirt", SUPPORT);
					G.put(u, v, -1, "water", OUTSIDE, true);
					G.dig(u, v, 0);
					G.dig(u, v, 1);
				}
			}
			G.put(ponU + 1, ponV, 0, "lily_pad", OUTSIDE, true);
			G.put(ponU + 2, ponV - 1, 0, "lily_pad", OUTSIDE, true);
			int qu = c - 5, qv = way - 3;                                           // the pergola: four posts, a roof of slabs and leaves
			for (int u = qu; u <= qu + 2; u++) for (int v = qv; v >= qv - 2; v--) {
				G.dig(u, v, 0);
				G.dig(u, v, 1);
				G.dig(u, v, 2);
			}
			for (int[] q : new int[][] {{qu, qv}, {qu + 2, qv}, {qu, qv - 2}, {qu + 2, qv - 2}}) {
				ground(G, q[0], q[1]);
				G.put(q[0], q[1], 0, p.fence(), OUTSIDE, true);
				G.put(q[0], q[1], 1, p.fence(), OUTSIDE, true);
			}
			for (int u = qu; u <= qu + 2; u++) for (int v = qv; v >= qv - 2; v--) G.put(u, v, 2, (u + v) % 2 == 0 ? leaves : slab + "[type=bottom]", OUTSIDE, true);
			ground(G, qu + 1, qv - 1);
			G.put(qu + 1, qv - 1, 0, stairsOf(floor) + "[facing={F},half=bottom,shape=straight]", OUTSIDE, true);
			G.put(qu + 1, qv - 1, 1, "lantern[hanging=true]", OUTSIDE, true);
		}
		if (ds.workshop()) {                                                        // a workshop against the left wall: a roof on posts, a table, a chest
			for (int u = -3; u <= -1; u++) for (int v = 1; v <= 3; v++) for (int y = 0; y <= 2; y++) G.dig(u, v, y);
			for (int v : new int[] {1, 3}) {
				ground(G, -3, v);
				G.put(-3, v, 0, p.fence(), OUTSIDE, true);
				G.put(-3, v, 1, p.fence(), OUTSIDE, true);
			}
			for (int u = -3; u <= -1; u++) for (int v = 1; v <= 3; v++) G.put(u, v, 2, slab + "[type=bottom]", OUTSIDE, true);
			for (int v = 1; v <= 3; v++) ground(G, -1, v);
			G.put(-1, 1, 0, "crafting_table", OUTSIDE, true);
			G.put(-1, 2, 0, "chest[facing={L}]", OUTSIDE, true);
			G.put(-1, 3, 0, "barrel[facing=up]", OUTSIDE, true);
			ground(G, -3, 2);
			G.put(-3, 2, 0, "hay_block", OUTSIDE, true);
			G.put(-2, 2, 1, "lantern[hanging=true]", OUTSIDE, true);
		}
		// inside
		if (ds.loft()) {                                                            // a loft over the back half, a ladder up
			for (int u = 1; u < w - 1; u++) for (int v = mid; v < d - 1; v++) L.put(u, v, 3, slab + "[type=top]", INSIDE);
			for (int y = 1; y <= 3; y++) L.put(1, mid - 1, y, "ladder[facing={R}]", INSIDE);
			L.put(w - 2, d - 3, 4, "red_bed[facing={B},part=foot]", INSIDE, true);
		} else if (tower) {
			L.put(w - 2, d - 3, (floors - 1) * (storey + 1) + 1, "red_bed[facing={B},part=foot]", INSIDE, true);   // (up top)
		} else {
			L.put(1, d - 3, 1, "red_bed[facing={B},part=foot]", INSIDE, true);
		}
		L.put(w - 2, d - 2, 1, "crafting_table", INSIDE, true);
		L.put(w - 3, d - 2, 1, "furnace[facing={F}]", INSIDE, true);
		if (w - 4 > 1) L.put(w - 4, d - 2, 1, "chest[facing={F}]", INSIDE, true);
		if (w >= 7) L.put(w - 2, 1, 1, "barrel[facing=up]", INSIDE, true);
		if (w >= 7 && d >= 7) {
			L.put(c + 1, mid, 1, p.fence(), INSIDE, true);                               // a table and a chair
			L.put(c + 1, mid, 2, floor.replace("_planks", "") + "_pressure_plate", INSIDE, true);
			L.put(c, mid, 1, stairsOf(floor) + "[facing={L},half=bottom,shape=straight]", INSIDE, true);
			for (int v = 2; v < d - 2; v++) if (v != mid) L.put(c, v, 1, "red_carpet", INSIDE, true);
		}
		L.put(tower ? 2 : 1, 1, 1, "potted_poppy", INSIDE, true);
		if (w >= 9) {                                                                  // a bigger house: books, plants, more light
			for (int y = 1; y <= 2; y++) {
				L.put(w - 5, d - 2, y, "bookshelf", INSIDE, true);
				if (w >= 11) L.put(w - 6, d - 2, y, "bookshelf", INSIDE, true);
			}
			L.put(w - 3, 1, 1, "potted_fern", INSIDE, true);
			if (!ds.loft()) L.put(2, d - 3, 1, "red_bed[facing={B},part=foot]", INSIDE, true);   // (two beds)
		}
		if (w >= 11) {
			L.put(c - 3, mid, h, "lantern[hanging=true]", INSIDE, true);
			L.put(c + 3, mid, h, "lantern[hanging=true]", INSIDE, true);
		} else {
			L.put(c, mid, h, "lantern[hanging=true]", INSIDE, true);
		}
		net.minecraft.world.phys.AABB inside = new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, 0, 1)),
				net.minecraft.world.phys.Vec3.atCenterOf(L.pos(w - 1, d - 1, top)));
		return new Plan("house", L.steps(), L.pos(c, 0, 1), L.pos(c, d / 2, 1), front, inside);
	}

	/** Outside the house: the ground made good under something that stands there (a hole filled; grass is fine as it is). */
	private static void ground(Layout L, int u, int v) {
		if (!L.has(u, v, -1)) L.put(u, v, -1, "dirt", SUPPORT);
	}

	/**
	 * The mouth of its mine, dressed up like players do: log posts either side of the staircase down, a beam across
	 * with lanterns under it, a little roof, a chest and a barrel by the door, and a path of gravel and coarse dirt out
	 * front. entrance: the top of the staircase; down: the way the staircase goes.
	 */
	static Plan mineEntrance(BlockPos entrance, Direction down, Palette p) {
		Layout L = new Layout(entrance.relative(down.getCounterClockWise(), 2), down.getOpposite());   // (u across, v into the mine)
		String log = p.frame() + "[axis=y]";
		for (int u : new int[] {0, 4}) {
			for (int v : new int[] {0, 2}) {
				L.put(u, v, -1, "cobblestone", SUPPORT);
				for (int y = 0; y <= 2; y++) L.put(u, v, y, log, FRAME);
			}
		}
		for (int u = 0; u <= 4; u++) {
			L.put(u, 0, 3, p.frame() + "[axis={U}]", FRAME);
			L.put(u, 2, 3, p.frame() + "[axis={U}]", FRAME);
			L.put(u, 1, 3, stairsOf(p.roof()) + "[facing={B},half=bottom,shape=straight]", ROOF);
			L.put(u, -1, 3, stairsOf(p.roof()) + "[facing={B},half=bottom,shape=straight]", ROOF, true);
		}
		L.put(1, 0, 2, "lantern[hanging=true]", INSIDE, true);
		L.put(3, 0, 2, "lantern[hanging=true]", INSIDE, true);
		L.put(1, -1, 0, "chest[facing={F}]", OUTSIDE, true);
		L.put(3, -1, 0, "barrel[facing=up]", OUTSIDE, true);
		String[] path = {"gravel", "coarse_dirt", "dirt_path", "gravel"};
		for (int v = -1; v >= -4; v--) L.put(2, v, -1, path[(-v) % path.length], OUTSIDE, true);
		net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, -1, 0)),
				net.minecraft.world.phys.Vec3.atCenterOf(L.pos(4, 2, 3)));
		return new Plan("mine entrance", L.steps(), L.pos(2, 0, 0), L.pos(2, 1, 0), down, box);
	}

	/**
	 * A stretch of highway: 3 wide, len long, from start (its middle lane) in dir: the floor laid in the ground (the
	 * block it chose: obsidian, stone...), 3 blocks of room above it dug out, and in a tunnel (the Nether) a torch on the
	 * wall every 8 blocks.
	 */
	static Plan highway(BlockPos start, Direction dir, int len, String floor) {
		return highway(start, dir, len, floor, start.getY() - 1);
	}

	/**
	 * The same, with its floor at floorY: it starts with a staircase from where it stands (a block up or down each
	 * step), then runs level (underground, out of the way of what's built above; in the Nether, under the roof).
	 */
	static Plan highway(BlockPos start, Direction dir, int len, String floor, int floorY) {
		Layout L = new Layout(start.relative(dir.getCounterClockWise(), 1), dir.getOpposite());   // (u across, v along the way)
		int dy = floorY - (start.getY() - 1), sign = Integer.signum(dy), last = -1;
		for (int v = 0; v < len; v++) {
			int fy = -1 + sign * Math.min(v + 1, Math.abs(dy));                      // the floor here (relative), stepping to the level
			last = fy;
			for (int u = 0; u < 3; u++) {
				L.put(u, v, fy, floor, SUPPORT);
				for (int y = fy + 1; y <= fy + 3; y++) L.dig(u, v, y);
				if (sign > 0) L.dig(u, v, fy);                                      // (going up: room for the head on the step before)
			}
			if (v % 8 == 4) L.put(0, v, fy + 2, "wall_torch[facing={R}]", OUTSIDE, true);
		}
		net.minecraft.world.phys.AABB lane = new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, 0, -1)),
				net.minecraft.world.phys.Vec3.atCenterOf(L.pos(2, len - 1, last + 3)));
		return new Plan("highway", L.steps(), L.pos(1, 0, 0), L.pos(1, len - 1, last + 1), dir, lane);
	}

	/** A roof block: j along the ridge, i across it (the ridge along the width or the depth). */
	private static void put2(Layout L, boolean alongW, int j, int i, int y, String spec, int phase) {
		if (alongW) L.put(j, i, y, spec, phase);
		else L.put(i, j, y, spec, phase);
	}

	/** Posts along a wall of n blocks: both corners, and every 3 or 4 blocks between. */
	private static List<Integer> posts(int n) {
		List<Integer> out = new ArrayList<>();
		out.add(0);
		int gaps = Math.max(1, Math.round((n - 1) / 4f));
		for (int i = 1; i < gaps; i++) out.add(Math.round(i * (n - 1) / (float) gaps));
		out.add(n - 1);
		return out;
	}

	/** A window in the middle of its bay (one block, or the two middle ones of a wide bay). */
	private static boolean isWindow(int i, List<Integer> posts) {
		return isWindow(i, posts, false);
	}

	/** narrow: a window in a bay one block wide too (between close posts, like a timber-framed front). */
	private static boolean isWindow(int i, List<Integer> posts, boolean narrow) {
		for (int k = 0; k + 1 < posts.size(); k++) {
			int a = posts.get(k), b = posts.get(k + 1);
			if (i <= a || i >= b) continue;
			int len = b - a - 1;
			if (len < 3) return len == 1 ? narrow : i == a + 1;
			double midBay = (a + b) / 2.0;
			return Math.abs(i - midBay) < (len % 2 == 0 ? 1 : 0.6);
		}
		return false;
	}

	static String stairsOf(String material) {
		return switch (material) {
			case "stone_bricks" -> "stone_brick_stairs";
			case "deepslate_tile", "deepslate_tiles" -> "deepslate_tile_stairs";
			case "smooth_sandstone" -> "smooth_sandstone_stairs";
			case "cobblestone" -> "cobblestone_stairs";
			default -> material.replace("_planks", "") + "_stairs";
		};
	}

	static String slabOf(String material) {
		return switch (material) {
			case "stone_bricks" -> "stone_brick_slab";
			case "deepslate_tile", "deepslate_tiles" -> "deepslate_tile_slab";
			case "smooth_sandstone" -> "smooth_sandstone_slab";
			case "cobblestone" -> "cobblestone_slab";
			default -> material.replace("_planks", "") + "_slab";
		};
	}

	// ----------------------------------------------------------------------------- underground
	/**
	 * An underground base: a staircase from origin (where it stands, on the surface) going down `depth` blocks toward
	 * the back, then a door and a room w wide, d deep and 4 high carved out of the stone, lit and furnished.
	 */
	static Plan underground(BlockPos origin, Direction front, int depth, int w, int d, Palette p, boolean fancy) {
		Layout L = new Layout(origin, front);
		// the staircase down, 3 high so nobody bumps their head, with torches along it
		for (int k = 0; k < depth; k++) {
			int v = k + 1, feet = -(k + 1);
			for (int y = feet; y <= feet + 2; y++) L.dig(0, v, y);
			if (k % 3 == 1) L.put(1, v, feet + 1, "wall_torch[facing={L}]", INSIDE, true);
		}
		int yr = -depth;                                                     // the room's floor level (its feet)
		int v0 = depth + 2, half = w / 2;                                    // the door in between, then the room
		L.dig(0, depth + 1, yr);
		L.dig(0, depth + 1, yr + 1);
		L.put(0, depth + 1, yr, p.door() + "[facing={B},half=lower,hinge=left]", DOORS, true);
		for (int u = -half; u <= half; u++) {
			for (int v = v0; v < v0 + d; v++) {
				for (int y = yr; y <= yr + 3; y++) L.dig(u, v, y);
				L.put(u, v, yr - 1, p.floor(), SUPPORT, true);                  // a plank floor
			}
		}
		// log pillars in the corners and along the long walls, beams across the ceiling
		List<Integer> beams = new ArrayList<>();
		for (int v = v0; v < v0 + d; v += 3) beams.add(v);
		if (!beams.contains(v0 + d - 1)) beams.add(v0 + d - 1);
		for (int v : beams) {
			for (int u : new int[] {-half, half}) for (int y = yr; y <= yr + 2; y++) L.put(u, v, y, p.frame() + "[axis=y]", FRAME, true);
			for (int u = -half; u <= half; u++) L.put(u, v, yr + 3, p.frame() + "[axis={U}]", FRAME, true);
		}
		// light: lanterns under the beams (torches on the pillars if it has no lanterns)
		for (int v : beams) {
			if (!p.light().equals("torch")) {
				L.put(0, v, yr + 2, "lantern[hanging=true]", INSIDE, true);
			} else {
				L.put(-half + 1, v, yr + 1, "wall_torch[facing={R}]", INSIDE, true);
				L.put(half - 1, v, yr + 1, "wall_torch[facing={L}]", INSIDE, true);
			}
		}
		// the back wall: storage, the workshop; a bed along the side; a table with a chair in the middle
		int back = v0 + d - 2;
		L.put(-half + 1, back, yr, "chest[facing={F}]", INSIDE, true);
		L.put(-half + 2, back, yr, "chest[facing={F}]", INSIDE, true);
		L.put(-half + 3, back, yr, "barrel[facing=up]", INSIDE, true);
		L.put(half - 1, back, yr, "crafting_table", INSIDE, true);
		L.put(half - 2, back, yr, "furnace[facing={F}]", INSIDE, true);
		L.put(half - 3, back, yr, "furnace[facing={F}]", INSIDE, true);
		L.put(half - 1, v0 + 1, yr, "red_bed[facing={F},part=foot]", INSIDE, true);
		L.put(0, v0 + d / 2, yr, p.fence(), INSIDE, true);
		L.put(0, v0 + d / 2, yr + 1, p.floor().replace("_planks", "") + "_pressure_plate", INSIDE, true);
		L.put(-1, v0 + d / 2, yr, stairsOf(p.floor().replace("_planks", "")) + "[facing={L},half=bottom,shape=straight]", INSIDE, true);
		if (fancy) {
			for (int u = -half + 1; u <= half - 1; u += 2) L.put(u, v0 + d - 1, yr + 1, "bookshelf", INSIDE, true);
			L.put(-half + 1, v0 + 1, yr, "potted_azalea_bush", INSIDE, true);
			for (int u = -1; u <= 1; u++) for (int v = v0 + d / 2 - 1; v <= v0 + d / 2 + 1; v++) if (u != 0 || v != v0 + d / 2) L.put(u, v, yr, "brown_carpet", INSIDE, true);
		}
		// the way in on the surface: two posts with lights
		for (int du : new int[] {-1, 1}) {
			L.put(du, 0, 0, p.fence(), OUTSIDE, true);
			L.put(du, 0, 1, p.light().equals("torch") ? "torch" : "lantern[hanging=false]", OUTSIDE, true);
		}
		return new Plan("underground base", L.steps(), L.pos(0, depth + 1, yr), L.pos(0, v0 + d / 2, yr), front,
				new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(-half, v0, yr)), net.minecraft.world.phys.Vec3.atCenterOf(L.pos(half, v0 + d - 1, yr + 3))));
	}

	// ---------------------------------------------------------------------------------- farms
	/**
	 * A crop farm the way the wiki shows it: 9 by 9, one water block in the middle (it keeps every block of farmland
	 * within four wet), the rest tilled with a hoe and sown (wheat; a row of carrots and one of potatoes if it has
	 * them), a fence round it with a gate at the front, lanterns on the corner posts, a composter and a chest by the
	 * gate. origin is the front left corner of the field (its farmland level).
	 */
	static Plan farm(BlockPos origin, Direction front, Palette p, boolean fancy) {
		Layout L = new Layout(origin, front);
		int n = 9, c = n / 2;
		String fence = p.fence(), gate = p.fence().replace("_fence", "_fence_gate");
		for (int u = -1; u <= n; u++) {
			for (int v = -1; v <= n; v++) {
				for (int y = 0; y <= 3; y++) L.dig(u, v, y);                    // clear it (and a strip round it)
				for (int y = -3; y <= -2; y++) L.put(u, v, y, "dirt", SUPPORT);
				boolean edge = u < 0 || v < 0 || u >= n || v >= n;
				if (edge) {
					L.put(u, v, -1, "dirt", SUPPORT);
					if (u == c && v == -1) L.put(u, v, 0, gate + "[facing={F}]", DOORS);
					else L.put(u, v, 0, fence, WALLS);
				} else if (u == c && v == c) {
					L.put(u, v, -1, "water", FRAME);                               // the water (one block: the wiki's trick)
				} else {
					L.put(u, v, -1, "farmland", FRAME);                            // tilled with a hoe
					String crop = !fancy || v >= 2 ? "wheat" : v == 0 ? "carrots" : "potatoes";
					L.put(u, v, 0, crop, INSIDE, true);
				}
			}
		}
		for (int[] k : new int[][] {{-1, -1}, {n, -1}, {-1, n}, {n, n}}) {
			L.put(k[0], k[1], 1, p.light().equals("torch") ? "torch" : "lantern[hanging=false]", OUTSIDE, true);
		}
		if (fancy) {
			L.put(c - 2, -2, 0, "composter", OUTSIDE, true);
			L.put(c + 2, -2, 0, "chest[facing={F}]", OUTSIDE, true);
			L.put(c - 2, -2, -1, "dirt", SUPPORT);
			L.put(c + 2, -2, -1, "dirt", SUPPORT);
		}
		return new Plan("farm", L.steps(), L.pos(c, -1, 0), L.pos(c, c, 0), front,
				new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, 0, 0)), net.minecraft.world.phys.Vec3.atCenterOf(L.pos(n - 1, n - 1, 1))));
	}

	/**
	 * An animal pen: a fenced 7 by 7 on grass with a gate, a water trough, hay bales in a corner and a lantern post:
	 * room for a few cows, sheep or pigs led in with wheat or carrots.
	 */
	/**
	 * A Nether portal: a frame of ten obsidian, four wide and five tall, around a hole two wide and three tall, with
	 * cobblestone in the corners (a builder needs something to put the sides against; the corners don't have to be
	 * obsidian). origin is the left end of its bottom, at floor level; you walk through it front to back.
	 */
	static Plan portal(BlockPos origin, Direction front) {
		Layout L = new Layout(origin, front);
		for (int u = -1; u <= 4; u++) {
			for (int v = -2; v <= 2; v++) {
				for (int y = 0; y <= 5; y++) if (v != 0 || u < 0 || u > 3) L.dig(u, v, y);   // room around it to stand and walk
				L.put(u, v, -1, "cobblestone", SUPPORT);
			}
		}
		for (int u = 0; u <= 3; u++) {
			for (int y = 0; y <= 4; y++) {
				boolean side = u == 0 || u == 3, end = y == 0 || y == 4;
				int phase = y == 0 ? FRAME : y == 4 ? ROOF : WALLS;                 // bottom, sides, then the top
				if (side && end) L.put(u, 0, y, "cobblestone", phase);
				else if (side || end) L.put(u, 0, y, "obsidian", phase);
				else L.dig(u, 0, y);
			}
		}
		return new Plan("nether portal", L.steps(), L.pos(1, -1, 0), L.pos(1, 0, 1), front,
				new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(1, 0, 1)), net.minecraft.world.phys.Vec3.atCenterOf(L.pos(2, 0, 3))));
	}

	static Plan pen(BlockPos origin, Direction front, Palette p, boolean fancy) {
		Layout L = new Layout(origin, front);
		int n = 7, c = n / 2;
		String fence = p.fence(), gate = p.fence().replace("_fence", "_fence_gate");
		for (int u = -1; u <= n; u++) {
			for (int v = -1; v <= n; v++) {
				for (int y = 1; y <= 3; y++) L.dig(u, v, y);
				L.dig(u, v, 0);
				L.put(u, v, -1, "grass_block", SUPPORT);
				for (int y = -3; y <= -2; y++) L.put(u, v, y, "dirt", SUPPORT);
				boolean edge = u < 0 || v < 0 || u >= n || v >= n;
				if (!edge) continue;
				if (u == c && v == -1) L.put(u, v, 0, gate + "[facing={F}]", DOORS);
				else L.put(u, v, 0, fence, WALLS);
			}
		}
		L.put(n - 1, n - 1, -1, "water", FRAME);                                  // a trough in the back corner
		L.put(n - 2, n - 1, -1, "water", FRAME);
		L.put(0, n - 1, 0, "hay_block", INSIDE, true);
		L.put(1, n - 1, 0, "hay_block", INSIDE, true);
		L.put(0, n - 2, 0, "hay_block", INSIDE, true);
		L.put(0, n - 1, 1, "hay_block", INSIDE, true);
		L.put(-1, -1, 1, p.light().equals("torch") ? "torch" : "lantern[hanging=false]", OUTSIDE, true);
		L.put(n, -1, 1, p.light().equals("torch") ? "torch" : "lantern[hanging=false]", OUTSIDE, true);
		return new Plan("animal pen", L.steps(), L.pos(c, -1, 0), L.pos(c, c, 0), front,
				new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, 0, 0)), net.minecraft.world.phys.Vec3.atCenterOf(L.pos(n - 1, n - 1, 1))));
	}

	/**
	 * A mob farm, the tower kind players build (the "easiest mob farm"): up on a pillar, an open 17 by 17 platform of
	 * stone bricks, four spawning floors with a cross of water channels sunk between them; open trapdoors along the
	 * channels (a mob takes an open trapdoor for floor, steps on it and drops into the water); the water at the end of
	 * each channel flows exactly 7 blocks, to the hole in the middle; they fall 22 blocks down the pillar (a zombie
	 * lands with half a heart) onto a hopper that puts what they drop into a chest at the foot, with a torch each side
	 * and a gap one block high above it to hit them through. Walls round the top keep them from walking off. It works at
	 * night (monsters spawn on the dark platform). origin is the foot of the pillar (where they land).
	 */
	static Plan mobFarm(BlockPos origin, Direction front, boolean fancy) {
		Layout L = new Layout(origin, front);
		String brick = fancy ? "stone_bricks" : "cobblestone", trap = fancy ? "spruce_trapdoor" : "oak_trapdoor";
		int q = 24, r = 8;                                                       // spawning floor on top at y q (mobs at q+1)
		int channel = q - 2, water = q - 1;                                     // channel floor, water, trapdoors at q
		// the pillar: open from 1 to channel, walled round (a hopper at the bottom, the chest in its front wall)
		for (int y = 0; y <= channel; y++) {
			if (y >= 1) L.dig(0, 0, y);
			for (int u = -1; u <= 1; u++) {
				for (int v = -1; v <= 1; v++) {
					if (u == 0 && v == 0) continue;
					if (y == channel && (u == 0 || v == 0)) continue;                  // (the channels' first floor blocks)
					if (u == 0 && v == -1 && y <= 1) continue;                        // the chest, and the gap over it
					L.put(u, v, y, brick, FRAME);
				}
			}
		}
		L.put(0, 0, 0, "hopper[facing={F}]", FRAME, true);                      // they land on it: drops go to the chest
		L.put(0, -1, 0, "chest[facing={F}]", FRAME, true);
		L.dig(0, -1, 1);                                                         // the gap: hit them here (they can't get out)
		L.put(-1, -2, 1, "wall_torch[facing={F}]", OUTSIDE, true);
		L.put(1, -2, 1, "wall_torch[facing={F}]", OUTSIDE, true);
		for (int u = -1; u <= 1; u++) for (int v = -3; v <= -2; v++) L.put(u, v, -1, brick, SUPPORT);   // a step to stand on
		// the platform
		for (int u = -r - 1; u <= r + 1; u++) {
			for (int v = -r - 1; v <= r + 1; v++) {
				boolean rim = Math.abs(u) == r + 1 || Math.abs(v) == r + 1;
				boolean chan = !rim && (u == 0 || v == 0);
				if (rim) {                                                          // the wall round it
					for (int y = channel; y <= q + 2; y++) L.put(u, v, y, brick, y <= q ? FRAME : WALLS);
					continue;
				}
				for (int y = q + 1; y <= q + 3; y++) L.dig(u, v, y);
				if (chan) {
					if (!(u == 0 && v == 0)) L.put(u, v, channel, brick, FRAME);       // the channel's floor (not over the hole)
					L.dig(u, v, water);
					L.dig(u, v, q);
				} else {                                                            // spawning floor, two thick (it holds the water in)
					L.put(u, v, water, brick, FRAME);
					L.put(u, v, q, brick, FRAME);
				}
			}
		}
		// water at the end of each channel (flows 7: to the hole's edge), trapdoors along the channels, open
		int[][] axes = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] a : axes) {
			L.put(a[0] * r, a[1] * r, water, "water", WALLS);
			for (int k = 1; k <= r; k++) {
				int u = a[0] * k, v = a[1] * k;
				String side = a[0] != 0 ? "{F}" : "{R}";                             // hung on the side of the floor next to it
				L.put(u, v, q, trap + "[half=top,open=true,facing=" + side + "]", INSIDE, true);
			}
		}
		L.dig(0, 0, water);
		L.dig(0, 0, q);
		return new Plan("mob farm", L.steps(), L.pos(0, -2, 0), L.pos(0, -2, 0), front,
				new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(-1, -3, 0)), net.minecraft.world.phys.Vec3.atCenterOf(L.pos(1, -2, 1))));
	}

	// ------------------------------------------------------------------------------------ sites
	/**
	 * Somewhere near to build a w by d cottage facing `front`: the flattest ground close by (dry, not on leaves or a
	 * tree), as its front left corner at floor level, or null.
	 */
	static BlockPos site(ServerLevel level, BlockPos near, Direction front, int w, int d) {
		Direction right = front.getCounterClockWise(), back = front.getOpposite();
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int dx = -8; dx <= 8; dx += 2) {
			for (int dz = -8; dz <= 8; dz += 2) {
				BlockPos corner = ground(level, near.offset(dx, 0, dz));
				if (corner == null) continue;
				List<Integer> heights = new ArrayList<>();
				int trees = 0;
				boolean ok = true;
				for (int u = -1; u <= w && ok; u += 2) {
					for (int v = -3; v <= d && ok; v += 2) {
						BlockPos g = ground(level, corner.relative(right, u).relative(back, v));
						if (g == null) {
							ok = false;                                          // water, or a cliff it can't see the bottom of
							break;
						}
						heights.add(g.getY());
						if (!level.getBlockState(g.above()).canBeReplaced()) trees++;
					}
				}
				if (!ok) continue;
				// the floor goes where the most ground already is (the least to dig and fill), like a player levelling a spot
				java.util.Collections.sort(heights);
				int level0 = heights.get(heights.size() / 2), bumps = 0;
				for (int y : heights) bumps += Math.abs(y - level0);
				int score = bumps * 4 + trees * 3 + (Math.abs(dx) + Math.abs(dz)) / 2;
				if (score < bestScore) {
					bestScore = score;
					best = new BlockPos(corner.getX(), level0 + 1, corner.getZ());   // floor level: one above the ground
				}
			}
		}
		return best;
	}

	/** The top solid, dry block of the ground at x, z near a height (not leaves), or null. */
	static BlockPos ground(ServerLevel level, BlockPos at) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(at.getX(), at.getY() + 6, at.getZ());
		for (int i = 0; i < 16; i++, m.move(Direction.DOWN)) {
			if (!level.isLoaded(m)) return null;
			BlockState s = level.getBlockState(m);
			if (s.isAir() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS)) continue;
			if (!s.getFluidState().isEmpty()) return null;
			return m.immutable();
		}
		return null;
	}
}
