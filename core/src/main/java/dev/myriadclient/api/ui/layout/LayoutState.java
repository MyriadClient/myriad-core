package dev.myriadclient.api.ui.layout;

import com.google.gson.JsonObject;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.Side;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The tiling of one workspace. {@code W} is an opaque window handle; layouts only compare handles by identity. The UI
 * calls it on the render thread: {@link #arrange} every frame the workspace is drawn, the rest as windows come, go and
 * are moved. All rectangles and positions are the screen's, in UI units. Floating windows never reach it.
 *
 * @param <W> the window handle type
 */
@ApiStatus.NonExtendable
public interface LayoutState<W> {
	/**
	 * Adds a window. {@code focused} is the currently focused tiled window (or null), which new windows split. Never
	 * called with a window that's already in the state.
	 */
	void add(W window, @Nullable W focused);

	/** Removes a window; one that isn't in the state is ignored. */
	void remove(W window);

	/** The windows it holds, in its own order (the last is where a window put back into the tiling goes beside). */
	List<W> windows();

	/**
	 * Computes tile rectangles inside {@code area} with {@code gap} units between tiles (outer gaps are already
	 * applied to the area), giving each window's to {@code out}. Every window should get one; the UI animates windows
	 * towards them.
	 */
	void arrange(Rect area, float gap, BiConsumer<W, Rect> out);

	/** Swap the positions of two windows (one dragged onto the middle of the other). */
	void swap(W a, W b);

	/**
	 * Moves {@code window} next to {@code target}, on its {@code side}: {@code target}'s tile is split, {@code window}
	 * taking the side towards {@code side} (a window dropped near another's edge). Layouts that can't place a window
	 * on a side put it next to {@code target} as {@link #add} would.
	 */
	@ApiStatus.AvailableSince("1.3")
	default void insertBeside(W window, W target, Side side) {
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

	/**
	 * Draggable boundaries between tiles, valid after the last {@link #arrange}. The UI grabs one when a press lands in
	 * its bounds, and uses them for resizing a tile with the mod key held.
	 */
	List<Splitter> splitters();

	/**
	 * Serialises the tiling, naming each window by {@code keys} (a string that stays the same across restarts), for the
	 * UI's saved layout.
	 */
	JsonObject save(Function<W, String> keys);

	/**
	 * Restores what {@link #save} wrote into this empty state; {@code windows} resolves keys (returning null for
	 * windows that no longer exist, or were placed already), and those are skipped. Windows the saved tiling doesn't
	 * place are {@link #add}ed afterwards.
	 */
	void load(JsonObject json, Function<String, @Nullable W> windows);

	/** A resizable boundary: {@code bounds} is the hit area; {@link #drag} receives the mouse position. */
	@ApiStatus.NonExtendable
	interface Splitter {
		/** The area a press grabs it in, in screen UI units. */
		Rect bounds();

		/** Whether it's a vertical line (between tiles side by side, dragged left and right). */
		boolean vertical();

		/** Moves the boundary to follow the mouse at ({@code mouseX}, {@code mouseY}), in screen UI units. */
		void drag(float mouseX, float mouseY);
	}
}
