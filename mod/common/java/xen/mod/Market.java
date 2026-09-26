package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * A village's economy, the Xens' own (no commands, no shop plugin): what the tribe decides on and does.
 * <ul>
 *   <li><b>Money.</b> Once a tribe has a village, its Xens settle on a currency: each one proposes the valuable thing
 *   it has most of (emeralds, diamonds, iron, gold, even coal), and what most of them propose becomes the village's
 *   money. It's announced, and it's what they price things in from then on.</li>
 *   <li><b>Prices.</b> What a thing is worth in that money: what villagers would give for it, then up when many want
 *   it and few have it, down when everyone has plenty. Prices move with every sale.</li>
 *   <li><b>Shops.</b> A Xen with more than it needs of something (and a head for trading) opens a shop at the village:
 *   a stall with a chest of its goods and a sign with its prices. It stocks it from what it can spare.</li>
 *   <li><b>Buying.</b> A Xen that needs something (food, iron for tools, wood for its house) and has money buys it
 *   from a shopkeeper: it walks over, pays (tosses the money), and the shopkeeper hands the goods over, like two
 *   players trading. A player can buy too: talk to the shopkeeper ("how much for your logs?", "buy 8 logs").</li>
 * </ul>
 */
final class Market {
	private final Tribe tribe;
	/** The village's money (an item key), or null until they've decided. */
	String currency;
	/** What things sell for, in the village's money (per one). */
	final Map<String, Float> prices = new LinkedHashMap<>();
	/** Shops: whose, where the stall is, what it sells. */
	final Map<UUID, Shop> shops = new LinkedHashMap<>();
	private final List<Deal> deals = new ArrayList<>();
	private final Random random = new Random();
	private long nextVote;

	record Shop(String owner, BlockPos stall, List<String> goods) {}

	/** A sale on its way: who buys what from whom, for how much; and whether the money has been paid. */
	private static final class Deal {
		final Companion buyer, seller;
		final String item;
		final int count, cost;
		final long started;
		int sellerHad;
		boolean paid;

		Deal(Companion buyer, Companion seller, String item, int count, int cost, long now) {
			this.buyer = buyer;
			this.seller = seller;
			this.item = item;
			this.count = count;
			this.cost = cost;
			this.started = now;
		}
	}

	Market(Tribe tribe) {
		this.tribe = tribe;
	}

	private static final String[] MONEY = {"emerald", "diamond", "gold_ingot", "iron_ingot", "coal"};

	/** Every five seconds, from the tribe: the vote on money, prices, shops, and sales on their way. */
	void tick(long now) {
		if (tribe.members.size() < 2 || tribe.center == null) return;
		if (currency == null && now >= nextVote) vote(now);
		if (currency == null) return;
		updatePrices();
		openShops();
		trade(now);
		settle(now);
	}

	/** Each Xen proposes the money it has most of (by worth); the most proposed wins. */
	private void vote(long now) {
		nextVote = now + 20 * 60;
		Map<String, Integer> votes = new HashMap<>();
		for (Companion m : tribe.members) {
			if (m.player == null) continue;
			var items = m.items();
			String best = null;
			float bestWorth = 0;
			for (String k : MONEY) {
				float w = items.getOrDefault(k, 0) * Trader.base(k);
				if (w > bestWorth) {
					bestWorth = w;
					best = k;
				}
			}
			if (best != null) votes.merge(best, 1, Integer::sum);
		}
		if (votes.isEmpty()) return;
		currency = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
		Companion speaker = tribe.members.get(0);
		String money = Trader.named(currency, 2);
		speaker.say("We'll trade in " + money + " in our village. " + money.substring(0, 1).toUpperCase(Locale.ROOT) + money.substring(1)
				+ (money.endsWith("s") ? " are" : " is") + " our money now!");
		XenMod.LOG.info("{} chose {} as its money", tribe.name, currency);
	}

	/** What sells for how much: villager worth in the village's money, times how scarce it is in the village. */
	private void updatePrices() {
		float unit = Trader.base(currency);
		for (String k : new String[] {"log", "cobblestone", "food", "bread", "iron_ingot", "coal", "wheat", "torch", "diamond", "string", "arrow", "raw_iron"}) {
			if (k.equals(currency)) continue;
			int have = 0, want = 0;
			for (Companion m : tribe.members) {
				if (m.player == null) continue;
				int n = m.items().getOrDefault(k, 0);
				have += n;
				if (n == 0) want++;
			}
			float scarce = (1f + want) / (1f + Math.min(tribe.members.size(), have / 16f));
			float p = Trader.base(k) / unit * Math.max(0.5f, Math.min(2.5f, scarce));
			prices.merge(k, p, (old, now) -> old + 0.2f * (now - old));           // prices move slowly
		}
	}

	/** One whole unit of money buys how many of it (at least one). */
	int perCoin(String item) {
		Float p = prices.get(item);
		return p == null || p <= 0 ? 0 : Math.max(1, Math.round(1f / p));
	}

	/** How much money for this many (at least one coin). */
	int cost(String item, int count) {
		Float p = prices.get(item);
		return p == null ? 0 : Math.max(1, Math.round(p * count));
	}

	/** A Xen that has plenty (and likes trading) opens a shop at the village. */
	private void openShops() {
		for (Companion m : tribe.members) {
			if (m.player == null || shops.containsKey(m.player.getUUID()) || m.builder.busy() || m.chores.busy()) continue;
			if (m.personality.chattiness < 0.45f && m.goals.dream != Goals.Long.TRADER) continue;
			List<String> goods = new ArrayList<>();
			var items = m.items();
			if (items.getOrDefault("log", 0) >= 32) goods.add("log");
			if (items.getOrDefault("cobblestone", 0) >= 96) goods.add("cobblestone");
			if (items.getOrDefault("food", 0) >= 16) goods.add("food");
			if (items.getOrDefault("coal", 0) >= 24) goods.add("coal");
			if (items.getOrDefault("iron_ingot", 0) >= 12 && !currency.equals("iron_ingot")) goods.add("iron_ingot");
			if (goods.isEmpty() || shops.size() >= Math.max(1, tribe.members.size() / 2)) continue;
			BlockPos stall = stallSpot();
			shops.put(m.player.getUUID(), new Shop(m.name, stall, goods));
			m.shop.open(this, stall, goods);
			return;                                                           // one new shop a round
		}
	}

	private BlockPos stallSpot() {
		double a = shops.size() * 1.3 + 0.7;
		return tribe.center.offset((int) Math.round(Math.cos(a) * 8), 0, (int) Math.round(Math.sin(a) * 8));
	}

	/** Someone who needs something and has money buys it from a shopkeeper who has plenty of it. */
	private void trade(long now) {
		if (deals.size() >= 2) return;
		for (Companion buyer : tribe.members) {
			if (buyer.player == null || buyer.chores.busy() || buyer.builder.busy() || busy(buyer)) continue;
			var items = buyer.items();
			int money = items.getOrDefault(currency, 0);
			if (money == 0) continue;
			String want = items.getOrDefault("food", 0) == 0 && buyer.player.getFoodData().getFoodLevel() < 16 ? "food"
					: buyer.crafter.pickTier() < 3 && items.getOrDefault("iron_ingot", 0) < 3 && !currency.equals("iron_ingot") ? "iron_ingot"
					: buyer.builder.busy() || buyer.goals.home == null && items.getOrDefault("log", 0) < 16 ? "log" : null;
			if (want == null) continue;
			int count = want.equals("iron_ingot") ? 3 : want.equals("food") ? 4 : 16;
			int cost = cost(want, count);
			if (cost == 0 || cost > money) continue;
			for (var e : shops.entrySet()) {
				Companion seller = tribe.members.stream().filter(x -> x.player != null && x.player.getUUID().equals(e.getKey())).findFirst().orElse(null);
				if (seller == null || seller == buyer || busy(seller) || !e.getValue().goods().contains(want)) continue;
				if (seller.items().getOrDefault(want, 0) < count + seller.chores.keepFor(want)) continue;
				if (seller.player.level() != buyer.player.level() || seller.player.distanceTo(buyer.player) > 48) continue;
				String plan = buyer.chores.give(seller.player, currency, cost);         // pay first (they're in the same village)
				if (!plan.startsWith("You will")) continue;
				buyer.chores.own = true;
				Deal d = new Deal(buyer, seller, want, count, cost, now);
				d.sellerHad = seller.items().getOrDefault(currency, 0);
				deals.add(d);
				buyer.say(String.format(Locale.ROOT, "%s, I'll buy %d %s for %d %s.", seller.name, count, Trader.named(want, count), cost, Trader.named(currency, cost)));
				seller.chatter(seller.pick3("Deal!", "Sure, coming right up.", "Pleasure doing business."), true);
				buyer.journal("trade", "buys " + count + " " + want + " from " + seller.name + " for " + cost + " " + currency);
				return;
			}
		}
	}

	private boolean busy(Companion c) {
		for (Deal d : deals) if (d.buyer == c || d.seller == c) return true;
		return false;
	}

	/** The money arrived: the shopkeeper hands the goods over; a sale that never finishes is called off. */
	private void settle(long now) {
		deals.removeIf(d -> {
			if (d.buyer.player == null || d.seller.player == null || now - d.started > 20 * 90) return true;
			if (!d.paid && d.seller.items().getOrDefault(currency, 0) >= d.sellerHad + d.cost) {
				d.paid = true;
				String plan = d.seller.chores.give(d.buyer.player, d.item, d.count);
				d.seller.chores.own = true;
				d.seller.journal("trade", "sold " + d.count + " " + d.item + " to " + d.buyer.name + " (" + plan + ")");
				prices.computeIfPresent(d.item, (k, p) -> p * 1.05f);                // it sold: a little dearer
				return true;
			}
			return false;
		});
	}

	/** A shop sign's lines: "Pip's shop", then what sells for what. */
	String sign(String owner, List<String> goods) {
		StringBuilder sb = new StringBuilder(owner + "'s shop:");
		for (String g : goods) {
			int per = perCoin(g);
			if (per > 0) sb.append(' ').append(per).append(' ').append(g.replace("_ingot", "").replace('_', ' ')).append(" =1 ")
					.append(currency.replace("_ingot", "").replace('_', ' ')).append(';');
		}
		return sb.toString();
	}

	String describe() {
		if (currency == null) return "";
		StringBuilder sb = new StringBuilder("Your village trades in " + Trader.named(currency, 2) + ".");
		if (!shops.isEmpty()) {
			sb.append(" Shops:");
			for (Shop s : shops.values()) sb.append(' ').append(s.owner()).append(" sells ").append(String.join(", ", s.goods())).append(';');
			sb.setCharAt(sb.length() - 1, '.');
		}
		return sb.toString();
	}

	/** The shop a Xen keeps (see {@link Market}): its stall, stocking it, and standing at it now and then. */
	static final class Keeper {
		private final Companion c;
		private Market market;
		BlockPos stall;
		private List<String> goods = List.of();
		private int step;
		private long nextVisit;

		Keeper(Companion c) {
			this.c = c;
		}

		boolean open() {
			return stall != null;
		}

		void open(Market m, BlockPos at, List<String> sells) {
			market = m;
			stall = at;
			goods = sells;
			step = 0;
			signTries = 0;
			c.say(c.pick3("I'm opening a shop in the village!", "Shop's open soon: I have more than I need.", "Anyone need " + Trader.named(sells.get(0), 2)
					+ "? I'm opening a shop."));
			c.journal("does", "opens a shop at " + at.toShortString() + " selling " + sells);
		}

		private int signTries;

		private boolean hasSign() {
			var inv = c.player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
				if (n.endsWith("_sign") && !n.contains("hanging")) return true;
			}
			return false;
		}

		/** The wood its planks (or logs) are of, for a sign. */
		private String plankWood() {
			var inv = c.player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				String n = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath();
				if (n.endsWith("_planks")) return n.substring(0, n.length() - 7);
				if (n.endsWith("_log") && !n.startsWith("stripped_")) return n.substring(0, n.length() - 4);
			}
			return null;
		}

		/** Setting the stall up (a chest, a sign with the prices), then its goods in the chest. Null when there's nothing to do. */
		Action next() {
			if (stall == null || market == null || c.player.isCreative() || c.chores.busy() || c.builder.busy() || c.storage.busy()) return null;
			var level = (net.minecraft.server.level.ServerLevel) c.player.level();
			if (!Places.dim(level).equals("overworld")) return null;
			BlockPos chestAt = stall;
			boolean chest = BuiltInRegistries.BLOCK.getKey(level.getBlockState(chestAt).getBlock()).getPath().equals("chest");
			if (step == 0 && !chest) {
				if (c.player.blockPosition().distSqr(chestAt) > 9) return c.walkTo(Vec3.atBottomCenterOf(chestAt.relative(net.minecraft.core.Direction.NORTH)));
				if (c.items().getOrDefault("chest", 0) == 0) {
					if (!c.crafter.hasOrder()) c.crafter.orderRecipe("chest", 1);
					return null;
				}
				if (c.hands.placeSeen(chestAt, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("chest"), chestAt.below(),
						net.minecraft.core.Direction.UP, -1)) {
					c.acted = true;
					step = 1;
					return Action.PLACE;
				}
				stall = XenMod.surface(level, chestAt.getX() + 1, chestAt.getZ()) == null ? null : BlockPos.containing(XenMod.surface(level, chestAt.getX() + 1, chestAt.getZ()));
				return null;
			}
			if (step <= 1 && signTries < 4 && !c.crafter.hasOrder() && !hasSign()) {   // no sign for the prices yet: it makes one
				signTries++;
				String wood = plankWood();
				if (wood != null) {
					c.crafter.orderRecipe(wood + "_sign", 1);
					return null;
				}
			}
			if (step <= 1) {
				if (c.crafter.hasOrder()) return null;
				step = 2;
				c.storage.chests.put(chestAt.immutable(), new LinkedHashMap<>());
				c.places.remember("shop", chestAt);
				if (c.hands.placeSign(market.sign(c.name, goods))) {                    // its prices, on a sign by the stall
					c.acted = true;
					return Action.PLACE;
				}
				return null;
			}
			long now = level.getGameTime();
			if (now < nextVisit) return null;
			nextVisit = now + 20 * 60 * 4;
			return null;
		}

		String describe() {
			return stall == null ? "" : "You keep a shop at " + stall.getX() + ", " + stall.getZ() + " selling " + String.join(", ", goods) + ".";
		}
	}
}
