package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Locale;

/**
 * Portals and the Nether, the way a player goes about them.
 * <ul>
 *   <li><b>Following through.</b> When the one it follows steps through a portal, it walks to where it last saw them,
 *   into the portal, and waits the four seconds it takes, like anyone; on the other side it keeps following.</li>
 *   <li><b>Remembering.</b> Every portal it goes through it remembers, on both sides (the way back home).</li>
 *   <li><b>Its own trips.</b> It builds a portal (ten obsidian and a flint and steel: obsidian mined with a diamond
 *   pickaxe, or made by pouring water on a lava pool; flint from gravel), lights it and goes. In the Nether it wears a
 *   piece of gold (piglins leave you alone then), looks out for a fortress's dark bricks, fights blazes there for their
 *   rods, and goes back the way it came.</li>
 *   <li><b>Portal math.</b> A block in the Nether is eight in the overworld. Xens don't know that from birth: a Xen
 *   of the fourth generation of evolution does (or one a player or a tribe mate has taught). Knowing it, it builds its
 *   way back at its home's Nether coordinates (x/8, z/8), so it comes out right by its home.</li>
 * </ul>
 */
final class Nether {
	private final Companion c;
	/** Where it last saw the one it follows (and in which world). */
	private Vec3 leaderSeenAt;
	private Level leaderSeenIn;
	/** The portal it's following someone through (or taking itself), and since when it's been at it. */
	BlockPos portalTo;
	private long portalSince;
	private Level lastLevel;
	private BlockPos lastPos;
	/** Its own trip: why ("visit", "blaze", "home"), what for, and the portal it's building and lighting. */
	String purpose;
	private int rodsWanted;
	BlockPos lightAt;
	private Direction portalFront = Direction.NORTH;
	private long tripSince, lastSay;
	private final java.util.Random random = new java.util.Random();
	private boolean sawNether, sawEnd;

	Nether(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	static boolean isPortal(Level level, BlockPos p) {
		var s = level.getBlockState(p);
		return s.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) || s.is(net.minecraft.world.level.block.Blocks.END_PORTAL);
	}

	/** A portal block within r blocks of here (the nearest), or null. */
	static BlockPos portalNear(Level level, BlockPos at, int r) {
		BlockPos best = null;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
			if (isPortal(level, q) && (best == null || q.distSqr(at) < best.distSqr(at))) best = q.immutable();
		}
		return best;
	}

	boolean inNether() {
		return Places.dim(c.player.level()).equals("the_nether");
	}

	/** Does it know that the Nether is eight times smaller (the fourth generation does, or it was taught)? */
	boolean knowsMath() {
		return c.knowledge.knows("portal_math");
	}

	static BlockPos toNether(BlockPos p) {
		return new BlockPos(Math.floorDiv(p.getX(), 8), Math.max(32, Math.min(100, p.getY())), Math.floorDiv(p.getZ(), 8));
	}

	// ------------------------------------------------------------------------------ every tick
	void tick() {
		var p = c.player;
		if (lastLevel != null && p.level() != lastLevel) arrived(lastLevel, lastPos);
		lastLevel = p.level();
		lastPos = p.blockPosition();
		if (p.tickCount % 40 == 10 && inNether() && c.knowledge.knows("piglin_gold")) wearGold();
	}

	/** Through a portal: it remembers both ends, and says what it thinks of where it is. */
	private void arrived(Level from, BlockPos fromPos) {
		ServerLevel here = (ServerLevel) c.player.level();
		BlockPos left = fromPos == null ? null : portalNear(from, fromPos, 3);
		if (left != null) c.places.remember("portal", Places.dim(from), left);
		BlockPos came = portalNear(here, c.player.blockPosition(), 3);
		String dim = Places.dim(here);
		if (came != null) c.places.remember("portal", dim, came);
		else if (dim.equals("the_end")) c.places.remember("end platform", dim, c.player.blockPosition());
		portalTo = null;
		c.walker.portalOk = null;
		c.walker.stop();
		c.journal("does", "went through a portal from " + Places.dim(from) + " to " + dim);
		switch (dim) {
			case "the_nether" -> c.chatter(sawNether ? c.pick3("Back in the Nether.", "The Nether again. Stay away from the lava.", "Hot in here.")
					: c.pick3("Whoa, the Nether! Careful with the lava.", "So this is the Nether... it's so hot.", "The Nether! Let's not fall in anything."), true);
			case "the_end" -> c.chatter(sawEnd ? "The End again." : c.pick3("The End... there's the dragon.", "We made it to the End!", "It's so quiet here. Don't look at the endermen."), true);
			default -> c.chatter(c.pick3("Back in the overworld!", "Home sweet overworld.", "Phew, blue sky again."), false);
		}
		sawNether |= dim.equals("the_nether");
		sawEnd |= dim.equals("the_end");
		if (dim.equals("overworld") && "home".equals(purpose)) finishTrip("Back home from the Nether!");
	}

	/** In the Nether: a piece of gold on (piglins leave players in gold alone), the way players go there. */
	private void wearGold() {
		var inv = c.player.getInventory();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST}) {
			if (BuiltInRegistries.ITEM.getKey(c.player.getItemBySlot(slot).getItem()).getPath().startsWith("golden_")) return;   // already
		}
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (!n.startsWith("golden_") || c.player.getEquipmentSlotForItem(s).getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
			EquipmentSlot slot = c.player.getEquipmentSlotForItem(s);
			ItemStack worn = c.player.getItemBySlot(slot);
			c.player.setItemSlot(slot, s.copy());
			inv.setItem(i, worn.copy());
			c.chatter("Gold on, so the piglins leave me alone.", false);
			return;
		}
	}

	// ------------------------------------------------------------------------------ following through
	/** Every tick it follows someone: where it last saw them, and whether they just went through a portal. */
	void watchLeader(ServerPlayer leader) {
		if (leader == null) return;
		if (leader.level() == c.player.level()) {
			leaderSeenAt = leader.position();
			leaderSeenIn = leader.level();
			if (portalTo != null && purpose == null) {
				portalTo = null;
				c.walker.portalOk = null;
			}
			return;
		}
		if (portalTo == null && leaderSeenAt != null && leaderSeenIn == c.player.level()) {   // gone to another world, from here
			BlockPos portal = portalNear(c.player.level(), BlockPos.containing(leaderSeenAt), 3);
			if (portal != null) {
				portalTo = portal;
				portalSince = now();
				c.chatter(c.pick3("Wait for me!", "Coming through!", "Right behind you!"), true);
			}
		}
	}

	/** Its friend went through a portal: after them. Null if not. */
	Action followThrough() {
		if (portalTo == null || purpose != null) return null;
		if (!isPortal(c.player.level(), portalTo) || now() - portalSince > 20 * 60) {
			portalTo = null;
			c.walker.portalOk = null;
			return null;
		}
		c.goals.instant = "following through the portal";
		return enter(portalTo);
	}

	/** Into a portal, and wait in it (a Nether portal takes four seconds). */
	Action enter(BlockPos portal) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		if (isPortal(level, feet) || isPortal(level, feet.above())) {
			c.hands.stop();
			c.acted = true;
			return Action.IDLE;                                                // standing in it: the world swirls
		}
		c.walker.portalOk = portal;
		BlockPos bottom = portal;
		while (isPortal(level, bottom.below())) bottom = bottom.below();
		Vec3 in = Vec3.atBottomCenterOf(bottom);
		double flat = Math.hypot(in.x - c.player.getX(), in.z - c.player.getZ());
		if (flat < 1.8 && Math.abs(in.y - c.player.getY()) < 1.2) {           // right next to it: step in
			c.hands.steer = in;
			c.hands.face(in.add(0, 1, 0));
			return Action.FORWARD;
		}
		return c.walkTo(in);
	}

	// ------------------------------------------------------------------------------ its own trips
	/** Go to the Nether (to look around, or for blaze rods), or back home from it. The plan, or why it can't. */
	String go(String why, int rods) {
		if (c.player.isCreative()) return "You won't: creative players just fly around.";
		boolean nether = inNether();
		if (why.equals("home")) {
			if (!nether) return "You are not in the Nether.";
			start("home");
			return "You will go back home through a portal" + (knowsMath() && c.goals.home != null ? " (your home is at " + toNether(c.goals.home).getX()
					+ ", " + toNether(c.goals.home).getZ() + " in the Nether)" : "") + ".";
		}
		if (nether) {
			start(why);
			rodsWanted = rods;
			return why.equals("blaze") ? "You will look for a Nether fortress and fight blazes for " + rods + " blaze rods." : "You will look around the Nether.";
		}
		BlockPos portal = c.places.nearest("portal");
		boolean canBuild = obsidian() >= 10 && has("flint_and_steel");
		boolean canGet = c.crafter.pickTier() >= 4 || has("water_bucket") || has("bucket") || iron() >= 3;
		if (portal == null && !canBuild && !canGet) {
			return "You can't go to the Nether yet: you need a portal (10 obsidian, mined with a diamond pickaxe or made by pouring water on lava, and a flint and steel).";
		}
		start(why);
		rodsWanted = rods;
		String how = portal != null ? "through the portal you know" : canBuild ? "through a portal you will build and light" : "through a portal: first you will get obsidian and flint";
		return "You will go to the Nether " + how + (why.equals("blaze") ? ", then find a fortress and fight blazes for " + rods + " blaze rods." : ".");
	}

	private void start(String why) {
		purpose = why;
		tripSince = now();
		c.goals.drop();
		c.chores.cancel();
		c.journal("does", "nether trip: " + why);
	}

	void cancel() {
		purpose = null;
		lightAt = null;
		portalTo = null;
		c.walker.portalOk = null;
	}

	private void finishTrip(String say) {
		cancel();
		c.chatter(say, true);
	}

	boolean busy() {
		return purpose != null || lightAt != null;
	}

	private boolean has(String id) {
		return count(id) > 0;
	}

	private int count(String id) {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().equals(id)) n += inv.getItem(i).getCount();
		return n;
	}

	private int obsidian() {
		return count("obsidian");
	}

	private int iron() {
		return count("iron_ingot");
	}

	private void say(String s) {
		if (now() - lastSay < 400) return;
		lastSay = now();
		c.chatter(s, false);
	}

	/** The next step of its trip (or of lighting a portal it built), or null to let other things decide. */
	Action next() {
		if (c.player.isCreative()) return null;
		if (lightAt != null) {
			Action a = light();
			if (a != null || lightAt != null) return a;
		}
		if (purpose == null) return null;
		if (c.chores.busy() || c.builder.busy() || c.crafter.hasOrder()) return null;   // getting something for the trip
		String dim = Places.dim(c.player.level());
		if (dim.equals("the_end")) return null;                                   // (the dragon is another story)
		if (now() - tripSince > 20 * 60 * 20) {
			finishTrip("This trip is taking too long. I'll try again later.");
			return null;
		}
		return dim.equals("the_nether") ? inside() : toPortal();
	}

	/** In the overworld: to a portal (building it first, and getting what that takes). */
	private Action toPortal() {
		if (purpose.equals("home")) {
			finishTrip("I'm home.");
			return null;
		}
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos portal = c.places.nearest("portal");
		if (portal != null && !isPortal(level, portal) && level.isLoaded(portal)) {
			c.places.forget("portal");                                           // it's gone (someone broke it)
			portal = null;
		}
		if (portal != null && portal.distSqr(c.player.blockPosition()) < 3000 * 3000) {
			c.goals.instant = "going to the Nether portal";
			return enter(portal);
		}
		// no portal: build one (after getting what it needs)
		if (!has("flint_and_steel")) {
			if (iron() >= 1 && has("flint")) {
				c.crafter.orderRecipe("flint_and_steel", 1);
				c.goals.instant = "making a flint and steel";
				return null;
			}
			if (!has("flint")) {
				say("I need flint for a flint and steel. Gravel drops it sometimes.");
				c.chores.mineFor("gravel", "flint", 1, "flint");
				c.chores.own = true;
				return null;
			}
			say("I need an iron ingot for a flint and steel.");
			if (c.crafter.pickTier() >= 2) {
				c.chores.mine(16, "iron", 3);
				c.chores.own = true;
			} else finishTrip("I'm not ready for the Nether yet.");
			return null;
		}
		if (obsidian() < 10) return getObsidian();
		BlockPos near = c.goals.home != null && c.player.blockPosition().distSqr(c.goals.home) < 64 * 64 ? c.goals.home.offset(6, 0, 6) : null;
		String plan = c.builder.startNear("nether portal", near);
		if (!plan.startsWith("You will")) {
			finishTrip("I can't build a portal here.");
			return null;
		}
		c.chatter("I'm building a Nether portal!", true);
		return null;
	}

	private BlockPos lavaAt;
	private long pouredAt = -1;

	/** Ten obsidian: mined where it sees some (a diamond pickaxe), or made by pouring water onto a lava pool first. */
	private Action getObsidian() {
		if (c.crafter.pickTier() < 4) {
			say("I need a diamond pickaxe to mine obsidian.");
			if (c.items().getOrDefault("diamond", 0) >= 3) return null;           // (the crafter makes it)
			c.chores.mine(-54, "diamonds", 3);
			c.chores.own = true;
			return null;
		}
		ServerLevel level = (ServerLevel) c.player.level();
		if (visible("obsidian", 16) != null) {
			c.chores.mineFor("obsidian", "obsidian", 10 - obsidian(), "obsidian");
			c.chores.own = true;
			return null;
		}
		if (!c.knowledge.knows("obsidian")) {                               // it doesn't know you can make it: it looks for some
			say("I need obsidian. I'll look around for some.");
			BlockPos seen = lookFor(level, "obsidian", 64);
			if (seen != null) return c.walkTo(Vec3.atBottomCenterOf(seen.above()));
			return c.goals.exploreStep();
		}
		if (!has("water_bucket")) {
			if (!has("bucket")) {
				if (iron() >= 3) {
					c.crafter.orderRecipe("bucket", 1);
					return null;
				}
				say("I need a bucket of water to make obsidian from lava.");
				finishTrip("I'll get iron for a bucket first.");
				return null;
			}
			BlockPos water = visibleWater(16);
			if (water == null) {
				say("I need water for my bucket.");
				return c.goals.exploreStep();
			}
			if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(water)) > 4) return c.walkTo(Vec3.atBottomCenterOf(water));
			c.hands.bucket(Vec3.atCenterOf(water).add(0, 0.4, 0), s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("bucket"));
			c.acted = true;
			return Action.PLACE;
		}
		if (lavaAt == null || !level.getFluidState(lavaAt).isSource()) lavaAt = visibleLava(20);
		if (lavaAt == null) {
			say("I'm looking for a lava pool to make obsidian.");
			return c.goals.exploreStep();
		}
		BlockPos top = lavaAt.above();
		double d = c.player.getEyePosition().distanceTo(Vec3.atCenterOf(top));
		if (d > 4) return c.walkTo(Vec3.atBottomCenterOf(top));
		if (pouredAt < 0) {
			c.goals.instant = "pouring water on the lava";
			c.hands.pour(top, Direction.UP);
			pouredAt = now();
			c.acted = true;
			return Action.PLACE;
		}
		if (now() - pouredAt < 30) return Action.IDLE;                             // the water flows over it
		pouredAt = -1;
		c.hands.bucket(Vec3.atCenterOf(top), s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("bucket"));   // the water back
		lavaAt = null;
		c.acted = true;
		c.chatter("Obsidian! Now to mine it.", false);
		return Action.PLACE;
	}

	/** The nearest block of this kind it can see (in the open, within r), or null. */
	private BlockPos visible(String id, int r) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -6, -r), feet.offset(r, 6, r))) {
			if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath().equals(id)) continue;
			if (!seen(level, q)) continue;
			if (best == null || q.distSqr(feet) < best.distSqr(feet)) best = q.immutable();
		}
		return best;
	}

	private boolean seen(ServerLevel level, BlockPos q) {
		Vec3 eye = c.player.getEyePosition();
		if (eye.distanceTo(Vec3.atCenterOf(q)) < 6) return true;
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.ANY, c.player));
		return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(q);
	}

	private BlockPos visibleWater(int r) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -4, -r), feet.offset(r, 4, r))) {
			var f = level.getFluidState(q);
			if (!f.isSource() || !f.is(net.minecraft.tags.FluidTags.WATER) || !level.getBlockState(q.above()).isAir()) continue;
			if (best == null || q.distSqr(feet) < best.distSqr(feet)) best = q.immutable();
		}
		return best;
	}

	private BlockPos visibleLava(int r) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -8, -r), feet.offset(r, 4, r))) {
			var f = level.getFluidState(q);
			if (!f.isSource() || !f.is(net.minecraft.tags.FluidTags.LAVA) || !level.getBlockState(q.above()).isAir() || !seen(level, q.above())) continue;
			if (best == null || q.distSqr(feet) < best.distSqr(feet)) best = q.immutable();
		}
		return best;
	}

	/** The portal it just built: light it (a flint and steel on the obsidian at the bottom, from outside). */
	void built(BlockPos insideBottom, Direction front) {
		lightAt = insideBottom.below();
		portalFront = front;
		c.places.remember("portal", insideBottom);
	}

	private Action light() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos inside = lightAt.above();
		if (isPortal(level, inside)) {
			c.chatter(c.pick3("The portal is lit!", "It works! A Nether portal.", "Portal's open!"), true);
			c.places.remember("portal", inside);
			lightAt = null;
			return null;
		}
		if (!has("flint_and_steel")) {
			if (iron() >= 1 && has("flint")) {
				c.crafter.orderRecipe("flint_and_steel", 1);
				return null;
			}
			lightAt = null;
			return null;
		}
		BlockPos stand = inside.relative(portalFront);
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(lightAt)) > c.player.blockInteractionRange() - 0.8
				|| c.player.blockPosition().equals(inside) || c.player.blockPosition().equals(inside.above())) {
			c.goals.instant = "going to light the portal";
			return c.walkTo(Vec3.atBottomCenterOf(stand));
		}
		c.goals.instant = "lighting the portal";
		c.hands.useWith(lightAt, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("flint_and_steel"));
		c.acted = true;
		return Action.PLACE;
	}

	// ------------------------------------------------------------------------------ in the Nether
	private BlockPos fortress;
	private double heading = Double.NaN;
	private Vec3 exploreTo;
	private long exploreSince;

	private Action inside() {
		ServerLevel level = (ServerLevel) c.player.level();
		if (purpose.equals("visit") && now() - tripSince > 20 * 90) purpose = "home";
		if (purpose.equals("blaze") && count("blaze_rod") >= rodsWanted) {
			c.chatter("Got " + count("blaze_rod") + " blaze rods! Time to go home.", true);
			purpose = "home";
		}
		if (purpose.equals("home")) return home();
		if (fortress == null) fortress = c.places.get("fortress");
		if (fortress == null && c.player.tickCount % 20 == 0) {
			BlockPos seen = lookFor(level, "nether_brick", 64);
			if (seen != null) {
				fortress = seen;
				c.places.remember("fortress", seen);
				c.chatter("A Nether fortress! Blazes live there.", true);
			}
		}
		if (fortress == null) {
			c.goals.instant = "looking for a Nether fortress";
			return wander(level);
		}
		if (c.player.blockPosition().distSqr(fortress) > 20 * 20) {
			c.goals.instant = "going to the fortress";
			return c.walkTo(Vec3.atBottomCenterOf(fortress));
		}
		String plan = c.chores.slay("blaze", "blaze_rod", Math.max(1, rodsWanted - count("blaze_rod")), "blaze rods");
		c.chores.own = true;
		c.journal("does", plan);
		return null;
	}

	/** Back home: through the portal it knows (or, knowing the math, one it builds where its home is). */
	private Action home() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos home = c.goals.home, portal = c.places.nearest("portal");
		if (knowsMath() && home != null) {
			BlockPos target = toNether(home);
			BlockPos near = null;
			for (Places.Place p : c.places.places.values()) {
				if (p.dim().equals("the_nether") && p.name().equals("portal") && p.pos().distSqr(target) < 24 * 24) near = p.pos();
			}
			if (near != null) portal = near;
			else if (portal != null && Math.sqrt(portal.distSqr(target)) * 8 > 200 && obsidian() >= 10 && has("flint_and_steel")) {
				if (c.player.blockPosition().distSqr(target) > 6 * 6) {
					c.goals.instant = String.format(Locale.ROOT, "walking to %d, %d (home's spot in the Nether: %d/8, %d/8)", target.getX(), target.getZ(), home.getX(), home.getZ());
					say(String.format(Locale.ROOT, "My home is at %d, %d, so in the Nether that's %d, %d. I'll build a portal there.", home.getX(), home.getZ(),
							target.getX(), target.getZ()));
					return c.walkTo(Vec3.atBottomCenterOf(target));
				}
				String plan = c.builder.startNear("nether portal", null);
				if (plan.startsWith("You will")) return null;
			}
		}
		if (portal == null) {
			say("I don't know the way back! I'll look for a portal.");
			BlockPos seen = lookFor(level, "nether_portal", 48);
			if (seen != null) c.places.remember("portal", seen);
			return wander(level);
		}
		c.goals.instant = "going back to the portal";
		return enter(portal);
	}

	/** Walks on in one direction (a new one now and then), the way players search the Nether. */
	private Action wander(ServerLevel level) {
		if (Double.isNaN(heading)) heading = random.nextDouble() * Math.PI * 2;
		if (exploreTo == null || c.player.position().distanceTo(exploreTo) < 4 || now() - exploreSince > 20 * 45) {
			if (exploreTo != null && now() - exploreSince > 20 * 45) heading += Math.PI / 2 * (random.nextBoolean() ? 1 : -1);   // stuck: turn
			heading += (random.nextDouble() - 0.5) * 0.6;
			BlockPos from = c.player.blockPosition();
			exploreTo = new Vec3(from.getX() + Math.cos(heading) * 40, from.getY(), from.getZ() + Math.sin(heading) * 40);
			exploreSince = now();
		}
		c.run(true);
		return c.walkTo(exploreTo);
	}

	/**
	 * Looks around for a kind of block, like a player's eyes sweeping the view: rays out in front of it (and a few
	 * behind), up to this far; the first block each one meets.
	 */
	BlockPos lookFor(ServerLevel level, String prefix, double far) {
		Vec3 eye = c.player.getEyePosition();
		for (int i = 0; i < 40; i++) {
			double yaw = Math.toRadians(c.player.getYRot() + (random.nextDouble() - 0.5) * (i < 30 ? 110 : 360));
			double pitch = Math.toRadians((random.nextDouble() - 0.5) * 70);
			Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(dir.scale(far)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
					net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
			String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).getPath();
			if (n.startsWith(prefix)) return hit.getBlockPos().immutable();
		}
		return null;
	}

	/** For its notes: the trip, and the portal math if it knows it. */
	String describe() {
		StringBuilder sb = new StringBuilder();
		if (purpose != null) sb.append(switch (purpose) {
			case "blaze" -> "You are on a trip to the Nether to get blaze rods.";
			case "home" -> "You are on your way back home from the Nether.";
			default -> "You are visiting the Nether.";
		});
		if (knowsMath()) {
			sb.append(sb.length() > 0 ? " " : "").append("You know that one block in the Nether is eight in the overworld");
			if (c.goals.home != null) {
				BlockPos n = toNether(c.goals.home);
				sb.append(" (your home is at ").append(n.getX()).append(", ").append(n.getZ()).append(" in the Nether)");
			}
			sb.append('.');
		}
		return sb.toString();
	}
}
