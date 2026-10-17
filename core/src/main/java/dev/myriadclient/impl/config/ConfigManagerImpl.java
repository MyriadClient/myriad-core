package dev.myriadclient.impl.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.config.ConfigManager;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Persists Myriad to {@code .minecraft/myriad/}, so that updating (or going back to an older version of) Myriad or an
 * addon loses nothing:
 * <ul>
 *   <li>entries for modules that aren't installed are carried over unchanged on every save;</li>
 *   <li>saved settings a module doesn't know (from a newer or older version) are kept, and settings saved at a newer
 *   {@code settingsVersion} keep that version, so they aren't migrated twice;</li>
 *   <li>each file records its {@code _format}; one from a newer Myriad is copied aside before it's overwritten;</li>
 *   <li>every file is written atomically, the first save of a session keeps the previous one as {@code .bak}, and a
 *   file that can't be read is kept as {@code .broken} and replaced by that backup.</li>
 * </ul>
 */
public final class ConfigManagerImpl implements ConfigManager {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Config");
	private static final long SAVE_DELAY_MS = 2000;
	/**
	 * The layout of Myriad's own config files, written into each as {@code _format}. Bump it when a file's structure
	 * changes and read older layouts on load; files from a newer format are copied aside before this version saves.
	 */
	static final int FORMAT = 1;
	static final String FORMAT_KEY = "_format";

	private final MyriadImpl myriad;
	private final Path root = FabricLoader.getInstance().getGameDir().resolve("myriad");
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Myriad Config IO");
		t.setDaemon(true);
		return t;
	});

	private String profile = "default";
	private String prefix = ".";
	private JsonObject rawModules = new JsonObject();
	private JsonObject rawAddonSettings = new JsonObject();
	private JsonObject rawKeyActions = new JsonObject();
	private boolean loading;
	private long dirtySince = -1;

	public ConfigManagerImpl(MyriadImpl myriad) {
		this.myriad = myriad;
	}

	@Override
	public Path root() {
		return root;
	}

	private Path profileDir(String name) {
		return root.resolve("profiles").resolve(name);
	}

	@Override
	public void markDirty() {
		if (!loading && dirtySince < 0) dirtySince = System.currentTimeMillis();
	}

	/** Called every tick; saves once things have been quiet for a moment. */
	public void tick() {
		if (dirtySince >= 0 && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
			dirtySince = -1;
			save(true);
		}
	}

	public void load() {
		loading = true;
		try {
			java.util.Optional.ofNullable(readObject(root.resolve("myriad.json"))).ifPresent(o -> {
				if (o.has("profile")) profile = sanitize(o.get("profile").getAsString());
				if (o.has("prefix")) prefix = o.get("prefix").getAsString();
				if (o.has("knownGrimServers") && o.get("knownGrimServers").isJsonArray()) {
					List<String> hosts = new ArrayList<>();
					for (JsonElement e : o.getAsJsonArray("knownGrimServers")) if (e.isJsonPrimitive()) hosts.add(e.getAsString());
					((dev.myriadclient.impl.service.AntiCheatTracker) myriad.antiCheat()).setKnownServers(hosts);
				}
				if (o.has("antiCheat")) {
					try {
						myriad.antiCheat().setMode(AntiCheat.Mode.valueOf(o.get("antiCheat").getAsString().toUpperCase(java.util.Locale.ROOT)));
					} catch (RuntimeException ignored) {
						// An unknown value (from a newer version) keeps the default.
					}
				}
				if (o.has("checkUpdates")) myriad.updates().setCheckEnabled(o.get("checkUpdates").getAsBoolean());
				if (o.has("keyActions") && o.get("keyActions").isJsonObject()) rawKeyActions = o.getAsJsonObject("keyActions");
			});
			for (KeyAction a : myriad.keyActions()) {
				JsonElement e = rawKeyActions.get(a.id().toString());
				if (e != null) a.setBind(Keybind.deserialize(e.getAsString()));
			}
			loadProfile(profile);
		} finally {
			loading = false;
		}
	}

	private void loadProfile(String name) {
		Path dir = profileDir(name);
		rawModules = java.util.Objects.requireNonNullElseGet(readObject(dir.resolve("modules.json")), JsonObject::new);
		for (Module m : myriad.modules()) {
			if (m.isMirror()) continue;
			JsonElement e = savedEntry(m);
			if (e == null || !e.isJsonObject()) {
				m.settings.resetAll();
				m.setEnabledSilently(false);
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			if (o.has("settings") && o.get("settings").isJsonObject()) {
				JsonObject settings = o.getAsJsonObject("settings");
				int version = intOr(o, "version", 1);
				if (version < m.settingsVersion()) {
					try {
						m.upgradeSavedSettings(settings, version);
					} catch (RuntimeException ex) {
						LOG.error("Could not update the saved settings of {} from version {}", m.id(), version, ex);
					}
				}
				m.settings.fromJson(settings);
			}
			m.setEnabledSilently(o.has("enabled") && o.get("enabled").getAsBoolean());
		}
		rawAddonSettings = java.util.Objects.requireNonNullElseGet(readObject(dir.resolve("addons.json")), JsonObject::new);
		for (AddonSettings a : myriad.addonSettings()) {
			JsonElement e = rawAddonSettings.get(a.id().toString());
			if (e != null && e.isJsonObject()) a.settings().fromJson(e.getAsJsonObject());
			else a.settings().resetAll();
		}
		JsonObject ui = readObject(dir.resolve("ui.json"));
		myriad.windowManager().load(ui);
	}

	/** A module's saved entry, moving it over from an id it was saved under before if it was renamed. */
	private JsonElement savedEntry(Module m) {
		String key = m.id().toString();
		if (rawModules.has(key)) return rawModules.get(key);
		for (MyriadId former : m.formerIds()) {
			JsonElement e = rawModules.remove(former.toString());
			if (e != null) {
				rawModules.add(key, e);
				return e;
			}
		}
		return null;
	}

	/** Builds the JSON on this (render) thread; writes on the IO thread if {@code async}. */
	private void save(boolean async) {
		JsonObject global = new JsonObject();
		global.addProperty(FORMAT_KEY, FORMAT);
		global.addProperty("profile", profile);
		global.addProperty("prefix", prefix);
		global.addProperty("antiCheat", myriad.antiCheat().mode().name().toLowerCase(java.util.Locale.ROOT));
		com.google.gson.JsonArray known = new com.google.gson.JsonArray();
		for (String h : myriad.antiCheat().knownServers()) known.add(h);
		global.add("knownGrimServers", known);
		global.addProperty("checkUpdates", myriad.updates().checkEnabled());
		JsonObject keys = rawKeyActions.deepCopy();
		for (KeyAction a : myriad.keyActions()) keys.addProperty(a.id().toString(), a.bind().serialize());
		global.add("keyActions", keys);

		JsonObject modules = rawModules.deepCopy();
		modules.addProperty(FORMAT_KEY, FORMAT);
		for (Module m : myriad.modules()) {
			if (m.isMirror()) continue;
			JsonElement before = modules.get(m.id().toString());
			JsonObject prev = before != null && before.isJsonObject() ? before.getAsJsonObject() : new JsonObject();
			JsonObject prevSettings = prev.has("settings") && prev.get("settings").isJsonObject() ? prev.getAsJsonObject("settings") : null;
			// Settings saved by a newer version of the module keep their version, so they aren't migrated again later.
			int version = Math.max(m.settingsVersion(), intOr(prev, "version", 1));
			JsonObject o = new JsonObject();
			o.addProperty("enabled", m.isEnabled());
			if (version > 1) o.addProperty("version", version);
			o.add("settings", m.settings.toJson(prevSettings));
			modules.add(m.id().toString(), o);
		}
		rawModules = modules;
		JsonObject addons = rawAddonSettings.deepCopy();
		addons.addProperty(FORMAT_KEY, FORMAT);
		for (AddonSettings a : myriad.addonSettings()) {
			JsonElement before = addons.get(a.id().toString());
			addons.add(a.id().toString(), a.settings().toJson(before != null && before.isJsonObject() ? before.getAsJsonObject() : null));
		}
		rawAddonSettings = addons;
		JsonObject ui = myriad.windowManager().save();
		Path dir = profileDir(profile);
		Runnable write = () -> {
			JsonFiles.write(root.resolve("myriad.json"), global);
			JsonFiles.write(dir.resolve("modules.json"), modules);
			if (addons.size() > 1) JsonFiles.write(dir.resolve("addons.json"), addons);
			if (ui != null) {
				ui.addProperty(FORMAT_KEY, FORMAT);
				JsonFiles.write(dir.resolve("ui.json"), ui);
			}
		};
		if (async) io.execute(write);
		else write.run();
	}

	@Override
	public void saveNow() {
		dirtySince = -1;
		save(false);
	}

	public void shutdown() {
		saveNow();
		io.shutdown();
	}

	@Override
	public String activeProfile() {
		return profile;
	}

	@Override
	public List<String> profiles() {
		List<String> list = new ArrayList<>();
		Path dir = root.resolve("profiles");
		if (Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted(Comparator.naturalOrder()).forEach(list::add);
			} catch (IOException e) {
				LOG.error("Could not list profiles", e);
			}
		}
		if (!list.contains(profile)) list.add(profile);
		return list;
	}

	@Override
	public void switchProfile(String name) {
		name = sanitize(name);
		if (name.isEmpty() || name.equals(profile)) return;
		saveNow();
		boolean exists = Files.isDirectory(profileDir(name));
		profile = name;
		if (exists) {
			loading = true;
			try {
				loadProfile(name);
			} finally {
				loading = false;
			}
		}
		// A new profile starts as a copy of the current state.
		saveNow();
	}

	@Override
	public void reload() {
		loading = true;
		try {
			loadProfile(profile);
		} finally {
			loading = false;
		}
	}

	@Override
	public boolean deleteProfile(String name) {
		name = sanitize(name);
		if (name.equals(profile)) return false;
		Path dir = profileDir(name);
		if (!Files.isDirectory(dir)) return false;
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			return true;
		} catch (Exception e) {
			LOG.error("Could not delete profile {}", name, e);
			return false;
		}
	}

	@Override
	public String commandPrefix() {
		return prefix;
	}

	@Override
	public void setCommandPrefix(String prefix) {
		if (prefix == null || prefix.isBlank()) return;
		this.prefix = prefix.trim();
		markDirty();
	}

	private static int intOr(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * Reads a config file, keeping a copy of one written by a newer Myriad ({@code <name>.format<N>.bak}): what this
	 * version doesn't understand would otherwise be lost when it saves.
	 */
	private static JsonObject readObject(Path file) {
		JsonObject o = JsonFiles.read(file).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
		if (o == null) return null;
		int format = intOr(o, FORMAT_KEY, 1);
		if (format > FORMAT) {
			Path copy = file.resolveSibling(file.getFileName() + ".format" + format + ".bak");
			try {
				if (!Files.exists(copy)) Files.copy(file, copy);
				LOG.warn("{} was written by a newer version of Myriad (format {}); kept a copy as {}", file, format, copy.getFileName());
			} catch (IOException e) {
				LOG.error("Could not keep a copy of {}", file, e);
			}
		}
		return o;
	}

	private static String sanitize(String name) {
		return name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
	}

	/** For tests and tooling: the raw module JSON including entries for modules not currently installed. */
	public JsonObject rawModules() {
		return rawModules;
	}

}
