package dev.myriadclient.gradle.signing;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Invites to an invite-only Library listing, as the developer's tool makes them: the code (made here, never sent: the API
 * gets only its hash) and the signed request ({@code myriad-library-invite:1}, spelled exactly as Myriad's API reads it).
 */
public final class Invites {
	/** Base32 without padding (RFC 4648): no 0, 1, 8 or 9, so codes read aloud without mixing up O and 0. */
	static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
	private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{22,64}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern UUID = Pattern.compile("[0-9a-f]{32}");
	private static final Pattern LABEL = Pattern.compile("[ -<>-~]{0,40}");
	private static final Pattern CANONICAL = Pattern.compile("[A-Z2-7]{27}");
	private static final SecureRandom RANDOM = new SecureRandom();

	private Invites() {
	}

	/** A new code, canonical: 26 random base32 letters (130 bits) and a checksum letter that catches most typos. */
	public static String newCode() {
		StringBuilder sb = new StringBuilder(27);
		for (int i = 0; i < 26; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(32)));
		return sb.append(checksum(sb)).toString();
	}

	/** The checksum letter of 26 code letters: the first 5 bits of their SHA-256, so 31 typos in 32 are caught. */
	static char checksum(CharSequence letters) {
		byte[] h = Signing.sha256(letters.subSequence(0, 26).toString().getBytes(StandardCharsets.US_ASCII));
		return ALPHABET.charAt((h[0] & 0xff) >>> 3);
	}

	/** A code as people type it ({@code myr-ABCDE-…}, any case, any dashes and spaces), canonical, or null. */
	public static String canonical(String typed) {
		if (typed == null || typed.length() > 64) return null;
		String c = typed.strip().toUpperCase(Locale.ROOT).replaceFirst("^MYR-?", "").replaceAll("[\\s-]", "");
		return CANONICAL.matcher(c).matches() ? c : null;
	}

	/** Whether a canonical code's last letter is its checksum (a typed code that fails this has a typo). */
	public static boolean checks(String canonical) {
		return canonical != null && CANONICAL.matcher(canonical).matches() && checksum(canonical) == canonical.charAt(26);
	}

	/** How a code is shown and shared: {@code myr-ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-Z2}. */
	public static String shown(String canonical) {
		StringBuilder sb = new StringBuilder("myr");
		for (int i = 0; i < canonical.length(); i += 5) sb.append('-').append(canonical, i, Math.min(canonical.length(), i + 5));
		return sb.toString();
	}

	/** The code's hash, the only thing the API keeps. */
	public static String hash(String canonical) {
		return Signing.hex(Signing.sha256(canonical.getBytes(StandardCharsets.US_ASCII)));
	}

	/** A request's fields (only those its action uses are read). */
	public record Request(String id, String action, String api, long issued, String nonce, String codeSha256, long uses, long notAfter, String label,
						  long invite, String uuid) {
	}

	/**
	 * The signed bytes: the kind, {@code id}, {@code action}, {@code api} (the origin of the API it's for, so it never counts
	 * at another), {@code issued}, {@code nonce}, then what the action needs, in this order: {@code code-sha256 uses
	 * not-after label} (create), {@code invite} (revoke), {@code uuid} (revoke-grant, restore-grant).
	 */
	public static byte[] bytes(Request r) {
		need(ID.matcher(r.id()).matches(), "not a mod id");
		need(java.util.List.of("create", "list", "revoke", "grants", "revoke-grant", "restore-grant").contains(r.action()), "not an invite action");
		need(r.api() != null && r.api().matches("https?://[a-z0-9.-]{1,253}(:\\d{1,5})?"), "not an API origin");
		need(r.issued() > 0, "issued isn't a time");
		need(NONCE.matcher(r.nonce()).matches(), "not a nonce");
		StringBuilder s = new StringBuilder("myriad-library-invite:1\nid=").append(r.id()).append("\naction=").append(r.action())
			.append("\napi=").append(r.api()).append("\nissued=").append(r.issued()).append("\nnonce=").append(r.nonce()).append('\n');
		switch (r.action()) {
			case "create" -> {
				need(r.codeSha256() != null && SHA256.matcher(r.codeSha256()).matches(), "not a code hash");
				need(r.uses() > 0 && r.uses() <= 10_000, "uses is 1 to 10000");
				need(r.notAfter() > 0, "not-after isn't a time");
				String label = r.label() == null ? "" : r.label();
				need(LABEL.matcher(label).matches(), "a label is up to 40 plain characters, no =");
				s.append("code-sha256=").append(r.codeSha256()).append("\nuses=").append(r.uses()).append("\nnot-after=").append(r.notAfter())
					.append("\nlabel=").append(label).append('\n');
			}
			case "revoke" -> {
				need(r.invite() > 0, "which invite?");
				s.append("invite=").append(r.invite()).append('\n');
			}
			case "revoke-grant", "restore-grant" -> {
				need(r.uuid() != null && UUID.matcher(r.uuid()).matches(), "not a player's UUID");
				s.append("uuid=").append(r.uuid()).append('\n');
			}
			default -> {
			}
		}
		return s.toString().getBytes(StandardCharsets.US_ASCII);
	}

	/** A player's UUID with or without dashes, as the request spells it (32 lowercase hex), or null. */
	public static String uuid(String typed) {
		if (typed == null) return null;
		String u = typed.strip().toLowerCase(Locale.ROOT).replace("-", "");
		return UUID.matcher(u).matches() ? u : null;
	}

	/** A one-time nonce for a request. */
	public static String nonce() {
		byte[] b = new byte[24];
		RANDOM.nextBytes(b);
		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static void need(boolean ok, String what) {
		if (!ok) throw new IllegalArgumentException(what);
	}
}
