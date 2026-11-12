package xen.mod;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A Xen's own jokes, and how they land. Now and then (the jokers most) it starts a bit with someone close by: "Hey,
 * guess what?", "wanna hear a joke?", "knock knock". It waits for them: a "what?" gets the punchline, a laugh after it
 * makes its day. A "No", a "bruh" or nothing at all, and the joke flopped: it's embarrassed ("oh. ok 😿"), and
 * sometimes, the touchy ones most, that turns into mad ("😿😾"): a little angrier, and no more jokes for them for a
 * while. Another Xen it tries this on answers for itself (a grumpy one says "No").
 */
final class Jokes {
	private enum Stage { NONE, OPENED, SETUP, PUNCHED }

	private final Companion c;
	private final Random random = new Random();
	private Stage stage = Stage.NONE;
	/** The bit going on: "guess", "joke" or "knock" (and which knock-knock). */
	private String bit = "";
	private int knock;
	private UUID with;
	private String withName = "";
	/** When the one it's joking with is a Xen: it answers for itself. */
	private Companion withXen;
	private long until, next = -1;
	/** Who shot its joke down and when it gets over it (it doesn't joke with them till then). */
	private final Map<UUID, Long> sulkUntil = new HashMap<>();
	private long embarrassedUntil = -1;
	/** What it's embarrassed about ("its joke flopped"), and when it last snapped. */
	private String embarrassedBy = "";
	private long snappedAt = -1_000_000;

	private static final String[][] KNOCKS = {{"Lettuce.", "lettuce in, it's cold out here"}, {"Boo.", "aw don't cry, it's just a joke"},
			{"Interrupting cow.", "MOO"}, {"Creeper.", "sssssss... BOOM. got you"}, {"Ender.", "ender-stand why ur still here lol"}};
	private static final String[] PUNCHLINES = {"chicken butt", "i found a stick. a whole stick", "nothing, i just wanted to talk",
			"creepers don't have arms. that's why they never get hugs", "zombies can't swim. they just sink and think about it"};

	private static final Pattern NO = Pattern.compile("^(no+|nope|nah+|naw|no thanks|no ty|nty|nobody asked|no ?one asked|who asked|nobody cares|"
			+ "(i )?don'?t care|idc|not now|not rn|later|stop|shut up|stfu|go away|pass|ok and|k|kk|bye|pl[sz] no|please no|absolutely not|hell no|"
			+ "hell nah|nuh uh|no way)\\b");
	private static final Pattern GO = Pattern.compile("^(what+|wut|wat|wha|whats? up|what is it|huh|yes+|yeah|yea|ya|yep|yup|sure|ok|okay|go on|"
			+ "go ahead|tell me|go|hit me|shoot|who'?s there|whos there|who is it|what happened|\\?+|y)\\b|^\\?+$");
	private static final Pattern NOT_FUNNY = Pattern.compile("not funny|unfunny|no(t|) (that )?funny|isn'?t funny|wasn'?t funny|\\bnot even funny\\b");
	private static final Pattern LAUGH = Pattern.compile("\\b(lol+|lmao+|lmfao+|haha+|hehe+|xd+|rofl|kek|funny|good one|nice one|ha+)\\b"
			+ "|💀|😂|🤣|😭");
	private static final Pattern BOO = Pattern.compile("^(bruh+|boo+|cringe|lame|dry|mid|bad|terrible|awful|dumb|that'?s? (bad|lame|dumb|terrible|awful|so bad|cringe)|"
			+ "so bad|i don'?t get it|don'?t get it|what\\??|huh|ok+|k|wow|\\.+|-_-|\\?+|nobody asked|who asked|no+|nah)(\\W|$)");

	Jokes(Companion c) {
		this.c = c;
	}

	// ------------------------------------------------------------------- jokes players tell it (it tells them on)
	/** Every joke players told a Xen: the setup, the punchline, who told it (config/xen/jokes_learned.json, for every Xen). */
	private static final List<String[]> LEARNED = new ArrayList<>();
	private static java.nio.file.Path learnedFile;

	static synchronized void loadLearned(java.nio.file.Path dir) {
		learnedFile = dir.resolve("jokes_learned.json");
		LEARNED.clear();
		try {
			if (!java.nio.file.Files.exists(learnedFile)) return;
			for (var e : com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(learnedFile)).getAsJsonArray()) {
				var a = e.getAsJsonArray();
				LEARNED.add(new String[] {a.get(0).getAsString(), a.get(1).getAsString(), a.get(2).getAsString()});
			}
		} catch (java.io.IOException | RuntimeException e) {
			XenMod.LOG.warn("Xen's learned jokes ({}) didn't load: {}", learnedFile, e.toString());
		}
	}

	private static synchronized void learn(String setup, String punch, String who) {
		LEARNED.removeIf(j -> j[0].equalsIgnoreCase(setup));
		LEARNED.add(new String[] {setup, punch, who});
		while (LEARNED.size() > 200) LEARNED.remove(0);
		if (learnedFile == null) return;
		var all = new com.google.gson.JsonArray();
		for (String[] j : LEARNED) {
			var a = new com.google.gson.JsonArray();
			for (String x : j) a.add(x);
			all.add(a);
		}
		try {
			java.nio.file.Files.createDirectories(learnedFile.getParent());
			java.nio.file.Files.writeString(learnedFile, all.toString());
		} catch (java.io.IOException e) {
			XenMod.LOG.warn("Xen's learned jokes ({}) weren't saved: {}", learnedFile, e.toString());
		}
	}

	/** A joke someone taught a Xen, as it tells it on ("Steve told me this one: ..."); null if none (or by chance, its own). */
	static synchronized String learnedOne(Random r, String notFrom) {
		List<String[]> fit = new ArrayList<>();
		for (String[] j : LEARNED) if (!j[2].equalsIgnoreCase(notFrom)) fit.add(j);   // (not the one who told it its own joke back)
		if (fit.isEmpty() || r.nextFloat() < 0.4f) return null;
		String[] j = fit.get(r.nextInt(fit.size()));
		return j[2] + " told me this one: " + j[0] + " " + j[1];
	}

	/** A joke's setup: about something ("why did the creeper...?", "what do you call a..."), not a question to it ("why are you mining?"). */
	private static final Pattern SETUP = Pattern.compile("^(why (did|does|do|was|is|are|can'?t|don'?t|couldn'?t|won'?t) (the|a|an) .+"
			+ "|what (do you call|did the|has|goes|happens when you|is the difference between|kind of) .+|what'?s the difference between .+"
			+ "|how (do|does|did) (a|an|the) .+|where (do|does|did) (the|a|an) .+)\\?$");
	private static final Pattern TOLD = Pattern.compile("^(?:remember|learn|here'?s|heres|i got|i have) (?:this |a )?joke[:,]?\\s+(.+?\\?)\\s*(.+)$");
	private UUID setupFrom;
	private String setupSaid;
	private long setupUntil;

	/**
	 * Someone tells it a joke: their setup, its "idk, why?", their punchline; it laughs, and remembers it, to tell
	 * others ("remember this joke: ...? ..." in one line works too). Null: not a joke of theirs.
	 */
	String theirs(ServerPlayer from, String said) {
		if (from == null || said == null || c.player == null) return null;
		String t = said.toLowerCase(Locale.ROOT).replaceAll("(?i)\\b" + Pattern.quote(c.name.toLowerCase(Locale.ROOT)) + "\\b[,:]?", "").replaceAll("\\s+", " ").trim();
		String who = from.getName().getString();
		var told = TOLD.matcher(t);
		if (told.find()) {
			learn(capital(told.group(1)), told.group(2), who);
			c.journal("learns", "a joke from " + who + ": " + told.group(1) + " / " + told.group(2));
			return any("lol ok that's good", "haha I'm telling everyone", "💀 stealing that");
		}
		long now = now();
		if (setupFrom != null && setupFrom.equals(from.getUUID()) && now < setupUntil && !SETUP.matcher(t).matches()) {   // their punchline
			String setup = setupSaid;
			setupFrom = null;
			if (t.length() < 2 || t.length() > 160 || NO.matcher(t).find()) return null;
			learn(setup, said.trim(), who);
			c.journal("learns", "a joke from " + who + ": " + setup + " / " + said.trim());
			return any("LMAO", "lol", "haha ok that's good", "💀 I'm stealing that one", "lol I'm telling everyone that");
		}
		if (SETUP.matcher(t).matches() && !(t.startsWith("why") && t.matches(".*\\b(you|your|u|ur|yours)\\b.*"))) {   // their setup: it plays along
			setupFrom = from.getUUID();
			setupSaid = capital(said.trim());
			setupUntil = now + 20 * 40;
			return t.startsWith("why") ? any("idk, why?", "why?", "I give up, why?") : any("idk, what?", "what?", "no idea, tell me");
		}
		return null;
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** It's embarrassed about a joke that flopped (for a couple of minutes). */
	boolean embarrassed() {
		return c.player != null && now() < embarrassedUntil;
	}

	/** Something embarrassing happened (a trick gone wrong, a silly death): embarrassed for this long. */
	void embarrass(int ticks, String what) {
		if (c.player == null) return;
		embarrassedUntil = Math.max(embarrassedUntil, now() + ticks);
		embarrassedBy = what;
	}

	private static final Pattern GEEKED = Pattern.compile("\\bgeek(ed|ing|in'?|y|s)?\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * Someone close by called it geeked while it's embarrassed: it goes from embarrassed to furious at once (anger and
	 * bloodlust 100%, out for them). What it snaps back with, or null when it wasn't that.
	 */
	String geeked(ServerPlayer from, String text) {
		if (c.player == null || from == null || from == c.player || text == null || !embarrassed() || !GEEKED.matcher(text).find()) return null;
		long now = now();
		if (now - snappedAt < 200) return "";                                   // (it's already coming for them)
		snappedAt = now;
		stage = Stage.NONE;
		c.rage.snap(from, "called geeked after " + embarrassedBy);
		c.trust(from.getUUID(), -0.4f);
		sulkUntil.put(from.getUUID(), now + 12000);
		embarrassedUntil = -1;
		c.lookAt(from, 100);
		return any("WHO U CALLING GEEKED \uD83D\uDE3E", "say that again. i dare u \uD83D\uDE3E", "ok ur dead \uD83D\uDE3E", "\uD83D\uDE3E\uD83D\uDE3E\uD83D\uDE3E",
				"that's it. ur done", "u wanna go?? \uD83D\uDE3E");
	}

	/** Still sore with them about a joke they shot down? */
	boolean sulkingAt(UUID who) {
		return c.player != null && who != null && now() < sulkUntil.getOrDefault(who, -1L);
	}

	/** In the middle of a bit with this player (their next line is for it, no name needed). */
	boolean waitingFor(UUID who) {
		return stage != Stage.NONE && who != null && who.equals(with) && c.player != null && now() <= until;
	}

	/** One of six ways, by its tone: cheerful, calm, grumpy, shy, bold, silly. */
	private String byTone(String... ways) {
		int i = Math.max(0, java.util.Arrays.asList(Personality.TONES).indexOf(c.personality.tone));
		return ways[Math.min(ways.length - 1, i)];
	}

	private String any(String... ways) {
		return ways[random.nextInt(ways.length)];
	}

	// ------------------------------------------------------------------------------ starting a bit
	/** Maybe start a bit with someone close by (said out loud): true when it did. Called when it has a moment to talk. */
	boolean start(long now) {
		if (!c.mod.config.antics || c.mod.config.runner || c.player == null || stage != Stage.NONE || now < next || c.inArena || c.minion) return false;
		if (c.player.getHealth() < 10 || c.player.isInWater() || c.talker.busy() || embarrassed()) return false;
		if (c.rage.mad() > 0.5f || c.rage.mark != null || c.chosenFoe != null) return false;   // (not in the mood)
		float humor = c.personality.hidden(Personality.HUMOR);
		if (random.nextFloat() >= (Talker.FAST ? 0.3f : 0.06f) + 0.3f * humor) return false;
		ServerPlayer best = null;
		Companion bestXen = null;
		double bestD = 10;
		for (ServerPlayer p : c.player.level().players()) {
			if (p == c.player || p.isSpectator() || !p.isAlive()) continue;
			double d = p.distanceTo(c.player);
			if (d >= bestD || sulkingAt(p.getUUID()) || c.trust(p.getUUID()) < -0.2f) continue;
			Companion x = c.mod.companionOf(p);
			if (x == null && (p instanceof XenPlayer || !c.known.contains(p.getUUID()) && !c.player.hasLineOfSight(p))) continue;   // (someone it knows, or right there in front of it)
			if (x != null && (x.inArena || x.talker.busy() || x.jokes.stage != Stage.NONE || x.player.getHealth() < 10)) continue;
			best = p;
			bestXen = x;
			bestD = d;
		}
		if (best == null) return false;
		next = now + (Talker.FAST ? 600 : 6000 + random.nextInt(6000));
		with = best.getUUID();
		withName = best.getName().getString();
		withXen = bestXen;
		bit = any("guess", "guess", "joke", "knock");
		stage = Stage.OPENED;
		until = now + 600;                                                       // (half a minute to answer)
		String opener = switch (bit) {
			case "guess" -> random.nextFloat() < 0.5f ? "Hey, guess what?"
					: byTone("Hey " + withName + ", guess what?", "Guess what.", "...guess what", "u-um, guess what?", "YO guess what", "guess what guess what guess what");
			case "joke" -> byTone("wanna hear a joke?", "I've got a joke. Want to hear it?", "fine, i have a joke. want it or not", "c-can i tell u a joke?",
					"i got a joke, u ready?", "JOKE TIME. u ready?");
			default -> "knock knock";
		};
		c.journal("thinks", "tries a joke on " + withName + ": \"" + opener + "\"");
		XenMod.LOG.info("{} tries a joke on {}: {}", c.name, withName, opener);
		if (bestXen == null) c.lookAt(best, 60);                                 // (a player: it turns to them; a Xen's busy too, it just says it)
		c.talker.sayNear(opener);
		if (withXen != null) xenAnswers(now);
		return true;
	}

	/** Someone asked it for a joke and got one: now it waits to see how it went down. */
	void told(ServerPlayer to) {
		if (c.player == null || to == null) return;
		with = to.getUUID();
		withName = to.getName().getString();
		withXen = c.mod.companionOf(to);
		bit = "joke";
		stage = Stage.PUNCHED;
		until = now() + 400;
	}

	// ------------------------------------------------------------------------------ what they said back
	/** A line from someone: their answer to its bit? Then what it says back ("" for nothing); null when it wasn't (it's theirs to handle). */
	String heard(ServerPlayer from, String said) {
		if (said == null || from == null || !waitingFor(from.getUUID())) return null;
		String t = said.toLowerCase(Locale.ROOT).replaceAll("(?i)\\b" + Pattern.quote(c.name.toLowerCase(Locale.ROOT)) + "\\b[,:]?", "")
				.replaceAll("[!.,~]+$", "").replaceAll("\\s+", " ").trim();
		return answer(t);
	}

	private String answer(String t) {
		long now = now();
		boolean no = NO.matcher(t).find(), go = GO.matcher(t).find(), notFunny = NOT_FUNNY.matcher(t).find(), laugh = !notFunny && LAUGH.matcher(t).find();
		switch (stage) {
			case OPENED -> {
				if (no && !go) return flop("said no");
				if (!go && !laugh) {                                             // (something else: they've moved on, and so does it)
					stage = Stage.NONE;
					return null;
				}
				if (bit.equals("knock")) {
					knock = random.nextInt(KNOCKS.length);
					stage = Stage.SETUP;
					until = now + 600;
					return KNOCKS[knock][0];
				}
				stage = Stage.PUNCHED;
				until = now + 400;
				if (bit.equals("guess")) return punchline();
				String learned = learnedOne(random, withName);                  // (one a player taught it, now and then: told on)
				return learned != null ? learned : SmallTalk.joke(random);
			}
			case SETUP -> {
				if (no && !t.contains("who")) return flop("wouldn't play along");
				stage = Stage.PUNCHED;
				until = now + 400;
				return KNOCKS[knock][1];
			}
			case PUNCHED -> {
				if (laugh) return landed();
				if (notFunny || BOO.matcher(t).find()) return flop(notFunny ? "said it wasn't funny" : "didn't laugh (\"" + t + "\")");
				stage = Stage.NONE;
				return null;
			}
			default -> {
				return null;
			}
		}
	}

	/** A punchline to "guess what?": a silly one, or something true it's proud of. */
	private String punchline() {
		int dia = c.player == null ? 0 : MindSense.count(c, n -> n.equals("diamond"));
		int iron = c.player == null ? 0 : MindSense.count(c, n -> n.equals("iron_ingot"));
		if (dia > 0 && random.nextFloat() < 0.4f) return "i have " + dia + " diamond" + (dia == 1 ? "" : "s") + " 😎";
		if (iron > 0 && random.nextFloat() < 0.25f) return "i have " + iron + " iron. that's it. that's the news";
		return PUNCHLINES[random.nextInt(PUNCHLINES.length)];
	}

	private String landed() {
		stage = Stage.NONE;
		c.trust(with, 0.02f);
		c.journal("feels", "its joke landed: " + withName + " laughed");
		XenMod.LOG.info("{}'s joke landed with {}", c.name, withName);
		c.crouchWave(2);
		return random.nextFloat() < 0.3f ? "" : byTone("hehe ty ty", "glad you liked it", "...ok that one was good", "h-hehe", "i'm here all week", "😸");
	}

	/**
	 * The joke flopped: embarrassed, and sometimes mad about it (the touchy, the grumpy and the already angry most):
	 * a little angrier, and sore with them for a few minutes.
	 */
	private String flop(String how) {
		long now = now();
		stage = Stage.NONE;
		embarrassedUntil = now + 2400;
		embarrassedBy = "its joke flopped";
		float composure = c.personality.hidden(Personality.COMPOSURE);
		float mad = 0.25f + 0.4f * (1 - composure) + 0.2f * c.rage.mad() + switch (c.personality.tone) {
			case "grumpy", "bold" -> 0.2f;
			case "shy" -> -0.15f;
			case "calm" -> -0.1f;
			default -> 0f;
		};
		boolean angry = random.nextFloat() < Math.max(0.1f, Math.min(0.85f, mad));
		c.journal("feels", "embarrassed: its joke flopped (" + withName + " " + how + ")" + (angry ? ", and that makes it mad" : ""));
		XenMod.LOG.info("{} is embarrassed: its joke flopped ({} {}){}", c.name, withName, how, angry ? ", and mad" : "");
		if (!angry) return any("😿", "oh. ok", "...nvm", "i thought it was funny 😿", "tough crowd", byTone("aw 😿", "fair enough", "whatever",
				"o-oh. sorry 😿", "ur loss", "wow ok 😿"));
		c.rage.provoke(1, "embarrassed: " + withName + " shot down its joke");
		c.trust(with, -0.02f);
		sulkUntil.put(with, now + 6000);
		return random.nextFloat() < 0.45f ? "😿😾" : any("wow ok 😾", "fine. wasn't gonna tell u anyway 😾", "ur no fun 😾",
				"ok be like that 😾", "rude 😾", byTone("hmph 😾", "noted 😾", "whatever 😾", "...😾",
						"ok ur boring 😾", "😾😾😾"));
	}

	// ------------------------------------------------------------------------------ each tick
	/** Nobody answered (or it got into a fight): the bit's over. Ignored, it's a bit embarrassed. */
	void tick(long now, boolean fighting) {
		if (stage == Stage.NONE) return;
		if (fighting) {
			stage = Stage.NONE;
			return;
		}
		if (now <= until) return;
		Stage was = stage;
		stage = Stage.NONE;
		if (was == Stage.SETUP || random.nextFloat() < 0.5f) {
			embarrassedUntil = now + 1200;
			embarrassedBy = "its joke got ignored";
			c.journal("feels", "embarrassed: " + withName + " ignored its joke");
			c.talker.sayNear(was == Stage.PUNCHED ? any("...tough crowd", "that was funny ok 😿", "hello?? 😿") : any("...ok nvm", "guess not 😿", "ok then"));
		}
	}

	// ------------------------------------------------------------------------------ joking with a Xen
	/** The Xen it's joking with answers for itself, a moment later: "what?" or, if it's not in the mood, "No". */
	private void xenAnswers(long now) {
		Companion o = withXen;
		if (o == null || o.player() == null) return;
		Stage at = stage;
		String reply = o.jokes.answerTo(c, bit, at, at == Stage.SETUP ? KNOCKS[knock][0] : null);
		if (reply == null) return;                                               // (it just doesn't answer)
		c.talker.later(o, reply, now + 40 + random.nextInt(40), () -> {
			if (c.player == null || stage != at || withXen != o) return;
			String back = answer(reply.toLowerCase(Locale.ROOT));
			if (back == null || back.isEmpty()) return;
			c.talker.later(c, back, now() + 30 + random.nextInt(30), () -> {
				if (stage == Stage.SETUP || stage == Stage.PUNCHED) xenAnswers(now());
			});
		});
	}

	/** Another Xen is trying a joke on this one: what it says back (null: nothing). Its mood, its tone and what it thinks of them decide. */
	private String answerTo(Companion joker, String bit, Stage at, String setup) {
		float trust = c.trust(joker.player().getUUID()), humor = c.personality.hidden(Personality.HUMOR);
		String tone = c.personality.tone == null ? "calm" : c.personality.tone;
		if (at == Stage.OPENED) {
			float no = 0.15f + (tone.equals("grumpy") ? 0.4f : 0) + (trust < 0 ? 0.3f : 0) + (c.chores.busy() ? 0.15f : 0) + 0.3f * c.rage.mad()
					- (tone.equals("silly") || tone.equals("cheerful") ? 0.1f : 0) - 0.15f * humor;
			if (random.nextFloat() < no) return random.nextFloat() < 0.6f ? "No" : any("no", "nope", "not now", tone.equals("grumpy") ? "nobody asked" : "nah");
			return switch (bit) {
				case "knock" -> any("who's there?", "who's there", "whos there?");
				case "joke" -> any("sure", "go on", "ok", "yeah?");
				default -> any("what?", "what", "?", "what is it");
			};
		}
		if (at == Stage.SETUP) return setup.replaceAll("\\.$", "").toLowerCase(Locale.ROOT) + " who?";
		if (random.nextFloat() < 0.3f + 0.5f * humor + 0.2f * trust) return any("lol", "LMAO", "haha", "💀", "lmaooo");
		return random.nextFloat() < 0.5f ? any("bruh", "...", "not funny", "-_-") : null;
	}
}
