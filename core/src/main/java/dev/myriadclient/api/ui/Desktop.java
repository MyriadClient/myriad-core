package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;

/**
 * Myriad's window manager: nine tiling workspaces plus a HUD workspace whose windows are drawn in game.
 */
@ApiStatus.NonExtendable
public interface Desktop {
	int HUD_WORKSPACE = 0;
	int WORKSPACES = 9;

	void open();

	/**
	 * Draws with Myriad's canvas inside any vanilla rendering (a screen, a tooltip, a HUD hook). One canvas unit is
	 * one scaled GUI pixel, matching {@code context}. Does nothing if a canvas is already drawing.
	 */
	void draw(net.minecraft.client.gui.GuiGraphicsExtractor context, java.util.function.Consumer<dev.myriadclient.api.render.Canvas> drawer);

	void close();

	boolean isOpen();

	default void toggle() {
		if (isOpen()) close();
		else open();
	}

	int activeWorkspace();

	void switchWorkspace(int workspace);

	/**
	 * Opens a panel on the active workspace (or focuses it if already open). Panels marked HUD-only open on the HUD
	 * workspace. Returns empty if the type is unknown or the args don't resolve.
	 */
	Optional<Window> openPanel(MyriadId type, JsonObject args);

	default Optional<Window> openPanel(MyriadId type) {
		return openPanel(type, new JsonObject());
	}

	/** Like {@link #openPanel} but on a specific workspace. */
	Optional<Window> openPanel(MyriadId type, JsonObject args, int workspace, boolean floating);

	Optional<Window> find(MyriadId type, JsonObject args);

	List<Window> windows();

	Optional<Window> focused();

	ThemeSettings theme();

	/** Applies a registered theme preset. */
	void applyTheme(MyriadId theme);

	SettingWidgets settingWidgets();

	/**
	 * Asks the player to confirm something ("Delete 40 waypoints?") in a small floating window, opening the menu if
	 * it's closed. {@code onCancel} (may be null) also runs if the window is closed without answering.
	 */
	void confirm(String title, String message, String confirmLabel, Runnable onConfirm, Runnable onCancel);

	default void confirm(String title, String message, Runnable onConfirm) {
		confirm(title, message, "Confirm", onConfirm, null);
	}
}
