package xen.mod.core;

/** Imagination: predicts next observation, reward, harm and the end of a life. Port of world_model.py. */
public final class WorldModel {
	public final Mlp net;
	final int obs, actions;
	public long updates;

	public WorldModel(int obs, int actions, int[] hidden, float lr, long seed) {
		int[] sizes = new int[hidden.length + 2];
		sizes[0] = obs + actions;
		System.arraycopy(hidden, 0, sizes, 1, hidden.length);
		sizes[sizes.length - 1] = obs + 3;
		net = new Mlp(sizes, lr, seed);
		this.obs = obs;
		this.actions = actions;
	}

	float[] input(float[] o, int a) {
		float[] x = new float[obs + actions];
		System.arraycopy(o, 0, x, 0, obs);
		x[obs + a] = 1f;
		return x;
	}

	static float sigmoid(float x) {
		return (float) (1 / (1 + Math.exp(-Math.max(-30, Math.min(30, x)))));
	}

	/** {nextObs..., reward, harm, pDone} for one state and action. */
	public float[] imagine(float[] o, int a) {
		float[] out = net.predict(input(o, a));
		float[] r = new float[obs + 3];
		for (int i = 0; i < obs; i++) r[i] = Math.max(-1f, Math.min(1f, o[i] + out[i]));
		r[obs] = out[obs];
		r[obs + 1] = Math.max(0f, out[obs + 1]);
		r[obs + 2] = sigmoid(out[obs + 2]);
		return r;
	}

	public float surprise(float[] o, int a, float[] next) {
		float[] p = imagine(o, a);
		double s = 0;
		for (int i = 0; i < obs; i++) s += (p[i] - next[i]) * (p[i] - next[i]);
		return (float) (s / obs);
	}

	/** Train on a batch; returns each transition's surprise before learning. */
	public float[] learn(float[][] o, int[] a, float[] reward, float[] harm, float[][] next, float[] done) {
		int n = a.length;
		float[][] x = new float[n][];
		for (int s = 0; s < n; s++) x[s] = input(o[s], a[s]);
		float[][] out = net.forward(x, true);
		float[][] grad = new float[n][obs + 3];
		float[] surprise = new float[n];
		for (int s = 0; s < n; s++) {
			double se = 0;
			for (int i = 0; i < obs; i++) {
				float err = out[s][i] - (next[s][i] - o[s][i]);
				se += err * err;
				grad[s][i] = 2f * err / (n * obs) * 50f;
			}
			surprise[s] = (float) (se / obs);
			grad[s][obs] = Mlp.huber(out[s][obs] - reward[s]) / n;
			grad[s][obs + 1] = Mlp.huber(out[s][obs + 1] - harm[s]) / n;
			grad[s][obs + 2] = (sigmoid(out[s][obs + 2]) - done[s]) / n;
		}
		net.backward(grad);
		updates++;
		return surprise;
	}
}
