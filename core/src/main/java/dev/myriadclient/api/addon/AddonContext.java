package dev.myriadclient.api.addon;

import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.hook.Hook;
import dev.myriadclient.api.hook.HookBuilder;
import dev.myriadclient.api.hook.ReplacementBuilder;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.input.KeyAction;
import dev.myriadclient.api.input.Keybind;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.module.Tags;
import dev.myriadclient.api.registry.MyriadId;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.SettingWidgets;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.hud.HudPanel;
import dev.myriadclient.api.ui.layout.Layout;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Everything an addon can register, scoped to that addon: registrations are tagged with its mod id (which is also
 * the namespace of every id it creates) and are rolled back if the addon fails to load. Each addon gets its own, passed
 * to every phase of its {@link MyriadAddon}; keep it if you register later on.
 * <p>
 * The {@code register...} methods throw {@link IllegalArgumentException} when the id is already taken in that
 * registry (two modules with the same name in one addon, say), and return what they registered so it can be kept in
 * a field. Call them on the render thread.
 *
 * <pre>{@code
 * public void initialize(AddonContext ctx) {
 *     ModuleGroup travel = ctx.registerGroup("Travel", Icons.ROCKET);
 *     ctx.registerModules(travel, new AutoWalk(), new Sprint());
 *     ctx.registerCommand(new Hello());
 *     ctx.registerPanel("Stats", Icons.MAP, StatsPanel::new);
 *     ctx.registerKeyAction("Open Stats", Keybind.NONE, () -> Myriad.ui().openPanel(ctx.id("stats")));
 * }
 * }</pre>
 */
@ApiStatus.NonExtendable
public interface AddonContext {
	/** The addon this context belongs to. */
	Addon addon();

	/** Shortcut for {@code addon().id()}. */
	default String namespace() {
		return addon().id();
	}

	/** An id in your namespace, {@code <your mod id>:<path>}; {@code path} is used as given. */
	default MyriadId id(String path) {
		return MyriadId.of(namespace(), path);
	}

	/** An SLF4J logger named after the addon's display name, for its messages in the game log. */
	Logger logger();

	/**
	 * Registers a tag with id {@code <your mod id>:<name in snake_case>}, for modules about something none of
	 * {@link Tags} covers. Prefer the shared ones: {@code #combat} then finds your modules beside everyone else's (a tag
	 * named like a shared one logs a warning). Theme-aware colours follow the theme (e.g. {@code SettingColor.role(MAGENTA)}).
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

	/**
	 * Registers a module in your addon's own window (with the modules you register without a group), giving it the id
	 * {@code <your mod id>:<its name in snake_case>}. It starts off; config, if it hasn't loaded yet, restores its saved
	 * state.
	 */
	<M extends Module> M registerModule(M module);

	/**
	 * Registers a module into {@code group}, one of yours from {@link #registerGroup}; a group of another addon throws
	 * {@link IllegalArgumentException}.
	 */
	<M extends Module> M registerModule(ModuleGroup group, M module);

	/** Registers several modules, in the order given (which is their order in the menu). */
	default void registerModules(Module... modules) {
		for (Module m : modules) registerModule(m);
	}

	/** Registers several modules into {@code group}, in the order given (which is their order in its window). */
	default void registerModules(ModuleGroup group, Module... modules) {
		for (Module m : modules) registerModule(group, m);
	}

	/**
	 * Registers a chat command, with id {@code <your mod id>:<its name>}. It's typed by its name or an alias after the
	 * prefix, so two addons' commands with the same name clash in chat even though their ids differ.
	 */
	<C extends Command> C registerCommand(C command);

	/**
	 * Registers a panel type you built (see {@link PanelType#builder}): a window the player can open, or a HUD element.
	 */
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

	/** Registers a block for the UI's top bar. */
	BarWidget registerBarWidget(BarWidget widget);

	/** Registers a tiling layout a workspace of the UI can arrange its windows by. */
	Layout registerLayout(Layout layout);

	/** Registers a theme preset the player can pick (and edit, keeping their edits on top of it). */
	Theme registerTheme(Theme theme);

	/**
	 * What a server checks, as a profile of your own, its id in your namespace
	 * ({@code AntiCheat.Profile.GRIM.named("my-addon:my_server", "My server").withSimulatesGlide(false)}): the player can
	 * pick it, {@code Match.on} matches it, and your {@link AntiCheat#DETECT} handler can answer with it on your server.
	 * A profile whose id is in another namespace throws {@link IllegalArgumentException}.
	 */
	AntiCheat.Profile registerAntiCheatProfile(AntiCheat.Profile profile);

	/**
	 * A ready-made control panel, with id {@code <your mod id>:<name in snake_case>}: players find it in their Control
	 * Panels, can edit it (their edits are kept) or delete it, and get it back as made with Reset. Returns that id.
	 */
	MyriadId registerControlPanel(ControlPreset preset);

	/**
	 * Handles a hook (core's, like {@link Rotations#TURN}, or another addon's): see
	 * {@link HookBuilder}. The player sees it in the Hooks window and can turn it off.
	 */
	<I, O extends @Nullable Object> HookBuilder<I, O> hook(Hook<I, O> hook);

	/**
	 * Takes over a core service with your own, for everyone or only your own modules: see {@link ReplacementBuilder}
	 * and {@link Service}.
	 */
	<S> ReplacementBuilder<S> replace(Service<S> service);

	/**
	 * A global key action, with id {@code <your mod id>:<name in snake_case>}, rebindable in the Keybinds panel:
	 * {@code action} runs on the render thread when its bind is pressed in game (not while a screen or the menu is
	 * open), and the press goes no further. {@code defaultBind} may be {@link Keybind#NONE} to leave it unbound
	 * until the player binds it. The player's bind is saved for every profile.
	 */
	KeyAction registerKeyAction(String name, Keybind defaultBind, Runnable action);

	/**
	 * Settings of the addon itself (not of a module), shown under it in the Addons panel and saved with the profile.
	 * Several groups of them can be declared, each with a name; each name once. Declare them in
	 * {@link MyriadAddon#initialize}, so they get their saved values when config loads.
	 */
	AddonSettings settings(String name);

	/** Where editor widgets for setting types are registered, for a setting class of your own. */
	SettingWidgets settingWidgets();

	/**
	 * The event bus, the same one as {@link dev.myriadclient.api.Myriad#events()}. A handler that throws is reported as
	 * your addon's, found by the package of its class. Prefer {@code @Subscribe} methods on modules, which receive
	 * events only while they're on.
	 */
	EventBus events();

	/** Your addon's private folder for its own data files. */
	AddonStorage storage();
}
