package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedReleasesTest {
	private static final URI FEED = URI.create("https://example.com/addon/feed.json");
	private static final String SHA = "a".repeat(64);

	private static String entry(String version, String extra) {
		return "{\"version\": \"" + version + "\", \"file\": \"addon-" + version + ".jar\", \"url\": \"addon-" + version + ".jar\", \"size\": 10, \"sha256\": \"" + SHA + "\""
			+ (extra.isEmpty() ? "" : ", " + extra) + "}";
	}

	private static String feed(String... entries) {
		return "{\"format\": 1, \"id\": \"addon\", \"name\": \"Addon\", \"keys\": [\"" + TestKeys.A.pub().text() + "\"], \"versions\": [" + String.join(",", entries) + "]}";
	}

	@Test
	void readsAFeed() throws IOException {
		FeedReleases.Feed f = FeedReleases.parse(feed(entry("1.0.0", "\"minecraft\": \"~26.3\", \"signatures\": [{\"key\": \"k\", \"sig\": \"s\"}]")), FEED);
		assertEquals("addon", f.id());
		assertEquals(java.util.List.of(TestKeys.A.pub()), f.keys());
		var r = f.releases().getFirst();
		assertEquals("https://example.com/addon/addon-1.0.0.jar", r.asset().url());
		assertEquals("~26.3", r.minecraft());
		assertEquals(1, r.asset().signatures().size());
		assertTrue(r.asset().signed());
	}

	@Test
	void skipsPrereleasesAndKindsItCantInstall() throws IOException {
		FeedReleases.Feed f = FeedReleases.parse(feed(entry("1.0.0", ""), entry("1.1.0", "\"prerelease\": true"), entry("1.2.0", "\"delivery\": \"encrypted\""),
			entry("1.3.0", "\"access\": \"token\""), entry("1.4.0", "\"somethingNew\": {\"a\": 1}")), FEED);
		assertEquals(java.util.List.of("1.0.0", "1.4.0"), f.releases().stream().map(Releases.Release::version).toList());
	}

	@Test
	void refusesTheWholeFeedOverOneBrokenEntry() {
		String[] broken = {
			entry("1.0.0", "").replace("addon-1.0.0.jar\", \"url", "../evil.jar\", \"url"),
			entry("1.0.0", "").replace(SHA, "A".repeat(64)),
			entry("1.0.0", "").replace("\"size\": 10", "\"size\": 99999999999"),
			entry("1.0.0", "").replace("\"size\": 10", "\"size\": 1.5"),
			entry("1.0.0\\nid=x", ""),
			entry("1.0.0", "").replace("\"url\": \"addon-1.0.0.jar\"", "\"url\": \"http://example.com/a.jar\""),
			entry("1.0.0", "\"signatures\": \"nope\""),
		};
		for (String b : broken) assertThrows(IOException.class, () -> FeedReleases.parse(feed(b), FEED), b);
		assertThrows(IOException.class, () -> FeedReleases.parse(feed().replace("\"format\": 1", "\"format\": 2"), FEED));
		assertThrows(IOException.class, () -> FeedReleases.parse(feed().replace("\"id\": \"addon\"", "\"id\": \"../x\""), FEED));
		assertThrows(IOException.class, () -> FeedReleases.parse(feed().replace(TestKeys.A.pub().text(), "rsa:abc"), FEED));
		assertThrows(IOException.class, () -> FeedReleases.parse("[]", FEED));
		assertThrows(IOException.class, () -> FeedReleases.parse("not json", FEED));
	}

	@Test
	void picksTheNewestForThisMinecraft() throws Exception {
		FeedReleases.Feed f = FeedReleases.parse(feed(entry("1.0.0", "\"minecraft\": \"~26.3\""), entry("2.0.0", "\"minecraft\": \"~26.4\"")), FEED);
		Releases.Found found = Releases.newest(f.releases(), net.fabricmc.loader.api.Version.parse("26.3"));
		assertEquals("1.0.0", found.forThisMinecraft().version());
		assertEquals("2.0.0", found.newest().version());
	}
}
