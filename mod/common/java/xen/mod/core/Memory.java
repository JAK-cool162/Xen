package xen.mod.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Experience + trauma + joy memories of n-step stretches. Port of xen/brain/memory.py. */
public final class Memory {
	/** One remembered moment: both the next step and the stretch of n steps after it. */
	public static final class Entry {
		byte[] obs, next, next1;
		int action;
		float reward, done, steps, reward1, harm, done1;
	}

	static final class Ring {
		final Entry[] items;
		int size, next;

		Ring(int capacity) {
			items = new Entry[capacity];
		}

		void add(Entry e) {
			items[next] = e;
			next = (next + 1) % items.length;
			size = Math.min(size + 1, items.length);
		}
	}

	final Ring experience, trauma, joy;
	final int nStep;
	final float gamma, traumaFraction, joyFraction, joyThreshold = 0.5f;
	private final Map<Object, ArrayDeque<Object[]>> recent = new HashMap<>();
	private final Random rng = new Random(3);

	public Memory(int capacity, int traumaCapacity, int nStep, float gamma, float traumaFraction, float joyFraction) {
		experience = new Ring(capacity);
		trauma = new Ring(traumaCapacity);
		joy = new Ring(traumaCapacity);
		this.nStep = nStep;
		this.gamma = gamma;
		this.traumaFraction = traumaFraction;
		this.joyFraction = joyFraction;
	}

	static byte[] pack(float[] o) {
		byte[] b = new byte[o.length];
		for (int i = 0; i < o.length; i++) b[i] = (byte) Math.round(Math.max(-1, Math.min(1, o[i])) * 127);
		return b;
	}

	static float[] unpack(byte[] b) {
		float[] o = new float[b.length];
		for (int i = 0; i < b.length; i++) o[i] = b[i] / 127f;
		return o;
	}

	public int size() {
		return experience.size;
	}

	public int traumaSize() {
		return trauma.size;
	}

	public int joySize() {
		return joy.size;
	}

	/** Vivid memories go with the brain when it's saved, so it never forgets what hurt it (or delighted it). */
	static final int KEEP = 1000;

	/** The most recent n entries of a ring, oldest first. */
	static List<Entry> recent(Ring r, int n) {
		List<Entry> out = new ArrayList<>();
		n = Math.min(n, r.size);
		for (int k = n; k > 0; k--) out.add(r.items[Math.floorMod(r.next - k, r.items.length)]);
		return out;
	}

	Ring ring(String name) {
		return switch (name) {
			case "trauma" -> trauma;
			case "joy" -> joy;
			default -> throw new IllegalArgumentException("unknown memory " + name);
		};
	}

	/** Write entries (same layout as xen/brain/memory.py: int8 observations, then int32 actions, then float32s). */
	static void write(List<Entry> entries, int obsDim, java.io.OutputStream out) throws java.io.IOException {
		int n = entries.size();
		for (int part = 0; part < 3; part++) {
			byte[] b = new byte[n * obsDim];
			for (int i = 0; i < n; i++) {
				Entry e = entries.get(i);
				System.arraycopy(part == 0 ? e.obs : part == 1 ? e.next : e.next1, 0, b, i * obsDim, obsDim);
			}
			out.write(b);
		}
		java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(n * 4 * 7).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (Entry e : entries) bb.putInt(e.action);
		for (Entry e : entries) bb.putFloat(e.reward);
		for (Entry e : entries) bb.putFloat(e.done);
		for (Entry e : entries) bb.putFloat(e.steps);
		for (Entry e : entries) bb.putFloat(e.reward1);
		for (Entry e : entries) bb.putFloat(e.harm);
		for (Entry e : entries) bb.putFloat(e.done1);
		out.write(bb.array());
	}

	static void read(Ring r, int n, int obsDim, java.io.DataInputStream in) throws java.io.IOException {
		Entry[] es = new Entry[n];
		for (int i = 0; i < n; i++) es[i] = new Entry();
		for (int part = 0; part < 3; part++) {
			byte[] b = new byte[n * obsDim];
			in.readFully(b);
			for (int i = 0; i < n; i++) {
				byte[] o = java.util.Arrays.copyOfRange(b, i * obsDim, (i + 1) * obsDim);
				if (part == 0) es[i].obs = o;
				else if (part == 1) es[i].next = o;
				else es[i].next1 = o;
			}
		}
		byte[] raw = new byte[n * 4 * 7];
		in.readFully(raw);
		java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (Entry e : es) e.action = bb.getInt();
		for (Entry e : es) e.reward = bb.getFloat();
		for (Entry e : es) e.done = bb.getFloat();
		for (Entry e : es) e.steps = bb.getFloat();
		for (Entry e : es) e.reward1 = bb.getFloat();
		for (Entry e : es) e.harm = bb.getFloat();
		for (Entry e : es) e.done1 = bb.getFloat();
		for (Entry e : es) r.add(e);
	}

	public void remember(float[] obs, int action, float reward, float harm, float[] next, boolean done, Object stream, boolean end) {
		ArrayDeque<Object[]> q = recent.computeIfAbsent(stream, k -> new ArrayDeque<>());
		q.addLast(new Object[] {obs, action, reward, harm, next, done});
		if (q.size() == nStep) commit(q, next, done);
		if (done || end) {
			while (!q.isEmpty()) commit(q, next, done);
			recent.remove(stream);
		}
	}

	private void commit(ArrayDeque<Object[]> q, float[] next, boolean done) {
		Object[] first = q.peekFirst();
		float reward = 0, harmAhead = 0;
		int i = 0;
		for (Object[] t : q) {
			reward += (float) Math.pow(gamma, i++) * (float) t[2];
			harmAhead += (float) t[3];
		}
		Entry e = new Entry();
		e.obs = pack((float[]) first[0]);
		e.action = (int) first[1];
		e.reward1 = (float) first[2];
		e.harm = (float) first[3];
		e.next1 = pack((float[]) first[4]);
		e.done1 = (boolean) first[5] ? 1 : 0;
		e.reward = reward;
		e.next = pack(next);
		e.done = done ? 1 : 0;
		e.steps = q.size();
		experience.add(e);
		if (harmAhead > 0) trauma.add(e);
		if (reward >= joyThreshold) joy.add(e);
		q.pollFirst();
	}

	public List<Entry> sample(int n) {
		int nT = Math.min(Math.round(n * traumaFraction), trauma.size);
		int nJ = Math.min(Math.round(n * joyFraction), joy.size);
		List<Entry> out = new ArrayList<>(n);
		for (int i = 0; i < n - nT - nJ; i++) out.add(experience.items[rng.nextInt(experience.size)]);
		for (int i = 0; i < nT; i++) out.add(trauma.items[rng.nextInt(trauma.size)]);
		for (int i = 0; i < nJ; i++) out.add(joy.items[rng.nextInt(joy.size)]);
		return out;
	}
}
