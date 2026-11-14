package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * You are about to send a chat message (not a {@code /} command): typed in the chat box or sent by code through
 * vanilla's chat sending. Posted on the render thread. Cancel it to send nothing; {@link #setMessage} to send other
 * text instead. Myriad's own commands are read here, at {@code Priority.HIGHEST}: a message starting with the command
 * prefix is cancelled and run as a command, so other handlers don't see it.
 */
public final class ChatSendEvent extends Cancellable {
	private String message;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public ChatSendEvent(String message) {
		this.message = message;
	}

	/** The text to send: what was typed, or what an earlier handler set. */
	public String message() {
		return message;
	}

	/** Sends {@code message} instead (after every handler has run, if the event isn't cancelled). */
	public void setMessage(String message) {
		this.message = message;
	}
}
