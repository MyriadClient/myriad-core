package dev.myriadclient.impl.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The player's macros: modules made at runtime in the Macros category. Which macros exist is kept for every profile
 * ({@code macros.json} in core's storage); what each does, and its bind, is saved per profile like any module.
 */
public final class MacroManager {
	public static final Category CATEGORY = new Category(MyriadId.of("myriad", "macros"), "Macros", "", SettingColor.role(SettingColor.Mode.CYAN));
	private static final String FILE = "macros.json";
	private static final List<MacroModule> MACROS = new ArrayList<>();
	private static AddonStorage storage;

	private MacroManager() {
	}

	/** At core's start, before the profile loads: the saved macros come back as modules, so their settings load. */
	public static void load(AddonStorage storage) {
		MacroManager.storage = storage;
		storage.readJson(FILE).ifPresent(json -> {
			if (!json.isJsonArray()) return;
			for (JsonElement e : json.getAsJsonArray()) if (e.isJsonPrimitive()) add(e.getAsString());
		});
	}

	public static List<MacroModule> all() {
		return List.copyOf(MACROS);
	}

	/** A new macro, or the existing one of that name. */
	public static MacroModule create(String name) {
		String n = name.isBlank() ? freshName() : name.trim();
		Optional<MacroModule> existing = byName(n);
		if (existing.isPresent()) return existing.get();
		MacroModule m = add(n);
		if (m == null) m = add(n + " (macro)");
		save();
		Myriad.config().markDirty();
		return m;
	}

	public static Optional<MacroModule> byName(String name) {
		for (MacroModule m : MACROS) if (m.name().equalsIgnoreCase(name.trim())) return Optional.of(m);
		return Optional.empty();
	}

	public static void delete(Module macro) {
		if (!(macro instanceof MacroModule m) || !MACROS.remove(m)) return;
		m.setEnabledSilently(false);
		MyriadImpl.get().modules().unregister(m.id());
		save();
	}

	public static String freshName() {
		int n = MACROS.size() + 1;
		while (byName("Macro " + n).isPresent()) n++;
		return "Macro " + n;
	}

	/** Registers a macro; null if a core module already has that name (the ids would clash). */
	private static MacroModule add(String name) {
		if (MyriadImpl.get().modules().contains(MyriadId.of("myriad", MyriadId.toPath(name)))) return byName(name).orElse(null);
		MacroModule m = new MacroModule(name);
		MyriadImpl.get().modules().register(m, "myriad");
		MACROS.add(m);
		return m;
	}

	private static void save() {
		if (storage == null) return;
		JsonArray a = new JsonArray();
		for (MacroModule m : MACROS) a.add(m.name());
		storage.writeJson(FILE, a);
	}
}
