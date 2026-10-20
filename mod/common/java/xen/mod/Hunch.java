package xen.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Luck: the way a good player always seems to wander the right way. The server knows the world (the ore in the rock,
 * the caves under the hills, the villages over the next rise, where the other Xens are); a Xen never gets told any of
 * it. Now and then, when it picks which way to explore or which way to run its tunnels, its feet go the lucky way:
 * toward what it needs, roughly, never straight to it. It still has to see the thing with its own eyes, get there,
 * mine it and live through it; it's never given a place or a number, its mind never hears of it, and it can walk
 * right past.
 * <p>And Xens on good terms pass things on between them, unseen and unsaid: one that finds a village or a cave, its
 * friends happen to wander that way; one mining a cave, the others happen to find their own; two friends out
 * exploring now and then happen to run into each other. Every Xen still decides for itself what it does.
 * <p>What it steered is kept apart from what the Xen saw: the moment, in its gameplay log ("assisted"), so Ex1 doesn't
 * learn from it as if it were seen; and a choice that luck helped teaches its mind less.
 */
final class Hunch {
	/** One thing in the world: ore (a clump of it in an 8-block cell; out in the open air or not), a cave, a structure. */
	record Find(String kind, BlockPos at, int amount, boolean open) {}

	private record Chunk(long at, List<Find> finds) {}

	/** What's in each chunk near a Xen, for each world: looked at a chunk at a time, again every five minutes. */
	private static final Map<String, Map<Long, Chunk>> WORLD = new HashMap<>();
	private static final int AROUND = 5, AGAIN = 20 * 60 * 5, EVERY = 3, MOST = 6000;
	/** Ore blocks, by what they are ("iron", "diamond", ...). */
	private static final Map<Block, String> ORES = new HashMap<>();
	/** The grapevine: what Xens found and pass on to their friends; caves being mined (and by whom); meetings. */
	private static final List<Word> WORD = new ArrayList<>();
	private static final Map<Long, Claim> CLAIMS = new HashMap<>();
	private static final Map<String, Long> MET = new HashMap<>();
	private static int cursor;

	private record Word(Companion from, String kind, String dim, BlockPos at, long when) {}

	private record Claim(Companion by, long until) {}

	private static void ores() {
		if (!ORES.isEmpty()) return;
		for (Block b : BuiltInRegistries.BLOCK) {
			String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
			if (n.equals("ancient_debris")) ORES.put(b, "debris");
			else if (n.endsWith("_ore")) ORES.put(b, n.replace("deepslate_", "").replace("nether_", "").replace("_ore", ""));
		}
	}

	// ------------------------------------------------------------------------- the world, as the server knows it
	/** Every few ticks: the next chunk near a lucky Xen. */
	static void tick(XenMod mod, MinecraftServer s) {
		double luck = mod.config.luck;
		if (luck <= 0 || s.getTickCount() % EVERY != 0 || mod.companions.isEmpty()) return;
		ores();
		List<Companion> who = new ArrayList<>();
		for (Companion c : mod.companions) if (c.player != null && c.player.isAlive() && !c.minion) who.add(c);
		if (who.isEmpty()) return;
		Companion c = who.get(Math.floorMod(cursor / ((2 * AROUND + 1) * (2 * AROUND + 1)), who.size()));
		int k = cursor++ % ((2 * AROUND + 1) * (2 * AROUND + 1));
		ServerLevel level = (ServerLevel) c.player.level();
		int cx = (c.player.getBlockX() >> 4) + k % (2 * AROUND + 1) - AROUND, cz = (c.player.getBlockZ() >> 4) + k / (2 * AROUND + 1) - AROUND;
		Map<Long, Chunk> known = WORLD.computeIfAbsent(dim(level), d -> new HashMap<>());
		long key = key(cx, cz), now = level.getGameTime();
		Chunk had = known.get(key);
		if (had != null && now - had.at() < AGAIN) return;
		LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) return;
		known.put(key, new Chunk(now, look(level, chunk)));
		if (known.size() > MOST) known.values().removeIf(ch -> now - ch.at() > AGAIN * 4);
		WORD.removeIf(w -> now - w.when() > 20 * 60 * 20 || w.from().player == null);
		CLAIMS.values().removeIf(cl -> now > cl.until());
	}

	private static String dim(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xffffffffL);
	}

	/** One chunk: its ore (in clumps), its caves (big air under the ground), the structures that start in it. */
	private static List<Find> look(ServerLevel level, LevelChunk chunk) {
		List<Find> out = new ArrayList<>();
		int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ(), minY = chunk.getMinY();
		int top = Integer.MIN_VALUE;
		int[] ground = new int[16 * 16];
		for (int x = 0; x < 16; x++)
			for (int z = 0; z < 16; z++) {
				ground[x * 16 + z] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x0 + x, z0 + z);
				top = Math.max(top, ground[x * 16 + z]);
			}
		Map<Long, int[]> ore = new HashMap<>();                                  // (cell -> amount, open, x, y, z, kind)
		Map<Long, String> oreKind = new HashMap<>();
		Map<Long, int[]> air = new HashMap<>();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection sec = sections[i];
			if (sec == null || sec.hasOnlyAir()) continue;
			int y0 = minY + 16 * i;
			boolean ores = sec.maybeHas(st -> ORES.containsKey(st.getBlock()));
			boolean caves = y0 < top - 5 && sec.maybeHas(BlockState::isAir);
			if (!ores && !caves) continue;
			for (int y = 0; y < 16; y++)
				for (int x = 0; x < 16; x++)
					for (int z = 0; z < 16; z++) {
						boolean sample = caves && (x & 1) == 0 && (y & 1) == 0 && (z & 1) == 0;
						if (!ores && !sample) continue;
						BlockState st = sec.getBlockState(x, y, z);
						int wy = y0 + y;
						long cell = ((long) (x >> 3) << 40) | ((long) (z >> 3) << 32) | ((wy >> 3) & 0xffffffffL);
						final int fx = x, fz = z;
						if (sample && st.isAir() && wy < ground[x * 16 + z] - 5) air.computeIfAbsent(cell, q -> new int[] {0, fx, wy, fz})[0]++;
						String kind = ores ? ORES.get(st.getBlock()) : null;
						if (kind == null) continue;
						long oc = cell ^ ((long) kind.hashCode() << 44);
						int[] o = ore.computeIfAbsent(oc, q -> new int[] {0, 0, fx, wy, fz});
						oreKind.put(oc, kind);
						o[0]++;
						if (o[1] == 0 && openAir(chunk, x0 + x, wy, z0 + z)) o[1] = 1;
					}
		}
		for (var e : ore.entrySet()) {
			int[] o = e.getValue();
			out.add(new Find(oreKind.get(e.getKey()), new BlockPos(x0 + o[2], o[3], z0 + o[4]), o[0], o[1] == 1));
		}
		for (int[] a : air.values()) if (a[0] >= 24) out.add(new Find("cave", new BlockPos(x0 + a[1], a[2], z0 + a[3]), a[0] * 8, true));
		try {
			var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			for (var e : chunk.getAllStarts().entrySet()) {
				if (e.getValue() == null || !e.getValue().isValid()) continue;
				var id = structures.getKey(e.getKey());
				if (id == null) continue;
				String n = id.getPath();
				String kind = n.startsWith("village") ? "village" : n;
				out.add(new Find(kind, e.getValue().getBoundingBox().getCenter(), 1, true));
			}
		} catch (RuntimeException ignored) {                                     // (no structures to read: ore and caves are enough)
		}
		return out;
	}

	private static boolean openAir(LevelChunk chunk, int x, int y, int z) {
		BlockPos at = new BlockPos(x, y, z);
		for (var d : net.minecraft.core.Direction.values()) {
			BlockPos q = at.relative(d);
			if ((q.getX() >> 4) != (x >> 4) || (q.getZ() >> 4) != (z >> 4)) continue;
			if (chunk.getBlockState(q).isAir()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------------- one Xen's luck
	private final Companion c;
	/** What luck steered it toward last, and when (for its gameplay log and its mind). */
	String steered;
	long steeredAt = -1;
	/** Things it was steered to and got to (or close enough: it saw for itself), not again. */
	private final Set<Long> reached = new HashSet<>();
	private BlockPos toward;
	/** How often it happened to go the lucky way (for /xen status and the tests). */
	int times;

	Hunch(Companion c) {
		this.c = c;
	}

	/** Luck steered it, about now? */
	boolean assisted() {
		return steeredAt >= 0 && c.player != null && c.player.level().getGameTime() - steeredAt < 20 * 60;
	}

	/** Steered it since then? */
	boolean assistedSince(long t) {
		return steeredAt >= t && steeredAt >= 0;
	}

	private boolean lucky() {
		return c.mod.config.luck > 0 && !c.minion && c.random().nextFloat() < (float) c.mod.config.luck;
	}

	/** What it needs now and how much: what its feet would take it toward. */
	private Map<String, Float> wants() {
		Map<String, Float> w = new HashMap<>();
		var items = c.items();
		int tier = c.crafter.pickTier();
		int iron = items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0);
		boolean nether = c.player.level().dimension() == net.minecraft.world.level.Level.NETHER;
		if (nether) {
			if (tier >= 3) w.put("debris", 0.8f);
			w.put("fortress", 1f);
			w.put("bastion_remnant", 0.4f);
			return w;
		}
		int meals = items.getOrDefault("food", 0), hunger = c.player.getFoodData().getFoodLevel();
		if (meals < 3) w.put("animals", hunger < 12 ? 1.8f : 1.3f);                 // (food walking about: most of all when it's hungry)
		else if (meals < 6) w.put("animals", 0.5f);
		if (items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0) < 8) w.put("coal", 0.5f);
		if (tier >= 1 && iron < 8 && c.crafter.pickTier() < 4) w.put("iron", 1f);
		if (tier >= 3 && items.getOrDefault("diamond", 0) < 5) w.put("diamond", 1.2f);
		if (tier >= 1) w.put("cave", 0.6f);
		if (c.places.find("village") == null) w.put("village", c.goals.home == null ? 0.9f : 0.4f);
		return w;
	}

	/**
	 * Which way its feet take it, exploring (radians, like Goals' heading), or NaN: no luck this time. What it needs,
	 * near enough to be worth it and not right under its nose (it sees that itself), what its friends found and passed
	 * on, a friend out exploring too; roughly that way, never straight at it.
	 */
	double exploreWay() {
		if (c.player == null || !lucky()) return Double.NaN;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		long now = level.getGameTime();
		Map<String, Float> want = wants();
		BlockPos best = null;
		String bestKind = null;
		double bestScore = 0;
		Map<Long, Chunk> known = WORLD.get(dim(level));
		if (known != null) for (Chunk ch : known.values())
			for (Find f : ch.finds()) {
				Float w = want.get(f.kind());
				if (w == null) continue;
				boolean ore = !f.kind().equals("cave") && !f.kind().equals("village") && !f.kind().contains("_") && !f.kind().equals("fortress");
				if (ore && !f.open()) continue;                                 // (ore it could see: out in the air, a cave wall)
				if (f.kind().equals("cave") && (f.at().getY() < feet.getY() - 40 || claimedByOther(f.at()))) continue;
				double d = Math.sqrt(f.at().distSqr(feet));
				if (d < 20 || d > 150 || reached.contains(f.at().asLong() >> 3) || !ore && knows(f.at(), 32)) continue;
				double score = w * Math.min(3, Math.sqrt(f.amount())) / (1 + d / 48);
				if (score > bestScore) {
					bestScore = score;
					best = f.at();
					bestKind = f.kind();
				}
			}
		Float hungry = want.get("animals");                                     // food walking about (the server knows where every herd is)
		if (hungry != null) {
			BlockPos herd = herd(20, 140);
			if (herd != null) {
				double score = hungry * 2.0 / (1 + Math.sqrt(herd.distSqr(feet)) / 48);
				if (score > bestScore) {
					bestScore = score;
					best = herd;
					bestKind = "animals";
				}
			}
		}
		for (Word word : WORD) {                                                // what its friends found
			if (word.from() == c || !word.dim().equals(dim(level)) || !friends(c, word.from())) continue;
			double d = Math.sqrt(word.at().distSqr(feet));
			if (d < 20 || d > 200 || reached.contains(word.at().asLong() >> 3) || knows(word.at(), 24)) continue;
			double score = 0.8 / (1 + d / 64);
			if (score > bestScore) {
				bestScore = score;
				best = word.at();
				bestKind = "a friend's " + word.kind();
			}
		}
		Companion friend = meetup(now);
		if (friend != null && 0.7 / (1 + Math.sqrt(friend.player.blockPosition().distSqr(feet)) / 64) > bestScore) {
			best = friend.player.blockPosition();
			bestKind = "running into " + friend.name;
		}
		if (best == null) return Double.NaN;
		if (toward == null || !toward.equals(best)) {
			toward = best;
			times++;
			XenMod.LOG.info("{}'s luck: {}", c.name, bestKind);
		}
		steered = bestKind;
		steeredAt = now;
		return Math.atan2(best.getZ() - feet.getZ(), best.getX() - feet.getX()) + c.random().nextGaussian() * 0.3;
	}

	/** A place it knows already (it saw it) about there. */
	private boolean knows(BlockPos at, int within) {
		String here = c.places.here();
		for (Places.Place p : c.places.all()) if (p.dim().equals(here) && p.pos().distSqr(at) < (long) within * within) return true;
		for (BlockPos k : c.caves.known) if (k.distSqr(at) < (long) within * within) return true;
		return false;
	}

	/** The best herd of animals people eat (most of them, nearest), between near and far blocks off: about where, or null. */
	private BlockPos herd(int near, int far) {
		var p = c.player;
		Map<Long, int[]> cells = new HashMap<>();
		for (var a : p.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(far, 40, far),
				x -> x.isAlive() && !x.isBaby() && Chores.food(x))) {
			double d = a.distanceTo(p);
			if (d < near || d > far) continue;
			long cell = ((long) (a.getBlockX() >> 4) << 32) ^ ((a.getBlockZ() >> 4) & 0xffffffffL);
			int[] k = cells.computeIfAbsent(cell, q -> new int[] {0, a.getBlockX(), a.getBlockY(), a.getBlockZ()});
			k[0]++;
		}
		BlockPos best = null;
		double bestScore = 0;
		for (int[] k : cells.values()) {
			BlockPos at = new BlockPos(k[1], k[2], k[3]);
			double score = Math.min(4, k[0]) / (1 + Math.sqrt(at.distSqr(p.blockPosition())) / 48);
			if (score > bestScore) {
				bestScore = score;
				best = at;
			}
		}
		return best;
	}

	/**
	 * Hunting, and none in sight: where the animals are, exactly (luck: the server knows), the best herd within 128
	 * blocks (sheep, if it's after wool), or null (luck off, none about). Always when it needs food: it goes straight there.
	 */
	BlockPos animalsAt(boolean sheep) {
		if (c.player == null || c.mod.config.luck <= 0 || c.minion) return null;
		var p = c.player;
		BlockPos best = null;
		double bestScore = 0;
		Map<Long, int[]> cells = new HashMap<>();
		for (var a : p.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, p.getBoundingBox().inflate(128, 40, 128),
				x -> x.isAlive() && !x.isBaby() && Chores.food(x) && (!sheep || x instanceof net.minecraft.world.entity.animal.sheep.Sheep))) {
			long cell = ((long) (a.getBlockX() >> 4) << 32) ^ ((a.getBlockZ() >> 4) & 0xffffffffL);
			int[] k = cells.computeIfAbsent(cell, q -> new int[] {0, a.getBlockX(), a.getBlockY(), a.getBlockZ()});
			k[0]++;
		}
		for (int[] k : cells.values()) {
			BlockPos at = new BlockPos(k[1], k[2], k[3]);
			double score = Math.min(4, k[0]) / (1 + Math.sqrt(at.distSqr(p.blockPosition())) / 32);   // (more of them, and nearer)
			if (score > bestScore) {
				bestScore = score;
				best = at;
			}
		}
		if (best == null) return null;
		if (toward == null || toward.distSqr(best) > 24 * 24) {
			times++;
			XenMod.LOG.info("{}'s luck: {} at {}", c.name, sheep ? "sheep" : "animals", best.toShortString());
		}
		toward = best;
		steered = sheep ? "sheep (where they are)" : "animals (where they are)";
		steeredAt = p.level().getGameTime();
		return best;
	}

	/** Hunting and no animals in sight: which way it happens to wander to look for some (radians), or NaN: no luck this time. */
	double animalWay() {
		if (c.player == null || !lucky()) return Double.NaN;
		BlockPos herd = herd(16, 120);
		if (herd == null) return Double.NaN;
		BlockPos feet = c.player.blockPosition();
		if (toward == null || toward.distSqr(herd) > 24 * 24) {
			times++;
			XenMod.LOG.info("{}'s luck: animals (hunting)", c.name);
		}
		toward = herd;
		steered = "animals (hunting)";
		steeredAt = c.player.level().getGameTime();
		return Math.atan2(herd.getZ() - feet.getZ(), herd.getX() - feet.getX()) + c.random().nextGaussian() * 0.3;
	}

	/** Where it was going (luck) and it's there now, or saw it: not again. Every second. */
	void second() {
		if (toward != null && c.player != null && c.player.blockPosition().closerThan(toward, 12)) {
			reached.add(toward.asLong() >> 3);
			toward = null;
		}
		if (reached.size() > 512) reached.clear();
	}

	/**
	 * Down where it'll run its tunnels: which way luck takes them (toward ore of the kind it's after, a few dozen blocks
	 * off at about this depth), or null: no luck, or none that way.
	 */
	net.minecraft.core.Direction tunnelWay(BlockPos base, String what) {
		if (c.player == null || !lucky()) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		Map<Long, Chunk> known = WORLD.get(dim(level));
		if (known == null) return null;
		String kind = switch (what) {
			case "diamonds" -> "diamond";
			case "ancient debris", "debris" -> "debris";
			default -> what;
		};
		BlockPos best = null;
		double bestScore = 0;
		for (Chunk ch : known.values())
			for (Find f : ch.finds()) {
				if (!f.kind().equals(kind) || Math.abs(f.at().getY() - base.getY()) > 5) continue;
				double d = Math.sqrt(f.at().distSqr(base));
				if (d < 4 || d > 28) continue;
				double score = f.amount() / (1 + d / 12);
				if (score > bestScore) {
					bestScore = score;
					best = f.at();
				}
			}
		if (best == null) return null;
		int dx = best.getX() - base.getX(), dz = best.getZ() - base.getZ();
		var way = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
				: (dz > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
		steered = kind + " (tunnels)";
		steeredAt = level.getGameTime();
		times++;
		XenMod.LOG.info("{}'s luck: its tunnels go {} ({})", c.name, way.getName(), kind);
		return way;
	}

	/**
	 * Ore of this kind close by (luck: the server knows the world), for the guide: the few cheapest to dig to (within
	 * flat blocks round, deep down or a few up) that are still there and dry, cheapest first. Empty with luck off.
	 */
	List<BlockPos> oresNear(String what, BlockPos from, int flat, int deep, int max) {
		List<BlockPos> out = new ArrayList<>();
		if (c.player == null || c.mod.config.luck <= 0 || c.minion) return out;
		ServerLevel level = (ServerLevel) c.player.level();
		Map<Long, Chunk> known = WORLD.get(dim(level));
		if (known == null) return out;
		String kind = what.equals("diamonds") ? "diamond" : what;
		List<double[]> found = new ArrayList<>();
		for (Chunk ch : known.values())
			for (Find f : ch.finds()) {
				if (!f.kind().equals(kind) || !f.open()) continue;               // (only ore showing to the air, a cave wall or a tunnel's: ore in solid rock it acts as if it can't know of)
				BlockPos at = f.at();
				int dx = Math.abs(at.getX() - from.getX()), dz = Math.abs(at.getZ() - from.getZ()), dy = from.getY() - at.getY();
				if (dx > flat || dz > flat || dy > deep || dy < -6 || taken.contains(at.asLong())) continue;
				double cost = Math.sqrt((double) dx * dx + (double) dz * dz) + 1.2 * Math.max(0, dy);
				found.add(new double[] {cost, at.getX(), at.getY(), at.getZ()});
			}
		found.sort((a, b) -> Double.compare(a[0], b[0]));
		for (int i = 0; i < found.size() && out.size() < max && i < 4 * max; i++) {
			BlockPos at = new BlockPos((int) found.get(i)[1], (int) found.get(i)[2], (int) found.get(i)[3]);
			BlockPos r = stillThere(level, at, kind);
			if (r != null && dry(level, r)) out.add(r);
			else taken.add(at.asLong());
		}
		return out;
	}

	/**
	 * A cave close by with a way in from the surface (luck: the server knows): where on the ground over it to go and
	 * look (it finds the mouth itself, by sight), or null. Not one another Xen is mining, not where it's been.
	 */
	BlockPos caveNear(BlockPos from, int within) {
		lastCave = "";
		lastCaveWorth = 0;
		if (c.player == null || c.mod.config.luck <= 0 || c.minion) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		Map<Long, Chunk> known = WORLD.get(dim(level));
		if (known == null) return null;
		List<Find> caves = new ArrayList<>(), iron = new ArrayList<>();
		for (Chunk ch : known.values())
			for (Find f : ch.finds()) {
				int dx = Math.abs(f.at().getX() - from.getX()), dz = Math.abs(f.at().getZ() - from.getZ());
				if (dx > within + 24 || dz > within + 24) continue;
				if (f.kind().equals("cave")) caves.add(f);
				else if (f.open() && f.kind().equals("iron")) iron.add(f);
			}
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		int bestSize = 0, bestIron = 0;
		for (Find f : caves) {                                                 // big and near first: a big cave is the quickest iron there is
			if (taken.contains(f.at().asLong()) || claimedBy(c, f.at())) continue;
			double d = Math.sqrt(f.at().distSqr(new BlockPos(from.getX(), f.at().getY(), from.getZ())));
			if (d > within || d < 8) continue;
			BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, f.at());
			if (!level.getFluidState(top.below()).isEmpty() || top.getY() - f.at().getY() > 40) continue;   // (under the sea, or too deep under the ground to show)
			int size = 0, ores = 0;
			for (Find g : caves) if (g.at().distSqr(f.at()) <= 24 * 24) size += g.amount();   // (the air joined up round it)
			for (Find g : iron) if (g.at().distSqr(f.at()) <= 24 * 24) ores++;
			double score = d / Math.sqrt(Math.max(1, size + 250.0 * ores));
			if (score < bestScore) {
				bestScore = score;
				best = top;
				bestSize = size;
				bestIron = ores;
			}
		}
		if (best != null) {
			taken.add(best.asLong());
			lastCaveWorth = bestSize + 250 * bestIron;
			lastCave = (lastCaveWorth >= 1500 ? "a big cave" : "a cave") + String.format(java.util.Locale.ROOT, " (about %d blocks of air, %d iron showing)", bestSize, bestIron);
			steered = lastCave + " (a feeling where it is)";
			steeredAt = level.getGameTime();
			times++;
		}
		return best;
	}

	/** What the last cave feeling was about (for its journal), and how much it looked worth (1500 and up: a big one). */
	String lastCave = "";
	int lastCaveWorth;

	/** Ore it already went for (mined, or no way to it): not again. */
	private final Set<Long> taken = new java.util.HashSet<>();

	void taken(BlockPos at) {
		taken.add(at.asLong());
		if (taken.size() > 4096) taken.clear();
	}

	/** No water by it (two blocks round) and none over the ground above it (not under the sea, a lake or a river). */
	private static boolean dry(ServerLevel level, BlockPos at) {
		if (!level.isLoaded(at)) return true;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 2, 2)))
			if (!level.getFluidState(q).isEmpty()) return false;
		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()),
				floor = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, at.getX(), at.getZ());
		return top - floor < 1;
	}

	/** That block, or another of its kind in its 8-block cell, still ore (not mined since); null if none. */
	private static BlockPos stillThere(ServerLevel level, BlockPos at, String kind) {
		if (!level.isLoaded(at)) return at;
		if (kind.equals(ORES.get(level.getBlockState(at).getBlock()))) return at;
		int x0 = at.getX() & ~7, y0 = at.getY() & ~7, z0 = at.getZ() & ~7;
		for (BlockPos q : BlockPos.betweenClosed(x0, y0, z0, x0 + 7, y0 + 7, z0 + 7))
			if (kind.equals(ORES.get(level.getBlockState(q).getBlock()))) return q.immutable();
		return null;
	}

	// ------------------------------------------------------------------------- the grapevine
	private static boolean friends(Companion a, Companion b) {
		if (a == b || a.player == null || b.player == null || b.minion) return false;
		Tribe t = a.tribe();
		if (t != null && t.members.contains(b)) return true;
		return a.trust(b.player.getUUID()) >= 0.3f && b.trust(a.player.getUUID()) >= 0.3f;
	}

	private static final Set<String> OWN = Set.of("home", "mine", "mine base", "mine hut", "bed", "base", "farm", "pen", "chest", "death", "spawn");

	/** It found a place (it saw it): its friends hear of it, without a word. */
	static void found(Companion c, String name, BlockPos at) {
		if (c.mod.config.luck <= 0 || c.minion || c.player == null) return;
		String kind = name.replaceAll(" \\d+$", "");
		if (OWN.contains(kind) || kind.startsWith("mine")) return;
		long now = c.player.level().getGameTime();
		String dim = dim((ServerLevel) c.player.level());
		for (Word w : WORD) if (w.dim().equals(dim) && w.at().distSqr(at) < 32 * 32) return;
		WORD.add(new Word(c, kind, dim, at.immutable(), now));
	}

	/** It's mining in this cave now: the others find their own. */
	static void claim(Companion c, BlockPos cave) {
		if (c.mod.config.luck <= 0 || cave == null || c.player == null) return;
		CLAIMS.put(cave.asLong(), new Claim(c, c.player.level().getGameTime() + 20 * 60 * 10));
	}

	/** Another Xen is mining a cave there. */
	private static boolean claimedByOther(BlockPos at) {
		return claimedBy(null, at);
	}

	/** Someone other than {@code c} is mining a cave right about there (luck: it happens to pick another). */
	static boolean claimedBy(Companion c, BlockPos at) {
		for (var e : CLAIMS.entrySet()) {
			if (e.getValue().by() == c) continue;
			if (BlockPos.of(e.getKey()).distSqr(at) < 24 * 24) return true;
		}
		return false;
	}

	/** A friend also out exploring, far enough off that it'd be a surprise, whom it hasn't run into in a while. */
	private Companion meetup(long now) {
		for (Companion o : c.mod.companions) {
			if (!friends(c, o) || o.player.level() != c.player.level() || o.goals.current != Goals.Short.EXPLORE) continue;
			double d = o.player.distanceTo(c.player);
			if (d < 40 || d > 220) continue;
			String pair = c.name.compareTo(o.name) < 0 ? c.name + "|" + o.name : o.name + "|" + c.name;
			Long last = MET.get(pair);
			if (last != null && now - last < 20 * 60 * 20) continue;
			if (d < 16) MET.put(pair, now);
			return o;
		}
		return null;
	}

	/** Two friends close together: they met (and won't be steered together again for a while). */
	void met() {
		if (c.player == null) return;
		long now = c.player.level().getGameTime();
		for (Companion o : c.mod.companions) {
			if (o == c || o.player == null || o.player.level() != c.player.level() || o.player.distanceTo(c.player) > 12) continue;
			String pair = c.name.compareTo(o.name) < 0 ? c.name + "|" + o.name : o.name + "|" + c.name;
			MET.put(pair, now);
		}
	}

	/** For /xen status. */
	String describe() {
		return times == 0 ? "" : String.format(Locale.ROOT, "lucky %d time%s", times, times == 1 ? "" : "s");
	}

	/** For the tests: how much the server has looked at, in this world. */
	static String stats(ServerLevel level) {
		Map<Long, Chunk> known = WORLD.get(dim(level));
		if (known == null) return "nothing yet";
		Map<String, Integer> n = new HashMap<>();
		for (Chunk ch : known.values()) for (Find f : ch.finds()) n.merge(f.kind(), 1, Integer::sum);
		return known.size() + " chunks: " + n;
	}

	static void clear() {
		WORLD.clear();
		WORD.clear();
		CLAIMS.clear();
		MET.clear();
	}
}
