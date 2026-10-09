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
	static final Set<String> PLACEABLE = Set.of("cobblestone", "cobbled_deepslate", "dirt", "netherrack", "end_stone");
	/**
	 * The order it spares them in (to climb on, to bridge, to block a gap): dirt, netherrack and end stone (what's
	 * everywhere in the Nether and the End: a tower up to a caged crystal takes 30 to 40), then deepslate, the
	 * cobblestone it makes things of last.
	 */
	private static final String[] SPARE_FIRST = {"dirt", "netherrack", "end_stone", "cobbled_deepslate", "cobblestone"};

	/** The block it can best spare that it carries, or null. */
	String spareBlock() {
		Inventory inv = p.getInventory();
		for (String n : SPARE_FIRST) {
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(n)) return n;
			}
		}
		return null;
	}

	/** The slot (in the hotbar, swapped in if need be) of the block it can best spare, or -1. */
	private int spareSlot() {
		String n = spareBlock();
		return n == null ? -1 : findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(n));
	}
	static final float[] YAW = {180f, -90f, 0f, 90f};                  // our facing index -> Minecraft degrees

	final XenPlayer p;
	int yaw, pitch;
	private Action current = Action.IDLE;
	private Action lastMove = Action.IDLE;
	private int ticks, limit;
	private BlockPos digging;
	/** A block it aims at with the mouse (a step of a staircase), instead of straight ahead or down. */
	private BlockPos aim;
	private float progress;
	private boolean pillar;
	private BlockPos pillarFrom;

	Hands(XenPlayer p) {
		this.p = p;
		this.yaw = Math.floorMod(Math.round((p.getYRot() + 180f) / 90f), 4);
	}

	boolean busy() {
		return ticks < limit || flyTarget != null;
	}

	/** Creative: where it's flying to (forward to it, space to go up, shift to go down, like a player), and until when. */
	private Vec3 flyTarget;
	private int flyTicks;

	void flyToward(Vec3 target) {
		flyTarget = target;
		flyTicks = 0;
		if (target == null) {
			p.zza = p.xxa = 0;
			p.setJumping(false);
			p.setShiftKeyDown(false);
		}
	}

	private void fly() {
		Vec3 d = flyTarget.subtract(p.position());
		double flat = Math.hypot(d.x, d.z);
		// onto a floor: it holds shift till it lands on it (the floor stops it), and hops up if it's below it (under a
		// doorway there's no room to float). In the air it can't hold still to a hair, so only well off does it go up or down.
		BlockPos at = BlockPos.containing(flyTarget);
		boolean land = flyTarget.y - Math.floor(flyTarget.y) < 0.05
				&& !p.level().getBlockState(at.below()).getCollisionShape(p.level(), at.below()).isEmpty();
		double dy = d.y, up = land ? 0.05 : 0.25, low = land ? -0.05 : -0.25;
		if (flat < 0.2 && dy < 0.3 && dy > low || ++flyTicks > 80) {       // there (or it can't get closer): stop
			flyToward(null);
			p.setDeltaMovement(p.getDeltaMovement().scale(0.2));
			return;
		}
		float yRot = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		p.setYRot(yRot);
		p.setYHeadRot(yRot);
		p.setXRot(0);
		p.zza = flat > 0.15 ? (float) Math.min(1, flat * 0.6) : 0;          // slower as it gets close
		p.setJumping(dy > up);
		p.setShiftKeyDown(dy < low);
	}

	/** Can what it's doing be dropped at once (like letting go of a key)? Not a jump or a swing (crits need them), or eating. */
	boolean interruptible() {
		return current != Action.JUMP && current != Action.ATTACK && current != Action.EAT;
	}

	/** Jump and put a block under its feet (a player's way out of a hole). Returns false without blocks. */
	boolean startPillar() {
		if (spareBlock() == null) return false;
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
		p.setXRot(Math.min(90f, p.getXRot() + 30f));                        // eyes down on the block it puts under itself
		if (p.getY() < pillarFrom.getY() + 1.0) return;
		ServerLevel level = (ServerLevel) p.level();
		if (!level.getBlockState(pillarFrom).canBeReplaced()) return;
		int slot = spareSlot();
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

	/** In a fight it keeps its eyes on the foe (like a player's mouse), even while it steps back or strafes. */
	LivingEntity watching;
	/**
	 * Where it's walking to next (the middle of the next block of its way): it steers at it like a player moving the
	 * mouse a little, so it doesn't catch on the corner of a block when it isn't in the middle of its own.
	 */
	Vec3 steer;

	/**
	 * Idle: a point its eyes go to (whoever's there, something that moved, the view), turning a little each tick the way
	 * a player moves the mouse, never in one jump.
	 */
	Vec3 glance;
	/**
	 * Placing things where people see it (building, a table, a chest): only on a face it can see. (Its legs' own blocks,
	 * pillaring up and bridging, go the way a player's do, by feel, without this.)
	 */
	boolean strictSight;
	/** A builder that has tried from everywhere: a block it can't see goes in (or out) anyway. */
	boolean blindOk;
	/** The last cantMine was because it couldn't see the block (and couldn't dig its way to it). */
	boolean cantMineUnseen;
	/** Where its eyes stay a moment after it clicked something (the block it just put down, the table it used), and till when. */
	private Vec3 holdAt;
	private int holdUntil;
	/** The point on the block it's mining that it looks at; turning: its eyes aren't on it yet (it turns first, then digs). */
	private Vec3 aimPoint;
	private boolean turning;

	/** Keep its eyes on this for a few ticks (unless it moves off or something else needs them). */
	void holdLook(Vec3 at, int ticks) {
		holdAt = at;
		holdUntil = p.tickCount + ticks;
	}

	/**
	 * How hard a hand speeds a mouse turn up and slows it down (degrees a tick, each tick), as players turn: fitted to
	 * 600 recorded runs of people walking to a point and chopping trees (OpenBlock-Team/Minecraft-Navigation and
	 * Minecraft-ChopTree on Hugging Face, CC BY 4.0). There a turn of 9 degrees took 5 ticks, 40 degrees 8, 80 degrees 11
	 * and 160 degrees 16, its speed rising and falling (barely any of it in the first tick): about the square root of the
	 * turn, which a steady 2.2 degrees a tick, each tick, up to the middle and down after, gives.
	 */
	static final float TURN_ACCEL = 2.2f;
	/** Its view's speed of turning now (degrees a tick), and the tick it last turned (a turn that stopped starts from still). */
	private float turnVy, turnVx;
	private int turnTick = -100;

	/** The next speed toward an angle e away: up (or down) by accel, no faster than it can still stop in, or cap. */
	private static float follow(float v, float e, float accel, float cap) {
		float want = Math.signum(e) * Math.min(cap, (float) Math.sqrt(2 * accel * Math.abs(e)));
		return v + net.minecraft.util.Mth.clamp(want - v, -accel, accel);
	}

	/**
	 * Turn its view toward a point the way a hand moves a mouse: speeding up into the turn and slowing into the target
	 * (at most this many degrees a tick). True once it's looking right at it.
	 */
	private boolean turnToward(Vec3 at, float maxYaw, float maxPitch) {
		Vec3 d = at.subtract(p.getEyePosition());
		float toY = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), toX = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		float dy = net.minecraft.util.Mth.wrapDegrees(toY - p.getYRot()), dx = toX - p.getXRot();
		float scale = p.companion == null ? 1f : p.companion.reflexes.turnScale();
		if (p.tickCount - turnTick > 1) turnVy = turnVx = 0;
		turnTick = p.tickCount;
		float accel = TURN_ACCEL * scale;
		turnVy = follow(turnVy, dy, accel, maxYaw * scale);
		turnVx = follow(turnVx, dx, accel, maxPitch * scale);
		boolean doneY = Math.abs(dy) <= Math.max(1.5f, Math.abs(turnVy)), doneX = Math.abs(dx) <= Math.max(1.5f, Math.abs(turnVx));
		float y = doneY ? p.getYRot() + dy : p.getYRot() + turnVy, x = doneX ? toX : p.getXRot() + turnVx;
		if (doneY) turnVy = 0;
		if (doneX) turnVx = 0;
		p.setYRot(y);
		p.setYHeadRot(y);
		p.setXRot(x);
		yaw = Math.floorMod(Math.round((y + 180f) / 90f), 4);
		return doneY && doneX;
	}

	/**
	 * A point of this block its eyes can see (its middle, or the middle of a face turned its way, with nothing in
	 * between: grass and flowers count, a player's click would hit them first), or null: it's behind something.
	 */
	Vec3 seePoint(ServerLevel level, BlockPos pos) {
		Vec3 eye = p.getEyePosition(), mid = Vec3.atCenterOf(pos);
		if (clearTo(level, eye, mid, pos)) return mid;
		for (Direction d : Direction.values()) {
			Vec3 n = Vec3.atLowerCornerOf(d.getUnitVec3i());
			Vec3 face = mid.add(n.scale(0.45));
			if (eye.subtract(face).dot(n) <= 0) continue;                    // a face turned away from it
			if (clearTo(level, eye, face, pos)) return face;
		}
		return null;
	}

	private boolean clearTo(ServerLevel level, Vec3 eye, Vec3 to, BlockPos pos) {
		BlockHitResult r = level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,   // (Xen 2.0: it sees past small things: leaf litter, glow lichen, grass, torches)
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		return r.getType() == net.minecraft.world.phys.HitResult.Type.MISS || r.getBlockPos().equals(pos);
	}

	/** The first thing between its eyes and that block, or null. */
	private BlockHitResult inTheWay(ServerLevel level, BlockPos pos) {
		BlockHitResult r = level.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), Vec3.atCenterOf(pos),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));   // (small things don't count)
		return r.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !r.getBlockPos().equals(pos) ? r : null;
	}

	/** Ground, rock, ore, plants: what a player digs through to get at something (never what someone built: planks, cobblestone, glass...). */
	static boolean natural(BlockState s) {
		String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return n.endsWith("_ore") || n.endsWith("_leaves") || n.equals("stone") || n.equals("deepslate") || n.equals("tuff") || n.equals("granite")
				|| n.equals("diorite") || n.equals("andesite") || n.equals("calcite") || n.equals("dripstone_block") || n.equals("netherrack")
				|| n.equals("basalt") || n.equals("blackstone") || n.equals("end_stone") || n.equals("dirt") || n.equals("grass_block")
				|| n.equals("coarse_dirt") || n.equals("rooted_dirt") || n.equals("podzol") || n.equals("mycelium") || n.equals("sand")
				|| n.equals("red_sand") || n.equals("gravel") || n.equals("clay") || n.equals("snow") || n.equals("snow_block") || n.equals("mud")
				|| n.equals("sandstone") || n.equals("red_sandstone") || n.equals("terracotta") || n.endsWith("_terracotta") && !n.contains("glazed")
				|| n.equals("moss_block") || n.equals("soul_sand") || n.equals("soul_soil") || n.equals("magma_block")
				|| s.canBeReplaced() && s.getFluidState().isEmpty();
	}

	/** Right by its feet (the blocks around and under where it stands): placed by feel, the way a player bridges and builds up. */
	private boolean byFeet(BlockPos pos) {
		BlockPos f = p.blockPosition();
		int dx = pos.getX() - f.getX(), dy = pos.getY() - f.getY(), dz = pos.getZ() - f.getZ();
		return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= -1 && dy <= 1;
	}

	void look() {
		if (aim != null) return;                                      // eyes on the block it's mining
		if (holdAt != null) {                                         // eyes a moment on what it just put down or used
			boolean moving = current == Action.FORWARD || current == Action.BACK || current == Action.LEFT || current == Action.RIGHT || current == Action.JUMP;
			if (p.tickCount <= holdUntil && !moving && watching == null) {
				turnToward(holdAt, 30f, 25f);
				return;
			}
			holdAt = null;
		}
		if (glance != null && current == Action.IDLE && watching == null) {
			Vec3 d = glance.subtract(p.getEyePosition());
			float toY = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), toX = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
			float y = p.getYRot() + net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(toY - p.getYRot()), -9f, 9f);
			float x = p.getXRot() + net.minecraft.util.Mth.clamp(toX - p.getXRot(), -6f, 6f);
			p.setYRot(y);
			p.setYHeadRot(y);
			p.setXRot(x);
			yaw = Math.floorMod(Math.round((y + 180f) / 90f), 4);
			return;
		}
		if (watching != null && watching.isAlive() && watching.level() == p.level()) {
			if (p.companion != null && !p.companion.fightingNow()) {               // someone it notices: its head turns there (in a fight, eyes on them)
				turnToward(watching.getEyePosition(), 30f, 25f);
				return;
			}
			Vec3 d = watching.getEyePosition().subtract(p.getEyePosition());
			float yRot = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
			p.setYRot(yRot);
			p.setYHeadRot(yRot);
			p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
			return;
		}
		float yRot = YAW[yaw];
		if (steer != null && (current == Action.FORWARD || current == Action.JUMP)) {
			double dx = steer.x - p.getX(), dz = steer.z - p.getZ();
			float to = (float) Math.toDegrees(Math.atan2(-dx, dz));
			if (Math.hypot(dx, dz) > 0.15 && Math.abs(net.minecraft.util.Mth.wrapDegrees(to - YAW[yaw])) < 50) yRot = to;   // (a little, never sideways)
		}
		p.setYRot(yRot);
		p.setYHeadRot(yRot);
		p.setXRot((float) -Math.toDegrees(Perception.LOOK_PITCH[pitch + 1]));
	}

	BlockPos target() {
		if (aim != null) return aim;
		BlockPos feet = p.blockPosition();
		int[] f = Perception.forward(yaw);
		if (pitch < 0) return feet.below();
		return feet.offset(f[0], pitch > 0 ? 1 : 0, f[1]);
	}

	void start(Action a) {
		boolean move = a == Action.FORWARD || a == Action.BACK || a == Action.LEFT || a == Action.RIGHT;
		if (move && a == current && busy() && !pillar) {             // the same move again: the key just stays down
			ticks = 0;
			look();
			return;
		}
		stop();
		current = a;
		if (a != Action.FORWARD && a != Action.JUMP) steer = null;
		if (move || a == Action.JUMP) lastMove = a;
		ticks = 0;
		limit = 5;                                                    // a quarter of a second
		switch (a) {
			case FORWARD -> p.zza = 1;                                // keys go down now, for the next physics step
			case BACK -> p.zza = -1;
			case LEFT -> p.xxa = 1;
			case RIGHT -> p.xxa = -1;
			case TURN_LEFT -> { yaw = Math.floorMod(yaw - 1, 4); limit = 1; }
			case TURN_RIGHT -> { yaw = Math.floorMod(yaw + 1, 4); limit = 1; }
			case LOOK_UP -> { pitch = Math.min(1, pitch + 1); limit = 1; }
			case LOOK_DOWN -> { pitch = Math.max(-1, pitch - 1); limit = 1; }
			case JUMP -> {
				limit = 7;
				p.zza = 1;
				p.setJumping(true);
			}
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
		if (flyTarget != null) {
			fly();
			return;
		}
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
		if ((p.zza != 0 || p.xxa != 0) && edgeAhead()) {                   // a real drop that way: it stops at the edge
			p.xxa = p.zza = 0;
			p.setJumping(false);
			p.setSprinting(false);
			limit = ticks;
		}
		if (current != Action.ATTACK) look();
		if (!busy()) {
			p.xxa = p.zza = 0;
			p.setJumping(false);
		}
	}

	/**
	 * Would the keys it's holding take it over an edge with a real drop (more than 4 blocks, no water below)? A player
	 * looks where they walk: it stops there. (The walker has its own guard; this is for plain steps and strafes.)
	 */
	private boolean edgeAhead() {
		if (!p.onGround() || p.isInWater() || p.isCreative() || p.isSpectator()) return false;
		double yaw = Math.toRadians(p.getYRot());
		double dx = p.xxa * Math.cos(yaw) - p.zza * Math.sin(yaw), dz = p.zza * Math.cos(yaw) + p.xxa * Math.sin(yaw);
		double len = Math.hypot(dx, dz);
		if (len < 1e-3) return false;
		var lv = p.level();
		BlockPos.MutableBlockPos q = BlockPos.containing(p.getX() + dx / len * 0.8, p.getY() + 0.01, p.getZ() + dz / len * 0.8).mutable();
		for (int k = 0; k < 6; k++) {
			var st = lv.getBlockState(q);
			if (!st.getFluidState().isEmpty() || !st.getCollisionShape(lv, q).isEmpty()) return false;
			q.move(net.minecraft.core.Direction.DOWN);
		}
		return true;
	}

	void stop() {
		pillar = false;
		aim = null;
		aimPoint = null;
		turning = false;
		if (flyTarget != null) flyToward(null);
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
		if (state.isAir() || c == Blocks.AIR && state.getShape(level, pos).isEmpty() || c == Blocks.LAVA || c == Blocks.WATER || c == Blocks.BEDROCK
				|| state.getDestroySpeed(level, pos) < 0) {                   // (grass and flowers it can break: they have a shape)
			limit = 2;                                                // nothing to mine there
			return;
		}
		String unsafe = p.companion == null ? null : p.companion.choices.unsafeToBreak(pos);
		if (unsafe != null) {                                         // Xen 2.0's doubter: a hard no (lava behind it, a drop under its feet)
			if (!pos.equals(cantMine)) p.companion.journal("chooses", "mine " + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + ": no | doubter: " + unsafe);
			cantMine = pos.immutable();
			limit = 2;
			return;
		}
		if (holdsSomeone(level, pos, p)) {                             // someone's standing on it: a player never digs out the ground under a friend
			cantMine = pos.immutable();
			limit = 2;
			return;
		}
		if (outOfSight(level, pos)) {                                  // (it moved, or something came between: not through it)
			cantMine = pos.immutable();
			cantMineUnseen = true;
			limit = 2;
			return;
		}
		selectBestTool(state);
		if (tooSlow(state.getDestroyProgress(p, level, pos))) {       // more than 10 seconds with what it has: not worth it
			cantMine = pos.immutable();
			limit = 2;
			return;
		}
		digging = pos;
		digSlot = p.getInventory().getSelectedSlot();
		digItem = p.getInventory().getItem(digSlot).getItem();
		progress = 0;
		limit = 200;                                                  // give up after 10 seconds
		p.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, face(),
				level.getMaxY(), 0);
		if (level.getBlockState(pos).isAir()) {                       // broke instantly
			digging = null;
			limit = 2;
		}
	}

	/**
	 * More than 10 seconds to break with the best it has, even standing on the ground (in the air or under water a
	 * player digs 5 times slower, and it can get down first)? In creative everything breaks at once.
	 */
	private boolean tooSlow(float progressPerTick) {
		return !p.isCreative() && progressPerTick * (p.onGround() ? 1 : 5) * (p.isUnderWater() ? 5 : 1) < 1f / 200;
	}

	/**
	 * Is the block it's digging out of its reach now, or out of its sight (something solid between its eyes and every
	 * face it could see)? A player's crosshair can only be on a block it sees, within reach. (A builder boxed in by what
	 * it built, after trying from everywhere, digs anyway.)
	 */
	private boolean outOfSight(ServerLevel level, BlockPos pos) {
		if (p.isCreative()) return false;
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > p.blockInteractionRange() + 0.5) return true;
		return !digBlind && seePoint(level, pos) == null;
	}

	/** Packing up its own bed in the morning: the one time it breaks a bed. */
	boolean mayBreakBed;

	private void keepMining() {
		if (turning && aim != null && aimPoint != null) {                  // eyes to the block first, then the pickaxe
			if (turnToward(aimPoint, 40f, 30f)) {
				turning = false;
				startMining();
			}
			return;
		}
		if (digging == null) return;
		ServerLevel level = (ServerLevel) p.level();
		BlockState state = level.getBlockState(digging);
		if (state.isAir()) {
			digging = null;
			limit = ticks;
			return;
		}
		if (digSlot >= 0 && p.getInventory().getItem(digSlot).getItem() != digItem) {   // (something new in that slot, say a sword it was just handed: the right tool again)
			selectBestTool(state);
			digSlot = p.getInventory().getSelectedSlot();
			digItem = p.getInventory().getItem(digSlot).getItem();
		}
		if (digSlot >= 0 && p.getInventory().getSelectedSlot() != digSlot) p.getInventory().setSelectedSlot(digSlot);   // (the tool it picked, to the end)
		if (ticks % 4 == 0 && outOfSight(level, digging)) {                  // like a player: it digs what its crosshair is on, nothing behind a wall
			cantMine = digging.immutable();
			cantMineUnseen = true;
			p.gameMode.handleBlockBreakAction(digging, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 0);
			digging = null;
			limit = ticks;
			return;
		}
		progress += state.getDestroyProgress(p, level, digging);
		Compat.swing(p);
		if (ticks > 60 && progress < 0.15f) {                                // (swimming, in the air: getting nowhere) it stops, and tries again from better footing
			cantMine = digging.immutable();
			cantMineUnseen = true;                                           // (not from here: from somewhere else it could)
			p.gameMode.handleBlockBreakAction(digging, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 0);
			digging = null;
			limit = ticks;
			return;
		}
		if (progress >= 1f) {
			String broke = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			if (p.companion != null && broke.endsWith("_ore")) {
				p.companion.skills.practice(Skills.MINE, 0.02f);
				p.companion.lessons.found(broke, digging.getY());                            // where it finds ore: what it comes to believe
			}
			p.gameMode.handleBlockBreakAction(digging, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, face(),
					level.getMaxY(), 0);
			digging = null;
			limit = ticks;
		}
	}

	private Direction face() {
		if (aim != null) {                                            // the side of the block that faces its eyes
			Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(aim));
			double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
			return ay >= ax && ay >= az ? (d.y > 0 ? Direction.UP : Direction.DOWN)
					: ax >= az ? (d.x > 0 ? Direction.EAST : Direction.WEST) : (d.z > 0 ? Direction.SOUTH : Direction.NORTH);
		}
		if (pitch < 0) return Direction.UP;
		return switch (yaw) {
			case 0 -> Direction.SOUTH;                                // facing north, it hits the south face
			case 1 -> Direction.WEST;
			case 2 -> Direction.NORTH;
			default -> Direction.EAST;
		};
	}

	/** How fast the best tool in its hotbar breaks a block (a fraction of it per tick). */
	/** How fast it digs this with its best tool, standing on the ground (per tick; 1 = at once). */
	float digSpeed(BlockState state, ServerLevel level, BlockPos pos) {
		return bestSpeed(state, level, pos) * (p.onGround() ? 1 : 5) * (p.isUnderWater() ? 5 : 1);
	}

	/** Is it digging right now? */
	boolean isMining() {
		return busy() && current == Action.MINE;
	}

	private float bestSpeed(BlockState state, ServerLevel level, BlockPos pos) {
		Inventory inv = p.getInventory();
		int was = inv.getSelectedSlot(), slot = bestSlot(state);
		if (slot >= 9) {                                                          // (a tool in its backpack: held for a moment to see, then back)
			ItemStack held = inv.getItem(was), tool = inv.getItem(slot);
			inv.setItem(was, tool);
			inv.setItem(slot, held);
			float f = state.getDestroyProgress(p, level, pos);
			inv.setItem(slot, tool);
			inv.setItem(was, held);
			return f;
		}
		if (slot >= 0) inv.setSelectedSlot(slot);
		float f = state.getDestroyProgress(p, level, pos);
		inv.setSelectedSlot(was);
		return f;
	}

	/** The slot (0-35) of the tool that breaks this fastest, better than a bare hand (not a sword, unless cobweb); -1: none. */
	private int bestSlot(BlockState state) {
		Inventory inv = p.getInventory();
		boolean cobweb = state.is(net.minecraft.world.level.block.Blocks.COBWEB) || state.is(net.minecraft.world.level.block.Blocks.BAMBOO);
		int best = -1;
		float speed = 1.0001f;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack st = inv.getItem(slot);
			if (st.isEmpty() || isSword(st) && !cobweb) continue;
			float sp = st.getDestroySpeed(state);
			if (sp > speed || sp == speed && best >= 9 && slot < 9) {
				speed = sp;
				best = slot;
			}
		}
		return best;
	}

	/** The hotbar slot of the tool it's digging with (it keeps to it while the block breaks). */
	private int digSlot = -1;
	private net.minecraft.world.item.Item digItem;

	/** Mid-dig, it holds the tool it picked for the block (what others see it hold: something else this tick switched away). */
	void keepTool() {
		if (digging != null && digSlot >= 0 && p.getInventory().getSelectedSlot() != digSlot) p.getInventory().setSelectedSlot(digSlot);
	}

	/**
	 * The right tool for the job, like a player: the one that breaks this fastest, from anywhere in its bag (a tool in
	 * the backpack goes to its hotbar first). A sword is for fighting (on blocks it only wears out), and when no tool
	 * beats a bare hand it doesn't dig with its tools either: an empty hand, or something that isn't a tool.
	 */
	private void selectBestTool(BlockState state) {
		Inventory inv = p.getInventory();
		int best = bestSlot(state);
		if (best >= 9) best = toHotbar(best);
		if (best >= 0) {
			inv.setSelectedSlot(best);
			return;
		}
		if (!inv.getItem(inv.getSelectedSlot()).isDamageableItem()) return;           // (a block, food, nothing in hand: fine)
		for (int slot = 0; slot < 9; slot++) if (inv.getItem(slot).isEmpty()) {
			inv.setSelectedSlot(slot);
			return;
		}
		for (int slot = 0; slot < 9; slot++) if (!inv.getItem(slot).isDamageableItem()) {
			inv.setSelectedSlot(slot);
			return;
		}
	}

	private static boolean isSword(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_sword");
	}

	/** A tool from its backpack onto its hotbar (an empty slot, else one without a tool; never the block slot, 8). Its new slot. */
	private int toHotbar(int from) {
		Inventory inv = p.getInventory();
		int to = -1;
		for (int s = 0; s < 8 && to < 0; s++) if (inv.getItem(s).isEmpty()) to = s;
		for (int s = 7; s >= 0 && to < 0; s--) if (!inv.getItem(s).isDamageableItem()) to = s;
		if (to < 0) to = 7;
		ItemStack moving = inv.getItem(from), there = inv.getItem(to);
		inv.setItem(to, moving);
		inv.setItem(from, there);
		return to;
	}

	/** The last block it gave up on: it would take too long with what it has (or can't be broken). */
	BlockPos cantMine;

	/** Would it take more than 10 seconds to break with the best it has? */
	boolean tooSlowToMine(BlockPos pos) {
		ServerLevel level = (ServerLevel) p.level();
		BlockState state = level.getBlockState(pos);
		return state.getDestroySpeed(level, pos) < 0 || tooSlow(bestSpeed(state, level, pos));
	}

	/** Is it mining that very block right now? */
	boolean digging(BlockPos pos) {
		return digging != null && digging.equals(pos) || aim != null && aim.equals(pos) && current == Action.MINE;
	}

	/** Mine a block it can reach by looking at it (not just ahead or under its feet): for staircases. */
	/**
	 * Is someone else (a player or a Xen) standing on this block, or on its edge? Then it's their ground: digging it out
	 * drops them (into a cave, into lava). A player doesn't do that to someone, so it doesn't either.
	 */
	static boolean holdsSomeone(ServerLevel level, BlockPos pos, net.minecraft.world.entity.player.Player self) {
		double top = pos.getY() + 1;
		for (var o : level.players()) {
			if (o == self || o.isSpectator() || !o.isAlive() || o.distanceToSqr(Vec3.atCenterOf(pos)) > 16) continue;
			var box = o.getBoundingBox();
			if (box.minY >= top - 0.05 && box.minY <= top + 0.6 && box.maxX > pos.getX() && box.minX < pos.getX() + 1
					&& box.maxZ > pos.getZ() && box.minZ < pos.getZ() + 1) return true;
		}
		return false;
	}

	/** This dig may go on without seeing the block (a builder boxed in by its own walls: set from blindOk when it starts). */
	private boolean digBlind;

	boolean mine(BlockPos pos) {
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > p.blockInteractionRange()) return false;
		if (!mayBreakBed && p.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {   // (a bed: slept in, never dug through)
			cantMine = pos.immutable();
			cantMineUnseen = false;
			return false;
		}
		ServerLevel level = (ServerLevel) p.level();
		if (holdsSomeone(level, pos, p)) {                                  // (someone stands on it)
			cantMine = pos.immutable();
			return false;
		}
		BlockState state = level.getBlockState(pos);
		cantMineUnseen = false;
		if (state.getDestroySpeed(level, pos) < 0 || tooSlow(bestSpeed(state, level, pos))) {   // bedrock, or far too slow
			cantMine = pos.immutable();
			return false;
		}
		BlockPos target = pos;
		Vec3 point = seePoint(level, pos);
		if (point == null && !blindOk) {                                   // behind something: what's in the way first, as a player digs to it
			BlockHitResult in = inTheWay(level, pos);
			BlockState bs = in == null ? null : level.getBlockState(in.getBlockPos());
			if (in == null || !natural(bs) || p.getEyePosition().distanceTo(Vec3.atCenterOf(in.getBlockPos())) > p.blockInteractionRange()
					|| bs.getDestroySpeed(level, in.getBlockPos()) < 0 || tooSlow(bestSpeed(bs, level, in.getBlockPos()))) {
				cantMine = pos.immutable();                                    // (never through a wall someone built: from elsewhere, or not at all)
				cantMineUnseen = true;
				return false;
			}
			target = in.getBlockPos();
			point = in.getLocation();
		}
		if (point == null) point = Vec3.atCenterOf(pos);
		stop();
		digBlind = blindOk;
		aim = target.immutable();
		aimPoint = point;
		current = Action.MINE;
		ticks = 0;
		if (turnToward(point, 12f, 12f)) {                                // already looking about there: at it
			limit = 5;
			startMining();
		} else {                                                           // it turns to it first (a few ticks), then digs
			turning = true;
			limit = 24;
		}
		return true;
	}

	// -------------------------------------------------------------------------------- placing
	private void place() {
		ServerLevel level = (ServerLevel) p.level();
		BlockPos pos = target();
		BlockState here = level.getBlockState(pos);
		if (!here.canBeReplaced()) return;
		int slot = spareSlot();
		if (slot < 0) return;
		for (Direction d : Direction.values()) {
			BlockPos against = pos.relative(d);
			if (!level.getBlockState(against).isCollisionShapeFullBlock(level, against)) continue;
			Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
			if (!canSee(level, against, d.getOpposite(), hit) && !byFeet(pos)) continue;   // only a face it can see (or right by its feet)
			p.getInventory().setSelectedSlot(slot);
			ItemStack stack = p.getInventory().getSelectedItem();
			face(hit);
			p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, d.getOpposite(), against, false));
			Compat.swing(p);
			holdLook(hit, 8);
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
				x -> x.isAlive() && (p.companion != null ? p.companion.hostile(x) : x instanceof Enemy))) {
			Vec3 d = e.position().add(0, e.getBbHeight() / 2, 0).subtract(eye);
			double flat = Math.hypot(d.x, d.z);
			if (flat > 0.01 && (d.x * f[0] + d.z * f[1]) / flat < 0.5) continue;      // must be in front (within 60 deg)
			double dist = p.distanceTo(e);
			if (dist <= reach + 0.5 && dist < bestDist && p.hasLineOfSight(e)) {
				best = e;
				bestDist = dist;
			}
		}
		if (best != null) {
			Vec3 d = best.getEyePosition().subtract(eye);
			p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
			p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
			LivingEntity target = best;
			XenMod.INSTANCE.gameplayLog.quietly(() -> XenMod.INSTANCE.gameplayLog.swing(p, target));
			p.attack(best);                                            // attack, then swing: a swing resets the charge
			Compat.swing(p);
		}                                                              // nothing there: no swinging at the air
	}

	// -------------------------------------------------------------------------------- eating
	private void startEating() {
		int slot = p.getHealth() < p.getMaxHealth() ? findHotbar(Hands::goldenApple) : -1;   // hurt: a golden apple, hungry or not
		if (slot < 0) {
			if (!p.getFoodData().needsFood()) {
				limit = 1;
				return;
			}
			slot = findHotbar(s -> s.has(DataComponents.FOOD));
		}
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
		return placeAt(pos, "any");
	}

	/** The blocks each building material means (the rest of what it can place comes after). */
	private static Set<String> favorite(String material) {
		return switch (material) {
			case "stone" -> Set.of("cobblestone", "cobbled_deepslate");
			case "earth" -> Set.of("dirt");
			default -> PLACEABLE;
		};
	}

	/** Place a block it carries at pos, its favorite material first. */
	boolean placeAt(BlockPos pos, String material) {
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
		Set<String> liked = favorite(material);
		int slot = material.equals("any") ? spareSlot() : findHotbar(s -> liked.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
		if (slot < 0) slot = spareSlot();
		if (slot < 0) {
			cantPlace = "no blocks left";
			return false;
		}
		boolean solid = false;
		for (int pass = 0; pass < 2; pass++) {                              // a face it can see first; by feel only right by its feet
			if (pass == 1 && !byFeet(pos) && !blindOk) break;
			for (Direction d : Direction.values()) {
				BlockPos against = pos.relative(d);
				if (!clickable(level, against)) continue;                      // (any block with a face to click: a slab, stairs, a fence, leaves too)
				solid = true;
				Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
				if (pass == 0 && !canSee(level, against, d.getOpposite(), hit)) continue;
				stop();
				p.getInventory().setSelectedSlot(slot);
				face(hit);
				boolean sneak = interactive(level.getBlockState(against).getBlock());   // (a chest, a table: sneak, or the click opens it)
				if (sneak) p.setShiftKeyDown(true);
				p.gameMode.useItemOn(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(hit, d.getOpposite(), against, false));
				if (sneak) p.setShiftKeyDown(false);
				Compat.swing(p);
				holdLook(hit, 8);                                              // (its eyes stay on it a moment, as a player's do)
				current = Action.PLACE;
				ticks = 0;
				limit = 4;
				cantPlace = level.getBlockState(pos).canBeReplaced() ? "the block didn't stay" : "";
				return cantPlace.isEmpty();
			}
		}
		cantPlace = solid ? "it can't see that spot from here" : "nothing solid to place it against";
		return false;
	}

	/** A block a click can put something against: anything solid with a shape (not air, water, grass or snow it'd replace). */
	static boolean clickable(ServerLevel level, BlockPos at) {
		BlockState st = level.getBlockState(at);
		return !st.isAir() && !st.canBeReplaced() && !st.getShape(level, at).isEmpty();
	}

	/** Blocks a plain right-click uses instead of building against (a player sneaks to put a block on them). */
	static boolean interactive(net.minecraft.world.level.block.Block b) {
		return b instanceof net.minecraft.world.level.block.EntityBlock || b instanceof net.minecraft.world.level.block.CraftingTableBlock
				|| b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.TrapDoorBlock
				|| b instanceof net.minecraft.world.level.block.FenceGateBlock || b instanceof net.minecraft.world.level.block.ButtonBlock
				|| b instanceof net.minecraft.world.level.block.LeverBlock || b instanceof net.minecraft.world.level.block.DiodeBlock
				|| b instanceof net.minecraft.world.level.block.AnvilBlock || b instanceof net.minecraft.world.level.block.BedBlock;
	}

	/**
	 * Place a block, and if there's nothing at all to put it against, a block under it first (as a player builds up
	 * from the ground), when {@code underOk} says the spot below may be filled. True when it put something down.
	 */
	boolean placeSupported(BlockPos pos, String material, java.util.function.Predicate<BlockPos> underOk) {
		if (placeAt(pos, material)) return true;
		if (!cantPlace.startsWith("nothing solid")) return false;
		ServerLevel level = (ServerLevel) p.level();
		BlockPos under = pos.below();
		String why = cantPlace;
		for (int down = 0; down < 3; down++, under = under.below()) {         // (down to the ground: the lowest free spot that has a face to go against)
			if (!level.getBlockState(under).canBeReplaced() || !underOk.test(under)) break;
			if (placeAt(under, material)) {
				cantPlace = "";
				return true;
			}
			if (!cantPlace.startsWith("nothing solid")) break;
		}
		cantPlace = why;
		return false;
	}

	/**
	 * Place an item it carries at pos by right-clicking a face of the block {@code against}, looking the way
	 * {@code facing} says (0-3, for parts that face the way you look, like repeaters; -1 = don't care).
	 */
	/** placeItem, only on a face it can see (a table, a chest: things people watch it put down). */
	boolean placeSeen(BlockPos pos, java.util.function.Predicate<ItemStack> item, BlockPos against, Direction side, int facing) {
		strictSight = true;
		try {
			return placeItem(pos, item, against, side, facing);
		} finally {
			strictSight = false;
		}
	}

	boolean placeItem(BlockPos pos, java.util.function.Predicate<ItemStack> item, BlockPos against, Direction side, int facing) {
		ServerLevel level = (ServerLevel) p.level();
		if (!level.getBlockState(pos).canBeReplaced()) {
			cantPlace = "already a block there";
			return false;
		}
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > p.blockInteractionRange()) {
			cantPlace = "too far to reach";
			return false;
		}
		int slot = findHotbar(item);
		if (slot < 0) {
			cantPlace = "it has run out of something it needs";
			return false;
		}
		Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
		if ((strictSight || !blindOk && !byFeet(pos)) && !canSee(level, against, side, hit)) {   // like a player: only where it can see the face it clicks
			cantPlace = "it can't see that spot from here";
			return false;
		}
		stop();
		p.getInventory().setSelectedSlot(slot);
		face(hit);
		if (facing >= 0) {
			p.setYRot(YAW[facing]);
			p.setYHeadRot(YAW[facing]);
		}
		var clicked = level.getBlockState(against).getBlock();                // a chest, a table, a door...: sneak, or the click opens it
		boolean sneak = clicked instanceof net.minecraft.world.level.block.EntityBlock || clicked instanceof net.minecraft.world.level.block.CraftingTableBlock
				|| clicked instanceof net.minecraft.world.level.block.DoorBlock || clicked instanceof net.minecraft.world.level.block.TrapDoorBlock
				|| clicked instanceof net.minecraft.world.level.block.FenceGateBlock || clicked instanceof net.minecraft.world.level.block.ButtonBlock
				|| clicked instanceof net.minecraft.world.level.block.LeverBlock || clicked instanceof net.minecraft.world.level.block.DiodeBlock
				|| clicked instanceof net.minecraft.world.level.block.AnvilBlock;
		if (sneak) p.setShiftKeyDown(true);
		var result = p.gameMode.useItemOn(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, against, false));
		if (sneak) p.setShiftKeyDown(false);
		Compat.swing(p);
		holdLook(hit, 8);
		current = Action.PLACE;
		ticks = 0;
		limit = 4;
		cantPlace = level.getBlockState(pos).canBeReplaced() ? "it didn't stay (" + result + ", clicked " + level.getBlockState(against).getBlock()
				+ " " + side + ", holding " + p.getInventory().getSelectedItem() + ")" : "";
		return cantPlace.isEmpty();
	}

	/**
	 * Could a player standing here click that face? Its eyes on the right side of it, and nothing solid between (grass
	 * and flowers don't count: a click goes through them).
	 */
	boolean canSee(ServerLevel level, BlockPos against, Direction side, Vec3 hit) {
		return canSee(level, p.getEyePosition(), against, side, hit);
	}

	/** The same, from eyes at that point (a spot it might stand on). */
	boolean canSee(ServerLevel level, Vec3 eye, BlockPos against, Direction side, Vec3 hit) {
		Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
		if (eye.subtract(hit).dot(normal) <= 0.01) return false;               // it's behind that face
		Vec3 to = hit.subtract(normal.scale(0.05));                           // (just into the block)
		BlockHitResult seen = level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		return seen.getType() == net.minecraft.world.phys.HitResult.Type.MISS || seen.getBlockPos().equals(against);
	}

	/**
	 * A bucket, like a player: it looks at the spot and right-clicks (a water bucket pours where it looks, onto the
	 * face of that block; an empty one scoops up the water it looks at). False if it has no such bucket.
	 */
	boolean bucket(Vec3 lookAt, java.util.function.Predicate<ItemStack> which) {
		int slot = findHotbar(which);
		if (slot < 0) return false;
		stop();
		p.getInventory().setSelectedSlot(slot);
		face(lookAt);
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		current = Action.PLACE;
		ticks = 0;
		limit = 4;
		return true;
	}

	/**
	 * Pour a water (or lava) bucket into that block, looking at it, as a player who aims right does (the bucket empties
	 * there; in survival an empty bucket is left in its hand). False if it has no full bucket or it didn't go.
	 */
	boolean pour(BlockPos into, Direction face) {
		int slot = findHotbar(s -> s.getItem() instanceof net.minecraft.world.item.BucketItem && s.getItem() != net.minecraft.world.item.Items.BUCKET);
		if (slot < 0) return false;
		stop();
		p.getInventory().setSelectedSlot(slot);
		ItemStack stack = p.getInventory().getSelectedItem();
		Vec3 at = Vec3.atCenterOf(into.relative(face.getOpposite())).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
		face(at);
		var bucket = (net.minecraft.world.item.BucketItem) stack.getItem();
		boolean ok = bucket.emptyContents(p, p.level(), into, new BlockHitResult(at, face, into.relative(face.getOpposite()), false));
		if (ok) {
			bucket.checkExtraContent(p, p.level(), stack, into);
			if (!p.isCreative()) p.getInventory().setItem(slot, net.minecraft.world.item.BucketItem.getEmptySuccessItem(stack, p));
		}
		Compat.swing(p);
		current = Action.PLACE;
		ticks = 0;
		limit = 4;
		return ok;
	}

	/** Right-click a block holding something (a plant into a flower pot). False if it has nothing like that. */
	boolean useWith(BlockPos pos, java.util.function.Predicate<ItemStack> item) {
		int slot = findHotbar(item);
		if (slot < 0) return false;
		stop();
		p.getInventory().setSelectedSlot(slot);
		return use(pos);
	}

	/** Could it right-click that block from here: within reach, and a face of it in sight (not through a wall)? */
	boolean canClick(BlockPos pos) {
		if (p.isCreative()) return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= p.blockInteractionRange();
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= p.blockInteractionRange() - 0.3 && seePoint((ServerLevel) p.level(), pos) != null;
	}

	/**
	 * Right-click a block, like a player (a repeater: one more tick of delay): only one it can see, within reach. False:
	 * it couldn't (behind something, too far): nothing happened.
	 */
	boolean use(BlockPos pos) {
		ServerLevel level = (ServerLevel) p.level();
		Vec3 hit = p.isCreative() ? Vec3.atCenterOf(pos).add(0, 0.4, 0) : seePoint(level, pos);
		if (hit == null || p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > p.blockInteractionRange()) return false;
		face(hit);
		p.gameMode.useItemOn(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, sideToward(pos, hit), pos, false));
		Compat.swing(p);
		holdLook(hit, 6);
		current = Action.PLACE;
		ticks = 0;
		limit = 3;
		return true;
	}

	/** The face of the block that point is on (the one its eyes see). */
	private static Direction sideToward(BlockPos pos, Vec3 hit) {
		Vec3 d = hit.subtract(Vec3.atCenterOf(pos));
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		return ay >= ax && ay >= az ? (d.y >= 0 ? Direction.UP : Direction.DOWN) : ax >= az ? (d.x > 0 ? Direction.EAST : Direction.WEST) : (d.z > 0 ? Direction.SOUTH : Direction.NORTH);
	}

	/** Do nothing for a few ticks. */
	void pause(int n) {
		stop();
		current = Action.IDLE;
		ticks = 0;
		limit = n;
	}

	/** Hold its best weapon (a sword, else an axe), like a player about to fight. */
	void ready() {
		ready(false);
	}

	/** The same, but an axe first when axeFirst (an axe hit disables a raised shield). */
	void ready(boolean axeFirst) {
		if (digging != null) return;                                  // (a block half mined: it finishes it with the tool it picked)
		String first = axeFirst ? "_axe" : "_sword", second = axeFirst ? "_sword" : "_axe";
		int weapon = findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(first));
		if (weapon < 0) weapon = findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(second));
		if (weapon >= 0 && weapon != p.getInventory().getSelectedSlot()) p.getInventory().setSelectedSlot(weapon);
	}

	/** A spear's jab reaches from 2 to 4.5 blocks (a sword's 3, any closer); a mace smashes after a fall of more than 1.5. */
	static final double SPEAR_MIN = 2.0, SPEAR_REACH = 4.5;

	/** Its weapon for a foe d blocks off: out of a sword's reach and within a spear's, the spear; else as ready(axeFirst). */
	void ready(boolean axeFirst, double d) {
		if (digging != null) return;
		if (d > 3.0 && d <= SPEAR_REACH && readySpear()) return;
		ready(axeFirst);
	}

	boolean readySpear() {
		int slot = findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_spear"));
		if (slot < 0) return false;
		p.getInventory().setSelectedSlot(slot);
		return true;
	}

	boolean holdingSpear() {
		return BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath().endsWith("_spear");
	}

	/** Does it carry this item (anywhere in its bag)? */
	boolean carries(String id) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id)) return true;
		}
		return false;
	}

	/** Throw something it carries this way (yaw and pitch in the game's degrees: an ender pearl back to land). False if it has none. */
	boolean throwAt(String id, float yawDeg, float pitchDeg) {
		int slot = findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id));
		if (slot < 0) return false;
		stop();
		lowerShield();
		p.getInventory().setSelectedSlot(slot);
		p.setYRot(yawDeg);
		p.setYHeadRot(yawDeg);
		p.setXRot(pitchDeg);
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		current = Action.PLACE;
		ticks = 0;
		limit = 2;
		return true;
	}

	/** Throw something it carries straight down at its feet (a wind charge: it goes up). False if it has none. */
	boolean throwDown(String id) {
		int slot = findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id));
		if (slot < 0) return false;
		stop();
		lowerShield();
		p.getInventory().setSelectedSlot(slot);
		p.setXRot(90f);                                                        // eyes on its feet, then right-click
		pitch = -1;
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		current = Action.PLACE;
		ticks = 0;
		limit = 2;
		return true;
	}

	boolean holdingAxe() {
		return BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath().endsWith("_axe");
	}

	/** The last way it moved (walked, stepped back, strafed or jumped). */
	Action lastMove() {
		return lastMove;
	}

	/** Can it heal by eating now: a golden apple (any time), or food when hungry? */
	boolean canHeal() {
		return findHotbar(Hands::goldenApple) >= 0 || p.getFoodData().needsFood() && findHotbar(s -> s.has(DataComponents.FOOD)) >= 0;
	}

	private static boolean goldenApple(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return n.equals("golden_apple") || n.equals("enchanted_golden_apple");
	}

	/**
	 * Raise a shield it carries and hold it up (right mouse button on the off hand). A shield in its bag goes to the off
	 * hand first, like the swap-hands key. False if it has no shield.
	 */
	boolean raiseShield() {
		if (!isShield(p.getOffhandItem())) {
			if (isTotem(p.getOffhandItem()) && (p.getHealth() <= 10 || p.companion != null && p.companion.dangerous())) return false;   // the totem stays
			Inventory inv = p.getInventory();
			int slot = -1;
			for (int i = 0; i < 36 && slot < 0; i++) if (isShield(inv.getItem(i))) slot = i;
			if (slot < 0) return false;
			ItemStack off = p.getOffhandItem();
			p.setItemInHand(InteractionHand.OFF_HAND, inv.getItem(slot));
			inv.setItem(slot, off);
		}
		if (!p.isUsingItem()) p.gameMode.useItem(p, p.level(), p.getOffhandItem(), InteractionHand.OFF_HAND);
		return true;
	}

	/**
	 * Where a player keeps a shield: in the off hand, always (never in the sword hand). A shield in its bag or hotbar
	 * goes to the off hand when that's free or holds something less useful there (a totem stays when it's in danger);
	 * one that ended up in its main hand (picked up, just crafted) moves over too. A shield still raised with no fight
	 * comes down.
	 */
	void shieldToOffhand(boolean fighting) {
		if (!fighting) lowerShield();
		if (isShield(p.getOffhandItem())) return;
		ItemStack off = p.getOffhandItem();
		if (isTotem(off) && (p.getHealth() <= 10 || p.companion != null && p.companion.dangerous())) return;   // the totem stays
		Inventory inv = p.getInventory();
		int slot = -1;
		for (int i = 0; i < 36 && slot < 0; i++) if (isShield(inv.getItem(i))) slot = i;
		if (slot < 0) return;
		p.setItemInHand(InteractionHand.OFF_HAND, inv.getItem(slot));
		inv.setItem(slot, off);
	}

	/** Let go of the right mouse button (a raised shield comes down). */
	void lowerShield() {
		if (p.isUsingItem() && isShield(p.getUseItem())) p.releaseUsingItem();
	}

	private static boolean isShield(ItemStack s) {
		return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("shield");
	}

	/** Hit a creature in reach (the normal attack, with the normal cooldown), with its best weapon. */
	/** What it hit last, and when (dead soon after: it killed it, and what it drops is its own). */
	LivingEntity lastHit;
	long lastHitAt;

	void hit(LivingEntity e) {
		stop();
		lowerShield();
		ready(e.isBlocking(), p.distanceTo(e));                        // (an axe for a raised shield, the spear out of a sword's reach)
		if (p.fallDistance > 1.5 && e.getY() < p.getY()) readyMace();            // falling onto it: a mace smash, if it has one
		face(e.getEyePosition());
		XenMod.INSTANCE.gameplayLog.quietly(() -> XenMod.INSTANCE.gameplayLog.swing(p, e));
		p.attack(e);                                                   // attack, then swing: a swing resets the charge
		Compat.swing(p);
		lastHit = e;
		lastHitAt = p.level().getGameTime();
		current = Action.ATTACK;
		ticks = 0;
		limit = 2;
	}

	/** Place a sign it carries next to it and write on it (up to 4 short lines), like a player would. */
	boolean placeSign(String text) {
		int slot = findHotbar(s -> {
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			return n.endsWith("_sign") && !n.contains("hanging");
		});
		if (slot < 0) return false;
		ServerLevel level = (ServerLevel) p.level();
		BlockPos feet = p.blockPosition();
		for (int d = 0; d < 4; d++) {
			int[] f = Perception.forward((yaw + d) % 4);
			BlockPos pos = feet.offset(f[0], 0, f[1]), below = pos.below();
			if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(below).isCollisionShapeFullBlock(level, below)) continue;
			stop();
			p.getInventory().setSelectedSlot(slot);
			Vec3 hit = Vec3.atCenterOf(below).add(0, 0.5, 0);
			face(hit);
			p.gameMode.useItemOn(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, Direction.UP, below, false));
			Compat.swing(p);
			if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) return false;
			java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (String w : text.split(" ")) {                          // about 15 letters fit on a sign line
				if (line.length() + w.length() + 1 > 15 && line.length() > 0) {
					if (lines.size() == 4) break;
					lines.add(net.minecraft.network.chat.Component.literal(line.toString()));
					line.setLength(0);
				}
				line.append(line.length() > 0 ? " " : "").append(w);
			}
			if (lines.size() < 4 && line.length() > 0) lines.add(net.minecraft.network.chat.Component.literal(line.toString()));
			while (lines.size() < 4) lines.add(net.minecraft.network.chat.Component.empty());
			return Compat.signText(sign, lines);
		}
		return false;
	}

	// ------------------------------------------------------------------- totems, bows, throwing
	static boolean isTotem(ItemStack s) {
		return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("totem_of_undying");
	}

	/**
	 * A totem of undying in the off hand (the swap-hands key), where it saves its life if it would die. False if it has
	 * none. A player keeps one there when things get dangerous (low health, the End, lava, a big fall).
	 */
	boolean holdTotem() {
		if (isTotem(p.getOffhandItem())) return true;
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			if (!isTotem(inv.getItem(i))) continue;
			lowerShield();
			ItemStack off = p.getOffhandItem();
			p.setItemInHand(InteractionHand.OFF_HAND, inv.getItem(i));
			inv.setItem(i, off);
			return true;
		}
		return false;
	}

	static boolean isBow(ItemStack s) {
		return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("bow");
	}

	/** Arrows it carries (or it can shoot anyway, in creative). */
	int arrows() {
		if (p.isCreative()) return 64;
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			String k = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (k.equals("arrow") || k.equals("spectral_arrow") || k.equals("tipped_arrow")) n += inv.getItem(i).getCount();
		}
		return n;
	}

	boolean hasBow() {
		return findHotbar(Hands::isBow) >= 0 && arrows() > 0;
	}

	/** Draw its bow (the right mouse button, held). False without a bow and arrows. */
	boolean drawBow() {
		if (p.isUsingItem() && isBow(p.getUseItem())) return true;
		if (arrows() == 0) return false;
		int slot = findHotbar(Hands::isBow);
		if (slot < 0) return false;
		stop();
		lowerShield();
		p.getInventory().setSelectedSlot(slot);
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		return p.isUsingItem();
	}

	/** How long the bow has been drawn (a full draw is 20 ticks), 0 if it isn't. */
	int drawn() {
		return p.isUsingItem() && isBow(p.getUseItem()) ? p.getTicksUsingItem() : 0;
	}

	/** Point the mouse this way (degrees, like the game: yaw, and pitch with up negative). */
	void aim(float yRot, float xRot) {
		p.setYRot(yRot);
		p.setYHeadRot(yRot);
		p.setXRot(xRot);
		yaw = Math.floorMod(Math.round((yRot + 180f) / 90f), 4);
	}

	/** Let go of the right mouse button: the arrow flies where it looks. */
	void loose() {
		if (p.isUsingItem()) p.releaseUsingItem();
	}

	/**
	 * Where to point a fully drawn bow to hit a point (yaw, pitch in the game's degrees), working out the arrow's fall
	 * the way a player learns it: it flies at 3 blocks a tick, slows by 1% and falls 0.05 faster each tick. The low
	 * arc; null if it can't reach.
	 */
	static float[] bowAim(Vec3 eye, Vec3 target) {
		double dx = target.x - eye.x, dz = target.z - eye.z, flat = Math.hypot(dx, dz), dy = target.y - (eye.y - 0.1);
		float yRot = (float) Math.toDegrees(Math.atan2(-dx, dz));
		double best = Double.MAX_VALUE;
		float bestPitch = Float.NaN;
		for (double deg = -60; deg <= 60; deg += 0.25) {
			double a = Math.toRadians(deg), vx = 3 * Math.cos(a), vy = 3 * Math.sin(a), x = 0, y = 0;
			for (int t = 0; t < 120 && x < flat; t++) {
				double nx = x + vx, ny = y + vy;
				if (nx >= flat) {
					y = y + (ny - y) * (flat - x) / Math.max(1e-6, nx - x);
					x = flat;
					break;
				}
				x = nx;
				y = ny;
				vx *= 0.99;
				vy = vy * 0.99 - 0.05;
			}
			if (x < flat) continue;
			double err = Math.abs(y - dy);
			if (err < best) {
				best = err;
				bestPitch = (float) -deg;
			}
			if (y > dy && best < 0.5) break;                                     // (the low arc is enough)
		}
		return Float.isNaN(bestPitch) || best > 1.5 ? null : new float[] {yRot, bestPitch};
	}

	/** How many ticks an arrow takes to fly that far (to lead something moving). */
	static int flightTicks(double flat) {
		double x = 0, v = 3;
		int t = 0;
		while (x < flat && t < 100) {
			x += v;
			v *= 0.99;
			t++;
		}
		return t;
	}

	/** Throw or use what it holds at the air (an eye of ender, a wind charge, an ender pearl), looking this way. */
	boolean useAtAir(java.util.function.Predicate<ItemStack> item, float yRot, float xRot) {
		int slot = findHotbar(item);
		if (slot < 0) return false;
		stop();
		p.getInventory().setSelectedSlot(slot);
		aim(yRot, xRot);
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		current = Action.PLACE;
		ticks = 0;
		limit = 4;
		return true;
	}

	/** Hit anything in reach (a part of the Ender Dragon, an end crystal): the normal attack, with its best weapon. */
	void hitEntity(net.minecraft.world.entity.Entity e, Vec3 at) {
		stop();
		lowerShield();
		ready();
		face(at);
		XenMod.INSTANCE.gameplayLog.quietly(() -> XenMod.INSTANCE.gameplayLog.swing(p, e));
		p.attack(e);
		Compat.swing(p);
		current = Action.ATTACK;
		ticks = 0;
		limit = 2;
	}

	/** Its best weapon for a smash from above: a mace, if it has one (a player switches to it mid-fall). */
	boolean readyMace() {
		int slot = findHotbar(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("mace"));
		if (slot < 0) return false;
		p.getInventory().setSelectedSlot(slot);
		return true;
	}

	/** Toss items where it looks (a player's Q key); the other player picks them up. */
	void toss(ItemStack stack) {
		if (!Compat.drop(p, stack)) p.getInventory().add(stack);
		Compat.swing(p);
	}

	int hotbar(java.util.function.Predicate<ItemStack> want) {
		return findHotbar(want);
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
