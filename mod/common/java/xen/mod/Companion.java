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
	private long lastChatter;
	private boolean wasNight;
	public String lastThought = "";
	boolean pillaring;                         // this decision: jump and place a block below (learned as a jump)
	boolean acted;                             // this decision: a chore already used its hands (placed, hit, gave)
	final Chores chores = new Chores(this);
	private Vec3 walkedFrom;
	private int stuck;
	private int lifeTicks;
	private float lifeReward;
	/** Its nature (genes) and how it looks. */
	final Personality personality;
	final String skin;
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

	/** Is it in the world someone keeps loaded (a minion only lives there)? */
	boolean awake() {
		return player != null && (!minion || ((ServerLevel) player.level()).isPositionEntityTicking(player.blockPosition()));
	}

	void tick() {
		if (player == null) return;
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
		hands.tick();
		walker.tick();                                                  // on its way somewhere: the keys for the next step
		nether.tick();                                                  // portals: where it came from, gold in the Nether
		if (player.tickCount % 10 == 0) {
			places.tick();                                               // the way it walked, remembered
			totems();
			rumors.look();                                               // who's that? (a shock, a warning to pass on)
		}
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
		if (hurtNow && !inArena) {                                      // its tribe may come to guard it
			Tribe hurtTribe = tribe();
			if (hurtTribe != null) hurtTribe.attackedAt.put(this, player.level().getGameTime());
		}
		tickHealthBefore = tickHealth;
		tickHealth = player.getHealth();
		if (player.tickCount % 40 == 0) readSigns();                    // signs it can see: it reads them, like anyone
		if (player.tickCount % 40 == 20) wearArmor();
		mimic.watch();                                                  // what are the players it sees doing?
		solver.watch();                                                 // and how they get out of holes
		antics.watch();
		script.tick(hurtNow);                                           // its owner's own rules
		if (mimic.clutchTick()) return;                                 // falling: a water clutch, this very tick
		if (hurtNow && !inArena && player.getLastHurtByMob() instanceof ServerPlayer by && by != player
				&& player.tickCount - player.getLastHurtByMobTimestamp() < 5) {
			hitBy(by);
		}
		if (hands.busy() && !((hurtNow || fighting) && hands.interruptible())) return;   // being hit cuts mining and walking short
		if (player.tickCount % 40 == 0) watched = watchedByAPlayer();
		int every = Math.max(1, mod.config.decisionTicks / 5) * (watched || inArena || hurtNow ? 1 : 3);   // far from any player: it thinks less often
		if (++decisions % every != 0 && !fighting) return;   // in a fight, every tick
		decide();
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
		return s.has(DataComponents.FOOD) ? "food"
				: n.equals("cobbled_deepslate") ? "cobblestone"
				: n.endsWith("_log") || WorldSenses.isNetherStem(n) ? "log"
				: n.equals("iron_ore") ? "raw_iron" : n.equals("gold_ore") ? "raw_gold" : n;
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
		lastHealth = player.getHealth();
		lastFood = player.getFoodData().getFoodLevel();
		lastItems = items;
		pillaring = acted = false;
		walker.touched = false;
		goals.instant = "";
		if (!inArena) goals.everyDecision();
		Action instinct = instinct();
		Brain.Thought thought = mod.brain.decide(next, emotions, mod.config.learn);
		Action sensible = instinct == null ? sensible(Action.values()[thought.action]) : null;
		if (sensible != null && sensible.ordinal() == thought.action) sensible = null;
		int action = instinct != null ? instinct.ordinal() : sensible != null ? sensible.ordinal() : thought.action;
		lastThought = instinct != null ? (chores.busy() ? "Doing what I was asked (" + chores.doing + "): " : "Instinct: ")
				+ (pillaring ? "climb up" : instinct.verb) + "." : sensible != null ? "Nothing worth doing there, so: " + sensible.verb + "."
				: thought.text;
		hands.glance = null;
		if (instinct == null && !inArena && !fighting) {                     // nothing to do this moment: what a person does then
			Action human = humanIdle(Action.values()[thought.action]);
			if (human.ordinal() != action || human == Action.IDLE) {
				action = human.ordinal();
				if (human == Action.IDLE) lastThought = idleThought;
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
	private boolean watched = true;

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
			case PLACE -> {
				return emotions.fear > 0.5f || player.isInWater() ? null : idle();
			}
			default -> {
				return null;
			}
		}
	}

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
		if (brain == Action.EAT && player.getFoodData().getFoodLevel() < 17 && items().getOrDefault("food", 0) > 0) return brain;
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
		}
		hands.glance = idleLook;
		return Action.IDLE;
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
				e -> e != player && e.isAlive() && !(e instanceof ServerPlayer) && player.hasLineOfSight(e));
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

	/** Companion instincts that come before the brain's own choice. */
	private Action instinct() {
		hands.watching = null;
		if (player.isInWater() && (player.isUnderWater() || player.getAirSupply() < player.getMaxAirSupply())) {
			goals.instant = "swimming up for air";
			return Action.JUMP;                                       // hold space to swim up, like a player
		}
		if (player.getFoodData().getFoodLevel() <= 10 && items().getOrDefault("food", 0) > 0 && player.getFoodData().needsFood()) {
			goals.instant = "eating";
			return Action.EAT;
		}
		Action fight = fightBack();
		if (fight != null) {
			if (antics.busy()) antics.next(true);                     // a fight ends the fun
			if (goals.instant.isEmpty()) goals.instant = "fighting the " + fightingWhat;
			return fight;
		}
		if (inArena) return Action.IDLE;                              // between duels it waits for the next one
		Action shocked = rumors.shockStep();                          // someone it killed, alive; the one they say nobody beats
		if (shocked != null) return shocked;
		Action creak = awayFromCreaking();                            // a creaking can't be hurt: it keeps away from it
		if (creak != null) return creak;
		Action end = dragon.next();                                    // in the End: the dragon fight
		if (end != null) return end;
		Tribe tribe = tribe();
		Action rally = tribe == null ? null : tribe.rally(this);       // its tribe is fighting someone: it comes to help
		if (rally != null) return rally;
		Action through = nether.followThrough();                       // its friend went through a portal: after them
		if (through != null) return through;
		Action fun = antics.next(false);                              // dancing, showing off
		if (fun != null) return fun;
		if (crafter.ready() && !farBehind()) {                        // tools first, like any new player
			Action craft = crafter.next();
			if (craft != null) return craft;
		}
		Action back = backForMyThings();                              // it died: its things are lying where it fell
		if (back != null) return back;
		Action pick = pickUpNearby();                                  // good things lying close by: it picks them up
		if (pick != null) return pick;
		Action bed = bedtime();                                       // night, and a bed at home: it sleeps, like a player
		if (bed != null) return bed;
		Action light = lightUp();                                     // in a dark cave or tunnel: a torch, like a player
		if (light != null) return light;
		if (chores.busy() && chores.own && mode == Mode.FOLLOW && !leaderWithin(LEASH)) {   // its friend is leaving: that comes first
			chores.cancel();
			goals.drop();
			goals.pause(20 * 20);                                         // (no new errand for a bit: it keeps up first)
			if (player.tickCount - saidComingAt > 400) {
				saidComingAt = player.tickCount;
				chatter("Coming!", true);
			}
		}
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
		Action drill = skills.step();                                  // training (sparring, practising its swings, parkour)
		if (drill != null) return drill;
		if (mode == Mode.FREE && mod.config.wants && !inArena && goals.think()) {   // free: what does it want?
			Action chore = chores.next();
			if (chore != null || chores.busy()) return chore;
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
		if (mode == Mode.FOLLOW && mod.config.wants && !inArena && !catchingUp && leaderWithin(NEARBY) && goals.think(true)) {
			Action chore = chores.next();
			if (chore != null || chores.busy()) return chore;
		}
		if (mode == Mode.FOLLOW && goals.useMind() && !catchingUp && leaderWithin(NEARBY)) {
			Action m = goals.mindStep();
			if (m != null) return m;
		}
		Vec3 goal = null;
		if (mode == Mode.FOLLOW && leader != null) {
			ServerPlayer o = server.getPlayerList().getPlayer(leader);
			if (o != null && o.level() == player.level()) {
				double d = o.position().distanceTo(player.position());
				if ((d > mod.config.followDistance || catchingUp && d > 2.5) && d < 96) goal = o.position();   // close up, then wait
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

	private BlockPos bedAt;
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
			float yes = m.personality.kindness + m.trust(player.getUUID()) + (m.personality.money > 0.6f ? -0.3f : 0);
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

	private Action bedtime() {
		var level = (ServerLevel) player.level();
		if (player.isSleeping()) {
			goals.instant = "sleeping";
			return Action.IDLE;
		}
		long time = Compat.timeOfDay(level) % 24000;
		boolean night = time >= 12600 && time <= 23400;
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
		goals.instant = "going to bed";
		if (player.getEyePosition().distanceTo(Vec3.atCenterOf(bedAt)) > player.blockInteractionRange() - 0.5) return walkTo(Vec3.atBottomCenterOf(bedAt));
		hands.stop();
		hands.use(bedAt);                                             // (monsters close by: it can't, like anyone)
		acted = true;
		if (player.isSleeping()) chatter(pick3("Good night!", "Time to sleep. Night night.", "Zzz..."), false);
		return Action.IDLE;
	}

	/** Where it died, with its things lying there, and until when they're there. */
	private BlockPos lostAt;
	private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lostDimension;
	private long lostUntil;
	private boolean saidGoingBack;

	/** Back for its things after dying, like a player (before they're gone, and not straight into what killed it). */
	private Action backForMyThings() {
		if (lostAt == null || player.level().dimension() != lostDimension || fighting) return null;
		long now = player.level().getGameTime();
		if (now > lostUntil || emotions.fear > 0.8f) {
			lostAt = null;
			saidGoingBack = false;
			return null;
		}
		if (!saidGoingBack) {
			saidGoingBack = true;
			chatter("My stuff! I'm going back for it.", true);
		}
		goals.instant = "going back for its things";
		if (Vec3.atCenterOf(lostAt).distanceTo(player.position()) > 3) return walkTo(Vec3.atBottomCenterOf(lostAt));
		for (var item : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(6), e -> e.isAlive())) {
			return walkTo(item.position());
		}
		lostAt = null;
		saidGoingBack = false;
		chatter("Got my things back!", true);
		return null;
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
			"paper", "experience_bottle", "shulker_shell", "netherite_scrap", "ancient_debris");
	private final Set<UUID> leftLying = new java.util.HashSet<>();
	private UUID goingFor;
	private long goingSince;

	private Action pickUpNearby() {
		if (fighting || mode == Mode.STAY && chores.busy()) return null;
		net.minecraft.world.entity.item.ItemEntity best = null;
		for (var e : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(8, 3, 8),
				x -> x.isAlive() && !leftLying.contains(x.getUUID()) && WORTH.contains(BuiltInRegistries.ITEM.getKey(x.getItem().getItem()).getPath()))) {
			if (!player.hasLineOfSight(e)) continue;
			if (best == null || e.distanceTo(player) < best.distanceTo(player)) best = e;
		}
		if (best == null) return null;
		long now = player.level().getGameTime();
		if (!best.getUUID().equals(goingFor)) {
			goingFor = best.getUUID();
			goingSince = now;
		} else if (now - goingSince > 160) {                            // can't get to it: leave it
			leftLying.add(best.getUUID());
			return null;
		}
		goals.instant = "picking up " + BuiltInRegistries.ITEM.getKey(best.getItem().getItem()).getPath().replace('_', ' ');
		return walkTo(best.position());
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
		if (times >= 3 && player.tickCount - stuckSaidAt > 600) {
			stuckSaidAt = player.tickCount;
			if (DEBUG) XenMod.LOG.info("[xen debug] {} is stuck on its way ({} times): {}", name, times, why);
		}
	}

	private int stuckSaidAt = -10000;

	/** Truces, giving up, and what it wants for peace (see {@link Diplomacy}). */
	final Diplomacy diplomacy = new Diplomacy(this);

	/** When it's stuck: a way out it learns (see {@link Solver}). */
	final Solver solver = new Solver(this);

	/**
	 * Walk towards a place: its legs find the way (path assist); when they can't, or keep failing, or it gets no
	 * closer, the solver picks a way out; and if all that's switched off, the old way: straight at it, digging.
	 */
	Action walkTo(Vec3 goal) {
		if (voyager.flying() || voyager.shouldFly(goal)) {                 // far, under the sky, with an elytra: it flies there
			Action f = voyager.flyTo(goal);
			if (f != null) return f;
		}
		if (mod.config.pathAssist) {
			if (solver.active()) {
				Action a = solver.next(goal);
				if (a != null) return a;
			}
			Action w = walker.go(goal);
			if (w != null && !walker.stuck()) return w;
			String why = w == null ? "no way it knows of" : walker.lastProblem.isEmpty() ? "getting no closer" : walker.lastProblem;
			if (solver.start(goal, why)) {
				walker.stop();
				Action a = solver.next(goal);
				if (a != null) return a;
			}
			if (w != null) return w;
		}
		return digToward(goal);
	}

	/** The old way on foot: straight at it through what it knows, digging through, pillaring up. Null if lava is in the way. */
	Action digToward(Vec3 goal) {
		double dx = goal.x - player.getX(), dz = goal.z - player.getZ();
		ServerLevel level = (ServerLevel) player.level();
		BlockPos feet = player.blockPosition();
		boolean below = goal.y - player.getY() >= 1.5;
		if (below && Math.hypot(dx, dz) < 3 && player.onGround() && hands.startPillar()) {   // straight up: pillar
			pillaring = true;
			return Action.JUMP;
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
		if (below && player.onGround() && hands.startPillar()) {
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
			return null;
		}
		fightingWhat = foe.getName().getString();
		if (!(foe instanceof ServerPlayer)) fightingWhat = fightingWhat.toLowerCase(java.util.Locale.ROOT);
		if (!(foe instanceof ServerPlayer sp && skills.sparringWith(sp.getUUID()))) diplomacy.during(foe);   // it may talk (truce, give up); not in a spar
		return fighter.next(foe, hurtNow);
	}

	private String fightingWhat = "";

	final Fighter fighter = new Fighter(this);
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
	/** The little human things: gestures, forgiveness, gifts, a dog, a hobby, milestones. */
	final Life life = new Life(this);
	/** It lost a fight lately (it may want to train). */
	boolean lostFight;
	/** How much it trusts each player (and Xen) it has met, -1 to 1: kind words and fair deals up, hits and cheating down. */
	final Map<UUID, Float> trust = new HashMap<>();

	float trust(UUID who) {
		if (who == null) return 0;
		if (who.equals(owner)) return 1f;
		return trust.getOrDefault(who, known.contains(who) ? 0.3f : 0.2f);
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
	 * A player hit it. With a weapon, it's an attack: it trusts them a lot less and may fight back. With an empty hand
	 * (or a flower, a block) it's a poke, the way players get someone's attention: it turns to them and asks what's up,
	 * and barely minds. Poking on and on is an attack too.
	 */
	private void hitBy(ServerPlayer by) {
		long now = player.level().getGameTime();
		UUID u = by.getUUID();
		if (skills.sparringWith(u)) return;                            // a sparring partner: that's the game
		Tribe home = tribe();
		if (home != null && by instanceof XenPlayer xp && xp.companion != null && isWeapon(by.getMainHandItem())) home.laws.hit(xp.companion, this);
		if (isWeapon(by.getMainHandItem()) || by.getAttackStrengthScale(0) > 0 && player.getHealth() < tickHealthBefore - 3) {
			armedHitAt.put(u, now);
			trust(u, -0.3f);                                           // it remembers who hit it
			Tribe t = tribe();
			if (t != null) t.alarm(this, by);                          // and its tribe does too
			return;
		}
		var q = pokes.computeIfAbsent(u, k -> new java.util.ArrayDeque<>());
		q.addLast(now);
		while (!q.isEmpty() && now - q.peekFirst() > 200) q.removeFirst();
		if (q.size() >= 6) {                                           // on and on: that's not asking for attention any more
			armedHitAt.put(u, now);
			trust(u, -0.3f);
			q.clear();
			say(pick3("Stop that!", "Okay, that's enough!", "Quit it!"));
			return;
		}
		trust(u, -0.01f);
		known.add(u);
		hands.watching = by;
		watchFor = u;
		watchUntil = player.tickCount + 100;                           // it looks at them
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

	/** Asked to join a team: it decides, by how much it trusts whoever asks and how loyal it is to its own. Null if not asked. */
	private String teamAsked(ServerPlayer from, String words) {
		var m = TEAM_JOIN.matcher(words.toLowerCase(java.util.Locale.ROOT));
		if (!m.find()) return null;
		String color = m.group(5);
		if (color == null) {
			var t = server.getScoreboard().getPlayersTeam(from.getScoreboardName());
			if (t == null || !t.getName().startsWith("xen_")) return "You don't have a team for me to join. Make one first (/team), or tell me a color.";
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
				player.getBoundingBox().inflate(5), x -> x.isAlive() && x.getSwellDir() > 0)) {
			if (player.distanceTo(creeper) < 5) return creeper;           // hissing close by: it hears that (and runs)
		}
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
		if (best != null || mod.config.pvp.equals("off")) return best;
		ServerPlayer o = owner == null ? null : server.getPlayerList().getPlayer(owner);
		long now = player.level().getGameTime();
		boolean own = mod.config.pvp.equals("own");
		for (LivingEntity victim : new LivingEntity[] {player, o}) {               // who hurt it, or its owner, just now
			if (victim == null || victim.level() != player.level()) continue;
			LivingEntity a = victim.getLastHurtByMob();
			if (a == null || !a.isAlive() || a == player || a == o) continue;             // (a teammate who hits it with a weapon too: its call)
			if (diplomacy.atPeace(a)) continue;                                   // a truce (unless they broke it)
			if (a instanceof ServerPlayer sp0 && skills.sparringWith(sp0.getUUID())) continue;   // (sparring: that's the game)
			if (a instanceof AbstractVillager || a instanceof AbstractGolem || a instanceof TamableAnimal t && t.isTame()) continue;
			if (victim.tickCount - victim.getLastHurtByMobTimestamp() > 200 || player.distanceTo(a) > 16) continue;
			if (victim == player && !attackedBy(a, now)) continue;                // a poke to get its attention isn't a fight
			if (victim == o && a instanceof ServerPlayer sp && !isWeapon(sp.getMainHandItem())) continue;   // nor someone poking its owner
			if (own && a instanceof ServerPlayer sp) {                            // its own call: who, and whether it can win
				if (trust(sp.getUUID()) >= 0.6f && player.getHealth() > 10) continue;   // a friend's mistake: it lets it go
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
		if (!mod.config.signs || player == null || inArena || someoneListening(mod.config.chatWakeDistance)) return;
		long now = player.level().getGameTime();
		if (now - lastNote < 6000) return;                            // at most one note every five minutes
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

	void chatter(String text, boolean always) {
		long now = System.currentTimeMillis();
		if (!mod.config.chat || inArena || (!always && now - lastChatter < personality.chatterGapMillis())) return;
		if (!always && !fresh(text)) return;                           // someone just said that: no need to say it again
		lastChatter = now;
		say(text);
	}

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

	public void say(String text) {
		Component line = Component.literal("<" + name + "> " + text);
		if (mod.config.localChat && player != null) {                     // local chat: only those close by hear it
			for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!(p instanceof XenPlayer) && mod.inRange(p, player)) p.sendSystemMessage(line);
			XenMod.LOG.info("<{}> {} (local chat)", name, text);
		} else {
			server.getPlayerList().broadcastSystemMessage(line, false);
		}
		if (player != null) mod.overheardBy(player, name, text);          // and the Xens close by
		life.said(text);                                                // a nod, a shake of the head, a wave
		journal("says", text);
	}

	/** A line in the journal (the Experimental tab's log): what it sees, thinks, says, how it goes. */
	void journal(String kind, String text) {
		if (mod.config.journal && mod.journal != null) mod.journal.add(name, kind, text);
	}

	private String journaledThought = "", journaledSight = "";
	private long journaledSightAt;

	// ------------------------------------------------------------------------ death and life
	void died(DamageSource source) {
		if (goals.option >= 0) goals.finishOption(true);                  // Xen 2.0 learns what that choice led to
		if (source.getEntity() instanceof ServerPlayer) lostFight = true; // (beaten by someone: it may want to train)
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
		lifeTicks = 0;
		lifeReward = 0;
		emotions.reset();
		String how = source.type().msgId();
		boolean gone = how.contains("lava") || how.contains("outOfWorld") || how.contains("void") || how.contains("fire") || how.contains("explosion")
				|| how.contains("drown") || player.isUnderWater();                   // (under water: not worth drowning again for)
		lostAt = gone || inArena ? null : player.blockPosition().immutable();   // its things are lying there: back for them after
		lostDimension = player.level().dimension();
		lostUntil = player.level().getGameTime() + 5200;              // (items last five minutes)
		journal("does", "died: " + source.getLocalizedDeathMessage(player).getString());
		respawnIn = 60;                                               // three seconds, like pressing "Respawn"
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
		XenMod.quietJoin = true;                                      // (coming back isn't news: no "joined the game")
		try {
			join(spot.newLevel(), spot.position(), spot.yRot());
		} finally {
			XenMod.quietJoin = false;
		}
	}

	public String status() {
		if (player == null) return name + " is not here.";
		return String.format("%s (%s; %s; %s): health %.0f/20, food %d/20, mood %s (fear %.0f%%), mode %s. Goals: %s. %s Last thought: %s",
				name, personality.describe(), personality.style(), goals.likes(), player.getHealth(), player.getFoodData().getFoodLevel(),
				emotions.mood(), emotions.fear * 100, mode.name().toLowerCase(), goals.status(),
				senses.describe() + (mimic.skill.isEmpty() ? "" : " " + mimic.describe()) + " " + skills.describe()
						+ (personality.loner ? " (a loner)" : ""), lastThought);
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
						skills.describe(), rumors.describe(), life.describe())
				+ (mimic.skill.isEmpty() ? "" : " " + mimic.describe())
				+ (lastSign != null && player.level().getGameTime() - lastSignAt < 6000 ? " You read a sign that says: \"" + lastSign + "\"." : "")
				+ (instructions().isEmpty() ? "" : " " + xen.mod.talk.Chat.TOLD + " " + instructions())
				+ (trader.market().isEmpty() ? "" : " " + trader.market())
				+ (mod.config.personalities ? " Your personality: " + personality.describe() + ". Your fighting style: " + personality.fight
						+ " (" + Personality.how(personality.fight) + "). " + personality.buildNote() : "");
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
		if (t < -0.2f) return "No, you won't, because " + who + " hurt you.";
		if (PLEASE.matcher(words).find()) return null;
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
	public String request(xen.mod.talk.Chat.Request r, ServerPlayer from, String said) {
		if (player == null) return null;
		UUID u = from.getUUID();
		known.add(u);                                                 // now it knows them
		String who = from.getName().getString();
		String words = said == null ? "" : xen.mod.talk.Chat.requestWords(said, name);
		needs.heard(from, words);                                      // "I'm hungry", "I need wood": it may help (its choice)
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
		String train = skills.asked(from, words);                      // "train your fighting", "practice mining"
		if (train != null) {
			if (skills.trainingNow()) goals.drop();
			say(train);
			return null;
		}
		String teamAnswer = teamAsked(from, words);                    // "join my team", "join the red team": its call
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
			boolean share = personality.kindness + 0.5f * trust(u) > 0.6f && needs.canHelp();
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
			}
			switch (r.intent()) {
				case "follow" -> {
					chores.cancel();
					mode = Mode.FOLLOW;
					leader = from.getUUID();
					plan = "You will follow " + who + ".";
				}
				case "stay" -> {
					chores.cancel();
					mode = Mode.STAY;
					anchor = player.blockPosition();
					plan = "You will stay here.";
				}
				case "explore" -> {
					chores.cancel();
					mode = Mode.FREE;
					plan = "You will go exploring on your own.";
				}
				case "stop" -> {
					boolean busy = wasBusy;
					if (!busy) {
						mode = Mode.STAY;
						anchor = player.blockPosition();
					}
					plan = busy ? "You will stop what you are doing." : "You will stop and wait here.";
				}
				case "wood", "stone", "coal", "iron", "mine" -> plan = chores.gather(r.intent(), r.amount());
				case "food" -> plan = chores.hunt(r.amount());
				case "pickup" -> plan = chores.pickUp(r.thing());
				case "give" -> plan = chores.give(from, r.thing(), r.amount());
				case "shelter" -> plan = chores.shelter();
				case "eat" -> plan = chores.eat();
				case "redstone" -> plan = chores.redstone(r.thing());
				case "craft" -> plan = crafter.request(r.thing(), r.amount());
				case "build" -> plan = r.thing().startsWith("statue") ? builder.statue(r.thing().substring(Math.min(r.thing().length(), 7)), from)
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
		if (plan == null) {
			String straight = talker.answer(words);                   // everyday questions: from what it knows, nothing made up
			if (straight != null) {
				say(straight);
				return null;
			}
			return notes();                                           // just talk: the chat answers
		}
		if (!refused) lastThought = "Asked by " + who + ": " + plan;
		say(xen.mod.talk.Chat.plainly(notes() + " Plan: " + plan, ""));   // what it will do (or why not), right away
		return null;
	}

	/** Talking on its own: remarks, questions and chats with other Xens. */
	final Talker talker = new Talker(this);
	/** Learning by watching: moves it copies from players when they work out. */
	final Mimic mimic = new Mimic(this);
	/** The unpredictable side: dancing, showing off, surprises. */
	final Antics antics = new Antics(this);
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

	String pick3(String a, String b, String c3) {
		int r = random.nextInt(3);
		return r == 0 ? a : r == 1 ? b : c3;
	}

	/** Someone said "watch me": it keeps its eyes on them for a while. */
	private UUID watchFor;
	private int watchUntil;

	boolean watchingYou(ServerPlayer p) {
		return p.getUUID().equals(watchFor) && watchUntil > player.tickCount;
	}
}
