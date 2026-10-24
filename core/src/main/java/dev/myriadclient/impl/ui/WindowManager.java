package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.module.ModuleGroup;
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
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.CoreAddon;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.service.NotificationManager;
import dev.myriadclient.impl.ui.layout.DwindleLayout;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.DialogPanel;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Hyprland-inspired window manager behind {@link Desktop}: workspaces with tiling layouts and floating windows,
 * keyboard-driven focus/move/resize, animated window placement and workspace switching, and the HUD workspace whose
 * windows are drawn in game.
 */
// size: the Desktop API is implemented here, and window bookkeeping (opening, closing, focus, floating, moving between
// workspaces, replacing panels) changes the window list and the workspaces together; input, saving and loading, HUD
// elements, drawing, group windows and binds are classes of their own.
public final class WindowManager implements Desktop {
	static final Logger LOG = LoggerFactory.getLogger("Myriad/Desktop");
	static final float TITLE_H = 13;
	final Minecraft mc = Minecraft.getInstance();
	final ThemeSettings theme = new ThemeSettings();
	private final ThemeManager themes = new ThemeManager(theme);
	private final SettingWidgetsImpl settingWidgets = new SettingWidgetsImpl();
	final Workspace[] workspaces = new Workspace[WORKSPACES + 1];
	final List<WindowImpl> windows = new ArrayList<>();
	final List<WindowImpl> closing = new ArrayList<>();
	final Launcher launcher = new Launcher(this);
	private final DesktopInput input = new DesktopInput(this);
	private final DesktopStore store = new DesktopStore(this);
	final HudElements hud = new HudElements(this);
	private final DesktopPainter painter = new DesktopPainter(this);
	final GroupWindows groups = new GroupWindows(this);
	final DesktopBinds binds = new DesktopBinds(this);
	final Set<WindowImpl> failedPanels = new HashSet<>();
	final Animated transition = new Animated(1);
	final long borderEpoch = System.nanoTime();
	private Runnable dirtyHook = () -> {
	};

	/** Modules came or went since the desktop was last laid out: group windows need rebuilding, new groups placing. */
	boolean modulesChanged;
	int active = 1, previous = 1;
	int nextUid = 1;

	// Frame/input state
	float pixelScale = 2, screenW = 960, screenH = 540;
	float mouseX, mouseY;
	Drag drag;
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

	/** Called when a module is registered or removed; the next {@link #open} refreshes the group windows. */
	public void modulesChanged() {
		modulesChanged = true;
	}

	/** Before the desktop shows: rebuilds group windows and places new groups if modules came or went since. */
	public void refreshModules() {
		if (!modulesChanged) return;
		modulesChanged = false;
		store.reviveOrphans();
		groups.tidy();
		for (WindowImpl w : windows) if (w.panel instanceof ModulesPanel p) safe(w, p::rebuild);
		groups.placeNewGroups(true);
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
		if (workspace == HUD_WORKSPACE) hud.ensureEditor();
		markDirty();
	}

	// ---- HUD elements ---------------------------------------------------------------------------------------------

	/** The HUD element of this type, if it's on the HUD. */
	public Optional<WindowImpl> hudElement(PanelType type) {
		return hud.element(type);
	}

	/** Puts a HUD-capable panel on the HUD at its default spot (or the screen centre). */
	public void addHudElement(PanelType type) {
		hud.add(type);
	}

	public void removeHudElement(PanelType type) {
		hud.remove(type);
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

	Panel createPanel(PanelType type, JsonObject args) {
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

	void insert(WindowImpl w, boolean focus) {
		windows.add(w);
		Workspace ws = workspaces[w.workspace];
		if (w.floating) ws.floating.add(w);
		else ws.tiling.add(w, ws.focused != null && !ws.focused.floating ? ws.focused : null);
		w.opacity.snap(0);
		w.opacity.animateTo(1, theme.durationMs(theme.windowSpeed), theme.bezier());
		if (focus) ws.focused = w;
	}

	void detach(WindowImpl w) {
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
			hud.setPosition(w, cur.x(), cur.y(), safePreferred(w.panel));
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

	void safe(WindowImpl w, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			if (failedPanels.add(w)) LOG.error("Panel {} threw", w.type.id(), t);
		}
	}

	Rect safePreferred(Panel panel) {
		UiRenderer renderer = UiRenderer.peek();
		if (renderer == null) return null;
		try {
			return panel.preferredSize(renderer);
		} catch (Throwable t) {
			return null;
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

	/** The mouse and keys, for the screens that show the desktop. */
	DesktopInput input() {
		return input;
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

	/** Every client tick (in game or not). */
	public void tick() {
		themes.tick();
		for (WindowImpl w : new ArrayList<>(windows)) {
			if (w.workspace == active || w.isHudElement()) safe(w, w.panel::tick);
		}
	}

	void onScreenRemoved() {
		drag = null;
		launcher.hideNow();
		markDirty();
	}

	// ================================================================================================================
	// Input

	int modMask() {
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

	boolean modHeld() {
		long handle = mc.getWindow().handle();
		return switch (theme.modKey.get()) {
			case ALT -> down(handle, GLFW.GLFW_KEY_LEFT_ALT) || down(handle, GLFW.GLFW_KEY_RIGHT_ALT);
			case SUPER -> down(handle, GLFW.GLFW_KEY_LEFT_SUPER) || down(handle, GLFW.GLFW_KEY_RIGHT_SUPER);
			case CTRL -> down(handle, GLFW.GLFW_KEY_LEFT_CONTROL) || down(handle, GLFW.GLFW_KEY_RIGHT_CONTROL);
		};
	}

	static boolean down(long handle, int key) {
		return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
	}

	WindowImpl topWindowAt(float x, float y) {
		Workspace ws = workspaces[active];
		List<WindowImpl> order = ws.drawOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			WindowImpl w = order.get(i);
			Rect r = w.isHudElement() ? hud.rect(w).inset(-2) : w.current();
			if (r.contains(x, y)) return w;
		}
		return null;
	}

	/**
	 * The group window whose title bar the dragged group window is over (dropping it there merges the two), or
	 * null.
	 */
	WindowImpl mergeTarget() {
		if (drag == null || (drag.kind != DragKind.SWAP && drag.kind != DragKind.MOVE) || !theme.titleBars.get()) return null;
		if (!(drag.window.panel instanceof ModulesPanel from) || !from.isGroupWindow()) return null;
		List<WindowImpl> order = workspaces[active].drawOrder();
		for (int i = order.size() - 1; i >= 0; i--) {
			WindowImpl w = order.get(i);
			if (w == drag.window || w.isHudElement()) continue;
			Rect r = w.current();
			if (!r.contains(mouseX, mouseY)) continue;
			boolean title = !w.fullscreen && mouseY < r.y() + theme.borderSize.get() + TITLE_H;
			return title && w.panel instanceof ModulesPanel to && to.isGroupWindow() ? w : null;
		}
		return null;
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

	public JsonObject save() {
		return store.save();
	}

	/** Loads a saved layout; {@code null} builds the first-run desktop. */
	public void load(JsonObject o) {
		store.load(o);
	}

	/** Shows the window holding {@code group}, opening one (on the current workspace) if none does. */
	public void openGroup(ModuleGroup group) {
		groups.openGroup(group);
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

	public String layoutName(int workspace) {
		return workspaces[Math.clamp(workspace, 0, WORKSPACES)].layout.name();
	}

	public void setLayout(int workspace, Layout layout) {
		workspaces[Math.clamp(workspace, 0, WORKSPACES)].setLayout(Objects.requireNonNull(layout));
		markDirty();
	}
}
