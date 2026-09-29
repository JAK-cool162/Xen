package xen.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import xen.mod.core.Action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handing its spare things to other Xens, like players on a server do: armor it doesn't wear (it already wears the best
 * it has), a second sword or pickaxe, blocks and food it has plenty of. It walks over and tosses it to them; they pick
 * it up, put it on or use it, and say thanks.
 * <ul>
 *   <li><b>Its team and its village</b>: it knows what they're missing (they talk), so it gives what they lack.</li>
 *   <li><b>Anyone else</b>: only what anyone can see they need (no helmet, weaker armor, fighting bare-handed), and only
 *   if it trusts them and has a kind streak. Never to someone it counts as an enemy.</li>
 * </ul>
 * A greedy Xen keeps more back and gives only to its own; a kind one gives more readily.
 */
final class Sharing {
	private final Companion c;
	/** A gift on its way: to whom, which item (bag slot and id), how many, until when it tries. */
	private Companion to;
	private String what;
	private int count;
	private long until;
	private long nextLook;
	/** When it last gave something to each Xen (the next thing 15 seconds later, once they've picked it up). */
	private final Map<UUID, Long> gaveAt = new HashMap<>();
	/** Something a Xen is tossing to it: it picks that up (it isn't usually worth a detour), until when. */
	String expecting;
	long expectingUntil;

	Sharing(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	boolean busy() {
		return to != null;
	}

	void cancel() {
		to = null;
	}

	/** Is this item on the ground one a friend just tossed to it? */
	boolean expected(ItemStack s) {
		return expecting != null && c.player != null && now() < expectingUntil
				&& BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(expecting);
	}

	// ------------------------------------------------------------------------------ what's spare, what they need
	private static int armorValue(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return n.startsWith("netherite") ? 6 : n.startsWith("diamond") ? 5 : n.startsWith("iron") ? 4 : n.startsWith("chainmail") ? 3
				: n.startsWith("golden") ? 2 : n.startsWith("leather") || n.startsWith("turtle") ? 1 : 0;
	}

	private static int tier(String id) {
		return id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3 : id.startsWith("stone") ? 2
				: id.startsWith("golden") ? 1 : id.startsWith("wooden") ? 1 : 0;
	}

	/** The best tier of a kind of tool it carries or holds ("_sword", "_pickaxe"), -1 if none. */
	private static int best(Companion x, String kind) {
		int b = -1;
		var inv = x.player.getInventory();
		for (int i = 0; i < 36; i++) {
			String id = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (id.endsWith(kind)) b = Math.max(b, tier(id));
		}
		return b;
	}

	private static int count(Companion x, java.util.function.Predicate<String> which) {
		int n = 0;
		var inv = x.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && which.test(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) n += s.getCount();
		}
		return n;
	}

	/** Does it have it (in its bag, in hand or worn)? */
	private static boolean has(Companion x, String id) {
		var inv = x.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().equals(id)) return true;
		}
		for (EquipmentSlot s : EquipmentSlot.values()) {
			if (BuiltInRegistries.ITEM.getKey(x.player.getItemBySlot(s).getItem()).getPath().equals(id)) return true;
		}
		return false;
	}

	private static boolean block(String id) {
		return id.equals("cobblestone") || id.equals("dirt") || id.equals("cobbled_deepslate") || id.endsWith("_planks");
	}

	/**
	 * Something it has to spare that this Xen needs: {item id, count} (the item in its bag), or null. Friends: what
	 * they lack (they talk); others: only what shows (armor they wear, the hand they fight with).
	 */
	private String[] spareFor(Companion them, boolean friend) {
		var inv = c.player.getInventory();
		var greed = c.personality.sin(xen.mod.core.Sins.GREED);
		for (int i = 0; i < 36; i++) {                                               // armor in its bag: it wears better already
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			EquipmentSlot slot = c.player.getEquipmentSlotForItem(s);
			if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR || armorValue(s) == 0) continue;
			ItemStack theirs = them.player.getItemBySlot(slot);                      // (anyone can see what someone wears)
			if (theirs.isEmpty() || armorValue(theirs) < armorValue(s)) return new String[] {BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), "1"};
		}
		String sword = spareTool("_sword");
		if (sword != null) {
			boolean bareHanded = !Companion.isWeapon(them.player.getMainHandItem()) && them.fightingNow();   // (seen fighting with its fists)
			if (friend ? best(them, "_sword") < tier(sword) : bareHanded) return new String[] {sword, "1"};
		}
		if (!friend) return null;
		String pick = spareTool("_pickaxe");
		if (pick != null && best(them, "_pickaxe") < tier(pick)) return new String[] {pick, "1"};
		int blocks = count(c, Sharing::block), theirBlocks = count(them, Sharing::block);
		if (theirBlocks < 16 && blocks > 64 + Goals.NIGHT_BLOCKS + (int) (64 * greed)) {
			for (int i = 0; i < 36; i++) {
				String id = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
				if (block(id) && inv.getItem(i).getCount() >= 16) return new String[] {id, "" + Math.min(32, inv.getItem(i).getCount())};
			}
		}
		int food = c.items().getOrDefault("food", 0);
		if (them.items().getOrDefault("food", 0) == 0 && them.player.getFoodData().getFoodLevel() < 16 && food > 10 + (int) (10 * greed)) {
			for (int i = 0; i < 36; i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD) && s.getCount() >= 3) {
					return new String[] {BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), "3"};
				}
			}
		}
		return null;
	}

	/** A second (worse) sword or pickaxe it carries, keeping its best: the item id, or null. */
	private String spareTool(String kind) {
		var inv = c.player.getInventory();
		int best = -1, n = 0;
		String worst = null;
		int worstTier = 99;
		for (int i = 0; i < 36; i++) {
			String id = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (!id.endsWith(kind)) continue;
			n++;
			int t = tier(id);
			best = Math.max(best, t);
			if (t <= worstTier) {
				worstTier = t;
				worst = id;
			}
		}
		return n >= 2 ? worst : null;
	}

	// ------------------------------------------------------------------------------ each decision
	/** Every half minute or so: someone close by it could help? Then the walk over and the toss. */
	Action next() {
		var p = c.player;
		if (p == null || c.inArena || c.minion) return null;
		long now = now();
		if (to != null) return deliver(now);
		if (now < nextLook || c.fightingNow() || c.chores.busy() || c.builder.busy() || c.rider.busy() || c.uses.busy()) return null;
		nextLook = now + 300 + c.random().nextInt(300);
		var who = c.personality;
		float greed = who.sin(xen.mod.core.Sins.GREED);
		Tribe mine = c.tribe();
		for (Companion them : c.mod.companions) {
			if (them == c || them.player == null || them.player.level() != p.level() || them.inArena || them.player.distanceTo(p) > 24) continue;
			if (now - gaveAt.getOrDefault(them.player.getUUID(), -100_000L) < 300) continue;   // (one thing at a time: they pick it up first)
			if (!p.hasLineOfSight(them.player)) continue;
			float trust = c.trust(them.player.getUUID());
			if (trust < 0) continue;                                                // (never an enemy)
			boolean friend = p.isAlliedTo(them.player) || mine != null && mine == them.tribe()
					|| c.owner != null && c.owner.equals(them.owner);                   // its team, its village, the same owner
			if (!friend && (greed > 0.6f || trust < 0.4f || who.kindness < 0.5f && who.sin(xen.mod.core.Sins.LUST) < 0.6f)) continue;
			String[] gift = spareFor(them, friend);
			if (gift == null) continue;
			to = them;
			what = gift[0];
			count = Integer.parseInt(gift[1]);
			until = now + 20 * 30;
			return deliver(now);
		}
		return null;
	}

	private Action deliver(long now) {
		var p = c.player;
		if (to.player == null || !to.player.isAlive() || to.player.level() != p.level() || now > until || c.fightingNow()) {
			to = null;
			return null;
		}
		String name = to.name;
		c.goals.instant = "bringing " + name + " " + what.replace('_', ' ');
		c.acted = true;
		if (p.distanceTo(to.player) > 2.5) return c.walkTo(to.player.position());
		var inv = p.getInventory();
		int slot = -1;
		for (int i = 0; i < 36; i++) {                                               // (its fullest stack of it)
			if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().equals(what) && (slot < 0 || inv.getItem(i).getCount() > inv.getItem(slot).getCount())) slot = i;
		}
		if (slot < 0) {
			to = null;
			return null;
		}
		c.hands.face(to.player.position().add(0, 0.2, 0));                         // at their feet, the way players hand things over
		ItemStack out = inv.getItem(slot).split(count);
		if (!Compat.tossTo(p, out, to.player.getUUID())) inv.add(out);   // (marked for them: only they can pick it up)
		Companion them = to;
		to = null;
		gaveAt.put(them.player.getUUID(), now);
		them.sharing.expecting = what;
		them.sharing.expectingUntil = now + 200;
		String thing = out.getCount() > 1 ? out.getCount() + " " + what.replace('_', ' ') : "this " + what.replace('_', ' ');
		c.chatter(c.pick3("Here, " + name + ", take " + thing + ". I've got a spare.", name + "! Catch. You need it more than me.",
				"I don't need " + thing + ". It's yours, " + name + "."), true);
		c.journal("does", "gives " + name + " " + out.getCount() + " " + what);
		c.trust(them.player.getUUID(), 0.02f);
		String given = what;
		c.mod.later(60, () -> {                                                      // it picked it up: thanks, and it trusts the giver more
			if (them.player == null || c.player == null) return;
			if (!has(them, given)) return;
			them.trust(c.player.getUUID(), 0.08f);
			them.chatter(them.pick3("Thanks, " + c.name + "!", "Oh nice, thank you!", "You're the best, " + c.name + "."), true);
			them.journal("does", "got " + given.replace('_', ' ') + " from " + c.name);
		});
		return Action.IDLE;
	}
}
