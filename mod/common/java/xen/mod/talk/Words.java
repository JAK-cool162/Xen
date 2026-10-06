package xen.mod.talk;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Xen's words (2.0 beta 6): a vocabulary, its grammar, and a memory of what it was told.
 * <ul>
 *   <li><b>Vocabulary</b>: every word it knows and what kind of word it is (pronoun, verb, noun, adjective...), verb
 *   forms and plurals (assets/xen/vocab.json); every block, item and creature of the game, added when the world
 *   starts; and words it hears and didn't know: it guesses their kind from where they stand ("a <i>blorp</i>" is a
 *   thing) and keeps them (the world's xen/words-learned.json).</li>
 *   <li><b>Reading</b>: what was said is cut into words, chat shorthand read out ("hw r u" is "how are you"), each word
 *   tagged by the words around it ("mine" after "a" is a place, after "I" something you do), and the whole read as a
 *   sentence: a greeting, a question (yes or no, or what, where, who...), something it's told ("cats like fish"), or a
 *   request.</li>
 *   <li><b>Memory</b>: what it's told is kept as facts: who or what, how (likes, eats, is, is at...), what, and who
 *   said it. A question is answered by finding the facts that fit (retrieval), from its memory and from what the game
 *   itself knows (what a creature drops, whether something is food, where a place is it remembers).</li>
 *   <li><b>Writing</b>: the answer is built word by word, with the grammar right ("a cat likes", "cats like", "doesn't",
 *   "aren't", "a"/"an"). What it doesn't know it asks back, and it learns the answer.</li>
 * </ul>
 * No game in here (the tests run it); the game passes in what it knows through {@link Context}.
 */
public final class Words {
	public enum Pos { PRON, NOUN, VERB, AUX, ADJ, ADV, DET, PREP, WH, CONJ, NEG, INTJ, NUM }

	/** One word as read: as written, its root form (cats: cat, ate: eat), its kind, plural or not, and for an aux or an interjection what it is. */
	public record Tok(String text, String lemma, Pos pos, boolean plural, String sense) {}

	/** A thing in a sentence: its key (the root of its main word: "cat", "village", "diamond ore", or a name), how it was said, plural, what kind of thing. */
	public record Phrase(String key, String text, boolean plural, String kind) {}

	/** Nothing after the verb ("pigs can't fly"). */
	static final Phrase NOTHING = new Phrase("", "", false, null);

	public enum Act { GREET, BYE, THANKS, LAUGH, YES, NO, WOW, ASK, ASK_WH, TELL, COMMAND, OTHER }

	/**
	 * What a sentence says. subject, rel (the verb's root, "be", or "at" for where), object (or an adjective, or a
	 * place "in the forest"); wh for a what/where/who question; negated ("don't", "isn't", "never").
	 */
	public record Meaning(Act act, String wh, Phrase subject, String rel, boolean negated, Phrase object, boolean question, List<Tok> toks) {
		public boolean complete() {
			return subject != null && rel != null && object != null;
		}
	}

	// ------------------------------------------------------------------------------------------------ vocabulary
	private static final Map<String, EnumSet<Pos>> KIND = new HashMap<>();
	private static final Map<String, String> VERB_ROOT = new HashMap<>(), AUX_ROOT = new HashMap<>(), INTJ = new HashMap<>(),
			NOUN_KIND = new HashMap<>(), PLURAL = new HashMap<>(), SINGULAR = new HashMap<>(), NORMALIZE = new HashMap<>();
	private static final Map<String, String[]> VERB_FORMS = new HashMap<>();
	private static final Set<String> POSSESSIVE = Set.of("my", "your", "his", "her", "its", "our", "their");
	private static final Set<String> SUBJECT_PRON = Set.of("i", "you", "he", "she", "it", "we", "they", "someone", "everyone", "everybody", "nobody", "somebody");
	private static final Set<String> PLACE_PREP = Set.of("in", "at", "near", "by", "under", "below", "above", "behind", "inside", "outside", "beside", "next", "around", "on", "between");
	/** Words it learned (heard and didn't know): what kind it took them for, and how often it heard them. */
	private static final Map<String, Object[]> LEARNED = new LinkedHashMap<>();
	private static int longest = 1;
	private static volatile boolean loaded;
	private static volatile boolean learnedChanged;

	private Words() {}

	/** The vocabulary that ships (once). */
	public static void load() {
		if (loaded) return;
		synchronized (Words.class) {
			if (loaded) return;
			try (InputStream in = Words.class.getResourceAsStream("/assets/xen/vocab.json")) {
				if (in != null) load(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class));
			} catch (IOException | RuntimeException e) {
				// no vocabulary: it reads only what the code knows (pronouns and the like)
			}
			loaded = true;
		}
	}

	private static void kind(String w, Pos p) {
		KIND.computeIfAbsent(w, k -> EnumSet.noneOf(Pos.class)).add(p);
		longest = Math.max(longest, w.split(" ").length);
	}

	private static List<String> strings(JsonElement e) {
		List<String> out = new ArrayList<>();
		if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
		return out;
	}

	static synchronized void load(JsonObject v) {
		for (String w : strings(v.get("pron"))) kind(w, Pos.PRON);
		for (String w : strings(v.get("det"))) kind(w, Pos.DET);
		for (String w : strings(v.get("prep"))) kind(w, Pos.PREP);
		for (String w : strings(v.get("wh"))) kind(w, Pos.WH);
		for (String w : strings(v.get("neg"))) kind(w, Pos.NEG);
		for (String w : strings(v.get("conj"))) kind(w, Pos.CONJ);
		for (String w : strings(v.get("adj"))) kind(w, Pos.ADJ);
		for (String w : strings(v.get("adv"))) kind(w, Pos.ADV);
		if (v.has("aux")) for (var e : v.getAsJsonObject("aux").entrySet()) for (String w : strings(e.getValue())) {
			kind(w, Pos.AUX);
			AUX_ROOT.put(w, e.getKey());
		}
		if (v.has("intj")) for (var e : v.getAsJsonObject("intj").entrySet()) for (String w : strings(e.getValue())) {
			kind(w, Pos.INTJ);
			INTJ.putIfAbsent(w, e.getKey());
		}
		if (v.has("verbs")) for (var e : v.getAsJsonObject("verbs").entrySet()) {
			String root = e.getKey();
			List<String> f = strings(e.getValue());
			VERB_FORMS.put(root, f.toArray(new String[0]));
			kind(root, Pos.VERB);
			VERB_ROOT.put(root, root);
			for (String form : f) {
				kind(form, Pos.VERB);
				VERB_ROOT.putIfAbsent(form, root);
			}
		}
		if (v.has("plurals")) for (var e : v.getAsJsonObject("plurals").entrySet()) {
			PLURAL.put(e.getKey(), e.getValue().getAsString());
			SINGULAR.putIfAbsent(e.getValue().getAsString(), e.getKey());
		}
		if (v.has("nouns")) for (var e : v.getAsJsonObject("nouns").entrySet()) for (String w : strings(e.getValue())) noun(w, e.getKey());
		if (v.has("normalize")) for (var e : v.getAsJsonObject("normalize").entrySet()) NORMALIZE.put(e.getKey(), e.getValue().getAsString());
	}

	/** A noun (the game adds every block, item and creature name: "iron golem", "deepslate diamond ore"). */
	public static synchronized void noun(String name, String kind) {
		String n = name.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
		if (n.isEmpty()) return;
		kind(n, Pos.NOUN);
		NOUN_KIND.putIfAbsent(n, kind);
		String pl = PLURAL.computeIfAbsent(n, Words::regularPlural);
		SINGULAR.putIfAbsent(pl, n);
		kind(pl, Pos.NOUN);
	}

	static String regularPlural(String n) {
		int sp = n.lastIndexOf(' ');
		if (sp > 0) {                                                    // "iron golem": "iron golems"
			String last = n.substring(sp + 1);
			return n.substring(0, sp + 1) + PLURAL.getOrDefault(last, regularPlural(last));
		}
		if (n.matches(".*(s|x|z|ch|sh)")) return n + "es";
		if (n.matches(".*[^aeiou]y")) return n.substring(0, n.length() - 1) + "ies";
		return n + "s";
	}

	/** What kind of thing a noun is ("animal", "mob", "place", "food", "thing", "person"; null if it isn't one it knows). */
	public static String kindOf(String noun) {
		load();
		return NOUN_KIND.get(noun);
	}

	public static boolean knows(String word) {
		load();
		return KIND.containsKey(word) || LEARNED.containsKey(word);
	}

	// ------------------------------------------------------------------------------------------------ reading
	/** What was said, as words (the Xen's own name taken out, shorthand read out, two- and three-word names as one). */
	public static List<Tok> read(String text, String self) {
		load();
		String t = " " + text.toLowerCase(Locale.ROOT).replace('’', '\'') + " ";
		if (self != null && !self.isBlank()) t = t.replaceAll("@?\\b" + java.util.regex.Pattern.quote(self.toLowerCase(Locale.ROOT)) + "\\b,?", " ");
		List<String> raw = new ArrayList<>();
		for (String w : t.split("[^a-z0-9'\\-.]+")) {
			w = w.matches("-\\d+") ? w : w.replaceAll("^['.\\-]+|['.\\-]+$", "");
			if (w.isEmpty()) continue;
			String n = NORMALIZE.get(w);
			if (n == null && w.matches("[a-z]+'s") && !KIND.containsKey(w)) {      // "steve's": steve
				n = w.substring(0, w.length() - 2);
			}
			if (n == null) raw.add(w);
			else for (String x : n.split(" ")) if (!x.isEmpty()) raw.add(x);
		}
		List<String> joined = new ArrayList<>();                            // "iron golem", "nether portal": one word
		for (int i = 0; i < raw.size(); ) {
			int took = 1;
			for (int k = Math.min(longest, raw.size() - i); k >= 2; k--) {
				String w = String.join(" ", raw.subList(i, i + k));
				EnumSet<Pos> p = KIND.get(w);
				if (p != null && p.contains(Pos.NOUN)) {
					took = k;
					break;
				}
			}
			joined.add(String.join(" ", raw.subList(i, i + took)));
			i += took;
		}
		List<Tok> out = new ArrayList<>();
		for (int i = 0; i < joined.size(); i++) out.add(tag(joined, i, out));
		return out;
	}

	private static boolean verbish(String w) {
		return VERB_ROOT.containsKey(w) || verbRoot(w) != null;
	}

	private static boolean nounish(String w) {
		EnumSet<Pos> p = KIND.get(w);
		if (p != null && p.contains(Pos.NOUN)) return true;
		Object[] l = LEARNED.get(w);
		return l != null && l[0] == Pos.NOUN || SINGULAR.containsKey(w);
	}

	/** A regular verb form's root, if the root is a verb it knows ("crafts", "mined", "digging"). */
	static String verbRoot(String w) {
		String r = VERB_ROOT.get(w);
		if (r != null) return r;
		String[] tries;
		if (w.endsWith("ies")) tries = new String[] {w.substring(0, w.length() - 3) + "y"};
		else if (w.endsWith("ing") && w.length() > 4) {
			String s = w.substring(0, w.length() - 3);
			tries = new String[] {s, s + "e", s.length() > 2 && s.charAt(s.length() - 1) == s.charAt(s.length() - 2) ? s.substring(0, s.length() - 1) : s};
		} else if (w.endsWith("ied")) tries = new String[] {w.substring(0, w.length() - 3) + "y"};
		else if (w.endsWith("ed") && w.length() > 3) {
			String s = w.substring(0, w.length() - 2);
			tries = new String[] {s, s + "e", s.length() > 2 && s.charAt(s.length() - 1) == s.charAt(s.length() - 2) ? s.substring(0, s.length() - 1) : s};
		} else if (w.endsWith("es")) tries = new String[] {w.substring(0, w.length() - 2), w.substring(0, w.length() - 1)};
		else if (w.endsWith("s") && !w.endsWith("ss")) tries = new String[] {w.substring(0, w.length() - 1)};
		else return null;
		for (String s : tries) if (VERB_FORMS.containsKey(s)) return s;
		return null;
	}

	/** A noun's singular ("wolves": wolf, "creepers": creeper, "diamonds": diamond). */
	static String nounRoot(String w) {
		String s = SINGULAR.get(w);
		if (s != null && !s.equals(w)) return s;
		if (KIND.containsKey(w) && KIND.get(w).contains(Pos.NOUN) && !w.endsWith("s")) return w;
		if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
		if (w.matches(".*(ses|xes|zes|ches|shes)") && w.length() > 4) return w.substring(0, w.length() - 2);
		if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) return w.substring(0, w.length() - 1);
		return w;
	}

	private static boolean pluralNoun(String w) {
		String s = SINGULAR.get(w);
		if (s != null) return !s.equals(w) || PLURAL.getOrDefault(w, "").equals(w) && false;
		return w.endsWith("s") && !w.endsWith("ss") && !KIND.containsKey(w) && w.length() > 3;
	}

	private static Tok tok(String w, Pos p) {
		return switch (p) {
			case NOUN -> new Tok(w, nounRoot(w), p, pluralNoun(w), NOUN_KIND.get(nounRoot(w)));
			case VERB -> new Tok(w, verbRoot(w) == null ? w : verbRoot(w), p, false, null);
			case AUX -> new Tok(w, AUX_ROOT.getOrDefault(w, w), p, false, AUX_ROOT.getOrDefault(w, w));
			case INTJ -> new Tok(w, w, p, false, INTJ.get(w));
			default -> new Tok(w, w, p, false, null);
		};
	}

	/** The kind of word w[i] is, from what it can be and the words before it (and the next one). */
	private static Tok tag(List<String> w, int i, List<Tok> before) {
		String x = w.get(i), next = i + 1 < w.size() ? w.get(i + 1) : null;
		Tok prev = before.isEmpty() ? null : before.get(before.size() - 1);
		Pos pp = prev == null ? null : prev.pos();
		if (x.matches("-?\\d+")) return new Tok(x, x, Pos.NUM, false, null);
		EnumSet<Pos> can = KIND.getOrDefault(x, EnumSet.noneOf(Pos.class));
		if (can.contains(Pos.AUX)) {
			String root = AUX_ROOT.get(x);
			boolean mainVerb = (root.equals("have") || root.equals("do")) && next != null && !next.equals("not") && !verbish(next)
					&& pp != null && (pp == Pos.PRON || pp == Pos.NOUN);     // "cats have tails", "I do stuff": a verb
			if (!mainVerb) return tok(x, Pos.AUX);
			return new Tok(x, root, Pos.VERB, false, null);
		}
		if (can.contains(Pos.WH) && (i == 0 || pp == Pos.INTJ || pp == Pos.CONJ || pp == Pos.VERB || pp == Pos.PREP)) return tok(x, Pos.WH);
		boolean start = i == 0 || pp == Pos.INTJ || pp == Pos.CONJ;
		if (can.contains(Pos.INTJ) && (start || before.stream().allMatch(t -> t.pos() == Pos.INTJ))
				&& !(x.equals("like") || x.equals("cool") || x.equals("nice") || x.equals("right") || x.equals("sure")) || can.size() == 1 && can.contains(Pos.INTJ)) return tok(x, Pos.INTJ);
		if (x.equals("no") && next != null && nounish(next)) return tok(x, Pos.DET);
		if (can.contains(Pos.NEG)) return tok(x, Pos.NEG);
		if (x.equals("to") && next != null && verbish(next) && !nounish(next)) return tok(x, Pos.PREP);
		if (can.contains(Pos.DET) && !(x.equals("lot") || x.equals("lots")) && next != null) return tok(x, Pos.DET);
		if (can.contains(Pos.PRON) && !(x.equals("mine") && (pp == Pos.DET || pp == Pos.ADJ || pp == Pos.PRON && SUBJECT_PRON.contains(prev.text()) || POSSESSIVE.contains(pp == null ? "" : prev.text()) || start && next != null && !next.equals("is"))))
			return tok(x, Pos.PRON);
		if (can.contains(Pos.PREP) && !(x.equals("like") && (pp == Pos.PRON || pp == Pos.NOUN || pp == Pos.ADV || pp == Pos.NEG || pp == Pos.AUX && !"be".equals(prev.sense())))
				&& !(x.equals("light") || x.equals("past")) && !can.contains(Pos.NOUN) && !can.contains(Pos.VERB) || can.size() == 1 && can.contains(Pos.PREP))
			return tok(x, Pos.PREP);
		if (can.contains(Pos.CONJ)) return tok(x, Pos.CONJ);
		boolean noun = nounish(x), verb = verbish(x), adj = can.contains(Pos.ADJ), adv = can.contains(Pos.ADV);
		boolean afterDet = pp == Pos.DET || pp == Pos.ADJ || pp == Pos.PREP || pp == Pos.NUM || prev != null && POSSESSIVE.contains(prev.text());
		boolean afterSubject = pp == Pos.PRON && SUBJECT_PRON.contains(prev.text()) || pp == Pos.NOUN
				|| pp == Pos.AUX && !"be".equals(prev.sense()) || pp == Pos.NEG || prev != null && prev.text().equals("to") && pp == Pos.PREP || pp == Pos.ADV && before.size() >= 2;
		boolean afterBe = pp == Pos.AUX && "be".equals(prev.sense()) || pp == Pos.ADV && before.size() >= 2 && before.get(before.size() - 2).pos() == Pos.AUX;
		if (afterBe) {                                                    // "is scary", "is a mob", "is mining", "is at"
			if (adj && !(noun && next == null && !adj)) return tok(x, Pos.ADJ);
			if (verb && x.endsWith("ing")) return tok(x, Pos.VERB);
			if (noun) return tok(x, Pos.NOUN);
			if (adv) return tok(x, Pos.ADV);
		}
		if (afterDet) {
			if (adj && next != null && nounish(next)) return tok(x, Pos.ADJ);
			if (noun) return tok(x, Pos.NOUN);
			if (adj) return tok(x, Pos.ADJ);
		}
		if (verb && afterSubject && !(pp == Pos.NOUN && noun && !before.isEmpty() && before.stream().anyMatch(t -> t.pos() == Pos.VERB))) return tok(x, Pos.VERB);
		if (start && verb && (!noun || next != null && !verbish(next) && KIND.getOrDefault(next, EnumSet.noneOf(Pos.class)).stream().noneMatch(k -> k == Pos.AUX)))
			return tok(x, Pos.VERB);                                       // "mine diamonds", "come here": a request
		if (noun) return tok(x, Pos.NOUN);
		if (adj) return tok(x, Pos.ADJ);
		if (verb) return tok(x, Pos.VERB);
		if (adv) return tok(x, Pos.ADV);
		// a word it doesn't know: what it must be, here; it learns it
		Pos guess = x.endsWith("ly") && x.length() > 4 ? Pos.ADV
				: (x.endsWith("ing") || x.endsWith("ed")) && x.length() > 4 && !afterDet ? Pos.VERB
				: afterDet ? Pos.NOUN
				: afterSubject && before.stream().noneMatch(t -> t.pos() == Pos.VERB || t.pos() == Pos.AUX && "be".equals(t.sense())) ? Pos.VERB
				: afterBe ? Pos.ADJ : Pos.NOUN;
		learn(x, guess);
		return guess == Pos.VERB ? new Tok(x, x.replaceAll("(ing|ed|s)$", ""), Pos.VERB, false, null) : tok(x, guess);
	}

	private static synchronized void learn(String w, Pos p) {
		if (w.length() < 2 || w.length() > 24 || w.matches(".*\\d.*")) return;
		Object[] l = LEARNED.computeIfAbsent(w, k -> new Object[] {p, 0});
		l[1] = (int) l[1] + 1;
		learnedChanged = true;
		if (LEARNED.size() > 3000) {                                        // (a few thousand, the oldest go)
			var it = LEARNED.keySet().iterator();
			it.next();
			it.remove();
		}
	}

	/** Words it has learned (for "how many words do you know"). */
	public static int learnedCount() {
		return LEARNED.size();
	}

	public static int vocabularySize() {
		load();
		return KIND.size() + LEARNED.size();
	}

	public static synchronized void loadLearned(Path file) {
		LEARNED.clear();
		if (file == null || !Files.exists(file)) return;
		try {
			JsonObject o = new Gson().fromJson(Files.readString(file), JsonObject.class);
			for (var e : o.entrySet()) {
				JsonObject w = e.getValue().getAsJsonObject();
				LEARNED.put(e.getKey(), new Object[] {Pos.valueOf(w.get("pos").getAsString()), w.get("heard").getAsInt()});
			}
		} catch (IOException | RuntimeException e) {
			// a damaged file: it starts again
		}
		learnedChanged = false;
	}

	public static synchronized void saveLearned(Path file) throws IOException {
		if (!learnedChanged || file == null) return;
		JsonObject o = new JsonObject();
		for (var e : LEARNED.entrySet()) {
			JsonObject w = new JsonObject();
			w.addProperty("pos", e.getValue()[0].toString());
			w.addProperty("heard", (int) e.getValue()[1]);
			o.add(e.getKey(), w);
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, o.toString());
		learnedChanged = false;
	}

	// ------------------------------------------------------------------------------------------------ grammar
	/** The phrase for a thing from toks[i]: [the/a/some] [adjectives] noun (or a pronoun, a name, a number); end index in end[0]. */
	private static Phrase phrase(List<Tok> t, int i, int[] end) {
		int j = i;
		StringBuilder text = new StringBuilder();
		while (j < t.size() && (t.get(j).pos() == Pos.DET || t.get(j).pos() == Pos.NUM && j + 1 < t.size() && t.get(j + 1).pos() == Pos.NOUN
				|| t.get(j).pos() == Pos.PRON && POSSESSIVE.contains(t.get(j).text()))) text.append(t.get(j++).text()).append(' ');
		List<String> adj = new ArrayList<>();
		while (j < t.size() && (t.get(j).pos() == Pos.ADJ || t.get(j).pos() == Pos.ADV && j + 1 < t.size() && t.get(j + 1).pos() == Pos.ADJ)) {
			adj.add(t.get(j).text());
			text.append(t.get(j++).text()).append(' ');
		}
		if (j < t.size() && (t.get(j).pos() == Pos.NOUN || t.get(j).pos() == Pos.PRON && text.isEmpty() && adj.isEmpty())) {
			Tok n = t.get(j);
			text.append(n.text());
			end[0] = j + 1;
			while (end[0] < t.size() && t.get(end[0]).pos() == Pos.NOUN) {     // "diamond pickaxe" told as two words it knows apart
				text.append(' ').append(t.get(end[0]).text());
				n = t.get(end[0]);
				end[0]++;
			}
			String key = n.pos() == Pos.PRON ? n.text() : text.toString().replaceAll("^(?:(?:the|a|an|some|any|my|your|his|her|its|our|their|\\d+)\\s+)+", "")
					.replaceAll("(?:^|\\s)(?:" + String.join("|", adj) + ")\\s", " ").trim();
			if (n.pos() == Pos.NOUN) {
				String[] parts = key.split(" ");
				parts[parts.length - 1] = nounRoot(parts[parts.length - 1]);
				key = String.join(" ", parts);
				if (!KIND.containsKey(key) && KIND.containsKey(n.lemma())) key = n.lemma();
			}
			boolean plural = n.plural() || n.pos() == Pos.PRON && (n.text().equals("they") || n.text().equals("them") || n.text().equals("we") || n.text().equals("these") || n.text().equals("those"));
			return new Phrase(key, text.toString().trim(), plural, n.pos() == Pos.NOUN ? NOUN_KIND.get(key) : "pron");
		}
		if (!adj.isEmpty() && j == i + adj.size()) {                        // just adjectives: "scary", "really big"
			end[0] = j;
			return new Phrase(adj.get(adj.size() - 1), text.toString().trim(), false, "adj");
		}
		end[0] = i;
		return null;
	}

	/** What comes after the verb: a thing, an adjective, a place ("in the forest", "at 100 64 200"), or the rest as said. */
	private static Phrase complement(List<Tok> t, int i) {
		if (i >= t.size()) return null;
		int[] end = {i};
		if (t.get(i).pos() == Pos.PREP && !t.get(i).text().equals("to") && !t.get(i).text().equals("like")) {
			StringBuilder sb = new StringBuilder(t.get(i).text());
			List<String> nums = new ArrayList<>();
			for (int k = i + 1; k < t.size() && t.get(k).pos() == Pos.NUM; k++) nums.add(t.get(k).text());
			if (nums.size() >= 2) return new Phrase(String.join(" ", nums), sb + " " + String.join(" ", nums), false, "coords");
			Phrase p = phrase(t, i + 1, end);
			if (p != null && end[0] < t.size() && t.get(end[0]).pos() == Pos.NUM) {   // "at y -58"
				StringBuilder n = new StringBuilder();
				for (int k = end[0]; k < t.size() && t.get(k).pos() == Pos.NUM; k++) n.append(' ').append(t.get(k).text());
				return new Phrase(p.key() + n, sb + " " + p.text() + n, false, "place");
			}
			if (p != null) return new Phrase(p.key(), sb + " " + p.text(), p.plural(), "place");
			return null;
		}
		List<String> nums = new ArrayList<>();
		for (int k = i; k < t.size() && t.get(k).pos() == Pos.NUM; k++) nums.add(t.get(k).text());
		if (nums.size() >= 2) return new Phrase(String.join(" ", nums), "at " + String.join(" ", nums), false, "coords");
		Phrase p = phrase(t, i, end);
		if (p != null && end[0] < t.size() && t.get(end[0]).pos() == Pos.CONJ && t.get(end[0]).text().equals("and")) {   // "fish and chicken"
			int[] e2 = {end[0] + 1};
			Phrase q = phrase(t, end[0] + 1, e2);
			if (q != null) return new Phrase(p.key(), p.text() + " and " + q.text(), true, p.kind());
		}
		if (p != null) return p;
		if (t.get(i).pos() == Pos.ADV && (t.get(i).text().equals("here") || t.get(i).text().equals("there") || t.get(i).text().equals("underground")))
			return new Phrase(t.get(i).text(), t.get(i).text(), false, "place");
		if (t.get(i).pos() == Pos.PREP && t.get(i).text().equals("to") && i + 1 < t.size() && t.get(i + 1).pos() == Pos.VERB) {   // "like to swim"
			StringBuilder sb = new StringBuilder("to");
			for (int k = i + 1; k < t.size() && t.get(k).pos() != Pos.CONJ; k++) sb.append(' ').append(t.get(k).text());
			return new Phrase(t.get(i + 1).lemma(), sb.toString(), false, "doing");
		}
		return null;
	}

	/** What a sentence says (its grammar read). */
	public static Meaning parse(String text, String self) {
		List<Tok> t = new ArrayList<>(read(text, self));
		boolean q = text.contains("?");
		while (!t.isEmpty() && (t.get(0).pos() == Pos.CONJ || t.get(0).text().equals("please") || t.get(0).text().equals("well") || t.get(0).text().equals("so"))) t.remove(0);
		if (t.isEmpty()) return new Meaning(Act.OTHER, null, null, null, false, null, q, t);
		if (t.stream().allMatch(x -> x.pos() == Pos.INTJ || x.pos() == Pos.ADV)) {   // "hi", "lol", "yes", "thanks"
			String sense = t.get(0).pos() == Pos.INTJ ? t.get(0).sense() : null;
			Act a = sense == null ? Act.OTHER : switch (sense) {
				case "greet" -> Act.GREET;
				case "bye" -> Act.BYE;
				case "thanks" -> Act.THANKS;
				case "laugh" -> Act.LAUGH;
				case "yes" -> Act.YES;
				case "no" -> Act.NO;
				default -> Act.WOW;
			};
			return new Meaning(a, null, null, null, false, null, q, t);
		}
		Act lead = null;
		if (t.get(0).pos() == Pos.INTJ) {                                   // "hey, cats like fish", "yeah they do"
			String s = t.get(0).sense();
			lead = "yes".equals(s) ? Act.YES : "no".equals(s) ? Act.NO : null;
			while (!t.isEmpty() && t.get(0).pos() == Pos.INTJ) t.remove(0);
		}
		int[] end = {0};
		if (t.get(0).pos() == Pos.WH) {                                     // what / where / who / how / why ...
			String wh = t.get(0).text();
			int i = 1;
			if (i < t.size() && (t.get(i).text().equals("many") || t.get(i).text().equals("much"))) return new Meaning(Act.OTHER, wh, null, null, false, null, true, t);
			if (i < t.size() && t.get(i).pos() == Pos.AUX) {
				String aux = t.get(i).sense();
				i++;
				boolean neg = i < t.size() && t.get(i).pos() == Pos.NEG;
				if (neg) i++;
				Phrase s = phrase(t, i, end);
				if (s == null) return new Meaning(Act.OTHER, wh, null, null, neg, null, true, t);
				i = end[0];
				if (aux.equals("be")) {
					if (i < t.size() && t.get(i).pos() == Pos.ADJ && i + 1 < t.size() && t.get(i + 1).pos() == Pos.PREP)   // "what are creepers scared of"
						return new Meaning(Act.ASK_WH, wh, s, t.get(i).text() + " " + t.get(i + 1).text(), neg, null, true, t);
					if (i < t.size() && t.get(i).pos() == Pos.VERB)                  // "what is it doing": not about a thing
						return new Meaning(Act.OTHER, wh, s, t.get(i).lemma(), neg, null, true, t);
					return new Meaning(Act.ASK_WH, wh, s, wh.equals("where") ? "at" : "be", neg, null, true, t);
				}
				if (i < t.size() && t.get(i).pos() == Pos.AUX && "do".equals(t.get(i).sense()) && wh.equals("what"))   // "what do zombies do": all it knows about them
					return new Meaning(Act.ASK_WH, wh, s, "do", neg, null, true, t);
				if (i < t.size() && t.get(i).pos() == Pos.VERB) {               // "what do cats eat", "where do cats live"
					String rel = t.get(i).lemma();
					Phrase o = complement(t, i + 1);
					return new Meaning(Act.ASK_WH, wh, s, rel, neg, o, true, t);
				}
				return new Meaning(Act.OTHER, wh, s, null, neg, null, true, t);
			}
			if (i < t.size() && t.get(i).pos() == Pos.VERB) {                   // "what drops leather", "who made you"
				String rel = t.get(i).lemma();
				Phrase o = complement(t, i + 1);
				return new Meaning(Act.ASK_WH, wh, null, rel, false, o, true, t);
			}
			return new Meaning(Act.OTHER, wh, null, null, false, null, true, t);
		}
		if (t.get(0).pos() == Pos.AUX) {                                    // do cats like fish? is the village far? can cows swim?
			String aux = t.get(0).sense();
			int i = 1;
			boolean neg = i < t.size() && t.get(i).pos() == Pos.NEG;
			if (neg) i++;
			Phrase s = phrase(t, i, end);
			if (s == null) return new Meaning(Act.OTHER, null, null, null, neg, null, true, t);
			i = end[0];
			if (i < t.size() && t.get(i).pos() == Pos.NEG) {
				neg = true;
				i++;
			}
			if (aux.equals("be")) {
				Phrase o = complement(t, i);
				String rel = o != null && ("place".equals(o.kind()) || "coords".equals(o.kind())) ? "at" : "be";
				return new Meaning(Act.ASK, null, s, rel, neg, o, true, t);
			}
			if (i < t.size() && t.get(i).pos() == Pos.VERB) {
				String rel = (aux.equals("can") ? "can " : "") + t.get(i).lemma();
				Phrase o = complement(t, i + 1);
				return new Meaning(Act.ASK, null, s, rel, neg, o == null ? NOTHING : o, true, t);
			}
			return new Meaning(Act.OTHER, null, s, null, neg, null, true, t);
		}
		if (t.get(0).pos() == Pos.VERB && !t.get(0).text().endsWith("s")) return new Meaning(Act.COMMAND, null, null, t.get(0).lemma(), false, complement(t, 1), q, t);
		Phrase s = phrase(t, 0, end);                                         // something it's told: cats like fish, the village is at 100 64 200
		if (s == null) return new Meaning(lead == null ? Act.OTHER : lead, null, null, null, false, null, q, t);
		int i = end[0];
		boolean neg = false;
		String modal = "";
		while (i < t.size() && (t.get(i).pos() == Pos.ADV && !t.get(i).text().equals("here") && !t.get(i).text().equals("there"))) i++;   // "cats really like fish"
		if (i < t.size() && t.get(i).pos() == Pos.AUX && !"be".equals(t.get(i).sense())) {
			if (t.get(i).sense().equals("can")) modal = "can ";
			i++;
			if (i < t.size() && t.get(i).pos() == Pos.NEG) {
				neg = true;
				i++;
			}
		}
		while (i < t.size() && t.get(i).pos() == Pos.ADV) i++;
		if (i < t.size() && t.get(i).pos() == Pos.AUX && "be".equals(t.get(i).sense())) {
			i++;
			if (i < t.size() && t.get(i).pos() == Pos.NEG) {
				neg = true;
				i++;
			}
			while (i < t.size() && t.get(i).pos() == Pos.ADV && !t.get(i).text().equals("here") && !t.get(i).text().equals("there")) i++;
			if (i < t.size() && t.get(i).pos() == Pos.VERB && t.get(i).text().endsWith("ing"))   // "I am mining": what someone's doing, not a fact
				return new Meaning(lead == null ? Act.OTHER : lead, null, s, t.get(i).lemma(), neg, null, q, t);
			Phrase o = complement(t, i);
			String rel = o != null && ("place".equals(o.kind()) || "coords".equals(o.kind())) ? "at" : "be";
			return new Meaning(q ? Act.ASK : Act.TELL, null, s, rel, neg, o, q, t);
		}
		if (i < t.size() && t.get(i).pos() == Pos.NEG) {
			neg = true;
			i++;
		}
		if (i < t.size() && t.get(i).pos() == Pos.VERB) {
			String rel = modal + t.get(i).lemma();
			Phrase o = complement(t, i + 1);
			if (o == null && i + 1 >= t.size()) o = NOTHING;                     // "pigs can't fly", "creepers explode": nothing after the verb
			return new Meaning(q ? Act.ASK : Act.TELL, null, s, rel, neg, o, q, t);
		}
		return new Meaning(lead == null ? Act.OTHER : lead, null, s, null, neg, null, q, t);
	}

	// ------------------------------------------------------------------------------------------------ memory
	/**
	 * What it was told (or found out), as facts: subject, how, object, and who said it. Questions are answered from
	 * the facts that fit (retrieval); a fact told again is refreshed, one told the other way round replaces the old.
	 */
	public static final class Memory {
		public record Fact(String subject, String subjectText, boolean plural, String rel, boolean negated, String object, String objectText, String source, long time) {}

		public static final int NEW = 0, KNOWN = 1, CHANGED = -1, CAP = 400;
		private final List<Fact> facts = new ArrayList<>();

		static boolean same(String a, String b) {
			if (a == null || b == null) return a == b;
			return a.equals(b) || nounRoot(a).equals(nounRoot(b));
		}

		/** Keep a fact: NEW, KNOWN (it knew), or CHANGED (it had it the other way round: now it's this way). */
		public synchronized int tell(Fact f) {
			for (int i = 0; i < facts.size(); i++) {
				Fact g = facts.get(i);
				if (same(g.subject(), f.subject()) && g.rel().equals(f.rel()) && same(g.object(), f.object())) {
					facts.remove(i);
					facts.add(f);
					return g.negated() == f.negated() ? KNOWN : CHANGED;
				}
			}
			facts.add(f);
			if (facts.size() > CAP) facts.remove(0);
			return NEW;
		}

		/** The facts about subject (and rel, and object, when given), newest first. */
		public synchronized List<Fact> recall(String subject, String rel, String object) {
			List<Fact> out = new ArrayList<>();
			for (int i = facts.size() - 1; i >= 0; i--) {
				Fact f = facts.get(i);
				if (subject != null && !same(f.subject(), subject)) continue;
				if (rel != null && !f.rel().equals(rel)) continue;
				if (object != null && !same(f.object(), object)) continue;
				out.add(f);
			}
			return out;
		}

		/** One fact this memory has that the other doesn't (to share), or null. */
		public synchronized Fact newTo(Memory other) {
			for (int i = facts.size() - 1; i >= 0; i--) {
				Fact f = facts.get(i);
				if (f.source().startsWith("me")) continue;
				if (other.recall(f.subject(), f.rel(), f.object()).isEmpty()) return f;
			}
			return null;
		}

		public synchronized int size() {
			return facts.size();
		}

		public synchronized JsonArray toJson() {
			JsonArray a = new JsonArray();
			for (Fact f : facts) {
				JsonObject o = new JsonObject();
				o.addProperty("s", f.subject());
				o.addProperty("st", f.subjectText());
				o.addProperty("pl", f.plural());
				o.addProperty("r", f.rel());
				o.addProperty("neg", f.negated());
				o.addProperty("o", f.object());
				o.addProperty("ot", f.objectText());
				o.addProperty("by", f.source());
				o.addProperty("t", f.time());
				a.add(o);
			}
			return a;
		}

		public synchronized void load(JsonArray a) {
			facts.clear();
			for (JsonElement e : a) {
				try {
					JsonObject o = e.getAsJsonObject();
					facts.add(new Fact(o.get("s").getAsString(), o.get("st").getAsString(), o.get("pl").getAsBoolean(), o.get("r").getAsString(),
							o.get("neg").getAsBoolean(), o.get("o").getAsString(), o.get("ot").getAsString(), o.get("by").getAsString(), o.get("t").getAsLong()));
				} catch (RuntimeException ignored) {
					// a fact it can't read: forgotten
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ writing
	/** "a" or "an" for a word. */
	public static String a(String word) {
		return word.matches("(?i)[aeiou].*") && !word.matches("(?i)(uni|use|eu|one).*") ? "an" : "a";
	}

	/** A verb in the form its subject needs ("likes" for a cat, "like" for cats). */
	static String verbFor(String root, boolean third) {
		if (!third) return root;
		String[] f = VERB_FORMS.get(root);
		if (f != null && f.length > 0) return f[0];
		if (root.matches(".*(s|x|z|ch|sh|o)")) return root + "es";
		if (root.matches(".*[^aeiou]y")) return root.substring(0, root.length() - 1) + "ies";
		return root + "s";
	}

	/**
	 * A sentence: subject (as said, or "I"/"you"), how (a verb's root, "be", "at", "can swim"), negated, and the rest
	 * ("fish", "scary", "at 100 64 200"), with the grammar right: "cats like fish", "a cat likes fish", "the village is at
	 * ...", "creepers aren't friendly", "you don't like cats", "I can't swim".
	 */
	public static String sentence(String subject, boolean plural, String rel, boolean negated, String rest) {
		String s = subject.trim();
		String lower = s.toLowerCase(Locale.ROOT);
		boolean me = lower.equals("i"), you = lower.equals("you"), many = plural || lower.equals("we") || lower.equals("they");
		boolean third = !me && !you && !many;
		String r = rest == null || rest.isBlank() ? "" : " " + rest.trim();
		if (rel.equals("be") || rel.equals("at") || rel.contains(" ") && !rel.startsWith("can ")) {   // be (and "scared of": be + adjective + of)
			String be = me ? "am" : third ? "is" : "are";
			String b = negated ? (me ? "'m not" : third ? " isn't" : " aren't") : (me ? "'m" : " " + be);
			if (me && !negated) b = " am";
			String extra = rel.equals("be") || rel.equals("at") ? "" : " " + rel;
			return (me ? "I" : s) + b + extra + r;
		}
		if (rel.startsWith("can ")) return (me ? "I" : s) + (negated ? " can't " : " can ") + rel.substring(4) + r;
		if (negated) return (me ? "I" : s) + (third ? " doesn't " : " don't ") + rel + r;
		return (me ? "I" : s) + " " + verbFor(rel, third) + r;
	}

	/** A fact as a sentence, to someone (facts about them say "you"; about itself, "I"). */
	public static String say(Memory.Fact f, String to, String self) {
		String subj = f.subject().equals(to == null ? "" : to.toLowerCase(Locale.ROOT)) ? "you" : self != null && f.subject().equals(self.toLowerCase(Locale.ROOT)) ? "I" : f.subjectText();
		return sentence(subj, f.plural() && !subj.equals("you") && !subj.equals("I"), f.rel(), f.negated(), f.objectText());
	}

	// ------------------------------------------------------------------------------------------------ talking
	/** What the game passes in: who's talking, its memory, what the game knows, and how it says things. */
	public interface Context {
		String self();

		String speaker();

		Memory memory();

		/** What the game itself knows about subject, for rel ("drop", "be", "at", "eat"...), as the rest of a sentence ("beef and leather", "a hostile mob", "at 100 64 200"); null if nothing. */
		String game(String subject, String rel);

		Random random();

		/** The game time (for when it was told). */
		long now();
	}

	/** What it asked someone and waits to hear back ("what do cats eat?", "do they?"). */
	public record Asked(Phrase subject, String rel, Phrase object, long at) {}

	private static final Map<String, Asked> ASKED = new HashMap<>();

	private static String pick(Random r, String... s) {
		return s[r.nextInt(s.length)];
	}

	private static String join(List<String> parts) {
		if (parts.size() == 1) return parts.get(0);
		return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
	}

	/** Who a subject is in its memory: "I" is the one talking, "you" is the Xen itself, a name as it is. */
	private static Phrase who(Phrase p, Context c, Phrase last) {
		if (p == null) return null;
		String k = p.key();
		if (k.equals("i") || k.equals("me")) return new Phrase(c.speaker().toLowerCase(Locale.ROOT), c.speaker(), false, "person");
		if (k.equals("you")) return new Phrase(c.self().toLowerCase(Locale.ROOT), c.self(), false, "self");
		if ((k.equals("it") || k.equals("they") || k.equals("them")) && last != null) return last;
		return p;
	}

	/** Can it keep this as a fact? (something about a thing or a person, not "it" with nothing before, nor what someone is doing). */
	private static boolean keepable(Phrase s, String rel, Phrase o) {
		if (s == null || rel == null || o == null) return false;
		if (Set.of("it", "they", "them", "this", "that", "he", "she", "we", "us", "something", "someone", "everything", "everyone").contains(s.key())) return false;
		return !Set.of("say", "tell", "ask", "mean", "think", "know", "go", "come").contains(rel);
	}

	private static final Map<String, Phrase> LAST_TOPIC = new HashMap<>();

	/**
	 * Its reply, in its own words, when what was said is a fact it can keep or a question its memory (or the game)
	 * can answer, or an answer to what it asked; null otherwise (other parts of it answer: requests, small talk, its
	 * feelings and tastes).
	 */
	public static String reply(String text, Context c) {
		Meaning m = parse(text, c.self());
		Random r = c.random();
		String key = c.speaker().toLowerCase(Locale.ROOT);
		Asked asked = ASKED.get(key);
		if (asked != null && c.now() - asked.at() > 20 * 120) {
			ASKED.remove(key);
			asked = null;
		}
		Phrase last = LAST_TOPIC.get(key);
		// an answer to what it asked
		if (asked != null) {
			if (asked.object() != null && (m.act() == Act.YES || m.act() == Act.NO || m.act() == Act.OTHER && m.toks().size() <= 3 && m.toks().stream().anyMatch(t -> t.pos() == Pos.INTJ))) {
				boolean no = m.act() == Act.NO || m.toks().stream().anyMatch(t -> "no".equals(t.sense()));
				ASKED.remove(key);
				Memory.Fact f = fact(asked.subject(), asked.rel(), no, asked.object(), c);
				c.memory().tell(f);
				return pick(r, "got it: ", "ok, so ", "oh ok, ") + say(f, c.speaker(), c.self()) + pick(r, ".", "!", ". thanks");
			}
			if (asked.object() == null) {
				Phrase o = null;
				if (m.act() == Act.TELL && m.object() != null) o = m.object();
				else if (m.act() == Act.OTHER && m.subject() != null && m.rel() == null) o = m.subject();   // "fish": the answer itself
				if (o != null && !o.key().equals(asked.subject().key())) {
					ASKED.remove(key);
					Memory.Fact f = fact(asked.subject(), asked.rel(), m.negated(), o, c);
					c.memory().tell(f);
					return pick(r, "oh, ", "ahh ", "ok so ") + say(f, c.speaker(), c.self()) + pick(r, ". good to know", "! thanks", ". I'll remember that");
				}
			}
		}
		Phrase s = who(m.subject(), c, last);
		if (s != null && !"pron".equals(s.kind()) && !"self".equals(s.kind())) LAST_TOPIC.put(key, s);
		switch (m.act()) {
			case TELL -> {
				if (s == null || "self".equals(s.kind()) || !keepable(s, m.rel(), m.object())) return null;
				Memory.Fact f = fact(s, m.rel(), m.negated(), m.object(), c);
				List<Memory.Fact> before = c.memory().recall(f.subject(), f.rel(), f.object());
				int how = c.memory().tell(f);
				if ("person".equals(s.kind())) return null;                       // (about them: kept; the rest of it answers how it feels about that)
				String said = say(f, c.speaker(), c.self());
				String game = how == Memory.NEW ? c.game(s.key(), f.rel()) : null;
				if (game != null && !f.object().isEmpty() && game.contains(f.object()) && !f.negated())   // the game told it already: it knew
					return pick(r, "yeah, I know", "yep, " + said, "ik, " + said);
				return switch (how) {
					case Memory.KNOWN -> pick(r, "yeah, I know", "ik", "yep, " + said, "I know! " + (before.isEmpty() || before.get(0).source().equals(c.speaker()) ? "you told me" : before.get(0).source() + " told me"));
					case Memory.CHANGED -> pick(r, "wait really? I thought " + say(before.get(0), c.speaker(), c.self()) + ". ok then", "oh, so " + said + "? I had it the other way round");
					default -> pick(r, "oh, " + said + "? good to know", "noted: " + said, "huh, " + said + ". I'll remember that", "really? " + said + "? cool");
				};
			}
			case ASK -> {
				if (s == null || "self".equals(s.kind()) || m.rel() == null) return null;
				List<Memory.Fact> got = c.memory().recall(s.key(), m.rel(), m.object() == null ? null : m.object().key());
				if (!got.isEmpty()) {
					Memory.Fact f = got.get(0);
					boolean aboutThem = f.subject().equals(key);
					String said = aboutThem ? say(f, c.speaker(), c.self()) : sentence(m.subject().text(), m.subject().plural(), f.rel(), f.negated(), f.objectText());   // (in the asker's words)
					String from = aboutThem ? "" : f.source().equals(c.speaker()) ? "you told me" : f.source().startsWith("me") ? "" : f.source() + " told me";
					boolean yes = f.negated() == m.negated();
					return (yes ? pick(r, "yeah, ", "yep, ", "yes, ") : pick(r, "nah, ", "no, ", "nope, ")) + said + (from.isEmpty() ? "" : pick(r, " (" + from + ")", ", " + from));
				}
				List<Memory.Fact> other = c.memory().recall(s.key(), m.rel(), null);
				if (!other.isEmpty() && m.object() != null)
					return pick(r, "not sure about " + m.object().text() + ", but ", "idk about " + m.object().text() + ". ") + say(other.get(0), c.speaker(), c.self());
				String g = m.object() == null || m.object().key().isEmpty() ? null : c.game(s.key(), m.rel());
				if (g != null && m.object() != null) {
					boolean yes = g.contains(m.object().key()) != m.negated();
					return (yes ? pick(r, "yeah, ", "yep, ") : pick(r, "nah, ", "no, ")) + sentence(s.text(), s.plural(), m.rel(), !g.contains(m.object().key()), m.object().text());
				}
				if (m.object() == null || "person".equals(s.kind())) return null;
				ASKED.put(key, new Asked(s, m.rel(), m.object(), c.now()));     // (a yes or no back: it keeps that)
				String them = s.plural() ? "they" : "it";
				String aux = m.rel().equals("be") || m.rel().equals("at") ? (s.plural() ? "are" : "is") : m.rel().startsWith("can ") ? "can" : s.plural() ? "do" : "does";
				return pick(r, "no idea tbh. " + aux + " " + them + "?", "hmm, idk. " + aux + " " + them + "?", "not sure... " + aux + " " + them + "?");
			}
			case ASK_WH -> {
				if (m.rel() == null || Set.of("how", "why", "when").contains(m.wh())) return null;   // (how to make, why, when: the rest of it answers those)
				if (s == null && m.object() != null) {                          // "what drops leather": the other way round
					return null;
				}
				if (s == null || "self".equals(s.kind())) return null;
				String rel = m.rel();
				List<Memory.Fact> got = new ArrayList<>(c.memory().recall(s.key(), rel.equals("do") ? null : rel, null));
				if (m.wh().equals("where") && got.isEmpty()) got.addAll(c.memory().recall(s.key(), "at", null));
				got.removeIf(Memory.Fact::negated);
				if (!got.isEmpty()) {
					List<String> objs = new ArrayList<>();
					for (Memory.Fact f : got) if (!objs.contains(f.objectText()) && objs.size() < 3) objs.add(f.objectText());
					Memory.Fact f = got.get(0);
					boolean aboutThem = f.subject().equals(key);
					String subj = aboutThem ? "you" : m.subject().text();
					String from = aboutThem ? "" : f.source().equals(c.speaker()) ? "you told me" : f.source().startsWith("me") ? "" : f.source() + " told me";
					if (rel.equals("do")) {                                          // "what do zombies do": what it knows about them
						List<String> all = new ArrayList<>();
						for (Memory.Fact g : got) if (all.size() < 3) all.add(sentence(aboutThem ? "you" : g.subjectText(), g.plural() && !aboutThem, g.rel(), g.negated(), g.objectText()));
						return join(all);
					}
					return sentence(subj, aboutThem ? false : m.subject().plural(), f.rel(), false, join(objs)) + (from.isEmpty() || r.nextBoolean() ? "" : " (" + from + ")");
				}
				String g = c.game(s.key(), m.wh().equals("where") ? "at" : rel);
				if (g != null) return sentence(s.text(), s.plural(), m.wh().equals("where") ? "at" : rel, false, g);
				if ("person".equals(s.kind())) return null;                       // (nothing it was told about them: the rest of it answers)
				if (rel.equals("be") && kindOf(s.key()) != null) return null;     // (a thing of the game it has no fact about: what it knows of recipes and the like answers)
				ASKED.put(key, new Asked(s, m.wh().equals("where") ? "at" : rel, null, c.now()));
				if (m.wh().equals("where")) return pick(r, "no idea where " + s.text() + (s.plural() ? " are" : " is") + ". do you know?", "idk where " + s.text() + (s.plural() ? " are" : " is") + " tbh. where?");
				if (rel.equals("be") && m.wh().equals("what")) return pick(r, "never heard of " + s.text() + ". what " + (s.plural() ? "are they" : "is it") + "?", "idk what " + s.text() + (s.plural() ? " are" : " is") + ". tell me?");
				String does = s.plural() ? "do" : "does";
				if (rel.equals("do")) return pick(r, "idk much about " + s.text() + " tbh", "not much, that I know of. tell me?");
				String v = s.plural() ? rel : verbFor(rel, true);
				return pick(r, "idk " + m.wh() + " " + s.text() + " " + v + ". do you?", "hmm, " + m.wh() + " " + does + " " + s.text() + " " + rel + "? no idea",
						"not sure " + m.wh() + " " + s.text() + " " + v + ". what?");
			}
			default -> {
				return null;
			}
		}
	}

	private static Memory.Fact fact(Phrase s, String rel, boolean neg, Phrase o, Context c) {
		return new Memory.Fact(s.key(), s.text(), s.plural(), rel, neg, o.key(), o.text(), c.speaker(), c.now());
	}

	/** Is it waiting for this one's answer to something it asked? (then "fish" is the answer, not a request to fish) */
	public static boolean awaiting(String speaker) {
		return ASKED.containsKey(speaker.toLowerCase(Locale.ROOT));
	}

	/** Forget what it asked everyone (for the tests). */
	public static void reset() {
		ASKED.clear();
		LAST_TOPIC.clear();
	}

	/** A Xen telling another a fact it knows ("did you know cats like fish?"). */
	public static String share(Memory.Fact f, Random r) {
		String s = sentence(f.subjectText(), f.plural(), f.rel(), f.negated(), f.objectText());
		return pick(r, "did you know " + s + "?", "fun fact: " + s, "btw " + s + ", " + f.source() + " told me", "heard that " + s);
	}

	/** Words that it has, of a kind (for the tests and "what words do you know"). */
	static Set<String> of(Pos p) {
		load();
		Set<String> out = new HashSet<>();
		for (var e : KIND.entrySet()) if (e.getValue().contains(p)) out.add(e.getKey());
		return out;
	}
}
