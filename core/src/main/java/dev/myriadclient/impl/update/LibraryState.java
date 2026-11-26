package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.impl.config.JsonFiles;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What this game remembers about the Myriad Library between sessions ({@code myriad/updates/library.json}), so a later
 * index can never take it backwards: the newest index accepted (when it was made, and its body's hash), each listing's
 * newest revision, every yank and every revoked key ever seen, and which addons are pinned to the Library (they update
 * only through it). Plus the ETag of the cached index, to ask for it again cheaply.
 */
final class LibraryState {
	long issued;
	@Nullable String bodySha256;
	final Map<String, Long> revisions = new HashMap<>();
	/** {@code id/version/sha256} of every yanked release seen. */
	final Set<String> yanks = new HashSet<>();
	long revocationSeq;
	/** {@code ed25519:...} of every revoked Library key seen. */
	final Set<String> revoked = new HashSet<>();
	/** Ids of addons that update only through the Library. */
	final Set<String> pins = new LinkedHashSet<>();
	@Nullable String etag;

	static String yankKey(String id, String version, String sha256) {
		return id + "/" + version + "/" + sha256;
	}

	LibraryState copy() {
		LibraryState s = new LibraryState();
		s.issued = issued;
		s.bodySha256 = bodySha256;
		s.revisions.putAll(revisions);
		s.yanks.addAll(yanks);
		s.revocationSeq = revocationSeq;
		s.revoked.addAll(revoked);
		s.pins.addAll(pins);
		s.etag = etag;
		return s;
	}

	static LibraryState read(Path file) {
		LibraryState s = new LibraryState();
		JsonElement e = JsonFiles.read(file).orElse(null);
		if (e == null || !e.isJsonObject()) return s;
		JsonObject o = e.getAsJsonObject();
		s.issued = number(o, "issued");
		s.bodySha256 = string(o, "bodySha256");
		if (o.get("revisions") instanceof JsonObject r) {
			for (Map.Entry<String, JsonElement> en : r.entrySet()) {
				if (Names.isId(en.getKey()) && en.getValue().isJsonPrimitive() && en.getValue().getAsJsonPrimitive().isNumber()) {
					s.revisions.put(en.getKey(), en.getValue().getAsLong());
				}
			}
		}
		strings(o, "yanks", s.yanks);
		s.revocationSeq = number(o, "revocationSeq");
		strings(o, "revoked", s.revoked);
		for (String id : stringsOf(o, "pins")) if (Names.isId(id)) s.pins.add(id);
		s.etag = string(o, "etag");
		return s;
	}

	void write(Path file) {
		JsonObject o = new JsonObject();
		o.addProperty("issued", issued);
		if (bodySha256 != null) o.addProperty("bodySha256", bodySha256);
		JsonObject r = new JsonObject();
		revisions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(en -> r.addProperty(en.getKey(), en.getValue()));
		o.add("revisions", r);
		o.add("yanks", array(yanks));
		o.addProperty("revocationSeq", revocationSeq);
		o.add("revoked", array(revoked));
		JsonArray p = new JsonArray();
		pins.forEach(p::add);
		o.add("pins", p);
		if (etag != null) o.addProperty("etag", etag);
		JsonFiles.write(file, o);
	}

	private static JsonArray array(Set<String> set) {
		JsonArray a = new JsonArray();
		set.stream().sorted().forEach(a::add);
		return a;
	}

	private static long number(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
	}

	private static @Nullable String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static void strings(JsonObject o, String key, Set<String> into) {
		into.addAll(stringsOf(o, key));
	}

	private static Set<String> stringsOf(JsonObject o, String key) {
		Set<String> out = new LinkedHashSet<>();
		if (o.get(key) instanceof JsonArray a) {
			for (JsonElement e : a) if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) out.add(e.getAsString());
		}
		return out;
	}
}
