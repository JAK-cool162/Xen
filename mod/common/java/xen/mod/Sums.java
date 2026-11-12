package xen.mod;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Xen's head for numbers: the one place its sums are worked out, so a tax, a rent, a fair split of its things and
 * a sum someone asks it all come out the same, and right (worked out, never guessed). Whole items: shares are whole,
 * and what's left over goes one each to the first ones.
 */
final class Sums {
	private Sums() {}

	/** n percent of a whole number of items: rounded to the nearest, at least one if there's any to take. */
	static int percent(int of, float pct) {
		if (of <= 0 || pct <= 0) return 0;
		return Math.max(1, Math.round(of * pct / 100f));
	}

	/** total split between ways: as even as whole items go, the rest one each to the first ones (7 by 3: 3, 2, 2). */
	static int[] split(int total, int ways) {
		int[] out = new int[Math.max(1, ways)];
		for (int i = 0; i < out.length; i++) out[i] = total / out.length + (i < total % out.length ? 1 : 0);
		return out;
	}

	private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s*of\\s*(\\d+(?:\\.\\d+)?)");
	private static final Pattern PART = Pattern.compile("\\b(half|a third|a quarter|a fourth|a tenth)\\s+of\\s+(\\d+(?:\\.\\d+)?)");
	private static final Pattern SPLIT = Pattern.compile("\\b(?:split|divide|share)\\s+(\\d+)\\s*([a-z_ ]*?)\\s*(?:between|among|amongst|by|into|for|with)\\s+(\\d+)");
	private static final Pattern STACKS = Pattern.compile("\\bhow many stacks\\b.*?(\\d+)|\\b(\\d+)\\s*(?:items?\\s*)?(?:is|are|in|to)\\s*(?:how many\\s*)?stacks\\b");
	private static final Pattern ASKED = Pattern.compile("(?:what(?:'s| is)|how much is|calculate|work out|whats)\\s+(.+?)\\s*\\??$");
	private static final Pattern BARE = Pattern.compile("^[\\d\\s.+\\-*/x×÷()^]+\\??$");

	/** The answer to a sum someone asked ("15% of 64", "what's 12*4", "split 30 between 4", "how many stacks is 200"), or null if it isn't one. */
	static String answer(String words) {
		String w = words.toLowerCase(Locale.ROOT).replace(',', ' ').trim();
		Matcher m = PERCENT.matcher(w);
		if (m.find()) {
			double v = Double.parseDouble(m.group(1)) * Double.parseDouble(m.group(2)) / 100;
			return m.group(1) + "% of " + m.group(2) + " is " + num(v) + (v != Math.floor(v) ? " (" + Math.round(v) + " if it's items)." : ".");
		}
		m = PART.matcher(w);
		if (m.find()) {
			double d = switch (m.group(1)) { case "half" -> 2; case "a third" -> 3; case "a tenth" -> 10; default -> 4; };
			double v = Double.parseDouble(m.group(2)) / d;
			return capital(m.group(1)) + " of " + m.group(2) + " is " + num(v) + ".";
		}
		m = SPLIT.matcher(w);
		if (m.find()) {
			int total = Integer.parseInt(m.group(1)), ways = Integer.parseInt(m.group(3));
			if (ways <= 0) return null;
			int[] s = split(total, ways);
			String what = m.group(2).isBlank() ? "" : " " + m.group(2).trim();
			return total % ways == 0 ? total + what + " between " + ways + " is " + s[0] + " each."
					: total + what + " between " + ways + " is " + s[s.length - 1] + " each, and " + total % ways + " of you get one more (" + join(s) + ").";
		}
		m = STACKS.matcher(w);
		if (m.find()) {
			int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
			return n + " is " + n / 64 + (n / 64 == 1 ? " stack" : " stacks") + (n % 64 > 0 ? " and " + n % 64 : "") + " (64 a stack).";
		}
		String expr = null;
		m = ASKED.matcher(w);
		if (m.find() && m.group(1).matches(".*\\d.*") && m.group(1).matches("[\\d\\s.+\\-*/x×÷()^]+|.*\\b(plus|minus|times|divided by)\\b.*")) expr = m.group(1);
		else if (BARE.matcher(w).matches() && w.matches(".*\\d\\s*[+\\-*/x×÷^]\\s*\\d.*")) expr = w;
		if (expr == null) return null;
		try {
			double v = new Calc(expr.replace("?", "").replace("divided by", "/").replace("times", "*").replace("plus", "+").replace("minus", "-")
					.replace("×", "*").replace("÷", "/").replaceAll("(?<=\\d)\\s*x\\s*(?=\\d)", "*")).value();
			if (Double.isNaN(v) || Double.isInfinite(v)) return "Can't divide by zero.";
			return expr.replace("?", "").trim() + " is " + num(v) + ".";
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String num(double v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
		return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private static String capital(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String join(int[] s) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length; i++) b.append(i == 0 ? "" : ", ").append(s[i]);
		return b.toString();
	}

	/** + - * / ^ and brackets, the usual order. */
	private static final class Calc {
		private final String s;
		private int i;

		Calc(String s) {
			this.s = s.replace(" ", "");
		}

		double value() {
			double v = sum();
			if (i != s.length()) throw new IllegalArgumentException("not a sum");
			return v;
		}

		private double sum() {
			double v = product();
			while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) v = s.charAt(i++) == '+' ? v + product() : v - product();
			return v;
		}

		private double product() {
			double v = power();
			while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) v = s.charAt(i++) == '*' ? v * power() : v / power();
			return v;
		}

		private double power() {
			double v = unit();
			if (i < s.length() && s.charAt(i) == '^') {
				i++;
				v = Math.pow(v, power());
			}
			return v;
		}

		private double unit() {
			if (i < s.length() && s.charAt(i) == '-') {
				i++;
				return -unit();
			}
			if (i < s.length() && s.charAt(i) == '(') {
				i++;
				double v = sum();
				if (i >= s.length() || s.charAt(i++) != ')') throw new IllegalArgumentException("no )");
				return v;
			}
			int from = i;
			while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
			if (from == i) throw new IllegalArgumentException("no number");
			return Double.parseDouble(s.substring(from, i));
		}
	}
}
