package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class LibraryAccountTest {
	@TempDir
	Path dir;

	/** The same server ids Myriad's API works out (its accounts test pins them too). */
	@Test
	void serverIdsMatchTheApi() {
		assertEquals("375f420c445c6c3ef27ef2e2d1867e64ffbf384e", LibraryAccount.serverId("abc"));
		assertEquals("-6aebae44fe50d5f3f22011d471415ba00be48134", LibraryAccount.serverId("nonce-xyz"), "negative hashes keep Java's sign");
	}

	/** Codes check the way the developer's tool makes them (the Gradle plugin's InvitesTest pins the same codes). */
	@Test
	void codesCheckLikeTheDevelopersTool() {
		assertTrue(LibraryAccount.checks("A".repeat(26) + "A"));
		assertTrue(LibraryAccount.checks("ABCDEFGHIJKLMNOPQRSTUVWXYZ2"));
		assertFalse(LibraryAccount.checks("ABCDEFGHIJKLMNOPQRSTUVWXYZ3"), "a typo in the checksum");
		assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ2", LibraryAccount.canonical("myr-abcde fghij-KLMNO-pqrst-uvwxy-z2"));
		assertNull(LibraryAccount.canonical("myr-ABC"));
		assertNull(LibraryAccount.canonical("myr-ABCDEFGHIJKLMNOPQRSTUVWXY01"), "0 and 1 aren't base32");
	}

	@Test
	void theSessionIsOwnerOnlyAndRoundTrips() throws Exception {
		Path file = dir.resolve("account/session.json");
		LibraryAccount.save(file, new LibraryAccount.Session("myr_s_" + "a".repeat(43), "Steve"));
		assertEquals("Steve", LibraryAccount.load(file).name());
		if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
		}
		assertFalse(Files.exists(file.resolveSibling("session.json.tmp")));
		assertFalse(Files.exists(file.resolveSibling("session.json.bak")), "no backup holding the token");
		Files.writeString(file, "{\"token\": \"stolen<script>\", \"name\": \"Steve\"}");
		assertNull(LibraryAccount.load(file), "an odd token is ignored");
	}
}
