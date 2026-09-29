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
 *   <li><b>Getting its bearings</b>: new in the world (or back from death), it looks around for a few seconds first.</li>
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
	/** Getting its bearings (ticks left), and the way it faced when it started. */
	private int bearings;
	private float bearingsFrom;
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
		return now() - workingSince > limit;
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

	/** Back in the world after dying: it grumbles, gets its bearings, and after two quick deaths, a breather. */
	void respawned() {
		bearings = 50;
		if (c.player == null) return;
		if (deathsLately >= 2) {
			breatherUntil = now() + 20 * 45;
			c.chatter(c.pick3("Okay. I need a minute.", "Twice?! I'm taking a break.", "This is not my day..."), true);
		} else {
			c.chatter(c.pick3("Ugh! All my stuff...", "That was so dumb of me.", "Well, that went badly."), true);
		}
	}

	/** New in the world: a look around first. */
	void arrived() {
		bearings = 60;
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
		if (bearings > 0 && !c.hands.busy()) {                                       // a slow look around, like a player getting their bearings
			if (bearings == 60 || bearings == 50) bearingsFrom = c.player.getYRot();
			bearings--;
			float yaw = bearingsFrom + (60 - bearings) * 6f;
			c.hands.aim(yaw, bearings % 20 < 10 ? -5f : 5f);
		}
	}

	/** What its habits want this moment (a look around, a detour, a breather, boredom, a full bag), or null. */
	Action next() {
		var p = c.player;
		if (p == null || c.inArena) return null;
		long now = now();
		if (bearings > 0 && !c.fightingNow()) {
			c.goals.instant = "getting its bearings";
			c.acted = true;
			return Action.IDLE;
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
	private static final Set<String> JUNK = Set.of("rotten_flesh", "poisonous_potato", "spider_eye", "wheat_seeds", "beetroot_seeds", "gravel",
			"dirt", "andesite", "diorite", "granite", "tuff", "cobbled_deepslate", "netherrack", "flint", "stick", "bone", "string", "feather");
	private static final java.util.Map<String, Integer> KEEP = java.util.Map.of("dirt", 32, "cobbled_deepslate", 32, "netherrack", 32,
			"flint", 4, "stick", 16, "bone", 8, "string", 8, "feather", 8, "wheat_seeds", 8, "gravel", 0);

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
