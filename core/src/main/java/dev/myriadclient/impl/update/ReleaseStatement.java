package dev.myriadclient.impl.update;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * What a developer's signature on a release covers: which addon, which version, and exactly which jar (its size and
 * SHA-256). It's written out from these typed fields, never taken from the feed as text, so there's one way to spell it:
 * <pre>
 * myriad-addon-release:1
 * id=my-addon
 * version=1.2.0
 * size=123456
 * sha256=&lt;64 lowercase hex&gt;
 * </pre>
 * each line ending in {@code \n}. The first line keeps a release signature from ever counting as any other kind of
 * Myriad signature. Notes and version ranges aren't signed: the jar's own {@code fabric.mod.json}, inside the hashed
 * bytes, is what counts.
 */
record ReleaseStatement(String id, String version, long size, String sha256) {
	static final String HEADER = "myriad-addon-release:1";
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	ReleaseStatement {
		if (!Names.isId(id)) throw new IllegalArgumentException("not a mod id");
		if (!Names.isVersion(version)) throw new IllegalArgumentException("not a version");
		if (size <= 0) throw new IllegalArgumentException("not a size");
		if (!SHA256.matcher(sha256).matches()) throw new IllegalArgumentException("not a SHA-256");
	}

	static ReleaseStatement of(String id, String version, byte[] jar, byte[] sha256) {
		return new ReleaseStatement(id, version, jar.length, HexFormat.of().formatHex(sha256));
	}

	byte[] bytes() {
		return (HEADER + "\nid=" + id + "\nversion=" + version + "\nsize=" + size + "\nsha256=" + sha256 + "\n").getBytes(StandardCharsets.US_ASCII);
	}
}
