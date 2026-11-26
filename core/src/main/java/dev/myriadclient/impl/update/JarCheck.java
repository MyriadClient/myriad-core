package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a downloaded jar before it's staged: it's the file the release listed, the mod we asked for, it will load here,
 * and it brings in no other Myriad addon (as itself, through {@code provides}, or in a jar inside it), since the loader
 * would take the newest of two mods with one id and so let one addon replace another.
 */
final class JarCheck {
	/** The largest {@code fabric.mod.json} read, and how deep jars inside jars are looked into. */
	static final int MAX_MOD_JSON = 256 * 1024;
	private static final int MAX_DEPTH = 3, MAX_NESTED = 64;
	/** The most the jars inside a jar may come to, all together, unpacked. */
	private static final long MAX_NESTED_BYTES = 128L * 1024 * 1024;
	static final long MAX_JAR = 64L * 1024 * 1024;

	private JarCheck() {
	}

	/** What the check found: {@code problem} null when the jar passed; then {@code keys} are those it lists. */
	record Checked(@Nullable String problem, List<DevKey> keys) {
		static Checked failed(String problem) {
			return new Checked(problem, List.of());
		}
	}

	static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Null if {@code jar} is the file the release described ({@code size}, and {@code sha256} when given), else why not. */
	static @Nullable String verifyDownload(byte[] jar, long size, @Nullable String sha256) {
		if (jar.length != size) return "the download was " + jar.length + " bytes, expected " + size;
		if (sha256 != null && !MessageDigest.isEqual(HexFormat.of().formatHex(sha256(jar)).getBytes(StandardCharsets.US_ASCII),
			sha256.getBytes(StandardCharsets.US_ASCII))) return "the download doesn't match its SHA-256";
		return null;
	}

	/**
	 * Whether the jar's {@code fabric.mod.json} is mod {@code id} at {@code version}, its {@code minecraft} and
	 * {@code myriad} dependencies accept {@code minecraft} and {@code core}, and neither it nor a jar inside it is (or
	 * provides) one of {@code others}.
	 *
	 * @param core the core version it will run with, or null to skip that check (when updating core itself)
	 * @param others the ids no other jar may bring in: the other installed Myriad addons and Myriad itself
	 */
	static Checked check(byte[] jar, String id, String version, Version minecraft, @Nullable Version core, Set<String> others) {
		JarEntries entries;
		JsonObject meta;
		try {
			entries = JarEntries.read(jar);
			meta = modJson(entries);
		} catch (IOException e) {
			return Checked.failed("the download isn't a jar Myriad can vouch for (" + e.getMessage() + ")");
		} catch (RuntimeException e) {
			return Checked.failed("the download isn't a readable jar");
		}
		if (meta == null) return Checked.failed("the download isn't a Fabric mod");
		String gotId = string(meta, "id");
		if (!id.equals(gotId)) return Checked.failed("the download is " + gotId + ", not " + id);
		String gotVersion = string(meta, "version");
		if (!version.equals(gotVersion)) return Checked.failed("the download is version " + gotVersion + ", not " + version);
		JsonObject depends = meta.has("depends") && meta.get("depends").isJsonObject() ? meta.getAsJsonObject("depends") : new JsonObject();
		String needs = unmet(depends, "minecraft", minecraft);
		if (needs != null) return Checked.failed(version + " needs Minecraft " + needs);
		if (core != null) {
			needs = unmet(depends, "myriad", core);
			if (needs != null) return Checked.failed(version + " needs Myriad " + needs);
		}
		Set<String> brings = new LinkedHashSet<>();
		try {
			provided(meta, brings);
			nested(entries, meta, brings, 1, new long[2]);
		} catch (IOException e) {
			return Checked.failed("a jar inside the download can't be vouched for (" + e.getMessage() + ")");
		} catch (RuntimeException e) {
			return Checked.failed("a jar inside the download isn't readable");
		}
		for (String other : others) {
			if (!other.equals(id) && brings.contains(other)) return Checked.failed("the download also brings in " + other + ", which it mustn't");
		}
		return new Checked(null, keys(meta));
	}

	/** The keys the jar lists ({@code custom.myriad.keys}). */
	static List<DevKey> keys(JsonObject meta) {
		List<DevKey> out = new ArrayList<>();
		JsonElement custom = meta.get("custom");
		if (custom == null || !custom.isJsonObject()) return out;
		JsonElement myriad = custom.getAsJsonObject().get("myriad");
		if (myriad == null || !myriad.isJsonObject()) return out;
		JsonElement keys = myriad.getAsJsonObject().get("keys");
		if (keys == null || !keys.isJsonArray()) return out;
		for (JsonElement e : keys.getAsJsonArray()) {
			if (out.size() >= DevKey.MAX_KEYS) break;
			DevKey k = e.isJsonPrimitive() ? DevKey.parse(e.getAsString()) : null;
			if (k != null && !out.contains(k)) out.add(k);
		}
		return out;
	}

	/** The id and {@code provides} of a {@code fabric.mod.json}, added to {@code into}. */
	static void provided(JsonObject meta, Set<String> into) {
		into.add(string(meta, "id"));
		JsonElement p = meta.get("provides");
		if (p != null && p.isJsonArray()) for (JsonElement e : p.getAsJsonArray()) if (e.isJsonPrimitive()) into.add(e.getAsString());
	}

	/** The mods in the jars {@code meta} nests ({@code "jars": [{"file": ...}]}), and theirs, to {@link #MAX_DEPTH}. */
	/** {@code count}: how many nested jars so far, and their bytes. */
	private static void nested(JarEntries entries, JsonObject meta, Set<String> into, int depth, long[] count) throws IOException {
		JsonElement jars = meta.get("jars");
		if (jars == null || !jars.isJsonArray()) return;
		for (JsonElement e : jars.getAsJsonArray()) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("file")) continue;
			if (depth > MAX_DEPTH || ++count[0] > MAX_NESTED) throw new IOException("too many jars inside jars");
			String file = e.getAsJsonObject().get("file").getAsString();
			JarEntries.Entry entry = entries.entries().get(file);
			if (entry == null) continue;
			count[1] += entry.size();
			if (count[1] > MAX_NESTED_BYTES) throw new IOException("the jars inside it are too large");
			byte[] inner = entries.content(file, (int) MAX_JAR);
			if (inner == null) continue;
			JarEntries innerEntries = JarEntries.read(inner);
			JsonObject innerMeta = modJson(innerEntries);
			if (innerMeta == null) continue;
			provided(innerMeta, into);
			nested(innerEntries, innerMeta, into, depth + 1, count);
		}
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

	private static @Nullable JsonObject modJson(JarEntries entries) throws IOException {
		byte[] json = entries.content("fabric.mod.json", MAX_MOD_JSON);
		return json == null ? null : JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "unknown";
	}
}
