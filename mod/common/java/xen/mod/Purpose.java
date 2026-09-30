package xen.mod;

import xen.mod.core.Mind;
import xen.mod.core.Sins;
import xen.mod.core.Strategy;

/**
 * Why a Xen does what it does, in layers, like a person: its role (its job in the village, or the kind of player its
 * plan makes it), its purpose (what that role is for), its current goal (what the plan says is next) and the action
 * (what its hands are doing). A village leader's own way of playing sets the group's priorities: a cautious one wants
 * food, beds and walls first; an ambitious one wants iron and new land; a builder wants houses. Members lean that way
 * as much as they go along with the leader (loyalty, trust); one whose own plan pulls hard the other way mostly
 * doesn't, and says so now and then.
 */
final class Purpose {
	enum Style {
		CAUTIOUS("cautious", "play it safe: food, beds and walls first"),
		AMBITIOUS("ambitious", "push on: iron, diamonds, new land"),
		BUILDER("builder", "build: good houses, a real village");

		final String word, says;

		Style(String word, String says) {
			this.word = word;
			this.says = says;
		}
	}

	/** Candidates after stone tools (see Goals#afterStone): the index of each, and its words. */
	static final int IRON = 0, FOOD = 1, BED = 2, HOME = 3, EXPLORE = 4, CHOICES = 5;
	static final String[] CHOICE = {"iron", "food", "a bed", "a home", "exploring"};
	/** What each leader style wants of those, in order. */
	private static final float[][] STYLE_CHOICE = {
			{-0.05f, 0.25f, 0.3f, 0.1f, -0.2f},          // cautious
			{0.3f, -0.05f, -0.1f, -0.1f, 0.2f},          // ambitious
			{0f, 0.1f, 0.05f, 0.35f, -0.1f},             // builder
	};

	private final Companion c;
	/** The goal it's on now, in words (for its thoughts and when asked). */
	String goal = "";
	private long disagreedAt = -1_000_000;

	Purpose(Companion c) {
		this.c = c;
	}

	/** Its role: its job in its village, or else the kind of player its plan makes it. */
	String role() {
		Tribe t = c.tribe();
		String job = t == null ? null : t.laws.jobOf(c);
		if (job != null) return job + " of " + t.name;
		int plan = c.personality.plan;
		return plan < 0 ? "newcomer" : switch (plan) {
			case Strategy.SPEEDRUN -> "speedrunner";
			case Strategy.BUILDER -> "builder";
			case Strategy.SETTLER -> "settler";
			case Strategy.MINER -> "miner";
			case Strategy.EXPLORER -> "explorer";
			case Strategy.TRADER -> "trader";
			case Strategy.WARRIOR -> "warrior";
			default -> "survivor";
		};
	}

	/** What its role is for. */
	String purpose() {
		int plan = c.personality.plan;
		return plan < 0 ? "find my feet in this world" : Strategy.WORDS[plan];
	}

	/** Role, purpose, goal: the lot, in its words. */
	String describe() {
		Style s = groupStyle();
		String lead = s == null ? "" : " My village's way: " + s.says + (follows() < 0.45f ? " (I go my own way, mostly)." : ".");
		return "I'm a " + role() + ". What for: " + purpose() + (goal.isEmpty() ? "" : " Right now: " + goal + ".") + lead;
	}

	/** A leader's way of playing, from its plan and its nature. */
	static Style style(Companion leader) {
		Personality p = leader.personality;
		int plan = p.plan;
		if (plan == Strategy.BUILDER || plan == Strategy.SETTLER && p.sin(Sins.PRIDE) > 0.5f) return Style.BUILDER;
		if (plan == Strategy.SURVIVOR || plan == Strategy.SETTLER) return Style.CAUTIOUS;
		if (plan == Strategy.SPEEDRUN || plan == Strategy.MINER || plan == Strategy.EXPLORER || plan == Strategy.WARRIOR) {
			return p.cautionScale() > 1.35f ? Style.CAUTIOUS : Style.AMBITIOUS;
		}
		return p.cautionScale() > 1.1f ? Style.CAUTIOUS : p.sin(Sins.PRIDE) > 0.55f ? Style.BUILDER : Style.AMBITIOUS;   // (trader, no plan yet)
	}

	/** Its village leader's style, or null (no village, or it leads itself). */
	Style groupStyle() {
		Tribe t = c.tribe();
		Companion leader = t == null ? null : t.laws.leader();
		return leader == null || leader.player == null ? null : style(leader);
	}

	private Companion leader() {
		Tribe t = c.tribe();
		return t == null ? null : t.laws.leader();
	}

	/**
	 * How much it goes along with its leader: its loyalty and its trust in them, less when its own plan pulls the other
	 * way (a speedrunner under a cautious leader, a survivor under an ambitious one). 1 for the leader itself.
	 */
	float follows() {
		Companion leader = leader();
		if (leader == null) return 0;
		if (leader == c) return 1;
		Personality p = c.personality;
		float f = 0.25f + 0.5f * p.loyalty + 0.35f * Math.max(0, c.trust(leader.player.getUUID()));
		if (clashes(style(leader), p.plan)) f *= 0.35f + 0.5f * p.loyalty;
		if (p.loner) f *= 0.5f;
		return Math.max(0, Math.min(1, f));
	}

	/** Its own plan against the group's way. */
	static boolean clashes(Style s, int plan) {
		return switch (s) {
			case CAUTIOUS -> plan == Strategy.SPEEDRUN || plan == Strategy.WARRIOR || plan == Strategy.EXPLORER;
			case AMBITIOUS -> plan == Strategy.SURVIVOR || plan == Strategy.SETTLER;
			case BUILDER -> plan == Strategy.SPEEDRUN || plan == Strategy.EXPLORER;
		};
	}

	/** The group's priorities as a lean on its mind's choices, as much as it goes along. */
	float[] lean() {
		float[] b = new float[Mind.N];
		Style s = groupStyle();
		if (s == null) return b;
		float w = follows();
		switch (s) {
			case CAUTIOUS -> {
				b[Mind.SHELTER] += 0.2f;
				b[Mind.SLEEP] += 0.1f;
				b[Mind.FOOD] += 0.15f;
				b[Mind.GUARD] += 0.1f;
				b[Mind.EXPLORE] -= 0.15f;
				b[Mind.ADVENTURE] -= 0.2f;
			}
			case AMBITIOUS -> {
				b[Mind.MINE] += 0.2f;
				b[Mind.EXPLORE] += 0.15f;
				b[Mind.ADVENTURE] += 0.2f;
				b[Mind.REST] -= 0.1f;
			}
			case BUILDER -> {
				b[Mind.HOUSE] += 0.25f;
				b[Mind.WOOD] += 0.15f;
				b[Mind.STONE] += 0.1f;
				b[Mind.FARM] += 0.1f;
			}
		}
		for (int i = 0; i < b.length; i++) b[i] *= w;
		return b;
	}

	/** The group's lean on the choices after stone tools, as much as it goes along. */
	float[] choiceLean() {
		float[] b = new float[CHOICES];
		Style s = groupStyle();
		if (s == null) return b;
		float w = follows();
		for (int i = 0; i < CHOICES; i++) b[i] = STYLE_CHOICE[s.ordinal()][i] * w;
		return b;
	}

	/** What the group would pick (the style's favourite of the choices open), or -1. */
	int groupPick(boolean[] open) {
		Style s = groupStyle();
		if (s == null) return -1;
		int best = -1;
		for (int i = 0; i < CHOICES; i++) if (open[i] && (best < 0 || STYLE_CHOICE[s.ordinal()][i] > STYLE_CHOICE[s.ordinal()][best])) best = i;
		return best;
	}

	/** It chose something other than what its village wants: it may say so (not often). */
	void disagree(int mine, int theirs, long now) {
		Companion leader = leader();
		if (leader == null || leader == c || theirs < 0 || mine == theirs || now - disagreedAt < 24000) return;
		disagreedAt = now;
		Style s = style(leader);
		c.journal("thinks", leader.name + " wants us to " + s.says + "; it goes for " + CHOICE[mine] + " instead");
		if (c.random().nextFloat() < 0.5f) {
			c.chatter(c.pick3(leader.name + " wants " + CHOICE[theirs] + " first. I need " + CHOICE[mine] + ".",
					"I know, I know, " + CHOICE[theirs] + " first. But " + CHOICE[mine] + " can't wait.",
					"Not everyone plays it " + leader.name + "'s way. " + cap(CHOICE[mine]) + " for me."), false);
		}
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
