package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.layout.LayoutState;
import dev.myriadclient.impl.CoreAddon;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.ui.WindowManager.Drag;
import dev.myriadclient.impl.ui.WindowManager.DragKind;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.MenuGroups;
import dev.myriadclient.impl.ui.panels.ModulesPanel;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The desktop's mouse and keys: clicks and drags on windows, title bars, dividers and the bar, the scroll wheel,
 * desktop binds, and what goes on to the panels. What it changes ({@link WindowManager#drag}, the mouse position) is
 * the manager's, which draws by it.
 */
final class DesktopInput {
	private final WindowManager wm;
	private final Minecraft mc = Minecraft.getInstance();
	private final ThemeSettings theme;
	private static CursorType resizeNwse, resizeNesw;

	DesktopInput(WindowManager wm) {
		this.wm = wm;
		theme = wm.theme;
	}

	private boolean shiftHeld() {
		long handle = mc.getWindow().handle();
		return WindowManager.down(handle, GLFW.GLFW_KEY_LEFT_SHIFT) || WindowManager.down(handle, GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	/** Moves {@code from}'s groups into {@code into} (as sections) and closes {@code from}. */
	private void merge(WindowImpl from, WindowImpl into) {
		ModulesPanel merged = ((ModulesPanel) into.panel).mergedWith((ModulesPanel) from.panel);
		wm.replacePanel(into, merged, MenuGroups.args(merged.groups()));
		wm.closeWindow(from);
		wm.markDirty();
	}

	/**
	 * Focus follows the mouse: the window under it becomes the focused one, for keys and the highlighted title bar. Only
	 * the focus moves (a floating window isn't raised, a column isn't scrolled into view), so nothing shifts under the
	 * cursor; a click still does both. Not while dragging, or with the launcher open over the desktop.
	 */
	void mouseMoved(double gx, double gy) {
		float mx = wm.toUnits(gx), my = wm.toUnits(gy);
		wm.mouseX = mx;
		wm.mouseY = my;
		if (wm.drag != null || wm.launcher.isOpen()) return;
		WindowImpl w = wm.topWindowAt(mx, my);
		if (w != null && !w.closing) wm.workspaces[wm.active].focused = w;
	}

	/**
	 * The cursor for what's being resized, or for what a press would resize: a divider between windows, a floating
	 * window's corner. Null for the usual arrow.
	 */
	@Nullable CursorType cursor(float mx, float my) {
		Drag d = wm.drag;
		if (d != null) return switch (d.kind) {
			case SPLITTER -> d.splitter.vertical() ? CursorTypes.RESIZE_EW : CursorTypes.RESIZE_NS;
			case RESIZE -> diagonal(d.cornerX, d.cornerY);
			case TILE_RESIZE -> d.edgeX != null && d.edgeY != null ? diagonal(d.cornerX, d.cornerY)
				: d.edgeX != null ? CursorTypes.RESIZE_EW : d.edgeY != null ? CursorTypes.RESIZE_NS : null;
			default -> null;
		};
		if (wm.launcher.isOpen()) return null;
		WindowImpl w = wm.topWindowAt(mx, my);
		if (w == null) {
			for (LayoutState.Splitter s : wm.workspaces[wm.active].tiling.splitters()) {
				if (s.bounds().contains(mx, my)) return s.vertical() ? CursorTypes.RESIZE_EW : CursorTypes.RESIZE_NS;
			}
			return null;
		}
		Rect r = w.current();
		return w.floating && !w.isHudElement() && mx > r.right() - 8 && my > r.bottom() - 8 ? diagonal(1, 1) : null;
	}

	/** Corner to corner: top-left and bottom-right share one arrow, the other two corners the other. */
	private static CursorType diagonal(int cornerX, int cornerY) {
		if (resizeNwse == null) {
			// Minecraft has no diagonal resize cursors of its own; GLFW's, falling back to the four-way arrow.
			resizeNwse = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "myriad_resize_nwse", CursorTypes.RESIZE_ALL);
			resizeNesw = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "myriad_resize_nesw", CursorTypes.RESIZE_ALL);
		}
		return cornerX == cornerY ? resizeNwse : resizeNesw;
	}

	boolean mouseClicked(double gx, double gy, int button) {
		float mx = wm.toUnits(gx), my = wm.toUnits(gy);
		wm.mouseX = mx;
		wm.mouseY = my;
		if (wm.launcher.mouseClicked(mx, my, button, wm.screenW, wm.screenH)) return true;
		if (theme.bar.get() && clickBar(mx, my, button)) return true;

		WindowImpl w = wm.topWindowAt(mx, my);
		if (w == null) return grabSplitter(mx, my);
		wm.focus(w);
		if (w.isHudElement()) {
			clickHudElement(w, mx, my, button);
			return true;
		}

		Rect r = w.current();
		if (wm.modHeld() && (button == 0 || button == 1)) {
			grabWithMod(w, r, mx, my, button);
			return true;
		}
		if (theme.titleBars.get() && !w.fullscreen && my < r.y() + theme.borderSize.get() + WindowManager.TITLE_H) {
			clickTitle(w, r, mx, my, button);
			return true;
		}

		// Floating windows can be resized from their bottom-right corner.
		if (w.floating && mx > r.right() - 8 && my > r.bottom() - 8) {
			wm.drag = new Drag();
			wm.drag.kind = DragKind.RESIZE;
			wm.drag.window = w;
			wm.drag.start = w.floatRect;
			wm.drag.startX = mx;
			wm.drag.startY = my;
			return true;
		}

		Rect content = wm.contentRect(w, r);
		if (content.contains(mx, my)) {
			wm.drag = new Drag();
			wm.drag.kind = DragKind.PANEL;
			wm.drag.window = w;
			wm.drag.button = button;
			try {
				w.panel.mouseClicked(mx - content.x(), my - content.y(), button);
			} catch (Throwable t) {
				WindowManager.LOG.error("Panel {} threw on click", w.type.id(), t);
			}
		}
		return true;
	}

	/** A click on the bar goes to the widget under it; true if it was on the bar at all. */
	private boolean clickBar(float mx, float my, int button) {
		float h = wm.barHeight(), by = wm.barAtTop() ? 0 : wm.screenH - h;
		if (my < by || my >= by + h) return false;
		for (DesktopPainter.BarSlot s : wm.painter().barLayout(UiRenderer.get())) {
			if (mx >= s.x() && mx < s.x() + s.w()) {
				try {
					s.widget().mouseClicked(mx - s.x(), my - by, button);
				} catch (Throwable t) {
					WindowManager.LOG.error("Bar widget {} threw", s.widget().id(), t);
				}
				return true;
			}
		}
		return true;
	}

	/** Between windows: grabs the divider under the cursor, if there is one. */
	private boolean grabSplitter(float mx, float my) {
		for (LayoutState.Splitter s : wm.workspaces[wm.active].tiling.splitters()) {
			if (s.bounds().contains(mx, my)) {
				wm.drag = new Drag();
				wm.drag.kind = DragKind.SPLITTER;
				wm.drag.splitter = s;
				return true;
			}
		}
		return false;
	}

	/** A HUD element: dragged with the left button, its settings opened with the right. */
	private void clickHudElement(WindowImpl w, float mx, float my, int button) {
		Rect r = wm.hud.rect(w);
		if (button == 0) {
			wm.drag = new Drag();
			wm.drag.kind = DragKind.HUD;
			wm.drag.window = w;
			wm.drag.offX = mx - r.x();
			wm.drag.offY = my - r.y();
		} else if (button == 1) {
			openPanelSettings(w);
		}
	}

	/** With the desktop's modifier held: the left button moves (or swaps) the window, the right resizes it. */
	private void grabWithMod(WindowImpl w, Rect r, float mx, float my, int button) {
		wm.drag = new Drag();
		wm.drag.window = w;
		wm.drag.start = w.floating ? w.floatRect : r;
		wm.drag.startX = mx;
		wm.drag.startY = my;
		wm.drag.offX = mx - r.x();
		wm.drag.offY = my - r.y();
		if (button == 0) {
			wm.drag.kind = w.floating ? DragKind.MOVE : DragKind.SWAP;
		} else if (w.floating) {
			// Like Hyprland: grab the corner nearest the cursor.
			wm.drag.kind = DragKind.RESIZE;
			wm.drag.cornerX = mx < r.centerX() ? -1 : 1;
			wm.drag.cornerY = my < r.centerY() ? -1 : 1;
		} else {
			beginTileResize(wm.drag, w, mx, my);
		}
	}

	/** The title bar: its controls, close and float, the right button for settings, else dragging the window. */
	private void clickTitle(WindowImpl w, Rect r, float mx, float my, int button) {
		if (button == 0) {
			for (var e : wm.painter().titleControls(w, r)) {
				if (mx >= e.getValue() && mx < e.getValue() + e.getKey().width()) {
					try {
						e.getKey().click();
					} catch (Throwable t) {
						WindowManager.LOG.error("Title control of {} threw", w.type.id(), t);
					}
					wm.markDirty();
					return;
				}
			}
		}
		float bx = r.right() - theme.borderSize.get() - 24 - wm.titleInset(w);
		if (button == 0 && mx >= bx + 12 && mx < bx + 23) {
			wm.closeWindow(w);
			return;
		}
		if (button == 0 && mx >= bx && mx < bx + 11) {
			wm.setFloating(w, !w.floating);
			return;
		}
		if (button == 1) {
			openPanelSettings(w);
			return;
		}
		wm.drag = new Drag();
		wm.drag.window = w;
		wm.drag.kind = w.floating ? DragKind.MOVE : DragKind.SWAP;
		wm.drag.offX = mx - r.x();
		wm.drag.offY = my - r.y();
		wm.drag.start = w.floating ? w.floatRect : r;
	}

	/**
	 * Tiled resize: pick the dividers on the window's left-or-right and top-or-bottom edges (whichever side the cursor
	 * is on), so dragging moves those edges. Works for any layout, since it goes through the layout's splitters.
	 */
	private void beginTileResize(Drag d, WindowImpl w, float mx, float my) {
		d.kind = DragKind.TILE_RESIZE;
		d.cornerX = mx < w.target.centerX() ? -1 : 1;
		d.cornerY = my < w.target.centerY() ? -1 : 1;
		Rect r = w.target;
		float reach = theme.gapsIn.get() + 8;
		float edgeX = mx < r.centerX() ? r.x() : r.right();
		float edgeY = my < r.centerY() ? r.y() : r.bottom();
		float bestX = reach, bestY = reach;
		for (LayoutState.Splitter sp : wm.workspaces[w.workspace].tiling.splitters()) {
			Rect b = sp.bounds();
			if (sp.vertical()) {
				float dist = Math.abs(b.centerX() - edgeX);
				boolean overlaps = b.y() < r.bottom() && b.bottom() > r.y();
				if (overlaps && dist < bestX) {
					bestX = dist;
					d.edgeX = sp;
					d.edgeStartX = b.centerX();
				}
			} else {
				float dist = Math.abs(b.centerY() - edgeY);
				boolean overlaps = b.x() < r.right() && b.right() > r.x();
				if (overlaps && dist < bestY) {
					bestY = dist;
					d.edgeY = sp;
					d.edgeStartY = b.centerY();
				}
			}
		}
	}

	private void openPanelSettings(WindowImpl w) {
		if (w.panel.settings.all().isEmpty()) return;
		JsonObject args = new JsonObject();
		args.addProperty("window", w.uid);
		wm.openPanel(CorePanels.PANEL_SETTINGS, args, wm.active, true);
	}

	boolean mouseDragged(double gx, double gy, int button) {
		float mx = wm.toUnits(gx), my = wm.toUnits(gy);
		wm.mouseX = mx;
		wm.mouseY = my;
		if (wm.drag == null) return false;
		switch (wm.drag.kind) {
			case MOVE -> {
				Rect s = wm.drag.window.floatRect;
				wm.drag.window.floatRect = new Rect(mx - wm.drag.offX, my - wm.drag.offY, s.w(), s.h());
				wm.drag.window.setTarget(wm.drag.window.floatRect, 0, theme.bezier(), false);
			}
			case RESIZE -> {
				Rect s = wm.drag.start;
				float dx = mx - wm.drag.startX, dy = my - wm.drag.startY;
				float w = Math.max(80, s.w() + dx * wm.drag.cornerX), h = Math.max(40, s.h() + dy * wm.drag.cornerY);
				float x = wm.drag.cornerX < 0 ? s.right() - w : s.x(), y = wm.drag.cornerY < 0 ? s.bottom() - h : s.y();
				wm.drag.window.floatRect = new Rect(x, y, w, h);
				wm.drag.window.setTarget(wm.drag.window.floatRect, 0, theme.bezier(), false);
			}
			case TILE_RESIZE -> {
				if (wm.drag.edgeX != null) wm.drag.edgeX.drag(wm.drag.edgeStartX + mx - wm.drag.startX, my);
				if (wm.drag.edgeY != null) wm.drag.edgeY.drag(mx, wm.drag.edgeStartY + my - wm.drag.startY);
			}
			case SPLITTER -> wm.drag.splitter.drag(mx, my);
			case HUD -> wm.hud.setPosition(wm.drag.window, mx - wm.drag.offX, my - wm.drag.offY, wm.safePreferred(wm.drag.window.panel));
			case PANEL -> {
				Rect content = wm.contentRect(wm.drag.window, wm.drag.window.current());
				try {
					wm.drag.window.panel.mouseDragged(mx - content.x(), my - content.y(), wm.drag.button, 0, 0);
				} catch (Throwable t) {
					WindowManager.LOG.error("Panel {} threw on drag", wm.drag.window.type.id(), t);
				}
			}
			case SWAP -> {
			}
		}
		return true;
	}

	boolean mouseReleased(double gx, double gy, int button) {
		float mx = wm.toUnits(gx), my = wm.toUnits(gy);
		Drag d = wm.drag;
		WindowImpl mergeInto = wm.mergeTarget();
		wm.drag = null;
		if (d == null) return false;
		if (mergeInto != null) {
			merge(d.window, mergeInto);
			return true;
		}
		switch (d.kind) {
			case SWAP -> {
				WindowImpl target = wm.topWindowAt(mx, my);
				if (target != null && target != d.window && !target.floating && !d.window.floating) {
					wm.workspaces[wm.active].tiling.swap(d.window, target);
					wm.markDirty();
				}
			}
			case PANEL -> {
				Rect content = wm.contentRect(d.window, d.window.current());
				try {
					d.window.panel.mouseReleased(mx - content.x(), my - content.y(), button);
				} catch (Throwable t) {
					WindowManager.LOG.error("Panel {} threw on release", d.window.type.id(), t);
				}
			}
			default -> wm.markDirty();
		}
		return true;
	}

	boolean mouseScrolled(double gx, double gy, double amount) {
		float mx = wm.toUnits(gx), my = wm.toUnits(gy);
		if (wm.launcher.mouseScrolled((float) amount)) return true;
		if (wm.modHeld()) {
			wm.binds.cycleWorkspace(amount > 0 ? -1 : 1);
			return true;
		}
		WindowImpl w = wm.topWindowAt(mx, my);
		// Sideways through a row of columns wider than the screen: Shift + wheel anywhere, or the wheel over a gap.
		if ((shiftHeld() || w == null) && wm.active != Desktop.HUD_WORKSPACE && wm.workspaces[wm.active].tiling.scroll((float) amount)) return true;
		if (w == null || w.isHudElement()) return false;
		Rect content = wm.contentRect(w, w.current());
		try {
			return w.panel.mouseScrolled(mx - content.x(), my - content.y(), (float) amount);
		} catch (Throwable t) {
			WindowManager.LOG.error("Panel {} threw on scroll", w.type.id(), t);
			return false;
		}
	}

	boolean keyPressed(int key, int scancode, int mods) {
		if (wm.launcher.isOpen()) return wm.launcher.keyPressed(key, mods);
		Workspace ws = wm.workspaces[wm.active];
		WindowImpl focused = ws.focused;
		boolean typing = focused != null && focused.panel.capturesKeyboard();

		// A panel that's being typed in sees the key first; what it doesn't take (mod+key in the console) is the desktop's.
		if (typing && panelKey(focused, key, scancode, mods)) return true;
		if ((mods & wm.modMask()) != 0) {
			int rest = mods & ~wm.modMask();
			for (DesktopBinds.Bind b : wm.binds.all().values()) {
				if (b.bind.code() == key && !b.bind.mouse() && (b.bind.modifiers() & ~GLFW.GLFW_MOD_NUM_LOCK & ~GLFW.GLFW_MOD_CAPS_LOCK) == (rest & ~GLFW.GLFW_MOD_NUM_LOCK & ~GLFW.GLFW_MOD_CAPS_LOCK)) {
					b.action.accept(wm);
					return true;
				}
			}
		}
		if (wm.active == Desktop.HUD_WORKSPACE && (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) && !typing) {
			WindowImpl hovered = wm.topWindowAt(wm.mouseX, wm.mouseY);
			WindowImpl target = hovered != null && hovered.isHudElement() ? hovered : focused != null && focused.isHudElement() ? focused : null;
			if (target != null) {
				wm.closeWindow(target);
				return true;
			}
		}
		if (!typing && focused != null && panelKey(focused, key, scancode, mods)) return true;
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			wm.close();
			return true;
		}
		for (var action : Myriad.keyActions()) {
			if (action.id().equals(CoreAddon.OPEN_MENU) && action.bind().matchesKey(key, mods)) {
				wm.close();
				return true;
			}
		}
		return false;
	}

	private boolean panelKey(WindowImpl w, int key, int scancode, int mods) {
		try {
			return w.panel.keyPressed(key, scancode, mods);
		} catch (Throwable t) {
			WindowManager.LOG.error("Panel {} threw on key", w.type.id(), t);
			return false;
		}
	}

	boolean charTyped(char chr, int mods) {
		if (wm.launcher.isOpen()) return wm.launcher.charTyped(chr);
		if (wm.modHeld()) return true;
		WindowImpl focused = wm.workspaces[wm.active].focused;
		if (focused == null) return false;
		try {
			return focused.panel.charTyped(chr, mods);
		} catch (Throwable t) {
			WindowManager.LOG.error("Panel {} threw on char", focused.type.id(), t);
			return false;
		}
	}
}
