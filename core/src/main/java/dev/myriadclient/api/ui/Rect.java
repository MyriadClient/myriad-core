package dev.myriadclient.api.ui;

/**
 * An axis-aligned box in UI units: its top-left corner ({@code x}, {@code y}, y growing downwards) and its size
 * ({@code w}, {@code h}). Immutable.
 */
public record Rect(float x, float y, float w, float h) {
	/** The empty box at the origin. */
	public static final Rect ZERO = new Rect(0, 0, 0, 0);

	/** {@code x + w}. */
	public float right() {
		return x + w;
	}

	/** {@code y + h}. */
	public float bottom() {
		return y + h;
	}

	/** The x of the middle, {@code x + w / 2}. */
	public float centerX() {
		return x + w / 2;
	}

	/** The y of the middle, {@code y + h / 2}. */
	public float centerY() {
		return y + h / 2;
	}

	/** Whether the point is inside: the left and top edges count, the right and bottom ones don't. */
	public boolean contains(double px, double py) {
		return px >= x && py >= y && px < x + w && py < y + h;
	}

	/** This box shrunk by {@code d} on every side (grown for a negative {@code d}); its size never goes below zero. */
	public Rect inset(float d) {
		return new Rect(x + d, y + d, Math.max(0, w - d * 2), Math.max(0, h - d * 2));
	}

	/** This box shrunk by a different amount on each side; its size never goes below zero. */
	public Rect inset(float left, float top, float right, float bottom) {
		return new Rect(x + left, y + top, Math.max(0, w - left - right), Math.max(0, h - top - bottom));
	}

	/** The box {@code t} of the way from this one to {@code o} (0: this, 1: {@code o}), each field linearly. */
	public Rect lerp(Rect o, float t) {
		return new Rect(x + (o.x - x) * t, y + (o.y - y) * t, w + (o.w - w) * t, h + (o.h - h) * t);
	}
}
