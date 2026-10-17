package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateSourceTest {
	@Test
	void readsTheRepoFromGitHubUrls() {
		assertEquals(Optional.of("MyriadClient/myriad-core"), UpdateSource.gitHubRepo("https://github.com/MyriadClient/myriad-core"));
		assertEquals(Optional.of("MyriadClient/myriad-core"), UpdateSource.gitHubRepo("https://github.com/MyriadClient/myriad-core/"));
		assertEquals(Optional.of("MyriadClient/myriad-core"), UpdateSource.gitHubRepo("https://github.com/MyriadClient/myriad-core.git"));
		assertEquals(Optional.of("MyriadClient/myriad-core"), UpdateSource.gitHubRepo("https://www.github.com/MyriadClient/myriad-core/tree/master/core"));
		assertEquals(Optional.of("a/my.addon"), UpdateSource.gitHubRepo("http://github.com/a/my.addon#readme"));
	}

	@Test
	void ignoresOtherUrls() {
		assertEquals(Optional.empty(), UpdateSource.gitHubRepo("https://gitlab.com/a/b"));
		assertEquals(Optional.empty(), UpdateSource.gitHubRepo("https://github.com/a"));
		assertEquals(Optional.empty(), UpdateSource.gitHubRepo("https://example.com/github.com/a/b"));
	}

	@Test
	void theCustomValueWinsOverSources() {
		assertEquals(Optional.of("a/releases"), UpdateSource.resolve("a/releases", "https://github.com/a/code"));
		assertEquals(Optional.of("a/releases"), UpdateSource.resolve("https://github.com/a/releases", null));
		assertEquals(Optional.of("a/code"), UpdateSource.resolve(null, "https://github.com/a/code"));
		assertEquals(Optional.of("a/code"), UpdateSource.resolve(true, "https://github.com/a/code"));
	}

	@Test
	void falseOptsOut() {
		assertEquals(Optional.empty(), UpdateSource.resolve(false, "https://github.com/a/code"));
		assertEquals(Optional.empty(), UpdateSource.resolve(null, null));
	}
}
