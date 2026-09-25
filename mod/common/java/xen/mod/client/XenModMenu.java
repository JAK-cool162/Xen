package xen.mod.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu shows a settings button for Xen Companion that opens {@link XenSettingsScreen}. */
public class XenModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return XenSettingsScreen::new;
	}
}
