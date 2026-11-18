package xen.mod;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xen's big brain: a language model (any OpenAI-style API, see {@link LlmClient}) that thinks it over slowly, while
 * its old brain (the DMM: Btb, Xenfish and the reflexes under them) keeps the body going fast. The two are one:
 * <ul>
 * <li>Every so often (brainEvery seconds, or straight away after something big: a death, a goal reached, a task that
 * got nowhere, being spoken to) it tells the model who it is, how it's doing, what it carries, what its team is up to,
 * what just happened, how its last plan went, and the choices its body can take right now with how its old brain
 * scores them. The model answers with its thought, a plan, the choice it makes, for how long, and maybe a line to say.</li>
 * <li>Its choice is checked before anything is done with it (only a choice the body can take now counts; a bad one is
 * turned down, and it's told why next time). A good one weighs heavily in the old brain's choosing for those minutes;
 * what it wants to avoid weighs against. But the reflexes come first: fighting, fleeing, eating, its shelter at night,
 * getting out of lava and water never wait on the slow brain, which isn't asked about them.</li>
 * <li>Spoken to, it answers in its own words, from what it knows (what it's doing and why), while the old brain still
 * does what was asked (so a request is done the same reliable way).</li>
 * <li>With brainVision on, now and then it sees too: its eyes' picture (the live view's) goes with the question.</li>
 * </ul>
 * Off with no brainUrl. Only the Xens in brainXens ("all", or names) have one.
 */
final class Brain {
	private final XenMod mod;
	final LlmClient llm;

	/** Each Xen's mind: what it chose and till when, what it avoids, its plan, its memory of what happened. */
	private static final class Mind {
		volatile Btb.Task pick;
		volatile long pickUntil;
		final Set<Btb.Task> avoid = ConcurrentHashMap.newKeySet();
		volatile long avoidUntil;
		volatile String plan = "", thought = "", rejected = "";
		volatile boolean waiting;
		volatile long askedAt, nudgedAt = -1, sawAt;
		volatile String nudge = "";
		volatile int asks;
		final Deque<String> events = new ArrayDeque<>();
		final Deque<String> history = new ArrayDeque<>();
		volatile long talkingUntil;
	}

	private final Map<String, Mind> minds = new ConcurrentHashMap<>();

	Brain(XenMod mod) {
		this.mod = mod;
		this.llm = new LlmClient(mod);
	}

	/** This one has a big brain (a URL set, and it's on the list)? */
	boolean on(Companion c) {
		if (!llm.on() || c == null || c.minion || c.player == null) return false;
		String who = mod.config.brainXens == null ? "all" : mod.config.brainXens.trim();
		if (who.isEmpty() || who.equalsIgnoreCase("all")) return true;
		for (String n : who.split("\\s*,\\s*")) if (n.equalsIgnoreCase(c.name)) return true;
		return false;
	}

	private Mind mind(Companion c) {
		return minds.computeIfAbsent(c.name, k -> new Mind());
	}

	/** Something it should know (from its journal): kept, the last few. */
	void noted(Companion c, String kind, String text) {
		if (!llm.on()) return;
		if (kind.equals("debug") || kind.equals("way") || kind.equals("sees") && text.startsWith("You saw")) return;   // (its eyes' every glance, its path-finding: noise)
		if (kind.equals("thinks") && (text.startsWith("wait") || text.startsWith("walk") || text.startsWith("jump") || text.startsWith("step") || text.startsWith("mine") || text.startsWith("place"))) return;   // (each step its body takes)
		Mind m = mind(c);
		synchronized (m.events) {
			m.events.addLast(java.time.LocalTime.now().withNano(0) + " " + kind + ": " + (text.length() > 140 ? text.substring(0, 140) : text));
			while (m.events.size() > 14) m.events.removeFirst();
		}
	}

	/** Something big happened: it thinks again soon, not at its next turn. */
	void nudge(Companion c, String why) {
		if (!on(c)) return;
		Mind m = mind(c);
		m.nudge = why;
		m.nudgedAt = System.currentTimeMillis();
	}

	/**
	 * Its old brain, choosing: what the big brain picked (if it's still one of the choices: else null), and asking it
	 * again when it's time. Options are "TASK (why)" with its old brain's score.
	 */
	Btb.Task pick(Companion c, List<String> options, Map<Btb.Task, Float> scores, Btb.Obs o) {
		if (!on(c)) return null;
		Mind m = mind(c);
		long now = System.currentTimeMillis();
		boolean due = now - m.askedAt > Math.max(15, mod.config.brainEvery) * 1000L
				|| m.nudgedAt > m.askedAt && now - m.askedAt > 8000;                     // (something big: soon, but not on every tick)
		if (!m.waiting && due && options.size() >= 1) ask(c, m, options, scores, o);
		Btb.Task p = m.pick;
		if (p == null || now > m.pickUntil) return null;
		return p;
	}

	/** Is this one it said to keep away from, for now? */
	boolean avoids(Companion c, Btb.Task t) {
		if (!on(c)) return false;
		Mind m = mind(c);
		return System.currentTimeMillis() < m.avoidUntil && m.avoid.contains(t);
	}

	/** Its plan in its own words (for the live view and /xen brain). */
	String plan(Companion c) {
		Mind m = minds.get(c.name);
		return m == null ? "" : m.plan;
	}

	// ----------------------------------------------------------------------------------------------- thinking it over

	private void ask(Companion c, Mind m, List<String> options, Map<Btb.Task, Float> scores, Btb.Obs o) {
		if (!llm.slot()) return;                                               // (the cap on calls a minute: its turn later)
		m.waiting = true;
		m.askedAt = System.currentTimeMillis();
		String why = m.nudgedAt > 0 && m.nudgedAt >= m.askedAt - 9000 ? m.nudge : "";
		m.nudge = "";
		String sys = system(c);
		String user = state(c, m, options, scores, o, why);
		byte[] eyes = null;
		if (mod.config.brainVision && m.asks % Math.max(1, mod.config.brainSeeEvery) == 0) eyes = eyes(c);
		m.asks++;
		List<LlmClient.Msg> msgs = new ArrayList<>();
		msgs.add(new LlmClient.Msg("system", sys));
		msgs.add(new LlmClient.Msg("user", user, eyes));
		List<String> valid = new ArrayList<>();
		for (String op : options) valid.add(op.split(" ")[0]);
		llm.chat(msgs, mod.config.brainTokens, 0.6).whenComplete((text, err) -> mod.server.execute(() -> {
			m.waiting = false;
			if (err != null || text == null) {
				XenMod.LOG.info("{}'s brain: no answer ({})", c.name, llm.lastError);
				return;
			}
			apply(c, m, text, valid);
		}));
	}

	/** Who it is and how to answer. */
	private String system(Companion c) {
		var p = c.personality;
		String team = mod.teamOf(c);
		return "You are " + c.name + ", a player in Minecraft survival" + (team == null ? "" : ", on the " + team.replace("xen_", "") + " team")
				+ ". Personality: " + (p.bravery > 0.6f ? "brave" : p.bravery < 0.35f ? "cautious" : "steady") + ", " + (p.curiosity > 0.6f ? "curious" : "focused") + ", "
				+ (p.kindness > 0.6f ? "kind" : p.kindness < 0.35f ? "selfish" : "fair") + ", " + (p.diligence > 0.6f ? "hard-working" : "easygoing") + ", "
				+ (p.chattiness > 0.6f ? "talkative" : "quiet") + ". Goal of your world: full diamond armor and tools, enchanted, for every player of your team, then the Nether.\n"
				+ "You are the slow, thinking part of your mind. A fast part runs your body: it walks, mines, crafts, fights, flees, eats, and hides at night by itself. "
				+ "You decide WHAT to work on next and WHY, from the choices your body can take right now. Think a few steps ahead (what each choice gets you, what it costs, what could kill you). "
				+ "Don't pick something that just failed again unless something changed.\n"
				+ "Answer ONLY with one JSON object, no other text: {\"thought\": \"one or two short sentences\", \"plan\": \"your next 2-4 steps, short\", "
				+ "\"do\": \"ONE choice word from the list, exactly as written\", \"minutes\": 1 to 10 (how long to stick with it), "
				+ "\"avoid\": [\"choice words to stay away from for now\"], \"say\": \"a short line to say out loud in chat, or empty\"}. "
				+ "Say something only now and then, in character, casual, like a real player (no emojis spam, no narration).";
	}

	/** How it is: in short lines, the numbers that matter. */
	private String state(Companion c, Mind m, List<String> options, Map<Btb.Task, Float> scores, Btb.Obs o, String why) {
		var p = c.player;
		StringBuilder sb = new StringBuilder();
		if (!why.isEmpty()) sb.append("JUST HAPPENED: ").append(why).append("\n");
		sb.append(String.format(Locale.ROOT, "You: health %.0f/20, food %d/20, %d meals in bag, XP level %d. %s, %s%s.%n", o.health(), o.food(), o.meals(), p.experienceLevel,
				o.night() ? "Night" : "Day", o.under() ? "underground at y " + p.blockPosition().getY() : "on the surface", o.danger() >= 2 ? ", MONSTERS CLOSE" : o.danger() == 1 ? ", a monster about" : ""));
		sb.append("Armor: ").append(armor(p)).append(". Holding: ").append(item(p.getMainHandItem())).append(".\n");
		sb.append("Bag: ").append(bag(c)).append(".\n");
		sb.append("Your current long goal: ").append(c.btb.goal).append(" (").append(c.xenfish.show()).append("). Doing now: ")
				.append(c.btb.task.name()).append(!c.chores.doing.isEmpty() ? " - " + c.chores.doing : "").append(".\n");
		String team = team(c);
		if (!team.isEmpty()) sb.append("Team: ").append(team).append("\n");
		synchronized (m.events) {
			if (!m.events.isEmpty()) {
				sb.append("Recently:\n");
				for (String e : m.events) sb.append("- ").append(e).append("\n");
			}
		}
		synchronized (m.history) {
			if (!m.history.isEmpty()) {
				sb.append("Your last decisions:\n");
				for (String h : m.history) sb.append("- ").append(h).append("\n");
			}
		}
		if (!m.rejected.isEmpty()) sb.append("NOTE: ").append(m.rejected).append("\n");
		sb.append("Choices your body can take now (with your fast brain's score; higher = it would pick it):\n");
		for (String op : options) {
			Btb.Task t = task(op.split(" ")[0]);
			Float s = t == null ? null : scores.get(t);
			sb.append("- ").append(op).append(s == null ? "" : String.format(Locale.ROOT, " [%.1f]", s)).append("\n");
		}
		sb.append("Which do you do?");
		return sb.toString();
	}

	private void apply(Companion c, Mind m, String text, List<String> valid) {
		JsonObject j = LlmClient.firstJson(text);
		if (j == null) {
			XenMod.LOG.info("{}'s brain answered something it couldn't read: {}", c.name, text.length() > 160 ? text.substring(0, 160) : text);
			m.rejected = "Your last answer was not one JSON object. Answer with JSON only.";
			return;
		}
		String thought = str(j, "thought"), plan = str(j, "plan"), say = str(j, "say"), pick = str(j, "do").toUpperCase(Locale.ROOT).trim();
		int minutes = 4;
		try {
			if (j.has("minutes")) minutes = Math.max(1, Math.min(10, (int) Math.round(j.get("minutes").getAsDouble())));
		} catch (RuntimeException e) {
			// (a word for a number: its usual)
		}
		if (pick.contains(" ")) pick = pick.split(" ")[0];
		m.thought = thought;
		if (!plan.isEmpty()) m.plan = plan;
		Btb.Task t = task(pick);
		long now = System.currentTimeMillis();
		if (t != null && valid.contains(pick)) {                                   // checked: one its body can take now
			m.pick = t;
			m.pickUntil = now + minutes * 60_000L;
			m.rejected = "";
		} else {
			m.pick = null;
			m.rejected = pick.isEmpty() ? "You didn't pick a choice last time." : "Your last pick \"" + pick + "\" wasn't one of the choices you had, so it was ignored. Pick only from the list.";
		}
		m.avoid.clear();
		if (j.has("avoid") && j.get("avoid").isJsonArray())
			for (var a : j.getAsJsonArray("avoid")) {
				Btb.Task at = a.isJsonPrimitive() ? task(a.getAsString().toUpperCase(Locale.ROOT).trim()) : null;
				if (at != null && at != t && !SAFE.contains(at)) m.avoid.add(at);       // (it can't think its way out of shelter, sleep or food)
			}
		m.avoidUntil = now + minutes * 60_000L;
		synchronized (m.history) {
			m.history.addLast(java.time.LocalTime.now().withNano(0) + " chose " + (m.pick != null ? pick : "nothing valid (" + pick + ")") + (plan.isEmpty() ? "" : "; plan: " + plan));
			while (m.history.size() > 5) m.history.removeFirst();
		}
		c.journal("thinks", "(brain) " + thought + (m.pick != null ? " -> " + pick + " for " + minutes + " min" : ""));
		XenMod.LOG.info("{}'s brain: {} | plan: {} | do: {}{}{}", c.name, thought, plan, m.pick != null ? pick : "(invalid: " + pick + ")", m.avoid.isEmpty() ? "" : " | avoid " + m.avoid,
				say.isEmpty() ? "" : " | says: " + say);
		if (!say.isEmpty() && mod.config.brainTalk && now > m.talkingUntil) {
			c.chatter(say.length() > 200 ? say.substring(0, 200) : say, false);
			m.talkingUntil = now + 45_000;                                        // (not every thought out loud)
		}
	}

	/** The reflexes' own: never pushed down by a thought. */
	static final Set<Btb.Task> SAFE = java.util.EnumSet.noneOf(Btb.Task.class);
	static {
		for (String s : new String[] {"SHELTER", "SLEEP", "RETREAT", "EAT", "FOOD"})
			try {
				SAFE.add(Btb.Task.valueOf(s));
			} catch (IllegalArgumentException e) { }
	}

	// ------------------------------------------------------------------------------------------------------- talking

	/**
	 * Someone talked to it: an answer in its own words, from what it knows (the request itself is still done by its
	 * old brain). False: no big brain (its old way answers). The fallback, said if no answer comes in time.
	 */
	boolean talk(Companion c, ServerPlayer from, String words, String fallback) {
		if (!on(c) || !mod.config.brainTalk || !llm.slot()) return false;
		Mind m = mind(c);
		var o = c.btb.lastObs;
		String sys = system(c).replaceAll("(?s)You are the slow, thinking part.*", "")
				+ "Someone talks to you in the game chat. Answer as yourself, in one or two short casual sentences (a real player's chat, no quotes, no name prefix). "
				+ "Be honest about what you're doing and why. If they ask you to do something, say whether you'll do it (your body handles the doing).";
		StringBuilder st = new StringBuilder();
		st.append(from.getName().getString()).append(" says: \"").append(words).append("\"\n");
		if (o != null) st.append(String.format(Locale.ROOT, "(You: health %.0f/20, food %d/20, %s, %s.)%n", o.health(), o.food(), o.night() ? "night" : "day", o.under() ? "underground" : "on the surface"));
		st.append("(Armor: ").append(armor(c.player)).append(". Doing: ").append(c.btb.task.name()).append(!c.chores.doing.isEmpty() ? " - " + c.chores.doing : "")
				.append(". Your plan: ").append(m.plan.isEmpty() ? "none yet" : m.plan).append(".)");
		if (fallback != null && !fallback.isBlank()) st.append("\n(Your quick reply would have been: \"").append(fallback).append("\")");
		List<LlmClient.Msg> msgs = List.of(new LlmClient.Msg("system", sys), new LlmClient.Msg("user", st.toString()));
		long asked = System.currentTimeMillis();
		java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
		llm.chat(msgs, 120, 0.8).whenComplete((text, err) -> mod.server.execute(() -> {
			if (!done.compareAndSet(false, true)) return;
			String line = err == null && text != null ? text.replaceAll("^\\s*\"|\"\\s*$", "").replaceAll("(?i)^" + java.util.regex.Pattern.quote(c.name) + "\\s*:\\s*", "").strip() : "";
			if (line.startsWith("{")) {                                            // (it answered in its thinking's JSON: its "say")
				JsonObject j = LlmClient.firstJson(line);
				line = j == null ? "" : str(j, "say");
			}
			if (line.isEmpty()) line = fallback == null ? "" : fallback;
			if (!line.isEmpty()) c.say(line.length() > 240 ? line.substring(0, 240) : line);
			XenMod.LOG.info("{}'s brain answers {} ({} ms): {}", c.name, from.getName().getString(), System.currentTimeMillis() - asked, line);
		}));
		final String fb = fallback;
		new Thread(() -> {                                                        // no answer in time: its quick reply after all
			try {
				Thread.sleep(Math.max(3, mod.config.brainTalkWait) * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			mod.server.execute(() -> {
				if (done.compareAndSet(false, true) && fb != null && !fb.isBlank()) c.say(fb);
			});
		}, "xen-brain-talk").start();
		nudge(c, from.getName().getString() + " said to you: \"" + (words.length() > 120 ? words.substring(0, 120) : words) + "\"");
		return true;
	}

	// ---------------------------------------------------------------------------------------------------- the details

	private byte[] eyes(Companion c) {
		try {
			Path png = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("live").resolve(c.name + ".png");
			if (Files.exists(png) && Files.size(png) > 200 && Files.size(png) < 2_000_000) return Files.readAllBytes(png);
		} catch (Exception e) {
			// (no picture this time)
		}
		return null;
	}

	private String team(Companion c) {
		String team = mod.teamOf(c);
		if (team == null) return "";
		StringBuilder sb = new StringBuilder();
		int full = 0, n = 0;
		for (Companion x : mod.companions) {
			if (x == c || x.player() == null || !team.equals(mod.teamOf(x))) continue;
			n++;
			if (Companion.fullDiamond(x.player())) full++;
			if (sb.length() < 400) sb.append(sb.length() == 0 ? "" : "; ").append(x.name).append(" ").append(pieces(x.player())).append(" (").append(x.btb.task.name().toLowerCase(Locale.ROOT)).append(")");
		}
		return n + " teammates, " + full + " in full diamond: " + sb;
	}

	private static String pieces(ServerPlayer p) {
		int dia = 0, ench = 0;
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack st = p.getItemBySlot(s);
			String k = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
			if (k.startsWith("diamond_") || k.startsWith("netherite_")) dia++;
			if (st.isEnchanted()) ench++;
		}
		return dia + "/4 diamond, " + ench + " enchanted";
	}

	private static String armor(ServerPlayer p) {
		StringBuilder sb = new StringBuilder();
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack st = p.getItemBySlot(s);
			sb.append(sb.length() == 0 ? "" : ", ").append(st.isEmpty() ? "no " + s.getName() : item(st));
		}
		return sb.toString();
	}

	private static String item(ItemStack st) {
		if (st.isEmpty()) return "nothing";
		return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath() + (st.isEnchanted() ? " (enchanted)" : "");
	}

	/** Its bag, the things that matter first, in counts. */
	private static String bag(Companion c) {
		Map<String, Integer> it = c.items();
		List<Map.Entry<String, Integer>> l = new ArrayList<>(it.entrySet());
		l.sort((a, b) -> Integer.compare(worth(b.getKey()) * 1000 + Math.min(999, b.getValue()), worth(a.getKey()) * 1000 + Math.min(999, a.getValue())));
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (var e : l) {
			if (n++ >= 28) break;
			sb.append(sb.length() == 0 ? "" : ", ").append(e.getValue()).append(" ").append(e.getKey());
		}
		return sb.length() == 0 ? "empty" : sb.toString();
	}

	private static int worth(String k) {
		if (k.contains("diamond") || k.contains("netherite") || k.equals("enchanting_table") || k.equals("book") || k.equals("totem_of_undying")) return 9;
		if (k.contains("lapis") || k.contains("obsidian") || k.contains("iron") || k.contains("blaze") || k.contains("ender") || k.equals("paper") || k.equals("leather") || k.contains("pickaxe") || k.contains("sword")) return 7;
		if (k.contains("cooked") || k.equals("bread") || k.contains("gold") || k.contains("bucket") || k.equals("flint_and_steel")) return 5;
		if (k.contains("log") || k.contains("planks") || k.equals("coal") || k.equals("cobblestone") || k.contains("_bed") || k.equals("crafting_table") || k.equals("furnace")) return 3;
		return 1;
	}

	private static Btb.Task task(String w) {
		try {
			return Btb.Task.valueOf(w);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String str(JsonObject j, String k) {
		try {
			return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString().strip() : "";
		} catch (RuntimeException e) {
			return "";
		}
	}

	/** For /xen brain: how it's going, and each one's last thought and plan. */
	String status() {
		StringBuilder sb = new StringBuilder(llm.status());
		for (var e : minds.entrySet()) {
			Mind m = e.getValue();
			if (m.thought.isEmpty() && m.plan.isEmpty()) continue;
			sb.append("\n").append(e.getKey()).append(": ").append(m.thought).append(m.pick != null && System.currentTimeMillis() < m.pickUntil ? " -> " + m.pick : "")
					.append(m.plan.isEmpty() ? "" : " | plan: " + m.plan);
		}
		return sb.toString();
	}
}
