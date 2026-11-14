package dev.myriadclient.api.ui;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.ui.draw.Canvas;

/**
 * A block in the UI's top bar (Waybar-style), shown while the menu is open and Theme &rarr; Bar is on. Register one
 * with {@code AddonContext.registerBarWidget}. Each frame the bar asks every widget its {@link #width}, lays them out
 * on their {@link #side} by {@link #order} (8 units apart, 6 from the screen edges) and calls {@link #render}.
 * Exceptions thrown here are caught; the widget is skipped for that frame.
 *
 * <pre>{@code
 * public final class ClockWidget extends BarWidget {
 *     public ClockWidget(MyriadId id) { super(id, "Clock", Side.RIGHT, 100); }
 *
 *     @Override
 *     public float width(Canvas c, float height) { return c.textWidth(text()); }
 *
 *     @Override
 *     public void render(Canvas c, float x, float y, float w, float h, float mx, float my) {
 *         c.text(text(), x, y + (h - c.textHeight()) / 2, Myriad.ui().theme().text.argb());
 *     }
 * }
 * }</pre>
 */
public abstract class BarWidget implements Identified {
	/** Which part of the bar a widget sits in. */
	public enum Side {
		/** From the left edge rightwards. */
		LEFT,
		/** Centred together as a group. */
		CENTER,
		/** Against the right edge. */
		RIGHT
	}

	private final MyriadId id;
	private final String name;
	private final Side side;
	private final int order;

	/**
	 * @param id    its registry id
	 * @param name  the name players see for it
	 * @param side  which part of the bar it sits in
	 * @param order sorting within the side; lower is further left
	 */
	protected BarWidget(MyriadId id, String name, Side side, int order) {
		this.id = id;
		this.name = name;
		this.side = side;
		this.order = order;
	}

	@Override
	public MyriadId id() {
		return id;
	}

	/** The name players see for it. */
	public String name() {
		return name;
	}

	/** Which part of the bar it sits in. */
	public Side side() {
		return side;
	}

	/** Its place within its side: lower is further left. */
	public int order() {
		return order;
	}

	/**
	 * Width this widget needs, in UI units, at the given bar height (Theme &rarr; Bar &rarr; Height, in UI units).
	 * Called every frame the bar is drawn, and for clicks. Return 0 (or less) to hide it.
	 */
	public abstract float width(Canvas canvas, float height);

	/**
	 * Draws the widget in its slot. All coordinates are the screen's, in UI units: ({@code x}, {@code y}) is the slot's
	 * top-left, {@code width} what {@link #width} returned, {@code height} the bar's, and the mouse is where the pointer
	 * is on screen. Nothing clips to the slot.
	 */
	public abstract void render(Canvas canvas, float x, float y, float width, float height, float mouseX, float mouseY);

	/**
	 * A mouse press inside the widget's slot; coordinates are relative to the slot's top-left, in UI units, and
	 * {@code button} is a GLFW button ({@code InputConstants.MOUSE_BUTTON_*}). The click is the bar's whatever this
	 * returns; the default does nothing.
	 */
	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		return false;
	}
}
