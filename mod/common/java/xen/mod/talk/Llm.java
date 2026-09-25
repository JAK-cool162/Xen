package xen.mod.talk;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small language model runtime in plain Java: reads a GGUF file (Llama architecture with Q8_0 weights,
 * e.g. SmolLM2-360M-Instruct) and generates text. Port of xen/talk/llm.py.
 */
public final class Llm implements AutoCloseable {
	// ------------------------------------------------------------------------------ weights
	/** A Q8_0 matrix: rows x cols, blocks of 32 int8 values sharing one scale. */
	static final class Q8 {
		final int rows, cols;
		final byte[] q;
		final float[] scale;

		Q8(int rows, int cols, byte[] q, float[] scale) {
			this.rows = rows;
			this.cols = cols;
			this.q = q;
			this.scale = scale;
		}

		/** Row r dequantized into out. */
		void row(int r, float[] out) {
			int base = r * cols;
			for (int i = 0; i < cols; i++) out[i] = q[base + i] * scale[(base + i) >> 5];
		}
	}

	final int layers, dim, heads, kvHeads, headDim, hidden, vocab, context;
	final float eps, ropeBase;
	final Q8 embed, output;
	final float[] outNorm;
	final Q8[] wq, wk, wv, wo, gate, up, down;
	final float[][] attnNorm, ffnNorm;
	public final Tokenizer tokenizer;
	private final ExecutorService pool;
	private final int threads;

	// KV cache and a saved prefix (the persona), so each reply only processes what is new.
	private final float[][] kCache, vCache;
	private final float[] ropeCos, ropeSin;          // [position * headDim/2 + i]
	private int pos;
	/** Remembered starts of prompts (the chat persona, the ears): their attention cache, to skip re-reading them. */
	private final java.util.Map<String, float[][][]> prefixes = new java.util.LinkedHashMap<>();

	public Llm(String path, int threads, int context) throws IOException {
		Gguf g = new Gguf(path);
		Map<String, Object> m = g.meta;
		if (!"llama".equals(m.getOrDefault("general.architecture", "llama"))) throw new IOException("only Llama models are supported");
		layers = ((Number) m.get("llama.block_count")).intValue();
		dim = ((Number) m.get("llama.embedding_length")).intValue();
		heads = ((Number) m.get("llama.attention.head_count")).intValue();
		kvHeads = ((Number) m.getOrDefault("llama.attention.head_count_kv", heads)).intValue();
		hidden = ((Number) m.get("llama.feed_forward_length")).intValue();
		headDim = dim / heads;
		eps = ((Number) m.getOrDefault("llama.attention.layer_norm_rms_epsilon", 1e-5)).floatValue();
		ropeBase = ((Number) m.getOrDefault("llama.rope.freq_base", 10000.0)).floatValue();
		this.context = context;
		tokenizer = new Tokenizer(m);
		embed = g.q8("token_embd.weight");
		vocab = embed.rows;
		output = g.has("output.weight") ? g.q8("output.weight") : embed;
		outNorm = g.f32("output_norm.weight");
		wq = new Q8[layers]; wk = new Q8[layers]; wv = new Q8[layers]; wo = new Q8[layers];
		gate = new Q8[layers]; up = new Q8[layers]; down = new Q8[layers];
		attnNorm = new float[layers][]; ffnNorm = new float[layers][];
		for (int l = 0; l < layers; l++) {
			String p = "blk." + l + ".";
			wq[l] = g.q8(p + "attn_q.weight");
			wk[l] = g.q8(p + "attn_k.weight");
			wv[l] = g.q8(p + "attn_v.weight");
			wo[l] = g.q8(p + "attn_output.weight");
			gate[l] = g.q8(p + "ffn_gate.weight");
			up[l] = g.q8(p + "ffn_up.weight");
			down[l] = g.q8(p + "ffn_down.weight");
			attnNorm[l] = g.f32(p + "attn_norm.weight");
			ffnNorm[l] = g.f32(p + "ffn_norm.weight");
		}
		g.close();
		int kvDim = kvHeads * headDim;
		kCache = new float[layers][context * kvDim];
		vCache = new float[layers][context * kvDim];
		int half = headDim / 2;
		ropeCos = new float[context * half];
		ropeSin = new float[context * half];
		for (int p = 0; p < context; p++) {
			for (int i = 0; i < half; i++) {
				double angle = p * Math.pow(ropeBase, -2.0 * i / headDim);
				ropeCos[p * half + i] = (float) Math.cos(angle);
				ropeSin[p * half + i] = (float) Math.sin(angle);
			}
		}
		this.threads = Math.max(1, threads);
		pool = Executors.newFixedThreadPool(this.threads, r -> {
			Thread t = new Thread(r, "xen-chat-model");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
	}

	@Override
	public void close() {
		pool.shutdownNow();
	}

	// --------------------------------------------------------------------------- the maths
	/** out = W x, with x quantized to Q8_0 like llama.cpp; rows split across threads. */
	private void matmul(Q8 w, float[] x, float[] out) {
		int blocks = w.cols / 32;
		byte[] qx = new byte[w.cols];
		float[] sx = new float[blocks];
		for (int b = 0; b < blocks; b++) {
			float amax = 0;
			for (int i = 0; i < 32; i++) amax = Math.max(amax, Math.abs(x[b * 32 + i]));
			float d = amax / 127f;
			sx[b] = d;
			float inv = d == 0 ? 0 : 1 / d;
			for (int i = 0; i < 32; i++) qx[b * 32 + i] = (byte) Math.round(x[b * 32 + i] * inv);
		}
		int chunk = (w.rows + threads - 1) / threads;
		List<Future<?>> jobs = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int lo = t * chunk, hi = Math.min(w.rows, lo + chunk);
			if (lo >= hi) break;
			jobs.add(pool.submit(() -> {
				byte[] q = w.q;
				float[] s = w.scale;
				for (int r = lo; r < hi; r++) {
					int base = r * w.cols;
					float sum = 0;
					for (int b = 0; b < blocks; b++) {
						int dot = 0, o = base + b * 32, xo = b * 32;
						for (int i = 0; i < 32; i++) dot += q[o + i] * qx[xo + i];
						sum += dot * s[(base >> 5) + b] * sx[b];
					}
					out[r] = sum;
				}
			}));
		}
		for (Future<?> f : jobs) {
			try {
				f.get();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	private void rmsnorm(float[] x, float[] w, float[] out) {
		double ss = 0;
		for (float v : x) ss += v * v;
		float inv = (float) (1 / Math.sqrt(ss / x.length + eps));
		for (int i = 0; i < x.length; i++) out[i] = x[i] * inv * w[i];
	}

	private void rope(float[] v, int nHeads, int position) {
		int half = headDim / 2;
		for (int h = 0; h < nHeads; h++) {
			for (int i = 0; i < half; i++) {
				float c = ropeCos[position * half + i], s = ropeSin[position * half + i];
				int a = h * headDim + 2 * i;
				float x0 = v[a], x1 = v[a + 1];
				v[a] = x0 * c - x1 * s;
				v[a + 1] = x0 * s + x1 * c;
			}
		}
	}

	/** Feed one token at the current position; returns the logits for the next one. */
	public float[] forward(int token) {
		if (pos >= context) throw new IllegalStateException("context full");
		float[] x = new float[dim], h = new float[dim], q = new float[dim], att = new float[dim], tmp = new float[dim];
		int kvDim = kvHeads * headDim, group = heads / kvHeads;
		float[] k = new float[kvDim], v = new float[kvDim], g = new float[hidden], u = new float[hidden];
		embed.row(token, x);
		float[] scores = new float[pos + 1];
		for (int l = 0; l < layers; l++) {
			rmsnorm(x, attnNorm[l], h);
			matmul(wq[l], h, q);
			matmul(wk[l], h, k);
			matmul(wv[l], h, v);
			rope(q, heads, pos);
			rope(k, kvHeads, pos);
			System.arraycopy(k, 0, kCache[l], pos * kvDim, kvDim);
			System.arraycopy(v, 0, vCache[l], pos * kvDim, kvDim);
			float scale = (float) (1 / Math.sqrt(headDim));
			for (int hd = 0; hd < heads; hd++) {
				int kvh = hd / group, qo = hd * headDim;
				float max = Float.NEGATIVE_INFINITY;
				for (int t = 0; t <= pos; t++) {
					int ko = t * kvDim + kvh * headDim;
					float s = 0;
					for (int i = 0; i < headDim; i++) s += q[qo + i] * kCache[l][ko + i];
					scores[t] = s * scale;
					max = Math.max(max, scores[t]);
				}
				float sum = 0;
				for (int t = 0; t <= pos; t++) sum += scores[t] = (float) Math.exp(scores[t] - max);
				for (int i = 0; i < headDim; i++) att[qo + i] = 0;
				for (int t = 0; t <= pos; t++) {
					float p = scores[t] / sum;
					int vo = t * kvDim + kvh * headDim;
					for (int i = 0; i < headDim; i++) att[qo + i] += p * vCache[l][vo + i];
				}
			}
			matmul(wo[l], att, tmp);
			for (int i = 0; i < dim; i++) x[i] += tmp[i];
			rmsnorm(x, ffnNorm[l], h);
			matmul(gate[l], h, g);
			matmul(up[l], h, u);
			for (int i = 0; i < hidden; i++) g[i] = g[i] / (1f + (float) Math.exp(-g[i])) * u[i];
			matmul(down[l], g, tmp);
			for (int i = 0; i < dim; i++) x[i] += tmp[i];
		}
		rmsnorm(x, outNorm, h);
		float[] logits = new float[vocab];
		matmul(output, h, logits);
		pos++;
		return logits;
	}

	/** Process a fixed start of prompts once and remember it (the persona), to start from there. */
	public synchronized void savePrefix(String text) {
		pos = 0;
		for (int id : tokenizer.encode(text)) forward(id);
		int n = pos * kvHeads * headDim;
		float[][][] saved = new float[2][layers][];
		for (int l = 0; l < layers; l++) {
			saved[0][l] = java.util.Arrays.copyOf(kCache[l], n);
			saved[1][l] = java.util.Arrays.copyOf(vCache[l], n);
		}
		prefixes.put(text, saved);
	}

	/** Start a prompt: from a remembered prefix when it has one. Returns the part still to read. */
	private String restore(String prompt) {
		pos = 0;
		for (var e : prefixes.entrySet()) {
			if (!prompt.startsWith(e.getKey()) || prompt.length() == e.getKey().length()) continue;
			float[][][] saved = e.getValue();
			for (int l = 0; l < layers; l++) {
				System.arraycopy(saved[0][l], 0, kCache[l], 0, saved[0][l].length);
				System.arraycopy(saved[1][l], 0, vCache[l], 0, saved[1][l].length);
			}
			pos = saved[0][0].length / (kvHeads * headDim);
			return prompt.substring(e.getKey().length());
		}
		return prompt;
	}

	private float[] read(String prompt, int room) {
		List<Integer> ids = tokenizer.encode(restore(prompt));
		int space = context - pos - room;
		if (ids.size() > space) ids = ids.subList(ids.size() - space, ids.size());
		float[] logits = null;
		for (int id : ids) logits = forward(id);
		return logits;
	}

	/** Which option the model finds likeliest to come next: the log-probability of each whole option. */
	public synchronized float[] choose(String prompt, String[] options) {
		float[] start = read(prompt, 8);
		int at = pos;
		float[] scores = new float[options.length];
		for (int o = 0; o < options.length; o++) {
			float[] logits = start;
			List<Integer> ids = tokenizer.encode(options[o]);
			for (int j = 0; j < ids.size(); j++) {
				float max = Float.NEGATIVE_INFINITY;
				for (float v : logits) max = Math.max(max, v);
				double sum = 0;
				for (float v : logits) sum += Math.exp(v - max);
				scores[o] += (float) (logits[ids.get(j)] - max - Math.log(sum));
				if (j + 1 < ids.size()) logits = forward(ids.get(j));
			}
			pos = at;                                     // the cache before this position is untouched
		}
		return scores;
	}

	/** Continue a prompt with up to maxTokens (from a remembered prefix when the prompt starts with one). */
	public synchronized String generate(String prompt, int maxTokens, float temperature, float topP, long seed) {
		float[] logits = read(prompt, maxTokens);
		Random rng = new Random(seed);
		List<Integer> out = new ArrayList<>();
		int end = tokenizer.id("<|im_end|>");
		for (int n = 0; n < maxTokens && logits != null; n++) {
			int token = sample(logits, temperature, topP, rng);
			if (token == end || token == tokenizer.eos) break;
			out.add(token);
			logits = forward(token);
		}
		return tokenizer.decode(out);
	}

	static int sample(float[] logits, float temperature, float topP, Random rng) {
		int best = 0;
		for (int i = 1; i < logits.length; i++) if (logits[i] > logits[best]) best = i;
		if (temperature <= 0) return best;
		double max = logits[best];
		Integer[] order = new Integer[logits.length];
		double[] p = new double[logits.length];
		double sum = 0;
		for (int i = 0; i < logits.length; i++) {
			order[i] = i;
			sum += p[i] = Math.exp((logits[i] - max) / temperature);
		}
		java.util.Arrays.sort(order, (a, b) -> Double.compare(p[b], p[a]));
		double cum = 0, keep = 0;
		int n = 0;
		while (n < order.length && cum < topP * sum) {
			cum += p[order[n]];
			n++;
		}
		keep = cum;
		double r = rng.nextDouble() * keep;
		for (int i = 0; i < n; i++) if ((r -= p[order[i]]) <= 0) return order[i];
		return order[0];
	}

	// ------------------------------------------------------------------------------ GGUF file
	static final class Gguf implements AutoCloseable {
		final Map<String, Object> meta = new HashMap<>();
		final Map<String, long[]> tensors = new HashMap<>();   // name -> {type, offset, dims...}
		final RandomAccessFile file;
		final MappedByteBuffer buf;
		long dataStart;

		Gguf(String path) throws IOException {
			file = new RandomAccessFile(path, "r");
			FileChannel ch = file.getChannel();
			buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
			buf.order(ByteOrder.LITTLE_ENDIAN);
			byte[] magic = new byte[4];
			buf.get(magic);
			if (!"GGUF".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException(path + " is not a GGUF file");
			int version = buf.getInt();
			if (version < 2) throw new IOException("GGUF v" + version + " is too old");
			long nTensors = buf.getLong(), nKv = buf.getLong();
			for (long i = 0; i < nKv; i++) {
				String key = string();
				meta.put(key, value(buf.getInt()));
			}
			for (long i = 0; i < nTensors; i++) {
				String name = string();
				int dims = buf.getInt();
				long[] info = new long[2 + dims];
				for (int d = 0; d < dims; d++) info[2 + d] = buf.getLong();
				info[0] = buf.getInt();
				info[1] = buf.getLong();
				tensors.put(name, info);
			}
			long align = ((Number) meta.getOrDefault("general.alignment", 32)).longValue();
			dataStart = (buf.position() + align - 1) / align * align;
		}

		String string() {
			byte[] b = new byte[(int) buf.getLong()];
			buf.get(b);
			return new String(b, StandardCharsets.UTF_8);
		}

		Object value(int type) {
			return switch (type) {
				case 0 -> buf.get() & 0xFF;
				case 1 -> (int) buf.get();
				case 2 -> buf.getShort() & 0xFFFF;
				case 3 -> (int) buf.getShort();
				case 4 -> buf.getInt() & 0xFFFFFFFFL;
				case 5 -> buf.getInt();
				case 6 -> buf.getFloat();
				case 7 -> buf.get() != 0;
				case 8 -> string();
				case 9 -> {
					int item = buf.getInt();
					long n = buf.getLong();
					List<Object> list = new ArrayList<>((int) n);
					for (long i = 0; i < n; i++) list.add(value(item));
					yield list;
				}
				case 10 -> buf.getLong();
				case 11 -> buf.getLong();
				case 12 -> buf.getDouble();
				default -> throw new IllegalStateException("unknown GGUF value type " + type);
			};
		}

		boolean has(String name) {
			return tensors.containsKey(name);
		}

		Q8 q8(String name) throws IOException {
			long[] info = tensors.get(name);
			if (info == null) throw new IOException("missing tensor " + name);
			int cols = (int) info[2], rows = info.length > 3 ? (int) info[3] : 1;
			long n = (long) rows * cols;
			byte[] q = new byte[(int) n];
			float[] s = new float[(int) (n / 32)];
			ByteBuffer b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			long start = dataStart + info[1];
			if (info[0] == 8) {                              // Q8_0: fp16 scale + 32 int8
				for (int blk = 0; blk < s.length; blk++) {
					int o = (int) (start + blk * 34L);
					s[blk] = Float.float16ToFloat(b.getShort(o));
					b.get(o + 2, q, blk * 32, 32);
				}
			} else {
				float[] f = floats(info, start, (int) n, b);
				for (int blk = 0; blk < s.length; blk++) {   // quantize F16/F32 weights once
					float amax = 0;
					for (int i = 0; i < 32; i++) amax = Math.max(amax, Math.abs(f[blk * 32 + i]));
					s[blk] = amax / 127f;
					float inv = s[blk] == 0 ? 0 : 1 / s[blk];
					for (int i = 0; i < 32; i++) q[blk * 32 + i] = (byte) Math.round(f[blk * 32 + i] * inv);
				}
			}
			return new Q8(rows, cols, q, s);
		}

		float[] f32(String name) throws IOException {
			long[] info = tensors.get(name);
			if (info == null) throw new IOException("missing tensor " + name);
			long n = 1;
			for (int d = 2; d < info.length; d++) n *= info[d];
			return floats(info, dataStart + info[1], (int) n, buf.duplicate().order(ByteOrder.LITTLE_ENDIAN));
		}

		static float[] floats(long[] info, long start, int n, ByteBuffer b) throws IOException {
			float[] f = new float[n];
			if (info[0] == 0) for (int i = 0; i < n; i++) f[i] = b.getFloat((int) (start + 4L * i));
			else if (info[0] == 1) for (int i = 0; i < n; i++) f[i] = Float.float16ToFloat(b.getShort((int) (start + 2L * i)));
			else throw new IOException("tensor type " + info[0] + " is not supported (use Q8_0, F16 or F32)");
			return f;
		}

		@Override
		public void close() throws IOException {
			file.close();
		}
	}

	// ------------------------------------------------------------------------------ tokenizer
	/** GPT-2 style byte-level BPE from the GGUF file (SmolLM splits digits one by one). */
	public static final class Tokenizer {
		private static final Pattern PIECES = Pattern.compile(
				"'s|'t|'re|'ve|'m|'ll|'d| ?[^\\W\\d_]+| ?\\d| ?(?:[^\\s\\w]|_)+|\\s+(?!\\S)|\\s+", Pattern.UNICODE_CHARACTER_CLASS);
		final List<String> tokens;
		final Map<String, Integer> ids = new HashMap<>();
		final Map<String, Integer> ranks = new HashMap<>();
		final List<String> special = new ArrayList<>();
		final Pattern specialPattern;
		final int eos;
		final char[] byteToChar = new char[256];
		final Map<Character, Integer> charToByte = new HashMap<>();
		private final Map<String, int[]> cache = new HashMap<>();

		@SuppressWarnings("unchecked")
		Tokenizer(Map<String, Object> m) {
			tokens = (List<String>) m.get("tokenizer.ggml.tokens");
			for (int i = 0; i < tokens.size(); i++) ids.put(tokens.get(i), i);
			List<String> merges = (List<String>) m.get("tokenizer.ggml.merges");
			for (int i = 0; i < merges.size(); i++) ranks.put(merges.get(i), i);
			List<Object> types = (List<Object>) m.get("tokenizer.ggml.token_type");
			for (int i = 0; i < tokens.size(); i++) {
				int t = types == null ? 1 : ((Number) types.get(i)).intValue();
				if ((t == 3 || t == 4) && !tokens.get(i).isEmpty()) special.add(tokens.get(i));
			}
			special.sort((a, b) -> b.length() - a.length());
			StringBuilder alt = new StringBuilder();
			for (String s : special) alt.append(alt.length() > 0 ? "|" : "").append(Pattern.quote(s));
			specialPattern = special.isEmpty() ? null : Pattern.compile(alt.toString());
			eos = ((Number) m.getOrDefault("tokenizer.ggml.eos_token_id", 2)).intValue();
			List<Integer> bs = new ArrayList<>();
			for (int b = '!'; b <= '~'; b++) bs.add(b);
			for (int b = 0xA1; b <= 0xAC; b++) bs.add(b);
			for (int b = 0xAE; b <= 0xFF; b++) bs.add(b);
			int extra = 0;
			for (int b = 0; b < 256; b++) {
				char c = bs.contains(b) ? (char) b : (char) (256 + extra++);
				byteToChar[b] = c;
				charToByte.put(c, b);
			}
		}

		public int id(String token) {
			return ids.getOrDefault(token, -1);
		}

		private int[] bpe(String word) {
			int[] hit = cache.get(word);
			if (hit != null) return hit;
			List<String> parts = new ArrayList<>();
			word.codePoints().forEach(cp -> parts.add(new String(Character.toChars(cp))));
			while (parts.size() > 1) {
				int best = -1, bestRank = Integer.MAX_VALUE;
				for (int i = 0; i + 1 < parts.size(); i++) {
					Integer r = ranks.get(parts.get(i) + " " + parts.get(i + 1));
					if (r != null && r < bestRank) {
						bestRank = r;
						best = i;
					}
				}
				if (best < 0) break;
				parts.set(best, parts.get(best) + parts.remove(best + 1));
			}
			int[] out = parts.stream().filter(ids::containsKey).mapToInt(ids::get).toArray();
			if (cache.size() < 50_000) cache.put(word, out);
			return out;
		}

		public List<Integer> encode(String text) {
			List<Integer> out = new ArrayList<>();
			List<String> chunks = new ArrayList<>();
			if (specialPattern == null) chunks.add(text);
			else {
				Matcher sm = specialPattern.matcher(text);
				int last = 0;
				while (sm.find()) {
					chunks.add(text.substring(last, sm.start()));
					chunks.add(sm.group());
					last = sm.end();
				}
				chunks.add(text.substring(last));
			}
			for (String chunk : chunks) {
				if (chunk.isEmpty()) continue;
				if (special.contains(chunk)) {
					out.add(ids.get(chunk));
					continue;
				}
				Matcher pm = PIECES.matcher(chunk);
				while (pm.find()) {
					StringBuilder sb = new StringBuilder();
					for (byte b : pm.group().getBytes(StandardCharsets.UTF_8)) sb.append(byteToChar[b & 0xFF]);
					for (int id : bpe(sb.toString())) out.add(id);
				}
			}
			return out;
		}

		public String decode(List<Integer> list) {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			for (int id : list) {
				String t = tokens.get(id);
				if (special.contains(t)) continue;
				for (char c : t.toCharArray()) bytes.write(charToByte.getOrDefault(c, (int) '?'));
			}
			return bytes.toString(StandardCharsets.UTF_8);
		}
	}
}
