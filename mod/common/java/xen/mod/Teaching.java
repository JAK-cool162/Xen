package xen.mod;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xen 2.0: teaching, and the talk of a fight.
 * <ul>
 *   <li><b>Xen to Xen</b>: two Xens together, one much better at something (each is born with its own random skills):
 *   it shows the other how ("watch, you hold it like this"), and the other gets a little better. What hurt it, it
 *   warns the other about ("careful, lava burns"): the other keeps away from it without being hurt first.</li>
 *   <li><b>Xen to player</b>: "teach me mining", "any tips for fighting?", "how do I get better at farming?": a tip
 *   from what it knows; one that's bad at it says so.</li>
 *   <li><b>Player to Xen</b>: "lava burns", "stay away from cactus", "magma is dangerous": it minds that from then on.</li>
 *   <li><b>A fight</b>: hit by someone, it talks like it ("bro what", "you started it"); and if they crouch at it after
 *   hitting it (a player's sorry), it makes up its own mind: it forgives, or "you ain't my friend after attacking me".</li>
 * </ul>
 */
final class Teaching {
	private final Companion c;
	private final Random random = new Random();
	private final Map<UUID, Long> taught = new HashMap<>();
	private long nextLook = -1;
	/** When any Xen last gave a lesson or a warning out loud (one at a time, not a chorus of tips). */
	private static long lastLessonMs;
	/** The one who hit it last: their crouches since, to read a sorry. */
	private UUID crouchFrom;
	private boolean crouched;
	private int crouches;
	private long crouchStart, judgedAt;
	int lessonsGiven, lessonsTaken, forgiven, refused;

	Teaching(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private static final String[][] TIPS = {
			{"strafe while you hit, and wait for the sword to charge: spam clicking does nothing", "jump as you hit for a crit (on the way down)",
					"shield up when they wind up, then hit back", "an axe hit knocks a shield out for a few seconds"},
			{"plan the shape first, then the details", "mix two or three blocks, never just one", "add depth: stairs and slabs on the walls",
					"a roof that sticks out a block looks way better"},
			{"mine in a tunnel two high, feet and head", "never dig straight down", "iron's best around y 16, diamonds near y -59",
					"take a bucket of water, for lava", "light it up as you go: torches every 8 blocks"},
			{"farmland next to water, within 4 blocks", "bone meal speeds crops up", "keep two of each animal and breed them",
					"replant as you harvest"},
			{"villagers change prices: cure a zombie villager for big discounts", "emeralds from farmers and fletchers are easy",
					"lock a good trade in by trading often"},
			{"sprint-jump to travel fast", "water bucket under you when you fall: the clutch", "crouch on edges so you don't fall off"}};

	private static final Pattern TEACH_ME = Pattern.compile("\\b(teach me|tips?|advice|how (do|can|should) i (get better|improve|be better)|show me how)\\b");
	private static final Pattern DANGER = Pattern.compile("\\b(?:stay away from|avoid|don'?t (?:touch|go near|walk into))\\s+(?:the\\s+)?([a-z ]+?)\\b(?:[.!,]|$)"
			+ "|\\b([a-z ]+?)\\s+(?:is|are)?\\s*(?:dangerous|hurts?|burns?|is bad|will kill you|kills)\\b");

	/** A thing a player names, as what it minds ("magma" is the magma block, "berry bush" the sweet berry bush); null if it doesn't know it. */
	static String thing(String words) {
		String w = words.trim().replaceAll("^(the|a|an)\\s+", "");
		return switch (w) {
			case "lava" -> "lava";
			case "fire", "flames" -> "fire";
			case "soul fire" -> "soul_fire";
			case "campfire", "campfires" -> "campfire";
			case "magma", "magma block", "magma blocks" -> "magma_block";
			case "cactus", "cacti" -> "cactus";
			case "berry bush", "berry bushes", "sweet berry bush", "berries" -> "sweet_berry_bush";
			case "powder snow" -> "powder_snow";
			default -> null;
		};
	}

	/** What a player says, if it's teaching or asking to be taught (null: something else). */
	String heard(ServerPlayer from, String words) {
		String w = words.toLowerCase(Locale.ROOT);
		Matcher d = DANGER.matcher(w);
		if (d.find()) {
			String t = thing(d.group(1) != null ? d.group(1) : d.group(2));
			if (t != null) {
				boolean knew = c.aversions.of(t) >= Aversions.KEEP_AWAY;
				c.aversions.learn(t, 0.4f, "told by " + from.getName().getString());
				return knew ? c.pick3("ik, " + Aversions.said(t) + " is no joke", "yeah, learned that one the hard way", "Yep, I keep away from it.")
						: c.pick3("Good to know! I'll keep away from " + Aversions.said(t) + ".", "oh, thanks for the heads up", "Noted: no " + Aversions.said(t) + ".");
			}
		}
		if (TEACH_ME.matcher(w).find()) {
			int s = Skills.named(w);
			if (s < 0) s = best();
			float level = c.skills.get(s);
			lessonsGiven++;
			if (level < 0.35f) return c.pick3("tbh I'm bad at " + Skills.NAMES[s] + ", ask someone else lol", "idk, I'm still learning " + Skills.NAMES[s] + " myself",
					"Not my thing. I'm better at " + Skills.NAMES[best()] + ".");
			String tip = TIPS[s][random.nextInt(TIPS[s].length)];
			return level >= 0.7f ? c.pick3("ok here's the trick: " + tip + ".", "easy: " + tip + ".", "Listen: " + tip + ".")
					: c.pick3("try this: " + tip + ".", "What helps me: " + tip + ".", "I think " + tip + ".");
		}
		return null;
	}

	/** Its best skill. */
	private int best() {
		int b = 0;
		for (int i = 1; i < Skills.NAMES.length; i++) if (c.skills.get(i) > c.skills.get(b)) b = i;
		return b;
	}

	/** Now and then, with another Xen close by: a lesson (a skill it's much better at), a warning (what hurt it). */
	void tick() {
		var p = c.player;
		if (p == null || c.inArena || c.fightingNow()) return;
		if (nextLook < 0) nextLook = now() + (Talker.FAST ? 200 : 20 * (120 + random.nextInt(240)));   // (just came: a few minutes before it teaches anyone)
		if (now() < nextLook) return;
		nextLook = now() + 20 * 20;
		if (System.currentTimeMillis() - lastLessonMs < (Talker.FAST ? 5_000 : 45_000)) return;
		for (Companion o : c.mod.companions) {
			if (o == c || o.player == null || o.player.level() != p.level() || o.player.distanceTo(p) > 6 || o.fightingNow()) continue;
			if (now() - taught.getOrDefault(o.player.getUUID(), -1_000_000L) < 20 * 60 * 10) continue;
			for (var e : c.aversions.of.entrySet()) {                     // what hurt it: a warning
				if (e.getValue() >= 0.5f && o.aversions.of(e.getKey()) < Aversions.KEEP_AWAY) {
					taught.put(o.player.getUUID(), now());
					lastLessonMs = System.currentTimeMillis();
					o.aversions.learn(e.getKey(), 0.35f, "told by " + c.name);
					c.chatter(c.pick3("careful with " + Aversions.said(e.getKey()) + ", " + o.name + ". trust me", o.name + ", stay away from " + Aversions.said(e.getKey()) + ". learned that the hard way",
							"Heads up " + o.name + ": " + Aversions.said(e.getKey()) + " hurts."), false);
					c.journal("teaches", o.name + " to keep away from " + Aversions.said(e.getKey()));
					return;
				}
			}
			for (int s = 0; s < Skills.NAMES.length; s++) {               // a skill it's much better at
				float gap = c.skills.get(s) - o.skills.get(s);
				if (gap < 0.25f) continue;
				taught.put(o.player.getUUID(), now());
				lastLessonMs = System.currentTimeMillis();
				float gain = 0.08f * gap * (0.6f + 0.8f * o.personality.diligence);
				o.skills.level[s] = Math.min(1f, o.skills.level[s] + gain);
				lessonsGiven++;
				o.teaching.lessonsTaken++;
				c.chatter(c.pick3("hey " + o.name + ", watch: " + TIPS[s][random.nextInt(TIPS[s].length)] + ".", o.name + ", lemme show you something about " + Skills.NAMES[s],
						"Pro tip, " + o.name + ": " + TIPS[s][random.nextInt(TIPS[s].length)] + "."), false);
				c.journal("teaches", String.format(Locale.ROOT, "%s some %s (%.2f -> %.2f)", o.name, Skills.NAMES[s], o.skills.level[s] - gain, o.skills.level[s]));
				return;
			}
		}
	}

	/** Every tick: the one who hit it, crouching at it (down, up, down: a sorry), and what it makes of that. */
	void watchCrouch() {
		var p = c.player;
		if (p == null || c.struckBy == null || p.tickCount - c.struckAt > 20 * 60) return;
		if (!c.struckBy.equals(crouchFrom)) {
			crouchFrom = c.struckBy;
			crouches = 0;
			crouched = false;
		}
		ServerPlayer by = c.server.getPlayerList().getPlayer(c.struckBy);
		if (by == null || by.level() != p.level() || by.distanceTo(p) > 8) return;
		long now = now();
		boolean down = by.isShiftKeyDown();
		if (down && !crouched) {
			if (now - crouchStart > 50) crouches = 0;
			if (crouches == 0) crouchStart = now;
			crouches++;
		}
		crouched = down;
		if (crouches >= 2 && now - judgedAt > 200 && !by.isShiftKeyDown()) {
			judgedAt = now;
			crouches = 0;
			judge(by);
		}
	}

	/** Its own call: forgive the one who hit it, or not (kindness and trust for; wrath and how many hits against). */
	private void judge(ServerPlayer by) {
		var pe = c.personality;
		int hits = c.hitsTaken.getOrDefault(by.getUUID(), 1);
		float trust = c.trust(by.getUUID());
		float chance = 0.25f + 0.5f * pe.kindness - 0.4f * pe.sin(xen.mod.core.Sins.WRATH) + 0.25f * trust + 0.2f * pe.hidden(Personality.COMPOSURE)
				- 0.12f * (hits - 1) - (pe.aggressive() ? 0.15f : 0);
		chance = Math.max(0.03f, Math.min(0.95f, chance));
		String who = by.getName().getString();
		if (random.nextFloat() < chance) {
			forgiven++;
			c.diplomacy.makePeace(by, "they crouched: sorry");
			c.trust.put(by.getUUID(), Math.min(1f, Math.max(trust, 0f) + 0.1f));
			c.say(c.pick3("ok ok... we're good, " + who, "fine. don't do that again", "alright, apology accepted lol"));
			c.journal("forgives", who + " (they crouched after hitting it; chance " + Math.round(chance * 100) + "%)");
		} else {
			refused++;
			c.trust.merge(by.getUUID(), -0.05f, (a, b) -> Math.max(-1f, a + b));
			c.say(c.pick3("you ain't my friend after attacking me", "nah. crouching won't fix it, " + who, "too late for that"));
			c.journal("won't forgive", who + " (they crouched after hitting it; chance " + Math.round(chance * 100) + "%)");
		}
	}

	/** What it says back in a fight with this player, to anything that isn't a truce (null: not fighting them). */
	String inFight(ServerPlayer from, String words) {
		var p = c.player;
		boolean fighting = p.getLastHurtByMob() == from && p.tickCount - p.getLastHurtByMobTimestamp() < 400 || c.fightingNow() && c.struckBy != null && c.struckBy.equals(from.getUUID());
		if (!fighting) return null;
		String w = words.toLowerCase(Locale.ROOT);
		if (w.contains("?")) return c.pick3("why'd you hit me??", "bro what", "you started it");
		if (w.matches(".*\\b(lol|lmao|haha|ez|gg)\\b.*")) return c.pick3("not funny", "ez? we'll see", "laugh now");
		return random.nextBoolean() ? c.pick3("not now!", "you hit me first", "back off") : "stop!!";
	}
}
