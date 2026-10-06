package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Xen 2.0: a choice for everything it could do with a tool, a block or an animal: do it, don't, or later (and when,
 * at most 30 minutes on). Every choice goes through the same three brains:
 * <ul>
 *   <li><b>Doer</b>: what it would get (from {@link Facts}) and how much it needs that right now;</li>
 *   <li><b>Doubter</b>: what could go wrong or get worse: dark coming, monsters about, lava behind the block, a drop
 *   under it, a pickaxe too weak, the last two of a kind;</li>
 *   <li><b>Gut</b>: what it pictures happening next if it does it (and, in {@link Gut}, the plan that takes over when
 *   it has to survive).</li>
 * </ul>
 * Then want minus caution times risk: clearly worth it, it does it. Not worth it, but only because of something that
 * passes (night, a zombie, a weak pickaxe, a full belly): later, at a time it picks. Not worth it at all: no. A close
 * call makes it a little confused ({@link Confusion}); a confused Xen hesitates, and now and then goes with the other
 * answer (never against a hard no: lava stays lava).
 *
 * The mining choice follows how a player mines (from a recorded game: 77% of the blocks broken were at the feet or
 * the head, next to the player: a tunnel two high; almost nothing straight down; copper left in the wall).
 */
final class Choices {
	enum Verdict { DO, NOT, LATER }

	/** At most this far off, a "later" (30 minutes). */
	static final long MAX_LATER = 20L * 60 * 30;

	static final class Choice {
		final String what;
		final Verdict verdict;
		final long until;
		final float want, risk;
		final boolean hard;
		final String doer, doubter, gut;

		Choice(String what, Verdict verdict, long until, float want, float risk, boolean hard, String doer, String doubter, String gut) {
			this.what = what;
			this.verdict = verdict;
			this.until = until;
			this.want = want;
			this.risk = risk;
			this.hard = hard;
			this.doer = doer;
			this.doubter = doubter;
			this.gut = gut;
		}

		boolean yes() {
			return verdict == Verdict.DO;
		}

		Choice as(Verdict v, long until) {
			return new Choice(what, v, until, want, risk, hard, doer, doubter, gut);
		}

		/** "fish: later (in 8 min) | doer: ... | doubter: ... | gut: ...". */
		String says(long now) {
			String v = switch (verdict) {
				case DO -> "yes";
				case NOT -> "no";
				case LATER -> "later (in " + Math.max(1, Math.round((until - now) / 1200f)) + " min)";
			};
			StringBuilder sb = new StringBuilder(what).append(": ").append(v);
			if (!doer.isEmpty()) sb.append(" | doer: ").append(doer);
			if (!doubter.isEmpty()) sb.append(" | doubter: ").append(doubter);
			if (!gut.isEmpty()) sb.append(" | gut: ").append(gut);
			return sb.toString();
		}
	}

	/** What the three brains say about one choice, before it's weighed. */
	static final class Weigh {
		float want, risk, wantLater;
		boolean passing, hard;
		/** When it thinks again, after a "later" (the longest of what passes). */
		long later = 20 * 60;
		String doer = "", doubter = "", gut = "";

		Weigh risk(float r, String why, boolean passes, long laterTicks) {
			risk += r;
			doubter = doubter.isEmpty() ? why : doubter + "; " + why;
			if (passes) {
				later = passing ? Math.max(later, laterTicks) : laterTicks;
				passing = true;
			}
			return this;
		}

		Weigh no(String why) {
			hard = true;
			doubter = doubter.isEmpty() ? why : why + "; " + doubter;
			return this;
		}
	}

	/** The weighing itself (no game in it: the tests run it). caution: 1 for most, more for the timid. */
	static Choice decide(String what, Weigh w, long now, float caution) {
		float score = w.want - caution * w.risk;
		Verdict v;
		long until = 0;
		if (w.hard) v = Verdict.NOT;
		else if (w.want >= 0.05f && score > 0.05f) v = Verdict.DO;
		else if (w.passing && w.want >= 0.15f || w.wantLater >= 0.3f) {
			v = Verdict.LATER;
			until = now + Math.max(20, Math.min(MAX_LATER, w.later));
		} else v = Verdict.NOT;
		return new Choice(what, v, until, w.want, w.risk, w.hard, w.doer, w.doubter, w.gut);
	}

	private final Companion c;
	/** Its last choice about each thing (a block, an animal, fishing), the newest last. */
	private final Map<String, Choice> last = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Choice> e) {
			return size() > 512;
		}
	};
	/** The last choice worth telling (for "what are you thinking" and the journal). */
	Choice latest;
	private long momentAt;
	int made, yes, no, later;

	Choices(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private float caution() {
		return c.personality.cautionScale();
	}

	/** Weigh it, add its confusion, remember it; journal it if the answer changed. */
	private Choice choose(String key, Weigh w, boolean tell) {
		long now = now();
		Choice ch = decide(key.contains("@") ? key.substring(0, key.indexOf('@')) : key, w, now, caution());
		float score = w.want - caution() * w.risk;
		if (!w.hard && Math.abs(score) < 0.08f && w.want >= 0.05f) c.confusion.closeCall(key);
		if (!ch.hard && c.confusion.flips()) {                        // confused: the other answer, now and then (a hesitation, a "why not")
			ch = ch.yes() ? ch.as(Verdict.LATER, now + 20 * 5) : ch.verdict == Verdict.LATER && w.want >= 0.25f ? ch.as(Verdict.DO, 0) : ch;
		}
		Choice before = last.put(key, ch);
		made++;
		switch (ch.verdict) {
			case DO -> yes++;
			case NOT -> no++;
			case LATER -> later++;
		}
		if (tell && (before == null || before.verdict != ch.verdict)) {
			latest = ch;
			c.journal("chooses", ch.says(now));
		}
		return ch;
	}

	/** A "later" that still holds (nothing to think over again until then), or null. */
	private Choice holding(String key) {
		Choice ch = last.get(key);
		return ch != null && ch.verdict == Verdict.LATER && now() < ch.until ? ch : null;
	}

	private boolean busy() {
		return c.chores.busy() && !c.chores.own || c.builder.busy() || c.commandedTo != null || c.mode == Companion.Mode.FOLLOW
				|| c.crafter.hasOrder() || c.fightingNow() || c.adventure.on || c.nether.busy();
	}

	private int hostilesWithin(double r) {
		return c.player.level().getEntitiesOfClass(Monster.class, c.player.getBoundingBox().inflate(r), LivingEntity::isAlive).size();
	}

	private int foodInBag() {
		return c.items().getOrDefault("food", 0);
	}

	/** How much it needs food: 1 starving with nothing to eat, down to 0.05 fed with plenty on it. */
	private float hunger() {
		int food = c.player.getFoodData().getFoodLevel() - Math.round(4 * (c.personality.hidden(Personality.APPETITE) - 0.5f)), bag = foodInBag();   // (always hungry: it feels it sooner)
		return food <= 12 && bag == 0 ? 1f : food <= 17 && bag < 3 ? 0.55f : bag < 6 ? 0.25f : 0.05f;
	}

	/** Ticks from now until the sun is up again (0 in the day). */
	private long untilMorning() {
		long t = Compat.timeOfDay(c.player.level()) % 24000;
		return t >= 12500 ? 24000 - t + 200 : 0;
	}

	// ---------------------------------------------------------------------------------- fishing
	/** Is its line of sight (where it looks, 16 blocks) on water? */
	boolean lookingAtWater() {
		var p = c.player;
		Vec3 eye = p.getEyePosition(), to = eye.add(p.getLookAngle().scale(16));
		var hit = p.level().clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, p));   // (past lily pads' edges, grass, reeds)
		return hit.getType() == HitResult.Type.BLOCK && p.level().getFluidState(hit.getBlockPos()).isSource()
				&& p.level().getBlockState(hit.getBlockPos()).is(net.minecraft.world.level.block.Blocks.WATER);
	}

	/**
	 * The fishing rod: fish (water close by, and it's looking at it), don't, or fish later (up to 30 minutes on). Not
	 * looking at the water yet: it looks, and thinks it over in a moment, like a player who spots a pond.
	 */
	Choice fish() {
		Choice held = holding("fish");
		if (held != null && hunger() < 1f) return held;
		Weigh w = new Weigh();
		boolean rod = c.fisher.hasRod();
		if (!rod && c.items().getOrDefault("string", 0) < 2) return choose("fish", w.no("no rod, and no string to make one"), false);
		BlockPos water = c.fisher.waterNear();
		if (water == null) return choose("fish", w.no("no water close by"), false);
		if (!lookingAtWater()) {
			c.hands.face(Vec3.atCenterOf(water));                           // (a look at the water first)
			w.want = 0.2f;
			w.wantLater = 0.3f;
			w.later = 30;
			w.doer = "water over there: a look first";
			return choose("fish", w, false);
		}
		float minutes = Math.max(0, (12000 - Compat.timeOfDay(c.player.level()) % 24000) / 1200f);
		boolean rain = c.player.level().isRaining();
		float catches = Facts.catchesIn(Math.min(5, minutes), rain);
		float need = hunger();
		boolean likes = c.personality.believes("fish_free_food") || c.personality.believes("animals_kindness");
		float angler = c.personality.hidden(Personality.ANGLER);                    // (its hidden love of fishing)
		w.want = need + (likes ? 0.3f : 0.12f) + 0.35f * (angler - 0.3f) + (rain ? 0.05f : 0) + (rod ? 0 : -0.05f);
		w.doer = need >= 1 ? "starving, nothing to eat: fish are food" : need >= 0.5f ? "hungry, little food on me"
				: likes ? "I like fishing, and here's water" : "a quiet moment by the water";
		w.gut = String.format(Locale.ROOT, "about %.0f catches in %.0f minutes, %.0f hunger cooked%s", catches, Math.min(5, minutes),
				Facts.fishHunger(catches), rain ? " (rain: they bite faster)" : "");
		if (c.player.level().isDarkOutside() || minutes < 1) {
			w.risk(0.6f * (1.3f - 0.6f * c.personality.hidden(Personality.NIGHT_OWL)), "night: monsters come out", true, Math.max(20 * 60, untilMorning()));
		}
		int mobs = hostilesWithin(16);
		if (mobs > 0) w.risk(0.4f + 0.1f * mobs, mobs == 1 ? "a monster about" : mobs + " monsters about", true, 20 * 60);
		if (busy()) w.risk(0.5f, "busy with something else", true, 20 * 60 * 8);
		if (c.player.getHealth() < 8) w.risk(0.2f, "hurt", true, 20 * 60 * 2);
		if (foodInBag() >= 6) {
			w.wantLater = 0.35f;
			w.later = Math.max(w.later, 20 * 60 * 15);
			w.doubter = w.doubter.isEmpty() ? "plenty of food on me: fish when it runs low" : w.doubter;
		}
		return choose("fish", w, true);
	}

	/** Every few seconds, free and with nothing on: a tool it could use here (its rod by the water). */
	Action moment() {
		var p = c.player;
		if (p == null || c.minion || c.mode != Companion.Mode.FREE || c.fisher.on || now() < momentAt || busy() || c.chores.busy() || c.builder.busy()) return null;
		momentAt = now() + 100;
		if (!c.fisher.hasRod() && c.items().getOrDefault("string", 0) < 2) return null;
		Choice f = fish();
		if (!f.yes()) return null;
		String go = c.fisher.start();
		if (!go.startsWith("You will")) return null;
		c.goals.instant = "fishing";
		c.chatter(c.pick3("Nice spot for fishing.", "Let's see what's biting.", "A bit of fishing."), false);
		return Action.IDLE;
	}

	/** Still worth fishing (every few seconds while it fishes)? Monsters, night: it reels in and fishes later. */
	boolean keepFishing() {
		if (c.player.level().isDarkOutside() || hostilesWithin(10) > 0) {
			Weigh w = new Weigh();
			w.want = 0.3f;
			w.doer = "was fishing";
			if (c.player.level().isDarkOutside()) w.risk(1f, "it got dark", true, Math.max(20 * 60, untilMorning()));
			else w.risk(1f, "a monster came close", true, 20 * 60);
			Choice ch = choose("fish", w, true);
			return ch.yes();
		}
		return true;
	}

	// ----------------------------------------------------------------------------------- blocks
	/**
	 * The hard no's for any block it's about to break (whatever it's for): lava behind it, or the block under its feet
	 * with a drop, water or lava under that. Null: nothing against it.
	 */
	String unsafeToBreak(BlockPos q) {
		ServerLevel level = (ServerLevel) c.player.level();
		if (Chores.lavaBehind(level, q)) return "lava behind it";
		BlockPos feet = c.player.blockPosition();
		if (q.getX() == feet.getX() && q.getZ() == feet.getZ() && q.getY() == feet.getY() - 1) {
			int air = 0;
			for (int d = 1; d <= 4; d++) {
				BlockPos u = q.below(d);
				if (!level.getFluidState(u).isEmpty()) return "water or lava right under it";
				if (level.getBlockState(u).getCollisionShape(level, u).isEmpty()) air++;
				else break;
			}
			if (air >= 3) return "a drop under it: I'd fall in";
		}
		return null;
	}

	/** The pickaxe an ore takes to drop anything (1 wooden, 2 stone, 3 iron, 4 diamond; 0: anything). */
	static int tierNeeded(String kind) {
		return switch (kind == null ? "" : kind) {
			case "coal" -> 1;
			case "iron", "copper", "lapis" -> 2;
			case "gold", "redstone", "diamond", "emerald" -> 3;
			case "debris", "obsidian" -> 4;
			default -> 0;
		};
	}

	/** What a block is worth to it now (the Doer), without the shape of its mining or the risks. */
	private float worth(String kind, String name, Weigh w) {
		int tier = c.crafter.pickTier();
		var items = c.items();
		int cobble = items.getOrDefault("cobblestone", 0) + items.getOrDefault("cobbled_deepslate", 0);
		float v;
		if (kind == null) {
			if (name.equals("stone") || name.equals("deepslate") || name.equals("cobblestone")) {
				v = cobble < 32 ? 0.35f : 0.08f;
				w.doer = cobble < 32 ? "stone for tools and a furnace" : "stone: I have plenty";
				if (tier < 1) w.risk(1f, "no pickaxe: stone would drop nothing", true, 20 * 60 * 5);
			} else if (name.endsWith("dirt") || name.equals("grass_block")) {
				v = 0.05f;
				w.doer = "dirt: a block to build with";
			} else if (name.endsWith("leaves")) {
				v = 0f;
				w.doer = "leaves: nothing in them";
			} else {
				v = 0.03f;
				w.doer = name.replace('_', ' ');
			}
			return v;
		}
		v = switch (kind) {
			case "diamond", "debris" -> 1f;
			case "emerald" -> 0.6f;
			case "iron" -> items.getOrDefault("raw_iron", 0) + items.getOrDefault("iron_ingot", 0) < 24 ? 0.8f : 0.3f;
			case "coal" -> items.getOrDefault("coal", 0) + items.getOrDefault("torch", 0) / 4 < 16 ? 0.6f : 0.25f;
			case "gold" -> c.personality.believes("gold_for_fools") ? 0f : 0.4f;
			case "lapis" -> 0.3f;
			case "redstone" -> 0.25f;
			case "copper" -> 0.08f;
			case "log" -> 0.5f;
			case "sand", "gravel", "clay" -> 0.12f;
			case "obsidian" -> 0.35f;
			default -> 0.02f;
		};
		w.doer = switch (kind) {
			case "copper" -> "copper: not worth much yet";
			case "log" -> "wood";
			default -> kind + (v >= 0.5f ? ": I want that" : "");
		};
		int needs = tierNeeded(kind);
		if (needs > tier) w.risk(1f, "my pickaxe is too weak: it takes " + Crafter.tierName(needs), true, 20 * 60 * 10);
		return v;
	}

	/**
	 * How a player mines, from a recorded game (95 blocks broken): 77% at the feet or the head, right next to them (a
	 * tunnel two high), 12% one down (a step), a few above the head, almost none straight down. 1 for that shape, less
	 * for the rest.
	 */
	static float shape(int dy, int side) {
		float shape = dy == 0 || dy == 1 ? 1f : dy == -1 ? 0.7f : dy == 2 ? 0.5f : 0.25f;
		if (side > 1) shape *= 0.6f;
		if (side == 0 && dy < 0) shape *= 0.3f;                              // (straight down: almost never)
		return shape;
	}

	/**
	 * Mine this block, or not, or later. Its worth (ore, wood, stone it needs), in the shape a player mines (at its feet
	 * and head, right next to it; above its head or under its feet much less), against what could go wrong.
	 */
	Choice mine(BlockPos q) {
		String key = "mine@" + q.asLong();
		Choice held = holding(key);
		if (held != null) return held;
		ServerLevel level = (ServerLevel) c.player.level();
		BlockState s = level.getBlockState(q);
		Weigh w = new Weigh();
		String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		if (s.isAir() || !s.getFluidState().isEmpty()) return choose(key, w.no("nothing to mine"), false);
		if (s.getDestroySpeed(level, q) < 0) return choose(key, w.no("it can't be broken"), false);
		String kind = Eyes.kind(s);
		if ("built".equals(kind) || "chest".equals(kind) || "spawner".equals(kind) || "crop".equals(kind)) return choose(key, w.no("someone made that"), false);
		String unsafe = unsafeToBreak(q);
		if (unsafe != null) return choose(key, w.no(unsafe), false);
		float v = worth(kind, name, w);
		float picky = c.personality.hidden(Personality.PICKINESS);                  // (a picky miner leaves the stone; one who mines anything takes it)
		v *= kind != null && v >= 0.25f ? 0.85f + 0.3f * picky : 1.3f - 0.8f * picky;
		BlockPos feet = c.player.blockPosition();
		int dy = q.getY() - feet.getY(), side = Math.max(Math.abs(q.getX() - feet.getX()), Math.abs(q.getZ() - feet.getZ()));
		float shape = shape(dy, side);
		boolean ore = kind != null && !kind.equals("log") && v >= 0.25f;
		w.want = ore || "log".equals(kind) ? v * (0.6f + 0.4f * shape) : v * shape;
		for (Direction d : Direction.values()) {
			BlockPos n = q.relative(d);
			if (n.equals(feet) || n.equals(feet.above())) continue;
			if (level.getFluidState(n).isSource() && level.getBlockState(n).is(net.minecraft.world.level.block.Blocks.WATER)) {
				w.risk(0.3f, "water would pour in", false, 0);
				break;
			}
		}
		String above = BuiltInRegistries.BLOCK.getKey(level.getBlockState(q.above()).getBlock()).getPath();
		if ((above.equals("gravel") || above.endsWith("sand")) && dy >= 0) w.risk(0.15f, above + " above it would fall", false, 0);
		int mobs = hostilesWithin(6);
		if (mobs > 0) w.risk(0.25f, "a monster close by", true, 20 * 60);
		float speed = s.getDestroyProgress(c.player, level, q);
		float seconds = speed <= 0 ? 99 : 1f / speed / 20f;
		if (seconds > 6) w.risk(0.2f, String.format(Locale.ROOT, "slow: %.0f seconds with what I have", seconds), true, 20 * 60 * 10);
		w.gut = String.format(Locale.ROOT, "%s in %.1f s%s", kind == null ? name.replace('_', ' ') : kind, Math.min(seconds, 99),
				dy < 0 && side == 0 ? ", and a hole under me" : "");
		return choose(key, w, ore);
	}

	/**
	 * Every block around it (4 blocks, the ones it can see), each with its yes, no or later: the best yes to mine, or
	 * null. For its spare moments (a furnace smelting, standing about).
	 */
	BlockPos bestToMine(int radius) {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition(), best = null;
		Vec3 eye = c.player.getEyePosition();
		float bestWant = 0;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-radius, -1, -radius), feet.offset(radius, 2, radius))) {
			BlockState s = level.getBlockState(q);
			if (s.isAir() || !s.getFluidState().isEmpty()) continue;
			if (eye.distanceTo(Vec3.atCenterOf(q)) > c.player.blockInteractionRange() - 0.3) continue;
			boolean open = false;
			for (Direction d : Direction.values()) if (level.getBlockState(q.relative(d)).isAir()) open = true;
			if (!open) continue;
			var hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(q), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c.player));
			if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(q)) continue;   // (only what it sees)
			Choice ch = mine(q.immutable());
			if (ch.yes() && ch.want > bestWant) {
				bestWant = ch.want;
				best = q.immutable();
			}
		}
		return best;
	}

	// ---------------------------------------------------------------------------------- animals
	/** Kill this animal (or shear it, a sheep with shears in its bag), or not, or later: what it drops against why not. */
	Choice animal(LivingEntity a) {
		String kind = Facts.kind(a), key = "animal@" + a.getUUID();
		Choice held = holding(key);
		if (held != null && hunger() < 1f) return held;
		Weigh w = new Weigh();
		if (a.isBaby()) return choose(key, w.no("a baby"), false);
		if (a.hasCustomName() || a instanceof net.minecraft.world.entity.Mob m && m.isLeashed()) return choose(key, w.no("someone's pet"), false);
		Facts.Drops drops = Facts.drops(a, c.player);
		float food = Facts.hunger(drops), need = hunger();
		boolean bed = c.items().keySet().stream().anyMatch(k -> k.endsWith("_bed")) || c.bedAt != null;
		int wool = c.chores.wool();
		boolean sheep = a instanceof Sheep;
		boolean shears = c.hands.hotbar(st -> st.getItem() == Items.SHEARS) >= 0;
		boolean shearable = sheep && ((Sheep) a).readyForShearing() && shears;
		float woolNeed = sheep && !bed && wool < 3 ? 0.7f : sheep && wool < 3 ? 0.15f : 0f;
		float woolDrop = drops.mean("wool");                                          // (a sheared sheep drops none: nothing in it for a bed)
		w.want = need * Math.min(1f, food / 12f) + woolNeed * (shearable ? 1f : woolDrop <= 0 ? 0f : Math.min(1f, woolDrop / 3f + 0.5f));
		w.doer = woolNeed >= 0.7f ? "wool for a bed" : need >= 0.5f ? String.format(Locale.ROOT, "food: about %.0f hunger cooked", food)
				: "not hungry";
		w.gut = shearable ? String.format(Locale.ROOT, "shearing: about %.0f wool, and it lives", Facts.SHEAR_WOOL)
				: "it drops " + drops.says() + (drops.rolled ? "" : " (from what I know)");
		int same = c.player.level().getEntitiesOfClass(Animal.class, a.getBoundingBox().inflate(16), x -> x.isAlive() && !x.isBaby() && Facts.kind(x).equals(kind)).size();
		if (same <= 2 && !shearable && need < 1f) w.risk(0.5f, same <= 1 ? "the last one here" : "the last two: they could breed", false, 0);
		if (penned(a)) w.risk(0.6f, "it's in a pen: someone keeps it", false, 0);
		if (c.player.level().getEntitiesOfClass(Monster.class, a.getBoundingBox().inflate(6), LivingEntity::isAlive).size() > 0) {
			w.risk(0.3f, "a monster next to it", true, 20 * 30);
		}
		if (need < 0.25f && woolNeed < 0.15f) {
			w.wantLater = 0.4f;
			w.later = 20 * 60 * 10;
			if (w.doubter.isEmpty()) w.doubter = "I'll want it when I'm hungry";
		}
		return rename(choose(key, w, false), (shearable ? "shear the " : "kill the ") + kind);
	}

	private static Choice rename(Choice ch, String what) {
		return new Choice(what, ch.verdict, ch.until, ch.want, ch.risk, ch.hard, ch.doer, ch.doubter, ch.gut);
	}

	/** Fences around it (two sides or more, within 3 blocks): a pen someone made. */
	private static boolean penned(LivingEntity a) {
		var level = a.level();
		BlockPos at = a.blockPosition();
		int sides = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (int i = 1; i <= 3; i++) {
				String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at.relative(d, i)).getBlock()).getPath();
				if (n.endsWith("_fence") || n.endsWith("_wall") || n.endsWith("_fence_gate")) {
					sides++;
					break;
				}
			}
		}
		return sides >= 2;
	}

	/** The animal it goes for: its choice in the journal (once per animal). */
	void picked(LivingEntity a, Choice ch) {
		if (ch == null || picked.equals(a.getUUID())) return;
		picked = a.getUUID();
		latest = ch;
		c.journal("chooses", ch.says(now()));
	}

	private java.util.UUID picked = new java.util.UUID(0, 0);

	/** Shear this one instead of killing it (a sheep ready for it, and shears)? */
	boolean shearInstead(LivingEntity a) {
		return a instanceof Sheep s && s.readyForShearing() && c.hands.hotbar(st -> st.getItem() == Items.SHEARS) >= 0;
	}

	// ------------------------------------------------------------------------------------- food
	/** Eat now, or later: a monster right on it while it's still healthy, it fights first (eating takes a moment). */
	Choice eat() {
		Weigh w = new Weigh();
		var p = c.player;
		w.want = 0.3f + (20 - p.getFoodData().getFoodLevel()) / 20f;
		w.doer = "hungry: " + p.getFoodData().getFoodLevel() + " of 20";
		w.gut = p.getHealth() < 20 ? "a full belly heals me" : "a full belly";
		int close = hostilesWithin(4);
		if (close > 0 && p.getHealth() >= 10 && p.getFoodData().getFoodLevel() > 6) w.risk(1.5f, "a monster right on me: eating would leave me open", true, 20 * 5);
		return choose("eat", w, close > 0);
	}

	/** For "what are you thinking". */
	String thinking() {
		return latest == null ? "" : latest.says(now());
	}
}
