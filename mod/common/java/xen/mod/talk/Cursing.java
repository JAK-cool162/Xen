package xen.mod.talk;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swearing, the way players swear in chat, angry or not: "oh shit", "wtf", "fucking creepers", "this shit is so far",
 * "hell yeah", "ow, fuck!", "damn it". How much: how foul its mouth is (a hidden stat) and how angry it is right now
 * (see the mod's Rage); a clean-mouthed Xen hardly ever, a foul-mouthed one in a good share of its lines just talking,
 * and in most of them when it's furious. Only the words players use about the
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
			{"^[Yy](es|eah|ep)\\b", "hell yeah|fuck yeah|hell yes"},
			{"^[Nn]o\\b(?!t)", "hell no|fuck no|nah fuck that"},
			{"^[Ww]hy\\b", "why the hell|why tf|why the fuck"},
			{"^[Ww]here\\b", "where the hell|where tf"},
			{"^[Ww]hat(?= (are|is|was|do|did|happened|the))\\b", "what the hell|wtf|what the fuck"},
			{"^[Ww]ow\\b", "holy shit|damn|holy fuck"},
			{"\\b(awesome|cool|great|nice|sick)\\b", "fucking $1|$1 as hell|damn $1"},
			{"\\b[Tt]his(?= (is|was) )", "this shit"},
			{"\\bI'm (tired|hungry|lost|bored|done|starving|dead)\\b", "I'm so fucking $1|I'm $1 as hell|damn I'm $1"},
			{"\\b(lucky|unlucky)\\b", "fucking $1|$1 as hell"},
	};
	private static final Pattern[] PATTERNS = new Pattern[SWAPS.length];
	static {
		for (int i = 0; i < SWAPS.length; i++) PATTERNS[i] = Pattern.compile(SWAPS[i][0]);
	}
	/** Already swearing: left as it is. */
	private static final Pattern SWEARING = Pattern.compile("(?i)\\b(fuck\\w*|shit\\w*|damn\\w*|hell|wtf|ffs|stfu|fml|crap|ass|piss\\w*|bastard)\\b");
	/** A line it starts with, hurt or furious. */
	private static final String[] OPENERS = {"shit, ", "fuck, ", "damn, ", "ffs ", "bro wtf, "};
	/** And just talking. */
	private static final String[] CASUAL = {"damn, ", "bro wtf, ", "shit, ", "hell, ", "fuck it, "};
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
		float open = heated ? amount * (0.25f + 0.6f * anger) * (s.endsWith("!") ? 1f : 0.5f) : amount * 0.18f;   // (heated: often; just talking: now and then)
		if (made == 0 && r.nextFloat() < open) {                                  // a word to start with ("shit, a creeper!", "damn, this is far")
			boolean keep = s.startsWith("I ") || s.startsWith("I'") || s.length() > 1 && Character.isUpperCase(s.charAt(1));
			String[] from = heated ? OPENERS : CASUAL;
			s = from[r.nextInt(from.length)] + (keep ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1));
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
