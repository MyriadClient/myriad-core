package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Bezier;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.util.ColorUtil;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** A horizontal slider. Right-click to type an exact value. */
public class Slider extends Widget {
	private final DoubleSupplier getter;
	private final DoubleConsumer setter;
	private final double min, max;
	private final int decimals;
	private final Animated fill = new Animated(0);
	private boolean dragging;
	private TextField editor;

	public Slider(DoubleSupplier getter, DoubleConsumer setter, double min, double max, int decimals) {
		this.getter = getter;
		this.setter = setter;
		this.min = min;
		this.max = max;
		this.decimals = decimals;
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		if (editor != null) editor.attach(root, this);
	}

	private String format(double v) {
		return decimals <= 0 ? String.valueOf(Math.round(v)) : String.format("%." + decimals + "f", v);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		if (editor != null) editor.layout(canvas, x, y, width);
		return ROW;
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		if (editor != null) {
			if (!editor.isFocused()) editor = null;
			else {
				editor.render(c, mx, my);
				return;
			}
		}
		double v = getter.getAsDouble();
		float t = (float) Math.clamp((v - min) / (max - min), 0, 1);
		fill.animateTo(t, dragging ? 0 : 140, Bezier.EASE_OUT_QUINT);
		boolean hover = isHovered(mx, my) || dragging;
		c.roundRect(x, y, width, height, 4, hover ? theme().surfaceHover.argb() : theme().surface.argb());
		float fw = width * fill.get();
		if (fw > 0.5f) c.roundRect(x, y, fw, height, 4, fw >= width - 4 ? 4 : Math.min(4, fw / 2), Math.min(4, fw / 2), 4, ColorUtil.withAlpha(theme().accent.argb(), 150));
		String text = format(v);
		c.text(text, x + width - c.textWidth(text) - 4, y + (height - c.textHeight()) / 2, theme().text.argb());
		offerTooltip(mx, my);
	}

	private void apply(float mx) {
		double t = Math.clamp((mx - x) / width, 0, 1);
		double v = min + (max - min) * t;
		double scale = Math.pow(10, Math.max(0, decimals));
		setter.accept(Math.round(v * scale) / scale);
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	/** Enter types an exact value. */
	@Override
	public void activate() {
		openEditor();
	}

	/** h/l step by a twentieth of the range (whole numbers for integer sliders); hold Shift for finer steps. */
	@Override
	public boolean adjust(int direction) {
		double range = max - min;
		double step = decimals <= 0 ? Math.max(1, Math.round(range / 20)) : range / 20;
		long window = net.minecraft.client.Minecraft.getInstance().getWindow().handle();
		if (org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
			step = decimals <= 0 ? 1 : step / 5;
		}
		double scale = Math.pow(10, Math.max(0, decimals));
		setter.accept(Math.clamp(Math.round((getter.getAsDouble() + step * direction) * scale) / scale, min, max));
		return true;
	}

	private void openEditor() {
		editor = new TextField(() -> format(getter.getAsDouble())).onSubmit(s -> {
			try {
				setter.accept(Double.parseDouble(s.trim()));
			} catch (NumberFormatException ignored) {
			}
		});
		editor.attach(root, this);
		editor.layout(null, x, y, width);
		editor.requestFocus();
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (editor != null && editor.mouseClicked(mx, my, button)) return true;
		if (!isHovered(mx, my)) return false;
		if (button == 1) {
			openEditor();
			return true;
		}
		dragging = true;
		apply(mx);
		return true;
	}

	@Override
	public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
		if (!dragging) return false;
		apply(mx);
		return true;
	}

	@Override
	public boolean mouseReleased(float mx, float my, int button) {
		dragging = false;
		return true;
	}
}
