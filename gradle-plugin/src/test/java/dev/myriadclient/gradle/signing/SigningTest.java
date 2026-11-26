package dev.myriadclient.gradle.signing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigningTest {
	/** RFC 8032's second test key. */
	static final String SEED = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
	/**
	 * The signature for my-addon 1.2.0, a 5-byte jar "a jar". Core's SignatureCheckTest checks the same value, so the
	 * signer here and the checker there can't drift apart.
	 */
	static final String FIXTURE_SIG = "EaZXmXVlg7Bs47RvHelTWPO1UR-YElFzFfNpBXJ-o6m32PB8DtvPowRFnhSe8lplUMDSx1z-kM7GnRB2QtoBDQ";

	@Test
	void signsTheSharedFixture() throws Exception {
		Signing.Key k = Signing.fromSeed(HexFormat.of().parseHex(SEED));
		assertEquals("ed25519:PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw", k.publicText());
		byte[] jar = "a jar".getBytes(StandardCharsets.UTF_8);
		byte[] st = Signing.statement("my-addon", "1.2.0", jar.length, Signing.sha256(jar));
		assertEquals(FIXTURE_SIG, Base64.getUrlEncoder().withoutPadding().encodeToString(k.sign(st)));
		assertEquals(k.publicText(), Signing.parsePrivate(k.privateText()).publicText());
	}

	@Test
	void keyFilesNeedTheirPassphrase() throws Exception {
		Signing.Key k = Signing.generate();
		String file = Signing.encrypt(k, "correct horse battery".toCharArray());
		assertEquals(k.publicText(), Signing.decrypt(file, "correct horse battery".toCharArray()).publicText());
		assertThrows(GeneralSecurityException.class, () -> Signing.decrypt(file, "wrong".toCharArray()));
		// The public key is bound to the encrypted seed: swapping it in the file fails.
		String swapped = file.replace(k.publicText(), Signing.generate().publicText());
		assertThrows(GeneralSecurityException.class, () -> Signing.decrypt(swapped, "correct horse battery".toCharArray()));
		assertThrows(GeneralSecurityException.class, () -> Signing.decrypt(file.replace("600000", "1000"), "correct horse battery".toCharArray()));
	}

	@Test
	void refusesAKeyTheJarDoesntList(@TempDir Path dir) throws Exception {
		Signing.Key listed = Signing.generate(), other = Signing.generate();
		Path jar = dir.resolve("my-addon-1.2.0.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"id\": \"my-addon\", \"version\": \"1.2.0\", \"depends\": {\"minecraft\": \"~26.3\"}, \"custom\": {\"myriad\": {\"keys\": [\""
				+ listed.publicText() + "\"]}}}").getBytes(StandardCharsets.UTF_8));
		}
		String sig = Signing.sign(jar, listed, false);
		assertTrue(sig.contains("\"file\": \"my-addon-1.2.0.jar\"") && sig.contains("\"key\": \"" + listed.id() + "\"") && sig.contains("\"minecraft\": \"~26.3\""), sig);
		assertThrows(IOException.class, () -> Signing.sign(jar, other, false));
		Signing.sign(jar, other, true);
	}

	@Test
	void secretsAreNeverOverwritten(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("k.txt");
		Signing.writeSecret(f, "a");
		assertThrows(IOException.class, () -> Signing.writeSecret(f, "b"));
		assertEquals("a", Files.readString(f));
	}
}
