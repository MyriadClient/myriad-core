package dev.myriadclient.api.registry;

import java.util.Locale;
import java.util.Objects;

/**
 * A namespaced identifier ({@code namespace:path}). The namespace is the Fabric mod id of the addon
 * that registered the thing, so two addons can ship a module called "speed" without colliding. The path is usually a
 * display name in snake_case ({@link #toPath}). Ids are compared, hashed and sorted by both parts, case-sensitively.
 *
 * <pre>{@code
 * MyriadId id = MyriadId.of("my-addon", MyriadId.toPath("Auto Walk"));   // my-addon:auto_walk
 * MyriadId same = MyriadId.parse("my-addon:auto_walk");
 * }</pre>
 *
 * @param namespace the mod id of the addon the thing belongs to; not empty
 * @param path      the thing's name within that addon; not empty
 */
public record MyriadId(String namespace, String path) implements Comparable<MyriadId> {
	/** @throws IllegalArgumentException if either part is empty */
	public MyriadId {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(path, "path");
		if (namespace.isEmpty() || path.isEmpty()) throw new IllegalArgumentException("Empty id component: " + namespace + ":" + path);
	}

	/** The id {@code namespace:path}, both used as given. */
	public static MyriadId of(String namespace, String path) {
		return new MyriadId(namespace, path);
	}

	/**
	 * Parses {@code ns:path} at its first colon; a bare {@code path} gets {@code defaultNamespace}.
	 *
	 * @throws IllegalArgumentException if a part is empty ({@code ":x"}, {@code "x:"})
	 */
	public static MyriadId parse(String id, String defaultNamespace) {
		int i = id.indexOf(':');
		if (i < 0) return new MyriadId(defaultNamespace, id);
		return new MyriadId(id.substring(0, i), id.substring(i + 1));
	}

	/**
	 * Parses {@code ns:path} at its first colon, as {@link #toString()} writes it.
	 *
	 * @throws IllegalArgumentException if there's no colon or a part is empty
	 */
	public static MyriadId parse(String id) {
		int i = id.indexOf(':');
		if (i < 0) throw new IllegalArgumentException("Missing namespace: " + id);
		return new MyriadId(id.substring(0, i), id.substring(i + 1));
	}

	/**
	 * Converts a display name like "Kill Aura" to an id path like "kill_aura": letters and digits in lower case, a
	 * {@code _} for each run of anything else and between a lower-case letter and an upper-case one ("AutoWalk" gives
	 * "auto_walk"), none at the ends. Empty if the name has no letters or digits.
	 */
	public static String toPath(String name) {
		StringBuilder sb = new StringBuilder(name.length());
		char prev = ' ';
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				if (Character.isUpperCase(c) && Character.isLowerCase(prev) && !sb.isEmpty()) sb.append('_');
				sb.append(Character.toLowerCase(c));
			} else if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '_') {
				sb.append('_');
			}
			prev = c;
		}
		while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '_') sb.setLength(sb.length() - 1);
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	/** By namespace, then path. */
	@Override
	public int compareTo(MyriadId o) {
		int c = namespace.compareTo(o.namespace);
		return c != 0 ? c : path.compareTo(o.path);
	}

	/** {@code namespace:path}, which {@link #parse(String)} reads back. */
	@Override
	public String toString() {
		return namespace + ":" + path;
	}
}
