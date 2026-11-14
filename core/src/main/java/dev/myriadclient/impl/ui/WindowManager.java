package dev.myriadclient.impl.ui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Side;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.Ui;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.draw.Animated;
import dev.myriadclient.api.ui.draw.Canvas;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;
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
import org.jspecify.annotations.Nullable;
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
 * The Hyprland-inspired window manager behind {@link Ui}: workspaces with tiling layouts and floating windows,
 * keyboard-driven focus/move/resize, animated window placement and workspace switching, and the HUD workspace whose
 * windows are drawn in game.
 */
// size: the Ui API is implemented here, and window bookkeeping (opening, closing, focus, floating, moving between
// workspaces, replacing panels) changes the window list and the workspaces together; input, saving and loading, HUD
// elements, drawing, group windows and binds are classes of their own.
public final class WindowManager implements Ui {
	static final Logger LOG = LoggerFactory.getLogger("Myriad/UI");
	static final float TITLE_H = 13;
	final Minecraft mc = Minecraft.getInstance();
	final ThemeSettings theme = new ThemeSettings();
	private final ThemeManager themes = new ThemeManager(theme);
	private final SettingWidgetsImpl settingWidgets = new SettingWidgetsImpl();
	final Workspace[] workspaces = new Workspace[WORKSPACES + 1];
	final List<WindowImpl> windows = new ArrayList<>();
	final List<WindowImpl> closing = new ArrayList<>();
	final Launcher launcher = new Launcher(this);
	private final UiInput input = new UiInput(this);
	private final UiStore store = new UiStore(this);
	final HudElements hud = new HudElements(this);
	private final UiPainter painter = new UiPainter(this);
	final GroupWindows groups = new GroupWindows(this);
	final UiBinds binds = new UiBinds(this);
	final Set<WindowImpl> failedPanels = new HashSet<>();
	final Animated transition = new Animated(1);
	final long borderEpoch = System.nanoTime();
	private Runnable dirtyHook = () -> {
	};

	/** Modules came or went since the UI was last laid out: group windows need rebuilding, new groups placing. */
	boolean modulesChanged;
	int active = 1, previous = 1;
	int nextUid = 1;

	// Frame/input state
	float pixelScale = 2, screenW = 960, screenH = 540;
	/** UI Scale as drawn and as last kept (see {@link #uiScale}), and the dialog asking to keep it (its id, 0: none). */
	private double shownUiScale = Double.NaN, keptUiScale = Double.NaN;
	private int scaleDialog;
	private static final long KEEP_SCALE_MS = 5000;
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
	// Ui API

	@Override
	public void open() {
		refreshModules();
		if (!(mc.gui.screen() instanceof UiScreen)) mc.gui.setScreen(new UiScreen(this));
	}

	/** Runs one of the UI's own bindings by id ("launcher", "layout", "close", ...), for the dev console. */
	public void runBind(String id) {
		UiBinds.Bind b = binds.get(id);
		if (b == null) throw new IllegalArgumentException("no UI binding '" + id + "'; one of " + binds.all().keySet());
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

	/** Before the UI shows: rebuilds group windows and places new groups if modules came or went since. */
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
		if (mc.gui.screen() instanceof UiScreen) mc.gui.setScreen(null);
	}

	@Override
	public boolean isOpen() {
		return mc.gui.screen() instanceof UiScreen;
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

	/** The HUD sidebar is collapsed (its panel saves this). */
	public boolean hudCollapsed() {
		return hud.collapsed;
	}

	public void setHudCollapsed(boolean collapsed) {
		hud.collapsed = collapsed;
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
		// HUD elements are on-screen information: asked for anywhere, they go on the HUD.
		Optional<PanelType> hudType = Myriad.panels().get(type).filter(PanelType::isHud);
		if (hudType.isPresent()) {
			hud.add(hudType.get());
			return hud.element(hudType.get()).map(Window.class::cast);
		}
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
	 * The UI's shortcuts that work without the menu open, in game and on the title screen: mod+1..9 opens the menu
	 * on that workspace, mod+S on the HUD, mod+Space the launcher (on its own over the game). True if the key was one.
	 */
	public boolean globalKey(int key, int mods) {
		if ((mods & modMask()) == 0 || isOpen() || mc.gui.screen() instanceof LauncherScreen) return false;
		int rest = mods & ~modMask() & ~InputConstants.MOD_NUM_LOCK & ~InputConstants.MOD_CAPS_LOCK;
		for (UiBinds.Bind b : binds.all().values()) {
			if (b.bind.mouse() || b.bind.code() != key || (b.bind.modifiers() & ~InputConstants.MOD_NUM_LOCK & ~InputConstants.MOD_CAPS_LOCK) != rest) continue;
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

	public Map<String, UiBinds.Bind> binds() {
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

	/** Dev console: floats the focused window at this place and size. */
	public void floatFocused(float x, float y, float w, float h) {
		WindowImpl focused = workspaces[active].focused;
		if (focused == null) return;
		setFloating(focused, true);
		focused.floatRect = new Rect(x, y, w, h);
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
		if (workspace == HUD_WORKSPACE && !w.type.isHud() && !w.isHudEditor()) {
			Myriad.notifications().warn("Menu", w.panel.title() + " can't be placed on the HUD");
			return;
		}
		if (workspace != HUD_WORKSPACE && w.isHudEditor()) return;
		if (workspace != HUD_WORKSPACE && w.isHudElement()) {
			Myriad.notifications().warn("Menu", w.type.name() + " stays on the HUD");
			return;
		}
		detach(w);
		w.workspace = workspace;
		if (workspace == HUD_WORKSPACE) {
			w.floating = true;
			if (w.type.isHud()) {
				Rect cur = w.current();
				hud.setPosition(w, cur.x(), cur.y(), safePreferred(w.panel));
			}
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
		pixelScale = (float) (Math.max(1f, fbH / 540f) * uiScale());
		screenW = fbW / pixelScale;
		screenH = fbH / pixelScale;
	}

	/**
	 * Theme → Preferences → UI Scale as the UI draws it: changed once the slider is let go, not at each step of a
	 * drag (the menu rescaled under the pointer as it moved, the slider jumping away from it), and then kept only if you
	 * say so within {@link #KEEP_SCALE_MS}, as a screen resolution is; otherwise it goes back. Changed with the menu shut
	 * (a profile loaded, a command), it's simply taken.
	 */
	private double uiScale() {
		double wanted = theme.uiScale.get();
		if (Double.isNaN(shownUiScale)) shownUiScale = keptUiScale = wanted;
		if (wanted == shownUiScale) return shownUiScale;
		if (!isOpen()) {
			shownUiScale = keptUiScale = wanted;
			return wanted;
		}
		if (Keybind.mouse(InputConstants.MOUSE_BUTTON_LEFT).isPressed()) return shownUiScale;
		shownUiScale = wanted;
		if (scaleDialog != 0) DialogPanel.dismiss(scaleDialog);
		JsonObject args = DialogPanel.prepare("UI Scale", left -> "Keep this scale? Going back in " + left + " s.", "Keep", () -> {
			keptUiScale = shownUiScale;
			scaleDialog = 0;
		}, () -> {
			theme.uiScale.set(keptUiScale);
			shownUiScale = keptUiScale;
			scaleDialog = 0;
		}, KEEP_SCALE_MS);
		scaleDialog = args.get("id").getAsInt();
		openPanel(CorePanels.DIALOG, args, active == HUD_WORKSPACE ? 1 : active, true);
		return shownUiScale;
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
			if (w.isHudEditor()) {
				w.setTarget(hud.sidebar(), duration, theme.bezier(), animate);
				continue;
			}
			if (w.floatRect == null) w.floatRect = defaultFloatRect(w.panel);
			w.setTarget(w.floatRect, duration, theme.bezier(), animate);
		}
		for (WindowImpl w : ws.drawOrder()) {
			if (w.fullscreen) w.setTarget(area, duration, theme.bezier(), animate);
		}
	}

	/** How far in from a window's sides its title bar's contents go: past the rounded corner where it crosses the bar's middle. */
	float titleInset(WindowImpl w) {
		float r = w.fullscreen ? 0 : Math.max(0, theme.rounding.get() - theme.borderSize.get()), mid = TITLE_H / 2;
		return r > mid ? r - (float) Math.sqrt(r * r - (r - mid) * (r - mid)) : 0;
	}

	Rect contentRect(WindowImpl w, Rect r) {
		float b = theme.borderSize.get();
		float title = theme.titleBars.get() && !w.titleless() ? TITLE_H : 0;
		// Clear of the rounded corners: a rectangle inside a corner of radius r keeps r(1 - 1/sqrt 2) from its edges, or
		// content draws past the curve (a few pixels at most roundings, enough to see at the highest).
		float corner = w.fullscreen ? 0 : Math.max(0, theme.rounding.get() - b) * (1 - (float) Math.sqrt(0.5));
		return r.inset(b + corner, b + Math.max(title, corner), b + corner, b + corner);
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

	/** The mouse and keys, for the screens that show the UI. */
	UiInput input() {
		return input;
	}

	/** Draws the UI, the HUD and Myriad's own screens. */
	UiPainter painter() {
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

	/** A GUI coordinate (mouse) in the UI's units. */
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
			case ALT -> InputConstants.MOD_ALT;
			case SUPER -> InputConstants.MOD_SUPER;
			case CTRL -> InputConstants.MOD_CONTROL;
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
		return switch (theme.modKey.get()) {
			case ALT -> InputConstants.isKeyDown(InputConstants.KEY_LALT) || InputConstants.isKeyDown(InputConstants.KEY_RALT);
			case SUPER -> InputConstants.isKeyDown(InputConstants.KEY_LGUI) || InputConstants.isKeyDown(InputConstants.KEY_RGUI);
			case CTRL -> InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
		};
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

	/** Of a window's width or height, the band at each edge where dropping a window splits it (the rest swaps them). */
	private static final float SPLIT_BAND = 0.3f;

	/**
	 * Where dropping a dragged tiled window on {@code target} (at {@code r}) puts it: beside it on the side returned, the
	 * cursor in the band along that edge, or null for swapping the two (the cursor towards the middle).
	 */
	@Nullable Side splitSide(Rect r, float mx, float my) {
		float fx = (mx - r.x()) / r.w(), fy = (my - r.y()) / r.h();
		float left = fx, right = 1 - fx, up = fy, down = 1 - fy;
		float nearest = Math.min(Math.min(left, right), Math.min(up, down));
		if (nearest > SPLIT_BAND) return null;
		return nearest == left ? Side.LEFT : nearest == right ? Side.RIGHT : nearest == up ? Side.UP : Side.DOWN;
	}

	/** The half of {@code r} a window dropped on its {@code side} takes. */
	static Rect half(Rect r, Side side) {
		return switch (side) {
			case LEFT -> new Rect(r.x(), r.y(), r.w() / 2, r.h());
			case RIGHT -> new Rect(r.x() + r.w() / 2, r.y(), r.w() / 2, r.h());
			case UP -> new Rect(r.x(), r.y(), r.w(), r.h() / 2);
			case DOWN -> new Rect(r.x(), r.y() + r.h() / 2, r.w(), r.h() / 2);
		};
	}

	/**
	 * Carries the mouse drag in progress on as one of {@code window} by its title bar, as if it had been grabbed there:
	 * a group pulled out of a merged window by its header goes on being dragged, to be dropped where it should go.
	 */
	public void continueDragAs(Window window) {
		if (!(window instanceof WindowImpl w)) return;
		drag = new Drag();
		drag.window = w;
		drag.kind = w.floating ? DragKind.MOVE : DragKind.SWAP;
		drag.offX = 30;
		drag.offY = TITLE_H / 2f;
		drag.start = w.floating ? w.floatRect : w.current();
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
		/** A panel drag: where the mouse was at the last drag event (for its movement since). */
		float lastX, lastY;
		int button;
	}

	public JsonObject save() {
		return store.save();
	}

	/** Loads a saved layout; {@code null} builds the first-run UI. */
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
