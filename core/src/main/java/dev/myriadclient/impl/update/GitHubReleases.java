package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.util.Http;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Reads a repo's latest GitHub release and picks the jar to install from it. */
final class GitHubReleases {
	private GitHubReleases() {
	}

	/** A release; {@code asset} is null when no jar in it could be told apart as the one to install. */
	record Release(String version, String url, @Nullable Asset asset) {
	}

	/** {@code sha256} is the hex digest GitHub reports for the file, or null if it reports none. */
	record Asset(String name, String url, long size, @Nullable String sha256) {
	}

	/** The latest full release (GitHub leaves out drafts and pre-releases). Fails with "HTTP 404" when there is none. */
	static CompletableFuture<Release> latest(String repo, @Nullable String installedFile, String installedVersion) {
		return Http.getJson("https://api.github.com/repos/" + repo + "/releases/latest")
			.thenApply(json -> parse(json.getAsJsonObject(), installedFile, installedVersion));
	}

	static Release parse(JsonObject json, @Nullable String installedFile, String installedVersion) {
		String version = versionOf(json.get("tag_name").getAsString());
		List<Asset> assets = new ArrayList<>();
		if (json.has("assets")) {
			for (JsonElement e : json.getAsJsonArray("assets")) {
				JsonObject a = e.getAsJsonObject();
				String digest = a.has("digest") && a.get("digest").isJsonPrimitive() ? a.get("digest").getAsString() : null;
				String sha256 = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
				assets.add(new Asset(a.get("name").getAsString(), a.get("browser_download_url").getAsString(), a.get("size").getAsLong(), sha256));
			}
		}
		String url = json.has("html_url") ? json.get("html_url").getAsString() : null;
		return new Release(version, url, pick(assets, installedFile, installedVersion, version));
	}

	/** {@code v0.1.2} → {@code 0.1.2}. */
	static String versionOf(String tag) {
		return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
	}

	/** Whether {@code candidate} is a newer version than {@code installed}; false if either can't be read. */
	static boolean isNewer(String candidate, String installed) {
		try {
			return Version.parse(candidate).compareTo(Version.parse(installed)) > 0;
		} catch (VersionParsingException e) {
			return false;
		}
	}

	/**
	 * The jar to install. When the installed jar is named {@code <base>-<version>.jar}, the release's
	 * {@code <base>-<new version>.jar} ({@code myriad-0.1.1.jar} → {@code myriad-0.1.2.jar}) or nothing, so another mod's
	 * jar in the same release is never taken for it. When it was renamed, the release's only jar that isn't sources,
	 * javadoc or a dev build.
	 */
	static @Nullable Asset pick(List<Asset> assets, @Nullable String installedFile, String installedVersion, String version) {
		List<Asset> jars = assets.stream().filter(a -> {
			String n = a.name().toLowerCase(Locale.ROOT);
			return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar") && !n.endsWith("-dev.jar");
		}).toList();
		String suffix = "-" + installedVersion + ".jar";
		if (installedFile != null && installedFile.endsWith(suffix)) {
			String expected = installedFile.substring(0, installedFile.length() - suffix.length()) + "-" + version + ".jar";
			for (Asset a : jars) if (a.name().equals(expected)) return a;
			return null;
		}
		return jars.size() == 1 ? jars.getFirst() : null;
	}
}
