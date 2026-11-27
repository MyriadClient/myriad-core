package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.impl.config.JsonFiles;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The addons the player trusts to update on their own, kept in {@code myriad/updates/auto.json}. Off for every addon
 * until the player turns it on, and only offered for addons whose updates are signature-checked (see
 * {@link UpdateManager#canAutoUpdate}). An automatic update is staged like any other: it installs when the game exits.
 */
final class AutoUpdates {
	private final Path file;
	private final Set<String> ids = new TreeSet<>();

	AutoUpdates(Path dir) {
		this.file = dir.resolve("auto.json");
		JsonFiles.read(file).filter(JsonElement::isJsonObject).map(o -> o.getAsJsonObject().get("addons")).filter(JsonElement::isJsonArray).ifPresent(a -> {
			for (JsonElement e : a.getAsJsonArray()) {
				if (e.isJsonPrimitive() && Names.isId(e.getAsString())) ids.add(e.getAsString());
			}
		});
	}

	synchronized boolean on(String id) {
		return ids.contains(id);
	}

	synchronized boolean any() {
		return !ids.isEmpty();
	}

	synchronized void set(String id, boolean on) {
		if (!Names.isId(id) || (on ? !ids.add(id) : !ids.remove(id))) return;
		JsonObject root = new JsonObject();
		JsonArray list = new JsonArray();
		ids.forEach(list::add);
		root.add("addons", list);
		JsonFiles.write(file, root);
	}
}
