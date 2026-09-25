package xen.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Blocks;
import xen.mod.core.Brain;
import xen.mod.core.Emotions;
import xen.mod.core.Paths;
import xen.mod.core.Perception;

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

	Companion(XenMod mod, MinecraftServer server, String name, UUID owner, String ownerName) {
		this.mod = mod;
		this.server = server;
		this.name = name;
		this.owner = owner;
		this.leader = owner;
		this.ownerName = ownerName;
		this.emotions = mod.brain.newBody();
	}

	public XenPlayer player() {
		return player;
	}

	// ------------------------------------------------------------------------------- joining
	/** Join the server as a real player at a position. */
	void join(ServerLevel level, Vec3 at, float yaw) {
		GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
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
	}

	void leave() {
		if (player != null && lifeTicks > 0) {                         // the life so far goes into lives.csv too
			mod.logLife(name, player.level().getGameTime() / 24000, lifeTicks, lifeReward, "left");
			lifeTicks = 0;
		}
		if (player != null && !player.isRemoved()) {
			hands.stop();
			server.getPlayerList().remove(player);
		}
		player = null;
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
		hands.tick();
		if (hands.busy()) return;
		if (++decisions % Math.max(1, mod.config.decisionTicks / 5) != 0) return;
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
			mod.learn(obs, lastAction, reward, harm, next, false, emotions, name);
			lifeReward += reward;
			if (items.getOrDefault("diamond", 0) > lastItems.getOrDefault("diamond", 0)) chatter("Diamonds!!", true);
		}
		lastHealth = player.getHealth();
		lastFood = player.getFoodData().getFoodLevel();
		lastItems = items;
		pillaring = acted = false;
		Action instinct = instinct();
		Brain.Thought thought = mod.brain.decide(next, emotions, mod.config.learn);
		int action = instinct != null ? instinct.ordinal() : thought.action;
		lastThought = instinct != null ? (chores.busy() ? "Doing what I was asked (" + chores.doing + "): " : "Instinct: ")
				+ (pillaring ? "climb up" : instinct.verb) + "." : thought.text;
		if (!pillaring && !acted) hands.start(Action.values()[action]);
		if (DEBUG) XenMod.LOG.info("[xen debug] {} at {} ground={} yaw={} pitch={} -> {} ({})", name, player.position(), player.onGround(),
				hands.yaw, hands.pitch, Action.values()[action], lastThought);
		obs = next;
		lastAction = action;
		react();
	}

	/** Companion instincts that come before the brain's own choice. */
	private Action instinct() {
		if (player.isInWater() && (player.isUnderWater() || player.getAirSupply() < player.getMaxAirSupply())) {
			return Action.JUMP;                                       // hold space to swim up, like a player
		}
		if (player.getFoodData().getFoodLevel() <= 10 && items().getOrDefault("food", 0) > 0 && player.getFoodData().needsFood()) {
			return Action.EAT;
		}
		Action fight = fightBack();
		if (fight != null) return fight;
		if (chores.busy()) {
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
		return walkTo(goal);
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

	/** A monster within reach (it knows everything within 6 blocks): face it and hit it once the attack is charged. */
	private Action fightBack() {
		double reach = player.entityInteractionRange() + 0.5;
		LivingEntity foe = null;
		double closest = reach;
		for (LivingEntity e : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(reach),
				x -> x instanceof Enemy && x.isAlive())) {
			double d = player.distanceTo(e);
			if (d <= closest && player.hasLineOfSight(e)) {
				closest = d;
				foe = e;
			}
		}
		if (foe == null) return null;
		double dx = foe.getX() - player.getX(), dz = foe.getZ() - player.getZ();
		int want = hands.yaw;
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
		return player.getAttackStrengthScale(0.5f) >= 0.9f ? Action.ATTACK : Action.IDLE;
	}

	private static int cat(ServerLevel level, BlockPos pos) {
		return level.isLoaded(pos) ? WorldSenses.category(level, pos, level.getBlockState(pos)) : Blocks.STONE;
	}

	/** Short spoken reactions to what it feels (no language model needed). */
	private void react() {
		boolean night = player.level().isDarkOutside();
		if (night && !wasNight) chatter("It's getting dark... stay close.", false);
		wasNight = night;
		if (emotions.pain > 0.25f) chatter("Ouch!", false);
		else if (emotions.fear > 0.65f) {
			chatter(senses.describe().contains("lava") ? "Careful, there's lava!" : "I don't like this...", false);
		}
	}

	void chatter(String text, boolean always) {
		long now = System.currentTimeMillis();
		if (!mod.config.chat || (!always && now - lastChatter < 20_000)) return;
		lastChatter = now;
		say(text);
	}

	public void say(String text) {
		server.getPlayerList().broadcastSystemMessage(Component.literal("<" + name + "> " + text), false);
	}

	// ------------------------------------------------------------------------ death and life
	void died(DamageSource source) {
		if (obs != null) {
			float harm = lastHealth / 20f + 1f;
			mod.learn(obs, lastAction, 0, harm, obs, true, emotions, name);
		}
		obs = null;
		mod.brain.lives++;
		String cause = source.type().msgId() + (source.getEntity() != null
				? ":" + BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).getPath() : "");
		mod.logLife(name, player.level().getGameTime() / 24000, lifeTicks, lifeReward, cause);
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
		return String.format("%s: health %.0f/20, food %d/20, mood %s (fear %.0f%%), mode %s. %s Last thought: %s",
				name, player.getHealth(), player.getFoodData().getFoodLevel(), emotions.mood(), emotions.fear * 100,
				mode.name().toLowerCase(), senses.describe(), lastThought);
	}

	/** Its notes for talking: only its own feelings, body and perception. */
	public String notes() {
		StringBuilder carrying = new StringBuilder();
		for (var e : items().entrySet()) {
			if (carrying.length() > 60) break;
			carrying.append(carrying.length() > 0 ? ", " : "").append(e.getValue()).append(' ').append(e.getKey().replace('_', ' '));
		}
		return xen.mod.talk.Chat.notes(emotions.mood(), emotions.pain > 0.15f, player.getHealth(),
				player.getFoodData().getFoodLevel(), carrying.toString(), senses.describe());
	}

	/**
	 * A player said something to it. A request: it does it (with its own hands and senses) and says its plan in plain
	 * words, or why it can't. Only its owner can tell it what to do (anyone, if it has no owner). Just talk: returns its
	 * notes for the chat to answer from.
	 */
	public String request(xen.mod.talk.Chat.Request r, ServerPlayer from) {
		if (player == null) return null;
		String who = from.getName().getString();
		String plan = null;
		if (!r.intent().equals("chat") && owner != null && !owner.equals(from.getUUID())) {
			plan = "Only " + ownerName + " can tell you what to do, so you won't.";
		} else {
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
				default -> {}
			}
		}
		if (plan == null) return notes();                             // just talk: the chat answers
		lastThought = "Asked by " + who + ": " + plan;
		say(xen.mod.talk.Chat.plainly(notes() + " Plan: " + plan, ""));   // what it will do (or why not), right away
		return null;
	}
}
