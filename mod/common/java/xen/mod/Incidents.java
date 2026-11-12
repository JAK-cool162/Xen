package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * What happened on the server that people still talk about: who fell in lava, who a creeper got, who died with all
 * their diamonds, who mined every diamond in sight and shared none. Every Xen remembers the same ones (it's the
 * server's story, config/xen/incidents.json), and brings one up now and then with someone who was in it: an inside
 * joke. Each one only so often, and only small talk (heard close by), never a stream of them.
 */
final class Incidents {
	private Incidents() {}

	/** When (real time), what kind, who it was about, and the rest ("33 diamonds", "a creeper", "Ann"). */
	record Incident(long at, String kind, String who, String detail) {}

	private static final List<Incident> ALL = new ArrayList<>();
	private static Path file;
	private static final Random RANDOM = new Random();

	static synchronized void load(Path dir) {
		file = dir.resolve("incidents.json");
		ALL.clear();
		try {
			if (!Files.exists(file)) return;
			for (var e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				ALL.add(new Incident(o.get("at").getAsLong(), o.get("kind").getAsString(), o.get("who").getAsString(), o.get("detail").getAsString()));
			}
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Xen's incidents ({}) didn't load: {}", file, e.toString());
		}
	}

	/** Something happened worth remembering (the last hundred are kept). */
	static synchronized void happened(String kind, String who, String detail) {
		ALL.add(new Incident(System.currentTimeMillis(), kind, who, detail == null ? "" : detail));
		while (ALL.size() > 100) ALL.remove(0);
		save();
		XenMod.LOG.info("Incident: {} {} ({})", who, kind, detail);
	}

	/** Someone died: worth remembering if it was lava, a creeper, a long fall, the water, or someone killed them. */
	static void died(String who, String cause, int diamonds, String killer) {
		String c = cause.toLowerCase(Locale.ROOT);
		String kind = killer != null ? "killed" : c.contains("lava") ? "lava" : c.contains("creeper") || c.contains("blown up") || c.contains("blew up") ? "creeper"
				: c.contains("fell") || c.contains("hit the ground") ? "fell" : c.contains("drown") ? "drowned" : null;
		if (kind == null && diamonds < 8) return;                            // (a zombie, with nothing much on it: everyday)
		happened(kind == null ? "died" : kind, who, killer != null ? killer : diamonds > 0 ? diamonds + " diamonds" : "");
	}

	/** Its own line about one of them, about someone it's with (or its own), not one it brought up lately; null if none. */
	static synchronized String joke(String me, java.util.Collection<String> around, java.util.Set<Long> told) {
		List<Incident> fit = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Incident i : ALL) if (now - i.at > 3 * 60_000 && !told.contains(i.at) && (around.contains(i.who) || i.who.equals(me))) fit.add(i);
		if (fit.isEmpty()) return null;
		Incident i = fit.get(RANDOM.nextInt(fit.size()));
		told.add(i.at);
		boolean mine = i.who.equals(me);
		String who = mine ? "I" : i.who, with = i.detail.isEmpty() ? "" : " with " + i.detail;
		return switch (i.kind) {
			case "lava" -> mine ? pick("never letting me forget the lava thing" + with + ", are you", "I'm still not over falling in that lava" + with)
					: pick("lol remember when " + who + " fell in lava" + with + "?", "" + who + ", stay away from lava this time lol", "still thinking about " + who + " swimming in lava" + with);
			case "creeper" -> mine ? "that creeper still haunts me" : pick("remember when that creeper got " + who + "? lol", who + " vs creeper, round 2?");
			case "fell" -> mine ? "I still don't know how I fell off that" : pick("remember when " + who + " just walked off a cliff lol", who + ", careful near edges this time");
			case "drowned" -> mine ? "I really need to learn to swim" : pick("remember when " + who + " forgot how to swim?", who + " and water: not friends lol");
			case "killed" -> pick("remember when " + who + " got killed by " + (i.detail.isEmpty() ? "someone" : i.detail) + "?", "still not over what happened to " + who);
			case "hogged" -> mine ? pick("ok I may have kept a few diamonds that time", "fine, I'll share next time") : pick("still mad " + who + " took all the diamonds btw", "remember when " + who + " mined every diamond and shared NONE", who + " the diamond hog lol");
			default -> pick("remember when " + who + " died" + with + "? rough", "rip " + who + "'s " + (i.detail.isEmpty() ? "stuff" : i.detail));
		};
	}

	private static String pick(String... s) {
		return s[RANDOM.nextInt(s.length)];
	}

	private static void save() {
		if (file == null) return;
		JsonArray a = new JsonArray();
		for (Incident i : ALL) {
			JsonObject o = new JsonObject();
			o.addProperty("at", i.at);
			o.addProperty("kind", i.kind);
			o.addProperty("who", i.who);
			o.addProperty("detail", i.detail);
			a.add(o);
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, a.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			XenMod.LOG.warn("Xen's incidents ({}) weren't saved: {}", file, e.toString());
		}
	}
}
