package xen.mod.talk;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XenTalk: Xen's own small language model, trained for one thing: what a Minecraft player says, given what's true for
 * it right now. Its input is one line of facts (who it is, its tone and mood, what it's doing and why, what it has,
 * what it saw, who it's talking to and what they said) and its output is the reply, word piece by word piece. It never
 * gets anything that isn't true for the Xen, and a reply that names a thing that isn't in the facts is thrown away
 * (the classic voice answers instead), so it can't make up iron it hasn't got.
 * <p>The model: a decoder-only transformer (4 layers, 256 wide, 4 heads, about 4M numbers, kept as bytes in the jar and
 * as floats in memory: 16 MB), with the keys and values of what it has read kept as it goes, so each new piece costs
 * one pass. It runs on its own thread, never the server's: a reply comes a moment later, like someone typing.
 */
public final class XenTalk {
	private static final int PAD = 0, BOS = 1, SEP = 2, EOS = 3;
	private static final Pattern PIECE = Pattern.compile("[a-z]+|[0-9]|[^a-z0-9]");
	/** Things a reply mustn't name unless the facts (or what was said) do: the same list the teacher's replies were kept by. */
	private static final Set<String> THINGS = Set.of(("diamond diamonds iron gold emerald emeralds coal redstone lapis netherite copper quartz amethyst wood log logs "
			+ "plank planks stone cobble cobblestone dirt sand gravel obsidian clay glass wool zombie zombies skeleton skeletons creeper creepers spider spiders "
			+ "enderman endermen witch slime blaze ghast piglin piglins villager villagers golem cow cows pig pigs sheep chicken chickens horse wolf cat fish "
			+ "nether end dragon village temple stronghold mineshaft portal cave caves ravine ocean desert jungle forest mountain swamp sword pickaxe axe shovel "
			+ "hoe bow arrow arrows shield armor helmet chestplate boots elytra bed chest furnace torch torches bucket lava water wheat bread apple apples steak "
			+ "beef pork porkchop carrot carrots potato potatoes house base farm mine trident totem enchant enchanting anvil beacon").split(" "));
	private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b"), WORD = Pattern.compile("[a-z]+");

	private final int vocab, d, layers, heads, ff, ctx;
	private final String[] pieces;
	/** A word with no space before it, and a piece that ends in a letter or digit: the first can't follow the second. */
	private final boolean[] glue, wordEnd;
	/** What may follow a word and "'" (the endings the teacher wrote: "i" -> m, ll, ve, d), and its own name's pieces. */
	private final Map<String, Set<String>> tails = new HashMap<>();
	private final int apostrophe, ownName, ownNameGlued;
	private final Map<String, Integer> index = new HashMap<>();
	private final float[] tok, pos, lnW, lnB;
	private final float[][] ln1W, ln1B, qkvW, qkvB, outW, outB, ln2W, ln2B, ff1W, ff1B, ff2W, ff2B;

	// ------------------------------------------------------------------------------------- loading
	private static XenTalk model;
	private static boolean tried;

	/** The model in the jar (null if it isn't there or can't be read: the classic voice talks then). */
	public static synchronized XenTalk get() {
		if (tried) return model;
		tried = true;
		try (InputStream bin = XenTalk.class.getResourceAsStream("/assets/xen/xentalk.bin");
				InputStream js = XenTalk.class.getResourceAsStream("/assets/xen/xentalk.json")) {
			if (bin != null && js != null) model = new XenTalk(new DataInputStream(new java.io.BufferedInputStream(bin)),
					new Gson().fromJson(new String(js.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
		} catch (IOException | RuntimeException e) {
			model = null;
		}
		return model;
	}

	private static int int32(DataInputStream in) throws IOException {
		return Integer.reverseBytes(in.readInt());
	}

	/** A matrix (rows of bytes, a scale per row) as floats, rows x cols. */
	private static float[] mat(DataInputStream in, int wantCols) throws IOException {
		int rows = int32(in), cols = int32(in);
		if (wantCols > 0 && cols != wantCols) throw new IOException("xentalk: " + cols + " columns, not " + wantCols);
		byte[] b = new byte[rows * cols];
		in.readFully(b);
		byte[] sb = new byte[rows * 4];
		in.readFully(sb);
		ByteBuffer scales = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN);
		float[] out = new float[rows * cols];
		for (int r = 0; r < rows; r++) {
			float s = scales.getFloat(r * 4);
			for (int c = 0; c < cols; c++) out[r * cols + c] = b[r * cols + c] * s;
		}
		return out;
	}

	private static float[] vec(DataInputStream in) throws IOException {
		int n = int32(in);
		byte[] b = new byte[n * 4];
		in.readFully(b);
		ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		float[] out = new float[n];
		for (int i = 0; i < n; i++) out[i] = bb.getFloat(i * 4);
		return out;
	}

	XenTalk(DataInputStream in, JsonObject meta) throws IOException {
		if (int32(in) != 0x58544B31) throw new IOException("not a XenTalk model");
		vocab = int32(in);
		d = int32(in);
		layers = int32(in);
		heads = int32(in);
		ff = int32(in);
		ctx = int32(in);
		int32(in);
		var list = meta.getAsJsonArray("pieces");
		pieces = new String[list.size()];
		for (int i = 0; i < pieces.length; i++) {
			pieces[i] = list.get(i).getAsString();
			index.put(pieces[i], i);
		}
		if (pieces.length != vocab) throw new IOException("xentalk: " + pieces.length + " pieces, the model has " + vocab);
		glue = new boolean[vocab];
		wordEnd = new boolean[vocab];
		for (int i = 0; i < vocab; i++) {
			glue[i] = pieces[i].matches("[a-z]+");
			wordEnd[i] = pieces[i].matches("(?s).*[a-z0-9]");
		}
		if (meta.has("contractions"))
			for (var e : meta.getAsJsonObject("contractions").entrySet()) {
				Set<String> t = new HashSet<>();
				for (var x : e.getValue().getAsJsonArray()) t.add(x.getAsString());
				tails.put(e.getKey(), t);
			}
		apostrophe = index.getOrDefault("'", -1);
		ownName = index.getOrDefault(" xme", -1);
		ownNameGlued = index.getOrDefault("xme", -1);
		tok = mat(in, d);
		pos = mat(in, d);
		ln1W = new float[layers][]; ln1B = new float[layers][]; qkvW = new float[layers][]; qkvB = new float[layers][]; outW = new float[layers][];
		outB = new float[layers][]; ln2W = new float[layers][]; ln2B = new float[layers][]; ff1W = new float[layers][]; ff1B = new float[layers][];
		ff2W = new float[layers][]; ff2B = new float[layers][];
		for (int l = 0; l < layers; l++) {
			ln1W[l] = vec(in); ln1B[l] = vec(in);
			qkvW[l] = mat(in, d); qkvB[l] = vec(in);
			outW[l] = mat(in, d); outB[l] = vec(in);
			ln2W[l] = vec(in); ln2B[l] = vec(in);
			ff1W[l] = mat(in, d); ff1B[l] = vec(in);
			ff2W[l] = mat(in, ff); ff2B[l] = vec(in);
		}
		lnW = vec(in);
		lnB = vec(in);
	}

	// ------------------------------------------------------------------------------------- pieces
	/** Words with the space before them, and single characters: the same split as scripts/xentalk/train.py. */
	static List<String> split(String text) {
		List<String> raw = new ArrayList<>();
		Matcher m = PIECE.matcher(text.toLowerCase(Locale.ROOT));
		while (m.find()) raw.add(m.group());
		List<String> out = new ArrayList<>();
		boolean space = false;
		for (String p : raw) {
			if (p.equals(" ")) {
				if (space) out.add(" ");
				space = true;
				continue;
			}
			out.add(space ? " " + p : p);
			space = false;
		}
		if (space) out.add(" ");
		return out;
	}

	public int[] encode(String text) {
		List<Integer> ids = new ArrayList<>();
		int q = index.getOrDefault("?", 0);
		for (String p : split(text)) {
			Integer id = index.get(p);
			if (id != null) ids.add(id);
			else if (p.startsWith(" ")) {                                        // (a word it doesn't know: " w", then letter by letter)
				ids.add(index.getOrDefault(p.substring(0, 2), q));
				for (int i = 2; i < p.length(); i++) ids.add(index.getOrDefault(String.valueOf(p.charAt(i)), q));
			} else {
				for (int i = 0; i < p.length(); i++) ids.add(index.getOrDefault(String.valueOf(p.charAt(i)), q));
			}
		}
		int[] out = new int[ids.size()];
		for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
		return out;
	}

	public String decode(int[] ids) {
		StringBuilder sb = new StringBuilder();
		for (int id : ids) if (id > EOS && id < pieces.length) sb.append(pieces[id]);
		return sb.toString();
	}

	// ------------------------------------------------------------------------------------- running it
	private static void layerNorm(float[] x, float[] w, float[] b, float[] out) {
		int n = x.length;
		float mean = 0, var = 0;
		for (float v : x) mean += v;
		mean /= n;
		for (float v : x) var += (v - mean) * (v - mean);
		float inv = (float) (1 / Math.sqrt(var / n + 1e-5));
		for (int i = 0; i < n; i++) out[i] = (x[i] - mean) * inv * w[i] + b[i];
	}

	/** out = W x + b, W rows x cols. */
	private static void matvec(float[] w, float[] b, float[] x, float[] out, int rows, int cols) {
		for (int r = 0; r < rows; r++) {
			int o = r * cols, c = 0;
			float s0 = 0, s1 = 0, s2 = 0, s3 = 0;                                 // (four sums at once: the CPU keeps them all going)
			for (; c + 3 < cols; c += 4) {
				s0 += w[o + c] * x[c];
				s1 += w[o + c + 1] * x[c + 1];
				s2 += w[o + c + 2] * x[c + 2];
				s3 += w[o + c + 3] * x[c + 3];
			}
			for (; c < cols; c++) s0 += w[o + c] * x[c];
			out[r] = (b == null ? 0 : b[r]) + (s0 + s1) + (s2 + s3);
		}
	}

	private static float gelu(float x) {
		return (float) (0.5 * x * (1 + Math.tanh(0.7978845608 * (x + 0.044715 * x * x * x))));
	}

	/** One reading: the keys and values of everything read so far. */
	private final class Run {
		final float[][][] k = new float[layers][ctx][d], v = new float[layers][ctx][d];
		final float[] x = new float[d], h = new float[d], qkv = new float[3 * d], y = new float[d], o = new float[d], f = new float[ff], att = new float[ctx];
		int n;

		/** Read one piece; its next-piece scores if wanted (null otherwise). */
		float[] step(int id, boolean logits) {
			int p = n++;
			for (int i = 0; i < d; i++) x[i] = tok[id * d + i] + pos[p * d + i];
			int dh = d / heads;
			float scale = (float) (1 / Math.sqrt(dh));
			for (int l = 0; l < layers; l++) {
				layerNorm(x, ln1W[l], ln1B[l], h);
				matvec(qkvW[l], qkvB[l], h, qkv, 3 * d, d);
				System.arraycopy(qkv, d, k[l][p], 0, d);
				System.arraycopy(qkv, 2 * d, v[l][p], 0, d);
				for (int hd = 0; hd < heads; hd++) {
					int off = hd * dh;
					float max = Float.NEGATIVE_INFINITY;
					for (int t = 0; t <= p; t++) {
						float s = 0;
						for (int i = 0; i < dh; i++) s += qkv[off + i] * k[l][t][off + i];
						att[t] = s * scale;
						max = Math.max(max, att[t]);
					}
					float sum = 0;
					for (int t = 0; t <= p; t++) {
						att[t] = (float) Math.exp(att[t] - max);
						sum += att[t];
					}
					for (int i = 0; i < dh; i++) {
						float s = 0;
						for (int t = 0; t <= p; t++) s += att[t] * v[l][t][off + i];
						y[off + i] = s / sum;
					}
				}
				matvec(outW[l], outB[l], y, o, d, d);
				for (int i = 0; i < d; i++) x[i] += o[i];
				layerNorm(x, ln2W[l], ln2B[l], h);
				matvec(ff1W[l], ff1B[l], h, f, ff, d);
				for (int i = 0; i < ff; i++) f[i] = gelu(f[i]);
				matvec(ff2W[l], ff2B[l], f, o, d, ff);
				for (int i = 0; i < d; i++) x[i] += o[i];
			}
			if (!logits) return null;
			layerNorm(x, lnW, lnB, h);
			float[] out = new float[vocab];
			matvec(tok, null, h, out, vocab, d);
			return out;
		}
	}

	/** The reply's pieces for these facts: greedy (temperature 0) or sampled from the top few. */
	public int[] generate(String facts, float temperature, int topK, int max, Random random) {
		int[] f = encode(facts);
		int keep = Math.min(f.length, ctx - max - 2);
		Run run = new Run();
		run.step(BOS, false);
		for (int i = 0; i < keep; i++) run.step(f[i], false);
		float[] logits = run.step(SEP, true);
		List<Integer> out = new ArrayList<>();
		for (int t = 0; t < max && run.n < ctx; t++) {
			penalise(logits, out);
			int next = pick(logits, temperature, topK, random);
			if (next == EOS || next == PAD) break;
			out.add(next);
			logits = run.step(next, true);
		}
		int[] ids = new int[out.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = out.get(i);
		return ids;
	}

	/** What it already said is less likely again (x1.3), and no three pieces in a row twice: the same as scripts/xentalk/train.py. */
	private void penalise(float[] logits, List<Integer> out) {
		java.util.Set<Integer> seen = new java.util.HashSet<>(out);
		for (int t : seen) logits[t] = logits[t] > 0 ? logits[t] / 1.3f : logits[t] * 1.3f;
		int n = out.size();
		if (n >= 2) {
			int a = out.get(n - 2), b = out.get(n - 1);
			for (int i = 0; i + 2 < n; i++) if (out.get(i) == a && out.get(i + 1) == b) logits[out.get(i + 2)] = Float.NEGATIVE_INFINITY;
		}
		if (n > 0 && wordEnd[out.get(n - 1)])                                   // no spelling: a word starts after a space or a mark ("i'm"), never straight after a letter
			for (int i = 0; i < logits.length; i++) if (glue[i]) logits[i] = Float.NEGATIVE_INFINITY;
		if (n >= 2 && out.get(n - 1) == apostrophe) {                           // after "i'" only m, ll, ve, d: the endings the teacher used
			Set<String> ok = tails.getOrDefault(pieces[out.get(n - 2)].trim(), Set.of());
			for (int i = 0; i < logits.length; i++) if ((ok.isEmpty() ? glue[i] : true) && !ok.contains(pieces[i])) logits[i] = Float.NEGATIVE_INFINITY;   // (nothing else after it, not even a word: "i' from")
		}
		if (ownName >= 0) logits[ownName] = Float.NEGATIVE_INFINITY;             // (never its own name: it was saying it to others)
		if (ownNameGlued >= 0) logits[ownNameGlued] = Float.NEGATIVE_INFINITY;
	}

	private static int pick(float[] logits, float temperature, int topK, Random random) {
		if (temperature <= 0) {
			int best = 0;
			for (int i = 1; i < logits.length; i++) if (logits[i] > logits[best]) best = i;
			return best;
		}
		int k = Math.min(topK, logits.length);
		int[] top = new int[k];
		java.util.Arrays.fill(top, -1);
		for (int i = 0; i < logits.length; i++) {                                   // (the k highest)
			int j = k - 1;
			if (top[j] >= 0 && logits[i] <= logits[top[j]]) continue;
			while (j > 0 && (top[j - 1] < 0 || logits[i] > logits[top[j - 1]])) {
				top[j] = top[j - 1];
				j--;
			}
			top[j] = i;
		}
		double[] p = new double[k];
		double sum = 0;
		for (int j = 0; j < k && top[j] >= 0; j++) {
			p[j] = Math.exp((logits[top[j]] - logits[top[0]]) / temperature);
			sum += p[j];
		}
		double r = random.nextDouble() * sum;
		for (int j = 0; j < k && top[j] >= 0; j++) {
			r -= p[j];
			if (r <= 0) return top[j];
		}
		return top[0];
	}

	/** Nothing in the reply that the facts don't have: things, numbers. */
	public static boolean grounded(String reply, String facts) {
		String known = facts.toLowerCase(Locale.ROOT);
		Matcher w = WORD.matcher(reply.toLowerCase(Locale.ROOT));
		while (w.find()) {
			String t = w.group();
			if (THINGS.contains(t) && !known.contains(t) && !known.contains(t.replaceAll("s$", ""))) return false;
		}
		Matcher n = NUMBER.matcher(reply);
		while (n.find()) if (!known.matches("(?s).*\\b" + n.group() + "\\b.*")) return false;
		return true;
	}

	/** A reply for these facts that sticks to them (up to three tries), or null. */
	public String reply(String facts, Random random) {
		List<String[]> names = new ArrayList<>();
		String plain = masked(facts, names);
		for (int attempt = 0; attempt < 3; attempt++) {
			String text = decode(generate(plain, 0.8f, 30, 28, random)).replaceAll("\\s+", " ").trim();
			if (text.length() >= 2 && text.length() <= 100 && grounded(text, plain)) {
				for (String[] n : names) text = text.replaceAll("(?<![a-z0-9])" + n[1] + "(?![a-z0-9])", Matcher.quoteReplacement(n[2]));
				if (!LEFT_OVER.matcher(text).find()) return text;                  // (a name it has no one for, as "xto" in a remark: try again)
			}
		}
		return null;
	}

	/**
	 * Names out, as train.py's masked(): a model this small can't spell a name back, so me, to and friend become the
	 * words xme, xto and xfriend in the facts and in what was heard; {@code names} gets {lowercase name, word, name}
	 * to put them back.
	 */
	private static final Pattern LEFT_OVER = Pattern.compile("(?<![a-z0-9])x(me|to|friend)(?![a-z0-9])");

	/** Two lines that say much the same (most of their words shared): a chat that has run out of things to say. */
	public static boolean samey(String a, String b) {
		Set<String> x = new HashSet<>(List.of(a.toLowerCase(Locale.ROOT).split("[^a-z0-9']+"))), y = new HashSet<>(List.of(b.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")));
		x.remove("");
		y.remove("");
		if (x.isEmpty() || y.isEmpty()) return true;
		Set<String> both = new HashSet<>(x);
		both.retainAll(y);
		return both.size() * 5 >= Math.min(x.size(), y.size()) * 3;           // (3 in 5 of the shorter line's words)
	}

	public static String masked(String facts, List<String[]> names) {
		String[] parts = facts.split("\\|", -1);
		for (String p : parts) {
			int eq = p.indexOf('=');
			String k = eq < 0 ? p : p.substring(0, eq), v = eq < 0 ? "" : p.substring(eq + 1);
			if ((k.equals("me") || k.equals("to") || k.equals("friend")) && !v.isEmpty()) names.add(new String[] {v.toLowerCase(Locale.ROOT), "x" + k, v});
		}
		names.sort((a, b) -> b[0].length() - a[0].length());
		StringBuilder out = new StringBuilder();
		for (String p : parts) {
			int eq = p.indexOf('=');
			String k = eq < 0 ? p : p.substring(0, eq), v = eq < 0 ? "" : p.substring(eq + 1);
			if (out.length() > 0) out.append('|');
			if ((k.equals("me") || k.equals("to") || k.equals("friend")) && !v.isEmpty()) v = "x" + k;
			else if (k.equals("heard"))
				for (String[] n : names) v = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(n[0]) + "(?![a-z0-9])", Pattern.CASE_INSENSITIVE).matcher(v).replaceAll(n[1]);
			out.append(eq < 0 ? k : k + "=" + v);
		}
		return out.toString();
	}

	// ------------------------------------------------------------------------------------- off the server's thread
	private static ExecutorService worker;

	private static synchronized ExecutorService worker() {
		if (worker == null || worker.isShutdown()) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "xen-talk");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
		return worker;
	}

	/** A job on XenTalk's own thread (one at a time, low priority: never the server's). */
	public static <T> java.util.concurrent.CompletableFuture<T> async(java.util.function.Supplier<T> job) {
		return java.util.concurrent.CompletableFuture.supplyAsync(job, worker());
	}

	/** Works out a reply on its own thread; done gets it (or null: none that sticks to the facts, or no model). */
	public static void ask(String facts, Consumer<String> done) {
		XenTalk m = get();
		if (m == null) {
			done.accept(null);
			return;
		}
		try {
			worker().submit(() -> {
				String r;
				try {
					r = m.reply(facts, new Random());
				} catch (RuntimeException e) {
					r = null;
				}
				done.accept(r);
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			done.accept(null);
		}
	}

	// ------------------------------------------------------------------------------------- the facts

	private static String trustWord(float t) {
		return t <= -0.3f ? "enemy" : t >= 0.7f ? "best friend" : t >= 0.3f ? "friend" : "stranger";
	}

	private static String clean(String s) {
		return s == null ? "" : s.replace('|', ',').replace('=', ' ').replaceAll("\\s+", " ").trim();
	}

	/** The facts line: what the model is given (the same format the training data was made with). */
	public static String facts(Voice.Self me, String to, float trust, String heard) {
		List<String> f = new ArrayList<>();
		f.add("me=" + clean(me.name()));
		f.add("tone=" + me.tone());
		f.add("mood=" + clean(me.mood()));
		List<String> feel = new ArrayList<>();
		if (me.hurt()) feel.add("hurt");
		if (me.hungry()) feel.add("hungry");
		if (!feel.isEmpty()) f.add("feel=" + String.join(",", feel));
		f.add("time=" + (me.night() ? "night" : "day"));
		float d = me.danger();
		if (d > 0.1f) f.add("danger=" + (d > 0.5f ? "high" : "some"));
		put(f, "doing", me.doing());
		put(f, "why", me.why());
		put(f, "next", me.next());
		put(f, "has", me.has());
		put(f, "saw", me.saw());
		put(f, "lately", me.lately());
		put(f, "dream", me.dream());
		put(f, "friend", me.bestFriend());
		if (to != null && !to.isEmpty()) {
			f.add("to=" + clean(to));
			f.add("trust=" + trustWord(trust));
		}
		if (heard != null && !heard.isBlank()) f.add("heard=" + clean(heard.toLowerCase(Locale.ROOT)));
		return String.join("|", f);
	}

	private static void put(List<String> f, String key, String v) {
		v = clean(v);
		if (!v.isEmpty()) f.add(key + "=" + v.toLowerCase(Locale.ROOT));
	}
}
