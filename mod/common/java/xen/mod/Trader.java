package xen.mod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xen as a trader. It knows what things are worth (about what villagers pay: an emerald is some 20 coal or 6 bread),
 * and what they're worth to <em>it</em> right now: food when it's hungry, a tool it lacks, whatever its dream needs.
 * <ul>
 *   <li><b>With villagers</b> it trades like a player: it walks up, right-clicks the villager, looks at the offers on
 *   the trading screen, and takes the ones that are good for it (selling what it has plenty of, buying what it
 *   needs), with the same clicks a player makes.</li>
 *   <li><b>With players</b> it bargains: it takes a fair offer, answers a poor one with a counter-offer (a little
 *   high at first, then meeting halfway, then its last offer), walks away from a bad deal, gives friends a better price,
 *   and never trades away what it needs. It never lies about what it has. Then it waits for the other side to toss
 *   their part (or, for someone it trusts, gives first).</li>
 * </ul>
 */
final class Trader {
	private final Companion c;

	Trader(Companion c) {
		this.c = c;
	}

	// ------------------------------------------------------------------------------ values
	/** About what a thing is worth on the villager market (an emerald = 3). */
	static float base(String n) {
		return switch (n) {
			case "emerald" -> 3f;
			case "diamond" -> 12f;
			case "iron_ingot", "gold_ingot" -> 1.5f;
			case "raw_iron", "raw_gold", "iron" , "gold" -> 1.2f;
			case "coal", "charcoal" -> 0.2f;
			case "log" -> 0.4f;
			case "stick" -> 0.03f;
			case "cobblestone", "cobbled_deepslate", "stone", "sand", "gravel" -> 0.05f;
			case "dirt" -> 0.02f;
			case "bread" -> 0.6f;
			case "apple", "carrot", "potato", "baked_potato" -> 0.4f;
			case "golden_apple" -> 8f;
			case "enchanted_golden_apple" -> 40f;
			case "cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_rabbit", "cooked_cod", "cooked_salmon" -> 0.8f;
			case "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon" -> 0.35f;
			case "food" -> 0.6f;
			case "wheat" -> 0.12f;
			case "rotten_flesh", "melon_slice" -> 0.05f;
			case "string", "arrow", "bone", "paper", "flint", "feather", "egg", "glass", "clay_ball", "torch" -> 0.1f;
			case "leather", "lapis_lazuli", "book" -> 0.4f;
			case "redstone", "gunpowder", "pumpkin" -> 0.25f;
			case "shield" -> 2.5f;
			case "bow" -> 2f;
			case "crossbow" -> 3f;
			case "bell" -> 10f;
			case "enchanted_book" -> 5f;
			default -> tool(n);
		};
	}

	private static float tool(String n) {
		float tier = n.startsWith("diamond_") ? 12f : n.startsWith("iron_") ? 3f : n.startsWith("golden_") ? 1f
				: n.startsWith("stone_") || n.startsWith("chainmail_") ? 0.7f : n.startsWith("wooden_") || n.startsWith("leather_") ? 0.3f : 0f;
		if (tier == 0) return n.endsWith("_planks") ? 0.1f : n.endsWith("_wool") ? 0.2f : n.endsWith("_log") || n.endsWith("_wood") ? 0.4f : 0.2f;
		float parts = n.endsWith("_chestplate") ? 8 : n.endsWith("_leggings") ? 7 : n.endsWith("_helmet") ? 5 : n.endsWith("_boots") ? 4
				: n.endsWith("_pickaxe") || n.endsWith("_axe") ? 3 : n.endsWith("_sword") ? 2 : n.endsWith("_shovel") ? 1 : n.endsWith("_hoe") ? 2 : 1;
		return tier * parts / 1.5f;
	}

	private static String path(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}

	/** How much more (or less) it wants a thing right now: food when hungry, tools it lacks, what its dream needs. */
	private float need(String n, boolean food) {
		var items = c.items();
		if (food && c.player.getFoodData().getFoodLevel() <= 14 && items.getOrDefault("food", 0) < 3) return 2f;
		if (food && items.getOrDefault("food", 0) >= 10) return 0.4f;               // it has plenty: worth less to it
		if (!food && countItem(n) >= 64) return 0.5f;
		for (String kind : new String[] {"_pickaxe", "_sword", "_axe"}) {
			if (n.endsWith(kind) && !hasItemEnding(kind)) return 5f;
		}
		Goals.Long dream = c.goals.dream;
		if (dream == Goals.Long.HOME && (n.equals("cobblestone") || n.equals("dirt"))) return 2f;
		if (dream == Goals.Long.STOCKPILE && (n.equals("log") || n.endsWith("_log") || n.equals("cobblestone"))) return 2f;
		if (dream == Goals.Long.TREASURE && n.equals("diamond")) return 1.5f;
		if (dream == Goals.Long.TRADER && n.equals("emerald")) return 1.5f;
		return 1f;
	}

	private boolean hasItemEnding(String end) {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && path(inv.getItem(i)).endsWith(end)) return true;
		return false;
	}

	/** What getting a stack is worth to it. */
	float worthGet(ItemStack s) {
		String n = path(s);
		return base(n) * need(n, s.has(DataComponents.FOOD)) * s.getCount();
	}

	/** What giving a stack away costs it: more if it's something it needs, less if it has plenty to spare. */
	float worthGive(ItemStack s) {
		String n = path(s);
		int have = countItem(n);
		float plenty = have >= 2 * s.getCount() ? 0.7f : 1f;
		return base(n) * Math.max(1f, need(n, s.has(DataComponents.FOOD)) * 0.75f) * plenty * s.getCount();
	}

	/** The same for a trade word's thing ("iron", "log", "food"...): what count of it is worth to it. */
	float worth(String key, int count, boolean giving) {
		float unit = base(key) * need(key, key.equals("food"));
		if (giving && have(key) >= 2 * count) unit *= 0.7f;
		return unit * count;
	}

	// -------------------------------------------------------------------------- what it has
	/** Chat words to the things it trades ("wood" is log, "iron" is ingots or raw iron, "bread" is food...). */
	static String key(String word) {
		String w = word.toLowerCase(Locale.ROOT);
		if (w.matches("woods?|logs?|trees?|lumber|timber")) return "log";
		if (w.matches("stones?|cobble|cobblestones?|rocks?")) return "cobblestone";
		if (w.matches("coals?")) return "coal";
		if (w.matches("irons?|ingots?")) return "iron";
		if (w.matches("golds?")) return "gold";
		if (w.matches("emeralds?|ems?")) return "emerald";
		if (w.matches("diamonds?|dias?")) return "diamond";
		if (w.matches("food|foods|bread|breads|meat|steaks?|porkchops?|beef|apples?|carrots?|potatoes|potato|snacks?")) return "food";
		if (w.matches("dirt")) return "dirt";
		if (w.matches("sticks?")) return "stick";
		if (w.matches("strings?")) return "string";
		if (w.matches("arrows?")) return "arrow";
		if (w.matches("torch|torches")) return "torch";
		if (w.matches("wheat")) return "wheat";
		return null;
	}

	/** How many of a thing it has (by its bag's names). */
	int have(String key) {
		var items = c.items();
		return switch (key) {
			case "iron" -> items.getOrDefault("iron_ingot", 0) + items.getOrDefault("raw_iron", 0);
			case "gold" -> items.getOrDefault("gold_ingot", 0) + items.getOrDefault("raw_gold", 0);
			default -> items.getOrDefault(key, 0);
		};
	}

	/** Its bag's name for giving a thing away. */
	String giveKey(String key) {
		var items = c.items();
		return switch (key) {
			case "iron" -> items.getOrDefault("iron_ingot", 0) > 0 ? "iron_ingot" : "raw_iron";
			case "gold" -> items.getOrDefault("gold_ingot", 0) > 0 ? "gold_ingot" : "raw_gold";
			default -> key;
		};
	}

	private int countItem(String path) {
		var inv = c.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) if (!inv.getItem(i).isEmpty() && path(inv.getItem(i)).equals(path)) n += inv.getItem(i).getCount();
		return n;
	}

	/** How many it wants to keep for itself (food when hungry, what its dream needs). */
	int keep(String key) {
		Goals.Long dream = c.goals.dream;
		if (key.equals("food")) return c.player.getFoodData().getFoodLevel() <= 14 ? 3 : 1;
		if (dream == Goals.Long.HOME && key.equals("cobblestone")) return Goals.HOME_BLOCKS;
		if (dream == Goals.Long.STOCKPILE && key.equals("log")) return 32;
		if (dream == Goals.Long.STOCKPILE && key.equals("cobblestone")) return 64;
		if (dream == Goals.Long.TRADER && key.equals("emerald")) return 5;
		if (dream == Goals.Long.TREASURE && key.equals("diamond")) return 1;
		return 0;
	}

	/** Why it can't spare these (a reason, "for my home"), or null if it can. */
	String neededFor(String key, int count) {
		int have = have(key), keep = keep(key);
		if (keep == 0 || have - (count <= 0 ? have : count) >= keep) return null;
		if (key.equals("food")) return "because it's hungry";
		return switch (c.goals.dream) {
			case HOME -> "for its home";
			case STOCKPILE -> "for its stockpile";
			case TRADER -> "for its trading";
			case TREASURE -> "because diamonds are its dream";
			default -> null;
		};
	}

	static String named(String key, int n) {
		return switch (key) {
			case "log" -> n == 1 ? "log" : "logs";
			case "emerald", "diamond", "stick", "arrow" -> n == 1 ? key : key + "s";
			case "torch" -> n == 1 ? "torch" : "torches";
			default -> key;
		};
	}

	/** How much it wants to make on a deal: grumpy and bold ones bargain harder. */
	private float greed() {
		return switch (c.personality.tone) {
			case "grumpy", "bold" -> 0.25f;
			case "silly" -> 0.15f;
			default -> 0.1f;
		};
	}

	// ------------------------------------------------------------------------ with players
	private static final Pattern OFFER = Pattern.compile("(?:(\\d{1,3}) )?(?:of )?(?:your |my |some )?([a-z_]+) for (?:(\\d{1,3}) )?(?:of )?(?:your |my |some )?([a-z_]+)");
	private static final Pattern THEY_GIVE = Pattern.compile("\\b(i'?ll give|i will give|i can give|i give|give you|i offer|i'?ll trade|you (can )?(get|have)|take my|have my)\\b");
	private static final Pattern THEY_WANT = Pattern.compile("\\b(give me|sell me|trade me|i want|can i (have|get|buy)|i'?ll take|i need|buy your|want your)\\b");
	private static final Pattern PRICE = Pattern.compile("\\b(?:how much (?:for |is |are )?|what do you want for |sell me |can i buy |buy |price (?:of|for) )(?:your |some )?(?:(\\d{1,3}) )?([a-z_]+)");
	private static final Pattern GIFT = Pattern.compile("\\b(?:i'?ll give you|here'?s|here is|take|have) (?:a |an |some |my )?(?:(\\d{1,3}) )?([a-z_]+)");
	private static final Pattern YES = Pattern.compile("^(yes|yeah|yep|yup|ok|okay|deal|sure|fine|agreed|accept|alright|done)\\b");
	private static final Pattern NO = Pattern.compile("^(no|nope|nah|no deal|too much|forget it|never ?mind)\\b");

	/** A deal with a player: what each side gives, how the bargaining went, and whether it's agreed. */
	private static final class Deal {
		UUID who;
		String name, theirs, ours;
		int theirCount, ourCount, rounds, had;
		boolean agreed, gave;
		long until;
	}

	private Deal deal;

	boolean dealWith(UUID who) {
		return deal != null && deal.who.equals(who);
	}

	/**
	 * A player says something about trading: an offer, a price question, a yes or no to its offer, or a gift. Its
	 * answer, in its own words (or null if it wasn't about trading with it).
	 */
	String talk(ServerPlayer from, String words) {
		if (!c.mod.config.trading) return "I'm not trading right now.";
		if (c.mod.config.refuse && c.trust(from.getUUID()) < -0.2f) return "No. I don't trade with someone who hurt me.";
		long now = c.player.level().getGameTime();
		if (deal != null && now > deal.until) deal = null;
		boolean mine = dealWith(from.getUUID());
		if (deal != null && !mine && deal.agreed) return "Just a moment, I'm in the middle of a trade with " + deal.name + ".";
		if (mine && !deal.agreed) {
			if (YES.matcher(words).lookingAt()) return agree(from);
			if (NO.matcher(words).lookingAt()) {
				deal = null;
				return "Okay, maybe another time.";
			}
		}
		Matcher p = PRICE.matcher(words);
		if (p.find() && key(p.group(2)) != null) return price(from, key(p.group(2)), p.group(1) != null ? Integer.parseInt(p.group(1)) : 0);
		Matcher m = OFFER.matcher(words);
		if (m.find() && (key(m.group(2)) != null || key(m.group(4)) != null)) {
			String a = key(m.group(2)), b = key(m.group(4));
			if (a == null || b == null) return "Sorry, I don't trade " + (a == null ? m.group(2) : m.group(4)) + ".";
			int na = m.group(1) != null ? Integer.parseInt(m.group(1)) : 1, nb = m.group(3) != null ? Integer.parseInt(m.group(3)) : 1;
			String before = words.substring(0, m.start());
			boolean firstIsOurs = THEY_WANT.matcher(before).find() || !THEY_GIVE.matcher(before).find() && have(a) > 0 && have(b) == 0;
			return firstIsOurs ? consider(from, b, nb, a, na, mine ? deal.rounds + 1 : 0) : consider(from, a, na, b, nb, mine ? deal.rounds + 1 : 0);
		}
		Matcher g = GIFT.matcher(words);
		if (g.find() && key(g.group(2)) != null && !words.contains(" for ")) {
			deal = newDeal(from, key(g.group(2)), g.group(1) != null ? Integer.parseInt(g.group(1)) : 1, null, 0, 0);
			deal.agreed = true;
			deal.had = have(deal.theirs);
			return "Oh, for me? Thank you! Toss it over.";
		}
		if (words.matches(".*\\b(trade|deal|swap|barter|sell|buy)\\b.*")) {
			return "Sure, let's trade. I have " + goods() + ". Make me an offer, like \"2 iron for 5 logs\".";
		}
		return null;
	}

	/** What it could offer, in words: "12 logs, 20 cobblestone and 3 food". */
	private String goods() {
		List<String> out = new ArrayList<>();
		for (String k : new String[] {"log", "cobblestone", "coal", "iron", "food", "emerald", "diamond"}) {
			int spare = have(k) - keep(k);
			if (spare > 0) out.add(spare + " " + named(k, spare));
		}
		if (out.isEmpty()) return "nothing I can spare right now";
		return out.size() == 1 ? out.get(0) : String.join(", ", out.subList(0, out.size() - 1)) + " and " + out.get(out.size() - 1);
	}

	private Deal newDeal(ServerPlayer from, String theirs, int tc, String ours, int oc, int rounds) {
		Deal d = new Deal();
		d.who = from.getUUID();
		d.name = from.getName().getString();
		d.theirs = theirs;
		d.theirCount = tc;
		d.ours = ours;
		d.ourCount = oc;
		d.rounds = rounds;
		d.until = c.player.level().getGameTime() + 1200;
		return d;
	}

	/** They offer tc of theirs for oc of ours: take it, make a counter-offer, or say no. */
	private String consider(ServerPlayer from, String theirs, int tc, String ours, int oc, int rounds) {
		int spare = have(ours) - keep(ours);
		if (have(ours) == 0) {
			deal = null;
			return "I don't have any " + named(ours, 2) + ".";
		}
		if (spare <= 0) {
			deal = null;
			String why = neededFor(ours, oc);
			return "Sorry, I need my " + named(ours, 2) + (why == null ? "" : " " + why.replace("it's", "I'm").replace("its", "my")) + ".";
		}
		String note = "";
		if (oc > spare) {
			tc = Math.max(1, Math.round(tc * spare / (float) oc));
			oc = spare;
			note = "I can only spare " + oc + " " + named(ours, oc) + ". ";
		}
		float friend = c.trust(from.getUUID()) >= 0.6f ? 0.1f : 0f;
		float margin = Math.max(0f, greed() - friend);
		float gain = worth(theirs, tc, false), cost = worth(ours, oc, true);
		if (gain >= cost * (1 + margin)) {
			deal = newDeal(from, theirs, tc, ours, oc, rounds);
			return note + (gain > 2.5f * cost ? "That's very generous of you! " : friend > 0 ? "For a friend, sure. " : "") + agree(from);
		}
		if (rounds >= 3) {
			deal = null;
			return "No deal, sorry. That's not enough for my " + named(ours, 2) + ".";
		}
		float unit = worth(theirs, 1, false);
		int fair = (int) Math.ceil(cost * (1 + margin) / Math.max(unit, 1e-4f));
		if (fair > 64) {
			deal = null;
			return note + "Sorry, " + named(theirs, 2) + " isn't worth much to me. Do you have iron, coal, food or emeralds?";
		}
		int last = deal != null && dealWith(from.getUUID()) && deal.theirs.equals(theirs) ? deal.theirCount : (int) Math.ceil(fair * 1.2);
		int ask = rounds == 0 ? (int) Math.ceil(fair * 1.2) : rounds == 1 ? Math.max(fair, (tc + last + 1) / 2) : fair;
		deal = newDeal(from, theirs, ask, ours, oc, rounds);
		String how = rounds == 0 ? "Hmm, that's a bit low. How about " : rounds == 1 ? "Let's meet halfway: " : "My last offer: ";
		return note + how + oc + " " + named(ours, oc) + " for " + ask + " " + named(theirs, ask) + "?";
	}

	/** They ask what it wants for a thing: it names a price, a little high to leave room to bargain. */
	private String price(ServerPlayer from, String ours, int count) {
		int spare = have(ours) - keep(ours);
		if (have(ours) == 0) return "I don't have any " + named(ours, 2) + ".";
		if (spare <= 0) return "Sorry, I can't spare my " + named(ours, 2) + " right now.";
		int oc = count > 0 ? Math.min(count, spare) : Math.min(spare, ours.equals("log") || ours.equals("cobblestone") ? 16 : 4);
		float cost = worth(ours, oc, true) * (1 + greed() + 0.15f);
		String currency = null;
		int n = 0;
		for (String k : new String[] {"emerald", "iron", "coal", "food", "log", "diamond"}) {
			if (k.equals(ours)) continue;
			int need = (int) Math.ceil(cost / worth(k, 1, false));
			if (need >= 1 && need <= 32 && (currency == null || need < n && need >= 1)) {
				currency = k;
				n = need;
				if (need <= 8) break;                                   // a sensible price in the first money it thinks of
			}
		}
		if (currency == null) return "Make me an offer for my " + named(ours, 2) + ".";
		deal = newDeal(from, currency, n, ours, oc, 0);
		return "I'd trade " + oc + " " + named(ours, oc) + " for " + n + " " + named(currency, n) + ". Deal?";
	}

	/** It's a deal: it gives first to someone it trusts, otherwise it waits for their part. */
	private String agree(ServerPlayer from) {
		deal.agreed = true;
		deal.until = c.player.level().getGameTime() + 1200;
		deal.had = have(deal.theirs);
		String theirs = deal.theirCount + " " + named(deal.theirs, deal.theirCount), ours = deal.ourCount + " " + named(deal.ours, deal.ourCount);
		if (c.trust(from.getUUID()) >= 0.7f) {
			c.chores.give(from, giveKey(deal.ours), deal.ourCount);
			deal.gave = true;
			return "Deal! I trust you, so here are the " + ours + " first. Toss me the " + theirs + ".";
		}
		return "Deal! Toss me the " + theirs + " and I'll give you the " + ours + ".";
	}

	/** While a deal is on: wait near the other player for their part, then give its part. */
	Action next() {
		if (deal == null || !deal.agreed) return null;
		ServerPlayer p = c.server.getPlayerList().getPlayer(deal.who);
		if (p == null) {
			deal = null;
			return null;
		}
		int got = have(deal.theirs) - deal.had;
		if (got >= deal.theirCount) {
			if (deal.ours == null) {
				c.say("Thank you so much, " + deal.name + "!");
				c.trust(deal.who, 0.2f);
			} else {
				if (!deal.gave) c.chores.give(p, giveKey(deal.ours), deal.ourCount);
				c.say(deal.gave ? "Thanks! Nice doing business with you." : "Got it, thanks! Here are your " + named(deal.ours, deal.ourCount) + ".");
				c.trust(deal.who, 0.15f);
			}
			deal = null;
			return null;
		}
		if (c.player.level().getGameTime() > deal.until) {
			if (deal.gave) {
				c.trust(deal.who, -0.6f);
				c.say("You never paid me for the " + named(deal.ours, 2) + ". I won't trust you so easily again.");
			} else {
				c.say(deal.ours == null ? "Never mind, then." : "You didn't give me the " + named(deal.theirs, 2) + ", so there's no deal.");
			}
			deal = null;
			return null;
		}
		if (c.chores.busy()) return null;                                // handing over its part first
		c.goals.instant = "waiting for " + deal.name + "'s part of the trade";
		var tossed = tossed(deal.theirs);
		if (tossed != null) return c.walkTo(tossed.position());           // they threw it: go and pick it up
		if (c.player.distanceTo(p) > 2.5) return c.walkTo(p.position());
		c.hands.watching = p;
		return Action.IDLE;
	}

	/** Items of this kind lying on the ground close by that it can see (what the other player tossed it). */
	private net.minecraft.world.entity.item.ItemEntity tossed(String key) {
		net.minecraft.world.entity.item.ItemEntity best = null;
		for (var item : c.player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, c.player.getBoundingBox().inflate(8),
				x -> x.isAlive() && matches(x.getItem(), key))) {
			if (best == null || c.player.distanceTo(item) < c.player.distanceTo(best)) best = item;
		}
		return best != null && (c.player.distanceTo(best) <= 6 || WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, best)) ? best : null;
	}

	private boolean matches(ItemStack s, String key) {
		String n = path(s);
		return switch (key) {
			case "iron" -> n.equals("iron_ingot") || n.equals("raw_iron");
			case "gold" -> n.equals("gold_ingot") || n.equals("raw_gold");
			default -> c.itemKey(s).equals(key);
		};
	}

	// ----------------------------------------------------------------------- with villagers
	private LivingEntity villager;
	private String villagerName = "villager";
	/** What it saw villagers offer, by villager: its knowledge of the market. */
	private final Map<UUID, List<String>> offersSeen = new HashMap<>();

	/** The nearest villager it can trade with: one it knows is there (within 6 blocks) or can see. */
	AbstractVillager villagerNear() {
		AbstractVillager best = null;
		double bestD = 24;
		for (AbstractVillager v : c.player.level().getEntitiesOfClass(AbstractVillager.class, c.player.getBoundingBox().inflate(24),
				x -> x.isAlive() && !x.isBaby())) {
			double d = c.player.distanceTo(v);
			if (d < bestD && (d <= 6 || WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, v))) {
				bestD = d;
				best = v;
			}
		}
		return best;
	}

	/** Emeralds to spend, or plenty of something villagers buy? */
	boolean hasSomethingToTrade() {
		if (have("emerald") > 0) return true;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && base(path(s)) < 1f && s.getCount() >= 16) return true;
		}
		return false;
	}

	/** Start trading with the nearest villager (a plan sentence, or why it can't). */
	String withVillager(String hint) {
		if (!c.mod.config.trading) return "You can't trade, because trading is turned off.";
		AbstractVillager v = villagerNear();
		if (v == null) return "You don't see any villagers to trade with.";
		villager = v;
		villagerName = v.getName().getString().toLowerCase(Locale.ROOT);
		c.chores.beginTrade();
		return String.format(Locale.ROOT, "You will trade with the %s %.0f blocks away.", villagerName, c.player.distanceTo(v));
	}

	/** One step of trading with a villager: walk up, open its trades, take the good ones, close. */
	Action villagerStep() {
		LivingEntity v = villager;
		if (v == null || !v.isAlive() || v.level() != c.player.level()) {
			c.chores.done("The villager is gone.");
			return null;
		}
		c.chores.doing = "trading with the " + villagerName;
		if (c.player.distanceTo(v) > 2.5) return c.walkTo(v.position());
		c.hands.stop();
		c.hands.face(v.getEyePosition());
		Map<String, Integer> before = bag();
		Compat.interact(c.player, v);                                    // right-click: the trading screen opens
		if (!(c.player.containerMenu instanceof MerchantMenu menu)) {
			c.chores.done("The " + villagerName + " doesn't want to trade right now.");
			return null;
		}
		int trades = trade(menu, v);
		c.player.closeContainer();                                       // what's left in the trade slots comes back
		c.acted = true;
		String what = changes(before, bag());
		c.chores.done(trades == 0 ? "The " + villagerName + " has nothing I want, and nothing I have is worth it."
				: "I traded with the " + villagerName + ": " + what + ".");
		villager = null;
		return Action.IDLE;
	}

	/** On the trading screen: the best offer for it, again and again (up to 3), while it's worth it. */
	private int trade(MerchantMenu menu, LivingEntity v) {
		MerchantOffers offers = menu.getOffers();
		List<String> seen = new ArrayList<>();
		for (MerchantOffer o : offers) seen.add(describe(o));
		offersSeen.put(v.getUUID(), seen);
		int done = 0;
		for (int round = 0; round < 3; round++) {
			int best = -1;
			float bestGain = 0.15f;
			for (int i = 0; i < offers.size(); i++) {
				MerchantOffer o = offers.get(i);
				if (o.isOutOfStock() || !canPay(o.getCostA()) || !o.getCostB().isEmpty() && !canPay(o.getCostB())) continue;
				float gain = worthGet(o.getResult()) - worthGive(o.getCostA()) - (o.getCostB().isEmpty() ? 0 : worthGive(o.getCostB()));
				if (gain > bestGain) {
					bestGain = gain;
					best = i;
				}
			}
			if (best < 0) break;
			menu.setSelectionHint(best);                                // clicking the offer on the left
			menu.tryMoveItems(best);                                    // puts the payment in
			if (!menu.getSlot(2).hasItem()) break;
			boolean selling = path(offers.get(best).getResult()).equals("emerald");
			Compat.click(menu, 2, selling, c.player);                   // selling: shift-click (as many as it can); buying: one
			ItemStack carried = menu.getCarried();
			if (!carried.isEmpty()) {
				if (!c.player.getInventory().add(carried)) Compat.drop(c.player, carried.copy());
				menu.setCarried(ItemStack.EMPTY);
			}
			done++;
		}
		return done;
	}

	private boolean canPay(ItemStack cost) {
		return countItem(path(cost)) >= cost.getCount();
	}

	private static String describe(MerchantOffer o) {
		String s = o.getCostA().getCount() + " " + path(o.getCostA()).replace('_', ' ');
		if (!o.getCostB().isEmpty()) s += " and " + o.getCostB().getCount() + " " + path(o.getCostB()).replace('_', ' ');
		return s + " for " + o.getResult().getCount() + " " + path(o.getResult()).replace('_', ' ');
	}

	/** What it knows villagers near it trade (for its notes). */
	String market() {
		for (var e : offersSeen.entrySet()) {
			if (!e.getValue().isEmpty()) return "A villager you traded with offers: " + String.join("; ", e.getValue().subList(0, Math.min(3, e.getValue().size()))) + ".";
		}
		return "";
	}

	private Map<String, Integer> bag() {
		Map<String, Integer> out = new HashMap<>();
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) out.merge(path(s).replace('_', ' '), s.getCount(), Integer::sum);
		}
		return out;
	}

	private static String changes(Map<String, Integer> before, Map<String, Integer> after) {
		List<String> gave = new ArrayList<>(), got = new ArrayList<>();
		java.util.Set<String> all = new java.util.TreeSet<>(before.keySet());
		all.addAll(after.keySet());
		for (String k : all) {
			int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
			if (d < 0) gave.add(-d + " " + k);
			if (d > 0) got.add(d + " " + k);
		}
		return "gave " + (gave.isEmpty() ? "nothing" : String.join(", ", gave)) + " and got " + (got.isEmpty() ? "nothing" : String.join(", ", got));
	}
}
