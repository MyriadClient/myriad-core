package dev.myriadclient.impl.update;

import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/**
 * Checks Ed25519 signatures against trusted keys. Each signature is tried with every trusted key (the key id it names is
 * only a label, never trusted), only the JDK's own provider is used (a provider another mod registers can't stand in),
 * and a signature whose S isn't reduced (a malleated copy of a valid one) is refused before the provider sees it.
 */
final class SignatureCheck {
	static final String PROVIDER = "SunEC";
	/** At most this many signatures are looked at on one release. */
	static final int MAX_SIGNATURES = 8;
	/** The order of Ed25519's base point. */
	private static final BigInteger L = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));

	/** A signature as a feed carries it: the signing key's id (a label) and the 64 signature bytes in base64url. */
	record Sig(String key, String sig) {
	}

	private SignatureCheck() {
	}

	/** The trusted key that signed {@code message}, or null when none of {@code signatures} is a valid one by them. */
	static @Nullable DevKey signer(byte[] message, List<Sig> signatures, List<DevKey> trusted) {
		int n = 0;
		for (Sig s : signatures) {
			if (++n > MAX_SIGNATURES) break;
			byte[] sig = decode(s.sig());
			if (sig == null) continue;
			for (DevKey k : trusted) if (verify(message, sig, k)) return k;
		}
		return null;
	}

	/**
	 * Like {@link #signer}, but each signature is checked only against the key it names. For the Myriad Library's index,
	 * which carries thousands of signatures: checking each against every trusted key would let a hostile index take
	 * minutes to read. Everything the Library carries is labelled by the tools that made it.
	 */
	static @Nullable DevKey signerByLabel(byte[] message, List<Sig> signatures, List<DevKey> trusted) {
		int n = 0;
		for (Sig s : signatures) {
			if (++n > MAX_SIGNATURES) break;
			byte[] sig = decode(s.sig());
			if (sig == null) continue;
			for (DevKey k : trusted) if (k.id().equals(s.key()) && verify(message, sig, k)) return k;
		}
		return null;
	}

	static boolean verify(byte[] message, byte[] sig, DevKey key) {
		if (sig.length != 64 || !reduced(sig)) return false;
		try {
			Signature v = Signature.getInstance("Ed25519", PROVIDER);
			v.initVerify(key.publicKey());
			v.update(message);
			return v.verify(sig);
		} catch (GeneralSecurityException | RuntimeException e) {
			return false;
		}
	}

	/** Whether the signature's S (its last 32 bytes, little-endian) is below L. */
	private static boolean reduced(byte[] sig) {
		byte[] be = new byte[32];
		for (int i = 0; i < 32; i++) be[i] = sig[63 - i];
		return new BigInteger(1, be).compareTo(L) < 0;
	}

	private static byte @Nullable [] decode(@Nullable String text) {
		if (text == null || text.length() > 100) return null;
		try {
			return Base64.getUrlDecoder().decode(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
