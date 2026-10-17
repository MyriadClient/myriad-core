package dev.myriadclient.impl.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.myriadclient.api.service.Friends;
import dev.myriadclient.impl.config.JsonFiles;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Friends are global (not per profile), stored in {@code myriad/friends.json}. */
public final class FriendsManager implements Friends {
	private final Path file;
	private final Set<String> friends = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

	public FriendsManager(Path root) {
		this.file = root.resolve("friends.json");
		JsonFiles.read(file).filter(JsonElement::isJsonArray).ifPresent(a -> {
			for (JsonElement e : a.getAsJsonArray()) friends.add(e.getAsString());
		});
	}

	@Override
	public boolean isFriend(String name) {
		return friends.contains(name);
	}

	@Override
	public boolean add(String name) {
		boolean added = friends.add(name.trim());
		if (added) save();
		return added;
	}

	@Override
	public boolean remove(String name) {
		boolean removed = friends.remove(name.trim().toLowerCase(Locale.ROOT)) || friends.remove(name.trim());
		if (removed) save();
		return removed;
	}

	@Override
	public Collection<String> all() {
		synchronized (friends) {
			return java.util.List.copyOf(friends);
		}
	}

	private void save() {
		JsonArray a = new JsonArray();
		all().forEach(a::add);
		JsonFiles.write(file, a);
	}
}
