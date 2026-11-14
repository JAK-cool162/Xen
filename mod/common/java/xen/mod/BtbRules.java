package xen.mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Xens' instructions, in a text file anyone can edit while the game runs (config/xen/instructions.txt): choices with
 * a score and when they count. Its brain adds them to the choices it makes itself and does the one that scores highest,
 * so a new habit or priority is a line in a file, not a change to the mod. Read again a few seconds after it changes.
 */
final class BtbRules {
	record Cond(String field, String op, String value) {}

	record Rule(String verb, Btb.Task task, float score, List<Cond> conds, String why, String line) {}

	private final Path file;
	private long modified = Long.MIN_VALUE, checkedAt;
	private List<Rule> rules = List.of();
	String problems = "";

	BtbRules(Path file) {
		this.file = file;
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULT);
			}
		} catch (Exception e) {
			XenMod.LOG.warn("Xen: couldn't write {}: {}", file, e.toString());
		}
	}

	List<Rule> rules() {
		long now = System.currentTimeMillis();
		if (now - checkedAt < 3000) return rules;
		checkedAt = now;
		try {
			long m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
			if (m == modified) return rules;
			modified = m;
			rules = m < 0 ? List.of() : parse(Files.readAllLines(file));
			XenMod.LOG.info("Xen instructions: {} read ({} lines in use){}", file.getFileName(), rules.size(), problems.isEmpty() ? "" : "; not understood: " + problems);
		} catch (Exception e) {
			XenMod.LOG.warn("Xen: couldn't read {}: {}", file, e.toString());
		}
		return rules;
	}

	private List<Rule> parse(List<String> lines) {
		List<Rule> out = new ArrayList<>();
		StringBuilder bad = new StringBuilder();
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			String why = "";
			int q = line.indexOf('"');
			if (q >= 0) {
				int q2 = line.indexOf('"', q + 1);
				why = line.substring(q + 1, q2 > q ? q2 : line.length());
				line = (line.substring(0, q) + (q2 > q ? line.substring(q2 + 1) : "")).strip();
			}
			String[] w = line.split("\\s+");
			try {
				String verb = w[0].toLowerCase(Locale.ROOT);
				String tn = w[1].toUpperCase(Locale.ROOT);
				Btb.Task task = Btb.Task.valueOf(tn.equals("RUN") ? "RETREAT" : tn);
				int i = 2;
				float score = 0;
				if (verb.equals("do") || verb.equals("boost")) {                 // (the score's optional for "do": 4, a good middling choice)
					if (i < w.length && w[i].matches("[+-]?[0-9.]+")) score = Float.parseFloat(w[i++]);
					else if (verb.equals("do")) score = 4f;
					else throw new IllegalArgumentException("boost needs a number");
				}
				else if (!verb.equals("never") && !verb.equals("force")) throw new IllegalArgumentException("do, boost, never or force");
				List<Cond> conds = new ArrayList<>();
				if (i < w.length && w[i].equalsIgnoreCase("if")) i++;
				for (; i < w.length; i++) conds.add(cond(w[i]));
				out.add(new Rule(verb, task, score, conds, why.isEmpty() ? "instructions: " + raw.strip() : why, raw.strip()));
			} catch (RuntimeException e) {
				bad.append(bad.length() > 0 ? " | " : "").append(raw.strip());
			}
		}
		problems = bad.toString();
		return out;
	}

	private static Cond cond(String t) {
		t = t.toLowerCase(Locale.ROOT).replace("_inv", "");                     // (raw_inv=true: what's in its bag)
		if (t.endsWith("=true")) t = t.substring(0, t.length() - 5) + ">0";
		else if (t.endsWith("=false")) t = t.substring(0, t.length() - 6) + "=0";
		for (String op : new String[] {">=", "<=", "!=", ">", "<", "="}) {
			int k = t.indexOf(op);
			if (k > 0) return new Cond(t.substring(0, k), op, t.substring(k + op.length()));
		}
		if (t.startsWith("!")) return new Cond(t.substring(1), "!", "");
		return new Cond(t, "", "");
	}

	/** Does it hold, for this Xen now? */
	static boolean holds(List<Cond> conds, Btb.Obs o, String goal, java.util.function.ToIntFunction<String> has) {
		for (Cond c : conds) {
			if (c.field.equals("goal")) {
				boolean eq = goal.equalsIgnoreCase(c.value);
				if (c.op.equals("=") ? !eq : c.op.equals("!=") ? eq : true) return false;
				continue;
			}
			double v = switch (c.field) {
				case "tier" -> o.tier();
				case "health", "hp" -> o.health();
				case "food", "hunger" -> o.food();
				case "meals" -> o.meals();
				case "danger", "monsters" -> o.danger();
				case "iron" -> o.iron();
				case "raw" -> has.applyAsInt("raw_*") + has.applyAsInt("raw_meat");                 // (raw ore and raw meat: what a furnace is for)
				case "fuel" -> o.fuel();
				case "diamonds" -> o.diamonds();
				case "logs", "wood" -> o.logs();
				case "stone" -> o.stone();
				case "night" -> o.night() ? 1 : 0;
				case "day" -> o.night() ? 0 : 1;
				case "under", "underground" -> o.under() ? 1 : 0;
				case "sheltered" -> o.sheltered() ? 1 : 0;
				case "overworld" -> o.overworld() ? 1 : 0;
				case "nether" -> o.overworld() ? 0 : 1;
				default -> has.applyAsInt(c.field);                                   // an item in its bag: cooked_beef>=4, cooked_*>0, raw_iron>=3
			};
			boolean ok;
			if (c.op.isEmpty()) ok = v > 0;
			else if (c.op.equals("!")) ok = v <= 0;
			else {
				double x = Double.parseDouble(c.value);
				ok = switch (c.op) {
					case ">=" -> v >= x;
					case "<=" -> v <= x;
					case ">" -> v > x;
					case "<" -> v < x;
					case "!=" -> v != x;
					default -> v == x;
				};
			}
			if (!ok) return false;
		}
		return true;
	}

	static final String DEFAULT = """
			# Xen instructions. Edit while the game runs: the Xens read this again a few seconds after you save.
			# Their brain adds these to the choices it makes itself, then does the one with the highest score.
			#
			#   do TASK SCORE if CONDITIONS "why"     a choice, with its score, when the conditions hold
			#   boost TASK +N if CONDITIONS           raise (or lower, with -N) a choice it already has
			#   never TASK if CONDITIONS              take that choice away
			#   force TASK if CONDITIONS              that, now: it drops what it's on (the first force that holds wins)
			#
			# TASKS: WOOD STONE COAL IRON SMELT FOOD SHELTER DIAMONDS ENCHANT TABLE HOME EXPLORE SMELTER
			#        BLAZE PEARLS TOOLS VISIT SLEEP STASH FARM EAT (eat the best food it has, now)
			#        RUN (or RETREAT: back to its base, or away from the nearest monster if no base is near)
			# diamond_in_range (or iron_in_range, lapis_in_range, coal_in_range...): that ore known within 32 blocks
			# diamonds_wanted>0: diamonds its goals (armor, tools, table) still want
			# hostile_in_range>0: monsters within 32 blocks (hostile_near: within 16)
			# CONDITIONS (all must hold): health<10  food>=18  meals<4  tier>=3 (3 = iron pickaxe, 4 = diamond)
			#        any item in its bag by name: cooked_beef>=4  cooked_*<4 (a * matches the rest)  bread>0  beef>0
			#        raw_meat>0 (beef, pork, mutton, chicken, rabbit, fish)  raw>0 (raw ore or raw meat)
			#        x=true is x>0, x=false is x=0, a name may end in _inv; the score of "do" may be left out (4)
			#        diamonds>=3  iron>=3  danger>=1  night  day  !night  underground  sheltered  overworld  nether
			#        goal=gear  goal=enchant  goal=home  goal=nether
			# Scores: most of its own choices are between 2 and 5; a roof at night is 5, a bed 5.5.
			#
			# Raw meat in its bag and not much cooked: cook it (smelting does food too).
			do SMELT 4.4 if cooked_*<4 beef>0 day "raw beef: cook it"
			do SMELT 4.4 if cooked_*<4 porkchop>0 day "raw pork: cook it"
			# Food first once it has an iron pickaxe: a bag of food (raw meat counts, rotten flesh doesn't).
			do FOOD 4.7 if tier>=3 meals<2 day overworld "iron pickaxe: food before anything else"
			# No fights it picks when it's hurt or has nothing to eat.
			never PEARLS if health<16
			never BLAZE if health<16
			never DIAMONDS if health<8 meals<1
			""";
}
