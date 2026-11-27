package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.util.ColorUtil;
import org.jetbrains.annotations.ApiStatus;

/** Empty space, optionally with a divider line across its middle. It never asks for width in a row. */
public class Spacer extends Widget {
	private final float size;
	private final boolean line;
	private final boolean fill;
	/** A {@link #fill()}'s height, set by the {@link ScrollView} it's in. */
	float extra;

	/**
	 * @param size its height, in UI units
	 * @param line whether a faint line runs across its middle
	 */
	public Spacer(float size, boolean line) {
		this(size, line, false);
	}

	private Spacer(float size, boolean line, boolean fill) {
		this.size = size;
		this.line = line;
		this.fill = fill;
	}

	/**
	 * The room left at the foot of a panel's column, so what comes after it sits at the bottom of the panel (a footer, a
	 * Reset or Sign out kept away from the settings above it). Once the content fills the panel and scrolls, it takes no
	 * room, and what follows simply ends the content. It works as a direct child of a {@link dev.myriadclient.api.ui.WidgetPanel}'s column (or of
	 * any {@link ScrollView} given a fixed height); only the first one counts.
	 */
	@ApiStatus.AvailableSince("2.11")
	public static Spacer fill() {
		return new Spacer(0, false, true);
	}

	boolean fills() {
		return fill;
	}

	/** Empty space {@code size} units tall (or, in a row, a weighted spacer pushing its neighbours apart). */
	public static Spacer gap(float size) {
		return new Spacer(size, false);
	}

	/** A faint horizontal line with a little room above and below (7 units tall). */
	public static Spacer divider() {
		return new Spacer(7, true);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		return fill ? extra : size;
	}

	@Override
	public float minWidth(Canvas canvas) {
		return 0;
	}

	@Override
	public void render(Canvas canvas, float mx, float my) {
		if (line) canvas.rect(x, y + size / 2, width, 1, ColorUtil.withAlpha(theme().textDim.argb(), 40));
	}
}
