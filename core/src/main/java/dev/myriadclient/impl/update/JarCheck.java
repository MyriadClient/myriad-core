package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Checks a downloaded jar before it's staged: it's the file GitHub listed, the mod we asked for, and it will load here. */
final class JarCheck {
	private JarCheck() {
	}

	/** Null if {@code jar} is the asset GitHub described (size, and SHA-256 when GitHub gives one), else why not. */
	static @Nullable String verifyDownload(byte[] jar, GitHubReleases.Asset asset) {
		if (jar.length != asset.size()) return "the download was " + jar.length + " bytes, expected " + asset.size();
		if (asset.sha256() != null) {
			try {
				String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
				if (!actual.equals(asset.sha256())) return "the download doesn't match its SHA-256";
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}
		return null;
	}

	/**
	 * Null if the jar's {@code fabric.mod.json} is mod {@code id} at {@code version} and its {@code minecraft} and
	 * {@code myriad} dependencies accept {@code minecraft} and {@code core}; else why not, for the user.
	 *
	 * @param core the core version it will run with, or null to skip that check (when updating core itself)
	 */
	static @Nullable String check(byte[] jar, String id, String version, Version minecraft, @Nullable Version core) {
		JsonObject meta;
		try {
			meta = modJson(jar);
		} catch (IOException | RuntimeException e) {
			return "the download isn't a readable jar";
		}
		if (meta == null) return "the download isn't a Fabric mod";
		String gotId = string(meta, "id");
		if (!id.equals(gotId)) return "the download is " + gotId + ", not " + id;
		String gotVersion = string(meta, "version");
		if (!version.equals(gotVersion)) return "the download is version " + gotVersion + ", not " + version;
		JsonObject depends = meta.has("depends") && meta.get("depends").isJsonObject() ? meta.getAsJsonObject("depends") : new JsonObject();
		String needs = unmet(depends, "minecraft", minecraft);
		if (needs != null) return version + " needs Minecraft " + needs;
		if (core != null) {
			needs = unmet(depends, "myriad", core);
			if (needs != null) return version + " needs Myriad " + needs;
		}
		return null;
	}

	/** The dependency's requirement if {@code have} doesn't meet it, else null. A list means any one of them. */
	private static @Nullable String unmet(JsonObject depends, String mod, Version have) {
		JsonElement e = depends.get(mod);
		if (e == null) return null;
		List<String> ranges = new ArrayList<>();
		if (e.isJsonArray()) for (JsonElement r : e.getAsJsonArray()) ranges.add(r.getAsString());
		else ranges.add(e.getAsString());
		for (String r : ranges) {
			try {
				if (VersionPredicate.parse(r).test(have)) return null;
			} catch (VersionParsingException ignored) {
				// A range this loader can't read won't load either.
			}
		}
		return String.join(" or ", ranges);
	}

	private static @Nullable JsonObject modJson(byte[] jar) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
				if (e.getName().equals("fabric.mod.json")) {
					return JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).getAsJsonObject();
				}
			}
		}
		return null;
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "unknown";
	}
}
