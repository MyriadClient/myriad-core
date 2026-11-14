package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * A character was typed (text input, after the keyboard layout and dead keys were applied), in a screen or not. Posted
 * on the render thread before Minecraft handles it; the key's own {@link KeyEvent} comes first. Cancel it to hide it
 * from Minecraft, so no text field receives it.
 */
public final class CharEvent extends Cancellable {
	private final int codePoint, modifiers;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public CharEvent(int codePoint, int modifiers) {
		this.codePoint = codePoint;
		this.modifiers = modifiers;
	}

	/** The Unicode code point typed ({@code Character.toChars(codePoint())} for the text). */
	public int codePoint() {
		return codePoint;
	}

	/** The modifier keys held as it was typed ({@code InputConstants.MOD_} bits, as {@link dev.myriadclient.api.input.Keybind#heldModifiers()}). */
	public int modifiers() {
		return modifiers;
	}
}
