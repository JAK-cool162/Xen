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
				|| inFire((ServerLevel) p.level(), p.blockPosition());
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
