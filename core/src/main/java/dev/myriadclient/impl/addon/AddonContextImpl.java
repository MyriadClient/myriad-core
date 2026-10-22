package dev.myriadclient.impl.addon;

import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.addon.AddonStorage;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.HookBuilder;
import dev.myriadclient.api.hook.ReplacementBuilder;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.control.ControlManager;
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
	public Tag registerTag(String name, String icon, SettingColor color) {
		for (Tag core : Tags.DEFAULTS) {
			if (core.name().equalsIgnoreCase(name)) {
				logger.warn("Registering a tag named '{}', which duplicates Myriad's shared one; use Tags.{} instead "
					+ "so #{} finds your modules with everyone else's", name, core.id().path().toUpperCase(), core.id().path());
			}
		}
		return registerTag(new Tag(id(MyriadId.toPath(name)), name, icon, color));
	}

	@Override
	public Tag registerTag(Tag tag) {
		return myriad.tags().register(tag, addon.id());
	}

	@Override
	public ModuleGroup registerGroup(String name, String icon) {
		return myriad.groups().register(new ModuleGroup(id(MyriadId.toPath(name)), name, icon), addon.id());
	}

	@Override
	public <M extends Module> M registerModule(M module) {
		myriad.modules().register(module, addon.id());
		return module;
	}

	@Override
	public <M extends Module> M registerModule(ModuleGroup group, M module) {
		if (!group.addon().equals(addon.id())) {
			throw new IllegalArgumentException("Group " + group.id() + " belongs to another addon; " + addon.id() + " can only use its own");
		}
		module.assignGroup(group);
		return registerModule(module);
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
	public AntiCheat.Profile registerAntiCheatProfile(AntiCheat.Profile profile) {
		if (!profile.id().namespace().equals(addon.id())) {
			throw new IllegalArgumentException("Anti-cheat profile " + profile.id() + " has to be in " + addon.id() + "'s namespace");
		}
		return myriad.antiCheat().profiles().register(profile, addon.id());
	}

	@Override
	public MyriadId registerControlPanel(ControlPreset preset) {
		MyriadId id = id(MyriadId.toPath(preset.name()));
		myriad.controlManager().presets().register(new ControlManager.Preset(id, preset), addon.id());
		return id;
	}

	@Override
	public <I, O> HookBuilder<I, O> hook(Hook<I, O> hook) {
		return myriad.hookManager().hookBuilder(addon.id(), hook);
	}

	@Override
	public <S> ReplacementBuilder<S> replace(Service<S> service) {
		return myriad.hookManager().replacementBuilder(addon.id(), service);
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
