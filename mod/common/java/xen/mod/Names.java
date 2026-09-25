package xen.mod;

import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Names for new Xens, in several styles, and matching their nature: a silly Xen may be "WobblyNoodle" or
 * "LilPickle", a bold one "IronComet" or "CaptainBlaze", a shy one "SleepyMoth", a grumpy one "SaltyBadger". Or a
 * made-up word ("Zorbax"), a gamer tag ("Pickle_42", "xXWaffleXx"), or a classic little name ("Pip", "Bramble").
 * Always a valid player name: 3 to 16 letters, digits and underscores.
 */
final class Names {
	static final String[] STYLES = {"mixed", "fun", "gamer", "fantasy", "classic"};

	/** The classic little names of earlier versions. */
	static final String[] CLASSIC = {"Pip", "Nova", "Bramble", "Juniper", "Pebble", "Rowan", "Sprocket", "Maple", "Fennel", "Tinker",
			"Wren", "Clover", "Ember", "Moss", "Quill", "Sable", "Tansy", "Birch", "Cobble", "Nimbus", "Pickle", "Rune", "Sorrel",
			"Thistle", "Umber", "Violet", "Willow", "Yarrow", "Zephyr", "Acorn", "Basil", "Cinder", "Dusk", "Echo", "Flint", "Gale",
			"Hazel", "Iris", "Jasper", "Kestrel", "Lark", "Mica", "Nettle", "Onyx", "Poppy", "Quartz", "Reed", "Slate", "Tallow",
			"Vesper", "Wisp", "Xeno", "Yew", "Zinnia", "Biscuit", "Noodle", "Muffin", "Waffle", "Pudding", "Sprout"};

	/** Words that fit each tone (cheerful, calm, grumpy, shy, bold, silly). */
	private static final String[][] ADJ = {
			{"Sunny", "Happy", "Lucky", "Sparkly", "Bubbly", "Jolly", "Peppy", "Zippy", "Cheery", "Merry", "Bright", "Giddy"},
			{"Misty", "Mellow", "Quiet", "Gentle", "Cozy", "Silver", "Drifty", "Calm", "Mossy", "Velvet", "Hazy", "Still"},
			{"Grumpy", "Cranky", "Salty", "Stormy", "Gruff", "Crusty", "Moody", "Sulky", "Grouchy", "Prickly", "Snarly", "Rusty"},
			{"Tiny", "Shy", "Soft", "Sleepy", "Little", "Wee", "Timid", "Fluffy", "Dozy", "Quiet", "Small", "Snuggly"},
			{"Mighty", "Iron", "Blaze", "Turbo", "Brave", "Wild", "Thunder", "Rocket", "Savage", "Steel", "Storm", "Epic"},
			{"Wobbly", "Goofy", "Bouncy", "Wacky", "Silly", "Sneaky", "Derpy", "Fuzzy", "Giggly", "Loopy", "Noodly", "Zany"}};
	private static final String[] NOUN = {"Pickle", "Waffle", "Nugget", "Biscuit", "Taco", "Muffin", "Noodle", "Potato", "Cookie",
			"Pancake", "Mango", "Toast", "Creeper", "Cobble", "Pebble", "Pixel", "Llama", "Axolotl", "Otter", "Badger", "Ferret", "Goose",
			"Panda", "Fox", "Beetle", "Moth", "Toad", "Newt", "Squid", "Slime", "Golem", "Wizard", "Knight", "Bandit", "Goblin",
			"Sprout", "Acorn", "Comet", "Rocket", "Wombat", "Walrus", "Yeti", "Dragon", "Button", "Sock", "Teapot", "Kettle",
			"Lantern", "Anvil", "Bucket", "Carrot", "Melon", "Dumpling", "Pretzel", "Bean", "Turnip", "Hamster", "Penguin", "Gecko",
			"Raccoon", "Pumpkin", "Cactus", "Bagel", "Donut", "Marshmallow", "Parrot", "Frog", "Bee", "Cod", "Salmon", "Moose"};
	private static final String[][] TITLE = {{"Sir", "Lady", "Captain", "Doctor", "Lil", "Mister", "Miss", "Professor", "Agent",
			"King", "Queen", "Baron", "Duke", "Chief"}};
	private static final String[] GAMER_END = {"Master", "Boss", "King", "Queen", "Lord", "Pro", "Gamer", "Main", "Fan", "Hunter", "Miner"};
	private static final String[] PRE = {"Zor", "Kri", "Bel", "Tam", "Vex", "Lum", "Fen", "Gri", "Mo", "Pi", "Ra", "Sku", "Tri", "Wu",
			"Ya", "Zi", "Or", "Ul", "Ka", "Dro", "Bo", "Fli", "Glo", "Hux", "Jin", "Quo", "Snu", "Tob", "Vo", "Wim"};
	private static final String[] MID = {"ba", "lo", "ri", "zen", "mo", "ka", "ndo", "xi", "", "", "pi", "lu", "ga", "vi", "to"};
	private static final String[] END = {"x", "n", "ra", "bo", "ly", "th", "zz", "ka", "to", "mi", "gle", "ck", "ble", "po", "nk", "sh"};

	private Names() {}

	private static int toneIndex(String tone) {
		int t = java.util.Arrays.asList(Personality.TONES).indexOf(tone);
		return t < 0 ? 0 : t;
	}

	private static String pick(String[] a, Random r) {
		return a[r.nextInt(a.length)];
	}

	/** One name in a style ("mixed" picks a style each time), fitting the tone. */
	static String one(String style, String tone, Random r) {
		String s = style;
		if (s.equals("mixed")) {
			float x = r.nextFloat();
			s = x < 0.35f ? "fun" : x < 0.5f ? "title" : x < 0.62f ? "gamer" : x < 0.8f ? "fantasy" : "classic";
		}
		String[] adj = ADJ[toneIndex(tone)];
		return switch (s) {
			case "fun" -> pick(adj, r) + pick(NOUN, r);
			case "title" -> pick(TITLE[0], r) + pick(NOUN, r);
			case "gamer" -> switch (r.nextInt(4)) {
				case 0 -> pick(NOUN, r) + (r.nextBoolean() ? "_" : "") + (2 + r.nextInt(98));
				case 1 -> pick(NOUN, r) + pick(GAMER_END, r);
				case 2 -> "xX" + pick(NOUN, r) + "Xx";
				default -> "The" + pick(adj, r) + pick(NOUN, r);
			};
			case "fantasy" -> {
				String w = pick(PRE, r) + pick(MID, r) + pick(END, r);
				yield w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1);
			}
			default -> pick(CLASSIC, r);
		};
	}

	/** A name nobody has yet, or null after many tries. */
	static String fresh(String style, String tone, Set<String> taken, Random r) {
		for (int tries = 0; tries < 300; tries++) {
			String n = one(style, tone, r);
			if (tries > 150) n = n + (2 + r.nextInt(98));
			if (n.length() < 3 || n.length() > 16 || !n.matches("[A-Za-z0-9_]+")) continue;
			if (!taken.contains(n.toLowerCase(Locale.ROOT))) return n;
		}
		return null;
	}
}
