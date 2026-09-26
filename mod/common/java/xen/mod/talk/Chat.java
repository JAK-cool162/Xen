package xen.mod.talk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *   <li>It understands requests ("Xen, get some wood", "follow me", "build a shelter") as one of a few things it can do.
 *   Keywords decide when there are some; otherwise the small local chat model (SmolLM2-360M) picks. What it then does,
 *   it does with its own hands and senses.</li>
 *   <li>It only knows what Xen knows: its notes are built from Xen's own senses and beliefs, feelings and body.</li>
 *   <li>Answers are one line of plain chat, never a command.</li>
 *   <li>It doesn't make things up about the world: an answer claiming to see something that isn't in its notes is not
 *   sent (it tries again, then just says what it knows).</li>
 *   <li>Without the model (switched off, or too little memory, as on phones) it still understands keywords and answers
 *   in plain words from its notes.</li>
 * </ul>
 * Same prompts and rules as xen/talk/chat.py.
 */
public final class Chat {
	public static final String MODEL = "smollm2-360m-instruct-q8_0.gguf";
	public static final String URL = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/" + MODEL;
	/** The model's SHA-256: a copy unpacked from the mod or downloaded must be exactly this file. */
	public static final String SHA256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201";
	/** Where the all-in-one jar carries the model. */
	public static final String EMBEDDED = "/assets/xen/model/" + MODEL;
	/** Memory the game should have (-Xmx3G or more) before the model is loaded on "auto"; the model takes about 500 MB. */
	public static final long AUTO_MEMORY = 2816L << 20;

	static final String PERSONA = "<|im_start|>system\nYou are Xen, a survival companion in Minecraft. You play fair like a real player: "
			+ "you only know what you have seen yourself, and far-away things are only guesses. You feel fear, pain and "
			+ "curiosity. Answer as Xen in one or two short, friendly sentences. Only talk about things in your notes; if you "
			+ "don't know, say so. Never write commands.<|im_end|>\n"
			+ "<|im_start|>user\nNotes: You feel calm. You know there is a pumpkin 4 blocks from you.\n"
			+ "Steve says: what do you see?<|im_end|>\n"
			+ "<|im_start|>assistant\nI can see a pumpkin about 4 blocks from me.<|im_end|>\n"
			+ "<|im_start|>user\nNotes: You feel afraid. You are hurt. Nothing special is around you.\n"
			+ "Alex says: find any gold?<|im_end|>\n"
			+ "<|im_start|>assistant\nNo, I haven't seen any gold. And I'm hurt, so let's be careful.<|im_end|>\n"
			+ "<|im_start|>user\nNotes: You feel happy. You carry 12 cobblestone. You know there is water 5 blocks from you.\n"
			+ "Steve says: thanks for the help!<|im_end|>\n"
			+ "<|im_start|>assistant\nAnytime! That was fun.<|im_end|>\n";

	// ------------------------------------------------------------------------------ requests
	/** What Xen can be asked to do. The chat model picks one of these words; without it, the rules below do. */
	public static final String[] INTENTS = {"follow", "stay", "explore", "wood", "stone", "coal", "iron", "mine", "food", "give",
			"shelter", "eat", "stop", "redstone", "trade", "chat"};
	/** Things it can be asked to craft (the recipe is worked out from what it carries: "boat" is an oak boat with oak planks). */
	static final String[] CRAFTABLE = {"crafting table", "pressure plate", "boats?", "chests?", "tables?", "furnaces?", "doors?",
			"torch(es)?", "sticks?", "planks?", "beds?", "ladders?", "fences?", "bowls?", "shields?", "buckets?", "pickaxes?", "swords?",
			"axes?", "shovels?", "hoes?", "signs?", "trapdoors?", "slabs?", "stairs", "buttons?", "barrels?", "campfires?", "bread"};
	private static final Pattern CRAFT_THING = Pattern.compile("\\b((wooden|wood|stone|iron|golden|gold|diamond) )?("
			+ String.join("|", CRAFTABLE) + ")\\b");
	private static final Pattern CRAFT_WORD = Pattern.compile("\\bcraft(ing)? (me |us )?(a |an |some |the |\\d+ )*([a-z_]+)");
	static final Map<String, Integer> AMOUNT = Map.of("wood", 8, "stone", 16, "coal", 8, "iron", 4, "mine", 8, "food", 3);
	private static final String[][] RULES = {                                   // the first that matches wins
			{"give", "\\b(give|hand (me|over)|pass me|toss|throw me|share|can i (have|get)|i need your)\\b"},
			{"redstone", "\\b(redstone|circuit|logic gate|(not|or|and) gate|wire)\\b"},
			{"craft", "\\b(craft|crafting)\\b|\\bmake (me |us )?(a |an |some |the |\\d+ )?((wooden|wood|stone|iron|golden|gold|diamond) )?(" + String.join("|", CRAFTABLE) + ")"},
			{"build", "\\b(build|make|dig|design) (me |us )?(a |an |our |my |the |some )?(\\w+ )?(house|home|cottage|cabin|base|bunker|hideout)\\b|\\bunderground\\b"},
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
	static final String[][] EAR_EXAMPLES = {{"come with me", "follow"}, {"nice one!", "chat"}, {"could you chop down a few trees", "wood"},
			{"keep guard right here", "stay"}, {"hand me your stuff", "give"}, {"how are you doing?", "chat"},
			{"go wander around", "explore"}, {"we need a place to hide tonight", "shelter"}, {"never mind", "stop"},
			{"go get us something to eat", "food"}, {"you're funny", "chat"}, {"i need smelting fuel", "coal"},
			{"follow my lead", "follow"}, {"grab me some cobblestone", "stone"}, {"see you later", "chat"},
			{"you look hurt, eat up", "eat"}, {"put together a little logic thing with levers", "redstone"},
			{"let's make a deal, my iron for your wood", "trade"}, {"watch this!", "chat"}};
	static final String EARS;
	/** The model's pick must beat "chat" by this much (log-probability), or it's just chat. */
	static final float SURE = 1.0f;
	static {
		for (int i = 0; i < RULES.length; i++) RULE[i] = Pattern.compile(RULES[i][1]);
		StringBuilder sb = new StringBuilder("<|im_start|>system\nYou are the ears of Xen, a Minecraft companion. Read what a player "
				+ "says to Xen and answer with the one word for what they want Xen to do: follow, stay, explore, wood, stone, coal, "
				+ "iron, mine, food, give, shelter, eat, stop, redstone, trade, or chat (only talking, thanking, praising or asking something).<|im_end|>\n");
		for (String[] e : EAR_EXAMPLES) {
			sb.append("<|im_start|>user\n").append(e[0]).append("<|im_end|>\n<|im_start|>assistant\n").append(e[1]).append("<|im_end|>\n");
		}
		EARS = sb.toString();
	}

	public static String persona() {
		return PERSONA;
	}

	public static String ears() {
		return EARS;
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
	static final String[][] THAI = {{"chat", "ขอบคุณ"}, {"trade", "แลก", "เทรด", "ซื้อ", "ขาย"}, {"stop", "หยุด", "พอแล้ว", "ยกเลิก"},
			{"stay", "ไม่ต้องตาม", "รอ", "อยู่ตรงนี้", "อยู่นี่"}, {"follow", "ตาม", "มานี่", "มาทางนี้", "มาหา"}, {"give", "ขอ", "ส่ง"},
			{"explore", "สำรวจ", "ไปเที่ยว", "ไปเล่น"}, {"redstone", "เรดสโตน", "วงจร"}, {"wood", "ไม้"}, {"coal", "ถ่าน"}, {"iron", "เหล็ก"},
			{"stone", "หิน"}, {"mine", "ขุด", "แร่", "เพชร", "ทอง"}, {"food", "อาหาร", "ล่า", "หาของกิน"},
			{"build", "สร้างบ้าน", "บ้านใต้ดิน", "ฐานใต้ดิน"}, {"shelter", "บ้าน", "ที่หลบ", "ที่พัก", "สร้าง"}, {"eat", "กิน"}};
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
		} else if (!QUESTION.matcher(words).lookingAt()) {
			for (int i = 0; i < RULE.length; i++) {
				if (RULE[i].matcher(words).find()) {
					intent = RULES[i][0];
					break;
				}
			}
		}
		return details(intent, words);
	}

	/** Questions and thanks: the chat model isn't asked to find a request in them. */
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
		if (intent.equals("build")) {                                         // a house, or a base under the ground
			thing = Pattern.compile("\\b(underground|base|bunker|hideout|dig)\\b").matcher(words).find() ? "base" : "house";
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

	private final Path modelPath;
	private final java.util.function.BooleanSupplier download;
	private final int threads;
	private final java.util.function.Supplier<String> policy;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "xen-chat");
		t.setDaemon(true);
		return t;
	});
	private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
	private volatile Llm llm;
	/**
	 * The GPU for the chat model, set by the game client (a dedicated server has none): given "force" and a log, an
	 * accelerator or null. The setting: "auto" (a real graphics card if there is one), "on" or "off".
	 */
	public static volatile java.util.function.BiFunction<Boolean, Consumer<String>, Llm.Accelerator> gpu;
	public static java.util.function.Supplier<String> gpuSetting = () -> "auto";
	/** Where the chat model runs, for /xen status and the settings: "CPU" or the graphics card's name. */
	public volatile String runsOn = "CPU";
	private volatile boolean loading;
	private volatile String problem;
	private volatile long problemAt;
	/** The chatModel setting when it last couldn't load (a new setting tries again at once). */
	private volatile String problemPolicy;
	private final Consumer<String> log;

	/** policy: "auto" (load the model when there is memory for it), "on" or "off" (read each time, so settings apply live). */
	public Chat(Path modelPath, java.util.function.Supplier<String> policy, java.util.function.BooleanSupplier download, int threads,
			Consumer<String> log) {
		this.modelPath = modelPath;
		this.policy = policy;
		this.download = download;
		this.threads = threads;
		this.log = log;
	}

	/** Whether the chat model should be used here. */
	public boolean wantsModel() {
		String p = policy.get() == null ? "auto" : policy.get().toLowerCase(Locale.ROOT);
		return switch (p) {
			case "on", "true" -> true;
			case "off", "false" -> false;
			default -> Runtime.getRuntime().maxMemory() >= AUTO_MEMORY;
		};
	}

	public boolean hasModel() {
		return llm != null;
	}

	/** The chat model's state in words, for players: ready, waking up, or off and why. */
	public String status() {
		if (llm != null) return "ready (on the " + runsOn + ")";
		if (loading) return "still waking up (that takes a minute or two)";
		String p = policy.get() == null ? "auto" : policy.get().toLowerCase(Locale.ROOT);
		if (p.equals("off") || p.equals("false")) return "turned off in the settings";
		if (!wantsModel()) {
			return "off: the game has " + (Runtime.getRuntime().maxMemory() >> 20) + " MB of memory and it needs about "
					+ (AUTO_MEMORY >> 20) + " MB (give Minecraft more memory in your launcher, or set Chat model to on)";
		}
		return problem != null ? "not available (" + problem + ")" : "not loaded yet";
	}

	/** Start loading the model in the background (it answers in plain words until it's ready). */
	public synchronized void warmUp() {
		if (llm != null || loading) return;
		String p = policy.get() == null ? "auto" : policy.get().toLowerCase(Locale.ROOT);
		boolean sameSetting = p.equals(problemPolicy);
		if (problem != null && sameSetting && System.currentTimeMillis() - problemAt < 10 * 60_000L) return;   // try again later, or when the setting changes
		problemAt = System.currentTimeMillis();
		problemPolicy = p;
		if (!wantsModel()) {
			boolean off = p.equals("off") || p.equals("false");
			problem = off ? "turned off in the settings" : "not loaded: the game has " + (Runtime.getRuntime().maxMemory() >> 20)
					+ " MB of memory and the chat model wants " + (AUTO_MEMORY >> 20) + " MB (set \"chatModel\": \"on\" in config/xen.json to load it anyway)";
			if (!off) log.accept("Xen's chat model " + problem + ". Xen still understands requests and answers in plain words.");
			return;
		}
		problem = null;
		loading = true;
		Thread t = new Thread(() -> {
			try {
				llm = model();
			} catch (Throwable e) {
				problem = e.toString();
				log.accept("Xen's chat model is not available: " + e + ". Xen still answers in plain words.");
			} finally {
				loading = false;
			}
		}, "xen-chat-loader");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	public String problem() {
		return problem;
	}

	/**
	 * Understand a player and answer, off the server thread. act gets the request (on the server thread): for something
	 * to do, Xen does it and says its plan itself (act returns null); for talk, act returns Xen's notes and reply gets
	 * the chat model's answer (or plain words from the notes), as safe chat.
	 */
	public void ask(String speaker, String message, String name, Function<Request, String> act, Consumer<String> reply) {
		if (pending.incrementAndGet() > 3) {                          // it can only keep so many things in mind
			pending.decrementAndGet();
			return;
		}
		worker.submit(() -> {
			try {
				Llm model = llm;
				Request request = understand(message, name);
				String words = requestWords(message, name);
				if (model != null && request.intent().equals("chat") && !justTalk(words)) {
					float[] s = model.choose(EARS + "<|im_start|>user\n" + words + "<|im_end|>\n<|im_start|>assistant\n", INTENTS);
					int best = 0;
					for (int i = 1; i < s.length; i++) if (s[i] > s[best]) best = i;
					if (s[best] - s[INTENTS.length - 1] >= SURE) request = details(INTENTS[best], words);
				}
				String notes = act.apply(request);
				if (notes == null) return;
				String answer = null;
				if (model != null && !PLAN.matcher(notes.trim()).find()) {   // requests are answered with the plan, in plain words
					String prompt = PERSONA + "<|im_start|>user\nNotes: " + notes + "\n" + speaker + " says: " + message
							+ "<|im_end|>\n<|im_start|>assistant\n";
					for (int attempt = 0; attempt < 2 && answer == null; attempt++) {  // made something up: try once more
						String text = safe(model.generate(prompt, 40, 0.5f, 0.9f, System.nanoTime()));
						if (honest(text, notes).equals(text) && !PROMISE.matcher(text.toLowerCase(Locale.ROOT)).find()) answer = text;
					}
				}
				reply.accept(answer != null ? answer : plainly(notes, message));
			} catch (Throwable e) {
				log.accept("Xen's chat failed: " + e);
			} finally {
				pending.decrementAndGet();
			}
		});
	}

	private Llm model() throws IOException {
		if (!Files.exists(modelPath)) {
			try (InputStream in = Chat.class.getResourceAsStream(EMBEDDED)) {     // the all-in-one jar has it inside
				if (in != null) {
					log.accept("Unpacking Xen's chat model from the mod to " + modelPath + " (only the first time)...");
					unpack(in, modelPath);
				}
			}
		}
		if (!Files.exists(modelPath)) {
			if (!download.getAsBoolean()) throw new IOException("no chat model at " + modelPath + " (downloadChatModel is off)");
			fetch();
		}
		log.accept("Loading Xen's chat model (" + modelPath.getFileName() + ")...");
		Llm model = new Llm(modelPath.toString(), threads, 1024);
		String setting = gpuSetting.get();
		runsOn = "CPU";
		var makeGpu = gpu;
		if (makeGpu != null && !"off".equals(setting)) {                // on the graphics card, if there's a good one
			try {
				Llm.Accelerator a = makeGpu.apply("on".equals(setting), log);
				if (a != null) {
					long t0 = System.nanoTime();
					if (model.useGpu(a)) {
						runsOn = a.name();
						log.accept(String.format(java.util.Locale.ROOT, "Xen's chat model runs on the GPU: %s (weights uploaded in %.1f s).",
								a.name(), (System.nanoTime() - t0) / 1e9));
					} else {
						log.accept("The GPU couldn't take the chat model (" + model.gpuProblem + "), so it runs on the CPU.");
					}
				}
			} catch (Throwable e) {
				log.accept("The chat model stays on the CPU: " + e);
			}
		}
		long t0 = System.nanoTime();
		model.savePrefix(PERSONA);
		model.savePrefix(EARS);
		log.accept(String.format(java.util.Locale.ROOT, "Xen's chat model is ready (read its prompts in %.1f s on the %s).",
				(System.nanoTime() - t0) / 1e9, model.onGpu() ? "GPU" : "CPU"));
		return model;
	}

	private void fetch() throws IOException {
		Files.createDirectories(modelPath.getParent());
		log.accept("Downloading Xen's chat model (" + MODEL + ", about 390 MB) to " + modelPath + " ...");
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
		try {
			HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() != 200) throw new IOException("download failed: HTTP " + r.statusCode());
			try (InputStream in = r.body()) {
				unpack(in, modelPath);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
		log.accept("Xen's chat model downloaded.");
	}

	/**
	 * Write the model to {@code to} from a stream (the mod jar, or a download), through a .part file, and only keep it
	 * if it's exactly the right file (its SHA-256). A cut-off or damaged copy is thrown away, never loaded.
	 */
	public static void unpack(InputStream in, Path to) throws IOException {
		Files.createDirectories(to.toAbsolutePath().getParent());
		Path part = to.resolveSibling(to.getFileName() + ".part");
		java.security.MessageDigest sha;
		try {
			sha = java.security.MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		try (InputStream checked = new java.security.DigestInputStream(in, sha)) {
			Files.copy(checked, part, StandardCopyOption.REPLACE_EXISTING);
		}
		String got = java.util.HexFormat.of().formatHex(sha.digest());
		if (!got.equals(SHA256)) {
			Files.deleteIfExists(part);
			throw new IOException("the chat model file is damaged (SHA-256 " + got + "), so it wasn't used");
		}
		Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Unload the model (nobody around for a while). It frees its memory until it's needed again. */
	public synchronized void sleep() {
		if (llm == null) return;
		Llm model = llm;
		llm = null;
		problem = null;
		worker.submit(model::close);                                   // after anything it's still saying
		log.accept("Xen's chat model is resting (nobody around to talk to).");
	}

	public void close() {
		worker.shutdownNow();
		if (llm != null) llm.close();
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
			{"\\byou (need|have|know|saw|see)\\b", "I $1"}, {"\\byourself\\b", "myself"}, {"\\bYour\\b", "My"}, {"\\byour\\b", "my"},
			{"\\bYou\\b", "I"}, {"\\byou\\b", "me"}};
	private static final Pattern PLAN = Pattern.compile("\\bPlan: (.+)$");
	/** Talk can't make it do things (only requests do), so it mustn't promise to. */
	private static final Pattern PROMISE = Pattern.compile("\\b(i'll|i will|i'm going to|let me|i can) (go |go and )?(get|build|chop|mine|dig|find|"
			+ "bring|give|follow|hunt|make|collect|gather|fetch|craft|check|look|explore|kill)\\b");

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
