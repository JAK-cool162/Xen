package xen.mod;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Random;

/**
 * A Xen's temperament: genes that shape how its body uses the shared brain. All Xens share one brain (what they have
 * learned), but each has its own nature: how brave, how curious, how chatty, how patient, and its tone of voice.
 * Evolution passes the genes of the Xens that do well on to new Xens, with small mutations.
 */
public final class Personality {
	static final String[] TONES = {"cheerful", "calm", "grumpy", "shy", "bold", "silly"};

	/** 0 = timid, 1 = fearless: how much fear holds it back. */
	public float bravery;
	/** How often it tries something new instead of what it knows. */
	public float curiosity;
	/** How much it talks on its own. */
	public float chattiness;
	/** How long it keeps at a chore before giving up. */
	public float diligence;
	public String tone;
	public int generation;
	public String parents = "";

	static Personality random(Random r) {
		Personality p = new Personality();
		p.bravery = r.nextFloat();
		p.curiosity = r.nextFloat();
		p.chattiness = r.nextFloat();
		p.diligence = r.nextFloat();
		p.tone = TONES[r.nextInt(TONES.length)];
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
		c.tone = r.nextFloat() < 0.1f ? TONES[r.nextInt(TONES.length)] : (r.nextBoolean() ? tone : other.tone);
		c.generation = Math.max(generation, other.generation) + 1;
		c.parents = names;
		return c;
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

	String genes() {
		return String.format(Locale.ROOT, "bravery %.2f, curiosity %.2f, chattiness %.2f, diligence %.2f, %s, generation %d",
				bravery, curiosity, chattiness, diligence, tone, generation);
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("bravery", bravery);
		o.addProperty("curiosity", curiosity);
		o.addProperty("chattiness", chattiness);
		o.addProperty("diligence", diligence);
		o.addProperty("tone", tone);
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
			default -> new String[] {what};
		};
		return lines.length == 1 ? lines[0] : lines[Math.max(0, t) % lines.length];
	}
}
