package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;

/**
 * A window managed by the UI: one {@link Panel} on a workspace, tiled by the workspace's layout or floating above it,
 * or a HUD element on the HUD workspace. Get one from {@link Ui#openPanel}, {@link Ui#find}, {@link Ui#windows()} or a
 * panel's own {@link Panel#window()}. A closed window stays a valid object but is no longer in {@link Ui#windows()}.
 */
@ApiStatus.NonExtendable
public interface Window {
	/** The panel it shows, for the window's whole life. */
	Panel panel();

	/** The kind of panel it shows. */
	PanelType type();

	/**
	 * The args the panel was opened with (an empty object for none). Together with {@link #type()} they identify the
	 * window: opening the same type with equal args focuses this one. Don't change it.
	 */
	JsonObject args();

	/** 1-9 for normal workspaces, {@link Ui#HUD_WORKSPACE} for the HUD. */
	int workspace();

	/** Whether it floats above the tiling (always true on the HUD workspace). */
	boolean isFloating();

	/**
	 * Floats it above the tiling, near where its tile was and a little smaller, or puts it back into the tiling (after
	 * the last tiled window). Focuses it. Does nothing on the HUD workspace, where everything floats.
	 */
	void setFloating(boolean floating);

	/** Whether it fills the workspace's area (everything but the bar), with no title bar or rounding. */
	boolean isFullscreen();

	/** Makes it fill the workspace's area, or gives it back its tile or floating place. The HUD's sidebar ignores it. */
	void setFullscreen(boolean fullscreen);

	/**
	 * Current on-screen bounds (including the border and title bar), in UI units, as animated this frame. For a HUD
	 * element this isn't where it's drawn, which follows its anchor and its panel's preferred size.
	 */
	Rect bounds();

	/**
	 * Makes it its workspace's focused window: a floating window comes to the front, a tiled one is scrolled into view
	 * (in layouts that scroll). Doesn't switch workspace or open the menu.
	 */
	void focus();

	/**
	 * Closes it: it leaves its workspace, its panel's {@link Panel#onClose()} runs, and it fades out. Does nothing if
	 * it's already closed, or for the HUD's own sidebar.
	 */
	void close();

	/**
	 * Moves it to workspace {@code workspace} (clamped to 0-9), keeping it floating or tiled. A window that isn't a HUD
	 * element can't go to the HUD, and a HUD element can't leave it: both are refused with a warning toast.
	 */
	void moveToWorkspace(int workspace);

	/**
	 * For HUD elements: 0 when anchored to the left third of the screen, 0.5 in the middle, 1 on the right. HUD
	 * panels use it to align content towards the screen edge (e.g. a right-aligned module list). It follows the
	 * element as players drag it from one third to another.
	 */
	float hudAlignX();
}
