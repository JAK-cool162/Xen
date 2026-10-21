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
	private long until, nextThink, nextScan, progressAt;
	private int progressStuff;
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

	/** Something it just saw is worth a visit: it thinks about one at once, not in a few seconds. */
	void soon() {
		nextThink = 0;
	}

	/** Has it been to that place (or is it there now)? */
	boolean wasThere(BlockPos p) {
		return visited.contains(p) || at != null && at.equals(p);
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
		if (now > until && now - progressAt < 20 * 60 * 2) until = now + 20 * 60;   // (still getting somewhere there: on with it)
		if (now > until || c.mode != Companion.Mode.FREE || c.inArena || c.player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			end("nothing more coming of it");
			return null;
		}
		int stuff = c.items().values().stream().mapToInt(Integer::intValue).sum() + been.size();
		if (stuff != progressStuff) {
			progressStuff = stuff;
			progressAt = now;
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
		wantsVillager = c.goals.home != null || c.personality.plan == xen.mod.core.Strategy.SETTLER || c.personality.plan == xen.mod.core.Strategy.BUILDER
				|| c.mod.config.runner;                                          // (a runner: one in a pit, to trade with on the way)
		at = best;
		kind = bestKind;
		until = now() + 20 * 60 * 4;                                         // (four minutes, then: as long as it's getting somewhere)
		progressAt = now();
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
		Action v = wantsVillager && c.mod.config.runner ? pitTrap(level) : null;   // (a runner: the quick way players trap one)
		if (v == null && wantsVillager && pitFor == null) v = boxVillager(level);
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

	/** A villager pit: the villager it's for, the top block of the hole, since when, and when the workstation went in. */
	private UUID pitFor;
	private BlockPos pit;
	private long pitSince, pitSetAt;
	private static final Set<String> STATIONS = Set.of("barrel", "composter", "smoker", "blast_furnace", "fletching_table", "cartography_table",
			"smithing_table", "stonecutter", "loom", "lectern", "grindstone", "brewing_stand", "cauldron");

	/**
	 * A villager with no job yet, trapped the way players do it: a hole three deep near it and a workstation at the
	 * bottom (a barrel, from planks). The villager comes for the job, drops in, and can't climb out of two deep: one to
	 * trade with, from the top. Null when there's no such villager (or no wood for the barrel), or it's done.
	 */
	private Action pitTrap(ServerLevel level) {
		var p = c.player;
		Villager v = pitFor == null ? null : level.getEntity(pitFor) instanceof Villager known && known.isAlive() ? known : null;
		if (v == null) {
			pitFor = null;
			pit = null;
			pitSetAt = 0;
			double best = 16;
			for (Villager e : level.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(16), x -> x.isAlive() && !x.isBaby() && !x.isSleeping() && jobless(x))) {
				double d = p.distanceTo(e);
				if (d < best && WorldSenses.sees(p, c.hands.yaw, c.hands.pitch, e)) {
					best = d;
					v = e;
				}
			}
			if (v == null) return null;
			if (station() == null && MindSense.count(c, n -> n.endsWith("_planks")) + 4 * c.items().getOrDefault("log", 0) < 9) return null;   // (no wood for a barrel: a box instead)
			pitFor = v.getUUID();
			pitSince = now();
		}
		if (now() - pitSince > 20 * 60 * 3 || pitSetAt > 0 && now() - pitSetAt > 20 * 90) {   // (it won't come: not this one)
			c.journal("does", "gives up on the villager pit: the villager didn't come for the job");
			wantsVillager = false;
			pitFor = null;
			return null;
		}
		doing = "trapping a villager in a pit (to trade with)";
		String station = station();
		if (station == null) {                                               // a barrel: two slabs (three planks make six) and six planks
			if (c.crafter.hasOrder()) return Action.IDLE;
			String slab = null;
			for (var e : c.items().keySet()) if (e.endsWith("_slab")) slab = e;
			if (slab == null) {
				String wood = null;
				for (var e : c.items().keySet()) if (e.endsWith("_planks")) wood = e;
				c.crafter.orderRecipe(wood != null ? wood.replace("_planks", "_slab") : "oak_slab", 1);
			} else c.crafter.orderRecipe("barrel", 1);
			if (!c.crafter.hasOrder()) {                                       // (it can't: a box instead)
				pitFor = null;
				return null;
			}
			return Action.IDLE;
		}
		if (pit == null) {
			pit = pitSpot(level, v);
			if (pit == null) {
				pitFor = null;
				return null;
			}
			c.journal("does", "digs a villager pit at " + pit.toShortString() + ": three deep, a " + station.replace('_', ' ') + " at the bottom (the villager comes for the job and drops in)");
		}
		BlockPos bottom = pit.below(2);
		if (v.blockPosition().equals(pit.below()) || v.blockPosition().equals(bottom) && !STATIONS.contains(id(level, bottom))) {   // in: two deep, it can't get out
			villagersBoxed++;
			wantsVillager = false;
			pitFor = null;
			c.places.rememberAnother("villager", v.blockPosition(), 8);
			c.journal("does", "trapped a villager in its pit at " + pit.toShortString() + ": one to trade with");
			c.chatter(c.pick3("Got one. In the hole you go, buddy.", "Villager trapped. Trades whenever I want.", "Haha, it fell right in."), false);
			return null;
		}
		if (STATIONS.contains(id(level, bottom))) {                           // the trap's set: out of the way, and wait for it
			if (pitSetAt == 0) pitSetAt = now();
			Vec3 back = Vec3.atBottomCenterOf(pit.above()).add(Vec3.atCenterOf(pit).subtract(v.position()).normalize().scale(4));
			c.hands.face(Vec3.atCenterOf(pit));
			return p.position().distanceTo(Vec3.atCenterOf(pit)) < 3 ? c.walkTo(back) : Action.IDLE;
		}
		net.minecraft.core.Direction side = null;                            // where it stands: beside the hole, not over it
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos st = pit.relative(d);
			if (level.getBlockState(st.above()).isAir() && level.getBlockState(st.above(2)).isAir() && (side == null
					|| st.distSqr(p.blockPosition().below()) < pit.relative(side).distSqr(p.blockPosition().below()))) side = d;
		}
		if (side == null) {
			pit = null;
			pitFor = null;
			return null;
		}
		BlockPos stand = pit.relative(side).above();
		if (!p.blockPosition().equals(stand)) return c.walkTo(Vec3.atBottomCenterOf(stand));
		for (BlockPos b : new BlockPos[] {pit, pit.below(), bottom}) {        // three blocks down
			if (level.getBlockState(b).isAir()) continue;
			if (c.hands.mine(b)) {
				c.acted = true;
				return Action.MINE;
			}
			return Action.IDLE;
		}
		net.minecraft.core.Direction far = side.getOpposite();               // the workstation at the bottom, against the far wall
		if (c.hands.placeItem(bottom, s -> STATIONS.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()), bottom.relative(far), side, -1)) {
			c.acted = true;
			return Action.PLACE;
		}
		if (c.hands.placeItem(bottom, s -> STATIONS.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()), bottom.below(), net.minecraft.core.Direction.UP, -1)) {
			c.acted = true;
			return Action.PLACE;
		}
		c.journal("does", "can't set its villager pit (" + c.hands.cantPlace + ")");
		pitFor = null;
		wantsVillager = false;
		return null;
	}

	private static boolean jobless(Villager v) {
		return v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("none").equals("none");
	}

	/** A workstation it carries (or null). */
	private String station() {
		for (String k : c.items().keySet()) if (STATIONS.contains(k)) return k;
		return null;
	}

	private static String id(ServerLevel level, BlockPos q) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath();
	}

	/**
	 * Where the pit goes: ground two or three blocks from the villager, on its level, solid all the way down (and
	 * round), with a floor under it and open air over it; the nearest such.
	 */
	private BlockPos pitSpot(ServerLevel level, Villager v) {
		BlockPos vf = v.blockPosition();
		BlockPos best = null;
		for (int dx = -3; dx <= 3; dx++)
			for (int dz = -3; dz <= 3; dz++) {
				int d2 = dx * dx + dz * dz;
				if (d2 < 4 || d2 > 10) continue;
				BlockPos q = vf.offset(dx, -1, dz);
				if (!level.getBlockState(q.above()).isAir() || !level.getBlockState(q.above(2)).isAir()) continue;
				boolean ok = level.getBlockState(q.below(3)).isFaceSturdy(level, q.below(3), net.minecraft.core.Direction.UP);
				for (int y = 0; y < 3 && ok; y++) {
					BlockPos b = q.below(y);
					var st = level.getBlockState(b);
					ok = !st.isAir() && st.getFluidState().isEmpty() && st.getDestroySpeed(level, b) >= 0 && st.getDestroySpeed(level, b) < 5
							&& !(st.getBlock() instanceof BedBlock) && !st.hasBlockEntity();
					for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL)   // (walls all round: no way out sideways)
						if (ok && y > 0) ok = level.getBlockState(b.relative(d)).isSolidRender() && level.getFluidState(b.relative(d)).isEmpty();
				}
				if (ok && (best == null || q.distSqr(vf) < best.distSqr(vf))) best = q.immutable();
			}
		return best;
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
