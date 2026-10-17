package xen.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /xen tour [minutes]}: training data without playing for hours. You float through the world as a spectator,
 * a new view every few seconds (out on the land, inside a cave, high over it), your view turning slowly, while the
 * recorder (F8) saves what you see: thousands of labelled pictures an hour, from land you haven't been to (it wanders
 * further as it goes). The recorder marks these as spectator views, so they teach Xen's eyes (colours, what things
 * look like) and not how a person plays. {@code /xen tour stop} (or the time running out) puts you back where you were,
 * in your game mode.
 */
final class Tour {
	private static final Map<UUID, Tour> ON = new HashMap<>();
	/** Ticks between views. */
	private static final int HOP = 60;

	private final UUID who;
	private final GameType before;
	private final ServerLevel homeLevel;
	private final Vec3 home;
	private final float homeYaw, homePitch;
	private final long until;
	private final Random r = new Random();
	private BlockPos centre;
	private long next;
	private int hops;
	private float yaw, pitch, turn;
	private Vec3 at;

	private Tour(ServerPlayer p, int minutes) {
		who = p.getUUID();
		before = p.gameMode.getGameModeForPlayer();
		homeLevel = (ServerLevel) p.level();
		home = p.position();
		homeYaw = p.getYRot();
		homePitch = p.getXRot();
		until = p.level().getGameTime() + 20L * 60 * minutes;
		centre = p.blockPosition();
	}

	static String start(ServerPlayer p, int minutes) {
		if (ON.containsKey(p.getUUID())) return "You're on a tour already (/xen tour stop to end it).";
		ON.put(p.getUUID(), new Tour(p, minutes));
		p.setGameMode(GameType.SPECTATOR);
		return "Tour: a new view every " + HOP / 20 + " seconds for " + minutes + " minutes. Press F8 to record it (F8 again to stop). /xen tour stop ends it.";
	}

	static String stop(ServerPlayer p) {
		Tour t = ON.remove(p.getUUID());
		if (t == null) return "You're not on a tour.";
		t.end(p);
		return "Tour over: " + t.hops + " views. You're back where you were.";
	}

	/** Leaving the server mid-tour: back where they were first, so they don't come back a spectator. */
	static void left(ServerPlayer p) {
		Tour t = p == null ? null : ON.remove(p.getUUID());
		if (t != null) t.end(p);
	}

	private void end(ServerPlayer p) {
		p.teleportTo(homeLevel, home.x, home.y, home.z, Set.of(), homeYaw, homePitch, true);
		p.setGameMode(before == GameType.SPECTATOR ? GameType.SURVIVAL : before);
	}

	static void tickAll(MinecraftServer server) {
		if (ON.isEmpty()) return;
		var it = ON.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {                                                     // (gone without a goodbye: nothing to put back)
				it.remove();
				continue;
			}
			Tour t = e.getValue();
			if (p.level().getGameTime() > t.until) {
				it.remove();
				t.end(p);
				p.sendSystemMessage(Component.literal("Tour over: " + t.hops + " views. You're back where you were."));
				continue;
			}
			t.tick(p);
		}
	}

	private void tick(ServerPlayer p) {
		ServerLevel level = (ServerLevel) p.level();
		long now = level.getGameTime();
		if (now >= next) {
			next = now + HOP;
			hop(level);
		}
		if (at != null && now % 5 == 0) {                                       // the view turns slowly, like someone looking round
			yaw += turn;
			pitch = Math.max(-60, Math.min(70, pitch + (r.nextFloat() - 0.5f) * 2));
			p.teleportTo(level, at.x, at.y, at.z, Set.of(), yaw, pitch, true);
		}
	}

	/** A new view: mostly out on the land, sometimes inside a cave or high over it; now and then further on. */
	private void hop(ServerLevel level) {
		hops++;
		if (hops % 10 == 0) centre = centre.offset(r.nextInt(161) - 80, 0, r.nextInt(161) - 80);   // (on to new land)
		int kind = hops % 6;                                                    // 0-3 land, 4 cave, 5 high up
		for (int tries = 0; tries < 12; tries++) {
			int x = centre.getX() + r.nextInt(193) - 96, z = centre.getZ() + r.nextInt(193) - 96;
			level.getChunk(x >> 4, z >> 4);                                       // (made if it isn't yet)
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			double y;
			if (kind == 4) {
				int found = Integer.MIN_VALUE;
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
				for (int yy = top - 8; yy > level.getMinY() + 4 && found == Integer.MIN_VALUE; yy--) {
					m.set(x, yy, z);
					if (level.getBlockState(m).isAir() && level.getBlockState(m.above()).isAir() && level.getBlockState(m.above(2)).isAir()
							&& !level.getBlockState(m.below()).isAir() && level.getBrightness(LightLayer.SKY, m) == 0) found = yy;
				}
				if (found == Integer.MIN_VALUE) continue;
				y = found + 0.2;
				pitch = r.nextFloat() * 30 - 10;
			} else if (kind == 5) {
				y = top + 20 + r.nextInt(25);
				pitch = 25 + r.nextFloat() * 25;
			} else {
				if (!level.getFluidState(new BlockPos(x, top - 1, z)).isEmpty() && r.nextFloat() < 0.7f) continue;   // (not too much open water)
				y = top + 0.1 + r.nextFloat() * 5;
				pitch = r.nextFloat() * 40 - 8;
			}
			yaw = r.nextFloat() * 360 - 180;
			turn = (r.nextBoolean() ? 1 : -1) * (2 + r.nextFloat() * 5);
			at = new Vec3(x + 0.5, y, z + 0.5);
			return;
		}
	}
}
