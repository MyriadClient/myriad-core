package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;

import java.util.Set;

/**
 * A module inside a category window: clicking the header toggles the module, and its settings unfold underneath
 * (right-click, the chevron, or l/→ with the keyboard). Shift+right-click opens the settings in their own window.
 */
class ModuleEntry extends CardEntry {
	protected final Module module;
	private final Set<Module> expandedSet;
	private final String source;

	ModuleEntry(Module module, Set<Module> expandedSet) {
		super(module.isEnabled(), expandedSet.contains(module));
		this.module = module;
		this.expandedSet = expandedSet;
		this.source = AddonNames.of(module);
		this.tooltip = tooltipText();
	}

	@Override
	protected String title() {
		return module.name();
	}

	@Override
	protected boolean isOn() {
		return module.isEnabled();
	}

	@Override
	protected int accent() {
		return module.category().color();
	}

	@Override
	protected boolean isExpanded() {
		return expandedSet.contains(module);
	}

	@Override
	protected void setExpanded(boolean value) {
		if (value) expandedSet.add(module);
		else expandedSet.remove(module);
	}

	@Override
	protected void buildBody(VBox body) {
		body.add(new Info());
		body.add(SettingsView.build(module.settings));
	}

	@Override
	protected void primary() {
		module.toggle();
	}

	@Override
	protected void middle() {
		module.visibleInList.toggle();
	}

	@Override
	protected void shiftSecondary() {
		CorePanels.openModuleSettings(module, true);
	}

	@Override
	protected float drawBadges(Canvas c, float right, float cy) {
		if (module.keybind.get().isSet()) right = badge(c, module.keybind.get().displayName(), right, cy, theme().textDim.argb(), false) - 4;
		return right;
	}

	/** Description, then the source addon and category as pills (in the category's current colour). */
	@Override
	protected String tooltipText() {
		return (module.description().isEmpty() ? "" : module.description() + "\n")
			+ badgeLine(" " + source + "@" + String.format("%08X", module.category().color()), module.category().name());
	}

	/** Description, then badges for the source addon and the category, at the top of the card. */
	private final class Info extends Widget {
		private final Label description = new Label(module.description()).dim();

		@Override
		protected float measure(Canvas c, float width) {
			float h = 0;
			if (!module.description().isEmpty()) h += description.layout(c, x, y, width) + 3;
			return h + c.textHeight(FontFamily.SANS, c.defaultFontSize() * 0.7f) + 3;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			if (!module.description().isEmpty()) description.render(c, mx, my);
			float s = c.defaultFontSize() * 0.7f;
			float th = c.textHeight(FontFamily.SANS, s) + 3;
			float cy = y + height - th / 2;
			int accent = module.category().color();
			String tag = " " + source;
			float bx = x + badgeWidth(c, tag, true);
			badge(c, tag, bx, cy, accent, true);
			String category = module.category().name();
			badge(c, category, bx + 4 + badgeWidth(c, category, false), cy, theme().textDim.argb(), false);
		}
	}
}
