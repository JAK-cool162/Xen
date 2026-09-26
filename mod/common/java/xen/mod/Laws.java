package xen.mod;

import net.minecraft.server.level.ServerPlayer;
import xen.mod.core.Mind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * A village's own rules, jobs and punishments, made up by its Xens (or proposed by a player and voted on). Nothing
 * here is a command: the leader proposes what fits it (a kind one wants food shared, a power-hungry one taxes, a loyal
 * one bans fighting at home), every member votes by its own motives and how much it trusts whoever proposed it, and
 * what passes, they live by. Everyone gets a job that fits them (woodcutter, miner, farmer, builder, guard, trader),
 * which their choices lean toward. Breaking a rule gets a warning, then a fine (in the village's money), then the
 * village turns its back on them (exile for a band; the others stop trusting them).
 */
final class Laws {
	enum Rule {
		NO_FIGHTING("no fighting in the village", "\\b(no|don'?t|stop|ban) (fighting|pvp|hitting|attacking|killing)\\b|\\bno violence\\b"),
		SHARE_FOOD("share food with the hungry", "\\bshare (the |your )?food\\b|\\bnobody (goes|stays) hungry\\b|\\bfeed (the )?hungry\\b"),
		EVERYONE_WORKS("everyone works every day", "\\b(everyone|everybody|all) (must |has to |should )?work\\b|\\bno (lazy|slacking|idlers?)\\b"),
		CURFEW("home before dark", "\\b(curfew|home (before|by) (dark|night)|inside at night|be home at night)\\b"),
		TAX("a tax for the village", "\\b(tax|taxes|pay (the |a )?(village|leader|chief))\\b"),
		NO_STRANGERS("no strangers at night", "\\bno (strangers|outsiders|visitors)\\b|\\bkeep (out )?(strangers|outsiders)\\b");

		final String words;
		final Pattern heard;

		Rule(String words, String heard) {
			this.words = words;
			this.heard = Pattern.compile(heard);
		}
	}

	enum Job {
		WOODCUTTER("woodcutter", Mind.WOOD), MINER("miner", Mind.MINE), FARMER("farmer", Mind.FOOD), BUILDER("builder", Mind.HOUSE),
		GUARD("guard", Mind.GUARD), TRADER("trader", Mind.TRADE);

		final String word;
		final int option;

		Job(String word, int option) {
			this.word = word;
			this.option = option;
		}
	}

	private final Tribe tribe;
	private final Random random = new Random();
	final Map<Rule, String> rules = new LinkedHashMap<>();                   // the rule, and who made it
	final Map<Companion, Job> jobs = new HashMap<>();
	private final Map<String, Integer> strikes = new HashMap<>();
	/** How much each member worked today (its work choices), for "everyone works". */
	final Map<Companion, Integer> worked = new HashMap<>();
	private long nextThought, day = -1;

	Laws(Tribe tribe) {
		this.tribe = tribe;
	}

	Companion leader() {
		Companion best = null;
		for (Companion m : tribe.members) {
			if (m.player == null || m.minion) continue;
			float score = m.personality.power + 0.5f * m.personality.loyalty + 0.3f * m.personality.chattiness + 0.1f * m.personality.generation;
			if (best == null || score > best.personality.power + 0.5f * best.personality.loyalty + 0.3f * best.personality.chattiness
					+ 0.1f * best.personality.generation) best = m;
		}
		return best;
	}

	/** Would this member vote for the rule (by its motives, and how much it trusts whoever proposes it)? */
	private boolean votes(Companion m, Rule r, java.util.UUID by) {
		var p = m.personality;
		float want = switch (r) {
			case NO_FIGHTING -> p.loyalty + (p.aggressive() ? -0.6f : 0.2f) + (p.passive() ? 0.3f : 0);
			case SHARE_FOOD -> p.kindness + 0.3f * p.loyalty - 0.3f * p.money;
			case EVERYONE_WORKS -> p.diligence + 0.3f * p.power - 0.2f;
			case CURFEW -> 1.0f - p.bravery + 0.2f * p.loyalty;
			case TAX -> p.power + p.loyalty * 0.4f - p.money * 0.6f;
			case NO_STRANGERS -> p.loyalty + (p.aggressive() ? 0.3f : 0) - p.kindness * 0.4f + 0.2f;
		};
		if (by != null) want += 0.4f * m.trust(by);
		return want + random.nextFloat() * 0.2f > 0.7f;
	}

	/** Someone proposes a rule: the tribe votes. What they say about it (one of them), or null if it's not a rule. */
	String propose(String words, ServerPlayer by, String byName) {
		String w = words.toLowerCase(Locale.ROOT);
		boolean asRule = w.matches("(?s).*\\b(new rule|a rule|the rule|rule:|let'?s make a rule|from now on|village rule|law)\\b.*");
		if (!asRule) return null;
		Rule rule = null;
		for (Rule r : Rule.values()) if (r.heard.matcher(w).find()) rule = r;
		if (rule == null) return "What rule, exactly? (no fighting, share food, everyone works, curfew, tax, no strangers)";
		if (rules.containsKey(rule)) return "That's already a rule here: " + rule.words + ".";
		return vote(rule, by == null ? null : by.getUUID(), byName);
	}

	private String vote(Rule rule, java.util.UUID by, String byName) {
		int yes = 0, no = 0;
		for (Companion m : tribe.members) {
			if (m.player == null) continue;
			if (votes(m, rule, by)) yes++;
			else no++;
		}
		boolean passes = yes > no;
		if (passes) rules.put(rule, byName);
		String result = String.format(Locale.ROOT, "Vote on \"%s\" (%s's idea): %d for, %d against. %s", rule.words, byName, yes, no,
				passes ? "It's a rule now!" : "It doesn't pass.");
		XenMod.LOG.info("{}: {}", tribe.name, result);
		return result;
	}

	/** Every so often: the leader may propose a rule it likes, jobs are handed out, rules are kept (and enforced). */
	void tick(long now) {
		if (tribe.members.size() < 3 || now < nextThought) return;
		nextThought = now + 20 * 60 * 3;
		Companion leader = leader();
		if (leader == null) return;
		long today = now / 24000;
		if (today != day) {                                                      // a new day: jobs, the tax, who worked
			if (day >= 0) newDay(leader);
			day = today;
			assignJobs(leader);
		}
		if (rules.size() < 4 && random.nextFloat() < 0.3f) {                    // the leader thinks of a rule
			Rule idea = leaderIdea(leader);
			if (idea != null && !rules.containsKey(idea)) leader.say("New rule idea: " + idea.words + ". " + vote(idea, leader.player.getUUID(), leader.name));
		}
		if (rules.containsKey(Rule.CURFEW) && leader.player.level().isDarkOutside()) {
			for (Companion m : tribe.members) {
				if (m == leader || m.player == null || tribe.center == null || tribe.inLand(m.player.blockPosition())) continue;
				if (m.goals.home != null && m.player.distanceTo(leader.player) < 64) offense(m, "out after dark");
			}
		}
	}

	private Rule leaderIdea(Companion l) {
		var p = l.personality;
		float r = random.nextFloat();
		if (p.kindness > 0.6f && r < 0.5f) return Rule.SHARE_FOOD;
		if (p.power > 0.6f && r < 0.4f) return random.nextBoolean() ? Rule.TAX : Rule.EVERYONE_WORKS;
		if (p.loyalty > 0.6f && r < 0.5f) return Rule.NO_FIGHTING;
		if (p.aggressive() && r < 0.4f) return Rule.NO_STRANGERS;
		if (p.bravery < 0.35f && r < 0.4f) return Rule.CURFEW;
		return r < 0.15f ? Rule.values()[random.nextInt(Rule.values().length)] : null;
	}

	/** Each member gets the job that fits it best (and says so). */
	private void assignJobs(Companion leader) {
		for (Companion m : tribe.members) {
			if (m.player == null) continue;
			var p = m.personality;
			Map<Job, Float> fit = new LinkedHashMap<>();
			fit.put(Job.WOODCUTTER, p.diligence * 0.6f + 0.2f);
			fit.put(Job.MINER, p.bravery * 0.8f + (m.crafter.pickTier() >= 2 ? 0.2f : -0.3f));
			fit.put(Job.FARMER, p.kindness * 0.6f + (m.farmer.middle != null ? 0.3f : 0));
			fit.put(Job.BUILDER, p.diligence * 0.4f + (p.build.equals("fort") || p.build.equals("tower") ? 0.3f : 0.1f));
			fit.put(Job.GUARD, p.power * 0.6f + p.bravery * 0.3f + (p.aggressive() ? 0.2f : 0));
			fit.put(Job.TRADER, p.money * 0.7f + p.chattiness * 0.3f);
			Job best = null;
			for (var e : fit.entrySet()) if (best == null || e.getValue() + random.nextFloat() * 0.1f > fit.get(best)) best = e.getKey();
			Job had = jobs.put(m, best);
			if (had != best && best != null) {
				m.journal("does", "gets the job of " + best.word + " in " + tribe.name);
				if (m == leader) m.chatter("I'll be the " + best.word + " around here.", false);
				else if (random.nextFloat() < 0.3f) leader.chatter(m.name + ", you're our " + best.word + ".", false);
			}
		}
	}

	/** What its job and the rules make it lean toward (added to what Xen 2.0 thinks of each choice). */
	float[] bias(Companion c) {
		float[] b = new float[Mind.N];
		Job j = jobs.get(c);
		if (j != null) b[j.option] += 0.35f;
		if (rules.containsKey(Rule.EVERYONE_WORKS)) b[Mind.REST] -= 0.3f;
		if (rules.containsKey(Rule.CURFEW) && c.goals.evening()) b[Mind.SHELTER] += 0.5f;
		if (rules.containsKey(Rule.SHARE_FOOD)) b[Mind.HELP] += 0.3f;
		if (rules.containsKey(Rule.NO_FIGHTING) && c.tribe() == tribe) b[Mind.FIGHT] -= 0.2f;
		return b;
	}

	/** Work it did (a choice that brings something in): for "everyone works". */
	void didWork(Companion c, int option) {
		if (option == Mind.WOOD || option == Mind.STONE || option == Mind.MINE || option == Mind.FOOD || option == Mind.HOUSE || option == Mind.FARM
				|| option == Mind.TRADE || option == Mind.GUARD || option == Mind.SMELT) worked.merge(c, 1, Integer::sum);
	}

	private void newDay(Companion leader) {
		if (rules.containsKey(Rule.EVERYONE_WORKS)) {
			for (Companion m : tribe.members) if (m.player != null && m != leader && worked.getOrDefault(m, 0) == 0) offense(m, "didn't work yesterday");
		}
		worked.clear();
		if (rules.containsKey(Rule.TAX) && tribe.market.currency != null) {        // the tax: one of the village's money each
			String money = tribe.market.currency;
			for (Companion m : tribe.members) {
				if (m == leader || m.player == null || m.player.distanceTo(leader.player) > 32) continue;
				if (m.items().getOrDefault(money, 0) > 0 && m.personality.loyalty + m.trust(leader.player.getUUID()) > 0.6f) {
					m.chores.give(leader.player, money, 1);
					m.chatter("Here's my tax, " + leader.name + ".", false);
				} else if (m.items().getOrDefault(money, 0) > 0) {
					offense(m, "didn't pay the tax");
				}
			}
		}
	}

	/** A rule broken: a warning, then a fine, then the village turns its back on them. */
	void offense(Companion m, String what) {
		Companion leader = leader();
		if (leader == null || leader == m || m.player == null) return;
		int n = strikes.merge(m.name, 1, Integer::sum);
		m.journal("does", "broke a rule of " + tribe.name + ": " + what + " (" + n + ")");
		if (n == 1) {
			leader.say(m.name + ", that's against our rules (" + what + "). Don't do it again.");
			m.trust(leader.player.getUUID(), -0.02f);
		} else if (n == 2) {
			String money = tribe.market.currency;
			if (money != null && m.items().getOrDefault(money, 0) > 0) {
				m.chores.give(leader.player, money, Math.min(3, m.items().getOrDefault(money, 0)));
				leader.say(m.name + ", you pay a fine this time (" + what + ").");
			} else {
				leader.say(m.name + ", last warning (" + what + ").");
			}
		} else {
			leader.say(m.name + " broke our rules too often. We don't want you here any more.");
			for (Companion o : tribe.members) if (o != m && o.player != null) o.trust(m.player.getUUID(), -0.3f);
			if (m.band != null && m.band.startsWith("band:")) m.band = "solo:" + m.name;   // (a band can send someone away)
			strikes.remove(m.name);
		}
	}

	/** A member hit another member: against the rules? */
	void hit(Companion attacker, Companion victim) {
		if (rules.containsKey(Rule.NO_FIGHTING) && tribe.members.contains(attacker) && tribe.members.contains(victim)) offense(attacker, "fighting in the village");
	}

	List<String> describe() {
		List<String> out = new ArrayList<>();
		for (var e : rules.entrySet()) out.add(e.getKey().words + " (" + e.getValue() + "'s idea)");
		return out;
	}

	String jobOf(Companion c) {
		Job j = jobs.get(c);
		return j == null ? null : j.word;
	}
}
