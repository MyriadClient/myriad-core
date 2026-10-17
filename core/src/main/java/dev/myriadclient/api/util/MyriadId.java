package dev.myriadclient.api.util;

import java.util.Locale;
import java.util.Objects;

/**
 * A namespaced identifier ({@code namespace:path}). The namespace is the Fabric mod id of the addon
 * that registered the thing, so two addons can ship a module called "speed" without colliding.
 */
public record MyriadId(String namespace, String path) implements Comparable<MyriadId> {
	public MyriadId {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(path, "path");
		if (namespace.isEmpty() || path.isEmpty()) throw new IllegalArgumentException("Empty id component: " + namespace + ":" + path);
	}

	public static MyriadId of(String namespace, String path) {
		return new MyriadId(namespace, path);
	}

	/** Parses {@code ns:path}; a bare {@code path} gets {@code defaultNamespace}. */
	public static MyriadId parse(String id, String defaultNamespace) {
		int i = id.indexOf(':');
		if (i < 0) return new MyriadId(defaultNamespace, id);
		return new MyriadId(id.substring(0, i), id.substring(i + 1));
	}

	public static MyriadId parse(String id) {
		int i = id.indexOf(':');
		if (i < 0) throw new IllegalArgumentException("Missing namespace: " + id);
		return new MyriadId(id.substring(0, i), id.substring(i + 1));
	}

	/** Converts a display name like "Kill Aura" to an id path like "kill_aura". */
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

	@Override
	public int compareTo(MyriadId o) {
		int c = namespace.compareTo(o.namespace);
		return c != 0 ? c : path.compareTo(o.path);
	}

	@Override
	public String toString() {
		return namespace + ":" + path;
	}
}
