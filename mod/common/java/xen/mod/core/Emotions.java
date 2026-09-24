package xen.mod.core;

/** Pain, fear, caution, curiosity, satisfaction, boredom. Port of xen/brain/emotions.py. */
public final class Emotions {
	public float baseCaution = 4f, sensitizationGain = 3f, habituation = 0.997f, maxSensitization = 2f, fearGain = 3f;
	public float sensitization;
	public float pain, fear, curiosity, satisfaction, boredom;
	private int same;
	private float[] lastView;

	public float caution() {
		return baseCaution * (1f + sensitization);
	}

	public float anticipate(float expectedHarm) {
		float felt = 1f - (float) Math.exp(-fearGain * Math.max(expectedHarm, 0f));
		fear = Math.max(felt, fear * 0.85f);
		return fear;
	}

	/** Boredom builds while nothing really changes around it (spinning on the spot doesn't count). */
	public float notice(float[] obs) {
		float[] view;
		if (obs.length > 750 + 13) {
			view = new float[5 + 10];
			for (int c = 0; c < 150; c++) for (int ch = 0; ch < 5; ch++) view[ch] += obs[c * 5 + ch];
			int k = 5;
			for (int i = obs.length - 13; i < obs.length; i++) {
				int j = i - (obs.length - 13);
				if (j >= 5 && j <= 7) continue;                   // which way it's looking up or down
				view[k++] = obs[i];
			}
			for (int i = 0; i < view.length; i++) view[i] = Math.round(view[i] * 1000f) / 1000f;
		} else {
			view = obs.clone();
		}
		same = lastView != null && java.util.Arrays.equals(view, lastView) ? same + 1 : 0;
		lastView = view;
		boredom = 1f - (float) Math.exp(-same / 3.0);
		return boredom;
	}

	public void experience(float harm, float reward, float surprise) {
		pain = Math.max(Math.min(harm, 1f), pain * 0.5f);
		sensitization = Math.min(maxSensitization, sensitization * habituation + sensitizationGain * harm);
		curiosity = 0.8f * curiosity + 0.2f * (float) Math.tanh(surprise);
		satisfaction = Math.max((float) Math.tanh(Math.max(reward, 0f)), satisfaction * 0.97f);
	}

	public void reset() {
		pain = fear = curiosity = satisfaction = boredom = 0;
		same = 0;
		lastView = null;
	}

	public String mood() {
		if (pain > 0.15f) return "hurt";
		if (fear > 0.7f) return "terrified";
		if (fear > 0.4f) return "afraid";
		if (fear > 0.2f) return "uneasy";
		if (satisfaction > 0.5f) return "pleased";
		if (boredom > 0.5f) return "bored";
		if (curiosity > 0.5f) return "curious";
		return "calm";
	}
}
