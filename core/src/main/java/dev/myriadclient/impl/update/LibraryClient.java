package dev.myriadclient.impl.update;

import dev.myriadclient.impl.MyriadApi;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This game's view of the Myriad Library: the last index that checked out (kept in {@code myriad/updates}, so Browse and
 * Library updates work offline until it expires), what's remembered so no later index can take it backwards
 * ({@link LibraryState}), and which addons are pinned to the Library. The index is fetched only when Browse opens or
 * updates are checked, as one file: what's searched for never leaves the game.
 */
final class LibraryClient {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Library");
	private final Path stateFile, cacheFile;
	private LibraryState state;
	private LibraryIndex.@Nullable Index index;
	private @Nullable String problem;
	private @Nullable CompletableFuture<LibraryIndex.Index> loading;

	LibraryClient(Path dir) {
		this.stateFile = dir.resolve("library.json");
		this.cacheFile = dir.resolve("library-index.json");
		this.state = LibraryState.read(stateFile);
		if (Files.isRegularFile(cacheFile)) {
			try {
				index = LibraryIndex.verify(Files.readString(cacheFile, StandardCharsets.UTF_8), state, now(), roots()).index();
			} catch (IOException e) {
				LOG.info("The saved Library index doesn't check out any more: {}", e.getMessage());
			}
		}
	}

	/** Myriad's root keys; with {@code -Dmyriad.dev}, also a test key from {@code -Dmyriad.library.testRoot}. */
	static List<DevKey> roots() {
		List<DevKey> out = new ArrayList<>(RootKeys.ALL);
		if (Boolean.getBoolean("myriad.dev")) {
			DevKey test = DevKey.parse(System.getProperty("myriad.library.testRoot"));
			if (test != null) out.add(test);
		}
		return out;
	}

	synchronized LibraryIndex.@Nullable Index index() {
		return index;
	}

	/** Why the last refresh failed, or null. */
	synchronized @Nullable String problem() {
		return problem;
	}

	synchronized boolean loading() {
		return loading != null && !loading.isDone();
	}

	/** Fetches the index again (asking only for changes); the future fails when there's no usable index at all. */
	synchronized CompletableFuture<LibraryIndex.Index> refresh() {
		if (loading != null && !loading.isDone()) return loading;
		URI uri = URI.create(MyriadApi.API + "/v1/index.json");
		String why = Fetch.refused(uri);
		if (why != null) return CompletableFuture.failedFuture(new IOException(why));
		String etag = index != null ? state.etag : null;
		loading = Fetch.conditional(uri, LibraryIndex.MAX_INDEX, etag, "application/json").thenApply(got -> {
			synchronized (this) {
				try {
					if (got.status() == 304 && index != null) {
						// Unchanged, but time has passed: it may have expired since.
						index = LibraryIndex.verify(Files.readString(cacheFile, StandardCharsets.UTF_8), state, now(), roots()).index();
					} else {
						String text = new String(got.body(), StandardCharsets.UTF_8);
						LibraryIndex.Verified v = LibraryIndex.verify(text, state, now(), roots());
						v.state().etag = got.etag();
						state = v.state();
						index = v.index();
						save(text);
					}
					problem = index.stale() ? "The Library's information is out of date; nothing is installed from it until it's fresh" : null;
					return index;
				} catch (IOException e) {
					problem = e.getMessage();
					LOG.warn("The Library index was refused: {}", e.getMessage());
					if (index != null) return index;
					throw new java.util.concurrent.CompletionException(e);
				}
			}
		}).exceptionally(err -> {
			synchronized (this) {
				if (problem == null) problem = UpdateManager.reasonOf(err);
				if (index != null) return index;
			}
			throw err instanceof java.util.concurrent.CompletionException c ? c : new java.util.concurrent.CompletionException(err);
		});
		return loading;
	}

	private void save(String text) {
		try {
			Files.createDirectories(cacheFile.getParent());
			Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOG.warn("Could not save the Library index: {}", e.getMessage());
		}
		state.write(stateFile);
	}

	synchronized boolean pinned(String id) {
		return state.pins.contains(id);
	}

	/** Pins {@code id}: from now on it updates only through the Library. */
	synchronized void pin(String id) {
		if (Names.isId(id) && state.pins.add(id)) state.write(stateFile);
	}

	/** Unpins {@code id} (the player's choice): it updates from its own source again. */
	synchronized void unpin(String id) {
		if (state.pins.remove(id)) state.write(stateFile);
	}

	/** The listed release with exactly this jar, or null: what a badge needs (never the id alone). */
	synchronized LibraryIndex.@Nullable Entry vouching(String id, String sha256) {
		if (index == null) return null;
		LibraryIndex.Entry e = index.entry(id);
		if (e == null) return null;
		for (LibraryIndex.Offered o : e.versions()) if (o.version().sha256().equals(sha256)) return e;
		return null;
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}
}
