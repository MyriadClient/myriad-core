package dev.myriadclient.api.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.util.MyriadId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A set of modules, e.g. "pause while any of these are on". Stored by id, so modules from addons that aren't
 * installed (or haven't registered yet) are remembered rather than dropped.
 */
public class ModuleListSetting extends Setting<Set<MyriadId>> {
	public ModuleListSetting(String name, String description, Set<MyriadId> defaultValue, Supplier<Boolean> visible) {
		super(name, description, defaultValue, visible);
	}

	@Override
	protected Set<MyriadId> copy(Set<MyriadId> v) {
		return new LinkedHashSet<>(v);
	}

	public boolean contains(Module module) {
		return get().contains(module.id());
	}

	public void toggle(Module module) {
		if (!value.remove(module.id())) value.add(module.id());
		changed();
	}

	/** The chosen modules that are installed. */
	public List<Module> modules() {
		List<Module> out = new ArrayList<>();
		for (MyriadId id : get()) Myriad.modules().get(id).ifPresent(out::add);
		return out;
	}

	/** Whether any chosen module is on. */
	public boolean anyEnabled() {
		for (Module m : modules()) if (m.isEnabled()) return true;
		return false;
	}

	@Override
	public JsonElement toJson() {
		JsonArray a = new JsonArray();
		for (MyriadId id : value) a.add(id.toString());
		return a;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json == null || !json.isJsonArray()) return;
		Set<MyriadId> ids = new LinkedHashSet<>();
		for (JsonElement e : json.getAsJsonArray()) {
			if (!e.isJsonPrimitive()) continue;
			try {
				ids.add(MyriadId.parse(e.getAsString()));
			} catch (IllegalArgumentException ignored) {
				// Not an id: left out, the rest kept.
			}
		}
		set(ids);
	}

	/** Toggles one module by name. */
	@Override
	public boolean parse(String input) {
		var module = Myriad.modules().byName(input.trim());
		if (module.isEmpty()) return false;
		toggle(module.get());
		return true;
	}

	@Override
	public List<String> suggestions() {
		return Myriad.modules().values().stream().map(m -> m.name().replace(" ", "")).toList();
	}

	@Override
	public String valueString() {
		return get().size() + " modules";
	}

	public static class Builder extends Setting.Builder<ModuleListSetting.Builder, Set<MyriadId>, ModuleListSetting> {
		public Builder(String name) {
			super(name, new LinkedHashSet<>());
		}

		/** Modules chosen by default, by id ("myriad-essentials:freecam"). */
		public ModuleListSetting.Builder defaultValue(String... ids) {
			Set<MyriadId> set = new LinkedHashSet<>();
			for (String id : ids) set.add(MyriadId.parse(id));
			return defaultValue(set);
		}

		@Override
		protected ModuleListSetting create() {
			return new ModuleListSetting(name, description, defaultValue, visible);
		}
	}
}
