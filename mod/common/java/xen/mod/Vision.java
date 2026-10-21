package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a Xen sees, as a picture: a camera of sight lines from its eyes, where its head points, a grid of 64 by 36 over
 * a player's field of view (70 degrees up and down, 16:9). Each line stops at the first block or creature it meets, so
 * nothing shows through a wall. No textures and no GPU: a pixel's colour is the block's map colour, shaded the way the
 * game shades it (tops brightest, sides darker, the undersides darkest), darker where the light is low, hazier the
 * further it is, and the sky's colour (by the time of day) where a line meets nothing. Every pixel also knows what it
 * is (the block or the creature, how far, how light), for free.
 * <ul>
 *   <li><b>Like a person's eyes</b>: sharp in the middle, coarse at the edges (the outer columns are one line for two
 *   pixels, the top and bottom rows looked at half as often); a row each tick near a player (a whole view in about 1.4 s);
 *   rows it has just looked at aren't looked at again while it stands still; slower and shorter-sighted where no real
 *   player is near.</li>
 *   <li><b>What it makes of it</b>, by simple rules: a cave mouth, a drop in front of it, a ravine, something people
 *   built (a village if there are villagers), dark ground where monsters spawn, a monster that went behind cover, a mess
 *   near its home.</li>
 *   <li><b>Where it goes</b>: its mind's inputs, the situations its own rules are written in ({@link Rules}), what it
 *   talks about, and its head: it turns to look at what moves or stands out, when its hands aren't busy.</li>
 * </ul>
 * Hints, not orders: none of this makes it do anything; its mind decides.
 */
public final class Vision {
	public static final int W = 64, H = 36;
	private static final double TAN_V = Math.tan(Math.toRadians(35)), TAN_H = TAN_V * 16 / 9;
	/** How far the recorder's picture of a player's view goes (a Xen's own: its sight setting, see Companion.sight). */
	public static final int FAR = 64;

	/** How the picture is shaded: the game's own directional shading and light curve (tuned from recordings: config/xen/vision.txt). */
	static float top = 1f, bottom = 0.5f, northSouth = 0.8f, eastWest = 0.6f, gamma = 0.5f, ambient = 0.03f, hazeFrom = 0.55f;
	static int skyDay = 0x78A7FF, skyNight = 0x0B0E1A, caveFog = 0x050507;
	/** A block's colour as tuned from recordings (by label id), where it was. */
	static final Map<Integer, Integer> COLOUR = new java.util.concurrent.ConcurrentHashMap<>();

	// ------------------------------------------------------------------------------ labels, shared
	private static final Map<String, Integer> LABEL_IDS = new HashMap<>();
	private static final List<String> LABELS = new ArrayList<>();
	private static final Map<BlockState, Integer> BLOCK_LABEL = new IdentityHashMap<>();

	static synchronized int label(String name) {
		Integer id = LABEL_IDS.get(name);
		if (id != null) return id;
		LABELS.add(name);
		LABEL_IDS.put(name, LABELS.size() - 1);
		return LABELS.size() - 1;
	}

	public static synchronized String labelName(int id) {
		return id >= 0 && id < LABELS.size() ? LABELS.get(id) : "?";
	}

	static final int SKY = label("sky"), FOG = label("fog"), WATER = label("water");

	private static int blockLabel(BlockState s) {
		Integer id;
		synchronized (Vision.class) {
			id = BLOCK_LABEL.get(s);
		}
		if (id != null) return id;
		int l = label(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
		synchronized (Vision.class) {
			BLOCK_LABEL.put(s, l);
		}
		return l;
	}

	/** Blocks people build (and, with villagers about, a village). */
	static boolean built(String n) {
		return n.endsWith("_planks") || n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_door") || n.endsWith("_fence") || n.endsWith("_fence_gate")
				|| n.contains("glass") || n.endsWith("bricks") || n.endsWith("_wool") || n.endsWith("_carpet") || n.endsWith("_bed") || n.equals("cobblestone")
				|| n.equals("mossy_cobblestone") || n.equals("bookshelf") || n.equals("crafting_table") || n.equals("furnace") || n.equals("chest")
				|| n.equals("barrel") || n.equals("lantern") || n.equals("torch") || n.equals("wall_torch") || n.equals("hay_block") || n.equals("bell")
				|| n.equals("smooth_stone") || n.startsWith("stripped_") || n.endsWith("_wall") || n.equals("dirt_path") || n.equals("farmland");
	}

	/**
	 * A camera: a grid of sight lines and, for each pixel, its colour and what it is (the block or creature, how far, how
	 * light, which face). The Xen's eyes use one; so does the recorder (client side), so a recording has the real
	 * screen and Xen's picture of the same view side by side.
	 */
	public static final class Camera {
		public final int w, h;
		public final int[] rgb, what, mob;
		public final float[] dist;
		public final byte[] light, face;
		/** Where each pixel's line ended (the block, packed), and that block's height. */
		final long[] hit;
		final int[] hitY;
		private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		private final BlockPos.MutableBlockPos prev = new BlockPos.MutableBlockPos();

		public Camera(int w, int h) {
			this.w = w;
			this.h = h;
			rgb = new int[w * h];
			what = new int[w * h];
			mob = new int[w * h];
			dist = new float[w * h];
			light = new byte[w * h];
			face = new byte[w * h];
			hit = new long[w * h];
			hitY = new int[w * h];
		}

		void copy(int from, int to) {
			rgb[to] = rgb[from];
			what[to] = what[from];
			dist[to] = dist[from];
			light[to] = light[from];
			face[to] = face[from];
			mob[to] = mob[from];
			hit[to] = hit[from];
			hitY[to] = hitY[from];
		}

		/** A whole view at once (the recorder's): from eye, looking yaw and pitch (degrees), tanV the half-height of the view. */
		public void shoot(net.minecraft.world.level.Level level, Vec3 eye, float yawD, float pitchD, double tanV, double aspect, int far, List<Entity> inView, boolean outside) {
			double yaw = Math.toRadians(yawD), pitch = Math.toRadians(pitchD);
			double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
			double fx = -sy * cp, fy = -sp, fz = cy * cp, rx = -cy, rz = -sy, ux = -sy * sp, uy = cp, uz = cy * sp;
			double tanH = tanV * aspect;
			for (int j = 0; j < h; j++) {
				double y = (1 - 2 * (j + 0.5) / h) * tanV;
				for (int i = 0; i < w; i++) {
					double x = (2 * (i + 0.5) / w - 1) * tanH;
					double dx = fx + x * rx + y * ux, dy = fy + y * uy, dz = fz + x * rz + y * uz;
					double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
					cast(level, eye, dx / len, dy / len, dz / len, far, j * w + i, inView, outside);
				}
			}
		}

		/** One line: block by block from its eyes until something stops it; a creature in front of that, it sees instead. */
		void cast(net.minecraft.world.level.Level level, Vec3 eye, double dx, double dy, double dz, int far, int px, List<Entity> inView, boolean outside) {
			int x = (int) Math.floor(eye.x), y = (int) Math.floor(eye.y), z = (int) Math.floor(eye.z);
			int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
			double tdx = dx == 0 ? 1e9 : Math.abs(1 / dx), tdy = dy == 0 ? 1e9 : Math.abs(1 / dy), tdz = dz == 0 ? 1e9 : Math.abs(1 / dz);
			double tx = dx == 0 ? 1e9 : (dx > 0 ? x + 1 - eye.x : eye.x - x) * tdx;
			double ty = dy == 0 ? 1e9 : (dy > 0 ? y + 1 - eye.y : eye.y - y) * tdy;
			double tz = dz == 0 ? 1e9 : (dz > 0 ? z + 1 - eye.z : eye.z - z) * tdz;
			int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE, axis = -1;
			net.minecraft.world.level.chunk.LevelChunk chunk = null;
			double t = 0, waterFrom = -1;
			BlockState got = null;
			prev.set(x, y, z);
			while (t < far) {
				if (tx < ty && tx < tz) {
					x += sx;
					t = tx;
					tx += tdx;
					axis = 0;
				} else if (ty < tz) {
					y += sy;
					t = ty;
					ty += tdy;
					axis = 1;
				} else {
					z += sz;
					t = tz;
					tz += tdz;
					axis = 2;
				}
				if (y < level.getMinY() || y >= level.getMaxY()) break;
				if (x >> 4 != chunkX || z >> 4 != chunkZ) {
					chunkX = x >> 4;
					chunkZ = z >> 4;
					chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
					if (chunk == null) break;
				}
				m.set(x, y, z);
				BlockState s = chunk.getBlockState(m);
				if (s.isAir()) {
					prev.set(x, y, z);
					continue;
				}
				if (!s.getFluidState().isEmpty() && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
					if (waterFrom < 0) waterFrom = t;
					if (t - waterFrom < 6) {                                              // (water: it sees a little way into it)
						prev.set(x, y, z);
						continue;
					}
					got = s;
					break;
				}
				MapColor mc = s.getMapColor(level, m);
				if (mc == MapColor.NONE) {                                               // (glass, a barrier: see-through)
					prev.set(x, y, z);
					continue;
				}
				if (s.getCollisionShape(level, m).isEmpty() && s.getFluidState().isEmpty()) {   // grass, a flower, a torch: only where its shape is
					var shape = s.getShape(level, m);
					if (shape.isEmpty()) {
						prev.set(x, y, z);
						continue;
					}
					double hx = eye.x + dx * t - x, hy = eye.y + dy * t - y, hz = eye.z + dz * t - z;
					AABB b = shape.bounds();
					if (hy > b.maxY + 0.05 || hx < b.minX - 0.1 || hx > b.maxX + 0.1 || hz < b.minZ - 0.1 || hz > b.maxZ + 0.1) {
						prev.set(x, y, z);
						continue;
					}
				}
				got = s;
				break;
			}
			// a creature in front of the block?
			Entity who = null;
			double whoT = got == null ? far : t;
			Vec3 from = eye, end = eye.add(dx * whoT, dy * whoT, dz * whoT);
			for (Entity e : inView) {
				var hitE = e.getBoundingBox().clip(from, end);
				if (hitE.isEmpty()) continue;
				double te = hitE.get().distanceTo(eye);
				if (te < whoT) {
					whoT = te;
					who = e;
				}
			}
			int lightHere = level.getRawBrightness(prev, level.getSkyDarken());
			float d;
			int colour, lab;
			byte f;
			if (who != null) {
				d = (float) whoT;
				colour = entityColour(who);
				lab = label(who instanceof net.minecraft.world.entity.player.Player ? "mob:player" : "mob:" + BuiltInRegistries.ENTITY_TYPE.getKey(who.getType()).getPath());
				f = 3;
				hit[px] = who.blockPosition().asLong();
				hitY[px] = who.blockPosition().getY();
				lightHere = level.getRawBrightness(who.blockPosition(), level.getSkyDarken());
			} else if (got != null) {
				d = (float) t;
				lab = got.getFluidState().is(net.minecraft.tags.FluidTags.WATER) ? WATER : blockLabel(got);
				Integer tuned = COLOUR.get(lab);                                          // (its colour as tuned from recordings, if it was)
				colour = tuned != null ? tuned : got.getFluidState().is(net.minecraft.tags.FluidTags.WATER) ? 0x3F76E4 : got.getMapColor(level, m).col;
				f = (byte) (axis == 1 ? (sy < 0 ? 1 : 2) : axis == 0 ? 4 : 5);           // top, bottom, east/west, north/south
				float shade = f == 1 ? top : f == 2 ? bottom : f == 4 ? eastWest : northSouth;
				colour = scale(colour, shade);
				hit[px] = m.asLong();
				hitY[px] = y;
			} else {
				d = far;
				boolean sky = dy > 0 || outside;
				colour = sky ? skyColour(level, dy) : caveFog;
				lab = sky && dy > -0.05 ? SKY : FOG;
				f = 0;
				hit[px] = Long.MIN_VALUE;
				hitY[px] = Integer.MIN_VALUE;
				lightHere = 15;
			}
			if (f != 0) colour = scale(colour, brightness(lightHere));
			if (waterFrom >= 0 && (got == null || !got.getFluidState().is(net.minecraft.tags.FluidTags.WATER))) {   // seen through water: bluer
				colour = mix(colour, 0x3F76E4, (float) Math.min(0.8, (d - waterFrom) / 6));
			}
			float haze = (float) Math.max(0, Math.min(1, (d / far - hazeFrom) / (1 - hazeFrom)));
			if (f != 0 && haze > 0) colour = mix(colour, outside ? skyColour(level, 0) : caveFog, haze);
			rgb[px] = colour;
			what[px] = lab;
			dist[px] = d;
			light[px] = (byte) lightHere;
			face[px] = f;
			mob[px] = who == null ? -1 : who.getId();
		}
	}

	// ------------------------------------------------------------------------------ one Xen's
	private final Companion c;
	final Camera cam = new Camera(W, H);
	final int[] rgb = cam.rgb, what = cam.what, mob = cam.mob, hitY = cam.hitY;
	final float[] dist = cam.dist;
	final byte[] light = cam.light, face = cam.face;
	private final long[] hit = cam.hit;
	private int row;
	private final float[][] rowPose = new float[H][];
	private int pass;
	/** The creatures in view this pass (their boxes are read each row: they move). */
	private final List<Entity> inView = new ArrayList<>();
	/**
	 * How far what it makes of its view reaches (a cave mouth, buildings, dark ground, what it turns its head to): as far
	 * as its mind learned it (64 near a player, 32 otherwise), however far it sees.
	 */
	private int reads = FAR / 2;
	private final BlockPos.MutableBlockPos prev = new BlockPos.MutableBlockPos();

	/** What it made of its last whole view. */
	boolean caveMouth, drop, ravine, structure, village, darkGround, hiddenMob, mess;
	BlockPos caveAt, structureAt;
	int dropDepth, frames;
	/** Monsters it saw lately (for "it went behind something"): its id, and when it last saw it. */
	private final Map<Integer, Long> monstersSeen = new HashMap<>();
	private final Map<Integer, Integer> lastPixels = new HashMap<>(), shownPx = new HashMap<>();
	private long nextNotice;
	/** Microseconds this Xen's eyes took, all told (for /xen vision). */
	long micros;

	Vision(Companion c) {
		this.c = c;
	}

	/** A few rows each tick (none while asleep). */
	void tick() {
		var p = c.player;
		if (p == null || !c.mod.config.vision || p.isSleeping() || c.minion && !c.awake()) return;
		boolean near = c.watched || DUMP != null || dumpDir() != null && c.fightingNow();   // (a runner's fights: every view, for Glance)
		int rows = near || p.tickCount % 4 == 0 ? 1 : 0;                        // (a row a tick near a player; out of sight, one every four)
		if (rows == 0) return;
		long t0 = System.nanoTime();
		ServerLevel level = (ServerLevel) p.level();
		Vec3 eye = p.getEyePosition();
		float yawD = p.getYHeadRot(), pitchD = p.getXRot();
		double yaw = Math.toRadians(yawD), pitch = Math.toRadians(pitchD);
		double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
		double fx = -sy * cp, fy = -sp, fz = cy * cp, rx = -cy, rz = -sy, ux = -sy * sp, uy = cp, uz = cy * sp;   // forward, right, up
		int far = near ? c.sight() : c.sight() / 2;                                  // (its render distance; half when nobody's near)
		reads = near ? FAR : FAR / 2;
		boolean outside = level.canSeeSky(p.blockPosition());
		for (int k = 0, guard = 0; k < rows && guard < H; guard++) {               // (rows it skips don't count)
			if (row == 0) startPass(level, eye, far);
			int j = row;
			row = (row + 1) % H;
			boolean edgeRow = j < H / 4 || j >= H - H / 4;
			if (edgeRow && (pass & 1) == 1) continue;                                  // (the top and bottom: every other pass)
			float[] pose = {(float) eye.x, (float) eye.y, (float) eye.z, yawD, pitchD};
			float[] was = rowPose[j];
			if (was != null && pass % 4 != 0 && inView.isEmpty() && Math.abs(was[0] - pose[0]) + Math.abs(was[1] - pose[1]) + Math.abs(was[2] - pose[2]) < 0.1
					&& Math.abs(was[3] - pose[3]) < 1 && Math.abs(was[4] - pose[4]) < 1) continue;   // (standing still, nothing moving: as it was)
			rowPose[j] = pose;
			k++;
			double y = (1 - 2 * (j + 0.5) / H) * TAN_V;
			for (int i = 0; i < W; i++) {
				boolean coarse = i < W / 4 || i >= W - W / 4;
				if (coarse && (i & 1) == 1) {                                          // (the edges: one line for two pixels)
					cam.copy(j * W + i - 1, j * W + i);
					continue;
				}
				double x = (2 * (i + 0.5) / W - 1) * TAN_H;
				double dx = fx + x * rx + y * ux, dy = fy + y * uy, dz = fz + x * rz + y * uz;
				double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
				cam.cast(level, eye, dx / len, dy / len, dz / len, far, j * W + i, inView, outside);
			}
		}
		micros += (System.nanoTime() - t0) / 1000;
	}

	/** A new pass: what it made of the last one, and the creatures in view now. */
	private void startPass(ServerLevel level, Vec3 eye, int far) {
		if (pass > 0) {
			frames++;
			understand(level);
		}
		pass++;
		inView.clear();
		var p = c.player;
		Vec3 look = Vec3.directionFromRotation(p.getXRot(), p.getYHeadRot());
		for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(far), e -> e.isAlive() && (e instanceof LivingEntity || e instanceof ItemEntity))) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double d = to.length();
			if (d < 0.5 || to.dot(look) / d < 0.35) continue;                        // (behind it, or off to the side: out of view)
			inView.add(e);
			if (inView.size() >= 32) break;
		}
	}

	/** The game's light curve: 0 to 15 light into 0 to 1 (and its brightness setting halfway, as it comes). */
	static float brightness(int l) {
		float f = Math.max(0, Math.min(15, l)) / 15f;
		float curve = f / (4 - 3 * f);
		float bright = 1 - (float) Math.pow(1 - curve, 4);
		return ambient + (1 - ambient) * (curve + (bright - curve) * gamma);
	}

	private static int skyColour(net.minecraft.world.level.Level level, double up) {
		float day = 1 - level.getSkyDarken() / 11f;
		int sky = mix(skyNight, skyDay, day);
		return up > 0.3 ? scale(sky, 0.9f) : sky;
	}

	private static int entityColour(Entity e) {
		String n = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		return switch (n) {
			case "zombie", "drowned", "husk" -> 0x44733A;
			case "creeper" -> 0x5DA048;
			case "skeleton", "stray" -> 0xBDBDBD;
			case "spider", "cave_spider" -> 0x3A3029;
			case "enderman" -> 0x161616;
			case "cow", "mooshroom" -> 0x5B4433;
			case "pig" -> 0xEDA5A1;
			case "sheep" -> 0xE8E8E8;
			case "chicken" -> 0xF0F0F0;
			case "villager", "wandering_trader" -> 0x8C6A51;
			case "iron_golem" -> 0xC8BFB5;
			case "item" -> 0xFFE070;
			default -> e instanceof net.minecraft.world.entity.player.Player ? 0x5E4A3A : e instanceof Enemy ? 0x4F5F3C : 0x9C7B55;
		};
	}

	static int scale(int rgb, float k) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 255) * k)), g = Math.min(255, Math.round(((rgb >> 8) & 255) * k)), b = Math.min(255, Math.round((rgb & 255) * k));
		return r << 16 | g << 8 | b;
	}

	static int mix(int a, int b, float k) {
		int r = Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k), g = Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k),
				bl = Math.round((a & 255) * (1 - k) + (b & 255) * k);
		return r << 16 | g << 8 | bl;
	}

	// ------------------------------------------------------------------------------ Xen Glance
	/** Pictures for training Xen Glance (-Dxen.visionDump=folder): each whole view, halved, with what was really there. */
	static final String DUMP = System.getProperty("xen.visionDump");

	/** Where its pictures go: -Dxen.visionDump, or a runner's to config/xen/glance_pictures (what it saw, fights and all); null: nowhere. */
	private String dumpDir() {
		if (DUMP != null) return DUMP;
		if (!c.mod.config.runner || c.minion) return null;
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("glance_pictures").toString();
	}
	/** What Xen Glance made of its last whole view (null: none yet, or no Glance), and how dangerous that looks (0 to 1). */
	float[] glance;
	float threat;

	/** Its picture, halved (each 2x2 averaged): red, green, blue and distance (a line that met nothing: as far as can be), 0 to 1. */
	float[][] glanceInput() {
		int gw = xen.mod.core.Glance.W, gh = xen.mod.core.Glance.H;
		float[][] x = new float[xen.mod.core.Glance.C][gw * gh];
		for (int r = 0; r < gh; r++)
			for (int q = 0; q < gw; q++) {
				float red = 0, green = 0, blue = 0, far = 0;
				for (int dr = 0; dr < 2; dr++)
					for (int dq = 0; dq < 2; dq++) {
						int px = (2 * r + dr) * W + 2 * q + dq, v = rgb[px];
						red += (v >> 16) & 255;
						green += (v >> 8) & 255;
						blue += v & 255;
						far += hit[px] == Long.MIN_VALUE ? 1f : Math.min(1f, dist[px] / 64f);
					}
				x[0][r * gw + q] = red / 1020f;
				x[1][r * gw + q] = green / 1020f;
				x[2][r * gw + q] = blue / 1020f;
				x[3][r * gw + q] = far / 4f;
			}
		return x;
	}

	private java.io.DataOutputStream dumpOut;

	/** Each whole view: what Glance makes of it (and, when collecting pictures, the picture with what was really there). */
	private void glance(ServerLevel level) {
		var model = c.mod.glance;
		String dump = dumpDir();
		float[][] x = model != null || dump != null ? glanceInput() : null;
		if (model != null) {
			glance = model.run(x);
			threat = Math.max(glance[xen.mod.core.Glance.CLOSE], 0.6f * glance[xen.mod.core.Glance.NEAR]);
		}
		if (dump == null) return;
		Map<Integer, Entity> byId = new HashMap<>();
		for (Entity e : inView) byId.put(e.getId(), e);
		boolean close = false, nearBy = false;
		for (int px = 0; px < W * H; px++) {
			if (mob[px] < 0 || !(byId.get(mob[px]) instanceof Enemy)) continue;
			if (dist[px] <= 6) close = true;
			if (dist[px] <= 16) nearBy = true;
		}
		try {
			if (dumpOut == null) {
				Path dir = Path.of(dump);
				Files.createDirectories(dir);
				dumpOut = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(dir.resolve("glance_" + c.name.replaceAll("[^A-Za-z0-9_]", "_") + ".bin"),
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)));
			}
			dumpOut.writeLong(level.getGameTime());
			dumpOut.writeFloat(c.player.getHealth());
			dumpOut.writeByte(close ? 1 : 0);
			dumpOut.writeByte(nearBy ? 1 : 0);
			dumpOut.writeByte(drop ? 1 : 0);
			dumpOut.writeByte(caveMouth ? 1 : 0);
			for (float[] ch : x) for (float v : ch) dumpOut.writeByte(Math.round(v * 255));
			if (frames % 20 == 0) dumpOut.flush();
		} catch (IOException e) {
			XenMod.LOG.warn("xen: couldn't write Glance pictures: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------------------ what it makes of it
	private void understand(ServerLevel level) {
		var p = c.player;
		int feet = p.blockPosition().getY();
		boolean outside = level.canSeeSky(p.blockPosition().above());
		int dark = 0, dropPx = 0, deep = 0, builtPx = 0, darkTop = 0, messPx = 0, stoneDeep = 0;
		java.util.Set<String> builtKinds = new java.util.HashSet<>();
		long caveSum = 0;
		double cx = 0, cy = 0, cz = 0, bx = 0, by = 0, bz = 0;
		BlockPos home = c.goals.home;
		for (int px = 0; px < W * H; px++) {
			int lab = what[px];
			if (hit[px] == Long.MIN_VALUE) continue;
			String n = labelName(lab);
			if (n.startsWith("mob:")) {
				continue;
			}
			BlockPos b = BlockPos.of(hit[px]);
			float d = dist[px];
			if (d > reads) continue;                                                   // (further off: seen, drawn, not read into)
			int j = px / W;
			// a cave mouth: out under the sky, looking into dark that the sky doesn't reach
			if (outside && d >= 3 && d <= 32 && face[px] != 1 && light[px] <= 7) {   // (dark there: is that the sky's dark, or a cave's?)
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.getX(), b.getZ());
				if (top > b.getY() + 2 && level.getBrightness(LightLayer.SKY, b.above()) <= 3 && level.getBrightness(LightLayer.BLOCK, b.above()) <= 3) {
					dark++;
					cx += b.getX();
					cy += b.getY();
					cz += b.getZ();
				}
			}
			// a drop: the ground it sees in front of it, far below its feet and close
			if (j > H / 2 && face[px] == 1 && hitY[px] <= feet - 6) {
				double hd = Math.hypot(b.getX() + 0.5 - p.getX(), b.getZ() + 0.5 - p.getZ());
				if (hd <= 10) {
					dropPx++;
					deep = Math.max(deep, feet - hitY[px]);
					if (n.equals("stone") || n.equals("deepslate") || n.equals("water") || n.equals("lava") || n.equals("tuff") || n.equals("andesite") || n.equals("diorite")
							|| n.equals("granite")) stoneDeep++;
				}
			}
			if (built(n) && d >= 6) {                                              // (not its own crafting table under its nose)
				if (builtKinds.size() < 4) builtKinds.add(n);
				builtPx++;
				bx += b.getX();
				by += b.getY();
				bz += b.getZ();
			}
			// dark ground: the top of a block, no light from any lamp and little from the sky: monsters spawn there
			if (face[px] == 1 && d <= 24 && light[px] <= 0) darkTop++;
			// a mess near its home: a lone pillar of dirt or cobblestone left standing
			if (home != null && b.closerThan(home, 24) && (n.equals("dirt") || n.equals("cobblestone") || n.equals("netherrack") || n.equals("cobbled_deepslate"))
					&& level.getBlockState(b.above()).isAir() && level.getBlockState(b.north()).isAir() && level.getBlockState(b.south()).isAir()
					&& level.getBlockState(b.east()).isAir() && level.getBlockState(b.west()).isAir()) messPx++;
		}
		// the creatures it saw this pass: monsters, and one that went behind something
		long now = level.getGameTime();
		int items = 0;
		boolean villagers = false;
		for (int px = 0; px < W * H; px++) {
			if (dist[px] > reads) continue;
			String n = labelName(what[px]);
			if (n.equals("mob:item")) items++;
			if (n.equals("mob:villager")) villagers = true;
		}
		hiddenMob = false;
		shownPx.clear();
		for (int px = 0; px < W * H; px++) if (mob[px] >= 0) shownPx.merge(mob[px], 1, Integer::sum);
		for (Entity e : inView) {
			if (!(e instanceof Enemy)) continue;
			boolean shown = shownPx.containsKey(e.getId());
			if (shown) monstersSeen.put(e.getId(), now);
			else if (now - monstersSeen.getOrDefault(e.getId(), -100_000L) < 100 && e.distanceTo(p) < 24) hiddenMob = true;   // (it was there a moment ago)
		}
		monstersSeen.values().removeIf(at -> now - at > 600);
		boolean wasCave = caveMouth, wasStructure = structure;
		caveMouth = dark >= 14;
		caveAt = caveMouth ? BlockPos.containing(cx / dark, cy / dark, cz / dark) : null;
		drop = dropPx >= 8;
		dropDepth = drop ? deep : 0;
		ravine = drop && deep >= 12 && stoneDeep >= dropPx / 2;
		structure = builtPx >= 25 && builtKinds.size() >= 2;                      // (a build has more than one kind of block)
		structureAt = structure ? BlockPos.containing(bx / builtPx, by / builtPx, bz / builtPx) : null;
		village = structure && villagers;
		darkGround = darkTop >= 40;
		mess = home != null && (messPx >= 3 || items >= 4 && p.blockPosition().closerThan(home, 24));
		glance(level);
		if (caveMouth && !wasCave && caveAt != null) {
			c.caves.sawFromAfar(caveAt, "cave");                                    // (the way into it, seen from out here)
			c.journal("sees", "a cave mouth, about " + Math.round(Math.sqrt(caveAt.distSqr(p.blockPosition()))) + " blocks off");
		}
		if (structure && !wasStructure && structureAt != null) {
			c.journal("sees", (village ? "a village" : "something people built") + ", about " + Math.round(Math.sqrt(structureAt.distSqr(p.blockPosition()))) + " blocks off");
			if (!structureAt.closerThan(p.blockPosition(), 16) && (home == null || !structureAt.closerThan(home, 32))) c.habits.spotted(structureAt, village ? "village" : "building");
		}
		notice(level, now);
	}

	/** Its head turns to what moves, or stands out (a monster, someone, a chest, ore in a wall), when its hands are free. */
	private void notice(ServerLevel level, long now) {
		if (now < nextNotice || c.fightingNow() || c.hands.busy()) return;
		var p = c.player;
		Vec3 best = null;
		float score = 0;
		for (Entity e : inView) {
			if (e.distanceTo(p) > reads) continue;                                     // (something far off doesn't turn its head)
			Integer was = lastPixels.get(e.getId());
			int shown = shownPx.getOrDefault(e.getId(), 0);
			lastPixels.put(e.getId(), shown);
			if (shown == 0) continue;
			float s = (e instanceof Enemy ? 3 : e instanceof net.minecraft.world.entity.player.Player ? 2.5f : e instanceof ItemEntity ? 1.2f : 1)
					* (float) (0.5 + Math.min(1.5, e.getDeltaMovement().horizontalDistance() * 8)) * (was == null ? 1.5f : 1);
			if (s > score) {
				score = s;
				best = e.getEyePosition();
			}
		}
		if (best == null) {                                                     // nothing moving: something rare in view (a chest, ore, lava)
			Map<Integer, Integer> count = new HashMap<>();
			for (int px = 0; px < W * H; px++) count.merge(what[px], 1, Integer::sum);
			for (int px = 0; px < W * H; px++) {
				if (count.get(what[px]) > 4 || hit[px] == Long.MIN_VALUE || dist[px] > reads) continue;
				BlockPos b = BlockPos.of(hit[px]);
				String k = Eyes.kind(level.getBlockState(b));
				if (k == null || k.equals("grass") || k.equals("log") || k.equals("water")) continue;
				best = Vec3.atCenterOf(b);
				score = 1;
				break;
			}
		}
		lastPixels.keySet().removeIf(id -> inView.stream().noneMatch(e -> e.getId() == id));
		if (best == null || c.random().nextFloat() > 0.3f + 0.5f * c.personality.curiosity) return;
		nextNotice = now + 30 + c.random().nextInt(50);
		c.habits.notice(best, now + 10 + c.random().nextInt(15));
	}

	/** What it makes of what it sees, in words ("" if nothing much). */
	String scene() {
		List<String> s = new ArrayList<>();
		if (village) s.add("a village");
		else if (structure) s.add("something people built");
		if (caveMouth) s.add("a cave mouth");
		if (ravine) s.add("a ravine");
		else if (drop) s.add("a " + dropDepth + "-block drop");
		if (darkGround) s.add("dark ground (monsters spawn there)");
		if (hiddenMob) s.add("a monster behind cover");
		if (mess) s.add("a mess near home");
		return String.join(", ", s);
	}

	// ------------------------------------------------------------------------------ /xen vision: the picture, to look at
	/** Its view as a picture (a PPM file, 4 times the size) and what each pixel is (a text file next to it). */
	Path dump(Path dir) throws IOException {
		Files.createDirectories(dir);
		var p = c.player;
		Camera snap = new Camera(W, H);                                          // (one whole view now: its own, row by row, is a little smeared when it turns)
		List<Entity> near = new ArrayList<>();
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(c.sight()), e -> e.isAlive() && (e instanceof LivingEntity || e instanceof ItemEntity))) near.add(e);
		snap.shoot(p.level(), p.getEyePosition(), p.getYHeadRot(), p.getXRot(), TAN_V, 16.0 / 9, c.sight(), near, p.level().canSeeSky(p.blockPosition()));
		int[] rgb = snap.rgb, what = snap.what;
		float[] dist = snap.dist;
		byte[] light = snap.light, face = snap.face;
		String base = c.name + "-" + System.currentTimeMillis();
		int k = 4;
		byte[] head = ("P6\n" + W * k + " " + H * k + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] img = new byte[W * k * H * k * 3];
		for (int j = 0; j < H * k; j++) {
			for (int i = 0; i < W * k; i++) {
				int v = rgb[(j / k) * W + i / k], o = (j * W * k + i) * 3;
				img[o] = (byte) (v >> 16);
				img[o + 1] = (byte) (v >> 8);
				img[o + 2] = (byte) v;
			}
		}
		Path ppm = dir.resolve(base + ".ppm");
		byte[] all = new byte[head.length + img.length];
		System.arraycopy(head, 0, all, 0, head.length);
		System.arraycopy(img, 0, all, head.length, img.length);
		Files.write(ppm, all);
		StringBuilder sb = new StringBuilder("# x y label distance light face(0 none,1 top,2 bottom,4 east/west,5 north/south,3 creature) rgb\n");
		for (int px = 0; px < W * H; px++) sb.append(px % W).append(' ').append(px / W).append(' ').append(labelName(what[px]))
				.append(String.format(Locale.ROOT, " %.1f %d %d %06x\n", dist[px], light[px], face[px], rgb[px]));
		Files.writeString(dir.resolve(base + ".txt"), sb.toString());
		return ppm;
	}

	String status() {
		String sc = scene();
		return String.format(Locale.ROOT, "%s: %d views, %.2f ms a view%s", c.name, frames, frames == 0 ? 0 : micros / 1000.0 / frames, sc.isEmpty() ? "" : "; sees " + sc);
	}

	// ------------------------------------------------------------------------------ tuned shading, from recordings
	static void loadTuning(Path f) {
		if (f == null || !Files.exists(f)) return;
		try {
			for (String l : Files.readAllLines(f)) {
				String[] kv = l.trim().split("\\s*=\\s*");
				if (kv.length != 2 || kv[0].startsWith("#")) continue;
				if (kv[0].startsWith("colour.")) {                                   // colour.stone = 7f7f7f
					COLOUR.put(label(kv[0].substring(7)), Integer.parseInt(kv[1], 16));
					continue;
				}
				float v = Float.parseFloat(kv[1]);
				switch (kv[0]) {
					case "top" -> top = v;
					case "bottom" -> bottom = v;
					case "northSouth" -> northSouth = v;
					case "eastWest" -> eastWest = v;
					case "gamma" -> gamma = v;
					case "ambient" -> ambient = v;
					case "hazeFrom" -> hazeFrom = v;
					default -> { }
				}
			}
			XenMod.LOG.info("xen: vision shading from {}", f);
		} catch (IOException | RuntimeException e) {
			XenMod.LOG.warn("xen: couldn't read {}: {}", f, e.toString());
		}
	}
}
