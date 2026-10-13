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
	/** Hits taken in a row without landing one (three: its shield goes up), and until when it holds the shield up. */
	private int combo, comboAt, shieldUntil, windAt = -1000;
	/**
	 * What beating one of each kind of mob has cost it (health, as with stone tools and no armor), learned from its own
	 * fights: the health it lost from the moment it took one on till it was dead, shared out over the others that were at
	 * it too. A death counts as more than it had. Before its first, a rough idea of the kind (a zombie hits for 3, takes
	 * four hits of a stone sword).
	 */
	final java.util.Map<String, Float> cost = new java.util.HashMap<>();
	/** The fight it's in: health lost since it took on each foe (its id), and how many were at it then. */
	private final java.util.Map<Integer, float[]> engaged = new java.util.HashMap<>();
	/** Getting away from a fight it can't win: two blocks up out of reach (perched), or running; and since when. */
	private boolean perched, escaping, up;
	private int perchY, escapedAt, perchSeen;
	/** Its gut says get out of this fight (till when). */
	private int gutUntil = -1000;

	Fighter(Companion c) {
		this.c = c;
	}

	/** A fight gene (its style), played as well as its fighting skill allows (an unskilled Xen fumbles its own style). */
	private float gene(int i) {
		float g = c.personality.fightGenes[i];
		if (c.inArena) return g;
		return Math.max(0f, Math.min(1f, g * (0.6f + 0.8f * c.skills.get(Skills.FIGHT))));
	}

	/** The fight is over (for now: if it was backing off to heal, it still is until it has healed). */
	void reset() {
		lastFoe = null;
		sTap = 0;
		engaged.clear();
	}

	// ---------------------------------------------------------------------- can it win this? (learned from its fights)
	private static String kind(LivingEntity e) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	/** A rough first idea of what one of a kind costs (health lost beating it with a stone sword, no armor). */
	static float prior(String kind) {
		return switch (kind) {
			case "zombie", "husk", "drowned", "zombie_villager", "phantom" -> 5f;
			case "skeleton", "stray", "bogged", "vex", "cave_spider" -> 6f;
			case "spider" -> 4f;
			case "slime", "magma_cube" -> 3f;
			case "silverfish", "endermite" -> 2f;
			case "pillager", "zombified_piglin", "piglin", "breeze" -> 7f;
			case "creeper", "blaze", "guardian" -> 8f;
			case "witch", "hoglin", "zoglin" -> 9f;
			case "enderman" -> 10f;
			case "vindicator" -> 12f;
			case "piglin_brute", "wither_skeleton", "ravager", "evoker" -> 14f;
			case "warden" -> 40f;
			default -> 6f;
		};
	}

	private float costOf(String kind) {
		Float k = cost.get(kind);
		return k != null ? k : prior(kind);
	}

	/** How its gear changes what a fight costs: armor takes some of every hit, a better weapon ends it sooner (1: a stone sword, no armor). */
	private float gear() {
		var p = c.player;
		float best = 1;                                                      // (its fists)
		var inv = p.getInventory();
		for (int i = 0; i < 9; i++) {
			String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (!n.endsWith("_sword") && !n.endsWith("_axe")) continue;
			best = Math.max(best, n.startsWith("netherite") ? 8 : n.startsWith("diamond") ? 7 : n.startsWith("iron") ? 6 : n.startsWith("stone") ? 5 : 4);
		}
		float armor = Math.max(0.2f, 1 - 0.04f * Math.min(20, p.getArmorValue()));
		return armor * Math.max(0.6f, Math.min(2.5f, 5f / best));
	}

	/** What it expects the rest of this fight to cost it: the foe (as hurt as it is now) and the others about that are after it. */
	float expected(LivingEntity foe) {
		var p = c.player;
		float g = gear(), sum = 0;
		boolean counted = false;
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(8),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy)) {
			double d = m.distanceTo(p);
			if (m != foe && m.getTarget() != p && d > 4) continue;              // (only the ones after it, or right by it)
			if (m != foe && d > 3 && !p.hasLineOfSight(m)) continue;          // (what it can see, or hear right by it)
			sum += costOf(kind(m)) * g * (m.getHealth() / m.getMaxHealth()) * (m == foe ? 1f : 0.8f);
			counted |= m == foe;
		}
		if (!counted) sum += costOf(kind(foe)) * g * (foe.getHealth() / foe.getMaxHealth());
		return sum;
	}

	/** How much health it wants left at the end of a fight: its nerve (brave, proud, wrathful: less), the dark (more). */
	private float margin() {
		var who = c.personality;
		float m = 3 + 4 * (1 - who.bravery) - 2 * who.sin(xen.mod.core.Sins.PRIDE) - 1.5f * who.sin(xen.mod.core.Sins.WRATH);
		if (who.believes("live_to_fight")) m += 3;
		var p = c.player;
		if (p.level().isDarkOutside() && p.level().getMaxLocalRawBrightness(p.blockPosition()) < 7) m += 1.5f;
		return Math.max(1, m);
	}

	/**
	 * Its gut feels this fight going wrong (a monster on it, hurt): that feeling goes into its call on the fight (the tip
	 * "can't win this: get out of reach or away, heal, come back" comes to mind, and it takes it or fights on), instead of a
	 * step back on the spot that only gets it hit again. False: not a fight that's its call (the arena, a player).
	 */
	boolean gutSays(float[] fear, float danger) {
		if (lastFoe == null || lastFoe instanceof Player || c.inArena) return false;
		gutUntil = c.player.tickCount + 100;                                    // (the feeling goes into its call on the fight: see next())
		return true;
	}

	/** Getting out of a fight right now (going up, up there, or running to heal): its gut keeps quiet meanwhile. */
	boolean escapingNow() {
		return perched || escaping || fleeing;
	}

	/** It was hurt: that goes on what the foes it's taken on cost it. */
	void hurt(float amount) {
		for (float[] e : engaged.values()) e[0] += amount;
	}

	/** It beat one: what that one cost it is learned (a little each time). */
	void beat(LivingEntity foe) {
		float[] e = engaged.remove(foe.getId());
		if (e == null || foe instanceof Player) return;
		learn(kind(foe), e[0] / Math.max(1, e[1]));
	}

	/** It died fighting: whatever it was fighting cost more than it had. */
	void died() {
		if (lastFoe != null && !(lastFoe instanceof Player)) {
			float[] e = engaged.get(lastFoe.getId());
			float lost = e != null ? e[0] + 6 : 20;
			learn(kind(lastFoe), lost / Math.max(1, e != null ? e[1] : 1));
		}
		engaged.clear();
		perched = escaping = false;
	}

	private void learn(String kind, float lost) {
		float bare = Math.max(0.5f, Math.min(30f, lost / gear()));
		float was = costOf(kind), now = was + 0.3f * (bare - was);
		cost.put(kind, now);
		if (Math.abs(now - was) >= 0.5f) c.journal("learns", String.format(java.util.Locale.ROOT, "a %s costs it about %.0f health to beat now (was %.0f)", kind.replace('_', ' '), now, was));
	}

	com.google.gson.JsonObject saveCosts() {
		var o = new com.google.gson.JsonObject();
		for (var e : cost.entrySet()) o.addProperty(e.getKey(), Math.round(e.getValue() * 10) / 10f);
		return o;
	}

	void loadCosts(com.google.gson.JsonObject o) {
		if (o == null) return;
		for (var e : o.entrySet()) {
			try {
				cost.put(e.getKey(), Math.max(0.5f, Math.min(30f, e.getValue().getAsFloat())));
			} catch (RuntimeException ignored) {
				// (one it can't read: the rough idea again)
			}
		}
	}

	/** Mobs that only hit what they can reach (two blocks up, they can't): it can wait them out on a pillar. */
	private static boolean meleeOnly(String kind) {
		return switch (kind) {
			case "zombie", "husk", "drowned", "zombie_villager", "zombified_piglin", "piglin_brute", "vindicator", "hoglin", "zoglin", "silverfish", "endermite" -> true;
			default -> false;
		};
	}

	private static boolean free(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos b) {
		return level.getBlockState(b).getCollisionShape(level, b).isEmpty() && level.getFluidState(b).isEmpty();
	}

	/** Up two blocks is out of reach of everything at it (nothing shoots, climbs or flies), and it has the blocks and the room. */
	private boolean canPerch(LivingEntity foe) {
		var p = c.player;
		if (!p.onGround() || p.isInWater() || p.isInLava() || c.hands.spareBlock() == null || !meleeOnly(kind(foe))) return false;
		var feet = p.blockPosition();
		if (!free(p.level(), feet.above(2)) || !free(p.level(), feet.above(3))) return false;
		return p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(16),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && !meleeOnly(kind(m)) && p.hasLineOfSight(m)).isEmpty();
	}

	/** Two blocks up and out of reach: it pillars up, then hits down at what it can reach, eats, heals. Null: it can't (it runs). */
	private Action perch(LivingEntity foe, double d) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		if (perched && !meleeOnly(kind(foe))) {                              // one that shoots (or climbs): no use up there, down and away
			perched = false;
			return null;
		}
		perchSeen = p.tickCount;
		if (!perched) {
			perched = true;
			up = false;
			perchY = p.getBlockY();
			c.journal("thinks", String.format(java.util.Locale.ROOT, "out of this fight (%.0f health): two blocks up, out of the %s's reach", p.getHealth(), kind(foe).replace('_', ' ')));
			if (!c.inArena) c.chatter(c.personality.say("flee"), false);
		}
		h.lowerShield();
		p.setSprinting(false);
		if (p.getY() < perchY + 1.9 || !p.onGround()) {                      // still going up (in the air: the block isn't under it yet)
			c.goals.instant = "pillaring up out of reach";
			if (!p.onGround()) {
				h.watching = foe;
				return idle();
			}
			var feet = p.blockPosition();
			if (!free(p.level(), feet.above(2)) || !h.startPillar()) {        // (a roof, or out of blocks: it runs instead)
				perched = false;
				return null;
			}
			c.pillaring = true;
			return Action.JUMP;
		}
		c.goals.instant = "up out of reach, healing";
		if (!up) {
			up = true;
			c.journal("thinks", String.format(java.util.Locale.ROOT, "up on its pillar (%d blocks), out of the %s's reach: hits down at it, heals", p.getBlockY() - perchY, kind(foe).replace('_', ' ')));
		}
		h.watching = foe;
		h.ready(false, d);
		if (p.getAttackStrengthScale(0.5f) >= 0.95f && d <= p.entityInteractionRange() && p.hasLineOfSight(foe)) return hit(foe);   // a hit down at it
		if (p.getHealth() < p.getMaxHealth() && h.canHeal()) return Action.EAT;
		return idle();
	}

	/** Up on its pillar out of reach (healing, or waiting one out). */
	boolean perched() {
		return perched;
	}

	/**
	 * Nothing at it any more, but it's still up on its pillar: it stays up while it heals (eating if it can) or while one
	 * it can see is still close, for up to a minute. Null: down it goes, on with things.
	 */
	Action stayUp() {
		if (!perched) return null;
		XenPlayer p = c.player;
		if (p.getY() < perchY + 1.5 || p.tickCount - perchSeen > 1200) {
			perched = escaping = false;
			if (p.getY() >= perchY + 1.5) c.tidy.comeDown();                 // (still up there: it digs its way down, the pillar with it)
			return null;
		}
		boolean near = !p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(6),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && p.hasLineOfSight(m)).isEmpty();
		if (!near && p.getHealth() >= 16) {
			perched = escaping = false;
			c.journal("thinks", String.format(java.util.Locale.ROOT, "healed up (%.0f health), nothing about: down from its pillar", p.getHealth()));
			c.tidy.comeDown();
			return null;
		}
		c.goals.instant = "up out of reach, healing";
		c.hands.watching = null;
		if (p.getHealth() < p.getMaxHealth() && c.hands.canHeal()) return Action.EAT;
		return idle();
	}

	/** Its next move against foe (null: go on with other things). hurtNow: it was hit this tick. */
	Action next(LivingEntity foe, boolean hurtNow) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		int now = p.tickCount;
		if (foe != lastFoe) {
			if (foe instanceof Player && !c.inArena && !c.skills.sparringWith(foe.getUUID())) c.chatter(c.personality.say("fight"), false);
			if (!(foe instanceof Player) && !engaged.containsKey(foe.getId())) {
				int at = p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(8),
						m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && (m == foe || m.getTarget() == p)).size();
				engaged.put(foe.getId(), new float[] {0, Math.max(1, at)});
				while (engaged.size() > 12) engaged.remove(engaged.keySet().iterator().next());
			}
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

		if (hurtNow) {                                                         // hit again (and again): a combo on it
			combo = now - comboAt < 30 ? combo + 1 : 1;
			comboAt = now;
			if (combo >= 3 && c.knowledge.knows("combo_shield")) shieldUntil = now + 20;
		}
		if (foe instanceof net.minecraft.world.entity.monster.Creeper creeper && (creeper.getSwellDir() > 0 || creeper.isIgnited()) && d < 5) {
			c.goals.instant = "getting away from the creeper";                 // it's hissing: run, like anyone would
			if (!c.inArena) c.chatter("Creeper! Run!", false);
			if (d < 3 && c.knowledge.knows("creeper_block")) {                  // right on it (a tunnel, no room to run): what players do
				if (p.getAttackStrengthScale(0.5f) > 0.9f && d <= p.entityInteractionRange()) {
					sTap = 3;                                                  // a hit knocks it back...
					return hit(foe);
				}
				net.minecraft.core.BlockPos between = p.blockPosition().relative(net.minecraft.core.Direction.getApproximateNearest(
						foe.getX() - p.getX(), 0, foe.getZ() - p.getZ()));
				if (h.placeAt(between, "any")) {                                // ...then a block between
					c.goals.instant = "a block between it and the creeper";
					c.acted = true;
					return Action.PLACE;
				}
				c.journal("thinks", "no block between it and the creeper: " + h.cantPlace);
				if (h.raiseShield()) return idle();                            // or the shield
			}
			return runFrom(foe, sprintable);
		}

		float retreat = 0.4f * gene(8) * Math.max(0.2f, 1 - 0.5f * c.personality.sin(xen.mod.core.Sins.PRIDE) - 0.3f * c.personality.sin(xen.mod.core.Sins.WRATH));
		if (c.personality.believes("warrior_never_runs")) retreat = Math.min(retreat, 0.12f);   // (it stands its ground till the very end)
		if (c.personality.believes("live_to_fight")) retreat = Math.max(retreat, 0.45f);        // (it backs off early and lives)
		if (!(foe instanceof Player) && !c.inArena) {                          // mobs: outnumbered, or in the dark, anyone backs off sooner (most of its deaths were two or three mobs, at night)
			int mobs = p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(10), m -> m.isAlive()).size();
			float more = Math.min(0.3f, 0.12f * Math.max(0, mobs - 1)) + (p.level().isDarkOutside() && p.level().getMaxLocalRawBrightness(p.blockPosition()) < 7 ? 0.08f : 0);
			retreat = Math.max(retreat, Math.min(0.6f, retreat + (c.personality.believes("warrior_never_runs") ? 0.5f : 1f) * more));
		}
		float health = p.getHealth() / p.getMaxHealth();
		// Can it win this? What this kind (and the others at it) have cost it before, against the health it has: if it would
		// come out of it with next to nothing, a player gets out of reach first (two blocks up: a zombie can't hit that) or
		// away, heals, and only then takes it on again. Its own nerve says how close it cuts it (a warrior never runs).
		boolean outmatched = false;
		if (!(foe instanceof Player) && !c.inArena) {
			float left = p.getHealth() - expected(foe), want = margin();
			if (escaping || perched) outmatched = left < want + 4 && p.getHealth() < 18;   // (it took the tip: away till it has healed, then back at it)
			else if (left < want || now < gutUntil && p.getHealth() < 16) {    // it sees it going badly (or feels it): a tip comes to mind, and it's its call
				float pull = Math.max(0, Math.min(1, (want - left) / Math.max(4, want))) + (now < gutUntil ? 0.3f : 0);
				outmatched = c.tips.take(Tips.Tip.CANT_WIN, pull, String.format(java.util.Locale.ROOT, "%.0f health, the %s would cost it about %.0f%s",
						p.getHealth(), kind(foe).replace('_', ' '), expected(foe), now < gutUntil ? ", its gut says get out" : ""), 60);
			}
		}
		if (outmatched && !escaping) {
			escaping = true;
			escapedAt = now;
		}
		if (!outmatched && escaping) escaping = false;
		if (perched || outmatched && canPerch(foe)) {
			Action up = perch(foe, d);
			if (up != null) return up;
		}
		Action gear = c.kit.use(foe, d, fleeing || outmatched || retreat > 0 && health < retreat);   // potions, cobwebs, pearls, golden apples
		if (gear != null) return gear;
		if (outmatched || retreat > 0 && (health < retreat || fleeing && health < retreat + 0.2f)) {
			if (!fleeing && !c.inArena) c.chatter(c.personality.say("flee"), false);
			fleeing = true;                                                    // back off until it has healed a bit
			c.goals.instant = "backing off to heal";
			h.lowerShield();
			if (d > (outmatched ? 6.5 : 5) && h.canHeal()) return Action.EAT;   // a golden apple (or food) at a safe distance (eating takes a second and a half: a zombie closes in)
			p.setSprinting(sprintable);
			LivingEntity shooter = outmatched ? shooterInSight() : null;     // one shooting at it: out of its sight, not just away (it'd be shot in the back)
			if (shooter != null) {
				Action cover = takeCover(shooter);
				if (cover != null) return cover;
			}
			Vec3 dir = escapeDir(foe);
			if (outmatched && clearRun(dir)) return runAlong(dir, sprintable);   // a clear way: just sprint off (no time to work out a way)
			return c.walkTo(p.position().add(dir.scale(8)));
		}
		fleeing = false;

		Action surprise = c.antics.surprise(foe, d);                          // not in the script
		if (surprise != null) return surprise;

		h.ready(foe.isBlocking(), d);                                          // an axe for a raised shield, a spear out of a sword's reach, else its sword
		float charge = p.getAttackStrengthScale(0.5f);
		if (charge < lastCharge - 0.3f) newSwing();                            // it just swung: a new cycle
		lastCharge = charge;
		boolean ready = charge >= 0.85f + 0.15f * gene(1);
		boolean spear = h.holdingSpear();
		double reach = spear ? (d >= Hands.SPEAR_MIN ? Hands.SPEAR_REACH : 0) : p.entityInteractionRange();   // (a spear can't jab closer than 2)

		// A mace and a wind charge: one at its feet throws it up, and it comes down on the foe with the mace (a smash).
		if (ready && p.onGround() && d < 3.5 && now - windAt > 40 && c.knowledge.knows("wind_charge") && c.knowledge.knows("mace")
				&& h.carries("mace") && h.carries("wind_charge") && foe.getY() <= p.getY() + 0.5) {
			windAt = now;
			if (h.throwDown("wind_charge")) {
				c.goals.instant = "up with a wind charge, for a mace smash";
				c.acted = true;
				return Action.JUMP;
			}
		}
		if (spear && d < Hands.SPEAR_MIN && !hurtNow) {                       // too close for its jab: a step back to spear range
			h.watching = foe;
			return Action.BACK;
		}

		if (hurtNow && p.onGround() && d < 4.5 && random.nextFloat() < gene(4)) {
			h.watching = foe;                                                  // jump reset: jump into the hit
			p.setSprinting(sprintable);
			return Action.JUMP;
		}
		boolean falling = !p.onGround() && p.getDeltaMovement().y < 0 && p.fallDistance > 0;
		if (now < shieldUntil && !ready && h.raiseShield()) {                // a combo on it: shield up till it can hit back
			h.watching = foe;
			return idle();
		}
		if (d <= reach) {
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
			double spacing = spear && c.knowledge.knows("spear_reach") ? 3.6 : 2.2 + 0.8 * gene(2);   // (a spear: out of a sword's reach)
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
		String kind = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(foe.getType()).getPath();
		boolean archer = kind.endsWith("skeleton") || kind.equals("stray") || kind.equals("bogged");
		if (archer && d > 4 && c.knowledge.knows("skeleton_dodge")) {        // side to side on the way in, eyes on it: its arrows miss
			h.watching = foe;
			int phase = (now / 8) % 4;                                          // (a strafe, a step in, the other strafe, a step in)
			return phase == 0 ? Action.LEFT : phase == 2 ? Action.RIGHT : Action.FORWARD;
		}
		return c.walkTo(foe.position());                                       // go after it (sprinting: a sprint hit)
	}

	/** That way, the next few blocks: ground to run on, no drop, no lava or water, no wall it can't jump (else it works out a way). */
	private boolean clearRun(Vec3 dir) {
		XenPlayer p = c.player;
		var level = p.level();
		for (int i = 1; i <= 3; i++) {
			var at = net.minecraft.core.BlockPos.containing(p.position().add(dir.scale(i)));
			if (!free(level, at)) {                                            // a step up: it can jump that, with room over it
				if (!free(level, at.above()) || !free(level, at.above(2))) return false;
				continue;
			}
			if (!free(level, at.above())) return false;
			boolean ground = false;
			for (int dy = 0; dy <= 2 && !ground; dy++) {                        // (a step down or two is fine)
				var below = at.below(dy + 1);
				if (!level.getFluidState(below).isEmpty()) return false;
				ground = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
			}
			if (!ground) return false;
		}
		return true;
	}

	/** Which way it runs: away from all that's after it (the closer, the more), and that way for a moment (no dithering between foes). */
	private Vec3 runDir;
	private int runDirAt;

	private Vec3 escapeDir(LivingEntity foe) {
		XenPlayer p = c.player;
		int now = p.tickCount;
		if (runDir != null && now - runDirAt < 40 && clearRun(runDir)) return runDir;
		Vec3 sum = Vec3.ZERO;
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(16),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && (m.getTarget() == p || m.distanceTo(p) < 6))) {
			Vec3 v = p.position().subtract(m.position()).multiply(1, 0, 1);
			double dd = Math.max(1, v.lengthSqr());
			sum = sum.add(v.scale(1 / dd));
		}
		if (sum.lengthSqr() < 1e-8) sum = p.position().subtract(foe.position()).multiply(1, 0, 1);
		if (sum.lengthSqr() < 1e-8) sum = new Vec3(1, 0, 0);
		Vec3 base = sum.normalize();
		runDir = base;
		runDirAt = now;
		for (int k : new int[] {0, 30, -30, 60, -60, 90, -90}) {             // straight away if it can, else the nearest way that's clear
			Vec3 dir = base.yRot((float) Math.toRadians(k));
			if (clearRun(dir)) {
				runDir = dir;
				break;
			}
		}
		return runDir;
	}

	private static boolean shoots(String kind) {
		return switch (kind) {
			case "skeleton", "stray", "bogged", "pillager", "witch", "blaze", "piglin" -> true;
			default -> false;
		};
	}

	/** The nearest one about that shoots and can see it (16 blocks), or null. */
	private LivingEntity shooterInSight() {
		XenPlayer p = c.player;
		LivingEntity best = null;
		double bestD = 16;
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(16),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && shoots(kind(m)))) {
			double dd = m.distanceTo(p);
			if (dd < bestD && m.hasLineOfSight(p)) {
				bestD = dd;
				best = m;
			}
		}
		return best;
	}

	/** Can it (its eyes) see that spot? */
	private boolean seen(LivingEntity by, Vec3 at) {
		var hit = c.player.level().clip(new net.minecraft.world.level.ClipContext(by.getEyePosition(), at, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, by));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	/** A spot close by it can't see (behind a tree, a hill, a wall): there, then it heals; null: none (it runs). */
	private net.minecraft.core.BlockPos cover;
	private int coverAt = -1000;

	private Action takeCover(LivingEntity shooter) {
		XenPlayer p = c.player;
		var level = p.level();
		int now = p.tickCount;
		String what = kind(shooter).replace('_', ' ');
		if (!seen(shooter, p.getEyePosition()) && !seen(shooter, p.position().add(0, 0.5, 0))) {   // out of its sight already: it stays, heals
			c.goals.instant = "in cover from the " + what + ", healing";
			c.hands.watching = null;
			if (p.getHealth() < p.getMaxHealth() && c.hands.canHeal()) return Action.EAT;
			return idle();
		}
		if (now - coverAt > 20) {
			coverAt = now;
			var was = cover;
			cover = null;
			var feet = p.blockPosition();
			for (int r = 1; r <= 6 && cover == null; r++) {                    // the nearest spot out of its sight
				double best = Double.MAX_VALUE;
				for (int a = 0; a < 16; a++) {
					double ang = a * Math.PI / 8;
					var q = feet.offset((int) Math.round(Math.cos(ang) * r), 0, (int) Math.round(Math.sin(ang) * r));
					for (int dy : new int[] {0, 1, -1}) {
						var s = q.above(dy);
						var under = s.below();
						if (!free(level, s) || !free(level, s.above()) || level.getBlockState(under).getCollisionShape(level, under).isEmpty()
								|| !level.getFluidState(under).isEmpty()) continue;
						Vec3 eye = Vec3.atBottomCenterOf(s).add(0, 1.6, 0);
						if (seen(shooter, eye) || seen(shooter, Vec3.atBottomCenterOf(s).add(0, 0.5, 0))) continue;
						double score = Vec3.atBottomCenterOf(s).distanceTo(shooter.position());
						score = -score;                                           // (of those as near, the one further from it)
						if (score < best) {
							best = score;
							cover = s;
						}
						break;
					}
				}
			}
			if (cover != null && (was == null || !was.equals(cover)))
				c.journal("thinks", String.format(java.util.Locale.ROOT, "the %s is shooting at it: takes cover %d blocks off, out of its sight", what,
						(int) Math.round(Math.sqrt(cover.distSqr(feet)))));
		}
		if (cover == null) return null;
		c.goals.instant = "taking cover from the " + what;
		return c.walkTo(Vec3.atBottomCenterOf(cover));
	}

	/** Turn its back on it and sprint away (jumping up a step if one is in the way): no pathfinding, no time lost. */
	private Action runFrom(LivingEntity foe, boolean sprintable) {
		Vec3 away = c.player.position().subtract(foe.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
		return runAlong(away.normalize(), sprintable);
	}

	/** Sprint that way (jumping up a step if one is in the way). */
	private Action runAlong(Vec3 away, boolean sprintable) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		h.lowerShield();
		h.watching = null;
		h.face(p.getEyePosition().add(away.scale(6)));                        // a flick of the mouse
		h.pitch = 0;
		p.setSprinting(sprintable);
		int[] f = xen.mod.core.Perception.forward(h.yaw);
		var level = p.level();
		net.minecraft.core.BlockPos ahead = p.blockPosition().offset(f[0], 0, f[1]);
		boolean wall = !level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
		return wall && p.onGround() ? Action.JUMP : Action.FORWARD;
	}

	private Action hit(LivingEntity foe) {
		combo = 0;
		if (Companion.DEBUG) {
			var p = c.player;
			XenMod.LOG.info("[xen fight] {} hits: charge {} falling {} sprinting {} fall {} ground {} foe hp {}", c.name,
					String.format("%.2f", p.getAttackStrengthScale(0.5f)), p.getDeltaMovement().y < 0, p.isSprinting(),
					String.format("%.2f", p.fallDistance), p.onGround(), String.format("%.1f", foe.getHealth()));
		}
		c.hands.hit(foe);
		c.skills.practice(Skills.FIGHT, 0.004f);
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
