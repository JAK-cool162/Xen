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
 * training Xen. /xen BuildAxe [build|tree|cave] gives one; with it, hit a block for one corner and another for the
 * other; right-click blocks to leave them out (saved as air: the grass round a tree). Then a screen comes up (with Xen
 * on the game too: {@link xen.mod.client.BuildAxeScreen}): what it is (a build, a tree, a cave, or a kind of the
 * player's own), its name (a must), Save. Without the screen, /xen BuildAxe save &lt;name&gt;. The box goes as one line
 * of JSON to config/xen/buildaxe/&lt;kind&gt;.jsonl. The axe never breaks or strips anything. Only blocks are kept: no
 * player names, no coordinates.
 */
public final class BuildAxe {
	private BuildAxe() {}

	static final String[] KINDS = {"build", "tree", "cave"};
	/** The biggest box: a side, and all of it (256 x 64 x 256, a village; a line of about 15 megabytes at most). */
	static final int MAX_SIDE = 256, MAX_BLOCKS = 256 * 64 * 256;
	/** How far off it marks a block, from a game with Xen in it (aimed by the game itself, not a hand's reach). */
	public static final int REACH = 160;
	private static final String TAG = "xen_build_axe";

	/** Each player's box so far (two corners and a world), what it's marked as, and the blocks it leaves out (saved as air). */
	private static final class Box {
		BlockPos a, b;
		ServerLevel level;
		String kind = "build";
		final java.util.Set<Long> out = new java.util.HashSet<>();
		final java.util.Set<String> outKinds = new java.util.TreeSet<>();

		boolean left(BlockPos p, BlockState s) {
			return out.contains(p.asLong()) || outKinds.contains(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
		}
	}

	private static final Map<UUID, Box> boxes = new HashMap<>();

	// ------------------------------------------------------------------------------ the screen, and what it sends back
	/** To the game: the box is marked, show the screen (its size, what it's marked as, how many blocks it leaves out and of what kinds all). */
	public record Open(int sx, int sy, int sz, String kind, int left, String leftKinds) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Open> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xen", "build_axe_open"));
		public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Open::sx, ByteBufCodecs.VAR_INT, Open::sy,
				ByteBufCodecs.VAR_INT, Open::sz, ByteBufCodecs.STRING_UTF8, Open::kind, ByteBufCodecs.VAR_INT, Open::left, ByteBufCodecs.STRING_UTF8, Open::leftKinds, Open::new);

		@Override
		public CustomPacketPayload.Type<Open> type() {
			return TYPE;
		}
	}

	/** From the game (with Xen on it too): a block it hit (1: a corner) or right-clicked (2: leave it out), or 0: the screen again. */
	public record Corner(BlockPos pos, int which) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Corner> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("xen", "build_axe_corner"));
		public static final StreamCodec<ByteBuf, Corner> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Corner::pos, ByteBufCodecs.VAR_INT, Corner::which, Corner::new);

		@Override
		public CustomPacketPayload.Type<Corner> type() {
			return TYPE;
		}
	}

	/** From the screen: save it as this kind (one of its own too: "house", "farm"), under this name. */
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
	 * The hooks: hitting a block with it (a corner, then the other), using it on a block (leave that block out: it's
	 * saved as air; crouching, every block of its kind in the box), using it in the air (the screen again). It never
	 * breaks or strips anything. These are the server's side (a game without Xen sends the clicks as usual); a game with
	 * Xen sends them itself ({@link Corner}: it holds the click back, so nothing else would reach the server).
	 */
	static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
			if (level.isClientSide() || !holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) corner(sp, sl, pos);
			return InteractionResult.SUCCESS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !holding(player)) return InteractionResult.PASS;
			if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) leaveOut(sp, sl, hit.getBlockPos());
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
			else if (!p.blockPosition().closerThan(payload.pos(), REACH + 8) || !(p.level() instanceof ServerLevel sl)) return;
			else if (payload.which() == 1) corner(p, sl, payload.pos());
			else leaveOut(p, sl, payload.pos());
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
		int left = 0;
		for (long k : box.out) if (inside(box, BlockPos.of(k))) left++;
		if (ServerPlayNetworking.canSend(p, Open.TYPE)) {
			ServerPlayNetworking.send(p, new Open(sx, sy, sz, box.kind, left, String.join(", ", box.outKinds)));
			return;
		}
		p.sendSystemMessage(Component.literal("[Xen] " + size(box) + " marked. Now name it: /xen BuildAxe save <name>").withStyle(ChatFormatting.GOLD));
	}

	private static boolean inside(Box b, BlockPos p) {
		return p.getX() >= Math.min(b.a.getX(), b.b.getX()) && p.getX() <= Math.max(b.a.getX(), b.b.getX()) && p.getY() >= Math.min(b.a.getY(), b.b.getY())
				&& p.getY() <= Math.max(b.a.getY(), b.b.getY()) && p.getZ() >= Math.min(b.a.getZ(), b.b.getZ()) && p.getZ() <= Math.max(b.a.getZ(), b.b.getZ());
	}

	private static void reopen(ServerPlayer p) {
		Box box = boxes.get(p.getUUID());
		if (box != null && box.a != null && box.b != null && fits(box) && cheats(p.createCommandSourceStack())) ask(p, box);
	}

	/** A hit: the first corner, then the other (and the screen); after a whole box, a new one starts. */
	private static void corner(ServerPlayer p, ServerLevel level, BlockPos pos) {
		if (!cheats(p.createCommandSourceStack())) {
			p.sendSystemMessage(Component.literal("[Xen] The Build Axe needs cheats on.").withStyle(ChatFormatting.GRAY));
			return;
		}
		Box box = boxes.computeIfAbsent(p.getUUID(), k -> new Box());
		BlockPos at = pos.immutable();
		if (box.level == level && (at.equals(box.b) || box.b == null && at.equals(box.a))) return;   // (the same block again: holding the button)
		boolean first = box.a == null || box.b != null || box.level != level;
		if (first && (box.b != null || box.level != level)) {                       // (a box done: a new one, nothing left out yet)
			box.out.clear();
			box.outKinds.clear();
		}
		box.level = level;
		if (first) {
			box.a = at;
			box.b = null;
		} else {
			box.b = at;
		}
		String size = first ? ". Hit another block for the other corner." : ": " + size(box) + (fits(box) ? "" : " (too big: " + tooBig() + ")");
		p.sendSystemMessage(Component.literal("[Xen] Corner " + (first ? 1 : 2) + size).withStyle(ChatFormatting.GOLD));
		if (!first && fits(box)) ask(p, box);
	}

	/** A right-click on a block: leave it out (saved as air), or put it back; crouching, every block of its kind in the box. */
	private static void leaveOut(ServerPlayer p, ServerLevel level, BlockPos pos) {
		if (!cheats(p.createCommandSourceStack())) return;
		Box box = boxes.computeIfAbsent(p.getUUID(), k -> new Box());
		if (box.level != level) {
			box.level = level;
			box.a = null;
			box.b = null;
			box.out.clear();
			box.outKinds.clear();
		}
		String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
		if (level.getBlockState(pos).isAir()) return;
		String said;
		if (p.isShiftKeyDown()) {
			said = box.outKinds.remove(n) ? "Putting back every " + n + "." : box.outKinds.add(n) ? "Leaving out every " + n + " (saved as air)." : "";
		} else {
			long k = pos.asLong();
			said = box.out.remove(k) ? "Putting back that " + n + "." : box.out.add(k) ? "Leaving out that " + n + " (saved as air)." : "";
		}
		int blocks = box.out.size();
		String all = box.outKinds.isEmpty() ? "" : "; every " + String.join(", ", box.outKinds);
		p.sendSystemMessage(Component.literal("[Xen] " + said + " Left out: " + blocks + (blocks == 1 ? " block" : " blocks") + all + ".").withStyle(ChatFormatting.GOLD));
	}

	private static String size(Box b) {
		return (Math.abs(b.a.getX() - b.b.getX()) + 1) + " x " + (Math.abs(b.a.getY() - b.b.getY()) + 1) + " x " + (Math.abs(b.a.getZ() - b.b.getZ()) + 1);
	}

	private static boolean fits(Box b) {
		long sx = Math.abs(b.a.getX() - b.b.getX()) + 1, sy = Math.abs(b.a.getY() - b.b.getY()) + 1, sz = Math.abs(b.a.getZ() - b.b.getZ()) + 1;
		return sx <= MAX_SIDE && sy <= MAX_SIDE && sz <= MAX_SIDE && sx * sy * sz <= MAX_BLOCKS;
	}

	private static String tooBig() {
		return MAX_SIDE + " a side, " + String.format(java.util.Locale.ROOT, "%,d", MAX_BLOCKS) + " blocks in all, at most";
	}

	// ------------------------------------------------------------------------------ the command
	/** A kind as a file name: "Big House" is "big_house" (letters, digits, - and _, up to 32), or null if nothing's left. */
	static String kind(String k) {
		String n = k == null ? "" : k.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
		return n.isEmpty() ? null : n.length() > 32 ? n.substring(0, 32) : n;
	}

	static LiteralArgumentBuilder<CommandSourceStack> command(String name) {
		LiteralArgumentBuilder<CommandSourceStack> c = Commands.literal(name).requires(BuildAxe::cheats).executes(ctx -> give(ctx, "build"));
		for (String k : KINDS) c.then(Commands.literal(k).executes(ctx -> give(ctx, k)));
		c.then(Commands.literal("type").then(Commands.argument("kind", StringArgumentType.greedyString())   // (one of its own, without the screen)
				.executes(ctx -> kind(StringArgumentType.getString(ctx, "kind")) == null ? 0 : give(ctx, kind(StringArgumentType.getString(ctx, "kind"))))));
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
		ctx.getSource().sendSuccess(() -> Component.literal("[Xen] Build Axe (" + kind + "): hit a block for one corner, then another for the other corner. Right-click a block to leave it out "
				+ "(saved as air; crouch to leave out all of that kind), right-click the air for the screen. Saved to config/xen/buildaxe/" + kind + ".jsonl."), false);
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
		if (box == null || box.a == null || box.b == null) return "Mark the box first: hit one corner, then the other, with the Build Axe.";
		if (!fits(box)) return "Too big (" + size(box) + "): " + tooBig() + ".";
		if (name.isEmpty() || name.length() > 64) return "Give it a name (1 to 64 letters).";
		kind = kind(kind) != null ? kind(kind) : box.kind;
		try {
			int[] cells = new int[cellsOf(box)];
			JsonObject line = capture(box, kind, name, cells);
			Path f = dir().resolve(kind + ".jsonl");
			Files.createDirectories(f.getParent());
			Files.writeString(f, json(line, cells) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
			box.out.clear();
			box.outKinds.clear();
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
	private static int cellsOf(Box b) {
		return (Math.abs(b.a.getX() - b.b.getX()) + 1) * (Math.abs(b.a.getY() - b.b.getY()) + 1) * (Math.abs(b.a.getZ() - b.b.getZ()) + 1);
	}

	/** The line: what capture made, then "blocks" written out as it is (millions of numbers: no JSON objects for each). */
	private static String json(JsonObject o, int[] cells) {
		String head = o.toString();
		StringBuilder sb = new StringBuilder(head.length() + cells.length * 3 + 16).append(head, 0, head.length() - 1).append(",\"blocks\":[");
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) sb.append(',');
			sb.append(cells[i]);
		}
		return sb.append("]}").toString();
	}

	private static JsonObject capture(Box box, String kind, String name, int[] cells) {
		ServerLevel level = box.level;
		BlockPos a = box.a, b = box.b;
		BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		int sx = hi.getX() - lo.getX() + 1, sy = hi.getY() - lo.getY() + 1, sz = hi.getZ() - lo.getZ() + 1;
		Map<String, Integer> index = new LinkedHashMap<>();
		List<Integer> sky = new ArrayList<>(), lamp = new ArrayList<>();
		int solid = 0, left = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					m.set(lo.getX() + x, lo.getY() + y, lo.getZ() + z);
					BlockState s = level.getBlockState(m);
					if (!s.isAir() && box.left(m, s)) {                                // (left out: saved as air)
						left++;
						cells[(y * sz + z) * sx + x] = index.computeIfAbsent("air", k -> index.size());
						continue;
					}
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
		f.addProperty("left_out", left);                                         // (blocks the player left out: air in "blocks")
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
		return o;                                                                // ("blocks" goes on the end: see json)
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
