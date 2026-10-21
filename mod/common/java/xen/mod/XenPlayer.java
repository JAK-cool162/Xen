package xen.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * Xen's body: a real server-side player. The server treats it like anyone else (player list, damage,
 * hunger, inventory, reach checks). With no game client to move it, it runs its own player physics.
 */
public class XenPlayer extends ServerPlayer {
	Companion companion;

	public XenPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	/** Knockback the server sent "to its client": applied at the start of its next tick, as a game client would. */
	private net.minecraft.world.phys.Vec3 push;
	private boolean addPush;

	void pushed(net.minecraft.world.phys.Vec3 motion, boolean add) {
		push = add && push != null ? push.add(motion) : motion;
		addPush = add;
	}

	@Override
	public void tick() {
		boolean minion = companion != null && companion.minion;
		if (minion && !((net.minecraft.server.level.ServerLevel) level()).isPositionEntityTicking(blockPosition())) return;   // nobody keeps it loaded: it waits, frozen
		if (push != null) {                                // hit or blown back: the push its client would feel
			if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} pushed {} (was moving {})", getName().getString(), push, getDeltaMovement());
			setDeltaMovement(addPush ? getDeltaMovement().add(push) : push);
			push = null;
		}
		if (level().getServer().getTickCount() % 10 == 0 && !minion) {   // (a minion doesn't load the world: others do)
			connection.resetPosition();
			level().getChunkSource().move(this);          // load the world around it, like a player
		}
		if (getAbilities().flying) {                        // flying (creative): space up, shift down, as a game client does
			double up = (jumping ? 1 : 0) - (isShiftKeyDown() ? 1 : 0);
			if (up != 0) setDeltaMovement(getDeltaMovement().add(0, up * getAbilities().getFlyingSpeed() * 3, 0));
		}
		super.tick();
		if (!minion) edgeGuard();
		double x = getX(), y = getY(), z = getZ();
		doTick();                                          // player physics a client would normally drive
		doCheckFallDamage(getX() - x, getY() - y, getZ() - z, onGround());   // and what the server does with a client's moves:
	}                                                      // falling counts (fall damage, and critical hits need it)

	/** It crouched itself at an edge (so it lets go once the edge is behind it), and when it last said so. */
	private boolean edgeCrouch;
	private long edgeSaidAt;

	/**
	 * Whatever moves it (a chore, a fight, its own way-finding): a deep drop right ahead that it didn't choose, and it
	 * crouches at the edge, as a player does (crouched, the game won't let it walk off). The drops its way-finder
	 * planned (a short fall, a leap, a water clutch) go on as planned.
	 */
	private void edgeGuard() {
		int drop = 0;
		int planned = companion == null ? -1 : companion.walker.plannedDrop();
		if (companion != null && onGround() && !isInWater() && !isInLava() && !getAbilities().flying && !isPassenger()) {
			var v = getDeltaMovement();
			double sx = v.x, sz = v.z;
			if (sx * sx + sz * sz < 0.0009 && zza != 0) {                         // (only just setting off: the way it faces)
				double yaw = Math.toRadians(getYRot());
				sx = -Math.sin(yaw) * Math.signum(zza);
				sz = Math.cos(yaw) * Math.signum(zza);
			}
			double len = Math.sqrt(sx * sx + sz * sz);
			if (len > 1e-3) for (double k : new double[] {0.45, 0.9}) {
				drop = Math.max(drop, dropAt(net.minecraft.core.BlockPos.containing(getX() + sx / len * k, getY() + 0.01, getZ() + sz / len * k)));
			}
		}
		boolean deep = (drop >= 6 || drop >= 4 && drop - 3 >= getHealth() - 2) && drop > planned + 1;   // (more than a few hearts, or what it has left; a drop its way-finder planned passes, not a deeper one)
		if (deep) {
			setShiftKeyDown(true);
			setJumping(false);
			edgeCrouch = true;
			long now = level().getGameTime();
			if (now - edgeSaidAt > 200) {
				edgeSaidAt = now;
				companion.journal("moves", "stops at the edge of a " + drop + "-block drop");
			}
		} else if (edgeCrouch) {
			edgeCrouch = false;
			setShiftKeyDown(false);
		}
	}

	/** How far it would fall stepping into this block (0: floor there, a wall, or water to land in). */
	private int dropAt(net.minecraft.core.BlockPos at) {
		var q = at.mutable();
		for (int k = 0; k < 40; k++) {
			var st = level().getBlockState(q);
			if (!st.getFluidState().isEmpty()) return 0;
			if (!st.getCollisionShape(level(), q).isEmpty()) return Math.max(0, k - 1);
			q.move(net.minecraft.core.Direction.DOWN);
		}
		return 40;
	}

	/**
	 * Riding: no game client moves its boat or horse (a player's client would), so the server does, and it steers by the
	 * keys ({@link Rider}). On its own feet nothing changes.
	 */
	@Override
	public boolean isClientAuthoritative() {
		return !isPassenger() && super.isClientAuthoritative();
	}

	/** Hit: how the one who hit it did it, at that very moment (a crit is decided right then, before they land). */
	@Override
	public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
		if (companion != null && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer by && source.getDirectEntity() == by) {
			companion.struckBy(by, amount);
		}
		float before = getHealth();
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt && companion != null && !isCreative()) {
			companion.aversions.hurt(source, amount);                          // (what hurt it: it remembers)
			companion.fighter.hurt(Math.max(0, before - getHealth()));         // (what the fight it's in is costing it)
			if (amount >= 1) companion.journalHurt(source, amount);
		}
		return hurt;
	}

	/**
	 * A message only for it: someone's /msg (a whisper). It hears it, and nobody else did (the game sends a whisper to
	 * the one it's for alone).
	 */
	@Override
	public void sendChatMessage(net.minecraft.network.chat.OutgoingChatMessage message, boolean filtered, net.minecraft.network.chat.ChatType.Bound bound) {
		super.sendChatMessage(message, filtered, bound);
		if (companion == null) return;
		try {
			if (!bound.chatType().is(net.minecraft.network.chat.ChatType.MSG_COMMAND_INCOMING)) return;
			String from = bound.name().getString(), text = message.content().getString();
			level().getServer().execute(() -> companion.whispered(from, text));
		} catch (RuntimeException e) {
			XenMod.LOG.debug("whisper: {}", e.toString());
		}
	}

	/**
	 * A line over its hotbar from the game ("You may not rest now; there are monsters nearby", "This bed is obstructed"):
	 * it reads it, as a player does, and knows why what it just tried didn't work.
	 */
	@Override
	public void sendSystemMessage(net.minecraft.network.chat.Component message, boolean overlay) {
		super.sendSystemMessage(message, overlay);
		if (companion == null || !overlay || message == null) return;
		companion.toldKey = message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : message.getString();
		companion.toldAt = level().getGameTime();
	}

	/** Its jump key, for the gameplay log (a game client would send it). */
	boolean jumpKey() {
		return jumping;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (companion != null) companion.died(source);
	}
}
