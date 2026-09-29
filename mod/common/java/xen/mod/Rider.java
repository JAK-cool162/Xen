package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.UUID;

/**
 * Boats and horses, the way a player uses them.
 * <ul>
 *   <li><b>Your boat</b>: "get in my boat" (or "hop on", "ride with me"): it walks over and gets in (right-click, like
 *   anyone). While its friend is in, it stays in; when they get out, it gets out too. Following someone who gets into a
 *   boat with room, it hops in on its own.</li>
 *   <li><b>Its own boat</b>: following someone out on the water with no seat for it, it puts its own boat on the water,
 *   gets in and paddles after them; back at the shore it gets out and breaks the boat to take it along.</li>
 *   <li><b>Horses</b>: it gets on (an empty hand, or a wild horse bucks). A wild one throws it off a few times before it
 *   lets it stay: that's taming. With a saddle it puts it on and rides after its friend; without one it can't steer.</li>
 * </ul>
 * A Xen has no game client to move what it rides, so while it rides it tells the game the server moves its boat or horse
 * ({@link XenPlayer#isClientAuthoritative}), and it steers the way the keys would: A and D turn the boat, W paddles,
 * and on a horse it looks where it wants to go and walks.
 */
final class Rider {
	private final Companion c;
	/** The boat or horse it's getting in or on, until when it tries, and who it's riding with (their boat, their ride). */
	private Entity want;
	private long wantUntil;
	private UUID with;
	/** Tries to get on a wild horse (it bucks), and when it last tried. */
	private int tries;
	private long triedAt;
	/** Its own boat on the water (it takes it back at the shore), and when its friend got out of the boat they shared. */
	private Entity ownBoat;
	private long aloneSince = -1;
	/** When it last put its boat down (not every decision). */
	private long placedAt = -1_000;

	Rider(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	static boolean rideable(Entity e) {
		return e instanceof AbstractBoat || e instanceof AbstractHorse h && !h.isBaby();
	}

	/** How many can sit in it: 2 in a boat (1 with a chest), 2 on a camel, 1 on a horse. */
	static int seats(Entity v) {
		if (v instanceof AbstractChestBoat) return 1;
		if (v instanceof AbstractBoat) return 2;
		return BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath().equals("camel") ? 2 : 1;
	}

	static boolean room(Entity v) {
		return v.getPassengers().size() < seats(v);
	}

	private static String what(Entity v) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).getPath();
		if (v instanceof AbstractBoat) return id.contains("raft") ? "raft" : "boat";
		return id.replace('_', ' ');
	}

	/** Riding something right now? */
	boolean riding() {
		return c.player != null && c.player.isPassenger();
	}

	/** Getting in or on something, or riding. */
	boolean busy() {
		return want != null || riding();
	}

	void cancel() {
		want = null;
		taming = null;
	}

	// ------------------------------------------------------------------------------ asked
	/** "Get in my boat", "hop on the horse", "ride with me": the plan, in words ("You will ..."). */
	String ask(ServerPlayer from, String words) {
		var p = c.player;
		if (p.isPassenger() && p.getVehicle() == from.getVehicle()) {
			with = from.getUUID();
			shared = p.getVehicle();
			return "You are already in " + from.getName().getString() + "'s " + what(p.getVehicle()) + ".";
		}
		boolean horse = words.matches("(?s).*\\b(horse|donkey|mule|camel|llama)s?\\b.*");
		boolean boat = words.matches("(?s).*\\b(boat|raft)s?\\b.*");
		Entity theirs = from.getVehicle();
		Entity pick = null;
		if (theirs != null && rideable(theirs) && room(theirs) && theirs.distanceTo(p) < 32) pick = theirs;   // their ride, with them
		if (pick == null) {
			double best = 24;
			for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(24), x -> x.isAlive() && rideable(x) && room(x))) {
				if (horse && !(e instanceof AbstractHorse) || boat && !(e instanceof AbstractBoat)) continue;
				if (e.distanceTo(p) > 8 && !p.hasLineOfSight(e)) continue;   // only what it can see (close by: it knows it's there)
				double d = e.distanceTo(p) + (e.getPassengers().contains(from) ? -20 : 0);
				if (d < best) {
					best = d;
					pick = e;
				}
			}
		}
		if (pick == null) {
			if (!horse && hasBoat() >= 0) {                                  // no boat around: its own, on the water
				with = from.getUUID();
				return putBoatDown(from.position()) ? "You will put your boat on the water and get in." : "You will put your boat down once there's water.";
			}
			return "You don't see a " + (horse ? "horse" : boat ? "boat" : "boat or horse") + " with room for you, so you can't.";
		}
		c.walker.stop();
		c.player.stopRiding();
		want = pick;
		wantUntil = now() + 20 * 30;
		with = pick.getPassengers().contains(from) || pick == theirs ? from.getUUID() : null;
		tries = 0;
		taming = pick instanceof AbstractHorse h && !h.isTamed() ? h : null;
		c.goals.instant = "getting in the " + what(pick);
		return pick == theirs ? "You will get in " + from.getName().getString() + "'s " + what(pick) + "."
				: "You will get " + (pick instanceof AbstractBoat ? "in" : "on") + " the " + what(pick) + ".";
	}

	/** "Get out", "get off": it lets go (shift, like a player). */
	String getOut() {
		want = null;
		if (!riding()) return "You aren't riding anything.";
		Entity v = c.player.getVehicle();
		leave();
		return "You will get " + (v instanceof AbstractBoat ? "out of the " : "off the ") + what(v) + ".";
	}

	private void leave() {
		Entity v = c.player.getVehicle();
		c.player.stopRiding();
		c.player.setShiftKeyDown(false);
		with = null;
		aloneSince = -1;
		if (v != null && v == ownBoat) c.mod.later(10, this::takeBoatBack);
	}

	// ------------------------------------------------------------------------------ each decision
	/** What riding needs this moment (getting in, sitting still, getting out), or null when it isn't riding. */
	Action next() {
		var p = c.player;
		if (p == null) return null;
		if (riding()) return ride();
		if (ownBoat != null && ownBoat.isAlive() && ownBoat.getPassengers().isEmpty() && ownBoat.distanceTo(p) < 16 && !c.fightingNow()) {
			return takeBoatBack();                                            // its boat, left at the shore: it takes it along
		}
		if (want == null && taming != null && taming.isAlive() && !taming.isTamed() && !taming.isVehicle() && tries < 16
				&& taming.distanceTo(p) < 16 && !c.fightingNow()) {
			want = taming;                                                   // thrown off: back on (that's how taming goes)
			wantUntil = now() + 20 * 20;
		} else if (want == null && taming != null && (tries >= 16 || !taming.isAlive())) {
			taming = null;
			c.chatter("This one won't let me ride it.", false);
		}
		if (want == null) want = friendsRide();
		if (want == null) return null;
		if (!want.isAlive() || now() > wantUntil || !room(want) || want.level() != p.level() || c.fightingNow()) {
			want = null;
			return null;
		}
		c.goals.instant = "getting " + (want instanceof AbstractBoat ? "in the " : "on the ") + what(want);
		c.acted = true;
		if (p.distanceTo(want) > 2.8) return c.walkTo(want.position());
		if (now() - triedAt < 20) return Action.IDLE;                        // (bucked off a moment ago)
		triedAt = now();
		if (want instanceof AbstractHorse h) {
			if (h.isTamed() && !h.isSaddled()) {                              // a saddle, if it has one: on it goes first
				int saddle = slot("saddle");
				if (saddle >= 0) {
					p.getInventory().setSelectedSlot(saddle);
					c.hands.face(h.getEyePosition());
					Compat.interact(p, h);
					Compat.swing(p);
					return Action.IDLE;
				}
			}
			int empty = emptyHotbarSlot();                                   // an empty hand (with food it would feed it, with a tool a wild one gets mad)
			if (empty >= 0) p.getInventory().setSelectedSlot(empty);
			tries++;
		}
		c.walker.stop();
		c.hands.face(want.position().add(0, want.getBbHeight() * 0.5, 0));
		p.setShiftKeyDown(false);
		Compat.interact(p, want);                                            // right-click it
		Compat.swing(p);
		if (p.getVehicle() == want) {
			Entity v = want;
			want = null;
			aloneSince = -1;
			if (v instanceof AbstractHorse h && !h.isTamed() && tries <= 1) c.chatter(pick("Whoa, easy... easy!", "Hold still, you!", "Yeehaw!"), false);
			else if (with != null) c.chatter(pick("In!", "Room for one more.", "Let's go!", "Alright, I'm in."), false);
			c.journal("does", "got " + (v instanceof AbstractBoat ? "in a " : "on a ") + what(v));
		}
		return Action.IDLE;
	}

	/** Following someone who got into a boat or on a horse: with room, it gets in with them; without, its own boat. */
	private Entity friendsRide() {
		if (c.mode != Companion.Mode.FOLLOW || c.leader == null || c.fightingNow()) return null;
		ServerPlayer f = c.server.getPlayerList().getPlayer(c.leader);
		if (f == null || f.level() != c.player.level() || !f.isPassenger()) return null;
		Entity v = f.getVehicle();
		if (v == null || !rideable(v) || v.distanceTo(c.player) > 20) return null;
		if (room(v) && !(v instanceof AbstractHorse h && !h.isTamed())) {
			with = f.getUUID();
			wantUntil = now() + 20 * 20;
			tries = 0;
			return v;
		}
		if (v instanceof AbstractBoat && v.isInWater() || v instanceof AbstractBoat && onWater(v)) {   // out on the water: its own boat
			if (now() - placedAt > 100 && hasBoat() >= 0 && putBoatDown(f.position())) with = f.getUUID();
		}
		return null;
	}

	private static boolean onWater(Entity v) {
		return v.level().getFluidState(v.blockPosition()).is(FluidTags.WATER) || v.level().getFluidState(v.blockPosition().below()).is(FluidTags.WATER);
	}

	/** Riding: it sits (and steers, per tick); it gets out when its friend did, or when it's there. */
	private Action ride() {
		var p = c.player;
		Entity v = p.getVehicle();
		c.acted = true;
		c.walker.stop();
		boolean driving = v.getControllingPassenger() == p;
		ServerPlayer friend = with != null ? c.server.getPlayerList().getPlayer(with) : null;
		if (friend == null && c.mode == Companion.Mode.FOLLOW && c.leader != null) friend = c.server.getPlayerList().getPlayer(c.leader);
		boolean theyreIn = friend != null && friend.getVehicle() == v;
		if (theyreIn) shared = v;
		c.goals.instant = theyreIn || !driving ? "riding with " + (friend != null ? friend.getName().getString() : "someone")
				: (v instanceof AbstractBoat ? "paddling" : "riding") + (friend != null ? " after " + friend.getName().getString() : "");
		if (c.fightingNow() && (v instanceof AbstractHorse || !v.isInWater())) {   // a fight on land: off, and fight
			leave();
			return null;
		}
		if (v instanceof AbstractHorse h && v.getPassengers().get(0) == p) return onHorse(h, friend);   // its own mount
		if (theyreIn || !driving && with == null && !v.getPassengers().isEmpty() && v.getPassengers().get(0) instanceof ServerPlayer) {
			aloneSince = -1;                                                 // someone else's ride, with them in it: it stays
			return Action.IDLE;
		}
		if (v != ownBoat) {                                                  // their ride, and they got out: so does it
			if (aloneSince < 0) aloneSince = now();
			if (now() - aloneSince > 10 && (shared == v || !driving || friend == null)) leave();
			return Action.IDLE;
		}
		if (friend == null || friend.level() != p.level()) {                 // its own boat, nobody to go after: out at the shore
			if (!v.isInWater() || nearShore(v)) leave();
			return Action.IDLE;
		}
		boolean friendAfloat = friend.getVehicle() instanceof AbstractBoat || friend.isInWater() && deep(friend.blockPosition());
		if (!friendAfloat && friend.distanceTo(p) < 8 && (nearShore(v) || !v.isInWater())) {
			leave();                                                         // at the shore, next to them: out, and the boat comes along
			return Action.IDLE;
		}
		if (v instanceof AbstractHorse h && !h.isSaddled()) {                // it can't steer without a saddle
			c.chatter("No saddle, I can't steer.", false);
			leave();
		}
		return Action.IDLE;
	}

	/** A wild horse it's taming (it gets back on each time it's thrown), and whether it said it's tamed. */
	private AbstractHorse taming;

	/** On a horse of its own: taming it (it bucks), a saddle on, then it rides after its friend (steered per tick). */
	private Action onHorse(AbstractHorse h, ServerPlayer friend) {
		if (!h.isTamed()) {
			taming = h;
			c.goals.instant = "taming a " + what(h);
			return Action.IDLE;                                              // (it throws it off in a while, or it's tamed)
		}
		if (taming == h) {
			taming = null;
			c.chatter(pick("It likes me now!", "Tamed! Good horse.", "Finally. We're friends now."), false);
			c.journal("does", "tamed a " + what(h));
		}
		if (!h.isSaddled()) {
			if (slot("saddle") >= 0) {                                      // off, saddle on, back on
				leave();
				want = h;
				wantUntil = now() + 20 * 15;
				tries = 0;
				return Action.IDLE;
			}
			if (friend != null && friend.distanceTo(c.player) > 8) {
				c.chatter("No saddle, I can't steer.", false);
				leave();
			}
			return Action.IDLE;
		}
		c.goals.instant = "riding" + (friend != null ? " after " + friend.getName().getString() : "");
		return Action.IDLE;
	}

	/** The ride it last shared with its friend (when they get out, it does too). */
	private Entity shared;

	private boolean deep(BlockPos at) {
		var l = c.player.level();
		return l.getFluidState(at).is(FluidTags.WATER) && l.getFluidState(at.below()).is(FluidTags.WATER);
	}

	private static boolean nearShore(Entity v) {
		BlockPos at = v.blockPosition();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos b = at.offset(dx, 0, dz);
				if (!v.level().getBlockState(b).getCollisionShape(v.level(), b).isEmpty() || !v.level().getFluidState(b.below()).is(FluidTags.WATER)
						&& !v.level().getBlockState(b.below()).getCollisionShape(v.level(), b.below()).isEmpty()) return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------------------ each tick
	/** Steering what it rides, the way the keys would (only when it's the one steering). */
	void tick() {
		var p = c.player;
		if (p == null || !p.isPassenger()) return;
		Entity v = p.getVehicle();
		if (v == null || v.getControllingPassenger() != p) return;
		ServerPlayer friend = with != null ? c.server.getPlayerList().getPlayer(with) : null;
		if (friend == null && c.leader != null) friend = c.server.getPlayerList().getPlayer(c.leader);
		Vec3 to = friend != null && friend.level() == p.level() ? friend.position() : null;
		if (v instanceof AbstractBoat b) paddle(b, to);
		else if (v instanceof AbstractHorse h && h.isSaddled()) spur(h, to);
	}

	/** A and D turn the boat a few degrees a tick, W pushes it on (on water it's quick; on land, slow). */
	private void paddle(AbstractBoat b, Vec3 to) {
		if (to == null) {
			b.setPaddleState(false, false);
			return;
		}
		Vec3 d = to.subtract(b.position());
		double flat = Math.hypot(d.x, d.z);
		float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float diff = wrap(want - b.getYRot());
		float turn = Math.max(-5f, Math.min(5f, diff));
		b.setYRot(b.getYRot() + turn);
		c.player.setYRot(c.player.getYRot() + turn);                         // (it turns with its boat)
		c.player.setYHeadRot(c.player.getYRot());
		boolean go = flat > 3 && Math.abs(diff) < 70;
		if (go) {
			double yr = Math.toRadians(b.getYRot());
			double f = 0.04;
			b.setDeltaMovement(b.getDeltaMovement().add(-Math.sin(yr) * f, 0, Math.cos(yr) * f));
		}
		b.setPaddleState(go || turn > 1, go || turn < -1);
	}

	/** On a saddled horse: it looks where it's going and walks (the horse goes), and jumps what's in the way. */
	private void spur(AbstractHorse h, Vec3 to) {
		var p = c.player;
		if (to == null) {
			p.zza = 0;
			return;
		}
		Vec3 d = to.subtract(p.position());
		double flat = Math.hypot(d.x, d.z);
		float want = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float turn = Math.max(-10f, Math.min(10f, wrap(want - p.getYRot())));
		p.setYRot(p.getYRot() + turn);
		p.setYHeadRot(p.getYRot());
		p.setXRot(0);
		p.zza = flat > 4 ? 1f : 0f;
		p.xxa = 0;
		if (h.horizontalCollision && h.onGround() && p.zza > 0 && p.tickCount - jumpedAt > 20) {   // something in the way: a jump (space, let go)
			jumpedAt = p.tickCount;
			h.onPlayerJump(60);
		}
	}

	private int jumpedAt = -100;

	private static float wrap(float deg) {
		deg %= 360f;
		if (deg >= 180f) deg -= 360f;
		if (deg < -180f) deg += 360f;
		return deg;
	}

	// ------------------------------------------------------------------------------ its own boat
	/** Its boat on the water next to it (looking at the water, right-click with the boat), then it gets in. */
	private boolean putBoatDown(Vec3 toward) {
		var p = c.player;
		int slot = hasBoat();
		if (slot < 0) return false;
		BlockPos water = null;
		double best = 1e9;
		BlockPos at = p.blockPosition();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -2; dy <= 0; dy++) {
					BlockPos b = at.offset(dx, dy, dz);
					if (!p.level().getFluidState(b).is(FluidTags.WATER) || !p.level().getBlockState(b.above()).isAir()) continue;
					double d = b.distToCenterSqr(toward) + b.distToCenterSqr(p.position()) * 4;
					if (d < best) {
						best = d;
						water = b;
					}
				}
			}
		}
		if (water == null) return false;
		placedAt = now();
		p.getInventory().setSelectedSlot(slot);
		c.walker.stop();
		c.hands.face(Vec3.atCenterOf(water).add(0, 0.45, 0));
		int before = p.getInventory().getSelectedItem().getCount();
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		if (p.getInventory().getSelectedItem().getCount() >= before && !p.isCreative()) return false;
		AbstractBoat placed = null;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(6), x -> x instanceof AbstractBoat && x.getPassengers().isEmpty() && x.tickCount < 5)) {
			placed = (AbstractBoat) e;
		}
		if (placed == null) return false;
		ownBoat = placed;
		want = placed;
		wantUntil = now() + 20 * 15;
		tries = 0;
		c.chatter(pick("Wait up, I've got a boat!", "Boat time.", "Hold on, getting my boat out."), false);
		return true;
	}

	/** Its boat, empty at the shore: a few hits break it and it picks it up (like anyone taking their boat along). */
	private Action takeBoatBack() {
		var p = c.player;
		if (ownBoat == null || !ownBoat.isAlive()) {
			ownBoat = null;
			return null;
		}
		if (!ownBoat.getPassengers().isEmpty()) {                            // someone else took it: theirs now
			ownBoat = null;
			return null;
		}
		if (p.distanceTo(ownBoat) > 3) return c.walkTo(ownBoat.position());
		c.hands.face(ownBoat.position().add(0, 0.3, 0));
		p.attack(ownBoat);
		Compat.swing(p);
		c.acted = true;
		c.goals.instant = "taking its boat back";
		if (!ownBoat.isAlive()) ownBoat = null;
		return Action.IDLE;
	}

	// ------------------------------------------------------------------------------ what it carries
	private int hasBoat() {
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if ((id.endsWith("_boat") || id.endsWith("_raft")) && !id.contains("chest")) return i < 9 ? i : toHotbar(i);
		}
		return -1;
	}

	private int slot(String id) {
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id)) return i < 9 ? i : toHotbar(i);
		}
		return -1;
	}

	/** Moves it into the hotbar (the last slot), as a player would drag it there; the hotbar slot. */
	private int toHotbar(int i) {
		var inv = c.player.getInventory();
		ItemStack s = inv.getItem(i), h = inv.getItem(8);
		inv.setItem(8, s);
		inv.setItem(i, h);
		return 8;
	}

	private int emptyHotbarSlot() {
		var inv = c.player.getInventory();
		for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
		return -1;
	}

	private String pick(String... s) {
		return s[c.player.getRandom().nextInt(s.length)];
	}
}
