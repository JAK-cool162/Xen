package xen.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import xen.mod.talk.Chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your own little script for Xens (the {@code script} setting, in the Experimental tab): one rule per line,
 * {@code when <something happens>: <what to do>}. Lines starting with # are notes. For example:
 * <pre>
 * when night: do build a shelter
 * when morning: say Good morning, {player}!
 * when hungry: say I'm starving!
 * when sees creeper: say RUN!
 * when someone comes: wave
 * when hears hello: dance
 * when every 10 minutes: show off
 * </pre>
 * <b>When</b>: night, morning, rain, hungry, hurt, attacked, diamonds, someone comes, sees &lt;a mob&gt;,
 * hears &lt;a word&gt;, every &lt;N&gt; minutes. <b>Do</b>: {@code say <words>} ({name} and {player} are filled in),
 * {@code do <a request>} (anything you could ask it, like "do follow me" or "do get 5 wood": it's as if its owner
 * asked, so it can still say no), or dance, spin, wave, show off. A rule fires at most once a minute. With
 * {@code /xen set script ...}, put | between rules.
 */
final class Script {
	record Rule(String event, String arg, String action, String text, String line) {}

	private static final Pattern RULE = Pattern.compile("^when\\s+(.+?)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern EVERY = Pattern.compile("every\\s+(\\d+)\\s*(minutes?|mins?|m)?");

	/** The rules in a script, and the lines it couldn't read. */
	static List<Rule> parse(String script, List<String> problems) {
		List<Rule> out = new ArrayList<>();
		if (script == null) return out;
		for (String raw : script.split("\\r?\\n|\\|")) {                         // a new line, or | (for /xen set)
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			Matcher m = RULE.matcher(line);
			if (!m.find()) {
				problems.add(line);
				continue;
			}
			String when = m.group(1).toLowerCase(Locale.ROOT).trim(), what = m.group(2).trim();
			String event, arg = "";
			Matcher every = EVERY.matcher(when);
			if (every.find()) {
				event = "every";
				arg = every.group(1);
			} else if (when.startsWith("sees ") || when.startsWith("see ")) {
				event = "sees";
				arg = when.substring(when.indexOf(' ') + 1).trim();
			} else if (when.startsWith("hears ") || when.startsWith("hear ")) {
				event = "hears";
				arg = when.substring(when.indexOf(' ') + 1).trim();
			} else if (when.matches("(someone|a player|player|friend|my friend) (comes|arrives|joins)|greet(ing)?")) {
				event = "comes";
			} else if (when.matches("night|dark|evening|dusk")) {
				event = "night";
			} else if (when.matches("morning|day|dawn|sunrise")) {
				event = "morning";
			} else if (when.matches("rain|raining|it rains")) {
				event = "rain";
			} else if (when.matches("hungry|starving")) {
				event = "hungry";
			} else if (when.matches("hurt|low health|badly hurt")) {
				event = "hurt";
			} else if (when.matches("attacked|hit|someone hits me")) {
				event = "attacked";
			} else if (when.matches("diamonds?|finds? diamonds?")) {
				event = "diamonds";
			} else {
				problems.add(line);
				continue;
			}
			String low = what.toLowerCase(Locale.ROOT), action, text = "";
			if (low.startsWith("say ")) {
				action = "say";
				text = what.substring(4).trim();
			} else if (low.startsWith("do ")) {
				action = "do";
				text = what.substring(3).trim();
			} else if (low.matches("dance|spin|wave|show off|celebrate")) {
				action = low.equals("celebrate") ? "dance" : low;
			} else {
				action = "do";                                              // a bare request: "when night: build a shelter"
				text = what;
			}
			out.add(new Rule(event, arg, action, text, line));
		}
		return out;
	}

	// ------------------------------------------------------------------------ one Xen's run
	private final Companion c;
	private String parsedFrom;
	private List<Rule> rules = List.of();
	private final Map<Rule, Long> fired = new HashMap<>();
	private final Set<UUID> near = new HashSet<>();
	private boolean wasNight, wasRaining, wasHungry, wasHurt;
	private long nextCheck;
	private int diamonds = -1;

	Script(Companion c) {
		this.c = c;
	}

	private List<Rule> rules() {
		String s = c.mod.config.script;
		if (!s.equals(parsedFrom)) {
			parsedFrom = s;
			rules = parse(s, new ArrayList<>());
			fired.clear();
		}
		return rules;
	}

	/** About once a second: does anything in the script happen? */
	void tick(boolean hurtNow) {
		if (c.player == null || c.inArena || c.mod.config.script.isBlank()) return;
		long now = c.player.level().getGameTime();
		if (hurtNow) fire("attacked", null, null);
		if (now < nextCheck) return;
		nextCheck = now + 20;
		var level = c.player.level();
		boolean night = level.isDarkOutside(), raining = level.isRaining();
		boolean hungry = c.player.getFoodData().getFoodLevel() <= 6, hurt = c.player.getHealth() < 8;
		if (night && !wasNight) fire("night", null, null);
		if (!night && wasNight) fire("morning", null, null);
		if (raining && !wasRaining) fire("rain", null, null);
		if (hungry && !wasHungry) fire("hungry", null, null);
		if (hurt && !wasHurt) fire("hurt", null, null);
		wasNight = night;
		wasRaining = raining;
		wasHungry = hungry;
		wasHurt = hurt;
		int d = c.items().getOrDefault("diamond", 0);
		if (diamonds >= 0 && d > diamonds) fire("diamonds", null, null);
		diamonds = d;
		Set<UUID> close = new HashSet<>();                                    // someone it knows comes near
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer || p.level() != c.player.level() || p.distanceTo(c.player) > 12 || !c.known.contains(p.getUUID())) continue;
			close.add(p.getUUID());
			if (!near.contains(p.getUUID())) fire("comes", null, p);
		}
		near.clear();
		near.addAll(close);
		for (Rule r : rules()) {
			if (r.event.equals("every")) {
				long gap = Math.max(1, Integer.parseInt(r.arg)) * 1200L;
				if (now - fired.getOrDefault(r, now - gap + 600) >= gap) run(r, null, now);   // the first a little after it joins
			} else if (r.event.equals("sees")) {
				for (LivingEntity e : c.player.level().getEntitiesOfClass(LivingEntity.class, c.player.getBoundingBox().inflate(16),
						x -> x != c.player && x.isAlive())) {
					String what = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().replace('_', ' ');
					String name = e.getName().getString().toLowerCase(Locale.ROOT);
					if ((what.contains(r.arg) || name.equals(r.arg)) && WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, e)) {
						run(r, e instanceof ServerPlayer sp ? sp : null, now);
						break;
					}
				}
			}
		}
	}

	/** Someone said something near it: "hears" rules. */
	void heard(String words, ServerPlayer from) {
		for (Rule r : rules()) {
			if (r.event.equals("hears") && words.toLowerCase(Locale.ROOT).contains(r.arg)) run(r, from, c.player.level().getGameTime());
		}
	}

	private void fire(String event, String arg, ServerPlayer who) {
		long now = c.player.level().getGameTime();
		for (Rule r : rules()) if (r.event.equals(event)) run(r, who, now);
	}

	/** Do what a rule says (at most once a minute for each rule). */
	private void run(Rule r, ServerPlayer who, long now) {
		if (now - fired.getOrDefault(r, -1_000_000L) < 1200 && !r.event.equals("every")) return;
		fired.put(r, now);
		ServerPlayer owner = c.owner == null ? null : c.server.getPlayerList().getPlayer(c.owner);
		ServerPlayer p = who != null ? who : owner;
		String player = p != null ? p.getName().getString() : "friend";
		switch (r.action) {
			case "say" -> c.say(r.text.replace("{name}", c.name).replace("{player}", player));
			case "dance" -> c.antics.celebrate();
			case "spin", "show off" -> c.antics.showOff(r.action.equals("spin"));
			case "wave" -> {
				if (p != null) c.antics.wave(p);
			}
			default -> {                                                        // a request, as if its owner asked
				ServerPlayer from = owner != null ? owner : p;
				if (from == null) return;
				String words = r.text.replace("{player}", player);
				c.request(Chat.understand(words, c.name), from, words);
			}
		}
		XenMod.LOG.info("{} ran its script: {}", c.name, r.line);
	}
}
