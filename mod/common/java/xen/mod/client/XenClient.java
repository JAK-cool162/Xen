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

	/** A Build Axe click in this game: to the server as a corner (or "the screen again"), if the server has Xen too. */
	private static net.minecraft.world.InteractionResult axeClick(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level,
			net.minecraft.core.BlockPos pos, int which) {
		if (!level.isClientSide() || !xen.mod.BuildAxe.holding(player)
				|| !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(xen.mod.BuildAxe.Corner.TYPE)) return net.minecraft.world.InteractionResult.PASS;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new xen.mod.BuildAxe.Corner(pos.immutable(), which));
		// (a hit held back with SUCCESS sends nothing more; a right-click with SUCCESS still goes to the server as a use,
		// and the server would take it again: a block left out, then put back. FAIL holds it back and sends nothing.)
		return which == 1 ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.FAIL;
	}

	/**
	 * With the Build Axe in hand, its clicks aim far (BuildAxe.REACH, 160 blocks): before the game handles them, it
	 * takes them and looks along where the player looks for the first block. A hit marks a corner; a right-click leaves
	 * that block out, or into the sky brings the screen back. (Without this, a hand's reach of about five blocks.)
	 */
	private static void axeFar(net.minecraft.client.Minecraft client) {
		var player = client.player;
		if (player == null || client.level == null || Screens.current(client) != null || !xen.mod.BuildAxe.holding(player)
				|| !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(xen.mod.BuildAxe.Corner.TYPE)) return;
		boolean hit = false, use = false;
		while (client.options.keyAttack.consumeClick()) hit = true;
		while (client.options.keyUse.consumeClick()) use = true;
		if (!hit && !use) return;
		net.minecraft.world.phys.Vec3 eye = player.getEyePosition(), end = eye.add(player.getViewVector(1f).scale(xen.mod.BuildAxe.REACH));
		var aim = client.level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		boolean block = aim.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
		if (hit && block) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new xen.mod.BuildAxe.Corner(aim.getBlockPos(), 1));
		if (use) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new xen.mod.BuildAxe.Corner(block ? aim.getBlockPos() : player.blockPosition(), block ? 2 : 0));
	}

	@Override
	public void onInitializeClient() {
		xen.mod.talk.Chat.gpu = GlAccelerator::create;                 // in a game client, the chat model can use the GPU
		// the Build Axe's screen, when the server says a box is marked
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(xen.mod.BuildAxe.Open.TYPE,
				(payload, context) -> Screens.open(context.client(), new BuildAxeScreen(payload)));
		// ...and its clicks: the corners go to the server as they are (the click itself is held back: nothing breaks or strips)
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> axeClick(player, level, pos, 1));
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> axeClick(player, level, hit.getBlockPos(), 2));
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> axeClick(player, level, player.blockPosition(), 0));
		ClientTickEvents.START_CLIENT_TICK.register(XenClient::axeFar);
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
