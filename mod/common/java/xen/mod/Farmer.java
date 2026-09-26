package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Crop farming the way a player starts one, not a building from a plan: seeds from the grass, a hoe, the ground next to
 * water tilled into farmland (a Xen that knows farmland needs water; one that doesn't tills by its house, and finds out
 * when it dries up), the seeds planted. Then it looks after it: when the wheat (or carrots, potatoes, beetroot) is ripe
 * it harvests it, plants again, and bakes bread from the wheat. The farm grows with the seeds it has.
 */
final class Farmer {
	private final Companion c;
	boolean on;
	/** The farm: its middle (water, when it knows it needs it) and the farmland it made. */
	BlockPos middle;
	final Set<BlockPos> plot = new LinkedHashSet<>();
	private long since, nextTend, nextLook, lastLine;
	private int hoeTries;

	private int planks() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().endsWith("_planks")) n += inv.getItem(i).getCount();
		return n;
	}

	private boolean tableNear() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-6, -2, -6), feet.offset(6, 2, 6))) if (id(level.getBlockState(q)).equals("crafting_table")) return true;
		return false;
	}
	private boolean tending;
	private final java.util.Random random = new java.util.Random();

	Farmer(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private static String id(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
	}

	private static boolean isSeed(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return n.equals("wheat_seeds") || n.equals("carrot") || n.equals("potato") || n.equals("beetroot_seeds");
	}

	private static boolean isHoe(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_hoe");
	}

	private int seeds() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isSeed(inv.getItem(i))) n += inv.getItem(i).getCount();
		return n;
	}

	private boolean hasHoe() {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isHoe(inv.getItem(i))) return true;
		return false;
	}

	private void line(String s) {
		if (now() - lastLine < 400) return;
		lastLine = now();
		c.chatter(s, false);
	}

	/** Start a crop farm (by its home, or here): the plan in words. */
	String start() {
		on = true;
		since = now();
		if (middle == null) plot.clear();
		c.journal("does", "starts a crop farm");
		return seeds() > 0 ? "You will make a crop farm: till the ground by water with a hoe and plant your " + seeds() + " seeds."
				: "You will make a crop farm: seeds from the grass first, a hoe, then farmland by the water.";
	}

	void cancel() {
		on = false;
		tending = false;
	}

	/** The next step of making the farm (null: something else decides this moment, like crafting the hoe). */
	Action next() {
		if (!on) return null;
		if (now() - since > 20 * 60 * 8) {
			line("That's enough farming for now.");
			on = false;
			return null;
		}
		if (c.crafter.hasOrder() || c.chores.busy()) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		if (!hasHoe()) {                                                      // a hoe: stone if it can, else wood
			var items = c.items();
			int wood = items.getOrDefault("log", 0) * 4 + planks(), sticks = items.getOrDefault("stick", 0);
			boolean table = items.getOrDefault("crafting_table", 0) > 0 || tableNear();
			int needs = (table ? 0 : 4) + (items.getOrDefault("cobblestone", 0) >= 2 ? 0 : 2) + (sticks >= 2 ? 0 : 2);
			if (hoeTries >= 2 || wood < needs) {                                   // not enough wood (or it failed): more wood first
				hoeTries = 0;
				line("I need more wood for a hoe.");
				c.chores.gather("wood", 3);
				c.chores.own = true;
				return null;
			}
			hoeTries++;
			c.crafter.orderRecipe(items.getOrDefault("cobblestone", 0) >= 2 ? "stone_hoe" : "wooden_hoe", 1);
			c.goals.instant = "making a hoe";
			return null;
		}
		hoeTries = 0;
		if (seeds() == 0 && unplanted(level) > 0 || seeds() == 0 && plot.isEmpty()) {   // seeds from the grass
			String grass = visible(level, "short_grass", 14) != null ? "short_grass" : "tall_grass";
			c.chores.mineFor(grass, "wheat_seeds", 6, "seeds");
			c.chores.own = true;
			line("First some seeds. Grass drops them sometimes.");
			return null;
		}
		if (middle == null && !pickSite(level)) {
			line("There's nowhere good for a farm around here.");
			on = false;
			return null;
		}
		if (middle == null) return null;
		// till: the ground around the middle (by the water), as many as it has seeds for
		BlockPos till = null, plant = null;
		for (BlockPos q : cells(level)) {
			BlockState s = level.getBlockState(q);
			if (id(s).equals("farmland")) {
				plot.add(q);
				if (level.getBlockState(q.above()).isAir() && plant == null) plant = q;
			} else if (till == null && plot.size() < Math.min(24, seeds() + plantedCount(level))) {
				till = q;
			}
		}
		if (plant != null && seeds() > 0) return act(plant, "planting seeds", Farmer::isSeed);
		if (till != null) return act(till, "tilling the ground with a hoe", Farmer::isHoe);
		on = false;
		c.goals.farm = middle;
		c.places.remember("farm", middle);
		c.say(c.pick3("My farm is planted! " + plantedCount(level) + " crops.", "Done! Now we wait for the wheat to grow.",
				"Farm's ready. I'll come back when it's ripe."));
		return null;
	}

	/** Walk in reach of a block and right-click it with this in hand (a hoe on the ground, seeds on the farmland). */
	private Action act(BlockPos at, String doing, java.util.function.Predicate<ItemStack> with) {
		c.goals.instant = doing;
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(at)) > c.player.blockInteractionRange() - 0.7) {
			BlockPos stand = standBy(at);
			return c.walkTo(stand != null ? Vec3.atBottomCenterOf(stand) : Vec3.atBottomCenterOf(at.above()));
		}
		c.hands.useWith(at, with);
		c.acted = true;
		return Action.PLACE;
	}

	/** Where to stand to reach it without walking on the crops: a spot beside the plot. */
	private BlockPos standBy(BlockPos at) {
		ServerLevel level = (ServerLevel) c.player.level();
		for (int r = 1; r <= 3; r++) {
			for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos q = at.relative(d, r).above();
				String below = id(level.getBlockState(q.below()));
				if (below.equals("farmland") || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)) continue;
				if (level.getBlockState(q).isAir() && level.getBlockState(q.above()).isAir()) return q;
			}
		}
		return null;
	}

	/** The ground it farms: around the water (four blocks out, the level of the water), or by its house. */
	private List<BlockPos> cells(ServerLevel level) {
		List<BlockPos> out = new ArrayList<>();
		for (int r = 1; r <= 4; r++) {                                           // closest first
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					BlockPos q = middle.offset(dx, 0, dz);
					String n = id(level.getBlockState(q));
					if (!(n.equals("grass_block") || n.equals("dirt") || n.equals("farmland") || n.equals("coarse_dirt"))) continue;
					if (!level.getBlockState(q.above()).isAir() && !(level.getBlockState(q.above()).getBlock() instanceof CropBlock)) continue;
					out.add(q);
				}
			}
		}
		return out;
	}

	private int plantedCount(ServerLevel level) {
		int n = 0;
		for (BlockPos q : plot) if (level.getBlockState(q.above()).getBlock() instanceof CropBlock) n++;
		return n;
	}

	private int unplanted(ServerLevel level) {
		int n = 0;
		for (BlockPos q : plot) if (id(level.getBlockState(q)).equals("farmland") && level.getBlockState(q.above()).isAir()) n++;
		return n;
	}

	/**
	 * Where the farm goes. Knowing that farmland needs water: by a pool or a river bank near its home (still water with
	 * flat ground round it at the same height). Not knowing: right by its house.
	 */
	private boolean pickSite(ServerLevel level) {
		BlockPos home = c.goals.home != null ? c.goals.home : c.player.blockPosition();
		if (!c.knowledge.knows("crops_water")) {
			BlockPos best = null;
			for (BlockPos q : BlockPos.betweenClosed(home.offset(-10, -3, -10), home.offset(10, 3, 10))) {
				String n = id(level.getBlockState(q));
				if (!n.equals("grass_block") && !n.equals("dirt") || !level.getBlockState(q.above()).isAir()) continue;
				if (q.distSqr(home) < 5 * 5) continue;                             // not right in front of the door
				if (best == null || q.distSqr(home) < best.distSqr(home)) best = q.immutable();
			}
			middle = best;
			return best != null;
		}
		BlockPos best = null;
		int bestScore = 0;
		for (BlockPos q : BlockPos.betweenClosed(home.offset(-24, -4, -24), home.offset(24, 4, 24))) {
			var f = level.getFluidState(q);
			if (!f.isSource() || !f.is(net.minecraft.tags.FluidTags.WATER) || !level.getBlockState(q.above()).isAir()) continue;
			int ground = 0;
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					BlockPos g = q.offset(dx, 0, dz);
					String n = id(level.getBlockState(g));
					if ((n.equals("grass_block") || n.equals("dirt") || n.equals("farmland")) && level.getBlockState(g.above()).isAir()) ground++;
				}
			}
			int score = ground * 10 - (int) Math.sqrt(q.distSqr(home));
			if (ground >= 6 && score > bestScore) {
				bestScore = score;
				best = q.immutable();
			}
		}
		middle = best;
		if (best == null) line("I know crops need water, but there's none near my home.");
		return best != null;
	}

	private BlockPos visible(ServerLevel level, String what, int r) {
		BlockPos feet = c.player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -3, -r), feet.offset(r, 3, r))) if (id(level.getBlockState(q)).equals(what)) return q.immutable();
		return null;
	}

	// ------------------------------------------------------------------------------ looking after it
	/**
	 * Its farm, now and then: ripe crops harvested and planted again, wheat baked into bread. And what it finds out:
	 * its farmland dried back into dirt (it had no water): farmland needs water. Null when there's nothing to do there.
	 */
	Action tend() {
		if (middle == null || c.player.isCreative() || on) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		if (!Places.dim(level).equals("overworld") || c.player.blockPosition().distSqr(middle) > 64 * 64) return null;
		if (!tending) {
			if (now() < nextTend) return null;
			nextTend = now() + 20 * 60 * 2;
			int dried = 0, ripe = 0;
			for (BlockPos q : plot) {
				BlockState s = level.getBlockState(q), up = level.getBlockState(q.above());
				if (!id(s).equals("farmland") && !(up.getBlock() instanceof CropBlock)) dried++;
				if (up.getBlock() instanceof CropBlock crop && crop.isMaxAge(up)) ripe++;
			}
			if (dried >= Math.max(2, plot.size() / 3) && c.knowledge.learn("crops_water", Knowledge.How.TRIED)) {
				middle = null;                                                    // start again, by the water this time
				plot.clear();
				start();
				return null;
			}
			if (ripe == 0 && unplanted(level) == 0 && (dried == 0 || seeds() == 0 || !hasHoe())) {
				if (c.items().getOrDefault("wheat", 0) >= 3 && !c.crafter.hasOrder()) c.crafter.orderRecipe("bread", c.items().getOrDefault("wheat", 0) / 3);
				return null;
			}
			tending = true;
			line(ripe > 0 ? "The wheat is ripe! Harvest time." : "Some of my farmland is empty. Planting again.");
		}
		for (BlockPos q : plot) {
			BlockState up = level.getBlockState(q.above());
			if (up.getBlock() instanceof CropBlock crop && crop.isMaxAge(up)) {
				c.goals.instant = "harvesting the farm";
				if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(q.above())) > c.player.blockInteractionRange() - 0.7) {
					BlockPos stand = standBy(q);
					return c.walkTo(stand != null ? Vec3.atBottomCenterOf(stand) : Vec3.atBottomCenterOf(q.above()));
				}
				if (c.hands.mine(q.above())) {
					c.acted = true;
					return Action.MINE;
				}
			}
		}
		for (BlockPos q : plot) {                                                 // the seeds back in
			if (id(level.getBlockState(q)).equals("farmland") && level.getBlockState(q.above()).isAir() && seeds() > 0) {
				return act(q, "planting again", Farmer::isSeed);
			}
		}
		for (BlockPos q : plot) {                                                 // trampled back to dirt: till it again
			String n = id(level.getBlockState(q));
			if ((n.equals("dirt") || n.equals("grass_block")) && level.getBlockState(q.above()).isAir() && hasHoe() && seeds() > 0) {
				return act(q, "tilling the trampled ground again", Farmer::isHoe);
			}
		}
		for (var drop : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(middle).inflate(6, 2, 6),
				x -> x.isAlive() && (isSeed(x.getItem()) || BuiltInRegistries.ITEM.getKey(x.getItem().getItem()).getPath().equals("wheat")))) {
			c.goals.instant = "picking up the harvest";
			return c.walkTo(drop.position());
		}
		tending = false;
		int wheat = c.items().getOrDefault("wheat", 0);
		if (wheat >= 3) c.crafter.orderRecipe("bread", wheat / 3);
		c.journal("does", "tended its farm");
		return null;
	}

	/** Now and then: farmland it sees with water by it (a village's fields, a player's): it gets how farms work. */
	void look() {
		if (c.knowledge.knows("crops_water") || now() < nextLook) return;
		nextLook = now() + 200;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 3, 8))) {
			if (!id(level.getBlockState(q)).equals("farmland") || plot.contains(q)) continue;
			for (BlockPos w : BlockPos.betweenClosed(q.offset(-4, 0, -4), q.offset(4, 1, 4))) {
				if (level.getFluidState(w).is(net.minecraft.tags.FluidTags.WATER)) {
					c.knowledge.learn("crops_water", Knowledge.How.SEEN);
					return;
				}
			}
			return;
		}
	}

	String describe() {
		if (on) return "You are making a crop farm.";
		return middle == null ? "" : "You have a crop farm at " + middle.getX() + ", " + middle.getZ() + ".";
	}

	com.google.gson.JsonObject toJson() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		if (middle == null) return o;
		o.addProperty("x", middle.getX());
		o.addProperty("y", middle.getY());
		o.addProperty("z", middle.getZ());
		com.google.gson.JsonArray cells = new com.google.gson.JsonArray();
		for (BlockPos q : plot) {
			cells.add(q.getX() - middle.getX());
			cells.add(q.getY() - middle.getY());
			cells.add(q.getZ() - middle.getZ());
		}
		o.add("plot", cells);
		return o;
	}

	void load(com.google.gson.JsonObject o) {
		if (o == null || !o.has("x")) return;
		middle = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
		var cells = o.getAsJsonArray("plot");
		for (int i = 0; cells != null && i + 2 < cells.size(); i += 3) plot.add(middle.offset(cells.get(i).getAsInt(), cells.get(i + 1).getAsInt(), cells.get(i + 2).getAsInt()));
	}
}
