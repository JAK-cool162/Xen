package xen.mod.core;

import java.util.Random;

/** Fully connected ReLU network with a linear output, trained with Adam (port of xen/brain/nn.py). */
public final class Mlp {
	public final int[] sizes;
	public final float[] flat;
	final int[] wOff, bOff;
	private final float[] m, v;
	private int t;
	public float lr;
	public float maxGradNorm = 10f;
	private float[][] cache;              // activations of the last training forward pass

	public Mlp(int[] sizes, float lr, long seed) {
		this.sizes = sizes.clone();
		this.lr = lr;
		int layers = sizes.length - 1, total = 0;
		wOff = new int[layers];
		bOff = new int[layers];
		for (int i = 0; i < layers; i++) {
			wOff[i] = total;
			total += sizes[i] * sizes[i + 1];
			bOff[i] = total;
			total += sizes[i + 1];
		}
		flat = new float[total];
		m = new float[total];
		v = new float[total];
		Random rng = new Random(seed);
		for (int i = 0; i < layers; i++) {
			double scale = Math.sqrt(2.0 / sizes[i]) * (i == layers - 1 ? 0.1 : 1.0);
			for (int j = 0; j < sizes[i] * sizes[i + 1]; j++) flat[wOff[i] + j] = (float) (rng.nextGaussian() * scale);
		}
	}

	public Mlp copy() {
		Mlp c = new Mlp(sizes, lr, 0);
		System.arraycopy(flat, 0, c.flat, 0, flat.length);
		return c;
	}

	public void softUpdate(Mlp source, float tau) {
		for (int i = 0; i < flat.length; i++) flat[i] += tau * (source.flat[i] - flat[i]);
	}

	/** Forward a batch (rows = samples). keep = remember activations for backward(). */
	public float[][] forward(float[][] x, boolean keep) {
		int layers = sizes.length - 1;
		float[][] acts = keep ? new float[layers + 1][] : null;
		float[] h = flatten(x);
		int n = x.length;
		if (keep) acts[0] = h;
		for (int l = 0; l < layers; l++) {
			int in = sizes[l], out = sizes[l + 1];
			float[] next = new float[n * out];
			for (int s = 0; s < n; s++) {
				int ro = s * out, ri = s * in;
				System.arraycopy(flat, bOff[l], next, ro, out);
				for (int i = 0; i < in; i++) {
					float a = h[ri + i];
					if (a == 0f) continue;
					int w = wOff[l] + i * out;
					for (int o = 0; o < out; o++) next[ro + o] += a * flat[w + o];
				}
				if (l < layers - 1) for (int o = 0; o < out; o++) if (next[ro + o] < 0) next[ro + o] = 0;
			}
			h = next;
			if (keep) acts[l + 1] = h;
		}
		if (keep) cache = acts;
		return unflatten(h, n, sizes[layers]);
	}

	public float[] predict(float[] x) {
		return forward(new float[][] {x}, false)[0];
	}

	/** Backprop dLoss/dOutput of the last forward(keep=true) and take an Adam step. */
	public void backward(float[][] gradOut) {
		int layers = sizes.length - 1, n = gradOut.length;
		float[] grad = new float[flat.length];
		float[] d = flatten(gradOut);
		for (int l = layers - 1; l >= 0; l--) {
			int in = sizes[l], out = sizes[l + 1];
			float[] a = cache[l];
			for (int s = 0; s < n; s++) {
				for (int i = 0; i < in; i++) {
					float ai = a[s * in + i];
					if (ai == 0f) continue;
					int w = wOff[l] + i * out;
					for (int o = 0; o < out; o++) grad[w + o] += ai * d[s * out + o];
				}
				for (int o = 0; o < out; o++) grad[bOff[l] + o] += d[s * out + o];
			}
			if (l > 0) {
				float[] prev = new float[n * in];
				for (int s = 0; s < n; s++) {
					for (int i = 0; i < in; i++) {
						if (a[s * in + i] <= 0f) continue;
						int w = wOff[l] + i * out;
						float sum = 0;
						for (int o = 0; o < out; o++) sum += flat[w + o] * d[s * out + o];
						prev[s * in + i] = sum;
					}
				}
				d = prev;
			}
		}
		double norm = 0;
		for (float g : grad) norm += g * g;
		norm = Math.sqrt(norm);
		float scale = norm > maxGradNorm ? (float) (maxGradNorm / norm) : 1f;
		t++;
		double b1 = 0.9, b2 = 0.999, c1 = 1 - Math.pow(b1, t), c2 = 1 - Math.pow(b2, t);
		for (int i = 0; i < flat.length; i++) {
			float g = grad[i] * scale;
			m[i] = (float) (b1 * m[i] + (1 - b1) * g);
			v[i] = (float) (b2 * v[i] + (1 - b2) * g * g);
			flat[i] -= (float) (lr * (m[i] / c1) / (Math.sqrt(v[i] / c2) + 1e-8));
		}
	}

	private static float[] flatten(float[][] x) {
		int cols = x[0].length;
		float[] out = new float[x.length * cols];
		for (int i = 0; i < x.length; i++) System.arraycopy(x[i], 0, out, i * cols, cols);
		return out;
	}

	private static float[][] unflatten(float[] x, int rows, int cols) {
		float[][] out = new float[rows][cols];
		for (int i = 0; i < rows; i++) System.arraycopy(x, i * cols, out[i], 0, cols);
		return out;
	}

	static float huber(float diff) {
		return Math.max(-1f, Math.min(1f, diff));
	}
}
