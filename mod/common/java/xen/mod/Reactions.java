package xen.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Sins;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Noticing people, the way players do. Someone comes into view (not seen for five minutes) and it reacts for a few
 * seconds, in its own way: what it is (its sins and beliefs), how far it trusts them, and what they carry.
 * <ul>
 *   <li><b>hello</b>: turns to them and crouches twice, says hi (a friend, a lustful or "everyone's a friend" Xen);</li>
 *   <li><b>wary</b>: watches them and backs off a few steps (a stranger with a sword, "strangers are dangerous", a timid one);</li>
 *   <li><b>stare</b>: sword out, stands its ground and stares them down (wrath, pride, "the strong rule");</li>
 *   <li><b>boast</b>: shows off (pride, when its gear is as good as theirs);</li>
 *   <li><b>envy</b>: sizes up their gear, and trusts them a little less (envy, when theirs is better);</li>
 *   <li><b>trade</b>: goes a little closer and asks for a deal (greed);</li>
 *   <li><b>nod</b>: a look and a "hey", then back to what it was doing (sloth, "talk is cheap").</li>
 * </ul>
 * Never in a fight, under water, or on the way to its bed at night; only a look for a Xen it's working next to.
 */
final class Reactions {
	private final Companion c;
	private final Random random = new Random();
	/** When it last had each player in view. */
	private final Map<UUID, Long> seen = new HashMap<>();
	private ServerPlayer who;
	private String how = "";
	private long until;
	private int step;
	/** How many times it reacted each way (for the journal and the tests). */
	final Map<String, Integer> counts = new HashMap<>();

	Reactions(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	boolean busy() {
		return who != null;
	}

	/** Anyone new in view? (twice a second) */
	void look() {
		var p = c.player;
		if (p == null || p.tickCount % 10 != 0 || c.inArena) return;
		long now = now();
		ServerPlayer first = null;
		for (ServerPlayer o : c.server.getPlayerList().getPlayers()) {
			if (o == p || o.level() != p.level() || o.isSpectator() || o.distanceTo(p) > 20 || !p.hasLineOfSight(o)) continue;
			Long last = seen.put(o.getUUID(), now);
			if (last != null && now - last < 6000) continue;                    // (in view all along, or seen a moment ago)
			if (first == null) first = o;
		}
		if (seen.size() > 64) seen.values().removeIf(t -> now - t > 12000);
		if (first == null || busy() || c.fightingNow() || p.isInWater() || p.getHealth() < 8) return;
		start(first, now);
	}

	private void start(ServerPlayer o, long now) {
		how = choose(o);
		who = o;
		step = 0;
		until = now + switch (how) {
			case "nod" -> 25;
			case "hello" -> 40;
			case "wary" -> 60;
			default -> 50;
		};
		counts.merge(how, 1, Integer::sum);
		c.journal("does", "notices " + o.getName().getString() + ": " + how);
		String line = line(o);
		boolean justCame = c.player.tickCount < 200;                           // (it just said hello when it arrived: the gesture's enough)
		if (line != null && !justCame && random.nextFloat() < 0.35f + 0.65f * c.personality.talkative()) c.say(line);
	}

	/** Its way of meeting them: each reaction scored by its nature, the best (with a little chance) wins. */
	private String choose(ServerPlayer o) {
		Personality p = c.personality;
		float trust = c.trust(o.getUUID());
		boolean friend = o.getUUID().equals(c.owner) || o.getUUID().equals(c.leader) || trust > 0.6f;
		boolean armed = armed(o);
		int theirs = o.getArmorValue(), mine = c.player.getArmorValue();
		if (friend) return "hello";
		Map<String, Float> score = new HashMap<>();
		score.put("hello", 0.3f + 0.8f * p.sin(Sins.LUST) + (p.believes("everyone_friend") ? 0.6f : 0) + 0.8f * Math.max(0, trust) + 0.3f * p.kindness
				- (armed ? 0.3f : 0));
		score.put("wary", 0.1f + (p.believes("strangers_dangerous") ? 0.6f : 0) + (p.believes("trust_no_one") ? 0.8f : 0)
				+ (armed ? 0.4f * p.cautionScale() : 0) + Math.max(0, -trust) + 0.4f * (1 - p.bravery) + (theirs > mine + 4 ? 0.3f : 0));
		score.put("stare", 0.1f + 0.8f * p.sin(Sins.WRATH) + 0.4f * p.sin(Sins.PRIDE) + (p.believes("strong_rule") ? 0.4f : 0) + (armed ? 0.2f : 0)
				+ (trust < -0.3f ? 0.5f : 0));
		if (mine >= theirs) score.put("boast", 0.1f + 0.7f * p.sin(Sins.PRIDE) + (p.believes("big_house") ? 0.1f : 0));
		if (theirs > mine || better(o)) score.put("envy", 0.1f + 0.9f * p.sin(Sins.ENVY) + (p.believes("why_they_have_more") ? 0.6f : 0));
		if (!(o instanceof XenPlayer) || trust >= 0) score.put("trade", 0.1f + 0.7f * p.sin(Sins.GREED) + (p.believes("emeralds_rule") ? 0.3f : 0));
		score.put("nod", 0.2f + 0.6f * p.sin(Sins.SLOTH) + (p.believes("talk_is_cheap") ? 0.3f : 0));
		String best = "nod";
		float top = Float.NEGATIVE_INFINITY;
		for (var e : score.entrySet()) {
			float s = e.getValue() + 0.25f * random.nextFloat();
			if (s > top) {
				top = s;
				best = e.getKey();
			}
		}
		return best;
	}

	private static boolean armed(ServerPlayer o) {
		String id = BuiltInRegistries.ITEM.getKey(o.getMainHandItem().getItem()).getPath();
		return id.endsWith("_sword") || id.endsWith("_axe") || id.equals("bow") || id.equals("crossbow") || id.equals("trident") || id.equals("mace");
	}

	/** Their weapon beats its best one (diamond over iron, say). */
	private boolean better(ServerPlayer o) {
		String theirs = BuiltInRegistries.ITEM.getKey(o.getMainHandItem().getItem()).getPath();
		return tier(theirs) > c.crafter.pickTier();
	}

	private static int tier(String id) {
		return id.startsWith("netherite") ? 5 : id.startsWith("diamond") ? 4 : id.startsWith("iron") ? 3 : id.startsWith("stone") ? 2 : id.startsWith("wooden") ? 1 : 0;
	}

	private String line(ServerPlayer o) {
		String n = o.getName().getString();
		return switch (how) {
			case "hello" -> c.pick3("Hi " + n + "!", "Hey " + n + "! *crouches*", "Oh, hi " + n + ".");
			case "wary" -> c.pick3("Who's that?", "Stay back, " + n + ".", "...I'm watching you, " + n + ".");
			case "stare" -> c.pick3("What do you want, " + n + "?", "Don't try anything.", "You looking at me?");
			case "boast" -> c.pick3("Nice gear, " + n + ". Mine's better.", "Behold, the best player around.", "Take notes, " + n + ".");
			case "envy" -> c.pick3("Where'd you get that gear?", "Must be nice, having all that.", "Hmph. Show-off.");
			case "trade" -> c.pick3("Hey " + n + ", got anything to trade?", "Nice stuff. Want to make a deal?", "Selling anything?");
			default -> random.nextBoolean() ? "Hey." : null;
		};
	}

	/** Its move this moment while it reacts, or null (done, or something more important came up). */
	Action next() {
		if (who == null) return null;
		var p = c.player;
		long now = now();
		if (now > until || !who.isAlive() || who.level() != p.level() || who.distanceTo(p) > 28 || c.fightingNow() || p.isInWater()) {
			finish();
			return null;
		}
		step++;
		c.hands.watching = who;                                            // eyes on them, the whole time
		c.goals.instant = switch (how) {
			case "hello" -> "saying hi to " + who.getName().getString();
			case "wary" -> "keeping an eye on " + who.getName().getString();
			case "stare" -> "staring " + who.getName().getString() + " down";
			case "trade" -> "asking " + who.getName().getString() + " for a deal";
			default -> "looking at " + who.getName().getString();
		};
		c.acted = true;
		switch (how) {
			case "hello" -> {
				p.setShiftKeyDown(step % 8 < 4 && step < 17);                 // crouch, crouch: a player's hello
				return Action.IDLE;
			}
			case "wary" -> {
				p.setShiftKeyDown(false);
				if (step < 25 && who.distanceTo(p) < 10) {                    // a few steps back, facing them
					Vec3 away = p.position().subtract(who.position()).multiply(1, 0, 1);
					if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
					return c.walkTo(p.position().add(away.normalize().scale(4)));
				}
				return Action.IDLE;
			}
			case "stare" -> {
				if (step == 1) c.hands.ready();                               // sword out
				return Action.IDLE;
			}
			case "boast" -> {
				return step == 10 || step == 25 ? Action.JUMP : Action.IDLE;   // (a hop or two: look at me)
			}
			case "trade" -> {
				if (who.distanceTo(p) > 4 && step < 30) return c.walkTo(who.position());
				return Action.IDLE;
			}
			case "envy" -> {
				if (step == 1 && c.trust(who.getUUID()) > -0.15f) c.trust(who.getUUID(), -0.05f);   // (it holds it against them, a little: never enough to fight over)
				return Action.IDLE;
			}
			default -> {
				return step < 15 ? Action.IDLE : null;
			}
		}
	}

	private void finish() {
		if (c.player != null) c.player.setShiftKeyDown(false);
		if (c.hands.watching == who) c.hands.watching = null;
		who = null;
		how = "";
	}
}
