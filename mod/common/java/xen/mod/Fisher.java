package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * Fishing, like a player: it stands on the shore, casts its rod at the water a few blocks out, watches the bobber, and
 * reels in when it dips (a fish bit). No rod? It makes one (three sticks and two string). Asked ("go fishing"), when it's
 * hungry with water close by, or just for fun on a quiet day.
 */
final class Fisher {
	private final Companion c;
	boolean on;
	private BlockPos water, shore;
	private long until, castAt, reeledAt;
	private int caught, casts;

	Fisher(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	boolean hasRod() {
		return c.hands.hotbar(s -> s.getItem() == Items.FISHING_ROD) >= 0;
	}

	/** Start fishing (for a few minutes); what it will do, or why it can't. */
	String start() {
		if (!hasRod()) {
			if (c.items().getOrDefault("string", 0) >= 2) {
				String made = c.crafter.request("fishing_rod", 1);
				if (!made.startsWith("You will")) return "I need a fishing rod: three sticks and two string. " + made;
			} else {
				return "I need a fishing rod (two string and three sticks). Spiders drop string.";
			}
		}
		water = findWater();
		if (water == null) return "There's no water close by to fish in.";
		on = true;
		until = now() + 20 * 60 * 4;
		caught = casts = 0;
		castAt = 0;
		return "You will go fishing for a while.";
	}

	void stop() {
		if (on && c.player != null && c.player.fishing != null) reel();
		on = false;
		water = shore = null;
	}

	/** Open water it can see within 14 blocks (a source with air above, not a puddle), and a dry spot next to it to stand on. */
	private BlockPos findWater() {
		var level = c.player.level();
		BlockPos at = c.player.blockPosition(), best = null;
		double bestD = 1e9;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-14, -4, -14), at.offset(14, 3, 14))) {
			if (!level.getFluidState(q).isSource() || !level.getBlockState(q).is(Blocks.WATER) || !level.getBlockState(q.above()).canBeReplaced()
					|| !level.getFluidState(q.above()).isEmpty()) continue;
			int wet = 0;
			for (BlockPos n : new BlockPos[] {q.north(), q.south(), q.east(), q.west(), q.north().east(), q.south().west()}) if (level.getFluidState(n).isSource()) wet++;
			if (wet < 4) continue;                                                  // (a pond, not a puddle)
			double d = q.distSqr(at);
			if (d < bestD) {
				bestD = d;
				best = q.immutable();
			}
		}
		shore = best == null ? null : shoreBy(best);
		return shore == null ? null : best;
	}

	private BlockPos shoreBy(BlockPos w) {
		var level = c.player.level();
		BlockPos best = null;
		double bestD = 1e9;
		for (BlockPos q : BlockPos.betweenClosed(w.offset(-5, -1, -5), w.offset(5, 2, 5))) {
			if (level.getBlockState(q).canBeReplaced() && level.getBlockState(q.above()).canBeReplaced() && level.getFluidState(q).isEmpty()
					&& level.getFluidState(q.above()).isEmpty() && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)) {   // (snow, grass: fine to stand in)
				double d = q.distSqr(w);
				if (d >= 4 && d < bestD) {                                           // (a few blocks from the spot it casts at)
					bestD = d;
					best = q.immutable();
				}
			}
		}
		return best;
	}

	/** Its next move while fishing; null when it's done (or can't go on). */
	Action next() {
		if (!on || c.player == null) return null;
		var p = c.player;
		if (now() > until || water == null || !hasRod() && !c.crafter.hasOrder()) {
			stop();
			c.chatter(caught > 0 ? c.pick3("Good fishing today: " + caught + " catches.", "That's enough fishing. Caught " + caught + ".", caught + " fish! Not bad.")
					: "Nothing's biting today.", false);
			return null;
		}
		if (!hasRod()) return null;                                                   // (making the rod first)
		c.goals.instant = "fishing";
		if (p.blockPosition().distSqr(shore) > 2) {
			if (p.fishing != null) reel();
			return c.walkTo(Vec3.atBottomCenterOf(shore));
		}
		Vec3 spot = Vec3.atCenterOf(water).add(0, 0.4, 0);
		var hook = p.fishing;
		if (hook == null) {
			if (now() - reeledAt < 15) {
				c.hands.face(spot);
				c.acted = true;
				return Action.IDLE;
			}
			int slot = c.hands.hotbar(s -> s.getItem() == Items.FISHING_ROD);
			p.getInventory().setSelectedSlot(slot);
			c.hands.face(spot.add(0, 1.5, 0));                                         // (a cast arcs: a little above where it should land)
			p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
			Compat.swing(p);
			castAt = now();
			casts++;
			c.acted = true;
			return Action.IDLE;
		}
		c.hands.face(hook.position());                                                  // eyes on the bobber
		boolean dipped = hook.isInWater() && hook.getDeltaMovement().y < -0.15 && now() - castAt > 30;
		if (dipped || now() - castAt > 20 * 40 || !hook.isAlive() || hook.distanceTo(p) > 30) {   // a bite! (or it gives up on this cast)
			int before = c.items().getOrDefault("food", 0) + treasure();
			reel();
			if (dipped) c.mod.later(10, () -> {
				int after = c.items().getOrDefault("food", 0) + treasure();
				if (after > before) {
					caught++;
					c.skills.practice(Skills.FARM, 0.01f);
					if (caught == 1 || c.player.getRandom().nextFloat() < 0.3f) c.chatter(c.pick3("Got one!", "A catch!", "Fish on!"), false);
				}
			});
		}
		c.acted = true;
		return Action.IDLE;
	}

	private int treasure() {
		int n = 0;
		for (String k : new String[] {"bow", "enchanted_book", "name_tag", "nautilus_shell", "saddle", "fishing_rod", "lily_pad", "bowl", "leather", "string"}) n += c.items().getOrDefault(k, 0);
		return n;
	}

	private void reel() {
		var p = c.player;
		int slot = c.hands.hotbar(s -> s.getItem() == Items.FISHING_ROD);
		if (slot < 0 || p.fishing == null) return;
		p.getInventory().setSelectedSlot(slot);
		p.gameMode.useItem(p, p.level(), p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		reeledAt = now();
	}

	/** Water close by and a rod: a good moment for it (hungry, or a quiet day and it likes fishing). */
	boolean fancies() {
		return !on && hasRod() && c.player != null && !c.player.level().isDarkOutside() && findWater() != null;
	}
}
