package xen.mod;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A network connection that goes nowhere: Xen has no game client, it plays on the server itself. */
public final class FakeConnection extends Connection {
	private final Runnable onDisconnect;

	public FakeConnection(Runnable onDisconnect) {
		super(PacketFlow.SERVERBOUND);
		this.onDisconnect = onDisconnect;
	}

	@Override public void send(Packet<?> packet) {}
	@Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
	@Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
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
