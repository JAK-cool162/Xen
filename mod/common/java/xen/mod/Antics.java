package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Perception;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The unpredictable side of a Xen: things it does for fun, not because they're useful. How often depends on how
 * playful it is (silly ones most, grumpy ones least), and it never does them when it's in danger or busy.
 * <ul>
 *   <li><b>Dancing along</b>: crouch up and down next to it and it joins in (and other Xens nearby join it, like a
 *   crowd hyping someone up). It also dances when something great happens.</li>
 *   <li><b>Showing off</b>: now and then, "Watch this!": a sprint, a jump and a spin in the air. Sometimes it nails
 *   it; sometimes it stumbles and says it meant to.</li>
 *   <li><b>Surprises in a fight</b>: a snack break in front of a foe that's nearly beaten, a crouch taunt, or
 *   running off and turning right back.</li>
 * </ul>
 */
final class Antics {
	private final Companion c;
	private final Random random = new Random();
	/** What it's doing for fun right now, and until when. */
	private String doing = "";
	private long until, next = -1;
	private int step;
	private Vec3 runTo;
	private ServerPlayer partner;
	/** Crouches it has seen from each player lately (to notice someone dancing). */
	private final Map<UUID, int[]> crouches = new HashMap<>();
	private final Map<UUID, Boolean> wasCrouching = new HashMap<>();
	private long lastSurprise;

	Antics(Companion c) {
		this.c = c;
	}

	/** How playful it is, 0 to 1. */
	float playful() {
		float tone = switch (c.personality.tone) {
			case "silly" -> 1f;
			case "cheerful", "bold" -> 0.7f;
			case "calm", "shy" -> 0.35f;
			default -> 0.2f;
		};
		return Math.min(1f, tone * 0.8f + 0.3f * c.personality.curiosity);
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private String pick(String... byTone) {
		int t = Math.max(0, java.util.Arrays.asList(Personality.TONES).indexOf(c.personality.tone));
		return byTone[Math.min(byTone.length - 1, t)];
	}

	boolean busy() {
		return !doing.isEmpty();
	}

	/** Something great happened: a little celebration dance. */
	void celebrate() {
		if (!c.mod.config.antics || busy()) return;
		start("dance", 40);
	}

	/** A trick or a spin, now (from a script). */
	void showOff(boolean justSpin) {
		if (!c.mod.config.antics || busy() || c.player == null) return;
		if (!justSpin && startTrick()) return;
		start("spin", 12);
	}

	/** Hello to a friend, the way players do it: crouching twice. */
	void wave(ServerPlayer to) {
		if (!c.mod.config.antics || busy()) return;
		partner = to;
		c.hands.watching = to;
		start("wave", 8);
	}

	private void start(String what, int ticks) {
		doing = what;
		until = now() + ticks;
		step = 0;
	}

	private void stop() {
		doing = "";
		c.player.setShiftKeyDown(false);
		c.hands.watching = null;
	}

	// ------------------------------------------------------------------------------ each tick
	/** When it last greeted each player back (a crouch greeting once a while, not every crouch). */
	private final Map<UUID, Long> greetedAt = new HashMap<>();

	/**
	 * Someone crouched at it a few times quickly: the players' way of saying "hi, I'm friendly". It trusts them a
	 * little more (never all the way: anyone can crouch) and crouches back.
	 */
	private void greeted(ServerPlayer p, long now) {
		if (now - greetedAt.getOrDefault(p.getUUID(), -10_000L) < 600) return;
		greetedAt.put(p.getUUID(), now);
		float t = c.trust(p.getUUID());
		if (t < 0.5f) c.trust(p.getUUID(), Math.min(0.1f, 0.5f - t));
		if (!busy()) {
			partner = p;
			c.hands.watching = p;
			start("wave", 8);
		}
		if (!(p instanceof XenPlayer)) c.talker.sayNear(pick("Hi hi! *crouches back*", "Hey. *crouches back*", "Hm. Hi.", "O-oh, hi! *crouches*",
				"Yo! *crouch crouch*", "*crouch crouch crouch* Hiii!"));
		if (Mimic.DEBUG) XenMod.LOG.info("[xen antics] {} greeted {} back (trust now {})", c.name, p.getName().getString(), c.trust(p.getUUID()));
	}

	/** Watching for someone greeting it or dancing (every tick, it's quick). */
	void watch() {
		if (c.player == null || c.inArena) return;
		long now = now();
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p == c.player || p.level() != c.player.level() || p.distanceTo(c.player) > 8) continue;
			boolean crouching = p.isShiftKeyDown();
			Boolean was = wasCrouching.put(p.getUUID(), crouching);
			if (was == null || was == crouching || !crouching) continue;
			int[] seen = crouches.computeIfAbsent(p.getUUID(), u -> new int[] {0, 0});
			if (now - seen[1] > 40) seen[0] = 0;                               // a new round of crouches
			seen[0]++;
			seen[1] = (int) now;
			if (Mimic.DEBUG) XenMod.LOG.info("[xen antics] {} saw {} crouch ({} in a row; busy {})", c.name, p.getName().getString(), seen[0], doing);
			boolean isXenDancing = p instanceof XenPlayer x && x.companion != null && x.companion.antics.doing.equals("dance");
			if (seen[0] == 4 && !isXenDancing && c.player.hasLineOfSight(p)) greeted(p, now);   // a crouch greeting (always, like players)
			if (!c.mod.config.antics) continue;
			boolean trusted = c.known.contains(p.getUUID()) || isXenDancing || c.trust(p.getUUID()) >= 0.3f;
			if (seen[0] >= 10 && trusted && !busy() && random.nextFloat() < 0.4f + 0.6f * playful()) {   // it keeps going: a dance
				partner = p;
				start("dance", 60 + random.nextInt(40));
				c.talker.sayNear(pick("Dance party!", "Alright, alright.", "Ugh... fine. One dance.", "O-okay, I'll dance too...",
						"LET'S GOOO!", "Wheee! Dance party!"));
			}
		}
	}

	/**
	 * Its move for fun this decision, or null. Only when it's safe: no fight, no chore, not hungry or hurt, not in
	 * water, and not in the arena.
	 */
	Action next(boolean fighting) {
		if (c.inArena || c.player == null) return null;
		if (!c.mod.config.antics && !doing.equals("wave")) return null;   // (crouching back to a greeting isn't an antic)
		if (fighting || c.player.isInWater() || c.player.getHealth() < 10) {
			if (busy() && !doing.equals("fight")) stop();
			return null;
		}
		long now = now();
		if (busy() && now > until) {
			String was = doing;
			stop();
			if (was.equals("trick")) landed();
			return null;
		}
		switch (doing) {
			case "dance" -> {
				return dance(now);
			}
			case "trick" -> {
				return trick(now);
			}
			case "spin" -> {
				c.hands.watching = null;
				return Action.TURN_LEFT;
			}
			case "stumble" -> {
				return Action.IDLE;
			}
			case "wave" -> {                                                    // crouching twice: a player's hello
				c.player.setShiftKeyDown(++step % 4 < 2);
				return Action.IDLE;
			}
			default -> {}
		}
		if (c.chores.busy() || c.mode == Companion.Mode.FREE && c.goals.current != null) return null;
		if (next < 0) next = now + 2400;
		if (now < next) return null;
		next = now + (long) ((Talker.FAST ? 600 : 12_000) * (1.5f - playful()) * (0.6f + 0.8f * random.nextFloat()));
		if (random.nextFloat() > playful()) return null;                     // not in the mood
		ServerPlayer friend = c.leader == null ? null : c.server.getPlayerList().getPlayer(c.leader);
		if (friend == null || friend.level() != c.player.level() || friend.distanceTo(c.player) > 16) return null;   // nobody to show
		if (random.nextBoolean() && startTrick()) return Action.IDLE;
		partner = friend;
		start("spin", 12);
		c.talker.sayNear(pick("Wheee!", "Spin!", "...", "Hehe.", "Behold!", "Weeeee!"));
		return Action.TURN_LEFT;
	}

	private Action dance(long now) {
		step++;
		if (partner != null && partner.isAlive() && partner.level() == c.player.level()) c.hands.watching = partner;
		c.player.setShiftKeyDown(step % 6 < 3);                               // down, up, down, up
		c.goals.instant = "dancing";
		if (step % 20 == 10 && c.player.onGround()) return Action.JUMP;
		if (step % 25 == 0) return random.nextBoolean() ? Action.TURN_LEFT : Action.TURN_RIGHT;
		return Action.IDLE;
	}

	/** A flat, safe run-up in front of it (no drop, no lava, nothing in the way), or false. */
	private boolean startTrick() {
		var level = c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (int k = 0; k < 4; k++) {
			int yaw = (c.hands.yaw + k) % 4;
			int[] f = Perception.forward(yaw);
			boolean ok = true;
			for (int d = 1; d <= 6 && ok; d++) {
				BlockPos p = feet.offset(f[0] * d, 0, f[1] * d);
				ok = level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
						&& !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()
						&& level.getFluidState(p.below()).isEmpty() && level.getFluidState(p).isEmpty();
			}
			if (!ok) continue;
			c.hands.yaw = yaw;
			runTo = Vec3.atCenterOf(feet.offset(f[0] * 6, 0, f[1] * 6));
			start("trick", 60);
			c.talker.sayNear(pick("Watch this!", "Watch this.", "Hey. Watch.", "U-um, watch this...", "Watch THIS!", "Hold my potato. Watch this!"));
			return true;
		}
		return false;
	}

	private Action trick(long now) {
		step++;
		c.goals.instant = "showing off";
		if (step < 12) {                                                    // the run-up
			c.player.setSprinting(true);
			return Action.FORWARD;
		}
		if (step == 12) return Action.JUMP;                                 // the jump...
		if (!c.player.onGround() && step < 30) return Action.TURN_LEFT;     // ...and a spin in the air
		if (step > 14) until = now;                                         // down again: how did it go?
		return Action.IDLE;
	}

	/** After the trick: nailed it, or not. */
	private void landed() {
		c.player.setSprinting(false);
		boolean nailed = random.nextFloat() < 0.35f + 0.5f * c.personality.diligence;
		if (nailed) {
			c.talker.sayNear(pick("Nailed it!", "Clean.", "Heh. Not bad.", "I did it!", "Flawless.", "NAILED IT!"));
			start("dance", 30);
		} else {
			c.player.setShiftKeyDown(true);                                 // stumbles
			c.talker.sayNear(pick("Oof! I meant to do that.", "That... was on purpose.", "Don't. Say. Anything.", "Ow... nobody saw that, right?",
					"I meant to do that!", "Oops! 10 out of 10 anyway!"));
			doing = "stumble";
			until = now() + 20;
		}
	}

	// ------------------------------------------------------------------------------- fights
	/**
	 * A surprise in a fight, or null: a snack in front of a foe that's nearly beaten, a crouch taunt, or a fake
	 * retreat. Playful Xens do it more; never when it's losing.
	 */
	Action surprise(LivingEntity foe, double distance) {
		if (!c.mod.config.antics || c.inArena) return null;
		long now = now();
		if (doing.equals("fight") && now <= until) return feint(foe);
		if (doing.equals("fight")) doing = "";
		if (now - lastSurprise < 200 || c.player.getHealth() < 12) return null;
		if (random.nextFloat() > 0.02f * playful()) return null;              // rare: a few per long fight
		lastSurprise = now;
		boolean foeLow = foe.getHealth() < foe.getMaxHealth() * 0.3f;
		if (foeLow && distance > 3 && c.hands.canHeal()) {
			c.talker.sayNear(pick("Snack break!", "Hold on, I'm eating.", "Not even worth a sword. *munch*", "U-um, sorry, I'm hungry...",
					"Mid-fight snack!", "Nom nom nom."));
			return Action.EAT;
		}
		if (foeLow && distance > 2.5 && !(foe instanceof net.minecraft.server.level.ServerPlayer)) {   // (a player: it talks terms instead, and keeps at it)
			c.talker.sayNear(pick("Hehe, gotcha!", "Too easy.", "Is that all?", "Boop!", "Next!", "Ha!"));
			c.player.setShiftKeyDown(true);
			c.hands.watching = foe;
			return Action.IDLE;
		}
		if (distance < 4) {                                                   // run off... and turn right back
			start("fight", 16);
			return feint(foe);
		}
		return null;
	}

	private Action feint(LivingEntity foe) {
		step++;
		c.player.setShiftKeyDown(false);
		c.player.setSprinting(true);
		if (step < 8) {
			Vec3 away = c.player.position().subtract(foe.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
			c.hands.face(c.player.getEyePosition().add(away.normalize().scale(5)));
			c.hands.pitch = 0;
			return Action.FORWARD;
		}
		c.hands.watching = foe;
		return c.walkTo(foe.position());                                      // surprise, it's back
	}
}
