package xen.mod.talk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A Xen's own words, without a big language model: it reads what was said (greeting, question, what it's about, how it
 * sounds), its small network ({@link ActNet}) picks what kind of reply fits it (greet back, how it feels, its opinion,
 * agree or not, ask back, a joke...), and it builds the sentence word by word from its word library ({@link Lexicon}):
 * the words its tone of voice prefers, what it really thinks of the thing (its own tastes: its nature and its life), what
 * it's really doing and wants, written the way it writes (a shy one stammers and trails off, a cheerful one shouts, a
 * grumpy one mutters). Light: no model to load, a few thousand numbers. Xens talk to each other with it too, and each
 * reads the other's words the same way.
 */
public final class Voice {
	/** What the Xen is and knows (the game fills it in). */
	public interface Self {
		String name();

		/** cheerful, calm, grumpy, shy, bold or silly. */
		String tone();

		/** friendly, passive or aggressive. */
		String temper();

		float chattiness();

		float curiosity();

		float kindness();

		float bravery();

		/** How much it trusts whoever this is (-1..1). */
		float trust(String who);

		/** How it feels in a word (pleased, calm, bored, curious, uneasy, afraid, terrified, hurt). */
		String mood();

		boolean hurt();

		boolean hungry();

		boolean busy();

		/** What it's doing, as "getting wood" (empty: nothing). */
		String doing();

		/** Its dream, as "build a home" (empty: none). */
		String dream();

		/** Something it saw lately, as "a village" (empty: nothing). */
		String saw();

		/** What it thinks of a topic (-1..1): what Xens think, and its own nature and life. */
		float like(Lexicon.Topic t);
	}

	/** What it made of a line. */
	public static final class Heard {
		public final String text;
		final List<String> words = new ArrayList<>();
		String flat = " ";
		public boolean question, greet, bye, thanks, sorry, praise, insult, laugh, yes, no, howAreYou, whatDoing, likeQ, suggest, feelMe, news,
				confused, aboutYou, aboutMe, name, tellMe;
		public String wh = "", suggestion = "";
		public int polarity;
		public Lexicon.Topic topic;

		Heard(String text) {
			this.text = text;
		}

		boolean statement() {
			return !question && !flagged();
		}

		boolean flagged() {
			return greet || bye || thanks || sorry || praise || insult || laugh || yes || no || howAreYou || whatDoing || likeQ || suggest || feelMe
					|| news || confused;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			String[] n = {"question", "greet", "bye", "thanks", "sorry", "praise", "insult", "laugh", "yes", "no", "howAreYou", "whatDoing", "likeQ",
					"suggest", "feelMe", "news", "confused", "aboutYou", "aboutMe"};
			boolean[] v = {question, greet, bye, thanks, sorry, praise, insult, laugh, yes, no, howAreYou, whatDoing, likeQ, suggest, feelMe, news,
					confused, aboutYou, aboutMe};
			for (int i = 0; i < n.length; i++) if (v[i]) sb.append(sb.length() > 0 ? "," : "").append(n[i]);
			return sb + (topic == null ? "" : " topic=" + topic.key()) + (polarity != 0 ? " polarity=" + polarity : "")
					+ (suggestion.isEmpty() ? "" : " suggest=\"" + suggestion + "\"");
		}
	}

	// ------------------------------------------------------------------------------------ the network, shared
	private static ActNet net;
	private static Path netFile;
	private static boolean dirty;

	/** Load the network (config/xen/words.net), or train a new one (a moment) and save it. */
	public static synchronized void init(Path dir) {
		netFile = dir.resolve("words.net");
		Lexicon.override = dir.resolve("words.json");
		Lexicon.reload();
		ActNet loaded = ActNet.load(netFile);
		net = loaded != null ? loaded : trained(7);
		dirty = loaded == null;
		save();
	}

	public static synchronized ActNet net() {
		if (net == null) net = trained(7);
		return net;
	}

	/** Keep what it learned (if it learned anything since). */
	public static synchronized void save() {
		if (!dirty || net == null || netFile == null) return;
		try {
			net.save(netFile);
			dirty = false;
		} catch (IOException ignored) {
			// (next time)
		}
	}

	// ------------------------------------------------------------------------------------ one Xen's voice
	private final Self me;
	private final Random random;
	private final Deque<String> recent = Lexicon.memory(), lines = Lexicon.memory();
	private record Last(float[] x, int act, long at) {}
	private final Map<String, Last> lastTo = new HashMap<>();
	/** The last reply it made (for its journal and the tests): the kind, and what it made of what it heard. */
	public String lastAct = "", lastHeard = "";

	public Voice(Self me, long seed) {
		this.me = me;
		this.random = new Random(seed);
	}

	private Lexicon lx() {
		return Lexicon.get();
	}

	private String w(String pool) {
		return lx().pick(pool, me.tone(), random, recent);
	}

	// ------------------------------------------------------------------------------------ reading
	private static final Set<String> WH = Set.of("what", "where", "why", "how", "who", "when", "which");
	private static final Set<String> AUX = Set.of("do", "does", "did", "are", "is", "can", "could", "will", "would", "should", "have", "has",
			"was", "were", "am", "shall", "may");
	private static final Set<String> GREETS = Set.of("hi", "hey", "hello", "hiya", "yo", "howdy", "heyo", "greetings", "hai", "helo", "hii", "sup");
	private static final Set<String> YES = Set.of("yes", "yeah", "sure", "ok", "definitely", "true", "right", "correct", "absolutely", "alright", "fine");
	private static final Set<String> NO = Set.of("no", "never", "not");
	private static final Set<String> INSULTS = Set.of("noob", "idiot", "loser", "trash", "stupid", "dumb", "useless", "bozo", "clown", "cringe");
	private static final Set<String> NEGATE = Set.of("not", "don't", "doesn't", "isn't", "aren't", "never", "no", "can't", "won't", "didn't");

	/** What it makes of a line (its own name taken out). */
	public static Heard hear(String text, String myName) {
		Lexicon lx = Lexicon.get();
		Heard h = new Heard(text);
		String t = text.toLowerCase(Locale.ROOT);
		if (myName != null && !myName.isEmpty()) t = t.replaceAll("\\b" + Pattern.quote(myName.toLowerCase(Locale.ROOT)) + "\\b", " ");
		h.question = t.contains("?");
		t = t.replaceAll("([a-z])\\1{2,}", "$1$1");                                   // "sooooo" -> "soo", "hiiii" -> "hii"
		for (String tok : t.split("[^a-z0-9']+")) {
			if (tok.isEmpty() || tok.equals("'")) continue;
			String n = lx.normalize.getOrDefault(tok, tok);
			for (String part : n.split(" ")) if (!part.isEmpty()) h.words.add(part);
		}
		List<String> w = h.words;
		h.flat = " " + String.join(" ", w) + " ";
		String s = h.flat, first = w.isEmpty() ? "" : w.get(0), second = w.size() > 1 ? w.get(1) : "";
		if (WH.contains(first) || AUX.contains(first) && w.size() > 1) h.question = true;
		if (WH.contains(first)) h.wh = first;
		else if (WH.contains(second)) h.wh = second;
		h.aboutYou = w.contains("you") || w.contains("your") || w.contains("you're") || w.contains("yours") || w.contains("yourself");
		h.aboutMe = w.contains("i") || w.contains("i'm") || w.contains("me") || w.contains("my") || w.contains("i've");
		for (int i = 0; i < w.size(); i++) {                                          // how it sounds: good words, bad words, "not" turns them round
			Integer p = lx.polarity.get(w.get(i));
			if (p == null) continue;
			boolean negated = i > 0 && NEGATE.contains(w.get(i - 1)) || i > 1 && NEGATE.contains(w.get(i - 2));
			h.polarity += negated ? -p : p;
		}
		h.greet = GREETS.contains(first) || s.startsWith(" good morning ") || s.startsWith(" good evening ") || s.startsWith(" what's up ")
				|| w.size() <= 2 && (w.contains("hello") || w.contains("hi") || w.contains("hey"));
		h.bye = w.contains("bye") || w.contains("goodbye") || w.contains("cya") || s.contains(" see you ") || s.contains(" good night ")
				|| s.contains(" have to go ") || s.contains(" gotta go ") || s.contains(" be right back ") || s.contains(" later ") && w.size() <= 3;
		h.thanks = w.contains("thanks") || w.contains("thank");
		h.sorry = w.contains("sorry") || s.contains(" my bad ") || w.contains("apologize");
		h.laugh = w.contains("lol") || s.contains(" so funny ") || s.contains(" that's funny ");
		h.yes = w.size() <= 4 && YES.contains(first) && !h.question;
		h.no = w.size() <= 4 && NO.contains(first) && !h.question;
		h.howAreYou = s.contains(" how are you") || s.contains(" how's it going") || s.contains(" how you doing") || s.contains(" are you ok")
				|| s.contains(" you ok ") || s.contains(" how do you feel ") && !s.contains(" feel about ") || s.contains(" how have you been") || s.contains(" how's life")
				|| s.contains(" you good ") && h.question || s.contains(" how is it going");
		h.whatDoing = s.contains(" what are you doing") || s.contains(" what you doing") || s.contains(" what are you up to")
				|| s.contains(" what're you doing") || s.contains(" what's up with you") || s.contains(" whatcha doing") || s.contains(" what are you working on")
				|| s.contains(" what's the plan") || s.contains(" busy ") && h.question && w.size() <= 3;
		h.likeQ = s.contains(" do you like ") || s.contains(" you like ") && h.question || s.contains(" do you love ") || s.contains(" do you hate ")
				|| s.contains(" think of ") || s.contains(" think about ") || s.contains(" feel about ") || s.contains(" favorite ")
				|| s.contains(" favourite ") || s.contains(" thoughts on ") || s.contains(" opinion ");
		for (String mark : new String[] {" let's ", " lets ", " we should ", " shall we ", " do you want to ", " want to ", " how about we ", " wanna "}) {
			int at = s.indexOf(mark);
			if (at < 0 || mark.equals(" want to ") && !h.question) continue;
			h.suggest = true;
			String rest = s.substring(at + mark.length()).trim().replaceAll("\\b(please|with me|together|now)\\b", "").replaceAll("\\s+", " ").trim();
			String[] parts = rest.split(" ");
			h.suggestion = String.join(" ", java.util.Arrays.copyOf(parts, Math.min(parts.length, 5))).replace("my ", "your ").trim();
			break;
		}
		h.feelMe = (s.contains(" i'm ") || s.contains(" i am ") || s.contains(" i feel ")) && h.polarity != 0 && !h.aboutYou;
		h.news = s.contains(" i found ") || s.contains(" i got ") || s.contains(" i built ") || s.contains(" i made ") || s.contains(" i killed ")
				|| s.contains(" i beat ") || s.contains(" i died ") || s.contains(" look what ") || s.contains(" check this ") || s.contains(" i just ")
				|| s.contains(" guess what ");
		h.confused = w.size() <= 3 && (first.equals("what") || first.equals("huh") || first.equals("eh")) && !h.aboutYou
				|| s.contains(" what do you mean ") || s.contains(" i don't get it ") || s.contains(" makes no sense ") || s.contains(" wdym ");
		h.name = s.contains(" your name ") || s.contains(" who are you ");
		h.tellMe = s.contains(" tell me ") || s.contains(" say something ") || s.contains(" talk to me ") || s.contains(" entertain me ");
		boolean insultWord = false;
		for (String x : w) insultWord |= INSULTS.contains(x);
		insultWord |= s.contains(" shut up ") || s.contains(" go away ");
		h.insult = (h.aboutYou && h.polarity < 0 && !h.question && !h.feelMe) || insultWord && !s.contains(" not ");
		h.praise = !h.insult && (h.aboutYou && h.polarity > 0 && !h.question && !h.likeQ) || s.contains(" good job ") || s.contains(" nice job ")
				|| s.contains(" well done ") || s.contains(" good game ") || s.contains(" great job ");
		if (h.praise && h.thanks) h.praise = false;
		for (String x : w) {                                                          // what it's about: the first thing it knows a word for
			if (x.equals("mine") || x.equals("end") && !s.contains(" the end ")) continue;   // ("mine" is also "my"; "end" is usually the verb)
			Lexicon.Topic tp = lx.topic(x);
			if (tp != null) {
				h.topic = tp;
				break;
			}
		}
		return h;
	}

	/** The network's inputs: what it heard, who it is, who's talking (see {@link ActNet#F}). */
	static float[] features(Heard h, Self me, String speaker) {
		float[] x = new float[ActNet.F];
		x[0] = 1;
		boolean[] f = {h.greet, h.bye, h.thanks, h.sorry, h.praise, h.insult, h.laugh, h.yes, h.no, h.howAreYou, h.whatDoing, h.likeQ, h.suggest,
				h.feelMe, h.news, h.confused};
		for (int i = 0; i < f.length; i++) x[1 + i] = f[i] ? 1 : 0;
		x[17] = h.question && !h.flagged() ? 1 : 0;
		x[18] = !h.question && !h.flagged() ? 1 : 0;
		float like = h.topic == null ? 0 : me.like(h.topic);
		x[19] = h.topic != null ? 1 : 0;
		x[20] = like > 0.3f ? 1 : 0;
		x[21] = like < -0.3f ? 1 : 0;
		x[22] = h.polarity > 0 ? 1 : 0;
		x[23] = h.polarity < 0 ? 1 : 0;
		x[24] = h.aboutYou ? 1 : 0;
		x[25] = h.aboutMe ? 1 : 0;
		x[26] = me.chattiness();
		x[27] = me.curiosity();
		x[28] = me.kindness();
		x[29] = me.bravery();
		String temper = me.temper();
		x[30] = "friendly".equals(temper) ? 1 : 0;
		x[31] = "passive".equals(temper) ? 1 : 0;
		x[32] = "aggressive".equals(temper) ? 1 : 0;
		int tone = List.of(TONES).indexOf(me.tone());
		if (tone >= 0) x[33 + tone] = 1;
		x[39] = Math.max(-1, Math.min(1, me.trust(speaker)));
		String mood = me.mood();
		x[40] = mood.equals("pleased") || mood.equals("calm") || mood.equals("curious") ? 1 : 0;
		x[41] = mood.equals("hurt") || mood.equals("afraid") || mood.equals("terrified") || mood.equals("uneasy") ? 1 : 0;
		x[42] = me.busy() ? 1 : 0;
		return x;
	}

	static final String[] TONES = {"cheerful", "calm", "grumpy", "shy", "bold", "silly"};

	// ------------------------------------------------------------------------------------ the teacher
	/**
	 * What people usually say back, as weights over the replies: the network learns this first (then from how people
	 * take what it says). Reads the same inputs the network gets.
	 */
	static float[] teach(float[] x) {
		float[] w = new float[ActNet.A];
		java.util.Arrays.fill(w, 0.02f);
		boolean greet = x[1] > 0, bye = x[2] > 0, thanks = x[3] > 0, sorry = x[4] > 0, praise = x[5] > 0, insult = x[6] > 0, laugh = x[7] > 0,
				yes = x[8] > 0, no = x[9] > 0, how = x[10] > 0, doing = x[11] > 0, likeQ = x[12] > 0, suggest = x[13] > 0, feelMe = x[14] > 0,
				news = x[15] > 0, confused = x[16] > 0, question = x[17] > 0, statement = x[18] > 0, topic = x[19] > 0, liked = x[20] > 0,
				disliked = x[21] > 0, pos = x[22] > 0, neg = x[23] > 0, busy = x[42] > 0;
		float chat = x[26], curious = x[27], kind = x[28], brave = x[29], trust = x[39];
		boolean friendly = x[30] > 0, aggressive = x[32] > 0;
		boolean cheerful = x[33] > 0, grumpy = x[35] > 0, shy = x[36] > 0, bold = x[37] > 0, silly = x[38] > 0;
		if (greet) {
			w[ActNet.GREET] += 3;
			w[ActNet.ASK_BACK] += chat;
			w[ActNet.SHARE] += 0.4f * chat;
		}
		if (bye) w[ActNet.BYE] += 3.5f;
		if (thanks || sorry) w[ActNet.WELCOME] += 3;
		if (praise) w[ActNet.PROUD] += 3;
		if (insult) {
			w[ActNet.HURT] += 3;
			if (silly || friendly && kind > 0.6f) w[ActNet.JOKE] += 0.6f;
		}
		if (laugh) {
			w[ActNet.REACT] += 2;
			w[ActNet.JOKE] += silly || cheerful ? 1.8f : 0.4f;
		}
		if (yes) {
			w[ActNet.REACT] += 1.5f;
			w[ActNet.SHARE] += 0.4f * chat;
		}
		if (no) {
			w[ActNet.REACT] += 1.5f;
			w[ActNet.ASK_BACK] += 0.4f * curious;
		}
		if (how) {
			w[ActNet.FEELING] += 3;
			w[ActNet.ASK_BACK] += 1.2f * kind;
		}
		if (doing) {
			w[ActNet.DOING] += 3;
			w[ActNet.ASK_BACK] += 0.4f * chat;
		}
		if (likeQ) {
			if (topic) w[ActNet.OPINION] += 3.5f;
			else {
				w[ActNet.DUNNO] += 1;
				w[ActNet.SHARE] += 1.2f;
			}
		}
		if (suggest) {
			w[ActNet.ACCEPT] += 1.4f + (liked ? 1 : 0) + trust + 0.5f * kind + (cheerful ? 0.4f : 0) - (busy ? 0.6f : 0);
			w[ActNet.DECLINE] += 0.4f + (disliked ? 1.6f : 0) + (busy ? 0.8f : 0) + (grumpy ? 0.8f : 0) + (aggressive ? 0.3f : 0) - trust * 0.5f;
		}
		if (feelMe) {
			if (neg) {
				w[ActNet.CARE] += 1 + 2 * kind;
				w[ActNet.REACT] += 0.5f;
			} else {
				w[ActNet.REACT] += 2;
				w[ActNet.ASK_BACK] += curious;
			}
		}
		if (news) {
			w[ActNet.REACT] += 2;
			w[ActNet.ASK_BACK] += 1.2f * curious;
		}
		if (confused) {
			w[ActNet.DUNNO] += 1.2f;
			w[ActNet.REACT] += 0.8f;
		}
		if (question) {
			if (topic) w[ActNet.OPINION] += 1.8f;
			else w[ActNet.DUNNO] += 1.5f;
			w[ActNet.SHARE] += 0.5f * chat;
		}
		if (statement) {
			if (topic && (pos && liked || neg && disliked)) w[ActNet.AGREE] += 2.5f;
			else if (topic && (pos && disliked || neg && liked)) {
				w[ActNet.DISAGREE] += 1.2f + (bold ? 0.8f : 0) + (grumpy ? 0.8f : 0) + (aggressive ? 0.4f : 0) + 0.3f * brave;
				w[ActNet.AGREE] += shy ? 1.2f : 0.3f;
			} else if (topic) {
				w[ActNet.OPINION] += 2;
				w[ActNet.AGREE] += pos || neg ? 0.6f : 0;
			} else {
				w[ActNet.REACT] += 1.3f;
				w[ActNet.ASK_BACK] += curious;
				w[ActNet.SHARE] += 0.8f * chat;
			}
		}
		if (silly) w[ActNet.JOKE] += 0.15f;
		if (grumpy) w[ActNet.ASK_BACK] *= 0.6f;
		if (shy) w[ActNet.SHARE] *= 0.7f;
		float sum = 0;
		for (float v : w) sum += v;
		for (int i = 0; i < w.length; i++) w[i] /= sum;
		return w;
	}

	/** A made-up situation for the teacher: something someone might say, and a Xen of some nature. */
	static float[] sample(Random r) {
		float[] x = new float[ActNet.F];
		x[0] = 1;
		int kind = r.nextInt(19);                                                 // one kind of line (or two: "hi, how are you?")
		if (kind < 16) x[1 + kind] = 1;
		else x[kind == 16 ? 17 : 18] = 1;
		if (r.nextFloat() < 0.15f && kind < 16) x[1 + r.nextInt(16)] = 1;
		boolean topic = r.nextFloat() < 0.55f;
		x[19] = topic ? 1 : 0;
		if (topic) {
			float like = (float) r.nextGaussian() * 0.5f;
			x[20] = like > 0.3f ? 1 : 0;
			x[21] = like < -0.3f ? 1 : 0;
		}
		float pol = r.nextFloat();
		x[22] = pol < 0.35f ? 1 : 0;
		x[23] = pol > 0.7f ? 1 : 0;
		x[24] = r.nextFloat() < 0.5f ? 1 : 0;
		x[25] = r.nextFloat() < 0.4f ? 1 : 0;
		for (int i = 26; i <= 29; i++) x[i] = r.nextFloat();
		x[30 + r.nextInt(3)] = 1;
		x[33 + r.nextInt(6)] = 1;
		x[39] = (float) Math.max(-1, Math.min(1, r.nextGaussian() * 0.4));
		int mood = r.nextInt(3);
		x[40] = mood == 0 ? 1 : 0;
		x[41] = mood == 1 ? 1 : 0;
		x[42] = r.nextFloat() < 0.4f ? 1 : 0;
		return x;
	}

	/** A new network, taught what people usually say back (a moment: a few thousand examples). */
	public static ActNet trained(long seed) {
		Random r = new Random(seed);
		int n = 6000;
		float[][] xs = new float[n][], ys = new float[n][];
		for (int i = 0; i < n; i++) {
			xs[i] = sample(r);
			ys[i] = teach(xs[i]);
		}
		ActNet made = new ActNet(seed);
		made.train(xs, ys, 12, 0.02f, r);
		return made;
	}

	/** How often the network picks the teacher's favourite reply on new examples (for the tests). */
	public static float agreement(ActNet n, int cases, long seed) {
		Random r = new Random(seed);
		int same = 0;
		for (int i = 0; i < cases; i++) {
			float[] x = sample(r), t = teach(x), p = n.probs(x);
			if (argmax(p) == argmax(t)) same++;
		}
		return same / (float) cases;
	}

	private static int argmax(float[] v) {
		int b = 0;
		for (int i = 1; i < v.length; i++) if (v[i] > v[b]) b = i;
		return b;
	}

	// ------------------------------------------------------------------------------------ replying
	/** A sentence to be written: its words, and whether it asks. */
	private record Sent(String text, boolean ask) {}

	/**
	 * Its reply to someone: what it makes of it, what kind of reply its network picks, the words. A fact it knows
	 * ({@code grounded}: "I carry 3 iron.") comes first when there is one. {@code learn}: a player's reaction to its last
	 * reply teaches the network (Xens talking to each other don't).
	 */
	public String reply(String speaker, String text, String grounded, boolean learn) {
		Heard h = hear(text, me.name());
		lastHeard = h.toString();
		if (learn) learnFrom(speaker, h);
		List<Sent> out = new ArrayList<>();
		if (h.words.isEmpty()) {                                                      // just its name: "yes?"
			lastAct = "listen";
			out.add(new Sent(w("listen"), true));
			return write(out);
		}
		if (h.name && h.question) {
			lastAct = "name";
			out.add(new Sent("I'm " + me.name(), false));
			return write(out);
		}
		float[] x = features(h, me, speaker);
		int act = choose(net().probs(x), h);
		if (h.greet && (h.howAreYou || h.whatDoing)) {                                // "hey, how's it going?": hello, and an answer
			out.add(new Sent(w("greet"), false));
			act = h.howAreYou ? ActNet.FEELING : ActNet.DOING;
		}
		if (h.tellMe && !h.flagged()) act = h.topic != null ? ActNet.OPINION : random.nextFloat() < ("silly".equals(me.tone()) ? 0.5f : 0.2f) ? ActNet.JOKE : ActNet.SHARE;
		lastAct = ActNet.ACTS[act];
		if (learn) lastTo.put(speaker, new Last(x, act, System.currentTimeMillis()));
		if (grounded != null && !grounded.isBlank() && !h.greet && !h.thanks && !h.likeQ && !h.howAreYou) {
			out.add(new Sent(stripEnd(grounded), grounded.trim().endsWith("?")));
			if (me.chattiness() > 0.6f && random.nextFloat() < 0.4f) out.add(new Sent(w("ask_back"), true));
			return write(out);
		}
		realize(act, h, speaker, out);
		if (out.isEmpty()) realize(ActNet.REACT, h, speaker, out);
		boolean more = me.chattiness() > 0.55f && random.nextFloat() < me.chattiness() * 0.6f && act != ActNet.ASK_BACK && act != ActNet.BYE
				&& act != ActNet.JOKE && act != ActNet.HURT && act != ActNet.DECLINE && act != ActNet.WELCOME && act != ActNet.CARE && out.size() < 2;
		if (more) {                                                                  // a second sentence that stays on the subject
			int extra = h.news ? ActNet.ASK_BACK : h.feelMe && h.polarity < 0 || h.insult || act == ActNet.AGREE || act == ActNet.DISAGREE ? -1
					: act == ActNet.SHARE || act == ActNet.OPINION ? (me.curiosity() > 0.4f ? ActNet.ASK_BACK : -1)
					: me.curiosity() > 0.5f && !h.question && act != ActNet.REACT ? ActNet.ASK_BACK : ActNet.SHARE;
			if (extra == ActNet.SHARE) {
				Sent own = share(true);
				if (own != null) out.add(own);
			} else if (extra >= 0) {
				if (act == ActNet.OPINION || act == ActNet.SHARE) out.add(new Sent(w("ask_back"), true));
				else realize(extra, h, speaker, out);
			}
		}
		return write(out);
	}

	/** Chit-chat its own words answer best (hello, thanks, jokes about, how it's doing, what it thinks of something). */
	public static boolean social(Heard h) {
		return h.greet || h.bye || h.thanks || h.sorry || h.praise || h.insult || h.laugh || h.feelMe || h.howAreYou || h.whatDoing || h.news
				|| h.likeQ && h.topic != null || h.suggest && !h.question || h.tellMe;
	}

	/** Pick a reply kind: by the network's odds (sharpened a little), only kinds that fit (an opinion needs something to be about). */
	private int choose(float[] p, Heard h) {
		float[] q = new float[p.length];
		float sum = 0;
		for (int k = 0; k < p.length; k++) {
			boolean fits = switch (k) {
				case ActNet.OPINION -> h.topic != null;
				case ActNet.ACCEPT, ActNet.DECLINE -> h.suggest;
				case ActNet.WELCOME -> h.thanks || h.sorry;
				case ActNet.PROUD -> h.praise;
				case ActNet.HURT -> h.insult;
				case ActNet.AGREE, ActNet.DISAGREE -> !h.question || h.topic != null;
				case ActNet.CARE -> h.feelMe && h.polarity < 0 || h.news && h.polarity < 0;
				case ActNet.BYE -> h.bye;
				case ActNet.GREET -> h.greet;
				default -> true;
			};
			q[k] = fits ? p[k] * p[k] * p[k] : 0;
			sum += q[k];
		}
		if (sum <= 0) return ActNet.REACT;
		double x = random.nextDouble() * sum;
		for (int k = 0; k < q.length; k++) {
			x -= q[k];
			if (x <= 0) return k;
		}
		return argmax(q);
	}

	/** How they took its last reply: that teaches the network (laughing, thanks, praise: good; "what?", insults: not). */
	private void learnFrom(String speaker, Heard h) {
		Last last = lastTo.remove(speaker);
		if (last == null || System.currentTimeMillis() - last.at() > 90_000) return;
		float reward = h.laugh || h.praise || h.thanks || h.yes ? 1f : h.insult || h.confused ? -1f : h.polarity > 0 ? 0.5f : h.polarity < 0 ? -0.3f : 0.2f;
		synchronized (Voice.class) {
			net().learn(last.x(), last.act(), reward, 0.01f);
			dirty = true;
		}
	}

	private void realize(int act, Heard h, String speaker, List<Sent> out) {
		Lexicon.Topic tp = h.topic;
		switch (act) {
			case ActNet.GREET -> {
				out.add(new Sent(w("greet") + (random.nextFloat() < 0.6f ? " " + speaker : ""), false));
				if (random.nextFloat() < 0.3f + 0.5f * me.chattiness()) {
					String tail = w("greet_tail");
					out.add(new Sent(tail, tail.startsWith("what") || tail.startsWith("how")));
				}
			}
			case ActNet.BYE -> out.add(new Sent(w("bye") + (random.nextFloat() < 0.5f ? " " + speaker : ""), false));
			case ActNet.WELCOME -> out.add(new Sent(h.sorry && !h.thanks ? w("forgive") : w("welcome"), false));
			case ActNet.FEELING -> out.add(new Sent(feeling(), false));
			case ActNet.DOING -> {
				String d = me.doing();
				if (d.isEmpty()) out.add(new Sent(w("busy_idle"), false));
				else {
					out.add(new Sent("I'm " + d, false));
					if (random.nextFloat() < me.chattiness() * 0.5f) out.add(new Sent("want to come along", true));
				}
			}
			case ActNet.OPINION -> {
				if (tp != null) out.add(new Sent(opinion(tp), false));
			}
			case ActNet.AGREE -> {
				String a = w("agree");
				if (tp != null && random.nextFloat() < 0.6f) {
					float like = me.like(tp);
					String adj = adjective(tp, h.polarity != 0 ? h.polarity > 0 : like >= 0);
					a += ", " + tp.many() + " " + tp.be() + " " + adj;
				}
				out.add(new Sent(a, false));
			}
			case ActNet.DISAGREE -> {
				out.add(new Sent(w("disagree"), false));
				if (tp != null) out.add(new Sent(tp.many() + " " + tp.be() + " " + very() + adjective(tp, me.like(tp) >= 0), false));
			}
			case ActNet.ASK_BACK -> {
				if (h.feelMe && h.polarity < 0) out.add(new Sent(pickOf("what happened", "why, what's wrong", "want to talk about it"), true));
				else if (h.feelMe) out.add(new Sent(w("ask_doing"), true));
				else if (h.news && tp != null) out.add(new Sent(pickOf("where did you find ", "how did you get ") + (tp.plural() != null ? "them" : "it"), true));
				else if (h.howAreYou) out.add(new Sent(w("ask_back"), true));
				else if (tp != null && random.nextFloat() < 0.5f) out.add(new Sent(w("ask_like") + " " + tp.many(), true));
				else if (h.news || h.statement()) {
					String more = w("tell_more");
					out.add(new Sent(more, more.equals("really")));
				}
				else out.add(new Sent(w("ask_doing"), true));
			}
			case ActNet.REACT -> {
				String r = h.laugh ? w("laugh") : h.news ? (h.polarity < 0 ? w("wow_bad") : w("news_good")) : h.no ? w("okay_then")
						: h.polarity > 0 || h.yes ? w("wow_good") : h.polarity < 0 ? w("wow_bad") : h.confused ? w("confused") : h.question ? w("dunno") : w("tell_more");
				out.add(new Sent(r, h.confused && !r.isEmpty() && (r.startsWith("what") || r.startsWith("huh") || r.startsWith("come") || r.startsWith("um"))));
			}
			case ActNet.JOKE -> {
				List<String> jokes = lx().jokes;
				String j = jokes.get(random.nextInt(jokes.size()));
				for (int i = 0; i < 4 && recent.contains(j); i++) j = jokes.get(random.nextInt(jokes.size()));
				Lexicon.remember(recent, j);
				if (me.tone().equals("grumpy")) out.add(new Sent("fine, here's one", false));
				out.add(new Sent(stripEnd(j), j.trim().endsWith("?")));
			}
			case ActNet.CARE -> {
				out.add(new Sent(w("care"), false));
				String tail = w("care_tail");
				out.add(new Sent(tail, tail.startsWith("want")));
			}
			case ActNet.PROUD -> {
				String proud = w("proud");
				out.add(new Sent(proud, proud.startsWith("d-do")));
			}
			case ActNet.HURT -> out.add(new Sent(w(switch (me.temper()) {
				case "aggressive" -> "hurt_aggressive";
				case "passive" -> "hurt_passive";
				default -> "hurt_friendly";
			}), false));
			case ActNet.DUNNO -> {
				out.add(new Sent(w("dunno"), false));
				if (me.curiosity() > 0.5f && random.nextFloat() < 0.5f) out.add(new Sent(w("ask_back"), true));
			}
			case ActNet.SHARE -> {
				Sent s = share(false);
				if (s != null) out.add(s);
			}
			case ActNet.ACCEPT -> {
				String yes = w("accept");
				out.add(new Sent(yes + (h.suggestion.isEmpty() ? "" : yes.endsWith("let's") ? " " + h.suggestion : ", let's " + h.suggestion), false));
			}
			case ActNet.DECLINE -> out.add(new Sent(w("decline") + (me.doing().isEmpty() ? "" : ", I'm " + me.doing()), false));
			default -> {}
		}
	}

	private String feeling() {
		String mood = me.mood();
		String word = switch (mood) {
			case "pleased" -> pickOf("great", "happy", "good", "really good");
			case "bored" -> pickOf("bored", "a bit bored", "kind of bored");
			case "curious" -> pickOf("curious", "good, just curious", "fine");
			case "uneasy" -> pickOf("a little nervous", "okay, a bit on edge");
			case "afraid" -> pickOf("scared", "a bit scared");
			case "terrified" -> pickOf("really scared", "terrified");
			case "hurt" -> pickOf("hurt", "not great, I'm hurt");
			default -> pickOf("fine", "good", "alright", "okay");
		};
		String s = "I'm " + word;
		if (me.hungry()) s += pickOf(", and kind of hungry", ", but hungry", ", a bit hungry though");
		else if (me.hurt() && !mood.equals("hurt")) s += pickOf(", a bit hurt though", ", but I took some hits");
		return s;
	}

	/** What it thinks of something, in a sentence of its own. */
	private String opinion(Lexicon.Topic tp) {
		float like = me.like(tp);
		Lexicon.Style st = lx().style(me.tone());
		String hedge = random.nextFloat() < st.hedge() + 0.2f ? w("think") + " " : "";
		int shape = random.nextInt(3);
		if (like > 0.35f) {
			return switch (shape) {
				case 0 -> hedge + "I " + w("love") + " " + tp.many();
				case 1 -> tp.many() + " " + tp.be() + " " + very() + adjective(tp, true);
				default -> "I " + w("love") + " " + tp.many() + ", " + tp.them() + " " + tp.be() + " " + adjective(tp, true);
			};
		}
		if (like < -0.35f) {
			return switch (shape) {
				case 0 -> hedge + "I " + w("hate") + " " + tp.many();
				case 1 -> tp.many() + " " + tp.be() + " " + very() + adjective(tp, false);
				default -> w("yuck") + ", " + tp.many() + ". " + cap(tp.them()) + " " + tp.be() + " " + adjective(tp, false);
			};
		}
		if (shape == 0 || tp.pos().isEmpty() || tp.neg().isEmpty()) {
			String meh = w("meh");
			if (tp.plural() == null) meh = meh.replaceFirst("^are ", "is ");
			return hedge + tp.many() + " " + meh;
		}
		return tp.many() + " " + tp.be() + " " + adjective(tp, true) + ", but " + tp.them() + " " + tp.be() + " " + adjective(tp, false);
	}

	/** A word for it (its own, or a general one): good or bad. */
	private String adjective(Lexicon.Topic tp, boolean good) {
		List<String> own = good ? tp.pos() : tp.neg();
		if (!own.isEmpty() && random.nextFloat() < 0.8f) return own.get(random.nextInt(own.size()));
		return good ? pickOf("great", "nice", "cool", "fun", "pretty good") : pickOf("annoying", "bad news", "the worst", "no fun");
	}

	/** "really " (only before one word: "really cool", not "really good for trading"). */
	private String very() {
		return random.nextFloat() < 0.5f ? w("very") + " " : "";
	}

	/** Something about itself: its dream, what it saw, what it's doing, or ({@code selfOnly} false) something it loves or hates. */
	private Sent share(boolean selfOnly) {
		List<Sent> options = new ArrayList<>();
		String lead = random.nextFloat() < 0.4f ? w("remark_start") + ", " : "";
		if (!me.dream().isEmpty()) options.add(new Sent(lead + "I really want to " + me.dream() + " someday", false));
		if (!me.saw().isEmpty()) options.add(new Sent(lead + "I saw " + me.saw() + " earlier", false));
		if (!me.doing().isEmpty()) options.add(new Sent(lead + "I'm busy " + me.doing(), false));
		if (!selfOnly || options.isEmpty() && random.nextFloat() < 0.3f) {
			Lexicon.Topic fav = strongest(true), worst = strongest(false);
			if (fav != null) options.add(new Sent(lead + "I " + w("love") + " " + fav.many(), false));
			if (worst != null) options.add(new Sent(lead + "I " + w("hate") + " " + worst.many(), false));
		}
		return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
	}

	/** Of a few topics, the one it likes most (or least). */
	private Lexicon.Topic strongest(boolean best) {
		List<Lexicon.Topic> all = new ArrayList<>(lx().topics.values());
		Lexicon.Topic pick = null;
		float v = 0;
		for (int i = 0; i < 8 && !all.isEmpty(); i++) {
			Lexicon.Topic t = all.get(random.nextInt(all.size()));
			float l = me.like(t);
			if (best ? l > Math.max(v, 0.4f) : l < Math.min(v, -0.4f)) {
				v = l;
				pick = t;
			}
		}
		return pick;
	}

	// ------------------------------------------------------------------------------------ talking first
	/** Something to say to another Xen (or anyone) to start a chat: a question, what it thinks of something, its news, a joke. */
	public String opener(String other) {
		List<Sent> out = new ArrayList<>();
		float r = random.nextFloat() * (me.kindness() + me.curiosity() + 2 * me.chattiness() + ("silly".equals(me.tone()) ? 0.6f : 0.15f) + 0.3f);
		if ((r -= me.kindness()) < 0) {
			out.add(new Sent(w("greet") + " " + other, false));
			out.add(new Sent(pickOf("how are you", "how's it going", "you okay"), true));
		} else if ((r -= me.curiosity()) < 0) {
			out.add(new Sent(w("ask_doing"), true));
		} else if ((r -= me.chattiness()) < 0) {
			Lexicon.Topic t = strongest(random.nextBoolean());
			if (t == null) t = new ArrayList<>(lx().topics.values()).get(random.nextInt(lx().topics.size()));
			if (random.nextBoolean()) out.add(new Sent(w("ask_like") + " " + t.many(), true));
			else out.add(new Sent(opinion(t), false));
		} else if ((r -= me.chattiness()) < 0) {
			Sent s = share(false);
			if (s != null) out.add(s);
		} else {
			realize(ActNet.JOKE, hear("", null), other, out);
		}
		if (out.isEmpty()) out.add(new Sent(w("ask_doing"), true));
		lastAct = "opener";
		return write(out);
	}

	/** Something it says on its own now and then: what it thinks of something, or about itself. */
	public String remark() {
		List<Sent> out = new ArrayList<>();
		Sent s = random.nextBoolean() ? share(false) : null;
		if (s != null) out.add(s);
		else {
			Lexicon.Topic t = strongest(random.nextFloat() < 0.6f);
			if (t == null) return null;
			out.add(new Sent(opinion(t), false));
		}
		return write(out);
	}

	/**
	 * A reply to a request in its own tone: "Okay! I'll stay here." as a shy one ("O-okay... I'll stay here.") or a
	 * grumpy one ("Fine. I'll stay here."); "I can't..." likewise.
	 */
	public String ack(String line) {
		if (line == null || line.isBlank()) return line;
		Lexicon.Style st = lx().style(me.tone());
		if (line.startsWith("Okay! ")) {
			String yes = cap(w("sure_ack"));
			String end = st.end().equals("...") ? "..." : st.end().equals("!") ? "!" : ".";
			return yes + end + " " + line.substring(6);
		}
		if (line.startsWith("I can't") || line.startsWith("I won't") || line.startsWith("I don't")) {
			return cap(w("cant_ack")) + ", " + line;
		}
		return line;
	}

	// ------------------------------------------------------------------------------------ writing it down
	/** Write the sentences the way its tone writes: end marks, a stammer, lower case, a smiley, trailing off. */
	private String write(List<Sent> sents) {
		Lexicon.Style st = lx().style(me.tone());
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < sents.size(); i++) {
			String s = contract(sents.get(i).text().trim());
			if (s.isEmpty()) continue;
			boolean ask = sents.get(i).ask();
			String end = ask ? "?" : st.end();
			if (!ask && end.equals("!") && random.nextFloat() < st.extraEnd()) end = "!!";
			if (!ask && end.equals("!") && random.nextFloat() < 0.35f) end = ".";                  // (not everything is shouted)
			if (i == 0 && random.nextFloat() < st.stutter()) s = stutter(s);
			s = random.nextFloat() < st.lower() ? s : cap(s);
			if (s.endsWith(".") || s.endsWith("!") || s.endsWith("?")) end = "";
			out.append(out.length() > 0 ? " " : "").append(s).append(end);
		}
		String text = out.toString();
		if (!text.endsWith("?") && random.nextFloat() < st.trail() && !text.endsWith("...")) text = text.replaceAll("[.!]+$", "") + "...";
		if (random.nextFloat() < st.smile()) text += " " + pickOf(":)", ":D", "^^");
		text = text.replaceAll("\\bi\\b", "I").replaceAll("\\bi'm\\b", "I'm").replaceAll("\\bi'd\\b", "I'd");
		Lexicon.remember(lines, text);
		return text;
	}

	private static String contract(String s) {
		return s.replaceAll("\\b([Tt])hey are\\b", "$1hey're").replaceAll("\\b([Ii])t is\\b", "$1t's").replaceAll("\\bI am\\b", "I'm")
				.replaceAll("\\bthey is\\b", "it's").replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ");
	}

	private String stutter(String s) {
		int sp = s.indexOf(' ');
		String firstWord = sp < 0 ? s : s.substring(0, sp);
		if (firstWord.isEmpty() || !Character.isLetter(firstWord.charAt(0)) || firstWord.contains("-")) return s;
		return firstWord.charAt(0) + "-" + s;
	}

	static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String stripEnd(String s) {
		return s.trim().replaceAll("[.!?]+$", "");
	}

	private String pickOf(String... options) {
		return options[random.nextInt(options.length)];
	}
}
