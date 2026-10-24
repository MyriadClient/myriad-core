package dev.myriadclient.impl.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingGroup;
import dev.myriadclient.api.setting.Settings;
import dev.myriadclient.api.share.ConfigShare;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.Http;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlPanelImpl;
import dev.myriadclient.impl.wheel.WheelImpl;
import dev.myriadclient.impl.wheel.WheelManager;
import dev.myriadclient.impl.wheel.WheelSlot;
import net.minecraft.SharedConstants;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared configs: builds share documents (settings minus protected ones, plus a {@link ShareView} of each module for the
 * website), publishes and fetches them through Myriad's API ({@code api.myriadclient.dev}, or {@code -Dmyriad.api}),
 * remembers what this computer published (with the keys that delete them, in core's {@code shares.json}), and takes
 * shares in.
 */
public final class ShareManager implements ConfigShares {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Shares");
	private static final String FILE = "shares.json";
	private static final Pattern CODE = Pattern.compile("myriad_[A-Za-z0-9]{6}");
	private static final String API = System.getProperty("myriad.api", "https://api.myriadclient.dev");
	private static final String SITE = System.getProperty("myriad.site", "https://myriadclient.dev");

	private record Own(Published published, String deleteKey) {
	}

	private final List<Own> published = new ArrayList<>();
	private @Nullable AddonStorage storage;

	public void load(AddonStorage storage) {
		this.storage = storage;
		published.clear();
		storage.readJson(FILE).filter(JsonElement::isJsonArray).ifPresent(a -> {
			for (JsonElement e : a.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				published.add(new Own(new Published(o.get("code").getAsString(), o.get("url").getAsString(), o.get("title").getAsString(),
					o.get("createdAt").getAsLong()), o.get("deleteKey").getAsString()));
			}
		});
	}

	private void save() {
		if (storage == null) return;
		JsonArray a = new JsonArray();
		for (Own own : published) {
			JsonObject o = new JsonObject();
			o.addProperty("code", own.published.code());
			o.addProperty("url", own.published.url());
			o.addProperty("title", own.published.title());
			o.addProperty("createdAt", own.published.createdAt());
			o.addProperty("deleteKey", own.deleteKey);
			a.add(o);
		}
		storage.writeJson(FILE, a);
	}

	// ---- building -----------------------------------------------------------------------------------------------------

	@Override
	public Builder builder() {
		return new ShareBuilder();
	}

	/** The group every module has for its bind and list options: shared only with binds. */
	static boolean isModuleGroup(SettingGroup g) {
		return g.id().equals("module");
	}

	/** {@code settings}' player values that differ from the default, protected ones (and binds, unless asked) left out. */
	static JsonObject shareable(Settings settings, boolean binds) {
		JsonObject out = new JsonObject();
		for (SettingGroup g : settings.groups()) {
			if (!binds && isModuleGroup(g)) continue;
			JsonObject group = new JsonObject();
			for (Setting<?> s : g.settings()) {
				if (s.isSerializable() && !s.isProtected() && !s.isDefault()) group.add(s.id(), s.toJson());
			}
			if (!group.isEmpty()) out.add(g.id(), group);
		}
		return out;
	}

	/** The builder {@link #builder()} hands out; core's Share window also puts wheels in, which aren't part of the API. */
	public static final class ShareBuilder implements Builder {
		private String title = "Shared config", description = "";
		private final Set<Module> modules = new LinkedHashSet<>();
		private final List<ControlPanel> panels = new ArrayList<>();
		private final List<WheelImpl> wheels = new ArrayList<>();
		private boolean theme, binds;

		@Override
		public Builder title(String title) {
			this.title = Objects.requireNonNull(title);
			return this;
		}

		@Override
		public Builder description(String description) {
			this.description = Objects.requireNonNull(description);
			return this;
		}

		@Override
		public Builder module(Module module) {
			if (!module.isMirror()) modules.add(module);
			return this;
		}

		@Override
		public Builder modules(java.util.Collection<? extends Module> modules) {
			modules.forEach(this::module);
			return this;
		}

		@Override
		public Builder tag(Tag tag) {
			return modules(Myriad.modules().withTag(tag));
		}

		@Override
		public Builder group(ModuleGroup group) {
			return modules(Myriad.modules().inGroup(group));
		}

		@Override
		public Builder addon(String modId) {
			for (Module m : Myriad.modules()) if (m.id().namespace().equals(modId)) module(m);
			return this;
		}

		@Override
		public Builder controlPanel(ControlPanel panel) {
			if (!panels.contains(panel)) panels.add(panel);
			return this;
		}

		@Override
		public Builder theme() {
			theme = true;
			return this;
		}

		/** A wheel: its name, icon and slots (its keybind only with {@link #binds}). */
		public ShareBuilder wheel(WheelImpl wheel) {
			if (!wheels.contains(wheel)) wheels.add(wheel);
			return this;
		}

		@Override
		public Builder binds(boolean include) {
			binds = include;
			return this;
		}

		@Override
		public ConfigShare build() {
			JsonObject doc = new JsonObject();
			doc.addProperty("format", ConfigShare.FORMAT);
			doc.addProperty("title", title);
			if (!description.isBlank()) doc.addProperty("description", description);
			JsonObject made = new JsonObject();
			made.addProperty("core", Myriad.version());
			made.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
			doc.add("made", made);

			Map<String, Addon> addons = new LinkedHashMap<>();
			JsonArray mods = new JsonArray();
			for (Module m : modules) {
				JsonObject o = new JsonObject();
				o.addProperty("id", m.id().toString());
				o.addProperty("enabled", m.isEnabled());
				o.add("settings", shareable(m.settings, binds));
				o.add("view", ShareView.module(m, binds));
				mods.add(o);
				Myriad.addons().stream().filter(a -> a.id().equals(m.id().namespace())).findFirst().ifPresent(a -> addons.put(a.id(), a));
			}
			doc.add("modules", mods);

			JsonArray cps = new JsonArray();
			for (ControlPanel p : panels) if (p instanceof ControlPanelImpl impl) cps.add(impl.share());
			if (!cps.isEmpty()) doc.add("controlPanels", cps);

			JsonArray ws = new JsonArray();
			for (WheelImpl w : wheels) ws.add(shareWheel(w, binds));
			if (!ws.isEmpty()) doc.add("wheels", ws);

			if (theme) {
				ThemeSettings t = Myriad.ui().theme();
				JsonObject o = new JsonObject();
				o.addProperty("name", MyriadImpl.get().windowManager().themes().active().name());
				o.add("settings", shareable(t.settings, true));
				o.add("view", ShareView.theme(t));
				doc.add("theme", o);
			} else {
				// The website draws it in the sharer's colours either way.
				doc.add("look", ShareView.theme(Myriad.ui().theme()));
			}

			JsonArray a = new JsonArray();
			for (Addon addon : addons.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", addon.id());
				o.addProperty("name", addon.name());
				o.addProperty("version", addon.version());
				a.add(o);
			}
			doc.add("addons", a);
			return ConfigShare.of(doc);
		}
	}

	/**
	 * A wheel in a share: name, icon, keybind (with binds) and slots, each slot with the name it shows (the website shows
	 * it, and a control panel's slot finds the panel again by it, as a shared panel gets a new id where it's taken in).
	 */
	private static JsonObject shareWheel(WheelImpl w, boolean binds) {
		JsonObject o = new JsonObject();
		o.addProperty("name", w.name());
		if (!w.icon.get().isEmpty()) o.addProperty("icon", w.icon.get());
		if (binds && w.keybind.get().isSet()) o.add("keybind", w.keybind.toJson());
		JsonArray slots = new JsonArray();
		for (WheelSlot slot : w.slots()) {
			JsonObject so = slot.toJson();
			so.addProperty("label", slot.defaultLabel());
			slots.add(so);
		}
		o.add("slots", slots);
		return o;
	}

	// ---- the API ------------------------------------------------------------------------------------------------------

	@Override
	public CompletableFuture<Published> publish(ConfigShare share) {
		if (share.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("Nothing to share"));
		return Http.postJson(API + "/v1/configs", share.json()).thenApply(body -> {
			JsonObject r = JsonParser.parseString(body).getAsJsonObject();
			String code = r.get("code").getAsString();
			Published p = new Published(code, r.has("url") ? r.get("url").getAsString() : SITE + "/config/" + code, share.title(), System.currentTimeMillis());
			synchronized (published) {
				published.addFirst(new Own(p, r.get("deleteKey").getAsString()));
				save();
			}
			return p;
		});
	}

	@Override
	public CompletableFuture<ConfigShare> fetch(String code) {
		String c = codeIn(code);
		if (c == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Not a share code: " + code));
		return Http.getJson(API + "/v1/configs/" + c).thenApply(json -> ConfigShare.of(json.getAsJsonObject()));
	}

	/** The code in {@code text} (a code, or a link to its page), or null. */
	public static @Nullable String codeIn(String text) {
		Matcher m = CODE.matcher(text == null ? "" : text);
		return m.find() ? m.group() : null;
	}

	@Override
	public CompletableFuture<Void> unpublish(String code) {
		Own own;
		synchronized (published) {
			own = published.stream().filter(o -> o.published.code().equals(code)).findFirst().orElse(null);
		}
		if (own == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Not published from this computer: " + code));
		return Http.delete(API + "/v1/configs/" + code, own.deleteKey).thenAccept(body -> {
			synchronized (published) {
				published.remove(own);
				save();
			}
		});
	}

	@Override
	public List<Published> published() {
		synchronized (published) {
			return published.stream().map(Own::published).toList();
		}
	}

	// ---- taking one in ------------------------------------------------------------------------------------------------

	@Override
	public Applied apply(ConfigShare share, Apply how) {
		JsonObject doc = share.json();
		List<String> applied = new ArrayList<>(), missing = new ArrayList<>();
		// A control panel to try needs modules in it; a share of wheels or a theme alone is just added.
		ControlPanelImpl trial = how == Apply.AS_CONTROL_PANEL && !share.modules().isEmpty() ? newPanel(shortened(share.title())) : null;
		Set<String> trying = trial == null ? Set.of() : trialMembers(array(doc, "modules"), id -> Myriad.modules().get(MyriadId.parse(id)).isPresent());

		for (JsonElement e : array(doc, "modules")) {
			try {
				applyModule(e.getAsJsonObject(), trial, trying, applied, missing);
			} catch (RuntimeException ex) {
				LOG.warn("Skipped a shared module it couldn't read: {}", e, ex);
			}
		}

		Map<String, ControlPanelImpl> sharedPanels = new HashMap<>();
		for (JsonElement e : array(doc, "controlPanels")) {
			JsonObject cp = e.getAsJsonObject();
			ControlPanelImpl panel = newPanel(shortened(cp.get("name").getAsString()));
			sharedPanels.put(cp.get("name").getAsString(), panel);
			try {
				panel.takeShared(cp);
			} catch (RuntimeException ex) {
				LOG.warn("Couldn't read shared control panel {}", panel.name(), ex);
			}
			applied.add("control panel " + panel.name());
		}

		for (JsonElement e : array(doc, "wheels")) {
			try {
				applied.add("wheel " + takeWheel(e.getAsJsonObject(), sharedPanels).name());
			} catch (RuntimeException ex) {
				LOG.warn("Skipped a shared wheel it couldn't read: {}", e, ex);
			}
		}

		if (doc.has("theme") && doc.get("theme").isJsonObject()) {
			JsonObject t = doc.getAsJsonObject("theme");
			ThemeSettings theme = Myriad.ui().theme();
			theme.settings.resetAll();
			if (t.has("settings") && t.get("settings").isJsonObject()) {
				for (Setting<?> setting : theme.settings.all()) {
					JsonElement group = setting.group() == null ? null : t.getAsJsonObject("settings").get(setting.group().id());
					if (group != null && group.isJsonObject() && group.getAsJsonObject().has(setting.id())) {
						readSafely(setting, group.getAsJsonObject().get(setting.id()));
					}
				}
			}
			MyriadImpl.get().windowManager().themes().commit();
			applied.add("theme");
		}
		Myriad.config().markDirty();
		return new Applied(applied, missing);
	}

	/**
	 * A shared wheel added as a new one (its name made unique). A control panel's slot points at the panel the share
	 * brought, or one of yours with its name. A command's slot shows its command, not a name the sharer gave it: a share
	 * comes from anyone, and a slot must say what it runs.
	 */
	private static WheelImpl takeWheel(JsonObject o, Map<String, ControlPanelImpl> sharedPanels) {
		WheelManager manager = MyriadImpl.get().wheelManager();
		String name = shortened(o.has("name") ? o.get("name").getAsString() : "Wheel");
		String unique = name;
		for (int i = 2; taken(manager, unique); i++) unique = name + " " + i;
		WheelImpl wheel = manager.create(unique);
		if (o.has("icon")) readSafely(wheel.icon, o.get("icon"));
		if (o.has("keybind")) readSafely(wheel.keybind, o.get("keybind"));
		for (JsonElement e : array(o, "slots")) {
			if (!e.isJsonObject()) continue;
			JsonObject so = e.getAsJsonObject();
			WheelSlot slot = WheelSlot.fromJson(so);
			if (slot == null) continue;
			String label = so.has("label") ? so.get("label").getAsString() : "";
			if (slot.kind() == WheelSlot.Kind.CONTROL && !label.isEmpty()) {
				ControlPanel panel = sharedPanels.containsKey(label) ? sharedPanels.get(label) : Myriad.controls().byName(label).orElse(null);
				if (panel != null) slot = new WheelSlot(slot.kind(), panel.id().toString(), slot.customIcon(), slot.customName());
			}
			if (slot.kind() == WheelSlot.Kind.COMMAND) slot = new WheelSlot(slot.kind(), slot.target());
			wheel.add(slot);
		}
		return wheel;
	}

	private static boolean taken(WheelManager manager, String name) {
		return manager.all().stream().anyMatch(w -> w.name().equalsIgnoreCase(name));
	}

	/**
	 * The modules of a share that a control panel to try turns on: those that were on for the sharer, since a whole
	 * config lists every module, most of them off (trying one turned on everything you have). A share with none of
	 * yours on brings them all: settings for modules you'd turn on to try them.
	 */
	static Set<String> trialMembers(JsonArray modules, Predicate<String> installed) {
		Set<String> on = new LinkedHashSet<>(), all = new LinkedHashSet<>();
		for (JsonElement e : modules) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
			JsonObject entry = e.getAsJsonObject();
			String id = entry.get("id").getAsString();
			if (!installed.test(id)) continue;
			all.add(id);
			if (entry.has("enabled") && entry.get("enabled").getAsBoolean()) on.add(id);
		}
		return on.isEmpty() ? all : on;
	}

	/** One module of a share taken in: as the player's settings, or into {@code trial} (a control panel to try) if it's one {@code trying}. */
	private static void applyModule(JsonObject entry, @Nullable ControlPanelImpl trial, Set<String> trying, List<String> applied, List<String> missing) {
		String id = entry.get("id").getAsString();
		Module m = Myriad.modules().get(MyriadId.parse(id)).orElse(null);
		if (m == null) {
			missing.add(entry.has("view") ? entry.getAsJsonObject("view").get("name").getAsString() + " (" + id + ")" : id);
			return;
		}
		JsonObject settings = entry.has("settings") ? entry.getAsJsonObject("settings") : new JsonObject();
		if (trial != null) {
			if (!trying.contains(id)) return;
			trial.members.set(withMember(trial.members.get(), m.id()));
			forEachShared(m, settings, (s, value) -> trial.remember(id, m.settings.keyOf(s), value));
		} else {
			replace(m, settings);
			if (entry.has("enabled")) m.setEnabled(entry.get("enabled").getAsBoolean());
		}
		applied.add(m.name());
	}

	/** {@code m}'s settings made the share's: shared values in, the rest back to default, protected ones (and binds the share leaves out) untouched. */
	private static void replace(Module m, JsonObject shared) {
		for (SettingGroup g : m.settings.groups()) {
			boolean sharedGroup = shared.has(g.id());
			if (isModuleGroup(g) && !sharedGroup) continue;
			for (Setting<?> s : g.settings()) {
				if (!s.isSerializable() || s.isProtected()) continue;
				JsonElement value = sharedGroup && shared.get(g.id()).isJsonObject() ? shared.getAsJsonObject(g.id()).get(s.id()) : null;
				if (value == null) s.reset();
				else readSafely(s, value);
			}
		}
	}

	/** The share's values for {@code m}'s settings that aren't protected here, decoded and encoded again as this version reads them. */
	private static void forEachShared(Module m, JsonObject shared, java.util.function.BiConsumer<Setting<?>, JsonElement> action) {
		for (SettingGroup g : m.settings.groups()) {
			if (!shared.has(g.id()) || isModuleGroup(g)) continue;
			JsonObject values = shared.getAsJsonObject(g.id());
			for (Setting<?> s : g.settings()) {
				JsonElement v = values.get(s.id());
				if (v == null || s.isProtected()) continue;
				try {
					action.accept(s, reencode(s, v));
				} catch (RuntimeException e) {
					LOG.warn("Skipped a shared value for {} it couldn't read", s.name(), e);
				}
			}
		}
	}

	/** A shared value into {@code s}; one it can't read (from a newer version, or made up) leaves {@code s} as it was. */
	private static void readSafely(Setting<?> s, JsonElement value) {
		try {
			s.fromJson(value);
		} catch (RuntimeException e) {
			LOG.warn("Skipped a shared value for {} it couldn't read", s.name(), e);
		}
	}

	private static <T> JsonElement reencode(Setting<T> s, JsonElement v) {
		return s.encode(s.decode(v));
	}

	private static Set<MyriadId> withMember(Set<MyriadId> members, MyriadId id) {
		Set<MyriadId> out = new LinkedHashSet<>(members);
		out.add(id);
		return out;
	}

	/** A name from a share, kept to a length the menu shows (a share comes from anyone). */
	private static String shortened(String name) {
		String n = name.strip().replaceAll("\\p{Cntrl}", "");
		return n.isEmpty() ? "Shared config" : n.length() > 48 ? n.substring(0, 48) : n;
	}

	/** A new control panel named {@code name}, or {@code name 2}, ... if that's taken. */
	private static ControlPanelImpl newPanel(String name) {
		String n = name;
		for (int i = 2; Myriad.controls().byName(n).isPresent(); i++) n = name + " " + i;
		return (ControlPanelImpl) Myriad.controls().create(n);
	}

	private static JsonArray array(JsonObject doc, String key) {
		return doc.has(key) && doc.get(key).isJsonArray() ? doc.getAsJsonArray(key) : new JsonArray();
	}
}
