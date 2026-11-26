package dev.myriadclient.impl.update;

import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The whole check a download goes through before it's staged, against the attacks it's there to stop. */
class UpdateVerifyTest {
	private static final Version MC = v("26.3"), CORE = v("2.7.0");

	private static Version v(String s) {
		try {
			return Version.parse(s);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] jar(String id, String version, List<DevKey> keys) throws IOException {
		String k = String.join(",", keys.stream().map(x -> "\"" + x.text() + "\"").toList());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"id\": \"" + id + "\", \"version\": \"" + version + "\", \"custom\": {\"myriad\": {\"keys\": [" + k + "]}}}").getBytes(StandardCharsets.UTF_8));
		}
		return out.toByteArray();
	}

	private static String sha(byte[] b) {
		return HexFormat.of().formatHex(JarCheck.sha256(b));
	}

	private static SignatureCheck.Sig sig(TestKeys key, String id, String version, byte[] jar) {
		return key.sign(ReleaseStatement.of(id, version, jar, JarCheck.sha256(jar)).bytes());
	}

	private static UpdateManager.Verified verify(String id, String version, byte[] jar, String declaredSha, List<SignatureCheck.Sig> sigs, List<DevKey> trusted, boolean core) {
		return UpdateManager.verify(id, version, new UpdateManager.Downloaded(jar, jar.length, declaredSha, sigs), trusted, core, Set.of("myriad", "other"), MC, core ? null : CORE);
	}

	@Test
	void unsignedAddonsStillUpdate() throws IOException {
		byte[] jar = jar("addon", "1.1.0", List.of());
		UpdateManager.Verified v = verify("addon", "1.1.0", jar, sha(jar), List.of(), List.of(), false);
		assertNull(v.problem());
		assertNull(v.signer());
	}

	@Test
	void aSignedUpdateFromTheTrustedKeyPasses() throws IOException {
		byte[] jar = jar("addon", "1.1.0", List.of(TestKeys.A.pub()));
		UpdateManager.Verified v = verify("addon", "1.1.0", jar, sha(jar), List.of(sig(TestKeys.A, "addon", "1.1.0", jar)), List.of(TestKeys.A.pub()), false);
		assertNull(v.problem());
		assertEquals(TestKeys.A.pub(), v.signer());
	}

	@Test
	void anAddonThatListsKeysRefusesAnythingElse() throws IOException {
		List<DevKey> trusted = List.of(TestKeys.A.pub());
		byte[] jar = jar("addon", "1.1.0", trusted);
		assertEquals("it isn't signed, and this addon only takes signed updates", verify("addon", "1.1.0", jar, sha(jar), List.of(), trusted, false).problem());
		assertEquals("it isn't signed with its developer's key",
			verify("addon", "1.1.0", jar, sha(jar), List.of(sig(TestKeys.B, "addon", "1.1.0", jar)), trusted, false).problem());
		// A real signature, but for an older version or another addon of the same developer.
		assertEquals("it isn't signed with its developer's key",
			verify("addon", "1.1.0", jar, sha(jar), List.of(sig(TestKeys.A, "addon", "1.0.0", jar)), trusted, false).problem());
		assertEquals("it isn't signed with its developer's key",
			verify("addon", "1.1.0", jar, sha(jar), List.of(sig(TestKeys.A, "other-addon", "1.1.0", jar)), trusted, false).problem());
	}

	@Test
	void aTamperedJarFails() throws IOException {
		List<DevKey> trusted = List.of(TestKeys.A.pub());
		byte[] jar = jar("addon", "1.1.0", trusted);
		SignatureCheck.Sig good = sig(TestKeys.A, "addon", "1.1.0", jar);
		byte[] evil = jar.clone();
		evil[evil.length / 2] ^= 1;
		assertEquals("the download doesn't match its SHA-256", verify("addon", "1.1.0", evil, sha(jar), List.of(good), trusted, false).problem());
		// Where the source gives no hash, the signature alone catches it.
		assertEquals("it isn't signed with its developer's key", verify("addon", "1.1.0", evil, null, List.of(good), trusted, false).problem());
	}

	@Test
	void rotationAndOptingOut() throws IOException {
		// Signed by the old key, listing only the new one: the next update must be signed by the new key.
		byte[] rotated = jar("addon", "1.1.0", List.of(TestKeys.B.pub()));
		assertNull(verify("addon", "1.1.0", rotated, sha(rotated), List.of(sig(TestKeys.A, "addon", "1.1.0", rotated)), List.of(TestKeys.A.pub()), false).problem());
		// Signed by the trusted key and listing none: its developer stopped signing.
		byte[] none = jar("addon", "1.2.0", List.of());
		assertNull(verify("addon", "1.2.0", none, sha(none), List.of(sig(TestKeys.A, "addon", "1.2.0", none)), List.of(TestKeys.A.pub()), false).problem());
	}

	@Test
	void coreNeedsItsRootKeysAndKeepsThem() throws IOException {
		List<DevKey> root = List.of(TestKeys.A.pub());
		byte[] good = jar("myriad", "2.8.0", root);
		assertNull(verify("myriad", "2.8.0", good, sha(good), List.of(sig(TestKeys.A, "myriad", "2.8.0", good)), root, true).problem());
		assertEquals("it isn't signed with Myriad's keys", verify("myriad", "2.8.0", good, sha(good), List.of(sig(TestKeys.B, "myriad", "2.8.0", good)), root, true).problem());
		byte[] dropped = jar("myriad", "2.8.0", List.of());
		assertEquals("it drops Myriad's signing keys", verify("myriad", "2.8.0", dropped, sha(dropped), List.of(sig(TestKeys.A, "myriad", "2.8.0", dropped)), root, true).problem());
	}
}
