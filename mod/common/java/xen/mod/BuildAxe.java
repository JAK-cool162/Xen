package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Build Axe, a developer's tool (cheats only): an enchanted wooden axe that turns a box of the world into data for
 * training Xen. /xen BuildAxe [build|tree|cave] gives one; with it, hit a block for one corner and use (right-click) a
 * block for the other, then /xen BuildAxe save &lt;name&gt; writes the box as one line of JSON to
 * config/xen/buildaxe/&lt;kind&gt;.jsonl. The axe never breaks or strips anything. Only blocks are kept: no player
 * names, no coordinates.
 */
final class BuildAxe {
	private BuildAxe() {}

	static final String[] KINDS = {"build", "tree", "cave"};
	/** The biggest box, a side (a line stays a few megabytes at most). */
	static final int MAX_SIDE = 64;
	private static final String TAG = "xen_build_axe";

	/** Each player's box so far (two corners and a world) and what it's marked as. */
	private static final class Box {
		BlockPos a, b;
		ServerLevel level;
		String kind = "build";
	}

	private static final Map<UUID, Box> boxes = new HashMap<>();

	static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("buildaxe");
	}

	// ------------------------------------------------------------------------------ the axe
	static ItemStack make(String kind) {
		ItemStack s = new ItemStack(Items.WOODEN_AXE);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Build Axe (" + kind + ")").withStyle(ChatFormatting.GOLD));
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag t = new CompoundTag();
		t.putString(TAG, kind);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		return s;
	}

	static boolean is(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().contains(TAG);
	}

	/** Cheats on (an operator, or a single-player world with cheats allowed): it's a tool, not part of the game. */
	static boolean cheats(CommandSourceStack src) {
		return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static boolean holding(Player p) {
		return is(p.getMainHandItem());
	}

	/** The hooks: hitting a block with it (a corner), using it on a block (the other corner); it never breaks or strips anything. */
	static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
			if (!holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) corner(sp, sl, pos, true);
			return InteractionResult.SUCCESS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) corner(sp, sl, hit.getBlockPos(), false);
			return InteractionResult.SUCCESS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> holding(player) ? InteractionResult.SUCCESS : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !holding(player));
	}

	private static void corner(ServerPlayer p, ServerLevel level, BlockPos pos, boolean first) {
		if (!cheats(p.createCommandSourceStack())) {
			p.sendSystemMessage(Component.literal("[Xen] The Build Axe needs cheats on.").withStyle(ChatFormatting.GRAY));
			return;
		}
		Box box = boxes.computeIfAbsent(p.getUUID(), k -> new Box());
		BlockPos at = pos.immutable();
		if (at.equals(first ? box.a : box.b) && box.level == level) return;      // (the same corner again: holding the button)
		if (box.level != level) {
			box.a = null;
			box.b = null;
		}
		box.level = level;
		if (first) box.a = at;
		else box.b = at;
		String size = box.a != null && box.b != null ? ": " + size(box) + (fits(box) ? ". Now name it: /xen BuildAxe save <name>" : " (too big: " + MAX_SIDE + " a side at most)") : "";
		p.sendSystemMessage(Component.literal("[Xen] Corner " + (first ? 1 : 2) + " (" + box.kind + ")" + size).withStyle(ChatFormatting.GOLD));
	}

	private static String size(Box b) {
		return (Math.abs(b.a.getX() - b.b.getX()) + 1) + " x " + (Math.abs(b.a.getY() - b.b.getY()) + 1) + " x " + (Math.abs(b.a.getZ() - b.b.getZ()) + 1);
	}

	private static boolean fits(Box b) {
		return Math.abs(b.a.getX() - b.b.getX()) < MAX_SIDE && Math.abs(b.a.getY() - b.b.getY()) < MAX_SIDE && Math.abs(b.a.getZ() - b.b.getZ()) < MAX_SIDE;
	}

	// ------------------------------------------------------------------------------ the command
	static LiteralArgumentBuilder<CommandSourceStack> command(String name) {
		LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal(name).requires(BuildAxe::cheats).executes(ctx -> give(ctx, "build"));
		for (String k : KINDS) c.then(Commands.literal(k).executes(ctx -> give(ctx, k)));
		c.then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.greedyString()).executes(BuildAxe::save))
				.executes(ctx -> {
					ctx.getSource().sendFailure(Component.literal("Give it a name: /xen BuildAxe save <name>"));
					return 0;
				}));
		return c;
	}

	private static int give(CommandContext<CommandSourceStack> ctx, String kind) {
		if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
			ctx.getSource().sendFailure(Component.literal("Only a player can hold the Build Axe."));
			return 0;
		}
		Box box = boxes.computeIfAbsent(p.getUUID(), k -> new Box());
		box.kind = kind;
		if (!holding(p) && !p.getInventory().add(make(kind))) Compat.drop(p, make(kind));
		else if (holding(p)) p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, make(kind));
		ctx.getSource().sendSuccess(() -> Component.literal("[Xen] Build Axe (" + kind + "): hit a block for one corner, right-click a block for the other, then /xen BuildAxe save <name>. Saved to config/xen/buildaxe/" + kind + ".jsonl."), false);
		return 1;
	}

	private static int save(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		Box box = ctx.getSource().getEntity() instanceof ServerPlayer p ? boxes.get(p.getUUID()) : null;
		if (box == null || box.a == null || box.b == null) {
			ctx.getSource().sendFailure(Component.literal("Mark the box first: hit one corner and right-click the other with the Build Axe."));
			return 0;
		}
		if (!fits(box)) {
			ctx.getSource().sendFailure(Component.literal("Too big (" + size(box) + "): " + MAX_SIDE + " a side at most."));
			return 0;
		}
		if (name.isEmpty() || name.length() > 64) {
			ctx.getSource().sendFailure(Component.literal("A name of 1 to 64 letters, please."));
			return 0;
		}
		try {
			JsonObject line = capture(box.level, box.a, box.b, box.kind, name);
			Path f = dir().resolve(box.kind + ".jsonl");
			Files.createDirectories(f.getParent());
			Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			long lines;
			try (var s = Files.lines(f)) {
				lines = s.filter(l -> !l.isBlank()).count();
			}
			int solid = line.getAsJsonObject("features").get("solid").getAsInt();
			String sz = size(box);
			ctx.getSource().sendSuccess(() -> Component.literal("[Xen] Saved " + box.kind + " \"" + name + "\" (" + sz + ", " + solid + " blocks) to config/xen/buildaxe/"
					+ box.kind + ".jsonl (" + lines + " in it).").withStyle(ChatFormatting.GOLD), false);
			XenMod.LOG.info("Build Axe: saved {} \"{}\" ({}) to {}", box.kind, name, sz, f);
			box.a = null;
			box.b = null;
			return 1;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.literal("Couldn't save it: " + e.getMessage()));
			return 0;
		}
	}

	// ------------------------------------------------------------------------------ the box, as a line of JSON
	/**
	 * Every block in the box (air too: inside a house, or the cave itself) as a palette and one index per block, x
	 * fastest: index = (y * sz + z) * sx + x. With it, where it was (the dimension and biome), and for a cave what the
	 * blocks alone don't tell: how dark it is and how far under the surface.
	 */
	static JsonObject capture(ServerLevel level, BlockPos a, BlockPos b, String kind, String name) {
		BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		int sx = hi.getX() - lo.getX() + 1, sy = hi.getY() - lo.getY() + 1, sz = hi.getZ() - lo.getZ() + 1;
		Map<String, Integer> index = new LinkedHashMap<>();
		int[] cells = new int[sx * sy * sz];
		List<Integer> sky = new ArrayList<>(), lamp = new ArrayList<>();
		int solid = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					m.set(lo.getX() + x, lo.getY() + y, lo.getZ() + z);
					BlockState s = level.getBlockState(m);
					if (s.isAir()) {
						sky.add(level.getBrightness(LightLayer.SKY, m));
						lamp.add(level.getBrightness(LightLayer.BLOCK, m));
					} else {
						solid++;
					}
					cells[(y * sz + z) * sx + x] = index.computeIfAbsent(key(s), k -> index.size());
				}
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind);
		o.addProperty("name", name);
		o.addProperty("dim", Places.dim(level));
		o.addProperty("biome", level.getBiome(lo).unwrapKey().map(k -> k.identifier().getPath()).orElse(""));
		JsonArray size = new JsonArray();
		size.add(sx);
		size.add(sy);
		size.add(sz);
		o.add("size", size);
		o.addProperty("order", "index = (y * sz + z) * sx + x");
		JsonObject f = new JsonObject();
		f.addProperty("solid", solid);
		f.addProperty("open", sky.size());
		if (!sky.isEmpty()) {
			f.addProperty("sky_light", median(sky));                             // (0 dark to 15 open sky, the middle of the open blocks)
			f.addProperty("block_light", median(lamp));                          // (torches, lava)
		}
		int mx = (lo.getX() + hi.getX()) / 2, mz = (lo.getZ() + hi.getZ()) / 2;
		f.addProperty("below_surface", level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz) - lo.getY());   // (the surface over its middle, from its bottom)
		o.add("features", f);
		JsonArray palette = new JsonArray();
		for (String k : index.keySet()) palette.add(k);
		o.add("palette", palette);
		JsonArray data = new JsonArray();
		for (int v : cells) data.add(v);
		o.add("blocks", data);
		return o;
	}

	/** "oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]" (another mod's block with its own name first). */
	private static String key(BlockState s) {
		var k = BuiltInRegistries.BLOCK.getKey(s.getBlock());
		String n = k.getNamespace().equals("minecraft") ? k.getPath() : k.toString();
		String t = s.toString();                                                    // Block{minecraft:oak_stairs}[facing=north,...]
		int i = t.indexOf("}["), j = t.lastIndexOf(']');
		return i < 0 || j <= i ? n : n + t.substring(i + 1, j + 1);
	}

	private static int median(List<Integer> v) {
		int[] s = v.stream().mapToInt(Integer::intValue).toArray();
		Arrays.sort(s);
		return s[s.length / 2];
	}
}
