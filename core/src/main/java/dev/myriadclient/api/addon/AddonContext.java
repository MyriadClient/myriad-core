package dev.myriadclient.api.addon;

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
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.hud.HudPanel;
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

	/**
	 * Registers a tag with id {@code <your mod id>:<name in snake_case>}, for modules about something none of
	 * {@link Tags} covers. Prefer the shared ones: {@code #combat} then finds your modules beside everyone else's.
	 * Theme-aware colours follow the theme (e.g. {@code SettingColor.role(MAGENTA)}).
	 */
	Tag registerTag(String name, String icon, SettingColor color);

	/** Registers a tag you constructed yourself (e.g. kept in a constants class). */
	Tag registerTag(Tag tag);

	/**
	 * Declares a group of your modules, with id {@code <your mod id>:<name in snake_case>}: one window in the menu,
	 * in the order groups are first used. Put modules in it with {@link #registerModule(ModuleGroup, Module)}. An
	 * addon that declares none has one window, named after it, with all its modules.
	 */
	ModuleGroup registerGroup(String name, String icon);

	/** Registers a module in your addon's own window (with the modules you register without a group). */
	<M extends Module> M registerModule(M module);

	/** Registers a module into {@code group}, one of yours from {@link #registerGroup}. */
	<M extends Module> M registerModule(ModuleGroup group, M module);

	/** Registers several modules, in the order given (which is their order in the menu). */
	default void registerModules(Module... modules) {
		for (Module m : modules) registerModule(m);
	}

	/** Registers several modules into {@code group}, in the order given (which is their order in its window). */
	default void registerModules(ModuleGroup group, Module... modules) {
		for (Module m : modules) registerModule(group, m);
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

	/** Registers a HUD element the user can add from the HUD workspace. See {@link HudPanel}. */
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
	 * What a server checks, as a profile of your own, its id in your namespace
	 * ({@code AntiCheat.Profile.GRIM.named("my-addon:my_server", "My server").withSimulatesGlide(false)}): the player can
	 * pick it, {@code Match.on} matches it, and your {@link AntiCheat#DETECT} handler can answer with it on your server.
	 */
	AntiCheat.Profile registerAntiCheatProfile(AntiCheat.Profile profile);

	/**
	 * A ready-made control panel, with id {@code <your mod id>:<name in snake_case>}: players find it in their Control
	 * Panels, can edit it (their edits are kept) or delete it, and get it back as made with Reset.
	 */
	MyriadId registerControlPanel(ControlPreset preset);

	/**
	 * Handles a hook (core's, like {@link Rotations#TURN}, or another addon's): see
	 * {@link HookBuilder}. The player sees it in the Hooks window and can turn it off.
	 */
	<I, O> HookBuilder<I, O> hook(Hook<I, O> hook);

	/**
	 * Takes over a core service with your own, for everyone or only your own modules: see {@link ReplacementBuilder}
	 * and {@link Service}.
	 */
	<S> ReplacementBuilder<S> replace(Service<S> service);

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
