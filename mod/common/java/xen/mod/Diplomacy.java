package xen.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Fights between players (and Xens) don't have to end with somebody dead. In a fight with a player or another Xen, a
 * Xen talks, like players do: losing, it asks for a truce; badly hurt, it gives up; winning, it tells the other to
 * give up. The other side decides (a Xen by its health, its trust in the other and its bravery): it takes the truce,
 * or wants something for it ("only if you give me your iron"), or says no and fights on. A truce holds for two
 * minutes; whoever breaks it loses a lot of trust. You can ask too: "truce", "peace", "sorry", "I give up".
 */
final class Diplomacy {
	private final Companion c;
	private final Random random = new Random();
	/** Peace with someone (a truce, or a fight given up), until when (game time), and when it was made (their ticks). */
	private final Map<UUID, Long> peace = new HashMap<>();
	private final Map<UUID, Integer> madeAt = new HashMap<>();
	private long lastLine = -1000;
	private UUID askedTruce;

	Diplomacy(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** At peace with them (and they haven't broken it by hurting it since)? */
	boolean atPeace(Entity e) {
		Long until = peace.get(e.getUUID());
		if (until == null) return false;
		if (now() > until) {
			peace.remove(e.getUUID());
			return false;
		}
		Integer made = madeAt.get(e.getUUID());
		if (c.player.getLastHurtByMob() == e && made != null && c.player.getLastHurtByMobTimestamp() > made + 10) {   // they hit it again
			peace.remove(e.getUUID());
			c.trust.merge(e.getUUID(), -0.4f, (a, b) -> Math.max(-1f, a + b));
			c.say(c.pick3("You broke the truce!", "Liar! So much for peace.", "We had a deal!"));
			c.journal("fight", e.getName().getString() + " broke the truce");
			return false;
		}
		return true;
	}

	void makePeace(Entity other, String why) {
		peace.put(other.getUUID(), now() + 2400);
		madeAt.put(other.getUUID(), c.player.tickCount);
		askedTruce = null;
		c.journal("fight", "peace with " + other.getName().getString() + " (" + why + ")");
	}

	private boolean speak() {
		if (now() - lastLine < 80) return false;
		lastLine = now();
		return true;
	}

	/** Every decision of a fight with a player or a Xen: does it want to talk? */
	void during(LivingEntity foe) {
		if (!(foe instanceof ServerPlayer sp) || c.inArena || atPeace(sp)) return;
		float mine = c.player.getHealth(), theirs = sp.getHealth();
		Companion other = sp instanceof XenPlayer x ? x.companion : null;
		if (mine <= 5 && theirs >= mine + 3 && c.personality.bravery < 0.85f && speak()) {          // losing badly: it gives up
			c.say(c.pick3("I give up! You win.", "Okay, okay, you win! Stop!", "I yield! I yield!"));
			makePeace(sp, "gave up");
			if (other != null) other.diplomacy.gaveUp(c);
			return;
		}
		if (mine <= 10 && theirs >= mine + 2 && askedTruce == null && speak()) {                    // losing: a truce?
			askedTruce = sp.getUUID();
			c.say(c.pick3("Truce? We both walk away.", "Let's stop this. Truce?", "Hey, truce? Nobody has to die."));
			if (other != null) other.diplomacy.truceOffered(c);
			return;
		}
		if (theirs <= 6 && mine >= theirs + 6 && random.nextFloat() < 0.5f && speak()) {           // winning: give up?
			c.say(c.pick3("Give up? I'll let you go.", "Had enough? Say truce.", "Yield, and walk away."));
			if (other != null) other.diplomacy.toldToGiveUp(c);
		}
	}

	/** Another Xen asks for a truce: take it, want something for it, or fight on. */
	void truceOffered(Companion from) {
		float mine = c.player.getHealth(), theirs = from.player.getHealth();
		boolean winning = mine > theirs + 3;
		double yes = 0.3 + 0.5 * c.trust(from.player.getUUID()) + (mine <= 10 ? 0.35 : 0) + 0.25 * (1 - c.personality.bravery) - (winning ? 0.3 : 0);
		c.server.execute(() -> {                                              // (a moment later, like a player reading it)
			if (c.player == null) return;
			if (random.nextDouble() < yes) {
				c.say(c.pick3("Deal. Truce.", "Fine. Truce.", "Okay. We're done here."));
				makePeace(from.player, "truce");
				from.diplomacy.makePeace(c.player, "truce");
				return;
			}
			String want = winning ? from.diplomacy.mostValuable() : null;
			if (want != null) {
				c.say("Only if you give me your " + want.replace('_', ' ') + ".");
				from.diplomacy.demanded(c, want);
				return;
			}
			c.say(c.pick3("No way. You started this!", "Not a chance.", "A truce? Now? Fight me!"));
		});
	}

	/** The other Xen wants something for the truce: pay (it's losing, or not brave), or refuse. */
	void demanded(Companion winner, String item) {
		boolean losing = c.player.getHealth() < winner.player.getHealth();
		if (losing || c.personality.bravery < 0.5f) {
			ItemStack paid = take(item);
			if (!paid.isEmpty()) {
				if (!winner.player.getInventory().add(paid)) Compat.drop(winner.player, paid);
				c.say(c.pick3("Fine. Take it.", "Ugh. Here.", "Okay, it's yours. Truce."));
				makePeace(winner.player, "paid " + item);
				winner.diplomacy.makePeace(c.player, "was paid " + item);
				return;
			}
		}
		c.say(c.pick3("Never!", "Not happening.", "You'll have to take it from me."));
	}

	/** Told to give up (it's losing): it does, or not. */
	void toldToGiveUp(Companion winner) {
		float mine = c.player.getHealth();
		if (mine <= 8 || mine <= 12 && c.personality.bravery < 0.4f) {
			c.say(c.pick3("Okay... I give up.", "Fine, you win.", "I yield."));
			makePeace(winner.player, "gave up");
			winner.diplomacy.gaveUp(c);
		} else if (speak()) {
			c.say(c.pick3("Never!", "I'm just getting started!", "Not yet!"));
		}
	}

	/** The other one gave up: it stops (a good sport), and maybe asks for something (a bold one). */
	void gaveUp(Companion loser) {
		makePeace(loser.player, "they gave up");
		c.server.execute(() -> {
			if (c.player == null) return;
			String want = c.personality.bravery > 0.65f ? loser.diplomacy.mostValuable() : null;
			if (want != null && random.nextFloat() < 0.5f) {
				c.say("Good fight. I'll take your " + want.replace('_', ' ') + ", though.");
				ItemStack paid = loser.diplomacy.take(want);
				if (!paid.isEmpty() && !c.player.getInventory().add(paid)) Compat.drop(c.player, paid);
			} else {
				c.say(c.pick3("Good fight.", "GG. No hard feelings.", "You fought well."));
				c.trust.merge(loser.player.getUUID(), 0.05f, Float::sum);
			}
		});
	}

	/** A player says "truce", "peace", "sorry", "I give up": what it says back (and whether it stops). */
	String asked(ServerPlayer from) {
		String who = from.getName().getString();
		boolean fightingThem = c.player.getLastHurtByMob() == from && c.player.tickCount - c.player.getLastHurtByMobTimestamp() < 600;
		if (!fightingThem) return c.pick3("We're good, " + who + ".", "No worries.", "Peace? We were never at war!");
		float trust = c.trust(from.getUUID());
		boolean armed = c.isWeapon(from.getMainHandItem());
		if (trust >= 0.2f || c.player.getHealth() < 10 || !armed || random.nextFloat() < 0.4f) {
			makePeace(from, "they asked");
			c.trust.merge(from.getUUID(), 0.05f, (a, b) -> Math.min(1f, a + b));
			return c.pick3("Okay. Truce. Don't do that again, " + who + ".", "Fine, truce. But I'm watching you.", "Deal. Let's stop.");
		}
		return c.pick3("You started this. I don't believe you.", "Nope. Not after that.", "Put the sword away first.");
	}

	/** What it carries that's worth the most (for a price): diamonds, iron, gold, emeralds, coal, logs. */
	String mostValuable() {
		var items = c.items();
		for (String k : new String[] {"diamond", "iron_ingot", "raw_iron", "gold_ingot", "emerald", "raw_gold", "coal", "log"}) {
			if (items.getOrDefault(k, 0) > 0) return k;
		}
		return null;
	}

	/** A few of that out of its inventory (to hand over): up to 3 of something rare, 8 of coal or logs. */
	ItemStack take(String key) {
		var inv = c.player.getInventory();
		int most = key.equals("coal") || key.equals("log") ? 8 : 3;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !c.itemKey(s).equals(key)) continue;
			return s.split(Math.min(most, s.getCount()));
		}
		return ItemStack.EMPTY;
	}

	static String name(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
	}
}
