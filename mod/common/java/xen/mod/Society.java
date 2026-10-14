package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xen.mod.core.Sins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What grows when Xens live together long enough, the way it did in Altera's Project Sid (1,000 agents in one
 * Minecraft world): a constitution they can change, opinions that spread by talk, and faiths that spread by preaching.
 * Nothing here is scripted; each part is something a Xen weighs by its own nature:
 * <ul>
 *   <li><b>The tax</b>: a village that votes a tax in (see {@link Laws}) writes a rate into its constitution (a share of
 *   each member's money, every morning). Each Xen has its own feeling about the tax (its motives: power and loyalty for
 *   it, money against it, what paying it cost it), and when the village's feelings have moved away from the rate, the
 *   one who feels most strongly proposes an amendment and everyone votes by what they'd pay.</li>
 *   <li><b>Influence</b>: feelings move when Xens talk ({@link Talker}). A chatty Xen with a strong view argues it
 *   ("Taxes help everyone!", "20% is too much."), and the other moves toward it as far as it trusts the speaker.</li>
 *   <li><b>Faiths</b>: now and then a chatty, kind or proud Xen founds a faith (a name, a teaching) and becomes its
 *   priest. It preaches to the Xens it chats with, and may offer a gift with the blessing; followers spread it too,
 *   less keenly. Who converts is its own call: trust in the preacher, curiosity, a gift, against pride and a faith of its
 *   own. Fellow followers trust each other more, which shows in everything trust moves (sharing, votes, trades).</li>
 * </ul>
 * Kept with the world ({@code <world>/xen/society.json}), with a history for graphs ({@code society.csv}: how many
 * follow each faith, and each village's tax, over time). {@code /xen society} tells it.
 */
final class Society {
	private final XenMod mod;
	private final Random random = new Random();
	/** Tests: founding, amendments and the leader's rule ideas come round much more often (-Dxen.societyFast=true). */
	static final boolean FAST = Boolean.getBoolean("xen.societyFast");
	/** How each Xen feels about the tax: -1 (no tax at all) to +1 (a high one). */
	final Map<String, Float> taxFeel = new HashMap<>();
	/** The faith each Xen follows, and who brought it in ("" for its founder). */
	final Map<String, String> faithOf = new HashMap<>(), convertedBy = new HashMap<>();

	/** A faith: its name, its teaching, its greeting, who founded it, on which day. */
	record Faith(String name, String teaching, String blessing, String founder, long day) {}

	final Map<String, Faith> faiths = new LinkedHashMap<>();

	/** A village's constitution: its tax (a share of its members' money each morning) and how it was changed. */
	static final class Charter {
		int tax;
		long lastVote = -10;
		int collected;
		final List<String> amendments = new ArrayList<>();
	}

	final Map<String, Charter> charters = new HashMap<>();
	private Path file, csv;
	private long nextHistory, nextFounding;

	Society(XenMod mod) {
		this.mod = mod;
	}

	Charter charter(Tribe t) {
		return charters.computeIfAbsent(t.id, k -> new Charter());
	}

	// ------------------------------------------------------------------------------- the tax
	/** Its feeling about the tax: from its nature the first time, then what happens to it moves it. */
	float feel(Companion c) {
		return taxFeel.computeIfAbsent(c.name, k -> {
			var p = c.personality;
			float f = 0.6f * p.power + 0.4f * p.loyalty + 0.2f * p.kindness - 0.9f * p.money - 0.2f * p.sin(Sins.GREED) - 0.15f + 0.2f * (float) random.nextGaussian();
			return Math.max(-1f, Math.min(1f, f));
		});
	}

	private void feel(Companion c, float f) {
		taxFeel.put(c.name, Math.max(-1f, Math.min(1f, f)));
	}

	/** The tax it would vote for, in percent (0 to 30). */
	int wants(Companion c) {
		return Math.round(15 * (feel(c) + 1));
	}

	/** It paid its tax this morning: what that cost it moves its feeling (a money-lover minds it most). */
	void paid(Companion c, int amount, int had) {
		if (amount <= 0 || had <= 0) return;
		float share = amount / (float) had;
		feel(c, feel(c) - share * (0.25f + 0.6f * c.personality.money));
	}

	/**
	 * A new day in a village with a tax: if what its members want has moved away from the rate, the one who feels
	 * most strongly proposes a new rate, and they vote, each for the rate closer to what it would pay.
	 */
	void maybeAmend(Tribe t, Companion leader, long day) {
		Charter ch = charter(t);
		if (ch.tax <= 0 || day - ch.lastVote < (FAST ? 0 : 2)) return;
		List<Companion> voters = new ArrayList<>();
		for (Companion m : t.members) if (m.player != null && !m.minion) voters.add(m);
		if (voters.size() < 3) return;
		float mean = 0;
		for (Companion m : voters) mean += wants(m);
		mean /= voters.size();
		if (Math.abs(mean - ch.tax) < 4) return;
		Companion by = null;                                                     // the one who cares most, that way
		for (Companion m : voters) {
			boolean sameWay = (wants(m) - ch.tax) * (mean - ch.tax) > 0;
			if (sameWay && (by == null || Math.abs(feel(m)) * (0.5f + m.personality.chattiness) > Math.abs(feel(by)) * (0.5f + by.personality.chattiness))) by = m;
		}
		if (by == null) return;
		int proposal = Math.max(0, Math.min(30, Math.round(mean)));
		if (proposal == ch.tax) return;
		int yes = 0, no = 0;
		for (Companion m : voters) {
			if (Math.abs(wants(m) - proposal) < Math.abs(wants(m) - ch.tax) + (m == by ? 1 : 0)) yes++;
			else no++;
		}
		ch.lastVote = day;
		boolean passes = yes > no;
		String result = String.format(Locale.ROOT, "%s proposes changing the tax from %d%% to %d%%: %d for, %d against. %s", by.name, ch.tax, proposal, yes, no,
				passes ? "The constitution is amended." : "It stays as it is.");
		by.say(by.pick3((proposal < ch.tax ? "This tax is too much. " : "We need more for the village. ") + "I say " + proposal + "%. Let's vote.",
				"Time to change the constitution: " + proposal + "%, not " + ch.tax + "%. Who's with me?",
				"I propose a " + proposal + "% tax. Vote!"));
		leader.say(result);
		XenMod.LOG.info("{}: {}", t.name, result);
		if (passes) {
			ch.amendments.add(String.format(Locale.ROOT, "day %d: tax %d%% -> %d%% (%d for, %d against; %s's proposal)", day, ch.tax, proposal, yes, no, by.name));
			while (ch.amendments.size() > 20) ch.amendments.remove(0);
			leader.lore(t.name + " amended its constitution: the tax went from " + ch.tax + "% to " + proposal + "% (" + by.name + "'s proposal, " + yes + " for, " + no + " against)");
			ch.tax = proposal;
		}
	}

	/** Someone (a player) proposes a new rate: everyone votes, each for the rate closer to what it would pay (and as far as it trusts them). */
	String vote(Tribe t, int proposal, String byName, java.util.UUID by) {
		Charter ch = charter(t);
		proposal = Math.max(0, Math.min(50, proposal));
		if (proposal == ch.tax) return "The tax is " + ch.tax + "% already.";
		int yes = 0, no = 0;
		for (Companion m : t.members) {
			if (m.player == null || m.minion) continue;
			float lean = by == null ? 0 : 3 * m.trust(by);
			if (Math.abs(wants(m) - proposal) - lean < Math.abs(wants(m) - ch.tax)) yes++;
			else no++;
		}
		long day = t.members.isEmpty() || t.members.get(0).player == null ? 0 : t.members.get(0).player.level().getGameTime() / 24000;
		boolean passes = yes > no;
		String result = String.format(Locale.ROOT, "Vote on changing the tax from %d%% to %d%% (%s's proposal): %d for, %d against. %s", ch.tax, proposal, byName, yes, no,
				passes ? "The constitution is amended." : "It stays as it is.");
		if (passes) {
			ch.amendments.add(String.format(Locale.ROOT, "day %d: tax %d%% -> %d%% (%d for, %d against; %s's proposal)", day, ch.tax, proposal, yes, no, byName));
			ch.tax = proposal;
			ch.lastVote = day;
		}
		XenMod.LOG.info("{}: {}", t.name, result);
		return result;
	}

	// ------------------------------------------------------------------------------- talk
	/** A line of a chat (see {@link Talker}): who says it, when, and what it does once it's said. */
	interface Line {
		void say(Companion who, String text, long at, Runnable then);
	}

	/**
	 * Two Xens chatting: the tax (one with a strong view argues it, the other moves as far as it trusts it) and faith
	 * (a follower may preach to one who isn't; fellow followers bless each other). Returns the time after its lines.
	 */
	long talk(Companion a, Companion b, long t, Line line) {
		if (a.player == null || b.player == null) return t;
		Tribe ta = a.tribe();
		if (ta != null && ta == b.tribe() && charter(ta).tax > 0 && Math.abs(feel(a)) > 0.45f && random.nextFloat() < 0.25f + 0.5f * a.personality.chattiness) {
			int rate = charter(ta).tax;
			boolean pro = feel(a) > 0;
			String says = pro ? pick("Taxes help everyone! The village needs it.", "Honestly the tax is fine. It pays for the village.", "Paying the tax is the least we can do.")
					: pick(rate + "% is too much. It's my stuff.", "This tax is robbing us. " + rate + "%? Come on.", "We should pay less tax. Way less.");
			float pull = 0.25f * (0.5f + Math.max(-0.5f, b.trust(a.player.getUUID()))) * (1 - 0.5f * b.personality.sin(Sins.PRIDE));
			line.say(a, says, t += 55, () -> {
				float before = feel(b);
				feel(b, before + (feel(a) - before) * pull);
				b.journal("thinks", String.format(Locale.ROOT, "%s talked about the tax: its feeling %+.2f -> %+.2f", a.name, before, feel(b)));
			});
			boolean agrees = Math.signum(feel(b) + (feel(a) - feel(b)) * pull) == Math.signum(feel(a));
			line.say(b, agrees ? pick("Yeah, you're right.", "True.", "I guess so.") : pick("I don't think so.", "Hmm, not sure about that.", "Nah."), t += 45, null);
		}
		String fa = faithOf.get(a.name), fb = faithOf.get(b.name);
		if (fa != null && fa.equals(fb)) {                                         // fellow followers
			Faith f = faiths.get(fa);
			if (f != null && random.nextFloat() < 0.6f) {
				line.say(a, f.blessing() + ", " + b.name + ".", t += 50, () -> {
					a.trust(b.player.getUUID(), 0.03f);
					b.trust(a.player.getUUID(), 0.03f);
				});
				line.say(b, f.blessing() + ".", t += 40, null);
			}
		} else if (fa != null && faiths.containsKey(fa)) {
			t = preach(a, b, faiths.get(fa), fb, t, line);
		} else if (fb != null && faiths.containsKey(fb)) {
			t = preach(b, a, faiths.get(fb), fa, t, line);
		}
		return t;
	}

	/** The faith it's the priest of (it founded it), or null. */
	String priestOf(Companion c) {
		String f = faithOf.get(c.name);
		return f != null && "".equals(convertedBy.get(c.name)) && faiths.containsKey(f) ? f : null;
	}

	private boolean priest(Companion c) {
		String f = faithOf.get(c.name);
		return f != null && "".equals(convertedBy.get(c.name));
	}

	/** A follower tells one who isn't about its faith (a priest always, others as keen as they are chatty); maybe a gift. */
	private long preach(Companion by, Companion to, Faith f, String theirs, long t, Line line) {
		boolean isPriest = priest(by);
		if (random.nextFloat() > (isPriest ? 0.9f : 0.15f + 0.3f * by.personality.chattiness)) return t;
		boolean gift = isPriest && !by.sharing.busy() && random.nextFloat() < 0.3f + 0.4f * by.personality.kindness;
		String what = gift ? spare(by) : null;
		String says = what != null
				? pick("Hey " + to.name + ", I trade not just goods but the blessings of " + f.name() + ". Take this.", "A gift from " + f.name() + ", " + to.name + ". " + f.teaching() + ".",
						"Here, " + to.name + ", have this. " + f.name() + " looks after its own.")
				: pick("Have you heard of " + f.name() + "? " + f.teaching() + ".", f.teaching() + ", " + to.name + ". Join " + f.name() + "!",
						"You should come to " + f.name() + ". " + f.teaching() + ".");
		float trust = to.trust(by.player.getUUID());
		var p = to.personality;
		float chance = 0.08f + 0.35f * Math.max(0, trust) + 0.2f * p.curiosity + (what != null ? 0.15f + 0.15f * p.money : 0) - (theirs != null ? 0.3f : 0)
				- 0.15f * p.sin(Sins.PRIDE) - (to.personality.believes("stubborn") ? 0.15f : 0) + (isPriest ? 0.05f : 0);
		if (priestOf(to) != null) chance *= 0.05f;                             // (a faith's own founder hardly ever leaves it)
		boolean converts = random.nextFloat() < Math.max(priestOf(to) != null ? 0.002f : 0.02f, Math.min(0.85f, chance));
		String item = what;
		line.say(by, says, t += 60, () -> {
			if (item != null && to.player != null && by.player != null) by.sharing.gift(to, item, 1, by.player.level().getGameTime());
		});
		if (converts) {
			line.say(to, pick("Okay... count me in. " + f.blessing() + "!", "You know what, I'm in.", "I'll join " + f.name() + "."), t += 50, () -> {
				if (to.player == null || by.player == null) return;
				faithOf.put(to.name, f.name());
				convertedBy.put(to.name, by.name);
				to.trust(by.player.getUUID(), 0.1f);
				by.trust(to.player.getUUID(), 0.05f);
				to.journal("does", "joins " + f.name() + " (" + by.name + (isPriest ? ", its priest" : "") + " talked it round" + (item != null ? ", with a gift" : "") + ")");
				by.journal("does", "brought " + to.name + " into " + f.name());
				XenMod.LOG.info("{} joins {} ({}{})", to.name, f.name(), by.name, isPriest ? ", the priest" : "");
				int n = followers(f.name());
				if (n == 3 || n == 5 || n % 10 == 0) by.lore(f.name() + " has " + n + " followers now");
			});
		} else {
			line.say(to, theirs != null ? pick("I've got my own faith, thanks.", "No, I follow " + theirs + ".") : pick("Not for me.", "Eh... maybe later.", "Sounds weird, no thanks."),
					t += 50, null);
		}
		return t;
	}

	/** Something it can spare as a gift: food, coal, a few logs (or nothing). */
	private static String spare(Companion c) {
		var inv = c.items();
		for (String s : new String[] {"bread", "cooked_beef", "cooked_porkchop", "baked_potato", "apple", "coal", "torch", "oak_log", "cobblestone"}) {
			if (inv.getOrDefault(s, 0) >= 4) return s;
		}
		return null;
	}

	int followers(String faith) {
		int n = 0;
		for (Companion c : mod.companions) if (faith.equals(faithOf.get(c.name))) n++;
		return n;
	}

	// ------------------------------------------------------------------------------- founding a faith
	private static final String[] HOLY = {"Holy", "Sacred", "Eternal", "Blessed", "Mighty", "Gentle", "Hidden", "Great"};
	private static final String[] THING = {"Pickaxe", "Furnace", "Creeper", "Squid", "Cobblestone", "Golden Carrot", "Oak", "Torch", "Bread",
			"Sheep", "Moon", "Diamond", "Bucket", "Slime"};
	private static final String[] DOES = {"spares the kind", "blesses honest miners", "sees every block you break", "lights the way down",
			"watches over the hungry", "forgives a broken pickaxe", "keeps the creepers away from the faithful", "rewards those who share"};

	/** Every few minutes: a chatty Xen with a kind heart or a proud one may found a faith (a few at most in the world). */
	void tick(long now) {
		if (now >= nextHistory) {
			nextHistory = now + 1200;
			history(now);
		}
		if (now < nextFounding) return;
		nextFounding = now + (FAST ? 400 : 20 * 60 * 3);
		if (faiths.size() >= 3 || mod.companions.size() < 4) return;
		for (Companion c : mod.companions) {
			if (c.player == null || c.minion || c.inArena || faithOf.containsKey(c.name) || c.age() < (FAST ? 1200 : 24000)) continue;
			var p = c.personality;
			if (p.chattiness < 0.55f || p.kindness < 0.5f && p.sin(Sins.PRIDE) < 0.5f) continue;
			if (random.nextFloat() > (FAST ? 0.3f : 0.08f)) continue;
			String thing = THING[random.nextInt(THING.length)], holy = HOLY[random.nextInt(HOLY.length)];
			String name = switch (random.nextInt(4)) {
				case 0 -> "the Church of the " + holy + " " + thing;
				case 1 -> "the Order of the " + holy + " " + thing;
				case 2 -> "the Followers of the " + thing;
				default -> thing.replace(" ", "") + (thing.endsWith("e") ? "ism" : "ism");
			};
			if (faiths.containsKey(name)) continue;
			String teaching = "The " + thing + " " + DOES[random.nextInt(DOES.length)];
			String blessing = random.nextBoolean() ? "Blessings of the " + thing : "May the " + thing + " guide you";
			long day = now / 24000 + 1;
			faiths.put(name, new Faith(name, teaching, blessing, c.name, day));
			faithOf.put(c.name, name);
			convertedBy.put(c.name, "");
			c.say(c.pick3("I've seen it: " + teaching.toLowerCase(Locale.ROOT) + ". Join " + name + "!", "Listen, everyone: " + teaching.toLowerCase(Locale.ROOT)
					+ ". I'm starting " + name + ".", "From today I follow " + name + ". " + teaching + "."));
			c.journal("does", "founds " + name + " (\"" + teaching + "\") and becomes its priest");
			c.lore(c.name + " founded " + name + ": \"" + teaching + "\"");
			XenMod.LOG.info("{} founds {}: {}", c.name, name, teaching);
			return;
		}
	}

	/** The history, for graphs: each faith's followers (by the priest, by others), each village's tax. */
	private void history(long now) {
		if (csv == null) return;
		StringBuilder sb = new StringBuilder();
		long minute = now / 1200;
		for (Faith f : faiths.values()) {
			int direct = 0, indirect = 0, all = 0;
			for (Companion c : mod.companions) {
				if (!f.name().equals(faithOf.get(c.name))) continue;
				all++;
				String by = convertedBy.getOrDefault(c.name, "");
				if (by.equals(f.founder())) direct++;
				else if (!by.isEmpty()) indirect++;
			}
			sb.append(String.format(Locale.ROOT, "%d,faith,%s,%d,%d,%d,%d%n", minute, f.name(), all, direct, indirect, mod.companions.size()));
		}
		for (Tribe t : mod.tribes.values()) {
			Charter ch = charters.get(t.id);
			if (ch != null && t.members.size() >= 2) sb.append(String.format(Locale.ROOT, "%d,tax,%s,%d,%d,0,%d%n", minute, t.name.replace(',', ' '), ch.tax, ch.collected, t.members.size()));
		}
		if (sb.length() == 0) return;
		try {
			boolean fresh = !Files.exists(csv);
			Files.createDirectories(csv.getParent());
			Files.writeString(csv, (fresh ? "minute,kind,name,value,direct_or_collected,indirect,population\n" : "") + sb, StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			XenMod.LOG.warn("Xen's society history: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------------------- telling it
	List<String> describe() {
		List<String> out = new ArrayList<>();
		for (Tribe t : mod.tribes.values()) {
			if (t.members.size() < 2) continue;
			Charter ch = charters.get(t.id);
			StringBuilder sb = new StringBuilder(t.name).append(" (").append(t.members.size()).append(")");
			Companion leader = t.laws.leader();
			if (leader != null && t.members.size() >= 3) sb.append(", led by ").append(leader.name);
			var rules = t.laws.describe();
			sb.append(": constitution ").append(rules.isEmpty() ? "none yet" : String.join("; ", rules));
			if (ch != null && ch.tax > 0) sb.append(String.format(Locale.ROOT, "; tax %d%% (%d paid in so far)", ch.tax, ch.collected));
			float feel = 0;
			int n = 0;
			for (Companion m : t.members) if (m.player != null) {
				feel += wants(m);
				n++;
			}
			if (n > 0 && ch != null && ch.tax > 0) sb.append(String.format(Locale.ROOT, "; its members would pay %.0f%% on average", feel / n));
			Map<String, Integer> jobs = new LinkedHashMap<>();
			for (var e : t.laws.currentJobs().entrySet()) jobs.merge(e.getValue().word, 1, Integer::sum);
			if (!jobs.isEmpty()) sb.append("; jobs ").append(jobs.toString().replace("{", "").replace("}", ""));
			if (ch != null && !ch.amendments.isEmpty()) sb.append("; amendments: ").append(String.join(", ", ch.amendments));
			out.add(sb.toString());
		}
		int pop = 0;
		for (Companion c : mod.companions) if (c.player != null) pop++;
		for (Faith f : faiths.values()) {
			int all = followers(f.name());
			out.add(String.format(Locale.ROOT, "%s (founded by %s on day %d: \"%s\"): %d of %d Xens follow it (%.0f%%)", f.name(), f.founder(), f.day(), f.teaching(),
					all, pop, pop == 0 ? 0 : 100f * all / pop));
		}
		if (out.isEmpty()) out.add("No village with its own rules yet, and no faith (it takes a few Xens living together a while).");
		return out;
	}

	private String pick(String... s) {
		return s[random.nextInt(s.length)];
	}

	// ------------------------------------------------------------------------------- kept with the world
	void load(Path file) {
		this.file = file;
		this.csv = file.resolveSibling("society.csv");
		taxFeel.clear();
		faithOf.clear();
		convertedBy.clear();
		faiths.clear();
		charters.clear();
		try {
			if (!Files.exists(file)) return;
			JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.has("feel")) for (var e : o.getAsJsonObject("feel").entrySet()) taxFeel.put(e.getKey(), e.getValue().getAsFloat());
			if (o.has("faithOf")) for (var e : o.getAsJsonObject("faithOf").entrySet()) faithOf.put(e.getKey(), e.getValue().getAsString());
			if (o.has("convertedBy")) for (var e : o.getAsJsonObject("convertedBy").entrySet()) convertedBy.put(e.getKey(), e.getValue().getAsString());
			if (o.has("faiths")) for (var e : o.getAsJsonArray("faiths")) {
				JsonObject f = e.getAsJsonObject();
				faiths.put(f.get("name").getAsString(), new Faith(f.get("name").getAsString(), f.get("teaching").getAsString(), f.get("blessing").getAsString(),
						f.get("founder").getAsString(), f.get("day").getAsLong()));
			}
			if (o.has("charters")) for (var e : o.getAsJsonObject("charters").entrySet()) {
				JsonObject j = e.getValue().getAsJsonObject();
				Charter ch = new Charter();
				ch.tax = j.get("tax").getAsInt();
				ch.lastVote = j.get("lastVote").getAsLong();
				ch.collected = j.has("collected") ? j.get("collected").getAsInt() : 0;
				for (var a : j.getAsJsonArray("amendments")) ch.amendments.add(a.getAsString());
				charters.put(e.getKey(), ch);
			}
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Could not read the Xens' society: {}", e.toString());
		}
	}

	void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		JsonObject feel = new JsonObject(), faith = new JsonObject(), by = new JsonObject(), ch = new JsonObject();
		taxFeel.forEach(feel::addProperty);
		faithOf.forEach(faith::addProperty);
		convertedBy.forEach(by::addProperty);
		JsonArray fs = new JsonArray();
		for (Faith f : faiths.values()) {
			JsonObject j = new JsonObject();
			j.addProperty("name", f.name());
			j.addProperty("teaching", f.teaching());
			j.addProperty("blessing", f.blessing());
			j.addProperty("founder", f.founder());
			j.addProperty("day", f.day());
			fs.add(j);
		}
		charters.forEach((k, v) -> {
			JsonObject j = new JsonObject();
			j.addProperty("tax", v.tax);
			j.addProperty("lastVote", v.lastVote);
			j.addProperty("collected", v.collected);
			JsonArray a = new JsonArray();
			v.amendments.forEach(a::add);
			j.add("amendments", a);
			ch.add(k, j);
		});
		o.add("feel", feel);
		o.add("faithOf", faith);
		o.add("convertedBy", by);
		o.add("faiths", fs);
		o.add("charters", ch);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			XenMod.LOG.warn("Could not save the Xens' society: {}", e.toString());
		}
	}
}
