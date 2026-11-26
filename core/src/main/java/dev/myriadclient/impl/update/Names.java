package dev.myriadclient.impl.update;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The strict forms of what an update names, so nothing read from a feed, a release or a jar can step outside them: a mod
 * id (Fabric's rule), a version, a jar's file name. Anything else is refused, never cleaned up.
 */
final class Names {
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
	private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._+-]{1,128}\\.jar");

	private Names() {
	}

	static boolean isId(@Nullable String s) {
		return s != null && ID.matcher(s).matches();
	}

	static boolean isVersion(@Nullable String s) {
		return s != null && VERSION.matcher(s).matches();
	}

	/** A plain jar file name: no folders, nothing hidden ({@code .x.jar}), nothing that's only dots. */
	static boolean isFile(@Nullable String s) {
		return s != null && FILE.matcher(s).matches() && !s.startsWith(".");
	}

	/** {@code dir}'s file {@code name}, or null unless {@code name} is a plain jar file name that stays inside it. */
	static @Nullable Path fileIn(Path dir, String name) {
		if (!isFile(name)) return null;
		Path base = dir.toAbsolutePath().normalize();
		Path p = base.resolve(name).normalize();
		return p.getParent() != null && p.getParent().equals(base) ? p : null;
	}
}
