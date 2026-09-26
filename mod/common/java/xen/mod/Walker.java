package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Path assist: how to get somewhere on foot, the way a good player moves. The Xen's own mind (its DMM) decides where
 * to go and why, and how much risk it takes on the way (how far it will drop, whether it jumps gaps, digs through or
 * builds across); this finds the moves and does them with the player's keys and mouse, like legs that know how to walk.
 * <p>It's a search (A*) over the moves a player can make from where they stand: walk and sprint, diagonals, jump up a
 * block, drop down, sprint-jump over a gap, swim, climb ladders and vines, open doors, dig through or dig a staircase up
 * or down, bridge across a gap and tower up out of a hole with blocks it carries. Each costs about the time it takes
 * (with its tools, digging), plus the danger (a fall that hurts, lava next to the way). It only uses what it could know:
 * blocks close by, or out in the light; anything dark and far is solid rock until it gets there and sees. It never
 * digs through what people built (planks, glass, wool...), only the ground.
 */
final class Walker {
	enum Kind { WALK, DIAGONAL, ASCEND, FALL, PARKOUR, BRIDGE, PILLAR, DIG_DOWN, CLIMB_UP, CLIMB_DOWN, SWIM }

	/** One move: from a block to a block, digging out some first (in order), maybe putting a block down on the way. */
	record Move(Kind kind, BlockPos from, BlockPos to, List<BlockPos> dig, double cost) {}

	// time costs, in ticks (a player walks a block in about 4.6, sprints one in 3.6)
	private static final double WALK = 4.63, SPRINT = 3.56, JUMP = 3, SWIM = 8, CLIMB = 8.5, SNEAK = 15, PLACE = 6, INF = 1e9;
	private static final int LIMIT = 4000;                                 // blocks it thinks about, at most, per plan

	private final Companion c;
	private List<Move> path;
	private int index;
	private Vec3 goal;
	private long plannedAt, stepStarted;
	private int stage, fails;
	/** Set each decision that wants it walking (else it stops, like letting go of W). */
	boolean touched;
	/** How close it has got to where it's going, and when (to tell when it's getting nowhere). */
	private double bestGap;
	private long bestAt, daringUntil;
	private boolean stepped;
	/** Moves that went wrong lately (it tries other ways): from-to, and how often. */
	private final Map<Long, Integer> bad = new HashMap<>();
	private long badSince;
	/** For /xen status and the journal: what it's doing on its way, and what went wrong last. */
	String doing = "", lastProblem = "";
	private String journaled = "";

	/** The one portal block it means to walk into (following someone through, going to the Nether), else none. */
	BlockPos portalOk;

	// how bold it is on the way, set by its mind before each plan
	private int maxFall = 3, maxGap = 0, blocks;
	private boolean dig = true;
	private float fear;

	Walker(Companion c) {
		this.c = c;
	}

	boolean active() {
		return path != null;
	}

	void stop() {
		if (path == null) return;
		path = null;
		release();
	}

	private void release() {
		var p = c.player;
		p.zza = p.xxa = 0;
		p.setJumping(false);
		p.setShiftKeyDown(false);
		p.setSprinting(false);
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	// ------------------------------------------------------------------------------------ asking
	/**
	 * Walk towards a place. FORWARD while it's on its way (the keys are held between decisions), null if there's no way
	 * it knows of (then it's up to the old ways: dig towards it).
	 */
	Action go(Vec3 to) {
		touched = true;
		var p = c.player;
		double gap = Math.hypot(p.getX() - to.x, p.getZ() - to.z) + 0.7 * Math.abs(p.getY() - to.y);
		if (goal == null || goal.distanceTo(to) > 1.5 || gap < bestGap - 1) {
			bestGap = gap;
			bestAt = now();
		}
		boolean settled = p.onGround() || p.isInWater() || p.onClimbable();
		boolean replan = path == null || goal == null || goal.distanceTo(to) > 1.5 || index >= path.size()
				|| now() - plannedAt > 60 && settled;                        // (every 3 s it looks again: it sees more on the way)
		if (replan && !settled && path != null) replan = false;             // not in the middle of a jump or a fall
		if (replan && !budget(p.level().getServer().getTickCount())) {     // many Xens thinking at once: its turn next tick
			if (path == null) {
				c.acted = true;
				return Action.IDLE;
			}
			replan = false;
		}
		if (replan) {
			goal = to;
			bold();
			Move was = path != null && index < path.size() ? path.get(index) : null;
			path = plan((ServerLevel) p.level(), p.blockPosition(), to);
			index = 0;
			stage = 0;
			boolean same = was != null && path != null && !path.isEmpty() && path.get(0).from().equals(was.from()) && path.get(0).to().equals(was.to());
			if (!same) stepStarted = now();                                   // (the same move as before: the clock keeps running)
			plannedAt = now();
			if (path == null) {
				release();
				return null;
			}
			if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} plans {} moves to {}: {}", c.name, path.size(), BlockPos.containing(to), summary());
			String plan = summary();
			if (!plan.equals(journaled)) c.journal("way", path.size() + " moves to " + BlockPos.containing(to).toShortString() + ": " + plan);
			journaled = plan;
		}
		c.acted = true;
		return Action.FORWARD;
	}

	/**
	 * Planning takes time: all the Xens together think about at most this many blocks each server tick (with 50 Xens,
	 * a few of them plan each tick and the rest walk the way they have), so the server never stutters.
	 */
	private static final int NODES_PER_TICK = 12000;
	private static int budgetTick = -1, budgetUsed;

	private static boolean budget(int tick) {
		if (tick != budgetTick) {
			budgetTick = tick;
			budgetUsed = 0;
		}
		if (budgetUsed >= NODES_PER_TICK) return false;
		budgetUsed += LIMIT;
		return true;
	}

	/** Getting nowhere: its moves keep failing, or it's been no closer for six seconds (then the solver has a go). */
	boolean stuck() {
		return fails >= 2 || now() - bestAt > 120 && bestGap > 2;
	}

	boolean hasBlocks() {
		return throwaway() > 0;
	}

	/** Braver for a while (the solver's "bolder way"): bigger drops, longer jumps, and the ways that failed get another go. */
	void dare(int ticks) {
		if (now() >= daringUntil) {
			bad.clear();
			plannedAt = 0;
		}
		daringUntil = now() + ticks;
	}

	/** One step of a staircase up that way (dig over its head, and the two above the step, then up). */
	Action stairsUp(Direction d) {
		touched = true;
		var p = c.player;
		BlockPos feet = p.blockPosition(), up = feet.relative(d).above();
		if (path == null || path.size() != 1 || path.get(0).kind() != Kind.ASCEND || !path.get(0).from().equals(feet)) {
			level = (ServerLevel) p.level();
			eyes = feet;
			if (breaks(feet.above(2)) >= INF || breaks(up.above()) >= INF || breaks(up) >= INF || !step(feet.relative(d))) return null;
			path = new ArrayList<>(List.of(new Move(Kind.ASCEND, feet, up, List.of(feet.above(2), up.above(), up), 0)));
			index = 0;
			stepStarted = plannedAt = now();
			goal = Vec3.atBottomCenterOf(up);
		}
		c.acted = true;
		return Action.FORWARD;
	}

	/** Did it just finish a move (once)? */
	boolean justStepped() {
		boolean s = stepped;
		stepped = false;
		return s;
	}

	/** The nearest dry ground to climb out on (from the water), within r blocks. */
	Vec3 nearestDryLand(int r) {
		var p = c.player;
		level = (ServerLevel) p.level();
		eyes = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					BlockPos q = eyes.offset(dx, dy, dz);
					if (!floor(q) || water(q) || water(q.below())) continue;
					double d = q.distSqr(eyes);
					if (d < bestD) {
						bestD = d;
						best = q;
					}
				}
			}
		}
		return best == null ? null : Vec3.atBottomCenterOf(best);
	}

	/** Its mind sets how bold it is: from its bravery, fear and health (a hurt, scared Xen takes the long safe way). */
	private void bold() {
		var p = c.player;
		fear = c.emotions.fear;
		float brave = c.personality.bravery;
		float h = p.getHealth();
		maxFall = (h >= 16 ? 5 : h >= 12 ? 4 : 3) + (brave > 0.7f && h >= 18 && fear < 0.4f ? 1 : 0);
		maxGap = h < 10 || fear > 0.6f ? 0 : brave > 0.7f ? 3 : brave > 0.35f ? 2 : 1;
		blocks = throwaway();
		dig = true;
		if (now() < daringUntil) {                                           // (the solver said: be bolder)
			maxFall += h >= 16 ? 4 : 2;
			maxGap = 3;
		}
		if (now() - badSince > 400) bad.clear();                           // (old troubles forgotten)
	}

	private int throwaway() {
		var inv = c.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && Hands.PLACEABLE.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) n += s.getCount();
		}
		return c.player.isCreative() ? 64 : n;
	}

	String summary() {
		if (path == null) return "no way";
		Map<Kind, Integer> kinds = new java.util.EnumMap<>(Kind.class);
		for (Move m : path) kinds.merge(m.kind(), 1, Integer::sum);
		return kinds.toString().toLowerCase(java.util.Locale.ROOT);
	}

	// ------------------------------------------------------------------------------------ planning
	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		double g, f;
		Node parent;
		Move via;
		boolean closed;
		/** Standing on a block it will have put down on the way (towering up, bridging): not there yet in the world. */
		boolean placedFloor;
		int placed;

		Node(BlockPos pos) {
			this.pos = pos;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(f, o.f);
		}
	}

	private ServerLevel level;
	private BlockPos eyes;

	private List<Move> plan(ServerLevel level, BlockPos start, Vec3 to) {
		this.level = level;
		this.eyes = start;
		BlockPos target = BlockPos.containing(to);
		if (!passable(start) && passable(start.above())) start = start.above();   // on a slab, a path, mud: its feet are a little higher
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();
		Node first = new Node(start);
		first.placedFloor = c.player.onGround();                            // it's standing (maybe on the very edge of a block)
		first.f = h(start, to);
		nodes.put(start.asLong(), first);
		open.add(first);
		Node best = first;
		double bestH = first.f;
		int expanded = 0;
		while (!open.isEmpty() && expanded < LIMIT) {
			Node n = open.poll();
			if (n.closed) continue;
			n.closed = true;
			expanded++;
			double hn = h(n.pos, to);
			if (n.pos.equals(target) || hn < 0.01) {
				best = n;
				break;
			}
			if (hn < bestH) {
				bestH = hn;
				best = n;
			}
			for (Move m : moves(n.pos, n.placedFloor, blocks - n.placed)) {
				double cost = m.cost() * (1 + bad.getOrDefault(key(m), 0) * 4);
				if (cost >= INF) continue;
				Node next = nodes.computeIfAbsent(m.to().asLong(), k -> new Node(m.to()));
				if (next.closed || n.g + cost >= next.g && next.parent != null) continue;
				next.g = n.g + cost;
				next.f = next.g + h(m.to(), to);
				next.parent = n;
				next.via = m;
				next.placedFloor = m.kind() == Kind.PILLAR || m.kind() == Kind.BRIDGE;
				next.placed = n.placed + (next.placedFloor ? 1 : 0);
				open.add(next);
			}
		}
		if (best == first) return null;
		List<Move> out = new ArrayList<>();
		for (Node n = best; n.via != null; n = n.parent) out.add(0, n.via);
		return out;
	}

	/** At best a sprint the whole way (a little more: it would rather find a good way than the shortest one). */
	private static double h(BlockPos p, Vec3 to) {
		double dx = p.getX() + 0.5 - to.x, dy = p.getY() - to.y, dz = p.getZ() + 0.5 - to.z;
		double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.5);
		return d < 0.75 && Math.abs(dy) < 1 ? 0 : d * SPRINT * 1.15;
	}

	private static long key(Move m) {
		return m.from().asLong() * 31 + m.to().asLong();
	}

	private final List<Move> out = new ArrayList<>();

	private List<Move> moves(BlockPos p, boolean placedFloor, int blocks) {
		out.clear();
		boolean inWater = water(p);
		boolean onFloor = floor(p) || placedFloor && passable(p) && passable(p.above());
		boolean standing = onFloor || inWater || climbable(p);
		if (!standing) return out;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos q = p.relative(d);
			double danger = danger(q);
			// walk (or swim) across, digging through what's in the way
			if (floor(q) || water(q)) {
				double br = breaks(q.above()) + breaks(q);
				if (br < INF) add(water(q) || inWater ? Kind.SWIM : Kind.WALK, p, q, (water(q) ? SWIM : SPRINT) + br + danger + door(q) + under(q) + (farmland(q) ? 8 : 0),
						br > 0 ? List.of(q.above(), q) : List.of());
			}
			// jump up a block (digging a staircase up if need be: over its head, then the two above the step)
			BlockPos up = q.above();
			if (!passable(q) && step(q) && floor(up) && !farmland(up)) {        // (never jump onto farmland: it tramples it)
				double br = breaks(p.above(2)) + breaks(up.above()) + breaks(up);
				if (br < INF) add(Kind.ASCEND, p, up, SPRINT + JUMP + br + danger(up), br > 0 ? List.of(p.above(2), up.above(), up) : List.of());
			}
			// down: walk off and drop (as far as it dares; into water from anywhere)
			if (passable(q) && passable(q.above()) && !floor(q) && !water(q)) {
				BlockPos r = q;
				int k = 0;
				while (k < 24 && passable(r.below()) && !water(r)) {
					r = r.below();
					k++;
				}
				if (water(r) || k <= maxFall && floor(r) && !farmland(r)) {
					double hurt = water(r) ? 0 : Math.max(0, k - 3) * (12 + 30 * fear);
					add(Kind.FALL, p, r, WALK + 2 * k + hurt + danger(r), List.of());
				}
				// across a gap: a jump (a sprint-jump for 3), where it's brave enough
				for (int g = 1; g <= maxGap; g++) {
					BlockPos land = p.relative(d, g + 1);
					boolean clear = passable(p.above(2)) && floor(land) && passable(land) && passable(land.above()) && passable(land.above(2));
					for (int i = 1; i <= g && clear; i++) {
						BlockPos mid = p.relative(d, i);
						clear = passable(mid) && passable(mid.above()) && passable(mid.above(2)) && !floor(mid);
					}
					if (clear && !farmland(land)) add(Kind.PARKOUR, p, land, (g + 1) * SPRINT + 4 + g * 6 * fear + danger(land), List.of());
					if (!clear) break;
				}
				// or a block down to walk on (sneaking at the edge, placed against the side of the one it stands on)
				if (blocks > 0 && passable(q.below()) && !water(q.below()) && (fullBlock(p.below()) || placedFloor) && !lava(q.below().below())) {
					add(Kind.BRIDGE, p, q, SNEAK + PLACE + danger, List.of());
				}
			}
			// a staircase down: dig the step in front and walk down onto it
			BlockPos down = q.below();
			if (dig && floor(down) && !(passable(q) && passable(q.above()) && passable(down))) {
				double br = breaks(q.above()) + breaks(q) + breaks(down);
				if (br < INF) add(Kind.FALL, p, down, WALK + 2 + br + danger(down), List.of(q.above(), q, down));
			}
		}
		// diagonals, if both corners are open (no cutting through walls)
		for (int[] dd : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
			BlockPos q = p.offset(dd[0], 0, dd[1]), a = p.offset(dd[0], 0, 0), b = p.offset(0, 0, dd[1]);
			if ((floor(q) || water(q)) && passable(q) && passable(q.above()) && passable(a) && passable(a.above()) && passable(b) && passable(b.above())) {
				add(water(q) ? Kind.SWIM : Kind.DIAGONAL, p, q, (water(q) ? SWIM : SPRINT) * 1.414 + danger(q), List.of());
			}
		}
		// up and down on the spot
		BlockPos above = p.above();
		if (climbable(p) && passable(above) && passable(above.above())) add(Kind.CLIMB_UP, p, above, CLIMB, List.of());
		if (climbable(p.below()) && passable(p.below())) add(Kind.CLIMB_DOWN, p, p.below(), CLIMB, List.of());
		if (inWater && passable(above) && (water(above) || floor(above) || passable(above.above()))) add(Kind.SWIM, p, above, SWIM, List.of());
		if (inWater && water(p.below())) add(Kind.SWIM, p, p.below(), SWIM + under(p.below()), List.of());
		if (blocks > 0 && onFloor && !inWater) {                          // tower up (out of a hole): jump, block under its feet
			double br = breaks(p.above(2));
			if (br < INF) add(Kind.PILLAR, p, above, PLACE + JUMP + 6 + br, br > 0 ? List.of(p.above(2)) : List.of());
		}
		if (dig && floor(p) && !inWater) {                                // straight down (players don't like to: only when there's no other way)
			BlockPos b = p.below();
			double br = breaks(b);
			if (br > 0 && br < INF && fullBlock(b.below()) && !lava(b.below()) && !water(b.below())) {
				add(Kind.DIG_DOWN, p, b, br + 6 + 20, List.of(b));
			}
		}
		return out;
	}

	private void add(Kind k, BlockPos from, BlockPos to, double cost, List<BlockPos> dig) {
		if (cost >= INF) return;
		if (!dig.isEmpty() && !this.dig) return;
		out.add(new Move(k, from, to, dig, cost));
	}

	// ------------------------------------------------------------------------------------ the world, as it knows it
	/** What it can know: close by it feels it; further off only what's out in the light (a dark cave far away: rock). */
	private boolean known(BlockPos p) {
		return p.distManhattan(eyes) <= 8 || level.isLoaded(p) && level.getRawBrightness(p, 0) > 0;
	}

	private BlockState state(BlockPos p) {
		if (!level.isLoaded(p)) return Blocks.BEDROCK.defaultBlockState();
		BlockState s = level.getBlockState(p);
		if (!known(p) && s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty()) return Blocks.STONE.defaultBlockState();
		return s;
	}

	/** Room for a body: nothing solid (a carpet or a snow layer is fine, an open door or gate too), no lava, no fire. */
	private boolean passable(BlockPos p) {
		BlockState s = state(p);
		if (s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.COBWEB)
				|| s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.CACTUS)) return false;
		if ((s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY)) && (portalOk == null || p.distManhattan(portalOk) > 3)) return false;   // not by accident
		if (openable(s) || s.is(BlockTags.CLIMBABLE)) return true;
		VoxelShape shape = s.getCollisionShape(level, p);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.1875;
	}

	/** A wooden door or a fence gate: it opens it on the way. */
	private static boolean openable(BlockState s) {
		return s.getBlock() instanceof DoorBlock && s.is(BlockTags.WOODEN_DOORS) || s.getBlock() instanceof FenceGateBlock;
	}

	private double door(BlockPos p) {
		BlockState s = state(p);
		return openable(s) && s.hasProperty(BlockStateProperties.OPEN) && !s.getValue(BlockStateProperties.OPEN) ? 4 : 0;
	}

	/** Can it stand with its feet here: room for its body and something to stand on (not a fence: too high)? */
	private boolean floor(BlockPos p) {
		if (!passable(p) || !passable(p.above())) return false;
		BlockPos b = p.below();
		BlockState s = state(b);
		VoxelShape shape = s.getCollisionShape(level, b);
		if (shape.isEmpty() || shape.max(Direction.Axis.Y) > 1.0) return false;
		return !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.CAMPFIRE) && !s.is(Blocks.SOUL_CAMPFIRE) && !s.is(Blocks.CACTUS);
	}

	/** Something it can jump up onto (a full block, a slab, stairs; not a fence or a wall). */
	private boolean step(BlockPos p) {
		VoxelShape shape = state(p).getCollisionShape(level, p);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0 || breaks(p) < INF;
	}

	private boolean fullBlock(BlockPos p) {
		return state(p).isCollisionShapeFullBlock(level, p);
	}

	private boolean water(BlockPos p) {
		return state(p).getFluidState().is(FluidTags.WATER);
	}

	private boolean lava(BlockPos p) {
		return state(p).getFluidState().is(FluidTags.LAVA);
	}

	private boolean climbable(BlockPos p) {
		return state(p).is(BlockTags.CLIMBABLE);
	}

	/** Standing there, it would be on farmland (a field: walk on it gently, never land on it). */
	private boolean farmland(BlockPos p) {
		return state(p.below()).is(Blocks.FARMLAND);
	}

	/** Swimming with its head under water: it would rather keep its head up (out of breath: much rather). */
	private double under(BlockPos p) {
		if (!water(p.above())) return 0;
		var body = c.player;
		return body.getAirSupply() < body.getMaxAirSupply() / 2 ? INF : 12;
	}

	/** Lava next to the way, a drop into the void: it keeps well clear. */
	private double danger(BlockPos p) {
		double d = 0;
		for (Direction dir : Direction.values()) if (lava(p.relative(dir)) || lava(p.above().relative(dir))) d += 40;
		String below = BuiltInRegistries.BLOCK.getKey(state(p.below()).getBlock()).getPath();
		if (below.equals("potent_sulfur") || below.equals("wither_rose") || below.equals("magma_block")) d += 30;   // sulfur gas makes you sick
		return d;
	}

	/**
	 * How long to dig it out of the way (0: nothing there). Only the ground, never what people built; not next to lava
	 * or water it would let in, not under sand or gravel that would fall on it.
	 */
	private double breaks(BlockPos p) {
		if (passable(p)) return 0;
		if (!dig) return INF;
		BlockState s = state(p);
		if (s.getDestroySpeed(level, p) < 0 || !natural(s)) return INF;
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			var f = level.getFluidState(p.relative(d));
			if (f.is(FluidTags.LAVA)) return INF;
			if (f.is(FluidTags.WATER) && f.isSource()) return INF;
		}
		if (state(p.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock && !passable(p.above())) return INF;
		if (c.player.isCreative()) return 2;
		float speed = c.hands.digSpeed(s, level, p);
		if (speed < 1f / 200) return INF;                                     // more than ten seconds: not that way
		return 1 / speed + 12;                                                // (a player walks round or over rather than tunnel through)
	}

	/** Ground it may dig: earth, stone, sand, ores, leaves, snow (what the world is made of, not what people make). */
	static boolean natural(BlockState s) {
		String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
				|| s.is(BlockTags.BASE_STONE_NETHER) || n.endsWith("_ore") || n.equals("gravel") || n.equals("clay") || n.equals("snow_block")
				|| n.equals("snow") || n.equals("ice") || n.equals("cobblestone") || n.equals("cobbled_deepslate") || n.equals("sandstone")
				|| n.equals("red_sandstone") || n.equals("mud") || n.equals("moss_block") || n.equals("end_stone") || n.equals("soul_sand")
				|| n.equals("soul_soil") || n.equals("calcite") || n.equals("dripstone_block") || n.endsWith("terracotta") && !n.contains("glazed")
				|| n.equals("mycelium") || n.equals("podzol") || n.equals("grass_block") || n.equals("dirt_path") || n.equals("magma_block");
	}

	// ------------------------------------------------------------------------------------ doing it
	/** Every tick while it has a way: the keys for the move it's on. True while it has the keys (else its hands are busy). */
	boolean tick() {
		if (path == null) return false;
		var p = c.player;
		level = (ServerLevel) p.level();
		eyes = p.blockPosition();
		if (index >= path.size()) {
			stop();
			return false;
		}
		Move m = path.get(index);
		if (arrived(m)) {
			stepped = true;
			index++;
			stage = 0;
			stepStarted = now();
			fails = Math.max(0, fails - 1);
			if (index >= path.size()) {
				stop();
				doing = "there";
				return false;
			}
			m = path.get(index);
		}
		if (c.hands.busy()) return false;                                   // its hands are at it (digging, a block down, a jump up)
		// out of the way first: dig what's in it (its hands do that; the walking waits)
		for (BlockPos b : m.dig()) {
			if (level.getBlockState(b).getCollisionShape(level, b).isEmpty()) continue;
			c.player.zza = c.player.xxa = 0;
			c.player.setSprinting(false);
			if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(b)) > c.player.blockInteractionRange()) break;   // closer first
			doing = "digging through";
			if (!c.hands.mine(b)) {
				problem(m, "can't dig " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(b).getBlock()).getPath());
				return false;
			}
			stepStarted = now();
			return false;
		}
		if (now() - stepStarted > 60 + 20 * m.dig().size()) {              // three seconds on one move and nowhere: another way
			problem(m, "stuck on a " + m.kind().name().toLowerCase(java.util.Locale.ROOT) + " at " + m.from().toShortString());
			return false;
		}
		// a closed door or gate in the way: open it
		BlockPos head = m.to();
		for (BlockPos d : new BlockPos[] {head, head.above()}) {
			BlockState s = level.getBlockState(d);
			if (openable(s) && s.hasProperty(BlockStateProperties.OPEN) && !s.getValue(BlockStateProperties.OPEN)
					&& c.player.getEyePosition().distanceTo(Vec3.atCenterOf(d)) < 3.5) {
				c.hands.use(d);
				return false;
			}
		}
		drive(m);
		return true;
	}

	private void problem(Move m, String why) {
		lastProblem = why;
		bad.merge(key(m), 1, Integer::sum);
		badSince = now();
		fails++;
		if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} on its way: {} ({})", c.name, why, m.kind());
		c.journal("way", "trouble: " + why);
		c.stuckOnTheWay(why, fails);
		plannedAt = 0;                                                       // think again at the next decision
		path = null;
		release();
	}

	private boolean arrived(Move m) {
		var p = c.player;
		BlockPos feet = p.blockPosition();
		double flat = Math.hypot(p.getX() - (m.to().getX() + 0.5), p.getZ() - (m.to().getZ() + 0.5));
		boolean last = index == path.size() - 1;
		boolean turning = !last && direction(path.get(index + 1)) != direction(m);
		boolean settled = p.onGround() || p.isInWater() || p.onClimbable();
		if (m.kind() == Kind.PILLAR || m.kind() == Kind.CLIMB_UP || m.kind() == Kind.CLIMB_DOWN) return feet.equals(m.to()) && settled;
		if (m.kind() == Kind.SWIM) return flat < 0.6 && Math.abs(p.getY() - m.to().getY()) < 1.0;   // (bobbing in the water)
		return feet.equals(m.to()) && settled && flat < (last || turning ? 0.35 : 0.7);
	}

	private static int direction(Move m) {
		int dx = Integer.signum(m.to().getX() - m.from().getX()), dz = Integer.signum(m.to().getZ() - m.from().getZ());
		return dx * 3 + dz;
	}

	/** The keys and the mouse for one move. */
	private void drive(Move m) {
		var p = c.player;
		Vec3 to = Vec3.atBottomCenterOf(m.to());
		double dx = to.x - p.getX(), dz = to.z - p.getZ(), flat = Math.hypot(dx, dz);
		boolean last = index == path.size() - 1;
		p.setShiftKeyDown(false);
		p.setJumping(false);
		p.xxa = 0;
		switch (m.kind()) {
			case PILLAR -> {                                                 // look down, jump, a block under its feet
				doing = "towering up";
				face(to.add(0, -1, 0));
				p.zza = 0;
				if (p.onGround() && !c.hands.busy() && !c.hands.startPillar()) problem(m, "no blocks to tower up with");
				return;
			}
			case CLIMB_UP -> {
				doing = "climbing";
				face(to.add(0, 1, 0));
				p.zza = flat > 0.2 ? 0.4f : 0;
				p.setJumping(true);
				return;
			}
			case CLIMB_DOWN -> {
				doing = "climbing down";
				p.zza = 0;
				return;
			}
			case DIG_DOWN -> {
				doing = "digging down";
				p.zza = 0;                                                    // it drops into the hole it dug
				return;
			}
			case BRIDGE -> {
				bridge(m);
				return;
			}
			default -> { }
		}
		face(to.add(0, 1.2, 0));
		boolean straight = !last && direction(path.get(index + 1)) == direction(m);
		p.zza = (float) (flat > 0.15 ? Math.min(1, flat * (last ? 1.5 : 3)) : 0);
		boolean sprint = (m.kind() == Kind.WALK || m.kind() == Kind.DIAGONAL || m.kind() == Kind.PARKOUR) && (straight || m.kind() == Kind.PARKOUR)
				&& p.getFoodData().getFoodLevel() > 6 && !p.isInWater() && remaining() > 3;
		p.setSprinting(sprint);
		switch (m.kind()) {
			case ASCEND -> {
				doing = "going up";
				if ((p.onGround() || p.isInWater()) && flat < 1.3 && p.getY() < m.to().getY() - 0.4) p.setJumping(true);   // (out of the water too)
			}
			case PARKOUR -> {                                                // at the very edge: jump
				doing = "jumping a gap";
				Vec3 from = Vec3.atBottomCenterOf(m.from());
				int ux = Integer.signum(m.to().getX() - m.from().getX()), uz = Integer.signum(m.to().getZ() - m.from().getZ());
				double along = (p.getX() - from.x) * ux + (p.getZ() - from.z) * uz;
				p.setSprinting(true);
				p.zza = 1;
				if (p.onGround() && along > 0.25 && p.blockPosition().equals(m.from())) p.setJumping(true);
			}
			case SWIM -> {
				doing = "swimming";
				double dy = m.to().getY() + 0.1 - p.getY();
				p.setJumping(dy > -0.3 || p.isUnderWater() && dy > -1.2 && flat > 0.3);   // up for air, like a player holding space
				p.setShiftKeyDown(dy < -0.8);
				if (dy < -0.8) face(to.add(0, -1, 0));
			}
			case FALL -> {
				doing = "going down";
				if (!p.onGround() && flat < 0.5) p.zza = 0;                      // over it: just drop
			}
			default -> doing = "walking";
		}
		// stuck against a block edge (it happens): a hop, like a player
		if (p.horizontalCollision && p.onGround() && m.kind() != Kind.FALL && now() - stepStarted > 10) p.setJumping(true);
	}

	/** Bridging: sneak to the edge, look down at the side of the block it stands on, put one against it, walk on. */
	private void bridge(Move m) {
		var p = c.player;
		doing = "bridging";
		BlockPos under = m.to().below();
		Direction d = Direction.getApproximateNearest(m.to().getX() - m.from().getX(), 0, m.to().getZ() - m.from().getZ());
		p.setShiftKeyDown(true);                                             // sneaking: it won't walk off the edge
		p.setSprinting(false);
		if (!level.getBlockState(under).canBeReplaced()) {                  // down: walk on (still sneaking)
			face(Vec3.atBottomCenterOf(m.to()).add(0, 1.2, 0));
			p.zza = 0.6f;
			return;
		}
		Vec3 from = Vec3.atBottomCenterOf(m.from());
		double along = (p.getX() - from.x) * d.getStepX() + (p.getZ() - from.z) * d.getStepZ();
		if (along < 0.2 && now() - stepStarted < 30) {                     // to the edge
			face(Vec3.atBottomCenterOf(m.to()).add(0, 1.2, 0));
			p.zza = 0.5f;
			return;
		}
		p.zza = 0;
		if (c.hands.busy()) return;
		int slot = -1;
		boolean placed = c.hands.placeItem(under, s -> Hands.PLACEABLE.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()),
				m.from().below(), d, -1);
		if (!placed && now() - stepStarted > 40) problem(m, "couldn't put a block down to bridge (" + c.hands.cantPlace + ")");
	}

	private int remaining() {
		return path == null ? 0 : path.size() - index;
	}

	private void face(Vec3 at) {
		var p = c.player;
		Vec3 d = at.subtract(p.getEyePosition());
		float yRot = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float xRot = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		if (Math.hypot(d.x, d.z) < 0.1) yRot = p.getYRot();
		p.setYRot(yRot);
		p.setYHeadRot(yRot);
		p.setXRot(Math.max(-90, Math.min(90, xRot)));
	}
}
