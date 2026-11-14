package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.network.Http;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a repo's GitHub releases and picks the jar to install from one. A release says which Minecraft it's for in a
 * line of its notes, {@code Minecraft: ~26.2} (a Fabric version range, as a {@code fabric.mod.json} gives it); one
 * without that line is taken to be for any.
 */
final class GitHubReleases {
	private GitHubReleases() {
	}

	/**
	 * A release; {@code asset} is null when no jar in it could be told apart as the one to install, {@code minecraft}
	 * when its notes don't say which Minecraft it's for.
	 */
	record Release(String version, String url, @Nullable Asset asset, @Nullable String minecraft) {
		/** Whether it's for Minecraft {@code version}: when its notes don't say, or their range can't be read, yes. */
		boolean worksOn(Version version) {
			if (minecraft == null) return true;
			try {
				return VersionPredicate.parse(minecraft).test(version);
			} catch (VersionParsingException e) {
				return true;
			}
		}

		/** Its Minecraft for the user: {@code ~26.3} as {@code 26.3}. */
		String minecraftShown() {
			return minecraft == null ? "" : minecraft.replaceFirst("^[~^=]+", "");
		}
	}

	/**
	 * What a repo has released: the newest release for the running Minecraft, and the newest of all (another Minecraft's,
	 * when it's newer than any for this one). Either is null when there's none.
	 */
	record Found(@Nullable Release forThisMinecraft, @Nullable Release newest) {
	}

	private static final Pattern MINECRAFT = Pattern.compile("(?m)^Minecraft:\\s*(\\S[^\\r\\n]*?)\\s*$");

	/** {@code sha256} is the hex digest GitHub reports for the file, or null if it reports none. */
	record Asset(String name, String url, long size, @Nullable String sha256) {
	}

	/**
	 * The repo's latest full releases (drafts and pre-releases left out), for Minecraft {@code minecraft}. Fails with
	 * "HTTP 404" when the repo can't be found.
	 */
	static CompletableFuture<Found> releases(String repo, @Nullable String installedFile, String installedVersion, Version minecraft) {
		return Http.getJson("https://api.github.com/repos/" + repo + "/releases?per_page=30")
			.thenApply(json -> find(json.getAsJsonArray(), installedFile, installedVersion, minecraft));
	}

	static Found find(Iterable<JsonElement> list, @Nullable String installedFile, String installedVersion, Version minecraft) {
		Release forThis = null, newest = null;
		for (JsonElement e : list) {
			JsonObject json = e.getAsJsonObject();
			if (flag(json, "draft") || flag(json, "prerelease") || !json.has("tag_name")) continue;
			Release r = parse(json, installedFile, installedVersion);
			if (newest == null || isNewer(r.version(), newest.version())) newest = r;
			if (r.worksOn(minecraft) && (forThis == null || isNewer(r.version(), forThis.version()))) forThis = r;
		}
		return new Found(forThis, newest);
	}

	private static boolean flag(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsBoolean();
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
		String minecraft = null;
		if (json.has("body") && json.get("body").isJsonPrimitive()) {
			Matcher m = MINECRAFT.matcher(json.get("body").getAsString());
			if (m.find()) minecraft = m.group(1);
		}
		return new Release(version, url, pick(assets, installedFile, installedVersion, version), minecraft);
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
