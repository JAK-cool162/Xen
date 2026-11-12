package xen.mod.core;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xen Glance learning while the Xens play: no pictures to collect, no training somewhere else. The game knows what was
 * really in each picture (a monster close or near, a drop ahead, a cave mouth), so every whole view a Xen takes in is a
 * lesson: what Glance made of it, against what was there.
 * <ul>
 *   <li><b>Graded first, then learned from</b>: each new picture is a test for both the Glance that came with the mod
 *   and the one learning (neither has seen it), then a lesson for the one learning. Only once the learning one has done
 *   better on the last few hundred new pictures do the Xens see with it; till then, with the one that came with the mod.
 *   Learning can't make their eyes worse.</li>
 *   <li><b>What it learns from</b>: this picture and seven it kept (the last 512 with something in them, the last 512
 *   with nothing), drawn as often as each comes up in play (drawn half and half, it learned monsters are about far
 *   more than they are, and got worse); small steps, and a pull back toward what the mod's knew.</li>
 *   <li><b>Light</b>: a millisecond or two a lesson, on a thread of its own, one lesson per whole view a Xen takes in
 *   (one every second or so); the pictures it keeps, a few hundred kilobytes.</li>
 *   <li><b>Kept</b>: config/xen/glance_learned.json (the same shape as the one that comes with the mod), learning on
 *   from there next time; delete it to start again from the mod's.</li>
 * </ul>
 */
public final class GlanceLearner {
	private static final int PICTURE = Glance.C * Glance.H * Glance.W, KEEP = 512, BATCH = 8, FAIR = 300;
	private static final float LR = 1e-4f, PULL = 0.05f;

	private final Glance shipped;
	private final Glance learning, grad, m, v;
	private volatile Glance inUse;
	private volatile boolean usingLearned;
	private final Path file;
	private final byte[][] pos = new byte[KEEP][], neg = new byte[KEEP][];
	private final float[][] posY = new float[KEEP][], negY = new float[KEEP][];
	private int posN, negN, posAt, negAt, steps, seen, posSeen, learnedBefore;
	private volatile double lossShipped, lossLearned;
	private final Random random = new Random();
	private final AtomicInteger queued = new AtomicInteger();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Xen Glance learning");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	/** From the Glance that comes with the mod, and on from what it learned before (the file) if there is one. */
	public GlanceLearner(Glance shipped, Path file) {
		this.shipped = shipped;
		this.file = file;
		Glance before = null;
		try {
			if (Files.exists(file)) try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				before = Glance.read(r);
			}
		} catch (Exception e) {
			before = null;                                                       // (unreadable, or another shape: from the mod's again)
		}
		learning = before != null ? before : shipped.copy();
		learnedBefore = before != null ? before.trainedOn : 0;
		grad = shipped.copy();
		m = shipped.copy();
		v = shipped.copy();
		m.zero();
		v.zero();
		inUse = shipped;
	}

	/** The Glance its eyes use now: the one learning, once it does better than the mod's; till then, the mod's. */
	public Glance current() {
		return inUse;
	}

	/** A picture ([C][H*W], 0 to 1) and what was really there ({@link Glance#HEADS}): graded, then learned from (on its own thread). */
	public void see(float[][] x, boolean[] truth) {
		if (queued.get() > 32) return;                                          // (it's behind: this one goes by)
		byte[] px = new byte[PICTURE];
		int k = 0;
		for (float[] ch : x) for (float val : ch) px[k++] = (byte) Math.round(Math.max(0, Math.min(1, val)) * 255);
		float[] y = new float[Glance.HEADS.length];
		for (int i = 0; i < y.length; i++) y[i] = truth[i] ? 1 : 0;
		queued.incrementAndGet();
		worker.execute(() -> {
			try {
				learnFrom(px, y);
			} catch (RuntimeException e) {
				// (one bad lesson: the next)
			} finally {
				queued.decrementAndGet();
			}
		});
	}

	private static float[][] unpack(byte[] px) {
		float[][] x = new float[Glance.C][Glance.H * Glance.W];
		int k = 0;
		for (float[] ch : x) for (int q = 0; q < ch.length; q++) ch[q] = (px[k++] & 255) / 255f;
		return x;
	}

	private void learnFrom(byte[] px, float[] y) {
		float[][] x = unpack(px);
		float[] os = shipped.run(x), ol = learning.run(x);
		float ls = Glance.loss(os, y), ll = Glance.loss(ol, y);                  // (the test: a picture neither has learned from)
		mark(px, y, os, ol);
		seen++;
		double a = 1.0 / Math.min(seen, FAIR);
		lossShipped += a * (ls - lossShipped);
		lossLearned += a * (ll - lossLearned);
		boolean something = false;
		for (float t : y) something |= t > 0;
		if (something) posSeen++;
		if (something) {
			pos[posAt] = px;
			posY[posAt] = y;
			posAt = (posAt + 1) % KEEP;
			posN = Math.min(KEEP, posN + 1);
		} else {
			neg[negAt] = px;
			negY[negAt] = y;
			negAt = (negAt + 1) % KEEP;
			negN = Math.min(KEEP, negN + 1);
		}
		grad.zero();
		int n = 1;
		learning.backprop(x, y, grad);
		float posRate = (float) posSeen / seen;
		for (int b = 1; b < BATCH; b++) {                                       // (and some it kept: as often with something in them as play has)
			boolean fromPos = posN > 0 && (negN == 0 || random.nextFloat() < posRate);
			if (fromPos && posN == 0 || !fromPos && negN == 0) continue;
			int i = random.nextInt(fromPos ? posN : negN);
			learning.backprop(unpack(fromPos ? pos[i] : neg[i]), fromPos ? posY[i] : negY[i], grad);
			n++;
		}
		grad.pull(learning, shipped, PULL * n);                                  // (what the mod's knew: it strays from that only as far as the pictures pay for)
		steps++;
		learning.adam(grad, m, v, steps, LR, 1f / n);
		if (steps % 25 == 0) {
			boolean better = seen >= FAIR && lossLearned < lossShipped * 0.97;   // (clearly better on pictures it hadn't learned from)
			usingLearned = better;
			inUse = better ? learning.copy() : shipped;
		}
		if (steps % 100 == 0 && seen >= FAIR && lossLearned <= lossShipped) save();   // (kept only if it's no worse than the mod's: never a worse start next time)
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, learning.toJson(learnedBefore + steps).toString(), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			// (not kept this time: the next)
		}
	}

	// ------------------------------------------------------------------------------------------------ its exam
	/** The last few hundred new pictures, each a question per head: what was there, what each Glance said (yes over a half). */
	private final boolean[][] examTruth = new boolean[FAIR][], examShipped = new boolean[FAIR][], examLearned = new boolean[FAIR][];
	private int examAt, examN;
	/** A few of the latest pictures (some with something in them, some with nothing) and what each said: for a page to show. */
	private final java.util.ArrayDeque<Example> examples = new java.util.ArrayDeque<>(), emptyOnes = new java.util.ArrayDeque<>();

	/** A picture (packed, [C][H*W] as bytes), what was there, and what each Glance made of it (0 to 1). */
	public record Example(byte[] picture, float[] truth, float[] shipped, float[] learned, long at) {}

	private synchronized void mark(byte[] px, float[] y, float[] os, float[] ol) {
		boolean[] t = new boolean[y.length], a = new boolean[y.length], b = new boolean[y.length];
		boolean something = false;
		for (int i = 0; i < y.length; i++) {
			t[i] = y[i] > 0.5f;
			a[i] = os[i] > 0.5f;
			b[i] = ol[i] > 0.5f;
			something |= t[i] || a[i] || b[i];
		}
		examTruth[examAt] = t;
		examShipped[examAt] = a;
		examLearned[examAt] = b;
		examAt = (examAt + 1) % FAIR;
		examN = Math.min(FAIR, examN + 1);
		var keep = something ? examples : emptyOnes;                          // (eight with something in them, said or there; four with nothing)
		keep.addLast(new Example(px, y.clone(), os.clone(), ol.clone(), System.currentTimeMillis()));
		while (keep.size() > (something ? 8 : 4)) keep.removeFirst();
	}

	/**
	 * Its exam on the last few hundred pictures it hadn't learned from, each head a question ("a monster close?"): of the
	 * times it was there, how many it caught; of the times it wasn't, how many it said it was (false alarms); for the
	 * Glance that's learning and the one that came with the mod. [head][0 there, 1 caught learning, 2 caught mod's,
	 * 3 not there, 4 false alarms learning, 5 false alarms mod's].
	 */
	public synchronized int[][] exam() {
		int[][] out = new int[Glance.HEADS.length][6];
		for (int k = 0; k < examN; k++)
			for (int i = 0; i < out.length; i++) {
				boolean t = examTruth[k][i];
				if (t) {
					out[i][0]++;
					if (examLearned[k][i]) out[i][1]++;
					if (examShipped[k][i]) out[i][2]++;
				} else {
					out[i][3]++;
					if (examLearned[k][i]) out[i][4]++;
					if (examShipped[k][i]) out[i][5]++;
				}
			}
		return out;
	}

	/** The latest pictures it kept for showing (with something in them first), oldest first. */
	public synchronized java.util.List<Example> examples() {
		var out = new java.util.ArrayList<Example>(examples);
		out.addAll(emptyOnes);
		return out;
	}

	/** How its learning's going, in a line (for the log and the live view). */
	public String status() {
		if (seen == 0) return "learning: no pictures yet";
		return String.format(Locale.ROOT, "learned from %d pictures (%d before); on the last %d new ones: %.3f wrong, the mod's own %.3f: %s", steps, learnedBefore,
				Math.min(seen, FAIR), lossLearned, lossShipped, usingLearned ? "sees with what it learned" : seen < FAIR ? "still proving itself" : "the mod's is still better");
	}

	/** True once it sees with what it learned. */
	public boolean usingLearned() {
		return usingLearned;
	}
}
