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
	/** Not again before this (game time): after a go (its levels come back slowly), or after it couldn't. */
	long retryAt;

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
		for (int i = 0; i < 36; i++) {                                         // (its bag and hotbar only: the slots past them are what it wears, and the menu has no place for those; Ann put her worn armor's slot in, the wrong thing went on the table, "not enough levels" every three seconds)
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

	/** Armor it wears that's worth enchanting and isn't yet (iron or diamond), or null. */
	private net.minecraft.world.entity.EquipmentSlot wornWorth() {
		for (net.minecraft.world.entity.EquipmentSlot es : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			ItemStack s = c.player.getItemBySlot(es);
			if (s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
			if (MindSense.tier(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()) > 2) return es;
		}
		return null;
	}

	/** One piece of what it wears off into its bag, to go on the table (its armor habit puts it back on after). True if it did. */
	private boolean takeOffForTable() {
		var es = wornWorth();
		if (es == null) return false;
		var inv = c.player.getInventory();
		for (int i = 9; i < 36; i++) {
			if (!inv.getItem(i).isEmpty()) continue;
			inv.setItem(i, c.player.getItemBySlot(es).copy());
			c.player.setItemSlot(es, ItemStack.EMPTY);
			c.journal("does", "takes off its " + BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().replace('_', ' ') + " to enchant it");
			return true;
		}
		return false;
	}

	/** Could it enchant something now (or make the table for it)? */
	boolean possible() {
		if (on) return true;
		var items = c.items();
		if (items.getOrDefault("lapis_lazuli", 0) < 1 || c.player.experienceLevel < 1 || worthSlot() < 0 && wornWorth() == null) return false;
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
			retryAt = c.player.level().getGameTime() + 20 * 60 * 20;
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
			for (int k = 1; k <= 2; k++) {                                       // (a block off, or two: in a tunnel a block wide, along it)
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos at = feet.relative(d, k);
					if (!level().getBlockState(at).canBeReplaced() || level().getBlockState(at.below()).canBeReplaced()) continue;
					if (c.hands.placeSeen(at, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("enchanting_table"), at.below(), Direction.UP, -1)) {
						table = at.immutable();
						c.places.remember("enchanting table", table);
						c.acted = true;
						return Action.PLACE;
					}
				}
			}
			c.journal("does", "no spot to put its enchanting table down here: tries again somewhere else");
			on = false;
			retryAt = c.player.level().getGameTime() + 20 * 20;
			return null;
		}
		if (!c.hands.canClick(t)) return c.walkTo(Vec3.atBottomCenterOf(t));
		int slot = worthSlot();
		if (slot < 0 && takeOffForTable()) slot = worthSlot();                // (its tools done: the armor it wears next, a piece at a time)
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
		retryAt = c.player.level().getGameTime() + (done ? 20 * 60 * 2 : 20 * 60 * 20);   // (done: the next piece soon; not enough levels: a good while)
		if (done) {
			c.chatter(c.pick3("Enchanted! That glow...", "Ooh, shiny. Enchanted.", "The table liked that one."), false);
			c.journal("does", "enchanted its gear (option " + (pick + 1) + ")");
		} else {
			c.journal("does", "couldn't enchant yet (not enough levels)");
		}
		return Action.PLACE;
	}
}
