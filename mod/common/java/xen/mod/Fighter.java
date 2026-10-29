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
		g += Skills.nameKnack(c.name, i);                                      // (its name's knack: one times its crits better, another strafes)
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
		float m = 2 + 3.5f * (1 - who.bravery) - 2 * who.sin(xen.mod.core.Sins.PRIDE) - 1.5f * who.sin(xen.mod.core.Sins.WRATH);   // (a player takes a gamble now and then: it cuts it a little closer)
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
		if (c.btb.on()) c.mod.btbLearner.mobBeaten(kind(foe));
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
	/** One that hits from a way off: not melee-only, or a drowned with a trident (it throws it). A pillar is no use against those. */
	private static boolean ranged(LivingEntity m) {
		return !meleeOnly(kind(m)) || kind(m).equals("drowned") && m.getMainHandItem().is(net.minecraft.world.item.Items.TRIDENT);
	}

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
		if (!p.onGround() || p.isInWater() || p.isInLava() || c.hands.spareBlock() == null || ranged(foe)) return false;
		var feet = p.blockPosition();
		if (!free(p.level(), feet.above(2)) || !free(p.level(), feet.above(3))) return false;
		return p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(16),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && ranged(m) && p.hasLineOfSight(m)).isEmpty();
	}

	/** Two blocks up and out of reach: it pillars up, then hits down at what it can reach, eats, heals. Null: it can't (it runs). */
	private Action perch(LivingEntity foe, double d) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		if (perched && (ranged(foe) || p.tickCount % 10 == 0 && shooterInSight() != null)) {   // one that shoots (or climbs), or a skeleton come along: no use up there, down and away
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
		if (foe instanceof net.minecraft.world.entity.monster.Creeper creeper && (creeper.getSwellDir() > 0 || creeper.isIgnited()) && d < 7.5) {   // (the fuse goes out past 7 blocks: it runs till then)
			c.goals.instant = "getting away from the creeper";                 // it's hissing: run, like anyone would
			if (!c.inArena) c.chatter("Creeper! Run!", false);
			if (d < 5 && h.carries("shield") && h.raiseShield()) {             // a shield up and facing it takes the whole blast: up, and backing off
				h.watching = foe;
				c.goals.instant = "shield up to the hissing creeper";
				p.setSprinting(false);
				return ledgeBehind(p, foe) ? Action.LEFT : Action.BACK;             // (a drop behind it: sideways, not backwards off the edge)
			}
			if (d < 3 && p.getAttackStrengthScale(0.5f) > 0.9f && d <= p.entityInteractionRange()) {
				sTap = 6;                                                      // a hit knocks it back (then away: back, till it stops hissing)
				return hit(foe);
			}
			if (d < 3 && c.knowledge.knows("creeper_block")) {                  // right on it (a tunnel, no room to run): what players do
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

		noRun = false;
		if (c.btb.on() && !(foe instanceof Player) && !c.inArena) {          // Back to Basics: one call (fight, dig in, out of sight), then it sticks to it
			Action plan = btbPlan(foe, d);
			if (plan != null) return plan;
		}

		Action screen = noRun ? null : creeperScreen();                        // a skeleton and a creeper: the creeper between it and the arrows
		if (screen != null) return screen;

		float retreat = 0.4f * gene(8) * Math.max(0.2f, 1 - 0.5f * c.personality.sin(xen.mod.core.Sins.PRIDE) - 0.3f * c.personality.sin(xen.mod.core.Sins.WRATH));
		if (c.personality.believes("warrior_never_runs")) retreat = Math.min(retreat, 0.12f);   // (it stands its ground till the very end)
		if (c.personality.believes("live_to_fight")) retreat = Math.max(retreat, 0.45f);        // (it backs off early and lives)
		if (!(foe instanceof Player) && !c.inArena)                             // the first rule: don't get low against monsters (back off, heal, then again); how low is its style's call
			retreat = Math.max(retreat, c.personality.believes("warrior_never_runs") ? Math.min(0.4f, floor()) : floor());
		if (!(foe instanceof Player) && !c.inArena) {                          // mobs: outnumbered, or in the dark, anyone backs off sooner (most of its deaths were two or three mobs, at night)
			int mobs = p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(10), m -> m.isAlive()).size();
			float more = Math.min(0.3f, 0.12f * Math.max(0, mobs - 1)) + (p.level().isDarkOutside() && p.level().getMaxLocalRawBrightness(p.blockPosition()) < 7 ? 0.08f : 0);
			retreat = Math.max(retreat, Math.min(0.6f, retreat + (c.personality.believes("warrior_never_runs") ? 0.5f : 1f) * more));
		}
		if (noRun) retreat = 0;                                                // (it chose to fight: it fights)
		float health = p.getHealth() / p.getMaxHealth();
		// Can it win this? What this kind (and the others at it) have cost it before, against the health it has: if it would
		// come out of it with next to nothing, a player gets out of reach first (two blocks up: a zombie can't hit that) or
		// away, heals, and only then takes it on again. Its own nerve says how close it cuts it (a warrior never runs).
		boolean outmatched = false, rush = false;
		if (!(foe instanceof Player) && !c.inArena) {
			float left = p.getHealth() - expected(foe), want = margin();
			// A skeleton close by and a weapon that kills it in two crits: a player rushes it (walking off to cover, it's shot
			// in the back all the way); only it after it, and health for the two or three arrows on the way in.
			rush = archer(foe) && d <= 6 && Math.min(critsToKill(foe, best("_axe")), critsToKill(foe, best("_sword"))) <= 2 && p.getHealth() >= 6
					&& p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(10), m -> m.isAlive() && m != foe && m.getTarget() == p).isEmpty();
			if (rush) {
				if (now - rushSaidAt > 600) {
					rushSaidAt = now;
					c.journal("fight", String.format(java.util.Locale.ROOT, "rushes the %s %.0f blocks off (two crits kill it; cover is further than that)", kind(foe).replace('_', ' '), d));
				}
			} else if (noRun) {
				outmatched = false;                                                // (Back to Basics made its call: no tips, no second thoughts)
			} else if (escaping || perched) outmatched = left < want + 4 && p.getHealth() < 18;   // (it took the tip: away till it has healed, then back at it)
			else if (left < want || now < gutUntil && p.getHealth() < 16) {    // it sees it going badly (or feels it): a tip comes to mind, and it's its call
				float pull = Math.max(0, Math.min(1, (want - left) / Math.max(4, want))) + (now < gutUntil ? 0.3f : 0);
				outmatched = c.tips.take(Tips.Tip.CANT_WIN, pull, String.format(java.util.Locale.ROOT, "%.0f health, the %s would cost it about %.0f%s",
						p.getHealth(), kind(foe).replace('_', ' '), expected(foe), now < gutUntil ? ", its gut says get out" : ""), 60);
			}
		}
		if (!noRun && !rush && !outmatched && !(foe instanceof Player) && !c.inArena) {   // what's coming, not only what's on it: all of them about, the dark, its armor, before it's low
			float group = threatAround();
			if (group > p.getHealth() - margin() && group > expected(foe) + 1) {
				outmatched = true;
				if (now - threatSaidAt > 400) {
					threatSaidAt = now;
					c.journal("fight", String.format(java.util.Locale.ROOT, "sees trouble coming (%.0f health, all of them about would cost about %.0f%s): gets out before it's hurt",
							p.getHealth(), group, p.level().isDarkOutside() ? ", in the dark" : ""));
				}
			}
		}
		if (!rush && !outmatched && !(foe instanceof Player) && !c.inArena && kind(foe).equals("piglin_brute") && c.knowledge.knows("piglin_brute") && !c.bruteReady()) {
			outmatched = true;                                                 // a brute, short of diamond: no fight to take, away (and out of reach of that axe)
			if (now - bruteSaidAt > 600) {
				bruteSaidAt = now;
				c.journal("fight", "a piglin brute: its axe takes half my hearts and I'm not in diamond, so away");
			}
		}
		if (noRun) outmatched = false;
		if (outmatched && !escaping) {
			escaping = true;
			escapedAt = now;
		}
		if (!outmatched && escaping) escaping = false;
		int onIt = p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(6), m -> m.isAlive() && m.getTarget() == p).size();
		boolean crowd = !noRun && style() == Style.CONTROLLER && health < 0.85f && onIt >= 2;   // (the terrain is its weapon: two on it, it takes the high ground before it's low)
		if (perched || (outmatched || crowd) && canPerch(foe)) {
			Action up = perch(foe, d);
			if (up != null) return up;
		}
		boolean tall = kind(foe).equals("enderman") || kind(foe).equals("wither_skeleton");   // (nearly three blocks tall, and two and a half: neither fits under two)
		if (tall && !c.inArena && (p.getHealth() < 16 || outmatched || expected(foe) > p.getHealth() - 6)) {
			Action roof = underLowRoof(foe, d);                                // they can't get under a roof two blocks up: in there, and hit their legs
			if (roof != null) return roof;
		}
		if (!noRun && Companion.archer(foe) && !(foe instanceof Player) && !c.inArena && d > 6 && !h.carries("bow") && !h.carries("crossbow")) {   // a skeleton off over there: walled off, not fought (no bow to answer it with)
			Action wall = wallOff(foe, kind(foe).replace('_', ' '));
			if (wall != null) return wall;
		}
		Action gear = c.kit.use(foe, d, fleeing || outmatched || retreat > 0 && health < retreat);   // potions, cobwebs, pearls, golden apples
		if (gear != null) return gear;
		// About to die with nothing to fight with: anyone runs (its gut, not a choice), to a friend if one's about (and
		// shouts for help), else behind something it can't be seen through (a tree, a wall), else just away.
		boolean bareInTheDark = !h.hasWeapon() && !(foe instanceof Player) && p.level().isDarkOutside() && !(foe instanceof net.minecraft.world.entity.monster.Creeper)
				&& expected(foe) > p.getHealth() * 0.4f;                       // (just respawned at night with nothing, say: fists against a zombie is how it died)
		boolean desperate = !noRun && !c.inArena && !c.skills.sparringWith(foe.getUUID()) && (p.getHealth() <= 6 && (!h.hasWeapon() || p.getHealth() <= 3 && !h.canHeal()) || bareInTheDark);
		boolean finishing = !(foe instanceof Player) && !desperate && !(foe instanceof net.minecraft.world.entity.monster.Creeper) && d <= p.entityInteractionRange() + 0.5
				&& p.getHealth() >= 6 && foe.getHealth() <= hitNow() && p.getAttackStrengthScale(0.5f) >= 0.8f;   // nearly dead and in reach: it takes the hit to finish it (a trade worth making)
		if (finishing && (health < retreat || outmatched) && now - saidWhyAt > 100) {
			saidWhyAt = now;
			c.journal("fight", String.format(java.util.Locale.ROOT, "takes a hit to finish the %s (%.0f health left on it, %.0f on itself)", kind(foe).replace('_', ' '), foe.getHealth(), p.getHealth()));
		}
		if (noRun) fleeing = false;
		if (!finishing && !rush && !noRun && (outmatched || desperate || retreat > 0 && (health < retreat || fleeing && health < retreat + 0.2f) || fleeing && !c.inArena && !safe() && (p.getHealth() < 16 || threatAround() > p.getHealth() - margin()))) {   // (once it's getting out, it keeps at it till it's safe, or healed with nothing it can't handle)
			if (!fleeing && !c.inArena) {
				c.chatter(c.personality.say("flee"), false);
				c.journal("fight", String.format(java.util.Locale.ROOT, "backs off at %.0f health (%s: keeps %.0f in hand)%s", p.getHealth(), style().words, retreat * p.getMaxHealth(),
						outmatched ? ", the fight would cost more than it has" : desperate ? ", nothing to fight with" : ""));
			}
			fleeing = true;                                                    // back off until it has healed a bit
			fledAt = now;
			c.goals.instant = desperate ? "running for its life" : "backing off to heal";
			LivingEntity archerOnIt = shooterInSight();
			if (archerOnIt != null && h.carries("shield") && h.raiseShield()) {   // one shooting at it, and a shield: up and facing it, backing off (turning its back to run, it's shot in the back)
				h.watching = archerOnIt;
				c.goals.instant = "backing off behind its shield from the " + kind(archerOnIt).replace('_', ' ');
				p.setSprinting(false);
				return Action.BACK;
			}
			h.lowerShield();
			if (d > (outmatched || desperate ? 6.5 : 5) && h.canHeal() && clearAround(4.5)) return Action.EAT;   // a golden apple (or food) at a safe distance from all of them (eating takes a second and a half: a zombie closes in)
			p.setSprinting(sprintable);
			if (desperate || outmatched) {
				Action friend = toFriend(foe, d, sprintable);
				if (friend != null) return friend;
			}
			LivingEntity shooter = shooterInSight();                           // one shooting at it: out of its sight, not just away (it'd be shot in the back)
			if (shooter == null && desperate && foe instanceof Player) shooter = foe;   // (a player after it: out of their sight too, behind a tree)
			boolean meleeOnIt = !Companion.archer(foe) && !(foe instanceof Player) && d < 4.5;   // (a zombie at it: cover a step away from the arrows is no escape from that)
			if (shooter != null && (outmatched || desperate) && !meleeOnIt) {
				Action cover = takeCover(shooter);
				if (cover != null) {
					coverUntil = now + 30;
					return cover;
				}
			} else if (shooter == null && cover != null && now < coverUntil && !meleeOnIt) {   // (on its way to cover and the shooter out of sight a moment: on to it, not off the other way)
				c.goals.instant = "taking cover";
				return c.walkTo(Vec3.atBottomCenterOf(cover));
			}
			Vec3 dir = escapeDir(foe);
			if (shooter != null) dir = weave(dir);                              // zig-zag: an arrow aimed where it was going misses
			if (outmatched && clearRun(dir)) return runAlong(dir, sprintable);   // a clear way: just sprint off (no time to work out a way)
			if (!clearRun(dir) && d < 4.5 && !archer(foe)) {                    // nowhere to run and one on it: blocks between them, not wandering about
				Action box = wallOff(foe, kind(foe).replace('_', ' '), 1.2);
				if (box != null) return box;
			}
			return c.walkTo(p.position().add(dir.scale(8)));
		}
		fleeing = false;

		float healAt = style().healAt;
		if (p.getHealth() <= healAt && d > 2.2 && now - gappleAt > 600 && h.carries("golden_apple")) {   // under pressure: a golden apple before it's low (absorption, regen), like a player in a trial chamber
			int on = 0;
			for (net.minecraft.world.entity.Mob m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(8),
					x -> x.isAlive() && x.getTarget() == p)) on++;
			if (on >= 2 || p.getHealth() <= healAt - 4) {
				c.journal("fight", String.format(java.util.Locale.ROOT, "a golden apple at %.0f health with %d at it: before it gets low (%s)", p.getHealth(), on, style().words));
				gappleAt = now;
				c.goals.instant = "a golden apple mid-fight";
				h.watching = foe;
				return Action.EAT;
			}
		}

		Action surprise = c.antics.surprise(foe, d);                          // not in the script
		if (surprise != null) return surprise;

		LivingEntity urgent = mostUrgent(foe);                                 // more than one at it: the shield (and its eyes) to whichever hits first
		if (urgent != foe && urgent != null && !p.isUsingItem() && h.carries("shield") && (archer(urgent) ? urgent.getTicksUsingItem() >= 12 : urgent.distanceTo(p) < 3) && h.raiseShield()) {
			h.watching = urgent;
			c.goals.instant = "shield up to the " + kind(urgent) + " first";
			return around >= 3 ? Action.BACK : idle();                        // (three or more round it: back off, so they're all in front of it)
		}

		boolean axeSooner = archer(foe) && axeKillsSooner(foe);                // a skeleton: the weapon that kills it in fewer crits (a stone axe: two)
		if (axeSooner && now - axeSaidAt > 1200) {
			axeSaidAt = now;
			c.journal("thinks", String.format(java.util.Locale.ROOT, "the %s has %.0f health: its axe kills it in %d crits, its sword in %d, so the axe",
					kind(foe).replace('_', ' '), foe.getHealth(), critsToKill(foe, best("_axe")), critsToKill(foe, best("_sword"))));
		}
		h.ready(foe.isBlocking() || axeSooner, d);                             // an axe for a raised shield (or a skeleton), a spear out of a sword's reach, else its sword
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
		String kindNow = kind(foe);
		boolean shooter = kindNow.endsWith("skeleton") || kindNow.equals("stray") || kindNow.equals("bogged") || kindNow.equals("pillager") || kindNow.equals("witch") || kindNow.equals("blaze");
		boolean melee = !(foe instanceof Player) && !shooter && !(foe instanceof net.minecraft.world.entity.monster.Creeper);
		boolean opening = melee && now - foeSwungAt < 14;                       // (a zombie just swung: it can't hit again for a moment, that's the time to be close)
		boolean axe = foe instanceof Player pl && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(pl.getMainHandItem().getItem()).getPath().endsWith("_axe");
		boolean eating = foe instanceof Player && foe.isUsingItem() && !foe.isBlocking()
				&& !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(foe.getUseItem().getItem()).getPath().matches("bow|crossbow|trident");
		if (axe) h.lowerShield();                                              // (an axe knocks a raised shield out for five seconds: it dodges instead)
		if (eating && d > reach && !c.skills.sparringWith(foe.getUUID())) {     // eating or drinking: slow and can't swing, that's the time to close in
			h.watching = foe;
			p.setSprinting(sprintable);
			return c.walkTo(foe.position());
		}
		if (now < shieldUntil && !ready && !axe && h.raiseShield()) {        // a combo on it: shield up till it can hit back
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
				boolean edge = !(foe instanceof Player) && dropBehind(foe);   // (any of them with a drop or lava behind it, a creeper too: a sprint hit and the fall does the rest, no fight)
				int bunched = melee ? bunchedBy(foe) : 0;
				if (bunched >= 1 && p.onGround() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath().endsWith("_sword")) {
					h.watching = foe;                                          // a sweep: on the ground, not sprinting, a full swing: every one bunched by it is hit and knocked back too (Sweeping Edge: hard)
					p.setSprinting(false);
					Action a = hit(foe);
					sTap = 4;
					if (now - knockSaidAt > 200) {
						knockSaidAt = now;
						c.journal("fight", String.format(java.util.Locale.ROOT, "sweeps the %s and the %d by it, then steps back while its sword recharges", kindNow.replace('_', ' '), bunched));
					}
					return a;
				}
				if ((melee && around >= 2 || edge) && p.onGround() && p.getFoodData().getFoodLevel() > 6 && !foe.isBlocking()) {   // knockback: a sprint hit sends it flying (over the edge, into lava, or out of the crowd), then back while the sword recharges
					h.watching = foe;
					p.setSprinting(true);
					Action a = hit(foe);
					sTap = 4;
					if (now - knockSaidAt > 200) {
						knockSaidAt = now;
						c.journal("fight", edge ? "knocks the " + kindNow.replace('_', ' ') + " back over the edge" : String.format(java.util.Locale.ROOT,
								"knocks the %s back (a sprint hit: %d of them at it) and steps back while its sword recharges", kindNow.replace('_', ' '), around));
					}
					return a;
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
				if (random.nextFloat() < gene(3)) sTap = 3;                     // S-tap
				if (foe instanceof net.minecraft.world.entity.monster.Creeper) sTap = 6;   // (a creeper: hit and run, back till it stops hissing, then in again)
				return a;
			}
			h.watching = foe;                                                  // eyes on it while the swing charges
			if (sTap > 0) {
				sTap--;
				p.setSprinting(false);
				return Action.BACK;
			}
			Action snack = snack(foe);
			if (snack != null) return snack;
			if (useShield && !axe && h.raiseShield()) return idle();
			if (foeRising && d < 3.5 && random.nextFloat() < gene(6)) return Action.BACK;   // it jumps in for a crit: step out
			if (opening) return idle();                                        // (its swing just went: no need to back off, it's ready first)
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
		if (!ready && d < 3.2 + 0.8 * gene(2) && !opening) {                  // keep its distance until its sword is ready
			h.watching = foe;
			Action snack = snack(foe);
			return snack != null ? snack : idle();
		}
		boolean walker = !(foe instanceof Player) && !(foe instanceof net.minecraft.world.entity.monster.Creeper) && !archer(foe)
				&& foe instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == p;
		if (walker && unsure() && d < 7 && h.carries("shield") && h.raiseShield()) {   // defense first: a monster on its way to it, shield up, let it come
			h.watching = foe;
			c.goals.instant = "shield up, letting the " + kind(foe).replace('_', ' ') + " come to it";
			return idle();
		}
		p.setSprinting(sprintable && d > 2);
		String kind = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(foe.getType()).getPath();
		boolean archer = kind.endsWith("skeleton") || kind.equals("stray") || kind.equals("bogged");
		int drawn = foe.isUsingItem() ? foe.getTicksUsingItem() : 0;             // a bow lets go at a full draw (20 ticks); a shield takes 5 to come up
		if (archer && d > 4 && drawn < 12) {                                 // no arrow for a moment yet: straight at it, sprinting (the shield slows it)
			h.lowerShield();
			p.setSprinting(sprintable);
			return c.walkTo(foe.position());
		}
		if (archer && d > 4 && h.raiseShield()) {                            // the arrow's coming: the shield up in time, eyes on it, a step in
			h.watching = foe;
			return Action.FORWARD;
		}
		if (archer && d > 4 && drawn >= 15) {                                // no shield: a step sideways just as it lets go (it shoots where it was)
			h.watching = foe;
			return (now / 30) % 2 == 0 ? Action.LEFT : Action.RIGHT;
		}
		if (archer && d > 4 && c.knowledge.knows("skeleton_dodge")) {        // side to side on the way in, eyes on it: its arrows miss
			h.watching = foe;
			int phase = (now / 8) % 4;                                          // (a strafe, a step in, the other strafe, a step in)
			return phase == 0 ? Action.LEFT : phase == 2 ? Action.RIGHT : Action.FORWARD;
		}
		return c.walkTo(foe.position());                                       // go after it (sprinting: a sprint hit)
	}

	/** Back to Basics chose to fight this one: no backing off, no running (its old ways off). */
	private boolean noRun;
	/** Where its feet were when it began to dig in (the roof goes there), and when. */
	private net.minecraft.core.BlockPos hole;
	private int holeAt, quietSince, holeLostAt = -1000;

	/**
	 * Back to Basics in a fight: one call, and it sticks to it. A creeper close by: hit it (that knocks it back), then
	 * back out of its blast. A shooter it can't get to (far, across water, up a cliff): out of its sight, never after it.
	 * Armed, 12 health or more and one or two at it: it fights, no running. Else, if none is within six blocks (time to
	 * finish), it digs in: two down (out of a zombie's reach even with no roof yet), then a block over its head. With them
	 * already on it, it fights where it stands (shield up between swings): a hole started with a crowd round it is how
	 * it died, the roof never in time. Null: its usual fighting, with noRun saying whether it may back off.
	 */
	private Action btbPlan(LivingEntity foe, double d) {
		XenPlayer p = c.player;
		Hands h = c.hands;
		if (hole != null) {                                                     // digging in, or dug in: on with that
			Action in = digIn();
			if (in != null) return in;
		}
		if (foe instanceof net.minecraft.world.entity.monster.Creeper && d <= 4.5) {
			if (d <= p.entityInteractionRange() && p.getAttackStrengthScale(0.5f) >= 0.9f) {
				sTap = 8;
				return hit(foe);
			}
			h.watching = foe;
			p.setSprinting(false);
			c.goals.instant = "out of the creeper's blast";
			return ledgeBehind(p, foe) ? Action.LEFT : Action.BACK;
		}
		if (Companion.archer(foe) && !c.reachable(foe)) {
			Action cover = takeCover(foe);
			if (cover != null) {
				c.goals.instant = "out of the " + kind(foe).replace('_', ' ') + "'s sight (no way to get at it)";
				return cover;
			}
		}
		int on = c.mobsOnIt(12);
		if (h.hasWeapon() && p.getHealth() >= 12 && on <= 2) {
			noRun = true;
			return null;
		}
		LivingEntity shooter = shooterInSight();                                // one shooting at it: a hole is no cover from arrows (it was shot digging, every time)
		if (shooter != null) {
			if (h.hasWeapon() && p.getHealth() >= 6 && c.reachable(shooter)) {
				noRun = true;                                                    // (close in on it, shield up, side to side: its usual way with a skeleton)
				return null;
			}
			Action cover = takeCover(shooter);
			if (cover != null) {
				c.goals.instant = "out of the " + kind(shooter).replace('_', ' ') + "'s sight";
				return cover;
			}
			noRun = h.hasWeapon();                                               // (no weapon: its fists against arrows is how it died again and again; away, the old way)
			return null;
		}
		double nearest = Double.MAX_VALUE;
		for (net.minecraft.world.entity.Mob m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(8),
				m -> m.isAlive() && m.getTarget() == p)) nearest = Math.min(nearest, p.distanceTo(m));
		if (nearest > 6 && now() - holeLostAt > 200) {                          // (knocked out of a hole: not another for ten seconds, it fights)
			Action dig = startDigIn(on);
			if (dig != null) return dig;
		}
		noRun = h.hasWeapon();                                                   // (no time to dig, nowhere better: it stands and fights, shield up between swings; with no weapon it gets away first, then digs in)
		if (now() - standSaidAt > 400) {
			standSaidAt = now();
			c.journal("fight", String.format(java.util.Locale.ROOT, "%d at it, %.0f health%s: no time to dig in, it stands and fights", on, p.getHealth(), h.hasWeapon() ? "" : ", no weapon"));
		}
		return null;
	}

	private int standSaidAt = -1000;

	/** Backing away from it, a step behind: a drop of more than three blocks, or lava (it died backing off a ledge from a creeper). */
	private static boolean ledgeBehind(XenPlayer p, LivingEntity from) {
		double dx = p.getX() - from.getX(), dz = p.getZ() - from.getZ(), len = Math.max(0.01, Math.hypot(dx, dz));
		var level = p.level();
		for (double step : new double[] {0.8, 1.6}) {
			net.minecraft.core.BlockPos q = net.minecraft.core.BlockPos.containing(p.getX() + dx / len * step, p.getY(), p.getZ() + dz / len * step);
			boolean ground = false;
			for (int k = 0; k <= 3 && !ground; k++) {
				net.minecraft.core.BlockPos b = q.below(k + 1);
				if (level.getFluidState(b).is(net.minecraft.tags.FluidTags.LAVA)) return true;
				ground = !level.getBlockState(b).getCollisionShape(level, b).isEmpty() || !level.getFluidState(b).isEmpty();
			}
			if (!ground) return true;
		}
		return false;
	}

	/** Dug in and none in sight (the roof is on): it stays down there till they've gone, not back out into them. */
	Action stayDug() {
		return hole == null ? null : digIn();
	}

	private int now() {
		return c.player.tickCount;
	}

	/** Two blocks down right here, a floor under that, nothing that falls or flows: a hole to dig in. Null if not here. */
	private Action startDigIn(int on) {
		XenPlayer p = c.player;
		if (!p.onGround() || p.isInWater() || p.isInLava()) return null;
		var level = (net.minecraft.server.level.ServerLevel) p.level();
		net.minecraft.core.BlockPos feet = p.blockPosition();
		for (net.minecraft.core.BlockPos b : new net.minecraft.core.BlockPos[] {feet.below(), feet.below(2)}) {
			var st = level.getBlockState(b);
			if (st.isAir() || !level.getFluidState(b).isEmpty() || !Hands.natural(st) || st.getBlock() instanceof net.minecraft.world.level.block.FallingBlock
					|| c.hands.tooSlowToMine(b)) return null;
			for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				net.minecraft.core.BlockPos side = b.relative(dir);
				if (!level.getFluidState(side).isEmpty()) return null;            // (water or lava beside it: not here)
			}
		}
		net.minecraft.core.BlockPos floor = feet.below(3);
		if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty() || !level.getFluidState(floor).isEmpty()) return null;
		hole = feet.immutable();
		holeAt = now();
		quietSince = -1;
		c.journal("fight", String.format(java.util.Locale.ROOT, "%d at it, %.0f health%s: digs in (two down, a block over its head)", on, p.getHealth(),
				c.hands.hasWeapon() ? "" : ", no weapon"));
		return digIn();
	}

	/** Down the hole, then the roof, then it waits there till they've gone (kill aura or not, it hits what comes in reach). */
	private Action digIn() {
		XenPlayer p = c.player;
		var level = (net.minecraft.server.level.ServerLevel) p.level();
		net.minecraft.core.BlockPos feet = p.blockPosition();
		if (Math.abs(feet.getX() - hole.getX()) + Math.abs(feet.getZ() - hole.getZ()) > 1 || feet.getY() > hole.getY() || now() - holeAt > 20 * 120) {
			if (now() - holeAt <= 20 * 120) holeLostAt = now();
			hole = null;                                                         // (knocked out of it, or two minutes: done)
			return null;
		}
		if (feet.getY() > hole.getY() - 2) {
			net.minecraft.core.BlockPos under = feet.below();
			c.goals.instant = "digging in";
			p.setSprinting(false);
			if (c.hands.mine(under)) {
				c.acted = true;
				return Action.MINE;
			}
			if (under.equals(c.hands.cantMine)) {
				hole = null;
				return null;
			}
			return Action.LOOK_DOWN;
		}
		var roof = level.getBlockState(hole);
		if (roof.canBeReplaced() && c.hands.placeAt(hole, "any")) {             // (no block, or one in the way: it stays down anyway, out of their reach)
			c.acted = true;
			c.goals.instant = "a block over its head";
			return Action.PLACE;
		}
		c.goals.instant = "dug in till they've gone";
		if (c.mobsOnIt(12) > 0) quietSince = -1;
		else if (quietSince < 0) quietSince = now();
		if (quietSince >= 0 && now() - quietSince > 200) {                     // ten quiet seconds: out again (its task, or the night in there)
			hole = null;
			return null;
		}
		return Action.IDLE;
	}

	/**
	 * Not sure how this goes: more than one at it, hurt (under seven hearts), or dark round it. Then, like a player who
	 * can't see it all, it plays defense first: the shield up, they come to it, it hits back when they're in reach.
	 */
	private boolean unsure() {
		XenPlayer p = c.player;
		if (p == null) return false;
		return around >= 2 || p.getHealth() < 14 || p.level().getMaxLocalRawBrightness(p.blockPosition()) < 5;
	}

	/**
	 * How it likes to fight, from its nature (after a player's notes on their own play): each a reasonable way to live
	 * through a fight, none the right one. How much health it keeps in hand, when it heals, whether it starts fights.
	 */
	enum Style {
		TACTICIAN("a defensive tactician", 0.6f, 15), OPPORTUNIST("an aggressive opportunist", 0.4f, 11), CONTROLLER("a terrain controller", 0.5f, 13),
		EXPLORER("a cautious explorer", 0.55f, 14), TEAM("a team player", 0.5f, 13);
		final String words;
		final float keep, healAt;

		Style(String words, float keep, float healAt) {
			this.words = words;
			this.keep = keep;
			this.healAt = healAt;
		}
	}

	private Style style;

	Style style() {
		if (style == null) {
			var who = c.personality;
			float[] score = new float[Style.values().length];
			score[Style.TACTICIAN.ordinal()] = 0.6f * (1 - who.bravery) + 0.4f * gene(9) + (who.believes("live_to_fight") ? 0.3f : 0);
			score[Style.OPPORTUNIST.ordinal()] = 0.5f * who.bravery + 0.4f * who.sin(xen.mod.core.Sins.PRIDE) + 0.4f * who.sin(xen.mod.core.Sins.WRATH) + 0.2f * gene(0);
			score[Style.CONTROLLER.ordinal()] = 0.5f * c.skills.get(Skills.BUILD) + 0.3f * who.diligence + 0.2f * who.curiosity;
			score[Style.EXPLORER.ordinal()] = 0.6f * who.curiosity + 0.4f * (1 - who.bravery) - 0.2f * who.sin(xen.mod.core.Sins.WRATH);
			score[Style.TEAM.ordinal()] = 0.5f * who.kindness + 0.5f * who.loyalty - (who.loner ? 0.6f : 0);
			int best = 0;
			for (int i = 1; i < score.length; i++) if (score[i] > score[best]) best = i;
			style = Style.values()[best];
			c.journal("is", "fights like " + style.words);
		}
		return style;
	}

	/** The health it keeps in hand against monsters (a share of all of it): its style, and its gear shifts it (no shield and no armor: more). */
	private float floor() {
		var p = c.player;
		float f = style().keep;
		if (!c.hands.carries("shield") && p.getArmorValue() < 6) f += 0.05f;
		else if (p.getArmorValue() >= 15) f -= 0.05f;
		return Math.max(0.35f, Math.min(0.65f, f));
	}

	/** About what its next hit does (its weapon, how charged the swing is). */
	private float hitNow() {
		var p = c.player;
		float charge = p.getAttackStrengthScale(0.5f);
		return (float) p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * (0.2f + 0.8f * charge * charge);
	}

	private int saidWhyAt = -1000;

	/** When it last ate a golden apple in a fight (one at a time: they're precious). */
	private long gappleAt = -10000;

	/** How many monsters were round it (within 6) at the last look. */
	private int around;

	/**
	 * Surrounded: which one hits it first? A skeleton at a full draw with a clear shot, a zombie right on it, before the
	 * one it's fighting, if those are coming sooner (a player in a crowd guards the side the hit comes from).
	 */
	private LivingEntity mostUrgent(LivingEntity foe) {
		XenPlayer p = c.player;
		LivingEntity best = foe;
		double bestT = soonest(foe);
		around = 0;
		for (net.minecraft.world.entity.Mob m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(10),
				x -> x.isAlive() && x != foe && x.getTarget() == p)) {
			if (m.distanceTo(p) < 6) around++;
			double t = soonest(m);
			if (t < bestT - 4) {
				bestT = t;
				best = m;
			}
		}
		return best;
	}

	/** A weapon's hit at full charge (1.9+ combat): an axe hits harder, a sword swings faster. 1 for a bare hand. */
	static float weaponHit(String id) {
		boolean axe = id.endsWith("_axe");
		if (!axe && !id.endsWith("_sword")) return 1;
		return switch (id.substring(0, id.lastIndexOf('_'))) {
			case "stone" -> axe ? 9 : 5;
			case "iron" -> axe ? 9 : 6;
			case "diamond" -> axe ? 9 : 7;
			case "netherite" -> axe ? 10 : 8;
			default -> axe ? 7 : 4;                                            // (wood, gold)
		};
	}

	/** Its hardest-hitting weapon of a kind on its hotbar ("_axe", "_sword"); 0 if none. */
	private float best(String kind) {
		var inv = c.player.getInventory();
		float best = 0;
		for (int i = 0; i < 9; i++) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (id.endsWith(kind)) best = Math.max(best, weaponHit(id));
		}
		return best;
	}

	/** Critical hits (half again a full hit) that kill it from where its health is now. */
	private static int critsToKill(LivingEntity foe, float hit) {
		return hit <= 0 ? 99 : (int) Math.ceil((foe.getHealth() + foe.getAbsorptionAmount()) / (hit * 1.5f));
	}

	/** Its axe kills this one in fewer crits than its sword (a skeleton: a stone axe in two, a stone or iron sword in three)? */
	private boolean axeKillsSooner(LivingEntity foe) {
		float axe = best("_axe"), sword = best("_sword");
		return axe > 0 && sword > 0 && critsToKill(foe, axe) < critsToKill(foe, sword);
	}

	/** When it last noted why the axe (once a minute at most). */
	private long axeSaidAt = -10000;

	/** When it last noted rushing a skeleton; getting away from a piglin brute. */
	private long rushSaidAt = -10000, bruteSaidAt = -10000;

	private boolean archer(LivingEntity e) {
		String k = kind(e);
		return k.endsWith("skeleton") && !k.equals("wither_skeleton") || k.equals("stray") || k.equals("bogged");
	}

	/** Ticks till this one could hurt it (about). */
	private double soonest(LivingEntity e) {
		XenPlayer p = c.player;
		double d = e.distanceTo(p);
		if (archer(e))
			return p.hasLineOfSight(e) ? (e.isUsingItem() ? Math.max(0, 20 - e.getTicksUsingItem()) : 25) + d / 3 : 999;   // (an arrow flies about 3 blocks a tick)
		if (e instanceof net.minecraft.world.entity.monster.Creeper cr) return cr.getSwellDir() > 0 ? 0 : d * 5;
		return Math.max(0, d - 2.5) * 5;                                    // (on foot: about a block every 5 ticks)
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

	private long screenSaidAt = -100_000;

	/**
	 * A skeleton shooting at it and a creeper coming (not hissing yet): it stands so the creeper is between it and the
	 * skeleton, the way players do, and the arrows meant for it go into the creeper. Null: not that.
	 */
	private Action creeperScreen() {
		XenPlayer p = c.player;
		if (c.inArena || p.getHealth() < 10) return null;
		LivingEntity shooter = shooterInSight();
		if (shooter == null || !Companion.archer(shooter)) return null;
		net.minecraft.world.entity.monster.Creeper cr = null;
		double best = 10;
		for (var x : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class, p.getBoundingBox().inflate(10),
				x -> x.isAlive() && x.getSwellDir() <= 0 && !x.isIgnited())) {
			double dd = x.distanceTo(p);
			if (dd > 3.5 && dd < best) {
				best = dd;
				cr = x;
			}
		}
		if (cr == null) return null;
		Vec3 away = cr.position().subtract(shooter.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1) return null;
		Vec3 spot = cr.position().add(away.normalize().scale(4.5));
		long now = p.level().getGameTime();
		if (now - screenSaidAt > 600) {
			screenSaidAt = now;
			c.journal("fight", "a skeleton and a creeper: keeps the creeper between it and the arrows");
		}
		c.goals.instant = "keeping the creeper between it and the skeleton";
		if (p.position().distanceTo(spot) < 1.5) {
			c.hands.watching = cr;
			return idle();
		}
		return c.walkTo(spot);
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

	/** Till when it keeps going for its cover with the shooter out of sight (no flip-flopping between cover and away). */
	private int coverUntil;

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
		Action wall = wallOff(shooter, what);                                  // a wall of blocks between them, two high: no fight at all
		if (wall != null) return wall;
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

	/** Blocks it put up against shooters lately, and when. */
	private int wallBlocks, wallAt = -10000;

	/**
	 * A shooter on it: two blocks (feet and head) between them, on the side it's shooting from, the way a player walls
	 * a skeleton off rather than fight it; more as it walks round (up to eight a fight). Null: no blocks, too close
	 * (that one's a fight), or nowhere to put them.
	 */
	private Action wallOff(LivingEntity shooter, String what) {
		return wallOff(shooter, what, 3.5);
	}

	private Action wallOff(LivingEntity shooter, String what, double nearest) {
		XenPlayer p = c.player;
		int now = p.tickCount;
		if (now - wallAt > 600) wallBlocks = 0;
		if (wallBlocks >= 8 || now - wallAt < 5 || shooter.distanceTo(p) < nearest) return null;
		var level = p.level();
		Vec3 to = shooter.position().subtract(p.position());
		net.minecraft.core.Direction d = Math.abs(to.x) > Math.abs(to.z) ? (to.x > 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
				: (to.z > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
		var feet = p.blockPosition();
		var w = feet.relative(d);
		for (var b : new net.minecraft.core.BlockPos[] {w, w.above()}) {
			if (!level.getBlockState(b).canBeReplaced()) continue;
			for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
				var n = b.relative(side);
				if (n.equals(feet) || n.equals(feet.above()) || level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
				if (c.hands.placeItem(b, st -> Hands.PLACEABLE.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()), n, side.getOpposite(), -1)) {
					wallAt = now;
					if (wallBlocks++ == 0) c.journal("fight", "the " + what + " is shooting at it: walls itself off with blocks instead of fighting it");
					c.goals.instant = "walling itself off from the " + what;
					c.acted = true;
					return Action.IDLE;
				}
			}
			if (now - wallFailSaidAt > 400) {
				wallFailSaidAt = now;
				c.journal("fight", "can't wall off the " + what + ": " + c.hands.cantPlace);
			}
			return null;                                                         // (nothing to put it against: cover then)
		}
		return null;
	}

	private int wallFailSaidAt = -10000, threatSaidAt = -10000, fledAt = -10000, recoverSaidAt = -10000;

	/**
	 * What all the monsters about it (within 12) would cost it together: those after it in full, the others less, the
	 * far ones less again; more in the dark, more with little armor. Its threat before anything has hit it.
	 */
	private float threatAround() {
		XenPlayer p = c.player;
		float t = 0;
		for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(12),
				x -> x.isAlive() && x instanceof net.minecraft.world.entity.monster.Enemy && !kind(x).equals("enderman"))) {
			float e = expected(m) * (m.getTarget() == p ? 1f : m.hasLineOfSight(p) ? 0.5f : 0.2f) * (m.distanceTo(p) < 6 ? 1f : 0.6f);
			t += e;
		}
		if (p.level().isDarkOutside() && p.level().canSeeSky(p.blockPosition().above())) t *= 1.25f;
		return t * (1.3f - 0.5f * Math.min(1f, p.getArmorValue() / 20f));
	}

	/** Safe again: nothing after it within 12 that can see it, and over half its health. */
	private boolean safe() {
		XenPlayer p = c.player;
		return p.getHealth() >= 12 && p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(12),
				m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && m.hasLineOfSight(p) && (m.getTarget() == p || m.distanceTo(p) < 8)).isEmpty();
	}

	/**
	 * Got away (nothing on it now): it heals before it goes back to anything, a bite and a moment to see what's about,
	 * up to half a minute, the way a player gets their health back before the next thing. Null: healed, or long enough.
	 */
	Action recover() {
		XenPlayer p = c.player;
		int now = p.tickCount;
		if (now - fledAt > 600 || p.getHealth() >= 16 || c.inArena) return null;
		if (now - recoverSaidAt > 600) {
			recoverSaidAt = now;
			c.journal("fight", String.format(java.util.Locale.ROOT, "got away (%.0f health): heals and looks about before going on", p.getHealth()));
		}
		c.goals.instant = "getting its health back";
		c.hands.watching = null;
		if (c.hands.canHeal() && p.getFoodData().getFoodLevel() < 20) return Action.EAT;
		if (p.getFoodData().getFoodLevel() < 18) return null;                 // (no food to heal on: on with things, carefully)
		return idle();
	}

	/** When it last shouted for help. */
	private int calledAt = -10000;

	/**
	 * To a friend close by (a Xen it trusts, or of its team), not past the foe; it shouts for help on the way, and each
	 * one that hears decides for itself whether to come. Null: nobody to run to (or it's there).
	 */
	private Action toFriend(LivingEntity foe, double d, boolean sprintable) {
		XenPlayer p = c.player;
		int now = p.tickCount;
		Companion best = null;
		double bestD = 40;
		Vec3 toFoe = foe.position().subtract(p.position()).multiply(1, 0, 1);
		for (Companion o : c.mod.companions) {
			if (o == c || o.player == null || !o.player.isAlive() || o.player.level() != p.level() || o.player == foe || o.inArena) continue;
			double od = o.player.distanceTo(p);
			if (od >= bestD || o.trust(p.getUUID()) < 0.3f && !p.isAlliedTo(o.player) || c.trust(o.player.getUUID()) < 0.2f) continue;
			if (o.player.getHealth() < 10 || !o.hands.hasWeapon() || o.fightingNow() && o.player.hurtTime > 0) continue;   // (in trouble too, or nothing to fight with: running to it is running into two fights)
			Vec3 to = o.player.position().subtract(p.position()).multiply(1, 0, 1);
			if (to.lengthSqr() > 1e-4 && toFoe.lengthSqr() > 1e-4 && to.normalize().dot(toFoe.normalize()) > 0.4 && od > d) continue;   // (not through the foe)
			bestD = od;
			best = o;
		}
		if (best == null) return null;
		if (now - calledAt > 200) {                                          // "Help!": those near enough to hear decide whether to come
			calledAt = now;
			String what = foe instanceof Player ? foe.getName().getString() : kind(foe).replace('_', ' ');
			c.say(c.pick3("Help! " + (foe instanceof Player ? what : "A " + what) + "'s on me!", "HELP! I'm nearly dead!", best.name + ", help me!"));
			c.journal("thinks", String.format(java.util.Locale.ROOT, "nearly dead, nothing to fight with: runs to %s (%.0f blocks), shouting for help", best.name, bestD));
			for (Companion o : c.mod.companions) if (o != c && o.player != null && o.player.level() == p.level() && o.player.distanceTo(p) < 40) o.heardCall(c, foe);
		}
		if (bestD < 3) return null;                                          // there: it fights on beside them, or runs on
		c.goals.instant = "running to " + best.name + " for help";
		Vec3 to = best.player.position().subtract(p.position()).multiply(1, 0, 1);
		if (clearRun(to.normalize())) return runAlong(to.normalize(), sprintable);
		return c.walkTo(best.player.position());
	}

	/** The way it runs, turned 30 degrees one way, then the other, every third of a second: a zig-zag, like a player dodging arrows. */
	private Vec3 weave(Vec3 dir) {
		double a = (c.player.tickCount / 7 % 2 == 0 ? 1 : -1) * 0.52, cos = Math.cos(a), sin = Math.sin(a);
		return new Vec3(dir.x * cos - dir.z * sin, dir.y, dir.x * sin + dir.z * cos);
	}

	/** Turn its back on it and sprint away (jumping up a step if one is in the way): no pathfinding, no time lost. */
	private Action runFrom(LivingEntity foe, boolean sprintable) {
		Vec3 away = c.player.position().subtract(foe.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
		away = away.normalize();
		return runAlong(shoots(kind(foe)) ? weave(away) : away, sprintable);   // (from one that shoots: zig-zag)
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

	static final boolean FIGHT_LOG = Boolean.getBoolean("xen.fightLog");

	private Action hit(LivingEntity foe) {
		combo = 0;
		if (Companion.DEBUG) {
			var p = c.player;
			XenMod.LOG.info("[xen fight] {} hits: charge {} falling {} sprinting {} fall {} ground {} foe hp {}", c.name,
					String.format("%.2f", p.getAttackStrengthScale(0.5f)), p.getDeltaMovement().y < 0, p.isSprinting(),
					String.format("%.2f", p.fallDistance), p.onGround(), String.format("%.1f", foe.getHealth()));
		}
		if (FIGHT_LOG && foe instanceof Player) {                              // (-Dxen.fightLog: each swing at a player, for tests: how far, a crit or not)
			var p = c.player;
			boolean crit = p.getAttackStrengthScale(0.5f) > 0.9f && p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater() && !p.isSprinting();
			XenMod.LOG.info("[xen pvp] {} swings at {}: d {} charge {} {}{}", c.name, foe.getName().getString(), String.format(java.util.Locale.ROOT, "%.2f", p.distanceTo(foe)),
					String.format(java.util.Locale.ROOT, "%.2f", p.getAttackStrengthScale(0.5f)), crit ? "crit" : "plain", p.isSprinting() ? " sprint" : "");
		}
		c.hands.hit(foe);
		c.skills.practice(Skills.FIGHT, 0.004f);
		c.acted = true;
		return Action.ATTACK;
	}

	/** A spot close by with a roof two blocks up (an enderman, three tall, can't get in): it goes there and fights from it. Null: none. */
	private net.minecraft.core.BlockPos lowRoof;
	private int lowRoofAt = -1000;

	private Action underLowRoof(LivingEntity foe, double d) {
		XenPlayer p = c.player;
		var level = p.level();
		int now = p.tickCount;
		if (lowRoof == null || now - lowRoofAt > 100) {
			lowRoofAt = now;
			lowRoof = null;
			net.minecraft.core.BlockPos feet = p.blockPosition();
			double best = Double.MAX_VALUE;
			for (int dx = -8; dx <= 8; dx++)
				for (int dz = -8; dz <= 8; dz++)
					for (int dy = -2; dy <= 2; dy++) {
						var q = feet.offset(dx, dy, dz);
						if (!roofed(level, q)) continue;
						int covered = 0;
						for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
							var n = q.relative(dir);
							if (roofed(level, n) || !free(level, n)) covered++;
						}
						if (covered < 3) continue;                                   // (deep enough in: not right at the edge)
						double score = q.distSqr(feet);
						if (score < best) {
							best = score;
							lowRoof = q.immutable();
						}
					}
		}
		if (lowRoof == null) return null;
		Hands h = c.hands;
		if (!p.blockPosition().equals(lowRoof)) {
			c.goals.instant = "under a low roof, away from the " + kind(foe).replace('_', ' ');
			return c.walkTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(lowRoof));
		}
		c.goals.instant = "under a low roof: the " + kind(foe).replace('_', ' ') + " can't get in";
		h.watching = foe;
		h.ready(false, d);
		if (d <= p.entityInteractionRange() && p.getAttackStrengthScale(0.5f) >= 0.9f) return hit(foe);
		return idle();
	}

	/** Room to stand, and a solid block right over its head: two blocks of room, no more. */
	private static boolean roofed(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos q) {
		return free(level, q) && free(level, q.above()) && !free(level, q.above(2))
				&& !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty() && level.getFluidState(q).isEmpty();
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

	private int knockSaidAt = -1000;

	/**
	 * A bite between swings, like a player: the food bar kept full in a fight, so its health keeps coming back. Only
	 * while nothing's on top of it (none within 3.5 blocks: eating slows it and takes a second and a half). Null: not now.
	 */
	private Action snack(LivingEntity foe) {
		var p = c.player;
		if (p.getFoodData().getFoodLevel() >= 20 || !c.hands.canHeal()) return null;
		if (clearAround(3.5)) return Action.EAT;
		if (p.getHealth() >= 12 || p.getFoodData().getFoodLevel() <= 6) return null;   // (fine for now, or too hungry to sprint)
		Vec3 dir = escapeDir(foe);                                             // hurt and hungry with them close: a sprint opens a gap (they're slower), then a bite
		c.goals.instant = "running off to eat";
		p.setSprinting(true);
		if (clearRun(dir)) return runAlong(dir, true);
		return c.walkTo(p.position().add(dir.scale(8)));
	}

	/** No monster within r blocks of it. */
	private boolean clearAround(double r) {
		var p = c.player;
		return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
				m -> m != p && m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).isEmpty();
	}

	/** Other monsters close enough to the one it's hitting for a sword's sweep to catch them too (a block round it, in reach). */
	private int bunchedBy(LivingEntity foe) {
		var p = c.player;
		return p.level().getEntitiesOfClass(LivingEntity.class, foe.getBoundingBox().inflate(1.0, 0.25, 1.0),
				m -> m != foe && m != p && m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && p.distanceToSqr(m) < 9).size();
	}

	/** Lava or a drop of four or more just behind it (as seen from here): a knock back sends it there. */
	private boolean dropBehind(LivingEntity foe) {
		var level = c.player.level();
		Vec3 dir = foe.position().subtract(c.player.position()).multiply(1, 0, 1);
		if (dir.lengthSqr() < 1e-4) return false;
		dir = dir.normalize();
		for (double k : new double[] {1.2, 2.2}) {
			var q = net.minecraft.core.BlockPos.containing(foe.position().add(dir.scale(k)));
			if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA) || level.getFluidState(q.below()).is(net.minecraft.tags.FluidTags.LAVA)) return true;
			int air = 0;
			while (air < 5 && level.getBlockState(q.below(air)).getCollisionShape(level, q.below(air)).isEmpty() && level.getFluidState(q.below(air)).isEmpty()) air++;
			if (air >= 4) return true;
		}
		return false;
	}

	/** The start of a swing: decide how to play this one. */
	private void newSwing() {
		goCrit = random.nextFloat() < gene(0);
		useShield = random.nextFloat() < gene(9) || unsure();                   // (not sure what's coming: defense first, the shield up between hits)
		selecting = random.nextFloat() < gene(7);
		waited = 0;
	}
}
