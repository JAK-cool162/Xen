package xen.mod.core;

/** TD critic with a dueling head: striatum (reward) or amygdala (harm). Port of xen/brain/critic.py. */
public final class Critic {
	public Mlp net, target;
	public final float gamma, tau;
	final int actions;

	public Critic(int obs, int actions, int[] hidden, float lr, float gamma, float tau, long seed) {
		int[] sizes = new int[hidden.length + 2];
		sizes[0] = obs;
		System.arraycopy(hidden, 0, sizes, 1, hidden.length);
		sizes[sizes.length - 1] = actions + 1;
		this.net = new Mlp(sizes, lr, seed);
		this.target = net.copy();
		this.gamma = gamma;
		this.tau = tau;
		this.actions = actions;
	}

	static float[] q(float[] out) {
		int n = out.length - 1;
		float mean = 0;
		for (int i = 1; i <= n; i++) mean += out[i];
		mean /= n;
		float[] q = new float[n];
		for (int i = 0; i < n; i++) q[i] = out[0] + out[i + 1] - mean;
		return q;
	}

	public float[] values(float[] obs) {
		return q(net.predict(obs));
	}

	public float[][] values(float[][] obs) {
		float[][] out = net.forward(obs, false);
		float[][] qs = new float[obs.length][];
		for (int i = 0; i < obs.length; i++) qs[i] = q(out[i]);
		return qs;
	}

	/** One TD step: Q(s,a) <- signal + gamma^steps * Q_target(s', a'). */
	public void learn(float[][] obs, int[] a, float[] signal, float[][] next, float[] done, int[] nextA, float[] steps) {
		int n = a.length;
		float[][] tOut = target.forward(next, false);
		float[][] out = net.forward(obs, true);
		float[][] grad = new float[n][actions + 1];
		for (int s = 0; s < n; s++) {
			float qNext = q(tOut[s])[nextA[s]];
			double discount = Math.pow(gamma, steps == null ? 1 : steps[s]);
			float y = (float) (signal[s] + discount * (1 - done[s]) * qNext);
			float g = Mlp.huber(q(out[s])[a[s]] - y) / n;
			grad[s][0] = g;
			for (int j = 0; j < actions; j++) grad[s][j + 1] = -g / actions;
			grad[s][a[s] + 1] += g;
		}
		net.backward(grad);
		target.softUpdate(net, tau);
	}
}
