package xen.mod.talk;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xen 2.0: how players actually type in chat (from real server chats: short lines, "gonna", "ngl", "idk", no full
 * stop, often no capital, "lol" now and then). Each Xen types its own way: casual 0 writes like a book, casual 1 like
 * someone on their phone between two jumps. Some things never change: what it says stays what it says, coordinates
 * stay exact, and nothing is said in decimals a person wouldn't use ("2.7 blocks away" is "3 blocks away").
 */
public final class Texting {
	private Texting() {}

	private static final Pattern DECIMAL_UNIT = Pattern.compile("(\\d+)\\.\\d+( blocks?| seconds?| minutes?| hearts?| m\\b)");
	/** Contractions everyone uses, only before another word ("here I am", "I know what it is" stay as they are). */
	private static final String[][] ALWAYS = {
			{"\\bI am(?= [a-z])", "I'm"}, {"\\bI do not(?= [a-z])", "I don't"}, {"\\bdo not(?= [a-z])", "don't"}, {"\\bI will(?= [a-z])", "I'll"},
			{"\\bcannot\\b", "can't"}, {"\\bit is(?= [a-z])", "it's"}, {"\\bthat is(?= [a-z])", "that's"}};
	/** Player shorthand: {pattern, replacement, how casual it takes (0 to 1)}. */
	private static final String[][] SHORT = {
			{"\\bgoing to\\b", "gonna", "0.25"}, {"\\bwant to\\b", "wanna", "0.3"}, {"\\bgot to\\b", "gotta", "0.35"},
			{"\\bokay\\b", "ok", "0.2"}, {"\\bOkay\\b", "Ok", "0.2"}, {"\\bto be honest\\b", "tbh", "0.5"}, {"\\bI don't know\\b", "idk", "0.6"},
			{"\\bright now\\b", "rn", "0.65"}, {"\\bbecause\\b", "cuz", "0.6"}, {"\\bprobably\\b", "prob", "0.7"}, {"\\bthanks\\b", "thx", "0.75"},
			{"\\bThanks\\b", "Thx", "0.75"}, {"\\byou\\b", "u", "0.9"}};

	/** Decimals a person wouldn't say, rounded ("2.7 blocks" -> "3 blocks"). */
	public static String plainNumbers(String text) {
		Matcher m = DECIMAL_UNIT.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String whole = m.group();
			double v = Double.parseDouble(whole.substring(0, whole.length() - m.group(2).length()));
			m.appendReplacement(sb, Matcher.quoteReplacement(Math.round(v) + m.group(2)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/** The line, typed by someone this casual. */
	public static String casual(String text, float casual, Random r) {
		if (text == null || text.isBlank()) return text;
		String s = plainNumbers(text);
		for (String[] a : ALWAYS) s = s.replaceAll(a[0], a[1]);
		if (casual <= 0.05f) return s;
		for (String[] a : SHORT) {
			float need = Float.parseFloat(a[2]);
			if (casual >= need && r.nextFloat() < 0.4f + casual - need) s = s.replaceAll(a[0], a[1]);
		}
		boolean question = s.endsWith("?");
		if (s.endsWith(".") && !s.endsWith("...") && r.nextFloat() < casual) s = s.substring(0, s.length() - 1);   // no full stop
		if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && !s.startsWith("I ") && !s.startsWith("I'") && r.nextFloat() < casual * 0.8f
				&& (s.length() < 2 || !Character.isUpperCase(s.charAt(1)))) {
			s = Character.toLowerCase(s.charAt(0)) + s.substring(1);            // no capital
		}
		if (!question && s.length() < 60 && Character.isLetter(s.charAt(s.length() - 1)) && r.nextFloat() < casual * 0.08f) s = s + (r.nextBoolean() ? " lol" : " ngl");
		return s;
	}

	/** Short reactions players type, that get a short one back. */
	public static final java.util.Set<String> SHORT_REACTIONS = java.util.Set.of("lol", "lmao", "lmfao", "true", "damn", "for real", "fr", "real", "facts",
			"bruh", "hell nah", "hell nahh", "nah", "ikr", "oh i see", "i see", "same", "mood", "based", "w", "l");
	public static final String[] LAUGHS = {"lol", "LMAO", "lmao", "LOL", "haha", "loll"};
	public static final String[] AGREES = {"true", "fr", "real", "ikr", "for real", "facts", "yeah"};
	public static final String[] BANTER = {"rude lol", "wow ok", "hell nah", "love u too", "owo", "ok that's fair lol", "LMAO rude", "i'm telling"};

	/** An insult that's a joke: laughing, "jk", or all in capitals (friends yelling for fun). */
	public static boolean banter(String text) {
		String t = text.toLowerCase(java.util.Locale.ROOT);
		boolean caps = text.chars().filter(Character::isLetter).count() >= 6 && text.equals(text.toUpperCase(java.util.Locale.ROOT));
		return caps || t.contains("lol") || t.contains("lmao") || t.contains("jk") || t.contains("haha") || t.contains("xd") || t.contains("\uD83D\uDE2D");
	}

	/** Praise the way players give it ("this is peak", "fire bro", "W build", "goated"). */
	public static boolean slangPraise(String lowerPadded) {
		return lowerPadded.contains(" peak ") || lowerPadded.contains(" fire ") || lowerPadded.contains(" goated ") || lowerPadded.contains(" w build ")
				|| lowerPadded.contains(" very w ") || lowerPadded.matches(".* w (set|base|house|build|xen|move)s? .*") || lowerPadded.contains(" so clean ")
				|| lowerPadded.contains(" looks comfy ") || lowerPadded.contains(" lovely ") || lowerPadded.contains(" amazing ") || lowerPadded.contains(" insane ")
				|| lowerPadded.trim().equals("w") || lowerPadded.contains(" based ");
	}
}
