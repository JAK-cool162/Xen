package xen.mod.talk;

import com.rivescript.Config;
import com.rivescript.RiveScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Small talk from scripts (RiveScript): replies written for it, not made up. One script engine for every Xen: the
 * brain that comes with the mod (assets/xen/rivescript/xen.rive) and any .rive files in config/xen/rivescript (yours:
 * new things to say, or the same things said your way; a trigger in your file wins over the mod's). Each Xen and each
 * one talking to it is its own conversation (what was said before, what it was asked), and what it knows of its life
 * (its name, what it's doing, its health, its gear, its goal) goes in as variables the replies can use.
 * <p>No reply in the scripts: null, and its own chat answers as before.
 */
public final class Rive {
	private Rive() {}

	private static RiveScript bot;
	private static Path folder;

	/** The scripts, loaded once (again with {@link #reload}): the mod's brain, then the files in config/xen/rivescript. */
	private static synchronized RiveScript bot() {
		if (bot != null) return bot;
		RiveScript b = new RiveScript(Config.Builder.utf8().unicodePunctuation("[.,!?;:]").build());
		try (InputStream in = Rive.class.getResourceAsStream("/assets/xen/rivescript/xen.rive")) {
			if (in != null) b.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			xen.mod.XenMod.LOG.warn("Xen's chat scripts didn't load: {}", e.toString());
		}
		if (folder != null && Files.isDirectory(folder)) {
			try {
				b.loadDirectory(folder.toFile(), ".rive");
			} catch (RuntimeException e) {
				xen.mod.XenMod.LOG.warn("The chat scripts in {} didn't load: {}", folder, e.toString());
			}
		}
		b.sortReplies();
		bot = b;
		return b;
	}

	/** Where your own scripts are (config/xen/rivescript). */
	public static synchronized void folder(Path dir) {
		folder = dir;
		Path mine = dir.resolve("your_replies.rive");
		if (Files.exists(mine)) return;
		try {                                                               // (a file to start from: how it goes, nothing in it yet)
			Files.createDirectories(dir);
			Files.writeString(mine, """
					! version = 2.0
					// Your own replies for every Xen (RiveScript: https://www.rivescript.com/docs/tutorial).
					// A line someone says (lower case, no punctuation; * is any words), then what a Xen says back (one picked at random):
					//
					// + do you like pizza
					// - pizza is great
					// - only with mushrooms
					//
					// What a Xen knows goes in as <get ...>: name, player, doing, place, health, food, diamonds, pick, goal, mood, time, owner, home.
					// + where is your stuff
					// - in my house at <get home>
					""");
		} catch (IOException e) {
			// (no file: the mod's own replies still work)
		}
	}

	/**
	 * A reply someone taught it ("when I say wassup, say not much"): kept in config/xen/rivescript/taught.rive, and the
	 * scripts read again so it's used from now on. False if it can't be kept.
	 */
	public static synchronized boolean teach(String heard, String say) {
		if (folder == null) return false;
		String trigger = substituted(heard.toLowerCase(java.util.Locale.ROOT)).replaceAll("[^a-z0-9 *]", " ").replaceAll("\\s+", " ").trim();
		String reply = say.replaceAll("[\\r\\n]", " ").trim();
		if (trigger.isEmpty() || reply.isEmpty() || trigger.length() > 120 || reply.length() > 200) return false;
		Path taught = folder.resolve("taught.rive");
		try {
			Files.createDirectories(folder);
			String before = Files.exists(taught) ? Files.readString(taught, StandardCharsets.UTF_8) : "! version = 2.0\n// Replies players taught their Xens in chat (\"when I say ..., say ...\").\n";
			Files.writeString(taught, before + "\n+ " + trigger + "\n- " + reply + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			return false;
		}
		reload();
		return true;
	}

	/** The scripts' own word swaps (! sub wassup = what is up), as what's said goes through them before it's matched. */
	private static java.util.Map<String, String> subs;

	private static String substituted(String words) {
		if (subs == null) {
			subs = new java.util.HashMap<>();
			try (InputStream in = Rive.class.getResourceAsStream("/assets/xen/rivescript/xen.rive")) {
				if (in != null) for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					var m = java.util.regex.Pattern.compile("^!\\s*sub\\s+(\\S+)\\s*=\\s*(.+?)\\s*$").matcher(l);
					if (m.find()) subs.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
				}
			} catch (IOException e) {
				// (none: as said)
			}
		}
		StringBuilder b = new StringBuilder();
		for (String w : words.trim().split("\\s+")) b.append(b.length() == 0 ? "" : " ").append(subs.getOrDefault(w.replaceAll("[.,!?;:]", ""), w));
		return b.toString();
	}

	/** Read the scripts again (after you change them). */
	public static synchronized void reload() {
		bot = null;
	}

	/**
	 * Its scripted reply to what someone said, or null. conversation: who talks to whom (the Xen and the one talking);
	 * facts: what the replies may use (&lt;get name&gt;, &lt;get doing&gt;...).
	 */
	public static String reply(String conversation, Map<String, String> facts, String words) {
		if (words == null || words.isBlank()) return null;
		RiveScript b;
		try {
			b = bot();
			for (var e : facts.entrySet()) b.setUservar(conversation, e.getKey(), e.getValue() == null ? "" : e.getValue());
			String r = b.reply(conversation, words.length() > 200 ? words.substring(0, 200) : words);
			if (r == null || r.isBlank() || r.startsWith("ERR:") || r.contains("undefined")) return null;
			return r.trim();
		} catch (RuntimeException e) {
			return null;                                                    // (a script that breaks: its own chat instead)
		}
	}
}
