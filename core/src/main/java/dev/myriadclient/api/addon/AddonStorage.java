package dev.myriadclient.api.addon;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.ApiStatus;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A private folder for an addon's own data, at {@code .minecraft/myriad/addons/<mod id>/}. Writes are atomic, the first
 * write of each session keeps the file before as {@code <name>.bak}, and a file that can't be read is replaced by that
 * backup. Version your data ({@link #writeJson(String, JsonElement, int)}) so later releases can change its layout.
 * <p>
 * Reads and writes happen on the calling thread and wait for the disk. They never throw: a failed write is logged and
 * the file left as it was, and a missing or unreadable file reads as empty. A large file is best written off the
 * render thread, one writer per file at a time. Names may contain {@code /} for subfolders, which are made as needed.
 *
 * <pre>{@code
 * AddonStorage storage = ctx.storage();
 * storage.writeJson("waypoints.json", json);
 * storage.readJson("waypoints.json").ifPresent(this::load);
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface AddonStorage {
	/**
	 * The addon's folder, {@code .minecraft/myriad/addons/<mod id>/}. It's made on the first write, so it may not exist
	 * yet.
	 */
	Path directory();

	/**
	 * Reads {@code name} as JSON: empty if the file doesn't exist, or if neither it nor its backup parses (a file that
	 * doesn't parse is kept as {@code <name>.broken} and its {@code .bak} read instead).
	 */
	Optional<JsonElement> readJson(String name);

	/**
	 * Writes {@code json} to {@code name}, replacing it: through a temporary file moved into place, so a crash halfway
	 * never leaves half a file. The first write to it each session keeps the file before as {@code <name>.bak}.
	 */
	void writeJson(String name, JsonElement json);

	/**
	 * Writes {@code json} to {@code name} marked as {@code version} of your data's layout, for {@link #readJson(String,
	 * int, Upgrade)} to bring it up to date after you change the layout. The file holds
	 * {@code {"_version": version, "data": json}}, so read it back with that method rather than {@link #readJson(String)}.
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
		/**
		 * Returns {@code data}, which is laid out as {@code fromVersion} of your data, laid out as
		 * {@code fromVersion + 1}. It may change {@code data} in place and return it.
		 */
		JsonElement apply(int fromVersion, JsonElement data);
	}
}
