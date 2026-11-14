package dev.myriadclient.api.event.events;

import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;

/**
 * A mouse button was pressed or released in the game window, in a screen or not. Posted on the render thread before
 * Minecraft handles it. Cancel it to hide it from Minecraft (no attack or use, no click in the screen).
 */
public final class MouseButtonEvent extends Cancellable {
	private final int button, action, modifiers;
	private final boolean inScreen;

	/** Core's; only Myriad makes these. */
	@ApiStatus.Internal
	public MouseButtonEvent(int button, int action, int modifiers, boolean inScreen) {
		this.button = button;
		this.action = action;
		this.modifiers = modifiers;
		this.inScreen = inScreen;
	}

	/** Which button: {@code InputConstants.MOUSE_BUTTON_LEFT}, {@code _RIGHT}, {@code _MIDDLE}, or higher (side buttons). */
	public int button() {
		return button;
	}

	/** {@link InputConstants#PRESS} or {@link InputConstants#RELEASE}. */
	public int action() {
		return action;
	}

	/** The modifier keys held, as {@code InputConstants.MOD_*} bits ({@code MOD_SHIFT}, {@code MOD_CONTROL}...). */
	public int modifiers() {
		return modifiers;
	}

	/** Whether a screen was open when the button changed. */
	public boolean inScreen() {
		return inScreen;
	}

	/** Whether the button went down. */
	public boolean isPress() {
		return action == InputConstants.PRESS;
	}
}
