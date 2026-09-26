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
	/** The running mod (for the settings screen). */
	static XenMod INSTANCE;
	final Roster roster = new Roster();
	final Arena arena = new Arena(this);
	/** Where skins come from: the pack, your folder, mineskin.org, players. */
	final Skins skins = new Skins();
	final java.util.Random random = new java.util.Random();
	private long lastChatNeed, lastEvolvedDay = -1;
	/** Players who were told why Xens' answers are simple (once each). */
	private final java.util.Set<java.util.UUID> toldAboutModel = java.util.concurrent.ConcurrentHashMap.newKeySet();

	XenConfig config;
	Brain brain;
	Chat chat;
	MinecraftServer server;

	MinecraftServer server() {
		return server;
	}
	final List<Companion> companions = new CopyOnWriteArrayList<>();
	private final AtomicInteger pendingTraining = new AtomicInteger();
	private volatile boolean running;
	private Thread trainer;
	private long lastSave;

	@Override
	public void onInitialize() {
		INSTANCE = this;
		Path configDir = FabricLoader.getInstance().getConfigDir();
		config = XenConfig.load(configDir.resolve("xen.json"));
		Chat.gpuSetting = () -> config.gpu;
		skins.prepare(configDir, config.skins);
		chat = new Chat(configDir.resolve("xen").resolve(Chat.MODEL), () -> config.chatModel, () -> config.downloadChatModel,
				config.chatThreads, LOG::info);
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
	Path brainFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("xen").resolve("brain.bin");
	}

	private void started(MinecraftServer s) {
		server = s;
		roster.load(brainFile().resolveSibling("companions.json"));
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
		for (Companion c : companions) roster.remember(c, teamOf(c));
		roster.save();
	}

	private void stopping(MinecraftServer s) {
		arena.stop("the server is stopping.");
		for (Companion c : new ArrayList<>(companions)) c.leave();
		running = false;
		if (trainer != null) trainer.interrupt();
		if (brain != null) save();
		chat.close();
	}

	// --------------------------------------------------------------------------------- world
	private void tick(MinecraftServer s) {
		arena.tick();
		List<Companion> order = new ArrayList<>(companions);
		java.util.Collections.shuffle(order, random);                  // nobody always gets to act first
		for (Companion c : order) {
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
		if (s.getTickCount() % 100 == 0) {
			wakeChat();
			if (config.evolution) evolve();
		}
	}

	/**
	 * The chat model only runs when it may be needed: someone a Xen knows is near it (or someone just talked). With
	 * nobody around for a while it's unloaded again, and Xen writes on signs instead.
	 */
	private void wakeChat() {
		long now = System.currentTimeMillis();
		for (Companion c : companions) {
			if (c.player() != null && c.someoneListening(config.chatWakeDistance)) {
				lastChatNeed = now;
				break;
			}
		}
		if (config.chat && now - lastChatNeed < 5000) chat.warmUp();
		else if (chat.hasModel() && now - lastChatNeed > config.chatIdleMinutes * 60_000L) chat.sleep();
	}

	/**
	 * Evolution. Every few days the Xens without an owner are ranked by how they did this generation (what they
	 * gathered, days they lived, deaths). The worst quarter leave, and children of the best half take their places:
	 * each gene from one of two parents, with a small mutation. They all keep one shared brain; what evolves is
	 * their nature. Logged in {@code <world>/xen/evolution.csv}.
	 */
	void evolve() {
		long day = server.overworld().getGameTime() / 24000;
		if (lastEvolvedDay < 0) lastEvolvedDay = day;
		if (day - lastEvolvedDay < Math.max(1, config.generationDays)) return;
		lastEvolvedDay = day;
		List<Companion> pool = new ArrayList<>();
		for (Companion c : companions) if (c.owner == null && c.player() != null && !c.inArena) pool.add(c);
		if (pool.size() < 4) return;
		java.util.function.ToDoubleFunction<Companion> fitness = c -> c.genReward + 2.0 * c.genTicks / 24000.0 - 5.0 * c.genDeaths;
		pool.sort(java.util.Comparator.comparingDouble(fitness).reversed());
		int replace = Math.max(1, pool.size() / 4), gen = 0;
		List<Companion> parents = pool.subList(0, pool.size() / 2);
		StringBuilder gone = new StringBuilder(), born = new StringBuilder(), styles = new StringBuilder();
		double best = fitness.applyAsDouble(pool.get(0));
		for (int i = 0; i < replace; i++) {
			Companion loser = pool.get(pool.size() - 1 - i);
			Companion a = parents.get(random.nextInt(parents.size())), b = parents.get(random.nextInt(parents.size()));
			Personality nature = a.personality.child(b.personality, a.name + "+" + b.name, random);
			gen = Math.max(gen, nature.generation);
			Vec3 at = a.player().position();
			ServerLevel level = (ServerLevel) a.player().level();
			gone.append(gone.length() > 0 ? " " : "").append(loser.name);
			loser.leave();
			Companion child = create(null, null, nature);
			child.mode = Companion.Mode.FREE;
			child.join(level, at, random.nextInt(4) * 90f);
			companions.add(child);
			born.append(born.length() > 0 ? " " : "").append(child.name);
			styles.append(styles.length() > 0 ? ", " : "").append(child.name).append(": ").append(nature.style());
		}
		double[] mean = new double[4];
		for (Companion c : companions) {
			if (c.owner != null || c.inArena) continue;
			mean[0] += c.personality.bravery;
			mean[1] += c.personality.curiosity;
			mean[2] += c.personality.chattiness;
			mean[3] += c.personality.diligence;
			c.genReward = 0;
			c.genTicks = 0;
			c.genDeaths = 0;
		}
		int n = (int) companions.stream().filter(c -> c.owner == null && !c.inArena).count();
		Path file = brainFile().resolveSibling("evolution.csv");
		try {
			if (!Files.exists(file)) Files.writeString(file, "day,generation,xens,best_fitness,bravery,curiosity,chattiness,diligence,gone,born\n");
			Files.writeString(file, String.format(Locale.ROOT, "%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%s,%s%n", day, gen, n, best,
					mean[0] / n, mean[1] / n, mean[2] / n, mean[3] / n, gone, born), java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.warn("Could not write {}: {}", file, e.toString());
		}
		LOG.info("Xen evolution, day {}: {} left, {} born (generation {}; {})", day, gone, born, gen, styles);
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
		lastChatNeed = System.currentTimeMillis();                     // someone is talking: wake the chat model up
		chat.warmUp();
		boolean toXen = false;
		for (Companion c : companions) {
			if (c.player() != null && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(c.name) + "\\b",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) toXen = true;
		}
		if (toXen && !chat.hasModel() && toldAboutModel.add(sender.getUUID())) {   // once: why its answers are simple
			sender.sendSystemMessage(Component.literal("[Xen] The chat model is " + chat.status()
					+ ". Until it's ready, Xens understand requests and answer simply.").withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		for (Companion c : companions) {
			if (c.player() == null) continue;
			if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(c.name) + "\\b",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) continue;
			chat.ask(sender.getName().getString(), text, c.name, request -> onServer(() -> c.request(request, sender, text)),
					reply -> server.execute(() -> c.say(reply)));
			return;
		}
		for (Companion c : companions) {                                 // "when hears ..." rules: anything said close by
			if (c.player() != null && c.player().level() == sender.level() && c.player().distanceTo(sender) <= 16) c.script.heard(text, sender);
		}
		// No name: an answer to a Xen close by that asked this player something, or is in a trade with them ("yes", "deal").
		Companion nearest = null;
		for (Companion c : companions) {
			if (c.player() == null || c.player().level() != sender.level() || c.player().distanceTo(sender) > 16) continue;
			if (!c.talker.waitingFor(sender.getUUID()) && !c.trader.dealWith(sender.getUUID())) continue;
			if (nearest == null || c.player().distanceTo(sender) < nearest.player().distanceTo(sender)) nearest = c;
		}
		if (nearest == null) nearest = addressedWithoutName(sender);
		if (nearest != null) {
			Companion c = nearest;
			chat.ask(sender.getName().getString(), text, c.name, request -> onServer(() -> c.request(request, sender, text)),
					reply -> server.execute(() -> c.say(reply)));
		}
	}

	/**
	 * No name, but it's clear who they're talking to, the way players can tell: a Xen they're in a conversation with
	 * (it answered them in the last half minute), one they're looking right at, or their own Xen when it's just the
	 * two of them. Not when another player is closer and they aren't looking at the Xen (then they're talking to them).
	 */
	private Companion addressedWithoutName(ServerPlayer sender) {
		long now = sender.level().getGameTime();
		Companion best = null;
		double bestScore = Double.MAX_VALUE;
		for (Companion c : companions) {
			ServerPlayer x = c.player();
			if (x == null || x.level() != sender.level()) continue;
			double d = x.distanceTo(sender);
			if (d > 12) continue;
			boolean talking = sender.getUUID().equals(c.talkingWith) && now < c.talkingUntil;
			boolean looking = looksAt(sender, x);
			boolean justUs = sender.getUUID().equals(c.owner) && d <= 8 && nobodyElseNear(sender, x);
			if (!talking && !looking && !justUs) continue;
			if (!looking && otherPlayerCloser(sender, x)) continue;
			double score = d - (talking ? 6 : 0) - (looking ? 4 : 0);
			if (score < bestScore) {
				bestScore = score;
				best = c;
			}
		}
		return best;
	}

	/** Is the player looking right at it (within about 15 degrees)? */
	private static boolean looksAt(ServerPlayer who, ServerPlayer at) {
		net.minecraft.world.phys.Vec3 look = who.getViewVector(1f), to = at.getEyePosition().subtract(who.getEyePosition());
		return to.length() > 0.1 && look.dot(to.normalize()) > 0.966 && who.hasLineOfSight(at);
	}

	/** No other real player and no other Xen within 16 blocks of the two of them. */
	private boolean nobodyElseNear(ServerPlayer sender, ServerPlayer xen) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == sender || p == xen || p.level() != sender.level()) continue;
			if (p.distanceTo(sender) <= 16) return false;
		}
		return true;
	}

	private boolean otherPlayerCloser(ServerPlayer sender, ServerPlayer xen) {
		double d = xen.distanceTo(sender);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == sender || p == xen || p instanceof XenPlayer || p.level() != sender.level()) continue;
			if (p.distanceTo(sender) < d) return true;
		}
		return false;
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
				.then(Commands.literal("settings").executes(this::showSettings))
				.then(Commands.literal("set").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.argument("setting", StringArgumentType.word()).suggests((ctx, b) -> {
									for (var f : XenConfig.class.getFields()) b.suggest(f.getName());
									return b.buildFuture();
								})
								.then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx ->
										set(ctx, StringArgumentType.getString(ctx, "setting"), StringArgumentType.getString(ctx, "value"))))))
				.then(Commands.literal("build").then(Commands.argument("what", StringArgumentType.greedyString()).suggests((ctx, b) -> {
									b.suggest("house");
									b.suggest("base");
									return b.buildFuture();
								}).executes(ctx -> each(ctx, c -> c.name + ": " + xen.mod.talk.Chat.plainly("Plan: "
										+ c.builder.start(StringArgumentType.getString(ctx, "what")), "")))))
				.then(Commands.literal("style")
						.then(Commands.argument("xen", StringArgumentType.word()).suggests((ctx, b) -> {
									for (Companion c : companions) b.suggest(c.name);
									return b.buildFuture();
								})
								.then(Commands.argument("trait", StringArgumentType.word()).suggests((ctx, b) -> {
											for (String t : TRAITS) b.suggest(t);
											return b.buildFuture();
										})
										.then(Commands.argument("value", StringArgumentType.word()).suggests((ctx, b) -> {
													String[] values = switch (StringArgumentType.getString(ctx, "trait")) {
														case "fight" -> Personality.FIGHTS;
														case "build" -> Personality.BUILDS;
														case "material" -> Personality.MATERIALS;
														case "tone" -> Personality.TONES;
														default -> new String[] {"0", "0.5", "1"};
													};
													for (String v : values) b.suggest(v);
													return b.buildFuture();
												})
												.executes(ctx -> style(ctx, StringArgumentType.getString(ctx, "xen"),
														StringArgumentType.getString(ctx, "trait"), StringArgumentType.getString(ctx, "value")))))))
				.then(Commands.literal("arena").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.literal("start").executes(ctx -> arenaStart(ctx, 4, 10, "sword"))
								.then(Commands.argument("xens", IntegerArgumentType.integer(1, 16))
										.executes(ctx -> arenaStart(ctx, IntegerArgumentType.getInteger(ctx, "xens"), 10, "sword"))
										.then(Commands.argument("generations", IntegerArgumentType.integer(1, 10000))
												.executes(ctx -> arenaStart(ctx, IntegerArgumentType.getInteger(ctx, "xens"),
														IntegerArgumentType.getInteger(ctx, "generations"), "sword"))
												.then(Commands.argument("kit", StringArgumentType.word()).suggests((ctx, b) -> {
															for (String k : Arena.KITS) b.suggest(k);
															return b.buildFuture();
														})
														.executes(ctx -> arenaStart(ctx, IntegerArgumentType.getInteger(ctx, "xens"),
																IntegerArgumentType.getInteger(ctx, "generations"), StringArgumentType.getString(ctx, "kit")))))))
						.then(Commands.literal("stop").executes(ctx -> {
							arena.stop("stopped.");
							return 1;
						}))
						.then(Commands.literal("status").executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.literal(arena.status()), false);
							return 1;
						})))
				.then(Commands.literal("save").executes(ctx -> {
					save();
					ctx.getSource().sendSuccess(() -> Component.literal("Xen's brain saved (" + brain.steps + " steps lived)."), false);
					return 1;
				})));
	}

	/** /xen arena start: red against blue, training their fighting against each other. */
	private int arenaStart(CommandContext<CommandSourceStack> ctx, int perTeam, int generations, String kit) {
		if (!java.util.Arrays.asList(Arena.KITS).contains(kit)) {
			ctx.getSource().sendFailure(Component.literal("Kits: " + String.join(", ", Arena.KITS)));
			return 0;
		}
		String result = arena.start(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), perTeam, generations, kit);
		ctx.getSource().sendSuccess(() -> Component.literal(result), true);
		return arena.running() ? 1 : 0;
	}

	private static final String[] TRAITS = {"fight", "build", "material", "tone", "bravery", "curiosity", "chattiness", "diligence", "crit", "charge",
			"spacing", "wtap", "jumpreset", "strafe", "counter", "select", "retreat", "shield"};

	/** /xen style: set one of a Xen's traits by hand (its owner, or an operator). */
	private int style(CommandContext<CommandSourceStack> ctx, String who, String trait, String value) {
		ServerPlayer p = ctx.getSource().getPlayer();
		for (Companion c : companions) {
			if (!c.name.equalsIgnoreCase(who)) continue;
			if (p != null && !p.getUUID().equals(c.owner) && !op(ctx)) {
				ctx.getSource().sendFailure(Component.literal(c.name + " isn't yours."));
				return 0;
			}
			String error = c.personality.set(trait, value);
			if (error != null) {
				ctx.getSource().sendFailure(Component.literal(error));
				return 0;
			}
			c.applyPersonality();
			roster.remember(c, teamOf(c));
			roster.save();
			ctx.getSource().sendSuccess(() -> Component.literal(c.name + ": " + c.personality.describe() + "; " + c.personality.style() + "."), false);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("No Xen called " + who + " here."));
		return 0;
	}

	private boolean op(CommandContext<CommandSourceStack> ctx) {
		return ctx.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	// ------------------------------------------------------------------- who they are
	private java.util.Set<String> takenNames() {
		java.util.Set<String> taken = new java.util.HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) taken.add(p.getName().getString().toLowerCase(Locale.ROOT));
		return taken;
	}

	/** A Xen: the same one again if it has been here before (by name), otherwise new, with a name, nature and skin. */
	Companion create(String wanted, ServerPlayer owner, Personality nature) {
		java.util.Set<String> taken = takenNames();
		if (wanted == null) taken.addAll(roster.names());                 // a new Xen gets a new name
		Personality born = nature != null ? nature : config.personalities ? Personality.random(random) : Personality.plain();
		String name = wanted;
		if (name == null && config.randomNames) name = Names.fresh(config.nameStyle, born.tone, taken, random);   // fits its nature
		if (name == null) name = Looks.freshName(false, taken, random);
		com.google.gson.JsonObject known = roster.get(name);
		Personality p = nature != null ? nature
				: known != null && known.has("personality") ? Personality.fromJson(known.getAsJsonObject("personality")) : born;
		if (nature == null && known == null && config.personalities) {           // born with an arena champion's fighting
			float[] champion = Arena.championGenes(brainFile().getParent(), random);
			if (champion != null) {
				p.fightGenes = champion;
				p.fight = Personality.nearest(champion);
			}
		}
		String skin = known != null && known.has("skin") && nature == null ? known.get("skin").getAsString() : skins.pick(config.skins, random);
		Companion c = new Companion(this, server, name, owner == null ? null : owner.getUUID(),
				owner == null ? "nobody" : owner.getName().getString(), p, skin);
		if (known != null && known.has("known")) {
			for (var u : known.getAsJsonArray("known")) c.known.add(java.util.UUID.fromString(u.getAsString()));
		}
		if (known != null) {
			c.goals.load(known.has("likes") ? known.getAsJsonObject("likes") : null, known.has("goals") ? known.getAsJsonObject("goals") : null);
			if (known.has("skills")) c.mimic.load(known.getAsJsonObject("skills"));
			if (known.has("memories")) for (var m : known.getAsJsonArray("memories")) c.memories.add(m.getAsString());
			if (known.has("trust")) {
				for (var e : known.getAsJsonObject("trust").entrySet()) c.trust.put(java.util.UUID.fromString(e.getKey()), e.getValue().getAsFloat());
			}
		}
		return c;
	}

	private static final String[] TEAM_COLORS = {"red", "blue", "green", "yellow", "purple", "aqua"};
	private static final net.minecraft.ChatFormatting[] TEAM_FORMATS = {net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BLUE,
			net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.LIGHT_PURPLE,
			net.minecraft.ChatFormatting.AQUA};

	String teamOf(Companion c) {
		var team = server.getScoreboard().getPlayersTeam(c.name);
		return team != null && team.getName().startsWith("xen") ? team.getName() : null;
	}

	/** Put a Xen on its team (one team for all, or the smallest of several, keeping the team it had). */
	void joinTeam(Companion c) {
		var board = server.getScoreboard();
		if (c.arenaTeam != null) {                                     // the arena's red and blue teams
			String name = "xen_" + c.arenaTeam;
			var team = board.getPlayerTeam(name);
			if (team == null) {
				team = board.addPlayerTeam(name);
				Compat.teamColor(team, c.arenaTeam.equals("red") ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.BLUE);
				team.setAllowFriendlyFire(false);
			}
			board.addPlayerToTeam(c.name, team);
			return;
		}
		int n = Math.min(Math.max(config.teams, 0), TEAM_COLORS.length);
		if (n == 0) {
			if (teamOf(c) != null) board.removePlayerFromTeam(c.name);
			return;
		}
		String name;
		if (n == 1) {
			name = "xen";
		} else {
			var known = roster.get(c.name);
			String had = known != null && known.has("team") ? known.get("team").getAsString() : null;
			int index = had == null ? -1 : java.util.Arrays.asList(TEAM_COLORS).indexOf(had.replace("xen_", ""));
			if (index < 0 || index >= n) {
				int[] size = new int[n];
				for (Companion o : companions) {
					String t = o == c ? null : teamOf(o);
					int i = t == null ? -1 : java.util.Arrays.asList(TEAM_COLORS).indexOf(t.replace("xen_", ""));
					if (i >= 0 && i < n) size[i]++;
				}
				index = 0;
				for (int i = 1; i < n; i++) if (size[i] < size[index]) index = i;
			}
			name = "xen_" + TEAM_COLORS[index];
		}
		var team = board.getPlayerTeam(name);
		if (team == null) {
			team = board.addPlayerTeam(name);
			Compat.teamColor(team, n == 1 ? net.minecraft.ChatFormatting.AQUA : TEAM_FORMATS[java.util.Arrays.asList(TEAM_COLORS).indexOf(name.replace("xen_", ""))]);
			team.setAllowFriendlyFire(false);
		}
		board.addPlayerToTeam(c.name, team);
		roster.remember(c, name);
	}

	private boolean full() {
		return config.maxXens > 0 && companions.size() >= config.maxXens;
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
		if (wanted != null && server.getPlayerList().getPlayerByName(wanted) != null) {
			ctx.getSource().sendFailure(Component.literal(wanted + " is already here."));
			return 0;
		}
		if (full()) {
			ctx.getSource().sendFailure(Component.literal("The world already has " + companions.size() + " Xens (max " + config.maxXens + ")."));
			return 0;
		}
		Companion c = create(wanted, owner, null);
		String name = c.name;
		if (owner != null) {
			Vec3 at = owner.position();                                // next to its owner, where there is room to stand
			for (Vec3 side : new Vec3[]{new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)}) {
				if (owner.level().noCollision(owner.getBoundingBox().move(side))) {
					at = owner.position().add(side);
					break;
				}
			}
			c.join((ServerLevel) owner.level(), at, owner.getYRot());
		} else {                                                       // from the console: on the ground at world spawn, on its own
			c.mode = Companion.Mode.FREE;
			Vec3 spawn = ctx.getSource().getPosition();
			c.join(server.overworld(), surface(server.overworld(), (int) Math.floor(spawn.x), (int) Math.floor(spawn.z)), 0);
		}
		companions.add(c);
		String n = name;
		ctx.getSource().sendSuccess(() -> Component.literal(n + " is here (" + c.personality.describe() + "; " + c.personality.style() + "). Talk to it in chat with its name (\""
				+ n + ", get some wood\", \"" + n + ", follow me\"). Right-click it for its bag. /xen status, /xen dismiss."), false);
		c.say("Hi! I'm " + n + ". I only know what I can see, so show me around!");
		return 1;
	}

	/** The top of the ground at x, z (null over water or lava). */
	static Vec3 surface(ServerLevel level, int x, int z) {
		int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
		if (y <= level.getMinY() + 1 || !level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) return null;
		return new Vec3(x + 0.5, y, z + 0.5);
	}

	/** Admins: bring in many Xens on their own, scattered on the surface up to radius blocks around here. */
	private int spawn(CommandContext<CommandSourceStack> ctx, int count, int radius) {
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 center = ctx.getSource().getPosition();
		java.util.Random random = new java.util.Random();
		int made = 0;
		for (int i = 0; i < count && !full(); i++) {
			Vec3 at = null;
			for (int tries = 0; tries < 10 && at == null; tries++) {
				double angle = random.nextDouble() * Math.PI * 2, r = radius * Math.sqrt(random.nextDouble());
				int x = (int) Math.floor(center.x + Math.cos(angle) * r), z = (int) Math.floor(center.z + Math.sin(angle) * r);
				at = surface(level, x, z);
			}
			if (at == null) continue;
			try {                                                        // one that can't join doesn't stop the rest
				Companion c = create(null, null, null);
				c.mode = Companion.Mode.FREE;
				c.join(level, at, random.nextInt(4) * 90f);
				companions.add(c);
				made++;
			} catch (RuntimeException e) {
				LOG.warn("A Xen could not join at {}", at, e);
			}
		}
		int n = made;
		ctx.getSource().sendSuccess(() -> Component.literal(n + " Xens joined within " + radius + " blocks. They share one brain. "
				+ "/xen dismiss sends them all home."), true);
		return n;
	}

	private int showSettings(CommandContext<CommandSourceStack> ctx) {
		StringBuilder sb = new StringBuilder("Xen settings (the chat model runs on the " + chat.runsOn + "):");
		for (var f : XenConfig.class.getFields()) {
			try {
				sb.append("\n  ").append(f.getName()).append(" = ").append(f.get(config));
			} catch (IllegalAccessException ignored) {
			}
		}
		ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	private int set(CommandContext<CommandSourceStack> ctx, String key, String value) {
		String error = config.set(key, value);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		config.save();
		applySettings();
		ctx.getSource().sendSuccess(() -> Component.literal("Xen: " + key + " = " + value), true);
		return 1;
	}

	/** After settings change (command or settings screen): teams, and the chat model. */
	void applySettings() {
		skins.prepare(FabricLoader.getInstance().getConfigDir(), config.skins);
		if (server == null) return;
		server.execute(() -> {
			for (Companion c : companions) if (c.player() != null) joinTeam(c);
			if (!config.chat || config.chatModel.equalsIgnoreCase("off")) chat.sleep();
		});
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
