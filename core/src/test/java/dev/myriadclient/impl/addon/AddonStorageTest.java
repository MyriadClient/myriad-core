package dev.myriadclient.impl.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AddonStorageTest {
	@TempDir
	Path dir;

	@Test
	void unversionedDataIsVersionOneAndUpgradesStepByStep() {
		AddonStorageImpl storage = new AddonStorageImpl(dir);
		JsonArray old = new JsonArray();
		old.add("a");
		storage.writeJson("data.json", old);
		List<Integer> steps = new ArrayList<>();
		JsonElement read = storage.readJson("data.json", 3, (from, data) -> {
			steps.add(from);
			data.getAsJsonArray().add(new JsonPrimitive("v" + (from + 1)));
			return data;
		}).orElseThrow();
		assertEquals(List.of(1, 2), steps);
		assertEquals(3, read.getAsJsonArray().size());
	}

	@Test
	void currentAndNewerDataIsReadAsItIs() {
		AddonStorageImpl storage = new AddonStorageImpl(dir);
		storage.writeJson("data.json", new JsonPrimitive("x"), 5);
		assertEquals(new JsonPrimitive("x"), storage.readJson("data.json", 5, (f, d) -> {
			throw new AssertionError("no upgrade expected");
		}).orElseThrow());
		assertEquals(new JsonPrimitive("x"), storage.readJson("data.json", 2, (f, d) -> {
			throw new AssertionError("no upgrade expected");
		}).orElseThrow());
	}
}
