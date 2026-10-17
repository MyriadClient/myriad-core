package dev.myriadclient.impl.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.control.Controls;
import dev.myriadclient.api.registry.Identified;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The active profile's control panels: the player's own, and the ones addons ship (presets), which show up in every
 * profile unless deleted there. Saved per profile, in {@code controls.json}.
 */
public final class ControlManager implements Controls {
	/** An addon's control panel, as registered. */
	public record Preset(MyriadId id, ControlPreset preset) implements Identified {
	}

	private final Registry<Preset> presets = new Registry<>("control panel");
	private final List<ControlPanelImpl> panels = new CopyOnWriteArrayList<>();
	/** Presets deleted in this profile. */
	private final Set<MyriadId> deleted = new LinkedHashSet<>();

	public ControlManager() {
		// An addon that loads after the profile still gets its panels.
		presets.onAdd(p -> {
			if (find(p.id()) == null && !deleted.contains(p.id())) panels.add(new ControlPanelImpl(p.id(), p.preset().name(), p.preset()));
		});
		presets.onRemove(p -> {
			ControlPanelImpl c = find(p.id());
			if (c != null) {
				c.setActive(false);
				panels.remove(c);
			}
		});
	}

	public Registry<Preset> presets() {
		return presets;
	}

	@Override
	public List<ControlPanel> all() {
		return List.copyOf(panels);
	}

	public List<ControlPanelImpl> impls() {
		return List.copyOf(panels);
	}

	@Override
	public Optional<ControlPanel> get(MyriadId id) {
		return Optional.ofNullable(find(id));
	}

	@Override
	public Optional<ControlPanel> byName(String name) {
		for (ControlPanelImpl c : panels) if (c.name().equalsIgnoreCase(name.trim())) return Optional.of(c);
		return Optional.empty();
	}

	private @Nullable ControlPanelImpl find(MyriadId id) {
		for (ControlPanelImpl c : panels) if (c.id().equals(id)) return c;
		return null;
	}

	@Override
	public ControlPanel create(String name) {
		int n = 1;
		while (find(MyriadId.of("myriad", "control_" + n)) != null) n++;
		ControlPanelImpl c = new ControlPanelImpl(MyriadId.of("myriad", "control_" + n), name.isBlank() ? "Control panel " + n : name.trim(), null);
		panels.add(c);
		dev.myriadclient.api.Myriad.config().markDirty();
		return c;
	}

	@Override
	public void delete(ControlPanel panel) {
		if (!(panel instanceof ControlPanelImpl c) || !panels.remove(c)) return;
		c.setActive(false);
		if (c.isPreset()) deleted.add(c.id());
		dev.myriadclient.api.Myriad.config().markDirty();
	}

	/** Puts an addon's panel back as the addon made it (and brings it back if it was deleted). */
	public void reset(MyriadId id) {
		Preset p = presets.get(id).orElse(null);
		if (p == null) return;
		deleted.remove(id);
		ControlPanelImpl c = find(id);
		if (c == null) {
			panels.add(new ControlPanelImpl(id, p.preset().name(), p.preset()));
		} else {
			c.setActive(false);
			c.seed(p.preset());
		}
		dev.myriadclient.api.Myriad.config().markDirty();
	}

	/** Whether any addon panel is missing from this profile (deleted), for a "bring them back" button. */
	public boolean anyDeleted() {
		for (MyriadId id : deleted) if (presets.contains(id)) return true;
		return false;
	}

	public void restoreDeleted() {
		for (MyriadId id : List.copyOf(deleted)) reset(id);
	}

	// ---- per profile ----------------------------------------------------------------------------------------------------

	public JsonObject save() {
		JsonObject o = new JsonObject();
		JsonArray list = new JsonArray();
		for (ControlPanelImpl c : panels) list.add(c.toJson());
		o.add("panels", list);
		JsonArray gone = new JsonArray();
		for (MyriadId id : deleted) gone.add(id.toString());
		o.add("deleted", gone);
		return o;
	}

	/**
	 * Loads a profile's panels in place of the last profile's (whose modules have already been replaced by this
	 * profile's own, so nothing is switched back). A panel that was on comes back on, remembering what it would undo.
	 */
	public void load(@Nullable JsonObject o) {
		for (ControlPanelImpl c : panels) c.drop();
		panels.clear();
		deleted.clear();
		if (o != null && o.has("deleted") && o.get("deleted").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("deleted")) deleted.add(MyriadId.parse(e.getAsString()));
		}
		if (o != null && o.has("panels") && o.get("panels").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("panels")) {
				if (!e.isJsonObject()) continue;
				JsonObject j = e.getAsJsonObject();
				if (!j.has("id")) continue;
				MyriadId id = MyriadId.parse(j.get("id").getAsString());
				Preset p = presets.get(id).orElse(null);
				// An addon's panel whose addon is gone is dropped; it comes back as made if the addon does.
				if (j.has("preset") && p == null) continue;
				ControlPanelImpl c = new ControlPanelImpl(id, "", p == null ? null : p.preset());
				c.fromJson(j);
				panels.add(c);
			}
		}
		for (Preset p : presets) {
			if (find(p.id()) == null && !deleted.contains(p.id())) panels.add(new ControlPanelImpl(p.id(), p.preset().name(), p.preset()));
		}
	}

	/** A name not used yet, for a new panel: "Control panel 2" and so on. */
	public String freshName() {
		int n = panels.size() + 1;
		while (byName("Control panel " + n).isPresent()) n++;
		return "Control panel " + n;
	}
}
