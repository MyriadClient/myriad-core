package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleasesTest {
	private static GitHubReleases.Asset jar(String name) {
		return new GitHubReleases.Asset(name, "https://example/" + name, 1, null);
	}

	@Test
	void readsTheLatestReleaseAndPicksThePlainJar() {
		JsonObject json = JsonParser.parseString("""
			{"tag_name": "v0.1.2", "html_url": "https://github.com/MyriadClient/myriad-core/releases/tag/v0.1.2", "assets": [
			  {"name": "myriad-0.1.2-javadoc.jar", "size": 5, "browser_download_url": "https://dl/javadoc"},
			  {"name": "myriad-0.1.2.jar", "size": 1234, "browser_download_url": "https://dl/jar", "digest": "sha256:ABCDEF"},
			  {"name": "myriad-0.1.2-sources.jar", "size": 7, "browser_download_url": "https://dl/sources"}
			]}""").getAsJsonObject();
		GitHubReleases.Release r = GitHubReleases.parse(json, "myriad-0.1.1.jar", "0.1.1");
		assertEquals("0.1.2", r.version());
		assertEquals("https://github.com/MyriadClient/myriad-core/releases/tag/v0.1.2", r.url());
		assertEquals(new GitHubReleases.Asset("myriad-0.1.2.jar", "https://dl/jar", 1234, "abcdef"), r.asset());
	}

	@Test
	void prefersTheJarNamedLikeTheInstalledOne() {
		List<GitHubReleases.Asset> assets = List.of(jar("fancy-fabric-1.0.0.jar"), jar("fancy-1.0.0.jar"), jar("fancy-1.0.0-dev.jar"));
		assertEquals("fancy-fabric-1.0.0.jar", GitHubReleases.pick(assets, "fancy-fabric-0.9.jar", "0.9", "1.0.0").name());
	}

	@Test
	void aRenamedJarStillUpdatesWhenTheReleaseHasOneJar() {
		List<GitHubReleases.Asset> assets = List.of(jar("myriad-essentials-0.1.1.jar"), jar("myriad-essentials-0.1.1-sources.jar"));
		assertEquals("myriad-essentials-0.1.1.jar", GitHubReleases.pick(assets, "essentials.jar", "0.1.0", "0.1.1").name());
	}

	@Test
	void neverTakesAnotherModsJar() {
		// Essentials 0.1.0 named Myriad's repository as its source; that release only has Myriad's jar.
		List<GitHubReleases.Asset> assets = List.of(jar("myriad-0.1.1.jar"), jar("myriad-0.1.1-sources.jar"));
		assertNull(GitHubReleases.pick(assets, "myriad-essentials-0.1.0.jar", "0.1.0", "0.1.1"));
	}

	@Test
	void givesUpWhenItCantTellWhichJar() {
		List<GitHubReleases.Asset> assets = List.of(jar("a-fabric-2.jar"), jar("a-forge-2.jar"));
		assertNull(GitHubReleases.pick(assets, "renamed.jar", "1", "2"));
		assertNull(GitHubReleases.pick(List.of(jar("notes.txt")), "a-1.jar", "1", "2"));
	}

	@Test
	void comparesVersions() {
		assertEquals("0.1.2", GitHubReleases.versionOf("v0.1.2"));
		assertEquals("0.1.2", GitHubReleases.versionOf("0.1.2"));
		assertTrue(GitHubReleases.isNewer("0.1.2", "0.1.1"));
		assertTrue(GitHubReleases.isNewer("0.10.0", "0.9.0"));
		assertTrue(GitHubReleases.isNewer("1.0.0", "1.0.0-beta.2"));
		assertFalse(GitHubReleases.isNewer("0.1.1", "0.1.1"));
		assertFalse(GitHubReleases.isNewer("0.1.0", "0.1.1"));
	}

	private static String release(String version, String body, boolean prerelease) {
		return """
			{"tag_name": "v%s", "html_url": "https://github.com/MyriadClient/myriad-core/releases/tag/v%s", "prerelease": %s,
			 "body": "%s", "assets": [{"name": "myriad-%s.jar", "size": 1, "browser_download_url": "https://dl/%s"}]}"""
			.formatted(version, version, prerelease, body, version, version);
	}

	private static GitHubReleases.Found find(String minecraft, String... releases) throws Exception {
		JsonArray list = JsonParser.parseString("[" + String.join(",", releases) + "]").getAsJsonArray();
		return GitHubReleases.find(list, "myriad-1.8.0.jar", "1.8.0", Version.parse(minecraft));
	}

	@Test
	void readsWhichMinecraftAReleaseIsFor() {
		JsonObject json = JsonParser.parseString(release("2.0.0", "## 2.0.0\\n\\nNotes.\\n\\nMinecraft: ~26.3\\n", false)).getAsJsonObject();
		GitHubReleases.Release r = GitHubReleases.parse(json, "myriad-1.8.0.jar", "1.8.0");
		assertEquals("~26.3", r.minecraft());
		assertEquals("26.3", r.minecraftShown());
		assertNull(GitHubReleases.parse(JsonParser.parseString(release("1.8.0", "Notes.", false)).getAsJsonObject(), null, "1.7.2").minecraft());
	}

	@Test
	void offersOnlyAReleaseForTheRunningMinecraft() throws Exception {
		// Newest first, as GitHub lists them: 2.0.0 is for 26.3 only, 1.8.1 for 26.2; 1.7.2 says nothing (any).
		String r200 = release("2.0.0", "Minecraft: ~26.3", false), r181 = release("1.8.1", "Minecraft: ~26.2", false), r172 = release("1.7.2", "", false);
		GitHubReleases.Found on262 = find("26.2", r200, r181, r172);
		assertEquals("1.8.1", on262.forThisMinecraft().version());
		assertEquals("2.0.0", on262.newest().version());
		assertEquals("2.0.0", find("26.3", r200, r181, r172).forThisMinecraft().version());
		// A release that doesn't say which Minecraft is taken for any.
		assertEquals("1.7.2", find("26.2", r200, r172).forThisMinecraft().version());
	}

	@Test
	void leavesOutPreReleasesAndOrdersByVersion() throws Exception {
		GitHubReleases.Found f = find("26.2", release("1.9.0-beta.1", "Minecraft: ~26.2", true), release("1.8.0", "Minecraft: ~26.2", false),
			release("1.8.1", "Minecraft: ~26.2", false));
		assertEquals("1.8.1", f.forThisMinecraft().version());
		assertEquals("1.8.1", f.newest().version());
	}
}
