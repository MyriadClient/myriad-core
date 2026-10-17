package dev.myriadclient.api.control;

import com.google.gson.JsonElement;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.util.Keybind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A control panel an addon ships ({@link AddonContext#registerControlPanel}): the modules
 * it switches and, for each, settings they take while it's on. Players can edit it like their own; their edits are
 * kept on top of it.
 * <p>
 * Your own modules go in by instance, with their settings typed (checked by the compiler, completed by your IDE);
 * other addons' modules, which you may not compile against, by id and with settings written the way {@code .set}
 * takes them:
 * <pre>{@code
 * ctx.registerControlPanel(ControlPreset.builder("Digging")
 *     .module(tunnel, m -> m.set(tunnel.width, 3).set(tunnel.height, 3))
 *     .module(autoTool)
 *     .module("myriad-essentials:nuker", m -> m.set("range", "4"))
 *     .keybind(Keybind.key(GLFW.GLFW_KEY_G))
 *     .build());
 * }</pre>
 * Register it after the modules it names (their ids come from registering them).
 */
public final class ControlPreset {
	private final String name;
	private final List<String> modules;
	private final Map<String, Map<String, String>> settings;
	private final Map<String, Map<String, JsonElement>> values;
	private final Keybind keybind;

	private ControlPreset(Builder b) {
		this.name = b.name;
		this.modules = List.copyOf(b.modules);
		this.settings = frozen(b.settings);
		this.values = frozen(b.values);
		this.keybind = b.keybind;
	}

	private static <V> Map<String, Map<String, V>> frozen(Map<String, Map<String, V>> map) {
		Map<String, Map<String, V>> copy = new LinkedHashMap<>();
		map.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
		return Collections.unmodifiableMap(copy);
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public String name() {
		return name;
	}

	/** Module ids ({@code namespace:path}), in order. */
	public List<String> modules() {
		return modules;
	}

	/** Module id → setting key → value, as {@code .set} takes them (the settings given by id). */
	public Map<String, Map<String, String>> settings() {
		return settings;
	}

	/** Module id → setting key → value as saved (the settings given typed, through {@link ModuleSettings#set(Setting, Object)}). */
	public Map<String, Map<String, JsonElement>> values() {
		return values;
	}

	public Keybind keybind() {
		return keybind;
	}

	public static final class Builder {
		private final String name;
		private final List<String> modules = new ArrayList<>();
		private final Map<String, Map<String, String>> settings = new LinkedHashMap<>();
		private final Map<String, Map<String, JsonElement>> values = new LinkedHashMap<>();
		private Keybind keybind = Keybind.NONE;

		private Builder(String name) {
			this.name = Objects.requireNonNull(name);
		}

		/** One of your modules it switches (registered already). */
		public Builder module(Module module) {
			return module(module.id().toString());
		}

		/** One of your modules it switches, with the settings it takes while the panel is on. */
		public Builder module(Module module, Consumer<ModuleSettings> settings) {
			module(module);
			settings.accept(new ModuleSettings(this, module, module.id().toString()));
			return this;
		}

		/** A module it switches, by id ({@code namespace:path}): it needn't be installed. */
		public Builder module(String id) {
			if (!modules.contains(id)) modules.add(id);
			return this;
		}

		/** A module it switches, by id, with the settings it takes while the panel is on (as {@code .set} takes them). */
		public Builder module(String id, Consumer<ModuleSettings> settings) {
			module(id);
			settings.accept(new ModuleSettings(this, null, id));
			return this;
		}

		/** A setting a module takes while the panel is on, as {@code .set <module> <key> <value>} would set it. */
		public Builder setting(String module, String key, String value) {
			module(module);
			settings.computeIfAbsent(module, k -> new LinkedHashMap<>()).put(key, value);
			return this;
		}

		/** Its default bind (none unless given). */
		public Builder keybind(Keybind keybind) {
			this.keybind = Objects.requireNonNull(keybind);
			return this;
		}

		public ControlPreset build() {
			return new ControlPreset(this);
		}
	}

	/** The settings one module takes while the panel is on. */
	public static final class ModuleSettings {
		private final Builder builder;
		private final Module module;
		private final String id;

		private ModuleSettings(Builder builder, Module module, String id) {
			this.builder = builder;
			this.module = module;
			this.id = id;
		}

		/** {@code setting} (one of this module's) at {@code value} while the panel is on. */
		public <T> ModuleSettings set(Setting<T> setting, T value) {
			if (module == null || !module.settings.all().contains(setting)) {
				throw new IllegalArgumentException(setting.name() + " isn't a setting of " + (module != null ? module.name() : id));
			}
			builder.values.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(module.settings.keyOf(setting), setting.encode(value));
			return this;
		}

		/** A setting by key, with its value as {@code .set} takes it: for modules you don't compile against. */
		public ModuleSettings set(String key, String value) {
			builder.setting(id, key, value);
			return this;
		}
	}
}
