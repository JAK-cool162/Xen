package xen.mod;

import com.google.gson.JsonObject;
import xen.mod.core.Beliefs;
import xen.mod.core.Mind;
import xen.mod.core.Sins;
import xen.mod.core.Strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A Xen's temperament: genes that shape how its body uses the shared brain. All Xens share one brain (what they have
 * learned), but each has its own nature: how brave, how curious, how chatty, how patient, its tone of voice, how it
 * fights and how it builds. Evolution passes the genes of the Xens that do well on to new Xens, with small mutations.
 */
public final class Personality {
	static final String[] TONES = {"cheerful", "calm", "grumpy", "shy", "bold", "silly"};
	/** How it fights: presets of its fight genes (see {@link #GENES}). */
	static final String[] FIGHTS = {"brawler", "rusher", "skirmisher", "guard", "dancer"};
	/** The shape of the shelter it builds: a hut (10 blocks), a fort with corners (18) or a tall tower (14). */
	static final String[] BUILDS = {"hut", "fort", "tower"};
	/** The blocks it likes to build with. */
	static final String[] MATERIALS = {"stone", "earth", "any"};

	/**
	 * Fight genes (each 0 to 1), the 1.9+ PvP skills a player learns: how often it jumps for critical hits; how charged
	 * its swing must be (0.85 to 1); the distance it keeps while its sword recharges (2.2 to 3 blocks); how often it
	 * S-taps after a hit so the next one is a sprint hit again; how often it jumps toward a hit to take less knockback
	 * (jump reset); strafing instead of stepping back; stepping back when the foe jumps in for a crit; waiting for the
	 * foe to swing first and punishing it (hit selecting); how hurt it gets before it backs off to heal (0 to 40%); and
	 * holding up a shield while it recharges.
	 */
	static final String[] GENES = {"crit", "charge", "spacing", "wtap", "jumpreset", "strafe", "counter", "select", "retreat", "shield"};

	/** The five fighting styles are starting points in gene space. */
	static float[] preset(String style) {
		return switch (style) {
			//                     crit charge space wtap  jump strafe count select retreat shield
			case "rusher" -> new float[] {0.85f, 0.0f, 0.2f, 0.9f, 0.7f, 0.1f, 0.1f, 0.0f, 0.0f, 0.0f};
			case "skirmisher" -> new float[] {0.3f, 0.67f, 0.9f, 0.8f, 0.4f, 0.2f, 0.6f, 0.6f, 0.75f, 0.2f};
			case "guard" -> new float[] {0.5f, 1.0f, 0.6f, 0.3f, 0.3f, 0.1f, 0.5f, 0.8f, 0.9f, 1.0f};
			case "dancer" -> new float[] {0.4f, 0.33f, 0.6f, 0.6f, 0.5f, 0.9f, 0.5f, 0.3f, 0.6f, 0.2f};
			default -> new float[] {0.6f, 0.33f, 0.5f, 0.5f, 0.5f, 0.3f, 0.3f, 0.2f, 0.0f, 0.3f};   // brawler
		};
	}

	static String how(String style) {
		return switch (style) {
			case "rusher" -> "rushes in and keeps pressing";
			case "skirmisher" -> "hits and steps back";
			case "guard" -> "holds its ground behind its shield and waits for full swings";
			case "dancer" -> "circles around its foe";
			default -> "trades blows";
		};
	}

	/** The style its fight genes are closest to (for describing it). */
	static String nearest(float[] genes) {
		String best = "brawler";
		double bestD = Double.MAX_VALUE;
		for (String style : FIGHTS) {
			float[] p = preset(style);
			double d = 0;
			for (int i = 0; i < p.length; i++) d += (genes[i] - p[i]) * (genes[i] - p[i]);
			if (d < bestD) {
				bestD = d;
				best = style;
			}
		}
		return best;
	}

	/** 0 = timid, 1 = fearless: how much fear holds it back. */
	public float bravery;
	/** How often it tries something new instead of what it knows. */
	public float curiosity;
	/** How much it talks on its own. */
	public float chattiness;
	/** How long it keeps at a chore before giving up. */
	public float diligence;
	public String tone;
	public String fight = "brawler";
	/** Its fight genes (see {@link #GENES}). */
	public float[] fightGenes = preset("brawler");
	public String build = "hut";
	public String material = "any";
	public int generation;
	public String parents = "";
	/**
	 * Hidden (it never says them, you find out from what it does): what moves it most. Kindness (helping others),
	 * loyalty (standing by its friend, its tribe, its boss), power (strength, weapons, winning, being feared), money
	 * (emeralds, diamonds, trade, owning things). Each 0 to 1; its choices weigh them.
	 */
	public float kindness = 0.5f, loyalty = 0.5f, power = 0.3f, money = 0.3f;
	/** Hidden too: how it meets others. "friendly" (greets, shares), "passive" (keeps out of trouble), "aggressive" (picks fights). */
	public String temper = "friendly";
	static final String[] TEMPERS = {"friendly", "passive", "aggressive"};
	/** Hidden too: a lone wolf never joins a team (it may still have friends). */
	public boolean loner;
	/** Xen 6.0: its seven sins (see {@link Sins}), given at birth and never changed. Null until given (an older Xen gets them from its name). */
	public float[] sins;
	/** Its beliefs (see {@link Beliefs}), given at birth with its sins, never changed. */
	public List<String> beliefs = new ArrayList<>();
	/** Its own plan (see {@link Strategy}; -1: not worked out yet). It changes its plan when it isn't working. */
	public int plan = -1;
	/**
	 * Xen 2.0: hidden stats, given at birth (an older Xen: from its name), passed on to children, never shown. Each 0 to
	 * 1 (0.5 is anyone): how quick its reflexes are, how well it keeps its head, how much it jokes, how casually it
	 * types, how soon it gets hungry, how picky a miner it is, how much it likes fishing, whether it's a night owl, and
	 * how stubborn it is once it has made up its mind.
	 */
	public float[] hidden;
	static final String[] HIDDEN = {"reflexes", "composure", "humor", "typing", "appetite", "pickiness", "angler", "night owl", "stubborn"};
	static final int REFLEXES = 0, COMPOSURE = 1, HUMOR = 2, TYPING = 3, APPETITE = 4, PICKINESS = 5, ANGLER = 6, NIGHT_OWL = 7, STUBBORN = 8;

	/** A hidden stat (0.5 for anyone without them). */
	float hidden(int i) {
		return hidden == null || i >= hidden.length ? 0.5f : hidden[i];
	}

	/** Hidden stats for a newborn: most near the middle, a few far out (like people). */
	static float[] hiddenStats(Random r) {
		float[] h = new float[HIDDEN.length];
		for (int i = 0; i < h.length; i++) h[i] = Math.max(0f, Math.min(1f, 0.5f + (float) r.nextGaussian() * 0.22f));
		return h;
	}

	/** Its hidden stats in words, for the log only ("quick reflexes, easily rattled, ..."): never said, never shown. */
	String hiddenInWords() {
		String[][] words = {{"slow reflexes", "quick reflexes"}, {"easily rattled", "keeps a cool head"}, {"serious", "a joker"},
				{"types properly", "types like on a phone"}, {"rarely hungry", "always hungry"}, {"mines anything", "a picky miner"},
				{"no patience for fishing", "loves fishing"}, {"an early bird", "a night owl"}, {"easily swayed", "stubborn"}};
		List<String> out = new ArrayList<>();
		for (int i = 0; i < HIDDEN.length; i++) {
			float v = hidden(i);
			if (v < 0.3f) out.add(words[i][0]);
			else if (v > 0.7f) out.add(words[i][1]);
		}
		return out.isEmpty() ? "nothing out of the ordinary" : String.join(", ", out);
	}
	private transient Beliefs.Knobs knobs;

	/** Its sins and beliefs, if it has none yet (a newborn; an older Xen: from its name, so it's always the same one). */
	void bornWith(Random r) {
		if (sins == null) {
			sins = Sins.random(r);
			beliefs = Beliefs.pick(sins, r, 4 + r.nextInt(3));
			knobs = null;
		}
		if (hidden == null) hidden = hiddenStats(r);                     // (after its sins: an older Xen's sins stay the ones it had)
	}

	float sin(int i) {
		return sins == null ? 0f : sins[i];
	}

	boolean believes(String id) {
		return beliefs.contains(id);
	}

	/** Its beliefs added up (fear, patience, talk, risk, sharing, hunger, nights, curiosity, trust, leanings). */
	Beliefs.Knobs knobs() {
		if (knobs == null) knobs = Beliefs.knobs(beliefs);
		return knobs;
	}

	/** What its sins, beliefs and plan lean it toward (added to what its mind thinks each choice is worth). */
	float[] bias() {
		float[] b = sins == null ? new float[Mind.N] : Sins.bias(sins), p = Strategy.bias(plan);
		Beliefs.Knobs k = knobs();
		for (int i = 0; i < Mind.N; i++) b[i] = Sins.LEAN * (b[i] + k.bias[i] + p[i]);   // (a nudge: its mind knows best what works)
		return b;
	}

	/** "proud, and a little greedy" (its sins), for its notes and its status. */
	String sinsInWords() {
		return sins == null ? "" : Sins.describe(sins);
	}

	/** What it believes, in its own words. */
	String beliefsInWords() {
		StringBuilder sb = new StringBuilder();
		for (String id : beliefs) sb.append(sb.length() == 0 ? "" : " ").append(Beliefs.words(id));
		return sb.toString();
	}

	String planInWords() {
		return plan < 0 ? "no plan yet" : Strategy.NAMES[plan] + ": " + Strategy.WORDS[plan];
	}

	boolean aggressive() {
		return temper.equals("aggressive");
	}

	boolean passive() {
		return temper.equals("passive");
	}

	static Personality random(Random r) {
		Personality p = new Personality();
		p.bravery = r.nextFloat();
		p.curiosity = r.nextFloat();
		p.chattiness = r.nextFloat();
		p.diligence = r.nextFloat();
		p.tone = TONES[r.nextInt(TONES.length)];
		p.fight = FIGHTS[r.nextInt(FIGHTS.length)];
		p.fightGenes = preset(p.fight);
		for (int i = 0; i < p.fightGenes.length; i++) p.fightGenes[i] = mutate(p.fightGenes[i], r);
		p.build = BUILDS[r.nextInt(BUILDS.length)];
		p.material = MATERIALS[r.nextInt(MATERIALS.length)];
		p.kindness = r.nextFloat();
		p.loyalty = r.nextFloat();
		p.power = r.nextFloat();
		p.money = r.nextFloat();
		float[] m = {p.kindness, p.loyalty, p.power, p.money};             // one of them stands out (what drives it)
		int top = r.nextInt(4);
		m[top] = 0.7f + 0.3f * r.nextFloat();
		p.kindness = m[0];
		p.loyalty = m[1];
		p.power = m[2];
		p.money = m[3];
		float t = r.nextFloat();
		p.temper = t < 0.5f ? "friendly" : t < 0.75f ? "passive" : "aggressive";
		p.loner = r.nextFloat() < 0.06f + 0.25f * (1 - p.loyalty) * (1 - p.kindness);   // (about one in eight)
		p.bornWith(r);
		return p;
	}

	/** The average temperament (for a Xen without personalities). */
	static Personality plain() {
		Personality p = new Personality();
		p.bravery = p.curiosity = p.chattiness = p.diligence = 0.5f;
		p.tone = "calm";
		return p;
	}

	/** A child of two parents: each gene from one of them, then a small mutation. */
	Personality child(Personality other, String names, Random r) {
		Personality c = new Personality();
		c.bravery = mutate(r.nextBoolean() ? bravery : other.bravery, r);
		c.curiosity = mutate(r.nextBoolean() ? curiosity : other.curiosity, r);
		c.chattiness = mutate(r.nextBoolean() ? chattiness : other.chattiness, r);
		c.diligence = mutate(r.nextBoolean() ? diligence : other.diligence, r);
		c.tone = pick(tone, other.tone, TONES, r);
		c.fightGenes = new float[GENES.length];
		for (int i = 0; i < GENES.length; i++) c.fightGenes[i] = mutate(r.nextBoolean() ? fightGenes[i] : other.fightGenes[i], r);
		c.fight = nearest(c.fightGenes);
		c.build = pick(build, other.build, BUILDS, r);
		c.material = pick(material, other.material, MATERIALS, r);
		c.generation = Math.max(generation, other.generation) + 1;
		c.parents = names;
		c.kindness = mutate(r.nextBoolean() ? kindness : other.kindness, r);
		c.loyalty = mutate(r.nextBoolean() ? loyalty : other.loyalty, r);
		c.power = mutate(r.nextBoolean() ? power : other.power, r);
		c.money = mutate(r.nextBoolean() ? money : other.money, r);
		c.temper = pick(temper, other.temper, TEMPERS, r);
		c.loner = r.nextFloat() < 0.1f ? !(r.nextBoolean() ? loner : other.loner) : r.nextBoolean() ? loner : other.loner;
		if (hidden != null && other.hidden != null) {                           // its hidden stats: each from one parent, a little changed
			c.hidden = new float[HIDDEN.length];
			for (int i = 0; i < HIDDEN.length; i++) c.hidden[i] = mutate(r.nextBoolean() ? hidden(i) : other.hidden(i), r);
		}
		if (sins != null && other.sins != null) {                               // born with a mix of its parents' sins and beliefs
			c.sins = Sins.mix(sins, other.sins, r);
			c.beliefs = Beliefs.mix(beliefs, other.beliefs, c.sins, r);
		} else {
			c.bornWith(r);
		}
		return c;
	}

	/** A trait from one of the parents, or (1 time in 10) a new one. */
	private static String pick(String mine, String theirs, String[] all, Random r) {
		return r.nextFloat() < 0.1f ? all[r.nextInt(all.length)] : (r.nextBoolean() ? mine : theirs);
	}

	static float mutate(float gene, Random r) {
		return Math.max(0f, Math.min(1f, gene + (float) r.nextGaussian() * 0.1f));
	}

	/**
	 * Fear weighs 1.6x for the most timid, 0.4x for the bravest; pride and wrath make it less, beliefs either way
	 * ("creepers are everywhere" more, "fortune favors the bold" less), and a plan of playing it safe a little more.
	 */
	float cautionScale() {
		return (1.6f - 1.2f * bravery) * Math.max(0.4f, 1 + knobs().fear - 0.15f * sin(Sins.PRIDE) - 0.1f * sin(Sins.WRATH))
				* (plan == Strategy.SURVIVOR ? 1.2f : 1f);
	}

	/** Tries new things half as often (not curious) to 1.5x as often (very curious); some beliefs more or less. */
	float curiosityScale() {
		return Math.max(0.2f, 0.5f + curiosity + knobs().curious);
	}

	/** How much it talks: its chattiness, pride and lust up, sloth down, and its beliefs ("silence is awkward"). */
	float talkative() {
		return Math.max(0f, Math.min(1f, chattiness + knobs().chat + 0.2f * sin(Sins.PRIDE) + 0.2f * sin(Sins.LUST) - 0.2f * sin(Sins.SLOTH)));
	}

	/** Seconds between things it says on its own: 60 for the quiet, 10 for the chattiest. */
	long chatterGapMillis() {
		return (long) ((60 - 50 * talkative()) * 1000);
	}

	/** Gives up on a chore after half (impatient) to 1.5x (patient) the usual time; sloth sooner, hard workers later. */
	float patience() {
		return Math.max(0.3f, 0.5f + diligence + knobs().work - 0.4f * sin(Sins.SLOTH) + 0.1f * sin(Sins.PRIDE));
	}

	/** Its risk-taking (jumps, drops): its beliefs and pride and wrath. Around 0; up to about +0.6 or down to -0.4. */
	float risk() {
		return knobs().risk + 0.15f * sin(Sins.PRIDE) + 0.1f * sin(Sins.WRATH) - 0.1f * sin(Sins.SLOTH);
	}

	/** When it eats: at this food level or below (10 for most; a glutton or "food is life" sooner, "hunger keeps you sharp" later). */
	int eatAt() {
		return Math.max(6, Math.min(18, Math.round(10 + 6 * sin(Sins.GLUTTONY) + 8 * knobs().hunger + 4 * (hidden(APPETITE) - 0.5f))));   // (always hungry: eats sooner)
	}

	/**
	 * How far it trusts a stranger at first: its beliefs, envy down, lust up. Never below -0.2: distrust makes it wary
	 * of someone new, not an enemy (that takes what they do: a hit, a theft).
	 */
	float strangerTrust() {
		return Math.max(-0.2f, Math.min(0.6f, 0.2f + knobs().trust - 0.15f * sin(Sins.ENVY) + 0.1f * sin(Sins.LUST)));
	}

	/** How ready it is to give things away: kindness, its beliefs, greed down. 0 to 1. */
	float generosity() {
		return Math.max(0f, Math.min(1f, kindness + knobs().share - 0.5f * sin(Sins.GREED)));
	}

	/** In words, for its notes: "cheerful, brave and curious". */
	String describe() {
		StringBuilder sb = new StringBuilder(tone);
		if (bravery > 0.66f) sb.append(", brave");
		else if (bravery < 0.33f) sb.append(", timid");
		if (curiosity > 0.66f) sb.append(", curious");
		if (chattiness > 0.66f) sb.append(", chatty");
		else if (chattiness < 0.33f) sb.append(", quiet");
		if (diligence > 0.66f) sb.append(", patient");
		else if (diligence < 0.33f) sb.append(", impatient");
		String s = sb.toString();
		int last = s.lastIndexOf(", ");
		return last > 0 ? s.substring(0, last) + " and " + s.substring(last + 2) : s;
	}

	/** How it fights and builds, in words: "a skirmisher (hits and steps back), builds stone forts". */
	String style() {
		String what = switch (build) {
			case "fort" -> "forts";
			case "tower" -> "towers";
			default -> "huts";
		};
		String of = switch (material) {
			case "stone" -> "stone ";
			case "earth" -> "dirt ";
			default -> "";
		};
		return "a " + fight + " (" + how(fight) + "), builds " + of + what;
	}

	/** "You build stone forts." (for its notes) */
	String buildNote() {
		return "You build " + style().substring(style().indexOf("builds ") + 7) + ".";
	}

	String genes() {
		return String.format(Locale.ROOT, "bravery %.2f, curiosity %.2f, chattiness %.2f, diligence %.2f, %s, %s, %s %s, generation %d",
				bravery, curiosity, chattiness, diligence, tone, fight, material, build, generation);
	}

	static final String TRAITS_HELP = "Traits: tone, fight, build, material, temper, plan (words); loner (true/false); bravery, curiosity, chattiness, diligence, kindness, loyalty, power, money, and the fight "
			+ "genes " + String.join(", ", GENES) + " (0 to 1).";

	/** Change one trait by hand (/xen style). Null if it worked, else what's wrong. Its sins and beliefs can't be changed: they're who it is. */
	String set(String trait, String value) {
		String v = value.toLowerCase(Locale.ROOT);
		if (trait.equals("plan")) {
			int s = Strategy.index(v);
			if (s < 0) return "plan is one of: " + String.join(", ", Strategy.NAMES);
			plan = s;
			return null;
		}
		if (Sins.index(trait) >= 0 || trait.equals("beliefs")) return "Its sins and beliefs are who it is: they don't change.";
		if (trait.equals("loner")) {
			if (!v.equals("true") && !v.equals("false")) return "loner is true or false";
			loner = v.equals("true");
			return null;
		}
		String[] choices = switch (trait) {
			case "tone" -> TONES;
			case "fight" -> FIGHTS;
			case "build" -> BUILDS;
			case "material" -> MATERIALS;
			case "temper" -> TEMPERS;
			default -> null;
		};
		if (choices != null) {
			if (!java.util.Arrays.asList(choices).contains(v)) return trait + " is one of: " + String.join(", ", choices);
			switch (trait) {
				case "tone" -> tone = v;
				case "fight" -> {
					fight = v;
					fightGenes = preset(v);
				}
				case "build" -> build = v;
				case "temper" -> temper = v;
				default -> material = v;
			}
			return null;
		}
		float f;
		try {
			f = Float.parseFloat(v);
		} catch (NumberFormatException e) {
			return TRAITS_HELP;
		}
		if (f < 0 || f > 1) return trait + " is between 0 and 1.";
		int gene = java.util.Arrays.asList(GENES).indexOf(trait);
		if (gene >= 0) {
			fightGenes[gene] = f;
			fight = nearest(fightGenes);
			return null;
		}
		switch (trait) {
			case "bravery" -> bravery = f;
			case "curiosity" -> curiosity = f;
			case "chattiness" -> chattiness = f;
			case "diligence" -> diligence = f;
			case "kindness" -> kindness = f;
			case "loyalty" -> loyalty = f;
			case "power" -> power = f;
			case "money" -> money = f;
			default -> {
				return TRAITS_HELP;
			}
		}
		return null;
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("bravery", bravery);
		o.addProperty("curiosity", curiosity);
		o.addProperty("chattiness", chattiness);
		o.addProperty("diligence", diligence);
		o.addProperty("tone", tone);
		o.addProperty("fight", fight);
		com.google.gson.JsonArray genes = new com.google.gson.JsonArray();
		for (float g : fightGenes) genes.add(g);
		o.add("fightGenes", genes);
		o.addProperty("build", build);
		o.addProperty("material", material);
		o.addProperty("generation", generation);
		o.addProperty("parents", parents);
		o.addProperty("kindness", kindness);
		o.addProperty("loyalty", loyalty);
		o.addProperty("power", power);
		o.addProperty("money", money);
		o.addProperty("temper", temper);
		o.addProperty("loner", loner);
		if (sins != null) {
			com.google.gson.JsonObject sn = new com.google.gson.JsonObject();
			for (int i = 0; i < Sins.N; i++) sn.addProperty(Sins.NAMES[i], sins[i]);
			o.add("sins", sn);
			com.google.gson.JsonArray bl = new com.google.gson.JsonArray();
			for (String b : beliefs) bl.add(b);
			o.add("beliefs", bl);
		}
		if (plan >= 0) o.addProperty("plan", Strategy.NAMES[plan]);
		if (hidden != null) {
			com.google.gson.JsonObject hd = new com.google.gson.JsonObject();
			for (int i = 0; i < HIDDEN.length; i++) hd.addProperty(HIDDEN[i], hidden[i]);
			o.add("hidden", hd);
		}
		return o;
	}

	static Personality fromJson(JsonObject o) {
		Personality p = plain();
		if (o.has("bravery")) p.bravery = o.get("bravery").getAsFloat();
		if (o.has("curiosity")) p.curiosity = o.get("curiosity").getAsFloat();
		if (o.has("chattiness")) p.chattiness = o.get("chattiness").getAsFloat();
		if (o.has("diligence")) p.diligence = o.get("diligence").getAsFloat();
		if (o.has("tone")) p.tone = o.get("tone").getAsString();
		if (o.has("fight")) p.fight = o.get("fight").getAsString();
		p.fightGenes = preset(p.fight);
		if (o.has("fightGenes") && o.getAsJsonArray("fightGenes").size() == GENES.length) {
			for (int i = 0; i < GENES.length; i++) p.fightGenes[i] = o.getAsJsonArray("fightGenes").get(i).getAsFloat();
		}
		if (o.has("build")) p.build = o.get("build").getAsString();
		if (o.has("material")) p.material = o.get("material").getAsString();
		if (o.has("generation")) p.generation = o.get("generation").getAsInt();
		if (o.has("parents")) p.parents = o.get("parents").getAsString();
		if (o.has("kindness")) p.kindness = o.get("kindness").getAsFloat();
		if (o.has("loyalty")) p.loyalty = o.get("loyalty").getAsFloat();
		if (o.has("power")) p.power = o.get("power").getAsFloat();
		if (o.has("money")) p.money = o.get("money").getAsFloat();
		if (o.has("temper")) p.temper = o.get("temper").getAsString();
		if (o.has("loner")) p.loner = o.get("loner").getAsBoolean();
		if (o.has("sins") && o.get("sins").isJsonObject()) {
			p.sins = new float[Sins.N];
			var sn = o.getAsJsonObject("sins");
			for (int i = 0; i < Sins.N; i++) if (sn.has(Sins.NAMES[i])) p.sins[i] = Math.max(0f, Math.min(1f, sn.get(Sins.NAMES[i]).getAsFloat()));
			if (o.has("beliefs") && o.get("beliefs").isJsonArray()) {
				for (var b : o.getAsJsonArray("beliefs")) if (Beliefs.ALL.containsKey(b.getAsString())) p.beliefs.add(b.getAsString());
			}
		}
		if (o.has("plan")) p.plan = Strategy.index(o.get("plan").getAsString());
		if (o.has("hidden") && o.get("hidden").isJsonObject()) {
			var hd = o.getAsJsonObject("hidden");
			p.hidden = new float[HIDDEN.length];
			for (int i = 0; i < HIDDEN.length; i++) p.hidden[i] = hd.has(HIDDEN[i]) ? Math.max(0f, Math.min(1f, hd.get(HIDDEN[i]).getAsFloat())) : 0.5f;
		}
		return p;
	}

	/** Its own words for what it feels, in its tone. */
	String say(String what) {
		int t = java.util.Arrays.asList(TONES).indexOf(tone);
		String[] lines = switch (what) {
			case "night" -> new String[] {"Ooh, it's getting dark! Stay close!", "Night is coming. Let's be careful.",
					"Great, it's dark again.", "It's getting dark... can I stay near you?", "Night! Let the monsters come.",
					"The sun fell over! Night time!"};
			case "ouch" -> new String[] {"Ouch!", "Ow. Careful.", "Hey! That hurt.", "Ow... that hurt...", "Is that all you've got?",
					"Ouchie!"};
			case "lava" -> new String[] {"Careful, there's lava!", "Lava here. Let's go around.", "Ugh, lava. Of course.",
					"L-lava...", "Lava! Stay back!", "Hot hot hot! Lava!"};
			case "afraid" -> new String[] {"Hmm, I don't like this...", "Something feels wrong here.", "I don't like this one bit.",
					"I'm scared...", "Something's out there. Let it come.", "Spooky..."};
			case "diamonds" -> new String[] {"Diamonds!! We're rich!", "Diamonds. Nice.", "Diamonds. Finally something good.",
					"Oh! D-diamonds!", "Diamonds! I knew it!", "Shiny! Diamonds!!"};
			case "fight" -> new String[] {"Let's go!", "Alright. Let's do this.", "You asked for it.", "P-please stop!",
					"Bring it on!", "En garde!"};
			case "flee" -> new String[] {"Oof, I need a break!", "Backing off to recover.", "Ugh. Not today.",
					"I-I'm getting out of here!", "I'll be back!", "Tactical retreat!"};
			default -> new String[] {what};
		};
		return lines.length == 1 ? lines[0] : lines[Math.max(0, t) % lines.length];
	}
}
