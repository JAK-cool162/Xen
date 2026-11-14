package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A team's wall, drawn first and coloured after, the way a builder sketches the outline and then fills it in.
 * <p>The outline: a ring of wall round its base (what it encloses), a tower on each corner so the walls meet, a
 * gate in the front, battlements along the top; every column stands on the ground where it is, so it follows the land.
 * <p>The colours: what the player's schematics taught (the stone they're built of, low, middle and top, counted
 * block by block): the foot of the wall in the rough stone those buildings stand on, the middle mixed the way theirs
 * is, the top in their trim. Each block picked at random by those counts: a wall with a texture, not one flat block.
 * Survival blocks only (the schematics' rarer ones swapped, as for their buildings).
 */
final class Fortify {
	private Fortify() {}

	/** The wall's shades, darkest first, and where each ends up the wall (0 to 1): its gradient. */
	private record Gradient(List<String> shades, double[] upTo) {
		/** The shade at this height (of h): a gradient from the foot up; where two meet, a checker of both (no hard line, nothing random). */
		String at(int y, int h, int x, int z) {
			double t = (y + 0.5) / Math.max(1, h);
			int i = 0;
			while (i < upTo.length - 1 && t > upTo[i]) i++;
			double rows = 1.0 / Math.max(1, h);
			if (i < upTo.length - 1 && upTo[i] - t < rows && ((x + z + y) & 1) == 1) i++;            // (the row under the next shade: half of it already)
			else if (i > 0 && t - upTo[i - 1] < rows && ((x + z + y) & 1) == 1) i--;                // (the row over the last: half of it still)
			return shades.get(i);
		}
	}

	private static Gradient learned;

	/** How light a stone is (0 dark, 1 light): the order of the gradient. */
	private static double light(String n) {
		if (n.contains("blackstone")) return 0.1;
		if (n.contains("deepslate")) return 0.2;
		if (n.contains("tuff")) return 0.35;
		if (n.contains("mossy")) return 0.38;
		if (n.equals("cobblestone")) return 0.42;
		if (n.contains("andesite")) return 0.5;
		if (n.contains("granite")) return 0.52;
		if (n.contains("stone_brick")) return 0.58;
		if (n.contains("smooth_stone") || n.equals("stone")) return 0.6;
		if (n.contains("diorite")) return 0.8;
		if (n.contains("calcite")) return 0.9;
		return 0.5;
	}

	/** Stone of the kind a wall is built of (not wood, glass, ground, plants). */
	private static boolean stone(String n) {
		return (n.contains("stone") || n.contains("cobble") || n.contains("brick") || n.contains("andesite") || n.contains("diorite") || n.contains("granite")
				|| n.contains("deepslate") || n.contains("tuff") || n.contains("blackstone")) && !n.contains("_stairs") && !n.contains("_slab") && !n.contains("_wall")
				&& !n.contains("button") && !n.contains("pressure") && !n.contains("redstone") && !n.contains("glowstone") && !n.contains("_ore");
	}

	/**
	 * The stone the player's schematics are built of (survival blocks, counted once): the five they use most, darkest
	 * first, each its share of the wall's height by how much they use it.
	 */
	private static synchronized Gradient palette() {
		if (learned != null) return learned;
		Map<String, Integer> count = new HashMap<>();
		for (String name : Schematic.list()) {
			Schematic s;
			try {
				s = Schematic.load(name);
			} catch (Exception e) {
				continue;
			}
			if (!s.note.isEmpty() || s.sizeY < 4) continue;                         // (a machine, or flat: not a building)
			for (Schematic.Block b : s.blocks) {
				String a = Schematic.adapt(b.spec(), null);
				if (a == null) continue;
				String n = a.indexOf('[') >= 0 ? a.substring(0, a.indexOf('[')) : a;
				if (stone(n)) count.merge(n, 1, Integer::sum);
			}
		}
		List<Map.Entry<String, Integer>> top = count.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(5)
				.sorted(java.util.Comparator.comparingDouble(e -> light(e.getKey()))).toList();
		List<String> shades = new ArrayList<>();
		double total = top.stream().mapToInt(Map.Entry::getValue).sum();
		double[] upTo = new double[Math.max(1, top.size())];
		double run = 0;
		for (int i = 0; i < top.size(); i++) {
			shades.add(top.get(i).getKey());
			run += top.get(i).getValue() / total;
			upTo[i] = i == top.size() - 1 ? 1.0 : run;
		}
		if (shades.isEmpty()) {
			shades.add("cobblestone");
			upTo[0] = 1;
		}
		learned = new Gradient(shades, upTo);
		XenMod.LOG.info("Xen walls: shades learned from the schematics, foot to top: {}", shades);
		return learned;
	}

	/**
	 * The ring round a box (its corners, x0 z0 to x1 z1): a wall {@code h} high, battlements on top, a five-wide tower
	 * {@code h + 4} high on each corner, a gate three wide in the north side. The ground under each column decides
	 * where it starts.
	 */
	static Architect.Plan ring(ServerLevel level, int x0, int z0, int x1, int z1, int h, long seed) {
		Gradient g = palette();
		Map<BlockPos, String> cells = new HashMap<>();
		int gate = (x0 + x1) / 2;
		// the towers: five by five, hollow, on each corner (the walls run into them)
		int[][] corners = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}};
		for (int[] cn : corners) {
			int ground = ground(level, cn[0], cn[1]);
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++) {
					boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					int x = cn[0] + dx, z = cn[1] + dz, th = h + 4;
					for (int y = 0; y < th; y++) {
						if (!edge && y > 0 && y < th - 1) continue;                      // (hollow inside; a floor at the foot and at the top)
						if (edge && y >= 1 && y <= 2 && (dx == 0 || dz == 0) && y < th - 1 && isInner(cn, dx, dz, x0, z0, x1, z1)) continue;   // (a doorway in from the wall's inside)
						cells.put(new BlockPos(x, ground + y, z), g.at(y, th, x, z));
					}
					if (edge && (dx + dz) % 2 == 0) cells.put(new BlockPos(x, ground + th, z), g.at(th - 1, th, x, z));   // (battlements: the top shade)
				}
		}
		// the walls between: one thick, on the ground where each column is
		for (int x = x0 + 3; x <= x1 - 3; x++) {
			wallColumn(level, cells, g, x, z0, h, Math.abs(x - gate) <= 1);
			wallColumn(level, cells, g, x, z1, h, false);
		}
		for (int z = z0 + 3; z <= z1 - 3; z++) {
			wallColumn(level, cells, g, x0, z, h, false);
			wallColumn(level, cells, g, x1, z, h, false);
		}
		List<Architect.Step> steps = new ArrayList<>();
		for (var e : cells.entrySet()) {
			BlockState st = Architect.state(Schematic.adapt(e.getValue(), null));
			if (st != null) steps.add(new Architect.Step(e.getKey(), st, 1, false));
		}
		steps.sort(java.util.Comparator.comparingInt((Architect.Step st) -> st.pos().getY()));
		BlockPos middle = new BlockPos((x0 + x1) / 2, ground(level, (x0 + x1) / 2, (z0 + z1) / 2), (z0 + z1) / 2);
		return new Architect.Plan("wall with towers", steps, middle, middle, Direction.NORTH, new net.minecraft.world.phys.AABB(middle));
	}

	private static boolean isInner(int[] cn, int dx, int dz, int x0, int z0, int x1, int z1) {
		int ix = cn[0] == x0 ? 1 : -1, iz = cn[1] == z0 ? 1 : -1;               // (which way is in)
		return dx == 2 * ix && dz == 0 || dz == 2 * iz && dx == 0;
	}

	private static void wallColumn(ServerLevel level, Map<BlockPos, String> cells, Gradient g, int x, int z, int h, boolean gateway) {
		int ground = ground(level, x, z);
		for (int y = 0; y < h; y++) {
			if (gateway && y >= 1 && y <= 3) continue;                             // (the gate: three wide, three high)
			cells.put(new BlockPos(x, ground + y, z), g.at(y, h, x, z));
		}
		if (((x + z) & 1) == 0) cells.put(new BlockPos(x, ground + h, z), g.at(h - 1, h, x, z));   // (battlements: every other block, the top shade)
	}

	/** The ground's top here (the first block above it is where a wall starts). */
	private static int ground(ServerLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}
}
