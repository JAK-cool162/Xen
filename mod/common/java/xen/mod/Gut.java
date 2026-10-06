package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * Xen 2.0's third brain, the gut: what happens next if nothing changes, and the plan that takes over when the answer
 * is "I get hurt". It runs before anything else it does, asked or not (a command waits while it survives), each time
 * a reaction time after it notices ({@link Reflexes}):
 * <ol>
 *   <li>in lava: out, to the nearest safe block;</li>
 *   <li>lava right next to its feet: a step away from it;</li>
 *   <li>on fire with water close by: into the water;</li>
 *   <li>badly hurt (3 hearts or less) with a monster on it: back off out of reach for a few seconds, then eat if it
 *   can (a creeper's hiss is the fight brain's: it runs).</li>
 * </ol>
 * It says so once ("Hold on, lava!") when it breaks off something it was asked to do.
 */
final class Gut {
	private final Companion c;
	private long saidAt, backingUntil, backedOffAt;
	/** What it's doing to survive right now (for its thoughts), or empty. */
	String doing = "";
	int overrides;

	Gut(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	Action override() {
		var p = c.player;
		if (p == null || c.inArena || p.isCreative() || p.isSpectator()) return null;
		ServerLevel level = (ServerLevel) p.level();
		Action a = null;
		String why = null;
		if (p.isInLava()) {
			if (!c.reflexes.ready("gut:inlava", false)) return null;
			BlockPos safe = safeSpot(level, p.blockPosition(), 5);
			why = "lava";
			doing = "getting out of the lava";
			c.walker.stop();
			if (safe != null) c.hands.face(Vec3.atBottomCenterOf(safe).add(0, 1.2, 0));   // (eyes on the way out, and jump for it: no path finding in lava)
			p.setSprinting(true);
			a = Action.JUMP;
		} else if (p.onGround() && headingInto(level, p.blockPosition())) {
			BlockPos lava = lavaAtFeet(level, p.blockPosition());
			if (!c.reflexes.ready("gut:lava@" + lava.asLong(), false)) return null;
			BlockPos safe = awayFrom(level, p.blockPosition(), lava);
			if (safe == null) return null;
			why = "lava";
			doing = "stepping back from lava";
			c.walker.stop();
			c.hands.face(Vec3.atCenterOf(lava));                              // eyes on the lava, and a step back from it, like a player
			p.setSprinting(false);
			a = Action.BACK;
		} else if (p.isOnFire() && !p.isInWater()) {
			BlockPos water = waterWithin(level, p.blockPosition(), 6);
			if (water == null || !c.reflexes.ready("gut:fire", false)) return null;
			why = "fire";
			doing = "running into the water to put the fire out";
			a = c.walkTo(Vec3.atBottomCenterOf(water));
		} else {
			a = backOff(p.getHealth());
			if (a != null) why = "hurt";
		}
		if (a == null) {
			doing = "";
			return null;
		}
		overrides++;
		c.goals.instant = doing;
		boolean asked = c.commandedTo != null || c.chores.busy() && !c.chores.own;
		if (asked && now() - saidAt > 600) {
			saidAt = now();
			c.chatter(switch (why) {
				case "lava" -> c.pick3("Hold on, lava!", "Whoa, lava. One sec.", "Lava! Wait.");
				case "fire" -> c.pick3("I'm on fire! Water!", "Hot hot hot!", "Fire! One sec.");
				default -> c.pick3("Hold on, I'm hurt!", "Wait, I need a moment!", "Back off, back off!");
			}, true);
		}
		if (overrides % 10 == 1) c.journal("gut", "takes over: " + doing);
		return a;
	}

	/** Badly hurt with a monster on it: a few seconds out of reach (once every half minute), then food. */
	private Action backOff(float health) {
		var p = c.player;
		long now = now();
		if (health > 6 && now >= backingUntil) return null;
		LivingEntity mob = null;
		double best = 5;
		for (Monster m : p.level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(5), LivingEntity::isAlive)) {
			if (m instanceof Creeper) continue;                            // (a creeper: the fight brain runs from it)
			double d = m.distanceTo(p);
			if (d < best) {
				best = d;
				mob = m;
			}
		}
		if (mob == null) {
			if (now < backingUntil && c.items().getOrDefault("food", 0) > 0 && p.getFoodData().needsFood()) {
				doing = "eating, out of reach";
				return Action.EAT;
			}
			return null;
		}
		if (now >= backingUntil) {
			if (now - backedOffAt < 600 || !c.reflexes.ready("gut:hurt@" + mob.getUUID(), false)) return null;
			backedOffAt = now;
			backingUntil = now + 80;                                        // four seconds of backing off, then it fights again if it must
		}
		Vec3 away = p.position().subtract(mob.position());
		away = new Vec3(away.x, 0, away.z);
		if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
		doing = "backing off from the " + Facts.kind(mob).replace('_', ' ') + ", badly hurt";
		p.setSprinting(true);
		return c.walkTo(p.position().add(away.normalize().scale(6)));
	}

	/** Lava beside its feet (it'll flow in), or an edge down to lava it's walking toward. */
	private boolean headingInto(ServerLevel level, BlockPos feet) {
		BlockPos lava = lavaAtFeet(level, feet);
		if (lava == null) return false;
		if (lava.getY() == feet.getY()) return true;
		Vec3 v = c.player.getDeltaMovement(), to = Vec3.atCenterOf(lava).subtract(c.player.position());
		return v.x * to.x + v.z * to.z > 0.02;
	}

	/** Lava next to its feet (or under the block next to it, an edge), or null. */
	static BlockPos lavaAtFeet(ServerLevel level, BlockPos feet) {
		for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos n = feet.relative(d);
			if (level.getFluidState(n).is(FluidTags.LAVA)) return n;
			if (level.getBlockState(n).getCollisionShape(level, n).isEmpty() && level.getFluidState(n.below()).is(FluidTags.LAVA)) return n.below();
		}
		return null;
	}

	/** A block it can stand on farther from that lava (within 3 blocks of it: the farther from the lava the better), or null. */
	private static BlockPos awayFrom(ServerLevel level, BlockPos feet, BlockPos lava) {
		BlockPos best = null;
		double bestScore = -1e9, now = feet.distSqr(lava);
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 1, 3))) {
			double away = q.distSqr(lava);
			if (away <= now || !standable(level, q)) continue;
			double score = Math.sqrt(away) - 0.5 * Math.sqrt(q.distSqr(feet));
			if (score > bestScore) {
				bestScore = score;
				best = q.immutable();
			}
		}
		return best;
	}

	private static BlockPos safeSpot(ServerLevel level, BlockPos from, int r) {
		BlockPos best = null;
		double bestD = 1e9;
		for (BlockPos q : BlockPos.betweenClosed(from.offset(-r, -1, -r), from.offset(r, 2, r))) {
			if (!standable(level, q)) continue;
			double d = q.distSqr(from);
			if (d < bestD) {
				bestD = d;
				best = q.immutable();
			}
		}
		return best;
	}

	/** Solid under it, room for its body, no lava in or next to it. */
	static boolean standable(ServerLevel level, BlockPos q) {
		if (!level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)) return false;
		if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) return false;
		if (level.getFluidState(q).is(FluidTags.LAVA) || level.getFluidState(q.above()).is(FluidTags.LAVA)) return false;
		return lavaAtFeet(level, q) == null;
	}

	private static BlockPos waterWithin(ServerLevel level, BlockPos from, int r) {
		BlockPos best = null;
		double bestD = 1e9;
		for (BlockPos q : BlockPos.betweenClosed(from.offset(-r, -2, -r), from.offset(r, 1, r))) {
			if (!level.getFluidState(q).is(FluidTags.WATER)) continue;
			double d = q.distSqr(from);
			if (d < bestD) {
				bestD = d;
				best = q.immutable();
			}
		}
		return best;
	}
}
