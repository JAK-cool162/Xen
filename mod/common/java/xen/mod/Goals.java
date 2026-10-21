package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;
import xen.mod.core.Mind;
import xen.mod.core.Sins;
import xen.mod.core.Strategy;

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
		SMELT("smelt the iron", "raw iron has to be smelted before you can make tools of it"),
		FARM("build a farm", "a farm by the house means food without hunting"),
		MOBFARM("build a mob farm", "a mob farm brings bones, string, gunpowder and experience to the door"),
		STORE("put things away in a chest", "your bag is getting full"),
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
	/** When the short goal going now began. */
	private long goalAt;

	/** Think again now (not in a few seconds): it just finished something that wasn't a goal (its first look around). */
	void thinkNow() {
		next = 0;
	}
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
		reviewPlan(now);
		if (current != null) {
			if (current == Short.EXPLORE && now > until && !progressWaiting() && c.g1x.size() > exploreKnown + 600) {   // still seeing new ground: on, as long as there's more
				exploreKnown = c.g1x.size();
				until = now + EXPLORE_TICKS;
			}
			boolean over = current == Short.EXPLORE ? now > until || progressWaiting() && now > until - EXPLORE_TICKS / 2 && now >= exploreAskedUntil
					: current == Short.FARM ? !c.farmer.on && !c.chores.busy()
					: building(current) ? !c.builder.busy() : current == Short.STORE ? !c.storage.busy()
					: current == Short.ADVENTURE ? !c.adventure.on : current == Short.TRIALS ? !c.trials.on : !c.chores.busy();
			if (!over) {
				c.focus.watch();
				return c.chores.busy() && !building(current);
			}
			if (current == Short.MINE && c.guide.leadingTo("diamond") && diamonds() == 0 && c.player.getFoodData().getFoodLevel() >= 8 && c.player.getHealth() >= 10) {
				String plan = chore(Short.MINE);                                     // its guide is taking it to diamonds: on with that (a player who knows where they are doesn't go off to build first)
				if (!cant(plan)) {
					c.chores.own = true;
					return c.chores.busy();
				}
			}
			if (Focus.WATCHED.contains(current) && (c.focus.holding() || c.focus.cutOff(current) && c.focus.hold(current.what))) {   // cut off, not over: it holds on
				String stale = c.focus.stale();
				if (stale != null) {
					c.journal("focus", "lets go of " + current.what + ": " + stale);
					c.focus.letGo++;
				} else if (!c.focus.quiet()) {
					return false;                                                 // (whatever cut it off has its turn)
				} else {
					String plan = chore(current);
					if (!cant(plan)) {
						c.chores.own = true;
						c.focus.back(current.what);
						return c.chores.busy();
					}
					c.journal("focus", "can't get back to " + current.what + ": " + plan);
				}
			}
			learn();
			next = now;                                                      // done: straight on to the next thing, like a player
		}
		if (option < 0 && c.chores.busy() && (c.chores.forWool && c.chores.kind == Chores.Kind.HUNT || now < c.bedErrandUntil)) return true;   // (a bed for the night: that first, nothing new over it)
		if (useMind()) return thinkMind(nearby, now);
		if (now < next) return false;
		next = now + THINK;
		Short best = null;
		float bestScore = 0.25f;                                         // below this it's happy doing whatever it does
		for (Short s : Short.values()) {
			if (c.focus.resting(s)) continue;                                    // (it came to nothing at once, just now)
			if (nearby && (s == Short.TRADE || s == Short.EXPLORE || s == Short.MINE || s == Short.HOUSE || s == Short.FARM || s == Short.MOBFARM
					|| s == Short.ADVENTURE || s == Short.TRIALS || s == Short.STORE)) continue;   // those would take it away from its friend
			float score = (urgency(s) + dreamPull(s)) * (0.5f + liking.get(s)) + random.nextFloat() * 0.15f * c.personality.curiosity;
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		if (best == null) {                                                    // nothing pressing: a friend building near? it lends a hand
			Companion friend = nearby || evening() ? null : c.builder.friendBuilding();
			if (friend != null && random.nextFloat() < 0.15f + 0.5f * c.personality.kindness && c.builder.help(friend).startsWith("You will")) {
				current = Short.HOUSE;
				rewardAtStart = c.genReward;
				until = now + EXPLORE_TICKS;
				c.chores.own = true;
				c.chatter(c.pick3("I'll help you, " + friend.name + "!", "Need a hand, " + friend.name + "?", "Let's build it together, " + friend.name + "."), false);
			}
			return false;
		}
		return begin(best, nearby, now) && c.chores.busy();
	}

	/** Get on with a short goal (it chose it, or Xen 2.0 did). False if it can't right now. */
	private boolean begin(Short best, boolean nearby, long now) {
		buildingHome = best == Short.SHELTER && dream == Long.HOME && home == null && !c.player.level().isDarkOutside();
		String plan = chore(best);
		if (!nearby) c.mode = Companion.Mode.FREE;                      // (a shelter would have it stay; it's still free)
		else if (best == Short.SHELTER) c.mode = Companion.Mode.FOLLOW;   // it builds it where it is, next to its friend
		if (cant(plan)) {
			if (Companion.DEBUG) XenMod.LOG.info("[xen debug] {} wanted to {}, but: {}", c.name, best.what, plan);
			liking.merge(best, -0.05f, (a, b) -> Math.max(0f, a + b));       // it can't right now: a little less keen
			c.chores.cancel();
			return false;
		}
		current = best;
		rewardAtStart = c.genReward;
		until = now + EXPLORE_TICKS;
		goalAt = now;
		c.chores.own = true;
		c.focus.started(best);
		c.chatter(buildingHome ? "I'm going to build my home here!" : nearby ? pickNearby(best) : "I want to " + best.what + ".", nearby);
		return true;
	}

	private static boolean cant(String plan) {
		return plan.startsWith("You can't") || plan.startsWith("You have no") || plan.startsWith("You don't");
	}

	/** Its goal's work, set going: what it's told it will do ("You can't ..." if it can't). */
	private String chore(Short best) {
		return switch (best) {
			case FOOD -> food();
			case SHELTER -> buildingHome ? c.chores.shelter("fort") : c.chores.shelter();
			case WOOD -> c.chores.gather("wood", c.crafter.pickTier() == 0 ? 5 : 10);
			case STONE -> needBlocksForTheNight() || c.crafter.pickTier() == 0 ? c.chores.gather("dirt", Math.max(4, NIGHT_BLOCKS + 2 - blocks()))
					: c.chores.gather("stone", 16);
			case ORE -> c.chores.gather("ore", 2);
			case HOUSE -> {
				Companion friend = c.builder.friendBuilding();                   // a friend building close by: it helps first (shared progress)
				if (friend != null && (home != null || random.nextFloat() < c.personality.kindness)) {
					String h = c.builder.help(friend);
					if (h.startsWith("You will")) {
						c.chatter(c.pick3("I'll help you, " + friend.name + "!", "Need a hand, " + friend.name + "?", "Let's build it together, " + friend.name + "."), false);
						yield h;
					}
				}
				if (home != null && c.builder.canUpgrade()) {                       // its own house first: the next stage (depth, details, polish)
					String up = c.builder.upgrade();
					if (up.startsWith("You will")) yield up;
				}
				Tribe t = c.tribe();
				BlockPos plot = t != null && t.members.size() > 1 && t.center != null ? t.plot() : home != null ? nextTo(home, 14) : null;   // in its tribe's village
				Companion neighbor = plot == null ? crowdedBy() : null;
				if (neighbor != null) {                                            // an SMP: someone's base is right here; its own land first
					BlockPos n = neighbor.goals.home;
					heading = Math.atan2(c.player.getZ() - n.getZ(), c.player.getX() - n.getX());
					exploreTo = null;
					yield "You can't: " + neighbor.name + "'s base is right here. You'll find land of your own first.";
				}
				String h = plot != null ? c.builder.startNear("house", plot, t != null && t.members.size() > 1 ? t.plotFront : null) : c.builder.start("home");   // (its own land: maybe a whole place)
				yield h.startsWith("You will") ? h : "You can't: " + h;
			}
			case STORE -> c.storage.busy() ? "You will see to your chest." : c.storage.store();   // (a job it already started: leaving valuables, taking spares)
			case ADVENTURE -> c.adventure.start();
			case TRIALS -> c.trials.start();
			case MINE -> fuelRun && (c.chores.fuel() == 0 || c.mod.config.runner) ? fuelRun() : c.crafter.pickTier() >= 3 && diamonds() < 3 && (readyForDiamonds() || diamondRun) ? c.chores.mine(c.lessons.depth("diamonds", -58), "diamonds", more(3) + (c.personality.believes("diamonds_forever") ? 2 : 0))
					: c.crafter.pickTier() >= 2 ? c.chores.mine(c.lessons.depth("iron", 16), "iron", more(6)) : c.chores.mine(c.lessons.depth("coal", 40), "coal", more(8));   // (an iron pickaxe: down for diamonds, the iron on the way comes too; greed: more; at the height it believes)
			case SMELT -> c.chores.smelt();
			case FARM -> {                                                       // (a real farm: tilled, planted, looked after; then a pen for the animals about)
				if (c.farmer.middle == null) yield c.farmer.start();
				String h = c.builder.startNear("animal pen", nextTo(home, 16));
				yield h.startsWith("You will") ? h : "You can't: " + h;
			}
			case MOBFARM -> {
				String h = c.builder.startNear("mob farm", nextTo(home, 24));
				yield h.startsWith("You will") ? h : "You can't: " + h;
			}
			case TRADE -> c.trader.withVillager(null);
			case EXPLORE -> "";
		};
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
			case HOUSE -> home == null && !busyBuilding && firstHouseTime() && !evening() && !c.player.isCreative()   // (its first house: once it has iron, or from its second day; the first day is for tools)
					? 0.7f * (0.6f + p.diligence)
					: home != null && !busyBuilding && !evening() && c.builder.canUpgrade() && items.getOrDefault("log", 0) >= 16
					? 0.45f * (0.5f + p.diligence)                                        // its house, a stage better (like players do)
					: home != null && !busyBuilding && !evening() && !c.player.isCreative() && c.taste.wantsBigger(items.getOrDefault("log", 0))
					? 0.35f * (0.5f + p.diligence) : 0;                                // a better house once it has the wood
			case MINE -> tools >= 2 && iron < 6 && c.crafter.pickTier() < 3 ? 0.55f * (0.6f + p.bravery)
					: c.crafter.pickTier() >= 3 && diamonds() < 3 ? (readyForDiamonds() ? 0.5f : 0.4f) * (0.5f + p.bravery)
					: tools >= 1 && items.getOrDefault("coal", 0) < 4 && home != null ? 0.3f : 0;
			case FARM -> home != null && (c.farmer.middle == null || pen == null && animalsByHome() >= 2) && !busyBuilding && !evening() && tools >= 1 ? 0.55f * (0.5f + p.diligence) : 0;
			case MOBFARM -> home != null && farm != null && mobFarm == null && !busyBuilding && !evening()
					&& (c.player.isCreative() || items.getOrDefault("cobblestone", 0) >= 300) ? 0.45f * (0.5f + p.diligence) : 0;
			case SMELT -> (items.getOrDefault("raw_iron", 0) >= 3 || c.chores.rawFood() >= 3) && (items.getOrDefault("coal", 0) > 0 || items.getOrDefault("log", 0) > 1)
					&& (items.getOrDefault("cobblestone", 0) >= 8 || items.getOrDefault("furnace", 0) > 0 || c.crafter.pickTier() >= 1) ? 0.85f : 0;
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
	/** Until when it's exploring because it was asked to (nothing of its own takes over, not even the dark). */
	long exploreAskedUntil;

	/**
	 * Asked to go exploring ("go explore"): it goes, now, for a few minutes, instead of whatever it had in mind (it
	 * used to say "okay" and then go back to its smelting, or build a hut for the night).
	 */
	String exploreAsked(long ticks) {
		String plan = exploreFor(ticks);
		if (plan.startsWith("You will")) exploreAskedUntil = c.player.level().getGameTime() + ticks;
		return plan;
	}

	/** Off exploring for a while now (its own idea: stood about too long), whatever it had in mind. */
	String exploreFor(long ticks) {
		long now = c.player.level().getGameTime();
		drop();
		c.chores.cancel();
		if (!begin(Short.EXPLORE, false, now)) return "You can't go exploring right now.";
		until = now + ticks;
		if (useMind()) {
			option = Mind.EXPLORE;
			optionFeatures = MindSense.features(c);
			optionSituation = c.experience.situation();
			optionAt = now;
			optionUntil = now + ticks;
			optionHow = "to see new ground";
		}
		exploreTo = null;
		lookUntil = 0;
		return c.player.level().isDarkOutside() ? "You will go exploring, even though it's dark (carefully, a torch at the ready)."
				: "You will go exploring on your own.";
	}

	/** Where its plan wants it to go and look (a biome it saw that has what it needs), for its next exploring. */
	private Vec3 towardPending;
	private String towardName;

	void exploreToward(BlockPos at, String what) {
		towardPending = Vec3.atBottomCenterOf(at);
		towardName = what;
	}

	boolean exploringAsked() {
		return c.player != null && current == Short.EXPLORE && c.player.level().getGameTime() < exploreAskedUntil;
	}

	/** It swam out to sea that way: exploring goes the other way now. */
	void turnAround() {
		if (!Double.isNaN(heading)) heading += Math.PI;
		exploreTo = null;
	}

	/** Most of what it can see that way (every 8 blocks, as far as the world is there) is water: the sea. */
	private boolean seaThatWay(BlockPos from, double a, double r) {
		var level = (net.minecraft.server.level.ServerLevel) c.player.level();
		int seen = 0, wet = 0;
		for (double d = 8; d <= r; d += 8) {
			int x = (int) Math.round(from.getX() + Math.cos(a) * d), z = (int) Math.round(from.getZ() + Math.sin(a) * d);
			if (!level.hasChunkAt(new BlockPos(x, from.getY(), z))) break;
			seen++;
			if (XenMod.surface(level, x, z) == null) wet++;
		}
		return seen >= 2 && wet * 2 >= seen;
	}

	/** The place it's on its way to have a look at (exploring), and the ones it has seen up close. */
	private String curiousOf;
	private final java.util.Set<String> seenUpClose = new java.util.HashSet<>();
	private static final java.util.Set<String> OWN_PLACES = java.util.Set.of("home", "mine", "mine base", "bed", "diamonds", "spawn", "death", "chest", "base", "farm",
			"pen", "portal", "enchanting table", "mobfarm", "mob farm", "furnace", "crafting table", "sugar cane");   // (its own: nothing new to see there)

	/** The nearest place it spotted (a village, a temple, ruins...) within 160 blocks, in this world, that it hasn't been to; null if none. */
	private Places.Place curiousAbout() {
		BlockPos feet = c.player.blockPosition();
		for (Places.Place pl : c.places.all()) {
			if (!pl.dim().equals(c.places.here()) || pl.pos().distSqr(feet) > 160 * 160) continue;
			String kind = pl.name().replaceAll(" \\d+$", "");
			if (OWN_PLACES.contains(kind) || kind.startsWith("mine") || seenUpClose.contains(pl.name())) continue;
			if (pl.pos().distSqr(feet) < 10 * 10) {                            // (there already)
				seenUpClose.add(pl.name());
				continue;
			}
			return pl;
		}
		return null;
	}

	Action exploreStep() {
		var p = c.player;
		long now = p.level().getGameTime();
		if (home != null && evening() && now >= exploreAskedUntil && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
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
				if (tier >= 1 && !evening() && now >= nextMineUnderground && now >= exploreAskedUntil) {   // (asked to explore: up and out instead)
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
		if (exploreTo != null && (Math.hypot(p.getX() - exploreTo.x, p.getZ() - exploreTo.z) < 4 || now - exploreSince > 20 * 90)) {
			boolean sawIt = curiousOf != null;
			if (sawIt) {                                                    // there: it takes a good look (and doesn't come back to it)
				seenUpClose.add(curiousOf);
				c.journal("explores", "had a look at the " + curiousOf.replaceAll(" \\d+$", ""));
				curiousOf = null;
			}
			exploreTo = null;
			lookUntil = now + (sawIt ? 60 + random.nextInt(60) : 30 + random.nextInt(50));
			return Action.IDLE;
		}
		if (exploreTo == null && towardPending != null) {                   // its plan: the biome it saw that has what it needs
			exploreTo = towardPending;
			curiousOf = towardName;
			towardPending = null;
			exploreSince = now;
			instant = "going to the " + curiousOf + " (for its plan)";
			c.run(p.position().distanceTo(exploreTo) > 6);
			return c.walkTo(exploreTo);
		}
		if (exploreTo == null) {                                            // something it spotted and hasn't been to: a player goes to see it
			var poi = curiousAbout();
			if (poi != null) {
				curiousOf = poi.name();
				exploreTo = Vec3.atBottomCenterOf(poi.pos());
				exploreSince = now;
				instant = "going to see the " + poi.name().replaceAll(" \\d+$", "");
				c.run(p.position().distanceTo(exploreTo) > 6);
				return c.walkTo(exploreTo);
			}
		}
		if (exploreTo == null) {
			if (Double.isNaN(heading)) heading = random.nextDouble() * Math.PI * 2;
			double lucky = c.hunch.exploreWay();                                // (its feet just go the lucky way, now and then)
			if (!Double.isNaN(lucky)) heading = lucky;
			for (int tries = 0; tries < 8 && exploreTo == null; tries++) {
				double a = heading + (random.nextDouble() - 0.5) * (tries < 4 ? 0.7 : Math.PI * 2);   // (it keeps going one way: far, not round in circles)
				double r = 40 + random.nextInt(40) + 60 * c.personality.curiosity;
				BlockPos from = p.blockPosition();
				int x = (int) Math.round(from.getX() + Math.cos(a) * r), z = (int) Math.round(from.getZ() + Math.sin(a) * r);
				if (!p.level().hasChunkAt(new BlockPos(x, from.getY(), z))) {          // land nobody has seen yet: it walks that way, and sees
					if (seaThatWay(from, a, r)) continue;                             // (not out to sea)
					exploreTo = new Vec3(x + 0.5, p.getY(), z + 0.5);
					heading = a;
					break;
				}
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

	/**
	 * On a server with other Xens, like a real SMP: another Xen's base (not a teammate's, not its village's) within 64
	 * blocks of here. Null when there's room.
	 */
	Companion crowdedBy() {
		if (home != null || c.mod.companions.size() < 2) return null;
		Tribe mine = c.tribe();
		String team = c.mod.teamOf(c);
		for (Companion o : c.mod.companions) {
			if (o == c || o.goals.home == null || o.player() == null || o.player().level() != c.player.level()) continue;
			if (mine != null && mine.members.contains(o)) continue;
			if (team != null && c.mod.ownTeam(team) && team.equals(c.mod.teamOf(o))) continue;
			if (o.goals.home.closerThan(c.player.blockPosition(), 64)) return o;
		}
		return null;
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

	/**
	 * Food, its way: "fish are free food" (or "animals deserve kindness") fishes when it can; "animals deserve kindness"
	 * only hunts when it's really hungry; everyone else hunts.
	 */
	private String food() {
		Personality p = c.personality;
		boolean kind = p.believes("animals_kindness");
		var prey = c.chores.nearestAnimal();
		boolean noAnimals = prey == null || prey.distanceTo(c.player) > 16;
		if ((kind || p.believes("fish_free_food") || noAnimals) && c.fisher.fancies()) {   // (Xen 2.0: a rod and water and no animal close: anyone fishes)
			String f = c.fisher.start();
			if (f.startsWith("You will")) return f;
		}
		if (kind && c.player.getFoodData().getFoodLevel() > 6) {
			String f = c.farmer.start();                                     // (a farm, not a hunt)
			if (f.startsWith("You will")) {
				c.journal("thinks", "won't hurt the animals (\"animals deserve kindness\"): farms instead");
				return f;
			}
		}
		return c.chores.hunt(2);
	}

	/** How much ore it goes down for: a greedy Xen wants more (up to twice as much). */
	private int more(int n) {
		return Math.round(n * (1 + c.personality.sin(Sins.GREED)));
	}

	/** Iron, nothing to burn it with: down for coal (its guide shows the way to some), a little only. */
	private boolean fuelRun;
	private long coalTriedAt = -10000;

	private String fuelRun() {
		fuelRun = false;
		coalTriedAt = now();
		return c.chores.mine(c.lessons.depth("coal", 40), "coal", 4);
	}

	/**
	 * An iron pickaxe: diamonds now, or armor first? Armor first, unless it's good with a shield and an axe (then that's
	 * enough). Its nature has a say too: a confident one sees what it could get (and goes, with a shield at least), a
	 * proud one sees only itself (and goes, ready or not).
	 */
	boolean readyForDiamonds() {
		boolean armored = c.crafter.has("_chestplate"), shield = c.crafter.hasShield();
		float fight = c.skills.get(Skills.FIGHT);
		boolean shieldAndAxe = shield && c.crafter.has("_axe") && fight >= 0.5f;
		boolean confident = shield && c.personality.bravery > 0.7f, ego = c.personality.sin(Sins.PRIDE) > 0.7f;
		return armored || shieldAndAxe || confident || ego || c.items().getOrDefault("iron_ingot", 0) + c.items().getOrDefault("raw_iron", 0) >= 24;
	}

	private int diamonds() {
		return c.items().getOrDefault("diamond", 0);
	}

	private int blocks() {
		var items = c.items();
		return items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
	}

	/** Evening (or night already) and too few blocks for a shelter: a player gets some dirt before dark. */
	/** Blocks for a night's shelter: a little hut with room inside takes about this many. */
	static final int NIGHT_BLOCKS = 24;

	boolean needBlocksForTheNight() {
		return evening() && blocks() < NIGHT_BLOCKS;
	}

	/** Its first house: once it has iron, or from its second day with stone tools (the first day is for tools and iron). */
	boolean firstHouseTime() {
		return c.crafter.pickTier() >= 3 || c.crafter.pickTier() >= 2 && livedDays() >= 2;
	}

	/** Whole days it has lived (from its first day in the world). */
	long livedDays() {
		long today = c.player.level().getGameTime() / 24000;
		return c.life.bornDay < 0 ? 0 : Math.max(1, today - c.life.bornDay + 1);   // (bornDay is the first new day it saw: that's day 1)
	}

	/** The sun going down for real (beds work from 12542, monsters come at about 13000), or dark already: time to go in. */
	boolean sunDown() {
		var level = c.player.level();
		long time = Compat.timeOfDay(level);
		return time >= 12500 && time < 23500 || level.isDarkOutside();
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
		Short was = current;
		current = null;
		boolean worked = option >= 0 ? finishOption(false) : outcome > 0.05f;
		if (was != null) c.focus.ended(was, worked, c.player.level().getGameTime() - goalAt);
	}

	/** No new goal for a while (it's catching up with its friend). */
	void pause(int ticks) {
		next = Math.max(next, c.player.level().getGameTime() + ticks);
	}

	/**
	 * It said yes to building what someone asked for: that's its goal now, till it's built, the way a player who agreed
	 * keeps at it instead of drifting off to whatever else comes to mind (what's pressing, a fight, the night, hunger,
	 * still comes first, as it always does).
	 */
	void adoptBuild() {
		drop();
		current = Short.HOUSE;
		rewardAtStart = c.genReward;
	}

	/** Someone asked it for something: that comes first. */
	void drop() {
		exploreAskedUntil = 0;
		current = null;
		c.focus.forget();
		buildingHome = false;
		c.chores.own = false;
		if (option >= 0) finishOption(false);
	}

	// ----------------------------------------------------------------------------- Xen 2.0
	/** Xen 2.0's choice now (a {@link Mind} option), what it knew when it chose, when, until when; -1: none. */
	int option = -1;
	/** The option it chose before this one. */
	private int lastOption = -1;
	/** What it did lately (its last few choices, newest last). */
	final java.util.ArrayDeque<Integer> done = new java.util.ArrayDeque<>();

	/** Was its last choice a breather (it doesn't take one after another in daylight)? */
	boolean restedLast() {
		return lastOption == Mind.REST || option == Mind.REST;
	}
	private float[] optionFeatures;
	private long optionAt, optionUntil;
	private java.util.UUID followWho;
	private Companion guardWho;
	private Vec3 fleeFrom;
	String optionHow = "";
	/** Why it chose what it's doing, in words it can say ("last time I went mining at night I died", "I need wood for a pickaxe"). */
	String optionSays = "";

	/** Does Xen 2.0 decide for it (the setting; minions keep the small brain)? */
	boolean useMind() {
		return c.mod.mind != null && !c.minion && "xen2".equals(c.mod.config.brain);
	}

	/** Leanings on top of what Xen 2.0 values: its village's rules and job, and what it's good at (people do what they're good at). */
	private float[] bias(Tribe t) {
		float[] b = t == null ? new float[Mind.N] : t.laws.bias(c);
		float[] nature = c.personality.bias();                                // its sins, beliefs and its own plan (Xen 6.0)
		for (int i = 0; i < Mind.N; i++) b[i] += nature[i];
		Skills k = c.skills;
		b[Mind.MINE] += 0.3f * (k.get(Skills.MINE) - 0.5f);
		b[Mind.HOUSE] += 0.3f * (k.get(Skills.BUILD) - 0.5f);
		b[Mind.FARM] += 0.3f * (k.get(Skills.FARM) - 0.5f);
		b[Mind.TRADE] += 0.3f * (k.get(Skills.TRADE) - 0.5f);
		b[Mind.FIGHT] += 0.2f * (k.get(Skills.FIGHT) - 0.5f);
		var st = c.fighter.style();                                            // (whether it starts a fight is its style's call: an explorer rather not, an opportunist sooner)
		b[Mind.FIGHT] += st == Fighter.Style.EXPLORER ? -0.25f : st == Fighter.Style.OPPORTUNIST ? 0.15f : 0;
		b[Mind.GUARD] += 0.2f * (k.get(Skills.FIGHT) - 0.5f);
		if (c.caves.nearest(160) != null && c.crafter.pickTier() >= 1) b[Mind.MINE] += 0.35f;   // a cave it knows: ore, easy
		if (crowdedBy() != null) {                                              // someone else's base here: off to find its own land
			b[Mind.EXPLORE] += 0.5f;
			b[Mind.HOUSE] -= 0.5f;
		}
		if (!c.player.level().isDarkOutside() && c.goals.home != null && c.crafter.pickTier() >= 1) {   // daytime: get things done
			b[Mind.WOOD] += 0.1f;
			b[Mind.STONE] += 0.1f;
			b[Mind.MINE] += 0.15f;
		}
		float[] group = c.purpose.lean();                                       // its village's priorities (its leader's way), as far as it goes along
		for (int i = 0; i < Mind.N; i++) b[i] += group[i];
		c.habits.bias(b);                                                       // its routine, what it's sick of, how stung it is
		c.rules.lean(b);                                                        // the rules it wrote itself; stuck: something it hasn't tried
		c.intent.lean(b);                                                       // what it promised, what it agreed to do with someone
		c.planner.lean(b);                                                      // what it's after next (a shield before night...), changed by what turns up
		c.teamRole.lean(b);                                                     // its part on its team (the base, the mine, the monsters, scouting)
		return b;
	}

	/** Until when each choice is off (one that ended the moment it began isn't picked again right away: no spinning on the spot). */
	private final long[] quickUntil = new long[Mind.N];
	/** How many times in a row each choice came to nothing. */
	private final int[] failsInRow = new int[Mind.N];

	/** Back from dying with nothing: a fresh start (what didn't work before it died isn't held against it now). */
	void freshStart() {
		java.util.Arrays.fill(failsInRow, 0);
		java.util.Arrays.fill(quickUntil, 0L);
	}

	private boolean thinkMind(boolean nearby, long now) {
		if (option >= 0 && current == null) {                              // one of its own kind of choices (rest, follow, eat, help...)
			if (!extraOver(now)) return c.chores.busy();
			int o = option;
			if (now - optionAt < 40 && o != Mind.EAT && o != Mind.FLEE) quickUntil[o] = now + 400;   // (over in under 2 s: nothing came of it)
			finishOption(false);
			next = now;
		}
		if (option >= 0) return c.chores.busy();
		if (c.visits.busy()) return false;                                     // (in a village it found, getting what it's good for: that first)
		if (now < next) return false;
		Mind mind = c.mod.mind;
		float[] f = MindSense.features(c);
		boolean[] can = MindSense.allowed(c, f, nearby);
		Plans.Step planNext = c.plans.advice();                                // (what its plan says next is something it can do: one of its choices, not an order)
		if (planNext != null && (planNext.option == Mind.CRAFT || planNext.option == Mind.SMELT)) can[planNext.option] = true;
		for (int i = 0; i < can.length && i < quickUntil.length; i++) if (now < quickUntil[i] || AS_SHORT[i] != null && c.focus.resting(AS_SHORT[i])) can[i] = false;
		float caution = c.personality.cautionScale() * (0.7f + 0.6f * c.emotions.fear);
		float explore = c.mod.config.learn ? 0.02f + 0.06f * c.personality.curiosity : 0.01f;
		int planned = agenda(f, can);                                          // the usual next step, like any player: advice, not an order
		String planWhy = agendaWhy;
		if (c.mod.config.runner && planned >= 0 && !planWhy.isEmpty() && planWhy.startsWith("runner: ")) {   // runner: the run's next step, at once
			next = now + 10;
			if (startOption(planned, nearby, now)) {
				c.chores.own = true;
				option = planned;
				c.habits.started(option);
				c.rules.started(option, f);
				optionFeatures = f;
				optionSituation = c.experience.situation();
				optionExpect = null;
				optionWhere = c.player.blockPosition().immutable();
				optionFollowed = true;
				optionAt = now;
				optionHow = "the run";
				optionSays = "";
				c.purpose.goal = planWhy.substring(8);
				c.journal("thinks", planWhy);
				return c.chores.busy();
			}
			quickUntil[planned] = now + 200;                                   // (it couldn't: the next step of the run, or its own call, in a moment)
		}
		float[] situation = c.experience.situation();
		next = now + thinkTicks(situation, f);                                 // (if nothing starts, it looks again when it's its own kind of quick)
		Tribe t = c.tribe();
		Mind.Choice ch = mind.choose(f, can, caution, 0, random, bias(t));      // what its mind makes of each (and, unsure, thinks through)
		Decision d = decide(f, can, caution, ch, planned, planWhy, situation);
		if (d.order.length == 0) return false;
		int need = d.order[0] == Mind.REST ? neededFirst(f, can) : -1;       // hybrid: a breather with something needed waiting, that first (what runners do)
		for (int i = 1; need >= 0 && i < d.order.length; i++) {
			if (d.order[i] != need) continue;
			System.arraycopy(d.order, 0, d.order, 1, i);
			d.order[0] = need;
			d.how[need] = "no standing about: " + Mind.SAYS[need] + " first";
			break;
		}
		if (ponderUntil == 0) {                                                // a new choice to make: it thinks it over first, as long as it needs
			long think = thinkTicks(situation, f, d.difficulty);
			if (think > 10) {
				ponderUntil = now + think;
				next = ponderUntil;
				instant = "thinking about what to do next";
				if (d.order.length > 1) c.journal("thinks", String.format(java.util.Locale.ROOT, "thinks it over (%.1f s): %s or %s", think / 20f,
						Mind.SAYS[d.order[0]], Mind.SAYS[d.order[1]]));
				return false;
			}
		}
		ponderUntil = 0;
		if (instant.equals("thinking about what to do next")) instant = "";
		int first = d.order[0];
		if (random.nextFloat() < explore && d.order.length > 1) {               // now and then something else, to find out
			first = d.order[1 + random.nextInt(d.order.length - 1)];
			d.how[first] = "let me try something different";
		}
		java.util.List<Integer> order = new java.util.ArrayList<>();
		order.add(first);
		for (int o : d.order) if (o != first) order.add(o);
		for (int tries = 0; tries < 5 && tries < order.size(); tries++) {
			int o = order.get(tries);
			if (!startOption(o, nearby, now)) continue;                          // it couldn't after all: the next best
			c.chores.own = true;                                               // (its own idea: what it's asked for comes first)
			option = o;
			c.habits.started(option);
			c.rules.started(option, f);
			optionFeatures = f;
			optionSituation = situation;
			optionExpect = d.expect[o];
			optionWhere = c.player.blockPosition().immutable();
			optionFollowed = o == planned;
			optionAt = now;
			optionHow = d.how[o];
			optionSays = d.says[o] == null ? "" : d.says[o];
			c.purpose.goal = o == planned ? planWhy.replaceAll(" \\(.*", "") : Mind.SAYS[o];
			c.journal("thinks", "Xen 2.0 chose to " + Mind.SAYS[option] + " (" + d.how[o] + ")");
			Experience.Prediction e = d.expect[o];
			if (e != null) c.journal("thinks", String.format(java.util.Locale.ROOT, "expects: goes well %.0f%%, risk %.0f/100%s", 100 * e.good(), 100 * e.risk(),
					e.minutes() > 0 ? String.format(java.util.Locale.ROOT, ", about %.1f min", e.minutes()) : ""));
			if (o == first && d.note != null) {
				c.journal("thinks", d.note);
				if (d.against != null) c.belief.up(0.01f, "its own call over the usual plan");
			}
			if (planned >= 0 && o != planned && d.against != null && now - saidAgainstAt > 20 * 60 * 2) {   // against the usual plan, from what happened before: it says so
				saidAgainstAt = now;
				c.chatter("I'd usually " + Mind.SAYS[planned] + " now, but " + d.against.replaceAll(" \\(.*\\)", "") + ". I'll " + Mind.SAYS[o] + " instead.", false);
			}
			return c.chores.busy();
		}
		return false;
	}

	/**
	 * Hybrid Xen (runner + Xen): the runner's lesson. A runner gears up fastest because it never stands about: the next
	 * thing needed, prepared before it's needed. So a Xen's own choice of a breather gives way to this: eat, craft,
	 * smelt its iron, wood with no pickaxe (by day), stone with a wooden one, mining with a pickaxe (at night too,
	 * unless it believes the night is the monsters'), food for the bag. Everything else it chooses stays its own
	 * (a stronger version, its side things too giving way until it was geared, did worse in the tests). -1: nothing
	 * needed (or danger, hurt, not its own time): the breather it is.
	 */
	private int neededFirst(float[] f, boolean[] can) {
		if (f[Mind.DANGER] > 0.3f || c.player.getHealth() < 8 || c.mode != Companion.Mode.FREE) return -1;
		var items = c.items();
		int tier = c.crafter.pickTier();
		boolean dark = f[Mind.NIGHT] > 0.5f, below = f[Mind.UNDERGROUND] > 0.5f;
		if (can[Mind.EAT] && c.player.getFoodData().getFoodLevel() < 14) return Mind.EAT;
		if (can[Mind.CRAFT]) return Mind.CRAFT;
		if (can[Mind.SMELT] && items.getOrDefault("raw_iron", 0) >= 1) return Mind.SMELT;
		if (tier == 0) return !dark && can[Mind.WOOD] ? Mind.WOOD : -1;
		if (tier == 1 && can[Mind.STONE] && (!dark || below)) return Mind.STONE;
		if (can[Mind.MINE] && (!dark || below || !c.personality.believes("night_belongs_to_monsters"))) return Mind.MINE;
		if (items.getOrDefault("food", 0) < 3 && !dark && can[Mind.FOOD]) return Mind.FOOD;
		return -1;
	}

	private long saidAgainstAt = -1_000_000;
	/** How much it knew when its exploring last got extended (G1X), and till when it's thinking a choice over. */
	private int exploreKnown;
	/** This death wasn't from anything it chose (a /kill, the void): not a lesson. */
	boolean notItsDoing;
	private long ponderUntil;
	/** The situation it chose the current option in (for its outcome memory), and whether that was the plan's advice. */
	private float[] optionSituation;
	private boolean optionFollowed;
	/** What it expected of the current option when it chose it (and where it was), to reflect on when it's over. */
	private Experience.Prediction optionExpect;
	private BlockPos optionWhere;

	/** A decision: every choice it could make, best first; why each scored as it did; why it went against the plan (or null). */
	private record Decision(int[] order, String[] how, String[] says, String against, float difficulty, String note, Experience.Prediction[] expect, float[] score) {}

	/**
	 * Xen 2.0 decides (the code gives it what it sees and what it can do; the choice is its own). Three things weigh in,
	 * each in its own say:
	 * <ul>
	 *   <li><b>its mind</b>: what its learned critics make of each choice (what it brings, what it costs in harm, and,
	 *   when it was unsure, the one its world model thought through), as how far above or below the others it is;</li>
	 *   <li><b>the usual plan</b> (how players get on: tools, iron, a roof at night...): advice, as much as it listens to
	 *   advice (a diligent one more, a proud one sure of itself less);</li>
	 *   <li><b>what happened before</b>: the times it chose that in a situation like this one (outcome memory), as much
	 *   as there are such times. Twice dead mining at night here outweighs the plan saying mine.</li>
	 * </ul>
	 */
	private Decision decide(float[] f, boolean[] can, float caution, Mind.Choice ch, int planned, String planWhy, float[] situation) {
		int n = Mind.N;
		float[] util = new float[n];
		double sum = 0, sumSq = 0;
		int k = 0;
		Tribe t = c.tribe();
		float[] b = bias(t);
		for (int i = 0; i < n; i++) {
			if (!can[i]) continue;
			util[i] = ch.value[i] - caution * ch.fear[i] + b[i];
			sum += util[i];
			sumSq += util[i] * util[i];
			k++;
		}
		if (k == 0) return new Decision(new int[0], new String[n], new String[n], null, 0, null, new Experience.Prediction[n], new float[n]);
		double mean = sum / k, sd = Math.sqrt(Math.max(1e-6, sumSq / k - mean * mean));
		float belief = c.belief.get();
		float adviceW = (1.0f + 0.8f * c.personality.diligence) * (1.25f - 0.5f * belief) * c.experience.planTrust();   // (how much it goes by the usual plan: its nature, and how the plan has worked out for it)
		float mindW = 0.6f + 0.6f * belief;                                    // (how much it goes by its own mind)
		float expW = 2.5f;                                                     // (what happened before: a lot, when there's enough of it)
		Plans.Step step = c.plans.advice();                                    // its own plan for something it wants (know-how): advice too, its mind weighs it
		int stepOption = step == null ? -1 : step.option;
		float knowW = step == null ? 0 : c.plans.weight();
		BlockPos here = c.player.blockPosition();
		float[] score = new float[n];
		String[] how = new String[n];
		Experience.Guess[] g = new Experience.Guess[n];
		Experience.Prediction[] expect = new Experience.Prediction[n];
		for (int i = 0; i < n; i++) {
			if (!can[i]) continue;
			float z = (float) ((util[i] - mean) / Math.max(sd, 0.5));
			float thought = i == ch.option && ch.how.startsWith("thinking it through") ? 0.5f : 0;   // (the one its world model thought best)
			g[i] = c.experience.guess(situation, i, here);
			expect[i] = c.experience.predict(g[i], i);   // (what it expects, before it does it: held against what happens)
			float past = expW * g[i].sure() * g[i].expect();
			float advice = i == planned ? adviceW : 0;
			float curious = i == Mind.EXPLORE ? 0.8f * c.personality.curiosity * situation[17] : 0;   // (G1X: much it hasn't seen round it: a curious one goes to look)
			float know = i == stepOption ? knowW : 0;
			score[i] = mindW * z + thought + past + advice + curious + know;
			StringBuilder sb = new StringBuilder();
			if (i == stepOption) sb.append("its plan for ").append(c.plans.goal.replace('_', ' ')).append(": ").append(step.says())
					.append(String.format(java.util.Locale.ROOT, " (+%.1f); ", know));
			if (i == planned) sb.append("the plan: ").append(planWhy).append("; ");
			sb.append(String.format(java.util.Locale.ROOT, "mind %+.1f", mindW * z + thought));
			if (g[i].n() > 0) sb.append(String.format(java.util.Locale.ROOT, ", before %+.1f (%s)", past, g[i].says()));
			if (i == planned) sb.append(String.format(java.util.Locale.ROOT, ", plan %+.1f", advice));
			how[i] = sb.toString();
		}
		Integer[] idx = new Integer[k];
		int j = 0;
		for (int i = 0; i < n; i++) if (can[i]) idx[j++] = i;
		java.util.Arrays.sort(idx, (x, y) -> Float.compare(score[y], score[x]));
		int[] order = new int[k];
		for (int i = 0; i < k; i++) order[i] = idx[i];
		String against = null, note = null;
		if (planned >= 0 && can[planned] && order[0] != planned) {
			Experience.Guess pg = g[planned];
			if (pg != null && pg.n() > 0 && pg.expect() < -0.2f) {
				against = pg.says().contains("died") ? "the last time" + (pg.n() > 1 ? "s" : "") + " like this didn't end well (" + pg.says() + ")"
						: "it hasn't gone well before (" + pg.says() + ")";
				note = "goes against the plan (" + Mind.SAYS[planned] + "): " + pg.says();
			} else {
				note = "goes against the plan (" + Mind.SAYS[planned] + "): its mind thinks " + Mind.SAYS[order[0]] + " is better";
			}
		}
		float margin = k > 1 ? score[order[0]] - score[order[1]] : 2;
		float difficulty = 1f / (1 + 3 * Math.max(0, margin));                   // (two choices neck and neck: a hard one)
		String[] says = new String[n];                                         // (and why, in words it could say)
		for (int i = 0; i < n; i++) {
			if (!can[i]) continue;
			if (i == planned) says[i] = "";                                    // (the usual plan: its own words for that, from the plan's reason)
			else if (planned >= 0 && against != null) says[i] = "I'd rather not " + Mind.SAYS[planned] + " right now: " + against.replaceAll(" \\(.*\\)", "");
			else if (i == Mind.EXPLORE && situation[17] > 0.5f) says[i] = "I haven't seen what's around here yet";
			else if (g[i] != null && g[i].n() > 0 && g[i].expect() > 0.3f) says[i] = "it went well the last times I did";
		}
		return new Decision(order, how, says, against, difficulty, note, expect, score);
	}

	/**
	 * What it expects of each thing it could do now (/xen predict): how likely it goes well, how risky, how long, from
	 * the times like this and its mind; which it would pick; how good its guesses have been. Its real numbers.
	 */
	String predictions() {
		if (c.player == null || c.mod.mind == null) return null;
		float[] f = MindSense.features(c);
		boolean[] can = MindSense.allowed(c, f, false);
		float caution = c.personality.cautionScale() * (0.7f + 0.6f * c.emotions.fear);
		int planned = agenda(f, can);
		String planWhy = agendaWhy;
		float[] situation = c.experience.situation();
		Mind.Choice ch = c.mod.mind.choose(f, can, caution, 0, random, bias(c.tribe()));
		Decision d = decide(f, can, caution, ch, planned, planWhy, situation);
		StringBuilder sb = new StringBuilder(c.name).append(" sizes up: ");
		StringBuilder now = new StringBuilder();
		for (int i = 0; i < situation.length; i++) {
			boolean flag = i <= 4 || i == 14 || i == 15 || i == 16;
			if (flag && situation[i] > 0.5f) now.append(now.length() == 0 ? "" : ", ").append(Experience.PARTS[i]);
		}
		sb.append(now.length() == 0 ? "nothing special" : now).append(String.format(java.util.Locale.ROOT, "; health %.0f, %.0f%% unseen", c.player.getHealth(), 100 * situation[17]));
		for (int r = 0; r < d.order.length && r < 4; r++) {
			int o = d.order[r];
			Experience.Prediction e = d.expect[o];
			sb.append("\n ").append(r == 0 ? "> " : "  ").append(Mind.SAYS[o]).append(String.format(java.util.Locale.ROOT, ": goes well %.0f%%, risk %.0f/100, ", 100 * e.good(), 100 * e.risk()))
					.append(e.minutes() > 0 ? String.format(java.util.Locale.ROOT, "about %.1f min", e.minutes()) : "time unknown");
			Experience.Guess g = c.experience.guess(situation, o, c.player.blockPosition());
			sb.append(g.n() > 0 ? " (" + g.says() + ")" : " (never like this: how it usually goes)");
			if (o == planned) sb.append(" [the usual plan]");
			String w = c.experience.weighs(o);
			if (!w.isEmpty()) sb.append(" [weighs ").append(w).append("]");
		}
		float err = c.experience.meanError();
		sb.append(err < 0 ? "\n guesses: none checked yet" : String.format(java.util.Locale.ROOT, "\n guesses: off by %.2f lately (%d checked)%s", err, c.experience.predicted,
				c.experience.firstError >= 0 ? String.format(java.util.Locale.ROOT, ", %.2f its first 30", c.experience.firstError) : ""));
		if (!c.experience.lesson.isEmpty()) sb.append("\n last lesson: ").append(c.experience.lesson);
		sb.append("\n its moves: ").append(c.walker.moves());
		return sb.toString();
	}

	/**
	 * How long it takes to make up its mind, its own way (not a setting): ticks till it decides (or looks again). Quick:
	 * in danger (no time: the gut), when it's been in a spot like this many times, sure of itself, hot-headed, quick by
	 * nature. Slow: somewhere new, unsure of itself, careful and thorough, rattled, lazy. Half a second to five.
	 */
	int thinkTicks(float[] situation, float[] f) {
		return thinkTicks(situation, f, 0.5f);
	}

	/** The same for a choice of this difficulty (0: obvious, 1: neck and neck): a hard one, it thinks longer; no cap, as long as it needs. */
	int thinkTicks(float[] situation, float[] f, float difficulty) {
		var p = c.personality;
		if (f[Mind.DANGER] > 0.5f) return 0;                                  // (no time to think)
		float familiar = c.experience.familiar(situation);
		float t = 40;
		t *= 1.4f - 0.6f * familiar;                                          // somewhere new: longer
		t *= 1.3f - 0.6f * c.belief.get();                                    // sure of itself: quicker
		t *= 0.8f + 0.5f * p.diligence;                                       // thorough: longer
		t *= 1.25f - 0.5f * p.hidden(Personality.REFLEXES);                   // quick by nature: quicker
		t *= 1 + 0.4f * p.sin(xen.mod.core.Sins.SLOTH) - 0.3f * p.sin(xen.mod.core.Sins.WRATH);   // lazy: slower; hot-headed: snap decisions
		t *= 1 + 0.8f * c.confusion.level;                                    // rattled: it dithers
		t *= 0.4f + 1.6f * difficulty;                                        // a hard choice: longer (an easy one: a moment)
		return Math.max(0, Math.round(t));
	}

	/** A duel it accepted ("1v1 me"): whatever it was doing waits; it goes over to them and fights (a truce ends it). */
	void duel(ServerPlayer foe) {
		drop();
		c.chores.cancel();
		long now = c.player.level().getGameTime();
		option = Mind.FIGHT;
		optionFeatures = MindSense.features(c);
		optionSituation = c.experience.situation();
		optionAt = now;
		optionUntil = now + 20 * 60;
		optionHow = "a duel";
		c.chosenFoe = foe;
		c.lastPickedFight = now;
	}

	private long nightCheckedAt;

	/**
	 * Night fell while it was out chopping, exploring, farming: a player drops that and sees to a roof (it checks every
	 * 5 seconds, before its errands go on). True when it dropped something.
	 */
	boolean nightfall() {
		long now = c.player.level().getGameTime();
		if (option < 0 || now - nightCheckedAt < 100 || !useMind() || c.mode != Companion.Mode.FREE || now < exploreAskedUntil) return false;
		nightCheckedAt = now;
		boolean outdoorWork = option == Mind.WOOD || option == Mind.EXPLORE || option == Mind.HOUSE || option == Mind.FARM
				|| option == Mind.STONE && !needBlocksForTheNight() || option == Mind.REST
				|| option == Mind.FOOD && (!c.hands.hasWeapon() || c.player.getHealth() < 14);   // (hunting in the dark with bare hands: no)
		boolean hurt = c.player.getHealth() <= 12 && option != Mind.SHELTER && option != Mind.SLEEP;
		if (!outdoorWork && !hurt || !outAtNight()) return false;
		var p = c.player;                                                     // out in the dark: a tip comes to mind, and it's its call
		int near = p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(16), m -> m.isAlive()).size();
		float dark = p.level().getMaxLocalRawBrightness(p.blockPosition()) < 5 ? 0.1f : 0;
		boolean ready = c.hands.hasWeapon() && p.getHealth() >= 14 && p.getFoodData().getFoodLevel() >= 10;   // (armed and well: night's just night)
		float pull = hurt ? (12 - p.getHealth()) / 12f + 0.1f * Math.min(3, near) + dark : (ready ? 0.2f : 0.55f) + 0.1f * Math.min(3, near) + dark;
		if (!c.tips.take(hurt ? Tips.Tip.HURT_IN_DARK : Tips.Tip.NIGHTFALL, pull, String.format(java.util.Locale.ROOT, "%s, %.0f health, %s",
				Mind.SAYS[option], p.getHealth(), near == 0 ? "nothing about" : near + " monster" + (near > 1 ? "s" : "") + " about"), 600)) return false;
		c.journal("does", (outdoorWork ? "night fell" : String.format(java.util.Locale.ROOT, "hurt (%.0f health) out in the dark", c.player.getHealth()))
				+ ": drops " + Mind.SAYS[option] + " for a shelter");
		c.focus.forget();                                                    // (night: what it was doing out here can wait for the morning)
		current = null;
		finishOption(false);
		c.chores.cancel();
		next = now;
		return true;
	}

	/** Night, and it's out under the open sky (leaves don't count): no roof, no walls. */
	boolean outAtNight() {
		var p = c.player;
		if (!p.level().isDarkOutside() || MindSense.enclosed(p)) return false;
		int top = p.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getBlockX(), p.getBlockZ());
		return top <= p.getBlockY() + 1;
	}

	/** Why the plan picked what it picked (for its thoughts). */
	private String agendaWhy = "";

	/**
	 * How players get on, step by step: eat when hungry; at night a bed, a roof or the mine (never out in the dark);
	 * the next tool; iron; a house; armor; diamonds. When one of these is clearly next, it does that (its mind learns
	 * from it too); when nothing is, its mind and its likes decide (farms, trading, exploring, adventures). It also
	 * stops gathering what it has plenty of (a chest full of logs doesn't get you anywhere). -1: its mind's call.
	 */
	private int agenda(float[] f, boolean[] can) {
		var items = c.items();
		int wood = MindSense.count(c, n -> n.endsWith("_planks")) + 4 * items.getOrDefault("log", 0);
		int stone = items.getOrDefault("cobblestone", 0);
		if (wood >= (home == null ? 96 : 64)) can[Mind.WOOD] = false;              // plenty: on with something else
		if (stone >= 64) can[Mind.STONE] = false;
		if (c.player.isCreative() || c.mode == Companion.Mode.STAY || c.mode == Companion.Mode.FOLLOW) return -1;
		if (can[Mind.FIGHT] && f[Mind.DANGER] < 0.2f && c.player.getHealth() >= 14 && MindSense.enemy(c) != null
				&& c.mod.config.teams < 0) return why(Mind.FIGHT, "a rival");             // (teams on auto: it takes the fight to them)
		if (can[Mind.FIGHT] && f[Mind.DANGER] < 0.2f && c.player.getHealth() >= 14 && c.rage.wantsBlood() && MindSense.enemy(c) != null)
			return why(Mind.FIGHT, "bloodlust");                                         // (out for blood: it goes for them)
		boolean calm = f[Mind.DANGER] < 0.3f && !can[Mind.HELP];
		if (!calm && (can[Mind.FIGHT] || can[Mind.FLEE] || can[Mind.HELP] || can[Mind.GUARD])) return -1;   // danger, a friend in need: its own call
		if (c.mod.config.runner && !c.minion && !c.inArena && !c.places.worlds().contains("the_nether")) {   // runner: only the run (to the Nether), then its own life
			int run = runner(f, can, items);
			if (run >= 0 && !agendaWhy.startsWith("runner: ")) agendaWhy = "runner: " + agendaWhy;
			return run;
		}
		int hunger = c.player.getFoodData().getFoodLevel(), meals = items.getOrDefault("food", 0);
		int tier = c.crafter.pickTier();
		int iron = items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0);
		boolean dark = f[Mind.NIGHT] > 0.5f || f[Mind.DUSK] > 0.5f;
		boolean below = f[Mind.UNDERGROUND] > 0.5f;
		Personality who = c.personality;
		int plan = who.plan;
		boolean nightReady = c.hands.hasWeapon() && c.player.getHealth() >= 14 && hunger >= 10 && !who.believes("night_belongs_to_monsters");   // (armed, healthy, fed: the night's no reason to stop, players play on)
		if (can[Mind.EAT] && hunger < Math.min(18, who.eatAt() + 4)) return why(Mind.EAT, "hungry");   // (a glutton sooner)
		if (dark && f[Mind.NIGHT] < 0.5f && !sunDown() && f[Mind.DANGER] < 0.2f) {   // late afternoon: ready for the night, not in for it yet (a player keeps at it till the sun goes down)
			if (home != null && can[Mind.SHELTER] && !c.player.blockPosition().closerThan(home, 48) && c.player.blockPosition().closerThan(home, 400))
				return why(Mind.SHELTER, "dusk: heading home before dark");
			if (blocks() < NIGHT_BLOCKS && can[Mind.STONE] && !below && !nightReady && (home == null || !c.player.blockPosition().closerThan(home, 96)))
				return why(Mind.STONE, "dusk: blocks for a hut tonight");
			dark = false;                                                       // (else its afternoon goes on)
		}
		if (dark) {
			if (can[Mind.SLEEP]) return why(Mind.SLEEP, "night: bed");
			boolean bedWithIt = items.keySet().stream().anyMatch(k -> k.endsWith("_bed"));
			if (bedWithIt && (home == null || !c.player.blockPosition().closerThan(home, 64)) && c.bedRoom() != null) return why(Mind.REST, "night: it camps with its bed");   // (room for it here: else, something useful)
			BlockPos mine = c.places.get("mine");
			int mineRange = who.believes("night_mining") ? 96 : who.believes("night_belongs_to_monsters") ? 0 : 32;   // (its beliefs about the night)
			boolean sheltered = f[Mind.SHELTERED] > 0.5f || c.chores.shelterBuilt && below;
			boolean atHome = home != null && c.player.blockPosition().closerThan(home, 24);
			boolean monstersNight = who.believes("night_belongs_to_monsters") && !below;   // (its belief: it stays in, unless it's down a mine already)
			if (c.player.getHealth() <= 12 && !sheltered && !below && can[Mind.SHELTER]) return why(Mind.SHELTER, "night, and hurt: dig in and heal");   // (not a long walk to a mine like this)
			if (tier >= 1 && can[Mind.MINE] && !monstersNight) {               // a pickaxe: the night's for mining, as players do (stone, coal, more iron: all worth having)
				boolean mineNear = mine != null && mine.closerThan(c.player.blockPosition(), mineRange);
				return why(Mind.MINE, below || mineNear ? "night: mining (safe under the ground)"
						: sheltered ? "night: digging down from its shelter (mining is safe under the ground)" : "night: digging down to mine (safe under the ground)");
			}
			if (sheltered || atHome) {                                          // in for the night: something useful, not just waiting
				if (can[Mind.CRAFT]) return why(Mind.CRAFT, "night: crafting inside");
				if (can[Mind.SMELT]) return why(Mind.SMELT, "night: smelting inside");
				if (can[Mind.STORE]) return why(Mind.STORE, "night: sorting its things");
				return why(Mind.REST, "night: staying in");
			}
			if (nightReady) return -1;                                          // (a roof is one way through the night, not the only one: its mind's call)
			if (f[Mind.NIGHT] < 0.5f && blocks() < NIGHT_BLOCKS && can[Mind.STONE] && !below
					&& (home == null || !c.player.blockPosition().closerThan(home, 96))) return why(Mind.STONE, "dusk: dirt for a hut");   // (before dark, like a player)
			if (can[Mind.SHELTER]) return why(Mind.SHELTER, "night: a roof first");
			return -1;
		}
		if (f[Mind.DANGER] > 0.2f || can[Mind.HELP] || can[Mind.GUARD]) return -1;   // (monsters at it in the day, a friend in need: its own call; a rival just being about isn't)
		if (c.player.getHealth() < 14 && hunger < 18) {                        // hurt, and too hungry to heal: food first (a player doesn't stand about waiting)
			if (can[Mind.EAT]) return why(Mind.EAT, "hurt: eating to heal");
			if (can[Mind.FOOD]) {
				c.chores.forWool = false;
				return why(Mind.FOOD, "hurt: food to heal");
			}
		}
		if (tier >= 3 && can[Mind.ENCHANT] && !c.enchanter.on && now() > c.enchanter.retryAt) {   // the makings of an enchantment (lapis, levels, the table): a few seconds, before anything long (crafting too: enchanted gear is the better gear)
			return why(Mind.ENCHANT, "enchant its gear (lapis, " + c.player.experienceLevel + " levels)");
		}
		if (can[Mind.CRAFT]) return why(Mind.CRAFT, "better gear");
		if (can[Mind.SMELT] && (items.getOrDefault("raw_iron", 0) >= 3 || c.chores.rawFood() >= 3 && meals < 3)) return why(Mind.SMELT, "iron to smelt");
		if (items.getOrDefault("raw_iron", 0) >= 3 && !can[Mind.SMELT] && c.chores.fuel() == 0) {   // (iron, nothing to burn: from the end back, coal first)
			if (can[Mind.MINE] && now() - coalTriedAt > 2400) {
				fuelRun = true;
				return why(Mind.MINE, "coal to smelt its iron");
			}
			if (can[Mind.WOOD]) return why(Mind.WOOD, "wood to smelt its iron");   // (no coal last time: a tree burns too)
		}
		if ((meals < 2 || meals < 3 && hunger < 14) && hunger < 18 && can[Mind.FOOD]) {   // (food in the bag before it's needed: hunger is a slow enemy)
			c.chores.forWool = false;
			return why(Mind.FOOD, "low on food");
		}
		boolean bed = items.keySet().stream().anyMatch(k -> k.endsWith("_bed")) || c.bedAt != null;
		if (!bed && tier >= 1 && can[Mind.FOOD] && wool() < 3 && sheepNear() && (tier != 2 || evening())) {   // (with stone tools: iron first, unless night's coming)
			c.chores.forWool = true;
			return why(Mind.FOOD, "wool for a bed");
		}
		if (tier == 0) return can[Mind.WOOD] ? why(Mind.WOOD, "wood for a pickaxe") : -1;
		if (tier == 1) return can[Mind.STONE] ? why(Mind.STONE, "stone tools") : -1;
		if (tier >= 4 && c.mod.config.adventures && !c.places.worlds().contains("the_nether") && !c.nether.busy() && now() > netherTryAt
				&& (enchantedSomething() || c.enchanter.table() == null && MindSense.count(c, n -> n.equals("obsidian") || n.equals("enchanting_table")) < 4
						&& MindSense.count(c, n -> n.equals("book")) > 0 || now() - diamondPickAt() > 24000 * 2)) {    // a diamond pickaxe and its gear enchanted, or the book and no obsidian for the table yet (the trip gets it, and it enchants before the portal), or two days on: a first look at the Nether
			netherTryAt = now() + 20 * 60 * 20;
			String trip = c.nether.go("visit", 0);
			c.journal("thinks", "the plan: a first look at the Nether (" + trip + ")");
			if (trip.startsWith("You will")) {
				c.chatter(c.pick3("Diamond pickaxe, enchanted gear. Time to see the Nether.", "Let's build a portal. The Nether's next.", "Nether time!"), true);
				return why(Mind.REST, "off to see the Nether");
			}
		}
		// The main goal: iron (tools, then armor), then diamonds, a home, and in the end the dragon. Its own plan (Xen 6.0)
		// changes the order: a builder or a settler makes its home first, a speedrunner skips it, a settler farms.
		boolean holeOnly = who.believes("hole_is_enough");
		boolean homeFirst = (plan == Strategy.BUILDER || plan == Strategy.SETTLER) && !holeOnly;
		if (tier == 2) {                                                         // stone tools: what next is its own call, weighed
			int next = afterStone(can, items, iron, meals, hunger, bed, holeOnly);
			if (next >= 0) return next;
		} else stoneGoal = -1;
		if (homeFirst && tier >= 3 && home == null) {
			if (can[Mind.HOUSE]) return why(Mind.HOUSE, "a home first (its plan)");
			if (can[Mind.WOOD]) return why(Mind.WOOD, "wood for its home (its plan)");
		}
		boolean farmed = c.places.get("farm") != null;
		if ((plan == Strategy.SETTLER || who.believes("farming_way")) && home != null && !farmed && can[Mind.FARM]) return why(Mind.FARM, "a farm (its plan)");
		if (tier == 2 && iron < 3 && can[Mind.MINE]) return trip(Mind.MINE, false, can, items, "iron");   // (nothing else open)
		boolean rushing = plan == Strategy.SPEEDRUN && f[Mind.ARMOR] >= 0.6f && f[Mind.SWORD] >= 0.75f;   // (geared enough: on to the End)
		if (tier >= 3 && !rushing && wantsOre(tier, iron) && can[Mind.MINE]) {
			boolean diamonds = readyForDiamonds() || !(f[Mind.ARMOR] < 0.6f && iron < 24);   // (its own call: a chestplate, or a shield and an axe it can use, or nerve)
			return trip(Mind.MINE, diamonds, can, items, diamonds ? "diamonds" : "iron for armor");
		}
		if (tier >= 3 && home == null && plan != Strategy.SPEEDRUN && !holeOnly) {
			if (can[Mind.HOUSE]) return why(Mind.HOUSE, "a home");
			if (can[Mind.WOOD]) return why(Mind.WOOD, "wood for a house");
		}
		boolean greedy = who.believes("nether_opportunity") || who.sin(Sins.GREED) >= 0.7f;   // (netherite before the dragon)
		if (!greedy) {
			int end = endgame(f, can, tier, items, homeFirst || holeOnly);
			if (end >= 0) return end;
		}
		if (tier >= 4 && netheriteNext() && !c.nether.busy() && now() > netherTryAt) {   // diamonds done: netherite, from the Nether
			netherTryAt = now() + 20 * 60 * 20;
			String trip = c.nether.go("debris", 0);
			c.journal("thinks", "the plan: netherite (" + trip + ")");
			if (trip.startsWith("You will")) {
				c.chatter(c.pick3("Diamonds done. Next: netherite!", "Time for the Nether. Ancient debris, here I come.", "Netherite next."), true);
				return why(Mind.REST, "off to the Nether for netherite");
			}
		}
		if (greedy) return endgame(f, can, tier, items, holeOnly);
		return -1;
	}

	/**
	 * The end of the game: the Ender Dragon, when it's geared (an iron pickaxe and sword, armor) and it's time by its
	 * plan and beliefs: a speedrunner (or "the dragon must fall") at once; "the End can wait" and a settler once life is
	 * good (a home, a farm, diamond tools); everyone else once it has a home and diamonds. Beliefs hold it back, never
	 * for ever: after four days on its plan it goes anyway. Food for the journey first.
	 */
	private int endgame(float[] f, boolean[] can, int tier, Map<String, Integer> items, boolean homeDone) {
		if (dragonDown || !c.mod.config.adventures || c.adventure.on) return -1;
		boolean geared = tier >= 3 && f[Mind.SWORD] >= 0.75f && f[Mind.ARMOR] >= 0.6f;
		if (!geared) return -1;
		Personality who = c.personality;
		int plan = who.plan;
		boolean farmed = c.places.get("farm") != null, homed = home != null || homeDone;
		boolean long_ = planSince >= 0 && now() - planSince > 4 * 24000L;
		boolean time;
		if (plan == Strategy.SPEEDRUN || who.believes("dragon_must_fall")) time = true;
		else if (who.believes("end_can_wait") || plan == Strategy.SETTLER) time = homed && farmed && tier >= 4 || long_;
		else time = homed && (tier >= 4 || diamonds() >= 3) || long_;
		if (time && who.believes("nether_is_hell") && !long_) {                 // not into the Nether without diamond armor
			String chest = c.itemKey(c.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
			time = chest.startsWith("diamond") || chest.startsWith("netherite");
		}
		if (!time) return -1;
		if (items.getOrDefault("food", 0) < 8 && can[Mind.FOOD]) return why(Mind.FOOD, "food for the journey to the End");
		return can[Mind.ADVENTURE] ? trip(Mind.ADVENTURE, true, can, items, "the dragon: the end of the game (" + (plan >= 0 ? Strategy.NAMES[plan] : "its plan") + ")") : -1;
	}

	private long netherTryAt;

	// -------------------------------------------------------------------- its own plan (Xen 6.0)
	private long planSince = -1, planChecked;
	private float planScoreAt;
	private int planDeathsAt, planBadDays;

	/**
	 * Its plan, worked out and looked at again once a day: how far did it get today (tools, armor, a home, a farm, ore,
	 * the Nether, the End; a death sets it back)? Every Xen's days go into the plan book, so the next Xen of its nature
	 * knows what works. Two days of getting nowhere and it thinks again: a new plan, not the one that failed.
	 */
	private void reviewPlan(long now) {
		Personality p = c.personality;
		if (p.sins == null || c.minion || c.player.isCreative()) return;
		if (p.plan < 0) {
			p.plan = Strategy.choose(p.sins, c.mod.strategies, -1, random);
			startPlan(now);
			c.journal("thinks", "works out its plan: " + p.planInWords());
			String[] casual = {"speedrun it: iron, diamonds, the nether, the dragon", "build a really nice home first", "farm and chill, the end can wait",
					"get rich mining", "explore everything", "trade with villagers and get rich", "gear up and fight stuff", "play it safe: armor, a bed, no night trips"};
			String said = p.plan < casual.length ? c.banter.event("plan", java.util.Map.of("plan", casual[p.plan])) : null;
			c.chatter(said != null ? said : c.pick3("My plan: " + Strategy.WORDS[p.plan], "Here's the plan: " + Strategy.WORDS[p.plan],
					"I know what I'll do: " + Strategy.WORDS[p.plan]), true);
			return;
		}
		if (planSince < 0) {
			startPlan(now);
			return;
		}
		if (now - planChecked < 24000) return;
		float score = gameProgress(), day = score - planScoreAt - 3f * (c.genDeaths - planDeathsAt);
		c.mod.strategies.add(Sins.top(p.sins), p.plan, day);
		planChecked = now;
		planScoreAt = score;
		planDeathsAt = c.genDeaths;
		planBadDays = day <= 0.5f ? planBadDays + 1 : 0;
		c.journal("thinks", String.format(Locale.ROOT, "its plan (%s): %+.1f progress today", Strategy.NAMES[p.plan], day));
		if (planBadDays >= 2 && now - planSince >= 2 * 24000L) {
			int was = p.plan;
			p.plan = Strategy.choose(p.sins, c.mod.strategies, was, random);
			startPlan(now);
			c.journal("thinks", "its plan wasn't working (" + Strategy.NAMES[was] + "): new plan " + p.planInWords());
			c.chatter(c.pick3("This isn't working. New plan: " + Strategy.WORDS[p.plan], "Time for a different plan: " + Strategy.WORDS[p.plan],
					"Okay, change of plan: " + Strategy.WORDS[p.plan]), true);
		}
	}

	private void startPlan(long now) {
		planSince = planChecked = now;
		planScoreAt = gameProgress();
		planDeathsAt = c.genDeaths;
		planBadDays = 0;
	}

	/** How far it has got, one yardstick for every plan. */
	float gameProgress() {
		var items = c.items();
		float s = 2f * c.crafter.pickTier();
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
			var it = c.player.getItemBySlot(slot);
			if (it.isEmpty()) continue;
			String id = c.itemKey(it);
			s += id.startsWith("diamond") || id.startsWith("netherite") ? 1.5f : id.startsWith("leather") ? 0.3f : 1f;
		}
		if (home != null) s += 3;
		if (c.places.get("farm") != null) s += 2;
		if (c.bedAt != null) s += 1;
		s += 0.5f * Math.min(5, diamonds()) + 0.2f * Math.min(10, items.getOrDefault("emerald", 0));
		if (c.player.getMainHandItem().isEnchanted() || c.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEnchanted()) s += 2;
		if (c.places.get("portal") != null || c.places.get("fortress") != null) s += 3;
		s += 0.3f * Math.min(7, items.getOrDefault("blaze_rod", 0) + items.getOrDefault("blaze_powder", 0) / 2);
		s += 0.3f * Math.min(12, items.getOrDefault("ender_eye", 0) + items.getOrDefault("ender_pearl", 0) / 2);
		if (c.places.get("stronghold", "overworld") != null) s += 3;
		if (dragonDown) s += 15;
		return s;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** Farm animals by its home (cows, sheep, pigs, chickens within 32): worth a pen. */
	private int animalsByHome() {
		if (home == null || !c.player.blockPosition().closerThan(home, 64)) return 0;
		return c.player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, new net.minecraft.world.phys.AABB(home).inflate(32, 8, 32), a -> {
			String n = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath();
			return a.isAlive() && (n.equals("cow") || n.equals("sheep") || n.equals("pig") || n.equals("chicken"));
		}).size();
	}

	/** When it first had a diamond pickaxe (game time; -1 not yet). */
	private long diamondPickSince = -1;

	private long diamondPickAt() {
		if (diamondPickSince < 0) diamondPickSince = now();
		return diamondPickSince;
	}

	/** Something it carries or wears is enchanted. */
	private boolean enchantedSomething() {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).isEnchanted()) return true;
		return false;
	}

	/** Diamond gear (a diamond pickaxe and sword or chestplate), and not yet netherite enough: the Nether is next. */
	private boolean netheriteNext() {
		int debris = MindSense.count(c, n -> n.equals("ancient_debris") || n.equals("netherite_scrap")) + 4 * MindSense.count(c, n -> n.startsWith("netherite_"));
		boolean diamondGear = MindSense.count(c, n -> n.equals("diamond_sword") || n.equals("diamond_chestplate")) > 0
				|| c.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem().toString().contains("diamond");
		return diamondGear && debris < 4 && c.mod.config.adventures;
	}

	// --------------------------------------------------------------- after stone tools (Xen 6.0)
	/** What it settled on after stone tools (a {@link Purpose} choice; -1: nothing yet), until when; how often it went for each. */
	private int stoneGoal = -1;
	private long stoneGoalUntil;
	private String stoneWhy = "";
	private final int[] stoneTried = new int[Purpose.CHOICES];
	/** How long it keeps at each choice before it weighs them again (ticks). */
	private static final int[] STONE_KEEP = {8000, 3600, 3600, 12000, 2400};

	/**
	 * Stone tools: a decision point, not a script. Iron, food, a bed, a home or a look around, each weighed by how it
	 * stands (food left, the time of day, sheep about, a cave it knows, how sure it is where iron is), its plan, nature
	 * and skills, and what its village's leader wants (as much as it goes along). Once it picks, it keeps at it until
	 * it's done or a good while has passed (no dithering); what it weighed goes in its journal. -1: nothing open.
	 */
	private int afterStone(boolean[] can, Map<String, Integer> items, int iron, int meals, int hunger, boolean bed, boolean holeOnly) {
		long now = now();
		boolean[] open = new boolean[Purpose.CHOICES];
		open[Purpose.IRON] = iron < 3 && can[Mind.MINE];
		open[Purpose.FOOD] = meals < 8 && can[Mind.FOOD];
		open[Purpose.BED] = !bed && can[Mind.FOOD] && wool() < 3 && sheepSeen();
		open[Purpose.HOME] = home == null && !holeOnly && (can[Mind.HOUSE] || can[Mind.WOOD]);
		open[Purpose.EXPLORE] = can[Mind.EXPLORE];
		if (stoneGoal >= 0 && (now > stoneGoalUntil || !open[stoneGoal] || stoneGoal == Purpose.FOOD && meals >= 6)) {
			c.journal("thinks", "done with " + Purpose.CHOICE[stoneGoal] + " for now");
			stoneTried[stoneGoal]++;
			stoneGoal = -1;
		}
		if (stoneGoal < 0) {
			float[] score = stoneScores(open, items, meals, hunger);
			int best = -1;
			for (int i = 0; i < Purpose.CHOICES; i++) if (open[i] && (best < 0 || score[i] > score[best])) best = i;
			if (best < 0) return -1;
			stoneGoal = best;
			stoneGoalUntil = now + STONE_KEEP[best];
			StringBuilder all = new StringBuilder();
			for (int i = 0; i < Purpose.CHOICES; i++) {
				if (all.length() > 0) all.append(", ");
				all.append(Purpose.CHOICE[i]).append(' ').append(open[i] ? String.format(Locale.ROOT, "%.2f", score[i]) : "-");
			}
			stoneWhy = reason(best);
			c.journal("thinks", "stone tools, what next? " + all + " -> " + Purpose.CHOICE[best] + " (" + stoneWhy + ")");
			c.purpose.disagree(best, c.purpose.groupPick(open), now);
			if (random.nextFloat() < 0.35f) c.chatter(c.pick3("Stone tools. Now " + Purpose.CHOICE[best] + ", I think.",
					"Okay. Next: " + Purpose.CHOICE[best] + ".", "Right, " + Purpose.CHOICE[best] + " next."), false);
		}
		c.purpose.goal = Purpose.CHOICE[stoneGoal];
		return switch (stoneGoal) {
			case Purpose.IRON -> trip(Mind.MINE, false, can, items, "iron (" + stoneWhy + ")");
			case Purpose.FOOD -> {
				c.chores.forWool = false;
				yield why(Mind.FOOD, "food (" + stoneWhy + ")");
			}
			case Purpose.BED -> {
				c.chores.forWool = true;
				yield why(Mind.FOOD, "wool for a bed (" + stoneWhy + ")");
			}
			case Purpose.HOME -> can[Mind.HOUSE] ? why(Mind.HOUSE, "a home (" + stoneWhy + ")") : why(Mind.WOOD, "wood for a home (" + stoneWhy + ")");
			default -> trip(Mind.EXPLORE, false, can, items, "a look around (" + stoneWhy + ")");
		};
	}

	/** Each choice after stone tools, weighed (see {@link #afterStone}). */
	private float[] stoneScores(boolean[] open, Map<String, Integer> items, int meals, int hunger) {
		Personality who = c.personality;
		int plan = who.plan;
		Skills k = c.skills;
		float[] s = new float[Purpose.CHOICES];
		int tries = 0;
		for (int t : stoneTried) tries += t;
		s[Purpose.IRON] = 0.55f + (plan == Strategy.MINER || plan == Strategy.SPEEDRUN || plan == Strategy.WARRIOR ? 0.35f : 0)
				+ (c.caves.nearest(160) != null ? 0.25f : 0) + 0.3f * (c.lessons.sure("iron") - 0.3f) + 0.25f * (k.get(Skills.MINE) - 0.5f)
				+ 0.15f * who.sin(Sins.GREED) + 0.1f * tries;                                  // (the longer it waits, the more iron presses)
		s[Purpose.FOOD] = 0.2f + 0.5f * (1 - Math.min(meals, 8) / 8f) + (hunger < 14 ? 0.2f : 0)
				+ (plan == Strategy.SETTLER || plan == Strategy.SURVIVOR ? 0.25f : 0) + 0.15f * who.sin(Sins.GLUTTONY) + 0.2f * (k.get(Skills.FARM) - 0.5f);
		s[Purpose.BED] = 0.3f + (evening() ? 0.3f : 0) + (sheepNear() ? 0.2f : 0) + (plan == Strategy.SURVIVOR || plan == Strategy.SETTLER ? 0.25f : 0)
				+ 0.2f * (who.cautionScale() - 1) + (who.believes("night_belongs_to_monsters") ? 0.2f : 0);
		int wood = MindSense.count(c, n -> n.endsWith("_planks")) + 4 * items.getOrDefault("log", 0);
		s[Purpose.HOME] = 0.25f + (plan == Strategy.BUILDER || plan == Strategy.SETTLER ? 0.5f : 0) + 0.25f * (k.get(Skills.BUILD) - 0.5f)
				+ 0.15f * who.sin(Sins.PRIDE) + (wood >= 32 ? 0.1f : 0) - (plan == Strategy.SPEEDRUN ? 0.4f : 0) - (crowdedBy() != null ? 0.3f : 0);
		boolean knowsLittle = c.caves.nearest(160) == null && c.places.find("village") == null;
		s[Purpose.EXPLORE] = 0.1f + 0.45f * who.curiosity + (plan == Strategy.EXPLORER || plan == Strategy.TRADER ? 0.35f : 0)
				+ (knowsLittle ? 0.15f : 0) + 0.15f * who.sin(Sins.LUST);
		float[] lean = c.purpose.choiceLean();
		for (int i = 0; i < Purpose.CHOICES; i++) s[i] += lean[i] - 0.3f * stoneTried[i] + 0.12f * (float) random.nextGaussian();
		return s;
	}

	/** Why it went for this one, in a few words (what weighed most). */
	private String reason(int choice) {
		int plan = c.personality.plan;
		Purpose.Style group = c.purpose.groupStyle();
		float lean = c.purpose.choiceLean()[choice];
		if (lean > 0.12f && group != null) return "its village wants it (" + group.word + " leader)";
		return switch (choice) {
			case Purpose.IRON -> plan == Strategy.MINER || plan == Strategy.SPEEDRUN || plan == Strategy.WARRIOR ? "its plan" : c.caves.nearest(160) != null
					? "a cave it knows" : "the usual next step";
			case Purpose.FOOD -> "running low";
			case Purpose.BED -> evening() ? "night is coming" : "sheep about";
			case Purpose.HOME -> plan == Strategy.BUILDER || plan == Strategy.SETTLER ? "its plan" : "somewhere to come back to";
			default -> plan == Strategy.EXPLORER || plan == Strategy.TRADER ? "its plan" : "curious what's out there";
		};
	}

	/** Sheep it can see or knows are close (within 48 blocks). */
	private boolean sheepSeen() {
		return !c.player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class, c.player.getBoundingBox().inflate(Eyes.RANGE), x -> x.isAlive()).isEmpty();
	}

	/** Off on a trip: ready for the worst first (food, blocks, a spare pickaxe; valuables left at home before a risky one). */
	/** Runner mode: off for diamonds now (its iron gear made, or iron enough), whatever its nerve says. */
	private boolean diamondRun;
	private long runnerTradeAt = -100_000, runnerCaneAt = -100_000;

	/**
	 * Runner mode (/xen set runner true): the run to the Nether and nothing else, in order and quick, each step started at
	 * once (no weighing it up): wood, a table and tools, stone, coal, iron, smelting, food, more iron for its iron gear,
	 * sugar cane (a little farm of it by the water: paper for a book), diamonds, a diamond pickaxe, obsidian (water poured
	 * on lava) and the portal. Each its own usual way (caves it knows, only ore it can see, a villager traded with if it
	 * comes across one); monsters in the way it fights as ever (danger is its own call). -1: nothing of the run to do now
	 * (or it's been to the Nether: its own life again).
	 */
	private int runner(float[] f, boolean[] can, Map<String, Integer> items) {
		int tier = c.crafter.pickTier();
		int hunger = c.player.getFoodData().getFoodLevel(), meals = items.getOrDefault("food", 0);
		int raw = items.getOrDefault("raw_iron", 0), iron = raw + items.getOrDefault("iron_ingot", 0);
		int coal = items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0);
		boolean dark = f[Mind.NIGHT] > 0.5f, below = f[Mind.UNDERGROUND] > 0.5f;
		if (can[Mind.EAT] && hunger < 14) return why(Mind.EAT, "runner: eating");
		if (c.player.getHealth() < 10 && hunger < 18 && can[Mind.FOOD]) {
			c.chores.forWool = false;
			return why(Mind.FOOD, "runner: hurt, food to heal");
		}
		if (dark && tier == 0 && f[Mind.SHELTERED] < 0.5f && !below && can[Mind.SHELTER]) return why(Mind.SHELTER, "runner: night and no pickaxe yet: dig in");
		if (can[Mind.CRAFT]) return why(Mind.CRAFT, "runner: crafting (tools, gear)");
		if (tier == 0) return can[Mind.WOOD] ? why(Mind.WOOD, "runner: wood (a table and a pickaxe)") : -1;
		if (tier == 1) return can[Mind.STONE] ? why(Mind.STONE, "runner: stone (stone tools, a furnace)") : -1;
		if (dark && !below && tier >= 2 && can[Mind.MINE]) return why(Mind.MINE, "runner: night: mining (safe under the ground)");
		if (raw >= 1 && can[Mind.SMELT] && (raw >= 3 || tier == 2)) return why(Mind.SMELT, "runner: smelting its iron");
		if (coal < 3 && iron < 24 && can[Mind.MINE] && now() - coalTriedAt > 2400 && (c.eyes.count("coal") > 0 || c.chores.fuel() == 0)) {   // (coal it has seen, or nothing at all to burn: else the iron trip brings it)
			fuelRun = true;
			return why(Mind.MINE, "runner: coal (to smelt with)");
		}
		if (tier == 2 && iron < 3 && can[Mind.MINE]) return trip(Mind.MINE, false, can, items, "runner: iron (an iron pickaxe)");
		if (can[Mind.TRADE] && now() - runnerTradeAt > 20 * 60 * 5) {           // (a villager by the way: a trade, then on)
			runnerTradeAt = now();
			return why(Mind.TRADE, "runner: a villager to trade with");
		}
		if (meals < 3 && hunger < 20 && can[Mind.FOOD]) {
			c.chores.forWool = false;
			return why(Mind.FOOD, "runner: food for the run");
		}
		Plans.Step step = c.plans.advice();                                     // (its iron gear: a shield, a sword, a bucket, a chestplate)
		if (step != null && step.option >= 0 && step.option < can.length && can[step.option] && step.option != Mind.EXPLORE)
			return why(step.option, "runner: " + step.how);
		boolean gear = c.crafter.has("_chestplate") && c.crafter.hasShield() && (items.getOrDefault("bucket", 0) + items.getOrDefault("water_bucket", 0) + items.getOrDefault("lava_bucket", 0) > 0);
		if (tier == 3 && !gear && iron < 17 && !diamondRun && can[Mind.MINE]) return trip(Mind.MINE, false, can, items, "runner: more iron (iron gear)");
		int cane = items.getOrDefault("sugar_cane", 0), paper = items.getOrDefault("paper", 0) + 3 * items.getOrDefault("book", 0);
		if (cane >= 4 && c.farmer.caneHome == null) {                         // (sugar cane by the water here: the spare planted, a little farm of it)
			c.farmer.caneHome = c.player.blockPosition().immutable();
			c.journal("thinks", "runner: plants its spare sugar cane by the water here (a little farm)");
		}
		if (tier >= 3 && cane + paper < 3 && c.plans.goal == null && now() - runnerCaneAt > 20 * 60 * 15 && c.eyes.count("sugar_cane") > 0) {
			runnerCaneAt = now();
			c.plans.setGoal("sugar_cane", 6, null);
			Plans.Step s = c.plans.advice();
			if (s != null && s.option >= 0 && can[s.option]) return why(s.option, "runner: sugar cane (paper for a book, and a little farm)");
		}
		if (tier == 3 && can[Mind.MINE]) {
			diamondRun = true;
			return trip(Mind.MINE, true, can, items, "runner: diamonds (a diamond pickaxe)");
		}
		if (tier >= 4 && !c.nether.busy() && now() > netherTryAt) {
			netherTryAt = now() + 20 * 60 * 5;
			String trip = c.nether.go("visit", 0);
			c.journal("thinks", "runner: the portal and the Nether (" + trip + ")");
			if (trip.startsWith("You will")) {
				c.chatter(c.pick3("Diamond pickaxe. Portal time.", "Obsidian next, then the Nether.", "Nether time!"), true);
				return why(Mind.REST, "runner: off to the Nether");
			}
		}
		return -1;
	}

	private int trip(int option, boolean deep, boolean[] can, Map<String, Integer> items, String why) {
		Prepared.Step first = c.prepared.before(option, deep, can, items);
		return first != null ? why(first.option(), first.why()) : why(option, why);
	}

	private int why(int option, String why) {
		agendaWhy = why;
		return option;
	}

	/** More to mine for: iron for tools and armor, then diamonds. */
	private boolean wantsOre(int tier, int iron) {
		boolean armor = true;
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) armor &= !c.player.getItemBySlot(slot).isEmpty();
		return tier == 2 ? iron < 3 : !armor && iron < 24 || diamonds() < 3;
	}

	private int wool() {
		int n = 0;
		for (var e : c.items().entrySet()) if (e.getKey().endsWith("_wool")) n += e.getValue();
		return n;
	}

	private boolean sheepNear() {
		for (var sheep : c.player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class, c.player.getBoundingBox().inflate(24), x -> x.isAlive())) {
			if (c.player.hasLineOfSight(sheep)) return true;
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
		if (c.plans.start(o)) {                                               // its mind chose what its plan's next step is: that step
			planStep = true;
			optionUntil = now + 20 * 60 * 8;
			return true;
		}
		boolean begun = startUsual(o, nearby, now);
		if (c.plans.takenItsWay(begun)) planStep = true;                     // (its plan's step, done its own usual way: the plan learns from it too)
		return begun;
	}

	private boolean startUsual(int o, boolean nearby, long now) {
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
			if (o == Mind.SHELTER && home != null && !c.player.blockPosition().closerThan(home, 12) && c.player.blockPosition().closerThan(home, 96)) {   // a home: it goes back there for the night
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
		if (planStep && current == null) return late || c.plans.stepOver();
		return switch (option) {
			case Mind.CRAFT -> !c.crafter.hasOrder() || late;
			case Mind.EAT -> !c.chores.busy() || late;
			case Mind.SLEEP -> !c.player.level().isDarkOutside() && !c.player.isSleeping() || late;
			case Mind.HELP -> !c.chores.busy();
			case Mind.FIGHT -> late || c.chosenFoe == null || !c.chosenFoe.isAlive() || c.chosenFoe.level() != c.player.level()
					|| c.chosenFoe.distanceTo(c.player) > 24 || c.diplomacy.atPeace(c.chosenFoe);
			case Mind.ENCHANT -> !c.enchanter.on || late;
			case Mind.SHELTER -> late || home != null && c.player.blockPosition().closerThan(home, 6) || !c.chores.busy() && (home == null || !c.player.blockPosition().closerThan(home, 96));
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
	/** The choice going now is a step of its plan. */
	private boolean planStep;

	boolean finishOption(boolean died) {
		int o = option;
		float[] before = optionFeatures;
		if (planStep) {
			planStep = false;
			c.plans.finished(died);
		}
		if (o >= 0) lastOption = o;
		if (o > Mind.REST) {                                                   // (what it did lately: it can tell)
			done.remove((Integer) o);
			done.addLast(o);
			while (done.size() > 6) done.removeFirst();
		}
		option = -1;
		optionFeatures = null;
		c.chosenFoe = null;
		fleeFrom = null;
		guardWho = null;
		followWho = null;
		if (o < 0 || before == null || c.mod.mind == null || c.player == null) return false;
		long now = c.player.level().getGameTime();
		float minutes = Math.max(0.5f, (now - optionAt) / 1200f);           // (a choice counts as half a minute at least: many quick ones in a row mustn't look like no time at all)
		float[] after = MindSense.features(c);
		if (died) after[Mind.HEALTH] = 0;
		float r = Mind.reward(before, after, o, minutes), h = Mind.harm(before, after, died);
		boolean gotDeeper = o == Mind.MINE && optionWhere != null && optionWhere.getY() - c.player.getBlockY() >= 6;   // (down its staircase: on its way, not nothing)
		boolean cutOff = !c.chores.endedItself;                               // (something else cut it short: not that it came to nothing)
		if (r > 0.05f || gotDeeper) failsInRow[o] = 0;
		else if (!cutOff && ++failsInRow[o] >= 3 && o != Mind.EAT && o != Mind.FLEE && o != Mind.FIGHT && o != Mind.FOLLOW && o != Mind.HELP && o != Mind.GUARD
				&& o != Mind.REST && o != Mind.SLEEP) {                                    // (the same choice, again and again, and nothing: a flooded staircase, a night by the sea)
			quickUntil[o] = now + (2400L << Math.min(3, failsInRow[o] - 3));
			c.journal("thinks", String.format(Locale.ROOT, "%s came to nothing %d times in a row: something else for %d min", Mind.SAYS[o], failsInRow[o], (2400 << Math.min(3, failsInRow[o] - 3)) / 1200));
		}
		if (r > 0 && c.hunch.assistedSince(optionAt)) r *= 0.5f;             // (luck helped: its mind learns less from that than from what it did itself)
		c.mod.mind.remember(before, o, r, h, after, died, minutes, Mind.bits(MindSense.allowed(c, after, false)));
		if (!notItsDoing) {                                                   // (its outcome memory: how that went, here, like that, against what it expected; not a /kill)
			String thought = c.experience.record(optionSituation, o, r, h, died, minutes, optionWhere != null ? optionWhere : c.player.blockPosition(), optionFollowed,
					optionExpect, optionSituation == null ? null : c.experience.situation());
			if (thought != null) c.journal("reflects", thought);
		}
		optionSituation = null;
		optionExpect = null;
		optionWhere = null;
		c.mod.mindExperiences++;
		if (o == Mind.MINE && c.places.get("mine hut") == null && c.places.get("mine") != null && !c.builder.busy() && !c.player.isCreative()
				&& c.crafter.pickTier() >= 3                                            // (once its mine has paid off: the first trips are for iron, not a hut)
				&& c.chores.mineRecordY != Integer.MIN_VALUE && c.player.blockPosition().closerThan(c.places.get("mine"), 24)) {   // its mine gets a proper entrance
			String hut = c.builder.startPlan(Architect.mineEntrance(c.places.get("mine"), c.chores.mineRecordDir,
					Architect.ofWood(MindSense.count(c, n -> n.startsWith("spruce_")) > 8 ? "spruce" : "oak", true, false)), "", "mine");
			c.chatter("My mine needs a proper entrance. " + xen.mod.talk.Chat.firstPerson(hut), false);
		}
		Tribe t = c.tribe();
		if (t != null) t.laws.didWork(c, o);
		c.journal("learns", String.format(Locale.ROOT, "Xen 2.0: %s took %.1f min, reward %+.2f, harm %.2f%s", Mind.OPTIONS[o], minutes, r, h, died ? " (died)" : ""));
		c.diary.spent(Mind.OPTIONS[o], (now - optionAt) / 1200f, r > 0.05f, died);
		if (!notItsDoing) c.rules.finished(o, r, h, died);
		return r > 0.05f;
	}

	// -------------------------------------------------------------------------------- words
	/** For its notes: all three, and only what's true. */
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
