package xen.mod.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import xen.mod.talk.Chat;
import xen.mod.talk.Llm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks the Java language model against Python's (tokens exactly, greedy text closely), and that the model is
 * unpacked (from the all-in-one jar, or a download) only when it's exactly the right file.
 */
public final class LlmCheck {
	static int unpackCheck(Path model) throws java.io.IOException {
		Path dir = Files.createTempDirectory("xen-unpack");
		Path good = dir.resolve(Chat.MODEL), bad = dir.resolve("damaged.gguf");
		try (var in = Files.newInputStream(model)) {
			Chat.unpack(in, good);
		}
		boolean kept = Files.size(good) == Files.size(model);
		byte[] start;
		try (var in = Files.newInputStream(model)) {
			start = in.readNBytes(1 << 20);                           // only the first megabyte: a cut-off copy
		}
		boolean refused;
		try (var in = new java.io.ByteArrayInputStream(start)) {
			Chat.unpack(in, bad);
			refused = false;
		} catch (java.io.IOException e) {
			refused = !Files.exists(bad) && !Files.exists(dir.resolve("damaged.gguf.part"));
		}
		Files.deleteIfExists(good);
		Files.deleteIfExists(dir);
		System.out.println("unpacking: the model is kept " + kept + ", a damaged copy is refused " + refused);
		return kept && refused ? 0 : 1;
	}

	public static void main(String[] args) throws Exception {
		String model = args[0];
		Path fixtures = Path.of(args.length > 1 ? args[1] : "mod/common/test/fixtures");
		JsonObject ref = new Gson().fromJson(Files.readString(fixtures.resolve("llm.json")), JsonObject.class);
		long t0 = System.nanoTime();
		Llm llm = new Llm(model, Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 512);
		System.out.printf("loaded in %.1fs%n", (System.nanoTime() - t0) / 1e9);
		int bad = 0;
		for (Map.Entry<String, JsonElement> e : ref.getAsJsonObject("tokens").entrySet()) {
			List<Integer> want = new ArrayList<>();
			for (JsonElement v : e.getValue().getAsJsonArray()) want.add(v.getAsInt());
			List<Integer> got = llm.tokenizer.encode(e.getKey());
			if (!got.equals(want)) {
				bad++;
				System.out.println("TOKENS DIFFER for " + e.getKey() + ": " + got + " vs " + want);
			}
		}
		System.out.println("tokenizer: " + (ref.getAsJsonObject("tokens").size() - bad) + "/" + ref.getAsJsonObject("tokens").size() + " match Python");
		for (JsonElement g : ref.getAsJsonArray("greedy")) {
			JsonObject c = g.getAsJsonObject();
			long t = System.nanoTime();
			String text = llm.generate(c.get("prompt").getAsString(), 20, 0f, 1f, 0);
			double secs = (System.nanoTime() - t) / 1e9;
			System.out.printf("java  : %s  (%.1fs)%npython: %s%n", text, secs, c.get("text").getAsString());
			String want = c.get("text").getAsString().trim(), got = text.trim();
			// Greedy text must be the same up to where one of them stops (ending is a near-tie in 8-bit math).
			if (!(got.startsWith(want) || want.startsWith(got)) || Math.min(got.length(), want.length()) < 8) bad++;
		}
		llm.close();
		bad += unpackCheck(Path.of(model));
		System.out.println(bad == 0 ? "LLM CHECKS PASSED" : bad + " LLM difference(s)");
		System.exit(bad == 0 ? 0 : 1);
	}
}
