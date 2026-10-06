package xen.mod;

import java.util.Arrays;
import java.util.Random;

/** Xen 2.0's arithmetic, without a game: the facts, the weighing of a choice, the mining shape, reaction times. */
public final class TwoCheck {
	private TwoCheck() {}

	private static int failures;

	private static void check(boolean ok, String what) {
		if (!ok) {
			failures++;
			System.out.println("FAIL xen 2.0: " + what);
		}
	}

	private static float median(int[] a) {
		int[] b = a.clone();
		Arrays.sort(b);
		return b[b.length / 2];
	}

	/** True when everything holds; prints a line of what it saw. */
	public static boolean run() {
		failures = 0;
		// facts (the table, when there's no game to ask)
		Facts.Drops pig = Facts.table("pig"), sheep = Facts.table("sheep"), cow = Facts.table("cow");
		check(Math.abs(pig.mean("porkchop") - 2f) < 1e-4 && pig.chance("porkchop") == 1f, "pig drops");
		check(Math.abs(Facts.hunger(pig) - 16f) < 1e-3, "a pig is 16 hunger cooked, not " + Facts.hunger(pig));
		check(Math.abs(Facts.hunger(sheep) - 9f) < 1e-3 && sheep.mean("wool") == 1f, "a sheep: 1 wool, 9 hunger cooked");
		check(cow.chance("leather") > 0.6f && cow.mean("leather") > 0.9f, "cow leather");
		check(Facts.cooked("beef").equals("cooked_beef") && Facts.cooked("apple").equals("apple"), "cooking names");
		float dry = Facts.catchesIn(5, false), wet = Facts.catchesIn(5, true);
		check(dry > 12 && dry < 17 && wet > dry, "catches in 5 minutes: " + dry + " / rain " + wet);
		check(!Facts.about("sheep").isEmpty() && !Facts.about("fish").isEmpty() && !Facts.about("lava").isEmpty(), "tips");
		// the weighing of a choice
		long now = 1000;
		Choices.Weigh w = new Choices.Weigh();
		w.want = 0.8f;
		w.risk(0.1f, "a little dark", true, 600);
		check(Choices.decide("fish", w, now, 1f).verdict == Choices.Verdict.DO, "worth it: do it");
		w = new Choices.Weigh();
		w.want = 0.6f;
		w.risk(0.6f, "night", true, 24000);
		w.risk(0.5f, "a zombie", true, 1200);
		Choices.Choice later = Choices.decide("fish", w, now, 1f);
		check(later.verdict == Choices.Verdict.LATER && later.until == now + 24000, "night and a zombie: later, in the morning (" + later.verdict + ")");
		w = new Choices.Weigh();
		w.want = 0.6f;
		w.risk(1f, "night", true, 20L * 60 * 60);
		check(Choices.decide("fish", w, now, 1f).until == now + Choices.MAX_LATER, "later is at most 30 minutes");
		w = new Choices.Weigh();
		w.want = 0.9f;
		w.no("lava behind it");
		check(Choices.decide("mine", w, now, 0.4f).verdict == Choices.Verdict.NOT, "a hard no stays no, even for the bravest");
		w = new Choices.Weigh();
		w.want = 0.02f;
		check(Choices.decide("mine", w, now, 1f).verdict == Choices.Verdict.NOT, "nothing in it: no");
		w = new Choices.Weigh();
		w.want = 0.05f;
		w.wantLater = 0.4f;
		w.later = 12000;
		check(Choices.decide("kill the pig", w, now, 1f).verdict == Choices.Verdict.LATER, "not hungry: later");
		w = new Choices.Weigh();
		w.want = 0.5f;
		w.risk(0.3f, "a monster close by", true, 1200);
		check(Choices.decide("mine", w, now, 0.4f).verdict == Choices.Verdict.DO && Choices.decide("mine", w, now, 1.6f).verdict == Choices.Verdict.LATER,
				"the brave mine on, the timid wait");
		// the mining shape (from the recorded game)
		check(Choices.shape(0, 1) == 1f && Choices.shape(1, 1) == 1f, "feet and head, right next to it");
		check(Choices.shape(-1, 0) < 0.25f && Choices.shape(-1, 1) > Choices.shape(-1, 0), "straight down: almost never");
		check(Choices.shape(3, 1) < Choices.shape(2, 1) && Choices.shape(0, 3) < Choices.shape(0, 1), "far up and far off: less");
		check(Choices.tierNeeded("iron") == 2 && Choices.tierNeeded("diamond") == 3 && Choices.tierNeeded("coal") == 1 && Choices.tierNeeded(null) == 0, "pickaxe tiers");
		// reaction times
		Random r = new Random(7);
		int n = 4000;
		int[] seen = new int[n], unseen = new int[n], fast = new int[n], confused = new int[n];
		for (int i = 0; i < n; i++) {
			seen[i] = Reflexes.sampleMs(r, false, false, false, 0, 1f);
			unseen[i] = Reflexes.sampleMs(r, true, false, false, 0, 1f);
			fast[i] = Reflexes.sampleMs(r, false, false, false, 0, 0.7f);
			confused[i] = Reflexes.sampleMs(r, false, false, false, 1f, 1f);
		}
		float ms = median(seen);
		check(ms >= 200 && ms <= 260, "a reaction is about a quarter second: " + ms);
		check(median(unseen) - ms >= 50 && median(unseen) - ms <= 160, "what it didn't see coming: slower");
		check(median(fast) < ms && median(confused) > ms + 120, "fast is faster, confused is slower");
		check(Arrays.stream(seen).min().getAsInt() >= 120 && Arrays.stream(unseen).max().getAsInt() <= 750, "no superhuman or frozen reactions");
		// typing like a player
		Random tr = new Random(3);
		check(xen.mod.talk.Texting.plainNumbers("hunting a cow 2.7 blocks away").equals("hunting a cow 3 blocks away"), "no decimals a person wouldn't say");
		check(xen.mod.talk.Texting.casual("I am going to get wood.", 0f, tr).equals("I'm going to get wood."), "a careful typist keeps it proper");
		String typed = "";
		for (int i = 0; i < 20 && !typed.contains("gonna"); i++) typed = xen.mod.talk.Texting.casual("Okay, I am going to get wood.", 1f, tr);
		check(typed.contains("gonna") && !typed.endsWith("."), "a casual typist: " + typed);
		check(xen.mod.talk.Texting.casual("Okay, to the cave at 120 64 -40.", 1f, tr).contains("120 64 -40"), "coordinates stay exact");
		check(xen.mod.talk.Texting.casual("I know what it is.", 0f, tr).equals("I know what it is.") && xen.mod.talk.Texting.casual("Here I am!", 0f, tr).equals("Here I am!"),
				"no contraction at the end of a sentence");
		check(xen.mod.talk.Texting.slangPraise(" this is peak ") && xen.mod.talk.Texting.slangPraise(" fire bro ") && xen.mod.talk.Texting.slangPraise(" very w set ")
				&& !xen.mod.talk.Texting.slangPraise(" where is the house "), "praise the way players give it");
		// hidden stats: born, saved, passed on
		Personality a = Personality.random(new Random(11)), b = Personality.random(new Random(12));
		check(a.hidden != null && a.hidden.length == Personality.HIDDEN.length, "a newborn has hidden stats");
		Personality back = Personality.fromJson(a.toJson());
		check(java.util.Arrays.equals(a.hidden, back.hidden), "hidden stats are saved");
		Personality kid = a.child(b, "a & b", new Random(13));
		check(kid.hidden != null && Math.abs(kid.hidden(Personality.REFLEXES) - a.hidden(Personality.REFLEXES)) < 0.45f, "children get their parents' hidden stats");
		Personality old = Personality.plain();
		old.bornWith(new Random(14));
		check(old.hidden != null && old.sins != null, "an older Xen gets hidden stats from its name");
		check(!a.hiddenInWords().isEmpty() && !a.describe().contains(a.hiddenInWords()), "hidden stats stay hidden");
		// the gut: a feeling from what it learned (no rules about lava)
		check(Gut.weigh(new float[6], 0.1f, 1f) == null, "calm: the gut stays out of it");
		check(Gut.weigh(new float[] {0.4f, 0.9f, 0.05f, 0.5f, 0.5f, 0.6f}, 0.2f, 1f) == xen.mod.core.Action.BACK, "walking on hurts, stepping back doesn't: back");
		check(Gut.weigh(new float[] {0.05f, 0.06f, 0.05f, 0.05f, 0.05f, 0.05f}, 0.95f, 1f) == null, "dread but no move is better: nothing to do");
		check(Gut.weigh(new float[] {0.2f, 0.3f, 0.25f, 0.1f, 0.4f, 0.3f}, 0.3f, 1.5f) == xen.mod.core.Action.LEFT, "the least feared move");
		check("lava".equals(Teaching.thing("lava")) && "magma_block".equals(Teaching.thing("magma")) && "sweet_berry_bush".equals(Teaching.thing("berry bush"))
				&& Teaching.thing("dirt") == null, "what players warn about");
		check(Confusion.scale("off") == 0f && Confusion.scale("high") > 1f && Reflexes.scale("instant") == 0f, "settings");
		// beta 6: words, grammar and a memory of facts
		xen.mod.talk.Words.reset();
		var W = xen.mod.talk.Words.Act.class;
		check(xen.mod.talk.Words.parse("hw r u", "Pip").act() == xen.mod.talk.Words.Act.ASK_WH, "shorthand read out: hw r u = how are you");
		check(xen.mod.talk.Words.parse("hello Pip!", "Pip").act() == xen.mod.talk.Words.Act.GREET, "a greeting");
		var told = xen.mod.talk.Words.parse("cats like fish", "Pip");
		check(told.act() == xen.mod.talk.Words.Act.TELL && told.subject().key().equals("cat") && told.rel().equals("like") && told.object().key().equals("fish") && told.subject().plural(),
				"cats like fish: subject cat (many), like, fish");
		var place = xen.mod.talk.Words.parse("the village is at 120 64 -30", "Pip");
		check(place.rel().equals("at") && place.object().key().equals("120 64 -30"), "a place: the village is at 120 64 -30");
		check(xen.mod.talk.Words.parse("mine diamonds", "Pip").act() == xen.mod.talk.Words.Act.COMMAND, "mine diamonds: a request");
		check(xen.mod.talk.Words.parse("creepers don't like cats", "Pip").negated(), "don't: negated");
		var wolves = xen.mod.talk.Words.parse("do wolves eat bones?", "Pip");
		check(wolves.act() == xen.mod.talk.Words.Act.ASK && wolves.subject().key().equals("wolf") && wolves.object().key().equals("bone"), "wolves are a wolf, bones a bone");
		check(xen.mod.talk.Words.sentence("a cat", false, "like", false, "fish").equals("a cat likes fish") && xen.mod.talk.Words.sentence("cats", true, "like", true, "dogs").equals("cats don't like dogs")
				&& xen.mod.talk.Words.sentence("the village", false, "at", false, "at 1 2 3").equals("the village is at 1 2 3") && xen.mod.talk.Words.sentence("creepers", true, "be", true, "friendly").equals("creepers aren't friendly")
				&& xen.mod.talk.Words.sentence("a fox", false, "catch", false, "chickens").equals("a fox catches chickens") && xen.mod.talk.Words.sentence("pigs", true, "can fly", true, "").equals("pigs can't fly"),
				"sentences with the grammar right");
		check(xen.mod.talk.Words.a("apple").equals("an") && xen.mod.talk.Words.a("creeper").equals("a"), "a / an");
		var mem = new xen.mod.talk.Words.Memory();
		Random wr = new Random(5);
		long[] clock = {0};
		xen.mod.talk.Words.Context ctx = new xen.mod.talk.Words.Context() {
			public String self() { return "Pip"; }
			public String speaker() { return "Steve"; }
			public xen.mod.talk.Words.Memory memory() { return mem; }
			public String game(String sub, String rel) { return sub.equals("cow") && rel.equals("drop") ? "beef and leather" : null; }
			public Random random() { return wr; }
			public long now() { return clock[0] += 20; }
		};
		check(xen.mod.talk.Words.reply("cats like fish", ctx) != null && mem.size() == 1, "a fact it's told, kept");
		check(xen.mod.talk.Words.reply("do cats like fish?", ctx).contains("cats like fish"), "and answered from memory");
		check(xen.mod.talk.Words.reply("what do cows drop?", ctx).equals("cows drop beef and leather"), "the game's own knowledge (retrieval)");
		String unknown = xen.mod.talk.Words.reply("what do wolves eat?", ctx);
		check(unknown != null && unknown.contains("?") && mem.recall("wolf", null, null).isEmpty(), "what it doesn't know, it doesn't make up (it asks): " + unknown);
		check(xen.mod.talk.Words.reply("bones", ctx).contains("wolves eat bones") && !mem.recall("wolf", "eat", "bone").isEmpty(), "it asked, and learns the answer");
		xen.mod.talk.Words.reply("what is a blorp?", ctx);
		xen.mod.talk.Words.reply("it is a mob", ctx);
		check(!mem.recall("blorp", "be", "mob").isEmpty() && xen.mod.talk.Words.knows("blorp"), "a new word, and what it is");
		check(xen.mod.talk.Words.reply("cats don't like fish", ctx).contains("thought") && mem.recall("cat", "like", "fish").get(0).negated(), "told the other way round: it changes its mind");
		var copy = new xen.mod.talk.Words.Memory();
		copy.load(mem.toJson());
		check(copy.size() == mem.size(), "facts are saved");
		xen.mod.talk.Words.reset();
		System.out.printf(java.util.Locale.ROOT, "xen 2.0: pig %.0f hunger (%s), sheep %.0f, %.1f catches in 5 min; reactions %.0f ms (unseen %.0f, confused %.0f); %s%n",
				Facts.hunger(pig), pig.says(), Facts.hunger(sheep), dry, ms, median(unseen), median(confused), failures == 0 ? "all good" : failures + " failed");
		return failures == 0;
	}
}
