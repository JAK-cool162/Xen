package xen.mod;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Locale;
import java.util.Random;

/**
 * What each Xen is good at, like players: one is a great fighter and a poor farmer, another builds beautifully and
 * can't hold a sword. Every Xen is born with its own levels (from its nature, and a talent of its own), 0 to 1.
 * <p>Doing a thing teaches a little. <b>Training</b> teaches much more, and a Xen only trains when it decides to
 * (it lost a fight, it heard someone is stronger, it's ambitious) or when someone asks it ("Pip, train your
 * fighting"). Fighting is trained by sparring, and a sparring partner has to agree: it's the other Xen's call too.
 * <p>What the levels change: how well it fights (the timing of crits, shields, spacing) and which fighting gear it can
 * use well (cobwebs, pearls, potions); the water clutch comes naturally to an agile one; a skilled builder designs
 * bigger, richer houses; and each Xen leans toward the work it's good at.
 */
final class Skills {
	static final String[] NAMES = {"fighting", "building", "mining", "farming", "trading", "moving"};
	static final int FIGHT = 0, BUILD = 1, MINE = 2, FARM = 3, TRADE = 4, MOVE = 5;
	private static final String[] WORDS = {"fight|fighting|pvp|combat|sword|sparring|spar", "build|building", "mine|mining", "farm|farming",
			"trade|trading|bargain", "move|moving|parkour|movement|clutch|clutches"};

	private final Companion c;
	private final Random random = new Random();
	final float[] level = new float[NAMES.length];
	/** What it's training now (-1: nothing), until when, and its sparring partner. */
	int training = -1;
	long trainingUntil;
	Companion partner;
	/** The last partner, and when the spar ended (a hit or two after that is still part of it). */
	private java.util.UUID lastPartner;
	private long sparEnded;
	private long nextThought;
	private int shadow;

	Skills(Companion c) {
		this.c = c;
		for (int i = 0; i < level.length; i++) level[i] = 0.3f;
	}

	/** A new Xen: levels from its nature, and one talent. */
	void born() {
		var p = c.personality;
		for (int i = 0; i < level.length; i++) level[i] = 0.1f + 0.3f * random.nextFloat();
		level[FIGHT] += 0.3f * p.power + 0.15f * p.bravery;
		level[BUILD] += 0.2f * p.diligence;
		level[MINE] += 0.2f * p.diligence;
		level[FARM] += 0.2f * p.kindness;
		level[TRADE] += 0.3f * p.money;
		level[MOVE] += 0.2f * p.curiosity;
		level[random.nextInt(level.length)] += 0.3f;                          // its talent
		for (int i = 0; i < level.length; i++) level[i] = Math.max(0.05f, Math.min(0.9f, level[i]));
	}

	float get(int skill) {
		return level[skill];
	}

	/** Doing it teaches a little, training much more (the more it knows, the slower it gets better). */
	void practice(int skill, float amount) {
		if (c.inArena) return;
		float gain = amount * (training == skill ? 1f : 0.15f) * (1 - level[skill]) * (0.6f + 0.8f * c.personality.diligence);
		float before = level[skill];
		level[skill] = Math.min(1f, level[skill] + gain);
		for (float mark : new float[] {0.25f, 0.5f, 0.75f, 0.9f}) {
			if (before < mark && level[skill] >= mark) {
				c.chatter(c.pick3("I'm getting better at " + NAMES[skill] + "!", "Practice pays off: my " + NAMES[skill] + " is improving.",
						"Nice, I feel I'm getting good at " + NAMES[skill] + "."), true);
				c.journal("does", String.format(Locale.ROOT, "%s skill up to %.2f", NAMES[skill], level[skill]));
			}
		}
	}

	/** Which skill these words are about ("train your fighting", "practice pvp"); -1 if none. */
	static int named(String words) {
		for (int i = 0; i < WORDS.length; i++) if (java.util.regex.Pattern.compile("\\b(" + WORDS[i] + ")\\b").matcher(words).find()) return i;
		return -1;
	}

	private static final java.util.regex.Pattern ASK = java.util.regex.Pattern.compile("\\b(train|practi[cs]e|get better at|improve|work on)\\b");

	/** Asked to train ("Pip, train your fighting", "practice mining"): its answer, or null if that wasn't the question. */
	String asked(ServerPlayer from, String words) {
		String w = words.toLowerCase(Locale.ROOT);
		if (!ASK.matcher(w).find()) return null;
		int s = named(w);
		if (s < 0) return "Train what? Fighting, building, mining, farming, trading or moving?";
		if (c.trust(from.getUUID()) < 0.1f && !from.getUUID().equals(c.owner)) return "Why would I train because you say so?";
		return start(s, from.getName().getString());
	}

	/** Begin training (five minutes); what it will do. */
	String start(int skill, String why) {
		training = skill;
		trainingUntil = now() + 20 * 60 * 5;
		partner = null;
		c.journal("does", "trains " + NAMES[skill] + " (" + why + ")");
		String plan = switch (skill) {
			case FIGHT -> "sparring with someone, or practising my swings";
			case BUILD -> "building something";
			case MINE -> "some mining";
			case FARM -> "working on a farm";
			case TRADE -> "trading with villagers";
			default -> "running, jumping and clutches";
		};
		switch (skill) {                                                     // the ones that are chores: the chore, done as practice
			case MINE -> c.chores.gather("mine", 16);
			case FARM -> c.farmer.start();
			case BUILD -> {
				if (!c.builder.busy()) c.builder.start("pen");
			}
			case TRADE -> c.trader.withVillager(null);
			default -> {}
		}
		return String.format(Locale.ROOT, "Okay, I'll train my %s: %s. (I'm at %.0f%% now.)", NAMES[skill], plan, 100 * level[skill]);
	}

	boolean trainingNow() {
		return training >= 0 && now() < trainingUntil;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/**
	 * Now and then: does it want to train? A Xen out for power trains its fighting after losing, or after hearing of
	 * someone stronger; a diligent one works on what it's worst at, when it has nothing better to do.
	 */
	void think(boolean lostFight, boolean heardStronger) {
		if (c.player == null || c.inArena || trainingNow() || now() < nextThought) return;
		nextThought = now() + 20 * 60 * 3;
		var p = c.personality;
		if ((lostFight || heardStronger) && p.power > 0.5f && random.nextFloat() < 0.5f + p.power * 0.4f) {
			c.chatter(c.pick3("I need to get stronger.", "Next time I'll be ready. Time to train.", "I'm not strong enough yet. Training!"), true);
			start(FIGHT, lostFight ? "lost a fight" : "heard of someone stronger");
		}
	}

	/** Its training, each decision while it has nothing else to do: sparring or shadow-boxing, running drills. Null: nothing now. */
	Action step() {
		if (!trainingNow()) {
			if (training >= 0) {
				if (partner != null) endSpar(false);
				c.chatter(String.format(Locale.ROOT, "Training done. My %s is at %.0f%%.", NAMES[training], 100 * level[training]), true);
				training = -1;
			}
			return null;
		}
		if (training == FIGHT) return fightDrill();
		if (training == MOVE) return moveDrill();
		return null;                                                         // (the others are its chores)
	}

	/** Find a sparring partner (it has to agree), or practise alone: swings, jump crits, strafing. */
	private Action fightDrill() {
		var p = c.player;
		if (partner == null && shadow % 200 == 0) {
			for (Companion o : c.mod.companions) {
				if (o == c || o.player == null || o.player.level() != p.level() || o.player.distanceTo(p) > 24 || o.skills.partner != null) continue;
				if (!o.skills.agreesToSpar(c)) continue;
				partner = o;
				o.skills.partner = c;
				o.skills.training = FIGHT;
				o.skills.trainingUntil = trainingUntil;
				c.say(c.pick3(o.name + ", want to spar? Just until one of us is hurt.", "Hey " + o.name + ", practice fight?", o.name + ", spar with me?"));
				o.say(o.pick3("Sure, let's go!", "You're on.", "Okay, don't cry when you lose."));
				c.chosenFoe = o.player;
				o.chosenFoe = p;
				break;
			}
		}
		shadow++;
		if (partner != null) {
			c.goals.instant = "sparring with " + partner.name;
			c.chosenFoe = partner.player;
			return null;                                                     // (its fighting takes it from here)
		}
		c.goals.instant = "practising its swings";
		Vec3 ahead = p.getEyePosition().add(p.getLookAngle().scale(3));
		c.hands.face(ahead);
		int beat = shadow % 12;
		if (beat == 0 && p.onGround()) return Action.JUMP;
		if (beat == 6 && p.getAttackStrengthScale(0.5f) > 0.9f) {
			Compat.swing(p);                                                 // a swing at the air (a crit's timing, on the way down)
			p.resetAttackStrengthTicker();
			practice(FIGHT, 0.004f);
			c.acted = true;
			return Action.IDLE;
		}
		return beat < 6 ? Action.LEFT : Action.RIGHT;
	}

	/** Every tick while sparring: it stops when one of them is hurt (half health), or the other is gone. */
	void checkSpar() {
		if (partner == null) return;
		var p = c.player;
		if (p == null || partner.player == null || partner.player.isRemoved() || p.getHealth() < 10 || partner.player.getHealth() < 10
				|| !trainingNow()) endSpar(true);
	}

	/** Is this its sparring partner (its hits are part of the game, not an attack)? */
	boolean sparringWith(java.util.UUID who) {
		if (partner != null && partner.player != null && partner.player.getUUID().equals(who)) return true;
		return who != null && who.equals(lastPartner) && c.player != null && now() - sparEnded < 100;
	}

	boolean agreesToSpar(Companion asker) {
		var p = c.personality;
		if (c.player == null || c.builder.busy() || c.chores.busy() || c.fightingNow() || c.player.getHealth() < 16) return false;
		return c.trust(asker.player.getUUID()) >= 0.2f && (p.power > 0.4f || p.kindness > 0.6f || p.bravery > 0.6f) && random.nextFloat() < 0.8f;
	}

	/** The spar is over: no hard feelings (a good one brings them closer). */
	void endSpar(boolean saySomething) {
		Companion o = partner;
		partner = null;
		c.chosenFoe = null;
		if (o == null) return;
		o.skills.partner = null;
		o.chosenFoe = null;
		if (c.player == null || o.player == null) return;
		lastPartner = o.player.getUUID();                                      // (no grudge: the last hits were part of it)
		o.skills.lastPartner = c.player.getUUID();
		sparEnded = now();
		o.skills.sparEnded = o.skills.now();
		c.player.setLastHurtByMob(null);
		o.player.setLastHurtByMob(null);
		c.fighter.reset();
		o.fighter.reset();
		boolean won = c.player.getHealth() >= o.player.getHealth();
		if (saySomething) {
			c.say(won ? c.pick3("Good fight, " + o.name + "!", "Got you that time!", "Nice try. Again sometime?")
					: c.pick3("You got me. Good fight.", "Okay okay, you win this one.", "Ow. You're good, " + o.name + "."));
		}
		c.trust(o.player.getUUID(), 0.05f);
		o.trust(c.player.getUUID(), 0.05f);
		practice(FIGHT, 0.04f);
		o.skills.practice(FIGHT, 0.04f);
	}

	/** Running drills: sprint-jumps back and forth (and a clutch, if it has a water bucket and a ledge). */
	private Action moveDrill() {
		var p = c.player;
		c.goals.instant = "practising parkour";
		shadow++;
		if (shadow % 40 == 0) c.hands.face(p.getEyePosition().add(p.getLookAngle().scale(-4)));   // turn around
		p.setSprinting(p.getFoodData().getFoodLevel() > 6);
		if (p.onGround() && shadow % 8 == 0) {
			practice(MOVE, 0.003f);
			return Action.JUMP;
		}
		return Action.FORWARD;
	}

	/** Its levels in words, for its notes and /xen status. */
	String describe() {
		int best = 0, worst = 0;
		for (int i = 1; i < level.length; i++) {
			if (level[i] > level[best]) best = i;
			if (level[i] < level[worst]) worst = i;
		}
		StringBuilder sb = new StringBuilder("Skills:");
		for (int i = 0; i < level.length; i++) sb.append(String.format(Locale.ROOT, " %s %.0f%%", NAMES[i], 100 * level[i]));
		sb.append(". You're best at ").append(NAMES[best]).append(" and worst at ").append(NAMES[worst]).append('.');
		if (trainingNow()) sb.append(" You're training ").append(NAMES[training]).append(partner != null ? " with " + partner.name : "").append('.');
		return sb.toString();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		for (int i = 0; i < level.length; i++) o.addProperty(NAMES[i], level[i]);
		return o;
	}

	void load(JsonObject o) {
		for (int i = 0; i < level.length; i++) if (o.has(NAMES[i])) level[i] = o.get(NAMES[i]).getAsFloat();
	}
}
