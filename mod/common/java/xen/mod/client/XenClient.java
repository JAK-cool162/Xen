package xen.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import xen.mod.XenMod;

/**
 * Client side. Only used for testing the settings screen without a person: with {@code -Dxen.uiTest=true} the game
 * opens Mod Menu's list, then Xen's settings and each of its categories, then closes (the test takes screenshots in between).
 */
public class XenClient implements ClientModInitializer {
	private int ticks = -1;
	/** The open screen, from Fabric's screen events (the game's own field became a method in 26.3). */
	private Screen screen;

	/** For the screenshot test: the mouse to the top left corner (by reflection: GLFW isn't there on every version). */
	private static void cursorToCorner(net.minecraft.client.Minecraft client) {
		try {
			Object window = client.getClass().getMethod("getWindow").invoke(client);
			long handle = (long) window.getClass().getMethod("handle").invoke(window);
			Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwSetCursorPos", long.class, double.class, double.class).invoke(null, handle, 1.0, 1.0);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			// no GLFW (26.3 uses SDL): the tooltips just show
		}
	}

	@Override
	public void onInitializeClient() {
		xen.mod.talk.Chat.gpu = GlAccelerator::create;                 // in a game client, the chat model can use the GPU
		String gpuTest = System.getProperty("xen.gpuTest");
		if (gpuTest != null) {                                       // -Dxen.gpuTest=<model>: CPU vs GPU, then quit
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (++ticks == 100) new Thread(() -> GpuCheck.run(gpuTest, client), "xen-gpu-test").start();
			});
			return;
		}
		if (!Boolean.getBoolean("xen.uiTest")) return;
		XenSettingsScreen.noTooltips = true;                          // (they'd cover the screenshots)
		ScreenEvents.AFTER_INIT.register((client, opened, width, height) -> screen = opened);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (ticks < 0 && screen == null) return;   // wait until the game has loaded and shows its first screen
			ticks++;
			if (ticks % 20 == 0) cursorToCorner(client);   // (so no tooltip covers the screenshots)
			if (ticks == 100 && FabricLoader.getInstance().isModLoaded("modmenu")) {
				XenMod.LOG.info("xen ui test: mods list");
				Screens.open(client, ModMenuScreens.mods(screen));
			} else if (ticks == 300) {
				boolean viaModMenu = FabricLoader.getInstance().isModLoaded("modmenu");
				XenMod.LOG.info("xen ui test: settings{}", viaModMenu ? " (opened through Mod Menu)" : "");
				Screens.open(client, viaModMenu ? ModMenuScreens.settings(screen) : new XenSettingsScreen(screen));
			} else if (ticks > 300 && ticks % 100 == 0 && ticks < 300 + 100 * XenSettingsScreen.tabs()) {   // then each category
				int tab = (ticks - 300) / 100;
				XenMod.LOG.info("xen ui test: tab {}", tab);
				if (tab == XenSettingsScreen.tabs() - 1) {                  // Experimental, with something written in it
					var settings = xen.mod.XenSettings.get();
					settings.set("instructions", "You love cats and hate the rain.\nPip: you're a pirate and talk like one.");
					settings.set("script", "when night: do build a shelter\nwhen someone comes: wave\nwhen hears hello: dance\nwhen sees creeper: say RUN!");
				}
				Screens.open(client, XenSettingsScreen.onTab(null, tab));
			} else if (ticks == 300 + 100 * XenSettingsScreen.tabs()) {
				XenMod.LOG.info("xen ui test: done");
				client.stop();
			}
		});
	}
}
