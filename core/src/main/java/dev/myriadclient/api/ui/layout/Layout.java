package dev.myriadclient.api.ui.layout;

import dev.myriadclient.api.registry.Identified;

/**
 * A tiling algorithm. Each workspace has its own {@link LayoutState}; switching a workspace's layout re-inserts its
 * tiled windows into a fresh state. Myriad ships {@code dwindle} (Hyprland's default binary-split tree) and
 * {@code columns}; addons can register more with {@code AddonContext.registerLayout}, and players pick one per
 * workspace.
 */
public interface Layout extends Identified {
	/** The name players see when choosing a layout ("Dwindle"). */
	String name();

	/**
	 * A new, empty tiling for one workspace. Called when a workspace starts with this layout or switches to it; the
	 * workspace's tiled windows are then {@link LayoutState#add}ed or {@link LayoutState#load}ed into it.
	 *
	 * @param <W> the window handle type, opaque to the layout
	 */
	<W> LayoutState<W> createState();
}
