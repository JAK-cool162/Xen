package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The way mobs find theirs: Minecraft's own pathfinder (the one zombies and villagers use), asked for a Xen. It walks,
 * jumps up a block, drops down up to three, swims, goes through doors, round fences and cactus, clear of lava and fire;
 * it never digs or builds. The walker follows the way it finds with the player's keys (sprinting), and only when this
 * finds no way there (the target is inside rock, behind a wall, up a cliff) does it plan its own (digging, towering).
 * <p>The pathfinder wants a mob to plan for: a stand-in zombie (the same size as a player, never added to the world).
 */
final class MobPath {
	private static EntityType<?> zombie;
	private Mob body;
	private ServerLevel bodyLevel;
	/** How many plans it found, and how many found no way (for the journal and the tests). */
	int found, missed;

	private Mob body(ServerLevel level) {
		if (body != null && bodyLevel == level) return body;
		if (zombie == null) {
			for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {                  // (by its id: the classes move between versions)
				if (BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath().equals("zombie")) zombie = t;
			}
		}
		if (zombie == null) return null;
		var e = zombie.create(level, EntitySpawnReason.LOAD);
		body = e instanceof Mob m ? m : null;
		bodyLevel = level;
		if (body != null) {
			body.setPathfindingMalus(PathType.WATER, 4);                             // (swimming is slow, not bad)
		}
		return body;
	}

	/**
	 * A way from where it stands to the goal (to the block, or next to it when it's a solid block to mine), as the
	 * walker's moves; null when the mobs' way can't get there (or not much closer, for a far goal).
	 */
	List<Walker.Move> plan(Companion c, ServerLevel level, BlockPos start, Vec3 to, int maxNodes) {
		Mob m = body(level);
		if (m == null) return null;
		var p = c.player;
		m.setPos(p.getX(), p.getY(), p.getZ());
		m.setOnGround(p.onGround());
		BlockPos target = BlockPos.containing(to);
		double far = Math.sqrt(start.distSqr(target));
		boolean solid = !level.getBlockState(target).getCollisionShape(level, target).isEmpty();
		int r = (int) Math.min(96, far + 16);
		WalkNodeEvaluator eval = new WalkNodeEvaluator();
		eval.setCanPassDoors(true);
		eval.setCanOpenDoors(true);
		eval.setCanFloat(true);
		PathFinder finder = new PathFinder(eval, maxNodes);
		PathNavigationRegion region = new PathNavigationRegion(level, start.offset(-r, -r, -r), start.offset(r, r, r));
		Path path;
		try {
			path = finder.findPath(region, m, Set.of(target), r, solid ? 1 : 0, 1f);
		} catch (RuntimeException e) {                                                    // (a chunk unloading under it, say)
			path = null;
		}
		if (path == null || path.getNodeCount() < 2) {
			missed++;
			return null;
		}
		Node end = path.getEndNode();
		double left = Math.sqrt(end.asBlockPos().distSqr(target));
		if (!path.canReach() && (far < 40 || left > far - 12)) {                      // no way there (a far goal: not much closer)
			missed++;
			return null;
		}
		found++;
		List<Walker.Move> out = new ArrayList<>();
		BlockPos prev = start;
		for (int i = 0; i < path.getNodeCount(); i++) {
			Node n = path.getNode(i);
			BlockPos at = n.asBlockPos();
			if (at.equals(prev)) continue;
			boolean wet = n.type == PathType.WATER || n.type == PathType.WATER_BORDER || !level.getFluidState(at).isEmpty();
			int dx = at.getX() - prev.getX(), dy = at.getY() - prev.getY(), dz = at.getZ() - prev.getZ();
			if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || dy > 1) return out.isEmpty() ? null : out;   // (not a step: stop there)
			Walker.Kind k = wet ? Walker.Kind.SWIM : dy > 0 ? Walker.Kind.ASCEND : dy < 0 ? Walker.Kind.FALL
					: dx != 0 && dz != 0 ? Walker.Kind.DIAGONAL : Walker.Kind.WALK;
			out.add(new Walker.Move(k, prev, at, List.of(), 1));
			prev = at;
		}
		return out.isEmpty() ? null : out;
	}
}
