package dev.myriadclient.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myriadclient.api.module.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Crash-loop safe mode: when the game crashed twice in a row within a minute of starting, Myriad starts with every
 * module off (as {@code .panic} leaves them) and asks whether to turn back on the ones that were on. A file in
 * Myriad's folder says a run is going: written at start with the streak of early crashes so far, marked once the game
 * has ticked for a minute, deleted when it stops cleanly. Found unmarked at start, the last run ended early without
 * stopping cleanly: a crash, or the game killed in its first minute.
 */
final class SafeMode {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad");
	/** A run that lasts this long wasn't a crash loop, however it ends. */
	static final long SURVIVED_MS = 60_000;
	/** Early crashes in a row that start the next run in safe mode. */
	static final int CRASHES = 2;

	private final Path file;
	private final long startedMs;
	private final int earlyCrashes;
	private boolean survived;

	SafeMode(Path file, long nowMs) {
		this.file = file;
		this.startedMs = nowMs;
		earlyCrashes = lastRunEarlyCrashes();
		write(false);
	}

	/** The early crashes in a row before this run, counting the last one if it was one. */
	private int lastRunEarlyCrashes() {
		if (!Files.exists(file)) return 0;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (o.has("survived") && o.get("survived").getAsBoolean()) return 0;
			return (o.has("early") ? o.get("early").getAsInt() : 0) + 1;
		} catch (IOException | RuntimeException e) {
			// Unreadable: it was there, so the last run didn't stop cleanly, and nothing more is known.
			return 1;
		}
	}

	/** Whether this run starts with every module off. */
	boolean active() {
		return earlyCrashes >= CRASHES;
	}

	int earlyCrashes() {
		return earlyCrashes;
	}

	/** Each client tick: a minute in, the run is marked as one that didn't crash early. */
	void tick(long nowMs) {
		if (survived || nowMs - startedMs < SURVIVED_MS) return;
		survived = true;
		write(true);
	}

	/** The game is stopping cleanly. */
	void stopped() {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOG.warn("Couldn't remove {}", file, e);
		}
	}

	/** Turns every module that's on off, as {@code .panic} does, and returns them. */
	static List<Module> turnOff(Iterable<Module> modules) {
		List<Module> were = new ArrayList<>();
		for (Module m : modules) {
			if (!m.isEnabled() || m.isMirror()) continue;
			were.add(m);
			m.setEnabledSilently(false);
		}
		return were;
	}

	private void write(boolean survived) {
		JsonObject o = new JsonObject();
		o.addProperty("started", startedMs);
		o.addProperty("early", earlyCrashes);
		o.addProperty("survived", survived);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, o.toString());
		} catch (IOException e) {
			// Safe mode is a net, never a reason not to start.
			LOG.warn("Couldn't write {}", file, e);
		}
	}
}
