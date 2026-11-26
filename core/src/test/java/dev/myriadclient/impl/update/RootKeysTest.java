package dev.myriadclient.impl.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** Core's jar must list exactly the keys it's updated with: the signer refuses others, and an update dropping them is refused. */
class RootKeysTest {
	@Test
	void twoDistinctKeys() {
		assertEquals(2, RootKeys.ALL.size());
		assertEquals(2, RootKeys.ALL.stream().map(DevKey::id).distinct().count());
	}

	@Test
	void coreJarListsTheRootKeys() throws IOException {
		JsonObject meta = coreModJson();
		List<String> listed = meta.getAsJsonObject("custom").getAsJsonObject("myriad").getAsJsonArray("keys").asList().stream()
				.map(JsonElement::getAsString).toList();
		assertEquals(RootKeys.ALL.stream().map(DevKey::text).toList(), listed);
	}

	/** Core's own fabric.mod.json, among the classpath's (Fabric's jars have theirs too). */
	private static JsonObject coreModJson() throws IOException {
		for (URL url : Collections.list(RootKeysTest.class.getClassLoader().getResources("fabric.mod.json"))) {
			try (InputStream in = url.openStream()) {
				JsonObject meta = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				if (meta.has("id") && meta.get("id").getAsString().equals("myriad")) return meta;
			}
		}
		return fail("core's fabric.mod.json isn't on the classpath");
	}
}
