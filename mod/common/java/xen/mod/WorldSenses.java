package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Blocks;
import xen.mod.core.Perception;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the real world into what Xen senses (same rules as bridge/xen_bridge.js):
 * everything within 6 blocks, and beyond that only what its eyes see. It never loads chunks just to look.
 */
public final class WorldSenses {
	private static final Map<BlockState, Integer> CATEGORY = new IdentityHashMap<>();
	private static final Map<BlockState, Boolean> SEE_THROUGH = new IdentityHashMap<>();
	private static final Pattern CLEAR = Pattern.compile("glass|^ice$|barrier|^light$|iron_bars");
	private static final Set<String> DIRT = Set.of("dirt", "coarse_dirt", "podzol", "rooted_dirt", "mud", "farmland", "dirt_path",
			"mycelium", "sand", "red_sand", "gravel", "clay", "snow_block");

	private WorldSenses() {}

	public static int category(ServerLevel level, BlockPos pos, BlockState state) {
		Integer c = CATEGORY.get(state);
		if (c != null) return c;
		c = classify(level, pos, state);
		synchronized (CATEGORY) {
			CATEGORY.put(state, c);
		}
		return c;
	}

	private static int classify(ServerLevel level, BlockPos pos, BlockState state) {
		String n = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (state.isAir()) return Blocks.AIR;
		if (state.getFluidState().is(FluidTags.LAVA) || n.equals("fire") || n.equals("magma_block")) return Blocks.LAVA;
		if (n.equals("water") || n.equals("bubble_column") || n.startsWith("kelp") || n.endsWith("seagrass")) return Blocks.WATER;
		if (n.equals("bedrock") || n.equals("barrier") || n.equals("obsidian")) return Blocks.BEDROCK;
		if (n.contains("diamond_ore")) return Blocks.DIAMOND;
		if (n.contains("gold_ore")) return Blocks.GOLD;
		if (n.contains("iron_ore")) return Blocks.IRON;
		if (n.contains("coal_ore")) return Blocks.COAL;
		if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_stem") || n.endsWith("_hyphae")) return Blocks.LOG;
		if (n.endsWith("_leaves")) return Blocks.LEAVES;
		if (n.equals("grass_block")) return Blocks.GRASS;
		if (DIRT.contains(n)) return Blocks.DIRT;
		if (state.isCollisionShapeFullBlock(level, pos)) return Blocks.STONE;
		return Blocks.AIR;                                     // flowers, torches... nothing to stand on
	}

	/** Can sight pass through it? (glass, ice...) */
	static boolean opaque(ServerLevel level, BlockPos pos, BlockState state) {
		int c = category(level, pos, state);
		if (!Blocks.OPAQUE[c]) return false;
		Boolean clear = SEE_THROUGH.get(state);
		if (clear == null) {
			clear = CLEAR.matcher(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()).find();
			synchronized (SEE_THROUGH) {
				SEE_THROUGH.put(state, clear);
			}
		}
		return !clear;
	}

	/** Category, or -1 if that part of the world isn't loaded (unknown). Glass counts as air for the eyes. */
	static int seen(ServerLevel level, BlockPos.MutableBlockPos pos) {
		if (!level.isLoaded(pos)) return -1;
		BlockState state = level.getBlockState(pos);
		int c = category(level, pos, state);
		return Blocks.OPAQUE[c] && !opaque(level, pos, state) ? Blocks.AIR : c;
	}

	public static Perception.Sight sense(ServerPlayer p, int yaw, int pitch, int phase, Perception.Body body) {
		ServerLevel level = (ServerLevel) p.level();
		Perception.Sight s = new Perception.Sight();
		BlockPos feet = p.blockPosition();
		s.position = new int[] {feet.getX(), feet.getY(), feet.getZ()};
		s.yaw = yaw;
		s.body = body;
		s.t = level.getGameTime();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int i = 0, near = Perception.NEAR;
		for (int dx = -near; dx <= near; dx++) {
			for (int dy = -near; dy <= near; dy++) {
				for (int dz = -near; dz <= near; dz++) {
					m.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
					s.near[i++] = level.isLoaded(m) ? category(level, m, level.getBlockState(m)) : Blocks.STONE;
				}
			}
		}
		Vec3 eye = p.getEyePosition();
		double[] e = {eye.x, eye.y, eye.z};
		Perception.World world = (x, y, z) -> seen(level, m.set(x, y, z));
		Perception.castRays(world, e, yaw, pitch, phase, s);
		AABB box = p.getBoundingBox().inflate(Perception.VIEW * 0.75);
		List<LivingEntity> mobs = level.getEntitiesOfClass(LivingEntity.class, box, x -> x instanceof Enemy && x.isAlive());
		for (LivingEntity mob : mobs) {
			BlockPos q = mob.blockPosition();
			int[] rel = {q.getX() - feet.getX(), q.getY() - feet.getY(), q.getZ() - feet.getZ()};
			if (Math.sqrt(rel[0] * rel[0] + rel[1] * rel[1] + rel[2] * rel[2]) <= near) {
				s.nearMobs.add(rel);                               // felt, even behind it
			} else {
				Vec3 head = mob.position().add(0, mob.getBbHeight() * 0.85, 0);
				double[] h = {head.x, head.y, head.z};
				if (Perception.inView(e, yaw, pitch, h) && Perception.lineOfSight(world, e, h)) {
					s.farMobs.add(new int[] {q.getX(), q.getY(), q.getZ()});
				}
			}
		}
		return s;
	}
}
