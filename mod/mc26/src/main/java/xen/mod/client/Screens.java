package xen.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Opening a screen (this is the 26.x side): 26.1 has Minecraft.setScreen, 26.3 moved it to Minecraft.gui. */
final class Screens {
	private Screens() {}

	static void open(Minecraft client, Screen screen) {
		try {
			try {
				Minecraft.class.getMethod("setScreen", Screen.class).invoke(client, screen);
			} catch (NoSuchMethodException e) {
				Object gui = Minecraft.class.getField("gui").get(client);
				gui.getClass().getMethod("setScreen", Screen.class).invoke(gui, screen);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not open " + screen, e);
		}
	}
}
