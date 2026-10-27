package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.widget.Collapsible;
import dev.myriadclient.api.ui.widget.Label;
import dev.myriadclient.api.ui.widget.TextField;
import dev.myriadclient.api.ui.widget.VBox;
import dev.myriadclient.api.util.FuzzyMatch;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.ui.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A group window: one addon's group of modules ("Travel", from Essentials), like a classic click-GUI frame, or several
 * groups merged into one window, each in its own section. Without groups it's the Modules panel: every module by
 * group, with a filter box.
 */
public final class ModulesPanel extends WidgetPanel {
	/** Null for the Modules panel (everything). */
	private final List<ModuleGroup> groups;
	private String filter = "";
	private final Map<String, Boolean> expanded = new HashMap<>();
	/** Modules whose settings are unfolded. */
	private final Set<Module> unfolded = new HashSet<>();
	/** Which settings groups are open, for the modules shown here (each window folds them on its own). */
	private final Map<SettingGroup, Boolean> groupsOpen = new HashMap<>();

	public ModulesPanel(List<ModuleGroup> groups) {
		this.groups = groups == null ? null : List.copyOf(groups);
	}

	public List<ModuleGroup> groups() {
		return groups == null ? List.of() : groups;
	}

	@Override
	public String title() {
		if (groups == null) return "Modules";
		Set<String> names = new LinkedHashSet<>();
		for (ModuleGroup g : groups) names.add(g.name());
		return String.join(" + ", names);
	}

	@Override
	public String subtitle() {
		if (groups == null) return null;
		// An addon's own window is already named after it.
		Set<String> names = new LinkedHashSet<>();
		for (ModuleGroup g : groups) if (!g.isAddonWindow()) names.add(AddonNames.of(g.addon()));
		return names.isEmpty() ? null : String.join(", ", names);
	}

	@Override
	public String icon() {
		return groups == null || groups.isEmpty() ? "" : groups.getFirst().icon();
	}

	/** Name matches fuzzily; descriptions only on whole-word prefixes of 3+ letters, so short queries stay precise. */
	private boolean matches(Module m) {
		if (filter.isBlank()) return true;
		if (FuzzyMatch.matches(m.name(), filter)) return true;
		String f = filter.trim().toLowerCase(Locale.ROOT);
		if (f.length() < 3) return false;
		for (String word : m.description().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (word.startsWith(f)) return true;
		return false;
	}

	@Override
	public void save(JsonObject out) {
		JsonArray a = new JsonArray();
		for (Module m : unfolded) a.add(m.id().toString());
		out.add("unfolded", a);
	}

	@Override
	public void load(JsonObject in) {
		if (!in.has("unfolded")) return;
		for (var e : in.getAsJsonArray("unfolded")) Myriad.modules().get(MyriadId.parse(e.getAsString())).ifPresent(unfolded::add);
		rebuild();
	}

	@Override
	protected void build(VBox content) {
		if (groups == null) {
			buildAll(content);
			return;
		}
		List<ModuleGroup> shown = new ArrayList<>();
		for (ModuleGroup g : groups) if (!MenuGroups.modules(g).isEmpty()) shown.add(g);
		if (shown.size() == 1) {
			for (Module m : MenuGroups.modules(shown.getFirst())) content.add(new ModuleEntry(m, unfolded, groupsOpen));
			return;
		}
		// Merged: a section per group.
		for (ModuleGroup g : shown) {
			List<Module> modules = MenuGroups.modules(g);
			VBox body = new VBox(2, 0);
			for (Module m : modules) body.add(new ModuleEntry(m, unfolded, groupsOpen));
			String name = sectionName(g);
			content.add(new GroupSection(g, () -> name + "  " + modules.stream().filter(Module::isEnabled).count() + "/" + modules.size(), body));
		}
	}

	/** A group's section title: its icon and name, and its addon's unless it's the addon's own window. */
	private static String sectionName(ModuleGroup g) {
		return g.icon() + "  " + g.name() + (g.isAddonWindow() ? "" : " · " + AddonNames.of(g.addon()));
	}

	/** The Modules panel: a filter box, then every module by group, as in the menu. */
	private void buildAll(VBox content) {
		content.add(new TextField(() -> filter).placeholder("  Filter modules…").onChange(s -> filter = s));
		if (Myriad.modules().size() == 0) {
			content.add(new Label("No modules installed. Drop Myriad addons (like myriad-essentials) into your mods folder.").dim());
			return;
		}
		for (ModuleGroup group : MenuGroups.all()) {
			List<Module> modules = MenuGroups.modules(group);
			VBox body = new VBox(2, 0);
			for (Module m : modules) body.add(new ModuleEntry(m, unfolded, groupsOpen)).visible(() -> matches(m));
			String key = group.id().toString();
			String name = sectionName(group);
			Collapsible section = new Collapsible(
				() -> name + "  " + modules.stream().filter(Module::isEnabled).count() + "/" + modules.size(),
				body, () -> !filter.isBlank() || expanded.getOrDefault(key, true), v -> expanded.put(key, v));
			section.visible(() -> modules.stream().anyMatch(this::matches));
			content.add(section);
		}
	}

	/** Whether this is a group window (rather than the Modules panel), so it can be merged with another. */
	public boolean isGroupWindow() {
		return groups != null;
	}

	/** A window showing this one's groups followed by {@code other}'s, keeping which modules are unfolded. */
	public ModulesPanel mergedWith(ModulesPanel other) {
		List<ModuleGroup> merged = new ArrayList<>(groups());
		for (ModuleGroup g : other.groups()) if (!merged.contains(g)) merged.add(g);
		ModulesPanel p = new ModulesPanel(merged);
		p.unfolded.addAll(unfolded);
		p.unfolded.addAll(other.unfolded);
		p.groupsOpen.putAll(groupsOpen);
		p.groupsOpen.putAll(other.groupsOpen);
		return p;
	}

	/** Moves {@code group} out of this merged window into a window of its own, beside this one; that window, if it opened. */
	private Optional<Window> popOut(ModuleGroup group) {
		Window w = window();
		if (w == null || groups == null || groups.size() < 2) return Optional.empty();
		WindowManager wm = (WindowManager) Myriad.ui();
		List<ModuleGroup> rest = new ArrayList<>(groups);
		rest.remove(group);
		ModulesPanel kept = new ModulesPanel(rest);
		kept.unfolded.addAll(unfolded);
		wm.replacePanel(w, kept, MenuGroups.args(rest));
		return wm.openPanel(CorePanels.GROUP, MenuGroups.args(List.of(group)), w.workspace(), false);
	}

	/**
	 * A merged window's section, with a pop-out button at the right of its header. The header dragged pulls the group
	 * out the same way, the drag going on as one of its new window (to drop where it goes: on another window to swap or
	 * split, on a group window's title to merge); let go without moving, it folds or unfolds as a click would.
	 */
	private final class GroupSection extends Collapsible {
		/** How far the pointer moves with the header held before it's a drag, not a click. */
		private static final float DRAG_START = 5;
		private final ModuleGroup group;
		/** Where the header was pressed (NaN: it isn't held). */
		private float pressX = Float.NaN, pressY;

		GroupSection(ModuleGroup group, Supplier<String> title, VBox body) {
			super(title, body, () -> expanded.getOrDefault(group.id().toString(), true), v -> expanded.put(group.id().toString(), v));
			this.group = group;
		}

		private boolean overPopOut(float mx, float my) {
			return mx >= x + width - 12 && mx < x + width && my >= y && my < y + ROW;
		}

		@Override
		public void render(Canvas c, float mx, float my) {
			super.render(c, mx, my);
			if (mx < x || my < y || mx >= x + width || my >= y + ROW) return;
			float s = c.defaultFontSize() * 0.8f;
			boolean over = overPopOut(mx, my);
			c.text(FontFamily.MONO, s, "\uf08e", x + width - 10, y + (ROW - c.textHeight(FontFamily.MONO, s)) / 2,
				over ? theme().accent.argb() : theme().textDim.argb());
			tooltip = over ? "Move " + group.name() + " to its own window" : null;
			offerTooltip(mx, my);
		}

		@Override
		public boolean mouseClicked(float mx, float my, int button) {
			if (button == 0 && overPopOut(mx, my)) {
				popOut(group);
				return true;
			}
			if (button == 0 && mx >= x && my >= y && mx < x + width && my < y + ROW) {
				// Held (taking the click makes it the pressed widget, for the drag and release): a click or a drag, told
				// apart as it moves or is let go.
				pressX = mx;
				pressY = my;
				return true;
			}
			return super.mouseClicked(mx, my, button);
		}

		@Override
		public boolean mouseDragged(float mx, float my, int button, float dx, float dy) {
			if (Float.isNaN(pressX) || Math.hypot(mx - pressX, my - pressY) < DRAG_START) return Float.isNaN(pressX) && super.mouseDragged(mx, my, button, dx, dy);
			pressX = Float.NaN;
			popOut(group).ifPresent(((WindowManager) Myriad.ui())::continueDragAs);
			return true;
		}

		@Override
		public boolean mouseReleased(float mx, float my, int button) {
			if (Float.isNaN(pressX)) return super.mouseReleased(mx, my, button);
			pressX = Float.NaN;
			activate();
			return true;
		}
	}
}
