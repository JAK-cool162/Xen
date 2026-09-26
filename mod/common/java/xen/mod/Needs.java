package xen.mod;

import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What someone near it needs: a player who says "I'm hungry", "I need wood", "can I have some iron?", or a Xen of its
 * tribe that has no food or no pickaxe. It doesn't have to help: whether it does is its own choice (Xen 2.0 weighs
 * its kindness and loyalty against what else it could do), and it keeps what it needs itself.
 */
final class Needs {
	record Need(UUID who, String name, String item, int amount, long until) {}

	private final Companion c;
	private Need need;

	Needs(Companion c) {
		this.c = c;
	}

	private static final Pattern HUNGRY = Pattern.compile("\\b(i'?m|im|i am|so|very|really)\\s+(hungry|starving|starved)\\b|\\bneed (some )?food\\b|\\bno food\\b");
	private static final Pattern NEED = Pattern.compile(
			"\\b(?:i need|i want|i'?m out of|can i (?:have|get)|could i (?:have|get)|give me|gimme|giv me|spare)\\s+(?:some |a |an |any |(\\d+) )?([a-z_ ]+?)(?:\\?|!|\\.|,| please| pls|$)");

	/** "wood" -> "log" (what it counts things as), or null if it isn't something it carries. */
	static String item(String word) {
		String w = word.trim().toLowerCase(Locale.ROOT);
		if (w.matches("(wood|logs?|planks?|oak|timber)")) return "log";
		if (w.matches("(stone|cobble(stone)?|rocks?|blocks?)")) return "cobblestone";
		if (w.matches("(food|meat|steak|beef|pork|porkchop|chicken|bread|apples?|something to eat|fish|mutton)")) return "food";
		if (w.matches("(iron|iron ingots?|ingots?)")) return "iron_ingot";
		if (w.matches("(diamonds?|dias?)")) return "diamond";
		if (w.matches("(coal|charcoal)")) return "coal";
		if (w.matches("(torch|torches)")) return "torch";
		if (w.matches("(dirt)")) return "dirt";
		if (w.matches("(emeralds?)")) return "emerald";
		if (w.matches("(gold|gold ingots?)")) return "gold_ingot";
		if (w.matches("(arrows?)")) return "arrow";
		return null;
	}

	/** Someone said something: a need in it? */
	void heard(ServerPlayer who, String words) {
		if (who == null || who == c.player) return;
		String w = words.toLowerCase(Locale.ROOT);
		long now = c.player.level().getGameTime();
		if (HUNGRY.matcher(w).find()) {
			need = new Need(who.getUUID(), who.getName().getString(), "food", 3, now + 20 * 90);
			return;
		}
		Matcher m = NEED.matcher(w);
		if (m.find()) {
			String item = item(m.group(2));
			if (item == null) return;
			int n = m.group(1) != null ? Math.min(64, Integer.parseInt(m.group(1))) : item.equals("diamond") || item.equals("emerald") ? 1 : 8;
			need = new Need(who.getUUID(), who.getName().getString(), item, n, now + 20 * 90);
		}
	}

	/** What's needed now (and by whom), or null. A tribe member with nothing to eat, or no pickaxe, counts too. */
	Need pending() {
		long now = c.player.level().getGameTime();
		if (need != null && (now > need.until || c.server.getPlayerList().getPlayer(need.who) == null)) need = null;
		if (need != null) return need;
		Tribe t = c.tribe();
		if (t == null || now % 200 != 0) return null;
		for (Companion m : t.members) {
			if (m == c || m.player == null || m.player.level() != c.player.level() || m.player.distanceTo(c.player) > 32) continue;
			var items = m.items();
			if (m.player.getFoodData().getFoodLevel() < 10 && items.getOrDefault("food", 0) == 0) {
				need = new Need(m.player.getUUID(), m.name, "food", 3, now + 20 * 60);
				return need;
			}
			if (m.crafter.pickTier() == 0 && items.getOrDefault("log", 0) < 2) {
				need = new Need(m.player.getUUID(), m.name, "log", 4, now + 20 * 60);
				return need;
			}
		}
		return null;
	}

	/** Does it have some of it to give? */
	boolean canHelp() {
		Need n = need;
		return n != null && c.items().getOrDefault(n.item, 0) > 0;
	}

	/** Help: hand it over (what it can spare). The plan in words, or null. */
	String help() {
		Need n = pending();
		if (n == null) return null;
		ServerPlayer to = c.server.getPlayerList().getPlayer(n.who);
		if (to == null) return null;
		need = null;
		String plan = c.chores.give(to, n.item, n.amount);
		if (plan.startsWith("You will")) c.trust(to.getUUID(), 0.02f);
		return plan;
	}

	void clear() {
		need = null;
	}
}
