package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/** A mouse button changed state. Cancel to hide it from Minecraft. */
public final class MouseButtonEvent extends Cancellable {
	private final int button, action, modifiers;
	private final boolean inScreen;

	@ApiStatus.Internal
	public MouseButtonEvent(int button, int action, int modifiers, boolean inScreen) {
		this.button = button;
		this.action = action;
		this.modifiers = modifiers;
		this.inScreen = inScreen;
	}

	public int button() {
		return button;
	}

	public int action() {
		return action;
	}

	public int modifiers() {
		return modifiers;
	}

	public boolean inScreen() {
		return inScreen;
	}

	public boolean isPress() {
		return action == GLFW.GLFW_PRESS;
	}
}
