package dev.myriadclient.api.event.events;

import dev.myriadclient.api.event.Cancellable;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/** A keyboard key changed state. Cancel to hide it from Minecraft. */
public final class KeyEvent extends Cancellable {
	private final int key, scancode, action, modifiers;
	private final boolean inScreen;

	@ApiStatus.Internal
	public KeyEvent(int key, int scancode, int action, int modifiers, boolean inScreen) {
		this.key = key;
		this.scancode = scancode;
		this.action = action;
		this.modifiers = modifiers;
		this.inScreen = inScreen;
	}

	public int key() {
		return key;
	}

	public int scancode() {
		return scancode;
	}

	/** {@link GLFW#GLFW_PRESS}, {@link GLFW#GLFW_RELEASE} or {@link GLFW#GLFW_REPEAT}. */
	public int action() {
		return action;
	}

	public int modifiers() {
		return modifiers;
	}

	/** Whether a screen was open when the key changed. */
	public boolean inScreen() {
		return inScreen;
	}

	public boolean isPress() {
		return action == GLFW.GLFW_PRESS;
	}

	public boolean isRelease() {
		return action == GLFW.GLFW_RELEASE;
	}
}
