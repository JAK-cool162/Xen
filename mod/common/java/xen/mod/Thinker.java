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
