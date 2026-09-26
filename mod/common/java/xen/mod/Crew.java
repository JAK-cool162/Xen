package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Minion Xens: sidekicks with the same mind, for a Xen that's building its own little civilization (and for a
 * server that feels full). A minion is a real player like any Xen, but it doesn't load the world around it: it only
 * lives where someone else (its boss, a player) keeps the world loaded, and it freezes, mid-step, where nobody does,
 * until someone comes by. It takes orders only from its boss (and the boss's owner); without orders it lives its own
 * life close to its boss.
 * <p>The boss runs the crew: every minute or so it looks at who's idle and gives them work, the way a village grows:
 * a house for each of them around its home (a village), then wood, stone and iron, and food.
 */
final class Crew {
	private final Companion boss;
	final List<Companion> minions = new ArrayList<>();
	private final Random random = new Random();
	private long nextRound;
	private int houses;

	Crew(Companion boss) {
		this.boss = boss;
	}

	private long now() {
		return boss.player.level().getGameTime();
	}

	/** Every tick of the boss: now and then, work for the idle ones. */
	void tick() {
		minions.removeIf(m -> m.player == null || m.player.isRemoved() && m.respawning() < 0);
		if (minions.isEmpty() || boss.player == null || now() < nextRound) return;
		nextRound = now() + 1200;
		thinkAboutTheBoss(now());
		BlockPos home = boss.goals.home != null ? boss.goals.home : boss.player.blockPosition();
		for (Companion m : minions) {
			if (m.player == null || m.chores.busy() || m.builder.busy() || !m.awake()) continue;
			if (m.goals.home == null && !m.player.isCreative() && m.crafter.pickTier() >= 1) {   // a house of its own, in the village
				double angle = houses++ * 2.4, r = 14 + 4 * (houses / 5);
				BlockPos site = home.offset((int) Math.round(Math.cos(angle) * r), 0, (int) Math.round(Math.sin(angle) * r));
				String plan = m.builder.startNear("house", site);
				if (plan.startsWith("You will")) {
					order(m, m.name + ", build your house over there. The village grows!");
					continue;
				}
			}
			String[] jobs = {"wood", "stone", "iron", "food", "wood", "coal"};
			String job = jobs[random.nextInt(jobs.length)];
			if (job.equals("iron") && m.crafter.pickTier() < 2) job = "stone";
			int amount = switch (job) {
				case "wood" -> 12;
				case "stone" -> 24;
				case "iron", "coal" -> 6;
				default -> 3;
			};
			String what = job;
			m.request(new xen.mod.talk.Chat.Request(what, "", amount), boss.player, "get " + amount + " " + what);
			order(m, m.name + ", get " + amount + " " + (what.equals("food") ? "food for the village" : what) + ".");
		}
	}

	private void order(Companion m, String words) {
		boss.chatter(words.startsWith(m.name) ? words : words.substring(0, 1).toUpperCase() + words.substring(1), true);
		boss.journal("crew", "told " + m.name + ": " + words);
	}

	// ------------------------------------------------------------------------------ not loading the world
	private static Method updatePlayerStatus;

	/**
	 * Take a minion off the world's loading: the server loads the chunks around every player (that's what keeps a
	 * world alive around them); a minion lets the others do it. (ChunkMap's own switch for that, found by what it
	 * takes, so it works whatever the method is called in this version.)
	 */
	/** A minion that went its own way: it loads the world around it again, like any Xen. */
	static void startLoading(ServerPlayer p) {
		try {
			if (updatePlayerStatus == null) stopLoading(null);                 // (finds the method)
			if (updatePlayerStatus != null) updatePlayerStatus.invoke(((ServerLevel) p.level()).getChunkSource().chunkMap, p, true);
		} catch (ReflectiveOperationException | RuntimeException e) {
			XenMod.LOG.warn("A free minion couldn't load chunks: {}", e.toString());
		}
	}

	/**
	 * Minions are people too: now and then each thinks about its boss. One that doesn't trust it (the boss hit it,
	 * never shares), or that is out for power or money more than loyal, may walk away (and turn on the boss, if it's
	 * aggressive and thinks it can win). Loyal ones stay unless badly treated. The ones that leave are free Xens: they
	 * live their own life and load the world around them.
	 */
	void thinkAboutTheBoss(long now) {
		for (Companion m : new ArrayList<>(minions)) {
			if (m.player == null || !m.awake() || boss.player == null) continue;
			if (random.nextFloat() > 0.05f) continue;                          // (now and then)
			var p = m.personality;
			float trust = m.trust(boss.player.getUUID());
			boolean mistreated = trust < -0.2f;
			boolean ambitious = p.loyalty < 0.35f && (p.power > 0.6f || p.money > 0.6f) && random.nextFloat() < 0.15f;
			if (p.loyalty > 0.6f && trust > -0.4f || !mistreated && !ambitious) continue;
			boolean turn = p.aggressive() && p.power > 0.5f && m.player.getHealth() >= boss.player.getHealth();
			m.goFree(turn ? "I'm done taking orders from you, " + boss.name + "!" : mistreated ? "I've had enough, " + boss.name + ". I'm leaving."
					: "I'm going my own way, " + boss.name + ". No hard feelings.", turn ? boss.player : null);
		}
	}

	static void stopLoading(ServerPlayer p) {
		if (p == null && updatePlayerStatus != null) return;
		try {
			if (updatePlayerStatus == null) {
				for (Method m : ChunkMap.class.getDeclaredMethods()) {
					Class<?>[] t = m.getParameterTypes();
					if (t.length == 2 && t[0] == ServerPlayer.class && t[1] == boolean.class && m.getReturnType() == void.class) {
						m.setAccessible(true);
						updatePlayerStatus = m;
						break;
					}
				}
			}
			if (p == null) return;                                           // (only finding the method)
			if (updatePlayerStatus != null) updatePlayerStatus.invoke(((ServerLevel) p.level()).getChunkSource().chunkMap, p, false);
		} catch (ReflectiveOperationException | RuntimeException e) {
			XenMod.LOG.warn("A minion couldn't be taken off chunk loading (it loads chunks like a normal Xen): {}", e.toString());
		}
	}
}
