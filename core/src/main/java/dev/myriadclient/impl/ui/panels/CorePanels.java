package dev.myriadclient.impl.ui.panels;

import com.google.gson.JsonObject;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.control.ControlPanel;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Window;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.ui.WindowManager;

import java.util.List;

/** Ids and registration of the panels Myriad core provides. */
public final class CorePanels {
	public static final MyriadId MODULES = MyriadId.of("myriad", "modules");
	public static final MyriadId CATEGORY = MyriadId.of("myriad", "category");
	public static final MyriadId MODULE_SETTINGS = MyriadId.of("myriad", "module_settings");
	public static final MyriadId PANEL_SETTINGS = MyriadId.of("myriad", "panel_settings");
	public static final MyriadId CONSOLE = MyriadId.of("myriad", "console");
	public static final MyriadId DIALOG = MyriadId.of("myriad", "dialog");
	public static final MyriadId PROFILES = MyriadId.of("myriad", "profiles");
	public static final MyriadId ADDONS = MyriadId.of("myriad", "addons");
	public static final MyriadId FRIENDS = MyriadId.of("myriad", "friends");
	public static final MyriadId THEME = MyriadId.of("myriad", "theme");
	public static final MyriadId KEYBINDS = MyriadId.of("myriad", "keybinds");
	public static final MyriadId HUD_ELEMENTS = MyriadId.of("myriad", "hud_elements");
	public static final MyriadId PROFILER = MyriadId.of("myriad", "profiler");
	public static final MyriadId CONTROLS = MyriadId.of("myriad", "controls");
	public static final MyriadId HOOKS = MyriadId.of("myriad", "hooks");
	public static final MyriadId CONTROL = MyriadId.of("myriad", "control");

	private CorePanels() {
	}

	public static void register(AddonContext ctx, WindowManager wm) {
		ctx.registerPanel(PanelType.builder(MODULES, "Modules").icon("").factory(() -> new ModulesPanel(null)).build());
		ctx.registerPanel(PanelType.builder(CATEGORY, "Category").icon("").factory(args -> {
			// A window whose addons are all gone stays saved (as an orphan) until they're back.
			List<ModuleGroup> groups = ModuleGroup.fromArgs(args);
			return groups.stream().anyMatch(g -> !g.modules().isEmpty()) ? new ModulesPanel(groups) : null;
		}).build());
		ctx.registerPanel(PanelType.builder(MODULE_SETTINGS, "Module Settings").icon("").factory(args -> {
			if (!args.has("module")) return null;
			return Myriad.modules().get(MyriadId.parse(args.get("module").getAsString())).map(ModuleSettingsPanel::new).orElse(null);
		}).build());
		ctx.registerPanel(PanelType.builder(PANEL_SETTINGS, "Panel Settings").icon("").factory(args ->
			args.has("window") ? new PanelSettingsPanel(wm, args.get("window").getAsInt()) : null).build());
		ctx.registerPanel(PanelType.builder(DIALOG, "Dialog").icon("\uf059").factory(DialogPanel::create).build());
		ctx.registerPanel(PanelType.builder(CONSOLE, "Console").icon("").factory(ConsolePanel::new).build());
		ctx.registerPanel(PanelType.builder(PROFILES, "Profiles").icon("").factory(ProfilesPanel::new).build());
		ctx.registerPanel(PanelType.builder(ADDONS, "Addons").icon("").factory(AddonsPanel::new).build());
		ctx.registerPanel(PanelType.builder(FRIENDS, "Friends").icon("").factory(FriendsPanel::new).build());
		ctx.registerPanel(PanelType.builder(THEME, "Theme").icon("").factory(ThemePanel::new).build());
		ctx.registerPanel(PanelType.builder(HUD_ELEMENTS, "HUD").icon("\uf2d2").factory(() -> new HudElementsPanel(wm)).build());
		ctx.registerPanel(PanelType.builder(KEYBINDS, "Keybinds").icon("").factory(() -> new KeybindsPanel(wm)).build());
		ctx.registerPanel(PanelType.builder(PROFILER, "Profiler").icon("\uf0e4").factory(ProfilerPanel::new).build());
		ctx.registerPanel(PanelType.builder(HOOKS, "Hooks").icon("\uf1e6").factory(HooksPanel::new).build());
		ctx.registerPanel(PanelType.builder(CONTROLS, "Control Panels").icon("\uf1de").factory(ControlPanelsPanel::new).build());
		// One control panel's window; not listed on its own (the launcher lists each control panel).
		ctx.registerPanel(PanelType.builder(CONTROL, "Control Panel").icon("\uf1de").listed(false).factory(args -> {
			if (!args.has("id")) return null;
			return MyriadImpl.get().controlManager().impls().stream().filter(c -> c.id().toString().equals(args.get("id").getAsString()))
				.findFirst().map(ControlPanelView::new).orElse(null);
		}).build());
	}

	/**
	 * Opens a module's settings. By default an existing settings window on the active workspace is re-targeted
	 * (an "inspector"), so browsing modules doesn't pile up windows; {@code newWindow} always opens another one.
	 */
	/** Opens a control panel's window. */
	public static void openControl(ControlPanel c) {
		ControlPanelsPanel.open(c);
	}

	public static void openModuleSettings(Module module, boolean newWindow) {
		JsonObject args = new JsonObject();
		args.addProperty("module", module.id().toString());
		WindowManager wm = (WindowManager) Myriad.ui();
		if (wm.find(MODULE_SETTINGS, args).isPresent() || newWindow) {
			wm.openPanel(MODULE_SETTINGS, args);
			return;
		}
		for (Window w : wm.windows()) {
			if (w.type().id().equals(MODULE_SETTINGS) && w.workspace() == wm.activeWorkspace()) {
				wm.replacePanel(w, new ModuleSettingsPanel(module), args);
				return;
			}
		}
		wm.openPanel(MODULE_SETTINGS, args);
	}
}
