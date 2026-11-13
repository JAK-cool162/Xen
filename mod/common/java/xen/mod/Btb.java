package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Xen 2.0 Back to Basics (brain "btb"): one state machine decides what a Xen does, and the parts it already has do it.
 *
 * <p>Observation, then the state it is in (in order: surviving, fighting, doing what it was told, working, exploring,
 * resting), then a task in that state, done by its muscle memory (its chores, crafter, walker, fighter, builder). A
 * task, once started, is kept until it is done or fails: a fight or a meal in between and it goes back to the same
 * one, never off to something else halfway (that is where the holes all over the map came from). When it is done,
 * how it went goes to its learning ({@link BtbLearner}: Q-values and a memory of what worked, kept between sessions),
 * which makes the same choice likelier or less likely the next time it is in that spot.
 *
 * <p>The longer goal (its DMM, see {@link #goal}) is thought over only now and then: get geared up first, then a home,
 * then the enchanting table and the world. Its reflexes (air, lava, falls, eating, fighting back) and what players tell
 * it come before all of this and are not its to decide.
 */
final class Btb {
	/** What it can do as a task (one at a time). */
	enum Task { NONE, WOOD, STONE, COAL, IRON, SMELT, FOOD, SHELTER, DIAMONDS, ENCHANT, TABLE, HOME, EXPLORE, SMELTER, BLAZE, PEARLS, TOOLS, VISIT, SLEEP, STASH }

	/** The state it is in, for its journal and /xen status. */
	enum State { SURVIVE, FIGHT, COMMAND, WORK, EXPLORE, REST }

	private final Companion c;
	State state = State.REST;
	Task task = Task.NONE;
	/** Why it is doing it, in words. */
	String why = "";
	private long taskAt;
	private String stateAt = "";
	private float hurtSince;
	private int deathsAt, tierAt, ironAt, fuelAt, enchantStuffAt, levelAt;
	private int rodsAt, pearlsAt;

	/** Blaze rods (as powder for the eyes: one rod, two) and ender pearls for twelve eyes of ender, counting the eyes it has. */
	private int rods() {
		var it = c.items();
		return 2 * it.getOrDefault("blaze_rod", 0) + it.getOrDefault("blaze_powder", 0) + it.getOrDefault("ender_eye", 0);
	}

	private int pearls() {
		var it = c.items();
		return it.getOrDefault("ender_pearl", 0) + it.getOrDefault("ender_eye", 0);
	}

	private boolean netherNeeds() {
		return rods() < 12 || pearls() < 12;
	}
	private double worthAt;
	private final Map<Task, Long> notBefore = new EnumMap<>(Task.class);
	private long visitLookedAt = -100_000;
	/** When it could first sleep this night, on its way to bed (-1: not yet). */
	private long sleepFrom = -1;
	private boolean visitThere;
	/** The longer goal (its DMM's call): "gear" (tools, iron, diamonds, armor), "home", "enchant", "world". */
	String goal = "gear";
	private long goalAt = -100000;
	/** When it last went for coal (for torches: not again for ten minutes if there was none to be had). */
	private long coalForLightAt = -100000;
	/** When it last went for wood (logs for the trip down: not again for ten minutes, if there were none to be had). */
	private long packedWoodAt = -100000;

	/** Its wood, in planks: a log is four, a stick half. */
	private int woodInBag() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			var st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String k = c.itemKey(st);
			n += k.equals("log") ? 4 * st.getCount() : k.endsWith("_planks") ? st.getCount() : k.equals("stick") ? st.getCount() / 2 : 0;
		}
		return n;
	}

	Btb(Companion c) {
		this.c = c;
	}

	/** Is this the brain deciding for it? (The setting; minions keep theirs.) */
	boolean on() {
		return "btb".equals(c.mod.config.brain) && !c.minion;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	// ------------------------------------------------------------------------------------------------ observation
	/** What it knows of itself and where it is, the few things its decisions need. */
	record Obs(int tier, float health, int food, int meals, boolean night, boolean under, int danger, int iron, int raw, int fuel,
			int diamonds, int logs, int stone, boolean sheltered, boolean overworld) {
		/** The state, coarse, for its learning: same spot, same key. */
		String key() {
			return "t" + tier + "h" + (health <= 8 ? 0 : health <= 14 ? 1 : 2) + "f" + (food <= 6 ? 0 : food <= 14 ? 1 : 2) + "m" + Math.min(meals, 2)
					+ "n" + (night ? 1 : 0) + "u" + (under ? 1 : 0) + "d" + danger;
		}
	}

	Obs observe() {
		var p = c.player;
		Map<String, Integer> items = c.items();
		int raw = items.getOrDefault("raw_iron", 0), iron = items.getOrDefault("iron_ingot", 0);
		int fuel = items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0);
		float d = c.danger();
		return new Obs(c.crafter.pickTier(), p.getHealth(), p.getFoodData().getFoodLevel(), items.getOrDefault("food", 0),
				p.level().isDarkOutside(), !p.level().canSeeSky(p.blockPosition().above()), d < 0.2f ? 0 : d < 0.6f ? 1 : 2, iron, raw, fuel,
				items.getOrDefault("diamond", 0), items.getOrDefault("log", 0), items.getOrDefault("cobblestone", 0), c.chores.sheltered() || MindSense.enclosed(p),
				p.level().dimension() == Level.OVERWORLD);
	}

	// ------------------------------------------------------------------------------------------------ the longer goal (DMM)
	/** Now and then (or when the one it had is done): what it is after for the next while. */
	private void thinkGoal(Obs o) {
		if (now() - goalAt < 20 * 60 * 3 && !goalDone(o)) return;
		goalAt = now();
		String was = goal;
		boolean geared = o.tier >= 4 && o.diamonds >= c.goals.diamondsWanted();   // (the diamonds for what it still lacks in its bag: geared; the table's two are the enchanting goal's)
		boolean tabled = c.enchanter.table() != null || c.crafter.has("enchanting_table");
		boolean early = !geared && c.goals.home == null && c.mod.config.wants && o.tier >= 3 && o.overworld && earlyBase();
		if (early) goal = "home";                                                // an iron pickaxe: a base first, a small one (a chest for its diamonds and ore, a table, a furnace; its bed for a spawn): a death after costs it only what it carries, and it wakes by it
		else if (!geared) goal = "gear";                                         // tools, iron, diamonds, armor: first, always
		else if (!tabled || !enchantedGear()) goal = "enchant";                 // (its tools and its armor enchanted, both)
		else if (c.goals.home == null && c.mod.config.wants) goal = "home";      // a base before the Nether: a chest for what it has (a death there costs it everything it carries), its bed for a spawn, its table set up
		else if (netherNeeds()) goal = "nether";                                // (blaze rods and ender pearls: the eyes for the End; no giving up on a goal, it stays on it till it's done)
		else goal = "world";
		if (!goal.equals(was)) c.journal("thinks", "its goal for now: " + goal + (was.isEmpty() ? "" : " (was " + was + ")"));
	}

	/** When it first went for an early base this time (ten minutes for it; then its gear, and another go in half an hour). */
	private long earlyBaseFrom = -1_000_000;

	private boolean earlyBase() {
		long t = now();
		if (t - earlyBaseFrom > 20 * 60 * 40) earlyBaseFrom = t;
		return t - earlyBaseFrom < 20 * 60 * 10;
	}

	/** Its spares into its chest at home (not for days: once in ten minutes at most), and its diamonds back out after a death. */
	private long stashAt = -1_000_000;
	private boolean takingBack;

	/** What it leaves in the chest: whole stacks of what it has no use for on it now. */
	private boolean leaves(String key, int count) {
		boolean done = c.goals.diamondsWanted() == 0;                              // (all its diamond gear and its table: diamonds are spares now)
		return switch (key) {
			case "diamond", "diamond_block" -> done;
			case "raw_iron", "raw_copper", "copper_ingot", "iron_ore", "deepslate_iron_ore", "copper_ore" -> count >= 8 && c.crafter.pickTier() >= 4;
			case "iron_ingot" -> count >= 16;
			case "book" -> c.enchanter.table() != null || c.crafter.has("enchanting_table");
			case "gold_ingot", "raw_gold", "gold_ore", "deepslate_gold_ore", "emerald", "enchanted_book", "name_tag", "saddle", "diamond_ore", "deepslate_diamond_ore",
					"redstone", "quartz", "amethyst_shard", "emerald_ore" -> true;
			default -> false;
		};
	}

	private boolean goalDone(Obs o) {
		return switch (goal) {
			case "gear" -> o.tier >= 4 && o.diamonds >= c.goals.diamondsWanted();
			case "enchant" -> enchantedGear();
			case "nether" -> !netherNeeds();
			case "home" -> c.goals.home != null;
			default -> false;
		};
	}

	/** An enchanted tool or weapon and an enchanted piece of armor (on it or in its bag). */
	private boolean enchantedGear() {
		boolean tool = false, armor = false;
		var p = c.player;
		var inv = p.getInventory();
		java.util.List<net.minecraft.world.item.ItemStack> all = new java.util.ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) all.add(inv.getItem(i));
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) all.add(p.getItemBySlot(slot));
		for (net.minecraft.world.item.ItemStack s : all) {
			if (s.isEmpty() || !s.isEnchanted()) continue;
			String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (n.endsWith("_pickaxe") || n.endsWith("_sword") || n.endsWith("_axe") || n.endsWith("_shovel")) tool = true;
			if (n.endsWith("_helmet") || n.endsWith("_chestplate") || n.endsWith("_leggings") || n.endsWith("_boots")) armor = true;
		}
		if (tool && armor && p.experienceLevel >= 3) {                          // levels for more: every diamond piece first, then the Nether (Ann, one chestplate done, three pieces bare, was off); out of lapis, it gets lapis (its table step does: it knows where), not the Nether
			for (net.minecraft.world.item.ItemStack s : all) {
				if (s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
				if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("diamond_")) return false;
			}
		}
		return tool && armor;
	}

	private boolean enchantedSomething() {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).isEnchanted()) return true;
		return false;
	}

	// ------------------------------------------------------------------------------------------------ choosing a task
	private record Option(Task task, float base, String why) {}

	/** The tasks that make sense now, with how much (the rules), most needed first. */
	private List<Option> options(Obs o) {
		List<Option> out = new ArrayList<>();
		boolean diamondsToGet = goal.equals("gear") && o.tier >= 3 && o.diamonds < c.goals.diamondsWanted();   // (diamond gear still to get: the smelter and a home wait)
		boolean dusk = c.goals.evening() && !o.under && o.overworld;           // (from the evening on: a roof before the dark, not in it)
		boolean digsIn = diamondsToGet && o.tier >= 3 && o.health >= 14 && o.meals > 0;   // (night, an iron pickaxe, well and fed: down its staircase is its roof, and the diamonds are down there; Cid hid in a hole ten minutes)
		if (dusk && !o.sheltered && !digsIn) out.add(new Option(Task.SHELTER, 5, "night, out in the open: a roof or a hole first"));
		boolean bed = c.items().keySet().stream().anyMatch(n -> n.endsWith("_bed"));
		if ((dusk || o.night) && bed && o.overworld && !o.under && now() >= notBefore.getOrDefault(Task.SLEEP, 0L))
			out.add(new Option(Task.SLEEP, 5.5f, "night, a bed in its bag: down in it (no night out in the open, and the night's over sooner)"));   // (most of its deaths were at night)
		boolean hurtNoFood = o.meals == 0 && o.food < 18 && o.health <= 12;     // (hurt, under 18 it doesn't heal, nothing to eat: food comes first; Ann at five health went after diamonds, then coal, ten minutes)
		boolean darkOut = (dusk || o.night) && o.overworld && !o.under;           // (out in the dark, a hunt is the night's monsters' hunt: Ann, food 6, after a chicken at night, a zombie; under 18 it doesn't heal, but it doesn't hurt either: a roof first, the hunt at dawn)
		if (o.meals < 2 && o.food < 18 && (!darkOut || o.food <= 3)) out.add(new Option(Task.FOOD, o.food < 10 || hurtNoFood ? 4.5f : 2.5f, hurtNoFood ? "hurt and nothing to eat: food to heal" : "little to eat"));
		else if (o.meals < 4 && !o.night && !dusk && o.overworld && !o.under)   // (its bag nearly out of food, still fed: a hunt now, by day, up here, not once it's hungry at the bottom of a mine or after dark; 19 of 23 deaths came at three health or less, unhealed for want of food)
			out.add(new Option(Task.FOOD, o.meals == 0 ? 3.9f : o.meals == 1 ? 3.4f : 2.6f, "food for later: its bag's nearly out"));
		boolean smelter = c.chores.smelterAt() != null;
		boolean tabling = goal.equals("enchant");                               // (its table needs no iron: the iron in its bag waits till it's enchanted; Ann smelted for five minutes first)
		boolean pickFine = o.tier >= 3 && c.crafter.pickWorn() >= 0.5;          // (a good pickaxe in hand, half of it left: its diamonds before iron it doesn't need yet; at a fifth there was no time to make a spare, Cid's broke and he dug with stone for diamonds)
		var bag = c.items();
		boolean tableOrWood = bag.getOrDefault("crafting_table", 0) > 0 || woodInBag() >= 4;
		boolean canFurnace = bag.getOrDefault("furnace", 0) > 0 || tableOrWood && o.stone >= 8 || c.chores.furnaceNear();   // (Ann tried to make one with no table and no wood to make one: three minutes, twice)
		boolean ironUse = o.tier < 3 || !pickFine || goal.equals("home") || goal.equals("world");   // (an iron pickaxe in hand with life in it and diamond gear coming: its iron waits; Bob stood at a furnace with thirty-five raw iron, his diamonds waiting)
		if (o.raw >= 3 && (o.fuel > 0 || o.logs > 0 || smelter) && (canFurnace || smelter) && !tabling && ironUse)   // (making its table: none at all; a lapis trip that came to nothing had it smelting thirty-one iron for minutes, its table waiting)
			out.add(new Option(Task.SMELT, goal.equals("gear") && pickFine ? 3.85f : 4, smelter ? "raw iron to drop off at its smelter" : "raw iron to smelt"));
		else if (smelter && c.chores.droppedRaw > 0 && now() - c.chores.droppedAt > 20 * 120) out.add(new Option(Task.SMELT, 3.7f, "its smelter's ingots to take out"));
		boolean earlyHome = goal.equals("home") && c.goals.home == null && !(o.tier >= 4 && o.diamonds >= c.goals.diamondsWanted());   // (its first base, before its diamonds: that, not an auto-smelter)
		boolean sideWork = !earlyHome && (goal.equals("home") || goal.equals("world"));   // (an auto-smelter, a house: once its gear, its enchanting and the Nether are done, not in the middle of them)
		if (!smelter && sideWork && o.tier >= 2 && o.iron + o.raw >= 12 && o.stone >= 16 && !o.night)
			out.add(new Option(Task.SMELTER, 3.6f, "an auto-smelter: its ore dropped off, no waiting at a furnace"));
		if (o.tier >= 4 && !o.night && c.crafter.armorToMake() && !c.crafter.has("crafting_table") && c.crafter.woodCount() < 4)   // (Eve: thirty-four diamonds in her bag, iron on her back, not a log for a crafting table, for ten minutes)
			out.add(new Option(Task.WOOD, 4.7f, "diamonds for armor in its bag, and no table or wood to make it on"));
		if (o.tier == 0) out.add(new Option(Task.WOOD, 4, "wood for a table and a pickaxe"));
		else if (c.crafter.shortOfWood()) out.add(new Option(Task.WOOD, 4.6f, "wood for sticks: the makings of its next pickaxe are in its bag"));
		else if (o.tier >= 2 && o.overworld && !o.under && !o.night && goal.equals("gear") && woodInBag() < 8 && now() - packedWoodAt > 20 * 60 * 10)
			out.add(new Option(Task.WOOD, 4.4f, "a few logs before it goes down (sticks for a new pickaxe, a table, fuel for its furnace)"));   // (like a player: Bob's pickaxe broke at the bottom of his mine, no wood on him, no way to make another)
		else if (o.tier >= 1 && o.overworld && !o.night && woodInBag() == 0 && bag.getOrDefault("crafting_table", 0) == 0 && now() - packedWoodAt > 20 * 60 * 10)   // (not at night: Bob went up for wood in the dark, skeletons, a zombie, a spider)
			out.add(new Option(Task.WOOD, 4.0f, "no wood at all: no table, no sticks, no furnace (up for some)"));   // (Ann, none on her: three minutes twice trying to make a furnace)
		if (o.tier == 1) out.add(new Option(Task.STONE, 4, "stone for stone tools and a furnace"));
		if (o.tier >= 1 && o.raw >= 3 && o.fuel == 0 && o.logs == 0 && !tabling) out.add(new Option(Task.COAL, 4, "iron and nothing to smelt it with"));
		if (o.tier == 2 && o.iron + o.raw < 3) out.add(new Option(Task.IRON, 3.8f, "iron for an iron pickaxe"));
		if (diamondsToGet) {                                                      // the iron pickaxe: straight for diamonds (its armor is diamond, not iron first)
			boolean spare = c.crafter.count(n -> n.equals("iron_pickaxe") || n.equals("diamond_pickaxe")) >= 2;
			if (o.tier == 3 && c.crafter.pickWorn() < 0.2 && !spare) {         // (a pickaxe nearly gone and no spare: the iron for one first, not a trip it breaks on)
				if (o.iron + o.raw < 3) out.add(new Option(Task.IRON, 4.1f, "iron for a new pickaxe (this one is nearly worn out)"));
				else if (o.iron >= 3) out.add(new Option(Task.TOOLS, 4.3f, "a new iron pickaxe (this one is nearly worn out)"));   // (the iron in its bag: made now, not after it breaks; raw iron: smelting's offered)
			} else out.add(new Option(Task.DIAMONDS, 3.9f, "diamonds (a diamond pickaxe, a sword, armor)"));
		}
		boolean lootFor = goal.equals("gear") && o.tier < 3;                     // (its obsidian for the table: its plan weighs the village and the portal against the lava, Xenfish)
		if (lootFor && !o.night && o.overworld && now() - visitLookedAt > 20 * 30) {   // (a village's chests or a ruined portal's: obsidian, iron, before digging for it, as players do)
			visitLookedAt = now();
			visitThere = c.visits.candidate();
		}
		if (lootFor && !o.night && o.overworld && visitThere)
			out.add(new Option(Task.VISIT, 3.5f, "a village's chests or a ruined portal's first (obsidian at the weaponsmith's, iron, the portal's own)"));
		boolean noBed = c.items().keySet().stream().noneMatch(n -> n.endsWith("_bed")) && !c.hasBed();
		if (!lootFor && !o.night && !dusk && o.overworld && (o.meals < 4 || noBed)) {   // a village for food and a bed: its hay is bread (three a bale), its beds a spawn and a night (no sheep needed); not only for obsidian
			if (now() - visitLookedAt > 20 * 30) {
				visitLookedAt = now();
				visitThere = c.visits.candidate();
			}
			if (visitThere) out.add(new Option(Task.VISIT, o.meals == 0 ? 4.6f : noBed ? 3.6f : 3.2f, "a village: hay for bread, a bed (no sheep needed)"));
		}
		if (goal.equals("enchant")) {
			if (c.enchanter.possible() && c.player.level().getGameTime() > c.enchanter.retryAt)   // (not again the moment it couldn't: Ann tried every three seconds)
				out.add(new Option(Task.ENCHANT, 4.2f, "the makings of an enchantment: its gear enchanted"));
			else out.add(new Option(Task.TABLE, 3.2f, "an enchanting table (a book, four obsidian)"));
		}
		if (goal.equals("home") && !o.night) out.add(earlyHome ? new Option(Task.HOME, 3.7f, "an early base: a chest for its diamonds and ore, its bed for a spawn") : new Option(Task.HOME, 3, "a home"));
		BlockPos chest = c.storage.chestAtHome();
		if (chest != null && !o.night && o.overworld && now() - stashAt > 20 * 60 * 10 && !c.storage.busy() && chest.closerThan(c.player.blockPosition(), 48) && stashable())
			out.add(new Option(Task.STASH, 3.3f, "its spares into its chest at home (a death costs it only what it carries)"));
		if (chest != null && !o.night && o.overworld && o.diamonds < c.goals.diamondsWanted() && c.storage.inChests("diamond") > 0 && chest.closerThan(c.player.blockPosition(), 160))
			out.add(new Option(Task.STASH, 4.1f, "its diamonds from its chest: for its gear"));
		if (goal.equals("nether")) {
			if (rods() < 12) out.add(new Option(Task.BLAZE, 3.6f, "blaze rods: a fortress in the Nether"));
			if (pearls() < 12) out.add(new Option(Task.PEARLS, rods() < 12 ? 3.3f : 3.7f, "ender pearls: endermen"));
		}
		if (c.goals.home == null && o.tier >= 2 && !o.night && c.mod.config.wants && goal.equals("world"))
			out.add(new Option(Task.HOME, 0.9f, "nothing pressing: a home of its own (its mind's design)"));   // (its job has nothing on: its mind builds)
		if (c.goals.home != null && !o.night && o.overworld && (c.builder.canUpgrade() || c.builder.canExtendBase()))
			out.add(new Option(Task.HOME, goal.equals("world") ? 2.5f : 0.8f, "nothing pressing: its base better or bigger (a house's next stage, a new room under the ground)"));   // (a home once had, never touched again: no upgrade ever ran)
		out.add(new Option(Task.EXPLORE, 0.5f, "nothing pressing: a look around"));
		if (resumeTask != null) {                                                // what it dropped for its life: back to it, now it's safe
			if (now() - resumeFrom > 20 * 60 * 10) resumeTask = null;
			else if (o.health >= 12 && o.danger < 2 && (o.meals > 0 || o.food >= 18) && !(o.night && o.overworld && !o.under && !o.sheltered)) {
				Task back = resumeTask;
				out.removeIf(op -> op.task == back);
				out.add(new Option(back, 4.4f, "back to what it dropped for its life (" + back.name().toLowerCase(Locale.ROOT) + ")"));
			}
		}
		// Danger first: hurt (under 14), nothing to eat in its bag, monsters about: nothing risky. No mining like that (it
		// heals, eats, gets food first); none of the walking-about jobs with monsters round it; by water, a mine is the
		// last thing (its holes fill, it swims off its staircase).
		boolean hurt = o.health < 14, noFood = o.meals == 0 && o.food < 16, wet = nearWater();
		if (noFood) out.replaceAll(op -> op.task == Task.FOOD ? new Option(Task.FOOD, 4.8f, "nothing to eat in its bag") : op);
		out.removeIf(op -> switch (op.task) {
			case COAL, IRON, DIAMONDS -> hurt || noFood;
			case EXPLORE, HOME, WOOD, STONE -> o.danger >= 1 && hurt;
			default -> false;
		});
		if (wet) out.replaceAll(op -> op.task == Task.COAL || op.task == Task.IRON || op.task == Task.DIAMONDS ? new Option(op.task, op.base - 1.5f, op.why + " (water close: elsewhere if it can)") : op);
		if (hurt && !noFood && o.food >= 18 && out.stream().noneMatch(op -> op.task == Task.SHELTER || op.task == Task.SMELT)) {
			out.removeIf(op -> op.task != Task.FOOD);                          // (fed and hurt: it heals first, a few seconds)
			if (now() - restSaidAt > 600) {
				restSaidAt = now();
				c.journal("thinks", String.format(Locale.ROOT, "btb: %.0f health: heals before anything risky", o.health));
			}
		}
		if ((o.night || c.goals.sunDown()) && o.overworld && !o.under) {        // the sun down: only what's done under a roof or under the ground
			out.removeIf(op -> switch (op.task) {
				case SHELTER, SMELT, ENCHANT, COAL, IRON, DIAMONDS, TOOLS -> false;
				case PEARLS -> false;                                            // (endermen are out at night: in its diamond armor, that's when)
				case STONE -> !o.sheltered;
				default -> true;
			});
		}
		return out;
	}

	private long nightCheckedAt, shelterSaidAt = -1000, restSaidAt = -1000;

	/** Water within three blocks of its feet (a mine dug here floods, a fight here ends in the water). */
	private boolean nearWater() {
		var level = c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 1, 3)))
			if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) return true;
		return false;
	}
	/** What it dropped for its life, and when: once it's safe again (twelve health, no monsters on it), back to that first, within ten minutes. */
	private Task resumeTask;
	private long resumeFrom;

	/** When it last dropped its task for food, hurt (not every tick: once a minute at most). */
	private long hurtDropAt = -1_000_000;
	/** Its task was dropped (the night): not done, whatever it picked up meanwhile. */
	private boolean dropped;
	/** Mining trips in a row that came to nothing within a minute and a half (its mine is no good: water, a ravine). */
	private int quickFails;

	/**
	 * The sun going down and it's out under the sky on wood, stone or food: that waits for the morning, a roof now.
	 * (Mining goes on: it's under the ground in a few steps.) True if it dropped its task.
	 */
	boolean nightfall() {
		boolean ownErrand = task == Task.NONE && c.chores.busy() && (c.chores.kind == Chores.Kind.HUNT || c.chores.kind == Chores.Kind.GATHER);   // (a hunt its plans started, no task of its brain's: Cid hunted a sheep for a bed's wool in the dark, shot from eleven health to none)
		if (task != Task.WOOD && task != Task.STONE && task != Task.FOOD && task != Task.SMELT && task != Task.TABLE && task != Task.HOME && task != Task.TOOLS && task != Task.VISIT
				&& !ownErrand || now() - nightCheckedAt < 40) return false;             // (any errand up top: Bob went on to his furnace at night, three zombies, dead)
		nightCheckedAt = now();
		var p = c.player;
		if (p.level().dimension() != Level.OVERWORLD || !c.goals.sunDown() || MindSense.enclosed(p) || !p.level().canSeeSky(p.blockPosition().above())) return false;
		c.journal("does", "btb: the sun's going down: drops " + (ownErrand ? c.chores.kind.name().toLowerCase(Locale.ROOT) : task.name().toLowerCase(Locale.ROOT)) + " for a roof");
		if (c.chores.busy()) c.chores.cancel();
		if (c.nether.busy()) c.nether.cancel();
		if (c.builder.busy()) c.builder.cancel();
		if (task == Task.VISIT) c.visits.stop("the sun's going down");
		dropped = true;
		return true;
	}

	/** Its next task (or null to let its other habits have the moment). */
	Action next() {
		if (c.player == null || !c.player.isAlive()) return null;
		Obs o = observe();
		String life = o.health <= 8 && o.meals == 0 && o.food < 18 ? "hurt (" + Math.round(o.health) + " health) and nothing to eat"
				: o.health <= 8 && o.danger >= 2 ? "hurt (" + Math.round(o.health) + " health) with monsters about"
				: o.health <= 8 && o.night && o.overworld && !o.under && !o.sheltered ? "hurt (" + Math.round(o.health) + " health) out under the night sky"
				: c.player.getAirSupply() < 100 && c.player.isUnderWater() ? "out of air" : null;
		if (life != null && task != Task.NONE && task != Task.FOOD && task != Task.SHELTER && task != Task.SLEEP && task != Task.STASH
				&& !c.fightingNow() && now() - hurtDropAt > 20 * 30 && busy()) {   // its life first: whatever it's on waits, and it chooses again, safe things first (Fin built a smelter at four health with no food; 19 of 23 deaths came at three health or less; Dee drowned "swimming to air" in diamond)
			hurtDropAt = now();
			c.journal("does", "btb: life first, " + life + ": drops " + task.name().toLowerCase(Locale.ROOT) + " (back to it once it's safe)");
			resumeTask = task;
			resumeFrom = now();
			if (c.chores.busy()) c.chores.cancel();
			if (c.nether.busy()) c.nether.cancel();
			if (c.builder.busy()) c.builder.cancel();
			if (task == Task.VISIT) c.visits.stop(life);
			dropped = true;
		}
		if (task != Task.NONE) {
			if (busy()) return task == Task.EXPLORE ? c.goals.exploreStep() : null;   // (still on it: a trip, a build, a look around)
			finish(o);                                                                // (its chore ended: how did that go?)
		}
		thinkGoal(o);
		List<Option> opts = options(o);
		String key = o.key();
		BtbLearner learn = c.mod.btbLearner;
		Option best = null;
		float bestScore = -1e9f;
		for (Option op : opts) {
			if (now() < notBefore.getOrDefault(op.task, 0L)) continue;           // (it just failed at that: something else for a bit)
			float score = op.base + learn.bias(key, op.task.name());
			if (score > bestScore) {
				bestScore = score;
				best = op;
			}
		}
		if (best != null && opts.size() > 1 && c.random().nextFloat() < learn.explore()) {   // now and then another of the sensible ones, to find out
			Option other = opts.get(c.random().nextInt(opts.size()));
			if (now() >= notBefore.getOrDefault(other.task, 0L)) best = other;
		}
		if (best == null)                                                        // (out in the dark with nothing it can start: on to other ground, not standing there)
			return (o.night || c.goals.sunDown()) && o.overworld && !o.under && !o.sheltered ? c.goals.exploreStep() : null;
		String plan = start(best.task, o);
		if (plan == null || !plan.startsWith("You will")) {                      // (it can't now: not again for a minute)
			boolean shelter = best.task == Task.SHELTER;
			notBefore.put(best.task, now() + (shelter ? 20 * 5 : forGoal(best.task) ? 20 * 15 : 20 * 60));   // (a roof: again in a few steps, from other ground; what its goal needs: soon)
			if (plan != null && (!shelter || now() - shelterSaidAt > 400)) {
				c.journal("thinks", "can't " + best.task.name().toLowerCase(Locale.ROOT) + " now: " + plan + (shelter ? " (other ground, then again)" : ""));
				if (shelter) shelterSaidAt = now();
			}
			if (best.task == Task.EXPLORE || shelter) return c.goals.exploreStep();   // (never standing about out in the dark: on to ground it can dig into)
			return null;
		}
		task = best.task;
		why = best.why;
		if (task == resumeTask) {
			resumeTask = null;
			c.journal("does", "btb: safe again: back to " + task.name().toLowerCase(Locale.ROOT));
		}
		state = best.task == Task.SHELTER || best.task == Task.FOOD ? State.SURVIVE : best.task == Task.EXPLORE ? State.EXPLORE : State.WORK;
		taskAt = now();
		stateAt = key;
		hurtSince = 0;
		deathsAt = c.genDeaths;
		tierAt = o.tier;
		worthAt = worth();
		ironAt = o.iron;
		fuelAt = o.fuel;
		enchantStuffAt = enchantStuff();
		levelAt = c.player.experienceLevel;
		rodsAt = rods();
		pearlsAt = pearls();
		c.journal("thinks", String.format(Locale.ROOT, "btb: %s (%s; goal %s)", task.name().toLowerCase(Locale.ROOT), why, goal));
		if (task == Task.EXPLORE) return c.goals.exploreStep();
		return null;                                                                 // (its chore starts next tick)
	}

	/** Sets the task going with the muscle memory it has: "You will ..." if it could. */
	private String start(Task t, Obs o) {
		Chores ch = c.chores;
		return switch (t) {
			case WOOD -> {
				packedWoodAt = now();
				yield ch.gather("wood", o.tier == 0 ? 5 : 4);
			}
			case STONE -> ch.gather("stone", 16);
			case COAL -> {
				coalForLightAt = now();
				yield ch.mine(c.lessons.depth("coal", 40), "coal", o.raw >= 3 ? 8 : 3);   // (for light, three is twelve torches: the eight took four minutes before its first diamond trip)
			}
			case IRON -> ch.mine(c.lessons.depth("iron", 16), "iron", o.tier == 2 ? 3 : 10);
			case DIAMONDS -> ch.mine(c.lessons.depth("diamonds", -58), "diamonds", Math.max(3, c.goals.diamondsWanted() - o.diamonds));
			case SMELT -> ch.smelt();
			case FOOD -> {
				var prey = ch.nearestAnimal();
				boolean noAnimals = prey == null || prey.distanceTo(c.player) > 24;
				if (noAnimals && !o.night && c.visits.candidate()) {               // (no animals about, a village it knows: its hay is bread)
					String v = c.visits.begin();
					if (v != null) {
						c.journal("thinks", "no animals about: a village's hay for bread instead");
						yield v;
					}
				}
				if (noAnimals && !o.night && (c.fisher.hasRod() || c.items().getOrDefault("string", 0) >= 2) && c.fisher.waterNear() != null) {   // (no animals about, water close by: it fishes; Ivy looked for animals for most of an hour at half a heart)
					String f = c.fisher.start();
					if (f.startsWith("You will")) {
						c.journal("thinks", "no animals about: fishes instead");
						yield f;
					}
				}
				yield ch.hunt(o.meals == 0 ? 5 : 3);
			}
			case SHELTER -> shelterOrPlanB(o);
			case ENCHANT -> c.enchanter.start();
			case TABLE -> tableStep();
			case TOOLS -> planFor("iron_pickaxe", 1, "a new pickaxe before this one breaks");
			case HOME -> {                                                    // a base: its team's if a teammate has one, else a small one of its own
				BlockPos mate = c.mod.teamHome(c);
				if (mate != null) {
					c.goals.home = mate;
					c.journal("does", "moves into its team's base at " + mate.toShortString());
					yield "You will share your team's base.";
				}
				if (c.goals.home != null) {                                    // a base it has: better (a house's next stage) or bigger (a new room under the ground)
					if (c.builder.canUpgrade()) yield c.builder.upgrade();
					if (c.builder.canExtendBase()) yield c.builder.extendBase();
					yield "You can't make your base any better just now.";
				}
				yield c.builder.startStarterHome();
			}
			case SMELTER -> c.builder.start("smelter");
			case BLAZE -> c.nether.go("blaze", Math.max(1, (12 - rods() + 1) / 2));
			case PEARLS -> c.chores.slay("enderman", "ender_pearl", 12 - pearls(), "ender pearls");
			case EXPLORE -> "You will look around.";
			case VISIT -> c.visits.begin();
			case SLEEP -> "You will sleep in your bed.";
			case STASH -> {
				stashAt = now();
				if (o.diamonds < c.goals.diamondsWanted() && c.storage.inChests("diamond") > 0) {
					String took = c.storage.take("diamond", Math.min(64, c.storage.inChests("diamond")));
					if (took != null) {
						c.journal("does", "goes to its chest for its diamonds (" + c.storage.inChests("diamond") + " in it)");
						yield took;
					}
				}
				if (c.storage.stash(48, this::leaves)) {
					c.journal("does", "takes its spares to its chest at home (in case the worst happens)");
					yield "You will leave your spares in the chest at home.";
				}
				yield "You have nothing to leave in your chest.";
			}
			case NONE -> null;
		};
	}

	/** A roof, or plan B: a hole dug by hand here or close by (never left standing about out in the dark). */
	private String shelterOrPlanB(Obs o) {
		Chores ch = c.chores;
		String plan = o.under ? ch.digIn() : ch.shelter();
		if (plan != null && plan.startsWith("You will")) return plan;
		String dig = ch.digIn();
		if (dig != null && dig.startsWith("You will")) {
			c.journal("thinks", "btb: no shelter here (" + (plan == null ? "no spot" : plan) + "): digs in by hand");
			return dig;
		}
		return plan;
	}

	/** What it has toward an enchanting table and enchanting: leather, sugar cane, paper, books, lapis, obsidian, a table. */
	private int enchantStuff() {
		var it = c.items();
		return it.getOrDefault("leather", 0) + it.getOrDefault("sugar_cane", 0) + it.getOrDefault("paper", 0) + 3 * it.getOrDefault("book", 0)
				+ it.getOrDefault("lapis_lazuli", 0) + 2 * it.getOrDefault("obsidian", 0) + 20 * it.getOrDefault("enchanting_table", 0)
				+ (c.enchanter.table() != null ? 20 : 0);
	}

	/**
	 * Toward its enchanting table, one step at a time, by its plans (the recipe tree): the book first (leather from a cow,
	 * sugar cane into paper), lapis to enchant with, then the obsidian trip (made from lava if it has to) and the table.
	 */
	private String tableStep() {
		var it = c.items();
		boolean tabled = c.enchanter.table() != null || c.crafter.has("enchanting_table");
		if (it.getOrDefault("lapis_lazuli", 0) < 3) {                          // (down there after its diamonds: the lapis and the obsidian first, the book's up on the surface)
			String lapis = c.chores.mine(c.lessons.depth("lapis", 0), "lapis", 3);   // (a mining trip to the lapis it knows of: the fetch walked at one 128 blocks off, ten minutes, none)
			if (lapis != null && lapis.startsWith("You will")) return lapis + " (lapis to enchant with)";
		}
		if (!tabled && it.getOrDefault("obsidian", 0) < 4 && it.getOrDefault("water_bucket", 0) + it.getOrDefault("bucket", 0) == 0
				&& c.visits.nearest("village") == null && c.visits.nearest("ruined portal") == null) {   // lava the only way, and no bucket: the bucket first (and it puts out a fire; Ann lost hers in the lava, and her table trip ended as it began, thirty times)
			String bucket = planFor("bucket", 1, "a bucket: water for the lava's obsidian, and for a fire");
			if (bucket.startsWith("You will")) return bucket;
		}
		if (!tabled && it.getOrDefault("obsidian", 0) < 4) {                    // Xenfish: the cheapest way to it from here, by its own record of each
			for (Xenfish.Way w : c.xenfish.obsidianWays()) {
				String started = switch (w.key()) {
					case "obsidian:village" -> c.visits.beginKind("village");
					case "obsidian:portal" -> c.visits.beginKind("ruined portal");
					default -> c.nether.go("table", 0);
				};
				if (started != null && started.startsWith("You will")) {
					c.xenfish.trying(w.key());
					return started + " (its plan: obsidian from " + w.says() + ")";
				}
			}
			String obsidian = c.nether.go("table", 0);
			if (obsidian.startsWith("You will")) {
				c.xenfish.trying("obsidian:lava");
				return obsidian;
			}
		}
		if (!tabled && it.getOrDefault("book", 0) < 1) return planFor("book", 1, "a book for the enchanting table");
		if (tabled && c.player.experienceLevel < 3) {                           // the table, no levels to pay with: coal ore gives them (and torches)
			String xp = c.chores.mine(c.lessons.depth("coal", 40), "coal", 16);
			if (xp != null && xp.startsWith("You will")) return xp + " (levels to enchant with)";
		}
		return c.nether.go("table", 0);
	}

	/** The next step of its plan for this (the recipe tree works out what's missing): started, "You will ...", or why not. */
	private String planFor(String item, int n, String why) {
		if (!item.equals(c.plans.goal)) c.plans.setGoal(item, n, null);
		Plans.Step s = c.plans.advice();
		if (s == null) return "no way to " + item.replace('_', ' ') + " it knows of";
		if (c.plans.start(s.option)) return "You will " + s.says() + " (" + why + ").";
		return "can't now: " + s.says();
	}

	/** Still on it? (Its chore, its build, its trip, its enchanting.) */
	boolean busy() {
		if (task == Task.SLEEP) {                                                 // (the night in its bed; half a minute and not asleep, something else: no room for it, monsters about)
			if (c.player.isSleeping()) return true;
			long day = Compat.timeOfDay(c.player.level()) % 24000;
			boolean bedTime = day >= 12600 && day <= 23400;                          // (when a bed can be slept in)
			if (bedTime && sleepFrom < 0) sleepFrom = now();
			if (!bedTime && !c.goals.evening() || sleepFrom >= 0 && now() - sleepFrom > 20 * 30) {
				sleepFrom = -1;
				notBefore.put(Task.SLEEP, now() + 20 * 60 * 2);
				return false;
			}
			return true;
		}
		if (task == Task.EXPLORE) return now() - taskAt < 20 * 45 && !(c.player.level().isDarkOutside() && c.player.level().canSeeSky(c.player.blockPosition().above()));   // (a look around: most of a minute; out under the night sky it stops)
		return task != Task.NONE && (c.chores.busy() || c.builder.busy() || c.nether.busy() || c.enchanter.on || (task == Task.VISIT || task == Task.TABLE) && c.visits.visiting()
				|| task == Task.STASH && c.storage.busy() || task == Task.FOOD && (c.fisher.on || c.visits.visiting()));
	}

	/** Every tick: what hurt it while it was on its task (for its learning). */
	void tick() {
		if (c.player == null) return;
		float h = c.player.getHealth();
		if (task != Task.NONE && h < lastHealth && c.player.isAlive()) hurtSince += lastHealth - h;
		lastHealth = h;
	}

	private float lastHealth = 20;
	/** The task it last dropped as soon as it began, and how many times straight. */
	private Task blipTask;
	private int blips;

	/** Something on it that goes in the chest. */
	private boolean stashable() {
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			var st = inv.getItem(i);
			if (!st.isEmpty() && leaves(c.itemKey(st), st.getCount())) return true;
		}
		return false;
	}

	/** Is this what its goal is waiting on (diamonds for its gear, the table and enchanting, blaze rods and pearls)? */
	private boolean forGoal(Task t) {
		return switch (t) {
			case DIAMONDS, IRON, TOOLS -> goal.equals("gear");
			case TABLE, ENCHANT -> goal.equals("enchant");
			case VISIT -> goal.equals("enchant") || goal.equals("gear");
			case BLAZE, PEARLS -> goal.equals("nether");
			default -> false;
		};
	}

	/** Its task is over: the outcome goes to its learning, and the next one is its to choose. */
	private void finish(Obs o) {
		if (busy()) return;
		double seconds = (now() - taskAt) / 20.0;
		boolean died = c.genDeaths > deathsAt;
		double gained = worth() - worthAt;
		double reward = gained * 0.05 + (o.tier - tierAt) * 20 - hurtSince - (died ? 40 : 0) - seconds / 120.0;
		boolean ok = !died && (task == Task.COAL ? o.fuel > fuelAt : gained > 0 || o.tier > tierAt || task == Task.SHELTER || task == Task.EXPLORE || task == Task.ENCHANT && enchantedSomething()
				|| task == Task.SMELT && (o.iron > ironAt || c.chores.droppedAt >= taskAt)
				|| task == Task.SMELTER && c.chores.smelterAt() != null
				|| task == Task.TABLE && (enchantStuff() > enchantStuffAt || c.player.experienceLevel > levelAt)
				|| task == Task.TOOLS && c.crafter.count(n -> n.equals("iron_pickaxe") || n.equals("diamond_pickaxe")) >= 2
				|| task == Task.BLAZE && rods() > rodsAt || task == Task.PEARLS && pearls() > pearlsAt) && !dropped;
		dropped = false;
		if (task == Task.TABLE && c.xenfish.trying() != null) c.xenfish.settle();   // (the way it was on for its obsidian: how it went, to its book)
		boolean blip = seconds < 10 && !died && gained == 0 && o.tier == tierAt && hurtSince == 0;   // (on and off in a moment, nothing happened: no lesson; the enchanting loop wrote 849 of these, two seconds each, all "worked")
		if (!blip) c.mod.btbLearner.learn(stateAt, task.name(), (float) reward, o.key(), ok, seconds, died);
		blips = blip && task == blipTask ? blips + 1 : blip ? 1 : 0;
		blipTask = blip ? task : null;
		if (blips >= 2 && task != Task.SHELTER && task != Task.SLEEP && task != Task.FOOD) {   // (never a roof, a bed or food: done at once there is done, not dithering)                                                         // the same task begun and dropped twice straight, nothing done: not a third time this minute (it does, not reconsiders: Ann went table, look round, table every twenty seconds)
			notBefore.put(task, now() + 20 * 60);
			c.journal("thinks", "btb: " + task.name().toLowerCase(Locale.ROOT) + " twice begun and dropped at once: leaves it a minute");
			blips = 0;
			blipTask = null;
		}
		c.journal("learns", String.format(Locale.ROOT, "btb: %s %s (%.0f s, reward %+.1f%s)", task.name().toLowerCase(Locale.ROOT),
				ok ? "worked" : "came to nothing", seconds, reward, died ? ", died" : ""));
		boolean mining = task == Task.IRON || task == Task.COAL || task == Task.DIAMONDS;
		if (mining) quickFails = !ok && !died && seconds < 90 ? quickFails + 1 : 0;
		if (quickFails >= 2) {                                                    // twice straight out of its mine with nothing: a new one, from somewhere else
			quickFails = 0;
			c.places.forget("mine");
			c.places.forget("mine base");
			c.places.forget("mine head");
			c.journal("thinks", "btb: its mine keeps coming to nothing: a new one next time");
		}
		if (!ok) notBefore.put(task, now() + (forGoal(task) ? 20 * 8 : 20 * 40));   // (not the same thing again straight away; what its goal needs, again in a few seconds: no giving up)
		task = Task.NONE;
		why = "";
		state = State.REST;
	}

	/** What its bag is worth (to a player at this stage of the game): what it has of what it wants, the later things more. */
	private double worth() {
		double w = 0;
		for (var e : c.items().entrySet()) {
			Worth.Use u = Worth.ALL.get(e.getKey());
			if (u != null) w += (1 + 3 * u.from()) * Math.min(e.getValue(), u.want());
		}
		return w;
	}

	/** For /xen status. */
	String describe() {
		return task == Task.NONE ? "btb: " + state.name().toLowerCase(Locale.ROOT) + ", goal " + goal
				: "btb: " + task.name().toLowerCase(Locale.ROOT) + " (" + why + "), goal " + goal;
	}
}
