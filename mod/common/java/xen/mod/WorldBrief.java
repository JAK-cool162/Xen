package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a Xen knows of the world, in a few lines, for its model to think with (its own ideas, its leader's plans): where
 * it is, the places it knows of (a village, a ruined portal, a trial chamber: how far, which way), what it knows lies
 * near (the nearest diamonds, iron, lapis, sugar cane it knows of; a forest, a river), its team (its base, its fortress,
 * the race), and itself (its gear, its food, its levels). Knowing the world (the server's luck) where it would.
 */
final class WorldBrief {
	private WorldBrief() {}

	/** Trial chambers looked up (once in five minutes a Xen: a locate is not free). */
	private static final Map<String, Object[]> TRIALS = new HashMap<>();

	static String of(Companion c) {
		if (c.player == null) return "";
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos feet = c.player.blockPosition();
		List<String> out = new ArrayList<>();
		String dim = level.dimension() == net.minecraft.world.level.Level.OVERWORLD ? "the overworld" : level.dimension().identifier().getPath().replace('_', ' ');
		out.add(String.format(Locale.ROOT, "You are in %s%s, %s, at height %d.", dim, c.biomes.here.isEmpty() ? "" : " (" + c.biomes.here.replace('_', ' ') + ")",
				level.isDarkOutside() ? "night" : "day", feet.getY()));
		List<String> places = new ArrayList<>();
		place(places, "a village", c.visits.nearest("village"), feet);
		place(places, "a ruined portal", c.visits.nearest("ruined portal"), feet);
		place(places, "a trial chamber", trialChamber(c, level), feet);
		BlockPos table = c.enchanter.table();
		place(places, "your team's enchanting table", table, feet);
		if (!places.isEmpty()) out.add("Places you know: " + String.join("; ", places) + ".");
		List<String> near = new ArrayList<>();
		if (c.mod.config.luck >= 2) {
			for (String kind : new String[] {"diamond", "iron", "lapis", "sugar_cane", "obsidian"}) {
				var at = c.hunch.oresNear(kind, feet, 128, 160, 1);
				if (!at.isEmpty()) near.add(kind.replace('_', ' ') + " " + where(at.get(0), feet));
			}
			for (String item : new String[] {"log", "sugar_cane", "clay_ball"}) {
				Object[] b = c.biomes.nearestWith(item);
				if (b != null) near.add("a " + ((String) b[1]).replace('_', ' ') + " " + where((BlockPos) b[0], feet));
			}
		}
		if (!near.isEmpty()) out.add("Near you: " + String.join("; ", near) + ".");
		String team = c.mod.teamOf(c);
		if (team != null) {
			XenMod.Project pj = c.mod.project(c);
			int mine = 0, theirs = 0;
			for (Companion x : c.mod.companions) {
				if (x.player() == null || c.mod.teamOf(x) == null) continue;
				if (x == c || c.mod.sameSide(c, x)) mine += x.btb.raceScore();
				else theirs += x.btb.raceScore();
			}
			out.add(String.format(Locale.ROOT, "Your team (%s): %s; %s. The race: your team %d points, the other %d.", team.replace("xen_", ""),
					c.goals.home != null ? "a base " + where(c.goals.home, feet) : "no base yet",
					pj == null ? "no fortress started" : pj.finished ? "its fortress is finished" : String.format(Locale.ROOT, "its fortress %d of %d parts built", pj.partsDone, Math.max(1, pj.plan.size())),
					mine, theirs));
		}
		var it = c.items();
		int enchanted = 0;
		for (var es : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET})
			if (c.player.getItemBySlot(es).isEnchanted()) enchanted++;
		out.add(String.format(Locale.ROOT, "You: health %.0f of 20, food %d of 20, %s armor%s, %d diamonds, %d levels, %s.", c.player.getHealth(),
				c.player.getFoodData().getFoodLevel(), Companion.fullDiamond(c.player) ? "full diamond" : "not full diamond", enchanted > 0 ? " (" + enchanted + " pieces enchanted)" : "",
				it.getOrDefault("diamond", 0), c.player.experienceLevel, it.keySet().stream().anyMatch(n -> n.endsWith("_bed")) || c.hasBed() ? "a bed" : "no bed"));
		return String.join(" ", out);
	}

	private static void place(List<String> places, String what, BlockPos at, BlockPos feet) {
		if (at != null) places.add(what + " " + where(at, feet));
	}

	/** "120 blocks north-east" (and how far down or up, if much). */
	static String where(BlockPos at, BlockPos feet) {
		int dx = at.getX() - feet.getX(), dz = at.getZ() - feet.getZ(), dy = at.getY() - feet.getY();
		double d = Math.hypot(dx, dz);
		if (d < 12) return dy < -8 ? Math.abs(dy) + " blocks below you" : "right here";
		String ns = dz < -d * 0.38 ? "north" : dz > d * 0.38 ? "south" : "", ew = dx > d * 0.38 ? "east" : dx < -d * 0.38 ? "west" : "";
		String dir = ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
		return String.format(Locale.ROOT, "%.0f blocks %s%s", d, dir, dy < -12 ? ", " + Math.abs(dy) + " down" : "");
	}

	/** The nearest trial chamber (remembered, else as the world's map has it, like /locate; within about 1000 blocks). */
	private static BlockPos trialChamber(Companion c, ServerLevel level) {
		BlockPos known = c.places.get("trial chamber");
		if (known != null) return known;
		if (c.mod.config.luck < 2 || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
		Object[] had = TRIALS.get(c.name);
		long now = level.getGameTime();
		if (had != null && now - (long) had[1] < 20 * 60 * 5) return (BlockPos) had[0];
		BlockPos found = null;
		try {
			var reg = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
			var holder = reg.getOrThrow(net.minecraft.world.level.levelgen.structure.BuiltinStructures.TRIAL_CHAMBERS);
			var r = level.getChunkSource().getGenerator().findNearestMapStructure(level, net.minecraft.core.HolderSet.direct(holder), c.player.blockPosition(), 64, false);
			if (r != null) found = r.getFirst();
		} catch (RuntimeException e) {
			found = null;
		}
		TRIALS.put(c.name, new Object[] {found, now});
		return found;
	}
}
