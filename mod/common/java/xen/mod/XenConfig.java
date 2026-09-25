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
	/** Download the chat model (about 390 MB) to config/xen/ the first time it's needed. */
	public boolean downloadChatModel = true;
	/** CPU threads the chat model may use (it runs in the background). */
	public int chatThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	/** Keep learning from everything it lives through. */
	public boolean learn = true;
	/** How many Xens one player may summon (0 = no limit; operators have no limit). */
	public int maxPerPlayer = 1;
	/** How many Xens the whole world may have (0 = no limit). */
	public int maxXens = 0;
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
	/** Skins to choose from: built-in ("alex", "ari:slim", ... or "random"), or "texture:<value>:<signature>" from mineskin.org. */
	public java.util.List<String> skins = new java.util.ArrayList<>(java.util.List.of("random"));

	/** Teams: 0 = none, 1 = all Xens on one team, 2-6 = Xens split into that many teams. */
	public int teams = 1;
	/** Fighting players: "off", "defend" (fights back against players who hurt it or its owner), "teams" (also Xens of other teams). */
	public String pvp = "defend";

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
	public double followDistance = 10;
	/** Xen leaves when its owner leaves (and comes back with /xen summon, inventory kept). */
	public boolean leaveWithOwner = true;
	/** Save the brain this often (minutes). */
	public int saveMinutes = 5;

	transient Path path;

	static XenConfig load(Path path) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		XenConfig config = new XenConfig();
		try {
			if (Files.exists(path)) config = gson.fromJson(Files.readString(path), XenConfig.class);
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
