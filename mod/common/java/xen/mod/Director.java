package xen.mod;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * Showing off, like a player with someone watching: a moment that would look good (a monster a fair way off and a bow
 * in hand: a jump, a full spin in the air, and the arrow lands; a three-block gap right there: a sprint-jump across;
 * a friend close by: a quick duel, first to half health). It only notices these when a player is watching, it's safe
 * (no monsters at it, healthy, fed, daylight) and it has nothing on (out exploring, or with the one it follows; no chore,
 * no build, no fight), never more than
 * once every few minutes; and the Xen decides whether it does it (a proud one, a bold one, more often; one whose shows
 * nobody reacted to, less). The moves themselves are scripted: the timing of a trick shot is the trick.
 */
final class Director {
	enum Show { TRICK_SHOT, GAP_JUMP, DUEL }

	private static final int COOLDOWN = 20 * 60 * 6, ALL_COOLDOWN = 20 * 90;
	private static long anyShowAt = -100_000;

	private final Companion c;
	Show on;
	private int stage, ticks;
	private long startedAt, lastShowAt = -100_000, nextLook;
	/** The trick shot's target; the gap jump's run-up, take-off and landing; who it's showing off to. */
	private Mob target;
	private BlockPos runUp, takeOff, land;
	private ServerPlayer audience;
	private float spinFrom;
	/** How much people liked each kind of show (0 to 1: did anyone say anything after?), and the reactions it's waiting for. */
	private final float[] liked = {0.5f, 0.5f, 0.5f};
	private Show waitingFor;
	private long waitUntil;
	int shows, landed;

	Director(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** A real player watching it (close enough to see it, in plain sight): who. */
	private ServerPlayer watcher() {
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer || p.isSpectator() || p.level() != c.player.level() || p.distanceTo(c.player) > 40) continue;
			if (p.hasLineOfSight(c.player)) return p;
		}
		return null;
	}

	/** Safe and free enough for it (no chore, no fight, healthy, fed, daylight, nothing after it). */
	private boolean free() {
		var p = c.player;
		if (c.minion || c.inArena || c.mode == Companion.Mode.STAY || c.chores.busy() || c.builder.busy() || c.storage.busy() || c.fightingNow()
				|| c.adventure.on || c.nether.busy() || c.visits.busy() || c.skills.trainingNow()) return false;
		if (p.getHealth() < 16 || p.getFoodData().getFoodLevel() < 14 || p.isInWater() || !p.onGround() || p.level().isDarkOutside()) return false;
		if (c.danger() > 0.2f) return false;
		var g = c.goals.current;
		return g == null || g == Goals.Short.EXPLORE;
	}

	/** Every few seconds: something worth showing, and does it feel like it. */
	void think() {
		if (c.player == null || on != null || now() < nextLook) return;
		nextLook = now() + 100;
		if (waitingFor != null && now() > waitUntil) {                          // nobody said a thing: it didn't go down that well
			liked[waitingFor.ordinal()] = 0.8f * liked[waitingFor.ordinal()];
			waitingFor = null;
		}
		if (now() - lastShowAt < COOLDOWN || now() - anyShowAt < ALL_COOLDOWN || !free()) return;
		ServerPlayer who = watcher();
		if (who == null) return;
		Show pick = null;
		Mob shot = c.hands.hasBow() ? shotTarget() : null;
		if (shot != null) pick = Show.TRICK_SHOT;
		else if (findGap()) pick = Show.GAP_JUMP;
		else if (duelPartner() != null) pick = Show.DUEL;
		if (pick == null) return;
		var me = c.personality;
		float want = 0.1f + 0.45f * me.sin(xen.mod.core.Sins.PRIDE) + 0.25f * me.bravery + 0.3f * (liked[pick.ordinal()] - 0.5f);
		if (c.random().nextFloat() >= want) {                                   // (its call: not now)
			lastShowAt = now() - COOLDOWN / 2;
			return;
		}
		audience = who;
		on = pick;
		stage = ticks = 0;
		startedAt = now();
		lastShowAt = anyShowAt = now();
		shows++;
		target = shot;
		c.goals.drop();
		c.journal("does", "shows off for " + who.getName().getString() + ": " + pick.name().toLowerCase(Locale.ROOT).replace('_', ' '));
		XenMod.LOG.info("{} shows off ({}) for {}", c.name, pick, who.getName().getString());
		if (pick == Show.DUEL) {
			Companion o = duelPartner();
			if (o == null || !c.skills.duel(o)) on = null;
			else waitFor(Show.DUEL);
			on = null;                                                           // (the duel is its fighting's from here)
		} else c.say(pick == Show.TRICK_SHOT ? c.pick3("Watch this.", "Hold on, watch this shot.", "Bet I can hit that.") : c.pick3("Watch this jump.", "I can make that.", "Ez jump, watch."));
	}

	/** A monster a fair way off, in plain sight, in the open, standing still enough. */
	private Mob shotTarget() {
		var p = c.player;
		Mob best = null;
		double bestD = Double.MAX_VALUE;
		for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(28), x -> x.isAlive() && x instanceof Enemy)) {
			double d = m.distanceTo(p);
			if (d < 12 || d > 28 || !p.hasLineOfSight(m) || Hands.bowAim(p.getEyePosition(), m.getEyePosition()) == null) continue;
			if (m.getTarget() == p) continue;                                     // (one coming for it: that's a fight, not a show)
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		return best;
	}

	/** A friend about, free and healthy: someone to duel. */
	private Companion duelPartner() {
		for (Companion o : c.mod.companions) {
			if (o == c || o.player == null || o.minion || o.player.level() != c.player.level() || o.player.distanceTo(c.player) > 16) continue;
			if (o.skills.trainingNow() || o.chores.busy() || o.fightingNow() || o.player.getHealth() < 16) continue;
			if (c.trust(o.player.getUUID()) >= 0.3f) return o;
		}
		return null;
	}

	/** A three-block gap close by (the same height across, a run-up behind, a drop it lives through if it misses). */
	private boolean findGap() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		for (int dx = -8; dx <= 8; dx++)
			for (int dz = -8; dz <= 8; dz++)
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos a = feet.offset(dx, dy, dz);
					if (!stand(level, a)) continue;
					for (Direction d : Direction.Plane.HORIZONTAL) {
						BlockPos r = a.relative(d.getOpposite()), b = a.relative(d, 4);
						if (!stand(level, r) || !stand(level, b) || !clearAbove(level, a.relative(d, 1)) || !clearAbove(level, a.relative(d, 2)) || !clearAbove(level, a.relative(d, 3))) continue;
						boolean gap = true, safe = true;
						for (int i = 1; i <= 3 && gap; i++) {
							BlockPos mid = a.relative(d, i);
							if (!level.getBlockState(mid.below()).getCollisionShape(level, mid.below()).isEmpty()) gap = false;
							int drop = 1;
							while (drop < 8 && level.getBlockState(mid.below(drop)).getCollisionShape(level, mid.below(drop)).isEmpty() && level.getFluidState(mid.below(drop)).isEmpty()) drop++;
							boolean wet = !level.getFluidState(mid.below(drop)).isEmpty();
							if (drop < 2 || drop > 4 && !wet || !level.getFluidState(mid.below(drop)).isEmpty() && level.getFluidState(mid.below(drop)).is(net.minecraft.tags.FluidTags.LAVA)) safe = false;
						}
						if (!gap || !safe) continue;
						runUp = r;
						takeOff = a;
						land = b;
						return true;
					}
				}
		return false;
	}

	private static boolean stand(ServerLevel level, BlockPos p) {
		return !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty() && level.getFluidState(p.below()).isEmpty()
				&& clearAbove(level, p);
	}

	private static boolean clearAbove(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
				&& level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty() && level.getFluidState(p).isEmpty();
	}

	/** Its show's next step (null: no show on). Something going wrong (hurt, a monster at it) ends it at once. */
	Action step() {
		if (on == null || c.player == null) return null;
		var p = c.player;
		if (p.hurtTime > 0 || c.fightingNow() || now() - startedAt > 20 * 25 || c.mode == Companion.Mode.STAY) {
			end(null);
			return null;
		}
		if (on == Show.TRICK_SHOT) {
			c.goals.instant = "lining up a trick shot";
			c.acted = true;
			return Action.IDLE;                                                  // (the timing is all in tick())
		}
		c.goals.instant = "going for a jump";
		if (stage == 0) {                                                        // to the run-up (its walking does that)
			if (p.blockPosition().equals(runUp) || Math.hypot(p.getX() - (runUp.getX() + 0.5), p.getZ() - (runUp.getZ() + 0.5)) < 0.35 && Math.abs(p.getY() - runUp.getY()) < 0.6) {
				stage = 1;
				ticks = 0;
				c.acted = true;
				return Action.IDLE;
			}
			Action a = c.walkTo(Vec3.atBottomCenterOf(runUp));
			if (a == null && now() - startedAt > 20 * 10) end(null);
			return a != null ? a : Action.IDLE;
		}
		c.acted = true;
		return Action.IDLE;                                                      // (the run and the jump: tick())
	}

	/** Every tick, after its hands: the moves that need the timing (the spin, the run-up and the take-off). */
	void tick() {
		if (on == null || c.player == null) return;
		var p = c.player;
		ticks++;
		if (on == Show.TRICK_SHOT) {
			if (target == null || !target.isAlive()) {
				end(null);
				return;
			}
			Vec3 aimAt = target.getEyePosition().add(target.getDeltaMovement().scale(8));
			if (stage == 0) {                                                    // face it, draw: a full draw first
				c.hands.face(target.getEyePosition());
				if (ticks == 2 && !c.hands.drawBow()) {
					end(null);
					return;
				}
				if (c.hands.drawn() >= 22) {
					stage = 1;
					ticks = 0;
					spinFrom = p.getYRot();
					p.setJumping(true);
				} else if (ticks > 60) end(null);
				return;
			}
			if (stage == 1) {                                                    // up, and a full turn in the air
				if (ticks == 2) p.setJumping(false);
				if (ticks <= 9) {
					c.hands.aim(spinFrom + 40f * ticks, p.getXRot());
					return;
				}
				float[] aim = Hands.bowAim(p.getEyePosition(), aimAt);
				if (aim == null) {
					end(null);
					return;
				}
				c.hands.aim(aim[0], aim[1]);                                     // facing it again: let go
				c.hands.loose();
				stage = 2;
				ticks = 0;
				return;
			}
			if (ticks > 40) end(target.isAlive() && target.getHealth() >= target.getMaxHealth() ? "missed" : "hit");   // (did it land?)
			else if (target.hurtTime > 0) end("hit");
			return;
		}
		if (on == Show.GAP_JUMP && stage >= 1) {
			Vec3 to = Vec3.atBottomCenterOf(land);
			if (stage == 1) {                                                    // the run: sprinting at the edge
				c.hands.face(to.add(0, 1.6, 0));
				p.setSprinting(true);
				p.zza = 1;
				double past = Vec3.atBottomCenterOf(takeOff).subtract(Vec3.atBottomCenterOf(runUp)).normalize().dot(p.position().subtract(Vec3.atBottomCenterOf(takeOff)));
				if (past > 0.3 && p.onGround()) {                                // the edge: jump
					p.setJumping(true);
					stage = 2;
					ticks = 0;
				} else if (ticks > 40) end(null);
				return;
			}
			c.hands.face(to.add(0, 1.6, 0));                                     // in the air: keep going
			p.setSprinting(true);
			p.zza = 1;
			if (ticks == 3) p.setJumping(false);
			if (ticks > 4 && (p.onGround() || p.isInWater())) {
				p.zza = 0;
				p.setSprinting(false);
				end(p.blockPosition().closerThan(land, 1.6) && Math.abs(p.getY() - land.getY()) < 0.6 ? "made it" : "fell");
			} else if (ticks > 40) end(null);
		}
	}

	/** It's over: how it went (null: called off), and what it says. */
	private void end(String how) {
		Show was = on;
		on = null;
		stage = ticks = 0;
		target = null;
		c.player.zza = 0;
		c.player.setJumping(false);
		if (c.hands.drawn() > 0) c.hands.loose();
		if (was == null || how == null) return;
		boolean good = how.equals("hit") || how.equals("made it");
		if (good) landed++;
		c.journal("does", "its " + was.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ": " + how);
		XenMod.LOG.info("{}'s {}: {}", c.name, was, how);
		c.say(good ? (was == Show.TRICK_SHOT ? c.pick3("Clean.", "Did you see that?!", "360 no scope lol") : c.pick3("Ez.", "Made it!", "Too easy."))
				: (was == Show.TRICK_SHOT ? c.pick3("Okay that didn't count.", "Missed, ugh.", "Wind, obviously.") : c.pick3("Ow. That didn't count.", "Nope.", "Okay, almost.")));
		waitFor(was);
	}

	private void waitFor(Show s) {
		waitingFor = s;
		waitUntil = now() + 20 * 20;
	}

	/** A player said something: right after a show, that's a reaction (the show went down well). */
	void heard(ServerPlayer from, String text) {
		if (waitingFor == null || c.player == null || now() > waitUntil || from.distanceTo(c.player) > 48) return;
		liked[waitingFor.ordinal()] = 0.7f * liked[waitingFor.ordinal()] + 0.3f;
		waitingFor = null;
	}

	/** For /xen status. */
	String describe() {
		return shows == 0 ? "" : String.format(Locale.ROOT, "showed off %d time%s (%d landed)", shows, shows == 1 ? "" : "s", landed);
	}
}
