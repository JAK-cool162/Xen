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
		if (n.endsWith("pumpkin_stem") || n.endsWith("melon_stem")) return Blocks.AIR;   // crops, not trees
		if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_hyphae") || isNetherStem(n)) return Blocks.LOG;   // not mushroom stems
		if (n.endsWith("_leaves")) return Blocks.LEAVES;
		if (n.equals("grass_block")) return Blocks.GRASS;
		if (DIRT.contains(n)) return Blocks.DIRT;
		if (!state.getCollisionShape(level, pos).isEmpty()) return Blocks.STONE;   // fences, walls, panes, slabs... (like the bridge)
		return Blocks.AIR;                                     // flowers, grass, torches... nothing in the way
	}

	/** Crimson and warped stems are the Nether's trees (a mushroom_stem is part of a huge mushroom: no wood in it). */
	static boolean isNetherStem(String n) {
		return n.endsWith("_stem") && (n.contains("crimson") || n.contains("warped"));
	}

	/** Stone to mine for cobblestone (not a mushroom cap, a fence or someone's wall, which are "stone" to its senses too). */
	static boolean isNaturalStone(String n) {
		return n.equals("stone") || n.equals("deepslate") || n.equals("cobblestone");
	}

	/**
	 * A log that's part of a tree (leaves grew on it, not placed ones), not one somebody built a house with: the way a
	 * player tells a trunk from a wall of logs.
	 */
	static boolean treeLog(ServerLevel level, BlockPos p) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = -1; dy <= 12; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
					if (!level.isLoaded(m)) return true;                           // (can't tell: say yes)
					BlockState s = level.getBlockState(m);
					if (s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
						if (!s.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) || !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) return true;
					} else if (s.is(net.minecraft.world.level.block.Blocks.NETHER_WART_BLOCK) || s.is(net.minecraft.world.level.block.Blocks.WARPED_WART_BLOCK)) {
						return true;                                                    // (a Nether "tree")
					}
				}
			}
		}
		return false;
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

	/** Whether Xen senses this creature: felt within 6 blocks, or seen in its view (not through walls). */
	public static boolean sees(ServerPlayer p, int yaw, int pitch, net.minecraft.world.entity.Entity e) {
		double d = p.distanceTo(e);
		if (d <= Perception.NEAR) return true;
		if (d > Perception.VIEW) return false;
		ServerLevel level = (ServerLevel) p.level();
		Vec3 eye = p.getEyePosition(), head = e.position().add(0, e.getBbHeight() * 0.85, 0);
		double[] from = {eye.x, eye.y, eye.z}, to = {head.x, head.y, head.z};
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		return Perception.inView(from, yaw, pitch, to) && Perception.lineOfSight((x, y, z) -> seen(level, m.set(x, y, z)), from, to)
				&& lit(level, e.blockPosition(), from);
	}

	/** How far it sees in the dark: like a player, the first few blocks (its own eyes adjust), not further. */
	static final double DARK_SIGHT = 5;

	/** Light a player's eyes get from here: blocks' own light, or the sky's (less at night). */
	static int light(ServerLevel level, BlockPos pos) {
		return level.isLoaded(pos) ? level.getRawBrightness(pos, level.getSkyDarken()) : 0;
	}

	/** Can it make out something at pos (lit enough, or close)? */
	static boolean lit(ServerLevel level, BlockPos pos, double[] eye) {
		double dx = pos.getX() + 0.5 - eye[0], dy = pos.getY() + 0.5 - eye[1], dz = pos.getZ() + 0.5 - eye[2];
		return dx * dx + dy * dy + dz * dz <= DARK_SIGHT * DARK_SIGHT || light(level, pos) >= 3 || light(level, pos.above()) >= 3;
	}

	/**
	 * What its eyes make out at a block: a solid surface in the dark beyond a few blocks is unknown (the ray stops
	 * there), like a cave you look into without a torch.
	 */
	static int inTheLight(ServerLevel level, BlockPos.MutableBlockPos pos, double[] eye, int c) {
		if (c < 0 || !Blocks.OPAQUE[c]) return c;
		double dx = pos.getX() + 0.5 - eye[0], dy = pos.getY() + 0.5 - eye[1], dz = pos.getZ() + 0.5 - eye[2];
		if (dx * dx + dy * dy + dz * dz <= DARK_SIGHT * DARK_SIGHT) return c;
		int best = 0;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {   // the light on its faces
			best = Math.max(best, light(level, n.setWithOffset(pos, side)));
			if (best >= 3) return c;
		}
		return -1;
	}

	/**
	 * What it feels close by (within 6 blocks) is everything, except ore buried in stone: nobody can tell that's
	 * there until a face of it shows (in a cave, a cliff, a tunnel it digs).
	 */
	static int felt(ServerLevel level, BlockPos.MutableBlockPos pos, int c) {
		if (c < Blocks.COAL || c > Blocks.DIAMOND) return c;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
			n.setWithOffset(pos, side);
			if (!level.isLoaded(n)) continue;
			BlockState st = level.getBlockState(n);
			if (!st.canOcclude() || !st.getFluidState().isEmpty()) return c;              // a face shows: it can see it
		}
		return Blocks.STONE;
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
					s.near[i++] = level.isLoaded(m) ? felt(level, m, category(level, m, level.getBlockState(m))) : Blocks.STONE;
				}
			}
		}
		Vec3 eye = p.getEyePosition();
		double[] e = {eye.x, eye.y, eye.z};
		Perception.World world = (x, y, z) -> seen(level, m.set(x, y, z));
		Perception.World eyes = (x, y, z) -> inTheLight(level, m.set(x, y, z), e, seen(level, m));   // (in the dark it sees little)
		Perception.castRays(eyes, e, yaw, pitch, phase, s);
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
				if (Perception.inView(e, yaw, pitch, h) && Perception.lineOfSight(world, e, h) && lit(level, q, e)) {
					s.farMobs.add(new int[] {q.getX(), q.getY(), q.getZ()});
				}
			}
		}
		return s;
	}
}
