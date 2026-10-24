package dev.myriadclient.impl.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.ui.layout.DwindleLayout;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.DialogPanel;
import dev.myriadclient.impl.ui.panels.MenuGroups;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The desktop as saved: its workspaces, windows, theme, preferences and binds, the first-run desktop when there's
 * nothing saved, and saved windows kept until their panel types are installed again.
 */
final class DesktopStore {
	/** Bump to rebuild users' workspace layouts (theme and HUD are kept) after a default-layout change. */
	private static final int LAYOUT_VERSION = 3;
	private JsonArray orphanWindows = new JsonArray();
	/** Each workspace's tiling as loaded, so a window revived later (its modules came late) can take its old place. */
	private final JsonObject[] savedTiling = new JsonObject[Desktop.WORKSPACES + 1];

	private final WindowManager wm;
	private final ThemeSettings theme;
	private final ThemeManager themes;

	DesktopStore(WindowManager wm) {
		this.wm = wm;
		theme = wm.theme;
		themes = wm.themes();
	}

	/**
	 * Brings back saved windows that couldn't be built at load because their modules weren't registered yet (an addon
	 * that bridges another mod registers once that mod is up). Each goes to its saved workspace; a tiled one takes the
	 * place it had in that workspace's saved tiling, so the layout comes back as it was.
	 */
	void reviveOrphans() {
		if (orphanWindows.isEmpty()) return;
		JsonArray still = new JsonArray();
		List<WindowImpl> revived = new ArrayList<>();
		for (JsonElement e : orphanWindows) {
			JsonObject j = e.getAsJsonObject();
			WindowImpl w = null;
			try {
				w = loadWindow(j);
			} catch (RuntimeException ex) {
				WindowManager.LOG.error("Skipping broken window entry {}", j, ex);
				continue;
			}
			if (w != null) revived.add(w);
			else if (!groupsGone(j)) still.add(j);
		}
		orphanWindows = still;
		if (revived.isEmpty()) return;
		Set<Integer> touched = new HashSet<>();
		for (WindowImpl w : revived) {
			wm.windows.add(w);
			w.opacity.snap(1);
			if (w.floating) wm.workspaces[w.workspace].floating.add(w);
			else touched.add(w.workspace);
			wm.safe(w, w.panel::onOpen);
		}
		for (int idx : touched) {
			Workspace ws = wm.workspaces[idx];
			Map<String, WindowImpl> byUid = new LinkedHashMap<>();
			for (WindowImpl w : wm.windows) if (w.workspace == idx && !w.floating) byUid.put(String.valueOf(w.uid), w);
			Set<WindowImpl> placed = new HashSet<>();
			if (savedTiling[idx] != null) {
				ws.tiling.load(savedTiling[idx], key -> {
					WindowImpl w = byUid.get(key);
					return w != null && placed.add(w) ? w : null;
				});
			}
			for (WindowImpl w : byUid.values()) if (!placed.contains(w)) ws.tiling.add(w, null);
		}
		wm.markDirty();
	}

	JsonObject save() {
		JsonObject o = new JsonObject();
		o.addProperty("version", LAYOUT_VERSION);
		o.addProperty("active", wm.active);
		themes.commit();
		o.addProperty("theme", themes.active().id().toString());
		o.add("preferences", theme.preferences.toJson());
		JsonObject b = new JsonObject();
		for (DesktopBinds.Bind bind : wm.binds.all().values()) b.addProperty(bind.id, bind.bind.serialize());
		o.add("binds", b);
		JsonArray ws = new JsonArray();
		for (Workspace w : wm.workspaces) {
			JsonObject j = new JsonObject();
			j.addProperty("index", w.index);
			j.addProperty("layout", w.layout.id().toString());
			j.add("tiling", w.tiling.save(win -> String.valueOf(win.uid)));
			if (w.focused != null) j.addProperty("focused", w.focused.uid);
			ws.add(j);
		}
		o.add("workspaces", ws);
		o.add("seen_groups", wm.groups.saveSeen());
		JsonArray wins = new JsonArray();
		for (WindowImpl w : wm.windows) {
			// Dialogs hold callbacks that don't survive a restart.
			if (!(w.panel instanceof DialogPanel)) wins.add(w.save());
		}
		// Windows of panel types that aren't installed right now are kept for when they come back.
		for (JsonElement e : orphanWindows) wins.add(e);
		o.add("windows", wins);
		return o;
	}

	/** Loads a saved layout; {@code null} builds the first-run desktop. */
	void load(JsonObject o) {
		clear();
		if (o == null) {
			firstRun();
			wm.groups.placeNewGroups(false);
			wm.modulesChanged = false;
			return;
		}
		if (o.has("seen_groups")) wm.groups.loadSeen(o.getAsJsonArray("seen_groups"));
		loadTheme(o);
		loadBinds(o);
		Map<String, WindowImpl> byUid = loadWindows(o);
		Set<WindowImpl> placed = loadWorkspaces(o, byUid);
		for (WindowImpl w : byUid.values()) {
			wm.windows.add(w);
			Workspace ws = wm.workspaces[w.workspace];
			if (w.floating) ws.floating.add(w);
			else if (!placed.contains(w)) ws.tiling.add(w, null);
			w.opacity.snap(1);
			wm.safe(w, w.panel::onOpen);
		}
		wm.active = o.has("active") ? Math.clamp(o.get("active").getAsInt(), 0, Desktop.WORKSPACES) : 1;
		wm.previous = wm.active;
		int version = o.has("version") ? o.get("version").getAsInt() : 1;
		if (version < LAYOUT_VERSION) {
			// One-time upgrade: adopt a theme an addon prefers (e.g. one matching the system), if any.
			if (version < 2) themes.firstRunPreference().ifPresent(themes::apply);
			for (WindowImpl w : new ArrayList<>(wm.windows)) if (w.workspace != Desktop.HUD_WORKSPACE) {
				wm.detach(w);
				wm.windows.remove(w);
			}
			defaultWorkspaces();
		}
		wm.groups.tidy();
		wm.groups.placeNewGroups(true);
		wm.modulesChanged = false;
	}

	/** Closes every window and starts each workspace empty, with nothing kept from a layout loaded before. */
	private void clear() {
		for (WindowImpl w : new ArrayList<>(wm.windows)) wm.safe(w, w.panel::onClose);
		wm.windows.clear();
		wm.closing.clear();
		orphanWindows = new JsonArray();
		for (int i = 0; i <= Desktop.WORKSPACES; i++) {
			wm.workspaces[i] = new Workspace(i, defaultLayout());
			savedTiling[i] = null;
		}
		themes.reload();
		wm.groups.reset();
	}

	private void loadBinds(JsonObject o) {
		if (!o.has("binds")) return;
		JsonObject b = o.getAsJsonObject("binds");
		for (DesktopBinds.Bind bind : wm.binds.all().values()) if (b.has(bind.id)) bind.bind = Keybind.deserialize(b.get(bind.id).getAsString());
	}

	/**
	 * Whether {@code j} is a group window whose groups are gone for good: each one's addon is here and has declared its
	 * groups, but not that one (renamed or dropped in an update). Such a window would wait as an orphan forever; the
	 * group under its new name gets a window of its own. A window whose addon is missing keeps waiting for it.
	 */
	private static boolean groupsGone(JsonObject j) {
		if (!j.has("type") || !CorePanels.GROUP.toString().equals(j.get("type").getAsString())) return false;
		JsonObject args = j.has("args") ? j.getAsJsonObject("args") : new JsonObject();
		if (!MenuGroups.isCurrent(args)) return false;
		for (JsonElement e : args.getAsJsonArray("groups")) {
			String id = e.getAsString();
			String addon = id.substring(0, Math.max(0, id.indexOf(':')));
			boolean declared = MenuGroups.all().stream().anyMatch(g -> g.addon().equals(addon));
			if (!declared || MenuGroups.all().stream().anyMatch(g -> g.id().toString().equals(id))) return false;
		}
		return true;
	}

	/** The saved windows that can be built now, by uid; the others are kept as orphans. */
	private Map<String, WindowImpl> loadWindows(JsonObject o) {
		Map<String, WindowImpl> byUid = new LinkedHashMap<>();
		if (!o.has("windows")) return byUid;
		for (JsonElement e : o.getAsJsonArray("windows")) {
			JsonObject j = e.getAsJsonObject();
			try {
				WindowImpl w = loadWindow(j);
				if (w == null && groupsGone(j)) continue;
				if (w == null) {
					orphanWindows.add(j);
					// Its uid stays its own, so a new window doesn't take it before it comes back.
					if (j.has("uid")) wm.nextUid = Math.max(wm.nextUid, j.get("uid").getAsInt() + 1);
				} else {
					byUid.put(String.valueOf(w.uid), w);
				}
			} catch (RuntimeException ex) {
				WindowManager.LOG.error("Skipping broken window entry {}", j, ex);
			}
		}
		return byUid;
	}

	/** Each saved workspace's layout, tiling and focus; returns the windows the tilings placed. */
	private Set<WindowImpl> loadWorkspaces(JsonObject o, Map<String, WindowImpl> byUid) {
		Set<WindowImpl> placed = new HashSet<>();
		if (!o.has("workspaces")) return placed;
		for (JsonElement e : o.getAsJsonArray("workspaces")) {
			JsonObject j = e.getAsJsonObject();
			int idx = j.get("index").getAsInt();
			if (idx < 0 || idx > Desktop.WORKSPACES) continue;
			Workspace ws = wm.workspaces[idx];
			Layout layout = Myriad.layouts().get(MyriadId.parse(j.get("layout").getAsString())).orElse(defaultLayout());
			ws.setLayout(layout);
			if (j.has("tiling")) {
				savedTiling[idx] = j.getAsJsonObject("tiling");
				ws.tiling.load(j.getAsJsonObject("tiling"), key -> {
					WindowImpl w = byUid.get(key);
					return w != null && w.workspace == idx && !w.floating && placed.add(w) ? w : null;
				});
			}
			if (j.has("focused")) ws.focused = byUid.get(j.get("focused").getAsString());
		}
		return placed;
	}

	/** The active theme and personal preferences. */
	private void loadTheme(JsonObject o) {
		JsonElement t = o.get("theme");
		if (o.has("preferences") && o.get("preferences").isJsonObject()) theme.preferences.fromJson(o.getAsJsonObject("preferences"));
		MyriadId id = t != null && t.isJsonPrimitive() ? MyriadId.parse(t.getAsString()) : ThemeManager.DEFAULT;
		if (!themes.apply(id)) themes.apply(ThemeManager.DEFAULT);
	}

	private Layout defaultLayout() {
		return Myriad.layouts().get(DwindleLayout.ID).orElseGet(DwindleLayout::new);
	}

	private WindowImpl loadWindow(JsonObject j) {
		MyriadId typeId = MyriadId.parse(j.get("type").getAsString());
		Optional<PanelType> type = Myriad.panels().get(typeId);
		if (type.isEmpty()) return null;
		JsonObject args = j.has("args") ? j.getAsJsonObject("args") : new JsonObject();
		Panel panel = wm.createPanel(type.get(), args);
		if (panel == null) return null;
		int uid = j.get("uid").getAsInt();
		// Layouts saved before orphans kept their uids can have two windows with one; the later gets a new one.
		for (WindowImpl other : wm.windows) if (other.uid == uid) uid = wm.nextUid++;
		WindowImpl w = new WindowImpl(wm, uid, type.get(), args, panel, Math.clamp(j.get("workspace").getAsInt(), 0, Desktop.WORKSPACES));
		wm.nextUid = Math.max(wm.nextUid, uid + 1);
		w.restore(j);
		return w;
	}

	private void firstRun() {
		wm.updateScale();
		theme.preferences.resetAll();
		themes.apply(themes.firstRunPreference().orElse(ThemeManager.DEFAULT));
		defaultWorkspaces();
		for (PanelType t : Myriad.panels()) if (t.defaultHud() != null) wm.addHudElement(t);
		for (WindowImpl w : wm.windows) w.opacity.snap(1);
	}

	/**
	 * The first-run desktop: one window per module group, a tiled click-GUI, laid out like addons that arrive
	 * later ({@link GroupWindows#placeNewGroups}): the first addon on workspace 1, the others beside it while there's room, then on
	 * workspaces of their own.
	 */
	private void defaultWorkspaces() {
		wm.active = 1;
		wm.groups.reset();
		wm.groups.placeNewGroups(false);
		if (wm.workspaces[1].isEmpty()) wm.openPanel(CorePanels.MODULES, new JsonObject(), 1, false);
		for (WindowImpl w : wm.windows) w.opacity.snap(1);
	}
}
