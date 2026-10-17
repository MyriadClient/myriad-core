package dev.myriadclient.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.util.MyriadId;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Saved settings as they are on disk, for updating them after you rename, move or change settings (see
 * {@code Module#migrateSettings}). Values are nested by group and only stored when they differ from the default, so
 * a missing value means "default". Groups and settings can be named as in code ({@code "Place Range"}) or by id
 * ({@code "place_range"}).
 *
 * <pre>{@code
 * protected void migrateSettings(int fromVersion, SavedSettings saved) {
 *     if (fromVersion == 1) saved.rename("General", "Range", "Place Range");
 *     if (fromVersion == 2) saved.map("General", "Mode", v -> v.getAsString().equals("Fast") ? new JsonPrimitive("Quick") : v);
 * }
 * }</pre>
 */
public final class SavedSettings {
	private final JsonObject json;

	public SavedSettings(JsonObject json) {
		this.json = json;
	}

	/** The underlying JSON, for changes the helpers don't cover. */
	public JsonObject json() {
		return json;
	}

	public Optional<JsonElement> get(String group, String setting) {
		JsonObject g = groupOrNull(group);
		return g == null ? Optional.empty() : Optional.ofNullable(g.get(MyriadId.toPath(setting)));
	}

	public void set(String group, String setting, JsonElement value) {
		String g = MyriadId.toPath(group);
		if (!json.has(g) || !json.get(g).isJsonObject()) json.add(g, new JsonObject());
		json.getAsJsonObject(g).add(MyriadId.toPath(setting), value);
	}

	/** Forgets a saved value, so the setting loads as its default. */
	public void remove(String group, String setting) {
		JsonObject g = groupOrNull(group);
		if (g == null) return;
		g.remove(MyriadId.toPath(setting));
		if (g.isEmpty()) json.remove(MyriadId.toPath(group));
	}

	/** Moves a value to another setting, in the same group or another. Does nothing if it was never changed. */
	public void move(String fromGroup, String fromSetting, String toGroup, String toSetting) {
		Optional<JsonElement> value = get(fromGroup, fromSetting);
		if (value.isEmpty()) return;
		remove(fromGroup, fromSetting);
		set(toGroup, toSetting, value.get());
	}

	public void rename(String group, String oldName, String newName) {
		move(group, oldName, group, newName);
	}

	/** Renames a group, merging into the new one if both exist (values already in the new group win). */
	public void renameGroup(String oldName, String newName) {
		JsonObject old = groupOrNull(oldName);
		if (old == null) return;
		json.remove(MyriadId.toPath(oldName));
		for (var e : old.entrySet()) {
			if (get(newName, e.getKey()).isEmpty()) set(newName, e.getKey(), e.getValue());
		}
	}

	/** Rewrites a saved value, e.g. after renaming an enum constant or changing units. Returning null removes it. */
	public void map(String group, String setting, UnaryOperator<JsonElement> mapper) {
		Optional<JsonElement> value = get(group, setting);
		if (value.isEmpty()) return;
		JsonElement mapped = mapper.apply(value.get());
		if (mapped == null) remove(group, setting);
		else set(group, setting, mapped);
	}

	private JsonObject groupOrNull(String group) {
		JsonElement g = json.get(MyriadId.toPath(group));
		return g != null && g.isJsonObject() ? g.getAsJsonObject() : null;
	}
}
