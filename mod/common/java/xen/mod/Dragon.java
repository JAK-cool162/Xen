package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Comparator;
import java.util.List;

/**
 * Fighting the Ender Dragon, the way players learn to do it.
 * <ol>
 *   <li>Onto the main island (bridging from the platform it arrived on if it has to), a totem of undying in the off
 *   hand if it has one.</li>
 *   <li>The end crystals first (they heal the dragon), with a bow: the open ones from the ground, where it can see them;
 *   for a caged one it towers up beside the pillar, breaks two of the iron bars, bridges back out a dozen blocks (a
 *   crystal's blast reaches about twelve) and shoots through the gap, then digs its tower back down.</li>
 *   <li>Then the dragon: arrows while it flies, and when it lands on the portal in the middle, up to its head and
 *   sword hits (jumping, for critical hits). It steps out of the purple breath, never looks endermen in the eye.</li>
 *   <li>When it dies: into the portal in the middle, back home.</li>
 * </ol>
 * Without a bow it can't reach the crystals, so it fights only when the dragon lands (slower: it heals between).
 */
final class Dragon {
	private final Companion c;
	private EndCrystal caged;
	private int cageStep;
	private BlockPos tower, gap1, gap2;
	private Direction out;
	private int groundY;
	private boolean saidStart, saidWon, saidNoBow;
	private float dragonHealth = Float.NaN;
	private long lastLine;

	Dragon(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	boolean inEnd() {
		return Places.dim(c.player.level()).equals("the_end");
	}

	private void line(String s) {
		if (now() - lastLine < 200) return;
		lastLine = now();
		c.chatter(s, true);
	}

	private EnderDragon dragon(ServerLevel level) {
		for (EnderDragon d : level.getDragons()) if (d.isAlive()) return d;
		return null;
	}

	private List<EndCrystal> crystals(ServerLevel level) {
		return level.getEntitiesOfClass(EndCrystal.class, new AABB(-140, level.getMinY(), -140, 140, level.getMaxY(), 140), EndCrystal::isAlive);
	}

	/** Is a crystal in an iron-bar cage? (bars two blocks out from it, at its height) */
	private static boolean isCaged(ServerLevel level, EndCrystal e) {
		BlockPos at = BlockPos.containing(e.position());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(at.relative(d, 2).below()).getBlock()).getPath().equals("iron_bars")
					|| BuiltInRegistries.BLOCK.getKey(level.getBlockState(at.relative(d, 2)).getBlock()).getPath().equals("iron_bars")) return true;
		}
		return false;
	}

	/** The fight's next step (null when it isn't in the End, or has nothing to do there). */
	Action next() {
		if (!inEnd() || c.player.isCreative()) return null;
		ServerLevel level = (ServerLevel) c.player.level();
		c.hands.holdTotem();
		EnderDragon dragon = dragon(level);
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
		Vec3 middle = new Vec3(0.5, top, 0.5);
		if (dragon == null) return won(level, middle);
		if (!saidStart) {
			saidStart = true;
			c.say(c.hands.hasBow() ? "The dragon! Crystals first, then her." : "The dragon! I have no bow, so I'll hit her when she lands.");
			c.journal("fight", "fighting the Ender Dragon (" + crystals(level).size() + " crystals)");
		}
		Action away = outOfBreath(level);
		if (away != null) return away;
		if (caged != null) {
			Action a = cage(level);
			if (a != null) return a;
		}
		if (isOnTower(level)) return climbDown(level);
		double fromMiddle = Math.hypot(c.player.getX() - 0.5, c.player.getZ() - 0.5);
		if (fromMiddle > 70) {                                                  // still on the platform it came to: over to the island
			c.goals.instant = "crossing to the main island";
			return c.walkTo(new Vec3(0.5 + (c.player.getX() - 0.5) / fromMiddle * 30, top, 0.5 + (c.player.getZ() - 0.5) / fromMiddle * 30));
		}
		if (!Float.isNaN(dragonHealth) && dragon.getHealth() > dragonHealth + 0.5f && dragon.nearestCrystal != null) {
			c.knowledge.learn("crystals_heal", Knowledge.How.SEEN);          // she healed from a crystal: those go first
		}
		dragonHealth = dragon.getHealth();
		EnderDragonPhase<?> phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
		boolean perched = phase == EnderDragonPhase.SITTING_SCANNING || phase == EnderDragonPhase.SITTING_ATTACKING
				|| phase == EnderDragonPhase.SITTING_FLAMING || phase == EnderDragonPhase.LANDING;
		if (perched) return headHit(dragon);
		if (c.hands.hasBow() && c.knowledge.knows("crystals_heal")) {
			List<EndCrystal> left = crystals(level);
			left.sort(Comparator.comparingDouble(e -> e.distanceTo(c.player)));
			for (EndCrystal e : left) {
				if (isCaged(level, e)) continue;
				Action shot = shootCrystal(level, e);
				if (shot != null) return shot;
			}
			for (EndCrystal e : left) {
				if (!isCaged(level, e)) continue;
				if (c.walker.hasBlocks()) {
					startCage(level, e);
					return Action.IDLE;
				}
			}
			Vec3 head = dragon.head.position().add(0, 0.5, 0);
			if (c.player.distanceTo(dragon) < 70 && c.player.hasLineOfSight(dragon)) {
				c.goals.instant = "shooting at the dragon";
				Action shot = c.shoot(head, dragon.getDeltaMovement());
				if (shot != null) return shot;
			}
		} else if (c.hands.hasBow()) {                                         // (not knowing about the crystals: at her)
			Vec3 head = dragon.head.position().add(0, 0.5, 0);
			if (c.player.distanceTo(dragon) < 70 && c.player.hasLineOfSight(dragon)) {
				c.goals.instant = "shooting at the dragon";
				Action shot = c.shoot(head, dragon.getDeltaMovement());
				if (shot != null) return shot;
			}
		} else if (!saidNoBow) {
			saidNoBow = true;
			line("No bow... I'll wait for her to land.");
		}
		// wait by the portal in the middle (not on it: she breathes there), watching her
		c.goals.instant = "waiting for the dragon to land";
		Vec3 spot = middle.add(c.player.getX() > 0 ? 7 : -7, 0, 0);
		if (c.player.position().distanceTo(spot) > 3) return c.walkTo(spot);
		c.hands.watching = dragon;
		return Action.IDLE;
	}

	/** She landed: up to her head, and hit it (a jump before each swing, for a critical hit). */
	private Action headHit(EnderDragon dragon) {
		c.goals.instant = "hitting the dragon's head";
		Vec3 head = dragon.head.position().add(0, dragon.head.getBbHeight() / 2, 0);
		double d = c.player.getEyePosition().distanceTo(head);
		if (d > c.player.entityInteractionRange() + 0.5) {
			Vec3 under = new Vec3(head.x, c.player.getY(), head.z);
			return c.walkTo(under);
		}
		if (c.player.getAttackStrengthScale(0.5f) < 0.9f) {
			c.hands.face(head);
			c.acted = true;
			return Action.IDLE;
		}
		if (c.player.onGround() && c.random().nextFloat() < 0.6f) return Action.JUMP;   // up, and hit on the way down
		c.hands.hitEntity(dragon.head, head);
		c.acted = true;
		return Action.ATTACK;
	}

	/** Out of the dragon's purple breath (it hurts a lot): the nearest spot it isn't. */
	private Action outOfBreath(ServerLevel level) {
		for (AreaEffectCloud cloud : level.getEntitiesOfClass(AreaEffectCloud.class, c.player.getBoundingBox().inflate(6))) {
			double r = cloud.getRadius() + 1.5;
			Vec3 d = c.player.position().subtract(cloud.position());
			double flat = Math.hypot(d.x, d.z);
			if (flat > r) continue;
			c.goals.instant = "getting out of the dragon's breath";
			Vec3 away = flat < 0.1 ? new Vec3(1, 0, 0) : new Vec3(d.x / flat, 0, d.z / flat);
			return c.walkTo(cloud.position().add(away.scale(r + 2)));
		}
		return null;
	}

	/** An open crystal: from where it can see it (on the ground, 15 to 40 blocks off), an arrow into it. */
	private Action shootCrystal(ServerLevel level, EndCrystal e) {
		Vec3 target = e.position().add(0, 1, 0);
		if (clear(level, c.player.getEyePosition(), target) && c.player.distanceTo(e) < 60 && c.player.onGround()) {
			c.goals.instant = "shooting an end crystal";
			return c.shoot(target, Vec3.ZERO);
		}
		for (int k = 0; k < 12; k++) {                                          // a spot on the ground where it can see it
			double a = Math.atan2(-e.getZ(), -e.getX()) + (k / 2) * 0.35 * (k % 2 == 0 ? 1 : -1);   // toward the middle first
			double r = 20 + (k % 3) * 6;
			int x = (int) Math.floor(e.getX() + Math.cos(a) * r), z = (int) Math.floor(e.getZ() + Math.sin(a) * r);
			Vec3 ground = XenMod.surface(level, x, z);
			if (ground == null || ground.y < level.getMinY() + 40) continue;
			if (!clear(level, ground.add(0, 1.62, 0), target)) continue;
			c.goals.instant = "going where it can see a crystal";
			return c.walkTo(ground);
		}
		return null;
	}

	private boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
		var hit = level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, c.player));
		return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getLocation().distanceTo(to) < 1.2;
	}

	// ------------------------------------------------------------------------------ a caged crystal
	private void startCage(ServerLevel level, EndCrystal e) {
		caged = e;
		cageStep = 0;
		BlockPos at = BlockPos.containing(e.position());                          // the crystal, on bedrock at the pillar's top
		int bedrock = at.getY() - 1;
		out = Direction.getApproximateNearest(-e.getX(), 0, -e.getZ());          // the side toward the middle
		int r = 1;
		while (r < 7 && BuiltInRegistries.BLOCK.getKey(level.getBlockState(at.relative(out, r).below(2)).getBlock()).getPath().equals("obsidian")) r++;
		Vec3 ground = XenMod.surface(level, at.relative(out, r).getX(), at.relative(out, r).getZ());
		groundY = ground == null ? 62 : (int) ground.y;
		tower = new BlockPos(at.relative(out, r).getX(), groundY, at.relative(out, r).getZ());
		gap1 = at.relative(out, 2).below();                                        // the bars in between, at its feet and head
		gap2 = at.relative(out, 2);
		c.journal("fight", "going for a caged crystal: tower at " + tower.toShortString() + ", up to " + bedrock);
		line("That one's in a cage. I'll climb up and open it.");
	}

	/** On its way up, opening the cage, backing off along a bridge and shooting, then back down. */
	private Action cage(ServerLevel level) {
		if (!caged.isAlive()) {
			if (cageStep < 3) line("Someone got it first.");
			caged = null;
			return null;
		}
		BlockPos at = BlockPos.containing(caged.position());
		int standY = at.getY() - 1;                                                  // its feet level with the pillar's top
		BlockPos feet = c.player.blockPosition();
		switch (cageStep) {
			case 0 -> {                                                             // to the foot of the pillar
				c.goals.instant = "going to the caged crystal's pillar";
				if (Math.abs(feet.getX() - tower.getX()) + Math.abs(feet.getZ() - tower.getZ()) > 0 || Math.abs(feet.getY() - groundY) > 2) {
					return c.walkTo(Vec3.atBottomCenterOf(tower));
				}
				cageStep = 1;
				return Action.IDLE;
			}
			case 1 -> {                                                             // tower up beside it
				c.goals.instant = "towering up beside the pillar";
				if (feet.getY() >= standY) {
					cageStep = 2;
					return Action.IDLE;
				}
				if (!c.walker.hasBlocks()) {
					line("I ran out of blocks.");
					caged = null;
					return null;
				}
				if (c.player.onGround() && c.hands.startPillar()) {
					c.pillaring = true;
					return Action.JUMP;
				}
				return Action.IDLE;
			}
			case 2 -> {                                                             // open the cage: the bars at its feet and head
				c.goals.instant = "breaking the cage";
				for (BlockPos b : new BlockPos[] {gap1, gap2}) {
					if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(b).getBlock()).getPath().equals("iron_bars")) {
						if (c.hands.mine(b)) {
							c.acted = true;
							return Action.MINE;
						}
						return Action.IDLE;
					}
				}
				cageStep = 3;
				return Action.IDLE;
			}
			case 3 -> {                                                             // back out along a bridge, 12+ blocks from it
				c.goals.instant = "bridging away from the crystal";
				BlockPos safe = at.relative(out, 13).atY(standY);
				if (Math.hypot(feet.getX() - at.getX(), feet.getZ() - at.getZ()) >= 12.5 && c.player.onGround()) {
					cageStep = 4;
					return Action.IDLE;
				}
				return c.walkTo(Vec3.atBottomCenterOf(safe));
			}
			case 4 -> {                                                             // through the gap
				c.goals.instant = "shooting the caged crystal";
				Action shot = c.shoot(caged.position().add(0, 1, 0), Vec3.ZERO);
				if (shot == null) {
					caged = null;
					return null;
				}
				return shot;
			}
			default -> {
				caged = null;
				return null;
			}
		}
	}

	/** High up on its own tower or bridge (after a cage): back down to the ground. */
	private boolean isOnTower(ServerLevel level) {
		BlockPos feet = c.player.blockPosition();
		if (!c.player.onGround()) return false;
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
		String under = BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet.below()).getBlock()).getPath();
		if (tower == null || caged != null) return false;
		if (feet.getY() <= groundY + 1) {
			tower = null;                                                            // down again
			return false;
		}
		return (under.equals("cobblestone") || under.equals("dirt") || under.equals("cobbled_deepslate")) && feet.getY() >= ground;
	}

	private Action climbDown(ServerLevel level) {
		c.goals.instant = "getting back down";
		BlockPos feet = c.player.blockPosition();
		Vec3 below = XenMod.surface(level, feet.getX(), feet.getZ());
		// along the bridge back to the tower, then dig the tower down under its feet
		if (tower != null && (feet.getX() != tower.getX() || feet.getZ() != tower.getZ())) {
			return c.walkTo(new Vec3(tower.getX() + 0.5, feet.getY(), tower.getZ() + 0.5));
		}
		if (c.hands.mine(feet.below())) {
			c.acted = true;
			return Action.MINE;
		}
		return below == null ? null : c.walkTo(below);
	}

	/** The dragon is dead (or wasn't there): the portal in the middle takes it home. */
	private Action won(ServerLevel level, Vec3 middle) {
		BlockPos portal = Nether.portalNear(level, BlockPos.containing(middle), 4);
		if (portal == null) portal = Nether.portalNear(level, BlockPos.containing(middle).below(), 5);
		if (!saidWon && saidStart) {
			saidWon = true;
			c.say(c.pick3("We did it! The dragon is dead!", "GG, dragon. GG.", "I beat the Ender Dragon!"));
			c.antics.celebrate();
			c.journal("fight", "the Ender Dragon is dead");
			c.goals.dragonDown = true;
		}
		if (portal == null) {
			c.goals.instant = "waiting for the way home";
			return c.walkTo(middle.add(6, 0, 0));
		}
		c.goals.instant = "going home through the portal";
		return c.nether.enter(portal);
	}

	String describe() {
		if (!inEnd()) return "";
		return caged != null ? "You are in the End, opening the cage of an end crystal to shoot it." : "You are in the End, fighting the Ender Dragon.";
	}
}
