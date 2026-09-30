package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Xen has worked out about playing, as opposed to the rules of the game ({@link Knowledge}): things like "iron is
 * best found around y 16". Each is a belief with where it came from (what every new player half-knows, its own
 * experience, another Xen, a player), how sure it is, who taught it and how often its own experience bore it out. A
 * new Xen only has a rough idea (not the wiki in its head: a little off, and not very sure). What it finds itself
 * moves the belief; what another Xen tells it moves it too, as far as it trusts them and they sound sure; and it passes
 * on what it has come to believe. So beliefs spread from Xen to Xen, sometimes wrong (a Xen that found iron twice up
 * a hill will say iron is up high), and experience puts them right again. Its mining trips go to the depth it believes.
 * Kept with the Xen.
 */
final class Lessons {
	enum Source { BORN, EXPERIENCE, XEN, PLAYER }

	/** A belief: what about, the value (a height), where it came from, how sure, who taught it, how often borne out. */
	static final class Lesson {
		final String subject;
		double value;
		/** What it believed before its own finds (born, or told): its finds move the belief away from this. */
		double prior;
		float priorSure;
		Source source;
		float sure;
		String teacher;
		int confirmed, contradicted;
		final List<Integer> finds = new ArrayList<>();

		Lesson(String subject, double value, Source source, float sure, String teacher) {
			this.subject = subject;
			this.value = this.prior = value;
			this.source = source;
			this.sure = this.priorSure = sure;
			this.teacher = teacher;
		}

		int y() {
			return (int) Math.round(value);
		}
	}

	/**
	 * The subjects, with what's true in this version of the game (1.18 and later: the height where the ore is most
	 * common, for a player digging tunnels) and how far off a new Xen's idea of it may be. Only here: the reasoning
	 * doesn't know these numbers, only the beliefs do.
	 */
	private static final Map<String, double[]> SUBJECTS = new LinkedHashMap<>();
	static {
		SUBJECTS.put("iron", new double[] {16, 10});
		SUBJECTS.put("diamonds", new double[] {-58, 7});
		SUBJECTS.put("coal", new double[] {44, 14});
	}

	private final Companion c;
	final Map<String, Lesson> lessons = new LinkedHashMap<>();

	Lessons(Companion c) {
		this.c = c;
	}

	/** A new Xen's rough idea: the right height give or take, not very sure (a later generation is born knowing it better). */
	void born(Random r) {
		for (var e : SUBJECTS.entrySet()) {
			if (lessons.containsKey(e.getKey())) continue;
			int gen = c.personality.generation;
			double off = r.nextGaussian() * e.getValue()[1] / (1 + 0.5 * gen);
			lessons.put(e.getKey(), new Lesson(e.getKey(), clampY(e.getKey(), e.getValue()[0] + off), Source.BORN, 0.3f, null));
		}
	}

	private static double clampY(String subject, double y) {
		return subject.equals("diamonds") ? Math.max(-60, Math.min(0, y)) : Math.max(-50, Math.min(120, y));
	}

	/** The height it goes mining at for this ore (what it believes), or the given one if it has no belief. */
	int depth(String what, int otherwise) {
		Lesson l = lessons.get(what.equals("diamond") ? "diamonds" : what);
		return l == null ? otherwise : l.y();
	}

	/** How sure it is of where this ore is (0: no idea). */
	float sure(String what) {
		Lesson l = lessons.get(what.equals("diamond") ? "diamonds" : what);
		return l == null ? 0 : l.sure;
	}

	/** It broke an ore at this height: its own experience (the belief moves toward where it finds them). */
	void found(String ore, int y) {
		String subject = ore.contains("diamond") ? "diamonds" : ore.contains("iron") ? "iron" : ore.contains("coal") ? "coal" : null;
		if (subject == null) return;
		Lesson l = lessons.computeIfAbsent(subject, s -> new Lesson(s, y, Source.EXPERIENCE, 0.3f, null));
		if (l.source == Source.XEN || l.source == Source.PLAYER) {                  // what it was told: borne out, or not
			if (Math.abs(y - l.prior) <= 10) {
				l.confirmed++;
				if (l.confirmed == 2 && l.teacher != null) {
					c.journal("learns", l.teacher + " was right: " + subject + " around y " + (int) l.prior);
					Companion t = teacherNear(l.teacher);
					if (t != null && t.player() != null) c.trust(t.player().getUUID(), 0.03f);
				}
			} else if (Math.abs(y - l.prior) > 20) {
				l.contradicted++;
			}
		}
		l.finds.add(y);
		if (l.finds.size() > 24) l.finds.remove(0);
		int n = l.finds.size();
		double mean = 0;
		for (int f : l.finds) mean += f;
		mean /= n;
		double own = (double) n / (n + 3);                                             // its own finds count for more and more
		double before = l.value;
		l.value = clampY(subject, own * mean + (1 - own) * l.prior);
		l.sure = Math.min(0.95f, (float) ((1 - own) * l.priorSure + own * (0.4 + 0.5 * n / (n + 4.0))));
		if (n >= 3 && l.source != Source.EXPERIENCE && Math.abs(mean - l.prior) > 12) {  // what it was told doesn't hold: its own now
			c.journal("learns", "not what it was told: " + subject + " is more around y " + Math.round(mean) + ", not " + (int) l.prior);
			l.source = Source.EXPERIENCE;
			l.teacher = null;
		} else if (n >= 3 && l.source == Source.BORN) {
			l.source = Source.EXPERIENCE;
		}
		if (Math.abs(l.value - before) >= 6) c.journal("learns", subject + ": now thinks around y " + l.y() + " (" + n + " found)");
	}

	private Companion teacherNear(String name) {
		for (Companion o : c.mod.companions) if (o.name.equals(name)) return o;
		return null;
	}

	/**
	 * Told by another Xen or a player: the belief moves toward what they said, as far as they sounded sure, it trusts
	 * them, and it isn't already sure itself. What it answers.
	 */
	String told(String subject, int y, Source source, String by, float theirSure, float trust) {
		Lesson l = lessons.computeIfAbsent(subject, s -> new Lesson(s, y, source, 0.2f, by));
		double t = Math.max(0, Math.min(1, (trust + 1) / 2));                       // (-1..1 to 0..1)
		double take = theirSure * (0.3 + 0.7 * t) * (1 - 0.6 * l.sure);
		boolean ownFinds = l.finds.size() >= 3 && l.sure > theirSure;
		if (ownFinds) take *= 0.3;                                                   // it found its own: it's hard to talk round
		double before = l.value;
		l.value = l.prior = clampY(subject, l.value + take * (y - l.value));
		l.priorSure = l.sure = Math.max(l.sure * 0.9f, (float) (theirSure * 0.6 * (0.5 + t)));
		if (take > 0.15) {
			l.source = source;
			l.teacher = by;
			l.confirmed = l.contradicted = 0;
		}
		c.journal("learns", by + " says " + subject + " around y " + y + ": now thinks y " + l.y() + " (was " + Math.round(before) + ", "
				+ String.format(Locale.ROOT, "%.0f%% sure", l.sure * 100) + ")");
		if (ownFinds) return c.pick3("Hm. That's not what I found, but okay.", "Really? I had better luck around y " + Math.round(before) + ".",
				"I'll keep that in mind. I'm not sure, though.");
		if (take < 0.1) return c.pick3("Hm, maybe.", "If you say so.", "Okay...");
		return c.pick3("Around y " + y + "? I'll try that!", "Thanks, I'll mine there next time.", "Good to know. y " + y + " it is.");
	}

	/**
	 * Something worth passing on to this Xen (a belief it holds for a reason: its own finds, or borne out, that the other
	 * sees quite differently): {words, subject, y, sure}, or null.
	 */
	String[] tipFor(Lessons other) {
		for (Lesson l : lessons.values()) {
			boolean earned = l.finds.size() >= 2 || l.confirmed >= 1;
			boolean hearsay = !earned && l.teacher != null && (l.source == Source.PLAYER || l.source == Source.XEN) && l.sure >= 0.55f;   // (told, not tried: passed on too, right or wrong)
			if (!earned && !hearsay || l.sure < 0.4f) continue;
			Lesson theirs = other.lessons.get(l.subject);
			if (theirs != null && Math.abs(theirs.value - l.value) < 8) continue;
			boolean deeper = theirs != null && l.value < theirs.value;
			String what = l.subject.equals("diamonds") ? "diamonds" : l.subject;
			String words = l.finds.size() >= 2
					? c.pick3("Mining " + (theirs == null ? "" : deeper ? "deeper, " : "higher, ") + "around y " + l.y() + " worked better for me for " + what + ".",
							"I find " + what + " around y " + l.y() + ". Try there.", "For " + what + ", y " + l.y() + ". Trust me.")
					: earned ? "For " + what + ", " + l.teacher + " told me y " + l.y() + ", and it worked."
					: c.pick3(l.teacher + " told me " + what + (what.equals("diamonds") ? " are" : " is") + " best around y " + l.y() + ". Haven't tried it yet.",
							"Heard from " + l.teacher + ": " + what + " at y " + l.y() + ".", "For " + what + ", " + l.teacher + " says y " + l.y() + ". Worth a try?");
			return new String[] {words, l.subject, "" + l.y(), "" + (hearsay ? 0.7f * l.sure : l.sure)};
		}
		return null;
	}

	/** A player telling it where to mine ("iron is best around y 16", "diamonds at y -58"): subject and y, or null. */
	private static final Pattern PLAYER_TIP = Pattern.compile("\\b(iron|diamonds?|coal)\\b[^0-9\\-]{0,40}?(?:\\by\\s*=?\\s*|\\blevel\\s+|\\bat\\s+)(-?\\d{1,3})\\b");

	/** A player told it where an ore is best: it takes it in (as far as it trusts them). What it says, or null. */
	String heard(String words, String player, float trust) {
		Matcher m = PLAYER_TIP.matcher(words.toLowerCase(Locale.ROOT));
		if (!m.find()) return null;
		String subject = m.group(1).startsWith("diamond") ? "diamonds" : m.group(1);
		int y;
		try {
			y = Integer.parseInt(m.group(2));
		} catch (NumberFormatException e) {
			return null;
		}
		if (y < -64 || y > 320) return null;
		return told(subject, y, Source.PLAYER, player, 0.75f, trust);
	}

	/** What it believes about mining, where from and how sure ("where do you mine iron?"). */
	String describe(String only) {
		StringBuilder b = new StringBuilder();
		for (Lesson l : lessons.values()) {
			if (only != null && !l.subject.equals(only)) continue;
			if (b.length() > 0) b.append(' ');
			String sure = l.sure >= 0.75f ? "I'm sure" : l.sure >= 0.5f ? "pretty sure" : l.sure >= 0.35f ? "I think" : "just a guess";
			String from = switch (l.source) {
				case BORN -> "no idea really, it's what everyone says";
				case EXPERIENCE -> "I found " + l.finds.size() + " there myself";
				case XEN, PLAYER -> l.teacher + " told me" + (l.confirmed > 0 ? ", and it held up" : l.finds.isEmpty() ? ", haven't checked yet" : "");
			};
			b.append(Character.toUpperCase(l.subject.charAt(0))).append(l.subject.substring(1)).append(": around y ").append(l.y())
					.append(" (").append(sure).append("; ").append(from).append(").");
		}
		return b.length() == 0 ? "I haven't worked out much about mining yet." : b.toString();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		for (Lesson l : lessons.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("value", l.value);
			j.addProperty("prior", l.prior);
			j.addProperty("priorSure", l.priorSure);
			j.addProperty("source", l.source.name());
			j.addProperty("sure", l.sure);
			if (l.teacher != null) j.addProperty("teacher", l.teacher);
			j.addProperty("confirmed", l.confirmed);
			j.addProperty("contradicted", l.contradicted);
			JsonArray f = new JsonArray();
			for (int y : l.finds) f.add(y);
			j.add("finds", f);
			o.add(l.subject, j);
		}
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		for (var e : o.entrySet()) {
			try {
				JsonObject j = e.getValue().getAsJsonObject();
				Lesson l = new Lesson(e.getKey(), j.get("value").getAsDouble(), Source.valueOf(j.get("source").getAsString()), j.get("sure").getAsFloat(),
						j.has("teacher") ? j.get("teacher").getAsString() : null);
				l.prior = j.get("prior").getAsDouble();
				l.priorSure = j.get("priorSure").getAsFloat();
				l.confirmed = j.get("confirmed").getAsInt();
				l.contradicted = j.get("contradicted").getAsInt();
				for (var f : j.getAsJsonArray("finds")) l.finds.add(f.getAsInt());
				lessons.put(l.subject, l);
			} catch (RuntimeException ignored) {
				// an entry from another version: it starts that one afresh
			}
		}
	}
}
