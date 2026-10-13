package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import xen.mod.core.Mind;

import java.util.HashMap;
import java.util.Map;

/**
 * What it thinks you two should do, out loud, from what it actually knows: a place it found and hasn't been to ("I saw
 * a village about 140 blocks northeast earlier. Want to check it out?"), a way it hasn't seen (G1X: "haven't seen
 * what's past there yet"), what it worked out when something went other than it expected, or a lesson it learned the hard way (its outcome memory: "last time I went mining at night I
 * died. not doing that again"). Nothing made up: each idea is something in its memory or in front of its eyes.
 * <p>Only to a player close by (not a spectator: nobody sees those), now and then (a chatty one more often), each idea
 * once in a long while.
 */
final class Ideas {
	private final Companion c;
	private long next;
	private final Map<String, Long> said = new HashMap<>();
	int ideas;

	Ideas(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	void tick() {
		var p = c.player;
		if (p == null || c.inArena || c.minion || !c.mod.config.chat || c.fightingNow() || p.isSleeping()) return;
		long now = now();
		if (now < next) return;
		ServerPlayer friend = null;
		for (ServerPlayer o : c.server.getPlayerList().getPlayers()) {
			if (o instanceof XenPlayer || o.isSpectator() || o.level() != p.level() || o.distanceTo(p) > 24) continue;
			friend = o;
			break;
		}
		if (friend == null) return;
		next = now + 20 * 20;                                               // (nothing to say: it looks again in 20 s)
		String line = place(friend);
		if (line == null) line = lesson();
		if (line == null) line = reflection();
		if (line == null && !p.level().isDarkOutside()) line = unseen(friend);
		if (line == null) return;
		float chatty = Math.max(0.1f, Math.min(1f, c.personality.chattiness));
		next = now + (long) (20 * 60 * (6 - 3 * chatty));                  // (said one: the next in three minutes or so for a chatty one, six for a quiet one)
		ideas++;
		c.journal("says", "an idea: " + line);
		c.chatter(line, false);
	}

	private boolean fresh(String key, long every) {
		Long t = said.get(key);
		if (t != null && now() - t < every) return false;
		said.put(key, now());
		return true;
	}

	/** Which way that is from the one it's talking to, in words (north, south-east...). */
	static String direction(BlockPos from, BlockPos to) {
		double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
		double a = Math.toDegrees(Math.atan2(dx, -dz));                    // (0: north, 90: east)
		String[] names = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
		return names[(int) Math.floorMod(Math.round(a / 45), 8)];
	}

	/** A place it found and hasn't been to (not too close: then they'd see it themselves; not too far: a trip, not a voyage). */
	private String place(ServerPlayer friend) {
		BlockPos here = friend.blockPosition();
		String bestKind = null;
		BlockPos best = null;
		for (var e : c.structures.found.entrySet()) {
			String kind = e.getKey();
			if (kind.equals("house") || kind.equals("old ruins")) continue;
			BlockPos at = e.getValue();
			double d = Math.sqrt(at.distSqr(here));
			if (d < 40 || d > 600 || c.visits.wasThere(at)) continue;
			if (best == null || d < Math.sqrt(best.distSqr(here))) {
				best = at;
				bestKind = kind;
			}
		}
		if (best == null || !fresh("place " + best.asLong(), 20 * 60 * 30)) return null;
		long dist = Math.round(Math.sqrt(best.distSqr(here)) / 10.0) * 10;
		String where = "about " + dist + " blocks " + direction(here, best);
		String a = "aeiou".indexOf(bestKind.charAt(0)) >= 0 ? "an " : "a ";
		return c.pick3("I saw " + a + bestKind + " " + where + " earlier. Want to check it out?",
				"There's " + a + bestKind + " " + where + ". I think we should go look.",
				"Remember that " + bestKind + "? It's " + where + ". We could go there.");
	}

	/** The hardest lesson it has: a choice that killed it, in a spot like this one (its outcome memory). */
	private String lesson() {
		Experience.Case worst = null;
		for (Experience.Case k : c.experience.cases) if (k.died() && (worst == null || k.when() > worst.when())) worst = k;
		if (worst == null || now() - worst.when() > 20 * 60 * 20 * 3) return null;          // (a few days and it lets it go)
		if (!fresh("lesson " + worst.option() + " " + (worst.s()[0] > 0.5f), 20 * 60 * 40)) return null;
		String what = Mind.SAYS[worst.option()].replaceFirst("^go ", "went ").replaceFirst("^get ", "got ").replaceFirst("^make ", "made ")
				.replaceFirst("^take ", "took ").replaceFirst("^find ", "looked for ").replaceFirst("^explore$", "went exploring")
				.replaceFirst("^fight$", "fought").replaceFirst("^craft ", "crafted ").replaceFirst("^build ", "built ");
		String when = worst.s()[0] > 0.5f ? " at night" : worst.s()[2] > 0.5f ? " underground" : "";
		return c.pick3("Last time I " + what + when + " I died. Not doing that again.",
				"Note to self: last time I " + what + when + ", I died.",
				"I'm not doing that again. Last time I " + what + when + " I died.");
	}

	/** What it worked out from a guess that was way off ("mining went worse than I thought: night came. I'll mind that next time"). */
	private String reflection() {
		var e = c.experience;
		if (e.lesson.isEmpty() || now() - e.lessonAt > 20 * 60 * 20) return null;   // (today's: older ones it has taken in)
		if (!fresh("reflect " + e.lesson, 20 * 60 * 60)) return null;
		return e.lesson;
	}

	/** G1X: much it hasn't seen one way (daytime, a curious one): it says so. */
	private String unseen(ServerPlayer friend) {
		if (c.personality.curiosity < 0.45f) return null;
		G1X.Around a = c.g1x.around();
		int i = a.mostUnseen();
		if (a.unseen()[i] < 0.75f || a.free()[i] < 3) return null;
		String[] names = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
		if (!fresh("unseen " + names[i] + " " + (c.player.blockPosition().asLong() >> 6), 20 * 60 * 15)) return null;
		return c.pick3("I haven't seen what's " + names[i] + " of here yet. Want to go look?",
				"What's over there, to the " + names[i] + "? I haven't been that way.",
				"Let's go " + names[i] + " sometime. I haven't seen any of it.");
	}
}
