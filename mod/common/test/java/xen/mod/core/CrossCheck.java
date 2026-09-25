package xen.mod.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks the Java port against fixtures written by scripts/make_mod_fixtures.py (Python). */
public final class CrossCheck {
	static int failures;

	static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what);
		}
	}

	static float maxDiff(float[] a, JsonArray b) {
		float m = 0;
		for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b.get(i).getAsFloat()));
		return m;
	}

	public static void main(String[] args) throws IOException {
		Path dir = Path.of(args.length > 0 ? args[0] : "mod/common/test/fixtures");
		Gson gson = new Gson();

		// Ray directions.
		JsonObject rays = gson.fromJson(Files.readString(dir.resolve("rays.json")), JsonObject.class);
		double worst = 0;
		for (Map.Entry<String, JsonElement> e : rays.entrySet()) {
			String[] k = e.getKey().split(",");
			double[][] mine = Perception.rayDirections(Integer.parseInt(k[0]), Integer.parseInt(k[1]), Integer.parseInt(k[2]));
			JsonArray theirs = e.getValue().getAsJsonArray();
			for (int r = 0; r < mine.length; r++)
				for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(mine[r][c] - theirs.get(r).getAsJsonArray().get(c).getAsDouble()));
		}
		check(worst < 1e-9, "ray directions differ by " + worst);
		System.out.println("rays: max difference " + worst);

		// Perception encoding.
		JsonArray cases = gson.fromJson(Files.readString(dir.resolve("perception.json")), JsonArray.class);
		float worstObs = 0;
		for (JsonElement ce : cases) {
			JsonObject c = ce.getAsJsonObject();
			Perception.Sight s = new Perception.Sight();
			JsonArray near = c.getAsJsonArray("near");
			for (int i = 0; i < near.size(); i++) s.near[i] = near.get(i).getAsInt();
			s.yaw = c.get("yaw").getAsInt();
			JsonArray pos = c.getAsJsonArray("position");
			s.position = new int[] {pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()};
			s.t = c.get("t").getAsDouble();
			JsonArray b = c.getAsJsonArray("body");
			s.body.health = b.get(0).getAsFloat();
			s.body.hunger = b.get(1).getAsFloat();
			s.body.night = b.get(2).getAsBoolean();
			s.body.burning = b.get(3).getAsBoolean();
			s.body.hurt = b.get(4).getAsFloat();
			s.body.pitch = b.get(5).getAsInt();
			s.body.blocks = b.get(6).getAsInt();
			s.body.food = b.get(7).getAsInt();
			s.body.inWater = b.get(8).getAsBoolean();
			s.body.inLava = b.get(9).getAsBoolean();
			for (JsonElement m : c.getAsJsonArray("near_mobs")) {
				JsonArray a = m.getAsJsonArray();
				s.nearMobs.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
			}
			JsonArray rd = c.getAsJsonArray("ray_dist"), rc = c.getAsJsonArray("ray_cat");
			s.rayDist = new double[rd.size()];
			s.rayCat = new int[rc.size()];
			s.rayHit = new int[rc.size()][3];
			for (int i = 0; i < rd.size(); i++) {
				double d = rd.get(i).getAsDouble();
				s.rayDist[i] = d < 0 ? Double.POSITIVE_INFINITY : d;
				s.rayCat[i] = rc.get(i).getAsInt();
			}
			Perception.Beliefs beliefs = new Perception.Beliefs();
			for (JsonElement be : c.getAsJsonArray("beliefs")) {
				JsonArray a = be.getAsJsonArray();
				beliefs.see(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsDouble());
			}
			for (JsonElement me : c.getAsJsonArray("mobs")) {
				JsonArray a = me.getAsJsonArray();
				beliefs.mobs.add(new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()});
			}
			float[] obs = Perception.encode(s, beliefs);
			float d = maxDiff(obs, c.getAsJsonArray("obs"));
			worstObs = Math.max(worstObs, d);
		}
		check(worstObs < 1e-4, "observations differ by " + worstObs);
		System.out.println("perception: " + cases.size() + " cases, max difference " + worstObs);

		// Brain file and networks.
		Brain brain;
		try (FileInputStream in = new FileInputStream(dir.resolve("brain.bin").toFile())) {
			brain = Brain.read(in);
		}
		JsonObject bj = gson.fromJson(Files.readString(dir.resolve("brain.json")), JsonObject.class);
		JsonArray obs = bj.getAsJsonArray("obs");
		float worstNet = 0;
		for (int i = 0; i < obs.size(); i++) {
			JsonArray o = obs.get(i).getAsJsonArray();
			float[] x = new float[o.size()];
			for (int k = 0; k < x.length; k++) x[k] = o.get(k).getAsFloat();
			worstNet = Math.max(worstNet, maxDiff(brain.striatum.values(x), bj.getAsJsonArray("q").get(i).getAsJsonArray()));
			worstNet = Math.max(worstNet, maxDiff(brain.fears(x), bj.getAsJsonArray("fear").get(i).getAsJsonArray()));
			float[] wmIn = new float[x.length + Action.COUNT];
			System.arraycopy(x, 0, wmIn, 0, x.length);
			wmIn[x.length + i] = 1f;
			worstNet = Math.max(worstNet, maxDiff(brain.worldModel.net.predict(wmIn), bj.getAsJsonArray("wm").get(i).getAsJsonArray()));
		}
		check(worstNet < 1e-4, "networks differ by " + worstNet);
		System.out.println("brain: max difference " + worstNet);

		// Vivid memories travel with the brain: the same ones in Java, and unchanged after Java saves it again.
		java.io.ByteArrayOutputStream again = new java.io.ByteArrayOutputStream();
		brain.write(again);
		Brain reread = Brain.read(new java.io.ByteArrayInputStream(again.toByteArray()));
		JsonObject mems = bj.getAsJsonObject("memories");
		for (String name : new String[] {"trauma", "joy"}) {
			JsonArray want = mems.getAsJsonArray(name);
			for (Brain b : new Brain[] {brain, reread}) {
				Memory.Ring r = b.memory.ring(name);
				long bytes = 0;
				double harm = 0, reward = 0;
				for (Memory.Entry e : Memory.recent(r, 1000)) {
					for (byte v : e.obs) bytes += v;
					harm += e.harm;
					reward += e.reward;
				}
				boolean ok = r.size == want.get(0).getAsInt() && bytes == want.get(1).getAsLong()
						&& Math.abs(harm - want.get(2).getAsDouble()) < 1e-3 && Math.abs(reward - want.get(3).getAsDouble()) < 1e-3;
				check(ok, name + " memories differ (" + r.size + " entries)");
			}
			System.out.println(name + " memories: " + want.get(0).getAsInt() + " carried over, the same in Java and after saving again");
		}

		// The chat's rules: the same understanding, safe chat, honesty filter, plain answers and prompts as in Python.
		JsonObject chat = gson.fromJson(Files.readString(dir.resolve("chat.json")), JsonObject.class);
		int same = 0, total = 0;
		for (JsonElement e : chat.getAsJsonArray("answers")) {
			JsonObject v = e.getAsJsonObject();
			String safe = xen.mod.talk.Chat.safe(v.get("text").getAsString());
			String notes = v.get("notes").getAsString();
			boolean ok = safe.equals(v.get("safe").getAsString())
					&& xen.mod.talk.Chat.honest(safe, notes).equals(v.get("honest").getAsString())
					&& xen.mod.talk.Chat.plainly(notes, v.get("message").getAsString()).equals(v.get("plainly").getAsString());
			check(ok, "chat rules differ for: " + v.get("text").getAsString());
			same += ok ? 1 : 0;
			total++;
		}
		for (JsonElement e : chat.getAsJsonArray("requests")) {
			JsonArray v = e.getAsJsonArray();
			var r = xen.mod.talk.Chat.understand(v.get(0).getAsString(), "xen");
			boolean ok = r.intent().equals(v.get(1).getAsString()) && r.thing().equals(v.get(2).getAsString()) && r.amount() == v.get(3).getAsInt();
			check(ok, "understood differently: " + v.get(0).getAsString() + " -> " + r);
			same += ok ? 1 : 0;
			total++;
		}
		for (String prompt : new String[] {"persona", "ears"}) {
			String java = prompt.equals("persona") ? xen.mod.talk.Chat.persona() : xen.mod.talk.Chat.ears();
			boolean ok = java.equals(chat.get(prompt).getAsString());
			check(ok, "the " + prompt + " prompt differs from Python's");
			same += ok ? 1 : 0;
			total++;
		}
		System.out.println("chat rules: " + same + "/" + total + " cases match");

		// Finding a way on foot through what it knows.
		check(pathTest(), "path finding failed");

		// Learning works in Java too: fear conditioning in a tiny lava room.
		check(learnsToFearLava(), "Java brain did not learn to fear lava");

		System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
		if (failures > 0) System.exit(1);
	}

	/** obs[0] = lava ahead. Walking FORWARD into lava burns; elsewhere FORWARD finds treasure. */
	static Perception.Sight ground() {
		Perception.Sight s = new Perception.Sight();
		for (int x = -6; x <= 6; x++) for (int y = -6; y <= 6; y++) for (int z = -6; z <= 6; z++)
			s.near[((x + 6) * 13 + y + 6) * 13 + z + 6] = y < 0 ? Blocks.STONE : Blocks.AIR;
		return s;
	}

	static void put(Perception.Sight s, int x, int y, int z, int c) {
		s.near[((x + 6) * 13 + y + 6) * 13 + z + 6] = c;
	}

	static boolean pathTest() {
		Perception.Sight flat = ground();
		int[] a = Paths.firstStep(flat, 5, 0, 0);                      // east, on flat ground
		Perception.Sight wall = ground();                               // a wall east of it, with a gap to the south
		for (int z = -6; z <= 6; z++) if (z != 3) for (int y = 0; y <= 1; y++) put(wall, 1, y, z, Blocks.STONE);
		int[] b = Paths.firstStep(wall, 5, 0, 0);
		Perception.Sight step = ground();                               // higher ground east: jump up
		for (int x = 1; x <= 6; x++) for (int z = -6; z <= 6; z++) put(step, x, 0, z, Blocks.DIRT);
		int[] c = Paths.firstStep(step, 4, 1, 0);
		Perception.Sight lava = ground();                               // lava right in front: go around it
		put(lava, 1, -1, 0, Blocks.LAVA);
		put(lava, 2, -1, 0, Blocks.LAVA);
		int[] d = Paths.firstStep(lava, 5, 0, 0);
		boolean ok = a != null && a[0] == 1 && a[1] == 0 && b != null && b[0] == 2 && c != null && c[0] == 1 && c[1] == 1
				&& d != null && d[0] != 1;
		System.out.println("paths: flat " + java.util.Arrays.toString(a) + ", around a wall " + java.util.Arrays.toString(b)
				+ ", step up " + java.util.Arrays.toString(c) + ", around lava " + java.util.Arrays.toString(d) + (ok ? " ok" : " WRONG"));
		return ok;
	}

	static boolean learnsToFearLava() {
		Brain brain = new Brain(8);
		brain.hidden = new int[] {32, 32};
		brain.lr = 1e-3f;
		brain.batch = 32;
		brain.trainEvery = 2;
		brain.thinkAfter = Integer.MAX_VALUE;
		brain.exploreEnd = 0.3f;
		brain.config.addProperty("note", "test");
		java.util.Random rng = new java.util.Random(1);
		Emotions emo = brain.newBody();
		float[] o = room(rng);
		for (int step = 0; step < 6000; step++) {
			Brain.Thought t = brain.decide(o, emo, true);
			boolean lava = o[0] > 0.5f;
			float reward = 0, harm = 0;
			boolean dead = false;
			if (t.action == Action.FORWARD.ordinal()) {
				if (lava) { harm = 1; dead = true; } else reward = 1;
			}
			float[] next = room(rng);
			brain.learn(o, t.action, reward, harm, next, dead, dead, emo, 0, true);
			o = next;
		}
		float[] lava = new float[8], safe = new float[8];
		lava[0] = 1; lava[3] = 1; safe[3] = 1;
		float fearLava = brain.fears(lava)[Action.FORWARD.ordinal()], fearSafe = brain.fears(safe)[Action.FORWARD.ordinal()];
		float fearBack = brain.fears(lava)[Action.BACK.ordinal()];
		System.out.printf("java fear conditioning: forward into lava %.2f, forward elsewhere %.2f, stepping back %.2f%n", fearLava, fearSafe, fearBack);
		return fearLava > 0.5f && fearSafe < 0.25f && fearBack < 0.25f;
	}

	static float[] room(java.util.Random rng) {
		float[] o = new float[8];
		o[0] = rng.nextFloat() < 0.5f ? 1 : 0;
		o[1 + rng.nextInt(7)] = 1;
		return o;
	}
}
