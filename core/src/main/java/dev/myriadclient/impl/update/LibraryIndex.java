package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.myriadclient.impl.update.LibraryStatements.KeyCert;
import dev.myriadclient.impl.update.LibraryStatements.Listing;
import dev.myriadclient.impl.update.LibraryStatements.Revocations;
import dev.myriadclient.impl.update.LibraryStatements.Role;
import dev.myriadclient.impl.update.LibraryStatements.Snapshot;
import dev.myriadclient.impl.update.LibraryStatements.Source;
import dev.myriadclient.impl.update.LibraryStatements.Version;
import dev.myriadclient.impl.update.LibraryStatements.Yank;
import dev.myriadclient.impl.update.SignatureCheck.Sig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and checks the Myriad Library's index ({@code /v1/index.json}):
 * <pre>{@code {"format": 1, "body": "<the index, as a JSON string>", "signatures": [{"key": "..", "sig": ".."}]}}</pre>
 * The signatures are an index key's, on a {@link Snapshot} of the body's SHA-256 and its {@code issued} and
 * {@code expires}. Trust starts from Myriad's root keys alone: the body's certificates say which keys may sign what, the
 * snapshot must be signed by a certified index key, each listing by a listing key, each version by a listing key (or a
 * version key, for an open-source listing) and by its developer. Nothing unsigned decides anything: names and
 * descriptions are only shown, cleaned. An entry that fails a check is left out on its own; a snapshot that fails, or an
 * index older than one already seen, is refused whole.
 */
final class LibraryIndex {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Library");
	static final int FORMAT = 1;
	/** The most an index may be. */
	static final long MAX_INDEX = 4L * 1024 * 1024;
	/** How far ahead of this computer's clock an index may say it was made. */
	static final long FUTURE_SLACK = 24 * 3600;
	private static final int MAX_CERTS = 16, MAX_ADDONS = 2000, MAX_VERSIONS = 20, MAX_YANKS = 200;
	private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}");
	private static final Pattern TAG = Pattern.compile("[A-Za-z0-9._+-]{1,100}");

	/** A release that may be offered: its signed statement, where to download it, and its developer's signatures. */
	record Offered(Version version, String url, @Nullable String minecraft, List<Sig> developer) {
	}

	/**
	 * A listed addon and its releases that may be offered, newest first. {@code name}, {@code description},
	 * {@code author} and {@code repo} are display text only, never trusted.
	 */
	record Entry(Listing listing, String name, String description, String author, @Nullable String repo, List<Offered> versions) {
		boolean verified() {
			return listing.source() == Source.OPEN;
		}
	}

	/** A checked index. {@code stale} once past its expiry: it may still be browsed, but nothing is installed from it. */
	record Index(long issued, long expires, boolean stale, List<Entry> entries) {
		@Nullable Entry entry(String id) {
			for (Entry e : entries) if (e.listing().id().equals(id)) return e;
			return null;
		}
	}

	/** The index, and the state to remember now that it's been accepted. */
	record Verified(Index index, LibraryState state) {
	}

	private LibraryIndex() {
	}

	/**
	 * Checks {@code text} against {@code roots} and what's been seen before ({@code seen}, left unchanged), at {@code now}
	 * (seconds since the epoch). Throws, with the reason, when it can't be trusted at all.
	 */
	static Verified verify(String text, LibraryState seen, long now, List<DevKey> roots) throws IOException {
		if (text.length() > MAX_INDEX) throw new IOException("the Library index is too big");
		JsonObject wrapper = object(parse(text), "the Library index");
		long format = number(wrapper, "format", "the Library index");
		if (format > FORMAT) throw new IOException("the Library index is a newer format; update Myriad to read it");
		if (format < 1) throw new IOException("the Library index has no format");
		String body = string(wrapper, "body", "the Library index", (int) MAX_INDEX);
		List<Sig> snapshotSigs = sigs(wrapper, "signatures", "the Library index");
		JsonObject b = object(parse(body), "the Library index");

		LibraryState state = seen.copy();
		revocations(b, state, roots);
		Map<Role, List<DevKey>> keys = certs(b, state, now, roots);

		long issued = number(b, "issued", "the Library index"), expires = number(b, "expires", "the Library index");
		Snapshot snapshot;
		try {
			snapshot = new Snapshot(issued, expires, sha256(body.getBytes(StandardCharsets.UTF_8)));
		} catch (IllegalArgumentException e) {
			throw new IOException("the Library index has odd times");
		}
		if (SignatureCheck.signer(snapshot.bytes(), snapshotSigs, keys.get(Role.INDEX)) == null) {
			throw new IOException("the Library index isn't signed by a current Myriad index key");
		}
		if (issued > now + FUTURE_SLACK) throw new IOException("the Library index is dated in the future; check this computer's clock");
		if (issued < state.issued) throw new IOException("the Library index is older than one already seen");
		if (issued == state.issued && !snapshot.bodySha256().equals(state.bodySha256)) {
			throw new IOException("the Library index changed without a new date");
		}
		state.issued = issued;
		state.bodySha256 = snapshot.bodySha256();

		List<Entry> entries = new ArrayList<>();
		JsonArray addons = array(b, "addons", "the Library index", MAX_ADDONS);
		Set<String> ids = new HashSet<>(), twice = new HashSet<>();
		for (JsonElement e : addons) {
			Entry entry = entry(e, keys, state);
			if (entry == null) continue;
			if (!ids.add(entry.listing().id())) twice.add(entry.listing().id());
			entries.add(entry);
		}
		// An id listed twice is listed by neither: there's no telling which one is meant.
		entries.removeIf(en -> twice.contains(en.listing().id()));
		return new Verified(new Index(issued, expires, now >= expires, List.copyOf(entries)), state);
	}

	private static void revocations(JsonObject b, LibraryState state, List<DevKey> roots) throws IOException {
		JsonElement r = b.get("revocations");
		if (r == null || r.isJsonNull()) return;
		JsonObject o = object(r, "the Library's revocations");
		Revocations rev;
		try {
			List<DevKey> keys = new ArrayList<>();
			for (JsonElement k : array(o, "keys", "the Library's revocations", LibraryStatements.MAX_REVOKED)) keys.add(key(k, "a revoked key"));
			rev = new Revocations(number(o, "seq", "the Library's revocations"), keys);
		} catch (IllegalArgumentException e) {
			throw new IOException("the Library's revocations are malformed");
		}
		if (SignatureCheck.signer(rev.bytes(), sigs(o, "signatures", "the Library's revocations"), roots) == null) {
			throw new IOException("the Library's revocations aren't signed by Myriad");
		}
		if (rev.seq() > state.revocationSeq) {
			state.revocationSeq = rev.seq();
			for (DevKey k : rev.keys()) state.revoked.add(k.text());
		}
	}

	private static Map<Role, List<DevKey>> certs(JsonObject b, LibraryState state, long now, List<DevKey> roots) throws IOException {
		Map<Role, List<DevKey>> keys = new EnumMap<>(Role.class);
		for (Role r : Role.values()) keys.put(r, new ArrayList<>());
		for (JsonElement e : array(b, "certs", "the Library index", MAX_CERTS)) {
			try {
				JsonObject o = object(e, "a Library key");
				Role role = Role.of(string(o, "role", "a Library key", 20));
				if (role == null) continue;
				long notAfter = o.has("notAfter") ? number(o, "notAfter", "a Library key") : 0;
				KeyCert cert = new KeyCert(key(o.get("key"), "a Library key"), role, notAfter);
				if (state.revoked.contains(cert.key().text())) continue;
				if (role == Role.INDEX && now >= notAfter) continue;
				if (SignatureCheck.signer(cert.bytes(), sigs(o, "signatures", "a Library key"), roots) == null) continue;
				if (!keys.get(role).contains(cert.key())) keys.get(role).add(cert.key());
			} catch (IOException | IllegalArgumentException ex) {
				LOG.debug("Skipping a Library key: {}", ex.getMessage());
			}
		}
		return keys;
	}

	/** One addon, or null (logged) when its listing doesn't check out. */
	private static @Nullable Entry entry(JsonElement e, Map<Role, List<DevKey>> keys, LibraryState state) {
		try {
			JsonObject a = object(e, "a listing");
			JsonObject l = object(a.get("listing"), "a listing");
			Listing listing = listing(l);
			if (SignatureCheck.signer(listing.bytes(), sigs(l, "signatures", "a listing"), keys.get(Role.LISTING)) == null) {
				throw new IOException("its listing isn't signed by a Myriad listing key");
			}
			long seen = state.revisions.getOrDefault(listing.id(), 0L);
			if (listing.revision() < seen) throw new IOException("its listing is older than one already seen");
			state.revisions.put(listing.id(), listing.revision());

			List<DevKey> anyLibrary = new ArrayList<>();
			for (List<DevKey> k : keys.values()) anyLibrary.addAll(k);
			List<DevKey> yankers = new ArrayList<>(anyLibrary);
			yankers.addAll(listing.keys());
			JsonElement y = a.get("yanks");
			if (y != null && !y.isJsonNull()) {
				for (JsonElement ye : array(a, "yanks", "a listing", MAX_YANKS)) {
					JsonObject yo = object(ye, "a yank");
					Yank yank;
					try {
						yank = new Yank(listing.id(), string(yo, "version", "a yank", 64), string(yo, "sha256", "a yank", 64));
					} catch (IllegalArgumentException ex) {
						continue;
					}
					if (SignatureCheck.signer(yank.bytes(), sigs(yo, "signatures", "a yank"), yankers) != null) {
						state.yanks.add(LibraryState.yankKey(yank.id(), yank.version(), yank.sha256()));
					}
				}
			}

			List<DevKey> versionSigners = new ArrayList<>(keys.get(Role.LISTING));
			if (listing.source() == Source.OPEN) versionSigners.addAll(keys.get(Role.VERSION));
			List<Offered> offered = new ArrayList<>();
			Map<String, String> hashOf = new HashMap<>();
			for (JsonElement ve : array(a, "versions", "a listing", MAX_VERSIONS)) {
				Offered o = version(ve, listing, versionSigners);
				if (o == null) continue;
				String before = hashOf.putIfAbsent(o.version().version(), o.version().sha256());
				if (before != null && !before.equals(o.version().sha256())) throw new IOException("it lists two different jars as one version");
				if (state.yanks.contains(LibraryState.yankKey(listing.id(), o.version().version(), o.version().sha256()))) continue;
				offered.add(o);
			}
			offered.sort((x, z) -> Releases.isNewer(x.version().version(), z.version().version()) ? -1 : Releases.isNewer(z.version().version(), x.version().version()) ? 1 : 0);

			JsonObject info = a.get("info") instanceof JsonObject io ? io : new JsonObject();
			String name = shown(info, "name", 64);
			String repo = shown(info, "repo", 201);
			return new Entry(listing, name.isEmpty() ? listing.id() : name, shown(info, "description", 300), shown(info, "author", 64),
				REPO.matcher(repo).matches() ? repo : null, List.copyOf(offered));
		} catch (IOException | IllegalArgumentException ex) {
			LOG.info("Leaving a Library listing out: {}", ex.getMessage());
			return null;
		}
	}

	private static Listing listing(JsonObject l) throws IOException {
		Source source = Source.of(string(l, "source", "a listing", 10));
		if (source == null) throw new IOException("its listing names an unknown kind of source");
		List<String> provides = new ArrayList<>();
		for (JsonElement p : array(l, "provides", "a listing", LibraryStatements.MAX_PROVIDES)) {
			if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString()) throw new IOException("its listing provides something that isn't an id");
			provides.add(p.getAsString());
		}
		List<DevKey> devKeys = new ArrayList<>();
		for (JsonElement k : array(l, "keys", "a listing", DevKey.MAX_KEYS)) devKeys.add(key(k, "a developer key"));
		return new Listing(string(l, "id", "a listing", 64), number(l, "revision", "a listing"), number(l, "repoId", "a listing"),
			number(l, "ownerId", "a listing"), source, l.has("downloadRepoId") ? number(l, "downloadRepoId", "a listing") : 0,
			string(l, "build", "a listing", 64), provides, devKeys);
	}

	/** One release, or null when it doesn't check out (then it's simply not offered). */
	private static @Nullable Offered version(JsonElement ve, Listing listing, List<DevKey> signers) {
		try {
			JsonObject v = object(ve, "a release");
			Version version = new Version(listing.id(), string(v, "version", "a release", 64), number(v, "size", "a release"),
				string(v, "sha256", "a release", 64), string(v, "commit", "a release", 40));
			if (SignatureCheck.signer(version.bytes(), sigs(v, "signatures", "a release"), signers) == null) return null;
			List<Sig> developer = sigs(v, "developer", "a release");
			ReleaseStatement release = new ReleaseStatement(version.id(), version.version(), version.size(), version.sha256());
			if (SignatureCheck.signer(release.bytes(), developer, listing.keys()) == null) return null;
			String url = string(v, "url", "a release", 500);
			if (!downloadable(url)) return null;
			String minecraft = v.has("minecraft") ? string(v, "minecraft", "a release", 200) : null;
			return new Offered(version, url, minecraft, developer);
		} catch (IOException | IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Whether a release may be downloaded from {@code url}: only a GitHub release asset. The jar's hash is signed, so the
	 * address can't change what's installed; this keeps downloads to one known host. With {@code -Dmyriad.dev}, also from
	 * this computer, for testing.
	 */
	static boolean downloadable(String url) {
		URI u;
		try {
			u = URI.create(url);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (u.getRawQuery() != null || u.getRawFragment() != null || u.getRawUserInfo() != null) return false;
		if ("https".equals(u.getScheme()) && "github.com".equals(u.getHost()) && u.getPort() == -1) {
			String[] p = u.getRawPath() == null ? new String[0] : u.getRawPath().split("/", -1);
			// "", owner, repo, "releases", "download", tag, file
			return p.length == 7 && p[0].isEmpty() && REPO.matcher(p[1] + "/" + p[2]).matches() && p[3].equals("releases")
				&& p[4].equals("download") && TAG.matcher(p[5]).matches() && Names.isFile(p[6]);
		}
		return Boolean.getBoolean("myriad.dev") && "http".equals(u.getScheme())
			&& ("localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost())) && Fetch.refused(u) == null;
	}

	/** Display text: control characters, formatting codes and line breaks removed, capped at {@code max}. */
	private static String shown(JsonObject o, String key, int max) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return "";
		String s = e.getAsString();
		StringBuilder out = new StringBuilder();
		s.codePoints().limit(4 * max).forEach(c -> {
			if (Character.isISOControl(c) || c == '§' || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029) return;
			out.appendCodePoint(c);
		});
		String t = out.toString().strip();
		return t.codePointCount(0, t.length()) > max ? t.substring(0, t.offsetByCodePoints(0, max)) : t;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static JsonElement parse(String text) throws IOException {
		try {
			return JsonParser.parseString(text);
		} catch (JsonParseException | IllegalStateException e) {
			throw new IOException("the Library index isn't valid JSON");
		}
	}

	private static JsonObject object(@Nullable JsonElement e, String what) throws IOException {
		if (e == null || !e.isJsonObject()) throw new IOException(what + " isn't an object");
		return e.getAsJsonObject();
	}

	private static JsonArray array(JsonObject o, String key, String what, int max) throws IOException {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonArray()) throw new IOException(what + " has no " + key);
		if (e.getAsJsonArray().size() > max) throw new IOException(what + " has too many " + key);
		return e.getAsJsonArray();
	}

	private static String string(JsonObject o, String key, String what, int max) throws IOException {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IOException(what + " has no " + key);
		String s = e.getAsString();
		if (s.length() > max) throw new IOException(what + "'s " + key + " is too long");
		return s;
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

	private static DevKey key(@Nullable JsonElement e, String what) throws IOException {
		DevKey k = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? DevKey.parse(e.getAsString()) : null;
		if (k == null) throw new IOException(what + " isn't an Ed25519 key");
		return k;
	}

	private static List<Sig> sigs(JsonObject o, String key, String what) throws IOException {
		List<Sig> out = new ArrayList<>();
		JsonElement s = o.get(key);
		if (s == null || s.isJsonNull()) return out;
		if (!s.isJsonArray()) throw new IOException(what + "'s " + key + " aren't a list");
		for (JsonElement e : s.getAsJsonArray()) {
			if (out.size() >= SignatureCheck.MAX_SIGNATURES) break;
			JsonObject so = object(e, "a signature");
			out.add(new Sig(string(so, "key", "a signature", 32), string(so, "sig", "a signature", 100)));
		}
		return out;
	}
}
