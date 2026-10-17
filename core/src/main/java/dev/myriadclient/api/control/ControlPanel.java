package dev.myriadclient.api.control;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * A named set of modules switched together. Turning it on turns its modules on, each with the settings the panel
 * remembers for it; turning it off turns off only the ones it turned on and puts back the settings it changed (unless
 * they were changed again meanwhile), so the modules you run yourself are left as they were.
 */
@ApiStatus.NonExtendable
public interface ControlPanel extends Identified {
	@Override
	MyriadId id();

	String name();

	/** Its modules that are installed, in order. */
	List<Module> modules();

	boolean isActive();

	void setActive(boolean active);

	default void toggle() {
		setActive(!isActive());
	}

	Keybind keybind();

	/** Whether it came with an addon (as opposed to one the player made). */
	boolean isPreset();
}
