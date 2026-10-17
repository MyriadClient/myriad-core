package dev.myriadclient.api.ui.layout;

import dev.myriadclient.api.registry.Identified;

/**
 * A tiling algorithm. Each workspace has its own {@link LayoutState}; switching a workspace's layout re-inserts its
 * tiled windows into a fresh state. Myriad ships {@code dwindle} (Hyprland's default binary-split tree) and
 * {@code columns}; addons can register more.
 */
public interface Layout extends Identified {
	String name();

	<W> LayoutState<W> createState();
}
