package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Where it's going, as a place its legs can be tested against: "am I there yet?" and "about how far is it?" (in ticks of
 * sprinting, so the search can weigh it against what the moves cost). The kinds a player has in mind:
 * <ul>
 *   <li>{@link #block}: standing on that very block;</li>
 *   <li>{@link #near}: within a few blocks of it;</li>
 *   <li>{@link #nextTo}: close enough to break or use it (standing beside it, or on or under it);</li>
 *   <li>{@link #column}: anywhere straight above or below a spot (far travel: the height sorts itself out);</li>
 *   <li>{@link #level}: down (or up) at a height, anywhere (a staircase to y 16 for iron, -58 for diamonds);</li>
 *   <li>{@link #anyOf}: the first of several (the ore that's quickest to get to, not just the nearest as the crow flies).</li>
 * </ul>
 */
interface Goal {
	/** Ticks a sprint takes per block (the heuristic's unit), a little under so the search stays honest. */
	double PER_BLOCK = 3.56;

	boolean isIn(int x, int y, int z);

	/** About how many ticks from there (never more than it really takes, so the way it finds is a good one). */
	double heuristic(int x, int y, int z);

	/** A point to look toward, fly toward or dig toward (the old ways use it). */
	Vec3 center();

	default boolean isIn(BlockPos p) {
		return isIn(p.getX(), p.getY(), p.getZ());
	}

	default double heuristic(BlockPos p) {
		return heuristic(p.getX(), p.getY(), p.getZ());
	}

	private static double dist(double dx, double dy, double dz) {
		return Math.sqrt(dx * dx + dz * dz + 1.5 * dy * dy) * PER_BLOCK;
	}

	static Goal block(BlockPos at) {
		BlockPos p = at.immutable();
		return new Goal() {
			public boolean isIn(int x, int y, int z) {
				return x == p.getX() && y == p.getY() && z == p.getZ();
			}

			public double heuristic(int x, int y, int z) {
				return dist(x - p.getX(), y - p.getY(), z - p.getZ());
			}

			public Vec3 center() {
				return Vec3.atBottomCenterOf(p);
			}

			@Override
			public String toString() {
				return "to " + p.toShortString();
			}
		};
	}

	static Goal near(BlockPos at, int r) {
		BlockPos p = at.immutable();
		return new Goal() {
			public boolean isIn(int x, int y, int z) {
				double dx = x - p.getX(), dy = y - p.getY(), dz = z - p.getZ();
				return dx * dx + dy * dy + dz * dz <= r * r;
			}

			public double heuristic(int x, int y, int z) {
				return Math.max(0, dist(x - p.getX(), y - p.getY(), z - p.getZ()) - r * PER_BLOCK);
			}

			public Vec3 center() {
				return Vec3.atBottomCenterOf(p);
			}

			@Override
			public String toString() {
				return "near " + p.toShortString();
			}
		};
	}

	/** Beside it (feet at its level or one below, or the block above or below it): where a player breaks or uses it. */
	static Goal nextTo(BlockPos at) {
		BlockPos p = at.immutable();
		return new Goal() {
			public boolean isIn(int x, int y, int z) {
				int dx = Math.abs(x - p.getX()), dz = Math.abs(z - p.getZ()), dy = y - p.getY();
				if (dx == 0 && dz == 0) return dy == 1 || dy == -2;                   // on it, or under it (head below)
				return dx + dz == 1 && (dy == 0 || dy == -1);                           // beside it, feet or head level
			}

			public double heuristic(int x, int y, int z) {
				return Math.max(0, dist(x - p.getX(), y - p.getY(), z - p.getZ()) - PER_BLOCK);
			}

			public Vec3 center() {
				return Vec3.atCenterOf(p);
			}

			@Override
			public String toString() {
				return "next to " + p.toShortString();
			}
		};
	}

	static Goal column(int gx, int gz) {
		return new Goal() {
			public boolean isIn(int x, int y, int z) {
				return x == gx && z == gz;
			}

			public double heuristic(int x, int y, int z) {
				return Math.hypot(x - gx, z - gz) * PER_BLOCK;
			}

			public Vec3 center() {
				return new Vec3(gx + 0.5, 64, gz + 0.5);
			}

			@Override
			public String toString() {
				return "toward " + gx + ", " + gz;
			}
		};
	}

	static Goal level(int gy) {
		return new Goal() {
			public boolean isIn(int x, int y, int z) {
				return y == gy;
			}

			public double heuristic(int x, int y, int z) {
				return Math.abs(y - gy) * 2 * PER_BLOCK;                              // (a staircase: about two blocks a level)
			}

			public Vec3 center() {
				return new Vec3(0, gy, 0);
			}

			@Override
			public String toString() {
				return "down to y " + gy;
			}
		};
	}

	static Goal anyOf(List<Goal> goals) {
		List<Goal> all = List.copyOf(goals);
		return new Goal() {
			public boolean isIn(int x, int y, int z) {
				for (Goal g : all) if (g.isIn(x, y, z)) return true;
				return false;
			}

			public double heuristic(int x, int y, int z) {
				double best = Double.MAX_VALUE;
				for (Goal g : all) best = Math.min(best, g.heuristic(x, y, z));
				return best;
			}

			public Vec3 center() {
				return all.isEmpty() ? Vec3.ZERO : all.get(0).center();
			}

			@Override
			public String toString() {                                               // (a different set: a new goal)
				return "any of " + all.size() + (all.isEmpty() ? "" : " from " + all.get(0));
			}
		};
	}
}
