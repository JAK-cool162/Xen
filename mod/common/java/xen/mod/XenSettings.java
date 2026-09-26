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
