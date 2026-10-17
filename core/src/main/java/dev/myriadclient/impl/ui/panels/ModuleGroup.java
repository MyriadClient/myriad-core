package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One addon's modules in one category ("Essentials · Combat"): the unit a category window shows. Addons share the
 * standard categories, so Combat from Essentials and Combat from a crystal PvP addon are two groups with the same icon
 * and colour, each in its own window until you merge them.
 */
public record ModuleGroup(String addon, Category category) {
	public ModuleGroup {
		Objects.requireNonNull(addon);
		Objects.requireNonNull(category);
	}

	/** Every group that has modules: in category order, then addon load order. */
	public static List<ModuleGroup> all() {
		List<ModuleGroup> out = new ArrayList<>();
		for (Category c : Myriad.categories()) {
			for (Addon a : Myriad.addons()) {
				ModuleGroup g = new ModuleGroup(a.id(), c);
				if (!g.modules().isEmpty()) out.add(g);
			}
		}
		return out;
	}

	public List<Module> modules() {
		List<Module> out = new ArrayList<>();
		for (Module m : Myriad.modules().inCategory(category)) if (addon.equals(Myriad.modules().ownerOf(m))) out.add(m);
		return out;
	}

	public String addonName() {
		return AddonNames.of(addon);
	}

	/** Stable text form, saved in window args and the desktop's list of groups it has seen. */
	public String key() {
		return addon + "|" + category.id();
	}

	public static ModuleGroup parse(String key) {
		int bar = key.indexOf('|');
		if (bar <= 0) return null;
		return Myriad.categories().get(MyriadId.parse(key.substring(bar + 1))).map(c -> new ModuleGroup(key.substring(0, bar), c)).orElse(null);
	}

	/** The args of a category window showing {@code groups}. */
	public static JsonObject args(List<ModuleGroup> groups) {
		JsonArray a = new JsonArray();
		for (ModuleGroup g : groups) a.add(g.key());
		JsonObject o = new JsonObject();
		o.add("groups", a);
		return o;
	}

	/**
	 * The groups a category window's args name. Groups of addons that aren't installed are skipped. Layouts saved
	 * before groups had only a category: that means every addon's modules in it.
	 */
	public static List<ModuleGroup> fromArgs(JsonObject args) {
		List<ModuleGroup> out = new ArrayList<>();
		if (args.has("groups")) {
			for (JsonElement e : args.getAsJsonArray("groups")) {
				ModuleGroup g = parse(e.getAsString());
				if (g != null) out.add(g);
			}
		} else if (args.has("category")) {
			Myriad.categories().get(MyriadId.parse(args.get("category").getAsString())).ifPresent(c -> {
				for (ModuleGroup g : all()) if (g.category.equals(c)) out.add(g);
			});
		}
		return out;
	}
}
