package xen.mod.client;

import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/** Mod Menu's own screens (only touched when Mod Menu is installed). */
final class ModMenuScreens {
	private ModMenuScreens() {}

	static Screen mods(Screen parent) {
		return ModMenuApi.createModsScreen(parent);
	}

	/** Xen's settings the way Mod Menu opens them (its config button). */
	static Screen settings(Screen parent) {
		return com.terraformersmc.modmenu.ModMenu.getConfigScreen("xen", parent);
	}
}
