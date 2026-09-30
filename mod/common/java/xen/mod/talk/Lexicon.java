package xen.mod.talk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Xen's word library (assets/xen/words.json, or config/xen/words.json if there is one): the words it builds its
 * sentences from. Pools of words and phrases for each kind of thing it says, each maybe marked with the tones of voice
 * that like it; topics it can talk about, with how Xens tend to feel about them and what they'd say about them; chat
 * shorthand and how it's meant; which words sound good or bad; a few jokes; and how each tone writes.
 */
public final class Lexicon {
	/** Something to talk about: its name, kind, how a Xen tends to feel about it (-1..1), its plural (null: a mass noun). */
	public record Topic(String key, String cat, float like, String plural, String article, String verb, List<String> pos, List<String> neg) {
		/** How it's named when it's the subject ("creepers", "iron", "the Nether"). */
		public String many() {
			String n = plural != null ? plural : key;
			if (n.equals("nether") || n.equals("end") || n.equals("minecraft") || n.equals("pvp")) n = n.equals("pvp") ? "PvP" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
			return article == null ? n : article + " " + n;
		}

		/** "are" for creepers, "is" for iron. */
		public String be() {
			return plural != null ? "are" : "is";
		}

		/** "they" or "it". */
		public String them() {
			return plural != null ? "they" : "it";
		}
	}

	/** A word or phrase, and the tones that prefer it (empty: anyone). */
	record Entry(String text, Set<String> tones) {}

	/** How a tone writes: its end mark, how often an extra one, a smiley, lower case, a stammer, trailing off, hedging. */
	public record Style(String end, float extraEnd, float smile, float lower, float stutter, float trail, float hedge) {}

	final Map<String, String> normalize = new HashMap<>();
	final Map<String, Integer> polarity = new HashMap<>();
	final Map<String, Topic> topics = new HashMap<>();
	/** Every form of a topic's name (singular and plural) to the topic. */
	final Map<String, Topic> forms = new HashMap<>();
	final Map<String, List<Entry>> words = new HashMap<>();
	final List<String> jokes = new ArrayList<>();
	final Map<String, Style> styles = new HashMap<>();

	private static volatile Lexicon shared;
	/** Where a player's own word library goes (config/xen/words.json), if they made one. */
	public static volatile Path override;

	/** The library (the player's own, if there is one; else the one in the mod). */
	public static Lexicon get() {
		Lexicon l = shared;
		if (l != null) return l;
		synchronized (Lexicon.class) {
			if (shared != null) return shared;
			Lexicon made = null;
			Path o = override;
			if (o != null && Files.exists(o)) {
				try (Reader r = Files.newBufferedReader(o, StandardCharsets.UTF_8)) {
					made = parse(JsonParser.parseReader(r).getAsJsonObject());
				} catch (IOException | RuntimeException e) {
					made = null;                                                  // (a broken file: the mod's own words)
				}
			}
			if (made == null) {
				try (InputStream in = Lexicon.class.getResourceAsStream("/assets/xen/words.json")) {
					if (in == null) throw new IllegalStateException("words.json missing from the mod");
					made = parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}
			shared = made;
			return made;
		}
	}

	/** Read it again (after the file changed). */
	public static void reload() {
		shared = null;
	}

	static Lexicon parse(JsonObject o) {
		Lexicon l = new Lexicon();
		for (var e : o.getAsJsonObject("normalize").entrySet()) l.normalize.put(e.getKey(), e.getValue().getAsString());
		for (var e : o.getAsJsonObject("polarity").entrySet()) l.polarity.put(e.getKey(), e.getValue().getAsInt());
		for (var e : o.getAsJsonObject("topics").entrySet()) {
			JsonObject t = e.getValue().getAsJsonObject();
			Topic topic = new Topic(e.getKey(), t.get("cat").getAsString(), t.get("like").getAsFloat(),
					t.has("mass") && t.get("mass").getAsBoolean() ? null : t.has("plural") ? t.get("plural").getAsString() : e.getKey() + "s",
					t.has("article") ? t.get("article").getAsString() : null, t.has("verb") ? t.get("verb").getAsString() : null,
					strings(t, "pos"), strings(t, "neg"));
			l.topics.put(topic.key(), topic);
			l.forms.put(topic.key(), topic);
			if (topic.plural() != null) l.forms.putIfAbsent(topic.plural(), topic);
		}
		for (var e : o.getAsJsonObject("words").entrySet()) {
			List<Entry> list = new ArrayList<>();
			for (JsonElement w : e.getValue().getAsJsonArray()) {
				String s = w.getAsString();
				int bar = s.indexOf('|');
				Set<String> tones = new HashSet<>();
				if (bar >= 0) Collections.addAll(tones, s.substring(bar + 1).split(","));
				list.add(new Entry(bar >= 0 ? s.substring(0, bar) : s, tones));
			}
			l.words.put(e.getKey(), list);
		}
		for (JsonElement j : o.getAsJsonArray("jokes")) l.jokes.add(j.getAsString());
		for (var e : o.getAsJsonObject("styles").entrySet()) {
			JsonObject s = e.getValue().getAsJsonObject();
			l.styles.put(e.getKey(), new Style(s.get("end").getAsString(), s.get("extra_end").getAsFloat(), s.get("smile").getAsFloat(),
					s.get("lower").getAsFloat(), s.get("stutter").getAsFloat(), s.get("trail").getAsFloat(), s.get("hedge").getAsFloat()));
		}
		return l;
	}

	private static List<String> strings(JsonObject t, String key) {
		List<String> out = new ArrayList<>();
		if (t.has(key)) for (JsonElement x : t.getAsJsonArray(key)) out.add(x.getAsString());
		return out;
	}

	/**
	 * A word from a pool, as this tone would say it: words its tone likes are three times as likely, words another tone
	 * likes a fifth as likely, and it avoids what it said lately (so it doesn't repeat itself).
	 */
	String pick(String pool, String tone, Random r, Deque<String> recent) {
		List<Entry> list = words.get(pool);
		if (list == null || list.isEmpty()) return "";
		double[] w = new double[list.size()];
		double sum = 0;
		for (int i = 0; i < w.length; i++) {
			Entry e = list.get(i);
			w[i] = e.tones().isEmpty() ? 1 : e.tones().contains(tone) ? 3 : 0.2;
			if (recent != null && recent.contains(e.text())) w[i] *= 0.1;
			sum += w[i];
		}
		double x = r.nextDouble() * sum;
		for (int i = 0; i < w.length; i++) {
			x -= w[i];
			if (x <= 0) {
				String s = list.get(i).text();
				if (recent != null) remember(recent, s);
				return s;
			}
		}
		return list.get(w.length - 1).text();
	}

	static void remember(Deque<String> recent, String s) {
		recent.addFirst(s);
		while (recent.size() > 24) recent.removeLast();
	}

	/** A topic by any of its names ("creepers", "creeper"), or null. */
	public Topic topic(String word) {
		Topic t = forms.get(word);
		if (t == null && word.endsWith("s")) t = forms.get(word.substring(0, word.length() - 1));
		if (t == null && word.endsWith("es")) t = forms.get(word.substring(0, word.length() - 2));
		return t;
	}

	public Style style(String tone) {
		Style s = styles.get(tone == null ? "calm" : tone.toLowerCase(Locale.ROOT));
		return s != null ? s : styles.getOrDefault("calm", new Style(".", 0, 0, 0, 0, 0, 0));
	}

	/** A new memory of what it said lately. */
	static Deque<String> memory() {
		return new ArrayDeque<>();
	}
}
