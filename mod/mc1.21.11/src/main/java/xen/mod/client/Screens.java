package xen.mod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Opening a screen (this is the 1.21.11 side). */
final class Screens {
	private Screens() {}

	static void open(Minecraft client, Screen screen) {
		client.setScreen(screen);
	}

	/** The screen that's open, or null (in the game). */
	static Screen current(Minecraft client) {
		return client.screen;
	}

	/** A line over the hotbar (the recorder's on and off). */
	static void overlay(Minecraft client, net.minecraft.network.chat.Component text) {
		if (client.player != null) client.player.displayClientMessage(text, true);
	}

	/** The time of day, 0 to 23999. */
	static long dayTime(net.minecraft.world.level.Level level) {
		return level.getDayTime() % 24000;
	}
}
