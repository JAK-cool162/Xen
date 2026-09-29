package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * What it's looking at, as places players know: a village (a bell, villagers), a mineshaft (rails and cobwebs), a
 * dungeon (a spawner in mossy cobblestone), a ruined portal (crying obsidian), a desert or jungle temple, a shipwreck,
 * a stronghold (end portal frames), a Nether fortress (nether bricks), a bastion (gilded blackstone), an End city
 * (purpur), or a house someone built (a door and a bed). Only from what it sees, like a player: a glance at a few
 * blocks in front of it now and then. It remembers each one (its places), says so, and knows what they're good for.
 */
final class Structures {
	/** What each is, by a block that gives it away (and what it's good for). */
	private static final String[][] SIGNS = {
			{"bell", "village", "A village! Food, beds and trades."},
			{"rail", "mineshaft", "An old mineshaft. Ore, and chests on minecarts. Careful, cave spiders."},
			{"spawner", "dungeon", "A spawner. That's a dungeon: there's loot in the chests."},
			{"crying_obsidian", "ruined portal", "A ruined portal. There's usually a chest with gold."},
			{"chiseled_sandstone", "desert temple", "A desert temple! Watch out for the trap under it."},
			{"mossy_cobblestone", "old ruins", "Mossy cobblestone. Something old is around here."},
			{"end_portal_frame", "stronghold", "End portal frames! The stronghold."},
			{"nether_bricks", "fortress", "A Nether fortress. Blazes and wither skeletons."},
			{"gilded_blackstone", "bastion", "A bastion. Lots of piglins, and gold."},
			{"purpur", "end city", "An End city!"},
			{"stripped_dark_oak_log", "shipwreck", "A shipwreck, maybe. Treasure maps are in those."},
			{"prismarine_bricks", "ocean monument", "An ocean monument! Guardians. Sponges inside."},
			{"reinforced_deepslate", "ancient city", "An ancient city... quiet. The warden lives here."},
			{"trial_spawner", "trial chamber", "A trial chamber! Keys and vaults."},
			{"tripwire_hook", "jungle temple", "A jungle temple. Mind the tripwires."},
			{"suspicious_gravel", "trail ruins", "Suspicious gravel. Old ruins: I could brush for pottery sherds."},
			{"cauldron", "witch hut", "A cauldron out here... a witch hut?"},
	};

	private final Companion c;
	private final Random random = new Random();
	/** The places of each kind it found (the latest). */
	final Map<String, BlockPos> found = new LinkedHashMap<>();
	private long next;
	private int turn;

	Structures(Companion c) {
		this.c = c;
	}

	/** Now and then: a glance for the blocks that give a place away (one kind at a time: cheap). */
	void look() {
		if (c.player == null) return;
		long now = c.player.level().getGameTime();
		if (now < next) return;
		next = now + 40;
		ServerLevel level = (ServerLevel) c.player.level();
		String[] sign = SIGNS[turn++ % SIGNS.length];
		BlockPos at = c.nether.lookFor(level, sign[0], 64);
		if (at == null) {
			house(level);
			return;
		}
		BlockPos had = found.get(sign[1]);
		if (had != null && had.closerThan(at, 96)) return;
		found.put(sign[1], at);
		c.habits.spotted(at, sign[1]);                                        // (a curious one may go and look)
		boolean many = !sign[1].equals("stronghold");                         // (there's one stronghold near; villages, temples: many)
		if (many) c.places.rememberAnother(sign[1], at, sign[1].equals("village") ? 160 : 96);
		else c.places.remember(sign[1], at);
		c.journal("sees", "a " + sign[1] + " at " + at.toShortString() + " (" + c.places.biomeAt(at) + ")");
		if (!sign[1].equals("old ruins")) c.lore(c.name + " found a " + sign[1] + " at " + at.getX() + " " + at.getZ() + " (" + c.places.biomeAt(at) + ")");
		c.chatter(sign[2], false);
	}

	/** A house someone built (a door, a bed or a crafting table behind walls of planks): not its own, and not to dig through. */
	private void house(ServerLevel level) {
		BlockPos door = c.nether.lookFor(level, "oak_door", 24);
		if (door == null) return;
		if (c.goals.home != null && c.goals.home.closerThan(door, 16)) return;
		if (c.builder.plan != null && c.builder.plan.middle().closerThan(door, 16)) return;   // (the one it's building)
		BlockPos had = found.get("house");
		if (had != null && had.closerThan(door, 24)) return;
		found.put("house", door);
		c.places.remember("house", door);
		c.journal("sees", "someone's house at " + door.toShortString());
		if (random.nextFloat() < 0.5f) c.chatter(c.pick3("Someone lives there.", "Nice house over there.", "A house. Not mine, though."), false);
	}

	/**
	 * Its eyes keep seeing blocks people build (planks, glass, doors, beds, bricks...): a dozen close together, not
	 * its own home, is someone's house (or a village's).
	 */
	void builtSeen(BlockPos at, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap built) {
		if (c.goals.home != null && c.goals.home.closerThan(at, 20)) return;
		if (c.builder.plan != null && c.builder.plan.middle().closerThan(at, 20)) return;   // (the one it's building)
		BlockPos had = found.get("house");
		if (had != null && had.closerThan(at, 24)) return;
		int near = 0;
		for (var it = built.keySet().iterator(); it.hasNext(); ) if (BlockPos.of(it.nextLong()).closerThan(at, 8)) near++;
		if (near < 12) return;
		found.put("house", at);
		c.eyes.houses++;
		c.places.remember("house", at);
		c.journal("sees", "someone's house at " + at.toShortString());
		XenMod.LOG.info("{} found a house at {}", c.name, at.toShortString());
		if (random.nextFloat() < 0.5f) c.chatter(c.pick3("Someone lives there.", "Nice house over there.", "A house. Not mine, though."), false);
	}

	String describe() {
		if (found.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("Places you found:");
		int n = 0;
		for (var e : found.entrySet()) {
			if (n++ >= 5) break;
			sb.append(n > 1 ? "," : "").append(" a ").append(e.getKey()).append(" at ").append(e.getValue().getX()).append(' ').append(e.getValue().getZ());
		}
		return sb.append('.').toString();
	}

	/** Is this a spot it knows as a place of that kind (within r)? */
	boolean near(String kind, Vec3 at, double r) {
		BlockPos p = found.get(kind);
		return p != null && Vec3.atCenterOf(p).distanceTo(at) < r;
	}

	static String name(net.minecraft.world.level.block.state.BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
	}
}
