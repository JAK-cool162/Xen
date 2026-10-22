package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Blocks;
import xen.mod.core.Perception;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * What players ask Xen to do: gather wood, stone or ore, hunt for food, hand items over, build a shelter. It does it
 * like a player: it goes for blocks and animals it knows about (felt within 6 blocks, or seen in its view), walks and
 * digs there with its own hands, and looks around when it knows of none.
 */
final class Chores {
	enum Kind { GATHER, HUNT, GIVE, SHELTER, HIDE, EAT, REDSTONE, TRADE, MINE, SMELT, PICKUP, BLOCKS, SLAY }

	/** The small redstone circuits Xen learned (xen/redstone, exported by scripts/export_circuits.py). */
	static final com.google.gson.JsonObject CIRCUITS;
	static {
		com.google.gson.JsonObject c = new com.google.gson.JsonObject();
		try (var in = Chores.class.getResourceAsStream("/assets/xen/circuits.json")) {
			if (in != null) c = new com.google.gson.Gson().fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
					com.google.gson.JsonObject.class);
		} catch (java.io.IOException | RuntimeException e) {
			XenMod.LOG.warn("No redstone circuits: {}", e.toString());
		}
		CIRCUITS = c;
	}

	/** One part of a circuit, placed in the world: where, what, which way it points (0-3) and its delay. */
	record Part(BlockPos pos, String kind, int facing, int delay) {}

	private static final java.util.Map<String, String> ITEM = java.util.Map.of("dust", "redstone", "torch", "redstone_torch",
			"repeater", "repeater", "comparator", "comparator", "lever", "lever", "lamp", "redstone_lamp");
	private static final List<String> ORDER = List.of("block", "lamp", "lever", "torch", "repeater", "comparator", "dust");

	private static final long TIME = 6000;                           // give up after five minutes

	private final Companion c;
	private final Random random = new Random();
	Kind kind;
	private int[] cats;
	private String[] items;
	private String what, lookFor;
	/** What to gather next, once this is done (stone after the wood for a pickaxe). */
	private String after;
	private int afterAmount;
	private int want, had;
	private long until;
	/** When it last got somewhere with it (something gained, new ground, new places seen), and where it set off from. */
	private long progressAt;
	private int progressHave = -1, progressKnown;
	private double progressFar;
	private Vec3 progressFrom;
	private UUID forWhom;
	private String giveItem;
	private final Set<Long> skip = new HashSet<>();
	/** The ores it's walking to (the quickest of them), and since when: kept a while so it's one goal, not a new one each step. */
	private List<BlockPos> oreWay;
	private long oreWayAt;
	/** The block right by it that it's trying to get, and since when (hitting at it for ever is no good: another one). */
	private BlockPos working;
	private long workingSince;
	BlockPos target;

	/** Its legs can't get to what it was going for (three tries): out of reach, it picks another (no waiting for help). */
	void unreachable() {
		if (kind == null) return;
		if (target != null) skip.add(Perception.Beliefs.key(target.getX(), target.getY(), target.getZ()));
		if (glanced != null) skip.add(Perception.Beliefs.key(glanced[0], glanced[1], glanced[2]));
		target = null;
		glanced = null;
		nextGlance = 0;
	}
	private double closest;
	private long lastCloser;
	private Vec3 wander;
	private long wanderUntil;
	private int scans;
	private boolean saidLooking;
	private LivingEntity prey;
	private List<BlockPos> walls;
	private String shape = "hut";
	private int waited;
	private List<Part> circuit;
	private String circuitName;
	/** Before a circuit: what's in the way (dug out) and the holes under it (filled). */
	private List<BlockPos> clearing = List.of(), filling = List.of();
	private int cycles, fails;
	private int[] buildSide;                                            // where it stands to reach far parts
	/** Is this chore one it chose itself (its own want)? Then it doesn't report every step. */
	boolean own;
	/** What it was told to do before it built its shelter (follow), to go back to in the morning. */
	private Companion.Mode resume;
	/** Did the last shelter get built? */
	boolean shelterBuilt;
	/** What it's doing for the chore right now, in words (for /xen status). */
	String doing = "";

	Chores(Companion c) {
		this.c = c;
	}

	boolean busy() {
		return kind != null;
	}

	/** Which chore this is (one more each time one begins), and whether the last one ended by itself (done, or it gave up on it). */
	int serial;
	boolean endedItself = true;

	/** Stopped from outside (a fight, its gut, something to pick up, someone asking): the chore didn't end, it was cut off (see {@link Focus}). */
	void cancel() {
		if (kind != null) endedItself = false;
		if (DEBUG && kind != null) endWhy("cut off");
		stop();
	}

	static final boolean DEBUG = Boolean.getBoolean("xen.choreDebug");

	/** (-Dxen.choreDebug: who ended a chore, for its journal) */
	private void endWhy(String how) {
		var at = StackWalker.getInstance().walk(f -> f.skip(2).limit(3).map(x -> x.getClassName().replace("xen.mod.", "") + "." + x.getMethodName() + ":" + x.getLineNumber()).toList());
		c.journal("debug", kind + " " + how + " after " + (now() - started) / 20 + " s, doing " + doing + ": " + String.join(" < ", at));
	}

	/** It ends (done, or it gave up). */
	private void stop() {
		if (DEBUG && kind != null && endedItself) endWhy("ended");
		kind = null;
		target = null;
		prey = null;
		walls = null;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private long started;

	private void begin(Kind k) {
		stop();
		started = now();
		serial++;
		endedItself = true;
		dugout = null;
		pit = null;
		inside = null;
		walked = 0;
		gaveAt = -1;
		kind = k;
		own = false;
		moves = 0;
		shelterBuilt = false;
		until = now() + (long) (TIME * c.personality.patience());
		progressAt = now();
		progressHave = -1;
		progressFar = 0;
		progressFrom = c.player.position();
		progressKnown = c.g1x.size();
		skip.clear();
		saidLooking = false;
		scans = 0;
		wander = null;
		closest = Double.MAX_VALUE;
		lastCloser = now();
		lastGain = now();
		gained = -1;
		deepest = Integer.MAX_VALUE;
	}

	/** When it last got something for this chore (and how much it had then): too long with nothing, it gives up. */
	private long lastGain;
	private int gained = -1;
	/** The deepest it has got on this trip: on the way down to its depth, each new step down is getting somewhere. */
	private int deepest = Integer.MAX_VALUE;

	/**
	 * Giving up, like a player: mining or gathering with nothing to show for three minutes (a mine with no ore, a
	 * forest cut bare): it stops and does something else; a mine that gave nothing is forgotten (next time, somewhere
	 * new). True if it gave up.
	 */
	private boolean fruitless() {
		if (kind != Kind.MINE && kind != Kind.GATHER && kind != Kind.BLOCKS || items == null) return false;
		int n = kind == Kind.BLOCKS ? countItem(blocksItem) : count(items);
		if (n != gained) {
			gained = n;
			lastGain = now();
			return false;
		}
		if (kind == Kind.MINE && toMine != null && now() - started < 20 * 60 * 3) {   // still on its way to the mine: nothing to show yet is no wonder
			lastGain = now();
			return false;
		}
		if (kind == Kind.MINE && mineBase == null) {                         // down the staircase (or the cave): it isn't there yet, nothing to show is no wonder
			int y = c.player.getBlockY();
			if (y < deepest) {
				if (deepest != Integer.MAX_VALUE) lastGain = now();
				deepest = y;
				return false;
			}
		}
		int minutes = "diamonds".equals(what) ? 8 : 4;                          // (diamonds are rare: a player strip-mines a while for them)
		float patience = Math.max(kind == Kind.MINE ? 0.7f : 0.6f, c.personality.patience());   // (a trip down takes a while, even for a lazy one; and nobody gives up in under two and a half minutes)
		if (now() - lastGain < (long) (20 * 60 * minutes * patience)) return false;   // (a lazy one gives up sooner, a hard worker later)
		if (kind == Kind.MINE) {
			if (!caveMode && mineBase != null) {                                // a mine that got down: these tunnels gave nothing, the next ones further out may (a player keeps their mine)
				mineRecordLeg = mineLeg + 4;
			} else {
				if (!caveMode) c.places.forget("mine");
				mineRecordY = Integer.MIN_VALUE;
				MINES.removeIf(m -> m.by == c && m.base == null);
			}
			if (caveMode) c.caves.done(c.caves.nearest(200));                 // (that cave's done)
		}
		c.journal("does", String.format(java.util.Locale.ROOT, "gives up: nothing for %.1f minutes (%s)", minutes * patience, what));
		finish(c.pick3("Nothing here. I'll try somewhere else.", "This isn't working. Time for something else.", "Giving up on this spot."));
		return true;
	}

	private int count(String... keys) {
		var inv = c.items();
		int n = 0;
		for (String k : keys) n += inv.getOrDefault(k, 0);
		return n;
	}

	// ----------------------------------------------------------------------------- requests
	String gather(String intent, int amount) {
		int tier = c.crafter.pickTier(), could = c.crafter.canMake(2) ? 2 : c.crafter.canMake(1) ? 1 : tier;
		int need = switch (intent) {
			case "wood", "dirt" -> 0;
			case "iron" -> 2;
			default -> 1;
		};
		if (Math.max(tier, could) < need) {                           // stone and ore drop nothing without the right pickaxe
			if (need == 2 && Math.max(tier, could) == 1) {
				String plan = gather("stone", 3);
				after = intent;
				afterAmount = amount;
				return plan.replace("You will get 3 stone", "You need a stone pickaxe for iron, so first you will get 3 stone");
			}
			int logs = Math.max(1, 3 - c.items().getOrDefault("log", 0));
			String plan = gather("wood", logs);
			after = intent;
			afterAmount = amount;
			return "You have no pickaxe, so first you will get wood to make one. " + plan;
		}
		switch (intent) {
			case "wood" -> set(new int[] {Blocks.LOG}, new String[] {"log"}, "wood", "trees");
			case "stone" -> set(new int[] {Blocks.STONE}, new String[] {"cobblestone"}, "stone", "stone");
			case "dirt" -> set(new int[] {Blocks.DIRT, Blocks.GRASS}, new String[] {"dirt"}, "dirt", "dirt");
			case "coal" -> set(new int[] {Blocks.COAL}, new String[] {"coal"}, "coal", "coal");
			case "iron" -> set(new int[] {Blocks.IRON}, new String[] {"raw_iron"}, "iron", "iron ore");
			default -> {                                                // the ores its pickaxe can mine
				int t = Math.max(tier, could);
				if (t >= 3) set(new int[] {Blocks.DIAMOND, Blocks.GOLD, Blocks.IRON, Blocks.COAL}, new String[] {"diamond", "raw_gold", "raw_iron", "coal"}, "ore", "ore");
				else if (t == 2) set(new int[] {Blocks.IRON, Blocks.COAL}, new String[] {"raw_iron", "coal"}, "ore", "iron or coal ore");
				else set(new int[] {Blocks.COAL}, new String[] {"coal"}, "ore", "coal ore");
			}
		}
		begin(Kind.GATHER);
		after = null;
		want = Math.max(1, amount);
		had = count(items);
		int[] known = c.senses.nearestKnown(cats, 0.25, skip, 4);
		for (int tries = 0; known != null && tries < 12; tries++) {             // a tree or rock, not someone's house (nor its own build)
			BlockPos k = new BlockPos(known[0], known[1], known[2]);
			ServerLevel level = (ServerLevel) c.player.level();
			if (!c.builder.onSite(k) && (known[4] != Blocks.LOG || !level.isLoaded(k) || WorldSenses.treeLog(level, k))) break;
			skip.add(Perception.Beliefs.key(k.getX(), k.getY(), k.getZ()));
			known = c.senses.nearestKnown(cats, 0.25, skip, 4);
		}
		if (known == null) return "You don't know where to find any " + lookFor + ", so you will look around for some.";
		XenMod.LOG.info("{} goes for {} it knows at {} {} {}", c.name, Blocks.NAMES[known[4]], known[0], known[1], known[2]);
		String name = known[4] == Blocks.LOG ? "tree" : Blocks.NAMES[known[4]];
		return known[3] == 1
				? String.format(java.util.Locale.ROOT, "You will get %d %s from the %s you know is %.0f blocks from you.", want, what, name, distance(known))
				: String.format(java.util.Locale.ROOT, "You will get %d %s from the %s you saw %.0f blocks away.", want, what, name, distance(known));
	}

	private void set(int[] cats, String[] items, String what, String lookFor) {
		this.cats = cats;
		int[] noCoal = java.util.Arrays.stream(cats).filter(k -> k != Blocks.COAL).toArray();
		this.catsNoCoal = noCoal.length == cats.length || noCoal.length == 0 ? cats : noCoal;
		this.items = items;
		this.what = what;
		this.lookFor = lookFor;
		this.oreWay = null;
	}

	/** The same, less coal: on an iron trip with coal enough for its torches, coal isn't worth a walk (coal right by it, it still takes). */
	private int[] catsNoCoal;

	/** What's worth going for now: the coal on the way it takes while it has little; plenty, it keeps to what it came for. */
	private int[] mineCats() {
		if (catsNoCoal == null || catsNoCoal == cats || "coal".equals(what)) return cats;
		return count("coal", "charcoal") + countItem("torch") / 4 >= 8 ? catsNoCoal : cats;
	}

	/** Is it on a hunt someone asked for? */
	boolean huntingAsked() {
		return busy() && kind == Kind.HUNT && !own;
	}

	/** Hunting for wool (a bed): sheep first. */
	boolean forWool;

	String hunt(int amount) {
		begin(Kind.HUNT);
		want = Math.max(1, amount);
		had = forWool ? wool() : count("food");
		sinceSheep = now();
		prey = nearestAnimal();
		if (prey == null) return "You don't see any animals, so you will look around for some to hunt for food.";
		return String.format(java.util.Locale.ROOT, "You will hunt the %s you see %.0f blocks away for food.",
				prey.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), c.player.distanceTo(prey));
	}

	/** How many of something it keeps for itself (it isn't a chest): wood for a pickaxe, stone for tools, a little food. */
	int keepFor(String key) {
		int tier = c.crafter.pickTier();
		return switch (key) {
			case "log" -> tier == 0 ? 3 : 0;
			case "cobblestone" -> Math.max(tier < 2 ? 3 : 0, c.goals.evening() ? Goals.NIGHT_BLOCKS - count("dirt") : 0);   // (a shelter tonight)
			case "dirt" -> c.goals.evening() ? Goals.NIGHT_BLOCKS - count("cobblestone") : 0;
			case "food" -> c.player.getFoodData().getFoodLevel() < 14 ? 2 : 0;
			case "torch" -> 2;
			default -> 0;
		};
	}

	private String why(String key) {
		return switch (key) {
			case "log" -> "for your pickaxe";
			case "cobblestone" -> c.goals.evening() && Goals.NIGHT_BLOCKS - count("dirt") > 3 ? "for a shelter tonight" : "for your stone tools";
			case "dirt" -> "for a shelter tonight";
			case "food" -> "to eat";
			default -> "for yourself";
		};
	}

	/**
	 * Asked for its things: it doesn't just hand everything over. It thinks for a moment, keeps what it needs itself
	 * (and says so), then walks over and tosses the rest.
	 */
	String give(ServerPlayer to, String item, int amount) {
		int have = item.equals("all") ? giveable().size() : count(item);
		if (have == 0) return item.equals("all") ? "You have nothing to give." : "You have no " + named(item, 2) + " to give.";
		int keep = item.equals("all") ? 0 : keepFor(item);
		boolean precious = item.contains("diamond") || item.contains("emerald") || item.contains("netherite") || item.equals("totem_of_undying");
		float greed = c.personality.sin(xen.mod.core.Sins.GREED);
		if (precious && have <= keep + 2 && random.nextFloat() < greed) {     // (a player's way: not enough to spare, and it doesn't want to: it says it has none)
			c.journal("lies", "says it has no " + named(item, 2) + " (it has " + have + ")");
			return "You have no " + named(item, 2) + " to give.";
		}
		if (!item.equals("all") && have <= keep) {
			return "You can't give your " + named(item, 2) + " away, because you need the " + have + " you have " + why(item) + ".";
		}
		begin(Kind.GIVE);
		forWhom = to.getUUID();
		giveItem = item;
		int n = amount > 0 ? Math.min(amount, have - keep) : have - keep;
		want = item.equals("all") ? 0 : n;
		thinkUntil = now() + 20 + random.nextInt(30);                 // a moment to think it over, like anyone would
		String name = to.getName().getString();
		if (item.equals("all")) return "You will think it over, then give " + name + " what you can spare.";
		return "You will give " + name + " " + n + " " + named(item, n) + (keep > 0 ? ", but keep " + keep + " " + why(item) : "") + ".";
	}

	private long thinkUntil;

	/** "log" -> "5 logs", "raw_iron" -> "raw iron". */
	static String named(String item, int n) {
		String name = item.replace('_', ' ');
		return n != 1 && (item.equals("log") || item.equals("diamond")) ? name + "s" : name;
	}

	/**
	 * The shelter in its own style: walls around where it stands (2 high for a hut, with the corners filled in for a
	 * fort, 3 high for a tower), then something to place the roof against, then the roof.
	 */
	static List<BlockPos> shelterPlan(BlockPos feet, String build) {
		List<BlockPos> plan = new java.util.ArrayList<>();
		int height = build.equals("tower") ? 3 : 2;
		for (int y = 0; y < height; y++) {
			BlockPos at = feet.above(y);
			plan.add(at.north());
			plan.add(at.east());
			plan.add(at.south());
			plan.add(at.west());
			if (build.equals("fort")) {
				plan.add(at.north().east());
				plan.add(at.south().east());
				plan.add(at.south().west());
				plan.add(at.north().west());
			}
		}
		plan.add(feet.above(height).north());
		plan.add(feet.above(height));
		return plan;
	}

	String shelter() {
		return shelter(c.personality.build);
	}

	/** A shelter of a given shape ("hut", "fort", "tower"): a fort for its home. */
	String shelter(String build) {
		BlockPos feet = c.player.blockPosition();
		ServerLevel level = (ServerLevel) c.player.level();
		if (!c.player.onGround()) return "You can't build a shelter because you are not standing on the ground.";
		if (level.isDarkOutside() && !build.equals("fort")) {                 // dark already: no time for a hut (they took too long and fell through); dig in
			String quick = digIn();
			if (quick != null) return quick;
		}
		if (c.crafter.pickTier() >= 1 || c.crafter.canMake(1)) {               // a hill right here: dig in, like a player's first night
			List<BlockPos> tunnel = dugoutPlan(level, feet);
			if (tunnel != null) {
				begin(Kind.SHELTER);
				dugout = tunnel;
				dugoutDug = false;
				walls = List.of();
				shape = "dugout";
				waited = 0;
				resume = c.mode == Companion.Mode.STAY ? null : c.mode;
				c.mode = Companion.Mode.STAY;
				until = now() + 20 * 60;
				return "You will dig into the hill here for the night (a little tunnel, then seal the way in behind you).";
			}
		}
		if (!level.canSeeSky(feet.above()) && level.dimension() == net.minecraft.world.level.Level.OVERWORLD && c.crafter.pickTier() >= 1) {
			List<BlockPos> hole = pitPlan(level, feet);                     // under the ground already (a cave): a hole two deep and a lid, not a hut
			if (hole != null) {
				begin(Kind.SHELTER);
				pit = hole;
				nookTried = false;
				walls = List.of();
				shape = "hole";
				waited = 0;
				resume = c.mode == Companion.Mode.STAY ? null : c.mode;
				c.mode = Companion.Mode.STAY;
				until = now() + 20 * 60;
				return "You will dig a hole two deep right here and put a block over your head for the night (you're under the ground: no hut down here).";
			}
		}
		int blocks = count("dirt", "cobblestone");
		List<BlockPos> room = roomyPlan(level, feet, build);                // a little room it can stand up and turn around in
		for (int r = 1; r <= 4 && room == null; r++) {                       // not here: a flat spot close by, like anyone looks for
			for (int dx = -r; dx <= r && room == null; dx++) {
				for (int dz = -r; dz <= r && room == null; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					for (int dy = -1; dy <= 1 && room == null; dy++) {
						BlockPos spot = feet.offset(dx, dy, dz);
						if (level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()) continue;
						room = roomyPlan(level, spot, build);
					}
				}
			}
		}
		if (room != null && missing(level, room) <= blocks) {
			begin(Kind.SHELTER);
			walls = room;
			inside = new java.util.ArrayList<>();
			BlockPos corner = room.get(room.size() - 4).below(build.equals("tower") ? 3 : 2);   // (the roof's first block, straight down)
			for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) inside.add(corner.offset(dx, 0, dz));
			shape = build.equals("tower") ? "hut with a lookout" : build;
			waited = 0;
			resume = c.mode == Companion.Mode.STAY ? null : c.mode;
			c.mode = Companion.Mode.STAY;
			c.anchor = inside.get(0);
			return "You will build a little " + shape + " (room for two inside) with " + missing(level, room) + " blocks.";
		}
		if (c.crafter.pickTier() >= 1 || diggableByHand(level, feet)) {   // not enough blocks for a hut: a hole in the ground (what it digs covers it)
			List<BlockPos> hole = pitPlan(level, feet);
			if (hole != null) {
				begin(Kind.SHELTER);
				pit = hole;
				nookTried = false;
				walls = List.of();
				shape = "hole";
				waited = 0;
				resume = c.mode == Companion.Mode.STAY ? null : c.mode;
				c.mode = Companion.Mode.STAY;
				until = now() + 20 * 60;
				return "You will dig a hole two deep right here and put a block over your head for the night.";
			}
		}
		List<BlockPos> plan = shelterPlan(feet, build);                   // the last resort: blocks all around where it stands
		if (missing(level, plan) > blocks && !build.equals("hut")) {       // not enough for its style: a plain hut will do
			build = "hut";
			plan = shelterPlan(feet, build);
		}
		List<BlockPos> holes = new java.util.ArrayList<>();
		for (BlockPos p : plan) {                                      // walls need ground under them: it fills the holes first,
			BlockPos under = p.below();                               // from the bottom up, like a player levelling the spot
			if (p.getY() != feet.getY() || !level.getBlockState(p).canBeReplaced() || !level.getBlockState(under).canBeReplaced()) continue;
			List<BlockPos> column = new java.util.ArrayList<>();
			BlockPos q = under;
			while (level.getBlockState(q).canBeReplaced() && column.size() < 4) {
				if (!level.getFluidState(q).isEmpty() && level.getFluidState(q).isSource() && column.size() > 1) break;   // (deep water: it builds on what it has)
				column.add(0, q);
				q = q.below();
			}
			if (level.getBlockState(q).canBeReplaced()) {
				return "You can't build a shelter here because the ground drops away (a big hole or deep water).";
			}
			holes.addAll(column);
		}
		if (!holes.isEmpty()) {
			holes.addAll(plan);
			plan = holes;
		}
		int missing = missing(level, plan);
		if (missing > blocks) {
			return "You can't build a shelter because you need " + missing + " dirt or cobblestone and have " + blocks + ".";
		}
		int liked = switch (c.personality.material) {
			case "stone" -> count("cobblestone");
			case "earth" -> count("dirt");
			default -> 0;
		};
		String of = liked >= missing ? (c.personality.material.equals("stone") ? "stone " : "dirt ") : "";
		begin(Kind.SHELTER);
		walls = plan;
		shape = build;
		waited = 0;
		resume = c.mode == Companion.Mode.STAY ? null : c.mode;        // in the morning it goes back to what it was told
		c.mode = Companion.Mode.STAY;
		c.anchor = feet;
		return "You will build a small " + of + build + " around yourself with " + missing + " blocks.";
	}

	/**
	 * A little room: 2 by 2 inside (it stands in one corner), walls 2 high all round (3 for a lookout), a roof on top.
	 * The floor must be solid and the inside clear (a flat spot, like anyone picks); null if it isn't. Walls first,
	 * bottom up, then the roof, which rests on them.
	 */
	private static List<BlockPos> roomyPlan(ServerLevel level, BlockPos feet, String build) {
		int height = build.equals("tower") ? 3 : 2;
		for (int[] o : new int[][] {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}}) {    // which corner of the room it stands in
			BlockPos a = feet.offset(o[0], 0, o[1]);                        // the room's north-west inside corner
			boolean ok = true;
			for (int dx = 0; dx < 2 && ok; dx++) {
				for (int dz = 0; dz < 2 && ok; dz++) {
					BlockPos in = a.offset(dx, 0, dz);
					if (level.getBlockState(in.below()).getCollisionShape(level, in.below()).isEmpty()) ok = false;   // a floor
					for (int y = 0; y < height && ok; y++) {
						BlockPos b = in.above(y);
						if (!level.getBlockState(b).canBeReplaced() || !level.getFluidState(b).isEmpty()) ok = false;   // room to stand
					}
				}
			}
			if (!ok) continue;
			List<BlockPos> plan = new java.util.ArrayList<>();
			for (int y = 0; y < height; y++) {
				for (int dx = -1; dx <= 2; dx++) {
					for (int dz = -1; dz <= 2; dz++) {
						if (dx >= 0 && dx < 2 && dz >= 0 && dz < 2) continue;   // (inside)
						plan.add(a.offset(dx, y, dz));
					}
				}
			}
			plan.add(a.offset(-1, height, 0));                              // one on top of a wall: the roof goes against it
			for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) plan.add(a.offset(dx, height, dz));   // the roof
			boolean grounded = true;                                        // walls need ground under them (no digging holes to fill)
			for (BlockPos b : plan) {
				if (b.getY() == feet.getY() && level.getBlockState(b.below()).canBeReplaced()) grounded = false;
			}
			if (grounded) return plan;
		}
		return null;
	}

	/**
	 * Dig in, now: into the hill beside it, or a hole two deep under it (or a step away) with a block over its head, and
	 * there it waits for the morning. The quick shelter: no blocks needed, nothing to build. Null: no ground here it can
	 * dig (or it's sheltering already).
	 */
	String digIn() {
		if (sheltered()) return null;
		BlockPos feet = c.player.blockPosition();
		ServerLevel level = (ServerLevel) c.player.level();
		if (!c.player.onGround() || c.player.isInWater()) return null;
		List<BlockPos> tunnel = dugoutPlan(level, feet);
		if (tunnel != null && tunnel.stream().noneMatch(c.hands::tooSlowToMine)) {
			begin(Kind.SHELTER);
			dugout = tunnel;
			dugoutDug = false;
			walls = List.of();
			shape = "dugout";
			waited = 0;
			resume = c.mode == Companion.Mode.STAY ? null : c.mode;
			c.mode = Companion.Mode.STAY;
			until = now() + 20 * 60;
			return "You will dig into the hill here for the night and seal the way in behind you.";
		}
		for (int r = 0; r <= 2; r++) {                                          // under it, or a step or two away
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					BlockPos spot = feet.offset(dx, 0, dz);
					if (!level.getBlockState(spot).getCollisionShape(level, spot).isEmpty() || !level.getFluidState(spot).isEmpty()) continue;
					List<BlockPos> hole = pitPlan(level, spot);
					if (hole == null) continue;
					begin(Kind.SHELTER);
					pit = hole;
					nookTried = false;
				nookTried = false;
					walls = List.of();
					shape = "hole";
					waited = 0;
					resume = c.mode == Companion.Mode.STAY ? null : c.mode;
					c.mode = Companion.Mode.STAY;
					until = now() + 20 * 60;
					return "You will dig a hole two deep here and put a block over your head until morning.";
				}
			}
		}
		return null;
	}

	/** In a shelter, or making one, for the night. */
	boolean sheltered() {
		return kind == Kind.SHELTER || kind == Kind.HIDE;
	}

	/** A hole two deep under its feet (dug straight down, rock or dirt all round, a floor under it); null if not here. */
	private List<BlockPos> pitPlan(ServerLevel level, BlockPos feet) {
		BlockPos one = feet.below(), two = feet.below(2);
		for (BlockPos b : new BlockPos[] {one, two}) {
			var st = level.getBlockState(b);
			if (!Walker.natural(st) || st.getCollisionShape(level, b).isEmpty() || st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return null;
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos side = b.relative(d);
				if (level.getBlockState(side).getCollisionShape(level, side).isEmpty() || !level.getFluidState(side).isEmpty()) return null;
			}
		}
		BlockPos floor = feet.below(3);
		if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty() || !level.getFluidState(floor).isEmpty()) return null;
		if (c.hands.tooSlowToMine(one) || c.hands.tooSlowToMine(two)) return null;
		return List.of(one, two);
	}

	private static boolean diggableByHand(ServerLevel level, BlockPos feet) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet.below()).getBlock()).getPath();
		return id.contains("dirt") || id.equals("grass_block") || id.contains("sand") || id.equals("gravel") || id.equals("podzol") || id.contains("mud");
	}

	/** The inside of the little room it's building (it stays in there while it builds), or null. */
	private List<BlockPos> inside;
	private int walked;

	/** The hole it's digging for the night (the two blocks under it), or null; and whether it tried a pocket in its side already. */
	private List<BlockPos> pit;
	private boolean nookTried;

	/** Beside the bottom of its hole: two blocks of earth or stone it can dig out, solid over them (a roof) and under them. Null: none. */
	private BlockPos sidePocket(ServerLevel level, BlockPos bottom) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos side = bottom.relative(d);
			var feet = level.getBlockState(side);
			var head = level.getBlockState(side.above());
			if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty() || feet.getCollisionShape(level, side).isEmpty() || head.getCollisionShape(level, side.above()).isEmpty()) continue;
			if (feet.getDestroySpeed(level, side) < 0 || head.getDestroySpeed(level, side.above()) < 0 || feet.getDestroySpeed(level, side) > 3) continue;   // (dirt, stone: not bedrock, not obsidian)
			BlockPos roof = side.above(2);
			if (level.getBlockState(roof).getCollisionShape(level, roof).isEmpty() || !level.getBlockState(roof).getFluidState().isEmpty()) continue;
			if (level.getBlockState(side.below()).getCollisionShape(level, side.below()).isEmpty()) continue;
			return side.immutable();
		}
		return null;
	}

	private Action pitNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		for (BlockPos b : pit) {                                                // straight down, like a player digging in
			if (level.getBlockState(b).getCollisionShape(level, b).isEmpty()) continue;
			doing = "digging a hole for the night";
			if (c.hands.busy()) return Action.IDLE;
			if (c.hands.mine(b)) {
				c.acted = true;
				return Action.MINE;
			}
			if (++waited > 60) break;
			return Action.IDLE;
		}
		BlockPos bottom = pit.get(1), cover = bottom.above(2);
		if (!c.player.blockPosition().equals(bottom)) {                         // (falls in by itself; a step if it's beside it)
			if (++waited > 80) {
				pit = null;
				finish("I couldn't get down into my hole.");
				return null;
			}
			return c.walkTo(Vec3.atBottomCenterOf(bottom));
		}
		if (level.getBlockState(cover).canBeReplaced()) {                     // and a block over its head
			doing = "covering its hole";
			if (c.hands.placeAt(cover, c.personality.material)) {
				c.acted = true;
				return Action.PLACE;
			}
			if (++waited < 100) return Action.IDLE;
			BlockPos nook = nookTried ? null : sidePocket(level, bottom);       // nothing to cover it with: into its side, under a roof of earth (an arrow can't get in there, nor an enderman)
			if (nook != null) {
				nookTried = true;
				pit = List.of(nook.above(), nook);
				waited = 0;
				c.journal("thinks", "nothing to cover its hole with: digs into the side of it, under the ground's own roof");
				return Action.IDLE;
			}
		}
		pit = null;
		stop();
		c.chatter(c.pick3("Dug in. See you in the morning!", "A hole in the ground: not pretty, but safe.", "Nice and snug down here."), true);
		shelterBuilt = true;
		doing = "hiding in its hole until morning";
		kind = Kind.HIDE;
		hidAt = now();
		until = now() + 1200;
		return Action.IDLE;
	}

	private static int missing(ServerLevel level, List<BlockPos> plan) {
		int n = 0;
		for (BlockPos p : plan) if (level.getBlockState(p).canBeReplaced()) n++;
		return n;
	}

	/** Build one of the circuits it learned, in front of it, from redstone parts it carries. */
	String redstone(String which) {
		if (!c.mod.config.redstone) return "You can't build redstone because it's switched off in the settings.";
		if (!CIRCUITS.has(which)) return "You don't know how to build that.";
		com.google.gson.JsonObject spec = CIRCUITS.getAsJsonObject(which);
		String name = spec.get("name").getAsString();
		var parts = spec.getAsJsonArray("parts");
		if (parts.size() > c.mod.config.maxRedstoneParts) {
			return "You won't build the " + name + " because it has " + parts.size() + " parts and the limit is " + c.mod.config.maxRedstoneParts + ".";
		}
		int yaw = c.hands.yaw, depth = spec.get("depth").getAsInt();
		int[] fwd = Perception.forward(yaw), right = Perception.forward((yaw + 1) % 4);
		BlockPos feet = c.player.blockPosition();
		ServerLevel level = (ServerLevel) c.player.level();
		List<Part> plan = new java.util.ArrayList<>();
		for (var e : parts) {
			var a = e.getAsJsonArray();
			int cx = a.get(0).getAsInt(), cz = a.get(1).getAsInt() - depth / 2, d = a.get(3).getAsInt();
			BlockPos pos = feet.offset(fwd[0] * (2 + cx) + right[0] * cz, 0, fwd[1] * (2 + cx) + right[1] * cz);
			int[] v = Perception.DIRS[d];                            // the circuit's directions, turned the way it faces
			int wx = v[0] * fwd[0] + v[1] * right[0], wz = v[0] * fwd[1] + v[1] * right[1];
			int facing = 0;
			for (int k = 0; k < 4; k++) if (Perception.DIRS[k][0] == wx && Perception.DIRS[k][1] == wz) facing = k;
			plan.add(new Part(pos, a.get(2).getAsString(), facing, a.get(4).getAsInt()));
		}
		int width = spec.get("width").getAsInt();                    // the ground where it goes must be flat and clear:
		List<BlockPos> clear = new java.util.ArrayList<>(), fill = new java.util.ArrayList<>();   // it clears and levels it first
		for (int cx = 0; cx < width; cx++) {
			for (int cz = -depth / 2; cz < depth - depth / 2; cz++) {
				BlockPos pos = feet.offset(fwd[0] * (2 + cx) + right[0] * cz, 0, fwd[1] * (2 + cx) + right[1] * cz);
				for (int y = 0; y <= 1; y++) if (!level.getBlockState(pos.above(y)).canBeReplaced()) clear.add(pos.above(y));
				BlockPos under = pos.below();
				if (!level.getBlockState(under).isCollisionShapeFullBlock(level, under)) {
					if (!level.getBlockState(under).canBeReplaced()) clear.add(under);            // a slab, a path: out, and a block instead
					if (level.getBlockState(under.below()).canBeReplaced()) {
						return "You can't build the " + name + " here because the ground in front of you drops away.";
					}
					fill.add(under);
				}
			}
		}
		java.util.Map<String, Integer> need = new java.util.TreeMap<>();
		for (Part p : plan) need.merge(p.kind().equals("block") ? "block" : ITEM.get(p.kind()), 1, Integer::sum);
		if (!fill.isEmpty()) need.merge("block", fill.size(), Integer::sum);
		StringBuilder missing = new StringBuilder();
		for (var e : need.entrySet()) {
			int have = e.getKey().equals("block") ? count("dirt", "cobblestone") : countItem(e.getKey());
			if (have < e.getValue()) {
				String what = e.getKey().equals("block") ? "dirt or cobblestone" : e.getKey().replace('_', ' ');
				missing.append(missing.length() > 0 ? ", " : "").append(e.getValue() - have).append(" more ").append(what);
			}
		}
		if (missing.length() > 0) return "You can't build the " + name + " yet because you need " + missing + ".";
		begin(Kind.REDSTONE);
		plan.sort(java.util.Comparator.comparingInt(p -> ORDER.indexOf(p.kind())));
		circuit = plan;
		circuitName = name;
		clearing = clear;
		filling = fill;
		buildSide = Perception.forward((yaw + 3) % 4);                 // the left side of it, fixed while it builds
		cycles = fails = 0;
		c.mode = Companion.Mode.STAY;
		c.anchor = feet;
		return "You will build a " + name + " (" + plan.size() + " parts) in front of you.";
	}

	private int countItem(String id) {
		var inv = c.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id)) n += s.getCount();
		}
		return n;
	}

	private Action redstoneNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		for (BlockPos p : clearing) {                                  // first the ground: clear, then level
			if (level.getBlockState(p).canBeReplaced()) continue;
			doing = "clearing the ground for a " + circuitName;
			if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > c.player.blockInteractionRange() - 0.5) return c.walkTo(Vec3.atBottomCenterOf(p));
			if (c.hands.mine(p)) {
				c.acted = true;
				return Action.MINE;
			}
		}
		for (BlockPos p : filling) {
			if (!level.getBlockState(p).canBeReplaced()) continue;
			doing = "levelling the ground for a " + circuitName;
			if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > c.player.blockInteractionRange() - 0.5) return c.walkTo(Vec3.atBottomCenterOf(p.above()));
			if (c.hands.placeAt(p)) {
				c.acted = true;
				return Action.PLACE;
			}
		}
		for (Part p : circuit) {
			if (!level.getBlockState(p.pos()).canBeReplaced()) {
				String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.pos()).getBlock()).getPath();
				if (p.kind().equals("repeater") && id.equals("repeater") && cycles < p.delay() - 1) {   // set its delay
					if (!c.hands.canClick(p.pos())) return c.walkTo(Vec3.atCenterOf(p.pos()));
					c.hands.use(p.pos());
					cycles++;
					c.acted = true;
					return Action.PLACE;
				}
				continue;
			}
			cycles = 0;
			doing = "building a " + circuitName + ": " + p.kind();
			boolean placed;
			if (p.kind().equals("block")) {
				placed = c.hands.placeItem(p.pos(), s -> Hands.PLACEABLE.contains(
						net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()), p.pos().below(), net.minecraft.core.Direction.UP, -1);
			} else if (p.kind().equals("torch")) {                        // on the side of the block behind it
				int[] back = Perception.DIRS[(p.facing() + 2) % 4];
				placed = c.hands.placeItem(p.pos(), s -> isItem(s, "redstone_torch"), p.pos().offset(back[0], 0, back[1]), direction(p.facing()), -1);
			} else {                                                        // on the ground, facing the way it looks
				String id = ITEM.get(p.kind());
				placed = c.hands.placeItem(p.pos(), s -> isItem(s, id), p.pos().below(), net.minecraft.core.Direction.UP, p.facing());
			}
			if (placed) {
				c.acted = true;
				return Action.PLACE;
			}
			if (c.hands.cantPlace.equals("too far to reach")) {             // walk along the side of it
				return c.walkTo(Vec3.atCenterOf(p.pos().offset(buildSide[0] * 2, 0, buildSide[1] * 2)));
			}
			if (++fails > 20) {
				finish("I couldn't finish the " + circuitName + ": " + c.hands.cantPlace + ".");
				return null;
			}
			return Action.IDLE;
		}
		finish("The " + circuitName + " is ready! Flip the lever and watch the lamp.");
		return null;
	}

	private static boolean isItem(ItemStack s, String id) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id);
	}

	private static net.minecraft.core.Direction direction(int facing) {
		return switch (facing) {
			case 0 -> net.minecraft.core.Direction.NORTH;
			case 1 -> net.minecraft.core.Direction.EAST;
			case 2 -> net.minecraft.core.Direction.SOUTH;
			default -> net.minecraft.core.Direction.WEST;
		};
	}

	String eat() {
		if (count("food") == 0) return "You have no food.";
		if (!c.player.getFoodData().needsFood()) return "You are not hungry.";
		begin(Kind.EAT);
		return "You will eat.";
	}

	// ------------------------------------------------------------------------------- doing it
	/** The next action for the chore, or null to let the brain (or following) decide this moment. */
	Action next() {
		if (kind == null) return null;
		if (fruitless()) return null;
		progress();
		if (now() > until && kind != Kind.HIDE) {
			if (now() - progressAt < stallTicks()) {                          // still getting somewhere: on with it, as long as it takes
				until = now() + 20 * 60;                                         // (it looks again in a minute)
			} else {
				finish(kind == Kind.GATHER && count(items) > had ? "I only found " + (count(items) - had) + " " + what + "."
						: c.pick3("No luck here. On to something else.", "That didn't work out. Something else, then.", "Can't get to it. I'll do something else."));
				return null;
			}
		}
		return switch (kind) {
			case GATHER -> gatherNext();
			case HUNT -> huntNext();
			case GIVE -> giveNext();
			case SHELTER -> shelterNext();
			case REDSTONE -> redstoneNext();
			case TRADE -> c.trader.villagerStep();
			case MINE -> mineNext();
			case PICKUP -> pickupNext();
			case BLOCKS -> blocksNext();
			case SLAY -> slayNext();
			case SMELT -> smeltNext();
			case HIDE -> {                                               // stays in its shelter until morning (or a minute)
				if (!c.player.level().isDarkOutside() && now() > until) {
					if (hutFilled != null && !"fort".equals(shape) && (c.goals.home == null || !c.goals.home.closerThan(c.player.blockPosition(), 10)))
						c.tidy.shelter(hutFilled);                            // morning: its night's hut may come down again (its call)
					hutFilled = null;
					stop();
					if (resume != null) c.mode = resume;                        // following again, as it was told
					resume = null;
					yield null;
				}
				Action dig = own && resume != Companion.Mode.FOLLOW ? mineFromShelter() : null;   // (not just sitting there all night: it digs down and mines, safe underground)
				yield dig != null ? dig : Action.IDLE;
			}
			case EAT -> {
				stop();
				yield c.player.getFoodData().needsFood() && count("food") > 0 ? Action.EAT : null;
			}
		};
	}

	/** Getting somewhere with it: more of what it's after, new ground (further than it got from where it set off), new places seen (G1X). */
	private void progress() {
		long now = now();
		int have = items == null ? 0 : count(items);
		if (have != progressHave) {
			if (progressHave >= 0) progressAt = now;
			progressHave = have;
		}
		if (progressFrom != null) {
			double far = c.player.position().distanceTo(progressFrom);
			if (far > progressFar + 4) {
				progressFar = far;
				progressAt = now;
			}
		}
		int known = c.g1x.size();
		if (known > progressKnown + 400) {
			progressKnown = known;
			progressAt = now;
		}
	}

	/** How long it keeps at something with nothing coming of it before it calls it (its own patience: two to four minutes). */
	private long stallTicks() {
		return (long) (20 * 60 * (2 + 2 * Math.min(1f, c.personality.patience() / 1.5f)));
	}

	private void finish(String say) {
		stop();
		String s = say.toLowerCase(java.util.Locale.ROOT);
		if (s.startsWith("got") || s.startsWith("done") || s.startsWith("here")) c.belief.up(0.015f, say);   // (what it set out to do: done)
		else if (s.startsWith("i can't") || s.startsWith("no ") || s.startsWith("couldn't")) c.belief.down(0.015f, say);
		if (!say.isEmpty()) c.chatter(say, !own);
	}

	/** When it went into its shelter for the night, and when it may next start a mine from one. */
	private long hidAt, nightMineAt;

	/**
	 * Settled in its shelter at night (20 s), with a pickaxe, fed and not hurt: like a player it digs down from where it
	 * is and mines (enclosed all the way) instead of waiting for morning. Null: it stays put.
	 */
	private Action mineFromShelter() {
		int tier = c.crafter.pickTier();
		if (!c.player.level().isDarkOutside() || now() - hidAt < 400 || now() < nightMineAt || tier < 1 || c.player.getHealth() < 12
				|| c.player.getFoodData().getFoodLevel() < 8 || c.player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
		nightMineAt = now() + 20 * 60 * 3;
		Companion.Mode back = resume;
		String ore = tier >= 3 ? "diamonds" : tier >= 2 ? "iron" : "coal";
		int y = c.lessons.depth(ore, ore.equals("diamonds") ? -58 : ore.equals("iron") ? 16 : 40);
		String plan = mine(Math.min(y, c.player.getBlockY() - 4), ore, ore.equals("diamonds") ? 3 : 6);
		if (!plan.startsWith("You will")) return null;
		own = true;
		if (back != null) c.mode = back;                                      // (the shelter kept it put: free again, down its mine)
		resume = null;
		c.journal("does", "night in its shelter: digs down for " + ore + " instead of waiting for morning");
		return next();
	}

	/** Trading with a villager ({@link Trader} walks it there and does the clicking). */
	void beginTrade() {
		begin(Kind.TRADE);
		doing = "going to trade";
	}

	/** A chore someone else runs (trading) is over. */
	void done(String say) {
		finish(say);
	}

	private double distance(int[] p) {
		Vec3 feet = c.player.position();
		return Math.sqrt(Math.pow(p[0] + 0.5 - feet.x, 2) + Math.pow(p[1] - feet.y, 2) + Math.pow(p[2] + 0.5 - feet.z, 2));
	}

	private Action gatherNext() {
		int got = count(items) - had;
		if (got >= want) {
			String said = c.banter.event("got", java.util.Map.of("count", String.valueOf(got), "item", what));
			finish(said != null && (!own || c.random().nextFloat() < 0.35f) ? said : own ? "" : "Got " + got + " " + what + "!");
			if (after != null) {                                        // and now what it was asked for
				String next = after;
				after = null;
				boolean mine = own;
				String plan = gather(next, afterAmount);
				own = mine;
				c.chatter(xen.mod.talk.Chat.firstPerson(plan), !own);
			}
			return null;
		}
		int needs = 0;
		for (int k : cats) needs = Math.max(needs, Crafter.tierFor(k));
		if (needs > c.crafter.pickTier() && !c.crafter.canMakeAtLeast(needs)) {   // its pickaxe broke, or it never had one (and it can't make one)
			finish("I can't mine " + what + " without " + Crafter.tierName(needs) + ".");
			return null;
		}
		ItemEntity drop = dropToPickUp();                               // what it just mined, lying on the ground
		if (drop != null) {
			doing = "picking up " + c.itemKey(drop.getItem()).replace('_', ' ');
			return c.stepTo(drop.position());
		}
		BlockPos bonus = oreInReach();                                  // ore showing right by it: nobody walks past iron for stone
		if (bonus != null) {
			doing = "mining the " + Blocks.NAMES[WorldSenses.category((ServerLevel) c.player.level(), bonus, c.player.level().getBlockState(bonus))] + " it spotted";
			return reach(bonus);
		}
		int[] known = glance(cats);                                    // what it can see around it (14 blocks)...
		if (known != null && c.builder.onSite(new BlockPos(known[0], known[1], known[2]))) {   // (not out of its own build)
			skip.add(Perception.Beliefs.key(known[0], known[1], known[2]));
			known = null;
		}
		for (int tries = 0; known == null && tries < 12; tries++) {       // ...or saw earlier, further away
			known = c.senses.nearestKnown(cats, 0.25, skip, 4);
			if (known == null || known[4] != Blocks.STONE && known[4] != Blocks.LOG) break;
			BlockPos k = new BlockPos(known[0], known[1], known[2]);       // what it saw: real stone, not a mushroom cap or a wall
			ServerLevel level = (ServerLevel) c.player.level();         // (and a tree, not someone's house)
			if (!level.isLoaded(k) || !c.builder.onSite(k) && (known[4] == Blocks.LOG ? WorldSenses.treeLog(level, k)
					: WorldSenses.isNaturalStone(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(k).getBlock()).getPath()))) break;
			skip.add(Perception.Beliefs.key(k.getX(), k.getY(), k.getZ()));
			known = null;
		}
		boolean forStone = cats.length > 0 && cats[0] == Blocks.STONE && c.crafter.pickTier() >= 1;
		if (forStone && !c.player.isInWater() && c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
				&& (known == null || Math.hypot(known[0] - c.player.getX(), known[2] - c.player.getZ()) > 24)) {   // (stone it saw on a hillside close by: it walks over, it doesn't dig)
			doing = "digging down for stone";                             // (none close: like a player, it digs down where it stands, stone is a few blocks under)
			return stairsDown();
		}
		doing = known == null ? "looking for " + lookFor : String.format(java.util.Locale.ROOT, "getting %s, %d of %d so far", what, got, want);
		if (known == null) {
			if (!saidLooking) {                                         // (asked, and it just answered: once is enough)
				if (own || System.currentTimeMillis() - c.saidMillis > 5000) c.chatter("I haven't seen any " + lookFor + " yet, I'll look around.", !own);
				saidLooking = true;
			}
			return lookAround();
		}
		BlockPos t = new BlockPos(known[0], known[1], known[2]);
		if (!t.equals(target)) {
			target = t;
			closest = Double.MAX_VALUE;
			lastCloser = now();
		}
		if (known[4] == Blocks.LOG) lastLog = t;
		if (c.player.isUnderWater() && c.player.getAirSupply() < c.player.getMaxAirSupply() / 2 || underwater(t)) {
			skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));   // under water: not worth drowning for
			target = null;
			doing = "coming up for air";
			return c.player.isInWater() ? Action.JUMP : null;
		}
		double d = distance(known);
		if (d < closest - 0.5) {
			closest = d;
			lastCloser = now();
		} else if (now() - lastCloser > 200) {                       // not getting any closer in 10 seconds: try another one
			skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
			target = null;
			return null;
		}
		return reach(t);
	}

	// ------------------------------------------------------------------------------ mining
	/** Where it went down, which way its tunnels go, how deep, which tunnel it's on, and where that one ends. */
	private BlockPos mineStart, mineBase;
	private net.minecraft.core.Direction mineDir;
	private int mineY, mineLeg;
	private long legStarted;

	/**
	 * A Xen's own mine (its staircase): where it starts, how deep it goes, which way, its foot once it's dug that far.
	 * Others may use it too, like on an SMP: down the same stairs, then tunnels of their own off the bottom (each the
	 * next way round), not a new half-dug hole each.
	 */
	static final class Mine {
		final Companion by;
		final BlockPos entrance;
		volatile int y;
		final net.minecraft.core.Direction dir;
		final String dim;
		volatile BlockPos base;
		int users;

		Mine(Companion by, BlockPos entrance, int y, net.minecraft.core.Direction dir, String dim) {
			this.by = by;
			this.entrance = entrance;
			this.y = y;
			this.dir = dir;
			this.dim = dim;
		}
	}

	static final List<Mine> MINES = new java.util.concurrent.CopyOnWriteArrayList<>();
	/** How long each branch of a strip mine runs off the main tunnel. */
	private static final int STRIP = 16;
	/** The mine it's using (its own, or another's), and ore it has a feeling about (luck) that it's digging to. */
	private Mine using;
	private long lastWet, guideNext;

	private String dimName() {
		return c.player.level().dimension().toString();
	}

	/** Its mine, known to the others (its own, started now: where, how deep, which way). */
	private Mine ownMine(BlockPos entrance) {
		for (Mine m : MINES) if (m.by == c && m.entrance.closerThan(entrance, 3)) return m;
		MINES.removeIf(m -> m.by == c);                                            // (one mine each: its newest)
		Mine m = new Mine(c, entrance.immutable(), mineY, mineDir, dimName());
		MINES.add(m);
		while (MINES.size() > 64) MINES.remove(0);
		return m;
	}

	/** Someone else's mine near enough, for about this depth, from someone it doesn't hold a grudge against; null if none. */
	private Mine sharedMine(int y, double within) {
		Mine best = null;
		double bestD = within * within;
		for (Mine m : MINES) {
			if (m.by == c || !m.dim.equals(dimName()) || m.y < y - 8 || m.by.player == null) continue;   // (deeper than it needs: no; shallower: on down from its foot)
			if (c.trust(m.by.player.getUUID()) < 0f || c.diplomacy.wary(m.by.player.getUUID())) continue;
			double d = m.entrance.distSqr(c.player.blockPosition());
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		return best;
	}

	/**
	 * Go mining, like a player: a staircase down to where the ore is (iron and coal around y 16, diamonds deep down in
	 * the deepslate), then branch tunnels two high, three blocks apart. It mines the ore it sees in the tunnel walls
	 * (never ore it can't see), lights the way with torches if it has them, and stops when it has what it came for.
	 */
	String mine(int y, String what, int amount) {
		int tier = Math.max(c.crafter.pickTier(), c.crafter.canMake(2) ? 2 : c.crafter.canMake(1) ? 1 : 0);
		int need = what.equals("debris") ? 4 : what.equals("diamonds") ? 3 : what.equals("iron") ? 2 : 1;
		if (tier < need) return "You can't go mining for " + what + " yet: you need " + Crafter.tierName(need) + " first.";
		begin(Kind.MINE);
		until = now() + 20 * 60 * 6;                                        // (six minutes, then: still getting somewhere? on, as long as it takes)
		tripWhat = what;
		wetSince = -1;
		debrisHunt = what.equals("debris");
		if (debrisHunt) set(new int[] {Blocks.GOLD}, new String[] {"ancient_debris"}, "ancient debris", "ancient debris");   // (the Nether: and gold on the way)
		else if (what.equals("diamonds")) set(new int[] {Blocks.DIAMOND, Blocks.IRON, Blocks.GOLD, Blocks.COAL}, new String[] {"diamond"}, "diamonds", "diamond ore");
		else if (tier >= 3) set(new int[] {Blocks.IRON, Blocks.GOLD, Blocks.COAL, Blocks.DIAMOND}, new String[] {"raw_iron"}, "iron", "iron ore");
		else if (tier == 2) set(new int[] {Blocks.IRON, Blocks.COAL}, new String[] {"raw_iron"}, "iron", "iron ore");
		else set(new int[] {Blocks.COAL}, new String[] {"coal"}, "coal", "coal ore");
		want = Math.max(1, amount);
		had = count(items);
		mineStart = c.player.blockPosition();
		mineBase = null;
		mineDir = c.player.getDirection();
		mineY = y;
		mineLeg = 0;
		legStarted = now();
		using = null;
		if (!c.guide.leading()) c.guide.drop();                                // (a new trip: its guide keeps what it's leading to, no new one each time)
		guideNext = 0;
		boolean dark = c.player.level().isDarkOutside();                      // at night: no long walks in the dark to get there
		// its own mine: one it dug before for this depth, it goes back to (down the same staircase, on with the next tunnel)
		BlockPos entrance = c.places.get("mine");
		toMine = null;
		if (entrance != null && mineRecordY != Integer.MIN_VALUE && entrance.closerThan(c.player.blockPosition(), dark ? 32 : 300)) {
			toMine = entrance;
			mineDir = mineRecordDir;
			if (y < mineRecordY - 8) {                                         // deeper this time (diamonds): on down the same staircase
				mineY = y;
				mineLeg = 0;
				mineBase = null;
			} else {
				mineY = mineRecordY;
				mineLeg = mineRecordLeg;
				mineBase = c.places.get("mine base");
			}
			using = ownMine(entrance);
			return "You will go back to your mine for " + what + " (down the staircase at " + entrance.getX() + " " + entrance.getZ() + ", on with tunnel "
					+ (mineLeg / 2 + 1) + ").";
		}
		Mine theirs = sharedMine(y, dark ? 32 : 160);                           // someone's mine close by: down their stairs, its own tunnels off the bottom
		if (theirs != null) {
			using = theirs;
			theirs.users++;
			toMine = theirs.entrance;
			boolean deeper = y < theirs.y - 8;
			mineY = deeper ? y : theirs.y;
			mineBase = deeper ? null : theirs.base;
			mineLeg = 0;
			mineDir = mineBase == null ? theirs.dir : theirs.users % 2 == 1 ? theirs.dir.getClockWise() : theirs.dir.getCounterClockWise();   // (the next way round from theirs)
			mineRecordY = mineY;
			mineRecordDir = mineDir;
			mineRecordLeg = 0;
			caveLook = 0;
			c.journal("thinks", String.format(java.util.Locale.ROOT, "uses %s's mine at %d %d (down to y %d), not a new hole of its own", theirs.by.name,
					theirs.entrance.getX(), theirs.entrance.getZ(), theirs.y));
			return "You will use " + theirs.by.name + "'s mine for " + what + " (down the staircase at " + theirs.entrance.getX() + " " + theirs.entrance.getZ() + ").";
		}
		// A cave it knows: the quickest way to ore (the walls show it). In, with torches, down it goes.
		caveMode = false;
		caveTarget = null;
		boolean under = !c.player.level().canSeeSky(c.player.blockPosition().above());
		BlockPos cave = c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && (!dark || under) ? c.caves.nearest(dark ? 16 : 160) : null;   // (at night: its own mine, strip mining, safe under the ground; not out to a cave)
		if (cave != null && !readyForCave(cave.getY())) {                     // not ready for that (no weapon, hurt, no food; deep: no armor or shield): its own mine
			c.journal("thinks", String.format(java.util.Locale.ROOT, "knows a cave at %d %d %d but isn't ready for it (%s): strip mines instead", cave.getX(), cave.getY(), cave.getZ(), unready(cave.getY())));
			cave = null;
		}
		if (cave != null) {
			caveMode = true;
			toMine = cave;
			Hunch.claim(c, cave);
			XenMod.LOG.info("{} mines in the cave at {}", c.name, cave.toShortString());
			return "You will go mining in the cave at " + cave.getX() + " " + cave.getY() + " " + cave.getZ()
					+ " (the walls show the ore; you'll light it with torches as you go).";
		}
		// None it knows: a player often has a look round first (a dark hole in a hillside, a ravine, the walls show the ore).
		// A curious one nearly always, a lazy one seldom; not at night, not deep down already, not every trip.
		caveLook = 0;
		if (!dark && !debrisHunt && c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && c.player.getBlockY() > 40
				&& c.player.level().canSeeSky(c.player.blockPosition().above()) && now() - lastCaveLook > 20 * 60 * 4 && readyForCave(40)
				&& !(what.equals("diamonds") && c.mod.config.luck >= 2 && !c.hunch.oresNear("diamond", c.player.blockPosition(), 128, 160, 1).isEmpty())) {   // (knowing everything, after diamonds it knows of: straight there, no looking round)
			{                                                                  // (by day a cave first, like a player: the walls show the ore; none close by, its own mine)
				caveLook = now() + 20 * 60;
				lastCaveLook = now();
				caveLookTo = null;
				c.journal("thinks", "has a look round for a cave first (the walls show the ore; quicker than digging), its own mine if there's none");
			}
		}
		BlockPos home = c.goals.home;                                          // a new one: by its home, like players dig theirs
		if (home != null && home.closerThan(c.player.blockPosition(), 48)) toMine = home.relative(c.player.getDirection(), 6);
		BlockPos top = toMine != null ? toMine : c.player.blockPosition();
		intoHill = false;
		Object[] hill = hillside(top, y);                                     // a hillside close by: in there, not a hole in the flat ground
		if (hill != null) {
			toMine = (BlockPos) hill[0];
			mineDir = (net.minecraft.core.Direction) hill[1];
			intoHill = true;
			c.journal("thinks", String.format(java.util.Locale.ROOT, "digs its mine into the hillside at %d %d %d (%s), not a hole in the flat ground",
					toMine.getX(), toMine.getY(), toMine.getZ(), mineDir.getName()));
			XenMod.LOG.info("{} starts a mine in the hillside at {} {} {} ({})", c.name, toMine.getX(), toMine.getY(), toMine.getZ(), mineDir.getName());
		} else {
			XenMod.LOG.info("{} starts a mine where it is (no hillside close by)", c.name);
			net.minecraft.core.Direction dry = dryWay(top, top.getY() - y);
			if (dry == null) {                                                 // water all round (a shore, a swamp): somewhere drier
				BlockPos away = drySpot(top);
				if (away != null) {
					toMine = away;
					dry = dryWay(away, away.getY() - y);
				}
			}
			if (dry != null) mineDir = dry;
		}
		mineRecordY = y;
		mineRecordDir = mineDir;
		mineRecordLeg = 0;
		c.places.forget("mine base");
		return "You will dig your own mine for " + what + ": a staircase down to about y " + y + " (you'll come back to it), then tunnels, mining the ore you see.";
	}

	/**
	 * Back to diamonds it saw before and couldn't mine then (its pickaxe was too weak): there first, and it mines them
	 * as it sees them; none left there: tunnels at that depth. An answer like mine()'s.
	 */
	String mineAt(BlockPos spot, int amount) {
		String plan = mine(spot.getY(), "diamonds", amount);
		if (!plan.startsWith("You will")) return plan;
		caveMode = true;                                                      // (a place it knows, not its own staircase: it isn't kept as "the mine")
		caveTarget = null;
		toMine = spot;
		mineY = spot.getY();
		return "You will go back to the diamonds at " + spot.getX() + " " + spot.getY() + " " + spot.getZ() + ".";
	}

	/**
	 * A way down that doesn't go under water (a staircase under a lake or a river floods and drowns you): of the four,
	 * the first with no water on or over its first steps (what it can see from up here), or null.
	 */
	private net.minecraft.core.Direction dryWay(BlockPos from, int down) {
		net.minecraft.core.Direction d = c.player.getDirection();
		for (int r = 0; r < 4; r++, d = d.getClockWise()) if (!wet(from, d, down)) return d;
		return null;
	}

	/** Water on or over the first steps of a staircase down that way? */
	/** When it may next put a block in water or lava, how many it put lately and since when, and what it last blocked. */
	private long plugNext, plugSince;
	private int plugs;
	private String plugged = "water";

	/**
	 * Water or lava in or right by its tunnel or staircase, under the ground: a cobblestone (or dirt...) in it, a source
	 * first, lava before water, the way a player blocks it off and mines on. Not a sea: more than a dozen blocks a minute
	 * and it gives up on that (it digs somewhere drier). True if it put one this time.
	 */
	private boolean plugFluid() {
		var p = c.player;
		long now = now();
		if (now < plugNext || p.isInLava()) return false;
		ServerLevel level = (ServerLevel) p.level();
		BlockPos feet = p.blockPosition();
		if (level.canSeeSky(feet.above()) && mineBase == null && tunnelIn == null) return false;   // (out in the open: a river is no flood)
		if (now - plugSince > 1200) {
			plugSince = now;
			plugs = 0;
		}
		if (plugs >= 12) return false;
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 3, 3))) {
			var fl = level.getFluidState(q);
			if (fl.isEmpty()) continue;
			boolean lava = fl.is(net.minecraft.tags.FluidTags.LAVA);
			int score = (int) q.distSqr(feet) + (fl.isSource() ? 0 : 40) + (lava ? 0 : 20);
			if (score < bestScore && p.getEyePosition().distanceTo(Vec3.atCenterOf(q)) <= p.blockInteractionRange() - 0.3) {
				bestScore = score;
				best = q.immutable();
			}
		}
		if (best == null) return false;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			BlockPos n = best.relative(d);
			if (n.equals(feet) || n.equals(feet.above()) || !level.getFluidState(n).isEmpty() || level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
			boolean lava = level.getFluidState(best).is(net.minecraft.tags.FluidTags.LAVA);
			if (c.hands.placeItem(best, st -> Hands.PLACEABLE.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()), n, d.getOpposite(), -1)) {
				plugNext = now + 6;
				plugs++;
				plugged = lava ? "lava" : "water";
				if (plugs == 1) c.journal("does", "blocks the " + plugged + " off at " + best.toShortString() + " and mines on");
				return true;
			}
		}
		plugNext = now + 10;
		return false;
	}

	private boolean wet(BlockPos from, net.minecraft.core.Direction d, int down) {
		ServerLevel level = (ServerLevel) c.player.level();
		for (int i = 0; i <= Math.min(Math.max(down, 4), 20); i++) {
			BlockPos step = from.relative(d, i).below(i);
			for (int up = 0; up <= i + 3; up++) {                              // the step and all above it to the surface
				BlockPos q = step.above(up);
				if (!level.getFluidState(q).isEmpty() || !level.getFluidState(q.relative(d.getClockWise())).isEmpty()
						|| !level.getFluidState(q.relative(d.getCounterClockWise())).isEmpty()) return true;
			}
		}
		return false;
	}

	/** Its staircase that way would come out of the hill's side into the open (daylight on the steps ahead). */
	private boolean opensUp(BlockPos from, net.minecraft.core.Direction d) {
		ServerLevel level = (ServerLevel) c.player.level();
		for (int i = 2; i <= 4; i++) {
			BlockPos head = from.relative(d, i).below(i - 1);
			if (level.isLoaded(head) && level.canSeeSky(head)) return true;
		}
		return false;
	}

	/** Its new mine goes in through a hillside: first a couple of blocks straight in, then the staircase down. */
	private boolean intoHill;
	private BlockPos tunnelIn;

	/** Ground a mine can go into: stone or earth (not a tree's trunk, a build, sand or gravel that would fall in). */
	private static boolean hillBlock(net.minecraft.world.level.block.state.BlockState s) {
		return s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.TERRACOTTA)
				|| s.is(net.minecraft.world.level.block.Blocks.SANDSTONE) || s.is(net.minecraft.world.level.block.Blocks.RED_SANDSTONE);
	}

	/**
	 * A hillside close by to dig its mine into, the way players start one: the foot of a slope that rises at least three
	 * blocks of stone or earth right in front, dry all the way down; the higher the hill and the closer, the better. How
	 * far it'll walk for one is its own: a diligent Xen goes further, a lazy one digs where it is unless a hill's right
	 * there. {foot, way in}, or null (flat land all round: a staircase where it is, as before).
	 */
	private Object[] hillside(BlockPos from, int depth) {
		ServerLevel level = (ServerLevel) c.player.level();
		int far = 10 + Math.round(22 * c.personality.diligence);
		Object[] best = null;
		float bestScore = Float.NEGATIVE_INFINITY;
		for (int r = 0; r <= far; r += 3) {
			int n = r == 0 ? 1 : Math.max(8, r * 2);
			for (int k = 0; k < n; k++) {
				double a = 2 * Math.PI * k / n;
				int x = from.getX() + (int) Math.round(r * Math.cos(a)), z = from.getZ() + (int) Math.round(r * Math.sin(a));
				if (!level.isLoaded(new BlockPos(x, from.getY(), z))) continue;
				Vec3 g = XenMod.surface(level, x, z);
				if (g == null) continue;
				BlockPos foot = BlockPos.containing(g);
				if (Math.abs(foot.getY() - from.getY()) > 12 || !level.getBlockState(foot).canBeReplaced() || !level.getBlockState(foot.above()).canBeReplaced()) continue;
				for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
					BlockPos in = foot.relative(d);
					if (!hillBlock(level.getBlockState(in)) || !hillBlock(level.getBlockState(in.above())) || !hillBlock(level.getBlockState(in.above(2)))) continue;   // a wall of earth right in front
					Vec3 g3 = XenMod.surface(level, x + 3 * d.getStepX(), z + 3 * d.getStepZ());
					int rise = g3 == null ? 0 : (int) g3.y - foot.getY();
					if (rise < 3 || wet(foot, d, foot.getY() - depth)) continue;
					float score = Math.min(rise, 10) - r / 5f;
					if (score > bestScore) {
						bestScore = score;
						best = new Object[] {foot.immutable(), d};
					}
				}
			}
		}
		return best;
	}

	/** Dry ground a little way off (no water within 4 blocks of it), for a mine's staircase; null if none near. */
	private BlockPos drySpot(BlockPos from) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos best = null;                                                  // the highest dry ground: a staircase from up there goes down inside the rock, not out by a river
		for (int r = 8; r <= 32; r += 8) {
			for (int k = 0; k < 8; k++) {
				double a = k * Math.PI / 4;
				int x = from.getX() + (int) Math.round(r * Math.cos(a)), z = from.getZ() + (int) Math.round(r * Math.sin(a));
				if (!level.isLoaded(new BlockPos(x, from.getY(), z))) continue;
				Vec3 ground = XenMod.surface(level, x, z);
				if (ground == null) continue;                                  // (no ground there it can find: not that spot)
				BlockPos q = BlockPos.containing(ground);
				boolean wet = false;
				for (BlockPos n : BlockPos.betweenClosed(q.offset(-4, -3, -4), q.offset(4, 1, 4))) {
					if (!level.getFluidState(n).isEmpty()) {
						wet = true;
						break;
					}
				}
				if (!wet && (best == null || q.getY() > best.getY() + 2)) best = q.immutable();
			}
		}
		return best;
	}

	/** Looking for ancient debris (the Nether, for netherite) rather than overworld ore. */
	private boolean debrisHunt;

	/** Ancient debris showing a face within 6 blocks, or what its eyes saw within 24; null if none. */
	private BlockPos debrisInSight() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-6, -4, -6), feet.offset(6, 5, 6))) {
			if (!"debris".equals(Eyes.kind(level.getBlockState(q)))) continue;
			if (skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ())) || !open(level, q)) continue;
			if (best == null || q.distSqr(feet) < best.distSqr(feet)) best = q.immutable();
		}
		if (best == null) best = c.eyes.nearest("debris", 24, k -> skip.contains(Perception.Beliefs.key(BlockPos.of(k).getX(), BlockPos.of(k).getY(), BlockPos.of(k).getZ())));
		return best;
	}

	/** Its mine: the depth it's dug to, the way it goes, the tunnel it's on (kept with the Xen). */
	int mineRecordY = Integer.MIN_VALUE, mineRecordLeg;
	net.minecraft.core.Direction mineRecordDir = net.minecraft.core.Direction.NORTH;
	private BlockPos toMine;
	/** Mining in a cave (not its own staircase): the spot it's heading for in there, and since when. */
	private boolean caveMode;
	/** First a look around for a cave (its call, by its nature): till when, where it's headed, and when it last did. */
	private long caveLook, lastCaveLook = -100_000;
	/** Since when its staircase has had it in water (a lake broke in): a while of that, it starts again somewhere drier. */
	private long wetSince = -1;
	/** Where it last looked ahead down its staircase (once a step). */
	private BlockPos stairLooked;
	/** What this trip is for, as it was asked (to start it again: a cave it can't get to, its own mine then). */
	private String tripWhat;
	private BlockPos caveLookTo;
	private long caveLookToAt;
	private BlockPos caveTarget;
	private long caveTargetAt;

	private Action mineNext() {
		if (caveLook > 0) {
			Action a = lookForCave();
			if (a != null) return a;
		}
		if (toMine != null) {                                                  // first to the mine's entrance
			if (c.player.blockPosition().closerThan(toMine, 2.5)) {
				if (!caveMode && (c.places.get("mine") == null || !c.places.get("mine").closerThan(toMine, 3))) c.places.remember("mine", toMine);
				if (!caveMode && using == null) using = ownMine(toMine);
				toMine = null;
				mineStart = c.player.blockPosition();
				legStarted = now();
				if (intoHill && mineBase == null) tunnelIn = mineStart.relative(mineDir, 2);   // into the hill first
				intoHill = false;
			} else {
				doing = "going to its mine";
				if (caveMode && c.player.isInWater()) {                         // a cave under a lake: not one it can get to dry
					if (wetSince < 0) wetSince = now();
					else if (now() - wetSince > 200) {
						c.caves.done(toMine);
						c.journal("thinks", "the cave at " + toMine.toShortString() + " is under water: its own mine instead");
						wetSince = -1;
						int left = Math.max(1, want - (count(items) - had));
						mine(mineY, tripWhat, left);                               // (the trip again, without that cave)
						return null;
					}
				} else if (!c.player.isInWater()) wetSince = -1;
				c.run(c.player.blockPosition().distSqr(toMine) > 400);
				Action a = c.walkTo(Vec3.atBottomCenterOf(toMine));
				if (a == null) toMine = null;                                     // (no way there: it digs where it is)
				return a;
			}
		}
		if (!caveMode && caveLook == 0 && !debrisHunt && c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			BlockPos cave = c.caves.nearest(20);                                 // its staircase broke into a cave (or one's right here): the walls show the ore, in it goes
			if (cave != null && cave.getY() <= c.player.getBlockY() + 4) {
				caveMode = true;
				caveTarget = null;
				toMine = cave;
				tunnelIn = null;
				c.journal("does", "came on a cave at " + cave.toShortString() + " on its way down: explores it for ore");
				XenMod.LOG.info("{} mines in the cave at {}", c.name, cave.toShortString());
				c.chatter(c.pick3("Ooh, a cave. Let's see what's in here.", "Broke into a cave! Ore's in the walls.", "A cave down here. Checking it out."), false);
				return null;
			}
		}
		int got = count(items) - had;
		if (got >= want) {
			finish("I got " + got + " " + what + "! Heading back up.");
			return null;
		}
		if (what.equals("iron") && c.crafter.pickTier() == 2 && count("raw_iron") + countItem("iron_ingot") >= 3 && dropToPickUp() == null) {
			finish("That's enough iron for a pickaxe. I'll smelt it first.");     // (a player smelts the first three for the pickaxe: the rest comes quicker with it)
			return null;
		}
		ItemEntity drop = dropToPickUp();
		if (drop != null) {
			doing = "picking up " + c.itemKey(drop.getItem()).replace('_', ' ');
			return c.stepTo(drop.position());
		}
		if (debrisHunt) {                                                    // ancient debris: any it can see (it hides in the netherrack)
			BlockPos d = debrisInSight();
			if (d != null) {
				doing = String.format(java.util.Locale.ROOT, "mining ancient debris (%d of %d)", got, want);
				return reach(d);
			}
		}
		BlockPos bonus = oreInReach();                                       // any ore right by it, whatever it came for
		if (bonus != null) {
			doing = "mining the " + Blocks.NAMES[WorldSenses.category((ServerLevel) c.player.level(), bonus, c.player.level().getBlockState(bonus))] + " it spotted";
			return reach(bonus);
		}
		if (c.mod.config.luck >= 2 && !debrisHunt && c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {   // knowing everything: straight to the ore it knows of
			Action a = knownOre(what.equals("diamonds") ? "diamond" : what.equals("iron") ? "iron" : "coal", got, want);
			if (a != null) return a;
		}
		int[] seek = mineCats();
		List<BlockPos> ores = oreCandidates(seek, 12);                       // every ore it knows (showing, or seen): the cheapest to get to
		if (ores.size() > 1 && c.mod.config.pathAssist) {
			for (BlockPos o : ores) {
				if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(o)) <= c.player.blockInteractionRange() - 0.5 && open((ServerLevel) c.player.level(), o)) {
					target = o;
					return reach(o);                                           // (one right here: that one)
				}
			}
		}
		if (ores.size() > 1 && c.mod.config.pathAssist && !c.guide.leading()) {   // (its guide leading it to ore it needs more: that way, not round the ones it happens to have seen)
			// The same set for a while (a new set is a new goal: the walker would plan afresh every step), less the ones gone.
			ServerLevel level = (ServerLevel) c.player.level();
			if (oreWay != null) oreWay.removeIf(o -> !java.util.Arrays.stream(seek).anyMatch(k -> k == WorldSenses.category(level, o, level.getBlockState(o)))
					|| skip.contains(Perception.Beliefs.key(o.getX(), o.getY(), o.getZ())));   // (mined, or given up on)
			if (oreWay == null || oreWay.isEmpty() || now() - oreWayAt > 200) {
				oreWay = ores;
				oreWayAt = now();
			}
			doing = String.format(java.util.Locale.ROOT, "going for the ore that's quickest to get to (%d it knows; %d of %d %s so far)", oreWay.size(), got, want, what);
			List<Goal> goals = new ArrayList<>();
			for (BlockPos o : oreWay) goals.add(Goal.nextTo(o));
			Action a = c.walkTo(Goal.anyOf(goals));
			if (a != null) return a;
			for (BlockPos o : oreWay) skip.add(Perception.Beliefs.key(o.getX(), o.getY(), o.getZ()));   // no way to any of them: others then
			oreWay = null;
			return null;
		}
		if (toMine == null && !debrisHunt && c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
			if (!c.guide.leading() && now() >= guideNext) {                   // its guide: a way to the nearest ore it's after (it follows, or goes its own way)
				guideNext = now() + 20 * 20;
				boolean deep = what.equals("diamonds"), down = mineBase != null;   // (down its mine: ore by its tunnels; on its way down: wherever it'd dig to; in a cave: round it, the ore showing first)
				c.guide.offer(deep ? "diamond" : what.equals("iron") ? "iron" : "coal", caveMode ? 20 : down ? 10 : 40, caveMode ? 12 : down ? 4 : deep ? 140 : 72);
			}
			if (c.guide.check()) {
				BlockPos t = c.guide.target, here = c.player.blockPosition();
				boolean inReach = c.player.getEyePosition().distanceTo(Vec3.atCenterOf(t)) <= c.player.blockInteractionRange() - 0.3;
				boolean byWay = !inReach && (c.mod.config.luck >= 2 || c.guide.leadingTo("diamond"));   // (the way it worked out, dry all the way, a step at a time, right up to it: no other way-finding over it)
				BlockPos n = byWay ? c.guide.nextCell(here) : null;
				Action step = n != null ? routeStep(n) : null;
				if (step != null) {
					doing = c.guide.doing(got, want);
					return step;
				}
				if (inReach || !byWay && t.closerThan(here, 11)) {              // close: straight to it
					doing = c.guide.doing(got, want);
					Action a = reach(t);
					if (a != null) return a;
				} else if (!caveMode && mineBase == null && tunnelIn == null && t.getY() < here.getY() - 3) {   // far and below: its staircase goes that way (as deep as it was going: it takes the ore as it passes)
					net.minecraft.core.Direction d = c.guide.way();
					if (!wet(here, d, 4) && !opensUp(here, d)) mineDir = d;
				}
			}
		}
		int[] ore = glance(seek);                                            // ore showing in the walls (the tunnel shows it)
		if (ore != null && ore[4] == Blocks.STONE && !java.util.Arrays.stream(cats).anyMatch(k -> k == Blocks.STONE))
			ore = null;                                                      // ("there may be ore in that stone": its own staircase and tunnels are for that)
		if (ore != null && ore[1] <= c.player.getBlockY() + 4) {
			doing = ore[4] == Blocks.STONE ? String.format(java.util.Locale.ROOT, "digging into the stone to look for ore, %d of %d %s so far", got, want, what)   // (no ore in sight: deep stone has some)
					: String.format(java.util.Locale.ROOT, "mining %s, %d of %d %s so far", Blocks.NAMES[ore[4]].replace(" ore", "") + " ore", got, want, what);
			BlockPos t = new BlockPos(ore[0], ore[1], ore[2]);
			if (!t.equals(target)) {
				target = t;
				closest = Double.MAX_VALUE;
				lastCloser = now();
			}
			double d = distance(ore);
			if (d < closest - 0.5) {
				closest = d;
				lastCloser = now();
			} else if (now() - lastCloser > 200) {
				skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
				target = null;
				return null;
			}
			return reach(t);
		}
		BlockPos feet = c.player.blockPosition();
		if (caveMode) {                                                      // in the cave: on and down, to where it hasn't been
			if (caveTarget == null || feet.closerThan(caveTarget, 1.8) || now() - caveTargetAt > 300) {
				caveTarget = c.caves.deeper();
				caveTargetAt = now();
			}
			if (caveTarget != null) {
				doing = String.format(java.util.Locale.ROOT, "exploring the cave for ore (y %d, %d of %d %s so far)", feet.getY(), got, want, what);
				Action a = c.walkTo(Vec3.atBottomCenterOf(caveTarget));
				if (a != null) return a;
				caveTarget = null;
			}
			caveMode = false;                                                // (all of it seen: its own tunnels from here)
			c.caves.done(c.caves.nearest(48));
			c.chatter("That's all of this cave. I'll dig my own tunnels from here.", false);
			mineStart = feet;
			mineDir = c.player.getDirection();
			if (feet.getY() <= mineY + 6) mineBase = feet;
			legStarted = now();
			return null;
		}
		if (plugFluid()) {                                                   // water or lava coming in: a block in it, as a player does, and on
			doing = "blocking the " + plugged + " off";
			return Action.IDLE;
		}
		net.minecraft.core.Direction right = mineDir.getClockWise();
		BlockPos goal;
		if (mineBase == null) {                                              // down the staircase
			if (c.player.isInWater() && tunnelIn == null) {
				lastWet = now();
				if (wetSince < 0) wetSince = now();
				else if (now() - wetSince > 200) {                               // ten seconds in the water in its own staircase (up for air and back counts too): not here
					wetSince = -1;
					BlockPos away = drySpot(feet);
					c.places.forget("mine");
					mineRecordY = Integer.MIN_VALUE;
					if (away != null) {
						toMine = away;
						net.minecraft.core.Direction dry = dryWay(away, away.getY() - mineY);
						if (dry != null) mineDir = dry;
						c.journal("thinks", "its staircase ran into water: starts again on dry ground at " + away.toShortString());
						c.chatter(c.pick3("Water everywhere. I'll dig somewhere drier.", "Flooded. Moving my mine.", "Ugh, water. Somewhere else."), false);
						return null;
					}
				}
			} else if (now() - lastWet > 100) wetSince = -1;                   // (dry a good while: that was a splash, not a flood)
			if (tunnelIn == null && !feet.equals(stairLooked)) {                // a look ahead each step: water, or daylight (out of the hill's side) that way? another way down
				stairLooked = feet;
				boolean covered = !((ServerLevel) c.player.level()).canSeeSky(feet.above());   // (still up in the open at the top of its stairs: daylight all round, of course, nothing to turn for)
				if (wet(feet, mineDir, 4) || covered && opensUp(feet, mineDir)) {
					net.minecraft.core.Direction d = mineDir;
					for (int r = 0; r < 3; r++) {
						d = r == 1 ? d.getOpposite() : d.getClockWise();
						if (!wet(feet, d, 4) && !(covered && opensUp(feet, d))) {
							c.journal("thinks", "turns its staircase " + d.getName() + " (" + (wet(feet, mineDir, 4) ? "water" : "daylight") + " ahead " + mineDir.getName() + ")");
							mineDir = d;
							mineRecordDir = d;
							break;
						}
					}
				}
			}
			if (feet.getY() <= mineY + 1) {
				mineBase = feet;
				mineLeg = 0;
				legStarted = now();
				net.minecraft.core.Direction lucky = c.hunch.tunnelWay(feet, what);   // (its tunnels just happen to go the good way, now and then)
				if (lucky != null && !wet(feet, lucky, 4)) {
					mineDir = lucky;
					mineRecordDir = lucky;
				}
				c.places.remember("mine base", feet);
				if (c.places.get("mine") == null) c.places.remember("mine", mineStart);
				if (using == null) using = ownMine(c.places.get("mine"));
				if (using != null && (using.base == null || feet.getY() < using.base.getY() - 3)) {   // (the foot of the stairs, or deeper now)
					using.base = feet.immutable();
					using.y = feet.getY();
				}
				mineRecordY = feet.getY();
				c.chatter("Down at y " + feet.getY() + ". Tunnels now.", false);
				return null;
			}
			int down = feet.getY() - mineY, step = Math.min(down, 3);        // (a few steps ahead at a time: a straight staircase, not a far point it digs about for)
			if (tunnelIn != null && (feet.distManhattan(tunnelIn) <= 0 || feet.distManhattan(tunnelIn) > 6 || now() - legStarted > 300)) tunnelIn = null;   // (in, or no getting in there: down from here)
			goal = tunnelIn != null ? tunnelIn : feet.relative(mineDir, step).below(step);
			doing = tunnelIn != null ? "digging into the hillside" : "digging a staircase down (y " + feet.getY() + ")";
		} else {                                                             // a strip mine: a main tunnel, a branch each side every 3 blocks (two of rock between: every block shows), 16 long
			int k = mineLeg, branch = k / 4, part = k % 4;
			BlockPos main = mineBase.relative(mineDir, 3 * branch + 2);
			goal = switch (part) {
				case 1 -> main.relative(right, STRIP);
				case 3 -> main.relative(right.getOpposite(), STRIP);
				default -> main;                                               // (along the main tunnel to the next branch, or back to it)
			};
			doing = part == 0 ? "strip mining: on along the main tunnel" : part == 2 ? "strip mining: back to the main tunnel"
					: String.format(java.util.Locale.ROOT, "strip mining, branch %d (%s)", 2 * branch + (part == 1 ? 1 : 2), part == 1 ? "right" : "left");
			if (feet.distManhattan(goal) <= 1 || now() - legStarted > 20 * 60) {
				mineLeg++;
				mineRecordLeg = mineLeg;
				mineRecordDir = mineDir;
				legStarted = now();
				return null;
			}
		}
		Action a = c.walkTo(Vec3.atBottomCenterOf(goal));
		if (a == null) {                                                     // no way that way (lava, water): turn
			mineDir = mineDir.getClockWise();
			legStarted = now();
		}
		return a;
	}

	/**
	 * Ready for a cave? It's dark in there and the monsters are out day and night: a weapon, health, something to eat; a
	 * deep one (deepslate, lava about) wants armor or a shield too. Not ready: strip mining, its own tunnels, is safer.
	 */
	boolean readyForCave(int y) {
		return unready(y).isEmpty();
	}

	private String unready(int y) {
		if (!c.hands.hasWeapon()) return "nothing to fight with";
		if (c.player.getHealth() < 12) return "hurt";
		if (y < 0 && !c.hands.canEat()) return "no food for a long way down";
		if (y < 0 && !c.crafter.has("_chestplate") && !c.crafter.hasShield()) return "no armor or shield for a deep one";
		return "";
	}

	/** It's following a feeling about a cave (its guide learns whether that was right). */
	private boolean caveFelt;

	/** Out looking round for a cave (for its mining), not in one yet. */
	boolean lookingForCave() {
		return kind == Kind.MINE && caveLook > 0 && !caveMode && now() < caveLook;
	}

	/** The felt cave it's walking to; the felt ones it went to or turned down (not asked about again). */
	private BlockPos feltAt;
	private final List<BlockPos> caveTried = new ArrayList<>();

	private void caveTried(BlockPos at) {
		caveTried.add(at);
		if (caveTried.size() > 32) caveTried.remove(0);
	}

	/** Looking round for a cave before it digs: one found (it saw it), in it goes; none in a while, its own mine. Null: done looking. */
	private Action lookForCave() {
		BlockPos cave = c.caves.nearest(64);
		if (cave != null) {
			caveLook = 0;
			caveMode = true;
			caveTarget = null;
			toMine = cave;
			Hunch.claim(c, cave);
			intoHill = false;
			tunnelIn = null;
			c.journal("does", "found a cave to mine in at " + cave.toShortString() + ": in there, not its own staircase");
			if (caveFelt) c.guide.caveFeeling(true);
			caveFelt = false;
			XenMod.LOG.info("{} mines in the cave at {}", c.name, cave.toShortString());
			c.chatter(c.pick3("A cave. Way quicker than digging.", "Found a cave, going in.", "Cave! The ore's in the walls there."), false);
			return null;
		}
		if (now() > caveLook || c.player.level().isDarkOutside()) {
			caveLook = 0;
			if (caveFelt) c.guide.caveFeeling(false);
			caveFelt = false;
			c.journal("thinks", "no cave close by: digs its own mine");
			return null;
		}
		BlockPos feet = c.player.blockPosition();
		boolean toFelt = caveLookTo != null && caveLookTo.equals(feltAt);
		if (caveLookTo == null || feet.closerThan(caveLookTo, 3) || now() - caveLookToAt > (toFelt ? 1200 : 300)) {
			if (toFelt) caveTried(feltAt);                                        // (there, or a minute on, and no cave to see: not that one again)
			feltAt = null;
			ServerLevel level = (ServerLevel) c.player.level();
			BlockPos sensed = c.hunch.caveNear(feet, 112);                        // luck: a feeling there's a cave over there (the server knows, a big one first): it goes and has a look
			BlockPos felt = sensed != null && caveTried.stream().anyMatch(t -> t.closerThan(sensed, 32)) ? null : sensed;   // (one it went to, or made up its mind about, already: not again)
			if (felt != null && !felt.closerThan(feet, 6) && !c.guide.follows(c.hunch.lastCave, felt)) {
				caveTried(felt);
				felt = null;
			}
			if (felt != null && !felt.closerThan(feet, 6)) {                      // (its guide's feeling: it follows it)
				feltAt = felt;
				caveLookTo = felt;
				caveLookToAt = now();
				caveFelt = true;
				if (c.hunch.lastCaveWorth >= 1500) {                              // a big one: worth the walk, more than digging a mine (more time to find its mouth)
					caveLook = Math.max(caveLook, now() + 20 * 120);
					c.journal("thinks", "a big cave should be over there: quicker iron than strip mining, so it goes for that first");
				}
				doing = "going to have a look for the cave it has a feeling about";
				Action a = c.wanderTo(Vec3.atBottomCenterOf(felt));
				if (a != null) return a;
			}
			BlockPos best = null;
			double bestScore = Double.MAX_VALUE;
			for (int i = 0; i < 8; i++) {                                         // low ground first: valleys and hillsides show their caves
				double a = i * Math.PI / 4 + random.nextDouble() * 0.6;
				int x = feet.getX() + (int) Math.round(Math.cos(a) * 24), z = feet.getZ() + (int) Math.round(Math.sin(a) * 24);
				if (!level.isLoaded(new BlockPos(x, feet.getY(), z))) continue;
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos q = new BlockPos(x, top, z);
				if (!level.getBlockState(q.below()).getFluidState().isEmpty() || c.walker.beenAt(q)) continue;   // (not the sea, not where it's been)
				double score = top + 6 * random.nextDouble();
				if (score < bestScore) {
					bestScore = score;
					best = q;
				}
			}
			caveLookTo = best;
			caveLookToAt = now();
			if (best == null) {
				caveLook = 0;
				return null;
			}
		}
		doing = caveLookTo.equals(feltAt) ? "going to have a look for the cave it has a feeling about" : "looking round for a cave (the walls show the ore)";
		Action a = caveLookTo.equals(feltAt) ? c.wanderTo(Vec3.atBottomCenterOf(caveLookTo)) : c.walkTo(Vec3.atBottomCenterOf(caveLookTo));
		if (a == null) caveLookTo = null;
		return a != null ? a : Action.TURN_LEFT;                         // (no way there: a look round from here, another way next)
	}

	// ------------------------------------------------------------------------------ picking up
	private String pickupId;
	private BlockPos pickupAt;
	private long pickupGoneAt;

	private static boolean isKind(String path, String id) {
		return path.equals(id) || id.equals("torch") && path.endsWith("torch") || id.equals("bed") && path.endsWith("_bed")
				|| id.equals("grass") && (path.equals("short_grass") || path.equals("tall_grass") || path.equals("fern") || path.equals("large_fern"))
				|| id.equals("door") && path.endsWith("_door") && !path.startsWith("iron") || id.equals("lantern") && path.endsWith("lantern");
	}

	/** The nearest one it can see (a side open to the air), within 16 blocks. */
	private BlockPos findVisible(String id) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-16, -6, -16), feet.offset(16, 6, 16))) {
			if (!isKind(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath(), id)) continue;
			if (skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ()))) continue;   // (one it gave up on)
			boolean open = false;
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				var n = level.getBlockState(q.relative(d));
				open |= n.canBeReplaced() && n.getFluidState().isEmpty();               // (open to the air: under water doesn't count)
			}
			if (open && !wetTop(level, q) && (best == null || q.distSqr(feet) < best.distSqr(feet))) best = q.immutable();
		}
		if (best == null) {
			String kind = id.equals("short_grass") || id.equals("tall_grass") ? "grass" : id;   // not close by: what its eyes saw further off
			best = c.eyes.nearest(kind, Eyes.RANGE, k -> skip.contains(Perception.Beliefs.key(BlockPos.of(k).getX(), BlockPos.of(k).getY(), BlockPos.of(k).getZ()))
					|| wetTop(level, BlockPos.of(k)));
		}
		return best;
	}

	/** Water on top of it (a river bed, the sea floor): not worth swimming down to dig, that's how they drown. */
	private static boolean wetTop(ServerLevel level, BlockPos q) {
		return level.getFluidState(q.above()).is(net.minecraft.tags.FluidTags.WATER);
	}

	/** Pick up a block someone (or it) put down: a crafting table, a furnace, a chest, a bed, a door, torches... */
	String pickUp(String id) {
		BlockPos at = findVisible(id);
		String name = id.replace('_', ' ');
		if (at == null) return "You don't see a " + name + " around here to pick up.";
		begin(Kind.PICKUP);
		until = now() + 1200;
		pickupId = id;
		pickupAt = at;
		pickupGoneAt = -1;
		items = new String[] {id.equals("torch") ? "torch" : id};
		what = name;
		had = countItem(id);
		return String.format(java.util.Locale.ROOT, "You will pick up the %s %.0f blocks away.", name, Math.sqrt(at.distSqr(c.player.blockPosition())));
	}

	/** Fetching for a plan: what drops (clay ball, redstone, raw gold...), how many, the eyes' kind and which blocks count. */
	private String fetchDrop, fetchKind;
	private int fetchWant;
	private java.util.function.Predicate<String> fetchMatch;

	/**
	 * Fetch n of what a kind of block drops (for its plans: sand, gravel, clay, obsidian, an ore, sugar cane...): the
	 * nearest one it can see, or one its eyes saw further off; mined, its drop picked up; then the next.
	 */
	String fetch(String kind, java.util.function.Predicate<String> match, String drop, int n) {
		fetchMatch = match;
		fetchKind = kind;
		BlockPos at = findBlock(match, kind);
		String name = kind.replace('_', ' ');
		if (at == null) {
			fetchMatch = null;
			return "You haven't seen any " + name + " yet.";
		}
		begin(Kind.PICKUP);
		until = now() + 20L * 60 * Math.max(2, n);
		pickupId = drop;
		pickupAt = at;
		pickupGoneAt = -1;
		fetchDrop = drop;
		fetchAt = now();
		fetchWant = Math.max(1, n);
		items = new String[] {drop};
		what = name;
		had = countItem(drop);
		return String.format(java.util.Locale.ROOT, "You will get %d %s from the %s %.0f blocks away.", fetchWant, drop.replace('_', ' '), name,
				Math.sqrt(at.distSqr(c.player.blockPosition())));
	}

	/** The nearest block that counts (a side open to the air) within 16, or the nearest its eyes saw of that kind. */
	private BlockPos findBlock(java.util.function.Predicate<String> match, String kind) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-16, -6, -16), feet.offset(16, 6, 16))) {
			if (!match.test(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath())) continue;
			if (skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ())) || underwater(q) || lavaBehind(level, q)) continue;
			boolean open = false;
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) open |= level.getBlockState(q.relative(d)).canBeReplaced();
			if (open && (best == null || q.distSqr(feet) < best.distSqr(feet))) best = q.immutable();
		}
		if (best == null) best = c.eyes.nearest(kind, Eyes.RANGE, k -> skip.contains(Perception.Beliefs.key(BlockPos.of(k).getX(), BlockPos.of(k).getY(), BlockPos.of(k).getZ())));
		if (best == null && c.mod.config.luck >= 2 && java.util.Set.of("obsidian", "diamond", "iron", "coal", "lapis", "gold", "redstone", "copper", "emerald", "sugar_cane").contains(kind)) {   // knowing everything: the nearest it knows of, seen or not
			for (BlockPos q : c.hunch.oresNear(kind, feet, 128, 160, 12)) {
				if (skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ())) || underwater(q) || lavaBehind(level, q)
						|| level.getFluidState(q.above()).is(net.minecraft.tags.FluidTags.WATER) && level.getFluidState(q.above(2)).is(net.minecraft.tags.FluidTags.WATER)   // (at the bottom of a lake: a dive, not a dig)
						|| !kind.equals("sugar_cane") && waterNear(level, q, 2)) continue;   // (by an aquifer, down deep: where it drowns; a dry one)
				best = q;
				break;
			}
		}
		return best;
	}

	private Action pickupNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		if (fetchMatch != null) {                                            // fetching for a plan: n of them, one block after another
			if (countItem(fetchDrop) >= had + fetchWant) {
				fetchMatch = null;
				finish("Got " + fetchWant + " " + fetchDrop.replace('_', ' ') + "!");
				return null;
			}
			ItemEntity drop = dropToPickUp();
			if (drop != null) {
				doing = "picking up " + fetchDrop.replace('_', ' ');
				return c.stepTo(drop.position());
			}
			boolean noWay = now() - c.walker.diveRefusedAt < 2 && now() - fetchAt > 40 || now() - fetchAt > 20 * 180;   // (too long under water to get there, or three minutes on this one: the next)
			if (noWay) c.journal("way", "lets the " + fetchKind.replace('_', ' ') + " at " + pickupAt.toShortString() + " go: no way there it can take (" + c.walker.lastProblem + ")");
			if (!noWay && fetchMatch.test(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pickupAt).getBlock()).getPath())) {
				doing = String.format(java.util.Locale.ROOT, "getting %s (%d of %d)", fetchDrop.replace('_', ' '), countItem(fetchDrop) - had, fetchWant);
				return reach(pickupAt);
			}
			if (noWay) pickupGoneAt = now() - 20;
			if (pickupGoneAt < 0) pickupGoneAt = now();
			if (now() - pickupGoneAt < 20) return Action.IDLE;                // (a moment for the drop to land)
			skip.add(Perception.Beliefs.key(pickupAt.getX(), pickupAt.getY(), pickupAt.getZ()));
			BlockPos next = findBlock(fetchMatch, fetchKind);
			if (next == null) {
				int got = countItem(fetchDrop) - had;
				fetchMatch = null;
				finish(got > 0 ? "That's all the " + what + " I can find (" + got + ")." : "I can't find any more " + what + ".");
				return null;
			}
			pickupAt = next;
			pickupGoneAt = -1;
			fetchAt = now();
			return Action.IDLE;
		}
		if (countItem(pickupId) > had || pickupId.equals("torch") && countItem("torch") > had) {
			finish("Got the " + what + "!");
			return null;
		}
		ItemEntity drop = dropToPickUp();
		if (drop != null) {
			doing = "picking up the " + what;
			return c.stepTo(drop.position());
		}
		if (isKind(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pickupAt).getBlock()).getPath(), pickupId)) {
			doing = "breaking the " + what + " to take it";
			return reach(pickupAt);
		}
		if (pickupGoneAt < 0) pickupGoneAt = now();
		if (now() - pickupGoneAt > 60) finish("Hmm, the " + what + " is gone.");
		return Action.IDLE;
	}

	// ------------------------------------------------------------------------------ blocks and mobs, for something
	private String blocksId, blocksItem, slayType;

	/**
	 * Mine blocks of one kind it can see (gravel for flint, obsidian for a portal) until it has this many of an item
	 * (flint drops from gravel only now and then, like for anyone): the plan, or why not.
	 */
	String mineFor(String block, String item, int amount, String what) {
		BlockPos at = findVisible(block);
		begin(Kind.BLOCKS);
		until = now() + 20 * 60 * 4;
		blocksId = block;
		blocksItem = item;
		items = new String[] {item, block};
		this.what = what;
		lookFor = block.replace('_', ' ');
		want = amount;
		had = countItem(item);
		return at == null ? "You will look around for " + lookFor + " to get " + what + "."
				: String.format(java.util.Locale.ROOT, "You will mine the %s %.0f blocks away to get %s.", lookFor, Math.sqrt(at.distSqr(c.player.blockPosition())), what);
	}

	private Action blocksNext() {
		if (countItem(blocksItem) - had >= want) {
			finish("Got the " + what + "!");
			return null;
		}
		ItemEntity drop = dropToPickUp();
		if (drop != null) {
			doing = "picking up " + c.itemKey(drop.getItem()).replace('_', ' ');
			return c.stepTo(drop.position());
		}
		if (target == null || !isKind(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(c.player.level().getBlockState(target).getBlock()).getPath(), blocksId)
				|| skip.contains(Perception.Beliefs.key(target.getX(), target.getY(), target.getZ()))) {
			target = findVisible(blocksId);
			while (target != null && skip.contains(Perception.Beliefs.key(target.getX(), target.getY(), target.getZ()))) {
				target = null;                                                 // (a skipped one: look again in a moment)
			}
			closest = Double.MAX_VALUE;
			lastCloser = now();
		}
		if (target == null) {
			doing = "looking for " + lookFor;
			return lookAround();
		}
		double d = Math.sqrt(target.distSqr(c.player.blockPosition()));
		if (d < closest - 0.5) {
			closest = d;
			lastCloser = now();
		} else if (now() - lastCloser > 300) {
			skip.add(Perception.Beliefs.key(target.getX(), target.getY(), target.getZ()));
			target = null;
			return null;
		}
		doing = String.format(java.util.Locale.ROOT, "mining %s for %s (%d of %d)", lookFor, what, countItem(blocksItem) - had, want);
		return reach(target);
	}

	/**
	 * Hunt a kind of mob for what it drops (spiders for string, chickens for feathers, endermen for pearls, blazes
	 * for rods), until it has this many of the item.
	 */
	String slay(String mob, String item, int amount, String what) {
		begin(Kind.SLAY);
		until = now() + 20 * 60 * 5;
		slayType = mob;
		items = new String[] {item};
		this.what = what;
		want = amount;
		had = countItem(item);
		prey = nearestOf(mob);
		return prey == null ? "You will look for a " + mob.replace('_', ' ') + " to get " + what + "."
				: String.format(java.util.Locale.ROOT, "You will fight the %s %.0f blocks away for %s.", mob.replace('_', ' '), c.player.distanceTo(prey), what);
	}

	private LivingEntity nearestOf(String type) {
		LivingEntity best = null;
		for (LivingEntity e : c.player.level().getEntitiesOfClass(LivingEntity.class, c.player.getBoundingBox().inflate(40), x -> x.isAlive()
				&& net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).getPath().equals(type))) {
			if (!WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, e) && c.player.distanceTo(e) > Perception.NEAR) continue;
			if (best == null || c.player.distanceTo(e) < c.player.distanceTo(best)) best = e;
		}
		return best;
	}

	private Action slayNext() {
		if (countItem(items[0]) - had >= want) {
			finish("Got the " + what + "!");
			return null;
		}
		ItemEntity drop = dropToPickUp();
		if (drop != null) {
			doing = "picking up " + c.itemKey(drop.getItem()).replace('_', ' ');
			return c.stepTo(drop.position());
		}
		if (prey == null || !prey.isAlive() || c.player.distanceTo(prey) > 48) prey = nearestOf(slayType);
		if (prey == null && c.mod.config.luck >= 2) {                         // knowing everything: the nearest one about, seen or not
			LivingEntity far = null;
			for (LivingEntity e : c.player.level().getEntitiesOfClass(LivingEntity.class, c.player.getBoundingBox().inflate(128, 48, 128), x -> x.isAlive()
					&& net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).getPath().equals(slayType)))
				if (far == null || c.player.distanceTo(e) < c.player.distanceTo(far)) far = e;
			if (far != null) {
				doing = "going to the " + slayType.replace('_', ' ') + " it knows of";
				if (c.player.distanceTo(far) < 16) prey = far;
				return c.walkTo(far.position());
			}
		}
		if (prey == null) {
			doing = "looking for a " + slayType.replace('_', ' ');
			return lookAround();
		}
		doing = String.format(java.util.Locale.ROOT, "fighting a %s for %s (%d of %d)", slayType.replace('_', ' '), what, countItem(items[0]) - had, want);
		double d = c.player.distanceTo(prey);
		if (d <= c.player.entityInteractionRange()) {
			if (c.player.getAttackStrengthScale(0.5f) < 0.9f) return Action.IDLE;
			c.hands.hit(prey);
			c.acted = true;
			return Action.ATTACK;
		}
		if (d > 6 && c.hands.hasBow() && c.player.hasLineOfSight(prey)) {            // out of reach (a blaze up there): the bow
			Action shot = c.shoot(prey.position().add(0, prey.getBbHeight() * 0.6, 0), prey.getDeltaMovement());
			if (shot != null) return shot;
		}
		return c.walkTo(prey.position());
	}

	// ------------------------------------------------------------------------------ smelting
	private BlockPos furnace;
	private long checkFurnaceAt;

	/**
	 * Smelt its raw iron (or gold) in a furnace, like a player: one nearby, or one it carries or makes from 8
	 * cobblestone, put down next to it; the ore on top, fuel below (coal, charcoal, or planks), and it waits by it and
	 * takes the ingots out.
	 */
	/** Raw food it would cook (a player cooks it: it fills you up far more). */
	private static final String[] RAW_FOOD = {"beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "potato"};

	int rawFood() {
		int n = 0;
		for (String f : RAW_FOOD) n += countItem(f);
		return n;
	}

	private static boolean isRawFood(ItemStack st) {
		String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
		for (String f : RAW_FOOD) if (n.equals(f)) return true;
		return false;
	}

	private int cooked() {
		int n = countItem("iron_ingot") + countItem("gold_ingot") + countItem("netherite_scrap");
		for (String f : new String[] {"cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "cooked_cod",
				"cooked_salmon", "baked_potato"}) n += countItem(f);
		return n;
	}

	/** Something else its plan wants smelted (sand for glass, a log for charcoal, cobblestone for stone...), or null. */
	private String smeltInput;

	/** Smelt what it has (raw ore, raw food) and, for its plan, that too. */
	String smelt(String input) {
		smeltInput = input;
		String s = smelt();
		if (!s.startsWith("You will")) smeltInput = null;
		return s;
	}

	private int toSmelt() {
		return count("raw_iron") + count("raw_gold") + countItem("ancient_debris") + rawFood() + (smeltInput != null ? countItem(smeltInput) : 0);
	}

	private boolean smeltable(ItemStack st) {
		return isItem(st, "raw_iron") || isItem(st, "raw_gold") || isItem(st, "ancient_debris") || isRawFood(st) || smeltInput != null && isItem(st, smeltInput);
	}

	String smelt() {
		int raw = toSmelt();
		if (raw == 0) return "You have nothing to smelt.";
		if (fuel() == 0) return "You have nothing to burn in a furnace (coal, charcoal or wood).";
		furnace = findFurnace();
		if (furnace == null && countItem("furnace") == 0 && countItem("cobblestone") + countItem("cobbled_deepslate") + countItem("blackstone") < 8
				&& c.crafter.pickTier() < 1) return "You need 8 cobblestone for a furnace.";   // (with a pickaxe it gets the stone first)
		begin(Kind.SMELT);
		until = now() + 20 * (12L * raw + 90);
		want = raw;
		had = cooked();
		checkFurnaceAt = 0;
		return "You will smelt " + raw + " raw ore and food in a furnace.";
	}

	/** What it could burn in a furnace (coal, charcoal, or wood: a log is four planks, three planks smelt two things). */
	int fuel() {
		return count("coal") + countItem("charcoal") + countItem("coal_block") * 9 + (count("log") * 4 + countPlanks()) / 3;
	}

	private int countPlanks() {
		var inv = c.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("_planks")) n += st.getCount();
		}
		return n;
	}

	/** While the furnace works: a block worth having within reach (ore first, then stone, then a log) it mines, or null. */
	private Action whileItSmelts() {
		if (c.hands.busy()) return Action.MINE;                               // (at it already)
		if (c.crafter.pickTier() < 1) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		float bestRank = 0;
		double reach = c.player.blockInteractionRange() - 0.5;
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-4, 0, -4), feet.offset(4, 2, 4))) {   // (at its level and up: it doesn't dig out its own floor)
			if (p.equals(furnace) || furnace != null && p.equals(furnace.below())) continue;
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
			boolean worth = id.endsWith("_ore") || id.equals("stone") || id.equals("deepslate") || id.equals("cobblestone") || id.endsWith("_log");
			if (!worth || c.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > reach || p.equals(c.hands.cantMine)) continue;
			if (c.hands.seePoint(level, p) == null) continue;
			Choices.Choice ch = c.choices.mine(p.immutable());            // Xen 2.0: mine it, or not, or later (and how much it's worth)
			if (!ch.yes() || ch.want <= bestRank) continue;
			best = p.immutable();
			bestRank = ch.want;
		}
		if (best == null || !c.hands.mine(best)) return null;
		c.acted = true;
		return Action.MINE;
	}

	private BlockPos findFurnace() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
			if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.FURNACE) && (best == null || p.distSqr(feet) < best.distSqr(feet))) best = p.immutable();
		}
		return best;
	}

	private Action smeltNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		int made = cooked() - had;
		if (furnace != null && !level.getBlockState(furnace).is(net.minecraft.world.level.block.Blocks.FURNACE)) furnace = null;
		if (furnace == null) furnace = findFurnace();
		if (furnace == null) {
			int stone = countItem("cobblestone") + countItem("cobbled_deepslate") + countItem("blackstone");
			if (countItem("furnace") == 0 && stone < 8) {                      // no furnace, and not the stone for one: that first (it doesn't stand about waiting)
				doing = "getting stone for a furnace (" + stone + " of 8)";
				int[] s = glance(new int[] {Blocks.STONE});
				if (s != null) return reach(new BlockPos(s[0], s[1], s[2]));
				return stairsDown();                                         // (none in sight: the stone under its feet, the staircase way)
			}
			if (countItem("furnace") == 0) {                                  // make one (8 cobblestone, in its crafting table)
				doing = "making a furnace";
				if (!c.crafter.hasOrder()) c.crafter.orderRecipe("furnace", 1);
				return null;
			}
			doing = "putting a furnace down";
			BlockPos feet = c.player.blockPosition();
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos at = feet.relative(d);
				if (level.getBlockState(at).canBeReplaced() && level.getBlockState(at.below()).isCollisionShapeFullBlock(level, at.below())
						&& c.hands.placeItem(at, st -> isItem(st, "furnace"), at.below(), net.minecraft.core.Direction.UP, -1)) {
					furnace = at;
					c.acted = true;
					return Action.PLACE;
				}
			}
			return lookAround();
		}
		if (!c.hands.canClick(furnace)) {                                    // (in reach and in sight: not through a wall)
			doing = "going to the furnace";
			return c.walkTo(Vec3.atBottomCenterOf(furnace));
		}
		if (now() < checkFurnaceAt) {
			doing = "waiting by the furnace (" + made + " of " + want + " done)";
			Action meanwhile = whileItSmelts();                               // (not standing there: ore, stone, a log in reach, like a player)
			return meanwhile != null ? meanwhile : Action.IDLE;
		}
		checkFurnaceAt = now() + 100;                                        // every five seconds a look
		c.hands.stop();
		c.hands.use(furnace);
		if (!(c.player.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu menu)) return Action.IDLE;
		// the ingots out, the ore in, fuel if the fire needs it
		if (menu.getSlot(2).hasItem()) Compat.click(menu, 2, true, c.player);
		int raw = toSmelt();
		if (!menu.getSlot(0).hasItem() && raw > 0) moveInto(menu, 0, this::smeltable);
		if (!menu.getSlot(1).hasItem() && (menu.getSlot(0).hasItem() || raw > 0)) {
			if (!moveInto(menu, 1, st -> isItem(st, "coal") || isItem(st, "charcoal"))) moveInto(menu, 1, st -> {
				String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
				return n.endsWith("_planks") || n.endsWith("_log");
			});
		}
		boolean empty = !menu.getSlot(0).hasItem() && !menu.getSlot(2).hasItem();
		c.player.closeContainer();
		Compat.swing(c.player);
		c.acted = true;
		if (empty && toSmelt() == 0) {
			smeltInput = null;
			made = cooked() - had;
			finish("Done at the furnace: " + made + " smelted and cooked!");
			return null;
		}
		return Action.IDLE;
	}

	/** Pick up a stack from its inventory (in the open screen) and put it in a slot, like a player with the mouse. */
	private boolean moveInto(net.minecraft.world.inventory.AbstractContainerMenu menu, int slot, java.util.function.Predicate<ItemStack> what) {
		for (var s : menu.slots) {
			if (s.index <= 2 || !s.hasItem() || !what.test(s.getItem())) continue;
			Compat.click(menu, s.index, false, c.player);                       // pick it up
			Compat.click(menu, slot, false, c.player);                          // put it down
			if (!menu.getCarried().isEmpty()) Compat.click(menu, s.index, false, c.player);   // the rest back
			return menu.getSlot(slot).hasItem();
		}
		return false;
	}

	/** Drops it came for (what it's gathering), close and on about its level; it walks over them to pick them up. */
	private static final Set<String> JUNK = Set.of("dirt", "granite", "andesite", "diorite", "tuff", "gravel", "calcite", "netherrack", "sand", "red_sand", "cobbled_deepslate");
	private long enoughAt;
	private boolean enough;

	/** Blocks enough for a night's hut and a pillar (cobblestone and dirt, 32): the rest of the dirt can lie. */
	private boolean enoughBlocks() {
		if (now() - enoughAt > 40) {
			enoughAt = now();
			enough = count("cobblestone") + countItem("dirt") >= 32;
		}
		return enough;
	}

	private ItemEntity dropToPickUp() {
		double feet = c.player.getY();
		ItemEntity best = null;
		for (ItemEntity drop : c.player.level().getEntitiesOfClass(ItemEntity.class, c.player.getBoundingBox().inflate(Perception.NEAR),
				x -> x.isAlive() && Math.abs(x.getY() - feet) <= 2.5 && !ignoredDrops.contains(x.getUUID()) && !x.isUnderWater())) {   // (sunk: not worth a dive)
			String k = c.itemKey(drop.getItem());
			boolean wanted = false;
			for (String i : items) wanted |= i.equals(k);
			// what it just mined is its too (a player picks up what they dig, cobblestone and all): close by, if there's room
			boolean mine = drop.distanceTo(c.player) < 5 && drop.getAge() < 20 * 30 && c.player.getInventory().getFreeSlot() >= 0;
			if (!wanted && !mine && !k.endsWith("_sapling") && !k.equals("stick") && !k.equals("apple")) continue;
			if (!wanted && JUNK.contains(k) && enoughBlocks()) continue;            // (dirt, granite...: it has plenty to build with; a player lets it lie)
			if (!wanted && (c.guide.leading() || onOreWay()) && (JUNK.contains(k) || k.equals("cobblestone") || k.equals("cobbled_deepslate")) && count("cobblestone") + countItem("cobbled_deepslate") + countItem("dirt") >= 8) continue;   // (on its way to ore it knows of: the stone it digs lies where it falls)
			if (drop.getUUID().equals(dropFor)) {                            // the one it's going for: it keeps to it (no dithering between two)
				best = drop;
				break;
			}
			if (best == null || (wanted ? 0 : 3) + drop.distanceTo(c.player) < (isWanted(best) ? 0 : 3) + best.distanceTo(c.player)) best = drop;   // (what it came for first)
		}
		if (best == null) {
			dropSince = -1;
			return null;
		}
		if (!best.getUUID().equals(dropFor)) {
			dropFor = best.getUUID();
			dropSince = now();
		} else if (now() - dropSince > 200) {                           // can't get to it (in a hole, on the leaves): leave it
			ignoredDrops.add(best.getUUID());
			return null;
		}
		return best;
	}

	private boolean isWanted(ItemEntity drop) {
		String k = c.itemKey(drop.getItem());
		for (String i : items) if (i.equals(k)) return true;
		return false;
	}

	private final Set<UUID> ignoredDrops = new HashSet<>();
	private UUID dropFor;
	private long dropSince = -1, nextGlance;
	private int[] glanced;
	private int[] glancedCats;

	/**
	 * Like a player glancing around: the closest block of these kinds within 14 blocks that shows a face to the air
	 * (it can see trunks between trees; ore buried in stone it can't). Stone means natural stone, never something built.
	 * Beyond 14 blocks it goes by what its eyes saw ({@link Perception.Beliefs}).
	 */
	/**
	 * The ore of these kinds it knows of: showing a face (not under water) within 14 blocks, and what its eyes saw within
	 * 48; not the ones it gave up on, not ones its pickaxe can't take. The nearest few, nearest first.
	 */
	private List<BlockPos> oreCandidates(int[] cats, int max) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		int tier = c.crafter.pickTier();
		boolean[] want = new boolean[Blocks.COUNT];
		for (int k : cats) if (k == Blocks.COAL || k == Blocks.IRON || k == Blocks.GOLD || k == Blocks.DIAMOND) want[k] = Crafter.tierFor(k) <= tier;
		if (c.personality.believes("gold_for_fools")) want[Blocks.GOLD] = false;          // ("gold is for fools")
		if (c.personality.believes("beauty_matters") && Crafter.tierFor(Blocks.GOLD) <= tier) want[Blocks.GOLD] = true;   // (gold is pretty)
		java.util.Set<BlockPos> found = new java.util.LinkedHashSet<>();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-GLANCE, -6, -GLANCE), feet.offset(GLANCE, 8, GLANCE))) {
			int cat = WorldSenses.category(level, q, level.getBlockState(q));
			if (!want[cat] || skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ())) || !open(level, q) || underwater(q) || lavaBehind(level, q)) continue;
			found.add(q.immutable());
		}
		for (int k = 0; k < Blocks.COUNT; k++) {
			if (!want[k]) continue;
			String kind = k == Blocks.COAL ? "coal" : k == Blocks.IRON ? "iron" : k == Blocks.GOLD ? "gold" : "diamond";
			var seen = c.eyes.seen.get(kind);
			if (seen == null) continue;
			for (var it = seen.keySet().iterator(); it.hasNext(); ) {
				BlockPos q = BlockPos.of(it.nextLong());
				if (q.closerThan(feet, Eyes.RANGE) && !skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ()))
						&& WorldSenses.category(level, q, level.getBlockState(q)) == k && !underwater(q)) found.add(q);
			}
		}
		List<BlockPos> out = new ArrayList<>(found);
		out.sort(java.util.Comparator.comparingDouble(q -> q.distSqr(feet)));
		return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
	}

	/** Lava beside or above it: mining it lets the lava in (below it can't flow up). */
	static boolean lavaBehind(ServerLevel level, BlockPos b) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (d != net.minecraft.core.Direction.DOWN && level.getFluidState(b.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		}
		return false;
	}

	/** Ore it can mine right from where it stands (showing, in sight, within reach): for mining on its way. */
	BlockPos oreWithinReach() {
		BlockPos o = oreInReach();
		if (o == null || skip.contains(Perception.Beliefs.key(o.getX(), o.getY(), o.getZ())) || lavaBehind((ServerLevel) c.player.level(), o)) return null;
		if (!c.choices.mine(o).yes()) return null;                            // Xen 2.0: its own yes (copper, a weak pickaxe, a monster close: no, or later)
		return c.player.getEyePosition().distanceTo(Vec3.atCenterOf(o)) <= c.player.blockInteractionRange() - 0.3 ? o : null;
	}

	/**
	 * Ore close by (5 blocks) that shows a face to the air and that it can see, and can mine with the pickaxe it has
	 * (iron: stone; gold, diamond: iron): like a player, it takes it on the way, whatever it came for. Null if none.
	 */
	private BlockPos oreInReach() {
		ServerLevel level = (ServerLevel) c.player.level();
		int tier = c.crafter.pickTier();
		if (tier < 1 || c.player.isUnderWater()) return null;              // (under water: air first, mining there is slow)
		BlockPos feet = c.player.blockPosition(), best = null;
		Vec3 eye = c.player.getEyePosition();
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-5, -3, -5), feet.offset(5, 5, 5))) {
			var state = level.getBlockState(q);
			int cat = WorldSenses.category(level, q, state);
			boolean lapis = state.is(net.minecraft.world.level.block.Blocks.LAPIS_ORE) || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_LAPIS_ORE);   // (for enchanting: a stone pickaxe takes it)
			if (cat != Blocks.COAL && cat != Blocks.IRON && cat != Blocks.GOLD && cat != Blocks.DIAMOND && !lapis) continue;
			if (cat == Blocks.GOLD && c.personality.believes("gold_for_fools")) continue;
			if ((lapis ? 2 : Crafter.tierFor(cat)) > tier || skip.contains(Perception.Beliefs.key(q.getX(), q.getY(), q.getZ()))) continue;
			boolean open = false;
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (level.getBlockState(q.relative(d)).isAir()) open = true;
			if (!open) continue;
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER,
					net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(q)) continue;   // (it has to see it)
			double d = eye.distanceToSqr(Vec3.atCenterOf(q)) + (cat == Blocks.DIAMOND ? -20 : cat == Blocks.IRON ? -8 : 0);
			if (d < bestD) {
				bestD = d;
				best = q.immutable();
			}
		}
		return best;
	}

	private int[] glance(int[] cats) {
		ServerLevel level = (ServerLevel) c.player.level();
		long now = now();
		if (now < nextGlance && cats == glancedCats) {
			if (glanced == null) return null;
			BlockPos g = new BlockPos(glanced[0], glanced[1], glanced[2]);
			if (!skip.contains(Perception.Beliefs.key(g.getX(), g.getY(), g.getZ())) && level.isLoaded(g)
					&& WorldSenses.category(level, g, level.getBlockState(g)) == glanced[4]) return glanced;
		}
		nextGlance = now + 40;
		glancedCats = cats;
		glanced = null;
		boolean[] want = new boolean[Blocks.COUNT];
		for (int k : cats) want[k] = true;
		BlockPos feet = c.player.blockPosition();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
		double best = Double.MAX_VALUE;
		int r = GLANCE;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				m.set(feet.getX() + dx, feet.getY(), feet.getZ() + dz);
				if (!level.isLoaded(m)) continue;
				for (int dy = -6; dy <= 8; dy++) {
					m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					var state = level.getBlockState(m);
					int cat = WorldSenses.category(level, m, state);
					if (!want[cat]) continue;
					if (cat == Blocks.STONE && !WorldSenses.isNaturalStone(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())) continue;
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz) + 4 * Math.max(0, dy - 1)   // high up counts as further;
							+ (dy < 0 ? 1.5 * -dy + (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 ? 8 : 0) : 0);   // down, and right under it, too: a face at its own level first (no crater round its feet)
					if (cat == Blocks.LOG && lastLog != null && Math.abs(m.getX() - lastLog.getX()) <= 1 && Math.abs(m.getZ() - lastLog.getZ()) <= 1
							&& m.getY() >= lastLog.getY() && m.getY() - feet.getY() <= 6) {
						d = Math.sqrt(dx * dx + dz * dz) - 8;                            // the rest of the tree it's chopping, first
					}
					if (d >= best || skip.contains(Perception.Beliefs.key(m.getX(), m.getY(), m.getZ()))) continue;
					boolean shows = false;
					for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
						n.setWithOffset(m, side);
						var ns = level.getBlockState(n);                              // open to the air (not under water: it would drown)
						shows |= ns.canBeReplaced() && ns.getFluidState().isEmpty() || ns.is(net.minecraft.tags.BlockTags.LEAVES);
					}
					if (!shows) continue;
					if (cat == Blocks.LOG && !WorldSenses.treeLog(level, m)) {            // a house's logs, not a tree: leave them
						skip.add(Perception.Beliefs.key(m.getX(), m.getY(), m.getZ()));
						continue;
					}
					best = d;
					glanced = new int[] {m.getX(), m.getY(), m.getZ(), 1, cat};
				}
			}
		}
		if (glanced == null) glanced = fromEyes(level, cats);
		return glanced;
	}

	/** Nothing close by: what its eyes saw further off (48 blocks), the nearest one still there. */
	private int[] fromEyes(ServerLevel level, int[] cats) {
		BlockPos best = null;
		int bestCat = -1;
		for (int cat : cats) {
			String kind = cat == Blocks.LOG ? "log" : cat == Blocks.COAL ? "coal" : cat == Blocks.IRON ? "iron" : cat == Blocks.GOLD ? "gold"
					: cat == Blocks.DIAMOND ? "diamond" : null;
			if (kind == null) continue;
			BlockPos p = c.eyes.nearest(kind, Eyes.RANGE, k -> skip.contains(Perception.Beliefs.key(BlockPos.of(k).getX(), BlockPos.of(k).getY(), BlockPos.of(k).getZ()))
					|| underwater(BlockPos.of(k)));                               // (not the bed of a lake: it would have to dive for it)
			if (p == null || cat == Blocks.LOG && !WorldSenses.treeLog(level, p)) continue;
			if (best == null || p.distSqr(c.player.blockPosition()) < best.distSqr(c.player.blockPosition())) {
				best = p;
				bestCat = cat;
			}
		}
		if (best == null && c.crafter.pickTier() >= 1) {                     // no ore in sight: the simple rule, dark stone under the grass has ore
			boolean ore = false;
			for (int cat : cats) ore |= cat == Blocks.COAL || cat == Blocks.IRON || cat == Blocks.GOLD || cat == Blocks.DIAMOND;
			if (ore) {
				best = c.eyes.nearest("deepstone", 24, k -> skip.contains(Perception.Beliefs.key(BlockPos.of(k).getX(), BlockPos.of(k).getY(), BlockPos.of(k).getZ()))
						|| underwater(BlockPos.of(k)));
				if (best != null) bestCat = Blocks.STONE;
			}
		}
		return best == null ? null : new int[] {best.getX(), best.getY(), best.getZ(), 1, bestCat};
	}

	/** How far it looks around for what it's gathering. */
	static final int GLANCE = 14;
	/** The last log it went for (the rest of that tree comes first: players don't leave floating trunks). */
	private BlockPos lastLog;

	/** Only water around it (a river bed, the bottom of a lake): it would have to dive for it. */
	/** Water within r blocks of it (a cube), in loaded chunks only. */
	private static boolean waterNear(ServerLevel level, BlockPos b, int r) {
		for (BlockPos q : BlockPos.betweenClosed(b.offset(-r, -r, -r), b.offset(r, r, r)))
			if (level.isLoaded(q) && level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) return true;
		return false;
	}

	private boolean underwater(BlockPos b) {
		ServerLevel level = (ServerLevel) c.player.level();
		if (!level.isLoaded(b)) return false;                                  // (never loads a chunk just to look)
		boolean water = false;
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
			var ns = level.getBlockState(b.relative(side));
			if (ns.getFluidState().isEmpty() && (ns.canBeReplaced() || ns.is(net.minecraft.tags.BlockTags.LEAVES))) return false;
			water |= !ns.getFluidState().isEmpty();
		}
		return water;
	}

	/**
	 * Can it mine this from where it stands, like a player: within reach, and the first thing its eyes meet on the way
	 * there (leaves in the way it clears first). Then the block to hit, else null.
	 */
	private BlockPos hitFromHere(BlockPos t) {
		ServerLevel level = (ServerLevel) c.player.level();
		Vec3 eye = c.player.getEyePosition(), center = Vec3.atCenterOf(t);
		double reach = c.player.blockInteractionRange() - 0.3;
		if (eye.distanceTo(center) > reach) return null;
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, center, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));                  // (past small things: grass, leaf litter, glow lichen)
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(t)) return t;
		BlockPos in = hit.getBlockPos();
		if (level.getBlockState(in).is(net.minecraft.tags.BlockTags.LEAVES) && eye.distanceTo(Vec3.atCenterOf(in)) <= reach) return in;
		return null;
	}

	/** Get to a block and mine it, the way a player would (dig down to it, climb one block to reach it). */
	private Action reach(BlockPos t) {
		Hands hands = c.hands;
		BlockPos feet = c.player.blockPosition();
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(t)) < 4.5) {   // right by it: 15 seconds and still there, it can't get at it
			if (!t.equals(working)) {
				working = t.immutable();
				workingSince = now();
			} else if (now() - workingSince > (c.player.level().getBlockState(t).is(net.minecraft.world.level.block.Blocks.OBSIDIAN) ? 1200 : 300)) {   // (obsidian takes its time: ten seconds with a diamond pickaxe, more in water)
				working = null;
				c.journal("does", "gives up on the block at " + t.toShortString() + " (can't get at it)");
				return giveUp(t);
			}
		}
		BlockPos hit = t.getY() >= feet.getY() ? hitFromHere(t) : null;   // (below its feet: the staircase way, never straight down)
		if (hit != null) {
			if (floods((ServerLevel) c.player.level(), hit)) return giveUp(t);   // (water behind it: not worth a flooded tunnel)
			Action a = mine(hit);
			if (a != null) return a;
		}
		int dx = t.getX() - feet.getX(), dy = t.getY() - feet.getY(), dz = t.getZ() - feet.getZ();
		if (Math.abs(dx) + Math.abs(dz) == 1) {
			int want = dx == 0 ? (dz < 0 ? 0 : 2) : (dx > 0 ? 1 : 3);
			if (hands.yaw != want) return Math.floorMod(want - hands.yaw, 4) == 3 ? Action.TURN_LEFT : Action.TURN_RIGHT;
			if (dy == 0 || dy == 1) {
				int pitch = dy;
				if (hands.pitch != pitch) return pitch > hands.pitch ? Action.LOOK_UP : Action.LOOK_DOWN;
				return Action.MINE;
			}
			if (dy >= 2 && dy <= 3 && c.player.getEyePosition().distanceTo(Vec3.atCenterOf(t)) <= c.player.blockInteractionRange() - 0.3
					&& hands.mine(t)) {                                        // up there but in reach: it looks up and mines it (no block to stand on)
				c.acted = true;
				return Action.MINE;
			}
			if (dy < 0) return digDown(t);
			if (dy > 1) {                                                // out of reach up there: another one
				skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
				return null;
			}
		}
		if (dx == 0 && dz == 0 && dy < 0) return digDown(t);
		if (dx == 0 && dz == 0 && dy > 1) {                              // straight above: step aside
			skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
			return null;
		}
		if (Math.abs(dx) + Math.abs(dz) <= 1 && dy < 0) return digDown(t);
		if (dx == 0 && dz == 0 && (dy == 0 || dy == 1) && hands.mine(t)) {   // standing in it (grass, a flower): at its feet
			c.acted = true;
			return Action.MINE;
		}
		return c.walkTo(Goal.nextTo(t));                                 // (to where it can mine it, not into it)
	}

	/** Dig the block under its feet, unless it knows there is lava or water right below. */
	/**
	 * Down to a block below it: a staircase, never straight down (a hole under your feet can drop you into lava or a
	 * cave). The block itself, once it's within reach and it can see a side of it, it just mines.
	 */
	/** The way its staircase down goes, and when it last dug at it: one way, kept, not wherever it happens to face. */
	private net.minecraft.core.Direction stairDir;
	private long stairDugAt = -1000;

	/** Down for stone the way a player does: a straight staircase one way (it doesn't turn as it looks about and wander off digging). */
	private Action stairsDown() {
		long now = now();
		if (stairDir == null || now - stairDugAt > 200) stairDir = c.player.getDirection();   // (a new staircase: the way it faces now)
		stairDugAt = now;
		BlockPos feet = c.player.blockPosition();
		return digDown(feet.relative(stairDir, 2).below(2));
	}

	/** The ore of this kind it knows of (knowing everything), and when it last looked them up. */
	private List<BlockPos> known = new ArrayList<>();
	private long knownAt = -1000;
	private String knownKind = "";

	/**
	 * Knowing everything, the way Baritone mines: every ore of the kind it's after that it knows of close by (a dozen,
	 * looked up again every few seconds, the mined ones dropped) is one goal for its way-finder, standing by any of
	 * them, digging the way there (the cheapest it can work out, through the rock as needed); one in arm's reach it
	 * mines at once. No way to any: the nearest is let go, the rest stay. Null: none known.
	 */
	private long oreWalkAt = -1000, oreBestAt, fetchAt;
	private double oreBest = Double.MAX_VALUE;
	private String oreBestKind = "";

	/** On its way to ore it knows of just now: what lies about (the stone it dug, its pillars) can stay where it is. */
	boolean onOreWay() {
		return busy() && now() - oreWalkAt < 100;
	}

	private Action knownOre(String kind, int got, int want) {
		var p = c.player;
		ServerLevel level = (ServerLevel) p.level();
		BlockPos feet = p.blockPosition();
		if (!kind.equals(knownKind) || now() - knownAt > 100 || known.isEmpty()) {
			knownKind = kind;
			knownAt = now();
			known = new ArrayList<>(c.hunch.oresNear(kind, feet, 128, 160, 12));
		}
		known.removeIf(o -> !level.getBlockState(o).getBlock().getDescriptionId().contains(kind) || skip.contains(Perception.Beliefs.key(o.getX(), o.getY(), o.getZ())));
		if (known.isEmpty()) return null;
		for (BlockPos o : known) {
			if (p.getEyePosition().distanceTo(Vec3.atCenterOf(o)) <= p.blockInteractionRange() - 0.3) {   // right here: mine it
				target = o;
				doing = String.format(java.util.Locale.ROOT, "mining the %s it knew was here (%d of %d)", kind, got, want);
				Action a = reach(o);
				if (a != null) return a;
			}
		}
		BlockPos nearest = known.stream().min(java.util.Comparator.comparingDouble(o -> o.distSqr(feet))).get();
		double gap = Math.sqrt(nearest.distSqr(feet));
		if (gap < oreBest - 1 || !kind.equals(oreBestKind)) {
			oreBest = gap;
			oreBestAt = now();
			oreBestKind = kind;
		} else if (now() - oreBestAt > 300 || now() - c.walker.diveRefusedAt < 2 && now() - oreBestAt > 20) {   // fifteen seconds and no closer (walled off), or the way there is too long under water: the next one
			c.journal("way", "lets the " + kind + " at " + nearest.toShortString() + " go: no way there it can take (" + c.walker.lastProblem + ")");
			c.hunch.taken(nearest);
			skip.add(Perception.Beliefs.key(nearest.getX(), nearest.getY(), nearest.getZ()));
			known.remove(nearest);
			oreBest = Double.MAX_VALUE;
			oreBestAt = now();
			if (known.isEmpty()) return null;
		}
		List<Goal> goals = new ArrayList<>();
		for (BlockPos o : known) goals.add(Goal.nextTo(o));
		doing = String.format(java.util.Locale.ROOT, "digging to the %s it knows of (%d known, %d of %d so far)", kind, known.size(), got, want);
		Action a = c.walkTo(Goal.anyOf(goals));
		if (a != null) oreWalkAt = now();
		if (a == null) {                                                       // no way to any of them: the nearest let go, on with the rest
			BlockPos near = known.stream().min(java.util.Comparator.comparingDouble(o -> o.distSqr(feet))).get();
			c.hunch.taken(near);
			skip.add(Perception.Beliefs.key(near.getX(), near.getY(), near.getZ()));
			known.remove(near);
		}
		return a;
	}

	/**
	 * One step along its guide's way: the blocks in the way mined (its head's room first, top down: a staircase step
	 * down needs three clear, one on the level two, a step up the room over its head too), then it steps in.
	 */
	private Action routeStep(BlockPos n) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		int dx = n.getX() - feet.getX(), dy = n.getY() - feet.getY(), dz = n.getZ() - feet.getZ();
		if (Math.abs(dx) + Math.abs(dz) > 1 || Math.abs(dy) > 1) return c.walkTo(Goal.block(n));   // (off its way: back onto it)
		BlockPos[] clear = dy < 0 ? new BlockPos[] {n.above(2), n.above(), n} : dy > 0 ? new BlockPos[] {feet.above(2), n.above(), n} : new BlockPos[] {n.above(), n};
		for (BlockPos b : clear) {
			var st = level.getBlockState(b);
			if (st.canBeReplaced() || st.getCollisionShape(level, b).isEmpty()) continue;
			if (lavaNext(level, b)) return giveUp(b);
			Action a = mine(b);
			if (a != null) return a;
		}
		return c.walkTo(Goal.block(n));                                       // (clear: a step in)
	}

	private Action digDown(BlockPos t) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		int dx = t.getX() - feet.getX(), dz = t.getZ() - feet.getZ();
		boolean under = dx == 0 && dz == 0;
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(t)) <= c.player.blockInteractionRange() - 0.5 && open(level, t)
				&& !(under && t.getY() < feet.getY() - 1)) {
			if (under && dangerBelow(level, t)) return giveUp(t);
			return mine(t);
		}
		int k = c.hands.yaw;
		if (!under) {
			double best = -2;
			for (int i = 0; i < 4; i++) {
				int[] f = Perception.forward(i);
				double dot = (dx * f[0] + dz * f[1]) / Math.max(1e-6, Math.hypot(dx, dz));
				if (dot > best) {
					best = dot;
					k = i;
				}
			}
		}
		int[] f = Perception.forward(k);
		BlockPos ahead = feet.offset(f[0], 0, f[1]), step = ahead.below();
		if (dangerBelow(level, step)) return giveUp(t);
		for (BlockPos b : new BlockPos[] {ahead.above(), ahead, step}) {
			if (level.getBlockState(b).canBeReplaced()) continue;
			if (lavaNext(level, b) || floods(level, b)) return giveUp(t);
			return mine(b);
		}
		if (k != c.hands.yaw) return Math.floorMod(k - c.hands.yaw, 4) == 3 ? Action.TURN_LEFT : Action.TURN_RIGHT;
		if (c.hands.pitch != 0) return c.hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP;
		return Action.FORWARD;                                           // one step down
	}

	private Action mine(BlockPos b) {
		ServerLevel level = (ServerLevel) c.player.level();
		boolean quenched = level.getBlockState(b).is(net.minecraft.world.level.block.Blocks.OBSIDIAN) && level.getFluidState(b.above()).is(net.minecraft.tags.FluidTags.WATER);   // (obsidian with water running over it: the hole fills with water, the lava by it turns to obsidian too)
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {   // lava right behind it (a player hears it pop, sees it drip): leave it
			if (!quenched && d != net.minecraft.core.Direction.DOWN && level.getFluidState(b.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) {
				c.journal("does", "leaves the block at " + b.toShortString() + " (lava behind it)");
				if (c.personality.chattiness > 0.4f) c.chatter(c.personality.say("lava"), false);
				return giveUp(b);
			}
		}
		if (!c.hands.mine(b)) {
			if (b.equals(c.hands.cantMine)) giveUp(b);                    // it can't break that: another one, not waiting forever
			return null;
		}
		c.acted = true;
		return Action.MINE;
	}

	private Action giveUp(BlockPos t) {
		skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
		target = null;
		return null;
	}

	/** Can it see a side of the block (something open next to it)? */
	private static boolean open(ServerLevel level, BlockPos b) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (level.getBlockState(b.relative(d)).canBeReplaced()) return true;
		return false;
	}

	/** Lava or water right under where it would stand, or a long drop. */
	private static boolean dangerBelow(ServerLevel level, BlockPos at) {
		int air = 0;
		for (int k = 1; k <= 3; k++) {
			var st = level.getBlockState(at.below(k));
			if (!st.getFluidState().isEmpty()) return true;
			if (st.canBeReplaced()) air++;
		}
		return air == 3;
	}

	private static boolean lavaNext(ServerLevel level, BlockPos b) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (level.getFluidState(b.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		}
		return false;
	}

	/**
	 * Under the ground, water next to it (a source, or water coming down from above): dig it and the tunnel floods, and
	 * a flooded tunnel is how you drown. Up in the open it doesn't matter (the water just runs out).
	 */
	private boolean floods(ServerLevel level, BlockPos b) {
		BlockPos feet = c.player.blockPosition();
		if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) <= feet.getY() + 2) return false;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			if (d == net.minecraft.core.Direction.DOWN) continue;
			var f = level.getFluidState(b.relative(d));
			if (f.is(net.minecraft.tags.FluidTags.WATER) && (f.isSource() || d == net.minecraft.core.Direction.UP)) return true;
		}
		return false;
	}

	/** Its wander is where it has a feeling the animals are (luck), not just somewhere new. */
	private boolean herdHunch;

	/** Knows of nothing to go for: look all around (its view is only 90 degrees), then walk somewhere new. */
	private Action lookAround() {
		if (kind == Kind.HUNT && (wander == null || now() > wanderUntil || c.player.position().distanceTo(wander) < 3)) {   // it knows where the animals are, and wanders that way
			BlockPos herd = c.hunch.animalsAt(forWool);
			if (herd != null && !herd.closerThan(c.player.blockPosition(), 6)) {
				if (wander == null || wander.distanceTo(Vec3.atBottomCenterOf(herd)) > 8)
					c.journal("thinks", String.format(java.util.Locale.ROOT, "has a feeling there are %s at %d %d %d (%.0f blocks): goes there", forWool ? "sheep" : "animals",
							herd.getX(), herd.getY(), herd.getZ(), Math.sqrt(herd.distSqr(c.player.blockPosition()))));
				wander = Vec3.atBottomCenterOf(herd);
				wanderUntil = now() + 20 * 60;
				scans = 0;
				herdHunch = true;
			} else herdHunch = false;
		}
		if (kind == Kind.HUNT && herdHunch && wander != null && now() <= wanderUntil) {
			doing = forWool ? "going to the sheep it has a feeling about" : "going to the animals it has a feeling about";
			c.run(c.player.position().distanceTo(wander) > 20);
			return c.wanderTo(wander);                                     // (the way a hungry player happens on them: roughly that way, not a beeline)
		}
		if (scans < 4) {
			scans++;
			if (c.hands.pitch != 0) return c.hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP;
			return Action.TURN_RIGHT;
		}
		Vec3 here = c.player.position();
		if (wander == null || now() > wanderUntil || here.distanceTo(wander) < 2) {
			double lucky = kind == Kind.HUNT && !forWool ? c.hunch.animalWay() : Double.NaN;   // (hungry: its feet happen to go the way the animals are, now and then)
			double a = Double.isNaN(lucky) ? random.nextDouble() * Math.PI * 2 : lucky;
			wander = here.add(Math.cos(a) * 24, 0, Math.sin(a) * 24);
			wanderUntil = now() + 400;
			herdHunch = false;
			if (scans++ > 4) scans = 0;                                  // and look around again when it gets there
		}
		return c.walkTo(wander);
	}

	/** Animals people eat: never a pet, a named one, or one on a lead (someone's). */
	static boolean food(LivingEntity a) {
		String n = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath();
		boolean eaten = n.equals("cow") || n.equals("pig") || n.equals("sheep") || n.equals("chicken") || n.equals("rabbit") || n.equals("mooshroom");
		return eaten && !a.hasCustomName() && !(a instanceof net.minecraft.world.entity.Mob m && m.isLeashed());
	}

	LivingEntity nearestAnimal() {
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		Choices.Choice pick = null;
		for (Animal a : c.player.level().getEntitiesOfClass(Animal.class, c.player.getBoundingBox().inflate(Eyes.RANGE),
				x -> x.isAlive() && !x.isBaby() && food(x))) {
			double d = c.player.distanceTo(a) - (forWool && a instanceof net.minecraft.world.entity.animal.sheep.Sheep ? 40 : 0);   // (sheep first, for a bed)
			if (d < bestD && WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, a)) {
				Choices.Choice ch = c.choices.animal(a);                    // Xen 2.0: kill it (or shear it), or not, or later: what it drops against why not
				if (!ch.yes() && c.player.getFoodData().getFoodLevel() > 6) continue;   // (starving: any animal will do)
				bestD = d;
				best = a;
				pick = ch;
			}
		}
		if (best != null) c.choices.picked(best, pick);
		return best;
	}

	/** The wool it carries (any color). */
	int wool() {
		return MindSense.count(c, n -> n.endsWith("_wool"));
	}

	/** When it last saw a sheep (hunting for wool: no sheep for a while, it gives up). */
	private long sinceSheep;

	private Action huntNext() {
		int got = (forWool ? wool() : count("food")) - had;
		if (got >= want) {
			finish(forWool ? "Got the wool!" : "Got some food!");
			return null;
		}
		if (prey == null || !prey.isAlive() || c.player.distanceTo(prey) > 48) prey = nearestAnimal();
		if (forWool && prey != null && !(prey instanceof net.minecraft.world.entity.animal.sheep.Sheep)) prey = null;   // (wool: only sheep)
		if (forWool && prey != null) sinceSheep = now();
		if (forWool && now() - sinceSheep > 600) {
			finish("No sheep around.");
			return null;
		}
		if (prey != null) {
			doing = String.format(java.util.Locale.ROOT, "hunting a %s %.1f blocks away",
					prey.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), c.player.distanceTo(prey));
			if (c.player.distanceTo(prey) <= c.player.entityInteractionRange() && c.choices.shearInstead(prey)) {
				doing = "shearing a sheep";                                   // (the sheep lives, and grows its wool back)
				int shears = c.hands.hotbar(s -> s.getItem() == net.minecraft.world.item.Items.SHEARS);
				return c.critters.use(shears, (Animal) prey, () -> c.critters.sheared++);
			}
			if (c.choices.shearInstead(prey)) return c.walkTo(prey.position());
			if (c.player.distanceTo(prey) <= c.player.entityInteractionRange()) {
				if (c.player.getAttackStrengthScale(0.5f) < 0.9f) return Action.IDLE;
				c.hands.hit(prey);
				c.acted = true;
				return Action.ATTACK;
			}
			return c.walkTo(prey.position());
		}
		double feet = c.player.getY();
		for (ItemEntity drop : c.player.level().getEntitiesOfClass(ItemEntity.class, c.player.getBoundingBox().inflate(Perception.NEAR),
				x -> (x.getItem().has(net.minecraft.core.component.DataComponents.FOOD)
						|| forWool && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(x.getItem().getItem()).getPath().endsWith("_wool"))
						&& Math.abs(x.getY() - feet) <= 1.5)) {
			doing = forWool ? "picking up what the sheep dropped" : "picking up food";
			return c.stepTo(drop.position());                             // pick up what it hunted
		}
		if (c.player.getY() < 40 && !c.player.level().canSeeSky(c.player.blockPosition())) {   // (down in the caves: no animals to find, only lava to walk into)
			finish(forWool ? "No sheep down here." : "No animals down here.");
			return null;
		}
		doing = forWool ? "looking around for sheep" : "no animals in sight, looking around";
		return lookAround();
	}

	private List<ItemStack> giveable() {
		List<ItemStack> out = new java.util.ArrayList<>();
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && !s.isDamageableItem()) out.add(s);     // keeps its tools
		}
		return out;
	}

	private Action giveNext() {
		ServerPlayer to = c.server.getPlayerList().getPlayer(forWhom);
		if (to == null || to.level() != c.player.level()) {
			finish("I can't find you to give it to.");
			return null;
		}
		Vec3 gap = to.position().subtract(c.player.position());
		boolean below = Math.hypot(gap.x, gap.z) <= 3 && gap.y < 0 && gap.y > -12;       // items fall down to them
		if (Math.hypot(gap.x, gap.z) <= 3 && gap.y > 2.5) {                             // right above it: they come down
			if (!saidLooking) {
				c.chatter("I can't reach you up there. Come down and I'll hand it over.", true);
				saidLooking = true;
			}
			return Action.IDLE;
		}
		if (!below && c.player.distanceTo(to) > 3) {
			doing = String.format(java.util.Locale.ROOT, "bringing it to %s, %.0f blocks away", to.getName().getString(), c.player.distanceTo(to));
			return c.walkTo(to.position());
		}
		c.hands.face(to.getEyePosition());
		if (gaveAt >= 0) {                                             // given: a moment still, then it's done
			if (now() - gaveAt < 60) return Action.IDLE;
			gaveAt = -1;
			stop();
			return null;
		}
		if (now() < thinkUntil) {                                      // looking at them, thinking it over
			doing = "thinking about what to give " + to.getName().getString();
			return Action.IDLE;
		}
		if (!below && c.player.distanceTo(to) < 1.4) return Action.BACK;   // right on top of them: a step back, or it throws past them
		c.hands.face(to.position().add(0, 0.2, 0));                     // aimed at their feet, the way players hand things over
		var inv = c.player.getInventory();
		int left = want > 0 ? want : Integer.MAX_VALUE, given = 0;
		java.util.Map<String, Integer> kept = new java.util.HashMap<>();
		for (int i = 0; i < 36 && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || s.isDamageableItem()) continue;
			String key = c.itemKey(s);
			if (!giveItem.equals("all") && !key.equals(giveItem)) continue;
			int spare = s.getCount();
			if (giveItem.equals("all")) {                              // everything but what it needs itself
				int keepLeft = keepFor(key) - kept.getOrDefault(key, 0);
				int k = Math.max(0, Math.min(keepLeft, spare));
				kept.merge(key, k, Integer::sum);
				spare -= k;
			}
			int n = Math.min(left, spare);
			if (n <= 0) continue;
			c.hands.toss(inv.removeItem(i, n));
			left -= n;
			given += n;
		}
		c.acted = true;
		if (given == 0) {
			finish("I don't have that anymore.");
			return Action.IDLE;
		}
		c.chatter("Here you go!", !own);
		gaveAt = now();                                                // and it stands still while they pick it up
		return Action.IDLE;
	}

	/** When it tossed what it was asked for (it waits a moment, not walking over its own gift and taking it back). */
	private long gaveAt = -1;

	/** The dugout: the blocks it digs (feet and head, three deep into the hill), in order; null if it's building walls instead. */
	private List<BlockPos> dugout;
	/** Its dugout dug out (from then on it only goes in and closes the way behind it: no digging out its own door again). */
	private boolean dugoutDug;

	/**
	 * A hillside to dig into from here: three blocks deep, two high, natural ground all round (a floor under it, a roof
	 * over it, no lava or water next to it). The blocks to dig, in order; null if there's no such hill.
	 */
	private List<BlockPos> dugoutPlan(ServerLevel level, BlockPos feet) {
		net.minecraft.core.Direction facing = c.player.getDirection();
		for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[] {facing, facing.getClockWise(), facing.getCounterClockWise(), facing.getOpposite()}) {
			List<BlockPos> dig = new java.util.ArrayList<>();
			boolean ok = true;
			for (int k = 1; k <= 3 && ok; k++) {
				BlockPos f = feet.relative(d, k);
				for (BlockPos b : new BlockPos[] {f, f.above()}) {
					var st = level.getBlockState(b);
					if (!Walker.natural(st) || st.getCollisionShape(level, b).isEmpty()) ok = false;
					dig.add(b);
				}
				for (BlockPos b : new BlockPos[] {f.below(), f.above(2)}) {
					if (level.getBlockState(b).getCollisionShape(level, b).isEmpty()) ok = false;   // a floor and a roof
				}
				for (net.minecraft.core.Direction side : new net.minecraft.core.Direction[] {d.getClockWise(), d.getCounterClockWise()}) {
					for (BlockPos b : new BlockPos[] {f.relative(side), f.above().relative(side)}) if (!level.getFluidState(b).isEmpty()) ok = false;
				}
			}
			if (ok) return dig;
		}
		return null;
	}

	private Action dugoutNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		for (int i = 0; i < dugout.size() && !dugoutDug; i++) {             // dig it out, front to back (once: then the door it puts back stays put)
			BlockPos b = dugout.get(i);
			if (level.getBlockState(b).getCollisionShape(level, b).isEmpty()) continue;
			doing = "digging into the hill for the night";
			if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(b)) <= c.player.blockInteractionRange() - 0.5 && c.hands.mine(b)) {
				c.acted = true;
				return Action.MINE;
			}
			BlockPos stand = i < 2 ? c.player.blockPosition() : dugout.get(i - 2 - (i % 2));   // one step further in
			return c.walkTo(Vec3.atBottomCenterOf(stand));
		}
		dugoutDug = true;
		BlockPos inner = dugout.get(dugout.size() - 2), door = dugout.get(0);
		if (!c.player.blockPosition().equals(inner)) {                      // in, to the back
			doing = "going into its dugout";
			return c.walkTo(Vec3.atBottomCenterOf(inner));
		}
		for (BlockPos b : new BlockPos[] {door, door.above()}) {            // and the way in sealed behind it
			if (!level.getBlockState(b).canBeReplaced()) continue;
			doing = "sealing the way into its dugout";
			if (c.hands.placeAt(b, c.personality.material)) {
				c.acted = true;
				return Action.PLACE;
			}
			if (++waited > 40) break;
			return Action.IDLE;
		}
		dugout = null;
		stop();
		c.chatter(c.pick3("Safe in my little hole in the hill. Good night!", "Dug in for the night.", "Nice and cozy in here."), true);
		shelterBuilt = true;
		doing = "hiding in its dugout until morning";
		kind = Kind.HIDE;
		hidAt = now();
		until = now() + 1200;
		return Action.IDLE;
	}

	/** The spots of its hut it fills itself (empty when it planned it): in the morning it may take those down again. */
	private List<BlockPos> hutFilled;

	private Action shelterNext() {
		if (dugout != null) return dugoutNext();
		if (pit != null) return pitNext();
		ServerLevel level = (ServerLevel) c.player.level();
		if (hutFilled == null && walls != null) hutFilled = walls.stream().filter(q -> level.getBlockState(q).canBeReplaced()).map(BlockPos::immutable).toList();
		if (inside != null && !inside.contains(c.player.blockPosition()) && ++walked < 200) {   // the spot it picked: over there first
			BlockPos in = inside.get(0);
			for (BlockPos b : inside) if (b.distSqr(c.player.blockPosition()) < in.distSqr(c.player.blockPosition())) in = b;
			doing = "going to the spot for its " + shape;
			return c.walkTo(Vec3.atBottomCenterOf(in));
		}
		boolean done = true;
		int left = 0;
		for (BlockPos p : walls) if (level.getBlockState(p).canBeReplaced()) left++;
		doing = "building a " + shape + ", " + left + " blocks to go";
		java.util.function.Predicate<BlockPos> notInside = q -> inside == null || inside.stream().noneMatch(i -> i.getX() == q.getX() && i.getZ() == q.getZ());
		for (BlockPos p : walls) {
			if (!level.getBlockState(p).canBeReplaced()) continue;
			done = false;
			if (c.hands.placeSupported(p, c.personality.material, notInside)) {   // (a gap under a wall: filled first, never its own room)
				c.acted = true;
				waited = 0;
				return Action.PLACE;
			}
		}
		if (done) {
			stop();
			c.chatter("Done! I'm safe in my little " + shape + ".", true);   // (always said: you'll want to know where it is)
			shelterBuilt = true;
			doing = "hiding in its " + shape + " until morning";
			kind = Kind.HIDE;
			hidAt = now();
			until = now() + 1200;
			return Action.IDLE;
		}
		doing = "building a " + shape + ", " + left + " blocks to go, stuck: " + c.hands.cantPlace;
		if (c.hands.selfInWay != null && inside != null && !inside.isEmpty()) {   // standing where a wall goes: a step inside, away from it
			BlockPos block = c.hands.selfInWay;
			c.hands.selfInWay = null;
			BlockPos to = inside.stream().max(java.util.Comparator.comparingDouble(q -> q.distSqr(block))).orElse(inside.get(0));
			return c.walkTo(Vec3.atBottomCenterOf(to));
		}
		if (++waited == 12 && inside != null && inside.size() > 1 && moves < inside.size()) {   // it can't see that side from here: another spot inside
			BlockPos to = inside.get(++moves % inside.size());
			waited = 0;
			return c.walkTo(Vec3.atBottomCenterOf(to));
		}
		if (waited < 30) return Action.IDLE;                             // someone in the way? wait a little
		String why = c.hands.cantPlace;
		if (c.player.level().isDarkOutside() && (c.crafter.pickTier() >= 1 || diggableByHand(level, c.player.blockPosition()))) {
			List<BlockPos> hole = pitPlan(level, c.player.blockPosition());   // the night won't wait: a hole in the ground instead
			if (hole != null) {
				c.journal("does", "couldn't finish the " + shape + " (" + why + "): digs a hole for the night instead");
				walls = List.of();
				inside = null;
				pit = hole;
				nookTried = false;
				shape = "hole";
				waited = 0;
				return Action.IDLE;
			}
		}
		finish("I couldn't finish the " + shape + ": " + why + ".");
		return null;
	}

	/** Spots inside its shelter it has tried building from. */
	private int moves;
}
