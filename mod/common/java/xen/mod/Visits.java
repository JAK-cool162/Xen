package xen.mod;

import net.minecraft.core.BlockPos;
import xen.mod.core.Action;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Places it found, used the way a player uses them. Seeing a village isn't the end of it: a player goes in and gets what
 * it's good for, early on most of all.
 * <ul>
 *   <li><b>A village</b>: its chests (the blacksmith's iron, bread, saplings: it walks past the houses and loots what it
 *   sees), a bed off a villager (no sheep needed), hay bales (wheat: bread), its iron golem (3 to 5 iron, no mining or
 *   smelting: the speedrunner's way, from three blocks up, where the golem can't reach it), and a villager boxed in where
 *   it stands (feet and three sides at head height, a roof: it can't get away and it can still be traded with).</li>
 *   <li><b>A ruined portal</b>: its chest (gold, flint and steel, obsidian).</li>
 * </ul>
 * One visit at a time, a few minutes at most, each place once; only by day (or already there), free and not busy.
 */
final class Visits {
	private final Companion c;
	/** The place it's visiting, what kind, and till when. */
	BlockPos at;
	String kind = "";
	private long until, nextThink, nextScan;
	String doing = "";
	private final Set<BlockPos> visited = new HashSet<>();
	/** The village's beds and hay, its doors (the houses to walk past), and the ones it has been to. */
	private final java.util.List<BlockPos> beds = new java.util.ArrayList<>(), hay = new java.util.ArrayList<>(), doors = new java.util.ArrayList<>();
	private final Set<BlockPos> been = new HashSet<>();
	/** Its golem fight: the golem, and where its pillar starts. */
	private UUID golem;
	private BlockPos pillarBase;
	private boolean wantsIron, wantsBed, wantsFood, wantsVillager;
	/** A villager it's boxing in, since when it stood still there, and the box's blocks. */
	private UUID boxing;
	private BlockPos boxAt;
	private long boxSince;
	int golemsKilled, villagersBoxed, bedsTaken, hayTaken;

	Visits(Companion c) {
		this.c = c;
	}

	boolean busy() {
		return at != null;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** Free for a visit: its own time, nothing else on, not fighting, in the Overworld. */
	private boolean free() {
		return c.mode == Companion.Mode.FREE && !c.chores.busy() && !c.builder.busy() && !c.fightingNow() && !c.inArena && !c.minion
				&& !c.adventure.on && !c.nether.busy() && c.player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
	}

	/** The next step of a visit, or of starting one; null: none now. */
	Action next() {
		if (c.player == null || c.player.isCreative() || c.player.isSpectator()) return null;
		long now = now();
		if (at == null) {
			if (now < nextThink || !free() || c.player.level().isDarkOutside()) return null;
			nextThink = now + 200;
			pick();
			if (at == null) return null;
		}
		if (now > until || c.mode != Companion.Mode.FREE || c.inArena || c.player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			end("time's up there");
			return null;
		}
		if (c.fightingNow() && golem == null) return null;                   // (a monster first: its fight, then the visit goes on)
		c.goals.instant = doing;
		return kind.equals("village") ? village() : portal();
	}

	/** The nearest place worth a visit it knows of and hasn't been to (a village within 192, a ruined portal within 128). */
	private void pick() {
		BlockPos feet = c.player.blockPosition();
		BlockPos best = null;
		String bestKind = "";
		for (String k : new String[] {"village", "ruined portal"}) {
			BlockPos p = c.structures.found.get(k);
			if (p == null) p = c.places.get(k);
			if (p == null || visited.contains(p)) continue;
			if (!p.closerThan(feet, k.equals("village") ? 192 : 128)) continue;
			if (best == null || p.distSqr(feet) < best.distSqr(feet)) {
				best = p;
				bestKind = k;
			}
		}
		if (best == null) return;
		var items = c.items();
		int tier = c.crafter.pickTier();
		wantsIron = tier < 3 && items.getOrDefault("iron_ingot", 0) + items.getOrDefault("raw_iron", 0) < 3;
		wantsBed = items.keySet().stream().noneMatch(n -> n.endsWith("_bed")) && !c.hasBed();
		wantsFood = items.getOrDefault("food", 0) < 8;
		wantsVillager = c.goals.home != null || c.personality.plan == xen.mod.core.Strategy.SETTLER || c.personality.plan == xen.mod.core.Strategy.BUILDER;
		at = best;
		kind = bestKind;
		until = now() + 20 * 60 * 4;                                         // (four minutes there at most)
		beds.clear();
		hay.clear();
		doors.clear();
		been.clear();
		golem = null;
		pillarBase = null;
		boxing = null;
		nextScan = 0;
		doing = "going to the " + kind;
		c.journal("does", "goes to the " + kind + " at " + at.toShortString() + " (" + Math.round(Math.sqrt(at.distSqr(feet))) + " blocks; wants:"
				+ (wantsIron ? " iron" : "") + (wantsBed ? " a bed" : "") + (wantsFood ? " food" : "") + (wantsVillager ? " a villager" : "") + " loot)");
		c.chatter(kind.equals("village") ? c.pick3("Village! Let's see what they've got.", "Off to that village: beds, food, maybe iron.", "That village has stuff I need.")
				: c.pick3("A ruined portal. There's a chest at those.", "Ruined portal: loot!", "Let's check that portal's chest."), false);
	}

	private void end(String why) {
		if (at != null) {
			visited.add(at);
			c.journal("does", "done at the " + kind + " at " + at.toShortString() + ": " + why);
		}
		at = null;
		golem = null;
		boxing = null;
		pillarBase = null;
		c.hands.mayBreakBed = false;
		doing = "";
	}

	// ---------------------------------------------------------------------------------- a village
	private Action village() {
		ServerLevel level = (ServerLevel) c.player.level();
		Vec3 middle = Vec3.atBottomCenterOf(at);
		if (c.player.position().distanceTo(middle) > 24 && golem == null) {
			doing = "going to the village";
			c.run(c.player.position().distanceTo(middle) > 40);
			Action a = c.walkTo(middle);
			if (a == null && c.player.position().distanceTo(middle) > 24) end("no way there");
			return a;
		}
		if (now() >= nextScan) scan(level);
		if (c.storage.busy() && c.storage.looting()) {                       // a chest it's at: that first
			Action st = c.storage.next();
			if (st != null || c.storage.busy()) return st;
		}
		if (c.storage.spotLoot()) {                                           // (a chest in sight nobody opened: it looks inside)
			doing = "looting the village's chests";
			return c.storage.next();
		}
		Action g = golemFight(level);
		if (g != null) return g;
		if (wantsBed) {
			Action b = take(level, beds, "taking a bed (no sheep needed)", true);
			if (b != null) return b;
		}
		if (wantsFood && c.items().getOrDefault("hay_block", 0) < 2) {
			Action h = take(level, hay, "taking hay (wheat for bread)", false);
			if (h != null) return h;
		}
		if (c.items().getOrDefault("hay_block", 0) > 0 && !c.crafter.hasOrder()) {   // hay: wheat, then bread
			c.crafter.orderRecipe("wheat", c.items().getOrDefault("hay_block", 0));
			hayTaken++;
		} else if (c.items().getOrDefault("wheat", 0) >= 3 && !c.crafter.hasOrder()) {
			c.crafter.orderRecipe("bread", c.items().getOrDefault("wheat", 0) / 3);
		}
		Action v = wantsVillager ? boxVillager(level) : null;
		if (v != null) return v;
		for (BlockPos d : doors) {                                            // past every house, so it sees what's inside
			if (been.contains(d)) continue;
			if (c.player.blockPosition().closerThan(d, 3)) {
				been.add(d);
				continue;
			}
			doing = "walking through the village (" + been.size() + " of " + doors.size() + " houses)";
			Action a = c.walkTo(Vec3.atBottomCenterOf(d));
			if (a == null) been.add(d);
			return a;
		}
		end("seen it all (" + doors.size() + " houses)");
		return null;
	}

	/** What's in the village: its beds, hay bales and doors (a box round its middle). */
	private void scan(ServerLevel level) {
		nextScan = now() + 100;
		beds.clear();
		hay.clear();
		boolean firstDoors = doors.isEmpty();
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-40, -8, -40), at.offset(40, 10, 40))) {
			if (!level.isLoaded(q)) continue;
			var s = level.getBlockState(q);
			if (s.isAir()) continue;
			if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT) beds.add(q.immutable());
			else if (s.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK)) hay.add(q.immutable());
			else if (firstDoors && s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
					&& s.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) doors.add(q.immutable());
		}
		BlockPos feet = c.player.blockPosition();
		java.util.Comparator<BlockPos> near = java.util.Comparator.comparingDouble(p -> p.distSqr(feet));
		beds.sort(near);
		hay.sort(near);
		if (firstDoors) {
			doors.sort(near);
			while (doors.size() > 8) doors.remove(doors.size() - 1);
		}
	}

	/** Go to the nearest of those blocks and mine it (a bed: the one time it breaks a bed that isn't its own). */
	private Action take(ServerLevel level, java.util.List<BlockPos> list, String what, boolean bed) {
		for (int i = 0; i < list.size(); i++) {
			BlockPos b = list.get(i);
			var s = level.getBlockState(b);
			if (bed ? !(s.getBlock() instanceof BedBlock) : !s.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK)) {
				list.remove(i--);
				continue;
			}
			doing = what;
			if (!c.hands.canClick(b)) {
				Action a = c.walkTo(Vec3.atBottomCenterOf(b));
				if (a == null) {
					list.remove(i--);
					continue;
				}
				return a;
			}
			c.hands.mayBreakBed = bed;
			boolean ok = c.hands.mine(b);
			c.hands.mayBreakBed = false;
			if (!ok) {
				list.remove(i--);
				continue;
			}
			if (bed) {
				bedsTaken++;
				wantsBed = false;                                              // (one is all it needs)
				c.journal("does", "took a bed from the village at " + b.toShortString());
			}
			c.acted = true;
			return Action.MINE;
		}
		return null;
	}

	/**
	 * The village's iron golem, for its iron (3 to 5 ingots): the speedrunner's way. Up three blocks right by it, where its
	 * arms can't reach (it's 2.7 tall), and hit it from above till it drops. Only with blocks to climb on, a weapon, and
	 * its health up; hurt on the way, it stops and stays up there.
	 */
	private Action golemFight(ServerLevel level) {
		var p = c.player;
		var items = c.items();
		int blocks = items.getOrDefault("cobblestone", 0) + items.getOrDefault("dirt", 0) + items.getOrDefault("oak_planks", 0);
		boolean armed = c.hands.hotbar(st -> { String n = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(); return n.endsWith("_sword") || n.endsWith("_axe"); }) >= 0;
		IronGolem g = null;
		if (golem != null && level.getEntity(golem) instanceof IronGolem known && known.isAlive()) g = known;
		if (g == null) {
			if (golem != null) {                                              // it's down: the iron
				golem = null;
				pillarBase = null;
				golemsKilled++;
				wantsIron = false;
				c.journal("does", "killed the village's iron golem for its iron");
				c.chatter(c.pick3("Iron! Thanks, golem.", "Golem down. Free iron.", "That's my iron pickaxe sorted."), false);
				return null;
			}
			if (!wantsIron || !armed || blocks < 4 || p.getHealth() < 16) return null;
			for (IronGolem e : level.getEntitiesOfClass(IronGolem.class, p.getBoundingBox().inflate(32), x -> x.isAlive() && !x.isPlayerCreated())) {
				if (!WorldSenses.sees(p, c.hands.yaw, c.hands.pitch, e) && p.distanceTo(e) > 8) continue;
				g = e;
				break;
			}
			if (g == null) return null;
			golem = g.getUUID();
			pillarBase = null;
			c.journal("does", "goes for the iron golem (pillar up three, hit it from above)");
			c.chatter(c.pick3("That golem's got iron in it. Up I go.", "Iron golem: three blocks up and it can't touch me.", "Golem time."), false);
		}
		doing = "fighting the iron golem from a pillar";
		if (p.getHealth() < 8) {                                              // hurt: it stays up (or gives up) and eats
			if (pillarBase == null || p.getY() < pillarBase.getY() + 2.5) {
				golem = null;
				wantsIron = false;
				c.journal("does", "gives up on the golem: hurt");
				return null;
			}
			return Action.IDLE;
		}
		double up = pillarBase == null ? 0 : p.getY() - pillarBase.getY();
		if (pillarBase == null) {
			double flat = Math.hypot(g.getX() - p.getX(), g.getZ() - p.getZ());
			if (flat > 2.2 || !p.onGround()) return c.walkTo(g.position());    // (right by it first)
			pillarBase = p.blockPosition();
		}
		if (up < 2.9) {                                                      // up, a block at a time
			if (c.hands.busy()) return Action.JUMP;
			if (!c.hands.startPillar()) {
				golem = null;
				wantsIron = false;
				return null;
			}
			c.acted = true;
			return Action.JUMP;
		}
		if (p.distanceTo(g) <= p.entityInteractionRange() + 0.5) {
			c.hands.face(g.position().add(0, g.getBbHeight() * 0.8, 0));
			if (p.getAttackStrengthScale(0.5f) < 0.9f) return Action.IDLE;
			c.hands.hit(g);
			c.acted = true;
			return Action.ATTACK;
		}
		c.hands.face(g.position().add(0, g.getBbHeight() * 0.8, 0));        // it'll come (it's after it now): wait up here
		return Action.IDLE;
	}

	/**
	 * A villager boxed in where it stands, for trading: once it stands still (a villager at its work does), blocks on its
	 * four sides at its feet, three at its head (the front open, to trade through) and one on top. It can't walk or jump
	 * out of that. Once per village.
	 */
	private Action boxVillager(ServerLevel level) {
		var p = c.player;
		var items = c.items();
		if (items.getOrDefault("cobblestone", 0) + items.getOrDefault("dirt", 0) < 8) return null;
		Villager v = boxing == null ? null : level.getEntity(boxing) instanceof Villager known && known.isAlive() ? known : null;
		if (v == null) {
			if (boxing != null) {
				boxing = null;
				return null;
			}
			double best = Double.MAX_VALUE;
			for (Villager e : level.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(24), x -> x.isAlive() && !x.isBaby() && !x.isSleeping())) {
				double d = p.distanceTo(e);
				if (d < best && WorldSenses.sees(p, c.hands.yaw, c.hands.pitch, e)) {
					best = d;
					v = e;
				}
			}
			if (v == null) return null;
			boxing = v.getUUID();
			boxAt = null;
			boxSince = now();
		}
		if (now() - boxSince > 20 * 60) {                                      // a minute and it wouldn't stand still: not this one
			wantsVillager = false;
			boxing = null;
			return null;
		}
		doing = "boxing in a villager (to trade with)";
		BlockPos vf = v.blockPosition();
		if (boxAt == null || !boxAt.equals(vf)) {                            // it moved: start again where it stands now
			boxAt = vf;
		}
		Vec3 here = Vec3.atBottomCenterOf(vf);
		if (p.position().distanceTo(here) > 3.5) return c.walkTo(here);
		if (v.getDeltaMovement().horizontalDistanceSqr() > 0.0004) return Action.IDLE;   // (wait for it to stop)
		net.minecraft.core.Direction open = net.minecraft.core.Direction.getApproximateNearest(p.getX() - here.x, 0, p.getZ() - here.z);
		java.util.List<BlockPos> box = new java.util.ArrayList<>();
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			box.add(vf.relative(d));
			if (d != open) box.add(vf.above().relative(d));
		}
		box.add(vf.above(2));
		int done = 0;
		for (BlockPos b : box) {
			if (!level.getBlockState(b).canBeReplaced()) {
				done++;
				continue;
			}
			if (b.equals(p.blockPosition()) || b.equals(p.blockPosition().above())) {   // standing in it: a step back
				return c.walkTo(Vec3.atBottomCenterOf(vf.relative(open, 2)));
			}
			if (c.hands.placeAt(b, "stone") || c.hands.placeAt(b)) {
				c.acted = true;
				return Action.PLACE;
			}
		}
		if (done >= box.size()) {
			villagersBoxed++;
			wantsVillager = false;
			c.places.rememberAnother("villager", vf, 8);
			c.journal("does", "boxed in a villager at " + vf.toShortString() + " (" + v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("?")
					+ "): one to trade with");
			c.chatter(c.pick3("Got a villager. You're staying right here, buddy.", "One villager, boxed. Trades whenever I want.", "Sorry villager, you live here now."), false);
			boxing = null;
		}
		return null;
	}

	// ---------------------------------------------------------------------------------- a ruined portal
	private Action portal() {
		Vec3 middle = Vec3.atBottomCenterOf(at);
		if (c.storage.busy() && c.storage.looting()) {
			Action st = c.storage.next();
			if (st != null || c.storage.busy()) return st;
		}
		if (c.storage.spotLoot()) {
			doing = "looting the ruined portal's chest";
			return c.storage.next();
		}
		if (c.player.position().distanceTo(middle) > 5) {
			doing = "going to the ruined portal";
			Action a = c.walkTo(middle);
			if (a == null) end("no way there");
			return a;
		}
		end("looked it over");
		return null;
	}

	String describe() {
		return at == null ? "" : "You are visiting the " + kind + " at " + at.getX() + " " + at.getZ() + " (" + doing + ").";
	}
}
