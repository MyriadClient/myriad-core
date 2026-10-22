package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import dev.myriadclient.api.util.AnyThread;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.ApiStatus;

/**
 * Packet traffic. Bundles are split, so handlers see each inner packet. {@link Receive} fires on the network thread, and
 * {@link Send} on the thread that sends (the render thread, but for vanilla's keep-alive replies): a handler that acts
 * on the game rather than on the packet asks for {@code @Subscribe(thread = HandlerThread.RENDER)}.
 */
public abstract class PacketEvent extends Cancellable {
	private Packet<?> packet;

	protected PacketEvent(Packet<?> packet) {
		this.packet = packet;
	}

	public Packet<?> packet() {
		return packet;
	}

	public void setPacket(Packet<?> packet) {
		this.packet = packet;
	}

	@AnyThread
	public static final class Send extends PacketEvent {
		@ApiStatus.Internal
		public Send(Packet<?> packet) {
			super(packet);
		}
	}

	@AnyThread
	public static final class Receive extends PacketEvent {
		@ApiStatus.Internal
		public Receive(Packet<?> packet) {
			super(packet);
		}
	}
}
