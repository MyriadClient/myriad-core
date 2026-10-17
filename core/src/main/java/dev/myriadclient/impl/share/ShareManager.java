package dev.myriadclient.impl.share;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Category;
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
import net.minecraft.SharedConstants;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared configs: builds share documents (settings minus protected ones, plus a {@link ShareView} of each module for the
 * website), publishes and fetches them through Myriad's API ({@code api.myriadclient.dev}, or {@code -Dmyriad.api}),
 * remembers what this computer published (with the keys that delete them, in core's {@code shares.json}), and takes
 * shares in.
 */
public final class ShareManager implements ConfigShares {
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

	private static final class ShareBuilder implements Builder {
		private String title = "Shared config", description = "";
		private final Set<Module> modules = new LinkedHashSet<>();
		private final List<ControlPanel> panels = new ArrayList<>();
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
		public Builder category(Category category) {
			return modules(Myriad.modules().inCategory(category));
		}

		@Override
		public Builder addon(String modId) {
			for (Module m : Myriad.modules()) if (m.id().namespace().equals(modId)) module(m);
			return this;
		}

		@Override
		public Builder addonCategory(String modId, Category category) {
			for (Module m : Myriad.modules().inCategory(category)) if (m.id().namespace().equals(modId)) module(m);
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
		ControlPanelImpl trial = how == Apply.AS_CONTROL_PANEL ? newPanel(share.title()) : null;

		for (JsonElement e : array(doc, "modules")) {
			JsonObject entry = e.getAsJsonObject();
			String id = entry.get("id").getAsString();
			Module m = Myriad.modules().get(MyriadId.parse(id)).orElse(null);
			if (m == null) {
				missing.add(entry.has("view") ? entry.getAsJsonObject("view").get("name").getAsString() + " (" + id + ")" : id);
				continue;
			}
			JsonObject settings = entry.has("settings") ? entry.getAsJsonObject("settings") : new JsonObject();
			if (trial != null) {
				trial.members.set(withMember(trial.members.get(), m.id()));
				forEachShared(m, settings, (s, value) -> trial.remember(id, m.settings.keyOf(s), value));
			} else {
				replace(m, settings);
				if (entry.has("enabled")) m.setEnabled(entry.get("enabled").getAsBoolean());
			}
			applied.add(m.name());
		}

		for (JsonElement e : array(doc, "controlPanels")) {
			JsonObject cp = e.getAsJsonObject();
			ControlPanelImpl panel = newPanel(cp.get("name").getAsString());
			panel.takeShared(cp);
			applied.add("control panel " + panel.name());
		}

		if (doc.has("theme") && doc.get("theme").isJsonObject()) {
			JsonObject t = doc.getAsJsonObject("theme");
			ThemeSettings theme = Myriad.ui().theme();
			theme.settings.resetAll();
			if (t.has("settings")) theme.settings.fromJson(t.getAsJsonObject("settings"));
			MyriadImpl.get().windowManager().themes().commit();
			applied.add("theme");
		}
		Myriad.config().markDirty();
		return new Applied(applied, missing);
	}

	/** {@code m}'s settings made the share's: shared values in, the rest back to default, protected ones (and binds the share leaves out) untouched. */
	private static void replace(Module m, JsonObject shared) {
		for (SettingGroup g : m.settings.groups()) {
			boolean sharedGroup = shared.has(g.id());
			if (isModuleGroup(g) && !sharedGroup) continue;
			for (Setting<?> s : g.settings()) {
				if (!s.isSerializable() || s.isProtected()) continue;
				JsonElement value = sharedGroup ? shared.getAsJsonObject(g.id()).get(s.id()) : null;
				if (value == null) s.reset();
				else s.fromJson(value);
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
				if (v != null && !s.isProtected()) action.accept(s, reencode(s, v));
			}
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
