package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Its eyes, block by block: a yes or no for every block it can see. Over its whole view (110 by 90 degrees, where
 * its head points) it sends a sight line every 3 degrees out to 48 blocks, a slice of them each tick (the whole view
 * about every second and a half, each sweep a little shifted so nothing slips between the lines). Every block a line
 * passes or stops at gets a yes or no: worth knowing (ore, a tree, grass for seeds, a chest, water, lava, crops, what
 * people build) or not. It sees what a player would: nothing through rock, and in the dark only what's close by (8
 * blocks) or lit. What it saw, it remembers (where, and when), for its chores to go for; dark open space under the
 * ground is a cave, and blocks people build, close together, are a house.
 */
final class Eyes {
	static final int RANGE = 48, NEAR_IN_DARK = 8;
	private static final double H_FOV = Math.toRadians(110), V_FOV = Math.toRadians(90), STEP = Math.toRadians(3);
	private static final int COLS = (int) (H_FOV / STEP) + 1, ROWS = (int) (V_FOV / STEP) + 1, PER_TICK = 24, CAP = 2000;
	/** The yes or no for each kind of block (null: no, not worth knowing), worked out once per block state. */
	private static final Map<BlockState, String> KIND = new IdentityHashMap<>();

	private final Companion c;
	private final Random random = new Random();
	/** What it saw: for each kind, where, and when (game time); compact maps (a few bytes a block, not dozens). */
	final Map<String, Long2LongOpenHashMap> seen = new HashMap<>();
	private int cursor;
	private double jitterH, jitterV;
	/** Found this sweep, and all its life (for the journal and the tests). */
	int sweeps, newThisSweep, caves, houses;
	private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

	Eyes(Companion c) {
		this.c = c;
	}

	/** The yes or no: the kind of thing a block is, if it's worth knowing. */
	static String kind(BlockState s) {
		String k = KIND.get(s);
		if (k != null) return k.isEmpty() ? null : k;
		k = classify(s);
		synchronized (KIND) {
			KIND.put(s, k == null ? "" : k);
		}
		return k;
	}

	private static String classify(BlockState s) {
		if (s.isAir()) return null;
		String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		if (n.endsWith("_ore")) {
			for (String ore : new String[] {"diamond", "emerald", "gold", "iron", "copper", "lapis", "redstone", "coal"}) if (n.contains(ore)) return ore;
			return "ore";
		}
		if (n.equals("ancient_debris")) return "debris";
		if (n.equals("lava")) return "lava";
		if (n.equals("water")) return s.getFluidState().isSource() ? "water" : null;
		if (n.endsWith("_log") || n.endsWith("_stem") && (n.contains("crimson") || n.contains("warped"))) return "log";
		if (n.equals("short_grass") || n.equals("tall_grass") || n.equals("fern") || n.equals("large_fern")) return "grass";
		if (n.equals("chest") || n.equals("barrel") || n.equals("trapped_chest")) return "chest";
		if (n.equals("wheat") || n.equals("carrots") || n.equals("potatoes") || n.equals("beetroots")) return "crop";
		if (n.equals("sugar_cane") || n.equals("melon") || n.equals("pumpkin") || n.equals("sweet_berry_bush")) return n;
		if (n.equals("sand") || n.equals("gravel") || n.equals("clay")) return n;
		if (n.equals("obsidian")) return "obsidian";
		if (n.equals("spawner")) return "spawner";
		if (n.endsWith("_planks") || n.endsWith("_door") || n.endsWith("_bed") || n.contains("glass") || n.endsWith("_bricks") || n.equals("bricks")
				|| n.equals("crafting_table") || n.equals("furnace") || n.endsWith("_wool") || n.endsWith("_fence") || n.endsWith("_stairs")
				|| n.endsWith("_slab") || n.equals("torch") || n.equals("wall_torch") || n.endsWith("lantern") || n.equals("bookshelf")) return "built";
		return null;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** Every tick: the next slice of sight lines. */
	void tick() {
		if (c.player == null || c.player.isSleeping()) return;
		ServerLevel level = (ServerLevel) c.player.level();
		Vec3 eye = c.player.getEyePosition();
		double yaw = Math.toRadians(c.player.getYRot()), pitch = Math.toRadians(c.player.getXRot());
		for (int i = 0; i < PER_TICK; i++) {
			if (cursor >= COLS * ROWS) {                                         // a whole sweep done: the next one shifted a little
				cursor = 0;
				sweeps++;
				newThisSweep = 0;
				jitterH = random.nextDouble() * STEP;
				jitterV = random.nextDouble() * STEP;
				if (sweeps % 20 == 0) forget();
			}
			int col = cursor % COLS, row = cursor / COLS;
			cursor++;
			double h = yaw - H_FOV / 2 + col * STEP + jitterH, v = Math.max(-Math.PI / 2 + 0.01, Math.min(Math.PI / 2 - 0.01, pitch - V_FOV / 2 + row * STEP + jitterV));
			double dx = -Math.sin(h) * Math.cos(v), dy = -Math.sin(v), dz = Math.cos(h) * Math.cos(v);   // (Minecraft's yaw and pitch)
			cast(level, eye, dx, dy, dz);
		}
	}

	/** One sight line, block by block (every block it crosses), until something stops it. */
	private void cast(ServerLevel level, Vec3 eye, double dx, double dy, double dz) {
		int x = (int) Math.floor(eye.x), y = (int) Math.floor(eye.y), z = (int) Math.floor(eye.z);
		int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
		double tdx = dx == 0 ? 1e9 : Math.abs(1 / dx), tdy = dy == 0 ? 1e9 : Math.abs(1 / dy), tdz = dz == 0 ? 1e9 : Math.abs(1 / dz);
		double tx = dx == 0 ? 1e9 : (dx > 0 ? x + 1 - eye.x : eye.x - x) * tdx;
		double ty = dy == 0 ? 1e9 : (dy > 0 ? y + 1 - eye.y : eye.y - y) * tdy;
		double tz = dz == 0 ? 1e9 : (dz > 0 ? z + 1 - eye.z : eye.z - z) * tdz;
		int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE, darkRun = 0;
		net.minecraft.world.level.chunk.LevelChunk chunk = null;
		boolean lit = true;
		double t = 0;
		while (t < RANGE) {
			if (tx < ty && tx < tz) {
				x += sx;
				t = tx;
				tx += tdx;
			} else if (ty < tz) {
				y += sy;
				t = ty;
				ty += tdy;
			} else {
				z += sz;
				t = tz;
				tz += tdz;
			}
			if (y < level.getMinY() || y >= level.getMaxY()) return;
			if (x >> 4 != chunkX || z >> 4 != chunkZ) {                        // (blocks straight from the chunk: quick)
				chunkX = x >> 4;
				chunkZ = z >> 4;
				chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) return;
			}
			m.set(x, y, z);
			BlockState s = chunk.getBlockState(m);
			boolean open = s.isAir() || s.getCollisionShape(level, m).isEmpty();
			if (open && s.getFluidState().isEmpty()) {                         // air (or grass, a flower): light, and caves
				boolean under = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) > y + 3;
				if (t > NEAR_IN_DARK) lit = !under || level.getRawBrightness(m, 0) > 0;   // (out under the sky it's never too dark to see)
				if (under && t <= 32 && level.getBrightness(LightLayer.SKY, m) <= 7) {
					if (++darkRun == 5) c.caves.saw(m.immutable());             // dark room under the ground: a cave
				} else {
					darkRun = 0;
				}
			}
			if (!lit) {
				if (!open) return;                                              // a wall in the dark: it can't make it out
				continue;
			}
			if (open && s.getFluidState().isEmpty() && chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) > y) sawOpen(level);
			String k = kind(s);
			if (k != null) remember(k, m, t);
			else if (darkRun > 0 && !open && deepStone(s)) remember("deepstone", m, t);   // dark stone under the grass: where ore is

			if (s.canOcclude() && !s.is(BlockTags.LEAVES)) return;               // rock, a wall: that's as far as it sees
			if (!s.getFluidState().isEmpty() && t > 16) return;                  // (deep water: murky)
		}
	}

	/**
	 * The open space it has seen under cover (caves, overhangs, under water's edge): the shape of the world as far as
	 * it knows it, for its way. Out under the sky anyone sees the lay of the land; under the ground only this, what's
	 * close by and the way it came, the rest is rock to it. Two lots, the older let go when the new one fills (a few
	 * hundred kilobytes, never the world).
	 */
	private it.unimi.dsi.fastutil.longs.LongOpenHashSet looked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(),
			lookedBefore = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lookedIn;
	private static final int LOOKED_CAP = 40000;

	boolean looked(BlockPos p, ServerLevel level) {
		long k = p.asLong();
		return level.dimension() == lookedIn && (looked.contains(k) || lookedBefore.contains(k));
	}

	private void sawOpen(ServerLevel level) {
		if (level.dimension() != lookedIn) {
			looked.clear();
			lookedBefore.clear();
			lookedIn = level.dimension();
		}
		if (looked.add(m.asLong()) && looked.size() >= LOOKED_CAP) {
			lookedBefore = looked;
			looked = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		}
	}

	/** Stone or deepslate (the rock ore hides in). */
	static boolean deepStone(BlockState s) {
		String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return n.equals("stone") || n.equals("deepslate") || n.equals("tuff") || n.equals("andesite") || n.equals("diorite") || n.equals("granite");
	}

	private void remember(String kind, BlockPos at, double t) {
		Long2LongOpenHashMap where = seen.computeIfAbsent(kind, k -> new Long2LongOpenHashMap());
		long key = at.asLong();
		if (where.size() >= CAP && !where.containsKey(key)) {                // full: old ones go first (now and then), else it skips it
			if (now() - forgotAt > 100) forget();
			if (where.size() >= CAP) return;
		}
		if (where.put(key, now()) == 0) {
			newThisSweep++;
			if (kind.equals("built") && where.size() % 12 == 0) c.structures.builtSeen(at.immutable(), where);
		}
	}

	/** Old sightings (10 minutes) and far ones (160 blocks) fade. */
	private long forgotAt;

	private void forget() {
		long t = now();
		forgotAt = t;
		BlockPos here = c.player.blockPosition();
		for (Long2LongOpenHashMap where : seen.values()) {
			var it = where.long2LongEntrySet().fastIterator();
			while (it.hasNext()) {
				var e = it.next();
				if (t - e.getLongValue() > 12000 || !BlockPos.of(e.getLongKey()).closerThan(here, 160)) it.remove();
			}
			where.trim();
		}
	}

	/**
	 * The nearest one of a kind it saw (within max blocks) that is still there (it checks: a tree cut down, an ore
	 * mined by someone else, is forgotten); skip: ones it gave up on. Null if none.
	 */
	BlockPos nearest(String kind, double max, java.util.function.LongPredicate skip) {
		Long2LongOpenHashMap where = seen.get(kind);
		if (where == null || where.isEmpty()) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos here = c.player.blockPosition(), best = null;
		double bestD = max * max;
		var it = where.keySet().iterator();
		while (it.hasNext()) {
			long key = it.nextLong();
			BlockPos p = BlockPos.of(key);
			double d = p.distSqr(here);
			if (d >= bestD || skip != null && skip.test(key)) continue;
			if (!level.isLoaded(p) || !(kind.equals("deepstone") ? deepStone(level.getBlockState(p)) : kind.equals(kind(level.getBlockState(p))))) {
				it.remove();                                                    // gone
				continue;
			}
			bestD = d;
			best = p;
		}
		return best;
	}

	int count(String kind) {
		Long2LongOpenHashMap where = seen.get(kind);
		return where == null ? 0 : where.size();
	}

	String describe() {
		StringBuilder sb = new StringBuilder();
		for (String k : new String[] {"diamond", "iron", "gold", "coal", "log", "chest", "water"}) {
			BlockPos p = nearest(k, RANGE * 2, null);
			if (p == null) continue;
			sb.append(sb.length() == 0 ? "Seen lately:" : ",").append(' ').append(k.equals("log") ? "a tree" : k.equals("water") ? "water" : k.equals("chest") ? "a chest" : k + " ore")
					.append(String.format(java.util.Locale.ROOT, " %.0f blocks away", Math.sqrt(p.distSqr(c.player.blockPosition()))));
		}
		return sb.length() == 0 ? "" : sb.append('.').toString();
	}
}
