package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * A whole place a Xen lays out by itself, with no template: before it builds, it pictures it. It looks over the ground
 * round it (what it can see from where it stands: the lie of the land, water, trees, anything someone built), and in
 * its head tries thousands of ways to put its house there, a pool and a sitting area of its own round it, a road from
 * the way in to its door (with paths off it to each part) and a wall round it all with a gate where the road comes in.
 * It keeps the way it likes best, by its own taste: flat ground, no trees or water in the way, the door facing the way
 * in with open ground in front, a pool close by but not in front of the door, a road that doesn't climb (winding, if it
 * likes that), the parts lined up or spread about. Which parts it wants is its own taste too (Taste: "place:pool" and
 * the rest), and it learns from what people say about the place.
 * <p>Nothing round the house is a copy of what it was shown: it learned a style from those (what a road, a wall, a pool
 * are made of, the lantern on a post, a bench and a table, a bush: Taught.Style) and builds its own, to fit the ground
 * there: a road dug into the ground (it mines the bumps down and fills the dips), a pool dug in, a wall that follows the
 * land. The house is one it was shown (its own version) or one of its own designs.
 * <p>What it pictured can be seen: outlines in the world (each part in its own colour, the road and paths dotted, the
 * wall and its gate, the door marked) while it pictures it and for a while after (and again with /xen layout), and a
 * map of it in its journal.
 */
final class Imagined {
	private Imagined() {}

	/** How far round it looks: a square of 2R + 1 a side. */
	static final int R = 22, S = 2 * R + 1;
	static final byte OPEN = 0, WATER = 1, TREE = 2, BUILT = 3, UNSEEN = 4, NONE = 5;
	/** The colour each part is pictured in. */
	static final int HOUSE = 0xFFAA00, POOL = 0x33DDFF, SEAT = 0xFF55FF, WALL = 0xC8C8C8, GATE = 0xAA77FF, ROAD = 0xFFFFFF;

	/** One part of the place: what, the piece it is (or its own design, or neither: its own), its front-left corner at floor level, the way it faces, its box. */
	record Part(String what, VillageHouses.House shown, Taste.Design design, BlockPos corner, Direction front, int x0, int z0, int x1, int z1,
			int y0, int y1, BlockPos door, int color) {
		BlockPos middle() {
			return new BlockPos((x0 + x1) / 2, corner.getY(), (z0 + z1) / 2);
		}

		boolean has(int x, int z) {
			return x >= x0 && x <= x1 && z >= z0 && z <= z1;
		}
	}

	/**
	 * A place it pictured: the parts (the house first), the road (from its door out, each block the top of the ground
	 * there), the paths off it to the other parts, the wall round it all (each block the top of the ground) and its gate,
	 * the map, what it chose, and the ground as it saw it.
	 */
	record Layout(List<Part> parts, List<BlockPos> road, List<List<BlockPos>> paths, List<BlockPos> wall, Set<Long> gate, String map, String[] chose,
			BlockPos center, Ground ground) {
		Part house() {
			return parts.get(0);
		}

		/** In words: "a house of your own design facing south, a pool beside it, a sitting area by the pool, a wall round it all and a road of 30 blocks out from the door". */
		String describe() {
			StringBuilder sb = new StringBuilder();
			Part h = house();
			sb.append(h.shown() != null ? "the " + h.what() + " you were shown" : "a house of your own design").append(", facing ").append(h.front().getName());
			for (int i = 1; i < parts.size(); i++) {
				Part p = parts.get(i);
				sb.append(", a ").append(p.what()).append(' ').append(where(h, p));
			}
			if (!wall.isEmpty()) sb.append(", a wall round it all with a gate");
			if (!road.isEmpty()) sb.append(" and a road of ").append(road.size()).append(" blocks out from the door").append(paths.isEmpty() ? "" : " with paths to the rest");
			return sb.toString();
		}

		/** Where a part is from the house, as a person would say it (beside it, behind it, out in front). */
		private static String where(Part h, Part p) {
			int dx = (p.x0() + p.x1()) - (h.x0() + h.x1()), dz = (p.z0() + p.z1()) - (h.z0() + h.z1());
			Direction toward = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
			return toward == h.front() ? "out in front" : toward == h.front().getOpposite() ? "behind the house" : "beside the house";
		}
	}

	/** What it knows of the ground round it: the height of the top of each column and what's there. */
	static final class Ground {
		final int cx, cz;
		final int[] top = new int[S * S];
		final byte[] what = new byte[S * S];

		Ground(int cx, int cz) {
			this.cx = cx;
			this.cz = cz;
		}

		int at(int x, int z) {
			int i = x - cx + R, j = z - cz + R;
			return i < 0 || j < 0 || i >= S || j >= S ? -1 : j * S + i;
		}
	}

	// ------------------------------------------------------------------------------ looking over the ground
	/** It looks round: each column's top, and whether it's water, a tree, someone's build, or out of its sight. */
	static Ground survey(ServerLevel level, ServerPlayer p, BlockPos center) {
		Ground g = new Ground(center.getX(), center.getZ());
		Vec3 eye = p.getEyePosition();
		for (int j = 0; j < S; j++) {
			for (int i = 0; i < S; i++) {
				int x = g.cx - R + i, z = g.cz - R + j, k = j * S + i;
				g.top[k] = center.getY() - 1;
				BlockPos high = new BlockPos(x, center.getY() + 12, z);
				if (!level.isLoaded(high) || !level.getBlockState(high).getCollisionShape(level, high).isEmpty()) {
					g.what[k] = NONE;                                              // (a mountain, or not there to see)
					continue;
				}
				BlockPos.MutableBlockPos m = high.mutable();
				byte w = NONE;
				boolean tree = false;
				for (int n = 0; n < 30; n++, m.move(Direction.DOWN)) {
					BlockState s = level.getBlockState(m);
					if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) {
						tree = tree || m.getY() <= center.getY() + 6 && s.is(BlockTags.LOGS);
						continue;
					}
					if (!s.getFluidState().isEmpty()) {
						w = WATER;
						g.top[k] = m.getY();
						break;
					}
					if (s.isAir() || s.canBeReplaced()) continue;
					g.top[k] = m.getY();
					BlockState above = level.getBlockState(m.above());
					w = !Hands.natural(s) || !above.isAir() && !above.canBeReplaced() && !above.is(BlockTags.LOGS) && !above.is(BlockTags.LEAVES) ? BUILT
							: tree ? TREE : OPEN;
					break;
				}
				if (w == OPEN || w == TREE) {                                      // can it see that ground from here?
					Vec3 to = new Vec3(x + 0.5, g.top[k] + 1.05, z + 0.5);
					if (eye.distanceTo(to) > 6) {
						var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
								net.minecraft.world.level.ClipContext.Fluid.NONE, p));
						if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().distManhattan(new BlockPos(x, g.top[k], z)) > 1) w = UNSEEN;
					}
				}
				g.what[k] = w;
			}
		}
		for (ServerPlayer o : level.players()) {                                   // (where someone stands is theirs: nothing goes on top of them)
			if (o instanceof XenPlayer || o.isSpectator()) continue;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				int k = g.at(o.getBlockX() + dx, o.getBlockZ() + dz);
				if (k >= 0) g.what[k] = BUILT;
			}
		}
		return g;
	}

	// ------------------------------------------------------------------------------ the parts, as shapes
	private static final BlockPos REF = new BlockPos(0, 100, 0);

	/** A part's box from its corner (facing a way), and its door from the corner. */
	record Shape(int dx0, int dz0, int dx1, int dz1, int dy0, int dy1, int doorDx, int doorDy, int doorDz) {}

	private static Shape shapeOf(Architect.Plan plan, int margin) {
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
		for (Architect.Step s : plan.steps()) {
			BlockPos q = s.pos();
			x0 = Math.min(x0, q.getX()); x1 = Math.max(x1, q.getX());
			z0 = Math.min(z0, q.getZ()); z1 = Math.max(z1, q.getZ());
			y0 = Math.min(y0, q.getY()); y1 = Math.max(y1, q.getY());
		}
		BlockPos d = plan.door();
		return new Shape(x0 - REF.getX() - margin, z0 - REF.getZ() - margin, x1 - REF.getX() + margin, z1 - REF.getZ() + margin, y0 - REF.getY(), y1 - REF.getY(),
				d.getX() - REF.getX(), d.getY() - REF.getY(), d.getZ() - REF.getZ());
	}

	/** One kind of part, its shape each way it can face. */
	private record Kind(String what, VillageHouses.House shown, Taste.Design design, int color, Shape[] faces) {}

	private static final Direction[] WAYS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private static Kind shownKind(String what, VillageHouses.House h, int color) {
		Shape[] f = new Shape[4];
		for (int i = 0; i < 4; i++) f[i] = shapeOf(VillageHouses.plan(h, REF, WAYS[i], true, "oak"), 0);
		return new Kind(what, h, null, color, f);
	}

	private static Kind designKind(Taste.Design ds, Architect.Palette p, int stage) {
		Shape[] f = new Shape[4];
		for (int i = 0; i < 4; i++) f[i] = shapeOf(Architect.designed(REF, WAYS[i], ds, p, new Random(0), stage), 1);   // (a block round: its details vary)
		return new Kind("house", null, ds, HOUSE, f);
	}

	/** A part of its own (no piece: it makes it to fit): w across, d deep, turned either way. */
	private static Kind ownKind(String what, int w, int d, int color) {
		Shape[] f = new Shape[4];
		for (int i = 0; i < 4; i++) {
			int a = i % 2 == 0 ? w : d, b = i % 2 == 0 ? d : w;
			f[i] = new Shape(0, 0, a - 1, b - 1, -2, 2, a / 2, 1, 0);
		}
		return new Kind(what, null, null, color, f);
	}

	/** A part put down somewhere in its head: the part, and what it costs. */
	private record Fit(int floor, double cost) {}

	private static Fit fit(Ground g, int x0, int z0, int x1, int z1, boolean[] taken) {
		int n = (x1 - x0 + 1) * (z1 - z0 + 1);
		int[] h = new int[n];
		int c = 0, trees = 0, unseen = 0;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				int k = g.at(x, z);
				if (k < 0 || taken[k]) return null;
				byte w = g.what[k];
				if (w == WATER || w == BUILT || w == NONE) return null;
				if (w == TREE) trees++;
				if (w == UNSEEN) unseen++;
				h[c++] = g.top[k];
			}
		}
		Arrays.sort(h);
		int mid = h[n / 2], dev = 0, worst = 0;
		for (int y : h) {
			dev += Math.abs(y - mid);
			worst = Math.max(worst, Math.abs(y - mid));
		}
		if (worst > 3) return null;                                                // (too steep for one floor)
		return new Fit(mid + 1, dev + trees * 6 + unseen * 0.8);                // (a tree in the way: it would have to cut it down)
	}

	/** A part put down somewhere in its head: the part, and what it costs. */
	private record Placed(Part part, double cost) {}

	private static Placed put(Ground g, Kind k, int way, int cx, int cz, boolean[] taken) {
		Shape s = k.faces()[way];
		Fit f = fit(g, cx + s.dx0(), cz + s.dz0(), cx + s.dx1(), cz + s.dz1(), taken);
		if (f == null) return null;
		BlockPos corner = new BlockPos(cx, f.floor(), cz);
		BlockPos door = corner.offset(s.doorDx(), s.doorDy(), s.doorDz());
		return new Placed(new Part(k.what(), k.shown(), k.design(), corner, WAYS[way], cx + s.dx0(), cz + s.dz0(), cx + s.dx1(), cz + s.dz1(),
				f.floor() + s.dy0(), f.floor() + s.dy1(), door, k.color()), f.cost());
	}

	private static void take(Ground g, boolean[] taken, Part p, int margin) {
		for (int x = p.x0() - margin; x <= p.x1() + margin; x++) for (int z = p.z0() - margin; z <= p.z1() + margin; z++) {
			int k = g.at(x, z);
			if (k >= 0) taken[k] = true;
		}
	}

	/** The gap between two boxes (0: touching or overlapping). */
	private static int gap(Part a, Part b) {
		int gx = Math.max(0, Math.max(a.x0() - b.x1(), b.x0() - a.x1()) - 1), gz = Math.max(0, Math.max(a.z0() - b.z1(), b.z0() - a.z1()) - 1);
		return Math.max(gx, gz);
	}

	/** Is the box in front of a door (where the way in is)? */
	private static boolean inFront(Part house, Part p) {
		Direction f = house.front();
		int dx = house.door().getX(), dz = house.door().getZ();
		for (int s = 1; s <= 6; s++) for (int w = -1; w <= 1; w++) {
			int x = dx + f.getStepX() * s + (f.getStepX() == 0 ? w : 0), z = dz + f.getStepZ() * s + (f.getStepZ() == 0 ? w : 0);
			if (x >= p.x0() && x <= p.x1() && z >= p.z0() && z <= p.z1()) return true;
		}
		return false;
	}

	/** Do the boxes line up (an edge or a middle along the same line)? A tidy builder likes that. */
	private static boolean lined(Part a, Part b) {
		return a.x0() == b.x0() || a.x1() == b.x1() || a.z0() == b.z0() || a.z1() == b.z1() || (a.x0() + a.x1()) == (b.x0() + b.x1())
				|| (a.z0() + a.z1()) == (b.z0() + b.z1());
	}


	/**
	 * Picture a place round center: the parts it wants (its taste), tried thousands of ways in its head, the one it
	 * likes best. Null: there's no room for a house here.
	 */
	static Layout picture(Companion c, ServerLevel level, BlockPos center, boolean creative, Taste.Design ds, Architect.Palette palette, int stage, Random r, String biome) {
		long t0 = System.nanoTime();
		Ground g = survey(level, c.player, center);
		Taste t = c.taste;
		List<String> chose = new ArrayList<>();
		// the house: one it was shown here (its own version of it: a desert house in the desert), or its own design (its taste
		// for using what it was shown); one that fits: the next it'd like if not
		List<VillageHouses.House> shown = new ArrayList<>(Taught.houses(biome));
		java.util.Collections.shuffle(shown, r);
		List<Kind> houseKinds = new ArrayList<>();
		boolean useShown = !shown.isEmpty() && t.placeLike("shown house") + r.nextGaussian() * 0.35 > t.placeLike("own house");
		if (useShown) for (VillageHouses.House h : shown.subList(0, Math.min(3, shown.size()))) houseKinds.add(shownKind(h.name(), h, HOUSE));
		houseKinds.add(designKind(ds, palette, stage));
		chose.add(useShown ? "shown house" : "own house");
		// what else it wants (each by how much it likes it, with a bit of chance); in survival less
		boolean wantPool = t.placeLike("pool") + r.nextGaussian() * 0.35 > 0, wantSeat = t.placeLike("seat") + r.nextGaussian() * 0.35 > 0,
				wantWall = t.placeLike("wall") + r.nextGaussian() * 0.35 > (creative ? 0 : 0.3), lamps = t.placeLike("lamps") + r.nextGaussian() * 0.35 > 0,
				garden = t.placeLike("garden") + r.nextGaussian() * 0.35 > 0;
		boolean winding = t.placeLike("winding") + r.nextGaussian() * 0.3 > 0, neat = t.placeLike("neat") + r.nextGaussian() * 0.3 > 0,
				spread = t.placeLike("spread") + r.nextGaussian() * 0.3 > 0;
		List<Kind> extras = new ArrayList<>();
		if (wantPool) {                                                            // its own pool, the size it likes (and can make)
			int w = creative ? 6 + r.nextInt(5) + (spread ? 2 : 0) : 4 + r.nextInt(2), d = creative ? 5 + r.nextInt(3) : 3 + r.nextInt(2);
			extras.add(ownKind("pool", w, d, POOL));
			chose.add("pool");
		}
		if (wantSeat) {
			extras.add(ownKind("sitting area", 3, 3, SEAT));
			chose.add("seat");
		}
		if (wantWall) chose.add("wall");
		if (lamps) chose.add("lamps");
		if (garden) chose.add("garden");
		chose.add(winding ? "winding" : "straight");
		chose.add(neat ? "neat" : "loose");
		chose.add(spread ? "spread" : "close");
		// the way in: toward its friend, or where it stands
		ServerPlayer friend = c.leader != null ? c.server.getPlayerList().getPlayer(c.leader) : c.owner != null ? c.server.getPlayerList().getPlayer(c.owner) : null;
		Vec3 in = friend != null && friend.level() == level && friend.distanceTo(c.player) < 96 ? friend.position() : c.player.position();
		// 1. where the house goes: the flattest ground, its door to the way in, open ground in front, not right at the edge
		record HouseTry(Placed p, double score) {}
		List<HouseTry> houses = new ArrayList<>();
		boolean[] none = new boolean[S * S];
		for (Kind house : houseKinds) {
		if (!houses.isEmpty()) break;
		for (int n = 0; n < 700; n++) {
			int way = r.nextInt(4), cx = g.cx - R + 5 + r.nextInt(S - 10), cz = g.cz - R + 5 + r.nextInt(S - 10);
			Placed p = put(g, house, way, cx, cz, none);
			if (p == null) continue;
			Part h = p.part();
			if (h.x0() < g.cx - R + 4 || h.x1() > g.cx + R - 4 || h.z0() < g.cz - R + 4 || h.z1() > g.cz + R - 4) continue;   // (room for a wall and a road round it)
			BlockPos out = h.door().relative(h.front(), 1);
			int k = g.at(out.getX(), out.getZ());
			if (k < 0 || g.what[k] == WATER || g.what[k] == BUILT || g.what[k] == NONE || Math.abs(g.top[k] + 1 - h.corner().getY()) > 1) continue;
			Vec3 toIn = in.subtract(Vec3.atCenterOf(h.door())).multiply(1, 0, 1);
			double face = toIn.lengthSqr() < 1 ? 0 : toIn.normalize().dot(new Vec3(h.front().getStepX(), 0, h.front().getStepZ()));
			int open = 0;
			for (int s = 1; s <= 5; s++) {
				BlockPos q = h.door().relative(h.front(), s);
				int kq = g.at(q.getX(), q.getZ());
				if (kq >= 0 && g.what[kq] == OPEN && Math.abs(g.top[kq] + 1 - h.corner().getY()) <= 1) open++;
			}
			double off = Math.hypot(h.middle().getX() - g.cx, h.middle().getZ() - g.cz);
			houses.add(new HouseTry(p, -p.cost() + face * 6 + open * 1.2 - off * 0.15));
		}
		}
		if (houses.isEmpty()) return null;
		if (houses.get(0).p().part().shown() == null) chose.set(0, "own house");
		houses.sort(Comparator.comparingDouble(HouseTry::score).reversed());
		// 2. round each of the best few: the rest of the place, the road, the paths, the wall
		double best = -1e18;
		Layout keep = null;
		int tried = 0;
		for (int hi = 0; hi < Math.min(14, houses.size()); hi++) {
			HouseTry ht = houses.get(hi);
			Part h = ht.p().part();
			boolean[] taken = new boolean[S * S];
			take(g, taken, h, 1);
			List<Part> parts = new ArrayList<>(List.of(h));
			double score = ht.score();
			for (Kind k : extras) {
				Placed pick = null;
				double pickScore = -1e18;
				for (int n = 0; n < 90; n++) {
					int way = r.nextInt(4), cx = h.middle().getX() + r.nextInt(31) - 15, cz = h.middle().getZ() + r.nextInt(31) - 15;
					Placed p = put(g, k, way, cx, cz, taken);
					tried++;
					if (p == null) continue;
					Part q = p.part();
					if (q.x0() < g.cx - R + 3 || q.x1() > g.cx + R - 3 || q.z0() < g.cz - R + 3 || q.z1() > g.cz + R - 3) continue;
					int gp = gap(h, q);
					double s = -p.cost();
					if (k.what().equals("pool")) s += gp >= 2 && gp <= (spread ? 8 : 5) ? 4 : -gp * 0.4;
					else {                                                         // a sitting area: by the pool, else by the house
						Part pool = parts.stream().filter(o -> o.what().equals("pool")).findFirst().orElse(null);
						int gb = gap(pool != null ? pool : h, q);
						s += gb >= 1 && gb <= 3 ? 4 : -gb * 0.5;
					}
					if (inFront(h, q)) s -= 8;                                     // (nothing in front of the door: the way in)
					if (neat && lined(h, q)) s += 2.5;
					if (s > pickScore) {
						pickScore = s;
						pick = p;
					}
				}
				if (pick == null) {
					score -= 3;                                                    // (it wanted it, and there was no room)
					continue;
				}
				parts.add(pick.part());
				take(g, taken, pick.part(), 1);
				score += pickScore;
			}
			// the road: out past its porch, then its own way to the edge toward the way in (and on, to wherever that is)
			BlockPos from = h.door().relative(h.front(), 1);
			List<BlockPos> lead = new ArrayList<>();
			for (int s = 0; s < 24; s++) {
				int kf = g.at(from.getX(), from.getZ());
				if (kf < 0 || !taken[kf]) break;
				if (!h.has(from.getX(), from.getZ())) lead.add(new BlockPos(from.getX(), g.top[kf], from.getZ()));
				from = from.relative(h.front());
			}
			boolean[] edge = new boolean[S * S];
			Vec3 dir = in.subtract(Vec3.atCenterOf(from)).multiply(1, 0, 1);
			for (int i = 0; i < S; i++) for (int j = 0; j < S; j++) {
				if (i != 0 && j != 0 && i != S - 1 && j != S - 1) continue;
				Vec3 to = new Vec3(g.cx - R + i - from.getX(), 0, g.cz - R + j - from.getZ());
				if (dir.lengthSqr() < 9 || to.normalize().dot(dir.normalize()) > 0.6) edge[j * S + i] = true;
			}
			List<BlockPos> road = route(g, taken, from, edge, winding, r);
			if (road == null) {
				score -= 25;                                                       // (no way to its door: not a place to live)
				road = List.of();
			} else {
				lead.addAll(road);
				road = lead;
				score -= road.size() * (winding ? 0.05 : 0.1);
			}
			// paths off the road to each part (the side of it nearest the road)
			List<List<BlockPos>> paths = new ArrayList<>();
			if (!road.isEmpty()) {
				boolean[] onRoad = new boolean[S * S];
				for (BlockPos q : road) {
					int k = g.at(q.getX(), q.getZ());
					if (k >= 0) onRoad[k] = true;
				}
				for (int i = 1; i < parts.size(); i++) {
					Part q = parts.get(i);
					List<BlockPos> best1 = null;
					for (BlockPos st : around(q)) {
						int k = g.at(st.getX(), st.getZ());
						if (k < 0 || g.what[k] == WATER || g.what[k] == BUILT || g.what[k] == NONE || g.what[k] == TREE || inAny(parts, st)) continue;
						boolean[] tk = taken.clone();
						tk[k] = false;
						List<BlockPos> p = route(g, tk, st, onRoad, false, r);
						if (p != null && (best1 == null || p.size() < best1.size())) best1 = p;
					}
					if (best1 != null) {
						if (best1.size() > 1) best1 = best1.subList(0, best1.size() - 1);   // (the last is the road itself)
						paths.add(best1);
						score -= best1.size() * 0.05;
					} else {
						score -= 2;
					}
				}
			}
			// the wall: round all of it, a few blocks out, its gate where the road goes through
			List<BlockPos> wall = new ArrayList<>();
			Set<Long> gate = new HashSet<>();
			if (wantWall) {
				int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
				for (Part q : parts) {
					x0 = Math.min(x0, q.x0()); z0 = Math.min(z0, q.z0()); x1 = Math.max(x1, q.x1()); z1 = Math.max(z1, q.z1());
				}
				int px0 = x0, pz0 = z0, px1 = x1, pz1 = z1;
				x0 = Math.max(g.cx - R + 1, x0 - 3); z0 = Math.max(g.cz - R + 1, z0 - 3); x1 = Math.min(g.cx + R - 1, x1 + 3); z1 = Math.min(g.cz + R - 1, z1 + 3);
				for (int n = 0; n < 2; n++) {                                     // (a side on water or someone's build: a block or two in, not through the parts)
					if (z0 < pz0 - 1 && badRow(g, x0, x1, z0, true)) z0++;
					if (z1 > pz1 + 1 && badRow(g, x0, x1, z1, true)) z1--;
					if (x0 < px0 - 1 && badRow(g, z0, z1, x0, false)) x0++;
					if (x1 > px1 + 1 && badRow(g, z0, z1, x1, false)) x1--;
				}
				Set<Long> roadAt = new HashSet<>();
				for (BlockPos q : road) roadAt.add(BlockPos.asLong(q.getX(), 0, q.getZ()));
				int bad = 0, n = 0;
				for (BlockPos q : ring(x0, z0, x1, z1)) {
					n++;
					int k = g.at(q.getX(), q.getZ());
					if (k < 0 || g.what[k] == WATER || g.what[k] == BUILT || g.what[k] == NONE || inAny(parts, q)) {
						bad++;
						continue;
					}
					if (roadAt.contains(BlockPos.asLong(q.getX(), 0, q.getZ()))) {     // the gate: the road and a block either side
						for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) gate.add(BlockPos.asLong(q.getX() + dx, 0, q.getZ() + dz));
					}
					wall.add(new BlockPos(q.getX(), g.top[k], q.getZ()));
				}
				wall.removeIf(q -> gate.contains(BlockPos.asLong(q.getX(), 0, q.getZ())));
				if (bad > n / 4 || gate.isEmpty() && !road.isEmpty()) {
					wall.clear();                                                  // (round water and builds, it'd be all gaps)
					gate.clear();
					score -= 1;
				} else {
					score += 1.5;
				}
			}
			if (spread) score += parts.size() > 1 ? 1.5 : 0;
			if (score > best) {
				best = score;
				keep = new Layout(parts, road, paths, wall, gate, null, chose.toArray(new String[0]), center, g);
			}
		}
		if (keep == null) return null;
		Layout k0 = keep;                                                          // (what it wanted and found no room for isn't part of it)
		if (k0.wall().isEmpty()) chose.remove("wall");
		if (k0.parts().stream().noneMatch(q -> q.what().equals("pool"))) chose.remove("pool");
		if (k0.parts().stream().noneMatch(q -> q.what().equals("sitting area"))) chose.remove("seat");
		String map = map(g, keep, c.player.blockPosition());
		XenMod.LOG.info("{} pictures its place round {} {} ({} ways tried in {} ms): {}\n{}", c.name, center.getX(), center.getZ(), houses.size() + tried,
				(System.nanoTime() - t0) / 1_000_000, String.join(", ", chose), map);
		return new Layout(keep.parts(), keep.road(), keep.paths(), keep.wall(), keep.gate(), map, chose.toArray(new String[0]), center, g);
	}

	/** Is any block of this row of the wall (along x at z, or along z at x) on water, a build, or nothing to stand on? */
	private static boolean badRow(Ground g, int from, int to, int at, boolean alongX) {
		for (int v = from; v <= to; v++) {
			int k = alongX ? g.at(v, at) : g.at(at, v);
			if (k < 0 || g.what[k] == WATER || g.what[k] == BUILT || g.what[k] == NONE) return true;
		}
		return false;
	}

	private static boolean inAny(List<Part> parts, BlockPos q) {
		for (Part p : parts) if (p.has(q.getX(), q.getZ())) return true;
		return false;
	}

	/** The blocks just outside a part's box, the middle of each side first. */
	private static List<BlockPos> around(Part p) {
		int y = p.corner().getY();
		int mx = (p.x0() + p.x1()) / 2, mz = (p.z0() + p.z1()) / 2;
		return List.of(new BlockPos(mx, y, p.z0() - 1), new BlockPos(mx, y, p.z1() + 1), new BlockPos(p.x0() - 1, y, mz), new BlockPos(p.x1() + 1, y, mz));
	}

	/** The blocks round a box, in order (clockwise from the north-west corner). */
	private static List<BlockPos> ring(int x0, int z0, int x1, int z1) {
		List<BlockPos> out = new ArrayList<>();
		for (int x = x0; x <= x1; x++) out.add(new BlockPos(x, 0, z0));
		for (int z = z0 + 1; z <= z1; z++) out.add(new BlockPos(x1, 0, z));
		for (int x = x1 - 1; x >= x0; x--) out.add(new BlockPos(x, 0, z1));
		for (int z = z1 - 1; z > z0; z--) out.add(new BlockPos(x0, 0, z));
		return out;
	}

	/**
	 * A way over the ground from a block to any of the goal blocks, the way it likes (straight, or winding a little):
	 * round what's taken, no climbs of more than a block, no water or builds or trees. Each block is the top of the
	 * ground there. Null: no way.
	 */
	private static List<BlockPos> route(Ground g, boolean[] taken, BlockPos from, boolean[] goal, boolean winding, Random r) {
		int start = g.at(from.getX(), from.getZ());
		if (start < 0) return null;
		double[] cost = new double[S * S];
		int[] prev = new int[S * S];
		Arrays.fill(cost, Double.MAX_VALUE);
		Arrays.fill(prev, -1);
		double[] noise = new double[S * S];
		if (winding) for (int k = 0; k < noise.length; k++) noise[k] = r.nextDouble() * 1.4;
		PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
		cost[start] = 0;
		open.add(new double[] {0, start});
		int end = -1;
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!open.isEmpty()) {
			double[] e = open.poll();
			int k = (int) e[1];
			if (e[0] > cost[k]) continue;
			if (goal[k] && k != start) {
				end = k;
				break;
			}
			int i = k % S, j = k / S;
			for (int[] st : steps) {
				int ni = i + st[0], nj = j + st[1];
				if (ni < 0 || nj < 0 || ni >= S || nj >= S) continue;
				int nk = nj * S + ni;
				byte w = g.what[nk];
				if (taken[nk] && !goal[nk] || w == WATER || w == BUILT || w == NONE || w == TREE) continue;
				int dy = Math.abs(g.top[nk] - g.top[k]);
				if (dy > 1) continue;
				double c = 1 + dy * 3 + (w == UNSEEN ? 1 : 0) + noise[nk];
				if (cost[k] + c < cost[nk]) {
					cost[nk] = cost[k] + c;
					prev[nk] = k;
					open.add(new double[] {cost[nk], nk});
				}
			}
		}
		if (end < 0) return null;
		List<BlockPos> out = new ArrayList<>();
		for (int k = end; k >= 0; k = prev[k]) out.add(new BlockPos(g.cx - R + k % S, g.top[k], g.cz - R + k / S));
		java.util.Collections.reverse(out);
		return out;
	}

	/** The place as a map (north up): H house, P pool, S sitting area, # road, + path, W wall, G gate, D door, X itself, ~ water, T trees, x built, ? out of sight. */
	static String map(Ground g, Layout l, BlockPos me) {
		char[] m = new char[S * S];
		for (int k = 0; k < m.length; k++) m[k] = switch (g.what[k]) {
			case WATER -> '~';
			case TREE -> 'T';
			case BUILT -> 'x';
			case UNSEEN -> '?';
			case NONE -> ' ';
			default -> '.';
		};
		for (Part p : l.parts()) {
			char ch = p.what().equals("pool") ? 'P' : p.what().equals("sitting area") ? 'S' : 'H';
			for (int x = p.x0(); x <= p.x1(); x++) for (int z = p.z0(); z <= p.z1(); z++) {
				int k = g.at(x, z);
				if (k >= 0) m[k] = ch;
			}
		}
		for (List<BlockPos> path : l.paths()) for (BlockPos q : path) {
			int k = g.at(q.getX(), q.getZ());
			if (k >= 0) m[k] = '+';
		}
		for (BlockPos q : l.road()) {
			int k = g.at(q.getX(), q.getZ());
			if (k >= 0) m[k] = '#';
		}
		for (BlockPos q : l.wall()) {
			int k = g.at(q.getX(), q.getZ());
			if (k >= 0) m[k] = 'W';
		}
		for (long gl : l.gate()) {
			BlockPos q = BlockPos.of(gl);
			int k = g.at(q.getX(), q.getZ());
			if (k >= 0 && m[k] != '#' && m[k] != '.') m[k] = 'G';
		}
		int d = g.at(l.house().door().getX(), l.house().door().getZ()), x = g.at(me.getX(), me.getZ());
		if (d >= 0) m[d] = 'D';
		if (x >= 0) m[x] = 'X';
		StringBuilder sb = new StringBuilder();
		for (int j = 0; j < S; j++) sb.append(new String(m, j * S, S).replaceAll("\\s+$", "")).append('\n');
		return sb.toString();
	}

	// ------------------------------------------------------------------------------ showing it
	/** What it pictured, in the world for a player to see: each part's box in its colour, the road and paths dotted, the wall, its gate, the door. */
	static void show(ServerPlayer to, ServerLevel level, Layout l) {
		for (Part p : l.parts()) box(to, level, p.x0(), p.y0(), p.z0(), p.x1() + 1, p.y1() + 1, p.z1() + 1, p.color());
		var road = new DustParticleOptions(ROAD, 2.0f);
		int every = Math.max(1, l.road().size() / 90);
		for (int i = 0; i < l.road().size(); i += every) dot(to, level, l.road().get(i), road, 1.2);
		var path = new DustParticleOptions(ROAD, 1.3f);
		for (List<BlockPos> p : l.paths()) for (BlockPos q : p) dot(to, level, q, path, 1.15);
		var wall = new DustParticleOptions(WALL, 2.0f);
		int ew = Math.max(1, l.wall().size() / 110);
		for (int i = 0; i < l.wall().size(); i += ew) {
			BlockPos q = l.wall().get(i);
			dot(to, level, q, wall, 1.5);
			dot(to, level, q, wall, 2.5);
		}
		var gate = new DustParticleOptions(GATE, 2.5f);
		for (long gl : l.gate()) {
			BlockPos q = BlockPos.of(gl);
			int k = l.ground().at(q.getX(), q.getZ());
			if (k >= 0 && (Math.abs(q.getX() - l.center().getX()) + Math.abs(q.getZ() - l.center().getZ())) % 2 == 0) dot(to, level, new BlockPos(q.getX(), l.ground().top[k], q.getZ()), gate, 3.5);
		}
		BlockPos d = l.house().door();
		var door = new DustParticleOptions(0xFF3030, 2.5f);
		for (int y = 0; y < 2; y++) level.sendParticles(to, door, true, true, d.getX() + 0.5, d.getY() + 0.5 + y, d.getZ() + 0.5, 1, 0, 0, 0, 0);
	}

	private static void dot(ServerPlayer to, ServerLevel level, BlockPos q, DustParticleOptions dust, double up) {
		level.sendParticles(to, dust, true, true, q.getX() + 0.5, q.getY() + up, q.getZ() + 0.5, 1, 0, 0, 0, 0);
	}

	private static void box(ServerPlayer to, ServerLevel level, double x0, double y0, double z0, double x1, double y1, double z1, int color) {
		var dust = new DustParticleOptions(color, 2.5f);
		double[][] ends = {{x0, y0, z0}, {x1, y1, z1}};
		for (int axis = 0; axis < 3; axis++) {
			for (int i = 0; i < 4; i++) {
				double[] at = new double[3];
				int o1 = (axis + 1) % 3, o2 = (axis + 2) % 3;
				at[o1] = ends[i & 1][o1];
				at[o2] = ends[(i >> 1) & 1][o2];
				double len = ends[1][axis] - ends[0][axis];
				int n = (int) Math.max(2, Math.min(16, Math.ceil(len)));
				for (int k = 0; k <= n; k++) {
					at[axis] = ends[0][axis] + len * k / n;
					level.sendParticles(to, dust, true, true, at[0], at[1], at[2], 1, 0, 0, 0, 0);
				}
			}
		}
	}

	// ------------------------------------------------------------------------------ building it: its own, in its style
	/**
	 * Everything round the house as plans, in the order it builds them: the road (dug into the ground: the bumps mined
	 * down, the dips filled, a half step where it climbs, lamps along it) and the paths off it, the pool, the sitting
	 * area, the bushes by the door, the wall. All of its own making, in the style it learned (Taught.Style), in survival of
	 * what it can make.
	 */
	static List<Architect.Plan> plans(Layout l, Taught.Style style, boolean creative, String wood, String biome, Random r) {
		Taught.Style st = style.in(wood);
		Ground g = l.ground();
		Set<String> chose = Set.of(l.chose());
		List<Architect.Plan> out = new ArrayList<>();
		java.util.function.UnaryOperator<String> mat = n -> creative ? n : VillageHouses.adapt(n, wood);
		String light = creative ? st.light() : "torch";
		if (!l.road().isEmpty()) out.add(roadPlan(g, l, st, creative, mat, light, chose.contains("lamps")));
		for (int i = 1; i < l.parts().size(); i++) {
			Part p = l.parts().get(i);
			out.add(p.what().equals("pool") ? poolPlan(g, p, mat.apply(st.rim()), mat.apply(st.floor())) : seatPlan(g, p, st, mat, light));
		}
		if (chose.contains("garden")) {
			Architect.Plan b = gardenPlan(g, l, st, wood, biome, chose.contains("spread"), r);
			if (b != null) out.add(b);
		}
		if (!l.wall().isEmpty()) out.add(wallPlan(g, l, st, mat, light));
		return out;
	}

	private static void put(List<Architect.Step> to, BlockPos p, String spec, int phase, boolean decor) {
		BlockState s = Architect.state(spec);
		if (s != null) to.add(new Architect.Step(p, s, phase, decor));
	}

	private static void dig(List<Architect.Step> to, BlockPos p) {
		to.add(new Architect.Step(p, null, Architect.DIG, false));
	}

	/** The plan's steps in a builder's order: by phase; digging from the top down, the rest from the bottom up. */
	private static Architect.Plan plan(String name, List<Architect.Step> steps, BlockPos door, BlockPos middle) {
		steps.sort((a, b) -> a.phase() != b.phase() ? Integer.compare(a.phase(), b.phase())
				: a.phase() == Architect.DIG ? Integer.compare(b.pos().getY(), a.pos().getY()) : Integer.compare(a.pos().getY(), b.pos().getY()));
		return new Architect.Plan(name, steps, door, middle, Direction.NORTH, null);
	}

	private static int top(Ground g, int x, int z, int or) {
		int k = g.at(x, z);
		return k < 0 || g.what[k] == NONE ? or : g.top[k];
	}

	/**
	 * Its road: level with the ground, not on it (the grass dug out and the road in its place), smoothed along the way
	 * (a bump mined down, a dip filled), headroom cleared, a slab where it climbs so it walks up without a jump; three wide
	 * in creative. The paths off it: one wide. Lamps along it (a post and a light) if it likes them. In survival, a path.
	 */
	private static Architect.Plan roadPlan(Ground g, Layout l, Taught.Style st, boolean creative, java.util.function.UnaryOperator<String> mat, String light, boolean lamps) {
		List<Architect.Step> steps = new ArrayList<>();
		List<BlockPos> road = l.road();
		int n = road.size();
		int[] h = new int[n], t = new int[n];
		for (int i = 0; i < n; i++) h[i] = road.get(i).getY();
		for (int i = 0; i < n; i++) {                                              // its height: the middle of the five round it (bumps and dips gone)
			int[] w = new int[Math.min(n, i + 3) - Math.max(0, i - 2)];
			for (int k = Math.max(0, i - 2), m = 0; k < Math.min(n, i + 3); k++) w[m++] = h[k];
			Arrays.sort(w);
			t[i] = w[w.length / 2];
		}
		for (int i = 1; i < n; i++) t[i] = Math.max(t[i - 1] - 1, Math.min(t[i - 1] + 1, t[i]));   // (a block at a time, no more)
		java.util.Map<Long, Integer> level = new java.util.HashMap<>();
		int half = creative ? 1 : 0;
		for (int i = 0; i < n; i++) {
			BlockPos q = road.get(i), a = road.get(Math.max(0, i - 1)), b = road.get(Math.min(n - 1, i + 1));
			boolean alongX = a.getX() != b.getX();
			for (int s = -half; s <= half; s++) {
				int x = q.getX() + (alongX ? 0 : s), z = q.getZ() + (alongX ? s : 0);
				if (s != 0 && (inAny(l.parts(), new BlockPos(x, 0, z)) || g.at(x, z) < 0 || g.what[g.at(x, z)] != OPEN && g.what[g.at(x, z)] != UNSEEN)) continue;
				level.merge(BlockPos.asLong(x, 0, z), t[i], Math::max);
			}
		}
		for (List<BlockPos> p : l.paths()) for (BlockPos q : p) level.putIfAbsent(BlockPos.asLong(q.getX(), 0, q.getZ()), q.getY());
		String surface = creative ? st.road() : "dirt_path", slab = st.roadSlab();
		for (var e : level.entrySet()) {
			BlockPos c = BlockPos.of(e.getKey());
			int x = c.getX(), z = c.getZ(), y = e.getValue(), ground = top(g, x, z, y);
			for (int k = Math.max(ground, y) + 2; k > y; k--) dig(steps, new BlockPos(x, k, z));   // mined down to it, and room above
			for (int k = ground + 1; k < y; k++) put(steps, new BlockPos(x, k, z), "dirt", Architect.SUPPORT, false);   // a dip filled
			put(steps, new BlockPos(x, y, z), surface, Architect.WALLS, false);
			if (creative) {                                                        // where the next is a block up: a slab, half way
				boolean climb = false;
				for (Direction d : Direction.Plane.HORIZONTAL) climb |= level.getOrDefault(BlockPos.asLong(x + d.getStepX(), 0, z + d.getStepZ()), y) > y;
				if (climb && slab != null) put(steps, new BlockPos(x, y + 1, z), slab + "[type=bottom]", Architect.OUTSIDE, false);
			}
		}
		if (lamps) {                                                               // a lamp every seven blocks, one side then the other
			for (int i = 3, side = 1; i < n - 1; i += 7, side = -side) {
				BlockPos q = road.get(i), a = road.get(i - 1), b = road.get(i + 1);
				boolean alongX = a.getX() != b.getX();
				int x = q.getX() + (alongX ? 0 : side * (half + 1)), z = q.getZ() + (alongX ? side * (half + 1) : 0), k = g.at(x, z);
				if (k < 0 || g.what[k] != OPEN || inAny(l.parts(), new BlockPos(x, 0, z)) || level.containsKey(BlockPos.asLong(x, 0, z))) continue;
				int y = g.top[k];
				put(steps, new BlockPos(x, y + 1, z), mat.apply(st.post()), Architect.OUTSIDE, true);
				put(steps, new BlockPos(x, y + 2, z), mat.apply(st.post()), Architect.OUTSIDE, true);
				put(steps, new BlockPos(x, y + 3, z), light, Architect.OUTSIDE, true);
			}
		}
		BlockPos mid = road.get(n / 2);
		return plan("road", steps, road.get(0), mid.above());
	}

	/**
	 * Its pool, the way you'd dig one: dug out two deep (the ground under it is its bottom), a rim all round laid from
	 * outside it, then water two deep: a few buckets by the rim and the rest fills itself, as water does.
	 */
	private static Architect.Plan poolPlan(Ground g, Part p, String rim, String floor) {
		List<Architect.Step> steps = new ArrayList<>();
		int y = p.corner().getY() - 1;                                             // (the ground level it's sunk into)
		for (int x = p.x0(); x <= p.x1(); x++) for (int z = p.z0(); z <= p.z1(); z++) {
			int ground = top(g, x, z, y);
			for (int k = Math.max(ground, y) + 2; k >= y - 1; k--) dig(steps, new BlockPos(x, k, z));
			boolean edge = x == p.x0() || x == p.x1() || z == p.z0() || z == p.z1();
			if (edge) {
				put(steps, new BlockPos(x, y - 1, z), rim, Architect.WALLS, false);
				put(steps, new BlockPos(x, y, z), rim, Architect.WALLS, false);
			} else {
				put(steps, new BlockPos(x, y - 1, z), "water", Architect.INSIDE, false);
				put(steps, new BlockPos(x, y, z), "water", Architect.INSIDE, false);
			}
		}
		return plan("pool", steps, p.door(), p.middle());
	}

	/** A sitting area: the ground levelled, a table (a post and a top) with a chair either side, a lamp at a corner. */
	private static Architect.Plan seatPlan(Ground g, Part p, Taught.Style st, java.util.function.UnaryOperator<String> mat, String light) {
		List<Architect.Step> steps = new ArrayList<>();
		int y = p.corner().getY() - 1;
		for (int x = p.x0(); x <= p.x1(); x++) for (int z = p.z0(); z <= p.z1(); z++) {
			int ground = top(g, x, z, y);
			for (int k = Math.max(ground, y) + 3; k > y; k--) dig(steps, new BlockPos(x, k, z));
			for (int k = ground + 1; k <= y; k++) put(steps, new BlockPos(x, k, z), "dirt", Architect.SUPPORT, false);
		}
		BlockPos mid = new BlockPos((p.x0() + p.x1()) / 2, y, (p.z0() + p.z1()) / 2);
		put(steps, mid.above(), mat.apply(st.post()), Architect.INSIDE, true);
		put(steps, mid.above(2), mat.apply(st.top()), Architect.INSIDE, true);
		Direction f = p.front().getAxis() == Direction.Axis.Z ? Direction.NORTH : Direction.EAST;
		for (Direction d : new Direction[] {f, f.getOpposite()}) {                  // (a chair's back away from the table)
			put(steps, mid.relative(d).above(), mat.apply(st.bench()) + "[facing=" + d.getName() + ",half=bottom]", Architect.INSIDE, true);
		}
		BlockPos corner = new BlockPos(p.x0(), y, p.z0());
		put(steps, corner.above(), mat.apply(st.post()), Architect.OUTSIDE, true);
		put(steps, corner.above(2), light, Architect.OUTSIDE, true);
		return plan("sitting area", steps, p.door(), p.middle());
	}

	/** Bushes either side of the way to its door, a step out from the house, and trees of its own about the place. */
	private static Architect.Plan gardenPlan(Ground g, Layout l, Taught.Style st, String wood, String biome, boolean spread, Random r) {
		Part h = l.house();
		List<Architect.Step> steps = new ArrayList<>();
		Set<Long> road = new HashSet<>();
		for (BlockPos q : l.road()) for (int d = -1; d <= 1; d++) {
			road.add(BlockPos.asLong(q.getX() + d, 0, q.getZ()));
			road.add(BlockPos.asLong(q.getX(), 0, q.getZ() + d));
		}
		Direction f = h.front();
		for (Direction side : new Direction[] {f.getClockWise(), f.getCounterClockWise()}) {
			for (int along = 2; along <= 4; along += 2) {
				BlockPos q = h.door().relative(f, 1).relative(side, along);
				int k = g.at(q.getX(), q.getZ());
				if (k < 0 || g.what[k] != OPEN || inAny(l.parts(), q) || road.contains(BlockPos.asLong(q.getX(), 0, q.getZ()))) continue;
				String bush = st.bush().endsWith("_leaves") ? st.bush() + "[persistent=true]" : st.bush();
				put(steps, new BlockPos(q.getX(), g.top[k] + 1, q.getZ()), bush, Architect.OUTSIDE, true);
			}
		}
		trees(g, l, steps, wood, biome, spread ? 3 : 2, r);
		return steps.isEmpty() ? null : plan("garden", steps, h.door(), h.door());
	}

	/**
	 * Trees of its own, shaped like the trees it was shown (Taught.trees: the trunk's height, how wide the leaves are at
	 * each height), never the same twice: the trunk a block taller or shorter, a leaf here and there left off at the
	 * edges. On open ground inside the place, clear of the parts, the road and the wall, apart from each other. The tree
	 * it was shown here (a spruce in the snow) in its own wood and leaves, or its own wood's.
	 */
	private static void trees(Ground g, Layout l, List<Architect.Step> steps, String wood, String biome, int want, Random r) {
		List<Taught.TreeShape> shapes = Taught.trees();
		if (shapes.isEmpty()) return;
		Taught.TreeShape shape = shapes.stream().filter(t -> t.biome().equals(biome)).findFirst().orElse(shapes.get(r.nextInt(shapes.size())));
		String log = shape.biome().equals(biome) ? shape.log() : wood + "_log", leaves = shape.biome().equals(biome) ? shape.leaves() : wood + "_leaves";
		if (Architect.state(log) == null) log = "oak_log";
		if (Architect.state(leaves) == null) leaves = "oak_leaves";
		Set<Long> busy = new HashSet<>();
		java.util.function.Consumer<BlockPos> mark = q -> {
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) busy.add(BlockPos.asLong(q.getX() + dx, 0, q.getZ() + dz));
		};
		l.road().forEach(mark);
		l.wall().forEach(mark);
		for (List<BlockPos> p : l.paths()) p.forEach(mark);
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
		for (Part p : l.parts()) {
			for (int x = p.x0() - 3; x <= p.x1() + 3; x++) for (int z = p.z0() - 3; z <= p.z1() + 3; z++) busy.add(BlockPos.asLong(x, 0, z));
			x0 = Math.min(x0, p.x0()); z0 = Math.min(z0, p.z0()); x1 = Math.max(x1, p.x1()); z1 = Math.max(z1, p.z1());
		}
		List<BlockPos> spots = new ArrayList<>();
		for (int x = x0 - 6; x <= x1 + 6; x++) for (int z = z0 - 6; z <= z1 + 6; z++) {
			int k = g.at(x, z);
			if (k >= 0 && g.what[k] == OPEN && !busy.contains(BlockPos.asLong(x, 0, z))) spots.add(new BlockPos(x, g.top[k], z));
		}
		java.util.Collections.shuffle(spots, r);
		List<BlockPos> planted = new ArrayList<>();
		for (BlockPos q : spots) {
			if (planted.size() >= want) break;
			if (planted.stream().anyMatch(o -> o.closerThan(q, 6))) continue;
			planted.add(q);
			int trunk = Math.max(3, shape.trunk() + r.nextInt(3) - 1), lift = trunk - shape.trunk();
			for (int y = 1; y <= trunk; y++) put(steps, q.above(y), log, Architect.WALLS, true);
			for (int i = 0; i < shape.radius().length; i++) {
				int rad = shape.radius()[i];
				if (rad < 0) continue;
				int y = q.getY() + 1 + i + lift;                                   // (the leaves at their height up the trunk, and up with a taller one)
				for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
					if (dx == 0 && dz == 0 && y <= q.getY() + trunk) continue;     // (the trunk)
					boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == rad && rad > 0;
					if (edge && Math.abs(dx) == rad && Math.abs(dz) == rad && r.nextFloat() < 0.6f) continue;   // (round off the corners)
					if (edge && r.nextFloat() < 0.12f) continue;
					put(steps, new BlockPos(q.getX() + dx, y, q.getZ() + dz), leaves + "[persistent=true]", Architect.ROOF, true);
				}
			}
		}
	}

	/** Its wall: two high on the ground all round, capped, a post every few blocks with a light; the gate: two tall posts. */
	private static Architect.Plan wallPlan(Ground g, Layout l, Taught.Style st, java.util.function.UnaryOperator<String> mat, String light) {
		List<Architect.Step> steps = new ArrayList<>();
		String body = mat.apply(st.wall()), post = mat.apply(st.accent()), cap = mat.apply(st.cap());
		List<BlockPos> w = l.wall();
		Set<Long> at = new HashSet<>();
		for (BlockPos q : w) at.add(BlockPos.asLong(q.getX(), 0, q.getZ()));
		for (int i = 0; i < w.size(); i++) {
			BlockPos q = w.get(i);
			int x = q.getX(), z = q.getZ(), y = q.getY();
			boolean nearGate = false;
			for (Direction d : Direction.Plane.HORIZONTAL) nearGate |= l.gate().contains(BlockPos.asLong(x + d.getStepX(), 0, z + d.getStepZ()));
			boolean corner = (at.contains(BlockPos.asLong(x + 1, 0, z)) || at.contains(BlockPos.asLong(x - 1, 0, z)))
					&& (at.contains(BlockPos.asLong(x, 0, z + 1)) || at.contains(BlockPos.asLong(x, 0, z - 1)));
			boolean isPost = nearGate || corner || i % 5 == 0;
			int high = nearGate ? 4 : isPost ? 3 : 2;
			for (int k = 1; k <= high; k++) put(steps, new BlockPos(x, y + k, z), isPost ? post : body, Architect.WALLS, false);
			if (isPost) put(steps, new BlockPos(x, y + high + 1, z), light, Architect.OUTSIDE, true);
			else put(steps, new BlockPos(x, y + 3, z), cap + "[type=bottom]", Architect.ROOF, true);
		}
		BlockPos mid = w.get(w.size() / 2);
		return plan("wall", steps, mid, mid.above());
	}
}
