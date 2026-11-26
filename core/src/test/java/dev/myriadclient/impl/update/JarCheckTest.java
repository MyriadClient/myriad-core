package dev.myriadclient.impl.update;

import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

class JarCheckTest {
	private static final Version MC = v("1.21.11");
	private static final Version CORE = v("0.1.1");

	private static Version v(String s) {
		try {
			return Version.parse(s);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] jar(String modJson) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("dev/Thing.class"));
			zip.write(new byte[] {1, 2, 3});
			if (modJson != null) {
				zip.putNextEntry(new ZipEntry("fabric.mod.json"));
				zip.write(modJson.getBytes(StandardCharsets.UTF_8));
			}
		}
		return out.toByteArray();
	}

	private static String problem(byte[] jar, String id, String version, Version mc, Version core) {
		return JarCheck.check(jar, id, version, mc, core, Set.of()).problem();
	}

	private static byte[] mod(String id, String version, String depends) throws IOException {
		return jar("{\"schemaVersion\": 1, \"id\": \"" + id + "\", \"version\": \"" + version + "\", \"depends\": {" + depends + "}}");
	}

	@Test
	void acceptsTheModItAskedFor() throws IOException {
		assertNull(problem(mod("addon", "1.1.0", "\"minecraft\": \"~1.21.11\", \"myriad\": \">=0.1.0\""), "addon", "1.1.0", MC, CORE));
		assertNull(problem(mod("addon", "1.1.0", ""), "addon", "1.1.0", MC, CORE));
	}

	@Test
	void refusesAnotherModOrVersion() throws IOException {
		assertEquals("the download is other, not addon", problem(mod("other", "1.1.0", ""), "addon", "1.1.0", MC, CORE));
		assertEquals("the download is version 1.0.0, not 1.1.0", problem(mod("addon", "1.0.0", ""), "addon", "1.1.0", MC, CORE));
		assertEquals("the download isn't a Fabric mod", problem(jar(null), "addon", "1.1.0", MC, CORE));
		// An error page instead of a jar isn't a zip at all.
		assertEquals("the download isn't a jar Myriad can vouch for (not a jar)", problem("<html>".getBytes(StandardCharsets.UTF_8), "addon", "1.1.0", MC, CORE));
	}

	@Test
	void refusesAJarForAnotherMinecraftOrCore() throws IOException {
		assertEquals("2.0.0 needs Minecraft ~1.22", problem(mod("addon", "2.0.0", "\"minecraft\": \"~1.22\""), "addon", "2.0.0", MC, CORE));
		assertEquals("2.0.0 needs Myriad >=0.2.0", problem(mod("addon", "2.0.0", "\"myriad\": \">=0.2.0\""), "addon", "2.0.0", MC, CORE));
		// Updating core itself skips the core check.
		assertNull(problem(mod("myriad", "0.2.0", "\"myriad\": \">=0.2.0\""), "myriad", "0.2.0", MC, null));
	}

	@Test
	void aListOfRangesNeedsOneToMatch() throws IOException {
		assertNull(problem(mod("addon", "2.0.0", "\"minecraft\": [\"1.20.x\", \"1.21.x\"]"), "addon", "2.0.0", MC, CORE));
		assertEquals("2.0.0 needs Minecraft 1.19.x or 1.20.x",
			problem(mod("addon", "2.0.0", "\"minecraft\": [\"1.19.x\", \"1.20.x\"]"), "addon", "2.0.0", MC, CORE));
	}

	@Test
	void checksTheDownloadAgainstWhatGitHubListed() throws Exception {
		byte[] data = mod("addon", "1.0.0", "");
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		assertNull(JarCheck.verifyDownload(data, data.length, sha));
		assertNull(JarCheck.verifyDownload(data, data.length, null));
		assertEquals("the download was " + data.length + " bytes, expected 3", JarCheck.verifyDownload(data, 3, null));
		assertEquals("the download doesn't match its SHA-256", JarCheck.verifyDownload(data, data.length, "00"));
	}

	@Test
	void readsTheKeysAJarLists() throws IOException {
		String key = TestKeys.A.pub().text();
		byte[] jar = jar("{\"id\": \"addon\", \"version\": \"1.0.0\", \"custom\": {\"myriad\": {\"keys\": [\"" + key + "\", \"rsa:abc\"]}}}");
		assertEquals(java.util.List.of(TestKeys.A.pub()), JarCheck.check(jar, "addon", "1.0.0", MC, CORE, Set.of()).keys());
	}

	@Test
	void refusesAJarThatBringsInAnotherAddon() throws IOException {
		byte[] provides = jar("{\"id\": \"addon\", \"version\": \"1.0.0\", \"provides\": [\"myriad-essentials\"]}");
		assertEquals("the download also brings in myriad-essentials, which it mustn't",
			JarCheck.check(provides, "addon", "1.0.0", MC, CORE, Set.of("myriad", "myriad-essentials")).problem());
		// Its own id among the others (updating itself) is fine.
		assertNull(JarCheck.check(mod("addon", "1.0.0", ""), "addon", "1.0.0", MC, CORE, Set.of("addon", "myriad")).problem());

		byte[] inner = jar("{\"id\": \"myriad\", \"version\": \"99.0.0\"}");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write("{\"id\": \"addon\", \"version\": \"1.0.0\", \"jars\": [{\"file\": \"META-INF/jars/lib.jar\"}]}".getBytes(StandardCharsets.UTF_8));
			zip.putNextEntry(new ZipEntry("META-INF/jars/lib.jar"));
			zip.write(inner);
		}
		assertEquals("the download also brings in myriad, which it mustn't", JarCheck.check(out.toByteArray(), "addon", "1.0.0", MC, CORE, Set.of("myriad")).problem());
		assertTrue(JarCheck.check(out.toByteArray(), "addon", "1.0.0", MC, CORE, Set.of("other")).problem() == null);
	}
}
