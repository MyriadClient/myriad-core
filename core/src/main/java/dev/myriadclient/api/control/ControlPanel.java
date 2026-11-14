package dev.myriadclient.api.control;

import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * A named set of modules switched together. Turning it on turns its modules on, each with the settings the panel
 * remembers for it; turning it off turns off only the ones it turned on and puts back the settings it changed (unless
 * they were changed again meanwhile), so the modules you run yourself are left as they were.
 */
@ApiStatus.NonExtendable
public interface ControlPanel extends Identified {
	/** Its id: the addon's own for one an addon ships, {@code myriad:control_n} for one the player made. */
	@Override
	MyriadId id();

	/** Its name, as the UI shows it. */
	String name();

	/** Its icon (one of {@link dev.myriadclient.api.ui.Icons}, or the sliders it shows without one). */
	default String icon() {
		return "\uf1de";
	}

	/** Its modules that are installed, in order. */
	List<Module> modules();

	/** Whether it's on. */
	boolean isActive();

	/**
	 * Turns it on (its modules on, with the settings it remembers for them) or off (only the modules it turned on go
	 * off, and the settings it changed go back), with a toast saying so. Nothing happens if it's already that way.
	 */
	void setActive(boolean active);

	/** Turns it on if it's off, off if it's on. */
	default void toggle() {
		setActive(!isActive());
	}

	/** The bind that turns it on and off in game ({@link Keybind#NONE} if it has none). */
	Keybind keybind();

	/** Whether it came with an addon (as opposed to one the player made). */
	boolean isPreset();
}
