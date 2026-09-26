package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * The small things that make a Xen feel like a person rather than a machine:
 * <ul>
 *   <li><b>body language</b>: it nods when it agrees, shakes its head when it refuses, waves when it says hi;</li>
 *   <li><b>forgiveness</b>: grudges fade day by day (fast for a kind Xen, slowly for an aggressive one);</li>
 *   <li><b>gifts</b>: now and then a kind Xen gives a close friend something it has plenty of;</li>
 *   <li><b>a dog</b>: with bones and a wolf around, it tames one and names it;</li>
 *   <li><b>a hobby</b> for its free time: picking flowers, watching the stars at night, or the sunset;</li>
 *   <li><b>milestones</b>: it notices how many days it has lived in this world.</li>
 * </ul>
 */
final class Life {
	static final String[] HOBBIES = {"flowers", "stars", "sunsets", "dogs"};
	private static final String[] DOG_NAMES = {"Biscuit", "Rex", "Luna", "Pepper", "Max", "Coco", "Buddy", "Mochi", "Shadow", "Nugget", "Bean", "Scout"};

	private final Companion c;
	private final Random random = new Random();
	/** Its hobby (from HOBBIES), the day it came to this world, and its dogs (by name). */
	String hobby;
	long bornDay = -1;
	final List<String> dogs = new ArrayList<>();
	private UUID taming;
	private long lastDay = -1, nextGift, stargazedDay = -1, sunsetDay = -1, hobbyUntil, nextFlower;
	private int gestureTicks, picked;
	private char gesture;
	private Vec3 hobbyLook;

	Life(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** A new Xen: its hobby (from its nature) and its first day. */
	void born() {
		var p = c.personality;
		hobby = p.kindness > 0.6f && random.nextBoolean() ? "dogs" : p.curiosity > 0.6f && random.nextBoolean() ? "stars" : HOBBIES[random.nextInt(HOBBIES.length)];
	}

	// ------------------------------------------------------------------ body language
	/** What it just said: yes, no or hello shows on its face too. */
	void said(String text) {
		if (c.player == null || c.fightingNow() || c.inArena) return;
		String t = text.toLowerCase(Locale.ROOT);
		if (t.matches("^(okay|ok|sure|yes|yeah|deal|alright|of course|you're on|fine|count me in|got it).*")) start('n');
		else if (t.matches("^(no|nope|why would i|i can't|i won't|not a chance|never|no thanks|i don't).*")) start('s');
		else if (t.matches("^(hi|hey|hello|hiii|yo|oh, hi|good morning)\\b.*")) start('w');
	}

	private void start(char g) {
		gesture = g;
		gestureTicks = g == 'w' ? 16 : 12;
	}

	/** Every tick: the gesture going on (after its hands and eyes had their say). */
	void tick() {
		if (c.player == null) return;
		if (gestureTicks > 0) {
			gestureTicks--;
			var p = c.player;
			double phase = Math.sin(gestureTicks * Math.PI / 3);
			switch (gesture) {
				case 'n' -> p.setXRot((float) (10 + 18 * phase));                              // a nod
				case 's' -> p.setYHeadRot(p.getYRot() + (float) (28 * phase));                  // a shake of the head
				default -> {
					if (gestureTicks % 5 == 0) Compat.swing(p);                                  // a wave
				}
			}
		}
		long day = c.player.level().getGameTime() / 24000;
		if (lastDay >= 0 && day != lastDay) newDay(day);
		lastDay = day;
	}

	// ------------------------------------------------------------------ days
	private void newDay(long day) {
		if (bornDay < 0) bornDay = day;
		picked = 0;
		var p = c.personality;
		float fade = p.aggressive() ? 0.02f : 0.04f + 0.1f * p.kindness;        // forgiveness: grudges fade with time
		for (var e : new ArrayList<>(c.trust.entrySet())) {
			if (e.getValue() < 0) c.trust.put(e.getKey(), Math.min(0f, e.getValue() + fade));
		}
		long lived = day - bornDay;
		if (lived > 0 && lived % 10 == 0) {
			c.chatter(c.pick3("It's been " + lived + " days since I came to this world. Time flies!", lived + " days here already. What a life.",
					"Day " + lived + ". I remember when I only had a wooden pickaxe."), true);
		}
	}

	// ------------------------------------------------------------------ gifts
	/** Now and then a kind Xen gives a close friend nearby something it has plenty of. The plan, or null. */
	String gift() {
		if (c.player == null || now() < nextGift || c.personality.kindness < 0.5f || c.chores.busy() || c.builder.busy() || c.fightingNow()) return null;
		nextGift = now() + 20 * 60 * (8 + random.nextInt(8));
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p == c.player || p.level() != c.player.level() || p.distanceTo(c.player) > 8 || c.trust(p.getUUID()) < 0.7f) continue;
			var items = c.items();
			String thing = items.getOrDefault("food", 0) >= 10 ? "food" : items.getOrDefault("log", 0) >= 32 ? "log"
					: items.getOrDefault("iron_ingot", 0) >= 10 ? "iron_ingot" : items.getOrDefault("diamond", 0) >= 4 ? "diamond" : null;
			if (thing == null) return null;
			int n = thing.equals("diamond") ? 1 : thing.equals("iron_ingot") ? 3 : 4;
			c.say(c.pick3("Here, " + p.getName().getString() + ", a little something for you.", "I have plenty. Take some, " + p.getName().getString() + "!",
					"For you, " + p.getName().getString() + ". Don't mention it."));
			c.journal("does", "gives " + p.getName().getString() + " a gift: " + n + " " + thing);
			return c.chores.give(p, thing, n);
		}
		return null;
	}

	// ------------------------------------------------------------------ free time
	/** What it does in a free moment, by its hobby (or taming a dog). Null: nothing now. */
	Action idle() {
		var p = c.player;
		if (p == null || c.inArena || c.fightingNow() || c.emotions.fear > 0.4f || p.isInWater()) return null;
		if (now() < hobbyUntil && hobbyLook != null) {
			c.hands.glance = hobbyLook;
			return Action.IDLE;
		}
		Action dog = tameDog();
		if (dog != null) return dog;
		var level = p.level();
		long time = Compat.timeOfDay(level), day = level.getGameTime() / 24000;
		boolean sky = level.canSeeSky(p.blockPosition().above());
		if ("stars".equals(hobby) && sky && time > 13500 && time < 22000 && stargazedDay != day && !c.dangerous()) {
			stargazedDay = day;
			hobbyUntil = now() + 200;
			hobbyLook = p.getEyePosition().add(random.nextGaussian() * 3, 12, random.nextGaussian() * 3);
			c.chatter(c.pick3("The stars are beautiful tonight.", "Look at all those stars...", "I love the night sky."), false);
			c.journal("does", "watches the stars");
			return Action.IDLE;
		}
		if ("sunsets".equals(hobby) && sky && time > 11600 && time < 12600 && sunsetDay != day) {
			sunsetDay = day;
			hobbyUntil = now() + 160;
			hobbyLook = p.getEyePosition().add(-40, 3, 0);                    // (the sun sets in the west: toward -x)
			c.chatter(c.pick3("What a sunset.", "Look at that sky. Beautiful.", "I never get tired of sunsets."), false);
			c.journal("does", "watches the sunset");
			return Action.IDLE;
		}
		if ("flowers".equals(hobby) && time < 12000 && now() >= nextFlower && picked < 12) {
			BlockPos f = flowerNear();
			if (f == null) {
				nextFlower = now() + 400;
				return null;
			}
			if (p.getEyePosition().distanceTo(Vec3.atCenterOf(f)) > p.blockInteractionRange() - 0.5) return c.walkTo(Vec3.atBottomCenterOf(f));
			if (c.hands.mine(f)) {                                                 // picked (it breaks at once, drops, and it picks it up)
				nextFlower = now() + 60 + random.nextInt(200);
				picked++;
				if (random.nextFloat() < 0.3f) c.chatter(c.pick3("Pretty!", "This one's nice.", "For my collection."), false);
				c.acted = true;
				return Action.MINE;
			}
		}
		return null;
	}

	private BlockPos flowerNear() {
		BlockPos at = c.player.blockPosition();
		BlockPos best = null;
		double bestD = 1e9;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 2, 8))) {
			if (!(c.player.level().getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.FlowerBlock)) continue;
			if (c.builder.planned(q)) continue;                                    // (not the flower beds of a house)
			double d = q.distSqr(at);
			if (d < bestD && visible(q)) {
				bestD = d;
				best = q.immutable();
			}
		}
		return best;
	}

	/** Can it see that block from where it stands (nothing solid in between)? */
	private boolean visible(BlockPos q) {
		var hit = c.player.level().clip(new net.minecraft.world.level.ClipContext(c.player.getEyePosition(), Vec3.atCenterOf(q),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(q);
	}

	/** With a bone and a wild wolf close by, it tames it (a bone at a time, like a player: a 1 in 3 chance each). */
	private Action tameDog() {
		var p = c.player;
		if (dogs.size() >= 2 && !"dogs".equals(hobby) || dogs.size() >= 4) return null;
		int bone = c.hands.hotbar(st -> st.getItem() == Items.BONE);
		if (bone < 0) return null;
		Wolf wolf = null;
		for (Wolf w : p.level().getEntitiesOfClass(Wolf.class, p.getBoundingBox().inflate(12), w -> w.isAlive() && !w.isTame() && !w.isAngry())) {
			if (taming != null && w.getUUID().equals(taming) || wolf == null || w.distanceTo(p) < wolf.distanceTo(p)) wolf = w;
		}
		if (wolf == null || !p.hasLineOfSight(wolf)) return null;
		taming = wolf.getUUID();
		c.goals.instant = "taming a wolf";
		if (p.distanceTo(wolf) > 2.5) return c.walkTo(wolf.position());
		c.hands.face(wolf.getEyePosition());
		p.getInventory().setSelectedSlot(bone);
		Compat.interact(p, wolf);
		Compat.swing(p);
		c.acted = true;
		if (wolf.isTame()) {
			taming = null;
			String name = DOG_NAMES[random.nextInt(DOG_NAMES.length)];
			dogs.add(name);
			c.say(c.pick3("Good dog! I'll call you " + name + ".", "You're coming with me, " + name + "!", "Hey " + name + ", we're friends now."));
			c.journal("does", "tamed a wolf and named it " + name);
			XenMod.LOG.info("{} tamed a wolf ({})", c.name, name);
		}
		return Action.IDLE;
	}

	/** For its notes. */
	String describe() {
		StringBuilder sb = new StringBuilder();
		if (hobby != null) sb.append("Your hobby: ").append(switch (hobby) {
			case "flowers" -> "picking flowers";
			case "stars" -> "watching the stars";
			case "sunsets" -> "watching sunsets";
			default -> "dogs";
		}).append(". ");
		if (!dogs.isEmpty()) sb.append("Your dog").append(dogs.size() > 1 ? "s: " : ": ").append(String.join(", ", dogs)).append(". ");
		if (bornDay >= 0 && lastDay >= bornDay) sb.append("You have lived here ").append(lastDay - bornDay).append(" days.");
		return sb.toString().trim();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		if (hobby != null) o.addProperty("hobby", hobby);
		o.addProperty("bornDay", bornDay);
		JsonArray d = new JsonArray();
		for (String n : dogs) d.add(n);
		o.add("dogs", d);
		return o;
	}

	void load(JsonObject o) {
		if (o.has("hobby")) hobby = o.get("hobby").getAsString();
		if (o.has("bornDay")) bornDay = o.get("bornDay").getAsLong();
		if (o.has("dogs")) for (var e : o.getAsJsonArray("dogs")) dogs.add(e.getAsString());
	}
}
