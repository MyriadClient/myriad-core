package dev.myriadclient.api.control;

import dev.myriadclient.api.util.Keybind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A control panel an addon ships ({@link dev.myriadclient.api.addon.AddonContext#registerControlPanel}): the modules
 * it switches and, for each, settings they take while it's on, written the way {@code .set} takes them. Players can
 * edit it like their own; their edits are kept on top of it.
 * <pre>{@code
 * ctx.registerControlPanel(ControlPreset.builder("Hunting")
 *     .module("myriad-stash-hunter:search").setting("myriad-stash-hunter:search", "spacing", "6")
 *     .module("myriad-stash-hunter:stash_finder")
 *     .build());
 * }</pre>
 */
public final class ControlPreset {
	private final String name;
	private final List<String> modules;
	private final Map<String, Map<String, String>> settings;
	private final Keybind keybind;

	private ControlPreset(Builder b) {
		this.name = b.name;
		this.modules = List.copyOf(b.modules);
		Map<String, Map<String, String>> s = new LinkedHashMap<>();
		b.settings.forEach((k, v) -> s.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
		this.settings = Collections.unmodifiableMap(s);
		this.keybind = b.keybind;
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

	/** Module id → setting key → value, as {@code .set} takes them. */
	public Map<String, Map<String, String>> settings() {
		return settings;
	}

	public Keybind keybind() {
		return keybind;
	}

	public static final class Builder {
		private final String name;
		private final List<String> modules = new ArrayList<>();
		private final Map<String, Map<String, String>> settings = new LinkedHashMap<>();
		private Keybind keybind = Keybind.NONE;

		private Builder(String name) {
			this.name = name;
		}

		/** A module it switches, by id ({@code namespace:path}): it needn't be installed. */
		public Builder module(String id) {
			if (!modules.contains(id)) modules.add(id);
			return this;
		}

		/** A setting the module takes while the panel is on, as {@code .set <module> <key> <value>} would set it. */
		public Builder setting(String module, String key, String value) {
			module(module);
			settings.computeIfAbsent(module, k -> new LinkedHashMap<>()).put(key, value);
			return this;
		}

		/** Its default bind (none unless given). */
		public Builder keybind(Keybind keybind) {
			this.keybind = keybind;
			return this;
		}

		public ControlPreset build() {
			return new ControlPreset(this);
		}
	}
}
