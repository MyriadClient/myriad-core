package dev.myriadclient.impl.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.myriadclient.api.service.Enemies;
import dev.myriadclient.impl.config.JsonFiles;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Enemies are global (not per profile), stored in {@code myriad/enemies.json}. */
public final class EnemiesManager implements Enemies {
	private final Path file;
	private final Set<String> enemies = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

	public EnemiesManager(Path root) {
		this.file = root.resolve("enemies.json");
		JsonFiles.read(file).filter(JsonElement::isJsonArray).ifPresent(a -> {
			for (JsonElement e : a.getAsJsonArray()) enemies.add(e.getAsString());
		});
	}

	@Override
	public boolean isEnemy(String name) {
		return enemies.contains(name);
	}

	@Override
	public boolean add(String name) {
		boolean added = enemies.add(name.trim());
		if (added) save();
		return added;
	}

	@Override
	public boolean remove(String name) {
		boolean removed = enemies.remove(name.trim());
		if (removed) save();
		return removed;
	}

	@Override
	public Collection<String> all() {
		synchronized (enemies) {
			return List.copyOf(enemies);
		}
	}

	private void save() {
		JsonArray a = new JsonArray();
		all().forEach(a::add);
		JsonFiles.write(file, a);
	}
}
