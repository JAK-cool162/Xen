package xen.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Opening a screen (this is the 1.21.11 side). */
final class Screens {
	private Screens() {}

	static void open(Minecraft client, Screen screen) {
		client.setScreen(screen);
	}
}
