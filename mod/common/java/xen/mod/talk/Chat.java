package xen.mod.talk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xen's chat: it understands what players ask and answers, but cannot cheat.
 *
 * <ul>
 *   <li>It understands requests ("Xen, get some wood", "follow me", "build a shelter") as one of a few things it can do,
 *   from the words (typos and chat shorthand too). What it then does, it does with its own hands and senses.</li>
 *   <li>It only knows what Xen knows: its notes are built from Xen's own senses and beliefs, feelings and body.</li>
 *   <li>Answers are one line of plain chat, never a command, and claim nothing that isn't in its notes.</li>
 * </ul>
 * Its talk is its own words (see {@link Voice} and {@link Banter}); there is no language model.
 */
public final class Chat {
	// ------------------------------------------------------------------------------ requests
	/** What Xen can be asked to do: the rules below pick one of these words. */
	public static final String[] INTENTS = {"follow", "stay", "explore", "wood", "stone", "coal", "iron", "mine", "food", "give",
			"shelter", "eat", "stop", "redstone", "trade", "chat", "peace", "pickup"};
	/** Things it can be asked to craft (the recipe is worked out from what it carries: "boat" is an oak boat with oak planks). */
	static final String[] CRAFTABLE = {"crafting table", "pressure plate", "boats?", "chests?", "tables?", "furnaces?", "doors?",
			"torch(es)?", "sticks?", "planks?", "beds?", "ladders?", "fences?", "bowls?", "shields?", "buckets?", "pickaxes?", "swords?",
			"axes?", "shovels?", "hoes?", "signs?", "trapdoors?", "slabs?", "stairs", "buttons?", "barrels?", "campfires?", "bread"};
	private static final Pattern CRAFT_THING = Pattern.compile("\\b((wooden|wood|stone|iron|golden|gold|diamond) )?("
			+ String.join("|", CRAFTABLE) + ")\\b");
	private static final Pattern CRAFT_WORD = Pattern.compile("\\bcraft(ing)? (me |us )?(a |an |some |the |\\d+ )*([a-z_]+)");
	private static final Pattern TOWER = Pattern.compile("\\b(tower|skyscraper)\\b"), STILTS = Pattern.compile("\\bstilts?\\b"),
			MODERN = Pattern.compile("\\b(modern|contemporary|minimalist)\\b"), COTTAGE = Pattern.compile("\\b(cottage|cabin|cozy|cosy)\\b"),
			VILLAGE = Pattern.compile("\\bvillage(r|rs)?('s)? (house|home|hut)|\\b(house|home) like (the|a) village|\\bvillage[- ]style\\b");

	/** A house in the style asked for ("a modern house", "a house on stilts", "a tower", "a village house"), or just "house". */
	static String houseStyle(String words) {
		return VILLAGE.matcher(words).find() ? "village house" : TOWER.matcher(words).find() ? "tower" : STILTS.matcher(words).find() ? "stilt house" : MODERN.matcher(words).find() ? "modern house"
				: COTTAGE.matcher(words).find() ? "cottage" : "house";
	}

	static final Map<String, Integer> AMOUNT = Map.of("wood", 8, "stone", 16, "coal", 8, "iron", 4, "mine", 8, "food", 3);
	private static final String[][] RULES = {                                   // the first that matches wins
			{"pickup", "\\b(mine|break|pick up|pickup|take|grab|collect) (the |that |this |your |my |a )?(crafting table|table|workbench|furnace|chest|bed|door|torch|torches|lantern|barrel|ladder)\\b"},
			{"peace", "\\b(truce|peace|ceasefire|i give up|i surrender|surrender|stop fighting|let'?s (stop fighting|not fight|be friends)|don'?t (hit|attack|kill|hurt) me)\\b|^(sorry|so sorry|my bad|i'?m sorry|ok ok|okay okay)[!. ]*$"},
			{"dismount", "\\b(get|hop|jump|climb) (out|off)\\b(?! (of )?(here|my (land|lawn|base|house|property)))|\\bdismount\\b"},
			{"ride", "\\b(get|hop|jump|climb) (in|into|on|onto|aboard)( of)? (my |the |this |that |our |a |your )?(boat|raft|horse|donkey|mule|camel|llama|ride)\\b|\\bride with me\\b|\\b(let'?s|we) (ride|sail|go boating)\\b|\\b(ride|mount|tame) (the |that |this |a |my |our )?(horse|donkey|mule|camel|llama)s?\\b|^(hop|get) in\\b"},
			{"attack", "\\b(attack|go after|gang up on|team up (on|against)|take down|hunt down) (the )?([a-z0-9_]{3,16})\\b"},
			{"quest", "\\b(kill|beat|defeat|slay|fight) (the )?(ender ?)?dragon\\b|\\bbeat the game\\b|\\b(find|locate|look for) (the |a )?(stronghold|end portal)\\b|\\b(go|travel|head) (to|into) (the )?(nether|end)\\b|\\bnether trip\\b|\\b(get|find|farm|collect) (some |me |us )?(\\d+ )?blaze rods?\\b|\\b(build|make|light) (a |the |us a |me a )?(nether )?portal\\b|\\b(raid|loot|clear|do|beat|take on) (the |a )?(trial chambers?|vaults?)\\b|\\bleave the nether\\b|\\b(go|get) (back )?home from the nether\\b"},
			{"use", "\\b(light|ignite)\\b (up )?(the |that |this |a |some )?(tnt|fire|it|that|this|block|grass|house|campfire|candles?)\\b|\\b(burn|set fire to|set (it|that|this) on fire|blow up)\\b|\\buse (the |your |my |a |an |some )?[a-z]|\\bbone ?meal (that|this|it)\\b|\\bput (it|that|the fire) out\\b|\\bextinguish\\b"},
			{"store", "\\b(put|store|stash|deposit|dump)\\b.*\\b(chests?|storage|away)\\b|\\b(sort|organi[sz]e) (the |your )?(chests?|storage|stuff|inventory)\\b"},
			{"guard", "\\b(guard|protect|defend|patrol|keep watch over) (the |our |my |this )?(village|base|home|house|area|territory|farm)\\b|\\bkeep (the )?(monsters|mobs) (out|away)\\b"},
			{"give", "\\b(give|hand (me|over)|pass me|toss|throw me|share|can i (have|get)|i need your)\\b"},
			{"redstone", "\\b(redstone|circuit|logic gate|(not|or|and) gate|wire)\\b"},
			{"bed", "\\b(get|find|collect|gather|grab|go for|shear)( me| us| yourself| some)* wool\\b|\\bwool for\\b|\\b(shear (the |some |a )?sheep|sheep for (a |the |my |your )?bed)\\b|\\b(get|find|make|craft|need) (yourself |me |us |you )?(a |some |your )?(new )?bed\\b"},
			{"craft", "\\b(craft|crafting)\\b|\\bmake (me |us )?(a |an |some |the |\\d+ )?((wooden|wood|stone|iron|golden|gold|diamond) )?(" + String.join("|", CRAFTABLE) + ")"},
			{"helpbuild", "\\bhelp ([a-z0-9_]{3,16}) (build|with (the |their |his |her )?(house|build|building|home))\\b|\\bhelp (them|him|her) (build|with (the |their |his |her )?(house|build|building))\\b"},
			{"build", "\\b(decorate|upgrade|improve|fix up|renovate|polish|prettify) (your |my |our |the )?(house|home|base|place)\\b|\\bmake (your |the )?(house|home) (better|nicer|prettier|look good)\\b|\\b(build|make|dig|design) (me |us )?(a |an |our |my |the |some )?(\\w+ )?(house|home|cottage|cabin|base|bunker|hideout|farm|pen|barn|grinder|statue|sculpture|tower|skyscraper|mansion)\\b|\\bunderground\\b|\\bstatue of\\b"},
			{"wood", "\\b(wood|woods|logs?|trees?|chop|timber|lumber|planks?)\\b"},
			{"coal", "\\bcoal\\b"},
			{"iron", "\\biron\\b"},
			{"stone", "\\b(stones?|cobble|cobblestone)\\b"},
			{"mine", "\\b(mine|mining|dig|digging|ores?|diamonds?|gold)\\b"},
			{"food", "\\b(food|hunt|hunting|meat|pork|porkchops?|beef|steak|mutton|chickens?|pigs?|cows?|sheep)\\b"},
			{"shelter", "\\b(shelter|house|hut|hide|bunker|build)\\b"},
			{"eat", "\\b(eat|snack|heal)\\b"},
			{"stop", "\\b(stop|cancel|never ?mind|forget (it|that)|quit|enough)\\b"},
			{"stay", "\\b(stay|wait|don'?t move|hold (on|still)|stand (still|here)|remain)\\b"},
			{"explore", "\\b(explore|wander|roam|adventure|do your (own )?thing|go play|look around|have fun|free)\\b"},
			{"follow", "\\b(follow|come|with me|let'?s go|over here|this way|keep up|to me)\\b"},
	};
	private static final Pattern[] RULE = new Pattern[RULES.length];
	private static final Pattern RULE_ATTACK = Pattern.compile("\\b(attack|go after|gang up on|team up (on|against)|take down|hunt down) (the )?([a-z0-9_]{3,16})\\b");
	/** Trading comes first, questions too ("how much for your logs?"): Xen answers those itself, as a trader. */
	private static final Pattern TRADE = Pattern.compile("\\b(trade|trades|trading|sell|selling|buy|buying|swap|exchange|barter|haggle|how much (for|is|are|do you want)|what do you want for|price (of|for))\\b|\\b\\d{1,3} [a-z_]+ for (\\d{1,3} )?(your |my )?[a-z_]+");
	private static final Pattern QUESTION = Pattern.compile("^((what|where|why|how|who|when|which)\\b|(do|does|did|are|is|am|was|were|have|has|had) "
			+ "(you|we|i|it|there|they|he|she|this|that|your|my)\\b|you (had|have|got|already have) \\d+|any\\b|(?!(can|could|will|would|wanna|pls|please) )[^?]*\\?\\s*$)");
			// (and "you had 20 wood": telling it; "any diamonds?"; anything else asked with a "?", but "can you get wood?" is a request)
	private static final Pattern SOCIAL = Pattern.compile("^(thanks|thank you|thx|ty|good (job|work|boy|girl)|nice (one|job|work)|well done|gg|lol|haha|"
			+ "love you|you rock|you're (the best|awesome|cool)|bye|goodbye|good night)\\b");
	private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,3})\\b");
	private static final String[][] GIVE_THINGS = {{"log", "wood", "log", "tree", "plank", "ไม้"}, {"cobblestone", "stone", "cobble", "rock", "หิน"},
			{"coal", "coal", "ถ่าน"}, {"raw_iron", "iron", "เหล็ก"}, {"diamond", "diamond", "เพชร"}, {"food", "food", "meat", "eat", "อาหาร", "ของกิน"}};
	static {
		for (int i = 0; i < RULES.length; i++) RULE[i] = Pattern.compile(RULES[i][1]);
	}

	/** A request: what to do, what thing (for give) and how many. */
	public record Request(String intent, String thing, int amount) {}

	/** Common typos, fixed word by word before Xen reads a request ("fallow me" is "follow me"). */
	static final Map<String, String> TYPOS = Map.ofEntries(Map.entry("fallow", "follow"), Map.entry("folow", "follow"),
			Map.entry("follw", "follow"), Map.entry("flw", "follow"), Map.entry("folllow", "follow"), Map.entry("follwo", "follow"),
			Map.entry("cmere", "come here"), Map.entry("comere", "come here"), Map.entry("c'mere", "come here"), Map.entry("plz", "please"),
			Map.entry("pls", "please"), Map.entry("stahp", "stop"), Map.entry("stp", "stop"), Map.entry("wod", "wood"),
			Map.entry("woood", "wood"), Map.entry("sheltr", "shelter"), Map.entry("shleter", "shelter"), Map.entry("explor", "explore"),
			Map.entry("exlpore", "explore"), Map.entry("mien", "mine"), Map.entry("ston", "stone"), Map.entry("stne", "stone"),
			Map.entry("fod", "food"), Map.entry("foood", "food"), Map.entry("giv", "give"), Map.entry("gimme", "give me"),
			Map.entry("stya", "stay"), Map.entry("sty", "stay"));

	public static String requestWords(String message, String name) {
		String s = message.toLowerCase(Locale.ROOT).replaceAll("\\b" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "\\b", " ")
				.replace(",", " ").trim();
		if (s.isEmpty()) return "";
		List<String> out = new ArrayList<>();
		for (String w : s.split("\\s+")) out.add(TYPOS.getOrDefault(w, w));
		return String.join(" ", out);
	}

	/** Requests in Thai: words to look for (Thai has no spaces between words), the first that matches wins. */
	static final String[][] THAI = {{"peace", "สงบศึก", "ขอโทษ", "ยอมแพ้", "ไม่สู้แล้ว"}, {"quest", "มังกร", "เนเธอร์", "ดิเอนด์", "ห้องทดลอง"},
			{"store", "เก็บของ", "ใส่หีบ", "ใส่กล่อง"}, {"chat", "ขอบคุณ"}, {"trade", "แลก", "เทรด", "ซื้อ", "ขาย"}, {"stop", "หยุด", "พอแล้ว", "ยกเลิก"},
			{"stay", "ไม่ต้องตาม", "รอ", "อยู่ตรงนี้", "อยู่นี่"}, {"follow", "ตาม", "มานี่", "มาทางนี้", "มาหา"}, {"give", "ขอ", "ส่ง"},
			{"explore", "สำรวจ", "ไปเที่ยว", "ไปเล่น"}, {"redstone", "เรดสโตน", "วงจร"}, {"wood", "ไม้"}, {"coal", "ถ่าน"}, {"iron", "เหล็ก"},
			{"stone", "หิน"}, {"mine", "ขุด", "แร่", "เพชร", "ทอง"}, {"food", "อาหาร", "ล่า", "หาของกิน"},
			{"build", "สร้างบ้าน", "บ้านใต้ดิน", "ฐานใต้ดิน", "ฟาร์ม", "คอก"}, {"shelter", "บ้าน", "ที่หลบ", "ที่พัก", "สร้าง"}, {"eat", "กิน"}};
	private static final Pattern THAI_CHAR = Pattern.compile("[\\u0e00-\\u0e7f]");

	/** What a player asks Xen to do, by keywords. Questions are just chat. */
	public static Request understand(String message, String name) {
		String words = requestWords(message, name);
		String intent = "chat";
		if (THAI_CHAR.matcher(words).find()) {
			outer:
			for (String[] t : THAI) {
				for (int k = 1; k < t.length; k++) {
					if (words.contains(t[k])) {
						intent = t[0];
						break outer;
					}
				}
			}
		} else if (TRADE.matcher(words).find()) {
			intent = "trade";
		} else {
			if (ORDER.matcher(words).lookingAt()) words = words.replaceAll("[?\\s]+$", "");   // "go explore?": an order with a question mark is still an order
			Matcher lead = SUGGESTION.matcher(words);                      // "why don't you build a house", "want to get some iron?": asked, nicely
			boolean led = lead.lookingAt();
			boolean notAsked = led && Pattern.compile("^(what|who|where|when|which|how many|how much|like|love|be|have|know|mind if|ever|still|think|remember|need|some|any)\\b")
					.matcher(lead.group("rest")).find();                     // ("would you like some food?", "do you need wood?": an offer or a question, not a request)
			String asked = led && !notAsked ? lead.group("rest").replaceAll("[?!.\\s]+$", "") : null;
			if (!notAsked && (asked != null || !QUESTION.matcher(words).lookingAt())) {
				String look = asked != null ? asked : words;
				if (BUILD_IT.matcher(look).find() && (xen.mod.Taught.nameIn(look) != null || PLACE.matcher(look).find()) || Pattern.compile("\\blay (it )?out\\b").matcher(look).find()) intent = "build";   // ("build a pool": one it was shown, first; "build a place": its own layout)
				else for (int i = 0; i < RULE.length; i++) {
					if (RULE[i].matcher(look).find()) {
						intent = RULES[i][0];
						break;
					}
				}
			}
		}
		return details(intent, words);
	}

	private static final Pattern BUILD_IT = Pattern.compile("\\b(build|make|put|place|set up|add)\\b");
	/** Words for a whole place a Xen lays out itself ("build a place", "set up your own place", "a compound", "a homestead", "lay out an estate"). */
	public static final Pattern PLACE = Pattern.compile("\\b(a|an|your|my|our|the|whole|own|new|nice|big|little) (place|compound|estate|homestead|property|yard|layout|grounds|mansion|ranch|villa)\\b"
			+ "|^(place|compound|estate|homestead|layout)$|\\blay (it )?out\\b");

	/** Starts with a verb that orders something ("go", "get", "build"...). */
	private static final Pattern ORDER = Pattern.compile("^(go|get|come|follow|build|make|mine|dig|craft|explore|stay|stop|give|bring|find|hunt|chop|collect|gather|"
			+ "fetch|kill|attack|sleep|eat|put|store|guard|help|fish|trade|light|smelt|cook|plant|farm|wait|run|take|grab|pick)\\b");

	/** A suggestion or a nice way to ask: what's after it is the request ("why don't you get some wood" is get some wood). */
	private static final Pattern SUGGESTION = Pattern.compile("^(why (don'?t|dont|not) (you|we) |why not |how about (you |we )?(go |try )?|maybe (you |we )?(should|could|can) "
			+ "|(you|we) (should|could|can|might want to|need to|ought to|gotta|have to) |(do you |you |u )?(want to|wanna) |do you wanna |shall we |should (we|you) "
			+ "|(can|could) we |(would|will|could|can) you (please )?|(please |pls )?(go )?try (to |and )?)(?<rest>.+)$");

	/** Questions and thanks: not read as a request. */
	static boolean justTalk(String words) {
		return QUESTION.matcher(words).lookingAt() || SOCIAL.matcher(words).lookingAt();
	}

	/** The thing and the amount for a request: "give me 5 logs" is (give, log, 5). */
	static Request details(String intent, String words) {
		Matcher number = NUMBER.matcher(words);
		Integer n = number.find() ? Integer.valueOf(number.group(1)) : words.contains("stack") ? Integer.valueOf(64) : null;
		int amount = n != null ? n : AMOUNT.getOrDefault(intent, 0);
		String thing = "";
		if (intent.equals("redstone")) {
			thing = "not";
			for (String k : new String[] {"and", "or", "wire"}) {
				if (Pattern.compile("\\b" + k + "\\b").matcher(words).find()) {
					thing = k;
					break;
				}
			}
			amount = 0;
		}
		if (intent.equals("pickup")) {                                        // "mine the crafting table" is (pickup, crafting_table)
			Matcher m = Pattern.compile("\\b(crafting table|table|workbench|furnace|chest|bed|door|torch|torches|lantern|barrel|ladder)\\b").matcher(words);
			String b = m.find() ? m.group(1) : "crafting table";
			thing = b.equals("table") || b.equals("workbench") ? "crafting_table" : b.equals("torches") ? "torch" : b.replace(' ', '_');
			amount = 0;
		}
		if (intent.equals("helpbuild")) {                                     // "help Aria build" is (helpbuild, aria)
			Matcher m = Pattern.compile("\\bhelp ([a-z0-9_]{3,16})\\b").matcher(words);
			return new Request(intent, m.find() && !m.group(1).matches("them|him|her") ? m.group(1) : "", 0);
		}
		if (intent.equals("build") && Pattern.compile("\\b(decorate|upgrade|improve|fix up|renovate|polish|prettify|better|nicer|prettier|look good)\\b").matcher(words).find()) {
			return new Request(intent, "upgrade", 0);                             // its own house, a stage better
		}
		if (intent.equals("build")) {                                         // a house, or a base under the ground
			Matcher of = Pattern.compile("\\b(statue|sculpture)( of ([a-z0-9_]+))?").matcher(words);
			thing = of.find() ? "statue:" + (of.group(3) == null || of.group(3).equals("yourself") ? "you" : of.group(3))
					: Pattern.compile("\\b(mob|mobs|xp|grinder)\\b").matcher(words).find() ? "mob farm"
					: Pattern.compile("\\bfarm\\b").matcher(words).find() ? "farm"
					: Pattern.compile("\\b(pen|barn|animal)\\b").matcher(words).find() ? "pen"
					: Pattern.compile("\\b(underground|base|bunker|hideout|dig)\\b").matcher(words).find() ? "base"
					: PLACE.matcher(words).find() ? "place"
					: xen.mod.Taught.nameIn(words) != null ? "taught:" + xen.mod.Taught.nameIn(words) : houseStyle(words);
			amount = 0;
		}
		if (intent.equals("craft")) {                                         // "craft 4 torches" is (craft, torch, 4)
			Matcher m = CRAFT_THING.matcher(words);
			if (m.find()) {
				String what = m.group(3).replaceAll("(es|s)$", "").replace("torch", "torch").replace(' ', '_');
				if (m.group(3).equals("stairs")) what = "stairs";
				if (m.group(3).startsWith("torch")) what = "torch";
				if (what.equals("table")) what = "crafting_table";
				if (what.equals("plank")) what = "planks";
				String material = m.group(2) == null ? "" : m.group(2).replace("wood", "wooden").replace("woodenen", "wooden").replace("gold", "golden").replace("goldenen", "golden");
				thing = material.isEmpty() ? what : material + "_" + what;
			} else {
				Matcher w = CRAFT_WORD.matcher(words);
				thing = w.find() ? w.group(4) : "";
			}
			amount = n != null ? n : 1;
		}
		if (intent.equals("quest")) {                                         // "go to the nether" is (quest, nether)
			thing = Pattern.compile("\\b(trial|vault)|ห้องทดลอง").matcher(words).find() ? "trial"
					: Pattern.compile("\\bblaze\\b").matcher(words).find() ? "blaze"
					: Pattern.compile("\\b(leave the nether|home)\\b").matcher(words).find() ? "home"
					: Pattern.compile("\\b(build|make|light) (a |the |us a |me a )?(nether )?portal\\b").matcher(words).find() ? "portal"
					: Pattern.compile("\\b(dragon|stronghold|end portal|the end|beat the game)\\b|มังกร|ดิเอนด์").matcher(words).find() ? "dragon" : "nether";
			amount = n != null ? n : 0;
		}
		if (intent.equals("attack")) {                                        // "attack steve" is (attack, steve)
			Matcher m = RULE_ATTACK.matcher(words);
			thing = m.find() ? m.group(4) : "";
			amount = 0;
		}
		if (intent.equals("store") || intent.equals("guard")) amount = 0;
		if (intent.equals("give")) {
			thing = "all";
			for (String[] t : GIVE_THINGS) {
				boolean hit = false;
				for (int k = 1; k < t.length; k++) hit |= words.contains(t[k]);
				if (hit) {
					thing = t[0];
					break;
				}
			}
			amount = n != null ? n : 0;                                    // 0 = all of it
		}
		return new Request(intent, thing, Math.max(0, Math.min(amount, 256)));
	}

	/** Things in the world it could claim to know about. */
	static final String[][] THINGS = {{"diamond"}, {"gold"}, {"iron"}, {"coal"}, {"lava"}, {"water", "lake", "river", "ocean"},
			{"tree", "wood", "log"}, {"zombie"}, {"skeleton"}, {"creeper"}, {"spider"}, {"enderman", "endermen"},
			{"village", "villager"}, {"cave"}, {"chest", "treasure"}, {"mob", "monster"}, {"emerald"}, {"redstone"}, {"lapis"},
			{"copper"}, {"obsidian"}, {"portal", "nether"}, {"dungeon", "spawner"}, {"temple", "ruin"}, {"wolf", "wolves"},
			{"animal", "cow", "pig", "sheep", "chicken", "horse"}, {"pumpkin", "melon"}};
	static final String[] PLURAL = {"diamonds", "gold", "iron", "coal", "lava", "water", "trees", "zombies", "skeletons",
			"creepers", "spiders", "endermen", "villages", "caves", "chests", "mobs", "emeralds", "redstone", "lapis", "copper",
			"obsidian", "portals", "dungeons", "temples", "wolves", "animals", "pumpkins"};
	/** Whole words, plurals too ("iron" is not in "environment"). */
	private static final Pattern[] THING = new Pattern[THINGS.length];
	static {
		for (int t = 0; t < THINGS.length; t++) THING[t] = Pattern.compile("\\b(?:" + String.join("|", THINGS[t]) + ")(?:s|es)?\\b");
	}
	private static final Pattern CLAIM = Pattern.compile("\\b(see|saw|seen|spot|spotted|found|there'?s|there is|there are|near|nearby|"
			+ "close|away|here|over there|next to|ahead|behind)\\b");
	private static final Pattern NEGATION = Pattern.compile("\\b(no|not|n't|never|nothing|haven't|don't|can't|didn't|any)\\b");

	/**
	 * The chat thread. Made when it's needed: a world that closes shuts it down for good (an executor can't be started
	 * again), and the next world opened in the same game gets a new one.
	 */
	private ExecutorService worker;
	private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
	private final Consumer<String> log;

	public Chat(Consumer<String> log) {
		this.log = log;
	}

	/** The chat thread (a new one if there's none, or the last was shut down when a world closed). */
	private synchronized ExecutorService worker() {
		if (worker == null || worker.isShutdown()) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "xen-chat");
				t.setDaemon(true);
				return t;
			});
		}
		return worker;
	}

	/** A job for the chat thread. Never throws into the game: if it can't be taken (a world closing), false. */
	private boolean submit(Runnable job) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				worker().submit(job);
				return true;
			} catch (java.util.concurrent.RejectedExecutionException e) {
				// shut down between worker() and submit(): the next try makes a new one
			}
		}
		return false;
	}

	/**
	 * Understand a player and answer, off the server thread. act gets the request (on the server thread): for something
	 * to do, Xen does it and says its plan itself (act returns null); for talk, act returns Xen's notes, and reply gets
	 * its answer in plain words from them, as safe chat.
	 */
	public void ask(String speaker, String message, String name, Function<Request, String> act, Consumer<String> reply) {
		if (pending.incrementAndGet() > 3) {                          // it can only keep so many things in mind
			pending.decrementAndGet();
			return;
		}
		boolean taken = submit(() -> {
			try {
				String notes = act.apply(understand(message, name));
				if (notes != null) reply.accept(plainly(notes, message));
			} catch (Throwable e) {
				log.accept("Xen's chat failed: " + e);
				try {
					String notes = act.apply(new Request("chat", "", 0));             // it still answers, simply
					if (notes != null) reply.accept(plainly(notes, message));
				} catch (Throwable ignored) {
					// (nothing to say then)
				}
			} finally {
				pending.decrementAndGet();
			}
		});
		if (!taken) pending.decrementAndGet();                           // (a world closing: it wasn't heard)
	}

	/** The world is closing: the chat thread stops. The next world gets a new one. */
	public synchronized void close() {
		if (worker != null) worker.shutdownNow();
		worker = null;
		pending.set(0);
	}

	/** One line of plain chat: no commands, no special tokens, at most two sentences. */
	public static String safe(String text) {
		text = text.replaceAll("<\\|[^|]*\\|>", " ").replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
		text = text.replaceFirst("^(Xen\\s*:\\s*)", "");
		while (text.startsWith("/") || text.startsWith("\\")) text = text.substring(1).trim();
		String[] sentences = text.split("(?<=[.!?])\\s+");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(2, sentences.length); i++) sb.append(i > 0 ? " " : "").append(sentences[i]);
		text = sb.toString();
		if (text.length() > 220) text = text.substring(0, text.lastIndexOf(' ', 220) > 0 ? text.lastIndexOf(' ', 220) : 220) + "...";
		return text.isEmpty() ? "..." : text;
	}

	/** Drop sentences claiming things that aren't in Xen's notes. Being told about something isn't seeing it. */
	public static String honest(String reply, String notes) {
		String known = notes.toLowerCase(Locale.ROOT);
		List<String> kept = new ArrayList<>();
		for (String sentence : reply.split("(?<=[.!?])\\s+")) {
			String s = sentence.toLowerCase(Locale.ROOT);
			boolean made_up = false;
			if (CLAIM.matcher(s).find() && !NEGATION.matcher(s).find()) {
				for (Pattern thing : THING) {
					if (thing.matcher(s).find() && !thing.matcher(known).find()) made_up = true;
				}
			}
			if (!made_up) kept.add(sentence);
		}
		return kept.isEmpty() ? "I'm not sure, I haven't seen that." : String.join(" ", kept);
	}

	private static final String[][] FIRST_PERSON = {{"\\bYou are\\b", "I'm"}, {"\\b[Yy]ou were\\b", "I was"}, {"\\bYou know there is\\b", "I know there's"},
			{"\\b[Yy]ou will\\b", "I'll"}, {"\\byou won't\\b", "I won't"}, {"\\byou're\\b", "I'm"}, {"\\byou are\\b", "I'm"},
			{"\\b(so|and|but|because|if|when) you\\b", "$1 I"}, {"\\b(tree|block|ore|lava|water|mob|mobs|it) you\\b", "$1 I"},
			{"\\byou'll\\b", "I'll"}, {"\\byou've\\b", "I've"}, {"\\byou'd\\b", "I'd"}, {"\\bYou'll\\b", "I'll"},   // ("me'll come back to it": no)
			{"\\byou (need|have|know|saw|see)\\b", "I $1"},
			{"\\byou (can|can't|could|should|would|might|must|do|did|don't|didn't|get|got|go|want|like|make|feel|think|hate|love|found|made|built|pictured|picture|imagined|planned)\\b", "I $1"},   // ("before you can make tools": "I can", not "me can")
			{"\\byourself\\b", "myself"}, {"\\bYour\\b", "My"}, {"\\byour\\b", "my"},
			{"\\bYou\\b", "I"}, {"\\byou\\b", "me"}};
	private static final Pattern PLAN = Pattern.compile("\\bPlan: (.+)$");
	/** Talk from outside the world (an AI, a bot, a program): not how Xen thinks of itself. Such a reply is thrown away. */
	/** Talk can't make it do things (only requests do), so it mustn't promise to. */

	public static String firstPerson(String text) {
		for (String[] r : FIRST_PERSON) text = text.replaceAll(r[0], r[1]);
		return text;
	}

	private static final Pattern GREET = Pattern.compile("\\b(hi|hello|hey|yo|sup|hiya|howdy|good (morning|evening|afternoon))\\b|สวัสดี");
	private static final Pattern THANKS = Pattern.compile("\\b(thanks|thank you|thx|ty)\\b|ขอบคุณ");
	private static final Pattern FEEL = Pattern.compile("\\b(how are you|how do you feel|are you ok|you ok)\\b");
	private static final Pattern LOOK = Pattern.compile("\\b(see|around|near|nearby|found|where|any|anything)\\b");
	private static final Pattern SENSED = Pattern.compile("^You (know|saw|see|hear|think)\\b");
	public static final String NOT_UNDERSTOOD = "Sorry, I didn't get that. You can ask me to follow you, stay, get wood or stone, hunt, "
			+ "build a shelter, or trade.";
	/** In its notes, before what its owner told it about itself (the custom instructions). */
	public static final String TOLD = "What your owner told you about yourself:";
	private static final Pattern WORD = Pattern.compile("[a-z]{3,}");
	private static final java.util.Set<String> COMMON = java.util.Set.of("the", "and", "you", "your", "are", "was", "were", "what", "who",
			"how", "why", "when", "where", "which", "that", "this", "with", "for", "not", "but", "have", "has", "had", "does", "did", "can",
			"could", "would", "should", "like", "about", "any", "anything", "some", "there", "they", "them", "their", "yes", "yeah", "please",
			"tell", "know", "think", "really", "very", "much", "more", "from", "into", "out", "all", "just", "too", "also", "see", "around",
			"near", "nearby", "found", "doing", "favorite", "favourite", "love", "hate", "want", "thing", "things");

	/** The words in a text that say what it's about ("cats" -> "cat"). */
	private static java.util.Set<String> keyWords(String text) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (m.find()) {
			String w = m.group();
			if (COMMON.contains(w)) continue;
			out.add(w.length() > 3 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
		}
		return out;
	}

	/** The sentence of what its owner told it about itself that fits what was asked, in its own words (or null). */
	public static String toldAbout(String notes, String message) {
		int at = notes.indexOf(TOLD);
		if (at < 0) return null;
		java.util.Set<String> asked = keyWords(message);
		if (asked.isEmpty()) return null;
		String best = null;
		int most = 0;
		for (String x : notes.substring(at + TOLD.length()).trim().split("(?<=[.!?])\\s+")) {
			java.util.Set<String> w = keyWords(x);
			w.retainAll(asked);
			if (w.size() > most) {
				most = w.size();
				best = x;
			}
		}
		return best == null ? null : firstPerson(best);
	}

	/** Xen's notes in its own words: its answer when the model's answer can't be trusted (or there's no model). */
	public static String plainly(String notes, String message) {
		Matcher plan = PLAN.matcher(notes.trim());
		if (plan.find()) return (plan.group(1).startsWith("You will") ? "Okay! " : "") + firstPerson(plan.group(1));
		List<String> sentences = new ArrayList<>();
		for (String x : notes.trim().split("(?<=[.!?])\\s+")) if (!x.isEmpty()) sentences.add(x);
		String asked = message.toLowerCase(Locale.ROOT), known = notes.toLowerCase(Locale.ROOT), unseen = null;
		for (int t = 0; t < THING.length && unseen == null; t++) {
			if (THING[t].matcher(asked).find() && !THING[t].matcher(known).find()) unseen = PLURAL[t];
		}
		if (unseen != null || asked.isBlank()) {                        // nothing asked: what it feels and knows
			StringBuilder sb = new StringBuilder(unseen != null ? "I haven't seen any " + unseen + "." : sentences.isEmpty() ? "" : sentences.get(0));
			for (int i = 1; i < sentences.size(); i++) {
				if (sentences.get(i).startsWith("You carry")) continue;
				sb.append(' ').append(sentences.get(i));
				break;
			}
			String text = firstPerson(sb.toString().trim());
			return text.isBlank() ? "I'm not sure, I haven't seen that." : text;
		}
		for (Pattern thing : THING) {                               // asked about something it knows: that
			if (!thing.matcher(asked).find()) continue;
			for (String x : sentences) if (thing.matcher(x.toLowerCase(Locale.ROOT)).find()) return firstPerson(x);
		}
		String told = toldAbout(notes, asked);                          // what its owner told it about itself
		if (told != null) return told;
		String mood = sentences.isEmpty() ? "" : firstPerson(sentences.get(0));
		if (THANKS.matcher(asked).find()) return "You're welcome!";
		if (GREET.matcher(asked).find()) return ("Hi! " + mood).trim();
		if (FEEL.matcher(asked).find()) return mood.isEmpty() ? "I'm fine." : mood;
		if (LOOK.matcher(asked).find()) {
			for (String x : sentences) if (SENSED.matcher(x).find()) return firstPerson(x);
			return "I don't see anything special.";
		}
		return NOT_UNDERSTOOD;
	}

	/** Does the message ask about a thing it might have or have seen (iron, wood, a zombie...)? */
	public static boolean asksAboutThing(String message) {
		String asked = message.toLowerCase(Locale.ROOT);
		for (Pattern thing : THING) if (thing.matcher(asked).find()) return true;
		return false;
	}

	/** Xen's notes: its feelings, body and what it perceives, in plain words. */
	public static String notes(String mood, boolean hurt, float health, float hunger, String carrying, String perceived) {
		StringBuilder sb = new StringBuilder("You feel ").append(mood).append('.');
		if (hurt || health < 8) sb.append(" You are hurt.");
		if (hunger < 8) sb.append(" You are hungry.");
		if (!carrying.isEmpty()) sb.append(" You carry ").append(carrying).append('.');
		sb.append(' ').append(perceived);
		return sb.toString();
	}
}
