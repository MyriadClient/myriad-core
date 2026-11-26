package dev.myriadclient.impl.update;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.jetbrains.annotations.Nullable;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * A developer's public signing key, as an addon lists it: {@code "custom": {"myriad": {"keys": ["ed25519:<key>"]}}} in its
 * {@code fabric.mod.json}, the key's 32 bytes in base64url. The jar that's installed names the keys its updates must be
 * signed with. Only Ed25519 is known; a key of any other kind is ignored, never trusted.
 *
 * @param raw the key's 32 bytes
 */
record DevKey(byte[] raw) {
	static final String PREFIX = "ed25519:";
	/** At most this many keys are read from one jar or feed. */
	static final int MAX_KEYS = 8;
	/** ASN.1 for an Ed25519 SubjectPublicKeyInfo, before the key's 32 bytes. */
	private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

	DevKey {
		if (raw.length != 32) throw new IllegalArgumentException("an Ed25519 key is 32 bytes");
		raw = raw.clone();
	}

	@Override
	public byte[] raw() {
		return raw.clone();
	}

	/** The key from {@code ed25519:<base64url>}, or null when it isn't one. */
	static @Nullable DevKey parse(@Nullable String text) {
		if (text == null || !text.startsWith(PREFIX)) return null;
		try {
			byte[] raw = Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
			return raw.length == 32 ? new DevKey(raw) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The keys {@code meta} lists ({@code custom.myriad.keys}); empty when it lists none. */
	static List<DevKey> of(ModMetadata meta) {
		List<DevKey> out = new ArrayList<>();
		CustomValue myriad = meta.getCustomValue("myriad");
		if (myriad == null || myriad.getType() != CustomValue.CvType.OBJECT) return out;
		CustomValue keys = myriad.getAsObject().get("keys");
		if (keys == null || keys.getType() != CustomValue.CvType.ARRAY) return out;
		for (CustomValue v : keys.getAsArray()) {
			if (out.size() >= MAX_KEYS) break;
			DevKey k = v.getType() == CustomValue.CvType.STRING ? parse(v.getAsString()) : null;
			if (k != null && !out.contains(k)) out.add(k);
		}
		return out;
	}

	/** {@code ed25519:<base64url>}, as it's written in a {@code fabric.mod.json}. */
	String text() {
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
	}

	/** The first 8 bytes of the key's SHA-256 in hex: how a signature names its key, and how it's shown. */
	String id() {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw), 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The key as the JDK takes it. */
	PublicKey publicKey() throws GeneralSecurityException {
		byte[] der = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + 32);
		System.arraycopy(raw, 0, der, X509_PREFIX.length, 32);
		return KeyFactory.getInstance("Ed25519", SignatureCheck.PROVIDER).generatePublic(new X509EncodedKeySpec(der));
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof DevKey k && Arrays.equals(raw, k.raw);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(raw);
	}

	@Override
	public String toString() {
		return "DevKey[" + id() + "]";
	}
}
