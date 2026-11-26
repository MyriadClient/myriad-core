package dev.myriadclient.impl.update;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the Myriad Library's keys sign. Like {@link ReleaseStatement}, each is written out from typed, checked fields,
 * never taken as text, one {@code name=value} line each ending in {@code \n}, and its first line names the kind, so a
 * signature on one kind never counts as another. Lists are sorted and without repeats, so there's one way to spell each.
 * <ul>
 * <li>{@link KeyCert}, signed by a Myriad root key: a Library key and the one thing it may sign.</li>
 * <li>{@link Revocations}, signed by a root key: Library keys no longer trusted, numbered so a later list wins.</li>
 * <li>{@link Listing}, signed by a listing key: an addon accepted into the Library, tied to its repository, its build
 * logic and its developer's keys.</li>
 * <li>{@link Version}, signed by a listing key, or by a version key when the listing is open source: one release's exact
 * jar.</li>
 * <li>{@link Yank}, signed by any Library key or one of the listing's developer keys: a release never to be offered
 * again.</li>
 * <li>{@link Snapshot}, signed by an index key: which index is current, and until when.</li>
 * </ul>
 */
final class LibraryStatements {
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	static final int MAX_PROVIDES = 16;
	static final int MAX_REVOKED = 64;

	private LibraryStatements() {
	}

	/** What a Library key may sign. */
	enum Role {
		LISTING, VERSION, INDEX;

		String text() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		static @Nullable Role of(@Nullable String s) {
			for (Role r : values()) if (r.text().equals(s)) return r;
			return null;
		}
	}

	/** Whether a listing's code is public ("Verified": we build it from its public source) or not ("Reviewed"). */
	enum Source {
		OPEN, CLOSED;

		String text() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		static @Nullable Source of(@Nullable String s) {
			for (Source v : values()) if (v.text().equals(s)) return v;
			return null;
		}
	}

	/**
	 * A Library key and its role, certified by a root key. Only an index key's certificate expires ({@code notAfter},
	 * seconds since the epoch, checked against the clock): nothing an index key signs is kept, while a listing or version
	 * signature has no trusted time to check an expiry against, so those keys are ended by revocation instead.
	 */
	record KeyCert(DevKey key, Role role, long notAfter) {
		static final String HEADER = "myriad-library-key:1";

		KeyCert {
			if (role == Role.INDEX ? notAfter <= 0 : notAfter != 0) throw new IllegalArgumentException("only an index key's certificate expires, and it must");
		}

		byte[] bytes() {
			return ascii(HEADER + "\nkey=" + key.text() + "\nrole=" + role.text() + (role == Role.INDEX ? "\nnot-after=" + notAfter : "") + "\n");
		}
	}

	/** Library keys revoked by a root key; {@code seq} only ever grows, and a client keeps every key it has seen revoked. */
	record Revocations(long seq, List<DevKey> keys) {
		static final String HEADER = "myriad-library-revocations:1";

		Revocations {
			if (seq <= 0) throw new IllegalArgumentException("not a sequence number");
			keys = sortedKeys(keys, 0, MAX_REVOKED);
		}

		byte[] bytes() {
			StringBuilder s = new StringBuilder(HEADER).append("\nseq=").append(seq).append('\n');
			for (DevKey k : keys) s.append("key=").append(k.text()).append('\n');
			return ascii(s.toString());
		}
	}

	/**
	 * An addon accepted into the Library. {@code repoId} and {@code ownerId} are GitHub's numbers (they survive renames, not
	 * transfers); {@code downloadRepoId} is the public repository a closed-source addon releases its jars from (0 for open
	 * source, which releases from {@code repoId}); {@code build} is the hash of everything in the repository that isn't the
	 * addon's own code or resources (its build logic), so changed build logic needs a new listing; {@code keys} are the
	 * developer's signing keys. {@code revision} only grows.
	 */
	record Listing(String id, long revision, long repoId, long ownerId, Source source, long downloadRepoId, String build, List<String> provides, List<DevKey> keys) {
		static final String HEADER = "myriad-library-listing:1";

		Listing {
			if (!Names.isId(id)) throw new IllegalArgumentException("not a mod id");
			if (revision <= 0 || repoId <= 0 || ownerId <= 0) throw new IllegalArgumentException("not a revision or GitHub id");
			if (source == Source.CLOSED ? downloadRepoId <= 0 : downloadRepoId != 0) throw new IllegalArgumentException("a closed listing names its download repository, an open one doesn't");
			if (!SHA256.matcher(build).matches()) throw new IllegalArgumentException("not a build hash");
			List<String> p = new ArrayList<>();
			for (String s : provides) {
				if (!Names.isId(s) || s.equals(id)) throw new IllegalArgumentException("not a provided mod id");
				if (!p.contains(s)) p.add(s);
			}
			if (p.size() > MAX_PROVIDES) throw new IllegalArgumentException("provides too many ids");
			p.sort(Comparator.naturalOrder());
			provides = List.copyOf(p);
			keys = sortedKeys(keys, 1, DevKey.MAX_KEYS);
		}

		byte[] bytes() {
			StringBuilder s = new StringBuilder(HEADER)
				.append("\nid=").append(id)
				.append("\nrevision=").append(revision)
				.append("\nrepo-id=").append(repoId)
				.append("\nowner-id=").append(ownerId)
				.append("\nsource=").append(source.text());
			if (source == Source.CLOSED) s.append("\ndownload-repo-id=").append(downloadRepoId);
			s.append("\nbuild=").append(build).append("\nprovides=").append(String.join(",", provides)).append('\n');
			for (DevKey k : keys) s.append("key=").append(k.text()).append('\n');
			return ascii(s.toString());
		}
	}

	/** One release of a listed addon: its exact jar (size and SHA-256) and the commit it was built from. */
	record Version(String id, String version, long size, String sha256, String commit) {
		static final String HEADER = "myriad-library-version:1";

		Version {
			if (!Names.isId(id)) throw new IllegalArgumentException("not a mod id");
			if (!Names.isVersion(version)) throw new IllegalArgumentException("not a version");
			if (size <= 0 || size > JarCheck.MAX_JAR) throw new IllegalArgumentException("not a size");
			if (!SHA256.matcher(sha256).matches()) throw new IllegalArgumentException("not a SHA-256");
			if (!COMMIT.matcher(commit).matches()) throw new IllegalArgumentException("not a commit");
		}

		byte[] bytes() {
			return ascii(HEADER + "\nid=" + id + "\nversion=" + version + "\nsize=" + size + "\nsha256=" + sha256 + "\ncommit=" + commit + "\n");
		}
	}

	/** A release taken back: never offered again, whatever a later index says. */
	record Yank(String id, String version, String sha256) {
		static final String HEADER = "myriad-library-yank:1";

		Yank {
			if (!Names.isId(id)) throw new IllegalArgumentException("not a mod id");
			if (!Names.isVersion(version)) throw new IllegalArgumentException("not a version");
			if (!SHA256.matcher(sha256).matches()) throw new IllegalArgumentException("not a SHA-256");
		}

		byte[] bytes() {
			return ascii(HEADER + "\nid=" + id + "\nversion=" + version + "\nsha256=" + sha256 + "\n");
		}
	}

	/** Which index is current: its body's SHA-256, when it was made and when it stops counting (seconds since the epoch). */
	record Snapshot(long issued, long expires, String bodySha256) {
		static final String HEADER = "myriad-library-snapshot:1";

		Snapshot {
			if (issued <= 0 || expires <= issued) throw new IllegalArgumentException("not a time span");
			if (!SHA256.matcher(bodySha256).matches()) throw new IllegalArgumentException("not a SHA-256");
		}

		byte[] bytes() {
			return ascii(HEADER + "\nissued=" + issued + "\nexpires=" + expires + "\nbody-sha256=" + bodySha256 + "\n");
		}
	}

	private static List<DevKey> sortedKeys(List<DevKey> keys, int min, int max) {
		List<DevKey> k = new ArrayList<>();
		for (DevKey key : keys) if (!k.contains(key)) k.add(key);
		if (k.size() < min || k.size() > max) throw new IllegalArgumentException("between " + min + " and " + max + " keys");
		k.sort(Comparator.comparing(DevKey::text));
		return List.copyOf(k);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}
}
