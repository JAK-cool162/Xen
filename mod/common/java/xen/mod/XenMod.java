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
	final Arena arena = new Arena(this);
	/** Where skins come from: the pack, your folder, mineskin.org, players. */
	final Skins skins = new Skins();
	final java.util.Random random = new java.util.Random();
	private long lastChatNeed, lastEvolvedDay = -1;
	/** Players who were told why Xens' answers are simple (once each). */
	private final java.util.Set<java.util.UUID> toldAboutModel = java.util.concurrent.ConcurrentHashMap.newKeySet();

	XenConfig config;
	Brain brain;
	/** Xen 2.0's mind (what to do next: one for all main Xens, it keeps learning); null: the old way. */
	xen.mod.core.Mind mind;
	/** How many of its choices Xen 2.0 has seen turn out (in this world), and learned from. */
	long mindExperiences;
	private long mindTrained;
	private final Random mindRandom = new Random();
	Chat chat;
	MinecraftServer server;

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
		Chat.gpuSetting = () -> config.gpu;
		Chat.sizeSetting = () -> config.chatModelSize;
		skins.prepare(configDir, config.skins);
		solverMind.load(configDir.resolve("xen"));
		journal = new Journal(configDir.resolve("xen").resolve("logs"));
		chat = new Chat(configDir.resolve("xen").resolve(Chat.MODEL), () -> config.chatModel, () -> config.downloadChatModel,
				config.chatThreads, LOG::info);
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
			if (entity instanceof ServerPlayer victim && server != null) died(victim, source);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> ownerLeft(handler.getPlayer()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> ownerJoined(handler.getPlayer()));
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
		Path mindFile = brainFile().resolveSibling("mind.bin");
		try (InputStream in = Files.exists(mindFile) ? Files.newInputStream(mindFile) : XenMod.class.getResourceAsStream("/assets/xen/mind.bin")) {
			mind = in == null ? null : xen.mod.core.Mind.load(new java.io.DataInputStream(new java.io.BufferedInputStream(in)));
			if (mind != null) LOG.info("Xen 5.2's mind loaded ({} learning steps){}", mind.updates, Files.exists(mindFile) ? "" : " - trained in SimLife");
		} catch (IOException e) {
			LOG.warn("No Xen 2.0 mind ({}): Xens choose the old way", e.toString());
			mind = null;
		}
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
				} else if (mind != null && config.learn && mindTrained < mindExperiences * 8) {   // Xen 2.0: 8 lessons per choice it saw through
					mindTrained++;
					mind.learn(32, mindRandom);
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
	/** What Xens see, think, say and hear (for the Experimental tab's log). */
	public Journal journal;

	private void save() {
		solverMind.save();
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
		for (Companion c : companions) roster.remember(c, teamOf(c));
		roster.save();
	}

	private void stopping(MinecraftServer s) {
		arena.stop("the server is stopping.");
		for (Companion c : companions) if (c.player() != null && !c.inArena) rememberAway(c);   // they come back next time
		saveAway();
		for (Companion c : companions) roster.remember(c, teamOf(c));                          // (before they leave: all they know)
		roster.save();
		for (Companion c : new ArrayList<>(companions)) c.leave();
		running = false;
		if (trainer != null) trainer.interrupt();
		if (brain != null) save();
		chat.close();
	}

	// --------------------------------------------------------------------------------- world
	private void tick(MinecraftServer s) {
		arena.tick();
		runLater();
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
			try {
				Tribe.tickAll(this);                                          // tribes: who's in which, sharing, the night watch
			} catch (RuntimeException e) {
				LOG.warn("Xen tribes stumbled: {}", e.toString(), e);
			}
			if (config.evolution) evolve();
		}
		if (s.getTickCount() % 6000 == 3000) {                                // every five minutes: its team, its rules
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
			if (o.has("boss")) {
				Companion boss = companions.stream().filter(b -> b.name.equals(o.get("boss").getAsString())).findFirst().orElse(null);
				if (boss == null || boss.player() == null) continue;              // its boss isn't here: it stays away
				c.minion = true;
				c.boss = boss;
				boss.crew.minions.add(c);
			}
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

	/** Can this Xen hear what that player says? (With local chat only close by; else anywhere.) */
	private boolean hears(Companion c, ServerPlayer sender) {
		return c.player() != null && (!config.localChat || inRange(c.player(), sender));
	}

	/** Something said out loud by a player or a Xen: the Xens close by overhear it (rumors, plots: spies). */
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
		if (sender instanceof XenPlayer || !config.chat) return;
		overheardBy(sender, sender.getName().getString(), text);             // who's listening close by?
		if (config.journal && journal != null) journal.add(sender.getName().getString(), "says", text);
		lastChatNeed = System.currentTimeMillis();                     // someone is talking: wake the chat model up
		chat.warmUp();
		boolean toXen = false;
		for (Companion c : companions) {
			if (hears(c, sender) && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(c.name) + "\\b",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) toXen = true;
		}
		if (toXen && !chat.hasModel() && toldAboutModel.add(sender.getUUID())) {   // once: why its answers are simple
			sender.sendSystemMessage(Component.literal("[Xen] The chat model is " + chat.status()
					+ ". Until it's ready, Xens understand requests and answer simply.").withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		for (Companion c : companions) {
			if (!hears(c, sender)) continue;
			if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(c.name) + "\\b",
					java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) continue;
			chat.ask(sender.getName().getString(), text, c.name, request -> onServer(() -> c.request(request, sender, text)),
					reply -> server.execute(() -> c.say(reply)));
			return;
		}
		Companion byStart = null;                                              // "riv, come here": the start of its name will do
		String asked = null;
		for (Companion c : companions) {
			if (!hears(c, sender) || c.player().level() != sender.level()) continue;
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
						.then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
				.then(Commands.literal("spawn").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
								.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), 300))
								.then(Commands.argument("radius", IntegerArgumentType.integer(0, 30000))
										.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count"), IntegerArgumentType.getInteger(ctx, "radius"))))))
				.then(Commands.literal("minions").then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
						.executes(ctx -> minions(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
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
				.then(Commands.literal("knows").executes(ctx -> each(ctx, c -> c.knowledge.status())))

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
							ctx.getSource().sendSuccess(() -> Component.literal("(Xens won't listen to chat at all now. To keep them listening but turn off only the AI chat model: /xen chat on, then /xen set chatModel off.)")
									.withStyle(net.minecraft.ChatFormatting.GRAY), false);
							return r;
						})))
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
									for (String w : new String[] {"house", "base", "farm", "pen", "mob farm"}) b.suggest(w);
									return b.buildFuture();
								}).executes(ctx -> each(ctx, c -> c.name + ": " + xen.mod.talk.Chat.plainly("Plan: "
										+ c.builder.start(StringArgumentType.getString(ctx, "what")), "")))))
				.then(Commands.literal("goto").then(Commands.argument("where", StringArgumentType.greedyString())
						.executes(ctx -> each(ctx, c -> c.name + ": " + c.goTo(StringArgumentType.getString(ctx, "where"))))))
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
				.then(Commands.literal("log").executes(ctx -> {                // the journal, as a file (the Experimental tab copies it too)
					try {
						Path f = journal.save();
						ctx.getSource().sendSuccess(() -> Component.literal("Xen's journal saved: " + f), false);
					} catch (IOException e) {
						ctx.getSource().sendFailure(Component.literal("Couldn't save the journal: " + e.getMessage()));
					}
					return 1;
				}))
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

	private static final String[] TRAITS = {"fight", "build", "material", "tone", "temper", "bravery", "curiosity", "chattiness", "diligence", "kindness",
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
		if (config.ownLife && config.wants) c.mode = Companion.Mode.FREE;   // its own life: it plays its own game
		if (known != null && known.has("known")) {
			for (var u : known.getAsJsonArray("known")) c.known.add(java.util.UUID.fromString(u.getAsString()));
		}
		if (known == null) {                                                    // a new Xen: its own talents, its hobby
			c.skills.born();
			c.life.born();
		}
		if (known != null) {
			c.goals.load(known.has("likes") ? known.getAsJsonObject("likes") : null, known.has("goals") ? known.getAsJsonObject("goals") : null);
			if (known.has("skills")) c.mimic.load(known.getAsJsonObject("skills"));
			if (known.has("abilities")) c.skills.load(known.getAsJsonObject("abilities"));
			else c.skills.born();
			if (known.has("rumors")) c.rumors.load(known.getAsJsonObject("rumors"));
			if (known.has("life")) c.life.load(known.getAsJsonObject("life"));
			if (c.life.hobby == null) c.life.born();
			if (known.has("memories")) for (var m : known.getAsJsonArray("memories")) c.memories.add(m.getAsString());
			if (known.has("places")) c.places.load(known.getAsJsonObject("places"));
			if (known.has("chests")) c.storage.load(known.getAsJsonObject("chests"));
			if (known.has("knows")) c.knowledge.load(known.getAsJsonObject("knows"));
			if (known.has("portalMath") && known.get("portalMath").getAsBoolean()) c.knowledge.known.put("portal_math", Knowledge.How.TAUGHT);
			if (known.has("crops")) c.farmer.load(known.getAsJsonObject("crops"));
			if (known.has("taste")) c.taste.load(known.getAsJsonObject("taste"));
			if (known.has("mine")) {
				com.google.gson.JsonObject mine = known.getAsJsonObject("mine");
				c.chores.mineRecordY = mine.get("y").getAsInt();
				c.chores.mineRecordLeg = mine.get("leg").getAsInt();
				var d = net.minecraft.core.Direction.byName(mine.get("dir").getAsString());
				if (d != null) c.chores.mineRecordDir = d;
			}
			c.adventure.on = known.has("adventure") && known.get("adventure").getAsBoolean();
			if (known.has("band")) c.band = known.get("band").getAsString();
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
	private boolean full() {
		return config.maxXens > 0 && companions.stream().filter(c -> !c.minion).count() >= config.maxXens;
	}

	private long minionCount() {
		return companions.stream().filter(c -> c.minion).count();
	}

	private int summon(CommandContext<CommandSourceStack> ctx, String wanted) {
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
			deathMessages();
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
