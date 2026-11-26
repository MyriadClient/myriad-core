package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.impl.config.JsonFiles;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens for private update sources (a private feed, or a private GitHub repo with a read-only access token), kept in
 * {@code myriad/updates/sources.json}: a plain file, readable by anything running as you, kept out of profiles, shares and
 * logs. Each token goes only to its own source's origin (see {@link Fetch}).
 */
final class FeedTokens {
	private static final int MAX_TOKEN = 4096;
	private final Path file;
	private final Map<String, String> tokens = new ConcurrentHashMap<>();

	FeedTokens(Path dir) {
		this.file = dir.resolve("sources.json");
		JsonFiles.read(file).filter(JsonElement::isJsonObject).ifPresent(o -> {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject().entrySet()) {
				if (!Names.isId(e.getKey()) || !e.getValue().isJsonObject()) continue;
				JsonElement t = e.getValue().getAsJsonObject().get("token");
				if (t != null && t.isJsonPrimitive() && valid(t.getAsString())) tokens.put(e.getKey(), t.getAsString());
			}
		});
	}

	/** A token is printable ASCII without spaces (so it can't break the header it's sent in). */
	static boolean valid(String token) {
		return !token.isEmpty() && token.length() <= MAX_TOKEN && token.chars().allMatch(c -> c > 0x20 && c < 0x7f);
	}

	@Nullable String get(String id) {
		return tokens.get(id);
	}

	boolean has(String id) {
		return tokens.containsKey(id);
	}

	/** Sets (or with null or blank, removes) {@code id}'s token; false when it isn't a valid token. */
	synchronized boolean set(String id, @Nullable String token) {
		if (token == null || token.isBlank()) tokens.remove(id);
		else if (!valid(token.strip())) return false;
		else tokens.put(id, token.strip());
		JsonObject root = new JsonObject();
		tokens.forEach((k, v) -> {
			JsonObject o = new JsonObject();
			o.addProperty("token", v);
			root.add(k, o);
		});
		JsonFiles.write(file, root);
		try {
			// Owner-only where the system has such permissions.
			java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | java.io.IOException ignored) {
			// Windows: the profile folder's own permissions apply.
		}
		return true;
	}
}
