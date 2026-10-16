package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import xen.mod.core.Mind;
import xen.mod.core.Sins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One thread through what a Xen does and what it says (like Project Sid's agents: one decision, told to its hands and
 * its mouth). Its chats with other Xens come out of what it's really doing and needs, and what they agree goes back
 * into its choices:
 * <ul>
 *   <li><b>Catching up</b>: it remembers what a Xen was off to do the last time they talked, and asks how that went; the
 *   answer is what really happened (its time diary: got somewhere, got nowhere, died, and what it has now).</li>
 *   <li><b>What each is doing and why</b>, from its choice and its reasons, not a script.</li>
 *   <li><b>Help</b>: what one's plan is short of, the other may have to spare (and hands it over), or be on its way to
 *   get (and may promise to bring some, as much as it's kind and likes them). A promise it can keep, it keeps: it walks
 *   over with the thing and says so. One it couldn't, it owns up to the next time they talk.</li>
 *   <li><b>Together</b>: one asks the other along ("want to come mining with me?"); if it says yes, for a while both lean
 *   that way, and the one who asked shares where (its cave).</li>
 *   <li><b>What it worked out</b>: one of its own rules ({@link Rules}), which the other takes on as hearsay.</li>
 * </ul>
 * All of it is hints to its mind, not orders: what it promised or agreed leans its choices (a loyal one's more).
 */
final class Intent {
	/** What it knows of another Xen from their last chat: what it was off to do, how its diary stood for that then, and when. */
	static final class Note {
		int option = -1;
		int tries, worked, died;
		long at;
	}

	/** A promise: to whom, what (as a plan names it: "log", "food", "iron_ingot"), how many, in words, until when; kept? */
	static final class Promise {
		final String to, item, words;
		final int n;
		final long until;
		boolean kept;

		Promise(String to, String item, int n, String words, long until) {
			this.to = to;
			this.item = item;
			this.n = n;
			this.words = words;
			this.until = until;
		}
	}

	private final Companion c;
	private final Random random = new Random();
	final Map<String, Note> know = new HashMap<>();
	final List<Promise> word = new ArrayList<>();
	/** When it last asked each Xen for something (not the same ask every chat). */
	private final Map<String, Long> askedAt = new HashMap<>();
	/** Going along with someone for a while: who, for what, until when; tagAlong: it's the one who was asked. */
	String with;
	int withOption = -1;
	long withUntil;
	boolean tagAlong;

	Intent(Companion c) {
		this.c = c;
	}

	private static long now(Companion x) {
		return x.player.level().getGameTime();
	}

	/** One of six ways to say it, by its tone: cheerful, calm, grumpy, shy, bold, silly. */
	private static String pick(Companion x, String... byTone) {
		int t = Math.max(0, java.util.Arrays.asList(Personality.TONES).indexOf(x.personality.tone));
		return byTone[Math.min(byTone.length - 1, t)];
	}

	private Companion named(String n) {
		for (Companion x : c.mod.companions) if (x.name.equals(n)) return x;
		return null;
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ------------------------------------------------------------------------------ what it needs, what it can spare
	/** What its plan is short of, as {item (as the plan names it), how many, in words, what for}; null: nothing it'd ask for. */
	String[] need() {
		var p = c.player;
		if (p == null) return null;
		if (p.getFoodData().getFoodLevel() < 12 && c.items().getOrDefault("food", 0) == 0) return new String[] {"food", "3", "some food", "I'm starving"};
		Plans.Step s = c.plans.peek();
		if (s == null || c.plans.goal == null || s.kind == Plans.Kind.CRAFT || s.kind == Plans.Kind.LOOK) return null;
		int n = Math.max(1, Math.min(16, s.count));
		String thing = s.item.replace('_', ' ');
		return new String[] {s.item, String.valueOf(n), n + " " + Crafter.plural(thing, n), "for my " + c.plans.goal.replace('_', ' ')};
	}

	private static boolean fits(String want, ItemStack s, String id) {
		return switch (want) {
			case "food" -> s.has(DataComponents.FOOD) && !id.equals("rotten_flesh") && !id.equals("spider_eye") && !id.equals("poisonous_potato")
					&& !id.equals("pufferfish") && !id.equals("chicken");
			case "log" -> Crafter.isLog(id);
			case "planks" -> id.endsWith("_planks");
			case "wool" -> id.endsWith("_wool");
			case "coal" -> id.equals("coal") || id.equals("charcoal");
			case "cobblestone" -> id.equals("cobblestone") || id.equals("cobbled_deepslate");
			case "raw_iron" -> id.equals("raw_iron") || id.equals("iron_ore");
			default -> id.equals(want);
		};
	}

	/** What it has of what someone needs (any log for "log"...), and how many it can spare: {item, count}, or null. */
	String[] spare(String item, int n) {
		if (c.player == null) return null;
		var inv = c.player.getInventory();
		Map<String, Integer> have = new HashMap<>();
		int total = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (!fits(item, s, id)) continue;
			have.merge(id, s.getCount(), Integer::sum);
			total += s.getCount();
		}
		String best = null;
		for (var e : have.entrySet()) if (best == null || e.getValue() > have.get(best)) best = e.getKey();
		if (best == null) return null;
		int keep = switch (item) {                                                // what it keeps for itself
			case "food", "log", "planks", "coal" -> 4;
			case "cobblestone" -> 16;
			default -> 0;
		};
		keep += Math.round(4 * c.personality.sin(Sins.GREED));
		String[] mine = need();
		if (mine != null && mine[0].equals(item)) keep += Integer.parseInt(mine[1]);   // (it needs them too)
		int give = Math.min(n, Math.min(have.get(best), total - keep));
		return give > 0 ? new String[] {best, String.valueOf(give)} : null;
	}

	/** The choice that gets that thing (-1: none it knows). */
	static int optionFor(String item) {
		return switch (item) {
			case "log", "planks", "stick" -> Mind.WOOD;
			case "cobblestone" -> Mind.STONE;
			case "food", "wool", "leather", "string" -> Mind.FOOD;
			default -> item.contains("iron") || item.contains("gold") || item.contains("diamond") || item.equals("coal") || item.contains("redstone")
					|| item.contains("lapis") ? Mind.MINE : -1;
		};
	}

	private static boolean joinable(int o) {
		return o == Mind.MINE || o == Mind.WOOD || o == Mind.STONE || o == Mind.FOOD || o == Mind.EXPLORE || o == Mind.ADVENTURE || o == Mind.HOUSE
				|| o == Mind.FARM;
	}

	private static String invite(int o) {
		return switch (o) {
			case Mind.MINE -> "Want to come mining with me?";
			case Mind.WOOD -> "Want to come get wood with me?";
			case Mind.STONE -> "Want to come get stone with me?";
			case Mind.FOOD -> "Want to come hunting with me?";
			case Mind.EXPLORE -> "Want to come exploring with me?";
			case Mind.ADVENTURE -> "Want to come on an adventure with me?";
			case Mind.HOUSE -> "Want to help me with my house?";
			default -> "Want to help me on my farm?";
		};
	}

	private static String howWent(int o) {
		return switch (o) {
			case Mind.MINE -> "How was the mining? Find anything?";
			case Mind.WOOD -> "Did you get your wood?";
			case Mind.STONE -> "Get enough stone?";
			case Mind.FOOD -> "Did you find food?";
			case Mind.HOUSE -> "How's the house coming along?";
			case Mind.FARM -> "How's the farm doing?";
			case Mind.EXPLORE -> "Find anything out there?";
			case Mind.ADVENTURE -> "How was your adventure?";
			case Mind.CRAFT -> "Did you make your gear?";
			case Mind.SMELT -> "Get your stuff smelted?";
			case Mind.TRADE -> "Any good trades?";
			default -> null;
		};
	}

	/** What it has to show for that kind of thing, in a few words ("" if nothing to say). */
	private static String haul(Companion x, int o) {
		Map<String, Integer> it = x.items();
		int iron = it.getOrDefault("raw_iron", 0) + it.getOrDefault("iron_ingot", 0), dia = it.getOrDefault("diamond", 0);
		return switch (o) {
			case Mind.MINE -> dia > 0 ? "Got " + dia + (dia == 1 ? " diamond" : " diamonds") + (iron > 0 ? " and " + iron + " iron" : "") + "!"
					: iron > 0 ? "Got " + iron + " iron." : it.getOrDefault("coal", 0) > 0 ? "Some coal, at least." : "";
			case Mind.WOOD -> it.getOrDefault("log", 0) > 0 ? "Got " + it.get("log") + " logs." : "";
			case Mind.STONE -> it.getOrDefault("cobblestone", 0) > 0 ? "Got " + it.get("cobblestone") + " cobble." : "";
			case Mind.FOOD -> it.getOrDefault("food", 0) > 0 ? "I've got " + it.get("food") + " food now." : "";
			case Mind.EXPLORE -> x.me.saw().isEmpty() ? "" : "Found " + x.me.saw() + "!";
			default -> "";
		};
	}

	// ------------------------------------------------------------------------------ a chat
	/** Its part of a chat with another Xen (they take turns): returns the time after the last line. */
	long chat(Companion o, long t, Society.Line line) {
		if (c.player == null || o.player == null) return t;
		long now = now(c);
		t = promises(c, o, t, line, now);
		t = promises(o, c, t, line, now);
		t = catchUp(c, o, t, line, now);
		if (random.nextFloat() < 0.3f + 0.5f * o.personality.chattiness) t = catchUp(o, c, t, line, now);
		t = upTo(c, o, t, line, now);
		t = ask(c, o, t, line, now);
		if (random.nextBoolean()) t = ask(o, c, t, line, now);
		t = rule(random.nextBoolean() ? c : o, t, line, c, o);
		remember(c, o, now);
		remember(o, c, now);
		return t;
	}

	/** Promises a made b: one it couldn't keep it owns up to; one it kept, b thanks it for again. */
	private static long promises(Companion a, Companion b, long t, Society.Line line, long now) {
		var it = a.intent.word.iterator();
		while (it.hasNext()) {
			Promise p = it.next();
			if (!p.to.equals(b.name)) continue;
			if (p.kept) {
				it.remove();
				line.say(b, pick(b, "Thanks again for the " + p.words + "!", "Thanks for the " + p.words + ", by the way.", "The " + p.words + " helped. Thanks.",
						"Th-thanks for the " + p.words + ".", "You kept your word. Respect.", "Best " + p.words + " ever!"), t += 55, null);
			} else if (now > p.until) {
				it.remove();
				line.say(a, "Sorry, I never got you that " + p.words + ".", t += 55, () -> b.trust(a.player.getUUID(), -0.05f));
				line.say(b, pick(b, "That's okay!", "No worries.", "Figured.", "Oh... okay.", "Next time, then.", "I'll survive. Probably."), t += 45, null);
			}
			return t;
		}
		return t;
	}

	/** a asks how what b was off to do went, last time; b says what really happened. */
	private static long catchUp(Companion a, Companion b, long t, Society.Line line, long now) {
		Note n = a.intent.know.get(b.name);
		if (n == null || n.option < 0 || now - n.at > 24000L * 3) return t;
		String q = howWent(n.option);
		if (q == null) return t;
		Diary.Line d = b.diary.lines.get(Mind.OPTIONS[n.option]);
		int tries = d == null ? 0 : d.tries, worked = d == null ? 0 : d.worked, died = d == null ? 0 : d.died;
		String answer, back;
		if (died > n.died) {
			String of = b.diedOf;
			answer = "Not great. " + (of.contains("fall") ? "I fell and died." : of.contains("lava") ? "Lava got me." : of.contains("drown") ? "I drowned."
					: of.contains("fire") ? "I burned." : of.contains("starv") ? "I starved." : of.isEmpty() ? "I died." : "A " + of + " got me.") + " Lost my stuff.";
			back = pick(a, "Oh no! Glad you're back.", "That's rough. Be careful out there.", "Ugh. Happens to everyone.", "Oh no... are you okay?",
					"Get back out there!", "Rest in pieces!");
		} else if (worked > n.worked) {
			String h = haul(b, n.option);
			answer = pick(b, "Really good!", "Went fine.", "Not bad.", "Okay, I guess.", "Great!", "Amazing!") + (h.isEmpty() ? "" : " " + h);
			back = pick(a, "Nice!", "Good.", "Huh, not bad.", "C-cool!", "Nice work!", "Show-off!");
		} else if (tries > n.tries) {
			answer = pick(b, "Not really. Didn't get anywhere.", "Didn't get far, honestly.", "Waste of time.", "Not w-well...", "Didn't work out. I'll try again.",
					"Total disaster!");
			back = pick(a, "Next time!", "It happens.", "Yeah, that happens.", "Oh...", "You'll get it.", "Classic.");
		} else {
			answer = b.goals.option == n.option ? "Still on it." : pick(b, "Didn't get round to it yet.", "Haven't yet.", "Haven't bothered.", "N-not yet.",
					"Got sidetracked!", "Forgot, honestly!");
			back = null;
		}
		line.say(a, q, t += 55, null);
		line.say(b, answer, t += 55, null);
		if (back != null) line.say(a, back, t += 45, null);
		return t;
	}

	/** What a is doing and why; b says what it's doing; the same thing, or a asks b along. */
	private long upTo(Companion a, Companion b, long t, Society.Line line, long now) {
		int oa = a.goals.option, ob = b.goals.option;
		String da = a.me.doing(), wa = a.me.why();
		if (!da.isEmpty()) line.say(a, "I'm " + da + (wa.isEmpty() || wa.startsWith("I was asked") ? "." : ". " + cap(wa) + "."), t += 55, null);
		if (ob >= 0 && ob == oa && joinable(oa)) line.say(b, pick(b, "Me too!", "Same here.", "Same. Ugh.", "M-me too.", "Same! Race you.", "Twins!"), t += 45, null);
		else {
			String db = b.me.doing();
			if (!db.isEmpty()) line.say(b, "I'm " + db + ".", t += 50, null);
		}
		if (!joinable(oa) || a.intent.with != null && now < a.intent.withUntil || b.intent.with != null && now < b.intent.withUntil) return t;
		float keen = 0.25f + 0.4f * a.personality.chattiness - (a.personality.loner ? 0.3f : 0) + (ob == oa ? 0.3f : 0);
		if (random.nextFloat() > keen) return t;
		line.say(a, invite(oa), t += 55, null);
		float yes = 0.15f + 0.5f * Math.max(0, b.trust(a.player.getUUID())) + 0.25f * b.personality.chattiness + (ob == oa ? 0.3f : 0)
				- (b.personality.loner ? 0.3f : 0) - 0.3f * b.personality.sin(Sins.SLOTH)
				- (b.chores.busy() && !b.chores.own || b.mode == Companion.Mode.STAY ? 1 : 0)
				- (b.player.getHealth() < 10 || b.player.getFoodData().getFoodLevel() < 8 ? 0.3f : 0);
		if (random.nextFloat() < yes) {
			line.say(b, pick(b, "Sure, let's go!", "Alright, I'll come.", "Fine. Lead the way.", "O-okay!", "Let's do it!", "Yesss! Adventure buddies!"), t += 50, () -> {
				long at = now(a);
				a.intent.with = b.name;
				a.intent.tagAlong = false;
				b.intent.with = a.name;
				b.intent.tagAlong = true;
				a.intent.withOption = b.intent.withOption = oa;
				a.intent.withUntil = b.intent.withUntil = at + 20 * 60 * 4;
				BlockPos cave = oa == Mind.MINE ? a.caves.nearest(160) : null;
				if (cave != null) b.caves.sawFromAfar(cave, "cave");                // (the one who asked shows the way)
				a.journal("thinks", "asked " + b.name + " along to " + Mind.SAYS[oa] + ": " + b.name + " said yes");
				b.journal("thinks", "goes along with " + a.name + " to " + Mind.SAYS[oa]);
			});
		} else {
			String why = b.me.why();
			line.say(b, why.isEmpty() || why.startsWith("I was asked") ? pick(b, "Maybe next time!", "Not right now.", "Nah.", "M-maybe later.", "Can't, busy!", "Nope!")
					: "Not now, " + why + ".", t += 50, null);
		}
		return t;
	}

	/** a asks b for what its plan is short of: b hands it over, promises to bring some, or says no. */
	private static long ask(Companion a, Companion b, long t, Society.Line line, long now) {
		String[] need = a.intent.need();
		if (need == null || now - a.intent.askedAt.getOrDefault(b.name, -100_000L) < 20 * 60 * 5) return t;
		a.intent.askedAt.put(b.name, now);
		int n = Integer.parseInt(need[1]);
		line.say(a, "I need " + need[2] + " " + need[3] + ". Got any?", t += 55, null);
		float trust = b.trust(a.player.getUUID());
		Tribe tb = b.tribe();
		float will = 0.2f + 0.5f * b.personality.kindness + 0.4f * Math.max(0, trust) + (tb != null && tb == a.tribe() ? 0.2f * b.personality.loyalty : 0)
				- 0.5f * b.personality.sin(Sins.GREED);
		String[] s = b.intent.spare(need[0], n);
		Random r = b.intent.random;
		if (trust < -0.1f) {
			line.say(b, pick(b, "Not for you, sorry.", "No.", "Get your own.", "N-no...", "Not a chance.", "Nope, nope, nope."), t += 45, null);
		} else if (s != null && r.nextFloat() < will) {
			int give = Integer.parseInt(s[1]);
			line.say(b, pick(b, "Sure, here you go!", "I can spare " + give + ".", "Fine. Here.", "S-sure, here.", "Take it!", "Catch!"), t += 50, () -> {
				if (b.player == null || a.player == null) return;
				if (b.sharing.gift(a, s[0], give, now(b))) {
					a.trust(b.player.getUUID(), 0.15f);
					b.journal("does", "gives " + a.name + " " + give + " " + s[0].replace('_', ' ') + " (asked)");
				}
			});
			line.say(a, pick(a, "Thanks so much!", "Thanks.", "Thanks. I owe you.", "Th-thank you!", "Legend!", "You're the best!"), t += 50, null);
		} else if (optionFor(need[0]) >= 0 && (b.goals.option == optionFor(need[0]) || r.nextFloat() < 0.3f) && r.nextFloat() < will) {
			Promise p = new Promise(a.name, need[0], n, need[2].replaceFirst("^\\d+ ", "").replaceFirst("^some ", ""), now + 24000);
			line.say(b, "I don't have any" + (s == null ? "" : " to spare") + ", but I'll bring you some when I get them.", t += 50, () -> {
				b.intent.word.add(p);
				b.journal("thinks", "promises " + a.name + " " + need[2] + " " + need[3].replace("my ", "their "));
			});
			line.say(a, pick(a, "Deal! Thanks!", "Thanks, I'd appreciate it.", "We'll see.", "R-really? Thanks!", "Deal.", "Pinky promise?"), t += 45, null);
		} else {
			line.say(b, s == null ? pick(b, "Sorry, I don't have any.", "None, sorry.", "Nope, none.", "S-sorry, none.", "Haven't got any.", "My pockets are empty!")
					: pick(b, "Sorry, I need mine.", "I need what I have, sorry.", "Mine. Get your own.", "S-sorry, I need them.", "Can't spare any.", "Finders keepers!"), t += 50, null);
		}
		return t;
	}

	/** Something one of them worked out (its own rule), the other takes on as hearsay. */
	private long rule(Companion a, long t, Society.Line line, Companion x, Companion y) {
		Companion b = a == x ? y : x;
		Rules.Rule r = a.rules.tell(b.rules);
		if (r == null || random.nextFloat() > 0.3f + 0.4f * a.personality.chattiness) return t;
		line.say(a, "Something I worked out: " + r.says() + ".", t += 60, () -> {
			if (b.player == null || a.player == null) return;
			boolean took = b.rules.heard(r, a.name, b.trust(a.player.getUUID()));
			b.talker.sayNear(took ? pick(b, "Huh! Good to know.", "Makes sense.", "Hm. Fair.", "O-oh. Okay!", "I'll remember that.", "Big brain!")
					: pick(b, "Not in my experience.", "Hm, not for me.", "Doubt it.", "I-I don't think so...", "Nah, I do it my way.", "Lies!"));
		});
		return t + 50;
	}

	/** What it knows the other one's up to now (for next time). */
	private static void remember(Companion a, Companion b, long now) {
		int o = b.goals.option;
		if (o < 0 || howWent(o) == null) return;
		Note n = a.intent.know.computeIfAbsent(b.name, k -> new Note());
		Diary.Line d = b.diary.lines.get(Mind.OPTIONS[o]);
		n.option = o;
		n.tries = d == null ? 0 : d.tries;
		n.worked = d == null ? 0 : d.worked;
		n.died = d == null ? 0 : d.died;
		n.at = now;
	}

	// ------------------------------------------------------------------------------ its word, in what it does
	/** Who it's going along with now (null: nobody, or they're not around). */
	Companion partner() {
		if (with == null || c.player == null) return null;
		if (now(c) > withUntil) {
			with = null;
			return null;
		}
		Companion w = named(with);
		if (w == null || w.player == null || !w.player.isAlive() || w.player.level() != c.player.level() || w.player.distanceTo(c.player) > 64) return null;
		return w;
	}

	/** What it agreed leans its choices: the thing they're doing together, what it promised to get. */
	void lean(float[] b) {
		if (c.player == null) return;
		long now = now(c);
		if (partner() != null && withOption >= 0) {
			b[withOption] += tagAlong ? 0.35f : 0.25f;
			if (tagAlong && (withOption == Mind.EXPLORE || withOption == Mind.ADVENTURE)) b[Mind.FOLLOW] += 0.45f;   // (going with them: tagging along)
		}
		for (Promise p : word) {
			if (p.kept || now > p.until) continue;
			int o = optionFor(p.item);
			if (o >= 0) b[o] += 0.15f + 0.3f * c.personality.loyalty;
		}
	}

	/** Every few seconds: a promise it can keep now (it has the thing to spare, they're near), it keeps. */
	void tick() {
		if (c.player == null || c.minion || c.inArena || c.sharing.busy() || c.fightingNow()) return;
		long now = now(c);
		word.removeIf(p -> !p.kept && now > p.until + 24000);                     // (an old one it never kept is forgotten a day later)
		for (Promise p : word) {
			if (p.kept || now > p.until) continue;
			Companion them = named(p.to);
			if (them == null || them.player == null || them.player.level() != c.player.level() || them.player.distanceTo(c.player) > 48) continue;
			String[] s = spare(p.item, p.n);
			if (s == null) continue;
			if (!c.sharing.gift(them, s[0], Integer.parseInt(s[1]), now)) return;
			p.kept = true;
			c.talker.sayNear(pick(c, "Hey " + p.to + "! Got you the " + p.words + " I promised!", p.to + ", here's the " + p.words + " I said I'd bring.",
					p.to + ". Your " + p.words + ". As promised.", "Um, " + p.to + "? I-I got your " + p.words + ".", p.to + "! " + cap(p.words) + ", delivered.",
					"Special delivery for " + p.to + "! One " + p.words + " order!"));
			c.journal("does", "keeps its word: brings " + p.to + " " + s[1] + " " + s[0].replace('_', ' '));
			them.trust(c.player.getUUID(), 0.15f);
			return;
		}
	}
}
