package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Mind;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What a Xen is after, in three tiers.
 * <ul>
 *   <li><b>Instant</b> (seconds): staying alive and what it's doing this moment: swimming up, eating, fighting,
 *   backing off to heal, getting away from a creeper, making a tool, keeping up with its friend. Its instincts decide
 *   these; here they only get a name.</li>
 *   <li><b>Short</b> (minutes): what it wants now, when it's free: food, a shelter for the night, wood, stone, ore, a
 *   trade with a villager, or to explore. It weighs what it needs right now, its personality, how well each one has
 *   turned out before (it learns which it likes), and what its long goal needs.</li>
 *   <li><b>Long</b> (days): its dream: a home, a big stockpile, diamonds, being a trader, far-away places, or friends.
 *   It's picked from its personality, steers the short goals, and is kept with the Xen. When it comes true, the Xen is
 *   proud of it and picks a new one.</li>
 * </ul>
 */
final class Goals {
	enum Short {
		FOOD("find some food", "you are hungry and have no food"),
		SHELTER("build a shelter for the night", "it's dark and you have no roof"),
		WOOD("get some wood", "you have little wood"),
		STONE("get some stone", "you have few blocks"),
		ORE("look for ore", "you would like to find treasure"),
		HOUSE("build a house", "you have no home yet and a real player builds one"),
		MINE("go mining", "you want iron and diamonds for better tools"),
		SMELT("smelt your iron", "raw iron has to be smelted before you can make tools of it"),
		FARM("build a farm", "a farm by your house means food without hunting"),
		MOBFARM("build a mob farm", "a mob farm brings bones, string, gunpowder and experience to your door"),
		STORE("put your things away in a chest", "your bag is getting full"),
		ADVENTURE("go on an adventure to beat the Ender Dragon", "you are well equipped now and the dragon is waiting"),
		TRIALS("take on the trial chamber", "its vaults are full of loot"),
		TRADE("trade with a villager", "you have things a villager may want"),
		EXPLORE("explore", "you are curious");

		final String what, why;

		Short(String what, String why) {
			this.what = what;
			this.why = why;
		}
	}

	enum Long {
		HOME("build a home"), STOCKPILE("gather a big stockpile of wood and stone"), TREASURE("find diamonds"),
		TRADER("become a trader with 5 emeralds"), EXPLORER("see places 300 blocks away"), FRIENDS("make three friends"),
		DRAGON("beat the Ender Dragon"), VILLAGE("grow a village with my tribe");

		final String what;

		Long(String what) {
			this.what = what;
		}
	}

	/** How often it thinks about what it wants (ticks), and how long it just explores. */
	static final int THINK = 400, EXPLORE_TICKS = 1200;
	static final int HOME_BLOCKS = 18;

	private final Companion c;
	private final Random random = new Random();
	/** How well each short goal has turned out for it, 0 to 1 (learned). */
	final Map<Short, Float> liking = new EnumMap<>(Short.class);
	/** What it's doing this moment (named by its instincts each decision). */
	String instant = "";
	Short current;
	Long dream;
	BlockPos home, farm, mobFarm, pen;
	private int[] origin;
	int achieved;
	private long next, until;
	private float rewardAtStart;
	private boolean buildingHome;

	Goals(Companion c) {
		this.c = c;
		for (Short s : Short.values()) liking.put(s, 0.5f);
	}

	// --------------------------------------------------------------------------------- long
	/** Its dream, picked from its personality (with a little chance). */
	private Long pickDream(Long not) {
		var p = c.personality;
		Map<Long, Float> pull = new EnumMap<>(Long.class);
		pull.put(Long.HOME, 0.5f * p.diligence + 0.3f * (1 - p.curiosity));
		pull.put(Long.STOCKPILE, 0.7f * p.diligence);
		pull.put(Long.TREASURE, 0.8f * p.bravery);
		pull.put(Long.TRADER, 0.6f * p.chattiness + (c.mod.config.trading ? 0.1f : -1f));
		pull.put(Long.EXPLORER, 0.8f * p.curiosity);
		pull.put(Long.FRIENDS, 0.8f * p.chattiness);
		pull.put(Long.DRAGON, c.mod.config.adventures ? 0.6f * p.bravery + 0.3f * p.diligence : -1f);
		pull.put(Long.VILLAGE, c.mod.config.tribes ? 0.5f * p.chattiness + 0.4f * p.diligence : -1f);
		Long best = null;
		float bestScore = -9;
		for (var e : pull.entrySet()) {
			float s = e.getValue() + random.nextFloat() * 0.3f;
			if (e.getKey() != not && s > bestScore) {
				bestScore = s;
				best = e.getKey();
			}
		}
		return best;
	}

	/** How far along its dream is, 0 to 1. */
	float progress() {
		if (dream == null) return 0;
		var items = c.items();
		int logs = items.getOrDefault("log", 0), blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
		return switch (dream) {
			case HOME -> home != null ? 1f : 0.9f * Math.min(1f, blocks / (float) HOME_BLOCKS);
			case STOCKPILE -> (Math.min(1f, logs / 32f) + Math.min(1f, items.getOrDefault("cobblestone", 0) / 64f)) / 2f;
			case TREASURE -> items.getOrDefault("diamond", 0) > 0 ? 1f : 0.3f * Math.min(1f, items.getOrDefault("raw_iron", 0) / 3f);
			case TRADER -> Math.min(1f, items.getOrDefault("emerald", 0) / 5f);
			case EXPLORER -> origin == null ? 0 : (float) Math.min(1.0, Math.hypot(c.player.getX() - origin[0], c.player.getZ() - origin[1]) / 300.0);
			case FRIENDS -> Math.min(1f, c.friends() / 3f);
			case DRAGON -> dragonDown ? 1f : c.adventure.stage == null ? 0.05f : c.adventure.stage.ordinal() / (float) Adventure.Stage.DONE.ordinal();
			case VILLAGE -> {
				Tribe t = c.tribe();
				int homes = 0;
				if (t != null) for (Companion m : t.members) if (m.goals.home != null) homes++;
				yield Math.min(1f, homes / 4f);
			}
		};
	}

	private long nextDreamCheck;

	/** Every decision, whatever it's doing: it always has a dream, and notices when it comes true. */
	void everyDecision() {
		long now = c.player.level().getGameTime();
		if (dream != null && now < nextDreamCheck) return;
		nextDreamCheck = now + THINK;
		checkDream();
	}

	/** Its dream came true? Then it's proud, says so, and dreams of something new. */
	private void checkDream() {
		if (dream == null) dream = pickDream(null);
		if (origin == null) origin = new int[] {(int) c.player.getX(), (int) c.player.getZ()};
		if (progress() < 1f) return;
		achieved++;
		c.antics.celebrate();
		c.say(switch (dream) {
			case HOME -> "I built my home! It's a little fort, but it's mine.";
			case STOCKPILE -> "Look at my stockpile! 32 logs and 64 stone.";
			case TREASURE -> "I found diamonds! My dream came true!";
			case TRADER -> "Five emeralds! I'm a real trader now.";
			case EXPLORER -> "I've come so far! Everything here is new to me.";
			case FRIENDS -> "I have three friends now. That makes me happy.";
			case DRAGON -> "The Ender Dragon is beaten! I did it!";
			case VILLAGE -> "Look at our village! Four houses, and a tribe to live in it.";
		});
		Long old = dream;
		dream = pickDream(old);
		origin = new int[] {(int) c.player.getX(), (int) c.player.getZ()};
		c.chatter("Next, I'd like to " + dream.what + ".", false);
	}

	// -------------------------------------------------------------------------------- short
	/**
	 * Called when it's free. While a short goal is on, it follows it (via its chore) or finishes it; otherwise, every
	 * so often, it picks a new one. True if a chore is now going (so the chore decides this moment).
	 */
	boolean think() {
		return think(false);
	}

	/**
	 * Nearby (while it follows a friend who's close): only what it can do right here, without leaving them: food,
	 * wood, stone, ore, a shelter at night. It keeps following (it isn't set free).
	 */
	boolean think(boolean nearby) {
		long now = c.player.level().getGameTime();
		if (current != null) {
			boolean over = current == Short.EXPLORE ? now > until || progressWaiting() && now > until - EXPLORE_TICKS / 2
					: current == Short.FARM ? !c.farmer.on && !c.chores.busy()
					: building(current) ? !c.builder.busy() : current == Short.STORE ? !c.storage.busy()
					: current == Short.ADVENTURE ? !c.adventure.on : current == Short.TRIALS ? !c.trials.on : !c.chores.busy();
			if (!over) return c.chores.busy() && !building(current);
			learn();
			next = now;                                                      // done: straight on to the next thing, like a player
		}
		if (useMind()) return thinkMind(nearby, now);
		if (now < next) return false;
		next = now + THINK;
		Short best = null;
		float bestScore = 0.25f;                                         // below this it's happy doing whatever it does
		for (Short s : Short.values()) {
			if (nearby && (s == Short.TRADE || s == Short.EXPLORE || s == Short.MINE || s == Short.HOUSE || s == Short.FARM || s == Short.MOBFARM
					|| s == Short.ADVENTURE || s == Short.TRIALS || s == Short.STORE)) continue;   // those would take it away from its friend
			float score = (urgency(s) + dreamPull(s)) * (0.5f + liking.get(s)) + random.nextFloat() * 0.15f * c.personality.curiosity;
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		if (best == null) return false;
		return begin(best, nearby, now) && c.chores.busy();
	}

	/** Get on with a short goal (it chose it, or Xen 2.0 did). False if it can't right now. */
	private boolean begin(Short best, boolean nearby, long now) {
		buildingHome = best == Short.SHELTER && dream == Long.HOME && home == null && !c.player.level().isDarkOutside();
		String plan = switch (best) {
			case FOOD -> c.chores.hunt(2);
			case SHELTER -> buildingHome ? c.chores.shelter("fort") : c.chores.shelter();
			case WOOD -> c.chores.gather("wood", c.crafter.pickTier() == 0 ? 5 : 10);
			case STONE -> needBlocksForTheNight() || c.crafter.pickTier() == 0 ? c.chores.gather("dirt", Math.max(4, 12 - blocks()))
					: c.chores.gather("stone", 16);
			case ORE -> c.chores.gather("ore", 2);
			case HOUSE -> {
				Tribe t = c.tribe();
				BlockPos plot = t != null && t.members.size() > 1 && t.center != null ? t.plot() : home != null ? nextTo(home, 14) : null;   // in its tribe's village
				String h = plot != null ? c.builder.startNear("house", plot) : c.builder.start("house");
				yield h.startsWith("You will") ? h : "You can't: " + h;
			}
			case STORE -> c.storage.store();
			case ADVENTURE -> c.adventure.start();
			case TRIALS -> c.trials.start();
			case MINE -> c.crafter.pickTier() >= 3 && diamonds() < 3 ? c.chores.mine(-54, "diamonds", 3)
					: c.crafter.pickTier() >= 2 ? c.chores.mine(16, "iron", 6) : c.chores.mine(40, "coal", 8);
			case SMELT -> c.chores.smelt();
			case FARM -> c.farmer.start();                                    // (a real farm: tilled, planted, looked after)
			case MOBFARM -> {
				String h = c.builder.startNear("mob farm", nextTo(home, 24));
				yield h.startsWith("You will") ? h : "You can't: " + h;
			}
			case TRADE -> c.trader.withVillager(null);
			case EXPLORE -> "";
		};
		if (!nearby) c.mode = Companion.Mode.FREE;                      // (a shelter would have it stay; it's still free)
		else if (best == Short.SHELTER) c.mode = Companion.Mode.FOLLOW;   // it builds it where it is, next to its friend
		if (plan.startsWith("You can't") || plan.startsWith("You have no") || plan.startsWith("You don't")) {
			if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} wanted to {}, but: {}", c.name, best.what, plan);
			liking.merge(best, -0.05f, (a, b) -> Math.max(0f, a + b));       // it can't right now: a little less keen
			c.chores.cancel();
			return false;
		}
		current = best;
		rewardAtStart = c.genReward;
		until = now + EXPLORE_TICKS;
		c.chores.own = true;
		c.chatter(buildingHome ? "I'm going to build my home here!" : nearby ? pickNearby(best) : "I want to " + best.what + ".", nearby);
		return true;
	}

	/** What it says when it gets on with something while its friend is close. */
	private String pickNearby(Short s) {
		return switch (s) {
			case FOOD -> "I'm hungry. I'll hunt something nearby.";
			case SHELTER -> "It's getting dark. I'll build us a shelter.";
			case WOOD -> "I'll grab some wood while we're here.";
			case STONE -> needBlocksForTheNight() ? "It'll be dark soon. I'll dig up some dirt for a shelter." : "I'll get some stone while we're here.";
			default -> "I'll look for ore around here.";
		};
	}

	/** How much it needs a short goal right now, 0 to about 1, shaped by its personality. */
	private float urgency(Short s) {
		var p = c.personality;
		var items = c.items();
		int food = c.player.getFoodData().getFoodLevel();
		int blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
		var level = c.player.level();
		boolean outInTheDark = level.isDarkOutside() && level.canSeeSky(c.player.blockPosition().above());   // no roof yet
		int tools = Math.max(c.crafter.pickTier(), c.crafter.canMake(1) ? 1 : 0);
		int iron = items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0);
		boolean busyBuilding = c.builder.busy();
		return switch (s) {
			case FOOD -> items.getOrDefault("food", 0) > 0 ? 0 : Math.max(0, (16 - food) / 16f) * 1.2f;
			case SHELTER -> outInTheDark && blocks >= 10 ? 0.9f * (1.2f - 0.6f * p.bravery) : 0;
			case WOOD -> tools == 0 && items.getOrDefault("log", 0) < 3 ? 0.9f                 // wood for a pickaxe comes first
					: items.getOrDefault("log", 0) < 4 ? 0.5f * (0.5f + p.diligence) : 0;
			case STONE -> needBlocksForTheNight() ? 0.85f                  // evening, and not enough blocks for a shelter: dirt will do
					: tools == 0 ? 0 : c.crafter.pickTier() == 1 && items.getOrDefault("cobblestone", 0) < 3 ? 0.8f   // stone tools next
					: blocks < 10 ? 0.4f * (0.5f + p.diligence) : 0;
			case ORE -> tools == 0 ? 0 : 0.3f * (0.5f + p.bravery);                         // no pickaxe: ore drops nothing
			case TRADE -> c.mod.config.trading && c.trader.villagerNear() != null && c.trader.hasSomethingToTrade() ? 0.35f : 0;
			case EXPLORE -> 0.3f * (0.5f + p.curiosity) * (progressWaiting() ? 0.4f : 1f);   // not while there's progress to make
			// the way a player gets on in the world: a real house once it has tools, then down for iron, then diamonds
			case HOUSE -> home == null && !busyBuilding && c.crafter.pickTier() >= 2 && !evening() && !c.player.isCreative()
					? 0.7f * (0.6f + p.diligence)
					: home != null && !busyBuilding && !evening() && !c.player.isCreative() && c.taste.wantsBigger(items.getOrDefault("log", 0))
					? 0.35f * (0.5f + p.diligence) : 0;                                // a better house once it has the wood
			case MINE -> tools >= 2 && iron < 6 && c.crafter.pickTier() < 3 ? 0.55f * (0.6f + p.bravery)
					: c.crafter.pickTier() >= 3 && diamonds() < 3 ? 0.5f * (0.5f + p.bravery)
					: tools >= 1 && items.getOrDefault("coal", 0) < 4 && home != null ? 0.3f : 0;
			case FARM -> home != null && c.farmer.middle == null && !busyBuilding && !evening() && tools >= 1 ? 0.55f * (0.5f + p.diligence) : 0;
			case MOBFARM -> home != null && farm != null && mobFarm == null && !busyBuilding && !evening()
					&& (c.player.isCreative() || items.getOrDefault("cobblestone", 0) >= 300) ? 0.45f * (0.5f + p.diligence) : 0;
			case SMELT -> (items.getOrDefault("raw_iron", 0) >= 3 || c.chores.rawFood() >= 3) && (items.getOrDefault("coal", 0) > 0 || items.getOrDefault("log", 0) > 1)
					&& (items.getOrDefault("cobblestone", 0) >= 8 || items.getOrDefault("furnace", 0) > 0) ? 0.85f : 0;
			case STORE -> c.storage.wantsToStore() && (home != null || c.tribe() != null && c.tribe().center != null) ? 0.6f : 0;
			case ADVENTURE -> c.mod.config.adventures && !dragonDown && c.crafter.pickTier() >= 3 && !evening() && !c.player.isCreative()
					&& (dream == Long.DRAGON || c.personality.bravery > 0.6f && c.personality.generation >= 1) ? 0.5f * (0.5f + p.bravery) : 0;
			case TRIALS -> c.places.get("trial chamber") != null && c.trials.ready() && !evening() ? 0.45f * (0.5f + p.bravery) : 0;
		};
	}

	/** The next step in a player's progress is waiting (tools to upgrade, stone, iron): no time to wander off. */
	private boolean progressWaiting() {
		var items = c.items();
		int tier = c.crafter.pickTier();
		if (c.player.isCreative()) return false;
		return tier == 0 || tier == 1 || tier == 2 && items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0) < 3;
	}

	// ------------------------------------------------------------------------------ exploring
	private Vec3 exploreTo;
	private long exploreSince, lookUntil, nextMineUnderground;
	private double heading = Double.NaN;

	/**
	 * Exploring the way a player does it: it picks a spot a few dozen blocks off (on in the same direction, mostly,
	 * not back where it came from) and walks there, sprinting; there it stops and looks around for a moment, and on to
	 * the next. At dusk, with a home, it heads home instead. (What it sees on the way is what makes it want things.)
	 */
	Action exploreStep() {
		var p = c.player;
		long now = p.level().getGameTime();
		if (home != null && evening() && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
				&& p.blockPosition().distSqr(home) > 12 * 12 && p.blockPosition().distSqr(home) < 400 * 400) {
			instant = "heading home for the night";
			c.run(true);
			return c.walkTo(Vec3.atBottomCenterOf(home));
		}
		// Underground with no way to the sky it knows (a cave, a box someone put it in): a player doesn't wander in the
		// dark, it goes mining along a pattern (branch tunnels) when it has a pickaxe, or digs a staircase up and out.
		BlockPos feet = p.blockPosition();
		var level = (net.minecraft.server.level.ServerLevel) p.level();
		if (p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && !level.canSeeSky(feet.above())) {
			int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
			if (top - feet.getY() > 8) {
				int tier = c.crafter.pickTier();
				if (tier >= 1 && !evening() && now >= nextMineUnderground) {
					nextMineUnderground = now + 20 * 60 * 3;
					String plan = tier >= 3 && feet.getY() < 10 ? c.chores.mine(feet.getY(), "diamonds", 3)
							: tier >= 2 ? c.chores.mine(feet.getY(), "iron", 6) : c.chores.mine(feet.getY(), "coal", 8);
					if (plan.startsWith("You will")) {
						c.chores.own = true;
						c.chatter("Underground already? Then I'll mine here, in tunnels.", false);
						return null;
					}
				}
				instant = "digging a way up to the surface";
				return c.walkTo(new Vec3(feet.getX() + 0.5 + 3, top + 1, feet.getZ() + 0.5));   // a staircase up
			}
		}
		if (now < lookUntil) {                                                  // a look around, like a player taking in the view
			instant = "looking around";
			if (random.nextFloat() < 0.15f) c.hands.face(p.getEyePosition().add(random.nextGaussian(), random.nextGaussian() * 0.3, random.nextGaussian()));
			c.acted = true;
			return Action.IDLE;
		}
		if (exploreTo != null && (p.position().distanceTo(exploreTo) < 3 || now - exploreSince > 20 * 40)) {
			exploreTo = null;
			lookUntil = now + 30 + random.nextInt(50);
			return Action.IDLE;
		}
		if (exploreTo == null) {
			if (Double.isNaN(heading)) heading = random.nextDouble() * Math.PI * 2;
			for (int tries = 0; tries < 8 && exploreTo == null; tries++) {
				double a = heading + (random.nextDouble() - 0.5) * (tries < 4 ? 1.2 : Math.PI * 2);
				double r = 24 + random.nextInt(32);
				BlockPos from = p.blockPosition();
				int x = (int) Math.round(from.getX() + Math.cos(a) * r), z = (int) Math.round(from.getZ() + Math.sin(a) * r);
				if (!p.level().hasChunkAt(new BlockPos(x, from.getY(), z))) continue;
				Vec3 top = XenMod.surface((net.minecraft.server.level.ServerLevel) p.level(), x, z);
				if (top == null || Math.abs(top.y - p.getY()) > 20) continue;          // (water, a cliff: somewhere else)
				exploreTo = top;
				heading = a;
			}
			exploreSince = now;
			if (exploreTo == null) {
				heading += Math.PI / 2;
				return Action.IDLE;
			}
		}
		instant = "exploring";
		c.run(p.position().distanceTo(exploreTo) > 6);
		return c.walkTo(exploreTo);
	}

	/** A spot about that far from home, to one side (for its farm, its mob farm), in a direction that's the same for it each time. */
	private BlockPos nextTo(BlockPos home, int far) {
		double a = (c.name.hashCode() & 7) * Math.PI / 4 + far * 0.3;
		return home.offset((int) Math.round(Math.cos(a) * far), 0, (int) Math.round(Math.sin(a) * far));
	}

	/** The dragon is dead (it was there): its big adventure is over. */
	boolean dragonDown;

	private static boolean building(Short s) {
		return s == Short.HOUSE || s == Short.MOBFARM;
	}

	private int diamonds() {
		return c.items().getOrDefault("diamond", 0);
	}

	private int blocks() {
		var items = c.items();
		return items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
	}

	/** Evening (or night already) and too few blocks for a shelter: a player gets some dirt before dark. */
	boolean needBlocksForTheNight() {
		return evening() && blocks() < 10;
	}

	/** Evening or night: time to think about a shelter. */
	boolean evening() {
		var level = c.player.level();
		long time = Compat.timeOfDay(level);
		return time >= 11000 && time < 23000 || level.isDarkOutside();
	}

	/** What its dream adds to a short goal. */
	private float dreamPull(Short s) {
		if (dream == null) return 0;
		var items = c.items();
		int blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
		return switch (dream) {
			case HOME -> s == Short.HOUSE && home == null ? 0.4f : s == Short.WOOD && home == null && items.getOrDefault("log", 0) < 24 ? 0.2f : 0;
			case STOCKPILE -> s == Short.WOOD && items.getOrDefault("log", 0) < 32 || s == Short.STONE && items.getOrDefault("cobblestone", 0) < 64
					? 0.35f : 0;
			case TREASURE -> s == Short.ORE ? 0.3f : s == Short.MINE ? 0.35f : 0;
			case TRADER -> s == Short.TRADE && urgency(Short.TRADE) > 0 ? 0.45f : s == Short.ORE ? 0.15f : 0;
			case EXPLORER -> s == Short.EXPLORE ? 0.45f : 0;
			case FRIENDS -> s == Short.EXPLORE ? 0.1f : 0;
			case DRAGON -> s == Short.ADVENTURE ? 0.5f : s == Short.MINE ? 0.2f : 0;
			case VILLAGE -> s == Short.HOUSE ? 0.3f : s == Short.FARM ? 0.2f : s == Short.STORE ? 0.1f : 0;
		};
	}

	/** How did it go? What it gained (or, for a shelter, whether it got built) moves its liking for that goal. */
	private void learn() {
		boolean built = c.chores.shelterBuilt;
		float outcome = current == Short.SHELTER ? (built ? 1f : 0f) : Math.min(1f, Math.max(0f, (c.genReward - rewardAtStart) / 2f));
		liking.merge(current, 0f, (a, b) -> a + 0.2f * (outcome - a));
		if (buildingHome && built) {
			home = c.player.blockPosition();
			checkDream();
		}
		buildingHome = false;
		c.chores.own = false;
		current = null;
		if (option >= 0) finishOption(false);
	}

	/** No new goal for a while (it's catching up with its friend). */
	void pause(int ticks) {
		next = Math.max(next, c.player.level().getGameTime() + ticks);
	}

	/** Someone asked it for something: that comes first. */
	void drop() {
		current = null;
		buildingHome = false;
		c.chores.own = false;
		if (option >= 0) finishOption(false);
	}

	// ----------------------------------------------------------------------------- Xen 2.0
	/** Xen 2.0's choice now (a {@link Mind} option), what it knew when it chose, when, until when; -1: none. */
	int option = -1;
	private float[] optionFeatures;
	private long optionAt, optionUntil;
	private java.util.UUID followWho;
	private Companion guardWho;
	private Vec3 fleeFrom;
	String optionHow = "";

	/** Does Xen 2.0 decide for it (the setting; minions keep the small brain)? */
	boolean useMind() {
		return c.mod.mind != null && !c.minion && "xen2".equals(c.mod.config.brain);
	}

	/** Leanings on top of what Xen 2.0 values: its village's rules and job, and what it's good at (people do what they're good at). */
	private float[] bias(Tribe t) {
		float[] b = t == null ? new float[Mind.N] : t.laws.bias(c);
		Skills k = c.skills;
		b[Mind.MINE] += 0.3f * (k.get(Skills.MINE) - 0.5f);
		b[Mind.HOUSE] += 0.3f * (k.get(Skills.BUILD) - 0.5f);
		b[Mind.FARM] += 0.3f * (k.get(Skills.FARM) - 0.5f);
		b[Mind.TRADE] += 0.3f * (k.get(Skills.TRADE) - 0.5f);
		b[Mind.FIGHT] += 0.2f * (k.get(Skills.FIGHT) - 0.5f);
		b[Mind.GUARD] += 0.2f * (k.get(Skills.FIGHT) - 0.5f);
		return b;
	}

	private boolean thinkMind(boolean nearby, long now) {
		if (option >= 0 && current == null) {                              // one of its own kind of choices (rest, follow, eat, help...)
			if (!extraOver(now)) return c.chores.busy();
			finishOption(false);
			next = now;
		}
		if (option >= 0) return c.chores.busy();
		if (now < next) return false;
		next = now + 60;                                                       // (if nothing starts, it looks again in 3 s)
		Mind mind = c.mod.mind;
		float[] f = MindSense.features(c);
		boolean[] can = MindSense.allowed(c, f, nearby);
		float caution = c.personality.cautionScale() * (0.7f + 0.6f * c.emotions.fear);
		float explore = c.mod.config.learn ? 0.02f + 0.06f * c.personality.curiosity : 0.01f;
		for (int tries = 0; tries < 5; tries++) {
			Tribe t = c.tribe();
			Mind.Choice ch = mind.choose(f, can, caution, explore, random, bias(t));
			if (!startOption(ch.option, nearby, now)) {                       // it couldn't after all: the next best
				can[ch.option] = false;
				continue;
			}
			option = ch.option;
			optionFeatures = f;
			optionAt = now;
			optionHow = ch.how;
			c.journal("thinks", "Xen 2.0 chose to " + Mind.SAYS[option] + " (" + ch.how + ")");
			return c.chores.busy();
		}
		return false;
	}

	private static final Short[] AS_SHORT = new Short[Mind.N];
	static {
		AS_SHORT[Mind.WOOD] = Short.WOOD;
		AS_SHORT[Mind.STONE] = Short.STONE;
		AS_SHORT[Mind.FOOD] = Short.FOOD;
		AS_SHORT[Mind.SHELTER] = Short.SHELTER;
		AS_SHORT[Mind.HOUSE] = Short.HOUSE;
		AS_SHORT[Mind.FARM] = Short.FARM;
		AS_SHORT[Mind.MINE] = Short.MINE;
		AS_SHORT[Mind.SMELT] = Short.SMELT;
		AS_SHORT[Mind.STORE] = Short.STORE;
		AS_SHORT[Mind.EXPLORE] = Short.EXPLORE;
		AS_SHORT[Mind.TRADE] = Short.TRADE;
		AS_SHORT[Mind.ADVENTURE] = Short.ADVENTURE;
	}

	/** Start what Xen 2.0 chose. False if it can't right now. */
	private boolean startOption(int o, boolean nearby, long now) {
		if (AS_SHORT[o] != null) {
			if (o == Mind.ADVENTURE && dragonDown && c.voyager.wantsQuest()) {    // the dragon's beaten: next, an elytra in the End
				c.voyager.startQuest();
				c.chatter("Next adventure: an elytra from an End city!", true);
				optionUntil = now + 20 * 60 * 30;
				return true;
			}
			if (o == Mind.EXPLORE && c.personality.curiosity > 0.6f && !c.nether.busy() && c.places.get("portal") != null && random.nextFloat() < 0.3f) {
				String trip = c.nether.go("visit", 0);                           // every world: a curious one goes to see the Nether
				if (trip.startsWith("You will")) {
					optionUntil = now + 20 * 60 * 3;
					c.chatter("I want to see more of the Nether.", false);
					return true;
				}
			}
			if (o == Mind.HOUSE && home == null && c.moveIn()) {               // living together instead: its (and their) call
				optionUntil = now + 40;
				return true;
			}
			if (o == Mind.SHELTER && home != null && !c.player.blockPosition().closerThan(home, 12)) {   // a home: it goes back there for the night
				optionUntil = now + 1200;
				followWho = null;
				return true;
			}
			return begin(AS_SHORT[o], nearby, now);
		}
		switch (o) {
			case Mind.REST -> {
				optionUntil = now + 100 + random.nextInt(200);
				return true;
			}
			case Mind.CRAFT -> {
				String u = c.crafter.upgrade();
				if (u == null) return false;
				c.crafter.orderRecipe(u, u.equals("torch") ? 4 : 1);
				optionUntil = now + 600;
				c.chatter("I'll make " + (u.endsWith("s") ? "" : "aeiou".indexOf(u.charAt(0)) >= 0 ? "an " : "a ") + u.replace('_', ' ') + ".", false);
				return c.crafter.hasOrder();
			}
			case Mind.EAT -> {
				optionUntil = now + 200;
				return c.chores.eat().startsWith("You will");
			}
			case Mind.SLEEP -> {
				optionUntil = now + 12000;
				return c.hasBed();
			}
			case Mind.FOLLOW -> {
				ServerPlayer f = MindSense.friend(c);
				if (f == null) return false;
				followWho = f.getUUID();
				optionUntil = now + 600;
				return true;
			}
			case Mind.HELP -> {
				String plan = c.needs.help();
				if (plan == null || !plan.startsWith("You will")) return false;
				c.chatter(xen.mod.talk.Chat.firstPerson(plan), true);
				return true;
			}
			case Mind.GUARD -> {
				Tribe t = c.tribe();
				guardWho = t == null ? null : t.inDanger(c);
				if (guardWho == null) return false;
				optionUntil = now + 600;
				c.chatter(c.pick3("Hang on, " + guardWho.name + "! I'm coming!", "Nobody touches " + guardWho.name + ".", "On my way, " + guardWho.name + "!"), true);
				return true;
			}
			case Mind.FIGHT -> {
				LivingEntity foe = MindSense.threat(c);
				if (foe == null) foe = MindSense.enemy(c);
				if (foe == null) return false;
				c.chosenFoe = foe;
				if (foe instanceof ServerPlayer) c.lastPickedFight = now;
				optionUntil = now + 400;
				if (foe instanceof ServerPlayer p) {
					c.journal("fight", "picks a fight with " + p.getName().getString() + " (its own choice)");
					c.chatter(c.personality.say("fight"), true);
				}
				return true;
			}
			case Mind.FLEE -> {
				LivingEntity from = MindSense.threat(c);
				if (from == null) from = MindSense.enemy(c);
				if (from == null) return false;
				fleeFrom = from.position();
				optionUntil = now + 160;
				c.chatter(c.personality.say("flee"), false);
				return true;
			}
			case Mind.ENCHANT -> {
				optionUntil = now + 2400;
				return c.enchanter.start().startsWith("You will");
			}
			default -> {
				return false;
			}
		}
	}

	/** Is one of its own kind of choices (not a short goal) over? */
	private boolean extraOver(long now) {
		boolean late = now > optionUntil;
		return switch (option) {
			case Mind.CRAFT -> !c.crafter.hasOrder() || late;
			case Mind.EAT -> !c.chores.busy() || late;
			case Mind.SLEEP -> !c.player.level().isDarkOutside() && !c.player.isSleeping() || late;
			case Mind.HELP -> !c.chores.busy();
			case Mind.FIGHT -> late || c.chosenFoe == null || !c.chosenFoe.isAlive() || c.chosenFoe.level() != c.player.level()
					|| c.chosenFoe.distanceTo(c.player) > 24 || c.diplomacy.atPeace(c.chosenFoe);
			case Mind.ENCHANT -> !c.enchanter.on || late;
			case Mind.SHELTER -> late || home != null && c.player.blockPosition().closerThan(home, 6);
			default -> late;
		};
	}

	/** Its own kind of choices, this moment (null: nothing to do for it now; it rests, looks about). */
	Action mindStep() {
		if (option < 0 || current != null) return null;
		ServerPlayer p = c.player;
		switch (option) {
			case Mind.FOLLOW -> {
				ServerPlayer f = followWho == null ? null : c.server.getPlayerList().getPlayer(followWho);
				if (f == null || f.level() != p.level()) return null;
				instant = "staying with " + f.getName().getString();
				if (f.distanceTo(p) > 4) {
					c.run(f.distanceTo(p) > 10);
					return c.walkTo(f.position());
				}
				return null;
			}
			case Mind.GUARD -> {
				if (guardWho == null || guardWho.player == null) return null;
				instant = "guarding " + guardWho.name;
				if (guardWho.player.distanceTo(p) > 5) {
					c.run(true);
					return c.walkTo(guardWho.player.position());
				}
				return null;
			}
			case Mind.FIGHT -> {
				LivingEntity foe = c.chosenFoe;
				if (foe == null || !foe.isAlive()) return null;
				instant = "going after " + foe.getName().getString();
				if (foe.distanceTo(p) > p.entityInteractionRange()) {
					c.run(true);
					return c.walkTo(foe.position());
				}
				return null;                                                   // in reach: its fighting instinct takes over
			}
			case Mind.FLEE -> {
				if (fleeFrom == null) return null;
				instant = "getting away";
				Vec3 away = p.position().subtract(fleeFrom);
				if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
				c.run(true);
				return c.walkTo(p.position().add(away.normalize().scale(12)));
			}
			case Mind.SHELTER -> {
				if (home == null) return null;
				instant = "going home for the night";
				c.run(p.blockPosition().distSqr(home) > 400);
				return c.walkTo(Vec3.atBottomCenterOf(home));
			}
			case Mind.ENCHANT -> {
				instant = "enchanting";
				return c.enchanter.next();
			}
			case Mind.EAT -> {
				return c.chores.busy() ? c.chores.next() : null;
			}
			case Mind.HELP -> {
				return c.chores.busy() ? c.chores.next() : null;
			}
			default -> {
				return null;                                                   // rest, craft (its crafting goes on its own), sleep (its bedtime habit)
			}
		}
	}

	/** Its choice is over (or it died): how did it go? Xen 2.0 remembers and learns from it. */
	void finishOption(boolean died) {
		int o = option;
		float[] before = optionFeatures;
		option = -1;
		optionFeatures = null;
		c.chosenFoe = null;
		fleeFrom = null;
		guardWho = null;
		followWho = null;
		if (o < 0 || before == null || c.mod.mind == null || c.player == null) return;
		long now = c.player.level().getGameTime();
		float minutes = Math.max(0.05f, (now - optionAt) / 1200f);
		float[] after = MindSense.features(c);
		if (died) after[Mind.HEALTH] = 0;
		float r = Mind.reward(before, after, o, minutes), h = Mind.harm(before, after, died);
		c.mod.mind.remember(before, o, r, h, after, died, minutes, Mind.bits(MindSense.allowed(c, after, false)));
		c.mod.mindExperiences++;
		if (o == Mind.MINE && c.places.get("mine hut") == null && c.places.get("mine") != null && !c.builder.busy() && !c.player.isCreative()
				&& c.chores.mineRecordY != Integer.MIN_VALUE && c.player.blockPosition().closerThan(c.places.get("mine"), 24)) {   // its mine gets a proper entrance
			String hut = c.builder.startPlan(Architect.mineEntrance(c.places.get("mine"), c.chores.mineRecordDir,
					Architect.ofWood(MindSense.count(c, n -> n.startsWith("spruce_")) > 8 ? "spruce" : "oak", true, false)), "", "mine");
			c.chatter("My mine needs a proper entrance. " + xen.mod.talk.Chat.firstPerson(hut), false);
		}
		Tribe t = c.tribe();
		if (t != null) t.laws.didWork(c, o);
		c.journal("learns", String.format(Locale.ROOT, "Xen 2.0: %s took %.1f min, reward %+.2f, harm %.2f%s", Mind.OPTIONS[o], minutes, r, h, died ? " (died)" : ""));
	}

	// -------------------------------------------------------------------------------- words
	/** For its notes (the chat model): all three, and only what's true. */
	String describe() {
		StringBuilder sb = new StringBuilder();
		if (!instant.isEmpty()) sb.append("Right now you are ").append(instant).append(". ");
		if (current != null) sb.append("You want to ").append(current.what).append(", because ").append(current.why).append(". ");
		if (dream != null) {
			sb.append(String.format(Locale.ROOT, "Your dream is to %s (%.0f%% of the way).", dream.what, 100 * progress()));
			if (home != null) sb.append(" Your home is at ").append(home.getX()).append(", ").append(home.getZ()).append('.');
		}
		return sb.toString().trim();
	}

	/** For /xen status: "now: fighting the zombie; soon: get some wood; dream: build a home (40%)". */
	String status() {
		return String.format(Locale.ROOT, "now: %s; soon: %s; dream: %s", instant.isEmpty() ? "looking around" : instant,
				current == null ? "nothing in particular" : current.what,
				dream == null ? "not yet" : String.format(Locale.ROOT, "%s (%.0f%%)", dream.what, 100 * progress()));
	}

	/** Its likes in words: "likes to explore and look for ore most". */
	String likes() {
		Short a = null, b = null;
		for (Short s : Short.values()) {
			if (a == null || liking.get(s) > liking.get(a)) {
				b = a;
				a = s;
			} else if (b == null || liking.get(s) > liking.get(b)) {
				b = s;
			}
		}
		return "likes to " + a.what + " and " + b.what + " most";
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		liking.forEach((s, v) -> o.addProperty(s.name().toLowerCase(Locale.ROOT), v));
		return o;
	}

	JsonObject dreamJson() {
		JsonObject o = new JsonObject();
		if (dream != null) o.addProperty("dream", dream.name());
		o.addProperty("achieved", achieved);
		if (dragonDown) o.addProperty("dragonDown", true);
		if (home != null) {
			JsonArray h = new JsonArray();
			h.add(home.getX());
			h.add(home.getY());
			h.add(home.getZ());
			o.add("home", h);
		}
		if (origin != null) {
			JsonArray h = new JsonArray();
			h.add(origin[0]);
			h.add(origin[1]);
			o.add("origin", h);
		}
		String[] names = {"farm", "mobfarm", "pen"};
		BlockPos[] spots = {farm, mobFarm, pen};
		for (int i = 0; i < names.length; i++) {
			if (spots[i] == null) continue;
			JsonArray h = new JsonArray();
			h.add(spots[i].getX());
			h.add(spots[i].getY());
			h.add(spots[i].getZ());
			o.add(names[i], h);
		}
		return o;
	}

	private static BlockPos at(JsonArray h) {
		return new BlockPos(h.get(0).getAsInt(), h.get(1).getAsInt(), h.get(2).getAsInt());
	}

	void load(JsonObject likes, JsonObject dreams) {
		if (likes != null) {
			for (Short s : Short.values()) {
				String k = s.name().toLowerCase(Locale.ROOT);
				if (likes.has(k)) liking.put(s, likes.get(k).getAsFloat());
			}
		}
		if (dreams == null) return;
		if (dreams.has("dream")) {
			try {
				dream = Long.valueOf(dreams.get("dream").getAsString());
			} catch (IllegalArgumentException ignored) {
				dream = null;
			}
		}
		if (dreams.has("achieved")) achieved = dreams.get("achieved").getAsInt();
		dragonDown = dreams.has("dragonDown") && dreams.get("dragonDown").getAsBoolean();
		if (dreams.has("home")) {
			JsonArray h = dreams.getAsJsonArray("home");
			home = new BlockPos(h.get(0).getAsInt(), h.get(1).getAsInt(), h.get(2).getAsInt());
		}
		if (dreams.has("farm")) farm = at(dreams.getAsJsonArray("farm"));
		if (dreams.has("mobfarm")) mobFarm = at(dreams.getAsJsonArray("mobfarm"));
		if (dreams.has("pen")) pen = at(dreams.getAsJsonArray("pen"));
		if (dreams.has("origin")) {
			JsonArray h = dreams.getAsJsonArray("origin");
			origin = new int[] {h.get(0).getAsInt(), h.get(1).getAsInt()};
		}
	}
}
