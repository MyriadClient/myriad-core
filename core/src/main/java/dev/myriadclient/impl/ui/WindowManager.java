package dev.myriadclient.impl.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.render.Animated;
import dev.myriadclient.api.render.FontFamily;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Direction;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Rect;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.ThemeSettings;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.ui.layout.LayoutState;
import dev.myriadclient.api.util.ColorUtil;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.render.UiRenderer;
import dev.myriadclient.impl.ui.layout.DwindleLayout;
import dev.myriadclient.impl.ui.panels.CorePanels;
import dev.myriadclient.impl.ui.panels.DialogPanel;
import dev.myriadclient.impl.ui.panels.ModuleGroup;
import dev.myriadclient.impl.ui.panels.ModulesPanel;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import dev.myriadclient.api.ui.TitleControl;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * The Hyprland-inspired window manager behind {@link Desktop}: workspaces with tiling layouts and floating windows,
 * keyboard-driven focus/move/resize, animated window placement and workspace switching, and the HUD workspace whose
 * windows are drawn in game.
 */
public final class WindowManager implements Desktop {
	private static final Logger LOG = LoggerFactory.getLogger("Myriad/Desktop");
	static final float TITLE_H = 13;
	/** Bump to rebuild users' workspace layouts (theme and HUD are kept) after a default-layout change. */
	private static final int LAYOUT_VERSION = 3;
	private static final float SNAP = 4;

	private final Minecraft mc = Minecraft.getInstance();
	private final ThemeSettings theme = new ThemeSettings();
	private final ThemeManager themes = new ThemeManager(theme);
	private final SettingWidgetsImpl settingWidgets = new SettingWidgetsImpl();
	private final Workspace[] workspaces = new Workspace[WORKSPACES + 1];
	private final List<WindowImpl> windows = new ArrayList<>();
	private final List<WindowImpl> closing = new ArrayList<>();
	private final Launcher launcher = new Launcher(this);
	private final Map<String, WmBind> binds = new LinkedHashMap<>();
	private final Set<WindowImpl> failedPanels = new HashSet<>();
	private final Animated transition = new Animated(1);
	private final long borderEpoch = System.nanoTime();
	private Runnable dirtyHook = () -> {
	};

	private JsonArray orphanWindows = new JsonArray();
	/** Modules came or went since the desktop was last laid out: category windows need rebuilding, new groups placing. */
	private boolean modulesChanged;
	/** Each workspace's tiling as loaded, so a window revived later (its modules came late) can take its old place. */
	private final JsonObject[] savedTiling = new JsonObject[WORKSPACES + 1];
	/** Addon + category groups this desktop has already placed, so a group whose window you closed stays closed. */
	private final Set<String> seenGroups = new LinkedHashSet<>();
	private int active = 1, previous = 1;
	private int nextUid = 1;

	// Frame/input state
	private float pixelScale = 2, screenW = 960, screenH = 540;
	private float mouseX, mouseY;
	private Drag drag;
	private WindowImpl pressed;
	private boolean snappedX, snappedY;

	public WindowManager() {
		for (int i = 0; i <= WORKSPACES; i++) workspaces[i] = new Workspace(i, new DwindleLayout());
		registerBinds();
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
		WmBind b = binds.get(id);
		if (b == null) throw new IllegalArgumentException("no desktop binding '" + id + "'; one of " + binds.keySet());
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
		placeNewGroups(true);
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
		MyriadId id = dev.myriadclient.impl.ui.panels.CorePanels.HUD_ELEMENTS;
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
			Myriad.notifications().send("Theme", "Applied " + themes.active().name(), dev.myriadclient.api.service.Notifications.Level.SUCCESS, 2000, "theme");
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
		for (WmBind b : binds.values()) {
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
			if (action.id().equals(dev.myriadclient.impl.CoreAddon.OPEN_MENU) && action.bind().isSet()) menu = action.bind().displayName() + " or " + menu;
		}
		return menu + " opens the menu · " + mod + "+Space the launcher";
	}

	public Map<String, WmBind> binds() {
		return binds;
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
			if (w.type.id().equals(type) && w.panel instanceof dev.myriadclient.api.ui.WidgetPanel wp) wp.rebuild();
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

	private void updateScale() {
		if (mc.getWindow() == null) return;
		int fbH = mc.getWindow().getHeight();
		int fbW = mc.getWindow().getWidth();
		pixelScale = (float) (Math.max(1f, fbH / 540f) * theme.uiScale.get());
		screenW = fbW / pixelScale;
		screenH = fbH / pixelScale;
	}

	private float barHeight() {
		return theme.bar.get() ? theme.barHeight.get() : 0;
	}

	private boolean barAtTop() {
		return theme.barPosition.get() == ThemeSettings.BarPosition.TOP;
	}

	/** Area available to windows (screen minus the bar). */
	private Rect workArea() {
		float bh = barHeight();
		return barAtTop() ? new Rect(0, bh, screenW, screenH - bh) : new Rect(0, 0, screenW, screenH - bh);
	}

	private void arrange(Workspace ws, boolean animate) {
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

	private Rect contentRect(WindowImpl w, Rect r) {
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
	public void draw(GuiGraphicsExtractor ctx, java.util.function.Consumer<dev.myriadclient.api.render.Canvas> drawer) {
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, (float) mc.getWindow().getGuiScale());
		try {
			drawer.accept(c);
		} finally {
			c.end();
		}
	}

	private void prepareRenderer(UiRenderer r) {
		r.setDefaults(theme.fontFamily(), theme.fontSize.get().floatValue());
		// No world, nothing worth blurring: the desktop's own background is drawn as part of the GUI.
		r.setBlur(theme.blur.get() && mc.level != null, theme.blurPasses.get(), theme.blurSize.get().floatValue());
	}

	private float toUnits(double guiCoord) {
		return (float) (guiCoord * mc.getWindow().getGuiScale() / pixelScale);
	}

	void renderDesktop(GuiGraphicsExtractor ctx, int guiMouseX, int guiMouseY) {
		updateScale();
		mouseX = toUnits(guiMouseX);
		mouseY = toUnits(guiMouseY);
		UiRenderer c = UiRenderer.get();
		prepareRenderer(c);
		c.begin(ctx, pixelScale);
		try {
			titleTip = null;
			if (mc.level == null) c.gradientRect(0, 0, screenW, screenH, 0, 0xFF0C0C0C, 0xFF151816, 90);
			c.captureBlur();
			if (active != HUD_WORKSPACE) c.rect(0, 0, screenW, screenH, theme.desktopTint.argb());

			float t = transition.get();
			if (t < 1 && previous != active) {
				drawWorkspaceLayer(c, workspaces[previous], t, true);
				drawWorkspaceLayer(c, workspaces[active], t, false);
			} else {
				drawWorkspaceLayer(c, workspaces[active], 1, false);
			}
			drawClosing(c);
			if (active == HUD_WORKSPACE) drawHudEditorOverlay(c);
			if (theme.bar.get()) drawBar(c);
			if (workspaces[active].isEmpty() && active != HUD_WORKSPACE) drawEmptyHint(c);
			launcher.render(c, screenW, screenH, mouseX, mouseY);
			drawTooltip(c);
			notifications().render(c, screenW, screenH, barAtTop() ? 0 : barHeight());
		} finally {
			c.end();
		}
	}

	/** Draws in the desktop's units, at its scale, for screens of Myriad's own (the title screen). */
	void paint(GuiGraphicsExtractor ctx, java.util.function.Consumer<UiRenderer> drawer) {
		updateScale();
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, pixelScale);
		try {
			drawer.accept(c);
		} finally {
			c.end();
		}
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

	/** The launcher on its own over the game ({@link LauncherScreen}). */
	void renderLauncher(GuiGraphicsExtractor ctx, int guiMouseX, int guiMouseY) {
		updateScale();
		mouseX = toUnits(guiMouseX);
		mouseY = toUnits(guiMouseY);
		UiRenderer c = UiRenderer.get();
		prepareRenderer(c);
		c.begin(ctx, pixelScale);
		try {
			c.captureBlur();
			launcher.render(c, screenW, screenH, mouseX, mouseY);
		} finally {
			c.end();
		}
	}

	/**
	 * Myriad's mark on the title screen: the logo, the version, and how to open the menu, in the bottom left corner
	 * just above Minecraft's version line (the one corner every window shape leaves free).
	 */
	public void renderTitleScreen(GuiGraphicsExtractor ctx) {
		updateScale();
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, pixelScale);
		try {
			float fs = c.defaultFontSize() * 1.25f;
			float size = 16, x = 6, block = Math.max(size, 1 + c.textHeight(FontFamily.SANS, fs) + 2 + c.textHeight());
			float y = screenH - toUnits(12) - 4 - block;
			c.texture(TITLE_LOGO, x, y, size, size, 0, 0, 1, 1, theme.accent.argb());
			c.text(FontFamily.SANS, fs, "Myriad", x + size + 5, y + 1, 0xFFFFFFFF);
			float vx = x + size + 5 + c.textWidth(FontFamily.SANS, fs, "Myriad") + 4;
			c.text(FontFamily.SANS, c.defaultFontSize(), Myriad.version(), vx, y + 1 + (c.textHeight(FontFamily.SANS, fs) - c.textHeight()) / 2, 0xB0FFFFFF);
			c.text(openHint(), x + size + 5, y + 1 + c.textHeight(FontFamily.SANS, fs) + 2, 0xC8FFFFFF);
		} finally {
			c.end();
		}
	}

	/** The logo, white on transparent, tinted with the accent. */
	private static final Identifier TITLE_LOGO = Identifier.fromNamespaceAndPath("myriad", "textures/logo.png");

	private dev.myriadclient.impl.service.NotificationManager notifications() {
		return (dev.myriadclient.impl.service.NotificationManager) Myriad.notifications();
	}

	/** In game: draw HUD elements and notifications. */
	public void renderHud(GuiGraphicsExtractor ctx) {
		if (mc.gui.hud.isHidden() || isOpen()) return;
		updateScale();
		UiRenderer c = UiRenderer.get();
		if (c.isActive()) return;
		prepareRenderer(c);
		c.begin(ctx, pixelScale);
		try {
			for (WindowImpl w : new ArrayList<>(workspaces[HUD_WORKSPACE].floating)) {
				if (!w.isHudElement()) continue;
				Rect r = hudRect(w);
				drawHudElement(c, w, r, false);
			}
			notifications().render(c, screenW, screenH, 0);
		} finally {
			c.end();
		}
	}

	private void drawHudElement(UiRenderer c, WindowImpl w, Rect r, boolean editing) {
		int depth = c.depth();
		c.push();
		try {
			if (theme.hudBackground.get()) {
				float rad = theme.rounding.get();
				c.backdrop(r.x() - 3, r.y() - 3, r.w() + 6, r.h() + 6, rad, 1);
				c.roundRect(r.x() - 3, r.y() - 3, r.w() + 6, r.h() + 6, rad, theme.windowBackground.argb());
			}
			c.translate(r.x(), r.y());
			w.panel.render(c, r.w(), r.h(), editing ? -1000 : mouseX - r.x(), editing ? -1000 : mouseY - r.y());
		} catch (Throwable t) {
			if (failedPanels.add(w)) LOG.error("HUD panel {} threw while rendering", w.type.id(), t);
		} finally {
			c.restoreDepth(depth + 1);
			c.pop();
		}
	}

	private void drawWorkspaceLayer(UiRenderer c, Workspace ws, float t, boolean outgoing) {
		// Follow the mouse directly while resizing; animate everything else.
		boolean resizing = drag != null && (drag.kind == DragKind.SPLITTER || drag.kind == DragKind.TILE_RESIZE);
		arrange(ws, !resizing);
		c.push();
		float dir = Integer.compare(active, previous);
		switch (theme.workspaceAnimation.get()) {
			case SLIDE -> c.translate((outgoing ? -t : 1 - t) * dir * screenW, 0);
			case SLIDE_VERTICAL -> c.translate(0, (outgoing ? -t : 1 - t) * dir * screenH);
			case FADE -> c.alpha(outgoing ? 1 - t : t);
			case NONE -> {
				if (outgoing) {
					c.pop();
					return;
				}
			}
		}
		boolean interactive = !outgoing && t >= 1;
		for (WindowImpl w : ws.drawOrder()) {
			if (ws.index == HUD_WORKSPACE && w.isHudElement()) {
				drawHudElement(c, w, hudRect(w), true);
				continue;
			}
			drawWindow(c, w, w.current(), w == ws.focused, interactive);
		}
		c.pop();
	}

	private void drawClosing(UiRenderer c) {
		for (WindowImpl w : new ArrayList<>(closing)) {
			if (w.opacity.get() <= 0.01f) {
				closing.remove(w);
				continue;
			}
			if (w.workspace == active) drawWindow(c, w, w.target, false, false);
		}
	}

	private float borderAngle() {
		float base = theme.borderAngle.get().floatValue();
		if (!theme.animateBorder.get() || !theme.animations.get()) return base;
		double secs = (System.nanoTime() - borderEpoch) / 1e9;
		return (float) (base + secs / theme.borderSpeed.get() * 360 % 360);
	}

	private void drawWindow(UiRenderer c, WindowImpl w, Rect r, boolean focused, boolean interactive) {
		if (r.w() < 1 || r.h() < 1) return;
		float op = w.opacity.get();
		float rad = w.fullscreen ? 0 : theme.rounding.get();
		int depth = c.depth();
		c.push();
		try {
			float alpha = op * (focused ? 1f : theme.inactiveOpacity.get().floatValue());
			c.alpha(alpha);
			if (op < 1) {
				// Hyprland "popin": grow from the theme's popin scale around the centre.
				float p = theme.popin.get().floatValue();
				float s = p + (1 - p) * op;
				c.translate(r.centerX(), r.centerY());
				c.scale(s);
				c.translate(-r.centerX(), -r.centerY());
			}
			if (theme.shadow.get() && !w.fullscreen) {
				c.shadow(r.x(), r.y(), r.w(), r.h(), rad, theme.shadowRange.get(), theme.shadowColor.argb());
			}
			c.backdrop(r.x(), r.y(), r.w(), r.h(), rad, 1);
			c.roundRect(r.x(), r.y(), r.w(), r.h(), rad, theme.windowBackground.argb());

			Rect content = contentRect(w, r);
			if (theme.titleBars.get() && !w.fullscreen) drawTitleBar(c, w, r, rad, focused, interactive);

			c.push();
			c.clip(content.x(), content.y(), content.w(), content.h());
			c.translate(content.x(), content.y());
			boolean hoverable = interactive && launcher.isOpen() == false && topWindowAt(mouseX, mouseY) == w;
			float lmx = hoverable ? mouseX - content.x() : -10000, lmy = hoverable ? mouseY - content.y() : -10000;
			int innerDepth = c.depth();
			try {
				w.panel.render(c, content.w(), content.h(), lmx, lmy);
			} catch (Throwable t) {
				c.restoreDepth(innerDepth);
				if (failedPanels.add(w)) LOG.error("Panel {} threw while rendering", w.type.id(), t);
				c.text(" " + t.getClass().getSimpleName() + ": " + t.getMessage(), 6, 6, 0xFFF38BA8);
			}
			c.pop();

			if (!focused && theme.dimInactive.get() > 0) {
				c.roundRect(r.x(), r.y(), r.w(), r.h(), rad, ColorUtil.argb((int) (theme.dimInactive.get() * 255), 0, 0, 0));
			}
			int bs = theme.borderSize.get();
			if (bs > 0) {
				if (focused) c.gradientOutline(r.x(), r.y(), r.w(), r.h(), rad, bs, theme.activeBorderFrom.argb(), theme.activeBorderTo.argb(), borderAngle());
				else c.outline(r.x(), r.y(), r.w(), r.h(), rad, bs, theme.inactiveBorder.argb());
			}
			if (drag != null && drag.kind == DragKind.SWAP && drag.window != w && interactive && r.contains(mouseX, mouseY) && mergeTarget() == null) {
				c.roundRect(r.x(), r.y(), r.w(), r.h(), rad, ColorUtil.withAlpha(theme.accent.argb(), 40));
			}
		} finally {
			c.restoreDepth(depth + 1);
			c.pop();
		}
	}

	private void drawTitleBar(UiRenderer c, WindowImpl w, Rect r, float rad, boolean focused, boolean interactive) {
		float b = theme.borderSize.get();
		c.roundRect(r.x() + b, r.y() + b, r.w() - b * 2, TITLE_H, Math.max(0, rad - b), Math.max(0, rad - b), 0, 0, theme.titleBackground.argb());
		float ty = r.y() + b + (TITLE_H - c.textHeight()) / 2;
		float x = r.x() + b + 6;
		String icon = w.panel.icon() != null ? w.panel.icon() : w.type.icon();
		if (icon != null) {
			c.text(FontFamily.MONO, c.defaultFontSize(), icon, x, ty, focused ? theme.accent.argb() : theme.textDim.argb());
			x += 11;
		}
		var controls = titleControls(w, r);
		float buttons = 26;
		for (var e : controls) buttons += e.getKey().width() + 3;
		float room = r.right() - buttons - x - 4;
		String title = c.ellipsize(FontFamily.SANS_BOLD, c.defaultFontSize(), safeTitle(w), room);
		c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), title, x, ty, focused ? theme.text.argb() : theme.textDim.argb());
		String sub = safeSubtitle(w);
		if (sub != null && !sub.isEmpty()) {
			float sx = x + c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), title) + 5;
			String s = c.ellipsize(FontFamily.SANS, c.defaultFontSize(), sub, x + room - sx);
			c.text(FontFamily.SANS, c.defaultFontSize(), s, sx, ty, ColorUtil.withAlpha(theme.textDim.argb(), focused ? 220 : 150));
		}
		if (mergeTarget() == w) {
			// Dropping here merges the dragged category window into this one.
			c.roundRect(r.x() + b, r.y() + b, r.w() - b * 2, TITLE_H, Math.max(0, rad - b), Math.max(0, rad - b), 0, 0, ColorUtil.withAlpha(theme.accent.argb(), 110));
			String m = "\uf0c1  Merge";
			c.text(FontFamily.SANS_BOLD, c.defaultFontSize(), m, r.centerX() - c.textWidth(FontFamily.SANS_BOLD, c.defaultFontSize(), m) / 2, ty, theme.text.argb());
		}
		// The panel's own controls (a switch, icon buttons), then float and close.
		float cy = r.y() + b + TITLE_H / 2;
		for (var e : controls) {
			TitleControl tc = e.getKey();
			float cx = e.getValue();
			boolean hover = interactive && mouseX >= cx && mouseX < cx + tc.width() && mouseY >= r.y() && mouseY < r.y() + TITLE_H;
			if (hover) titleTip = tc.tooltip();
			if (tc.isToggle()) {
				tc.knob().animateTo(tc.isOn() ? 1 : 0, 160, theme.bezier());
				float t = tc.knob().get(), sw = tc.width() - 2, sh = 8, sx = cx + 1, sy = cy - sh / 2, k = sh - 3;
				c.roundRect(sx, sy, sw, sh, sh / 2, ColorUtil.lerp(hover ? theme.textDim.argb() : theme.surfaceHover.argb(), theme.accent.argb(), t));
				c.circle(sx + 1.5f + k / 2 + (sw - 3 - k) * t, cy, k / 2, 0xFFFFFFFF);
			} else {
				c.text(FontFamily.MONO, c.defaultFontSize(), tc.icon(), cx + 1, ty, hover ? theme.text.argb() : theme.textDim.argb());
			}
		}
		// Buttons: float toggle, close.
		float bx = r.right() - b - 24;
		boolean hoverFloat = interactive && mouseX >= bx && mouseX < bx + 11 && mouseY >= r.y() && mouseY < r.y() + TITLE_H;
		boolean hoverClose = interactive && mouseX >= bx + 12 && mouseX < bx + 23 && mouseY >= r.y() && mouseY < r.y() + TITLE_H;
		c.text(FontFamily.MONO, c.defaultFontSize(), w.floating ? "" : "", bx + 1, ty, hoverFloat ? theme.text.argb() : theme.textDim.argb());
		c.text(FontFamily.MONO, c.defaultFontSize(), "", bx + 13, ty, hoverClose ? 0xFFF38BA8 : theme.textDim.argb());
	}

	/** A title-bar control hovered this frame, whose tooltip shows instead of the panel's. */
	private String titleTip;

	/** The panel's title-bar controls and the x of each, right to left from just before the float and close buttons. */
	private List<Map.Entry<TitleControl, Float>> titleControls(WindowImpl w, Rect r) {
		List<TitleControl> all;
		try {
			all = w.panel.titleControls();
		} catch (Throwable t) {
			return List.of();
		}
		List<Map.Entry<TitleControl, Float>> out = new ArrayList<>();
		float x = r.right() - theme.borderSize.get() - 24 - 3;
		for (TitleControl tc : all) {
			if (!tc.isVisible()) continue;
			x -= tc.width();
			out.add(Map.entry(tc, x));
			x -= 3;
		}
		return out;
	}

	private String safeSubtitle(WindowImpl w) {
		try {
			return w.panel.subtitle();
		} catch (Throwable t) {
			return null;
		}
	}

	private String safeTitle(WindowImpl w) {
		try {
			return w.panel.title();
		} catch (Throwable t) {
			return w.type.name();
		}
	}

	private void drawHudEditorOverlay(UiRenderer c) {
		int guide = ColorUtil.withAlpha(theme.accent.argb(), 90);
		c.rect(screenW / 3, 0, 0.5f, screenH, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		c.rect(screenW * 2 / 3, 0, 0.5f, screenH, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		c.rect(0, screenH / 3, screenW, 0.5f, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		c.rect(0, screenH * 2 / 3, screenW, 0.5f, ColorUtil.withAlpha(theme.textDim.argb(), 25));
		for (WindowImpl w : workspaces[HUD_WORKSPACE].floating) {
			if (!w.isHudElement()) continue;
			Rect r = hudRect(w);
			boolean hover = r.inset(-2).contains(mouseX, mouseY);
			boolean dragging = drag != null && drag.window == w;
			c.outline(r.x() - 2, r.y() - 2, r.w() + 4, r.h() + 4, 3, 1, hover || dragging ? theme.accent.argb() : ColorUtil.withAlpha(theme.textDim.argb(), 80));
			if (hover || dragging) {
				String label = safeTitle(w) + "  ·  " + w.anchor.name().toLowerCase().replace('_', ' ');
				float ly = r.y() - 2 - c.textHeight() - 2 < 0 ? r.bottom() + 3 : r.y() - 2 - c.textHeight() - 2;
				c.text(FontFamily.SANS, c.defaultFontSize() * 0.85f, label, r.x(), ly, theme.accent.argb());
			}
			if (dragging) {
				if (snappedX) c.rect(r.centerX(), 0, 0.5f, screenH, guide);
				if (snappedY) c.rect(0, r.centerY(), screenW, 0.5f, guide);
			}
		}
		String hint = "HUD · drag to move · right-click for settings · Alt+Space to add elements";
		c.text(FontFamily.SANS, c.defaultFontSize() * 0.85f, hint, (screenW - c.textWidth(FontFamily.SANS, c.defaultFontSize() * 0.85f, hint)) / 2,
			barAtTop() ? barHeight() + 6 : 6, theme.textDim.argb());
	}

	private void drawEmptyHint(UiRenderer c) {
		String a = "Workspace " + active + " is empty";
		String b = modName() + "+Space to open something · " + modName() + "+1-9 to switch";
		float size = c.defaultFontSize();
		c.text(FontFamily.SANS_BOLD, size * 1.4f, a, (screenW - c.textWidth(FontFamily.SANS_BOLD, size * 1.4f, a)) / 2, screenH / 2 - 14, theme.text.argb());
		c.text(b, (screenW - c.textWidth(b)) / 2, screenH / 2 + 4, theme.textDim.argb());
	}

	private void drawTooltip(UiRenderer c) {
		if (drag != null || launcher.isOpen()) return;
		WindowImpl w = topWindowAt(mouseX, mouseY);
		if (w == null) return;
		String tip = titleTip;
		try {
			if (tip == null) tip = w.panel.tooltip();
		} catch (Throwable t) {
			return;
		}
		if (tip == null || tip.isBlank()) return;
		float max = 180;
		float lh = c.textHeight() + 1;
		float bs = c.defaultFontSize() * 0.75f, bh = c.textHeight(FontFamily.SANS, bs) + 4;
		// Plain lines wrap; badge lines (Widget.badgeLine) become a row of pills.
		List<Object> rows = new ArrayList<>();
		float tw = 0, th = 6;
		for (String part : tip.split("\n", -1)) {
			if (part.startsWith(dev.myriadclient.api.ui.widget.Widget.BADGE_LINE)) {
				String[] labels = part.substring(dev.myriadclient.api.ui.widget.Widget.BADGE_LINE.length()).split("\t");
				float rw = 0;
				for (String l : labels) rw += c.textWidth(FontFamily.SANS_BOLD, bs, badgeText(l)) + 12;
				tw = Math.max(tw, rw - 4);
				th += bh + 2;
				rows.add(labels);
				continue;
			}
			for (String l : dev.myriadclient.api.ui.widget.Label.wrap(c, c.defaultFont(), c.defaultFontSize(), part, max)) {
				tw = Math.max(tw, c.textWidth(l));
				th += lh;
				rows.add(l);
			}
		}
		float x = Math.min(mouseX + 8, screenW - tw - 10), y = Math.min(mouseY + 10, screenH - th - 4);
		c.roundRect(x, y, tw + 8, th, 4, 0xF0181825);
		c.outline(x, y, tw + 8, th, 4, 1, ColorUtil.withAlpha(theme.accent.argb(), 120));
		float ly = y + 3;
		for (Object row : rows) {
			if (row instanceof String l) {
				c.text(l, x + 4, ly, theme.text.argb());
				ly += lh;
				continue;
			}
			float bx = x + 4;
			ly += 1;
			for (String label : (String[]) row) {
				String text = badgeText(label);
				Integer tint = badgeTint(label);
				float pw = c.textWidth(FontFamily.SANS_BOLD, bs, text) + 8;
				if (tint != null) {
					c.roundRect(bx, ly, pw, bh, bh / 2, ColorUtil.withAlpha(tint, 50));
					c.outline(bx, ly, pw, bh, bh / 2, 1, ColorUtil.withAlpha(tint, 110));
					c.text(FontFamily.SANS_BOLD, bs, text, bx + 4, ly + 2, ColorUtil.lerp(tint, theme.text.argb(), 0.35f));
				} else {
					c.outline(bx, ly, pw, bh, bh / 2, 1, ColorUtil.withAlpha(theme.textDim.argb(), 90));
					c.text(FontFamily.SANS, bs, text, bx + 4, ly + 2, theme.textDim.argb());
				}
				bx += pw + 4;
			}
			ly += bh + 1;
		}
	}

	private static String badgeText(String label) {
		int at = label.lastIndexOf('@');
		return at > 0 && label.length() - at == 9 ? label.substring(0, at) : label;
	}

	private static Integer badgeTint(String label) {
		int at = label.lastIndexOf('@');
		if (at <= 0 || label.length() - at != 9) return null;
		try {
			return (int) Long.parseLong(label.substring(at + 1), 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- bar ------------------------------------------------------------------------------------------------------

	private record BarSlot(BarWidget widget, float x, float w) {
	}

	private List<BarSlot> barLayout(UiRenderer c) {
		float h = barHeight();
		List<BarSlot> slots = new ArrayList<>();
		Map<BarWidget.Side, List<BarWidget>> sides = new java.util.EnumMap<>(BarWidget.Side.class);
		for (BarWidget w : Myriad.barWidgets()) sides.computeIfAbsent(w.side(), k -> new ArrayList<>()).add(w);
		for (List<BarWidget> l : sides.values()) l.sort(Comparator.comparingInt(BarWidget::order));
		float pad = 6, spacing = 8;
		float x = pad;
		for (BarWidget w : sides.getOrDefault(BarWidget.Side.LEFT, List.of())) {
			float ww = widthOf(w, c, h);
			if (ww <= 0) continue;
			slots.add(new BarSlot(w, x, ww));
			x += ww + spacing;
		}
		float right = screenW - pad;
		List<BarWidget> rightList = new ArrayList<>(sides.getOrDefault(BarWidget.Side.RIGHT, List.of()));
		Collections.reverse(rightList);
		for (BarWidget w : rightList) {
			float ww = widthOf(w, c, h);
			if (ww <= 0) continue;
			right -= ww;
			slots.add(new BarSlot(w, right, ww));
			right -= spacing;
		}
		List<BarWidget> center = sides.getOrDefault(BarWidget.Side.CENTER, List.of());
		float total = 0;
		for (BarWidget w : center) total += Math.max(0, widthOf(w, c, h)) + spacing;
		float cx = (screenW - total + spacing) / 2;
		for (BarWidget w : center) {
			float ww = widthOf(w, c, h);
			if (ww <= 0) continue;
			slots.add(new BarSlot(w, cx, ww));
			cx += ww + spacing;
		}
		return slots;
	}

	private static float widthOf(BarWidget w, UiRenderer c, float h) {
		try {
			return w.width(c, h);
		} catch (Throwable t) {
			return 0;
		}
	}

	private void drawBar(UiRenderer c) {
		float h = barHeight();
		float y = barAtTop() ? 0 : screenH - h;
		c.backdrop(0, y, screenW, h, 0, 1);
		c.rect(0, y, screenW, h, theme.barBackground.argb());
		for (BarSlot s : barLayout(c)) {
			int depth = c.depth();
			try {
				s.widget.render(c, s.x, y, s.w, h, mouseX, mouseY);
			} catch (Throwable t) {
				c.restoreDepth(depth);
			}
		}
	}

	// ================================================================================================================
	// Ticking

	void tickDesktop() {
	}

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

	private WindowImpl topWindowAt(float x, float y) {
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
	private WindowImpl mergeTarget() {
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

	private enum DragKind {
		MOVE, RESIZE, SWAP, SPLITTER, TILE_RESIZE, HUD, PANEL
	}

	private static final class Drag {
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
				for (BarSlot s : barLayout(UiRenderer.get())) {
					if (mx >= s.x && mx < s.x + s.w) {
						try {
							s.widget.mouseClicked(mx - s.x, my - by, button);
						} catch (Throwable t) {
							LOG.error("Bar widget {} threw", s.widget.id(), t);
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
				for (var e : titleControls(w, r)) {
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
		openPanel(dev.myriadclient.impl.ui.panels.CorePanels.PANEL_SETTINGS, args, active, true);
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
			cycleWorkspace(amount > 0 ? -1 : 1);
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
			for (WmBind b : binds.values()) {
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
			if (action.id().equals(dev.myriadclient.impl.CoreAddon.OPEN_MENU) && action.bind().matchesKey(key, mods)) {
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
	// Window manager actions (bound to mod+key)

	public static final class WmBind {
		public final String id, name;
		public final Keybind defaultBind;
		public Keybind bind;
		final Consumer<WindowManager> action;

		WmBind(String id, String name, Keybind bind, Consumer<WindowManager> action) {
			this.id = id;
			this.name = name;
			this.defaultBind = bind;
			this.bind = bind;
			this.action = action;
		}
	}

	private void bind(String id, String name, int key, int mods, Consumer<WindowManager> action) {
		binds.put(id, new WmBind(id, name, Keybind.key(key, mods), action));
	}

	/** Omarchy's tiling bindings, with the mod key standing in for Super (Hyprland keeps Super for itself). */
	private void registerBinds() {
		int shift = GLFW.GLFW_MOD_SHIFT, ctrl = GLFW.GLFW_MOD_CONTROL;
		bind("launcher", "Launcher", GLFW.GLFW_KEY_SPACE, 0, wm -> wm.launcher.toggle());
		bind("console", "Console", GLFW.GLFW_KEY_ENTER, 0, wm -> wm.openPanel(dev.myriadclient.impl.ui.panels.CorePanels.CONSOLE));
		bind("keybinds", "Keybindings", GLFW.GLFW_KEY_K, 0, wm -> wm.openPanel(dev.myriadclient.impl.ui.panels.CorePanels.KEYBINDS));
		bind("close", "Close window", GLFW.GLFW_KEY_W, 0, wm -> wm.focused().ifPresent(Window::close));
		bind("togglesplit", "Toggle window split", GLFW.GLFW_KEY_J, 0, wm -> wm.focused().ifPresent(w -> wm.workspaces[wm.active].tiling.toggleSplit((WindowImpl) w)));
		bind("float", "Toggle floating/tiling", GLFW.GLFW_KEY_T, 0, wm -> wm.focused().ifPresent(w -> w.setFloating(!w.isFloating())));
		bind("fullscreen", "Full screen", GLFW.GLFW_KEY_F, 0, wm -> wm.focused().ifPresent(w -> w.setFullscreen(!w.isFullscreen())));
		bind("layout", "Toggle workspace layout", GLFW.GLFW_KEY_L, 0, WindowManager::cycleLayout);
		bind("hud", "Toggle HUD (scratchpad)", GLFW.GLFW_KEY_S, 0, wm -> wm.switchWorkspace(wm.active == HUD_WORKSPACE ? wm.previous : HUD_WORKSPACE));
		bind("move_hud", "Move window to HUD", GLFW.GLFW_KEY_S, shift, wm -> wm.focused().ifPresent(w -> w.moveToWorkspace(HUD_WORKSPACE)));
		bind("next_workspace", "Next workspace", GLFW.GLFW_KEY_TAB, 0, wm -> wm.cycleWorkspace(1));
		bind("prev_workspace", "Previous workspace", GLFW.GLFW_KEY_TAB, shift, wm -> wm.cycleWorkspace(-1));
		bind("former_workspace", "Former workspace", GLFW.GLFW_KEY_TAB, ctrl, wm -> wm.switchWorkspace(wm.previous));
		bind("shrink", "Shrink window", GLFW.GLFW_KEY_MINUS, 0, wm -> wm.resizeFocused(-0.05f));
		bind("grow", "Grow window", GLFW.GLFW_KEY_EQUAL, 0, wm -> wm.resizeFocused(0.05f));
		bind("bar", "Toggle top bar", GLFW.GLFW_KEY_SPACE, shift, wm -> wm.theme.bar.toggle());
		bind("gaps", "Toggle window gaps", GLFW.GLFW_KEY_BACKSPACE, shift, WindowManager::toggleGaps);
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
			bind("focus_" + n, "Focus on " + n + " window", keys[i], 0, wm -> wm.neighbor(d).ifPresent(wm::focus));
			bind("swap_" + n, "Swap window " + n, keys[i], shift, wm -> wm.swapDirection(d));
		}
	}

	private int savedGapsIn = -1, savedGapsOut = -1;

	private void toggleGaps() {
		if (savedGapsIn < 0) {
			savedGapsIn = theme.gapsIn.get();
			savedGapsOut = theme.gapsOut.get();
			theme.gapsIn.set(0);
			theme.gapsOut.set(0);
		} else {
			theme.gapsIn.set(savedGapsIn);
			theme.gapsOut.set(savedGapsOut);
			savedGapsIn = savedGapsOut = -1;
		}
	}

	/** Next/previous workspace among 1-9 (wrapping), like Omarchy's Super+Tab. */
	private void cycleWorkspace(int dir) {
		int from = active == HUD_WORKSPACE ? previous : active;
		switchWorkspace(Math.floorMod(from - 1 + dir, WORKSPACES) + 1);
	}

	private void resizeFocused(float amount) {
		WindowImpl w = workspaces[active].focused;
		if (w == null) return;
		if (w.floating && w.floatRect != null) {
			float d = amount * 400;
			Rect r = w.floatRect;
			w.floatRect = new Rect(r.x() - d / 2, r.y() - d / 2, Math.max(80, r.w() + d), Math.max(40, r.h() + d));
		} else {
			workspaces[active].tiling.resize(w, amount);
		}
		markDirty();
	}

	private void cycleLayout() {
		Workspace ws = workspaces[active];
		List<Layout> layouts = new ArrayList<>(Myriad.layouts().values());
		if (layouts.isEmpty()) return;
		int i = layouts.indexOf(ws.layout);
		Layout next = layouts.get((i + 1) % layouts.size());
		ws.setLayout(next);
		Myriad.notifications().send("Menu", "Layout: " + next.name(), dev.myriadclient.api.service.Notifications.Level.INFO, 1500, "layout");
		markDirty();
	}

	private Optional<WindowImpl> neighbor(Direction d) {
		Workspace ws = workspaces[active];
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
		Workspace ws = workspaces[active];
		WindowImpl from = ws.focused;
		if (from == null || from.floating) return;
		neighbor(d).filter(w -> !w.floating).ifPresent(to -> {
			ws.tiling.swap(from, to);
			markDirty();
		});
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
		for (WmBind bind : binds.values()) b.addProperty(bind.id, bind.bind.serialize());
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
		JsonArray seen = new JsonArray();
		for (String g : seenGroups) seen.add(g);
		o.add("seen_groups", seen);
		JsonArray wins = new JsonArray();
		for (WindowImpl w : windows) {
			// Dialogs hold callbacks that don't survive a restart.
			if (w.panel instanceof DialogPanel) continue;
			JsonObject j = new JsonObject();
			j.addProperty("uid", w.uid);
			j.addProperty("type", w.type.id().toString());
			j.add("args", w.args);
			j.addProperty("workspace", w.workspace);
			j.addProperty("floating", w.floating);
			j.addProperty("fullscreen", w.fullscreen);
			if (w.floatRect != null) {
				JsonArray r = new JsonArray();
				r.add(w.floatRect.x());
				r.add(w.floatRect.y());
				r.add(w.floatRect.w());
				r.add(w.floatRect.h());
				j.add("rect", r);
			}
			JsonObject hud = new JsonObject();
			hud.addProperty("anchor", w.anchor.name());
			hud.addProperty("x", w.anchorX);
			hud.addProperty("y", w.anchorY);
			j.add("hud", hud);
			JsonObject state = new JsonObject();
			try {
				w.panel.save(state);
			} catch (Throwable t) {
				LOG.error("Panel {} failed to save", w.type.id(), t);
			}
			j.add("state", state);
			j.add("settings", w.panel.settings.toJson());
			wins.add(j);
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
		seenGroups.clear();
		if (o == null) {
			firstRun();
			placeNewGroups(false);
			modulesChanged = false;
			return;
		}
		if (o.has("seen_groups")) for (JsonElement e : o.getAsJsonArray("seen_groups")) seenGroups.add(e.getAsString());
		loadTheme(o);
		if (o.has("binds")) {
			JsonObject b = o.getAsJsonObject("binds");
			for (WmBind bind : binds.values()) if (b.has(bind.id)) bind.bind = Keybind.deserialize(b.get(bind.id).getAsString());
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
		placeNewGroups(true);
		modulesChanged = false;
	}

	/** The addon + category groups a window shows (empty for anything but a category window). */
	private static List<ModuleGroup> groupsOf(WindowImpl w) {
		return w.panel instanceof ModulesPanel p ? p.groups() : List.of();
	}

	/** Narrowest a tiled window is readable at (a category window's module names fit); see {@link #capacity()}. */
	static final float MIN_TILE_WIDTH = dev.myriadclient.impl.ui.layout.ColumnsLayout.MIN_WIDTH;

	/** How many tiled windows a workspace takes before new addons go elsewhere: as many as fit side by side. */
	private int capacity() {
		Rect area = workArea().inset(theme.gapsOut.get());
		float gap = theme.gapsIn.get();
		return Math.max(2, (int) ((area.w() + gap) / (MIN_TILE_WIDTH + gap)));
	}

	/**
	 * Gives each addon + category group the desktop hasn't seen yet a window, one addon at a time, by
	 * {@link GroupPlacement}: beside its categories' windows when they have room, otherwise together on a workspace of
	 * its own (spilling onto the next empty one when there are more than fit). Groups already on screen, or placed
	 * before and since closed or moved, are left alone.
	 */
	private void placeNewGroups(boolean announce) {
		Set<ModuleGroup> shown = new HashSet<>();
		for (WindowImpl w : windows) shown.addAll(groupsOf(w));
		Map<String, List<ModuleGroup>> byAddon = new LinkedHashMap<>();
		for (dev.myriadclient.api.addon.Addon a : Myriad.addons()) byAddon.put(a.id(), new ArrayList<>());
		for (ModuleGroup g : ModuleGroup.all()) {
			if (!seenGroups.add(g.key()) || shown.contains(g)) continue;
			byAddon.computeIfAbsent(g.addon(), k -> new ArrayList<>()).add(g);
		}
		int capacity = capacity();
		for (List<ModuleGroup> groups : byAddon.values()) {
			if (groups.isEmpty()) continue;
			int[] counts = new int[WORKSPACES + 1];
			for (int i = 1; i <= WORKSPACES; i++) counts[i] = workspaces[i].tiling.windows().size();
			int[] siblings = new int[groups.size()];
			for (int i = 0; i < groups.size(); i++) {
				WindowImpl s = sibling(groups.get(i), -1);
				siblings[i] = s == null ? -1 : s.workspace;
			}
			int[] targets = GroupPlacement.place(siblings, counts, capacity);
			java.util.TreeSet<Integer> used = new java.util.TreeSet<>();
			for (int ws : targets) used.add(ws);
			for (int ws : used) {
				List<ModuleGroup> batch = new ArrayList<>();
				for (int i = 0; i < groups.size(); i++) if (targets[i] == ws) batch.add(groups.get(i));
				openGroups(ws, batch);
			}
			if (announce) {
				ModuleGroup first = groups.getFirst();
				String what = groups.size() == 1 ? first.category().name() + " modules" : groups.size() + " windows";
				Myriad.notifications().send("Menu", first.addonName() + " added " + what + " to " + workspacesText(used),
					dev.myriadclient.api.service.Notifications.Level.INFO, 5000, "groups");
			}
		}
		markDirty();
	}

	/** "workspace 2", "workspaces 2 and 3", "workspaces 2, 3 and 5". */
	private static String workspacesText(java.util.SortedSet<Integer> used) {
		if (used.size() == 1) return "workspace " + used.first();
		List<String> names = new ArrayList<>();
		for (int ws : used) names.add(String.valueOf(ws));
		return "workspaces " + String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
	}

	/** The last window (on {@code workspace}, or any if it's -1) showing a group of {@code g}'s category. */
	private WindowImpl sibling(ModuleGroup g, int workspace) {
		WindowImpl found = null;
		for (WindowImpl w : windows) {
			if (w.workspace == HUD_WORKSPACE || (workspace >= 0 && w.workspace != workspace)) continue;
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
		Workspace ws = workspaces[workspace];
		WindowImpl keepFocus = ws.focused;
		boolean grid = ws.tiling.windows().isEmpty() && ws.layout instanceof dev.myriadclient.impl.ui.layout.DwindleLayout;
		java.util.ArrayDeque<WindowImpl> toSplit = new java.util.ArrayDeque<>();
		WindowImpl last = null;
		for (ModuleGroup g : groups) {
			WindowImpl target = grid ? toSplit.poll() : sibling(g, workspace);
			if (target == null && !grid) target = last;
			ws.focused = target != null && !target.floating ? target : null;
			Optional<Window> opened = openPanel(CorePanels.CATEGORY, ModuleGroup.args(List.of(g)), workspace, false);
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
	public void openGroup(ModuleGroup group) {
		for (WindowImpl w : windows) {
			if (!groupsOf(w).contains(group)) continue;
			if (isOpen() && w.workspace != active) switchWorkspace(w.workspace);
			focus(w);
			return;
		}
		openPanel(CorePanels.CATEGORY, ModuleGroup.args(List.of(group)));
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
		w.floating = j.has("floating") && j.get("floating").getAsBoolean();
		w.fullscreen = j.has("fullscreen") && j.get("fullscreen").getAsBoolean();
		if (w.workspace == HUD_WORKSPACE) w.floating = true;
		if (j.has("rect")) {
			JsonArray r = j.getAsJsonArray("rect");
			w.floatRect = new Rect(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat());
		}
		if (j.has("hud")) {
			JsonObject h = j.getAsJsonObject("hud");
			try {
				w.anchor = PanelType.Anchor.valueOf(h.get("anchor").getAsString());
			} catch (RuntimeException ignored) {
			}
			w.anchorX = h.get("x").getAsFloat();
			w.anchorY = h.get("y").getAsFloat();
		}
		if (j.has("settings")) panel.settings.fromJson(j.getAsJsonObject("settings"));
		if (j.has("state")) {
			WindowImpl ww = w;
			safe(w, () -> ww.panel.load(j.getAsJsonObject("state")));
		}
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
		seenGroups.clear();
		placeNewGroups(false);
		if (workspaces[1].isEmpty()) openPanel(dev.myriadclient.impl.ui.panels.CorePanels.MODULES, new JsonObject(), 1, false);
		for (WindowImpl w : windows) w.opacity.snap(1);
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
