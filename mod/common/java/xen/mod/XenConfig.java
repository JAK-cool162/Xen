package xen.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/xen.json */
public final class XenConfig {
	/** Xen chats: it understands requests ("Xen, get some wood"), answers, and says how it feels. */
	public boolean chat = true;
	/**
	 * The small chat model (SmolLM2-360M) that understands requests in any words and answers: "auto" (when the game has
	 * 3 GB of memory or more), "on" or "off". Without it Xen still understands keywords and answers in plain words.
	 */
	public String chatModel = "auto";
	/**
	 * Which chat model: "auto" (the small one on phones and with less than 3 GB for the game, else the normal one),
	 * "small" (SmolLM2 135M: about 145 MB, three times faster, simpler answers) or "normal" (SmolLM2 360M, about 390 MB).
	 */
	public String chatModelSize = "auto";
	/** Chat model on the graphics card: "auto" (when there's a real one, in a game client), "on" (even a software one) or "off". */
	public String gpu = "auto";
	/** Download the chat model (about 390 MB) to config/xen/ the first time it's needed. */
	public boolean downloadChatModel = true;
	/** CPU threads the chat model may use (it runs in the background). */
	public int chatThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	/** Keep learning from everything it lives through. */
	public boolean learn = true;
	/** How many Xens one player may summon (0 = no limit; operators have no limit). */
	public int maxPerPlayer = 1;
	/** How many Xens the whole world may have (0 = no limit; minions don't count). */
	public int maxXens = 50;
	/** How many minions (/xen minions) the whole world may have (0 = no limit). Minions don't load chunks. */
	public int maxMinions = 100;
	/** Load the chat model only when someone Xen knows is this close (blocks) or talks to it. */
	public int chatWakeDistance = 32;
	/** Unload the chat model after this many minutes with nobody around it knows and no chat. */
	public int chatIdleMinutes = 10;
	/** Leave notes on signs (if it carries signs) when nobody it knows is around. */
	public boolean signs = true;

	/** Give Xens random names (Pip, Nova, Bramble...) instead of Xen, Xen2... */
	public boolean randomNames = true;
	/** Give each Xen its own personality (brave or timid, curious, chatty, patient, and a tone of voice). */
	public boolean personalities = true;
	/** Free Xens choose their own goals (instant, short and long ones) and learn which ones they like. */
	public boolean wants = true;
	/**
	 * Xens live their own life: a new Xen starts free and plays its own game (wood, tools, a house, mining, iron,
	 * diamonds), like another player on the server; it comes along when you say "follow me".
	 */
	public boolean ownLife = true;
	/** Xens say things on their own: what they see, want and feel, greetings, and questions you can answer. */
	public boolean talk = true;
	/** Xens near each other talk, and tell each other where things are. */
	public boolean talkToXens = true;
	/** Xens trade: with villagers (the real trading screen) and with players (they bargain). */
	public boolean trading = true;
	/** Xens may say no: when it's dangerous, when they need the thing themselves, or to someone who hurt them. */
	public boolean refuse = true;
	/** Xen watches players and copies moves that work out for them (the water clutch, a winning fighting style). */
	public boolean copy = true;
	/**
	 * Path assist: its legs find the way (walk, sprint, jump up, drop down, jump gaps, swim, climb, open doors, dig
	 * through the ground, bridge, tower up out of holes). Its own mind still decides where to go and how bold to be.
	 */
	public boolean pathAssist = true;
	/**
	 * (Experiment) The solver: a second little mind that, when a Xen is stuck, picks a way out (dig up, tower up, a
	 * staircase, bridge, swim, go round, back off) and learns which ones work where, also from players it trusts.
	 */
	public boolean solver = true;
	/** (Experiment) Keep a journal of what Xens see, think, say and hear (copy it or save it from the Experiment tab). */
	public boolean journal = true;
	/** Xen does unpredictable things for fun: dances along, shows off tricks (that don't always work), surprises in fights. */
	public boolean antics = true;
	/**
	 * Tribes: Xens that live together (one owner's, a team, free ones that get on) share their things and chests, build
	 * their houses around one village, keep watch over it at night, and stand together when one of them is attacked.
	 */
	public boolean tribes = true;
	/** Xens open chests they find out in the world that nobody has opened yet (dungeons, camps, trial chambers) and loot them. */
	public boolean loot = true;
	/**
	 * Adventures: free Xens go for the Ender Dragon on their own when they're ready (the Nether for blaze rods, pearls,
	 * the stronghold, the End), and take on trial chambers they find.
	 */
	public boolean adventures = true;
	/** The generation (of evolution) from which Xens know the Nether portal math (and triangulate strongholds). */
	public int smartsAtGeneration = 4;
	/** Skins to choose from: built-in ("alex", "ari:slim", ... or "random"), or "texture:<value>:<signature>" from mineskin.org. */
	public java.util.List<String> skins = new java.util.ArrayList<>(java.util.List.of("modern"));
	/**
	 * How new Xens are named: "player" (like real players' names now: luvhi, MeeroSG, cold_lemon, Solen2009; made up,
	 * never someone's), "mixed", "fun" (SneakyWaffle), "gamer" (Pickle_42), "fantasy" (Zorbax) or "classic" (Pip).
	 */
	public String nameStyle = "player";

	/** Teams: 0 = none, 1 = all Xens on one team, 2-6 = Xens split into that many teams. */
	public int teams = 1;
	/**
	 * Fighting players: "own" (its own call: it fights back when someone attacks it or its owner with a weapon, lets a
	 * friend's mistake go, gets away when it's losing, and a poke with an empty hand only gets its attention), "off",
	 * "defend" (fights back against anyone who attacks it or its owner with a weapon), "teams" (also Xens of other teams).
	 */
	public String pvp = "own";

	/** Evolution: every few days, the Xens that did worst (only ones without an owner) are replaced by children of the best. */
	public boolean evolution = false;
	/** Days (Minecraft days) per generation. */
	public int generationDays = 3;

	/** Xen may build small redstone circuits it learned (NOT, OR, AND gates), from items it carries. */
	public boolean redstone = true;
	/** The biggest circuit it will build (parts), so nothing big enough to slow the server. */
	public int maxRedstoneParts = 24;
	/** Minimum ticks between decisions (actions last at least a quarter of a second anyway). */
	public int decisionTicks = 5;
	/** Follow the owner when further away than this. */
	public double followDistance = 4;
	/** The settings file's version (older files get new defaults where the old ones were a bad fit). */
	public int version = 6;
	/** Your own words for Xens: who they are, what they should know or do (for the chat model, and its notes). */
	public String instructions = "";
	/** Your own little script for Xens: lines like "when night: shelter" or "when hungry: say I'm starving!". */
	public String script = "";
	/** Xen leaves when its owner leaves (and comes back with /xen summon, inventory kept). */
	public boolean leaveWithOwner = true;
	/** Save the brain this often (minutes). */
	public int saveMinutes = 5;

	transient Path path;

	static XenConfig load(Path path) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		XenConfig config = new XenConfig();
		try {
			if (Files.exists(path)) {
				com.google.gson.JsonObject j = gson.fromJson(Files.readString(path), com.google.gson.JsonObject.class);
				config = gson.fromJson(j, XenConfig.class);
				if (!j.has("version") && config.followDistance == 10) config.followDistance = 4;   // it stayed too far behind
				if ((!j.has("version") || j.get("version").getAsInt() < 3) && config.pvp.equals("defend")) config.pvp = "own";   // the new default
				if (!j.has("version") || j.get("version").getAsInt() < 4) {           // 0.7: names and skins like real players now
					if (config.nameStyle.equals("mixed")) config.nameStyle = "player";
					if (config.skins.equals(java.util.List.of("random"))) config.skins = new java.util.ArrayList<>(java.util.List.of("modern"));
					if (!j.has("ownLife")) config.ownLife = true;
				}
				if (!j.has("version") || j.get("version").getAsInt() < 5) {           // 0.7: 50 Xens, 100 minions per world
					if (config.maxXens == 0) config.maxXens = 50;
					if (!j.has("maxMinions") || config.maxMinions == 8) config.maxMinions = 100;
				}
				config.version = 6;
			}
			Files.createDirectories(path.getParent());
			Files.writeString(path, gson.toJson(config));
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Could not read {}: {}", path, e.toString());
		}
		config.path = path;
		return config;
	}

	/** Change a setting by name (from /xen set or the settings screen). Returns an error, or null. */
	String set(String key, String value) {
		try {
			java.lang.reflect.Field f = XenConfig.class.getField(key);
			Class<?> t = f.getType();
			String v = value.trim();
			if (t == boolean.class) {
				if (!v.matches("(?i)true|false|on|off")) return key + " is on or off";
				f.setBoolean(this, v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on"));
			} else if (t == int.class) {
				f.setInt(this, Integer.parseInt(v));
			} else if (t == double.class) {
				f.setDouble(this, Double.parseDouble(v));
			} else if (t == String.class) {
				f.set(this, v);
			} else if (t == java.util.List.class) {
				f.set(this, new java.util.ArrayList<>(java.util.Arrays.asList(v.split("\\s*,\\s*"))));
			} else {
				return "can't set " + key;
			}
			return null;
		} catch (NoSuchFieldException e) {
			return "no setting called " + key + " (see /xen settings)";
		} catch (NumberFormatException e) {
			return key + " needs a number";
		} catch (IllegalAccessException e) {
			return "can't set " + key;
		}
	}

	/** Every setting back to its default (the settings screen's Defaults button). */
	void reset() {
		XenConfig defaults = new XenConfig();
		for (java.lang.reflect.Field f : XenConfig.class.getFields()) {
			try {
				f.set(this, f.get(defaults));
			} catch (IllegalAccessException ignored) {
				// every setting is public
			}
		}
	}

	/** Save the settings (after changing them in game). */
	void save() {
		try {
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(this));
		} catch (IOException e) {
			XenMod.LOG.warn("Could not save {}: {}", path, e.toString());
		}
	}
}
