package xen.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
import java.util.Random;
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
	/** The world's lore: what happened on it (see {@link Lore}). */
	final Lore lore = new Lore();
	/** What grows when they live together: constitutions and taxes they vote on, opinions, faiths (see {@link Society}). */
	final Society society = new Society(this);
	/** (Experiment) Spawn as Xen: a Xen plays your character (see {@link Avatar}). */
	final Avatar avatar = new Avatar(this);
	final Arena arena = new Arena(this);
	/** Where skins come from: the pack, your folder, mineskin.org, players. */
	final Skins skins = new Skins();
	final java.util.Random random = new java.util.Random();
	private long lastEvolvedDay = -1;
	/** Players who were told why Xens' answers are simple (once each). */

	XenConfig config;
	Brain brain;
	/** Xen Ex1, shared by every Xen (each one's hurts teach it, for all of them). */
	xen.mod.core.Ex1 ex1;
	/** Xen Glance: what its eyes' picture looks like at a glance (a monster close, a drop...), or null. */
	xen.mod.core.Glance glance;
	/** Xen 2.0's mind (what to do next: one for all main Xens, it keeps learning); null: the old way. */
	xen.mod.core.Mind mind;
	/** How many of its choices Xen 2.0 has seen turn out (in this world), and learned from. */
	long mindExperiences;
	private long mindTrained;
	/** Some ordinary moments, and how big the shipped mind's values are on them (a mind far beyond that has drifted). */
	private float[][] probe;
	private float mindScale;

	/** The mind that ships with the mod (trained in SimLife), fresh; null if it can't be read. */
	private static xen.mod.core.Mind shippedMind() {
		try (InputStream in = XenMod.class.getResourceAsStream("/assets/xen/mind.bin")) {
			return in == null ? null : xen.mod.core.Mind.load(new java.io.DataInputStream(new java.io.BufferedInputStream(in)));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
	private final Random mindRandom = new Random();
	Chat chat;
	MinecraftServer server;
	/** Gameplay logs (the gameplayLog setting), for training Xen Ex1. */
	GameplayLog gameplayLog;
	Duels duels;

	MinecraftServer server() {
		return server;
	}
	final List<Companion> companions = new CopyOnWriteArrayList<>();
	/** The tribes Xens live in (see {@link Tribe}), by key. */
	final java.util.Map<String, Tribe> tribes = Tribe.newMap();
	/** Lines Xens said lately (any of them): so they don't all say the same thing over and over. */
	final java.util.Map<String, Long> saidLately = new java.util.HashMap<>();
	/** When a Xen last made a remark on its own (they take turns, a few seconds apart, not all at once). */
	volatile long lastRemarkAt;
	private final AtomicInteger pendingTraining = new AtomicInteger();
	private volatile boolean running;
	private Thread trainer;
	private long lastSave;

	@Override
	public void onInitialize() {
		INSTANCE = this;
		Path configDir = FabricLoader.getInstance().getConfigDir();
		config = XenConfig.load(configDir.resolve("xen.json"));
		skins.prepare(configDir, config.skins);
		if (config.realSkins) skins.keepLooking(8, java.util.Set.of());   // (real players' skins in before the first Xen comes)
		if (config.realNameStyle()) skins.prepareReal(Skins.realNames(configDir));   // (their skins ready before anyone summons a Xen)
		solverMind.load(configDir.resolve("xen"));
		btbLearner.load(configDir.resolve("xen"));
		journal = new Journal(configDir.resolve("xen").resolve("logs"));
		gameplayLog = new GameplayLog(this, configDir.resolve("xen").resolve("gameplay_logs"));
		duels = new Duels(configDir.resolve("xen").resolve("duels"));
		try {
			xen.mod.talk.Voice.init(configDir.resolve("xen"));               // its own words: the reply network (taught once, then it learns), the word library
			xen.mod.talk.Banter.init(configDir.resolve("xen"));              // its talk model: what kind of line players say, and the answers (and which land)
		} catch (RuntimeException e) {
			LOG.warn("Xen's word engine couldn't start: {}", e.toString());
		}
		chat = new Chat(LOG::info);
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> commands(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(this::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::stopping);
		ServerTickEvents.END_SERVER_TICK.register(this::tick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> heard(sender, message.signedContent()));
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {   // local chat: only those close by hear it
			if (!config.localChat || sender instanceof XenPlayer) return true;
			var out = net.minecraft.network.chat.OutgoingChatMessage.create(message);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p instanceof XenPlayer || p != sender && !inRange(p, sender)) continue;
				p.sendChatMessage(out, sender.shouldFilterMessageTo(p), params);
			}
			LOG.info("<{}> {} (local chat)", sender.getName().getString(), message.signedContent());
			heard(sender, message.signedContent());                               // (the Xens close enough hear it)
			return false;
		});
		ServerMessageEvents.ALLOW_GAME_MESSAGE.register((srv, message, overlay) ->   // a Xen respawning isn't "joining"
				!(quietJoin && message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().startsWith("multiplayer.player.joined")));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer victim && server != null) {
				gameplayLog.quietly(() -> gameplayLog.died(victim, source));
				died(victim, source);
			}
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {   // (gameplay logs: what hurt whom)
			if (entity instanceof ServerPlayer && !blocked) gameplayLog.quietly(() -> gameplayLog.damaged(entity, source, taken));
		});
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			gameplayLog.quietly(() -> gameplayLog.broke(player, pos, state));
			watchedBreak(player, pos, state);
			if (player instanceof XenPlayer xp && xp.companion != null) xp.companion.broke(pos, state.is(net.minecraft.tags.BlockTags.LOGS));   // (what it mines, it picks up)
		});
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide()) gameplayLog.quietly(() -> gameplayLog.swing(player, entity));
			return InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
			if (!level.isClientSide()) gameplayLog.quietly(() -> gameplayLog.digging(player, pos));
			return InteractionResult.PASS;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			Tour.left(handler.getPlayer());                                  // (mid-tour: back to how they were)
			ownerLeft(handler.getPlayer());
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> ownerJoined(handler.getPlayer()));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof XenPlayer xen) || !(player instanceof ServerPlayer sp) || xen.companion == null) return InteractionResult.PASS;
			if (!sp.getUUID().equals(xen.companion.owner)) return InteractionResult.PASS;
			sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, xen.getInventory(), 4),
					Component.literal(xen.companion.name + "'s bag")));
			return InteractionResult.SUCCESS;
		});
		BuildAxe.register();                                                    // (a developer's tool: boxes of the world into training data)
		LOG.info("Xen is ready: /xen summon");
	}

	// --------------------------------------------------------------------------------- brain
	/** Keep a file it won't use any more next to it (name.old), in case someone wants it back. */
	private static void keepOld(Path file) {
		try {
			Files.move(file, file.resolveSibling(file.getFileName() + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.warn("Couldn't keep {} aside: {}", file, e.toString());
		}
	}

	Path brainFile() {
		return server.getWorldPath(LevelResource.ROOT).resolve("xen").resolve("brain.bin");
	}

	private void started(MinecraftServer s) {
		server = s;
		try {                                                                   // Xen's words: every creature, item and block of this game is a noun it knows
			xen.mod.talk.Words.load();
			for (var t : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
				var cat = t.getCategory();
				xen.mod.talk.Words.noun(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath(),
						cat == net.minecraft.world.entity.MobCategory.MONSTER ? "mob" : cat == net.minecraft.world.entity.MobCategory.MISC ? "thing" : "animal");
			}
			for (var i : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath();
				xen.mod.talk.Words.noun(n, Facts.fills(n) > 0 ? "food" : "thing");
			}
			xen.mod.talk.Words.loadLearned(s.getWorldPath(LevelResource.ROOT).resolve("xen").resolve("words-learned.json"));
			LOG.info("Xen's words: {} it knows ({} learned in this world)", xen.mod.talk.Words.vocabularySize(), xen.mod.talk.Words.learnedCount());
		} catch (RuntimeException e) {
			LOG.warn("Xen's words couldn't take in the game's names: {}", e.toString());
		}
		roster.load(brainFile().resolveSibling("companions.json"));
		lore.load(brainFile().resolveSibling("lore.json"));
		society.load(brainFile().resolveSibling("society.json"));
		Rules.load(brainFile().resolveSibling("rules.tsv"));                  // the rules each Xen wrote itself
		Focus.load(brainFile().resolveSibling("focus.tsv"));                  // what Focus learned of which goals are worth going back to
		Planner.load(brainFile().resolveSibling("planner.tsv"));              // how often each kind of aim came to something
		Vision.loadTuning(FabricLoader.getInstance().getConfigDir().resolve("xen").resolve("vision.txt"));   // (its shading, tuned from recordings)
		// A world from an older version keeps what its Xens learned; anything it can't read (a brain of another shape, a
		// damaged file) is kept aside (.old) and the one that ships with the mod takes its place: old worlds just upgrade.
		Path file = brainFile();
		brain = null;
		if (Files.exists(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				brain = Brain.read(in);
				if (brain.obsDim != Perception.OBS_DIM) throw new IOException("made for another version (" + brain.obsDim + " senses, now " + Perception.OBS_DIM + ")");
				LOG.info("Xen's brain loaded ({} steps lived, {} lives)", brain.steps, brain.lives);
			} catch (IOException | RuntimeException e) {
				LOG.warn("Xen's brain in this world couldn't be read ({}): kept as brain.bin.old, starting from the one that ships", e.toString());
				keepOld(file);
				brain = null;
			}
		}
		if (brain == null) {
			try (InputStream in = XenMod.class.getResourceAsStream("/assets/xen/brain.bin")) {
				if (in == null) throw new IOException("no bundled brain");
				brain = Brain.read(in);
				LOG.info("Xen's brain loaded ({} steps lived, {} lives) - pre-trained", brain.steps, brain.lives);
			} catch (IOException | RuntimeException e) {
				LOG.warn("Starting Xen with a newborn brain: {}", e.toString());
				brain = new Brain(Perception.OBS_DIM);
			}
		}
		ex1 = xen.mod.core.Ex1.bundled();                                       // Xen Ex1: trained on recorded play
		glance = xen.mod.core.Glance.load();                                   // Xen Glance: the picture network
		if (glance != null) LOG.info("Xen Glance loaded (trained on {} pictures)", glance.trainedOn);
		if (ex1 != null) {
			Path learned = brainFile().resolveSibling("ex1-learned.json");
			try {
				if (Files.exists(learned) && !ex1.restore(new com.google.gson.Gson().fromJson(Files.readString(learned), com.google.gson.JsonObject.class))) {
					LOG.info("What Xen Ex1 learned in this world was for an older Ex1: kept as ex1-learned.json.old, the new one starts fresh");
					keepOld(learned);
				}
			} catch (IOException | RuntimeException e) {
				LOG.warn("What Xen Ex1 learned in this world couldn't be read ({}): starting from the one that ships", e.toString());
			}
			LOG.info("Xen Ex1 loaded (learned from {} hurts and {} calm moments in this world)", ex1.learnedHurts, ex1.learnedCalm);
		} else {
			LOG.warn("No Xen Ex1: Xens move the old way");
		}
		Path mindFile = brainFile().resolveSibling("mind.bin");
		probe = xen.mod.core.SimLife.probe(64, 17);
		xen.mod.core.Mind shipped = null, own = null;
		try (InputStream in = XenMod.class.getResourceAsStream("/assets/xen/mind.bin")) {
			if (in != null) shipped = xen.mod.core.Mind.load(new java.io.DataInputStream(new java.io.BufferedInputStream(in)));
		} catch (IOException | RuntimeException e) {
			LOG.warn("The bundled mind couldn't be read: {}", e.toString());
		}
		if (Files.exists(mindFile)) {
			try (InputStream in = Files.newInputStream(mindFile)) {
				own = xen.mod.core.Mind.load(new java.io.DataInputStream(new java.io.BufferedInputStream(in)));
			} catch (IOException | RuntimeException e) {
				own = null;
			}
			if (own == null || own.carried && (shipped == null || !shipped.carried) || shipped != null && own.updates < shipped.updates) {   // unreadable, an older version, or less trained than the one that ships
				LOG.info("Upgrading this world's Xen mind to the one that ships (the old one is kept as mind.bin.old)");
				keepOld(mindFile);
				own = null;
			}
		}
		if (own != null && shipped != null && own.scale(probe) > 3 * shipped.scale(probe) + 20) {   // its values ran away while it played: broken
			LOG.info("This world's Xen mind had drifted (its values {} against {}): back to the one that ships (the old one is kept as mind.bin.old)",
					(int) own.scale(probe), (int) shipped.scale(probe));
			keepOld(mindFile);
			own = null;
		}
		mind = own != null ? own : shipped;
		if (mind != null) {
			mind.minMemory = 400;                                                // (learns from a fair few choices, not the last handful)
			mindScale = shipped != null ? shipped.scale(probe) : mind.scale(probe);
		}
		if (mind != null) LOG.info("{}'s mind loaded ({} learning steps){}", xen.mod.core.Mind.VERSION, mind.updates, own != null ? "" : " - trained in SimLife");
		else LOG.warn("No Xen 2.0 mind: Xens choose the old way");
		loadStrategies();
		loadAway();
		deathMessages();
		later(60, () -> comeBack(o -> !o.has("owner")));                          // the free ones: back when the world is up
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
				} else if (mind != null && config.learn && mindTrained < mindExperiences * 2) {   // Xen 2.0: 2 lessons per choice it saw through
					mindTrained++;
					mind.learn(32, mindRandom);
					if (mindTrained % 200 == 0 && mind.scale(probe) > 3 * mindScale + 20) {   // its values running away: back to what it knew
						LOG.warn("Xen's mind drifted while learning (its values {} against {}): back to the one that ships", (int) mind.scale(probe), (int) mindScale);
						xen.mod.core.Mind fresh = shippedMind();
						if (fresh != null) {
							fresh.minMemory = 400;
							mind = fresh;
						}
					}
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

	/** What all Xens have learned about getting unstuck (the solver). */
	final Solver.Mind solverMind = new Solver.Mind();
	/** What the Back to Basics brain learned (shared by every Xen, kept in config/xen/btb.json). */
	final BtbLearner btbLearner = new BtbLearner();
	/** What Xens see, think, say and hear (for the Experimental tab's log). */
	public Journal journal;

	/** What every Xen's plans led to (see {@link xen.mod.core.Strategy.Book}): which plan works for which nature. */
	final xen.mod.core.Strategy.Book strategies = new xen.mod.core.Strategy.Book();

	private void loadStrategies() {
		Path f = brainFile().resolveSibling("strategies.txt");
		try {
			if (Files.exists(f)) strategies.load(Files.readString(f));
		} catch (IOException | RuntimeException e) {
			LOG.warn("Xen's plan book couldn't be read: {}", e.toString());
		}
	}

	private void save() {
		solverMind.save();
		btbLearner.save();
		try {
			xen.mod.talk.Words.saveLearned(brainFile().resolveSibling("words-learned.json"));
		} catch (IOException e) {
			LOG.warn("Couldn't keep the words Xens learned: {}", e.toString());
		}
		try {
			Path f = brainFile().resolveSibling("strategies.txt");
			Files.createDirectories(f.getParent());
			Files.writeString(f, strategies.save());
		} catch (IOException e) {
			LOG.warn("Could not save Xen's plan book: {}", e.toString());
		}
		Path file = brainFile();
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("brain.bin.tmp");
			try (OutputStream out = Files.newOutputStream(tmp)) {
				brain.write(out);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			if (mind != null && mindExperiences > 0) {
				Path mtmp = file.resolveSibling("mind.bin.tmp");
				try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(mtmp)))) {
					mind.save(out);
				}
				Files.move(mtmp, file.resolveSibling("mind.bin"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (IOException e) {
			LOG.warn("Could not save Xen's brain: {}", e.toString());
		}
		if (ex1 != null) {
			try {
				ex1.save(file.resolveSibling("ex1-learned.json"));
			} catch (IOException e) {
				LOG.warn("Could not save what Xen Ex1 learned: {}", e.toString());
			}
		}
		for (Companion c : companions) roster.remember(c, teamOf(c));
		roster.save();
		lore.save();
		society.save();
		Rules.save();
		Focus.save();
		Planner.save();
		xen.mod.talk.Voice.save();                                            // (what its words network learned from people)
		xen.mod.talk.Banter.save();
	}

	private void stopping(MinecraftServer s) {
		gameplayLog.quietly(gameplayLog::closeAll);
		arena.stop("the server is stopping.");
		avatar.stopping();
		for (Companion c : companions) if (c.player() != null && !c.inArena) rememberAway(c);   // they come back next time
		saveAway();
		for (Companion c : companions) roster.remember(c, teamOf(c));                          // (before they leave: all they know)
		roster.save();
		lore.save();
		society.save();
		Rules.save();
		Focus.save();
		Planner.save();
		xen.mod.talk.Voice.save();
		xen.mod.talk.Banter.save();
		for (Companion c : new ArrayList<>(companions)) c.leave();
		running = false;
		if (trainer != null) trainer.interrupt();
		if (brain != null) save();
		chat.close();
	}

	// --------------------------------------------------------------------------------- world
	private long lastTickTrouble;

	/**
	 * Every server tick. Nothing Xen does may take the server down with it: a part that fails is logged (at most once a
	 * minute) and the game goes on without it for that tick.
	 */
	private void tick(MinecraftServer s) {
		try {
			Tour.tickAll(s);                                                     // (anyone on a /xen tour: their next view)
			tickWorld(s);
		} catch (RuntimeException e) {
			long now = System.currentTimeMillis();
			if (now - lastTickTrouble > 60_000L) {
				lastTickTrouble = now;
				LOG.error("Xen stumbled this tick (the game goes on): {}", e.toString(), e);
			}
		}
	}

	private void tickWorld(MinecraftServer s) {
		DesignTest.maybeRun(s);                                          // (developing designs: only with -Dxen.designTest)
		float life = config.hardcore(s) ? 1.4f : 1f;                     // hardcore: one life, every danger weighs more
		if (life != Personality.lifeWeight) {
			Personality.lifeWeight = life;
			for (Companion c : companions) c.applyPersonality();
			LOG.info("Xens are {}", life > 1 ? "hardcore: one life each, gone for good when they die" : "not hardcore: they respawn");
		}
		if (s.getTickCount() % 20 == 0) avatar.tick();
		if (s.getTickCount() % 8 == 0) BuildAxe.show(s);                       // (the Build Axe's box, for whoever holds one)
		if (s.getTickCount() % 12 == 0) for (Companion c : companions) c.builder.showLayout();   // (a place a Xen pictured, for the players near)
		arena.tick();
		runLater();
		gameplayLog.quietly(() -> gameplayLog.tick(s));
		if (Duels.ON && duels != null) duels.tick(s);                            // (-Dxen.fightLog: duel rows, to learn fighting from a good player)
		long tLuck = Perf.now();
		Hunch.tick(this, s);
		Perf.add("luck (looking at the world)", tLuck);
		List<Companion> order = new ArrayList<>(companions);
		java.util.Collections.shuffle(order, random);                  // nobody always gets to act first
		Perf.tick(s.getTickCount());
		for (Companion c : order) {
			long t0 = Perf.now();
			try {
				c.tick();
				c.director.tick();                                            // (a show's timing: after everything else has had its say this tick)
			} catch (Throwable e) {
				LOG.warn("{} stumbled: {}", c.name, e.toString(), e);
			}
			Perf.xen(c.name, t0);
		}
		if (brain != null && System.currentTimeMillis() - lastSave > config.saveMinutes * 60_000L) {
			lastSave = System.currentTimeMillis();
			save();
		}
		if (s.getTickCount() % 100 == 0) {
			try {
				Tribe.tickAll(this);                                          // tribes: who's in which, sharing, the night watch
				if (config.tribes) society.tick(s.overworld().getGameTime());   // (faiths founded, the history for graphs)
			} catch (RuntimeException e) {
				LOG.warn("Xen tribes stumbled: {}", e.toString(), e);
			}
			if (config.evolution) evolve();
		}
		if (config.realSkins && s.getTickCount() % 200 == 100) {
			try {
				realSkins();
			} catch (RuntimeException e) {
				LOG.warn("Xen's real skins stumbled: {}", e.toString());
			}
		}
		if (s.getTickCount() % 6000 == 3000) {                                // every five minutes: its team, its rules
			try {
				maybeFoundTeams();                                             // (a Xen may start a team of its own)
			} catch (RuntimeException e) {
				LOG.warn("Xen team founding stumbled: {}", e.toString());
			}
			for (Companion c : companions) {
				if (c.player == null || c.minion || c.inArena) continue;
				try {
					rethinkTeam(c);
				} catch (RuntimeException e) {
					LOG.warn("Xen team thinking stumbled: {}", e.toString());
				}
			}
		}
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

	/**
	 * A player broke ore: the Xens close by that saw it (in their view, nothing in between) learn where that ore is from
	 * watching, the way a new player copies someone who knows (not Xens watching Xens: they tell each other instead).
	 */
	private void watchedBreak(net.minecraft.world.entity.player.Player player, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		if (player instanceof XenPlayer || !(player instanceof ServerPlayer sp)) return;
		String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (!n.endsWith("_ore")) return;
		for (Companion c : companions) {
			var me = c.player();
			if (me == null || me.level() != sp.level() || me.distanceTo(sp) > 24 || !me.hasLineOfSight(sp)) continue;
			c.lessons.watched(n, pos.getY(), sp.getGameProfile().name(), c.trust(sp.getUUID()));
			LOG.info("{} watched {} mine {} at y {}: thinks {} around y {}", c.name, sp.getGameProfile().name(), n, pos.getY(),
					n.contains("diamond") ? "diamonds" : n.contains("iron") ? "iron" : "coal", c.lessons.depth(n.contains("diamond") ? "diamonds" : n.contains("iron") ? "iron" : "coal", 0));
		}
	}

	/**
	 * A hardcore Xen died: it's gone for good. Nothing of it is kept (a Xen by that name later is someone new), it's in
	 * the world's history, the players are told, and every Xen that knew it remembers (and those that liked it say so).
	 */
	void goneForGood(Companion c, java.util.UUID id, BlockPos at, String how) {
		roster.forget(c.name);
		roster.save();
		away.remove(c.name);
		saveAway();
		Component line = Component.literal("[Xen] " + c.name + " is gone for good (hardcore: one life).").withStyle(net.minecraft.ChatFormatting.GRAY);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof XenPlayer)) p.sendSystemMessage(line);
		LOG.info("{} died for good ({}): hardcore", c.name, how);
		for (Companion o : companions) {
			if (o == c || o.player() == null || id == null || !o.known.contains(id) && o.trust(id) == 0) continue;
			o.journal("remembers", c.name + " died (" + how + ") and is gone for good");
			if (o.trust(id) > 0.3f && o.player().blockPosition().closerThan(at, 64)) {
				o.chatter(o.pick3("Rest in peace, " + c.name + ".", "I can't believe " + c.name + " is gone...", c.name + "... gone. Just like that."), true);
			}
		}
	}

	private void ownerLeft(ServerPlayer p) {
		avatar.leaving(p);
		if (p == null || p instanceof XenPlayer || !config.leaveWithOwner) return;
		for (Companion c : companions) {
			if (!p.getUUID().equals(c.owner)) continue;
			rememberAway(c);                                                     // back when its owner is
			roster.remember(c, teamOf(c));
			server.execute(c::leave);
		}
		saveAway();
		roster.save();
	}

	// ------------------------------------------------------------------------------ coming back
	/**
	 * The Xens that were in the world when it stopped (or when their owner left): who, whose, and how they were
	 * (following, staying, free; a minion and its boss). They come back by themselves: the free ones when the world
	 * starts, the others when their owner joins, where they were and with their things (the server keeps a player's
	 * bag and place). Kept in {@code <world>/xen/away.json}.
	 */
	private final java.util.Map<String, com.google.gson.JsonObject> away = new java.util.LinkedHashMap<>();

	private Path awayFile() {
		return brainFile().resolveSibling("away.json");
	}

	private void rememberAway(Companion c) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		if (c.owner != null) o.addProperty("owner", c.owner.toString());
		o.addProperty("mode", c.mode.name());
		if (c.anchor != null) {
			o.addProperty("ax", c.anchor.getX());
			o.addProperty("ay", c.anchor.getY());
			o.addProperty("az", c.anchor.getZ());
		}
		if (c.minion) o.addProperty("boss", c.boss == null ? "" : c.boss.name);
		away.put(c.name, o);
	}

	private void saveAway() {
		try {
			com.google.gson.JsonObject all = new com.google.gson.JsonObject();
			away.forEach(all::add);
			Files.createDirectories(awayFile().getParent());
			Files.writeString(awayFile(), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(all));
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not save who's away: {}", e.toString());
		}
	}

	private void loadAway() {
		away.clear();
		try {
			if (!Files.exists(awayFile())) return;
			var all = new com.google.gson.Gson().fromJson(Files.readString(awayFile()), com.google.gson.JsonObject.class);
			for (var e : all.entrySet()) away.put(e.getKey(), e.getValue().getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			LOG.warn("Could not read who's away: {}", e.toString());
		}
	}

	/** Its owner is back: its Xens come back too (a moment later, when the world around has loaded). */
	private void ownerJoined(ServerPlayer p) {
		if (p == null || p instanceof XenPlayer) return;
		String id = p.getUUID().toString();
		if (away.values().stream().noneMatch(o -> o.has("owner") && o.get("owner").getAsString().equals(id))) return;
		later(40, () -> comeBack(o -> o.has("owner") && o.get("owner").getAsString().equals(id)));
	}

	private final List<Object[]> laterJobs = new ArrayList<>();

	void later(int ticks, Runnable job) {
		synchronized (laterJobs) {
			laterJobs.add(new Object[] {server.getTickCount() + ticks, job});
		}
	}

	private void runLater() {
		List<Runnable> due = new ArrayList<>();
		synchronized (laterJobs) {
			laterJobs.removeIf(j -> {
				if ((int) j[0] > server.getTickCount()) return false;
				due.add((Runnable) j[1]);
				return true;
			});
		}
		for (Runnable r : due) r.run();
	}

	/** Bring back the Xens that were away (those that match), where the server kept them, bosses before their minions. */
	private void comeBack(java.util.function.Predicate<com.google.gson.JsonObject> which) {
		List<String> names = new ArrayList<>();
		for (var e : away.entrySet()) if (which.test(e.getValue())) names.add(e.getKey());
		names.sort(java.util.Comparator.comparing(n -> away.get(n).has("boss") ? 1 : 0));
		int back = 0;
		for (String name : names) {
			com.google.gson.JsonObject o = away.remove(name);
			if (server.getPlayerList().getPlayerByName(name) != null || full() && !o.has("boss")) continue;
			ServerPlayer owner = o.has("owner") ? server.getPlayerList().getPlayer(java.util.UUID.fromString(o.get("owner").getAsString())) : null;
			if (o.has("owner") && owner == null) {
				away.put(name, o);                                               // (its owner left again already)
				continue;
			}
			Companion c = create(name, owner, null);
			try {
				c.mode = Companion.Mode.valueOf(o.get("mode").getAsString());
			} catch (RuntimeException e) {
				c.mode = Companion.Mode.FOLLOW;
			}
			if (o.has("ax")) c.anchor = new BlockPos(o.get("ax").getAsInt(), o.get("ay").getAsInt(), o.get("az").getAsInt());
			if (o.has("boss")) c.mode = Companion.Mode.FREE;                   // (minions are gone since 1.2: an old world's minion is a Xen of its own now)
			c.join(server.overworld(), null, 0);                                // (the server puts it back where it was, bag and all)
			companions.add(c);
			back++;
		}
		saveAway();
		if (back > 0) LOG.info("{} Xens came back ({} still away)", back, away.size());
	}

	/** A player said something: if it's to a Xen (by name), it understands, does what was asked and answers. */
	/** Close enough to hear someone (local chat: within the chat range, in the same world). */
	boolean inRange(net.minecraft.world.entity.Entity a, net.minecraft.world.entity.Entity b) {
		return a.level() == b.level() && a.distanceTo(b) <= config.chatRange;
	}

	/** Its own name called out (a shout carries): four times as far as talk does, in the same world. */
	private boolean hearsItsName(Companion c, ServerPlayer sender) {
		return c.player() != null && c.player().level() == sender.level() && c.player().distanceTo(sender) <= config.chatRange * 4;
	}

	/** Called by name from too far off to hear it: the one calling is told so (and how to reach it anyway). */
	private void tooFar(ServerPlayer sender, Companion c) {
		String where = c.player() == null ? "" : c.player().level() != sender.level() ? " (it's in another dimension)"
				: String.format(java.util.Locale.ROOT, " (it's %.0f blocks away)", c.player().distanceTo(sender));
		sender.sendSystemMessage(net.minecraft.network.chat.Component.literal(c.name + " is too far away to hear you" + where
				+ ". Chat carries " + config.chatRange + " blocks, a name called out " + config.chatRange * 4 + "; /msg " + c.name + " reaches it anywhere.")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
	}

	/** Can this Xen hear what that player says? (With local chat only close by; else anywhere.) */
	private boolean hears(Companion c, ServerPlayer sender) {
		return c.player() != null && (!config.localChat || inRange(c.player(), sender));
	}

	/**
	 * A player whispered to a Xen (/msg): the Xen alone hears it, wherever it is, and whispers back (a request it
	 * takes on, it answers in a whisper too). Nobody else hears a word of it.
	 */
	void whisperFrom(ServerPlayer sender, Companion c, String text) {
		if (!config.chat || c.player() == null) return;
		if (config.journal && journal != null) journal.add(sender.getName().getString(), "whispers to " + c.name, text);
		chat.ask(sender.getName().getString(), text, c.name, request -> onServer(() -> c.privately(sender, () -> c.request(request, sender, text))),
				reply -> server.execute(() -> c.whisper(sender, reply)));
	}

	/** Something said out loud by a player or a Xen: the Xens close by overhear it (rumors, plots: spies). */
	/** The Xen this player is (null: a real player). */
	Companion companionOf(ServerPlayer p) {
		for (Companion o : companions) if (o.player() == p) return o;
		return null;
	}

	void overheardBy(ServerPlayer speaker, String name, String text) {
		if (speaker == null) return;
		for (Companion o : companions) {
			if (o.player() == null || o.player() == speaker || !inRange(o.player(), speaker)) continue;
			o.rumors.overheard(name, speaker.getUUID(), text);
		}
	}

	/**
	 * Someone died. With local death messages, only those within the chat range get the message; and the Xens close by
	 * (and the killer) remember it: who killed whom.
	 */
	private void died(ServerPlayer victim, net.minecraft.world.damagesource.DamageSource source) {
		if (config.localDeaths) {
			Component msg = source.getLocalizedDeathMessage(victim);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!(p instanceof XenPlayer) && (p == victim || inRange(p, victim))) p.sendSystemMessage(msg);
			}
			LOG.info("{} (local death message)", msg.getString());
		}
		var killer = source.getEntity();
		for (Companion o : companions) {
			if (o.player() == null || o.player() == victim) continue;
			if (o.player() == killer || inRange(o.player(), victim)) o.rumors.witnessed(victim, killer);
		}
	}

	private boolean deathRuleOff;

	/** A Xen is coming back after dying (its "joined the game" isn't said). */
	static volatile boolean quietJoin;

	/**
	 * "Loop until real player skin": Xens still in one of the mod's own skins (it was offline, or the gallery ran
	 * short) keep it looking, and change into one a real player made as soon as there's one (two at a time).
	 */
	private void realSkins() {
		java.util.Set<String> worn = new java.util.HashSet<>();
		List<Companion> stock = new ArrayList<>();
		for (Companion c : companions) {
			worn.add(c.skin);
			if (c.player != null && !c.minion && !c.inArena && skins.stock(c.skin)) stock.add(c);
		}
		if (stock.isEmpty()) return;
		skins.keepLooking(Math.min(stock.size() + 4, 200), worn);
		int changed = 0;
		for (Companion c : stock) {
			if (changed >= 2) break;
			String real = skins.real(worn, random);
			if (real == null) break;
			if (c.reskin(real)) {
				worn.add(real);
				changed++;
				LOG.info("{} wears a skin a real player made now ({} still in the mod's own)", c.name, stock.size() - changed);
			}
		}
	}

	/** Local death messages: the game's own (to everyone) off, it sends them itself; back on when the setting goes off. */
	private void deathMessages() {
		if (server == null) return;
		if (!config.localDeaths && !deathRuleOff) return;
		var src = server.createCommandSourceStack().withSuppressedOutput();
		for (String rule : new String[] {"show_death_messages", "showDeathMessages"}) {   // (its name in 1.21.11 and 26.x, and before)
			try {
				server.getCommands().performPrefixedCommand(src, "gamerule " + rule + " " + !config.localDeaths);
			} catch (RuntimeException ignored) {
			}
		}
		deathRuleOff = config.localDeaths;
	}

	private void heard(ServerPlayer sender, String text) {
		try {
			gameplayLog.quietly(() -> gameplayLog.chat(sender, text));
			hear(sender, text);
		} catch (RuntimeException e) {                                        // (a chat problem never stops the chat, or the game)
			LOG.warn("Xen couldn't take in what {} said: {}", sender.getName().getString(), e.toString(), e);
		}
	}

	private void hear(ServerPlayer sender, String text) {
		if (sender instanceof XenPlayer || !config.chat) return;
		for (Companion c : companions) if (c.player() != null) c.director.heard(sender, text);   // (right after a show: a reaction)
		for (Companion c : companions) if (c.player() != null && c.gut.answer(sender, text)) return;   // (the answer to what a Xen asked them)
		for (Companion c : companions) {                                       // "lol ur geeked" to one that's embarrassed: it snaps
			if (c.player() == null || !hears(c, sender)) continue;
			String snap = c.jokes.geeked(sender, text);
			if (snap != null) {
				if (!snap.isEmpty()) c.say(snap);
				return;
			}
		}
		overheardBy(sender, sender.getName().getString(), text);             // who's listening close by?
		if (config.journal && journal != null) journal.add(sender.getName().getString(), "says", text);
		Companion named = null;                                               // the one named first ("Bex, help Aria build": Bex)
		int namedAt = Integer.MAX_VALUE;
		for (Companion c : companions) {
			if (!hears(c, sender) && !hearsItsName(c, sender)) continue;           // (its own name, called out: it hears that further off)
			var m = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(c.name) + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
			if (m.find() && m.start() < namedAt) {
				named = c;
				namedAt = m.start();
			}
		}
		if (named != null) {
			Companion c = named;
			chat.ask(sender.getName().getString(), text, c.name, request -> onServer(() -> c.request(request, sender, text)),
					reply -> server.execute(() -> c.say(reply)));
			return;
		}
		for (Companion c : companions) {                                       // said to a Xen by name that can't hear it: not for anyone else
			if (c.player() != null && java.util.regex.Pattern.compile("^\\W*" + java.util.regex.Pattern.quote(c.name) + "\\b",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) {
				tooFar(sender, c);                                                    // (and they're told, so they don't stand waiting)
				return;
			}
		}
		Companion byStart = null;                                              // "riv, come here": the start of its name will do
		String asked = null;
		for (Companion c : companions) {
			if (!hears(c, sender) && !hearsItsName(c, sender) || c.player().level() != sender.level()) continue;
			String t = calledByStart(text, c.name);
			if (t != null && (byStart == null || c.player().distanceTo(sender) < byStart.player().distanceTo(sender))) {
				byStart = c;
				asked = t;
			}
		}
		if (byStart != null) {
			Companion c = byStart;
			String t = asked;
			chat.ask(sender.getName().getString(), t, c.name, request -> onServer(() -> c.request(request, sender, t)),
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

	private static final java.util.Set<String> NOT_NAMES = java.util.Set.of("the", "and", "you", "can", "get", "come", "all", "not", "for", "but",
			"are", "was", "how", "why", "who", "yes", "now", "hey", "lol", "bro", "what", "where", "when", "stop", "stay", "give", "follow", "please",
			"hello", "help", "make", "build", "sleep", "go", "let", "lets", "okay", "yeah", "nah", "nope", "one", "two", "its", "it's", "this",
			"that", "they", "them", "there", "here", "have", "has", "had", "did", "does", "don", "dont", "wait", "look", "see", "use", "put", "take",
			"mine", "wood", "stone", "iron", "food", "eat", "run", "hit", "kill", "fight", "attack", "trade", "join", "team", "our", "your", "his", "her",
			"any", "some", "more", "less", "very", "much", "many", "too", "also", "just", "only", "then", "than", "with", "from", "into", "out", "off",
			"on", "in", "at", "to", "of", "up", "down", "bed", "sit", "try", "want", "need", "like", "love", "hate", "good", "bad", "nice", "cool");

	/**
	 * Called by the start of its name (3 letters or more) as the first word or with a comma after it ("riv come here",
	 * "hey neo", "holl, stop"): the message with its whole name in, or null. Common words don't count.
	 */
	static String calledByStart(String text, String name) {
		String n = name.toLowerCase(java.util.Locale.ROOT), letters = n.replaceAll("[^a-z]", "");
		var m = java.util.regex.Pattern.compile("^\\s*([a-z0-9_]{3,})\\b|\\b(?:hey|yo|oi|hi|hello)\\s+([a-z0-9_]{3,})\\b|\\b([a-z0-9_]{3,})\\s*[,:]")
				.matcher(text.toLowerCase(java.util.Locale.ROOT));
		while (m.find()) {
			String w = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
			if (w == null || NOT_NAMES.contains(w) || w.length() >= n.length()) continue;
			if (n.startsWith(w) || letters.length() >= 3 && letters.startsWith(w)) {
				return text.substring(0, m.start(m.group(1) != null ? 1 : m.group(2) != null ? 2 : 3)) + name
						+ text.substring(m.end(m.group(1) != null ? 1 : m.group(2) != null ? 2 : 3));
			}
		}
		return null;
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
			boolean mine = sender.getUUID().equals(c.owner);
			boolean justUs = mine && d <= 8 && nobodyElseNear(sender, x);
			boolean close = d <= 5 || mine && d <= 10;                       // right next to you: you're talking to it
			if (!talking && !looking && !justUs && !close) continue;
			if (!looking && !mine && otherPlayerCloser(sender, x)) continue;
			double score = d - (talking ? 6 : 0) - (looking ? 4 : 0) - (mine ? 3 : 0);
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
		return to.length() > 0.1 && look.dot(to.normalize()) > 0.9 && who.hasLineOfSight(at);   // (about 25 degrees, like a glance)
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
						.then(Commands.argument("name", StringArgumentType.word()).suggests((ctx, b) -> b.suggest("random").buildFuture())
								.executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
										.executes(ctx -> summonMany(ctx, StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("spawn").requires(XenMod::mayRun)
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
								.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), 300))
								.then(Commands.argument("radius", IntegerArgumentType.integer(0, 30000))
										.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), IntegerArgumentType.getInteger(ctx, "radius"))))
								.then(Commands.argument("name", StringArgumentType.word())          // (/xen spawn 1 Steve: that one's name, as /xen summon Steve)
										.executes(ctx -> IntegerArgumentType.getInteger(ctx, "count") == 1 ? summon(ctx, StringArgumentType.getString(ctx, "name"))
												: summonMany(ctx, StringArgumentType.getString(ctx, "name"), IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("duel").requires(XenMod::mayRun)                   // (for tests: every Xen here takes on that player)
						.then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
							String who = StringArgumentType.getString(ctx, "player");
							ServerPlayer foe = server.getPlayerList().getPlayerByName(who);
							if (foe == null) {
								ctx.getSource().sendFailure(Component.literal("No player called " + who + " here."));
								return 0;
							}
							return each(ctx, c -> {
								if (c.player() == foe) return null;
								c.goals.duel(foe);
								return c.name + " takes on " + who + ".";
							});
						})))
				.then(Commands.literal("dismiss").executes(ctx -> each(ctx, c -> {
					away.remove(c.name);                                            // (sent home: it doesn't come back by itself)
					c.leave();
					saveAway();
					return c.name + " went home.";
				})))
				.then(Commands.literal("mode")
						.then(Commands.literal("follow").executes(ctx -> each(ctx, c -> { c.mode = Companion.Mode.FOLLOW; return c.name + " will follow you."; })))
						.then(Commands.literal("stay").executes(ctx -> each(ctx, c -> {
							c.mode = Companion.Mode.STAY;
							c.anchor = c.player().blockPosition();
							return c.name + " will stay around here.";
						})))
						.then(Commands.literal("free").executes(ctx -> each(ctx, c -> { c.mode = Companion.Mode.FREE; return c.name + " will do its own thing."; }))))
				.then(Commands.literal("status").executes(ctx -> each(ctx, Companion::status)))
				.then(Commands.literal("progress").executes(ctx -> each(ctx, Companion::progress)))
				.then(Commands.literal("predict").executes(ctx -> each(ctx, c -> c.goals.predictions())))
				.then(Commands.literal("tips").executes(ctx -> each(ctx, c -> c.tips.describe())))
				.then(Commands.literal("plan").executes(ctx -> each(ctx, c -> c.plans.describe()))   // (its plan, or a goal for it: /xen plan bucket)
						.then(Commands.argument("item", StringArgumentType.word())
								.executes(ctx -> each(ctx, c -> c.player == null ? null : c.name + ": " + c.plans.setGoal(StringArgumentType.getString(ctx, "item"), 1, "you")))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
										.executes(ctx -> each(ctx, c -> c.player == null ? null : c.name + ": " + c.plans.setGoal(StringArgumentType.getString(ctx, "item"),
												IntegerArgumentType.getInteger(ctx, "count"), "you"))))))   // (the survival tips it weighs, and how far it trusts each)   // (what it expects of each choice now, and how good its guesses have been)
				.then(Commands.literal("g1x").executes(ctx -> each(ctx, c -> c.g1x.describe())))   // (what its geometry vision makes of the place)   // (one short line each: how far along it is)
				.then(Commands.literal("layout").executes(ctx -> each(ctx, c -> c.builder.showLayoutAgain())))
				.then(Commands.literal("terrain").requires(XenMod::mayRun)           // the world as it really is (a dev tool: WorldTruth)
						.then(Commands.literal("sample").then(Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 20000))
								.then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(8, 2000)).executes(ctx -> {
									String out;
									try {
										out = WorldTruth.sample(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()),
												com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"));
									} catch (java.io.IOException e) {
										out = "couldn't write: " + e;
									}
									String said = out;
									ctx.getSource().sendSuccess(() -> Component.literal(said), false);
									LOG.info("xen terrain: {}", said);
									return 1;
								}))))
						.then(Commands.literal("caves").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(8, 2000)).executes(ctx -> {
							String out;
							try {
								out = WorldTruth.caves(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()),
										com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"));
							} catch (java.io.IOException e) {
								out = "couldn't write: " + e;
							}
							String said = out;
							ctx.getSource().sendSuccess(() -> Component.literal(said), false);
							LOG.info("xen terrain: {}", said);
							return 1;
						}))))
				.then(Commands.literal("perf").executes(ctx -> {                    // where the server's time goes for the Xens
					String out = Perf.report();
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					LOG.info("xen perf: {}", out.replace('\n', ' '));
					return 1;
				}))
				.then(Commands.literal("knows").executes(ctx -> each(ctx, c -> c.knowledge.status())))

				.then(Commands.literal("time").executes(ctx -> {                 // where their time goes: each Xen, then all of them
					List<Diary> all = new ArrayList<>();
					StringBuilder sb = new StringBuilder();
					for (Companion c : companions) {
						if (c.minion) continue;
						all.add(c.diary);
						sb.append(Diary.describe(c.name, List.of(c.diary))).append('\n');
					}
					sb.append(Diary.describe("All Xens", all));
					String out = sb.toString();
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				}))
				.then(Commands.literal("tour").requires(XenMod::mayRun)          // training data without playing: a spectator flight, a new view every 3 s, for the recorder
						.executes(ctx -> tour(ctx, 30))
						.then(Commands.literal("stop").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayer();
							String out = p == null ? "Only a player can tour." : Tour.stop(p);
							ctx.getSource().sendSuccess(() -> Component.literal(out), false);
							return 1;
						}))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 600)).executes(ctx -> tour(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
				.then(Commands.literal("note").then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {   // a note in your own gameplay log: what you're doing
					ServerPlayer p = ctx.getSource().getPlayer();
					String text = StringArgumentType.getString(ctx, "text");
					String out = p == null ? "Only a player has a gameplay log." : gameplayLog.note(p, text) ? "Noted in your gameplay log: " + text
							: "Your play isn't being logged (an operator can turn it on: /xen set gameplayLog players).";
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				})))
				.then(Commands.literal("vision").executes(ctx -> {              // what each Xen sees, what it makes of it, what it costs
					StringBuilder sb = new StringBuilder();
					for (Companion c : companions) if (!c.minion) sb.append(sb.length() > 0 ? "\n" : "").append(c.vision.status());
					String out = sb.length() == 0 ? "No Xens." : sb.toString();
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				}).then(Commands.literal("dump").executes(ctx -> {             // its view as a picture (PPM) and what each pixel is, in the world's xen folder
					StringBuilder sb = new StringBuilder();
					for (Companion c : companions) {
						if (c.minion || c.player == null) continue;
						try {
							sb.append(sb.length() > 0 ? "\n" : "").append(c.name).append(": ").append(c.vision.dump(brainFile().resolveSibling("vision")).getFileName());
						} catch (IOException e) {
							sb.append(sb.length() > 0 ? "\n" : "").append(c.name).append(": ").append(e.getMessage());
						}
					}
					String out = sb.length() == 0 ? "No Xens." : sb.toString();
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				})))
				.then(Commands.literal("rules").executes(ctx -> {               // the rules each Xen wrote itself, from what worked and what didn't
					StringBuilder sb = new StringBuilder();
					for (Companion c : companions) if (!c.minion) sb.append(sb.length() > 0 ? "\n" : "").append(c.rules.describe());
					String out = sb.length() == 0 ? "No Xens." : sb.toString();
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				}))
				.then(Commands.literal("society").executes(ctx -> {             // constitutions, taxes, amendments, jobs, faiths
					String out = String.join("\n", society.describe());
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				}))
				.then(Commands.literal("tribes").executes(ctx -> {
					StringBuilder sb = new StringBuilder();
					for (Tribe t : tribes.values()) if (t.members.size() > 1) sb.append(sb.length() > 0 ? "\n" : "").append(t.status());
					String out = sb.length() == 0 ? "No tribes yet (Xens on their own)." : sb.toString();
					ctx.getSource().sendSuccess(() -> Component.literal(out), false);
					return 1;
				}))
				.then(Commands.literal("chat").then(Commands.literal("on").executes(ctx -> setting(ctx, "chat", true)))
						.then(Commands.literal("off").executes(ctx -> {
							int r = setting(ctx, "chat", false);
							ctx.getSource().sendSuccess(() -> Component.literal("(Xens won't listen to chat at all now: /xen chat on to talk with them again.)")
									.withStyle(net.minecraft.ChatFormatting.GRAY), false);
							return r;
						})))
				.then(Commands.literal("learn").then(Commands.literal("on").executes(ctx -> setting(ctx, "learn", true)))
						.then(Commands.literal("off").executes(ctx -> setting(ctx, "learn", false))))
				.then(Commands.literal("settings").executes(this::showSettings))
				.then(Commands.literal("set").requires(XenMod::mayRun)
						.then(Commands.argument("setting", StringArgumentType.word()).suggests((ctx, b) -> {
									for (var f : XenConfig.class.getFields()) b.suggest(f.getName());
									return b.buildFuture();
								})
								.then(Commands.argument("value", StringArgumentType.greedyString()).executes(ctx ->
										set(ctx, StringArgumentType.getString(ctx, "setting"), StringArgumentType.getString(ctx, "value"))))))
				.then(Commands.literal("build").then(Commands.argument("what", StringArgumentType.greedyString()).suggests((ctx, b) -> {
									for (String w : new String[] {"house", "base", "farm", "pen", "mob farm"}) b.suggest(w);
									for (String w : Schematic.list()) b.suggest(w);
									return b.buildFuture();
								}).executes(ctx -> {
									String what = StringArgumentType.getString(ctx, "what");
									if (Schematic.list().contains(what) || what.endsWith(".litematic")) return buildSchematic(ctx, what);   // a schematic: the nearest Xen, at your feet
									return each(ctx, c -> c.name + ": " + xen.mod.talk.Chat.plainly("Plan: " + c.builder.start(what), ""));
								})))
				.then(Commands.literal("schematics").executes(ctx -> {             // what's in config/xen/schematics/
					List<String> all = Schematic.list();
					if (all.isEmpty()) {
						ctx.getSource().sendSuccess(() -> Component.literal("No schematics yet: put .litematic files in config/xen/schematics/, then /xen build <name>."), false);
						return 0;
					}
					for (String n : all) {
						String line;
						try {
							line = Schematic.load(n).describe();
						} catch (IOException e) {
							line = n + ": can't read it (" + e.getMessage() + ")";
						}
						String l = line;
						ctx.getSource().sendSuccess(() -> Component.literal(l), false);
					}
					return all.size();
				}))
				.then(Commands.literal("goto").then(Commands.argument("where", StringArgumentType.greedyString())
						.executes(ctx -> {
							String where = StringArgumentType.getString(ctx, "where");
							String first = where.split(" ")[0];
							for (Companion one : companions) {                       // "/xen goto Pip 100 200": just that one
								if (one.name.equalsIgnoreCase(first) && one.player() != null) {
									String rest = where.substring(first.length()).trim();
									return each(ctx, c -> c == one ? c.name + ": " + c.goTo(rest) : null);
								}
							}
							return each(ctx, c -> c.name + ": " + c.goTo(where));
						})))
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
														case "temper" -> Personality.TEMPERS;
														case "plan" -> xen.mod.core.Strategy.NAMES;
														default -> new String[] {"0", "0.5", "1"};
													};
													for (String v : values) b.suggest(v);
													return b.buildFuture();
												})
												.executes(ctx -> style(ctx, StringArgumentType.getString(ctx, "xen"),
														StringArgumentType.getString(ctx, "trait"), StringArgumentType.getString(ctx, "value")))))))
				.then(Commands.literal("arena").requires(XenMod::mayRun)
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
				.then(Commands.literal("log").executes(ctx -> {                // the journal, as a file (the Experimental tab copies it too)
					try {
						Path f = journal.save();
						ctx.getSource().sendSuccess(() -> Component.literal("Xen's journal saved: " + f), false);
					} catch (IOException e) {
						ctx.getSource().sendFailure(Component.literal("Couldn't save the journal: " + e.getMessage()));
					}
					return 1;
				}))
				.then(BuildAxe.command("BuildAxe"))
				.then(BuildAxe.command("buildaxe"))
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

	private static final String[] TRAITS = {"plan", "fight", "build", "material", "tone", "temper", "bravery", "curiosity", "chattiness", "diligence", "kindness",
			"loyalty", "power", "money", "crit", "charge",
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
			int skill = java.util.Arrays.asList(Skills.NAMES).indexOf(trait.toLowerCase(java.util.Locale.ROOT));
			if (skill >= 0) {                                                  // a skill: "/xen style Pip fighting 0.9"
				try {
					c.skills.level[skill] = Math.max(0f, Math.min(1f, Float.parseFloat(value)));
				} catch (NumberFormatException e) {
					ctx.getSource().sendFailure(Component.literal(trait + " is a number from 0 to 1"));
					return 0;
				}
				roster.remember(c, teamOf(c));
				ctx.getSource().sendSuccess(() -> Component.literal(c.name + ": " + c.skills.describe()), false);
				return 1;
			}
			String error = c.personality.set(trait, value);
			if (error != null) {
				ctx.getSource().sendFailure(Component.literal(error));
				return 0;
			}
			c.applyPersonality();
			if (trait.equals("loner")) joinTeam(c);                              // (a lone wolf leaves its team)
			roster.remember(c, teamOf(c));
			roster.save();
			ctx.getSource().sendSuccess(() -> Component.literal(c.name + ": " + c.personality.describe() + "; " + c.personality.style() + "."), false);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("No Xen called " + who + " here."));
		return 0;
	}

	private static int tour(CommandContext<CommandSourceStack> ctx, int minutes) {
		ServerPlayer p = ctx.getSource().getPlayer();
		String out = p == null ? "Only a player can tour." : Tour.start(p, minutes);
		ctx.getSource().sendSuccess(() -> Component.literal(out), false);
		return 1;
	}

	private boolean op(CommandContext<CommandSourceStack> ctx) {
		return mayRun(ctx.getSource());
	}

	/**
	 * Who may use the /xen commands that change things: an operator, or the world's own player (single player, or the
	 * one who opened it to LAN) even with cheats off: it's their world. On a server, operators only.
	 */
	static boolean mayRun(CommandSourceStack src) {
		if (src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;
		var server = src.getServer();
		if (server == null || server.isDedicatedServer() || !(src.getEntity() instanceof ServerPlayer p)) return false;
		var owner = server.getSingleplayerProfile();
		return owner != null && owner.name().equalsIgnoreCase(p.getGameProfile().name());
	}

	// ------------------------------------------------------------------- who they are
	private java.util.Set<String> takenNames() {
		java.util.Set<String> taken = new java.util.HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) taken.add(p.getName().getString().toLowerCase(Locale.ROOT));
		return taken;
	}

	/** A Xen: the same one again if it has been here before (by name), otherwise new, with a name, nature and skin. */
	Companion create(String wanted, ServerPlayer owner, Personality nature) {
		return create(wanted, owner, nature, false);
	}

	/** typed: the name is one a person gave it (summoned by name, or their own), so with real names it's an account's. */
	Companion create(String wanted, ServerPlayer owner, Personality nature, boolean typed) {
		java.util.Set<String> taken = takenNames();
		if (wanted == null) taken.addAll(roster.names());                 // a new Xen gets a new name
		Personality born = nature != null ? nature : config.personalities ? Personality.random(random) : Personality.plain();
		String name = wanted;
		String real = null;
		if (name == null && config.randomNames && config.realNameStyle()) {     // a real account's name (one listed in real_names.txt), and its real skin
			for (String n : Skins.realNames(FabricLoader.getInstance().getConfigDir())) {
				if (!taken.contains(n.toLowerCase(Locale.ROOT))) {
					name = real = n;
					break;
				}
			}
		}
		if (name == null && config.randomNames) name = Names.fresh(config.realNameStyle() ? "player" : config.nameStyle, born.tone, taken, random);   // fits its nature
		if (name == null) name = Looks.freshName(false, taken, random);
		com.google.gson.JsonObject known = roster.get(name);
		Personality p = nature != null ? nature
				: known != null && known.has("personality") ? Personality.fromJson(known.getAsJsonObject("personality")) : born;
		p.bornWith(new Random(name.toLowerCase(Locale.ROOT).hashCode() * 31L + 7));   // (a Xen from before 1.5: its sins and beliefs from its name, always the same)
		if (nature == null && known == null && config.personalities) {           // born with an arena champion's fighting
			float[] champion = Arena.championGenes(brainFile().getParent(), random);
			if (champion != null) {
				p.fightGenes = champion;
				p.fight = Personality.nearest(champion);
			}
		}
		String skin = known != null && known.has("skin") && nature == null ? known.get("skin").getAsString() : skins.pick(config.skins, random);
		// Real names: the account's own skin, like the Carpet mod. A name from the list, or a new one someone summons a Xen
		// by (never a made-up one: that could be a stranger's account), fetched now if it isn't in yet (a few seconds at most).
		boolean account = real != null || config.nameStyle.equals("accurate") && typed && wanted != null && known == null;
		String own = config.realNameStyle() ? (account ? skins.playerNow(name, 3000) : skins.player(name)) : null;
		if (own != null) skin = own;
		if (own == null && config.realSkins && (known == null || skins.stock(skin))) {   // a real player's skin, if one's in (else later)
			java.util.Set<String> worn = new java.util.HashSet<>();
			for (Companion o : companions) worn.add(o.skin);
			String r = skins.real(worn, random);
			if (r != null) skin = r;
		}
		Companion c = new Companion(this, server, name, owner == null ? null : owner.getUUID(),
				owner == null ? "nobody" : owner.getName().getString(), p, skin);
		if (config.ownLife && config.wants) c.mode = Companion.Mode.FREE;   // its own life: it plays its own game
		if (known != null && known.has("known")) {
			try {
				for (var u : known.getAsJsonArray("known")) c.known.add(java.util.UUID.fromString(u.getAsString()));
			} catch (RuntimeException e) {
				LOG.warn("{}'s saved friends list couldn't be read: {}", name, e.toString());
			}
		}
		if (known == null) {                                                    // a new Xen: its own talents, its hobby
			c.skills.born();
			c.life.born();
			c.journal("is", "hidden: " + c.personality.hiddenInWords());       // (Xen 2.0: its hidden stats, for the log only)
		}
		if (known != null) {
			try {
				restore(c, known);
			} catch (RuntimeException e) {                                        // (a part saved by another version it can't read: the rest stays)
				LOG.warn("Some of {}'s saved life couldn't be read ({}); the rest is kept", name, e.toString());
			}
		}
		if (c.lessons.lessons.isEmpty()) c.lessons.born(random);               // (a rough idea of where ore is: new, or from before 1.8)
		if ((account || typed && wanted != null) && config.realNameStyle()) {               // a ranked player's name: it fights as well (tiers.txt, or MCTiers)
			String tier = Tiers.local(FabricLoader.getInstance().getConfigDir(), name);
			if (tier != null) Tiers.apply(c, tier);
			else {
				String who = name;
				java.util.concurrent.CompletableFuture.supplyAsync(() -> Tiers.online(who))
						.thenAccept(t -> { if (t != null) server.execute(() -> Tiers.apply(c, t)); });
			}
		}
		return c;
	}

	/** A Xen's saved life, part by part: a part it can't read (saved by another version) is skipped, not the whole Xen. */
	private void restore(Companion c, com.google.gson.JsonObject known) {
		java.util.function.BiConsumer<String, Runnable> part = (what, job) -> {
			try {
				job.run();
			} catch (RuntimeException e) {
				LOG.warn("{}'s saved {} couldn't be read ({}): starting that part fresh", c.name, what, e.toString());
			}
		};
		{
			part.accept("goals", () -> c.goals.load(known.has("likes") ? known.getAsJsonObject("likes") : null, known.has("goals") ? known.getAsJsonObject("goals") : null));
			part.accept("moves", () -> { if (known.has("skills")) c.mimic.load(known.getAsJsonObject("skills")); });
			part.accept("skills", () -> { if (known.has("abilities")) c.skills.load(known.getAsJsonObject("abilities")); else c.skills.born(); });
			part.accept("rumors", () -> { if (known.has("rumors")) c.rumors.load(known.getAsJsonObject("rumors")); });
			part.accept("life", () -> { if (known.has("life")) c.life.load(known.getAsJsonObject("life")); });
			if (c.life.hobby == null) c.life.born();
			part.accept("memories", () -> { if (known.has("memories")) for (var m : known.getAsJsonArray("memories")) c.memories.add(m.getAsString()); });
			part.accept("places", () -> { if (known.has("places")) c.places.load(known.getAsJsonObject("places")); });
			part.accept("chests", () -> { if (known.has("chests")) c.storage.load(known.getAsJsonObject("chests")); });
			part.accept("knowledge", () -> { if (known.has("knows")) c.knowledge.load(known.getAsJsonObject("knows")); });
			part.accept("lessons", () -> { if (known.has("lessons")) c.lessons.load(known.getAsJsonObject("lessons")); });
			part.accept("aversions", () -> { if (known.has("aversions")) c.aversions.load(known.getAsJsonObject("aversions")); });
			part.accept("facts", () -> { if (known.has("facts")) c.memory.load(known.getAsJsonArray("facts")); });
			part.accept("crafted", () -> { if (known.has("crafted")) for (var m : known.getAsJsonArray("crafted")) c.pace.made.add(m.getAsString()); });
			part.accept("portal math", () -> { if (known.has("portalMath") && known.get("portalMath").getAsBoolean()) c.knowledge.known.put("portal_math", Knowledge.How.TAUGHT); });
			part.accept("farm", () -> { if (known.has("crops")) c.farmer.load(known.getAsJsonObject("crops")); });
			part.accept("taste", () -> { if (known.has("taste")) c.taste.load(known.getAsJsonObject("taste")); });
			part.accept("habits", () -> { if (known.has("habits")) c.habits.load(known.getAsJsonObject("habits")); });
			part.accept("chronicle", () -> {
				if (!known.has("chronicle")) return;
				c.loreWritten = known.getAsJsonObject("chronicle").get("written").getAsInt();
				c.loreVolume = known.getAsJsonObject("chronicle").get("volume").getAsInt();
			});
			part.accept("home", () -> { if (known.has("homeBuild")) c.builder.loadHome(known.getAsJsonObject("homeBuild")); });
			part.accept("unfinished house", () -> { if (known.has("unfinishedBuild")) c.builder.loadUnfinished(known.getAsJsonObject("unfinishedBuild")); });
			part.accept("mine", () -> {
				if (!known.has("mine")) return;
				com.google.gson.JsonObject mine = known.getAsJsonObject("mine");
				c.chores.mineRecordY = mine.get("y").getAsInt();
				c.chores.mineRecordLeg = mine.get("leg").getAsInt();
				var d = net.minecraft.core.Direction.byName(mine.get("dir").getAsString());
				if (d != null) c.chores.mineRecordDir = d;
			});
			part.accept("adventure", () -> c.adventure.on = known.has("adventure") && known.get("adventure").getAsBoolean());
			part.accept("band", () -> { if (known.has("band")) c.band = known.get("band").getAsString(); });
			part.accept("gutTrust", () -> { if (known.has("gutTrust")) c.gut.faith = known.get("gutTrust").getAsFloat(); });
			part.accept("rage", () -> { if (known.has("rage")) c.rage.load(known.getAsJsonObject("rage")); });
			part.accept("selfBelief", () -> { if (known.has("selfBelief")) c.belief.value = known.get("selfBelief").getAsFloat(); });
			part.accept("experience", () -> { if (known.has("experience")) c.experience.load(known.getAsJsonArray("experience")); });
			part.accept("experienceLearned", () -> { if (known.has("experienceLearned")) c.experience.loadLearned(known.getAsJsonObject("experienceLearned")); });
			part.accept("moves", () -> { if (known.has("moves")) c.walker.loadMoves(known.getAsJsonObject("moves")); });
			part.accept("fightCosts", () -> { if (known.has("fightCosts")) c.fighter.loadCosts(known.getAsJsonObject("fightCosts")); });
			part.accept("tips", () -> { if (known.has("tips")) c.tips.load(known.getAsJsonObject("tips")); });
			part.accept("plans", () -> { if (known.has("plans")) c.plans.load(known.getAsJsonObject("plans")); });
			part.accept("trust", () -> {
				if (known.has("trust")) for (var e : known.getAsJsonObject("trust").entrySet()) c.trust.put(java.util.UUID.fromString(e.getKey()), e.getValue().getAsFloat());
			});
		}
	}

	private static final String[] TEAM_COLORS = {"red", "blue", "green", "yellow", "purple", "aqua"};
	private static final net.minecraft.ChatFormatting[] TEAM_FORMATS = {net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BLUE,
			net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.LIGHT_PURPLE,
			net.minecraft.ChatFormatting.AQUA};

	String teamOf(Companion c) {
		var team = server.getScoreboard().getPlayersTeam(c.name);
		return team != null && team.getName().startsWith("xen") ? team.getName() : null;
	}

	private static final String[] TEAM_A = {"Iron", "Crimson", "Shadow", "Golden", "Frost", "Ender", "Emerald", "Stone", "Night", "Storm", "Wild", "Obsidian", "Copper", "Lucky"};
	private static final String[] TEAM_B = {"Wolves", "Miners", "Knights", "Raiders", "Builders", "Foxes", "Legion", "Clan", "Guild", "Crew", "Dragons", "Squad", "Owls", "Bandits"};
	private static final net.minecraft.ChatFormatting[] TEAM_PAINT = {net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.DARK_PURPLE,
			net.minecraft.ChatFormatting.DARK_GREEN, net.minecraft.ChatFormatting.DARK_AQUA, net.minecraft.ChatFormatting.LIGHT_PURPLE,
			net.minecraft.ChatFormatting.DARK_RED, net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.BLUE};

	/** A team a Xen started itself (not one of the settings' color teams). */
	boolean ownTeam(String team) {
		if (team == null || !team.startsWith("xen_")) return false;
		return !java.util.Arrays.asList(TEAM_COLORS).contains(team.substring(4));
	}

	/**
	 * A player starts a team by asking in chat ("let's make a team called Night Owls"), no commands or cheats needed: a
	 * name they chose (or one made up) and a color; they're in. Xens join it if they want to (see Companion). The team,
	 * or null if they're already on one they made.
	 */
	net.minecraft.world.scores.PlayerTeam playerTeam(ServerPlayer p, String wanted) {
		var board = server.getScoreboard();
		var had = board.getPlayersTeam(p.getScoreboardName());
		if (had != null && ownTeam(had.getName())) return null;
		String name = wanted != null && !wanted.isBlank() ? titled(wanted.trim()) : TEAM_A[random.nextInt(TEAM_A.length)] + " " + TEAM_B[random.nextInt(TEAM_B.length)];
		if (name.length() > 24) name = name.substring(0, 24);
		String id = "xen_" + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (id.length() > 16) id = id.substring(0, 16);
		if (java.util.Arrays.asList(TEAM_COLORS).contains(id.substring(4))) id = id + "s";   // (not mixed up with a color team)
		var team = board.getPlayerTeam(id);
		if (team == null) {
			team = board.addPlayerTeam(id);
			team.setDisplayName(Component.literal(name));
			Compat.teamColor(team, TEAM_PAINT[random.nextInt(TEAM_PAINT.length)]);
		}
		team.setAllowFriendlyFire(config.friendlyFire);
		board.addPlayerToTeam(p.getScoreboardName(), team);
		LOG.info("{} made the team {}", p.getName().getString(), name);
		return team;
	}

	/** A Xen joins a player's team, whoever made it (asked in chat, or /team): it stays on it (no team of its own, no color team). */
	void joinPlayersTeam(Companion c, net.minecraft.world.scores.PlayerTeam team) {
		server.getScoreboard().addPlayerToTeam(c.name, team);
		roster.remember(c, team.getName());
		c.journal("does", "joins the team " + team.getDisplayName().getString() + " (its own choice)");
	}

	/** Is the Xen on a team a person is on too (a player's team: it keeps to it)? */
	boolean onPlayersTeam(Companion c) {
		var team = server.getScoreboard().getPlayersTeam(c.name);
		if (team == null) return false;
		for (String member : team.getPlayers()) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(member);
			boolean xen = companions.stream().anyMatch(o -> o.name.equals(member));
			if (!xen && (p == null || !(p instanceof XenPlayer))) return true;   // (someone not a Xen: a person, online or not)
		}
		return false;
	}

	/**
	 * A Xen starts a team of its own (a name it makes up, or the one asked for, and a color), like players on an SMP,
	 * and asks the friends around it to join; each decides (trust, loyalty; loners never). A player who asked is in too.
	 */
	String foundTeam(Companion c, String wanted, ServerPlayer with) {
		if (c.player == null) return null;
		if (c.personality.loner) return c.pick3("A team? Not for me.", "I work alone.", "No teams for me.");
		if (ownTeam(teamOf(c))) return "I already have a team: the " + server.getScoreboard().getPlayerTeam(teamOf(c)).getDisplayName().getString() + ".";
		String name = wanted != null && !wanted.isBlank() ? titled(wanted.trim()) : TEAM_A[random.nextInt(TEAM_A.length)] + " " + TEAM_B[random.nextInt(TEAM_B.length)];
		if (name.length() > 24) name = name.substring(0, 24);
		String id = "xen_" + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (id.length() > 16) id = id.substring(0, 16);
		var board = server.getScoreboard();
		var team = board.getPlayerTeam(id);
		if (team == null) {
			team = board.addPlayerTeam(id);
			team.setDisplayName(Component.literal(name));
			Compat.teamColor(team, TEAM_PAINT[random.nextInt(TEAM_PAINT.length)]);
		}
		team.setAllowFriendlyFire(config.friendlyFire);
		board.addPlayerToTeam(c.name, team);
		roster.remember(c, id);
		c.journal("does", "founded the team " + name);
		LOG.info("{} founded the team {}", c.name, name);
		if (with != null) board.addPlayerToTeam(with.getScoreboardName(), team);
		int joined = 0;
		for (Companion o : companions) {                                          // who's in? (their call)
			if (o == c || o.player() == null || o.player().level() != c.player.level() || o.player().distanceTo(c.player) > 32) continue;
			if (o.personality.loner || ownTeam(teamOf(o)) || o.diplomacy.wary(c.player.getUUID())) continue;
			float t = o.trust(c.player.getUUID());
			boolean kin = Personality.kindred(c.personality, o.personality);     // one of its own kind: in, before they know each other well
			if (kin) t = Math.max(t, Math.min(0.8f, t + 0.35f));
			if (t < 0.5f || o.personality.loyalty > 0.75f && teamOf(o) != null && t < 0.8f) continue;
			board.addPlayerToTeam(o.name, team);
			roster.remember(o, id);
			var n = name;
			o.mod.later(30 + 20 * joined, () -> o.say(kin ? o.pick3("We think alike. I'm in.", "Count me in! The " + n + "!", "People like us should stick together. I'm in.")
					: o.pick3("Count me in! The " + n + "!", "I'm in.", "The " + n + "? Sure, I'll join.")));
			joined++;
		}
		return c.pick3("I'm starting a team: the " + name + "!", "New team: the " + name + ". Who's in?", "From now on we're the " + name + "!")
				+ (joined > 0 ? "" : " Just me for now.");
	}

	private static String titled(String s) {
		StringBuilder b = new StringBuilder();
		for (String w : s.split("\\s+")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		return b.toString();
	}

	/** Now and then a Xen with a leader's nature and friends around starts a team of its own (an SMP thing). */
	private void maybeFoundTeams() {
		if (config.teams == 0 || companions.size() < 2) return;
		for (Companion c : companions) {
			if (c.player() == null || c.minion || c.personality.loner || ownTeam(teamOf(c)) || onPlayersTeam(c)) continue;
			var p = c.personality;
			int friends = 0, kin = 0;
			for (Companion o : companions) {
				if (o == c || o.player() == null || o.player().level() != c.player().level() || o.player().distanceTo(c.player()) >= 32 || ownTeam(teamOf(o))
						|| o.personality.loner) continue;
				if (o.trust(c.player().getUUID()) >= 0.5f) friends++;
				else if (Personality.kindred(p, o.personality)) kin++;               // (like-minded: they stick together from the start, before they know each other)
			}
			boolean leader = p.power > 0.6f || p.loyalty > 0.65f;
			if (friends + kin == 0 || !(leader || kin >= 2) || random.nextFloat() > (kin > 0 ? 0.3f : 0.12f)) continue;
			if (kin > 0) c.journal("thinks", "others about think like it: a team of its own kind");
			c.say(foundTeam(c, null, null));
			return;                                                               // (one new team at a time)
		}
	}

	/** Put a Xen on its team (one team for all, or the smallest of several, keeping the team it had). */
	void joinTeam(Companion c) {
		var board = server.getScoreboard();
		var knownTeam = roster.get(c.name);
		String kept = knownTeam != null && knownTeam.has("team") ? knownTeam.get("team").getAsString() : null;
		if (c.arenaTeam == null && ownTeam(kept) && board.getPlayerTeam(kept) != null && !c.personality.loner) {   // a team it started or joined itself
			board.addPlayerToTeam(c.name, board.getPlayerTeam(kept));
			return;
		}
		if (c.arenaTeam == null && kept != null && board.getPlayerTeam(kept) != null && !c.personality.loner) {   // a player's team it joined (made with /team): it stays
			board.addPlayerToTeam(c.name, board.getPlayerTeam(kept));
			if (onPlayersTeam(c)) return;
		}
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
		if (n == 0 || c.personality.loner) {                            // (a lone wolf is on nobody's team)
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
			if (index < 0 || index >= n) index = likedTeam(c, n, null);           // the team of those around it it likes most
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
		}
		team.setAllowFriendlyFire(config.friendlyFire);                          // (same team or not: whether to fight is each Xen's own call)
		board.addPlayerToTeam(c.name, team);
		roster.remember(c, name);
	}

	/** Which team's people around it (Xens and players, 64 blocks) it likes most, by trust and closeness; -1 if none. */
	int likedTeam(Companion c, int n, double[] scores) {
		if (c.player == null) return -1;
		double[] s = scores != null ? scores : new double[n];
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == c.player || p.level() != c.player.level()) continue;
			var t = server.getScoreboard().getPlayersTeam(p.getScoreboardName());
			if (t == null || !t.getName().startsWith("xen_")) continue;
			int i = java.util.Arrays.asList(TEAM_COLORS).indexOf(t.getName().substring(4));
			double d = p.distanceTo(c.player);
			if (i < 0 || i >= n || d > 64) continue;
			s[i] += c.trust(p.getUUID()) / (1 + d / 16);
		}
		int best = -1;
		for (int i = 0; i < n; i++) if (s[i] > 0.3 && (best < 0 || s[i] > s[best])) best = i;
		return best;
	}

	/**
	 * Now and then a Xen thinks about its team: if the people of another team around it are the ones it likes (and
	 * it isn't very loyal to its own), it goes over to them.
	 */
	void rethinkTeam(Companion c) {
		int n = Math.min(Math.max(config.teams, 0), TEAM_COLORS.length);
		if (n < 2 || c.arenaTeam != null || c.player == null || c.personality.loyalty > 0.7f || c.personality.loner) return;
		String now = teamOf(c);
		int mine = now == null ? -1 : java.util.Arrays.asList(TEAM_COLORS).indexOf(now.replace("xen_", ""));
		double[] s = new double[n];
		int best = likedTeam(c, n, s);
		if (best < 0 || best == mine || mine >= 0 && s[best] < s[mine] + 0.8) return;
		moveToTeam(c, TEAM_COLORS[best]);
		c.say(c.pick3("I'm joining the " + TEAM_COLORS[best] + " team. I like it there.", "Team " + TEAM_COLORS[best] + " it is!",
				"I'm with " + TEAM_COLORS[best] + " now."));
	}

	/** Put it on that team (a color); false if there's no such team here. */
	boolean moveToTeam(Companion c, String color) {
		var own = server.getScoreboard().getPlayerTeam("xen_" + color);
		if (own != null && ownTeam("xen_" + color)) {                              // a team someone started (not a color team)
			server.getScoreboard().addPlayerToTeam(c.name, own);
			roster.remember(c, "xen_" + color);
			return true;
		}
		int n = Math.min(Math.max(config.teams, 0), TEAM_COLORS.length);
		int i = java.util.Arrays.asList(TEAM_COLORS).indexOf(color);
		if (n < 2 || i < 0 || i >= n) return false;
		var board = server.getScoreboard();
		String name = "xen_" + color;
		var team = board.getPlayerTeam(name);
		if (team == null) {
			team = board.addPlayerTeam(name);
			Compat.teamColor(team, TEAM_FORMATS[i]);
		}
		team.setAllowFriendlyFire(config.friendlyFire);
		board.addPlayerToTeam(c.name, team);
		roster.remember(c, name);
		c.journal("does", "joins the " + color + " team (its own choice)");
		return true;
	}

	/** The world has all the Xens it may have (minions apart). */
	boolean full() {
		return config.maxXens > 0 && companions.stream().filter(c -> !c.minion).count() >= config.maxXens;
	}

	private long minionCount() {
		return companions.stream().filter(c -> c.minion).count();
	}

	/**
	 * /xen summon random 5: five Xens, each with a random name and skin (any other name: Pip, Pip2, Pip3...). As many
	 * as the limits allow (Xens per player, Xens in the world); it says how many came.
	 */
	private int summonMany(CommandContext<CommandSourceStack> ctx, String name, int count) {
		boolean random = name.equalsIgnoreCase("random");
		int made = 0;
		for (int i = 0; i < count; i++) {
			String n = random ? null : i == 0 ? name : (name.length() > 14 ? name.substring(0, 14) : name) + (i + 1);
			if (summon(ctx, n) == 0) break;
			made++;
		}
		int m = made;
		if (count > 1) ctx.getSource().sendSuccess(() -> Component.literal(m + " of " + count + " Xens summoned" + (m < count ? " (the limit: /xen set maxPerPlayer, maxXens)" : "") + "."), false);
		return made;
	}

	private int summon(CommandContext<CommandSourceStack> ctx, String wanted) {
		if (wanted != null && wanted.equalsIgnoreCase("random")) wanted = null;   // "random": a random name and skin
		ServerPlayer owner = ctx.getSource().getPlayer();              // null from the server console
		long mine = owner == null ? 0 : companions.stream().filter(c -> owner.getUUID().equals(c.owner) && !c.minion).count();
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
		Companion c = create(wanted, owner, null, true);
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

	/**
	 * Minions for your Xen: sidekicks with the same mind that don't load the world themselves (they only live where
	 * someone keeps it loaded), take orders from their boss, and help it build a village. See {@link Crew}.
	 */
	private int minions(CommandContext<CommandSourceStack> ctx, int count) {
		ServerPlayer owner = ctx.getSource().getPlayer();
		Companion boss = owner == null ? null : companions.stream().filter(c -> owner.getUUID().equals(c.owner) && !c.minion && c.player != null)
				.findFirst().orElse(null);
		if (boss == null) {
			ctx.getSource().sendFailure(Component.literal("Summon a Xen first (/xen summon): minions work for a Xen."));
			return 0;
		}
		int room = config.maxMinions <= 0 ? count : (int) Math.max(0, config.maxMinions - minionCount());
		int made = 0;
		java.util.Random random = new java.util.Random();
		for (int i = 0; i < Math.min(count, room); i++) {
			Companion m = create(null, owner, null);
			m.minion = true;
			m.boss = boss;
			m.mode = Companion.Mode.FREE;
			Vec3 at = boss.player.position();
			for (int tries = 0; tries < 12; tries++) {
				Vec3 side = new Vec3(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
				if (boss.player.level().noCollision(boss.player.getBoundingBox().move(side))) {
					at = boss.player.position().add(side);
					break;
				}
			}
			m.join((ServerLevel) boss.player.level(), at, random.nextFloat() * 360);
			companions.add(m);
			boss.crew.minions.add(m);
			m.say(m.pick3("Ready to work, " + boss.name + "!", "What's the job, boss?", "Hi " + boss.name + "! Where do I start?"));
			made++;
		}
		int n = made;
		if (made == 0) ctx.getSource().sendFailure(Component.literal("The world has all the minions it may have (" + config.maxMinions + ")."));
		else ctx.getSource().sendSuccess(() -> Component.literal(n + " minion" + (n == 1 ? "" : "s") + " for " + boss.name
				+ ". They don't load the world themselves (they freeze where nobody keeps it loaded), take orders from " + boss.name
				+ " and you, and build a village around " + boss.name + "'s home."), false);
		return made;
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
		StringBuilder sb = new StringBuilder("Xen settings:");
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

	/** After settings change (command or settings screen): teams and death messages. */
	void applySettings() {
		skins.prepare(FabricLoader.getInstance().getConfigDir(), config.skins);
		if (server == null) return;
		server.execute(() -> {
			for (Companion c : companions) if (c.player() != null) joinTeam(c);
			deathMessages();
		});
	}

	/** /xen build <schematic>: the nearest of your Xens builds it, its lowest corner where you stand (as it was saved). */
	private int buildSchematic(CommandContext<CommandSourceStack> ctx, String name) {
		Schematic s;
		try {
			s = Schematic.load(name);
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.literal("Couldn't read that schematic: " + e.getMessage()));
			return 0;
		}
		ServerPlayer p = ctx.getSource().getPlayer();
		var at = ctx.getSource().getPosition();
		Companion best = null;
		double bestD = Double.MAX_VALUE;
		for (Companion c : companions) {
			if (c.player() == null || c.player().level() != ctx.getSource().getLevel()) continue;
			if (p != null && !p.getUUID().equals(c.owner) && !op(ctx)) continue;
			double d = c.player().position().distanceTo(at);
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		if (best == null) {
			ctx.getSource().sendFailure(Component.literal("You have no Xen here. /xen summon"));
			return 0;
		}
		String plan = best.builder.startSchematic(s, net.minecraft.core.BlockPos.containing(at));
		String msg = best.name + ": " + xen.mod.talk.Chat.plainly("Plan: " + plan, "") + " (" + s.describe() + ")";
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private int each(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Companion, String> f) {
		ServerPlayer p = ctx.getSource().getPlayer();
		int n = 0;
		for (Companion c : companions) {
			if (p != null && !p.getUUID().equals(c.owner) && !op(ctx)) continue;
			if (p != null && c.owner == null && !op(ctx)) continue;
			if (c.player() == null) continue;
			String msg = f.apply(c);
			if (msg == null) continue;                                    // (not this one)
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
