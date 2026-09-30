package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import xen.mod.core.Mind;

import java.util.Map;

/**
 * What a Xen knows about its life, as Xen 2.0's {@link Mind} sees it: the same things the simulation it learned in
 * has (its body, the time, danger, its bag, its tools and armor, what it has built, its friends and tribe, and who it
 * is: its motives and temper). Only what the Xen itself could know. And what it can do right now.
 */
final class MindSense {
	private MindSense() {}

	static int tier(String n) {
		if (n.startsWith("netherite") || n.startsWith("diamond")) return 4;
		if (n.startsWith("iron")) return 3;
		if (n.startsWith("stone") || n.startsWith("golden") || n.startsWith("chainmail")) return 2;
		return 1;
	}

	/** Its best tool of a kind (0: none, 1 wood, 2 stone, 3 iron, 4 diamond). */
	static int best(Companion c, String suffix) {
		int t = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			if (n.endsWith(suffix)) t = Math.max(t, tier(n));
		}
		return t;
	}

	/** Its armor, 0 to 4 (a full set of iron is 3, diamond 4; leather is 1). */
	static float armor(Companion c) {
		return armor(c.player);
	}

	static float armor(net.minecraft.world.entity.LivingEntity who) {
		float sum = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack s = who.getItemBySlot(slot);
			if (s.isEmpty()) continue;
			String n = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
			sum += n.startsWith("leather") ? 1 : tier(n);
		}
		return sum / 4f;
	}

	static int count(Companion c, java.util.function.Predicate<String> which) {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && which.test(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) n += s.getCount();
		}
		return n;
	}

	static boolean enchanted(Companion c) {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).isEnchanted()) return true;
		for (EquipmentSlot slot : EquipmentSlot.values()) if (c.player.getItemBySlot(slot).isEnchanted()) return true;
		return false;
	}

	/** The closest thing that means it harm (a monster after it, one that attacks on sight), within 16 blocks. */
	static LivingEntity threat(Companion c) {
		LivingEntity best = null;
		double bestD = 16;
		for (LivingEntity e : c.player.level().getEntitiesOfClass(LivingEntity.class, c.player.getBoundingBox().inflate(16), c::hostile)) {
			double d = c.player.distanceTo(e);
			if (d < bestD && c.player.hasLineOfSight(e)) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	/**
	 * Someone it would fight: a player (or Xen) it distrusts or its tribe is fighting, and, if it's aggressive and
	 * power drives it, a stranger it doesn't trust much (it picks fights). Within 24 blocks, in sight.
	 */
	static ServerPlayer enemy(Companion c) {
		Tribe t = c.tribe();
		long now = c.player.level().getGameTime();
		boolean settled = c.age() > 20 * 60 * 3;                              // (new in the world: it looks around first, it doesn't start fights)
		boolean bully = settled && c.personality.aggressive() && c.personality.power > 0.55f && now - c.lastPickedFight > 20 * 60 * 5;   // (not every minute)
		// Teams on auto (an SMP): anyone not on its team is a rival. Near its base it defends its land; a good fighter,
		// healthy and armed, goes looking for a fight now and then (not the ones it likes).
		boolean smp = settled && c.mod.config.teams < 0 && c.mod.config.pvp.equals("own") && now - c.lastPickedFight > 20 * 60 * 4 && !c.inArena;
		boolean armed = smp && count(c, n -> n.endsWith("_sword") || n.endsWith("_axe")) > 0;
		boolean duelist = smp && armed && c.player.getHealth() >= 16 && c.skills.get(Skills.FIGHT) >= 0.55f && c.personality.bravery > 0.45f;
		var myTeam = c.server.getScoreboard().getPlayersTeam(c.name);
		ServerPlayer best = null;
		double bestD = 24;
		for (ServerPlayer p : c.server.getPlayerList().getPlayers()) {
			if (p == c.player || p.level() != c.player.level() || !p.isAlive() || p.isCreative() || p.isSpectator()) continue;
			if (p.getUUID().equals(c.owner) || c.diplomacy.atPeace(p)) continue;
			float trust = c.trust(p.getUUID());
			boolean weaker = p.getHealth() + 2 * armor(p) < c.player.getHealth() + 2 * armor(c.player) + 4;   // a bully picks on the weaker
			Companion other = p instanceof XenPlayer x ? x.companion : null;
			boolean ours = t != null && other != null && t.members.contains(other);   // (its own village: not someone to pick on)
			boolean foe = trust < -0.25f || t != null && t.enemy(p, now) || bully && trust < 0.35f && weaker && !ours;
			var theirTeam = c.server.getScoreboard().getPlayersTeam(p.getScoreboardName());
			boolean rival = other == null ? myTeam == null || theirTeam != myTeam                   // a player: anyone not on its team
					: myTeam != null && theirTeam != null && theirTeam != myTeam                          // a Xen: only one of another team (team wars)
							&& !(other.owner != null && other.owner.equals(c.owner)) && !(t != null && t.members.contains(other));
			if (!foe && smp && rival) {
				boolean onItsLand = c.goals.home != null && p.blockPosition().closerThan(c.goals.home, 24) && trust < 0.5f;
				foe = onItsLand && armed || duelist && trust < 0.5f;
			}
			if (!foe) continue;
			double d = p.distanceTo(c.player);
			if (d < bestD && c.player.hasLineOfSight(p)) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** Its friend: whoever it follows, else its owner, else the tribe member it trusts most (online, same world, 48 blocks). */
	static ServerPlayer friend(Companion c) {
		for (java.util.UUID id : new java.util.UUID[] {c.leader, c.owner}) {
			if (id == null) continue;
			ServerPlayer p = c.server.getPlayerList().getPlayer(id);
			if (p != null && p != c.player && p.level() == c.player.level()) return p;
		}
		Tribe t = c.tribe();
		if (t == null) return null;
		Companion best = null;
		for (Companion m : t.members) {
			if (m == c || m.player == null || m.player.level() != c.player.level() || m.player.distanceTo(c.player) > 48) continue;
			if (best == null || c.trust(m.player.getUUID()) > c.trust(best.player.getUUID())) best = m;
		}
		return best == null ? null : best.player;
	}

	static float[] features(Companion c) {
		float[] f = new float[Mind.F];
		ServerPlayer p = c.player;
		var level = p.level();
		Map<String, Integer> items = c.items();
		f[Mind.HEALTH] = p.getHealth() / p.getMaxHealth();
		f[Mind.FOODLVL] = p.getFoodData().getFoodLevel() / 20f;
		boolean night = level.isDarkOutside();
		f[Mind.NIGHT] = night ? 1 : 0;
		f[Mind.DUSK] = !night && c.goals.evening() ? 1 : 0;
		LivingEntity threat = threat(c);
		f[Mind.DANGER] = threat == null ? 0 : (float) (1 - c.player.distanceTo(threat) / 16);
		int hostiles = level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16), c::hostile).size();
		f[Mind.HOSTILES] = Math.min(1f, hostiles / 4f);
		f[Mind.ENEMY] = enemy(c) != null ? 1 : 0;
		boolean sky = level.canSeeSky(p.blockPosition().above());
		f[Mind.UNDERGROUND] = sky ? 0 : 1;
		f[Mind.INWATER] = p.isInWater() ? 1 : 0;
		f[Mind.LOGS] = Mind.count(items.getOrDefault("log", 0), 32);
		f[Mind.PLANKS] = Mind.count(count(c, n -> n.endsWith("_planks")), 64);
		f[Mind.COBBLE] = Mind.count(items.getOrDefault("cobblestone", 0), 64);
		f[Mind.COAL] = Mind.count(items.getOrDefault("coal", 0) + items.getOrDefault("charcoal", 0), 16);
		f[Mind.RAWIRON] = Mind.count(items.getOrDefault("raw_iron", 0), 16);
		f[Mind.IRON] = Mind.count(items.getOrDefault("iron_ingot", 0), 24);
		f[Mind.DIAMOND] = Mind.count(items.getOrDefault("diamond", 0), 6);
		f[Mind.FOODITEMS] = Mind.count(Math.max(0, items.getOrDefault("food", 0) - c.chores.rawFood()), 16);
		f[Mind.TORCHES] = Mind.count(items.getOrDefault("torch", 0), 32);
		f[Mind.EMERALD] = Mind.count(items.getOrDefault("emerald", 0), 10);
		f[Mind.GOLD] = Mind.count(items.getOrDefault("gold_ingot", 0) + items.getOrDefault("raw_gold", 0), 10);
		f[Mind.PICK] = c.crafter.pickTier() / 4f;
		f[Mind.AXE] = best(c, "_axe") / 4f;
		f[Mind.SWORD] = best(c, "_sword") / 4f;
		f[Mind.ARMOR] = armor(c) / 4f;
		f[Mind.HOE] = count(c, n -> n.endsWith("_hoe")) > 0 ? 1 : 0;
		f[Mind.BUCKET] = count(c, n -> n.endsWith("bucket")) > 0 ? 1 : 0;
		f[Mind.BED] = count(c, n -> n.endsWith("_bed")) > 0 || c.hasBed() ? 1 : 0;
		f[Mind.HOME] = c.goals.home != null ? 1 : 0;
		f[Mind.FARMED] = c.farmer.middle != null || c.goals.farm != null ? 1 : 0;
		f[Mind.CHEST] = c.storage.chests.isEmpty() ? 0 : 1;
		f[Mind.MINED] = c.places.get("mine") != null ? 1 : 0;
		f[Mind.ENCHANTED] = enchanted(c) ? 1 : 0;
		f[Mind.TREES] = c.senses.nearestKnown(new int[] {xen.mod.core.Blocks.LOG}, 0.25, java.util.Set.of()) != null ? 1 : 0;
		f[Mind.VILLAGER] = c.trader.villagerNear() != null ? 1 : 0;
		int used = 0;
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) if (!inv.getItem(i).isEmpty()) used++;
		f[Mind.BAGFULL] = used / 36f;
		ServerPlayer friend = friend(c);
		f[Mind.HASFRIEND] = friend != null ? 1 : 0;
		f[Mind.FRIENDNEAR] = friend != null && friend.distanceTo(p) < 16 ? 1 : 0;
		f[Mind.ASKEDFOLLOW] = c.mode == Companion.Mode.FOLLOW ? 1 : 0;
		f[Mind.FRIENDNEEDS] = c.needs.pending() != null ? 1 : 0;
		Tribe t = c.tribe();
		f[Mind.TRIBEDANGER] = t != null && t.inDanger(c) != null ? 1 : 0;
		f[Mind.TRIBE] = t == null ? 0 : Math.min(1f, (t.members.size() - 1) / 6f);
		ServerPlayer near = null;
		double nd = 16;
		for (ServerPlayer o : c.server.getPlayerList().getPlayers()) {
			if (o == p || o.level() != level) continue;
			double d = o.distanceTo(p);
			if (d < nd) {
				nd = d;
				near = o;
			}
		}
		f[Mind.TRUST] = near == null ? 0.5f : Math.max(0, Math.min(1, (c.trust(near.getUUID()) + 1) / 2));
		Personality who = c.personality;
		f[Mind.KINDNESS] = who.kindness;
		f[Mind.LOYALTY] = who.loyalty;
		f[Mind.POWER] = who.power;
		f[Mind.MONEY] = who.money;
		f[Mind.AGGRESSIVE] = who.aggressive() ? 1 : 0;
		f[Mind.PASSIVE] = who.passive() ? 1 : 0;
		f[Mind.BRAVERY] = who.bravery;
		f[Mind.CURIOSITY] = who.curiosity;
		f[Mind.DILIGENCE] = who.diligence;
		f[Mind.XP] = Math.min(1f, p.experienceLevel / 30f);
		f[Mind.OBSIDIAN] = Mind.count(items.getOrDefault("obsidian", 0), 10);
		f[Mind.LAPIS] = Mind.count(items.getOrDefault("lapis_lazuli", 0), 10);
		f[Mind.RAWFOOD] = Mind.count(c.chores.rawFood(), 8);
		f[Mind.FURNACE] = items.getOrDefault("furnace", 0) > 0 || c.places.get("furnace") != null ? 1 : 0;
		f[Mind.DRAGON] = c.goals.dragonDown ? 1 : 0;
		BlockPos home = c.goals.home;
		f[Mind.SHELTERED] = enclosed(p) || home != null && home.closerThan(p.blockPosition(), 8) ? 1 : 0;   // (under a tree isn't a shelter)
		for (int i = 0; i < xen.mod.core.Sins.N; i++) f[Mind.SIN0 + i] = who.sin(i);   // Xen 6.0: its nature and its plan
		if (who.plan >= 0) f[Mind.PLAN0 + who.plan] = 1;
		return f;
	}

	/** What it can do right now (the same rules as in the simulation it learned in, as far as the game goes). */
	static boolean[] allowed(Companion c, float[] f, boolean nearby) {
		boolean[] a = new boolean[Mind.N];
		var cfg = c.mod.config;
		boolean creative = c.player.isCreative();
		int wood = count(c, n -> n.endsWith("_planks")) + 4 * c.items().getOrDefault("log", 0);
		int food = c.player.getFoodData().getFoodLevel();
		boolean night = f[Mind.NIGHT] > 0.5f;
		float health = c.player.getHealth();
		a[Mind.REST] = night || health < 8 || health < 14 && food >= 18 || c.mode == Companion.Mode.STAY;   // (in daylight a player gets on with something; hurt, a rest only helps if it heals, fed)
		if (!night && c.goals.restedLast() && health >= 8) a[Mind.REST] = false;   // (one breather, not one after another)
		a[Mind.WOOD] = true;
		a[Mind.STONE] = c.crafter.pickTier() >= 1;
		a[Mind.CRAFT] = c.crafter.canTry() && c.crafter.upgrade() != null;
		a[Mind.FOOD] = true;
		a[Mind.EAT] = (food < 16 || food < 19 && c.player.getHealth() < 14) && c.items().getOrDefault("food", 0) > 0;
		a[Mind.SHELTER] = night || f[Mind.DUSK] > 0.5f;
		a[Mind.SLEEP] = night && c.hasBed();
		a[Mind.HOUSE] = (!c.builder.busy() && !creative && (c.goals.home == null && wood >= 60 || c.goals.home != null && wood >= 160
				|| c.goals.home != null && c.builder.canUpgrade() && wood >= 40)) || !nearby && !c.builder.busy() && c.builder.friendBuilding() != null;   // (or a friend's build to help with)
		a[Mind.FARM] = c.farmer.middle == null && !c.farmer.on && (f[Mind.HOE] > 0 || wood >= 4)
				&& (c.crafter.pickTier() >= 3 || food < 10 && c.items().getOrDefault("food", 0) == 0);   // iron first (farms come later, or when food runs out)
		a[Mind.MINE] = c.crafter.pickTier() >= 1;
		var items = c.items();
		a[Mind.SMELT] = (items.getOrDefault("raw_iron", 0) >= 1 || c.chores.rawFood() >= 1) && (items.getOrDefault("coal", 0) > 0 || items.getOrDefault("log", 0) > 1)
				&& (items.getOrDefault("cobblestone", 0) >= 8 || items.getOrDefault("furnace", 0) > 0);
		a[Mind.STORE] = c.storage.wantsToStore() && (c.goals.home != null || c.tribe() != null && c.tribe().center != null);
		a[Mind.EXPLORE] = !night && f[Mind.DUSK] < 0.5f;                       // (not off into the dark)
		a[Mind.TRADE] = cfg.trading && c.trader.villagerNear() != null && c.trader.hasSomethingToTrade();
		a[Mind.FOLLOW] = f[Mind.HASFRIEND] > 0.5f;
		a[Mind.HELP] = c.needs.pending() != null && c.needs.canHelp();
		a[Mind.GUARD] = f[Mind.TRIBEDANGER] > 0.5f;
		a[Mind.FIGHT] = f[Mind.DANGER] > 0.2f || f[Mind.ENEMY] > 0.5f && !cfg.pvp.equals("off");
		a[Mind.FLEE] = f[Mind.DANGER] > 0.2f || f[Mind.ENEMY] > 0.5f;
		a[Mind.ADVENTURE] = cfg.adventures && !creative && (!c.goals.dragonDown && c.crafter.pickTier() >= 3 && f[Mind.SWORD] >= 0.75f
				&& f[Mind.ARMOR] >= 0.6f && items.getOrDefault("food", 0) >= 8 || c.goals.dragonDown && c.voyager.wantsQuest());
		a[Mind.ENCHANT] = c.enchanter.possible();
		if (nearby && c.personality.loyalty >= 0.35f) {                        // with its friend: a loyal Xen stays close
			for (int o = 0; o < Mind.N; o++) if (Mind.far(o)) a[o] = false;
		}
		return a;
	}

	/**
	 * A real roof over its head (not leaves, not open sky) and something on every side close by: a hut, a hole in the
	 * ground, a dugout, a cave, a house. Standing under a tree is not a shelter.
	 */
	static boolean enclosed(net.minecraft.server.level.ServerPlayer p) {
		var level = p.level();
		BlockPos feet = p.blockPosition(), head = feet.above();
		boolean roof = false;
		for (int y = 1; y <= 4; y++) {
			BlockPos b = head.above(y);
			var st = level.getBlockState(b);
			if (st.isAir()) continue;
			roof = !st.getCollisionShape(level, b).isEmpty() && !(st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock);
			break;
		}
		if (!roof) return false;
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			boolean low = false, high = false;
			for (int k = 1; k <= 3; k++) {
				BlockPos a = feet.relative(d, k), b = head.relative(d, k);
				if (!low && !level.getBlockState(a).getCollisionShape(level, a).isEmpty()) low = true;
				if (!high && !level.getBlockState(b).getCollisionShape(level, b).isEmpty()) high = true;
			}
			if (!low || !high) return false;
		}
		return true;
	}
}
