package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Before a decision of some size (which lava pool, which way to its obsidian), a Xen plays each choice out in its head
 * first: its own legs' planner run over the real world's blocks (a copy of the world in its mind: nothing moved, nothing
 * dug), every move of the way walked through in thought. What it meets on the way (lava beside it, a long fall, a swim,
 * a gap to jump, monsters about, the night coming down) are the cons; a short way, a clear one, one that worked before
 * are the pros. The best of them it does. Not for every step: only when there's a real choice, and not twice a minute.
 */
final class Imagination {
	/** A choice: what it is, where it leads, and the name of its record in the book (null: none). */
	record Option(String name, BlockPos to, String way) {}

	/** One choice played out: how long, the good and the bad, its score, the way (for the live view), and where the trouble is. */
	record Played(Option option, double seconds, List<String> pros, List<String> cons, double score, List<BlockPos> way, List<BlockPos> trouble, boolean reaches) {}

	private final Companion c;
	private long lastAt = -100_000, saidAt = -100_000;
	/** The last decision it thought through, for the live view (null: none yet). */
	private JsonObject last;

	Imagination(Companion c) {
		this.c = c;
	}

	/**
	 * A decision: each choice played out, the best one picked (its index). Too soon after the last (a minute), only one
	 * choice, or no body: the first one, as it would have anyway.
	 */
	int choose(String decision, List<Option> options) {
		if (c.player == null || options.size() < 2) return 0;
		ServerLevel level = (ServerLevel) c.player.level();
		long now = level.getGameTime();
		if (now - lastAt < 20 * 60) return 0;
		lastAt = now;
		List<Played> played = new ArrayList<>();
		for (int i = 0; i < Math.min(3, options.size()); i++) played.add(play(level, options.get(i), i));
		int best = 0;
		for (int i = 1; i < played.size(); i++) if (played.get(i).score() > played.get(best).score()) best = i;
		Played pick = played.get(best);
		StringBuilder sb = new StringBuilder("imagines " + decision + ": ");
		for (int i = 0; i < played.size(); i++) {
			Played p = played.get(i);
			sb.append(i == best ? "[" : "").append(p.option().name()).append(String.format(Locale.ROOT, " ~%.0f s", p.seconds()));
			if (!p.cons().isEmpty()) sb.append(", con: ").append(String.join(", ", p.cons()));
			if (!p.pros().isEmpty()) sb.append(", pro: ").append(String.join(", ", p.pros()));
			sb.append(i == best ? "]" : "").append(i < played.size() - 1 ? " | " : "");
		}
		c.journal("thinks", sb.toString());
		if (now - saidAt > 20 * 60 * 3) {                                       // (a word now and then, not at every choice)
			saidAt = now;
			Played other = played.get(best == 0 ? 1 : 0);
			String why = !other.cons().isEmpty() ? "the other way " + other.cons().get(0) : !pick.pros().isEmpty() ? pick.pros().get(0) : "it's quicker";
			c.chatter("Thought it through: " + pick.option().name() + " (" + why + ").", false);
		}
		last = json(decision, played, best, now);
		return best;
	}

	/** One choice walked through in its head, move by move. */
	private Played play(ServerLevel level, Option o, int order) {
		List<String> pros = new ArrayList<>(), cons = new ArrayList<>();
		List<BlockPos> way = new ArrayList<>(), trouble = new ArrayList<>();
		List<Walker.Move> moves = c.walker.imagine(o.to());
		boolean partial = c.walker.imaginedPartial;
		BlockPos at = c.player.blockPosition();
		way.add(at);
		double seconds = 0;
		int lava = 0, falls = 0, swims = 0, gaps = 0, digs = 0;
		if (moves != null) {
			for (Walker.Move m : moves) {
				seconds += switch (m.kind()) {
					case WALK -> 0.25;
					case DIAGONAL -> 0.35;
					case ASCEND, CLIMB_UP, CLIMB_DOWN -> 0.6;
					case PILLAR, BRIDGE -> 0.8;
					case SWIM -> 0.6;
					case PARKOUR -> 0.5;
					case FALL, DIG_DOWN -> 0.4;
				};
				if (m.dig() != null) for (BlockPos d : m.dig()) {
					float speed = c.hands.digSpeed(level.getBlockState(d), level, d);
					seconds += speed > 0 ? Math.min(15, 1 / speed / 20.0) : 15;
					digs++;
				}
				BlockPos to = m.to();
				if (nearLava(level, to) || nearLava(level, to.above())) {
					lava++;
					trouble.add(to);
				}
				if (m.kind() == Walker.Kind.FALL && m.from().getY() - to.getY() >= 4) {
					falls++;
					trouble.add(to);
				}
				if (m.kind() == Walker.Kind.SWIM) swims++;
				if (m.kind() == Walker.Kind.PARKOUR) gaps++;
				way.add(to);
				at = to;
			}
		}
		double left = Math.sqrt(at.distSqr(o.to()));
		if (moves == null || partial) seconds += left * 0.35;                    // (the rest of the way: guessed, not thought through)
		int monsters = 0;
		if (moves != null) {
			for (int i = 0; i < way.size(); i += 6) {
				BlockPos w = way.get(i);
				AABB box = new AABB(w.getX() - 6, w.getY() - 6, w.getZ() - 6, w.getX() + 7, w.getY() + 7, w.getZ() + 7);
				monsters += level.getEntitiesOfClass(Monster.class, box, LivingEntity::isAlive).size();
			}
		}
		long day = Compat.timeOfDay(level) % 24000;
		boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
		boolean nightFalls = overworld && day < 12500 && day + seconds * 20 > 12500;
		double[] book = o.way() != null ? c.mod.btbLearner.stats(o.way()) : null;
		// the bad
		if (moves == null) cons.add("no way there it can think of");
		else if (partial) cons.add(String.format(Locale.ROOT, "only %d of %d blocks of it thought through", (int) Math.sqrt(c.player.blockPosition().distSqr(at)), (int) Math.sqrt(c.player.blockPosition().distSqr(o.to()))));
		if (lava > 0) cons.add("lava beside it " + lava + (lava == 1 ? " time" : " times"));
		if (falls > 0) cons.add(falls + (falls == 1 ? " long fall" : " long falls"));
		if (swims > 6) cons.add("a swim");
		if (gaps > 0) cons.add(gaps + (gaps == 1 ? " gap to jump" : " gaps to jump"));
		if (monsters > 0) cons.add(monsters + (monsters == 1 ? " monster" : " monsters") + " about");
		if (nightFalls) cons.add("night falls on the way");
		if (book != null && book[0] >= 4 && book[1] / book[0] < 0.25) cons.add(String.format(Locale.ROOT, "%d of %d tries worked", (int) book[1], (int) book[0]));
		// the good
		if (moves != null && !partial) pros.add("a way all the way there");
		if (moves != null && lava == 0 && falls == 0 && monsters == 0) pros.add("nothing dangerous on it");
		if (seconds < 45) pros.add("close");
		if (digs == 0 && moves != null) pros.add("no digging");
		if (book != null && book[0] >= 2 && book[1] / book[0] >= 0.5) pros.add(String.format(Locale.ROOT, "%d of %d tries worked", (int) book[1], (int) book[0]));
		float hp = c.player.getHealth();
		double total = Math.max(1, Math.sqrt(c.player.blockPosition().distSqr(o.to()))), thought = Math.sqrt(c.player.blockPosition().distSqr(at));
		double unsure = moves == null ? 1 : partial ? Math.max(0, 1 - thought / total) : 0;   // (how much of the way is a guess: Ivy's "1 of 108 blocks thought through" counted no worse than nearly all of it)
		double risk = hp <= 8 ? 4 : hp < 12 ? 2 : 1, hurry = hp <= 8 ? 0.3 : 1;  // its life at stake: the long safe way over the quick risky one (a minute more counts for little, a lava pool for four times as much)
		double score = -hurry * seconds / 60.0 - risk * (2.5 * lava + 1.5 * falls + 0.6 * monsters + (nightFalls ? 1.5 : 0) + 0.3 * gaps) - (moves == null ? 50 : 3 * unsure) - 0.15 * order;
		if (risk > 1 && (lava > 0 || falls > 0 || monsters > 0)) cons.add("risky for its health now");
		if (book != null && book[0] >= 2) score += 2 * (book[1] / book[0]) - 5 * (book[2] / book[0]);
		return new Played(o, seconds, pros, cons, score, way, trouble, moves != null && !partial);
	}

	private static boolean nearLava(ServerLevel level, BlockPos b) {
		if (level.getFluidState(b).is(FluidTags.LAVA)) return true;
		for (Direction d : Direction.values()) if (level.getFluidState(b.relative(d)).is(FluidTags.LAVA)) return true;
		return false;
	}

	/** For the live view: the decision, each choice with its way (a dozen dozen points at most), its pros and cons, and which it picked. */
	private JsonObject json(String decision, List<Played> played, int best, long now) {
		JsonObject o = new JsonObject();
		o.addProperty("decision", decision);
		o.addProperty("pick", best);
		o.addProperty("at", System.currentTimeMillis());
		BlockPos me = c.player.blockPosition();
		o.addProperty("x", me.getX());
		o.addProperty("y", me.getY());
		o.addProperty("z", me.getZ());
		JsonArray arr = new JsonArray();
		for (Played p : played) {
			JsonObject q = new JsonObject();
			q.addProperty("name", p.option().name());
			q.addProperty("seconds", Math.round(p.seconds()));
			q.addProperty("score", Math.round(p.score() * 10) / 10.0);
			q.addProperty("reaches", p.reaches());
			JsonArray pr = new JsonArray(), co = new JsonArray(), w = new JsonArray(), t = new JsonArray();
			p.pros().forEach(pr::add);
			p.cons().forEach(co::add);
			int step = Math.max(1, p.way().size() / 140);
			for (int i = 0; i < p.way().size(); i += step) w.add(point(p.way().get(i)));
			w.add(point(p.way().get(p.way().size() - 1)));
			for (BlockPos b : p.trouble()) if (t.size() < 40) t.add(point(b));
			q.add("pros", pr);
			q.add("cons", co);
			q.add("way", w);
			q.add("trouble", t);
			q.add("to", point(p.option().to()));
			arr.add(q);
		}
		o.add("options", arr);
		return o;
	}

	private static JsonArray point(BlockPos b) {
		JsonArray a = new JsonArray();
		a.add(b.getX());
		a.add(b.getY());
		a.add(b.getZ());
		return a;
	}

	/** The last decision it thought through (null: none yet), for the live view. */
	JsonObject last() {
		return last;
	}
}
