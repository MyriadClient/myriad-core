package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureCheckTest {
	private static final byte[] JAR = "a jar".getBytes(StandardCharsets.UTF_8);

	private static ReleaseStatement statement(String id, String version) {
		return ReleaseStatement.of(id, version, JAR, JarCheck.sha256(JAR));
	}

	@Test
	void rfc8032KnownAnswer() {
		byte[] sig = HexFormat.of().parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
		assertTrue(SignatureCheck.verify(new byte[0], sig, TestKeys.RFC.pub()));
		assertArrayEquals(sig, TestKeys.RFC.signRaw(new byte[0]));
		assertFalse(SignatureCheck.verify(new byte[] {0}, sig, TestKeys.RFC.pub()));
	}

	@Test
	void theStatementHasOneSpelling() {
		assertEquals("myriad-addon-release:1\nid=my-addon\nversion=1.2.0+mc26.3\nsize=5\nsha256=" + HexFormat.of().formatHex(JarCheck.sha256(JAR)) + "\n",
			new String(statement("my-addon", "1.2.0+mc26.3").bytes(), StandardCharsets.US_ASCII));
	}

	@Test
	void theStatementRefusesInjection() {
		assertThrows(IllegalArgumentException.class, () -> statement("my-addon", "1.0\nid=other"));
		assertThrows(IllegalArgumentException.class, () -> statement("my-addon\n", "1.0"));
		assertThrows(IllegalArgumentException.class, () -> statement("My-Addon", "1.0"));
		assertThrows(IllegalArgumentException.class, () -> statement("my-addon", "1.0 "));
		assertThrows(IllegalArgumentException.class, () -> new ReleaseStatement("my-addon", "1.0", 5, "ABC"));
	}

	@Test
	void aTrustedKeysSignaturePasses() {
		byte[] m = statement("my-addon", "1.2.0").bytes();
		assertEquals(TestKeys.A.pub(), SignatureCheck.signer(m, List.of(TestKeys.A.sign(m)), List.of(TestKeys.A.pub())));
		// The key id a signature names is only a label: it's checked against every trusted key.
		var mislabelled = new SignatureCheck.Sig("0000000000000000", TestKeys.A.sign(m).sig());
		assertEquals(TestKeys.A.pub(), SignatureCheck.signer(m, List.of(mislabelled), List.of(TestKeys.B.pub(), TestKeys.A.pub())));
	}

	@Test
	void anUntrustedKeyOrAnotherStatementFails() {
		byte[] m = statement("my-addon", "1.2.0").bytes();
		assertNull(SignatureCheck.signer(m, List.of(TestKeys.B.sign(m)), List.of(TestKeys.A.pub())));
		// Signed for another addon with the same key, or for another version.
		assertNull(SignatureCheck.signer(m, List.of(TestKeys.A.sign(statement("other-addon", "1.2.0").bytes())), List.of(TestKeys.A.pub())));
		assertNull(SignatureCheck.signer(m, List.of(TestKeys.A.sign(statement("my-addon", "1.1.0").bytes())), List.of(TestKeys.A.pub())));
		assertNull(SignatureCheck.signer(m, List.of(), List.of(TestKeys.A.pub())));
		assertNull(SignatureCheck.signer(m, List.of(new SignatureCheck.Sig("x", "!!not base64!!")), List.of(TestKeys.A.pub())));
	}

	@Test
	void aMalleatedSignatureFails() {
		byte[] m = statement("my-addon", "1.2.0").bytes();
		byte[] sig = TestKeys.A.signRaw(m);
		BigInteger l = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
		byte[] be = new byte[32];
		for (int i = 0; i < 32; i++) be[i] = sig[63 - i];
		byte[] s = new BigInteger(1, be).add(l).toByteArray();
		byte[] mal = sig.clone();
		for (int i = 0; i < 32; i++) mal[32 + i] = i < s.length ? s[s.length - 1 - i] : 0;
		assertTrue(SignatureCheck.verify(m, sig, TestKeys.A.pub()));
		assertFalse(SignatureCheck.verify(m, mal, TestKeys.A.pub()));
		byte[] flipped = sig.clone();
		flipped[5] ^= 1;
		assertFalse(SignatureCheck.verify(m, flipped, TestKeys.A.pub()));
		assertFalse(SignatureCheck.verify(m, java.util.Arrays.copyOf(sig, 63), TestKeys.A.pub()));
	}

	@Test
	void keysParseStrictly() {
		DevKey k = TestKeys.A.pub();
		assertEquals(k, DevKey.parse(k.text()));
		assertNull(DevKey.parse("rsa:" + k.text().substring(8)));
		assertNull(DevKey.parse("ed25519:" + Base64.getUrlEncoder().encodeToString(new byte[31])));
		assertNull(DevKey.parse("ed25519:***"));
		assertNull(DevKey.parse(null));
		assertEquals(16, k.id().length());
	}

	/** The value the Gradle plugin's SigningTest makes: what the plugin signs, core accepts. */
	@Test
	void acceptsWhatTheGradlePluginSigns() {
		byte[] m = statement("my-addon", "1.2.0").bytes();
		var sig = new SignatureCheck.Sig("39f713d0a644253f", "EaZXmXVlg7Bs47RvHelTWPO1UR-YElFzFfNpBXJ-o6m32PB8DtvPowRFnhSe8lplUMDSx1z-kM7GnRB2QtoBDQ");
		assertEquals(TestKeys.A.pub(), SignatureCheck.signer(m, List.of(sig), List.of(TestKeys.A.pub())));
		assertEquals("39f713d0a644253f", TestKeys.A.pub().id());
		assertEquals("ed25519:PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw", TestKeys.A.pub().text());
	}
}
