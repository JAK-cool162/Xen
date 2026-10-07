package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Mind;

import java.util.Set;

/**
 * The little things that make a Xen play like a person on a server, not a bot with a plan:
 * <ul>
  *   <li><b>A first look around</b>: new in the world (or back from death), it walks off a little way, the safest-looking way, eyes on everything, and then decides.</li>
 *   <li><b>Routines</b>: it notices what it tends to do at each time of day (mining in the morning, building in the
 *   afternoon...) and leans that way again: habits, its own patterns.</li>
 *   <li><b>Boredom</b>: too long at the same kind of work (how long depends on its patience) and it gets sick of it:
 *   "Enough chopping for now." It drops it and won't pick it again for a few minutes.</li>
 *   <li><b>Frustration</b>: dying stings. It grumbles, plays it safer for a while, and after dying twice in a short
 *   time it takes a breather before going on.</li>
 *   <li><b>Curiosity</b>: on its way somewhere and it spots something new (a village, a temple, a portal...): a
 *   curious one takes a short detour to look, then gets back to what it was doing.</li>
 *   <li><b>A full bag</b>: it tosses the junk (rotten flesh, too much dirt and gravel, seeds...), keeping a little;
 *   a greedy one keeps everything.</li>
 * </ul>
 */
final class Habits {
	private final Companion c;
	/** Parts of the day: morning, afternoon, evening, night. */
	static final int SLOTS = 4;
	/** What it started at each part of the day, counted (fades a little each time, so habits can change). */
	final float[][] done = new float[SLOTS][Mind.N];
	/** The kind of work it's been at, since when; what it's sick of, until when. */
	private int workingAt = -1;
	private long workingSince;
	private final long[] boredUntil = new long[Mind.N];
	/**
	 * New in the world (or back from dying): it sets off a little way, the way that looks safest (no water, lava or
	 * drops, something worth seeing), its eyes on everything on the way, and only then decides what to do. True: it
	 * still has to pick the way; where it's headed, until when, how far it goes, and what it saw.
	 */
	private boolean scoutPending;
	private BlockPos scoutTo;
	private long scoutUntil;
	private int scoutReach = 14;
	private final java.util.LinkedHashSet<String> scoutSaw = new java.util.LinkedHashSet<>();
	/** A glance: where its head turns (an offset from where it walks), toward what, until when; the next one. */
	private float headYaw, headPitch;
	private Vec3 glanceAt;
	private long glanceUntil, nextGlance;
	/** Frustration after dying (0 to 1, fades), deaths lately, a breather until when. */
	float frustration;
	private long lastDeath = -1_000_000, breatherUntil;
	private int deathsLately;
	/** Something new it spotted: a short look, until when; what it was. */
	private BlockPos detour;
	private String detourWhat = "";
	private long detourUntil, lastDetour = -1_000_000;
	private long bagCheckedAt;

	Habits(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	static int slot(long timeOfDay) {
		return (int) (Math.floorMod(timeOfDay, 24000L) / 6000L);
	}

	private static final String[] SLOT_NAMES = {"mornings", "afternoons", "evenings", "nights"};

	// ------------------------------------------------------------------------------ routines and boredom
	/** It started this (its mind chose it): counted for its routine at this time of day. */
	void started(int option) {
		if (c.player == null || option < 0) return;
		int s = slot(Compat.timeOfDay(c.player.level()));
		for (int i = 0; i < Mind.N; i++) done[s][i] *= 0.97f;                      // (older habits fade)
		done[s][option] += 1f;
		if (option != workingAt) {
			workingAt = option;
			workingSince = now();
		}
	}

	/** Its leanings from habit (what it usually does now) and boredom (what it's sick of): added to what its mind values. */
	void bias(float[] b) {
		if (c.player == null) return;
		long now = now();
		int s = slot(Compat.timeOfDay(c.player.level()));
		float total = 0;
		for (float v : done[s]) total += v;
		if (total >= 4) {                                                          // enough days to have a routine
			for (int i = 0; i < Mind.N; i++) b[i] += 0.3f * done[s][i] / total * c.personality.patience();
		}
		for (int i = 0; i < Mind.N; i++) if (now < boredUntil[i]) b[i] -= 0.8f;
		if (frustration > 0.2f) {                                                  // stung: safer things for a while
			b[Mind.FIGHT] -= 0.4f * frustration;
			b[Mind.EXPLORE] -= 0.3f * frustration;
			b[Mind.MINE] -= 0.15f * frustration;
		}
	}

	/** Sick of it? True when it's been at the same kind of work too long (then it drops it). */
	private boolean bored() {
		int o = c.goals.option;
		if (o < 0 || o != workingAt) return false;
		if (o == Mind.REST || o == Mind.SLEEP || o == Mind.SHELTER || o == Mind.FIGHT || o == Mind.FLEE || o == Mind.EAT || o == Mind.HELP) return false;
		float patience = c.personality.patience() * (1f - 0.5f * c.personality.sin(xen.mod.core.Sins.SLOTH));
		long limit = (long) (20 * 60 * (5 + 10 * patience));                        // 5 to 15 minutes of the same thing
		return now() - workingSince > limit && !c.whims.resists();                // (sick of it, unless it has to be done and it has the will)
	}

	/** What it usually does at this time of day, in words (for its status), or "". */
	String routine() {
		if (c.player == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int s = 0; s < SLOTS; s++) {
			int best = -1;
			float total = 0;
			for (int i = 0; i < Mind.N; i++) {
				total += done[s][i];
				if (best < 0 || done[s][i] > done[s][best]) best = i;
			}
			if (total < 4 || best < 0 || done[s][best] / total < 0.3f) continue;
			sb.append(sb.length() > 0 ? ", " : "").append(SLOT_NAMES[s]).append(": ").append(Mind.SAYS[best]);
		}
		return sb.length() == 0 ? "" : "Your routine: " + sb + ".";
	}

	// ------------------------------------------------------------------------------ deaths
	void died() {
		if (c.player == null) return;
		long now = now();
		deathsLately = now - lastDeath < 20 * 60 * 10 ? deathsLately + 1 : 1;
		lastDeath = now;
		frustration = Math.min(1f, frustration + 0.5f);
	}

	/** Back in the world after dying: it grumbles, has a look around, and after two quick deaths, a breather. */
	void respawned() {
		scout(10);
		if (c.player == null) return;
		if (deathsLately >= 2) {
			breatherUntil = now() + 20 * 45;
			c.chatter(c.pick3("Okay. I need a minute.", "Twice?! I'm taking a break.", "This is not my day..."), true);
		} else {
			c.chatter(c.pick3("Ugh! All my stuff...", "That was so dumb of me.", "Well, that went badly."), true);
		}
	}

	/** New in the world: a little walk and a look around first. */
	void arrived() {
		scout(10 + (int) (Math.random() * 7));
	}

	private void scout(int reach) {
		scoutPending = true;
		scoutReach = reach;
		scoutTo = null;
		scoutSaw.clear();
		c.walker.stroll = false;
	}

	/** Having its first look around (it doesn't pick a goal till it's done). */
	boolean scouting() {
		return scoutPending || scoutTo != null;
	}

	/**
	 * The way to go: eight directions weighed by what's along them (water, lava, drops and steep climbs count against;
	 * animals, trees and bare stone at the end for it), roughly ahead preferred, a little luck. Null: nowhere good
	 * (under the ground, nothing loaded).
	 */
	private BlockPos pickScout() {
		var p = c.player;
		var level = p.level();
		BlockPos at = p.blockPosition();
		if (!level.canSeeSky(at.above()) || level.isDarkOutside() || p.isInWater() || !p.onGround()) return null;   // (under a roof, at night, swimming: no stroll, it decides at once)
		var random = c.random();
		BlockPos best = null;
		double bestScore = -1e9;
		float face = p.getYRot();
		for (int k = 0; k < 8; k++) {
			double ang = Math.toRadians(face + k * 45 + (random.nextFloat() - 0.5f) * 30);
			double dx = -Math.sin(ang), dz = Math.cos(ang);
			double risk = 0;
			int lastY = at.getY();
			BlockPos end = null;
			for (int d = 2; d <= scoutReach; d += 2) {
				int x = at.getX() + (int) Math.round(dx * d), z = at.getZ() + (int) Math.round(dz * d);
				if (!level.hasChunkAt(new BlockPos(x, at.getY(), z))) {
					risk += 5;
					break;
				}
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				if (Math.abs(top - lastY) > 12) {                                     // (a wall of rock, or the world not ready there: not that way)
					risk += 5;
					break;
				}
				BlockPos ground = new BlockPos(x, top - 1, z);
				var fluid = level.getFluidState(ground);
				if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) risk += 10;
				else if (!fluid.isEmpty()) risk += 1.2;
				if (lastY - top > 3) risk += 2 + 0.3 * (lastY - top);
				else if (top - lastY > 2) risk += 0.6;
				lastY = top;
				end = ground.above();
			}
			if (end == null) continue;
			double lure = 0.25 * Math.min(4, level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
					new net.minecraft.world.phys.AABB(end).inflate(12), a -> a.isAlive()).size());
			for (int t = 0; t < 6; t++) {
				BlockPos q = end.offset(random.nextInt(17) - 8, 0, random.nextInt(17) - 8);
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, q.getX(), q.getZ());
				String what = name(level.getBlockState(new BlockPos(q.getX(), top - 1, q.getZ())).getBlock());
				if (what.endsWith("_leaves") || what.endsWith("_log")) lure += 0.12;
				else if (what.equals("stone") || what.equals("andesite") || what.equals("granite") || what.equals("diorite")) lure += 0.1;
			}
			double score = lure - risk + (k == 0 || k == 1 || k == 7 ? 0.2 : 0) + 0.3 * random.nextFloat();
			if (score > bestScore) {
				bestScore = score;
				best = end;
			}
		}
		return bestScore < -3 ? null : best;
	}

	private static String name(net.minecraft.world.level.block.Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	/**
	 * Every few ticks while it has its first look: its eyes go to something (an animal, a tree, bare stone, water, a
	 * person), or over its shoulder now and then. Its head turns (up to 70 degrees either way), its feet keep going.
	 */
	private void glance(long now) {
		var p = c.player;
		if (now >= glanceUntil) glanceAt = null;
		if (glanceAt == null && now >= nextGlance) {
			var random = c.random();
			nextGlance = now + 14 + random.nextInt(18);
			glanceUntil = now + 8 + random.nextInt(8);
			if (random.nextFloat() < 0.15f) {                                              // a look over its shoulder
				double back = Math.toRadians(p.getYRot() + (random.nextBoolean() ? 115 : -115));
				glanceAt = p.getEyePosition().add(-Math.sin(back) * 8, -1, Math.cos(back) * 8);
				return;
			}
			var level = p.level();
			java.util.List<Vec3> things = new java.util.ArrayList<>();
			java.util.List<String> kinds = new java.util.ArrayList<>();
			for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(24), x -> x.isAlive() && x != p
					&& !(Tactics.enderman(x) && c.knowledge.knows("endermen")))) {   // (not an enderman's eyes)
				if (!p.hasLineOfSight(e)) continue;
				things.add(e.getEyePosition());
				kinds.add(e instanceof net.minecraft.world.entity.player.Player ? "someone" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' '));
			}
			BlockPos at = p.blockPosition();
			for (int t = 0; t < 8; t++) {
				BlockPos q = at.offset(random.nextInt(33) - 16, 0, random.nextInt(33) - 16);
				if (!level.hasChunkAt(q)) continue;
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, q.getX(), q.getZ());
				BlockPos b = new BlockPos(q.getX(), top - 1, q.getZ());
				String what = name(level.getBlockState(b).getBlock());
				String kind = what.endsWith("_leaves") || what.endsWith("_log") ? "trees" : what.equals("water") ? "water"
						: what.equals("stone") || what.equals("andesite") || what.equals("granite") || what.equals("diorite") || what.equals("deepslate") ? "stone"
						: what.equals("sand") ? "sand" : what.equals("lava") ? "lava" : null;
				if (kind == null) continue;
				things.add(Vec3.atCenterOf(b));
				kinds.add(kind);
			}
			if (things.isEmpty()) return;
			int i = random.nextInt(things.size());
			glanceAt = things.get(i);
			scoutSaw.add(kinds.get(i));
		}
	}

	/** Its head where its eyes are (after its feet have had their say: the way it walks stays the same). */
	private void turnHead() {
		var p = c.player;
		float wantYaw = 0, wantPitch = p.getXRot();
		if (glanceAt != null) {
			Vec3 d = glanceAt.subtract(p.getEyePosition());
			float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
			wantYaw = Math.max(-70, Math.min(70, net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot())));
			wantPitch = (float) Math.max(-40, Math.min(40, -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))));
		}
		headYaw += (wantYaw - headYaw) * 0.35f;
		if (Math.abs(headYaw) < 0.5f && glanceAt == null) headYaw = 0;
		if (headYaw != 0) p.setYHeadRot(p.getYRot() + headYaw);
		if (glanceAt != null) {
			headPitch = p.getXRot() + (wantPitch - p.getXRot()) * 0.35f;
			p.setXRot(headPitch);
		}
	}

	// ------------------------------------------------------------------------------ curiosity
	/** It spotted something new (a village, a temple, a portal...): maybe a look. */
	void spotted(BlockPos at, String what) {
		if (c.player == null || c.inArena || c.mode != Companion.Mode.FREE) return;
		long now = now();
		if (now - lastDetour < 20 * 60 * 3 || c.fightingNow() || c.player.level().isDarkOutside()) return;
		if (c.random().nextFloat() > 0.3f + 0.6f * c.personality.curiosity) return;
		if (at.distSqr(c.player.blockPosition()) > 48 * 48) return;
		detour = at.immutable();
		detourWhat = what;
		detourUntil = now + 20 * 25;
		lastDetour = now;
		c.chatter(c.pick3("Ooh, what's that?", "Wait, is that a " + what + "?", "Hold on, I want to see that."), false);
	}

	// ------------------------------------------------------------------------------ each tick and decision
	void tick() {
		if (c.player == null) return;
		if (c.player.tickCount % 100 == 0 && !c.inArena && now() - bagCheckedAt > 100) {   // a full bag: Q on the junk, whatever it's doing
			bagCheckedAt = now();
			tossJunk();
		}
		if (frustration > 0) frustration = Math.max(0, frustration - 1f / (20 * 60 * 10));   // gone in about ten minutes
		if (scoutTo != null && !c.hands.busy() && !c.fightingNow()) glance(now());   // its first look around: eyes on everything
		else glanceAt = null;
		if (glanceAt != null || headYaw != 0) turnHead();
	}

	/** What its habits want this moment (a look around, a detour, a breather, boredom, a full bag), or null. */
	Action next() {
		var p = c.player;
		if (p == null || c.inArena) return null;
		long now = now();
		if (scoutPending && !c.fightingNow()) {                                       // new here: which way looks good?
			scoutPending = false;
			scoutTo = c.mode == Companion.Mode.FREE && c.mod.config.wants ? pickScout() : null;
			if (scoutTo != null) {
				scoutUntil = now + 20 * 15;
				nextGlance = now + 4;
				c.journal("does", "sets off for a look around, " + (int) Math.sqrt(scoutTo.distSqr(p.blockPosition())) + " blocks the safest-looking way");
			}
		}
		if (scoutTo != null) {
			if (now > scoutUntil || c.fightingNow() || c.mode != Companion.Mode.FREE || p.blockPosition().closerThan(scoutTo, 2.5)) {
				c.journal("thinks", "had a look around" + (scoutSaw.isEmpty() ? "" : ": " + String.join(", ", scoutSaw)) + "; now to decide");
				scoutTo = null;
				c.walker.stroll = false;
				c.goals.thinkNow();                                                      // (and it decides now)
				return null;
			}
			c.goals.instant = "having a look around";
			c.walker.stroll = true;
			c.acted = true;
			return c.walkTo(Vec3.atBottomCenterOf(scoutTo));
		}
		if (now < breatherUntil && !c.fightingNow() && c.mode == Companion.Mode.FREE) {
			c.goals.instant = "taking a breather";
			return Action.IDLE;
		}
		if (detour != null) {
			if (now > detourUntil || c.fightingNow() || p.blockPosition().closerThan(detour, 8)) {
				if (p.blockPosition().closerThan(detour, 8)) {
					c.chatter(c.pick3("Huh. A " + detourWhat + ".", "Neat. Okay, back to work.", "Cool. Remember that spot."), false);
					c.journal("does", "went to look at a " + detourWhat);
				}
				detour = null;
				return null;
			}
			c.goals.instant = "taking a look at a " + detourWhat;
			return c.walkTo(Vec3.atBottomCenterOf(detour));
		}
		if (c.mode == Companion.Mode.FREE && bored()) {
			int o = c.goals.option;
			boredUntil[o] = now + 20 * 60 * 4;
			workingAt = -1;
			c.journal("does", "got bored of " + Mind.SAYS[o]);
			c.chatter(boredLine(o), false);
			c.goals.finishOption(false);
			c.chores.cancel();
			return null;
		}
		return null;
	}

	private String boredLine(int o) {
		String what = switch (o) {
			case Mind.WOOD -> "chopping";
			case Mind.STONE, Mind.MINE -> "digging";
			case Mind.HOUSE -> "building";
			case Mind.FARM -> "farming";
			case Mind.EXPLORE -> "walking around";
			case Mind.FOOD -> "hunting";
			default -> "this";
		};
		return c.pick3("Enough " + what + " for now.", "Ugh, I'm so bored of " + what + ".", "Okay, something else. " + Character.toUpperCase(what.charAt(0)) + what.substring(1) + " is boring.");
	}

	// ------------------------------------------------------------------------------ a full bag
	// (a player's way: the extra stone and dirt go; sticks never do, nor logs: it keeps a dozen or more for when it needs them)
	private static final Set<String> JUNK = Set.of("rotten_flesh", "poisonous_potato", "spider_eye", "wheat_seeds", "beetroot_seeds", "gravel",
			"dirt", "andesite", "diorite", "granite", "tuff", "cobbled_deepslate", "cobblestone", "netherrack", "sand", "red_sand", "flint",
			"bone", "string", "feather");
	private static final java.util.Map<String, Integer> KEEP = java.util.Map.ofEntries(java.util.Map.entry("dirt", 32),
			java.util.Map.entry("cobbled_deepslate", 32), java.util.Map.entry("cobblestone", 64), java.util.Map.entry("netherrack", 32),
			java.util.Map.entry("sand", 16), java.util.Map.entry("flint", 4), java.util.Map.entry("bone", 8), java.util.Map.entry("string", 8),
			java.util.Map.entry("feather", 8), java.util.Map.entry("wheat_seeds", 8), java.util.Map.entry("gravel", 0));

	/** No room left in its bag: the junk goes (a stack at a time, like pressing Q), keeping a little. */
	private void tossJunk() {
		var inv = c.player.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) return;             // room left: nothing to do
		if (c.personality.sin(xen.mod.core.Sins.GREED) > 0.7f) return;                    // (a hoarder keeps it all)
		java.util.Map<String, Integer> have = new java.util.HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			have.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
		}
		for (int i = 35; i >= 0; i--) {
			ItemStack s = inv.getItem(i);
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (!JUNK.contains(id)) continue;
			int keep = KEEP.getOrDefault(id, 0);
			if (have.get(id) - s.getCount() < keep) continue;                               // (what's left after this stack is still enough)
			ItemStack out = inv.removeItemNoUpdate(i);
			c.hands.toss(out);
			c.journal("does", "tossed " + out.getCount() + " " + id.replace('_', ' ') + " (bag full)");
			c.chatter(c.pick3("Bag's full. Bye, " + id.replace('_', ' ') + ".", "Out with the junk.", "Too much stuff. Tossing some."), false);
			return;
		}
	}

	// ------------------------------------------------------------------------------ kept with the world
	com.google.gson.JsonObject toJson() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
		for (float[] s : done) {
			com.google.gson.JsonArray a = new com.google.gson.JsonArray();
			for (float v : s) a.add(Math.round(v * 100) / 100f);
			slots.add(a);
		}
		o.add("routine", slots);
		return o;
	}

	void load(com.google.gson.JsonObject o) {
		if (!o.has("routine")) return;
		var slots = o.getAsJsonArray("routine");
		for (int s = 0; s < Math.min(SLOTS, slots.size()); s++) {
			var a = slots.get(s).getAsJsonArray();
			for (int i = 0; i < Math.min(Mind.N, a.size()); i++) done[s][i] = a.get(i).getAsFloat();   // (older worlds: fewer choices, the rest start at 0)
		}
	}
}
