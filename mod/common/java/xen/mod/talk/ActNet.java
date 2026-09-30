package xen.mod.talk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * The small network that decides what kind of thing to say back: from what it heard (a greeting, a question about what
 * it likes, a joke, an insult...) and who it is (its tone, temper, how chatty, kind, curious, how much it trusts whoever
 * spoke, how it feels), which reply fits: greet back, say how it feels, give its opinion, agree, ask back, crack a joke...
 * One hidden layer, a few thousand weights: trained in a moment when the mod first runs (from a teacher that knows what
 * people usually say back), then it keeps learning from how people react (laughing, thanking and praising teach it one
 * thing; "what?" and insults another). The words themselves are built afterwards, from the word library.
 */
public final class ActNet {
	public static final String[] ACTS = {"greet", "bye", "welcome", "feeling", "doing", "opinion", "agree", "disagree", "ask back", "react",
			"joke", "care", "proud", "hurt", "dunno", "share", "accept", "decline"};
	public static final int GREET = 0, BYE = 1, WELCOME = 2, FEELING = 3, DOING = 4, OPINION = 5, AGREE = 6, DISAGREE = 7, ASK_BACK = 8,
			REACT = 9, JOKE = 10, CARE = 11, PROUD = 12, HURT = 13, DUNNO = 14, SHARE = 15, ACCEPT = 16, DECLINE = 17, A = 18;
	/** Inputs (see {@link Voice#features}). */
	public static final int F = 43;
	static final int H = 32;
	private static final int MAGIC = 0x58574f52;                                        // "XWOR"

	final float[] w1 = new float[H * F], b1 = new float[H], w2 = new float[A * H], b2 = new float[A];
	/** How many times it learned from someone's reaction. */
	public int lessons;

	ActNet(long seed) {
		Random r = new Random(seed);
		for (int i = 0; i < w1.length; i++) w1[i] = (float) (r.nextGaussian() * Math.sqrt(2.0 / F));
		for (int i = 0; i < w2.length; i++) w2[i] = (float) (r.nextGaussian() * Math.sqrt(1.0 / H));
	}

	private float[] hidden(float[] x) {
		float[] h = new float[H];
		for (int j = 0; j < H; j++) {
			float s = b1[j];
			for (int i = 0; i < F; i++) s += w1[j * F + i] * x[i];
			h[j] = Math.max(0, s);
		}
		return h;
	}

	/** How likely each reply is, for these inputs. */
	public synchronized float[] probs(float[] x) {
		return softmax(logits(hidden(x)));
	}

	private float[] logits(float[] h) {
		float[] z = new float[A];
		for (int k = 0; k < A; k++) {
			float s = b2[k];
			for (int j = 0; j < H; j++) s += w2[k * H + j] * h[j];
			z[k] = s;
		}
		return z;
	}

	private static float[] softmax(float[] z) {
		float max = Float.NEGATIVE_INFINITY, sum = 0;
		for (float v : z) max = Math.max(max, v);
		float[] p = new float[z.length];
		for (int k = 0; k < z.length; k++) sum += p[k] = (float) Math.exp(z[k] - max);
		for (int k = 0; k < z.length; k++) p[k] /= sum;
		return p;
	}

	/** One step toward a wanted distribution (cross-entropy), or toward/away from a reply it gave (grad = its weight). */
	private void step(float[] x, float[] grad, float lr) {
		float[] h = hidden(x);
		float[] dh = new float[H];
		for (int k = 0; k < A; k++) {
			float g = grad[k];
			if (g == 0) continue;
			for (int j = 0; j < H; j++) {
				dh[j] += g * w2[k * H + j];
				w2[k * H + j] -= lr * g * h[j];
			}
			b2[k] -= lr * g;
		}
		for (int j = 0; j < H; j++) {
			if (h[j] <= 0 || dh[j] == 0) continue;
			for (int i = 0; i < F; i++) w1[j * F + i] -= lr * dh[j] * x[i];
			b1[j] -= lr * dh[j];
		}
	}

	/** Learn to answer like the teacher (targets: a distribution over replies per example). */
	synchronized void train(float[][] xs, float[][] targets, int epochs, float lr, Random r) {
		int n = xs.length;
		int[] order = new int[n];
		for (int i = 0; i < n; i++) order[i] = i;
		for (int e = 0; e < epochs; e++) {
			for (int i = n - 1; i > 0; i--) {
				int j = r.nextInt(i + 1), t = order[i];
				order[i] = order[j];
				order[j] = t;
			}
			for (int i : order) {
				float[] p = softmax(logits(hidden(xs[i])));
				float[] g = new float[A];
				for (int k = 0; k < A; k++) g[k] = p[k] - targets[i][k];
				step(xs[i], g, lr);
			}
		}
	}

	/** How someone took what it said: reward above 0 makes that reply likelier next time in the same situation. */
	public synchronized void learn(float[] x, int act, float reward, float lr) {
		float[] p = softmax(logits(hidden(x)));
		float[] g = new float[A];
		for (int k = 0; k < A; k++) g[k] = reward * (p[k] - (k == act ? 1 : 0));   // (policy gradient: descent on -reward * log p)
		step(x, g, lr);
		lessons++;
	}

	public synchronized void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
			out.writeInt(MAGIC);
			out.writeInt(F);
			out.writeInt(H);
			out.writeInt(A);
			out.writeInt(lessons);
			for (float[] a : new float[][] {w1, b1, w2, b2}) for (float v : a) out.writeFloat(v);
		}
	}

	/** A saved one, or null if there's none (or it's from another version of the inputs). */
	public static ActNet load(Path file) {
		if (!Files.exists(file)) return null;
		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			if (in.readInt() != MAGIC || in.readInt() != F || in.readInt() != H || in.readInt() != A) return null;
			ActNet net = new ActNet(0);
			net.lessons = in.readInt();
			for (float[] a : new float[][] {net.w1, net.b1, net.w2, net.b2}) for (int i = 0; i < a.length; i++) a[i] = in.readFloat();
			return net;
		} catch (IOException e) {
			return null;
		}
	}
}
