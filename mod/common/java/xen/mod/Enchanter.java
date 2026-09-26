package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * The enchanting table, like a player uses it: it makes one (a book, two diamonds, four obsidian), puts it down at
 * home (by its bookshelves if it has some: they make better enchantments), then puts its best unenchanted tool or
 * armor in with lapis, and picks the strongest enchantment its levels pay for.
 */
final class Enchanter {
	private final Companion c;
	private BlockPos table;
	private int step, tries;
	boolean on;

	Enchanter(Companion c) {
		this.c = c;
	}

	private ServerLevel level() {
		return (ServerLevel) c.player.level();
	}

	/** Its enchanting table: remembered, or one it sees around its home. */
	BlockPos table() {
		if (table != null && level().getBlockState(table).is(Blocks.ENCHANTING_TABLE)) return table;
		table = c.places.get("enchanting table");
		if (table != null && !level().getBlockState(table).is(Blocks.ENCHANTING_TABLE)) table = null;
		return table;
	}

	/** Something worth enchanting (iron or diamond tools, weapons, armor) that isn't yet. */
	private int worthSlot() {
		var inv = c.player.getInventory();
		int best = -1, bestTier = 2;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			int t = MindSense.tier(n);
			if (t > bestTier) {
				bestTier = t;
				best = i;
			}
		}
		return best;
	}

	/** Could it enchant something now (or make the table for it)? */
	boolean possible() {
		if (on) return true;
		var items = c.items();
		if (items.getOrDefault("lapis_lazuli", 0) < 1 || c.player.experienceLevel < 1 || worthSlot() < 0) return false;
		return table() != null || items.getOrDefault("enchanting_table", 0) > 0
				|| items.getOrDefault("book", 0) >= 1 && items.getOrDefault("diamond", 0) >= 2 && items.getOrDefault("obsidian", 0) >= 4;
	}

	String start() {
		if (!possible()) return "You can't enchant anything: you need lapis, levels, something worth it and an enchanting table (a book, 2 diamonds, 4 obsidian).";
		on = true;
		step = 0;
		tries = 0;
		return "You will enchant your best gear at your enchanting table.";
	}

	void cancel() {
		on = false;
	}

	Action next() {
		if (!on) return null;
		if (++tries > 2400) {                                                  // (two minutes: something's wrong, it gives up)
			on = false;
			return null;
		}
		BlockPos t = table();
		if (t == null) {
			if (c.items().getOrDefault("enchanting_table", 0) == 0) {
				if (!c.crafter.hasOrder()) c.crafter.orderRecipe("enchanting_table", 1);
				return null;
			}
			BlockPos near = c.goals.home != null ? c.goals.home : c.player.blockPosition();
			if (c.player.blockPosition().distSqr(near) > 36) return c.walkTo(Vec3.atBottomCenterOf(near));
			BlockPos feet = c.player.blockPosition();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos at = feet.relative(d, 2);
				if (!level().getBlockState(at).canBeReplaced() || level().getBlockState(at.below()).canBeReplaced()) continue;
				if (c.hands.placeSeen(at, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("enchanting_table"), at.below(), Direction.UP, -1)) {
					table = at.immutable();
					c.places.remember("enchanting table", table);
					c.acted = true;
					return Action.PLACE;
				}
			}
			on = false;
			return null;
		}
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(t)) > c.player.blockInteractionRange() - 0.5) return c.walkTo(Vec3.atBottomCenterOf(t));
		int slot = worthSlot();
		if (slot < 0 || c.items().getOrDefault("lapis_lazuli", 0) == 0) {
			on = false;
			return null;
		}
		c.hands.stop();
		c.hands.use(t);                                                        // right-click: the enchanting screen
		if (!(c.player.containerMenu instanceof EnchantmentMenu menu)) {
			on = false;
			return null;
		}
		// the menu: 0 the item, 1 lapis, then its bag (2-28) and hotbar (29-37)
		int item = slot < 9 ? 29 + slot : 2 + slot - 9;
		Compat.click(menu, item, true, c.player);                              // shift-click: the item goes in
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (!BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().equals("lapis_lazuli")) continue;
			Compat.click(menu, i < 9 ? 29 + i : 2 + i - 9, true, c.player);    // and the lapis
			break;
		}
		int pick = -1;
		for (int i = 2; i >= 0; i--) {                                          // the strongest it can pay for
			if (menu.costs[i] > 0 && menu.costs[i] <= c.player.experienceLevel && c.player.experienceLevel >= i + 1) {
				pick = i;
				break;
			}
		}
		boolean done = pick >= 0 && menu.clickMenuButton(c.player, pick);
		Compat.click(menu, 0, true, c.player);                                 // the item back into its bag
		Compat.click(menu, 1, true, c.player);                                 // and what lapis is left
		c.player.closeContainer();
		c.acted = true;
		on = false;
		if (done) {
			c.chatter(c.pick3("Enchanted! That glow...", "Ooh, shiny. Enchanted.", "The table liked that one."), false);
			c.journal("does", "enchanted its gear (option " + (pick + 1) + ")");
		} else {
			c.journal("does", "couldn't enchant yet (not enough levels)");
		}
		return Action.PLACE;
	}
}
