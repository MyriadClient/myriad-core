/**
 * The UI: Myriad's tiling window manager, modelled on Hyprland. {@link dev.myriadclient.api.Myriad#ui()} returns the
 * {@link dev.myriadclient.api.ui.Ui}: nine tiling workspaces of {@link dev.myriadclient.api.ui.Window}s, each showing a
 * {@link dev.myriadclient.api.ui.Panel} (build one from widgets with {@link dev.myriadclient.api.ui.WidgetPanel}), a
 * HUD workspace whose elements are drawn in game, a top bar of {@link dev.myriadclient.api.ui.BarWidget}s, and the live
 * {@link dev.myriadclient.api.ui.ThemeSettings} that {@link dev.myriadclient.api.ui.Theme} presets write.
 * <p>
 * <b>Units.</b> Everything the UI lays out is in <i>UI units</i>, whatever Minecraft's own GUI scale is: a unit is
 * {@code max(1, framebuffer height / 540) * UI Scale} framebuffer pixels, so at UI Scale 1 the screen is 540 units
 * tall (more on a window under 540 pixels). Panels draw and receive the mouse in their own content area's units, (0,0)
 * at its top-left. The exception is {@link dev.myriadclient.api.ui.Ui#draw}, where one unit is one of Minecraft's
 * scaled GUI pixels.
 * <p>
 * <b>Threads.</b> Everything here runs on, and is called from, the render thread.
 */
@NullMarked
package dev.myriadclient.api.ui;

import org.jspecify.annotations.NullMarked;
