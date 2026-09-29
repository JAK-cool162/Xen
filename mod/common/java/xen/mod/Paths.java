package xen.mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.BiPredicate;

/**
 * Paths the way builders make them (not a straight line of dirt path): two or three wide, winding a little but never
 * broken, of blocks that suit the place (dirt path with coarse dirt, packed mud, gravel and a few cobbles in a meadow;
 * sandstone in a desert; gravel and stone in the cold) laid in patches the way a worn path looks, the edges softened by
 * a stray block beside them, and along the sides now and then a bush, flowers, a rock, a lamp post or a bench. Only a
 * "perfect" path gets the mix and the things along it; a simpler one is plain dirt path, two wide.
 */
final class Paths {
	private Paths() {}

	/** The blocks of a path in this kind of place, with their shares. */
	static String[][] mix(String place) {
		return switch (place) {
			case "desert" -> new String[][] {{"smooth_sandstone", "50"}, {"sandstone", "30"}, {"coarse_dirt", "20"}};
			case "cold" -> new String[][] {{"gravel", "45"}, {"cobblestone", "30"}, {"coarse_dirt", "25"}};
			case "wet" -> new String[][] {{"packed_mud", "50"}, {"coarse_dirt", "30"}, {"mud_bricks", "20"}};
			default -> new String[][] {{"dirt_path", "60"}, {"coarse_dirt", "30"}, {"gravel", "10"}};
		};
	}

	private static String pick(String[][] mix, Random r) {
		int total = 0;
		for (String[] m : mix) total += Integer.parseInt(m[1]);
		int x = r.nextInt(total);
		for (String[] m : mix) if ((x -= Integer.parseInt(m[1])) < 0) return m[0];
		return mix[0][0];
	}

	private static long key(int u, int v) {
		return ((long) u << 32) ^ (v & 0xffffffffL);
	}

	/**
	 * A path from (u0, v0) out towards the front (v going down), len blocks long, on the ground (y -1, what stands on
	 * it from y 0). polished: the mix, the winding, the soft edges and the things along it; else plain dirt path, two
	 * wide. keepOff: spots it stays off (the house).
	 */
	static void lay(Architect.Layout G, int u0, int v0, int len, String place, boolean polished, boolean anyBlock, Random r,
			String leaves, String fence, String light, String seat, BiPredicate<Integer, Integer> keepOff) {
		String[][] mix = mix(place);                                                        // (two or three blocks: more is noise)
		Map<Long, String> patch = new HashMap<>();
		Map<Long, Boolean> laid = new HashMap<>();
		int off = 0, prevFrom = u0 - 1, prevTo = u0 + 1, sinceTurn = 0, sinceDeco = 0;
		boolean leftNext = r.nextBoolean();
		for (int i = 0; i < len; i++) {
			int v = v0 - i;
			if (polished && i > 2 && ++sinceTurn >= 3 && r.nextFloat() < 0.45f) {          // (it winds, a block at a time)
				off = Math.max(-2, Math.min(2, off + (r.nextBoolean() ? 1 : -1)));
				sinceTurn = 0;
			}
			int width = i < 2 || polished && r.nextFloat() < 0.5f ? 3 : 2;
			int from = u0 + off - (width == 3 ? 1 : 0), to = from + width - 1;
			int a = polished ? Math.min(from, prevFrom) : from, b = polished ? Math.max(to, prevTo) : to;   // (where it turns: no gaps)
			for (int u = a; u <= b; u++) {
				if (keepOff.test(u, v)) continue;
				String block = !polished ? "dirt_path" : r.nextFloat() < 0.25f ? pick(mix, r)
						: patch.computeIfAbsent(key(u >> 1, v >> 1), k -> pick(mix, r));
				G.put(u, v, -1, block, Architect.OUTSIDE, true);
				G.dig(u, v, 0);
				G.dig(u, v, 1);
				laid.put(key(u, v), true);
			}
			if (polished) {                                                                   // the edges softened: a stray block beside
				for (int u : new int[] {a - 1, b + 1}) {
					if (r.nextFloat() < 0.22f && !keepOff.test(u, v) && !G.has(u, v, 0)) {
						G.put(u, v, -1, r.nextBoolean() ? "coarse_dirt" : "gravel", Architect.OUTSIDE, true);
					}
				}
			}
			prevFrom = from;
			prevTo = to;
			if (!polished || i < 2 || ++sinceDeco < 4 + r.nextInt(3)) continue;             // things along it, every few blocks
			sinceDeco = 0;
			boolean left = leftNext;
			leftNext = !leftNext;
			int side = left ? a - 2 : b + 2;
			if (keepOff.test(side, v) || G.has(side, v, 0) || laid.containsKey(key(side, v))) continue;
			float what = r.nextFloat();
			if (what < 0.3f) {                                                                // a bush, a flower by it
				Architect.ground(G, side, v);
				G.put(side, v, 0, Architect.bushLeaves(leaves, anyBlock, r), Architect.OUTSIDE, true);
				if (r.nextBoolean()) G.put(side, v, 1, Architect.bushLeaves(leaves, anyBlock, r), Architect.OUTSIDE, true);
				int fv = v + (r.nextBoolean() ? 1 : -1);
				if (!G.has(side, fv, 0) && !laid.containsKey(key(side, fv))) G.put(side, fv, 0, flower(r), Architect.OUTSIDE, true);
			} else if (what < 0.5f) {                                                         // flowers
				for (int dv = -1; dv <= 1; dv++) {
					if (r.nextFloat() < 0.6f && !G.has(side, v + dv, 0) && !laid.containsKey(key(side, v + dv))) {
						G.put(side, v + dv, 0, flower(r), Architect.OUTSIDE, true);
					}
				}
			} else if (what < 0.65f) {                                                        // a rock: a boulder and a smaller stone by it
				Architect.ground(G, side, v);
				G.put(side, v, 0, r.nextBoolean() ? "mossy_cobblestone" : "cobblestone", Architect.OUTSIDE, true);
				int sv = v + (r.nextBoolean() ? 1 : -1);
				if (!G.has(side, sv, 0) && !laid.containsKey(key(side, sv))) {
					G.put(side, sv, 0, r.nextBoolean() ? "andesite_slab[type=bottom]" : "cobblestone_slab[type=bottom]", Architect.OUTSIDE, true);
				}
				if (r.nextFloat() < 0.4f) G.put(side, v, 1, "stone_button[face=floor]", Architect.OUTSIDE, true);
			} else if (what < 0.8f) {                                                         // a lamp post
				Architect.ground(G, side, v);
				for (int y = 0; y <= 1; y++) G.put(side, v, y, fence, Architect.OUTSIDE, true);
				G.put(side, v, 2, light, Architect.OUTSIDE, true);
			} else if (what < 0.92f) {                                                        // a bit of fence along it
				for (int dv = 0; dv >= -2; dv--) {
					if (G.has(side, v + dv, 0) || laid.containsKey(key(side, v + dv)) || keepOff.test(side, v + dv)) break;
					Architect.ground(G, side, v + dv);
					G.put(side, v + dv, 0, fence, Architect.OUTSIDE, true);
				}
			} else {                                                                           // a bench facing the path, arms at its ends
				if (G.has(side, v - 1, 0) || G.has(side, v + 1, 0) || G.has(side, v - 2, 0)) continue;
				String back = left ? "{L}" : "{R}";
				for (int dv = 0; dv >= -1; dv--) {
					Architect.ground(G, side, v + dv);
					G.put(side, v + dv, 0, seat + "[facing=" + back + ",half=bottom,shape=straight]", Architect.OUTSIDE, true);
				}
				String trapdoor = seat.replace("_stairs", "_trapdoor");
				G.put(side, v + 1, 0, trapdoor + "[facing={B},half=bottom,open=true]", Architect.OUTSIDE, true);
				G.put(side, v - 2, 0, trapdoor + "[facing={F},half=bottom,open=true]", Architect.OUTSIDE, true);
			}
		}
	}

	private static String flower(Random r) {
		String[] flowers = {"poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "lily_of_the_valley", "red_tulip"};
		return flowers[r.nextInt(flowers.length)];
	}
}
