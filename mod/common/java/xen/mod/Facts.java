package xen.mod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xen 2.0's facts: what it looks up instead of keeping it in its weights (its RAG store). Its brains learn how to read
 * a moment and decide; what a pig drops, how much a cooked porkchop fills, what shearing gives, those are facts, and
 * they come from the game it is in: the drops are the game's own loot tables, rolled many times (so they're right in
 * any version, with any data pack), the food is each item's own food value. When the game can't be asked (the tests,
 * before a world is loaded), a small table of the vanilla numbers stands in. The tips are what players learn the hard
 * way, in a line each, for its thoughts and for answers.
 */
final class Facts {
	private Facts() {}

	/** What one kind of animal drops, on average and how often at all: item -> {mean count, chance of at least one}. */
	static final class Drops {
		final Map<String, float[]> items = new LinkedHashMap<>();
		/** Rolled from the game's loot table (true) or from the table here (false). */
		final boolean rolled;

		Drops(boolean rolled) {
			this.rolled = rolled;
		}

		float mean(String item) {
			float[] v = items.get(item);
			return v == null ? 0 : v[0];
		}

		float chance(String item) {
			float[] v = items.get(item);
			return v == null ? 0 : v[1];
		}

		/** "1.9 porkchop (always)", "0.9 leather (62%)". */
		String says() {
			List<String> out = new ArrayList<>();
			for (var e : items.entrySet()) {
				float[] v = e.getValue();
				out.add(String.format(Locale.ROOT, "%.1f %s (%s)", v[0], e.getKey().replace('_', ' '), v[1] >= 0.99f ? "always" : Math.round(v[1] * 100) + "%"));
			}
			return out.isEmpty() ? "nothing" : String.join(", ", out);
		}
	}

	private static final int ROLLS = 48;
	private static final Map<String, Drops> ROLLED = new ConcurrentHashMap<>();

	/** The vanilla numbers, for when the game can't be asked: {item, min, max, chance of any}. */
	private static final Map<String, Object[][]> TABLE = Map.of(
			"pig", new Object[][] {{"porkchop", 1, 3, 1f}},
			"cow", new Object[][] {{"beef", 1, 3, 1f}, {"leather", 0, 2, 0.67f}},
			"mooshroom", new Object[][] {{"beef", 1, 3, 1f}, {"leather", 0, 2, 0.67f}},
			"sheep", new Object[][] {{"wool", 1, 1, 1f}, {"mutton", 1, 2, 1f}},
			"chicken", new Object[][] {{"chicken", 1, 1, 1f}, {"feather", 0, 2, 0.67f}},
			"rabbit", new Object[][] {{"rabbit", 0, 1, 0.5f}, {"rabbit_hide", 0, 1, 0.5f}, {"rabbit_foot", 1, 1, 0.1f}},
			"cod", new Object[][] {{"cod", 1, 1, 1f}, {"bone_meal", 1, 1, 0.05f}},
			"salmon", new Object[][] {{"salmon", 1, 1, 1f}, {"bone_meal", 1, 1, 0.05f}});

	/** The table's drops for a kind (an empty list for kinds it doesn't know). */
	static Drops table(String kind) {
		Drops d = new Drops(false);
		Object[][] rows = TABLE.get(kind);
		if (rows == null) return d;
		for (Object[] r : rows) {
			int min = (int) r[1], max = (int) r[2];
			float any = (float) r[3];
			float mean = min == 0 ? any * (1 + max) / 2f : (min + max) / 2f * any;
			d.items.put((String) r[0], new float[] {mean, any});
		}
		return d;
	}

	/**
	 * What this animal would drop if it killed it: the game's own loot table for it, rolled {@link #ROLLS} times (once
	 * per kind; a sheared sheep is its own kind), or the table here if that can't be done.
	 */
	static Drops drops(LivingEntity e, ServerPlayer killer) {
		String kind = kind(e);
		String key = kind + (e instanceof Sheep s && s.isSheared() ? ":sheared" : "");
		Drops d = ROLLED.get(key);
		if (d != null) return d;
		d = roll(e, killer);
		if (d == null) d = table(kind);
		ROLLED.put(key, d);
		return d;
	}

	private static Drops roll(LivingEntity e, ServerPlayer killer) {
		if (!(e.level() instanceof ServerLevel level) || e.getLootTable().isEmpty()) return null;
		try {
			Map<String, int[]> sum = new LinkedHashMap<>();                 // item -> {count, rolls it came up in}
			var source = level.damageSources().playerAttack(killer);
			for (int i = 0; i < ROLLS; i++) {
				Map<String, Integer> once = new LinkedHashMap<>();
				e.dropFromLootTable(level, source, false, e.getLootTable().get(), (ItemStack s) -> {
					if (!s.isEmpty()) once.merge(item(s), s.getCount(), Integer::sum);
				});
				for (var o : once.entrySet()) {
					int[] t = sum.computeIfAbsent(o.getKey(), k -> new int[2]);
					t[0] += o.getValue();
					t[1]++;
				}
			}
			Drops d = new Drops(true);
			for (var s : sum.entrySet()) d.items.put(s.getKey(), new float[] {s.getValue()[0] / (float) ROLLS, s.getValue()[1] / (float) ROLLS});
			return d;
		} catch (RuntimeException ex) {
			XenMod.LOG.debug("Xen facts: no loot roll for {}: {}", kind(e), ex.toString());
			return null;
		}
	}

	/** An item's name, the color left off wool ("white_wool" -> "wool"), so a pink sheep and a white one say the same. */
	static String item(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return n.endsWith("_wool") ? "wool" : n;
	}

	static String kind(LivingEntity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	// ------------------------------------------------------------------------------------- food
	/** What a raw food becomes in a furnace (the same name if it doesn't cook). */
	static String cooked(String raw) {
		return switch (raw) {
			case "porkchop" -> "cooked_porkchop";
			case "beef" -> "cooked_beef";
			case "mutton" -> "cooked_mutton";
			case "chicken" -> "cooked_chicken";
			case "rabbit" -> "cooked_rabbit";
			case "cod" -> "cooked_cod";
			case "salmon" -> "cooked_salmon";
			case "potato" -> "baked_potato";
			default -> raw;
		};
	}

	/** The vanilla hunger each food fills, for when the game can't be asked. */
	private static final Map<String, Integer> FILLS = Map.ofEntries(Map.entry("cooked_porkchop", 8), Map.entry("cooked_beef", 8),
			Map.entry("cooked_mutton", 6), Map.entry("cooked_chicken", 6), Map.entry("cooked_rabbit", 5), Map.entry("cooked_cod", 5),
			Map.entry("cooked_salmon", 6), Map.entry("baked_potato", 5), Map.entry("porkchop", 3), Map.entry("beef", 3), Map.entry("mutton", 2),
			Map.entry("chicken", 2), Map.entry("rabbit", 3), Map.entry("cod", 2), Map.entry("salmon", 2), Map.entry("bread", 5),
			Map.entry("apple", 4), Map.entry("carrot", 3), Map.entry("potato", 1), Map.entry("golden_carrot", 6));

	/** How much hunger an item fills (its food value in this game; the table if the game isn't there; 0 if it isn't food). */
	static int fills(String item) {
		try {
			var it = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(item));
			if (it.isPresent()) {
				var food = new ItemStack(it.get()).get(DataComponents.FOOD);
				return food == null ? 0 : food.nutrition();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// no game here (the tests): the table
		}
		return FILLS.getOrDefault(item, 0);
	}

	/** The hunger in an animal, cooked (what killing it is worth as food). */
	static float hunger(Drops d) {
		float h = 0;
		for (var e : d.items.entrySet()) h += e.getValue()[0] * fills(cooked(e.getKey()));
		return h;
	}

	// ----------------------------------------------------------------------------------- fishing
	/** Open water, no enchantments: a bite every 5 to 30 seconds (faster in rain); 85% fish, 10% junk, 5% treasure. */
	static final float FISH_WAIT_SECONDS = 17.5f, FISH_CHANCE = 0.85f, COD_SHARE = 0.6f, SALMON_SHARE = 0.25f;

	/** About how many catches fit in so many minutes (a cast, the wait, reeling in). */
	static float catchesIn(float minutes, boolean rain) {
		float wait = FISH_WAIT_SECONDS * (rain ? 0.8f : 1f) + 3f;
		return Math.max(0, minutes * 60f / wait);
	}

	/** About the hunger so many catches bring, cooked. */
	static float fishHunger(float catches) {
		return catches * FISH_CHANCE * (COD_SHARE * fills("cooked_cod") + SALMON_SHARE * fills("cooked_salmon"));
	}

	/** Shearing a sheep: 1 to 3 wool, and the sheep lives (its wool grows back when it eats grass). */
	static final float SHEAR_WOOL = 2f;

	// --------------------------------------------------------------------------------------- tips
	/** What players learn the hard way: a line each, found by the words in it. */
	static final String[] TIPS = {
			"Shearing a sheep gives 1 to 3 wool and the sheep lives; killing it gives 1 wool and 1 to 2 mutton.",
			"Two animals of a kind can breed: the last two are worth more alive.",
			"Cooked meat fills two to three times what raw meat does.",
			"A bed takes 3 wool of one color and 3 planks.",
			"Never dig straight down: there can be a cave, water or lava under the block.",
			"A block with lava behind it stays where it is.",
			"Iron ore needs a stone pickaxe; gold, redstone and diamond need iron.",
			"Copper isn't worth much early on.",
			"A tunnel two blocks high is how players mine: feet and head, no more.",
			"Fish bite faster in the rain.",
			"Fishing needs open water: a pond, a river, the sea.",
			"A crafting table and a furnace come along: break them and take them when you leave.",
			"Eat before the hunger bar is empty: a full bar heals you.",
			"Creepers hiss before they blow: back off, or hit them first.",
			"Gravel and sand fall when the block under them goes.",
	};

	/** The tips about something ("sheep", "fish", "lava"...). */
	static List<String> about(String topic) {
		String t = topic.toLowerCase(Locale.ROOT).replaceAll("e?s$", "");
		List<String> out = new ArrayList<>();
		if (t.length() < 3) return out;
		for (String tip : TIPS) if (tip.toLowerCase(Locale.ROOT).contains(t)) out.add(tip);
		return out;
	}
}
