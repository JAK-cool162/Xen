package xen.mod;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Random;

/**
 * How a Xen fights: like a 1.9+ PvP player, with nothing but a player's inputs (movement keys, jump, sprint, the mouse,
 * the hotbar). Its fight genes ({@link Personality#GENES}) decide how it plays; the rules are the server's own:
 * <ul>
 *   <li>a swing does full damage, and can crit or knock back extra, only when it's charged over 90%;</li>
 *   <li>a critical hit (x1.5) needs it to be falling and <em>not</em> sprinting, so it lets go of sprint in the air;</li>
 *   <li>a sprint hit knocks the foe back further, and the server ends the sprint: a quick step back (an S-tap) and
 *   sprinting in again makes the next hit a sprint hit too;</li>
 *   <li>jumping toward an attacker just as its hit lands cancels part of the knockback (a jump reset);</li>
 *   <li>a raised shield blocks hits from the front only, and an axe hit on it disables it for five seconds.</li>
 * </ul>
 */
final class Fighter {
	private final Companion c;
	private final Random random = new Random();
	/** This swing: go for a jump crit? hold up a shield while it recharges? let the foe swing first? */
	private boolean goCrit, useShield, selecting;
	private boolean fleeing, strafeRight;
	private int sTap, waited;
	private float lastCharge = 1f;
	private double foeY = Double.NaN;
	private int foeSwungAt = -100;
	private boolean foeSwung;
	private LivingEntity lastFoe;

	Fighter(Companion c) {
		this.c = c;
	}

	private float gene(int i) {
		return c.personality.fightGenes[i];
	}

	/** The fight is over (for now: if it was backing off to heal, it still is until it has healed). */
	void reset() {
		lastFoe = null;
		sTap = 0;
	}

	/** Its next move against foe (null: go on with other things). hurtNow: it was hit this tick. */
	Action next(LivingEntity foe, boolean hurtNow) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		int now = p.tickCount;
		if (foe != lastFoe) {
			if (foe instanceof Player && !c.inArena) c.chatter(c.personality.say("fight"), false);
			lastFoe = foe;
			foeY = foe.getY();
			sTap = 0;
			newSwing();
		}
		boolean foeRising = !foe.onGround() && foe.getY() > foeY + 0.02;     // it sees the foe jump at it
		foeY = foe.getY();
		boolean swings = Compat.swinging(foe);
		if (swings && !foeSwung) foeSwungAt = now;                           // and sees it swing
		foeSwung = swings;
		double d = p.distanceTo(foe);
		boolean sprintable = p.getFoodData().getFoodLevel() > 6;             // like a player, it can't sprint when starving

		if (foe instanceof net.minecraft.world.entity.monster.Creeper creeper && creeper.getSwellDir() > 0 && d < 5) {
			c.goals.instant = "getting away from the creeper";                 // it's hissing: run, like anyone would
			if (!c.inArena) c.chatter("Creeper! Run!", false);
			return runFrom(foe, sprintable);
		}

		float retreat = 0.4f * gene(8);
		float health = p.getHealth() / p.getMaxHealth();
		if (retreat > 0 && (health < retreat || fleeing && health < retreat + 0.2f)) {
			if (!fleeing && !c.inArena) c.chatter(c.personality.say("flee"), false);
			fleeing = true;                                                    // back off until it has healed a bit
			h.lowerShield();
			if (d > 5 && h.canHeal()) return Action.EAT;                       // a golden apple (or food) at a safe distance
			p.setSprinting(sprintable);
			Vec3 away = p.position().subtract(foe.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
			return c.walkTo(p.position().add(away.normalize().scale(8)));
		}
		fleeing = false;

		Action surprise = c.antics.surprise(foe, d);                          // not in the script
		if (surprise != null) return surprise;

		h.ready(foe.isBlocking());                                             // an axe for a raised shield, else its sword
		float charge = p.getAttackStrengthScale(0.5f);
		if (charge < lastCharge - 0.3f) newSwing();                            // it just swung: a new cycle
		lastCharge = charge;
		boolean ready = charge >= 0.85f + 0.15f * gene(1);

		if (hurtNow && p.onGround() && d < 4.5 && random.nextFloat() < gene(4)) {
			h.watching = foe;                                                  // jump reset: jump into the hit
			p.setSprinting(sprintable);
			return Action.JUMP;
		}
		boolean falling = !p.onGround() && p.getDeltaMovement().y < 0 && p.fallDistance > 0;
		if (d <= p.entityInteractionRange()) {
			if (ready && foe.isBlocking() && !h.holdingAxe()) {
				h.watching = foe;                                              // a shield in the way and no axe: go around it
				return strafe();
			}
			if (ready) {
				if (falling) {
					p.setSprinting(false);                                     // let go of sprint: a critical hit
					return hit(foe);
				}
				if (!p.onGround()) {                                           // in the air, still going up: wait for the way down
					h.watching = foe;
					p.setSprinting(false);
					return idle();
				}
				if (goCrit && p.onGround()) {
					h.watching = foe;
					p.setSprinting(false);
					return Action.JUMP;                                        // jump now, strike on the way down
				}
				if (selecting && foe instanceof Player && now - foeSwungAt > 12 && waited++ < 20) {
					h.watching = foe;                                          // hit selecting: let it swing first
					return d < 2.7 ? Action.BACK : idle();
				}
				Action moved = h.lastMove();
				if (sprintable && (moved == Action.FORWARD || moved == Action.JUMP)) p.setSprinting(true);   // a sprint hit
				Action a = hit(foe);
				if (random.nextFloat() < gene(3) || foe instanceof net.minecraft.world.entity.monster.Creeper) sTap = 3;   // S-tap (from a creeper: always)
				return a;
			}
			h.watching = foe;                                                  // eyes on it while the swing charges
			if (sTap > 0) {
				sTap--;
				p.setSprinting(false);
				return Action.BACK;
			}
			if (useShield && h.raiseShield()) return idle();
			if (foeRising && d < 3.5 && random.nextFloat() < gene(6)) return Action.BACK;   // it jumps in for a crit: step out
			double spacing = 2.2 + 0.8 * gene(2);
			if (d < spacing - 0.2) return random.nextFloat() < gene(5) ? strafe() : Action.BACK;
			return random.nextFloat() < gene(5) ? strafe() : idle();
		}
		h.lowerShield();
		if (sTap > 0) {
			sTap--;
			h.watching = foe;
			return Action.BACK;
		}
		if (!ready && d < 3.2 + 0.8 * gene(2)) {                              // keep its distance until its sword is ready
			h.watching = foe;
			return idle();
		}
		p.setSprinting(sprintable && d > 2);
		return c.walkTo(foe.position());                                       // go after it (sprinting: a sprint hit)
	}

	/** Turn its back on it and sprint away (jumping up a step if one is in the way): no pathfinding, no time lost. */
	private Action runFrom(LivingEntity foe, boolean sprintable) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		h.lowerShield();
		h.watching = null;
		Vec3 away = p.position().subtract(foe.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
		h.face(p.getEyePosition().add(away.normalize().scale(6)));             // a flick of the mouse
		h.pitch = 0;
		p.setSprinting(sprintable);
		int[] f = xen.mod.core.Perception.forward(h.yaw);
		var level = p.level();
		net.minecraft.core.BlockPos ahead = p.blockPosition().offset(f[0], 0, f[1]);
		boolean wall = !level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
		return wall && p.onGround() ? Action.JUMP : Action.FORWARD;
	}

	private Action hit(LivingEntity foe) {
		if (Companion.DEBUG) {
			var p = c.player;
			XenMod.LOG.info("[xen fight] {} hits: charge {} falling {} sprinting {} fall {} ground {} foe hp {}", c.name,
					String.format("%.2f", p.getAttackStrengthScale(0.5f)), p.getDeltaMovement().y < 0, p.isSprinting(),
					String.format("%.2f", p.fallDistance), p.onGround(), String.format("%.1f", foe.getHealth()));
		}
		c.hands.hit(foe);
		c.acted = true;
		return Action.ATTACK;
	}

	private Action idle() {
		c.hands.pause(1);
		c.acted = true;
		return Action.IDLE;
	}

	private Action strafe() {
		if (random.nextFloat() < 0.1f) strafeRight = !strafeRight;
		return strafeRight ? Action.RIGHT : Action.LEFT;
	}

	/** The start of a swing: decide how to play this one. */
	private void newSwing() {
		goCrit = random.nextFloat() < gene(0);
		useShield = random.nextFloat() < gene(9);
		selecting = random.nextFloat() < gene(7);
		waited = 0;
	}
}
