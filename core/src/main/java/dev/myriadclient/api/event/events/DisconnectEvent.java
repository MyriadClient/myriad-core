package dev.myriadclient.api.event.events;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The connection to a server ended, with the reason the server gave (or the client's own, for a timeout). Posted on
 * the render thread before the disconnect screen opens; {@link WorldEvent.Leave} follows. For auto reconnect
 * ({@code Myriad.server().reconnect()}), logout spots and "why was I kicked" logs.
 */
public final class DisconnectEvent {
	private final Component reason;
	private final @Nullable String address;

	@ApiStatus.Internal
	public DisconnectEvent(Component reason, @Nullable String address) {
		this.reason = reason;
		this.address = address;
	}

	public Component reason() {
		return reason;
	}

	/** The server address you were connected to, or null in singleplayer. */
	public @Nullable String address() {
		return address;
	}

	/** Whether the server said so (a kick, a ban, a restart), rather than the connection dropping. */
	public boolean isKick() {
		String key = reason.getContents() instanceof TranslatableContents t ? t.getKey() : "";
		return !key.startsWith("disconnect.timeout") && !key.startsWith("disconnect.lost") && !key.startsWith("disconnect.genericReason");
	}
}
