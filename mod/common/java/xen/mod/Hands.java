package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Blocks;
import xen.mod.core.Perception;

import java.util.Set;

/**
 * Carries out Xen's decisions through a player's inputs only: movement keys drive the normal player physics,
 * mining takes the real break time (with the best tool in its hotbar), it places blocks it carries, attacks with
 * the normal cooldown and reach, and eats food it has. Nothing a player couldn't do.
 */
public final class Hands {
	static final Set<String> PLACEABLE = Set.of("cobblestone", "cobbled_deepslate", "dirt");
	private static final float[] YAW = {180f, -90f, 0f, 90f};          // our facing index -> Minecraft degrees

	final XenPlayer p;
	int yaw, pitch;
	private Action current = Action.IDLE;
	private int ticks, limit;
	private BlockPos digging;
	private float progress;
	private boolean pillar;
	private BlockPos pillarFrom;

	Hands(XenPlayer p) {
		this.p = p;
		this.yaw = Math.floorMod(Math.round((p.getYRot() + 180f) / 90f), 4);
	}

	boolean busy() {
		return ticks < limit;
	}

	/** Jump and put a block under its feet (a player's way out of a hole). Returns false without blocks. */
	boolean startPillar() {
		if (findHotbar(s -> PLACEABLE.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) < 0) return false;
		stop();
		current = Action.JUMP;
		pillar = true;
		pillarFrom = p.blockPosition();
		ticks = 0;
		limit = 12;
		return true;
	}

	private void keepPillaring() {
		p.setJumping(ticks < 4);
		if (p.getY() < pillarFrom.getY() + 1.0) return;
		ServerLevel level = (ServerLevel) p.level();
		if (!level.getBlockState(pillarFrom).canBeReplaced()) return;
		int slot = findHotbar(s -> PLACEABLE.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
		if (slot < 0) return;
		p.getInventory().setSelectedSlot(slot);
		BlockPos ground = pillarFrom.below();
		Vec3 hit = Vec3.atCenterOf(ground).add(0, 0.5, 0);
		p.gameMode.useItemOn(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(hit, Direction.UP, ground, false));
		Compat.swing(p);
		pillar = false;
		limit = Math.min(limit, ticks + 3);
	}

	void look() {
		p.setYRot(YAW[yaw]);
		p.setYHeadRot(YAW[yaw]);
		p.setXRot((float) -Math.toDegrees(Perception.LOOK_PITCH[pitch + 1]));
	}

	BlockPos target() {
		BlockPos feet = p.blockPosition();
		int[] f = Perception.forward(yaw);
		if (pitch < 0) return feet.below();
		return feet.offset(f[0], pitch > 0 ? 1 : 0, f[1]);
	}

	void start(Action a) {
		stop();
		current = a;
		ticks = 0;
		limit = 5;                                                    // a quarter of a second
		switch (a) {
			case TURN_LEFT -> { yaw = Math.floorMod(yaw - 1, 4); limit = 1; }
			case TURN_RIGHT -> { yaw = Math.floorMod(yaw + 1, 4); limit = 1; }
			case LOOK_UP -> { pitch = Math.min(1, pitch + 1); limit = 1; }
			case LOOK_DOWN -> { pitch = Math.max(-1, pitch - 1); limit = 1; }
			case JUMP -> limit = 7;
			case MINE -> startMining();
			case PLACE -> { place(); limit = 2; }
			case ATTACK -> { attack(); limit = 2; }
			case EAT -> startEating();
			default -> {}
		}
		look();
	}

	/** Called every server tick while an action runs. */
	void tick() {
		if (!busy()) {
			p.xxa = p.zza = 0;
			p.setJumping(false);
			return;
		}
		ticks++;
		if (pillar) {
			keepPillaring();
			if (!busy()) {
				pillar = false;
				p.setJumping(false);
			}
			return;
		}
		switch (current) {
			case FORWARD -> p.zza = 1;
			case BACK -> p.zza = -1;
			case LEFT -> p.xxa = 1;
			case RIGHT -> p.xxa = -1;
			case JUMP -> { p.zza = 1; p.setJumping(true); }
			case MINE -> keepMining();
			case EAT -> { if (!p.isUsingItem() && ticks > 2) limit = ticks; }
			default -> {}
		}
		if (current != Action.ATTACK) look();
		if (!busy()) {
			p.xxa = p.zza = 0;
			p.setJumping(false);
		}
	}

	void stop() {
		pillar = false;
		if (digging != null) {
			p.gameMode.handleBlockBreakAction(digging, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP,
					p.level().getMaxY(), 0);
			digging = null;
		}
		p.xxa = p.zza = 0;
		p.setJumping(false);
		ticks = limit = 0;
	}

	// --------------------------------------------------------------------------------- mining
	private void startMining() {
		ServerLevel level = (ServerLevel) p.level();
		BlockPos pos = target();
		BlockState state = level.getBlockState(pos);
		int c = WorldSenses.category(level, pos, state);
		if (state.isAir() || c == Blocks.AIR || c == Blocks.LAVA || c == Blocks.WATER || c == Blocks.BEDROCK
				|| state.getDestroySpeed(level, pos) < 0) {
			Compat.swing(p);
			limit = 2;
			return;
		}
		selectBestTool(state);
		digging = pos;
		progress = 0;
		limit = 200;                                                  // give up after 10 seconds
		p.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, face(),
				level.getMaxY(), 0);
		if (level.getBlockState(pos).isAir()) {                       // broke instantly
			digging = null;
			limit = 2;
		}
	}

	private void keepMining() {
		if (digging == null) return;
		ServerLevel level = (ServerLevel) p.level();
		BlockState state = level.getBlockState(digging);
		if (state.isAir()) {
			digging = null;
			limit = ticks;
			return;
		}
		progress += state.getDestroyProgress(p, level, digging);
		Compat.swing(p);
		if (progress >= 1f) {
			p.gameMode.handleBlockBreakAction(digging, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, face(),
					level.getMaxY(), 0);
			digging = null;
			limit = ticks;
		}
	}

	private Direction face() {
		if (pitch < 0) return Direction.UP;
		return switch (yaw) {
			case 0 -> Direction.SOUTH;                                // facing north, it hits the south face
			case 1 -> Direction.WEST;
			case 2 -> Direction.NORTH;
			default -> Direction.EAST;
		};
	}

	/** Pick the hotbar item that breaks this block fastest (like pressing a number key). */
	private void selectBestTool(BlockState state) {
		Inventory inv = p.getInventory();
		int best = inv.getSelectedSlot();
		float speed = inv.getItem(best).getDestroySpeed(state);
		for (int slot = 0; slot < 9; slot++) {
			float s = inv.getItem(slot).getDestroySpeed(state);
			if (s > speed) {
				speed = s;
				best = slot;
			}
		}
		inv.setSelectedSlot(best);
	}

	// -------------------------------------------------------------------------------- placing
	private void place() {
		ServerLevel level = (ServerLevel) p.level();
		BlockPos pos = target();
		BlockState here = level.getBlockState(pos);
		if (!here.canBeReplaced()) return;
		int slot = findHotbar(s -> PLACEABLE.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
		if (slot < 0) return;
		for (Direction d : Direction.values()) {
			BlockPos against = pos.relative(d);
			if (!level.getBlockState(against).isCollisionShapeFullBlock(level, against)) continue;
			p.getInventory().setSelectedSlot(slot);
			ItemStack stack = p.getInventory().getSelectedItem();
			Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
			p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, d.getOpposite(), against, false));
			Compat.swing(p);
			return;
		}
	}

	// ------------------------------------------------------------------------------ fighting
	private void attack() {
		double reach = p.entityInteractionRange();
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3 eye = p.getEyePosition();
		int[] f = Perception.forward(yaw);
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(reach + 1),
				x -> x instanceof Enemy && x.isAlive())) {
			Vec3 d = e.position().add(0, e.getBbHeight() / 2, 0).subtract(eye);
			double flat = Math.hypot(d.x, d.z);
			if (flat > 0.01 && (d.x * f[0] + d.z * f[1]) / flat < 0.5) continue;      // must be in front (within 60 deg)
			double dist = p.distanceTo(e);
			if (dist <= reach + 0.5 && dist < bestDist && p.hasLineOfSight(e)) {
				best = e;
				bestDist = dist;
			}
		}
		Compat.swing(p);
		if (best != null) {
			Vec3 d = best.getEyePosition().subtract(eye);
			p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
			p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
			p.attack(best);
		}
	}

	// -------------------------------------------------------------------------------- eating
	private void startEating() {
		if (!p.getFoodData().needsFood()) {
			limit = 1;
			return;
		}
		int slot = findHotbar(s -> s.has(DataComponents.FOOD));
		if (slot < 0) {
			limit = 1;
			return;
		}
		p.getInventory().setSelectedSlot(slot);
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		limit = 45;
	}

	// ------------------------------------------------------------------- aimed actions (chores)
	/** Look straight at a point, like moving the mouse there (and face the nearest of the 4 directions). */
	void face(Vec3 at) {
		Vec3 d = at.subtract(p.getEyePosition());
		float yRot = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		p.setYRot(yRot);
		p.setYHeadRot(yRot);
		p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
		yaw = Math.floorMod(Math.round((yRot + 180f) / 90f), 4);
	}

	/** Why the last placeAt didn't place (for its status). */
	String cantPlace = "";

	/** Place a block it carries at pos, against a solid neighbour, if it can reach. */
	boolean placeAt(BlockPos pos) {
		ServerLevel level = (ServerLevel) p.level();
		if (!level.getBlockState(pos).canBeReplaced()) {
			cantPlace = "already a block there";
			return false;
		}
		var there = level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(pos));
		if (!there.isEmpty()) {
			cantPlace = there.get(0).getName().getString() + " is in the way";
			return false;
		}
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > p.blockInteractionRange()) {
			cantPlace = "too far to reach";
			return false;
		}
		int slot = findHotbar(s -> PLACEABLE.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
		if (slot < 0) {
			cantPlace = "no blocks left";
			return false;
		}
		for (Direction d : Direction.values()) {
			BlockPos against = pos.relative(d);
			if (!level.getBlockState(against).isCollisionShapeFullBlock(level, against)) continue;
			stop();
			p.getInventory().setSelectedSlot(slot);
			Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
			face(hit);
			p.gameMode.useItemOn(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, d.getOpposite(), against, false));
			Compat.swing(p);
			current = Action.PLACE;
			ticks = 0;
			limit = 4;
			cantPlace = level.getBlockState(pos).canBeReplaced() ? "the block didn't stay" : "";
			return cantPlace.isEmpty();
		}
		cantPlace = "nothing solid to place it against";
		return false;
	}

	/** Hit a creature in reach (the normal attack, with the normal cooldown). */
	void hit(LivingEntity e) {
		stop();
		face(e.getEyePosition());
		Compat.swing(p);
		p.attack(e);
		current = Action.ATTACK;
		ticks = 0;
		limit = 2;
	}

	/** Toss items where it looks (a player's Q key); the other player picks them up. */
	void toss(ItemStack stack) {
		p.drop(stack, false, true);
		Compat.swing(p);
	}

	/** A hotbar slot holding a matching item, moving one there from the backpack if needed. */
	private int findHotbar(java.util.function.Predicate<ItemStack> want) {
		Inventory inv = p.getInventory();
		for (int slot = 0; slot < 9; slot++) if (!inv.getItem(slot).isEmpty() && want.test(inv.getItem(slot))) return slot;
		for (int slot = 9; slot < 36; slot++) {
			ItemStack s = inv.getItem(slot);
			if (!s.isEmpty() && want.test(s)) {
				ItemStack held = inv.getItem(8);                     // swap into the last hotbar slot
				inv.setItem(8, s);
				inv.setItem(slot, held);
				return 8;
			}
		}
		return -1;
	}
}
