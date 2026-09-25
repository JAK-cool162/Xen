package xen.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import xen.mod.XenMod;

/**
 * Client side. Only used for testing the settings screen without a person: with {@code -Dxen.uiTest=true} the game
 * opens Mod Menu's list, then Xen's settings and each of its tabs, then closes (the test takes screenshots in between).
 */
public class XenClient implements ClientModInitializer {
	private int ticks = -1;
	/** The open screen, from Fabric's screen events (the game's own field became a method in 26.3). */
	private Screen screen;

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
		ScreenEvents.AFTER_INIT.register((client, opened, width, height) -> screen = opened);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (ticks < 0 && screen == null) return;   // wait until the game has loaded and shows its first screen
			ticks++;
			if (ticks == 100 && FabricLoader.getInstance().isModLoaded("modmenu")) {
				XenMod.LOG.info("xen ui test: mods list");
				Screens.open(client, ModMenuScreens.mods(screen));
			} else if (ticks == 300) {
				boolean viaModMenu = FabricLoader.getInstance().isModLoaded("modmenu");
				XenMod.LOG.info("xen ui test: settings{}", viaModMenu ? " (opened through Mod Menu)" : "");
				Screens.open(client, viaModMenu ? ModMenuScreens.settings(screen) : new XenSettingsScreen(screen));
			} else if (ticks > 300 && ticks % 100 == 0 && ticks <= 800) {   // then each tab
				int tab = (ticks - 300) / 100;
				XenMod.LOG.info("xen ui test: tab {}", tab);
				Screens.open(client, XenSettingsScreen.onTab(null, tab));
			} else if (ticks == 900) {
				XenMod.LOG.info("xen ui test: done");
				client.stop();
			}
		});
	}
}
