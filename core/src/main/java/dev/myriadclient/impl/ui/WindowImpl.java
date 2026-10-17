package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.Window;

/** A window: a panel plus its placement and animation state. */
public final class WindowImpl implements Window {
	final WindowManager wm;
	final int uid;
	final PanelType type;
	final JsonObject args;
	Panel panel;
	int workspace;
	boolean floating, fullscreen;
	/** Floating geometry in UI units (desktop windows). */
	Rect floatRect;
	/** HUD placement (workspace 0 HUD elements). */
	PanelType.Anchor anchor = PanelType.Anchor.TOP_LEFT;
	float anchorX, anchorY;

	final Animated ax = new Animated(0), ay = new Animated(0), aw = new Animated(0), ah = new Animated(0);
	final Animated opacity = new Animated(0);
	final Animated focusGlow = new Animated(0);
	boolean placed;
	boolean closing;
	Rect target = Rect.ZERO;

	WindowImpl(WindowManager wm, int uid, PanelType type, JsonObject args, Panel panel, int workspace) {
		this.wm = wm;
		this.uid = uid;
		this.type = type;
		this.args = args;
		this.panel = panel;
		this.workspace = workspace;
		panel.attach(this);
	}

	/** True for HUD elements: HUD-capable panels living on the HUD workspace (drawn in game). */
	boolean isHudElement() {
		return workspace == Desktop.HUD_WORKSPACE && type.isHud();
	}

	/** Sets where the window should be; animates there unless it is being placed for the first time. */
	void setTarget(Rect r, float durationMs, dev.myriadclient.api.render.Bezier curve, boolean animate) {
		target = r;
		if (!placed || !animate) {
			ax.snap(r.x());
			ay.snap(r.y());
			aw.snap(r.w());
			ah.snap(r.h());
			placed = true;
			return;
		}
		ax.animateTo(r.x(), durationMs, curve);
		ay.animateTo(r.y(), durationMs, curve);
		aw.animateTo(r.w(), durationMs, curve);
		ah.animateTo(r.h(), durationMs, curve);
	}

	Rect current() {
		return new Rect(ax.get(), ay.get(), aw.get(), ah.get());
	}

	@Override
	public Panel panel() {
		return panel;
	}

	@Override
	public PanelType type() {
		return type;
	}

	@Override
	public JsonObject args() {
		return args;
	}

	@Override
	public int workspace() {
		return workspace;
	}

	@Override
	public boolean isFloating() {
		return floating;
	}

	@Override
	public void setFloating(boolean floating) {
		wm.setFloating(this, floating);
	}

	@Override
	public boolean isFullscreen() {
		return fullscreen;
	}

	@Override
	public void setFullscreen(boolean fullscreen) {
		this.fullscreen = fullscreen;
		wm.markDirty();
	}

	@Override
	public Rect bounds() {
		return current();
	}

	@Override
	public void focus() {
		wm.focus(this);
	}

	@Override
	public void close() {
		wm.closeWindow(this);
	}

	@Override
	public void moveToWorkspace(int workspace) {
		wm.moveToWorkspace(this, workspace);
	}

	public int uid() {
		return uid;
	}

	@Override
	public float hudAlignX() {
		return anchor.fx();
	}

	@Override
	public String toString() {
		return "Window#" + uid + "(" + type.id() + ")";
	}
}
