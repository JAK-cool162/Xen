package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * An automatic smelter (chests, hoppers, furnaces; no redstone): what goes in its top barrel drops through a hopper into
 * the furnace, fuel from a barrel at the back through a hopper into its side, and what it makes through a hopper under
 * it into a barrel in front. A Xen drops off its raw iron (and logs for charcoal) and goes on with its day, and takes
 * the ingots out the next time it's by. No waiting at the furnace.
 *
 * <p>Its design grows with what it learned: the top hopper always; the output and the fuel hoppers once it has seen
 * them in a schematic (a hopper under a furnace, one beside it) or has the iron to spare. And any auto-smelter of that
 * kind is found by looking at it (one it built from a schematic, one a player built): {@link #scan}.
 */
final class Smelter {
	private Smelter() {}

	/** One furnace and what feeds it: where raw things go in, where fuel goes in, where what it made comes out (null: the furnace itself). */
	record Unit(BlockPos furnace, BlockPos input, BlockPos fuel, BlockPos output) {}

	private static boolean container(BlockState s) {
		return s.is(Blocks.CHEST) || s.is(Blocks.BARREL) || s.is(Blocks.TRAPPED_CHEST);
	}

	private static boolean furnace(BlockState s) {
		return s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER);
	}

	/** The auto-furnaces within r of a spot: a furnace with a hopper over it pointing down, a container over that hopper. */
	static List<Unit> scan(ServerLevel level, BlockPos near, int r) {
		List<Unit> out = new ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(near.offset(-r, -r, -r), near.offset(r, r, r))) {
			if (!level.isLoaded(q) || !furnace(level.getBlockState(q))) continue;
			BlockPos f = q.immutable();
			BlockState top = level.getBlockState(f.above());
			if (!top.is(Blocks.HOPPER) || top.getValue(HopperBlock.FACING) != Direction.DOWN) continue;
			BlockPos in = container(level.getBlockState(f.above(2))) ? f.above(2) : null;
			if (in == null) continue;
			BlockPos fuel = null, output = null;
			for (Direction d : Direction.Plane.HORIZONTAL) {                       // a hopper beside it pointing into it: its fuel
				BlockPos h = f.relative(d);
				BlockState hs = level.getBlockState(h);
				if (hs.is(Blocks.HOPPER) && hs.getValue(HopperBlock.FACING) == d.getOpposite() && container(level.getBlockState(h.above()))) fuel = h.above().immutable();
			}
			BlockState under = level.getBlockState(f.below());                    // a hopper under it: into a container, what it made
			if (under.is(Blocks.HOPPER)) {
				BlockPos into = f.below().relative(under.getValue(HopperBlock.FACING));
				if (container(level.getBlockState(into))) output = into.immutable();
			}
			out.add(new Unit(f, in, fuel, output));
		}
		return out;
	}

	/**
	 * Its own smelter: n furnaces side by side (to the right of the corner, facing front), each with a barrel and a hopper
	 * on top; an output hopper and barrel below and in front if it learned that (or has iron to spare), and a fuel hopper
	 * and barrel at the back the same way. Bottom up, so each block has something to go against.
	 */
	static Architect.Plan plan(BlockPos corner, Direction front, int n, boolean output, boolean fuel) {
		Direction right = front.getCounterClockWise(), back = front.getOpposite();
		List<Architect.Step> steps = new ArrayList<>();
		BlockState barrel = Blocks.BARREL.defaultBlockState();
		for (int i = 0; i < n; i++) {
			BlockPos col = corner.relative(right, i).relative(back, 1);           // (row 0 in front: the output barrels; row 1: the furnaces; row 2: fuel)
			if (output) {
				steps.add(new Architect.Step(col.relative(front), barrel, Architect.INSIDE, false));
				steps.add(new Architect.Step(col, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, front), Architect.INSIDE, false));
			} else {
				steps.add(new Architect.Step(col, Blocks.COBBLESTONE.defaultBlockState(), Architect.SUPPORT, false));
			}
			steps.add(new Architect.Step(col.above(), Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, front), Architect.INSIDE, false));
			if (fuel) {
				BlockPos h = col.relative(back).above();
				steps.add(new Architect.Step(h.below(), Blocks.COBBLESTONE.defaultBlockState(), Architect.SUPPORT, false));
				steps.add(new Architect.Step(h, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, front), Architect.INSIDE, false));
				steps.add(new Architect.Step(h.above(), barrel, Architect.INSIDE, false));
			}
			steps.add(new Architect.Step(col.above(2), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), Architect.INSIDE, false));
			steps.add(new Architect.Step(col.above(3), barrel, Architect.INSIDE, false));
		}
		steps.sort(java.util.Comparator.comparingInt(Architect.Step::phase).thenComparingInt(st -> st.pos().getY()));
		BlockPos middle = corner.relative(right, n / 2);
		return new Architect.Plan("smelter", steps, middle, middle, front, new net.minecraft.world.phys.AABB(middle));
	}

	/** How it learned to build one: the hoppers it saw under and beside furnaces (any of its studies), and the iron it has. */
	static boolean learnedOutput(BtbLearner l) {
		for (var e : l.rules().entrySet()) if (e.getKey().matches("(furnace|blast_furnace|smoker)>\\w+ above hopper>\\w+")) return true;
		return false;
	}

	static boolean learnedFuel(BtbLearner l) {
		for (var e : l.rules().entrySet()) if (e.getKey().matches("(hopper>\\w+ beside (furnace|blast_furnace|smoker)>\\w+|(furnace|blast_furnace|smoker)>\\w+ beside hopper>\\w+) \\(\\w+\\)")) return true;
		return false;
	}
}
