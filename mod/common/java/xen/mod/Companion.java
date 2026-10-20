package xen.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Blocks;
import xen.mod.core.Brain;
import xen.mod.core.Emotions;
import xen.mod.core.Paths;
import xen.mod.core.Perception;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One Xen in the world: a real player driven by the shared brain. Every few ticks it senses, feels, decides and
 * acts; then it learns from what happened. On top of the brain it has a companion's instincts: swim up in water,
 * eat when hungry, fight back when a monster is within reach, do what it was asked, and follow its owner (or stay
 * where it was told).
 */
public final class Companion {
	public enum Mode { FOLLOW, STAY, FREE }

	/** -Dxen.debug=true logs every decision (for troubleshooting). */
	static final boolean DEBUG = Boolean.getBoolean("xen.debug");
	/** (Tests) Each game minute, a line in the log with how it's doing (-Dxen.stats=true). */
	static final boolean STATS = Boolean.getBoolean("xen.stats");
	/** For the tests: how far it went, how often its way went wrong, it paced, it was stuck. */
	double moved;
	int troubles, pacings, stucks;
	private Vec3 lastPos;

	static final Map<String, Float> ITEM_VALUE = Map.of("dirt", 0.05f, "cobblestone", 0.1f, "log", 1f, "coal", 1.5f,
			"raw_iron", 3f, "raw_gold", 4f, "diamond", 10f, "food", 0.3f);

	final XenMod mod;
	final MinecraftServer server;
	public final String name;
	public UUID owner;
	String ownerName;
	/** Who it follows: its owner, or whoever asked an ownerless Xen to follow them. */
	UUID leader;
	public Mode mode = Mode.FOLLOW;
	BlockPos anchor;
	XenPlayer player;
	Hands hands;
	final Perception.Senses senses = new Perception.Senses();
	final Emotions emotions;
	private float[] obs;
	private int lastAction;
	private float lastHealth, lastFood;
	private Map<String, Integer> lastItems = Map.of();
	private int decisions, respawnIn = -1;
	/** Hardcore: it died, and in a moment it's gone for good (ticks to go; -1: no). */
	private int goneIn = -1;
	private String goneHow = "";
	private BlockPos goneAt;
	private long lastChatter;
	private boolean wasNight;
	public String lastThought = "";
	boolean pillaring;                         // this decision: jump and place a block below (learned as a jump)
	boolean acted;                             // this decision: a chore already used its hands (placed, hit, gave)
	final Chores chores = new Chores(this);
	private Vec3 walkedFrom;
	private int stuck;
	private int lifeTicks;

	/** Ticks since it came into the world (this life). */
	int age() {
		return lifeTicks;
	}
	private float lifeReward;
	/** Its nature (genes) and how it looks. */
	final Personality personality;
	/** Its skin ({@link Looks#profile}); a real player's one in place of one of the mod's own, with "keep looking" on. */
	String skin;
	private boolean reskinning;
	/** Players it knows: its owner and whoever has talked to it. */
	final Set<UUID> known = new java.util.HashSet<>();
	/** How it does this generation (for evolution). */
	float genReward;
	long genTicks;
	int genDeaths;
	private long lastNote = -1_000_000;

	Companion(XenMod mod, MinecraftServer server, String name, UUID owner, String ownerName, Personality personality, String skin) {
		this.mod = mod;
		this.server = server;
		this.name = name;
		this.owner = owner;
		this.leader = owner;
		this.ownerName = ownerName;
		this.personality = personality;
		this.skin = skin;
		if (owner != null) known.add(owner);
		this.emotions = mod.brain.newBody();
		applyPersonality();
	}

	public XenPlayer player() {
		return player;
	}

	// ------------------------------------------------------------------------------- joining
	/** Join the server as a real player at a position. */
	void join(ServerLevel level, Vec3 at, float yaw) {
		if (!reskinning) habits.arrived();                              // (new here: a look around first)
		GameProfile profile = Looks.profile(name, skin);
		// What the server kept of it (its bag, health, where it was), like any player coming back: read before it joins.
		java.util.Optional<net.minecraft.nbt.CompoundTag> saved = server.getPlayerList().loadPlayerData(new net.minecraft.server.players.NameAndId(profile));
		XenPlayer p = null;
		if (saved.isPresent()) {
			try (var reporter = new net.minecraft.util.ProblemReporter.ScopedCollector(XenMod.LOG)) {
				var in = net.minecraft.world.level.storage.TagValueInput.create(reporter, server.registryAccess(), saved.get());
				ServerLevel was = at != null ? level : in.read("Dimension", net.minecraft.world.level.Level.RESOURCE_KEY_CODEC).map(server::getLevel).orElse(level);
				p = new XenPlayer(server, was, profile);
				p.load(in);
				if (at == null && p.isDeadOrDying()) p.setHealth(p.getMaxHealth());
			} catch (RuntimeException e) {
				XenMod.LOG.warn("{}'s saved things couldn't be read: {}", name, e.toString());
				p = null;
			}
		}
		if (p == null) {
			p = new XenPlayer(server, level, profile);
			if (at == null) {                                               // nothing kept: the world's spawn, on the ground
				BlockPos spawn = level.getRespawnData().pos();
				at = XenMod.surface(level, spawn.getX(), spawn.getZ());
				if (at == null) at = Vec3.atBottomCenterOf(spawn);
			}
		}
		p.companion = this;
		p.seenCredits = true;                                           // (no credits to watch: the End's exit portal just takes it home)
		FakeConnection connection = new FakeConnection(() -> server.execute(this::leave));
		connection.body = p;
		server.getPlayerList().placeNewPlayer(connection, p, new CommonListenerCookie(profile, 0, p.clientInformation(), false));
		if (at != null) p.teleportTo(level, at.x, at.y, at.z, Set.<Relative>of(), yaw, 0, true);
		player = p;
		hands = new Hands(p);
		lastHealth = p.getHealth();
		lastFood = p.getFoodData().getFoodLevel();
		lastItems = items();
		obs = null;
		mod.joinTeam(this);
	}

	void leave() {
		if (player != null && !inArena) mod.roster.remember(this, mod.teamOf(this));   // all it knows, kept for next time
		if (player != null && lifeTicks > 0 && !inArena) {             // the life so far goes into lives.csv too
			mod.logLife(name, player.level().getGameTime() / 24000, lifeTicks, lifeReward, "left");
			lifeTicks = 0;
		}
		if (player != null && server.getPlayerList().getPlayer(player.getUUID()) == player) {   // dead ones too (waiting to respawn)
			hands.stop();
			server.getPlayerList().remove(player);
		}
		player = null;
		respawnIn = -1;
		mod.forget(this);
	}

	// -------------------------------------------------------------------------------- living
	/** A minion (see {@link Crew}): it doesn't load the world, and takes orders from its boss. */
	boolean minion;
	Companion boss;
	/** Its minions, if it has any, and the work it gives them. */
	final Crew crew = new Crew(this);
	private net.minecraft.world.level.Level minionLevel;

	int respawning() {
		return respawnIn;
	}

	/**
	 * A minion goes its own way (it chose to: it didn't trust its boss, or wanted more for itself): a free Xen now,
	 * living its own life and loading the world around it. turnOn: the boss it now fights.
	 */
	void goFree(String says, ServerPlayer turnOn) {
		if (!minion) return;
		Companion was = boss;
		say(says);
		journal("does", "leaves " + (was == null ? "its boss" : was.name) + (turnOn != null ? " and turns on them" : "") + " (its own choice)");
		XenMod.LOG.info("{} leaves its boss {}{}", name, was == null ? "?" : was.name, turnOn != null ? " and turns on them" : "");
		if (was != null) was.crew.minions.remove(this);
		minion = false;
		boss = null;
		owner = null;
		ownerName = "";
		mode = Mode.FREE;
		if (player != null) Crew.startLoading(player);
		if (turnOn != null) {
			trust(turnOn.getUUID(), -0.6f);
			chosenFoe = turnOn;
			Tribe t = tribe();
			if (t != null) t.enemies.put(turnOn.getUUID(), player.level().getGameTime() + 20 * 60);
		}
		mod.roster.remember(this, mod.teamOf(this));
	}

	/** -Dxen.dumpFacts=true: every 20 s each Xen writes what it knows of itself (XenTalk's facts) to the world's xen/talkfacts.txt. */
	static final boolean DUMP_FACTS = Boolean.getBoolean("xen.dumpFacts");

	/** Is it in the world someone keeps loaded (a minion only lives there)? */
	boolean awake() {
		return player != null && (!minion || ((ServerLevel) player.level()).isPositionEntityTicking(player.blockPosition()));
	}

	void tick() {
		tickInner();
		gesture();                                                      // last: nothing this tick undoes it
		if (player != null) {
			hands.keepTool();                                           // (mid-dig: the tool it picked is what it holds, whatever else went on this tick)
			instinct.tick();                                            // (and a clear danger over everything)
		}
	}

	/** How much it believes in itself (see {@link SelfBelief}). */
	final SelfBelief belief = new SelfBelief(this);

	/** How much food it likes to keep on it: 4 to 10 (a glutton and a hard worker more). */
	int foodReserve() {
		return Math.max(3, Math.min(10, Math.round(4 + 4 * personality.sin(xen.mod.core.Sins.GLUTTONY) + 3 * personality.diligence)));
	}

	/** Its instinct: a clear danger (no air, lava, fire), every tick, before everything (see {@link Instinct}). */
	final Instinct instinct = new Instinct(this);

	/** Its head is in a block (sand fell on it, it was pushed in, it woke up in a wall): it breaks it, like a player. */
	private void digOut() {
		BlockPos head = BlockPos.containing(player.getEyePosition());
		var level = player.level();
		if (level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return;
		if (hands.digging(head)) return;                                // (already at it)
		walker.stop();
		hands.stop();
		if (hands.mine(head)) {
			goals.instant = "digging out of a wall";
			if (DEBUG) XenMod.LOG.info("[xen debug] {} digs out of {} at {}", name, level.getBlockState(head), head);
		}
	}

	/** A crouch wave going on (a player's hello: down, up, down, up): ticks left. */
	private int crouchWave;

	/** Crouch at someone this many times, a player's way (down 4 ticks, up 4 ticks), whatever else it's doing. */
	void crouchWave(int times) {
		crouchWave = Math.max(crouchWave, times * 8 + 1);
	}

	boolean waving() {
		return crouchWave > 0;
	}

	private void gesture() {
		if (crouchWave <= 0 || player == null) return;
		if (player.isPassenger()) {                                     // (crouching gets you out of a boat: not now)
			crouchWave = 0;
			return;
		}
		crouchWave--;
		player.setShiftKeyDown(crouchWave > 0 && crouchWave % 8 >= 4);
	}

	private void tickInner() {
		if (player == null) return;
		if (goneIn >= 0) {
			if (--goneIn < 0) goneForGood();
			return;
		}
		if (respawnIn >= 0) {
			if (--respawnIn < 0) respawn();
			return;
		}
		if (minion) {
			if (player.level() != minionLevel) {                         // (new world, portal: off the loading again)
				minionLevel = player.level();
				Crew.stopLoading(player);
			}
			if (!awake()) return;                                        // frozen till someone comes by
		}
		crew.tick();
		lifeTicks++;
		genTicks++;
		long t0 = Perf.now();
		hands.tick();
		Perf.add("hands", t0);
		t0 = Perf.now();
		walker.tick();                                                  // on its way somewhere: the keys for the next step
		Perf.add("walking", t0);
		rider.tick();                                                   // in a boat or on a horse: steering
		habits.tick();                                                  // its first look around (eyes on things), frustration fading
		pace.tick();                                                    // a tool that just broke; its eyes on its hands
		t0 = Perf.now();
		eyes.tick();                                                    // a yes or no for every block it can see
		t0 = Perf.now();
		vision.tick();                                                  // its view as a picture, and what it makes of it
		Perf.add("vision", t0);
		Perf.add("eyes", t0);
		if (STATS) stats();
		nether.tick();                                                  // portals: where it came from, gold in the Nether
		if (player.tickCount % 20 == 7) noticeSheep();                  // (where the sheep were: for wool, for a bed)
		if (player.tickCount % 6000 == 11) {                            // every five minutes, old grudges fade a little (a kind one's faster, a wrathful one's slower)
			float fade = 0.02f + 0.05f * personality.kindness - 0.02f * personality.sin(xen.mod.core.Sins.WRATH);
			if (fade > 0) trust.replaceAll((k, v) -> v < 0 ? Math.min(0f, v + fade) : v);
		}
		t0 = Perf.now();
		if (player.tickCount % 10 == 0) {
			if (player.onGround() && !player.isInWater()) lastDry = player.blockPosition();   // (the last dry ground under its feet: to swim back to)
			noteKills();                                                 // (something it hit died: its drops are its own)
			tips.tick();                                                 // (a tip it took, or didn't: how did that go?)
			if (DUMP_FACTS && player.tickCount % 400 == 0 && !minion) {       // (-Dxen.dumpFacts=true: what it knows of itself, for training XenTalk)
				try {
					java.nio.file.Files.writeString(mod.brainFile().resolveSibling("talkfacts.txt"), xen.mod.talk.XenTalk.facts(me, "", 0, "") + "\n",
							java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
				} catch (java.io.IOException ignored) {
					// (a test aid only)
				}
			}
			if (player.tickCount % 100 == 0) {
				rules.tick();                                            // (stuck? what's true now, for its rules)
				intent.tick();                                           // (a promise it can keep now)
			}
			biomes.tick();                                               // (the biome it's in: a cave biome is a cave)
			belief.tick(player.level().getGameTime());                   // (a bad day fades)
			places.tick();                                               // the way it walked, remembered
			totems();
			rumors.look();                                               // who's that? (a shock, a warning to pass on)
			caves.scan();                                                // a cave in sight?
			Sights.look(this);                                           // what's that over there? (a ravine, a river: it was shown)
			structures.look();                                           // a village, a mineshaft, a temple?
		}
		Perf.add("looking around", t0);
		skills.checkSpar();
		life.tick();                                                    // a nod, a shake of the head, a wave; a new day
		if (player.tickCount % 1200 == 600) {
			skills.think(lostFight, rumors.heardOfStronger());          // does it want to train?
			lostFight = false;
		}
		if (mode == Mode.FOLLOW && leader != null) nether.watchLeader(server.getPlayerList().getPlayer(leader));
		if (player.tickCount % 20 == 5) farmer.look();                  // (farmland by water: that's how farms work)
		if (hurtNow && player.getLastHurtByMob() != null) learnFromHurt(player.getLastHurtByMob());
		dontStareAtEndermen();
		hurtNow = player.getHealth() < tickHealth;
		if (hurtNow && player.getLastHurtByMob() != null && !WorldSenses.sees(player, hands.yaw, hands.pitch, player.getLastHurtByMob())) {
			confusion.surprise(0.12f);                                  // a hit from out of view: a moment's "what was that?"
		}
		if (hurtNow && player.getLastHurtByMob() instanceof net.minecraft.world.entity.monster.Enemy && random.nextFloat() < 0.25f) {
			rage.provoke(1, "hit by a " + BuiltInRegistries.ENTITY_TYPE.getKey(player.getLastHurtByMob().getType()).getPath().replace('_', ' '));   // (now and then a monster's hit gets to it)
		}
		if (player.tickCount % 20 == 13) confusion.second();
		if (player.tickCount % 20 == 9) rage.second();                  // (anger cooling; bloodlust that picks someone)
		if (player.tickCount % 20 == 11) {
			hunch.second();
			hunch.met();
			planner.think();
			director.think();
		}
		if (player.tickCount % 1200 == 17) aversions.fade();
		if (player.tickCount % 40 == 21) diamondsWatch();
		if (player.tickCount % 100 == 37) ideas.tick();
		teaching.watchCrouch();
		if (player.tickCount % 20 == 3) teaching.tick();
		if (hurtNow && !inArena) {                                      // its tribe may come to guard it
			Tribe hurtTribe = tribe();
			if (hurtTribe != null) hurtTribe.attackedAt.put(this, player.level().getGameTime());
		}
		tickHealthBefore = tickHealth;
		tickHealth = player.getHealth();
		if (player.tickCount % 40 == 0) readSigns();                    // signs it can see: it reads them, like anyone
		if (player.tickCount % 20 == 10) giveUpChase();                // chasing someone who keeps away: not forever
		if (player.tickCount % 40 == 20) {
			wearArmor();
			hands.shieldToOffhand(fighting);                             // a shield lives in the off hand
		}
		t0 = Perf.now();
		mimic.watch();                                                  // what are the players it sees doing?
		solver.watch();                                                 // and how they get out of holes
		antics.watch();
		reactions.look();                                               // someone new in view? (it reacts, in its own way)
		Perf.add("watching players", t0);
		script.tick(hurtNow);                                           // its owner's own rules
		if (mimic.clutchTick()) return;                                 // falling: a water clutch, this very tick
		if (tactics.pearlTick()) return;                               // flung, or off the edge: an ender pearl
		if (hurtNow && !inArena && player.getLastHurtByMob() instanceof ServerPlayer by && by != player
				&& player.tickCount - player.getLastHurtByMobTimestamp() < 5) {
			hitBy(by);
		}
		if (player.isInWall()) {                                        // its head in a block: out first, whatever it was doing
			if (player.tickCount % 5 == 0) digOut();
			if (hands.busy()) return;
		}
		if (hands.busy() && hands.interruptible() && player.isUnderWater() && player.getAirSupply() < player.getMaxAirSupply() / 2) {
			hands.stop();                                                // half its air gone: whatever it's doing, air first
		}
		if (hands.busy() && !((hurtNow || fighting) && hands.interruptible())) return;   // being hit cuts mining and walking short
		if (player.tickCount % 40 == 0) watched = watchedByAPlayer();
		int every = Math.max(1, mod.config.decisionTicks / 5) * (watched || inArena || hurtNow ? 1 : 3);   // far from any player: it thinks less often
		if (++decisions % every != 0 && !fighting) return;   // in a fight, every tick
		t0 = Perf.now();
		decide();
		Perf.add("choosing", t0);
	}

	private Perception.Body body() {
		Perception.Body b = new Perception.Body();
		b.health = player.getHealth();
		b.hunger = player.getFoodData().getFoodLevel();
		b.night = player.level().isDarkOutside();
		b.burning = player.isOnFire();
		b.hurt = Math.min(1f, Math.max(0f, lastHealth - player.getHealth()) / 5f);
		b.pitch = hands.pitch;
		Map<String, Integer> items = items();
		b.blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
		b.food = items.getOrDefault("food", 0);
		b.inWater = player.isInWater();
		b.inLava = player.isInLava();
		return b;
	}

	/** Counts of what it carries, in the categories rewards are about. */
	Map<String, Integer> items() {
		Map<String, Integer> out = new LinkedHashMap<>();
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			out.merge(itemKey(s), s.getCount(), Integer::sum);
		}
		return out;
	}

	/** The category an item counts as (for rewards and requests). */
	String itemKey(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return s.has(DataComponents.FOOD) ? (Survival.risky(n) != null ? n : "food")   // (rotten flesh, spider eyes, raw chicken: not real food, only when starving)
				: n.equals("cobbled_deepslate") ? "cobblestone"
				: n.endsWith("_log") || WorldSenses.isNetherStem(n) ? "log"
				: n.equals("iron_ore") ? "raw_iron" : n.equals("gold_ore") ? "raw_gold" : n;
	}

	private float lastHealthBefore = 20;

	/**
	 * Xen Ex1, twice a second: what it senses (the recorder's 119 senses), what it makes of it, and what it learns. A
	 * hurt teaches it that the last two seconds were dangerous; a calm moment now and then, that the moment before was
	 * safe. It learns what hurts by itself (lava, falls, monsters): nothing is written in about any of them.
	 */
	private void ex1Step(float hurt) {
		var ex1 = mod.ex1;
		if (ex1 == null || !mod.config.ex1 || inArena) {
			ex1Out = null;
			return;
		}
		long now = player.level().getGameTime();
		if (hurt > 0.5f && mod.config.learn && now - ex1HurtAt >= 20) {         // (a hurt teaches once a second at most: a long burn isn't fifty lessons)
			ex1HurtAt = now;
			for (var o : ex1Recent) ex1.learn(o, true, 0.006f);
			ex1Recent.clear();
		}
		if (++ex1Ticks % 2 != 0) return;
		long tEx = Perf.now();
		ex1Out = ex1.run(Ex1Senses.sense(this));
		Perf.add("choosing: Ex1 net", tEx);
		ex1Recent.addLast(ex1Out);
		if (ex1Recent.size() > 5) {
			var old = ex1Recent.removeFirst();                              // (two and a half seconds on, nothing hurt: that moment was safe)
			if (now - ex1HurtAt > 60 && mod.config.learn) ex1.learn(old, false, 0.004f);
		}
	}

	private void decide() {
		Perception.Body body = body();
		Perception.Sight sight = WorldSenses.sense(player, hands.yaw, hands.pitch, decisions, body);
		float[] next = senses.perceive(sight);
		Map<String, Integer> items = items();
		if (obs != null) {
			float reward = 0;
			for (var e : ITEM_VALUE.entrySet()) {
				int had = lastItems.getOrDefault(e.getKey(), 0);
				int gained = items.getOrDefault(e.getKey(), 0) - had;
				boolean common = e.getKey().equals("dirt") || e.getKey().equals("cobblestone");
				for (int i = 0; i < gained; i++) reward += common ? e.getValue() * 16f / (16f + had + i) : e.getValue();   // a stack of dirt is plenty
			}
			float food = player.getFoodData().getFoodLevel();
			if (food > lastFood && lastFood < 14) reward += 0.5f * (food - lastFood) / 6f;
			float harm = Math.max(0f, lastHealth - player.getHealth()) / 20f;
			if (!inArena) mod.learn(obs, lastAction, reward, harm, next, false, emotions, name);
			lifeReward += reward;
			genReward += reward;
			if (items.getOrDefault("diamond", 0) > lastItems.getOrDefault("diamond", 0)) {
				chatter(personality.say("diamonds"), true);
				antics.celebrate();
				note("Diamonds here!");
			}
		}
		lastHealthBefore = lastHealth;
		lastHealth = player.getHealth();
		lastFood = player.getFoodData().getFoodLevel();
		lastItems = items;
		pillaring = acted = false;
		walker.touched = false;
		goals.instant = "";
		if (!inArena) goals.everyDecision();
		perceived = next;
		ex1Step(Math.max(0f, lastHealthBefore - player.getHealth()));
		long tIn = Perf.now();
		Action instinct = instinct();
		Perf.add("choosing: instincts (building, chores, ways)", tIn);
		Brain.Thought thought = mod.brain.decide(next, emotions, mod.config.learn);
		Action sensible = instinct == null ? sensible(Action.values()[thought.action]) : null;
		if (sensible != null && sensible.ordinal() == thought.action) sensible = null;
		int action = instinct != null ? instinct.ordinal() : sensible != null ? sensible.ordinal() : thought.action;
		lastThought = instinct != null ? (chores.busy() ? (chores.own ? "Doing my own errand (" : "Doing what I was asked (") + chores.doing + "): " : "Instinct: ")
				+ (pillaring ? "climb up" : instinct.verb) + "." : sensible != null ? "Nothing worth doing there, so: " + sensible.verb + "."
				: thought.text;
		hands.glance = null;
		boolean working = chores.busy() || builder.busy() || storage.busy() || farmer.on || fisher.on || nether.busy() || adventure.on || commandedTo != null
				|| skills.trainingNow();
		if (instinct == null && working) {                                   // mid-task, nothing this very moment: eyes stay on the work
			action = Action.IDLE.ordinal();
			lastThought = "A moment in what it's doing (" + goals.instant + ").";
		} else if (instinct == null && !inArena && !fighting) {              // nothing to do this moment: what a person does then
			Action human = humanIdle(Action.values()[thought.action]);
			if (human.ordinal() != action || human == Action.IDLE) {
				action = human.ordinal();
				if (human == Action.IDLE || goals.instant.equals("fidgeting")) lastThought = idleThought;
			}
		}
		if (goals.instant.isEmpty()) goals.instant = mode == Mode.FREE ? "exploring" : "looking around";
		if (hands.watching == null && watchFor != null && watchUntil > player.tickCount) {   // "watch me": eyes on them
			ServerPlayer w = server.getPlayerList().getPlayer(watchFor);
			if (w != null && w.level() == player.level()) hands.watching = w;
		}
		if (!fighting && action != Action.FORWARD.ordinal() && action != Action.JUMP.ordinal()) run(false);   // stopped: no more running
		if (!walker.touched) walker.stop();                                // doing something else now: it lets go of the keys
		if (!pillaring && !acted) hands.start(Action.values()[action]);
		if (mod.config.journal) {
			String thinking = Action.values()[action].verb + " - " + lastThought + (goals.instant.isEmpty() ? "" : " [" + goals.instant + "]");
			if (!thinking.equals(journaledThought)) journal("thinks", thinking);
			journaledThought = thinking;
			if (player.tickCount - journaledSightAt > 100) {                  // what it sees, every five seconds when it changes
				String seen = senses.describe();
				if (!seen.equals(journaledSight)) journal("sees", seen + String.format(java.util.Locale.ROOT, " (at %d %d %d, health %.0f, food %d)",
						player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.getHealth(), player.getFoodData().getFoodLevel()));
				journaledSight = seen;
				journaledSightAt = player.tickCount;
			}
		}
		if (DEBUG) XenMod.LOG.info("[xen debug] {} at {} ground={} yaw={} pitch={} -> {} ({})", name, player.position(), player.onGround(),
				hands.yaw, hands.pitch, Action.values()[action], lastThought);
		obs = next;
		lastAction = action;
		react();
		talker.tick(fighting);
	}

	/** What its genes do to how it feels (again after /xen style changes them). */
	void applyPersonality() {
		emotions.baseCaution = mod.brain.newBody().baseCaution * personality.cautionScale();   // brave ones mind fear less
		emotions.curiosityDrive = personality.curiosityScale();                            // curious ones try new things more
	}

	/**
	 * The brain's own choice, checked the way a player would: it mines only what's worth it (wood, ore its pickaxe can
	 * mine, stone when it needs blocks) and never straight down under itself; it swings only at something hostile in
	 * front of it; it places blocks only when it's scared (to wall off a mob or lava); and next to the friend it follows
	 * (or where it was told to stay) it doesn't wander off, unless something scares it. Otherwise it waits and watches
	 * its friend, or looks around. Null: the brain's choice is fine.
	 */
	/** Is a real player (not a Xen) within 96 blocks? If not, it lives a bit slower (less work for the server). */
	boolean watched = true;

	private boolean watchedByAPlayer() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer || p.level() != player.level()) continue;
			if (p.distanceToSqr(player) < 96 * 96) return true;
		}
		return false;
	}

	private Action sensible(Action a) {
		switch (a) {
			case JUMP -> {                                               // only with something to jump onto (or in water)
				if (player.isInWater() || stepAhead()) return null;
				return emotions.fear > 0.5f || mode == Mode.FREE ? Action.FORWARD : idle();
			}
			case FORWARD, BACK, LEFT, RIGHT -> {
				if (emotions.fear > 0.5f || mode == Mode.FREE || random.nextFloat() < 0.1f) return null;   // a step now and then
				return idle();
			}
			case MINE -> {
				ServerLevel level = (ServerLevel) player.level();
				BlockPos t = hands.target();
				int cat = cat(level, t);
				var items = items();
				int blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
				int tier = crafter.pickTier();
				boolean worth = cat == Blocks.LOG
						|| cat >= Blocks.COAL && cat <= Blocks.DIAMOND && tier >= Crafter.tierFor(cat)
						|| cat == Blocks.STONE && tier >= 1 && blocks < 32
						|| (cat == Blocks.DIRT || cat == Blocks.GRASS) && blocks < 4;
				boolean down = hands.pitch < 0;
				if (worth && !(down && (cat == Blocks.STONE || cat == Blocks.DIRT || cat == Blocks.GRASS))) return null;
				if (hands.pitch != 0) return hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP;
				return idle();
			}
			case ATTACK -> {
				return hostileInFront() ? null : idle();
			}
			case PLACE -> {                                              // a block put down needs a reason: a monster on it (a pillar up), sinking out of air
				return emotions.fear > 0.5f && danger() > 0.3f || player.isUnderWater() && player.getAirSupply() < player.getMaxAirSupply() / 2 ? null : idle();
			}
			default -> {
				return null;
			}
		}
	}

	/**
	 * Diamonds in sight that its pickaxe can't take yet (stone breaks them for nothing): like a player, it remembers
	 * where, says so, and goes back for them once it has an iron pickaxe.
	 */
	private void diamondsWatch() {
		if (inArena || player == null || minion || player.isSpectator()) return;
		BlockPos known = places.get("diamonds");
		if (crafter.pickTier() < 3) {
			BlockPos d = eyes.nearest("diamond", 24, null);
			if (d == null || known != null && known.closerThan(d, 12)) {
				workUpToDiamonds(known);
				return;
			}
			places.remember("diamonds", d);
			chatter(pick3("Diamonds! I need an iron pickaxe for those. I'll be back.", "ooh diamonds... can't mine them with this pickaxe. noted",
					"Diamonds here! Iron pickaxe first, then they're mine."), false);
			journal("notes", "diamonds at " + d.toShortString() + " (its pickaxe can't take them yet)");
			return;
		}
		if (known == null || mode != Mode.FREE || chores.busy() || builder.busy() || fightingNow() || !known.closerThan(player.blockPosition(), 256)
				|| player.level().isDarkOutside() && player.level().canSeeSky(player.blockPosition())) return;
		String plan = chores.mineAt(known, 3);
		if (!plan.startsWith("You will")) return;
		chores.own = true;
		places.forget("diamonds");
		chatter(pick3("Got an iron pickaxe: back to those diamonds!", "ok, time for those diamonds I saw", "Iron pickaxe! Now for the diamonds."), false);
		journal("does", plan);
	}

	private long diamondPlanAt;

	/**
	 * Diamonds it knows of right here, and a pickaxe too weak for them: like a player, it doesn't walk off and forget
	 * them; it works its way up on the spot, one step at a time: stone for a stone pickaxe, iron from round about, a
	 * furnace and smelting, the iron pickaxe (its crafter makes that as soon as it has the ingots), then the diamonds.
	 */
	private void workUpToDiamonds(BlockPos known) {
		if (known == null || mode != Mode.FREE || chores.busy() || builder.busy() || fightingNow() || asked != null
				|| !known.closerThan(player.blockPosition(), 64) || player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
		long now = player.level().getGameTime();
		if (now - diamondPlanAt < 20 * 30) return;
		diamondPlanAt = now;
		var items = items();
		int tier = crafter.pickTier(), raw = items.getOrDefault("raw_iron", 0), ingots = items.getOrDefault("iron_ingot", 0);
		if (tier < 1) return;                                                       // (no pickaxe at all: wood first, its usual way)
		String plan, why;
		if (tier >= 2 && raw + ingots >= 3) {
			if (ingots >= 3) return;                                                // (its crafter makes the pickaxe)
			plan = chores.smelt();
			why = "smelting iron for a pickaxe";
		} else if (tier >= 2) {
			plan = chores.mine(player.blockPosition().getY(), "iron", 3 - raw - ingots);
			why = "iron for a pickaxe";
		} else {
			plan = chores.gather("iron", 3);                                     // (a stone pickaxe first: its gather sees to that)
			why = "a stone pickaxe, then iron";
		}
		if (!plan.startsWith("You will")) {
			journal("thinks", "diamonds at " + known.toShortString() + ", but for now: " + plan);
			return;
		}
		chores.own = true;
		journal("does", "works up to the diamonds at " + known.toShortString() + ": " + why + " (" + plan + ")");
		if (now - diamondSaidAt > 20 * 60 * 3) {
			diamondSaidAt = now;
			chatter(tier >= 2 ? pick3("Iron first, right here. Then those diamonds.", "Okay: iron, furnace, iron pickaxe, diamonds.", "I'm not leaving these diamonds. Iron first.")
					: pick3("Stone pickaxe first, then iron, then the diamonds.", "One step at a time: stone, iron, then diamonds.", "I'll work my way up to those diamonds."), false);
		}
	}

	private long diamondSaidAt = -1_000_000;

	/** A block right ahead to step up onto (with room to jump)? */
	private boolean stepAhead() {
		var s = senses.last;
		if (s == null) return false;
		int[] f = Perception.forward(hands.yaw);
		return Blocks.SOLID[s.near(f[0], 0, f[1])] && !Blocks.SOLID[s.near(f[0], 1, f[1])] && !Blocks.SOLID[s.near(f[0], 2, f[1])]
				&& !Blocks.SOLID[s.near(0, 2, 0)];
	}

	/** Nothing to do: like a player waiting, it watches its friend if they're near, or looks around now and then. */
	private Vec3 idleLook;
	private long idleLookUntil;
	private String idleThought = "";

	/**
	 * A moment with nothing to do (no goal step, no chore, no danger). A player then doesn't strafe, look up and down,
	 * eat nothing or put a block down anywhere: they look at whoever is there, glance about, and get on with the next
	 * thing. What its learning mind picks it does only when it makes sense right here (a monster in front, hungry with
	 * food, a log or ore right in front of it, getting away when scared, swimming).
	 */
	private Action humanIdle(Action brain) {
		if (brain == Action.ATTACK && hostileInFront()) return brain;
		if (brain == Action.EAT && player.getFoodData().getFoodLevel() < 17 && hands.canEat()) return brain;
		if (brain == Action.MINE && sensible(Action.MINE) == null) return brain;
		if (player.isInWater() || emotions.fear > 0.6f) {
			Action s = sensible(brain);
			return s == null ? brain : s;
		}
		Action hobby = life.idle();                                    // free time: its hobby, its dog
		if (hobby != null) return hobby;
		String gift = life.gift();                                     // a present for a close friend
		if (gift != null && chores.busy()) return chores.next();
		long now = player.tickCount;
		ServerPlayer near = personToWatch();
		if (near != null) {
			idleLook = near.getEyePosition();
			idleThought = "Nothing to do this moment: I'm watching " + near.getName().getString() + ".";
		} else if (idleLook == null || now > idleLookUntil) {
			idleLookUntil = now + 40 + random.nextInt(100);
			idleLook = somethingToLookAt();
			idleThought = "Nothing to do this moment: looking around.";
			if (g1x.lookWhereUnseen()) {                                     // G1X: the way it knows least about, it looks at (active perception)
				idleLook = null;
				idleThought = "Nothing to do this moment: having a look where it hasn't seen.";
			}
		}
		hands.glance = idleLook;
		Action fidget = antics.fidget(near);                          // standing about a while: a jiggle, a hop, its hotbar...
		if (fidget != null) idleThought = "Nothing to do for a while: fidgeting.";
		return fidget != null ? fidget : Action.IDLE;
	}

	/** Who a player standing here would look at: its friend first, else whoever is closest (and in sight). */
	private ServerPlayer personToWatch() {
		ServerPlayer best = null;
		double bestD = 12;
		for (ServerPlayer p : ((ServerLevel) player.level()).players()) {
			if (p == player || p.isSpectator()) continue;
			double d = p.distanceTo(player) - (p.getUUID().equals(leader) || p.getUUID().equals(owner) ? 4 : 0);
			if (d < bestD && player.hasLineOfSight(p)) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** Something to rest its eyes on: an animal or a mob close by, or a spot out over the land. */
	private Vec3 somethingToLookAt() {
		var around = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(14),
				e -> e != player && e.isAlive() && !(e instanceof ServerPlayer) && player.hasLineOfSight(e)
						&& !(Tactics.enderman(e) && knowledge.knows("endermen")));   // (never an enderman in the eyes)
		if (!around.isEmpty() && random.nextFloat() < 0.6f) return around.get(random.nextInt(around.size())).getEyePosition();
		double a = Math.toRadians(player.getYRot() + (random.nextFloat() - 0.5f) * 160f);
		return player.getEyePosition().add(-Math.sin(a) * 10, (random.nextFloat() - 0.6f) * 4, Math.cos(a) * 10);
	}

	private Action idle() {
		ServerPlayer friend = leader == null ? null : server.getPlayerList().getPlayer(leader);
		if (friend != null && friend.level() == player.level() && friend.distanceTo(player) < 16) {
			hands.watching = friend;
			return Action.IDLE;
		}
		return random.nextFloat() < 0.2f ? (random.nextBoolean() ? Action.TURN_LEFT : Action.TURN_RIGHT) : Action.IDLE;
	}

	private final java.util.Random random = new java.util.Random();

	private boolean hostileInFront() {
		double reach = player.entityInteractionRange() + 0.5;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(reach), this::hostile)) {
			if (player.distanceTo(e) <= reach && player.hasLineOfSight(e)) return true;
		}
		return false;
	}

	/**
	 * Is this a mob to fight? One that's after it or its owner, or a monster that attacks on sight. It knows how mobs
	 * behave: endermen, piglins, wolves, bees, golems and the like leave you alone unless you provoke them (so it
	 * doesn't), spiders are calm in daylight, and villagers, golems and pets are never hit.
	 */
	boolean hostile(LivingEntity e) {
		if (e == player || !e.isAlive() || e instanceof ServerPlayer) return false;
		if (e instanceof AbstractVillager || e instanceof TamableAnimal t && t.isTame()) return false;
		ServerPlayer o = owner == null ? null : server.getPlayerList().getPlayer(owner);
		if (e instanceof Mob m && m.getTarget() != null && (m.getTarget() == player || m.getTarget() == o)) {
			return !(e instanceof AbstractGolem);                    // a golem after it: better run than fight
		}
		if (!(e instanceof Enemy) || e instanceof NeutralMob) return false;
		String n = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		return switch (n) {
			case "piglin", "spider", "cave_spider" -> false;           // calm until provoked (or in the dark: then they target it)
			case "creaking" -> !knowledge.knows("creaking");            // it can't be hurt (only its heart can): once it knows, it keeps away
			default -> true;
		};
	}

	/**
	 * Under water with a roof over its head (a flooded tunnel, an aquifer it dug into): swimming straight up only bumps
	 * the roof, so it swims to the nearest air it can get to (back the way it came, most often), like a player would.
	 */
	private Action toAir() {
		ServerLevel level = (ServerLevel) player.level();
		BlockPos head = BlockPos.containing(player.getEyePosition());
		for (int k = 1; k <= 6; k++) {                                // open water over it: up is the way
			var s = level.getBlockState(head.above(k));
			if (s.getFluidState().isEmpty() && s.getCollisionShape(level, head.above(k)).isEmpty()) return null;
			if (s.getFluidState().isEmpty()) break;
		}
		BlockPos start = player.blockPosition();
		boolean tall = true;
		BlockPos step = stepToAir(level, start, true);                 // a way its whole body fits (two blocks high)...
		if (step == null) {
			step = stepToAir(level, start, false);                     // ...or through gaps one block high, swimming flat
			tall = false;
		}
		if (step == null) return airPocket(level, start);
		goals.instant = "swimming to air";
		hands.steer = Vec3.atBottomCenterOf(step);
		if (step.getY() < start.getY()) {                              // the way out starts down (under the edge of the roof): it dives
			player.setSprinting(false);
			player.setShiftKeyDown(true);
			diving = true;
			hands.face(Vec3.atCenterOf(step).add(0, -1, 0));
			return Action.IDLE;
		}
		player.setShiftKeyDown(false);
		if (step.getY() == start.getY()) {                             // along: no jumping into the roof
			boolean low = !tall || !swimmable(level, step.above());
			player.setSprinting(low);                                  // (a gap one block high: only swimming flat fits through)
			if (!low && !swimmable(level, step.above(2))) {             // a roof right over it there: keep low
				player.setShiftKeyDown(true);
				diving = true;
			}
			hands.face(Vec3.atCenterOf(step).add(0, low ? -0.4 : 0, 0));
			return Action.FORWARD;
		}
		player.setSprinting(false);                                    // up: hold space
		hands.face(Vec3.atCenterOf(step));
		return Action.JUMP;
	}

	/** No way to air near, under a roof, short of breath: a door put down beside it holds no water (an air pocket, a player's trick). */
	private Action airPocket(ServerLevel level, BlockPos feet) {
		if (player.getAirSupply() > player.getMaxAirSupply() * 0.5 || level.getGameTime() - pocketAt < 60) return null;
		java.util.function.Predicate<ItemStack> door = s -> s.getItem() instanceof net.minecraft.world.item.DoubleHighBlockItem
				&& ((net.minecraft.world.item.BlockItem) s.getItem()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
		if (!has(door)) return null;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos at = feet.relative(d);
			if (!level.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER) || !level.getBlockState(at).canBeReplaced()
					|| !level.getBlockState(at.above()).canBeReplaced() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) continue;
			if (hands.placeItem(at, door, at.below(), net.minecraft.core.Direction.UP, -1)) {
				pocketAt = level.getGameTime();
				journal("water", "out of air under a roof: put a door down for a pocket of air at " + at.toShortString());
				goals.instant = "breathing in a door's air pocket";
				hands.steer = Vec3.atBottomCenterOf(at);
				return Action.FORWARD;
			}
		}
		return null;
	}

	private long pocketAt = -1000;

	private boolean has(java.util.function.Predicate<ItemStack> want) {
		var inv = player.getInventory();
		for (int i = 0; i < 36; i++) if (!inv.getItem(i).isEmpty() && want.test(inv.getItem(i))) return true;
		return false;
	}

	/** The first block of the shortest swim to air (breathing room over it), through water its body fits in; null if none near. */
	private static BlockPos stepToAir(ServerLevel level, BlockPos start, boolean tall) {
		java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		open.add(start);
		from.put(start, start);
		BlockPos air = null;
		while (!open.isEmpty() && from.size() < 1500) {
			BlockPos q = open.poll();
			if (!q.equals(start) && breathable(level, q.above())) {
				air = q;
				break;
			}
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				BlockPos n = q.relative(d);
				if (from.containsKey(n) || n.distManhattan(start) > 16 || !swimmable(level, n) || tall && !swimmable(level, n.above())) continue;
				from.put(n, q);
				open.add(n);
			}
		}
		if (air == null) return null;
		BlockPos step = air;
		while (!from.get(step).equals(start)) step = from.get(step);
		return step;
	}

	private static boolean swimmable(ServerLevel level, BlockPos p) {
		var s = level.getBlockState(p);
		return !s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) && (s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty());
	}

	private static boolean breathable(ServerLevel level, BlockPos p) {
		var s = level.getBlockState(p);
		return s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty();
	}

	/** The last dry ground it stood on (to swim back to), and the shore it's swimming for, since when; when it last said so. */
	BlockPos lastDry;
	private BlockPos shoreTo;
	private long shoreSince, shoreSaidAt = -1_000_000;

	/**
	 * Open water (a sea, a big lake: water all round, nothing to stand on under it): a player doesn't swim about out
	 * there (drowned, no food, night coming), they head for land: the nearest shore it can see, else the way it came.
	 * Not when it's just crossing a river to somewhere dry close by, or keeping up with a friend who swims.
	 */
	private Action backToShore() {
		if (!player.isInWater() || player.isPassenger() || inArena || fighting || player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			shoreTo = null;
			return null;
		}
		var level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		long now = level.getGameTime();
		if (shoreTo == null) {
			if (!openWater(level, feet)) return null;
			Vec3 going = walker.goal();
			if (going != null && going.distanceTo(player.position()) < 24 && XenMod.surface(level, (int) Math.floor(going.x), (int) Math.floor(going.z)) != null) return null;   // (crossing to land close by)
			if (mode == Mode.FOLLOW && leader != null) {
				ServerPlayer l = server.getPlayerList().getPlayer(leader);
				if (l != null && l.isInWater() && l.distanceTo(player) < 16) return null;   // (its friend is swimming: it keeps up)
			}
		}
		if (shoreTo == null || now - shoreSince > 20 * 40 || shoreTo.closerThan(feet, 2.5)) {   // (there and still in the water: somewhere it can climb out)
			shoreTo = nearestLand(level, feet, 160);
			if (shoreTo == null && lastDry != null && lastDry.distSqr(feet) < 300 * 300) shoreTo = lastDry;
			if (shoreTo == null) {                                        // no land in sight: toward the world's spawn (land), 64 blocks at a time
				BlockPos spawn = level.getRespawnData().pos();
				Vec3 way = Vec3.atCenterOf(spawn).subtract(player.position());
				if (way.horizontalDistance() > 8) {
					way = way.multiply(1, 0, 1).normalize().scale(Math.min(64, way.horizontalDistance()));
					shoreTo = BlockPos.containing(player.position().add(way));
				}
			}
			shoreSince = now;
			if (shoreTo == null) return null;
			journal("does", "out in open water: swims for the shore at " + shoreTo.toShortString());
			if (now - shoreSaidAt > 1200) {
				shoreSaidAt = now;
				chatter(pick3("Too deep out here. Back to land.", "Okay, enough swimming. Shore!", "Where's the land... there."), false);
			}
			goals.turnAround();                                           // (exploring: the other way now)
			goals.drop();
			chores.cancel();
		}
		goals.instant = "swimming back to the shore";
		return walkTo(Vec3.atBottomCenterOf(shoreTo));
	}

	/** Water under it and water all round (at least 6 of 8 ways, 6 blocks out): a sea or a lake, not a stream. */
	private static boolean openWater(ServerLevel level, BlockPos feet) {
		BlockPos under = feet.below();
		if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty() || !level.getBlockState(under.below()).getCollisionShape(level, under.below()).isEmpty()) return false;
		int wet = 0;
		for (int k = 0; k < 8; k++) {
			double a = k * Math.PI / 4;
			int x = feet.getX() + (int) Math.round(Math.cos(a) * 6), z = feet.getZ() + (int) Math.round(Math.sin(a) * 6);
			if (!level.hasChunkAt(new BlockPos(x, feet.getY(), z)) || XenMod.surface(level, x, z) == null) wet++;
		}
		return wet >= 6;
	}

	/** The nearest dry ground by the water (a block to stand on, air over it, not much above the water), out to that far. */
	private static BlockPos nearestLand(ServerLevel level, BlockPos from, int far) {
		for (int r = 3; r <= far; r += r < 48 ? 3 : 8) {
			BlockPos best = null;
			double bestD = Double.MAX_VALUE;
			int n = Math.max(12, r * 2);
			for (int k = 0; k < n; k++) {
				double a = k * Math.PI * 2 / n;
				int x = from.getX() + (int) Math.round(Math.cos(a) * r), z = from.getZ() + (int) Math.round(Math.sin(a) * r);
				if (!level.hasChunkAt(new BlockPos(x, from.getY(), z))) continue;
				Vec3 top = XenMod.surface(level, x, z);
				if (top == null || top.y > from.getY() + 5 || top.y < level.getSeaLevel() || top.y < from.getY() - 3) continue;   // (above the water line)
				int dry = 0;                                                     // real land, not a rock sticking out: dry ground round it too
				for (int[] o : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}}) {
					if (level.hasChunkAt(new BlockPos(x + o[0], from.getY(), z + o[1])) && XenMod.surface(level, x + o[0], z + o[1]) != null) dry++;
				}
				if (dry < 5) continue;
				double d = top.distanceToSqr(Vec3.atCenterOf(from));
				if (d < bestD) {
					bestD = d;
					best = BlockPos.containing(top);
				}
			}
			if (best != null) return best;
		}
		return null;
	}

	/** Holding sneak to swim down (out from under a roof): let go once its head is out. */
	private boolean diving;

	/** Companion instincts that come before the brain's own choice. */
	private Action instinct() {
		hands.watching = null;
		if (diving && !player.isUnderWater()) {
			player.setShiftKeyDown(false);
			diving = false;
		}
		if (this.instinct.clear()) {                                  // a clear danger: its instinct has the keys (Instinct), nothing new now
			hands.watching = null;
			return Action.IDLE;
		}
		Action survive = gut.override();                              // Xen 2.0's gut first: lava, fire, badly hurt with a monster on it
		if (survive != null) return survive;
		Action taught = tactics.next();                               // water flooding in, lava beside it, powder snow, a spawner
		if (taught != null) return taught;
		if (player.isInWater() && (player.isUnderWater() || player.getAirSupply() < player.getMaxAirSupply())
				&& !(walker.swimmingUnder() && player.getAirSupply() >= player.getMaxAirSupply() * 0.6)) {   // (swimming along under the top: its walk minds its air)
			if (player.getAirSupply() < player.getMaxAirSupply() * 0.6) {
				walker.stop();                                        // (its way led under: air first)
				walker.noDiveUntil = player.level().getGameTime() + 600;   // and no ways under water for a while
			}
			Action out = toAir();
			if (out != null) return out;
			goals.instant = "swimming up for air";
			player.setSprinting(false);                               // (upright: swimming flat and looking down, it would sink)
			player.setShiftKeyDown(false);
			return Action.JUMP;                                       // hold space to swim up, like a player
		}
		Action shore = backToShore();                                 // out in open water: back to land before anything else
		if (shore != null) return shore;
		Action come = comeOver();                                     // "come here": right over to them, then waits to see what they want
		if (come != null) return come;
		Action escort = escortNext();                                 // a friend died: with them back to their things, picking them up, handing them back
		if (escort != null) return escort;
		boolean toHeal = player.getHealth() <= player.getMaxHealth() - 4 && player.getFoodData().getFoodLevel() < 18;   // (two hearts down: under 18 food it doesn't heal, so a player eats)
		if ((player.getFoodData().getFoodLevel() <= personality.eatAt() || toHeal) && hands.canEat() && player.getFoodData().needsFood()
				&& choices.eat().yes()) {                                 // (a monster right on it: it fights first, eats after)
			goals.instant = toHeal && player.getFoodData().getFoodLevel() > personality.eatAt() ? "eating to heal" : "eating";
			return Action.EAT;
		}
		Action ride = rider.next();                                   // getting in a boat, on a horse, riding
		if (ride != null) return ride;
		Action use = uses.next();                                     // an item used on a block (asked, or a grudge)
		if (use != null) return use;
		Action share = sharing.next();                                // spare armor, a second sword, blocks: to a Xen who needs it
		if (share != null) return share;
		Action fight = fightBack();
		if (fight != null) {
			if (antics.busy()) antics.next(true);                     // a fight ends the fun
			if (goals.instant.isEmpty()) goals.instant = "fighting " + (fightingWhat.equals(fightingWhat.toLowerCase(java.util.Locale.ROOT)) ? "the " : "") + fightingWhat;
			return fight;
		}
		Action care = critters.danger();                              // arrows coming, drowned about, poison
		if (care != null) return care;
		if (inArena) return Action.IDLE;                              // between duels it waits for the next one
		Action shocked = rumors.shockStep();                          // someone it killed, alive; the one they say nobody beats
		if (shocked != null) return shocked;
		Action told = goToStep();                                     // "go to 120 64 -40": to that very block
		if (told != null) return told;
		Action creak = awayFromCreaking();                            // a creaking can't be hurt: it keeps away from it
		if (creak != null) return creak;
		Action end = dragon.next();                                    // in the End: the dragon fight
		if (end != null) return end;
		Tribe tribe = tribe();
		Action rally = tribe == null ? null : tribe.rally(this);       // its tribe is fighting someone: it comes to help
		if (rally != null) return rally;
		Action through = nether.followThrough();                       // its friend went through a portal: after them
		if (through != null) return through;
		Action off = backOffFromOwner();                              // its owner kept hitting it: it keeps away for a bit
		if (off != null) return off;
		Action met = reactions.next();                                // someone came into view: hello, a wary step back, a stare...
		if (met != null) return met;
		Action about = standingAbout();                                // a minute standing about with nothing to show: it moves on
		if (about != null) return about;
		Action whim = whims.next();                                   // bored stiff: a plan out loud, a party, a needless choice; a friend's party
		if (whim != null) return whim;
		Action fun = antics.next(false);                              // dancing, showing off
		if (fun != null) return fun;
		Action grudge = mode == Mode.FREE ? uses.grudge() : null;      // someone hurt it badly: their house (the grief setting)
		if (grudge != null) return grudge;
		Action pause = pace.next();                                    // its tool just broke (or a new one): a look at its hands
		if (pause != null) return pause;
		if (crafter.ready() && !farBehind()) {                        // tools first, like any new player
			Action craft = crafter.next();
			if (craft != null) return craft;
		}
		Action tidyUp = tidy.next();                                   // the pillar it climbed: down again, like most players
		if (tidyUp != null) return tidyUp;
		Action back = backForMyThings();                              // it died: its things are lying where it fell
		if (back != null) return back;
		Action spares = mode == Mode.FREE || builder.busy() ? prepared.next() : null;   // they're gone: its spares from the chest at home (building its own: then too)
		if (spares != null) return spares;
		Action pick = pickUpNearby();                                  // good things lying close by: it picks them up
		if (pick != null) return pick;
		Action water = fillBucket();                                   // an empty bucket and water right by it: filled (for a fall: the water clutch)
		if (water != null) return water;
		Action bed = bedtime();                                       // night, and a bed at home: it sleeps, like a player
		if (bed != null) return bed;
		Action light = lightUp();                                     // in a dark cave or tunnel: a torch, like a player
		if (light != null) return light;
		if (mode == Mode.FOLLOW) watchLeaderMoving();
		if (chores.busy() && chores.own && mode == Mode.FOLLOW && (!leaderWithin(LEASH) || leaderMoving())) {   // its friend is leaving: that comes first
			if (asked != null) resumeAsked = true;                           // (it'll get back to what it was asked, once it's caught up)
			chores.cancel();
			goals.drop();
			goals.pause(20 * 20);                                         // (no new errand for a bit: it keeps up first)
			if (player.tickCount - saidComingAt > 400) {
				saidComingAt = player.tickCount;
				chatter("Coming!", true);
			}
		}
		if (resumeAsked && !chores.busy() && asked != null && askedBy != null && !fighting && mode != Mode.FOLLOW) {
			resumeAsked = false;                                          // caught up: back to what it was asked to do
			String again = chores.gather(asked.intent(), asked.amount());
			if (again.startsWith("You will")) chatter(pick3("Okay, back to it.", "Now, where was I... right, " + asked.intent() + ".", "Back to work."), true);
		}
		if (storage.busy() && storage.looting()) {                     // a chest it's going to look in: that first (a moment)
			Action st = storage.next();
			if (st != null || storage.busy()) return st;
		}
		if ((mode != Mode.FOLLOW || leaderWithin(NEARBY)) && !inArena && !builder.busy() && (!chores.busy() || chores.own) && storage.spotLoot()) {
			return storage.next();                                    // passing a chest: a look inside (following someone too, while they're close)
		}
		Action hungry = starving();                                   // starving with nothing to eat: food before errands
		if (hungry != null) return hungry;
		Action bedFirst = bedBeforeNight();                           // night coming, no bed: wool first (its own errands wait)
		if (bedFirst != null) return bedFirst;
		if (mode == Mode.FREE && mod.config.wants && !inArena && goals.nightfall() && goals.think()) {   // dark, out in the open: a roof before errands
			Action roof = chores.busy() ? chores.next() : null;
			if (roof != null) return roof;
		}
		Action unsure = confusion.step();                             // Xen 2.0: confused, a look around, a moment, a question
		if (unsure != null) return unsure;
		Action habit = habits.next();                                 // a look around, a detour, a breather, boredom, a full bag
		if (habit != null) return habit;
		Action visit = mode == Mode.FREE && mod.config.wants ? visits.next() : null;   // a village or a ruined portal it found: in, and what it's good for
		if (visit != null) return visit;
		if (chores.busy()) {
			Action chore = chores.next();
			if (!chores.doing.isEmpty() && goals.instant.isEmpty()) goals.instant = chores.doing;
			if (chore != null || chores.busy()) return chore;
		}
		if (builder.busy()) {                                          // building its house (or base)
			Action b = builder.next();
			if (!builder.doing.isEmpty() && goals.instant.isEmpty()) goals.instant = builder.doing;
			if (b != null || builder.busy()) return b;
		}
		if (storage.busy()) {                                          // putting things away, taking them out, looting
			Action st = storage.next();
			if (!storage.doing.isEmpty() && goals.instant.isEmpty()) goals.instant = storage.doing;
			if (st != null || storage.busy()) return st;
		}
		if (nether.busy()) {                                           // a trip to the Nether (or lighting its portal)
			Action n = nether.next();
			if (n != null) return n;
		}
		if (voyager.onQuest()) {                                       // after the dragon: an elytra from an End city
			Action v = voyager.questStep();
			if (v != null) return v;
		}
		if (adventure.on) {                                            // on its way to the Ender Dragon
			Action a = adventure.next();
			if (a != null) return a;
			if (chores.busy()) return chores.next();
		}
		if (trials.on) {
			Action t = trials.next();
			if (t != null) return t;
		}
		if (mode != Mode.FOLLOW && !inArena && storage.spotLoot()) return storage.next();   // a chest nobody opened yet
		if (player.tickCount % 100 == 0 && mode == Mode.FREE) trials.spot();
		Action deal = trader.next();
		if (deal != null) return deal;
		Action show = director.step();                                 // showing off for someone watching (a trick shot, a jump)
		if (show != null) return show;
		Action drill = skills.step();                                  // training (sparring, practising its swings, parkour)
		if (drill != null) return drill;
		Action need = needs();                                         // starving, or night coming with no bed: that first
		if (need != null) return need;
		Action tool = choices.moment();                                // Xen 2.0: a tool it could use here (its rod, by the water: fish, don't, later)
		if (tool != null) return tool;
		if (mode == Mode.FREE && !minion && !chores.busy() && !builder.busy()) {
			Action animals = critters.chores();                          // its animals: breeding, shearing, a cat
			if (animals != null) return animals;
		}
		if (mode == Mode.FREE && mod.config.wants && !inArena && goals.think()) {   // free: what does it want?
			Action chore = chores.next();
			if (chore != null || chores.busy()) return chore;
		}
		if (fisher.on) {                                               // fishing: cast, watch the bobber, reel in
			Action f = fisher.next();
			if (f != null) return f;
		}
		if (farmer.on) {                                               // making its crop farm
			Action f = farmer.next();
			if (f != null) return f;
			if (chores.busy() || crafter.hasOrder()) return chores.busy() ? chores.next() : null;
		}
		if (mode != Mode.FOLLOW || leaderWithin(NEARBY)) {
			Action tend = farmer.tend();                                   // its crops: ripe ones harvested, planted again
			if (tend != null) return tend;
		}
		if (mode == Mode.FREE && mod.config.wants && !inArena && !minion) {
			Action watch = tribe == null ? null : tribe.watchStep(this);   // on watch over the village tonight
			if (watch != null) return watch;
			Action keep = shop.next();                                     // its shop: the stall, the sign, the goods
			if (keep != null) return keep;
			if (storage.busy() || nether.busy() || builder.busy() || chores.busy()) return null;
			if (goals.useMind()) {                                          // Xen 2.0: its choice, this moment
				Action m = goals.mindStep();
				if (m != null) return m;
				if (goals.current != Goals.Short.EXPLORE) return null;       // (resting, crafting, sleeping: it looks about)
			}
			if (goals.current == Goals.Short.EXPLORE || goals.current == null) return goals.exploreStep();   // out to see what's there
		}
		// Following a friend who's close: it doesn't just stand there. Like a friend playing along, it gets on with what
		// it needs nearby (wood, stone, food, ore, a shelter at night) and drops it to keep up when they leave.
		if (mode == Mode.FOLLOW && mod.config.wants && !inArena && !catchingUp && leaderWithin(NEARBY) && leaderStill() && goals.think(true)) {
			Action chore = chores.next();
			if (chore != null || chores.busy()) return chore;
		}
		if (mode == Mode.FOLLOW && goals.useMind() && !catchingUp && leaderWithin(NEARBY) && leaderStill()) {
			Action m = goals.mindStep();
			if (m != null) return m;
		}
		Vec3 goal = null;
		if (mode == Mode.FOLLOW && leader != null) {
			ServerPlayer o = server.getPlayerList().getPlayer(leader);
			if (o != null && o.level() == player.level()) {
				double d = o.position().distanceTo(player.position());
				if ((d > mod.config.followDistance || catchingUp && d > 2.5) && (d < 96 || leader.equals(comeFor))) goal = o.position();   // close up, then wait
				catchingUp = goal != null;
			}
		} else if (mode == Mode.STAY && anchor != null && Vec3.atCenterOf(anchor).distanceTo(player.position()) > 8) {
			goal = Vec3.atCenterOf(anchor);
		}
		if (goal == null && minion && mode == Mode.FREE && boss != null && boss.player != null && boss.player.level() == player.level()
				&& boss.player.distanceTo(player) > 48) {
			goal = boss.player.position();                               // a minion stays where its boss keeps the world alive
			goals.instant = "going back to " + boss.name;
			return walkTo(goal);
		}
		if (goal == null) return null;
		goals.instant = mode == Mode.STAY ? "going back to where you were told to stay" : "keeping up with your friend";
		return walkTo(goal);
	}

	private boolean catchingUp;
	/** Called over ("come here"): who, till when, and when it got there (then it waits a while for what they want: a tool to take...). */
	private java.util.UUID comeFor;
	private long comeUntil, cameAt = -1;

	/** What it was doing before it was called over (its own life: back to that after, unless asked to stay). */
	private Mode modeBeforeCome;
	/** Something given to it while it was there (then it says thanks, and it knows what that was about). */
	private int comeGifts;

	private Action comeOver() {
		if (comeFor == null) return null;
		long now = player.level().getGameTime();
		ServerPlayer o = server.getPlayerList().getPlayer(comeFor);
		boolean leftIt = cameAt >= 0 && o != null && o.distanceTo(player) > 10;                 // (they walked off: it keeps up, it was called)
		if (o == null || o.level() != player.level() || now > comeUntil || cameAt >= 0 && now - cameAt > 20 * 30 && !leftIt) {
			doneComing(o, false);                                        // (a good while there and nothing asked: its own things again)
			return null;
		}
		if (fighting) return null;                                       // (a monster at them or at it first: its fight, then back here)
		double d = o.distanceTo(player);
		if (d > 2.5 && (cameAt < 0 || leftIt)) {
			goals.instant = "coming over to " + o.getName().getString();
			if (hands.busy() && hands.interruptible()) hands.stop();
			return walkTo(o.position());
		}
		if (cameAt < 0) {
			cameAt = now;
			comeGifts = 0;
			comeHad = items();
			walker.stop();
			String sized = sizeUp(o);                                     // a look at them and round them first: what's wrong?
			journal("does", "came over to " + o.getName().getString() + ": " + sized);
			say(sized);
		}
		Map<String, Integer> nowHas = items();
		for (var e : nowHas.entrySet())
			if (e.getValue() > comeHad.getOrDefault(e.getKey(), 0)) {     // something given to it: worth keeping? (its call)
				comeGifts++;
				gift(o, e.getKey());
			}
		comeHad = items();
		Action pick = pickUpNearby();                                  // something tossed to it (a pickaxe, an axe): it takes it
		if (pick != null) return pick;
		if ((now - cameAt) % 20 == 0) {                                 // while it waits it looks round: anything coming at them?
			net.minecraft.world.entity.Mob mob = null;
			for (net.minecraft.world.entity.Mob m : o.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, o.getBoundingBox().inflate(14),
					m -> m.isAlive() && hostile(m) && player.hasLineOfSight(m)))
				if (mob == null || m.distanceTo(o) < mob.distanceTo(o)) mob = m;
			if (mob != null && hands.hasWeapon() && !helpGoing) {
				helpAgainst = mob;
				helpGoing = true;
				helpUntil = now + 20 * 25;
				say(pick3("Behind you!", "Watch out, " + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath().replace('_', ' ') + "!", "Hold on, I got this one."));
			}
		}
		goals.instant = "here with " + o.getName().getString() + ", waiting to see what they want";
		long phase = (now - cameAt) / 30 % 4;                           // eyes on them, a glance round, back to them (like a player waiting)
		if (phase == 1 || phase == 3) {
			double yaw = Math.toRadians(o.getYRot() + (phase == 1 ? 120 : -120));
			hands.face(player.getEyePosition().add(-Math.sin(yaw) * 6, -0.3, Math.cos(yaw) * 6));
		} else hands.face(o.getEyePosition());
		return Action.IDLE;
	}

	/** What it had when it got there (to notice what it's given). */
	private Map<String, Integer> comeHad = new HashMap<>();

	/**
	 * Given something: does it keep it? Junk it has no use for, or a tool worse than the one it has: its call (a kind one
	 * keeps it anyway, to be nice). Else thanks.
	 */
	private void gift(ServerPlayer o, String item) {
		boolean junk = GIFT_JUNK.contains(item);
		boolean worse = toolTier(item) >= 0 && bestTier(item) > toolTier(item);
		if (!junk && !worse) {
			say(pick3("Oh nice, thanks!", "Thanks, I needed that.", "Ooh, a " + item.replace('_', ' ') + "! Thanks."));
			return;
		}
		if (personality.kindness > 0.7f) {                              // (too nice to give it back)
			say(pick3("Uh, thanks I guess.", "Oh... thanks!", "Haha, okay, thanks."));
			return;
		}
		var inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty() || !BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().equals(item)) continue;
			hands.face(o.position().add(0, 0.2, 0));
			ItemStack out = st.split(st.getCount());
			if (!Compat.tossTo(player, out, o.getUUID())) inv.add(out);
			break;
		}
		say(worse ? pick3("Thanks, but I've got a better one. You keep it.", "I already have a better " + item.replaceAll("^[a-z]+_", "") + ", here.", "Nah, mine's better. Keep it!")
				: pick3("What am I gonna do with this? Here.", "Uh, no thanks.", "I've got no use for that, here you go."));
		journal("thinks", "was given " + item.replace('_', ' ') + ": " + (worse ? "it has a better one" : "no use for it") + ", gives it back");
	}

	private static final java.util.Set<String> GIFT_JUNK = java.util.Set.of("dirt", "rotten_flesh", "poisonous_potato", "spider_eye", "dead_bush",
			"gravel", "sand", "andesite", "diorite", "granite", "tuff", "wheat_seeds", "stick");
	private static final java.util.List<String> TOOL_TIERS = java.util.List.of("wooden", "golden", "stone", "copper", "iron", "diamond", "netherite");

	/** How good a tool is (0 wood ... 6 netherite), -1 if it isn't one. */
	private static int toolTier(String item) {
		if (!item.matches(".*_(pickaxe|axe|sword|shovel|hoe)$")) return -1;
		return TOOL_TIERS.indexOf(item.substring(0, item.indexOf('_')));
	}

	/** Its best tool of that kind (a pickaxe for a pickaxe...) other than this one, -1 if none. */
	private int bestTier(String item) {
		String kind = item.substring(item.lastIndexOf('_'));
		int best = -1;
		var inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			String n = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (!n.equals(item) && n.endsWith(kind)) best = Math.max(best, toolTier(n));
		}
		return best;
	}

	/** Called over: what it sees when it gets there, and what it says (or does: a monster at them, food for them). */
	private String sizeUp(ServerPlayer o) {
		String who = o.getName().getString();
		net.minecraft.world.entity.Mob mob = null;
		for (net.minecraft.world.entity.Mob m : o.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, o.getBoundingBox().inflate(12),
				m -> m.isAlive() && hostile(m) && (m.getTarget() == o || m.distanceTo(o) < 6)))
			if (mob == null || m.distanceTo(o) < mob.distanceTo(o)) mob = m;
		if (mob != null && hands.hasWeapon()) {                          // that's why they called: it goes for it
			helpAgainst = mob;
			helpGoing = true;
			helpUntil = o.level().getGameTime() + 20 * 25;
			return pick3("Hang on, I got it!", "I see it, I'm on it!", "Leave that " + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath().replace('_', ' ') + " to me!");
		}
		if (o.getHealth() <= 10 && foodFor(o)) return pick3("You're hurt, " + who + ". Here, eat something.", "You look rough. Take this, eat.", "Whoa, you're low. Here, food.");
		if (o.getFoodData().getFoodLevel() <= 8 && foodFor(o)) return pick3("You hungry? Here.", "Here, I've got food. Eat.", "You look starving. Take some.");
		if (!o.getMainHandItem().isEmpty() && o.getLookAngle().dot(player.getEyePosition().subtract(o.getEyePosition()).normalize()) > 0.9)
			return pick3("Is that for me?", "What's that? For me?", "Oh, you got something for me?");
		if (o.level().isDarkOutside() && o.level().canSeeSky(o.blockPosition())) return pick3("It's getting dark. Need a shelter?", "Night's coming. What's up, need help?", "Here. It's dark out, want a hand?");
		return pick3("Here. What's wrong?", "I'm here. What's up, need something?", "Yeah? Something wrong?");
	}

	/** Some of its food tossed to them (not its golden apples), if it has any to give. */
	private boolean foodFor(ServerPlayer o) {
		var inv = player.getInventory();
		int best = -1, bestFill = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !s.has(net.minecraft.core.component.DataComponents.FOOD)) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (Survival.keep(n) || Survival.risky(n) != null) continue;
			int fill = s.get(net.minecraft.core.component.DataComponents.FOOD).nutrition() * s.getCount();
			if (fill > bestFill) {
				best = i;
				bestFill = fill;
			}
		}
		if (best < 0) return false;
		hands.face(o.position().add(0, 0.2, 0));
		ItemStack out = inv.getItem(best).split(Math.max(1, Math.min(4, inv.getItem(best).getCount() / 2)));
		if (!Compat.tossTo(player, out, o.getUUID())) {
			inv.add(out);
			return false;
		}
		return true;
	}

	/** Done being called over: back to what it was doing before (its own life), unless it was asked to stay with them. */
	private void doneComing(ServerPlayer o, boolean saidSo) {
		if (comeFor == null) return;
		comeFor = null;
		if (modeBeforeCome != null && mode == Mode.FOLLOW) {
			mode = modeBeforeCome;
			if (o != null && !saidSo && comeGifts > 0) say(pick3("Thanks for that! Back to it.", "Cheers! I'll get back to what I was doing.", "Nice, thanks. Back to work."));
			else if (o != null && !saidSo) chatter(pick3("Alright, I'll get back to it then.", "Okay, shout if you need me.", "Back to work. Call me if you need anything."), true);
		}
		modeBeforeCome = null;
	}

	/** Called over and there: they said they're fine, or gave it something and are done ("nothing", "thanks", "go on"). */
	boolean comeAnswered(ServerPlayer from, String words) {
		if (comeFor == null || cameAt < 0 || !from.getUUID().equals(comeFor)) return false;
		if (!words.matches("(?is).*\\b(nothing|nvm|never ?mind|nah|no|all good|i'?m (fine|good|ok)|thanks?|thx|ty|go( on| back)?|carry on|you can go|that'?s all|bye)\\b.*")) return false;
		say(pick3("Alright. Shout if you need me.", "Okay! Back to it.", "Cool, I'll be around."));
		doneComing(from, true);
		return true;
	}
	/** Where its friend was standing, and since when they've stayed about there (following: moving means keep up). */
	private Vec3 leaderSpot;
	private long leaderStillSince;

	private void watchLeaderMoving() {
		ServerPlayer o = leader == null ? null : server.getPlayerList().getPlayer(leader);
		if (o == null) return;
		long now = player.level().getGameTime();
		if (leaderSpot == null || o.position().distanceTo(leaderSpot) > 3) {
			leaderSpot = o.position();
			leaderStillSince = now;
		}
	}

	/** Following: its friend has stayed put for a while (then it may do a little something of its own nearby). */
	private boolean leaderStill() {
		return leaderSpot != null && player.level().getGameTime() - leaderStillSince > 160;
	}

	/** Following: its friend just set off (and is getting away): it drops what it's doing and keeps up. */
	private boolean leaderMoving() {
		return player.level().getGameTime() - leaderStillSince < 40 && !leaderWithin(mod.config.followDistance + 4);
	}

	/** While following: it does things of its own when its friend is this close, and drops them past the leash. */
	static final double NEARBY = 12, LEASH = 24;

	private boolean leaderWithin(double distance) {
		ServerPlayer o = leader == null ? null : server.getPlayerList().getPlayer(leader);
		return o != null && o.level() == player.level() && o.distanceTo(player) <= distance;
	}

	/** Is its friend getting away (so there's no time to stop and craft)? */
	private boolean farBehind() {
		if (mode != Mode.FOLLOW || leader == null) return false;
		ServerPlayer o = server.getPlayerList().getPlayer(leader);
		return o != null && o.level() == player.level() && o.distanceTo(player) > mod.config.followDistance * 2;
	}

	private long lastTorch, nextTorchCraft;

	/**
	 * Light where it is when it's dark there (underground or under a roof, not just night outside): a torch on the
	 * floor next to it or on a wall, from its bag. Without torches but with coal, it makes some first. So caves it
	 * works in get lit up the way players light them, and mobs don't spawn right next to it.
	 */
	private Action lightUp() {
		ServerLevel level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		long now = level.getGameTime();
		if (fighting || !player.onGround() || now - lastTorch < 60) return null;
		boolean dark = WorldSenses.light(level, feet) <= 3 && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, feet) < 8;
		if (!dark) return null;
		if (DEBUG) XenMod.LOG.info("[xen debug] {} is in the dark (light {}), torches {}", name, WorldSenses.light(level, feet), items().getOrDefault("torch", 0));
		var items = items();
		if (items.getOrDefault("torch", 0) == 0) {
			int coal = items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0);
			if (coal > 0 && !crafter.hasOrder() && now >= nextTorchCraft) {
				nextTorchCraft = now + 1200;
				crafter.request("torch", 4);
				chatter("It's dark here. I'll make some torches.", false);
			}
			return null;
		}
		for (int k = 0; k < 4; k++) {
			int[] f = Perception.forward((hands.yaw + 2 + k) % 4);                // behind it first (not where it's going)
			BlockPos side = feet.offset(f[0], 0, f[1]);
			BlockPos wall = side.above();
			if (!level.getBlockState(wall).canBeReplaced() && level.getBlockState(wall).isFaceSturdy(level, wall, direction(-f[0], -f[1]))
					&& level.getBlockState(feet.above()).canBeReplaced()
					&& hands.placeItem(feet.above(), st -> itemKey(st).equals("torch"), wall, direction(-f[0], -f[1]), -1)) {   // on the wall
				lastTorch = now;
				acted = true;
				return Action.PLACE;
			}
			BlockPos under = side.below();
			if (level.getBlockState(side).canBeReplaced() && level.getFluidState(side).isEmpty()
					&& level.getBlockState(under).isFaceSturdy(level, under, net.minecraft.core.Direction.UP)
					&& hands.placeItem(side, st -> itemKey(st).equals("torch"), under, net.minecraft.core.Direction.UP, -1)) {   // on the floor
				lastTorch = now;
				acted = true;
				return Action.PLACE;
			}
		}
		lastTorch = now;                                               // nowhere to put one right here: in a moment
		if (DEBUG) XenMod.LOG.info("[xen debug] {} found nowhere for a torch: {}", name, hands.cantPlace);
		return null;
	}

	private static net.minecraft.core.Direction direction(int x, int z) {
		return x > 0 ? net.minecraft.core.Direction.EAST : x < 0 ? net.minecraft.core.Direction.WEST : z > 0 ? net.minecraft.core.Direction.SOUTH
				: net.minecraft.core.Direction.NORTH;
	}

	/** Signs it has read (where, and what they said), and the last one, for its notes. */
	private final Map<BlockPos, String> signsRead = new HashMap<>();
	private String lastSign;
	private long lastSignAt, lastSignSaid = -10_000;

	/** Read the signs it can see within 10 blocks (a new or changed one it reads out when someone's there to hear). */
	private void readSigns() {
		if (inArena) return;
		ServerLevel level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				var chunk = level.getChunkSource().getChunkNow((feet.getX() >> 4) + dx, (feet.getZ() >> 4) + dz);
				if (chunk == null) continue;
				for (var e : chunk.getBlockEntities().entrySet()) {
					if (!(e.getValue() instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) continue;
					BlockPos at = e.getKey();
					Vec3 middle = Vec3.atCenterOf(at);
					double d = eye.distanceTo(middle);
					if (d > 10) continue;
					if (d > 3) {                                               // further: in its view, with nothing in the way
						double[] from = {eye.x, eye.y, eye.z}, to = {middle.x, middle.y, middle.z};
						if (!Perception.inView(from, hands.yaw, hands.pitch, to)) continue;
						var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, middle, net.minecraft.world.level.ClipContext.Block.COLLIDER,
								net.minecraft.world.level.ClipContext.Fluid.NONE, player));
						if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(at)) continue;
					}
					String text = Compat.readSign(sign).trim();
					if (DEBUG && !text.equals(signsRead.get(at))) XenMod.LOG.info("[xen debug] {} reads the sign at {}: {}", name, at, text);
					if (text.isEmpty() || text.equals(signsRead.get(at))) continue;
					if (signsRead.size() > 200) signsRead.clear();
					signsRead.put(at.immutable(), text);
					if (text.contains("-" + name)) continue;                     // its own note
					lastSign = text.length() > 90 ? text.substring(0, 90) : text;
					lastSignAt = level.getGameTime();
					if (someoneListening(12) && lastSignAt - lastSignSaid > 600) {   // read out (one sign every half minute at most)
						lastSignSaid = lastSignAt;
						chatter("This sign says: \"" + lastSign + "\"", true);
					}
				}
			}
		}
	}

	/** Sprint (a double tap on W): when there's far to go and it isn't too hungry, like a player; never in water. */
	void run(boolean on) {
		boolean can = on && player.getFoodData().getFoodLevel() > 6 && !player.isInWater() && !player.isShiftKeyDown() && player.onGround();
		if (can != player.isSprinting() && (can || !fighting)) player.setSprinting(can);
	}

	/** Where it's walking to, and the closest it has got (and when): to notice when it's getting nowhere. */
	private Vec3 trackedGoal;
	private double bestGap;
	private int bestGapAt;

	/** How far it will drop down: a little fall damage (1 point for each block over 3) is fine when it is healthy. */
	private int maxDrop() {
		float h = player.getHealth();
		return h >= 16 ? 5 : h >= 12 ? 4 : 3;
	}

	/** Like a player: never look an Enderman in the eyes (it would come for you); it looks down instead. */
	private void dontStareAtEndermen() {
		for (var e : player.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(40),
				x -> x.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).getPath().equals("enderman") && x.getTarget() != player)) {
			Vec3 look = player.getViewVector(1f).normalize(), to = e.getEyePosition().subtract(player.getEyePosition());
			double d = to.length();
			if (look.dot(to.normalize()) > 1 - 0.08 / d && player.hasLineOfSight(e)) {
				player.setXRot(Math.min(90, player.getXRot() + 35));             // eyes down
				return;
			}
		}
	}

	BlockPos bedAt;
	private long lookedForBedAt = -10000;

	/**
	 * Bedtime: at night, with a bed at home (its house has one) and nothing to fight, it goes to bed and sleeps (that
	 * also makes it come back there if it dies); it gets up in the morning. Not while it's following you out somewhere,
	 * or on a job.
	 */
	/**
	 * Instead of building its own house: moving in with someone of its tribe whose house has room (it saves the
	 * wood; a thrifty or close Xen likes that). They decide too: a kind one who trusts it says yes. True if it moved in.
	 */
	boolean moveIn() {
		Tribe t = tribe();
		if (t == null || goals.home != null || !mod.config.tribes) return false;
		var p = personality;
		boolean wants = p.money > 0.5f || p.diligence < 0.4f || p.loyalty > 0.6f || p.kindness > 0.7f;
		if (!wants) return false;
		for (Companion m : t.members) {
			if (m == this || m.player == null || m.goals.home == null || m.player.level() != player.level()) continue;
			Taste.Design d = m.taste.last;
			int size = d == null ? 25 : d.w() * d.d(), living = 0;
			for (Companion o : t.members) if (m.goals.home.equals(o.goals.home)) living++;
			if (size < 30 * (living + 1)) continue;                            // (about 30 blocks of floor each)
			float yes = m.personality.generosity() + m.trust(player.getUUID()) + (m.personality.money > 0.6f ? -0.3f : 0);
			if (yes < 0.8f) continue;
			goals.home = m.goals.home;
			say(pick3("Can I move in with you, " + m.name + "? No point building two houses.", m.name + ", room for one more at yours?",
					"Mind if I live with you, " + m.name + "?"));
			m.say(m.pick3("Sure, move in!", "Of course. Make yourself at home.", "Okay, but you do the dishes."));
			journal("does", "moves in with " + m.name + " (they both chose to)");
			return true;
		}
		return false;
	}

	/** A bed at home it knows of (it looks around its home now and then). */
	boolean hasBed() {
		if (goals.home == null) return false;
		var level = (ServerLevel) player.level();
		if (bedAt != null && level.getBlockState(bedAt).getBlock() instanceof net.minecraft.world.level.block.BedBlock) return true;
		bedAt = null;
		if (level.getGameTime() - lookedForBedAt < 600) return false;
		lookedForBedAt = level.getGameTime();
		for (BlockPos q : BlockPos.betweenClosed(goals.home.offset(-8, -3, -8), goals.home.offset(8, 3, 8))) {
			if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
				bedAt = q.immutable();
				break;
			}
		}
		return bedAt != null;
	}

	private long neededAt = -100_000;
	private long starvingAt = -100_000;

	/**
	 * Starving (food 6 or less) with nothing to eat: an errand of its own waits, and one it was asked waits too once it's
	 * down to nothing. Food first: it hunts (or fishes), like any player who looks at their hunger bar.
	 */
	private Action starving() {
		if (fighting || inArena || player.isCreative() || fisher.on) return null;
		int food = player.getFoodData().getFoodLevel();
		if (food > 6 || items().getOrDefault("food", 0) > 0) return null;
		if (chores.busy() && (chores.kind == Chores.Kind.HUNT || chores.kind == Chores.Kind.EAT)) return null;
		if (chores.busy() && !chores.own && food > 0) return null;          // asked: it finishes first, unless it's down to nothing
		long now = player.level().getGameTime();
		if (now - starvingAt < 400) return null;
		starvingAt = now;
		if (chores.busy()) {
			if (!chores.own && asked != null) resumeAsked = true;         // (it gets back to it after eating)
			chores.cancel();
		}
		if (builder.busy()) builder.cancel();
		neededAt = now;
		var prey = chores.nearestAnimal();
		if ((prey == null || prey.distanceTo(player) > 16) && fisher.fancies() && fisher.start().startsWith("You will")) {   // Xen 2.0: a rod, water, no animal close: it fishes
			chatter(pick3("Starving. Fishing for dinner.", "No animals around... fish it is.", "Fish, please bite."), true);
			return Action.IDLE;
		}
		chores.forWool = false;
		String plan = chores.hunt(2);
		chores.own = true;
		chatter(plan.startsWith("You don't see") ? pick3("I'm starving... I need to find some animals.", "So hungry. Where are the animals?", "I need food, now.")
				: pick3("I'm starving. Food first.", "Can't work on an empty stomach. Hunting.", "Dinner first, sorry."), true);
		return chores.next();
	}


	/**
	 * What a player sees to before anything else: food when it's starving with none on it (it hunts, or fishes), and a
	 * bed before night when it has none (wool from sheep: it hunts sheep). Null when all's well.
	 */
	private Action needs() {
		boolean hurtNoFood = player.getHealth() <= 10 && player.getFoodData().getFoodLevel() < 18 && items().getOrDefault("food", 0) == 0;
		if (hurtNoFood && !fighting && chores.busy() && chores.own && asked == null && chores.kind != Chores.Kind.HUNT && chores.kind != Chores.Kind.EAT
				&& player.level().getGameTime() - neededAt >= 1200) {
			journal("thinks", "hurt (" + Math.round(player.getHealth()) + " health) with nothing to eat: it won't heal like this, so food first");
			chores.cancel();                                                  // (its own errand: hurt with nothing to eat, it doesn't heal; food first)
		}
		if (fighting || chores.busy() || builder.busy() || inArena || player.isCreative() || mode == Mode.FOLLOW && !leaderWithin(NEARBY)) return null;
		long now = player.level().getGameTime();
		if (now - neededAt < 1200) return null;
		var items = items();
		if (player.getFoodData().getFoodLevel() <= 17 && items.getOrDefault("food", 0) == 0 && player.getFoodData().getFoodLevel() > 12) {
			var prey = chores.nearestAnimal();                             // nothing to eat on it, and an animal right there: a player takes it
			if (prey != null && prey.distanceTo(player) < 10) {
				neededAt = now;
				chores.forWool = false;
				chores.hunt(1);
				chores.own = true;
				return chores.next();
			}
		}
		if ((player.getFoodData().getFoodLevel() <= 12 || hurtNoFood) && items.getOrDefault("food", 0) == 0) {
			neededAt = now;
			if (fisher.fancies() && fisher.start().startsWith("You will")) {
				chatter("I'm starving. Fishing for dinner.", true);
				return Action.IDLE;
			}
			chores.forWool = false;
			String plan = chores.hunt(3);
			chores.own = true;
			boolean starving = player.getFoodData().getFoodLevel() <= 12;
			chatter(starving ? (plan.startsWith("You don't see") ? "I'm starving... I need to find some animals." : "I'm starving. Time to hunt.")
					: plan.startsWith("You don't see") ? "I'm hurt and I've got nothing to eat. I need to find food." : "I'm hurt. Food first, then I'll heal.", true);
			return chores.next();
		}
		// Food for later: little on it and a food animal right there (not a baby, not the last of them): a player takes it.
		int carried = items.getOrDefault("food", 0), reserve = foodReserve();
		if (carried < reserve && player.getHealth() >= 10 && !personality.believes("animals_kindness") && crafter.pickTier() >= 2) {   // (tools first: a player doesn't chase cows before a pickaxe)
			var prey = chores.nearestAnimal();
			if (prey != null && prey.distanceTo(player) < 14) {
				neededAt = now;
				chores.forWool = false;
				chores.hunt(1);                                                // (the one right there, not a search for more)
				chores.own = true;
				journal("does", "hunts for food for later (" + carried + " on it, it likes " + reserve + ")");
				if (random.nextFloat() < 0.3f) chatter(pick3("Food for later.", "Dinner for later, sorry.", "Stocking up on food."), false);
				return chores.next();
			}
		}
		long time = Compat.timeOfDay(player.level()) % 24000;
		boolean bed = items.keySet().stream().anyMatch(k -> k.endsWith("_bed")) || goals.home != null && bedAt != null;
		int wool = 0;
		for (var e : items.entrySet()) if (e.getKey().endsWith("_wool")) wool += e.getValue();
		if (!bed && wool < 3 && time > 8000 && time < 12000 && crafter.pickTier() >= 1) {
			for (var sheep : player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class, player.getBoundingBox().inflate(32), x -> x.isAlive())) {
				if (!WorldSenses.sees(player, hands.yaw, hands.pitch, sheep)) continue;
				neededAt = now;
				chores.forWool = true;
				chores.hunt(3);
				chores.own = true;
				chatter(pick3("I need a bed before night. Sheep!", "Wool for a bed. Sorry, sheep.", "Three wool and I can sleep tonight."), true);
				return chores.next();
			}
		}
		return null;
	}

	/** Where it last saw sheep, and when (a player remembers where the sheep were). */
	private BlockPos sheepSeenAt;
	private long sheepSeenTime = -1_000_000, bedCheckAt, woolTripAt;
	/** Until when what it's doing is for a bed tonight (its mind doesn't start anything over it). */
	long bedErrandUntil;

	/** Where it's walking to find those sheep again (null: not), and whether that was its own idea (or it was asked). */
	private BlockPos woolTrip;
	private boolean woolOwn;

	/** Every second: sheep in sight (or right by it) are remembered. */
	private void noticeSheep() {
		net.minecraft.world.entity.animal.sheep.Sheep near = null;
		for (var sheep : player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class, player.getBoundingBox().inflate(48), x -> x.isAlive())) {
			if (sheep.distanceTo(player) > 12 && !WorldSenses.sees(player, hands.yaw, hands.pitch, sheep)) continue;
			if (near == null || sheep.distanceTo(player) < near.distanceTo(player)) near = sheep;
		}
		if (near != null) {
			sheepSeenAt = near.blockPosition();
			sheepSeenTime = player.level().getGameTime();
		}
	}

	/**
	 * No bed and the day's getting on (or it's night and there are sheep right here): a bed is how a player gets
	 * through the night, so wool first: it drops its own errands (never what it was asked) and hunts sheep, or goes to
	 * where it saw some. With three wool the crafter makes the bed, and it sleeps.
	 */
	private Action bedBeforeNight() {
		if (fighting || inArena || player.isCreative() || minion || player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			woolTrip = null;
			return null;
		}
		long now = player.level().getGameTime();
		if (woolTrip != null && (!woolOwn || mode == Mode.FREE && !builder.busy()) && !(chores.busy() && !chores.own)) {   // on its way to the sheep it saw: keep going
			boolean there = woolTrip.closerThan(player.blockPosition(), 6) || !player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class,
					player.getBoundingBox().inflate(10), x -> x.isAlive()).isEmpty();
			if (now - woolTripAt > 20 * 90 || there || emotions.fear > 0.6f) {
				woolTrip = null;
				int wool = 0;
				for (var e : items().entrySet()) if (e.getKey().endsWith("_wool")) wool += e.getValue();
				if (there && wool < 3) {                                          // there: after them (it looks around for them; none in half a minute, it gives up)
					chores.forWool = true;
					chores.hunt(3 - wool);
					chores.own = woolOwn;
					return chores.next();
				}
			} else return walkTo(Vec3.atBottomCenterOf(woolTrip));
		}
		if (mode != Mode.FREE || builder.busy() && !builder.ownHouse() || goals.exploringAsked()) return null;   // (its own house can wait while it gets wool: the building picks up after; not what it was asked)
		if (chores.busy() && (!chores.own || chores.forWool && chores.kind == Chores.Kind.HUNT)) return null;   // (asked for something: that first; already after wool)
		if (chores.busy() && chores.kind == Chores.Kind.MINE && (!player.level().canSeeSky(player.blockPosition().above())
				|| items().getOrDefault("raw_iron", 0) + items().getOrDefault("iron_ingot", 0) == 0 && Compat.timeOfDay(player.level()) % 24000 < 12000)) return null;   // (down its mine, or after its first iron: the wool can wait)
		if (now - bedCheckAt < 100) return null;
		bedCheckAt = now;
		long time = Compat.timeOfDay(player.level()) % 24000;
		boolean late = time > 8500 && time < 12500, night = time >= 12500 && time < 23000;
		if (!late && !night) return null;
		var items = items();
		if (items.keySet().stream().anyMatch(k -> k.endsWith("_bed")) || hasBed()) return null;
		int wool = 0;
		for (var e : items.entrySet()) if (e.getKey().endsWith("_wool")) wool += e.getValue();
		if (wool >= 3) {                                                      // (the crafter makes the bed: with a log for the planks)
			if (!crafter.bedNeedsWood() || chores.busy() && chores.kind == Chores.Kind.GATHER && (chores.doing.contains("wood") || chores.doing.contains("trees"))) return null;   // (already after wood)
			if (chores.busy()) chores.cancel();
			goals.drop();
			String plan = chores.gather("wood", 2);
			chores.own = true;
			bedErrandUntil = now + 20 * 60;
			if (plan.startsWith("You will")) journal("does", "has the wool for a bed: a log for the planks");
			return chores.next();
		}
		net.minecraft.world.entity.animal.sheep.Sheep near = null;
		for (var sheep : player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class, player.getBoundingBox().inflate(night ? 16 : 40),
				x -> x.isAlive() && !x.isBaby() && Math.abs(x.getY() - player.getY()) < 8 && player.hasLineOfSight(x))) {   // (one it can see: not through the rock)
			if (near == null || sheep.distanceTo(player) < near.distanceTo(player)) near = sheep;
		}
		if (night && (near == null || emotions.fear > 0.5f || player.getHealth() < 12
				|| !player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, player.getBoundingBox().inflate(12), m -> m.isAlive()).isEmpty())) return null;
		if (near != null) {
			neededAt = now;
			if (chores.busy()) chores.cancel();
			goals.drop();
			chores.forWool = true;
			chores.hunt(3 - wool);
			chores.own = true;
			journal("does", "no bed for the night: goes for wool (sheep " + Math.round(near.distanceTo(player)) + " blocks away)");
			chatter(pick3("I need a bed before night. Sheep!", "Wool for a bed. Sorry, sheep.", "Three wool and I can sleep tonight."), true);
			return chores.next();
		}
		if (late && sheepSeenAt != null && now - sheepSeenTime < 20 * 60 * 5 && sheepSeenAt.closerThan(player.blockPosition(), 96)) {
			if (chores.busy()) chores.cancel();
			goals.drop();
			goals.instant = "going to where it saw sheep (wool for a bed)";
			if (now - neededAt > 1200) journal("does", "no bed for the night: back to the sheep it saw at " + sheepSeenAt.toShortString());
			neededAt = now;
			woolTrip = sheepSeenAt;
			woolTripAt = now;
			woolOwn = true;
			sheepSeenTime = -1_000_000;                                         // (one trip per sighting: no sheep there, it gives up on them)
			return walkTo(Vec3.atBottomCenterOf(woolTrip));
		}
		return null;
	}

	/** "Get wool for a bed", "make a bed": it makes one if it has the wool, else goes for wool (sheep it sees, or where it saw some). */
	private String bedAsked() {
		var items = items();
		if (items.keySet().stream().anyMatch(k -> k.endsWith("_bed"))) return "You already have a bed with you.";
		int wool = 0;
		for (var e : items.entrySet()) if (e.getKey().endsWith("_wool")) wool += e.getValue();
		if (wool >= 3) return crafter.request("bed", 1);
		chores.forWool = true;
		String hunt = chores.hunt(3 - wool);
		if (!hunt.startsWith("You don't see")) return "You will get " + (3 - wool) + " wool from the sheep nearby, for a bed.";
		if (sheepSeenAt != null && sheepSeenAt.closerThan(player.blockPosition(), 160)) {
			chores.cancel();
			woolTrip = sheepSeenAt;
			woolTripAt = player.level().getGameTime();
			woolOwn = false;
			goals.instant = "going to where it saw sheep (wool for a bed)";
			return "You will go back to the sheep you saw at " + sheepSeenAt.getX() + " " + sheepSeenAt.getZ() + " for wool, for a bed.";
		}
		return "You don't see any sheep, so you will look around for some (wool for a bed).";
	}

	/** Where a bed can go: its foot, and which way its head points (a facing index: north, east, south, west). */
	record BedRoom(BlockPos foot, int facing) {}

	private static final net.minecraft.core.Direction[] FACINGS = {net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
			net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST};

	/** Room for a bed close by (within reach): both halves free (no water), solid ground under both. Null: nowhere (a tight tunnel). */
	BedRoom bedRoom() {
		var level = player.level();
		BlockPos feet = player.blockPosition();
		for (int r = 1; r <= 2; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					for (int dy = 0; dy >= -1; dy--) {
						BlockPos foot = feet.offset(dx, dy, dz);
						if (!free(level, foot) || player.getEyePosition().distanceTo(Vec3.atCenterOf(foot)) > player.blockInteractionRange() - 0.5) continue;
						for (int i = 0; i < 4; i++) {
							BlockPos head = foot.relative(FACINGS[i]);
							if (!head.equals(feet) && !head.equals(feet.above()) && free(level, head) && !level.getBlockState(head.above()).isSuffocating(level, head.above())
								&& !level.getBlockState(foot.above()).isSuffocating(level, foot.above()))
							return new BedRoom(foot.immutable(), i);   // (room over its head end too: else the game says the bed is obstructed)
						}
					}
				}
			}
		}
		return null;
	}

	/** Free for half a bed: replaceable, dry, on something solid. */
	private static boolean free(net.minecraft.world.level.Level level, BlockPos p) {
		return level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty() && !level.getBlockState(p.below()).canBeReplaced();
	}

	/** A bed it put down out in the wild for the night (it takes it with it in the morning). */
	private BlockPos campBed;

	private Action bedtime() {
		var level = (ServerLevel) player.level();
		if (player.isSleeping()) {
			goals.instant = "sleeping";
			return Action.IDLE;
		}
		long time = Compat.timeOfDay(level) % 24000;
		boolean night = time >= 12600 && time <= 23400;
		if ((!night || campBedBad) && campBed != null && !fighting) {         // morning (or a bad spot for it): the bed comes along
			if (!(level.getBlockState(campBed).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
				campBed = null;
				if (campBedBad) lookedForBedAt = 0;                                // (somewhere else for it, now)
				campBedBad = false;
			} else {
				goals.instant = "packing up its bed";
				if (packingSince == 0) packingSince = level.getGameTime();
				if (level.getGameTime() - packingSince > 20 * 60) {                // (a minute and it can't get to it: it leaves it, and remembers it)
					journal("thinks", "couldn't get to its bed at " + campBed.toShortString() + " to pack it up: leaves it there");
					places.remember("bed", campBed);
					campBed = null;
					packingSince = 0;
				} else {
					if (!hands.canClick(campBed)) return walkTo(Vec3.atBottomCenterOf(campBed));
					hands.mayBreakBed = true;
					boolean ok = hands.mine(campBed);
					hands.mayBreakBed = false;
					if (ok) {
						acted = true;
						return Action.MINE;
					}
				}
			}
		}
		if (campBed == null) packingSince = 0;
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;   // (a bed anywhere else blows up: a player learns that once)
		if (night && !fighting && (goals.home == null || player.distanceToSqr(Vec3.atCenterOf(goals.home)) > 64 * 64) && campBed == null
				&& mode != Mode.FOLLOW && !chores.busy() && !builder.busy() && player.onGround() && level.getGameTime() - lookedForBedAt > 600) {
			lookedForBedAt = level.getGameTime();                                 // out in the wild with a bed on it: it puts it down and sleeps
			int slot = hands.hotbar(st -> BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("_bed"));
			BedRoom room = slot >= 0 ? bedRoom() : null;                           // (room for it: two free blocks on solid ground, any way round)
			if (room != null && hands.placeItem(room.foot(), st -> BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().endsWith("_bed"), room.foot().below(),
					net.minecraft.core.Direction.UP, room.facing())) {                   // (it faces that way: the bed goes that way)
				campBed = room.foot();
				bedAt = campBed;
				chatter(pick3("Camping out here tonight.", "Bed down, sleep time.", "Good thing I brought my bed."), false);
				acted = true;
				return Action.PLACE;
			}
		}
		if (campBed != null && night && !fighting && level.getBlockState(campBed).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
			Action a = sleepIn(campBed, true);
			if (a != null) return a;
		}
		if (!night || fighting || goals.home == null || mode == Mode.FOLLOW || chores.busy() || builder.busy()
				|| player.distanceToSqr(Vec3.atCenterOf(goals.home)) > 64 * 64) return null;
		if (bedAt == null || !(level.getBlockState(bedAt).getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
			bedAt = null;
			if (level.getGameTime() - lookedForBedAt < 600) return null;
			lookedForBedAt = level.getGameTime();
			for (BlockPos q : BlockPos.betweenClosed(goals.home.offset(-8, -3, -8), goals.home.offset(8, 3, 8))) {
				if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
					bedAt = q.immutable();
					break;
				}
			}
			if (bedAt == null) return null;
		}
		return sleepIn(bedAt, false);
	}

	/** What the game last told it over its hotbar (a translation key, like "block.minecraft.bed.not_safe"), and when. */
	String toldKey = "";
	long toldAt = -1;
	/** A bed it couldn't sleep in, and till when it leaves it be (it knows why: it read the game's message). */
	private BlockPos noSleepBed;
	private long noSleepUntil;
	private String noSleepWhy = "";
	private int bedClicks;
	private long packingSince;

	/**
	 * Lies down in that bed, like a player: close enough for the game (3 blocks across, 2 up or down), it right-clicks it;
	 * if it doesn't lie down, it reads what the game said (night only, monsters about, something over the bed, someone in
	 * it) and does something about it, instead of clicking again and again. Null: not this bed now.
	 */
	private Action sleepIn(BlockPos bed, boolean own) {
		var level = (ServerLevel) player.level();
		long now = level.getGameTime();
		if (bed.equals(noSleepBed) && now < noSleepUntil) return null;
		goals.instant = "going to bed";
		Vec3 at = Vec3.atBottomCenterOf(bed);
		boolean close = Math.abs(player.getX() - at.x) <= 2.5 && Math.abs(player.getZ() - at.z) <= 2.5 && Math.abs(player.getY() - at.y) <= 1.5;
		if (!close || !hands.canClick(bed)) return walkTo(at);
		hands.stop();
		toldKey = "";
		if (!hands.use(bed)) return walkTo(at);
		acted = true;
		if (player.isSleeping()) {
			bedClicks = 0;
			chatter(pick3("Good night!", "Time to sleep. Night night.", "Zzz..."), false);
			return Action.IDLE;
		}
		String why = toldAt == now ? toldKey : "";
		long wait;
		String thought;
		switch (why) {
			case "block.minecraft.bed.not_safe" -> {                              // monsters close: it can't, like anyone
				wait = 20 * 15;
				thought = "monsters nearby";
				if (!noSleepWhy.equals(why)) chatter(pick3("Can't sleep, there are monsters nearby.", "Monsters around... no sleeping yet.", "I can't sleep with monsters this close."), false);
			}
			case "block.minecraft.bed.no_sleep" -> {                              // not night yet (or a storm over)
				wait = 20 * 30;
				thought = "it isn't night yet";
			}
			case "block.minecraft.bed.obstructed" -> {                            // something over it: this bed's no good tonight
				wait = 20 * 60 * 10;
				thought = "something is over the bed";
				if (own) campBedBad = true;
			}
			case "block.minecraft.bed.occupied" -> {
				wait = 20 * 60 * 10;
				thought = "someone is in it";
				if (!own) bedAt = null;
			}
			case "block.minecraft.bed.too_far_away" -> {                         // a step closer
				wait = 0;
				thought = "too far from it";
			}
			default -> {                                                          // nothing said: a few tries, then it leaves it
				wait = ++bedClicks >= 3 ? 20 * 60 * 5 : 20;
				thought = "it doesn't know why";
			}
		}
		if (wait > 0) {
			if (!why.isEmpty()) bedClicks = 0;
			noSleepBed = bed.immutable();
			noSleepUntil = now + wait;
			if (!noSleepWhy.equals(why) || wait >= 20 * 60) journal("thinks", "can't sleep in the bed at " + bed.toShortString() + ": " + thought);
			noSleepWhy = why;
		}
		return Action.IDLE;
	}

	/** Its camp bed is no good where it is (something over it): it packs it up and puts it down somewhere else. */
	private boolean campBedBad;

	/** Where it died, with its things lying there, and until when they're there. */
	private BlockPos lostAt;
	private double lostBest;
	private long lostProgressAt;
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lostDimension;
	private long lostUntil;
	private boolean saidGoingBack;
	/**
	 * How long its things have really been lying there: dropped things only count down their five minutes while a player
	 * is close enough to keep that ground going (the server's simulation distance). Away from everyone, they wait.
	 */
	private long lostAged, lostAgedAt;
	/** It found some of its things there (or none: they were gone). */
	private boolean lostFoundAny;

	/** Back for its things after dying, like a player (before they're gone, and not straight into what killed it). */
	/** Going back for its things (they're lying where it died)? */
	boolean lostThings() {
		return lostAt != null;
	}

	/** Of its things lying where it died, the one it's picking up now. */
	private UUID lostItem, lostItemNext;
	/** What killed it last ("skeleton", "lava"), for the sign it leaves there. */
	String diedOf = "";

	private Action backForMyThings() {
		if (lostAt == null || player.level().dimension() != lostDimension || fighting) return null;
		long now = player.level().getGameTime();
		if (now - lostAgedAt >= 20) {                                            // (once a second: is the ground there going?)
			if (lostThingsTicking()) lostAged += now - lostAgedAt;
			lostAgedAt = now;
		}
		if (now > lostUntil || lostAged >= 5800 || emotions.fear > 0.8f) {
			lostAt = null;
			saidGoingBack = false;
			return null;
		}
		double far = Vec3.atCenterOf(lostAt).distanceTo(player.position());
		long day = Compat.timeOfDay(player.level()) % 24000, toMorning = day < 23000 ? 23000 - day : 0;
		boolean vanishing = lostThingsTicking() && 5800 - lostAged < toMorning && far <= 64 && player.getHealth() >= 10;   // (by morning they'd be gone: near enough, it runs for them now)
		if (player.level().isDarkOutside() && player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && far > 12 && !hands.hasWeapon() && !vanishing) {   // night, nothing to fight with: dug in until morning, then back for them
			goals.instant = "waiting for morning to go back for its things";
			if (!chores.sheltered() && chores.digIn() != null) {
				chores.own = true;
				journal("thinks", String.format(java.util.Locale.ROOT, "night, no weapon, its things %.0f blocks off: digs in and goes back in the morning (%s)",
						far, lostThingsTicking() ? "they'll be gone by then if someone's near them" : "nobody's near them, so they'll wait"));
				chatter(pick3("Not going back out there in the dark. Morning.", "I'll get my stuff when it's light.", "Digging in. My things can wait till morning."), true);
				return chores.next();
			}
			return null;
		}
		if (goals.outAtNight() && !hands.hasWeapon() && !vanishing && player.level().canSeeSky(lostAt.above())     // night, out in the open, no sword: a tip comes to mind (its call)
				&& tips.take(Tips.Tip.NO_SWORD_NIGHT, 0.4f + (diedOf.isEmpty() || diedOf.contains("fall") ? 0 : 0.3f) + 0.2f * Math.min(1, (float) (far / 64)),
						"no sword, night, its things out where it died" + (diedOf.isEmpty() ? "" : " (" + diedOf + ")"), 600)) {
			goals.instant = "waiting for morning to go back for its things";
			return null;
		}
		if (!saidGoingBack) {
			saidGoingBack = true;
			lostBest = Double.MAX_VALUE;
			lostProgressAt = now;
			chatter("My stuff! I'm going back for it.", true);
			askFriendAlong(far);                                          // (back there with nothing on it: better with a friend)
		}
		Action ready = readyAlone(far, now);                                  // nobody came: a few blocks and stone tools first
		if (ready != null) return ready;
		if (aloneUntil == 0 && far > 8 && crafter.pickTier() == 0 && !crafter.canMake(1) && 5800 - lostAged > 2400 && !player.level().canSeeSky(lostAt.above())
				&& lostAt.getY() < player.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lostAt.getX(), lostAt.getZ()) - 4) {
			if (lostToolUntil == 0) {                                            // its things are down in the ground, no pickaxe: a basic one first
				lostToolUntil = now + 2400;
				journal("thinks", "its things are underground and it has no pickaxe: a few logs for one first, then back for them");
			}
			if (now < lostToolUntil) {
				if (!chores.busy()) {
					chores.gather("wood", 3);
					chores.own = true;
				}
				goals.instant = "a few logs for a pickaxe, then back for its things";
				Action a = chores.next();
				if (a != null) return a;
			}
		}
		goals.instant = "going back for its things";
		rushingBack = now;                                                     // (straight there: no ore on the way, it runs)
		BlockPos spot = lostAt;                                                // under water: its things float up, so it goes to the top over the spot
		for (int k = 0; k < 96 && player.level().getFluidState(spot).is(net.minecraft.tags.FluidTags.WATER); k++) spot = spot.above();   // (never down to the sea floor: that's how it drowned)
		if (!spot.equals(lostAt)) spot = spot.below();
		Vec3 target = Vec3.atBottomCenterOf(spot);
		if (Vec3.atCenterOf(spot).distanceTo(player.position()) <= 3) {         // there: its things, the ones it can see (or right by it)
			target = null;
			double bestScore = 0;
			for (var item : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(6),
					e -> e.isAlive() && (e.distanceTo(player) < 2 || player.hasLineOfSight(e)))) {
				if (item.getUUID().equals(lostItem)) {                          // the one it's going for: that first (no dithering)
					target = item.position();
					lostItemNext = lostItem;
					break;
				}
				lostFoundAny = true;
				double score = pickValue(item.getItem()) / (1 + 0.25 * item.distanceTo(player));   // its best things first, then the nearest
				if (target == null || score > bestScore) {
					target = item.position();
					bestScore = score;
					lostItemNext = item.getUUID();
				}
			}
			if (target != null && lostItemNext != null) lostItem = lostItemNext;
			lostItemNext = null;
			if (target == null && helper != null && helper.escortFor == this && !helper.escortGot.isEmpty()) {   // its friend picked them up: they'll hand them over
				lostAt = null;
				saidGoingBack = false;
				chatter(pick3("Thanks " + helper.name + ", you got my stuff!", helper.name + " has my things. Legend.", "Nice, " + helper.name + " grabbed it all."), true);
				return null;
			}
			if (target == null) {
				lostAt = null;
				saidGoingBack = false;
				chatter(lostFoundAny ? "Got my things back!" : pick3("My stuff's gone. Starting over.", "Nothing left. Oh well.", "Gone. Should've been quicker."), true);
				note(diedOf.isEmpty() ? "I died here. Careful." : "I died here (" + diedOf + "). Careful.");   // (a sign for whoever comes by)
				return null;
			}
		}
		double gap = target.distanceTo(player.position());                    // getting closer? (closer to the spot, then to each thing)
		if (gap < lostBest - 0.5) {
			lostBest = gap;
			lostProgressAt = now;
		}
		if (now - lostProgressAt > 600) {                                    // half a minute and no closer: it lets it go, like a player
			lostAt = null;
			saidGoingBack = false;
			walker.stop();
			chatter(pick3("I can't get to my stuff. Oh well.", "Forget it, my things are gone.", "I'll never reach it. Starting over!"), true);
			journal("does", "gives up on its lost things (no way to them)");
			return null;
		}
		return walkTo(target);
	}

	/** Going back alone: till when it gets ready first (0: not yet weighed, -1: ready, or no time for it: it goes). */
	private long aloneUntil;

	/**
	 * Nobody came along: before going back alone, blocks (sixteen if there's time, never none) and basic stone gear (a
	 * stone pickaxe, a stone axe or sword), with the time its things have left and the way back there in mind.
	 */
	private Action readyAlone(double far, long now) {
		if (helper != null || aloneUntil < 0 || far < 16) return null;
		int blocks = walker.blocks();
		boolean pick = crafter.pickTier() >= 2, weapon = stoneWeapon();
		long left = 5800 - lostAged - (long) (far * 5);                       // (its things' time, less the way there at a run)
		if (blocks >= 16 && pick && weapon || left < 200) {
			if (aloneUntil > 0) journal("thinks", blocks >= 16 && pick && weapon ? "ready: " + blocks + " blocks and stone tools, back for its things"
					: "no more time to get ready: back for its things as it is (" + blocks + " blocks)");
			aloneUntil = -1;
			return null;
		}
		if (aloneUntil == 0) {
			aloneUntil = now + Math.min(2400, Math.max(300, left - 600));     // (two minutes at most, a margin left over)
			java.util.List<String> needs = new java.util.ArrayList<>();
			if (blocks < 16) needs.add(blocks == 0 ? "some blocks (it has none)" : "blocks (" + blocks + " of 16)");
			if (!pick) needs.add("a stone pickaxe");
			if (!weapon) needs.add("a stone weapon");
			journal("thinks", String.format(java.util.Locale.ROOT, "nobody came along: %s first, then back alone (%.0f blocks off, about %d s before its things go)",
					String.join(", ", needs), far, (5800 - lostAged) / 20));
			chatter(pick3("Fine, I'll go alone. Getting some blocks and stone tools first.", "Nobody? OK. Tools first, then my stuff.",
					"Going alone then. Let me grab some stone first."), true);
		}
		if (now >= aloneUntil && (blocks > 0 || now >= aloneUntil + 300)) {   // time's up (one block at least, if it can get one quickly)
			journal("thinks", "out of time getting ready: back for its things with " + blocks + " blocks" + (pick ? "" : ", no stone pickaxe") + (weapon ? "" : ", no stone weapon"));
			aloneUntil = -1;
			if (chores.own) chores.cancel();
			return null;
		}
		goals.instant = "getting blocks and stone tools, then back for its things alone";
		if (!chores.busy()) {
			var have = items();
			int stone = have.getOrDefault("cobblestone", 0), wood = 4 * have.getOrDefault("log", 0) + have.getOrDefault("stick", 0);
			for (var e : have.entrySet()) if (e.getKey().endsWith("_planks")) wood += e.getValue();
			boolean under = !player.level().canSeeSky(player.blockPosition());
			int toolStone = (pick ? 0 : 3) + (weapon ? 0 : 3);
			if (now >= aloneUntil) chores.gather(under && crafter.pickTier() >= 1 ? "stone" : "dirt", 1);   // (the one block it won't go without)
			else if (toolStone > 0 && wood < 4 && crafter.pickTier() >= 1) chores.gather("wood", 2);         // (sticks for the handles)
			else if (toolStone > stone) chores.gather("stone", toolStone - stone + (under ? Math.max(0, 16 - blocks) : 0));   // (no pickaxe: wood for one first, by itself)
			else chores.gather(under && crafter.pickTier() >= 1 ? "stone" : "dirt", 16 - blocks);
			chores.own = true;
		}
		return chores.next();
	}

	/** A stone weapon or better (a sword or an axe, not wood or gold). */
	private boolean stoneWeapon() {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			String n = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if ((n.endsWith("_sword") || n.endsWith("_axe")) && !n.startsWith("wooden") && !n.startsWith("golden")) return true;
		}
		return false;
	}

	/** Going back for its things with a friend: who came along (null: nobody). */
	private Companion helper;
	/** Helping a friend get their things back: whose, where they are, till when, and what of theirs it picked up (to hand back). */
	private Companion escortFor;
	private BlockPos escortSpot;
	private long escortUntil;
	final Map<String, Integer> escortGot = new HashMap<>();
	private Map<String, Integer> escortHad = new HashMap<>();

	/**
	 * Back for its things with nothing on it: it asks a friend close by (its team, or one it trusts) to come along. The
	 * friend decides (kindness, loyalty, trust, alike or not, armed or not, busy or not), like answering a call for help.
	 */
	private void askFriendAlong(double far) {
		if (inArena || minion || lostAt == null || far < 6) return;
		for (Companion o : mod.companions) {
			if (o == this || o.player == null || !o.player.isAlive() || o.player.level() != player.level() || o.player.distanceTo(player) > 64) continue;
			if (o.inArena || o.minion || o.lostAt != null || o.escortFor != null || o.fightingNow()) continue;
			float t = o.trust(player.getUUID());
			if (!o.player.isAlliedTo(player) && t < 0.4f) continue;
			var p = o.personality;
			float chance = 0.15f + 0.35f * p.kindness + 0.25f * p.loyalty + 0.3f * Math.max(0f, t) + (Personality.kindred(p, personality) ? 0.2f : 0f)
					+ (o.player.isAlliedTo(player) ? 0.2f : 0f) + (o.fighter.style() == Fighter.Style.TEAM ? 0.25f : 0f)
					- (o.hands.hasWeapon() ? 0f : 0.3f) - (o.chores.busy() ? 0.15f : 0f) - (o.player.getHealth() < 12 ? 0.3f : 0f);
			String spot = lostAt.getX() + " " + lostAt.getY() + " " + lostAt.getZ();
			say(pick3("Hey " + o.name + ", help me get my stuff back? It's at " + spot + ".", o.name + ", come with me? I died at " + spot + ".",
					o.name + "! My things are at " + spot + ", cover me?"));
			boolean goes = o.random.nextFloat() < chance;
			journal("thinks", "asks " + o.name + " to come along for its things: " + (goes ? "they come" : "they don't"));
			if (goes) {
				helper = o;
				o.escortFor = this;
				o.escortSpot = lostAt;
				o.escortUntil = player.level().getGameTime() + 20 * 180;
				o.escortGot.clear();
				o.escortHad = o.rawItems();
				o.chores.cancel();
				o.journal("thinks", "goes with " + name + " to get their things back (at " + spot + ")");
				mod.later(15 + o.random.nextInt(20), () -> o.say(o.pick3("Sure, I've got you.", "On my way, stay close.", "Yeah, let's go get it.")));
			} else {
				mod.later(15 + o.random.nextInt(20), () -> o.say(o.pick3("Can't right now, sorry.", "Busy, good luck!", "Nah, I'll pass.")));
			}
			return;                                                       // (one ask: then it goes, with them or alone)
		}
	}

	/** What it carries by item id (not merged like items(): to hand back exactly what it picked up). */
	private Map<String, Integer> rawItems() {
		Map<String, Integer> out = new HashMap<>();
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum);
		}
		return out;
	}

	/** With a friend back to their things: guard them, pick up what's lying there, then hand it over. Null: not on it (or a fight first). */
	private Action escortNext() {
		if (escortFor == null) return null;
		Companion o = escortFor;
		long now = player.level().getGameTime();
		if (o.player == null || !o.player.isAlive() || o.player.level() != player.level() || now > escortUntil && escortGot.isEmpty() || now > escortUntil + 20 * 120) {
			escortFor = null;                                              // (they're gone, or it's been too long: its own things again)
			if (o.helper == this) o.helper = null;
			return null;
		}
		if (fighting) return null;                                         // (a monster first: that's half of why it came)
		for (net.minecraft.world.entity.Mob m : player.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, o.player.getBoundingBox().inflate(12),
				m -> m.isAlive() && m.getTarget() == o.player && hostile(m))) {
			if (!helpGoing && hands.hasWeapon()) {                         // one after them: its fight
				helpAgainst = m;
				helpGoing = true;
				helpUntil = now + 20 * 20;
			}
			break;
		}
		Map<String, Integer> has = rawItems();                             // what it picked up since it came: theirs
		for (var e : has.entrySet()) {
			int more = e.getValue() - escortHad.getOrDefault(e.getKey(), 0);
			if (more > 0) escortGot.merge(e.getKey(), more, Integer::sum);
		}
		escortHad = has;
		var lying = escortSpot == null ? java.util.List.<net.minecraft.world.entity.item.ItemEntity>of()
				: player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(escortSpot).inflate(8, 4, 8), it -> it.isAlive());
		boolean there = escortSpot != null && player.blockPosition().closerThan(escortSpot, 8);
		if (!lying.isEmpty() && (there || player.blockPosition().closerThan(escortSpot, 40))) {   // their things on the ground: it picks them up
			var it = lying.stream().min(java.util.Comparator.comparingDouble(x -> x.distanceToSqr(player))).get();
			goals.instant = "picking up " + o.name + "'s things";
			return stepTo(it.position());
		}
		if (!there && lying.isEmpty() && escortGot.isEmpty() && escortSpot != null && now < escortUntil) {   // not there yet: on the way, with them
			goals.instant = "going with " + o.name + " to their things";
			return walkTo(Vec3.atBottomCenterOf(escortSpot));
		}
		if (escortGot.isEmpty()) {                                         // nothing left there, nothing of theirs on it: done
			escortFor = null;
			if (o.helper == this) o.helper = null;
			return null;
		}
		if (player.distanceTo(o.player) > 2.5) {                           // theirs on it: over to them
			goals.instant = "bringing " + o.name + " their things";
			return walkTo(o.player.position());
		}
		var inv = player.getInventory();
		hands.face(o.player.position().add(0, 0.2, 0));
		for (var e : new java.util.ArrayList<>(escortGot.entrySet())) {     // at their feet, all of it, the way players hand things over
			int left = e.getValue();
			for (int i = 0; i < 36 && left > 0; i++) {
				ItemStack st = inv.getItem(i);
				if (st.isEmpty() || !BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().equals(e.getKey())) continue;
				ItemStack out = st.split(Math.min(left, st.getCount()));
				left -= out.getCount();
				if (!Compat.tossTo(player, out, o.player.getUUID())) inv.add(out);
			}
		}
		journal("does", "handed " + o.name + " back their things: " + escortGot);
		say(pick3("Here, " + o.name + ", all your stuff.", "There you go, everything I found.", "Got it all back for you."));
		escortGot.clear();
		escortHad = rawItems();
		escortFor = null;
		if (o.helper == this) o.helper = null;
		acted = true;
		return Action.IDLE;
	}

	/** When it last ran back for its things (no stopping for ore meanwhile), and till when it gets a pickaxe first. */
	private long rushingBack = -100, lostToolUntil;

	/** Is the ground its things lie on going (a player, a Xen too, within the server's simulation distance of it)? */
	private boolean lostThingsTicking() {
		var server = ((net.minecraft.server.level.ServerLevel) player.level()).getServer();
		double reach = (server.getPlayerList().getSimulationDistance() + 1) * 16.0;
		for (var other : server.getPlayerList().getPlayers()) {
			if (other.level().dimension() != lostDimension) continue;
			double dx = other.getX() - lostAt.getX(), dz = other.getZ() - lostAt.getZ();
			if (Math.max(Math.abs(dx), Math.abs(dz)) <= reach) return true;
		}
		return false;
	}

	/** Armor it made or found: on, like a player shift-clicking it in the inventory (the better piece if it has two). */
	private void wearArmor() {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			net.minecraft.world.entity.EquipmentSlot slot = player.getEquipmentSlotForItem(s);
			if (slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR) continue;
			ItemStack worn = player.getItemBySlot(slot);
			if (!worn.isEmpty() && armorValue(worn) >= armorValue(s)) continue;
			if (nether.inNether() && BuiltInRegistries.ITEM.getKey(worn.getItem()).getPath().startsWith("golden_")) continue;   // (the piglins' gold stays on)
			player.setItemSlot(slot, s.copy());
			inv.setItem(i, worn.copy());
			journal("does", "puts on " + BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().replace('_', ' '));
		}
	}

	private static int armorValue(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return n.startsWith("netherite") ? 6 : n.startsWith("diamond") ? 5 : n.startsWith("iron") ? 4 : n.startsWith("chainmail") ? 3
				: n.startsWith("golden") ? 2 : n.startsWith("leather") || n.startsWith("turtle") ? 1 : 0;
	}

	/** Its legs: finding the way and walking it (it decides where to, and how bold it is on the way). */
	final Walker walker = new Walker(this);
	/** What it remembers of the world: places, and the ways it walked (see {@link Places}). */
	final Places places = new Places(this);
	/** Chests: its own, its tribe's, and loot (see {@link Storage}). */
	final Storage storage = new Storage(this);
	/** Portals and the Nether (see {@link Nether}). */
	final Nether nether = new Nether(this);
	/** The Ender Dragon fight (see {@link Dragon}), and the long way there (see {@link Adventure}). */
	final Dragon dragon = new Dragon(this);
	final Adventure adventure = new Adventure(this);
	/** Trial chambers (see {@link Trials}). */
	final Trials trials = new Trials(this);
	/** What it knows of how the game works, and what it doesn't yet (see {@link Knowledge}). */
	final Knowledge knowledge = new Knowledge(this);
	/** What it has worked out about playing (where ore is best), how sure, from whom (see {@link Lessons}). */
	final Lessons lessons = new Lessons(this);
	/** Its role, purpose and current goal, and how its village's leader leans it (Xen 6.0). */
	final Purpose purpose = new Purpose(this);
	/** How quickly it gets to things: a moment to get ready to craft, noticing a broken tool (game ticks, never sleeps). */
	final Pace pace = new Pace(this);
	/** Ready for the worst: packed before a trip, valuables at home, spares after a death (see {@link Prepared}). */
	final Prepared prepared = new Prepared(this);
	/** What people around it need (it may help: its choice). */
	final Needs needs = new Needs(this);
	/** Highways it builds (in the Nether from a portal, or roads). */
	final Highway highway = new Highway(this);
	/** Going far: elytra flights, the End-city quest, the worlds it has been to. */
	final Voyager voyager = new Voyager(this);
	/** Enchanting its gear at an enchanting table. */
	final Enchanter enchanter = new Enchanter(this);
	/** How it likes to build (learned from its houses, what people say, its tribe). */
	final Taste taste = new Taste(this);
	/** Its crop farm (see {@link Farmer}). */
	final Farmer farmer = new Farmer(this);
	/** Its shop in the village, if it keeps one (see {@link Market}). */
	final Market.Keeper shop = new Market.Keeper(this);
	/** The band of free Xens it joined (see {@link Tribe}), or null. */
	String band;

	java.util.Random random() {
		return random;
	}

	/** Its tribe (the Xens it lives with), or null. */
	Tribe tribe() {
		return mod.tribes.get(Tribe.key(this));
	}

	/**
	 * Danger a player would hold a totem for: the End, low health, lava or fire, a long fall, or a real fight going
	 * badly. (Then a totem of undying goes in its off hand, before the shield.)
	 */
	boolean dangerous() {
		if (player == null) return false;
		return dragon.inEnd() || player.getHealth() <= 10 || player.isInLava() || player.isOnFire() || player.fallDistance > 5
				|| fighting && player.getHealth() <= 14;
	}

	private int totemsHad = -1;

	/** A totem in its off hand when things get dangerous; and when one saves its life, it knows (and says so). */
	private void totems() {
		int n = 0;
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (Hands.isTotem(inv.getItem(i))) n += inv.getItem(i).getCount();
		if (Hands.isTotem(player.getOffhandItem())) n++;
		if (totemsHad > n && player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) && player.getHealth() <= 8) {
			say(pick3("My totem saved me!", "Whoa, that was close. Bye, totem.", "The totem! I'd be dead without it."));
			journal("does", "a totem of undying saved its life");
		}
		totemsHad = n;
		if (n > 0 && dangerous()) hands.holdTotem();
	}

	/**
	 * Shoot at a point with its bow, like a player: draw it (the right button held for a second), aim above the target
	 * so the arrow's fall brings it down onto it (and ahead of it if it's moving), let go. Null without a bow and arrows,
	 * or if it can't reach that far.
	 */
	Action shoot(Vec3 target, Vec3 motion) {
		if (!hands.hasBow()) return null;
		if (hands.drawn() == 0) {
			if (!hands.drawBow()) return null;
			acted = true;
			return Action.IDLE;
		}
		Vec3 eye = player.getEyePosition();
		int t = Hands.flightTicks(Math.hypot(target.x - eye.x, target.z - eye.z));
		Vec3 at = target.add(motion.scale(t));
		float[] aim = Hands.bowAim(eye, at);
		if (aim == null) {
			player.releaseUsingItem();
			return null;
		}
		hands.aim(aim[0], aim[1]);
		acted = true;
		if (hands.drawn() >= 21) {
			hands.loose();
			journal("does", String.format(java.util.Locale.ROOT, "shoots an arrow at %.0f %.0f %.0f", target.x, target.y, target.z));
		}
		return Action.IDLE;
	}

	/** Things worth picking up when they're lying close by (a player walks over them): keys, rods, pearls, loot, its arrows. */
	private static final Set<String> WORTH = Set.of("trial_key", "ominous_trial_key", "blaze_rod", "ender_pearl", "ender_eye", "arrow", "diamond",
			"emerald", "iron_ingot", "gold_ingot", "raw_iron", "raw_gold", "totem_of_undying", "golden_apple", "enchanted_golden_apple", "string",
			"feather", "gunpowder", "flint", "heavy_core", "breeze_rod", "wind_charge", "obsidian", "name_tag", "saddle", "book", "enchanted_book",
			"music_disc_bounce", "bread", "cooked_beef", "cooked_porkchop", "nether_wart", "gold_nugget", "iron_nugget", "coal", "wheat", "wheat_seeds",
			"carrot", "potato", "beetroot", "beetroot_seeds", "bone_meal", "elytra", "firework_rocket", "lapis_lazuli", "sugar_cane", "leather",
			"paper", "experience_bottle", "shulker_shell", "netherite_scrap", "ancient_debris",
			// (what animals and monsters drop: food for later, bone meal, beds, slime for pistons)
			"beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "cooked_chicken", "cooked_mutton", "cooked_rabbit", "cooked_cod",
			"cooked_salmon", "apple", "bone", "egg", "rabbit_hide", "slime_ball", "ink_sac", "glow_ink_sac", "spider_eye", "honeycomb",
			"white_wool", "black_wool", "gray_wool", "light_gray_wool", "brown_wool", "pink_wool");
	/** How much a thing lying there is worth going for: diamonds before a sword, a sword before iron, iron before bread, bread before string. */
	static int pickValue(ItemStack s) {
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		if (n.contains("diamond") || n.contains("emerald") || n.contains("netherite") || n.equals("ancient_debris") || n.equals("totem_of_undying")
				|| n.equals("elytra") || n.contains("golden_apple") || n.equals("heavy_core") || n.contains("trial_key")) return 10;
		if (s.isDamageableItem()) return 7;
		if (n.contains("iron") || n.contains("gold") || n.equals("ender_pearl") || n.equals("ender_eye") || n.equals("blaze_rod") || n.equals("breeze_rod")
				|| n.equals("obsidian") || n.equals("enchanted_book") || n.equals("lapis_lazuli") || n.equals("coal") || n.equals("name_tag") || n.equals("saddle")) return 6;
		if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null) return 4;
		return 2;
	}

	private final Set<UUID> leftLying = new java.util.HashSet<>();
	private UUID goingFor;
	private long goingSince;

	/** Blocks it broke lately (where, when): what drops there is its own, and it picks it up, like a player. */
	/** The blocks it broke lately: where, when, and whether it was a tree (a log: its leaves drop saplings and sticks for minutes after). */
	private final java.util.ArrayDeque<long[]> broke = new java.util.ArrayDeque<>();

	/** Did it mine a block right by this spot just now (it opened the way for what's there: water coming in, say)? */
	boolean openedNear(BlockPos at, long within) {
		if (player == null) return false;
		long now = player.level().getGameTime();
		for (long[] b : broke) if (now - b[1] <= within && BlockPos.of(b[0]).distManhattan(at) <= 2) return true;
		return false;
	}

	void broke(BlockPos pos, boolean log) {
		broke.addLast(new long[] {pos.asLong(), player == null ? 0 : player.level().getGameTime(), log ? 1 : 0});
		while (broke.size() > 64) broke.removeFirst();
	}

	/**
	 * Its own, to pick up, if there's room for it: lying where it mined a block in the last minute (cobblestone and
	 * all), or under a tree it chopped in the last five minutes (the saplings, sticks and apples its leaves let fall).
	 */
	/** Where things it killed died lately (where, when): what dropped there is its own, and it picks it up, like a player. */
	private final java.util.ArrayDeque<long[]> killed = new java.util.ArrayDeque<>();

	/** Every few ticks: did what it hit last die (it killed it)? Then where it fell is noted. */
	private void noteKills() {
		var e = hands.lastHit;
		if (e == null) return;
		long now = player.level().getGameTime();
		if (!e.isAlive() || e.isRemoved()) {
			if (now - hands.lastHitAt < 60) {
				if (e instanceof net.minecraft.world.entity.monster.Monster || e instanceof ServerPlayer) belief.up(0.03f, "beat " + e.getName().getString());
				rage.killed(e);                                                     // (a kill takes the edge off its anger and bloodlust)
				fighter.beat(e);                                                    // (what that one cost it: learned)
				killed.addLast(new long[] {e.blockPosition().asLong(), now});
				while (killed.size() > 16) killed.removeFirst();
			}
			hands.lastHit = null;
		} else if (now - hands.lastHitAt > 200) {
			hands.lastHit = null;
		}
	}

	private boolean killedIt(net.minecraft.world.entity.item.ItemEntity e) {
		long now = player.level().getGameTime();
		if (e.getAge() > 20 * 120 || !room(e.getItem())) return false;
		for (long[] k : killed) {
			if (now - k[1] > 20 * 120) continue;
			BlockPos p = BlockPos.of(k[0]);
			double dx = e.getX() - p.getX() - 0.5, dy = e.getY() - p.getY(), dz = e.getZ() - p.getZ() - 0.5;
			if (dx * dx + dz * dz <= 4 * 4 && dy > -4 && dy < 3) return true;
		}
		return false;
	}

	private boolean minedIt(net.minecraft.world.entity.item.ItemEntity e) {
		if (killedIt(e)) return true;                                   // (what it killed dropped: its own too)
		long now = player.level().getGameTime();
		if (e.getAge() > 20 * 300 || !room(e.getItem())) return false;
		for (long[] b : broke) {
			boolean tree = b[2] == 1;
			if (now - b[1] > 20 * (tree ? 300 : 60) || !tree && e.getAge() > 20 * 60) continue;
			BlockPos p = BlockPos.of(b[0]);
			double dx = e.getX() - p.getX() - 0.5, dy = e.getY() - p.getY() - 0.5, dz = e.getZ() - p.getZ() - 0.5;
			if (tree ? dx * dx + dz * dz <= 5 * 5 && dy > -12 && dy < 3 : dx * dx + dy * dy + dz * dz <= 3.5 * 3.5) return true;
		}
		return false;
	}

	private boolean room(net.minecraft.world.item.ItemStack s) {
		var inv = player.getInventory();
		if (inv.getFreeSlot() >= 0) return true;
		for (int i = 0; i < 36; i++) {
			var have = inv.getItem(i);
			if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(have, s) && have.getCount() < have.getMaxStackSize()) return true;
		}
		return false;
	}

	private long bucketLookAt;

	/** An empty bucket and still water in reach, nothing else going on: it fills it, like a player keeping one for falls. */
	private Action fillBucket() {
		long now = player.level().getGameTime();
		if (now - bucketLookAt < 40 || fighting || hands.busy() || player.isInWater()) return null;
		bucketLookAt = now;
		var inv = player.getInventory();
		boolean empty = false, full = false;
		for (int i = 0; i < 36; i++) {
			String n = BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
			if (n.equals("bucket")) empty = true;
			else if (n.equals("water_bucket")) full = true;
		}
		if (!empty || full) return null;
		ServerLevel level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 1, 3))) {
			if (!level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(q).isSource() || !level.getBlockState(q.above()).isAir()) continue;
			Vec3 at = Vec3.atCenterOf(q).add(0, 0.4, 0);
			if (player.getEyePosition().distanceTo(at) > player.blockInteractionRange() - 0.5) continue;
			if (!hands.bucket(at, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("bucket"))) return null;
			goals.instant = "filling its bucket with water";
			journal("does", "filled its bucket at " + q.toShortString() + " (water for a fall)");
			acted = true;
			return Action.PLACE;
		}
		return null;
	}

	/** Things not worth building a way to (it puts down the same blocks to get there). */
	private static final java.util.Set<String> CHEAP = java.util.Set.of("cobblestone", "cobbled_deepslate", "dirt", "gravel", "sand", "netherrack",
			"andesite", "diorite", "granite", "tuff", "deepslate", "stone", "flint", "wheat_seeds");

	private Action pickUpNearby() {
		if (fighting || mode == Mode.STAY && chores.busy()) return null;
		net.minecraft.world.entity.item.ItemEntity best = null, current = null;
		double bestScore = 0, currentScore = 0;
		for (var e : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(8, 3, 8),
				x -> x.isAlive() && !leftLying.contains(x.getUUID()) && (WORTH.contains(BuiltInRegistries.ITEM.getKey(x.getItem().getItem()).getPath())
						|| x.getItem().isDamageableItem() || sharing.expected(x.getItem()) || minedIt(x)))) {   // (a tool or armor lying there too; what a friend just tossed to it; what it mined)
			if (!player.hasLineOfSight(e)) continue;
			double score = Math.max(pickValue(e.getItem()), minedIt(e) ? 1 : 0) / (1 + 0.25 * e.distanceTo(player));   // the best first, then the nearest
			if (e.getUUID().equals(goingFor)) {
				current = e;
				currentScore = score;
			}
			if (best == null || score > bestScore) {
				best = e;
				bestScore = score;
			}
		}
		if (best == null) return null;
		if (current != null && bestScore < currentScore * 2) best = current;   // it keeps to the one it went for (no dithering between two)
		long now = player.level().getGameTime();
		if (!best.getUUID().equals(goingFor)) {
			goingFor = best.getUUID();
			goingSince = now;
		} else if (now - goingSince > 160) {                            // can't get to it: leave it
			leftLying.add(best.getUUID());
			return null;
		}
		String what = BuiltInRegistries.ITEM.getKey(best.getItem().getItem()).getPath();
		goals.instant = "picking up " + what.replace('_', ' ');
		if (CHEAP.contains(what)) walker.noBuildingUntil = now + 20;         // (a block of stone isn't worth putting a block down for: walking to it, or leaving it)
		return stepTo(best.position());                                   // (right by it: a step or two straight there; further: the usual way)
	}

	/** A creaking coming (only its heart can hurt it): back off, looking at it (it freezes when looked at). */
	private Action awayFromCreaking() {
		if (!knowledge.knows("creaking")) {                            // fighting one for a while, and it doesn't get hurt: it learns
			if (fighting && fightingWhat.contains("creaking") && ++creakingFight > 100) knowledge.learn("creaking", Knowledge.How.TRIED);
			return null;
		}
		for (var e : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(10),
				x -> x.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).getPath().equals("creaking"))) {
			if (player.distanceTo(e) > 8) continue;
			goals.instant = "keeping away from the creaking";
			Vec3 away = player.position().subtract(e.position()).normalize().scale(10);
			hands.watching = e;
			chatter(pick3("A creaking! Don't take your eyes off it.", "Creaking... back away slowly.", "Nope, not fighting that thing."), false);
			return walkTo(player.position().add(away));
		}
		return null;
	}

	private int creakingFight;

	/** Hurt by something: some things you learn the hard way (a piglin, when you wear no gold). */
	private void learnFromHurt(LivingEntity by) {
		String n = BuiltInRegistries.ENTITY_TYPE.getKey(by.getType()).getPath();
		if (n.startsWith("piglin") && nether.inNether()) knowledge.learn("piglin_gold", Knowledge.How.TRIED);
	}

	private static String join(String... parts) {
		StringBuilder sb = new StringBuilder();
		for (String p : parts) if (p != null && !p.isEmpty()) sb.append(' ').append(p);
		return sb.toString();
	}


	/** Trouble on the way (a move that didn't work): it tries another way; again and again, and it's stuck. */
	void stuckOnTheWay(String why, int times) {
		if (times >= 3) chores.unreachable();                          // can't get there: another one, on its own
		if (times >= 3 && player.tickCount - stuckSaidAt > 600) {
			stuckSaidAt = player.tickCount;
			if (DEBUG) XenMod.LOG.info("[xen debug] {} is stuck on its way ({} times): {}", name, times, why);
			String plain = walker.stuckWhy();
			BlockPos at = player.blockPosition();
			if (times < 6) {                                          // it says why (in chat and in its journal), and works it out itself first
				journal("way", "stuck " + times + " times: " + plain + "; working it out itself");
				chatter(pick3("Ugh, stuck. " + capital(plain) + ". Let me figure this out.", "Hm, can't get through, " + plain + ". Trying another way.",
						"Stuck again (" + plain + "). Hang on."), false);
			} else {                                                  // still stuck after trying: it asks a teammate, or whoever's about
				String helper = null;
				for (Companion o : mod.companions)
					if (o != this && o.player != null && o.player.level() == player.level() && o.player.distanceTo(player) < 64
							&& (player.isAlliedTo(o.player) || trust(o.player.getUUID()) >= 0.5f)) { helper = o.name; break; }
				ServerPlayer friend = leader == null ? null : server.getPlayerList().getPlayer(leader);
				String to = helper != null ? helper : friend != null && friend.distanceTo(player) < 64 ? friend.getName().getString() : null;
				journal("way", "stuck " + times + " times (" + plain + "), tried its own ways: asks " + (to == null ? "anyone" : to) + " for help");
				say((to == null ? "Anyone around? " : "Hey " + to + ", ") + "I'm stuck at " + at.getX() + " " + at.getY() + " " + at.getZ()
						+ " (" + plain + "). Tried everything I know. Can you help?");
			}
		}
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private int stuckSaidAt = -10000;

	/** Truces, giving up, and what it wants for peace (see {@link Diplomacy}). */
	final Diplomacy diplomacy = new Diplomacy(this);

	private void stats() {
		Vec3 now = player.position();
		if (lastPos != null && lastPos.distanceTo(now) < 2) moved += Math.hypot(now.x - lastPos.x, now.z - lastPos.z);   // (not a respawn or a teleport)
		lastPos = now;
		if (player.tickCount % 1200 != 0) return;
		XenMod.LOG.info(String.format(java.util.Locale.ROOT,
				"[xen stats] %s way=%s moved=%.0f mobplans=%d/%d trouble=%d pacing=%d stuck=%d caves=%d houses=%d ores=%d logs=%d sweeps=%d",
				name, walker.mobPaths() ? "mob" : "xen", moved, walker.mobPath.found, walker.mobPath.found + walker.mobPath.missed, troubles, pacings, stucks,
				eyes.caves, eyes.houses, eyes.count("coal") + eyes.count("iron") + eyes.count("gold") + eyes.count("diamond"), eyes.count("log"), eyes.sweeps));
	}

	/** The mobs and animals around it: arrows, drowned, poison, breeding, shearing, cats (see {@link Critters}). */
	final Critters critters = new Critters(this);
	final Tactics tactics = new Tactics(this);
	final Whims whims = new Whims(this);

	/** Who it's chasing, since when, and the closest it got (and when). */
	private LivingEntity chasing;
	private long chaseSince, closestAt;
	private double closestTo;

	/**
	 * Giving up a chase, like a player: after 15 seconds without getting any closer, or a minute in all, it lets them
	 * go (and doesn't pick that fight again for a while).
	 */
	private void giveUpChase() {
		long now = player.level().getGameTime();
		LivingEntity foe = chosenFoe;
		if (foe == null || !foe.isAlive() || foe.level() != player.level()) {
			chasing = null;
			return;
		}
		double d = player.distanceTo(foe);
		if (foe != chasing) {
			chasing = foe;
			chaseSince = closestAt = now;
			closestTo = d;
			return;
		}
		if (d < closestTo - 1) {
			closestTo = d;
			closestAt = now;
		}
		if (d > 4 && (now - closestAt > 300 || now - chaseSince > 1200)) {
			journal("fight", "gives up chasing " + foe.getName().getString());
			rage.provoke(1, foe.getName().getString() + " got away");
			chosenFoe = null;
			chasing = null;
			lastPickedFight = now;
			if (goals.option == xen.mod.core.Mind.FIGHT) goals.finishOption(false);
			chatter(pick3("Not worth chasing.", "Run then. I've got better things to do.", "Forget it, you're too fast."), false);
		}
	}

	/** Its eyes: every block in view, worth knowing or not (see {@link Eyes}). */
	final Eyes eyes = new Eyes(this);

	/** When it's stuck: a way out it learns (see {@link Solver}). */
	final Solver solver = new Solver(this);

	/**
	 * Walk towards a place: its legs find the way (path assist); when they can't, or keep failing, or it gets no
	 * closer, the solver picks a way out; and if all that's switched off, the old way: straight at it, digging.
	 */
	Action walkTo(Vec3 goal) {
		return walkTo(null, goal);
	}

	/** Walk toward a goal (next to an ore, down to a height, the nearest of several...): see {@link Goal}. */
	Action walkTo(Goal g) {
		return walkTo(g, g.center());
	}

	/**
	 * A drop (or a spot) right by it: it just walks onto it, a step or two straight there, the way a player does (no
	 * route to plan: that's where it stood about, a block short). Further, or up or down a step: the usual way.
	 */
	Action stepTo(Vec3 at) {
		Vec3 d = at.subtract(player.position());
		double flat = Math.hypot(d.x, d.z);
		long now = player.level().getGameTime();
		// straight there only with nothing in the way, and while it gets closer (a wall it walked into: round, the usual way)
		if (flat < stepLast - 0.05 || flat > stepLast + 1 || now - stepCalled > 5) {
			stepLast = flat;
			stepSince = now;
		}
		stepCalled = now;
		if (now - stepSince > 15) stepAroundUntil = now + 60;
		if (flat > 3 || Math.abs(d.y) > 0.6 || !player.onGround() || now < stepAroundUntil || !straightTo(at)) return walkTo(at);
		if (flat < 0.3) return Action.IDLE;                                  // (on it: it's picked up)
		hands.face(new Vec3(at.x, player.getEyeY(), at.z));
		hands.steer = at;
		acted = true;
		return Action.FORWARD;
	}

	private double stepLast = Double.MAX_VALUE;
	private long stepSince, stepCalled, stepAroundUntil;

	/** A clear walk straight there on the level: floor all the way, nothing at its feet or head, no hole, no lava. */
	private boolean straightTo(Vec3 at) {
		ServerLevel level = (ServerLevel) player.level();
		Vec3 from = player.position(), d = at.subtract(from);
		int n = (int) Math.ceil(Math.hypot(d.x, d.z) / 0.3);
		for (int i = 1; i <= n; i++) {
			Vec3 q = from.add(d.x * i / n, 0, d.z * i / n);
			BlockPos feet = BlockPos.containing(q.x, from.y + 0.1, q.z);
			for (BlockPos b : new BlockPos[] {feet, feet.above()}) {
				var s = level.getBlockState(b);
				if (!s.getCollisionShape(level, b).isEmpty() || !s.getFluidState().isEmpty() || s.is(net.minecraft.tags.BlockTags.FIRE)) return false;
			}
			var under = level.getBlockState(feet.below());
			if (under.getCollisionShape(level, feet.below()).isEmpty() || under.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
					|| under.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) return false;
		}
		return true;
	}

	/** When it last looked for ore to take on its way. */
	private int lookedOnTheWay;

	private Action walkTo(Goal g, Vec3 goal) {
		Action ore = oreOnTheWay();                                     // on its way: ore right there, it takes it (like a player would)
		if (ore != null) return ore;
		if (g == null && (voyager.flying() || voyager.shouldFly(goal))) {   // far, under the sky, with an elytra: it flies there
			Action f = voyager.flyTo(goal);
			if (f != null) return f;
		}
		if (mod.config.pathAssist) {
			if (solver.active()) {
				Action a = solver.next(goal);
				if (a != null) return a;
			}
			Action w = g != null ? walker.go(g) : walker.go(goal);
			if (w == null && player.position().distanceTo(goal) < 2) return digToward(goal);   // right there (a drop at its feet): a step, not being stuck
			if (w != null && !walker.stuck()) return w;
			String why = w == null ? "no way it knows of" : walker.lastProblem.isEmpty() ? "getting no closer" : walker.lastProblem;
			if (Walker.WALK_DEBUG) XenMod.LOG.info("[walk] {} calls the solver at {}: {} (w {}, stuck {})", name, player.blockPosition().toShortString(), why, w, walker.stuck());
			if (solver.start(goal, why)) {
				walker.stop();
				Action a = solver.next(goal);
				if (a != null) return a;
			}
			if (w != null) return w;
		}
		return digToward(goal);
	}

	/**
	 * Ore it passes on its way (showing, in sight, within reach, and its pickaxe can take it): it stops a moment and mines
	 * it, then walks on. Not in a fight, running away, scared, in water or with its hands full of something else.
	 */
	/** Ore it stopped for on its way (it sees that through before walking or digging on). */
	private BlockPos oreTaking;

	private Action oreOnTheWay() {
		if (oreTaking != null) {
			if (!fightingNow() && hands.busy() && oreTaking.equals(hands.target()) && !player.level().getBlockState(oreTaking).isAir()) {
				acted = true;
				return Action.MINE;
			}
			oreTaking = null;
		}
		if (player.tickCount - lookedOnTheWay < 10 || fightingNow() || inArena || player.isInWater() || emotions.fear > 0.6f
				|| player.level().getGameTime() - rushingBack < 5                    // (running back for its things before they go: no stopping)
				|| goals.option == xen.mod.core.Mind.FLEE || hands.busy() && !hands.interruptible() || commandedTo != null && visiting == null   // (sent somewhere: there first; digging its way: it stops for ore it passes, a diamond in the wall too)
				|| player.level().isDarkOutside() && player.level().canSeeSky(player.blockPosition())) return null;   // (out at night: keep going)
		lookedOnTheWay = player.tickCount;
		BlockPos ore = chores.oreWithinReach();
		if (ore == null) return null;
		if (hands.busy()) {                                              // (digging its way: that block can wait, the ore first)
			hands.stop();
			walker.stop();
		}
		if (!hands.mine(ore)) return null;
		oreTaking = ore.immutable();
		goals.instant = "taking the ore on its way";
		journal("does", "takes ore on its way at " + ore.toShortString());
		acted = true;
		return Action.MINE;
	}

	/**
	 * In a hole: no way to walk out on any side (the block beside its feet and the one over it open, with ground to stand
	 * on a few blocks down at most, or a step up with room over it).
	 */
	private static boolean boxedIn(ServerLevel level, BlockPos feet) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos q = feet.relative(d);
			boolean open = level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty();
			if (open) {
				for (int k = 1; k <= 4; k++) {
					BlockPos g = q.below(k);
					var st = level.getBlockState(g);
					if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) break;
					if (!st.getCollisionShape(level, g).isEmpty() || !st.getFluidState().isEmpty()) return false;   // ground (or water) to land on
				}
				continue;
			}
			BlockPos up = q.above();                                        // a step up, with room over it and over its head
			if (level.getBlockState(up).getCollisionShape(level, up).isEmpty() && level.getBlockState(up.above()).getCollisionShape(level, up.above()).isEmpty()
					&& level.getBlockState(feet.above(2)).getCollisionShape(level, feet.above(2)).isEmpty()) return false;
		}
		return true;
	}

	/** The old way on foot: straight at it through what it knows, digging through, pillaring up. Null if lava is in the way. */
	Action digToward(Vec3 goal) {
		double dx = goal.x - player.getX(), dz = goal.z - player.getZ();
		ServerLevel level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		boolean below = goal.y - player.getY() >= 1.5;
		// straight up: pillar, but only out of a hole. Out in the open, towering up to a log or an item on the leaves
		// with no way there looked like placing blocks for no reason: it lets that one go.
		if (below && Math.hypot(dx, dz) < 3 && player.onGround()) {
			if (boxedIn(level, feet) && hands.startPillar()) {
				pillaring = true;
				return Action.JUMP;
			}
			if (!boxedIn(level, feet)) return null;
		}
		// Stuck (walked or jumped but didn't move): like a player, dig at head height, then at the feet, then step aside.
		boolean tried = lastAction == Action.FORWARD.ordinal() || lastAction == Action.JUMP.ordinal();
		Vec3 here = player.position();                                 // (jumping on the spot isn't moving)
		boolean moved = walkedFrom == null || Math.hypot(here.x - walkedFrom.x, here.z - walkedFrom.z) >= 0.1;
		stuck = moved ? 0 : tried || stuck >= 4 ? stuck + 1 : stuck;
		walkedFrom = here;
		// Getting anywhere? Walking back and forth (or jumping on the spot) doesn't count: no closer in 3 seconds and it
		// tries other ways, like a player would (dig through, step aside, pillar).
		double gap = Math.hypot(dx, dz) + 0.5 * Math.abs(goal.y - player.getY());
		if (trackedGoal == null || trackedGoal.distanceToSqr(goal) > 4) {
			trackedGoal = goal;
			bestGap = gap;
			bestGapAt = player.tickCount;
		} else if (gap < bestGap - 0.7) {
			bestGap = gap;
			bestGapAt = player.tickCount;
		} else if (player.tickCount - bestGapAt > 60 && gap > 1.5) {
			stuck = Math.max(stuck, 4);
			bestGapAt = player.tickCount;
			if (DEBUG) XenMod.LOG.info("[xen debug] {} isn't getting closer to {}: trying another way", name, goal);
		}
		if (stuck > 13) stuck = 0;                                     // then try finding a way again
		if (stuck >= 4) {
			int phase = (stuck / 3) % 3;
			if (phase == 0) return hands.pitch != 1 ? Action.LOOK_UP : Action.MINE;
			if (phase == 1) return hands.pitch != 0 ? (hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP) : Action.MINE;
			return Action.RIGHT;
		}
		// A way through what it knows (within 6 blocks): walk, step up, drop down, swim, around obstacles and lava.
		int[] step = senses.last == null ? null
				: Paths.firstStep(senses.last, goal.x - (feet.getX() + 0.5), goal.y - feet.getY(), goal.z - (feet.getZ() + 0.5), maxDrop());
		if (step != null) {
			if (step[0] != hands.yaw) {
				run(false);
				return Math.floorMod(step[0] - hands.yaw, 4) == 3 ? Action.TURN_LEFT : Action.TURN_RIGHT;
			}
			int[] ahead = Perception.forward(step[0]);
			hands.steer = Vec3.atBottomCenterOf(feet.offset(ahead[0], 0, ahead[1]));   // the middle of the next block
			run(Math.hypot(dx, dz) > 5 && step[1] == 0);                   // far to go: it runs, like a player
			return step[1] == 1 ? Action.JUMP : Action.FORWARD;
		}
		run(false);
		// No known way gets closer: dig towards it.
		int want = 0;
		double best = -2;
		for (int k = 0; k < 4; k++) {
			int[] f = Perception.forward(k);
			double dot = (dx * f[0] + dz * f[1]) / Math.max(1e-6, Math.hypot(dx, dz));
			if (dot > best) {
				best = dot;
				want = k;
			}
		}
		if (want != hands.yaw) return Math.floorMod(want - hands.yaw, 4) == 3 ? Action.TURN_LEFT : Action.TURN_RIGHT;
		int[] f = Perception.forward(hands.yaw);
		int ahead = cat(level, feet.offset(f[0], 0, f[1])), head = cat(level, feet.offset(f[0], 1, f[1]));
		int above = cat(level, feet.offset(f[0], 2, f[1])), roof = cat(level, feet.above(2));
		if (ahead == Blocks.LAVA || head == Blocks.LAVA || cat(level, feet.offset(f[0], -1, f[1])) == Blocks.LAVA) return null;
		if (!Blocks.SOLID[ahead] && !Blocks.SOLID[head]) {
			if (hands.pitch != 0 && Blocks.SOLID[cat(level, feet.offset(f[0], -1, f[1]))]) return hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP;
			return Action.FORWARD;
		}
		if (Blocks.SOLID[ahead] && !Blocks.SOLID[head] && !Blocks.SOLID[above] && !Blocks.SOLID[roof]) return Action.JUMP;
		// Where it's going is higher up (out of a hole, up a hill): a staircase up, the way players climb out. It clears
		// the block over its head, then the two above the step, and jumps up; never under lava or water.
		if (goal.y - player.getY() >= 1 && Blocks.SOLID[ahead] && player.onGround()) {
			BlockPos stair = feet.offset(f[0], 0, f[1]);
			int overStep = cat(level, stair.above(3)), overHead = cat(level, feet.above(3));
			if (overStep != Blocks.LAVA && overStep != Blocks.WATER && overHead != Blocks.LAVA && overHead != Blocks.WATER) {
				BlockPos dig = Blocks.SOLID[roof] ? feet.above(2) : Blocks.SOLID[head] ? stair.above() : Blocks.SOLID[above] ? stair.above(2) : null;
				if (dig != null && hands.mine(dig)) {
					acted = true;
					return Action.MINE;
				}
			}
		}
		// Blocked, like in a hole: stand on something (pillar) or dig a staircase out, the way a player would.
		if (below && player.onGround() && boxedIn(level, feet) && hands.startPillar()) {
			pillaring = true;
			return Action.JUMP;
		}
		if (Blocks.SOLID[head]) return hands.pitch != 1 ? Action.LOOK_UP : Action.MINE;          // clear head height
		if (Blocks.SOLID[ahead]) {                                                              // then the feet, and walk through
			return hands.pitch != 0 ? (hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP) : Action.MINE;
		}
		return Action.FORWARD;
	}

	/**
	 * Fighting: a monster within reach (it knows everything within 6 blocks); with PvP on, whoever hurt it or its owner
	 * in the last ten seconds (never its owner or a teammate); with team PvP, Xens of other teams it sees; in the arena,
	 * its duel partner. How it fights is up to its fight genes ({@link Fighter}).
	 */
	private Action fightBack() {
		if (!mod.config.pvp.equals("off") && armedStranger()) hands.ready();   // someone armed comes close: sword out
		LivingEntity foe = foe();
		fighting = foe != null;
		hands.watching = null;
		if (foe == null) {
			hands.lowerShield();
			player.setSprinting(false);
			fighter.reset();
			return fighter.stayUp();                                     // (up on its pillar, healing: it stays up a while)
		}
		if (!hurtNow && !reflexes.ready(foe.getUUID(), !WorldSenses.sees(player, hands.yaw, hands.pitch, foe))) {   // Xen 2.0: it notices a reaction time later
			fighting = false;
			return fighter.stayUp();                                     // (up on its pillar meanwhile: it stays up)
		}
		lastFoe = foe;
		lastFoeAt = player.level().getGameTime();
		fightingWhat = foe.getName().getString();
		if (!(foe instanceof ServerPlayer)) fightingWhat = fightingWhat.toLowerCase(java.util.Locale.ROOT);
		if (!(foe instanceof ServerPlayer sp && skills.sparringWith(sp.getUUID()))) diplomacy.during(foe);   // it may talk (truce, give up); not in a spar
		return fighter.next(foe, hurtNow);
	}

	private String fightingWhat = "";
	/** Who it's fighting, and when it last was: a fight goes on for a moment after they step out of reach or sight. */
	private LivingEntity lastFoe;
	private long lastFoeAt;

	final Fighter fighter = new Fighter(this);
	/** Survival tips it weighs (and learns how far to trust). */
	final Tips tips = new Tips(this);
	/** Know-how and its plans (what it wants, and how to get it). */
	final Plans plans = new Plans(this);
	/** The biomes it has seen, and what they tend to have. */
	final Biomes biomes = new Biomes(this);
	/** Tidying up after itself: the pillars it climbed come down again. */
	final Tidy tidy = new Tidy(this);
	/** What it's after: this moment, the next minutes, and its dream. */
	final Goals goals = new Goals(this);
	/** Trading with villagers and players. */
	final Trader trader = new Trader(this);
	/** Making its tools. */
	final Crafter crafter = new Crafter(this);
	/** Building houses and bases. */
	final Builder builder = new Builder(this);
	/** What it's good at (and training). */
	final Skills skills = new Skills(this);
	/** What it has heard about people (who's strong, whom it killed, plots), and its shocks. */
	final Rumors rumors = new Rumors(this);
	/** Potions, cobwebs, pearls, golden apples: the fighting gear, when it's good enough to use it. */
	final PvpKit kit = new PvpKit(this);
	/** The caves it found (the quickest way to ore), and the places it recognizes (villages, mineshafts, temples...). */
	final Caves caves = new Caves(this);
	final Structures structures = new Structures(this);
	/** Villages and ruined portals it found: in, and what they're good for (loot, a bed, hay, the golem's iron, a villager). */
	final Visits visits = new Visits(this);
	/** What happened the times it chose something (Xen 2.0's outcome memory: it decides with it). */
	final Experience experience = new Experience(this);
	/** G1X: the shape of what it has seen (depths, known open and solid space): gaps, ledges, ways, what it hasn't seen. */
	final G1X g1x = new G1X(this, Eyes.COLS, Eyes.ROWS);
	/** What it thinks you two should do, from what it knows (a place it found, a way it hasn't seen, a lesson). */
	final Ideas ideas = new Ideas(this);
	/** Fishing, with a rod, like a player. */
	final Fisher fisher = new Fisher(this);
	/** Xen Ex1: what it made of the last moment (keys, turn, look, danger), the moments before (for learning), and what it perceived last. */
	xen.mod.core.Ex1.Out ex1Out;

	/** How dangerous right now feels: Ex1's sense of it (its senses), and what its eyes make of the picture (Xen Glance) on top. */
	float danger() {
		float ex = ex1Out == null ? 0f : ex1Out.danger;
		return 1 - (1 - ex) * (1 - 0.5f * vision.threat);
	}
	private final java.util.ArrayDeque<xen.mod.core.Ex1.Out> ex1Recent = new java.util.ArrayDeque<>();
	private int ex1Ticks;
	private long ex1HurtAt = -1000;
	float[] perceived;
	/** Xen 2.0: a do, don't or later for every tool, block and animal (its three brains), its reaction time, its confusion, its gut. */
	final Choices choices = new Choices(this);
	final Reflexes reflexes = new Reflexes(this);
	final Confusion confusion = new Confusion(this);
	final Gut gut = new Gut(this);
	/** How far it sees (its render distance, in blocks): the sight setting, as far as the server has the world loaded. */
	int sight() {
		return Math.max(32, Math.min(mod.config.sight, server.getPlayerList().getViewDistance() * 16));
	}

	/** Its eye on the goal: a goal cut short is gone back to, if it's worth it (see {@link Focus}). */
	final Focus focus = new Focus(this);

	/** Its anger and bloodlust (hidden; see {@link Rage}). */
	final Rage rage = new Rage(this);
	final Hunch hunch = new Hunch(this);
	/** Its guide: a soul beside it that sees through the rock, and offers a way to the ore (it follows or not). */
	final Guide guide = new Guide(this);
	final Planner planner = new Planner(this);
	final Director director = new Director(this);
	/** What hurt it, remembered (once burnt, twice shy). */
	final Aversions aversions = new Aversions(this);
	/** Teaching (Xens and players), and the talk of a fight (a crouch after a hit: a sorry it may take or not). */
	final Teaching teaching = new Teaching(this);
	/** Everyday conversation, in character. */
	final SmallTalk smallTalk = new SmallTalk(this);
	/** The little human things: gestures, forgiveness, gifts, a dog, a hobby, milestones. */
	final Life life = new Life(this);
	/** Where it was told to go ("go to 120 64 -40", /xen goto), and since when. */
	BlockPos commandedTo;
	private long commandedAt;
	private static final java.util.regex.Pattern COORDS = java.util.regex.Pattern.compile("(-?\\d+)[\\s,]+(-?\\d+)(?:[\\s,]+(-?\\d+))?");
	private static final java.util.regex.Pattern MAKE_TEAM = java.util.regex.Pattern.compile(
			"\\b(make|start|create|found)\\s+(a|your own|our own|a new|your)?\\s*team(?:\\s+(?:called|named)\\s+([a-z0-9 ]{2,24}?))?(\\s+with me)?\\s*$");
	private static final java.util.regex.Pattern FISH = java.util.regex.Pattern.compile("\\b(go|let'?s|can you|time to|want to|wanna) (go )?fish(ing)?\\b|^fish\\b");
	private static final java.util.regex.Pattern GO_TO = java.util.regex.Pattern.compile("\\b(?:go|walk|move|head|come|run)\\s+(?:to|over to)\\s+(?:x\\s*)?-?\\d+");

	/**
	 * Go to a block: "x y z" (or "x z": the ground there). It walks to that very block and stays there. Its answer.
	 */
	private static final java.util.regex.Pattern GO_PLACE = java.util.regex.Pattern.compile("\\b(?:go|walk|move|head|run|take me|lead me|bring me|show me the way)\\s+"
			+ "(?:back )?(?:to|over to|into)\\s+(?:the |a |that |this |your |our |its )?(cave|village|house|home|base|mine|nether portal|portal|temple|desert temple|"
			+ "jungle temple|monument|ocean monument|stronghold|shipwreck|ruins|ruined portal|outpost|pillager outpost|mansion|trial chamber|farm)\\b");

	/**
	 * Told where to go the way players say it: "go over there", "walk that way", "go about 50 blocks that way", "head
	 * north", "go 30 blocks east". "There" is where the one asking is looking (the spot they point at, as far as it can
	 * see); "that way", the way they face; a number is about that far (about: never to the block). No coordinates.
	 */
	private static final java.util.regex.Pattern GO_VAGUE = java.util.regex.Pattern.compile("\\b(?:go|walk|move|head|run|sprint|wander)\\s+"
			+ "(?:(?:about|around|like|roughly|maybe|some|~)\\s*)?(?:(\\d{1,4})\\s*(?:blocks?|meters?|m)\\b\\s*)?(?:to(?:ward)?s?\\s+the\\s+)?"
			+ "(over there|there|that way|this way|that direction|yonder|north|south|east|west|forward|ahead|straight)?\\b");
	/** Where it was told to go was vague ("over there"): about there is there. */
	private boolean commandedVague;
	/** "Go to the hills", "head for the river", "let's go to the beach": the nearest one of those it can see. */
	private static final java.util.regex.Pattern GO_FEATURE = java.util.regex.Pattern.compile("\\b(?:go|walk|move|head|run|sprint|let'?s go)\\s+(?:to|toward|towards|for|into|up|down to)?\\s*"
			+ "(?:the |a |some |that |those |nearest |closest )*(hills?|mountains?|river|lake|water|beach|shore|forest|woods|trees|desert|plains|fields?|ocean|sea|snow|swamp|jungle)\\b");

	/** "Go to the hills": the nearest of that kind it can see (high ground, water's edge, trees, a biome), and off it goes. Null: not that kind of ask. */
	String goFeature(String words) {
		var m = GO_FEATURE.matcher(words);
		if (!m.find() || player == null) return null;
		String kind = m.group(1);
		ServerLevel level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		BlockPos found = null;
		int far = Math.min(sight(), 160);
		outer:
		for (int r = 12; r <= far; r += 8) {
			int n = Math.max(16, (int) (2 * Math.PI * r / 5));                     // (a look every 5 blocks or so round each ring)
			boolean byBiome = !kind.startsWith("hill") && !kind.startsWith("mountain") && !kind.equals("water") && !kind.equals("lake") && !kind.equals("forest")
					&& !kind.equals("woods") && !kind.equals("trees");
			for (int i = 0; i < n; i++) {
				double a = 2 * Math.PI * i / n;
				int x = feet.getX() + (int) Math.round(Math.cos(a) * r), z = feet.getZ() + (int) Math.round(Math.sin(a) * r);
				if (!level.hasChunkAt(new BlockPos(x, feet.getY(), z))) continue;
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), leafTop = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
				BlockPos ground = new BlockPos(x, top, z);
				boolean wet = !level.getFluidState(ground.below()).isEmpty();
				String biome = byBiome || (i & 3) == 0 ? places.biomeAt(ground) : "";
				boolean match = switch (kind) {
					case "hill", "hills" -> !wet && top >= feet.getY() + 10;
					case "mountain", "mountains" -> !wet && top >= feet.getY() + 25;
					case "river", "lake", "water", "ocean", "sea" -> wet && (!kind.equals("river") || biome.contains("river")) && (!kind.equals("ocean") && !kind.equals("sea") || biome.contains("ocean"));
					case "beach", "shore" -> !wet && (biome.contains("beach") || level.getBlockState(ground.below()).is(net.minecraft.world.level.block.Blocks.SAND) && nearWater(level, ground));
					case "forest", "woods", "trees" -> !wet && leafTop > top + 2;
					case "field", "fields" -> !wet && biome.contains("plains");
					case "snow" -> !wet && biome.contains("snow");
					default -> !wet && biome.contains(kind);
				};
				if (!match) continue;
				if (wet) {                                                        // (water: the dry ground at its edge, not in it)
					for (double d = r; d > 4; d -= 2) {
						int sx = feet.getX() + (int) Math.round(Math.cos(a) * d), sz = feet.getZ() + (int) Math.round(Math.sin(a) * d);
						Vec3 dry = XenMod.surface(level, sx, sz);
						if (dry != null) {
							found = BlockPos.containing(dry);
							break outer;
						}
					}
					continue;
				}
				found = new BlockPos(x, top, z);
				break outer;
			}
		}
		if (found == null) return pick3("I don't see any " + kind + " around here.", "No " + kind + " that I can see.", "Where? I can't see any " + kind + ".");
		commandedTo = found;
		commandedVague = true;
		commandedAt = player.level().getGameTime();
		chores.cancel();
		goals.drop();
		journal("does", "heads for the " + kind + " it can see, about " + Math.round(Math.sqrt(found.distSqr(feet))) + " blocks off, as it was told");
		return pick3("Okay, to the " + kind + ".", "Sure, heading for the " + kind + ".", "On my way to the " + kind + ".");
	}

	private static boolean nearWater(ServerLevel level, BlockPos p) {
		for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) for (int k = 1; k <= 4; k++) if (!level.getFluidState(p.below().relative(d, k)).isEmpty()) return true;
		return false;
	}

	/** "Go over there", "50 blocks that way": where that is (the one asking points), and off it goes. Null: not that kind of ask. */
	String goVague(ServerPlayer from, String words) {
		var m = GO_VAGUE.matcher(words);
		if (!m.find() || player == null || m.group(1) == null && m.group(2) == null) return null;
		String way = m.group(2) == null ? "that way" : m.group(2);
		if (way.equals("this way")) return null;                                 // ("come this way": follow)
		int far = m.group(1) == null ? -1 : Math.min(400, Integer.parseInt(m.group(1)));
		ServerLevel level = (ServerLevel) player.level();
		boolean near = from != null && from.level() == player.level() && from.distanceTo(player) < 48;
		double yaw = switch (way) {                                              // (Minecraft: south is +z, yaw 0)
			case "north" -> 180;
			case "south" -> 0;
			case "east" -> -90;
			case "west" -> 90;
			default -> near ? from.getYRot() : player.getYRot();
		};
		BlockPos target = null;
		if (far < 0 && near && (way.contains("there") || way.equals("yonder"))) {   // "over there": the spot they're looking at
			Vec3 eye = from.getEyePosition(), to = eye.add(from.getLookAngle().scale(160));
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.ANY, from));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) target = hit.getBlockPos().above();
		}
		if (target == null) {
			double d = far > 0 ? far : 30;
			d *= 0.92 + 0.16 * random().nextDouble();                           // (about that far)
			double a = Math.toRadians(yaw) + (random().nextDouble() - 0.5) * 0.12;
			int x = (int) Math.round(player.getX() - Math.sin(a) * d), z = (int) Math.round(player.getZ() + Math.cos(a) * d);
			int y = level.isLoaded(new BlockPos(x, 0, z)) ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : player.getBlockY();
			target = new BlockPos(x, y, z);
		}
		commandedTo = target;
		commandedVague = true;
		commandedAt = player.level().getGameTime();
		chores.cancel();
		goals.drop();
		journal("does", "goes " + (far > 0 ? "about " + far + " blocks " : "") + way + ", as it was told");
		return far > 0 ? pick3("Okay, about " + far + " that way.", "On it, " + far + " blocks or so.", "Sure, heading " + (way.contains("th") && !way.contains("there") ? way : "that way") + ".")
				: way.contains("there") || way.equals("yonder") ? pick3("Over there? On my way.", "Okay, going there.", "Sure, over there.") : pick3("Okay, that way.", "Heading " + way + ".", "Sure.");
	}

	/** Off to a place it knows ("the cave", "home"): where, how far; or that it doesn't know one. */
	String goToPlace(String kind) {
		BlockPos at = switch (kind) {
			case "house", "home", "base" -> goals.home;
			case "mine" -> places.get("mine");
			case "cave" -> caves.nearest(400) != null ? caves.nearest(400) : places.find("cave") != null ? places.find("cave").pos() : null;
			case "portal" -> places.get("portal") != null ? places.get("portal") : places.find("ruined portal") != null ? places.find("ruined portal").pos() : null;
			case "temple" -> places.find("desert temple") != null ? places.find("desert temple").pos() : places.find("jungle temple") != null ? places.find("jungle temple").pos() : null;
			case "monument" -> places.find("ocean monument") != null ? places.find("ocean monument").pos() : null;
			case "outpost" -> places.find("pillager outpost") != null ? places.find("pillager outpost").pos() : null;
			case "mansion" -> places.find("woodland mansion") != null ? places.find("woodland mansion").pos() : null;
			default -> places.find(kind) != null ? places.find(kind).pos() : null;
		};
		String a = kind.equals("home") ? "" : "the ";
		if (at == null) return pick3("I don't know where " + (a.isEmpty() ? "home is" : "a " + kind + " is") + " yet.", "I haven't found " + ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind + " yet.",
				"No idea where " + (a.isEmpty() ? "home is" : "one is") + ", sorry.");
		commandedTo = at;
		commandedAt = player.level().getGameTime();
		commandedVague = false;
		chores.cancel();
		goals.drop();
		return String.format(java.util.Locale.ROOT, "Okay, to %s%s at %d %d %d (%.0f blocks from here). Follow me!", a, kind, at.getX(), at.getY(), at.getZ(),
				Math.sqrt(player.blockPosition().distSqr(at)));
	}

	String goTo(String where) {
		var m = COORDS.matcher(where);
		if (!m.find() || player == null) return "Where? Tell me the spot: x y z (or x z).";
		int x = Integer.parseInt(m.group(1)), y, z;
		if (m.group(3) != null) {
			y = Integer.parseInt(m.group(2));
			z = Integer.parseInt(m.group(3));
		} else {
			z = Integer.parseInt(m.group(2));
			ServerLevel level = (ServerLevel) player.level();
			y = level.isLoaded(new BlockPos(x, 0, z)) ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
					: player.getBlockY();
		}
		commandedTo = new BlockPos(x, y, z);
		commandedAt = player.level().getGameTime();
		commandedVague = false;
		chores.cancel();
		goals.drop();
		return String.format(java.util.Locale.ROOT, "Okay, going to %d %d %d (%.0f blocks from here).", x, y, z,
				Math.sqrt(player.blockPosition().distSqr(commandedTo)));
	}

	/** Visiting a friend's base (it goes back to its own life after). */
	private Companion visiting;

	/** Off to visit a friend's base, like players on an SMP do. */
	void visit(Companion friend) {
		visiting = friend;
		commandedTo = friend.goals.home;
		commandedAt = player.level().getGameTime();
		commandedVague = false;
		chatter(pick3("I'm going to visit " + friend.name + ".", "Let's see what " + friend.name + " has built.", "Time to drop by " + friend.name + "'s place."), true);
		journal("does", "goes to visit " + friend.name);
	}

	/** On its way to where it was told: the next step, or null when it's there (it stays) or gave up. */
	private Action goToStep() {
		if (commandedTo == null) return null;
		BlockPos at = player.blockPosition();
		if (visiting != null && at.closerThan(commandedTo, 6)) {               // there: hello, then back to its own life
			Companion f = visiting;
			visiting = null;
			commandedTo = null;
			walker.stop();
			say(pick3("Hey " + f.name + "! Nice place.", "Knock knock, " + f.name + "!", "Just passing by, " + f.name + ". Love the base."));
			trust(f.player() != null ? f.player().getUUID() : null, 0.05f);
			return Action.IDLE;
		}
		if (commandedVague && Math.hypot(at.getX() - commandedTo.getX(), at.getZ() - commandedTo.getZ()) <= 3 && Math.abs(at.getY() - commandedTo.getY()) <= 4) {
			walker.stop();                                                      // (about there: there)
			mode = Mode.STAY;
			anchor = at;
			say(pick3("Here?", "Okay, here.", "Made it."));
			commandedTo = null;
			commandedVague = false;
			return Action.IDLE;
		}
		if (commandedVague && player.level().getGameTime() % 40 == 0) {          // (the ground there, now it can see it)
			ServerLevel level = (ServerLevel) player.level();
			if (level.isLoaded(commandedTo)) {
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, commandedTo.getX(), commandedTo.getZ());
				if (Math.abs(y - commandedTo.getY()) > 2 && level.getBlockState(commandedTo.below()).isAir()) commandedTo = new BlockPos(commandedTo.getX(), y, commandedTo.getZ());
			}
		}
		if (at.getX() == commandedTo.getX() && at.getZ() == commandedTo.getZ() && Math.abs(at.getY() - commandedTo.getY()) <= 1) {
			walker.stop();
			mode = Mode.STAY;
			anchor = at;
			say(String.format(java.util.Locale.ROOT, "I'm here: %d %d %d.", at.getX(), at.getY(), at.getZ()));
			commandedTo = null;
			return Action.IDLE;
		}
		if (player.level().getGameTime() - commandedAt > 20 * 60 * 4 && visiting != null) {   // (a visit that didn't work out: never mind)
			visiting = null;
			commandedTo = null;
			return null;
		}
		if (player.level().getGameTime() - commandedAt > 20 * 60 * 4) {
			say("I can't find a way there. I'll stay here.");
			mode = Mode.STAY;
			anchor = at;
			commandedTo = null;
			return null;
		}
		goals.instant = commandedVague ? "going where it was told" : "going to " + commandedTo.toShortString();
		if (commandedVague) run(player.blockPosition().distSqr(commandedTo) > 8 * 8);   // (a way off: it sprints, like a player)
		Action a = walkTo(Vec3.atBottomCenterOf(commandedTo));
		return a != null ? a : Action.IDLE;
	}

	/** What it was last asked to gather (and by whom), and whether it should get back to it after an interruption. */
	private xen.mod.talk.Chat.Request asked;
	private ServerPlayer askedBy;
	private String askedWords;
	private boolean resumeAsked;

	/** It lost a fight lately (it may want to train). */
	boolean lostFight;
	/** How much it trusts each player (and Xen) it has met, -1 to 1: kind words and fair deals up, hits and cheating down. */
	final Map<UUID, Float> trust = new HashMap<>();
	/** Where its time goes (for /xen time): minutes on each kind of thing, and how often it came to something. */
	final Diary diary = new Diary();

	float trust(UUID who) {
		if (who == null) return 0;
		if (who.equals(owner)) return 1f;
		float stranger = personality.strangerTrust();                  // (its beliefs and sins: "trust no one", "everyone's a friend")
		return trust.getOrDefault(who, known.contains(who) ? stranger + 0.1f : stranger);
	}

	void trust(UUID who, float change) {
		if (who == null || who.equals(owner)) return;
		trust.put(who, Math.max(-1f, Math.min(1f, trust(who) + change)));
	}

	/** When each player last hit it with a weapon (then it's a real attack). */
	private final Map<UUID, Long> armedHitAt = new HashMap<>();
	/** Hits with an empty hand (or a flower, a block...) lately, per player: someone wanting its attention. */
	private final Map<UUID, java.util.ArrayDeque<Long>> pokes = new HashMap<>();
	private long answeredPokeAt = -10_000;

	static boolean isWeapon(ItemStack s) {
		if (s.isEmpty()) return false;
		String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return n.endsWith("_sword") || n.endsWith("_axe") || n.endsWith("_spear") || n.equals("trident") || n.equals("mace")
				|| n.equals("bow") || n.equals("crossbow");
	}

	/**
	 * A player hit it. A poke (the way players get someone's attention: one light tap with an empty hand) only counts
	 * while they're talking with it; then it turns to them and asks what's up. Anything else is an attack: a weapon, a
	 * critical hit (hitting while falling), a hard hit, hitting while crouching, a second hit within three seconds, or a
	 * hit out of nowhere (not talking to it). Its owner gets told off first ("Ow! What was that for?"); keep it up and a
	 * wrathful or aggressive Xen hits back for a bit, a gentler one backs off. Anyone else: it trusts them a lot less and
	 * it may fight back (its own call).
	 */
	private void hitBy(ServerPlayer by) {
		long now = player.level().getGameTime();
		UUID u = by.getUUID();
		if (skills.sparringWith(u)) return;                            // a sparring partner: that's the game
		Tribe home = tribe();
		if (home != null && by instanceof XenPlayer xp && xp.companion != null && isWeapon(by.getMainHandItem())) home.laws.hit(xp.companion, this);
		var q = pokes.computeIfAbsent(u, k -> new java.util.ArrayDeque<>());
		q.addLast(now);
		while (!q.isEmpty() && now - q.peekFirst() > 200) q.removeFirst();
		int recent = 0;
		for (long t : q) if (now - t <= 60) recent++;
		boolean armed = isWeapon(by.getMainHandItem());
		boolean fresh = u.equals(struckBy) && player.tickCount - struckAt < 10;   // (how they hit it, seen at that very moment)
		boolean crit = fresh ? struckCrit : !by.onGround() && !by.isInWater() && !by.onClimbable() && by.fallDistance > 0;
		boolean sneaking = fresh ? struckSneaking : by.isShiftKeyDown();
		boolean hard = player.getHealth() < tickHealthBefore - 3;
		boolean talking = u.equals(talkingWith) && now < talkingUntil;
		boolean attack = armed || crit || hard || sneaking || recent >= 2 || !talking;
		hitsTaken.merge(u, 1, Integer::sum);
		hands.watching = by;
		watchFor = u;
		watchUntil = player.tickCount + 100;                           // it looks at them
		if (attack) {
			armedHitAt.put(u, now);
			rage.provoke(armed || crit || hard ? 2 : 1, "hit by " + by.getName().getString());   // (a hit gets to it: more for a hard one)
			String how = crit ? "a critical hit" : armed ? "a weapon" : recent >= 2 ? "hit after hit" : sneaking ? "a sneaky hit" : "a hit out of nowhere";
			if (u.equals(owner)) {                                      // its owner: told off first, then (its nature) it hits back or backs off
				int n = 0;
				for (long t : q) if (now - t <= 200) n++;
				journal("does", "hit by its owner (" + how + ", " + n + " in 10 s)");
				boolean hitsBack = personality.aggressive() || personality.sin(xen.mod.core.Sins.WRATH) >= 0.5f || personality.sin(xen.mod.core.Sins.PRIDE) >= 0.7f
						|| rage.anger >= 60;                                     // (angry enough, anyone hits back)
				int level = Math.min(n, 3);                                 // how bad it's getting: it says so when it gets worse (even right after a "what's up?")
				boolean speak = now - scoldedAt > 60 || level > scoldLevel && now - scoldedAt > 8;
				if (n >= 3 && hitsBack && !mod.config.pvp.equals("off")) {
					ownerFoeUntil = now + 120;                              // six seconds of hitting back
					if (speak) say(pick3("That's it!", "You asked for it!", "Okay, now I'm mad."));
				} else {
					if (speak) say(n <= 1 ? (now - answeredPokeAt < 80 ? pick3("Ow! Okay, I'm listening!", "Hey, no need to hit!", "Ow, I heard you!")
									: pick3("Ow! What was that for?", "Hey! Why'd you hit me?", "Ouch! What did I do?"))
							: n == 2 ? pick3("Hey, stop hitting me!", "Stop it!", "Quit it, that hurts!")
							: pick3("I'm not fighting you. Stop!", "Seriously, stop!", "Fine, I'm leaving."));
					if (n >= 3) fleeFromOwnerUntil = now + 100;                // (a gentle one backs off)
				}
				if (speak) {
					scoldedAt = now;
					scoldLevel = level;
				}
				answeredPokeAt = now;
				return;
			}
			trust(u, armed || crit || hard ? -0.3f : -0.15f);            // it remembers who hit it
			Tribe t = tribe();
			if (t != null) t.alarm(this, by);                          // and its tribe does too
			journal("does", "attacked by " + by.getName().getString() + " (" + how + ")");
			if (now - answeredPokeAt > 100 && random().nextFloat() < 0.5f) {
				answeredPokeAt = now;
				say(personality.say("ouch"));
			}
			return;
		}
		trust(u, -0.01f);                                               // a poke while they talk: it wants its attention
		known.add(u);
		if (now - answeredPokeAt > 200) {
			answeredPokeAt = now;
			talkingWith = u;
			talkingUntil = now + 600;
			String n = by.getName().getString();
			say(switch (personality.tone) {
				case "grumpy" -> "What? What do you want, " + n + "?";
				case "shy" -> "O-oh! Um, yes, " + n + "?";
				case "silly" -> "Boop! Hi " + n + ", what's up?";
				case "bold" -> "Yeah? What is it, " + n + "?";
				case "calm" -> "Yes, " + n + "? I'm listening.";
				default -> "Hey " + n + "! What's up?";
			});
		}
	}

	private Action backOffFromOwner() {
		if (owner == null || player.level().getGameTime() >= fleeFromOwnerUntil) return null;
		ServerPlayer o = server.getPlayerList().getPlayer(owner);
		if (o == null || o.level() != player.level() || o.distanceTo(player) > 8) return null;
		Vec3 away = player.position().subtract(o.position()).multiply(1, 0, 1);
		if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
		goals.instant = "keeping away from " + o.getName().getString();
		return walkTo(player.position().add(away.normalize().scale(6)));
	}

	/** The last hit by a player, as it happened: who, when, a crit (falling), crouching, how hard. */
	UUID struckBy;
	int struckAt = -100;
	private boolean struckCrit, struckSneaking;

	void struckBy(ServerPlayer by, float amount) {
		struckBy = by.getUUID();
		struckAt = player.tickCount;
		struckCrit = by.fallDistance > 0 && !by.onGround() && !by.onClimbable() && !by.isInWater() && !by.isPassenger();
		struckSneaking = by.isShiftKeyDown();
	}

	/** When it last told its owner off for hitting it, and how bad it was then (1 to 3). */
	private long scoldedAt = -1_000;
	private int scoldLevel;

	/** Hits taken from each player, all told (for its memory of them). */
	final Map<UUID, Integer> hitsTaken = new HashMap<>();
	/** Its owner hit it again and again: until when it hits back (a wrathful one), or keeps away (a gentle one). */
	long ownerFoeUntil, fleeFromOwnerUntil;

	/** Is this player really attacking it (a weapon, or poking on and on) lately? */
	private boolean attackedBy(LivingEntity a, long now) {
		if (!(a instanceof ServerPlayer sp)) return true;               // mobs: yes
		return now - armedHitAt.getOrDefault(sp.getUUID(), -1_000_000L) < 200;
	}

	/** Friends: players and Xens it trusts (its owner counts). */
	int friends() {
		int n = owner != null ? 1 : 0;
		for (var e : trust.entrySet()) if (e.getValue() >= 0.5f && !e.getKey().equals(owner)) n++;
		return n;
	}
	/** In the PvP arena: its duel partner (the only one it fights), and no learning into the shared brain. */
	boolean inArena;
	LivingEntity duelFoe;
	/** Its arena team ("red" or "blue"). */
	String arenaTeam;

	/** Moved by the arena to its lane. */
	void teleport(ServerLevel level, Vec3 at, float yaw) {
		hands.stop();
		player.teleportTo(level, at.x, at.y, at.z, Set.<Relative>of(), yaw, 0, true);
		hands = new Hands(player);
		walkedFrom = null;
		stuck = 0;
	}

	/** It knows everything within 6 blocks (like a player who hears and feels what's close), seen or not. */
	private static final double NEAR = 6;

	private float tickHealth, tickHealthBefore;
	private boolean fighting, hurtNow;

	boolean fightingNow() {
		return fighting;
	}

	/** A player (not its owner, not a teammate) close by with a sword or axe in hand: it can see that. */
	private boolean armedStranger() {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == player || p.level() != player.level() || p.getUUID().equals(owner) || player.isAlliedTo(p)) continue;
			if (p.distanceTo(player) > 5) continue;
			String held = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
			if (held.endsWith("_sword") || held.endsWith("_axe")) return true;
		}
		return false;
	}

	private static final java.util.regex.Pattern SLEEP = java.util.regex.Pattern.compile("\\b(go(es)? to (bed|sleep)|go sleep|bed ?time|sleep now|time to sleep|get some sleep)\\b|^\\s*sleep\\s*[!.]*$");
	private static final java.util.regex.Pattern TEAM_JOIN = java.util.regex.Pattern.compile(
			"\\b(join|be on|come to|switch to|be in)\\s+(my|our|the)?\\s*(team|side)\\b|\\bjoin\\s+(the\\s+)?(red|blue|green|yellow|purple|aqua)(\\s+team)?\\b|\\bjoin us\\b");

	private static final java.util.regex.Pattern TEAM_MAKE = java.util.regex.Pattern.compile(
			"\\b(make|create|start|form|found)\\s+(a|our|my|an)?\\s*(new\\s+)?team\\b(?:\\s+(?:called|named))?\\s*([a-z0-9 ]{0,24})");

	/**
	 * A player starts a team in chat ("let's make a team called Night Owls", "make a team with me"): it's made for them,
	 * and this Xen decides whether it joins (trust, loyalty to the team it has; a loner never). Null if not asked.
	 */
	private String teamMade(ServerPlayer from, String words) {
		var m = TEAM_MAKE.matcher(words.toLowerCase(java.util.Locale.ROOT));
		if (!m.find()) return null;
		String wanted = m.group(4) == null ? "" : m.group(4).replaceAll("\\b(with|me|us|and|you|please|now|together)\\b", " ").trim();
		var team = mod.playerTeam(from, wanted);
		if (team == null) team = server.getScoreboard().getPlayersTeam(from.getScoreboardName());   // (they have one: that one)
		if (team == null) return "Couldn't make a team, sorry.";
		String name = team.getDisplayName().getString();
		journal("thinks", from.getName().getString() + " made the team " + name);
		String join = joinOrNot(from, team);
		return pick3("The " + name + "? ", "A team, the " + name + ". ", "Team " + name + ", huh. ") + join;
	}

	/** Join this player's team? Its call (trust in them, loyalty to its own team; a loner never): what it says. */
	private String joinOrNot(ServerPlayer from, net.minecraft.world.scores.PlayerTeam team) {
		if (personality.loner) return pick3("I don't do teams. I work alone.", "Thanks, but I'm better on my own.", "No teams for me. Nothing personal.");
		var mine = server.getScoreboard().getPlayersTeam(name);
		if (mine == team) return "I'm already on it!";
		float trust = trust(from.getUUID());
		boolean onOne = mine != null && (mod.ownTeam(mine.getName()) || mod.onPlayersTeam(this));
		boolean agree = trust >= 0.45f && (!onOne || personality.loyalty < 0.7f || trust >= 0.8f);
		if (!agree) return trust < 0.2f ? "Why would I join you? I don't even know you." : "No thanks, I'm staying with my team.";
		mod.joinPlayersTeam(this, team);
		return pick3("I'm in!", "Count me in, " + from.getName().getString() + ".", "Sure, I'll join.");
	}

	/** Asked to join a team: it decides, by how much it trusts whoever asks and how loyal it is to its own. Null if not asked. */
	private String teamAsked(ServerPlayer from, String words) {
		var m = TEAM_JOIN.matcher(words.toLowerCase(java.util.Locale.ROOT));
		if (!m.find()) return null;
		String color = m.group(5);
		if (color == null) {
			var t = server.getScoreboard().getPlayersTeam(from.getScoreboardName());
			if (t == null) return "You don't have a team for me to join. Make one first (say \"let's make a team\"), or tell me a color.";
			if (!t.getName().startsWith("xen_") || mod.ownTeam(t.getName())) return joinOrNot(from, t);   // (a team they made, in chat or with /team)
			color = t.getName().substring(4);
		}
		if (personality.loner) return pick3("I don't do teams. I work alone.", "Thanks, but I'm better on my own.", "No teams for me. Nothing personal.");
		String now = mod.teamOf(this);
		if (now != null && now.equals("xen_" + color)) return "I'm already on the " + color + " team!";
		float trust = trust(from.getUUID());
		boolean agree = trust >= 0.45f && (now == null || personality.loyalty < 0.7f || trust >= 0.8f);
		if (!agree) {
			return trust < 0.2f ? "Why would I join you? I don't even know you." : "No thanks, I'm staying with my team.";
		}
		if (!mod.moveToTeam(this, color)) return "There's no " + color + " team here (the world has " + mod.config.teams + (mod.config.teams == 1 ? " team)." : " teams).");
		return pick3("Okay, I'm on the " + color + " team now!", "Sure. Team " + color + "!", "Alright, " + from.getName().getString() + ", count me in.");
	}

	/** A friend in trouble called to it for help: what's at them, till when, and whether it goes (its own call). */
	private LivingEntity helpAgainst;
	private long helpUntil;
	private boolean helpGoing;

	/**
	 * A friend, about to die and with nothing to fight with, called for help: does it go? Its kindness, loyalty and nerve,
	 * how much it trusts them, whether they're its own kind or team, and whether it's in a state to help, decide.
	 */
	void heardCall(Companion from, LivingEntity against) {
		if (player == null || from == this || from.player == null || inArena || against == null || against == player || helpGoing) return;
		if (against instanceof ServerPlayer sp && (sp.getUUID().equals(owner) || trust(sp.getUUID()) >= 0.6f)) return;   // (not against its own friend)
		var p = personality;
		float t = trust(from.player.getUUID());
		float chance = 0.1f + 0.3f * p.kindness + 0.25f * p.loyalty + 0.2f * p.bravery + 0.4f * Math.max(0f, t)
				+ (Personality.kindred(p, from.personality) ? 0.25f : 0f) + (player.isAlliedTo(from.player) ? 0.2f : 0f)
				- (hands.hasWeapon() ? 0f : 0.35f) - (player.getHealth() < 10 ? 0.3f : 0f) - (t < 0 ? 0.5f : 0f);
		helpGoing = random.nextFloat() < chance;
		helpAgainst = against;
		helpUntil = player.level().getGameTime() + 20 * 25;
		String what = against instanceof ServerPlayer ? against.getName().getString() : BuiltInRegistries.ENTITY_TYPE.getKey(against.getType()).getPath().replace('_', ' ');
		journal("thinks", from.name + " called for help (" + what + " at them): " + (helpGoing ? "goes" : "doesn't go"));
		if (helpGoing) mod.later(10 + random.nextInt(20), () -> say(pick3("Coming!", "Hang on, " + from.name + ", I'm coming!", "On my way!")));
		else if (random.nextFloat() < 0.4f) mod.later(10 + random.nextInt(20), () -> say(pick3("Sorry, I can't!", "Run!", "I can't help, get out of there!")));
	}

	/** A team player, armed and fit: a teammate fighting a monster close by gets a hand without having to ask. */
	private void teamHelp() {
		if (inArena || minion || fighter.style() != Fighter.Style.TEAM || !hands.hasWeapon() || player.getHealth() < 12) return;
		for (Companion o : mod.companions) {
			if (o == this || o.player == null || o.player.level() != player.level() || o.player.distanceTo(player) > 16) continue;
			if (!player.isAlliedTo(o.player) && trust(o.player.getUUID()) < 0.5f) continue;
			LivingEntity f = o.lastFoe;
			if (f == null || !f.isAlive() || !(f instanceof net.minecraft.world.entity.monster.Enemy) || f.level() != player.level() || f.distanceTo(o.player) > 10
					|| player.level().getGameTime() - o.lastFoeAt > 60 || !player.hasLineOfSight(f)) continue;
			helpAgainst = f;
			helpGoing = true;
			helpUntil = player.level().getGameTime() + 20 * 20;
			journal("fight", "helps " + o.name + " with the " + BuiltInRegistries.ENTITY_TYPE.getKey(f.getType()).getPath().replace('_', ' ') + " (a team player: nobody had to ask)");
			return;
		}
	}

	/** Who Xen 2.0 chose to fight (its own call: a monster, or a player it's against, even one of its team). */
	LivingEntity chosenFoe;
	private int saidComingAt = -10000;
	/** When it last picked a fight with a player (an aggressive one doesn't do it every minute). */
	long lastPickedFight = -100000;

	private LivingEntity foe() {
		if (inArena) return duelFoe != null && duelFoe.isAlive() && duelFoe.level() == player.level() && player.distanceTo(duelFoe) < 32
				? duelFoe : null;
		if (chosenFoe != null && diplomacy.atPeace(chosenFoe)) chosenFoe = null;   // a truce is a truce: it keeps its word
		if (chosenFoe != null && chosenFoe.isAlive() && chosenFoe.level() == player.level() && player.distanceTo(chosenFoe) < 6
				&& player.hasLineOfSight(chosenFoe) && !(chosenFoe instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator()))) return chosenFoe;
		for (var creeper : player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Creeper.class,
				player.getBoundingBox().inflate(8), x -> x.isAlive() && x.getSwellDir() > 0)) {
			if (player.distanceTo(creeper) < 7.5) return creeper;         // hissing close by: it hears that, and runs till it stops (past 7 blocks the fuse goes out)
		}
		long tick = player.level().getGameTime();
		if (lastFoe != null && lastFoe.isAlive() && lastFoe.level() == player.level() && player.distanceTo(lastFoe) < 10 && tick - lastFoeAt < 60
				&& !diplomacy.atPeace(lastFoe) && !(lastFoe instanceof ServerPlayer sp0 && (sp0.isCreative() || sp0.isSpectator())))
			return lastFoe;                                                     // in a fight: it stays in it (a step back, a moment out of sight, isn't the end of it)
		LivingEntity coming = null;                                          // a monster coming for it, in sight: it deals with that before going on (a player turns round)
		double comingD = 10;
		for (net.minecraft.world.entity.Mob m : player.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(10),
				m -> m.isAlive() && m.getTarget() == player && hostile(m))) {
			double d = player.distanceTo(m);
			if (d < comingD && player.hasLineOfSight(m)) {
				comingD = d;
				coming = m;
			}
		}
		if (coming != null) return coming;
		double reach = player.entityInteractionRange() + 0.5;
		LivingEntity best = null;
		double bestD = reach;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(reach), this::hostile)) {
			double d = player.distanceTo(e);
			if (d <= bestD && player.hasLineOfSight(e)) {
				bestD = d;
				best = e;
			}
		}
		if (best != null) return best;
		if (!helpGoing && player.tickCount % 20 == 0) teamHelp();                    // a team player: a friend's fight close by is its fight too, called or not
		if (helpGoing && helpAgainst != null) {                                     // a friend called for help, and it chose to go
			LivingEntity a = helpAgainst;
			if (player.level().getGameTime() > helpUntil || !a.isAlive() || a.level() != player.level() || a == player || player.distanceTo(a) > 32
					|| diplomacy.atPeace(a) || a instanceof ServerPlayer && mod.config.pvp.equals("off")) helpGoing = false;
			else return a;
		}
		if (mod.config.pvp.equals("off")) return null;
		ServerPlayer o = owner == null ? null : server.getPlayerList().getPlayer(owner);
		long now = player.level().getGameTime();
		boolean own = mod.config.pvp.equals("own");
		for (LivingEntity victim : new LivingEntity[] {player, o}) {               // who hurt it, or its owner, just now
			if (victim == null || victim.level() != player.level()) continue;
			LivingEntity a = victim.getLastHurtByMob();
			if (a == null || !a.isAlive() || a == player || a == o && now >= ownerFoeUntil) continue;   // (its owner: only when it's had enough)
			if (diplomacy.atPeace(a)) continue;                                   // a truce (unless they broke it)
			if (a instanceof ServerPlayer sp0 && skills.sparringWith(sp0.getUUID())) continue;   // (sparring: that's the game)
			if (a instanceof AbstractVillager || a instanceof AbstractGolem || a instanceof TamableAnimal t && t.isTame()) continue;
			if (victim.tickCount - victim.getLastHurtByMobTimestamp() > 200 || player.distanceTo(a) > 16) continue;
			if (victim == player && !attackedBy(a, now)) continue;                // a poke to get its attention isn't a fight
			if (victim == o && a instanceof ServerPlayer sp && !isWeapon(sp.getMainHandItem())) continue;   // nor someone poking its owner
			if (own && a instanceof ServerPlayer sp) {                            // its own call: who, and whether it can win
				if (trust(sp.getUUID()) >= 0.6f && player.getHealth() > 10 && rage.anger < 70) continue;   // a friend's mistake: it lets it go (not when it's furious)
				if (player.getHealth() < 6 || personality.bravery < 0.3f && player.getHealth() < 12) continue;   // losing: it gets away instead
			}
			if (player.distanceTo(a) <= NEAR || WorldSenses.sees(player, hands.yaw, hands.pitch, a)) return a;   // near: it feels them
		}
		Tribe tribe = tribe();
		if (tribe != null && !tribe.enemies.isEmpty() && mod.config.tribes) {       // someone its tribe is fighting, close by
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p == player || p.level() != player.level() || !p.isAlive() || p.isCreative() || p.isSpectator() || !tribe.enemy(p, now)) continue;
				if (p.getUUID().equals(owner) || trust(p.getUUID()) >= 0.6f || diplomacy.atPeace(p)) continue;
				double d = player.distanceTo(p);
				if (d <= 16 && (d <= NEAR || WorldSenses.sees(player, hands.yaw, hands.pitch, p))) return p;
			}
		}
		if (!mod.config.pvp.equals("teams")) return null;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {           // team battles: Xens of other teams
			if (!(other instanceof XenPlayer) || other == player || !other.isAlive() || other.level() != player.level()) continue;
			if (player.isAlliedTo(other) || player.getTeam() == null || other.getTeam() == null || diplomacy.atPeace(other)) continue;
			double d = player.distanceTo(other);
			if (d < 24 && (best == null || d < player.distanceTo(best)) && (d <= NEAR || WorldSenses.sees(player, hands.yaw, hands.pitch, other))) {
				best = other;
			}
		}
		return best;
	}

	private static int cat(ServerLevel level, BlockPos pos) {
		return level.isLoaded(pos) ? WorldSenses.category(level, pos, level.getBlockState(pos)) : Blocks.STONE;
	}

	/** Is someone it knows close enough to hear it? */
	boolean someoneListening(double distance) {
		for (UUID u : known) {
			ServerPlayer p = server.getPlayerList().getPlayer(u);
			if (p != null && p.level() == player.level() && p.distanceTo(player) <= distance) return true;
		}
		return false;
	}

	/** When nobody it knows is around to hear, it leaves a note on a sign (one it carries), signed with its name. */
	void note(String text) {
		if (!mod.config.signs || player == null || inArena || someoneListening(32)) return;
		long now = player.level().getGameTime();
		if (now - lastNote < 3000) return;                            // at most one note every two and a half minutes
		if (hands.hotbar(s -> { String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); return n.endsWith("_sign") && !n.contains("hanging"); }) < 0) {
			makeSigns();                                              // Xen 2.0: no sign on it: it makes some (planks and a stick), for the next note
			return;
		}
		long day = now / 24000 + 1;
		if (hands.placeSign("Day " + day + ": " + text + " -" + name)) {
			lastNote = now;
			acted = true;
			XenMod.LOG.info("{} left a note on a sign at {}: {}", name, player.blockPosition().toShortString(), text);
		}
	}

	/** Short spoken reactions to what it feels (no language model needed). */
	private void react() {
		boolean night = player.level().isDarkOutside();
		if (night && !wasNight) chatter(personality.say("night"), false);
		if (!night && wasNight) note(switch (mode) {                  // a morning note for whoever comes by
			case FOLLOW -> "Looking for my friend.";
			case STAY -> "Waiting here.";
			case FREE -> "Off exploring.";
		});
		wasNight = night;
		if (emotions.pain > 0.25f) chatter(personality.say("ouch"), false);
		else if (emotions.fear > 0.65f) {
			boolean lava = senses.describe().contains("lava");
			chatter(personality.say(lava ? "lava" : "afraid"), false);
			if (lava) note("Careful, lava!");
		}
	}

	/** Signs to write notes on: three, from six planks and a stick, once in a while (Xen 2.0: more notes, less chat). */
	private void makeSigns() {
		long now = player.level().getGameTime();
		if (now < signsAt || crafter.hasOrder()) return;
		signsAt = now + 20 * 60 * 10;
		String planks = null;
		for (var e : items().entrySet()) if (e.getKey().endsWith("_planks") && e.getValue() >= 6 && !e.getKey().contains("bamboo")) planks = e.getKey();
		if (planks == null || items().getOrDefault("stick", 0) < 1) return;
		crafter.orderRecipe(planks.replace("_planks", "_sign"), 1);
	}

	private long signsAt;

	/** The least time between two things it says on its own (the talkAmount setting): it doesn't narrate. */
	private long talkGapMillis() {
		long floor = switch (mod.config.talkAmount) {
			case "quiet" -> 120_000L;
			case "chatty" -> 20_000L;
			default -> 45_000L;
		};
		return Math.max(floor, personality.chatterGapMillis());
	}

	void chatter(String text, boolean always) {
		long now = System.currentTimeMillis();
		if (!mod.config.chat || inArena || (!always && now - lastChatter < talkGapMillis())) return;
		boolean talking = talkingWith != null && player != null && player.level().getGameTime() < talkingUntil;
		if (always && !talking && now - lastChatter < 8_000) return;  // (Xen 2.0: two lines in a row only in a conversation)
		if (!always && !fresh(text)) return;                           // someone just said that: no need to say it again
		String key = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]+", "#").replaceAll("[^a-z# ]", "").trim();
		Long before = ownSaid.get(key);
		if (before != null && now - before < (talking ? 45_000 : 300_000)) {   // (it said that just now: a player doesn't repeat themselves)
			if (!(always && talking)) return;
			text = pick3("like I said, ", "I told you, ", "still: ") + Character.toLowerCase(text.charAt(0)) + text.substring(1);   // (asked again: an answer, not silence)
		}
		if (ownSaid.size() > 200) ownSaid.values().removeIf(t -> now - t > 300_000);
		ownSaid.put(key, now);
		lastChatter = now;
		say(text);
	}

	/** What it said lately (the meaning, numbers aside) and when: it doesn't say the same thing again for a while. */
	private final java.util.Map<String, Long> ownSaid = new java.util.HashMap<>();

	/**
	 * Hasn't any Xen said this lately (three minutes)? Then it's fresh (and noted as said). Ten Xens saying the same
	 * thing one after the other sounds like a machine, not like players.
	 */
	boolean fresh(String text) {
		long now = System.currentTimeMillis();
		String key = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]+", "#").replaceAll("[^a-z# ]", "");
		synchronized (mod.saidLately) {
			mod.saidLately.values().removeIf(t -> now - t > 180_000);
			if (mod.saidLately.containsKey(key)) return false;
			mod.saidLately.put(key, now);
		}
		return true;
	}

	/** How casually this Xen types (0: like a book, 1: like a player on a phone), the same all its life. */
	float casual() {
		float base = personality.hidden(Personality.TYPING);                    // (its hidden way of typing)
		return switch (personality.tone) {
			case "silly", "cheerful" -> Math.min(1f, base * 0.8f + 0.25f);
			case "calm" -> base * 0.6f;
			default -> base * 0.85f;
		};
	}

	private final java.util.Random typing = new java.util.Random();

	/** A line as this Xen types it (Xen 2.0: like a player: "gonna", "ngl", no full stop; never "2.7 blocks"). */
	String typed(String text) {
		if (mod.config.swearing && !minion) text = xen.mod.talk.Cursing.swear(text, rage.swearing(emotions.pain > 0.15f), rage.mad(), typing);   // (how foul its mouth is, how angry it is)
		return xen.mod.talk.Texting.casual(text, casual(), typing);
	}

	/** Whom it's answering in a whisper right now (null: out loud): what it says goes to them alone. */
	private ServerPlayer privateTo;

	/** Do this answering someone's whisper: what it says meanwhile is whispered back to them. */
	<T> T privately(ServerPlayer to, java.util.function.Supplier<T> r) {
		ServerPlayer was = privateTo;
		privateTo = to;
		try {
			return r.get();
		} finally {
			privateTo = was;
		}
	}

	/**
	 * A whisper to one person, with the game's own /msg, the way a player whispers: only they see it (nobody standing
	 * by overhears it, Xens included).
	 */
	void whisper(ServerPlayer to, String text) {
		if (player == null || to == null || !mod.config.chat || inArena) return;
		String line = typed(text).replace('\n', ' ').trim();
		if (line.isEmpty()) return;
		if (line.length() > 240) line = line.substring(0, 240);
		try {
			server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withSuppressedOutput(), "msg " + to.getGameProfile().name() + " " + line);
		} catch (RuntimeException e) {
			XenMod.LOG.debug("{} couldn't whisper: {}", name, e.toString());
			return;
		}
		XenMod.LOG.info("{} whispers to {}: {}", name, to.getName().getString(), line);
		journal("whispers", to.getName().getString() + ": " + line);
		life.said(line);
	}

	/** Someone whispered to it (/msg): a player is answered in a whisper; a Xen's secret is taken in, as if told. */
	void whispered(String fromName, String text) {
		if (player == null || inArena) return;
		ServerPlayer from = server.getPlayerList().getPlayerByName(fromName);
		journal("hears", fromName + " whispers: " + text);
		if (from == null) return;
		if (from instanceof XenPlayer xp) {
			rumors.overheard(fromName, xp.getUUID(), text);                // (gossip, a plot: it takes it in, told in confidence)
			return;
		}
		mod.whisperFrom(from, this, text);
	}

	/** When it last said anything (a reply, a word on its own), by the clock. */
	long saidMillis;

	public void say(String text) {
		saidMillis = System.currentTimeMillis();
		if (privateTo != null && player != null && privateTo.isAlive()) {   // answering a whisper: whispered back
			whisper(privateTo, text);
			return;
		}
		text = typed(text);
		Component line = Component.literal("<" + name + "> " + text);
		if (mod.config.localChat && player != null) {                     // local chat: only those close by hear it
			for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof XenPlayer) && mod.inRange(p, player)) p.sendSystemMessage(line);
			XenMod.LOG.info("<{}> {} (local chat)", name, text);
		} else {
			server.getPlayerList().broadcastSystemMessage(line, false);
		}
		if (player != null) mod.overheardBy(player, name, text);          // and the Xens close by
		String said = text;
		if (player != null) mod.gameplayLog.quietly(() -> mod.gameplayLog.chat(player, said));
		life.said(text);                                                // a nod, a shake of the head, a wave
		journal("says", text);
		if (talkingWith != null && player != null && player.level().getGameTime() < talkingUntil) {
			Talk t = talks.get(talkingWith);
			if (t != null) t.reply = text;                                  // (what it answered them: a follow-up is read with it)
		}
	}

	/** Chit-chat with each person: how keen it is still (0 to 1), when they last said something, until when it's had enough. */
	private final Map<UUID, double[]> chatMood = new HashMap<>();

	/**
	 * Chit-chat (not a request): it answers as long as it feels like it, not always. Each line of a long chat tires it a
	 * little (less a chatty one, and with someone it likes; more when it's busy), and a while without talking brings it
	 * back. A question is harder to leave unanswered. When it's had enough it winds the chat up, once, and leaves idle
	 * chat be for a minute or three (a request still gets done).
	 */
	private boolean wantsToAnswer(UUID u, String words) {
		long now = player.level().getGameTime();
		double[] m = chatMood.computeIfAbsent(u, k -> new double[] {1, now, -1});
		if (now < m[2]) return false;                                         // (it said it had to go)
		if (now - m[1] > 600) m[0] = Math.min(1, m[0] + (now - m[1] - 600) / 2400.0 * 0.6);   // a real pause (half a minute or more): keen again
		m[1] = now;
		boolean question = words.trim().endsWith("?") || words.matches("(?i)^(what|where|why|how|who|when|do|does|did|are|is|can|will|would)\\b.*");
		boolean filler = words.trim().split("\\s+").length <= 3 && !question;   // "lol", "ok", "cool": not much to answer
		boolean busy = fightingNow() || builder.busy() || chores.busy();
		boolean fun = xen.mod.talk.Texting.banter(words) || words.matches("(?i).*\\b(lol|lmao|haha|thanks|ty|nice|gg|w)\\b.*");   // (a laugh, a thanks: it's fun, it stays)
		m[0] -= (question ? 0.02 : filler ? 0.05 : 0.035) * (1.3 - personality.chattiness) * (1 - 0.4 * Math.max(0, trust(u))) + (busy ? 0.015 : 0) - (fun ? 0.03 : 0);
		m[0] = Math.min(1, m[0]);
		if (m[0] < 0.15) {                                                   // enough: it winds it up
			m[2] = now + 1200 + (long) (2400 * (1 - personality.chattiness));
			m[0] = 0.6;
			chatter(pick3(busy ? "Sorry, gotta focus. Talk later!" : "Anyway, I've got stuff to do. Talk later!", "Okay, I'm gonna get back to it. See ya!",
					"Alright, enough chatting for me. Later!"), true);
			if (u.equals(talkingWith)) talkingWith = null;
			return false;
		}
		return question || m[0] > 0.4 || random().nextFloat() < 0.5f + m[0];   // (a little tired of it: now and then it lets one go)
	}

	private static String clip(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n) + "...";
	}

	/** A conversation with one player: what they said last and before, their last question and what it was about, its answer. */
	private static final class Talk {
		String line, prevLine, question, subject, reply, prevReply;
		long at = -1_000_000;
	}

	private final Map<UUID, Talk> talks = new HashMap<>();

	/** How much of the world's lore it has written down (entries), and its volumes so far (a chronicler: see {@link Lore}). */
	int loreWritten, loreVolume;

	/** Something that belongs in the world's lore (it happened today). */
	void lore(String text) {
		if (player == null || inArena) return;
		mod.lore.add(server.overworld().getGameTime() / 24000 + 1, text);
	}

	/**
	 * A chronicler, once a day: writes what happened since its last volume into a book (when there's enough to tell
	 * and it has a book and quill, or can make one from a book, a feather and an ink sac; in creative, always) and
	 * keeps it.
	 */
	void writeChronicle() {
		if (player == null || !Lore.chronicler(this)) return;
		var fresh = mod.lore.since(loreWritten);
		if (fresh.size() < 4) return;
		var inv = player.getInventory();
		boolean can = player.isCreative();
		if (!can) {
			for (int i = 0; i < inv.getContainerSize() && !can; i++) {
				if (inv.getItem(i).is(net.minecraft.world.item.Items.WRITABLE_BOOK)) {
					inv.getItem(i).shrink(1);
					can = true;
				}
			}
		}
		if (!can && inv.countItem(net.minecraft.world.item.Items.BOOK) > 0 && inv.countItem(net.minecraft.world.item.Items.FEATHER) > 0
				&& inv.countItem(net.minecraft.world.item.Items.INK_SAC) > 0) {                   // (a book and quill from a book, a feather, ink)
			for (var item : new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.BOOK, net.minecraft.world.item.Items.FEATHER,
					net.minecraft.world.item.Items.INK_SAC}) {
				for (int i = 0; i < inv.getContainerSize(); i++) {
					if (inv.getItem(i).is(item)) {
						inv.getItem(i).shrink(1);
						break;
					}
				}
			}
			can = true;
		}
		if (!can) {
			if (random.nextFloat() < 0.3f) chatter(pick3("I should write down what's been happening. I need a book and quill.",
					"So much has happened. If only I had a book to write it in.", "Someone should keep a record of all this. Anyone got a book and quill?"), false);
			return;
		}
		loreVolume++;
		net.minecraft.world.item.ItemStack book = Lore.book(name, loreVolume, fresh);
		loreWritten = mod.lore.entries.size();
		if (!inv.add(book)) Compat.drop(player, book);
		journal("does", "writes volume " + loreVolume + " of the chronicle (" + fresh.size() + " entries)");
		chatter(pick3("I wrote down what's happened lately. Volume " + loreVolume + " of my chronicle!", "Another volume of the chronicle, done.",
				"History, written down. Ask me if you want to read it."), false);
	}

	/** A line in the journal (the Experimental tab's log): what it sees, thinks, says, how it goes. */
	void journal(String kind, String text) {
		if (mod.config.journal && mod.journal != null) mod.journal.add(name, kind, text);
	}

	/** Hurt: by what, how much, and what it was doing then (so a death can be read back). */
	void journalHurt(DamageSource source, float amount) {
		if (!mod.config.journal || mod.journal == null || inArena) return;
		var k = source.getEntity();
		String by = k != null ? BuiltInRegistries.ENTITY_TYPE.getKey(k.getType()).getPath().replace('_', ' ') : source.type().msgId();
		String doing = !chores.doing.isEmpty() ? chores.doing : goals.instant.isEmpty() ? "looking around" : goals.instant;
		journal("hurt", String.format(java.util.Locale.ROOT, "%.0f by %s (health %.0f, food %d, %s, light %d) while %s%s", amount, by, player.getHealth(),
				player.getFoodData().getFoodLevel(), player.level().isDarkOutside() ? "night" : "day",
				player.level().getMaxLocalRawBrightness(player.blockPosition()), doing, fightingNow() ? " (fighting)" : ""));
	}

	private String journaledThought = "", journaledSight = "";
	private long journaledSightAt;

	// ------------------------------------------------------------------------ death and life
	void died(DamageSource source) {
		fighter.died();                                                // (what it was fighting cost more than it had)
		tips.died();                                                   // (the tips it was weighing: right or wrong)
		gut.died();                                                    // (if it had just gone against its gut: the gut was right)
		habits.died();
		prepared.died();
		goals.notItsDoing = source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL) || source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD);   // (/kill, the void: nothing it chose did that)
		focus.forget();                                                  // (dead: whatever it was doing, it starts over)
		if (goals.option >= 0) goals.finishOption(true);
		goals.notItsDoing = false;                  // Xen 2.0 learns what that choice led to
		if (source.getEntity() instanceof ServerPlayer) lostFight = true; // (beaten by someone: it may want to train)
		if (!source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) rage.provoke(source.getEntity() instanceof ServerPlayer ? 4 : 2, "died");   // (killed by someone: that gets to it most)
		belief.down(source.getEntity() != null ? 0.06f : 0.04f, "died");
		if (skills.partner != null) skills.endSpar(false);
		if (obs != null && !inArena) {
			float harm = lastHealth / 20f + 1f;
			mod.learn(obs, lastAction, 0, harm, obs, true, emotions, name);
		}
		obs = null;
		mod.brain.lives++;
		genDeaths++;
		String cause = source.type().msgId() + (source.getEntity() != null
				? ":" + BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath() : "");
		if (!inArena) mod.logLife(name, player.level().getGameTime() / 24000, lifeTicks, lifeReward, cause);
		if (!inArena) {
			var k = source.getEntity();
			lore(name + (k instanceof ServerPlayer sp ? " was killed by " + sp.getGameProfile().name()
					: k != null ? " was killed by a " + BuiltInRegistries.ENTITY_TYPE.getKey(k.getType()).getPath().replace('_', ' ')
					: " died (" + source.type().msgId() + ")") + " near " + player.blockPosition().getX() + " " + player.blockPosition().getZ());
		}
		lifeTicks = 0;
		lifeReward = 0;
		emotions.reset();
		String how = source.type().msgId();
		if (mod.config.journal && mod.journal != null && !inArena) mod.journal.died(name, cause + " at " + player.blockPosition().toShortString());
		diedOf = source.getEntity() != null ? BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath().replace('_', ' ') : how.replace('_', ' ');
		confusion.surprise(0.3f);                                         // (back from death: where am I, where's my stuff?)
		if (!inArena && !source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL) && random.nextFloat() < 0.4f + 0.4f * personality.chattiness) {
			String said = banter.event("died", java.util.Map.of("mob", new BanterSelf().death().isEmpty() ? "that" : new BanterSelf().death()));   // ("bruh", "i died to a skeleton lol")
			if (said != null) chatter(said, false);
		}
		boolean gone = how.contains("lava") || how.contains("outOfWorld") || how.contains("void") || how.contains("fire") || how.contains("explosion")
				|| how.contains("drown") || player.isUnderWater();                   // (under water: not worth drowning again for)
		BlockPos here = player.blockPosition().immutable();
		boolean again = lostAt != null && lostDimension == player.level().dimension() && lostAt.closerThan(here, 16)
				&& player.level().getGameTime() < lostUntil && (source.getEntity() instanceof Enemy || how.contains("fall"));
		if (again && !inArena) {                                      // killed going back to where it died: what's there wins (a player lets it go too)
			chatter(pick3("Not going back down there again.", "Too many monsters there. My stuff can stay.", "Twice is enough. I'll start over."), true);
			journal("does", "gives up on its things (died there again)");
		}
		lostAt = gone || inArena || again ? null : here;              // its things are lying there: back for them after
		saidGoingBack = false;
		lostDimension = player.level().dimension();
		lostUntil = player.level().getGameTime() + 72_000;            // (its things last five minutes of the ground there going: see lostAged)
		lostAged = 0;
		lostToolUntil = 0;
		aloneUntil = 0;
		helper = null;
		lostAgedAt = player.level().getGameTime();
		lostFoundAny = false;
		journal("does", "died: " + source.getLocalizedDeathMessage(player).getString());
		if (hardcore() && !inArena) {                                 // one life: that was it
			goneHow = source.getLocalizedDeathMessage(player).getString();
			goneAt = here;
			lore(name + " died for good (" + goneHow + ")");
			goneIn = 40;
			respawnIn = -1;
			return;
		}
		respawnIn = 60;                                               // three seconds, like pressing "Respawn"
	}

	/** Hardcore for Xens (one life each)? */
	boolean hardcore() {
		return mod.config.hardcore(server);
	}

	/** Hardcore: gone for good. Its body leaves the world, nothing of it is kept, and those who knew it remember it. */
	private void goneForGood() {
		java.util.UUID id = player == null ? null : player.getUUID();
		if (player != null && server.getPlayerList().getPlayer(player.getUUID()) == player) {
			hands.stop();
			server.getPlayerList().remove(player);
		}
		player = null;
		mod.forget(this);
		mod.goneForGood(this, id, goneAt == null ? BlockPos.ZERO : goneAt, goneHow);
	}

	/**
	 * Into another skin (one a real player made, found online): out of the game and straight back in wearing it, the
	 * way a player changes skins (its bag, health and place kept; no "joined the game"). Not in the middle of anything
	 * a moment away would spoil: false, and it waits for a better moment.
	 */
	boolean reskin(String to) {
		XenPlayer p = player;
		if (p == null || to == null || to.equals(skin) || respawnIn >= 0 || goneIn >= 0 || minion || inArena || !p.isAlive() || p.isSleeping()
				|| p.isPassenger() || !p.onGround() || p.isInWater() || fightingNow() || pillaring || builder.busy()) return false;
		ServerLevel level = (ServerLevel) p.level();
		Vec3 at = p.position();
		float yaw = p.getYRot();
		UUID was = p.getUUID(), now = Looks.uuid(name, Looks.builtIn(to));
		hands.stop();
		server.getPlayerList().remove(p);                             // (its bag, health and all saved, as when a player leaves)
		String old = skin;
		skin = to;
		if (!was.equals(now)) {                                       // (one of Minecraft's own skins went with another UUID: what's saved goes along)
			try (var files = java.nio.file.Files.list(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR))) {
				for (java.nio.file.Path f : files.filter(f -> f.getFileName().toString().startsWith(was.toString())).toList())
					java.nio.file.Files.copy(f, f.resolveSibling(now + f.getFileName().toString().substring(was.toString().length())),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				XenMod.LOG.warn("{}'s saved things didn't move with its new skin: {}", name, e.toString());
			}
			for (Companion o : mod.companions) {
				Float t = o.trust.remove(was);
				if (t != null) o.trust.put(now, t);
			}
		}
		reskinning = true;
		XenMod.quietJoin = true;
		try {
			join(level, at, yaw);
		} catch (RuntimeException e) {                                // (back in its old one, rather than not at all)
			XenMod.LOG.warn("{} couldn't change skins: {}", name, e.toString());
			skin = old;
			join(level, at, yaw);
		} finally {
			XenMod.quietJoin = false;
			reskinning = false;
		}
		mod.roster.remember(this, mod.teamOf(this));
		journal("is", "in a skin a real player made now (it had one of the mod's own)");
		return skin.equals(to);
	}

	/** Come back where a player would respawn (bed or world spawn), healthy and hungry-free. */
	private void respawn() {
		XenPlayer p = player;
		TeleportTransition spot = p.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.clearFire();
		p.removeAllEffects();
		hands.stop();
		server.getPlayerList().remove(p);
		if (lostAt != null) {                                          // did anything drop? (keepInventory, lava, someone took it: nothing to go back for)
			ServerLevel where = server.getLevel(lostDimension);
			if (where != null && where.isLoaded(lostAt) && where.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
					new net.minecraft.world.phys.AABB(lostAt).inflate(5), e -> e.isAlive()).isEmpty()) lostAt = null;
		}
		Vec3 at = dryNear(spot.newLevel(), spot.position());          // (the world's spawn out in the sea: on the nearest dry land, as the game does for players)
		XenMod.quietJoin = true;                                      // (coming back isn't news: no "joined the game")
		try {
			join(spot.newLevel(), at, spot.yRot());
		} finally {
			XenMod.quietJoin = false;
		}
		habits.respawned();
		goals.freshStart();
		respawnedAt = player.level().getGameTime();
	}

	/** That spot, or the nearest dry ground to it (32 blocks round) if it's in water: somewhere to stand and breathe. */
	private static Vec3 dryNear(ServerLevel level, Vec3 pos) {
		BlockPos b = BlockPos.containing(pos);
		if (level.getFluidState(b).isEmpty() && level.getFluidState(b.above()).isEmpty()) return pos;
		for (int r = 1; r <= 32; r++)
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // (ring by ring: the nearest first)
					BlockPos q = b.offset(dx, 0, dz);
					if (!level.isLoaded(q)) continue;
					BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q);
					if (level.getFluidState(top.below()).isEmpty() && level.getFluidState(top).isEmpty()
							&& !level.getBlockState(top.below()).getCollisionShape(level, top.below()).isEmpty()) return Vec3.atBottomCenterOf(top);
				}
		return pos;
	}

	/** When it last came back from dying (game time): back at the spawn in the dark with nothing, it digs in (see Gut.refuge). */
	long respawnedAt = -100_000;

	public String status() {
		if (player == null) return name + " is not here.";
		return String.format("%s (%s; %s; %s): health %.0f/20, food %d/20, mood %s (fear %.0f%%), mode %s. Goals: %s. %s Last thought: %s",
				name, personality.describe() + (personality.sins == null ? "" : "; " + personality.sinsInWords() + "; plan: "
						+ (personality.plan < 0 ? "none yet" : xen.mod.core.Strategy.NAMES[personality.plan]) + "; believes: " + personality.beliefsInWords()),
				personality.style(), goals.likes(), player.getHealth(), player.getFoodData().getFoodLevel(),
				emotions.mood(), emotions.fear * 100, mode.name().toLowerCase(), goals.status(),
				senses.describe() + (mimic.skill.isEmpty() ? "" : " " + mimic.describe()) + " " + skills.describe()
						+ (personality.loner ? " (a loner)" : ""), lastThought)
				+ String.format(java.util.Locale.ROOT, " Xen 2.0: %s, reactions %d ms on average; choices %d (%d yes, %d no, %d later)%s%s",
						confusion.says(), reflexes.averageMs(), choices.made, choices.yes, choices.no, choices.later,
						choices.thinking().isEmpty() ? "" : "; last: " + choices.thinking(), gut.doing.isEmpty() ? "" : "; gut: " + gut.doing)
				+ (focus.describe().isEmpty() ? "" : "; " + focus.describe()) + (hunch.describe().isEmpty() ? "" : "; " + hunch.describe()) + (guide.describe().isEmpty() ? "" : "; " + guide.describe()) + (planner.describe().isEmpty() ? "" : "; " + planner.describe())
				+ (director.describe().isEmpty() ? "" : "; " + director.describe());
	}

	/** How far along it is, in one line (for a long run's log): where, health, its pickaxe, the things that mark progress, its adventure. */
	public String progress() {
		if (player == null) return name + " | not here";
		var it = items();
		var a = adventure.stage();
		return String.format(java.util.Locale.ROOT, "%s | %s %d %d %d | hp %.0f food %d | pick %d | iron %d diamond %d obsidian %d blaze %d pearl %d eye %d | adventure %s | home %s | life %d s",
				name, Places.dim(player.level()), player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.getHealth(),
				player.getFoodData().getFoodLevel(), crafter.pickTier(), it.getOrDefault("raw_iron", 0) + it.getOrDefault("iron_ingot", 0), it.getOrDefault("diamond", 0),
				it.getOrDefault("obsidian", 0), it.getOrDefault("blaze_rod", 0), it.getOrDefault("ender_pearl", 0), it.getOrDefault("ender_eye", 0),
				a == null ? "-" : a.name().toLowerCase(java.util.Locale.ROOT), goals.home == null ? "-" : "yes", player.tickCount / 20);
	}

	/** Its notes for talking: only its own feelings, body and perception. */
	public String notes() {
		StringBuilder carrying = new StringBuilder();
		for (var e : items().entrySet()) {
			if (carrying.length() > 60) break;
			carrying.append(carrying.length() > 0 ? ", " : "").append(e.getValue()).append(' ').append(e.getKey().replace('_', ' '));
		}
		return xen.mod.talk.Chat.notes(emotions.mood(), emotions.pain > 0.15f, player.getHealth(),
				player.getFoodData().getFoodLevel(), carrying.toString(), senses.describe())
				+ " " + goals.describe() + " " + crafter.describe() + (builder.busy() ? " " + builder.describe() : "")
				+ join(places.describe(), storage.describe(), tribe() == null ? "" : tribe().describe(this), nether.describe(), adventure.describe(),
						trials.describe(), dragon.describe(), farmer.describe(), knowledge.describe(), shop.describe(), taste.describe(),
						skills.describe(), rumors.describe(), life.describe(), caves.describe(), structures.describe(), habits.routine())
				+ (mimic.skill.isEmpty() ? "" : " " + mimic.describe())
				+ (lastSign != null && player.level().getGameTime() - lastSignAt < 6000 ? " You read a sign that says: \"" + lastSign + "\"." : "")
				+ (instructions().isEmpty() ? "" : " " + xen.mod.talk.Chat.TOLD + " " + instructions())
				+ (hardcore() ? " You have only one life: if you die, you are gone for good." : "")
				+ (trader.market().isEmpty() ? "" : " " + trader.market())
				+ (mod.config.personalities ? " Your personality: " + personality.describe() + ". Your fighting style: " + personality.fight
						+ " (" + Personality.how(personality.fight) + "). " + personality.buildNote()
						+ (personality.sins == null ? "" : " You are " + personality.sinsInWords() + ". You believe: " + personality.beliefsInWords()
								+ " Your plan: " + (personality.plan < 0 ? "not worked out yet." : xen.mod.core.Strategy.WORDS[personality.plan])) : "");
	}

	private static final java.util.regex.Pattern PORTAL_MATH = java.util.regex.Pattern.compile(
			"\\bnether\\b.*\\b(8|eight)\\b|\\b(8|eight)\\b.*\\bnether\\b|\\b(divide|divided) by (8|eight)\\b");

	/**
	 * "Attack Steve" (its owner asked): with PvP on, it (and its tribe) goes after them; never its owner, never
	 * someone it trusts a lot (it says so instead), never with PvP off.
	 */
	private String attack(String who, ServerPlayer from) {
		if (mod.config.pvp.equals("off")) return "You won't attack players: PvP is off.";
		ServerPlayer target = who.isEmpty() ? null : server.getPlayerList().getPlayerByName(who);
		if (target == null) return "You don't see anyone called " + who + ".";
		if (target == player) return "You won't attack yourself.";
		if (target.getUUID().equals(owner)) return "You won't attack " + ownerName + ".";
		if (trust(target.getUUID()) >= 0.6f) return "You won't attack " + who + ": they are your friend.";
		Tribe t = tribe();
		long now = player.level().getGameTime();
		armedHitAt.put(target.getUUID(), now);
		if (t != null) t.enemies.put(target.getUUID(), now + 20 * 60);
		trust(target.getUUID(), -0.3f);
		return "You will attack " + who + (t != null && t.members.size() > 1 ? ", with your tribe" : "") + ".";
	}

	private static final java.util.regex.Pattern PLEASE = java.util.regex.Pattern.compile("\\b(please|pls|plz|i insist|i really need|it'?s important)\\b");
	private static final java.util.regex.Pattern FRIENDLY = java.util.regex.Pattern.compile(
			"\\b(thanks|thank you|thx|good (job|work)|nice|well done|love you|you rock|awesome|great|hi|hello|hey)\\b");
	private static final java.util.regex.Pattern WATCH = java.util.regex.Pattern.compile(
			"(watch|look at) (me|this)|see this|copy me|learn from me|do what i do|check this out");
	private static final java.util.regex.Pattern VILLAGER = java.util.regex.Pattern.compile("\\b(villagers?|trader|merchant)\\b");
	/** Soft refusals when asked for something while hurt, scared or needing what's asked for; "please" gets past them. */
	private static final java.util.Set<String> OUT = java.util.Set.of("explore", "wood", "stone", "coal", "iron", "mine", "food");

	/**
	 * Why it says no to a request, or null. Someone who hurt it gets a no, full stop. The rest are soft: when it's
	 * badly hurt it wants to heal before going out, a timid one won't explore in the dark, and it won't give away the
	 * food it needs, what its dream needs, or its things to a stranger. "Please" (or insisting) changes its mind.
	 */
	private String refusal(xen.mod.talk.Chat.Request r, ServerPlayer from, String words) {
		if (!mod.config.refuse) return null;
		String who = from.getName().getString();
		float t = trust(from.getUUID());
		if (t < -0.2f) return "No, you won't, because " + who + " hurt you (a sorry would help).";
		if (hardcore() && OUT.contains(r.intent()) && player.getHealth() < player.getMaxHealth() * 0.6f) {   // (please or not: one life)
			return "No, not hurt like this: you have only one life, and nobody's request is worth it. Heal first.";
		}
		if (PLEASE.matcher(words).find()) return null;
		if (owner == null && t < 0.3f && !r.intent().equals("peace") && random().nextFloat() < (0.3f - t) * 2 + 0.3f * (1 - personality.kindness)) {
			return "No, you won't: you don't know " + who + " well enough yet.";   // (like anyone: a stranger asks, it may not feel like it)
		}
		String intent = r.intent();
		float health = player.getHealth() / player.getMaxHealth();
		if (OUT.contains(intent) && health < 0.3f) return "No, not now: you are badly hurt and need to heal first.";
		if (intent.equals("explore") && personality.bravery < 0.3f && player.level().isDarkOutside()) {
			return "No, you won't go exploring now, because it's dark and you are scared.";
		}
		if (intent.equals("give")) {
			if (owner == null && t < 0.4f) return "No, you won't give your things to a stranger.";
			String key = switch (r.thing()) {
				case "raw_iron" -> "iron";
				case "all" -> "food";
				default -> r.thing();
			};
			if (key.equals("food") && player.getFoodData().getFoodLevel() <= 12 && items().getOrDefault("food", 0) <= 3
					&& items().getOrDefault("food", 0) > 0) {
				return "No, you won't give your food away, because you are hungry.";
			}
			String why = trader.neededFor(key, r.amount());
			if (why != null && !r.thing().equals("all")) {
				return "No, you won't give your " + Chores.named(r.thing(), 2) + " away, because you need them " + why.replaceFirst("^for its", "for your")
						.replaceFirst("^because diamonds are its dream", "for your dream").replaceFirst("^because it's hungry", "to eat") + ".";
			}
		}
		return null;
	}

	/**
	 * A player said something to it. A request: it does it (with its own hands and senses) and says its plan in plain
	 * words, or why it won't. Only its owner can tell it what to do (anyone, if it has no owner), and it can say no.
	 * Trading is its own business: anyone can make it an offer. Just talk: returns its notes for the chat to answer from.
	 */
	private static final java.util.regex.Pattern LATER = java.util.regex.Pattern.compile("\\b(later|sometime|some time|tomorrow|tmr|tmrw|another time|one day|someday|after this)\\b");
	/** What a player says about themselves or to greet (never an order, whatever words are in it). */
	private static final java.util.Set<String> ABOUT_THEM = java.util.Set.of("goodnight", "bye", "good_morning", "greet", "thanks", "sorry", "laugh");

	/** What its talk model answers first when it's sure (talk, not orders: "gonna go to sleep" isn't "go to sleep"). */
	private static final java.util.Set<String> EARLY_TALK = java.util.Set.of("greet", "wsg", "how_are_you", "player_fine", "player_bad", "player_tired",
			"player_bored", "player_happy", "player_scared", "player_angry", "player_died", "player_died_creeper", "player_died_lava", "player_died_fall",
			"player_found_diamonds", "player_found", "player_built", "player_got", "player_new", "player_away", "player_back", "bye", "goodnight",
			"good_morning", "thanks", "sorry", "laugh", "agree", "disagree", "ok", "wow", "nice", "fail", "bruh", "confused", "doing", "are_you_bot",
			"who_are_you", "age", "where_live", "where_from", "fav_block", "fav_mob", "fav_food", "fav_biome", "fav_color", "fav_thing", "fav_weapon",
			"likes_me", "love_you", "compliment", "shut_up", "joke", "story", "real_life", "meaning_of_life", "sing", "best_ore", "where_diamonds",
			"have_items", "time", "weather", "sus", "random_question", "where_are_you", "lag", "hacker", "skin", "name_meaning", "dare", "xen_hungry",
			"xen_hurt", "xen_scared", "time_irl", "compliment_xen_work");

	/** Requests that are about fetching things (what a statement like "I found diamonds" is mistaken for). */
	private static final java.util.Set<String> GATHERING = java.util.Set.of("mine", "gather", "wood", "stone", "iron", "coal", "food", "diamonds", "ore");

	public String request(xen.mod.talk.Chat.Request r, ServerPlayer from, String said) {
		if (player == null) return null;
		UUID u = from.getUUID();
		known.add(u);                                                 // now it knows them
		String who = from.getName().getString();
		Talk talk = talks.computeIfAbsent(u, k -> new Talk());         // what was said before: "what about cows?", "and you?", "is it good?"
		long saidAt = player.level().getGameTime();
		if (said != null) said = xen.mod.talk.Chat.fromThai(said);             // (Thai: in the words it reads; what it asks for was read in Thai already)
		if (said != null && r.intent().equals("chat") && comeAnswered(from, said)) return null;   // (called over: "nothing, thanks": back to its own things)
		if (!r.intent().equals("chat") && !r.intent().equals("follow") && comeFor != null) {   // called over and now asked for something: that's what it was for
			comeFor = null;
			modeBeforeCome = null;
		}

		if (said != null && r.intent().equals("chat")) {
			boolean fresh = saidAt - talk.at < 20 * 60 * 3;
			String full = xen.mod.talk.Voice.followUp(said, name, fresh ? talk.question : null, fresh ? talk.subject : null, fresh ? talk.line : null);
			if (full != null && !full.equalsIgnoreCase(said)) {
				journal("thinks", "follow-up: \"" + said + "\" means \"" + full + "\"");
				said = full;
				r = xen.mod.talk.Chat.understand(full, name);
			}
		}
		if (said != null) {
			talk.prevLine = talk.line;
			talk.prevReply = talk.reply;
			talk.reply = null;
			talk.line = said;
			talk.at = saidAt;
			if (xen.mod.talk.Voice.hear(said, name).question) {
				talk.question = said;
				String subject = xen.mod.talk.Voice.subject(said, name);
				if (!subject.isEmpty()) talk.subject = subject;
			}
		}
		String words = said == null ? "" : xen.mod.talk.Chat.requestWords(said, name);
		if (trust(u) >= 0.3f && knowledge.heard(words)) return null;   // how to handle something it didn't know ("in a storm, wait in a shelter"): learned, not an order
		xen.mod.talk.Voice.Heard told = xen.mod.talk.Voice.hear(said == null ? "" : said, name);
		if (told.insult && from != null && !xen.mod.talk.Texting.banter(said)) rage.provoke(random.nextFloat() < 0.4f ? 2 : 1, "insulted by " + from.getName().getString());   // (a joke isn't an insult)
		boolean aboutThem = words.toLowerCase(java.util.Locale.ROOT).matches("(?s)^\\s*i('m| am| really| just| also)? ?(love|like|hate|found|got|have|had|made|saw|think|mined|killed|built|am|was)\\b.*");
		if ((told.news || told.feelMe || aboutThem) && !told.question && GATHERING.contains(r.intent())) r = new xen.mod.talk.Chat.Request("chat", "", 0);   // "I found diamonds!", "I love diamonds": talk, not "go mine diamonds"
		xen.mod.talk.Words.Meaning grammar = said == null ? null : xen.mod.talk.Words.parse(said, name);
		if (grammar != null && !r.intent().equals("chat") && xen.mod.talk.Words.awaiting(who) && grammar.act() != xen.mod.talk.Words.Act.COMMAND
				&& (grammar.rel() == null || grammar.subject() != null && java.util.Set.of("it", "they", "them").contains(grammar.subject().key()))) {   // its question's answer ("fish", "yes", "it's a mob"), not a request
			journal("thinks", "\"" + said + "\" answers what it asked: not a request (" + r.intent() + ")");
			r = new xen.mod.talk.Chat.Request("chat", "", 0);
		}
		if (grammar != null && r.intent().equals("chat") && xen.mod.talk.Words.awaiting(who) && grammar.act() != xen.mod.talk.Words.Act.COMMAND) {   // its answer first ("fish" isn't "go fishing" then)
			String w = xen.mod.talk.Words.reply(said, wordsWith(who));
			if (w != null) {
				journal("thinks", "words: the answer to what it asked; knows " + memory.size() + " facts");
				say(w);
				return null;
			}
		}
		if (grammar != null && !r.intent().equals("chat") && aboutAThing(grammar)) {   // its grammar: "cows drop leather", "diamonds are at y -58" tell it something, they aren't requests
			journal("thinks", "\"" + said + "\" tells it something about " + grammar.subject().text() + ": not a request (" + r.intent() + ")");
			r = new xen.mod.talk.Chat.Request("chat", "", 0);
		}
		needs.heard(from, words);                                      // "I'm hungry", "I need wood": it may help (its choice)
		if (!r.intent().equals("peace")) {
			String heated = teaching.inFight(from, words);              // in a fight with them: it talks like it
			if (heated != null) {
				say(heated);
				return null;
			}
		}
		if (said != null) banter.reaction(said, player.level().getGameTime());   // (how its last answer went down: a laugh, a "what?")
		boolean later = LATER.matcher(words).find() && !r.intent().equals("chat");   // "wanna go mining later": an idea for later, not a request now
		if (later) r = new xen.mod.talk.Chat.Request("chat", "", 0);
		if (said != null && !r.intent().equals("chat") && !r.intent().equals("peace")) {   // "im gonna go to sleep", "brb gonna eat": about them, not an order
			xen.mod.talk.Banter.Read about = xen.mod.talk.Banter.read(said, name);
			if (about.p() >= 0.95f && (about.kind().startsWith("player_") || ABOUT_THEM.contains(about.kind()))) {
				journal("thinks", "\"" + said + "\" is about them (" + about.kind() + "), not a request (" + r.intent() + ")");
				r = new xen.mod.talk.Chat.Request("chat", "", 0);
			}
		}
		if (said != null && r.intent().equals("chat") && !talker.waitingFor(u)) {   // talk it's sure of: its talk model answers, before any keyword reads it as an order
			xen.mod.talk.Banter.Read rd = xen.mod.talk.Banter.read(said, name);
			if (rd.p() >= 0.85f && EARLY_TALK.contains(rd.kind()) || later && rd.kind().startsWith("invite_")) {
				talkingWith = u;                                              // (a conversation: for the next half minute, no name needed)
				talkingUntil = player.level().getGameTime() + 600;
				if (!wantsToAnswer(u, words)) return null;
				xen.mod.talk.Banter.Answer a = banter.answer(who, said, player.level().getGameTime());
				journal("thinks", "talk model: " + banter.lastHeard + (a == null ? " (nothing true to say)" : " -> " + a.id()));
				if (a != null) {
					say(a.text());
					return null;
				}
			}
		}
		String lesson = teaching.heard(from, words);                    // "lava burns", "teach me mining", "any tips?"
		if (lesson != null) {
			say(lesson);
			return null;
		}
		Tribe village = tribe();
		if (village != null && village.members.size() >= 2) {             // "new rule: no fighting in the village": they vote
			String vote = village.laws.propose(words, from, who);
			if (vote != null) {
				say(vote);
				return null;
			}
		}
		if (owner == null || owner.equals(u) || trust(u) >= 0.5f) {         // "build a highway north out of obsidian"
			String road = highway.asked(words);
			if (road != null) {
				goals.drop();
				say(xen.mod.talk.Chat.firstPerson(road));
				return null;
			}
		}
		var place = GO_PLACE.matcher(words.toLowerCase(java.util.Locale.ROOT));
		if ((owner == null || owner.equals(u) || trust(u) >= 0.5f) && place.find()) {   // "go to the cave", "take me to the village": a place it knows
			say(goToPlace(place.group(1)));
			return null;
		}
		if ((owner == null || owner.equals(u) || trust(u) >= 0.5f) && !COORDS.matcher(words).find()) {   // "go over there", "50 blocks that way"
			String vague = goFeature(words.toLowerCase(java.util.Locale.ROOT));
			if (vague == null) vague = goVague(from, words.toLowerCase(java.util.Locale.ROOT));
			if (vague != null) {
				say(vague);
				return null;
			}
		}
		if ((owner == null || owner.equals(u) || trust(u) >= 0.5f) && GO_TO.matcher(words.toLowerCase(java.util.Locale.ROOT)).find()) {
			say(goTo(words.substring(GO_TO.matcher(words.toLowerCase(java.util.Locale.ROOT)).results().findFirst().get().start())));
			return null;                                               // "go to 120 64 -40"
		}
		var founding = MAKE_TEAM.matcher(words.toLowerCase(java.util.Locale.ROOT));
		if (founding.find() && (owner == null || owner.equals(u) || trust(u) >= 0.5f)) {   // "make a team called Night Owls", "make a team with me"
			String called = founding.group(3) != null ? words.substring(founding.start(3), Math.min(words.length(), founding.end(3))) : null;
			say(mod.foundTeam(this, called, founding.group(4) != null ? from : null));
			return null;
		}
		if (FISH.matcher(words.toLowerCase(java.util.Locale.ROOT)).find()) {   // "go fishing"
			say(xen.mod.talk.Chat.firstPerson(fisher.start()));
			return null;
		}
		String train = skills.asked(from, words);                      // "train your fighting", "practice mining"
		if (train != null) {
			if (skills.trainingNow()) goals.drop();
			say(train);
			return null;
		}
		String teamAnswer = teamMade(from, words);                     // "let's make a team called ...": made for them, and it decides whether to join
		if (teamAnswer == null) teamAnswer = teamAsked(from, words);   // "join my team", "join the red team": its call
		if (teamAnswer != null) {
			say(teamAnswer);
			return null;
		}
		if (SLEEP.matcher(words).find() && !r.intent().equals("give")) {  // "go to bed", "sleep"
			if (!player.level().isDarkOutside()) say(pick3("It's not night yet!", "Sleep? It's still day.", "Too early for bed."));
			else if (!hasBed()) say(pick3("I don't have a bed yet.", "No bed at home... yet.", "I'd need a bed for that."));
			else {
				goals.drop();
				mode = Mode.FREE;
				say(pick3("Good idea. Night!", "Off to bed.", "Yeah, I'm tired."));
			}
			return null;
		}
		Needs.Need need = needs.pending();
		if (need != null && need.who().equals(u) && (r.intent().equals("chat") || r.intent().equals("food")) && need.item().equals("food")
				&& words.matches("(?s).*\\b(i'?m|im|i am|so|very)\\s+(hungry|starving|starved).*")) {   // "I'm hungry": share or not, its call
			boolean share = personality.generosity() + 0.5f * trust(u) > 0.6f && needs.canHelp();   // (greed and "what's mine is mine" say no)
			if (share) {
				String plan = needs.help();
				say(plan != null && plan.startsWith("You will") ? pick3("Here, have some of mine.", "Take some food!", "I've got you. Here.")
						: "I only have enough for myself, sorry.");
			} else {
				needs.clear();
				say(needs.canHelp() ? pick3("I need my food myself.", "Get your own food.", "Sorry, I'm keeping mine.") : "I don't have any food either.");
			}
			return null;
		}
		if (r.intent().equals("chat") && FRIENDLY.matcher(words).find()) trust(u, 0.05f);   // kind words
		script.heard(words, from);
		talkingWith = u;                                              // a conversation: for the next half minute, no name needed
		talkingUntil = player.level().getGameTime() + 600;
		if (talker.answered(from, words)) return null;               // a yes or no to something it asked
		if (r.intent().equals("chat") && !wantsToAnswer(u, words)) return null;   // (it's had enough of chit-chat for now)
		java.util.regex.Matcher rem = REMEMBER.matcher(words);
		if (rem.find() && (owner == null || owner.equals(u) || trust(u) >= 0.3f)) {
			String fact = rem.group(4).trim().replaceAll("[.!]+$", "");
			fact = fact.replaceAll("\\bmy\\b", who + "'s").replaceAll("\\bi am\\b|\\bi'm\\b", who + " is").replaceAll("\\bme\\b", who).replaceAll("^i ", who + " ");
			memories.add(who + " told you: " + fact + ".");
			while (memories.size() > 12) memories.remove(0);
			say(pick3("Okay, I'll remember that.", "Got it. I won't forget.", "Noted!"));
			lastThought = "Remembered: " + fact;
			return null;
		}
		if (FORGET_ALL.matcher(words).find()) {
			memories.removeIf(m -> m.startsWith(who + " told you:"));
			say("Okay, I forgot it.");
			return null;
		}
		if (RECALL.matcher(words).find()) {
			java.util.List<String> mine = memories.stream().filter(m -> m.startsWith(who + " told you:")).toList();
			say(mine.isEmpty() ? "You haven't asked me to remember anything." : "You told me: "
					+ String.join(" ", mine.subList(Math.max(0, mine.size() - 3), mine.size()).stream().map(m -> m.substring((who + " told you: ").length())).toList()));
			return null;
		}
		if (r.intent().equals("chat") && WATCH.matcher(words).find()) {
			watchFor = u;
			watchUntil = player.tickCount + 1200;                     // a minute
			say(mod.config.copy ? "I'm watching! If it works, I'll try it too." : "I'm watching!");
			return null;
		}
		boolean trade = r.intent().equals("trade");
		if (trade && VILLAGER.matcher(words).find() && (owner == null || owner.equals(u))) {
			goals.drop();
			chores.cancel();
			String plan = trader.withVillager(null);
			lastThought = "Asked by " + who + ": " + plan;
			say(xen.mod.talk.Chat.plainly(notes() + " Plan: " + plan, ""));
			return null;
		}
		if (trade || trader.dealWith(u)) {                           // a trade: its own answer, in its own words
			String reply = trader.talk(from, words);
			if (reply != null) {
				lastThought = "Trading with " + who + ": " + reply;
				say(reply);
				return null;
			}
			if (trade) r = new xen.mod.talk.Chat.Request("chat", "", 0);
		}
		if (r.intent().equals("peace")) {                                   // anyone may ask for peace (not only its owner)
			Tribe t = tribe();
			String answer = diplomacy.asked(from);
			if (t != null && diplomacy.atPeace(from)) t.peace(from.getUUID());   // a truce with one is a truce with the tribe
			say(answer);
			return null;
		}
		if (r.intent().equals("chat") && trust(u) >= 0.3f && knowledge.heard(words)) return null;   // it was taught how something works
		if (r.intent().equals("chat") || r.intent().equals("mine") || r.intent().equals("iron") || r.intent().equals("coal")) {
			String reply = lessons.heard(words, from.getName().getString(), trust(u));   // a tip where to mine ("diamonds at y -58")
			if (reply != null) {
				say(reply);
				return null;
			}
		}
		if (!words.matches("(?s).*\\b(build|make|dig|craft|put up)\\b.*")) {   // what someone thinks of its house (not a request): it learns
			String thanks = taste.heard(words, from);
			if (thanks != null) {
				say(thanks);
				return null;
			}
		}
		String plan = null;
		boolean refused = false;
		boolean fromBoss = minion && boss != null && boss.player != null && boss.player.getUUID().equals(u);
		if (!r.intent().equals("chat") && owner != null && !owner.equals(u) && !fromBoss) {
			plan = "Only " + (minion && boss != null ? boss.name + " (or " + ownerName + ")" : ownerName) + " can tell you what to do, so you won't.";
		} else if (!r.intent().equals("chat") && (plan = refusal(r, from, words)) != null) {
			refused = true;
			lastThought = "Said no to " + who + ": " + plan;
		} else {
			if (!r.intent().equals("chat")) goals.drop();            // being asked for something comes before its own goals
			boolean wasBusy = chores.busy();
			if (!r.intent().equals("chat")) {                           // a new request replaces what it was doing
				chores.cancel();
				crafter.cancelOrder();
				builder.cancel();
				storage.cancel();
				if (!r.intent().equals("follow")) nether.cancel();
				adventure.on = false;
				trials.on = false;
				farmer.cancel();
				hands.stop();
				if (!r.intent().equals("ride")) rider.cancel();
				if (!r.intent().equals("use")) uses.cancel();
				sharing.cancel();
				woolTrip = null;
			}
			switch (r.intent()) {
				case "follow" -> {
					chores.cancel();
					asked = null;                                              // (a new request: what it was asked before is over)
					resumeAsked = false;
					leaderSpot = null;
					boolean come = words.matches("(?s).*\\b(come|cmere|over here|to me|here)\\b.*") && !words.matches("(?s).*\\b(follow|with me|keep up)\\b.*");
					if (come) {                                                // over to them, right up close (wherever they are), then it sees what's up
						if (comeFor == null) modeBeforeCome = mode;            // (after: back to its own life, unless they ask it to stay with them)
						comeFor = from.getUUID();
						comeUntil = player.level().getGameTime() + 20 * 180;
						cameAt = -1;
					} else {
						comeFor = null;
						modeBeforeCome = null;
					}
					mode = Mode.FOLLOW;
					leader = from.getUUID();
					plan = come ? "You will come over to " + who + "." : "You will follow " + who + ".";
				}
				case "ride" -> {
					if (mode == Mode.STAY) mode = Mode.FOLLOW;
					if (leader == null || mode == Mode.FOLLOW) leader = from.getUUID();
					plan = rider.ask(from, words);
				}
				case "dismount" -> plan = rider.getOut();
				case "use" -> plan = uses.ask(from, words);
				case "stay" -> {
					chores.cancel();
					asked = null;
					resumeAsked = false;
					mode = Mode.STAY;
					anchor = player.blockPosition();
					plan = "You will stay here.";
				}
				case "explore" -> {
					chores.cancel();
					mode = Mode.FREE;
					plan = goals.exploreAsked(20 * 60 * 3);                // (three minutes of it, then its own plans again)
				}
				case "stop" -> {
					fisher.stop();
					asked = null;                                          // (stopped on purpose: nothing to come back to)
					resumeAsked = false;
					boolean busy = wasBusy;
					if (!busy) {
						mode = Mode.STAY;
						anchor = player.blockPosition();
					}
					plan = busy ? "You will stop what you are doing." : "You will stop and wait here.";
				}
				case "wood", "stone", "coal", "iron", "mine" -> {
					plan = (r.intent().equals("mine") || r.intent().equals("iron")) && caves.nearest(160) != null
							? chores.mine(lessons.depth("iron", 16), "iron", Math.max(3, r.amount())) : chores.gather(r.intent(), r.amount());   // (a cave it knows: in there)
					if (plan.startsWith("You can't go mining")) plan = chores.gather(r.intent().equals("mine") ? "iron" : r.intent(), Math.max(3, r.amount()));   // (its pickaxe first: gather works up to it)
					asked = r;                                             // (remembered: if something cuts it short, it comes back to it)
					askedBy = from;
					askedWords = said;
				}
				case "food" -> plan = chores.hunt(r.amount());
				case "pickup" -> plan = chores.pickUp(r.thing());
				case "give" -> plan = chores.give(from, r.thing(), r.amount());
				case "shelter" -> plan = chores.shelter();
				case "eat" -> plan = chores.eat();
				case "redstone" -> plan = chores.redstone(r.thing());
				case "craft" -> {                                                  // something it can make right away; else, from what it knows, a plan
					String id = crafter.recipeIdFor(r.thing());
					String what = id != null ? id : r.thing();
					Plans.Plan p = plans.make(what, Math.max(1, r.amount()));
					plan = p.ok && p.steps.size() > 1 || id == null && p.ok ? plans.setGoal(what, Math.max(1, r.amount()), from.getGameProfile().name())
							: crafter.request(r.thing(), r.amount());
				}
				case "bed" -> plan = bedAsked();
				case "helpbuild" -> {                                              // a friend's build: help with it (shared progress)
					Companion mate = null;
					for (Companion o : mod.companions) if (o != this && o.name.equalsIgnoreCase(r.thing())) mate = o;
					if (mate == null && r.thing().isEmpty()) mate = builder.friendBuilding();
					plan = mate == null ? "You can't: you don't see who that is building." : builder.help(mate);
				}
				case "build" -> plan = r.thing().equals("upgrade") ? builder.upgrade()
						: r.thing().startsWith("statue") ? builder.statue(r.thing().substring(Math.min(r.thing().length(), 7)), from)
						: r.thing().equals("farm") ? farmer.start() : builder.start(r.thing());
				case "quest" -> plan = switch (r.thing()) {
					case "nether" -> nether.go(nether.inNether() ? "home" : "visit", 0);
					case "blaze" -> nether.go("blaze", Math.max(1, r.amount() > 0 ? r.amount() : 6));
					case "home" -> nether.go("home", 0);
					case "trial" -> {
						trials.spot();                                          // (one right here it hasn't noticed yet)
						yield trials.start();
					}
					case "portal" -> builder.startNear("nether portal", null);
					default -> adventure.start();                                       // the dragon (the stronghold, the End)
				};
				case "store" -> plan = storage.store();
				case "guard" -> {
					Tribe t = tribe();
					plan = t == null || t.center == null ? "You have no village to guard yet." : "You will keep watch over the village.";
					if (t != null && t.center != null) {
						mode = Mode.STAY;
						anchor = t.center;
					}
				}
				case "attack" -> plan = attack(r.thing(), from);
				default -> {}
			}
		}
		if (plan == null && r.intent().equals("chat")) {                     // a survival question it knows the answer to (the wiki's facts)
			String fact = Survival.answer(words);
			if (fact != null) {
				journal("thinks", "knows this one: " + fact);
				say(fact);
				return null;
			}
		}
		if (plan == null && r.intent().equals("chat") && said != null && !(grammar != null && aboutAThing(grammar))) {   // its talk model: a player's answer to a player
			xen.mod.talk.Banter.Answer a = banter.answer(who, said, player.level().getGameTime());
			journal("thinks", "talk model: " + banter.lastHeard + (a == null ? " (not sure: the rest of its chat answers)" : " -> " + a.id()));
			if (a != null) {
				say(a.text());
				return null;
			}
		}
		if (plan == null) {
			boolean ownWords = true;                                          // (its own words: there's no language model)
			xen.mod.talk.Voice.Heard heard = told;
			String straight = talker.answer(words);                   // everyday questions: from what it knows, nothing made up
			if (straight == null && r.intent().equals("chat") && said != null && grammar != null) {   // Xen 2.0 beta 6: facts it's told, questions its memory (or the game) answers
				String w = xen.mod.talk.Words.reply(said, wordsWith(who));
				if (grammar.act() == xen.mod.talk.Words.Act.TELL && "at".equals(grammar.rel()) && grammar.object() != null && "coords".equals(grammar.object().kind())
						&& grammar.subject() != null) {                             // "the village is at 120 64 -30": a place it knows now
					String[] xyz = grammar.object().key().split(" ");
					if (xyz.length >= 3) places.remember(grammar.subject().key(), new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
				}
				if (w != null) {
					journal("thinks", "words: " + grammar.act() + " (" + (grammar.subject() == null ? "-" : grammar.subject().key()) + " " + grammar.rel() + " "
							+ (grammar.object() == null ? "-" : grammar.object().text()) + "); knows " + memory.size() + " facts");
					say(w);
					return null;
				}
			}
			if (straight == null && r.intent().equals("chat") && xen.mod.talk.Voice.hard(heard)) {   // why, what next, how to make, which is better...: what it knows, its own words
				String reply = voice.reply(from == null ? "you" : from.getName().getString(), said, null, true);
				journal("thinks", "own words: " + voice.lastAct + " (heard: " + voice.lastHeard + ")");
				if (reply != null && !reply.isBlank() && voice.lastAct.startsWith("answer")) {
					say(reply);
					return null;
				}
			}
			if (straight == null && r.intent().equals("chat") && !(ownWords && xen.mod.talk.Voice.social(heard))) straight = smallTalk.answer(from, words);   // hellos, jokes, "do you like me"...
			if (straight != null) {
				say(straight);
				return null;
			}
			if (ownWords) {
				String simple = xen.mod.talk.Chat.plainly(notes(), said);
				String grounded = heard.question && !heard.likeQ && xen.mod.talk.Chat.asksAboutThing(said) && !simple.equals(xen.mod.talk.Chat.NOT_UNDERSTOOD) ? simple : null;   // (a question about a thing: what it knows; an opinion: its own words)
				String reply = voice.reply(from == null ? "you" : from.getName().getString(), said, grounded, true);
				journal("thinks", "own words: " + voice.lastAct + " (heard: " + voice.lastHeard + ")");
				if (grounded == null && mod.config.xentalk && xen.mod.talk.XenTalk.get() != null && said != null) {   // its own talk model: a moment later, like typing
					String facts = xen.mod.talk.XenTalk.facts(me, who, from == null ? 0 : trust(from.getUUID()), said);
					xen.mod.talk.XenTalk.ask(facts, own -> server.execute(() -> {
						if (player == null) return;
						if (own != null) journal("thinks", "xentalk: " + own);
						say(own != null ? own : reply);
					}));
					return null;
				}
				say(reply);
				return null;
			}
			String before = talk.prevLine == null || saidAt - talk.at > 20 * 60 * 3 ? ""
					: "Just before, " + who + " said \"" + clip(talk.prevLine, 90) + "\"" + (talk.prevReply == null ? "" : " and you answered \"" + clip(talk.prevReply, 90) + "\"") + ". ";
			return before + notes();                                  // just talk: the chat answers (knowing what was just said)
		}
		if (!refused && r.intent().equals("build") && plan != null && plan.startsWith("You will") && builder.busy()) goals.adoptBuild();   // (it said yes: the build is its goal now)
		if (!refused) lastThought = "Asked by " + who + ": " + plan;
		say(voice.ack(xen.mod.talk.Chat.plainly(notes() + " Plan: " + plan, "")));   // what it will do (or why not), right away, in its own tone
		return null;
	}

	/** Xen 2.0 beta 6: what it was told and kept as facts ("cats like fish", "the village is at ..."), see {@link xen.mod.talk.Words}. */
	final xen.mod.talk.Words.Memory memory = new xen.mod.talk.Words.Memory();

	/** Its words' view of the world, talking with someone: its memory, and what the game itself knows. */
	xen.mod.talk.Words.Context wordsWith(String who) {
		return new xen.mod.talk.Words.Context() {
			public String self() {
				return name;
			}

			public String speaker() {
				return who;
			}

			public xen.mod.talk.Words.Memory memory() {
				return memory;
			}

			public String game(String subject, String rel) {
				return gameKnows(subject, rel);
			}

			public java.util.Random random() {
				return random;
			}

			public long now() {
				return player == null ? 0 : player.level().getGameTime();
			}
		};
	}

	/** What the game itself knows about a thing, for a question (what it drops, what it is, where a place it knows is), as the rest of a sentence; null if nothing. */
	String gameKnows(String subject, String rel) {
		if (player == null) return null;
		String id = subject.replace(' ', '_');
		return switch (rel) {
			case "drop", "give" -> Facts.dropsSaid(id, player);
			case "be" -> {
				String w = Facts.what(id);
				yield w != null ? w : switch (id) {                               // (a kind of tool: "pickaxe" is wooden_pickaxe, stone_pickaxe...)
					case "pickaxe", "axe", "shovel", "hoe", "shears", "fishing_rod" -> "a tool";
					case "sword", "bow", "crossbow", "trident", "mace", "spear" -> "a weapon";
					case "helmet", "chestplate", "leggings", "boots" -> "armor";
					default -> null;
				};
			}
			case "at" -> {
				BlockPos p = places.get(subject);
				if (p == null) p = places.nearest(subject);
				yield p == null ? null : "at " + p.getX() + " " + p.getY() + " " + p.getZ();
			}
			default -> null;
		};
	}

	/** Something said about a thing (not to it, not about it or them): told, not a request ("cows drop leather", "diamonds are at y -58"). */
	private boolean aboutAThing(xen.mod.talk.Words.Meaning g) {
		if (g.subject() == null || !(g.act() == xen.mod.talk.Words.Act.TELL || g.act() == xen.mod.talk.Words.Act.ASK || g.act() == xen.mod.talk.Words.Act.ASK_WH)) return false;
		String k = g.subject().key();
		return !java.util.Set.of("you", "i", "we", "me", "us", "it", "this", "that").contains(k) && !k.equals(name.toLowerCase(java.util.Locale.ROOT));
	}

	/** Talking on its own: remarks, questions and chats with other Xens. */
	final Talker talker = new Talker(this);
	/** Something it did lately, in words ("made a stone pickaxe"), and when: it may mention it. */
	private String did = "";
	private long didAt = -1_000_000;

	void did(String what) {
		did = what;
		didAt = player == null ? 0 : player.level().getGameTime();
	}

	/** Its talk model: what kind of line a player said, and a player's answer, true to it (see {@link xen.mod.talk.Banter}). */
	final xen.mod.talk.Banter banter = new xen.mod.talk.Banter(new BanterSelf(), new java.util.Random());

	/** What its talk knows of it, all true, all now. */
	private final class BanterSelf implements xen.mod.talk.Banter.Self {
		@Override public String name() {
			return name;
		}

		@Override public String tone() {
			return personality.tone == null ? "calm" : personality.tone;
		}

		@Override public String doing() {
			if (player == null) return "nothing";
			String d = !chores.doing.isEmpty() && chores.busy() ? chores.doing : goals.instant;
			if (d == null || d.isEmpty() || d.startsWith("thinking about")) return chores.busy() || goals.option > xen.mod.core.Mind.REST ? "working on stuff" : "just chilling";
			d = Talker.mine(xen.mod.talk.Chat.firstPerson(d)).replaceAll("\\s*\\([^)]*\\)", "").replaceAll(",\\s*\\d+ of \\d+.*$", "").trim();
			int comma = d.indexOf(", ");
			if (comma > 0 && d.substring(0, comma).split(" ")[0].endsWith("ing")) d = d.substring(0, comma);
			if (!d.split(" ")[0].endsWith("ing")) return chores.busy() ? "working on stuff" : "just chilling";
			return d.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + d.substring(1);
		}

		@Override public String mood() {
			if (player == null) return "ok";
			if (player.getHealth() < 8) return "kinda hurt tbh";
			if (player.getFoodData().getFoodLevel() < 7) return "hungry lol";
			return switch (emotions.mood()) {
				case "terrified" -> "scared ngl";
				case "afraid" -> "kinda scared";
				case "uneasy" -> "a bit on edge";
				case "pleased" -> "pretty good";
				case "bored" -> "bored lol";
				case "curious" -> "good";
				case "hurt" -> "kinda hurt";
				default -> "good";
			};
		}

		@Override public int count(String what) {
			if (player == null) return 0;
			var it = items();
			return switch (what) {
				case "iron" -> it.getOrDefault("raw_iron", 0) + it.getOrDefault("iron_ingot", 0);
				case "gold" -> it.getOrDefault("raw_gold", 0) + it.getOrDefault("gold_ingot", 0);
				case "wood" -> it.getOrDefault("log", 0) + MindSense.count(Companion.this, n -> n.endsWith("_planks"));
				case "stone" -> it.getOrDefault("cobblestone", 0);
				default -> it.getOrDefault(what, 0);
			};
		}

		@Override public String pick() {
			if (player == null) return "";
			return switch (crafter.pickTier()) {
				case 1 -> "wooden pickaxe";
				case 2 -> "stone pickaxe";
				case 3 -> "iron pickaxe";
				case 4 -> "diamond pickaxe";
				case 5 -> "netherite pickaxe";
				default -> "";
			};
		}

		@Override public boolean night() {
			return player != null && player.level().isDarkOutside();
		}

		@Override public boolean hurt() {
			return player != null && player.getHealth() < 12;
		}

		@Override public boolean hungry() {
			return player != null && player.getFoodData().getFoodLevel() < 10;
		}

		@Override public boolean busy() {
			return chores.busy() || builder.busy() || fightingNow();
		}

		@Override public boolean home() {
			return goals.home != null;
		}

		@Override public boolean underground() {
			return player != null && !player.level().canSeeSky(player.blockPosition()) && player.getY() < 50;
		}

		@Override public String death() {
			if (diedOf.isEmpty()) return "";
			String d = diedOf.replace("in fire", "fire").replace("on fire", "fire");
			return d.matches("(zombie|skeleton|creeper|spider|drowned|witch|enderman|slime|pillager|husk|stray|phantom|piglin|blaze|wolf|bee|iron golem|cave spider|zombie villager).*")
					? "a " + d : d.equals("fall") ? "fall damage" : d.contains("lava") ? "lava" : d.contains("drown") ? "drowning" : d;
		}

		@Override public String place() {
			if (player == null) return "";
			BlockPos here = player.blockPosition(), best = null;
			String kind = null;
			for (var e : structures.found.entrySet()) {
				if (e.getKey().equals("house") || e.getKey().equals("old ruins")) continue;
				double dd = Math.sqrt(e.getValue().distSqr(here));
				if (dd < 20 || dd > 800) continue;
				if (best == null || dd < Math.sqrt(best.distSqr(here))) {
					best = e.getValue();
					kind = e.getKey();
				}
			}
			if (best == null) return "";
			long dist = Math.round(Math.sqrt(best.distSqr(here)) / 10.0) * 10;
			return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind + " about " + dist + " blocks " + Ideas.direction(here, best);
		}

		@Override public String dream() {
			return goals.dream == null ? "" : goals.dream.what;
		}

		@Override public String friend() {
			for (Companion o : mod.companions) {
				if (o == Companion.this || o.player() == null) continue;
				if (trust(o.player().getUUID()) >= 0.4f) return o.name;
			}
			return "";
		}

		@Override public String biome() {
			return player == null ? "" : places.biome().replace('_', ' ');
		}

		@Override public String did() {
			return player != null && player.level().getGameTime() - didAt < 20 * 60 * 20 ? did : "";
		}

		@Override public int day() {
			return player == null ? 0 : (int) (player.level().getGameTime() / 24000) + 1;
		}

		@Override public String where() {
			return player == null ? "" : player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ();
		}

		@Override public String fav(String kind) {
			int h = Math.floorMod(name.hashCode(), 7);
			return switch (kind) {
				case "block" -> new String[] {"spruce planks", "deepslate bricks", "moss", "copper", "cherry wood", "glass", "bookshelves"}[h];
				case "food" -> new String[] {"steak", "bread", "golden carrots", "cooked salmon", "pumpkin pie", "baked potatoes", "cookies"}[h];
				case "mob" -> new String[] {"axolotls", "foxes", "wolves", "cats", "allays", "sniffers", "bees"}[h];
				case "color" -> new String[] {"blue", "green", "red", "purple", "orange", "cyan", "yellow"}[h];
				case "biome" -> new String[] {"cherry groves", "taigas", "mushroom fields", "jungles", "badlands", "snowy plains", "meadows"}[h];
				case "weapon" -> new String[] {"a sword", "an axe", "a sword", "a mace", "a bow", "a trident", "a crossbow"}[h];
				default -> new String[] {"mining", "exploring", "building", "farming", "caving", "fighting mobs", "finding loot"}[h];
			};
		}
	}

	/** Its own words: it reads what's said, picks what kind of reply fits it, builds the sentence (see {@link xen.mod.talk.Voice}). */
	/** What it knows of itself, in words (what it's doing, why, what it saw): for its voice and its chats. */
	final xen.mod.talk.Voice.Self me = new VoiceSelf();
	final xen.mod.talk.Voice voice = new xen.mod.talk.Voice(me, System.nanoTime());

	/** What its voice knows of it: its nature, how it feels, what it does and wants, and what it thinks of things. */
	private final class VoiceSelf implements xen.mod.talk.Voice.Self {
		@Override public String name() {
			return name;
		}

		@Override public String tone() {
			return personality.tone == null ? "calm" : personality.tone;
		}

		@Override public String temper() {
			return personality.temper;
		}

		@Override public float humor() {
			return personality.hidden(Personality.HUMOR);
		}

		@Override public float chattiness() {
			return personality.chattiness;
		}

		@Override public float curiosity() {
			return personality.curiosity;
		}

		@Override public float kindness() {
			return personality.kindness;
		}

		@Override public float bravery() {
			return personality.bravery;
		}

		@Override public float trust(String who) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(who);
			return p == null ? 0 : Companion.this.trust(p.getUUID());
		}

		@Override public String mood() {
			return emotions.mood();
		}

		@Override public boolean hurt() {
			return player != null && player.getHealth() < 10;
		}

		@Override public boolean hungry() {
			return player != null && player.getFoodData().getFoodLevel() < 8;
		}

		@Override public boolean busy() {
			return chores.busy() || builder.busy() || goals.option >= 0 && goals.option != xen.mod.core.Mind.REST;
		}

		@Override public String doing() {
			String d = !goals.instant.isEmpty() ? goals.instant : chores.busy() ? chores.doing : "";
			if (d.isEmpty() && goals.option >= 0 && goals.option != xen.mod.core.Mind.REST) d = "going to " + xen.mod.core.Mind.SAYS[goals.option];
			d = d.replaceAll("[,(].*$", "").replaceAll("\\bits\\b", "my").replaceAll("\\bitself\\b", "myself").replaceAll("\\b(where|what|when) it\\b", "$1 I").trim();
			if (d.startsWith("no ") || d.startsWith("nothing")) d = "looking around";          // ("no animals in sight": what it's doing is looking)
			return d.equals("looking around") || d.equals("exploring") && goals.option < 0 ? "" : d;
		}

		@Override public String dream() {
			return goals.dream == null ? "" : goals.dream.what;
		}

		@Override public String saw() {
			if (vision.village) return "a village";                                   // (what it sees right now first)
			if (vision.caveMouth) return "a cave mouth";
			if (vision.ravine) return "a ravine";
			if (vision.structure) return "some buildings";
			Places.Place latest = null;
			for (Places.Place p : places.all()) {
				String n = p.name();
				if (n.startsWith("home") || n.startsWith("mine") || n.startsWith("farm") || n.startsWith("bed") || n.startsWith("chest")) continue;
				if (latest == null || p.day() >= latest.day()) latest = p;
			}
			if (latest == null) return "";
			String n = latest.name().replaceAll("\\s*\\d+$", "");
			if (n.endsWith("s")) return "some " + n;                              // ("some old ruins", not "an old ruins")
			return ("aeiou".indexOf(n.charAt(0)) >= 0 ? "an " : "a ") + n;
		}

		@Override public float like(xen.mod.talk.Lexicon.Topic t) {
			Personality p = personality;
			float v = t.like();
			switch (t.cat()) {
				case "ore" -> v += 0.4f * p.sin(xen.mod.core.Sins.GREED) + 0.2f * p.money;
				case "hostile" -> v += 0.5f * (p.bravery - 0.5f) + 0.3f * p.sin(xen.mod.core.Sins.WRATH) + (p.aggressive() ? 0.2f : 0);
				case "animal" -> v += 0.5f * (p.kindness - 0.5f);
				case "build" -> v += 0.4f * (skills.get(Skills.BUILD) - 0.5f) + 0.2f * p.sin(xen.mod.core.Sins.PRIDE);
				case "food" -> v += 0.4f * p.sin(xen.mod.core.Sins.GLUTTONY);
				case "place" -> v += 0.4f * (p.curiosity - 0.5f) + 0.2f * p.sin(xen.mod.core.Sins.LUST);
				case "people" -> v += 0.3f * (p.kindness - 0.5f) - (p.loner ? 0.3f : 0);
				case "time" -> v += t.key().equals("night") ? 0.4f * (p.bravery - 0.5f) : 0;
				case "activity" -> v += switch (t.key()) {
					case "mining" -> 0.4f * (skills.get(Skills.MINE) - 0.5f) + 0.2f * p.sin(xen.mod.core.Sins.GREED);
					case "building" -> 0.4f * (skills.get(Skills.BUILD) - 0.5f);
					case "exploring" -> 0.5f * (p.curiosity - 0.5f);
					case "fighting", "pvp" -> 0.4f * p.sin(xen.mod.core.Sins.WRATH) + 0.3f * (p.bravery - 0.5f) + (p.aggressive() ? 0.3f : 0);
					case "farming" -> 0.4f * (skills.get(Skills.FARM) - 0.5f) + 0.2f * p.sin(xen.mod.core.Sins.GLUTTONY);
					case "trading" -> 0.4f * p.money;
					default -> 0f;
				};
				default -> { }
			}
			if (t.cat().equals("activity") || t.cat().equals("build") || t.cat().equals("ore")) v -= 0.3f * p.sin(xen.mod.core.Sins.SLOTH) * (t.cat().equals("ore") ? 0.3f : 1);
			int h = (name + ":" + t.key()).hashCode();                        // its own tastes: a favourite here, a pet hate there
			v += ((h & 0xff) / 255f - 0.5f) * 0.5f;
			return Math.max(-1, Math.min(1, v));
		}

		/** Why it chose each thing, when its mind chose it (not the plan). */
		private static final String[] WHY = {"I needed a break", "you always need wood", "stone makes better tools", "better gear keeps me alive",
				"I'll need food", "I'm hungry", "nights are dangerous", "sleep skips the night", "everyone needs a home", "a farm means food forever",
				"the good stuff is underground", "raw iron is no use till it's smelted", "my pockets are full", "I want to see what's out there",
				"trading gets me things I can't make", "I like the company", "friends help friends", "someone has to keep watch", "they had it coming",
				"it's too dangerous here", "I want an adventure", "enchanted gear is way better"};
		private static final String[] DID = {"took a breather", "got some wood", "got some stone", "made better gear", "found food", "ate",
				"made a shelter", "slept", "worked on a house", "worked on a farm", "went mining", "smelted my ores", "put my things away", "explored",
				"traded", "stayed with a friend", "helped out", "kept watch", "fought", "ran from a fight", "went on an adventure", "enchanted my gear"};

		@Override public String whyPlaced() {
			if (hands.placedAt == null || player.level().getGameTime() - hands.placedWhen > 20 * 120) return "";
			String i = hands.placedFor;                                           // what it was doing when it put the block down: that's why
			return i.contains("water") ? "the water was coming in and I'd have drowned or lost my tunnel"
					: i.contains("lava") ? "there was lava right there"
					: i.contains("towering") || i.contains("pillar") ? "I needed to get up there and there was no other way up"
					: i.contains("bridg") ? "there was a gap I had to get over"
					: i.contains("shelter") || i.contains("hut") || i.contains("night") || i.contains("dugout") || i.contains("fort") ? "night was coming and I needed walls round me"
					: i.contains("mine entrance") ? "it's the door to my mine, so mobs stay out"
					: i.contains("craft") || i.contains("table") || i.contains("furnace") ? "I needed it to make something"
					: i.contains("torch") ? "it was dark, a torch keeps mobs from spawning"
					: i.contains("build") || i.contains("house") ? "I'm building"
					: i.isEmpty() ? "" : "I was " + i;
		}

		@Override public String why() {
			if (chores.busy() && !chores.own) return askedBy != null ? askedBy.getName().getString() + " asked me to" : "I was asked to";
			String i = goals.instant;                                             // what an instinct has it doing right now
			String instinct = i.contains("shore") || i.contains("swimming") ? "I'm too far out in the water"
					: i.contains("air") ? "I need air" : i.startsWith("eating") ? "I'm hungry"
					: i.startsWith("fighting") ? "it came at me" : i.contains("running") || i.contains("fleeing") || i.contains("getting away") ? "it's too dangerous here"
					: i.contains("hiding") || i.contains("dugout") || i.contains("fort") ? "it's dark and the mobs are out"
					: i.startsWith("staying with") || i.startsWith("following") ? "I like the company"
					: i.contains("sheep") ? "I need wool for a bed" : i.startsWith("going back for its things") ? "I want my things back" : "";
			if (!instinct.isEmpty()) return instinct;
			if (!goals.optionSays.isEmpty()) return goals.optionSays;            // (Xen 2.0: why it chose it, in words, from the decision itself)
			String how = goals.optionHow == null ? "" : goals.optionHow;
			if (how.startsWith("the plan: ") || how.equals("a duel")) return reason(how.replace("the plan: ", "").split("; ")[0]);
			if (goals.option >= 0 && goals.option < WHY.length) return WHY[goals.option];
			var m = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(goals.instant);
			if (m.find()) return reason(m.group(1));
			if (goals.current != null) return "I want to " + goals.current.what + ", " + xen.mod.talk.Chat.firstPerson(goals.current.why);   // ("I want to go mining, I want iron and diamonds for better tools")
			if (!walker.stuckWhy().isEmpty()) return "I'm stuck, " + walker.stuckWhy();
			return "";
		}

		/** A reason in its words: "night: crafting inside" is "it's dark out, so I'm crafting inside". */
		private String reason(String how) {
			String h = how.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\bitself\\b", "myself").replaceAll("\\bits\\b", "my").trim();
			if (h.startsWith("night: ")) {
				String x = h.substring(7);
				return x.equals("bed") ? "it's night, time for bed" : "it's dark out, so " + (x.matches("[a-z]+ing\\b.*") ? "I'm " : "") + x;
			}
			if (h.startsWith("dusk: ")) return "it's getting dark and I need " + h.substring(6);
			if (h.startsWith("hurt")) return "I'm hurt";
			if (h.equals("hungry") || h.startsWith("low on")) return "I'm " + h;
			if (h.equals("a rival")) return "there's a rival around";
			if (h.equals("a duel")) return "we're having a duel";
			if (h.startsWith("iron to smelt")) return "I have iron to smelt";
			if (h.startsWith("off to")) return "I'm " + h;
			return "I need " + h;
		}

		@Override public String next() {
			int tier = crafter.pickTier();
			var items = items();
			if (tier == 0) return "get wood for a pickaxe";
			if (tier == 1) return "get stone for better tools";
			if (!hasBed() && items.keySet().stream().noneMatch(k -> k.endsWith("_bed"))) return "get some wool for a bed";
			if (tier == 2) return "go down the mine for iron";
			if (goals.home == null) return "build a house of my own";
			if (player != null && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()) return "get iron for armor";
			if (tier < 4 || items.getOrDefault("diamond", 0) < 3) return "find diamonds";
			return "go to the Nether for netherite";
		}

		@Override public String bestFriend() {
			String best = "";
			float most = 0.3f;
			for (var e : trust.entrySet()) {
				if (e.getValue() <= most) continue;
				String who = null;
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				if (p != null) who = p.getName().getString();
				for (Companion o : mod.companions) if (o != Companion.this && o.player != null && o.player.getUUID().equals(e.getKey())) who = o.name;
				if (who == null || who.equals(name)) continue;
				most = e.getValue();
				best = who;
			}
			if (best.isEmpty() && owner != null && ownerName != null && !ownerName.isEmpty()) best = ownerName;
			return best;
		}

		@Override public String lately() {
			java.util.List<String> did = new java.util.ArrayList<>();
			var it = goals.done.descendingIterator();
			while (it.hasNext() && did.size() < 3) {
				int o = it.next();
				if (o >= 0 && o < DID.length) did.add(DID[o]);
			}
			if (did.isEmpty()) return "";
			if (did.size() == 1) return did.get(0);
			return String.join(", ", did.subList(0, did.size() - 1)) + " and " + did.get(did.size() - 1);
		}

		@Override public String has() {
			if (player == null) return "";
			var it = items();
			int tier = crafter.pickTier();
			java.util.List<String> out = new java.util.ArrayList<>();
			out.add(new String[] {"no pickaxe", "a wooden pickaxe", "a stone pickaxe", "an iron pickaxe", "a diamond pickaxe", "a netherite pickaxe"}[Math.max(0, Math.min(5, tier))]);
			int iron = it.getOrDefault("raw_iron", 0) + it.getOrDefault("iron_ingot", 0), coal = it.getOrDefault("coal", 0) + it.getOrDefault("charcoal", 0);
			String[][] kinds = {{"log", "logs"}, {"cobblestone", "cobblestone"}, {"coal", "coal"}, {"iron", "iron"}, {"diamond", "diamonds"}};
			int[] n = {it.getOrDefault("log", 0), it.getOrDefault("cobblestone", 0), coal, iron, it.getOrDefault("diamond", 0)};
			for (int i = 0; i < n.length; i++) if (n[i] > 0) out.add(n[i] + " " + (n[i] == 1 && !kinds[i][0].equals("cobblestone") ? kinds[i][0] : kinds[i][1]));
			int food = it.getOrDefault("food", 0);
			out.add(food == 0 ? "no food" : food + " food");
			if (hasBed()) out.add("a bed");
			return String.join(", ", out);
		}

		@Override public float danger() {
			if (player == null) return 0;
			int n = player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, player.getBoundingBox().inflate(16), m -> m.isAlive()).size();
			return Math.min(1, 0.3f * n + (player.getHealth() < 8 ? 0.3f : 0));
		}

		@Override public boolean night() {
			return player != null && player.level().isDarkOutside();
		}
	}
	/** Learning by watching: moves it copies from players when they work out. */
	final Mimic mimic = new Mimic(this);
	/** The unpredictable side: dancing, showing off, surprises. */
	final Antics antics = new Antics(this);
	final Rider rider = new Rider(this);
	final Uses uses = new Uses(this);
	final Habits habits = new Habits(this);
	final Sharing sharing = new Sharing(this);
	/** One thread through what it does and says: what it told and promised others, what it agreed to do with them. */
	final Intent intent = new Intent(this);
	/** The rules it writes itself from what worked and what didn't (and getting itself unstuck). */
	final Rules rules = new Rules(this);
	/** What it sees, as a picture (and what it makes of it). */
	final Vision vision = new Vision(this);
	final Reactions reactions = new Reactions(this);

	// ------------------------------------------------------------------------ standing about
	/** Where it has been standing, since when; and where it's off to when it had enough of it. */
	private Vec3 idleFrom;
	private long idleSince, movingOnUntil;
	private Vec3 movingOnTo;
	/** How often it caught itself standing about (for the journal and the tests). */
	int standings;

	/**
	 * A player doesn't stand in one spot for a minute doing nothing. If it has been within three blocks of the same
	 * place for a minute (not asleep, not staying in at night, not building, farming, fishing, crafting or told to
	 * stay), it drops what it was stuck on and walks off somewhere new for a bit, then its mind picks again.
	 */
	private Action standingAbout() {
		if (player == null || mode != Mode.FREE || inArena) return null;
		long now = player.level().getGameTime();
		if (movingOnTo != null) {
			if (now < movingOnUntil && movingOnTo.distanceTo(player.position()) > 2) return walkTo(movingOnTo);
			movingOnTo = null;
		}
		boolean legit = player.isSleeping() || player.level().isDarkOutside() && (goals.option == xen.mod.core.Mind.REST || goals.option == xen.mod.core.Mind.SLEEP)
				|| builder.busy() || farmer.on || fisher.on || crafter.hasOrder() || storage.busy() || player.isInWater() || adventure.on && dragon.next() != null
				|| chores.busy() && (chores.kind == Chores.Kind.HIDE || chores.kind == Chores.Kind.SHELTER || chores.kind == Chores.Kind.SMELT) || rider.busy();   // (in its shelter for the night, at its furnace, riding)
		Vec3 here = player.position();
		if (legit || idleFrom == null || idleFrom.distanceTo(here) > 3) {
			idleFrom = here;
			idleSince = now;
			return null;
		}
		boolean doingNothing = !chores.busy() && (goals.instant.isEmpty() || goals.instant.startsWith("looking around") || goals.instant.equals("exploring"));
		if (now - idleSince < (doingNothing ? 400 : 1200)) return null;      // (twenty seconds of nothing is a lot for a player; a stuck chore gets a minute)
		standings++;
		String was = goals.instant.isEmpty() ? chores.busy() ? "a chore" : "nothing" : goals.instant;
		journal("does", "stood about " + (doingNothing ? "doing nothing" : "for a minute") + " (" + was + "): moves on");
		chores.cancel();
		goals.drop();
		walker.stop();
		idleFrom = null;
		chatter(personality.sin(xen.mod.core.Sins.SLOTH) > 0.6f ? pick3("Fine, fine. I'll do something.", "Ugh, okay, moving.", "Alright, alright.")
				: pick3("Enough standing around.", "Right, let's do something.", "Okay, what next?"), false);
		String out = goals.exploreFor(20 * 45);                              // off to see new ground a while (then its own plans again)
		if (out.startsWith("You will")) return null;
		double a = random().nextDouble() * Math.PI * 2;
		movingOnTo = here.add(Math.cos(a) * 16, 0, Math.sin(a) * 16);
		movingOnUntil = now + 300;
		return walkTo(movingOnTo);
	}
	/** Its owner's own rules (the script setting). */
	final Script script = new Script(this);

	/**
	 * The custom instructions that are for it: every line, except lines that start with another Xen's name and a colon
	 * ("Pip: you love cats" is only for Pip).
	 */
	String instructions() {
		String all = mod.config.instructions == null ? "" : mod.config.instructions;
		for (String m : memories) all = all + "\n" + m;                 // and what people asked it to remember
		if (all.isBlank()) return "";
		StringBuilder sb = new StringBuilder();
		for (String raw : all.split("\\r?\\n|\\|")) {                             // lines, or | between them (for /xen set)
			String line = raw.trim();
			if (line.isEmpty()) continue;
			int colon = line.indexOf(':');
			if (colon > 0 && colon < 17 && line.substring(0, colon).matches("[A-Za-z0-9_]{3,16}")) {
				String who = line.substring(0, colon);
				if (who.equalsIgnoreCase(name)) line = line.substring(colon + 1).trim();
				else if (mod.roster.has(who) || mod.companions.stream().anyMatch(o -> o.name.equalsIgnoreCase(who))) continue;
			}
			sb.append(sb.length() > 0 ? " " : "").append(line.endsWith(".") || line.endsWith("!") || line.endsWith("?") ? line : line + ".");
		}
		return sb.toString();
	}
	/** What people asked it to remember ("Steve told you: the base is by the river."), newest last. */
	final java.util.List<String> memories = new java.util.ArrayList<>();
	private static final java.util.regex.Pattern REMEMBER = java.util.regex.Pattern.compile("^(please |pls |can you |could you )?remember(,)? (that |this: |this )?(.+)$");
	private static final java.util.regex.Pattern RECALL = java.util.regex.Pattern.compile("\\b(what did i (tell|say to) you|what do you remember|what i told you)\\b");
	private static final java.util.regex.Pattern FORGET_ALL = java.util.regex.Pattern.compile("^forget (everything|what i (told you|said))\\b");
	/** Who it's talking with (so "what about you?" without its name is for it), and until when. */
	UUID talkingWith;
	long talkingUntil;

	/**
	 * What it means to say, in its own words (its voice words it: {@link xen.mod.talk.Voice#express}): the game gives the
	 * meaning, never the line. what: the thing itself in plain words (null: just the word for it, like a greeting).
	 */
	String words(xen.mod.talk.Voice.Say act, String what) {
		return words(act, what, null);
	}

	String words(xen.mod.talk.Voice.Say act, String what, String to) {
		String s = voice.express(act, what, to);
		return s.isEmpty() ? (what == null ? "" : what) : s;
	}

	String pick3(String a, String b, String c3) {
		int r = random.nextInt(3);
		return r == 0 ? a : r == 1 ? b : c3;
	}

	/** Someone said "watch me": it keeps its eyes on them for a while. */
	private UUID watchFor;
	private int watchUntil;

	/** Eyes on them for a while (ticks). */
	void lookAt(ServerPlayer p, int ticks) {
		watchFor = p.getUUID();
		watchUntil = player.tickCount + ticks;
	}

	boolean watchingYou(ServerPlayer p) {
		return p.getUUID().equals(watchFor) && watchUntil > player.tickCount;
	}
}
