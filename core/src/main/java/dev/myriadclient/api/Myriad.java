package dev.myriadclient.api;

import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.config.ConfigManager;
import dev.myriadclient.api.control.Controls;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.hook.Hooks;
import dev.myriadclient.api.job.Jobs;
import dev.myriadclient.api.map.Maps;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.ModuleRegistry;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.render.Rendering;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Friends;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.service.ServerStats;
import dev.myriadclient.api.service.TickSpeed;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.PacketChannel;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Static entry point to everything Myriad exposes to addons.
 *
 * <pre>{@code
 * Myriad.modules().get(KillAura.class).ifPresent(Module::toggle);
 * Myriad.notifications().info("My Addon", "Hello!");
 * Myriad.ui().openPanel(MyriadId.of("my-addon", "stats"));
 * }</pre>
 */
public final class Myriad {
	public static final String MOD_ID = "myriad";
	private static MyriadApi api;

	private Myriad() {
	}

	private static MyriadApi api() {
		if (api == null) throw new IllegalStateException("Myriad is not initialised yet");
		return api;
	}

	@ApiStatus.Internal
	public static void install(MyriadApi impl) {
		api = impl;
	}

	public static boolean isReady() {
		return api != null;
	}

	/** The version of Myriad core that's running, e.g. {@code "0.1.0"}. */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
	}

	/**
	 * Whether the running core is {@code version} or newer: for using something added in a later core version while
	 * still supporting older ones (declare the oldest you support in {@code fabric.mod.json}'s {@code depends}).
	 */
	public static boolean isAtLeast(String version) {
		try {
			return Version.parse(version()).compareTo(Version.parse(version)) >= 0;
		} catch (VersionParsingException e) {
			throw new IllegalArgumentException("Not a version: " + version, e);
		}
	}

	public static EventBus events() {
		return api().events();
	}

	/** Every addon Myriad found, including failed ones and Myriad core itself. */
	public static List<Addon> addons() {
		return api().addons();
	}

	/** Every module tag: Myriad's shared ones ({@link dev.myriadclient.api.module.Tags}) and addons' own. */
	public static Registry<Tag> tags() {
		return api().tags();
	}

	/**
	 * The module groups addons declared. Every group with modules, addons' own windows included, is
	 * {@link ModuleRegistry#groups()}.
	 */
	public static Registry<ModuleGroup> groups() {
		return api().groups();
	}

	public static ModuleRegistry modules() {
		return api().modules();
	}

	public static Registry<Command> commands() {
		return api().commands();
	}

	public static Registry<PanelType> panels() {
		return api().panels();
	}

	public static Registry<Layout> layouts() {
		return api().layouts();
	}

	public static Registry<Theme> themes() {
		return api().themes();
	}

	public static Registry<BarWidget> barWidgets() {
		return api().barWidgets();
	}

	public static Registry<KeyAction> keyActions() {
		return api().keyActions();
	}

	/** Every addon's own settings (see {@link AddonContext#settings}). */
	public static Registry<AddonSettings> addonSettings() {
		return api().addonSettings();
	}

	public static ConfigManager config() {
		return api().config();
	}

	public static Notifications notifications() {
		return api().notifications();
	}

	public static Rotations rotations() {
		return api().rotations();
	}

	public static Inventory inventory() {
		return api().inventory();
	}

	/** Shared block placement (neighbour finding, rotation, silent swap, cooldowns). */
	public static Placement placement() {
		return api().placement();
	}

	/** Shared block breaking: queued by priority, instant breaks batched, tool and rotation handled, server-confirmed. */
	public static Breaking breaking() {
		return api().breaking();
	}

	/** Builds blueprints: describe the shape, and the planner breaks and places what's in reach in a working order. */
	public static Building building() {
		return api().building();
	}

	public static Friends friends() {
		return api().friends();
	}

	/** Server TPS and totem pops, tracked once for every addon. */
	public static ServerStats server() {
		return api().server();
	}

	/** Opening containers, reading them and moving items. */
	public static Containers containers() {
		return api().containers();
	}

	/**
	 * Long-running work as jobs: start a {@link dev.myriadclient.api.job.Step} for your module and the runner ticks it,
	 * shows it in the Jobs window, and cancels it when the module turns off.
	 */
	public static Jobs jobs() {
		return api().jobs();
	}

	/**
	 * Elytra flight shared by every flight module: No Durability, chest swaps, the glide hold, and pilots that fly the
	 * local player by their own rotation.
	 */
	public static Elytra elytra() {
		return api().elytra();
	}

	/** Core's renderer, behind the rendering helpers ({@code Renderer3D}, {@code WorldMesh}, ...): use those. */
	@ApiStatus.Internal
	public static Rendering render() {
		return api().render();
	}

	/** Core's networking, behind {@code Packets}: use that. */
	@ApiStatus.Internal
	public static PacketChannel packets() {
		return api().packets();
	}

	/** The map mods installed alongside Myriad (Xaero's Minimap): their waypoints, empty without one. */
	public static Maps maps() {
		return api().maps();
	}

	/** Control panels: sets of modules switched together by one bind, each with the settings it takes while on. */
	public static Controls controls() {
		return api().controls();
	}

	/** Shared configs: share modules, control panels and a theme as a code, take one in (see {@link dev.myriadclient.api.share}). */
	public static ConfigShares shares() {
		return api().shares();
	}

	/** Hook contributions and service replacements (see {@link dev.myriadclient.api.hook}). */
	public static Hooks hooks() {
		return api().hooks();
	}

	/** The shared budget for packets servers count (block actions, interactions, inventory clicks). */
	public static PacketLimits limits() {
		return api().limits();
	}

	/** The speed the client runs game ticks at, combined from every feature that changes it. */
	public static TickSpeed tickSpeed() {
		return api().tickSpeed();
	}

	/**
	 * What the server checks (Grim or nothing beyond vanilla), from the player's choice or detected per server. The
	 * services' {@code forServer()} presets follow it.
	 */
	public static AntiCheat antiCheat() {
		return api().antiCheat();
	}

	/** The window manager. */
	public static Desktop ui() {
		return api().ui();
	}

	/** Prints a client-side chat message with the Myriad prefix (also mirrored in the console panel). */
	public static void chat(Component message) {
		api().chat(message);
	}

	/**
	 * Runs a Myriad command as if typed in chat, with or without the prefix ({@code "toggle sprint"} or
	 * {@code ".toggle sprint"}): for macros, binds and clickable chat. Errors are shown in chat as they would be.
	 */
	public static void runCommand(String line) {
		api().runCommand(line);
	}

	/**
	 * Like {@link #chat(Component)}, replacing the previous message sent with the same {@code id} instead of adding another
	 * line: progress ("Printer: 120/450"), status changes, repeated warnings. Ids are global, so prefix them with your
	 * mod id.
	 */
	public static void chat(Component message, String id) {
		api().chat(message, id);
	}
}
