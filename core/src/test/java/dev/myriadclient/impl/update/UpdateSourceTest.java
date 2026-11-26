package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateSourceTest {
	private static Optional<UpdateSource> gh(String repo) {
		return Optional.of(new UpdateSource.GitHub(repo));
	}

	@Test
	void readsTheRepoFromGitHubUrls() {
		assertEquals(gh("MyriadClient/myriad-core"), UpdateSource.gitHub("https://github.com/MyriadClient/myriad-core"));
		assertEquals(gh("MyriadClient/myriad-core"), UpdateSource.gitHub("https://github.com/MyriadClient/myriad-core/"));
		assertEquals(gh("MyriadClient/myriad-core"), UpdateSource.gitHub("https://github.com/MyriadClient/myriad-core.git"));
		assertEquals(gh("MyriadClient/myriad-core"), UpdateSource.gitHub("https://www.github.com/MyriadClient/myriad-core/tree/master/core"));
		assertEquals(gh("a/my.addon"), UpdateSource.gitHub("http://github.com/a/my.addon#readme"));
	}

	@Test
	void ignoresOtherUrls() {
		assertEquals(Optional.empty(), UpdateSource.gitHub("https://gitlab.com/a/b"));
		assertEquals(Optional.empty(), UpdateSource.gitHub("https://github.com/a"));
		assertEquals(Optional.empty(), UpdateSource.gitHub("https://example.com/github.com/a/b"));
	}

	@Test
	void theCustomValueWinsOverSources() {
		assertEquals(gh("a/releases"), UpdateSource.resolve("a/releases", "https://github.com/a/code"));
		assertEquals(gh("a/releases"), UpdateSource.resolve("https://github.com/a/releases", null));
		assertEquals(gh("a/code"), UpdateSource.resolve(null, "https://github.com/a/code"));
		assertEquals(gh("a/code"), UpdateSource.resolve(true, "https://github.com/a/code"));
	}

	@Test
	void anHttpsAddressIsAFeed() {
		assertEquals(Optional.of(new UpdateSource.Feed(URI.create("https://example.com/addon/feed.json"))),
			UpdateSource.resolve("https://example.com/addon/feed.json", "https://github.com/a/code"));
	}

	@Test
	void plainHttpAndOddAddressesAreNoSource() {
		assertEquals(Optional.empty(), UpdateSource.resolve("http://example.com/feed.json", null));
		assertEquals(Optional.empty(), UpdateSource.resolve("ftp://example.com/feed.json", null));
		assertEquals(Optional.empty(), UpdateSource.resolve("https://user:pw@example.com/feed.json", null));
		assertEquals(Optional.empty(), UpdateSource.resolve("not a link", null));
		// Plain http to this machine needs the dev flag.
		String dev = System.getProperty("myriad.dev");
		try {
			System.setProperty("myriad.dev", "false");
			assertEquals(Optional.empty(), UpdateSource.resolve("http://localhost:8000/feed.json", null));
		} finally {
			if (dev == null) System.clearProperty("myriad.dev");
			else System.setProperty("myriad.dev", dev);
		}
	}

	@Test
	void falseOptsOut() {
		assertEquals(Optional.empty(), UpdateSource.resolve(false, "https://github.com/a/code"));
		assertEquals(Optional.empty(), UpdateSource.resolve(null, null));
	}
}
