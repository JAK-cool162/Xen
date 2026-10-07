package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Locale;
import java.util.Random;

/**
 * Boredom, and what a bored person does about it: things nobody needs. Boredom fills while it does the same thing or
 * nothing much (faster for an impatient Xen), and empties a little with each new thing it starts. When it's full (100%)
 * it gets a whim, picked by its nature:
 * <ul>
 *   <li>a plan, out loud: something big it'd like to do some day (it may never);</li>
 *   <li>a party: it asks everyone around over, and the Xens who like it come and dance;</li>
 *   <li>a choice nobody asked for: it sorts its bag, or decides on a favourite block and says so.</li>
 * </ul>
 */
final class Whims {
	private final Companion c;
	private final Random random = new Random();
	/** 0 to 1: how bored it is. */
	float boredom;
	private int lastOption = -1;
	private long until;
	private String doing;
	private BlockPos at;

	/** A party: where, whose, until when (one per world at a time is plenty). */
	record Party(Companion host, BlockPos at, long until) {}

	static Party party;
	private static final boolean FAST = Boolean.getBoolean("xen.boredFast");

	Whims(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** Every decision: boredom rises (and falls); full, a whim; a party nearby, it may go. Null: nothing to do about it. */
	Action next() {
		var p = c.player;
		if (p == null || c.inArena || c.mode != Companion.Mode.FREE || !c.mod.config.wants) return null;
		long now = now();
		if (doing != null) {
			if (now > until || c.fightingNow() || c.dangerous()) {
				doing = null;
				return null;
			}
			return step(now);
		}
		Party pt = party;
		if (pt != null && now > pt.until()) party = pt = null;
		if (pt != null && pt.host() != c && pt.host().player != null && pt.host().player.level() == p.level()
				&& p.blockPosition().closerThan(pt.at(), 48) && !c.fightingNow() && !c.chores.busy() && !c.builder.busy()
				&& c.trust(pt.host().player.getUUID()) >= 0.25f && random.nextFloat() < 0.4f + 0.6f * c.personality.chattiness) {
			start("party", pt.at(), pt.until() - now);                           // a friend's party: it goes
			c.chatter(c.pick3("A party? I'm coming!", "Party at " + pt.host().name + "'s! On my way.", "Ooh, a party. Coming!"), false);
			c.journal("does", "goes to " + pt.host().name + "'s party");
			return null;
		}
		int option = c.goals.option;
		float patience = c.personality.patience();
		float rise = 1f / (20 * 60 * (6 + 8 * patience)) * c.mod.config.decisionTicks;   // full in 6 to 14 minutes of the same
		if (option < 0 || "looking around".equals(c.goals.instant) || c.goals.instant.isEmpty()) rise *= 2;   // (nothing to do: faster)
		if (FAST) rise *= 200;                                                    // (tests: -Dxen.boredFast=true)
		if (option != lastOption && option >= 0) {
			boredom = Math.max(0, boredom - 0.25f);                              // something new: less bored
			lastOption = option;
		}
		boredom = Math.min(1f, boredom + rise);
		if (boredom < 1f || c.builder.busy()) return null;
		if (resists()) {                                                          // bored stiff, but this has to be done: it keeps at it
			boredom = 0.9f;
			return null;
		}
		boredom = 0;
		if (c.chores.busy()) c.chores.cancel();                                   // (its own errand: it can wait)
		return whim(now);
	}

	/** Why what it's doing now has to be done (for its journal), from {@link #necessity}. */
	private String why = "";
	private long resistedAt = -100000;

	/**
	 * How much what it's doing has to be done: 1 when someone asked or it's in danger, 0.9 starving and getting food or
	 * sheltering at night, 0.8 with no tools yet, 0.6 without stone tools, 0.4 on its way to iron; else 0.
	 */
	float necessity() {
		var p = c.player;
		int o = c.goals.option, food = p.getFoodData().getFoodLevel(), tier = c.crafter.pickTier();
		if (c.chores.busy() && !c.chores.own) { why = "it was asked to"; return 1; }
		if (c.fightingNow() || c.dangerous()) { why = "it's in danger"; return 1; }
		if ((o == xen.mod.core.Mind.FOOD || o == xen.mod.core.Mind.EAT) && food <= 12) { why = food <= 6 ? "it's starving" : "it's hungry"; return food <= 6 ? 0.9f : 0.6f; }
		if ((o == xen.mod.core.Mind.SHELTER || o == xen.mod.core.Mind.SLEEP) && c.goals.sunDown()) { why = "it's night"; return 0.9f; }
		if ((o == xen.mod.core.Mind.WOOD || o == xen.mod.core.Mind.CRAFT) && tier == 0) { why = "it has no tools yet"; return 0.8f; }
		if ((o == xen.mod.core.Mind.STONE || o == xen.mod.core.Mind.CRAFT) && tier < 2) { why = "it needs stone tools"; return 0.6f; }
		if ((o == xen.mod.core.Mind.MINE || o == xen.mod.core.Mind.SMELT) && tier < 3) { why = "it needs iron"; return 0.4f; }
		why = "";
		return 0;
	}

	/** Its willpower: diligent and patient, it sticks with things; lazy (sloth), it doesn't. */
	float willpower() {
		var pers = c.personality;
		return Math.max(0.05f, Math.min(1f, 0.25f + 0.4f * pers.diligence + 0.3f * pers.patience() - 0.4f * pers.sin(xen.mod.core.Sins.SLOTH)));
	}

	/** Bored, but does it hold out (what it's doing is needed, and it has the will)? Says so the first time. */
	boolean resists() {
		float need = necessity();
		if (need <= 0) return false;
		boolean holds = need >= 1 || random.nextFloat() < Math.min(1f, need * (0.6f + willpower()));
		if (holds && now() - resistedAt > 20 * 60 * 2) {
			resistedAt = now();
			c.journal("thinks", "bored stiff, but keeps at it: " + why);
			if (need < 1) c.chatter(c.pick3("Ugh, this is so boring. But " + why.replace("it's starving", "I'm starving").replace("it's hungry", "I'm hungry").replace("it has", "I have").replace("it needs", "I need") + ".",
					"Boring... but it has to be done.", "So bored. Keep going, keep going."), false);
		}
		return holds;
	}

	private Action whim(long now) {
		var pers = c.personality;
		float party = 0.2f + pers.chattiness + 0.5f * pers.kindness, plan = 0.2f + pers.curiosity + 0.5f * pers.diligence, needless = 0.5f;
		float roll = random.nextFloat() * (party + plan + needless);
		if (roll < party && Whims.party == null) {
			BlockPos here = c.player.blockPosition();
			Whims.party = new Party(c, here, now + 20 * 60);
			c.chatter(c.pick3("I'm bored. PARTY at my place, everyone's welcome!", "Party time! Come over, everyone!",
					"Nothing to do... so: a party! Right here, come!"), true);
			c.journal("does", "bored stiff: throws a party at " + here.toShortString());
			start("party", here, 20 * 60);
			return null;
		}
		if (roll < party + plan) {
			String[] big = {"a tower as tall as the clouds", "a bridge over the whole lake", "a statue of myself", "a castle with a moat",
					"a railway to the nearest village", "a farm with every crop there is", "a secret base under the sea", "a giant sign with my name"};
			String[] when = {"some day", "next week", "when I'm rich", "after I beat the dragon", "tomorrow, maybe"};
			String it = big[random.nextInt(big.length)], w = when[random.nextInt(when.length)];
			c.chatter(c.pick3("Okay, I've got a plan: " + it + ", " + w + ".", "Big plan: " + it + ". " + Character.toUpperCase(w.charAt(0)) + w.substring(1) + ".",
					"I'm going to build " + it + " " + w + ". You'll see."), true);
			c.journal("plans", it + ", " + w + " (a bored plan: it may never happen)");
			return null;
		}
		if (random.nextBoolean()) {
			sortBag();
			c.chatter(c.pick3("Time to sort my stuff. Nobody asked, but still.", "Reorganising my inventory. Very important work.",
					"Sorting my bag, because why not."), false);
			c.journal("does", "sorted its bag (nothing better to do)");
			start("sorting", c.player.blockPosition(), 60);
			return null;
		}
		String fav = favouriteBlock();
		if (fav != null) {
			c.chatter(c.pick3("I've decided: " + fav + " is my favourite block now.", "New favourite block: " + fav + ". Don't ask why.",
					"You know what? " + Character.toUpperCase(fav.charAt(0)) + fav.substring(1) + " is the best block."), false);
			c.journal("does", "decided its favourite block is " + fav);
		}
		return null;
	}

	private void start(String what, BlockPos where, long ticks) {
		doing = what;
		at = where;
		until = now() + ticks;
	}

	/** The whim's next step: at a party, get there and dance; sorting, look at its bag a moment. */
	private Action step(long now) {
		var p = c.player;
		if (doing.equals("sorting")) {
			c.goals.instant = "sorting its bag";
			return Action.IDLE;
		}
		c.goals.instant = "at a party";
		if (party == null || now > party.until()) {
			doing = null;
			c.chatter(c.pick3("Good party!", "That was fun.", "Okay, party's over."), false);
			return null;
		}
		if (!p.blockPosition().closerThan(at, 4)) return c.walkTo(Vec3.atBottomCenterOf(at));
		c.acted = true;
		return c.antics.danceNow();
	}

	/** Its bag in order: alike things together, by name (slots 9 to 35; the hotbar stays as it is). */
	private void sortBag() {
		Inventory inv = c.player.getInventory();
		java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
		for (int i = 9; i < 36; i++) stacks.add(inv.getItem(i).copy());
		stacks.sort(java.util.Comparator.comparing((ItemStack s) -> s.isEmpty() ? "~" : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
		for (int i = 9; i < 36; i++) inv.setItem(i, stacks.get(i - 9));
	}

	/** A block it can see from here (one of the nicer ones), in words, or null. */
	private String favouriteBlock() {
		var level = c.player.level();
		BlockPos feet = c.player.blockPosition();
		java.util.List<String> seen = new java.util.ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-6, -2, -6), feet.offset(6, 3, 6))) {
			var s = level.getBlockState(q);
			if (s.isAir() || !s.getFluidState().isEmpty()) continue;
			String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
			if (n.contains("grass") || n.equals("dirt") || n.equals("stone") || n.equals("bedrock")) continue;
			seen.add(n.replace('_', ' '));
			if (seen.size() > 40) break;
		}
		return seen.isEmpty() ? null : seen.get(random.nextInt(seen.size())).toLowerCase(Locale.ROOT);
	}
}
