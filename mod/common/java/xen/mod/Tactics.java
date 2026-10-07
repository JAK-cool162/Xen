package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import xen.mod.core.Action;

/**
 * What players do when the world turns on them, from a player's own answers (each one a {@link Knowledge} mechanic: a
 * Xen that knows it does it, one that doesn't can be told or learn it from a Xen who knows):
 * <ul>
 *   <li>water flooding into its tunnel: a block on it, then on another way ("block_water");</li>
 *   <li>lava next to it underground: covered with blocks ("cover_lava");</li>
 *   <li>stuck in powder snow: it mines its way out ("powder_snow");</li>
 *   <li>a spawner: torches on it (no monsters), and it's remembered for a mob farm, never broken ("spawner_torch").</li>
 * </ul>
 * Each looks at the blocks next to it only (what it would feel or see), every half second, and does one thing at a time.
 */
final class Tactics {
	private final Companion c;
	private long nextLook, nextSpawner;

	Tactics(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** The next thing to do about water, lava, powder snow or a spawner close by, or null. */
	Action next() {
		var p = c.player;
		if (p == null || p.isCreative() || c.inArena) return null;
		long now = now();
		ServerLevel level = (ServerLevel) p.level();
		BlockPos feet = p.blockPosition();
		if (p.isInPowderSnow && c.knowledge.knows("powder_snow")) {          // (every tick: it's sinking)
			for (BlockPos b : new BlockPos[] {feet.above(), feet}) {
				if (level.getBlockState(b).is(Blocks.POWDER_SNOW) && c.hands.mine(b)) {
					c.goals.instant = "mining its way out of the powder snow";
					c.acted = true;
					return Action.MINE;
				}
			}
		}
		if (now < nextLook) return null;
		nextLook = now + 10;
		if (p.isUnderWater() || p.isInLava()) return null;
		boolean under = !level.canSeeSky(feet.above());
		if (under && c.knowledge.knows("block_water")) {
			BlockPos w = flowingIn(level, feet);
			if (w != null && c.hands.placeAt(w, "any")) {                    // a block in the way of the water
				c.goals.instant = "blocking the water flooding in";
				c.journal("does", "blocked the water flowing into its tunnel");
				c.walker.stop();                                              // (and another way from here)
				c.acted = true;
				return Action.PLACE;
			}
		}
		if (under && c.knowledge.knows("cover_lava")) {
			BlockPos l = lavaBeside(level, feet);
			if (l != null && c.hands.placeAt(l, "any")) {
				c.goals.instant = "covering the lava";
				c.journal("does", "covered lava next to it with a block");
				c.acted = true;
				return Action.PLACE;
			}
		}
		if (now >= nextSpawner && c.knowledge.knows("spawner_torch")) {
			nextSpawner = now + 40;
			Action t = lightSpawner(level, feet);
			if (t != null) return t;
		}
		return null;
	}

	/** Water coming in beside its feet or head, or from above (a block it can put something in), or null. */
	private static BlockPos flowingIn(ServerLevel level, BlockPos feet) {
		for (BlockPos at : new BlockPos[] {feet, feet.above()}) {
			for (Direction d : Direction.values()) {
				if (d == Direction.DOWN) continue;
				BlockPos q = at.relative(d);
				if (q.equals(feet) || q.equals(feet.above())) continue;
				var f = level.getFluidState(q);
				if (f.is(FluidTags.WATER) && level.getBlockState(q).canBeReplaced()) return q;
			}
		}
		return null;
	}

	/** Lava beside it or under the blocks next to it (one step and it's in), or null. */
	private static BlockPos lavaBeside(ServerLevel level, BlockPos feet) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (BlockPos q : new BlockPos[] {feet.relative(d), feet.relative(d).below(), feet.relative(d).above()}) {
				if (level.getFluidState(q).is(FluidTags.LAVA) && level.getBlockState(q).canBeReplaced()) return q;
			}
		}
		BlockPos below = feet.below();
		return level.getFluidState(below).is(FluidTags.LAVA) ? below : null;
	}

	/** A spawner within reach with no light on it: a torch on top (it stays: a mob farm later), remembered. */
	private Action lightSpawner(ServerLevel level, BlockPos feet) {
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 3, 4))) {
			if (!level.getBlockState(q).is(Blocks.SPAWNER)) continue;
			BlockPos spawner = q.immutable();
			if (c.places.get("spawner") == null || !c.places.get("spawner").closerThan(spawner, 8)) {
				c.places.remember("spawner", spawner);
				c.journal("notes", "a spawner at " + spawner.toShortString() + ": a mob farm, some day");
			}
			BlockPos top = spawner.above();
			if (!level.getBlockState(top).canBeReplaced() || level.getBlockState(top).is(Blocks.TORCH)) return null;
			if (!c.hands.carries("torch")) return null;
			if (c.hands.placeItem(top, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("torch"), spawner, Direction.UP, -1)) {
				c.goals.instant = "putting a torch on the spawner";
				c.chatter("A spawner! A torch on it, and I'll make a mob farm here one day.", false);
				c.acted = true;
				return Action.PLACE;
			}
			return null;
		}
		return null;
	}
}
