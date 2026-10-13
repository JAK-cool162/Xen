package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Biomes, the way a player reads the land: a desert means sand, a swamp clay, badlands gold near the top, the
 * mountains emeralds and iron, lush or dripstone caves (or the deep dark) a cave right here. Its eyes' sight lines tell
 * it the biome where they end (as a player sees the grass colour, the sand, the snow), and it remembers where it saw
 * each one, the nearest spot. That's know-how too: a plan that wants sand it hasn't seen goes to the desert it saw,
 * and in a cave biome it knows it's in a cave before it has looked round the dark.
 * <p>Hints, not rules: what each biome tends to have. The world's own biomes (any version, any data pack); the hints
 * only know the vanilla names, and a biome they don't know is just remembered.
 */
final class Biomes {
	/** What a biome tends to have (items its plans may want there). */
	private static final Map<String, String[]> HAS = new HashMap<>();

	private static void has(String[] biomes, String... items) {
		for (String b : biomes) HAS.put(b, items);
	}

	static {
		has(new String[] {"desert"}, "sand", "cactus");
		has(new String[] {"beach", "snowy_beach"}, "sand", "gravel");
		has(new String[] {"river", "frozen_river"}, "sand", "clay_ball", "gravel", "sugar_cane");
		has(new String[] {"swamp"}, "clay_ball", "slime_ball");
		has(new String[] {"mangrove_swamp"}, "clay_ball", "mud");
		has(new String[] {"badlands", "eroded_badlands", "wooded_badlands"}, "raw_gold", "red_sand", "terracotta");
		has(new String[] {"jagged_peaks", "stony_peaks", "frozen_peaks", "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "meadow", "grove",
				"snowy_slopes", "cherry_grove"}, "emerald", "raw_iron");
		has(new String[] {"stony_shore"}, "gravel", "cobblestone");
		has(new String[] {"ocean", "deep_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "cold_ocean", "deep_cold_ocean", "warm_ocean", "frozen_ocean",
				"deep_frozen_ocean"}, "gravel", "sand", "clay_ball");
		has(new String[] {"dripstone_caves"}, "raw_copper", "pointed_dripstone");
		has(new String[] {"lush_caves"}, "clay_ball", "moss_block");
		has(new String[] {"jungle", "sparse_jungle", "bamboo_jungle"}, "log", "melon_slice", "cocoa_beans");
		has(new String[] {"forest", "birch_forest", "old_growth_birch_forest", "dark_forest", "flower_forest", "taiga", "old_growth_pine_taiga",
				"old_growth_spruce_taiga", "snowy_taiga", "savanna", "savanna_plateau", "windswept_savanna", "plains", "sunflower_plains"}, "log");
	}

	/** Cave biomes: there, it's in a cave (lush caves glow, so dark isn't the clue). */
	static boolean cave(String biome) {
		return biome.equals("lush_caves") || biome.equals("dripstone_caves") || biome.equals("deep_dark");
	}

	private final Companion c;
	/** Each biome it saw: the nearest spot it saw it at (packed), and when. In the world it's in. */
	private final Map<String, long[]> seen = new HashMap<>();
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> in;
	/** The biome it's in now, and is it a cave biome. */
	String here = "";
	boolean caveHere;
	private long hereAt = -100;

	Biomes(Companion c) {
		this.c = c;
	}

	private static String name(ServerLevel level, BlockPos at) {
		return level.getBiome(at).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
	}

	/** A sight line ended there: the biome of that spot (now and then: a sample of its lines). */
	void saw(ServerLevel level, BlockPos at, boolean under) {
		if (level.dimension() != in) {
			seen.clear();
			in = level.dimension();
		}
		String b = name(level, at);
		if (b.isEmpty() || cave(b) && !under) return;                       // (a cave biome is under the ground)
		long now = level.getGameTime();
		long[] had = seen.get(b);
		BlockPos me = c.player.blockPosition();
		if (had == null) {
			seen.put(b, new long[] {at.asLong(), now});
			if (seen.size() > 1) c.journal("sees", String.format(java.util.Locale.ROOT, "%s %.0f blocks off (%s)", cave(b) ? "a cave biome (" + b.replace('_', ' ') + ")" : "a " + b.replace('_', ' '),
					Math.sqrt(at.distSqr(me)), hints(b)));
			if (cave(b)) c.caves.sawInBiome(at, b);
			return;
		}
		if (at.distSqr(me) < BlockPos.of(had[0]).distSqr(me) || now - had[1] > 24000) {
			had[0] = at.asLong();
			had[1] = now;
		}
	}

	private static String hints(String b) {
		String[] h = HAS.get(b);
		return h == null ? "nothing it knows of there" : "there's " + String.join(", ", h).replace('_', ' ') + " there";
	}

	/** Every second: the biome it's in (a cave biome: it's in a cave, and its eyes take that as given). */
	void tick() {
		if (c.player == null) return;
		ServerLevel level = (ServerLevel) c.player.level();
		long now = level.getGameTime();
		if (now - hereAt < 20) return;
		hereAt = now;
		BlockPos feet = c.player.blockPosition();
		String b = name(level, feet);
		boolean under = !level.canSeeSky(feet.above());
		caveHere = cave(b) && under;
		if (!b.equals(here)) {
			here = b;
			if (caveHere) {
				c.journal("sees", "it's in " + b.replace('_', ' ') + (b.equals("deep_dark") ? ": the warden's home; quiet, and careful" : ": a cave"));
				c.caves.sawInBiome(feet, b);
			}
		}
		saw(level, feet, under);
	}

	/** The nearest spot it saw a biome that tends to have this, and which (null: none it knows). */
	Object[] nearestWith(String item) {
		BlockPos me = c.player.blockPosition(), best = null;
		String which = null;
		for (var e : seen.entrySet()) {
			String[] h = HAS.get(e.getKey());
			if (h == null || !java.util.Arrays.asList(h).contains(item)) continue;
			BlockPos p = BlockPos.of(e.getValue()[0]);
			if (best == null || p.distSqr(me) < best.distSqr(me)) {
				best = p;
				which = e.getKey();
			}
		}
		return best == null ? null : new Object[] {best, which};
	}

	/** For /xen g1x and its answers: the biome it's in and the ones it has seen. */
	String describe() {
		StringBuilder sb = new StringBuilder("in " + (here.isEmpty() ? "?" : here.replace('_', ' ')));
		if (!seen.isEmpty()) {
			sb.append("; seen: ");
			BlockPos me = c.player.blockPosition();
			seen.entrySet().stream().sorted(java.util.Comparator.comparingDouble(e -> BlockPos.of(e.getValue()[0]).distSqr(me))).limit(6)
					.forEach(e -> sb.append(e.getKey().replace('_', ' ')).append(String.format(java.util.Locale.ROOT, " (%.0f), ", Math.sqrt(BlockPos.of(e.getValue()[0]).distSqr(me)))));
			sb.setLength(sb.length() - 2);
		}
		return sb.toString();
	}
}
