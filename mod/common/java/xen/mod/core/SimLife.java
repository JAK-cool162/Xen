package xen.mod.core;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.Random;

/**
 * A survival life, fast: where {@link Mind} learns what to do from nothing before it ever sees Minecraft. Not blocks
 * and physics (its body knows those), but what a player's choices lead to: days and nights, hunger, monsters in the
 * dark, trees, stone, ore at depth, crafting tiers, furnaces, homes and beds, farms, chests, villagers, a friend who
 * asks it to stay close or needs a hand, a tribe that gets attacked, enemies. Each life gets a random personality
 * (kindness, loyalty, power, money, temper, bravery, curiosity, diligence) so one mind learns to choose for all of them.
 *
 * Run: java -cp <mod classes> xen.mod.core.SimLife out.bin [steps]
 */
public final class SimLife {
	final Random r;
	// who it is
	float kind, loyal, power, money, brave, curious, diligent;
	boolean aggressive, passive;
	// its body and its things
	long t;
	float hp, food;
	int logs, planks, cobble, coal, rawIron, iron, diamond, foodItems, rawFood, torches, emerald, gold, obsidian, lapis, wool, xp;
	int pick, axe, sword;
	float armor;
	boolean hoe, bucket, bedItem, furnace, home, bed, farm, chest, mine, table, enchanted, trees, villager, sheltered, dragon;
	long farmGrow;
	// around it
	float danger;
	int hostiles;
	boolean enemy, hasFriend, friendNear, asked, friendNeeds, tribeDanger;
	int tribe;
	float trust;
	boolean dead;
	// for the report
	long firstPick = -1, firstStone = -1, firstIron = -1, firstHome = -1, firstDiamond = -1, firstFarm = -1;

	SimLife(Random r) {
		this.r = r;
		reset();
	}

	void reset() {
		kind = r.nextFloat();
		loyal = r.nextFloat();
		power = r.nextFloat();
		money = r.nextFloat();
		float[] m = {kind, loyal, power, money};
		m[r.nextInt(4)] = 0.7f + 0.3f * r.nextFloat();
		kind = m[0];
		loyal = m[1];
		power = m[2];
		money = m[3];
		brave = r.nextFloat();
		curious = r.nextFloat();
		diligent = r.nextFloat();
		float tm = r.nextFloat();
		aggressive = tm > 0.75f;
		passive = tm > 0.5f && tm <= 0.75f;
		t = r.nextInt(6000);
		hp = 20;
		food = 20;
		logs = planks = cobble = coal = rawIron = iron = diamond = foodItems = rawFood = torches = emerald = gold = obsidian = lapis = wool = xp = 0;
		pick = axe = sword = 0;
		armor = 0;
		hoe = bucket = bedItem = furnace = home = bed = farm = chest = mine = table = enchanted = villager = sheltered = dragon = false;
		trees = r.nextFloat() < 0.7f;
		farmGrow = 0;
		danger = 0;
		hostiles = 0;
		enemy = false;
		hasFriend = r.nextFloat() < 0.6f;
		friendNear = hasFriend && r.nextBoolean();
		asked = hasFriend && r.nextFloat() < 0.3f;
		friendNeeds = tribeDanger = false;
		tribe = r.nextFloat() < 0.4f ? 1 + r.nextInt(6) : 0;
		trust = (float) r.nextGaussian() * 0.4f;
		dead = false;
		firstPick = firstStone = firstIron = firstHome = firstDiamond = firstFarm = -1;
		if (r.nextFloat() < 0.35f) midLife();                             // some lives start further along (so it learns those too)
	}

	/** A life already under way: some tools, some things. */
	private void midLife() {
		int stage = r.nextInt(4);
		logs = r.nextInt(30);
		cobble = r.nextInt(60);
		coal = r.nextInt(12);
		foodItems = r.nextInt(10);
		pick = Math.min(4, 1 + stage);
		axe = Math.min(3, stage + (r.nextBoolean() ? 1 : 0));
		sword = Math.min(4, stage);
		armor = stage >= 2 ? r.nextFloat() * 3 : 0;
		rawIron = stage >= 1 ? r.nextInt(8) : 0;
		iron = stage >= 2 ? r.nextInt(12) : 0;
		diamond = stage >= 3 ? r.nextInt(5) : 0;
		home = stage >= 1 && r.nextBoolean();
		bed = home && r.nextBoolean();
		furnace = stage >= 1 && r.nextBoolean();
		farm = home && r.nextBoolean();
		chest = home && r.nextBoolean();
		torches = r.nextInt(20);
		lapis = stage >= 3 ? r.nextInt(6) : 0;
		obsidian = stage >= 3 ? r.nextInt(6) : 0;
		xp = r.nextInt(20);
		hp = 8 + r.nextInt(13);
		food = 6 + r.nextInt(15);
	}

	long day() {
		return t % 24000;
	}

	boolean night() {
		long d = day();
		return d >= 13000 && d < 23000;
	}

	boolean dusk() {
		long d = day();
		return d >= 11000 && d < 13000;
	}

	int wood() {
		return planks + 4 * logs;
	}

	float bagFull() {
		int stacks = 0;
		for (int n : new int[] {logs, planks, cobble, coal, rawIron, iron, diamond, foodItems, rawFood, torches, emerald, gold, obsidian, lapis, wool}) {
			stacks += (n + 63) / 64;
		}
		stacks += pick > 0 ? 1 : 0;
		stacks += axe > 0 ? 1 : 0;
		stacks += sword > 0 ? 1 : 0;
		stacks += 3;                                                        // (dirt, seeds, the odd thing)
		return Math.min(1f, stacks / 36f);
	}

	float[] features() {
		float[] f = new float[Mind.F];
		f[Mind.HEALTH] = hp / 20f;
		f[Mind.FOODLVL] = food / 20f;
		f[Mind.NIGHT] = night() ? 1 : 0;
		f[Mind.DUSK] = dusk() ? 1 : 0;
		f[Mind.DANGER] = danger;
		f[Mind.HOSTILES] = Math.min(1f, hostiles / 4f);
		f[Mind.ENEMY] = enemy ? 1 : 0;
		f[Mind.LOGS] = Mind.count(logs, 32);
		f[Mind.PLANKS] = Mind.count(planks, 64);
		f[Mind.COBBLE] = Mind.count(cobble, 64);
		f[Mind.COAL] = Mind.count(coal, 16);
		f[Mind.RAWIRON] = Mind.count(rawIron, 16);
		f[Mind.IRON] = Mind.count(iron, 24);
		f[Mind.DIAMOND] = Mind.count(diamond, 6);
		f[Mind.FOODITEMS] = Mind.count(foodItems, 16);
		f[Mind.TORCHES] = Mind.count(torches, 32);
		f[Mind.EMERALD] = Mind.count(emerald, 10);
		f[Mind.GOLD] = Mind.count(gold, 10);
		f[Mind.PICK] = pick / 4f;
		f[Mind.AXE] = axe / 4f;
		f[Mind.SWORD] = sword / 4f;
		f[Mind.ARMOR] = armor / 4f;
		f[Mind.HOE] = hoe ? 1 : 0;
		f[Mind.BUCKET] = bucket ? 1 : 0;
		f[Mind.BED] = bedItem || bed ? 1 : 0;
		f[Mind.HOME] = home ? 1 : 0;
		f[Mind.FARMED] = farm ? 1 : 0;
		f[Mind.CHEST] = chest ? 1 : 0;
		f[Mind.MINED] = mine ? 1 : 0;
		f[Mind.ENCHANTED] = enchanted ? 1 : 0;
		f[Mind.TREES] = trees ? 1 : 0;
		f[Mind.VILLAGER] = villager ? 1 : 0;
		f[Mind.BAGFULL] = bagFull();
		f[Mind.FRIENDNEAR] = friendNear ? 1 : 0;
		f[Mind.ASKEDFOLLOW] = asked ? 1 : 0;
		f[Mind.FRIENDNEEDS] = friendNeeds ? 1 : 0;
		f[Mind.TRIBEDANGER] = tribeDanger ? 1 : 0;
		f[Mind.TRIBE] = Math.min(1f, tribe / 6f);
		f[Mind.TRUST] = Math.max(0, Math.min(1, (trust + 1) / 2));
		f[Mind.KINDNESS] = kind;
		f[Mind.LOYALTY] = loyal;
		f[Mind.POWER] = power;
		f[Mind.MONEY] = money;
		f[Mind.AGGRESSIVE] = aggressive ? 1 : 0;
		f[Mind.PASSIVE] = passive ? 1 : 0;
		f[Mind.BRAVERY] = brave;
		f[Mind.CURIOSITY] = curious;
		f[Mind.DILIGENCE] = diligent;
		f[Mind.XP] = Mind.count(xp, 30);
		f[Mind.OBSIDIAN] = Mind.count(obsidian, 10);
		f[Mind.LAPIS] = Mind.count(lapis, 10);
		f[Mind.RAWFOOD] = Mind.count(rawFood, 8);
		f[Mind.FURNACE] = furnace ? 1 : 0;
		f[Mind.DRAGON] = dragon ? 1 : 0;
		f[Mind.SHELTERED] = sheltered || home && night() ? 1 : 0;
		f[Mind.HASFRIEND] = hasFriend ? 1 : 0;
		return f;
	}

	// ---------------------------------------------------------------------- what it can do now
	boolean canCraft() {
		return pick == 0 && wood() >= 9 || pick == 1 && cobble >= 3 || axe < pick && (pick <= 2 ? cobble >= 3 || wood() >= 5 : iron >= 3)
				|| sword < pick && (pick <= 2 ? cobble >= 2 : iron >= 2) || pick == 2 && iron >= 3 || pick == 3 && diamond >= 3
				|| armor < 3 && iron >= 5 || armor >= 3 && armor < 4 && diamond >= 5 || !bucket && iron >= 3 && pick >= 2
				|| torches < 16 && coal > 0 && wood() >= 1 || !bedItem && !bed && wool >= 3 && wood() >= 3 || !hoe && wood() >= 4;
	}

	boolean[] allowed() {
		boolean[] a = new boolean[Mind.N];
		a[Mind.REST] = night() || hp < 14;                                  // (in daylight, a player gets on with something)
		a[Mind.WOOD] = true;
		a[Mind.STONE] = pick >= 1;
		a[Mind.CRAFT] = canCraft();
		a[Mind.FOOD] = true;
		a[Mind.EAT] = (food < 16 || food < 19 && hp < 14) && (foodItems > 0 || rawFood > 0);
		a[Mind.SHELTER] = night() || dusk();
		a[Mind.SLEEP] = night() && bed && home;
		a[Mind.HOUSE] = !home && wood() >= 60 || home && wood() >= 160;
		a[Mind.FARM] = !farm && (hoe || wood() >= 4);
		a[Mind.MINE] = pick >= 1;
		a[Mind.SMELT] = (rawIron > 0 || rawFood > 0) && (coal > 0 || logs > 0) && (furnace || cobble >= 8);
		a[Mind.STORE] = bagFull() > 0.6f || !chest && home && wood() >= 8 && bagFull() > 0.3f;
		a[Mind.EXPLORE] = true;
		a[Mind.TRADE] = villager && (emerald > 0 || logs >= 16 || coal >= 15 || foodItems >= 8);
		a[Mind.FOLLOW] = hasFriend;
		a[Mind.HELP] = friendNeeds && (logs >= 4 || foodItems >= 2 || cobble >= 16 || iron >= 2);
		a[Mind.GUARD] = tribeDanger || friendNear && danger > 0.2f;
		a[Mind.FIGHT] = danger > 0.2f || enemy;
		a[Mind.FLEE] = danger > 0.2f || enemy;
		a[Mind.ADVENTURE] = !dragon && pick >= 3 && sword >= 3 && armor >= 2.5f && foodItems >= 8;
		a[Mind.ENCHANT] = !enchanted && lapis >= 1 && xp >= 5 && (table || diamond >= 2 && obsidian >= 4);
		return a;
	}

	// --------------------------------------------------------------------------------- doing
	private int between(int lo, int hi) {
		return lo + r.nextInt(hi - lo + 1);
	}

	private float armorCut() {
		return 1f - 0.18f * armor;
	}

	private void hurt(float dmg) {
		hp -= dmg;
		if (hp <= 0) {
			hp = 0;
			dead = true;
		}
	}

	private void planks(int need) {                                          // it makes planks from logs as it needs them
		while (planks < need && logs > 0) {
			logs--;
			planks += 4;
		}
	}

	/** Do it: how many minutes it took. */
	float step(int o) {
		boolean[] can = allowed();
		long ticks;
		boolean active = true;
		boolean wasNight = night();
		if (!can[o]) {                                                         // (the game never offers it; here it just wastes a moment)
			pass(100, false, false);
			return 100 / 1200f;
		}
		boolean safeHere = false;
		switch (o) {
			case Mind.REST -> {
				ticks = 300;
				active = false;
				safeHere = sheltered || home && night();
			}
			case Mind.WOOD -> {
				ticks = trees ? 1200 : 1800;
				trees = true;
				logs += Math.round(between(4, 9) * (1 + 0.25f * axe));
			}
			case Mind.STONE -> {
				ticks = 1000;
				cobble += between(10, 24);
				if (r.nextFloat() < 0.4f) coal += between(1, 4);
				xp += 1;
			}
			case Mind.CRAFT -> {
				ticks = 200;
				craft();
			}
			case Mind.FOOD -> {
				if (farm && farmGrow >= 3000) {
					ticks = 600;
					foodItems += between(3, 7);
					farmGrow = 0;
				} else {
					ticks = 1200;
					if (!night()) {
						rawFood += between(1, 4);
						if (r.nextFloat() < 0.4f) wool += between(1, 2);
					} else if (r.nextFloat() < 0.3f) {
						rawFood += 1;
					}
				}
			}
			case Mind.EAT -> {
				ticks = 60;
				active = false;
				while (food < 18 && (foodItems > 0 || rawFood > 0)) {
					if (foodItems > 0) {
						foodItems--;
						food = Math.min(20, food + 6);
					} else {
						rawFood--;
						food = Math.min(20, food + 3);
					}
				}
			}
			case Mind.SHELTER -> {
				ticks = 400;
				if (home) sheltered = true;
				else if (cobble >= 10) {
					cobble -= 10;
					sheltered = true;
				} else sheltered = r.nextFloat() < 0.6f;                              // (dirt, digging in)
				safeHere = sheltered;
			}
			case Mind.SLEEP -> {
				long d = day();
				ticks = Math.max(200, 24000 - d + 100);
				active = false;
				sheltered = true;
				safeHere = true;
				hp = Math.min(20, hp + 6);
			}
			case Mind.HOUSE -> {
				ticks = 3600;
				planks(60);
				int use = Math.min(wood(), home ? 160 : 60);
				planks(use);
				planks -= Math.min(planks, use);
				cobble -= Math.min(cobble, 16);
				if (!home && firstHome < 0) firstHome = t;
				home = true;
				if (bedItem) {
					bed = true;
					bedItem = false;
				}
			}
			case Mind.FARM -> {
				ticks = 2400;
				if (!hoe) {
					planks(4);
					planks -= 4;
					hoe = true;
				}
				farm = true;
				if (firstFarm < 0) firstFarm = t;
			}
			case Mind.MINE -> {
				ticks = 3600;
				float more = mine ? 1.2f : 1f;
				mine = true;
				cobble += between(10, 30);
				coal += Math.round(between(2, 8) * more);
				xp += between(3, 8);
				if (pick >= 2) rawIron += Math.round(between(1, 6) * more);
				if (pick >= 3) {
					if (r.nextFloat() < 0.45f) diamond += Math.round(between(1, 3) * more);
					gold += between(0, 3);
					lapis += between(0, 6);
				}
				if (pick >= 4) obsidian += between(0, 5);
				float attack = torches >= 4 ? 0.2f : 0.4f;
				torches = Math.max(0, torches - between(3, 8));
				if (r.nextFloat() < attack) hurt(between(2, 8) * armorCut());
				if (r.nextFloat() < 0.05f) hurt(between(4, 16) * (bucket ? 0.4f : 1f));
				safeHere = true;                                                  // (underground, with torches: the night outside can't get it)
			}
			case Mind.SMELT -> {
				ticks = 600;
				if (!furnace) {
					cobble -= 8;
					furnace = true;
				}
				if (coal > 0) coal--;
				else logs--;
				iron += rawIron;
				rawIron = 0;
				foodItems += rawFood;
				rawFood = 0;
				xp += 1;
			}
			case Mind.STORE -> {
				ticks = 400;
				if (!chest) {
					planks(8);
					planks -= Math.min(planks, 8);
					chest = true;
				}
				cobble = Math.min(cobble, 32);
				logs = Math.min(logs, 16);
				gold = 0;
			}
			case Mind.EXPLORE -> {
				ticks = 1800;
				trees = r.nextFloat() < 0.95f || trees;
				villager = r.nextFloat() < 0.3f;
				if (r.nextFloat() < 0.1f) {
					if (r.nextBoolean()) emerald += between(1, 3);
					else rawIron += between(1, 3);
				}
			}
			case Mind.TRADE -> {
				ticks = 900;
				if (logs >= 16) logs -= 16;
				else if (coal >= 15) coal -= 15;
				else if (foodItems >= 8) foodItems -= 8;
				emerald += between(1, 3);
				trust += 0.05f;
			}
			case Mind.FOLLOW -> {
				ticks = 600;
				active = false;
				friendNear = true;
				safeHere = sheltered;
			}
			case Mind.HELP -> {
				ticks = 300;
				if (foodItems >= 2) foodItems -= 2;
				else if (logs >= 4) logs -= 4;
				else if (iron >= 2) iron -= 2;
				else cobble -= 16;
				friendNeeds = false;
				trust = Math.min(1, trust + 0.2f);
			}
			case Mind.GUARD -> {
				ticks = 600;
				if (r.nextFloat() < 0.5f) hurt(between(0, 6) * armorCut());
				tribeDanger = false;
				danger = 0;
			}
			case Mind.FIGHT -> {
				ticks = 200;
				double odds = sword * 0.6 + armor * 0.5 + hp / 20.0 * 1.5 + power * 0.5 - (enemy ? 2.4 : danger * 2.5);
				if (r.nextDouble() < 1 / (1 + Math.exp(-odds))) {
					danger = 0;
					hostiles = 0;
					enemy = false;
					xp += 3;
					hurt(between(0, 4) * armorCut());
				} else {
					hurt(between(6, 14) * armorCut());
				}
			}
			case Mind.FLEE -> {
				ticks = 200;
				if (r.nextFloat() < 0.8f) {
					danger = 0;
					hostiles = 0;
					enemy = false;
				} else hurt(between(2, 6) * armorCut());
			}
			case Mind.ADVENTURE -> {
				ticks = 24000;
				double p = 0.15 + 0.15 * (pick - 2) + 0.15 * (sword - 2) + 0.08 * armor + 0.15 * brave;
				hurt(between(4, 16) * armorCut());
				if (!dead && r.nextDouble() < p) {
					dragon = true;
					xp += 30;
				}
				foodItems = Math.max(0, foodItems - 8);
			}
			case Mind.ENCHANT -> {
				ticks = 600;
				if (!table) {
					diamond -= 2;
					obsidian -= 4;
					table = true;
				}
				lapis -= Math.min(lapis, 3);
				xp = Math.max(0, xp - 5);
				enchanted = true;
			}
			default -> ticks = 200;
		}
		pass(ticks, active, safeHere);
		if (wasNight && !night()) sheltered = false;
		return ticks / 1200f;
	}

	/** The best thing it can make now (the next tier first, like a player). */
	private void craft() {
		if (pick == 0 && wood() >= 9) {
			planks(9);
			planks -= 9;
			table = true;
			pick = 1;
			axe = Math.max(axe, 1);
			if (firstPick < 0) firstPick = t;
			return;
		}
		if (pick == 1 && cobble >= 3) {
			cobble -= 3;
			pick = 2;
			if (firstStone < 0) firstStone = t;
			return;
		}
		if (pick == 2 && iron >= 3) {
			iron -= 3;
			pick = 3;
			if (firstIron < 0) firstIron = t;
			return;
		}
		if (pick == 3 && diamond >= 3) {
			diamond -= 3;
			pick = 4;
			return;
		}
		if (sword < pick) {
			if (pick <= 2 && cobble >= 2) {
				cobble -= 2;
				sword = Math.max(sword + 1, 2);
				return;
			}
			if (pick >= 3 && iron >= 2) {
				iron -= 2;
				sword = Math.max(sword, 3);
				return;
			}
		}
		if (axe < pick) {
			if (pick <= 2 && cobble >= 3) {
				cobble -= 3;
				axe = 2;
				return;
			}
			if (pick >= 3 && iron >= 3) {
				iron -= 3;
				axe = 3;
				return;
			}
		}
		if (armor < 3 && iron >= 5) {
			iron -= 5;
			armor = Math.min(3, armor + 0.75f);
			return;
		}
		if (armor >= 3 && armor < 4 && diamond >= 5) {
			diamond -= 5;
			armor = Math.min(4, armor + 0.25f);
			return;
		}
		if (!bucket && iron >= 3) {
			iron -= 3;
			bucket = true;
			return;
		}
		if (!bedItem && !bed && wool >= 3 && wood() >= 3) {
			wool -= 3;
			planks(3);
			planks -= 3;
			bedItem = true;
			if (home) {
				bed = true;
				bedItem = false;
			}
			return;
		}
		if (torches < 16 && coal > 0 && wood() >= 1) {
			coal--;
			planks(1);
			planks = Math.max(0, planks - 1);
			torches += 4;
			return;
		}
		if (!hoe && wood() >= 4) {
			planks(4);
			planks -= 4;
			hoe = true;
		}
	}

	/** Time goes by: hunger, healing, the night, what happens around it. */
	private void pass(long ticks, boolean active, boolean safeHere) {
		for (long done = 0; done < ticks && !dead; done += 600) {
			long chunk = Math.min(600, ticks - done);
			t += chunk;
			food = Math.max(0, food - (active ? 1.2f : 0.4f) * chunk / 1200f);
			if (food >= 18) hp = Math.min(20, hp + chunk / 200f);
			if (food <= 0) hurt(chunk / 400f);
			if (farm) farmGrow += chunk;
			boolean safe = safeHere || sheltered || home && night();
			if (night() && !safe && r.nextFloat() < 0.45f * chunk / 600f) {
				hostiles = between(1, 4);
				hurt(between(2, 7) * armorCut());
			}
		}
		// what's around it now
		boolean safe = sheltered || home && night();
		if (night() && !safe && r.nextFloat() < 0.6f) {
			danger = 0.3f + 0.6f * r.nextFloat();
			hostiles = between(1, 4);
		} else if (r.nextFloat() < 0.08f) {
			danger = 0.25f + 0.3f * r.nextFloat();                              // a creeper in the daytime, a spider...
			hostiles = 1;
		} else {
			danger = 0;
			hostiles = 0;
		}
		if (!enemy && r.nextFloat() < 0.02f + (tribe > 0 ? 0.02f : 0)) enemy = true;
		else if (enemy && r.nextFloat() < 0.3f) enemy = false;
		if (hasFriend) {
			if (!asked && r.nextFloat() < 0.08f) asked = true;
			else if (asked && r.nextFloat() < 0.12f) asked = false;
			if (!asked && r.nextFloat() < 0.3f) friendNear = r.nextBoolean();
		}
		if ((hasFriend || tribe > 0) && !friendNeeds && r.nextFloat() < 0.06f) friendNeeds = true;
		if (tribe > 0 && !tribeDanger && r.nextFloat() < 0.04f) tribeDanger = true;
		trust = Math.max(-1, Math.min(1, trust + (float) r.nextGaussian() * 0.03f));
	}

	boolean over() {
		return dead || t > 5 * 24000L;
	}

	// ------------------------------------------------------------------------------- training
	public static void main(String[] args) throws Exception {
		String out = args.length > 0 ? args[0] : "mind.bin";
		long steps = args.length > 1 ? Long.parseLong(args[1]) : 200_000;
		Random r = new Random(7);
		Mind mind = Mind.standard(11);
		float eps0 = 1f;
		if (args.length > 2) {                                                 // go on from a mind already trained (fine-tuning)
			try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(args[2])))) {
				mind = Mind.load(in);
			}
			eps0 = 0.3f;
			System.out.println("going on from " + args[2] + " (" + mind.updates + " learning steps)");
		}
		mind.think = false;                                                    // (quick while it trains)
		SimLife life = new SimLife(r);
		double lifeReward = 0, sumRewards = 0;
		int lives = 0, deaths = 0, pickN = 0, ironN = 0, homeN = 0, diamondN = 0, farmN = 0;
		int[] picks = new int[Mind.N];
		long t0 = System.currentTimeMillis();
		for (long s = 1; s <= steps; s++) {
			if (life.over()) {
				lives++;
				if (life.dead) deaths++;
				if (life.pick >= 1) pickN++;
				if (life.pick >= 3) ironN++;
				if (life.home) homeN++;
				if (life.diamond > 0 || life.pick >= 4) diamondN++;
				if (life.farm) farmN++;
				sumRewards += lifeReward;
				lifeReward = 0;
				life.reset();
			}
			float eps = (float) Math.max(0.05, eps0 - eps0 * s / (0.5 * steps));
			float[] f = life.features();
			boolean[] can = life.allowed();
			Mind.Choice c = mind.choose(f, can, 1.6f - 1.2f * life.brave, eps, r);
			float minutes = life.step(c.option);
			float[] g = life.features();
			float rew = Mind.reward(f, g, c.option, minutes), harm = Mind.harm(f, g, life.dead);
			lifeReward += rew;
			picks[c.option]++;
			mind.remember(f, c.option, rew, harm, g, life.dead, minutes, Mind.bits(life.allowed()));
			if (mind.remembered() > 2000) mind.learn(32, r);
			if (s % 20000 == 0) {
				StringBuilder h = new StringBuilder();
				int total = 0;
				for (int p : picks) total += p;
				for (int i = 0; i < Mind.N; i++) if (picks[i] * 100 >= total) h.append(Mind.OPTIONS[i]).append(' ').append(picks[i] * 100 / total).append("% ");
				System.out.printf(Locale.ROOT, "%7d steps  eps %.2f  lives %d (died %d)  reward/life %.1f  pickaxe %d%% iron %d%% home %d%% farm %d%% diamonds %d%%  [%s] %ds%n",
						s, eps, lives, deaths, lives == 0 ? 0 : sumRewards / lives, pct(pickN, lives), pct(ironN, lives), pct(homeN, lives),
						pct(farmN, lives), pct(diamondN, lives), h.toString().trim(), (System.currentTimeMillis() - t0) / 1000);
				lives = deaths = pickN = ironN = homeN = diamondN = farmN = 0;
				sumRewards = 0;
				java.util.Arrays.fill(picks, 0);
			}
		}
		mind.think = true;
		evaluate(mind, new Random(99), 300);
		try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
			mind.save(d);
		}
		System.out.println("saved " + out);
	}

	private static int pct(int n, int of) {
		return of == 0 ? 0 : n * 100 / of;
	}

	/** Lives with no chance-taking: how far does it get, how soon (in Minecraft days), and does it survive? */
	static void evaluate(Mind mind, Random r, int n) {
		SimLife life = new SimLife(r);
		int deaths = 0, pickN = 0, stoneN = 0, ironN = 0, homeN = 0, diamondN = 0, farmN = 0, dragonN = 0;
		double tPick = 0, tStone = 0, tIron = 0, tHome = 0;
		int[] picks = new int[Mind.N];
		for (int i = 0; i < n; i++) {
			life.reset();
			while (!life.midLifeFree()) life.reset();
			while (!life.over()) {
				Mind.Choice c = mind.choose(life.features(), life.allowed(), 1.6f - 1.2f * life.brave, 0f, r);
				picks[c.option]++;
				life.step(c.option);
			}
			if (life.dead) deaths++;
			if (life.firstPick >= 0) {
				pickN++;
				tPick += life.firstPick / 24000.0;
			}
			if (life.firstStone >= 0) {
				stoneN++;
				tStone += life.firstStone / 24000.0;
			}
			if (life.firstIron >= 0) {
				ironN++;
				tIron += life.firstIron / 24000.0;
			}
			if (life.firstHome >= 0) {
				homeN++;
				tHome += life.firstHome / 24000.0;
			}
			if (life.diamond > 0 || life.pick >= 4) diamondN++;
			if (life.farm) farmN++;
			if (life.dragon) dragonN++;
		}
		System.out.printf(Locale.ROOT, "evaluation, %d fresh lives of 5 days: died %d%%, wooden pickaxe %d%% (day %.2f), stone %d%% (day %.2f), iron %d%% (day %.2f), "
						+ "home %d%% (day %.2f), farm %d%%, diamonds %d%%, dragon %d%%%n", n, pct(deaths, n), pct(pickN, n), tPick / Math.max(1, pickN),
				pct(stoneN, n), tStone / Math.max(1, stoneN), pct(ironN, n), tIron / Math.max(1, ironN), pct(homeN, n), tHome / Math.max(1, homeN),
				pct(farmN, n), pct(diamondN, n), pct(dragonN, n));
		StringBuilder h = new StringBuilder("  choices:");
		int total = 0;
		for (int p : picks) total += p;
		for (int i = 0; i < Mind.N; i++) if (picks[i] > 0) h.append(' ').append(Mind.OPTIONS[i]).append(' ').append(Math.round(picks[i] * 1000.0 / total) / 10.0).append('%');
		System.out.println(h);
	}

	/** (For the evaluation: a fresh start, not one of the lives already under way.) */
	boolean midLifeFree() {
		return pick == 0 && logs == 0 && !home;
	}
}
