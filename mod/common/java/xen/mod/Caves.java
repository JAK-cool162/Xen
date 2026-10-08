package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
	/** Wide dark pockets with natural stone walls: the walls are more likely to expose ore. */
	private final Set<Long> orePockets = new HashSet<>();
	/** Last ore-wall check for each known cave, to avoid repeating block scans every sight sweep. */
	private final Map<Long, Long> oreChecked = new HashMap<>();
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
		if (spot == null || !rocky(level, spot) || !remember(level, spot)) return;
		c.places.rememberAnother("cave", spot, 48);
		c.journal("sees", "a cave at " + spot.toShortString());
		XenMod.LOG.info("{} found a cave at {}", c.name, spot.toShortString());
		c.chatter(caveLine(spot), false);
	}

	/**
	 * Its eyes saw dark open space under the ground (see {@link Eyes}): a cave, if there's room around the spot (not a
	 * hole) and nobody lit it (a lit room is someone's). Kept like the ones it spots itself.
	 */
	void saw(BlockPos spot) {
		if (c.player == null) return;
		ServerLevel level = (ServerLevel) c.player.level();
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
		if (air(level, spot) < 10 || level.getBrightness(LightLayer.BLOCK, spot) > 7 || !rocky(level, spot) || !remember(level, spot)) return;
		c.eyes.caves++;
		c.places.rememberAnother("cave", spot, 48);
		c.journal("sees", "a cave at " + spot.toShortString());
		XenMod.LOG.info("{} found a cave at {}", c.name, spot.toShortString());
		c.chatter(caveLine(spot), false);
	}

	/** Remember a cave once; a roomy, stone-walled pocket gets a small mining preference. */
	private boolean remember(ServerLevel level, BlockPos spot) {
		long now = now();
		for (BlockPos k : known) {
			if (!k.closerThan(spot, 24)) continue;
			long key = k.asLong();
			if (!orePockets.contains(key) && now - oreChecked.getOrDefault(key, Long.MIN_VALUE / 2) >= 100) {
				oreChecked.put(key, now);
				if (orePocket(level, spot)) orePockets.add(key);
			}
			return false;
		}
		known.add(spot.immutable());
		long key = spot.asLong();
		oreChecked.put(key, now);
		if (orePocket(level, spot) || shownLike(level, spot)) orePockets.add(key);   // (a cave like the ones it was shown: a big one, worth going to)
		if (known.size() > 12) {
			long removed = known.remove(0).asLong();
			orePockets.remove(removed);
			oreChecked.remove(removed);
		}
		return true;
	}

	/** What a cave is like, learned from the caves it was shown with the Build Axe (scripts/learn_caves.py: assets/xen/taught/caves.json). */
	private static int learnedAir = 150, learnedPocket = 11, learnedRoof = 19;
	private static float learnedRock = 0.95f;
	private static boolean learnedRead;

	private static synchronized void readLearned() {
		if (learnedRead) return;
		learnedRead = true;
		try (var in = Caves.class.getResourceAsStream("/assets/xen/taught/caves.json")) {
			if (in == null) return;
			var o = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
			learnedAir = o.get("windowAir").getAsInt();
			learnedRock = o.get("rockShare").getAsFloat();
			learnedPocket = o.get("pocketAir").getAsInt();
			learnedRoof = o.get("roofWithin").getAsInt();
			XenMod.LOG.info("Xen knows what a cave is like from {} spots in the caves it was shown ({} in the window, rock {}, a roof within {})",
					o.get("caveSpots").getAsInt(), learnedAir, learnedRock, learnedRoof);
		} catch (Exception e) {
			XenMod.LOG.warn("Xen: couldn't read what caves are like: {}", e.toString());
		}
	}

	/**
	 * Is it like the caves it was shown (a big open cave or a ravine: lots of air round the spot, rock nearly all the
	 * solid there, room close round, a rock roof over it)? Those it goes to first for ore: the walls show the most.
	 */
	static boolean shownLike(ServerLevel level, BlockPos q) {
		readLearned();
		int air = 0, solid = 0, rock = 0, near = 0;
		for (BlockPos b : BlockPos.betweenClosed(q.offset(-4, -1, -4), q.offset(4, 3, 4))) {
			var st = level.getBlockState(b);
			if (st.isAir()) {
				air++;
				if (Math.abs(b.getX() - q.getX()) <= 1 && Math.abs(b.getZ() - q.getZ()) <= 1 && b.getY() >= q.getY() && b.getY() <= q.getY() + 2) near++;
			} else if (!st.getCollisionShape(level, b).isEmpty()) {
				solid++;
				if (Eyes.deepStone(st) || Eyes.kind(st) != null && !"log".equals(Eyes.kind(st)) && !"built".equals(Eyes.kind(st))) rock++;
			}
		}
		if (air < learnedAir || solid == 0 || rock < learnedRock * solid || near < learnedPocket) return false;
		for (int up = 1; up <= learnedRoof; up++) {                                 // a roof of rock over it
			var st = level.getBlockState(q.above(up));
			if (st.isAir()) continue;
			return Eyes.deepStone(st) || Eyes.kind(st) != null && !"log".equals(Eyes.kind(st)) && !"built".equals(Eyes.kind(st));
		}
		return false;
	}

	private String caveLine(BlockPos spot) {
		return orePockets.contains(spot.asLong())
				? c.pick3("Big dark pocket, stone walls. There might be ore here.", "Stone all around a cave like this can mean ore nearby.", "That cave looks promising. I'll check the stone walls.")
				: c.pick3("A cave! Good place for ore.", "There's a cave there. I'll remember it.", "Ooh, a cave.");
	}

	/** The nearest cave it knows (within that far), or null. */
	BlockPos nearest(double far) {
		if (c.player == null) return null;
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos k : known) {
			if (!k.closerThan(c.player.blockPosition(), far)) continue;
			double score = k.distSqr(c.player.blockPosition()) - (orePockets.contains(k.asLong()) ? 16 * 16 : 0);
			if (score < bestScore) {
				bestScore = score;
				best = k;
			}
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
			boolean seen = been.containsKey(q.asLong() >> 2 << 2) && now() - been.get(q.asLong() >> 2 << 2) < 24000 || c.walker.beenAt(q);   // (the map in its head: where it has been)
			double score = inside ? 2 * (at.getY() - q.getY()) - (seen ? 30 : 0) - 0.1 * Math.sqrt(q.distSqr(at)) : -q.distSqr(at);
			if (lavaNear(level, q)) score -= 50;
			if (score > bestScore) {
				bestScore = score;
				best = q.immutable();
			}
		}
		return inside && bestScore < -20 ? null : best;
	}

	/**
	 * The simple rule for ore: a big dark space with rock all round (stone, deepslate, tuff...) is a cave, and caves
	 * are where ore shows. Half or more of the solid blocks around the spot (9 wide, 5 high) must be rock (not dirt,
	 * logs, planks: a dark forest or someone's cellar isn't a cave), and there must be real room (20+ air).
	 */
	static boolean rocky(ServerLevel level, BlockPos q) {
		int air = 0, solid = 0, rock = 0;
		for (BlockPos b : BlockPos.betweenClosed(q.offset(-4, -1, -4), q.offset(4, 3, 4))) {
			var st = level.getBlockState(b);
			if (st.isAir()) {
				air++;
			} else if (!st.getCollisionShape(level, b).isEmpty()) {
				solid++;
				if (Eyes.deepStone(st) || Eyes.kind(st) != null && !"log".equals(Eyes.kind(st)) && !"built".equals(Eyes.kind(st))) rock++;
			}
		}
		return air >= 20 && solid > 0 && rock * 2 >= solid;
	}

	private static int air(ServerLevel level, BlockPos q) {
		int n = 0;
		for (BlockPos b : BlockPos.betweenClosed(q.offset(-1, 0, -1), q.offset(1, 2, 1))) if (level.getBlockState(b).isAir()) n++;
		return n;
	}

	/**
	 * A large dark air pocket with natural stone or deepslate in at least four directions is a useful ore clue.
	 * The clue is probabilistic: it only changes which cave Xen prefers, never claims ore is guaranteed.
	 */
	private static boolean orePocket(ServerLevel level, BlockPos q) {
		if (air(level, q) < 16) return false;                   // most of the 3x3x3 space is open
		int stoneSides = 0;
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
			for (int distance = 2; distance <= 8; distance++) {
				BlockPos wall = q.relative(side, distance);
				if (!level.isLoaded(wall)) break;
				var state = level.getBlockState(wall);
				if (Eyes.deepStone(state)) {
					stoneSides++;
					break;
				}
				if (!state.isAir()) break;
			}
			if (stoneSides >= 4) return true;
		}
		return false;
	}

	private static boolean lavaNear(ServerLevel level, BlockPos q) {
		for (BlockPos b : BlockPos.betweenClosed(q.offset(-2, -1, -2), q.offset(2, 1, 2))) if (level.getFluidState(b).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		return false;
	}

	String describe() {
		BlockPos n = nearest(200);
		return n == null ? "" : "You know a cave at " + n.getX() + " " + n.getY() + " " + n.getZ()
				+ (orePockets.contains(n.asLong()) ? " (a wide dark pocket with natural stone walls; ore may be nearby)." : " (caves are the quickest way to ore).");
	}
}
