package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Caves, the way players use them: the fastest way to ore (the walls show it, no digging needed). A Xen notices a cave
 * when it sees one: a dark opening below the ground (dark air, under the surface, with room around it), only what's in
 * front of its eyes. It remembers the caves it found; mining, it goes in with torches (it lights the dark as it goes),
 * follows the cave down, mines the ore showing in the walls, and digs its own tunnels only where there's no cave.
 */
final class Caves {
	private final Companion c;
	private final Random random = new Random();
	/** Caves it found (their mouths), newest last. */
	final List<BlockPos> known = new ArrayList<>();
	/** Cave spots it has been to lately (so it goes on to new parts, not round in circles). */
	private final Map<Long, Long> been = new HashMap<>();
	private long nextScan;

	Caves(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** Every few seconds: a look for caves in sight. */
	void scan() {
		if (c.player == null || now() < nextScan) return;
		nextScan = now() + 100;
		ServerLevel level = (ServerLevel) c.player.level();
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
		BlockPos at = c.player.blockPosition();
		BlockPos spot = sample(level, at, 24, -18, 6, 600, false);
		if (spot == null) return;
		for (BlockPos k : known) if (k.closerThan(spot, 24)) return;           // (one it knows already)
		known.add(spot);
		if (known.size() > 12) known.remove(0);
		c.places.remember("cave", spot);
		c.journal("sees", "a cave at " + spot.toShortString());
		XenMod.LOG.info("{} found a cave at {}", c.name, spot.toShortString());
		c.chatter(c.pick3("A cave! Good place for ore.", "There's a cave there. I'll remember it.", "Ooh, a cave."), false);
	}

	/** The nearest cave it knows (within that far), or null. */
	BlockPos nearest(double far) {
		if (c.player == null) return null;
		BlockPos best = null;
		for (BlockPos k : known) {
			if (!k.closerThan(c.player.blockPosition(), far)) continue;
			if (best == null || k.distSqr(c.player.blockPosition()) < best.distSqr(c.player.blockPosition())) best = k;
		}
		return best;
	}

	/**
	 * In a cave: the next spot to go to, deeper in (lower, somewhere it hasn't been, that it can see and stand on), or
	 * null when it has seen all of this cave it can.
	 */
	BlockPos deeper() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos at = c.player.blockPosition();
		been.put(at.asLong() >> 2 << 2, now());
		return sample(level, at, 14, -10, 3, 160, true);
	}

	/**
	 * A random look around: a dark spot of a cave (air it could stand in, under the surface, with room around it) that
	 * it can see. inside: deeper and new is better; else the nearest mouth.
	 */
	private BlockPos sample(ServerLevel level, BlockPos at, int r, int down, int up, int tries, boolean inside) {
		Vec3 eye = c.player.getEyePosition();
		BlockPos best = null;
		double bestScore = -1e9;
		for (int i = 0; i < tries; i++) {
			BlockPos q = at.offset(random.nextInt(2 * r + 1) - r, down + random.nextInt(up - down + 1), random.nextInt(2 * r + 1) - r);
			if (!level.isLoaded(q) || !level.getBlockState(q).isAir() || !level.getBlockState(q.above()).isAir()) continue;
			if (level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) continue;   // (something to stand on)
			if (level.getBrightness(LightLayer.SKY, q) > 7) continue;                                     // (dim: under rock, not a valley)
			if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ()) <= q.getY() + 3) continue;   // under the ground
			if (!inside && level.getBrightness(LightLayer.BLOCK, q) > 7) continue;                      // (a lit room is someone's, not a cave)
			if (air(level, q) < 10) continue;                                                            // room around it: a cave, not a hole
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER,
					net.minecraft.world.level.ClipContext.Fluid.ANY, c.player));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) continue;                  // it has to see it
			double score = inside ? 2 * (at.getY() - q.getY()) - (been.containsKey(q.asLong() >> 2 << 2) && now() - been.get(q.asLong() >> 2 << 2) < 6000 ? 30 : 0)
					- 0.1 * Math.sqrt(q.distSqr(at)) : -q.distSqr(at);
			if (lavaNear(level, q)) score -= 50;
			if (score > bestScore) {
				bestScore = score;
				best = q.immutable();
			}
		}
		return inside && bestScore < -20 ? null : best;
	}

	private static int air(ServerLevel level, BlockPos q) {
		int n = 0;
		for (BlockPos b : BlockPos.betweenClosed(q.offset(-1, 0, -1), q.offset(1, 2, 1))) if (level.getBlockState(b).isAir()) n++;
		return n;
	}

	private static boolean lavaNear(ServerLevel level, BlockPos q) {
		for (BlockPos b : BlockPos.betweenClosed(q.offset(-2, -1, -2), q.offset(2, 1, 2))) if (level.getFluidState(b).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		return false;
	}

	String describe() {
		BlockPos n = nearest(200);
		return n == null ? "" : "You know a cave at " + n.getX() + " " + n.getY() + " " + n.getZ() + " (caves are the quickest way to ore).";
	}
}
