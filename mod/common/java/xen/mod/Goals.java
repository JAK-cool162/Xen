package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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
		TRADER("become a trader with 5 emeralds"), EXPLORER("see places 300 blocks away"), FRIENDS("make three friends");

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
	BlockPos home;
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
			boolean over = current == Short.EXPLORE ? now > until : !c.chores.busy();
			if (!over) return c.chores.busy();
			learn();
		}
		if (now < next) return false;
		next = now + THINK;
		Short best = null;
		float bestScore = 0.25f;                                         // below this it's happy doing whatever it does
		for (Short s : Short.values()) {
			if (nearby && (s == Short.TRADE || s == Short.EXPLORE)) continue;   // those would take it away from its friend
			float score = (urgency(s) + dreamPull(s)) * (0.5f + liking.get(s)) + random.nextFloat() * 0.15f * c.personality.curiosity;
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		if (best == null) return false;
		buildingHome = best == Short.SHELTER && dream == Long.HOME && home == null && !c.player.level().isDarkOutside();
		String plan = switch (best) {
			case FOOD -> c.chores.hunt(2);
			case SHELTER -> buildingHome ? c.chores.shelter("fort") : c.chores.shelter();
			case WOOD -> c.chores.gather("wood", 4);
			case STONE -> needBlocksForTheNight() || c.crafter.pickTier() == 0 ? c.chores.gather("dirt", Math.max(4, 12 - blocks()))
					: c.chores.gather("stone", 8);
			case ORE -> c.chores.gather("ore", 2);
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
		return c.chores.busy();
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
		return switch (s) {
			case FOOD -> items.getOrDefault("food", 0) > 0 ? 0 : Math.max(0, (16 - food) / 16f) * 1.2f;
			case SHELTER -> outInTheDark && blocks >= 10 ? 0.9f * (1.2f - 0.6f * p.bravery) : 0;
			case WOOD -> tools == 0 && items.getOrDefault("log", 0) < 3 ? 0.9f                 // wood for a pickaxe comes first
					: items.getOrDefault("log", 0) < 4 ? 0.5f * (0.5f + p.diligence) : 0;
			case STONE -> needBlocksForTheNight() ? 0.85f                  // evening, and not enough blocks for a shelter: dirt will do
					: tools == 0 ? 0 : blocks < 10 || tools == 1 && items.getOrDefault("cobblestone", 0) < 3 ? 0.4f * (0.5f + p.diligence) : 0;
			case ORE -> tools == 0 ? 0 : 0.3f * (0.5f + p.bravery);                         // no pickaxe: ore drops nothing
			case TRADE -> c.mod.config.trading && c.trader.villagerNear() != null && c.trader.hasSomethingToTrade() ? 0.35f : 0;
			case EXPLORE -> 0.3f * (0.5f + p.curiosity);
		};
	}

	private int blocks() {
		var items = c.items();
		return items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
	}

	/** Evening (or night already) and too few blocks for a shelter: a player gets some dirt before dark. */
	boolean needBlocksForTheNight() {
		var level = c.player.level();
		long time = Compat.timeOfDay(level);
		return (time >= 11000 && time < 23000 || level.isDarkOutside()) && blocks() < 10;
	}

	/** What its dream adds to a short goal. */
	private float dreamPull(Short s) {
		if (dream == null) return 0;
		var items = c.items();
		int blocks = items.getOrDefault("dirt", 0) + items.getOrDefault("cobblestone", 0);
		return switch (dream) {
			case HOME -> s == Short.STONE && blocks < HOME_BLOCKS ? 0.35f
					: s == Short.SHELTER && home == null && blocks >= HOME_BLOCKS && !c.player.level().isDarkOutside() ? 0.8f : 0;
			case STOCKPILE -> s == Short.WOOD && items.getOrDefault("log", 0) < 32 || s == Short.STONE && items.getOrDefault("cobblestone", 0) < 64
					? 0.35f : 0;
			case TREASURE -> s == Short.ORE ? 0.45f : 0;
			case TRADER -> s == Short.TRADE && urgency(Short.TRADE) > 0 ? 0.45f : s == Short.ORE ? 0.15f : 0;
			case EXPLORER -> s == Short.EXPLORE ? 0.45f : 0;
			case FRIENDS -> s == Short.EXPLORE ? 0.1f : 0;
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
	}

	/** Someone asked it for something: that comes first. */
	void drop() {
		current = null;
		buildingHome = false;
		c.chores.own = false;
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
		return o;
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
		if (dreams.has("home")) {
			JsonArray h = dreams.getAsJsonArray("home");
			home = new BlockPos(h.get(0).getAsInt(), h.get(1).getAsInt(), h.get(2).getAsInt());
		}
		if (dreams.has("origin")) {
			JsonArray h = dreams.getAsJsonArray("origin");
			origin = new int[] {h.get(0).getAsInt(), h.get(1).getAsInt()};
		}
	}
}
