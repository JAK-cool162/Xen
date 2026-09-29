package xen.mod;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/**
 * (Experiment) Spawn as Xen: in single player (or as the host of a LAN world) your own character is played by a Xen,
 * and you watch through its eyes. Turned on, a Xen named after you ("Steve_X") takes your place where you stand, with
 * your things; you become a spectator riding its camera. It lives its own life (its own goals, its own mind, its own
 * chat; it's yours, so it listens to you). Turned off, you're back where it is, with what it has, in your game mode
 * again. Never on a dedicated server: on a public server it would be a bot playing for someone.
 */
final class Avatar {
	private final XenMod mod;
	private UUID host;
	private Companion body;
	private GameType before = GameType.SURVIVAL;

	Avatar(XenMod mod) {
		this.mod = mod;
	}

	/** Every second: on or off as the setting says; the camera kept on it (it respawns as a new body). */
	void tick() {
		MinecraftServer s = mod.server;
		if (s == null) return;
		boolean want = mod.config.spawnAsXen && !s.isDedicatedServer();
		if (body != null && body.player() == null && !mod.companions.contains(body)) {   // it was dismissed
			stop("It's gone: you're back.");
			return;
		}
		if (!want) {
			if (body != null) stop("Spawn as Xen is off: you're back.");
			return;
		}
		ServerPlayer p = host();
		if (p == null) return;
		if (body == null) {
			if (p.isAlive() && !p.isSpectator()) start(p);
			return;
		}
		if (body.player() != null && p.getCamera() != body.player() && body.player().isAlive()) p.setCamera(body.player());
	}

	/** The one playing on this computer: the first real player (the host of a single player or LAN world). */
	private ServerPlayer host() {
		for (ServerPlayer p : mod.server.getPlayerList().getPlayers()) {
			if (p instanceof XenPlayer) continue;
			if (host == null || host.equals(p.getUUID())) return p;
		}
		return null;
	}

	private void start(ServerPlayer p) {
		if (mod.full()) {
			p.sendSystemMessage(Component.literal("Spawn as Xen: the world already has as many Xens as it may."));
			return;
		}
		String own = p.getName().getString();
		String name = (own.length() > 14 ? own.substring(0, 14) : own) + "_X";
		Companion c = mod.create(name, p, null);
		c.mode = Companion.Mode.FREE;                                         // its own life
		c.join((ServerLevel) p.level(), p.position(), p.getYRot());
		mod.companions.add(c);
		var from = p.getInventory();
		var to = c.player().getInventory();
		for (int i = 0; i < Math.min(from.getContainerSize(), to.getContainerSize()); i++) {   // your things go with it
			ItemStack st = from.getItem(i);
			if (st.isEmpty()) continue;
			to.setItem(i, st.copy());
			from.setItem(i, ItemStack.EMPTY);
		}
		c.player().setHealth(p.getHealth());
		before = p.gameMode.getGameModeForPlayer();
		p.setGameMode(GameType.SPECTATOR);
		p.setCamera(c.player());
		host = p.getUUID();
		body = c;
		c.journal("does", "plays as " + own + " (spawn as Xen)");
		p.sendSystemMessage(Component.literal(name + " plays as you now: you see what it sees. Turn Spawn as Xen off (Xen settings, Experimental) to take over again."));
	}

	/** You back in your body (where it is, with what it has), and it goes. */
	void stop(String why) {
		Companion c = body;
		body = null;
		ServerPlayer p = host == null ? null : mod.server.getPlayerList().getPlayer(host);
		if (p != null) {
			p.setCamera(p);
			if (c != null && c.player() != null) {
				var from = c.player().getInventory();
				var to = p.getInventory();
				for (int i = 0; i < Math.min(from.getContainerSize(), to.getContainerSize()); i++) {
					ItemStack st = from.getItem(i);
					if (st.isEmpty()) continue;
					if (to.getItem(i).isEmpty()) to.setItem(i, st.copy());
					else if (!to.add(st.copy())) Compat.drop(p, st.copy());
					from.setItem(i, ItemStack.EMPTY);
				}
				var at = c.player().position();
				p.teleportTo((ServerLevel) c.player().level(), at.x, at.y, at.z, java.util.Set.of(), c.player().getYRot(), 0, true);
			}
			p.setGameMode(before == GameType.SPECTATOR ? GameType.SURVIVAL : before);
			p.sendSystemMessage(Component.literal(why));
		}
		if (c != null && c.player() != null) c.leave();
	}

	/** The server is stopping: you back first (your things are saved with you). */
	void stopping() {
		if (body != null) stop("");
	}

	/** The server is stopping, or you left: you get your things back first (they're saved with you). */
	void leaving(ServerPlayer p) {
		if (body != null && p != null && p.getUUID().equals(host)) stop("");
	}
}
