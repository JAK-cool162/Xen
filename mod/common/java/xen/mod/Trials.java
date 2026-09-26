package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * Trial chambers (1.21 and on), the way players run them: it goes up to a trial spawner (that wakes it up), fights
 * what comes out (breezes too: arrows bounce off them, so up close with a sword), picks up the trial keys the spawner
 * gives when it's beaten, and opens the vaults with them (one key, one vault, each vault once), picking up the loot.
 * Ominous ones it leaves alone. It remembers where the chamber is.
 */
final class Trials {
	private final Companion c;
	boolean on;
	private BlockPos spawner, vault;
	private long since, lastLine;
	private final java.util.Set<BlockPos> opened = new java.util.HashSet<>(), beaten = new java.util.HashSet<>();
	private long nextFind;

	Trials(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private static String id(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
	}

	/** A block state's property by its name ("trial_spawner_state", "vault_state", "ominous"), as text. */
	static String prop(BlockState s, String name) {
		for (var p : s.getProperties()) if (p.getName().equals(name)) return String.valueOf(s.getValue(p)).toLowerCase(java.util.Locale.ROOT);
		return "";
	}

	private int keys() {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().equals("trial_key")) n += inv.getItem(i).getCount();
		return n;
	}

	private void line(String s) {
		if (now() - lastLine < 400) return;
		lastLine = now();
		c.chatter(s, true);
	}

	/** Does it see a trial chamber (its spawners or vaults) near? Remembers it. */
	boolean spot() {
		if (c.player == null || !Places.dim(c.player.level()).equals("overworld")) return false;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-12, -5, -12), feet.offset(12, 5, 12))) {
			String n = id(level.getBlockState(q));
			if (!n.equals("trial_spawner") && !n.equals("vault")) continue;
			if (c.places.get("trial chamber") == null) {
				c.places.remember("trial chamber", q);
				c.chatter("A trial chamber! There's loot in those vaults.", true);
			}
			return true;
		}
		return false;
	}

	/** Ready for it? Iron gear and food. */
	boolean ready() {
		var items = c.items();
		return c.crafter.pickTier() >= 3 && items.getOrDefault("food", 0) >= 4 && c.player.getHealth() >= 16;
	}

	String start() {
		if (c.places.get("trial chamber") == null) return "You don't know where a trial chamber is.";
		on = true;
		since = now();
		return "You will take on the trial chamber: beat its spawners, get the keys, and open the vaults.";
	}

	Action next() {
		if (!on || c.player.isCreative()) return null;
		if (c.player.getHealth() < 8) {
			line("Too dangerous, I'm hurt. Later.");
			on = false;
			return null;
		}
		if (now() - since > 20 * 60 * 10) {
			on = false;
			line("That's enough trials for today.");
			return null;
		}
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos home = c.places.get("trial chamber");
		if (home == null) {
			on = false;
			return null;
		}
		// keys: to a vault it hasn't opened
		if (keys() > 0) c.knowledge.learn("trial_keys", Knowledge.How.SEEN);        // (a key, and vaults around: what else would it be for)
		if (keys() > 0 && c.knowledge.knows("trial_keys")) {
			if ((vault == null || opened.contains(vault)) && now() >= nextFind) {
				nextFind = now() + 40;
				vault = find(level, "vault", home, 32);
			}
			if (vault != null) {
				if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(vault)) > c.player.blockInteractionRange() - 0.7) {
					c.goals.instant = "taking a key to a vault";
					return c.walkTo(Vec3.atBottomCenterOf(vault));
				}
				c.goals.instant = "opening a vault";
				c.hands.useWith(vault, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("trial_key"));
				opened.add(vault);
				c.chatter(c.pick3("Let's see what's in the vault!", "Key in... loot out!", "Vault's opening!"), false);
				c.acted = true;
				return Action.PLACE;
			}
		}
		// a spawner to beat
		if ((spawner == null || beaten.contains(spawner)) && now() >= nextFind) {
			nextFind = now() + 40;
			spawner = find(level, "trial_spawner", home, 32);
			if (spawner == null) {
				line(opened.isEmpty() ? "No more spawners here." : "The trial chamber is done!");
				on = false;
				return null;
			}
		}
		if (spawner == null) return Action.IDLE;
		String state = prop(level.getBlockState(spawner), "trial_spawner_state");
		if (state.contains("cooldown")) {                                             // beaten (it's resting): the next one
			beaten.add(spawner);
			spawner = null;
			return null;
		}
		c.goals.instant = state.contains("active") ? "fighting the trial spawner's mobs" : "waking up a trial spawner";
		if (c.player.blockPosition().distSqr(spawner) > 6 * 6) return c.walkTo(Vec3.atBottomCenterOf(spawner.above()));
		return Action.IDLE;                                                            // near it: what comes out gets fought (its instincts)
	}

	/** The nearest one of these (not ominous, not done yet) around the chamber. */
	private BlockPos find(ServerLevel level, String what, BlockPos around, int r) {
		BlockPos best = null, feet = c.player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(around.offset(-r, -10, -r), around.offset(r, 10, r))) {
			BlockState s = level.getBlockState(q);
			if (!id(s).equals(what) || prop(s, "ominous").equals("true") || opened.contains(q) || beaten.contains(q)) continue;
			if (what.equals("vault") && !prop(s, "vault_state").contains("active")) continue;
			if (best == null || q.distSqr(feet) < best.distSqr(feet)) best = q.immutable();
		}
		return best;
	}

	String describe() {
		return on ? "You are taking on a trial chamber (" + keys() + " trial keys)." : "";
	}
}
