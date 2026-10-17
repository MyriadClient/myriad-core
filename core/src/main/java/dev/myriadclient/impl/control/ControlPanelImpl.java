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
 * A control panel: its own settings (name, modules, bind, hold), the settings it remembers for each module, and, while
 * it's on, what it changed so it can put it back. Remembered values are kept as the setting's own JSON (captured from
 * the module) or as text the way {@code .set} takes it (an addon's preset), whichever came last.
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
	/** While on: what it did to each module, to undo it. */
	private final Map<String, Undo> undo = new LinkedHashMap<>();
	private Set<MyriadId> lastMembers = new LinkedHashSet<>();

	private static final class Undo {
		boolean turnedOn;
		final Map<String, JsonElement> previous = new LinkedHashMap<>();
		final Map<String, JsonElement> applied = new LinkedHashMap<>();
	}

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
			for (String m : List.copyOf(undo.keySet())) revert(m);
			undo.clear();
		}
		Myriad.notifications().send("Control panel", name() + (on ? " on" : " off"), on ? Notifications.Level.SUCCESS : Notifications.Level.INFO, 1500, "control:" + id);
		Myriad.config().markDirty();
	}

	/** Gives the module the settings remembered for it and turns it on, noting what to undo. */
	private void apply(MyriadId moduleId) {
		Module m = Myriad.modules().get(moduleId).orElse(null);
		if (m == null || undo.containsKey(moduleId.toString())) return;
		Undo u = new Undo();
		for (var e : remembered.getOrDefault(moduleId.toString(), Map.of()).entrySet()) {
			Setting<?> s = m.settings.get(e.getKey()).orElse(null);
			if (s == null) continue;
			JsonElement before = s.toJson();
			try {
				if (e.getValue().has("json")) s.fromJson(e.getValue().get("json"));
				else if (!s.parse(e.getValue().get("text").getAsString())) continue;
			} catch (RuntimeException ex) {
				LOG.warn("{}: couldn't set {} of {}", name(), e.getKey(), moduleId, ex);
				continue;
			}
			u.previous.put(e.getKey(), before);
			u.applied.put(e.getKey(), s.toJson());
		}
		if (!m.isEnabled()) {
			m.setEnabled(true);
			u.turnedOn = m.isEnabled();
		}
		undo.put(moduleId.toString(), u);
	}

	/** Turns the module off if this turned it on, and puts back each setting it changed that's still as it left it. */
	private void revert(String moduleId) {
		Undo u = undo.remove(moduleId);
		Module m = Myriad.modules().get(MyriadId.parse(moduleId)).orElse(null);
		if (u == null || m == null) return;
		if (u.turnedOn && m.isEnabled()) m.setEnabled(false);
		u.previous.forEach((key, before) -> m.settings.get(key).ifPresent(s -> {
			if (s.toJson().equals(u.applied.get(key))) s.fromJson(before);
		}));
	}

	// ---- remembering settings -----------------------------------------------------------------------------------------

	/** Remembers every setting the module has now (except its keybind and other module options) for when this is on. */
	public void remember(Module m) {
		Map<String, JsonObject> values = new LinkedHashMap<>();
		SettingGroup own = m.keybind.group();
		for (SettingGroup g : m.settings.groups()) {
			if (g == own) continue;
			for (Setting<?> s : g.settings()) {
				if (!s.isSerializable()) continue;
				JsonObject o = new JsonObject();
				o.add("json", s.toJson());
				values.put(m.settings.keyOf(s), o);
			}
		}
		remembered.put(m.id().toString(), values);
		// Already on: what it would have set is what the module has now.
		Undo u = undo.get(m.id().toString());
		if (u != null) for (String key : values.keySet()) m.settings.get(key).ifPresent(s -> u.applied.put(key, s.toJson()));
		Myriad.config().markDirty();
	}

	public void forget(Module m) {
		remembered.remove(m.id().toString());
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
		JsonObject un = new JsonObject();
		undo.forEach((module, u) -> {
			JsonObject j = new JsonObject();
			j.addProperty("turnedOn", u.turnedOn);
			JsonObject prev = new JsonObject(), applied = new JsonObject();
			u.previous.forEach(prev::add);
			u.applied.forEach(applied::add);
			j.add("previous", prev);
			j.add("applied", applied);
			un.add(module, j);
		});
		o.add("undo", un);
		return o;
	}

	/** Loads what {@link #toJson} wrote. Being on is restored as it was, with what it would undo, without switching anything. */
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
		undo.clear();
		if (active && o.has("undo") && o.get("undo").isJsonObject()) {
			for (var e : o.getAsJsonObject("undo").entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				JsonObject j = e.getValue().getAsJsonObject();
				Undo u = new Undo();
				u.turnedOn = j.has("turnedOn") && j.get("turnedOn").getAsBoolean();
				if (j.has("previous")) for (var p : j.getAsJsonObject("previous").entrySet()) u.previous.put(p.getKey(), p.getValue());
				if (j.has("applied")) for (var p : j.getAsJsonObject("applied").entrySet()) u.applied.put(p.getKey(), p.getValue());
				undo.put(e.getKey(), u);
			}
		}
	}

	/** Forgets being on without switching anything back (the profile it belonged to is being unloaded). */
	void drop() {
		active = false;
		undo.clear();
	}
}
