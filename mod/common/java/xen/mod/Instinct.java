package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Instinct: what a body does to stay alive when there's no doubt about it, before anything else, every tick. Not a
 * feeling to weigh (that's the gut, which a Xen can argue with) and not a plan (its mind): only when the danger is
 * clear and the way out is plain.
 * <ul>
 *   <li><b>Out of air</b>: under water, air under 60%, open water above: up, holding space, not pushing into a wall.</li>
 *   <li><b>In lava</b>: out to the nearest block it can stand on that isn't lava, jumping (or straight up).</li>
 *   <li><b>Standing in fire</b>: a step to the nearest block without fire.</li>
 *   <li><b>At the edge of a deadly drop</b>: about to step off where the fall would kill it or take half its health (no
 *   water to land in): it stops and steps back. Its mind can argue with its gut about a ledge; not about that.</li>
 * </ul>
 * While it acts the gut keeps quiet and whatever else the Xen was doing waits (its keys are its instinct's). It runs
 * last in a Xen's tick, so nothing else this tick undoes it.
 */
final class Instinct {
	private final Companion c;
	/** What it's doing right now (for its thoughts and /xen status), or empty. */
	String doing = "";
	private String last = "";
	private int lastAt;
	int takeovers;

	Instinct(Companion c) {
		this.c = c;
	}

	/** Is a clear danger on it right now (then nothing argues: not its gut, not its mind)? */
	boolean clear() {
		var p = c.player;
		if (p == null || !p.isAlive() || p.isCreative() || p.isSpectator()) return false;
		return p.isInLava() || p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() * 0.6 && openAbove((ServerLevel) p.level())
				|| inFire((ServerLevel) p.level(), p.blockPosition()) || deadlyEdge((ServerLevel) p.level());
	}

	/** Every tick, after everything: the keys for staying alive, if it has to. */
	void tick() {
		var p = c.player;
		if (p == null || !p.isAlive() || p.isCreative() || p.isSpectator()) {
			doing = "";
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		String now = "";
		if (p.isInLava()) now = outOfLava(level);
		else if (p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() * 0.6 && openAbove(level)) now = upForAir();
		else if (inFire(level, p.blockPosition())) now = outOfFire(level);
		else if (deadlyEdge(level)) now = backFromEdge();
		doing = now;
		if (now.isEmpty()) {
			if (p.tickCount - lastAt > 20) last = "";                    // (bobbing out of it for a moment is still the same danger)
			return;
		}
		lastAt = p.tickCount;
		c.goals.instant = now;
		c.acted = true;
		if (!now.equals(last)) {
			takeovers++;
			c.walker.stop();
			if (c.hands.busy() && c.hands.interruptible()) c.hands.stop();
			c.journal("instinct", "takes over: " + now);
			XenMod.LOG.info("{}'s instinct takes over: {}", c.name, now);
		}
		last = now;
	}

	/** Water and then air straight up over its head (a roof: the way round is for its mind, Companion.toAir). */
	private boolean openAbove(ServerLevel level) {
		BlockPos head = BlockPos.containing(c.player.getEyePosition());
		for (int k = 1; k <= 8; k++) {
			var st = level.getBlockState(head.above(k));
			if (!st.getCollisionShape(level, head.above(k)).isEmpty()) return false;
			if (st.getFluidState().isEmpty()) return true;
		}
		return false;
	}

	private String upForAir() {
		var p = c.player;
		p.setJumping(true);
		p.setShiftKeyDown(false);
		p.setSprinting(false);
		if (p.horizontalCollision) {                                   // (into a wall: up, not forward)
			p.zza = 0;
			p.xxa = 0;
		}
		return "swimming up for air";
	}

	private static boolean inFire(ServerLevel level, BlockPos feet) {
		var s = level.getBlockState(feet);
		return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE);
	}

	/** The nearest block round it (4 out, a block up or down) it could stand in: no lava or fire, room, firm under it. Null: none. */
	private BlockPos safeStep(ServerLevel level, boolean fromLava) {
		var p = c.player;
		BlockPos feet = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dy = fromLava ? 1 : 0; dy >= -1; dy--) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					if (dx == 0 && dz == 0) continue;
					BlockPos q = feet.offset(dx, dy, dz);
					if (!room(level, q) || !room(level, q.above())) continue;
					var under = level.getBlockState(q.below());
					if (under.getCollisionShape(level, q.below()).isEmpty() || under.getFluidState().is(FluidTags.LAVA) || under.is(Blocks.MAGMA_BLOCK)) continue;
					double dist = Math.hypot(q.getX() + 0.5 - p.getX(), q.getZ() + 0.5 - p.getZ()) + (dy > 0 ? 0.3 : 0);
					if (dist < bestD) {
						bestD = dist;
						best = q;
					}
				}
			}
		}
		return best;
	}

	private static boolean room(ServerLevel level, BlockPos q) {
		var s = level.getBlockState(q);
		return s.getCollisionShape(level, q).isEmpty() && !s.getFluidState().is(FluidTags.LAVA) && !s.is(Blocks.FIRE) && !s.is(Blocks.SOUL_FIRE);
	}

	private String outOfLava(ServerLevel level) {
		var p = c.player;
		BlockPos to = safeStep(level, true);
		p.setJumping(true);                                            // (in lava, space is what keeps you up)
		p.setShiftKeyDown(false);
		p.setSprinting(false);
		if (to != null) toward(Vec3.atBottomCenterOf(to));
		return "getting out of the lava";
	}

	private String outOfFire(ServerLevel level) {
		BlockPos to = safeStep(level, false);
		if (to == null) {
			c.player.setJumping(true);
			return "jumping out of the fire";
		}
		toward(Vec3.atBottomCenterOf(to));
		return "stepping out of the fire";
	}

	/**
	 * Walking on, on the ground, toward an edge (the block it's heading into has nothing under it) where the fall would
	 * kill it or take half its health, with no water down there. (Going down on purpose, its way's own drop, is never
	 * that far: Walker's falls are 3 to 6.)
	 */
	private boolean deadlyEdge(ServerLevel level) {
		var p = c.player;
		if (!p.onGround() || p.isInWater() || p.isPassenger() || p.getAbilities().flying || p.isShiftKeyDown() || c.walker.leaping()) return false;   // (sneaking it can't fall; a jump across is on purpose)
		var v = p.getDeltaMovement();
		double speed = Math.hypot(v.x, v.z);
		Vec3 dir;
		if (speed > 0.03) dir = new Vec3(v.x / speed, 0, v.z / speed);
		else if (p.zza > 0) dir = Vec3.directionFromRotation(0, p.getYRot());
		else return false;
		BlockPos ahead = BlockPos.containing(p.getX() + dir.x * 0.7, p.getY() + 0.1, p.getZ() + dir.z * 0.7);
		if (ahead.equals(p.blockPosition())) return false;
		if (!level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty()) return false;     // a wall, a step: no edge
		int drop = 0;
		for (BlockPos q = ahead.below(); drop < 64 && q.getY() > level.getMinY(); q = q.below(), drop++) {
			var st = level.getBlockState(q);
			if (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;   // (water down there: a soft landing)
			if (!st.getCollisionShape(level, q).isEmpty()) break;
		}
		float hurt = drop - 3;                                          // (a fall hurts a heart for each block past three)
		return hurt >= p.getHealth() || hurt >= p.getMaxHealth() / 2;
	}

	private String backFromEdge() {
		var p = c.player;
		p.zza = 0;
		p.xxa = 0;
		p.setSprinting(false);
		p.setJumping(false);
		p.setShiftKeyDown(true);                                        // (sneaking: it can't walk off an edge)
		var v = p.getDeltaMovement();
		p.setDeltaMovement(v.x * 0.2, v.y, v.z * 0.2);
		return "stopping at the edge of a deadly drop";
	}

	/** Faces it and goes, at once (no time for a careful turn). */
	private void toward(Vec3 at) {
		var p = c.player;
		double dx = at.x - p.getX(), dz = at.z - p.getZ();
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.zza = 1;
		p.xxa = 0;
	}
}
