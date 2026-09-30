package xen.mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import xen.mod.core.Action;

import java.util.HashSet;
import java.util.Set;

/**
 * How quickly it gets to things, like a person: not a fixed wait but a moment to get ready that depends on how
 * familiar the thing is. A recipe it has made many times it makes almost at once; a new one takes a moment (the
 * recipe book, the grid); between steps a short pause; a new tool gets a look. When its tool breaks it notices, stops,
 * looks at its hands, says so, and then sees to a new one. Never under pressure (a fight, low health, in the water,
 * monsters close): then it just acts. All of it counts game ticks: nothing ever waits on a clock or sleeps.
 */
final class Pace {
	private final Companion c;
	/** Recipes it has made (familiar: quicker to make again). */
	final Set<String> made = new HashSet<>();
	/** The crafting step it's getting ready for, what for, and when it'll be ready; the thing it made last. */
	private String prepFor, prepGoal, lastGoal;
	private long readyAt;
	private boolean prepFresh;
	/** The tool in its hand last tick: its name, uses left, which slot. */
	private String heldName = "";
	private int heldLeft = Integer.MAX_VALUE, heldSlot = -1;
	/** Looking at its hands (a broken tool, a new one) until then, how far down, and why. */
	private long lookUntil;
	private float lookPitch;
	private String lookWhy = "";

	Pace(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	/** Under pressure: a fight, low health, in water or lava, falling, a monster close. No pauses then. */
	boolean pressed() {
		var p = c.player;
		if (c.fightingNow() || p.getHealth() < 8 || p.isInWater() || p.isInLava() || !p.onGround() && p.fallDistance > 2) return true;
		return !p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(8), m -> m.isAlive()).isEmpty();
	}

	/**
	 * Before a crafting step: null when it's ready to do it now, else IDLE while it gets ready. The first step of
	 * something it has made before: 4 to 8 ticks; something new: 14 to 26; each step after: 3 to 6.
	 */
	Action craftPrep(String step, String goal) {
		if (c.player == null) return null;
		long now = now();
		if (pressed()) {
			readyAt = now;
			prepFor = step;
			return null;
		}
		if (!step.equals(prepFor) || goal != null && !goal.equals(prepGoal)) {
			var random = c.random();
			boolean fresh = goal == null || !goal.equals(lastGoal);
			int wait = !fresh ? 3 + random.nextInt(4) : made.contains(goal) ? 4 + random.nextInt(5) : 14 + random.nextInt(13);
			prepFor = step;
			prepGoal = goal;
			lastGoal = goal;
			prepFresh = fresh;
			readyAt = now + wait;
			if (fresh && !made.contains(goal == null ? step : goal) && goal != null) c.journal("thinks", "never made " + goal.replace('_', ' ') + " before: works out the recipe");
		}
		if (now >= readyAt) return null;
		c.goals.instant = goal == null ? "getting ready to craft" : (prepFresh ? (made.contains(goal) ? "going to make " : "working out how to make ") : "making ")
				+ goal.replace('_', ' ');
		net.minecraft.core.BlockPos table = c.crafter.tableNear();
		if (table != null && step.equals(goal)) c.hands.holdLook(net.minecraft.world.phys.Vec3.atCenterOf(table), (int) (readyAt - now) + 2);   // (at the table: its eyes on it)
		else look(readyAt, 35, "");
		return Action.IDLE;
	}

	/** A crafting step done (or failed): the next one gets its own moment; a new tool gets a look. */
	void crafted(String step, String goal, boolean ok) {
		prepFor = null;
		if (!ok || goal == null || !step.equals(goal)) return;
		made.add(goal);
		lastGoal = null;                                                       // (the next thing it makes is a fresh start)
		if (isTool(goal) && c.player != null && !pressed()) look(now() + 6 + c.random().nextInt(5), 45, "looking over its new " + goal.replace('_', ' '));
	}

	private static boolean isTool(String n) {
		return n.endsWith("_pickaxe") || n.endsWith("_axe") || n.endsWith("_shovel") || n.endsWith("_sword") || n.endsWith("_hoe");
	}

	private void look(long until, float pitch, String why) {
		lookUntil = Math.max(lookUntil, until);
		lookPitch = pitch;
		if (!why.isEmpty()) lookWhy = why;
	}

	/** Each tick: did its tool just break in its hand? And its eyes on its hands while it looks at them. */
	void tick() {
		var p = c.player;
		if (p == null) return;
		ItemStack h = p.getMainHandItem();
		int slot = p.getInventory().getSelectedSlot();
		if (h.isEmpty() && slot == heldSlot && heldLeft <= 3 && isTool(heldName)) broke(heldName);
		heldName = h.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(h.getItem()).getPath();
		heldLeft = h.isDamageableItem() ? h.getMaxDamage() - h.getDamageValue() : Integer.MAX_VALUE;
		heldSlot = slot;
		if (now() < lookUntil) {
			if (pressed()) {
				lookUntil = 0;
				return;
			}
			p.setYHeadRot(p.getYRot());
			p.setXRot(p.getXRot() + (lookPitch - p.getXRot()) * 0.4f);
		}
	}

	/** Its tool broke: it notices, stops a moment, looks at its hands, says so; then it sees to a new one. */
	private void broke(String tool) {
		String what = tool.replace('_', ' ');
		String kind = what.substring(what.lastIndexOf(' ') + 1);
		if (pressed()) {
			c.journal("does", "its " + what + " broke (no time to stop)");
			return;
		}
		var random = c.random();
		look(now() + 8 + random.nextInt(9), 50, "looking at its broken " + kind);
		c.journal("does", "its " + what + " broke: stops, looks, thinks what now");
		if (random.nextFloat() < 0.75f) c.chatter(c.pick3("Ah, my " + kind + " broke.", "Aw, there goes my " + kind + ".", "Great. No more " + kind + "."), true);
	}

	/** While it's looking at its hands (a broken tool, a new one): it stands and looks; null otherwise. */
	Action next() {
		if (c.player == null || lookWhy.isEmpty()) return null;
		if (now() >= lookUntil) {
			lookWhy = "";
			return null;
		}
		if (pressed()) {
			lookUntil = 0;
			return null;
		}
		c.goals.instant = lookWhy;
		c.hands.start(Action.IDLE);
		c.acted = true;
		return Action.IDLE;
	}
}
