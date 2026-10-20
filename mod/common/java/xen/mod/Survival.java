package xen.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a survival player knows from the wiki (assets/xen/survival.json, facts from minecraft.wiki in our own words):
 * the mobs and how to deal with them, food, weapons, armor, beds, hunger. For answers ("how do I beat an enderman?")
 * and for what it eats: the foods with a catch (rotten flesh, spider eyes, raw chicken...) only when there's nothing
 * else, and golden apples kept for a fight.
 */
final class Survival {
	private Survival() {}

	private record Fact(List<String> about, String says) {}

	private static final List<Fact> FACTS = new ArrayList<>();
	private static final Map<String, String> RISKY = new LinkedHashMap<>();
	private static final Set<String> KEEP = new HashSet<>();

	static {
		try (var in = Survival.class.getResourceAsStream("/assets/xen/survival.json")) {
			if (in != null) {
				JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : o.getAsJsonObject("risky_food").entrySet()) RISKY.put(e.getKey(), e.getValue().getAsString());
				for (var e : o.getAsJsonArray("keep_food")) KEEP.add(e.getAsString());
				for (var e : o.getAsJsonArray("facts")) {
					JsonObject f = e.getAsJsonObject();
					List<String> about = new ArrayList<>();
					for (var a : f.getAsJsonArray("about")) about.add(a.getAsString().toLowerCase(Locale.ROOT));
					FACTS.add(new Fact(about, f.get("fact").getAsString()));
				}
			}
		} catch (Exception e) {
			XenMod.LOG.warn("xen: couldn't read survival.json: {}", e.toString());
		}
	}

	/** A food with a catch (and what it is), or null. */
	static String risky(String item) {
		return RISKY.get(item);
	}

	/** A food worth more than the hunger it fills (golden apples: for a fight). */
	static boolean keep(String item) {
		return KEEP.contains(item);
	}

	static int size() {
		return FACTS.size();
	}

	/** The facts about something in these words ("creeper", "how do i fight endermen"), the best match first. */
	static List<String> about(String words) {
		String w = " " + words.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ") + " ";
		List<String> out = new ArrayList<>();
		int best = 0;
		for (Fact f : FACTS) {
			int n = 0;
			for (String a : f.about) if (w.contains(" " + a + " ")) n = Math.max(n, a.length());
			if (n == 0) continue;
			if (n > best) {
				best = n;
				out.add(0, f.says);
			} else {
				out.add(f.says);
			}
		}
		return out;
	}

	private static final java.util.regex.Pattern ASKS = java.util.regex.Pattern.compile(
			"\\b(how (do|to|can|should|does|much|long|many)|what (do|does|should|happens|is|are)|why (do|does|is|are|can|did)|when (do|does|can|is)|tips?|advice|should i|can i|is it safe|deal with|beat|survive|fight|kill)\\b");

	/** A question about surviving something it knows about ("how do i beat a creeper?"): what the wiki says, or null. */
	static String answer(String words) {
		String w = words.toLowerCase(Locale.ROOT);
		if (!ASKS.matcher(w).find() || w.matches(".*\\b(you|u) (have|got)\\b.*")) return null;   // (not "do you have food?": that's asking for some)
		List<String> f = about(w);
		return f.isEmpty() ? null : f.get(0);
	}
}
