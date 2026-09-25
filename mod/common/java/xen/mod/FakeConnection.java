package xen.mod;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A network connection that goes nowhere: Xen has no game client, it plays on the server itself. The one thing a game
 * client would do with what the server sends that matters to its body is being pushed: the server leaves knockback
 * (from hits and explosions) to the player's own client, so it's passed on to Xen's body here.
 */
public final class FakeConnection extends Connection {
	private final Runnable onDisconnect;
	/** The body this connection belongs to (for knockback). */
	XenPlayer body;
	private int pushedAt = -100;

	public FakeConnection(Runnable onDisconnect) {
		super(PacketFlow.SERVERBOUND);
		this.onDisconnect = onDisconnect;
	}

	private void received(Packet<?> packet) {
		if (body == null) return;
		net.minecraft.world.phys.Vec3 push = Compat.motionFor(packet, body.getId());
		// Only the push from a hit (the server also echoes a player's own motion back now and then: that isn't news
		// to a client, and taking it a tick late would undo friction).
		if (push != null && body.hurtTime > 0 && body.tickCount - pushedAt > 5) {
			pushedAt = body.tickCount;
			body.pushed(push, false);
		}
		if (packet instanceof net.minecraft.network.protocol.game.ClientboundExplodePacket boom) {
			boom.playerKnockback().ifPresent(v -> body.pushed(v, true));
		}
	}

	@Override public void send(Packet<?> packet) { received(packet); }
	@Override public void send(Packet<?> packet, ChannelFutureListener listener) { received(packet); }
	@Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { received(packet); }
	@Override public void flushChannel() {}
	@Override public boolean isConnected() { return true; }
	@Override public boolean isMemoryConnection() { return true; }
	@Override public void setReadOnly() {}
	@Override public void handleDisconnection() {}
	@Override public void setListenerForServerboundHandshake(PacketListener listener) {}
	@Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {}
	@Override public void setupOutboundProtocol(ProtocolInfo<?> protocol) {}
	@Override public void disconnect(Component reason) { onDisconnect.run(); }
	@Override public void disconnect(DisconnectionDetails details) { onDisconnect.run(); }
}
