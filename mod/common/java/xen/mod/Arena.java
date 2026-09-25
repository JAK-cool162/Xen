package xen.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The PvP arena: Xens train against each other, red team against blue team. Every round each red Xen duels a random
 * blue one in its own lane, with the same kit. Every few rounds each team evolves: its worst fighters take on the fight
 * genes of children of its best (each gene from one of two parents, with a small mutation), so the two teams push
 * each other on, generation after generation. The best genes are kept in {@code <world>/xen/arena_champions.json}, and
 * new Xens are born with them; every generation is logged in {@code arena.csv}. Arena Xens don't learn into the shared
 * brain (duels would crowd out what it learned about lava and caves), and they don't chat.
 */
final class Arena {
	static final int FIGHT_TICKS = 600, REST_TICKS = 80, ROUNDS = 3, LANE = 24;
	static final String[] KITS = {"sword", "shield", "axe"};

	private final XenMod mod;
	private final Random random = new Random();
	private final List<Companion> red = new ArrayList<>(), blue = new ArrayList<>();
	/** Per Xen this generation: wins, losses, draws, damage dealt, damage taken. */
	private final Map<Companion, float[]> stats = new HashMap<>();
	private ServerLevel level;
	private BlockPos origin;
	private String kit = "sword";
	private boolean running, fighting;
	private int generation, lastGeneration, round, timer;
	private Companion[] laneRed, laneBlue;
	private float[] healthRed, healthBlue;
	private boolean[] done;

	Arena(XenMod mod) {
		this.mod = mod;
	}

	boolean running() {
		return running;
	}

	/** Build the arena above at, bring in the two teams and start. */
	String start(ServerLevel level, BlockPos at, int perTeam, int generations, String kit) {
		if (running) return "The arena is already running: " + status();
		this.level = level;
		this.kit = kit;
		int y = Math.min(level.getMaxY() - 12, Math.max(at.getY() + 40, 120));
		origin = new BlockPos(at.getX(), y, at.getZ());
		for (int i = 0; i < perTeam; i++) build(lane(i));
		red.clear();
		blue.clear();
		stats.clear();
		generation = readGeneration();
		lastGeneration = generation + generations;
		for (int i = 0; i < perTeam; i++) {
			for (String team : new String[] {"red", "blue"}) {
				String name = (team.equals("red") ? "Red" : "Blue") + (i + 1);
				if (mod.server().getPlayerList().getPlayerByName(name) != null) return name + " is already here: dismiss it first.";
			}
		}
		for (int i = 0; i < perTeam; i++) {
			red.add(bringIn("Red" + (i + 1), "red", i));
			blue.add(bringIn("Blue" + (i + 1), "blue", i));
		}
		laneRed = new Companion[perTeam];
		laneBlue = new Companion[perTeam];
		healthRed = new float[perTeam];
		healthBlue = new float[perTeam];
		done = new boolean[perTeam];
		running = true;
		fighting = false;
		round = 0;
		timer = 40;
		return String.format(Locale.ROOT, "Arena: %d red against %d blue Xens, kit: %s, %d generations of %d rounds, at %d %d %d. "
				+ "/xen arena status, /xen arena stop. With /tick sprint it goes faster.", perTeam, perTeam, kit, generations, ROUNDS,
				origin.getX(), origin.getY(), origin.getZ());
	}

	void stop(String why) {
		if (!running) return;
		running = false;
		for (Companion c : all()) {
			c.duelFoe = null;
			c.leave();
		}
		red.clear();
		blue.clear();
		XenMod.LOG.info("Xen arena stopped: {}", why);
		mod.server().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("Xen arena: " + why), false);
	}

	String status() {
		if (!running) return "The arena isn't running. /xen arena start [xens per team] [generations] [sword|shield|axe]";
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "Arena generation %d (until %d), round %d of %d, kit %s.",
				generation + 1, lastGeneration, round % ROUNDS + 1, ROUNDS, kit));
		for (List<Companion> team : List.of(red, blue)) {
			Companion best = null;
			float wins = 0;
			for (Companion c : team) {
				wins += stats(c)[0];
				if (best == null || score(c) > score(best)) best = c;
			}
			sb.append(String.format(Locale.ROOT, " %s: %.0f wins, best %s (%s).", team == red ? "Red" : "Blue", wins,
					best == null ? "-" : best.name, best == null ? "" : best.personality.fight));
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------------------ rounds
	void tick() {
		if (!running) return;
		for (Companion c : all()) {
			if (!mod.companions.contains(c)) {
				stop(c.name + " left, so the arena stopped.");
				return;
			}
		}
		if (!fighting) {
			if (timer-- > 0) return;
			for (Companion c : all()) {
				if (c.player() == null || !c.player().isAlive()) {
					timer = 10;                                                // wait for the fallen to come back
					return;
				}
			}
			tidy();
			List<Companion> order = new ArrayList<>(blue);
			Collections.shuffle(order, random);
			for (int i = 0; i < red.size(); i++) {
				laneRed[i] = red.get(i);
				laneBlue[i] = order.get(i);
				setUp(i);
			}
			fighting = true;
			timer = 0;
			return;
		}
		timer++;
		boolean over = true;
		for (int i = 0; i < done.length; i++) {
			if (done[i]) continue;
			Companion r = laneRed[i], b = laneBlue[i];
			float rh = health(r), bh = health(b);
			stats(r)[4] += Math.max(0, healthRed[i] - rh);
			stats(b)[3] += Math.max(0, healthRed[i] - rh);
			stats(b)[4] += Math.max(0, healthBlue[i] - bh);
			stats(r)[3] += Math.max(0, healthBlue[i] - bh);
			healthRed[i] = rh;
			healthBlue[i] = bh;
			if (rh > 0 && bh > 0 && timer < FIGHT_TICKS) {
				over = false;
				continue;
			}
			done[i] = true;
			r.duelFoe = b.duelFoe = null;
			if (rh > 0 && bh <= 0) record(r, b);
			else if (bh > 0 && rh <= 0) record(b, r);
			else {
				stats(r)[2]++;
				stats(b)[2]++;
			}
		}
		if (!over) return;
		fighting = false;
		timer = REST_TICKS;
		if (++round % ROUNDS == 0) evolve();
	}

	private void setUp(int lane) {
		BlockPos c = lane(lane);
		Companion r = laneRed[lane], b = laneBlue[lane];
		for (Companion x : new Companion[] {r, b}) {
			var p = x.player();
			p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5f);
			p.removeAllEffects();
			p.clearFire();
			giveKit(x);
		}
		int side = round % 2 == 0 ? 1 : -1;                                           // the teams swap ends every round
		r.teleport(level, Vec3.atBottomCenterOf(c.offset(0, 1, -4 * side)), side > 0 ? 0f : 180f);   // facing each other, 8 apart
		b.teleport(level, Vec3.atBottomCenterOf(c.offset(0, 1, 4 * side)), side > 0 ? 180f : 0f);
		r.duelFoe = b.player();
		b.duelFoe = r.player();
		healthRed[lane] = r.player().getHealth();
		healthBlue[lane] = b.player().getHealth();
		done[lane] = false;
	}

	/** The same kit for everyone: iron armor, an iron sword and a golden apple (and a shield, or a shield and an axe). */
	private void giveKit(Companion c) {
		var p = c.player();
		p.getInventory().clearContent();
		p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
		p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
		p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
		p.getInventory().add(new ItemStack(Items.IRON_SWORD));
		if (kit.equals("axe")) p.getInventory().add(new ItemStack(Items.IRON_AXE));
		p.getInventory().add(new ItemStack(Items.GOLDEN_APPLE));
		p.setItemInHand(InteractionHand.OFF_HAND, kit.equals("sword") ? ItemStack.EMPTY : new ItemStack(Items.SHIELD));
	}

	private void record(Companion winner, Companion loser) {
		stats(winner)[0]++;
		stats(loser)[1]++;
	}

	private static float health(Companion c) {
		var p = c.player();
		return p == null || !p.isAlive() ? 0 : p.getHealth();
	}

	/** Dropped kits and experience from the last round go away. */
	private void tidy() {
		AABB box = new AABB(Vec3.atLowerCornerOf(origin.offset(-10, -2, -10)), Vec3.atLowerCornerOf(origin.offset(red.size() * LANE + 10, 8, 10)));
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
		for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, box)) e.discard();
	}

	// --------------------------------------------------------------------------- evolution
	float[] stats(Companion c) {
		return stats.computeIfAbsent(c, k -> new float[5]);
	}

	private float score(Companion c) {
		float[] s = stats(c);
		return 3 * s[0] + s[2] + (s[3] - 0.5f * s[4]) / 20f;          // hitting counts more than not being hit
	}

	/**
	 * Each team: the worst quarter get the fight genes of children of the best half. A team that won less than a quarter
	 * of its fights also learns from the enemy: one child has a parent from the other team's best half.
	 */
	private void evolve() {
		generation++;
		List<float[]> champions = new ArrayList<>();
		StringBuilder log = new StringBuilder();
		Map<List<Companion>, List<Companion>> best = new HashMap<>();
		for (List<Companion> team : List.of(red, blue)) {
			List<Companion> ranked = new ArrayList<>(team);
			ranked.sort((a, b) -> Float.compare(score(b), score(a)));
			best.put(team, new ArrayList<>(ranked.subList(0, Math.max(1, ranked.size() / 2))));
		}
		for (List<Companion> team : List.of(red, blue)) {
			String color = team == red ? "red" : "blue";
			List<Companion> ranked = new ArrayList<>(team);
			ranked.sort((a, b) -> Float.compare(score(b), score(a)));
			float wins = 0, losses = 0, draws = 0;
			float[] mean = new float[Personality.GENES.length];
			for (Companion c : ranked) {
				wins += stats(c)[0];
				losses += stats(c)[1];
				draws += stats(c)[2];
				for (int g = 0; g < mean.length; g++) mean[g] += c.personality.fightGenes[g] / ranked.size();
			}
			Companion top = ranked.get(0);
			writeCsv(color, wins, losses, draws, top, mean);
			List<Companion> enemyBest = best.get(team == red ? blue : red);
			boolean behind = wins < 0.25f * (wins + losses + draws);
			List<Companion> parents = ranked.subList(0, Math.max(1, ranked.size() / 2));
			for (Companion c : parents) champions.add(c.personality.fightGenes.clone());
			int replace = ranked.size() >= 2 ? Math.max(1, ranked.size() / 4) : 0;
			for (int i = 0; i < replace; i++) {
				Companion loser = ranked.get(ranked.size() - 1 - i);
				Companion a = parents.get(random.nextInt(parents.size()));
				Companion b = behind && i == 0 ? enemyBest.get(random.nextInt(enemyBest.size())) : parents.get(random.nextInt(parents.size()));
				Personality nature = loser.personality;
				for (int g = 0; g < nature.fightGenes.length; g++) {
					nature.fightGenes[g] = Personality.mutate(random.nextBoolean() ? a.personality.fightGenes[g] : b.personality.fightGenes[g], random);
				}
				nature.fight = Personality.nearest(nature.fightGenes);
				nature.generation = Math.max(a.personality.generation, b.personality.generation) + 1;
				nature.parents = a.name + "+" + b.name;
			}
			log.append(String.format(Locale.ROOT, " %s %.0f-%.0f-%.0f (best %s, a %s)%s;", color, wins, losses, draws, top.name,
					top.personality.fight, behind ? ", learning from the enemy" : ""));
		}
		stats.clear();
		saveChampions(champions);
		for (Companion c : all()) mod.roster.remember(c, mod.teamOf(c));
		mod.roster.save();
		XenMod.LOG.info("Xen arena generation {}:{}", generation, log);
		if (generation >= lastGeneration) stop("done: " + (generation) + " generations. Results in <world>/xen/arena.csv.");
	}

	private void writeCsv(String team, float wins, float losses, float draws, Companion best, float[] mean) {
		Path file = mod.brainFile().resolveSibling("arena.csv");
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.writeString(file, "generation,team,kit,wins,losses,draws,best,best_style," + String.join(",", Personality.GENES) + "\n");
			}
			StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d,%s,%s,%.0f,%.0f,%.0f,%s,%s", generation, team, kit, wins, losses,
					draws, best.name, best.personality.fight));
			for (float m : mean) sb.append(String.format(Locale.ROOT, ",%.3f", m));
			Files.writeString(file, sb.append('\n').toString(), StandardOpenOption.APPEND);
		} catch (IOException e) {
			XenMod.LOG.warn("Could not write {}: {}", file, e.toString());
		}
	}

	private void saveChampions(List<float[]> champions) {
		JsonObject o = new JsonObject();
		o.addProperty("generation", generation);
		o.addProperty("kit", kit);
		JsonArray all = new JsonArray();
		for (float[] genes : champions) {
			JsonArray g = new JsonArray();
			for (float x : genes) g.add(x);
			all.add(g);
		}
		o.add("genes", all);
		try {
			Files.writeString(mod.brainFile().resolveSibling("arena_champions.json"), new Gson().toJson(o));
		} catch (IOException e) {
			XenMod.LOG.warn("Could not save the arena champions: {}", e.toString());
		}
	}

	/** The fight genes of a random arena champion (mutated a little), or null if there are none yet. */
	static float[] championGenes(Path xenDir, Random random) {
		try {
			Path file = xenDir.resolve("arena_champions.json");
			if (!Files.exists(file)) return null;
			JsonArray all = new Gson().fromJson(Files.readString(file), JsonObject.class).getAsJsonArray("genes");
			if (all == null || all.isEmpty()) return null;
			JsonArray pick = all.get(random.nextInt(all.size())).getAsJsonArray();
			if (pick.size() != Personality.GENES.length) return null;
			float[] genes = new float[pick.size()];
			for (int i = 0; i < genes.length; i++) genes[i] = Personality.mutate(pick.get(i).getAsFloat(), random);
			return genes;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private int readGeneration() {
		try {
			Path file = mod.brainFile().resolveSibling("arena_champions.json");
			if (Files.exists(file)) return new Gson().fromJson(Files.readString(file), JsonObject.class).get("generation").getAsInt();
		} catch (IOException | RuntimeException ignored) {
			// start from 0
		}
		return 0;
	}

	// ------------------------------------------------------------------------------ places
	private List<Companion> all() {
		List<Companion> out = new ArrayList<>(red);
		out.addAll(blue);
		return out;
	}

	private BlockPos lane(int i) {
		return origin.offset(i * LANE, 0, 0);
	}

	private Companion bringIn(String name, String team, int lane) {
		Companion c = mod.create(name, null, null);
		c.inArena = true;
		c.arenaTeam = team;
		c.mode = Companion.Mode.STAY;
		BlockPos at = lane(lane).offset(0, 1, team.equals("red") ? -4 : 4);
		c.join(level, Vec3.atBottomCenterOf(at), team.equals("red") ? 0f : 180f);
		c.anchor = at;
		mod.companions.add(c);
		return c;
	}

	/** A block by its name (the same on every version), or plain glass. */
	private static BlockState block(String name) {
		for (net.minecraft.world.level.block.Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
			if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().equals(name)) return b.defaultBlockState();
		}
		return Blocks.GLASS.defaultBlockState();
	}

	/**
	 * One lane: a 13x13 floor with walls 4 high, all of barrier blocks (nothing can spawn on them, and nobody can break
	 * them), with colored glass under the floor so people can see it.
	 */
	private void build(BlockPos c) {
		BlockState barrier = Blocks.BARRIER.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		BlockState redGlass = block("red_stained_glass"), blueGlass = block("blue_stained_glass");
		for (int x = -7; x <= 7; x++) {
			for (int z = -7; z <= 7; z++) {
				boolean wall = Math.abs(x) == 7 || Math.abs(z) == 7;
				BlockState glass = z < 0 ? redGlass : z > 0 ? blueGlass : Blocks.GLASS.defaultBlockState();
				level.setBlock(c.offset(x, -1, z), glass, 3);
				level.setBlock(c.offset(x, 0, z), barrier, 3);
				for (int y = 1; y <= 5; y++) level.setBlock(c.offset(x, y, z), wall && y <= 4 ? barrier : air, 3);
			}
		}
	}
}
