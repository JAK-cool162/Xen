package xen.mod;

import io.netty.buffer.ByteBuf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
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
 * block for the other. Then a screen comes up (with Xen on the game too: {@link xen.mod.client.BuildAxeScreen}): what
 * it is (a build, a tree, a cave), its name (a must), Save. Without the screen, /xen BuildAxe save &lt;name&gt;. The box
 * goes as one line of JSON to config/xen/buildaxe/&lt;kind&gt;.jsonl. The axe never breaks or strips anything. Only
 * blocks are kept: no player names, no coordinates.
 */
public final class BuildAxe {
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

	// ------------------------------------------------------------------------------ the screen, and what it sends back
	/** To the game: the box is marked, show the screen (its size, what it's marked as). */
	public record Open(int sx, int sy, int sz, String kind) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Open> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xen", "build_axe_open"));
		public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Open::sx, ByteBufCodecs.VAR_INT, Open::sy,
				ByteBufCodecs.VAR_INT, Open::sz, ByteBufCodecs.STRING_UTF8, Open::kind, Open::new);

		@Override
		public CustomPacketPayload.Type<Open> type() {
			return TYPE;
		}
	}

	/** From the game (with Xen on it too): a corner it clicked (1 hit, 2 right-click), or 0: show the screen again. */
	public record Corner(BlockPos pos, int which) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Corner> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xen", "build_axe_corner"));
		public static final StreamCodec<ByteBuf, Corner> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Corner::pos, ByteBufCodecs.VAR_INT, Corner::which, Corner::new);

		@Override
		public CustomPacketPayload.Type<Corner> type() {
			return TYPE;
		}
	}

	/** From the screen: save it as this kind, under this name. */
	public record Save(String kind, String name) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Save> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xen", "build_axe_save"));
		public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Save::kind, ByteBufCodecs.STRING_UTF8, Save::name, Save::new);

		@Override
		public CustomPacketPayload.Type<Save> type() {
			return TYPE;
		}
	}

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

	public static boolean holding(Player p) {
		return is(p.getMainHandItem());
	}

	/**
	 * The hooks: hitting a block with it (a corner), using it on a block (the other corner); it never breaks or strips
	 * anything. These are the server's side (a game without Xen sends the clicks as usual); a game with Xen sends the
	 * corners itself ({@link Corner}: it holds the click back, so nothing else would reach the server).
	 */
	static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
			if (level.isClientSide() || !holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) corner(sp, sl, pos, true);
			return InteractionResult.SUCCESS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) corner(sp, sl, hit.getBlockPos(), false);
			return InteractionResult.SUCCESS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp) reopen(sp);                   // (into the air: the screen again, for the box it has)
			return InteractionResult.SUCCESS;
		});
		Compat.toServer(Corner.TYPE, Corner.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Corner.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			if (!holding(p)) return;
			if (payload.which() == 0) reopen(p);
			else if (p.blockPosition().closerThan(payload.pos(), 12) && p.level() instanceof ServerLevel sl) corner(p, sl, payload.pos(), payload.which() == 1);
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !holding(player));
		Compat.toClient(Open.TYPE, Open.CODEC);
		Compat.toServer(Save.TYPE, Save.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Save.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			if (!cheats(p.createCommandSourceStack())) return;
			String said = save(p, payload.kind(), payload.name());
			p.sendSystemMessage(Component.literal(said).withStyle(said.startsWith("[Xen] Saved") ? ChatFormatting.GOLD : ChatFormatting.RED));
		});
	}

	/** Both corners marked: the screen (with Xen on the game), or how to name it in chat. */
	private static void ask(ServerPlayer p, Box box) {
		int sx = Math.abs(box.a.getX() - box.b.getX()) + 1, sy = Math.abs(box.a.getY() - box.b.getY()) + 1, sz = Math.abs(box.a.getZ() - box.b.getZ()) + 1;
		if (ServerPlayNetworking.canSend(p, Open.TYPE)) {
			ServerPlayNetworking.send(p, new Open(sx, sy, sz, box.kind));
			return;
		}
		p.sendSystemMessage(Component.literal("[Xen] " + size(box) + " marked. Now name it: /xen BuildAxe save <name>").withStyle(ChatFormatting.GOLD));
	}

	private static void reopen(ServerPlayer p) {
		Box box = boxes.get(p.getUUID());
		if (box != null && box.a != null && box.b != null && fits(box) && cheats(p.createCommandSourceStack())) ask(p, box);
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
		boolean both = box.a != null && box.b != null;
		String size = both ? ": " + size(box) + (fits(box) ? "" : " (too big: " + MAX_SIDE + " a side at most)") : "";
		p.sendSystemMessage(Component.literal("[Xen] Corner " + (first ? 1 : 2) + " (" + box.kind + ")" + size).withStyle(ChatFormatting.GOLD));
		if (both && fits(box)) ask(p, box);
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
		c.then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.greedyString()).executes(BuildAxe::saveCommand))
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
		ctx.getSource().sendSuccess(() -> Component.literal("[Xen] Build Axe (" + kind + "): hit a block for one corner, right-click a block for the other, then name it. Saved to config/xen/buildaxe/" + kind + ".jsonl."), false);
		return 1;
	}

	private static int saveCommand(CommandContext<CommandSourceStack> ctx) {
		if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
			ctx.getSource().sendFailure(Component.literal("Only a player can hold the Build Axe."));
			return 0;
		}
		Box box = boxes.get(p.getUUID());
		String said = save(p, box == null ? "build" : box.kind, StringArgumentType.getString(ctx, "name"));
		if (!said.startsWith("[Xen] Saved")) {
			ctx.getSource().sendFailure(Component.literal(said));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(said).withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	/** Saves the player's box as this kind under this name: what happened, in words ("[Xen] Saved ..." when it did). */
	static String save(ServerPlayer p, String kind, String name) {
		name = name == null ? "" : name.trim();
		Box box = boxes.get(p.getUUID());
		if (box == null || box.a == null || box.b == null) return "Mark the box first: hit one corner and right-click the other with the Build Axe.";
		if (!fits(box)) return "Too big (" + size(box) + "): " + MAX_SIDE + " a side at most.";
		if (name.isEmpty() || name.length() > 64) return "Give it a name (1 to 64 letters).";
		if (!Arrays.asList(KINDS).contains(kind)) kind = box.kind;
		try {
			JsonObject line = capture(box.level, box.a, box.b, kind, name);
			Path f = dir().resolve(kind + ".jsonl");
			Files.createDirectories(f.getParent());
			Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			long lines;
			try (var st = Files.lines(f)) {
				lines = st.filter(l -> !l.isBlank()).count();
			}
			int solid = line.getAsJsonObject("features").get("solid").getAsInt();
			String sz = size(box);
			XenMod.LOG.info("Build Axe: saved {} \"{}\" ({}) to {}", kind, name, sz, f);
			box.kind = kind;
			box.a = null;
			box.b = null;
			return "[Xen] Saved " + kind + " \"" + name + "\" (" + sz + ", " + solid + " blocks) to config/xen/buildaxe/" + kind + ".jsonl (" + lines + " in it).";
		} catch (IOException e) {
			return "Couldn't save it: " + e.getMessage();
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
