package dev.myriadclient.api.ui.layout;

import com.google.gson.JsonObject;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.Direction;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The tiling of one workspace. {@code W} is an opaque window handle; layouts only compare handles by identity.
 */
@ApiStatus.NonExtendable
public interface LayoutState<W> {
	/** Adds a window. {@code focused} is the currently focused tiled window (or null), which new windows split. */
	void add(W window, W focused);

	void remove(W window);

	List<W> windows();

	/**
	 * Computes tile rectangles inside {@code area} with {@code gap} units between tiles (outer gaps are already
	 * applied to the area).
	 */
	void arrange(Rect area, float gap, BiConsumer<W, Rect> out);

	/** Swap the positions of two windows. */
	void swap(W a, W b);

	/**
	 * Moves {@code window} next to {@code target}, on its {@code side}: {@code target}'s tile is split, {@code window}
	 * taking the side towards {@code side} (a window dropped near another's edge). Layouts that can't place a window
	 * on a side put it next to {@code target} as {@link #add} would.
	 */
	@ApiStatus.AvailableSince("1.3")
	default void insertBeside(W window, W target, Direction side) {
		if (window == target) return;
		remove(window);
		add(window, target);
	}

	/** Flip the split orientation around {@code window} (dwindle) or do nothing. */
	default void toggleSplit(W window) {
	}

	/**
	 * Grow ({@code amount} &gt; 0) or shrink the window's tile by a fraction of its container, for keyboard resizing.
	 * Layouts that can't resize ignore it.
	 */
	default void resize(W window, float amount) {
	}

	/**
	 * Scroll the tiling sideways by {@code amount} mouse-wheel notches (positive = towards the start), for layouts whose
	 * tiles can be wider than the screen. Returns whether it scrolled; layouts that don't scroll return false.
	 */
	default boolean scroll(float amount) {
		return false;
	}

	/** Scroll so {@code window} is in view, if the layout scrolls; called when a window is focused. */
	default void reveal(W window) {
	}

	/** Draggable boundaries between tiles, valid after the last {@link #arrange}. */
	List<Splitter> splitters();

	/** Serialise using the given window keys. */
	JsonObject save(Function<W, String> keys);

	/** Restore; {@code windows} resolves keys (returning null for windows that no longer exist). */
	void load(JsonObject json, Function<String, W> windows);

	/** A resizable boundary: {@code bounds} is the hit area; {@link #drag} receives the mouse position. */
	@ApiStatus.NonExtendable
	interface Splitter {
		Rect bounds();

		boolean vertical();

		void drag(float mouseX, float mouseY);
	}
}
