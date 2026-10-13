package xen.mod.talk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xen's talk model: what kind of thing a player just said, read the way players really type ("wsg", "u got iron??",
 * "bro i just died to a creeper", "LMAOOO"), and an answer a player would give, in this Xen's own tone, with only what's
 * true of it filled in ("got 3 iron", "just chopping some trees rn", "rip. died to a skeleton earlier lol").
 * <p>The model (assets/xen/talk.bin) was trained by scripts/train_talk.py on data/talk/corpus.txt: every way of saying
 * each kind of line, typed the ways players type it. A line becomes its words, word pairs and letter triples, hashed
 * into rows of an embedding; their mean, one layer, a softmax over the kinds. The answers (assets/xen/talk.json) are the
 * word library: hundreds of them, each maybe only for some tones, or only when something is true (it has iron, it's
 * night, it has died...). It learns which answers land: a laugh or thanks after one, it says that one more; a "what?"
 * or an insult, less (config/xen/talk-learned.json).
 */
public final class Banter {
	/** What a Xen's talk knows of it (all of it true, all of it now). */
	public interface Self {
		String name();
		String tone();
		/** What it's doing, the way it would say it ("chopping some trees", "mining"). */
		String doing();
		/** How it feels, said casually ("pretty good", "kinda scared"). */
		String mood();
		/** How many it has (iron, diamond, wood, stone, food, coal, gold, emerald). */
		int count(String what);
		/** Its best pickaxe ("stone pickaxe"), or "". */
		String pick();
		boolean night();
		boolean hurt();
		boolean hungry();
		boolean busy();
		boolean home();
		boolean underground();
		/** What killed it last ("a skeleton"), or "". */
		String death();
		/** A place it found and could go to ("a village about 140 blocks northeast"), or "". */
		String place();
		String dream();
		/** A friend's name, or "". */
		String friend();
		String biome();
		/** Something it did lately ("made a stone pickaxe"), or "". */
		String did();
		int day();
		/** Where it is ("120 64 -30"). */
		String where();
		/** Its favorite of a kind (block, food, mob, color, biome, weapon, thing): always the same for it. */
		String fav(String kind);
	}

	/** What kind of line it was, how sure, and the chances of every kind. */
	public record Read(String kind, float p, float[] probs) {}

	/** An answer: the words, the kind of line it answered, and which answer it was (to learn how it went down). */
	public record Answer(String text, String kind, String id) {}

	private record Reply(String text, List<String> need, Set<String> tones, String ask, String id) {}

	// --------------------------------------------------------------------------------------- the model, loaded once
	private static String[] kinds = new String[0];
	private static final Set<String> PASS = new HashSet<>();
	private static final Map<String, List<Reply>> REPLIES = new HashMap<>();
	private static int buckets, dim;
	private static float[] scale, w, b;
	private static byte[] emb;
	private static boolean loaded;
	/** How each answer went down with people (sum of reactions, times): learned, kept in config/xen/talk-learned.json. */
	private static final Map<String, float[]> LEARNED = new HashMap<>();
	private static Path learnedFile;

	/** Load the model and the answers (from the mod), and what it learned before (from dir). */
	public static synchronized void init(Path dir) {
		if (!loaded) {
			try (InputStream lib = Banter.class.getResourceAsStream("/assets/xen/talk.json");
					InputStream bin = Banter.class.getResourceAsStream("/assets/xen/talk.bin")) {
				if (lib == null || bin == null) throw new IOException("talk.json or talk.bin missing from the mod");
				load(lib, bin);
			} catch (IOException | RuntimeException e) {
				System.err.println("Xen's talk model couldn't load: " + e);
			}
		}
		if (dir != null) {
			learnedFile = dir.resolve("talk-learned.json");
			try {
				if (Files.exists(learnedFile)) {
					JsonObject o = JsonParser.parseString(Files.readString(learnedFile)).getAsJsonObject();
					for (var e : o.entrySet()) {
						JsonArray a = e.getValue().getAsJsonArray();
						LEARNED.put(e.getKey(), new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat()});
					}
				}
			} catch (IOException | RuntimeException e) {
				System.err.println("Xen's talk: couldn't read " + learnedFile + ": " + e);
			}
		}
	}

	/** Load from these streams (the mod's files, or a test's). */
	public static synchronized void load(InputStream lib, InputStream bin) throws IOException {
		JsonObject o = JsonParser.parseReader(new InputStreamReader(lib, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonArray ks = o.getAsJsonArray("kinds");
		String[] names = new String[ks.size()];
		for (int i = 0; i < names.length; i++) names[i] = ks.get(i).getAsString();
		PASS.clear();
		for (JsonElement e : o.getAsJsonArray("pass")) PASS.add(e.getAsString());
		REPLIES.clear();
		for (var e : o.getAsJsonObject("replies").entrySet()) {
			List<Reply> list = new ArrayList<>();
			int n = 0;
			for (JsonElement r : e.getValue().getAsJsonArray()) {
				JsonObject ro = r.getAsJsonObject();
				List<String> need = new ArrayList<>();
				for (JsonElement x : ro.getAsJsonArray("need")) need.add(x.getAsString());
				Set<String> tones = new HashSet<>();
				for (JsonElement x : ro.getAsJsonArray("tones")) tones.add(x.getAsString());
				String ask = ro.has("ask") && !ro.get("ask").isJsonNull() ? ro.get("ask").getAsString() : null;
				list.add(new Reply(ro.get("text").getAsString(), need, tones, ask, e.getKey() + "#" + n++));
			}
			REPLIES.put(e.getKey(), list);
		}
		DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(bin));
		if (in.readInt() != 0x58544C4B) throw new IOException("not a talk model");
		in.readInt();
		int nb = in.readInt(), nd = in.readInt(), nk = in.readInt();
		if (nk != names.length) throw new IOException("the model has " + nk + " kinds, the library " + names.length);
		float[] sc = new float[nb];
		for (int i = 0; i < nb; i++) sc[i] = in.readFloat();
		byte[] e = new byte[nb * nd];
		in.readFully(e);
		float[] ww = new float[nk * nd], bb = new float[nk];
		for (int i = 0; i < ww.length; i++) ww[i] = in.readFloat();
		for (int i = 0; i < nk; i++) bb[i] = in.readFloat();
		kinds = names;
		buckets = nb;
		dim = nd;
		scale = sc;
		emb = e;
		w = ww;
		b = bb;
		loaded = true;
	}

	public static boolean ready() {
		return loaded;
	}

	public static synchronized void save() {
		if (learnedFile == null || LEARNED.isEmpty()) return;
		JsonObject o = new JsonObject();
		for (var e : LEARNED.entrySet()) {
			JsonArray a = new JsonArray();
			a.add(Math.round(e.getValue()[0] * 100) / 100f);
			a.add(e.getValue()[1]);
			o.add(e.getKey(), a);
		}
		try {
			Files.createDirectories(learnedFile.getParent());
			Files.writeString(learnedFile, o.toString());
		} catch (IOException e) {
			System.err.println("Xen's talk: couldn't save " + learnedFile + ": " + e);
		}
	}

	// --------------------------------------------------------------------------------------- reading a line (as train_talk.py)
	private static final Pattern STRETCH = Pattern.compile("(.)\\1{2,}");

	static String normalize(String text, String name) {
		String t = text.toLowerCase(Locale.ROOT);
		if (name != null && !name.isEmpty()) {
			t = t.replaceAll("(?<![0-9a-z_])" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(?![0-9a-z_])", Matcher.quoteReplacement(" @x "));
		}
		return STRETCH.matcher(t).replaceAll("$1$1");
	}

	/** Words (letters and digits; ' dropped inside), "@x", "?" and "!" once each, and emoji and symbols one by one. */
	public static List<String> tokens(String text, String name) {
		String t = normalize(text, name);
		int[] cps = t.codePoints().toArray();
		List<String> out = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		boolean q = false, bang = false;
		for (int i = 0; i < cps.length; i++) {
			int ch = cps[i];
			if (ch == '@' && i + 1 < cps.length && cps[i + 1] == 'x' && (i + 2 >= cps.length || !Character.isLetterOrDigit(cps[i + 2]))) {
				if (word.length() > 0) {
					out.add(word.toString());
					word.setLength(0);
				}
				out.add("@x");
				i++;
				continue;
			}
			if (Character.isLetterOrDigit(ch)) {
				word.appendCodePoint(ch);
			} else if ((ch == '\'' || ch == '’') && word.length() > 0) {
				// (dropped: "what's" is "whats")
			} else {
				if (word.length() > 0) {
					out.add(word.toString());
					word.setLength(0);
				}
				if (ch == '?' && !q) {
					out.add("?");
					q = true;
				} else if (ch == '!' && !bang) {
					out.add("!");
					bang = true;
				} else if (ch >= 0x2190 && ch != 0xFE0F && ch != 0x200D && !Character.isWhitespace(ch)) {
					out.add(new String(Character.toChars(ch)));
				}
			}
		}
		if (word.length() > 0) out.add(word.toString());
		return out;
	}

	static List<String> features(String text, String name) {
		List<String> toks = tokens(text, name), f = new ArrayList<>();
		for (String t : toks) {
			f.add("w|" + t);
			if (t.codePointCount(0, t.length()) >= 2 && !t.equals("@x") && Character.isLetterOrDigit(t.codePointAt(0))) {
				int[] p = ("<" + t + ">").codePoints().toArray();
				for (int i = 0; i + 3 <= p.length; i++) f.add("c|" + new String(p, i, 3));
			}
		}
		List<String> seq = new ArrayList<>();
		seq.add("<s>");
		seq.addAll(toks);
		seq.add("</s>");
		for (int i = 0; i + 1 < seq.size(); i++) f.add("b|" + seq.get(i) + " " + seq.get(i + 1));
		if (toks.isEmpty()) f.add("w|<empty>");
		return f;
	}

	static int fnv(String s) {
		int h = 0x811C9DC5;
		for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
			h ^= x & 0xFF;
			h *= 0x01000193;
		}
		return h;
	}

	public static int[] ids(String text, String name) {
		List<String> f = features(text, name);
		int[] out = new int[f.size()];
		for (int i = 0; i < out.length; i++) out[i] = fnv(f.get(i)) & (buckets - 1);
		return out;
	}

	/** What kind of line this is (its own name read as "@x"). */
	public static Read read(String text, String name) {
		if (!loaded || text == null) return new Read("none", 0, new float[0]);
		int[] rows = ids(text, name);
		float[] h = new float[dim];
		for (int r : rows) {
			float s = scale[r];
			int base = r * dim;
			for (int j = 0; j < dim; j++) h[j] += emb[base + j] * s;
		}
		for (int j = 0; j < dim; j++) h[j] /= rows.length;
		int k = kinds.length;
		float[] z = new float[k];
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < k; i++) {
			float s = b[i];
			for (int j = 0; j < dim; j++) s += w[i * dim + j] * h[j];
			z[i] = s;
			max = Math.max(max, s);
		}
		double sum = 0;
		for (int i = 0; i < k; i++) sum += z[i] = (float) Math.exp(z[i] - max);
		int best = 0;
		for (int i = 0; i < k; i++) {
			z[i] /= sum;
			if (z[i] > z[best]) best = i;
		}
		return new Read(kinds[best], z[best], z);
	}

	// --------------------------------------------------------------------------------------- one Xen's talk
	private final Self me;
	private final Random random;
	private final ArrayDeque<String> said = new ArrayDeque<>();
	/** What it asked back ("fav_block": the next line is the answer), and when. */
	private String asked;
	private long askedAt;
	/** Its last answer (to learn how it went down) and when. */
	private String lastId;
	private long lastAt;
	public String lastKind = "", lastHeard = "";

	public Banter(Self me, Random random) {
		this.me = me;
		this.random = random;
	}

	/** Is it waiting for the answer to something it asked back? */
	public boolean asking() {
		return asked != null;
	}

	/** Sure enough to answer from the talk model (else the rest of Xen's chat answers). */
	static final float SURE = 0.5f;

	/**
	 * Its answer to a line (now: game ticks), or null when it isn't sure what was meant, or it's for another part of
	 * Xen (a request, a fact, a recipe).
	 */
	public Answer answer(String speaker, String text, long now) {
		if (!loaded || text == null || text.isBlank()) return null;
		Read rd = read(text, me.name());
		lastHeard = rd.kind() + String.format(Locale.ROOT, " %.2f", rd.p());
		if (asked != null && now - askedAt < 20 * 90 && rd.p() < 0.9f && !rd.kind().startsWith("player_") && !rd.kind().equals("confused")) {
			String topic = asked;
			asked = null;
			String ans = cleanAnswer(text);
			if (!ans.isEmpty()) {
				Answer a = pick("answer:" + topic, speaker, ans, now);
				if (a != null) return a;
			}
		}
		asked = null;
		if (rd.p() < SURE || rd.kind().equals("none") || PASS.contains(rd.kind())) return null;
		focus = about(text);
		try {
			return pick(rd.kind(), speaker, "", now);
		} finally {
			focus = null;
		}
	}

	/** The thing a line asks about, if it names one ("got any iron?": iron): its answer is about that. */
	private String focus;
	private static final String[][] THINGS = {{"iron", "iron"}, {"diamond", "diamonds?|dias"}, {"food", "food|eat|steak|bread|porkchop"},
			{"wood", "wood|logs?|planks?"}, {"coal", "coal"}, {"gold", "gold"}, {"emerald", "emeralds?"}, {"stone", "stone|cobble(stone)?"},
			{"pick", "pick(axe)?s?"}};

	static String about(String text) {
		String t = " " + text.toLowerCase(Locale.ROOT) + " ";
		for (String[] th : THINGS) if (Pattern.compile("\\b(" + th[1] + ")\\b").matcher(t).find()) return th[0];
		return null;
	}

	/** Does this answer speak of the thing asked about (its words, or what it needs)? */
	private static boolean speaksOf(Reply r, String thing) {
		if (thing.equals("pick")) return r.text().contains("{pick}") || r.need().contains("pick") || r.need().contains("nopick") || r.text().contains("pickaxe");
		if (r.text().contains("{n:" + thing + "}") || r.need().contains("has:" + thing) || r.need().contains("no:" + thing)) return true;
		return r.text().contains(thing.equals("diamond") ? "diamond" : thing);
	}

	/** A line from someone just after it answered them: a laugh or thanks, it'll say that kind of thing more; "what?" or an insult, less. */
	public void reaction(String text, long now) {
		if (lastId == null || now - lastAt > 20 * 30 || !loaded) return;
		Read rd = read(text, me.name());
		if (rd.p() < 0.6f) return;
		float r = switch (rd.kind()) {
			case "laugh", "nice", "compliment", "thanks", "agree", "love_you" -> 1f;
			case "confused", "insult", "shut_up", "bruh" -> -1f;
			default -> 0f;
		};
		if (r == 0) return;
		synchronized (Banter.class) {
			float[] s = LEARNED.computeIfAbsent(lastId, k -> new float[2]);
			s[0] += r;
			s[1]++;
		}
		lastId = null;
	}

	private static final Pattern FILLER = Pattern.compile("^(i think |i guess |probably |prob |definitely |def |honestly |tbh |uh+ |um+ |hmm+ |"
			+ "my fav(ou?rite)? (\\w+ )?is |mine is |its |it's |i like |i love |i'd say |id say |easy |gotta be |the )+");

	/** What they answered, cut down to the thing itself ("my favorite block is probably deepslate lol" is "deepslate"). */
	static String cleanAnswer(String text) {
		String t = text.toLowerCase(Locale.ROOT).replaceAll("[!?.,~]+", " ").replaceAll("\\s+", " ").trim();
		t = FILLER.matcher(t).replaceAll("");
		t = t.replaceAll("\\b(lol|lmao|haha|tbh|ngl|fr|bro|dude|man|for sure|i guess)\\b", "").replaceAll("\\s+", " ").trim();
		String[] ws = t.split(" ");
		if (ws.length > 4 || t.isEmpty()) return "";
		return t;
	}

	private Answer pick(String kind, String speaker, String answer, long now) {
		List<Reply> pool = REPLIES.get(kind);
		if (pool == null || pool.isEmpty()) return null;
		String tone = me.tone() == null ? "calm" : me.tone();
		List<Reply> ok = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		boolean anyOnIt = false;
		if (focus != null) for (Reply r : pool) anyOnIt |= speaksOf(r, focus) && meets(r.need()) && (r.tones().isEmpty() || r.tones().contains(tone));
		for (Reply r : pool) {
			if (anyOnIt && !speaksOf(r, focus)) continue;                    // (asked about iron: it talks about its iron)
			if (!r.tones().isEmpty() && !r.tones().contains(tone)) continue;
			if (!meets(r.need())) continue;
			String t = fill(r.text(), speaker, answer);
			if (t == null) continue;
			double wgt = r.tones().isEmpty() ? 1 : 2.2;                      // (its own tone's words, when there are some)
			wgt *= 1 + 0.6 * r.need().size();                                // (one that fits the moment over a general one)
			if (said.contains(r.id())) wgt *= 0.05;                          // (not the same thing twice in a row)
			float[] l = LEARNED.get(r.id());
			if (l != null && l[1] > 0) wgt *= Math.exp(Math.max(-1.5, Math.min(1.5, 0.6 * l[0] / Math.sqrt(l[1]))));
			ok.add(r);
			texts.add(t);
			weights.add(wgt);
		}
		if (ok.isEmpty()) return null;
		double sum = 0;
		for (double x : weights) sum += x;
		double roll = random.nextDouble() * sum;
		int i = 0;
		while (i < ok.size() - 1 && (roll -= weights.get(i)) > 0) i++;
		Reply r = ok.get(i);
		said.addLast(r.id());
		while (said.size() > 12) said.removeFirst();
		if (r.ask() != null) {
			asked = r.ask();
			askedAt = now;
		}
		lastId = r.id();
		lastAt = now;
		lastKind = kind;
		return new Answer(texts.get(i), kind, r.id());
	}

	private boolean meets(List<String> need) {
		for (String n : need) {
			boolean ok = switch (n) {
				case "pick" -> !me.pick().isEmpty();
				case "nopick" -> me.pick().isEmpty();
				case "night" -> me.night();
				case "day" -> !me.night();
				case "hurt" -> me.hurt();
				case "hungry" -> me.hungry();
				case "fine" -> !me.hurt() && !me.hungry();
				case "busy" -> me.busy();
				case "idle" -> !me.busy();
				case "home" -> me.home();
				case "nohome" -> !me.home();
				case "died" -> !me.death().isEmpty();
				case "place" -> !me.place().isEmpty();
				case "friend" -> !me.friend().isEmpty();
				case "dream" -> !me.dream().isEmpty();
				case "did" -> !me.did().isEmpty();
				case "underground" -> me.underground();
				default -> {
					if (n.startsWith("has:")) yield me.count(n.substring(4)) > 0;
					if (n.startsWith("no:")) yield me.count(n.substring(3)) == 0;
					yield false;
				}
			};
			if (!ok) return false;
		}
		return true;
	}

	private static final Pattern SLOT = Pattern.compile("\\{([a-z:]+)\\}");

	/** The answer with what's true of it filled in; null if something it would say isn't there. */
	private String fill(String text, String speaker, String answer) {
		Matcher m = SLOT.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String slot = m.group(1), v;
			if (slot.startsWith("n:")) v = String.valueOf(me.count(slot.substring(2)));
			else if (slot.startsWith("fav:")) v = me.fav(slot.substring(4));
			else v = switch (slot) {
				case "player" -> speaker;
				case "me" -> me.name();
				case "doing" -> me.doing();
				case "mood" -> me.mood();
				case "pick" -> me.pick();
				case "death" -> me.death();
				case "place" -> me.place();
				case "dream" -> me.dream();
				case "friend" -> me.friend();
				case "biome" -> me.biome();
				case "did" -> me.did();
				case "day" -> String.valueOf(me.day());
				case "xyz" -> me.where();
				case "answer" -> answer;
				default -> null;
			};
			if (v == null || v.isEmpty()) return null;
			m.appendReplacement(sb, Matcher.quoteReplacement(v));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
