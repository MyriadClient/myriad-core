package dev.myriadclient.api.ui;

import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.MyriadId;

/** A block in the desktop's top bar (Waybar-style). */
public abstract class BarWidget implements Identified {
	public enum Side {
		LEFT, CENTER, RIGHT
	}

	private final MyriadId id;
	private final String name;
	private final Side side;
	private final int order;

	/** @param order sorting within the side; lower is further left */
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

	public String name() {
		return name;
	}

	public Side side() {
		return side;
	}

	public int order() {
		return order;
	}

	/** Width this widget needs at the given bar height. Return 0 to hide it. */
	public abstract float width(Canvas canvas, float height);

	/** Draws the widget in its slot. */
	public abstract void render(Canvas canvas, float x, float y, float width, float height, float mouseX, float mouseY);

	/** Mouse click inside the widget's slot; coordinates are relative to the slot. */
	public boolean mouseClicked(float mouseX, float mouseY, int button) {
		return false;
	}
}
