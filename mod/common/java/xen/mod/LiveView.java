package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watching a Xen live without Minecraft open (a server with no one on it, a test run): what it sees, as a picture, once
 * a second, with how it is (health, food, levels, its gear, its goal, what it's doing) and the last lines of its
 * journal. Off unless the folder config/xen/live is there (make it to turn this on, delete it to turn it off: looked
 * at every five seconds); then each Xen writes &lt;name&gt;.png and &lt;name&gt;.json in it, for a page to show.
 * <p>The picture is its own eyes ({@link Vision.Camera}): sight lines from where its head points, a player's field of
 * view, each block its map colour shaded as the game shades it; not textures, and nothing a real client would draw.
 * The picture's made on the server thread (a few milliseconds); the file's written on a thread of its own.
 */
final class LiveView {
	private LiveView() {}

	static final int W = 160, H = 90;
	private static final double TAN_V = Math.tan(Math.toRadians(35));
	private static volatile boolean on;
	private static volatile long checkedAt;
	private static Path dir;
	private static final Map<String, Long> SHOT = new ConcurrentHashMap<>();
	private static final Map<String, Vision.Camera> CAMS = new ConcurrentHashMap<>();
	private static final Map<String, Deque<String>> LINES = new ConcurrentHashMap<>();
	private static final AtomicInteger WRITING = new AtomicInteger();
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Xen live view");
		t.setDaemon(true);
		return t;
	});

	/** On or off: the folder's there or not (looked at every five seconds). */
	private static boolean on() {
		long now = System.currentTimeMillis();
		if (now - checkedAt > 5000) {
			checkedAt = now;
			if (dir == null) dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("live");
			on = Files.isDirectory(dir);
		}
		return on;
	}

	/** A journal line, kept (the last twelve) while the live view is on. */
	static void journaled(String name, String kind, String text) {
		if (!on) return;
		Deque<String> d = LINES.computeIfAbsent(name, k -> new ArrayDeque<>());
		synchronized (d) {
			d.addLast(java.time.LocalTime.now().withNano(0) + " " + kind + " " + (text.length() > 180 ? text.substring(0, 180) + "..." : text));
			while (d.size() > 12) d.removeFirst();
		}
	}

	/** Each tick: once a second, its view and how it is, to the folder. */
	static void tick(Companion c) {
		var p = c.player;
		if (p == null || c.minion || !on()) return;
		long now = System.currentTimeMillis();
		Long last = SHOT.get(c.name);
		if (last != null && now - last < 1000 || WRITING.get() > 6) return;
		SHOT.put(c.name, now);
		ServerLevel level = (ServerLevel) p.level();
		Vision.Camera cam = CAMS.computeIfAbsent(c.name, k -> new Vision.Camera(W, H));
		Vec3 eye = p.getEyePosition();
		int far = Math.max(32, Math.min(96, c.sight()));
		Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
		List<Entity> inView = new ArrayList<>();
		for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(far), e -> e.isAlive() && (e instanceof LivingEntity || e instanceof ItemEntity))) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double d = to.length();
			if (d < 0.5 || to.dot(look) / d < 0.3) continue;
			inView.add(e);
			if (inView.size() >= 48) break;
		}
		cam.shoot(level, eye, p.getYHeadRot(), p.getXRot(), TAN_V, 16.0 / 9, far, inView, level.canSeeSky(p.blockPosition()));
		int[] rgb = cam.rgb.clone();

		JsonObject o = new JsonObject();
		o.addProperty("name", c.name);
		o.addProperty("at", now);
		o.addProperty("x", p.getBlockX());
		o.addProperty("y", p.getBlockY());
		o.addProperty("z", p.getBlockZ());
		o.addProperty("dimension", level.dimension().identifier().getPath());
		o.addProperty("health", Math.round(p.getHealth() * 10) / 10.0);
		o.addProperty("food", p.getFoodData().getFoodLevel());
		o.addProperty("levels", p.experienceLevel);
		o.addProperty("diamonds", c.diamondsCarried());
		o.addProperty("night", level.isDarkOutside());
		o.addProperty("held", item(p.getMainHandItem()));
		JsonArray armor = new JsonArray();
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack st = p.getItemBySlot(s);
			if (!st.isEmpty()) armor.add(item(st));
		}
		o.add("armor", armor);
		o.addProperty("goal", c.btb.on() ? c.btb.goal : "");
		o.addProperty("doing", !c.chores.doing.isEmpty() ? c.chores.doing : c.goals.instant.isEmpty() ? "looking around" : c.goals.instant);
		JsonArray lines = new JsonArray();
		Deque<String> d = LINES.get(c.name);
		if (d != null) synchronized (d) {
			for (String l : d) lines.add(l);
		}
		o.add("journal", lines);
		String json = o.toString(), name = c.name;
		Path to = dir;

		WRITING.incrementAndGet();
		WRITER.execute(() -> {
			try {
				BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
				img.setRGB(0, 0, W, H, rgb, 0, W);
				Path png = to.resolve(name + ".png"), tmp = to.resolve(name + ".png.tmp");
				javax.imageio.ImageIO.write(img, "png", tmp.toFile());
				Files.move(tmp, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				Path js = to.resolve(name + ".json"), jtmp = to.resolve(name + ".json.tmp");
				Files.writeString(jtmp, json, StandardCharsets.UTF_8);
				Files.move(jtmp, js, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception e) {
				// (the folder went away, or the disk's full: the next one)
			} finally {
				WRITING.decrementAndGet();
			}
		});
	}

	/** An item's name, with a star if it's enchanted ("diamond_pickaxe*"). */
	private static String item(ItemStack st) {
		if (st.isEmpty()) return "";
		return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath() + (st.isEnchanted() ? "*" : "");
	}
}
