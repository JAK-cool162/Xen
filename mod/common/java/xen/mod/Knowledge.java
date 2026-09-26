package xen.mod;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a Xen knows of how the game works, and what it doesn't know yet. A few things every new player knows (trees
 * give wood, monsters come at night, creepers explode). The rest it has to find out, the ways players do: seeing it
 * happen, trying it, being told ("water on lava makes obsidian"), learning it from its tribe, or, for the clever
 * tricks (the Nether portal math, finding strongholds from two throws), by being born into a later generation.
 * What it doesn't know it doesn't do: a Xen that doesn't know end crystals heal the dragon just attacks the dragon,
 * until it sees her heal; one that doesn't know about piglins and gold goes to the Nether without gold, until a piglin
 * attacks it. Kept with the Xen.
 */
final class Knowledge {
	enum How { BORN, SEEN, TRIED, TAUGHT, TRIBE, EVOLVED }

	/** A mechanic: what knowing it means (in its own words), whether every player starts with it, how you'd tell it. */
	record Mechanic(String id, String fact, boolean born, Pattern taught) {}

	static final List<Mechanic> ALL = List.of(
			m("wood", "punching trees gives logs, and logs make planks, sticks, a crafting table and tools", true, null),
			m("pickaxes", "stone needs a pickaxe, iron a stone one, diamonds an iron one", true, null),
			m("night", "monsters spawn in the dark and at night", true, null),
			m("hunger", "food fills your hunger, and a full hunger bar heals you", true, null),
			m("lava", "lava burns you and destroys what falls in", true, null),
			m("falling", "falling more than three blocks hurts", true, null),
			m("creepers", "creepers hiss and then explode: get away", true, null),
			m("endermen", "looking an enderman in the eyes makes it attack you", true, null),
			m("beds", "sleeping in a bed skips the night and sets where you come back", true, null),
			m("totems", "a totem of undying in your hand saves your life once", true, null),
			m("ore_depth", "diamonds are deep down in the deepslate, iron is common around y 16", true, null),
			m("crops_water", "farmland needs water within four blocks, or it dries up and nothing grows", false,
					"\\b(crops?|farm(land)?|wheat|seeds?)\\b.*\\bwater\\b|\\bwater\\b.*\\b(crops?|farm(land)?|wheat)\\b"),
			m("bone_meal", "bone meal makes crops grow at once", false, "\\bbone ?meal\\b.*\\b(grow|crops?|plants?)\\b"),
			m("obsidian", "water poured onto still lava turns it into obsidian", false,
					"\\bwater\\b.*\\blava\\b.*\\bobsidian\\b|\\blava\\b.*\\bwater\\b.*\\bobsidian\\b|\\bobsidian\\b.*\\b(water|lava)\\b"),
			m("portal_math", "one block in the Nether is eight in the overworld", false,
					"\\bnether\\b.*\\b(8|eight)\\b|\\b(8|eight)\\b.*\\bnether\\b|\\b(divide|divided) by (8|eight)\\b"),
			m("triangulate", "two eye of ender throws a way apart cross where the stronghold is", false,
					"\\btriangulat|\\btwo (eyes|throws)\\b.*\\b(cross|meet)\\b"),
			m("piglin_gold", "piglins leave you alone if you wear gold", false, "\\bpiglins?\\b.*\\bgold\\b|\\bgold\\b.*\\bpiglins?\\b"),
			m("bed_blast", "beds explode in the Nether and the End", false, "\\bbeds?\\b.*\\b(explode|blow up|boom)\\b"),
			m("crystals_heal", "end crystals heal the Ender Dragon, so they go first", false,
					"\\bcrystals?\\b.*\\b(heal|first|break)\\b|\\b(break|destroy|shoot) (the )?crystals\\b"),
			m("creaking", "a creaking can't be hurt; only breaking its creaking heart kills it", false, "\\bcreaking\\b.*\\bheart\\b"),
			m("trial_keys", "trial spawners give keys, and a key opens a vault", false, "\\b(trial )?keys?\\b.*\\bvaults?\\b"),
			m("breeze", "arrows bounce off breezes: hit them up close", false, "\\bbreezes?\\b.*\\b(arrows?|bounce|deflect)\\b"),
			m("mace", "a mace hits harder the farther you fall before the hit", false, "\\bmace\\b.*\\b(fall|drop|smash|height)\\b"),
			m("sulfur", "potent sulfur under water gives off a gas that makes you sick", false, "\\bsulfur\\b.*\\b(gas|sick|nausea)\\b"),
			m("wind_charge", "a wind charge at your feet throws you up high", false, "\\bwind charges?\\b.*\\b(jump|launch|up|feet)\\b"));

	private static Mechanic m(String id, String fact, boolean born, String taught) {
		return new Mechanic(id, fact, born, taught == null ? null : Pattern.compile(taught));
	}

	static Mechanic find(String id) {
		for (Mechanic m : ALL) if (m.id.equals(id)) return m;
		return null;
	}

	private final Companion c;
	/** What it knows, and how it came to know it. */
	final Map<String, How> known = new LinkedHashMap<>();
	private String lastLearned;

	Knowledge(Companion c) {
		this.c = c;
		for (Mechanic m : ALL) if (m.born) known.put(m.id, How.BORN);
	}

	boolean knows(String id) {
		if (known.containsKey(id)) return true;
		int gen = c.personality.generation, smart = c.mod.config.smartsAtGeneration;
		if ((id.equals("portal_math") || id.equals("triangulate")) && gen >= smart
				|| (id.equals("obsidian") || id.equals("crops_water") || id.equals("crystals_heal")) && gen >= Math.max(1, smart / 2)) {
			known.put(id, How.EVOLVED);                                           // born into a later generation: it just knows
			return true;
		}
		return false;
	}

	/** It found out (or was told): a new thing it knows. True if it's new. */
	boolean learn(String id, How how) {
		if (knows(id)) return false;
		Mechanic m = find(id);
		if (m == null) return false;
		known.put(id, how);
		lastLearned = id;
		c.journal("learns", m.fact + " (" + how.name().toLowerCase(Locale.ROOT) + ")");
		XenMod.LOG.info("{} learned: {} ({})", c.name, m.fact, how.name().toLowerCase(Locale.ROOT));
		String said = switch (how) {
			case SEEN -> "Oh! I see: " + m.fact + ".";
			case TRIED -> "Huh. So " + m.fact + ". Good to know!";
			case TAUGHT -> "Thanks! So " + m.fact + ". I'll remember.";
			case TRIBE -> "My tribe taught me: " + m.fact + ".";
			default -> "";
		};
		if (!said.isEmpty()) c.chatter(said, how == How.TAUGHT);
		return true;
	}

	/** A player told it something: a mechanic it didn't know? (The first that matches.) */
	boolean heard(String words) {
		for (Mechanic m : ALL) {
			if (m.taught == null || knows(m.id) || !m.taught.matcher(words).find()) continue;
			return learn(m.id, How.TAUGHT);
		}
		return false;
	}

	/** What it doesn't know yet (for /xen knows). */
	List<Mechanic> unknown() {
		return ALL.stream().filter(m -> !knows(m.id)).toList();
	}

	/** For its notes: a little of what it knows beyond the basics (the chat answers from this). */
	String describe() {
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (var e : known.entrySet()) {
			if (e.getValue() == How.BORN) continue;
			Mechanic m = find(e.getKey());
			if (m == null) continue;
			sb.append(n++ == 0 ? "You have learned that " : "; that ").append(m.fact);
			if (n >= 3) break;
		}
		return n == 0 ? "" : sb.append('.').toString();
	}

	/** For /xen knows. */
	String status() {
		StringBuilder sb = new StringBuilder(c.name + " knows " + known.size() + " of " + ALL.size() + " things about the game.");
		for (var e : known.entrySet()) {
			if (e.getValue() == How.BORN) continue;
			sb.append(" [").append(e.getValue().name().toLowerCase(Locale.ROOT)).append("] ").append(find(e.getKey()).fact).append('.');
		}
		List<Mechanic> u = unknown();
		if (!u.isEmpty()) {
			sb.append(" Doesn't know yet:");
			for (Mechanic m : u) sb.append(" ").append(m.id).append(',');
			sb.setLength(sb.length() - 1);
			sb.append('.');
		}
		return sb.toString();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		known.forEach((k, v) -> { if (v != How.BORN) o.addProperty(k, v.name()); });
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		for (var e : o.entrySet()) {
			try {
				if (find(e.getKey()) != null) known.put(e.getKey(), How.valueOf(e.getValue().getAsString()));
			} catch (RuntimeException ignored) {
				// an old or unknown entry
			}
		}
	}
}
