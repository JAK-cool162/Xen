package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * For developing the designs only (not part of play): started with {@code -Dxen.designTest=x,z}, the server puts down
 * the test houses at once when it starts: each row one design and palette, from left to right its stages (basic,
 * simple, good, perfect), on levelled ground, so they can be looked at and compared. Nothing happens without the flag.
 */
final class DesignTest {
	private DesignTest() {}

	private static boolean done;

	static void maybeRun(MinecraftServer server) {
		String at = System.getProperty("xen.designTest");
		if (done || at == null) return;
		done = true;
		String[] xz = at.split(",");
		ServerLevel level = server.overworld();
		int x0 = Integer.parseInt(xz[0].trim()), z0 = Integer.parseInt(xz[1].trim());
		String[] rows = System.getProperty("xen.designTestRows", "oak:cottage").split(";");
		level.getChunk(x0 >> 4, z0 >> 4);                                              // (loaded first: an unloaded one has no heights yet)
		int y0 = xz.length > 2 ? Integer.parseInt(xz[2].trim())
				: Math.max(level.getSeaLevel() + 1, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0));
		levelGround(level, new BlockPos(x0 - 14, y0, z0 - 30), 4 * 36 + 28, rows.length * 34 + 48);   // one flat field for all of them
		for (int r = 0; r < rows.length; r++) {
			String[] rp = rows[r].split(":");
			Architect.Palette p = Architect.PALETTES.getOrDefault(rp[0], Architect.PALETTES.get("oak"));
			Taste.Design ds = design(rp.length > 1 ? rp[1] : "cottage");
			for (int stage = 0; stage <= Architect.POLISH; stage++) {
				BlockPos corner = new BlockPos(x0 + stage * 36, y0, z0 + r * 34);
				Architect.Plan plan = Architect.designed(corner, Direction.SOUTH, ds, p, new Random(corner.asLong()), stage);
				put(level, plan);
				XenMod.LOG.info("[design test] {} {} {} at {} (middle {})", rp[0], rp.length > 1 ? rp[1] : "cottage", Architect.STAGES[stage], corner, plan.middle());
			}
		}
	}

	/** The designs to look at, by name. */
	static Taste.Design design(String name) {
		return switch (name) {
			case "small" -> new Taste.Design(7, 7, 3, Taste.Roof.GABLE, false, true, true, true, true, false, true, true, false, false, true,
					Taste.Style.COTTAGE, false, false);
			case "hip" -> new Taste.Design(9, 7, 4, Taste.Roof.HIP, true, true, true, true, true, true, true, true, false, false, true,
					Taste.Style.COTTAGE, false, false);
			case "wide" -> new Taste.Design(11, 7, 4, Taste.Roof.GABLE, true, true, true, true, true, false, true, true, false, false, true,
					Taste.Style.COTTAGE, false, true);
			case "cross" -> new Taste.Design(13, 7, 4, Taste.Roof.GABLE, true, true, true, true, true, true, true, true, false, false, true,
					Taste.Style.COTTAGE, false, false);
			case "ell" -> new Taste.Design(9, 7, 4, Taste.Roof.GABLE, true, true, true, true, true, false, true, true, false, false, true,
					Taste.Style.COTTAGE, false, false);
			case "lowcross" -> new Taste.Design(13, 7, 3, Taste.Roof.GABLE, true, true, true, true, true, true, true, true, false, false, true,
					Taste.Style.COTTAGE, false, false);
			default -> new Taste.Design(9, 7, 4, Taste.Roof.GABLE, false, true, true, true, true, false, true, true, false, false, true,
					Taste.Style.COTTAGE, false, false);
		};
	}

	/** Flat grass from corner (its low x, z) w by d blocks, the air above clear. */
	private static void levelGround(ServerLevel level, BlockPos corner, int w, int d) {
		for (int dx = 0; dx <= w; dx++) {
			for (int dz = 0; dz <= d; dz++) {
				BlockPos b = corner.offset(dx, 0, dz);
				if (dz % 16 == 0) level.getChunk(b.getX() >> 4, b.getZ() >> 4);
				for (int y = 0; y <= 50; y++) level.setBlock(b.above(y), Blocks.AIR.defaultBlockState(), 2);
				level.setBlock(b.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
				for (int y = 2; y <= 5; y++) level.setBlock(b.below(y), Blocks.DIRT.defaultBlockState(), 2);
			}
		}
	}

	/** Every step of a plan at once (a door's top half and a bed's head too, as placing them would). */
	private static void put(ServerLevel level, Architect.Plan plan) {
		for (Architect.Step st : plan.steps()) {
			BlockState s = st.dig() ? Blocks.AIR.defaultBlockState() : st.state();
			if (s.is(Blocks.DIRT) && level.getBlockState(st.pos()).is(Blocks.GRASS_BLOCK)) continue;   // (grass is fine as it is)
			level.setBlock(st.pos(), s, 2);
			if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				level.setBlock(st.pos().above(), s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
			} else if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.FOOT) {
				level.setBlock(st.pos().relative(s.getValue(BedBlock.FACING)), s.setValue(BedBlock.PART, BedPart.HEAD), 2);
			} else if (s.getBlock() instanceof DoublePlantBlock && s.hasProperty(DoublePlantBlock.HALF) && s.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
				level.setBlock(st.pos().above(), s.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2);
			}
		}
	}
}
