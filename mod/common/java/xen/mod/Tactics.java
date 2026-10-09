package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import xen.mod.core.Action;

/**
 * What players do when the world turns on them, from a player's own answers (each one a {@link Knowledge} mechanic: a
 * Xen that knows it does it, one that doesn't can be told or learn it from a Xen who knows):
 * <ul>
 *   <li>water flooding into its tunnel: a block on it, then on another way ("block_water");</li>
 *   <li>lava next to it underground: covered with blocks ("cover_lava");</li>
 *   <li>stuck in powder snow: it mines its way out ("powder_snow");</li>
 *   <li>a spawner: torches on it (no monsters), and it's remembered for a mob farm, never broken ("spawner_torch").</li>
 * </ul>
 * Each looks at the blocks next to it only (what it would feel or see), every half second, and does one thing at a time.
 */
final class Tactics {
	private final Companion c;
	private long nextLook, nextSpawner, nextWall, nextGaze;

	Tactics(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private static final boolean DEBUG = Boolean.getBoolean("xen.pearlDebug");
	private net.minecraft.world.entity.Entity flying;
	private net.minecraft.world.phys.Vec3 flyingAt;

	/** An enderman? (By its id: the class moves between versions.) */
	static boolean enderman(net.minecraft.world.entity.Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("enderman");
	}

	/** Where it last stood on solid ground (to pearl back to, flung off the edge). */
	private net.minecraft.world.phys.Vec3 lastGround;
	private long pearlAt = -1000;

	/**
	 * Every tick, falling: a pearl clutch (a player's way, from a recorded dragon fight: flung thirty blocks up by the
	 * dragon's wings, they threw an ender pearl at the ground below and landed with a scratch). With ground below and a
	 * fall that would hurt more than a pearl does (5), a pearl straight down; with nothing below (the void), a pearl
	 * back to where it last stood. True if it threw one.
	 */
	boolean pearlTick() {
		var p = c.player;
		if (p == null || p.isCreative() || p.isSpectator()) return false;
		ServerLevel level = (ServerLevel) p.level();
		if (DEBUG) {
			if (flying == null && now() - pearlAt < 3) for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class, p.getBoundingBox().inflate(4))) if (e.getOwner() == p) flying = e;
			if (flying != null && flying.isRemoved()) { c.journal("thinks", "[pearl] came down at " + flying.position() + "; it's at " + p.position()); flying = null; }
			else if (flying != null) flyingAt = flying.position();
		}
		if (p.onGround() && !p.isInWater()) {
			BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());   // (really on a block: not just teleported up)
			if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
				lastGround = p.position();
				pearlAt = -1000;                                                    // (landed: a new pearl at once if it goes off again)
			}
			return false;
		}
		long now = now();
		if (now - pearlAt < 30 || p.getDeltaMovement().y > 0 || p.isInWater() || p.isFallFlying()) return false;
		if (!c.knowledge.knows("pearl_clutch") || !c.hands.carries("ender_pearl")) return false;
		BlockPos feet = p.blockPosition();
		int ground = -1;
		for (int k = 1; k <= 64 && feet.getY() - k >= level.getMinY(); k++) {
			BlockPos q = feet.below(k);
			if (!level.getFluidState(q).isEmpty()) return false;               // (water below: it lands in it)
			if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
				ground = k;
				break;
			}
		}
		// Off an edge (the land it was on is close, and not far below): a pearl back onto it, before it falls too far.
		if (lastGround != null && Math.hypot(lastGround.x - p.getX(), lastGround.z - p.getZ()) < 30 && lastGround.y > p.getY() - 12
				&& lastGround.y < p.getY() + 25 && (ground < 0 || ground > 6)) {           // (too far below it, no throw gets it back up)
			net.minecraft.world.phys.Vec3 inland = landingSpot(level, lastGround);   // (well in from the edge it came off)
			float[] aim = pearlAim(level, p.getEyePosition(), net.minecraft.world.phys.Vec3.ZERO, inland);   // (a thrown pearl gets the movement a player's client last sent, which a Xen has none of: none)
			if (DEBUG) c.journal("thinks", "[pearl] from " + p.getEyePosition() + " to " + inland + " aim " + (aim == null ? "none" : aim[0] + "/" + aim[1]));
			if (aim != null && c.hands.throwAt("ender_pearl", aim[0], aim[1])) {
				pearlAt = now;
				c.journal("does", "off the edge: an ender pearl back to " + BlockPos.containing(lastGround).toShortString());
				c.chatter("Whoa! Pearl!", false);
				return true;
			}
		}
		if (ground > 0) {                                                       // ground below: a pearl clutch if the fall would hurt more than the pearl (5)
			if (p.getDeltaMovement().y > -0.3 || p.fallDistance + ground < 12) return false;
			if (!c.hands.throwAt("ender_pearl", p.getYRot(), 90f)) return false;
			pearlAt = now;
			c.journal("does", "pearl clutch: an ender pearl at the ground " + ground + " blocks down");
			return true;
		}
		return false;
	}

	/** Near where it last stood, the top of the block with the most solid ground around it (in from an edge or a corner). */
	private static net.minecraft.world.phys.Vec3 landingSpot(ServerLevel level, net.minecraft.world.phys.Vec3 stood) {
		BlockPos at = BlockPos.containing(stood).below();
		BlockPos best = null;
		double bestScore = -1e9;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 1, 3))) {
			if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()) continue;
			int around = 0;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos n = q.offset(dx, 0, dz);
				if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) around++;
			}
			double score = around - 0.3 * Math.sqrt(q.distSqr(at));
			if (score > bestScore) {
				bestScore = score;
				best = q.immutable();
			}
		}
		return best == null ? stood : net.minecraft.world.phys.Vec3.atCenterOf(best).add(0, 0.5, 0);
	}

	/**
	 * The throw (yaw, pitch) that lands a pearl on top of a block nearest to the spot: each pitch from -60 to 60 is flown
	 * the way the game flies a pearl (1.5 a tick along the throw plus its own movement, 0.99 drag, 0.03 gravity a tick)
	 * through the real blocks. A throw that hits the side of a block (it would only fall again) doesn't count. Null if
	 * none lands within 4 blocks of the spot.
	 */
	static float[] pearlAim(ServerLevel level, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 own, net.minecraft.world.phys.Vec3 to) {
		float yaw = (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
		float best = Float.NaN;
		double bestMiss = 4;
		for (int pitch = -60; pitch <= 60; pitch += 2) {
			double rp = Math.toRadians(pitch), ry = Math.toRadians(yaw);
			double vx = -Math.sin(ry) * Math.cos(rp) * 1.5 + own.x, vy = -Math.sin(rp) * 1.5 + own.y, vz = Math.cos(ry) * Math.cos(rp) * 1.5 + own.z;
			double x = from.x, y = from.y, z = from.z;
			y -= 0.1;                                                                   // (it leaves from just under the eyes)
			boolean hit = false;
			for (int t = 0; t < 200 && y > level.getMinY() && !hit; t++) {
				vy -= 0.03;                                                             // (the game's order: gravity, drag, then the move)
				vx *= 0.99; vy *= 0.99; vz *= 0.99;
				for (int sub = 1; sub <= 4 && !hit; sub++) {                            // (in quarters: a corner isn't flown through)
					double px = x, py = y, pz = z;
					x += vx / 4; y += vy / 4; z += vz / 4;
					BlockPos b = BlockPos.containing(x, y, z);
					if (level.getBlockState(b).getCollisionShape(level, b).isEmpty()) continue;
					hit = true;
					boolean onTop = vy < 0 && py >= b.getY() + 0.9 && level.getBlockState(b.above()).getCollisionShape(level, b.above()).isEmpty();
					double miss = Math.hypot(px - to.x, pz - to.z);
					if (onTop && miss < bestMiss) {
						bestMiss = miss;
						best = pitch;
					}
				}
			}
		}
		return Float.isNaN(best) ? null : new float[] {yaw, best};
	}

	private BlockPos waterNoBlock;
	private long waterNoBlockUntil;

	/** The next thing to do about water, lava, powder snow or a spawner close by, or null. */
	Action next() {
		var p = c.player;
		if (p == null || p.isCreative() || c.inArena) return null;
		long now = now();
		ServerLevel level = (ServerLevel) p.level();
		BlockPos feet = p.blockPosition();
		if (p.isInPowderSnow && c.knowledge.knows("powder_snow")) {          // (every tick: it's sinking)
			for (BlockPos b : new BlockPos[] {feet.above(), feet}) {
				if (level.getBlockState(b).is(Blocks.POWDER_SNOW) && c.hands.mine(b)) {
					c.goals.instant = "mining its way out of the powder snow";
					c.acted = true;
					return Action.MINE;
				}
			}
		}
		if (now >= nextGaze && !c.fightingNow() && c.knowledge.knows("endermen")) {
			nextGaze = now + 4;                                                 // endermen about: eyes on the ground a few blocks ahead (a player's way)
			boolean near = !level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(32),
					e -> e.isAlive() && enderman(e) && e.getTarget() != p).isEmpty();
			if (near) {
				var look = p.getLookAngle();
				c.hands.watching = null;
				c.hands.holdLook(p.position().add(look.x * 3, 0.1, look.z * 3), 8);   // (about 30 degrees down, whichever way it turns)
			}
		}
		if (now < nextLook) return null;
		nextLook = now + 10;
		if (p.isUnderWater() || p.isInLava()) return null;
		boolean under = !level.canSeeSky(feet.above());
		if (under && c.knowledge.knows("block_water")) {
			BlockPos w = flowingIn(level, feet);
			if (w != null && w.equals(waterNoBlock) && now < waterNoBlockUntil) w = null;   // (it tried there just now and couldn't: not again and again)
			if (w != null && c.hands.placeAt(w, "any")) {                    // a block in the way of the water
				c.goals.instant = "blocking the water flooding in";
				c.journal("does", "blocked the water flowing into its tunnel");
				c.walker.stop();                                              // (and another way from here)
				c.acted = true;
				return Action.PLACE;
			}
			if (w != null) {
				waterNoBlock = w.immutable();
				waterNoBlockUntil = now + 20 * 15;
				c.journal("thinks", "water coming in at " + w.toShortString() + ", can't block it: " + c.hands.cantPlace);
			}
		}
		if (under && c.knowledge.knows("cover_lava")) {
			BlockPos l = lavaBeside(level, feet);
			if (l != null && c.hands.placeAt(l, "any")) {
				c.goals.instant = "covering the lava";
				c.journal("does", "covered lava next to it with a block");
				c.acted = true;
				return Action.PLACE;
			}
		}
		if (under && now >= nextWall && c.knowledge.knows("trial_walls") && c.places.get("trial chamber") == null) {
			nextWall = now + 100;
			BlockPos wall = flatWall(level);
			if (wall != null) {
				c.places.remember("trial chamber", wall);
				c.journal("notes", "a dead-flat wall at " + wall.toShortString() + ": a trial chamber behind it, most likely");
				c.chatter(c.pick3("This wall is way too flat... a trial chamber must be behind it!", "A perfectly flat wall down here? Trial chamber!",
						"Flat wall. That means a trial chamber, I know it."), false);
			}
		}
		if (now >= nextSpawner && c.knowledge.knows("spawner_torch")) {
			nextSpawner = now + 40;
			Action t = lightSpawner(level, feet);
			if (t != null) return t;
		}
		return null;
	}

	/**
	 * A wall it's looking at in a cave that's dead flat: 5 wide and 4 high of rock with open air all along in front.
	 * Caves are rough; a trial chamber's shell isn't, and caves that run into one end in a flat wall. Null if none.
	 */
	private BlockPos flatWall(ServerLevel level) {
		BlockPos eye = BlockPos.containing(c.player.getEyePosition());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (int k = 2; k <= 16; k++) {
				BlockPos q = eye.relative(d, k);
				if (level.getBlockState(q).isAir()) continue;
				if (c.walker.beenAt(q.relative(d.getOpposite()))) break;           // (a room it dug itself: its own flat walls)
				Direction side = d.getClockWise();
				int flat = 0;
				for (int a = -2; a <= 2; a++) {
					for (int y = -1; y <= 2; y++) {
						BlockPos w = q.relative(side, a).above(y);
						if (level.getBlockState(w).is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) && level.getBlockState(w.relative(d.getOpposite())).isAir()) flat++;
					}
				}
				if (flat == 20) return q.relative(d, 6);                       // (the chamber: behind the wall)
				break;
			}
		}
		return null;
	}

	/** Water coming in beside its feet or head, or from above (a block it can put something in), or null. */
	private static BlockPos flowingIn(ServerLevel level, BlockPos feet) {
		for (BlockPos at : new BlockPos[] {feet, feet.above()}) {
			for (Direction d : Direction.values()) {
				if (d == Direction.DOWN) continue;
				BlockPos q = at.relative(d);
				if (q.equals(feet) || q.equals(feet.above())) continue;
				var f = level.getFluidState(q);
				if (f.is(FluidTags.WATER) && level.getBlockState(q).canBeReplaced()) return q;
			}
		}
		return null;
	}

	/** Lava beside it or under the blocks next to it (one step and it's in), or null. */
	private static BlockPos lavaBeside(ServerLevel level, BlockPos feet) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (BlockPos q : new BlockPos[] {feet.relative(d), feet.relative(d).below(), feet.relative(d).above()}) {
				if (level.getFluidState(q).is(FluidTags.LAVA) && level.getBlockState(q).canBeReplaced()) return q;
			}
		}
		BlockPos below = feet.below();
		return level.getFluidState(below).is(FluidTags.LAVA) ? below : null;
	}

	/** A spawner within reach with no light on it: a torch on top (it stays: a mob farm later), remembered. */
	private Action lightSpawner(ServerLevel level, BlockPos feet) {
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 3, 4))) {
			if (!level.getBlockState(q).is(Blocks.SPAWNER)) continue;
			BlockPos spawner = q.immutable();
			if (c.places.get("spawner") == null || !c.places.get("spawner").closerThan(spawner, 8)) {
				c.places.remember("spawner", spawner);
				c.journal("notes", "a spawner at " + spawner.toShortString() + ": a mob farm, some day");
			}
			BlockPos top = spawner.above();
			if (!level.getBlockState(top).canBeReplaced() || level.getBlockState(top).is(Blocks.TORCH)) return null;
			if (!c.hands.carries("torch")) return null;
			if (c.hands.placeItem(top, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("torch"), spawner, Direction.UP, -1)) {
				c.goals.instant = "putting a torch on the spawner";
				c.chatter("A spawner! A torch on it, and I'll make a mob farm here one day.", false);
				c.acted = true;
				return Action.PLACE;
			}
			return null;
		}
		return null;
	}
}
