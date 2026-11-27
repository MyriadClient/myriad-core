package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchTest {
	@Test
	void onlyHttpsIsFetched() {
		assertNull(Fetch.refused(URI.create("https://example.com/a")));
		assertNotNull(Fetch.refused(URI.create("http://example.com/a")));
		String dev = System.getProperty("myriad.dev");
		try {
			System.setProperty("myriad.dev", "false");
			assertNotNull(Fetch.refused(URI.create("http://localhost/a")));
			System.setProperty("myriad.dev", "true");
			assertNull(Fetch.refused(URI.create("http://localhost:8000/a")));
			assertNotNull(Fetch.refused(URI.create("http://example.com/a")));
		} finally {
			if (dev == null) System.clearProperty("myriad.dev");
			else System.setProperty("myriad.dev", dev);
		}
		assertNotNull(Fetch.refused(URI.create("file:///etc/passwd")));
		assertNotNull(Fetch.refused(URI.create("https://user:pass@example.com/a")));
		assertNotNull(Fetch.refused(URI.create("jar:https://example.com/a!/b")));
	}

	@Test
	void aTokenStaysWithItsOrigin() {
		URI feed = URI.create("https://feeds.example.com/a/feed.json");
		assertTrue(Fetch.sameOrigin(feed, URI.create("https://FEEDS.example.com:443/b.jar")));
		assertFalse(Fetch.sameOrigin(feed, URI.create("https://cdn.example.com/b.jar")));
		assertFalse(Fetch.sameOrigin(feed, URI.create("https://feeds.example.com:8443/b.jar")));
		assertFalse(Fetch.sameOrigin(feed, URI.create("http://feeds.example.com/b.jar")));
	}

	@Test
	void namesStayPlain() {
		Path dir = Path.of("/game/myriad/updates");
		assertEquals(dir.resolve("a-1.0.jar"), Names.fileIn(dir, "a-1.0.jar"));
		for (String bad : new String[] {"../a.jar", "a/b.jar", "/a.jar", ".hidden.jar", "a.jar\0.txt", "a.zip", "..jar", "a b.jar"}) {
			assertNull(Names.fileIn(dir, bad), bad);
		}
		assertTrue(Names.isVersion("2.7.0-beta.1+mc26.3"));
		assertFalse(Names.isVersion("1.0\n"));
		assertFalse(Names.isVersion("..")); // never a path step in /v1/library/private/<id>/<version>
		assertFalse(Names.isVersion("."));
		assertFalse(Names.isId("Myriad"));
		assertTrue(FeedTokens.valid("github_pat_ABC123"));
		assertFalse(FeedTokens.valid("abc\r\nX-Evil: 1"));
	}
}
