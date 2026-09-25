package xen.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xen.mod.core.Brain;
import xen.mod.core.Emotions;
import xen.mod.core.Perception;
import xen.mod.talk.Chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xen Companion: a survival companion that learns, thinks, feels fear and talks, and plays fair.
 * It joins the world as a real player, knows only what it senses (everything within 6 blocks, beyond that only what
 * it sees in its 90 degree view up to 8 chunks, as beliefs that fade), acts only through a player's inputs, and
 * its chat only knows what it knows.
 */
public class XenMod implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("xen");

	XenConfig config;
	Brain brain;
	Chat chat;
	MinecraftServer server;
	final List<Companion> companions = new CopyOnWriteArrayList<>();
	private final AtomicInteger pendingTraining = new AtomicInteger();
	private volatile boolean running;
	private Thread trainer;
	private long lastSave;

	@Override
	public void onInitialize() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		config = XenConfig.load(configDir.resolve("xen.json"));
		chat = new Chat(configDir.resolve("xen").resolve(Chat.MODEL), config.chatModel, config.downloadChatModel, config.chatThreads, LOG::info);
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> commands(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(this::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::stopping);
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> heard(sender, message.signedContent()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> ownerLeft(handler.getPlayer()));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof XenPlayer xen) || !(player instanceof ServerPlayer sp) || xen.companion == null) return InteractionResult.PASS;
			if (!sp.getUUID().equals(xen.companion.owner)) return InteractionResult.PASS;
			sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, xen.getInventory(), 4),
					Component.literal(xen.companion.name + "'s bag")));
			return InteractionResult.SUCCESS;
		});
		LOG.info("Xen is ready: /xen summon");
	}

	// --------------------------------------------------------------------------------- brain
	private Path brainFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("xen").resolve("brain.bin");
	}

	private void started(MinecraftServer s) {
		server = s;
		Path file = brainFile();
		try (InputStream in = Files.exists(file) ? Files.newInputStream(file) : XenMod.class.getResourceAsStream("/assets/xen/brain.bin")) {
			if (in == null) throw new IOException("no bundled brain");
			brain = Brain.read(in);
			LOG.info("Xen's brain loaded ({} steps lived, {} lives){}", brain.steps, brain.lives, Files.exists(file) ? "" : " - pre-trained");
		} catch (IOException e) {
			LOG.warn("Starting Xen with a newborn brain: {}", e.toString());
			brain = new Brain(Perception.OBS_DIM);
		}
		running = true;
		trainer = new Thread(this::train, "xen-learning");
		trainer.setDaemon(true);
		trainer.setPriority(Thread.MIN_PRIORITY);
		trainer.start();
		lastSave = System.currentTimeMillis();
	}

	/** Learning happens here, off the server thread, so the game never waits for Xen to think. */
	private void train() {
		while (running) {
			try {
				if (pendingTraining.get() > 0) {
					pendingTraining.decrementAndGet();
					brain.trainStep();
				} else {
					Thread.sleep(50);
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable e) {
				LOG.warn("Xen learning step failed: {}", e.toString());
			}
		}
	}

	void learn(float[] obs, int action, float reward, float harm, float[] next, boolean terminal, Emotions emo, String stream) {
		brain.learn(obs, action, reward, harm, next, terminal, terminal, emo, stream, false);
		if (!config.learn || brain.steps % brain.trainEvery != 0) return;
		if (pendingTraining.get() < 8) pendingTraining.incrementAndGet();
		else if (server.tickRateManager().isSprinting()) brain.trainStep();   // sped up (/tick sprint): learning keeps pace
	}

	/** One line per life in <world>/xen/lives.csv, to see how it's doing over time. */
	void logLife(String name, long day, int ticks, float reward, String cause) {
		Path file = brainFile().resolveSibling("lives.csv");
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) Files.writeString(file, "life,day,xen,ticks,reward,cause\n");
			Files.writeString(file, String.format(Locale.ROOT, "%d,%d,%s,%d,%.2f,%s%n", brain.lives, day, name, ticks, reward, cause),
					java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warn("Could not write {}: {}", file, e.toString());
		}
	}

	private void save() {
		Path file = brainFile();
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("brain.bin.tmp");
			try (OutputStream out = Files.newOutputStream(tmp)) {
				brain.write(out);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOG.warn("Could not save Xen's brain: {}", e.toString());
		}
	}

	private void stopping(MinecraftServer s) {
		for (Companion c : new ArrayList<>(companions)) c.leave();
		running = false;
		if (trainer != null) trainer.interrupt();
		if (brain != null) save();
		chat.close();
	}

	// --------------------------------------------------------------------------------- world
	private void tick(MinecraftServer s) {
		for (Companion c : companions) {
			try {
				c.tick();
			} catch (Throwable e) {
				LOG.warn("{} stumbled: {}", c.name, e.toString(), e);
			}
		}
		if (brain != null && System.currentTimeMillis() - lastSave > config.saveMinutes * 60_000L) {
			lastSave = System.currentTimeMillis();
			save();
		}
	}

	void forget(Companion c) {
		companions.remove(c);
	}

	private void ownerLeft(ServerPlayer p) {
		if (p == null || p instanceof XenPlayer || !config.leaveWithOwner) return;
		for (Companion c : companions) if (p.getUUID().equals(c.owner)) server.execute(c::leave);
	}

	/** A player said something: if it's to a Xen (by name), it understands, does what was asked and answers. */
	private void heard(ServerPlayer sender, String text) {
		if (sender instanceof XenPlayer || !config.chat) return;
		for (Companion c : companions) {
			if (c.player() == null) continue;
			if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(c.name) + "\\b",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) continue;
			chat.ask(sender.getName().getString(), text, c.name, request -> onServer(() -> c.request(request, sender)),
					reply -> server.execute(() -> c.say(reply)));
			return;
		}
	}

	/** Run on the server thread and wait for the result (the chat thread must not touch the world itself). */
	private <T> T onServer(java.util.function.Supplier<T> job) {
		try {
			return java.util.concurrent.CompletableFuture.supplyAsync(job, server).get(10, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			LOG.warn("Xen could not act on a request: {}", e.toString());
			return null;
		}
	}

	// ------------------------------------------------------------------------------ commands
	private void commands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("xen")
				.then(Commands.literal("summon").executes(ctx -> summon(ctx, null))
						.then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
				.then(Commands.literal("spawn").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
								.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), 300))
								.then(Commands.argument("radius", IntegerArgumentType.integer(0, 30000))
										.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), IntegerArgumentType.getInteger(ctx, "radius"))))))
				.then(Commands.literal("dismiss").executes(ctx -> each(ctx, c -> { c.leave(); return c.name + " went home."; })))
				.then(Commands.literal("mode")
						.then(Commands.literal("follow").executes(ctx -> each(ctx, c -> { c.mode = Companion.Mode.FOLLOW; return c.name + " will follow you."; })))
						.then(Commands.literal("stay").executes(ctx -> each(ctx, c -> {
							c.mode = Companion.Mode.STAY;
							c.anchor = c.player().blockPosition();
							return c.name + " will stay around here.";
						})))
						.then(Commands.literal("free").executes(ctx -> each(ctx, c -> { c.mode = Companion.Mode.FREE; return c.name + " will do its own thing."; }))))
				.then(Commands.literal("status").executes(ctx -> each(ctx, Companion::status)))
				.then(Commands.literal("chat").then(Commands.literal("on").executes(ctx -> setting(ctx, "chat", true)))
						.then(Commands.literal("off").executes(ctx -> setting(ctx, "chat", false))))
				.then(Commands.literal("learn").then(Commands.literal("on").executes(ctx -> setting(ctx, "learn", true)))
						.then(Commands.literal("off").executes(ctx -> setting(ctx, "learn", false))))
				.then(Commands.literal("save").executes(ctx -> {
					save();
					ctx.getSource().sendSuccess(() -> Component.literal("Xen's brain saved (" + brain.steps + " steps lived)."), false);
					return 1;
				})));
	}

	private boolean op(CommandContext<CommandSourceStack> ctx) {
		return ctx.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** A free player name: base, base2, base3, ... (null if it gets too long). */
	private String freeName(String base) {
		String name = base;
		for (int i = 2; server.getPlayerList().getPlayerByName(name) != null; i++) name = base + i;
		return name.length() <= 16 ? name : null;
	}

	private int summon(CommandContext<CommandSourceStack> ctx, String wanted) {
		ServerPlayer owner = ctx.getSource().getPlayer();              // null from the server console
		long mine = owner == null ? 0 : companions.stream().filter(c -> owner.getUUID().equals(c.owner)).count();
		if (config.maxPerPlayer > 0 && mine >= config.maxPerPlayer && !op(ctx)) {
			ctx.getSource().sendFailure(Component.literal("You already have " + mine + " Xen (max " + config.maxPerPlayer + ")."));
			return 0;
		}
		if (wanted != null && (wanted.length() > 16 || !wanted.matches("[A-Za-z0-9_]+"))) {
			ctx.getSource().sendFailure(Component.literal("Names are up to 16 letters, digits or _."));
			return 0;
		}
		String name = freeName(wanted != null ? wanted : "Xen");
		if (name == null) {
			ctx.getSource().sendFailure(Component.literal("That name is taken."));
			return 0;
		}
		if (config.chat) chat.warmUp();
		Companion c = new Companion(this, server, name, owner == null ? null : owner.getUUID(),
				owner == null ? "nobody" : owner.getName().getString());
		if (owner != null) {
			Vec3 at = owner.position();                                // next to its owner, where there is room to stand
			for (Vec3 side : new Vec3[]{new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)}) {
				if (owner.level().noCollision(owner.getBoundingBox().move(side))) {
					at = owner.position().add(side);
					break;
				}
			}
			c.join((ServerLevel) owner.level(), at, owner.getYRot());
		} else {                                                       // from the console: at world spawn, on its own
			c.mode = Companion.Mode.FREE;
			c.join(server.overworld(), null, 0);
		}
		companions.add(c);
		String n = name;
		ctx.getSource().sendSuccess(() -> Component.literal(n + " is here. Talk to it in chat with its name (\"" + n + ", get some wood\", \""
				+ n + ", follow me\"). Right-click it for its bag. /xen status, /xen dismiss."), false);
		c.say("Hi! I'm " + n + ". I only know what I can see, so show me around!");
		return 1;
	}

	/** Admins: bring in many Xens on their own, scattered on the surface up to radius blocks around here. */
	private int spawn(CommandContext<CommandSourceStack> ctx, int count, int radius) {
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 center = ctx.getSource().getPosition();
		java.util.Random random = new java.util.Random();
		int made = 0;
		for (int i = 0; i < count; i++) {
			String name = freeName("Xen");
			if (name == null) break;
			Vec3 at = null;
			for (int tries = 0; tries < 10 && at == null; tries++) {
				double angle = random.nextDouble() * Math.PI * 2, r = radius * Math.sqrt(random.nextDouble());
				int x = (int) Math.floor(center.x + Math.cos(angle) * r), z = (int) Math.floor(center.z + Math.sin(angle) * r);
				int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
				if (y > level.getMinY() + 1 && level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) at = new Vec3(x + 0.5, y, z + 0.5);
			}
			if (at == null) continue;
			Companion c = new Companion(this, server, name, null, "nobody");
			c.mode = Companion.Mode.FREE;
			c.join(level, at, random.nextInt(4) * 90f);
			companions.add(c);
			made++;
		}
		int n = made;
		if (n > 0 && config.chat) chat.warmUp();
		ctx.getSource().sendSuccess(() -> Component.literal(n + " Xens joined within " + radius + " blocks. They share one brain. "
				+ "/xen dismiss sends them all home."), true);
		return n;
	}

	private int each(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Companion, String> f) {
		ServerPlayer p = ctx.getSource().getPlayer();
		int n = 0;
		for (Companion c : companions) {
			if (p != null && !p.getUUID().equals(c.owner) && !op(ctx)) continue;
			if (p != null && c.owner == null && !op(ctx)) continue;
			if (c.player() == null) continue;
			String msg = f.apply(c);
			ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
			n++;
		}
		if (n == 0) ctx.getSource().sendFailure(Component.literal("You have no Xen here. /xen summon"));
		return n;
	}

	private int setting(CommandContext<CommandSourceStack> ctx, String what, boolean on) {
		if (what.equals("chat")) config.chat = on;
		else config.learn = on;
		ctx.getSource().sendSuccess(() -> Component.literal("Xen " + what + ": " + (on ? "on" : "off")), false);
		return 1;
	}
}
