package dev.myriadclient.impl.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.setting.BoolSetting;
import dev.myriadclient.api.setting.KeybindSetting;
import dev.myriadclient.api.setting.ModuleListSetting;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.setting.StringSetting;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A control panel: its own settings (name, modules, bind, hold) and the settings it saves for each module. While it's
 * on, those lie over the modules' own as {@link Setting.Layer#CONTROL_PANEL} overrides (tuning one in the UI then
 * tunes the panel's), and turning it off just takes them off. Saved values are kept as the setting's own JSON (saved
 * from the module) or as text the way {@code .set} takes it (an addon's preset), whichever came last.
 */
public final class ControlPanelImpl implements ControlPanel {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Controls");

	private final MyriadId id;
	private final @Nullable ControlPreset preset;
	public final Settings settings = new Settings();
	private final SettingGroup sg = settings.group("Control Panel");
	public final StringSetting name = sg.string("Name").build();
	public final StringSetting description = sg.string("Description").description("Shown beside its name in its window's title bar.").build();
	public final ModuleListSetting members = sg.modules("Modules").description("The modules it switches on and off together.").build();
	public final KeybindSetting keybind = sg.keybind("Keybind").description("Turns it on and off, in game.").build();
	public final BoolSetting hold = sg.bool("Hold").description("Only on while the keybind is held.").build();

	/** Module id → setting key → {"json": value} or {"text": value}. */
	private final Map<String, Map<String, JsonObject>> remembered = new LinkedHashMap<>();
	private boolean active;
	/** While on: the modules it's applied to, and whether it turned each on (so off turns only those off). */
	private final Map<String, Boolean> applied = new LinkedHashMap<>();
	private Set<MyriadId> lastMembers = new LinkedHashSet<>();

	ControlPanelImpl(MyriadId id, String name, @Nullable ControlPreset preset) {
		this.id = id;
		this.preset = preset;
		this.name.set(name);
		if (preset != null) seed(preset);
		lastMembers = new LinkedHashSet<>(members.get());
		// Modules added or removed while it's on are switched at once.
		members.onChanged(now -> {
			if (active) {
				for (MyriadId m : now) if (!lastMembers.contains(m)) apply(m);
				for (MyriadId m : lastMembers) if (!now.contains(m)) revert(m.toString());
			}
			lastMembers = new LinkedHashSet<>(now);
		});
	}

	/** Back to the addon's version (its modules, settings and bind). */
	void seed(ControlPreset p) {
		Set<MyriadId> ids = new LinkedHashSet<>();
		for (String m : p.modules()) ids.add(MyriadId.parse(m));
		members.set(ids);
		keybind.set(p.keybind());
		hold.set(false);
		name.set(p.name());
		remembered.clear();
		p.settings().forEach((module, values) -> {
			Map<String, JsonObject> m = remembered.computeIfAbsent(module, k -> new LinkedHashMap<>());
			values.forEach((key, value) -> {
				JsonObject o = new JsonObject();
				o.addProperty("text", value);
				m.put(key, o);
			});
		});
	}

	@Override
	public MyriadId id() {
		return id;
	}

	@Override
	public String name() {
		return name.get().isBlank() ? "Control panel" : name.get();
	}

	@Override
	public List<Module> modules() {
		return members.modules();
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public Keybind keybind() {
		return keybind.get();
	}

	@Override
	public boolean isPreset() {
		return preset != null;
	}

	@Nullable ControlPreset preset() {
		return preset;
	}

	@Override
	public void setActive(boolean on) {
		if (on == active) return;
		active = on;
		if (on) {
			for (MyriadId m : members.get()) apply(m);
		} else {
			for (String m : List.copyOf(applied.keySet())) revert(m);
			applied.clear();
		}
		Myriad.notifications().send("Control panel", name() + (on ? " on" : " off"), on ? Notifications.Level.SUCCESS : Notifications.Level.INFO, 1500, "control:" + id);
		Myriad.config().markDirty();
	}

	/** Lays the settings saved for the module over its own and turns it on, noting whether this turned it on. */
	private void apply(MyriadId moduleId) {
		Module m = Myriad.modules().get(moduleId).orElse(null);
		if (m == null || applied.containsKey(moduleId.toString())) return;
		lay(m);
		boolean turnedOn = false;
		if (!m.isEnabled()) {
			m.setEnabled(true);
			turnedOn = m.isEnabled();
		}
		applied.put(moduleId.toString(), turnedOn);
	}

	/** Takes its settings off the module, and turns the module off if this turned it on. */
	private void revert(String moduleId) {
		Boolean turnedOn = applied.remove(moduleId);
		Module m = Myriad.modules().get(MyriadId.parse(moduleId)).orElse(null);
		if (turnedOn == null || m == null) return;
		m.settings.releaseAll(this);
		if (turnedOn && m.isEnabled()) m.setEnabled(false);
	}

	/** Lays every setting saved for the module over its own (again, after they changed). */
	private void lay(Module m) {
		m.settings.releaseAll(this);
		for (var e : remembered.getOrDefault(m.id().toString(), Map.of()).entrySet()) {
			m.settings.get(e.getKey()).ifPresent(s -> lay(m, e.getKey(), s, e.getValue()));
		}
	}

	private <T> void lay(Module m, String key, Setting<T> s, JsonObject saved) {
		T value;
		try {
			value = saved.has("json") ? s.decode(saved.get("json")) : s.decode(saved.get("text").getAsString());
		} catch (RuntimeException ex) {
			LOG.warn("{}: couldn't read its {} for {}", name(), key, m.id(), ex);
			return;
		}
		if (value == null) return;
		// Tuning it in the UI while this is on tunes this panel's value.
		s.override(this, Setting.Layer.CONTROL_PANEL, name(), value, edited -> {
			JsonObject o = new JsonObject();
			o.add("json", s.encode(edited));
			remembered.computeIfAbsent(m.id().toString(), k -> new LinkedHashMap<>()).put(key, o);
			Myriad.config().markDirty();
		});
	}

	// ---- remembering settings -----------------------------------------------------------------------------------------

	/** Saves every setting the module has in effect now (except its keybind and other module options) for when this is on. */
	public void remember(Module m) {
		Map<String, JsonObject> values = new LinkedHashMap<>();
		SettingGroup own = m.keybind.group();
		for (SettingGroup g : m.settings.groups()) {
			if (g == own) continue;
			for (Setting<?> s : g.settings()) {
				if (!s.isSerializable()) continue;
				values.put(m.settings.keyOf(s), saved(s));
			}
		}
		remembered.put(m.id().toString(), values);
		if (applied.containsKey(m.id().toString())) lay(m);
		Myriad.config().markDirty();
	}

	private static <T> JsonObject saved(Setting<T> s) {
		JsonObject o = new JsonObject();
		o.add("json", s.encode(s.get()));
		return o;
	}

	public void forget(Module m) {
		remembered.remove(m.id().toString());
		if (applied.containsKey(m.id().toString())) m.settings.releaseAll(this);
		Myriad.config().markDirty();
	}

	/** How many settings it remembers for the module. */
	public int rememberedCount(Module m) {
		return remembered.getOrDefault(m.id().toString(), Map.of()).size();
	}

	/** The remembered settings' keys, for a summary. */
	public List<String> rememberedKeys(Module m) {
		return List.copyOf(remembered.getOrDefault(m.id().toString(), Map.of()).keySet());
	}

	// ---- saving ---------------------------------------------------------------------------------------------------------

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id.toString());
		if (preset != null) o.addProperty("preset", true);
		o.add("settings", settings.toJson());
		JsonObject rem = new JsonObject();
		remembered.forEach((module, values) -> {
			JsonObject v = new JsonObject();
			values.forEach(v::add);
			rem.add(module, v);
		});
		o.add("remembered", rem);
		o.addProperty("active", active);
		JsonObject on = new JsonObject();
		applied.forEach(on::addProperty);
		o.add("applied", on);
		return o;
	}

	/** Loads what {@link #toJson} wrote: being on comes back as it was, without switching anything ({@link #restore} lays its settings again). */
	void fromJson(JsonObject o) {
		if (o.has("settings") && o.get("settings").isJsonObject()) settings.fromJson(o.getAsJsonObject("settings"));
		lastMembers = new LinkedHashSet<>(members.get());
		if (o.has("remembered") && o.get("remembered").isJsonObject()) {
			remembered.clear();
			for (var e : o.getAsJsonObject("remembered").entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				Map<String, JsonObject> values = new LinkedHashMap<>();
				for (var v : e.getValue().getAsJsonObject().entrySet()) if (v.getValue().isJsonObject()) values.put(v.getKey(), v.getValue().getAsJsonObject());
				remembered.put(e.getKey(), values);
			}
		}
		active = o.has("active") && o.get("active").getAsBoolean();
		applied.clear();
		if (active && o.has("applied") && o.get("applied").isJsonObject()) {
			for (var e : o.getAsJsonObject("applied").entrySet()) applied.put(e.getKey(), e.getValue().getAsBoolean());
		}
	}

	/** After a profile loads (its modules' own settings already in): lays this panel's settings again if it's on. */
	void restore() {
		if (!active) return;
		for (String id : applied.keySet()) Myriad.modules().get(MyriadId.parse(id)).ifPresent(this::lay);
	}

	/** Forgets being on without switching anything back (the profile it belonged to is being unloaded). */
	void drop() {
		for (String id : applied.keySet()) Myriad.modules().get(MyriadId.parse(id)).ifPresent(m -> m.settings.releaseAll(this));
		active = false;
		applied.clear();
	}
}
