package xen.mod;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Anger and bloodlust: hidden (never shown, never said; you find out from what it does), each 0 to 100.
 * <p>Anger stacks. Each thing that gets to it (a hit, an insult, dying, someone getting away) is one or more stacks,
 * and each stack is 1 to 6 points of anger and, with it, 1 to 3 of bloodlust: bloodlust climbs about half as fast, so
 * 100 anger isn't 100 bloodlust. Anger cools in minutes (slower for a wrathful one, quicker for a cool head); bloodlust
 * lingers for much longer, and a bloodthirsty one (a hidden stat) never loses all of it.
 * <p>Anger makes it swear more ({@link xen.mod.talk.Cursing}) and hit back sooner (a friend's slip isn't let go).
 * Bloodlust makes it go after people: with a reason (anyone it holds something against: the more bloodlust, the less
 * it takes) and, when it runs high, for no reason at all: someone close by it just picks. It's still its own choice
 * (its mind weighs a fight like anything else), never in a truce, never its owner or a close friend for no reason, and
 * not when players can't be fought (the pvp setting off). A kill takes the edge off both.
 */
final class Rage {
	private final Companion c;
	/** 0 to 100. */
	float anger, bloodlust;
	/** Someone it's set on killing for no reason, and till when. */
	UUID mark;
	long markUntil;
	private long markCheckedAt;
	/** How angry it was (in quarters) when it last noted it, for the journal. */
	private int noted;

	Rage(Companion c) {
		this.c = c;
	}

	/** How far its bloodlust can fall (a bloodthirsty one: never to nothing): 0 to 40. */
	float floor() {
		return Math.max(0f, (c.personality.hidden(Personality.BLOODTHIRST) - 0.6f) * 100f);
	}

	/** Something got to it: so many stacks (1 to 6 anger and 1 to 3 bloodlust each). */
	void provoke(int stacks, String why) {
		if (c.inArena || c.minion || stacks <= 0) return;
		var r = c.random();
		for (int i = 0; i < stacks; i++) {
			anger = Math.min(100f, anger + 1 + r.nextInt(6));
			bloodlust = Math.min(100f, bloodlust + 1 + r.nextInt(3));
		}
		int level = (int) anger / 25;
		if (level > noted) {
			noted = level;
			c.journal("feels", String.format("angrier: %s (anger %.0f%%, bloodlust %.0f%%)", why, anger, bloodlust));
		}
	}

	/** It killed someone: that takes the edge off. */
	void killed(LivingEntity who) {
		if (!(who instanceof ServerPlayer p)) return;
		var r = c.random();
		anger = Math.max(0f, anger - 10 - r.nextInt(11));
		bloodlust = Math.max(floor(), bloodlust - 8 - r.nextInt(13));
		if (p.getUUID().equals(mark)) {
			c.journal("feels", "killed " + p.getName().getString() + ", the one it wanted dead (bloodlust " + Math.round(bloodlust) + "%)");
			mark = null;
		}
	}

	/** Every second: it cools down; with its bloodlust up, now and then it picks someone (one it has nothing against). */
	void second() {
		float cool = 0.5f + c.personality.hidden(Personality.COMPOSURE);                      // (0.5x to 1.5x)
		float wrath = c.personality.sin(xen.mod.core.Sins.WRATH);
		anger = Math.max(0f, anger - 8f * cool * (1 - 0.5f * wrath) / 60f);                // (about 8 a minute: furious to calm in a quarter of an hour)
		if (anger < noted * 25 - 10) noted = Math.max(0, (int) anger / 25);
		float least = floor();
		bloodlust = bloodlust <= least ? least : Math.max(least, bloodlust - 1f * cool * (1 - 0.5f * wrath) / 60f);   // (about 1 a minute: it lingers for an hour or two)
		long now = c.player.level().getGameTime();
		if (mark != null) {
			ServerPlayer m = c.server.getPlayerList().getPlayer(mark);
			if (now > markUntil || m == null || !m.isAlive() || m.level() != c.player.level() || c.diplomacy.atPeace(m) || bloodlust < 30) {
				if (now > markUntil && m != null) c.journal("feels", "lets " + m.getName().getString() + " go (for now)");
				mark = null;
			}
		}
		if (mark == null && now - markCheckedAt >= 1200 && bloodlust >= 50 && c.mode == Companion.Mode.FREE && !c.mod.config.pvp.equals("off") && c.age() > 20 * 60 * 3) {
			markCheckedAt = now;
			if (c.random().nextFloat() < (bloodlust - 40f) / 200f) pick(now);               // (bloodlust 50: 1 in 20 a minute; 100: 3 in 10)
		}
	}

	private void pick(long now) {
		ServerPlayer best = null;
		double bestD = 24;
		Tribe t = c.tribe();
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p == c.player || p.level() != c.player.level() || !p.isAlive() || p.isCreative() || p.isSpectator()) continue;
			float trust = c.trust(p.getUUID());
			if (p.getUUID().equals(c.owner) || trust >= 0.6f || trust < 0f || c.diplomacy.atPeace(p) || c.skills.sparringWith(p.getUUID())) continue;   // (one it holds something against: that's with a reason)
			if (t != null && p instanceof XenPlayer x && x.companion != null && t.members.contains(x.companion) && bloodlust < 85) continue;   // (its own village: only at its worst)
			double d = p.distanceTo(c.player);
			if (d < bestD && c.player.hasLineOfSight(p)) {
				bestD = d;
				best = p;
			}
		}
		if (best == null) return;
		mark = best.getUUID();
		markUntil = now + 20 * 60 * 2;
		c.journal("feels", String.format("wants %s dead, for no reason (bloodlust %.0f%%, anger %.0f%%)", best.getName().getString(), bloodlust, anger));
		XenMod.LOG.info("{} wants {} dead, for no reason (bloodlust {}%)", c.name, best.getName().getString(), Math.round(bloodlust));
	}

	/** Its bloodlust, 0 to 1. */
	float lust() {
		return bloodlust / 100f;
	}

	/** Its anger, 0 to 1. */
	float mad() {
		return anger / 100f;
	}

	/** Out for blood: someone it wants dead, or bloodlust that high. */
	boolean wantsBlood() {
		return mark != null || bloodlust >= 40;
	}

	/** Someone it'd fight for what they did (or for nothing, if it picked them): its trust in them, and its bloodlust. */
	boolean wouldKill(ServerPlayer p, float trust) {
		if (p.getUUID().equals(mark)) return true;
		float grudge = -0.25f + 0.25f * Math.min(1f, lust() / 0.6f);                         // (bloodlust 60 and up: any grudge is reason enough)
		return trust < grudge && bloodlust >= 20;
	}

	/** How much it swears right now (0 to 1): how foul its mouth is, and how angry it is. */
	float swearing(boolean hurt) {
		float foul = c.personality.hidden(Personality.FOUL_MOUTH);
		float tone = switch (c.personality.tone) {
			case "shy", "calm" -> 0.7f;
			case "grumpy", "bold" -> 1.2f;
			default -> 1f;
		};
		return Math.max(0f, Math.min(1f, foul * foul * 1.4f * tone * (0.4f + 1.2f * mad()) + (hurt ? 0.15f * foul : 0f)));
	}

	JsonObject save() {
		JsonObject o = new JsonObject();
		o.addProperty("anger", Math.round(anger * 10) / 10f);
		o.addProperty("bloodlust", Math.round(bloodlust * 10) / 10f);
		return o;
	}

	void load(JsonObject o) {
		if (o.has("anger")) anger = Math.max(0f, Math.min(100f, o.get("anger").getAsFloat()));
		if (o.has("bloodlust")) bloodlust = Math.max(0f, Math.min(100f, o.get("bloodlust").getAsFloat()));
	}
}
