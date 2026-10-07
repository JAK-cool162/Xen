package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;

import static xen.mod.Architect.*;

/**
 * The cottage, built the way the builders in the guides do it. Its shape is one of three (a house is more than a box):
 * <ul>
 *   <li>a front gable: the gable end facing the way in, jutting out a block over the door;</li>
 *   <li>a long house with a cross gable: the roof's slope to the front, a gabled bay in the middle of it jutting out with
 *   the door in it, dormers either side;</li>
 *   <li>an L: a long house with a gabled wing coming forward at one end, the door in the corner.</li>
 * </ul>
 * Each part: a stone ground floor (a rough course at the bottom, windows two tall, log posts at the corners); over it (a
 * house with tall walls) a timber-framed upper floor (posts, beams, a light infill), jutting out over the door on log
 * pillars (a jetty), else a loft under the roof; a steep roof a block past the walls, its edges thickened in a darker
 * wood, upside-down stairs under the eaves; dormers; a stone chimney; lamps, shutters, sills, flower boxes, bushes and
 * a winding path. The roofs of the parts meet the way real ones do: over each column the highest of them.
 * <p>
 * The same house at every stage (Architect.STAGES), only more of it: basic is its shape in one material; simple adds the
 * logs, the stone and the dormers (depth); good adds the light infill, the trims, sills, shutters, lamps, the chimney
 * (details); perfect adds flower boxes, bushes, a path of mixed blocks with things along it, barrels and hay, and some
 * wear. origin is the front left corner of the main part at floor level; the door faces front; the ground is at y -1.
 */
final class TimberHouse {
	private TimberHouse() {}

	/**
	 * A part of the house: its footprint (u0..u1 across, v0..v1 deep) and its roof, the ridge along v (else along u).
	 * jetty: its front row (v0) only from the upper floor up, on pillars. roofOnly: a dormer (a roof and a window, no
	 * walls of its own under it). top: the top of its walls (8 two floors, 4 one): a lower wing's roof meets the
	 * taller part's wall.
	 */
	record Part(int u0, int v0, int u1, int v1, boolean ridgeV, boolean jetty, boolean roofOnly, int top) {
		private int a(int u, int v) {
			return ridgeV ? u - (u0 - 1) : v - (v0 - 1);
		}

		private int b(int u, int v) {
			return ridgeV ? (u1 + 1) - u : (v1 + 1) - v;
		}

		/** Its roof reaches over this column (a block past its walls all round). */
		boolean over(int u, int v) {
			return u >= u0 - 1 && u <= u1 + 1 && v >= v0 - 1 && v <= v1 + 1;
		}

		/** How high its roof is over this column, above the top of the walls: 1 at the eaves, one more each block in. */
		int rise(int u, int v) {
			return 1 + Math.min(a(u, v), b(u, v));
		}

		/** The way up its roof here (a stair's facing), null on the ridge (a slab). */
		String up(int u, int v) {
			int a = a(u, v), b = b(u, v);
			if (a == b) return null;
			return ridgeV ? (a < b ? "{R}" : "{L}") : (a < b ? "{B}" : "{F}");
		}

		/** The column is the roof's edge over a gable (not an eave). */
		boolean overGable(int u, int v) {
			return ridgeV ? v == v0 - 1 || v == v1 + 1 : u == u0 - 1 || u == u1 + 1;
		}

		/** The wall here is one of its gable ends. */
		boolean onGable(int u, int v) {
			return ridgeV ? (v == v0 || v == v1) && u >= u0 && u <= u1 : (u == u0 || u == u1) && v >= v0 && v <= v1;
		}

		int across(int u, int v) {
			return ridgeV ? u : v;
		}

		int mid() {
			return ridgeV ? (u0 + u1) / 2 : (v0 + v1) / 2;
		}

		int span() {
			return ridgeV ? u1 - u0 + 1 : v1 - v0 + 1;
		}

		/** It stands on this spot (on the ground floor, or higher up). */
		boolean has(int u, int v, boolean groundFloor) {
			return !roofOnly && u >= u0 && u <= u1 && v >= (groundFloor && jetty ? v0 + 1 : v0) && v <= v1;
		}
	}

	/** A spot of wall: a post ('p'), a window ('w'), the door ('d') or plain wall ('x'); the way out; its run's axis. */
	private record Cell(char kind, String out, String axis) {}

	private static long key(int u, int v) {
		return ((long) u << 32) ^ (v & 0xffffffffL);
	}

	private static int ku(long k) {
		return (int) (k >> 32);
	}

	private static int kv(long k) {
		return (int) k;
	}

	static int du(String dir) {
		return dir.equals("{R}") ? 1 : dir.equals("{L}") ? -1 : 0;
	}

	static int dv(String dir) {
		return dir.equals("{B}") ? 1 : dir.equals("{F}") ? -1 : 0;
	}

	private static String opp(String dir) {
		return switch (dir) {
			case "{R}" -> "{L}";
			case "{L}" -> "{R}";
			case "{F}" -> "{B}";
			default -> "{F}";
		};
	}

	/** Along a wall that looks out this way (the right hand way along it). */
	static String along(String out) {
		return out.equals("{F}") || out.equals("{B}") ? "{R}" : "{B}";
	}

	/** What kind of place a palette is for (its path). */
	static String place(Palette p) {
		return switch (p.name()) {
			case "desert" -> "desert";
			case "spruce" -> "cold";
			case "swamp" -> "wet";
			default -> "meadow";
		};
	}

	/** The frame: a darker log than its own when it can pick any block (the light infill needs one to stand out). */
	static String frameOf(Palette p, boolean any) {
		if (!any) return p.frame();
		return p.name().equals("oak") ? "spruce_log" : p.frame();
	}

	static String roofOf(Palette p, boolean any) {
		return any && p.name().equals("oak") ? "dark_oak" : p.roof();
	}

	/** The roof's edges (the barge boards) in a wood that stands out from the roof. */
	static String edgeOf(String roof, boolean any) {
		if (!any) return roof;
		return switch (roof) {
			case "spruce", "oak", "deepslate_tile", "warped", "cherry", "mangrove" -> "dark_oak";
			case "dark_oak", "acacia" -> "spruce";
			default -> roof;
		};
	}

	/** The wood of a log ("spruce" of stripped_spruce_log): its stairs for trims. */
	static String woodOf(String log) {
		return log.replace("stripped_", "").replace("_log", "").replace("_wood", "").replace("_stem", "").replace("_block", "");
	}

	static Plan plan(BlockPos origin, Direction front, Taste.Design ds, Palette p, Random random, int stage) {
		boolean depth = stage >= DEPTH, detail = stage >= DETAIL, polish = stage >= POLISH;
		boolean any = p.light().equals("lantern");                                        // (a creative palette: any block it likes)
		// ---- the shape: its parts
		List<Part> parts = new ArrayList<>(), dormers = new ArrayList<>();
		int kind = !ds.ridgeAlongWidth() ? 0 : ds.w() >= 11 ? 1 : 2;                       // front gable, cross gable, L
		int w, d, doorU, doorV;
		boolean two;
		if (kind == 0) {
			w = Math.max(7, Math.min(11, ds.w() | 1));
			d = Math.max(7, Math.min(13, ds.d()));
			two = ds.wallH() >= 4 && w >= 9;
			parts.add(new Part(0, -1, w - 1, d - 1, true, true, false, two ? 8 : 4));
			doorU = w / 2;
			doorV = 0;
		} else if (kind == 1) {
			w = Math.max(11, Math.min(15, ds.w() | 1));
			d = Math.max(7, Math.min(9, ds.d() | 1));
			two = ds.wallH() >= 4;
			int g = w >= 13 ? 7 : 5, c = w / 2, m = (d - 1) / 2, t = two ? 8 : 4;
			parts.add(new Part(0, 0, w - 1, d - 1, false, false, false, t));
			parts.add(new Part(c - g / 2, -3, c + g / 2, m, true, true, false, t));
			int x = (c - g / 2) / 2;                                                          // dormers either side of the bay, one at the back
			dormers.add(new Part(x - 1, 0, x + 1, m, true, false, true, t));
			dormers.add(new Part(w - 2 - x, 0, w - x, m, true, false, true, t));
			dormers.add(new Part(c - 1, m, c + 1, d - 1, true, false, true, t));
			doorU = c;
			doorV = -2;
		} else {
			w = Math.max(9, Math.min(11, ds.w() | 1));
			d = 7;
			two = ds.wallH() >= 4;
			int m = (d - 1) / 2, t = two ? 8 : 4;
			parts.add(new Part(0, 0, w - 1, d - 1, false, false, false, t));
			parts.add(new Part(0, -4, 4, m, true, true, false, 4));                          // (the wing lower: roofs at different heights)
			doorU = (4 + w - 1) / 2;
			doorV = 0;
			dormers.add(new Part(doorU - 1, 0, doorU + 1, m, true, false, true, t));
			dormers.add(new Part(doorU - 1, m, doorU + 1, d - 1, true, false, true, t));
		}
		int c = w / 2, m = (d - 1) / 2, top = two ? 8 : 4, ladderV = d - 2;
		List<Part> roofs = new ArrayList<>(parts);
		if (depth && ds.loft() && kind != 0) roofs.addAll(dormers);                          // (dormers only over a loft)
		BiPredicate<Integer, Integer> inG = (u, v) -> parts.stream().anyMatch(q -> q.has(u, v, true));
		BiPredicate<Integer, Integer> inU = (u, v) -> parts.stream().anyMatch(q -> q.top() >= 8 && q.has(u, v, false));   // (the upper floor)
		BiPredicate<Integer, Integer> inA = (u, v) -> parts.stream().anyMatch(q -> q.has(u, v, false));
		java.util.function.ToIntBiFunction<Integer, Integer> topAt = (u, v) -> parts.stream().filter(q -> q.has(u, v, false)).mapToInt(Part::top).max().orElse(top);
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Part q : parts) {
			box[0] = Math.min(box[0], q.u0());
			box[1] = Math.min(box[1], q.v0());
			box[2] = Math.max(box[2], q.u1());
			box[3] = Math.max(box[3], q.v1());
		}

		// ---- materials
		String wall = p.wall(), floor = p.floor(), log = frameOf(p, any);
		boolean stoneBase = any || !p.base().equals(p.frame());                            // (no stone yet: plank walls, not walls of logs)
		String frame = depth ? log : wall, low = depth && stoneBase ? (any ? "cobblestone" : p.base()) : wall, high = depth && stoneBase ? p.base() : wall;
		String upper = detail ? accentOf(p, any) : wall;                                    // the infill between the timbers
		String roof = roofOf(p, any), stairs = stairsOf(roof), slab = slabOf(roof);
		String edgeWood = detail ? edgeOf(roof, any) : roof, edge = stairsOf(edgeWood), edgeSlab = slabOf(edgeWood);
		String trim = woodOf(log) + "_stairs";
		String light = p.light().equals("torch") ? "torch" : "lantern[hanging=false]";
		String hanging = p.light().equals("torch") ? "torch" : "lantern[hanging=true]";
		String leaves = (p.name().contains("spruce") ? "spruce" : p.name().contains("birch") ? "birch" : p.name().contains("dark") ? "dark_oak" : "oak")
				+ "_leaves[persistent=true]";
		String post = frame + "[axis=y]";
		boolean glass = !p.window().equals("air");
		Layout L = new Layout(origin, front);

		// ---- the roofs: each part's, except where it's inside another part under that one's roof (the roofs meet the
		// way real ones do: in valleys, or a lower wing's against the taller part's wall); over each column the highest
		Map<Long, List<Part>> roofsAt = new HashMap<>();
		Map<Long, Integer> height = new HashMap<>();
		Map<Long, Part> roofHere = new HashMap<>();
		int maxH = top + 1;
		for (int u = box[0] - 2; u <= box[2] + 2; u++) {
			for (int v = box[1] - 2; v <= box[3] + 2; v++) {
				for (int i = 0; i < roofs.size(); i++) {
					Part q = roofs.get(i);
					if (!q.over(u, v)) continue;
					int h = q.top() + q.rise(u, v);
					boolean hidden = false;
					for (int j = 0; j < parts.size() && !hidden; j++) {
						Part o = parts.get(j);
						if (o == q || !o.has(u, v, false)) continue;
						int oh = o.top() + o.rise(u, v);
						hidden = oh > h || oh == h && j < i;
					}
					if (hidden) continue;
					roofsAt.computeIfAbsent(key(u, v), k -> new ArrayList<>()).add(q);
					Integer was = height.get(key(u, v));
					if (was == null || h > was) {
						height.put(key(u, v), h);
						roofHere.put(key(u, v), q);
					}
				}
				maxH = Math.max(maxH, height.getOrDefault(key(u, v), 0));
			}
		}

		// ---- the ground: cleared (a strip around to walk on, more in front for the path), holes under it filled
		for (int u = box[0] - 3; u <= box[2] + 3; u++) {
			for (int v = box[1] - 5; v <= box[3] + 3; v++) {
				if (inG.test(u, v)) {
					for (int y = 1; y <= maxH + 1; y++) L.dig(u, v, y);
					for (int y = -4; y <= -1; y++) L.put(u, v, y, "dirt", SUPPORT);
				} else {
					for (int y = 0; y <= maxH + 2; y++) L.dig(u, v, y);
				}
			}
		}

		// ---- where the chimney goes (details): up the side, or up a gable end past the ridge
		boolean chimney = detail && ds.chimney();
		int chU = w, chV = kind == 0 ? d - 2 : m;
		Set<Long> noWindowG = new HashSet<>(), noWindowU = new HashSet<>();
		noWindowG.add(key(0, ladderV));                                                      // (behind the ladder)
		noWindowU.add(key(0, ladderV));
		if (chimney) {
			for (int s = -1; s <= 1; s++) noWindowG.add(key(w - 1, chV + s));
			noWindowU.add(key(w - 1, chV));
		}

		// ---- the walls: where the posts, windows and the door are
		Map<Long, Cell> ground = walls(inG, box, parts, key(doorU, doorV), false, noWindowG);
		Map<Long, Cell> upperWalls = walls(inU, box, parts, Long.MIN_VALUE, true, noWindowU);
		Map<Long, Cell> allWalls = walls(inA, box, parts, Long.MIN_VALUE, true, noWindowU);

		// ---- the floor, on a foundation of stone under the walls (no dirt showing under a house)
		for (int u = box[0]; u <= box[2]; u++) {
			for (int v = box[1]; v <= box[3]; v++) {
				if (!inG.test(u, v)) continue;
				boolean edgeOfIt = ground.containsKey(key(u, v));
				L.put(u, v, 0, edgeOfIt ? low : floor, SUPPORT);
				if (edgeOfIt && depth && stoneBase) for (int y = -2; y <= -1; y++) L.put(u, v, y, low, SUPPORT);
			}
		}
		// ---- the ground floor (y 1-3): stone, log posts, windows two tall, a beam over the door
		for (var e : ground.entrySet()) {
			int u = ku(e.getKey()), v = kv(e.getKey());
			Cell cl = e.getValue();
			for (int y = 1; y <= 3; y++) {
				if (cl.kind() == 'p') L.put(u, v, y, post, FRAME);
				else if (cl.kind() == 'd') {
					if (y == 3) L.put(u, v, y, depth ? frame + "[axis=" + cl.axis() + "]" : wall, FRAME);
				} else if (cl.kind() == 'w' && y >= 2) {
					if (glass) L.put(u, v, y, p.window(), WALLS);                                // (no glass yet: an open window)
				} else L.put(u, v, y, y == 1 ? low : high, WALLS);
			}
		}
		// ---- y 4: beams round the top of the ground floor and under the jetty, the floor above (or the loft) inside
		for (int u = box[0]; u <= box[2]; u++) {
			for (int v = box[1]; v <= box[3]; v++) {
				if (!inA.test(u, v)) continue;
				Cell cg = ground.get(key(u, v)), cu = upperWalls.get(key(u, v)), ca = allWalls.get(key(u, v));
				if (cg != null || cu != null || ca != null || !inG.test(u, v)) {
					String axis = cg != null ? cg.axis() : cu != null ? cu.axis() : ca != null ? ca.axis() : "{U}";
					L.put(u, v, 4, depth ? frame + "[axis=" + axis + "]" : wall, FRAME);
				} else if (!(u == 1 && v == ladderV)) {
					L.put(u, v, 4, floor, WALLS);
				}
			}
		}
		for (int y = 1; y <= 5; y++) L.put(1, ladderV, y, "ladder[facing={R}]", INSIDE);
		// ---- the jetties: on log pillars under a whole upper floor, else on brackets
		for (Part q : parts) {
			if (!q.jetty()) continue;
			for (int u : new int[] {q.u0(), q.u1()}) {
				if (q.top() >= 8) {
					ground(L, u, q.v0());
					for (int y = 0; y <= 3; y++) L.put(u, q.v0(), y, post, FRAME);
				} else if (depth) {
					L.put(u, q.v0(), 3, trim + "[facing={B},half=top,shape=straight]", FRAME);
				}
			}
		}
		// ---- the upper floor (y 5-7, the top beams at 8): timber framed
		if (two) {
			for (var e : upperWalls.entrySet()) {
				int u = ku(e.getKey()), v = kv(e.getKey());
				Cell cl = e.getValue();
				boolean inward = cl.out() != null && inA.test(u + du(cl.out()), v + dv(cl.out()));   // (a wall to a lower wing's attic)
				for (int y = 5; y <= top; y++) {
					if (y == top) L.put(u, v, y, depth ? frame + "[axis=" + cl.axis() + "]" : wall, FRAME);
					else if (cl.kind() == 'p') L.put(u, v, y, post, FRAME);
					else if (cl.kind() == 'w' && y >= 6 && !inward) {
						if (glass) L.put(u, v, y, p.window(), WALLS);
					} else L.put(u, v, y, upper, WALLS);
				}
			}
		}
		// ---- the roof: over each column the stair of the roof that's highest there (a slab on a ridge); the edges over
		// the gables thick (a stair under each) and darker, upside-down stairs under the eaves
		for (var e : roofsAt.entrySet()) {
			int u = ku(e.getKey()), v = kv(e.getKey());
			for (Part q : e.getValue()) {
				int y = q.top() + q.rise(u, v);
				String up = q.up(u, v);
				boolean gableEdge = q.overGable(u, v);
				if (up == null) {
					L.put(u, v, y, (gableEdge ? edgeSlab : slab) + "[type=bottom]", ROOF);
					continue;
				}
				L.put(u, v, y, (gableEdge ? edge : stairs) + "[facing=" + up + ",half=bottom,shape=straight]", ROOF);
				if (inA.test(u, v)) continue;
				if (depth && gableEdge && !L.has(u, v, y - 1)) L.put(u, v, y - 1, edge + "[facing=" + opp(up) + ",half=top,shape=straight]", ROOF);
				if (detail && !gableEdge && y == q.top() + 1 && !L.has(u, v, q.top())) {
					L.put(u, v, q.top(), trim + "[facing=" + up + ",half=top,shape=straight]", ROOF, true);
				}
			}
		}
		// ---- the walls up under the roof: the gables timber framed (a post up the middle, braces under the roof,
		// windows either side of the post), a dormer's front a window between two posts, the eaves closed
		for (long k : allWalls.keySet()) {
			Integer h = height.get(k);
			if (h == null) continue;
			int u = ku(k), v = kv(k), cellTop = topAt.applyAsInt(u, v);
			Part q = roofHere.get(k);
			boolean face = q.onGable(u, v), small = q.span() <= 3;
			int dist = Math.abs(q.across(u, v) - q.mid());
			for (int y = cellTop + 1; y < h; y++) {
				String block;
				if (!face) block = upper;
				else if (small) block = dist == 0 ? (glass ? p.window() : null) : post;
				else if (dist == 0) block = post;
				else if (glass && dist == 1 && y - cellTop >= 2 && y - cellTop <= 3) block = p.window();
				else block = upper;
				if (block == null) L.dig(u, v, y);
				else L.put(u, v, y, block, ROOF);
			}
		}
		// ---- the chimney: a stone stack with a flue, the fire at its foot, the smoke out of the top
		if (chimney) {
			int chTop = kind == 0 ? top + 4 : height.getOrDefault(key(chU, chV), top + 4) + 1;
			chimney(L, chU, chV, "{R}", chTop, any, p.trapdoor());
		}
		// ---- the door, a step up to it, lamps by it (under the jetty, or under a little hood)
		Cell left = ground.get(key(doorU - 1, doorV)), right = ground.get(key(doorU + 1, doorV));
		int doorIn = depth && left != null && right != null && left.kind() == 'p' && right.kind() == 'p' ? 1 : 0;   // (set back, in its frame)
		if (doorIn > 0) L.put(doorU, doorV, 0, floor, SUPPORT);
		L.put(doorU, doorV + doorIn, 1, p.door() + "[facing={B},half=lower,hinge=left]", DOORS);
		ground(L, doorU, doorV - 1);
		L.put(doorU, doorV - 1, 0, stairsOf(floor) + "[facing={B},half=bottom,shape=straight]", DOORS, true);
		boolean underJetty = !inG.test(doorU, doorV - 1) && inU.test(doorU, doorV - 1);
		if (detail) {
			if (!underJetty) {
				for (int s = -1; s <= 1; s++) L.put(doorU + s, doorV - 1, 3, stairs + "[facing={B},half=bottom,shape=straight]", OUTSIDE, true);
			}
			L.put(doorU - 1, doorV - 1, underJetty ? 3 : 2, hanging, OUTSIDE, true);
			L.put(doorU + 1, doorV - 1, underJetty ? 3 : 2, hanging, OUTSIDE, true);
		}
		// ---- the windows: sills (details) or flower boxes (polish) under them
		if (detail) {
			List<long[]> sides = new ArrayList<>();
			for (int floorAt : two ? new int[] {0, 4} : new int[] {0}) {
				Map<Long, Cell> ws = floorAt == 0 ? ground : upperWalls;
				for (var e : ws.entrySet()) {
					Cell cl = e.getValue();
					if (cl.kind() != 'w' || cl.out() == null) continue;
					int u = ku(e.getKey()), v = kv(e.getKey());
					if (inA.test(u + du(cl.out()), v + dv(cl.out()))) continue;
					String out = cl.out(), t = along(out);
					int nu = du(out), nv = dv(out), tu = du(t), tv = dv(t), y0 = floorAt + 1;
					if (polish) {
						if (floorAt == 0) {
							L.put(u + nu, v + nv, y0, any ? (random.nextBoolean() ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]") : leaves, OUTSIDE, true);
						} else {
							L.put(u + nu, v + nv, y0, any ? "moss_block" : "dirt", OUTSIDE, true);
							L.put(u + nu, v + nv, y0 + 1, flower(random), OUTSIDE, true);
						}
						L.put(u + 2 * nu, v + 2 * nv, y0, p.trapdoor() + "[facing=" + out + ",half=bottom,open=true]", OUTSIDE, true);
						for (int s : new int[] {-1, 1}) sides.add(new long[] {u + nu + s * tu, v + nv + s * tv, y0, s * (tu + tv), nu, nv});
					} else {
						L.put(u + nu, v + nv, y0, trim + "[facing=" + opp(out) + ",half=top,shape=straight]", OUTSIDE, true);
					}
				}
			}
			for (long[] sd : sides) {                                                         // the flower boxes' ends
				int u = (int) sd[0], v = (int) sd[1], y = (int) sd[2];
				if (L.has(u, v, y)) continue;
				String dir = sd[4] == 0 ? (sd[3] < 0 ? "{F}" : "{B}") : (sd[3] < 0 ? "{L}" : "{R}");   // (away from the box)
				L.put(u, v, y, p.trapdoor() + "[facing=" + dir + ",half=bottom,open=true]", OUTSIDE, true);
			}
		}
		// ---- texture (details): a few of the roof's stairs of a wood (or stone) near its colour, the way builders
		// break up a plain roof
		if (detail && any) texture(L, roof, random);
		// ---- outside: the path (plain, then polished), lamps where it starts, bushes, barrels and hay
		BiPredicate<Integer, Integer> keepOff = (u, v) -> inU.test(u, v) || u == chU && Math.abs(v - chV) <= 1;
		if (depth) {
			Paths.lay(L, doorU, doorV - 2, 10 + w / 2, place(p), polish, any, random, leaves, p.fence(), light, stairsOf(floor), keepOff);
		}
		if (detail) {
			for (int s : new int[] {-2, 2}) {
				if (keepOff.test(doorU + s, doorV - 2)) continue;
				ground(L, doorU + s, doorV - 2);
				L.put(doorU + s, doorV - 2, 0, p.fence(), OUTSIDE, true);
				L.put(doorU + s, doorV - 2, 1, light, OUTSIDE, true);
			}
		}
		if (polish) {
			for (var e : ground.entrySet()) {                                                // bushes along the walls, not in the way
				Cell cl = e.getValue();
				if (cl.out() == null) continue;
				int u = ku(e.getKey()) + du(cl.out()), v = kv(e.getKey()) + dv(cl.out());
				if (L.has(u, v, 0) || L.has(u, v, -1) || keepOff.test(u, v) || Math.abs(u - doorU) + Math.abs(v - doorV) <= 3) continue;
				if (random.nextFloat() > 0.5f) continue;
				ground(L, u, v);
				L.put(u, v, 0, bushLeaves(leaves, any, random), OUTSIDE, true);
				if (random.nextFloat() < 0.3f) L.put(u, v, 1, bushLeaves(leaves, any, random), OUTSIDE, true);
			}
			for (int[] q : new int[][] {{-1, d - 3, 0}, {-1, d - 2, 1}, {-2, d - 3, 1}}) {   // barrels and hay by the left wall
				if (L.has(q[0], q[1], 0) || L.has(q[0], q[1], 1)) continue;
				ground(L, q[0], q[1]);
				L.put(q[0], q[1], 0, q[2] == 0 ? "barrel[facing=up]" : "hay_block", OUTSIDE, true);
			}
			wear(L, random, any);
			if (any) tree(L, box, random, leaves, log);
		}
		// ---- inside: the kitchen and the table downstairs, the bed and a chest up top
		L.put(w - 2, d - 2, 1, "crafting_table", INSIDE, true);
		L.put(w - 3, d - 2, 1, "furnace[facing={F}]", INSIDE, true);
		if (w - 4 > 1) L.put(w - 4, d - 2, 1, "chest[facing={F}]", INSIDE, true);
		L.put(w - 2, 1, 1, "barrel[facing=up]", INSIDE, true);
		L.put(c + 1, d / 2, 1, p.fence(), INSIDE, true);
		L.put(c + 1, d / 2, 2, floor.replace("_planks", "") + "_pressure_plate", INSIDE, true);
		L.put(c, d / 2, 1, stairsOf(floor) + "[facing={L},half=bottom,shape=straight]", INSIDE, true);
		L.put(1, 1, 1, "potted_poppy", INSIDE, true);
		L.put(c, d / 2, 3, hanging, INSIDE, true);
		L.put(c, d / 2 + 1, 5, "red_bed[facing={B},part=foot]", INSIDE, true);
		L.put(c + 1, d - 2, 5, "chest[facing={F}]", INSIDE, true);
		L.put(c, m, maxH - 2, hanging, INSIDE, true);
		net.minecraft.world.phys.AABB inside = new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(L.pos(0, 0, 1)),
				net.minecraft.world.phys.Vec3.atCenterOf(L.pos(w - 1, d - 1, maxH)));
		return new Plan(stage >= POLISH ? "house" : STAGES[stage] + " house", L.steps(), L.pos(doorU, doorV + doorIn, 1), L.pos(c, d / 2, 1), front, inside);
	}

	/**
	 * The walls round a footprint, spot by spot: posts at the corners, and along each straight run between them posts
	 * every few blocks with windows in the middle of the bays; on a run with the door, posts either side of it (a
	 * door frame); on an upper floor's gable end, a post up the middle with windows either side of it.
	 */
	private static Map<Long, Cell> walls(BiPredicate<Integer, Integer> in, int[] box, List<Part> parts, long door, boolean upperFloor, Set<Long> noWindow) {
		Map<Long, Cell> cells = new HashMap<>();
		for (int u = box[0]; u <= box[2]; u++) {
			for (int v = box[1]; v <= box[3]; v++) {
				if (!in.test(u, v)) continue;
				boolean f = !in.test(u, v - 1), b = !in.test(u, v + 1), l = !in.test(u - 1, v), r = !in.test(u + 1, v);
				boolean diag = !in.test(u - 1, v - 1) || !in.test(u + 1, v - 1) || !in.test(u - 1, v + 1) || !in.test(u + 1, v + 1);
				boolean alongU = f || b, alongV = l || r;
				if (!alongU && !alongV && !diag) continue;
				if (alongU == alongV || f && b || l && r) cells.put(key(u, v), new Cell('p', null, "{U}"));   // a corner
			}
		}
		for (int pass = 0; pass < 2; pass++) {
			boolean alongU = pass == 0;
			int lineFrom = alongU ? box[1] : box[0], lineTo = alongU ? box[3] : box[2];
			int from = alongU ? box[0] : box[1], to = alongU ? box[2] : box[3];
			for (int line = lineFrom; line <= lineTo; line++) {
				for (String out : alongU ? new String[] {"{F}", "{B}"} : new String[] {"{L}", "{R}"}) {
					int i = from;
					while (i <= to) {
						if (!runCell(in, cells, alongU ? i : line, alongU ? line : i, out)) {
							i++;
							continue;
						}
						int a = i;
						while (i + 1 <= to && runCell(in, cells, alongU ? i + 1 : line, alongU ? line : i + 1, out)) i++;
						run(cells, parts, alongU, line, a, i, out, door, upperFloor, noWindow);
						i++;
					}
				}
			}
		}
		return cells;
	}

	private static boolean runCell(BiPredicate<Integer, Integer> in, Map<Long, Cell> cells, int u, int v, String out) {
		return in.test(u, v) && !cells.containsKey(key(u, v)) && !in.test(u + du(out), v + dv(out));
	}

	/** One straight run of wall, a..b along its line (a corner post before a and after b). */
	private static void run(Map<Long, Cell> cells, List<Part> parts, boolean alongU, int line, int a, int b, String out, long door, boolean upperFloor,
			Set<Long> noWindow) {
		int n = b - a + 3;                                                                     // (with the corners: positions 0..n-1)
		String axis = alongU ? "{U}" : "{V}";
		int doorAt = -1;
		for (int k = 1; k < n - 1; k++) if (key(alongU ? a + k - 1 : line, alongU ? line : a + k - 1) == door) doorAt = k;
		int midU = alongU ? (a + b) / 2 : line, midV = alongU ? line : (a + b) / 2;
		Part gable = null;
		if (upperFloor) for (Part q : parts) if (!q.roofOnly() && q.onGable(midU, midV)) gable = q;
		TreeSet<Integer> posts = new TreeSet<>(List.of(0, n - 1));
		int mid = gable == null ? -1 : gable.mid() - (a - 1);
		if (doorAt >= 0) {
			if (n >= 7) {
				posts.add(doorAt - 1);
				posts.add(doorAt + 1);
			}
		} else if (gable != null) {
			posts.add(mid);
			if (gable.span() >= 9) {
				posts.add(mid - 2);
				posts.add(mid + 2);
			}
		} else {
			posts.addAll(Architect.posts(n));
		}
		for (int k = 1; k < n - 1; k++) {
			int u = alongU ? a + k - 1 : line, v = alongU ? line : a + k - 1;
			char kind;
			if (k == doorAt) kind = 'd';
			else if (posts.contains(k)) kind = 'p';
			else if (noWindow.contains(key(u, v))) kind = 'x';
			else if (doorAt >= 0) kind = n < 7 ? (Math.abs(k - doorAt) == 1 ? 'w' : 'x') : windowIn(k, posts) ? 'w' : 'x';
			else if (gable != null) kind = Math.abs(k - mid) == 1 || gable.span() >= 11 && Math.abs(k - mid) == 4 ? 'w' : 'x';
			else kind = windowIn(k, posts) ? 'w' : 'x';
			cells.put(key(u, v), new Cell(kind, out, axis));
		}
	}

	/** In the middle of its bay (between two posts): one block, or two of an even bay. */
	private static boolean windowIn(int k, TreeSet<Integer> posts) {
		Integer a = posts.lower(k), b = posts.higher(k);
		if (a == null || b == null) return false;
		int len = b - a - 1;
		double mid = (a + b) / 2.0;
		return Math.abs(k - mid) < (len % 2 == 0 ? 1 : 0.6);
	}

	/**
	 * A chimney the way it's built: against a wall (outside it at (u, v), the wall the other way from out), a foot of
	 * stone three wide with its shoulders sloping in, then a stack one block square up past the roof, the fire in a pot
	 * at the top (trapdoors all round it: the flames out of sight, the smoke rising out of it). Bricks over a
	 * cobblestone foot (any block), else all cobblestone.
	 */
	static void chimney(Layout G, int u, int v, String out, int topY, boolean any, String trapdoor) {
		String t = along(out);
		int tu = du(t), tv = dv(t);
		String foot = "cobblestone", stack = any ? "bricks" : "cobblestone";
		for (int s = -1; s <= 1; s++) {
			ground(G, u + s * tu, v + s * tv);
			for (int y = 0; y <= 1; y++) G.put(u + s * tu, v + s * tv, y, foot, OUTSIDE, true);
			if (s != 0) G.put(u + s * tu, v + s * tv, 2, stairsOf(foot) + "[facing=" + (s < 0 ? t : opp(t)) + ",half=bottom,shape=straight]", OUTSIDE, true);
		}
		for (int y = 2; y < topY; y++) G.put(u, v, y, stack, OUTSIDE, true);
		G.put(u, v, topY, "campfire[lit=true]", OUTSIDE, true);
		for (String dir : new String[] {"{F}", "{B}", "{L}", "{R}"}) {
			G.put(u + du(dir), v + dv(dir), topY, trapdoor + "[facing=" + dir + ",half=bottom,open=true]", OUTSIDE, true);
		}
		for (int y = topY + 1; y <= topY + 4; y++) G.dig(u, v, y);
	}

	/**
	 * Wear, the way builders texture: in clusters (a patch of a few blocks, not single ones scattered), moss thicker
	 * near the ground, cracks higher up; cobblestone gets mossy, stone bricks mossy or cracked, a plinth of andesite
	 * here and there.
	 */
	static void wear(Layout L, Random random, boolean any) {
		if (!any) return;
		long seed = random.nextLong();
		for (var e : L.at.entrySet()) {
			Step st = e.getValue();
			if (st.state() == null) continue;
			BlockPos p = st.pos();
			int y = p.getY() - L.origin.getY();
			double patch = clusterNoise(seed, p.getX() >> 1, p.getY() >> 1, p.getZ() >> 1) * 0.75 + clusterNoise(seed ^ 77, p.getX(), p.getY(), p.getZ()) * 0.25;
			double low = y <= 1 ? 0.25 : y <= 3 ? 0.1 : 0;                        // (more of it near the ground)
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.state().getBlock()).getPath();
			String to = switch (id) {
				case "cobblestone" -> patch < 0.22 + low ? "mossy_cobblestone" : patch > 0.9 ? "andesite" : null;
				case "stone_bricks" -> patch < 0.14 + low ? "mossy_stone_bricks" : patch > 0.86 ? "cracked_stone_bricks" : null;
				case "cobblestone_stairs" -> patch < 0.2 + low ? "mossy_cobblestone_stairs" : null;
				case "stone_brick_stairs" -> patch < 0.15 + low ? "mossy_stone_brick_stairs" : null;
				default -> null;
			};
			if (to == null) continue;
			var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(to));
			if (block.isEmpty()) continue;
			e.setValue(new Step(st.pos(), block.get().withPropertiesOf(st.state()), st.phase(), st.decor()));
		}
	}

	/** A value 0..1 that's the same for a spot every time (the same seed), different from spot to spot. */
	private static double clusterNoise(long seed, int x, int y, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		return (h >>> 11) / (double) (1L << 53);
	}

	/**
	 * A tree by the house (landscaping: a house looks placed with nature round it): a trunk four high with a branch,
	 * a round crown of leaves with a few gaps, behind a back corner; inside the cleared ground only.
	 */
	private static void tree(Layout L, int[] box, Random random, String leaves, String log) {
		String wood = log.contains("dark_oak") || log.contains("spruce") ? "oak_log" : log.replace("stripped_", "");
		if (!wood.endsWith("_log")) wood = "oak_log";
		int tu = box[0] - 2, tv = box[3] + 2;
		ground(L, tu, tv);
		for (int y = 0; y <= 4; y++) L.put(tu, tv, y, wood + "[axis=y]", OUTSIDE, true);
		L.put(tu + 1, tv, 3, wood + "[axis={U}]", OUTSIDE, true);
		for (int du = -2; du <= 2; du++) {
			for (int dv = -2; dv <= 2; dv++) {
				for (int y = 3; y <= 7; y++) {
					int r = y == 3 || y == 7 ? 1 : 2;
					if (Math.abs(du) > r || Math.abs(dv) > r || Math.abs(du) == 2 && Math.abs(dv) == 2) continue;
					if (du == 0 && dv == 0 && y <= 4) continue;                                 // (the trunk)
					int u = tu + du, v = tv + dv;
					if (u < box[0] - 3 || v > box[3] + 3 || u > box[2] + 3) continue;              // (only where the ground was cleared)
					if (L.has(u, v, y) || random.nextFloat() < 0.12f) continue;
					L.put(u, v, y, leaves, OUTSIDE, true);
				}
			}
		}
	}

	/** Some of the roof's plain stairs and slabs of a block near its colour (not the edges): a roof with texture. */
	private static void texture(Layout L, String roof, Random random) {
		String alt = switch (roof) {
			case "dark_oak" -> "spruce";
			case "spruce" -> "dark_oak";
			case "oak" -> "spruce";
			case "deepslate_tile" -> "deepslate_brick";
			case "warped" -> "oxidized_cut_copper";
			case "acacia" -> "smooth_red_sandstone";
			default -> null;
		};
		long seed = random.nextLong();
		if (alt == null) return;
		String st = stairsOf(roof), sl = slabOf(roof), altSt = stairsOf(alt), altSl = slabOf(alt);
		for (var e : L.at.entrySet()) {
			Step s = e.getValue();
			if (s.state() == null || s.phase() != ROOF) continue;
			BlockPos q = s.pos();
			if (clusterNoise(seed, q.getX() >> 1, q.getY(), q.getZ() >> 1) >= 0.18) continue;   // (in patches)
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).getPath();
			String to = id.equals(st) ? altSt : id.equals(sl) ? altSl : null;
			if (to == null) continue;
			var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.withDefaultNamespace(to));
			if (block.isEmpty()) continue;
			e.setValue(new Step(s.pos(), block.get().withPropertiesOf(s.state()), s.phase(), s.decor()));
		}
	}

	private static String flower(Random r) {
		String[] f = {"poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "red_tulip", "pink_tulip"};
		return f[r.nextInt(f.length)];
	}
}
