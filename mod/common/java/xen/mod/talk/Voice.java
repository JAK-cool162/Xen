package xen.mod.talk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Xen's voice: a small local language model (SmolLM2-360M) that can talk but cannot cheat.
 *
 * <ul>
 *   <li>It only knows what Xen knows: its notes are built from Xen's own senses and beliefs, feelings and body.</li>
 *   <li>It only talks: replies are one line of plain chat, never a command, and it doesn't control what Xen does.</li>
 *   <li>It doesn't make things up about the world: an answer claiming to see something that isn't in its notes is
 *   not sent (it tries again, then just says what it knows).</li>
 * </ul>
 */
public final class Voice {
	public static final String MODEL = "smollm2-360m-instruct-q8_0.gguf";
	public static final String URL = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/" + MODEL;

	static final String PERSONA = "<|im_start|>system\nYou are Xen, a survival companion in Minecraft. You play fair like a real player: "
			+ "you only know what you have seen yourself, and far-away things are only guesses. You feel fear, pain and curiosity. "
			+ "Answer as Xen in one or two short, friendly sentences. Only talk about things in your notes; if you don't know, say so. "
			+ "Never write commands.<|im_end|>\n"
			+ "<|im_start|>user\nNotes: You feel calm. You know there is a pumpkin 4 blocks from you.\n"
			+ "Steve says: what do you see?<|im_end|>\n"
			+ "<|im_start|>assistant\nI can see a pumpkin about 4 blocks from me.<|im_end|>\n"
			+ "<|im_start|>user\nNotes: You feel afraid. You are hurt. Nothing special is around you.\n"
			+ "Alex says: find any gold?<|im_end|>\n"
			+ "<|im_start|>assistant\nNo, I haven't seen any gold. And I'm hurt, so let's be careful.<|im_end|>\n";

	/** Things in the world it could claim to know about. */
	static final String[][] THINGS = {{"diamond"}, {"gold"}, {"iron"}, {"coal"}, {"lava"}, {"water", "lake", "river", "ocean"},
			{"tree", "wood", "log"}, {"zombie"}, {"skeleton"}, {"creeper"}, {"spider"}, {"enderman", "endermen"},
			{"village", "villager"}, {"cave"}, {"chest", "treasure"}, {"mob", "monster"}, {"emerald"}, {"redstone"}, {"lapis"},
			{"copper"}, {"obsidian"}, {"portal", "nether"}, {"dungeon", "spawner"}, {"temple", "ruin"}, {"wolf", "wolves"},
			{"animal", "cow", "pig", "sheep", "chicken", "horse"}, {"pumpkin", "melon"}};
	static final String[] PLURAL = {"diamonds", "gold", "iron", "coal", "lava", "water", "trees", "zombies", "skeletons",
			"creepers", "spiders", "endermen", "villages", "caves", "chests", "mobs", "emeralds", "redstone", "lapis", "copper",
			"obsidian", "portals", "dungeons", "temples", "wolves", "animals", "pumpkins"};
	/** Whole words, plurals too ("iron" is not in "environment"). */
	private static final Pattern[] THING = new Pattern[THINGS.length];
	static {
		for (int t = 0; t < THINGS.length; t++) THING[t] = Pattern.compile("\\b(?:" + String.join("|", THINGS[t]) + ")(?:s|es)?\\b");
	}
	private static final Pattern CLAIM = Pattern.compile("\\b(see|saw|seen|spot|spotted|found|there'?s|there is|there are|near|nearby|"
			+ "close|away|here|over there|next to|ahead|behind)\\b");
	private static final Pattern NEGATION = Pattern.compile("\\b(no|not|n't|never|nothing|haven't|don't|can't|didn't|any)\\b");

	private final Path modelPath;
	private final boolean download;
	private final int threads;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "xen-voice-main");
		t.setDaemon(true);
		return t;
	});
	private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
	private volatile Llm llm;
	private volatile String problem;
	private final Consumer<String> log;

	public Voice(Path modelPath, boolean download, int threads, Consumer<String> log) {
		this.modelPath = modelPath;
		this.download = download;
		this.threads = threads;
		this.log = log;
	}

	public boolean busy() {
		return pending.get() > 0;
	}

	/** Load the model in the background now, so the first answer comes quickly. */
	public void warmUp() {
		worker.submit(() -> {
			try {
				model();
			} catch (Throwable e) {
				problem = e.toString();
				log.accept("Xen's voice is not available: " + e);
			}
		});
	}

	public String problem() {
		return problem;
	}

	/** Answer a player asynchronously; reply is called with safe chat text (or not at all when it can't speak). */
	public void ask(String speaker, String message, String notes, Consumer<String> reply) {
		if (pending.incrementAndGet() > 3) {                          // it can only keep so many questions in mind
			pending.decrementAndGet();
			return;
		}
		worker.submit(() -> {
			try {
				Llm model = model();
				if (model == null) return;
				String turn = "<|im_start|>user\nNotes: " + notes + "\n" + speaker + " says: " + message
						+ "<|im_end|>\n<|im_start|>assistant\n";
				String answer = null;
				for (int attempt = 0; attempt < 2 && answer == null; attempt++) {  // made something up: try once more
					String text = safe(model.generate(turn, 40, 0.5f, 0.9f, System.nanoTime()));
					if (honest(text, notes).equals(text)) answer = text;
				}
				reply.accept(answer != null ? answer : plainly(notes, message));
			} catch (Throwable e) {
				problem = e.toString();
				log.accept("Xen's voice failed: " + e);
			} finally {
				pending.decrementAndGet();
			}
		});
	}

	private synchronized Llm model() throws IOException {
		if (llm != null) return llm;
		if (!Files.exists(modelPath)) {
			if (!download) {
				problem = "no voice model at " + modelPath;
				return null;
			}
			fetch();
		}
		log.accept("Loading Xen's voice (" + modelPath.getFileName() + ")...");
		Llm model = new Llm(modelPath.toString(), threads, 1024);
		model.savePrefix(PERSONA);
		log.accept("Xen can talk now.");
		llm = model;
		return llm;
	}

	private void fetch() throws IOException {
		Files.createDirectories(modelPath.getParent());
		log.accept("Downloading Xen's voice (" + MODEL + ", about 390 MB) to " + modelPath + " ...");
		Path part = modelPath.resolveSibling(MODEL + ".part");
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
		try {
			HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() != 200) throw new IOException("download failed: HTTP " + r.statusCode());
			try (InputStream in = r.body()) {
				Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
		Files.move(part, modelPath, StandardCopyOption.REPLACE_EXISTING);
		log.accept("Xen's voice downloaded.");
	}

	public void close() {
		worker.shutdownNow();
		if (llm != null) llm.close();
	}

	/** One line of plain chat: no commands, no special tokens, at most two sentences. */
	public static String safe(String text) {
		text = text.replaceAll("<\\|[^|]*\\|>", " ").replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
		text = text.replaceFirst("^(Xen\\s*:\\s*)", "");
		while (text.startsWith("/") || text.startsWith("\\")) text = text.substring(1).trim();
		String[] sentences = text.split("(?<=[.!?])\\s+");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(2, sentences.length); i++) sb.append(i > 0 ? " " : "").append(sentences[i]);
		text = sb.toString();
		if (text.length() > 220) text = text.substring(0, text.lastIndexOf(' ', 220) > 0 ? text.lastIndexOf(' ', 220) : 220) + "...";
		return text.isEmpty() ? "..." : text;
	}

	/** Drop sentences claiming things that aren't in Xen's notes. Being told about something isn't seeing it. */
	public static String honest(String reply, String notes) {
		String known = notes.toLowerCase(Locale.ROOT);
		List<String> kept = new ArrayList<>();
		for (String sentence : reply.split("(?<=[.!?])\\s+")) {
			String s = sentence.toLowerCase(Locale.ROOT);
			boolean made_up = false;
			if (CLAIM.matcher(s).find() && !NEGATION.matcher(s).find()) {
				for (Pattern thing : THING) {
					if (thing.matcher(s).find() && !thing.matcher(known).find()) made_up = true;
				}
			}
			if (!made_up) kept.add(sentence);
		}
		return kept.isEmpty() ? "I'm not sure, I haven't seen that." : String.join(" ", kept);
	}

	/** Xen's notes in its own words: its answer when the model's answer can't be trusted. */
	public static String plainly(String notes, String message) {
		String[] sentences = notes.trim().split("(?<=[.!?])\\s+");
		String asked = message.toLowerCase(Locale.ROOT), known = notes.toLowerCase(Locale.ROOT), unseen = null;
		for (int t = 0; t < THING.length && unseen == null; t++) {
			if (THING[t].matcher(asked).find() && !THING[t].matcher(known).find()) unseen = PLURAL[t];
		}
		StringBuilder sb = new StringBuilder(unseen != null ? "I haven't seen any " + unseen + "." : sentences[0]);
		for (int i = 1; i < sentences.length; i++) {
			if (sentences[i].startsWith("You carry")) continue;
			sb.append(' ').append(sentences[i]);
			break;
		}
		String text = sb.toString().replaceAll("\\bYou are\\b", "I'm").replaceAll("\\bYou know there is\\b", "I know there's")
				.replaceAll("\\byou're\\b", "I'm").replaceAll("\\bYou\\b", "I").replaceAll("\\byou\\b", "me");
		return text.isBlank() ? "I'm not sure, I haven't seen that." : text;
	}

	/** Xen's notes: its feelings, body and what it perceives, in plain words. */
	public static String notes(String mood, boolean hurt, float health, float hunger, String carrying, String perceived) {
		StringBuilder sb = new StringBuilder("You feel ").append(mood).append('.');
		if (hurt || health < 8) sb.append(" You are hurt.");
		if (hunger < 8) sb.append(" You are hungry.");
		if (!carrying.isEmpty()) sb.append(" You carry ").append(carrying).append('.');
		sb.append(' ').append(perceived);
		return sb.toString();
	}
}
