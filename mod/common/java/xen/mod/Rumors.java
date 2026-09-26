package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Xen has heard and seen about people: who is strong ("Steve is cracked at pvp", "Nova killed Pip"), who it
 * killed itself, and plots it overheard ("let's raid Bob's base"). With local chat (2 chunks), only what's said close
 * by is heard, so a Xen standing nearby can overhear, and tell the one it's about: a spy. Rumors spread when Xens
 * gossip. And some sights are a shock: someone it killed standing there alive again, or the one everyone says
 * nobody beats.
 */
final class Rumors {
	private final Companion c;
	private final Random random = new Random();
	/** Rumored fighting strength, by name (lower case), 0 to 1. */
	final Map<String, Float> strength = new HashMap<>();
	/** Whom it killed (players and Xens), and when (game time). */
	final Map<UUID, Long> killed = new HashMap<>();
	private final Map<UUID, Long> shockedAt = new HashMap<>();
	record Plot(String by, String against, String words, long at) {}
	final List<Plot> plots = new ArrayList<>();
	private long nextLook;
	/** Shocked right now: at whom, and for how many more decisions. */
	ServerPlayer shockedBy;
	int shockTicks;
	private boolean heardStronger;

	Rumors(Companion c) {
		this.c = c;
	}

	private static final Pattern STRONG = Pattern.compile("\\b([a-z0-9_]{3,16}) (?:is|'s|was) (?:so |really |very |super |crazy |insanely |kinda )?"
			+ "(strong|op|overpowered|a pro|pro|cracked|a beast|unbeatable|insane|a god|good at (?:pvp|fighting)|scary|dangerous)\\b");
	private static final Pattern WEAK = Pattern.compile("\\b([a-z0-9_]{3,16}) (?:is|'s|was) (?:so |really |very |such )?(weak|a noob|noob|bad at (?:pvp|fighting)|trash|easy)\\b");
	private static final Pattern KILLED = Pattern.compile("\\b([a-z0-9_]{3,16}) (?:killed|beat|destroyed|wrecked|one-shot|oneshot|obliterated) ([a-z0-9_]{3,16})\\b");
	private static final Pattern PLOT = Pattern.compile("\\b(?:let'?s|we should|we'll|i'?ll|i will|gonna|going to|we gonna|time to) "
			+ "(kill|attack|raid|rob|grief|steal from|ambush|betray|jump|trap|get) ([a-z0-9_]{3,16})\\b");

	/** Someone said something within earshot (a player, or another Xen). */
	void overheard(String speaker, UUID speakerId, String text) {
		if (c.player == null || speaker.equalsIgnoreCase(c.name)) return;
		String w = text.toLowerCase(Locale.ROOT);
		Matcher m = STRONG.matcher(w);
		while (m.find()) if (isSomeone(m.group(1))) raise(m.group(1), 0.65f, 0.1f);
		m = WEAK.matcher(w);
		while (m.find()) if (isSomeone(m.group(1))) strength.merge(m.group(1), 0.25f, Math::min);
		m = KILLED.matcher(w);
		while (m.find()) {
			if (!isSomeone(m.group(1))) continue;
			float victim = strength.getOrDefault(m.group(2), 0.4f);
			raise(m.group(1), Math.max(0.55f, victim + 0.1f), 0.05f);
		}
		m = PLOT.matcher(w.replaceAll("\"[^\"]*\"", ""));                     // (what someone else said, quoted, isn't their plot)
		while (m.find()) {
			String target = m.group(2);
			if (!isSomeone(target) || target.equalsIgnoreCase(speaker)) continue;
			if (target.equalsIgnoreCase(c.name)) {                          // about it: now it knows
				c.trust(speakerId, -0.3f);
				c.chatter(c.pick3("I heard that, " + speaker + ".", "Excuse me?! I can hear you, " + speaker + ".", "Try it, " + speaker + "."), true);
				continue;
			}
			plots.removeIf(p -> p.against().equals(target) && p.by().equalsIgnoreCase(speaker));
			plots.add(new Plot(speaker, target, text.length() > 80 ? text.substring(0, 80) + "..." : text, now()));
			if (plots.size() > 12) plots.remove(0);
			c.journal("hears", "overheard " + speaker + " plotting against " + target + ": " + text);
		}
	}

	private void raise(String name, float atLeast, float more) {
		float before = strength.getOrDefault(name, 0.4f);
		strength.put(name, Math.min(1f, Math.max(before, atLeast) + more));
		if (c.player != null && strength.get(name) > strength() + 0.2f) heardStronger = true;
	}

	/** A name it can put a face to: someone online (a player or a Xen). */
	private boolean isSomeone(String name) {
		if (c.server == null) return false;
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) if (p.getName().getString().equalsIgnoreCase(name)) return true;
		return false;
	}

	/** It saw (or heard, close by) someone die: at whose hands. */
	void witnessed(ServerPlayer victim, Entity killer) {
		if (c.player == null || victim == c.player) return;
		String v = victim.getName().getString().toLowerCase(Locale.ROOT);
		if (killer instanceof ServerPlayer k && k != victim) {
			String kn = k.getName().getString().toLowerCase(Locale.ROOT);
			if (k == c.player) {
				killed.put(victim.getUUID(), now());
				c.skills.practice(Skills.FIGHT, 0.03f);
				String vn = victim.getName().getString();
				c.chatter(c.personality.aggressive() ? c.pick3("ez.", "Too easy, " + vn + ".", "Stay down, " + vn + ".")
						: c.personality.passive() ? c.pick3("Sorry, " + vn + "... you made me.", "I didn't want to do that.", "...Sorry.")
						: c.pick3("GG, " + vn + "!", "Good fight, " + vn + ".", "GG. No hard feelings?"), true);
				return;
			}
			raise(kn, Math.max(0.55f, strength.getOrDefault(v, 0.4f) + 0.1f), 0.05f);
			if (c.trust(victim.getUUID()) > 0.5f) {                          // a friend: grief, and a grudge
				c.trust(k.getUUID(), -0.35f);
				c.chatter(c.pick3("No! " + victim.getName().getString() + "!", k.getName().getString() + "... you'll pay for that.",
						"Not " + victim.getName().getString() + "..."), true);
			} else if (c.trust(victim.getUUID()) < -0.3f) {
				c.chatter(c.pick3("Ha. Good riddance.", "Serves them right.", "Nice one, " + k.getName().getString() + "."), false);
			}
		} else if (c.trust(victim.getUUID()) > 0.5f) {
			c.chatter(c.pick3("Oh no, " + victim.getName().getString() + "!", "Careful, " + victim.getName().getString() + "!", "Ouch. Poor " + victim.getName().getString() + "."), false);
		}
	}

	/** Its own fighting strength as it sees it (gear and skill), 0 to 1. */
	float strength() {
		return 0.3f * MindSense.armor(c.player) / 4f + 0.25f * MindSense.best(c, "_sword") / 4f + 0.45f * c.skills.get(Skills.FIGHT);
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/**
	 * Twice a second: who's in sight? Someone it killed, alive again, or the one it heard nobody beats: a shock. And
	 * someone a plot it overheard is against: it may warn them.
	 */
	void look() {
		if (c.player == null || c.inArena || now() < nextLook) return;
		nextLook = now() + 10;
		var me = c.player;
		Vec3 look = me.getLookAngle();
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p == me || p.level() != me.level() || p.isSpectator() || p.distanceTo(me) > 20) continue;
			Vec3 to = p.getEyePosition().subtract(me.getEyePosition()).normalize();
			if (to.dot(look) < 0.5 || !me.hasLineOfSight(p)) continue;         // (only what's in front of its eyes)
			UUID id = p.getUUID();
			String name = p.getName().getString();
			long shocked = shockedAt.getOrDefault(id, -1_000_000L);
			Long k = killed.get(id);
			if (k != null && k > shocked && now() - k > 200) {
				shock(p, c.pick3("W-wait... I killed you!", "You're ALIVE?!", "No way. I finished you off, " + name + "!"));
				continue;
			}
			float s = strength.getOrDefault(name.toLowerCase(Locale.ROOT), 0f);
			if (s > strength() + 0.2f && now() - shocked > 20 * 60 * 10 && c.trust(id) < 0.6f) {
				shock(p, s > 0.85f ? c.pick3("That's " + name + "... they say nobody beats them.", "Oh no. It's " + name + ".", "Is that... " + name + "? The " + name + "?")
						: c.pick3("Uh oh, " + name + ". I heard about you.", name + "... I've heard you're strong.", "Careful... that's " + name + "."));
				continue;
			}
			for (Plot plot : new ArrayList<>(plots)) {                        // a spy's report
				if (!plot.against().equalsIgnoreCase(name) || p.distanceTo(me) > 8) continue;
				plots.remove(plot);
				UUID byId = null;
				for (ServerPlayer q : c.server.getPlayerList().getPlayers()) if (q.getName().getString().equalsIgnoreCase(plot.by())) byId = q.getUUID();
				if (c.trust(id) < c.trust(byId) && c.personality.loyalty > 0.3f) continue;   // it keeps the secret for its friend
				c.say(c.pick3("Psst, " + name + ". I heard " + plot.by() + " say: \"" + plot.words() + "\"",
						name + ", watch out. " + plot.by() + " said: \"" + plot.words() + "\"", "Hey " + name + "... " + plot.by() + " is up to something: \"" + plot.words() + "\""));
				c.trust(id, 0.05f);
			}
		}
	}

	private void shock(ServerPlayer p, String line) {
		shockedAt.put(p.getUUID(), now());
		shockedBy = p;
		shockTicks = 8;                                                       // (a few decisions: it freezes and stares)
		c.emotions.fear = Math.max(c.emotions.fear, c.personality.bravery > 0.7f ? 0.35f : 0.6f);
		c.say(line);
		c.goals.instant = "shocked to see " + p.getName().getString();
		c.journal("feels", "shocked to see " + p.getName().getString());
		if (c.personality.aggressive() && c.personality.power > 0.6f && c.personality.bravery > 0.5f) {
			c.say(c.pick3("Let's see if the stories are true.", "Doesn't matter. I'm not scared.", "Round two, then."));
		}
	}

	/** While shocked: a step back, then frozen, staring. Null when it's over. */
	xen.mod.core.Action shockStep() {
		if (shockTicks <= 0 || shockedBy == null) return null;
		shockTicks--;
		c.hands.face(shockedBy.getEyePosition());
		c.acted = true;
		return shockTicks >= 6 ? xen.mod.core.Action.BACK : xen.mod.core.Action.IDLE;
	}

	/** Heard of someone stronger since it last asked (it may want to train). */
	boolean heardOfStronger() {
		boolean h = heardStronger;
		heardStronger = false;
		return h;
	}

	/** Something to gossip about, in words other Xens understand (so the rumor spreads); null if nothing. */
	String gossip() {
		if (strength.isEmpty() || random.nextFloat() < 0.4f) return null;
		List<String> names = new ArrayList<>(strength.keySet());
		String n = names.get(random.nextInt(names.size()));
		float s = strength.get(n);
		if (n.equalsIgnoreCase(c.name)) return null;
		String shown = n;
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) if (p.getName().getString().equalsIgnoreCase(n)) shown = p.getName().getString();
		if (s >= 0.8f) return c.pick3("Did you hear? " + shown + " is unbeatable.", "They say " + shown + " is insane at pvp.", "Word is " + shown + " is a beast.");
		if (s >= 0.6f) return c.pick3("I heard " + shown + " is really strong.", "Watch out for " + shown + ". " + shown + " is strong.", "People say " + shown + " is good at pvp.");
		if (s <= 0.3f) return c.pick3("Honestly, " + shown + " is weak.", "I heard " + shown + " is a noob.", shown + " is easy, they say.");
		return null;
	}

	/** For its notes. */
	String describe() {
		StringBuilder sb = new StringBuilder();
		List<String> strong = new ArrayList<>();
		for (var e : strength.entrySet()) if (e.getValue() >= 0.6f) strong.add(e.getKey());
		if (!strong.isEmpty()) sb.append("You've heard these are strong: ").append(String.join(", ", strong.subList(0, Math.min(4, strong.size())))).append(". ");
		if (!killed.isEmpty()) sb.append("You have killed ").append(killed.size()).append(killed.size() == 1 ? " person. " : " people. ");
		if (!plots.isEmpty()) sb.append("You overheard ").append(plots.get(plots.size() - 1).by()).append(" plotting against ").append(plots.get(plots.size() - 1).against()).append('.');
		return sb.toString().trim();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		JsonObject s = new JsonObject();
		strength.forEach(s::addProperty);
		o.add("strength", s);
		JsonArray k = new JsonArray();
		for (UUID u : killed.keySet()) k.add(u.toString());
		o.add("killed", k);
		return o;
	}

	void load(JsonObject o) {
		if (o.has("strength")) for (var e : o.getAsJsonObject("strength").entrySet()) strength.put(e.getKey(), e.getValue().getAsFloat());
		if (o.has("killed")) for (var e : o.getAsJsonArray("killed")) killed.put(UUID.fromString(e.getAsString()), 0L);
	}
}
