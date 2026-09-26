package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Locale;

/**
 * A highway, the way players build them (the Nether ones out from a portal, or a road between villages): three wide,
 * straight, a floor of obsidian if it has plenty (nothing blows it up), otherwise the block it has most of, three high,
 * torches along a tunnel. It builds it a stretch at a time and goes on from where it got to (it keeps what it
 * mines, so a Nether tunnel pays for its own floor in netherrack).
 */
final class Highway {
	private final Companion c;
	BlockPos next;
	Direction dir;
	int built, goal;
	String floor;

	Highway(Companion c) {
		this.c = c;
	}

	boolean on() {
		return goal > 0 && built < goal;
	}

	private static final String[] FLOORS = {"obsidian", "cobblestone", "cobbled_deepslate", "blackstone", "netherrack", "stone_bricks", "stone",
			"deepslate_bricks", "polished_blackstone_bricks"};

	/** The floor: obsidian if it has enough, else what it has most of (asked for one: that). */
	private String pick(String asked, int len) {
		var items = c.items();
		if (asked != null) return asked;
		if (items.getOrDefault("obsidian", 0) >= 3 * len) return "obsidian";
		String best = c.nether.inNether() ? "netherrack" : "cobblestone";
		int most = -1;
		for (String f : FLOORS) {
			int n = MindSense.count(c, x -> x.equals(f));
			if (n > most) {
				most = n;
				best = f;
			}
		}
		return best;
	}

	/** Where the highway runs: in the Nether 5 blocks under the roof; elsewhere underground, below what's built (up top it's in the way). */
	int floorY;

	String start(Direction way, int length, String material) {
		dir = way == null ? c.player.getDirection() : way;
		goal = Math.max(24, Math.min(1000, length));
		built = 0;
		next = c.player.blockPosition();
		floor = pick(material, 24);
		var level = c.player.level();
		if (c.nether.inNether()) floorY = 118;
		else if (level.dimension() == net.minecraft.world.level.Level.END) floorY = next.getY() - 1;
		else {
			int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, next.getX(), next.getZ());
			floorY = Math.max(level.getMinY() + 6, Math.min(next.getY() - 1, top - 1) - 12);
		}
		goal += Math.abs(floorY - (next.getY() - 1));                         // (the staircase to its level counts too)
		c.journal("build", "starts a highway " + dir.getName() + ", " + goal + " blocks, of " + floor + ", at y " + (floorY + 1));
		return stretch();
	}

	private String stretch() {
		int len = Math.min(24, goal - built);
		floor = pick(null, len).equals("obsidian") && floor.equals("obsidian") ? "obsidian" : floor;
		String plan = c.builder.startPlan(Architect.highway(next, dir, len, floor, floorY), " out of " + floor.replace('_', ' '), floor);
		return plan.replace("a highway here", "a highway " + dir.getName() + " (" + built + " of " + goal + " blocks done)");
	}

	/** A stretch is done: on to the next one (or it's finished). */
	void built(BlockPos end) {
		int len = Math.min(24, goal - built);
		built += len;
		next = end.relative(dir, 1);
		if (built >= goal) {
			c.say(String.format(Locale.ROOT, "The highway is done: %d blocks %s!", goal, dir.getName()));
			c.places.remember("highway end", next);
			goal = 0;
			return;
		}
		c.chatter(String.format(Locale.ROOT, "%d blocks of highway done, %d to go.", built, goal - built), false);
		c.mod.later(20, () -> {
			if (c.player != null && on() && !c.builder.busy()) stretch();
		});
	}

	/** "build a highway north", "a highway of obsidian 200 blocks", "build a road to the east" */
	private static final java.util.regex.Pattern ASK = java.util.regex.Pattern.compile("\\b(highway|road|nether tunnel)\\b");

	String asked(String words) {
		String w = words.toLowerCase(Locale.ROOT);
		if (!ASK.matcher(w).find() || !w.matches("(?s).*\\b(build|make|dig|lay|start)\\b.*")) return null;
		Direction d = w.contains("north") ? Direction.NORTH : w.contains("south") ? Direction.SOUTH : w.contains("east") ? Direction.EAST
				: w.contains("west") ? Direction.WEST : null;
		var m = java.util.regex.Pattern.compile("\\b(\\d{2,4})\\b").matcher(w);
		int len = m.find() ? Integer.parseInt(m.group(1)) : 128;
		String mat = null;
		for (String f : FLOORS) if (w.contains(f.replace('_', ' '))) mat = f;
		return start(d, len, mat);
	}
}
