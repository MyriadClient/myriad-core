package dev.myriadclient.impl.ui.panels;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.widget.Button;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.SettingsView;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.ui.widget.Widget;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.impl.ui.ThemeManager;
import dev.myriadclient.impl.ui.WindowManager;
import net.minecraft.util.Util;

/**
 * The theme list (pick, create, duplicate, rename, delete or reset themes) and the active theme's values. Edits save
 * into the active theme straight away; personal options live under Preferences and don't change with the theme.
 */
public final class ThemePanel extends WidgetPanel {
	private final WindowManager wm = (WindowManager) Myriad.ui();
	private int builtVersion = -1;

	@Override
	public String title() {
		return "Theme";
	}

	@Override
	public String icon() {
		return "";
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

		VBox list = new VBox(2, 0);
		for (ThemeManager.Entry e : themes.all()) list.add(new ThemeRow(e));
		content.add(new Collapsible("Themes", list).hint(() -> themes.all().size() + ""));

		// Themes can explain problems (e.g. a system theme that can't be read). The first line is the message; any
		// further lines are shown as-is in a monospace font (commands to run, paths).
		for (Theme t : Myriad.themes()) {
			String notice = t.notice();
			if (notice == null || notice.isBlank()) continue;
			String[] lines = notice.split("\n");
			content.add(new Label(t.name() + ": " + lines[0]).color(() -> wm.theme().yellow.argb()));
			for (int i = 1; i < lines.length; i++) content.add(new Label(lines[i]).font(FontFamily.MONO, 0).dim());
		}

		HBox actions = new HBox(4);
		actions.add(new Button(" New", () -> themes.create(themes.uniqueName("New Theme")))).tooltip("A new theme starting from the current one.");
		actions.add(new Button(" Duplicate", () -> themes.create(themes.uniqueName(active.name() + " Copy"))));
		if (active.isUser()) {
			actions.add(new Button(" Delete", () -> themes.delete(active)));
		} else {
			actions.add(new Button(" Reset", () -> themes.reset(active))).tooltip("Undo your edits to this preset.").visible(active::isModified);
		}
		content.add(actions);

		HBox files = new HBox(4);
		files.add(new Button(" Open Folder", () -> Util.getPlatform().openPath(themes.folder())))
			.tooltip("Theme files live here. Share one by sending its .json; drop a received one in and press Reload.");
		files.add(new Button(" Reload", () -> {
			themes.reload();
			themes.reapply();
		}));
		content.add(files);

		if (active.isUser()) {
			HBox name = new HBox(4);
			name.addFixed(new Label("Name").dim(), 40);
			name.addWeighted(new TextField(active::name).onSubmit(n -> themes.rename(active, n)), 1);
			content.add(name);
			HBox author = new HBox(4);
			author.addFixed(new Label("Author").dim(), 40);
			author.addWeighted(new TextField(active::author).onSubmit(a -> themes.setAuthor(active, a)), 1);
			content.add(author);
		} else {
			content.add(new Label(active.isModified()
				? "Editing a built-in preset. Your changes are saved on top of it; Reset brings the original back."
				: "Editing a built-in preset saves your changes on top of it. Duplicate it to make a theme you can rename and share.").dim());
		}

		content.add(new Label(() -> "Editing “" + themes.active().name() + "”  ·  right-click a setting's name to reset it").dim());
		content.add(SettingsView.build(wm.theme().settings));

		VBox prefs = new VBox(4, 0);
		prefs.add(new Label("These stay the same whichever theme is active.").dim());
		prefs.add(SettingsView.build(wm.theme().preferences));
		content.add(new Collapsible("Preferences", prefs).indent(10));
	}

	/** One theme: colour swatches, name, a badge for user/modified themes, and a check on the active one. */
	private final class ThemeRow extends Widget {
		private final ThemeManager.Entry entry;

		ThemeRow(ThemeManager.Entry entry) {
			this.entry = entry;
			this.tooltip = entry.isUser() ? "Your theme" + (entry.author().isEmpty() ? "" : " by " + entry.author()) + " · " + entry.id().path() + ".json"
				: entry.isModified() ? "Built-in preset, with your edits" : "Built-in preset";
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
			c.roundRect(x, y, width, height, 4, active ? ColorUtil.withAlpha(sw[1], 45) : hover ? theme().surfaceHover.argb() : theme().surface.argb());
			if (active) c.outline(x, y, width, height, 4, 1, ColorUtil.withAlpha(sw[1], 140));
			// Background chip with the accent and palette dots on it.
			float chipW = 44, chipH = height - 6, cx = x + 3, cy = y + 3;
			c.roundRect(cx, cy, chipW, chipH, chipH / 2, sw[0]);
			c.outline(cx, cy, chipW, chipH, chipH / 2, 1, ColorUtil.withAlpha(0xFFFFFFFF, 30));
			float dot = chipH - 4;
			for (int i = 1; i < sw.length && i <= 7; i++) c.circle(cx + 2 + dot / 2 + (i - 1) * (chipW - 4 - dot) / 6f, cy + chipH / 2, dot / 2 - 0.5f, sw[i]);
			float tx = cx + chipW + 6;
			float right = x + width - 6;
			if (active) {
				float s = c.defaultFontSize() * 0.85f;
				right -= c.textWidth(FontFamily.MONO, s, "");
				c.text(FontFamily.MONO, s, "", right, y + (height - c.textHeight(FontFamily.MONO, s)) / 2, sw[1]);
				right -= 5;
			}
			String badge = entry.isUser() ? "Custom" : entry.isModified() ? "Edited" : null;
			if (badge != null) {
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
			if (button != 0 || !isHovered(mx, my)) return false;
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
