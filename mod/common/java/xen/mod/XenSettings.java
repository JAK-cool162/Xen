package xen.mod;

import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

/** The settings, for the settings screen: the running mod's (so changes apply at once), or config/xen.json. */
public final class XenSettings {
	private final XenConfig config;

	private XenSettings(XenConfig config) {
		this.config = config;
	}

	public static XenSettings get() {
		return new XenSettings(XenMod.INSTANCE != null && XenMod.INSTANCE.config != null ? XenMod.INSTANCE.config
				: XenConfig.load(FabricLoader.getInstance().getConfigDir().resolve("xen.json")));
	}

	public String value(String key) {
		try {
			return String.valueOf(XenConfig.class.getField(key).get(config));
		} catch (ReflectiveOperationException e) {
			return "";
		}
	}

	public List<String> skins() {
		return config.skins;
	}

	public void set(String key, String value) {
		String error = config.set(key, value);
		if (error != null) XenMod.LOG.warn("Setting {}: {}", key, error);
		else config.save();                                               // saved right away: nothing's lost if the game stops
	}

	/** The big brain's key file (config/xen/brain_key.txt): kept out of xen.json, so the settings can be shared. */
	private static java.nio.file.Path brainKeyFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("brain_key.txt");
	}

	/** Is there a key for the big brain (the setting, the environment, or the file)? */
	public static boolean brainKeySet() {
		if (XenMod.INSTANCE != null && XenMod.INSTANCE.config != null && XenMod.INSTANCE.config.brainKey != null && !XenMod.INSTANCE.config.brainKey.isBlank()) return true;
		String env = System.getenv("XEN_BRAIN_KEY");
		if (env != null && !env.isBlank()) return true;
		try {
			var f = brainKeyFile();
			return java.nio.file.Files.exists(f) && !java.nio.file.Files.readString(f).isBlank();
		} catch (Exception e) {
			return false;
		}
	}

	/** The big brain's key, to its file (empty: the file goes). */
	public static void setBrainKey(String key) {
		try {
			var f = brainKeyFile();
			if (key == null || key.isBlank()) java.nio.file.Files.deleteIfExists(f);
			else {
				java.nio.file.Files.createDirectories(f.getParent());
				java.nio.file.Files.writeString(f, key.trim() + "\n");
			}
		} catch (Exception e) {
			XenMod.LOG.warn("Xen: couldn't keep the brain key: {}", e.toString());
		}
	}

	/** One call to the big brain, to see the URL, model and key work; the result (or why not) comes back in words. */
	public static void testBrain(java.util.function.Consumer<String> done) {
		XenMod m = XenMod.INSTANCE;
		if (m == null || m.server == null) {
			done.accept("Open a world first (on a server: /xen brain test).");
			return;
		}
		if (!m.bigBrain.llm.on()) {
			done.accept("Set the API URL first.");
			return;
		}
		long t0 = System.currentTimeMillis();
		m.bigBrain.llm.chat(java.util.List.of(new LlmClient.Msg("user", "Reply with exactly: Xen brain online")), 20, 0.0)
				.whenComplete((r, e) -> done.accept(e != null ? "Failed: " + m.bigBrain.llm.lastError : "Works (" + (System.currentTimeMillis() - t0) + " ms): " + r));
	}

	/** How the big brain is going, in a line. */
	public static String brainStatus() {
		XenMod m = XenMod.INSTANCE;
		return m == null ? "" : m.bigBrain.llm.status();
	}

	public void reset() {
		config.reset();
	}

	/** These settings back to how they come. */
	public void reset(List<String> keys) {
		XenConfig fresh = new XenConfig();
		for (String key : keys) {
			try {
				var f = XenConfig.class.getField(key);
				f.set(config, f.get(fresh));
			} catch (ReflectiveOperationException e) {
				XenMod.LOG.warn("Setting {}: {}", key, e.toString());
			}
		}
	}

	/** The lines of a script that Xen can't read (shown under the script box). */
	public static List<String> scriptProblems(String script) {
		List<String> problems = new java.util.ArrayList<>();
		Script.parse(script, problems);
		return problems;
	}

	/** The journal (what Xens see, think, say and hear), for copying; or why there's none. */
	public static String journalText() {
		if (XenMod.INSTANCE == null || XenMod.INSTANCE.journal == null) return "";
		return XenMod.INSTANCE.journal.text();
	}

	/** The journal saved as a file: where it went (or what went wrong). */
	public static String saveJournal() {
		if (XenMod.INSTANCE == null || XenMod.INSTANCE.journal == null) return "No journal yet.";
		try {
			return "Saved: " + FabricLoader.getInstance().getGameDir().relativize(XenMod.INSTANCE.journal.save());
		} catch (java.io.IOException | IllegalArgumentException e) {
			return "Couldn't save it: " + e.getMessage();
		}
	}

	public void save() {
		config.save();
		if (XenMod.INSTANCE != null) XenMod.INSTANCE.applySettings();
	}
}
