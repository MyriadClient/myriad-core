package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.ui.panels.CorePanels;

import java.util.Optional;

/** The elements on the HUD workspace: adding and removing them, and where each sits, anchored to a third of the screen. */
final class HudElements {
	private static final float SNAP = 4;
	/** The last drag snapped to an edge or centre line, across and down (the painter shows guides for it). */
	boolean snappedX, snappedY;

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
		PanelType.HudPlacement p = type.defaultHud();
		w.anchor = p != null ? p.anchor() : PanelType.Anchor.CENTER;
		w.anchorX = p != null ? p.offsetX() : 0;
		w.anchorY = p != null ? p.offsetY() : 0;
		wm.insert(w, true);
		wm.safe(w, panel::onOpen);
		wm.markDirty();
	}

	void remove(PanelType type) {
		element(type).ifPresent(wm::closeWindow);
	}

	/** Opens the HUD window as a floating window on the HUD workspace, unless it's already there. */
	void ensureEditor() {
		MyriadId id = CorePanels.HUD_ELEMENTS;
		if (wm.find(id, new JsonObject()).isPresent()) return;
		wm.openPanel(id, new JsonObject(), Desktop.HUD_WORKSPACE, true).ifPresent(win -> {
			WindowImpl w = (WindowImpl) win;
			// Wide enough for an element's options to unfold inline.
			float wd = Math.min(200, wm.screenW * 0.3f), ht = Math.min(300, wm.screenH * 0.6f);
			w.floatRect = new Rect(wm.screenW - wd - 20, wm.barHeight() + 20, wd, ht);
			w.placed = false;
		});
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
