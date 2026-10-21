package xen.mod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Duel rows for learning to fight like a good player (-Dxen.fightLog): every tick, for each two players in survival
 * within 8 blocks of each other where one is a Xen, a row for each of them: how far, how fast it closes in or strafes,
 * its swing charge and its foe's, on the ground or falling, hurt or not. What it did comes from one row to the next
 * (a jump, a swing, back or forward), so a practice bot's play (or a player's) is a teacher: python -m xen.ex1.duel.
 * Off unless the flag is set: nothing is written.
 */
final class Duels {
	static final boolean ON = Fighter.FIGHT_LOG;
	private BufferedWriter out;
	private final Path dir;
	private final Map<UUID, Long> lastRow = new HashMap<>();

	Duels(Path dir) {
		this.dir = dir;
	}

	void tick(MinecraftServer s) {
		if (!ON) return;
		List<ServerPlayer> all = s.getPlayerList().getPlayers();
		for (ServerPlayer a : all) {
			if (!a.isAlive() || a.isSpectator() || a.isCreative()) continue;
			ServerPlayer foe = null;
			double best = 8;
			for (ServerPlayer b : all) {
				if (b == a || !b.isAlive() || b.isSpectator() || b.isCreative() || b.level() != a.level()) continue;
				boolean xenPair = a instanceof XenPlayer || b instanceof XenPlayer;
				double d = a.distanceTo(b);
				if (xenPair && d < best) {
					best = d;
					foe = b;
				}
			}
			if (foe != null) row(a, foe, s.getTickCount());
		}
	}

	private void row(ServerPlayer a, ServerPlayer b, long tick) {
		Long last = lastRow.put(a.getUUID(), tick);
		if (last != null && last == tick) return;
		Vec3 to = b.position().subtract(a.position());
		Vec3 flat = new Vec3(to.x, 0, to.z);
		double d = flat.length();
		Vec3 way = d > 1e-4 ? flat.scale(1 / d) : Vec3.ZERO;
		Vec3 v = a.getDeltaMovement();
		double closing = v.x * way.x + v.z * way.z, side = v.x * -way.z + v.z * way.x;
		Vec3 look = a.getLookAngle();
		double facing = look.x * way.x + look.z * way.z;
		String line = String.join("\t", Long.toString(tick), a.getName().getString(), a instanceof XenPlayer ? "xen" : "player", b.getName().getString(),
				f(a.distanceTo(b)), f(d), f(to.y), f(closing), f(side), f(v.y), a.onGround() ? "1" : "0", f(a.fallDistance), a.isSprinting() ? "1" : "0",
				f(a.getAttackStrengthScale(0.5f)), f(b.getAttackStrengthScale(0.5f)), Integer.toString(a.hurtTime), Integer.toString(b.hurtTime),
				f(a.getHealth()), f(b.getHealth()), f(facing), a.isBlocking() ? "1" : "0", b.isBlocking() ? "1" : "0");
		try {
			if (out == null) {
				Files.createDirectories(dir);
				out = Files.newBufferedWriter(dir.resolve("duels-" + System.currentTimeMillis() + ".tsv"));
				out.write("tick\tname\tkind\tfoe\td\tflat\tdy\tclosing\tside\tvy\tground\tfall\tsprint\tcharge\tfoe_charge\thurt\tfoe_hurt\thealth\tfoe_health\tfacing\tblocking\tfoe_blocking\n");
			}
			out.write(line);
			out.write('\n');
			if (tick % 100 == 0) out.flush();
		} catch (IOException e) {
			XenMod.LOG.warn("xen: couldn't write duel rows: {}", e.toString());
		}
	}

	private static String f(double x) {
		return String.format(java.util.Locale.ROOT, "%.3f", x);
	}
}
