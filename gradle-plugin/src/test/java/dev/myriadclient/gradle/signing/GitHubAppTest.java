package dev.myriadclient.gradle.signing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitHubAppTest {
	private static KeyPair rsa() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	/** PKCS#1 out of Java's PKCS#8 encoding: the octet string after the version and algorithm. */
	private static byte[] pkcs1Of(byte[] pkcs8) {
		int i = 4 + 3 + 15; // SEQUENCE header (4 for a 2048-bit key), version, algorithm
		assertEquals(0x04, pkcs8[i] & 0xff);
		int len = ((pkcs8[i + 2] & 0xff) << 8) | (pkcs8[i + 3] & 0xff);
		return Arrays.copyOfRange(pkcs8, i + 4, i + 4 + len);
	}

	private static String pem(String kind, byte[] der) {
		return "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der) + "\n-----END " + kind + "-----\n";
	}

	@Test
	void readsGitHubsPkcs1KeysAndPkcs8AndSignsAJwtThatVerifies() throws Exception {
		KeyPair k = rsa();
		byte[] pkcs8 = k.getPrivate().getEncoded();
		assertArrayEquals(pkcs8, GitHubApp.pkcs8(pkcs1Of(pkcs8)), "wrapping PKCS#1 gives Java's own encoding back");
		for (String pem : List.of(pem("RSA PRIVATE KEY", pkcs1Of(pkcs8)), pem("PRIVATE KEY", pkcs8), pem("RSA PRIVATE KEY", pkcs1Of(pkcs8)).replace("\n", "\\n"))) {
			String jwt = new GitHubApp("123456", pem).jwt(1_800_000_000L);
			String[] parts = jwt.split("\\.");
			assertEquals(3, parts.length);
			String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
			assertEquals("{\"iat\":1799999940,\"exp\":1800000540,\"iss\":\"123456\"}", claims);
			Signature v = Signature.getInstance("SHA256withRSA");
			v.initVerify(k.getPublic());
			v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			assertTrue(v.verify(Base64.getUrlDecoder().decode(parts[2])));
		}
		assertThrows(IllegalArgumentException.class, () -> new GitHubApp("123456", "not a key"));
		assertThrows(IllegalArgumentException.class, () -> new GitHubApp("12a", pem("PRIVATE KEY", pkcs8)));
	}

	@Test
	void aCheckRunFromTheApiIsPassedOnOnlyAsStatusConclusionAndText() {
		Map<String, Object> body = Builder.checkRunBody(Map.of("status", "completed", "conclusion", "success", "name", "Not Myriad", "head_sha", "f".repeat(40),
			"output", Map.of("title", "t".repeat(500), "summary", "s", "annotations", List.of("x"))));
		assertEquals(List.of("status", "conclusion", "output"), List.copyOf(body.keySet()), "no name, commit or other fields from the API");
		@SuppressWarnings("unchecked") Map<String, Object> output = (Map<String, Object>) body.get("output");
		assertEquals(List.of("title", "summary"), List.copyOf(output.keySet()));
		assertEquals(200, ((String) output.get("title")).length());
		assertThrows(IllegalArgumentException.class, () -> Builder.checkRunBody(Map.of("status", "deleted", "output", Map.of("title", "t", "summary", "s"))));
		assertThrows(IllegalArgumentException.class, () -> Builder.checkRunBody(Map.of("status", "completed", "conclusion", "skipped", "output", Map.of("title", "t", "summary", "s"))));
		assertFalse(Builder.checkRunBody(Map.of("status", "queued", "conclusion", "success", "output", Map.of("title", "t", "summary", "s"))).containsKey("conclusion"));
	}
}
