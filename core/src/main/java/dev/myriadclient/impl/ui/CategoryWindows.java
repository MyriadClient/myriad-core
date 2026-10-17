package dev.myriadclient.impl.ui;

import com.google.gson.JsonArray;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.impl.ui.layout.ColumnsLayout;
import dev.myriadclient.impl.ui.layout.DwindleLayout;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.ModuleGroup;
import dev.myriadclient.impl.ui.panels.ModulesPanel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The menu's category windows: one per addon + category group of modules, placed when the group first appears (on
 * the first run, or when an addon arrives later) and never again, so a window the player closed or moved stays that
 * way. Which groups the desktop has already placed is saved with it.
 */
final class CategoryWindows {
	private final WindowManager wm;
	/** Addon + category groups this desktop has already placed, so a group whose window you closed stays closed. */
	private final Set<String> seen = new LinkedHashSet<>();

	CategoryWindows(WindowManager wm) {
		this.wm = wm;
	}

	/** Forgets every group placed (a fresh desktop places them all again). */
	void reset() {
		seen.clear();
	}

	JsonArray saveSeen() {
		JsonArray a = new JsonArray();
		for (String g : seen) a.add(g);
		return a;
	}

	void loadSeen(JsonArray a) {
		for (var e : a) seen.add(e.getAsString());
	}

	/** The addon + category groups a window shows (empty for anything but a category window). */
	private static List<ModuleGroup> groupsOf(WindowImpl w) {
		return w.panel instanceof ModulesPanel p ? p.groups() : List.of();
	}

	/** Narrowest a tiled window is readable at (a category window's module names fit); see {@link #capacity()}. */
	static final float MIN_TILE_WIDTH = ColumnsLayout.MIN_WIDTH;

	/** How many tiled windows a workspace takes before new addons go elsewhere: as many as fit side by side. */
	private int capacity() {
		Rect area = wm.workArea().inset(wm.theme.gapsOut.get());
		float gap = wm.theme.gapsIn.get();
		return Math.max(2, (int) ((area.w() + gap) / (MIN_TILE_WIDTH + gap)));
	}

	/**
	 * Gives each addon + category group the desktop hasn't seen yet a window, one addon at a time, by
	 * {@link GroupPlacement}: beside its categories' windows when they have room, otherwise together on a workspace of
	 * its own (spilling onto the next empty one when there are more than fit). Groups already on screen, or placed
	 * before and since closed or moved, are left alone.
	 */
	void placeNewGroups(boolean announce) {
		Set<ModuleGroup> shown = new HashSet<>();
		for (WindowImpl w : wm.windows) shown.addAll(groupsOf(w));
		Map<String, List<ModuleGroup>> byAddon = new LinkedHashMap<>();
		for (Addon a : Myriad.addons()) byAddon.put(a.id(), new ArrayList<>());
		for (ModuleGroup g : ModuleGroup.all()) {
			if (!seen.add(g.key()) || shown.contains(g)) continue;
			byAddon.computeIfAbsent(g.addon(), k -> new ArrayList<>()).add(g);
		}
		int capacity = capacity();
		for (List<ModuleGroup> groups : byAddon.values()) {
			if (groups.isEmpty()) continue;
			int[] counts = new int[Desktop.WORKSPACES + 1];
			for (int i = 1; i <= Desktop.WORKSPACES; i++) counts[i] = wm.workspaces[i].tiling.windows().size();
			int[] siblings = new int[groups.size()];
			for (int i = 0; i < groups.size(); i++) {
				WindowImpl s = sibling(groups.get(i), -1);
				siblings[i] = s == null ? -1 : s.workspace;
			}
			int[] targets = GroupPlacement.place(siblings, counts, capacity);
			TreeSet<Integer> used = new TreeSet<>();
			for (int ws : targets) used.add(ws);
			for (int ws : used) {
				List<ModuleGroup> batch = new ArrayList<>();
				for (int i = 0; i < groups.size(); i++) if (targets[i] == ws) batch.add(groups.get(i));
				openGroups(ws, batch);
			}
			if (announce) {
				ModuleGroup first = groups.getFirst();
				String what = groups.size() == 1 ? first.category().name() + " modules" : groups.size() + " windows";
				Myriad.notifications().toast("Menu", first.addonName() + " added " + what + " to " + workspacesText(used))
					.lasting(5000).replacing("groups").show();
			}
		}
		wm.markDirty();
	}

	/** "workspace 2", "workspaces 2 and 3", "workspaces 2, 3 and 5". */
	private static String workspacesText(SortedSet<Integer> used) {
		if (used.size() == 1) return "workspace " + used.first();
		List<String> names = new ArrayList<>();
		for (int ws : used) names.add(String.valueOf(ws));
		return "workspaces " + String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
	}

	/** The last window (on {@code workspace}, or any if it's -1) showing a group of {@code g}'s category. */
	private WindowImpl sibling(ModuleGroup g, int workspace) {
		WindowImpl found = null;
		for (WindowImpl w : wm.windows) {
			if (w.workspace == Desktop.HUD_WORKSPACE || (workspace >= 0 && w.workspace != workspace)) continue;
			for (ModuleGroup other : groupsOf(w)) if (other.category().equals(g.category())) found = w;
		}
		return found;
	}

	/**
	 * Opens a window for each group on {@code workspace}. Each goes right after its category's window there, if it has
	 * one. On an empty workspace, Dwindle splits the least recently split window each time, for an even grid rather
	 * than ever thinner slivers.
	 */
	private void openGroups(int workspace, List<ModuleGroup> groups) {
		Workspace ws = wm.workspaces[workspace];
		WindowImpl keepFocus = ws.focused;
		boolean grid = ws.tiling.windows().isEmpty() && ws.layout instanceof DwindleLayout;
		ArrayDeque<WindowImpl> toSplit = new ArrayDeque<>();
		WindowImpl last = null;
		for (ModuleGroup g : groups) {
			WindowImpl target = grid ? toSplit.poll() : sibling(g, workspace);
			if (target == null && !grid) target = last;
			ws.focused = target != null && !target.floating ? target : null;
			Optional<Window> opened = wm.openPanel(CorePanels.CATEGORY, ModuleGroup.args(List.of(g)), workspace, false);
			if (opened.isEmpty()) continue;
			WindowImpl w = (WindowImpl) opened.get();
			w.opacity.snap(1);
			last = w;
			if (grid && target != null) toSplit.add(target);
			if (grid) toSplit.add(w);
			if (keepFocus == null) keepFocus = w;
		}
		ws.focused = keepFocus;
	}

	/** Shows the window holding {@code group}, opening one (on the current workspace) if none does. */
	void openGroup(ModuleGroup group) {
		for (WindowImpl w : wm.windows) {
			if (!groupsOf(w).contains(group)) continue;
			if (wm.isOpen() && w.workspace != wm.active) wm.switchWorkspace(w.workspace);
			wm.focus(w);
			return;
		}
		wm.openPanel(CorePanels.CATEGORY, ModuleGroup.args(List.of(group)));
	}
}
