package xen.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gameplay logs, written the way the gameplay recorder writes them (JSONL, its format 1.2), one file for each Xen and
 * one for each player (the gameplayLog setting: off, xens, players or both), in config/xen/gameplay_logs:
 * <ul>
 *   <li>state, 4 a second (20 in a fight): where it is, how it moves, its health and food, the keys it holds, its hands
 *   and armor, what it looks at, the blocks at its feet and head, the creatures about, and twice a second its view (14
 *   by 24 sight lines, 192 blocks deep); its inventory every 10 seconds;</li>
 *   <li>events: session_start, damage_taken, death, swing (a hit at a creature or a block), block_break, chat_out,
 *   chat_in.</li>
 * </ul>
 * xen/ex1 reads them like the recorder's own (python -m xen.ex1.train config/xen/gameplay_logs/player_*.jsonl), and
 * python -m xen.ex1.compare puts a Xen's play next to a player's. A player whose play is logged is told so when it
 * starts. Like the recorder, it logs only what that one would know: no ore through walls.
 */
final class GameplayLog {
	/** The recorder's format these files follow. */
	static final String FORMAT = "1.2.0";
	static final int EVERY = 5, IN_FIGHT = 1, VISION_EVERY = 10, INVENTORY_EVERY = 200, FIGHT_TICKS = 60;
	static final int ROWS = 14, COLS = 24;
	static final double FOV_V = 90, FOV_H = 130.626, NEAR = 64, FAR = 192, AROUND = 24;
	/** A new file after this much (a long session doesn't make one huge file). */
	static final long MAX_BYTES = 200L << 20;

	private final XenMod mod;
	private final Path dir;
	private final Map<UUID, Log> logs = new HashMap<>();
	private long troubleAt;

	/** One logged player or Xen: its file and its clocks. */
	private static final class Log {
		final boolean xen;
		final String name;
		BufferedWriter out;
		Path file;
		long i, ticks, lastState = -1000, lastVision = -1000, lastInventory = -100_000, fightUntil, bytes;
		BlockPos digging;
		long digStart;
		float health = 20;

		Log(boolean xen, String name) {
			this.xen = xen;
			this.name = name;
		}
	}

	GameplayLog(XenMod mod, Path dir) {
		this.mod = mod;
		this.dir = dir;
	}

	private boolean wants(ServerPlayer p) {
		String mode = mod.config.gameplayLog;
		if (p.isSpectator()) return false;
		if (p instanceof XenPlayer x) return x.companion != null && (mode.equals("xens") || mode.equals("both"));
		return mode.equals("players") || mode.equals("both");
	}

	/** Every server tick: a state for each one it logs when it's time, and new and finished files. */
	void tick(MinecraftServer s) {
		if (mod.config.gameplayLog.equals("off") && logs.isEmpty()) return;
		Set<UUID> here = new HashSet<>();
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (!wants(p)) continue;
			here.add(p.getUUID());
			Log log = logs.get(p.getUUID());
			if (log == null || log.bytes > MAX_BYTES) {
				if (log != null) close(log);
				log = open(p);
				logs.put(p.getUUID(), log);
			}
			if (log.out == null) continue;
			log.ticks++;
			if (log.ticks - log.lastState >= (log.ticks < log.fightUntil ? IN_FIGHT : EVERY)) {
				try {
					state(p, log);
				} catch (RuntimeException e) {
					trouble("couldn't write " + log.name + "'s state", e);
				}
			}
		}
		logs.entrySet().removeIf(e -> {
			if (here.contains(e.getKey())) return false;
			close(e.getValue());
			return true;
		});
		if (s.getTickCount() % 100 == 0) for (Log l : logs.values()) flush(l);
	}

	/** The server stops: every file written out and closed. */
	void closeAll() {
		for (Log l : logs.values()) close(l);
		logs.clear();
	}

	private Log open(ServerPlayer p) {
		boolean isXen = p instanceof XenPlayer;
		Log log = new Log(isXen, p.getName().getString());
		try {
			Files.createDirectories(dir);
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			log.file = dir.resolve((isXen ? "xen_" : "player_") + log.name.replaceAll("[^A-Za-z0-9_.-]", "_") + "_" + stamp + ".jsonl");
			if (Files.exists(log.file)) log.file = log.file.resolveSibling(log.file.getFileName().toString().replace(".jsonl", "_" + p.getId() + ".jsonl"));
			log.out = Files.newBufferedWriter(log.file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			trouble("couldn't start a gameplay log for " + log.name, e);
			log.out = null;
			return log;
		}
		JsonObject o = event(log, "session_start");
		o.addProperty("mod_version", FORMAT);
		o.addProperty("written_by", xen.mod.core.Mind.VERSION);
		o.addProperty("source", isXen ? "xen" : "player");
		o.addProperty("who", log.name);
		o.addProperty("interval_ticks", EVERY);
		o.addProperty("fight_interval_ticks", IN_FIGHT);
		write(log, o);
		JsonObject m = event(log, "game_message");
		m.addProperty("text", "Xen gameplay log: recording ON [" + (isXen ? "xen" : "player") + "]");
		write(log, m);
		XenMod.LOG.info("Gameplay log for {}: {}", log.name, log.file.getFileName());
		if (!isXen) p.sendSystemMessage(Component.literal("[Xen] Your gameplay is being logged on this server, to train Xen (setting gameplayLog).").withStyle(ChatFormatting.GRAY));
		return log;
	}

	private void close(Log log) {
		if (log.out == null) return;
		try {
			log.out.close();
		} catch (IOException e) {
			trouble("couldn't finish " + log.name + "'s gameplay log", e);
		}
		log.out = null;
	}

	private void flush(Log log) {
		if (log.out == null) return;
		try {
			log.out.flush();
		} catch (IOException e) {
			trouble("couldn't write " + log.name + "'s gameplay log", e);
		}
	}

	/** A logging hook that can never get in the way of the game (an attack, a hit, chat). */
	void quietly(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			trouble("stumbled", e);
		}
	}

	private void trouble(String what, Exception e) {
		long now = System.currentTimeMillis();
		if (now - troubleAt < 60_000) return;
		troubleAt = now;
		XenMod.LOG.warn("Gameplay log: {} ({})", what, e.toString());
	}

	private static JsonObject head(Log log, String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("i", log.i);
		o.addProperty("tick", log.ticks);
		o.addProperty("t_ms", System.currentTimeMillis());
		o.addProperty("tag", log.xen ? "xen" : "player");
		return o;
	}

	private static JsonObject event(Log log, String kind) {
		JsonObject o = head(log, "event");
		o.addProperty("kind", kind);
		return o;
	}

	private void write(Log log, JsonObject o) {
		if (log == null || log.out == null) return;
		String line = o.toString();
		try {
			log.out.write(line);
			log.out.write('\n');
			log.bytes += line.length() + 1;
		} catch (IOException e) {
			trouble("couldn't write " + log.name + "'s gameplay log", e);
		}
	}

	private Log of(Entity e) {
		return e instanceof ServerPlayer p ? logs.get(p.getUUID()) : null;
	}

	// ------------------------------------------------------------------------------------------- what it writes

	private static String id(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}

	private static String id(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static double r3(double x) {
		return Math.round(x * 1000) / 1000.0;
	}

	private static JsonArray vec(double... v) {
		JsonArray a = new JsonArray();
		for (double x : v) a.add(r3(x));
		return a;
	}

	private static JsonObject item(ItemStack s) {
		JsonObject o = new JsonObject();
		if (s.isEmpty()) {
			o.addProperty("id", "empty");
			return o;
		}
		o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
		o.addProperty("count", s.getCount());
		if (s.isDamageableItem()) o.addProperty("durability", s.getMaxDamage() - s.getDamageValue());
		return o;
	}

	/** The keys it holds: a player's from its game client, a Xen's from its hands. */
	private static JsonObject input(ServerPlayer p) {
		boolean f, b, l, r, j, sneak, sprint;
		if (p instanceof XenPlayer x) {
			f = p.zza > 0.05f;
			b = p.zza < -0.05f;
			l = p.xxa > 0.05f;
			r = p.xxa < -0.05f;
			j = x.jumpKey();
			sneak = p.isShiftKeyDown();
			sprint = p.isSprinting();
		} else {
			var in = p.getLastClientInput();
			f = in.forward();
			b = in.backward();
			l = in.left();
			r = in.right();
			j = in.jump();
			sneak = in.shift();
			sprint = in.sprint();
		}
		JsonObject o = new JsonObject();
		o.addProperty("forward", f);
		o.addProperty("back", b);
		o.addProperty("left", l);
		o.addProperty("right", r);
		o.addProperty("jump", j);
		o.addProperty("sneak", sneak);
		o.addProperty("sprint", sprint);
		o.addProperty("attack", Compat.swinging(p));
		o.addProperty("use", p.isUsingItem());
		return o;
	}

	/** What its crosshair is on: a creature, a block, or nothing (within its reach, like a game client's). */
	private static JsonObject lookingAt(ServerPlayer p, ServerLevel level) {
		Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
		double reachB = p.blockInteractionRange(), reachE = p.entityInteractionRange();
		BlockHitResult block = level.clip(new ClipContext(eye, eye.add(look.scale(reachB)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		double blockD = block.getType() == HitResult.Type.BLOCK ? block.getLocation().distanceTo(eye) : Double.MAX_VALUE;
		Vec3 end = eye.add(look.scale(Math.min(reachE, blockD)));
		Entity seen = null;
		double seenD = Double.MAX_VALUE;
		for (Entity e : level.getEntities(p, p.getBoundingBox().expandTowards(look.scale(reachE)).inflate(1), e -> !e.isSpectator() && e.isPickable())) {
			var hit = e.getBoundingBox().inflate(e.getPickRadius()).clip(eye, end);
			if (hit.isPresent() && hit.get().distanceTo(eye) < seenD) {
				seenD = hit.get().distanceTo(eye);
				seen = e;
			}
		}
		JsonObject o = new JsonObject();
		if (seen != null) {
			o.addProperty("type", "entity");
			o.addProperty("id", id(seen));
			o.addProperty("distance", r3(seenD));
			o.addProperty("hostile", seen instanceof Enemy);
			if (seen instanceof LivingEntity le) o.addProperty("health", r3(le.getHealth()));
		} else if (block.getType() == HitResult.Type.BLOCK) {
			BlockPos at = block.getBlockPos();
			o.addProperty("type", "block");
			o.addProperty("id", id(level.getBlockState(at)));
			JsonArray pos = new JsonArray();
			pos.add(at.getX());
			pos.add(at.getY());
			pos.add(at.getZ());
			o.add("pos", pos);
			o.addProperty("face", block.getDirection().getSerializedName());
			o.addProperty("distance", r3(blockD));
		} else {
			o.addProperty("type", "none");
		}
		return o;
	}

	/** The creatures (and dropped things) about, nearest first. */
	private static JsonArray nearby(ServerPlayer p, ServerLevel level) {
		List<Entity> about = new ArrayList<>(level.getEntities(p, p.getBoundingBox().inflate(AROUND), e -> !e.isSpectator() && e.isAlive() && e.distanceTo(p) <= AROUND));
		about.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
		JsonArray out = new JsonArray();
		for (Entity e : about.subList(0, Math.min(16, about.size()))) {
			JsonObject o = new JsonObject();
			o.addProperty("type", id(e));
			o.addProperty("dist", r3(e.distanceTo(p)));
			o.add("rel", vec(e.getX() - p.getX(), e.getY() - p.getY(), e.getZ() - p.getZ()));
			o.addProperty("hostile", e instanceof Enemy);
			if (e instanceof LivingEntity le) {
				o.addProperty("health", r3(le.getHealth()));
				if (!le.getMainHandItem().isEmpty()) o.addProperty("holding", BuiltInRegistries.ITEM.getKey(le.getMainHandItem().getItem()).toString());
			}
			out.add(o);
		}
		return out;
	}

	/**
	 * Its view as the recorder takes it: 14 by 24 sight lines across 90 by 130.6 degrees, each as far as the first
	 * block it meets (to 192, -1 for nothing) and what that block is (-2 past 64, -1 for nothing).
	 */
	static JsonObject vision(ServerPlayer p, ServerLevel level) {
		Vec3 eye = p.getEyePosition();
		List<String> palette = new ArrayList<>();
		Map<String, Integer> index = new HashMap<>();
		JsonArray idx = new JsonArray(), dist = new JsonArray();
		int open = 0, far = 0;
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				float pitch = (float) (p.getXRot() - FOV_V / 2 + (r + 0.5) * FOV_V / ROWS);
				float yaw = (float) (p.getYRot() - FOV_H / 2 + (c + 0.5) * FOV_H / COLS);
				Vec3 dir = Vec3.directionFromRotation(Math.max(-90, Math.min(90, pitch)), yaw);
				BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(FAR)), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
				if (hit.getType() != HitResult.Type.BLOCK) {
					open++;
					dist.add(-1);
					idx.add(-1);
					continue;
				}
				double d = hit.getLocation().distanceTo(eye);
				dist.add((int) Math.floor(d));
				if (d > NEAR) {
					far++;
					idx.add(-2);
					continue;
				}
				String b = id(level.getBlockState(hit.getBlockPos()));
				Integer k = index.get(b);
				if (k == null) {
					k = palette.size();
					palette.add(b);
					index.put(b, k);
				}
				idx.add(k);
			}
		}
		JsonObject near = new JsonObject();
		near.addProperty("rows", ROWS);
		near.addProperty("cols", COLS);
		JsonArray pal = new JsonArray();
		palette.forEach(pal::add);
		near.add("palette", pal);
		near.add("idx", idx);
		near.add("dist", dist);
		JsonObject farView = new JsonObject();
		farView.add("ray_counts", new JsonObject());
		farView.addProperty("hit_frac", r3(far / (double) (ROWS * COLS)));
		farView.add("things", new JsonArray());
		farView.add("entities", new JsonArray());
		JsonObject o = new JsonObject();
		o.addProperty("max_dist", (int) FAR);
		o.addProperty("near_dist", (int) NEAR);
		o.addProperty("fov_v", FOV_V);
		o.addProperty("fov_h", FOV_H);
		o.addProperty("open_frac", r3(open / (double) (ROWS * COLS)));
		o.add("near", near);
		o.add("far", farView);
		return o;
	}

	private void state(ServerPlayer p, Log log) {
		ServerLevel level = (ServerLevel) p.level();
		log.lastState = log.ticks;
		JsonObject o = head(log, "state");
		log.i++;
		o.addProperty("dimension", level.dimension().identifier().toString());
		o.addProperty("raining", level.isRaining());
		o.addProperty("thundering", level.isThundering());
		o.addProperty("day_time", Compat.timeOfDay(level));
		if (p.containerMenu != p.inventoryMenu) {                          // (a chest, a furnace...: the server knows that one, not its own inventory)
			String screen = "container";
			try {
				var key = BuiltInRegistries.MENU.getKey(p.containerMenu.getType());
				if (key != null) screen = key.toString();
			} catch (RuntimeException e) {
				// a menu with no type: still a screen
			}
			o.addProperty("screen", screen);
		} else {
			o.add("screen", JsonNull.INSTANCE);
		}
		BlockPos feet = p.blockPosition();
		o.addProperty("biome", level.getBiome(feet).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown"));
		o.addProperty("light", level.getMaxLocalRawBrightness(BlockPos.containing(p.getEyePosition())));
		JsonObject me = new JsonObject();
		me.add("pos", vec(p.getX(), p.getY(), p.getZ()));
		Vec3 v = p.getDeltaMovement(), look = p.getLookAngle();
		me.add("velocity", vec(v.x, v.y, v.z));
		me.addProperty("yaw", r3(p.getYRot()));
		me.addProperty("pitch", r3(p.getXRot()));
		me.add("look_dir", vec(look.x, look.y, look.z));
		me.addProperty("on_ground", p.onGround());
		me.addProperty("sprinting", p.isSprinting());
		me.addProperty("sneaking", p.isShiftKeyDown());
		me.addProperty("in_water", p.isInWater());
		me.addProperty("health", r3(p.getHealth()));
		log.health = p.getHealth();
		me.addProperty("food", p.getFoodData().getFoodLevel());
		me.addProperty("air", p.getAirSupply());
		me.addProperty("xp_level", p.experienceLevel);
		me.addProperty("xp_progress", r3(p.experienceProgress));
		me.addProperty("xp_total", p.totalExperience);
		o.add("player", me);
		o.add("input", input(p));
		o.addProperty("selected_slot", p.getInventory().getSelectedSlot());
		o.add("main_hand", item(p.getMainHandItem()));
		o.add("off_hand", item(p.getOffhandItem()));
		JsonObject armor = new JsonObject();
		armor.add("head", item(p.getItemBySlot(EquipmentSlot.HEAD)));
		armor.add("chest", item(p.getItemBySlot(EquipmentSlot.CHEST)));
		armor.add("legs", item(p.getItemBySlot(EquipmentSlot.LEGS)));
		armor.add("feet", item(p.getItemBySlot(EquipmentSlot.FEET)));
		o.add("armor", armor);
		o.addProperty("using_item", p.isUsingItem());
		if (log.ticks - log.lastInventory >= INVENTORY_EVERY) {
			log.lastInventory = log.ticks;
			JsonArray inv = new JsonArray();
			var items = p.getInventory().getNonEquipmentItems();
			for (int k = 0; k < items.size(); k++) {
				ItemStack s = items.get(k);
				if (s.isEmpty()) continue;
				JsonArray one = new JsonArray();
				one.add(k);
				one.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
				one.add(s.getCount());
				inv.add(one);
			}
			o.add("inventory", inv);
		}
		o.add("looking_at", lookingAt(p, level));
		JsonObject blocks = new JsonObject();
		blocks.addProperty("below", id(level.getBlockState(feet.below())));
		blocks.addProperty("feet", id(level.getBlockState(feet)));
		blocks.addProperty("head", id(level.getBlockState(feet.above())));
		o.add("blocks", blocks);
		o.add("nearby", nearby(p, level));
		if (log.ticks - log.lastVision >= VISION_EVERY) {
			log.lastVision = log.ticks;
			o.add("vision", vision(p, level));
		}
		write(log, o);
	}

	// ------------------------------------------------------------------------------------------------- events

	/** It was hurt (after armor): what by, and how it was then. A fight: states 20 a second for a while. */
	void damaged(LivingEntity e, DamageSource src, float amount) {
		Log log = of(e);
		if (log == null || log.out == null || amount <= 0) return;          // (all blocked: no hurt)
		log.fightUntil = log.ticks + FIGHT_TICKS;
		Log by = of(src.getEntity());
		if (by != null) by.fightUntil = by.ticks + FIGHT_TICKS;
		JsonObject o = event(log, "damage_taken");
		o.addProperty("amount", r3(amount));
		o.addProperty("health", r3(e.getHealth()));
		o.addProperty("source", src.type().msgId());
		if (src.getEntity() != null) o.addProperty("source_entity", id(src.getEntity()));
		if (src.getDirectEntity() != null) o.addProperty("direct_entity", id(src.getDirectEntity()));
		o.addProperty("feet_block", id(e.level().getBlockState(e.blockPosition())));
		o.addProperty("on_fire", e.isOnFire());
		o.addProperty("fall_distance", r3(e.fallDistance));
		o.addProperty("air", e.getAirSupply());
		if (e instanceof Player p) o.addProperty("food", p.getFoodData().getFoodLevel());
		write(log, o);
	}

	void died(LivingEntity e, DamageSource src) {
		Log log = of(e);
		if (log == null || log.out == null) return;
		JsonObject hit = event(log, "damage_taken");                         // (the last hit: no damage event comes for one that kills)
		hit.addProperty("amount", r3(Math.max(0.5f, log.health)));
		hit.addProperty("health", 0.0);
		hit.addProperty("source", src.type().msgId());
		if (src.getEntity() != null) hit.addProperty("source_entity", id(src.getEntity()));
		if (src.getDirectEntity() != null) hit.addProperty("direct_entity", id(src.getDirectEntity()));
		hit.addProperty("feet_block", id(e.level().getBlockState(e.blockPosition())));
		hit.addProperty("on_fire", e.isOnFire());
		hit.addProperty("fall_distance", r3(e.fallDistance));
		hit.addProperty("fatal", true);
		write(log, hit);
		JsonObject o = event(log, "death");
		o.addProperty("source", src.type().msgId());
		if (src.getEntity() != null) o.addProperty("source_entity", id(src.getEntity()));
		write(log, o);
	}

	/** A hit at a creature (before the hit, so the charge is the one it hit with). */
	void swing(Player p, Entity target) {
		Log log = of(p);
		if (log == null || log.out == null) return;
		log.fightUntil = log.ticks + FIGHT_TICKS;
		JsonObject o = event(log, "swing");
		o.addProperty("cooldown", r3(p.getAttackStrengthScale(0.5f)));
		o.addProperty("held", p.getMainHandItem().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
		o.addProperty("sprinting", p.isSprinting());
		o.addProperty("target", id(target));                            // (as the recorder writes it: what it hit, "block", or "none")
		o.addProperty("hostile", target instanceof Enemy);
		write(log, o);
	}

	/** It started breaking a block. */
	void digging(Player p, BlockPos pos) {
		Log log = of(p);
		if (log == null || log.out == null) return;
		if (!pos.equals(log.digging)) {
			log.digging = pos.immutable();
			log.digStart = log.ticks;
		}
		JsonObject o = event(log, "swing");
		o.addProperty("cooldown", r3(p.getAttackStrengthScale(0.5f)));
		o.addProperty("held", p.getMainHandItem().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
		o.addProperty("sprinting", p.isSprinting());
		o.addProperty("target", "block");
		write(log, o);
	}

	void broke(Player p, BlockPos pos, BlockState state) {
		Log log = of(p);
		if (log == null || log.out == null) return;
		JsonObject o = event(log, "block_break");
		o.addProperty("block", id(state));
		JsonArray at = new JsonArray();
		at.add(pos.getX());
		at.add(pos.getY());
		at.add(pos.getZ());
		o.add("pos", at);
		o.addProperty("tool", p.getMainHandItem().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
		if (pos.equals(log.digging)) o.addProperty("ticks", log.ticks - log.digStart);
		log.digging = null;
		write(log, o);
	}

	/** Something said in chat: the one who said it logs chat_out, the others chat_in (those in range, with local chat). */
	void chat(ServerPlayer from, String text) {
		if (logs.isEmpty() || mod.server == null) return;
		String who = from.getName().getString();
		for (ServerPlayer p : mod.server.getPlayerList().getPlayers()) {
			Log log = logs.get(p.getUUID());
			if (log == null || log.out == null) continue;
			if (p == from) {
				JsonObject o = event(log, "chat_out");
				o.addProperty("text", text);
				write(log, o);
			} else if (!mod.config.localChat || mod.inRange(p, from)) {
				JsonObject o = event(log, "chat_in");
				o.addProperty("text", "<" + who + "> " + text);
				write(log, o);
			}
		}
	}
}
