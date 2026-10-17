package dev.myriadclient.api.addon;

import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.Keybind;
import dev.myriadclient.api.util.MyriadId;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Everything an addon can register, scoped to that addon: registrations are tagged with its mod id (which is also
 * the namespace of every id it creates) and are rolled back if the addon fails to load.
 */
@ApiStatus.NonExtendable
public interface AddonContext {
	Addon addon();

	/** Shortcut for {@code addon().id()}. */
	default String namespace() {
		return addon().id();
	}

	default MyriadId id(String path) {
		return MyriadId.of(namespace(), path);
	}

	Logger logger();

	/** Registers a category with id {@code <your mod id>:<name in snake_case>}. */
	Category registerCategory(String name, String icon, int color);

	/** Like {@link #registerCategory(String, String, int)} with a theme-aware colour (e.g. {@code SettingColor.role(MAGENTA)}). */
	Category registerCategory(String name, String icon, SettingColor color);

	/** Registers a category you constructed yourself (e.g. kept in a constants class). */
	Category registerCategory(Category category);

	<M extends Module> M registerModule(M module);

	/** Registers several modules, in the order given (which is their order in the menu). */
	default void registerModules(Module... modules) {
		for (Module m : modules) registerModule(m);
	}

	<C extends Command> C registerCommand(C command);

	PanelType registerPanel(PanelType type);

	/**
	 * Registers a panel that opens as a window, with id {@code <your mod id>:<name in snake_case>}. For panels that
	 * take arguments or need more options, build a {@link PanelType} yourself.
	 */
	default PanelType registerPanel(String name, String icon, Supplier<? extends Panel> factory) {
		return registerPanel(PanelType.builder(id(MyriadId.toPath(name)), name).icon(icon).factory(() -> factory.get()).build());
	}

	/** Registers a HUD element the user can add from the HUD workspace. See {@link dev.myriadclient.api.ui.hud.HudPanel}. */
	default PanelType registerHud(String name, String icon, Supplier<? extends Panel> factory) {
		return registerPanel(PanelType.builder(id(MyriadId.toPath(name)), name).icon(icon).factory(() -> factory.get()).hud().build());
	}

	/** Like {@link #registerHud(String, String, Supplier)}, and a fresh install shows it at {@code anchor} + offset. */
	default PanelType registerHud(String name, String icon, Supplier<? extends Panel> factory, PanelType.Anchor anchor, float offsetX, float offsetY) {
		return registerPanel(PanelType.builder(id(MyriadId.toPath(name)), name).icon(icon).factory(() -> factory.get())
			.defaultHud(anchor, offsetX, offsetY).build());
	}

	BarWidget registerBarWidget(BarWidget widget);

	Layout registerLayout(Layout layout);

	Theme registerTheme(Theme theme);

	/**
	 * A ready-made control panel, with id {@code <your mod id>:<name in snake_case>}: players find it in their Control
	 * Panels, can edit it (their edits are kept) or delete it, and get it back as made with Reset.
	 */
	MyriadId registerControlPanel(ControlPreset preset);

	/** A global key action (works in game, outside the menu), rebindable in the Keybinds panel. */
	KeyAction registerKeyAction(String name, Keybind defaultBind, Runnable action);

	/**
	 * Settings of the addon itself (not of a module), shown under it in the Addons panel and saved with the profile.
	 * Several groups of them can be declared, each with a name.
	 */
	AddonSettings settings(String name);

	SettingWidgets settingWidgets();

	/**
	 * The event bus. Listeners registered via this context are owned by the addon (exceptions are attributed to it).
	 * Prefer subscribing modules implicitly by enabling them.
	 */
	EventBus events();

	AddonStorage storage();
}
