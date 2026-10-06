package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Ex1;

/**
 * What a Xen senses, the way the gameplay recorder wrote a player's frames (xen/ex1/features.py, the same 119 numbers
 * in the same order): its body, the blocks at its feet and head, what it looks at, creatures about, its hands, and 7
 * by 8 sight lines across its view (the recorder's 14 by 24, every other row and every third column), 64 blocks deep.
 * What it sees is what a player would: only along its sight lines, nothing through walls.
 */
final class Ex1Senses {
	private Ex1Senses() {}

	static final double FOV_V = 90, FOV_H = 130.626, NEAR = 64, FAR = 192;
	static final int ROWS = 14, COLS = 24, CELL_ROWS = 7, CELL_COLS = 8;

	private static String id(ServerLevel level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();   // (water and lava are blocks too: "water", "lava")
	}

	private static float clip(double x, double lo, double hi) {
		return (float) Math.max(lo, Math.min(hi, x));
	}

	private static double wrap(double deg) {
		return ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
	}

	static float[] sense(Companion c) {
		var p = c.player;
		ServerLevel level = (ServerLevel) p.level();
		float[] f = new float[Ex1.N];
		f[0] = p.onGround() ? 1 : 0;
		f[1] = p.isInWater() ? 1 : 0;
		f[2] = p.isSprinting() ? 1 : 0;
		f[3] = p.isShiftKeyDown() ? 1 : 0;
		f[4] = p.getHealth() / 20f;
		f[5] = p.getFoodData().getFoodLevel() / 20f;
		Vec3 v = p.getDeltaMovement();
		f[6] = clip(Math.hypot(v.x, v.z) / 0.4, 0, 1.5);
		f[7] = clip(v.y, -1, 1);
		f[8] = clip(p.getXRot() / 90.0, -1, 1);
		f[9] = level.getMaxLocalRawBrightness(BlockPos.containing(p.getEyePosition())) / 15f;
		f[10] = level.isRaining() ? 1 : 0;
		String dim = level.dimension().identifier().getPath();
		f[11 + (dim.contains("nether") ? 1 : dim.contains("end") ? 2 : 0)] = 1;
		BlockPos feet = p.blockPosition();
		BlockPos[] at = {feet.below(), feet, feet.above()};
		for (int k = 0; k < 3; k++) f[14 + 6 * k + Ex1.category(id(level, at[k]))] = 1;
		// what it looks at (a block or a creature, what the crosshair would pick)
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		double reach = 6;
		BlockHitResult block = level.clip(new ClipContext(eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		double blockD = block.getType() == HitResult.Type.BLOCK ? block.getLocation().distanceTo(eye) : Double.MAX_VALUE;
		Vec3 end = eye.add(look.scale(Math.min(reach, blockD)));
		LivingEntity seen = null;
		double seenD = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().expandTowards(look.scale(reach)).inflate(1), e -> e != p && !e.isSpectator())) {
			var box = e.getBoundingBox().inflate(e.getPickRadius());
			var hit = box.clip(eye, end);
			if (hit.isPresent() && hit.get().distanceTo(eye) < seenD) {
				seenD = hit.get().distanceTo(eye);
				seen = e;
			}
		}
		if (seen != null) {
			f[34] = 1;
			f[35] = clip(seenD / 6.0, 0, 1);
			f[42] = seen instanceof Enemy ? 1 : 0;
		} else if (block.getType() == HitResult.Type.BLOCK) {
			f[33] = 1;
			f[35] = clip(blockD / 6.0, 0, 1);
			f[36 + Ex1.category(id(level, block.getBlockPos()))] = 1;
		} else {
			f[32] = 1;
		}
		// creatures about (16 blocks)
		LivingEntity nearestHostile = null, nearestOther = null;
		double dh = Double.MAX_VALUE, doth = Double.MAX_VALUE;
		int within8 = 0, within16 = 0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16), e -> e != p && e.isAlive() && !e.isSpectator())) {
			double d = e.distanceTo(p);
			if (d > 16) continue;
			if (e instanceof Enemy) {
				if (d <= 8) within8++;
				within16++;
				if (d < dh) {
					dh = d;
					nearestHostile = e;
				}
			} else if (d < doth && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)) {   // (an animal, a villager, a player)
				doth = d;
				nearestOther = e;
			}
		}
		if (nearestHostile != null) {
			f[43] = (float) (1 / (1 + dh));
			Vec3 rel = nearestHostile.position().subtract(p.position());
			double a = Math.toRadians(wrap(Math.toDegrees(Math.atan2(-rel.x, rel.z)) - p.getYRot()));
			f[46] = (float) Math.sin(a);
			f[47] = (float) Math.cos(a);
			f[48] = clip(rel.y / 8, -1, 1);
		}
		f[44] = Math.min(1f, within8 / 4f);
		f[45] = Math.min(1f, within16 / 6f);
		if (nearestOther != null) f[49] = (float) (1 / (1 + doth));
		ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
		f[50 + Ex1.hand(main.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(main.getItem()).getPath())] = 1;
		f[58] = !off.isEmpty() && BuiltInRegistries.ITEM.getKey(off.getItem()).getPath().equals("shield") ? 1 : 0;
		f[59] = p.isUsingItem() ? 1 : 0;
		// its view: sight lines across the screen, 64 blocks deep (and to 192 for how open it is)
		int lava = 0, water = 0, open = 0;
		for (int i = 0; i < CELL_ROWS; i++) {
			for (int j = 0; j < CELL_COLS; j++) {
				int row = Math.min(ROWS - 1, 2 * i + 1), col = Math.min(COLS - 1, 3 * j + 1);
				float pitch = (float) (p.getXRot() - FOV_V / 2 + (row + 0.5) * FOV_V / ROWS);
				float yaw = (float) (p.getYRot() - FOV_H / 2 + (col + 0.5) * FOV_H / COLS);
				Vec3 dir = Vec3.directionFromRotation(Math.max(-90, Math.min(90, pitch)), yaw);
				BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(FAR)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
				double d = hit.getType() == HitResult.Type.BLOCK ? Math.floor(hit.getLocation().distanceTo(eye)) : -1;
				if (d < 0) open++;
				f[60 + i * CELL_COLS + j] = d < 0 || d > NEAR ? 1f : clip(d / NEAR, 0, 1);
				if (d >= 0 && d <= NEAR) {
					int cat = Ex1.category(id(level, hit.getBlockPos()));
					if (cat == Ex1.LAVA) lava++;
					if (cat == Ex1.WATER) water++;
				}
			}
		}
		f[116] = lava > 0 ? 1 : 0;
		f[117] = water / (float) (CELL_ROWS * CELL_COLS);
		f[118] = open / (float) (CELL_ROWS * CELL_COLS);
		return f;
	}
}
