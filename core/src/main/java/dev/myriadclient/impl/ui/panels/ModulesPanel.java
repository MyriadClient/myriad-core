package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A category window: one addon's modules in one category ("Combat · Essentials"), like a classic click-GUI frame, or
 * several such groups merged into one window, each in its own section. Without groups it's the Modules panel: every
 * module by category, with a filter box.
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
		for (ModuleGroup g : groups) names.add(g.category().name());
		return String.join(" + ", names);
	}

	@Override
	public String subtitle() {
		if (groups == null) return null;
		Set<String> names = new LinkedHashSet<>();
		for (ModuleGroup g : groups) names.add(g.addonName());
		return String.join(", ", names);
	}

	@Override
	public String icon() {
		return groups == null || groups.isEmpty() ? "" : groups.getFirst().category().icon();
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
		for (ModuleGroup g : groups) if (!g.modules().isEmpty()) shown.add(g);
		if (shown.size() == 1) {
			for (Module m : shown.getFirst().modules()) content.add(new ModuleEntry(m, unfolded, groupsOpen));
			return;
		}
		// Merged: a section per group. The category is only worth repeating when the window mixes categories.
		boolean mixed = shown.stream().map(ModuleGroup::category).distinct().count() > 1;
		for (ModuleGroup g : shown) {
			List<Module> modules = g.modules();
			VBox body = new VBox(2, 0);
			for (Module m : modules) body.add(new ModuleEntry(m, unfolded, groupsOpen));
			String name = mixed ? g.category().icon() + "  " + g.category().name() + " · " + g.addonName() : g.addonName();
			content.add(new GroupSection(g, () -> name + "  " + modules.stream().filter(Module::isEnabled).count() + "/" + modules.size(), body));
		}
	}

	/** The Modules panel: a filter box, then every module grouped by category. */
	private void buildAll(VBox content) {
		content.add(new TextField(() -> filter).placeholder("  Filter modules…").onChange(s -> filter = s));
		if (Myriad.modules().size() == 0) {
			content.add(new Label("No modules installed. Drop Myriad addons (like myriad-essentials) into your mods folder.").dim());
			return;
		}
		for (Category category : Myriad.categories()) {
			List<Module> modules = Myriad.modules().inCategory(category);
			if (modules.isEmpty()) continue;
			VBox body = new VBox(2, 0);
			for (Module m : modules) body.add(new ModuleEntry(m, unfolded, groupsOpen)).visible(() -> matches(m));
			String key = category.id().toString();
			Collapsible section = new Collapsible(
				() -> category.icon() + "  " + category.name() + "  " + modules.stream().filter(Module::isEnabled).count() + "/" + modules.size(),
				body, () -> !filter.isBlank() || expanded.getOrDefault(key, true), v -> expanded.put(key, v));
			section.visible(() -> modules.stream().anyMatch(this::matches));
			content.add(section);
		}
	}

	/** Whether this is a category window (rather than the Modules panel), so it can be merged with another. */
	public boolean isCategoryWindow() {
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

	/** Moves {@code group} out of this merged window into a window of its own, beside this one. */
	private void popOut(ModuleGroup group) {
		Window w = window();
		if (w == null || groups == null || groups.size() < 2) return;
		WindowManager wm = (WindowManager) Myriad.ui();
		List<ModuleGroup> rest = new ArrayList<>(groups);
		rest.remove(group);
		ModulesPanel kept = new ModulesPanel(rest);
		kept.unfolded.addAll(unfolded);
		wm.replacePanel(w, kept, ModuleGroup.args(rest));
		wm.openPanel(CorePanels.CATEGORY, ModuleGroup.args(List.of(group)), w.workspace(), false);
	}

	/** A merged window's section, with a pop-out button at the right of its header. */
	private final class GroupSection extends Collapsible {
		private final ModuleGroup group;

		GroupSection(ModuleGroup group, Supplier<String> title, VBox body) {
			super(title, body, () -> expanded.getOrDefault(group.key(), true), v -> expanded.put(group.key(), v));
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
			tooltip = over ? "Move " + group.addonName() + " · " + group.category().name() + " to its own window" : null;
			offerTooltip(mx, my);
		}

		@Override
		public boolean mouseClicked(float mx, float my, int button) {
			if (button == 0 && overPopOut(mx, my)) {
				popOut(group);
				return true;
			}
			return super.mouseClicked(mx, my, button);
		}
	}
}
