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
	enum Kind { GATHER, HUNT, GIVE, SHELTER, HIDE, EAT, REDSTONE }

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
	private int cycles, fails;
	private int[] buildSide;                                            // where it stands to reach far parts
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
		kind = k;
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
		switch (intent) {
			case "wood" -> set(new int[] {Blocks.LOG}, new String[] {"log"}, "wood", "trees");
			case "stone" -> set(new int[] {Blocks.STONE}, new String[] {"cobblestone"}, "stone", "stone");
			case "coal" -> set(new int[] {Blocks.COAL}, new String[] {"coal"}, "coal", "coal");
			case "iron" -> set(new int[] {Blocks.IRON}, new String[] {"raw_iron"}, "iron", "iron ore");
			default -> set(new int[] {Blocks.DIAMOND, Blocks.GOLD, Blocks.IRON, Blocks.COAL},
					new String[] {"diamond", "raw_gold", "raw_iron", "coal"}, "ore", "ore");
		}
		begin(Kind.GATHER);
		want = Math.max(1, amount);
		had = count(items);
		int[] known = c.senses.nearestKnown(cats, 0.25, skip, 4);
		if (known == null) return "You don't know where to find any " + lookFor + ", so you will look around for some.";
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

	String give(ServerPlayer to, String item, int amount) {
		int have = item.equals("all") ? giveable().size() : count(item);
		if (have == 0) return item.equals("all") ? "You have nothing to give." : "You have no " + named(item, 2) + " to give.";
		begin(Kind.GIVE);
		forWhom = to.getUUID();
		giveItem = item;
		want = amount;
		String name = to.getName().getString();
		int n = amount > 0 ? Math.min(amount, have) : have;
		return item.equals("all") ? "You will give " + name + " what you carry." : "You will give " + name + " " + n + " " + named(item, n) + ".";
	}

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
		BlockPos feet = c.player.blockPosition();
		ServerLevel level = (ServerLevel) c.player.level();
		if (!c.player.onGround()) return "You can't build a shelter because you are not standing on the ground.";
		String build = c.personality.build;
		List<BlockPos> plan = shelterPlan(feet, build);
		int blocks = count("dirt", "cobblestone");
		if (missing(level, plan) > blocks && !build.equals("hut")) {       // not enough for its style: a plain hut will do
			build = "hut";
			plan = shelterPlan(feet, build);
		}
		int missing = missing(level, plan);
		for (BlockPos p : plan) {                                      // walls need ground under them
			BlockPos under = p.below();
			if (p.getY() == feet.getY() && level.getBlockState(p).canBeReplaced()
					&& !level.getBlockState(under).isCollisionShapeFullBlock(level, under)) {
				return "You can't build a shelter here because the ground isn't flat.";
			}
		}
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
		int width = spec.get("width").getAsInt();                    // the ground where it goes must be flat and clear
		for (int cx = 0; cx < width; cx++) {
			for (int cz = -depth / 2; cz < depth - depth / 2; cz++) {
				BlockPos pos = feet.offset(fwd[0] * (2 + cx) + right[0] * cz, 0, fwd[1] * (2 + cx) + right[1] * cz);
				if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below())) {
					return "You can't build the " + name + " here because the ground in front of you isn't flat and clear.";
				}
			}
		}
		java.util.Map<String, Integer> need = new java.util.TreeMap<>();
		for (Part p : plan) need.merge(p.kind().equals("block") ? "block" : ITEM.get(p.kind()), 1, Integer::sum);
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
			case HIDE -> {                                               // stays in its shelter until morning (or a minute)
				if (!c.player.level().isDarkOutside() && now() > until) {
					cancel();
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
		c.chatter(say, true);
	}

	private double distance(int[] p) {
		Vec3 feet = c.player.position();
		return Math.sqrt(Math.pow(p[0] + 0.5 - feet.x, 2) + Math.pow(p[1] - feet.y, 2) + Math.pow(p[2] + 0.5 - feet.z, 2));
	}

	private Action gatherNext() {
		int got = count(items) - had;
		if (got >= want) {
			finish("Got " + got + " " + what + "!");
			return null;
		}
		int[] known = c.senses.nearestKnown(cats, 0.25, skip, 4);
		doing = known == null ? "looking for " + lookFor : String.format(java.util.Locale.ROOT, "getting %s, %d of %d so far", what, got, want);
		if (known == null) {
			if (!saidLooking) {
				c.chatter("I haven't seen any " + lookFor + " yet, I'll look around.", true);
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
		double d = distance(known);
		if (d < closest - 0.5) {
			closest = d;
			lastCloser = now();
		} else if (now() - lastCloser > 400) {                       // not getting any closer: try another one
			skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
			target = null;
			return null;
		}
		return reach(t);
	}

	/** Get to a block and mine it, the way a player would (dig down to it, climb one block to reach it). */
	private Action reach(BlockPos t) {
		Hands hands = c.hands;
		BlockPos feet = c.player.blockPosition();
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
			if (dy < 0) return digDown();
			if (dy > 1) {                                                // out of reach up there: another one
				skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
				return null;
			}
		}
		if (dx == 0 && dz == 0 && dy < 0) return digDown();
		if (dx == 0 && dz == 0 && dy > 1) {                              // straight above: step aside
			skip.add(Perception.Beliefs.key(t.getX(), t.getY(), t.getZ()));
			return null;
		}
		if (Math.abs(dx) + Math.abs(dz) <= 1 && dy < 0) return digDown();
		return c.walkTo(Vec3.atCenterOf(t));
	}

	/** Dig the block under its feet, unless it knows there is lava or water right below. */
	private Action digDown() {
		Perception.Sight s = c.senses.last;
		for (int k = 1; k <= 3; k++) {
			int below = s.near(0, -k, 0);
			if (below == Blocks.LAVA || below == Blocks.WATER) {
				if (target != null) skip.add(Perception.Beliefs.key(target.getX(), target.getY(), target.getZ()));
				target = null;
				return null;
			}
		}
		if (c.hands.pitch != -1) return Action.LOOK_DOWN;
		return Action.MINE;
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

	private LivingEntity nearestAnimal() {
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (Animal a : c.player.level().getEntitiesOfClass(Animal.class, c.player.getBoundingBox().inflate(48),
				x -> x.isAlive() && !x.isBaby())) {
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
		if (!below && c.player.distanceTo(to) > 2) {
			doing = String.format(java.util.Locale.ROOT, "bringing it to %s, %.0f blocks away", to.getName().getString(), c.player.distanceTo(to));
			return c.walkTo(to.position());
		}
		c.hands.face(to.getEyePosition());
		var inv = c.player.getInventory();
		int left = want > 0 ? want : Integer.MAX_VALUE, given = 0;
		for (int i = 0; i < 36 && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || s.isDamageableItem()) continue;
			if (!giveItem.equals("all") && !c.itemKey(s).equals(giveItem)) continue;
			int n = Math.min(left, s.getCount());
			c.hands.toss(inv.removeItem(i, n));
			left -= n;
			given += n;
		}
		finish(given > 0 ? "Here you go!" : "I don't have that anymore.");
		c.acted = true;
		return Action.IDLE;
	}

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
			finish("Done! I'm safe in my little " + shape + ".");
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
