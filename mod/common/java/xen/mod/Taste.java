package xen.mod;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * How a Xen likes to build, learned: no two Xens build the same house, and a Xen's houses change as it learns what works
 * and what people like. A design is a set of choices (how big, how tall, the roof, a stone base, a log frame, a stone
 * bottom row, shutters, a porch, a chimney, bushes, a loft); each choice has a liking, from -1 to 1. It starts from its
 * personality (a fort builder likes stone and hipped roofs, a tower builder tall houses with a loft), picks each new
 * design by its likings with a bit of chance (curious Xens try more new things), and learns after each house from:
 * what players say about it ("nice house!", "that's ugly"), what its tribe says (a village comes to share a style), and
 * how well the build went (blocks it couldn't manage, how long it took).
 */
final class Taste {
	enum Roof { GABLE, HIP, FLAT }

	/** One design: all the choices for a house. */
	record Design(int w, int d, int wallH, Roof roof, boolean ridgeAlongWidth, boolean base, boolean frame, boolean lowerStone, boolean shutters,
			boolean porch, boolean chimney, boolean bushes, boolean loft, boolean yard, boolean garden) {

		/** The choices as "feature:value" keys (what the likings are about). */
		String[] keys() {
			String size = w * d <= 49 ? "small" : w * d <= 63 ? "medium" : "large";
			return new String[] {"size:" + size, "tall:" + (wallH >= 4), "roof:" + roof.name().toLowerCase(Locale.ROOT), "base:" + base, "frame:" + frame,
					"lower:" + lowerStone, "shutters:" + shutters, "porch:" + porch, "chimney:" + chimney, "bushes:" + bushes, "loft:" + loft,
					"yard:" + yard, "garden:" + garden};
		}

		String describe() {
			StringBuilder sb = new StringBuilder(w + " by " + d + (wallH >= 4 ? ", tall" : "") + ", a " + roof.name().toLowerCase(Locale.ROOT) + " roof");
			if (base || lowerStone) sb.append(", stone at the bottom");
			if (frame) sb.append(", a log frame");
			if (shutters) sb.append(", shutters");
			if (porch) sb.append(", a porch");
			if (chimney) sb.append(", a chimney");
			if (loft) sb.append(", a loft");
			if (bushes) sb.append(", bushes out front");
			if (garden) sb.append(", flower beds");
			if (yard) sb.append(", a fenced yard");
			return sb.toString();
		}
	}

	private final Companion c;
	final Map<String, Float> liking = new LinkedHashMap<>();
	private final Random random = new Random();
	/** Its last house: the design, where, when it was finished (for what people say about it). */
	Design last;
	net.minecraft.core.BlockPos lastAt;
	long lastDone = -1;
	int houses;
	/** Its tribe has seen its last house (and said what they think). */
	boolean shown;

	Taste(Companion c) {
		this.c = c;
	}

	private float like(String key) {
		return liking.computeIfAbsent(key, this::prior);
	}

	/** Where its liking for something starts: from its personality. */
	private float prior(String key) {
		var p = c.personality;
		boolean fort = p.build.equals("fort"), tower = p.build.equals("tower"), stone = p.material.equals("stone");
		return switch (key) {
			case "base:true", "lower:true" -> stone || fort ? 0.5f : 0.1f;
			case "roof:hip" -> fort ? 0.4f : 0f;
			case "roof:gable" -> fort ? 0f : 0.3f;
			case "roof:flat" -> p.material.equals("earth") ? 0.2f : -0.2f;
			case "tall:true", "loft:true" -> tower ? 0.5f : -0.1f;
			case "size:large" -> p.diligence > 0.5f ? 0.35f : 0.1f;
			case "size:medium" -> 0.25f;
			case "size:small" -> p.diligence < 0.3f ? 0.1f : -0.2f;
			case "garden:true" -> 0.3f;
			case "yard:true" -> p.diligence > 0.5f ? 0.2f : -0.1f;
			case "frame:true" -> 0.3f;
			case "shutters:true", "bushes:true" -> p.curiosity > 0.5f ? 0.2f : 0f;
			case "porch:true", "chimney:true" -> p.diligence > 0.5f ? 0.1f : -0.1f;
			default -> 0f;
		};
	}

	/** Pick one of two by liking (softmax, with more chance for curious Xens). */
	private boolean pick(String feature) {
		float t = 0.25f + 0.35f * c.personality.curiosity;
		double a = Math.exp(like(feature + ":true") / t), b = Math.exp(like(feature + ":false") / t);
		return random.nextDouble() < a / (a + b);
	}

	/**
	 * A new design. starter: its first house in survival (small and cheap: what a few trees give); room: how much
	 * wood (in planks) it could spend.
	 */
	Design design(boolean starter, boolean creative) {
		float t = 0.25f + 0.35f * c.personality.curiosity;
		String[] sizes = starter ? new String[] {"small"} : creative ? new String[] {"medium", "large"} : new String[] {"small", "medium", "large"};
		String size = sizes[0];
		double best = -1e9;
		for (String s : sizes) {
			double v = like("size:" + s) / t + random.nextGaussian() * 0.5;
			if (v > best) {
				best = v;
				size = s;
			}
		}
		int w, d;
		switch (size) {
			case "small" -> {                                                    // (a first house: 7 by 5; later ones 7 by 7)
				w = 7;
				d = starter ? 5 : 7;
			}
			case "medium" -> {
				w = 9;
				d = 7;
			}
			default -> {
				w = 11 + 2 * random.nextInt(2);
				d = 9;
			}
		}
		Roof roof = Roof.GABLE;
		best = -1e9;
		for (Roof r : Roof.values()) {
			if (starter && r == Roof.HIP) continue;
			double v = like("roof:" + r.name().toLowerCase(Locale.ROOT)) / t + random.nextGaussian() * 0.5;
			if (v > best) {
				best = v;
				roof = r;
			}
		}
		boolean tall = !starter && pick("tall");
		boolean loft = tall && d >= 7 && pick("loft");
		boolean rich = creative || !starter;
		boolean tallAnyway = w >= 11;                                        // (a big house gets taller walls)
		return new Design(w, d, loft ? 5 : tall || tallAnyway ? 4 : 3, roof, random.nextBoolean(), rich && pick("base"), !starter && pick("frame"),
				rich && pick("lower"), pick("shutters"), rich && pick("porch"), rich && pick("chimney"), pick("bushes"), loft, rich && pick("yard"), pick("garden"));
	}

	/** How much it likes a design (-1 to 1): for choosing, and for what it thinks of another Xen's house. */
	float score(Design d) {
		float s = 0;
		String[] k = d.keys();
		for (String key : k) s += like(key);
		return s / k.length;
	}

	/** How it went, from -1 (awful) to 1 (loved): each choice of that design moves toward it. */
	void reward(Design d, float r) {
		if (d == null) return;
		for (String key : d.keys()) liking.put(key, Math.max(-1f, Math.min(1f, like(key) + 0.25f * (r - like(key)))));
		c.journal("learns", String.format(Locale.ROOT, "building taste: %s -> %+.1f", d.describe(), r));
	}

	/** It liked another's house: a little of that style rubs off (a village comes to share a look). */
	void admire(Design d) {
		for (String key : d.keys()) liking.put(key, Math.max(-1f, Math.min(1f, like(key) + 0.08f)));
	}

	/** It finished a house of its design: how it went (r: its own view, from how many blocks it had to leave out, how long). */
	void built(Design d, net.minecraft.core.BlockPos at, float r) {
		last = d;
		lastAt = at;
		lastDone = c.player.level().getGameTime();
		houses++;
		shown = false;
		reward(d, r);
	}

	private static final java.util.regex.Pattern PRAISE = java.util.regex.Pattern.compile(
			"\\b(nice|cool|beautiful|lovely|love|great|awesome|pretty|amazing|good|cute|cozy|cosy|wow)\\b.*\\b(house|home|build|place|hut|roof|porch)\\b"
					+ "|\\b(house|home|build|place|hut|roof|porch)\\b.*\\b(nice|cool|beautiful|lovely|great|awesome|pretty|amazing|good|cute|cozy|cosy)\\b");
	private static final java.util.regex.Pattern CRITICISM = java.util.regex.Pattern.compile(
			"\\b(ugly|bad|hate|boring|awful|weird|trash|ew|terrible|small|tiny)\\b.*\\b(house|home|build|hut|roof)\\b"
					+ "|\\b(house|home|build|hut|roof)\\b.*\\b(ugly|bad|boring|awful|weird|trash|terrible|sucks|too small|tiny)\\b");

	/**
	 * Someone said something about its house (in the ten minutes after, or standing by it): praise makes it build more
	 * like that, criticism makes it try other things. What it says back, or null if it wasn't about the house.
	 */
	String heard(String words, net.minecraft.world.entity.player.Player from) {
		if (last == null || lastAt == null) return null;
		long since = c.player.level().getGameTime() - lastDone;
		boolean near = from != null && from.blockPosition().closerThan(lastAt, 20);
		if (since > 12000 && !near) return null;
		String w = words.toLowerCase(Locale.ROOT);
		if (w.contains("not ") && PRAISE.matcher(w).find() || CRITICISM.matcher(w).find()) {
			reward(last, -0.6f);
			return random.nextBoolean() ? "Oh. Okay, I'll try something different next time." : "Hm. Fair. Next one will be better.";
		}
		if (PRAISE.matcher(w).find()) {
			reward(last, 0.9f);
			return random.nextBoolean() ? "Thanks! I'll build more like that." : "Thank you! I like the " + favourite() + " too.";
		}
		return null;
	}

	private static final Map<String, String> WORDS = Map.ofEntries(Map.entry("roof:gable", "roof"), Map.entry("roof:hip", "hipped roof"),
			Map.entry("roof:flat", "flat roof"), Map.entry("base:true", "stone base"), Map.entry("frame:true", "timber frame"),
			Map.entry("lower:true", "stonework"), Map.entry("shutters:true", "shutters"), Map.entry("porch:true", "porch"),
			Map.entry("chimney:true", "chimney"), Map.entry("bushes:true", "bushes"), Map.entry("loft:true", "loft"), Map.entry("yard:true", "yard"),
			Map.entry("garden:true", "flower beds"), Map.entry("tall:true", "high walls"), Map.entry("size:large", "size of it"));

	/** What it (this Xen, by its own taste) likes most about a design. */
	String likedMost(Design d) {
		String best = "roof";
		float top = -9;
		for (String key : d.keys()) {
			String word = WORDS.get(key);
			if (word == null) continue;
			float l = like(key) + random.nextFloat() * 0.1f;
			if (l > top) {
				top = l;
				best = word;
			}
		}
		return best;
	}

	/** What it likes most about its last house (for a thank-you). */
	private String favourite() {
		return likedMost(last);
	}

	/** Its first house was a small one: once it has plenty of wood, it wants a better one. */
	boolean wantsBigger(int logs) {
		return last != null && houses < 3 && last.w() * last.d() <= 35 && logs >= 40 && c.player.level().getGameTime() - lastDone > 24000;
	}

	/**
	 * Another Xen of its tribe looked at its new house: what it thinks, in words (or null if nothing to say). Liking it
	 * makes it a little more like that itself, and pleases the builder (a village comes to share a style).
	 */
	String opinionOf(Companion builder) {
		Design d = builder.taste.last;
		if (d == null) return null;
		float s = score(d);
		if (s > 0.12f) {
			admire(d);
			builder.taste.reward(d, 0.6f);
			String what = likedMost(d);
			return c.pick3("Nice house, " + builder.name + "! I like the " + what + ".", "Ooh, " + builder.name + ", the " + what + " is so good!",
					"Love your house, " + builder.name + ". That " + what + "!");
		}
		if (s < -0.15f) return "Not my style, " + builder.name + ", but it's a house!";
		return null;
	}

	/** What it builds like, in words (for its notes). */
	String describe() {
		if (last == null) return "";
		return "Your last house: " + last.describe() + ".";
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		liking.forEach(o::addProperty);
		o.addProperty("#houses", houses);
		if (last != null) {
			o.addProperty("#last", last.w() + "," + last.d() + "," + last.wallH() + "," + last.roof() + "," + last.ridgeAlongWidth() + "," + last.base() + ","
					+ last.frame() + "," + last.lowerStone() + "," + last.shutters() + "," + last.porch() + "," + last.chimney() + "," + last.bushes() + ","
					+ last.loft() + "," + last.yard() + "," + last.garden());
			if (lastAt != null) o.addProperty("#at", lastAt.getX() + "," + lastAt.getY() + "," + lastAt.getZ());
		}
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		for (var e : o.entrySet()) {
			try {
				switch (e.getKey()) {
					case "#houses" -> houses = e.getValue().getAsInt();
					case "#last" -> {
						String[] f = e.getValue().getAsString().split(",");
						last = new Design(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Roof.valueOf(f[3]), Boolean.parseBoolean(f[4]),
								Boolean.parseBoolean(f[5]), Boolean.parseBoolean(f[6]), Boolean.parseBoolean(f[7]), Boolean.parseBoolean(f[8]),
								Boolean.parseBoolean(f[9]), Boolean.parseBoolean(f[10]), Boolean.parseBoolean(f[11]), Boolean.parseBoolean(f[12]),
								f.length > 13 && Boolean.parseBoolean(f[13]), f.length > 14 && Boolean.parseBoolean(f[14]));
						shown = true;
						lastDone = -24000;                                         // (as if a while ago)
					}
					case "#at" -> {
						String[] f = e.getValue().getAsString().split(",");
						lastAt = new net.minecraft.core.BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
					}
					default -> liking.put(e.getKey(), e.getValue().getAsFloat());
				}
			} catch (RuntimeException ignored) {
				// an old or broken entry
			}
		}
	}
}
