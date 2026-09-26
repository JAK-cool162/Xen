package xen.mod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statues, the way players build them: a player's skin, pixel by pixel, one block for each pixel, standing 32 blocks
 * tall (head 8 by 8 by 8, body 8 by 12 by 4, arms and legs 4 by 12 by 4, slim arms 3 wide), the skin's outer layer
 * (hair, a hood, a jacket) laid over it. Every pixel becomes the block closest to its colour: concrete, terracotta,
 * wool, planks, stone, ores. In survival only blocks it has (so it looks rougher, like anyone's first statue).
 */
final class Statue {
	private Statue() {}

	/** Blocks and their colours (their average texture colour). */
	private static final Object[][] COLOURS = {
			{"white_concrete", 207, 213, 214}, {"orange_concrete", 224, 97, 1}, {"magenta_concrete", 169, 48, 159}, {"light_blue_concrete", 36, 137, 199},
			{"yellow_concrete", 241, 175, 21}, {"lime_concrete", 94, 169, 24}, {"pink_concrete", 214, 101, 143}, {"gray_concrete", 55, 58, 62},
			{"light_gray_concrete", 125, 125, 115}, {"cyan_concrete", 21, 119, 136}, {"purple_concrete", 100, 32, 156}, {"blue_concrete", 45, 47, 143},
			{"brown_concrete", 96, 60, 32}, {"green_concrete", 73, 91, 36}, {"red_concrete", 142, 33, 33}, {"black_concrete", 8, 10, 15},
			{"white_terracotta", 210, 178, 161}, {"orange_terracotta", 162, 84, 38}, {"magenta_terracotta", 150, 88, 109}, {"light_blue_terracotta", 113, 109, 138},
			{"yellow_terracotta", 186, 133, 35}, {"lime_terracotta", 104, 118, 53}, {"pink_terracotta", 162, 78, 79}, {"gray_terracotta", 58, 42, 36},
			{"light_gray_terracotta", 135, 107, 98}, {"cyan_terracotta", 87, 91, 91}, {"purple_terracotta", 118, 70, 86}, {"blue_terracotta", 74, 60, 91},
			{"brown_terracotta", 77, 51, 36}, {"green_terracotta", 76, 83, 42}, {"red_terracotta", 143, 61, 47}, {"black_terracotta", 37, 23, 16},
			{"terracotta", 152, 94, 68}, {"white_wool", 234, 236, 237}, {"orange_wool", 241, 118, 20}, {"yellow_wool", 249, 198, 40},
			{"light_blue_wool", 58, 175, 217}, {"pink_wool", 238, 141, 172}, {"gray_wool", 63, 68, 72}, {"light_gray_wool", 142, 142, 135},
			{"brown_wool", 114, 72, 41}, {"lime_wool", 112, 185, 26}, {"snow_block", 249, 254, 254}, {"quartz_block", 236, 230, 223},
			{"smooth_sandstone", 223, 214, 170}, {"birch_planks", 192, 175, 121}, {"oak_planks", 162, 131, 79}, {"spruce_planks", 115, 85, 49},
			{"dark_oak_planks", 67, 43, 20}, {"jungle_planks", 160, 115, 81}, {"acacia_planks", 168, 90, 50}, {"mangrove_planks", 118, 54, 49},
			{"cherry_planks", 226, 178, 172}, {"stone", 125, 125, 125}, {"andesite", 136, 136, 136}, {"deepslate", 80, 80, 82},
			{"blackstone", 42, 36, 41}, {"mud_bricks", 137, 103, 79}, {"packed_mud", 142, 106, 79}, {"bone_block", 229, 225, 207},
			{"calcite", 223, 224, 220}, {"tuff", 108, 109, 102}, {"clay", 160, 166, 179}, {"bricks", 150, 97, 83}, {"dripstone_block", 134, 107, 92},
			{"lapis_block", 31, 67, 140}, {"prismarine", 99, 156, 151}, {"purpur_block", 169, 125, 169}, {"cobblestone", 127, 127, 127},
			{"dirt", 134, 96, 67}, {"sandstone", 216, 203, 155}, {"netherrack", 97, 38, 38}, {"moss_block", 89, 109, 45}};

	/** The skin (its texture: base64 of Mojang's JSON) of a player, or null (offline players have none). */
	static String textureOf(ServerPlayer p) {
		for (Property prop : p.getGameProfile().properties().get("textures")) return prop.value();
		return null;
	}

	/** A Xen's skin setting ("texture:value:signature") as a texture, or null (a built-in skin by name). */
	static String textureOf(String skin) {
		if (skin == null || !skin.startsWith("texture:")) return null;
		String[] parts = skin.split(":");
		return parts.length >= 2 ? parts[1] : null;
	}

	/** Download the skin image of a texture (with whether it has slim arms). Slow: never on the server thread. */
	static Object[] download(String texture) throws Exception {
		JsonObject o = new Gson().fromJson(new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8), JsonObject.class);
		JsonObject skin = o.getAsJsonObject("textures").getAsJsonObject("SKIN");
		boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
				&& skin.getAsJsonObject("metadata").get("model").getAsString().equals("slim");
		var conn = URI.create(skin.get("url").getAsString()).toURL().openConnection();
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(10000);
		try (InputStream in = conn.getInputStream()) {
			BufferedImage img = javax.imageio.ImageIO.read(in);
			if (img == null || img.getWidth() != 64) throw new IllegalStateException("not a 64x64 skin");
			return new Object[] {img, slim};
		}
	}

	/** One box of the model: where its faces are in the skin (base and outer layer), and its size. */
	private record Box(int x, int y, int ox, int oy, int w, int h, int d) {}

	/**
	 * The statue's plan, facing front: origin is where its right foot's front corner stands (as you look at it, the
	 * lower left). Only blocks from the palette given (null: all of them).
	 */
	static Architect.Plan plan(BlockPos origin, Direction front, BufferedImage img, boolean slim, List<String> palette, String who) {
		Direction right = front.getCounterClockWise(), back = front.getOpposite();
		int aw = slim ? 3 : 4;
		Map<BlockPos, String> blocks = new LinkedHashMap<>();
		// (u across as you look at it from the front, v into it, y up); the statue's right side is on your left
		place(blocks, img, new Box(0, 16, 0, 32, 4, 12, 4), 0, 0, 0);                 // right leg
		place(blocks, img, new Box(16, 48, 0, 48, 4, 12, 4), 4, 0, 0);                // left leg
		place(blocks, img, new Box(16, 16, 16, 32, 8, 12, 4), 0, 12, 0);              // body
		place(blocks, img, new Box(40, 16, 40, 32, aw, 12, 4), -aw, 12, 0);           // right arm
		place(blocks, img, new Box(32, 48, 48, 48, aw, 12, 4), 8, 12, 0);             // left arm
		place(blocks, img, new Box(0, 0, 32, 0, 8, 8, 8), 0, 24, -2);                 // head
		List<Architect.Step> steps = new ArrayList<>();
		Map<String, net.minecraft.world.level.block.state.BlockState> states = new LinkedHashMap<>();
		for (var e : blocks.entrySet()) {
			BlockPos local = e.getKey();
			BlockPos at = origin.relative(right, local.getX()).relative(back, local.getZ()).above(local.getY());
			String name = palette == null ? e.getValue() : nearest(colour(e.getValue()), palette);
			var state = states.computeIfAbsent(name, n -> BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(n))
					.orElse(net.minecraft.world.level.block.Blocks.STONE).defaultBlockState());
			steps.add(new Architect.Step(at, state, Architect.FRAME, false));
		}
		for (int u = -aw - 1; u <= 8 + aw; u++) {                               // room for it: anything in the way dug out
			for (int v = -3; v <= 6; v++) {
				for (int y = 0; y <= 32; y++) {
					BlockPos at = origin.relative(right, u).relative(back, v).above(y);
					if (!blocks.containsKey(new BlockPos(u, y, v))) steps.add(new Architect.Step(at, null, Architect.DIG, false));
				}
			}
		}
		steps.sort((a, b) -> a.phase() != b.phase() ? Integer.compare(a.phase(), b.phase())
				: a.dig() ? Integer.compare(b.pos().getY(), a.pos().getY()) : Integer.compare(a.pos().getY(), b.pos().getY()));
		BlockPos middle = origin.relative(right, 4).relative(front, 3);
		return new Architect.Plan("statue of " + who, steps, middle, middle, front,
				new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atCenterOf(origin.relative(right, -aw).relative(back, -2)),
						net.minecraft.world.phys.Vec3.atCenterOf(origin.relative(right, 7 + aw).relative(back, 5).above(31))));
	}

	/** Every block of one box: the colour of the face it's on (the outer layer over the base where it has one). */
	private static void place(Map<BlockPos, String> out, BufferedImage img, Box b, int u0, int y0, int v0) {
		for (int i = 0; i < b.w(); i++) {
			for (int j = 0; j < b.h(); j++) {
				for (int k = 0; k < b.d(); k++) {
					int[] px = pixel(b, i, j, k);
					int rgb = sample(img, b, px, true);
					if (rgb == -1) rgb = sample(img, b, px, false);
					if (rgb == -1) rgb = 0x7f7f7f;
					out.put(new BlockPos(u0 + i, y0 + (b.h() - 1 - j), v0 + k), nearest(rgb, null));
				}
			}
		}
	}

	/** Which face a block of the box shows and where on it: {face 0-5, column, row}. Faces: front, back, right, left, top, bottom. */
	private static int[] pixel(Box b, int i, int j, int k) {
		int w = b.w(), h = b.h(), d = b.d();
		if (k == 0) return new int[] {0, i, j};                                   // the front (and the inside: its colour)
		if (k == d - 1) return new int[] {1, w - 1 - i, j};
		if (i == 0) return new int[] {2, d - 1 - k, j};                           // its right side (on your left)
		if (i == w - 1) return new int[] {3, k, j};
		if (j == 0) return new int[] {4, i, d - 1 - k};
		if (j == h - 1) return new int[] {5, i, k};
		return new int[] {0, i, j};
	}

	/** The colour of that pixel of the skin (base or outer layer), or -1 if it's see-through. */
	private static int sample(BufferedImage img, Box b, int[] px, boolean outer) {
		int x = outer ? b.ox() : b.x(), y = outer ? b.oy() : b.y(), w = b.w(), h = b.h(), d = b.d();
		int[] r = switch (px[0]) {
			case 0 -> new int[] {x + d, y + d};
			case 1 -> new int[] {x + 2 * d + w, y + d};
			case 2 -> new int[] {x, y + d};
			case 3 -> new int[] {x + d + w, y + d};
			case 4 -> new int[] {x + d, y};
			default -> new int[] {x + d + w, y};
		};
		int argb = img.getRGB(r[0] + px[1], r[1] + px[2]);
		if ((argb >>> 24) < 128) return -1;
		return argb & 0xffffff;
	}

	private static int colour(String block) {
		for (Object[] c : COLOURS) if (c[0].equals(block)) return ((Integer) c[1] << 16) | ((Integer) c[2] << 8) | (Integer) c[3];
		return 0x7f7f7f;
	}

	/** The block closest to a colour (by eye: green counts most), out of the palette (null: all). */
	static String nearest(int rgb, List<String> palette) {
		int r = rgb >> 16 & 255, g = rgb >> 8 & 255, bl = rgb & 255;
		String best = "stone";
		double bestD = Double.MAX_VALUE;
		for (Object[] c : COLOURS) {
			if (palette != null && !palette.contains((String) c[0])) continue;
			double dr = r - (Integer) c[1], dg = g - (Integer) c[2], db = bl - (Integer) c[3];
			double dd = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
			if (dd < bestD) {
				bestD = dd;
				best = (String) c[0];
			}
		}
		return best;
	}

	/** Survival: the blocks it carries that a statue can be made of. */
	static List<String> carried(net.minecraft.world.entity.player.Player p) {
		List<String> out = new ArrayList<>();
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
			String n = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).getPath();
			for (Object[] c : COLOURS) if (c[0].equals(n) && !out.contains(n)) out.add(n);
		}
		return out;
	}
}
