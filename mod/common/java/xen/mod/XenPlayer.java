package xen.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * Xen's body: a real server-side player. The server treats it like anyone else (player list, damage,
 * hunger, inventory, reach checks). With no game client to move it, it runs its own player physics.
 */
public class XenPlayer extends ServerPlayer {
	Companion companion;

	public XenPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	@Override
	public void tick() {
		if (level().getServer().getTickCount() % 10 == 0) {
			connection.resetPosition();
			level().getChunkSource().move(this);          // load the world around it, like a player
		}
		super.tick();
		doTick();                                          // player physics a client would normally drive
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (companion != null) companion.died(source);
	}
}
