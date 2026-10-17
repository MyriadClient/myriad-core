package dev.myriadclient.impl.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFilesTest {
	@TempDir
	Path dir;

	private static JsonObject obj(int n) {
		JsonObject o = new JsonObject();
		o.addProperty("n", n);
		return o;
	}

	@Test
	void firstWriteOfASessionKeepsTheFileBefore() throws Exception {
		Path file = dir.resolve("a.json");
		Files.writeString(file, "{\"n\":1}");
		JsonFiles.write(file, obj(2));
		JsonFiles.write(file, obj(3));
		assertEquals(obj(1), JsonParser.parseString(Files.readString(dir.resolve("a.json.bak"))));
		assertEquals(obj(3), JsonFiles.read(file).orElseThrow());
	}

	@Test
	void aBrokenFileIsKeptAsideAndTheBackupRead() throws Exception {
		Path file = dir.resolve("b.json");
		Files.writeString(dir.resolve("b.json.bak"), "{\"n\":7}");
		Files.writeString(file, "{\"n\":");
		assertEquals(obj(7), JsonFiles.read(file).orElseThrow());
		assertTrue(Files.exists(dir.resolve("b.json.broken")));
	}

	@Test
	void aBrokenFileWithoutABackupReadsAsNothing() throws Exception {
		Path file = dir.resolve("c.json");
		Files.writeString(file, "not json {");
		assertTrue(JsonFiles.read(file).isEmpty());
	}
}
