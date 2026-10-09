package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A way over the land, the way a player looks at the landscape before setting off: a search over the ground's top
 * (one column a step, eight ways round) from where it stands to far off, with what anyone sees from the sky (the height
 * of the ground, water, lava; leaves don't count, tree trunks do): a step up of a block is fine, more is a wall, a drop
 * of a few is fine, more is a cliff, water is slow (the open sea slower), lava is out. It only gives the way; the steps
 * (jumps, digging, swimming) are the walker's, a piece at a time, to a point a couple of dozen blocks along it.
 * <p>Before, a long trip was guessed a piece at a time (the walker's best block after 2,500), which could lead into a
 * valley with no way out the far side.
 */
final class LandRoute {
	private LandRoute() {}

	private static final int LIMIT = 30000;
	private static final double INF = 1e9;

	/** The top of the ground there (the block to stand on is the one above), or Integer.MIN_VALUE if not loaded. */
	private static int top(ServerLevel level, int x, int z) {
		if (!level.isLoaded(new BlockPos(x, 0, z))) return Integer.MIN_VALUE;
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
	}

	/** The way, as standing spots (feet), every block of it; null: no way over the land it can see. */
	static List<BlockPos> find(ServerLevel level, BlockPos from, BlockPos to) {
		int minX = Math.min(from.getX(), to.getX()) - 48, maxX = Math.max(from.getX(), to.getX()) + 48;
		int minZ = Math.min(from.getZ(), to.getZ()) - 48, maxZ = Math.max(from.getZ(), to.getZ()) + 48;
		var cost = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
		var came = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
		var height = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		height.defaultReturnValue(Integer.MAX_VALUE);
		cost.defaultReturnValue(INF);
		PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
		long start = key(from.getX(), from.getZ()), goal = key(to.getX(), to.getZ());
		cost.put(start, 0);
		came.put(start, start);
		open.add(new double[] {1.05 * dist(from.getX(), from.getZ(), to.getX(), to.getZ()), from.getX(), from.getZ(), 0});
		long best = start;
		double bestH = dist(from.getX(), from.getZ(), to.getX(), to.getZ());
		int expanded = 0;
		while (!open.isEmpty() && expanded++ < LIMIT) {
			double[] n = open.poll();
			int x = (int) n[1], z = (int) n[2];
			long k = key(x, z);
			double g = cost.get(k);
			if (n[3] > g + 1e-6) continue;                                   // (an older, dearer way here)
			double h = dist(x, z, to.getX(), to.getZ());
			if (h < bestH) {
				bestH = h;
				best = k;
			}
			if (h <= 2) {
				best = k;
				bestH = h;
				break;
			}
			int hy = heightAt(level, height, x, z);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					int nx = x + dx, nz = z + dz;
					if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
					double step = step(level, height, x, z, hy, nx, nz, dx != 0 && dz != 0);
					if (step >= INF) continue;
					if (dx != 0 && dz != 0 && (step(level, height, x, z, hy, x + dx, z, false) >= INF || step(level, height, x, z, hy, x, z + dz, false) >= INF)) continue;   // (no cutting corners)
					long nk = key(nx, nz);
					double ng = g + step;
					if (ng < cost.get(nk)) {
						cost.put(nk, ng);
						came.put(nk, k);
						open.add(new double[] {ng + 1.05 * dist(nx, nz, to.getX(), to.getZ()), nx, nz, ng});
					}
				}
			}
		}
		Perf.count("land routes", 1);
		Perf.count("land route columns", expanded);
		if (best == start || bestH > dist(from.getX(), from.getZ(), to.getX(), to.getZ()) - 8) return null;   // (nothing much better than here)
		List<BlockPos> way = new ArrayList<>();
		for (long k = best; k != start; k = came.get(k)) {
			int x = (int) (k >> 32), z = (int) k;
			way.add(0, new BlockPos(x, heightAt(level, height, x, z) + 1, z));
		}
		return way;
	}

	private static int heightAt(ServerLevel level, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap cache, int x, int z) {
		long k = key(x, z);
		int h = cache.get(k);
		if (h == Integer.MAX_VALUE) {
			h = top(level, x, z);
			cache.put(k, h);
		}
		return h;
	}

	/** What a step from (x, z), its ground at hy, to (nx, nz) costs, in blocks walked; INF: not that way. */
	private static double step(ServerLevel level, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap cache, int x, int z, int hy, int nx, int nz, boolean diagonal) {
		int ny = heightAt(level, cache, nx, nz);
		if (ny == Integer.MIN_VALUE) return INF;                                  // (not loaded: it can't see that far)
		var top = level.getBlockState(new BlockPos(nx, ny, nz));
		if (top.getFluidState().is(FluidTags.LAVA)) return INF;
		boolean water = top.getFluidState().is(FluidTags.WATER);
		int up = ny - hy;
		double base = diagonal ? 1.414 : 1;
		if (water) {
			int deep = 0;                                                         // (a stream or the open sea?)
			for (BlockPos q = new BlockPos(nx, ny, nz); deep < 8 && level.getBlockState(q).getFluidState().is(FluidTags.WATER); q = q.below()) deep++;
			if (deep >= 6 && level.getBiome(new BlockPos(nx, ny, nz)).is(net.minecraft.tags.BiomeTags.IS_OCEAN)) return INF;   // (not across the open sea: round it)
			return base * (deep >= 6 ? 4 : 2.5) + (up > 1 ? 4 : 0);
		}
		if (up >= 2) return INF;                                                  // (a wall, a tree trunk: round it)
		if (up == 1) return base + 0.6;
		if (up <= -6) return INF;                                                 // (a cliff: round it)
		if (up <= -4) return base + 3;
		return base + (up <= -2 ? 0.5 : 0);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static double dist(int x, int z, int tx, int tz) {
		double dx = Math.abs(x - tx), dz = Math.abs(z - tz);
		return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
	}
}
