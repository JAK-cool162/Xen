package xen.mod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Xen Glance: a very small picture network (a CNN) that looks at what a Xen's eyes drew (the 64 by 36 sight-line
 * picture, halved to 32 by 18: its colours and how far each pixel is, nothing else: no labels, no block names) and
 * says what it makes of it, the way a player takes in a screen at a glance: a monster close by, one near, a drop in
 * front, a cave mouth. Three layers of 3x3 filters (8, 16, 16 of them), pooled, then four answers: about 4,000
 * numbers in all, a fraction of a millisecond a picture, light enough for a phone. Trained in Python (xen/glance) on
 * Xens' own pictures, against what was really there; this runs it (assets/xen/glance.json), and goes on learning
 * while they play ({@link GlanceLearner}: the game knows what was really there, so every picture is a lesson).
 * <p>Its eyes' rules still read the picture too; this is the part that learned to see.
 */
public final class Glance {
	public static final int W = 32, H = 18, C = 4;
	public static final String[] HEADS = {"monster close", "monster near", "drop ahead", "cave mouth"};
	public static final int CLOSE = 0, NEAR = 1, DROP = 2, CAVE = 3;
	private static final int[] FILTERS = {C, 8, 16, 16};

	/** [layer][out][in][3*3] and [layer][out]; then the answers: [head][2 * last filters] and [head]. */
	private final float[][][][] conv = new float[3][][][];
	private final float[][] bias = new float[3][];
	private final float[][] head;
	private final float[] headBias;
	public final int trainedOn;

	private Glance(JsonObject o) {
		JsonArray layers = o.getAsJsonArray("conv");
		for (int l = 0; l < 3; l++) {
			JsonObject L = layers.get(l).getAsJsonObject();
			int out = FILTERS[l + 1], in = FILTERS[l];
			conv[l] = new float[out][in][9];
			bias[l] = new float[out];
			JsonArray w = L.getAsJsonArray("w"), b = L.getAsJsonArray("b");
			for (int i = 0; i < out; i++) {
				JsonArray wi = w.get(i).getAsJsonArray();
				for (int j = 0; j < in; j++) {
					JsonArray wij = wi.get(j).getAsJsonArray();
					for (int k = 0; k < 9; k++) conv[l][i][j][k] = wij.get(k).getAsFloat();
				}
				bias[l][i] = b.get(i).getAsFloat();
			}
		}
		JsonObject d = o.getAsJsonObject("head");
		JsonArray w = d.getAsJsonArray("w"), b = d.getAsJsonArray("b");
		head = new float[HEADS.length][2 * FILTERS[3]];
		headBias = new float[HEADS.length];
		for (int i = 0; i < HEADS.length; i++) {
			for (int j = 0; j < 2 * FILTERS[3]; j++) head[i][j] = w.get(i).getAsJsonArray().get(j).getAsFloat();
			headBias[i] = b.get(i).getAsFloat();
		}
		trainedOn = o.has("trained_on") ? o.get("trained_on").getAsInt() : 0;
	}

	public static Glance read(Reader r) {
		return new Glance(JsonParser.parseReader(r).getAsJsonObject());
	}

	/** The one that comes with the mod, or null (none, or it can't be read). */
	public static Glance load() {
		try (InputStream in = Glance.class.getResourceAsStream("/assets/xen/glance.json")) {
			if (in == null) return null;
			return read(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** A 3x3 filter layer (zero padding), then ReLU. x: [in][h*w]. */
	private static float[][] convolve(float[][] x, int h, int w, float[][][] k, float[] b) {
		float[][] y = new float[k.length][h * w];
		for (int o = 0; o < k.length; o++) {
			float[] yo = y[o];
			java.util.Arrays.fill(yo, b[o]);
			for (int i = 0; i < x.length; i++) {
				float[] xi = x[i], ki = k[o][i];
				for (int r = 0; r < h; r++)
					for (int c = 0; c < w; c++) {
						float s = 0;
						for (int dr = -1; dr <= 1; dr++) {
							int rr = r + dr;
							if (rr < 0 || rr >= h) continue;
							for (int dc = -1; dc <= 1; dc++) {
								int cc = c + dc;
								if (cc < 0 || cc >= w) continue;
								s += ki[(dr + 1) * 3 + dc + 1] * xi[rr * w + cc];
							}
						}
						yo[r * w + c] += s;
					}
			}
			for (int p = 0; p < yo.length; p++) if (yo[p] < 0) yo[p] = 0;
		}
		return y;
	}

	/** 2x2 max pooling (an odd row or column at the end left out, as torch does). */
	private static float[][] pool(float[][] x, int h, int w) {
		int h2 = h / 2, w2 = w / 2;
		float[][] y = new float[x.length][h2 * w2];
		for (int ch = 0; ch < x.length; ch++)
			for (int r = 0; r < h2; r++)
				for (int c = 0; c < w2; c++) {
					float[] xc = x[ch];
					y[ch][r * w2 + c] = Math.max(Math.max(xc[2 * r * w + 2 * c], xc[2 * r * w + 2 * c + 1]), Math.max(xc[(2 * r + 1) * w + 2 * c], xc[(2 * r + 1) * w + 2 * c + 1]));
				}
		return y;
	}

	/** What it makes of a picture ([C][H*W]: red, green, blue, distance, each 0 to 1): the four answers, 0 to 1. */
	public float[] run(float[][] x) {
		float[][] a = convolve(x, H, W, conv[0], bias[0]);
		a = pool(a, H, W);                                                       // 16 x 9
		a = convolve(a, H / 2, W / 2, conv[1], bias[1]);
		a = pool(a, H / 2, W / 2);                                               // 8 x 4
		int h = H / 4, w = W / 4;
		a = convolve(a, h, w, conv[2], bias[2]);
		float[] f = new float[2 * a.length];
		for (int ch = 0; ch < a.length; ch++) {
			float sum = 0, max = 0;
			for (float v : a[ch]) {
				sum += v;
				max = Math.max(max, v);
			}
			f[ch] = sum / a[ch].length;
			f[a.length + ch] = max;
		}
		float[] out = new float[HEADS.length];
		for (int i = 0; i < out.length; i++) {
			double z = headBias[i];
			for (int j = 0; j < f.length; j++) z += head[i][j] * f[j];
			out[i] = (float) (1 / (1 + Math.exp(-z)));
		}
		return out;
	}

	// ------------------------------------------------------------------------------ learning while it plays
	private Glance(Glance g) {
		for (int l = 0; l < 3; l++) {
			conv[l] = new float[g.conv[l].length][][];
			for (int o = 0; o < conv[l].length; o++) {
				conv[l][o] = new float[g.conv[l][o].length][];
				for (int i = 0; i < conv[l][o].length; i++) conv[l][o][i] = g.conv[l][o][i].clone();
			}
			bias[l] = g.bias[l].clone();
		}
		head = new float[g.head.length][];
		for (int i = 0; i < head.length; i++) head[i] = g.head[i].clone();
		headBias = g.headBias.clone();
		trainedOn = g.trainedOn;
	}

	/** A copy of it (to learn on, or to use while the other learns). */
	public Glance copy() {
		return new Glance(this);
	}

	/** Every number in it, in a fixed order (filters, their biases, the answers). */
	private java.util.List<float[]> arrays() {
		java.util.List<float[]> out = new java.util.ArrayList<>();
		for (int l = 0; l < 3; l++) {
			for (float[][] o : conv[l]) java.util.Collections.addAll(out, o);
			out.add(bias[l]);
		}
		java.util.Collections.addAll(out, head);
		out.add(headBias);
		return out;
	}

	/** All its numbers to nought (a copy used to add up how each should change). */
	public void zero() {
		for (float[] a : arrays()) java.util.Arrays.fill(a, 0);
	}

	/** How wrong four answers are (the sum over them of the log loss), against what was really there (0 or 1 each). */
	public static float loss(float[] out, float[] y) {
		double l = 0;
		for (int i = 0; i < out.length; i++) {
			double o = Math.min(1 - 1e-6, Math.max(1e-6, out[i]));
			l -= y[i] * Math.log(o) + (1 - y[i]) * Math.log(1 - o);
		}
		return (float) l;
	}

	/**
	 * One picture's lesson: how wrong it is (returned), and how each of its numbers should change to be less so, added
	 * to g (the same layers backwards: the answers, the pooling, each layer of filters).
	 */
	public float backprop(float[][] x, float[] y, Glance g) {
		return backprop(x, y, g, null);
	}

	/**
	 * The same, with each question's yes counted w times over (null: once): a thing seen once in thirty pictures
	 * otherwise teaches it that "no" is nearly always right, and it never says yes (4 of 23 drops caught).
	 */
	public float backprop(float[][] x, float[] y, Glance g, float[] w) {
		int h2 = H / 2, w2 = W / 2, h3 = h2 / 2, w3 = w2 / 2;
		float[][] z1 = convZ(x, H, W, conv[0], bias[0]), a1 = relu(z1);
		int[][] i1 = new int[a1.length][h2 * w2];
		float[][] p1 = poolArg(a1, H, W, i1);
		float[][] z2 = convZ(p1, h2, w2, conv[1], bias[1]), a2 = relu(z2);
		int[][] i2 = new int[a2.length][h3 * w3];
		float[][] p2 = poolArg(a2, h2, w2, i2);
		float[][] z3 = convZ(p2, h3, w3, conv[2], bias[2]), a3 = relu(z3);
		int nc = a3.length, n3 = h3 * w3;
		float[] f = new float[2 * nc];
		int[] at = new int[nc];
		for (int ch = 0; ch < nc; ch++) {
			float sum = 0, max = 0;
			at[ch] = -1;
			for (int q = 0; q < n3; q++) {
				float v = a3[ch][q];
				sum += v;
				if (v > max) {
					max = v;
					at[ch] = q;
				}
			}
			f[ch] = sum / n3;
			f[nc + ch] = max;
		}
		float[] out = new float[HEADS.length], df = new float[f.length];
		for (int i = 0; i < out.length; i++) {
			double z = headBias[i];
			for (int j = 0; j < f.length; j++) z += head[i][j] * f[j];
			out[i] = (float) (1 / (1 + Math.exp(-z)));
			float wy = w != null ? w[i] * y[i] : y[i];
			float dz = out[i] * (wy + 1 - y[i]) - wy;                            // (the log loss through the sigmoid, its yes weighted)
			g.headBias[i] += dz;
			for (int j = 0; j < f.length; j++) {
				g.head[i][j] += dz * f[j];
				df[j] += dz * head[i][j];
			}
		}
		float[][] dz3 = new float[nc][n3];
		for (int ch = 0; ch < nc; ch++) {
			for (int q = 0; q < n3; q++) dz3[ch][q] = df[ch] / n3;
			if (at[ch] >= 0) dz3[ch][at[ch]] += df[nc + ch];
			for (int q = 0; q < n3; q++) if (z3[ch][q] <= 0) dz3[ch][q] = 0;
		}
		float[][] dz2 = poolBack(convBack(p2, h3, w3, conv[2], dz3, g.conv[2], g.bias[2], true), i2, h2 * w2);
		for (int ch = 0; ch < dz2.length; ch++) for (int q = 0; q < dz2[ch].length; q++) if (z2[ch][q] <= 0) dz2[ch][q] = 0;
		float[][] dz1 = poolBack(convBack(p1, h2, w2, conv[1], dz2, g.conv[1], g.bias[1], true), i1, H * W);
		for (int ch = 0; ch < dz1.length; ch++) for (int q = 0; q < dz1[ch].length; q++) if (z1[ch][q] <= 0) dz1[ch][q] = 0;
		convBack(x, H, W, conv[0], dz1, g.conv[0], g.bias[0], false);
		return loss(out, y);
	}

	/** Adds to g (this) a pull of each of at's numbers back toward anchor's, k times how far it's gone (what it knew isn't lost). */
	public void pull(Glance at, Glance anchor, float k) {
		var G = arrays();
		var P = at.arrays();
		var A = anchor.arrays();
		for (int a = 0; a < G.size(); a++) {
			float[] g = G.get(a), p = P.get(a), an = A.get(a);
			for (int i = 0; i < g.length; i++) g[i] += k * (p[i] - an[i]);
		}
	}

	/** A step its numbers take, the way g says (Adam: m and v keep the running averages; t counts the steps, from 1). */
	public void adam(Glance g, Glance m, Glance v, int t, float lr, float scale) {
		var P = arrays();
		var G = g.arrays();
		var M = m.arrays();
		var V = v.arrays();
		double c1 = 1 - Math.pow(0.9, t), c2 = 1 - Math.pow(0.999, t);
		for (int a = 0; a < P.size(); a++) {
			float[] p = P.get(a), gr = G.get(a), ma = M.get(a), va = V.get(a);
			for (int k = 0; k < p.length; k++) {
				float d = gr[k] * scale;
				ma[k] = 0.9f * ma[k] + 0.1f * d;
				va[k] = 0.999f * va[k] + 0.001f * d * d;
				p[k] -= (float) (lr * (ma[k] / c1) / (Math.sqrt(va[k] / c2) + 1e-8));
			}
		}
	}

	/** It as the file it's read from (assets/xen/glance.json's shape), having learned from this many pictures. */
	public JsonObject toJson(int pictures) {
		JsonObject o = new JsonObject();
		JsonArray layers = new JsonArray();
		for (int l = 0; l < 3; l++) {
			JsonObject L = new JsonObject();
			JsonArray w = new JsonArray(), b = new JsonArray();
			for (float[][] out : conv[l]) {
				JsonArray wo = new JsonArray();
				for (float[] in : out) {
					JsonArray wi = new JsonArray();
					for (float v : in) wi.add(v);
					wo.add(wi);
				}
				w.add(wo);
			}
			for (float v : bias[l]) b.add(v);
			L.add("w", w);
			L.add("b", b);
			layers.add(L);
		}
		o.add("conv", layers);
		JsonObject d = new JsonObject();
		JsonArray w = new JsonArray(), b = new JsonArray();
		for (float[] h : head) {
			JsonArray wi = new JsonArray();
			for (float v : h) wi.add(v);
			w.add(wi);
		}
		for (float v : headBias) b.add(v);
		d.add("w", w);
		d.add("b", b);
		o.add("head", d);
		o.addProperty("trained_on", pictures);
		return o;
	}

	/** A 3x3 filter layer as {@link #convolve}, before its ReLU. */
	private static float[][] convZ(float[][] x, int h, int w, float[][][] k, float[] b) {
		float[][] y = new float[k.length][h * w];
		for (int o = 0; o < k.length; o++) {
			float[] yo = y[o];
			java.util.Arrays.fill(yo, b[o]);
			for (int i = 0; i < x.length; i++) {
				float[] xi = x[i], ki = k[o][i];
				for (int r = 0; r < h; r++)
					for (int c = 0; c < w; c++) {
						float s = 0;
						for (int dr = -1; dr <= 1; dr++) {
							int rr = r + dr;
							if (rr < 0 || rr >= h) continue;
							for (int dc = -1; dc <= 1; dc++) {
								int cc = c + dc;
								if (cc < 0 || cc >= w) continue;
								s += ki[(dr + 1) * 3 + dc + 1] * xi[rr * w + cc];
							}
						}
						yo[r * w + c] += s;
					}
			}
		}
		return y;
	}

	private static float[][] relu(float[][] z) {
		float[][] a = new float[z.length][];
		for (int ch = 0; ch < z.length; ch++) {
			a[ch] = z[ch].clone();
			for (int q = 0; q < a[ch].length; q++) if (a[ch][q] < 0) a[ch][q] = 0;
		}
		return a;
	}

	/** {@link #pool}, keeping which of the four each came from. */
	private static float[][] poolArg(float[][] x, int h, int w, int[][] from) {
		int h2 = h / 2, w2 = w / 2;
		float[][] y = new float[x.length][h2 * w2];
		for (int ch = 0; ch < x.length; ch++)
			for (int r = 0; r < h2; r++)
				for (int c = 0; c < w2; c++) {
					int best = 2 * r * w + 2 * c;
					for (int q : new int[] {2 * r * w + 2 * c + 1, (2 * r + 1) * w + 2 * c, (2 * r + 1) * w + 2 * c + 1}) if (x[ch][q] > x[ch][best]) best = q;
					y[ch][r * w2 + c] = x[ch][best];
					from[ch][r * w2 + c] = best;
				}
		return y;
	}

	private static float[][] poolBack(float[][] dy, int[][] from, int n) {
		float[][] dx = new float[dy.length][n];
		for (int ch = 0; ch < dy.length; ch++) for (int q = 0; q < dy[ch].length; q++) dx[ch][from[ch][q]] += dy[ch][q];
		return dx;
	}

	/** A filter layer backwards: what its filters and biases should do (added to dk, db), and (if asked) what its input should. */
	private static float[][] convBack(float[][] x, int h, int w, float[][][] k, float[][] dz, float[][][] dk, float[] db, boolean wantDx) {
		float[][] dx = wantDx ? new float[x.length][h * w] : null;
		for (int o = 0; o < k.length; o++)
			for (int r = 0; r < h; r++)
				for (int c = 0; c < w; c++) {
					float g = dz[o][r * w + c];
					if (g == 0) continue;
					db[o] += g;
					for (int i = 0; i < x.length; i++) {
						float[] xi = x[i], ki = k[o][i], dki = dk[o][i];
						float[] dxi = wantDx ? dx[i] : null;
						for (int dr = -1; dr <= 1; dr++) {
							int rr = r + dr;
							if (rr < 0 || rr >= h) continue;
							for (int dc = -1; dc <= 1; dc++) {
								int cc = c + dc;
								if (cc < 0 || cc >= w) continue;
								int kk = (dr + 1) * 3 + dc + 1, q = rr * w + cc;
								dki[kk] += g * xi[q];
								if (dxi != null) dxi[q] += g * ki[kk];
							}
						}
					}
				}
		return dx;
	}
}
