package dev.myriadclient.impl.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.Canvas;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.WidgetPanel;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.CoreAddon;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.service.NotificationManager;
import dev.myriadclient.impl.ui.layout.DwindleLayout;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.DialogPanel;
import dev.myriadclient.impl.ui.panels.ModuleGroup;
import dev.myriadclient.impl.ui.panels.ModulesPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Hyprland-inspired window manager behind {@link Desktop}: workspaces with tiling layouts and floating windows,
 * keyboard-driven focus/move/resize, animated window placement and workspace switching, and the HUD workspace whose
 * windows are drawn in game.
 */
public final class WindowManager implements Desktop {
	static final Logger LOG = LoggerFactory.getLogger("Myriad/Desktop");
	static final float TITLE_H = 13;
	/** Bump to rebuild users' workspace layouts (theme and HUD are kept) after a default-layout change. */
	private static final int LAYOUT_VERSION = 3;
	private static final float SNAP = 4;

	final Minecraft mc = Minecraft.getInstance();
	final ThemeSettings theme = new ThemeSettings();
	private final ThemeManager themes = new ThemeManager(theme);
	private final SettingWidgetsImpl settingWidgets = new SettingWidgetsImpl();
	final Workspace[] workspaces = new Workspace[WORKSPACES + 1];
	final List<WindowImpl> windows = new ArrayList<>();
	final List<WindowImpl> closing = new ArrayList<>();
	final Launcher launcher = new Launcher(this);
	private final DesktopPainter painter = new DesktopPainter(this);
	private final CategoryWindows categories = new CategoryWindows(this);
	private final DesktopBinds binds = new DesktopBinds(this);
	final Set<WindowImpl> failedPanels = new HashSet<>();
	final Animated transition = new Animated(1);
	final long borderEpoch = System.nanoTime();
	private Runnable dirtyHook = () -> {
	};

	private JsonArray orphanWindows = new JsonArray();
	/** Modules came or went since the desktop was last laid out: category windows need rebuilding, new groups placing. */
	private boolean modulesChanged;
	/** Each workspace's tiling as loaded, so a window revived later (its modules came late) can take its old place. */
	private final JsonObject[] savedTiling = new JsonObject[WORKSPACES + 1];
	int active = 1, previous = 1;
	private int nextUid = 1;

	// Frame/input state
	float pixelScale = 2, screenW = 960, screenH = 540;
	float mouseX, mouseY;
	Drag drag;
	private WindowImpl pressed;
	boolean snappedX, snappedY;

	public WindowManager() {
		for (int i = 0; i <= WORKSPACES; i++) workspaces[i] = new Workspace(i, new DwindleLayout());
	}

	public void setDirtyHook(Runnable hook) {
		this.dirtyHook = hook;
	}

	void markDirty() {
		dirtyHook.run();
	}

	// ================================================================================================================
	// Desktop API

	@Override
	public void open() {
		refreshModules();
		if (!(mc.gui.screen() instanceof DesktopScreen)) mc.gui.setScreen(new DesktopScreen(this));
	}

	/** Runs one of the desktop's own bindings by id ("launcher", "layout", "close", ...), for the dev console. */
	public void runBind(String id) {
		DesktopBinds.Bind b = binds.get(id);
		if (b == null) throw new IllegalArgumentException("no desktop binding '" + id + "'; one of " + binds.all().keySet());
		b.action.accept(this);
	}

	/** Opens the launcher with a query typed, for the dev console. */
	public void showLauncher(String query) {
		launcher.show(query);
	}

	/** Called when a module is registered or removed; the next {@link #open} refreshes the category windows. */
	public void modulesChanged() {
		modulesChanged = true;
	}

	/** Before the desktop shows: rebuilds category windows and places new groups if modules came or went since. */
	public void refreshModules() {
		if (!modulesChanged) return;
		modulesChanged = false;
		reviveOrphans();
		for (WindowImpl w : windows) if (w.panel instanceof ModulesPanel p) safe(w, p::rebuild);
		categories.placeNewGroups(true);
	}

	/**
	 * Brings back saved windows that couldn't be built at load because their modules weren't registered yet (an addon
	 * that bridges another mod registers once that mod is up). Each goes to its saved workspace; a tiled one takes the
	 * place it had in that workspace's saved tiling, so the layout comes back as it was.
	 */
	private void reviveOrphans() {
		if (orphanWindows.isEmpty()) return;
		JsonArray still = new JsonArray();
		List<WindowImpl> revived = new ArrayList<>();
		for (JsonElement e : orphanWindows) {
			JsonObject j = e.getAsJsonObject();
			WindowImpl w = null;
			try {
				w = loadWindow(j);
			} catch (RuntimeException ex) {
				LOG.error("Skipping broken window entry {}", j, ex);
				continue;
			}
			if (w == null) still.add(j);
			else revived.add(w);
		}
		orphanWindows = still;
		if (revived.isEmpty()) return;
		Set<Integer> touched = new HashSet<>();
		for (WindowImpl w : revived) {
			windows.add(w);
			w.opacity.snap(1);
			if (w.floating) workspaces[w.workspace].floating.add(w);
			else touched.add(w.workspace);
			safe(w, w.panel::onOpen);
		}
		for (int idx : touched) {
			Workspace ws = workspaces[idx];
			Map<String, WindowImpl> byUid = new LinkedHashMap<>();
			for (WindowImpl w : windows) if (w.workspace == idx && !w.floating) byUid.put(String.valueOf(w.uid), w);
			Set<WindowImpl> placed = new HashSet<>();
			if (savedTiling[idx] != null) {
				ws.tiling.load(savedTiling[idx], key -> {
					WindowImpl w = byUid.get(key);
					return w != null && placed.add(w) ? w : null;
				});
			}
			for (WindowImpl w : byUid.values()) if (!placed.contains(w)) ws.tiling.add(w, null);
		}
		markDirty();
	}

	@Override
	public void close() {
		if (mc.gui.screen() instanceof DesktopScreen) mc.gui.setScreen(null);
	}

	@Override
	public boolean isOpen() {
		return mc.gui.screen() instanceof DesktopScreen;
	}

	@Override
	public int activeWorkspace() {
		return active;
	}

	@Override
	public void switchWorkspace(int workspace) {
		workspace = Math.clamp(workspace, 0, WORKSPACES);
		if (workspace == active) return;
		previous = active;
		active = workspace;
		transition.snap(0);
		transition.animateTo(1, theme.durationMs(theme.workspaceSpeed), theme.bezier());
		drag = null;
		if (workspace == HUD_WORKSPACE) ensureHudEditor();
		markDirty();
	}

	// ---- HUD elements ---------------------------------------------------------------------------------------------

	/** The HUD element of this type, if it's on the HUD. */
	public Optional<WindowImpl> hudElement(PanelType type) {
		for (WindowImpl w : workspaces[HUD_WORKSPACE].floating) if (w.type == type && w.isHudElement()) return Optional.of(w);
		return Optional.empty();
	}

	/** Puts a HUD-capable panel on the HUD at its default spot (or the screen centre). */
	public void addHudElement(PanelType type) {
		if (!type.isHud() || hudElement(type).isPresent()) return;
		Panel panel = createPanel(type, new JsonObject());
		if (panel == null) return;
		WindowImpl w = new WindowImpl(this, nextUid++, type, new JsonObject(), panel, HUD_WORKSPACE);
		w.floating = true;
		PanelType.HudPlacement p = type.defaultHud();
		w.anchor = p != null ? p.anchor() : PanelType.Anchor.CENTER;
		w.anchorX = p != null ? p.offsetX() : 0;
		w.anchorY = p != null ? p.offsetY() : 0;
		insert(w, true);
		safe(w, panel::onOpen);
		markDirty();
	}

	public void removeHudElement(PanelType type) {
		hudElement(type).ifPresent(this::closeWindow);
	}

	/** Opens the HUD window as a floating window on the HUD workspace, unless it's already there. */
	private void ensureHudEditor() {
		MyriadId id = CorePanels.HUD_ELEMENTS;
		if (find(id, new JsonObject()).isPresent()) return;
		openPanel(id, new JsonObject(), HUD_WORKSPACE, true).ifPresent(win -> {
			WindowImpl w = (WindowImpl) win;
			// Wide enough for an element's options to unfold inline.
			float wd = Math.min(200, screenW * 0.3f), ht = Math.min(300, screenH * 0.6f);
			w.floatRect = new Rect(screenW - wd - 20, barHeight() + 20, wd, ht);
			w.placed = false;
		});
	}

	@Override
	public void confirm(String title, String message, String confirmLabel, Runnable onConfirm, Runnable onCancel) {
		if (!isOpen()) open();
		openPanel(CorePanels.DIALOG, DialogPanel.prepare(title, message, confirmLabel, onConfirm, onCancel), active == HUD_WORKSPACE ? 1 : active, true);
	}

	@Override
	public Optional<Window> openPanel(MyriadId type, JsonObject args) {
		Optional<PanelType> t = Myriad.panels().get(type);
		if (t.isEmpty()) return Optional.empty();
		int ws = active == HUD_WORKSPACE && !t.get().isHud() ? HUD_WORKSPACE : active;
		boolean floating = ws == HUD_WORKSPACE;
		return openPanel(type, args, ws, floating);
	}

	@Override
	public Optional<Window> openPanel(MyriadId type, JsonObject args, int workspace, boolean floating) {
		JsonObject a = args == null ? new JsonObject() : args;
		Optional<Window> existing = find(type, a);
		if (existing.isPresent()) {
			WindowImpl w = (WindowImpl) existing.get();
			if (w.workspace != workspace && workspace != HUD_WORKSPACE) moveToWorkspace(w, workspace);
			if (isOpen() && w.workspace != active) switchWorkspace(w.workspace);
			focus(w);
			return existing;
		}
		Optional<PanelType> t = Myriad.panels().get(type);
		if (t.isEmpty()) return Optional.empty();
		Panel panel = createPanel(t.get(), a);
		if (panel == null) return Optional.empty();
		WindowImpl w = new WindowImpl(this, nextUid++, t.get(), a, panel, workspace);
		w.floating = floating || workspace == HUD_WORKSPACE;
		if (w.floating) w.floatRect = defaultFloatRect(panel);
		insert(w, true);
		safe(w, panel::onOpen);
		markDirty();
		return Optional.of(w);
	}

	private Panel createPanel(PanelType type, JsonObject args) {
		try {
			return type.create(args);
		} catch (Throwable t) {
			LOG.error("Panel {} failed to create", type.id(), t);
			Myriad.notifications().error("Menu", "Could not open " + type.name() + ": " + t.getMessage());
			return null;
		}
	}

	@Override
	public Optional<Window> find(MyriadId type, JsonObject args) {
		JsonObject a = args == null ? new JsonObject() : args;
		for (WindowImpl w : windows) if (w.type.id().equals(type) && w.args.equals(a)) return Optional.of(w);
		return Optional.empty();
	}

	@Override
	public List<Window> windows() {
		return Collections.unmodifiableList(new ArrayList<>(windows));
	}

	@Override
	public Optional<Window> focused() {
		return Optional.ofNullable(workspaces[active].focused);
	}

	@Override
	public ThemeSettings theme() {
		return theme;
	}

	@Override
	public void applyTheme(MyriadId id) {
		if (themes.apply(id)) {
			Myriad.notifications().toast("Theme", "Applied " + themes.active().name()).level(Notifications.Level.SUCCESS).lasting(2000).replacing("theme").show();
		}
	}

	public ThemeManager themes() {
		return themes;
	}

	@Override
	public SettingWidgets settingWidgets() {
		return settingWidgets;
	}

	public void toggleLauncher() {
		launcher.toggle();
	}

	boolean launcherOpen() {
		return launcher.isOpen();
	}

	/**
	 * The desktop's shortcuts that work without the menu open, in game and on the title screen: mod+1..9 opens the menu
	 * on that workspace, mod+S on the HUD, mod+Space the launcher (on its own over the game). True if the key was one.
	 */
	public boolean globalKey(int key, int mods) {
		if ((mods & modMask()) == 0 || isOpen() || mc.gui.screen() instanceof LauncherScreen) return false;
		int rest = mods & ~modMask() & ~GLFW.GLFW_MOD_NUM_LOCK & ~GLFW.GLFW_MOD_CAPS_LOCK;
		for (DesktopBinds.Bind b : binds.all().values()) {
			if (b.bind.mouse() || b.bind.code() != key || (b.bind.modifiers() & ~GLFW.GLFW_MOD_NUM_LOCK & ~GLFW.GLFW_MOD_CAPS_LOCK) != rest) continue;
			if (b.id.equals("launcher")) {
				// On its own, over the game or the title screen: no workspace opens unless what it runs needs one.
				Screen behind = mc.gui.screen();
				launcher.show("", true);
				mc.gui.setScreen(new LauncherScreen(this, behind));
				return true;
			}
			int target = b.id.equals("hud") ? HUD_WORKSPACE : b.id.startsWith("workspace_") ? Integer.parseInt(b.id.substring(10)) : -1;
			if (target < 0) continue;
			// Straight onto it: no slide from the workspace the menu was last on.
			switchWorkspace(target);
			transition.snap(1);
			open();
			return true;
		}
		return false;
	}

	/** How to open the menu, for the title screen: mod+1, and the Open Menu key if one is set. */
	public String openHint() {
		String mod = modName();
		String menu = mod + "+1";
		for (var action : Myriad.keyActions()) {
			if (action.id().equals(CoreAddon.OPEN_MENU) && action.bind().isSet()) menu = action.bind().displayName() + " or " + menu;
		}
		return menu + " opens the menu · " + mod + "+Space the launcher";
	}

	public Map<String, DesktopBinds.Bind> binds() {
		return binds.all();
	}

	// ================================================================================================================
	// Window bookkeeping

	private Rect defaultFloatRect(Panel panel) {
		Rect pref = safePreferred(panel);
		float w = pref != null ? Math.max(120, pref.w() + 8) : screenW * 0.4f;
		float h = pref != null ? Math.max(60, pref.h() + 8 + (theme.titleBars.get() ? TITLE_H : 0)) : screenH * 0.5f;
		w = Math.min(w, screenW - 40);
		h = Math.min(h, screenH - 40);
		return new Rect((screenW - w) / 2, (screenH - h) / 2, w, h);
	}

	private void insert(WindowImpl w, boolean focus) {
		windows.add(w);
		Workspace ws = workspaces[w.workspace];
		if (w.floating) ws.floating.add(w);
		else ws.tiling.add(w, ws.focused != null && !ws.focused.floating ? ws.focused : null);
		w.opacity.snap(0);
		w.opacity.animateTo(1, theme.durationMs(theme.windowSpeed), theme.bezier());
		if (focus) ws.focused = w;
	}

	private void detach(WindowImpl w) {
		Workspace ws = workspaces[w.workspace];
		ws.tiling.remove(w);
		ws.floating.remove(w);
		if (ws.focused == w) {
			List<WindowImpl> order = ws.drawOrder();
			ws.focused = order.isEmpty() ? null : order.getLast();
		}
	}

	void closeWindow(WindowImpl w) {
		if (!windows.remove(w)) return;
		Rect last = w.current();
		detach(w);
		safe(w, w.panel::onClose);
		w.closing = true;
		w.target = last;
		w.opacity.animateTo(0, theme.durationMs(theme.windowSpeed) * 0.6f, theme.bezier());
		closing.add(w);
		markDirty();
	}

	void focus(WindowImpl w) {
		Workspace ws = workspaces[w.workspace];
		ws.focused = w;
		if (w.floating && ws.floating.remove(w)) ws.floating.add(w);
		else if (!w.floating) ws.tiling.reveal(w);
	}

	void setFloating(WindowImpl w, boolean floating) {
		if (w.floating == floating || w.workspace == HUD_WORKSPACE) return;
		Workspace ws = workspaces[w.workspace];
		if (floating) {
			Rect cur = w.current();
			ws.tiling.remove(w);
			w.floating = true;
			w.floatRect = new Rect(cur.x() + 10, cur.y() + 10, Math.max(120, cur.w() - 20), Math.max(60, cur.h() - 20));
			ws.floating.add(w);
		} else {
			ws.floating.remove(w);
			w.floating = false;
			WindowImpl anchor = null;
			for (WindowImpl o : ws.tiling.windows()) anchor = o;
			ws.tiling.add(w, anchor);
		}
		ws.focused = w;
		markDirty();
	}

	void moveToWorkspace(WindowImpl w, int workspace) {
		workspace = Math.clamp(workspace, 0, WORKSPACES);
		if (w.workspace == workspace) return;
		if (workspace == HUD_WORKSPACE && !w.type.isHud()) {
			Myriad.notifications().warn("Menu", w.panel.title() + " can't be placed on the HUD");
			return;
		}
		detach(w);
		w.workspace = workspace;
		if (workspace == HUD_WORKSPACE) {
			w.floating = true;
			Rect cur = w.current();
			setHudPosition(w, cur.x(), cur.y(), safePreferred(w.panel));
		}
		Workspace ws = workspaces[workspace];
		if (w.floating) {
			if (w.floatRect == null) w.floatRect = defaultFloatRect(w.panel);
			ws.floating.add(w);
		} else {
			ws.tiling.add(w, ws.focused != null && !ws.focused.floating ? ws.focused : null);
		}
		ws.focused = w;
		w.placed = false;
		markDirty();
	}

	private void safe(WindowImpl w, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			if (failedPanels.add(w)) LOG.error("Panel {} threw", w.type.id(), t);
		}
	}

	private Rect safePreferred(Panel panel) {
		UiRenderer renderer = UiRenderer.peek();
		if (renderer == null) return null;
		try {
			return panel.preferredSize(renderer);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Re-creates panels of a type (e.g. after the module list changed). */
	public void rebuildPanels(MyriadId type) {
		for (WindowImpl w : windows) {
			if (w.type.id().equals(type) && w.panel instanceof WidgetPanel wp) wp.rebuild();
		}
	}

	/** Replaces a window's panel in place (used to re-target a module settings window). */
	public void replacePanel(Window window, Panel panel, JsonObject args) {
		WindowImpl w = (WindowImpl) window;
		safe(w, w.panel::onClose);
		WindowImpl replacement = new WindowImpl(this, w.uid, w.type, args, panel, w.workspace);
		replacement.floating = w.floating;
		replacement.floatRect = w.floatRect;
		replacement.fullscreen = w.fullscreen;
		replacement.setTarget(w.current(), 0, theme.bezier(), false);
		replacement.opacity.snap(1);
		Workspace ws = workspaces[w.workspace];
		int idx = windows.indexOf(w);
		windows.set(idx, replacement);
		if (w.floating) ws.floating.set(ws.floating.indexOf(w), replacement);
		else {
			// Take over w's slot: add anywhere, swap into place, drop the old window.
			ws.tiling.add(replacement, w);
			ws.tiling.swap(w, replacement);
			ws.tiling.remove(w);
		}
		ws.focused = replacement;
		safe(replacement, panel::onOpen);
		markDirty();
	}

	// ================================================================================================================
	// Geometry

	void updateScale() {
		if (mc.getWindow() == null) return;
		int fbH = mc.getWindow().getHeight();
		int fbW = mc.getWindow().getWidth();
		pixelScale = (float) (Math.max(1f, fbH / 540f) * theme.uiScale.get());
		screenW = fbW / pixelScale;
		screenH = fbH / pixelScale;
	}

	float barHeight() {
		return theme.bar.get() ? theme.barHeight.get() : 0;
	}

	boolean barAtTop() {
		return theme.barPosition.get() == ThemeSettings.BarPosition.TOP;
	}

	/** Area available to windows (screen minus the bar). */
	Rect workArea() {
		float bh = barHeight();
		return barAtTop() ? new Rect(0, bh, screenW, screenH - bh) : new Rect(0, 0, screenW, screenH - bh);
	}

	void arrange(Workspace ws, boolean animate) {
		Rect area = workArea();
		float duration = theme.durationMs(theme.windowSpeed);
		float out = theme.gapsOut.get();
		ws.tiling.arrange(area.inset(out), theme.gapsIn.get(), (w, r) -> w.setTarget(r, duration, theme.bezier(), animate));
		for (WindowImpl w : ws.floating) {
			if (ws.index == HUD_WORKSPACE && w.isHudElement()) continue;
			if (w.floatRect == null) w.floatRect = defaultFloatRect(w.panel);
			w.setTarget(w.floatRect, duration, theme.bezier(), animate);
		}
		for (WindowImpl w : ws.drawOrder()) {
			if (w.fullscreen) w.setTarget(area, duration, theme.bezier(), animate);
		}
	}

	Rect contentRect(WindowImpl w, Rect r) {
		float b = theme.borderSize.get();
		float title = theme.titleBars.get() && !w.fullscreen ? TITLE_H : 0;
		return r.inset(b, b + title, b, b);
	}

	Rect hudRect(WindowImpl w) {
		Rect pref = safePreferred(w.panel);
		float pw = pref != null ? pref.w() : 80, ph = pref != null ? pref.h() : 20;
		float pad = theme.hudPadding.get();
		float x = pad + w.anchor.fx() * (screenW - pad * 2 - pw) + w.anchorX;
		float y = pad + w.anchor.fy() * (screenH - pad * 2 - ph) + w.anchorY;
		x = Math.clamp(x, 0, Math.max(0, screenW - pw));
		y = Math.clamp(y, 0, Math.max(0, screenH - ph));
		return new Rect(x, y, pw, ph);
	}

	/** Re-anchors a HUD element to the screen third its centre is in, keeping it where it is. */
	private void setHudPosition(WindowImpl w, float x, float y, Rect pref) {
		float pw = pref != null ? pref.w() : 80, ph = pref != null ? pref.h() : 20;
		float pad = theme.hudPadding.get();
		snappedX = snappedY = false;
		// Snap to edges and centre lines.
		if (Math.abs(x - pad) < SNAP) {
			x = pad;
			snappedX = true;
		} else if (Math.abs(x + pw - (screenW - pad)) < SNAP) {
			x = screenW - pad - pw;
			snappedX = true;
		} else if (Math.abs(x + pw / 2 - screenW / 2) < SNAP) {
			x = screenW / 2 - pw / 2;
			snappedX = true;
		}
		if (Math.abs(y - pad) < SNAP) {
			y = pad;
			snappedY = true;
		} else if (Math.abs(y + ph - (screenH - pad)) < SNAP) {
			y = screenH - pad - ph;
			snappedY = true;
		} else if (Math.abs(y + ph / 2 - screenH / 2) < SNAP) {
			y = screenH / 2 - ph / 2;
			snappedY = true;
		}
		int col = (x + pw / 2) < screenW / 3 ? 0 : (x + pw / 2) < screenW * 2 / 3 ? 1 : 2;
		int row = (y + ph / 2) < screenH / 3 ? 0 : (y + ph / 2) < screenH * 2 / 3 ? 1 : 2;
		w.anchor = PanelType.Anchor.of(col, row);
		w.anchorX = x - (pad + w.anchor.fx() * (screenW - pad * 2 - pw));
		w.anchorY = y - (pad + w.anchor.fy() * (screenH - pad * 2 - ph));
	}

	// ================================================================================================================
	// Rendering

	@Override
	public void draw(GuiGraphicsExtractor ctx, Consumer<Canvas> drawer) {
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		painter.prepareRenderer(c);
		c.begin(ctx, (float) mc.getWindow().getGuiScale());
		try {
			drawer.accept(c);
		} finally {
			c.end();
		}
	}

	/** Draws the desktop, the HUD and Myriad's own screens. */
	DesktopPainter painter() {
		return painter;
	}

	/** In game: HUD elements and notifications. */
	public void renderHud(GuiGraphicsExtractor ctx) {
		painter.renderHud(ctx);
	}

	/** Minecraft's own title screen (with Myriad's off): the logo, version and how to open the menu in a corner. */
	public void renderTitleScreen(GuiGraphicsExtractor ctx) {
		painter.renderTitleScreen(ctx);
	}

	float toUnits(double guiCoord) {
		return (float) (guiCoord * mc.getWindow().getGuiScale() / pixelScale);
	}


	float unitsWide() {
		return screenW;
	}

	float unitsHigh() {
		return screenH;
	}

	/** A GUI coordinate (mouse) in the desktop's units. */
	float units(double guiCoord) {
		return toUnits(guiCoord);
	}

	NotificationManager notifications() {
		return (NotificationManager) Myriad.notifications();
	}


	// ================================================================================================================
	// Ticking

	private int livePoll;
	private String liveSignature = "";
	private MyriadId liveTheme;

	/** Re-applies the active theme when it follows something outside the game (Theme.live) and that changed. */
	private void followLiveTheme() {
		ThemeManager.Entry active = themes.active();
		Supplier<String> live = active.preset() == null ? null : active.preset().live();
		if (live == null) {
			liveTheme = null;
			return;
		}
		if (!active.id().equals(liveTheme)) {
			// The theme was applied when it was picked; start watching from here.
			liveTheme = active.id();
			liveSignature = safeSignature(live);
			return;
		}
		if (++livePoll % 40 != 0) return;
		String sig = safeSignature(live);
		if (sig.equals(liveSignature)) return;
		liveSignature = sig;
		themes.reapply();
	}

	private static String safeSignature(Supplier<String> live) {
		try {
			return String.valueOf(live.get());
		} catch (Throwable t) {
			return "";
		}
	}

	/** Every client tick (in game or not). */
	public void tick() {
		followLiveTheme();
		themes.tick();
		for (WindowImpl w : new ArrayList<>(windows)) {
			if (w.workspace == active || w.isHudElement()) safe(w, w.panel::tick);
		}
	}

	void onScreenRemoved() {
		drag = null;
		pressed = null;
		launcher.hideNow();
		markDirty();
	}

	// ================================================================================================================
	// Input

	private int modMask() {
		return switch (theme.modKey.get()) {
			case ALT -> GLFW.GLFW_MOD_ALT;
			case SUPER -> GLFW.GLFW_MOD_SUPER;
			case CTRL -> GLFW.GLFW_MOD_CONTROL;
		};
	}

	String modName() {
		return switch (theme.modKey.get()) {
			case ALT -> "Alt";
			case SUPER -> "Super";
			case CTRL -> "Ctrl";
		};
	}

	private boolean shiftHeld() {
		long handle = mc.getWindow().handle();
		return down(handle, GLFW.GLFW_KEY_LEFT_SHIFT) || down(handle, GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	boolean modHeld() {
		long handle = mc.getWindow().handle();
		return switch (theme.modKey.get()) {
			case ALT -> down(handle, GLFW.GLFW_KEY_LEFT_ALT) || down(handle, GLFW.GLFW_KEY_RIGHT_ALT);
			case SUPER -> down(handle, GLFW.GLFW_KEY_LEFT_SUPER) || down(handle, GLFW.GLFW_KEY_RIGHT_SUPER);
			case CTRL -> down(handle, GLFW.GLFW_KEY_LEFT_CONTROL) || down(handle, GLFW.GLFW_KEY_RIGHT_CONTROL);
		};
	}

	private static boolean down(long handle, int key) {
		return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
	}

	WindowImpl topWindowAt(float x, float y) {
		Workspace ws = workspaces[active];
		List<WindowImpl> order = ws.drawOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			WindowImpl w = order.get(i);
			Rect r = w.isHudElement() ? hudRect(w).inset(-2) : w.current();
			if (r.contains(x, y)) return w;
		}
		return null;
	}

	/**
	 * The category window whose title bar the dragged category window is over (dropping it there merges the two), or
	 * null.
	 */
	WindowImpl mergeTarget() {
		if (drag == null || (drag.kind != DragKind.SWAP && drag.kind != DragKind.MOVE) || !theme.titleBars.get()) return null;
		if (!(drag.window.panel instanceof ModulesPanel from) || !from.isCategoryWindow()) return null;
		List<WindowImpl> order = workspaces[active].drawOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			WindowImpl w = order.get(i);
			if (w == drag.window || w.isHudElement()) continue;
			Rect r = w.current();
			if (!r.contains(mouseX, mouseY)) continue;
			boolean title = !w.fullscreen && mouseY < r.y() + theme.borderSize.get() + TITLE_H;
			return title && w.panel instanceof ModulesPanel to && to.isCategoryWindow() ? w : null;
		}
		return null;
	}

	/** Moves {@code from}'s groups into {@code into} (as sections) and closes {@code from}. */
	private void merge(WindowImpl from, WindowImpl into) {
		ModulesPanel merged = ((ModulesPanel) into.panel).mergedWith((ModulesPanel) from.panel);
		replacePanel(into, merged, ModuleGroup.args(merged.groups()));
		closeWindow(from);
		markDirty();
	}

	enum DragKind {
		MOVE, RESIZE, SWAP, SPLITTER, TILE_RESIZE, HUD, PANEL
	}

	static final class Drag {
		DragKind kind;
		WindowImpl window;
		LayoutState.Splitter splitter;
		/** Tiled resize: the dividers on the window's horizontal/vertical edge nearest the cursor, and where they started. */
		LayoutState.Splitter edgeX, edgeY;
		float edgeStartX, edgeStartY;
		/** Floating resize: which corner is being dragged (-1 = left/top, 1 = right/bottom). */
		int cornerX = 1, cornerY = 1;
		float offX, offY;
		Rect start;
		float startX, startY;
		int button;
	}

	boolean mouseClicked(double gx, double gy, int button) {
		float mx = toUnits(gx), my = toUnits(gy);
		mouseX = mx;
		mouseY = my;
		if (launcher.mouseClicked(mx, my, button, screenW, screenH)) return true;

		if (theme.bar.get()) {
			float h = barHeight(), by = barAtTop() ? 0 : screenH - h;
			if (my >= by && my < by + h) {
				for (DesktopPainter.BarSlot s : painter.barLayout(UiRenderer.get())) {
					if (mx >= s.x() && mx < s.x() + s.w()) {
						try {
							s.widget().mouseClicked(mx - s.x(), my - by, button);
						} catch (Throwable t) {
							LOG.error("Bar widget {} threw", s.widget().id(), t);
						}
						return true;
					}
				}
				return true;
			}
		}

		Workspace ws = workspaces[active];
		WindowImpl w = topWindowAt(mx, my);
		if (w == null) {
			for (LayoutState.Splitter s : ws.tiling.splitters()) {
				if (s.bounds().contains(mx, my)) {
					drag = new Drag();
					drag.kind = DragKind.SPLITTER;
					drag.splitter = s;
					return true;
				}
			}
			return false;
		}
		focus(w);

		if (w.isHudElement()) {
			Rect r = hudRect(w);
			if (button == 0) {
				drag = new Drag();
				drag.kind = DragKind.HUD;
				drag.window = w;
				drag.offX = mx - r.x();
				drag.offY = my - r.y();
			} else if (button == 1) {
				openPanelSettings(w);
			}
			return true;
		}

		Rect r = w.current();
		boolean mod = modHeld();
		if (mod && (button == 0 || button == 1)) {
			drag = new Drag();
			drag.window = w;
			drag.start = w.floating ? w.floatRect : r;
			drag.startX = mx;
			drag.startY = my;
			drag.offX = mx - r.x();
			drag.offY = my - r.y();
			if (button == 0) {
				drag.kind = w.floating ? DragKind.MOVE : DragKind.SWAP;
			} else if (w.floating) {
				// Like Hyprland: grab the corner nearest the cursor.
				drag.kind = DragKind.RESIZE;
				drag.cornerX = mx < r.centerX() ? -1 : 1;
				drag.cornerY = my < r.centerY() ? -1 : 1;
			} else {
				beginTileResize(drag, w, mx, my);
			}
			return true;
		}

		if (theme.titleBars.get() && !w.fullscreen && my < r.y() + theme.borderSize.get() + TITLE_H) {
			if (button == 0) {
				for (var e : painter.titleControls(w, r)) {
					if (mx >= e.getValue() && mx < e.getValue() + e.getKey().width()) {
						try {
							e.getKey().click();
						} catch (Throwable t) {
							LOG.error("Title control of {} threw", w.type.id(), t);
						}
						markDirty();
						return true;
					}
				}
			}
			float bx = r.right() - theme.borderSize.get() - 24;
			if (button == 0 && mx >= bx + 12 && mx < bx + 23) {
				closeWindow(w);
				return true;
			}
			if (button == 0 && mx >= bx && mx < bx + 11) {
				setFloating(w, !w.floating);
				return true;
			}
			if (button == 1) {
				openPanelSettings(w);
				return true;
			}
			drag = new Drag();
			drag.window = w;
			drag.kind = w.floating ? DragKind.MOVE : DragKind.SWAP;
			drag.offX = mx - r.x();
			drag.offY = my - r.y();
			drag.start = w.floating ? w.floatRect : r;
			return true;
		}

		// Floating windows can be resized from their bottom-right corner.
		if (w.floating && mx > r.right() - 8 && my > r.bottom() - 8) {
			drag = new Drag();
			drag.kind = DragKind.RESIZE;
			drag.window = w;
			drag.start = w.floatRect;
			drag.startX = mx;
			drag.startY = my;
			return true;
		}

		Rect content = contentRect(w, r);
		if (content.contains(mx, my)) {
			pressed = w;
			drag = new Drag();
			drag.kind = DragKind.PANEL;
			drag.window = w;
			drag.button = button;
			try {
				w.panel.mouseClicked(mx - content.x(), my - content.y(), button);
			} catch (Throwable t) {
				LOG.error("Panel {} threw on click", w.type.id(), t);
			}
		}
		return true;
	}

	/**
	 * Tiled resize: pick the dividers on the window's left-or-right and top-or-bottom edges (whichever side the cursor
	 * is on), so dragging moves those edges. Works for any layout, since it goes through the layout's splitters.
	 */
	private void beginTileResize(Drag d, WindowImpl w, float mx, float my) {
		d.kind = DragKind.TILE_RESIZE;
		Rect r = w.target;
		float reach = theme.gapsIn.get() + 8;
		float edgeX = mx < r.centerX() ? r.x() : r.right();
		float edgeY = my < r.centerY() ? r.y() : r.bottom();
		float bestX = reach, bestY = reach;
		for (LayoutState.Splitter sp : workspaces[w.workspace].tiling.splitters()) {
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
		openPanel(CorePanels.PANEL_SETTINGS, args, active, true);
	}

	boolean mouseDragged(double gx, double gy, int button) {
		float mx = toUnits(gx), my = toUnits(gy);
		mouseX = mx;
		mouseY = my;
		if (drag == null) return false;
		switch (drag.kind) {
			case MOVE -> {
				Rect s = drag.window.floatRect;
				drag.window.floatRect = new Rect(mx - drag.offX, my - drag.offY, s.w(), s.h());
				drag.window.setTarget(drag.window.floatRect, 0, theme.bezier(), false);
			}
			case RESIZE -> {
				Rect s = drag.start;
				float dx = mx - drag.startX, dy = my - drag.startY;
				float w = Math.max(80, s.w() + dx * drag.cornerX), h = Math.max(40, s.h() + dy * drag.cornerY);
				float x = drag.cornerX < 0 ? s.right() - w : s.x(), y = drag.cornerY < 0 ? s.bottom() - h : s.y();
				drag.window.floatRect = new Rect(x, y, w, h);
				drag.window.setTarget(drag.window.floatRect, 0, theme.bezier(), false);
			}
			case TILE_RESIZE -> {
				if (drag.edgeX != null) drag.edgeX.drag(drag.edgeStartX + mx - drag.startX, my);
				if (drag.edgeY != null) drag.edgeY.drag(mx, drag.edgeStartY + my - drag.startY);
			}
			case SPLITTER -> drag.splitter.drag(mx, my);
			case HUD -> setHudPosition(drag.window, mx - drag.offX, my - drag.offY, safePreferred(drag.window.panel));
			case PANEL -> {
				Rect content = contentRect(drag.window, drag.window.current());
				try {
					drag.window.panel.mouseDragged(mx - content.x(), my - content.y(), drag.button, 0, 0);
				} catch (Throwable t) {
					LOG.error("Panel {} threw on drag", drag.window.type.id(), t);
				}
			}
			case SWAP -> {
			}
		}
		return true;
	}

	boolean mouseReleased(double gx, double gy, int button) {
		float mx = toUnits(gx), my = toUnits(gy);
		Drag d = drag;
		WindowImpl mergeInto = mergeTarget();
		drag = null;
		if (d == null) return false;
		if (mergeInto != null) {
			merge(d.window, mergeInto);
			pressed = null;
			return true;
		}
		switch (d.kind) {
			case SWAP -> {
				WindowImpl target = topWindowAt(mx, my);
				if (target != null && target != d.window && !target.floating && !d.window.floating) {
					workspaces[active].tiling.swap(d.window, target);
					markDirty();
				}
			}
			case PANEL -> {
				Rect content = contentRect(d.window, d.window.current());
				try {
					d.window.panel.mouseReleased(mx - content.x(), my - content.y(), button);
				} catch (Throwable t) {
					LOG.error("Panel {} threw on release", d.window.type.id(), t);
				}
			}
			default -> markDirty();
		}
		pressed = null;
		return true;
	}

	boolean mouseScrolled(double gx, double gy, double amount) {
		float mx = toUnits(gx), my = toUnits(gy);
		if (launcher.mouseScrolled((float) amount)) return true;
		if (modHeld()) {
			binds.cycleWorkspace(amount > 0 ? -1 : 1);
			return true;
		}
		WindowImpl w = topWindowAt(mx, my);
		// Sideways through a row of columns wider than the screen: Shift + wheel anywhere, or the wheel over a gap.
		if ((shiftHeld() || w == null) && active != HUD_WORKSPACE && workspaces[active].tiling.scroll((float) amount)) return true;
		if (w == null || w.isHudElement()) return false;
		Rect content = contentRect(w, w.current());
		try {
			return w.panel.mouseScrolled(mx - content.x(), my - content.y(), (float) amount);
		} catch (Throwable t) {
			LOG.error("Panel {} threw on scroll", w.type.id(), t);
			return false;
		}
	}

	boolean keyPressed(int key, int scancode, int mods) {
		if (launcher.isOpen()) return launcher.keyPressed(key, mods);
		Workspace ws = workspaces[active];
		WindowImpl focused = ws.focused;
		boolean typing = focused != null && focused.panel.capturesKeyboard();

		// A panel that's being typed in sees the key first; what it doesn't take (mod+key in the console) is the desktop's.
		if (typing && panelKey(focused, key, scancode, mods)) return true;
		if ((mods & modMask()) != 0) {
			int rest = mods & ~modMask();
			for (DesktopBinds.Bind b : binds.all().values()) {
				if (b.bind.code() == key && !b.bind.mouse() && (b.bind.modifiers() & ~GLFW.GLFW_MOD_NUM_LOCK & ~GLFW.GLFW_MOD_CAPS_LOCK) == (rest & ~GLFW.GLFW_MOD_NUM_LOCK & ~GLFW.GLFW_MOD_CAPS_LOCK)) {
					b.action.accept(this);
					return true;
				}
			}
		}
		if (active == HUD_WORKSPACE && (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) && !typing) {
			WindowImpl hovered = topWindowAt(mouseX, mouseY);
			WindowImpl target = hovered != null && hovered.isHudElement() ? hovered : focused != null && focused.isHudElement() ? focused : null;
			if (target != null) {
				closeWindow(target);
				return true;
			}
		}
		if (!typing && focused != null && panelKey(focused, key, scancode, mods)) return true;
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		for (var action : Myriad.keyActions()) {
			if (action.id().equals(CoreAddon.OPEN_MENU) && action.bind().matchesKey(key, mods)) {
				close();
				return true;
			}
		}
		return false;
	}

	private boolean panelKey(WindowImpl w, int key, int scancode, int mods) {
		try {
			return w.panel.keyPressed(key, scancode, mods);
		} catch (Throwable t) {
			LOG.error("Panel {} threw on key", w.type.id(), t);
			return false;
		}
	}

	boolean charTyped(char chr, int mods) {
		if (launcher.isOpen()) return launcher.charTyped(chr);
		if (modHeld()) return true;
		WindowImpl focused = workspaces[active].focused;
		if (focused == null) return false;
		try {
			return focused.panel.charTyped(chr, mods);
		} catch (Throwable t) {
			LOG.error("Panel {} threw on char", focused.type.id(), t);
			return false;
		}
	}

	// ================================================================================================================
	// Persistence

	public JsonObject save() {
		JsonObject o = new JsonObject();
		o.addProperty("version", LAYOUT_VERSION);
		o.addProperty("active", active);
		themes.commit();
		o.addProperty("theme", themes.active().id().toString());
		o.add("preferences", theme.preferences.toJson());
		JsonObject b = new JsonObject();
		for (DesktopBinds.Bind bind : binds.all().values()) b.addProperty(bind.id, bind.bind.serialize());
		o.add("binds", b);
		JsonArray ws = new JsonArray();
		for (Workspace w : workspaces) {
			JsonObject j = new JsonObject();
			j.addProperty("index", w.index);
			j.addProperty("layout", w.layout.id().toString());
			j.add("tiling", w.tiling.save(win -> String.valueOf(win.uid)));
			if (w.focused != null) j.addProperty("focused", w.focused.uid);
			ws.add(j);
		}
		o.add("workspaces", ws);
		o.add("seen_groups", categories.saveSeen());
		JsonArray wins = new JsonArray();
		for (WindowImpl w : windows) {
			// Dialogs hold callbacks that don't survive a restart.
			if (!(w.panel instanceof DialogPanel)) wins.add(w.save());
		}
		// Windows of panel types that aren't installed right now are kept for when they come back.
		for (JsonElement e : orphanWindows) wins.add(e);
		o.add("windows", wins);
		return o;
	}

	/** Loads a saved layout; {@code null} builds the first-run desktop. */
	public void load(JsonObject o) {
		for (WindowImpl w : new ArrayList<>(windows)) safe(w, w.panel::onClose);
		windows.clear();
		closing.clear();
		orphanWindows = new JsonArray();
		for (int i = 0; i <= WORKSPACES; i++) {
			workspaces[i] = new Workspace(i, defaultLayout());
			savedTiling[i] = null;
		}
		themes.reload();
		categories.reset();
		if (o == null) {
			firstRun();
			categories.placeNewGroups(false);
			modulesChanged = false;
			return;
		}
		if (o.has("seen_groups")) categories.loadSeen(o.getAsJsonArray("seen_groups"));
		loadTheme(o);
		if (o.has("binds")) {
			JsonObject b = o.getAsJsonObject("binds");
			for (DesktopBinds.Bind bind : binds.all().values()) if (b.has(bind.id)) bind.bind = Keybind.deserialize(b.get(bind.id).getAsString());
		}
		Map<String, WindowImpl> byUid = new LinkedHashMap<>();
		if (o.has("windows")) {
			for (JsonElement e : o.getAsJsonArray("windows")) {
				JsonObject j = e.getAsJsonObject();
				try {
					WindowImpl w = loadWindow(j);
					if (w == null) orphanWindows.add(j);
					else byUid.put(String.valueOf(w.uid), w);
				} catch (RuntimeException ex) {
					LOG.error("Skipping broken window entry {}", j, ex);
				}
			}
		}
		Set<WindowImpl> placed = new HashSet<>();
		if (o.has("workspaces")) {
			for (JsonElement e : o.getAsJsonArray("workspaces")) {
				JsonObject j = e.getAsJsonObject();
				int idx = j.get("index").getAsInt();
				if (idx < 0 || idx > WORKSPACES) continue;
				Workspace ws = workspaces[idx];
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
		}
		for (WindowImpl w : byUid.values()) {
			windows.add(w);
			Workspace ws = workspaces[w.workspace];
			if (w.floating) ws.floating.add(w);
			else if (!placed.contains(w)) ws.tiling.add(w, null);
			w.opacity.snap(1);
			safe(w, w.panel::onOpen);
		}
		active = o.has("active") ? Math.clamp(o.get("active").getAsInt(), 0, WORKSPACES) : 1;
		previous = active;
		int version = o.has("version") ? o.get("version").getAsInt() : 1;
		if (version < LAYOUT_VERSION) {
			// One-time upgrade: adopt a theme an addon prefers (e.g. one matching the system), if any.
			if (version < 2) themes.firstRunPreference().ifPresent(themes::apply);
			for (WindowImpl w : new ArrayList<>(windows)) if (w.workspace != HUD_WORKSPACE) {
				detach(w);
				windows.remove(w);
			}
			defaultWorkspaces();
		}
		categories.placeNewGroups(true);
		modulesChanged = false;
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
		Panel panel = createPanel(type.get(), args);
		if (panel == null) return null;
		int uid = j.get("uid").getAsInt();
		WindowImpl w = new WindowImpl(this, uid, type.get(), args, panel, Math.clamp(j.get("workspace").getAsInt(), 0, WORKSPACES));
		nextUid = Math.max(nextUid, uid + 1);
		w.restore(j);
		return w;
	}

	private void firstRun() {
		updateScale();
		theme.preferences.resetAll();
		themes.apply(themes.firstRunPreference().orElse(ThemeManager.DEFAULT));
		defaultWorkspaces();
		for (PanelType t : Myriad.panels()) if (t.defaultHud() != null) addHudElement(t);
		for (WindowImpl w : windows) w.opacity.snap(1);
	}

	/**
	 * The first-run desktop: one window per addon + category group, a tiled click-GUI, laid out like addons that arrive
	 * later ({@link #placeNewGroups}): the first addon on workspace 1, the others beside it while there's room, then on
	 * workspaces of their own.
	 */
	private void defaultWorkspaces() {
		active = 1;
		categories.reset();
		categories.placeNewGroups(false);
		if (workspaces[1].isEmpty()) openPanel(CorePanels.MODULES, new JsonObject(), 1, false);
		for (WindowImpl w : windows) w.opacity.snap(1);
	}

	/** Shows the window holding {@code group}, opening one (on the current workspace) if none does. */
	public void openGroup(ModuleGroup group) {
		categories.openGroup(group);
	}

	/** Windows on {@code workspace} (for bar widgets). */
	public int windowCount(int workspace) {
		int n = 0;
		for (WindowImpl w : windows) if (w.workspace == workspace) n++;
		return n;
	}

	/** Panel instance by window uid (for the panel settings panel). */
	public Optional<Panel> panelByUid(int uid) {
		for (WindowImpl w : windows) if (w.uid == uid) return Optional.of(w.panel);
		return Optional.empty();
	}

	public Optional<Window> windowByUid(int uid) {
		for (WindowImpl w : windows) if (w.uid == uid) return Optional.of(w);
		return Optional.empty();
	}

	public String layoutName(int workspace) {
		return workspaces[Math.clamp(workspace, 0, WORKSPACES)].layout.name();
	}

	public void setLayout(int workspace, Layout layout) {
		workspaces[Math.clamp(workspace, 0, WORKSPACES)].setLayout(Objects.requireNonNull(layout));
		markDirty();
	}
}
