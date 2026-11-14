package dev.myriadclient.api.event.events;

import dev.myriadclient.api.annotation.AnyThread;
import dev.myriadclient.api.event.Cancellable;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.ApiStatus;

/**
 * Packet traffic. Bundles are split, so handlers see each inner packet. {@link Receive} fires on the network thread, and
 * {@link Send} on the thread that sends (the render thread, but for vanilla's keep-alive replies): a handler that acts
 * on the game rather than on the packet asks for {@code @Subscribe(thread = HandlerThread.RENDER)}. Narrow handlers to
 * the packets they want with {@code @Subscribe(packets = ...)}, which costs less than checking each packet. Only the
 * client's own connection is covered (not the integrated server's side of it).
 */
public abstract class PacketEvent extends Cancellable {
	private Packet<?> packet;

	/** Core's; addons don't make these. */
	protected PacketEvent(Packet<?> packet) {
		this.packet = packet;
	}

	/** The packet: as it came, or what an earlier handler set. */
	public Packet<?> packet() {
		return packet;
	}

	/** Sends (or handles) {@code packet} instead of this one. */
	public void setPacket(Packet<?> packet) {
		this.packet = packet;
	}

	/**
	 * A packet is about to be sent to the server. Cancel it to not send it. Packets core holds for the next tick, or
	 * resends after a rewrite, aren't posted again; an attack is posted as an {@link AttackEvent} first.
	 */
	@AnyThread
	public static final class Send extends PacketEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Send(Packet<?> packet) {
			super(packet);
		}
	}

	/**
	 * A packet arrived from the server, on the network thread, before the game handles it (most packets are then
	 * handed to the render thread). Cancel it to drop it: the game never sees it.
	 */
	@AnyThread
	public static final class Receive extends PacketEvent {
		/** Core's; only Myriad makes these. */
		@ApiStatus.Internal
		public Receive(Packet<?> packet) {
			super(packet);
		}
	}
}
