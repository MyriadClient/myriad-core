package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;

import java.util.Map;
import java.util.Set;

/**
 * A module inside a category window: clicking the header toggles the module, and its settings unfold underneath
 * (right-click, the chevron, or l/→ with the keyboard). Shift+right-click opens the settings in their own window.
 */
class ModuleEntry extends CardEntry {
	protected final Module module;
	private final Set<Module> expandedSet;
	/** Which of its settings groups are open in this window (another window showing the module folds them on its own). */
	private final Map<SettingGroup, Boolean> groupsOpen;
	private final String source;

	ModuleEntry(Module module, Set<Module> expandedSet, Map<SettingGroup, Boolean> groupsOpen) {
		super(module.isEnabled(), expandedSet.contains(module));
		this.module = module;
		this.expandedSet = expandedSet;
		this.groupsOpen = groupsOpen;
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
		return module.tag().color();
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
		body.add(FoldedSettings.build(module.settings, groupsOpen));
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
		String by = module.suspendedBy();
		if (by != null) right = badge(c, "suspended by " + by, right, cy, theme().accent.argb(), true) - 4;
		return right;
	}

	/** Description, then the source addon and the tags as pills (in the main tag's current colour). */
	@Override
	protected String tooltipText() {
		String by = module.suspendedBy();
		return (module.description().isEmpty() ? "" : module.description() + "\n")
			+ (by == null ? "" : by + " holds it off for now; it comes back on when that's done.\n")
			+ badgeLine(" " + source + "@" + String.format("%08X", module.tag().color()), tagNames());
	}

	/** The module's tags, for its badge: "Combat, World". */
	private String tagNames() {
		return String.join(", ", module.tags().stream().map(Tag::name).toList());
	}

	/** Description, then badges for the source addon and the tags, at the top of the card. */
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
			int accent = module.tag().color();
			String tag = " " + source;
			float bx = x + badgeWidth(c, tag, true);
			badge(c, tag, bx, cy, accent, true);
			String tags = tagNames();
			badge(c, tags, bx + 4 + badgeWidth(c, tags, false), cy, theme().textDim.argb(), false);
		}
	}
}
