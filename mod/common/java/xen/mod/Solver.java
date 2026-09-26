package xen.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The solver (an experiment): a second little mind for being stuck. When its legs can't find a way, or keep failing,
 * or it gets no closer for a while, it looks at where it is (in a hole? under water? underground? is the goal above,
 * below? does it have blocks, a pickaxe?) and picks a way out: tower up, dig a staircase up, dig straight through, a
 * bolder way (a bigger drop, a longer jump), go round, back off and look again, swim out, or ask for help.
 * <p>It learns which ways work where: each try is scored by whether it got closer within ten seconds, and the next
 * time it's in a place like that it mostly picks what worked (and now and then something else, to find out). It also
 * learns from players it trusts: when it sees one get out of a hole by towering up or digging, or out of the water,
 * that counts like a try of its own that worked. All Xens share what they learn (config/xen/solver.json), and every
 * try is written down (config/xen/solver-tries.jsonl): the data to make it better.
 */
final class Solver {
	enum Way { TOWER, STAIRS_UP, DIG_THROUGH, BOLDER, AROUND, BACK_OFF, SWIM_OUT, ASK }

	// ------------------------------------------------------------------------------ what all Xens know
	/** For each kind of place, each way out: how often it worked, and how often it was tried. */
	static final class Mind {
		final Map<String, float[]> table = new LinkedHashMap<>();   // "place|way" -> {worked, tried}
		private Path file, tries;
		private final Random random = new Random();

		void load(Path dir) {
			file = dir.resolve("solver.json");
			tries = dir.resolve("solver-tries.jsonl");
			try {
				if (Files.exists(file)) {
					JsonObject o = new Gson().fromJson(Files.readString(file), JsonObject.class);
					for (var e : o.entrySet()) {
						var a = e.getValue().getAsJsonArray();
						table.put(e.getKey(), new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat()});
					}
				}
			} catch (IOException | RuntimeException e) {
				XenMod.LOG.warn("The solver's notes couldn't be read: {}", e.toString());
			}
		}

		void save() {
			if (file == null) return;
			JsonObject o = new JsonObject();
			for (var e : table.entrySet()) {
				var a = new com.google.gson.JsonArray();
				a.add(e.getValue()[0]);
				a.add(e.getValue()[1]);
				o.add(e.getKey(), a);
			}
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(o));
			} catch (IOException e) {
				XenMod.LOG.warn("The solver's notes couldn't be saved: {}", e.toString());
			}
		}

		/** A way out for this place: the one most likely to work, as far as it knows (a guess from the odds, so it keeps trying new ones). */
		Way pick(String place, java.util.function.Predicate<Way> possible) {
			Way best = null;
			double bestDraw = -1;
			for (Way w : Way.values()) {
				if (!possible.test(w)) continue;
				float[] t = table.getOrDefault(place + "|" + w, prior(w));
				double draw = beta(1 + t[0], 1 + t[1] - t[0]);
				if (draw > bestDraw) {
					bestDraw = draw;
					best = w;
				}
			}
			return best;
		}

		/** Before it knows anything: what players usually do first (asking for help last). */
		private static float[] prior(Way w) {
			return switch (w) {
				case TOWER, STAIRS_UP, SWIM_OUT -> new float[] {1, 2};
				case BOLDER, AROUND, DIG_THROUGH -> new float[] {0.8f, 2};
				case BACK_OFF -> new float[] {0.6f, 2};
				case ASK -> new float[] {0.2f, 2};
			};
		}

		void learn(String place, Way w, boolean worked, double weight, String who, double secs) {
			float[] t = table.computeIfAbsent(place + "|" + w, k -> prior(w).clone());
			t[0] += worked ? weight : 0;
			t[1] += weight;
			if (t[1] > 60) {                                                // old tries count for less: places change, it gets better
				t[0] *= 0.5f;
				t[1] *= 0.5f;
			}
			if (tries != null) {
				try {
					Files.createDirectories(tries.getParent());
					Files.writeString(tries, String.format(Locale.ROOT, "{\"place\":\"%s\",\"way\":\"%s\",\"worked\":%b,\"who\":\"%s\",\"secs\":%.1f}%n",
							place, w.name().toLowerCase(Locale.ROOT), worked, who, secs), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				} catch (IOException ignored) {
				}
			}
		}

		/** A draw from Beta(a, b) (the odds it works, given what it saw). */
		private double beta(double a, double b) {
			double x = gamma(a), y = gamma(b);
			return x / (x + y);
		}

		private double gamma(double k) {
			if (k < 1) return gamma(k + 1) * Math.pow(random.nextDouble(), 1 / k);
			double d = k - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
			while (true) {
				double x = random.nextGaussian(), v = Math.pow(1 + c * x, 3);
				if (v <= 0) continue;
				double u = random.nextDouble();
				if (Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) return d * v;
			}
		}

		String report() {
			StringBuilder sb = new StringBuilder();
			for (var e : table.entrySet()) {
				float[] t = e.getValue();
				sb.append(String.format(Locale.ROOT, "%s: %.0f of %.0f worked%n", e.getKey().replace('|', ' '), t[0], t[1]));
			}
			return sb.length() == 0 ? "The solver hasn't tried anything yet.\n" : sb.toString();
		}
	}

	// ------------------------------------------------------------------------------ one Xen, stuck
	private final Companion c;
	private final Random random = new Random();
	private Way way;
	private String place;
	private Vec3 goal, detour;
	private double startGap;
	private long started;
	private int steps;
	/** Not again straight away (its legs get another go first). */
	private long restUntil;
	String doing = "";

	Solver(Companion c) {
		this.c = c;
	}

	boolean active() {
		return way != null;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private static double gap(Vec3 a, Vec3 to) {
		return Math.hypot(a.x - to.x, a.z - to.z) + 0.7 * Math.abs(a.y - to.y);
	}

	/** What kind of place it's in, in a few words (the same words for places that are alike). */
	String place(Vec3 to) {
		var p = c.player;
		ServerLevel level = (ServerLevel) p.level();
		BlockPos feet = p.blockPosition();
		int walls = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (!level.getBlockState(feet.relative(d)).getCollisionShape(level, feet.relative(d)).isEmpty()
				|| !level.getBlockState(feet.relative(d).above()).getCollisionShape(level, feet.relative(d).above()).isEmpty()) walls++;
		String where = p.isInWater() ? "water" : walls >= 4 ? "hole" : walls >= 2 ? "corner" : "open";
		String sky = level.canSeeSky(feet.above()) ? "sky" : "under";
		double dy = to.y - p.getY();
		String goalAt = dy > 2 ? "above" : dy < -2 ? "below" : "level";
		boolean blocks = c.walker.hasBlocks();
		boolean pick = c.crafter.pickTier() > 0;
		return where + "," + sky + ",goal " + goalAt + (blocks ? ",blocks" : "") + (pick ? ",pick" : "");
	}

	/** Its legs gave up on the way to `to`: pick a way out and start it. False if it isn't the time (or it's switched off). */
	boolean start(Vec3 to, String why) {
		if (!c.mod.config.solver || active() || now() < restUntil) return false;
		goal = to;
		place = place(to);
		boolean water = c.player.isInWater(), blocks = c.walker.hasBlocks(), pick = c.crafter.pickTier() > 0;
		boolean above = to.y - c.player.getY() > 1.5;
		way = c.mod.solverMind.pick(place, w -> switch (w) {
			case TOWER -> blocks && !water;
			case STAIRS_UP -> above && !water;
			case DIG_THROUGH -> !water && (pick || !place.startsWith("water"));
			case SWIM_OUT -> water;
			case ASK -> c.leader != null;
			default -> true;
		});
		if (way == null) return false;
		started = now();
		startGap = gap(c.player.position(), to);
		steps = 0;
		detour = null;
		c.journal("solver", "stuck (" + why + ") in a place like \"" + place + "\": trying " + name(way));
		if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} is stuck ({}), {}: tries {}", c.name, why, place, way);
		return true;
	}

	static String name(Way w) {
		return switch (w) {
			case TOWER -> "towering up";
			case STAIRS_UP -> "a staircase up";
			case DIG_THROUGH -> "digging straight through";
			case BOLDER -> "a bolder way";
			case AROUND -> "going round";
			case BACK_OFF -> "backing off to look again";
			case SWIM_OUT -> "swimming out";
			case ASK -> "asking for help";
		};
	}

	/** One decision of the way out it's trying; null when that try is over (then its legs take over again). */
	Action next(Vec3 to) {
		if (way == null) return null;
		goal = to;
		long t = now() - started;
		double gap = gap(c.player.position(), to);
		boolean better = gap < startGap - 3 || gap < 2;
		if (better || t > 240) {                                              // it worked (closer), or ten seconds and it didn't
			finish(better);
			return null;
		}
		doing = name(way);
		var p = c.player;
		Vec3 here = p.position();
		double dx = to.x - here.x, dz = to.z - here.z, len = Math.max(1e-6, Math.hypot(dx, dz));
		Direction toward = Direction.getApproximateNearest(dx, 0, dz);
		switch (way) {
			case TOWER -> {                                                   // up two or three blocks, to look around (and get out)
				if (steps >= 3 || !c.walker.hasBlocks()) return c.walker.go(to);
				if (p.onGround() && !c.hands.busy() && c.hands.startPillar()) {
					steps++;
					c.pillaring = true;
					return Action.JUMP;
				}
				return Action.IDLE;
			}
			case STAIRS_UP -> {
				if (steps >= 4) return c.walker.go(to);
				Action a = c.walker.stairsUp(toward);
				if (a == null) {
					finish(false);
					return null;
				}
				if (c.walker.justStepped()) steps++;
				return a;
			}
			case DIG_THROUGH -> {
				return c.digToward(to);
			}
			case BOLDER -> {
				c.walker.dare(300);
				return c.walker.go(to);
			}
			case AROUND, BACK_OFF -> {
				if (detour == null) {
					double side = random.nextBoolean() ? 1 : -1;
					detour = way == Way.AROUND ? here.add(-dz / len * 6 * side + dx / len * 2, 0, dx / len * 6 * side + dz / len * 2)
							: here.add(-dx / len * 5, 0, -dz / len * 5);
				}
				if (Math.hypot(detour.x - here.x, detour.z - here.z) < 1.5) {
					detour = null;
					way = Way.BOLDER;                                            // there: now the way from here
					return c.walker.go(to);
				}
				Action a = c.walker.go(detour);
				return a != null ? a : c.digToward(detour);
			}
			case SWIM_OUT -> {
				Vec3 shore = c.walker.nearestDryLand(12);
				if (shore == null) return c.walker.go(to);
				return c.walker.go(shore);
			}
			case ASK -> {
				if (steps++ == 0) {
					ServerPlayer o = c.leader == null ? null : c.server.getPlayerList().getPlayer(c.leader);
					c.say(o != null ? "I'm stuck, " + o.getName().getString() + ". Can you help me out?" : "I'm stuck. Can anyone help?");
				}
				return Action.IDLE;
			}
		}
		return null;
	}

	private void finish(boolean worked) {
		double secs = (now() - started) / 20.0;
		c.mod.solverMind.learn(place, way, worked, 1, c.name, secs);
		c.journal("solver", name(way) + (worked ? " worked" : " didn't work") + String.format(Locale.ROOT, " (%.0f s)", secs));
		if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {}: {} {}", c.name, way, worked ? "worked" : "didn't work");
		way = null;
		detour = null;
		restUntil = now() + (worked ? 40 : 20);
	}

	void cancel() {
		way = null;
		detour = null;
	}

	// ------------------------------------------------------------------------------ watching players
	/** A player it trusts, stuck somewhere like a hole or water: how do they get out? */
	private static final class Seen {
		String place;
		Vec3 from;
		long since;
		int placedUnder, dug;
		boolean wasInWater;
	}

	private final Map<UUID, Seen> seen = new HashMap<>();

	/** Every second or so: the players it can see (and trusts), and how they get out of holes and water. */
	void watch() {
		if (!c.mod.config.solver || !c.mod.config.copy || c.player == null || c.player.tickCount % 10 != 0) return;
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer || p.level() != c.player.level() || p.isSpectator() || p.isCreative() || p.distanceTo(c.player) > 16
					|| c.trust(p.getUUID()) < 0.25f || !WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, p) && p.distanceTo(c.player) > 6) {
				seen.remove(p.getUUID());
				continue;
			}
			ServerLevel level = (ServerLevel) p.level();
			BlockPos feet = p.blockPosition();
			Seen s = seen.get(p.getUUID());
			int walls = 0;
			for (Direction d : Direction.Plane.HORIZONTAL) if (!level.getBlockState(feet.relative(d).above()).getCollisionShape(level, feet.relative(d).above()).isEmpty()) walls++;
			boolean stuckish = walls >= 3 || p.isInWater();
			if (s == null) {
				if (stuckish) {
					s = new Seen();
					s.place = (p.isInWater() ? "water" : walls >= 4 ? "hole" : "corner") + "," + (level.canSeeSky(feet.above()) ? "sky" : "under") + ",goal above";
					s.from = p.position();
					s.since = now();
					s.wasInWater = p.isInWater();
					seen.put(p.getUUID(), s);
				}
				continue;
			}
			if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty() && p.getY() > s.from.y + 0.9
					&& Math.hypot(p.getX() - s.from.x, p.getZ() - s.from.z) < 1.2) s.placedUnder++;   // straight up on new blocks: towering
			boolean out = p.getY() > s.from.y + 2.5 || s.wasInWater && !p.isInWater() && p.onGround();
			if (out) {
				Way w = s.wasInWater ? Way.SWIM_OUT : s.placedUnder >= 2 ? Way.TOWER : Way.STAIRS_UP;
				c.mod.solverMind.learn(s.place, w, true, 0.5 + c.trust(p.getUUID()), p.getName().getString(), (now() - s.since) / 20.0);
				c.journal("solver", "saw " + p.getName().getString() + " get out of a " + s.place.split(",")[0] + " by " + name(w));
				seen.remove(p.getUUID());
			} else if (now() - s.since > 1200) {
				seen.remove(p.getUUID());
			}
		}
	}
}
