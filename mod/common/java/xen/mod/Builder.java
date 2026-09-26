package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Builds a plan from {@link Architect}, block by block, with a player's hands: it walks (or, in creative, flies) to
 * where it can reach, clicks the face of a block next to where the new one goes, and digs out what's in the way. In
 * creative it takes its blocks from the creative inventory, like any creative player. In survival it builds with
 * what it carries: it makes the planks, stairs, slabs, doors, fences and trapdoors from its logs itself, gets more
 * wood and stone when it runs out, climbs on a pillar of dirt to reach the roof (and takes it down again), and leaves
 * out decoration it has nothing to make from.
 * <p>After a block goes down, if it's the right block but turned the wrong way (a stair, a log, a trapdoor), it's set
 * the way the plan wants, as a player would get it by clicking at the right angle.
 */
final class Builder {
	private final Companion c;
	private final Random random = new Random();
	Architect.Plan plan;
	private final List<Architect.Step> left = new ArrayList<>();
	private final Set<BlockPos> skipped = new HashSet<>();
	private final Map<BlockPos, Integer> tries = new HashMap<>();
	private final List<BlockPos> scaffold = new ArrayList<>();
	private Architect.Step current;
	/** All of the plan (and a little around it): where it finds its way through the air. */
	private net.minecraft.world.phys.AABB bounds;
	/** Blocks it can't get to yet (say, deeper down the stairs it is still digging), and until when it leaves them be. */
	private final Map<BlockPos, Long> notNow = new HashMap<>();
	private long started, lastProgress, nextStuckCheck;
	private int placed, dug;
	private boolean creative, removingScaffold;
	/** A survival Xen that ran out of something: what it went to get. */
	private String gathering;
	/** What it's doing, in words (for /xen status). */
	String doing = "";

	Builder(Companion c) {
		this.c = c;
	}

	boolean busy() {
		return plan != null;
	}

	void cancel() {
		plan = null;
		left.clear();
		current = null;
		gathering = null;
		stopFlying();
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	// --------------------------------------------------------------------------------- start
	/**
	 * Start building "house" (a cottage) or "base" (underground) next to it. The plan in words (to itself), or why
	 * it can't.
	 */
	String start(String what) {
		ServerLevel level = (ServerLevel) c.player.level();
		creative = c.player.isCreative();
		Direction front = c.player.getDirection().getOpposite();             // the door faces where it stood looking from
		BlockPos feet = c.player.blockPosition();
		boolean base = what.contains("base") || what.contains("underground") || what.contains("bunker");
		Architect.Palette p = palette(level, feet);
		Architect.Plan made;
		if (base) {
			BlockPos top = Architect.ground(level, feet);
			if (top == null) return "You can't dig a base here: there's no solid ground.";
			made = Architect.underground(top.above().relative(front.getOpposite(), 1), front, 7, 9, 7, p, creative);
		} else {
			int w = creative ? 9 : 7, d = creative ? 7 : 7;
			BlockPos corner = Architect.site(level, feet.relative(front.getOpposite(), 3), front, w, d);
			if (corner == null) return "You can't build a house here: it's all water or cliffs around. Somewhere with dry ground would work.";
			made = Architect.cottage(corner, front, w, d, p, creative, random);
		}
		cancel();
		plan = made;
		left.addAll(made.steps());
		var box = new net.minecraft.world.phys.AABB(made.middle());
		for (Architect.Step st : made.steps()) box = box.minmax(new net.minecraft.world.phys.AABB(st.pos()));
		bounds = box.inflate(6);
		notNow.clear();
		skipped.clear();
		tries.clear();
		scaffold.clear();
		removingScaffold = false;
		placed = dug = 0;
		started = lastProgress = now();
		c.chores.cancel();
		c.mode = Companion.Mode.STAY;                                         // it's working here now
		c.anchor = made.middle();
		int blocks = (int) made.steps().stream().filter(s -> !s.dig()).count();
		String of = creative ? "" : " out of " + p.name() + " wood" + (p.base().equals("cobblestone") ? " and cobblestone" : "");
		XenMod.LOG.info("{} starts building a {} at {} ({} blocks, {} palette)", c.name, made.name(), made.middle(), blocks, p.name());
		return "You will build " + (made.name().startsWith("u") ? "an " : "a ") + made.name() + " here" + of + ": about " + blocks
				+ " blocks, one at a time.";
	}

	/** The look: in creative one of the designed palettes (fitting where it is); in survival its own wood. */
	private Architect.Palette palette(ServerLevel level, BlockPos feet) {
		if (creative) {
			String biome = level.getBiome(feet).unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
			if (biome.contains("desert") || biome.contains("badlands")) return Architect.PALETTES.get("desert");
			if (biome.contains("taiga") || biome.contains("snow") || biome.contains("grove")) return Architect.PALETTES.get("spruce");
			if (biome.contains("birch")) return Architect.PALETTES.get("birch");
			String[] any = {"oak", "medieval", "stone", "spruce"};
			return Architect.PALETTES.get(any[random.nextInt(any.length)]);
		}
		var items = c.items();
		return Architect.ofWood(woodType(), items.getOrDefault("cobblestone", 0) >= 24, count(n -> n.equals("glass_pane")) >= 6);
	}

	private String woodType() {
		Map<String, Integer> woods = new HashMap<>();
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), w = null;
			if (n.endsWith("_planks")) w = n.substring(0, n.length() - 7);
			else if (n.endsWith("_log") && !n.startsWith("stripped_")) w = n.substring(0, n.length() - 4);
			if (w != null) woods.merge(w, s.getCount() * (n.endsWith("_log") ? 4 : 1), Integer::sum);
		}
		return woods.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("oak");
	}

	private int count(java.util.function.Predicate<String> which) {
		var inv = c.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && which.test(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) n += s.getCount();
		}
		return n;
	}

	// ----------------------------------------------------------------------------------- work
	/** Is this step done already (the right block there, or dug out)? */
	private boolean done(ServerLevel level, Architect.Step s) {
		BlockState here = level.getBlockState(s.pos());
		if (s.dig()) return here.isAir() || !here.getFluidState().isEmpty() && here.canBeReplaced();
		if (s.state().is(net.minecraft.world.level.block.Blocks.DIRT) && s.phase() == Architect.SUPPORT) {
			return !here.canBeReplaced();                                     // filling a hole: any ground will do
		}
		return here.getBlock() == s.state().getBlock();
	}

	/** One decision's worth of building. Null when the building is done (or it gave up). */
	Action next() {
		if (plan == null || c.player == null) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		creative = c.player.isCreative();
		if (gathering != null) {                                            // out getting wood or stone for it
			if (c.chores.busy()) return c.chores.next();
			gathering = null;
			c.mode = Companion.Mode.STAY;
			c.anchor = plan.middle();
			c.chatter("Back to building!", false);
		}
		if (c.crafter.hasOrder()) return null;                               // making what it needs first
		if (removingScaffold) return takeDownScaffold(level);
		// what's left, in order: the first phase that still has work
		left.removeIf(s -> done(level, s) || skipped.contains(s.pos()));
		if (left.isEmpty()) {
			if (!scaffold.isEmpty() && !creative) {
				removingScaffold = true;
				return takeDownScaffold(level);
			}
			return finish(level);
		}
		int phase = left.get(0).phase();
		doing = String.format(java.util.Locale.ROOT, "building a %s: %s, %d blocks to go", plan.name(), Architect.PHASES[phase], left.size());
		c.goals.instant = doing;
		long patience = current != null && current.decor() ? 200 : 600;       // stuck on one: decoration after 10 s, the rest after 30 s
		if (now() - lastProgress > patience && now() >= nextStuckCheck) {
			nextStuckCheck = now() + 100;
			if (current != null) {
				skip(current.pos(), "stuck");
				XenMod.LOG.info("{} skips {} at {} (couldn't get it done: {}; it's at {})", c.name, current.state(), current.pos(),
						c.hands.cantPlace, c.player.blockPosition());
			}
			lastProgress = now();
		}
		// the steps of this phase it can do from here, else the nearest one it can get to
		Vec3 eye = c.player.getEyePosition();
		double reach = c.player.blockInteractionRange() - 0.4;
		Architect.Step best = null, nearest = null;
		double bestD = Double.MAX_VALUE, nearD = Double.MAX_VALUE;
		int checked = 0;
		int later = 0;
		for (Architect.Step s : left) {
			if (s.phase() != phase) break;
			if (++checked > 400) break;
			if (notNow.getOrDefault(s.pos(), 0L) > now()) {
				later++;
				continue;
			}
			if (!s.dig() && against(level, s) == null) continue;              // nothing to put it against yet
			if (!s.dig() && !creative && !haveOrCanMake(s)) {                  // survival: nothing to make it from
				if (s.decor()) {
					skip(s.pos(), "no material");
					continue;
				}
				return getMaterials(s);
			}
			double d = eye.distanceTo(Vec3.atCenterOf(s.pos()));
			if (d <= reach && !inTheWay(s)) {
				if (d < bestD) {
					bestD = d;
					best = s;
				}
			} else if (d < nearD) {
				nearD = d;
				nearest = s;
			}
		}
		if (best != null) {
			stopFlying();
			current = best;
			return best.dig() ? dig(level, best) : place(level, best);
		}
		if (nearest == null) {
			if (later > 0 && now() - lastProgress < 600) return Action.IDLE;  // what's left it can't get to yet: in a moment
			int n = 0;
			for (Architect.Step s : left) {
				if (s.phase() != phase) continue;
				skipped.add(s.pos());                                         // nothing it can do in this phase: leave it
				if (n++ < 3) XenMod.LOG.info("{} leaves out {} at {} ({})", c.name, s.dig() ? "digging" : s.state().getBlock(), s.pos(),
						!s.dig() && against(level, s) == null ? "nothing to put it against" : "can't get to it");
			}
			notNow.clear();
			return Action.IDLE;
		}
		current = nearest;
		return goTo(level, nearest);
	}

	private Action finish(ServerLevel level) {
		stopFlying();
		String what = plan.name();
		int miss = skipped.size();
		XenMod.LOG.info("{} finished the {} ({} placed, {} dug, {} left out) in {} s", c.name, what, placed, dug, miss, (now() - started) / 20);
		c.say(miss == 0 ? "Done! Come see the " + what + "!" : "Done! The " + what + " is ready (" + miss + (miss == 1 ? " block" : " blocks") + " I couldn't manage).");
		c.goals.home = plan.middle();
		c.antics.celebrate();
		c.mode = Companion.Mode.STAY;
		c.anchor = plan.middle();
		plan = null;
		return Action.IDLE;
	}

	/** Its body is standing (or its head is) where this block goes: it has to step aside first. */
	private boolean inTheWay(Architect.Step s) {
		if (s.dig()) return false;
		BlockPos feet = c.player.blockPosition();
		return s.pos().equals(feet) || s.pos().equals(feet.above()) || c.player.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(s.pos()));
	}

	// ---------------------------------------------------------------------------- hands on
	private Action dig(ServerLevel level, Architect.Step s) {
		if (c.hands.mine(s.pos())) {
			c.acted = true;
			if (level.getBlockState(s.pos()).isAir()) progress(true);
			return Action.MINE;
		}
		if (s.pos().equals(c.hands.cantMine)) skip(s.pos(), "can't mine");          // bedrock, or far too slow
		return null;
	}

	/** The block it clicks to put this one down, and the face; null if there's nothing to put it against yet. */
	private Object[] against(ServerLevel level, Architect.Step s) {
		BlockPos pos = s.pos();
		Block b = s.state().getBlock();
		String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
		java.util.function.Predicate<BlockPos> solid = q -> !level.getBlockState(q).canBeReplaced();
		if (n.equals("wall_torch") || n.endsWith("_wall_torch")) {            // on the wall behind it
			Direction f = facing(s.state());
			BlockPos wall = pos.relative(f.getOpposite());
			return solid.test(wall) ? new Object[] {wall, f} : null;
		}
		if (n.equals("lantern") && s.state().getValue(net.minecraft.world.level.block.LanternBlock.HANGING)) {
			return solid.test(pos.above()) ? new Object[] {pos.above(), Direction.DOWN} : null;
		}
		if (n.endsWith("_trapdoor") && s.state().getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN)) {   // a shutter: on the wall
			Direction f = facing(s.state());
			BlockPos wall = pos.relative(f.getOpposite());
			return solid.test(wall) ? new Object[] {wall, f} : null;
		}
		boolean onTheFloor = n.endsWith("_door") || n.endsWith("_bed") || n.endsWith("_carpet") || n.endsWith("_pressure_plate")
				|| n.equals("torch") || n.equals("lantern") || n.equals("dirt_path") || b instanceof net.minecraft.world.level.block.FlowerBlock
				|| n.endsWith("_fence") && false;
		if (solid.test(pos.below())) return new Object[] {pos.below(), Direction.UP};
		if (onTheFloor) return null;
		for (Direction d : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP}) {
			if (solid.test(pos.relative(d))) return new Object[] {pos.relative(d), d.getOpposite()};
		}
		return null;
	}

	private static Direction facing(BlockState s) {
		for (var p : s.getProperties()) {
			if (p.getName().equals("facing") && s.getValue(p) instanceof Direction d) return d;
		}
		return Direction.NORTH;
	}

	private static int yawIndex(Direction d) {
		return switch (d) {
			case NORTH -> 0;
			case EAST -> 1;
			case SOUTH -> 2;
			default -> 3;
		};
	}

	/** The item that puts this block down (a torch for a wall torch). */
	static Item itemFor(BlockState s) {
		return s.getBlock().asItem();
	}

	private Action place(ServerLevel level, Architect.Step s) {
		BlockState here = level.getBlockState(s.pos());
		if (s.state().getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock pot
				&& pot.getPotted() != net.minecraft.world.level.block.Blocks.AIR) {   // a plant in a pot: the pot, then the plant in it
			if (here.is(net.minecraft.world.level.block.Blocks.FLOWER_POT)) {
				Item plant = pot.getPotted().asItem();
				if (creative) takeFromCreative(plant);
				if (!c.hands.useWith(s.pos(), st -> st.getItem() == plant)) {
					skip(s.pos(), "no plant");
					return null;
				}
				c.acted = true;
				progress(false);
				return Action.PLACE;
			}
			if (here.canBeReplaced()) s = new Architect.Step(s.pos(), net.minecraft.world.level.block.Blocks.FLOWER_POT.defaultBlockState(), s.phase(), s.decor());
		}
		if (!here.canBeReplaced()) return dig(level, new Architect.Step(s.pos(), null, s.phase(), s.decor()));   // something else there: out with it
		Item item = itemFor(s.state());
		if (item == Items.AIR) {
			skip(s.pos(), "no item for " + s.state().getBlock());
			return null;
		}
		if (creative) takeFromCreative(item);
		Object[] a = against(level, s);
		if (a == null) return null;
		BlockPos wall = (BlockPos) a[0];
		Direction side = (Direction) a[1];
		// which way to face: doors, beds and stairs take the way it looks; chests and furnaces face it
		Block b = s.state().getBlock();
		String n = BuiltInRegistries.BLOCK.getKey(b).getPath();
		Direction f = facing(s.state());
		int yaw = -1;
		if (b instanceof DoorBlock || b instanceof BedBlock || n.endsWith("_stairs")) yaw = yawIndex(f);
		else if (n.equals("furnace") || n.equals("chest") || n.equals("barrel") || n.equals("smoker")) yaw = yawIndex(f.getOpposite());
		boolean ok = c.hands.placeItem(s.pos(), st -> st.getItem() == item, wall, side, yaw);
		int t = tries.merge(s.pos(), 1, Integer::sum);
		if (!ok) {
			if (t > 6) {
				skipped.add(s.pos());
				XenMod.LOG.info("{} leaves out {} at {} ({}; it's at {})", c.name, s.state().getBlock(), s.pos(), c.hands.cantPlace, c.player.blockPosition());
			}
			return null;
		}
		c.acted = true;
		BlockState now = level.getBlockState(s.pos());
		if (now.getBlock() == b && now != s.state() && !(b instanceof DoorBlock) && !(b instanceof BedBlock)) {
			level.setBlock(s.pos(), s.state(), 3);                          // turned the way the plan has it
		}
		progress(false);
		return Action.PLACE;
	}

	private void skip(BlockPos pos, String why) {
		if (skipped.add(pos) && Companion.DEBUG) XenMod.LOG.info("[xen debug] {} skips {}: {}", c.name, pos, why);
	}

	private void progress(boolean digging) {
		lastProgress = now();
		if (digging) dug++;
		else placed++;
		if ((placed + dug) % 60 == 0 && placed + dug > 0) {
			c.chatter(left.size() > 0 ? String.format(java.util.Locale.ROOT, "%d blocks down, about %d to go.", placed + dug, left.size())
					: "Almost done!", false);
		}
	}

	/** A creative player takes any block from the creative inventory: into its hand. */
	private void takeFromCreative(Item item) {
		var inv = c.player.getInventory();
		for (int i = 0; i < 9; i++) if (inv.getItem(i).getItem() == item) return;
		inv.setItem(8, new ItemStack(item, 64));
	}

	// ------------------------------------------------------------------------------ materials
	/** Survival: does it have this block's item, or can it make it from what it carries? */
	private boolean haveOrCanMake(Architect.Step s) {
		Item item = itemFor(s.state());
		String n = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (count(x -> x.equals(n)) > 0) return true;
		String wood = woodType();
		int logs = count(x -> x.endsWith("_log") || x.endsWith("_stem")), planks = count(x -> x.endsWith("_planks"));
		boolean woodThing = n.endsWith("_planks") || n.endsWith("_stairs") && n.startsWith(wood) || n.endsWith("_slab") && n.startsWith(wood)
				|| n.endsWith("_door") || n.endsWith("_fence") || n.endsWith("_trapdoor") || n.endsWith("_pressure_plate")
				|| n.equals("crafting_table") || n.equals("chest") || n.equals("barrel");
		if (woodThing && logs + planks > 0) {
			String order = n.startsWith(wood) || !n.contains("_") || n.equals("crafting_table") || n.equals("chest") || n.equals("barrel") ? n
					: wood + n.substring(n.indexOf('_'));
			if (!c.crafter.hasOrder()) c.crafter.orderRecipe(order, 1);
			return false;                                                    // (it's making it: next time)
		}
		if (n.equals("furnace") && count(x -> x.equals("cobblestone")) >= 8) {
			if (!c.crafter.hasOrder()) c.crafter.orderRecipe("furnace", 1);
			return false;
		}
		if (n.equals("torch") && count(x -> x.equals("coal") || x.equals("charcoal")) > 0) {
			if (!c.crafter.hasOrder()) c.crafter.orderRecipe("torch", 4);
			return false;
		}
		return false;
	}

	/** Out of what the walls, floor or roof need: it goes to get wood (or stone) and comes back. */
	private Action getMaterials(Architect.Step s) {
		String n = BuiltInRegistries.ITEM.getKey(itemFor(s.state())).getPath();
		if (c.crafter.hasOrder()) return null;                               // it's making it right now
		boolean stone = n.equals("cobblestone") || n.equals("furnace") || n.startsWith("cobblestone_");
		if (n.equals("dirt")) {
			if (count(x -> x.equals("cobblestone")) > 0) return null;        // (placeAt uses cobblestone for holes as well)
			gathering = "dirt";
			String plan = c.chores.gather("dirt", 16);
			c.chatter("I need some dirt to fill the holes. " + xen.mod.talk.Chat.firstPerson(plan), false);
			return null;
		}
		gathering = stone ? "stone" : "wood";
		String plan = c.chores.gather(gathering, stone ? 16 : 12);
		c.chores.own = true;
		c.chatter((stone ? "I'm out of stone" : "I need more wood") + " for the " + plan().name() + ". " + xen.mod.talk.Chat.firstPerson(plan), true);
		return null;
	}

	private Architect.Plan plan() {
		return plan;
	}

	// ------------------------------------------------------------------------------ getting there
	/** To somewhere it can reach the block from: flying in creative; walking, or on a pillar, in survival. */
	private Action goTo(ServerLevel level, Architect.Step s) {
		List<BlockPos> spots = standSpots(level, s);
		if (creative) {
			Vec3 here = c.player.position(), spot = null;
			for (int i = 0; i < spots.size() && i < 60 && spot == null; i++) {
				Vec3 at = flyPoint(level, spots.get(i));
				if (clearFlight(level, here, at)) spot = at;                  // straight there
			}
			if (spot == null) spot = nextOnRoute(level, s, spots);            // round the walls, in by the door, over the roof
			if (spot == null) {                                              // no way there (yet): something else first
				if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} can't get to {} yet ({} spots, first {}, searched {} from {})", c.name, s.pos(), spots.size(),
						spots.isEmpty() ? "-" : spots.get(0), searched, c.player.blockPosition());
				notNow.put(s.pos(), now() + 100);
				return Action.IDLE;
			}
			excuseMe(spot);
			if (!c.player.getAbilities().flying) {
				c.player.getAbilities().flying = true;
				c.player.onUpdateAbilities();
			}
			if (Companion.DEBUG && now() - loggedAt >= 40) {
				loggedAt = now();
				XenMod.LOG.info("[xen debug] {} flies to {} for {} at {} (route {}; flying {}, moving {}, keys {} {} {})", c.name, spot,
						s.dig() ? "digging" : s.state().getBlock(), s.pos(), route == null ? "-" : route.size(), c.player.getAbilities().flying,
						c.player.getDeltaMovement(), c.player.zza, c.player.isShiftKeyDown(), c.player.getYRot());
			}
			c.hands.flyToward(spot);
			c.acted = true;
			return Action.IDLE;
		}
		if (!spots.isEmpty()) {
			doing = "walking over to build";
			return c.walkTo(Vec3.atBottomCenterOf(spots.get(0)));
		}
		return climb(level, s);                                              // out of reach from the ground: a pillar
	}

	private long excusedAt = -10000;

	/** Someone standing where it has to go (players push each other): it asks them to let it by. */
	private void excuseMe(Vec3 spot) {
		var body = c.player.getBoundingBox().move(spot.subtract(c.player.position())).minmax(c.player.getBoundingBox()).inflate(0.2);
		for (var other : c.player.level().players()) {
			if (other == c.player || other.isSpectator() || !other.getBoundingBox().intersects(body)) continue;
			if (now() - excusedAt > 400) {
				excusedAt = now();
				c.say(c.pick3("Excuse me, " + other.getName().getString() + ", can I get by?", "Could you move a little? I need to build there.",
						"Sorry, you're right where I'm building!"));
			}
			return;
		}
	}

	private void stopFlying() {
		c.hands.flyToward(null);
		route = null;
	}

	/**
	 * Where to stand to reach a block, nearest first: close enough, with room for its body (not where a block of the
	 * plan still has to go), on solid ground (in creative anywhere in the air).
	 */
	private List<BlockPos> standSpots(ServerLevel level, Architect.Step s) {
		BlockPos t = s.pos();
		double reach = c.player.blockInteractionRange() - 0.6;
		Set<BlockPos> planned = new HashSet<>();
		for (Architect.Step o : left) if (!o.dig()) planned.add(o.pos());
		Vec3 here = c.player.position();
		List<BlockPos> spots = new ArrayList<>();
		Map<BlockPos, Double> dist = new HashMap<>();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = -4; dy <= 2; dy++) {
					BlockPos feet = t.offset(dx, dy, dz);
					if (planned.contains(feet) || planned.contains(feet.above()) || feet.equals(t) || feet.above().equals(t)) continue;
					if (s.phase() == Architect.ROOF && plan.inside().inflate(0.6).contains(Vec3.atCenterOf(feet))) continue;   // the roof: from outside
					if (!passable(level, feet) || !passable(level, feet.above())) continue;
					if (!creative && !solid(level, feet.below())) continue;
					if (creative && !floats(level, feet)) continue;
					Vec3 eye = (creative ? flyPoint(level, feet) : Vec3.atBottomCenterOf(feet)).add(0, 1.62, 0);
					if (eye.distanceTo(Vec3.atCenterOf(t)) > reach) continue;
					net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2,
							feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8).inflate(0.3);
					if (body.intersects(new net.minecraft.world.phys.AABB(t))) continue;   // (with room to spare: it never stands quite in the middle)
					spots.add(feet);
					dist.put(feet, here.distanceTo(Vec3.atBottomCenterOf(feet)) + (creative ? 0 : 3 * Math.abs(feet.getY() - c.player.getBlockY())));
				}
			}
		}
		spots.sort(java.util.Comparator.comparingDouble(dist::get));
		return spots;
	}

	/** Creative: the way it's flying, block by block (found once per block it's going to), and for which block. */
	private List<BlockPos> route;
	private BlockPos routeFor;
	private long routeAt, loggedAt;
	private int searched;

	/**
	 * The next point to fly to on the way to one of the spots, when it can't fly there straight: the shortest way through
	 * the air (two blocks high for its body) around the house, as a player finds their way in and out. Null if there's none.
	 */
	private Vec3 nextOnRoute(ServerLevel level, Architect.Step s, List<BlockPos> spots) {
		if (spots.isEmpty()) return null;
		if (route == null || !s.pos().equals(routeFor) || now() - routeAt > 40) {   // (again now and then: it builds as it goes)
			routeFor = s.pos();
			routeAt = now();
			route = findRoute(level, c.player.blockPosition(), new HashSet<>(spots));
			if (route == null) return null;
		}
		Vec3 here = c.player.position();
		// the farthest point along the way it can fly to straight from here (it cuts corners like a player)
		while (!route.isEmpty() && here.distanceTo(flyPoint(level, route.get(0))) < 0.5) route.remove(0);
		if (route.isEmpty()) {
			route = null;
			return null;
		}
		int far = 0;
		for (int i = Math.min(route.size() - 1, 12); i > 0; i--) {
			if (clearFlight(level, here, flyPoint(level, route.get(i)))) {
				far = i;
				break;
			}
		}
		return flyPoint(level, route.get(far));                              // (the next block even if not quite straight: it slides along)
	}

	/**
	 * Where its feet go, flying, to be in that block: on the floor if there's one (shift down till it lands), else in
	 * the middle of the air, which needs three blocks of room (it can't hold still to a hair in the air, and stands
	 * 1.8 high).
	 */
	private static Vec3 flyPoint(ServerLevel level, BlockPos feet) {
		return Vec3.atBottomCenterOf(feet).add(0, solid(level, feet.below()) ? 0 : 0.4, 0);
	}

	/** Room for its body there, flying: two blocks on a floor, three in the air. */
	private static boolean floats(ServerLevel level, BlockPos feet) {
		return passable(level, feet) && passable(level, feet.above()) && (solid(level, feet.below()) || passable(level, feet.above(2)));
	}

	private static boolean solid(ServerLevel level, BlockPos p) {
		return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/** Breadth first through the air around the plan (6 ways from each block), to the nearest of the goals. */
	private List<BlockPos> findRoute(ServerLevel level, BlockPos from, Set<BlockPos> goals) {
		var box = bounds.minmax(new net.minecraft.world.phys.AABB(from).inflate(3));
		Map<BlockPos, BlockPos> came = new HashMap<>();
		java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
		came.put(from, from);
		open.add(from);
		BlockPos found = null;
		while (!open.isEmpty() && came.size() < 30000) {
			BlockPos at = open.poll();
			if (goals.contains(at)) {
				found = at;
				break;
			}
			List<BlockPos> next = new ArrayList<>(10);
			for (Direction d : Direction.values()) {
				BlockPos n = at.relative(d);
				if (floats(level, n)) next.add(n);
				if (d.getAxis().isHorizontal()) {                             // down (or up) a step at once, like down a staircase
					BlockPos down = n.below(), up = n.above();
					if (passable(level, n) && passable(level, n.above()) && floats(level, down)) next.add(down);
					if (passable(level, at.above(2)) && passable(level, up) && passable(level, up.above()) && floats(level, up)) next.add(up);
				}
			}
			for (BlockPos n : next) {
				if (came.containsKey(n) || !box.contains(Vec3.atCenterOf(n))) continue;
				came.put(n, at);
				open.add(n);
			}
		}
		searched = came.size();
		if (found == null) return null;
		List<BlockPos> way = new ArrayList<>();
		for (BlockPos at = found; !at.equals(from); at = came.get(at)) way.add(0, at);
		way.add(0, from);
		return way;
	}

	/** Can its body fly in a straight line from here to there (no wall in the way)? */
	private boolean clearFlight(ServerLevel level, Vec3 from, Vec3 to) {
		var box = c.player.getBoundingBox().deflate(0.05);
		Vec3 d = to.subtract(from);
		int n = (int) Math.ceil(d.length() / 0.4);
		for (int i = 1; i <= n; i++) {
			Vec3 at = from.add(d.scale(i / (double) n));
			if (at.distanceTo(from) < 0.7) continue;                          // (where it is now, say in a doorway, it can move out of)
			if (!level.noCollision(c.player, box.move(at.subtract(c.player.position())))) return false;
		}
		return true;
	}

	private static boolean passable(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
	}

	/**
	 * Survival, and the block is too high to reach from the ground (a roof): it stands next to it and jumps up on a
	 * pillar of dirt or cobblestone, block by block, like players do (and remembers the pillar, to take it down later).
	 */
	private Action climb(ServerLevel level, Architect.Step s) {
		BlockPos feet = c.player.blockPosition();
		double dx = s.pos().getX() + 0.5 - c.player.getX(), dz = s.pos().getZ() + 0.5 - c.player.getZ();
		if (Math.hypot(dx, dz) > 3) {                                      // under it first (as close as it can get on foot)
			doing = "going over to climb up";
			BlockPos under = s.pos();
			for (int i = 0; i < 12 && passable(level, under.below()); i++) under = under.below();
			return c.walkTo(Vec3.atBottomCenterOf(under.relative(plan.front(), 2)));
		}
		if (c.items().getOrDefault("dirt", 0) + c.items().getOrDefault("cobblestone", 0) == 0) {
			skip(s.pos(), "nothing to pillar with");                          // nothing to stand on: that one stays out
			return null;
		}
		if (c.player.onGround() && c.hands.startPillar()) {
			scaffold.add(feet.immutable());
			doing = "climbing up to build the roof";
			c.pillaring = true;
			return Action.JUMP;
		}
		return Action.IDLE;
	}

	/** The pillars it climbed come down again: it stands on top and digs out the block under its feet. */
	private Action takeDownScaffold(ServerLevel level) {
		scaffold.removeIf(p -> level.getBlockState(p).isAir());
		if (scaffold.isEmpty()) {
			removingScaffold = false;
			return finish(level);
		}
		BlockPos top = scaffold.stream().max(java.util.Comparator.comparingInt(BlockPos::getY)).get();
		doing = "taking down the pillar it climbed";
		BlockPos feet = c.player.blockPosition();
		if (feet.equals(top.above())) {
			if (c.hands.mine(top)) {
				c.acted = true;
				return Action.MINE;
			}
			scaffold.remove(top);
			return null;
		}
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(top)) <= c.player.blockInteractionRange() - 0.5) {
			if (c.hands.mine(top)) {
				c.acted = true;
				return Action.MINE;
			}
			scaffold.remove(top);
			return null;
		}
		return c.walkTo(Vec3.atBottomCenterOf(top.above()));
	}

	/** For its notes. */
	String describe() {
		return plan == null ? "" : "You are building a " + plan.name() + " (" + left.size() + " blocks to go).";
	}
}
