package xen.mod;

import com.google.gson.JsonObject;
import xen.mod.core.Mind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Know-how and plans: how things are made and where they come from, and from that, a plan for something it was never
 * shown how to get. The know-how is the game's own crafting recipes (whatever the version or data pack), what a furnace
 * makes of what, and a few hints a player picks up ("iron ore wants a stone pickaxe", "sand: beaches and rivers",
 * "string: spiders, at night"). Asked for a bucket, or wanting one, it works back from what it has: a bucket is three
 * iron ingots; ingots come out of a furnace from raw iron, with fuel; the furnace is eight cobblestone; raw iron is
 * mined with a stone pickaxe, which it has... and the plan is the steps in the order they can be done. Where there are
 * two ways (coal or planks to burn), it takes the one that has cost it least, by what each kind of step has cost it so
 * far, in any plan: what it learns getting iron for a bucket goes into the plan for a shield.
 * <p>The plan is advice, not an order: its next step weighs in with its other choices (how much: its diligence, and how
 * its plans have worked out), and its mind still decides. Whatever it does, the plan is worked out again from what it
 * has now, so a step it skipped, a thing it found or lost, a way that failed, all just change the plan.
 */
final class Plans {
	enum Kind { GATHER, FETCH, HUNT, CRAFT, SMELT, LOOK }

	/** One step: what kind, the thing it gives, how many, its detail (the intent, the block, what goes in the furnace), in words. */
	static final class Step {
		final Kind kind;
		final String item, arg, how;
		int count;
		final int option;

		Step(Kind kind, String item, int count, String arg, String how, int option) {
			this.kind = kind;
			this.item = item;
			this.count = count;
			this.arg = arg;
			this.how = how;
			this.option = option;
		}

		String key() {
			return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + item;
		}

		String says() {
			String thing = item.replace('_', ' ');
			return switch (kind) {
				case CRAFT -> "craft " + (count == 1 ? "a " + thing : count + " " + Crafter.plural(thing, count));
				case SMELT -> "smelt " + count + " " + arg.replace('_', ' ') + " into " + Crafter.plural(thing, count);
				default -> how + " (" + count + " " + Crafter.plural(thing, count) + ")";
			};
		}
	}

	/** Where a raw thing comes from: the kind of step, the intent or block, the eyes' kind, the pickaxe tier it takes, minutes each, minutes to get going. */
	private record Source(Kind kind, String arg, String eyes, int tier, float each, float setup, String how) {}

	private static final Map<String, List<Source>> SOURCES = new LinkedHashMap<>();
	/** What a furnace makes, and of what. */
	static final Map<String, String> SMELTS = new LinkedHashMap<>();

	private static void src(String item, Kind kind, String arg, String eyes, int tier, float each, float setup, String how) {
		SOURCES.computeIfAbsent(item, k -> new ArrayList<>()).add(new Source(kind, arg, eyes, tier, each, setup, how));
	}

	static {
		src("log", Kind.GATHER, "wood", "log", 0, 0.25f, 0.3f, "chop a tree");
		src("cobblestone", Kind.GATHER, "stone", null, 1, 0.08f, 0.2f, "mine stone with a pickaxe");
		src("dirt", Kind.GATHER, "dirt", null, 0, 0.05f, 0.1f, "dig dirt");
		src("coal", Kind.GATHER, "coal", "coal", 1, 0.3f, 1.5f, "mine coal ore");
		src("raw_iron", Kind.GATHER, "iron", "iron", 2, 0.6f, 3f, "mine iron ore with a stone pickaxe (most around y 16)");
		src("raw_gold", Kind.FETCH, "gold_ore", "gold", 3, 1.5f, 6f, "mine gold ore with an iron pickaxe");
		src("diamond", Kind.FETCH, "diamond_ore", "diamond", 3, 3f, 12f, "mine diamond ore with an iron pickaxe, deep down");
		src("redstone", Kind.FETCH, "redstone_ore", "redstone", 3, 0.5f, 8f, "mine redstone ore with an iron pickaxe, deep down");
		src("lapis_lazuli", Kind.FETCH, "lapis_ore", "lapis", 2, 0.4f, 8f, "mine lapis ore with a stone pickaxe");
		src("raw_copper", Kind.FETCH, "copper_ore", "copper", 2, 0.3f, 3f, "mine copper ore with a stone pickaxe");
		src("emerald", Kind.FETCH, "emerald_ore", "emerald", 3, 3f, 15f, "mine emerald ore in the mountains (or trade for it)");
		src("sand", Kind.FETCH, "sand", "sand", 0, 0.1f, 2f, "dig sand (beaches, deserts, rivers)");
		src("gravel", Kind.FETCH, "gravel", "gravel", 0, 0.1f, 2f, "dig gravel");
		src("flint", Kind.FETCH, "gravel", "gravel", 0, 0.8f, 2f, "dig gravel till flint drops");
		src("clay_ball", Kind.FETCH, "clay", "clay", 0, 0.1f, 4f, "dig clay (rivers, swamps)");
		src("obsidian", Kind.FETCH, "obsidian", "obsidian", 4, 0.5f, 6f, "mine obsidian with a diamond pickaxe");
		src("sugar_cane", Kind.FETCH, "sugar_cane", "sugar_cane", 0, 0.1f, 3f, "pick sugar cane by the water");
		src("pumpkin", Kind.FETCH, "pumpkin", "pumpkin", 0, 0.2f, 5f, "pick a pumpkin");
		src("melon_slice", Kind.FETCH, "melon", "melon", 0, 0.1f, 5f, "break a melon");
		src("wool", Kind.HUNT, "wool", null, 0, 0.8f, 2f, "shear or kill a sheep");
		src("leather", Kind.HUNT, "leather", null, 0, 1f, 2f, "hunt a cow");
		src("feather", Kind.HUNT, "feather", null, 0, 1f, 2f, "hunt a chicken");
		for (String meat : new String[] {"beef", "porkchop", "chicken", "mutton"}) src(meat, Kind.HUNT, "food", null, 0, 0.8f, 2f, "hunt animals");
		src("string", Kind.LOOK, null, null, 0, 2f, 8f, "spiders drop it (at night), cobwebs too");
		src("bone", Kind.LOOK, null, null, 0, 2f, 8f, "skeletons drop them");
		src("gunpowder", Kind.LOOK, null, null, 0, 3f, 8f, "creepers drop it");
		src("slime_ball", Kind.LOOK, null, null, 0, 3f, 15f, "slimes drop them (swamps, deep slime chunks)");
		src("ender_pearl", Kind.LOOK, null, null, 0, 4f, 15f, "endermen drop them");
		src("blaze_rod", Kind.LOOK, null, null, 0, 4f, 30f, "blazes drop them, in the Nether");
		src("wheat", Kind.LOOK, null, null, 0, 1f, 10f, "grow it from seeds (a farm)");
		src("water_bucket", Kind.LOOK, null, null, 0, 0.5f, 2f, "fill a bucket at water");
		src("lava_bucket", Kind.LOOK, null, null, 0, 0.5f, 6f, "fill a bucket at lava");
		SMELTS.put("iron_ingot", "raw_iron");
		SMELTS.put("gold_ingot", "raw_gold");
		SMELTS.put("copper_ingot", "raw_copper");
		SMELTS.put("glass", "sand");
		SMELTS.put("stone", "cobblestone");
		SMELTS.put("smooth_stone", "stone");
		SMELTS.put("brick", "clay_ball");
		SMELTS.put("charcoal", "log");
		SMELTS.put("cooked_beef", "beef");
		SMELTS.put("cooked_porkchop", "porkchop");
		SMELTS.put("cooked_chicken", "chicken");
		SMELTS.put("cooked_mutton", "mutton");
		SMELTS.put("baked_potato", "potato");
	}

	/** How many one craft makes (the common ones; the crafter makes the real number, and the plan is worked out again after). */
	private static int makes(String id) {
		if (id.endsWith("_planks") || id.equals("stick") || id.equals("torch") || id.equals("bowl") || id.endsWith("_stairs")) return 4;
		if (id.equals("ladder") || id.endsWith("_fence") || id.equals("paper") || id.endsWith("_door")) return 3;
		if (id.endsWith("_slab")) return 6;
		if (id.equals("glass_pane") || id.equals("iron_bars") || id.equals("rail")) return 16;
		if (id.endsWith("_trapdoor") && !id.startsWith("iron")) return 2;
		return 1;
	}

	private static int tierOf(String pickaxe) {
		return pickaxe.startsWith("netherite") || pickaxe.startsWith("diamond") ? 4 : pickaxe.startsWith("iron") ? 3 : pickaxe.startsWith("stone") ? 2
				: pickaxe.endsWith("_pickaxe") ? 1 : 0;
	}

	private static final String[] PICKAXE = {null, "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe"};

	// ------------------------------------------------------------------------------------- planning
	/** What it has (and has already counted on) while it works a plan out. */
	private static final class Ctx {
		final Map<String, Integer> stock;
		int tier;
		boolean table, furnace;

		Ctx(Map<String, Integer> stock, int tier, boolean table, boolean furnace) {
			this.stock = stock;
			this.tier = tier;
			this.table = table;
			this.furnace = furnace;
		}

		Ctx copy() {
			return new Ctx(new HashMap<>(stock), tier, table, furnace);
		}

		void set(Ctx o) {
			stock.clear();
			stock.putAll(o.stock);
			tier = o.tier;
			table = o.table;
			furnace = o.furnace;
		}

		int take(String item, int n) {
			int have = stock.getOrDefault(item, 0), used = Math.min(have, n);
			if (used > 0) stock.put(item, have - used);
			return used;
		}
	}

	/** A plan: for what, how many, its steps in the order they can be done, roughly how long, and what it doesn't know how to get. */
	static final class Plan {
		final String goal;
		final int count;
		final List<Step> steps;
		final float minutes;
		final List<String> unknown;
		final boolean ok;

		Plan(String goal, int count, List<Step> steps, float minutes, List<String> unknown, boolean ok) {
			this.goal = goal;
			this.count = count;
			this.steps = steps;
			this.minutes = minutes;
			this.unknown = unknown;
			this.ok = ok;
		}
	}

	private final Companion c;
	private int budget;

	Plans(Companion c) {
		this.c = c;
	}

	/** What it has, by kind too: any planks count as "planks", any logs as "log", coal or charcoal as "coal"... */
	private Map<String, Integer> stock() {
		Map<String, Integer> s = new HashMap<>();
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			var st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
			int k = st.getCount();
			s.merge(n, k, Integer::sum);
			if (n.endsWith("_planks")) s.merge("planks", k, Integer::sum);
			else if (Crafter.isLog(n)) s.merge("log", k, Integer::sum);
			else if (n.endsWith("_wool")) s.merge("wool", k, Integer::sum);
			else if (n.equals("charcoal")) s.merge("coal", k, Integer::sum);
			else if (n.equals("cobbled_deepslate") || n.equals("blackstone")) s.merge("cobblestone", k, Integer::sum);
		}
		return s;
	}

	/** The plan for n of a thing, from what it has now. */
	Plan make(String item, int n) {
		budget = 4000;
		Ctx ctx = new Ctx(stock(), c.crafter.pickTier(), c.crafter.tableNear() != null, nearFurnace());
		if (ctx.stock.getOrDefault("crafting_table", 0) > 0) ctx.table = true;
		if (ctx.stock.getOrDefault("furnace", 0) > 0) ctx.furnace = true;
		List<Step> steps = new ArrayList<>();
		List<String> unknown = new ArrayList<>();
		float[] cost = {0};
		boolean ok = need(item, n, 0, ctx, steps, unknown, cost, new HashSet<>());
		return new Plan(item, n, merge(steps), cost[0], unknown, ok);
	}

	private boolean nearFurnace() {
		var level = c.player.level();
		var feet = c.player.blockPosition();
		for (var p : net.minecraft.core.BlockPos.betweenClosed(feet.offset(-12, -4, -12), feet.offset(12, 4, 12))) {
			if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.FURNACE)) return true;
		}
		return false;
	}

	/** Steps of the same kind for the same thing: one step (where the first was: it does it all then). */
	private static List<Step> merge(List<Step> steps) {
		Map<String, Step> byKey = new LinkedHashMap<>();
		for (Step s : steps) {
			Step had = byKey.get(s.key());
			if (had == null) byKey.put(s.key(), new Step(s.kind, s.item, s.count, s.arg, s.how, s.option));
			else had.count += s.count;
		}
		return new ArrayList<>(byKey.values());
	}

	/** n of item: what it has first, then the cheapest way it knows for the rest (its steps added). False: no way it knows. */
	private boolean need(String item, int n, int depth, Ctx ctx, List<Step> steps, List<String> unknown, float[] cost, Set<String> doing) {
		n -= ctx.take(item, n);
		if (n <= 0) return true;
		if (depth > 10 || doing.contains(item) || --budget < 0) return false;
		doing.add(item);
		Ctx bestCtx = null;
		List<Step> bestSteps = null;
		List<String> bestUnknown = null;
		float bestCost = Float.MAX_VALUE;
		for (int way = 0; way < 3; way++) {                                  // the ways it knows: a source (gather, fetch...), a furnace, a recipe
			List<Source> sources = way == 0 ? SOURCES.get(item) : null;
			int alts = way == 0 ? (sources == null ? 0 : sources.size()) : 1;
			for (int a = 0; a < alts; a++) {
				Ctx k = ctx.copy();
				List<Step> s = new ArrayList<>();
				List<String> u = new ArrayList<>();
				float[] kc = {0};
				boolean ok = switch (way) {
					case 0 -> fromSource(item, n, sources.get(a), depth, k, s, u, kc, doing);
					case 1 -> fromFurnace(item.equals("coal") ? "charcoal" : item, n, depth, k, s, u, kc, doing);   // (charcoal does for coal)
					default -> fromRecipe(item, n, depth, k, s, u, kc, doing);
				};
				if (ok && kc[0] < bestCost) {
					bestCost = kc[0];
					bestCtx = k;
					bestSteps = s;
					bestUnknown = u;
				}
			}
		}
		doing.remove(item);
		if (bestCtx == null) {
			if (!unknown.contains(item)) unknown.add(item);
			return false;
		}
		ctx.set(bestCtx);
		steps.addAll(bestSteps);
		for (String x : bestUnknown) if (!unknown.contains(x)) unknown.add(x);
		cost[0] += bestCost;
		return true;
	}

	private boolean fromSource(String item, int n, Source src, int depth, Ctx ctx, List<Step> steps, List<String> unknown, float[] cost, Set<String> doing) {
		if (src.tier > ctx.tier) {                                           // the pickaxe it takes, first
			if (!need(PICKAXE[src.tier], 1, depth + 1, ctx, steps, unknown, cost, doing)) return false;
			ctx.tier = src.tier;
		}
		int option = switch (src.kind) {
			case GATHER -> src.arg.equals("wood") ? Mind.WOOD : src.arg.equals("stone") || src.arg.equals("dirt") ? Mind.STONE : Mind.MINE;
			case FETCH -> src.arg.endsWith("_ore") ? Mind.MINE : Mind.EXPLORE;
			case HUNT -> Mind.FOOD;
			default -> Mind.EXPLORE;
		};
		String how = src.how;
		float k = learnedCost("" + src.kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + item, n, src.setup, src.each);
		if (src.kind == Kind.FETCH && src.eyes != null && c.eyes.count(src.eyes) == 0) {   // it hasn't seen any: it has to find some first
			Object[] b = c.biomes.nearestWith(item);
			if (b != null) {                                                    // (a biome it saw has it: there)
				how = "go to the " + ((String) b[1]).replace('_', ' ') + " it saw and " + how;
				k += 0.5f * src.setup + (float) Math.sqrt(((net.minecraft.core.BlockPos) b[0]).distSqr(c.player.blockPosition())) / 200f;
			} else {
				how = "look for " + src.arg.replace("_ore", " ore").replace('_', ' ') + " and " + how;
				k += 2 * src.setup;
			}
		}
		if (src.kind == Kind.LOOK) k *= 3;                                   // (it knows where it comes from, not how to go about it yet)
		steps.add(new Step(src.kind, item, n, src.arg, how, option));
		cost[0] += k;
		return true;
	}

	private boolean fromFurnace(String item, int n, int depth, Ctx ctx, List<Step> steps, List<String> unknown, float[] cost, Set<String> doing) {
		String input = SMELTS.get(item);
		if (input == null) return false;
		if (!need(input, n, depth + 1, ctx, steps, unknown, cost, doing)) return false;
		int coal = (n + 7) / 8, planks = (int) Math.ceil(n / 1.5);           // fuel: coal smelts 8, a plank one and a half
		Ctx byCoal = ctx.copy();
		List<Step> s1 = new ArrayList<>();
		List<String> u1 = new ArrayList<>();
		float[] c1 = {0};
		boolean coalOk = !item.equals("charcoal") && need("coal", coal, depth + 1, byCoal, s1, u1, c1, doing);
		Ctx byPlanks = ctx.copy();
		List<Step> s2 = new ArrayList<>();
		List<String> u2 = new ArrayList<>();
		float[] c2 = {0};
		boolean planksOk = need("planks", planks, depth + 1, byPlanks, s2, u2, c2, doing);
		if (!coalOk && !planksOk) return false;
		boolean useCoal = coalOk && (!planksOk || c1[0] <= c2[0]);
		ctx.set(useCoal ? byCoal : byPlanks);
		steps.addAll(useCoal ? s1 : s2);
		cost[0] += useCoal ? c1[0] : c2[0];
		if (!ctx.furnace) {
			if (!need("furnace", 1, depth + 1, ctx, steps, unknown, cost, doing)) return false;
			ctx.furnace = true;
		}
		steps.add(new Step(Kind.SMELT, item, n, input, "smelt " + input.replace('_', ' '), Mind.SMELT));
		cost[0] += learnedCost("smelt:" + item, n, 0.5f, 0.17f);
		return true;
	}

	private boolean fromRecipe(String item, int n, int depth, Ctx ctx, List<Step> steps, List<String> unknown, float[] cost, Set<String> doing) {
		String id = c.crafter.recipeIdFor(item);
		if (id == null) return false;
		Map<String, Integer> ings = c.crafter.ingredients(id);
		if (ings == null || ings.isEmpty()) return false;
		int out = makes(id), times = (n + out - 1) / out;
		if (c.crafter.needsTable(id) && !ctx.table) {
			if (!need("crafting_table", 1, depth + 1, ctx, steps, unknown, cost, doing)) return false;
			ctx.table = true;
		}
		for (var e : ings.entrySet()) if (!need(e.getKey(), e.getValue() * times, depth + 1, ctx, steps, unknown, cost, doing)) return false;
		steps.add(new Step(Kind.CRAFT, id, times * out, id, "craft " + id.replace('_', ' '), Mind.CRAFT));
		if (times * out > n) ctx.stock.merge(item, times * out - n, Integer::sum);   // (what's left over counts for later steps)
		if (id.endsWith("_pickaxe")) ctx.tier = Math.max(ctx.tier, tierOf(id));
		cost[0] += learnedCost("craft:" + id, times, 0.1f, 0.05f);
		return true;
	}

	// ------------------------------------------------------------------------------- what it learns
	/** Per kind of step ("gather:raw_iron", "smelt:iron_ingot"): times, times it came to nothing, minutes for each one. In any plan. */
	final Map<String, float[]> learned = new HashMap<>();
	/** How far it goes by its plans, 0.1 to 1 (how its steps have worked out). */
	float trust = 0.6f;

	private float learnedCost(String key, int n, float setup, float each) {
		float[] l = learned.get(key);
		if (l == null || l[0] < 1) return setup + each * n;
		float failRate = l[1] / l[0];
		return (0.5f * setup + l[2] * n) * (1 + 1.5f * failRate);
	}

	// ----------------------------------------------------------------------------- its goal and its plan
	String goal;
	int goalCount;
	/** Who asked for it (null: its own idea). */
	String askedBy;
	private Plan plan;
	private long plannedAt = -10000, goalAt, ownGoalAt = -100000;
	private int stockHash;
	/** The step it started (its choice matched the plan), what it had of that thing then, and when. */
	private Step started;
	private int startedHad;
	private long startedAt;
	private boolean saidStuck;

	/** A goal for it (asked, or its own): it works out the plan and says it, in words (to itself: "You will..."). */
	String setGoal(String item, int n, String who) {
		goal = item;
		goalCount = Math.max(1, n);
		askedBy = who;
		goalAt = c.player.level().getGameTime();
		saidStuck = false;
		plan = make(item, goalCount);
		plannedAt = goalAt;
		stockHash = stock().hashCode();
		c.journal("thinks", "makes a plan for " + thing(goal, goalCount) + (who == null ? " (its own idea)" : " (" + who + " asked)") + ": " + steps(plan));
		if (!plan.ok) return "You don't know how to get " + thing(goal, goalCount) + " yet: you don't know where to get " + String.join(", ", plan.unknown) + ".";
		if (plan.steps.isEmpty()) return "You already have " + thing(goal, goalCount) + ".";
		return "You will get " + thing(goal, goalCount) + ": " + steps(plan) + ".";
	}

	private static String thing(String item, int n) {
		String t = item.replace('_', ' ');
		return n == 1 ? (t.matches("^[aeiou].*") ? "an " : "a ") + t : n + " " + Crafter.plural(t, n);
	}

	private static String steps(Plan p) {
		if (!p.ok && p.steps.isEmpty()) return "no way it knows";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < p.steps.size(); i++) sb.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(p.steps.get(i).says());
		return sb + String.format(java.util.Locale.ROOT, " (about %.0f min)", Math.max(1, p.minutes));
	}

	/** The step its plan says is next, as it stands (null: none), without working anything out. */
	Step peek() {
		return plan == null || plan.steps.isEmpty() ? null : plan.steps.get(0);
	}

	/**
	 * The step its plan says is next (null: no goal, or no way it knows): worked out again when what it has changed, or
	 * every half minute. Done: the goal's met, and it says so.
	 */
	Step advice() {
		if (c.player == null) return null;
		long now = c.player.level().getGameTime();
		if (goal == null) {
			if (now - ownGoalAt < 2400) return null;
			ownGoalAt = now;
			String want = ownGoal();
			if (want == null) return null;
			int star = want.indexOf('*');                                         // ("lapis_lazuli*3": how many)
			setGoal(star < 0 ? want : want.substring(0, star), star < 0 ? 1 : Integer.parseInt(want.substring(star + 1)), null);
		}
		int h = stock().hashCode();
		if (plan == null || h != stockHash || now - plannedAt > 600) {
			plan = make(goal, goalCount);
			plannedAt = now;
			stockHash = h;
		}
		if (plan.ok && plan.steps.isEmpty()) {                               // it has it
			c.journal("does", String.format(java.util.Locale.ROOT, "has %s: its plan worked out (%.1f min)", thing(goal, goalCount), (now - goalAt) / 1200f));
			if (askedBy != null) c.chatter("Got " + thing(goal, goalCount).replaceFirst("^an? ", "the ") + "!", true);
			trust = Math.min(1f, trust + 0.05f);
			goal = null;
			plan = null;
			return null;
		}
		if (!plan.ok) {
			if (!saidStuck) {
				saidStuck = true;
				c.journal("thinks", "can't see a way to get " + thing(goal, goalCount) + " yet: doesn't know where to get " + String.join(", ", plan.unknown));
			}
			if (askedBy == null && now - goalAt > 20 * 60 * 10) goal = null;  // (its own idea: it lets it go for now)
			return null;
		}
		if (askedBy == null && now - goalAt > 20L * 60 * 40) {              // its own idea, forty minutes on: it lets it go for now
			c.journal("thinks", "lets " + thing(goal, goalCount) + " go for now (it's taking too long)");
			goal = null;
			return null;
		}
		return plan.steps.get(0);
	}

	/** How much its plan weighs in with its choices: its diligence and how its plans have worked out (an asked plan, more). */
	float weight() {
		float w = (0.6f + 0.9f * c.personality.diligence) * trust * (askedBy != null ? 1.4f : 1f);
		Step next = plan == null || plan.steps.isEmpty() ? null : plan.steps.get(0);
		float[] l = next == null ? null : learned.get(next.key());
		if (l != null && l[0] >= 3) w *= 1 - 0.7f * l[1] / l[0];              // (a kind of step that keeps coming to nothing: it pushes it less, and does other things)
		return w;
	}

	/** What it wants to make itself, now and then, from what it has (null: nothing in particular). Its nature puts them in order. */
	private String ownGoal() {
		var s = stock();
		int tier = c.crafter.pickTier();
		if (tier < 2) return null;                                           // (its first tools are the usual plan's, quickest)
		List<String> wants = new ArrayList<>();
		boolean fighter = c.personality.bravery + c.personality.sin(xen.mod.core.Sins.WRATH) > 1f;
		if (tier < 3 && !fighter) wants.add("iron_pickaxe");
		if (s.getOrDefault("shield", 0) == 0) wants.add("shield");
		if (s.getOrDefault("iron_sword", 0) + s.getOrDefault("diamond_sword", 0) == 0) wants.add(fighter ? 0 : wants.size(), "iron_sword");
		if (tier < 3 && fighter) wants.add("iron_pickaxe");
		if (s.getOrDefault("bucket", 0) + s.getOrDefault("water_bucket", 0) == 0) wants.add("bucket");
		if (tier >= 3 && s.getOrDefault("iron_chestplate", 0) + s.getOrDefault("diamond_chestplate", 0) == 0) wants.add("iron_chestplate");
		if (wants.isEmpty() && tier >= 3) {                                  // then like a player: a diamond pickaxe, the enchanting table, lapis for it
			boolean table = c.enchanter.table() != null || s.getOrDefault("enchanting_table", 0) > 0;
			if (tier < 4) wants.add("diamond_pickaxe");
			else if (!table && s.getOrDefault("book", 0) == 0) wants.add("book");   // (the book first: the obsidian may be a Nether trip's, and a plan with it unknown gets nowhere)
			else if (s.getOrDefault("lapis_lazuli", 0) < 3) wants.add("lapis_lazuli*3");
			else if (!table) wants.add("enchanting_table");
		}
		return wants.isEmpty() ? null : wants.get(0);
	}

	// ---------------------------------------------------------------- doing a step (when its mind chose it)
	/** Its mind chose what the plan's next step is: that step it starts (true), else what the choice usually does (false). */
	boolean start(int option) {
		Step s = plan == null ? null : plan.steps.isEmpty() ? null : plan.steps.get(0);
		if (s == null || s.option != option || goal == null) return false;
		boolean ok = switch (s.kind) {
			case GATHER -> {
				if ((s.arg.equals("iron") || s.arg.equals("coal")) && c.eyes.count(s.arg) == 0) {   // no ore in sight: its own way of mining (caves it knows, the depth it believes) does it
					started = s;
					startedHad = stock().getOrDefault(s.item, 0);
					startedAt = c.player.level().getGameTime();
					byItsWay = true;
					yield false;
				}
				String r = c.chores.gather(s.arg, s.count);
				yield r.startsWith("You will") || c.chores.busy();
			}
			case FETCH -> {
				String block = s.arg;
				boolean ore = block.endsWith("_ore");
				String eyes = SOURCES.get(s.item).get(0).eyes();
				boolean go = c.chores.fetch(eyes, p -> ore ? p.equals(block) || p.endsWith("_" + block) : p.equals(block), s.item, s.count).startsWith("You will");
				if (!go && option == Mind.EXPLORE) {                                 // none in sight: to the biome it saw that has it (its exploring goes there)
					Object[] b = c.biomes.nearestWith(s.item);
					if (b != null) {
						c.goals.exploreToward((net.minecraft.core.BlockPos) b[0], ((String) b[1]).replace('_', ' '));
						c.journal("thinks", "no " + s.item.replace('_', ' ') + " in sight: the " + ((String) b[1]).replace('_', ' ') + " it saw should have some");
					}
				}
				yield go;
			}
			case CRAFT -> {
				c.crafter.orderRecipe(s.arg, s.count);
				yield c.crafter.hasOrder();
			}
			case SMELT -> {
				String input = s.arg;
				if (input.equals("log")) input = anyLog();
				yield input != null && c.chores.smelt(input).startsWith("You will");
			}
			case HUNT -> {
				if (s.item.equals("wool")) c.chores.forWool = true;
				yield false;                                                   // (the usual hunt does it)
			}
			default -> false;                                                  // (LOOK: it goes and looks about, the usual way)
		};
		if (!ok) return false;
		started = s;
		startedHad = stock().getOrDefault(s.item, 0);
		startedAt = c.player.level().getGameTime();
		c.journal("does", "a step of its plan for " + thing(goal, goalCount) + ": " + s.says());
		return true;
	}

	/** The step is done its own usual way (the choice's own start): the plan only watches how it goes. */
	private boolean byItsWay;

	/** Did its plan's step go to its own usual way of doing that? (Then it learns from how that went too.) */
	boolean takenItsWay(boolean begun) {
		boolean was = byItsWay;
		byItsWay = false;
		if (was && !begun) started = null;
		if (was && begun) c.journal("does", "a step of its plan for " + thing(goal, goalCount) + ", its own way: " + started.says());
		return was && begun;
	}

	/** Is the plan's step it started over (the chore or the crafting done)? */
	boolean stepOver() {
		return started != null && !c.chores.busy() && !c.crafter.hasOrder();
	}

	boolean stepGoing() {
		return started != null;
	}

	private String anyLog() {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (Crafter.isLog(n)) return n;
		}
		return null;
	}

	/** Its choice ended: if it was a step of its plan, how did it go (what it got for the time)? That's learned, for any plan. */
	void finished(boolean died) {
		if (started == null || c.player == null) return;
		Step s = started;
		started = null;
		int got = stock().getOrDefault(s.item, 0) - startedHad;
		float minutes = Math.max(0.1f, (c.player.level().getGameTime() - startedAt) / 1200f);
		boolean ok = !died && got > 0;
		float[] l = learned.computeIfAbsent(s.key(), k -> new float[] {0, 0, 0});
		float each = minutes / Math.max(1, got);
		l[2] = l[0] < 1 ? each : l[2] + 0.3f * (each - l[2]);
		l[0]++;
		if (!ok) l[1]++;
		trust = Math.max(0.1f, Math.min(1f, trust + (ok ? 0.03f : -0.05f)));
		c.journal("learns", String.format(java.util.Locale.ROOT, "%s: %s (%.1f min; that kind of step: %d tries, %d came to nothing)", s.says(),
				ok ? "got " + got : died ? "died" : "came to nothing", minutes, (int) l[0], (int) l[1]));
	}

	// ------------------------------------------------------------------------------------- for /xen plan
	String describe() {
		if (c.player == null) return c.name + " isn't here.";
		if (goal == null) return c.name + " has no plan right now.";
		Plan p = plan == null ? make(goal, goalCount) : plan;
		return String.format(java.util.Locale.ROOT, "%s's plan for %s (%s): %s%s. Trusts its plans %.0f%%.", c.name, thing(goal, goalCount),
				askedBy == null ? "its own idea" : askedBy + " asked", steps(p), p.ok ? "" : "; doesn't know where to get " + String.join(", ", p.unknown), 100 * trust);
	}

	JsonObject save() {
		JsonObject o = new JsonObject();
		o.addProperty("trust", Math.round(trust * 100) / 100f);
		JsonObject l = new JsonObject();
		for (var e : learned.entrySet()) {
			com.google.gson.JsonArray a = new com.google.gson.JsonArray();
			for (float v : e.getValue()) a.add(Math.round(v * 100) / 100f);
			l.add(e.getKey(), a);
		}
		o.add("steps", l);
		if (goal != null) {
			o.addProperty("goal", goal);
			o.addProperty("count", goalCount);
			if (askedBy != null) o.addProperty("askedBy", askedBy);
		}
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		try {
			if (o.has("trust")) trust = Math.max(0.1f, Math.min(1f, o.get("trust").getAsFloat()));
			if (o.has("steps")) for (var e : o.getAsJsonObject("steps").entrySet()) {
				var a = e.getValue().getAsJsonArray();
				learned.put(e.getKey(), new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()});
			}
			if (o.has("goal")) {
				goal = o.get("goal").getAsString();
				goalCount = o.has("count") ? o.get("count").getAsInt() : 1;
				askedBy = o.has("askedBy") ? o.get("askedBy").getAsString() : null;
			}
		} catch (RuntimeException ignored) {
			// (what it can't read: it starts over on that)
		}
	}
}
