package dev.myriadclient.impl.addon;

import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AddonContextImpl implements AddonContext {
	private final Addon addon;
	private final MyriadImpl myriad;
	private final Logger logger;
	private final AddonStorage storage;

	AddonContextImpl(Addon addon, MyriadImpl myriad) {
		this.addon = addon;
		this.myriad = myriad;
		this.logger = LoggerFactory.getLogger(addon.name());
		this.storage = new AddonStorageImpl(myriad.configImpl().root().resolve("addons").resolve(addon.id()));
	}

	@Override
	public Addon addon() {
		return addon;
	}

	@Override
	public Logger logger() {
		return logger;
	}

	@Override
	public Category registerCategory(String name, String icon, int color) {
		return registerCategory(name, icon, dev.myriadclient.api.setting.SettingColor.of(color));
	}

	@Override
	public Category registerCategory(String name, String icon, dev.myriadclient.api.setting.SettingColor color) {
		for (Category core : dev.myriadclient.api.module.Categories.DEFAULTS) {
			if (core.name().equalsIgnoreCase(name)) {
				logger.warn("Registering a category named '{}', which duplicates Myriad's shared one; use Categories.{} instead "
					+ "so your modules share a window with other addons'", name, core.id().path().toUpperCase());
			}
		}
		return myriad.categories().register(new Category(id(MyriadId.toPath(name)), name, icon, color), addon.id());
	}

	@Override
	public Category registerCategory(Category category) {
		return myriad.categories().register(category, addon.id());
	}

	@Override
	public <M extends Module> M registerModule(M module) {
		myriad.modules().register(module, addon.id());
		return module;
	}

	@Override
	public <C extends Command> C registerCommand(C command) {
		command.assignNamespace(addon.id());
		myriad.commands().register(command, addon.id());
		return command;
	}

	@Override
	public PanelType registerPanel(PanelType type) {
		return myriad.panels().register(type, addon.id());
	}

	@Override
	public BarWidget registerBarWidget(BarWidget widget) {
		return myriad.barWidgets().register(widget, addon.id());
	}

	@Override
	public Layout registerLayout(Layout layout) {
		return myriad.layouts().register(layout, addon.id());
	}

	@Override
	public Theme registerTheme(Theme theme) {
		return myriad.themes().register(theme, addon.id());
	}

	@Override
	public MyriadId registerControlPanel(dev.myriadclient.api.control.ControlPreset preset) {
		MyriadId id = id(MyriadId.toPath(preset.name()));
		myriad.controlManager().presets().register(new dev.myriadclient.impl.control.ControlManager.Preset(id, preset), addon.id());
		return id;
	}

	@Override
	public KeyAction registerKeyAction(String name, Keybind defaultBind, Runnable action) {
		return myriad.keyActions().register(new KeyAction(id(MyriadId.toPath(name)), name, defaultBind, action), addon.id());
	}

	@Override
	public AddonSettings settings(String name) {
		return myriad.addonSettings().register(new AddonSettings(id(MyriadId.toPath(name)), name), addon.id());
	}

	@Override
	public SettingWidgets settingWidgets() {
		return myriad.ui().settingWidgets();
	}

	@Override
	public EventBus events() {
		return myriad.events();
	}

	@Override
	public AddonStorage storage() {
		return storage;
	}
}
