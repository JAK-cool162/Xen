package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * Tidying up after itself, the way most players do: the pillar it jumped up on (to reach a log at the top of a tree, to
 * get out of a zombie's reach) comes down again once it's done up there, instead of a stalk of dirt left standing in
 * the field. Up on it still: it digs the block under its feet, one at a time, and drops down with it. Down already: it
 * takes the pillar apart from the ground, the top first (nothing left floating). A habit, not a rule: each pillar it
 * weighs once (a diligent Xen nearly always clears up, a lazy, slothful one leaves some), never in a fight, never with
 * a monster close, and never a block that's become part of something (one with something on it, or down in a hole it
 * climbed out of, where the pillar is just filling the hole back in).
 */
final class Tidy {
	private final Companion c;
	private final java.util.Random random = new java.util.Random();
	/** The blocks it put under its own feet to climb (or a night's hut): where, when, its call on it (0: not yet, 1: take it down, 2: leave it), and 1 for a hut's. */
	private final List<long[]> placed = new ArrayList<>();
	/** It just came down off its perch (healed, nothing about): down by digging, not jumping, for a moment. */
	private long downUntil;
	private BlockPos working;
	private long workingSince;
	String doing = "";

	Tidy(Companion c) {
		this.c = c;
	}

	/** A block it just put under its feet, climbing (not one of a build's: the builder takes its own scaffold down). */
	void placed(BlockPos at) {
		if (c.builder.busy() || c.player.level().dimension() == net.minecraft.world.level.Level.END) return;   // (the End's towers to a crystal stay)
		placed.add(new long[] {at.asLong(), c.player.level().getGameTime(), 0, 0});
		while (placed.size() > 96) placed.remove(0);
	}

	/**
	 * Morning, and the little hut it put up for the night: does it take it down (the blocks back in its bag, the field
	 * as it was)? Its call, once for the whole hut; then the roof first, the walls from the top.
	 */
	void shelter(List<BlockPos> blocks) {
		if (blocks.isEmpty() || c.player == null) return;
		var p = c.personality;
		float tidy = 0.45f + 0.45f * p.diligence - 0.3f * p.sin(xen.mod.core.Sins.SLOTH);
		if (random.nextFloat() >= tidy) {
			c.journal("thinks", "leaves its night's hut standing (could come in handy)");
			return;
		}
		long now = c.player.level().getGameTime();
		for (BlockPos b : blocks) placed.add(new long[] {b.asLong(), now - 40, 1, 1});
		while (placed.size() > 96) placed.remove(0);
		c.journal("thinks", "morning: takes its night's hut down again (" + blocks.size() + " blocks)");
	}

	/** Off its pillar by digging down: the fight's over and it healed up there. */
	void comeDown() {
		downUntil = c.player.level().getGameTime() + 200;
	}

	private boolean mine(BlockPos b) {
		for (long[] e : placed) if (e[0] == b.asLong()) return true;
		return false;
	}

	/** Its call on a pillar block, once: does it bother taking it down? */
	private boolean wants(long[] e) {
		if (e[2] == 0) {
			var p = c.personality;
			float tidy = 0.55f + 0.45f * p.diligence - 0.3f * p.sin(xen.mod.core.Sins.SLOTH);
			e[2] = random.nextFloat() < tidy ? 1 : 2;
		}
		return e[2] == 1;
	}

	/** Leaves and a tree's trunk aren't ground: a pillar up a tree stands among them. */
	private static boolean loose(net.minecraft.world.level.block.state.BlockState s) {
		return s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS);
	}

	/** Standing up out of the ground (no more than one side against earth or stone), not filling a hole or a gap in a wall. */
	private static boolean sticksUp(ServerLevel level, BlockPos b) {
		int ground = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (!loose(level.getBlockState(b.relative(d)))) ground++;
		return ground <= 1;
	}

	/** This moment's tidying (null: nothing to tidy, or not now). */
	Action next() {
		if (placed.isEmpty() || c.player == null || c.fightingNow() || c.fighter.perched() || c.pillaring || c.builder.busy() || c.chores.onOreWay()) return null;
		XenPlayer p = c.player;
		ServerLevel level = (ServerLevel) p.level();
		long now = level.getGameTime();
		BlockPos feet = p.blockPosition(), under = feet.below();
		boolean onTop = p.onGround() && mine(under);
		// not with a monster close, not in the dark of night out in the open (it has better things to do then)
		if (!level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(8),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).isEmpty()) return null;
		placed.removeIf(e -> {                                               // gone (dug, burnt, built over), or long ago
			BlockPos b = BlockPos.of(e[0]);
			return level.getBlockState(b).canBeReplaced() || now - e[1] > 20 * 60 * 10 || !level.isLoaded(b);
		});
		if (onTop) {                                                         // still up on it: once it's done up there, down it digs
			long[] e = placed.stream().filter(x -> x[0] == under.asLong()).findFirst().orElse(null);
			long last = 0;
			for (long[] x : placed) last = Math.max(last, x[1]);
			BlockPos t = c.chores.target;
			boolean working = c.chores.busy() && t != null && t.getY() >= feet.getY() - 1 && t.closerThan(feet, 6);   // (a log up here it's still after)
			boolean done = now < downUntil || e != null && now - last > 100 && !working;   // (a perch it left, or a while up there with nothing to do up here)
			if (e == null || !done || !wants(e)) return null;
			doing = "digging its pillar down under its feet";
			c.goals.instant = doing;
			if (c.hands.mine(under)) {
				c.journal("does", String.format(java.util.Locale.ROOT, "digs down its pillar (%d %d %d): done up there", under.getX(), under.getY(), under.getZ()));
				c.acted = true;
				return Action.MINE;
			}
			e[2] = 2;                                                        // (it can't: it lets that one be)
			return null;
		}
		if (!p.onGround()) return null;
		// down already: the nearest pillar it left close by, top first
		BlockPos best = null;
		for (long[] e : placed) {
			BlockPos b = BlockPos.of(e[0]);
			if (now - e[1] < 40 || b.distSqr(feet) > 144) continue;         // (just put there: it may still want it)
			if (!loose(level.getBlockState(b.above())) || mine(b.above()) || e[3] == 0 && !sticksUp(level, b)) continue;   // (the top first; something on it, or it fills a hole: that stays)
			if (!wants(e)) continue;
			if (best == null || b.getY() > best.getY() || b.getY() == best.getY() && b.distSqr(feet) < best.distSqr(feet)) best = b;
		}
		if (best == null) return null;
		if (!best.equals(working)) {
			working = best;
			workingSince = now;
		} else if (now - workingSince > 200) {                              // ten seconds at it and no luck: it leaves this one
			for (long[] e : placed) if (e[0] == best.asLong()) e[2] = 2;
			working = null;
			return null;
		}
		doing = "taking down the pillar it climbed";
		c.goals.instant = doing;
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(best)) <= p.blockInteractionRange() - 0.5) {
			if (c.hands.mine(best)) {
				c.journal("does", String.format(java.util.Locale.ROOT, "takes down the pillar it climbed (%d %d %d)", best.getX(), best.getY(), best.getZ()));
				c.acted = true;
				return Action.MINE;
			}
			for (long[] e : placed) if (e[0] == best.asLong()) e[2] = 2;     // (out of its reach from here after all)
			return null;
		}
		BlockPos foot = best;                                                // next to its foot, on the ground
		for (int i = 0; i < 8 && level.getBlockState(foot.below()).canBeReplaced() == false && mine(foot.below()); i++) foot = foot.below();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = foot.relative(d);
			if (level.getBlockState(side).canBeReplaced() && level.getBlockState(side.above()).canBeReplaced()) {
				Action a = c.walkTo(Vec3.atBottomCenterOf(side));
				if (a != null) return a;
			}
		}
		for (long[] e : placed) if (e[0] == best.asLong()) e[2] = 2;         // no way over to it
		return null;
	}
}
