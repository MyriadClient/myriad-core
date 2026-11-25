package dev.myriadclient.impl.ui.panels;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.draw.FontFamily;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.Note;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.Spacer;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.ui.ThemeManager;
import dev.myriadclient.impl.ui.WindowManager;

/**
 * The theme list (pick, create, duplicate, rename, delete or reset themes) and the active theme's values. Edits save
 * into the active theme straight away; personal options live under Preferences and don't change with the theme.
 */
public final class ThemePanel extends WidgetPanel {
	private final WindowManager wm = (WindowManager) Myriad.ui();
	/** The theme unfolded (only ever the active one shows its settings), or null. */
	private @org.jetbrains.annotations.Nullable dev.myriadclient.api.registry.MyriadId unfolded;
	private int builtVersion = -1;

	@Override
	public String title() {
		return "Theme";
	}

	@Override
	public String icon() {
		return "";
	}

	@Override
	public void tick() {
		// The list, names and active theme change from here, from commands and from live themes; rebuild when they do.
		if (builtVersion != wm.themes().version()) rebuild();
	}

	@Override
	protected void build(VBox content) {
		ThemeManager themes = wm.themes();
		builtVersion = themes.version();
		ThemeManager.Entry active = themes.active();

		// The themes, to switch with a click; a right-click unfolds the one you're on (switching to it first), its settings
		// under it, as a module's are.
		VBox list = new VBox(2, 0);
		for (ThemeManager.Entry e : themes.all()) {
			ThemeRow row = new ThemeRow(e);
			if (e != active || !e.id().equals(unfolded)) {
				list.add(row);
				continue;
			}
			// Unfolded, the row and its settings are one card, as a module's are.
			ThemeCard card = new ThemeCard(row);
			card.add(row);
			card.add(indented(themeBody(themes, e)));
			list.add(card);
		}
		content.add(list);

		// Themes can explain problems (e.g. a system theme that can't be read). The first line is the message; any
		// further lines are shown as-is in a monospace font (commands to run, paths).
		for (Theme t : Myriad.themes()) {
			String notice = t.notice();
			if (notice == null || notice.isBlank()) continue;
			String[] lines = notice.split("\n");
			content.add(new Note(t.name() + ": " + lines[0]).warn());
			for (int i = 1; i < lines.length; i++) content.add(new Label(lines[i]).font(FontFamily.MONO, 0).dim());
		}

		// What isn't about one theme: a new one, and the theme files.
		content.add(new Toolbar()
			.add("\uf067", "New", "A new theme starting from the current one.", () -> {
				unfolded = null;
				themes.create(themes.uniqueName("New Theme"));
			})
			.gap()
			.add("\uf07c", "Folder", "Theme files live here. Share one by sending its .json; drop a received one in and press Reload.",
				() -> Blaze3D.openPath(themes.folder()))
			.add("\uf021", "Reload", "Read the theme files again.", () -> {
				themes.reload();
				themes.reapply();
			}));

		VBox prefs = new VBox(4, 0);
		prefs.add(new Label("These stay the same whichever theme is active.").dim());
		prefs.add(preferences());
		content.add(new Collapsible("Preferences", prefs).indent(10));
	}

	/** An unfolded theme: what's done to it, its name and author if it's yours, and every one of its settings. */
	private VBox themeBody(ThemeManager themes, ThemeManager.Entry active) {
		VBox body = new VBox(4, 0);
		Toolbar actions = new Toolbar()
			.add("\uf0c5", "Duplicate", "A copy of " + active.name() + " you can rename and share.",
				() -> themes.create(themes.uniqueName(active.name() + " Copy")));
		if (active.isUser()) actions.add("\uf1f8", "Delete", "Delete " + active.name() + ".", () -> Myriad.ui().confirm("Delete " + active.name(),
			"Delete this theme for good?", "Delete", () -> themes.delete(active), null), true, false, () -> true);
		else actions.add("\uf0e2", "Reset", "Undo your edits to this preset.", () -> themes.reset(active), false, false, active::isModified);
		body.add(actions);
		if (active.isUser()) {
			HBox meta = new HBox(4);
			meta.addFixed(new Label("Name").dim(), 26);
			meta.addWeighted(new TextField(active::name).onSubmit(n -> themes.rename(active, n)), 1);
			meta.addFixed(new Label("Author").dim(), 30);
			meta.addWeighted(new TextField(active::author).onSubmit(a -> themes.setAuthor(active, a)), 1);
			body.add(meta);
		}
		body.add(SettingsView.build(wm.theme().settings));
		return body;
	}

	/** {@code body} set in under the row above it, inside its card, as a module's settings sit under its row. */
	private static Widget indented(Widget body) {
		VBox padded = new VBox(0, 0);
		HBox row = new HBox(0);
		row.addFixed(Spacer.gap(0), 8);
		row.addWeighted(body, 1);
		row.addFixed(Spacer.gap(0), 6);
		padded.add(row);
		padded.add(Spacer.gap(4));
		return padded;
	}

	/**
	 * An unfolded theme: its row and settings on one card behind them, outlined in the theme's accent, as a module's row
	 * and settings are (CardEntry).
	 */
	private static final class ThemeCard extends VBox {
		private final ThemeRow row;

		ThemeCard(ThemeRow row) {
			super(4, 0);
			this.row = row;
			row.inCard = true;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			int accent = theme().accent.argb();
			c.roundRect(x, y, width, height, 4, ColorUtil.withAlpha(theme().surface.argb(), (int) (ColorUtil.alpha(theme().surface.argb()) * 0.55f)));
			c.outline(x, y, width, height, 4, 1, ColorUtil.withAlpha(accent, 45));
			super.render(c, mx, my);
		}
	}

	/** The preferences, but for Input (the mod key, vim keys): binds, they're set in Keybinds (KeybindsPanel). */
	private VBox preferences() {
		VBox box = new VBox(4, 0);
		for (SettingGroup group : wm.theme().preferences.groups()) {
			if (group.settings().isEmpty() || group.id().equals("input")) continue;
			VBox body = new VBox(3, 0);
			for (Setting<?> s : group.settings()) {
				Widget row = Myriad.ui().settingWidgets().create(s);
				if (row != null) body.add(row);
			}
			Collapsible section = new Collapsible(group::name, body, group::isExpanded, group::setExpanded).indent(10)
				.hint(() -> group.isExpanded() ? "" : String.valueOf(group.settings().stream().filter(Setting::isVisible).count()));
			section.visible(() -> group.isVisible() && group.settings().stream().anyMatch(Setting::isVisible));
			box.add(section);
		}
		return box;
	}

	/** One theme: colour swatches, name, a badge for user/modified themes, and a check on the active one. */
	private final class ThemeRow extends Widget {
		private final ThemeManager.Entry entry;
		/** The header of an unfolded card: its bottom corners square, the card's outline round both. */
		boolean inCard;

		ThemeRow(ThemeManager.Entry entry) {
			this.entry = entry;
			this.tooltip = (entry.isUser() ? "Your theme" + (entry.author().isEmpty() ? "" : " by " + entry.author()) + " · " + entry.id().path() + ".json"
				: entry.isModified() ? "Built-in preset, with your edits" : "Built-in preset") + "\nRight-click for its settings";
		}

		@Override
		protected float measure(Canvas c, float width) {
			return ROW + 2;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			ThemeManager themes = wm.themes();
			boolean active = themes.active() == entry;
			boolean hover = mx >= x && my >= y && mx < x + width && my < y + height;
			int[] sw = themes.swatch(entry);
			float corner = inCard ? 0 : 4;
			c.roundRect(x, y, width, height, 4, 4, corner, corner, active ? ColorUtil.withAlpha(sw[1], 45) : hover ? theme().surfaceHover.argb() : theme().surface.argb());
			if (active && !inCard) c.outline(x, y, width, height, 4, 1, ColorUtil.withAlpha(sw[1], 140));
			// Background chip with the accent and palette dots on it.
			// Narrow, the chip gives way to the name, showing the accent and one colour more.
			float chipW = width < 120 ? 18 : 44, chipH = height - 6, cx = x + 3, cy = y + 3;
			c.roundRect(cx, cy, chipW, chipH, chipH / 2, sw[0]);
			c.outline(cx, cy, chipW, chipH, chipH / 2, 1, ColorUtil.withAlpha(0xFFFFFFFF, 30));
			float dot = chipH - 4;
			int dots = chipW < 44 ? 2 : 7;
			for (int i = 1; i < sw.length && i <= dots; i++) c.circle(cx + 2 + dot / 2 + (i - 1) * (chipW - 4 - dot) / (dots - 1f), cy + chipH / 2, dot / 2 - 0.5f, sw[i]);
			float tx = cx + chipW + 6;
			float right = x + width - 6;
			// Unfolds with a right-click, as a module's row does.
			boolean open = active && entry.id().equals(unfolded);
			float cs = c.defaultFontSize() * 0.8f;
			right -= c.textWidth(FontFamily.MONO, cs, "\uf054");
			c.text(FontFamily.MONO, cs, open ? "\uf078" : "\uf054", right, y + (height - c.textHeight(FontFamily.MONO, cs)) / 2,
				open ? sw[1] : ColorUtil.withAlpha(theme().textDim.argb(), hover ? 220 : 130));
			right -= 6;
			if (active) {
				float s = c.defaultFontSize() * 0.85f;
				right -= c.textWidth(FontFamily.MONO, s, "");
				c.text(FontFamily.MONO, s, "", right, y + (height - c.textHeight(FontFamily.MONO, s)) / 2, sw[1]);
				right -= 5;
			}
			String badge = entry.isUser() ? "Custom" : entry.isModified() ? "Edited" : null;
			// The name comes first.
			if (badge != null && right - tx > 80) {
				float s = c.defaultFontSize() * 0.7f;
				float bw = c.textWidth(FontFamily.SANS_BOLD, s, badge) + 8, bh = c.textHeight(FontFamily.SANS, s) + 3;
				right -= bw;
				float by = y + (height - bh) / 2;
				c.roundRect(right, by, bw, bh, bh / 2, ColorUtil.withAlpha(sw[1], 50));
				c.outline(right, by, bw, bh, bh / 2, 1, ColorUtil.withAlpha(sw[1], 110));
				c.text(FontFamily.SANS_BOLD, s, badge, right + 4, by + 1.5f, ColorUtil.lerp(sw[1], theme().text.argb(), 0.35f));
				right -= 5;
			}
			c.text(c.ellipsize(c.defaultFont(), c.defaultFontSize(), entry.name(), right - tx), tx, y + (height - c.textHeight()) / 2,
				active || hover ? theme().text.argb() : theme().textDim.argb());
			if (hover) offerTooltip(mx, my);
		}

		@Override
		public boolean mouseClicked(float mx, float my, int button) {
			if (!isHovered(mx, my)) return false;
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				// Unfold (switching to it first: the settings shown are the theme you're on), or fold it again.
				boolean open = wm.themes().active() == entry && entry.id().equals(unfolded);
				unfolded = open ? null : entry.id();
				if (wm.themes().active() != entry) wm.applyTheme(entry.id());
				else rebuild();
				return true;
			}
			if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
			wm.applyTheme(entry.id());
			return true;
		}

		@Override
		public boolean isNavigable() {
			return true;
		}

		@Override
		public void activate() {
			wm.applyTheme(entry.id());
		}
	}
}
