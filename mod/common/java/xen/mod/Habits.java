package xen.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Habits a player teaches it, for their server's mods: "crouch when you mine logs" (a tree-felling mod), "crouch when
 * you mine ore" (a vein miner that works while sneaking), "crouch when you place on chests". Nothing it works out by
 * itself and nothing on unless someone asks: told in chat (its owner), or written in config/xen/habits.txt, one a line:
 * <pre>mine log: crouch
 * mine _ore: crouch
 * place *: crouch</pre>
 * Every Xen keeps them. (A mod that only listens to a key on the player's own computer can't be done: a Xen has none.)
 */
final class Habits {
	private Habits() {}

	/** When it does what (mine, place), to which blocks (a part of their name, * for any), it does this (crouch). */
	record Rule(String when, String what, String act) {
		String line() {
			return when + " " + what + ": " + act;
		}
	}

	private static final List<Rule> RULES = new ArrayList<>();
	private static Path file;

	/** Its habits from config/xen/habits.txt (made, with how it goes, if there isn't one). */
	static synchronized void load(Path dir) {
		file = dir.resolve("habits.txt");
		RULES.clear();
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(dir);
				Files.writeString(file, """
						# Habits for every Xen, for your mods (none until you add some). One a line: when, which blocks (part of the name, * for any), what it does.
						# Or tell a Xen in chat: "when you mine logs, crouch".
						# mine log: crouch
						# mine _ore: crouch
						# place *: crouch
						""", StandardCharsets.UTF_8);
			}
			for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String l = raw.trim().toLowerCase(Locale.ROOT);
				if (l.isEmpty() || l.startsWith("#")) continue;
				Matcher m = LINE.matcher(l);
				if (m.matches()) RULES.add(new Rule(m.group(1), m.group(2).trim(), m.group(3)));
			}
		} catch (IOException e) {
			XenMod.LOG.warn("Xen's habits ({}) didn't load: {}", file, e.toString());
		}
		if (!RULES.isEmpty()) XenMod.LOG.info("Xen knows {} habits from {}", RULES.size(), file);
	}

	private static final Pattern LINE = Pattern.compile("(mine|place)\\s+(\\S+)\\s*:\\s*(crouch)");
	private static final Pattern TEACH = Pattern.compile("\\b(?:when(?:ever)?|if) you (mine|break|chop|cut|dig|place|put down|put|build with) (?:a |an |the |some |any )?(.+?),? (?:always |please |then |you )*(crouch|sneak|shift)\\b"
			+ "|\\b(?:always )?(crouch|sneak|shift)(?: down)? (?:when(?:ever)?|while|if) you (mine|break|chop|cut|dig|place|put down|put|build with) (?:a |an |the |some |any )?(.+?)[.!]*$");
	private static final Pattern FORGET = Pattern.compile("\\b(forget|drop|stop) (your|all your|the|those) habits\\b|\\bno more crouching\\b");

	/** Does it do that (crouch) when it does this (mine) to this block? */
	static synchronized boolean wants(String when, String block, String act) {
		for (Rule r : RULES) if (r.when.equals(when) && r.act.equals(act) && (r.what.equals("*") || block.contains(r.what))) return true;
		return false;
	}

	/** Is someone teaching it a habit (or telling it to drop them)? */
	static boolean teaching(String words) {
		String w = words.toLowerCase(Locale.ROOT);
		return TEACH.matcher(w).find() || FORGET.matcher(w).find();
	}

	/** "When you mine logs, crouch": a new habit, kept (null if that isn't what was said). */
	static synchronized String heard(String words) {
		String w = words.toLowerCase(Locale.ROOT).trim();
		if (FORGET.matcher(w).find()) {
			if (RULES.isEmpty()) return "I don't have any habits to forget.";
			RULES.clear();
			save();
			return "Okay, no more habits.";
		}
		Matcher m = TEACH.matcher(w);
		if (!m.find()) return null;
		String doing = m.group(1) != null ? m.group(1) : m.group(5), thing = (m.group(2) != null ? m.group(2) : m.group(6)).trim();
		String when = doing.startsWith("place") || doing.startsWith("put") || doing.startsWith("build") ? "place" : "mine";
		String what = block(thing);
		Rule r = new Rule(when, what, "crouch");
		if (!RULES.contains(r)) RULES.add(r);
		save();
		return "Okay: when I " + when + " " + (what.equals("*") ? "anything" : thing) + ", I'll crouch.";
	}

	/** The part of block names someone means: "trees" are logs, "ores" any ore, "anything" any block. */
	private static String block(String thing) {
		String t = thing.replaceAll("[.!?]", "").trim();
		if (t.matches("(anything|everything|blocks?|stuff|all|any block|any blocks)")) return "*";
		if (t.matches("(trees?|wood|logs?)")) return "log";
		if (t.matches("(ores?|any ore|ore blocks?)")) return "_ore";
		if (t.endsWith("es") && t.length() > 4 && !t.endsWith("ores")) t = t.substring(0, t.length() - 2);
		else if (t.endsWith("s") && t.length() > 3) t = t.substring(0, t.length() - 1);
		return t.replace(' ', '_');
	}

	private static void save() {
		if (file == null) return;
		StringBuilder b = new StringBuilder("# Habits for every Xen, for your mods. One a line: when, which blocks (part of the name, * for any), what it does.\n");
		for (Rule r : RULES) b.append(r.line()).append('\n');
		try {
			Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			XenMod.LOG.warn("Xen's habits ({}) weren't saved: {}", file, e.toString());
		}
	}
}
