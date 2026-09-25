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

	public void save() {
		config.save();
		if (XenMod.INSTANCE != null) XenMod.INSTANCE.applySettings();
	}
}
