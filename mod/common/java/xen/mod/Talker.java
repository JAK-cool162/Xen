package xen.mod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import xen.mod.core.Blocks;
import xen.mod.core.Perception;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A Xen talking on its own, only about what's true for it: what it sees, feels, carries and wants. It greets people it
 * knows, now and then says what's on its mind, and asks things you can answer with a yes or a no ("It's dark. Should I
 * build us a shelter?"). When it meets another Xen they chat: who they are, what they dream of, and tips about where
 * they saw trees and ore, which the other one then knows too (less sure than if it had seen it itself). How often it
 * talks is up to its chattiness, and there's a limit for everyone together, so chat never floods.
 */
final class Talker {
	private static final Pattern YES = Pattern.compile("^(yes|yeah|yep|yup|ya|sure|ok|okay|please|go ahead|do it|alright|of course|why not|y)\\b");
	private static final Pattern NO = Pattern.compile("^(no|nope|nah|not now|don'?t|no thanks|n)\\b");
	/** Who talked last, for all Xens together (game time): at most one remark every 10 seconds. */
	private static long anyoneSpoke = -1_000_000;
	private static int conversations;

	private final Companion c;
	private final Random random = new Random();
	/** -Dxen.fastTalk=true: talk every ten seconds (for testing). */
	static final boolean FAST = Boolean.getBoolean("xen.fastTalk");
	private long nextRemark = -1, nextXenTalk;
	/** A question it asked, waiting for a yes or no: what it's about, from whom, until when. */
	private String asked, askedThing;
	private UUID askedWho;
	private int askedCount;
	private long askedUntil;
	private final Map<UUID, Long> greeted = new HashMap<>();
	private long lastGreetCheck;
	private final Set<UUID> near = new HashSet<>();
	private final Map<String, Long> lastWith = new HashMap<>();
	private final Set<String> said = new HashSet<>();
	/** When it last asked each kind of question (it doesn't ask the same thing again for five minutes). */
	private final Map<String, Long> askedAt = new HashMap<>();
	private String lastRemark = "";
	/** The lines of a chat with another Xen, one every few seconds: {speaker, text, when, what it does}. */
	private final ArrayDeque<Object[]> lines = new ArrayDeque<>();

	Talker(Companion c) {
		this.c = c;
	}

	private int tone() {
		return Math.max(0, java.util.Arrays.asList(Personality.TONES).indexOf(c.personality.tone));
	}

	/** One of six ways to say it, by its tone: cheerful, calm, grumpy, shy, bold, silly. */
	private String pick(String... byTone) {
		return byTone[Math.min(byTone.length - 1, tone())];
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	boolean busy() {
		return !lines.isEmpty() || asked != null && now() < askedUntil;
	}

	/** Players (not Xens) within this distance hear it. */
	private List<ServerPlayer> listeners(double distance) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (!(p instanceof XenPlayer) && p.level() == c.player.level() && p.distanceTo(c.player) <= distance) out.add(p);
		}
		return out;
	}

	/** Say something to whoever is close enough to hear (48 blocks), not the whole server. */
	void sayNear(String text) {
		Component line = Component.literal("<" + c.name + "> " + text);
		for (ServerPlayer p : listeners(48)) p.sendSystemMessage(line);
	}

	// ----------------------------------------------------------------------------- each decision
	void tick(boolean fighting) {
		if (c.player == null || c.inArena || !c.mod.config.chat) return;
		long now = now();
		if (nextRemark < 0) {                                          // just arrived: a minute before it says much
			nextRemark = now + (FAST ? 200 : 1200);
			nextXenTalk = now + (FAST ? 100 : 600);
		}
		playLines(now);
		if (!c.mod.config.talk || fighting) return;
		if (asked != null && now > askedUntil) asked = null;          // nobody answered: never mind
		if (now - lastGreetCheck >= 20 && !c.known.isEmpty()) {       // once a second is enough to notice someone
			lastGreetCheck = now;
			greet(now);
		}
		if (c.mod.config.talkToXens && now >= nextXenTalk && lines.isEmpty()) chatWithXen(now);
		if (now >= nextRemark && (now - anyoneSpoke > 200 || FAST) && lines.isEmpty() && !listeners(48).isEmpty()) {
			nextRemark = now + (FAST ? 200 : (long) (2400 * (1.6f - c.personality.chattiness) * (0.7f + 0.6f * random.nextFloat())));
			String r = random.nextFloat() < (FAST ? 0.7f : 0.35f) ? question() : null;
			if (r == null) r = remark();
			if (r != null && r.equals(lastRemark)) r = null;                  // not the same thing twice in a row
			if (r != null) {
				lastRemark = r;
				anyoneSpoke = now;
				sayNear(r);
			}
		}
	}

	/** Someone it knows comes close: it says hi (once every ten minutes or so). */
	private void greet(long now) {
		Set<UUID> close = new HashSet<>();
		for (ServerPlayer p : listeners(12)) {
			UUID u = p.getUUID();
			if (!c.known.contains(u)) continue;
			close.add(u);
			if (near.contains(u) || now - greeted.getOrDefault(u, -1_000_000L) < 12_000) continue;
			greeted.put(u, now);
			String n = p.getName().getString();
			if (c.trust(u) >= 0.3f) c.antics.wave(p);
			boolean friend = c.trust(u) >= 0.5f;
			sayNear(c.trust(u) < -0.2f ? pick("Oh. It's you.", "Hello, " + n + ".", "You again.", "...", "Stay back, " + n + ".", "Oh no, it's " + n + "!")
					: friend ? pick("Hi " + n + "! I missed you!", "Hello, " + n + ". Good to see you.", "Oh, it's you, " + n + ".",
							"H-hi " + n + "...", "There you are, " + n + "!", "Hiii " + n + "!")
					: pick("Hi " + n + "!", "Hello, " + n + ".", "Hey.", "Oh, h-hi.", "Hey " + n + "!", "Howdy, " + n + "!"));
		}
		near.clear();
		near.addAll(close);
	}

	// ------------------------------------------------------------------------------- remarks
	/** When it last made each kind of remark: the same kind at most every five minutes. */
	private final Map<String, Long> remarked = new HashMap<>();

	/** Something that's on its mind, true for it right now (null: nothing to say). */
	private String remark() {
		Map<String, String> options = new java.util.LinkedHashMap<>();       // kind of remark -> what it says
		var items = c.items();
		var goals = c.goals;
		int food = c.player.getFoodData().getFoodLevel();
		if (food <= 8) options.put("hungry", items.getOrDefault("food", 0) > 0 ? pick("Snack time soon!", "I should eat something.", "I'm starving.",
				"I'm a little hungry...", "I need food.", "My tummy is growling!")
				: pick("I'm so hungry. Does anyone have food?", "I'm hungry and I have no food.", "No food, of course.",
						"Um... does anyone have some food?", "I need to find food. Now.", "I could eat a whole cow!"));
		if (c.player.getHealth() < 8) options.put("hurt", pick("Ow, I'm hurt. I need to rest.", "I'm hurt. I'll be careful.", "Everything hurts.",
				"I don't feel so good...", "Just a scratch. Mostly.", "I'm leaking hearts!"));
		if (goals.dream != null) {
			float pr = goals.progress();
			if (pr > 0.1f && pr < 0.97f) options.put("dream", String.format(Locale.ROOT, pick("I'm %2$.0f%% of the way to my dream: to %1$s!",
					"My dream is to %s. I'm about %.0f%% there.", "I still want to %s. Only %.0f%% done.", "I'd like to %s someday... I'm %.0f%% there.",
					"I will %s. I'm %.0f%% there already.", "Dream check: %s, %.0f%% done!"), goals.dream.what, 100 * pr));
			else if (pr <= 0.1f) options.put("dream", pick("Someday I want to ", "I've been thinking: I'd like to ", "What I really want is to ",
					"Can I tell you something? I want to ", "Mark my words: I will ", "Big plans: I'm going to ") + goals.dream.what + ".");
		}
		if (goals.current != null) {
			options.put("goal " + goals.current, "I want to " + goals.current.what + ", " + xen.mod.talk.Chat.firstPerson("because " + goals.current.why) + ".");
		}
		if (c.crafter.pickTier() == 0 && items.getOrDefault("log", 0) < 3) {
			options.put("pickaxe", pick("I need some wood to make a pickaxe.", "First thing: wood, for a pickaxe.", "No pickaxe. I need wood.",
					"I don't have a pickaxe yet...", "I need wood for tools.", "No pickaxe, no fun. Wood first!"));
		}
		int logs = items.getOrDefault("log", 0);
		for (int m : new int[] {16, 32, 64}) {
			if (logs >= m && !said.contains("logs " + m)) options.put("logs " + m, "I have " + logs + " logs now!");
		}
		if (c.trader.villagerNear() != null) {
			options.put("villager", pick("Look, a villager! Maybe they want to trade.", "There's a villager. We could trade.", "A villager. Hope they have good deals.",
					"Oh, a villager...", "A villager! Let's see what they sell.", "Hrrm! A villager!"));
		}
		if (c.player.level().isRaining() && c.player.level().canSeeSky(c.player.blockPosition().above())) {
			options.put("rain", pick("It's raining! I love the sound.", "Rain. The plants will like it.", "Rain. Great.", "I'm getting wet...",
					"A little rain never hurt anyone.", "Splish splash!"));
		}
		options.put("idle", pick("What a nice day!", "It's peaceful here.", "Hmph.", "...", "I'm ready for anything.", "I wonder what's over that hill."));
		long now = now();
		List<String> fresh = new ArrayList<>();
		for (String k : options.keySet()) {
			long gap = k.equals("dream") ? 24_000 : k.equals("villager") ? 12_000 : 6000;   // its dream: once in a while
			if (now - remarked.getOrDefault(k, -1_000_000L) >= (FAST ? 400 : gap)) fresh.add(k);
		}
		if (fresh.size() > 1) fresh.remove("idle");                       // small talk only when there's nothing else
		if (fresh.isEmpty() || fresh.get(0).equals("idle") && random.nextFloat() > 0.3f) return null;
		String k = fresh.get(random.nextInt(fresh.size()));
		remarked.put(k, now);
		if (k.startsWith("logs")) said.add(k);
		return options.get(k);
	}

	/** A question for its friend nearby, with a yes or no answer it will act on (null: nothing to ask). */
	private String question() {
		if (asked != null) return null;
		ServerPlayer friend = null;
		for (ServerPlayer p : listeners(16)) {
			if (p.getUUID().equals(c.owner) || p.getUUID().equals(c.leader)) friend = p;
		}
		var items = c.items();
		int blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
		var level = c.player.level();
		String q;
		if (friend != null) {
			if (level.isDarkOutside() && level.canSeeSky(c.player.blockPosition().above()) && blocks >= 10 && !c.chores.busy()
					&& (q = ask("shelter", friend, null, 0, "It's dark. Should I build us a shelter?")) != null) return q;
			if (items.getOrDefault("food", 0) >= 8 && (q = ask("give", friend, "food", 4, "I have lots of food. Want some?")) != null) return q;
			if (items.getOrDefault("log", 0) >= 24 && (q = ask("give", friend, "log", 8, "I have lots of wood. Want some?")) != null) return q;
			if (c.mode == Companion.Mode.FOLLOW && c.personality.curiosity > 0.6f && !level.isDarkOutside()
					&& (q = ask("explore", friend, null, 0, pick("Can I go exploring for a bit?", "May I look around on my own for a while?",
							"Can I go now? I'm bored.", "Um... could I go explore a little?", "I'm going to scout ahead, okay?", "Can I go on an adventure?"))) != null) {
				return q;
			}
			if (!c.mod.config.wants && c.crafter.pickTier() == 0 && items.getOrDefault("log", 0) < 3 && !c.chores.busy()   // (with its own goals it just goes)
					&& (q = ask("wood", friend, null, 0, "I need a pickaxe. Should I go get some wood?")) != null) return q;
		}
		if (c.mod.config.trading) {
			for (ServerPlayer p : listeners(10)) {
				if (p.getUUID().equals(c.owner) || !c.known.contains(p.getUUID()) || c.trust(p.getUUID()) < 0) continue;
				String n = p.getName().getString();
				if (c.trader.hasSomethingToTrade() && (q = ask("trade", p, null, 0, pick("Hey " + n + ", want to trade?",
						"Would you like to trade, " + n + "?", "Want to trade? I won't give it away cheap.",
						"Um, w-would you like to trade?", n + "! Let's make a deal.", "Trade? Trade! Trade?"))) != null) return q;
			}
		}
		return null;
	}

	private String ask(String what, ServerPlayer who, String thing, int count, String text) {
		String kind = what + (thing == null ? "" : " " + thing);
		if (now() - askedAt.getOrDefault(kind, -1_000_000L) < 6000) return null;
		askedAt.put(kind, now());
		asked = what;
		askedWho = who.getUUID();
		askedThing = thing;
		askedCount = count;
		askedUntil = now() + 600;                                        // 30 seconds to answer
		return text;
	}

	/** A yes or no from the one it asked? Then it does what it said (true), or it wasn't an answer (false). */
	boolean answered(ServerPlayer from, String words) {
		if (asked == null || c.player == null || now() > askedUntil || !from.getUUID().equals(askedWho)) return false;
		boolean yes = YES.matcher(words).lookingAt(), no = !yes && NO.matcher(words).lookingAt();
		if (!yes && !no) return false;
		if (words.trim().contains(" ") && !xen.mod.talk.Chat.understand(words, c.name).intent().equals("chat")) {
			return false;                                              // "please give me 16 cobblestone" is a new request, not a yes
		}
		String q = asked;
		asked = null;
		if (no) {
			c.say(switch (q) {
				case "explore" -> "Okay, I'll stay with you.";
				case "trade" -> "Okay, maybe later.";
				default -> pick("Okay!", "Alright.", "Fine.", "O-okay.", "Your call.", "Okey-dokey!");
			});
			return true;
		}
		c.goals.drop();
		String plan = switch (q) {
			case "shelter" -> {
				c.chores.cancel();
				yield c.chores.shelter();
			}
			case "give" -> c.chores.give(from, askedThing, askedCount);
			case "wood" -> c.chores.gather("wood", 4);
			case "explore" -> {
				c.chores.cancel();
				c.mode = Companion.Mode.FREE;
				yield "You will go exploring on your own.";
			}
			default -> null;
		};
		if (q.equals("trade")) {
			String reply = c.trader.talk(from, "let's trade");
			if (reply != null) c.say(reply);
			return true;
		}
		if (plan != null) {
			c.lastThought = "Asked " + from.getName().getString() + ", who said yes: " + plan;
			c.say(xen.mod.talk.Chat.plainly("Plan: " + plan, ""));
		}
		return true;
	}

	/** Waiting for an answer from this player? (Then a plain "yes" in chat is for it.) */
	boolean waitingFor(UUID who) {
		return asked != null && c.player != null && now() <= askedUntil && who.equals(askedWho);
	}

	// --------------------------------------------------------------------------- Xen with Xen
	private void chatWithXen(long now) {
		nextXenTalk = now + 200;
		if (conversations >= 2) return;                                  // two chats at a time, for all Xens together
		for (Companion o : c.mod.companions) {
			if (o == c || o.player() == null || o.inArena || o.player().level() != c.player.level()) continue;
			if (o.player().distanceTo(c.player) > 12 || o.talker.busy() || !c.mod.config.talk) continue;
			if (now - lastWith.getOrDefault(o.name, -1_000_000L) < 3600) continue;
			lastWith.put(o.name, now);
			o.talker.lastWith.put(c.name, now);
			o.talker.nextXenTalk = now + 1200;
			nextXenTalk = now + 1200;
			converse(o, now);
			return;
		}
	}

	/** A short chat: hello, what they're up to, a tip (which the other one learns), a thank-you. */
	private void converse(Companion o, long now) {
		UUID them = o.player().getUUID(), me = c.player.getUUID();
		boolean met = c.trust.containsKey(them);
		long t = now;
		if (met) {
			line(c, pick("Hey " + o.name + "!", "Hello again, " + o.name + ".", "Oh, " + o.name + ".", "H-hi " + o.name + ".",
					o.name + "! Good to see you.", o.name + "! My favorite!"), t, null);
		} else {
			line(c, "Hi! I'm " + c.name + ". Who are you?", t, null);
			line(o, "I'm " + o.name + ". Nice to meet you, " + c.name + "!", t += 50, null);
		}
		if (c.goals.dream != null && random.nextBoolean()) {
			line(c, "I'm trying to " + c.goals.dream.what + ".", t += 60, null);
			if (o.goals.dream != null) {
				line(o, (o.goals.dream == c.goals.dream ? "Me too! " : "Cool! ") + "I want to " + o.goals.dream.what + ".", t += 50, null);
			}
		}
		String[] tip = tip(o);
		if (tip != null) {
			int[] where = {Integer.parseInt(tip[1]), Integer.parseInt(tip[2]), Integer.parseInt(tip[3]), Integer.parseInt(tip[4])};
			line(c, tip[0], t += 60, () -> learn(o, where));
			line(o, "Thanks! I'll remember that.", t += 50, null);
		} else if (o.goals.current != null) {
			line(o, "I'm off to " + o.goals.current.what + ".", t += 60, null);
			line(c, pick("Good luck!", "Take care.", "Don't get lost.", "B-be careful.", "Go get it!", "Bring me a souvenir!"), t += 50, null);
		}
		line(c, pick("See you around!", "Bye for now.", "Later.", "Bye...", "See you!", "Toodles!"), t + 60, () -> {
			c.trust(them, 0.1f);                                         // a nice chat: they like each other a bit more
			o.trust(me, 0.1f);
		});
		conversations++;
	}

	private void line(Companion who, String text, long at, Runnable then) {
		lines.add(new Object[] {who, text, at, then});
	}

	private void playLines(long now) {
		while (!lines.isEmpty() && (long) lines.peek()[2] <= now) {
			Object[] l = lines.poll();
			Companion who = (Companion) l[0];
			if (who.player() != null && !who.inArena) who.talker.sayNear((String) l[1]);
			if (l[3] != null) ((Runnable) l[3]).run();
			if (lines.isEmpty()) conversations = Math.max(0, conversations - 1);
		}
	}

	/** Something it knows the other Xen would like to know: {words, x, y, z, what}. Only what it has seen. */
	private String[] tip(Companion o) {
		int[][] wants = o.goals.dream == Goals.Long.TREASURE ? new int[][] {{Blocks.DIAMOND}, {Blocks.IRON}, {Blocks.COAL}, {Blocks.LOG}}
				: new int[][] {{Blocks.IRON}, {Blocks.DIAMOND}, {Blocks.COAL}, {Blocks.LOG}};
		for (int[] cat : wants) {
			int[] k = c.senses.nearestKnown(cat, 0.25, java.util.Set.of());
			if (k == null) continue;
			var op = o.player().blockPosition();
			int dx = k[0] - op.getX(), dz = k[2] - op.getZ();
			double d = Math.hypot(dx, dz);
			if (d < 3) continue;
			String dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
			String what = k[4] == Blocks.LOG ? "trees" : Blocks.NAMES[k[4]];
			String text = String.format(Locale.ROOT, "I saw %s about %.0f blocks %s of here%s.", what, d, dir,
					k[1] < op.getY() - 3 ? ", down low" : "");
			return new String[] {text, "" + k[0], "" + k[1], "" + k[2], "" + k[4]};
		}
		return null;
	}

	/** The other Xen now knows where that is, a little less sure than if it had seen it itself. */
	private static void learn(Companion o, int[] w) {
		Perception.Sight s = o.senses.last;
		if (s == null) return;
		o.senses.beliefs.see(w[0], w[1], w[2], w[3], s.t - o.senses.beliefs.halfLife / 2);
	}

	// ------------------------------------------------------------------------ plain questions
	private static final Pattern DOING = Pattern.compile("^(what are you doing|what('?s| is) up|whatcha doing|what are you up to|wyd)\\b");
	private static final Pattern HOW = Pattern.compile("^(how are you|how do you feel|how'?s it going|are you ok(ay)?|you ok)\\b");
	private static final Pattern WANT = Pattern.compile("^(what do you want|what('?s| is) your (goal|dream|plan)|what are your (goals|plans|dreams))\\b");
	private static final Pattern HAVE = Pattern.compile("^(what do you have|what('?s| is) in your (bag|inventory|pockets?)|what are you carrying)\\b");
	private static final Pattern WHO = Pattern.compile("^(who are you|what('?s| is) your name|tell me about yourself)\\b");

	/**
	 * Everyday questions it answers straight from what it knows (no language model, so nothing made up). Null: not
	 * one of those.
	 */
	String answer(String words) {
		if (DOING.matcher(words).lookingAt()) {
			String now = c.goals.instant.isEmpty() ? "looking around" : c.goals.instant;
			return "I'm " + xen.mod.talk.Chat.firstPerson(now) + (c.goals.current != null ? ", because I want to " + c.goals.current.what : "") + ".";
		}
		if (HOW.matcher(words).lookingAt()) {
			float h = c.player.getHealth();
			int f = c.player.getFoodData().getFoodLevel();
			return "I feel " + c.emotions.mood() + (h < 8 ? ", and I'm hurt" : "") + (f < 8 ? ", and I'm hungry" : "") + ".";
		}
		if (WANT.matcher(words).lookingAt()) {
			String s = c.goals.dream != null ? String.format(Locale.ROOT, "My dream is to %s (%.0f%% done).", c.goals.dream.what, 100 * c.goals.progress()) : "";
			return (c.goals.current != null ? "Right now I want to " + c.goals.current.what + ". " : "") + (s.isEmpty() ? "I'm still figuring that out." : s);
		}
		if (HAVE.matcher(words).lookingAt()) {
			List<String> out = new ArrayList<>();
			for (var e : c.items().entrySet()) {
				if (out.size() == 8) break;
				out.add(e.getValue() + " " + Chores.named(e.getKey(), e.getValue()));
			}
			return out.isEmpty() ? "Nothing at all." : "I have " + String.join(", ", out) + ".";
		}
		if (WHO.matcher(words).lookingAt()) {
			String told = c.instructions(), first = told.isEmpty() ? "" : told.split("(?<=[.!?])\\s+")[0];
			return "I'm " + c.name + ". I'm " + c.personality.describe() + (c.goals.dream != null ? ", and I want to " + c.goals.dream.what : "") + "."
					+ (first.isEmpty() ? "" : " " + xen.mod.talk.Chat.firstPerson(first));
		}
		return null;
	}
}
