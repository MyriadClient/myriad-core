package dev.myriadclient.api.ui;

/** An axis-aligned box in UI units. */
public record Rect(float x, float y, float w, float h) {
	public static final Rect ZERO = new Rect(0, 0, 0, 0);

	public float right() {
		return x + w;
	}

	public float bottom() {
		return y + h;
	}

	public float centerX() {
		return x + w / 2;
	}

	public float centerY() {
		return y + h / 2;
	}

	public boolean contains(double px, double py) {
		return px >= x && py >= y && px < x + w && py < y + h;
	}

	public Rect inset(float d) {
		return new Rect(x + d, y + d, Math.max(0, w - d * 2), Math.max(0, h - d * 2));
	}

	public Rect inset(float left, float top, float right, float bottom) {
		return new Rect(x + left, y + top, Math.max(0, w - left - right), Math.max(0, h - top - bottom));
	}

	public Rect lerp(Rect o, float t) {
		return new Rect(x + (o.x - x) * t, y + (o.y - y) * t, w + (o.w - w) * t, h + (o.h - h) * t);
	}
}
