package xen.mod;

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
	enum Task { NONE, WOOD, STONE, COAL, IRON, SMELT, FOOD, SHELTER, DIAMONDS, ENCHANT, TABLE, HOME, EXPLORE }

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
	private int deathsAt, tierAt;
	private double worthAt;
	private final Map<Task, Long> notBefore = new EnumMap<>(Task.class);
	/** The longer goal (its DMM's call): "gear" (tools, iron, diamonds, armor), "home", "enchant", "world". */
	String goal = "gear";
	private long goalAt = -100000;

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
				items.getOrDefault("diamond", 0), items.getOrDefault("log", 0), items.getOrDefault("cobblestone", 0), c.chores.sheltered(),
				p.level().dimension() == Level.OVERWORLD);
	}

	// ------------------------------------------------------------------------------------------------ the longer goal (DMM)
	/** Now and then (or when the one it had is done): what it is after for the next while. */
	private void thinkGoal(Obs o) {
		if (now() - goalAt < 20 * 60 * 3 && !goalDone(o)) return;
		goalAt = now();
		String was = goal;
		boolean geared = o.tier >= 4 && c.goals.diamondsWanted() == 0;
		boolean tabled = c.enchanter.table() != null || c.crafter.has("enchanting_table");
		if (!geared) goal = "gear";                                              // tools, iron, diamonds, armor: first, always
		else if (!tabled || !enchantedSomething()) goal = "enchant";
		else if (c.goals.home == null && c.mod.config.wants) goal = "home";
		else goal = "world";
		if (!goal.equals(was)) c.journal("thinks", "its goal for now: " + goal + (was.isEmpty() ? "" : " (was " + was + ")"));
	}

	private boolean goalDone(Obs o) {
		return switch (goal) {
			case "gear" -> o.tier >= 4 && c.goals.diamondsWanted() == 0;
			case "enchant" -> enchantedSomething();
			case "home" -> c.goals.home != null;
			default -> false;
		};
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
		boolean dusk = o.night && !o.under && o.overworld;
		if (dusk && !o.sheltered) out.add(new Option(Task.SHELTER, 5, "night, out in the open: a roof or a hole first"));
		if (o.meals < 2 && o.food < 18) out.add(new Option(Task.FOOD, o.food < 10 ? 4.5f : 2.5f, "little to eat"));
		if (o.raw >= 3 && (o.fuel > 0 || o.logs > 0)) out.add(new Option(Task.SMELT, 4, "raw iron to smelt"));
		if (o.tier == 0) out.add(new Option(Task.WOOD, 4, "wood for a table and a pickaxe"));
		if (o.tier == 1) out.add(new Option(Task.STONE, 4, "stone for stone tools and a furnace"));
		if (o.tier >= 1 && o.raw >= 3 && o.fuel == 0 && o.logs == 0) out.add(new Option(Task.COAL, 4, "iron and nothing to smelt it with"));
		if (o.tier == 2 && o.iron + o.raw < 3) out.add(new Option(Task.IRON, 3.8f, "iron for an iron pickaxe"));
		if (goal.equals("gear") && o.tier >= 3) {
			boolean diamonds = c.goals.readyForDiamonds() || o.iron + o.raw >= 24;
			if (!diamonds) out.add(new Option(Task.IRON, 3.5f, "iron for armor and a shield"));
			else if (o.diamonds < c.goals.diamondsWanted()) out.add(new Option(Task.DIAMONDS, 3.5f, "diamonds (a diamond pickaxe, a sword, armor)"));
		}
		if (goal.equals("enchant")) {
			if (c.enchanter.possible()) out.add(new Option(Task.ENCHANT, 4.2f, "the makings of an enchantment: its gear enchanted"));
			else out.add(new Option(Task.TABLE, 3.2f, "an enchanting table (a book, four obsidian)"));
		}
		if (goal.equals("home") && !o.night) out.add(new Option(Task.HOME, 3, "a home"));
		out.add(new Option(Task.EXPLORE, 0.5f, "nothing pressing: a look around"));
		if (o.night && o.overworld && !o.under) {                                // dark out: only what's done under a roof or under the ground
			out.removeIf(op -> switch (op.task) {
				case SHELTER, SMELT, ENCHANT, COAL, IRON, DIAMONDS -> false;
				case STONE -> !o.sheltered;
				default -> true;
			});
		}
		return out;
	}

	/** Its next task (or null to let its other habits have the moment). */
	Action next() {
		if (c.player == null || !c.player.isAlive()) return null;
		Obs o = observe();
		if (task != Task.NONE) finish(o);                                         // (its chore ended: how did that go?)
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
		if (best == null) return null;
		String plan = start(best.task, o);
		if (plan == null || !plan.startsWith("You will")) {                      // (it can't now: not again for a minute)
			notBefore.put(best.task, now() + 20 * 60);
			if (plan != null) c.journal("thinks", "can't " + best.task.name().toLowerCase(Locale.ROOT) + " now: " + plan);
			if (best.task == Task.EXPLORE) return c.goals.exploreStep();
			return null;
		}
		task = best.task;
		why = best.why;
		state = best.task == Task.SHELTER || best.task == Task.FOOD ? State.SURVIVE : best.task == Task.EXPLORE ? State.EXPLORE : State.WORK;
		taskAt = now();
		stateAt = key;
		hurtSince = 0;
		deathsAt = c.genDeaths;
		tierAt = o.tier;
		worthAt = worth();
		c.journal("thinks", String.format(Locale.ROOT, "btb: %s (%s; goal %s)", task.name().toLowerCase(Locale.ROOT), why, goal));
		if (task == Task.EXPLORE) return c.goals.exploreStep();
		return null;                                                                 // (its chore starts next tick)
	}

	/** Sets the task going with the muscle memory it has: "You will ..." if it could. */
	private String start(Task t, Obs o) {
		Chores ch = c.chores;
		return switch (t) {
			case WOOD -> ch.gather("wood", o.tier == 0 ? 5 : 10);
			case STONE -> ch.gather("stone", 16);
			case COAL -> ch.mine(c.lessons.depth("coal", 40), "coal", 8);
			case IRON -> ch.mine(c.lessons.depth("iron", 16), "iron", o.tier == 2 ? 3 : 10);
			case DIAMONDS -> ch.mine(c.lessons.depth("diamonds", -58), "diamonds", Math.max(3, c.goals.diamondsWanted() - o.diamonds));
			case SMELT -> ch.smelt();
			case FOOD -> ch.hunt(3);
			case SHELTER -> o.under ? ch.digIn() : ch.shelter();
			case ENCHANT -> c.enchanter.start();
			case TABLE -> c.nether.go("table", 0);
			case HOME -> c.builder.start("home");
			case EXPLORE -> "You will look around.";
			case NONE -> null;
		};
	}

	/** Still on it? (Its chore, its build, its trip, its enchanting.) */
	boolean busy() {
		return task != Task.NONE && (c.chores.busy() || c.builder.busy() || c.nether.busy() || c.enchanter.on);
	}

	/** Every tick: what hurt it while it was on its task (for its learning). */
	void tick() {
		if (c.player == null) return;
		float h = c.player.getHealth();
		if (task != Task.NONE && h < lastHealth && c.player.isAlive()) hurtSince += lastHealth - h;
		lastHealth = h;
	}

	private float lastHealth = 20;

	/** Its task is over: the outcome goes to its learning, and the next one is its to choose. */
	private void finish(Obs o) {
		if (busy()) return;
		double seconds = (now() - taskAt) / 20.0;
		boolean died = c.genDeaths > deathsAt;
		double gained = worth() - worthAt;
		double reward = gained * 0.05 + (o.tier - tierAt) * 20 - hurtSince - (died ? 40 : 0) - seconds / 120.0;
		boolean ok = !died && (gained > 0 || o.tier > tierAt || task == Task.SHELTER || task == Task.EXPLORE || task == Task.ENCHANT && enchantedSomething());
		c.mod.btbLearner.learn(stateAt, task.name(), (float) reward, o.key(), ok, seconds, died);
		c.journal("learns", String.format(Locale.ROOT, "btb: %s %s (%.0f s, reward %+.1f%s)", task.name().toLowerCase(Locale.ROOT),
				ok ? "worked" : "came to nothing", seconds, reward, died ? ", died" : ""));
		if (!ok) notBefore.put(task, now() + 20 * 40);                            // (not the same thing again straight away)
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
