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

	private static byte[] mod(String id, String version, String depends) throws IOException {
		return jar("{\"schemaVersion\": 1, \"id\": \"" + id + "\", \"version\": \"" + version + "\", \"depends\": {" + depends + "}}");
	}

	@Test
	void acceptsTheModItAskedFor() throws IOException {
		assertNull(JarCheck.check(mod("addon", "1.1.0", "\"minecraft\": \"~1.21.11\", \"myriad\": \">=0.1.0\""), "addon", "1.1.0", MC, CORE));
		assertNull(JarCheck.check(mod("addon", "1.1.0", ""), "addon", "1.1.0", MC, CORE));
	}

	@Test
	void refusesAnotherModOrVersion() throws IOException {
		assertEquals("the download is other, not addon", JarCheck.check(mod("other", "1.1.0", ""), "addon", "1.1.0", MC, CORE));
		assertEquals("the download is version 1.0.0, not 1.1.0", JarCheck.check(mod("addon", "1.0.0", ""), "addon", "1.1.0", MC, CORE));
		assertEquals("the download isn't a Fabric mod", JarCheck.check(jar(null), "addon", "1.1.0", MC, CORE));
		// An error page instead of a jar has no zip entries at all.
		assertEquals("the download isn't a Fabric mod", JarCheck.check("<html>".getBytes(StandardCharsets.UTF_8), "addon", "1.1.0", MC, CORE));
	}

	@Test
	void refusesAJarForAnotherMinecraftOrCore() throws IOException {
		assertEquals("2.0.0 needs Minecraft ~1.22", JarCheck.check(mod("addon", "2.0.0", "\"minecraft\": \"~1.22\""), "addon", "2.0.0", MC, CORE));
		assertEquals("2.0.0 needs Myriad >=0.2.0", JarCheck.check(mod("addon", "2.0.0", "\"myriad\": \">=0.2.0\""), "addon", "2.0.0", MC, CORE));
		// Updating core itself skips the core check.
		assertNull(JarCheck.check(mod("myriad", "0.2.0", "\"myriad\": \">=0.2.0\""), "myriad", "0.2.0", MC, null));
	}

	@Test
	void aListOfRangesNeedsOneToMatch() throws IOException {
		assertNull(JarCheck.check(mod("addon", "2.0.0", "\"minecraft\": [\"1.20.x\", \"1.21.x\"]"), "addon", "2.0.0", MC, CORE));
		assertEquals("2.0.0 needs Minecraft 1.19.x or 1.20.x",
			JarCheck.check(mod("addon", "2.0.0", "\"minecraft\": [\"1.19.x\", \"1.20.x\"]"), "addon", "2.0.0", MC, CORE));
	}

	@Test
	void checksTheDownloadAgainstWhatGitHubListed() throws Exception {
		byte[] data = mod("addon", "1.0.0", "");
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		assertNull(JarCheck.verifyDownload(data, new GitHubReleases.Asset("a.jar", "u", data.length, sha)));
		assertNull(JarCheck.verifyDownload(data, new GitHubReleases.Asset("a.jar", "u", data.length, null)));
		assertEquals("the download was " + data.length + " bytes, expected 3", JarCheck.verifyDownload(data, new GitHubReleases.Asset("a.jar", "u", 3, null)));
		assertEquals("the download doesn't match its SHA-256", JarCheck.verifyDownload(data, new GitHubReleases.Asset("a.jar", "u", data.length, "00")));
	}
}
