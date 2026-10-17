package dev.myriadclient.impl.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.impl.config.JsonFiles;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Downloaded updates wait in {@code .minecraft/myriad/updates/} and replace the installed jars when the game exits, so the
 * next launch runs them. The jar they replace is kept in {@code updates/old/<id>/} (the latest one per mod) to go back by
 * hand. What's waiting is listed in {@code updates/pending.json}, and the next launch checks it went in.
 *
 * <p>On Windows a running jar can't be moved, so a hidden PowerShell waits for the game's process to end and moves them.
 */
final class UpdateInstaller {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Updates");

	/** An update waiting for the game to exit: {@code staged} goes where {@code replaces} is. */
	record Pending(String id, String version, Path staged, Path replaces) {
		Path target() {
			return replaces.resolveSibling(staged.getFileName());
		}
	}

	/** What the last session's updates came to: installed, or still waiting (the swap at exit didn't happen or failed). */
	record Outcome(List<Pending> installed, List<Pending> stillWaiting) {
	}

	/** An installed addon: its real version and jar. */
	record Installed(String version, Path jar) {
	}

	private final Path dir;
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	/** False in tests: nothing is installed when the JVM exits. */
	private final boolean installAtExit;
	private boolean hooked;

	UpdateInstaller(Path dir) {
		this(dir, true);
	}

	UpdateInstaller(Path dir, boolean installAtExit) {
		this.dir = dir;
		this.installAtExit = installAtExit;
	}

	Path dir() {
		return dir;
	}

	synchronized @Nullable Pending pending(String id) {
		return pending.get(id);
	}

	/**
	 * Reads what the last session left waiting and compares it with what's installed now. Updates still waiting are kept
	 * (and tried again at exit); ones for mods no longer installed are dropped.
	 */
	synchronized Outcome reconcile(Map<String, Installed> installed) {
		pending.clear();
		List<Pending> done = new ArrayList<>(), waiting = new ArrayList<>();
		JsonFiles.read(dir.resolve("pending.json")).filter(JsonElement::isJsonObject).map(e -> e.getAsJsonObject().get("updates"))
			.filter(JsonElement::isJsonArray).ifPresent(a -> {
				for (JsonElement e : a.getAsJsonArray()) {
					try {
						JsonObject o = e.getAsJsonObject();
						Pending p = new Pending(o.get("id").getAsString(), o.get("version").getAsString(), dir.resolve(o.get("staged").getAsString()),
							Path.of(o.get("replaces").getAsString()));
						Installed now = installed.get(p.id());
						if (now != null && now.version().equals(p.version())) {
							done.add(p);
							Files.deleteIfExists(p.staged());
						} else if (now != null && Files.isRegularFile(p.staged())) {
							Pending again = new Pending(p.id(), p.version(), p.staged(), now.jar());
							pending.put(p.id(), again);
							waiting.add(again);
						} else {
							Files.deleteIfExists(p.staged());
						}
					} catch (IOException | RuntimeException ex) {
						LOG.warn("Skipping a broken entry in {}", dir.resolve("pending.json"), ex);
					}
				}
			});
		save();
		if (!pending.isEmpty()) hook();
		return new Outcome(done, waiting);
	}

	/** Writes {@code jar} to the updates folder and schedules it to replace {@code replaces} when the game exits. */
	synchronized Pending stage(String id, String version, String fileName, byte[] jar, Path replaces) throws IOException {
		Files.createDirectories(dir);
		Path staged = dir.resolve(fileName);
		Path part = dir.resolve(fileName + ".part");
		Files.write(part, jar);
		Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
		Pending old = pending.get(id);
		if (old != null && !old.staged().equals(staged)) Files.deleteIfExists(old.staged());
		Pending p = new Pending(id, version, staged, replaces);
		pending.put(id, p);
		save();
		hook();
		return p;
	}

	private void save() {
		JsonArray list = new JsonArray();
		for (Pending p : pending.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", p.id());
			o.addProperty("version", p.version());
			o.addProperty("staged", p.staged().getFileName().toString());
			o.addProperty("replaces", p.replaces().toAbsolutePath().toString());
			list.add(o);
		}
		if (list.isEmpty() && !Files.exists(dir.resolve("pending.json"))) return;
		JsonObject root = new JsonObject();
		root.addProperty("_format", 1);
		root.add("updates", list);
		JsonFiles.write(dir.resolve("pending.json"), root);
	}

	private void hook() {
		if (hooked || !installAtExit) return;
		hooked = true;
		Runtime.getRuntime().addShutdownHook(new Thread(this::applyAtExit, "Myriad Updates"));
	}

	private void applyAtExit() {
		List<Pending> list;
		synchronized (this) {
			list = List.copyOf(pending.values());
		}
		if (list.isEmpty()) return;
		Path oldDir = dir.resolve("old");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
			try {
				new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand",
					encode(windowsScript(list, oldDir, ProcessHandle.current().pid()))).start();
			} catch (IOException e) {
				LOG.error("Could not start the updater; the update will be tried again next time", e);
			}
		} else {
			apply(list, oldDir);
		}
	}

	/** Moves each installed jar to {@code oldDir/<id>/} and its update into its place. Returns those that went in. */
	static List<Pending> apply(Collection<Pending> list, Path oldDir) {
		List<Pending> done = new ArrayList<>();
		for (Pending p : list) {
			try {
				if (!Files.isRegularFile(p.staged()) || !Files.isRegularFile(p.replaces())) continue;
				Path keep = oldDir.resolve(p.id());
				Files.createDirectories(keep);
				try (Stream<Path> s = Files.list(keep)) {
					for (Path f : s.toList()) Files.deleteIfExists(f);
				}
				Path backup = keep.resolve(p.replaces().getFileName());
				Files.move(p.replaces(), backup, StandardCopyOption.REPLACE_EXISTING);
				try {
					Files.move(p.staged(), p.target(), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					Files.move(backup, p.replaces(), StandardCopyOption.REPLACE_EXISTING);
					throw e;
				}
				done.add(p);
			} catch (IOException e) {
				LOG.error("Could not install the update of {}; it will be tried again next time", p.id(), e);
			}
		}
		return done;
	}

	/** The same moves as {@link #apply}, as PowerShell that first waits for process {@code pid} to exit. */
	static String windowsScript(List<Pending> list, Path oldDir, long pid) {
		StringBuilder sb = new StringBuilder("$ErrorActionPreference = 'Stop'\n");
		sb.append("Wait-Process -Id ").append(pid).append(" -ErrorAction SilentlyContinue\n");
		sb.append("Start-Sleep -Milliseconds 500\n");
		for (Pending p : list) {
			Path keep = oldDir.resolve(p.id());
			String backup = quote(keep.resolve(p.replaces().getFileName()));
			sb.append("try {\n");
			sb.append("  if ((Test-Path -LiteralPath ").append(quote(p.staged())).append(") -and (Test-Path -LiteralPath ").append(quote(p.replaces())).append(")) {\n");
			sb.append("    New-Item -ItemType Directory -Force -Path ").append(quote(keep)).append(" | Out-Null\n");
			sb.append("    Get-ChildItem -LiteralPath ").append(quote(keep)).append(" | Remove-Item -Force\n");
			sb.append("    Move-Item -LiteralPath ").append(quote(p.replaces())).append(" -Destination ").append(backup).append(" -Force\n");
			sb.append("    try { Move-Item -LiteralPath ").append(quote(p.staged())).append(" -Destination ").append(quote(p.target())).append(" -Force }\n");
			sb.append("    catch { Move-Item -LiteralPath ").append(backup).append(" -Destination ").append(quote(p.replaces())).append(" -Force }\n");
			sb.append("  }\n");
			sb.append("} catch {}\n");
		}
		return sb.toString();
	}

	/** A PowerShell single-quoted string: nothing inside is expanded, and {@code '} is doubled. */
	private static String quote(Path p) {
		return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
	}

	/** PowerShell's {@code -EncodedCommand} takes Base64 of UTF-16LE, which sidesteps command-line quoting. */
	private static String encode(String script) {
		return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
	}
}
