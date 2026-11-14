package dev.myriadclient.api.event.events;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * A keyboard key was pressed, released or repeated (held down) in the game window, in a screen or not. Posted on the
 * render thread before Minecraft handles it; a typed character follows as a {@link CharEvent}. Cancel it to hide it
 * from Minecraft (no key binding, no screen input).
 */
public final class KeyEvent extends Cancellable {
	private final int key, scancode, action, modifiers;
	private final boolean inScreen;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public KeyEvent(int key, int scancode, int action, int modifiers, boolean inScreen) {
		this.key = key;
		this.scancode = scancode;
		this.action = action;
		this.modifiers = modifiers;
		this.inScreen = inScreen;
	}

	/** Which key, by its position on the keyboard: an {@code InputConstants.KEY_*} value (Q is where a US layout has it). */
	public int key() {
		return key;
	}

	/** What the key types on the player's layout (an SDL keycode: {@code 'q'} for the key labelled Q, wherever it is). */
	public int scancode() {
		return scancode;
	}

	/** {@link InputConstants#PRESS}, {@link InputConstants#RELEASE} or {@link InputConstants#REPEAT}. */
	public int action() {
		return action;
	}

	/** The modifier keys held, as {@code InputConstants.MOD_*} bits ({@code MOD_SHIFT}, {@code MOD_CONTROL}...). */
	public int modifiers() {
		return modifiers;
	}

	/** Whether a screen was open when the key changed. */
	public boolean inScreen() {
		return inScreen;
	}

	/** Whether the key went down (not a repeat). */
	public boolean isPress() {
		return action == InputConstants.PRESS;
	}

	/** Whether the key came up. */
	public boolean isRelease() {
		return action == InputConstants.RELEASE;
	}
}
