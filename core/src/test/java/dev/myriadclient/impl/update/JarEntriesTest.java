package dev.myriadclient.impl.update;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Jars built to read one way to one tool and another way to the loader are refused. */
class JarEntriesTest {
	private static byte[] zip(String... namesAndContents) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (int i = 0; i < namesAndContents.length; i += 2) {
				zip.putNextEntry(new ZipEntry(namesAndContents[i]));
				zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
			}
		}
		return out.toByteArray();
	}

	/** Replaces the {@code nth} (0-based) occurrence of {@code from} with {@code to} (same length). */
	private static byte[] patch(byte[] data, String from, String to, int nth) {
		byte[] f = from.getBytes(StandardCharsets.UTF_8), t = to.getBytes(StandardCharsets.UTF_8);
		byte[] out = data.clone();
		int seen = 0;
		outer:
		for (int i = 0; i <= out.length - f.length; i++) {
			for (int j = 0; j < f.length; j++) if (out[i + j] != f[j]) continue outer;
			if (seen++ == nth) {
				System.arraycopy(t, 0, out, i, t.length);
				return out;
			}
		}
		throw new IllegalArgumentException("not found");
	}

	@Test
	void readsAPlainJar() throws IOException {
		JarEntries j = JarEntries.read(zip("fabric.mod.json", "{\"id\": \"a\"}", "a/B.class", "xyz"));
		assertArrayEquals("{\"id\": \"a\"}".getBytes(StandardCharsets.UTF_8), j.content("fabric.mod.json", 1000));
		assertEquals(2, j.entries().size());
	}

	@Test
	void refusesTwoEntriesWithOneName() throws IOException {
		byte[] z = zip("fabric.mod.json", "{\"id\": \"good\"}", "fabric.mod.jsoX", "{\"id\": \"evil\"}");
		// Local and central names both renamed: two entries named fabric.mod.json.
		byte[] dup = patch(patch(z, "fabric.mod.jsoX", "fabric.mod.json", 0), "fabric.mod.jsoX", "fabric.mod.json", 0);
		assertEquals("the jar has two entries named fabric.mod.json", assertThrows(IOException.class, () -> JarEntries.read(dup)).getMessage());
	}

	@Test
	void refusesALocalHeaderThatDisagreesWithTheDirectory() throws IOException {
		byte[] z = zip("fabric.mod.jsoX", "{\"id\": \"evil\"}", "other", "x");
		// Only the central directory's name changed (its second occurrence): a stream reader sees another name.
		byte[] mismatch = patch(z, "fabric.mod.jsoX", "fabric.mod.json", 1);
		assertEquals("the jar's entries don't match its directory", assertThrows(IOException.class, () -> JarEntries.read(mismatch)).getMessage());
	}

	@Test
	void refusesOddNames() throws IOException {
		for (String n : new String[] {"../evil.class", "/abs.class", "a//b.class", "a\\b.class", "./fabric.mod.json"}) {
			byte[] z = zip(n, "x");
			assertThrows(IOException.class, () -> JarEntries.read(z), n);
		}
	}

	@Test
	void refusesWhatIsntAZip() {
		assertThrows(IOException.class, () -> JarEntries.read(new byte[0]));
		assertThrows(IOException.class, () -> JarEntries.read("<html>error</html>".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void refusesACutShortJar() throws IOException {
		byte[] z = zip("fabric.mod.json", "{}", "b", "c");
		byte[] cut = java.util.Arrays.copyOfRange(z, 10, z.length);
		assertThrows(IOException.class, () -> JarEntries.read(cut));
	}

	@Test
	void capsWhatItReads() throws IOException {
		JarEntries j = JarEntries.read(zip("fabric.mod.json", "x".repeat(5000)));
		assertEquals("fabric.mod.json is too large", assertThrows(IOException.class, () -> j.content("fabric.mod.json", 4096)).getMessage());
	}

	@Test
	void refusesAnEndRecordThatDoesntEndTheFile() throws IOException {
		byte[] z = zip("fabric.mod.json", "{}");
		byte[] trailing = java.util.Arrays.copyOf(z, z.length + 3);
		assertThrows(IOException.class, () -> JarEntries.read(trailing));
		// A comment that hides another end record.
		byte[] hidden = java.util.Arrays.copyOf(z, z.length + 30);
		hidden[z.length - 2] = 30;
		hidden[z.length] = 'P';
		hidden[z.length + 1] = 'K';
		hidden[z.length + 2] = 5;
		hidden[z.length + 3] = 6;
		assertThrows(IOException.class, () -> JarEntries.read(hidden));
	}
}
