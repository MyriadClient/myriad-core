package dev.myriadclient.impl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** JSON reads that survive a broken file, and atomic writes that keep a backup of the session before. */
public final class JsonFiles {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Config");
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private JsonFiles() {
	}

	/**
	 * Reads {@code file}. If it can't be parsed, it's kept as {@code <name>.broken} and the backup from the session before
	 * ({@code <name>.bak}, see {@link #write}) is read instead, if there is one.
	 */
	public static Optional<JsonElement> read(Path file) {
		if (!Files.isRegularFile(file)) return Optional.empty();
		try {
			return Optional.of(parse(file));
		} catch (Exception e) {
			try {
				Files.copy(file, sibling(file, ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
			Path backup = sibling(file, ".bak");
			if (Files.isRegularFile(backup)) {
				try {
					JsonElement restored = parse(backup);
					LOG.error("Could not read {}; kept it as {}.broken and restored the backup from the session before", file, file.getFileName(), e);
					backedUp.add(file);
					return Optional.of(restored);
				} catch (Exception ignored) {
				}
			}
			LOG.error("Could not read {}; kept it as {}.broken and using defaults", file, file.getFileName(), e);
			return Optional.empty();
		}
	}

	private static JsonElement parse(Path file) throws IOException {
		return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
	}

	private static Path sibling(Path file, String suffix) {
		return file.resolveSibling(file.getFileName() + suffix);
	}

	/** Files already backed up this session. */
	private static final Set<Path> backedUp = ConcurrentHashMap.newKeySet();

	/**
	 * Writes {@code json} atomically (to a temporary file, then moved into place). The first write to a file each session
	 * first copies what was there to {@code <name>.bak}, so a bad save never costs more than one session.
	 */
	public static void write(Path file, JsonElement json) {
		try {
			Files.createDirectories(file.getParent());
			if (backedUp.add(file) && Files.isRegularFile(file)) {
				try {
					parse(file);
					Files.copy(file, sibling(file, ".bak"), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
					// Don't replace a good backup with a broken file.
				}
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOG.error("Could not write {}", file, e);
		}
	}
}
