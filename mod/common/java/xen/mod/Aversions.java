package xen.mod;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Xen 2.0: once burnt, twice shy. What hurt it, it remembers: the lava it stepped in, the fire, the magma, the cactus,
 * the berry bush, the creature that bit it. Nothing is known at birth (a newborn Xen walks right up to lava); the
 * first hurt teaches it (or another Xen telling it, or a player: "lava burns"), and from then on its gut keeps it away
 * from that thing. The memory fades slowly unless it's hurt again. What to keep away from is learned; keeping away from
 * what hurts is the only thing it's born with, like anyone.
 */
final class Aversions {
	private final Companion c;
	/** What hurt it (a block's name, or "mob:zombie"), and how much it minds it now (0 to 1). */
	final Map<String, Float> of = new LinkedHashMap<>();
	/** From how much it minds a thing, it keeps away. */
	static final float KEEP_AWAY = 0.3f;

	Aversions(Companion c) {
		this.c = c;
	}

	/** What it felt hurt it: the creature, or the block it touched (null: nothing to keep away from, a fall, hunger). */
	String cause(DamageSource src) {
		if (src.getEntity() instanceof LivingEntity e && !(e instanceof Player)) return "mob:" + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		String how = src.type().msgId();
		var p = c.player;
		var level = p.level();
		return switch (how) {
			case "lava" -> "lava";
			case "inFire", "onFire" -> {
				for (BlockPos q : new BlockPos[] {p.blockPosition(), p.blockPosition().below()}) {
					String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath();
					if (n.contains("fire") || n.contains("campfire")) yield n;
				}
				yield how.equals("inFire") ? "fire" : null;
			}
			case "hotFloor" -> "magma_block";
			case "cactus" -> "cactus";
			case "sweetBerryBush" -> "sweet_berry_bush";
			case "freeze" -> "powder_snow";
			default -> null;
		};
	}

	/** It was hurt: it minds what did it more. */
	void hurt(DamageSource src, float amount) {
		String cause = cause(src);
		if (cause == null) return;
		learn(cause, 0.3f + 0.04f * amount, "it hurt me");
	}

	/** It learns to mind a thing (hurt by it, or told about it). */
	void learn(String thing, float how, String why) {
		float before = of.getOrDefault(thing, 0f), after = Math.min(1f, before + how);
		of.put(thing, after);
		if (before < KEEP_AWAY && after >= KEEP_AWAY) {
			c.journal("learns", said(thing) + " is to keep away from (" + why + ")");
			if (why.startsWith("it hurt")) c.chatter(c.pick3("Ow! Note to self: " + said(thing) + " hurts.", "ok " + said(thing) + " is bad news", "Ouch. Never again, " + said(thing) + "."), false);
		}
	}

	float of(String thing) {
		return of.getOrDefault(thing, 0f);
	}

	static String said(String thing) {
		return thing.startsWith("mob:") ? thing.substring(4).replace('_', ' ') + "s" : thing.replace('_', ' ');
	}

	/** The nearest block within r of its feet (feet level, one down, one up) that it minds, or null. */
	BlockPos near(int r) {
		if (of.isEmpty()) return null;
		var p = c.player;
		var level = p.level();
		BlockPos feet = p.blockPosition(), best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -1, -r), feet.offset(r, 1, r))) {
			String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath();
			if (n.equals("air")) continue;
			if (of(n) < KEEP_AWAY) continue;
			double d = q.distToCenterSqr(p.position());
			if (d < bestD) {
				bestD = d;
				best = q.immutable();
			}
		}
		return best;
	}

	/** Once a minute: what it hasn't been hurt by in a long time it minds a little less. */
	void fade() {
		of.replaceAll((k, v) -> v * 0.997f);
		of.values().removeIf(v -> v < 0.02f);
	}

	/** What it minds most, in words ("lava, magma block"), for talk; empty if nothing. */
	String words() {
		StringBuilder sb = new StringBuilder();
		of.entrySet().stream().filter(e -> e.getValue() >= KEEP_AWAY).sorted((a, b) -> Float.compare(b.getValue(), a.getValue())).limit(3)
				.forEach(e -> sb.append(sb.length() == 0 ? "" : ", ").append(said(e.getKey())));
		return sb.toString();
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		for (var e : of.entrySet()) o.addProperty(e.getKey(), Math.round(e.getValue() * 1000) / 1000f);
		return o;
	}

	void load(JsonObject o) {
		of.clear();
		for (var e : o.entrySet()) of.put(e.getKey(), Math.max(0f, Math.min(1f, e.getValue().getAsFloat())));
	}
}
