package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/** A character was typed. Cancel to hide it from Minecraft. */
public final class CharEvent extends Cancellable {
	private final int codePoint, modifiers;

	@ApiStatus.Internal
	public CharEvent(int codePoint, int modifiers) {
		this.codePoint = codePoint;
		this.modifiers = modifiers;
	}

	public int codePoint() {
		return codePoint;
	}

	public int modifiers() {
		return modifiers;
	}
}
