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
	private static final int LIMIT = 2500;                                 // blocks it thinks about, at most, per plan

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

	/** The blocks it walked on lately, and when: a new plan doesn't go back over them unless it has to (no back and forth). */
	private final Map<Long, Long> walked = new HashMap<>();
	/** Where it stood every second lately: to tell when it's pacing back and forth. */
	private final java.util.ArrayDeque<Vec3> trail = new java.util.ArrayDeque<>();
	private long lastTrail, lockedUntil;

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
		return go(null, to);
	}

	/**
	 * Walk toward a goal (see {@link Goal}): next to an ore, down to a height, toward a far column, the nearest of
	 * several. Like Baritone's goals (our own code): the search ends where the goal says "you're there", and a long
	 * way is planned a piece at a time, the next piece before the last one runs out, so it never stops to think.
	 */
	Action go(Goal g) {
		return go(g, g.center());
	}

	/** The goal it's walking to (null: a point, the old way), and whether the way it has only gets part of the way there. */
	private Goal want;
	private String wantKey = "";
	private boolean partial;

	private Action go(Goal g, Vec3 to) {
		touched = true;
		var p = c.player;
		double gap = g != null ? g.heuristic(p.blockPosition()) / Goal.PER_BLOCK                  // (how far, by the goal's own measure)
				: Math.hypot(p.getX() - to.x, p.getZ() - to.z) + 0.7 * Math.abs(p.getY() - to.y);
		if (goal == null || (g == null ? goal.distanceTo(to) > 1.5 : !g.toString().equals(wantKey)) || gap < bestGap - 1) {
			bestGap = gap;
			bestAt = now();
		}
		boolean settled = p.onGround() || p.isInWater() || p.onClimbable();
		pace();
		// A goal that moved a little isn't a new goal (far away, a few blocks either way are the same way), and while it
		// was pacing back and forth it keeps to the way it chose for a while.
		double tolerance = now() < lockedUntil ? 12 : Math.max(1.5, Math.min(6, 0.15 * gap));
		boolean moved = goal == null || (g == null ? goal.distanceTo(to) > tolerance || want != null : !g.toString().equals(wantKey));
		boolean nearEnd = partial && path != null && path.size() - index <= 2;   // (a piece of a long way: the next piece now)
		// It keeps to its way (like a player who knows where they're going): a new plan only when the way is done, the goal
		// moved, a move failed, or what it sees now blocks the next steps.
		boolean replan = path == null || moved || index >= path.size() || now() - plannedAt > 20 && settled && !stillGood()
				|| nearEnd && settled && now() - plannedAt > 10;
		if (replan && !settled && path != null) replan = false;             // not in the middle of a jump or a fall
		if (replan && !budget(p.level().getServer().getTickCount())) {     // many Xens thinking at once: its turn next tick
			if (path == null) {
				c.acted = true;
				return Action.IDLE;
			}
			replan = false;
		}
		if (replan) {
			if (moved || goal == null) {
				boolean flip = false;                                         // back to a goal it just left: that's dithering
				for (Vec3 r : recentGoals) flip |= r.distanceTo(to) < 2;
				if (!flip) trail.clear();                                     // a new goal (the next log, the next ore): not pacing
				recentGoals.addLast(to);
				if (recentGoals.size() > 4) recentGoals.removeFirst();
				goal = to;
				want = g;
				wantKey = g == null ? "" : g.toString();
			}
			bold();
			Move was = path != null && index < path.size() ? path.get(index) : null;
			path = plan((ServerLevel) p.level(), p.blockPosition(), to, want);
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
			if (WALK_DEBUG) {
				StringBuilder w = new StringBuilder();
				for (int i = 0; i < Math.min(path.size(), 12); i++) w.append(path.get(i).kind()).append('>').append(path.get(i).to().toShortString()).append("; ");
				XenMod.LOG.info("[walk] {} plan from {} to {} (goal {}, partial {}, moved {}, was {}): {}", c.name, p.blockPosition().toShortString(),
						BlockPos.containing(to).toShortString(), want, partial, moved, was == null ? "-" : was.kind() + ">" + was.to().toShortString(), w);
			}
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
	private static final int NODES_PER_TICK = 5000;
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
		float risk = c.personality.risk();                                 // its beliefs: "fortune favors the bold", "look before you leap"
		if (risk > 0.25f && h >= 14) {
			maxGap = Math.min(3, maxGap + 1);
			maxFall += 1;
		} else if (risk < -0.25f) {
			maxGap = Math.min(maxGap, 1);
			maxFall = Math.min(maxFall, 3);
		}
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
		return (mobWay ? "mob way " : "") + kinds.toString().toLowerCase(java.util.Locale.ROOT);
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

	private List<Move> plan(ServerLevel level, BlockPos start, Vec3 to, Goal want) {
		this.level = level;
		this.eyes = start;
		BlockPos target = BlockPos.containing(to);
		Goal aim = want != null ? want : Goal.block(target);
		partial = false;
		if (!passable(start) && passable(start.above())) start = start.above();   // on a slab, a path, mud: its feet are a little higher
		mobWay = false;
		if (want == null && mobPaths() && now() >= noMobWayUntil && reachable(target)) {   // the mobs' way first (the setting): quick, sure, no digging
			List<Move> m = mobPath.plan(c, level, start, to, 1600);
			if (m != null) {
				mobWay = true;
				return m;
			}
		}
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();
		Node first = new Node(start);
		first.placedFloor = c.player.onGround();                            // it's standing (maybe on the very edge of a block)
		first.f = h(aim, start);
		nodes.put(start.asLong(), first);
		open.add(first);
		Node best = first;
		double bestH = first.f;
		int expanded = 0;
		// Nothing better yet than where it stands (at the foot of a wall that takes digging, the cheap ways all lead
		// further from the goal): it thinks harder, up to four times as long, before it says there's no way (then the
		// solver would back it off to look again: walking back and forth).
		while (!open.isEmpty() && (expanded < LIMIT || best == first && expanded < LIMIT * 4)) {
			Node n = open.poll();
			if (n.closed) continue;
			n.closed = true;
			expanded++;
			double hn = h(aim, n.pos);
			if (aim.isIn(n.pos) || want == null && hn < 0.01) {
				best = n;
				break;
			}
			if (hn < bestH) {
				bestH = hn;
				best = n;
			}
			for (Move m : moves(n.pos, n.placedFloor, blocks - n.placed)) {
				double cost = m.cost() * (1 + bad.getOrDefault(key(m), 0) * 4) * back(m.to(), target);
				if (cost >= INF) continue;
				Node next = nodes.computeIfAbsent(m.to().asLong(), k -> new Node(m.to()));
				if (next.closed || n.g + cost >= next.g && next.parent != null) continue;
				next.g = n.g + cost;
				next.f = next.g + h(aim, m.to());
				next.parent = n;
				next.via = m;
				next.placedFloor = m.kind() == Kind.PILLAR || m.kind() == Kind.BRIDGE;
				next.placed = n.placed + (next.placedFloor ? 1 : 0);
				open.add(next);
			}
		}
		if (best == first) {
			if (WALK_DEBUG) {
				StringBuilder w = new StringBuilder();
				for (Move m : moves(start, first.placedFloor, blocks)) w.append(m.kind()).append('>').append(m.to().toShortString()).append(String.format(" %.0f; ", m.cost()));
				XenMod.LOG.info("[walk] {} no way from {} (expanded {}, open {}, h {}, dig {}, onGround {}): {}", c.name, start.toShortString(), expanded, open.size(),
						String.format("%.1f", bestH), dig, c.player.onGround(), w);
			}
			return null;
		}
		partial = !aim.isIn(best.pos) && !(want == null && h(aim, best.pos) < 0.01);   // (only part of the way: more later)
		List<Move> out = new ArrayList<>();
		for (Node n = best; n.via != null; n = n.parent) out.add(0, n.via);
		return out;
	}

	/** Going back over blocks it walked on in the last 20 seconds costs more (much more while it's been pacing). */
	private double back(BlockPos to, BlockPos target) {
		Long at = walked.get(to.asLong());
		if (at == null || to.equals(target) || now() - at > 400) return 1;
		return now() < lockedUntil ? 6 : 2.5;
	}

	/** Are the next few moves of its way still good, by what it sees now (not walled off, no lava)? */
	private boolean stillGood() {
		if (path == null) return false;
		level = (ServerLevel) c.player.level();
		eyes = c.player.blockPosition();
		for (int i = index; i < Math.min(path.size(), index + 4); i++) {
			Move m = path.get(i);
			for (BlockPos b : new BlockPos[] {m.to(), m.to().above()}) {
				if (!m.dig().contains(b) && !passable(b) && m.kind() != Kind.PILLAR && m.kind() != Kind.CLIMB_UP && m.kind() != Kind.SWIM) return false;
			}
			if (lava(m.to()) || lava(m.to().below())) return false;
		}
		return true;
	}

	/**
	 * Where it has been: the blocks it walked on (for the next plan) and, every second, where it stands. Walking eight
	 * blocks or more in eight seconds and ending up where it started is pacing back and forth: then it keeps to one way.
	 */
	private void pace() {
		var p = c.player;
		walked.put(p.blockPosition().asLong(), now());
		if (walked.size() > 400) walked.values().removeIf(t -> now() - t > 400);
		if (now() - lastTrail < 20) return;
		lastTrail = now();
		trail.addLast(p.position());
		if (trail.size() > 8) trail.removeFirst();
		if (trail.size() < 8 || now() < lockedUntil) return;
		double walkedFar = 0;
		Vec3 prev = null;
		for (Vec3 v : trail) {
			if (prev != null) walkedFar += Math.hypot(v.x - prev.x, v.z - prev.z);
			prev = v;
		}
		Vec3 first = trail.peekFirst();
		if (walkedFar > 8 && Math.hypot(prev.x - first.x, prev.z - first.z) < 2) {
			lockedUntil = now() + 200;                                        // ten seconds on one way, no turning back
			plannedAt = 0;
			c.pacings++;
			XenMod.LOG.info("{} was pacing back and forth ({} blocks walked, {} from where it was): it keeps to one way now",
					c.name, String.format(java.util.Locale.ROOT, "%.0f", walkedFar), String.format(java.util.Locale.ROOT, "%.1f", Math.hypot(prev.x - first.x, prev.z - first.z)));
			c.journal("way", "was pacing back and forth: keeps to one way");
			trail.clear();
		}
	}

	/** At best a sprint the whole way (a little more: it would rather find a good way than the shortest one). */
	/** How far from the goal, a little more than a sprint the whole way (it would rather find a good way than the shortest). */
	private static double h(Goal aim, BlockPos p) {
		return aim.isIn(p) ? 0 : aim.heuristic(p) * 1.15;
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
			if (!passable(q) && step(q) && floorOnceDug(up) && !farmland(up)) {   // (never jump onto farmland: it tramples it)
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
				if (water(r) || k <= maxFall && floor(r) && !farmland(r) && realDrop(q) <= maxFall) {   // (a dark pit looks deep to anyone)
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
				// up across a gap (a sprint-jump lands a block higher two blocks on), where it's brave enough and a miss won't hurt
				if (maxGap >= 1 && realDrop(q) <= maxFall) {
					BlockPos up2 = p.relative(d, 2).above(), mid = p.relative(d, 1);
					if (passable(p.above(2)) && passable(mid.above()) && passable(mid.above(2)) && passable(up2) && passable(up2.above())
							&& floor(up2) && !farmland(up2)) {
						add(Kind.PARKOUR, p, up2, 2 * SPRINT + JUMP + 6 + 8 * fear + danger(up2), List.of());
					}
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
			if (br < INF) add(Kind.PILLAR, p, above, PLACE + JUMP + 24 + br, br > 0 ? List.of(p.above(2)) : List.of());   // (a last resort: a jump up a step is better)
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
		return p.distManhattan(eyes) <= 8 || level.getRawBrightness(p, 0) > 0;
	}

	/**
	 * What it knows is at a block. Planning asks about the same blocks over and over, so each answer is kept for the
	 * rest of the tick (the world doesn't change within one), and blocks come straight from their chunk.
	 */
	private BlockState state(BlockPos p) {
		long now = level.getGameTime(), key = p.asLong();
		if (now != cacheTick || level != cacheLevel) {
			cache.clear();
			chunks.clear();
			cacheTick = now;
			cacheLevel = level;
		}
		BlockState s = cache.get(key);
		if (s != null) return s;
		var chunk = chunks.computeIfAbsent(((long) (p.getX() >> 4) << 32) | ((p.getZ() >> 4) & 0xFFFFFFFFL),
				k -> level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4));
		if (chunk == null) {
			s = Blocks.BEDROCK.defaultBlockState();
		} else {
			s = chunk.getBlockState(p);
			if (!known(p) && s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty()) s = Blocks.STONE.defaultBlockState();
		}
		cache.put(key, s);
		return s;
	}

	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> cache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.chunk.LevelChunk> chunks = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private long cacheTick = -1;
	private ServerLevel cacheLevel;

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
	/**
	 * It could stand at p once what's in the way is dug out: the block under it firm to stand on, and p and the block
	 * over it clear or diggable (a wall two high: it digs a step into it and goes up, like a player; before, only a
	 * block over its own head could be dug, and at such a wall it found no way and backed off to look again).
	 */
	private boolean floorOnceDug(BlockPos p) {
		if (floor(p)) return true;
		if (breaks(p) >= INF || breaks(p.above()) >= INF) return false;
		BlockPos b = p.below();
		BlockState s = state(b);
		VoxelShape shape = s.getCollisionShape(level, b);
		if (shape.isEmpty() || shape.max(Direction.Axis.Y) > 1.0) return false;
		return !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.CAMPFIRE) && !s.is(Blocks.SOUL_CAMPFIRE) && !s.is(Blocks.CACTUS);
	}

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
		if (body.getAirSupply() < body.getMaxAirSupply() / 2) noDiveUntil = now() + 600;   // (nearly out of air just now: no more ways under for 30 s)
		return body.getAirSupply() < body.getMaxAirSupply() * 0.8 || now() < noDiveUntil ? INF : 30;
	}

	/** Till when it plans no way with its head under water (it ran short of air there). */
	long noDiveUntil;

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
		if (WALK_DEBUG) XenMod.LOG.info("[walk] {} #{} {} {}->{} feet={} pos={} ground={} yaw={} zza={} v={}", c.name, index, m.kind(), m.from().toShortString(),
				m.to().toShortString(), c.player.blockPosition().toShortString(), String.format("%.2f,%.2f,%.2f", c.player.getX(), c.player.getY(), c.player.getZ()),
				c.player.onGround(), Math.round(c.player.getYRot()), c.player.zza, String.format("%.2f,%.2f", c.player.getDeltaMovement().x, c.player.getDeltaMovement().z));
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
		if (mobWay) noMobWayUntil = now() + 100;                              // the mobs' way didn't work here: its own for a bit
		c.troubles++;
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
		boolean onFoot = m.kind() == Kind.WALK || m.kind() == Kind.DIAGONAL || m.kind() == Kind.ASCEND || m.kind() == Kind.FALL;
		if (!last && onFoot) {                                               // already on the next step (it ran or jumped on): on with it
			BlockPos next = path.get(index + 1).to();
			Kind nextKind = path.get(index + 1).kind();
			if (feet.getX() == next.getX() && feet.getZ() == next.getZ() && Math.abs(feet.getY() - next.getY()) <= 1
					&& nextKind != Kind.PARKOUR && nextKind != Kind.BRIDGE && nextKind != Kind.PILLAR && m.dig().isEmpty()) return true;
		}
		if (!last && (m.kind() == Kind.WALK || m.kind() == Kind.DIAGONAL) && smooth()) {   // (running on through; in the air mid-jump too)
			return feet.getX() == m.to().getX() && feet.getZ() == m.to().getZ() && feet.getY() >= m.to().getY() && feet.getY() <= m.to().getY() + 2
					&& flat < 0.9;
		}
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
		steer(m, to, last);
		boolean straight = !last && direction(path.get(index + 1)) == direction(m);
		p.zza = (float) (flat > 0.15 ? Math.min(1, flat * (last ? 1.5 : 3)) : 0);
		boolean sprint = (m.kind() == Kind.WALK || m.kind() == Kind.DIAGONAL || m.kind() == Kind.PARKOUR || m.kind() == Kind.ASCEND)
				&& (straight || smooth() || m.kind() == Kind.PARKOUR) && (!stroll || m.kind() == Kind.PARKOUR) && p.getFoodData().getFoodLevel() > 6 && !p.isInWater() && remaining() > 2;
		var ex = c.mod.config.ex1 ? c.ex1Out : null;                              // Xen Ex1: how the player it learned from moved
		if (ex != null && sprint && m.kind() != Kind.PARKOUR && ex.keys[xen.mod.core.Ex1.SPRINT] < 0.25f) sprint = false;   // (they walked here)
		p.setSprinting(sprint);
		if (ex != null && sprint && straight && (m.kind() == Kind.WALK || m.kind() == Kind.DIAGONAL) && p.onGround() && remaining() > 4
				&& ex.presses(xen.mod.core.Ex1.JUMP) && p.level().getBlockState(p.blockPosition().above(2)).getCollisionShape(p.level(), p.blockPosition().above(2)).isEmpty()) {
			p.setJumping(true);                                                     // sprint-jumping, the way they travelled
		}
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
				if (flat > 1 && p.isInWater() && p.getFoodData().getFoodLevel() > 6) p.setSprinting(true);   // swimming fast (against a current too)
				p.setShiftKeyDown(dy < -0.8);
				if (dy < -0.8) face(to.add(0, -1, 0));
			}
			case FALL -> {
				doing = "going down";
				if (!p.onGround() && flat < 0.5) p.zza = 0;                      // over it: just drop
			}
			default -> {
				doing = sprint ? "sprinting" : "walking";
				if (sprint && p.onGround() && flatAhead(4) && now() - lastHop > 12) {   // a long flat stretch: sprint-jumping, like players
					p.setJumping(true);
					lastHop = now();
					doing = "sprint-jumping";
				}
			}
		}
		// in water and the way goes up (a waterfall, flowing water, a bank): hold space and it swims up it, like a player
		if (p.isInWater() && m.to().getY() + 0.2 >= p.getY() && m.kind() != Kind.FALL) p.setJumping(true);
		// stuck against a block edge (it happens): a hop, like a player
		if (p.horizontalCollision && p.onGround() && m.kind() != Kind.FALL && now() - stepStarted > 10) p.setJumping(true);
		// an edge it doesn't mean to go over (a cliff, a ravine, a dark one too: a player sees a pit is deep): it
		// crouches there, like a player, and can't slip off (running on, it could fly past its turn)
		if (p.onGround() && !p.isInWater() && m.kind() != Kind.FALL && m.kind() != Kind.PARKOUR && m.kind() != Kind.DIG_DOWN) {
			var v = p.getDeltaMovement();
			double yaw = Math.toRadians(p.getYRot());
			Vec3 ahead = p.position().add(-Math.sin(yaw) * 0.6 + v.x * 3, 0, Math.cos(yaw) * 0.6 + v.z * 3);
			BlockPos under = BlockPos.containing(ahead);
			if (!under.equals(m.to()) && realDrop(under) > 4) {
				p.setShiftKeyDown(true);
				p.setSprinting(false);
				doing = "careful at the edge";
			}
		}
	}

	/** How far down it would really fall from this block (0: something to stand on, or water to land in). */
	private int realDrop(BlockPos from) {
		var lv = (ServerLevel) c.player.level();
		BlockPos.MutableBlockPos q = from.mutable();
		int k = 0;
		while (k < 32) {
			var st = lv.getBlockState(q);
			if (!st.getFluidState().isEmpty()) return 0;
			if (!st.getCollisionShape(lv, q).isEmpty()) return k == 0 ? 0 : k - 1;
			q.move(Direction.DOWN);
			k++;
		}
		return k;
	}

	private long lastHop;

	/** The next move keeps about the same heading (45 degrees at most): it runs on through, sprinting, like a player. */
	private boolean smooth() {
		if (path == null || index + 1 >= path.size()) return false;
		Move a = path.get(index), b = path.get(index + 1);
		double ax = a.to().getX() - a.from().getX(), az = a.to().getZ() - a.from().getZ();
		double bx = b.to().getX() - b.from().getX(), bz = b.to().getZ() - b.from().getZ();
		double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
		if (la < 0.1 || lb < 0.1) return false;
		return (ax * bx + az * bz) / (la * lb) >= 0.7;
	}
	/** Minecraft's own mob pathfinder, and whether the way it's on came from it. */
	final MobPath mobPath = new MobPath();
	boolean mobWay;
	/** A mob's way that failed on the ground: its own planner for a while. */
	private long noMobWayUntil;

	/** Somewhere a mob could get to: open, or a block with an open side (not one buried in the ground). */
	private boolean reachable(BlockPos t) {
		if (!level.isLoaded(t)) return true;
		if (level.getBlockState(t).getCollisionShape(level, t).isEmpty()) return true;
		for (Direction d : Direction.values()) if (level.getBlockState(t.relative(d)).getCollisionShape(level, t.relative(d)).isEmpty()) return true;
		return false;
	}

	/** Does it plan the mobs' way first (the setting; "ab" splits the Xens in two, by name)? */
	boolean mobPaths() {
		String mode = c.mod.config.pathMode;
		return "mob".equals(mode) || "ab".equals(mode) && c.mod.companions.indexOf(c) % 2 == 0;
	}
	/** The last few goals it walked to (going back to one it just left is dithering, not work). */
	private final java.util.ArrayDeque<Vec3> recentGoals = new java.util.ArrayDeque<>();

	/** The next n moves are plain walking the same way on the level, with room over its head (for a sprint-jump). */
	private boolean flatAhead(int n) {
		if (index + n > path.size()) return false;
		Move first = path.get(index);
		for (int i = index; i < index + n; i++) {
			Move m = path.get(i);
			if (m.kind() != Kind.WALK || !m.dig().isEmpty() || m.to().getY() != first.from().getY() || direction(m) != direction(first)) return false;
			if (!passable(m.to().above(2))) return false;
		}
		return true;
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

	/**
	 * Where it looks while it walks, like a player running: its body turned to where it's going (the step it's on, or
	 * the next one once it's right on top of this one or already past it: never turning round mid-jump or mid-fall to
	 * face a spot behind it, which would also brake it, as the keys push the way it faces), and its eyes on the way a
	 * few steps ahead at about eye height, not down at its feet. The eyes follow smoothly.
	 */
	private void steer(Move m, Vec3 to, boolean last) {
		var p = c.player;
		Vec3 aim = to;
		double dx = to.x - p.getX(), dz = to.z - p.getZ(), flat = Math.hypot(dx, dz);
		Vec3 v = p.getDeltaMovement();
		boolean passed = v.horizontalDistance() > 0.05 && dx * v.x + dz * v.z < 0     // (moving away from it: it's behind,
				&& (flat < 1.2 || !p.onGround());                                      //  just overshot or in the air; not knocked back)
		if (!last && (flat < 0.5 || passed)) aim = Vec3.atBottomCenterOf(path.get(index + 1).to());
		double ax = aim.x - p.getX(), az = aim.z - p.getZ();
		float yRot = Math.hypot(ax, az) < 0.1 ? p.getYRot() : (float) Math.toDegrees(Math.atan2(-ax, az));
		p.setYRot(yRot);
		p.setYHeadRot(yRot);
		Vec3 gaze = Vec3.atBottomCenterOf(path.get(Math.min(path.size() - 1, index + 3)).to()).add(0, 1.5, 0);
		Vec3 d = gaze.subtract(p.getEyePosition());
		float want = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(2.0, Math.hypot(d.x, d.z))));   // (never closer than 2 blocks: no staring down)
		var ex = c.mod.config.ex1 ? c.ex1Out : null;
		if (ex != null) want = 0.5f * want + 0.5f * Math.max(-30f, Math.min(45f, ex.pitchDegrees()));   // (where Ex1 would look: the player it learned from)
		want = Math.max(-30, Math.min(35, want));
		p.setXRot(p.getXRot() + (want - p.getXRot()) * 0.3f);
	}

	/** For developing the walking only (-Dxen.walkDebug=true): every step, every plan, every "no way" in the log. */
	/** Where it's walking to (null: nowhere now). */
	Vec3 goal() {
		return path == null ? null : goal;
	}

	/** Taking its time (a first look around): it walks, it doesn't run (a gap it still jumps at a run). */
	boolean stroll;
	static final boolean WALK_DEBUG = Boolean.getBoolean("xen.walkDebug");

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
