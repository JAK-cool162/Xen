package xen.mod.core;

import java.util.ArrayDeque;

/**
 * Finding a way on foot through the blocks Xen knows for sure (everything within 6 blocks), the way a player looks
 * where to step: walk, step up one block, drop down up to three, swim; never into lava.
 */
public final class Paths {
	private static final int N = Perception.NEAR, S = Perception.SIDE;

	private Paths() {}

	private static boolean passable(int c) {
		return !Blocks.SOLID[c] && c != Blocks.LAVA;
	}

	private static int at(Perception.Sight s, int x, int y, int z) {
		if (Math.abs(x) > N || Math.abs(y) > N || Math.abs(z) > N) return Blocks.STONE;   // beyond what it knows: not a way
		return s.near(x, y, z);
	}

	/** Can a player stand (or swim) with its feet here? */
	static boolean standable(Perception.Sight s, int x, int y, int z) {
		int feet = at(s, x, y, z), head = at(s, x, y + 1, z), below = at(s, x, y - 1, z);
		if (!passable(feet) || !passable(head) || below == Blocks.LAVA) return false;
		return Blocks.SOLID[below] || below == Blocks.WATER || feet == Blocks.WATER;
	}

	private static int index(int x, int y, int z) {
		return ((x + N) * S + y + N) * S + z + N;
	}

	/**
	 * The first step towards the reachable spot nearest to the goal (given relative to its feet): {direction 0-3 (north,
	 * east, south, west), 1 if it must jump up}. Null when no known way gets it any closer (then it has to dig).
	 */
	public static int[] firstStep(Perception.Sight s, double gx, double gy, double gz) {
		int total = S * S * S;
		int[] from = new int[total];
		java.util.Arrays.fill(from, -2);
		int start = index(0, 0, 0);
		from[start] = -1;
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[] {0, 0, 0});
		int best = start;
		double bestD = dist(0, 0, 0, gx, gy, gz);
		while (!queue.isEmpty()) {
			int[] p = queue.poll();
			for (int d = 0; d < 4; d++) {
				int nx = p[0] + Perception.DIRS[d][0], nz = p[2] + Perception.DIRS[d][1];
				for (int dy = 1; dy >= -3; dy--) {
					int ny = p[1] + dy;
					if (!standable(s, nx, ny, nz)) continue;
					if (dy == 1 && !passable(at(s, p[0], p[1] + 2, p[2]))) break;       // no head room to jump
					boolean clear = true;                                             // dropping: the way down is open
					for (int y = ny + 1; y <= p[1] + 1 && dy < 0; y++) clear &= passable(at(s, nx, y, nz));
					if (!clear) break;
					int i = index(nx, ny, nz);
					if (from[i] == -2) {
						from[i] = index(p[0], p[1], p[2]);
						queue.add(new int[] {nx, ny, nz});
						double dd = dist(nx, ny, nz, gx, gy, gz);
						if (dd < bestD - 1e-6) {
							bestD = dd;
							best = i;
						}
					}
					break;                                                            // the highest way it can take
				}
			}
		}
		if (best == start) return null;
		int step = best;
		while (from[step] != start) step = from[step];
		int x = step / (S * S) - N, y = (step / S) % S - N, z = step % S - N;
		for (int d = 0; d < 4; d++) {
			if (Perception.DIRS[d][0] == x && Perception.DIRS[d][1] == z) return new int[] {d, y > 0 ? 1 : 0};
		}
		return null;
	}

	private static double dist(double x, double y, double z, double gx, double gy, double gz) {
		return Math.sqrt((x - gx) * (x - gx) + (y - gy) * (y - gy) * 1.5 + (z - gz) * (z - gz));
	}
}
