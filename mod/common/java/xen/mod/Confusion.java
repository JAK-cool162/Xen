package xen.mod;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import xen.mod.core.Action;

import java.util.Random;

/**
 * Xen 2.0: confusion, the way a player gets confused. One level, 0 to 1, that goes up with close calls (its brains
 * can't settle a choice), the dark, a crowd of monsters, a hit from out of view, a new place, coming back from death,
 * and settles again as things calm down (about a minute from very confused to clear). A little: a pause, a look
 * around, a second look. More: it hesitates, and now and then goes with the other answer to a choice (never against a
 * hard no). A lot: a short question out loud ("wait, which way was it?"). It never slows its survival plan more than a
 * reaction time ({@link Reflexes}): confused is human, not helpless. The {@code confusion} setting: off, low, human,
 * high.
 */
final class Confusion {
	private final Companion c;
	private final Random random = new Random();
	float level;
	private long nextLook, nextQuestion, lookUntil;
	private float lookYaw, lookPitch;
	private String lastKey = "";
	int hesitations, flipsDone, questions;

	Confusion(Companion c) {
		this.c = c;
	}

	static float scale(String setting) {
		return switch (setting == null ? "" : setting) {
			case "off" -> 0f;
			case "low" -> 0.5f;
			case "high" -> 1.5f;
			default -> 1f;
		};
	}

	private float scale() {
		return scale(c.mod.config.confusion);
	}

	void add(float amount) {
		float head = 1.5f - c.personality.hidden(Personality.COMPOSURE);          // (a cool head: half as much; easily rattled: half again)
		level = Math.max(0f, Math.min(1f, level + amount * scale() * head));
	}

	/** Its brains couldn't settle a choice (the same one twice in a row counts once). */
	void closeCall(String key) {
		if (key.equals(lastKey)) return;
		lastKey = key;
		add(0.05f);
	}

	/** Something it didn't see coming (a hit from behind, coming back from death, a new dimension). */
	void surprise(float amount) {
		add(amount);
	}

	/** Once a second: what's around it adds or settles. */
	void second() {
		var p = c.player;
		if (p == null) return;
		float settle = c.fightingNow() ? 0.01f : 0.02f;
		int light = p.level().getMaxLocalRawBrightness(p.blockPosition());
		int mobs = p.level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(12), LivingEntity::isAlive).size();
		float dark = 1.3f - 0.6f * c.personality.hidden(Personality.NIGHT_OWL);     // (a night owl minds the dark less)
		float push = (light <= 3 ? 0.012f * dark : 0) + (mobs >= 3 ? 0.02f : 0) + (p.getHealth() <= 6 ? 0.015f : 0);
		level = Math.max(0f, Math.min(1f, level - settle + push * scale() * (1.5f - c.personality.hidden(Personality.COMPOSURE))));
	}

	/** Goes with the other answer this time? (Only when confused: up to one choice in five, very confused.) */
	boolean flips() {
		float swayed = 1.2f - 0.8f * c.personality.hidden(Personality.STUBBORN);    // (stubborn: it sticks to its answer)
		if (level < 0.3f || random.nextFloat() >= (level - 0.2f) * 0.25f * swayed) return false;
		flipsDone++;
		return true;
	}

	/**
	 * Its confusion showing, between the things it does: a look around, a moment's stillness, a short question. Null
	 * most of the time. Never in a fight, never while it's following someone it can't lose.
	 */
	Action step() {
		var p = c.player;
		if (p == null || level < 0.35f || c.fightingNow() || c.inArena) return null;
		long now = p.level().getGameTime();
		if (now < lookUntil) {                                        // still looking about
			p.setYRot(p.getYRot() + net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(lookYaw - p.getYRot()), -14f, 14f));
			p.setYHeadRot(p.getYRot());
			p.setXRot(p.getXRot() + net.minecraft.util.Mth.clamp(lookPitch - p.getXRot(), -8f, 8f));
			c.goals.instant = "looking around, not sure";
			c.acted = true;
			return Action.IDLE;
		}
		if (now < nextLook) return null;
		nextLook = now + 20L * (6 + random.nextInt(10)) - (long) (level * 80);
		if (random.nextFloat() > level * (1.3f - c.belief.get())) return null;   // (sure of itself, it hesitates less)
		hesitations++;
		lookUntil = now + 10 + random.nextInt(10 + (int) (level * 30));     // half a second to two
		lookYaw = p.getYRot() + (random.nextBoolean() ? 1 : -1) * (40 + random.nextInt(100));
		lookPitch = -10 + random.nextInt(35);
		if (level > 0.6f && now >= nextQuestion && c.mod.config.talk) {
			nextQuestion = now + 20 * 120;
			questions++;
			c.chatter(question(), false);
		}
		c.journal("thinks", String.format(java.util.Locale.ROOT, "confused (%.0f%%): a look around", level * 100));
		return Action.IDLE;
	}

	private String question() {
		var p = c.player;
		if (p.getHealth() <= 6) return c.pick3("Wait, what do I do now?", "Okay, okay... think.", "Hold on, hold on.");
		if (p.level().getMaxLocalRawBrightness(p.blockPosition()) <= 3) return c.pick3("Which way was it again?", "Hmm, I can't see a thing.", "Where am I?");
		return c.pick3("Hmm, what was I doing?", "Wait... which way?", "Uh, one sec.");
	}

	String says() {
		return level < 0.15f ? "clear-headed" : level < 0.4f ? "a bit unsure" : level < 0.7f ? "confused" : "very confused";
	}
}
