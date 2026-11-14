package xen.mod;

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

	Leader(XenMod mod) {
		this.mod = mod;
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
		String plan = hungry + hurt > n / 2 ? "regroup: food and rest first"
				: noKit > n / 3 ? "kit up: everyone's basic kit"
				: noArmor > n / 3 ? "diamonds: full diamond armor for all"
				: unenchanted > 0 ? "enchant: our diamond gear enchanted"
				: "hold: guard the base, help the slow ones";
		boolean changed = !plan.equals(lastPlan.put(lead.name, plan));
		StringBuilder jobs = new StringBuilder();
		int took = 0, refused = 0;
		for (Companion c : members) {
			if (c == lead) continue;
			Btb.Task job = jobFor(c, plan);
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
	}

	/** The job for this one: what it lacks most, or the team's plan. */
	private static Btb.Task jobFor(Companion c, String plan) {
		int meals = c.items().getOrDefault("food", 0);
		if (meals < 2) return Btb.Task.FOOD;
		if (c.btb.kitMissing() > 0) return Btb.Task.KIT;
		int dia = diamondArmor(c);
		if (dia < 4 && c.crafter.pickTier() >= 3) return Btb.Task.DIAMONDS;
		if (dia < 4) return Btb.Task.IRON;
		if (!allEnchanted(c)) return Btb.Task.ENCHANT;
		return plan.startsWith("hold") ? Btb.Task.STASH : null;
	}
}
