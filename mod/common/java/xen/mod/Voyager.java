package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * Going far, in every world. With an elytra and rockets it flies the long way (it puts the elytra on, jumps, opens it
 * falling, boosts with a rocket when it slows, steers for where it's going, and comes down gently when it's there),
 * like a player. After the dragon it goes looking for one: through an End gateway (an ender pearl thrown into it), out
 * to the outer islands, to an End city's ship, where the elytra hangs in an item frame. It remembers every world it has
 * been to.
 */
final class Voyager {
	enum Quest { TO_END, GATEWAY, CITY, SHIP, DONE }

	private final Companion c;
	Quest quest;
	private long questSince, lastRocket;
	private BlockPos gateway, city;
	private boolean flying;
	private Vec3 flyTo;

	Voyager(Companion c) {
		this.c = c;
	}

	private ServerLevel level() {
		return (ServerLevel) c.player.level();
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	static boolean isElytra(ItemStack s) {
		return !s.isEmpty() && s.getItem() == Items.ELYTRA && s.getDamageValue() < s.getMaxDamage() - 10;
	}

	boolean hasElytra() {
		if (isElytra(c.player.getItemBySlot(EquipmentSlot.CHEST))) return true;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isElytra(inv.getItem(i))) return true;
		return false;
	}

	int rockets() {
		return MindSense.count(c, n -> n.equals("firework_rocket"));
	}

	/** Worth flying there? Far (over 96 blocks), under the open sky, not in the Nether (its ceiling), an elytra and rockets. */
	boolean shouldFly(Vec3 to) {
		if (c.player == null || c.player.isCreative() || c.nether.inNether() || !hasElytra() || rockets() < 2) return false;
		double far = Math.hypot(to.x - c.player.getX(), to.z - c.player.getZ());
		return far > 96 && level().canSeeSky(c.player.blockPosition().above());
	}

	boolean flying() {
		return flying || c.player != null && c.player.isFallFlying();
	}

	/** One step of flying there; null when it has landed (or can't fly). */
	Action flyTo(Vec3 to) {
		var p = c.player;
		flyTo = to;
		if (!isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) {                 // the elytra on first (a right-click swaps it with the chestplate)
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (!isElytra(inv.getItem(i))) continue;
				int slot = i < 9 ? i : 8;
				if (i >= 9) {
					ItemStack held = inv.getItem(8);
					inv.setItem(8, inv.getItem(i));
					inv.setItem(i, held);
				}
				inv.setSelectedSlot(slot);
				p.gameMode.useItem(p, p.level(), inv.getSelectedItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
				c.acted = true;
				return Action.PLACE;
			}
			return null;
		}
		double dx = to.x - p.getX(), dz = to.z - p.getZ(), far = Math.hypot(dx, dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		if (!p.isFallFlying()) {
			if (flying && p.onGround()) {                                     // landed
				flying = false;
				c.goals.instant = "landed";
				return null;
			}
			if (far < 24) return null;
			p.setYRot(yaw);
			p.setYHeadRot(yaw);
			if (p.onGround()) {                                               // a jump...
				p.setXRot(-30);
				p.setJumping(true);
				p.jumpFromGround();
				c.acted = true;
				return Action.JUMP;
			}
			if (p.getDeltaMovement().y < 0 && p.tryToStartFallFlying()) {       // ...and it opens the elytra falling
				flying = true;
				rocket();
			}
			c.acted = true;
			return Action.IDLE;
		}
		flying = true;
		c.goals.instant = String.format(java.util.Locale.ROOT, "flying (%.0f blocks to go)", far);
		double height = p.getY() - level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
		float pitch = far < 30 ? 25f : height < 20 ? -25f : height > 60 ? 5f : -8f;   // up while low, level cruising, down to land
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setXRot(pitch);
		double speed = p.getDeltaMovement().length();
		if (far >= 30 && speed < 1.1 && now() - lastRocket > 30) rocket();
		c.acted = true;
		return Action.IDLE;
	}

	private void rocket() {
		var inv = c.player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).getItem() != Items.FIREWORK_ROCKET) continue;
			inv.setSelectedSlot(i);
			c.player.gameMode.useItem(c.player, c.player.level(), inv.getItem(i), net.minecraft.world.InteractionHand.MAIN_HAND);
			lastRocket = now();
			return;
		}
		for (int i = 9; i < inv.getContainerSize(); i++) {                   // (rockets from its bag into the hotbar)
			if (inv.getItem(i).getItem() != Items.FIREWORK_ROCKET) continue;
			ItemStack held = inv.getItem(7);
			inv.setItem(7, inv.getItem(i));
			inv.setItem(i, held);
			return;
		}
	}

	// ------------------------------------------------------------------------ the elytra quest
	/** After the dragon, a brave or curious Xen with no elytra goes for one (in the End). */
	boolean wantsQuest() {
		return quest == null && c.goals.dragonDown && !hasElytra() && c.mod.config.adventures
				&& (c.personality.curiosity > 0.5f || c.personality.bravery > 0.6f) && c.items().getOrDefault("ender_pearl", 0) >= 2;
	}

	String startQuest() {
		quest = Quest.TO_END;
		questSince = now();
		gateway = city = null;
		return "You will go back to the End for an elytra: through a gateway to the outer islands, to an End city's ship.";
	}

	boolean onQuest() {
		return quest != null && quest != Quest.DONE;
	}

	Action questStep() {
		if (!onQuest()) return null;
		if (now() - questSince > 20 * 60 * 30 || hasElytra()) {               // half an hour, or it has one: done
			if (hasElytra()) c.say(c.pick3("An elytra! I can fly!", "Got the elytra. Wings!", "Finally, wings."));
			quest = Quest.DONE;
			return null;
		}
		boolean inEnd = level().dimension() == net.minecraft.world.level.Level.END;
		switch (quest) {
			case TO_END -> {
				if (inEnd) {
					quest = Quest.GATEWAY;
					return null;
				}
				BlockPos portal = c.places.get("end portal");
				if (portal == null) {
					quest = Quest.DONE;
					c.chatter("I don't remember where the End portal is.", false);
					return null;
				}
				c.goals.instant = "going to the End portal";
				return c.walkTo(Vec3.atBottomCenterOf(portal));
			}
			case GATEWAY -> {
				if (!inEnd) {
					quest = Quest.TO_END;
					return null;
				}
				if (c.player.blockPosition().distSqr(BlockPos.ZERO) > 400 * 400) {   // through already: the outer islands
					quest = Quest.CITY;
					return null;
				}
				if (gateway == null) gateway = findGateway();
				if (gateway == null) {
					quest = Quest.DONE;
					return null;
				}
				c.goals.instant = "going to an End gateway";
				Vec3 g = Vec3.atCenterOf(gateway);
				double d = c.player.position().distanceTo(g);
				if (d > 20) return c.walkTo(g.add(g.subtract(c.player.position()).normalize().scale(-14)));
				return throwPearl(g);                                           // a pearl into the gateway takes it through
			}
			case CITY -> {
				if (city == null) city = c.nether.lookFor(level(), "purpur", 128);
				if (city == null) {
					c.goals.instant = "looking for an End city";
					return c.goals.exploreStep();
				}
				c.goals.instant = "going to the End city";
				if (c.player.blockPosition().closerThan(city, 24)) {
					quest = Quest.SHIP;
					return null;
				}
				return c.walkTo(Vec3.atBottomCenterOf(city));
			}
			case SHIP -> {
				ItemFrame frame = null;
				for (ItemFrame f : level().getEntitiesOfClass(ItemFrame.class, c.player.getBoundingBox().inflate(64), f -> f.getItem().getItem() == Items.ELYTRA)) {
					frame = f;
					break;
				}
				if (frame == null) {
					c.goals.instant = "searching the city for its ship";
					return c.goals.exploreStep();
				}
				c.goals.instant = "getting the elytra from the ship";
				if (c.player.distanceTo(frame) > c.player.entityInteractionRange()) return c.walkTo(frame.position());
				c.hands.face(frame.position());
				c.player.attack(frame);                                         // the frame drops what's in it
				c.acted = true;
				return Action.ATTACK;
			}
			default -> {
				return null;
			}
		}
	}

	/** The End gateways sit in a ring 96 blocks out from the middle, at y 75 (after the dragon). */
	private BlockPos findGateway() {
		BlockPos best = null;
		for (int i = 0; i < 20; i++) {
			double a = 2 * Math.PI * i / 20;
			BlockPos at = BlockPos.containing(96 * Math.cos(a), 75, 96 * Math.sin(a));
			for (int dy = -3; dy <= 3; dy++) {
				BlockPos q = at.above(dy);
				if (!level().isLoaded(q) || !level().getBlockState(q).is(Blocks.END_GATEWAY)) continue;
				if (best == null || q.distSqr(c.player.blockPosition()) < best.distSqr(c.player.blockPosition())) best = q;
			}
		}
		return best;
	}

	private Action throwPearl(Vec3 at) {
		var inv = c.player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).getItem() != Items.ENDER_PEARL) continue;
			inv.setSelectedSlot(i);
			Vec3 eye = c.player.getEyePosition();
			double dx = at.x - eye.x, dz = at.z - eye.z, flat = Math.hypot(dx, dz);
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float pitch = (float) -Math.toDegrees(Math.atan2(at.y - eye.y + flat * flat * 0.0028, flat));   // (a pearl drops a little on its way)
			c.player.setYRot(yaw);
			c.player.setYHeadRot(yaw);
			c.player.setXRot(pitch);
			c.player.gameMode.useItem(c.player, c.player.level(), inv.getItem(i), net.minecraft.world.InteractionHand.MAIN_HAND);
			c.acted = true;
			return Action.PLACE;
		}
		for (int i = 9; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() != Items.ENDER_PEARL) continue;
			ItemStack held = inv.getItem(6);
			inv.setItem(6, inv.getItem(i));
			inv.setItem(i, held);
			return Action.IDLE;
		}
		quest = Quest.DONE;
		return null;
	}

	/** The worlds it has been to (for its notes). */
	String describe() {
		StringBuilder sb = new StringBuilder();
		var been = c.places.worlds();
		if (been.size() > 1) sb.append("You have been to ").append(String.join(", ", been)).append(". ");
		if (hasElytra()) sb.append("You have an elytra").append(rockets() > 0 ? " and rockets: you fly the long trips." : ".");
		return sb.toString().trim();
	}
}
