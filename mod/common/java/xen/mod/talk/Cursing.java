package xen.mod.talk;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swearing, the way players swear in chat: "oh shit", "wtf", "fucking creepers", "this is so damn far", "ow, fuck!",
 * "damn it". How much: how foul its mouth is (a hidden stat) and how angry it is right now (see the mod's Rage); a
 * clean-mouthed Xen hardly ever, a foul-mouthed one that's furious in most lines. Only the words players use about the
 * game and each other in a heated moment: never a slur, never about who someone is. What it says stays what it says
 * (the swearing goes around the words, it doesn't change what they mean), and a line already swearing isn't made worse.
 */
public final class Cursing {
	private Cursing() {}

	/** {pattern, its replacements (one picked)}: words a swearer says differently. */
	private static final String[][] SWAPS = {
			{"\\b[Oo]h no\\b", "oh shit|oh fuck|oh crap"},
			{"\\b[Ww]hat\\?$", "wtf?|what the hell?|what the fuck?"},
			{"\\b[Ww]hat was that\\b", "what the hell was that|wtf was that"},
			{"^[Nn]ope\\b", "hell nah|fuck no|hell no"},
			{"^[Nn]o way\\b", "no fucking way|hell no"},
			{"\\b[Rr]eally(?= (hard|far|annoying|dark|close|bad|good|nice|cool|tired|hungry|scary|weird|slow|lucky|stuck|lost|need|want|hate)\\b)", "fucking|really fucking|so damn"},
			{"\\b[Vv]ery(?= [a-z])", "fucking|damn|so damn"},
			{"\\b[Ss]o(?= (hard|far|annoying|dark|close|many|much|bad|good|nice|cool|tired|hungry|done|lost|stuck|scary|weird|slow|lucky|big)\\b)", "so damn|so fucking"},
			{"\\bheck\\b", "hell"},
			{"\\b(freaking|frickin|flipping)\\b", "fucking"},
			{"\\bfrick\\b", "fuck"},
			{"\\bdarn\\b", "damn"},
			{"\\b(stupid|dumb)\\b", "fucking $1|$1 as hell"},
			{"\\bannoying\\b", "annoying as hell|so fucking annoying"},
			{"\\b(creeper|skeleton|zombie|spider|phantom|enderman|witch|drowned|pillager|slime)(s?)\\b", "fucking $1$2|damn $1$2"},
			{"^(Ow|Ouch|Ugh|Argh|Agh)\\b", "$1, fuck|$1 shit|$1, damn"},
			{"\\b[Ss]hut up\\b", "shut the fuck up|stfu"},
			{"\\b[Gg]o away\\b", "fuck off|piss off"},
	};
	private static final Pattern[] PATTERNS = new Pattern[SWAPS.length];
	static {
		for (int i = 0; i < SWAPS.length; i++) PATTERNS[i] = Pattern.compile(SWAPS[i][0]);
	}
	/** Already swearing: left as it is. */
	private static final Pattern SWEARING = Pattern.compile("(?i)\\b(fuck\\w*|shit\\w*|damn\\w*|hell|wtf|ffs|stfu|fml|crap|ass|piss\\w*|bastard)\\b");
	/** A line it starts with, hurt or furious. */
	private static final String[] OPENERS = {"shit, ", "fuck, ", "damn, ", "ffs ", "bro wtf, "};
	/** One it ends a line with, furious. */
	private static final String[] TAILS = {" ffs", ", damn it", " wtf", ", fuck this", " fml"};

	/**
	 * The line, as a Xen that swears this much would type it. amount: 0 (never) to 1 (a foul mouth at its angriest);
	 * anger 0 to 1. One swear word a line (two when it's furious): a word said differently, else one at the start (hurt,
	 * heated), and when it's furious one at the end.
	 */
	public static String swear(String text, float amount, float anger, Random r) {
		if (text == null || text.isBlank() || amount <= 0.02f || SWEARING.matcher(text).find()) return text;
		if (text.matches(".*-?\\d+ -?\\d+ -?\\d+.*")) return text;           // (coordinates: said straight)
		int most = anger >= 0.7f ? 2 : 1, made = 0;
		String s = text;
		for (int i = 0; i < SWAPS.length && made < most; i++) {
			Matcher m = PATTERNS[i].matcher(s);
			if (!m.find() || r.nextFloat() >= 0.25f + 0.65f * amount) continue;
			String[] ways = SWAPS[i][1].split("\\|");
			String way = ways[r.nextInt(ways.length)];
			if (way.contains("$")) {
				for (int g = m.groupCount(); g >= 1; g--) way = way.replace("$" + g, m.group(g) == null ? "" : m.group(g));
			}
			boolean capital = Character.isUpperCase(m.group().charAt(0)) && m.start() == 0;
			if (capital) way = Character.toUpperCase(way.charAt(0)) + way.substring(1);
			s = s.substring(0, m.start()) + way + s.substring(m.end());
			made++;
		}
		boolean heated = s.endsWith("!") || anger >= 0.4f;
		if (made == 0 && heated && r.nextFloat() < amount * (0.25f + 0.6f * anger) * (s.endsWith("!") ? 1f : 0.5f)) {   // hurt, heated: a word to start with ("shit, a creeper!")
			boolean keep = s.startsWith("I ") || s.startsWith("I'") || s.length() > 1 && Character.isUpperCase(s.charAt(1));
			s = OPENERS[r.nextInt(OPENERS.length)] + (keep ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1));
			made++;
		}
		if (made < most && (s.endsWith("!") || anger >= 0.8f) && !s.endsWith("?") && r.nextFloat() < amount * anger * 0.6f) {   // furious: one to end with too
			String end = s.replaceAll("[.!]+$", "");
			String t = TAILS[r.nextInt(TAILS.length)];
			if (!end.toLowerCase(java.util.Locale.ROOT).contains(t.replaceAll("[^a-z ]", "").trim())) s = end + t + (s.endsWith("!") ? "!" : "");   // (not "ffs ... ffs")
		}
		return s;
	}
}
