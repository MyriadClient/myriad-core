package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.FuzzyMatch;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.ModuleGroup;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * A rofi/wofi-style launcher: fuzzy search over modules, panels, categories, themes, profiles and workspaces.
 * Enter runs the selection; Shift+Enter runs its alternate action (e.g. open a module's settings instead of
 * toggling it).
 */
final class Launcher {
	private static final float WIDTH = 260, ROW = 15, MAX_ROWS = 10;

	private enum Kind {
		MODULE, OTHER
	}

	private record Entry(String icon, String title, String hint, Runnable run, Runnable alt, Kind kind) {
		Entry(String icon, String title, String hint, Runnable run, Runnable alt) {
			this(icon, title, hint, run, alt, Kind.OTHER);
		}
	}

	private final WindowManager wm;
	private final Animated open = new Animated(0);
	private boolean visible;
	private String query = "";
	private int selected;
	private List<Entry> results = List.of();

	Launcher(WindowManager wm) {
		this.wm = wm;
	}

	boolean isOpen() {
		return visible;
	}

	void show() {
		show("");
	}

	/** Opens with the query already typed (the dev console uses it). */
	void show(String query) {
		visible = true;
		this.query = query;
		selected = 0;
		refresh();
	}

	void hide() {
		visible = false;
	}

	void toggle() {
		if (visible) hide();
		else show();
	}

	private List<Entry> entries() {
		List<Entry> list = new ArrayList<>();
		for (Module m : Myriad.modules()) {
			list.add(new Entry(m.isEnabled() ? "" : "", m.name(), m.category().name() + " · " + (m.isEnabled() ? "on" : "off"),
				m::toggle, () -> CorePanels.openModuleSettings(m, false), Kind.MODULE));
		}
		for (PanelType t : Myriad.panels()) {
			if (t.isHud()) {
				boolean on = wm.hudElement(t).isPresent();
				list.add(new Entry(t.icon() == null ? "\uf2d2" : t.icon(), (on ? "Remove from HUD: " : "Add to HUD: ") + t.name(), "HUD element",
					() -> {
						if (on) wm.removeHudElement(t);
						else wm.addHudElement(t);
					}, null));
			}
			if (!t.isListed()) continue;
			list.add(new Entry(t.icon() == null ? "" : t.icon(), t.name(), t.isHud() ? "HUD element" : "Panel",
				() -> wm.openPanel(t.id(), new JsonObject()),
				() -> wm.openPanel(t.id(), new JsonObject(), wm.activeWorkspace(), true)));
		}
		for (ModuleGroup g : ModuleGroup.all()) {
			list.add(new Entry(g.category().icon(), g.category().name() + " · " + g.addonName(), "Category", () -> wm.openGroup(g), null));
		}
		for (ThemeManager.Entry t : wm.themes().all()) {
			list.add(new Entry("\uf53f", "Theme: " + t.name(), t.isUser() ? "Your theme" : "Apply theme", () -> wm.applyTheme(t.id()),
				() -> wm.openPanel(CorePanels.THEME, new JsonObject())));
		}
		for (String p : Myriad.config().profiles()) {
			list.add(new Entry("", "Profile: " + p, p.equals(Myriad.config().activeProfile()) ? "active" : "Switch profile",
				() -> Myriad.config().switchProfile(p), null));
		}
		for (int i = 1; i <= 9; i++) {
			int ws = i;
			list.add(new Entry("", "Workspace " + i, "Switch", () -> wm.switchWorkspace(ws), null));
		}
		list.add(new Entry("", "HUD", "Edit HUD layout", () -> wm.switchWorkspace(0), null));
		return list;
	}

	/**
	 * Scores the full title and the part after a "Kind: " prefix ("Theme: Nord" matches "nord" as strongly as a
	 * theme called Nord), so prefixes neither help nor hurt. Modules rank slightly ahead of other kinds on ties.
	 */
	static int score(Entry e, String query) {
		int s = FuzzyMatch.score(e.title, query);
		int colon = e.title.indexOf(": ");
		if (colon > 0) s = Math.max(s, FuzzyMatch.score(e.title.substring(colon + 2), query) - 5);
		if (s == FuzzyMatch.NO_MATCH) return s;
		return s + (e.kind == Kind.MODULE ? 3 : 0);
	}

	private void refresh() {
		List<Entry> all = entries();
		if (query.isBlank()) {
			results = all.stream().filter(e -> !e.title.startsWith("Workspace") && !e.title.startsWith("Profile")).limit(40).toList();
		} else {
			results = all.stream().filter(e -> score(e, query) != FuzzyMatch.NO_MATCH)
				.sorted(Comparator.comparingInt((Entry e) -> -score(e, query))).limit(40).toList();
		}
		selected = Math.clamp(selected, 0, Math.max(0, results.size() - 1));
	}

	void render(Canvas c, float screenW, float screenH, float mx, float my) {
		open.animateTo(visible ? 1 : 0, 160, wm.theme().bezier());
		float t = open.get();
		if (t <= 0.01f) return;
		ThemeSettings theme = wm.theme();
		int rows = (int) Math.min(MAX_ROWS, results.size());
		float h = 22 + rows * ROW + 6;
		float x = (screenW - WIDTH) / 2, y = screenH * 0.22f;
		c.push();
		c.alpha(t);
		c.rect(0, 0, screenW, screenH, ColorUtil.fade(0x66000000, t));
		c.translate(0, (1 - t) * -8);
		float r = theme.rounding.get();
		if (theme.shadow.get()) c.shadow(x, y, WIDTH, h, r, theme.shadowRange.get(), theme.shadowColor.argb());
		c.backdrop(x, y, WIDTH, h, r, 1);
		c.roundRect(x, y, WIDTH, h, r, theme.windowBackground.argb());
		c.gradientOutline(x, y, WIDTH, h, r, Math.max(1, theme.borderSize.get()), theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), theme.borderAngle.get().floatValue());

		float size = c.defaultFontSize() * 1.1f;
		c.text(FontFamily.MONO, size, "", x + 8, y + 6, theme.accent.argb());
		String shown = query.isEmpty() ? "Search modules, panels, themes…" : query;
		c.text(FontFamily.SANS, size, shown, x + 22, y + 6, query.isEmpty() ? theme.textDim.argb() : theme.text.argb());
		if (!query.isEmpty() && (System.nanoTime() / 500_000_000L) % 2 == 0) {
			c.rect(x + 22 + c.textWidth(FontFamily.SANS, size, query) + 1, y + 6, 0.75f, c.textHeight(FontFamily.SANS, size), theme.text.argb());
		}
		c.rect(x + 6, y + 20, WIDTH - 12, 1, ColorUtil.withAlpha(theme.textDim.argb(), 40));

		int first = Math.max(0, selected - (int) MAX_ROWS + 1);
		for (int i = 0; i < rows; i++) {
			int idx = first + i;
			if (idx >= results.size()) break;
			Entry e = results.get(idx);
			float ry = y + 24 + i * ROW;
			boolean hover = mx >= x && mx < x + WIDTH && my >= ry && my < ry + ROW;
			if (idx == selected) c.roundRect(x + 4, ry, WIDTH - 8, ROW - 1, 4, ColorUtil.withAlpha(theme.accent.argb(), 70));
			else if (hover) c.roundRect(x + 4, ry, WIDTH - 8, ROW - 1, 4, theme.surface.argb());
			c.text(FontFamily.MONO, c.defaultFontSize(), e.icon, x + 9, ry + (ROW - c.textHeight()) / 2, theme.accent.argb());
			c.text(e.title, x + 24, ry + (ROW - c.textHeight()) / 2, theme.text.argb());
			float hw = c.textWidth(FontFamily.SANS, c.defaultFontSize() * 0.85f, e.hint);
			c.text(FontFamily.SANS, c.defaultFontSize() * 0.85f, e.hint, x + WIDTH - 10 - hw, ry + (ROW - c.textHeight() * 0.85f) / 2, theme.textDim.argb());
		}
		c.pop();
	}

	boolean mouseClicked(float mx, float my, int button, float screenW, float screenH) {
		if (!visible) return false;
		float x = (screenW - WIDTH) / 2, y = screenH * 0.22f;
		int rows = (int) Math.min(MAX_ROWS, results.size());
		if (mx < x || mx >= x + WIDTH || my < y || my >= y + 22 + rows * ROW + 6) {
			hide();
			return true;
		}
		int first = Math.max(0, selected - (int) MAX_ROWS + 1);
		int i = (int) ((my - y - 24) / ROW);
		if (my >= y + 24 && i >= 0 && i < rows && first + i < results.size()) {
			run(results.get(first + i), button == 1);
		}
		return true;
	}

	private void run(Entry e, boolean alt) {
		hide();
		Runnable r = alt && e.alt != null ? e.alt : e.run;
		r.run();
	}

	boolean keyPressed(int key, int mods) {
		if (!visible) return false;
		switch (key) {
			case GLFW.GLFW_KEY_ESCAPE -> hide();
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				if (!results.isEmpty()) run(results.get(selected), (mods & GLFW.GLFW_MOD_SHIFT) != 0);
			}
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> selected = Math.min(results.size() - 1, selected + 1);
			case GLFW.GLFW_KEY_UP -> selected = Math.max(0, selected - 1);
			// Walker/fzf style: Ctrl+j/k or Ctrl+n/p move the selection.
			case GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_N -> {
				if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) selected = Math.min(results.size() - 1, selected + 1);
			}
			case GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_P -> {
				if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) selected = Math.max(0, selected - 1);
			}
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (!query.isEmpty()) {
					query = (mods & GLFW.GLFW_MOD_CONTROL) != 0 ? "" : query.substring(0, query.length() - 1);
					selected = 0;
					refresh();
				}
			}
			case GLFW.GLFW_KEY_V -> {
				if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) {
					query += Minecraft.getInstance().keyboardHandler.getClipboard();
					refresh();
				}
			}
			default -> {
			}
		}
		return true;
	}

	boolean charTyped(char chr) {
		if (!visible) return false;
		long window = net.minecraft.client.Minecraft.getInstance().getWindow().handle();
		boolean ctrl = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
		if (chr >= 32 && chr != 127 && !ctrl) {
			query += chr;
			selected = 0;
			refresh();
		}
		return true;
	}

	boolean mouseScrolled(float amount) {
		if (!visible) return false;
		selected = Math.clamp(selected - (int) Math.signum(amount), 0, Math.max(0, results.size() - 1));
		return true;
	}
}
