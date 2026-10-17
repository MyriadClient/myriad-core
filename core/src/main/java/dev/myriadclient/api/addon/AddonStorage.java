package dev.myriadclient.api.addon;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.ApiStatus;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A private folder for an addon's own data, at {@code .minecraft/myriad/addons/<mod id>/}. Writes are atomic, the first
 * write of each session keeps the file before as {@code <name>.bak}, and a file that can't be read is replaced by that
 * backup. Version your data ({@link #writeJson(String, JsonElement, int)}) so later releases can change its layout.
 */
@ApiStatus.NonExtendable
public interface AddonStorage {
	Path directory();

	/** Reads {@code name} as JSON, if it exists and parses. */
	Optional<JsonElement> readJson(String name);

	/** Atomically writes {@code json} to {@code name}. */
	void writeJson(String name, JsonElement json);

	/**
	 * Writes {@code json} to {@code name} marked as {@code version} of your data's layout, for {@link #readJson(String,
	 * int, Upgrade)} to bring it up to date after you change the layout.
	 */
	void writeJson(String name, JsonElement json, int version);

	/**
	 * Reads {@code name} and brings data written by an older version of your addon up to {@code version}, one step at a
	 * time: {@code upgrade} is called with each older version in turn (data written without a version is version 1).
	 * Data from a newer version (after a downgrade) is returned as it is. The file isn't changed until you write it.
	 *
	 * <pre>{@code
	 * storage.readJson("waypoints.json", 2, (from, data) -> {
	 *     if (from == 1) data.getAsJsonArray().forEach(w -> w.getAsJsonObject().addProperty("dimension", "minecraft:overworld"));
	 *     return data;
	 * }).ifPresent(this::load);
	 * storage.writeJson("waypoints.json", save(), 2);
	 * }</pre>
	 */
	Optional<JsonElement> readJson(String name, int version, Upgrade upgrade);

	/** One step of {@link #readJson(String, int, Upgrade)}: data at {@code fromVersion}, returned at the next version. */
	@FunctionalInterface
	interface Upgrade {
		JsonElement apply(int fromVersion, JsonElement data);
	}
}
