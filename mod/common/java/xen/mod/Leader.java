package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A team's leader (the "leaders" setting: Cid,Bob): about once a minute it looks over its whole team (food, health, its
 * basic kit, diamond armor, enchanting), picks the team's plan, and gives each one a job that fits where they are. Each
 * teammate weighs it (its loyalty, its trust in the leader, how much it goes its own way) and takes it or says no; one
 * taken is a strong choice in its brain, not a command: a roof at night or its life still come first.
 */
final class Leader {
	private final XenMod mod;
	private final Random random = new Random();
	private final java.util.Map<String, Long> nextRound = new java.util.HashMap<>();
	private final java.util.Map<String, String> lastPlan = new java.util.HashMap<>();
	private final java.util.Map<String, Long> nextTalk = new java.util.HashMap<>();
	/** What each plan has paid each team (race points per round, averaged): the leaders learn which plans work. */
	private final java.util.Map<String, Double> worth = new java.util.HashMap<>();
	private final java.util.Map<String, Integer> raceAt = new java.util.HashMap<>();
	private final java.util.Map<String, Long> planSince = new java.util.HashMap<>();

	Leader(XenMod mod) {
		this.mod = mod;
	}

	private final java.util.Map<String, Long> ideaAt = new java.util.HashMap<>();

	/** Its team's leader (null: none, or not about). */
	Companion leaderOf(Companion c) {
		if (mod.config.leaders == null || mod.config.leaders.isBlank()) return null;
		for (String name : mod.config.leaders.split("[, ]+"))
			for (Companion x : mod.companions)
				if (x.name.equalsIgnoreCase(name) && x.player() != null && x.player().isAlive() && (x == c || mod.sameSide(x, c))) return x;
		return null;
	}

	/**
	 * Something happened to a team (a death, hunger): its leader thinks of a plan of its own (the bigger model), at
	 * most once in three minutes a team; what it says becomes jobs its teammates take or not.
	 */
	void event(Companion about, String what, net.minecraft.server.level.ServerPlayer enemy) {
		Companion lead = leaderOf(about);
		if (lead == null) return;
		long now = lead.player().level().getGameTime();
		if (now < ideaAt.getOrDefault(lead.name, 0L)) return;
		ideaAt.put(lead.name, now + 20 * 60 * 3);
		java.util.List<Companion> team = new java.util.ArrayList<>();
		for (Companion x : mod.companions) if (x.player() != null && (x == lead || mod.sameSide(lead, x))) team.add(x);
		mod.thinker.leaderIdea(lead, what, team, enemy);
	}

	void tick() {
		if (mod.config.leaders == null || mod.config.leaders.isBlank()) return;
		for (String name : mod.config.leaders.split("[, ]+")) {
			Companion lead = null;
			for (Companion c : mod.companions) if (c.name.equalsIgnoreCase(name)) lead = c;
			if (lead == null || lead.player() == null || !lead.player().isAlive()) continue;
			long now = lead.player().level().getGameTime();
			if (now < nextRound.getOrDefault(lead.name, 0L)) continue;
			nextRound.put(lead.name, now + 20 * 60);
			int starving = 0;
			for (Companion x : mod.companions) if (x.player() != null && (x == lead || mod.sameSide(lead, x)) && x.player().getFoodData().getFoodLevel() <= 6) starving++;
			if (starving >= 3) event(lead, starving + " of the team are starving and there are no animals near", null);   // (an idea for that: a farm, the village, fishing...)
			try {
				lead(lead, now);
			} catch (RuntimeException e) {
				XenMod.LOG.warn("Xen leader {} stumbled: {}", lead.name, e.toString());
			}
		}
	}

	private static int diamondArmor(Companion c) {
		int n = 0;
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
			if (BuiltInRegistries.ITEM.getKey(c.player().getItemBySlot(s).getItem()).getPath().startsWith("diamond_")) n++;
		return n;
	}

	private static boolean allEnchanted(Companion c) {
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
			if (!c.player().getItemBySlot(s).isEnchanted()) return false;
		return true;
	}

	private void lead(Companion lead, long now) {
		String team = mod.teamOf(lead);
		if (team == null) return;
		List<Companion> members = new ArrayList<>();
		for (Companion c : mod.companions) if (c.player() != null && c.player().isAlive() && team.equals(mod.teamOf(c))) members.add(c);
		int n = members.size(), hungry = 0, hurt = 0, noKit = 0, noArmor = 0, unenchanted = 0;
		for (Companion c : members) {
			int meals = c.items().getOrDefault("food", 0);
			if (meals < 2) hungry++;
			if (c.player().getHealth() < 10) hurt++;
			if (c.btb.kitMissing() > 0) noKit++;
			int dia = diamondArmor(c);
			if (dia < 4) noArmor++;
			else if (!allEnchanted(c)) unenchanted++;
		}
		boolean fortStarted = false;
		for (Companion c : members) fortStarted |= c.builder.fortress && c.builder.busy();
		// the race so far: how much the team gained under the plan it was on (what it learns which plans pay)
		int race = 0;
		for (Companion c : members) race += c.btb.raceScore();
		String was = lastPlan.get(lead.name);
		if (was != null && raceAt.containsKey(lead.name)) {
			String key = team + "|" + was.split(":")[0];
			double gain = race - raceAt.get(lead.name);
			worth.put(key, worth.getOrDefault(key, 0.0) * 0.8 + gain * 0.2);
		}
		raceAt.put(lead.name, race);
		// every plan it could choose, each scored: what the team needs, its leader's nature, what each has paid so far
		var p0 = lead.personality;
		double geared = (n - noArmor) / (double) Math.max(1, n);
		BlockPos village = lead.structures.found.get("village");
		if (village == null) village = lead.places.get("village");
		java.util.Map<String, Double> score = new java.util.LinkedHashMap<>();
		score.put("regroup: food and rest first", 2.0 + 5.0 * (hungry + hurt) / Math.max(1, n));
		score.put("kit up: everyone's basic kit", 1.5 + 4.0 * noKit / Math.max(1, n));
		score.put("diamonds: full diamond armor for all", 1.5 + 4.0 * noArmor / Math.max(1, n));
		score.put("enchant: our diamond gear enchanted", 1.0 + 4.0 * unenchanted / Math.max(1, n));
		score.put("fortress: a walled base for the team", 0.5 + 2.5 * geared + 1.5 * p0.power + 0.8 * p0.bravery);
		if (village != null) score.put("village: settle a village, build our houses in it", 0.5 + 2.5 * geared + 1.5 * p0.kindness + 0.5 * p0.curiosity);
		score.put("farms: farms and a supply chest at our base", 0.5 + 2.0 * hungry / Math.max(1, n) + 1.0 * geared + 1.2 * p0.diligence);
		String plan = null;
		double best = -1e9;
		StringBuilder why = new StringBuilder();
		for (var e : score.entrySet()) {
			double v = e.getValue() + 2.0 * worth.getOrDefault(team + "|" + e.getKey().split(":")[0], 0.0) + random.nextGaussian() * 0.15
					+ (e.getKey().equals(was) && now - planSince.getOrDefault(lead.name, 0L) < 20 * 60 * 3 ? 1.0 : 0);   // (it sticks with a plan a few minutes)
			why.append(why.length() > 0 ? ", " : "").append(e.getKey().split(":")[0]).append(String.format(Locale.ROOT, " %.1f", v));
			if (v > best) {
				best = v;
				plan = e.getKey();
			}
		}
		if (plan.startsWith("village") && village != null && (lead.goals.home == null || lead.goals.home.distSqr(village) > 48 * 48)) {
			lead.goals.home = village;                                            // (its team's base: in the village now)
			XenMod.LOG.info("{} (leads {}): our base is the village at {} now", lead.name, team.replace("xen_", ""), village.toShortString());
		}
		boolean changed = !plan.equals(lastPlan.put(lead.name, plan));
		if (changed) {
			planSince.put(lead.name, now);
			XenMod.LOG.info("{} (leads {}) weighs its plans: {}", lead.name, team.replace("xen_", ""), why);
		}
		StringBuilder jobs = new StringBuilder();
		int took = 0, refused = 0;
		if (plan.startsWith("fortress") && !fortStarted && diamondArmor(lead) >= 4 && !lead.enchanter.possible()) lead.btb.order(Btb.Task.FORT, lead.name, 1.2f);   // (the leader lays it out itself)
		int builders = 0;
		for (Companion c : members) {
			if (c == lead) continue;
			Btb.Task job = jobFor(c, plan);
			if ((job == Btb.Task.FORT || job == Btb.Task.HOME || job == Btb.Task.FARM) && ++builders > 3) job = jobFor(c, "diamonds");   // (three on the team's work; the rest go on with the race)
			if (job == null) continue;
			var p = c.personality;
			float trust = c.trust(lead.player().getUUID());
			float yes = Math.max(0.15f, Math.min(0.95f, 0.45f + 0.35f * p.loyalty + 0.3f * (trust - 0.5f) - 0.3f * (p.power - 0.3f)));
			boolean agrees = random.nextFloat() < yes;
			jobs.append(jobs.length() > 0 ? ", " : "").append(c.name).append(' ').append(job.name().toLowerCase(Locale.ROOT)).append(agrees ? "" : " (says no)");
			if (agrees) {
				c.btb.order(job, lead.name, 0.6f + 0.6f * p.loyalty);
				took++;
			} else {
				refused++;
				c.journal("thinks", lead.name + " wants it on " + job.name().toLowerCase(Locale.ROOT) + ": it goes its own way this time");
			}
		}
		if (changed || refused > 0 || took > 0)
			XenMod.LOG.info("{} (leads {}): plan \"{}\"{}; {} of {} take their jobs: {}", lead.name, team.replace("xen_", ""), plan, changed ? " (new plan)" : "",
					took, took + refused, jobs);
		if (changed) lead.journal("does", "as leader: the team's plan is " + plan);
		share(lead, members, team);
		if (now >= nextTalk.getOrDefault(lead.name, 0L)) {                       // its team's laws and everyone's role, in its own words (the language model), every ten minutes
			nextTalk.put(lead.name, now + 20 * 60 * 10);
			mod.thinker.teamTalk(lead, members);
		}
	}

	/** Diamonds it still needs for its armor (what it isn't wearing yet) and a diamond pickaxe and sword. */
	private static int diamondsFor(Companion c) {
		int need = 0;
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		int[] cost = {5, 8, 7, 4};
		for (int i = 0; i < 4; i++)
			if (!BuiltInRegistries.ITEM.getKey(c.player().getItemBySlot(slots[i]).getItem()).getPath().startsWith("diamond_")) need += cost[i];
		if (c.btb.invCount("diamond_pickaxe") == 0) need += 3;
		if (c.btb.invCount("diamond_sword") == 0) need += 2;
		return need;
	}

	/**
	 * Its team's goods where they're needed (one hand-over a round): spare diamonds to the one closest to full armor,
	 * and the makings of a book (a book, paper, sugar cane, leather) to whoever has the obsidian for the team's table.
	 * Within a team nobody haggles: what's spare (by its worth to the one holding it) goes where it's needed.
	 */
	private void share(Companion lead, List<Companion> members, String team) {
		Companion needy = null;
		int short_ = Integer.MAX_VALUE;
		for (Companion m : members) {
			int want = diamondsFor(m) - m.btb.invCount("diamond");
			if (want > 0 && want < short_) {
				short_ = want;
				needy = m;
			}
		}
		if (needy != null) {
			Companion giver = null;
			int spareBest = 0;
			for (Companion m : members) {
				if (m == needy || m.fightingNow() || m.chores.kind == Chores.Kind.GIVE || m.player().distanceTo(needy.player()) > 96) continue;
				int spare = m.btb.invCount("diamond") - Math.max(0, diamondsFor(m)) - 2;   // (two kept back: the team's enchanting table)
				if (spare > spareBest) {
					spareBest = spare;
					giver = m;
				}
			}
			if (giver != null) {
				int n = Math.min(spareBest, short_);
				String r = giver.chores.give(needy.player(), "diamond", n);
				if (r != null && r.startsWith("You will")) {
					XenMod.LOG.info("{} (leads {}): team share: {} gives {} diamonds to {} ({} short of full armor)", lead.name, team.replace("xen_", ""), giver.name, n, needy.name, short_);
					return;
				}
			}
		}
		for (Companion m : members) {                                          // diamonds in its bag and nothing to make armor on: a teammate's logs (Ann carried 27 diamonds round a treeless savanna)
			if (m.btb.invCount("diamond") < 5 || m.btb.invCount("crafting_table") > 0 || m.btb.invCount("*_log") >= 2 || m.btb.invCount("*_planks") >= 4) continue;
			for (Companion g : members) {
				if (g == m || g.btb.invCount("*_log") < 8 || g.fightingNow() || g.chores.kind == Chores.Kind.GIVE || g.player().distanceTo(m.player()) > 96) continue;
				String r = g.chores.give(m.player(), "log", 4);
				if (r != null && r.startsWith("You will")) {
					XenMod.LOG.info("{} (leads {}): team share: {} gives 4 logs to {} (diamonds and nothing to craft armor on)", lead.name, team.replace("xen_", ""), g.name, m.name);
					return;
				}
			}
		}
		if (members.stream().anyMatch(m -> m.enchanter.table() != null)) return;   // (the team has a table: no book needed)
		Companion maker = null;
		for (Companion m : members) if (m.btb.invCount("obsidian") >= 4 && m.btb.invCount("book") == 0) maker = m;
		if (maker == null) return;
		for (String item : new String[] {"book", "paper", "sugar_cane", "leather"}) {
			int min = item.equals("book") ? 1 : item.equals("leather") ? 1 : 3;
			if (maker.btb.invCount(item) >= min) continue;
			for (Companion m : members) {
				if (m == maker || m.btb.invCount(item) < min || m.fightingNow() || m.chores.kind == Chores.Kind.GIVE || m.player().distanceTo(maker.player()) > 96) continue;
				String r = m.chores.give(maker.player(), item, min);
				if (r != null && r.startsWith("You will")) {
					XenMod.LOG.info("{} (leads {}): team share: {} gives {} {} to {} (for the team's enchanting table)", lead.name, team.replace("xen_", ""), m.name, min, item, maker.name);
					return;
				}
			}
		}
	}

	/** The job for this one: what it lacks most, or the team's plan. */
	private static Btb.Task jobFor(Companion c, String plan) {
		int meals = c.items().getOrDefault("food", 0);
		if (meals < 2) return Btb.Task.FOOD;
		if (c.btb.kitMissing() > 0) return Btb.Task.KIT;
		int dia = diamondArmor(c);
		boolean free = dia >= 4 && (allEnchanted(c) || !c.enchanter.possible());   // (in diamond and nothing to enchant with yet: free for the team's work)
		if (plan.startsWith("fortress") && free) return Btb.Task.FORT;
		if (plan.startsWith("village") && free) return Btb.Task.HOME;
		if (plan.startsWith("farms") && (free || dia >= 2)) return c.farmer.middle == null ? Btb.Task.FARM : Btb.Task.STASH;
		if (dia < 4 && c.crafter.pickTier() >= 3) return Btb.Task.DIAMONDS;
		if (dia < 4) return Btb.Task.IRON;
		if (!allEnchanted(c)) return Btb.Task.ENCHANT;
		return plan.startsWith("hold") ? Btb.Task.STASH : null;
	}
}
