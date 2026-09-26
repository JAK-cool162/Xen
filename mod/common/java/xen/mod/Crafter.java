package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Perception;

/**
 * Crafting like a player, with the recipe book: it clicks a recipe (which puts the ingredients in the grid, the way
 * the recipe book does for everyone), shift-clicks the result, and puts back what's left. Small things (planks,
 * sticks, a crafting table) in its own 2x2 grid; tools at a crafting table it places next to itself (or one that's
 * already there), opened with a right-click. One step per decision, so it takes a few seconds, as it would for anyone.
 * <p>What it makes is what a new player makes first: a wooden pickaxe from three logs, then a stone pickaxe, a sword
 * and an axe once it has cobblestone. Without a pickaxe, stone and ore drop nothing, so it gets wood first.
 */
final class Crafter {
	private final Companion c;
	/** The crafting table it last used (it leaves it there, like most players do). */
	private BlockPos table;
	private long nextTry;
	/** What it's making (for its status and notes). */
	String making;

	Crafter(Companion c) {
		this.c = c;
	}

	private static String path(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	private int count(java.util.function.Predicate<String> which) {
		var inv = c.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && which.test(path(inv.getItem(i)))) n += inv.getItem(i).getCount();
		return n;
	}

	static boolean isLog(String n) {
		return n.endsWith("_log") || n.endsWith("_stem") || n.endsWith("_wood") || n.endsWith("_hyphae") || n.equals("bamboo_block");
	}

	private static boolean isStoneMaterial(String n) {
		return n.equals("cobblestone") || n.equals("cobbled_deepslate") || n.equals("blackstone");
	}

	/** Its best pickaxe: 0 none, 1 wood or gold, 2 stone, 3 iron, 4 diamond or netherite. */
	int pickTier() {
		int best = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			String n = inv.getItem(i).isEmpty() ? "" : path(inv.getItem(i));
			if (!n.endsWith("_pickaxe")) continue;
			best = Math.max(best, n.startsWith("netherite") || n.startsWith("diamond") ? 4 : n.startsWith("iron") ? 3 : n.startsWith("stone") ? 2 : 1);
		}
		return best;
	}

	boolean has(String suffix) {
		return count(n -> n.endsWith(suffix)) > 0;
	}

	/** The pickaxe a block category needs to drop anything: stone and coal wood, iron stone, gold and diamond iron. */
	static int tierFor(int cat) {
		return switch (cat) {
			case xen.mod.core.Blocks.STONE, xen.mod.core.Blocks.COAL -> 1;
			case xen.mod.core.Blocks.IRON -> 2;
			case xen.mod.core.Blocks.GOLD, xen.mod.core.Blocks.DIAMOND -> 3;
			default -> 0;
		};
	}

	/** Planks it could have: what it carries plus four for every log. */
	private int wood() {
		return count(n -> n.endsWith("_planks")) + 4 * count(Crafter::isLog);
	}

	private boolean tableNearOrCarried() {
		return nearbyTable() != null || count(n -> n.equals("crafting_table")) > 0;
	}

	/** Could it make a pickaxe good enough for this tier right now (from what it carries)? */
	boolean canMake(int tier) {
		int sticks = Math.max(0, 2 - count(n -> n.equals("stick")));
		int woodForSticks = sticks > 0 ? 2 : 0, woodForTable = tableNearOrCarried() ? 0 : 4;
		if (tier <= 1) return wood() >= 3 + woodForSticks + woodForTable;
		if (tier == 2) return count(Crafter::isStoneMaterial) >= 3 && wood() >= woodForSticks + woodForTable;
		return false;                                                    // iron needs smelting: not yet
	}

	/** What it would make now, most needed first (null: nothing). */
	private String wanted() {
		int tier = pickTier();
		if (tier >= 2 && has("_sword") && has("_axe")) return null;           // (most of the time: nothing to make)
		if (wood() < 2 && count(n -> n.equals("stick")) == 0) return null;     // no wood, no tools
		int stone = count(Crafter::isStoneMaterial);
		if (tier == 0 && canMake(1)) return stone >= 3 && canMake(2) ? "stone_pickaxe" : "wooden_pickaxe";
		if (tier == 1 && canMake(2)) return "stone_pickaxe";
		if (tier >= 1 && !has("_sword")) {
			if (stone >= 2 && sticksOrWood(1, 0)) return "stone_sword";
			if (stone == 0 && sticksOrWood(1, 2 + 4)) return "wooden_sword";         // with wood to spare only
		}
		if (tier >= 2 && !has("_axe") && stone >= 3 && sticksOrWood(2, 0)) return "stone_axe";
		return null;
	}

	/** Enough wood for the sticks (and a table, if there's none) plus extra planks? */
	private boolean sticksOrWood(int sticks, int extra) {
		int need = count(n -> n.equals("stick")) >= sticks ? 0 : 2;
		return wood() >= need + extra + (tableNearOrCarried() ? 0 : 4);
	}

	/** Does it want to (and can it) make something now? */
	boolean ready() {
		if (c.player != null && c.player.isCreative() && order == null) return false;   // a creative player doesn't make tools
		return c.player != null && (order != null || c.player.level().getGameTime() >= nextTry && wanted() != null);
	}

	// ------------------------------------------------------------------------ "craft a boat"
	/** Something it was asked to craft: the recipe, how many more, how many tries it has left. */
	private String order, orderName;
	private int orderLeft, orderSteps;

	/** Busy with something it was asked to craft? */
	boolean hasOrder() {
		return order != null;
	}

	void cancelOrder() {
		order = null;
	}

	/** The wood it has most of, as in "oak" (for "oak_boat", "oak_door"...). */
	private String woodType() {
		java.util.Map<String, Integer> woods = new java.util.HashMap<>();
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String n = path(st), w = null;
			if (n.endsWith("_planks")) w = n.substring(0, n.length() - "_planks".length());
			else if (isLog(n) && !n.equals("bamboo_block")) w = n.replaceFirst("^stripped_", "").replaceFirst("_(log|wood|stem|hyphae)$", "");
			if (w != null) woods.merge(w, st.getCount() * (n.endsWith("_planks") ? 1 : 4), Integer::sum);
		}
		return woods.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse("oak");
	}

	/** The recipe for what someone asked for, from what it carries ("boat" -> "spruce_boat"), or null. */
	private String recipeFor(String thing) {
		String wood = woodType();
		java.util.List<String> tries = new java.util.ArrayList<>();
		switch (thing) {
			case "boat" -> tries.add(wood.equals("bamboo") ? "bamboo_raft" : wood + "_boat");
			case "planks", "door", "fence", "slab", "stairs", "trapdoor", "sign", "button", "pressure_plate", "fence_gate" -> tries.add(wood + "_" + thing);
			case "bed" -> {
				for (String color : new String[] {"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green",
						"cyan", "light_blue", "blue", "purple", "magenta", "pink"}) {
					if (count(n -> n.equals(color + "_wool")) >= 3) tries.add(color + "_bed");
				}
				tries.add("white_bed");
			}
			case "pickaxe", "sword", "axe", "shovel", "hoe" -> {
				if (count(n -> n.equals("diamond")) >= 3) tries.add("diamond_" + thing);
				if (count(n -> n.equals("iron_ingot")) >= 3) tries.add("iron_" + thing);
				if (count(Crafter::isStoneMaterial) >= 3) tries.add("stone_" + thing);
				tries.add("wooden_" + thing);
			}
			default -> {
				tries.add(thing);
				tries.add(wood + "_" + thing);
			}
		}
		for (String t : tries) if (recipe(t) != null) return t;
		return null;
	}

	/** Asked to craft something: the plan, in words (to itself: "You will craft a spruce boat."). */
	String request(String thing, int amount) {
		quietOrder = false;
		if (thing == null || thing.isEmpty()) return "You don't know what to craft.";
		String id = recipeFor(thing);
		if (id == null) return "You don't know how to craft " + thing.replace('_', ' ') + ".";
		order = id;
		orderName = id.replace('_', ' ');
		orderLeft = Math.max(1, Math.min(amount, 64));
		orderSteps = 0;
		nextTry = 0;
		return "You will craft " + (orderLeft == 1 ? (orderName.matches("^[aeiou].*") ? "an " : "a ") + orderName : orderLeft + " " + plural(orderName, orderLeft)) + ".";
	}

	private static final java.util.Set<String> MASS = java.util.Set.of("cobblestone", "wool", "coal", "redstone", "dirt", "sand", "gravel",
			"string", "leather", "glass", "clay", "paper", "sugar", "wheat", "glowstone", "iron", "gold", "planks", "stairs");

	/** "4 torch" -> "4 torches", "2 diamond" -> "2 diamonds" (not "cobblestones"). */
	static String plural(String name, int n) {
		if (n == 1) return name;
		String last = name.substring(name.lastIndexOf(' ') + 1);
		if (MASS.contains(last) || last.endsWith("s")) return name;
		return name + (last.endsWith("ch") || last.endsWith("sh") || last.endsWith("x") ? "es" : "s");
	}

	/** Make an exact recipe (for the builder: "spruce_stairs"), without talking about it. */
	void orderRecipe(String id, int amount) {
		if (recipe(id) == null) return;
		order = id;
		orderName = id.replace('_', ' ');
		orderLeft = Math.max(1, amount);
		orderSteps = 0;
		quietOrder = true;
	}

	private boolean quietOrder;

	/** What a recipe needs that it doesn't have, in words ("5 planks"), or "" if it has it all. */
	private String missing(RecipeHolder<CraftingRecipe> r) {
		java.util.Map<String, int[]> need = new java.util.LinkedHashMap<>();
		java.util.Map<net.minecraft.world.item.crafting.Ingredient, String> names = new java.util.IdentityHashMap<>();
		for (net.minecraft.world.item.crafting.Ingredient ing : r.value().placementInfo().ingredients()) {
			if (ing.isEmpty()) continue;
			String name = names.computeIfAbsent(ing, i -> {
				var items = i.items().toList();
				String first = items.isEmpty() ? "something" : BuiltInRegistries.ITEM.getKey(items.get(0).value()).getPath();
				return (items.size() > 1 ? first.replaceFirst("^[a-z]+_(?=planks|logs?|wool|slab)", "") : first).replace('_', ' ');
			});
			int[] n = need.computeIfAbsent(name, k -> new int[] {0, 0});
			n[0]++;
			if (n[1] == 0) {
				var inv = c.player.getInventory();
				for (int k = 0; k < inv.getContainerSize(); k++) if (ing.test(inv.getItem(k))) n[1] += inv.getItem(k).getCount();
			}
		}
		StringBuilder sb = new StringBuilder();
		for (var e : need.entrySet()) {
			if (e.getValue()[1] >= e.getValue()[0]) continue;
			sb.append(sb.length() > 0 ? " and " : "").append(e.getValue()[0]).append(' ').append(plural(e.getKey(), e.getValue()[0]))
					.append(" (I have ").append(e.getValue()[1]).append(')');
		}
		return sb.toString();
	}

	private boolean fitsSmallGrid(RecipeHolder<CraftingRecipe> r) {
		if (r.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) return shaped.getWidth() <= 2 && shaped.getHeight() <= 2;
		return r.value().placementInfo().ingredients().size() <= 4;
	}

	/** One step toward what it was asked to craft: planks or sticks it needs first, a table, then the thing. */
	private Action orderNext() {
		RecipeHolder<CraftingRecipe> r = recipe(order);
		if (r == null || ++orderSteps > 12) {
			if (!quietOrder) c.chatter("I couldn't make the " + orderName + ", sorry.", true);
			order = null;
			return null;
		}
		c.goals.instant = "crafting " + orderName;
		making = orderName;
		String lack = missing(r);
		if (!lack.isEmpty()) {                                            // make the planks and sticks it needs from what it has
			boolean planks = lack.contains("planks") && count(Crafter::isLog) > 0;
			boolean sticks = lack.contains("stick") && count(n -> n.endsWith("_planks")) >= 2;
			if (planks) return step(craftSmall(planksRecipe()), "planks");
			if (sticks) return step(craftSmall("stick"), "sticks");
			if (!quietOrder) c.chatter("I can't make the " + orderName + ": I need " + lack + ".", true);
			order = null;
			making = null;
			return null;
		}
		int before = count(n -> n.equals(order));
		boolean made;
		if (fitsSmallGrid(r)) {
			made = craftSmall(order);
		} else {
			BlockPos at = nearbyTable();
			if (at == null) {
				if (count(n -> n.equals("crafting_table")) == 0) {
					if (count(n -> n.endsWith("_planks")) < 4 && count(Crafter::isLog) > 0) return step(craftSmall(planksRecipe()), "planks");
					return step(craftSmall("crafting_table"), "a crafting table");
				}
				return step(placeTable(), "a place for the table");
			}
			made = craftAt(at, order);
		}
		c.acted = true;
		int got = count(n -> n.equals(order)) - before;
		if (made && got > 0) orderLeft -= got;
		if (orderLeft <= 0 || made && got == 0) {
			if (!quietOrder) c.chatter(got > 1 || orderName.endsWith("s") ? "Done! I made " + (before + got) + " " + plural(orderName, before + got) + "."
					: "Done! Here's my " + orderName + ".", true);
			XenMod.LOG.info("{} crafted {}", c.name, order);
			order = null;
			making = null;
			return Action.PLACE;
		}
		return made ? Action.PLACE : null;
	}

	/**
	 * The next step toward what it's making: planks, sticks, a table, then the tool at the table. Null when there's
	 * nothing to do (or a step failed: it tries again later).
	 */
	Action next() {
		if (order != null) return orderNext();
		String goal = wanted();
		if (goal == null) {
			making = null;
			return null;
		}
		if (making == null || !making.equals(goal)) c.chatter("I'll make a " + goal.replace('_', ' ') + ".", false);
		making = goal;
		c.goals.instant = "making a " + goal.replace('_', ' ');
		boolean wooden = goal.startsWith("wooden_");
		int sticks = goal.endsWith("_sword") ? 1 : 2;
		int heads = goal.endsWith("_sword") ? 2 : 3;
		int planksNeeded = (wooden ? heads : 0) + (count(n -> n.equals("stick")) >= sticks ? 0 : 2)
				+ (tableNearOrCarried() ? 0 : 4);
		if (count(n -> n.endsWith("_planks")) < planksNeeded) return step(craftSmall(planksRecipe()), "planks");
		if (count(n -> n.equals("stick")) < sticks) return step(craftSmall("stick"), "sticks");
		BlockPos at = nearbyTable();
		if (at == null) {
			if (count(n -> n.equals("crafting_table")) == 0) return step(craftSmall("crafting_table"), "a crafting table");
			return step(placeTable(), "a place for the table");
		}
		return step(craftAt(at, goal), goal);
	}

	private Action step(boolean ok, String what) {
		c.acted = true;
		if (!ok) {
			XenMod.LOG.info("{} couldn't make {} (making {})", c.name, what, making);
			nextTry = c.player.level().getGameTime() + 600;              // try again in half a minute
			making = null;
			return null;
		}
		return Action.PLACE;
	}

	/** The planks recipe for a log it carries ("birch_log" -> "birch_planks"). */
	private String planksRecipe() {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			String n = inv.getItem(i).isEmpty() ? "" : path(inv.getItem(i));
			if (!isLog(n)) continue;
			if (n.equals("bamboo_block")) return "bamboo_planks";
			String wood = n.replaceFirst("^stripped_", "").replaceFirst("_(log|wood|stem|hyphae)$", "");
			return wood + "_planks";
		}
		return "oak_planks";
	}

	@SuppressWarnings("unchecked")
	private RecipeHolder<CraftingRecipe> recipe(String name) {
		var holder = c.server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(name)));
		if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe)) return null;
		return (RecipeHolder<CraftingRecipe>) holder.get();
	}

	/** In its own 2x2 grid: click the recipe, shift-click the result, take back what's left. */
	private boolean craftSmall(String name) {
		RecipeHolder<CraftingRecipe> r = recipe(name);
		if (r == null) return false;
		if (c.player.containerMenu != c.player.inventoryMenu) c.player.closeContainer();
		return craftIn(c.player.inventoryMenu, r);
	}

	private boolean craftIn(AbstractCraftingMenu menu, RecipeHolder<CraftingRecipe> r) {
		ServerLevel level = (ServerLevel) c.player.level();
		int before = count(n -> true);
		menu.handlePlacement(false, false, r, level, c.player.getInventory());      // the recipe book click
		Slot result = menu.getResultSlot();
		boolean made = result.hasItem();
		if (made) Compat.click(menu, result.index, true, c.player);                 // shift-click the result
		for (Slot s : menu.getInputGridSlots()) if (s.hasItem()) Compat.click(menu, s.index, true, c.player);   // leftovers back
		ItemStack carried = menu.getCarried();
		if (!carried.isEmpty()) {
			if (!c.player.getInventory().add(carried)) Compat.drop(c.player, carried.copy());
			menu.setCarried(ItemStack.EMPTY);
		}
		Compat.swing(c.player);
		return made && count(n -> true) != before;
	}

	private long lookedForTable = -1_000;
	private BlockPos tableSeen;

	/** A crafting table within reach (it knows what's within 6 blocks). */
	private BlockPos nearbyTable() {
		ServerLevel level = (ServerLevel) c.player.level();
		double reach = c.player.blockInteractionRange();
		if (table != null && isTable(level, table) && c.player.getEyePosition().distanceTo(Vec3.atCenterOf(table)) <= reach) return table;
		long now = level.getGameTime();
		if (now - lookedForTable < 40 && c.player.containerMenu == c.player.inventoryMenu) {   // looked just now
			return tableSeen != null && isTable(level, tableSeen) ? tableSeen : null;
		}
		lookedForTable = now;
		tableSeen = null;
		BlockPos feet = c.player.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 3, 4))) {
			if (isTable(level, p) && c.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) <= reach) return tableSeen = table = p.immutable();
		}
		return null;
	}

	private static boolean isTable(ServerLevel level, BlockPos p) {
		return level.isLoaded(p) && level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
	}

	/** Put its crafting table down next to it, on solid ground. */
	private boolean placeTable() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (int d = 0; d < 4; d++) {
			int[] f = Perception.forward((c.hands.yaw + d) % 4);
			BlockPos pos = feet.offset(f[0], 0, f[1]), below = pos.below();
			if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(below).isCollisionShapeFullBlock(level, below)) continue;
			if (c.hands.placeItem(pos, s -> path(s).equals("crafting_table"), below, Direction.UP, -1)) {
				table = tableSeen = pos;
				return true;
			}
		}
		return false;
	}

	/** At the table: right-click it, click the recipe, take the tool, close it. */
	private boolean craftAt(BlockPos at, String name) {
		RecipeHolder<CraftingRecipe> r = recipe(name);
		if (r == null) return false;
		c.hands.stop();
		c.hands.use(at);                                                   // right-click: the crafting screen opens
		if (!(c.player.containerMenu instanceof CraftingMenu menu)) return false;
		boolean made = craftIn(menu, r);
		c.player.closeContainer();
		if (made) {
			c.chatter("I made a " + name.replace('_', ' ') + "!", false);
			XenMod.LOG.info("{} made a {}", c.name, name);
		}
		making = null;
		return made;
	}

	/** For its notes. */
	String describe() {
		int tier = pickTier();
		String pick = switch (tier) {
			case 0 -> "You have no pickaxe, so stone and ore would drop nothing.";
			case 1 -> "You have a wooden pickaxe (good for stone and coal).";
			case 2 -> "You have a stone pickaxe (good for iron too).";
			default -> "You have a good pickaxe.";
		};
		return making != null ? pick + " You are making a " + making.replace('_', ' ') + "." : pick;
	}

	static String tierName(int tier) {
		return switch (tier) {
			case 1 -> "a wooden pickaxe";
			case 2 -> "a stone pickaxe";
			case 3 -> "an iron pickaxe";
			default -> "a diamond pickaxe";
		};
	}
}
