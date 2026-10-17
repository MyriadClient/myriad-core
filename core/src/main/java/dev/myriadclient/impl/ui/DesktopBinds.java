package dev.myriadclient.impl.ui;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Direction;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.impl.ui.panels.CorePanels;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The desktop's own key bindings (mod+key, rebindable in the Keybinds window) and what they do: Omarchy's tiling
 * bindings, with the mod key standing in for Super.
 */
public final class DesktopBinds {
	private final WindowManager wm;
	private final Map<String, Bind> all = new LinkedHashMap<>();

	DesktopBinds(WindowManager wm) {
		this.wm = wm;
		registerAll();
	}

	/** Every binding by id, in the order the Keybinds window lists them. */
	Map<String, Bind> all() {
		return Collections.unmodifiableMap(all);
	}

	Bind get(String id) {
		return all.get(id);
	}

	public static final class Bind {
		public final String id, name;
		public final Keybind defaultBind;
		public Keybind bind;
		final Consumer<WindowManager> action;

		Bind(String id, String name, Keybind bind, Consumer<WindowManager> action) {
			this.id = id;
			this.name = name;
			this.defaultBind = bind;
			this.bind = bind;
			this.action = action;
		}
	}

	private void bind(String id, String name, int key, int mods, Consumer<WindowManager> action) {
		all.put(id, new Bind(id, name, Keybind.key(key, mods), action));
	}

	/** Omarchy's tiling bindings, with the mod key standing in for Super (Hyprland keeps Super for itself). */
	private void registerAll() {
		int shift = GLFW.GLFW_MOD_SHIFT, ctrl = GLFW.GLFW_MOD_CONTROL;
		bind("launcher", "Launcher", GLFW.GLFW_KEY_SPACE, 0, wm -> wm.launcher.toggle());
		bind("console", "Console", GLFW.GLFW_KEY_ENTER, 0, wm -> wm.openPanel(CorePanels.CONSOLE));
		bind("keybinds", "Keybindings", GLFW.GLFW_KEY_K, 0, wm -> wm.openPanel(CorePanels.KEYBINDS));
		bind("close", "Close window", GLFW.GLFW_KEY_W, 0, wm -> wm.focused().ifPresent(Window::close));
		bind("togglesplit", "Toggle window split", GLFW.GLFW_KEY_J, 0, wm -> wm.focused().ifPresent(w -> wm.workspaces[wm.active].tiling.toggleSplit((WindowImpl) w)));
		bind("float", "Toggle floating/tiling", GLFW.GLFW_KEY_T, 0, wm -> wm.focused().ifPresent(w -> w.setFloating(!w.isFloating())));
		bind("fullscreen", "Full screen", GLFW.GLFW_KEY_F, 0, wm -> wm.focused().ifPresent(w -> w.setFullscreen(!w.isFullscreen())));
		bind("layout", "Toggle workspace layout", GLFW.GLFW_KEY_L, 0, wm -> cycleLayout());
		bind("hud", "Toggle HUD (scratchpad)", GLFW.GLFW_KEY_S, 0, wm -> wm.switchWorkspace(wm.active == Desktop.HUD_WORKSPACE ? wm.previous : Desktop.HUD_WORKSPACE));
		bind("move_hud", "Move window to HUD", GLFW.GLFW_KEY_S, shift, wm -> wm.focused().ifPresent(w -> w.moveToWorkspace(Desktop.HUD_WORKSPACE)));
		bind("next_workspace", "Next workspace", GLFW.GLFW_KEY_TAB, 0, wm -> cycleWorkspace(1));
		bind("prev_workspace", "Previous workspace", GLFW.GLFW_KEY_TAB, shift, wm -> cycleWorkspace(-1));
		bind("former_workspace", "Former workspace", GLFW.GLFW_KEY_TAB, ctrl, wm -> wm.switchWorkspace(wm.previous));
		bind("shrink", "Shrink window", GLFW.GLFW_KEY_MINUS, 0, wm -> resizeFocused(-0.05f));
		bind("grow", "Grow window", GLFW.GLFW_KEY_EQUAL, 0, wm -> resizeFocused(0.05f));
		bind("bar", "Toggle top bar", GLFW.GLFW_KEY_SPACE, shift, wm -> wm.theme.bar.toggle());
		bind("gaps", "Toggle window gaps", GLFW.GLFW_KEY_BACKSPACE, shift, wm -> toggleGaps());
		for (int i = 1; i <= 9; i++) {
			int ws = i;
			bind("workspace_" + i, "Switch to workspace " + i, GLFW.GLFW_KEY_0 + i, 0, wm -> {
				// The one showing: back to the game, so mod+N goes in and out of the menu on one key.
				if (wm.active == ws) wm.close();
				else wm.switchWorkspace(ws);
			});
			bind("move_" + i, "Move window to workspace " + i, GLFW.GLFW_KEY_0 + i, shift, wm -> wm.focused().ifPresent(w -> w.moveToWorkspace(ws)));
		}
		int[] keys = {GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN};
		Direction[] dirs = {Direction.LEFT, Direction.RIGHT, Direction.UP, Direction.DOWN};
		for (int i = 0; i < 4; i++) {
			Direction d = dirs[i];
			String n = d.name().toLowerCase();
			bind("focus_" + n, "Focus on " + n + " window", keys[i], 0, wm -> neighbor(d).ifPresent(wm::focus));
			bind("swap_" + n, "Swap window " + n, keys[i], shift, wm -> swapDirection(d));
		}
	}

	private int savedGapsIn = -1, savedGapsOut = -1;

	private void toggleGaps() {
		if (savedGapsIn < 0) {
			savedGapsIn = wm.theme.gapsIn.get();
			savedGapsOut = wm.theme.gapsOut.get();
			wm.theme.gapsIn.set(0);
			wm.theme.gapsOut.set(0);
		} else {
			wm.theme.gapsIn.set(savedGapsIn);
			wm.theme.gapsOut.set(savedGapsOut);
			savedGapsIn = savedGapsOut = -1;
		}
	}

	/** Next/previous workspace among 1-9 (wrapping), like Omarchy's Super+Tab. */
	void cycleWorkspace(int dir) {
		int from = wm.active == Desktop.HUD_WORKSPACE ? wm.previous : wm.active;
		wm.switchWorkspace(Math.floorMod(from - 1 + dir, Desktop.WORKSPACES) + 1);
	}

	private void resizeFocused(float amount) {
		WindowImpl w = wm.workspaces[wm.active].focused;
		if (w == null) return;
		if (w.floating && w.floatRect != null) {
			float d = amount * 400;
			Rect r = w.floatRect;
			w.floatRect = new Rect(r.x() - d / 2, r.y() - d / 2, Math.max(80, r.w() + d), Math.max(40, r.h() + d));
		} else {
			wm.workspaces[wm.active].tiling.resize(w, amount);
		}
		wm.markDirty();
	}

	private void cycleLayout() {
		Workspace ws = wm.workspaces[wm.active];
		List<Layout> layouts = new ArrayList<>(Myriad.layouts().values());
		if (layouts.isEmpty()) return;
		int i = layouts.indexOf(ws.layout);
		Layout next = layouts.get((i + 1) % layouts.size());
		ws.setLayout(next);
		Myriad.notifications().toast("Menu", "Layout: " + next.name()).lasting(1500).replacing("layout").show();
		wm.markDirty();
	}

	private Optional<WindowImpl> neighbor(Direction d) {
		Workspace ws = wm.workspaces[wm.active];
		WindowImpl from = ws.focused;
		if (from == null) return Optional.empty();
		Rect a = from.current();
		WindowImpl best = null;
		float bestScore = Float.MAX_VALUE;
		for (WindowImpl w : ws.drawOrder()) {
			if (w == from) continue;
			Rect b = w.current();
			float dx = b.centerX() - a.centerX(), dy = b.centerY() - a.centerY();
			boolean ok = switch (d) {
				case LEFT -> dx < -1 && Math.abs(dy) < Math.abs(dx) * 2;
				case RIGHT -> dx > 1 && Math.abs(dy) < Math.abs(dx) * 2;
				case UP -> dy < -1 && Math.abs(dx) < Math.abs(dy) * 2;
				case DOWN -> dy > 1 && Math.abs(dx) < Math.abs(dy) * 2;
			};
			if (!ok) continue;
			float score = dx * dx + dy * dy;
			if (score < bestScore) {
				bestScore = score;
				best = w;
			}
		}
		return Optional.ofNullable(best);
	}

	private void swapDirection(Direction d) {
		Workspace ws = wm.workspaces[wm.active];
		WindowImpl from = ws.focused;
		if (from == null || from.floating) return;
		neighbor(d).filter(w -> !w.floating).ifPresent(to -> {
			ws.tiling.swap(from, to);
			wm.markDirty();
		});
	}
}
