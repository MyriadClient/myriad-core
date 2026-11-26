package dev.myriadclient.gradle.signing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The statements the Myriad Library's keys sign, spelled exactly as core's {@code LibraryStatements} reads them (core's
 * tests hold signatures made here to that). Built from {@code name=value} fields, every one checked: a statement that
 * doesn't pass is never written, so nothing odd is ever signed.
 * <pre>
 * key          key=ed25519:… role=listing|version|index [not-after=&lt;unix s&gt;, index only]
 * revocations  seq=N key=…(0..64)
 * listing      id revision repo-id owner-id source=open|closed [download-repo-id, closed only] build provides=a,b key=…(1..8)
 * version      id version size sha256 commit
 * yank         id version sha256
 * snapshot     issued expires body-sha256
 * </pre>
 */
public final class LibraryStatements {
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,64}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,18}");
	private static final long MAX_JAR = 64L * 1024 * 1024;
	public static final Set<String> KINDS = Set.of("key", "revocations", "listing", "version", "yank", "snapshot");

	private LibraryStatements() {
	}

	/** The statement of {@code kind} from {@code fields} (a field named twice, like {@code key}, holds every value). */
	public static byte[] statement(String kind, Map<String, List<String>> fields) {
		Fields f = new Fields(kind, fields);
		String text = switch (kind) {
			case "key" -> {
				String key = f.key("key");
				String role = f.one("role");
				if (!Set.of("listing", "version", "index").contains(role)) throw new IllegalArgumentException("role is listing, version or index");
				String notAfter = role.equals("index") ? "\nnot-after=" + f.number("not-after") : f.none("not-after");
				yield "myriad-library-key:1\nkey=" + key + "\nrole=" + role + notAfter + "\n";
			}
			case "revocations" -> "myriad-library-revocations:1\nseq=" + f.number("seq") + "\n" + f.keyLines(0, 64);
			case "listing" -> {
				String id = f.id("id");
				String source = f.one("source");
				if (!source.equals("open") && !source.equals("closed")) throw new IllegalArgumentException("source is open or closed");
				String download = source.equals("closed") ? "\ndownload-repo-id=" + f.number("download-repo-id") : f.none("download-repo-id");
				List<String> provides = new ArrayList<>();
				String p = f.optional("provides");
				if (p != null && !p.isEmpty()) {
					for (String s : p.split(",", -1)) {
						if (!ID.matcher(s).matches() || s.equals(id)) throw new IllegalArgumentException("provides holds mod ids other than the listing's own");
						if (!provides.contains(s)) provides.add(s);
					}
				}
				if (provides.size() > 16) throw new IllegalArgumentException("provides too many ids");
				provides.sort(Comparator.naturalOrder());
				yield "myriad-library-listing:1\nid=" + id + "\nrevision=" + f.number("revision") + "\nrepo-id=" + f.number("repo-id")
					+ "\nowner-id=" + f.number("owner-id") + "\nsource=" + source + download + "\nbuild=" + f.hex("build", SHA256)
					+ "\nprovides=" + String.join(",", provides) + "\n" + f.keyLines(1, 8);
			}
			case "version" -> {
				String size = f.number("size");
				if (Long.parseLong(size) > MAX_JAR) throw new IllegalArgumentException("size is past what Myriad downloads");
				yield "myriad-library-version:1\nid=" + f.id("id") + "\nversion=" + f.version("version") + "\nsize=" + size
					+ "\nsha256=" + f.hex("sha256", SHA256) + "\ncommit=" + f.hex("commit", COMMIT) + "\n";
			}
			case "yank" -> "myriad-library-yank:1\nid=" + f.id("id") + "\nversion=" + f.version("version") + "\nsha256=" + f.hex("sha256", SHA256) + "\n";
			case "snapshot" -> {
				String issued = f.number("issued"), expires = f.number("expires");
				if (Long.parseLong(expires) <= Long.parseLong(issued)) throw new IllegalArgumentException("expires comes after issued");
				yield "myriad-library-snapshot:1\nissued=" + issued + "\nexpires=" + expires + "\nbody-sha256=" + f.hex("body-sha256", SHA256) + "\n";
			}
			default -> throw new IllegalArgumentException("not a Library statement: " + kind + " (" + String.join(", ", KINDS) + ")");
		};
		f.allUsed();
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	/** {@code name=value} arguments as fields, in order; a name given twice keeps both values. */
	public static Map<String, List<String>> fields(List<String> args) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (String a : args) {
			int eq = a.indexOf('=');
			if (eq <= 0) throw new IllegalArgumentException("fields are name=value, not " + a);
			out.computeIfAbsent(a.substring(0, eq), k -> new ArrayList<>()).add(a.substring(eq + 1));
		}
		return out;
	}

	/** A signature as the Library carries one: {@code {"key": "<key id>", "sig": "<base64url>"}}. */
	public static String signatureJson(Signing.Key key, byte[] statement) throws java.security.GeneralSecurityException {
		return "{\"key\": \"" + key.id() + "\", \"sig\": \"" + Base64.getUrlEncoder().withoutPadding().encodeToString(key.sign(statement)) + "\"}";
	}

	private static final class Fields {
		private final String kind;
		private final Map<String, List<String>> fields;
		private final java.util.HashSet<String> used = new java.util.HashSet<>();

		Fields(String kind, Map<String, List<String>> fields) {
			this.kind = kind;
			this.fields = fields;
		}

		String optional(String name) {
			used.add(name);
			List<String> v = fields.get(name);
			if (v == null) return null;
			if (v.size() != 1) throw new IllegalArgumentException(name + " is given more than once");
			String s = v.getFirst();
			if (s.chars().anyMatch(c -> c < 0x21 || c > 0x7e || c == '=')) throw new IllegalArgumentException(name + " has characters a statement can't hold");
			return s;
		}

		String one(String name) {
			String s = optional(name);
			if (s == null) throw new IllegalArgumentException("a " + kind + " statement needs " + name);
			return s;
		}

		String none(String name) {
			if (fields.containsKey(name)) throw new IllegalArgumentException("this " + kind + " statement doesn't take " + name);
			used.add(name);
			return "";
		}

		String number(String name) {
			String s = one(name);
			if (!NUMBER.matcher(s).matches()) throw new IllegalArgumentException(name + " is a positive whole number");
			return s;
		}

		String id(String name) {
			String s = one(name);
			if (!ID.matcher(s).matches()) throw new IllegalArgumentException(name + " isn't a mod id");
			return s;
		}

		String version(String name) {
			String s = one(name);
			if (!VERSION.matcher(s).matches()) throw new IllegalArgumentException(name + " isn't a version Myriad signs");
			return s;
		}

		String hex(String name, Pattern p) {
			String s = one(name);
			if (!p.matcher(s).matches()) throw new IllegalArgumentException(name + " isn't lowercase hex of the right length");
			return s;
		}

		String key(String name) {
			String s = one(name);
			checkKey(s);
			return s;
		}

		String keyLines(int min, int max) {
			used.add("key");
			List<String> keys = new ArrayList<>();
			for (String k : fields.getOrDefault("key", List.of())) {
				checkKey(k);
				if (!keys.contains(k)) keys.add(k);
			}
			if (keys.size() < min || keys.size() > max) throw new IllegalArgumentException("between " + min + " and " + max + " keys");
			keys.sort(Comparator.naturalOrder());
			StringBuilder s = new StringBuilder();
			for (String k : keys) s.append("key=").append(k).append('\n');
			return s.toString();
		}

		void allUsed() {
			for (String name : fields.keySet()) if (!used.contains(name)) throw new IllegalArgumentException("a " + kind + " statement has no field " + name);
		}

		private static void checkKey(String s) {
			if (!s.startsWith(Signing.PUBLIC)) throw new IllegalArgumentException("not an Ed25519 key: " + s);
			byte[] raw;
			try {
				raw = Base64.getUrlDecoder().decode(s.substring(Signing.PUBLIC.length()));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("not an Ed25519 key: " + s);
			}
			// One spelling only: 32 bytes, unpadded, exactly as written back.
			if (raw.length != 32 || !s.equals(Signing.PUBLIC + Base64.getUrlEncoder().withoutPadding().encodeToString(raw))) {
				throw new IllegalArgumentException("not an Ed25519 key in its usual spelling: " + s);
			}
		}
	}
}
