package dev.myriadclient.impl.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.config.JsonFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Owns the themes: the built-in presets (registered by addons) and user themes stored as files in
 * {@code .minecraft/myriad/themes/}. Applying a theme writes every value of {@link ThemeSettings#settings}; editing
 * those values saves straight back into the active theme.
 * <ul>
 * <li>A user theme file holds every value, so it can be shared: drop it into someone else's themes folder.</li>
 * <li>Editing a built-in preset saves only what differs from the preset, in {@code <namespace>.<preset>.json};
 * resetting the preset deletes that file. An edited live theme (one that follows a system theme) still follows it
 * for everything you didn't change.</li>
 * </ul>
 */
public final class ThemeManager {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Themes");
	private static final int FORMAT = 1;
	public static final String USER_NAMESPACE = "user";
	public static final MyriadId DEFAULT = MyriadId.of("myriad", "default");

	/** One theme in the list. Built-ins have a {@link #preset}; user themes don't. */
	public static final class Entry {
		private final MyriadId id;
		private final @Nullable Theme preset;
		private String name;
		private String author = "";
		/** User themes: every value. Built-ins: only the values changed from the preset. */
		private JsonObject values = new JsonObject();
		private int[] swatch;

		private Entry(MyriadId id, @Nullable Theme preset, String name) {
			this.id = id;
			this.preset = preset;
			this.name = name;
		}

		public MyriadId id() {
			return id;
		}

		public String name() {
			return name;
		}

		public String author() {
			return author;
		}

		/** The registered preset this entry is (or is edits to); null for user themes. */
		public @Nullable Theme preset() {
			return preset;
		}

		public boolean isUser() {
			return preset == null;
		}

		/** A built-in preset with saved edits. */
		public boolean isModified() {
			return preset != null && !values.isEmpty();
		}
	}

	private final ThemeSettings theme;
	private final Map<MyriadId, Entry> entries = new LinkedHashMap<>();
	private MyriadId active = DEFAULT;
	private boolean applying, dirty;
	private long dirtyAt;
	private int version;

	public ThemeManager(ThemeSettings theme) {
		this.theme = theme;
		for (Setting<?> s : theme.settings.all()) s.onChanged(v -> edited());
	}

	private static Path dir() {
		return Myriad.config().root().resolve("themes");
	}

	/** Bumped whenever the list or names change, so panels know to rebuild. */
	public int version() {
		return version;
	}

	public List<Entry> all() {
		return new ArrayList<>(entries.values());
	}

	public Optional<Entry> get(MyriadId id) {
		return Optional.ofNullable(entries.get(resolve(id)));
	}

	/** The id a theme is registered under now, following {@link Theme#aliases} for ids it replaced. */
	public MyriadId resolve(MyriadId id) {
		if (entries.containsKey(id)) return id;
		for (Theme t : Myriad.themes()) if (t.aliases().contains(id)) return t.id();
		return id;
	}

	/** The registered theme that asked to be used on a fresh install, if any. */
	public Optional<MyriadId> firstRunPreference() {
		for (Theme t : Myriad.themes()) if (t.prefersFirstRun()) return Optional.of(t.id());
		return Optional.empty();
	}

	public Entry active() {
		Entry e = entries.get(active);
		return e != null ? e : entries.values().stream().findFirst().orElseThrow();
	}

	/** Finds a theme by id, id path or name (for commands). */
	public Optional<Entry> find(String query) {
		for (Entry e : entries.values()) {
			if (e.id.toString().equalsIgnoreCase(query) || e.id.path().equalsIgnoreCase(query) || e.name.equalsIgnoreCase(query)
				|| MyriadId.toPath(e.name).equalsIgnoreCase(query)) return Optional.of(e);
		}
		return Optional.empty();
	}

	// ---- loading --------------------------------------------------------------------------------------------------

	/** Rebuilds the list from the registered presets and the themes folder. */
	public void reload() {
		commit();
		entries.clear();
		for (Theme t : Myriad.themes()) entries.put(t.id(), new Entry(t.id(), t, t.name()));
		Path dir = dir();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().forEach(this::loadFile);
			} catch (IOException e) {
				LOG.warn("Could not list {}", dir, e);
			}
		}
		version++;
	}

	private void loadFile(Path file) {
		JsonObject o = JsonFiles.read(file).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
		if (o == null) {
			LOG.warn("Skipping unreadable theme {}", file);
			return;
		}
		JsonObject values = o.has("settings") && o.get("settings").isJsonObject() ? o.getAsJsonObject("settings") : new JsonObject();
		if (o.has("base")) {
			// Saved edits to a built-in preset.
			MyriadId baseId = MyriadId.parse(o.get("base").getAsString());
			Entry base = entries.get(resolve(baseId));
			if (base != null && base.preset != null) {
				// A file saved under an id the theme has since replaced moves to its new name.
				if (!base.id.equals(baseId) && Files.exists(file(base))) return;
				base.values = values;
				if (!base.id.equals(baseId)) {
					save(base);
					try {
						Files.deleteIfExists(file);
					} catch (IOException ex) {
						LOG.warn("Could not remove {}", file, ex);
					}
				}
				return;
			}
			// Edits to a preset that no longer exists (or whose addon is gone): leave the file alone.
			return;
		}
		String fileName = file.getFileName().toString();
		String slug = MyriadId.toPath(fileName.substring(0, fileName.length() - ".json".length()));
		MyriadId id = MyriadId.of(USER_NAMESPACE, slug);
		Entry e = new Entry(id, null, o.has("name") ? o.get("name").getAsString() : slug);
		if (o.has("author")) e.author = o.get("author").getAsString();
		e.values = values;
		entries.put(id, e);
	}

	// ---- applying ---------------------------------------------------------------------------------------------------

	/** Makes {@code id} the active theme and writes all of its values. */
	public boolean apply(MyriadId id) {
		Entry e = entries.get(resolve(id));
		if (e == null) return false;
		commit();
		applying = true;
		try {
			write(e, theme);
		} finally {
			applying = false;
		}
		active = e.id;
		dirty = false;
		version++;
		return true;
	}

	/** Re-applies the active theme (e.g. after a live theme's source changed). */
	public void reapply() {
		apply(active().id);
	}

	/** Writes a theme's full set of values into {@code target}: defaults, then the preset, then saved values. */
	private static void write(Entry e, ThemeSettings target) {
		for (Setting<?> s : target.settings.all()) s.reset();
		if (e.preset != null) e.preset.apply().accept(target);
		applyValues(target, e.values);
	}

	private static void applyValues(ThemeSettings target, JsonObject values) {
		for (SettingGroup g : target.settings.groups()) {
			JsonElement ge = values.get(g.id());
			if (ge == null || !ge.isJsonObject()) continue;
			JsonObject go = ge.getAsJsonObject();
			for (Setting<?> s : g.settings()) {
				JsonElement v = go.get(s.id());
				if (v == null) continue;
				try {
					s.fromJson(v);
				} catch (RuntimeException ignored) {
					// A bad value keeps the preset's; the rest still load.
				}
			}
		}
	}

	/** Every theme value of {@code t}, defaults included, so the file stands on its own. */
	private static JsonObject snapshot(ThemeSettings t) {
		JsonObject o = new JsonObject();
		for (SettingGroup g : t.settings.groups()) {
			JsonObject go = new JsonObject();
			for (Setting<?> s : g.settings()) if (s.isSerializable()) go.add(s.id(), s.toJson());
			if (!go.isEmpty()) o.add(g.id(), go);
		}
		return o;
	}

	/** The values in {@code now} that differ from {@code base}. */
	private static JsonObject diff(JsonObject now, JsonObject base) {
		JsonObject out = new JsonObject();
		for (String group : now.keySet()) {
			JsonObject g = now.getAsJsonObject(group), b = base.has(group) ? base.getAsJsonObject(group) : new JsonObject();
			JsonObject d = new JsonObject();
			for (String key : g.keySet()) if (!g.get(key).equals(b.get(key))) d.add(key, g.get(key));
			if (!d.isEmpty()) out.add(group, d);
		}
		return out;
	}

	private static JsonObject pristine(Entry e) {
		ThemeSettings scratch = new ThemeSettings();
		for (Setting<?> s : scratch.settings.all()) s.reset();
		if (e.preset != null) e.preset.apply().accept(scratch);
		return snapshot(scratch);
	}

	// ---- editing ----------------------------------------------------------------------------------------------------

	private void edited() {
		if (applying) return;
		dirty = true;
		dirtyAt = System.currentTimeMillis();
	}

	/** Saves edits to the active theme a moment after the last change. Called every tick. */
	public void tick() {
		followLive();
		if (dirty && System.currentTimeMillis() - dirtyAt > 400) commit();
	}

	private int livePoll;
	private String liveSignature = "";
	private MyriadId liveTheme;

	/** Re-applies the active theme when it follows something outside the game (Theme.live) and that changed. */
	private void followLive() {
		Entry current = active();
		Supplier<String> live = current.preset() == null ? null : current.preset().live();
		if (live == null) {
			liveTheme = null;
			return;
		}
		if (!current.id().equals(liveTheme)) {
			// The theme was applied when it was picked; start watching from here.
			liveTheme = current.id();
			liveSignature = safeSignature(live);
			return;
		}
		if (++livePoll % 40 != 0) return;
		String sig = safeSignature(live);
		if (sig.equals(liveSignature)) return;
		liveSignature = sig;
		reapply();
	}

	private static String safeSignature(Supplier<String> live) {
		try {
			return String.valueOf(live.get());
		} catch (Throwable t) {
			return "";
		}
	}

	/** Saves pending edits to the active theme now. */
	public void commit() {
		if (!dirty) return;
		dirty = false;
		Entry e = entries.get(active);
		if (e == null) return;
		JsonObject now = snapshot(theme);
		e.values = e.isUser() ? now : diff(now, pristine(e));
		e.swatch = null;
		save(e);
		version++;
	}

	/** Creates a user theme from the current values and makes it active. */
	public Entry create(String name) {
		commit();
		String slug = uniqueSlug(name);
		Entry e = new Entry(MyriadId.of(USER_NAMESPACE, slug), null, name);
		e.author = Optional.ofNullable(Minecraft.getInstance().getUser()).map(s -> s.getName()).orElse("");
		e.values = snapshot(theme);
		entries.put(e.id, e);
		active = e.id;
		save(e);
		version++;
		return e;
	}

	public String uniqueName(String base) {
		String name = base;
		for (int i = 2; nameTaken(name); i++) name = base + " " + i;
		return name;
	}

	private boolean nameTaken(String name) {
		for (Entry e : entries.values()) if (e.name.equalsIgnoreCase(name)) return true;
		return false;
	}

	private String uniqueSlug(String name) {
		String base = MyriadId.toPath(name);
		if (base.isEmpty()) base = "theme";
		String slug = base;
		for (int i = 2; entries.containsKey(MyriadId.of(USER_NAMESPACE, slug)) || Files.exists(dir().resolve(slug + ".json")); i++) slug = base + "_" + i;
		return slug;
	}

	public void rename(Entry e, String name) {
		if (!e.isUser() || name.isBlank()) return;
		e.name = name.trim();
		save(e);
		version++;
	}

	public void setAuthor(Entry e, String author) {
		if (!e.isUser()) return;
		e.author = author.trim();
		save(e);
	}

	/** Deletes a user theme (switching to the default if it was active). */
	public void delete(Entry e) {
		if (!e.isUser()) return;
		try {
			Files.deleteIfExists(file(e));
		} catch (IOException ex) {
			LOG.warn("Could not delete {}", file(e), ex);
		}
		entries.remove(e.id);
		if (active.equals(e.id)) {
			dirty = false;
			apply(entries.containsKey(DEFAULT) ? DEFAULT : entries.keySet().iterator().next());
		}
		version++;
	}

	/** Drops saved edits to a built-in preset. */
	public void reset(Entry e) {
		if (e.isUser()) return;
		if (active.equals(e.id)) dirty = false;
		e.values = new JsonObject();
		e.swatch = null;
		save(e);
		if (active.equals(e.id)) apply(e.id);
		version++;
	}

	private Path file(Entry e) {
		return e.isUser() ? dir().resolve(e.id.path() + ".json") : dir().resolve(e.id.namespace() + "." + e.id.path() + ".json");
	}

	private void save(Entry e) {
		Path f = file(e);
		try {
			if (!e.isUser() && e.values.isEmpty()) {
				Files.deleteIfExists(f);
				return;
			}
			JsonObject o = new JsonObject();
			o.addProperty("format", FORMAT);
			o.addProperty("name", e.name);
			if (!e.author.isEmpty()) o.addProperty("author", e.author);
			if (!e.isUser()) o.addProperty("base", e.id.toString());
			o.add("settings", e.values);
			JsonFiles.write(f, o);
		} catch (IOException ex) {
			LOG.warn("Could not save theme {}", f, ex);
		}
	}

	public Path folder() {
		Path dir = dir();
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	/** Colours to preview a theme: background, accent, secondary, then the palette. */
	public int[] swatch(Entry e) {
		if (e.id.equals(active) && !applying) return colors(theme);
		if (e.swatch == null) {
			ThemeSettings scratch = new ThemeSettings();
			write(e, scratch);
			e.swatch = colors(scratch);
		}
		return e.swatch;
	}

	private static int[] colors(ThemeSettings t) {
		return new int[]{t.windowBackground.get().color() | 0xFF000000, t.accent.get().color(), t.secondary.get().color(), t.red.get().color(),
			t.green.get().color(), t.yellow.get().color(), t.blue.get().color(), t.cyan.get().color()};
	}
}
