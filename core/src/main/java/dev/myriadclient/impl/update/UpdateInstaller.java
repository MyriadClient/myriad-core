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
 * <p>An addon the player removes goes the same way: at exit its jar moves to {@code updates/old/<id>/}, and the next launch
 * says so.
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

	/**
	 * What the last session's updates came to: installed, or still waiting (the swap at exit didn't happen or failed);
	 * addons added; addons removed.
	 */
	record Outcome(List<Pending> installed, List<Pending> stillWaiting, List<Added> added, List<Removal> removed) {
		Outcome(List<Pending> installed, List<Pending> stillWaiting) {
			this(installed, stillWaiting, List.of(), List.of());
		}

		Outcome(List<Pending> installed, List<Pending> stillWaiting, List<Added> added) {
			this(installed, stillWaiting, added, List.of());
		}
	}

	/** An addon the player removed: its jar leaves the mods folder when the game exits. */
	record Removal(String id, String name, String version, Path jar) {
	}

	/** A new addon installed from a link, already in the mods folder: said once the game has loaded it. */
	record Added(String id, String version) {
	}

	/** An installed addon: its real version and jar. */
	record Installed(String version, Path jar) {
	}

	private final Path dir;
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	private final Map<String, Added> added = new LinkedHashMap<>();
	private final Map<String, Removal> removing = new LinkedHashMap<>();
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
		added.clear();
		removing.clear();
		List<Pending> done = new ArrayList<>(), waiting = new ArrayList<>();
		List<Added> loaded = new ArrayList<>();
		List<Removal> gone = new ArrayList<>();
		// Removed last session: gone now (said once), or still there (the move at exit failed) and tried again. The jar is
		// always the one installed now, never a path read from the file.
		JsonFiles.read(dir.resolve("pending.json")).filter(JsonElement::isJsonObject).map(e -> e.getAsJsonObject().get("removed"))
			.filter(JsonElement::isJsonArray).ifPresent(a -> {
				for (JsonElement e : a.getAsJsonArray()) {
					try {
						JsonObject o = e.getAsJsonObject();
						String id = o.get("id").getAsString(), version = o.get("version").getAsString();
						String name = o.has("name") ? o.get("name").getAsString() : id;
						if (!Names.isId(id)) continue;
						Installed now = installed.get(id);
						if (now == null) gone.add(new Removal(id, name, version, Path.of(id)));
						else if (now.version().equals(version)) removing.put(id, new Removal(id, name, version, now.jar()));
					} catch (RuntimeException ex) {
						LOG.warn("Skipping a broken entry in {}", dir.resolve("pending.json"), ex);
					}
				}
			});
		JsonFiles.read(dir.resolve("pending.json")).filter(JsonElement::isJsonObject).map(e -> e.getAsJsonObject().get("added"))
			.filter(JsonElement::isJsonArray).ifPresent(a -> {
				for (JsonElement e : a.getAsJsonArray()) {
					try {
						JsonObject o = e.getAsJsonObject();
						Added n = new Added(o.get("id").getAsString(), o.get("version").getAsString());
						Installed now = installed.get(n.id());
						if (now != null && now.version().equals(n.version())) loaded.add(n);
					} catch (RuntimeException ex) {
						LOG.warn("Skipping a broken entry in {}", dir.resolve("pending.json"), ex);
					}
				}
			});
		JsonFiles.read(dir.resolve("pending.json")).filter(JsonElement::isJsonObject).map(e -> e.getAsJsonObject().get("updates"))
			.filter(JsonElement::isJsonArray).ifPresent(a -> {
				for (JsonElement e : a.getAsJsonArray()) {
					try {
						JsonObject o = e.getAsJsonObject();
						Path staged = Names.fileIn(dir, o.get("staged").getAsString());
						if (staged == null) throw new IOException("its file is outside " + dir);
						Pending p = new Pending(o.get("id").getAsString(), o.get("version").getAsString(), staged, Path.of(o.get("replaces").getAsString()));
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
		if (!pending.isEmpty() || !removing.isEmpty()) hook();
		return new Outcome(done, waiting, loaded, gone);
	}

	/**
	 * Removes {@code id} when the game exits: {@code jar} (the one it was loaded from) moves to {@code updates/old/<id>/}.
	 * An update waiting for it is dropped.
	 */
	synchronized void remove(String id, String name, String version, Path jar) throws IOException {
		if (!Names.isId(id) || !Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) throw new IOException("there's no jar to remove");
		Pending p = pending.remove(id);
		if (p != null) Files.deleteIfExists(p.staged());
		removing.put(id, new Removal(id, name, version, jar));
		save();
		hook();
	}

	/** Keeps {@code id} after all. */
	synchronized void keep(String id) {
		if (removing.remove(id) != null) save();
	}

	/** Whether {@code id} is removed when the game exits. */
	synchronized boolean removing(String id) {
		return removing.containsKey(id);
	}

	/** Whether {@code id} was installed this session and loads at the next start. */
	synchronized boolean pendingAdded(String id) {
		return added.containsKey(id);
	}

	/** Notes a new addon installed into the mods folder, to say so once the next start has loaded it. */
	synchronized void added(String id, String version) {
		added.put(id, new Added(id, version));
		save();
	}

	/**
	 * The file name an update installs as, worked out here and never taken from the release (which could otherwise name
	 * any jar in mods to replace): the installed jar's name with the new version ({@code addon-1.0.jar} becomes
	 * {@code addon-1.1.jar}), or its name as it is when it has no version in it, or {@code <id>-<version>.jar} when that
	 * isn't a plain jar name.
	 */
	static String installName(String id, String installedVersion, String version, String installedFile) {
		String suffix = "-" + installedVersion + ".jar";
		String name = installedFile.endsWith(suffix) ? installedFile.substring(0, installedFile.length() - suffix.length()) + "-" + version + ".jar" : installedFile;
		return Names.isFile(name) ? name : id + "-" + version + ".jar";
	}

	/** Writes {@code jar} to the updates folder and schedules it to replace {@code replaces} when the game exits. */
	synchronized Pending stage(String id, String version, String fileName, byte[] jar, Path replaces) throws IOException {
		if (removing.containsKey(id)) throw new IOException("it's being removed");
		Path staged = Names.fileIn(dir, fileName);
		if (staged == null || !Names.isId(id) || !Names.isVersion(version)) throw new IOException("the release names an odd file");
		// An update replaces its own jar and nothing else: never another file in mods, nor another update waiting here.
		Path target = replaces.resolveSibling(fileName);
		if (!target.equals(replaces) && Files.exists(target)) throw new IOException("a file named " + fileName + " is already in mods");
		for (Pending other : pending.values()) {
			if (!other.id().equals(id) && (other.staged().equals(staged) || other.target().equals(target))) throw new IOException(fileName + " is another update's file");
		}
		Files.createDirectories(dir);
		Path part = staged.resolveSibling(fileName + ".part");
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
		JsonArray news = new JsonArray();
		for (Added a : added.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", a.id());
			o.addProperty("version", a.version());
			news.add(o);
		}
		JsonArray gone = new JsonArray();
		for (Removal r : removing.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", r.id());
			o.addProperty("name", r.name());
			o.addProperty("version", r.version());
			gone.add(o);
		}
		if (list.isEmpty() && news.isEmpty() && gone.isEmpty() && !Files.exists(dir.resolve("pending.json"))) return;
		JsonObject root = new JsonObject();
		root.addProperty("_format", 2);
		root.add("updates", list);
		root.add("added", news);
		root.add("removed", gone);
		JsonFiles.write(dir.resolve("pending.json"), root);
	}

	private void hook() {
		if (hooked || !installAtExit) return;
		hooked = true;
		Runtime.getRuntime().addShutdownHook(new Thread(this::applyAtExit, "Myriad Updates"));
	}

	private void applyAtExit() {
		List<Pending> list;
		List<Removal> gone;
		synchronized (this) {
			// An update for an addon being removed never goes in, whatever staged it.
			list = pending.values().stream().filter(p -> !removing.containsKey(p.id())).toList();
			gone = List.copyOf(removing.values());
		}
		if (list.isEmpty() && gone.isEmpty()) return;
		Path oldDir = dir.resolve("old");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
			try {
				new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand",
					encode(windowsScript(list, gone, oldDir, ProcessHandle.current().pid()))).start();
			} catch (IOException e) {
				LOG.error("Could not start the updater; the update will be tried again next time", e);
			}
		} else {
			apply(list, oldDir);
			removeAll(gone, oldDir);
		}
	}

	/** Moves each removed addon's jar to {@code oldDir/<id>/}. Returns those that went. */
	static List<Removal> removeAll(Collection<Removal> list, Path oldDir) {
		List<Removal> done = new ArrayList<>();
		for (Removal r : list) {
			try {
				if (!Files.isRegularFile(r.jar())) continue;
				Path keep = oldDir.resolve(r.id());
				Files.createDirectories(keep);
				try (Stream<Path> s = Files.list(keep)) {
					for (Path f : s.toList()) Files.deleteIfExists(f);
				}
				Files.move(r.jar(), keep.resolve(r.jar().getFileName()), StandardCopyOption.REPLACE_EXISTING);
				done.add(r);
			} catch (IOException e) {
				LOG.error("Could not remove {}; it will be tried again next time", r.id(), e);
			}
		}
		return done;
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
		return windowsScript(list, List.of(), oldDir, pid);
	}

	/** {@link #windowsScript(List, Path, long)}, and the removals' moves after the updates. */
	static String windowsScript(List<Pending> list, List<Removal> gone, Path oldDir, long pid) {
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
		for (Removal r : gone) {
			Path keep = oldDir.resolve(r.id());
			sb.append("try {\n");
			sb.append("  if (Test-Path -LiteralPath ").append(quote(r.jar())).append(") {\n");
			sb.append("    New-Item -ItemType Directory -Force -Path ").append(quote(keep)).append(" | Out-Null\n");
			sb.append("    Get-ChildItem -LiteralPath ").append(quote(keep)).append(" | Remove-Item -Force\n");
			sb.append("    Move-Item -LiteralPath ").append(quote(r.jar())).append(" -Destination ").append(quote(keep.resolve(r.jar().getFileName()))).append(" -Force\n");
			sb.append("  }\n");
			sb.append("} catch {}\n");
		}
		return sb.toString();
	}

	/** A PowerShell single-quoted string: nothing inside is expanded, and {@code '} is doubled. */
	private static String quote(Path p) {
		// PowerShell takes the curly single quotes as quotes too, so each is doubled like ' is.
		String text = p.toAbsolutePath().toString();
		for (String q : new String[] {"'", "\u2018", "\u2019", "\u201A", "\u201B"}) text = text.replace(q, q + q);
		return "'" + text + "'";
	}

	/** PowerShell's {@code -EncodedCommand} takes Base64 of UTF-16LE, which sidesteps command-line quoting. */
	private static String encode(String script) {
		return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
	}
}
