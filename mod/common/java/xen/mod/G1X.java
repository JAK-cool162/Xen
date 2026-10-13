package xen.mod;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * G1X (Xen Geometry, generation 1, experimental): the shape of what it has seen, not only what kind of block. Its eyes'
 * sight lines (Eyes: across its view, block by block) tell it two things here:
 * <ul>
 *   <li><b>a depth view</b>: for each line, how far it went and what stopped it (a floor, a wall, a ceiling; water, lava,
 *   leaves): what a player sees as distance and surfaces;</li>
 *   <li><b>known space</b>: every block a line passed through is open, every one it stopped at is solid (or water, or
 *   lava). What it never saw is unknown; that's the honest part: it only knows what it saw (and what its body touches:
 *   the blocks right round it).</li>
 * </ul>
 * From those it answers what a player asks with their eyes: can I fit through that gap, is that ledge safe to drop, what's
 * in my way, is this cave joined to where I've been, how much of that way haven't I seen? When it wants to go somewhere
 * it hasn't seen, it looks first (active perception).
 * <p>No images and no trained model: the world's own blocks, seen the way a player sees them. Bounded memory (two
 * generations of a few hundred thousand blocks, the old one let go when the new one fills).
 */
final class G1X {
	static final byte UNKNOWN = 0, OPEN = 1, SOLID = 2, WATER = 3, LAVA = 4;
	/** What stopped a sight line. */
	static final byte HIT_NONE = 0, HIT_FLOOR = 1, HIT_WALL = 2, HIT_CEILING = 3, HIT_WATER = 4, HIT_LAVA = 5, HIT_LEAVES = 6;
	private static final int CAP = 250_000;

	private final Companion c;
	private Long2ByteOpenHashMap known = new Long2ByteOpenHashMap(), older = new Long2ByteOpenHashMap();
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> in;
	/** The depth view: per sight line (Eyes' columns and rows), how far it went, what stopped it, and when. */
	final float[] depth;
	final byte[] hit;
	final long[] when;
	final int cols, rows;
	private long touchedAt;
	/** The last summary of what's round it (worked out at most once a second). */
	private Around around;
	private long aroundAt = -100;

	G1X(Companion c, int cols, int rows) {
		this.c = c;
		this.cols = cols;
		this.rows = rows;
		depth = new float[cols * rows];
		hit = new byte[cols * rows];
		when = new long[cols * rows];
	}

	// --------------------------------------------------------------------------------- what it sees
	private void dimension(ServerLevel level) {
		if (level.dimension() == in) return;
		known.clear();
		older.clear();
		in = level.dimension();
	}

	private void put(long k, byte v) {
		known.put(k, v);
		if (known.size() >= CAP) {
			older = known;
			known = new Long2ByteOpenHashMap();
		}
	}

	/** A block its sight line went through: open (air, grass, a flower). Only near ones are kept (far air tells it little). */
	void sawOpen(ServerLevel level, int x, int y, int z, double t) {
		if (t > 32) return;
		dimension(level);
		put(BlockPos.asLong(x, y, z), OPEN);
	}

	/** Where a sight line stopped: what, how far, and which way the face was (from the step that got it there). */
	void sawStop(ServerLevel level, int line, int x, int y, int z, double t, BlockState s, int axis, int stepY) {
		dimension(level);
		byte v;
		byte h;
		if (s.getFluidState().is(FluidTags.LAVA)) {
			v = LAVA;
			h = HIT_LAVA;
		} else if (!s.getFluidState().isEmpty()) {
			v = WATER;
			h = HIT_WATER;
		} else {
			v = SOLID;
			h = s.is(net.minecraft.tags.BlockTags.LEAVES) ? HIT_LEAVES : axis == 1 ? (stepY < 0 ? HIT_FLOOR : HIT_CEILING) : HIT_WALL;
		}
		put(BlockPos.asLong(x, y, z), v);
		if (line >= 0 && line < depth.length) {
			depth[line] = (float) t;
			hit[line] = h;
			when[line] = level.getGameTime();
		}
	}

	/** A line that went as far as its eyes go without stopping: far, open. */
	void sawNothing(ServerLevel level, int line, double t) {
		if (line < 0 || line >= depth.length) return;
		depth[line] = (float) t;
		hit[line] = HIT_NONE;
		when[line] = level.getGameTime();
	}

	/** What its body knows without looking: the blocks right round it (under its feet, what it bumps into). */
	void touch() {
		var p = c.player;
		if (p == null) return;
		ServerLevel level = (ServerLevel) p.level();
		long now = level.getGameTime();
		if (now - touchedAt < 10) return;
		touchedAt = now;
		dimension(level);
		BlockPos f = p.blockPosition();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) {
			m.set(f.getX() + dx, f.getY() + dy, f.getZ() + dz);
			put(m.asLong(), classify(level, m));
		}
	}

	private static byte classify(ServerLevel level, BlockPos q) {
		BlockState s = level.getBlockState(q);
		if (s.getFluidState().is(FluidTags.LAVA)) return LAVA;
		if (!s.getFluidState().isEmpty()) return WATER;
		return s.getCollisionShape(level, q).isEmpty() ? OPEN : SOLID;
	}

	/** What it knows of that block: UNKNOWN, OPEN, SOLID, WATER or LAVA. */
	byte at(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		byte v = known.get(k);
		return v != UNKNOWN ? v : older.get(k);
	}

	byte at(BlockPos p) {
		return at(p.getX(), p.getY(), p.getZ());
	}

	int size() {
		return known.size() + older.size();
	}

	// ----------------------------------------------------------------------------- what it makes of it
	/** Can a player stand there: open there and above it (2 high), something solid under it. Null: it doesn't know. */
	Boolean standable(int x, int y, int z) {
		byte feet = at(x, y, z), head = at(x, y + 1, z), floor = at(x, y - 1, z);
		if (feet == SOLID || head == SOLID) return false;
		if (feet == UNKNOWN || head == UNKNOWN || floor == UNKNOWN) return null;
		return floor == SOLID;
	}

	/** Through a gap at that spot: "walk" (two high), "crouch" never helps in a one-high gap ("too low"), "blocked", or "unknown". */
	String fits(BlockPos at) {
		byte feet = at(at), head = at(at.above());
		if (feet == UNKNOWN || head == UNKNOWN) return "unknown";
		if (feet == SOLID) return "blocked";
		if (head == SOLID) return "too low";
		if (feet == LAVA || head == LAVA) return "lava";
		return feet == WATER || head == WATER ? "swim" : "walk";
	}

	/**
	 * A ledge that way: how far down the first known floor is from the first spot with nothing under it, within 4 blocks
	 * (-1: no ledge it can see; -2: it can't see the bottom), and whether there's water at the bottom.
	 */
	int[] ledge(Direction d) {
		BlockPos f = c.player.blockPosition();
		for (int k = 1; k <= 4; k++) {
			BlockPos q = f.relative(d, k);
			byte here = at(q), under = at(q.below());
			if (here == SOLID) return new int[] {-1, 0};                       // (a wall first)
			if (under == SOLID || under == UNKNOWN && k > 1) continue;
			int drop = 0;
			for (int y = q.getY() - 1; drop < 64; y--, drop++) {
				byte b = at(q.getX(), y, q.getZ());
				if (b == UNKNOWN) return new int[] {-2, 0};
				if (b == WATER) return new int[] {drop, 1};
				if (b == SOLID || b == LAVA) return new int[] {drop, 0};
			}
			return new int[] {-2, 0};
		}
		return new int[] {-1, 0};
	}

	/** Is dropping that far safe for it now: water at the bottom, or the fall's damage well under its health. */
	boolean safeDrop(int drop, boolean water) {
		if (drop < 0) return false;
		if (water || drop <= 3) return true;
		return drop - 3 < c.player.getHealth() - 6;
	}

	/** What it knows to be in the way, on a straight line from its eyes to there (the solid blocks, nearest first, up to 5). */
	java.util.List<BlockPos> blocking(Vec3 to) {
		java.util.List<BlockPos> out = new java.util.ArrayList<>();
		Vec3 eye = c.player.getEyePosition();
		Vec3 d = to.subtract(eye);
		double len = d.length();
		if (len < 1e-3) return out;
		d = d.scale(1 / len);
		BlockPos last = null;
		for (double t = 0.5; t < len && out.size() < 5; t += 0.5) {
			BlockPos q = BlockPos.containing(eye.add(d.scale(t)));
			if (q.equals(last)) continue;
			last = q;
			if (at(q) == SOLID) out.add(q.immutable());
		}
		return out;
	}

	/**
	 * Is there a way it knows of (through open space it has seen) from a to b? TRUE, FALSE (all it knows is closed off),
	 * or null (it would have to see more). Up to 6000 blocks of looking.
	 */
	Boolean connected(BlockPos a, BlockPos b) {
		if (at(a) != OPEN && at(a) != WATER || at(b) != OPEN && at(b) != WATER) return null;
		java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		q.add(a);
		seen.add(a.asLong());
		boolean edge = false;                                                 // (it ran into unknown space somewhere)
		while (!q.isEmpty() && seen.size() < 6000) {
			BlockPos p = q.poll();
			if (p.equals(b)) return Boolean.TRUE;
			for (Direction dir : Direction.values()) {
				BlockPos n = p.relative(dir);
				if (!seen.add(n.asLong())) continue;
				byte v = at(n);
				if (v == OPEN || v == WATER) q.add(n);
				else if (v == UNKNOWN) edge = true;
			}
		}
		return edge || seen.size() >= 6000 ? null : Boolean.FALSE;
	}

	/** How much of the space that way it hasn't seen (0..1): a cone of blocks out to range, at its feet and head height. */
	float unseen(double yaw, int range) {
		BlockPos f = c.player.blockPosition();
		int n = 0, unknown = 0;
		for (int r = 2; r <= range; r += 2) {
			for (int s = -1; s <= 1; s++) {
				double a = yaw + s * Math.toRadians(15);
				int x = f.getX() + (int) Math.round(-Math.sin(a) * r), z = f.getZ() + (int) Math.round(Math.cos(a) * r);
				for (int y = 0; y <= 1; y++) {
					n++;
					if (at(x, f.getY() + y, z) == UNKNOWN) unknown++;
				}
			}
		}
		return n == 0 ? 0 : unknown / (float) n;
	}

	/** What's round it, in eight directions: as far as it can walk, a ledge, water, lava, what it hasn't seen; a roof, walls. */
	record Around(int[] free, int[] drop, boolean[] water, boolean[] lava, float[] unseen, boolean roof, int walls, float unseenAll, int mostUnseen) {}

	Around around() {
		var p = c.player;
		long now = p.level().getGameTime();
		if (around != null && now - aroundAt < 20) return around;
		aroundAt = now;
		touch();
		BlockPos f = p.blockPosition();
		int[] free = new int[8], drop = new int[8];
		boolean[] water = new boolean[8], lava = new boolean[8];
		float[] unseen = new float[8];
		int walls = 0, most = 0;
		float all = 0;
		for (int i = 0; i < 8; i++) {
			int dx = new int[] {0, 1, 1, 1, 0, -1, -1, -1}[i], dz = new int[] {-1, -1, 0, 1, 1, 1, 0, -1}[i];
			drop[i] = -1;
			int k = 1;
			for (; k <= 8; k++) {
				int x = f.getX() + dx * k, z = f.getZ() + dz * k;
				byte feet = at(x, f.getY(), z), head = at(x, f.getY() + 1, z), under = at(x, f.getY() - 1, z);
				if (feet == WATER || under == WATER) water[i] = true;
				if (feet == LAVA || under == LAVA) lava[i] = true;
				if (feet == SOLID || head == SOLID || feet == UNKNOWN) break;
				if (under != SOLID && drop[i] < 0) {
					int dd = 0;
					for (int y = f.getY() - 1; dd < 32; y--, dd++) {
						byte b = at(x, y, z);
						if (b == UNKNOWN) { dd = -2; break; }
						if (b != OPEN) break;
					}
					drop[i] = dd;
					if (dd > 3 || dd == -2) break;                                 // (over the edge: no further on foot)
				}
			}
			free[i] = k - 1;
			if (free[i] == 0 && at(f.getX() + dx, f.getY(), f.getZ() + dz) == SOLID) walls++;
			unseen[i] = unseen(Math.atan2(-dx, dz), 12);
			all += unseen[i] / 8;
			if (unseen[i] > unseen[most]) most = i;
		}
		boolean roof = false;
		for (int y = 2; y <= 6 && !roof; y++) roof = at(f.getX(), f.getY() + y, f.getZ()) == SOLID;
		around = new Around(free, drop, water, lava, unseen, roof, walls, all, most);
		return around;
	}

	/** The yaw (degrees, Minecraft's) of one of the eight directions. */
	static float yawOf(int dir) {
		return dir * 45f - 180f;
	}

	/**
	 * Looking where it hasn't (active perception): with nothing pressing, now and then its eyes go to the way it knows
	 * least about, a slow look, as a player takes in a place. True if it looked.
	 */
	private long lookedAt;

	boolean lookWhereUnseen() {
		var p = c.player;
		long now = p.level().getGameTime();
		if (now - lookedAt < 60 || c.fightingNow() || p.isInWater()) return false;
		Around a = around();
		if (a.unseen()[a.mostUnseen()] < 0.5f) return false;
		lookedAt = now;
		double yaw = Math.toRadians(yawOf(a.mostUnseen()));
		Vec3 eye = p.getEyePosition();
		c.hands.holdLook(eye.add(-Math.sin(yaw) * 8, -0.5, Math.cos(yaw) * 8), 25);
		return true;
	}

	/** In words (for /xen g1x and its notes). */
	String describe() {
		if (c.player == null) return c.name + ": not here";
		Around a = around();
		String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		StringBuilder sb = new StringBuilder(c.name).append(" | G1X knows ").append(size()).append(" blocks | ");
		for (int i = 0; i < 8; i++) {
			sb.append(names[i]).append(' ').append(a.free()[i]);
			if (a.drop()[i] == -2) sb.append(" drop?");
			else if (a.drop()[i] > 0) sb.append(" drop").append(a.drop()[i]);
			if (a.water()[i]) sb.append(" water");
			if (a.lava()[i]) sb.append(" LAVA");
			sb.append(String.format(java.util.Locale.ROOT, " (%.0f%% unseen)", a.unseen()[i] * 100)).append(i < 7 ? ", " : "");
		}
		sb.append(" | roof ").append(a.roof() ? "yes" : "no").append(", walls ").append(a.walls()).append(String.format(java.util.Locale.ROOT, ", unseen %.0f%%", a.unseenAll() * 100));
		int lines = 0, far = 0;
		long now = c.player.level().getGameTime();
		for (int i = 0; i < depth.length; i++) if (now - when[i] < 100) { lines++; if (hit[i] == HIT_NONE) far++; }
		return sb.append(" | depth view: ").append(lines).append(" fresh lines, ").append(far).append(" open to the distance").toString();
	}
}
