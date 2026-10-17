package dev.myriadclient.api.ui;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.ApiStatus;

/** A window managed by the desktop. */
@ApiStatus.NonExtendable
public interface Window {
	Panel panel();

	PanelType type();

	JsonObject args();

	/** 1-9 for normal workspaces, {@link Desktop#HUD_WORKSPACE} for the HUD. */
	int workspace();

	boolean isFloating();

	void setFloating(boolean floating);

	boolean isFullscreen();

	void setFullscreen(boolean fullscreen);

	/** Current on-screen bounds (including decorations), in UI units. */
	Rect bounds();

	void focus();

	void close();

	void moveToWorkspace(int workspace);

	/**
	 * For HUD elements: 0 when anchored to the left third of the screen, 0.5 in the middle, 1 on the right. HUD
	 * panels use it to align content towards the screen edge (e.g. a right-aligned module list).
	 */
	float hudAlignX();
}
