package dev.myriadclient.api.input;

import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import org.jspecify.annotations.Nullable;

/**
 * A named, rebindable global key action (e.g. "Open Menu"): something to do on a key press that isn't a module toggle.
 * Register one with {@link dev.myriadclient.api.addon.AddonContext#registerKeyAction}; the player rebinds it in the
 * Keybinds panel, and the bind is saved for every profile. It runs on the render thread when its bind is pressed in
 * game (not while a screen or the menu is open), and the press goes no further. With binds that share a key, only the
 * ones with the most modifiers held run (Ctrl+T over T).
 */
public final class KeyAction implements Identified {
	private final MyriadId id;
	private final String name;
	private final Keybind defaultBind;
	private final Runnable action;
	private Keybind bind;

	/**
	 * A key action bound to {@code defaultBind} until the player rebinds it. Addons get theirs from
	 * {@code AddonContext.registerKeyAction}, which makes and registers it; one that isn't registered never runs.
	 */
	public KeyAction(MyriadId id, String name, Keybind defaultBind, Runnable action) {
		this.id = id;
		this.name = name;
		this.defaultBind = defaultBind;
		this.bind = defaultBind;
		this.action = action;
	}

	/** {@code <mod id>:<name in snake_case>}: the key the bind is saved under. */
	@Override
	public MyriadId id() {
		return id;
	}

	/** The name the Keybinds panel shows. */
	public String name() {
		return name;
	}

	/** The bind in effect: the player's, or the default until they change it. */
	public Keybind bind() {
		return bind;
	}

	/** The bind it was registered with. */
	public Keybind defaultBind() {
		return defaultBind;
	}

	/** Changes the bind ({@code null} or {@link Keybind#NONE} unbinds it); saved with the next save. */
	public void setBind(@Nullable Keybind bind) {
		this.bind = bind == null ? Keybind.NONE : bind;
	}

	/** Runs the action now, as a press of its bind does. */
	public void run() {
		action.run();
	}
}
