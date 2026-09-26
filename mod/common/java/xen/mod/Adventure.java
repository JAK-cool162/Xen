package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.Vec3;
import xen.mod.core.Action;

import java.util.Locale;

/**
 * The long way to the Ender Dragon, step by step like a player's first time:
 * <ol>
 *   <li><b>Gear:</b> an iron pickaxe and sword and some armor (its own progress gets it there), a bow and arrows if it
 *   can make them (string from spiders, feathers from chickens, flint from gravel).</li>
 *   <li><b>Blaze rods:</b> a trip to a Nether fortress (see {@link Nether}).</li>
 *   <li><b>Ender pearls:</b> endermen, at night.</li>
 *   <li><b>Eyes of ender:</b> blaze powder and pearls, at a crafting table.</li>
 *   <li><b>The stronghold:</b> it throws an eye and watches where it flies. A young Xen follows the eyes: walk that way,
 *   throw another, and another, until one flies down into the ground. A Xen of the fourth generation (or one that was
 *   taught) works it out: two throws a way apart, and where the two lines cross is the stronghold; it walks straight
 *   there. Then it digs down and searches the stronghold for the portal room.</li>
 *   <li><b>The portal:</b> an eye in every frame, and in.</li>
 *   <li><b>The End:</b> see {@link Dragon}.</li>
 * </ol>
 * It gets ready first (food, blocks for towers and bridges, a water bucket), and it may take many days: its other goals
 * go on meanwhile (sleep, food, shelter).
 */
final class Adventure {
	enum Stage { GEAR, BOW, RODS, PEARLS, EYES, READY, FIND, PORTAL, END, DONE }

	static final int EYES = 14, RODS = 7;

	private final Companion c;
	boolean on;
	Stage stage;
	private long stageSince, lastLine;
	private boolean skipBow;
	private final java.util.Random random = new java.util.Random();

	Adventure(Companion c) {
		this.c = c;
	}

	private long now() {
		return c.player.level().getGameTime();
	}

	private int count(String id) {
		int n = 0;
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().equals(id)) n += inv.getItem(i).getCount();
		return n;
	}

	private boolean has(String suffix) {
		var inv = c.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (BuiltInRegistries.ITEM.getKey(inv.getItem(i).getItem()).getPath().endsWith(suffix)) return true;
		return false;
	}

	private void line(String s) {
		if (now() - lastLine < 600) return;
		lastLine = now();
		c.chatter(s, true);
	}

	/** Start (asked, or its dream): the plan in words, or why not now. */
	String start() {
		if (c.player.isCreative()) return "You won't: in creative there's no adventure.";
		on = true;
		stage = null;
		stageSince = now();
		Stage s = stage();
		c.journal("does", "adventure: beat the Ender Dragon (starting at " + s.name().toLowerCase(Locale.ROOT) + ")");
		return "You will go for the Ender Dragon, like a real player: " + switch (s) {
			case GEAR -> "first better tools and armor, then blaze rods from the Nether, ender pearls, eyes of ender, the stronghold and the End.";
			case BOW -> "first a bow and arrows, then blaze rods from the Nether, ender pearls, eyes of ender, the stronghold and the End.";
			case RODS -> "first blaze rods from a Nether fortress, then ender pearls, eyes of ender, the stronghold and the End.";
			case PEARLS -> "first ender pearls from endermen, then eyes of ender, the stronghold and the End.";
			case EYES, READY -> "you will make eyes of ender and get ready, then find the stronghold and go to the End.";
			case FIND -> "you will throw eyes of ender to find the stronghold.";
			case PORTAL -> "you will fill the End portal with eyes and jump in.";
			case END, DONE -> "you are already there.";
		};
	}

	void stop() {
		on = false;
		c.nether.cancel();
	}

	/** Where it is on the way, from what it has and where it is (so it picks up again after a restart). */
	Stage stage() {
		String dim = Places.dim(c.player.level());
		if (dim.equals("the_end")) return Stage.END;
		if (c.goals.dragonDown) return Stage.DONE;
		int eyes = count("ender_eye"), powder = count("blaze_powder") + 2 * count("blaze_rod"), pearls = count("ender_pearl");
		boolean framesKnown = c.places.get("end portal", "overworld") != null;
		if (framesKnown && eyes > 0 && dim.equals("overworld")) return Stage.PORTAL;
		if (eyes >= EYES || eyes >= 3 && c.places.get("stronghold", "overworld") != null) return prepared() ? Stage.FIND : Stage.READY;
		boolean armor = c.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() != net.minecraft.world.item.Items.AIR
				|| c.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem() != net.minecraft.world.item.Items.AIR;
		if (c.crafter.pickTier() < 3 || !(has("iron_sword") || has("diamond_sword") || has("netherite_sword")) || !armor) return Stage.GEAR;
		if (!skipBow && !c.hands.hasBow() && c.hands.arrows() < 16) return Stage.BOW;
		if (eyes + powder < EYES) return Stage.RODS;
		if (eyes + pearls < EYES) return Stage.PEARLS;
		return Stage.EYES;
	}

	/** Ready for the End: food, blocks to tower and bridge with, a water bucket. */
	private boolean prepared() {
		var items = c.items();
		return items.getOrDefault("food", 0) >= 10 && items.getOrDefault("cobblestone", 0) + items.getOrDefault("dirt", 0) >= 128;
	}

	/** The next step (null: its other goals decide this moment, like eating and sleeping, or a chore it just started). */
	Action next() {
		if (!on || c.player.isCreative()) return null;
		if (c.chores.busy() || c.builder.busy() || c.crafter.hasOrder() || c.storage.busy()) return null;
		Stage s = stage();
		if (s != stage) {
			stage = s;
			stageSince = now();
			c.journal("does", "adventure stage: " + s.name().toLowerCase(Locale.ROOT));
		}
		if (c.nether.busy()) return c.nether.next();
		switch (s) {
			case GEAR -> {
				line("First, iron tools and armor.");
				gear();
				return null;
			}
			case BOW -> {
				return bow();
			}
			case RODS -> {
				int rods = Math.max(1, (EYES - count("ender_eye") - count("blaze_powder") + 1) / 2 - count("blaze_rod"));
				String plan = c.nether.go("blaze", Math.min(RODS, rods + count("blaze_rod")));
				if (!plan.startsWith("You will")) {
					line(xen.mod.talk.Chat.firstPerson(plan));
					stop();
					return null;
				}
				c.chatter(xen.mod.talk.Chat.firstPerson(plan), true);
				return c.nether.next();
			}
			case PEARLS -> {
				if (!c.player.level().isDarkOutside() && !c.nether.inNether()) {
					line("Endermen come out at night. I'll hunt them then.");
					return null;                                                    // (daytime: its other goals)
				}
				String plan = c.chores.slay("enderman", "ender_pearl", EYES - count("ender_eye") - count("ender_pearl"), "ender pearls");
				c.chores.own = true;
				c.journal("does", plan);
				return null;
			}
			case EYES -> {
				if (count("blaze_powder") < 1 && count("blaze_rod") > 0) c.crafter.orderRecipe("blaze_powder", Math.min(count("blaze_rod"), 7));
				else if (count("ender_pearl") > 0 && count("blaze_powder") > 0) c.crafter.orderRecipe("ender_eye", Math.min(count("ender_pearl"), count("blaze_powder")));
				c.goals.instant = "making eyes of ender";
				return null;
			}
			case READY -> {
				var items = c.items();
				if (items.getOrDefault("food", 0) < 10) {
					line("I need more food for the End.");
					c.chores.hunt(6);
					c.chores.own = true;
				} else {
					line("I'll need lots of blocks in the End, for towers and bridges.");
					c.chores.gather("stone", 64);
					c.chores.own = true;
				}
				return null;
			}
			case FIND -> {
				return find();
			}
			case PORTAL -> {
				return portal();
			}
			case END -> {
				return null;                                                         // the dragon fight takes over there
			}
			case DONE -> {
				c.say("My adventure is done: the Ender Dragon is beaten!");
				on = false;
				return null;
			}
		}
		return null;
	}

	/** Iron: mining and smelting (its own progress), armor from the ingots. */
	private void gear() {
		int iron = count("iron_ingot");
		if (iron >= 5 && !has("_chestplate") && !has("_helmet")) c.crafter.orderRecipe(iron >= 8 ? "iron_chestplate" : "iron_helmet", 1);
		else if (iron >= 2 && !(has("iron_sword") || has("diamond_sword"))) c.crafter.orderRecipe("iron_sword", 1);
		else if (count("raw_iron") >= 3) c.chores.smelt();
		else if (c.crafter.pickTier() >= 2) c.chores.mine(16, "iron", 8);
		else c.chores.gather("stone", 8);
		c.chores.own = true;
	}

	/** A bow (3 string, 3 sticks) and arrows (flint, a stick and a feather make 4). Five minutes, or it goes without. */
	private Action bow() {
		if (now() - stageSince > 20 * 60 * 5) {
			line("No bow then. I'll manage without.");
			skipBow = true;
			return null;
		}
		if (!c.hands.hasBow() && count("bow") == 0) {
			if (count("string") >= 3) {
				c.crafter.orderRecipe("bow", 1);
				return null;
			}
			if (!c.player.level().isDarkOutside()) {
				line("I need string for a bow. Spiders come out at night.");
				return null;
			}
			c.chores.slay("spider", "string", 3 - count("string"), "string for a bow");
			c.chores.own = true;
			return null;
		}
		if (count("flint") == 0) {
			c.chores.mineFor("gravel", "flint", 4, "flint for arrows");
			c.chores.own = true;
			return null;
		}
		if (count("feather") == 0) {
			c.chores.slay("chicken", "feather", 4, "feathers for arrows");
			c.chores.own = true;
			return null;
		}
		c.crafter.orderRecipe("arrow", Math.min(count("flint"), count("feather")) * 4);
		return null;
	}

	// ------------------------------------------------------------------------------ finding the stronghold
	private EyeOfEnder eye;
	private Vec3 thrownAt, eyeFirst;
	private int eyeTicks;
	private Vec3 lineA, dirA, walkTo;
	private boolean down;

	/** Throws, watches, walks; digs down where an eye goes into the ground; searches for the portal room. */
	private Action find() {
		ServerLevel level = (ServerLevel) c.player.level();
		if (!c.nether.inNether() && !Places.dim(level).equals("overworld")) return null;
		if (c.nether.inNether()) {
			c.nether.go("home", 0);
			return c.nether.next();
		}
		BlockPos stronghold = c.places.get("stronghold", "overworld");
		if (stronghold != null && c.player.blockPosition().distSqr(stronghold.atY(c.player.getBlockY())) < 14 * 14 || down) return search(level, stronghold);
		if (eye != null) return watchEye(level);
		ItemEntity dropped = droppedEye(level);
		if (dropped != null) return c.walkTo(dropped.position());
		if (walkTo != null && c.player.position().distanceTo(walkTo) > 6) {
			c.goals.instant = "following the eye of ender";
			c.run(true);
			return c.walkTo(walkTo);
		}
		walkTo = null;
		if (count("ender_eye") <= 12 && c.places.get("stronghold", "overworld") == null && count("ender_eye") < 3) {
			line("I'm running out of eyes...");
		}
		if (count("ender_eye") == 0) {
			stop();
			return null;
		}
		if (!c.player.onGround()) return Action.IDLE;
		c.goals.instant = "throwing an eye of ender";
		c.hands.useAtAir(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("ender_eye"), c.player.getYRot(), -30);
		thrownAt = c.player.position();
		eyeTicks = 0;
		eyeFirst = null;
		c.acted = true;
		for (EyeOfEnder e : level.getEntitiesOfClass(EyeOfEnder.class, c.player.getBoundingBox().inflate(4))) eye = e;
		if (eye == null) line("Hmm, the eye didn't fly.");
		return Action.IDLE;
	}

	/** Its eyes follow the eye of ender: which way it flies, and whether it goes down into the ground (close!). */
	private Action watchEye(ServerLevel level) {
		c.goals.instant = "watching the eye of ender fly";
		eyeTicks++;
		c.hands.watching = null;
		if (eye.isAlive()) c.hands.face(eye.position());
		if (eyeTicks == 3) eyeFirst = eye.position();
		if (eyeTicks < 24 && eye.isAlive()) {
			c.acted = true;
			return Action.IDLE;
		}
		Vec3 now = eye.isAlive() ? eye.position() : eyeFirst;
		eye = null;
		if (eyeFirst == null || now == null) return Action.IDLE;
		Vec3 d = now.subtract(eyeFirst);
		double flat = Math.hypot(d.x, d.z);
		if (d.y < -0.5 && flat < 6) {                                               // it went down: the stronghold is right here
			c.places.remember("stronghold", BlockPos.containing(thrownAt.add(d.x * 2, 0, d.z * 2)));
			c.chatter("The eye went down! The stronghold is right under us.", true);
			down = true;
			return Action.IDLE;
		}
		if (flat < 0.2) return Action.IDLE;
		Vec3 dir = new Vec3(d.x / flat, 0, d.z / flat);
		if (c.knowledge.knows("triangulate")) {                                      // two lines cross where it is
			if (lineA == null) {
				lineA = thrownAt;
				dirA = dir;
				Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(random.nextBoolean() ? 1 : -1);
				walkTo = thrownAt.add(side.scale(64));
				c.chatter("It flew that way. Now I'll walk to the side and throw another: where the two lines cross is the stronghold.", true);
				return Action.IDLE;
			}
			double cross = dirA.x * dir.z - dirA.z * dir.x;
			if (Math.abs(cross) > 0.05) {
				Vec3 w = thrownAt.subtract(lineA);
				double t = (w.x * dir.z - w.z * dir.x) / cross;
				if (t > 0 && t < 3000) {
					Vec3 at = lineA.add(dirA.scale(t));
					BlockPos sh = BlockPos.containing(at.x, 40, at.z);
					c.places.remember("stronghold", sh);
					walkTo = new Vec3(at.x, c.player.getY(), at.z);
					c.chatter(String.format(Locale.ROOT, "The lines cross at %d, %d. The stronghold is %d blocks away!", sh.getX(), sh.getZ(),
							(int) Math.sqrt(sh.distSqr(c.player.blockPosition().atY(40)))), true);
					lineA = null;
					return Action.IDLE;
				}
			}
			lineA = null;                                                            // too close to parallel: once more
		}
		walkTo = thrownAt.add(dir.scale(c.knowledge.knows("triangulate") ? 40 : 90));   // follow it
		c.chatter(c.pick3("That way!", "Follow the eye!", "It's pointing over there."), false);
		return Action.IDLE;
	}

	private ItemEntity droppedEye(ServerLevel level) {
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, c.player.getBoundingBox().inflate(16),
				x -> BuiltInRegistries.ITEM.getKey(x.getItem().getItem()).getPath().equals("ender_eye"))) return e;
		return null;
	}

	private Vec3 searchTo;
	private long searchSince;

	/** Down into the stronghold, and through it looking for the portal room (the frames around a pool of lava). */
	private Action search(ServerLevel level, BlockPos stronghold) {
		if (stronghold == null) stronghold = c.player.blockPosition();
		BlockPos frame = c.nether.lookFor(level, "end_portal_frame", 40);
		if (frame != null) {
			c.places.remember("end portal", frame);
			c.chatter("The portal room! I found the End portal!", true);
			down = false;
			return Action.IDLE;
		}
		c.goals.instant = "searching the stronghold";
		boolean inside = false;
		for (BlockPos q : BlockPos.betweenClosed(c.player.blockPosition().offset(-3, -2, -3), c.player.blockPosition().offset(3, 3, 3))) {
			String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath();
			if (n.contains("stone_bricks")) {
				inside = true;
				break;
			}
		}
		if (!inside && c.player.getY() > 20) {                                     // not there yet: a staircase down
			return c.walkTo(new Vec3(stronghold.getX() + 0.5 + (random.nextInt(9) - 4), 30, stronghold.getZ() + 0.5 + (random.nextInt(9) - 4)));
		}
		if (searchTo == null || c.player.position().distanceTo(searchTo) < 3 || now() - searchSince > 20 * 25) {
			double a = random.nextDouble() * Math.PI * 2, r = 12 + random.nextInt(24);
			searchTo = new Vec3(stronghold.getX() + Math.cos(a) * r, c.player.getY() + random.nextInt(9) - 4, stronghold.getZ() + Math.sin(a) * r);
			searchSince = now();
		}
		if (now() - stageSince > 20 * 60 * 15) {
			line("I can't find the portal room... I'll throw another eye.");
			down = false;
			c.places.forget("stronghold");
			stageSince = now();
		}
		return c.walkTo(searchTo);
	}

	// ------------------------------------------------------------------------------ the portal
	/** An eye in each empty frame, then into the portal. */
	private Action portal() {
		ServerLevel level = (ServerLevel) c.player.level();
		BlockPos known = c.places.get("end portal", "overworld");
		if (known == null) return null;
		if (c.player.blockPosition().distSqr(known) > 24 * 24) {
			c.goals.instant = "going to the End portal";
			return c.walkTo(Vec3.atBottomCenterOf(known.above()));
		}
		BlockPos open = Nether.portalNear(level, known, 6);
		if (open != null && BuiltInRegistries.BLOCK.getKey(level.getBlockState(open).getBlock()).getPath().equals("end_portal")) {
			if (!prepared()) {
				line("The portal is open. Let me get ready first.");
				return null;
			}
			c.chatter(c.pick3("Here goes nothing!", "To the End!", "Wish me luck!"), true);
			return c.nether.enter(open);
		}
		BlockPos empty = null;
		for (BlockPos q : BlockPos.betweenClosed(known.offset(-6, -2, -6), known.offset(6, 2, 6))) {
			var st = level.getBlockState(q);
			if (!(st.getBlock() instanceof EndPortalFrameBlock) || st.getValue(EndPortalFrameBlock.HAS_EYE)) continue;
			if (empty == null || q.distSqr(c.player.blockPosition()) < empty.distSqr(c.player.blockPosition())) empty = q.immutable();
		}
		if (empty == null) return Action.IDLE;                                       // (all filled: the portal opens)
		if (count("ender_eye") == 0) {
			line("I need more eyes of ender for the portal.");
			stop();
			return null;
		}
		if (c.player.getEyePosition().distanceTo(Vec3.atCenterOf(empty)) > c.player.blockInteractionRange() - 0.7) {
			c.goals.instant = "going to an empty portal frame";
			return c.walkTo(Vec3.atBottomCenterOf(empty.above()));
		}
		c.goals.instant = "putting an eye in the portal frame";
		c.hands.useWith(empty, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("ender_eye"));
		c.acted = true;
		return Action.PLACE;
	}

	String describe() {
		if (!on || stage == null) return "";
		return "You are on an adventure to beat the Ender Dragon; right now: " + switch (stage) {
			case GEAR -> "getting iron tools and armor";
			case BOW -> "making a bow and arrows";
			case RODS -> "getting blaze rods in the Nether";
			case PEARLS -> "hunting endermen for ender pearls";
			case EYES -> "making eyes of ender";
			case READY -> "getting food and blocks ready for the End";
			case FIND -> "finding the stronghold with eyes of ender";
			case PORTAL -> "filling the End portal with eyes";
			case END -> "fighting in the End";
			case DONE -> "done: the dragon is beaten";
		} + ".";
	}
}
