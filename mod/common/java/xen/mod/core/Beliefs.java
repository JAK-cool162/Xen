package xen.mod.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What a Xen believes: its mindset, the rules it lives by ("a true warrior never runs", "the night belongs to the
 * monsters", "share what you have"). Each Xen is born with a few (four to six), the ones its sins make likelier, never
 * two that contradict each other. They are fixed for life, like its sins. Every belief changes what it does:
 * <ul>
 *   <li>its knobs: fear, patience, how much it talks, risk (jumps, drops), sharing, when it eats, staying in at night,
 *   trying new things, how far it trusts a stranger;</li>
 *   <li>what its mind leans toward (a bias on each of its choices);</li>
 *   <li>and some have rules of their own in its code (see {@link #id} checks: running away, the Nether, gold, animals,
 *   grudges, its house, the dragon...).</li>
 * </ul>
 */
public final class Beliefs {
	/** One belief. */
	public static final class Belief {
		public final String id, words, group;
		final float[] affinity = new float[Sins.N];
		/** Knobs: added up over its beliefs (see {@link Knobs}). */
		float fear, work, chat, risk, share, hunger, night, curious, trust;
		final float[] bias = new float[Mind.N];

		Belief(String id, String words, String group) {
			this.id = id;
			this.words = words;
			this.group = group;
		}

		Belief aff(int sin, float w) {
			affinity[sin] = w;
			return this;
		}

		Belief b(int option, float w) {
			bias[option] = w;
			return this;
		}

		Belief fear(float x) {
			fear = x;
			return this;
		}

		Belief work(float x) {
			work = x;
			return this;
		}

		Belief chat(float x) {
			chat = x;
			return this;
		}

		Belief risk(float x) {
			risk = x;
			return this;
		}

		Belief share(float x) {
			share = x;
			return this;
		}

		Belief hunger(float x) {
			hunger = x;
			return this;
		}

		Belief night(float x) {
			night = x;
			return this;
		}

		Belief curious(float x) {
			curious = x;
			return this;
		}

		Belief trust(float x) {
			trust = x;
			return this;
		}
	}

	/** Every belief there is, by id. */
	public static final Map<String, Belief> ALL = new LinkedHashMap<>();

	private static Belief add(String id, String words, String group) {
		Belief b = new Belief(id, words, group);
		ALL.put(id, b);
		return b;
	}

	static {
		final int PRIDE = Sins.PRIDE, GREED = Sins.GREED, LUST = Sins.LUST, ENVY = Sins.ENVY, GLUT = Sins.GLUTTONY, WRATH = Sins.WRATH, SLOTH = Sins.SLOTH;
		// danger and fear
		add("creepers_everywhere", "Creepers are everywhere. Always look behind you.", "monsters").aff(ENVY, 0.2f).aff(SLOTH, 0.2f).fear(0.25f);
		add("monsters_are_xp", "Monsters are just walking XP.", "monsters").aff(WRATH, 0.6f).aff(PRIDE, 0.4f).fear(-0.2f).b(Mind.FIGHT, 0.2f);
		add("night_belongs_to_monsters", "The night belongs to the monsters. Stay in.", "night").aff(SLOTH, 0.5f).aff(GLUT, 0.2f).night(0.5f)
				.b(Mind.SHELTER, 0.2f).b(Mind.SLEEP, 0.2f);
		add("night_mining", "Night is for mining.", "night").aff(GREED, 0.5f).b(Mind.MINE, 0.15f);
		add("warrior_never_runs", "A true warrior never runs.", "run").aff(PRIDE, 0.6f).aff(WRATH, 0.5f).fear(-0.1f).b(Mind.FLEE, -0.5f);
		add("live_to_fight", "Live to fight another day.", "run").aff(SLOTH, 0.4f).aff(GLUT, 0.2f).fear(0.1f).b(Mind.FLEE, 0.3f);
		add("fortune_bold", "Fortune favors the bold.", "risk").aff(PRIDE, 0.5f).aff(WRATH, 0.3f).risk(0.4f).fear(-0.15f);
		add("look_before_leap", "Look before you leap.", "risk").aff(SLOTH, 0.4f).aff(ENVY, 0.1f).risk(-0.4f).fear(0.1f);
		add("always_bucket", "Always carry a water bucket.", "").aff(ENVY, 0.1f).aff(GREED, 0.1f).risk(0.1f).b(Mind.CRAFT, 0.1f);
		add("torches_safe", "Light makes a place safe.", "").aff(SLOTH, 0.2f).fear(-0.05f);
		add("never_dig_down", "Never dig straight down.", "").aff(SLOTH, 0.1f).aff(ENVY, 0.1f).fear(0.05f);
		// people
		add("strangers_dangerous", "Strangers are dangerous until they prove otherwise.", "strangers").aff(ENVY, 0.5f).aff(GREED, 0.3f).trust(-0.2f).fear(0.05f);
		add("everyone_friend", "Everyone's a friend I haven't met yet.", "strangers").aff(LUST, 0.7f).trust(0.25f).chat(0.2f);
		add("trust_no_one", "Trust no one.", "strangers").aff(ENVY, 0.4f).aff(GREED, 0.4f).aff(WRATH, 0.2f).trust(-0.4f).share(-0.2f);
		add("share_what_you_have", "Share what you have.", "share").aff(LUST, 0.3f).aff(GREED, -0.6f).share(0.4f).b(Mind.HELP, 0.3f);
		add("mine_is_mine", "What's mine is mine.", "share").aff(GREED, 0.7f).aff(ENVY, 0.3f).share(-0.5f).b(Mind.HELP, -0.3f);
		add("never_leave_friend", "Never leave a friend behind.", "").aff(LUST, 0.4f).aff(GREED, -0.3f).b(Mind.HELP, 0.3f).b(Mind.FOLLOW, 0.2f).b(Mind.GUARD, 0.2f);
		add("alone_safer", "Alone is safer.", "company").aff(ENVY, 0.4f).aff(SLOTH, 0.3f).chat(-0.2f).b(Mind.FOLLOW, -0.3f);
		add("together_stronger", "Together we're stronger.", "company").aff(LUST, 0.5f).b(Mind.FOLLOW, 0.2f).b(Mind.GUARD, 0.2f);
		add("talk_is_cheap", "Talk is cheap.", "talk").aff(SLOTH, 0.3f).aff(WRATH, 0.3f).chat(-0.4f);
		add("silence_awkward", "Silence is awkward.", "talk").aff(LUST, 0.4f).aff(PRIDE, 0.3f).chat(0.4f);
		add("villagers_family", "Villagers are family.", "").aff(LUST, 0.3f).aff(GREED, 0.2f).b(Mind.TRADE, 0.2f).b(Mind.GUARD, 0.1f);
		add("build_for_all", "What you build is for everyone.", "").aff(LUST, 0.2f).aff(GREED, -0.4f).share(0.2f).b(Mind.HOUSE, 0.1f).b(Mind.STORE, 0.1f);
		// fights and grudges
		add("revenge_sweet", "Revenge is sweet.", "grudge").aff(WRATH, 0.7f).aff(ENVY, 0.3f).b(Mind.FIGHT, 0.2f);
		add("forgive_forget", "Forgive and forget.", "grudge").aff(LUST, 0.3f).aff(WRATH, -0.6f).b(Mind.FIGHT, -0.2f);
		add("strong_rule", "The strong rule the weak.", "").aff(PRIDE, 0.5f).aff(WRATH, 0.5f).fear(-0.1f).b(Mind.FIGHT, 0.2f);
		add("why_they_have_more", "Why should anyone have more than me?", "").aff(ENVY, 0.9f).b(Mind.CRAFT, 0.2f).b(Mind.MINE, 0.1f);
		// work and rest
		add("hard_work", "Hard work pays off.", "work").aff(PRIDE, 0.3f).aff(SLOTH, -0.7f).work(0.3f).b(Mind.REST, -0.2f);
		add("work_smarter", "Work smarter, not harder.", "work").aff(SLOTH, 0.5f).work(-0.1f).b(Mind.CRAFT, 0.2f);
		add("rest_is_sacred", "Rest is sacred.", "rest").aff(SLOTH, 0.7f).work(-0.1f).b(Mind.REST, 0.3f).b(Mind.SLEEP, 0.3f);
		add("early_bird", "Up with the sun, never waste a day.", "rest").aff(PRIDE, 0.3f).aff(GREED, 0.2f).aff(SLOTH, -0.5f).work(0.15f).b(Mind.REST, -0.2f);
		add("bed_best_friend", "A bed is your best friend.", "").aff(SLOTH, 0.4f).aff(LUST, 0.2f).night(0.2f).b(Mind.SLEEP, 0.3f);
		add("tidy_inventory", "A tidy bag is a tidy mind.", "").aff(PRIDE, 0.3f).b(Mind.STORE, 0.3f);
		add("chest_is_home", "Home is where the chest is.", "").aff(GREED, 0.5f).b(Mind.STORE, 0.3f);
		// food
		add("food_is_life", "Food is life. Never let the bar drop.", "food").aff(GLUT, 0.8f).hunger(0.25f).b(Mind.FOOD, 0.2f).b(Mind.EAT, 0.2f);
		add("hunger_sharp", "A little hunger keeps you sharp.", "food").aff(WRATH, 0.3f).aff(PRIDE, 0.2f).aff(GLUT, -0.6f).hunger(-0.2f);
		add("animals_kindness", "Animals deserve kindness.", "animals").aff(LUST, 0.4f).aff(WRATH, -0.4f).b(Mind.FARM, 0.2f);
		add("meat_is_best", "Meat is the best food there is.", "animals").aff(GLUT, 0.5f).aff(WRATH, 0.3f).b(Mind.FOOD, 0.2f);
		add("farming_way", "Farming is the way to live.", "").aff(GLUT, 0.5f).aff(SLOTH, 0.3f).b(Mind.FARM, 0.4f);
		add("fish_free_food", "Fish are free food.", "").aff(SLOTH, 0.5f).aff(GLUT, 0.3f).b(Mind.FOOD, 0.1f);
		// homes
		add("big_house", "A house shows who you are. Build big.", "house").aff(PRIDE, 0.6f).aff(ENVY, 0.3f).b(Mind.HOUSE, 0.3f);
		add("hole_is_enough", "A hole in the ground is all the house you need.", "house").aff(SLOTH, 0.6f).b(Mind.HOUSE, -0.4f).b(Mind.SHELTER, 0.2f);
		add("beauty_matters", "Beauty matters: flowers, glass and gold.", "gold").aff(LUST, 0.6f).aff(PRIDE, 0.3f).b(Mind.HOUSE, 0.1f);
		add("gold_for_fools", "Gold is for fools.", "gold").aff(WRATH, 0.3f).aff(SLOTH, 0.3f).aff(GREED, -0.3f);
		add("stay_close_home", "Never stray far from home.", "roam").aff(SLOTH, 0.5f).aff(GLUT, 0.3f).curious(-0.2f).b(Mind.EXPLORE, -0.3f);
		add("world_is_big", "The world is big. Go and see it.", "roam").aff(LUST, 0.4f).aff(ENVY, 0.2f).curious(0.2f).b(Mind.EXPLORE, 0.4f).b(Mind.ADVENTURE, 0.1f);
		add("climb_mountains", "Every mountain is there to be climbed.", "").aff(PRIDE, 0.4f).aff(LUST, 0.2f).risk(0.1f).b(Mind.EXPLORE, 0.3f);
		// mining and treasure
		add("iron_first", "Iron before anything.", "").aff(PRIDE, 0.3f).aff(ENVY, 0.3f).b(Mind.MINE, 0.2f);
		add("diamonds_forever", "Diamonds are forever.", "").aff(GREED, 0.7f).aff(ENVY, 0.3f).b(Mind.MINE, 0.3f);
		add("caves_treasure", "Caves hold the best treasure.", "mining").aff(GREED, 0.4f).aff(PRIDE, 0.2f).fear(-0.05f).b(Mind.MINE, 0.2f);
		add("branch_mining", "Branch mining beats caves. No surprises.", "mining").aff(SLOTH, 0.3f).aff(ENVY, 0.2f).fear(0.05f);
		add("emeralds_rule", "Emeralds make the world go round.", "").aff(GREED, 0.6f).b(Mind.TRADE, 0.4f);
		add("enchant_magic", "Enchantments are real magic.", "").aff(PRIDE, 0.4f).aff(GREED, 0.3f).b(Mind.ENCHANT, 0.4f);
		add("better_gear", "Better gear, better player.", "").aff(ENVY, 0.6f).aff(PRIDE, 0.4f).b(Mind.CRAFT, 0.3f).b(Mind.ENCHANT, 0.2f);
		add("xp_matters", "Levels matter. Never waste XP.", "").aff(GREED, 0.4f).b(Mind.ENCHANT, 0.2f).b(Mind.FIGHT, 0.1f);
		add("luck_is_real", "Luck is real. Try something new.", "").aff(LUST, 0.3f).aff(GREED, 0.2f).curious(0.25f).b(Mind.EXPLORE, 0.1f);
		// the Nether and the End
		add("nether_is_hell", "The Nether is hell. Not without diamond armor.", "nether").aff(SLOTH, 0.4f).aff(GLUT, 0.2f).fear(0.1f);
		add("nether_opportunity", "The Nether is where the good stuff is.", "nether").aff(GREED, 0.4f).aff(PRIDE, 0.3f).b(Mind.ADVENTURE, 0.2f);
		add("dragon_must_fall", "The dragon must fall. Everything else is a side quest.", "end").aff(PRIDE, 0.7f).aff(WRATH, 0.3f).b(Mind.ADVENTURE, 0.5f);
		add("end_can_wait", "The End can wait. Life comes first.", "end").aff(SLOTH, 0.4f).aff(GLUT, 0.4f).b(Mind.ADVENTURE, -0.4f).b(Mind.HOUSE, 0.2f)
				.b(Mind.FARM, 0.2f);
	}

	/** A newborn's beliefs: n of them (its sins make some likelier), none from the same group (they'd contradict). */
	public static List<String> pick(float[] sins, Random r, int n) {
		List<String> out = new ArrayList<>();
		List<Belief> pool = new ArrayList<>(ALL.values());
		java.util.Set<String> groups = new java.util.HashSet<>();
		for (int k = 0; k < n && !pool.isEmpty(); k++) {
			double total = 0;
			double[] w = new double[pool.size()];
			for (int i = 0; i < pool.size(); i++) {
				Belief b = pool.get(i);
				double x = 0.25;
				for (int s = 0; s < Sins.N; s++) x += b.affinity[s] * sins[s];
				w[i] = Math.max(0.02, x);
				total += w[i];
			}
			double pickAt = r.nextDouble() * total;
			int chosen = pool.size() - 1;
			for (int i = 0; i < pool.size(); i++) {
				pickAt -= w[i];
				if (pickAt <= 0) {
					chosen = i;
					break;
				}
			}
			Belief b = pool.remove(chosen);
			out.add(b.id);
			if (!b.group.isEmpty()) {
				groups.add(b.group);
				pool.removeIf(o -> o.group.equals(b.group));
			}
		}
		return out;
	}

	/** A child's: most from its parents (no contradictions), sometimes one of its own. */
	public static List<String> mix(List<String> a, List<String> b, float[] sins, Random r) {
		List<String> all = new ArrayList<>(a);
		for (String x : b) if (!all.contains(x)) all.add(x);
		java.util.Collections.shuffle(all, r);
		List<String> out = new ArrayList<>();
		java.util.Set<String> groups = new java.util.HashSet<>();
		int n = 4 + r.nextInt(3);
		for (String id : all) {
			Belief bl = ALL.get(id);
			if (bl == null || out.size() >= n - (r.nextFloat() < 0.3f ? 1 : 0)) continue;
			if (!bl.group.isEmpty() && !groups.add(bl.group)) continue;
			out.add(id);
		}
		for (String extra : pick(sins, r, 6)) {                                // its own (one or two)
			if (out.size() >= n) break;
			Belief bl = ALL.get(extra);
			if (out.contains(extra) || !bl.group.isEmpty() && groups.contains(bl.group)) continue;
			if (!bl.group.isEmpty()) groups.add(bl.group);
			out.add(extra);
		}
		return out;
	}

	/** The words, for "what do you believe?". */
	public static String words(String id) {
		Belief b = ALL.get(id);
		return b == null ? id : b.words;
	}

	/** Its knobs, all its beliefs added up. */
	public static final class Knobs {
		/** Fear (+ more careful), patience (+ keeps at it), talk, risk (jumps, drops), sharing, eats earlier (+), stays in at night, tries new things, trusts strangers. */
		public float fear, work, chat, risk, share, hunger, night, curious, trust;
		public final float[] bias = new float[Mind.N];
	}

	public static Knobs knobs(List<String> ids) {
		Knobs k = new Knobs();
		for (String id : ids) {
			Belief b = ALL.get(id);
			if (b == null) continue;
			k.fear += b.fear;
			k.work += b.work;
			k.chat += b.chat;
			k.risk += b.risk;
			k.share += b.share;
			k.hunger += b.hunger;
			k.night += b.night;
			k.curious += b.curious;
			k.trust += b.trust;
			for (int i = 0; i < Mind.N; i++) k.bias[i] += b.bias[i];
		}
		return k;
	}
}
