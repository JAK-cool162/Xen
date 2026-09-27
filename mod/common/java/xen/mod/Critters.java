package xen.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Random;

/**
 * Living with the mobs, the way players do.
 * <ul>
 *   <li><b>Arrows</b>: a skeleton (or pillager) drawing its bow at it: shield up, facing it (no shield: a side step,
 *   then in close while it reloads).</li>
 *   <li><b>Drowned</b>: in the water with drowned about: out onto dry land first.</li>
 *   <li><b>Poison</b>: poisoned (a cave spider, a witch) with a bucket and a cow near: milk, and drink it.</li>
 *   <li><b>Animals</b>: near its home with their food (wheat for cows and sheep, seeds for chickens, carrots for
 *   pigs), two grown ones that can breed and not too many already: it feeds them, and there's food for later.</li>
 *   <li><b>Wool</b>: with shears, it shears sheep instead of killing them.</li>
 *   <li><b>Cats</b>: raw fish and a stray cat about: it tames it (cats keep creepers away).</li>
 * </ul>
 */
final class Critters {
	private final Companion c;
	private final Random random = new Random();
	private long nextChore, sideUntil;
	private int side = 1;
	/** How often it did each (for the journal and the tests). */
	int blocked, bred, sheared, milked, cats;

	Critters(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private static String id(LivingEntity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	// ------------------------------------------------------------------------------ danger (comes first)
	/** An arrow about to come, drowned in the water, poison: what it does about it this moment, or null. */
	Action danger() {
		var p = c.player;
		if (p == null || c.inArena) return null;
		// a bow drawn at it
		for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16), x -> x.isAlive() && x instanceof RangedAttackMob
				&& x.getTarget() == p && x.isUsingItem())) {
			if (!p.hasLineOfSight(m)) continue;
			c.goals.instant = "blocking the " + id(m).replace('_', ' ') + "'s arrows";
			c.hands.face(m.getEyePosition());
			if (c.hands.raiseShield()) {
				if (blocked++ % 10 == 0) c.journal("does", "shield up against a " + id(m));
				c.acted = true;
				return Action.IDLE;
			}
			if (now() > sideUntil) {                                             // no shield: a side step, like dodging
				side = random.nextBoolean() ? 1 : -1;
				sideUntil = now() + 15;
			}
			c.acted = true;
			return side > 0 ? Action.LEFT : Action.RIGHT;
		}
		// drowned about while it swims
		if (p.isInWater() && !p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(12), x -> x.isAlive() && id(x).equals("drowned")).isEmpty()) {
			Vec3 shore = c.walker.nearestDryLand(12);
			if (shore != null) {
				c.goals.instant = "getting out of the water (drowned)";
				c.run(true);
				return c.walkTo(shore);
			}
		}
		// poisoned: milk cures it
		if (p.hasEffect(MobEffects.POISON) && p.getHealth() < 14) {
			Action milk = milk();
			if (milk != null) return milk;
		}
		return null;
	}

	private Action milk() {
		var p = c.player;
		int milk = c.hands.hotbar(s -> s.getItem() == Items.MILK_BUCKET);
		if (milk >= 0) {
			p.getInventory().setSelectedSlot(milk);
			c.goals.instant = "drinking milk";
			if (!p.isUsingItem()) p.gameMode.useItem(p, p.level(), p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
			c.acted = true;
			return Action.IDLE;
		}
		int bucket = c.hands.hotbar(s -> s.getItem() == Items.BUCKET);
		if (bucket < 0) return null;
		Animal cow = nearest(Animal.class, 12, a -> (id(a).equals("cow") || id(a).equals("mooshroom")) && !a.isBaby());
		if (cow == null) return null;
		c.goals.instant = "milking a cow (poisoned)";
		if (p.distanceTo(cow) > 2.5) return c.walkTo(cow.position());
		return use(bucket, cow, () -> {
			milked++;
			c.chatter("Milk fixes poison.", false);
		});
	}

	// ------------------------------------------------------------------------------ animals (when it's free)
	/** Something to do with the animals around, now and then (breeding by its home, shearing, a cat): null if nothing. */
	Action chores() {
		var p = c.player;
		if (p == null || c.inArena || c.fightingNow() || p.level().isDarkOutside() || now() < nextChore) return null;
		Action a = shear();
		if (a == null) a = breed();
		if (a == null) a = tameCat();
		if (a == null) nextChore = now() + 200;                                   // (nothing: look again in 10 seconds)
		return a;
	}

	private Action shear() {
		if (MindSense.count(c, n -> n.equals("shears")) == 0) return null;
		int shears = c.hands.hotbar(s -> s.getItem() == Items.SHEARS);
		if (shears < 0) return null;
		int wool = 0;
		for (var e : c.items().entrySet()) if (e.getKey().endsWith("_wool")) wool += e.getValue();
		if (wool >= 16) return null;
		Animal sheep = nearest(Animal.class, 12, a -> a instanceof Shearable s && s.readyForShearing() && id(a).equals("sheep"));
		if (sheep == null) return null;
		c.goals.instant = "shearing a sheep";
		if (c.player.distanceTo(sheep) > 2.5) return c.walkTo(sheep.position());
		return use(shears, sheep, () -> sheared++);
	}

	/** By its home: two grown animals of a kind that can breed, and their food in its bag; at most a dozen of that kind there. */
	private Action breed() {
		var p = c.player;
		if (c.goals.home == null || !p.blockPosition().closerThan(c.goals.home, 32)) return null;
		for (String kind : new String[] {"cow", "sheep", "pig", "chicken"}) {
			if (MindSense.count(c, n -> feeds(kind, n)) == 0) continue;          // (a look in its bag first: nothing moved about)
			var all = p.level().getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(16), a -> a.isAlive() && id(a).equals(kind));
			if (all.size() >= 12) continue;
			Animal one = null;
			int ready = 0;
			for (Animal a : all) {
				if (a.isBaby() || a.getAge() != 0) continue;
				if (a.isInLove()) {
					ready++;
					continue;
				}
				ready++;
				if (one == null || a.distanceTo(p) < one.distanceTo(p)) one = a;
			}
			if (one == null || ready < 2) continue;
			Animal target = one;
			c.goals.instant = "feeding the " + kind + "s";
			if (p.distanceTo(target) > 2.5) return c.walkTo(target.position());
			int food = c.hands.hotbar(s -> feeds(kind, BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
			if (food < 0) return null;
			return use(food, target, () -> {
				if (target.isInLove() && ++bred % 2 == 0) {
					c.journal("does", "bred " + kind + "s by its home");
					c.chatter(c.pick3("More " + kind + "s on the way.", "Food for later: baby " + kind + "s.", "There, the " + kind + "s can have babies."), false);
				}
			});
		}
		return null;
	}

	private static boolean feeds(String kind, String n) {
		return switch (kind) {
			case "cow", "sheep" -> n.equals("wheat");
			case "pig" -> n.equals("carrot") || n.equals("potato") || n.equals("beetroot");
			default -> n.endsWith("_seeds");
		};
	}

	private Action tameCat() {
		if (MindSense.count(c, n -> n.equals("cod") || n.equals("salmon")) == 0) return null;
		int fish = c.hands.hotbar(s -> s.getItem() == Items.COD || s.getItem() == Items.SALMON);
		if (fish < 0) return null;
		Animal cat = nearest(Animal.class, 12, a -> id(a).equals("cat") && a instanceof TamableAnimal t && !t.isTame());
		if (cat == null) return null;
		c.goals.instant = "making friends with a cat";
		if (c.player.distanceTo(cat) > 2.5) return c.walkTo(cat.position());
		return use(fish, cat, () -> {
			if (cat instanceof TamableAnimal t && t.isTame()) {
				cats++;
				c.say(c.pick3("A cat! Creepers hate cats.", "You're my cat now.", "Here, kitty. Good kitty."));
				c.journal("does", "tamed a cat");
			}
		});
	}

	// ------------------------------------------------------------------------------ helpers
	private <T extends LivingEntity> T nearest(Class<T> type, double r, java.util.function.Predicate<T> which) {
		var p = c.player;
		T best = null;
		for (T e : p.level().getEntitiesOfClass(type, p.getBoundingBox().inflate(r), x -> x.isAlive() && which.test(x))) {
			if (!p.hasLineOfSight(e)) continue;
			if (best == null || e.distanceTo(p) < best.distanceTo(p)) best = e;
		}
		return best;
	}

	/** Right-click it with what's in that slot (like a player), then see what came of it. */
	private Action use(int slot, LivingEntity e, Runnable after) {
		var p = c.player;
		p.getInventory().setSelectedSlot(slot);
		c.hands.face(e.getEyePosition());
		Compat.interact(p, e);
		Compat.swing(p);
		c.mod.later(2, after);
		nextChore = now() + 20;
		c.acted = true;
		return Action.IDLE;
	}
}
