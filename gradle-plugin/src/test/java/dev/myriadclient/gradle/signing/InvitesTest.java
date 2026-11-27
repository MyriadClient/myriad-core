package dev.myriadclient.gradle.signing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class InvitesTest {
	/** The same bytes Myriad's API builds (its statements test pins this exact text). */
	@Test
	void requestBytesMatchTheApi() {
		Invites.Request create = new Invites.Request("closed-addon", "create", "https://api.myriadclient.dev", 1800000000, "nonce-00000000000000000000", "a".repeat(64), 2, 1800003600, "friends", 0, null);
		assertEquals("myriad-library-invite:1\nid=closed-addon\naction=create\napi=https://api.myriadclient.dev\nissued=1800000000\nnonce=nonce-00000000000000000000\n"
			+ "code-sha256=" + "a".repeat(64) + "\nuses=2\nnot-after=1800003600\nlabel=friends\n", new String(Invites.bytes(create), StandardCharsets.US_ASCII));
		Invites.Request revoke = new Invites.Request("closed-addon", "revoke-grant", "https://api.myriadclient.dev", 1800000000, "nonce-00000000000000000001", null, 0, 0, null, 0, "0123456789abcdef0123456789abcdef");
		assertEquals("myriad-library-invite:1\nid=closed-addon\naction=revoke-grant\napi=https://api.myriadclient.dev\nissued=1800000000\nnonce=nonce-00000000000000000001\nuuid=0123456789abcdef0123456789abcdef\n",
			new String(Invites.bytes(revoke), StandardCharsets.US_ASCII));
		assertThrows(IllegalArgumentException.class, () -> Invites.bytes(new Invites.Request("closed-addon", "create", "https://api.myriadclient.dev", 1, "nonce-00000000000000000002", "a".repeat(64), 1, 2, "a=b", 0, null)),
			"a label can't hold =");
		assertThrows(IllegalArgumentException.class, () -> Invites.bytes(new Invites.Request("closed-addon", "delete-all", "https://api.myriadclient.dev", 1, "nonce-00000000000000000002", null, 0, 0, null, 0, null)));
	}

	@Test
	void codesAreRandomCheckedAndForgiving() {
		String code = Invites.newCode();
		assertTrue(Invites.checks(code));
		assertNotEquals(code, Invites.newCode());
		String shown = Invites.shown(code);
		assertTrue(shown.matches("myr(-[A-Z2-7]{1,5}){6}"), shown);
		assertEquals(code, Invites.canonical(shown.toLowerCase().replace("-", " ")));
		// A wrong letter almost always fails the checksum (1 typo in 32 gets through).
		int passed = 0;
		for (char c : Invites.ALPHABET.toCharArray()) {
			if (c != code.charAt(3) && Invites.checks(code.substring(0, 3) + c + code.substring(4))) passed++;
		}
		assertTrue(passed <= 4, passed + " of 31 typos passed");
		assertNull(Invites.canonical("myr-short"));
		assertEquals(Invites.hash(code), Signing.hex(Signing.sha256(code.getBytes(StandardCharsets.US_ASCII))));
		assertEquals("0123456789abcdef0123456789abcdef", Invites.uuid("01234567-89AB-cdef-0123-456789abcdef"));
		// The same codes Myriad's core checks (its LibraryAccountTest pins them too).
		assertTrue(Invites.checks("A".repeat(26) + "A"));
		assertTrue(Invites.checks("ABCDEFGHIJKLMNOPQRSTUVWXYZ2"));
	}
}
