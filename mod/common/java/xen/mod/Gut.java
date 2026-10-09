package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

/**
 * Xen 2.0's third brain, the gut: a feeling, not a rulebook. Every moment it asks what it has learned:
 * <ul>
 *   <li>its amygdala (the fear critic of its brain): what each move is expected to cost in harm from here, learned in
 *   SimLife and from every hurt in this world since;</li>
 *   <li>Xen Ex1: how likely it is to be hurt in the next two seconds, learned from a person's recorded play and from
 *   every Xen's own hurts.</li>
 * </ul>
 * When what it's doing (standing, walking on) is expected to hurt clearly more than some other move, it takes that
 * move, a reaction time after it feels it, before anything it was asked. Nothing about lava, fire, drops or monsters
 * is written here: it steps back from lava because stepping into lava hurt (in SimLife, or here). A Xen that has
 * never been burnt may go too close the first time; the hurt teaches it (and every Xen with it).
 * <p>A Xen can argue with its gut. When the gut says no, its mind makes its case (it was asked to, it can take a hit,
 * it's braver than that, the feeling is faint) against how sure the gut is, how dangerous it all looks (Ex1) and how
 * much it trusts its gut, and now and then it goes on anyway. Then it watches what happens: hurt, and it trusts its gut
 * more ("should've listened"); fine, and a little less. Instinct (a clear danger: no air, lava, fire) isn't argued
 * with: it comes first ({@link Instinct}), and the gut keeps quiet meanwhile.
 */
final class Gut {
	static final Action[] MOVES = {Action.IDLE, Action.FORWARD, Action.BACK, Action.LEFT, Action.RIGHT, Action.JUMP};
	/** How much less a move must be expected to hurt before the gut takes it (times its caution), the least worry that counts, and dread. */
	static final float RELIEF = 0.12f, WORRY = 0.15f, DREAD = 0.8f;
	/** -Dxen.gutDebug=true: what it feels, every second, in the journal. */
	static final boolean DEBUG = Boolean.getBoolean("xen.gutDebug");

	private final Companion c;
	private long saidAt;
	/** What it's doing to keep safe right now (for its thoughts), or empty. */
	String doing = "";
	int overrides;
	private Action last;
	/** How much it trusts its gut, 0.1 to 0.95 (from its caution at first; then from what happened when it went against it). -1: not yet. */
	float faith = -1;
	/** Times it argued with its gut, went against it, and found it right or wrong. */
	int arguments, overruled, gutRight, gutWrong;
	/** Going against its gut: what it said no to, till when it sticks to that, when to see how it went, its health then. */
	private Action against;
	private long againstUntil, decidedUntil, judgeAt;
	private float healthThen;

	Gut(Companion c) {
		this.c = c;
	}

	/**
	 * The weighing, with no game in it (the tests run it): the fear of each of MOVES (in that order), Ex1's danger and
	 * its caution. The move to take instead, or null: what it's doing is fine.
	 */
	static Action weigh(float[] fear, float danger, float caution) {
		float keep = Math.max(fear[0], fear[1]);                            // standing, or walking on
		int best = 0;
		for (int i = 1; i < MOVES.length; i++) if (fear[i] < fear[best]) best = i;
		float relief = keep - fear[best];
		boolean worried = keep >= WORRY && relief * caution >= RELIEF || danger >= DREAD && relief >= 0.04f;
		return worried && best != 1 ? MOVES[best] : null;
	}

	/**
	 * Things that hurt it before (Aversions) close to its feet: the step that best takes it away from all of them (not
	 * into a wall, not onto one of them), or null. A push away from each, the closer the stronger.
	 */
	private Action keepAway(float[] fear) {
		var p = c.player;
		var level = p.level();
		BlockPos nearest = c.aversions.near(2);
		if (nearest == null) return null;
		Vec3 at = p.position();
		double nx = at.x - (nearest.getX() + 0.5), nz = at.z - (nearest.getZ() + 0.5);
		if (Math.hypot(nx, nz) > 2.2) return null;
		double px = 0, pz = 0;
		BlockPos feet = p.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 1, 3))) {
			String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath();
			if (n.equals("air") || c.aversions.of(n) < Aversions.KEEP_AWAY) continue;
			double dx = at.x - (q.getX() + 0.5), dz = at.z - (q.getZ() + 0.5), d = Math.max(0.3, Math.hypot(dx, dz));
			px += dx / (d * d * d);
			pz += dz / (d * d * d);
		}
		double len = Math.hypot(px, pz);
		if (len < 1e-6) return null;
		px /= len;
		pz /= len;
		Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
		Vec3 right = new Vec3(-look.z, 0, look.x);
		Vec3[] step = {Vec3.ZERO, look, look.scale(-1), right.scale(-1), right};      // (MOVES order: stay, forward, back, left, right)
		int best = -1;
		double bestScore = 0.25;
		for (int i = 1; i < step.length; i++) {
			Vec3 to = at.add(step[i].scale(0.9));
			BlockPos t = BlockPos.containing(to);
			if (!level.getBlockState(t).getCollisionShape(level, t).isEmpty() || !level.getBlockState(t.above()).getCollisionShape(level, t.above()).isEmpty()) continue;
			String under = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(t).getBlock()).getPath();
			if (c.aversions.of(under) >= Aversions.KEEP_AWAY) continue;              // (not onto it)
			double score = step[i].x * px + step[i].z * pz - 0.5 * fear[i];
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best < 0 ? null : MOVES[best];
	}

	Action override() {
		var p = c.player;
		if (p == null || c.inArena || p.isCreative() || p.isSpectator() || c.perceived == null || c.mod.brain == null) return null;
		judge();                                                                 // (it went against its gut: was the gut right?)
		// Instinct first (a clear danger), and under water short of air: air first, whatever the gut says. Drowning hurts
		// whatever it does there, so every move felt wrong and it "held still, that feels wrong" till it drowned.
		if (c.instinct.clear() || p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply()) {
			last = null;
			return null;
		}
		float[] all = c.mod.brain.fears(c.perceived);
		float[] fear = new float[MOVES.length];
		for (int i = 0; i < MOVES.length; i++) fear[i] = all[MOVES[i].ordinal()];
		float danger = c.ex1Out == null ? 0f : c.ex1Out.danger;
		Action move = weigh(fear, danger, c.personality.cautionScale());
		if (DEBUG && p.tickCount % 20 == 0) {
			c.journal("gut", String.format(java.util.Locale.ROOT, "feels: stay %.2f on %.2f back %.2f left %.2f right %.2f jump %.2f, danger %.0f%% -> %s",
					fear[0], fear[1], fear[2], fear[3], fear[4], fear[5], 100 * danger, move == null ? "fine" : move.verb));
		}
		if (move == null) move = keepAway(fear);                                 // what hurt it before: it keeps away from it
		if (move == null) {
			doing = "";
			last = null;
			return null;
		}
		long now = p.level().getGameTime();
		if (move == against && now < againstUntil) return null;                 // it made up its mind: it goes on
		if (move != last || now >= decidedUntil) {                              // a new feeling: does its mind go along?
			decidedUntil = now + 60;
			String why = argue(move, fear, danger);
			if (why != null) {
				against = move;
				againstUntil = now + 60;
				judgeAt = now + 70;
				healthThen = p.getHealth();
				overruled++;
				last = move;
				doing = "";
				c.journal("gut", "argues with its gut (it says " + move.verb + ") and goes on anyway: " + why);
				XenMod.LOG.info("{} argues with its gut (it says {}) and goes on anyway: {}", c.name, move.verb, why);
				boolean asked = c.commandedTo != null || c.chores.busy() && !c.chores.own;
				if (asked || java.util.concurrent.ThreadLocalRandom.current().nextFloat() < 0.25f) {
					c.chatter(c.pick3("My gut says no... going anyway.", "This feels wrong, but okay.", "Eh, I'll risk it."), asked);
				}
				return null;
			}
		}
		if (!c.reflexes.ready("gut", false)) return null;                       // (it feels it a reaction time later)
		overrides++;
		doing = switch (move) {
			case BACK -> "stepping back, that feels wrong";
			case LEFT, RIGHT -> "stepping aside, that feels wrong";
			case JUMP -> "jumping clear";
			default -> "holding still, that feels wrong";
		};
		c.goals.instant = doing;
		if (move != last) {
			XenMod.LOG.info("{}'s gut takes over: {}", c.name, move.verb);
			c.journal("gut", String.format(java.util.Locale.ROOT, "takes over: %s (fear: stay %.2f, on %.2f, %s %.2f; danger %.0f%%)", move.verb,
					fear[0], fear[1], move.verb, all[move.ordinal()], 100 * danger));
			boolean asked = c.commandedTo != null || c.chores.busy() && !c.chores.own;
			if (asked && p.level().getGameTime() - saidAt > 600) {
				saidAt = p.level().getGameTime();
				c.chatter(c.pick3("Hold on!", "Whoa, wait.", "Nope, not there."), true);
			}
		}
		last = move;
		c.walker.stop();
		return move;
	}

	/**
	 * Its mind's case against its gut, now that the gut says "move" (fear: what each of MOVES is expected to cost). The
	 * reason it goes on anyway, or null: the gut wins (most of the time, more so the surer the gut and the more it
	 * trusts it). A weighed chance, not a rule: the same Xen in the same spot may go either way.
	 */
	private String argue(Action move, float[] fear, float danger) {
		var p = c.player;
		var pe = c.personality;
		if (faith < 0) faith = Math.max(0.2f, Math.min(0.9f, 0.55f + 0.25f * (pe.cautionScale() - 1f)));
		if (danger >= DREAD && p.getHealth() < 10) return null;                 // (scared and hurt: no arguing)
		arguments++;
		float keep = Math.max(fear[0], fear[1]), low = fear[0];
		for (float f : fear) low = Math.min(low, f);
		float sure = Math.max(0f, Math.min(1f, (keep - low) * pe.cautionScale() / (RELIEF * 3)));   // how sure the gut is
		float mind = 0, top = -1;
		String why = "";
		boolean asked = c.commandedTo != null || c.chores.busy() && !c.chores.own;
		float[] parts = {asked ? 0.35f : 0f, (p.getHealth() / p.getMaxHealth() - 0.5f) * 0.4f,
				(pe.bravery - 0.5f) * 0.5f + 0.5f * pe.risk(), 0.35f * (1 - sure)};
		String[] reasons = {"it was asked to", "it can take a hit", "it's braver than that", "the feeling is faint"};
		for (int i = 0; i < parts.length; i++) {
			mind += parts[i];
			if (parts[i] > top) {
				top = parts[i];
				why = reasons[i];
			}
		}
		float gut = faith * (0.3f + 0.7f * sure) + 0.5f * danger;
		float chance = (float) (1 / (1 + Math.exp(-(mind - gut) * 5)));
		boolean goes = java.util.concurrent.ThreadLocalRandom.current().nextFloat() < chance;
		if (DEBUG || !goes && arguments % 10 == 1) {
			c.journal("gut", String.format(java.util.Locale.ROOT, "argues with its gut (%s): mind %.2f, gut %.2f (sure %.2f, trust %.2f, danger %.0f%%): %.0f%% to go on -> %s",
					move.verb, mind, gut, sure, faith, 100 * danger, 100 * chance, goes ? "goes on" : "listens"));
		}
		return goes ? String.format(java.util.Locale.ROOT, "%s (mind %.2f against gut %.2f)", why, mind, gut) : null;
	}

	/** A while after it went against its gut: hurt since? Then the gut was right, and it trusts it more. */
	private void judge() {
		var p = c.player;
		if (judgeAt == 0 || p.level().getGameTime() < judgeAt) return;
		judgeAt = 0;
		if (p.getHealth() < healthThen - 0.9f) {
			gutRight++;
			faith = Math.min(0.95f, faith + 0.12f);
			c.journal("gut", String.format(java.util.Locale.ROOT, "went against its gut and got hurt: it trusts its gut more now (%.2f)", faith));
			XenMod.LOG.info("{} went against its gut and got hurt: trusts it more ({})", c.name, String.format(java.util.Locale.ROOT, "%.2f", faith));
			if (java.util.concurrent.ThreadLocalRandom.current().nextFloat() < 0.5f) {
				c.chatter(c.pick3("Ow. Should've listened to my gut.", "Okay, my gut was right.", "Next time I listen to my gut."), false);
			}
		} else {
			gutWrong++;
			faith = Math.max(0.1f, faith - 0.04f);
			c.journal("gut", String.format(java.util.Locale.ROOT, "went against its gut and was fine (trust in it %.2f)", faith));
			XenMod.LOG.info("{} went against its gut and was fine: trusts it less ({})", c.name, String.format(java.util.Locale.ROOT, "%.2f", faith));
		}
	}
}
