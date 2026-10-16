package xen.mod.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import xen.mod.Vision;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * The recorder: your own play, as training data for Xen (client side, off until you press its key; F8 by default).
 * A few times a second it notes what a Xen would want to learn from: a small picture of your screen (128 by 72, the
 * game's real textures and light), what each part of it is (64 by 36: the block or creature, how far, how light, which
 * face, from the same sight lines Xen's own eyes use, and Xen's own picture of that view next to it), where you look
 * and how your head turns, the keys you hold, the item in your hand, your health and hunger, the time of day; and what
 * you do: mining, placing, hitting, getting hurt, dying. A note key (F9) lets you say why ("going for iron").
 * <p>Read-only (it never presses a key or moves the mouse) and private: no chat, no names (a player is just "player"),
 * no server address. It writes to .minecraft/xen_recordings/session_.../ on your own device: a log (log.jsonl.gz), the
 * pictures (frames/), and Xen's pictures (xen/). Roughly 50 to 250 MB an hour of play (untested on phones).
 */
public final class Recorder {
	static final int SHOT_W = 128, SHOT_H = 72;
	/** Ticks between notes of what you do and see (4 a second), and between screen pictures (2 a second). */
	private static final int STATE_EVERY = 5, SHOT_EVERY = 10;

	private static KeyMapping recordKey, noteKey;
	private static boolean on;
	private static Path dir;
	private static Writer log;
	private static ExecutorService io;
	private static long started, ticks;
	private static int frame;
	private static float lastYaw, lastPitch, lastHealth = -1;
	private static final Set<Integer> labelsWritten = new HashSet<>();
	private static final Vision.Camera cam = new Vision.Camera(Vision.W, Vision.H);
	private static BlockPos attacking;
	/** -Dxen.recordTest=SECONDS: it records by itself once in a world, that long, then stops (a test without a person). */
	private static final int TEST = Integer.getInteger("xen.recordTest", 0);
	private static int testTicks;
	private static boolean warned;
	private static String attackingWhat;

	private Recorder() {}

	/** Fabric's key mapping helper (its name changed between versions). */
	private static KeyMapping register(KeyMapping k) {
		String[][] ways = {{"net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper", "registerKeyMapping"},
				{"net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper", "registerKeyBinding"}};
		for (String[] w : ways) {
			try {
				return (KeyMapping) Class.forName(w[0]).getMethod(w[1], KeyMapping.class).invoke(null, k);
			} catch (ReflectiveOperationException | LinkageError ignored) {
				// the other name
			}
		}
		return null;
	}

	static void init() {
		recordKey = register(new KeyMapping("key.xen.record", 297, KeyMapping.Category.MISC));   // F8
		noteKey = register(new KeyMapping("key.xen.note", 298, KeyMapping.Category.MISC));       // F9
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
			if (on && level.isClientSide()) {
				attacking = pos.immutable();
				attackingWhat = name(level.getBlockState(pos).getBlock());
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (on && level.isClientSide()) event("use", "\"item\":\"" + item(player.getItemInHand(hand)) + "\",\"on\":\"" + name(level.getBlockState(hit.getBlockPos()).getBlock()) + "\"");
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
			if (on && level.isClientSide()) event("attack", "\"target\":\"" + kind(target) + "\"");
			return net.minecraft.world.InteractionResult.PASS;
		});
	}

	private static String name(net.minecraft.world.level.block.Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	private static String item(net.minecraft.world.item.ItemStack s) {
		return s.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	private static String kind(Entity e) {
		return e instanceof net.minecraft.world.entity.player.Player ? "player" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	/** A note, typed in its screen: why you're doing what you do. */
	static void note(String text) {
		if (!on || text == null || text.isBlank()) return;
		event("note", "\"text\":\"" + text.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
	}

	static void tick(Minecraft client) {
		if (recordKey == null) return;
		while (recordKey.consumeClick()) {
			if (on) stop(client);
			else start(client);
		}
		while (noteKey != null && noteKey.consumeClick()) {
			if (on && Screens.current(client) == null) Screens.open(client, new NoteScreen());
		}
		var p = client.player;
		if (TEST > 0 && p != null && client.level != null) {
			testTicks++;
			if (testTicks == 100 && !on) start(client);
			if (on && testTicks == 100 + TEST * 20) stop(client);
		}
		if (!on) return;
		if (p == null || client.level == null) {
			stop(client);
			return;
		}
		ticks++;
		if (attacking != null && client.level.getBlockState(attacking).isAir()) {            // what you were hitting is gone: you mined it
			event("mine", "\"block\":\"" + attackingWhat + "\"");
			attacking = null;
		}
		float hp = p.getHealth();
		if (lastHealth >= 0 && hp < lastHealth) event(hp <= 0 ? "death" : "hurt", String.format(Locale.ROOT, "\"amount\":%.1f", lastHealth - hp));
		lastHealth = hp;
		if (Screens.current(client) != null) return;                                          // (a screen open: the world isn't what you're looking at)
		if (ticks % STATE_EVERY == 0) state(client);
		if (ticks % SHOT_EVERY == 0) shot(client, frame);
	}

	private static void start(Minecraft client) {
		if (client.player == null) return;
		try {
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			dir = client.gameDirectory.toPath().resolve("xen_recordings").resolve("session_" + stamp);
			Files.createDirectories(dir.resolve("frames"));
			Files.createDirectories(dir.resolve("xen"));
			log = new java.io.OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dir.resolve("log.jsonl.gz"))), StandardCharsets.UTF_8);
			io = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "xen-recorder");
				t.setDaemon(true);
				return t;
			});
			labelsWritten.clear();
			started = System.currentTimeMillis();
			ticks = 0;
			frame = 0;
			lastHealth = -1;
			on = true;
			write("{\"session\":\"" + stamp + "\",\"format\":\"xen-recorder 1\",\"shot\":[" + SHOT_W + "," + SHOT_H + "],\"labels_size\":[" + Vision.W + "," + Vision.H
					+ "],\"state_hz\":" + 20 / STATE_EVERY + ",\"shot_hz\":" + 20 / SHOT_EVERY + "}");
			say(client, "Xen recorder: on (F8 to stop, F9 for a note). Saving to xen_recordings/session_" + stamp);
		} catch (IOException e) {
			say(client, "Xen recorder: couldn't start (" + e.getMessage() + ")");
			on = false;
		}
	}

	private static void stop(Minecraft client) {
		on = false;
		ExecutorService was = io;
		Writer w = log;
		io = null;
		log = null;
		if (was != null) {
			was.submit(() -> {
				try {
					if (w != null) w.close();
				} catch (IOException ignored) {
					// (nothing more to save)
				}
			});
			was.shutdown();
		}
		if (client.player != null) say(client, "Xen recorder: off. " + frame + " pictures saved.");
	}

	private static void say(Minecraft client, String text) {
		Screens.overlay(client, Component.literal(text));
	}

	private static void write(String line) {
		ExecutorService x = io;
		Writer w = log;
		if (x == null || w == null) return;
		x.submit(() -> {
			try {
				w.write(line);
				w.write('\n');
			} catch (IOException ignored) {
				// (disk full: it just stops getting lines)
			}
		});
	}

	private static void event(String what, String fields) {
		write("{\"t\":" + (System.currentTimeMillis() - started) + ",\"event\":\"" + what + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}");
	}

	/** What you do and see now: your body, your hands, your keys, where you look, and what each part of the view is. */
	private static void state(Minecraft client) {
		var p = client.player;
		var level = client.level;
		var o = client.options;
		float yaw = p.getYRot(), pitch = p.getXRot();
		float dYaw = net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw), dPitch = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		Vec3 eye = p.getEyePosition();
		List<Entity> inView = new ArrayList<>();
		for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(Vision.FAR), e -> e.isAlive() && (e instanceof LivingEntity || e instanceof ItemEntity))) {
			inView.add(e);
			if (inView.size() >= 48) break;
		}
		double fov = o.fov().get();
		var win = client.getWindow();
		double aspect = win.getWidth() / (double) Math.max(1, win.getHeight());
		boolean outside = level.canSeeSky(p.blockPosition());
		cam.shoot(level, eye, yaw, pitch, Math.tan(Math.toRadians(fov) / 2), aspect, Vision.FAR, inView, outside);
		StringBuilder labels = new StringBuilder();
		for (int px = 0; px < cam.what.length; px++) {
			int id = cam.what[px];
			if (labelsWritten.add(id)) labels.append(labels.length() > 0 ? "," : "").append('"').append(id).append("\":\"").append(Vision.labelName(id)).append('"');
		}
		if (labels.length() > 0) write("{\"labels\":{" + labels + "}}");
		byte[] dist = new byte[cam.dist.length], lf = new byte[cam.dist.length];
		for (int px = 0; px < dist.length; px++) {
			dist[px] = (byte) Math.min(255, Math.round(cam.dist[px] * 4));
			lf[px] = (byte) (Math.max(0, Math.min(15, cam.light[px])) << 4 | cam.face[px] & 15);
		}
		var b64 = Base64.getEncoder();
		String dim = level.dimension().toString();
		dim = dim.substring(dim.lastIndexOf(':') + 1).replace("]", "").trim();
		boolean shotNow = ticks % SHOT_EVERY == 0;
		String line = String.format(Locale.ROOT,
				"{\"t\":%d,\"frame\":%d,\"pos\":[%.2f,%.2f,%.2f],\"dim\":\"%s\",\"yaw\":%.1f,\"pitch\":%.1f,\"turn\":[%.1f,%.1f],\"fov\":%.0f,"
						+ "\"keys\":{\"w\":%b,\"a\":%b,\"s\":%b,\"d\":%b,\"jump\":%b,\"sneak\":%b,\"sprint\":%b,\"attack\":%b,\"use\":%b},\"slot\":%d,\"hand\":\"%s\","
						+ "\"hp\":%.1f,\"food\":%d,\"air\":%d,\"day_time\":%d,\"on_ground\":%b,\"in_water\":%b,\"looking_at\":\"%s\","
						+ "\"what\":\"%s\",\"dist\":\"%s\",\"light_face\":\"%s\"}",
				System.currentTimeMillis() - started, shotNow ? frame : -1, p.getX(), p.getY(), p.getZ(), dim, yaw, pitch, dYaw, dPitch, fov,
				o.keyUp.isDown(), o.keyLeft.isDown(), o.keyDown.isDown(), o.keyRight.isDown(), o.keyJump.isDown(), o.keyShift.isDown(), o.keySprint.isDown(),
				o.keyAttack.isDown(), o.keyUse.isDown(), p.getInventory().getSelectedSlot(), item(p.getMainHandItem()), p.getHealth(), p.getFoodData().getFoodLevel(),
				p.getAirSupply(), Screens.dayTime(level), p.onGround(), p.isInWater(), lookingAt(client), rle(cam.what), b64.encodeToString(dist), b64.encodeToString(lf));
		write(line);
		if (shotNow) {                                                                      // Xen's own picture of this view, next to yours
			int[] mine = cam.rgb.clone();
			int n = frame;
			Path to = dir.resolve("xen").resolve(String.format(Locale.ROOT, "%06d.png", n));
			ExecutorService x = io;
			if (x != null) x.submit(() -> png(to, mine, cam.w, cam.h));
		}
	}

	private static String lookingAt(Minecraft client) {
		var hit = client.hitResult;
		if (hit instanceof net.minecraft.world.phys.BlockHitResult b && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return name(client.level.getBlockState(b.getBlockPos()).getBlock());
		if (hit instanceof net.minecraft.world.phys.EntityHitResult e) return "mob:" + kind(e.getEntity());
		return "";
	}

	/** Labels, run-length: "12x30,4x2,..." (id x how many in a row). */
	private static String rle(int[] v) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < v.length; ) {
			int j = i;
			while (j < v.length && v[j] == v[i]) j++;
			sb.append(sb.length() > 0 ? "," : "").append(v[i]).append('x').append(j - i);
			i = j;
		}
		return sb.toString();
	}

	/** Your screen, small: read back from the game's own picture (its textures, its light), shrunk to 128 by 72. */
	private static void shot(Minecraft client, int n) {
		RenderTarget target = mainTarget(client);
		if (target == null) return;
		frame++;
		var win = client.getWindow();
		int factor = Math.max(1, win.getWidth() / (SHOT_W * 2));
		while (factor > 1 && (win.getWidth() % factor != 0 || win.getHeight() % factor != 0)) factor--;   // (the game wants a factor that divides the window)
		Path to = dir.resolve("frames").resolve(String.format(Locale.ROOT, "%06d.png", n));
		try {
			Screenshot.takeScreenshot(target, factor, (NativeImage img) -> {
				try (img) {
					int w = img.getWidth(), h = img.getHeight();
					int[] out = new int[SHOT_W * SHOT_H];
					for (int y = 0; y < SHOT_H; y++) {                                       // box average into 128 by 72
						int y0 = y * h / SHOT_H, y1 = Math.max(y0 + 1, (y + 1) * h / SHOT_H);
						for (int x = 0; x < SHOT_W; x++) {
							int x0 = x * w / SHOT_W, x1 = Math.max(x0 + 1, (x + 1) * w / SHOT_W);
							long r = 0, g = 0, b = 0;
							int k = 0;
							for (int yy = y0; yy < y1; yy++) {
								for (int xx = x0; xx < x1; xx++) {
									int c = img.getPixel(xx, yy);                              // (ARGB)
									r += c >> 16 & 255;
									g += c >> 8 & 255;
									b += c & 255;
									k++;
								}
							}
							out[y * SHOT_W + x] = (int) (r / k) << 16 | (int) (g / k) << 8 | (int) (b / k);
						}
					}
					ExecutorService x = io;
					if (x != null) x.submit(() -> png(to, out, SHOT_W, SHOT_H));
				}
			});
		} catch (RuntimeException e) {
			if (!warned) xen.mod.XenMod.LOG.warn("xen recorder: no picture of the screen: {}", e.toString());
			warned = true;
		}
	}

	private static RenderTarget mainTarget(Minecraft client) {
		try {
			return (RenderTarget) Minecraft.class.getMethod("getMainRenderTarget").invoke(client);
		} catch (ReflectiveOperationException e) {
			try {                                                                           // 26.3: the game renderer has it
				Object gr = Minecraft.class.getField("gameRenderer").get(client);
				return (RenderTarget) gr.getClass().getMethod("mainRenderTarget").invoke(gr);
			} catch (ReflectiveOperationException e2) {
				return null;
			}
		}
	}

	/** A PNG, written by hand (no image library: the same on a phone). */
	static void png(Path to, int[] rgb, int w, int h) {
		try {
			ByteArrayOutputStream raw = new ByteArrayOutputStream(w * h * 3 + h);
			for (int y = 0; y < h; y++) {
				raw.write(0);
				for (int x = 0; x < w; x++) {
					int c = rgb[y * w + x];
					raw.write(c >> 16 & 255);
					raw.write(c >> 8 & 255);
					raw.write(c & 255);
				}
			}
			Deflater d = new Deflater(6);
			d.setInput(raw.toByteArray());
			d.finish();
			ByteArrayOutputStream z = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			while (!d.finished()) z.write(buf, 0, d.deflate(buf));
			d.end();
			try (OutputStream out = Files.newOutputStream(to)) {
				out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
				ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
				int32(ihdr, w);
				int32(ihdr, h);
				ihdr.write(new byte[] {8, 2, 0, 0, 0});                                    // 8 bits, RGB
				chunk(out, "IHDR", ihdr.toByteArray());
				chunk(out, "IDAT", z.toByteArray());
				chunk(out, "IEND", new byte[0]);
			}
		} catch (IOException ignored) {
			// (disk full: that picture is skipped)
		}
	}

	private static void int32(OutputStream o, int v) throws IOException {
		o.write(v >>> 24);
		o.write(v >>> 16 & 255);
		o.write(v >>> 8 & 255);
		o.write(v & 255);
	}

	private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		int32(b, data.length);
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		b.write(t);
		b.write(data);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		int32(b, (int) crc.getValue());
		out.write(b.toByteArray());
	}

	/** The note screen: a line of text and Save. */
	static final class NoteScreen extends net.minecraft.client.gui.screens.Screen {
		private net.minecraft.client.gui.components.EditBox text;

		NoteScreen() {
			super(Component.literal("Recorder note"));
		}

		@Override
		protected void init() {
			int w = 240, x = (width - w) / 2, y = height / 2 - 30;
			addRenderableWidget(new net.minecraft.client.gui.components.StringWidget(x, y, w, 12, Component.literal("Why are you doing this? (\"going for iron\")"), font));
			text = new net.minecraft.client.gui.components.EditBox(font, x, y + 16, w, 20, Component.literal("Note"));
			text.setMaxLength(120);
			addRenderableWidget(text);
			int half = (w - 4) / 2;
			addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Save"), b -> {
				note(text.getValue());
				onClose();
			}).bounds(x, y + 42, half, 20).build());
			addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + w - half, y + 42, half, 20).build());
			setInitialFocus(text);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}
}
