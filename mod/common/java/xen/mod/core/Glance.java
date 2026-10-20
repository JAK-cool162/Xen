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
 * Xens' own pictures, against what was really there; this runs it (assets/xen/glance.json).
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
}
