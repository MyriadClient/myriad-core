package dev.myriadclient.impl.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.myriadclient.impl.config.JsonFiles;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A list of player names kept in one JSON file under Myriad's folder, global rather than per profile, compared without
 * regard to case (player names are unique ignoring it): friends and enemies.
 */
abstract class NameList {
	private final Path file;
	private final Set<String> names = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

	NameList(Path root, String fileName) {
		file = root.resolve(fileName);
		JsonFiles.read(file).filter(JsonElement::isJsonArray).ifPresent(a -> {
			for (JsonElement e : a.getAsJsonArray()) names.add(e.getAsString());
		});
	}

	final boolean contains(String name) {
		return names.contains(name);
	}

	/** Adds {@code name} (trimmed) and saves; false if it was there already. */
	public final boolean add(String name) {
		boolean added = names.add(name.trim());
		if (added) save();
		return added;
	}

	/** Removes {@code name} (trimmed, any case) and saves; false if it wasn't there. */
	public final boolean remove(String name) {
		boolean removed = names.remove(name.trim());
		if (removed) save();
		return removed;
	}

	/** Every name, sorted ignoring case; a copy. */
	public final Collection<String> all() {
		synchronized (names) {
			return List.copyOf(names);
		}
	}

	private void save() {
		JsonArray a = new JsonArray();
		all().forEach(a::add);
		JsonFiles.write(file, a);
	}
}
