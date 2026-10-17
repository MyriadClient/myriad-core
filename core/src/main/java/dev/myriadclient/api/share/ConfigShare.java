package dev.myriadclient.api.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A shared config: the modules, control panels and theme someone shared, with their settings (never protected ones),
 * plus how each looked in their menu, so the website can show it. Immutable; made by {@link ConfigShares#builder()},
 * fetched by {@link ConfigShares#fetch}, taken in by {@link ConfigShares#apply}.
 * <p>
 * The document is JSON, format {@value #FORMAT}:
 * <pre>{@code
 * { "format": 1, "title": "...", "made": {"core": "0.3.4"}, "addons": [{"id", "name", "version"}],
 *   "modules": [{"id", "enabled", "settings": {group: {setting: value}}, "view": {...}}],
 *   "controlPanels": [{"name", "description", "members": [ids], "settings": {module: {setting: value}}}],
 *   "theme": {"name", "settings": {...}} }
 * }</pre>
 */
public final class ConfigShare {
	/** The document format this core writes and reads. */
	public static final int FORMAT = 1;

	private final JsonObject json;

	private ConfigShare(JsonObject json) {
		this.json = json;
	}

	/** Wraps a document (a copy of it). */
	public static ConfigShare of(JsonObject json) {
		Objects.requireNonNull(json);
		int format = json.has("format") ? json.get("format").getAsInt() : -1;
		if (format < 1 || format > FORMAT) throw new IllegalArgumentException("Not a config share this version reads (format " + format + ")");
		return new ConfigShare(json.deepCopy());
	}

	public static ConfigShare parse(String json) {
		JsonElement e = JsonParser.parseString(json);
		if (!e.isJsonObject()) throw new IllegalArgumentException("Not a config share");
		return of(e.getAsJsonObject());
	}

	/** The document, as JSON (a copy). */
	public JsonObject json() {
		return json.deepCopy();
	}

	public String title() {
		return string("title", "Shared config");
	}

	public String description() {
		return string("description", "");
	}

	/** The ids of the modules in it. */
	public List<String> modules() {
		return ids("modules", "id");
	}

	/** The names of the control panels in it. */
	public List<String> controlPanels() {
		return ids("controlPanels", "name");
	}

	/** Whether it carries a theme. */
	public boolean hasTheme() {
		return json.has("theme") && json.get("theme").isJsonObject();
	}

	/** The mod ids of the addons its modules come from. */
	public List<String> addons() {
		return ids("addons", "id");
	}

	/** Whether it has nothing in it. */
	public boolean isEmpty() {
		return modules().isEmpty() && controlPanels().isEmpty() && !hasTheme();
	}

	@Override
	public String toString() {
		return json.toString();
	}

	private String string(String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}

	private List<String> ids(String array, String key) {
		List<String> out = new ArrayList<>();
		if (!json.has(array) || !json.get(array).isJsonArray()) return out;
		for (JsonElement e : json.getAsJsonArray(array)) {
			if (e.isJsonObject() && e.getAsJsonObject().has(key)) out.add(e.getAsJsonObject().get(key).getAsString());
		}
		return out;
	}

	/** The entry for module {@code id}, or null. */
	@Nullable JsonObject module(String id) {
		JsonArray a = json.has("modules") ? json.getAsJsonArray("modules") : new JsonArray();
		for (JsonElement e : a) if (e.isJsonObject() && id.equals(e.getAsJsonObject().get("id").getAsString())) return e.getAsJsonObject();
		return null;
	}
}
