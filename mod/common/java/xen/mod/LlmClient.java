package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * Any OpenAI-style chat model, by its API: OpenAI, DeepSeek, OpenRouter, Groq, Together, Mistral, a local llama.cpp
 * or Ollama or LM Studio server: whatever speaks POST .../chat/completions with "Authorization: Bearer KEY". The
 * settings say where (brainUrl: the base, like https://api.deepseek.com or https://openrouter.ai/api/v1, or the full
 * .../chat/completions), which model (brainModel), and the key (brainKey; empty: the XEN_BRAIN_KEY environment
 * variable, or the first line of config/xen/brain_key.txt, so a key needn't sit in the settings file). Calls never
 * block the game: each comes back later, on its own thread, and a cap on calls a minute keeps the bill in hand.
 */
final class LlmClient {
	private final XenMod mod;
	private HttpClient http;
	private final Deque<Long> recent = new ArrayDeque<>();
	/** How it's gone: calls, failures, the last error, time taken. */
	volatile int calls, failures, refused;
	volatile String lastError = "";
	volatile long lastMillis, totalMillis;

	LlmClient(XenMod mod) {
		this.mod = mod;
	}

	/** Set up: a URL to call. */
	boolean on() {
		String u = mod.config.brainUrl;
		return u != null && !u.isBlank();
	}

	/** The chat endpoint from what the setting says (a base, a /v1, or the whole path). */
	static String endpoint(String url) {
		String u = url.trim();
		while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
		if (u.endsWith("/chat/completions")) return u;
		return u + "/chat/completions";
	}

	/** The key: the setting, else XEN_BRAIN_KEY, else config/xen/brain_key.txt (empty: none, as a local server wants). */
	String key() {
		String k = mod.config.brainKey;
		if (k != null && !k.isBlank()) return k.trim();
		String env = System.getenv("XEN_BRAIN_KEY");
		if (env != null && !env.isBlank()) return env.trim();
		try {
			Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("brain_key.txt");
			if (Files.exists(f)) {
				for (String line : Files.readAllLines(f)) if (!line.isBlank()) return line.trim();
			}
		} catch (Exception e) {
			// (no file: no key)
		}
		return "";
	}

	/** Room for another call this minute (the brainPerMinute cap, all Xens together)? Takes the slot if so. */
	synchronized boolean slot() {
		long now = System.currentTimeMillis();
		while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) recent.pollFirst();
		if (recent.size() >= Math.max(1, mod.config.brainPerMinute)) {
			refused++;
			return false;
		}
		recent.addLast(now);
		return true;
	}

	/** A message: its role, its words, and a picture (a PNG's bytes, or null). */
	record Msg(String role, String text, byte[] png) {
		Msg(String role, String text) {
			this(role, text, null);
		}
	}

	/**
	 * Ask: the messages, how long an answer may be, how wild. The answer's text (a thinking model's thinking and any
	 * code fences taken off), or a failed future. Never on the server's thread.
	 */
	CompletableFuture<String> chat(java.util.List<Msg> msgs, int maxTokens, double temperature) {
		if (!on()) return CompletableFuture.failedFuture(new IllegalStateException("no brainUrl set"));
		if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
		JsonObject body = new JsonObject();
		String model = mod.config.brainModel;
		if (model != null && !model.isBlank()) body.addProperty("model", model.trim());
		JsonArray arr = new JsonArray();
		for (Msg m : msgs) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			if (m.png() == null) o.addProperty("content", m.text());
			else {                                                            // (a picture too: the OpenAI vision form, which most vision models' APIs take)
				JsonArray parts = new JsonArray();
				JsonObject t = new JsonObject();
				t.addProperty("type", "text");
				t.addProperty("text", m.text());
				parts.add(t);
				JsonObject img = new JsonObject();
				img.addProperty("type", "image_url");
				JsonObject iu = new JsonObject();
				iu.addProperty("url", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(m.png()));
				img.add("image_url", iu);
				parts.add(img);
				o.add("content", parts);
			}
			arr.add(o);
		}
		body.add("messages", arr);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("temperature", temperature);
		body.addProperty("stream", false);
		HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint(mod.config.brainUrl))).timeout(Duration.ofSeconds(Math.max(5, mod.config.brainTimeout)))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		String k = key();
		if (!k.isEmpty()) rb.header("Authorization", "Bearer " + k);
		if (mod.config.brainUrl.contains("openrouter.ai")) rb.header("X-Title", "Xen");   // (OpenRouter likes to know the app; nothing personal)
		long t0 = System.currentTimeMillis();
		calls++;
		return http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
			lastMillis = System.currentTimeMillis() - t0;
			totalMillis += lastMillis;
			if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + shorten(res.body(), 200));
			JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
			if (json.has("error")) throw new IllegalStateException(shorten(json.get("error").toString(), 200));
			var msg = json.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
			String text = "";
			var content = msg.get("content");
			if (content != null && content.isJsonPrimitive()) text = content.getAsString();
			else if (content != null && content.isJsonArray())                    // (some give the answer in parts)
				for (var p : content.getAsJsonArray()) if (p.isJsonObject() && p.getAsJsonObject().has("text")) text += p.getAsJsonObject().get("text").getAsString();
			return clean(text);
		}).whenComplete((r, e) -> {
			if (e != null) {
				failures++;
				lastError = shorten(String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), 240);
			}
		});
	}

	/** A thinking model's "<think>...</think>" and a ```json fence, off. */
	static String clean(String s) {
		String t = s == null ? "" : s;
		t = t.replaceAll("(?s)<think>.*?</think>", "");
		t = t.replaceAll("(?s)^.*</think>", "");                               // (its thinking cut off at the front)
		t = t.replaceAll("(?m)^```[a-zA-Z]*\\s*$", "");
		return t.strip();
	}

	/** The first {...} in an answer, as JSON (null: none it could read). */
	static JsonObject firstJson(String s) {
		int a = s.indexOf('{');
		while (a >= 0) {
			int depth = 0;
			boolean str = false, esc = false;
			for (int i = a; i < s.length(); i++) {
				char ch = s.charAt(i);
				if (str) {
					if (esc) esc = false;
					else if (ch == '\\') esc = true;
					else if (ch == '"') str = false;
					continue;
				}
				if (ch == '"') str = true;
				else if (ch == '{') depth++;
				else if (ch == '}' && --depth == 0) {
					try {
						return JsonParser.parseString(s.substring(a, i + 1)).getAsJsonObject();
					} catch (RuntimeException e) {
						break;
					}
				}
			}
			a = s.indexOf('{', a + 1);
		}
		return null;
	}

	private static String shorten(String s, int n) {
		if (s == null) return "";
		s = s.replace('\n', ' ');
		return s.length() > n ? s.substring(0, n) + "..." : s;
	}

	/** One line on how it's going. */
	String status() {
		String k = key();
		return String.format(java.util.Locale.ROOT, "brain %s: %s, model %s, key %s; %d calls, %d failed, %d held back by the cap (%d a minute), last %d ms, average %d ms%s",
				on() ? "on" : "off", on() ? endpoint(mod.config.brainUrl) : "no brainUrl", mod.config.brainModel == null || mod.config.brainModel.isBlank() ? "(server's default)" : mod.config.brainModel,
				k.isEmpty() ? "none" : "set (" + k.length() + " chars)", calls, failures, refused, mod.config.brainPerMinute, lastMillis, calls == 0 ? 0 : totalMillis / Math.max(1, calls - failures),
				lastError.isEmpty() ? "" : "; last error: " + lastError);
	}
}
