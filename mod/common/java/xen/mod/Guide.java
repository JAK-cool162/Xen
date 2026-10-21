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

	/** Leading it to this kind of ore now? */
	boolean leadingTo(String what) {
		return target != null && what.equals(kind);
	}

	/**
	 * The nearest ore of this kind it can see a dry way to (within flat blocks round and deep down): offered, and the
	 * Xen takes it or not. True if it follows.
	 */
	boolean offer(String what, int flat, int deep) {
		if (c.player == null || c.mod.config.luck <= 0 || c.minion || target != null) return false;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos from = c.player.blockPosition();
		leads.clear();
		for (String k : KINDS) {                                                 // every ore it needs, how much it needs it (what it came for: a little more)
			int n = need(k) + (k.equals(what) ? 1 : 0);
			if (n <= 0) continue;
			for (BlockPos ore : c.hunch.oresNear(k, from, flat, deep, 6)) {
				List<BlockPos> way = route(level, from, ore);
				if (way == null) {
					c.hunch.taken(ore);                                              // (through water or lava: not that one)
					continue;
				}
				leads.add(new Lead(k, ore, n, way.size()));
				break;
			}
		}
		leads.sort((a, b) -> a.need() != b.need() ? b.need() - a.need() : a.steps() - b.steps());   // the most necessary on top, the nearest of those
		return next();
	}

	/** The ore it can go for, one of each kind, the most necessary first (the top one is the one it follows). */
	private final List<Lead> leads = new ArrayList<>();
	private record Lead(String kind, BlockPos at, int need, int steps) {}
	private static final String[] KINDS = {"coal", "iron", "lapis", "diamond"};

	/**
	 * How much it needs this ore now: 3 it can't go on without it (coal for the raw iron it has, iron for its first iron
	 * pickaxe), 2 the main goal (diamonds for a pickaxe and an enchanting table, coal for torches), 1 good to have
	 * (iron for armor, lapis for enchanting, coal to spare), 0 not at all.
	 */
	private int need(String k) {
		var items = c.items();
		int tier = c.crafter.pickTier();
		int coal = items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0);
		int iron = items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0);
		return switch (k) {
			case "coal" -> coal < 3 && items.getOrDefault("raw_iron", 0) > 0 ? 3 : coal < 3 && items.getOrDefault("torch", 0) < 8 ? 2 : coal < 8 ? 1 : 0;
			case "iron" -> tier < 3 ? 3 : iron < 24 && !c.crafter.has("_chestplate") ? 1 : 0;
			case "diamond" -> tier >= 3 && items.getOrDefault("diamond", 0) < 5 ? 2 : 0;
			case "lapis" -> tier >= 3 && items.getOrDefault("lapis_lazuli", 0) < 3 ? 1 : 0;
			default -> 0;
		};
	}

	/** On to the next lead on its stack that's still there (true), or none left. */
	private boolean next() {
		ServerLevel level = (ServerLevel) c.player.level();
		while (!leads.isEmpty()) {
			Lead l = leads.remove(0);
			var st = level.getBlockState(l.at());
			int cat = WorldSenses.category(level, l.at(), st);
			if (st.isAir() || cat == Blocks.AIR || cat == Blocks.STONE) continue;  // (mined meanwhile)
			offered++;
			followed++;
			target = l.at();
			kind = l.kind();
			closest = Double.MAX_VALUE;
			closerAt = since = now();
			c.hunch.steered = kind + " (its guide)";
			c.hunch.steeredAt = now();
			c.hunch.times++;
			c.journal("thinks", String.format(Locale.ROOT, "has a feeling there's %s at about %d %d %d and follows it: %d blocks of digging (%d more in mind)",
					kind, target.getX(), target.getY(), target.getZ(), l.steps(), leads.size()));
			XenMod.LOG.info("{} follows its guide to {} at {} ({} steps, need {}, {} more on its stack)", c.name, kind, target.toShortString(), l.steps(), l.need(), leads.size());
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
		for (int guard = 0; guard < 480; guard++) {
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
		next();                                                                  // (the next one on its stack, if any)
	}

	/** Not this time (the trip ended, a fight, the night...). */
	void drop() {
		target = null;
		leads.clear();
	}

	String describe() {
		return offered == 0 ? "" : String.format(Locale.ROOT, "its feelings: followed %d of %d, %d paid off (trust %.0f%%)", followed, offered, paid, 100 * faith());
	}
}
