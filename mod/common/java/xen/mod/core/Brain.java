package xen.mod.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Xen's brain (port of xen/brain/agent.py): perceive, feel, think, decide, learn.
 * Reads and writes the same brain file as Python ({@code Xen.export}), so a brain trained in SimCraft
 * plays here, keeps learning here, and can go back to Python.
 */
public final class Brain {
	public static final byte[] MAGIC = "XEN1".getBytes(StandardCharsets.US_ASCII);

	public int obsDim, nActions = Action.COUNT;
	public int[] hidden = {256, 256};
	public float lr = 3e-4f, gamma = 0.95f, fearGamma = 0.9f, tau = 0.01f, baseCaution = 4f, curiosity = 0.02f, curiosityScale = 20f;
	public int batch = 32, trainEvery = 4, nStep = 3, thinkDepth = 3, thinkBreadth = 4, thinkAfter = 5000;
	public float exploreEnd = 0.05f, thinkFear = 0.3f, thinkMargin = 0.01f, thinkTrust = 0.3f, trustHalfLife = 3000f;
	public Critic striatum, amygdala;
	public WorldModel worldModel;
	public Memory memory;
	public long steps, updates, lives;
	public float sensitization;
	public JsonObject config = new JsonObject();
	private final Random rng = new Random();

	/** A decision, with the feeling and words behind it. */
	public static final class Thought {
		public int action;
		public String mode, text;
		public float fear;
	}

	public Brain(int obsDim) {
		this.obsDim = obsDim;
		build(0);
	}

	private void build(long seed) {
		striatum = new Critic(obsDim, nActions, hidden, lr, gamma, tau, seed);
		amygdala = new Critic(obsDim, nActions, hidden, lr, fearGamma, tau, seed + 1);
		worldModel = new WorldModel(obsDim, nActions, hidden, lr, seed + 2);
		memory = new Memory(30_000, 5_000, nStep, gamma, 0.2f, 0.1f);
	}

	public Emotions newBody() {
		Emotions e = new Emotions();
		e.baseCaution = baseCaution;
		e.sensitization = sensitization;
		return e;
	}

	public float[] fears(float[] obs) {
		float[] f = amygdala.values(obs);
		for (int i = 0; i < f.length; i++) f[i] = Math.max(0f, f[i]);
		return f;
	}

	public float[] utility(float[] obs, float caution) {
		float[] q = striatum.values(obs), f = fears(obs);
		for (int i = 0; i < q.length; i++) q[i] -= caution * f[i];
		return q;
	}

	static int argmax(float[] v) {
		int b = 0;
		for (int i = 1; i < v.length; i++) if (v[i] > v[b]) b = i;
		return b;
	}

	/**
	 * Choose an action. Never blocks on training (which runs on its own thread): reading weights while
	 * they are being nudged only means a very slightly older opinion.
	 */
	public Thought decide(float[] obs, Emotions emo, boolean explore) {
		float[] fearQ = fears(obs);
		float caution = emo.caution();
		float[] util = striatum.values(obs);
		for (int i = 0; i < util.length; i++) util[i] -= caution * fearQ[i];
		int best = argmax(util);
		float mean = 0;
		for (float f : fearQ) mean += f;
		mean /= fearQ.length;
		float fear = emo.anticipate(0.5f * (fearQ[best] + mean));
		float boredom = emo.notice(obs);
		int feared = argmax(fearQ);
		Thought t = new Thought();
		t.fear = fear;
		List<String> options = new ArrayList<>();
		boolean restless = rng.nextFloat() < 0.9f * boredom;
		if (restless || (explore && rng.nextFloat() < exploreEnd * (1f - 0.8f * fear))) {
			if (rng.nextFloat() < 0.5f) {
				double[] p = new double[nActions];
				double max = Double.NEGATIVE_INFINITY, sum = 0;
				for (int i = 0; i < nActions; i++) max = Math.max(max, -caution * fearQ[i] * 3);
				for (int i = 0; i < nActions; i++) sum += p[i] = Math.exp(-caution * fearQ[i] * 3 - max);
				double r = rng.nextDouble() * sum;
				t.action = nActions - 1;
				for (int i = 0; i < nActions; i++) if ((r -= p[i]) <= 0) { t.action = i; break; }
			} else {
				t.action = rng.nextInt(nActions);       // exposure: sometimes even what it fears
			}
			t.mode = "explore";
		} else {
			float[] sorted = util.clone();
			java.util.Arrays.sort(sorted);
			boolean unsure = sorted[sorted.length - 1] - sorted[sorted.length - 2] < thinkMargin;
			if ((fear >= thinkFear || unsure) && worldModel.updates >= thinkAfter) {
				t.action = deliberate(obs, util, caution, options);
				t.mode = "thought";
			} else {
				t.action = best;
				t.mode = "instinct";
			}
		}
		t.text = monologue(t, emo, feared, fearQ, options);
		return t;
	}

	/** Imagine the best options a few steps ahead (port of cortex.py). */
	private int deliberate(float[] obs, float[] util, float caution, List<String> options) {
		Integer[] order = new Integer[nActions];
		for (int i = 0; i < nActions; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Float.compare(util[b], util[a]));
		double trust = thinkTrust * (1 - Math.pow(0.5, worldModel.updates / trustHalfLife));
		int best = order[0];
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int c = 0; c < thinkBreadth; c++) {
			int first = order[c], a = first;
			float[] state = obs;
			double total = 0, discount = 1, alive = 1;
			float[] future = util;
			for (int d = 0; d < thinkDepth; d++) {
				float[] im = worldModel.imagine(state, a);
				state = java.util.Arrays.copyOf(im, obsDim);
				total += discount * alive * (im[obsDim] - caution * im[obsDim + 1]);
				alive *= 1 - im[obsDim + 2];
				discount *= gamma;
				future = utility(state, caution);
				a = argmax(future);
			}
			total += discount * alive * future[argmax(future)];
			double score = (1 - trust) * util[first] + trust * total;
			options.add(String.format("%s %+.2f", Action.values()[first].verb, score));
			if (score > bestScore) {
				bestScore = score;
				best = first;
			}
		}
		return best;
	}

	private String monologue(Thought t, Emotions emo, int feared, float[] fearQ, List<String> options) {
		String verb = Action.values()[t.action].verb;
		StringBuilder sb = new StringBuilder();
		if (emo.pain > 0.15f) sb.append("Ouch! That hurt. ");
		if (emo.fear >= 0.2f && fearQ[feared] > 0.05f) {
			sb.append("I'm ").append(emo.mood()).append("; I don't want to ").append(Action.values()[feared].verb).append(". ");
		}
		switch (t.mode) {
			case "thought" -> sb.append("Thinking it through (").append(String.join(", ", options)).append(") -> I'll ").append(verb).append('.');
			case "explore" -> sb.append(emo.boredom > 0.5f ? "Nothing's happening... " + verb + "!" : "Let me try: " + verb + ".");
			default -> sb.append(Character.toUpperCase(verb.charAt(0))).append(verb.substring(1)).append('.');
		}
		return sb.toString();
	}

	/** Take in the outcome of an action; trains now and then. Returns true if it trained. */
	public boolean learn(float[] obs, int action, float reward, float harm, float[] next, boolean terminal, boolean end,
						 Emotions emo, Object stream, boolean train) {
		float felt = (float) (Math.signum(reward) * Math.log1p(Math.abs(reward)));
		float surprise = worldModel.updates > 0 ? worldModel.surprise(obs, action, next) * curiosityScale : 0f;
		synchronized (memory) {
			memory.remember(obs, action, felt, harm, next, terminal, stream, end || terminal);
			steps++;
		}
		emo.experience(harm, reward, surprise);
		if (harm > 0) sensitization = emo.sensitization;
		if (!train || steps % trainEvery != 0 || memory.size() < batch) return false;
		trainStep();
		return true;
	}

	/** One learning step; call from a single training thread. */
	public synchronized void trainStep() {
		List<Memory.Entry> b;
		synchronized (memory) {
			if (memory.size() < batch) return;
			b = memory.sample(batch);
		}
		int n = b.size();
		float[][] obs = new float[n][], next = new float[n][], next1 = new float[n][];
		int[] a = new int[n];
		float[] reward = new float[n], reward1 = new float[n], harm = new float[n], done = new float[n], done1 = new float[n], stepsN = new float[n];
		for (int i = 0; i < n; i++) {
			Memory.Entry e = b.get(i);
			obs[i] = Memory.unpack(e.obs);
			next[i] = Memory.unpack(e.next);
			next1[i] = Memory.unpack(e.next1);
			a[i] = e.action;
			reward[i] = e.reward;
			reward1[i] = e.reward1;
			harm[i] = e.harm;
			done[i] = e.done;
			done1[i] = e.done1;
			stepsN[i] = e.steps;
		}
		float[] surprise = worldModel.learn(obs, a, reward1, harm, next1, done1);
		int[] fearNext = new int[n], nextA = new int[n];
		for (int i = 0; i < n; i++) {
			fearNext[i] = argmax(utility(next1[i], baseCaution));
			nextA[i] = argmax(utility(next[i], baseCaution));
			reward[i] += curiosity * (float) Math.tanh(curiosityScale * surprise[i]);
		}
		amygdala.learn(obs, a, harm, next1, done1, fearNext, null);
		striatum.learn(obs, a, reward, next, done, nextA, stepsN);
		updates++;
	}

	// --------------------------------------------------------------------------------- brain file

	public static Brain read(InputStream in) throws IOException {
		DataInputStream data = new DataInputStream(in);
		byte[] magic = new byte[4];
		data.readFully(magic);
		if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("not a Xen brain file");
		byte[] len = new byte[4];
		data.readFully(len);
		byte[] json = new byte[ByteBuffer.wrap(len).order(ByteOrder.LITTLE_ENDIAN).getInt()];
		data.readFully(json);
		JsonObject h = new Gson().fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);
		Brain brain = new Brain(h.get("obs_dim").getAsInt(), h);
		for (var entry : h.getAsJsonArray("nets")) {
			JsonArray pair = entry.getAsJsonArray();
			Mlp net = brain.net(pair.get(0).getAsString());
			byte[] raw = new byte[net.flat.length * 4];
			data.readFully(raw);
			ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(net.flat);
		}
		return brain;
	}

	private Brain(int obsDim, JsonObject h) {
		this.obsDim = obsDim;
		this.nActions = h.get("n_actions").getAsInt();
		JsonObject c = h.getAsJsonObject("config");
		this.config = c;
		JsonArray hid = c.getAsJsonArray("hidden");
		hidden = new int[hid.size()];
		for (int i = 0; i < hidden.length; i++) hidden[i] = hid.get(i).getAsInt();
		lr = c.get("lr").getAsFloat();
		gamma = c.get("gamma").getAsFloat();
		fearGamma = c.get("fear_gamma").getAsFloat();
		tau = c.get("tau").getAsFloat();
		baseCaution = c.get("base_caution").getAsFloat();
		curiosity = c.get("curiosity").getAsFloat();
		curiosityScale = c.get("curiosity_scale").getAsFloat();
		nStep = c.get("n_step").getAsInt();
		thinkDepth = c.get("think_depth").getAsInt();
		thinkBreadth = c.get("think_breadth").getAsInt();
		thinkFear = c.get("think_fear").getAsFloat();
		thinkMargin = c.get("think_margin").getAsFloat();
		thinkTrust = c.get("think_trust").getAsFloat();
		thinkAfter = c.get("think_after").getAsInt();
		exploreEnd = c.get("explore_end").getAsFloat();
		build(0);
		steps = h.get("steps").getAsLong();
		updates = h.get("updates").getAsLong();
		lives = h.get("lives").getAsLong();
		worldModel.updates = h.get("wm_updates").getAsLong();
		JsonObject emo = h.getAsJsonObject("emotions");
		if (emo != null && emo.has("sensitization")) sensitization = emo.get("sensitization").getAsFloat();
	}

	private Mlp net(String name) {
		return switch (name) {
			case "striatum" -> striatum.net;
			case "amygdala" -> amygdala.net;
			case "world_model" -> worldModel.net;
			case "striatum_target" -> striatum.target;
			case "amygdala_target" -> amygdala.target;
			default -> throw new IllegalArgumentException("unknown network " + name);
		};
	}

	public synchronized void write(OutputStream out) throws IOException {
		String[] names = {"striatum", "amygdala", "world_model", "striatum_target", "amygdala_target"};
		JsonObject h = new JsonObject();
		h.addProperty("obs_dim", obsDim);
		h.addProperty("n_actions", nActions);
		h.add("config", config);
		h.addProperty("steps", steps);
		h.addProperty("updates", updates);
		h.addProperty("lives", lives);
		h.addProperty("wm_updates", worldModel.updates);
		JsonObject emo = new JsonObject();
		emo.addProperty("sensitization", sensitization);
		h.add("emotions", emo);
		JsonArray nets = new JsonArray();
		for (String name : names) {
			JsonArray pair = new JsonArray();
			pair.add(name);
			JsonArray sizes = new JsonArray();
			for (int s : net(name).sizes) sizes.add(s);
			pair.add(sizes);
			nets.add(pair);
		}
		h.add("nets", nets);
		byte[] json = new Gson().toJson(h).getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		buf.write(MAGIC);
		buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.length).array());
		buf.write(json);
		for (String name : names) {
			float[] flat = net(name).flat;
			ByteBuffer bb = ByteBuffer.allocate(flat.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			bb.asFloatBuffer().put(flat);
			buf.write(bb.array());
		}
		out.write(buf.toByteArray());
	}
}
