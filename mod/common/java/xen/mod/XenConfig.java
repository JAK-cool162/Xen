package xen.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/xen.json */
public final class XenConfig {
	/** Xen may talk (short reactions, and answers from its small language model). */
	public boolean talk = true;
	/** Download the voice model (SmolLM2-360M, about 390 MB) on first use. */
	public boolean downloadVoice = true;
	/** CPU threads the voice may use (it runs in the background, at low priority). */
	public int voiceThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
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
