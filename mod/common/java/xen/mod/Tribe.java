package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A tribe: Xens that live together (the ones one player owns, a team, a boss and its minions, or free Xens that met
 * and got on well). A tribe grows a village: its first home is the middle, the others build theirs around it, and
 * it keeps shared chests there. Its Xens share: one that's hungry or has no iron gets some from one that has plenty
 * (or from the chests). It looks after its land: at night one of them keeps watch (and lights up dark spots, so
 * nothing spawns in the village), and a player who hurt one of them and comes back gets told to leave, and then
 * chased off. Whoever attacks one of them with a weapon has the whole tribe to deal with: the ones close by come and
 * fight together (with PvP on). A truce with one of them is a truce with all of them.
 */
final class Tribe {
	final String id;
	String name;
	final List<Companion> members = new ArrayList<>();
	BlockPos center;
	String dim = "overworld";
	final Set<BlockPos> storage = new LinkedHashSet<>();
	/** Players the tribe is fighting, until when (game time). */
	final Map<UUID, Long> enemies = new HashMap<>();
	private final Map<UUID, Long> warned = new HashMap<>();
	private final Random random = new Random();
	private int houses;
	/** Its economy: its money, prices, shops (see {@link Market}). */
	final Market market = new Market(this);
	private Companion watch;
	private long watchSince;

	Tribe(String id) {
		this.id = id;
	}

	/** Which tribe a Xen belongs to (by its owner, its team, its boss, or the band it joined). */
	static String key(Companion c) {
		if (c.minion && c.boss != null) return key(c.boss);
		if (c.owner != null) return "owner:" + c.owner;
		String team = c.mod.teamOf(c);
		if (team != null && c.mod.config.teams > 1) return "team:" + team;
		return c.band != null ? c.band : "solo:" + c.name;
	}

	int radius() {
		return 40 + 8 * Math.min(8, members.size());
	}

	boolean member(net.minecraft.world.entity.Entity e) {
		return e instanceof XenPlayer x && x.companion != null && members.contains(x.companion);
	}

	boolean inLand(BlockPos p) {
		return center != null && p.distSqr(center) < (double) radius() * radius();
	}

	/** Is this player someone the tribe is fighting right now? */
	boolean enemy(ServerPlayer p, long now) {
		Long until = enemies.get(p.getUUID());
		return until != null && until > now;
	}

	/** Someone attacked one of them with a weapon: the tribe stands together (unless it's a member, or a friend of the owner). */
	void alarm(Companion victim, ServerPlayer attacker) {
		if (member(attacker) || attacker.getUUID().equals(victim.owner) || !victim.mod.config.tribes || victim.mod.config.pvp.equals("off")) return;
		long now = victim.player.level().getGameTime();
		boolean fresh = !enemy(attacker, now);
		enemies.put(attacker.getUUID(), now + 20 * 60);
		if (!fresh || members.size() < 2) return;
		for (Companion m : members) {
			if (m == victim || m.player == null || m.player.level() != victim.player.level() || m.player.distanceTo(victim.player) > 64) continue;
			m.say(m.pick3("Leave " + victim.name + " alone!", "Nobody hurts " + victim.name + "! Get " + attacker.getName().getString() + "!",
					"You picked a fight with all of us, " + attacker.getName().getString() + "!"));
			m.journal("fight", "comes to help " + victim.name + " against " + attacker.getName().getString());
			break;                                                            // one of them says it; they all come
		}
	}

	/** A truce with one of them: with all of them. */
	void peace(UUID with) {
		enemies.remove(with);
		warned.remove(with);
	}

	/**
	 * Its tribe is fighting someone close by and it isn't: it goes to help (if it's brave enough, or it's their home
	 * that's attacked). Null if not.
	 */
	Action rally(Companion c) {
		if (enemies.isEmpty() || !c.mod.config.tribes || c.mod.config.pvp.equals("off")) return null;
		long now = c.player.level().getGameTime();
		ServerPlayer target = null;
		double best = 64;
		for (var e : enemies.entrySet()) {
			if (e.getValue() < now) continue;
			ServerPlayer p = c.server.getPlayerList().getPlayer(e.getKey());
			if (p == null || p.level() != c.player.level() || !p.isAlive() || p.isCreative() || p.isSpectator()) continue;
			if (e.getKey().equals(c.owner) || c.trust(e.getKey()) >= 0.6f) continue;   // (its owner, or its friend: not them)
			double d = p.distanceTo(c.player);
			if (d < best) {
				best = d;
				target = p;
			}
		}
		if (target == null) return null;
		boolean home = inLand(target.blockPosition());
		if (c.player.getHealth() < 8 || c.personality.bravery < 0.25f && !home) return null;
		c.goals.instant = "coming to help against " + target.getName().getString();
		return best > 3 ? c.walkTo(target.position()) : null;
	}

	/** A house spot in the village for the next one who needs a home. */
	BlockPos plot() {
		if (center == null) return null;
		double angle = houses++ * 2.4, r = 16 + 5 * (houses / 5);
		return center.offset((int) Math.round(Math.cos(angle) * r), 0, (int) Math.round(Math.sin(angle) * r));
	}

	// ------------------------------------------------------------------------------ all the tribes
	/** Every five seconds: who's in which tribe, the village's middle, sharing, the night watch, their land. */
	static void tickAll(XenMod mod) {
		Map<String, Tribe> tribes = mod.tribes;
		for (Tribe t : tribes.values()) t.members.clear();
		for (Companion c : mod.companions) {
			if (c.player == null || c.inArena) continue;
			tribes.computeIfAbsent(key(c), Tribe::new).members.add(c);
		}
		tribes.values().removeIf(t -> t.members.isEmpty() && t.enemies.isEmpty());
		if (mod.config.tribes) bands(mod);
		for (Tribe t : tribes.values()) {
			if (t.members.isEmpty()) continue;
			t.name = t.members.get(0).name + "'s tribe";
			if (t.center == null) {
				for (Companion m : t.members) {
					if (m.goals.home != null) {
						t.center = m.goals.home;
						t.dim = "overworld";
						break;
					}
				}
			}
			if (!mod.config.tribes || t.members.size() < 2) continue;
			t.teach();
			t.admire();
			t.share();
			t.market.tick(t.members.get(0).player.level().getGameTime());
			t.guard();
		}
	}

	/**
	 * A member finished a new house: the others close by go and say what they think of it (the way players look at a
	 * friend's build). Liking it rubs off on their own taste, so a village comes to share a style.
	 */
	private void admire() {
		for (Companion b : members) {
			Taste tb = b.taste;
			if (tb.last == null || tb.shown || tb.lastAt == null) continue;
			tb.shown = true;
			int said = 0;
			for (Companion m : members) {
				if (m == b || m.player == null || said >= 2 || m.player.blockPosition().distSqr(tb.lastAt) > 64 * 64) continue;
				String opinion = m.taste.opinionOf(b);
				if (opinion == null) continue;
				m.chatter(opinion, false);
				said++;
			}
		}
	}

	/**
	 * Free Xens (no owner, no team) that meet and like each other team up: the smaller band joins the bigger one. Two
	 * Xens within 12 blocks that trust each other enough, now and then (the chatty ones sooner).
	 */
	private static void bands(XenMod mod) {
		for (Companion a : mod.companions) {
			if (a.player == null || a.owner != null || a.minion || a.inArena || !key(a).startsWith("solo:") && !key(a).startsWith("band:")) continue;
			for (Companion b : mod.companions) {
				if (b == a || b.player == null || b.owner != null || b.minion || b.inArena || b.player.level() != a.player.level()) continue;
				if (key(a).equals(key(b)) || !key(b).startsWith("solo:") && !key(b).startsWith("band:")) continue;
				if (a.player.distanceTo(b.player) > 12 || a.trust(b.player.getUUID()) < 0.3f || b.trust(a.player.getUUID()) < 0.3f) continue;
				if (a.random().nextFloat() > 0.15f + 0.3f * a.personality.chattiness) continue;
				Tribe ta = mod.tribes.get(key(a)), tb = mod.tribes.get(key(b));
				int na = ta == null ? 1 : ta.members.size(), nb = tb == null ? 1 : tb.members.size();
				Companion big = na >= nb ? a : b, small = big == a ? b : a;
				String band = big.band != null ? big.band : "band:" + big.name;
				big.band = band;
				Tribe from = mod.tribes.get(key(small));
				for (Companion m : from == null ? List.of(small) : new ArrayList<>(from.members)) m.band = band;
				small.say(small.pick3("Hey " + big.name + ", let's stick together!", big.name + ", can I join your tribe?", "Team up, " + big.name + "?"));
				big.say(big.pick3("Welcome to the tribe, " + small.name + "!", "Sure! More hands, more houses.", "Deal. We're a tribe now."));
				a.trust(b.player.getUUID(), 0.1f);
				b.trust(a.player.getUUID(), 0.1f);
				XenMod.LOG.info("{} joined {} ({})", small.name, big.name, band);
				return;                                                         // one band a round
			}
		}
	}

	/** Now and then one of them shows another something it knows about the game (when they're close). */
	private void teach() {
		for (Companion a : members) {
			if (a.player == null) continue;
			for (Companion b : members) {
				if (b == a || b.player == null || b.player.level() != a.player.level() || a.player.distanceTo(b.player) > 24) continue;
				for (var e : a.knowledge.known.entrySet()) {
					if (e.getValue() == Knowledge.How.BORN || b.knowledge.knows(e.getKey()) || random.nextFloat() > 0.15f) continue;
					if (b.knowledge.learn(e.getKey(), Knowledge.How.TRIBE)) {
						b.journal("learns", "from " + a.name);
						return;                                                      // one lesson a round
					}
				}
			}
		}
	}

	/** Sharing: one who has plenty gives to one who has none (food; iron for tools), when they're close. */
	private void share() {
		for (Companion needy : members) {
			if (needy.player == null || needy.chores.busy() || needy.builder.busy()) continue;
			var need = needy.items();
			String want = null;
			int amount = 0;
			if (need.getOrDefault("food", 0) == 0 && needy.player.getFoodData().getFoodLevel() < 14) {
				want = "food";
				amount = 3;
			} else if (needy.crafter.pickTier() < 3 && need.getOrDefault("iron_ingot", 0) + need.getOrDefault("raw_iron", 0) < 3) {
				want = "iron_ingot";
				amount = 3;
			}
			if (want == null) continue;
			for (Companion rich : members) {
				if (rich == needy || rich.player == null || rich.player.level() != needy.player.level() || rich.player.distanceTo(needy.player) > 24) continue;
				if (rich.chores.busy() || rich.builder.busy() || rich.storage.busy()) continue;
				int has = rich.items().getOrDefault(want, 0);
				if (has < amount * 2 + (want.equals("iron_ingot") && rich.crafter.pickTier() < 3 ? 3 : 0)) continue;
				String plan = rich.chores.give(needy.player, want, amount);
				if (!plan.startsWith("You will")) continue;
				rich.chores.own = true;
				rich.chatter(want.equals("food") ? "You look hungry, " + needy.name + ". Here, have some food." : needy.name + ", take some iron for your tools.", true);
				rich.journal("does", "shares " + amount + " " + want + " with " + needy.name);
				return;                                                       // one gift a round
			}
		}
	}

	/**
	 * The night watch: one of them (the bravest who isn't busy) walks around the village at night, lighting dark
	 * spots and fighting what comes. And its land: a player who hurt one of them, back in the village, is told to leave,
	 * and chased off if they don't.
	 */
	private void guard() {
		if (center == null) return;
		Companion any = members.get(0);
		long now = any.player.level().getGameTime();
		boolean night = any.player.level().isDarkOutside();
		if (night && (watch == null || !members.contains(watch) || watch.player == null)) {
			watch = null;
			float bravest = -1;
			for (Companion m : members) {
				if (m.player == null || m.minion || m.chores.busy() || m.builder.busy() || m.mode != Companion.Mode.FREE) continue;
				if (m.player.blockPosition().distSqr(center) > 96 * 96 || m.personality.bravery <= bravest) continue;
				bravest = m.personality.bravery;
				watch = m;
			}
			if (watch != null) {
				watchSince = now;
				watch.chatter(watch.pick3("I'll keep watch tonight.", "My turn to guard the village.", "Sleep well, I'm on watch."), false);
			}
		}
		if (!night) watch = null;
		for (ServerPlayer p : any.server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer || p.isCreative() || p.isSpectator() || p.level() != any.player.level() || !inLand(p.blockPosition())) continue;
			float trust = 0;
			boolean owner = false;
			for (Companion m : members) {
				trust += m.trust(p.getUUID()) / members.size();
				owner |= p.getUUID().equals(m.owner);
			}
			if (owner || trust > -0.2f || enemy(p, now) || any.mod.config.pvp.equals("off")) continue;
			Long told = warned.get(p.getUUID());
			if (told == null || now - told > 20 * 120) {
				warned.put(p.getUUID(), now);
				Companion speaker = closest(p);
				if (speaker != null) speaker.say("You're not welcome here, " + p.getName().getString() + ". Leave our village!");
			} else if (now - told > 20 * 10) {                                // still here after ten seconds
				enemies.put(p.getUUID(), now + 20 * 60);
				Companion speaker = closest(p);
				if (speaker != null) speaker.say("We warned you! Everyone, get " + p.getName().getString() + "!");
			}
		}
	}

	private Companion closest(ServerPlayer p) {
		Companion best = null;
		for (Companion m : members) {
			if (m.player == null || m.player.level() != p.level()) continue;
			if (best == null || m.player.distanceTo(p) < best.player.distanceTo(p)) best = m;
		}
		return best;
	}

	/** On watch tonight: its next step (a walk to a spot of the village, a torch where it's dark). Null if it isn't. */
	Action watchStep(Companion c) {
		if (watch != c || center == null || c.player.level().getGameTime() - watchSince > 20 * 60 * 10) return null;
		if (c.player.getHealth() < 10) return null;
		c.goals.instant = "keeping watch over the village";
		if (patrol == null || c.player.position().distanceTo(patrol) < 3 || c.random().nextFloat() < 0.01f) {
			int r = radius() / 2;
			patrol = XenMod.surface((net.minecraft.server.level.ServerLevel) c.player.level(),
					center.getX() + random.nextInt(2 * r + 1) - r, center.getZ() + random.nextInt(2 * r + 1) - r);
		}
		if (patrol == null) return null;
		return c.walkTo(patrol);
	}

	private Vec3 patrol;

	String describe(Companion c) {
		if (members.size() < 2) return "";
		StringBuilder sb = new StringBuilder("You are in a tribe with ");
		int n = 0;
		for (Companion m : members) {
			if (m == c) continue;
			if (n++ >= 5) break;
			sb.append(n > 1 ? ", " : "").append(m.name);
		}
		sb.append('.');
		if (center != null) sb.append(" Your village is at ").append(center.getX()).append(' ').append(center.getZ()).append('.');
		if (!enemies.isEmpty()) sb.append(" Your tribe is fighting someone who attacked one of you.");
		String economy = market.describe();
		if (!economy.isEmpty()) sb.append(' ').append(economy);
		return sb.toString();
	}

	/** For /xen tribes. */
	String status() {
		StringBuilder sb = new StringBuilder(name == null ? id : name).append(": ");
		for (int i = 0; i < members.size(); i++) sb.append(i > 0 ? ", " : "").append(members.get(i).name);
		if (center != null) sb.append("; village at ").append(center.toShortString());
		if (!storage.isEmpty()) sb.append("; ").append(storage.size()).append(" shared chests");
		return sb.toString();
	}

	static Map<String, Tribe> newMap() {
		return new LinkedHashMap<>();
	}
}
