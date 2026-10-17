package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import dev.myriadclient.api.util.ChatMessages;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * A line is about to be added to the chat: from the server, another player, or the client itself. Cancel to hide
 * it, or {@link #setMessage} to change it. Posted on the render thread.
 */
public final class ChatReceiveEvent extends Cancellable {
	private Component message;
	private ChatMessages.Parsed parsed;

	@ApiStatus.Internal
	public ChatReceiveEvent(Component message) {
		this.message = message;
	}

	public Component message() {
		return message;
	}

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
