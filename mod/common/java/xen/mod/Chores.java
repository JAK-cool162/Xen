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
	private UUID forWhom;
	private String giveItem;
	private final Set<Long> skip = new HashSet<>();
	private BlockPos target;
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

	void cancel() {
		kind = null;
		target = null;
		prey = null;
		walls = null;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private void begin(Kind k) {
		cancel();
		gaveAt = -1;
		kind = k;
		own = false;
		shelterBuilt = false;
		until = now() + (long) (TIME * c.personality.patience());
		skip.clear();
		saidLooking = false;
		scans = 0;
		wander = null;
		closest = Double.MAX_VALUE;
		lastCloser = now();
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
		for (int tries = 0; known != null && known[4] == Blocks.LOG && tries < 8; tries++) {   // a tree, not someone's house
			BlockPos k = new BlockPos(known[0], known[1], known[2]);
			ServerLevel level = (ServerLevel) c.player.level();
			if (!level.isLoaded(k) || WorldSenses.treeLog(level, k)) break;
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
		this.items = items;
		this.what = what;
		this.lookFor = lookFor;
	}

	String hunt(int amount) {
		begin(Kind.HUNT);
		want = Math.max(1, amount);
		had = count("food");
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
			case "cobblestone" -> Math.max(tier < 2 ? 3 : 0, c.goals.evening() ? 10 - count("dirt") : 0);   // (a shelter tonight: 10 blocks)
			case "dirt" -> c.goals.evening() ? 10 - count("cobblestone") : 0;
			case "food" -> c.player.getFoodData().getFoodLevel() < 14 ? 2 : 0;
			case "torch" -> 2;
			default -> 0;
		};
	}

	private String why(String key) {
		return switch (key) {
			case "log" -> "for your pickaxe";
			case "cobblestone" -> c.goals.evening() && 10 - count("dirt") > 3 ? "for a shelter tonight" : "for your stone tools";
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
		List<BlockPos> plan = shelterPlan(feet, build);
		int blocks = count("dirt", "cobblestone");
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
					if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(p.pos())) > c.player.blockInteractionRange()) {
						return c.walkTo(Vec3.atCenterOf(p.pos()));
					}
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
		if (now() > until && kind != Kind.HIDE) {
			finish(kind == Kind.GATHER && count(items) > had ? "I only found " + (count(items) - had) + " " + what + "."
					: "I couldn't do it, sorry.");
			return null;
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
					cancel();
					if (resume != null) c.mode = resume;                        // following again, as it was told
					resume = null;
					yield null;
				}
				yield Action.IDLE;
			}
			case EAT -> {
				cancel();
				yield c.player.getFoodData().needsFood() && count("food") > 0 ? Action.EAT : null;
			}
		};
	}

	private void finish(String say) {
		cancel();
		c.chatter(say, !own);
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
			finish("Got " + got + " " + what + "!");
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
		if (needs > c.crafter.pickTier() && !c.crafter.canMake(needs)) {   // its pickaxe broke, or it never had one
			finish("I can't mine " + what + " without " + Crafter.tierName(needs) + ".");
			return null;
		}
		ItemEntity drop = dropToPickUp();                               // what it just mined, lying on the ground
		if (drop != null) {
			doing = "picking up " + c.itemKey(drop.getItem()).replace('_', ' ');
			return c.walkTo(drop.position());
		}
		int[] known = glance(cats);                                    // what it can see around it (14 blocks)...
		for (int tries = 0; known == null && tries < 12; tries++) {       // ...or saw earlier, further away
			known = c.senses.nearestKnown(cats, 0.25, skip, 4);
			if (known == null || known[4] != Blocks.STONE && known[4] != Blocks.LOG) break;
			BlockPos k = new BlockPos(known[0], known[1], known[2]);       // what it saw: real stone, not a mushroom cap or a wall
			ServerLevel level = (ServerLevel) c.player.level();         // (and a tree, not someone's house)
			if (!level.isLoaded(k) || (known[4] == Blocks.LOG ? WorldSenses.treeLog(level, k)
					: WorldSenses.isNaturalStone(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(k).getBlock()).getPath()))) break;
			skip.add(Perception.Beliefs.key(k.getX(), k.getY(), k.getZ()));
			known = null;
		}
		doing = known == null ? "looking for " + lookFor : String.format(java.util.Locale.ROOT, "getting %s, %d of %d so far", what, got, want);
		if (known == null) {
			if (!saidLooking) {
				c.chatter("I haven't seen any " + lookFor + " yet, I'll look around.", !own);
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
	 * Go mining, like a player: a staircase down to where the ore is (iron and coal around y 16, diamonds deep down in
	 * the deepslate), then branch tunnels two high, three blocks apart. It mines the ore it sees in the tunnel walls
	 * (never ore it can't see), lights the way with torches if it has them, and stops when it has what it came for.
	 */
	String mine(int y, String what, int amount) {
		int tier = Math.max(c.crafter.pickTier(), c.crafter.canMake(2) ? 2 : c.crafter.canMake(1) ? 1 : 0);
		int need = what.equals("diamonds") ? 3 : what.equals("iron") ? 2 : 1;
		if (tier < need) return "You can't go mining for " + what + " yet: you need " + Crafter.tierName(need) + " first.";
		begin(Kind.MINE);
		until = now() + 20 * 60 * 6;                                        // six minutes down there at most
		if (what.equals("diamonds")) set(new int[] {Blocks.DIAMOND, Blocks.IRON, Blocks.GOLD, Blocks.COAL}, new String[] {"diamond"}, "diamonds", "diamond ore");
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
		return "You will go mining for " + what + ": a staircase down to about y " + y + ", then tunnels, mining the ore you see.";
	}

	private Action mineNext() {
		int got = count(items) - had;
		if (got >= want) {
			finish("I got " + got + " " + what + "! Heading back up.");
			return null;
		}
		ItemEntity drop = dropToPickUp();
		if (drop != null) {
			doing = "picking up " + c.itemKey(drop.getItem()).replace('_', ' ');
			return c.walkTo(drop.position());
		}
		int[] ore = glance(cats);                                            // ore showing in the walls (the tunnel shows it)
		if (ore != null && ore[1] <= c.player.getBlockY() + 4) {
			doing = String.format(java.util.Locale.ROOT, "mining %s, %d of %d %s so far", Blocks.NAMES[ore[4]].replace(" ore", "") + " ore", got, want, what);
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
		net.minecraft.core.Direction right = mineDir.getClockWise();
		BlockPos goal;
		if (mineBase == null) {                                              // down the staircase
			if (feet.getY() <= mineY + 1) {
				mineBase = feet;
				mineLeg = 0;
				legStarted = now();
				c.chatter("Down at y " + feet.getY() + ". Tunnels now.", false);
				return null;
			}
			int down = feet.getY() - mineY;
			goal = feet.relative(mineDir, down).below(down);
			doing = "digging a staircase down (y " + feet.getY() + ")";
		} else {                                                             // branch tunnels: 24 long, 3 apart, back and forth
			int k = mineLeg;
			int across = 3 * ((k + 1) / 2);
			boolean far = k % 4 == 0 || k % 4 == 1;
			goal = mineBase.relative(right, across).relative(mineDir, far ? 24 : 0);
			doing = "branch mining, tunnel " + (k / 2 + 1);
			if (feet.distManhattan(goal) <= 1 || now() - legStarted > 20 * 60) {
				mineLeg++;
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

	// ------------------------------------------------------------------------------ picking up
	private String pickupId;
	private BlockPos pickupAt;
	private long pickupGoneAt;

	private static boolean isKind(String path, String id) {
		return path.equals(id) || id.equals("torch") && path.endsWith("torch") || id.equals("bed") && path.endsWith("_bed")
				|| id.equals("door") && path.endsWith("_door") && !path.startsWith("iron") || id.equals("lantern") && path.endsWith("lantern");
	}

	/** The nearest one it can see (a side open to the air), within 16 blocks. */
	private BlockPos findVisible(String id) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-16, -6, -16), feet.offset(16, 6, 16))) {
			if (!isKind(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath(), id)) continue;
			boolean open = false;
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) open |= level.getBlockState(q.relative(d)).canBeReplaced();
			if (open && (best == null || q.distSqr(feet) < best.distSqr(feet))) best = q.immutable();
		}
		return best;
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

	private Action pickupNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		if (countItem(pickupId) > had || pickupId.equals("torch") && countItem("torch") > had) {
			finish("Got the " + what + "!");
			return null;
		}
		ItemEntity drop = dropToPickUp();
		if (drop != null) {
			doing = "picking up the " + what;
			return c.walkTo(drop.position());
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
			return c.walkTo(drop.position());
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
			return c.walkTo(drop.position());
		}
		if (prey == null || !prey.isAlive() || c.player.distanceTo(prey) > 48) prey = nearestOf(slayType);
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
		int n = countItem("iron_ingot") + countItem("gold_ingot");
		for (String f : new String[] {"cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "cooked_cod",
				"cooked_salmon", "baked_potato"}) n += countItem(f);
		return n;
	}

	String smelt() {
		int raw = count("raw_iron") + count("raw_gold") + rawFood();
		if (raw == 0) return "You have nothing to smelt.";
		if (fuel() == 0) return "You have nothing to burn in a furnace (coal, charcoal or wood).";
		furnace = findFurnace();
		if (furnace == null && countItem("furnace") == 0 && count("cobblestone") < 8) return "You need 8 cobblestone for a furnace.";
		begin(Kind.SMELT);
		until = now() + 20 * (12L * raw + 90);
		want = raw;
		had = cooked();
		checkFurnaceAt = 0;
		return "You will smelt " + raw + " raw ore and food in a furnace.";
	}

	private int fuel() {
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
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(furnace)) > c.player.blockInteractionRange() - 0.5) {
			doing = "going to the furnace";
			return c.walkTo(Vec3.atBottomCenterOf(furnace));
		}
		if (now() < checkFurnaceAt) {
			doing = "waiting by the furnace (" + made + " of " + want + " done)";
			return Action.IDLE;
		}
		checkFurnaceAt = now() + 100;                                        // every five seconds a look
		c.hands.stop();
		c.hands.use(furnace);
		if (!(c.player.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu menu)) return Action.IDLE;
		// the ingots out, the ore in, fuel if the fire needs it
		if (menu.getSlot(2).hasItem()) Compat.click(menu, 2, true, c.player);
		int raw = count("raw_iron") + count("raw_gold") + rawFood();
		if (!menu.getSlot(0).hasItem() && raw > 0) moveInto(menu, 0, st -> isItem(st, "raw_iron") || isItem(st, "raw_gold") || isRawFood(st));
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
		if (empty && count("raw_iron") + count("raw_gold") + rawFood() == 0) {
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
	private ItemEntity dropToPickUp() {
		double feet = c.player.getY();
		ItemEntity best = null;
		for (ItemEntity drop : c.player.level().getEntitiesOfClass(ItemEntity.class, c.player.getBoundingBox().inflate(Perception.NEAR),
				x -> x.isAlive() && Math.abs(x.getY() - feet) <= 2.5 && !ignoredDrops.contains(x.getUUID()))) {
			String k = c.itemKey(drop.getItem());
			boolean wanted = false;
			for (String i : items) wanted |= i.equals(k);
			if (!wanted && !k.endsWith("_sapling") && !k.equals("stick") && !k.equals("apple")) continue;
			if (best == null || drop.distanceTo(c.player) < best.distanceTo(c.player)) best = drop;
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
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz) + 4 * Math.max(0, dy - 1);   // high up counts as further
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
		return glanced;
	}

	/** How far it looks around for what it's gathering. */
	static final int GLANCE = 14;
	/** The last log it went for (the rest of that tree comes first: players don't leave floating trunks). */
	private BlockPos lastLog;

	/** Only water around it (a river bed, the bottom of a lake): it would have to dive for it. */
	private boolean underwater(BlockPos b) {
		ServerLevel level = (ServerLevel) c.player.level();
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
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, center, net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));                  // (what the mouse picks: grass and flowers too)
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(t)) return t;
		BlockPos in = hit.getBlockPos();
		if (level.getBlockState(in).is(net.minecraft.tags.BlockTags.LEAVES) && eye.distanceTo(Vec3.atCenterOf(in)) <= reach) return in;
		return null;
	}

	/** Get to a block and mine it, the way a player would (dig down to it, climb one block to reach it). */
	private Action reach(BlockPos t) {
		Hands hands = c.hands;
		BlockPos feet = c.player.blockPosition();
		BlockPos hit = t.getY() >= feet.getY() ? hitFromHere(t) : null;   // (below its feet: the staircase way, never straight down)
		if (hit != null) {
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
			boolean room = c.senses.last != null && !Blocks.SOLID[c.senses.last.near(0, 2, 0)] && !Blocks.SOLID[c.senses.last.near(0, 3, 0)];
			if (dy >= 2 && dy <= 3 && room && c.player.onGround() && hands.startPillar()) {     // stand on a block to reach it
				c.pillaring = true;
				return Action.JUMP;
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
		return c.walkTo(Vec3.atCenterOf(t));
	}

	/** Dig the block under its feet, unless it knows there is lava or water right below. */
	/**
	 * Down to a block below it: a staircase, never straight down (a hole under your feet can drop you into lava or a
	 * cave). The block itself, once it's within reach and it can see a side of it, it just mines.
	 */
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
			if (lavaNext(level, b)) return giveUp(t);
			return mine(b);
		}
		if (k != c.hands.yaw) return Math.floorMod(k - c.hands.yaw, 4) == 3 ? Action.TURN_LEFT : Action.TURN_RIGHT;
		if (c.hands.pitch != 0) return c.hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP;
		return Action.FORWARD;                                           // one step down
	}

	private Action mine(BlockPos b) {
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

	/** Knows of nothing to go for: look all around (its view is only 90 degrees), then walk somewhere new. */
	private Action lookAround() {
		if (scans < 4) {
			scans++;
			if (c.hands.pitch != 0) return c.hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP;
			return Action.TURN_RIGHT;
		}
		Vec3 here = c.player.position();
		if (wander == null || now() > wanderUntil || here.distanceTo(wander) < 2) {
			double a = random.nextDouble() * Math.PI * 2;
			wander = here.add(Math.cos(a) * 24, 0, Math.sin(a) * 24);
			wanderUntil = now() + 400;
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

	private LivingEntity nearestAnimal() {
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (Animal a : c.player.level().getEntitiesOfClass(Animal.class, c.player.getBoundingBox().inflate(48),
				x -> x.isAlive() && !x.isBaby() && food(x))) {
			double d = c.player.distanceTo(a);
			if (d < bestD && WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, a)) {
				bestD = d;
				best = a;
			}
		}
		return best;
	}

	private Action huntNext() {
		int got = count("food") - had;
		if (got >= want) {
			finish("Got some food!");
			return null;
		}
		if (prey == null || !prey.isAlive() || c.player.distanceTo(prey) > 48) prey = nearestAnimal();
		if (prey != null) {
			doing = String.format(java.util.Locale.ROOT, "hunting a %s %.1f blocks away",
					prey.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), c.player.distanceTo(prey));
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
				x -> x.getItem().has(net.minecraft.core.component.DataComponents.FOOD) && Math.abs(x.getY() - feet) <= 1.5)) {
			doing = "picking up food";
			return c.walkTo(drop.position());                             // pick up what it hunted
		}
		doing = "no animals in sight, looking around";
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
			cancel();
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

	private Action shelterNext() {
		ServerLevel level = (ServerLevel) c.player.level();
		boolean done = true;
		int left = 0;
		for (BlockPos p : walls) if (level.getBlockState(p).canBeReplaced()) left++;
		doing = "building a " + shape + ", " + left + " blocks to go";
		for (BlockPos p : walls) {
			if (!level.getBlockState(p).canBeReplaced()) continue;
			done = false;
			if (c.hands.placeAt(p, c.personality.material)) {
				c.acted = true;
				return Action.PLACE;
			}
		}
		if (done) {
			cancel();
			c.chatter("Done! I'm safe in my little " + shape + ".", true);   // (always said: you'll want to know where it is)
			shelterBuilt = true;
			doing = "hiding in its " + shape + " until morning";
			kind = Kind.HIDE;
			until = now() + 1200;
			return Action.IDLE;
		}
		doing = "building a " + shape + ", " + left + " blocks to go, stuck: " + c.hands.cantPlace;
		if (++waited < 30) return Action.IDLE;                          // someone in the way? wait a little
		finish("I couldn't finish the " + shape + ": " + c.hands.cantPlace + ".");
		return null;
	}
}
