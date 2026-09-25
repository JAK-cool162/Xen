package xen.mod;

import com.google.gson.JsonObject;

import xen.mod.core.Action;

import java.util.Locale;
import java.util.Random;

/**
 * A Xen's temperament: genes that shape how its body uses the shared brain. All Xens share one brain (what they have
 * learned), but each has its own nature: how brave, how curious, how chatty, how patient, its tone of voice, how it
 * fights and how it builds. Evolution passes the genes of the Xens that do well on to new Xens, with small mutations.
 */
public final class Personality {
	static final String[] TONES = {"cheerful", "calm", "grumpy", "shy", "bold", "silly"};
	/** How it fights: see {@link Fight}. */
	static final String[] FIGHTS = {"brawler", "rusher", "skirmisher", "guard", "dancer"};
	/** The shape of the shelter it builds: a hut (10 blocks), a fort with corners (18) or a tall tower (14). */
	static final String[] BUILDS = {"hut", "fort", "tower"};
	/** The blocks it likes to build with. */
	static final String[] MATERIALS = {"stone", "earth", "any"};

	/**
	 * A fighting style: how often it jumps for critical hits, how charged its swing must be, what it does while its
	 * swing charges (null: wait), how hurt it gets before it backs off to recover (0: never), and whether it chases.
	 */
	record Fight(String name, String how, float crit, float charge, Action recharge, float flee, boolean chase) {}

	static Fight fight(String name) {
		return switch (name) {
			case "rusher" -> new Fight(name, "rushes in and keeps pressing", 0.85f, 0.85f, Action.FORWARD, 0f, true);
			case "skirmisher" -> new Fight(name, "hits and steps back", 0.3f, 0.95f, Action.BACK, 0.3f, true);
			case "guard" -> new Fight(name, "holds its ground behind its shield and waits for full swings", 0.5f, 1f, null, 0.35f, false);
			case "dancer" -> new Fight(name, "circles around its foe", 0.4f, 0.9f, Action.LEFT, 0.25f, true);
			default -> new Fight("brawler", "trades blows", 0.6f, 0.9f, null, 0f, true);
		};
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
	public String build = "hut";
	public String material = "any";
	public int generation;
	public String parents = "";

	static Personality random(Random r) {
		Personality p = new Personality();
		p.bravery = r.nextFloat();
		p.curiosity = r.nextFloat();
		p.chattiness = r.nextFloat();
		p.diligence = r.nextFloat();
		p.tone = TONES[r.nextInt(TONES.length)];
		p.fight = FIGHTS[r.nextInt(FIGHTS.length)];
		p.build = BUILDS[r.nextInt(BUILDS.length)];
		p.material = MATERIALS[r.nextInt(MATERIALS.length)];
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
		c.fight = pick(fight, other.fight, FIGHTS, r);
		c.build = pick(build, other.build, BUILDS, r);
		c.material = pick(material, other.material, MATERIALS, r);
		c.generation = Math.max(generation, other.generation) + 1;
		c.parents = names;
		return c;
	}

	/** A trait from one of the parents, or (1 time in 10) a new one. */
	private static String pick(String mine, String theirs, String[] all, Random r) {
		return r.nextFloat() < 0.1f ? all[r.nextInt(all.length)] : (r.nextBoolean() ? mine : theirs);
	}

	private static float mutate(float gene, Random r) {
		return Math.max(0f, Math.min(1f, gene + (float) r.nextGaussian() * 0.1f));
	}

	/** Fear weighs 1.6x for the most timid, 0.4x for the bravest. */
	float cautionScale() {
		return 1.6f - 1.2f * bravery;
	}

	/** Tries new things half as often (not curious) to 1.5x as often (very curious). */
	float curiosityScale() {
		return 0.5f + curiosity;
	}

	/** Seconds between things it says on its own: 60 for the quiet, 10 for the chattiest. */
	long chatterGapMillis() {
		return (long) ((60 - 50 * chattiness) * 1000);
	}

	/** Gives up on a chore after half (impatient) to 1.5x (patient) the usual time. */
	float patience() {
		return 0.5f + diligence;
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
		return "a " + fight + " (" + fight(fight).how() + "), builds " + of + what;
	}

	/** "You build stone forts." (for its notes) */
	String buildNote() {
		return "You build " + style().substring(style().indexOf("builds ") + 7) + ".";
	}

	String genes() {
		return String.format(Locale.ROOT, "bravery %.2f, curiosity %.2f, chattiness %.2f, diligence %.2f, %s, %s, %s %s, generation %d",
				bravery, curiosity, chattiness, diligence, tone, fight, material, build, generation);
	}

	/** Change one trait by hand (/xen style). Null if it worked, else what's wrong. */
	String set(String trait, String value) {
		String v = value.toLowerCase(Locale.ROOT);
		String[] choices = switch (trait) {
			case "tone" -> TONES;
			case "fight" -> FIGHTS;
			case "build" -> BUILDS;
			case "material" -> MATERIALS;
			default -> null;
		};
		if (choices != null) {
			if (!java.util.Arrays.asList(choices).contains(v)) return trait + " is one of: " + String.join(", ", choices);
			switch (trait) {
				case "tone" -> tone = v;
				case "fight" -> fight = v;
				case "build" -> build = v;
				default -> material = v;
			}
			return null;
		}
		float f;
		try {
			f = Float.parseFloat(v);
		} catch (NumberFormatException e) {
			return "Traits: tone, fight, build, material (words); bravery, curiosity, chattiness, diligence (0 to 1).";
		}
		if (f < 0 || f > 1) return trait + " is between 0 and 1.";
		switch (trait) {
			case "bravery" -> bravery = f;
			case "curiosity" -> curiosity = f;
			case "chattiness" -> chattiness = f;
			case "diligence" -> diligence = f;
			default -> {
				return "Traits: tone, fight, build, material (words); bravery, curiosity, chattiness, diligence (0 to 1).";
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
		o.addProperty("build", build);
		o.addProperty("material", material);
		o.addProperty("generation", generation);
		o.addProperty("parents", parents);
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
		if (o.has("build")) p.build = o.get("build").getAsString();
		if (o.has("material")) p.material = o.get("material").getAsString();
		if (o.has("generation")) p.generation = o.get("generation").getAsInt();
		if (o.has("parents")) p.parents = o.get("parents").getAsString();
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
