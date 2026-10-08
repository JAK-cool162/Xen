package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowing what it's looking at: a cave entrance, the inside of a cave, a ravine, a river, a frozen river (or just
 * ground), learned from what it was shown with the Build Axe for seeing, not building (scripts/learn_sights.py:
 * assets/xen/taught/sights.json). Now and then it looks where it's looking: the 9 x 9 columns round the spot its eyes
 * fall on, each from the top down: what the ground is on top (water, ice or snow, sand or gravel, bare rock), the air
 * under it, the tallest hollow, how steep it drops from column to column and how far the ground's height ranges. The
 * nearest of what it learned names it, if it's near enough and what makes that kind is there (water for a river...).
 * It remembers the place (Places: "the ravine", "the river 2") and, now and then, says so.
 */
final class Sights {
	private Sights() {}

	private record Kind(String kind, double[] centre, double reach) {}

	private static double[] mean, std;
	private static final List<Kind> KINDS = new ArrayList<>();
	private static boolean read;

	private static synchronized void read() {
		if (read) return;
		read = true;
		try (InputStream in = Sights.class.getResourceAsStream("/assets/xen/taught/sights.json")) {
			if (in == null) return;
			JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			mean = nums(o.getAsJsonArray("mean"));
			std = nums(o.getAsJsonArray("std"));
			for (var e : o.getAsJsonArray("kinds")) {
				JsonObject k = e.getAsJsonObject();
				KINDS.add(new Kind(k.get("kind").getAsString(), nums(k.getAsJsonArray("centre")), k.get("reach").getAsDouble()));
			}
			XenMod.LOG.info("Xen knows {} kinds of sights from what it was shown ({} right when it learned)", KINDS.size(), o.get("correct").getAsDouble());
		} catch (Exception e) {
			XenMod.LOG.warn("Xen: couldn't read what it learned to see: {}", e.toString());
		}
	}

	private static double[] nums(JsonArray a) {
		double[] v = new double[a.size()];
		for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
		return v;
	}

	private static final Set<String> PLANT = Set.of("short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "leaf_litter", "vine",
			"glow_lichen", "moss_carpet", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sugar_cane", "lily_pad", "dandelion", "poppy",
			"torch", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass", "hanging_roots", "pointed_dripstone", "spore_blossom", "sweet_berry_bush");
	private static final String[] ROCK = {"stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "dripstone_block", "cobblestone",
			"sandstone", "_ore", "basalt", "blackstone", "obsidian", "bedrock", "smooth_basalt", "terracotta"};

	private static boolean skip(String n) {
		return n.equals("air") || n.equals("cave_air") || n.equals("void_air") || PLANT.contains(n) || n.endsWith("_leaves") || n.endsWith("_log")
				|| n.endsWith("_flower") || n.endsWith("_tulip") || n.endsWith("_sapling") || n.endsWith("_mushroom");
	}

	private static String name(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
	}

	/** The 8 numbers for the 9 x 9 columns round x, z, each from top down to bottom (as learn_sights.py measures them). */
	static double[] measure(ServerLevel level, int cx, int cz, int top, int bottom) {
		Integer[] h = new Integer[81];
		int got = 0, water = 0, ice = 0, sand = 0, rock = 0;
		double hollow = 0, tallest = 0;
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
			Integer first = null;
			String what = null;
			int under = 0, run = 0, best = 0;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(cx + dx, top, cz + dz);
			if (!level.isLoaded(m)) continue;
			for (int y = top; y >= bottom; y--) {
				m.setY(y);
				String n = name(level.getBlockState(m));
				if (first == null) {
					if (skip(n)) continue;
					first = y;
					what = n;
					continue;
				}
				if (n.equals("air") || n.equals("cave_air")) {
					under++;
					run++;
					best = Math.max(best, run);
				} else {
					run = 0;
				}
			}
			h[(dx + 4) * 9 + dz + 4] = first;
			if (first == null) continue;
			got++;
			if (what.equals("water") || what.equals("bubble_column")) water++;
			if (what.contains("ice") || what.equals("snow") || what.equals("snow_block") || what.equals("powder_snow")) ice++;
			if (what.equals("sand") || what.equals("red_sand") || what.equals("gravel") || what.equals("clay") || what.equals("suspicious_sand")) sand++;
			for (String r : ROCK) if (what.contains(r)) {
				rock++;
				break;
			}
			hollow += Math.min(under, 10);
			tallest += Math.min(best, 10);
		}
		if (got < 20) return null;
		double steps = 0;
		int n = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int i = 0; i < 9; i++) for (int j = 0; j < 9; j++) {
			Integer a = h[i * 9 + j];
			if (a == null) continue;
			lo = Math.min(lo, a);
			hi = Math.max(hi, a);
			if (i < 8 && h[(i + 1) * 9 + j] != null) {
				steps += Math.abs(a - h[(i + 1) * 9 + j]);
				n++;
			}
			if (j < 8 && h[i * 9 + j + 1] != null) {
				steps += Math.abs(a - h[i * 9 + j + 1]);
				n++;
			}
		}
		double steep = n == 0 ? 0 : Math.min(1, steps / n / 5);
		return new double[] {water / (double) got, ice / (double) got, sand / (double) got, rock / (double) got, hollow / got / 10, tallest / got / 10, steep,
				Math.min(1, (hi - lo) / 16.0)};
	}

	/** What that is (a kind it learned), or null: just ground, or nothing it knows well enough. */
	static String name(double[] f) {
		read();
		if (f == null || KINDS.isEmpty()) return null;
		double[] z = new double[f.length];
		for (int i = 0; i < f.length; i++) z[i] = (f[i] - mean[i]) / std[i];
		Kind best = null;
		double bestD = Double.MAX_VALUE;
		for (Kind k : KINDS) {
			double d = 0;
			for (int i = 0; i < z.length; i++) d += (z[i] - k.centre()[i]) * (z[i] - k.centre()[i]);
			d = Math.sqrt(d);
			if (d < bestD) {
				bestD = d;
				best = k;
			}
		}
		if (best == null || best.kind().equals("ground") || bestD > best.reach()) return null;
		boolean shows = switch (best.kind()) {                                     // (what makes it that kind has to be there)
			case "cave" -> f[4] > 0.25;
			case "cave entrance" -> f[4] > 0.08;
			case "ravine" -> f[6] > 0.25 || f[7] > 0.5;
			case "river" -> f[0] > 0.25;
			case "frozen river" -> f[1] > 0.25;
			default -> true;
		};
		return shows ? best.kind() : null;
	}

	/** A river runs on (a pond or a pool doesn't): water or ice 12 blocks out from there, one way and the other. */
	static boolean runsOn(ServerLevel level, BlockPos at) {
		int[][] ways = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
		for (int[] w : ways) {
			if (wet(level, at, w[0], w[1]) && wet(level, at, -w[0], -w[1])) return true;
		}
		return false;
	}

	private static boolean wet(ServerLevel level, BlockPos at, int dx, int dz) {
		for (int i = 4; i <= 12; i += 4) {
			BlockPos q = at.offset(dx * i, 0, dz * i);
			if (!level.isLoaded(q)) return false;
			boolean any = false;
			for (int dy = 2; dy >= -2 && !any; dy--) {
				BlockState s = level.getBlockState(q.above(dy));
				String n = name(s);
				any = s.getFluidState().is(net.minecraft.tags.FluidTags.WATER) || n.contains("ice");
			}
			if (!any) return false;
		}
		return true;
	}

	/** When each Xen last looked (every few seconds, not every tick). */
	private static final Map<Companion, Long> lookedAt = new HashMap<>();

	/** A look where it's looking: what is that? A new one it remembers (and now and then says). */
	static void look(Companion c) {
		if (c.player == null || c.fightingNow() || c.builder.busy()) return;        // (building: eyes on the build)
		ServerLevel level = (ServerLevel) c.player.level();
		long now = level.getGameTime();
		if (now - lookedAt.getOrDefault(c, -1000L) < 160) return;
		lookedAt.put(c, now);
		Vec3 eye = c.player.getEyePosition(), end = eye.add(c.player.getLookAngle().scale(56));
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.ANY, c.player));
		if (hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos at = hit.getBlockPos();
		if (at.distSqr(c.player.blockPosition()) < 6 * 6) return;                // (right in front of it: not a view)
		String kind = name(measure(level, at.getX(), at.getZ(), at.getY() + 16, at.getY() - 24));
		if (kind == null || kind.endsWith("river") && !runsOn(level, at)) return;
		if (!c.places.rememberAnother(kind, at, 48)) return;                     // (one it knows already)
		c.journal("sees", (kind.equals("cave") ? "the inside of a cave" : "a " + kind) + " at " + at.toShortString() + " (it knows what that is: it was shown)");
		XenMod.LOG.info("{} sees a {} at {}", c.name, kind, at.toShortString());
		if (java.util.concurrent.ThreadLocalRandom.current().nextFloat() < 0.35f) c.chatter(switch (kind) {
			case "ravine" -> c.pick3("Whoa, a ravine over there.", "Look, a ravine. Careful near the edge.", "That's a big ravine.");
			case "river" -> c.pick3("A river over there.", "There's a river. Good for water.", "Ooh, a river.");
			case "frozen river" -> c.pick3("A frozen river. Slippery.", "That river's all ice.", "Frozen river over there.");
			case "cave entrance" -> c.pick3("That looks like a cave entrance.", "A way into a cave, there.", "Cave entrance over there.");
			default -> c.pick3("Big cave in here.", "This is a proper cave.", "Now that's a cave.");
		}, false);
	}
}
