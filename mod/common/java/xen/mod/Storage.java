package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Chests, like a player uses them. A Xen with a home keeps a chest there (and its tribe keeps a few in the middle of
 * the village): when its bag gets full, or it carries piles of things it doesn't need on the way (dirt, gravel,
 * stone, flowers, rotten flesh), it goes home and puts them away, keeping its tools, armor, food, torches and a stack
 * of blocks. It remembers what it put in which chest (what it saw when it opened it), and when it needs something
 * (logs for a house, iron for a pickaxe) it looks in its chests before going out to get more. Chests it finds out in
 * the world that nobody has opened yet (a dungeon's, a camp's, a trial chamber's) it opens and loots, like anyone
 * would; chests other players put down it leaves alone.
 */
final class Storage {
	enum Job { STORE, TAKE, LOOT }

	private final Companion c;
	Job job;
	private BlockPos chest;
	private String takeKey;
	private int takeAmount, had;
	private long until, placedAt;
	/** Chests it knows (its own and its tribe's), per dimension name + position, and what it saw in them last. */
	final Map<BlockPos, Map<String, Integer>> chests = new LinkedHashMap<>();
	private final Set<BlockPos> looted = new HashSet<>();
	private long nextLookForLoot;
	String doing = "";

	Storage(Companion c) {
		this.c = c;
	}

	boolean busy() {
		return job != null;
	}

	void cancel() {
		job = null;
		chest = null;
		if (c.player != null && c.player.containerMenu != c.player.inventoryMenu) c.player.closeContainer();
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	// ------------------------------------------------------------------------------ what it keeps
	private static final Set<String> JUNK = Set.of("dirt", "gravel", "sand", "red_sand", "andesite", "diorite", "granite", "tuff", "deepslate",
			"cobbled_deepslate", "netherrack", "rotten_flesh", "poisonous_potato", "spider_eye", "wheat_seeds", "beetroot_seeds", "flint",
			"clay_ball", "kelp", "sugar_cane", "calcite", "dripstone_block", "pointed_dripstone", "blackstone", "basalt", "end_stone", "moss_block");

	/** What it always keeps on itself (by item key), or how many of it. Max value: all of them. */
	private int keep(String key, ItemStack s) {
		if (s.isDamageableItem() || Hands.isTotem(s)) return Integer.MAX_VALUE;               // tools, weapons, armor, shields, bows
		return switch (key) {
			case "food" -> 24;
			case "torch" -> 48;
			case "cobblestone" -> 64;
			case "dirt" -> c.items().getOrDefault("cobblestone", 0) >= 32 ? 0 : 32;
			case "log" -> 16;
			case "coal", "charcoal" -> 16;
			case "arrow", "ender_eye", "ender_pearl", "blaze_rod", "blaze_powder", "obsidian", "flint_and_steel", "bucket", "water_bucket",
					"lava_bucket", "trial_key", "ominous_trial_key", "wind_charge", "crafting_table", "furnace", "chest", "golden_helmet",
					"golden_boots", "diamond", "emerald", "raw_iron", "iron_ingot", "gold_ingot", "raw_gold", "string", "feather", "stick" -> Integer.MAX_VALUE;
			default -> key.endsWith("_planks") ? 16 : key.endsWith("_bed") || key.endsWith("_sign") ? 1 : JUNK.contains(key) ? 0 : 16;
		};
	}

	private int usedSlots() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) if (!inv.getItem(i).isEmpty()) n++;
		return n;
	}

	/** Does it want to put things away (bag nearly full, or a lot of stuff it doesn't need on it)? */
	boolean wantsToStore() {
		if (c.player == null || c.player.isCreative()) return false;
		int junk = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && JUNK.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) junk += s.getCount();
		}
		return usedSlots() >= 30 || junk >= 96;
	}

	// ------------------------------------------------------------------------------ requests
	/** Put its things away in a chest (at home, or its tribe's). The plan in words, or why not. */
	String store() {
		BlockPos at = homeChest();
		if (at == null && homeSpot() == null) return "You have no home or chest to put things in yet.";
		begin(Job.STORE);
		chest = at;
		until = now() + 20 * 90;
		return at != null ? "You will put your things away in the chest at home." : "You will put a chest down at home and put your things in it.";
	}

	/** Take some of something out of a chest it knows has it. The plan, or null if no chest it knows has any. */
	String take(String key, int amount) {
		BlockPos at = chestWith(key);
		if (at == null) return null;
		begin(Job.TAKE);
		chest = at;
		takeKey = key;
		takeAmount = amount;
		had = c.items().getOrDefault(key, 0);
		until = now() + 20 * 90;
		return "You will get " + amount + " " + key.replace('_', ' ') + " from your chest.";
	}

	private void begin(Job j) {
		cancel();
		job = j;
		c.journal("does", "storage: " + j.name().toLowerCase(java.util.Locale.ROOT));
	}

	/** A chest it knows with at least one of this in it (the closest), or null. */
	BlockPos chestWith(String key) {
		if (c.player == null) return null;
		BlockPos best = null, feet = c.player.blockPosition();
		for (var e : chests.entrySet()) {
			if (e.getValue().getOrDefault(key, 0) <= 0 || !isChest(e.getKey())) continue;
			if (best == null || e.getKey().distSqr(feet) < best.distSqr(feet)) best = e.getKey();
		}
		return best;
	}

	int inChests(String key) {
		int n = 0;
		for (var m : chests.values()) n += m.getOrDefault(key, 0);
		return n;
	}

	private boolean isChest(BlockPos p) {
		ServerLevel level = (ServerLevel) c.player.level();
		if (!level.isLoaded(p)) return true;                                        // (far away: it trusts its memory)
		String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
		return n.equals("chest") || n.equals("barrel") || n.equals("trapped_chest");
	}

	/** Its chest at home (or its tribe's), one it knows that isn't full; null if none. */
	private BlockPos homeChest() {
		Tribe t = c.tribe();
		if (t != null) for (BlockPos p : t.storage) if (isChest(p) && !full(p)) return p;
		BlockPos home = homeSpot();
		for (BlockPos p : chests.keySet()) if (isChest(p) && !full(p) && (home == null || p.distSqr(home) < 24 * 24)) return p;
		return null;
	}

	private boolean full(BlockPos p) {
		Map<String, Integer> m = chests.get(p);
		return m != null && m.getOrDefault("#slots", 0) >= 27;
	}

	private BlockPos homeSpot() {
		Tribe t = c.tribe();
		if (t != null && t.center != null && t.dim.equals(Places.dim(c.player.level()))) return t.center;
		return c.goals.home;
	}

	// ------------------------------------------------------------------------------ doing it
	Action next() {
		if (job == null) return null;
		if (now() > until) {
			c.chatter("I couldn't get to the chest.", false);
			cancel();
			return null;
		}
		if (chest != null && !isChest(chest)) {
			chests.remove(chest);
			chest = null;
			if (job != Job.STORE) {
				cancel();
				return null;
			}
		}
		if (chest == null) return job == Job.STORE ? placeChest() : null;
		ServerLevel level = (ServerLevel) c.player.level();
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(chest)) > c.player.blockInteractionRange() - 0.6) {
			doing = "going to the chest";
			return c.walkTo(Vec3.atBottomCenterOf(chest));
		}
		c.hands.stop();
		c.hands.use(chest);
		c.acted = true;
		AbstractContainerMenu menu = c.player.containerMenu;
		if (menu == c.player.inventoryMenu || menu.slots.size() < 36) {             // it didn't open (something on top of it?)
			c.chatter("This chest won't open.", false);
			chests.remove(chest);
			cancel();
			return Action.IDLE;
		}
		int size = menu.slots.size() - 36;
		switch (job) {
			case STORE -> deposit(menu, size);
			case TAKE -> withdraw(menu, size);
			case LOOT -> loot(menu, size);
		}
		remember(chest, menu, size);
		c.player.closeContainer();
		Job was = job;
		job = null;
		if (was == Job.STORE) c.chatter(c.pick3("All put away.", "Stored my stuff in the chest.", "There, the chest has it."), false);
		if (was == Job.TAKE) {
			int got = c.items().getOrDefault(takeKey, 0) - had;
			c.chatter(got > 0 ? "Got " + got + " " + takeKey.replace('_', ' ') + " from the chest." : "The chest didn't have it after all.", false);
		}
		return Action.IDLE;
	}

	private void deposit(AbstractContainerMenu menu, int size) {
		Map<String, Integer> kept = new HashMap<>();
		int stored = 0;
		for (int i = size; i < menu.slots.size(); i++) {
			ItemStack s = menu.getSlot(i).getItem();
			if (s.isEmpty()) continue;
			String key = c.itemKey(s);
			int k = keep(key, s), have = kept.getOrDefault(key, 0);
			if (k == Integer.MAX_VALUE || have + s.getCount() <= k) {
				kept.merge(key, s.getCount(), Integer::sum);
				continue;
			}
			Compat.click(menu, i, true, c.player);                                  // shift-click: into the chest
			if (menu.getSlot(i).getItem().isEmpty()) stored++;
			else break;                                                          // the chest is full
		}
		c.journal("does", "put " + stored + " stacks in the chest at " + chest.toShortString());
	}

	private void withdraw(AbstractContainerMenu menu, int size) {
		int got = 0;
		for (int i = 0; i < size && got < takeAmount; i++) {
			ItemStack s = menu.getSlot(i).getItem();
			if (s.isEmpty() || !c.itemKey(s).equals(takeKey)) continue;
			got += s.getCount();
			Compat.click(menu, i, true, c.player);
		}
	}

	private static final Set<String> NOT_WORTH = Set.of("rotten_flesh", "poisonous_potato", "wheat_seeds", "beetroot_seeds", "stick", "bone",
			"string", "spider_eye", "dead_bush", "cobweb");

	private void loot(AbstractContainerMenu menu, int size) {
		int took = 0;
		StringBuilder what = new StringBuilder();
		for (int i = 0; i < size; i++) {
			ItemStack s = menu.getSlot(i).getItem();
			if (s.isEmpty()) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (NOT_WORTH.contains(n) && c.items().getOrDefault(n, 0) > 8) continue;
			if (took < 4) what.append(what.length() > 0 ? ", " : "").append(n.replace('_', ' '));
			Compat.click(menu, i, true, c.player);
			took++;
		}
		looted.add(chest);
		c.journal("does", "looted a chest at " + chest.toShortString() + ": " + what);
		if (took > 0) c.chatter("Loot! " + what + (took > 4 ? " and more." : "."), false);
	}

	/** What it saw in the chest (for later: "is there iron in my chest?"). */
	private void remember(BlockPos at, AbstractContainerMenu menu, int size) {
		if (job == Job.LOOT) return;
		Map<String, Integer> seen = new LinkedHashMap<>();
		int slots = 0;
		for (int i = 0; i < size; i++) {
			ItemStack s = menu.getSlot(i).getItem();
			if (s.isEmpty()) continue;
			slots++;
			seen.merge(c.itemKey(s), s.getCount(), Integer::sum);
		}
		seen.put("#slots", slots);
		chests.put(at.immutable(), seen);
	}

	/** No chest at home yet: it makes one (8 planks) and puts it down by its home. */
	private Action placeChest() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos home = homeSpot();
		if (home == null) {
			cancel();
			return null;
		}
		if (c.items().getOrDefault("chest", 0) == 0) {
			if (c.crafter.hasOrder()) return null;
			int wood = c.items().getOrDefault("log", 0) * 4 + planks();
			if (wood < 8) {
				c.chatter("I need wood for a chest first.", false);
				cancel();
				c.chores.gather("wood", 3);
				c.chores.own = true;
				return null;
			}
			doing = "making a chest";
			c.crafter.orderRecipe("chest", 1);
			return null;
		}
		if (c.player.blockPosition().distSqr(home) > 6 * 6) {
			doing = "taking a chest home";
			return c.walkTo(Vec3.atBottomCenterOf(home));
		}
		for (BlockPos q : BlockPos.betweenClosed(home.offset(-3, -1, -3), home.offset(3, 1, 3))) {
			BlockPos at = q.immutable();
			if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).isAir() || !level.getFluidState(at).isEmpty()) continue;
			if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP) || at.equals(c.player.blockPosition())
					|| at.equals(c.player.blockPosition().above())) continue;
			boolean doorway = false;                                              // not in front of a door
			for (Direction d : Direction.Plane.HORIZONTAL) doorway |= level.getBlockState(at.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
			if (doorway) continue;
			if (c.hands.placeSeen(at, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("chest"), at.below(), Direction.UP, -1)) {
				chest = at;
				chests.put(at, new LinkedHashMap<>());
				Tribe t = c.tribe();
				if (t != null && t.center != null && at.distSqr(t.center) < 16 * 16) t.storage.add(at);
				c.places.remember("chest", at);
				c.acted = true;
				return Action.PLACE;
			}
		}
		c.chatter("There's no room for a chest here.", false);
		cancel();
		return null;
	}

	private int planks() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().endsWith("_planks")) n += inv.getItem(i).getCount();
		}
		return n;
	}

	// ------------------------------------------------------------------------------ loot
	/**
	 * Out in the world: a chest nobody has opened yet (it still has its loot to come) that it can see within 12
	 * blocks. It goes and loots it, like a player finding a dungeon. True if it's off to one.
	 */
	boolean spotLoot() {
		if (c.player == null || now() < nextLookForLoot || busy() || !c.mod.config.loot) return false;
		nextLookForLoot = now() + 60;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		Vec3 eye = c.player.getEyePosition();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				var chunk = level.getChunkSource().getChunkNow((feet.getX() >> 4) + dx, (feet.getZ() >> 4) + dz);
				if (chunk == null) continue;
				for (var e : chunk.getBlockEntities().entrySet()) {
					if (!(e.getValue() instanceof RandomizableContainerBlockEntity box) || box.getLootTable() == null) continue;
					BlockPos at = e.getKey();
					if (looted.contains(at) || at.distSqr(feet) > 12 * 12) continue;
					var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(at), net.minecraft.world.level.ClipContext.Block.COLLIDER,
							net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));
					if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(at)) continue;   // it can't see it
					begin(Job.LOOT);
					chest = at.immutable();
					until = now() + 20 * 40;
					c.chatter(c.pick3("Ooh, a chest!", "A chest! Let's see what's inside.", "Treasure?"), false);
					return true;
				}
			}
		}
		return false;
	}

	com.google.gson.JsonObject toJson() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		for (var e : chests.entrySet()) {
			com.google.gson.JsonObject m = new com.google.gson.JsonObject();
			e.getValue().forEach(m::addProperty);
			o.add(e.getKey().getX() + "," + e.getKey().getY() + "," + e.getKey().getZ(), m);
		}
		return o;
	}

	void load(com.google.gson.JsonObject o) {
		try {
			for (var e : o.entrySet()) {
				String[] xyz = e.getKey().split(",");
				Map<String, Integer> m = new LinkedHashMap<>();
				for (var f : e.getValue().getAsJsonObject().entrySet()) m.put(f.getKey(), f.getValue().getAsInt());
				chests.put(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), m);
			}
		} catch (RuntimeException e) {
			XenMod.LOG.warn("{} couldn't recall its chests: {}", c.name, e.toString());
		}
	}

	String describe() {
		if (chests.isEmpty()) return "";
		Map<String, Integer> all = new LinkedHashMap<>();
		for (var m : chests.values()) m.forEach((k, v) -> { if (!k.startsWith("#")) all.merge(k, v, Integer::sum); });
		StringBuilder sb = new StringBuilder("In your chests at home: ");
		int n = 0;
		for (var e : all.entrySet()) {
			if (n++ >= 6) break;
			sb.append(n > 1 ? ", " : "").append(e.getValue()).append(' ').append(e.getKey().replace('_', ' '));
		}
		return sb.append('.').toString();
	}
}
