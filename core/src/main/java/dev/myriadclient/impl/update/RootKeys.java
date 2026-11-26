package dev.myriadclient.impl.update;

import java.util.List;

/**
 * Myriad's own root keys: what every core update must be signed with, whatever any download says. There are two, either
 * of which signs: the primary, kept offline by the maintainer, and a backup kept apart from it, so losing one doesn't end
 * updates. They never sit in CI or on a server: a core release is built by CI, rebuilt and compared by the maintainer,
 * and only then signed (see {@code tools/sign-release}). A root key changes only through a core update signed by the
 * old one, so core's own {@code fabric.mod.json} lists these same keys (a test holds the two together).
 */
final class RootKeys {
	static final List<DevKey> ALL = List.of(
			key("ed25519:nCz3FbS30fsLg-MkX2de8AOrACc3xUYYo_RYAwpvRww"), // primary
			key("ed25519:50gMky_GrJ-mjBAlIKuWDURAFNzJYrBXzOUEuFG5RcI")); // backup

	private static DevKey key(String text) {
		DevKey k = DevKey.parse(text);
		if (k == null) throw new IllegalStateException("bad root key " + text);
		return k;
	}

	private RootKeys() {
	}
}
