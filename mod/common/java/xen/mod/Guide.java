package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import xen.mod.core.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Xen's guide: a miner's eye for where the ore is. It knows ore that a player could have spotted: ore
 * showing to the air (a cave wall, a ravine, a tunnel) or one block behind it; ore deep in solid rock it acts as if
 * nobody could know of. It knows where the nearest such ore really is (the server knows the world) and works out a
 * way there, a staircase down toward it and then straight in, dry all the way (no water, no lava on it or by it), and
 * the Xen goes, every time (no dice), as if it had just noticed it.
 * <p>Its mind isn't told any of this: it only knows that it had a feeling and followed it.
 */
final class Guide {
	private final Companion c;
	/** The ore it's leading to now (null: none), what kind, and the way there. */
	BlockPos target;
	private String kind;
	private double closest;
	private long closerAt, since;
	/** How much it trusts the guide, 0 to 1 (learned). Negative: not set yet (from its nature). */
	private float faith = -1;
	int offered, followed, paid, ignored;

	Guide(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	float faith() {
		if (faith < 0) {
			var p = c.personality;
			faith = Math.max(0.2f, Math.min(0.95f, 0.6f + 0.3f * p.curiosity - 0.25f * p.sin(xen.mod.core.Sins.PRIDE)));
		}
		return faith;
	}

	boolean leading() {
		return target != null;
	}

	/**
	 * The nearest ore of this kind it can see a dry way to (within flat blocks round and deep down): offered, and the
	 * Xen takes it or not. True if it follows.
	 */
	boolean offer(String what, int flat, int deep) {
		if (c.player == null || c.mod.config.luck <= 0 || c.minion || target != null) return false;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos from = c.player.blockPosition();
		for (BlockPos ore : c.hunch.oresNear(what, from, flat, deep, 6)) {
			List<BlockPos> way = route(level, from, ore);
			if (way == null) {
				c.hunch.taken(ore);                                                  // (through water or lava: not that one)
				continue;
			}
			offered++;
			target = ore;
			kind = what;
			closest = Double.MAX_VALUE;
			closerAt = since = now();
			followed++;
			c.hunch.steered = what + " (its guide)";
			c.hunch.steeredAt = now();
			c.hunch.times++;
			c.journal("thinks", String.format(Locale.ROOT, "has a feeling there's %s at about %d %d %d and follows it: %d blocks of digging",
					what, ore.getX(), ore.getY(), ore.getZ(), way.size()));
			XenMod.LOG.info("{} follows its guide to {} at {} ({} steps)", c.name, what, ore.toShortString(), way.size());
			return true;
		}
		return false;
	}

	/** A feeling there's a cave over there (a big one: quicker iron than any mine): it goes and has a look, every time. */
	boolean follows(String what, BlockPos at) {
		offered++;
		followed++;
		c.journal("thinks", String.format(Locale.ROOT, "has a feeling there's %s at about %d %d %d and wanders that way to look", what, at.getX(), at.getY(), at.getZ()));
		return true;
	}

	/** It followed a feeling about a cave and found one there (or didn't): its trust learns, as with ore. */
	void caveFeeling(boolean right) {
		faith = Math.max(0.1f, Math.min(0.95f, faith() + (right ? 0.05f : -0.06f)));
		if (right) paid++;
	}

	/**
	 * A way to dig there, like Baritone: a step toward it at a time (along the longer way), one down with each step
	 * while it's below (a staircase, never straight down; close over it, round it in a spiral), then straight in to
	 * it. Null if it'd go through water or lava, or by them.
	 */
	private static List<BlockPos> route(ServerLevel level, BlockPos from, BlockPos ore) {
		List<BlockPos> way = new ArrayList<>();
		int x = from.getX(), y = from.getY(), z = from.getZ(), turn = 0;
		int[][] ring = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
		for (int guard = 0; guard < 240; guard++) {
			int dx = ore.getX() - x, dz = ore.getZ() - z, dy = ore.getY() - y;
			int flat = Math.abs(dx) + Math.abs(dz);
			if (flat <= 1 && dy >= -1 && dy <= 1) return way;                        // right by it: it mines it from here
			if (flat <= 2 && dy < -1) {                                               // over it, still above: round it, down a step each time
				int[] r = ring[turn++ / 2 % 4];
				x += r[0];
				z += r[1];
			} else if (Math.abs(dx) >= Math.abs(dz)) x += Integer.signum(dx);
			else z += Integer.signum(dz);
			if (dy < 0) y--;
			else if (dy > 1) y++;
			BlockPos p = new BlockPos(x, y, z);
			if (!safe(level, p)) return null;
			way.add(p);
		}
		return null;
	}

	/** Room to dig through there (no bedrock), and no water or lava in it or next to it (lava two blocks off). */
	private static boolean safe(ServerLevel level, BlockPos feet) {
		if (!level.isLoaded(feet)) return false;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 2, 1)))
			if (!level.getFluidState(q).isEmpty() || level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.BEDROCK)) return false;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 3, 2)))
			if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return false;
		return true;
	}

	/**
	 * Still leading? (Mined: it paid off. Thirty seconds and no closer: not this one.) Its mining does the digging:
	 * its staircase toward it while it's far, straight to it once it's close.
	 */
	boolean check() {
		if (target == null || c.player == null) return false;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		var st = level.getBlockState(target);
		int cat = WorldSenses.category(level, target, st);
		if (st.isAir() || cat == Blocks.AIR || cat == Blocks.STONE) {               // mined (by it, right there: it paid off)
			done(feet.closerThan(target, 6), "mined it");
			return false;
		}
		double d = Math.sqrt(feet.distSqr(target));
		if (d < closest - 0.5) {
			closest = d;
			closerAt = now();
		} else if (now() - closerAt > 20 * (kind.startsWith("diamond") ? 90 : 30)) {   // (diamonds: a long way down through the rock, a staircase at a time)
			done(false, kind.startsWith("diamond") ? "no closer in 90 s" : "no closer in 30 s");
			return false;
		}
		return true;
	}

	/** Which way (of the four) its staircase goes toward it. */
	net.minecraft.core.Direction way() {
		BlockPos feet = c.player.blockPosition();
		int dx = target.getX() - feet.getX(), dz = target.getZ() - feet.getZ();
		return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
				: (dz > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
	}

	/** What it's doing, for its status. */
	String doing(int got, int want) {
		return String.format(Locale.ROOT, "following its feeling to the %s (%d of %d so far)", kind, got, want);
	}

	/** Following it is over: did it pay off? Its trust in it goes with that. */
	void done(boolean worked, String why) {
		if (target == null) return;
		c.hunch.taken(target);
		if (worked) {
			paid++;
			faith = Math.min(0.97f, faith() + 0.08f * (1 - faith()));
		} else faith = Math.max(0.1f, faith() - 0.12f * faith());
		c.journal("learns", String.format(Locale.ROOT, "following its feeling to the %s: %s (%s, %.1f min); trusts its feelings %.0f%%", kind, worked ? "paid off" : "didn't",
				why, (now() - since) / 1200f, 100 * faith()));
		target = null;
	}

	/** Not this time (the trip ended, a fight, the night...). */
	void drop() {
		target = null;
	}

	String describe() {
		return offered == 0 ? "" : String.format(Locale.ROOT, "its feelings: followed %d of %d, %d paid off (trust %.0f%%)", followed, offered, paid, 100 * faith());
	}
}
