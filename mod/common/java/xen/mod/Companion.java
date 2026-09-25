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
	final String ownerName;
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
		XenPlayer p = new XenPlayer(server, level, profile);
		p.companion = this;
		FakeConnection connection = new FakeConnection(() -> server.execute(this::leave));
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
	void tick() {
		if (player == null) return;
		if (respawnIn >= 0) {
			if (--respawnIn < 0) respawn();
			return;
		}
		lifeTicks++;
		genTicks++;
		hands.tick();
		hurtNow = player.getHealth() < tickHealth;
		tickHealth = player.getHealth();
		mimic.watch();                                                  // what are the players it sees doing?
		if (mimic.clutchTick()) return;                                 // falling: a water clutch, this very tick
		if (hurtNow && !inArena && player.getLastHurtByMob() instanceof ServerPlayer by && by != player
				&& player.tickCount - player.getLastHurtByMobTimestamp() < 5) {
			trust(by.getUUID(), -0.3f);                                     // it remembers who hit it
		}
		if (hands.busy() && !((hurtNow || fighting) && hands.interruptible())) return;   // being hit cuts mining and walking short
		if (++decisions % Math.max(1, mod.config.decisionTicks / 5) != 0 && !fighting) return;   // in a fight, every tick
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
				: n.endsWith("_log") || n.endsWith("_stem") ? "log"
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
				note("Diamonds here!");
			}
		}
		lastHealth = player.getHealth();
		lastFood = player.getFoodData().getFoodLevel();
		lastItems = items;
		pillaring = acted = false;
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
		if (goals.instant.isEmpty()) goals.instant = mode == Mode.FREE ? "exploring" : "looking around";
		if (hands.watching == null && watchFor != null && watchUntil > player.tickCount) {   // "watch me": eyes on them
			ServerPlayer w = server.getPlayerList().getPlayer(watchFor);
			if (w != null && w.level() == player.level()) hands.watching = w;
		}
		if (!pillaring && !acted) hands.start(Action.values()[action]);
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
	private Action sensible(Action a) {
		switch (a) {
			case FORWARD, BACK, LEFT, RIGHT, JUMP -> {
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

	/** Nothing to do: like a player waiting, it watches its friend if they're near, or looks around now and then. */
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
			if (goals.instant.isEmpty()) goals.instant = "fighting the " + fightingWhat;
			return fight;
		}
		if (inArena) return Action.IDLE;                              // between duels it waits for the next one
		if (crafter.ready() && !farBehind()) {                        // tools first, like any new player
			Action craft = crafter.next();
			if (craft != null) return craft;
		}
		if (chores.busy()) {
			Action chore = chores.next();
			if (!chores.doing.isEmpty() && goals.instant.isEmpty()) goals.instant = chores.doing;
			if (chore != null || chores.busy()) return chore;
		}
		Action deal = trader.next();
		if (deal != null) return deal;
		if (mode == Mode.FREE && mod.config.wants && !inArena && goals.think()) {   // free: what does it want?
			Action chore = chores.next();
			if (chore != null || chores.busy()) return chore;
		}
		Vec3 goal = null;
		if (mode == Mode.FOLLOW && leader != null) {
			ServerPlayer o = server.getPlayerList().getPlayer(leader);
			if (o != null && o.level() == player.level()) {
				double d = o.position().distanceTo(player.position());
				if (d > mod.config.followDistance && d < 96) goal = o.position();
			}
		} else if (mode == Mode.STAY && anchor != null && Vec3.atCenterOf(anchor).distanceTo(player.position()) > 8) {
			goal = Vec3.atCenterOf(anchor);
		}
		if (goal == null) return null;
		goals.instant = mode == Mode.STAY ? "going back to where you were told to stay" : "keeping up with your friend";
		return walkTo(goal);
	}

	/** Is its friend getting away (so there's no time to stop and craft)? */
	private boolean farBehind() {
		if (mode != Mode.FOLLOW || leader == null) return false;
		ServerPlayer o = server.getPlayerList().getPlayer(leader);
		return o != null && o.level() == player.level() && o.distanceTo(player) > mod.config.followDistance * 2;
	}

	/** Walk towards a place like a player: turn, walk, jump up steps, dig through, pillar up. Null if lava is in the way. */
	Action walkTo(Vec3 goal) {
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
		boolean moved = walkedFrom == null || player.position().distanceToSqr(walkedFrom) >= 0.01;
		stuck = moved ? 0 : tried || stuck >= 4 ? stuck + 1 : stuck;
		walkedFrom = player.position();
		if (stuck > 13) stuck = 0;                                     // then try finding a way again
		if (stuck >= 4) {
			int phase = (stuck / 3) % 3;
			if (phase == 0) return hands.pitch != 1 ? Action.LOOK_UP : Action.MINE;
			if (phase == 1) return hands.pitch != 0 ? (hands.pitch > 0 ? Action.LOOK_DOWN : Action.LOOK_UP) : Action.MINE;
			return Action.RIGHT;
		}
		// A way through what it knows (within 6 blocks): walk, step up, drop down, swim, around obstacles and lava.
		int[] step = senses.last == null ? null
				: Paths.firstStep(senses.last, goal.x - (feet.getX() + 0.5), goal.y - feet.getY(), goal.z - (feet.getZ() + 0.5));
		if (step != null) {
			if (step[0] != hands.yaw) return Math.floorMod(step[0] - hands.yaw, 4) == 3 ? Action.TURN_LEFT : Action.TURN_RIGHT;
			return step[1] == 1 ? Action.JUMP : Action.FORWARD;
		}
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
		return fighter.next(foe, hurtNow);
	}

	private String fightingWhat = "";

	private final Fighter fighter = new Fighter(this);
	/** What it's after: this moment, the next minutes, and its dream. */
	final Goals goals = new Goals(this);
	/** Trading with villagers and players. */
	final Trader trader = new Trader(this);
	/** Making its tools. */
	final Crafter crafter = new Crafter(this);
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

	private float tickHealth;
	private boolean fighting, hurtNow;

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

	private LivingEntity foe() {
		if (inArena) return duelFoe != null && duelFoe.isAlive() && duelFoe.level() == player.level() && player.distanceTo(duelFoe) < 32
				? duelFoe : null;
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
		for (LivingEntity victim : new LivingEntity[] {player, o}) {               // who hurt it, or its owner, just now
			if (victim == null || victim.level() != player.level()) continue;
			LivingEntity a = victim.getLastHurtByMob();
			if (a == null || !a.isAlive() || a == player || a == o || player.isAlliedTo(a)) continue;
			if (a instanceof AbstractVillager || a instanceof AbstractGolem || a instanceof TamableAnimal t && t.isTame()) continue;
			if (victim.tickCount - victim.getLastHurtByMobTimestamp() > 200 || player.distanceTo(a) > 16) continue;
			if (player.distanceTo(a) <= NEAR || WorldSenses.sees(player, hands.yaw, hands.pitch, a)) return a;   // near: it feels them
		}
		if (!mod.config.pvp.equals("teams")) return null;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {           // team battles: Xens of other teams
			if (!(other instanceof XenPlayer) || other == player || !other.isAlive() || other.level() != player.level()) continue;
			if (player.isAlliedTo(other) || player.getTeam() == null || other.getTeam() == null) continue;
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
		lastChatter = now;
		say(text);
	}

	public void say(String text) {
		server.getPlayerList().broadcastSystemMessage(Component.literal("<" + name + "> " + text), false);
	}

	// ------------------------------------------------------------------------ death and life
	void died(DamageSource source) {
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
		join(spot.newLevel(), spot.position(), spot.yRot());
	}

	public String status() {
		if (player == null) return name + " is not here.";
		return String.format("%s (%s; %s; %s): health %.0f/20, food %d/20, mood %s (fear %.0f%%), mode %s. Goals: %s. %s Last thought: %s",
				name, personality.describe(), personality.style(), goals.likes(), player.getHealth(), player.getFoodData().getFoodLevel(),
				emotions.mood(), emotions.fear * 100, mode.name().toLowerCase(), goals.status(),
				senses.describe() + (mimic.skill.isEmpty() ? "" : " " + mimic.describe()), lastThought);
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
				+ " " + goals.describe() + " " + crafter.describe() + (mimic.skill.isEmpty() ? "" : " " + mimic.describe())
				+ (trader.market().isEmpty() ? "" : " " + trader.market())
				+ (mod.config.personalities ? " Your personality: " + personality.describe() + ". Your fighting style: " + personality.fight
						+ " (" + Personality.how(personality.fight) + "). " + personality.buildNote() : "");
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
		if (r.intent().equals("chat") && FRIENDLY.matcher(words).find()) trust(u, 0.05f);   // kind words
		if (talker.answered(from, words)) return null;               // a yes or no to something it asked
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
		String plan = null;
		boolean refused = false;
		if (!r.intent().equals("chat") && owner != null && !owner.equals(u)) {
			plan = "Only " + ownerName + " can tell you what to do, so you won't.";
		} else if (!r.intent().equals("chat") && (plan = refusal(r, from, words)) != null) {
			refused = true;
			lastThought = "Said no to " + who + ": " + plan;
		} else {
			if (!r.intent().equals("chat")) goals.drop();            // being asked for something comes before its own goals
			boolean wasBusy = chores.busy();
			if (!r.intent().equals("chat")) {                           // a new request replaces what it was doing
				chores.cancel();
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
				case "give" -> plan = chores.give(from, r.thing(), r.amount());
				case "shelter" -> plan = chores.shelter();
				case "eat" -> plan = chores.eat();
				case "redstone" -> plan = chores.redstone(r.thing());
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
	/** Someone said "watch me": it keeps its eyes on them for a while. */
	private UUID watchFor;
	private int watchUntil;

	boolean watchingYou(ServerPlayer p) {
		return p.getUUID().equals(watchFor) && watchUntil > player.tickCount;
	}
}
