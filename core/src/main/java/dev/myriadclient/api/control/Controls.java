package dev.myriadclient.api.control;

import dev.myriadclient.api.registry.MyriadId;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;

/**
 * The control panels of the active profile ({@code Myriad.controls()}): the player's own and those addons ship.
 *
 * <pre>{@code
 * Myriad.controls().byName("Digging").ifPresent(ControlPanel::toggle);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Controls {
	/** Every control panel, in order; a copy, so it can be kept and iterated freely. */
	List<ControlPanel> all();

	/** The panel with {@code id}; empty if there's none (or it was deleted). */
	Optional<ControlPanel> get(MyriadId id);

	/** The first panel called {@code name}, ignoring case and surrounding spaces; empty if none is. */
	Optional<ControlPanel> byName(String name);

	/**
	 * A new, empty control panel of the player's own, saved with the profile. A blank {@code name} gets a numbered one
	 * ("Control panel 2").
	 */
	ControlPanel create(String name);

	/**
	 * Turns it off and removes it; nothing happens if it isn't one of these. An addon's comes back with Reset All in
	 * the Control Panels window.
	 */
	void delete(ControlPanel panel);
}
