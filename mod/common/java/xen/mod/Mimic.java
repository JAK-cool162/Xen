package xen.mod;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Learning by watching. A Xen watches the players it can see (within 24 blocks, in its view) and notices when a move
 * works out for them. Then it takes the move up: clumsy at first, better each time it sees it done well, and each time
 * it pulls it off itself. What it can pick up:
 * <ul>
 *   <li><b>The water clutch</b> ("MLG"): someone falls from high up, empties a water bucket just before landing, and
 *   isn't hurt. Then, when it falls with a water bucket, it looks down and uses it before it hits the ground, and
 *   scoops the water back up. How late it starts clicking depends on its skill, so at first it's often too late.</li>
 *   <li><b>A fighting style</b>: when someone wins a fight, how they fought (crits on the way down, full-charge swings,
 *   S-taps, jump resets, spacing, the shield) pulls its fight genes their way, more for a clean win.</li>
 * </ul>
 * It only learns from what it could see a player do (their moves, what they hold, a mob flinching), never from
 * anything hidden.
 */
final class Mimic {
	static final String CLUTCH = "water clutch";
	/** -Dxen.debugMimic=true logs what it sees players do (for troubleshooting). */
	static final boolean DEBUG = Boolean.getBoolean("xen.debugMimic");

	private final Companion c;
	private final Random random = new Random();
	/** What it learned by watching, 0 to 1 ("water clutch", "fighting like Steve"). */
	final Map<String, Float> skill = new LinkedHashMap<>();
	private final Map<UUID, Watch> watched = new HashMap<>();

	/** What it has seen one player do lately. */
	private static final class Watch {
		double peakY = Double.NaN;
		boolean hadWater, emptied;
		int lastHurt, prevHurt;
		// a fight
		LivingEntity foe;
		int hits, crits, charged, backSteps, hurts, jumpResets, blocking, ticks, lastHitTick = -100, backUntil = -1, hurtAt = -100;
		double reach;
		boolean swinging;
	}

	Mimic(Companion c) {
		this.c = c;
	}

	float skill(String name) {
		return skill.getOrDefault(name, 0f);
	}

	private void improve(String name, float by) {
		float s = skill(name);
		skill.put(name, s + by * (1 - s));
	}

	// ------------------------------------------------------------------------------ watching
	/** Every tick: what are the players it can see doing? */
	void watch() {
		if (!c.mod.config.copy || c.player == null || c.inArena) return;
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer || p.level() != c.player.level() || !p.isAlive() || p.isSpectator()) {
				watched.remove(p.getUUID());
				continue;
			}
			if (p.distanceTo(c.player) > 24) {
				watched.remove(p.getUUID());
				continue;
			}
			Watch w = watched.computeIfAbsent(p.getUUID(), u -> new Watch());
			boolean sees = p.distanceTo(c.player) <= 6 || c.watchingYou(p) || WorldSenses.sees(c.player, c.hands.yaw, c.hands.pitch, p);
			watchFall(p, w, sees);
			watchFight(p, w, sees);
		}
	}

	private static String held(Player p) {
		return BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
	}

	/** A fall: from how high, whether a water bucket got emptied on the way down, and whether it hurt. */
	private void watchFall(ServerPlayer p, Watch w, boolean sees) {
		boolean down = p.onGround() || p.isInWater();
		if (!down) {
			if (Double.isNaN(w.peakY) || p.getY() > w.peakY) w.peakY = p.getY();
			if (held(p).equals("water_bucket")) w.hadWater = true;
			else if (w.hadWater && held(p).equals("bucket")) w.emptied = true;   // it was just used
			w.lastHurt = p.hurtTime;
			return;
		}
		if (Double.isNaN(w.peakY)) return;
		double drop = w.peakY - p.getY();
		boolean hurt = p.hurtTime > w.lastHurt;
		if (w.hadWater && held(p).equals("bucket")) w.emptied = true;
		if (DEBUG && drop >= 4) XenMod.LOG.info("[xen mimic] {} saw {} fall {} blocks: sees {} water {} emptied {} in water {} hurt {}", c.name,
				p.getName().getString(), Math.round(drop), sees, w.hadWater, w.emptied, p.isInWater(), hurt);
		if (sees && drop >= 8 && w.emptied && p.isInWater() && !hurt) {
			boolean first = skill(CLUTCH) == 0;
			improve(CLUTCH, 0.35f);
			c.chatter(first ? "Whoa, " + p.getName().getString() + ", a water clutch! I want to learn that."
					: String.format(Locale.ROOT, "Nice clutch! I'm getting it (%.0f%%).", 100 * skill(CLUTCH)), true);
			XenMod.LOG.info("{} saw {} do a water clutch from {} blocks: its skill is now {}", c.name, p.getName().getString(),
					Math.round(drop), String.format(Locale.ROOT, "%.2f", skill(CLUTCH)));
		}
		w.peakY = Double.NaN;
		w.hadWater = w.emptied = false;
	}

	/** A fight: every hit, how it was made, and how the fight ends. */
	private void watchFight(ServerPlayer p, Watch w, boolean sees) {
		int now = p.tickCount;
		LivingEntity hit = p.getLastHurtMob();
		boolean real = hit != null && (hit instanceof Enemy || hit instanceof Player) && hit != c.player;
		boolean swings = Compat.swinging(p);
		boolean newSwing = swings && !w.swinging;
		w.swinging = swings;
		if (real && newSwing && now - p.getLastHurtMobTimestamp() <= 2 && sees) {   // a hit, and it saw it
			if (w.foe != hit) reset(w);
			w.foe = hit;
			w.hits++;
			if (!p.onGround() && p.getDeltaMovement().y < 0) w.crits++;              // on the way down: a crit
			if (now - w.lastHitTick >= 11) w.charged++;                              // waited for a full swing
			w.reach += p.distanceTo(hit);
			w.lastHitTick = now;
			w.backUntil = now + 8;
		}
		if (DEBUG && real && newSwing) XenMod.LOG.info("[xen mimic] {} saw {} swing at {}: hit {} sees {} hits {}", c.name, p.getName().getString(),
				hit.getName().getString(), now - p.getLastHurtMobTimestamp(), sees, w.hits);
		if (w.foe == null) return;
		w.ticks++;
		Vec3 toFoe = w.foe.position().subtract(p.position()).multiply(1, 0, 1);
		if (now <= w.backUntil && toFoe.lengthSqr() > 1e-4 && p.getDeltaMovement().dot(toFoe.normalize()) < -0.05) {
			w.backSteps++;                                                           // stepped back after the hit (S-tap)
			w.backUntil = -1;
		}
		boolean newHurt = p.hurtTime > w.prevHurt;
		w.prevHurt = p.hurtTime;
		if (newHurt) {                                                               // it just took a hit
			w.hurts++;
			w.hurtAt = now;
		}
		if (now - w.hurtAt <= 3 && p.getDeltaMovement().y > 0.3 && w.hurtAt > 0) {  // jumped into the hit
			w.jumpResets++;
			w.hurtAt = -100;
		}
		if (p.isBlocking()) w.blocking++;
		if (!w.foe.isAlive()) {                                                      // they won
			if (DEBUG) XenMod.LOG.info("[xen mimic] {} saw {}'s foe die after {} hits", c.name, p.getName().getString(), w.hits);
			if (w.hits >= 2) learnFight(p, w);
			reset(w);
		} else if (!p.isAlive() || now - w.lastHitTick > 200 || w.foe.level() != p.level()) {
			reset(w);                                                                // lost, or it just stopped
		}
	}

	private static void reset(Watch w) {
		w.foe = null;
		w.hits = w.crits = w.charged = w.backSteps = w.hurts = w.jumpResets = w.blocking = w.ticks = 0;
		w.reach = 0;
		w.lastHitTick = -100;
	}

	/** Someone won a fight it watched: fight a bit more like them (a clean win counts more). */
	private void learnFight(ServerPlayer p, Watch w) {
		float[] genes = c.personality.fightGenes;
		float rate = (w.hurts <= w.hits / 2 ? 0.35f : 0.15f) * Math.min(1f, w.hits / 4f);   // a clean, long fight counts most
		float[] seen = genes.clone();
		seen[0] = w.crits / (float) w.hits;
		seen[1] = w.charged / (float) w.hits;
		seen[2] = Math.max(0f, Math.min(1f, (float) ((w.reach / w.hits - 2.2) / 0.8)));
		seen[3] = Math.min(1f, w.backSteps / (float) w.hits);
		if (w.hurts > 0) seen[4] = Math.min(1f, w.jumpResets / (float) w.hurts);
		seen[9] = Math.min(1f, 2f * w.blocking / Math.max(1, w.ticks));
		int most = 0;
		float change = 0;
		for (int i = 0; i < genes.length; i++) {
			float d = rate * (seen[i] - genes[i]);
			genes[i] = Math.max(0f, Math.min(1f, genes[i] + d));
			if (Math.abs(d) > Math.abs(change)) {
				change = d;
				most = i;
			}
		}
		c.personality.fight = Personality.nearest(genes);
		String who = p.getName().getString();
		improve("fighting like " + who, 0.25f);
		String what = switch (most) {
			case 0 -> change > 0 ? "crit more, on the way down" : "crit less and just hit";
			case 1 -> change > 0 ? "wait for full swings" : "swing faster";
			case 2 -> change > 0 ? "keep my distance" : "fight up close";
			case 3 -> change > 0 ? "step back after hits" : "stay on them after hits";
			case 4 -> change > 0 ? "jump into hits" : "stop jumping into hits";
			case 9 -> change > 0 ? "use my shield" : "put my shield away";
			default -> "fight like you";
		};
		if (Math.abs(change) >= 0.03f) c.chatter("Nice fight, " + who + "! I'll " + what + ", like you.", true);
		XenMod.LOG.info("{} watched {} win a fight ({} hits, {} crits, {} hurt): its style is now {}", c.name, who, w.hits, w.crits,
				w.hurts, c.personality.fight);
	}

	// ------------------------------------------------------------------------------ doing it
	private double aimAt = Double.NaN;
	/** This fall: does it see the ground coming (and click in time), or only react when it's close? */
	private boolean anticipate;
	private BlockPos placed;
	private int landedTicks;
	private boolean tried;

	/**
	 * Every tick while it falls: the water clutch, if it has learned it and carries a water bucket. True when it used
	 * its hands this tick.
	 */
	boolean clutchTick() {
		var p = c.player;
		if (p == null) return false;
		ServerLevel level = (ServerLevel) p.level();
		if (placed != null) {                                                    // landed: scoop the water back up
			if (!p.onGround() && !p.isInWater()) return false;
			if (++landedTicks < 4) return false;
			boolean ok = level.getFluidState(placed).isSource();
			if (ok && pickUp(level)) {
				boolean hurt = p.hurtTime > 0;
				c.chatter(hurt ? "Ouch... almost." : "Yes! I did the water clutch!", true);
				if (!hurt) {
					improve(CLUTCH, 0.1f);                                       // it gets better by doing it
					c.skills.practice(Skills.MOVE, 0.05f);
					c.antics.celebrate();
				}
			}
			placed = null;
			return true;
		}
		float s = Math.max(c.mod.config.copy ? skill(CLUTCH) : 0, c.skills.get(Skills.MOVE) - 0.35f);   // (an agile Xen gets the idea by itself)
		boolean falling = !p.onGround() && !p.isInWater() && p.getDeltaMovement().y < -0.4 && p.fallDistance > 4;
		if (!falling || s <= 0) {
			if (p.onGround() || p.isInWater()) {
				if (tried && p.hurtTime > 0) c.chatter("Too late! I'll get it next time.", false);
				aimAt = Double.NaN;
				tried = false;
			}
			return false;
		}
		if (tried) return false;                                                 // one go per fall
		int slot = c.hands.hotbar(st -> BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().equals("water_bucket"));
		if (slot < 0) return false;
		double ground = groundBelow(level, p);
		if (Double.isNaN(ground)) return false;
		double h = p.getY() - ground, speed = -p.getDeltaMovement().y;
		if (p.fallDistance + h < 5) return false;                               // not a fall that hurts
		if (Double.isNaN(aimAt)) {                                               // clumsy at first: it often clicks too late
			aimAt = 2.7 - (1 - s) * random.nextDouble() * 5;
			anticipate = random.nextFloat() < s;
			if (random.nextFloat() < 0.3f * (1 - s)) {                           // or fumbles it altogether
				tried = true;
				return false;
			}
		}
		if (h > aimAt && !(anticipate && h - speed < 0.4)) return false;         // not yet
		if (h > 2.9) return false;                                               // out of reach: next tick
		tried = true;
		c.hands.stop();
		p.getInventory().setSelectedSlot(slot);
		p.setXRot(90f);
		c.hands.pitch = -1;
		ItemStack bucket = p.getInventory().getSelectedItem();
		p.gameMode.useItem(p, level, bucket, InteractionHand.MAIN_HAND);
		Compat.swing(p);
		c.acted = true;
		BlockPos at = BlockPos.containing(p.getX(), ground, p.getZ());
		if (level.getFluidState(at).isSource()) {
			placed = at;
			landedTicks = 0;
		}
		c.goals.instant = "doing a water clutch";
		return true;
	}

	/** The top of the first solid block under it (within 40 blocks), or NaN. */
	private static double groundBelow(ServerLevel level, Player p) {
		BlockPos feet = p.blockPosition();
		for (int dy = 0; dy <= 40; dy++) {
			BlockPos b = feet.below(dy);
			var state = level.getBlockState(b);
			var shape = state.getCollisionShape(level, b);
			if (!shape.isEmpty()) return b.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
			if (!state.getFluidState().isEmpty()) return Double.NaN;             // water below anyway
		}
		return Double.NaN;
	}

	/** Look at the water and use the empty bucket on it, like a player. */
	private boolean pickUp(ServerLevel level) {
		var p = c.player;
		int slot = c.hands.hotbar(st -> BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().equals("bucket"));
		if (slot < 0) return false;
		p.getInventory().setSelectedSlot(slot);
		c.hands.face(Vec3.atCenterOf(placed));
		p.gameMode.useItem(p, level, p.getInventory().getSelectedItem(), InteractionHand.MAIN_HAND);
		Compat.swing(p);
		c.acted = true;
		return !level.getFluidState(placed).isSource();
	}

	// -------------------------------------------------------------------------------- words
	String describe() {
		if (skill.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("You learned by watching: ");
		boolean first = true;
		for (var e : skill.entrySet()) {
			sb.append(first ? "" : ", ").append(e.getKey()).append(String.format(Locale.ROOT, " (%.0f%%)", 100 * e.getValue()));
			first = false;
		}
		return sb.append('.').toString();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		skill.forEach(o::addProperty);
		return o;
	}

	void load(JsonObject o) {
		if (o == null) return;
		for (var e : o.entrySet()) skill.put(e.getKey(), e.getValue().getAsFloat());
	}
}
