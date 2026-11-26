package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.myriadclient.impl.update.Releases.Asset;
import dev.myriadclient.impl.update.Releases.Release;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An addon's own update feed: a JSON file it hosts anywhere over https (its {@code fabric.mod.json} gives the address as
 * {@code "custom": {"myriad": {"updates": "https://..."}}}). Format 1:
 * <pre>{@code
 * {"format": 1, "id": "my-addon", "name": "My Addon", "keys": ["ed25519:..."],
 *  "versions": [{"version": "1.2.0", "file": "my-addon-1.2.0.jar", "url": "my-addon-1.2.0.jar",
 *                "size": 123456, "sha256": "...", "minecraft": "~26.3", "notesUrl": "https://...",
 *                "signatures": [{"key": "<key id>", "sig": "<base64url>"}]}]}
 * }</pre>
 * {@code url} may be relative to the feed. Unknown fields are ignored, so later formats can add them; an entry that's
 * marked as something this version doesn't know how to install ({@code delivery} other than {@code jar}, {@code access}
 * other than {@code public}) or as a pre-release is skipped. Anything malformed refuses the whole feed: nothing in a
 * broken feed is trusted.
 */
final class FeedReleases {
	static final int FORMAT = 1;
	/** The most a feed may be, and the most entries read from one. */
	static final long MAX_FEED = 1024 * 1024;
	private static final int MAX_VERSIONS = 500;
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/** A parsed feed; {@code keys} only matter to a first install from it (see {@link LinkInstall}). */
	record Feed(String id, @Nullable String name, List<DevKey> keys, List<Release> releases) {
	}

	private FeedReleases() {
	}

	static Feed parse(String text, URI feed) throws IOException {
		JsonObject root;
		try {
			JsonElement e = JsonParser.parseString(text);
			if (!e.isJsonObject()) throw new IOException("the feed isn't a feed");
			root = e.getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new IOException("the feed isn't valid JSON");
		}
		int format = integer(root, "format", "the feed");
		if (format > FORMAT) throw new IOException("the feed is a newer format (" + format + "); update Myriad to read it");
		if (format < 1) throw new IOException("the feed has no format");
		String id = string(root, "id", "the feed");
		if (!Names.isId(id)) throw new IOException("the feed's id isn't a mod id");
		String name = optional(root, "name", "the feed", 100);
		List<DevKey> keys = new ArrayList<>();
		JsonElement k = root.get("keys");
		if (k != null) {
			if (!k.isJsonArray()) throw new IOException("the feed's keys aren't a list");
			for (JsonElement e : k.getAsJsonArray()) {
				DevKey key = e.isJsonPrimitive() ? DevKey.parse(e.getAsString()) : null;
				if (key == null) throw new IOException("the feed lists a key that isn't an Ed25519 key");
				if (keys.size() < DevKey.MAX_KEYS && !keys.contains(key)) keys.add(key);
			}
		}
		JsonElement versions = root.get("versions");
		if (versions == null || !versions.isJsonArray()) throw new IOException("the feed has no versions");
		if (versions.getAsJsonArray().size() > MAX_VERSIONS) throw new IOException("the feed lists too many versions");
		List<Release> releases = new ArrayList<>();
		for (JsonElement e : versions.getAsJsonArray()) {
			if (!e.isJsonObject()) throw new IOException("the feed has a version that isn't an entry");
			Release r = entry(e.getAsJsonObject(), feed);
			if (r != null) releases.add(r);
		}
		return new Feed(id, name, List.copyOf(keys), List.copyOf(releases));
	}

	/**
	 * One entry: the release it describes, or null when it's one to skip (a pre-release, or a kind this version can't
	 * install). Also what a GitHub release's {@code <jar>.sig} holds, with {@code feed} the {@code .sig}'s address.
	 */
	static @Nullable Release entry(JsonObject o, URI feed) throws IOException {
		String version = string(o, "version", "a version");
		if (!Names.isVersion(version)) throw new IOException("the feed has a version that isn't one");
		String what = "version " + version;
		if (bool(o, "prerelease", what)) return null;
		if (!"jar".equals(optionalOr(o, "delivery", what, "jar")) || !"public".equals(optionalOr(o, "access", what, "public"))) return null;
		String file = string(o, "file", what);
		if (!Names.isFile(file)) throw new IOException(what + " has a file name that isn't a plain jar name");
		URI url;
		try {
			url = feed.resolve(string(o, "url", what));
		} catch (IllegalArgumentException e) {
			throw new IOException(what + " has an address that isn't one");
		}
		String why = Fetch.refused(url);
		if (why != null) throw new IOException(what + "'s download: " + why);
		long size = number(o, "size", what);
		if (size <= 0 || size > JarCheck.MAX_JAR) throw new IOException(what + " has a size Myriad won't download");
		String sha256 = string(o, "sha256", what);
		if (!SHA256.matcher(sha256).matches()) throw new IOException(what + " has a SHA-256 that isn't 64 lowercase hex digits");
		String minecraft = optional(o, "minecraft", what, 200);
		String notes = optional(o, "notesUrl", what, 500);
		if (notes != null) {
			try {
				if (Fetch.refused(URI.create(notes)) != null) notes = null;
			} catch (IllegalArgumentException e) {
				notes = null;
			}
		}
		List<SignatureCheck.Sig> sigs = new ArrayList<>();
		JsonElement s = o.get("signatures");
		if (s != null) {
			if (!s.isJsonArray()) throw new IOException(what + "'s signatures aren't a list");
			for (JsonElement e : s.getAsJsonArray()) {
				if (!e.isJsonObject()) throw new IOException(what + " has a signature that isn't one");
				if (sigs.size() < SignatureCheck.MAX_SIGNATURES) {
					sigs.add(new SignatureCheck.Sig(string(e.getAsJsonObject(), "key", what), string(e.getAsJsonObject(), "sig", what)));
				}
			}
		}
		return new Release(version, notes, new Asset(file, url.toString(), size, sha256, List.copyOf(sigs), null), minecraft);
	}

	private static String string(JsonObject o, String key, String what) throws IOException {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IOException(what + " has no " + key);
		String s = e.getAsString();
		if (s.length() > 2000 || s.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) throw new IOException(what + " has an odd " + key);
		return s;
	}

	private static @Nullable String optional(JsonObject o, String key, String what, int max) throws IOException {
		if (!o.has(key) || o.get(key).isJsonNull()) return null;
		String s = string(o, key, what);
		if (s.length() > max) throw new IOException(what + "'s " + key + " is too long");
		return s;
	}

	private static String optionalOr(JsonObject o, String key, String what, String fallback) throws IOException {
		String s = optional(o, key, what, 50);
		return s == null ? fallback : s;
	}

	private static boolean bool(JsonObject o, String key, String what) throws IOException {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) return false;
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IOException(what + "'s " + key + " isn't true or false");
		return e.getAsBoolean();
	}

	private static long number(JsonObject o, String key, String what) throws IOException {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IOException(what + " has no " + key);
		try {
			return e.getAsJsonPrimitive().getAsBigDecimal().longValueExact();
		} catch (ArithmeticException ex) {
			throw new IOException(what + "'s " + key + " isn't a whole number");
		}
	}

	private static int integer(JsonObject o, String key, String what) throws IOException {
		long n = number(o, key, what);
		if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IOException(what + "'s " + key + " is out of range");
		return (int) n;
	}
}
