package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.impl.ui.panels.CorePanels;

import java.util.ArrayList;
import java.util.Optional;

/** The elements on the HUD workspace: adding and removing them, and where each sits, anchored to a third of the screen. */
final class HudElements {
	private static final float SNAP = 4;
	private static final float SIDEBAR_WIDTH = 200, SIDEBAR_MIN = 140, SIDEBAR_GAP = 6, HANDLE_WIDTH = 10, HANDLE_HEIGHT = 36;
	/** The last drag snapped to an edge or centre line, across and down (the painter shows guides for it). */
	boolean snappedX, snappedY;
	/** The sidebar is put away, so what's behind it can be placed. Saved with its panel. */
	boolean collapsed;

	private final WindowManager wm;
	private final ThemeSettings theme;

	HudElements(WindowManager wm) {
		this.wm = wm;
		theme = wm.theme;
	}

	/** The HUD element of this type, if it's on the HUD. */
	Optional<WindowImpl> element(PanelType type) {
		for (WindowImpl w : wm.workspaces[Desktop.HUD_WORKSPACE].floating) if (w.type == type && w.isHudElement()) return Optional.of(w);
		return Optional.empty();
	}

	/** Puts a HUD-capable panel on the HUD at its default spot (or the screen centre). */
	void add(PanelType type) {
		if (!type.isHud() || element(type).isPresent()) return;
		Panel panel = wm.createPanel(type, new JsonObject());
		if (panel == null) return;
		WindowImpl w = new WindowImpl(wm, wm.nextUid++, type, new JsonObject(), panel, Desktop.HUD_WORKSPACE);
		w.floating = true;
		placeDefault(w);
		wm.insert(w, true);
		wm.safe(w, panel::onOpen);
		wm.markDirty();
	}

	private void placeDefault(WindowImpl w) {
		PanelType.HudPlacement p = w.type.defaultHud();
		w.anchor = p != null ? p.anchor() : PanelType.Anchor.CENTER;
		w.anchorX = p != null ? p.offsetX() : 0;
		w.anchorY = p != null ? p.offsetY() : 0;
	}

	void remove(PanelType type) {
		element(type).ifPresent(wm::closeWindow);
	}

	/** Opens the sidebar on the HUD workspace, unless it's there. */
	void ensureEditor() {
		Optional<Window> open = wm.find(CorePanels.HUD_ELEMENTS, new JsonObject());
		if (open.isPresent()) {
			((WindowImpl) open.get()).fullscreen = false;
			return;
		}
		wm.openPanel(CorePanels.HUD_ELEMENTS, new JsonObject(), Desktop.HUD_WORKSPACE, true).ifPresent(w -> ((WindowImpl) w).placed = false);
	}

	/**
	 * Puts back what older versions let leave the HUD: the sidebar, and elements opened on other workspaces (which had
	 * disappeared from the game). Run on anything loaded, so it needs no record of having run.
	 */
	void adoptStrays() {
		for (WindowImpl w : new ArrayList<>(wm.windows)) {
			if (w.workspace == Desktop.HUD_WORKSPACE) continue;
			if (w.isHudEditor()) {
				wm.moveToWorkspace(w, Desktop.HUD_WORKSPACE);
			} else if (w.type.isHud() && element(w.type).isPresent()) {
				wm.closeWindow(w);
			} else if (w.type.isHud()) {
				wm.moveToWorkspace(w, Desktop.HUD_WORKSPACE);
				placeDefault(w);
			}
		}
		ensureEditor();
	}

	/** Where the sidebar goes: just in from the right edge, the height of the work area; past the edge while it's collapsed. */
	Rect sidebar() {
		Rect area = wm.workArea().inset(SIDEBAR_GAP);
		float w = Math.min(wm.screenW - HANDLE_WIDTH - SIDEBAR_GAP * 2, Math.max(SIDEBAR_MIN, Math.min(SIDEBAR_WIDTH, wm.screenW * 0.3f)));
		return new Rect(collapsed ? wm.screenW : area.right() - w, area.y(), w, area.h());
	}

	/** The sidebar, if it's on the HUD workspace. */
	Optional<WindowImpl> editor() {
		for (WindowImpl w : wm.workspaces[Desktop.HUD_WORKSPACE].floating) if (w.isHudEditor()) return Optional.of(w);
		return Optional.empty();
	}

	/** The tab on the sidebar's left edge that opens and collapses it. */
	Rect handle(WindowImpl editor) {
		Rect area = wm.workArea();
		return new Rect(editor.current().x() - HANDLE_WIDTH, area.y() + (area.h() - HANDLE_HEIGHT) / 2, HANDLE_WIDTH, HANDLE_HEIGHT);
	}

	boolean handleAt(float x, float y) {
		return editor().filter(e -> handle(e).contains(x, y)).isPresent();
	}

	void toggleCollapsed() {
		collapsed = !collapsed;
		wm.markDirty();
	}

	/** Where a HUD element is on screen: its anchor and offset, kept on screen. */
	Rect rect(WindowImpl w) {
		Rect pref = wm.safePreferred(w.panel);
		float pw = pref != null ? pref.w() : 80, ph = pref != null ? pref.h() : 20;
		float pad = theme.hudPadding.get();
		float x = pad + w.anchor.fx() * (wm.screenW - pad * 2 - pw) + w.anchorX;
		float y = pad + w.anchor.fy() * (wm.screenH - pad * 2 - ph) + w.anchorY;
		x = Math.clamp(x, 0, Math.max(0, wm.screenW - pw));
		y = Math.clamp(y, 0, Math.max(0, wm.screenH - ph));
		return new Rect(x, y, pw, ph);
	}

	/** Re-anchors a HUD element to the screen third its centre is in, keeping it where it is. */
	void setPosition(WindowImpl w, float x, float y, Rect pref) {
		float pw = pref != null ? pref.w() : 80, ph = pref != null ? pref.h() : 20;
		float pad = theme.hudPadding.get();
		snappedX = snappedY = false;
		// Snap to edges and centre lines.
		if (Math.abs(x - pad) < SNAP) {
			x = pad;
			snappedX = true;
		} else if (Math.abs(x + pw - (wm.screenW - pad)) < SNAP) {
			x = wm.screenW - pad - pw;
			snappedX = true;
		} else if (Math.abs(x + pw / 2 - wm.screenW / 2) < SNAP) {
			x = wm.screenW / 2 - pw / 2;
			snappedX = true;
		}
		if (Math.abs(y - pad) < SNAP) {
			y = pad;
			snappedY = true;
		} else if (Math.abs(y + ph - (wm.screenH - pad)) < SNAP) {
			y = wm.screenH - pad - ph;
			snappedY = true;
		} else if (Math.abs(y + ph / 2 - wm.screenH / 2) < SNAP) {
			y = wm.screenH / 2 - ph / 2;
			snappedY = true;
		}
		int col = (x + pw / 2) < wm.screenW / 3 ? 0 : (x + pw / 2) < wm.screenW * 2 / 3 ? 1 : 2;
		int row = (y + ph / 2) < wm.screenH / 3 ? 0 : (y + ph / 2) < wm.screenH * 2 / 3 ? 1 : 2;
		w.anchor = PanelType.Anchor.of(col, row);
		w.anchorX = x - (pad + w.anchor.fx() * (wm.screenW - pad * 2 - pw));
		w.anchorY = y - (pad + w.anchor.fy() * (wm.screenH - pad * 2 - ph));
	}}
