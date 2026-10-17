package dev.myriadclient.api.control;

import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;

/** The control panels of the active profile ({@code Myriad.controls()}). */
@ApiStatus.NonExtendable
public interface Controls {
	List<ControlPanel> all();

	Optional<ControlPanel> get(MyriadId id);

	/** By name, ignoring case. */
	Optional<ControlPanel> byName(String name);

	/** A new, empty control panel of the player's own. */
	ControlPanel create(String name);

	/** Removes it (an addon's comes back with Reset All in the Control Panels window). */
	void delete(ControlPanel panel);
}
