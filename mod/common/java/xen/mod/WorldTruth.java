package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Random;

/**
 * The world as it really is, for teaching and testing Xens (a dev tool: /xen terrain). Minecraft's own world
 * generator is the simulator: generate land anywhere, and every spot can be labelled from all its blocks (which no Xen
 * ever sees): the inside of a cave (a big air space under the ground), a cave entrance (open to the sky and on down
 * into one), a ravine (the same, deep and narrow), a river or a frozen river (the biome, with water or ice on top), else
 * ground.
 * <ul>
 *   <li>{@code /xen terrain sample <n> <radius>}: n spots round where you are (on the land, or on a cave floor under it),
 *   each with the 8 numbers a Xen measures (Sights.measure) and what it really is, to {@code xen-terrain/<seed>.jsonl}
 *   (scripts/learn_sights.py learns from them).</li>
 *   <li>{@code /xen terrain caves <radius>}: cave entrances with a deep point reachable through the air, to
 *   {@code xen-terrain/caves-<seed>.jsonl} (a benchmark: send a Xen down).</li>
 * </ul>
 * Only loaded land counts (forceload it first). The flood is capped (4,000 blocks, 32 out), so it's quick.
 */
final class WorldTruth {
	private WorldTruth() {}

	/** What a flood of the air next to a spot found. */
	record Flood(int cells, int under, boolean sky, int deepest, BlockPos deepAt, int spanX, int spanZ) {}

	private static int surface(ServerLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	private static boolean air(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).isAir();
	}

	/** The air joined to the air next to the spot (32 out, 4,000 blocks at most). */
	static Flood flood(ServerLevel level, BlockPos spot) {
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (Direction d : Direction.values()) {
			BlockPos n = spot.relative(d);
			if (air(level, n) && seen.add(n.asLong())) q.add(n);
		}
		int cells = 0, under = 0, deepest = 0;
		boolean sky = false;
		BlockPos deepAt = null;
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		while (!q.isEmpty() && cells < 4000) {
			BlockPos p = q.poll();
			cells++;
			int h = surface(level, p.getX(), p.getZ());
			int depth = h - p.getY();
			if (depth <= 0) sky = true;
			else if (depth > 2) under++;
			if (depth > deepest && !air(level, p.below()) == true) {          // (a floor to stand on, that deep)
				deepest = depth;
				deepAt = p;
			}
			if (depth >= 6) {
				minX = Math.min(minX, p.getX());
				maxX = Math.max(maxX, p.getX());
				minZ = Math.min(minZ, p.getZ());
				maxZ = Math.max(maxZ, p.getZ());
			}
			if (depth <= -3) continue;                                         // (out in the open air: far enough)
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (Math.abs(n.getX() - spot.getX()) > 32 || Math.abs(n.getZ() - spot.getZ()) > 32 || Math.abs(n.getY() - spot.getY()) > 40) continue;
				if (!level.isLoaded(n) || !seen.add(n.asLong()) || !air(level, n)) continue;
				q.add(n);
			}
		}
		return new Flood(cells, under, sky, deepest, deepAt, minX > maxX ? 0 : maxX - minX + 1, minZ > maxZ ? 0 : maxZ - minZ + 1);
	}

	/** What the spot (a block a Xen's eyes fall on) really is. */
	static String label(ServerLevel level, BlockPos spot) {
		int h = surface(level, spot.getX(), spot.getZ());
		var biome = level.getBiome(spot).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		String top = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(spot.getX(), h - 1, spot.getZ())).getBlock()).getPath();
		if (spot.getY() >= h - 3) {
			if (biome.equals("frozen_river") && (top.contains("ice") || top.equals("water"))) return "frozen river";
			if (biome.equals("river") && top.equals("water")) return "river";
		}
		Flood f = flood(level, spot);
		if (spot.getY() < h - 5 && f.under() >= 150) return "cave";
		if (f.sky() && f.under() >= 60 && f.deepest() >= 6) {
			int narrow = Math.min(f.spanX(), f.spanZ()), longer = Math.max(f.spanX(), f.spanZ());
			return f.deepest() >= 15 && narrow <= 9 && longer >= 16 ? "ravine" : "cave entrance";
		}
		return "ground";
	}

	/** n spots round (x, z) out to radius: 6 in 10 on the land, the rest on the floor of a cave under it if there is one. */
	static String sample(ServerLevel level, BlockPos around, int n, int radius) throws IOException {
		Random r = new Random();
		Path out = Path.of("xen-terrain", level.getSeed() + ".jsonl");
		Files.createDirectories(out.getParent());
		StringBuilder sb = new StringBuilder();
		java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
		int made = 0;
		for (int tries = 0; made < n && tries < n * 20; tries++) {
			int x = around.getX() + r.nextInt(2 * radius + 1) - radius, z = around.getZ() + r.nextInt(2 * radius + 1) - radius;
			if (!level.isLoaded(new BlockPos(x, 0, z))) continue;
			int h = surface(level, x, z);
			BlockPos spot;
			if (r.nextFloat() < 0.6f) {
				spot = new BlockPos(x, h - 1, z);
			} else {                                                           // a cave floor under it, if any
				spot = null;
				int y = level.getMinY() + 8 + r.nextInt(Math.max(1, h - 8 - level.getMinY() - 8));
				for (int k = 0; k < 24 && y - k > level.getMinY() + 2; k++) {
					BlockPos p = new BlockPos(x, y - k, z);
					if (air(level, p) && !air(level, p.below()) && !level.getBlockState(p.below()).liquid()) {
						spot = p.below();
						break;
					}
				}
				if (spot == null) continue;
			}
			double[] f = Sights.measure(level, spot.getX(), spot.getZ(), spot.getY() + 16, spot.getY() - 24);
			if (f == null) continue;
			String kind = label(level, spot);
			JsonObject o = new JsonObject();
			o.addProperty("kind", kind);
			JsonArray a = new JsonArray();
			for (double v : f) a.add(Math.round(v * 10000) / 10000.0);
			o.add("features", a);
			o.addProperty("x", spot.getX());
			o.addProperty("y", spot.getY());
			o.addProperty("z", spot.getZ());
			o.addProperty("biome", level.getBiome(spot).unwrapKey().map(k -> k.identifier().getPath()).orElse(""));
			sb.append(o).append('\n');
			kinds.merge(kind, 1, Integer::sum);
			made++;
		}
		Files.writeString(out, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return made + " spots to " + out + ": " + kinds;
	}

	/** Cave entrances round (x, z) with a deep point reachable through the air (20 or more under the ground). */
	static String caves(ServerLevel level, BlockPos around, int radius) throws IOException {
		Path out = Path.of("xen-terrain", "caves-" + level.getSeed() + ".jsonl");
		Files.createDirectories(out.getParent());
		StringBuilder sb = new StringBuilder();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet near = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		int found = 0;
		for (int x = around.getX() - radius; x <= around.getX() + radius; x += 3) {
			for (int z = around.getZ() - radius; z <= around.getZ() + radius; z += 3) {
				if (!level.isLoaded(new BlockPos(x, 0, z)) || near.contains(((long) (x >> 5) << 32) ^ (z >> 5))) continue;
				int h = surface(level, x, z);
				BlockPos top = new BlockPos(x, h, z);
				if (!air(level, top.below(2)) && !air(level, top.below(3))) continue;   // (the ground opens here)
				Flood f = flood(level, new BlockPos(x, h, z));
				if (!f.sky() || f.deepAt() == null || f.deepest() < 20 || f.under() < 200) continue;
				near.add(((long) (x >> 5) << 32) ^ (z >> 5));                    // (one a 32 x 32 area)
				JsonObject o = new JsonObject();
				o.addProperty("x", x);
				o.addProperty("y", h);
				o.addProperty("z", z);
				o.addProperty("deep_x", f.deepAt().getX());
				o.addProperty("deep_y", f.deepAt().getY());
				o.addProperty("deep_z", f.deepAt().getZ());
				o.addProperty("depth", f.deepest());
				o.addProperty("air", f.cells());
				sb.append(o).append('\n');
				found++;
			}
		}
		Files.writeString(out, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return found + " caves to " + out;
	}
}
