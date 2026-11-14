/**
 * Tiling layouts for workspaces: a {@link dev.myriadclient.api.ui.layout.Layout} makes one
 * {@link dev.myriadclient.api.ui.layout.LayoutState} per workspace, which places its tiled windows. Core ships
 * {@code dwindle} and {@code columns}; addons can register their own with {@code AddonContext.registerLayout}.
 */
@NullMarked
package dev.myriadclient.api.ui.layout;

import org.jspecify.annotations.NullMarked;
