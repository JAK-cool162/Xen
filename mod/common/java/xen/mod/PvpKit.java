package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * The fighting gear good PvP players carry, used like they use it, and only by a Xen skilled enough to (its fighting
 * skill): <b>potions</b> (healing or regeneration when it's hurt, splash ones thrown at its feet; strength and speed
 * before the fight; fire resistance when it burns), <b>cobwebs</b> (put down at the foe's feet so it's stuck while
 * it gets hit, or behind itself when it runs), <b>ender pearls</b> (thrown far away to get out of a losing fight, or
 * after a foe who runs), <b>golden apples</b> mid-fight, and a <b>shield</b> (in {@link Fighter}). All with a
 * player's inputs: it picks the item on its hotbar, looks, and clicks.
 */
final class PvpKit {
	private final Companion c;
	private long nextWeb, nextPearl, nextPotion, nextApple;
	private boolean drinking;

	PvpKit(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private float skill() {
		return c.skills.get(Skills.FIGHT);
	}

	/** Its move with its gear against this foe, or null (fight on as usual). */
	Action use(LivingEntity foe, double d, boolean fleeing) {
		var p = c.player;
		if (c.inArena) return null;
		if (drinking) {                                                        // (a potion takes a moment to drink)
			if (p.isUsingItem()) {
				c.hands.watching = foe;
				c.acted = true;
				return d < 3 ? Action.BACK : Action.IDLE;
			}
			drinking = false;
		}
		float s = skill(), health = p.getHealth() / p.getMaxHealth();
		if (s >= 0.4f && now() >= nextPotion) {
			Action a = null;
			if (health < 0.45f) a = potion(foe, "instant_health", "regeneration");
			if (a == null && p.isOnFire() && !has("fire_resistance")) a = potion(foe, "fire_resistance");
			if (a == null && d > 5 && d < 16 && s >= 0.55f && !has("strength")) a = potion(foe, "strength");
			if (a == null && d > 5 && d < 16 && s >= 0.6f && !has("speed")) a = potion(foe, "speed");
			if (a != null) return a;
		}
		if (s >= 0.45f && health < 0.6f && d > 3 && now() >= nextApple && p.getAbsorptionAmount() <= 0 && c.hands.canHeal()) {
			nextApple = now() + 60;                                            // a golden apple mid-fight, like a good player
			XenMod.LOG.info("{} eats a golden apple mid-fight", c.name);
			return Action.EAT;
		}
		if (s >= 0.5f && now() >= nextPearl && fleeing && health < 0.35f && d < 6 && c.hands.hotbar(st -> st.getItem() == Items.ENDER_PEARL) >= 0) {
			Vec3 away = p.position().subtract(foe.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
			nextPearl = now() + 60;
			c.chatter(c.pick3("Later!", "Nope, I'm out.", "Bye bye!"), false);
			return pearl(p.position().add(away.normalize().scale(22)).add(0, 1, 0));   // out of there
		}
		if (s >= 0.65f && now() >= nextPearl && !fleeing && d > 14 && d < 40 && movingAway(foe) && c.hands.hotbar(st -> st.getItem() == Items.ENDER_PEARL) >= 0) {
			nextPearl = now() + 80;
			return pearl(foe.position().add(foe.getDeltaMovement().multiply(10, 0, 10)));   // after it
		}
		if (s >= 0.4f && now() >= nextWeb && c.hands.hotbar(st -> st.getItem() == Items.COBWEB) >= 0) {
			BlockPos at = foe.blockPosition();
			if (!fleeing && d <= 3.6 && foe.onGround() && !inWeb(foe) && c.player.level().getBlockState(at).canBeReplaced()
					&& !c.player.level().getBlockState(at.below()).canBeReplaced()) {
				nextWeb = now() + 100;
				if (c.hands.placeItem(at, st -> st.getItem() == Items.COBWEB, at.below(), Direction.UP, -1)) {
					XenMod.LOG.info("{} puts a cobweb on {}", c.name, foe.getName().getString());
					c.acted = true;
					return Action.PLACE;                                       // stuck: now it gets hit
				}
			}
			if (fleeing && d < 5 && p.onGround()) {                            // one behind it as it runs: the chaser gets stuck
				Vec3 back = foe.position().subtract(p.position()).multiply(1, 0, 1);
				if (back.lengthSqr() > 1e-4) {
					BlockPos behind = BlockPos.containing(p.position().add(back.normalize().scale(1.5)));
					if (c.player.level().getBlockState(behind).canBeReplaced() && !c.player.level().getBlockState(behind.below()).canBeReplaced()) {
						nextWeb = now() + 100;
						if (c.hands.placeItem(behind, st -> st.getItem() == Items.COBWEB, behind.below(), Direction.UP, -1)) {
							c.acted = true;
							return Action.PLACE;
						}
					}
				}
			}
		}
		return null;
	}

	private boolean movingAway(LivingEntity foe) {
		Vec3 v = foe.getDeltaMovement().multiply(1, 0, 1);
		return v.lengthSqr() > 0.01 && v.dot(foe.position().subtract(c.player.position())) > 0;
	}

	private boolean inWeb(LivingEntity e) {
		return c.player.level().getBlockState(e.blockPosition()).is(net.minecraft.world.level.block.Blocks.COBWEB);
	}

	/** Is this effect on it now? */
	private boolean has(String effect) {
		for (var e : c.player.getActiveEffects()) if (BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).getPath().equals(effect)) return true;
		return false;
	}

	/** A potion of one of these effects from its hotbar or bag: drunk, or (a splash one) thrown at its feet. */
	private Action potion(LivingEntity foe, String... effects) {
		var p = c.player;
		var inv = p.getInventory();
		int slot = -1;
		for (int i = 0; i < 36 && slot < 0; i++) if (potionOf(inv.getItem(i), effects)) slot = i;
		if (slot < 0) return null;
		if (slot >= 9) {                                                        // from the bag to the hotbar first
			ItemStack held = inv.getItem(6);
			inv.setItem(6, inv.getItem(slot));
			inv.setItem(slot, held);
			slot = 6;
		}
		nextPotion = now() + 40;
		c.hands.stop();
		inv.setSelectedSlot(slot);
		ItemStack st = inv.getSelectedItem();
		boolean splash = st.getItem() == Items.SPLASH_POTION || st.getItem() == Items.LINGERING_POTION;
		if (splash) p.setXRot(90f);                                            // at its own feet
		p.gameMode.useItem(p, p.level(), st, InteractionHand.MAIN_HAND);
		Compat.swing(p);
		drinking = !splash;
		c.goals.instant = splash ? "throwing a potion" : "drinking a potion";
		c.journal("does", (splash ? "throws a potion of " : "drinks a potion of ") + String.join("/", effects));
		XenMod.LOG.info("{} {} a potion ({})", c.name, splash ? "throws" : "drinks", String.join("/", effects));
		c.acted = true;
		return Action.IDLE;
	}

	private static boolean potionOf(ItemStack s, String... effects) {
		if (s.isEmpty() || !(s.getItem() == Items.POTION || s.getItem() == Items.SPLASH_POTION)) return false;
		var contents = s.get(DataComponents.POTION_CONTENTS);
		if (contents == null) return false;
		for (var e : contents.getAllEffects()) {
			String id = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).getPath();
			for (String want : effects) if (id.equals(want)) return true;
		}
		return false;
	}

	/** Throw an ender pearl to land at that spot (aimed a little high: a pearl drops on its way). */
	private Action pearl(Vec3 at) {
		var p = c.player;
		int slot = c.hands.hotbar(st -> st.getItem() == Items.ENDER_PEARL);
		if (slot < 0) return null;
		c.hands.stop();
		p.getInventory().setSelectedSlot(slot);
		Vec3 eye = p.getEyePosition();
		double dx = at.x - eye.x, dz = at.z - eye.z, flat = Math.hypot(dx, dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(at.y - eye.y + flat * flat * 0.0028, flat));
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setXRot(Math.max(-60f, pitch));
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		c.goals.instant = "throwing an ender pearl";
		c.journal("does", "throws an ender pearl");
		XenMod.LOG.info("{} throws an ender pearl", c.name);
		c.acted = true;
		return Action.IDLE;
	}
}
