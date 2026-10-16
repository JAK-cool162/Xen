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

	private static java.lang.reflect.Field screenField;
	private static java.lang.reflect.Method guiScreen;
	private static boolean looked;

	/** The screen that's open, or null: 26.1 has the field Minecraft.screen, 26.3 Minecraft.gui.screen(). */
	static Screen current(Minecraft client) {
		try {
			if (!looked) {
				looked = true;
				try {
					screenField = Minecraft.class.getField("screen");
				} catch (NoSuchFieldException e) {
					guiScreen = Minecraft.class.getField("gui").getType().getMethod("screen");
				}
			}
			if (screenField != null) return (Screen) screenField.get(client);
			return guiScreen == null ? null : (Screen) guiScreen.invoke(Minecraft.class.getField("gui").get(client));
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** A line over the hotbar (the recorder's on and off). */
	static void overlay(Minecraft client, net.minecraft.network.chat.Component text) {
		if (client.player != null) client.player.sendOverlayMessage(text);
	}

	/** The time of day, 0 to 23999 (26.x: world clocks). */
	static long dayTime(net.minecraft.world.level.Level level) {
		return level.getOverworldClockTime() % 24000;
	}
}
