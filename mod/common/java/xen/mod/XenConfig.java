package xen.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/xen.json */
public final class XenConfig {
	/** Xen chats: it understands requests ("Xen, get some wood"), answers, and says how it feels. */
	public boolean chat = true;
	/**
	 * The small chat model (SmolLM2-360M) that understands requests in any words and answers: "auto" (when the game has
	 * 3 GB of memory or more), "on" or "off". Without it Xen still understands keywords and answers in plain words.
	 */
	public String chatModel = "auto";
	/** Download the chat model (about 390 MB) to config/xen/ the first time it's needed. */
	public boolean downloadChatModel = true;
	/** CPU threads the chat model may use (it runs in the background). */
	public int chatThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	/** Keep learning from everything it lives through. */
	public boolean learn = true;
	/** How many Xens one player may summon (0 = no limit; operators have no limit). */
	public int maxPerPlayer = 1;
	/** Minimum ticks between decisions (actions last at least a quarter of a second anyway). */
	public int decisionTicks = 5;
	/** Follow the owner when further away than this. */
	public double followDistance = 10;
	/** Xen leaves when its owner leaves (and comes back with /xen summon, inventory kept). */
	public boolean leaveWithOwner = true;
	/** Save the brain this often (minutes). */
	public int saveMinutes = 5;

	static XenConfig load(Path path) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		XenConfig config = new XenConfig();
		try {
			if (Files.exists(path)) config = gson.fromJson(Files.readString(path), XenConfig.class);
			Files.createDirectories(path.getParent());
			Files.writeString(path, gson.toJson(config));
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("Could not read {}: {}", path, e.toString());
		}
		return config;
	}
}
