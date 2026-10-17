package dev.myriadclient.impl.addon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.impl.config.JsonFiles;

import java.nio.file.Path;
import java.util.Optional;

/** Versioned data is stored as {@code {"_version": n, "data": ...}}; anything else counts as version 1. */
record AddonStorageImpl(Path directory) implements AddonStorage {
	private static final String VERSION = "_version", DATA = "data";

	@Override
	public Optional<JsonElement> readJson(String name) {
		return JsonFiles.read(directory.resolve(name));
	}

	@Override
	public void writeJson(String name, JsonElement json) {
		JsonFiles.write(directory.resolve(name), json);
	}

	@Override
	public void writeJson(String name, JsonElement json, int version) {
		JsonObject o = new JsonObject();
		o.addProperty(VERSION, version);
		o.add(DATA, json);
		writeJson(name, o);
	}

	@Override
	public Optional<JsonElement> readJson(String name, int version, Upgrade upgrade) {
		return readJson(name).map(json -> {
			int saved = 1;
			JsonElement data = json;
			if (json.isJsonObject() && json.getAsJsonObject().has(VERSION) && json.getAsJsonObject().has(DATA)) {
				saved = json.getAsJsonObject().get(VERSION).getAsInt();
				data = json.getAsJsonObject().get(DATA);
			}
			for (int v = saved; v < version; v++) data = upgrade.apply(v, data);
			return data;
		});
	}
}
