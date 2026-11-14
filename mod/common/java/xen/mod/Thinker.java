package xen.mod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Xen thinking it over in words: now and then (the "thinkEvery" setting) it tells a small language model, running
 * beside the server (the "thinkUrl" setting: any OpenAI-style chat endpoint, like llama.cpp's server with SmolLM2),
 * who it is, how it's doing and the choices its brain has, and asks which it would do and why. The answer comes back
 * later (the game never waits for it); the choice it names is its own idea for a while, a little more keen on it.
 * Off when there's no URL.
 */
final class Thinker {
	private final XenMod mod;
	private HttpClient http;
	private final Map<String, Long> askedAt = new ConcurrentHashMap<>();
	private final Map<String, String> idea = new ConcurrentHashMap<>();
	private final Map<String, Long> ideaAt = new ConcurrentHashMap<>();
	private final Map<String, Boolean> waiting = new ConcurrentHashMap<>();

	Thinker(XenMod mod) {
		this.mod = mod;
	}

	/** Its own idea, if it has a fresh one (asking for a new one now and then). */
	Btb.Task idea(Companion c, List<String> options, Btb.Obs o) {
		String url = mod.config.thinkUrl;
		if (url == null || url.isBlank() || options.size() < 2 || c.player == null) return null;
		long now = System.currentTimeMillis();
		if (!waiting.getOrDefault(c.name, false) && now - askedAt.getOrDefault(c.name, 0L) > Math.max(10, mod.config.thinkEvery) * 1000L) {
			askedAt.put(c.name, now);
			waiting.put(c.name, true);
			ask(c, url, options, o);
		}
		String t = idea.get(c.name);
		if (t == null || now - ideaAt.getOrDefault(c.name, 0L) > 120_000) return null;
		try {
			return Btb.Task.valueOf(t);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** A leader writes its team's laws and gives each teammate a role (a title and what it does), in words; the roles lean their choices. */
	void teamTalk(Companion lead, List<Companion> members) {
		String url = mod.config.talkUrl == null || mod.config.talkUrl.isBlank() ? mod.config.thinkUrl : mod.config.talkUrl;
		if (url == null || url.isBlank() || lead.player == null) return;
		StringBuilder names = new StringBuilder();
		for (Companion m : members) if (m != lead) names.append(names.length() > 0 ? ", " : "").append(m.name).append(m.personality.bravery > 0.5f ? " (brave)" : m.personality.diligence > 0.5f ? " (hard-working)" : m.personality.curiosity > 0.5f ? " (curious)" : " (calm)");
		String team = String.valueOf(mod.teamOf(lead)).replace("xen_", "");
		String sys = "You are " + lead.name + ", leader of the " + team + " team in Minecraft, racing the other team to full enchanted diamond armor. Be short and playful.";
		String user = "Write 3 team laws, one per line, each starting with LAW: . Then give each teammate a role, one per line, as NAME: ROLE - what they do. Roles: Miner, Cook, Hunter, Scout, Builder, Smith, Guard. Teammates: " + names + ".";
		post(url, sys, user, 220, text -> mod.server.execute(() -> applyTeamTalk(lead, members, text)));
	}

	private void applyTeamTalk(Companion lead, List<Companion> members, String text) {
		List<String> laws = new java.util.ArrayList<>();
		int roles = 0;
		for (String raw : text.split("\\n")) {
			String line = raw.replaceAll("^[\\s*\\-\\d.)]+", "").strip();
			if (line.toUpperCase(Locale.ROOT).startsWith("LAW")) {
				String law = line.replaceFirst("(?i)^law\\s*\\d*\\s*[:.-]\\s*", "").strip();
				if (law.length() > 3 && laws.size() < 3) laws.add(law.length() > 90 ? law.substring(0, 90) : law);
				continue;
			}
		}
		for (Companion m : members) {                                          // "Ann - Miner", "Ann: Cook - keeps us fed", anywhere in its words
			if (m == lead) continue;
			var hit = java.util.regex.Pattern.compile("\\b" + m.name + "\\b\\s*[:\\-\u2013(]\\s*([A-Za-z][A-Za-z' ]{2,40}?)(?=[,;.)\\n]|\\s+-|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
			if (!hit.find()) continue;
			String rest = hit.group(1).strip();
			m.role = rest.length() > 40 ? rest.substring(0, 40) : rest;
			m.roleTask = roleTask(rest);
			roles++;
		}
		for (Companion m : members)                                           // (anyone it didn't name: a role that fits its nature)
			if (m != lead && (m.role == null || m.role.isEmpty())) {
				m.role = m.personality.bravery > 0.6f ? "Guard" : m.personality.diligence > 0.6f ? "Miner" : m.personality.curiosity > 0.6f ? "Scout" : "Cook";
				m.roleTask = roleTask(m.role);
			}
		if (!laws.isEmpty()) lead.say("Team laws: " + String.join(" / ", laws));
		StringBuilder rs = new StringBuilder();
		for (Companion m : members) if (m != lead && m.role != null) rs.append(rs.length() > 0 ? "; " : "").append(m.name).append(": ").append(m.role);
		XenMod.LOG.info("{} (leads {}) laws: {} | roles ({} from its words): {}", lead.name, String.valueOf(mod.teamOf(lead)).replace("xen_", ""), laws, roles, rs);
	}

	/** What a role leans it toward. */
	static Btb.Task roleTask(String role) {
		String r = role.toLowerCase(Locale.ROOT);
		if (r.matches(".*\\b(miner|digger|mining|mine)\\b.*")) return Btb.Task.DIAMONDS;
		if (r.matches(".*\\b(cook|chef|hunter|farmer|food|baker)\\b.*")) return Btb.Task.FOOD;
		if (r.matches(".*\\b(scout|explorer|ranger|runner)\\b.*")) return Btb.Task.EXPLORE;
		if (r.matches(".*\\b(builder|architect|mason)\\b.*")) return Btb.Task.HOME;
		if (r.matches(".*\\b(smith|enchanter|crafter|blacksmith|wizard)\\b.*")) return Btb.Task.ENCHANT;
		if (r.matches(".*\\b(gatherer|lumberjack|woodcutter)\\b.*")) return Btb.Task.WOOD;
		return null;
	}

	/** A villager it meets gets a name and a role of its own, from the model (its name shows over its head). */
	void nameVillager(Companion c, net.minecraft.world.entity.npc.villager.Villager v) {
		String url = mod.config.talkUrl == null || mod.config.talkUrl.isBlank() ? mod.config.thinkUrl : mod.config.talkUrl;
		if (url == null || url.isBlank() || v.hasCustomName()) return;
		String job = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().profession().value()).getPath();
		post(url, "You name villagers in a cozy Minecraft village. Answer with only a name and a title, like: Mira the Lantern-Keeper",
				"Name this " + (job.equals("none") ? "villager" : job) + ".", 16, text -> mod.server.execute(() -> {
					String name = text.split("\\n")[0].replaceAll("[\"*]", "").strip();
					if (name.length() < 3 || name.length() > 32 || v.hasCustomName() || !v.isAlive()
							|| !name.matches("[A-Z][A-Za-z'-]+ (the|of) [A-Z][A-Za-z' -]+")) return;   // (a name and a title, nothing else: "No name found for this farmer." is not one)
					v.setCustomName(net.minecraft.network.chat.Component.literal(name));
					XenMod.LOG.info("{} names a villager: {}", c.name, name);
				}));
	}

	private void post(String url, String sys, String user, int tokens, java.util.function.Consumer<String> then) {
		if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		String body = "{\"messages\":[{\"role\":\"system\",\"content\":\"" + esc(sys) + "\"},{\"role\":\"user\",\"content\":\"" + esc(user) + "\"}],\"max_tokens\":" + tokens + ",\"temperature\":0.8}";
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			if (err != null || res == null || res.statusCode() != 200) return;
			try {
				var json = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
				then.accept(json.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().strip());
			} catch (RuntimeException e) {
				// (nothing it could use)
			}
		});
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}

	private void ask(Companion c, String url, List<String> options, Btb.Obs o) {
		if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		var p = c.personality;
		String team = mod.teamOf(c);
		String who = String.format(Locale.ROOT, "You are %s, a Minecraft player%s. You are %s, %s and %s. Your team races the other to full enchanted diamond armor. Answer in one short line: the option you pick (its word in capitals) and why.",
				c.name, team == null ? "" : " on the " + team.replace("xen_", "") + " team", p.bravery > 0.5f ? "brave" : "careful", p.curiosity > 0.5f ? "curious" : "steady", p.diligence > 0.5f ? "hard-working" : "easygoing");
		String state = String.format(Locale.ROOT, "Health %.0f of 20, food %d of 20, %d meals in your bag, %s, %s, pickaxe tier %d, %d diamonds, %s. Your options: %s. Which do you do?",
				o.health(), o.food(), o.meals(), o.night() ? "night" : "day", o.under() ? "underground" : "on the surface", o.tier(), o.diamonds(),
				o.danger() >= 2 ? "monsters close" : o.danger() == 1 ? "a monster about" : "no monsters about", String.join("; ", options));
		String body = "{\"messages\":[{\"role\":\"system\",\"content\":\"" + esc(who) + "\"},{\"role\":\"user\",\"content\":\"" + esc(state) + "\"}],\"max_tokens\":48,\"temperature\":0.7}";
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			waiting.put(c.name, false);
			if (err != null || res == null || res.statusCode() != 200) return;
			try {
				var json = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
				String text = json.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().strip();
				String picked = null;
				int at = Integer.MAX_VALUE;
				String up = text.toUpperCase(Locale.ROOT);
				for (String op : options) {
					String name = op.split(" ")[0];
					int i = up.indexOf(name);
					if (i >= 0 && i < at) {
						at = i;
						picked = name;
					}
				}
				String line = text.length() > 160 ? text.substring(0, 160) : text;
				if (picked != null) {
					idea.put(c.name, picked);
					ideaAt.put(c.name, System.currentTimeMillis());
				}
				XenMod.LOG.info("{} thinks: {}{}", c.name, line.replace('\n', ' '), picked == null ? "" : " [its idea: " + picked + "]");
			} catch (RuntimeException e) {
				// (an answer it couldn't make sense of: no idea this time)
			}
		});
	}
}
