package xen.mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a Xen's time goes: for each kind of thing its mind chose (mining, a shelter for the night, wood...), the game
 * minutes it spent on it, how many times it went for it, how many of those came to something (it got further) and how
 * many ended in its death. {@code /xen time} adds them up for one Xen or all of them: the honest answer to "what does
 * it actually do all day?".
 */
final class Diary {
	static final class Line {
		float minutes;
		int tries, worked, died;
	}

	final Map<String, Line> lines = new LinkedHashMap<>();

	void spent(String what, float minutes, boolean worked, boolean died) {
		Line l = lines.computeIfAbsent(what, k -> new Line());
		l.minutes += Math.max(0, minutes);
		l.tries++;
		if (worked) l.worked++;
		if (died) l.died++;
	}

	/** One Xen's diary, or several added up: the biggest share of the time first. */
	static String describe(String who, List<Diary> diaries) {
		Map<String, Line> sum = new LinkedHashMap<>();
		float total = 0;
		for (Diary d : diaries) {
			for (var e : d.lines.entrySet()) {
				Line s = sum.computeIfAbsent(e.getKey(), k -> new Line()), l = e.getValue();
				s.minutes += l.minutes;
				s.tries += l.tries;
				s.worked += l.worked;
				s.died += l.died;
				total += l.minutes;
			}
		}
		if (sum.isEmpty()) return who + ": nothing chosen yet.";
		List<Map.Entry<String, Line>> list = new ArrayList<>(sum.entrySet());
		list.sort((a, b) -> Float.compare(b.getValue().minutes, a.getValue().minutes));
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s: %.0f game minutes of its own choices", who, total));
		for (var e : list) {
			Line l = e.getValue();
			sb.append(String.format(Locale.ROOT, "\n  %-8s %5.1f min (%2.0f%%) %3d times, %2.0f%% got somewhere%s", e.getKey(), l.minutes, total == 0 ? 0 : 100 * l.minutes / total,
					l.tries, l.tries == 0 ? 0 : 100f * l.worked / l.tries, l.died > 0 ? ", died " + l.died + "x" : ""));
		}
		return sb.toString();
	}
}
