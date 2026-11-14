package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.ui.draw.Canvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Myriad's window manager, the UI (players call it the menu), from {@link dev.myriadclient.api.Myriad#ui()}: nine
 * tiling workspaces plus a HUD workspace whose windows are drawn in game. It's a screen of its own while open; its
 * windows, layouts and theme are saved between sessions. Call it on the render thread.
 *
 * <pre>{@code
 * PanelType waypoints = ctx.registerPanel("Waypoints", Icons.MAP_MARKER, WaypointsPanel::new);
 * // later, from a key action:
 * Myriad.ui().open();
 * Myriad.ui().openPanel(waypoints);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface Ui {
	/** The HUD workspace's number: its windows are HUD elements, drawn in game while the menu is shut. */
	int HUD_WORKSPACE = 0;
	/** How many normal workspaces there are, numbered 1 to {@code WORKSPACES}. */
	int WORKSPACES = 9;

	/** Shows the menu (as Minecraft's current screen) on the active workspace; does nothing if it's already open. */
	void open();

	/**
	 * Draws with Myriad's canvas inside any vanilla rendering (a screen, a tooltip, a HUD hook). One canvas unit is
	 * one scaled GUI pixel, matching {@code context}, and the theme's font and blur apply. {@code drawer} runs at once
	 * and everything is submitted before this returns. Does nothing if a canvas is already drawing (from inside a
	 * panel, use the canvas you were given).
	 */
	void draw(GuiGraphicsExtractor context, Consumer<Canvas> drawer);

	/** Shuts the menu if it's open, back to the game. Windows stay where they are for next time. */
	void close();

	/** Whether the menu is Minecraft's current screen. */
	boolean isOpen();

	/** Opens the menu if it's shut, shuts it if it's open. */
	default void toggle() {
		if (isOpen()) close();
		else open();
	}

	/** The workspace shown (or shown next time the menu opens): 1-9, or {@link #HUD_WORKSPACE} while editing the HUD. */
	int activeWorkspace();

	/** Shows workspace {@code workspace} (clamped to 0-9), with the theme's workspace animation. Doesn't open the menu. */
	void switchWorkspace(int workspace);

	/**
	 * Opens a panel on the active workspace (or focuses it if a window with the same type and args is open, switching
	 * to its workspace while the menu shows). It tiles, unless the HUD workspace is active, where it floats. A HUD
	 * element is put on the HUD instead (see {@link PanelType#isHud}). Returns empty if the type is unknown or its
	 * factory returns null or throws (a toast says so). Doesn't open the menu.
	 *
	 * @param args the instance's args, or null for none (an empty object)
	 */
	Optional<Window> openPanel(MyriadId type, @Nullable JsonObject args);

	/** {@link #openPanel(MyriadId, JsonObject)} with no args. */
	default Optional<Window> openPanel(MyriadId type) {
		return openPanel(type, new JsonObject());
	}

	/** {@link #openPanel(MyriadId, JsonObject)} for a type you registered ({@code ctx.registerPanel} returns it). */
	default Optional<Window> openPanel(PanelType type) {
		return openPanel(type.id());
	}

	/** {@link #openPanel(MyriadId, JsonObject)} for a type you registered, with args. */
	default Optional<Window> openPanel(PanelType type, @Nullable JsonObject args) {
		return openPanel(type.id(), args);
	}

	/**
	 * Like {@link #openPanel} but on workspace {@code workspace}, floating or tiled. An open window with the same type
	 * and args is moved there instead and focused. HUD elements still go on the HUD.
	 */
	Optional<Window> openPanel(MyriadId type, @Nullable JsonObject args, int workspace, boolean floating);

	/** The open window of this type with args equal to {@code args} (null: none), on any workspace. */
	Optional<Window> find(MyriadId type, @Nullable JsonObject args);

	/** Every open window, HUD elements included, as a copy taken now. */
	List<Window> windows();

	/** The active workspace's focused window, if it has one. */
	Optional<Window> focused();

	/** The live theme: read colours and sizes from it when drawing, or change its settings. */
	ThemeSettings theme();

	/**
	 * Applies a registered theme preset (or one that lists {@code theme} among its {@link Theme#aliases}): every theme
	 * value is reset, then the preset and the player's saved edits to it are written, and a toast says so. Does
	 * nothing for an unknown id.
	 */
	void applyTheme(MyriadId theme);

	/** {@link #applyTheme(MyriadId)} for a theme you registered. */
	default void applyTheme(Theme theme) {
		applyTheme(theme.id());
	}

	/** Which widget edits which kind of setting; register editors for your own setting classes here. */
	SettingWidgets settingWidgets();

	/**
	 * Asks the player to confirm something ("Delete 40 waypoints?") in a small floating window, opening the menu if
	 * it's closed (on workspace 1 if the HUD is being edited). {@code onCancel} (may be null) also runs if the window is
	 * closed without answering. Only one of the two ever runs, and only once. The window isn't kept across restarts.
	 */
	void confirm(String title, String message, String confirmLabel, Runnable onConfirm, @Nullable Runnable onCancel);

	/** {@link #confirm(String, String, String, Runnable, Runnable)} with a "Confirm" button and nothing on cancel. */
	default void confirm(String title, String message, Runnable onConfirm) {
		confirm(title, message, "Confirm", onConfirm, null);
	}
}
