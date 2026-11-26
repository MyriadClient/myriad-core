package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myriadclient.impl.update.Releases.Asset;
import dev.myriadclient.impl.update.Releases.Found;
import dev.myriadclient.impl.update.Releases.Release;
import net.fabricmc.loader.api.Version;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a repo's GitHub releases and picks the jar to install from one. A release says which Minecraft it's for in a
 * line of its notes, {@code Minecraft: ~26.2} (a Fabric version range, as a {@code fabric.mod.json} gives it); one
 * without that line is taken to be for any. A signed release has {@code <jar>.sig} next to the jar: one feed entry for
 * it (see {@link FeedReleases}) with its developer's signatures.
 */
final class GitHubReleases {
	/** The most a release list may be. */
	private static final long MAX_LIST = 4L * 1024 * 1024;
	private GitHubReleases() {
	}

	private static final Pattern MINECRAFT = Pattern.compile("(?m)^Minecraft:\\s*(\\S[^\\r\\n]*?)\\s*$");

	/**
	 * The repo's latest full releases (drafts and pre-releases left out), for Minecraft {@code minecraft}. Fails with
	 * "HTTP 404" when the repo can't be found.
	 */
	static CompletableFuture<Found> releases(String repo, @Nullable String token, @Nullable String installedFile, String installedVersion, Version minecraft) {
		return Fetch.text(URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=30"), MAX_LIST, token, "application/vnd.github+json")
			.thenApply(text -> find(JsonParser.parseString(text).getAsJsonArray(), installedFile, installedVersion, minecraft, token != null));
	}

	static Found find(Iterable<JsonElement> list, @Nullable String installedFile, String installedVersion, Version minecraft) {
		return find(list, installedFile, installedVersion, minecraft, false);
	}

	/** {@code authed}: a token goes with the downloads, so they're made through the API (a private repo's only way). */
	static Found find(Iterable<JsonElement> list, @Nullable String installedFile, String installedVersion, Version minecraft, boolean authed) {
		List<Release> out = new ArrayList<>();
		for (JsonElement e : list) {
			JsonObject json = e.getAsJsonObject();
			if (flag(json, "draft") || flag(json, "prerelease") || !json.has("tag_name")) continue;
			Release r = parse(json, installedFile, installedVersion, authed);
			if (Names.isVersion(r.version())) out.add(r);
		}
		return Releases.newest(out, minecraft);
	}

	private static boolean flag(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsBoolean();
	}

	static Release parse(JsonObject json, @Nullable String installedFile, String installedVersion) {
		return parse(json, installedFile, installedVersion, false);
	}

	static Release parse(JsonObject json, @Nullable String installedFile, String installedVersion, boolean authed) {
		String version = versionOf(json.get("tag_name").getAsString());
		List<Asset> assets = new ArrayList<>();
		Map<String, String> sigs = new HashMap<>();
		if (json.has("assets")) {
			for (JsonElement e : json.getAsJsonArray("assets")) {
				JsonObject a = e.getAsJsonObject();
				String name = a.get("name").getAsString();
				String url = authed && a.has("url") ? a.get("url").getAsString() : a.get("browser_download_url").getAsString();
				if (name.endsWith(".jar.sig")) {
					sigs.put(name.substring(0, name.length() - 4), url);
					continue;
				}
				// A file name that isn't a plain jar name is never a candidate.
				if (!Names.isFile(name)) continue;
				String digest = a.has("digest") && a.get("digest").isJsonPrimitive() ? a.get("digest").getAsString() : null;
				String sha256 = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
				assets.add(new Asset(name, url, a.get("size").getAsLong(), sha256));
			}
		}
		String url = json.has("html_url") ? json.get("html_url").getAsString() : null;
		String minecraft = null;
		if (json.has("body") && json.get("body").isJsonPrimitive()) {
			Matcher m = MINECRAFT.matcher(json.get("body").getAsString());
			if (m.find()) minecraft = m.group(1);
		}
		Asset asset = pick(assets, installedFile, installedVersion, version);
		if (asset != null && sigs.containsKey(asset.name())) {
			asset = new Asset(asset.name(), asset.url(), asset.size(), asset.sha256(), List.of(), sigs.get(asset.name()));
		}
		return new Release(version, url, asset, minecraft);
	}

	/** {@code v0.1.2} → {@code 0.1.2}. */
	static String versionOf(String tag) {
		return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
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
