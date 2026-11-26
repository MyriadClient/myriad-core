package dev.myriadclient.gradle.signing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Library's chain of trust for the tools (the builder and the maintainer's review tool), checked the way Myriad's
 * core checks it: Myriad's root keys certify Library keys, an index key signs the index, a listing key signs listings.
 * Nothing the API says is taken on its word; everything is checked back to the root keys built in here.
 */
public final class LibraryTrust {
	/** Myriad's root keys, the same two core trusts. */
	public static final List<String> ROOT_KEYS = List.of(
		"ed25519:nCz3FbS30fsLg-MkX2de8AOrACc3xUYYo_RYAwpvRww",
		"ed25519:50gMky_GrJ-mjBAlIKuWDURAFNzJYrBXzOUEuFG5RcI");
	private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
	private static final BigInteger L = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));

	private LibraryTrust() {
	}

	/** Whether {@code sig} (base64url) is {@code key}'s Ed25519 signature on {@code message}; malleated copies refused. */
	public static boolean verify(String key, byte[] message, String sig) {
		try {
			if (!key.startsWith(Signing.PUBLIC) || sig.length() > 100) return false;
			byte[] raw = Base64.getUrlDecoder().decode(key.substring(Signing.PUBLIC.length()));
			byte[] s = Base64.getUrlDecoder().decode(sig);
			if (raw.length != 32 || s.length != 64) return false;
			byte[] be = new byte[32];
			for (int i = 0; i < 32; i++) be[i] = s[63 - i];
			if (new BigInteger(1, be).compareTo(L) >= 0) return false;
			byte[] der = Arrays.copyOf(X509_PREFIX, 44);
			System.arraycopy(raw, 0, der, 12, 32);
			Signature v = Signature.getInstance("Ed25519", "SunEC");
			v.initVerify(KeyFactory.getInstance("Ed25519", "SunEC").generatePublic(new X509EncodedKeySpec(der)));
			v.update(message);
			return v.verify(s);
		} catch (Exception e) {
			return false;
		}
	}

	/** The key among {@code keys} that made one of {@code sigs} (each {@code {"key", "sig"}}) over {@code message}, or null. */
	public static String signer(byte[] message, Object sigs, Collection<String> keys) {
		if (!(sigs instanceof List<?> list)) return null;
		for (Object o : list.subList(0, Math.min(8, list.size()))) {
			if (!(o instanceof Map<?, ?> m) || !(m.get("sig") instanceof String sig)) continue;
			for (String k : keys) if (verify(k, message, sig)) return k;
		}
		return null;
	}

	/**
	 * The Library keys in force in an index body, by role ({@code listing}, {@code version}, {@code index}): certified by
	 * {@code roots}, not revoked, and (index keys) not expired at {@code now} (seconds).
	 */
	public static Map<String, Set<String>> certifiedKeys(Map<String, Object> body, List<String> roots, long now) {
		Set<String> revoked = new HashSet<>();
		if (body.get("revocations") instanceof Map<?, ?> r) {
			try {
				Map<String, List<String>> fields = new HashMap<>();
				fields.put("seq", List.of(String.valueOf(Json.number(r.get("seq"), "seq"))));
				List<String> keys = new ArrayList<>();
				for (Object k : Json.array(r.get("keys"), "keys")) keys.add(Json.string(k, "key"));
				fields.put("key", keys);
				if (signer(LibraryStatements.statement("revocations", fields), r.get("signatures"), roots) != null) revoked.addAll(keys);
				else throw new IllegalArgumentException("the index's revocations aren't signed by Myriad");
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("the index's revocations don't check out: " + e.getMessage());
			}
		}
		Map<String, Set<String>> out = new HashMap<>();
		for (String role : List.of("listing", "version", "index")) out.put(role, new LinkedHashSet<>());
		for (Object c : body.get("certs") instanceof List<?> l ? l : List.of()) {
			try {
				Map<String, Object> cert = Json.object(c, "a certificate");
				String key = Json.string(cert.get("key"), "key"), role = Json.string(cert.get("role"), "role");
				Map<String, List<String>> fields = new HashMap<>();
				fields.put("key", List.of(key));
				fields.put("role", List.of(role));
				if (role.equals("index")) {
					long notAfter = Json.number(cert.get("notAfter"), "notAfter");
					if (now >= notAfter) continue;
					fields.put("not-after", List.of(String.valueOf(notAfter)));
				}
				if (revoked.contains(key) || !out.containsKey(role)) continue;
				if (signer(LibraryStatements.statement("key", fields), cert.get("signatures"), roots) != null) out.get(role).add(key);
			} catch (RuntimeException ignored) {
				// A certificate that doesn't check out certifies nothing.
			}
		}
		return out;
	}

	/** The body of a Library index ({@code /v1/index.json}) after checking its snapshot signature back to {@code roots}. */
	public static Map<String, Object> verifiedBody(String indexText, List<String> roots, long now) {
		Map<String, Object> wrapper = Json.object(Json.parse(indexText), "the index");
		String body = Json.string(wrapper.get("body"), "the index body");
		Map<String, Object> b = Json.object(Json.parse(body), "the index body");
		Map<String, Set<String>> keys = certifiedKeys(b, roots, now);
		long issued = Json.number(b.get("issued"), "issued"), expires = Json.number(b.get("expires"), "expires");
		byte[] snapshot = LibraryStatements.statement("snapshot", Map.of("issued", List.of(String.valueOf(issued)), "expires", List.of(String.valueOf(expires)),
			"body-sha256", List.of(Signing.hex(Signing.sha256(body.getBytes(StandardCharsets.UTF_8))))));
		if (signer(snapshot, wrapper.get("signatures"), keys.get("index")) == null) throw new IllegalArgumentException("the index isn't signed by a current Myriad index key");
		if (now >= expires) throw new IllegalArgumentException("the index has expired");
		return b;
	}

	/** The listing statement for a listing as the API and index carry it ({@code id, revision, repoId, ...}). */
	public static byte[] listingStatement(Map<String, Object> l) {
		Map<String, List<String>> f = new HashMap<>();
		f.put("id", List.of(Json.string(l.get("id"), "id")));
		f.put("revision", List.of(String.valueOf(Json.number(l.get("revision"), "revision"))));
		f.put("repo-id", List.of(String.valueOf(Json.number(l.get("repoId"), "repoId"))));
		f.put("owner-id", List.of(String.valueOf(Json.number(l.get("ownerId"), "ownerId"))));
		String source = Json.string(l.get("source"), "source");
		f.put("source", List.of(source));
		if (source.equals("closed")) f.put("download-repo-id", List.of(String.valueOf(Json.number(l.get("downloadRepoId"), "downloadRepoId"))));
		f.put("build", List.of(Json.string(l.get("build"), "build")));
		List<String> provides = new ArrayList<>();
		for (Object p : Json.array(l.get("provides"), "provides")) provides.add(Json.string(p, "provides"));
		f.put("provides", List.of(String.join(",", provides)));
		List<String> keys = new ArrayList<>();
		for (Object k : Json.array(l.get("keys"), "keys")) keys.add(Json.string(k, "key"));
		f.put("key", keys);
		return LibraryStatements.statement("listing", f);
	}

	/** Whether {@code listing} is signed by one of the listing keys in force in {@code body}. */
	public static boolean listingSigned(Map<String, Object> listing, Map<String, Object> body, List<String> roots, long now) {
		return signer(listingStatement(listing), listing.get("signatures"), certifiedKeys(body, roots, now).get("listing")) != null;
	}
}
