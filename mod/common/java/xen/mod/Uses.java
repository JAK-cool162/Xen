package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Items used the way a player uses them: right-click with the item in hand, on the block it looks at.
 * <ul>
 *   <li><b>Asked</b>: "light the tnt", "burn that", "set fire to that", "use the flint and steel", "bone meal that",
 *   "put that out": the block you're looking at (or TNT it can see), with the item for it (flint and steel or a fire
 *   charge to light, bone meal to grow, a water bucket to put a fire out, or the item you named).</li>
 *   <li><b>Griefing</b> (the "grief" setting): "off"; "revenge" (the default: someone who hurt it badly and whose house
 *   it knows may come back to find it on fire, if it's the kind to hold a grudge: wrathful, envious or aggressive);
 *   "chaos" (a mean one may also burn a stranger's house, just because). Never its owner's, its tribe's or a friend's.</li>
 * </ul>
 */
final class Uses {
	private final Companion c;
	/** What it's doing with what, and on which block, until when. */
	private BlockPos target;
	private String item = "";
	private String doing = "";
	private long until;
	private boolean grief;
	private UUID victim;
	/** Lit TNT it's getting away from, until when. */
	private BlockPos runFrom;
	private long runUntil;
	/** Houses it saw a player at (a house it knows, that player close to it): whose it probably is. */
	private final Map<UUID, BlockPos> homes = new HashMap<>();
	/** When it last took out a grudge (not every day). */
	private long griefedAt = -1_000_000;
	private long lookedAt;

	Uses(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	boolean busy() {
		return target != null;
	}

	void cancel() {
		target = null;
		grief = false;
	}

	// ------------------------------------------------------------------------------ asked
	/** "Light the tnt", "burn that", "use the X": the plan ("You will ..."), or why not. */
	String ask(ServerPlayer from, String words) {
		var p = c.player;
		ServerLevel level = (ServerLevel) p.level();
		String want;
		if (words.matches("(?s).*\\b(light|ignite|burn|set fire|set .* on fire|blow up|fire)\\b.*")) want = "fire";
		else if (words.matches("(?s).*\\b(bone ?meal|grow|fertili[sz]e)\\b.*")) want = "bone_meal";
		else if (words.matches("(?s).*\\b(put (it|that|the fire) out|put out|extinguish|water)\\b.*")) want = "water_bucket";
		else want = named(words);
		if (want == null) return "You don't have that, so you can't use it.";
		BlockPos at = null;
		if (want.equals("fire") && words.matches("(?s).*\\btnt\\b.*")) at = nearest(level, "tnt", 16);   // the TNT it can see
		if (at == null) at = lookedAt(from);                                     // what they're looking at
		if (at == null) return "You don't know which block they mean: they should look at it and ask again.";
		if (want.equals("water_bucket") && !level.getBlockState(at.above()).getBlock().getDescriptionId().contains("fire")
				&& !level.getBlockState(at).getBlock().getDescriptionId().contains("fire")) {
			return "You don't see a fire there to put out.";
		}
		if (slotFor(want) < 0) {
			if (want.equals("fire") && canMakeFlintAndSteel()) {
				c.crafter.orderRecipe("flint_and_steel", 1);
				return "You will make a flint and steel first (you have iron and flint), then use it on the " + blockName(level, at) + ".";
			}
			return "You can't, because you have no " + (want.equals("fire") ? "flint and steel or fire charge" : want.replace('_', ' ')) + ".";
		}
		start(at, want, false, null);
		return "You will use your " + itemName(want) + " on the " + blockName(level, at) + ".";
	}

	private void start(BlockPos at, String what, boolean isGrief, UUID whose) {
		target = at.immutable();
		item = what;
		grief = isGrief;
		victim = whose;
		until = now() + 20 * 60;
		doing = (isGrief ? "getting back at someone: " : "") + "using " + itemName(what);
	}

	// ------------------------------------------------------------------------------ each decision
	Action next() {
		if (c.player == null) return null;
		var p = c.player;
		if (runFrom != null) {                                                 // lit TNT: away from it, like anyone would
			if (now() > runUntil || p.position().distanceTo(Vec3.atCenterOf(runFrom)) > 9) {
				runFrom = null;
				return null;
			}
			Vec3 away = p.position().subtract(Vec3.atCenterOf(runFrom)).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
			c.goals.instant = "running from the TNT";
			c.acted = true;
			c.run(true);
			return c.walkTo(p.position().add(away.normalize().scale(10)));
		}
		if (target == null) return null;
		if (now() > until || c.fightingNow()) {
			cancel();
			return null;
		}
		if (item.equals("fire") && slotFor("fire") < 0 && c.crafter.hasOrder()) return null;   // (it's making the flint and steel)
		int slot = slotFor(item);
		if (slot < 0) {
			cancel();
			c.chatter("I don't have it anymore.", false);
			return null;
		}
		c.goals.instant = doing;
		c.acted = true;
		if (!c.hands.canClick(target)) return c.walkTo(Vec3.atBottomCenterOf(target));   // (in reach and in sight: not through a wall)
		p.getInventory().setSelectedSlot(slot);
		c.hands.use(target);                                                   // right-click it (fire goes on top)
		BlockPos done = target;
		boolean wasGrief = grief;
		cancel();
		if (item.equals("fire") && (BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(done).getBlock()).getPath().equals("tnt") || p.level().getBlockState(done).isAir())) {
			runFrom = done;                                                    // (primed TNT is gone from its spot: it runs)
			runUntil = now() + 100;
			c.chatter(c.pick3("Run!", "It's gonna blow!", "Fire in the hole!"), true);
		}
		if (wasGrief) {
			griefedAt = now();
			c.journal("does", "set fire to someone's house at " + done.toShortString() + " (a grudge)");
			c.chatter(c.pick3("That's for what you did.", "Payback.", "Should've left me alone."), true);
			c.lastPickedFight = now();
		}
		return Action.PLACE;
	}

	// ------------------------------------------------------------------------------ griefing
	/**
	 * Every couple of seconds: whose house is whose (a player seen right by a house it knows), and, when the setting and
	 * its nature allow, a grudge taken out on it: fire on its wooden walls, with nobody around to stop it.
	 */
	Action grudge() {
		var p = c.player;
		if (p == null || target != null || c.inArena || c.minion) return null;
		long now = now();
		if (now - lookedAt < 40) return null;
		lookedAt = now;
		String mode = c.mod.config.grief == null ? "revenge" : c.mod.config.grief;
		ServerLevel level = (ServerLevel) p.level();
		BlockPos house = c.places.get("house");
		for (ServerPlayer o : level.players()) {                               // whose house is that? whoever hangs around it
			if (o == p || house == null || o.distanceTo(p) > 48 || !house.closerThan(o.blockPosition(), 16)) continue;
			homes.put(o.getUUID(), house);
		}
		if (mode.equals("off") || now - griefedAt < 24000 || c.mode == Companion.Mode.FOLLOW || c.chores.busy()) return null;
		var who = c.personality;
		boolean mean = who.aggressive() || who.sin(xen.mod.core.Sins.WRATH) >= 0.6f || who.sin(xen.mod.core.Sins.ENVY) >= 0.6f;
		if (!mean) return null;
		for (var e : homes.entrySet()) {
			UUID u = e.getKey();
			if (u.equals(c.owner)) continue;
			float t = c.trust(u);
			boolean grudge = t <= -0.5f;
			boolean chaos = mode.equals("chaos") && t < 0.1f && !c.known.contains(u) && c.random().nextFloat() < 0.05f;
			if (!grudge && !chaos || !mode.equals("chaos") && !grudge) continue;
			BlockPos home = e.getValue();
			if (c.goals.home != null && c.goals.home.closerThan(home, 48)) continue;   // (not by its own home)
			Tribe t2 = c.tribe();
			if (t2 != null && t2.center != null && t2.center.closerThan(home, 48)) continue;   // (nor its village)
			ServerPlayer them = c.server.getPlayerList().getPlayer(u);
			if (them != null && them.level() == level && them.blockPosition().closerThan(home, 24)) continue;   // they're home: not now
			ServerPlayer owner = c.owner == null ? null : c.server.getPlayerList().getPlayer(c.owner);
			if (owner != null && owner.level() == level && owner.blockPosition().closerThan(home, 48)) continue;   // (not with its owner watching)
			if (slotFor("fire") < 0) {
				if (canMakeFlintAndSteel() && !c.crafter.hasOrder()) c.crafter.orderRecipe("flint_and_steel", 1);
				continue;
			}
			BlockPos wall = flammable(level, home);
			if (wall == null) continue;
			start(wall, "fire", true, u);
			c.journal("thinks", "a grudge against " + (them != null ? them.getName().getString() : "someone") + ": their house at " + home.toShortString());
			return next();
		}
		return null;
	}

	/** A wooden (or wool) block of the house with air on top: where fire takes. */
	private static BlockPos flammable(ServerLevel level, BlockPos near) {
		for (int r = 0; r <= 6; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					for (int dy = -2; dy <= 3; dy++) {
						BlockPos b = near.offset(dx, dy, dz);
						String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(b).getBlock()).getPath();
						if ((id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wool") || id.equals("bookshelf") || id.endsWith("_stairs") && id.contains("oak"))
								&& level.getBlockState(b.above()).isAir()) return b;
					}
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------ what and where
	/** The block a player is looking at (their crosshair, 20 blocks), or null. */
	private static BlockPos lookedAt(ServerPlayer from) {
		HitResult hit = from.pick(20, 1f, false);
		return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
	}

	private BlockPos nearest(ServerLevel level, String id, int r) {
		BlockPos best = null;
		BlockPos at = c.player.blockPosition();
		for (BlockPos b : BlockPos.betweenClosed(at.offset(-r, -4, -r), at.offset(r, 4, r))) {
			if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(b).getBlock()).getPath().equals(id)) continue;
			if (best == null || b.distSqr(at) < best.distSqr(at)) best = b.immutable();
		}
		return best;
	}

	/** "use the shears" -> "shears" (an item it carries whose name is in what they said), or null. */
	private String named(String words) {
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (words.contains(id.replace('_', ' ')) || words.contains(id)) return id;
		}
		return null;
	}

	/** The hotbar slot with it (moved there if it's in the bag), or -1. "fire": flint and steel, or a fire charge. */
	private int slotFor(String what) {
		var inv = c.player.getInventory();
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < 36; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty()) continue;
				String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
				boolean ok = what.equals("fire") ? (pass == 0 ? id.equals("flint_and_steel") : id.equals("fire_charge")) : id.equals(what);
				if (!ok) continue;
				if (i < 9) return i;
				ItemStack h = inv.getItem(8);                                   // dragged into the hotbar
				inv.setItem(8, s);
				inv.setItem(i, h);
				return 8;
			}
		}
		return -1;
	}

	private boolean canMakeFlintAndSteel() {
		var items = c.items();
		return items.getOrDefault("flint", 0) > 0 && items.getOrDefault("iron_ingot", 0) > 0;
	}

	private static String itemName(String what) {
		return what.equals("fire") ? "flint and steel" : what.replace('_', ' ');
	}

	private static String blockName(ServerLevel level, BlockPos at) {
		BlockState st = level.getBlockState(at);
		return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().replace('_', ' ');
	}
}
