package dev.myriadclient.api.ui.widget;

import dev.myriadclient.api.ui.draw.Animated;
import dev.myriadclient.api.ui.draw.Bezier;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.util.ColorUtil;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A titled section whose body can be folded away: click the header (or Enter, or h/l with the keyboard selection on
 * it) to fold or unfold it, with an animation. Folded, the body takes no room and gets no input.
 *
 * <pre>{@code
 * VBox body = new VBox(2, 0);
 * body.add(new Label("Inside"));
 * content.add(new Collapsible("Advanced", body).indent(10).hint(() -> "1"));
 * }</pre>
 */
public class Collapsible extends Widget {
	private final Supplier<String> title;
	private final Widget body;
	private final Supplier<Boolean> expandedGetter;
	private final Consumer<Boolean> expandedSetter;
	private final Animated open;
	private float indent;
	private @Nullable Supplier<@Nullable String> hint;

	/**
	 * A section whose folded state lives elsewhere (a setting group's, say): {@code expanded} is read every frame and
	 * {@code setExpanded} called when the player folds or unfolds it.
	 */
	public Collapsible(Supplier<String> title, Widget body, Supplier<Boolean> expanded, Consumer<Boolean> setExpanded) {
		this.title = title;
		this.body = body;
		this.expandedGetter = expanded;
		this.expandedSetter = setExpanded;
		this.open = new Animated(expanded.get() ? 1 : 0);
	}

	/** A section that starts unfolded and remembers its state itself (until the widgets are rebuilt). */
	public Collapsible(String title, Widget body) {
		this(() -> title, body, new boolean[]{true});
	}

	private Collapsible(Supplier<String> title, Widget body, boolean[] state) {
		this(title, body, () -> state[0], v -> state[0] = v);
	}

	/**
	 * Indents the body by {@code indent} UI units and draws a guide line down its left edge, so nested sections read as
	 * a tree. Returns this section.
	 */
	public Collapsible indent(float indent) {
		this.indent = indent;
		return this;
	}

	/**
	 * Dim text at the right of the header (e.g. how many settings the section holds), asked every frame; null or empty
	 * shows none. It gives way to the title in a narrow window. Returns this section.
	 */
	public Collapsible hint(Supplier<@Nullable String> hint) {
		this.hint = hint;
		return this;
	}

	@Override
	void attach(WidgetRoot root, Widget parent) {
		super.attach(root, parent);
		body.attach(root, this);
	}

	@Override
	protected float measure(Canvas canvas, float width) {
		open.animateTo(expandedGetter.get() ? 1 : 0, 200, Bezier.EASE_OUT_QUINT);
		float bh = body.layout(canvas, x + indent, y + ROW + 3, width - indent);
		return ROW + (bh + 5) * open.get();
	}

	@Override
	public void render(Canvas c, float mx, float my) {
		boolean hover = mx >= x && my >= y && mx < x + width && my < y + ROW;
		boolean expanded = expandedGetter.get();
		if (hover) c.roundRect(x - 2, y, width + 4, ROW, 3, theme().surface.argb());
		float iconSize = c.defaultFontSize() * 0.75f;
		String chevron = expanded ? "\uf078" : "\uf054";
		c.text(FontFamily.MONO, iconSize, chevron, x + 1, y + (ROW - c.textHeight(FontFamily.MONO, iconSize)) / 2,
			expanded ? theme().accent.argb() : theme().textDim.argb());
		// The hint is what gives way: the name keeps at least enough to tell which section this is.
		float room = width - 13;
		float titleW = c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), title.get());
		if (hint != null) {
			String h = hint.get();
			if (h != null && !h.isEmpty()) {
				float hs = c.defaultFontSize() * 0.8f;
				float hintRoom = room - 4 - Math.min(titleW, 44);
				String shown = c.ellipsize(FontFamily.SANS, hs, h, hintRoom);
				if (hintRoom > 12) {
					float hw = c.textWidth(FontFamily.SANS, hs, shown);
					c.text(FontFamily.SANS, hs, shown, x + width - 2 - hw, y + (ROW - c.textHeight(FontFamily.SANS, hs)) / 2, theme().textDim.argb());
					room -= hw + 4;
				}
			}
		}
		c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), c.ellipsize(FontFamily.SANS_BOLD, c.defaultFontSize(), title.get(), room), x + 11, y + (ROW - c.textHeight()) / 2,
			hover || expanded ? theme().text.argb() : theme().textDim.argb());
		float t = open.get();
		if (t > 0.01f) {
			c.push();
			c.clip(x - 2, y + ROW, width + 4, height - ROW);
			if (t < 1) c.alpha(t);
			// The guide line ties the indented body to its header.
			if (indent > 0) {
				c.roundRect(x + 3, y + ROW + 1, 1.5f, height - ROW - 3, 0.75f, ColorUtil.withAlpha(theme().accent.argb(), 120));
			}
			body.render(c, mx, my);
			c.pop();
		}
	}

	@Override
	public boolean mouseClicked(float mx, float my, int button) {
		if (mx >= x && my >= y && mx < x + width && my < y + ROW) {
			expandedSetter.accept(!expandedGetter.get());
			return true;
		}
		return expandedGetter.get() && body.isVisible() && body.mouseClicked(mx, my, button) && pressBody();
	}

	private boolean pressBody() {
		if (root != null && root.pressed() == null) root.setPressed(body);
		return true;
	}

	@Override
	public boolean isNavigable() {
		return true;
	}

	@Override
	public void activate() {
		expandedSetter.accept(!expandedGetter.get());
	}

	@Override
	public boolean adjust(int direction) {
		boolean open = direction > 0;
		if (expandedGetter.get() == open) return false;
		expandedSetter.accept(open);
		return true;
	}

	/** Navigation only reaches the header's own row. */
	@Override
	public float navHeight() {
		return ROW;
	}

	@Override
	public void collectNavigable(List<Widget> out) {
		if (!isVisible()) return;
		out.add(this);
		if (expandedGetter.get()) body.collectNavigable(out);
	}

	@Override
	public boolean mouseScrolled(float mx, float my, float amount) {
		return expandedGetter.get() && body.mouseScrolled(mx, my, amount);
	}
}
