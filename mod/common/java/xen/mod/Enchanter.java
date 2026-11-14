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
	/** New offers dealt in a row (a cheap enchant for them), none it wanted yet. */
	private int rerolls;
	boolean on;
	/** Not again before this (game time): after a go (its levels come back slowly), or after it couldn't. */
	long retryAt;
	private int budget = 2400;
	/** Its enchanting done for now, the table to mine and carry (no home to keep it at). */
	private boolean takeAlong;
	/** The table mined and in its bag: back to its base with it, to put it down there (the team's, for all of them). */
	private boolean bringHome;
	private long baseLookAt;

	/** Its base: its home, or its team's. */
	private BlockPos base() {
		return c.goals.home != null ? c.goals.home : c.mod.teamHome(c);
	}

	/** Something it has that's worth enchanting and isn't yet (a teammate asks this of it). */
	boolean wantsTable() {
		return c.player != null && (worthSlot() >= 0 || wornWorth() != null);
	}

	/** A teammate, near its base, with something worth enchanting: the table stays there for them. */
	private boolean teamNeedsIt(BlockPos at) {
		for (Companion x : c.mod.companions) {
			if (x == c || x.player() == null || x.player().level() != c.player.level() || !c.mod.sameSide(c, x)) continue;
			if (x.player().blockPosition().closerThan(at, 400) && x.enchanter.wantsTable()) return true;
		}
		return false;
	}

	/** An enchanting table within 12 blocks of there (its base has one already?). */
	private BlockPos tableNear(BlockPos at) {
		var level = level();
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-12, -4, -12), at.offset(12, 4, 12)))
			if (level.isLoaded(q) && level.getBlockState(q).is(Blocks.ENCHANTING_TABLE)) return q.immutable();
		return null;
	}

	/** Its table down, a block or two off its feet (on the ground, nothing in the way). Null: no spot here. */
	private Action putDown(String why) {
		BlockPos feet = c.player.blockPosition();
		for (int k = 1; k <= 2; k++) {                                           // (a block off, or two: in a tunnel a block wide, along it)
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos at = feet.relative(d, k);
				if (!level().getBlockState(at).canBeReplaced() || level().getBlockState(at.below()).canBeReplaced()) continue;
				if (c.hands.placeSeen(at, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("enchanting_table"), at.below(), Direction.UP, -1)) {
					table = at.immutable();
					c.places.remember("enchanting table", table);
					c.acted = true;
					if (why != null) {
						XenMod.LOG.info("{} {} at {}", c.name, why, table.toShortString());
						c.journal("does", why);
					}
					return Action.PLACE;
				}
			}
		}
		return null;
	}

	/**
	 * Every so often, not enchanting: home at its base with a table in its bag and none there, it puts it down there (for
	 * the whole team: a teammate's table is theirs too).
	 */
	Action toBase() {
		if (on || c.player == null || c.items().getOrDefault("enchanting_table", 0) == 0) return null;
		long now = c.player.level().getGameTime();
		if (now < baseLookAt) return null;
		BlockPos base = base();
		if (base == null || !c.player.blockPosition().closerThan(base, 12) || c.player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			baseLookAt = now + 20 * 10;
			return null;
		}
		BlockPos there = tableNear(base);
		if (there != null) {                                                     // (one there already: that's the team's; its own stays in its bag)
			if (table == null || !table.equals(there)) c.places.remember("enchanting table", there);
			baseLookAt = now + 20 * 60;
			return null;
		}
		Action put = putDown("puts its enchanting table down at its base, for the team");
		baseLookAt = now + (put == null ? 20 * 30 : 20 * 5);
		return put;
	}


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
		if (table == null && c.mod.config.luck > 0) {                          // (knowing the world: a table another Xen made, near enough, does as well as its own; Cid had one, nine others dug obsidian for theirs)
			BlockPos best = null;
			for (Companion x : c.mod.companions) {
				if (x == c || x.player() == null || x.player().level() != c.player.level()) continue;
				BlockPos t = x.places.get("enchanting table");
				if (t == null || t.distSqr(c.player.blockPosition()) > 150 * 150) continue;   // (near: a trip of more than a minute or two isn't worth it)
				if (level().isLoaded(t) && !level().getBlockState(t).is(Blocks.ENCHANTING_TABLE)) continue;
				if (best == null || t.distSqr(c.player.blockPosition()) < best.distSqr(c.player.blockPosition())) best = t;
			}
			if (best != null) {
				table = best;
				c.places.remember("enchanting table", best);
				XenMod.LOG.info("{} knows of an enchanting table another Xen made at {}", c.name, best.toShortString());
			}
		}
		return table;
	}

	/** Something worth enchanting (iron or diamond tools, weapons, armor) that isn't yet. */
	/** What's on the table back into its bag, and this from its bag onto the table. */
	private void swapIn(EnchantmentMenu menu, int invSlot) {
		Compat.click(menu, 0, true, c.player);
		Compat.click(menu, invSlot < 9 ? 29 + invSlot : 2 + invSlot - 9, true, c.player);
	}

	/** The cheapest offer it can pay for (-1: none). */
	private int cheapest(EnchantmentMenu menu) {
		for (int i = 0; i < 3; i++)
			if (menu.costs[i] > 0 && menu.costs[i] <= c.player.experienceLevel && c.player.experienceLevel >= i + 1) return i;
		return -1;
	}

	/** Something cheap to enchant for new offers: a book, else its lesser tool or armor (iron or below), not enchanted yet (-1: none). */
	private int fodder() {
		var inv = c.player.getInventory();
		int lesser = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (n.equals("book")) return i;
			if (MindSense.tier(n) <= 3 && lesser < 0) lesser = i;
		}
		return lesser;
	}

	/** The strongest offer on the table it can pay for (-1: none). */
	private int choose(EnchantmentMenu menu) {
		for (int i = 2; i >= 0; i--)
			if (menu.costs[i] > 0 && menu.costs[i] <= c.player.experienceLevel && c.player.experienceLevel >= i + 1) return i;
		return -1;
	}

	/** The enchantment an offer shows ("minecraft:sharpness"; "" if none or unknown). */
	private String clue(EnchantmentMenu menu, int i) {
		int id = menu.enchantClue[i];
		if (id < 0) return "";
		try {
			var reg = c.player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
			var h = reg.asHolderIdMap().byId(id);
			return h == null ? "" : h.getRegisteredName();
		} catch (RuntimeException e) {
			return "";
		}
	}

	/** Is this offer what it wants on the item on the table (by default: Sharpness on a sword, Fortune or Efficiency on a tool, Protection on armor)? */
	private boolean wanted(EnchantmentMenu menu, int i) {
		return wants(BuiltInRegistries.ITEM.getKey(menu.getSlot(0).getItem().getItem()).getPath(), clue(menu, i));
	}

	static boolean wants(String item, String enchantment) {
		String e = enchantment.replace("minecraft:", "");
		if (item.endsWith("_sword")) return e.equals("sharpness");
		if (item.endsWith("_pickaxe") || item.endsWith("_shovel") || item.endsWith("_axe") || item.endsWith("_hoe")) return e.equals("fortune") || e.equals("efficiency");
		if (item.endsWith("_helmet") || item.endsWith("_chestplate") || item.endsWith("_leggings") || item.endsWith("_boots")) return e.equals("protection");
		return false;
	}

	/** Its other pieces worth enchanting (iron or diamond, not enchanted yet), up to three, past the one on the table. */
	private java.util.List<Integer> others(int skip, int tier) {
		var inv = c.player.getInventory();
		java.util.List<Integer> out = new java.util.ArrayList<>();
		for (int i = 0; i < 36 && out.size() < 3; i++) {
			ItemStack s = inv.getItem(i);
			if (i == skip || s.isEmpty() || s.isEnchanted() || !s.isEnchantable()) continue;
			if (MindSense.tier(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()) >= Math.max(3, tier)) out.add(i);   // (as good as the one it took off: not an iron pickaxe for its diamond chestplate's turn; Jay and Gus, Efficiency on iron)
		}
		return out;
	}

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

	private static final java.util.Set<String> CHEAP = java.util.Set.of("dirt", "cobblestone", "cobbled_deepslate", "gravel", "andesite", "diorite", "granite", "tuff",
			"netherrack", "sand", "red_sand", "rotten_flesh", "wheat_seeds", "beetroot_seeds", "leaf_litter", "flint", "poisonous_potato", "spider_eye", "calcite", "dripstone_block");

	/** One piece of what it wears off into its bag, to go on the table (its armor habit puts it back on after). True if it did. */
	private boolean takeOffForTable() {
		var es = wornWorth();
		if (es == null) return false;
		var inv = c.player.getInventory();
		int free = -1;
		for (int i = 0; i < 36 && free < 0; i++) if (inv.getItem(i).isEmpty()) free = i;
		if (free < 0) {                                                          // a full bag: the cheapest stack goes, as a player presses Q (Ann's full bag: her boots never came off, her enchanting begun and dropped every few seconds)
			int cheapest = -1;
			for (int i = 0; i < 36; i++) {
				String id = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
				if (!CHEAP.contains(id) && !id.endsWith("_sapling")) continue;
				if (cheapest < 0 || inv.getItem(i).getCount() < inv.getItem(cheapest).getCount()) cheapest = i;
			}
			if (cheapest < 0) return false;
			ItemStack out = inv.removeItemNoUpdate(cheapest);
			c.hands.toss(out);
			c.journal("does", "tosses " + out.getCount() + " " + BuiltInRegistries.ITEM.getKey(out.getItem()).getPath().replace('_', ' ') + " to make room for its armor at the table");
			free = cheapest;
		}
		for (int i = free; i <= free; i++) {
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
		BlockPos t = table();
		budget = 2400 + (t == null || c.items().getOrDefault("enchanting_table", 0) > 0 ? 0 : (int) Math.min(6000, 12 * Math.sqrt(t.distSqr(c.player.blockPosition()))));   // (a walk to its table: time for that too)
		return "You will enchant your best gear at your enchanting table.";
	}

	void cancel() {
		on = false;
	}

	Action next() {
		if (!on) return null;
		if (++tries > budget) {                                                // (two minutes, more for a walk to its table: something's wrong, it gives up)
			on = false;
			retryAt = c.player.level().getGameTime() + 20 * 60 * 5;
			return null;
		}
		if (bringHome) {                                                        // its table in its bag: back to its base with it, and down there
			BlockPos base = base();
			if (base == null || c.items().getOrDefault("enchanting_table", 0) == 0) {
				bringHome = false;
				on = false;
				return null;
			}
			if (!c.player.blockPosition().closerThan(base, 6)) {
				c.goals.instant = "bringing its enchanting table back to its base";
				return c.walkTo(Vec3.atBottomCenterOf(base));
			}
			bringHome = false;
			on = false;
			baseLookAt = 0;
			return toBase();
		}
		if (takeAlong) {                                                        // its enchanting done for now and no home to keep the table at: it mines it and takes it along, as players do
			BlockPos tt = table();
			if (tt == null) {
				takeAlong = false;
				on = false;
				BlockPos base = base();
				if (base != null && c.items().getOrDefault("enchanting_table", 0) > 0 && base.closerThan(c.player.blockPosition(), 160)
						&& !base.closerThan(c.player.blockPosition(), 12) && tableNear(base) == null) {   // (its base near enough, and no table there: it brings it back)
					bringHome = true;
					on = true;
					tries = 0;
					budget = 2400 + (int) (12 * Math.sqrt(base.distSqr(c.player.blockPosition())));
					c.journal("does", "brings its enchanting table back to its base");
				}
				return null;
			}
			if (!c.hands.canClick(tt)) return c.walkTo(Vec3.atBottomCenterOf(tt));
			if (c.hands.mine(tt)) {
				c.acted = true;
				return Action.MINE;
			}
			takeAlong = false;
			on = false;
			return null;
		}
		BlockPos t = table();
		boolean carried = c.items().getOrDefault("enchanting_table", 0) > 0;
		if (t != null && carried && !t.closerThan(c.player.blockPosition(), 48)) t = null;   // its own in its bag: down here, not a trip to the far one (Cid walked to his first, 716 blocks off, gave up, and waited twenty minutes, again and again)
		if (t == null) {
			if (!carried) {
				if (!c.crafter.hasOrder()) c.crafter.orderRecipe("enchanting_table", 1);
				return null;
			}
			BlockPos base = base();
			BlockPos near = base != null && base.closerThan(c.player.blockPosition(), 48) ? base : c.player.blockPosition();   // (its base if it's near; a far one isn't worth the trip, it takes the table along after)
			if (c.player.blockPosition().distSqr(near) > 36) return c.walkTo(Vec3.atBottomCenterOf(near));
			Action put = putDown(near.equals(base) ? "puts its enchanting table down at its base" : null);
			if (put != null) return put;
			c.journal("does", "no spot to put its enchanting table down here: tries again somewhere else");
			on = false;
			retryAt = c.player.level().getGameTime() + 20 * 20;
			return null;
		}
		if (!c.hands.canClick(t)) return c.walkTo(Vec3.atBottomCenterOf(t));
		int slot = worthSlot();
		int bagTier = slot < 0 ? 0 : MindSense.tier(BuiltInRegistries.ITEM.getKey(c.player.getInventory().getItem(slot).getItem()).getPath());
		var worn = wornWorth();
		if (worn != null && bagTier < MindSense.tier(BuiltInRegistries.ITEM.getKey(c.player.getItemBySlot(worn).getItem()).getPath()) && takeOffForTable())
			slot = worthSlot();                                                // (the diamond armor it wears before a spare iron pickaxe in its bag: Ann's third enchant went on that, her armor bare)
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
		int pick = choose(menu);
		boolean reroll = false;
		String meant = BuiltInRegistries.ITEM.getKey(menu.getSlot(0).getItem().getItem()).getPath();
		if (pick >= 0 && !wanted(menu, pick)) {                                 // none of what it wants on offer for this one: its other pieces as good first (enchanted at the table once, an item can't be again: a wrong one is for good)
			int firstTier = MindSense.tier(meant);
			boolean found = false;
			for (int other : others(slot, firstTier)) {
				swapIn(menu, other);
				int op = choose(menu);
				if (op >= 0 && wanted(menu, op)) {
					pick = op;
					found = true;
					break;
				}
			}
			if (!found) {                                                       // nothing it wants on any of them: the cheapest offer on a book or a lesser tool, for new offers next time (what players do: every enchanting deals the cards again; Jay's iron sword and Gus's diamond sword got Knockback)
				int fodder = rerolls < 3 ? fodder() : -1;                      // (three goes at new offers, then the best it's offered: its levels aren't for dealing cards all day)
				int cheap = -1;
				if (fodder >= 0) {
					swapIn(menu, fodder);
					cheap = cheapest(menu);
				}
				if (cheap >= 0) {
					pick = cheap;
					reroll = true;
				} else {
					int best = worthSlot();
					if (best >= 0) swapIn(menu, best);
					pick = choose(menu);
				}
			}
		}
		String clue = pick >= 0 ? clue(menu, pick).replace("minecraft:", "") : "";
		String onIt = BuiltInRegistries.ITEM.getKey(menu.getSlot(0).getItem().getItem()).getPath();
		boolean liked = pick >= 0 && wanted(menu, pick);
		boolean done = pick >= 0 && menu.clickMenuButton(c.player, pick);
		Compat.click(menu, 0, true, c.player);                                 // the item back into its bag
		Compat.click(menu, 1, true, c.player);                                 // and what lapis is left
		c.player.closeContainer();
		c.acted = true;
		on = false;
		boolean moreNow = done && (worthSlot() >= 0 || wornWorth() != null) && c.items().getOrDefault("lapis_lazuli", 0) > 0;
		BlockPos base = base();
		boolean atBase = base != null && t.closerThan(base, 24);
		if (!moreNow && (!atBase || !teamNeedsIt(t))) {                       // nothing more to enchant here now: away from its base it takes the table along (Ann left hers by a lava pool and was 330 blocks off when she next wanted it); at its base it's its choice, kept there while a teammate still needs it, else taken along (and brought back)
			takeAlong = true;
			on = true;
			c.journal("does", atBase ? "takes its enchanting table along (no teammate needs it here now)" : "takes its enchanting table along (no home to keep it at)");
		} else if (!moreNow && atBase) c.journal("does", "leaves its enchanting table at the base for its team");
		retryAt = c.player.level().getGameTime() + (done && moreNow ? 20 * 5 : done ? 20 * 60 * 2 : 20 * 60 * 20);   // (done and more to enchant: the next piece straight away, as a player does them one after another; not enough levels: a good while)
		if (done) rerolls = reroll ? rerolls + 1 : 0;
		if (done && reroll) {
			c.journal("does", "enchants its " + onIt.replace('_', ' ') + " (option " + (pick + 1) + ", " + clue.replace('_', ' ') + ") for new offers: none it wanted for its " + meant.replace('_', ' '));
			retryAt = c.player.level().getGameTime() + 20 * 10;                  // (the new offers: straight back to them)
		} else if (done) {
			c.chatter(c.pick3("Enchanted! That glow...", "Ooh, shiny. Enchanted.", "The table liked that one."), false);
			c.journal("does", "enchanted its gear (option " + (pick + 1) + ", its " + onIt.replace('_', ' ') + ": " + clue.replace('_', ' ') + (liked ? ", one it wanted" : ", none it wanted on offer") + ")");
		} else {
			c.journal("does", "couldn't enchant yet (not enough levels)");
		}
		return Action.PLACE;
	}
}
