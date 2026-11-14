package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import dev.myriadclient.api.util.ChatMessages;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A line is about to be added to the chat: from the server, another player, or the client itself. Cancel to hide
 * it, or {@link #setMessage} to change it. Posted on the render thread, only while something listens.
 */
public final class ChatReceiveEvent extends Cancellable {
	private Component message;
	private ChatMessages.@Nullable Parsed parsed;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public ChatReceiveEvent(Component message) {
		this.message = message;
	}

	/** The line as it will show: as received, or what an earlier handler set. */
	public Component message() {
		return message;
	}

	/** Shows {@code message} instead (with the original's signature and tag); {@link #parsed()} is worked out again. */
	public void setMessage(Component message) {
		this.message = message;
		this.parsed = null;
	}

	/** Who the line is from and whether it's a whisper (see {@link ChatMessages}); worked out on first use. */
	public ChatMessages.Parsed parsed() {
		if (parsed == null) parsed = ChatMessages.parse(message);
		return parsed;
	}
}
