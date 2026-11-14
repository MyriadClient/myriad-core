package dev.myriadclient.api;

import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.config.ConfigManager;
import dev.myriadclient.api.control.Controls;
import dev.myriadclient.api.dev.DevSteps;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.hook.Hooks;
import dev.myriadclient.api.input.KeyAction;
import dev.myriadclient.api.job.Jobs;
import dev.myriadclient.api.map.Maps;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.ModuleRegistry;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.network.PacketChannel;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.render.Rendering;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Enemies;
import dev.myriadclient.api.service.Friends;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.service.ServerStats;
import dev.myriadclient.api.service.TickSpeed;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.Ui;
import dev.myriadclient.api.ui.layout.Layout;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;

/**
 * The services behind the {@link Myriad} facade, implemented by core: each method returns what the static method of
 * the same name on {@link Myriad} does, and is documented there. Addons call {@link Myriad}, not this.
 */
@ApiStatus.NonExtendable
public interface MyriadApi {
	/** See {@link Myriad#events()}. */
	EventBus events();

	/** See {@link Myriad#addons()}. */
	List<Addon> addons();

	/** See {@link Myriad#tags()}. */
	Registry<Tag> tags();

	/** See {@link Myriad#groups()}. */
	Registry<ModuleGroup> groups();

	/** See {@link Myriad#modules()}. */
	ModuleRegistry modules();

	/** See {@link Myriad#commands()}. */
	Registry<Command> commands();

	/** See {@link Myriad#panels()}. */
	Registry<PanelType> panels();

	/** See {@link Myriad#layouts()}. */
	Registry<Layout> layouts();

	/** See {@link Myriad#themes()}. */
	Registry<Theme> themes();

	/** See {@link Myriad#barWidgets()}. */
	Registry<BarWidget> barWidgets();

	/** See {@link Myriad#keyActions()}. */
	Registry<KeyAction> keyActions();

	/** See {@link Myriad#addonSettings()}. */
	Registry<AddonSettings> addonSettings();

	/** See {@link Myriad#config()}. */
	ConfigManager config();

	/** See {@link Myriad#notifications()}. */
	Notifications notifications();

	/** See {@link Myriad#rotations()}. */
	Rotations rotations();

	/** See {@link Myriad#inventory()}. */
	Inventory inventory();

	/** See {@link Myriad#placement()}. */
	Placement placement();

	/** See {@link Myriad#breaking()}. */
	Breaking breaking();

	/** See {@link Myriad#building()}. */
	Building building();

	/** See {@link Myriad#friends()}. */
	Friends friends();

	/** See {@link Myriad#enemies()}. */
	@ApiStatus.AvailableSince("1.6")
	Enemies enemies();

	/** See {@link Myriad#server()}. */
	ServerStats server();

	/** See {@link Myriad#containers()}. */
	Containers containers();

	/** See {@link Myriad#elytra()}. */
	Elytra elytra();

	/** See {@link Myriad#render()}. */
	@ApiStatus.Internal
	Rendering render();

	/** See {@link Myriad#packets()}. */
	@ApiStatus.Internal
	PacketChannel packets();

	/** See {@link Myriad#maps()}. */
	Maps maps();

	/** See {@link Myriad#controls()}. */
	Controls controls();

	/** See {@link Myriad#hooks()}. */
	Hooks hooks();

	/** See {@link Myriad#shares()}. */
	ConfigShares shares();

	/** See {@link Myriad#jobs()}. */
	Jobs jobs();

	/** See {@link Myriad#limits()}. */
	PacketLimits limits();

	/** See {@link Myriad#tickSpeed()}. */
	TickSpeed tickSpeed();

	/** See {@link Myriad#antiCheat()}. */
	AntiCheat antiCheat();

	/** See {@link Myriad#ui()}. */
	Ui ui();

	/** See {@link Myriad#chat(Component)}. */
	void chat(Component message);

	/** See {@link Myriad#chat(Component, String)}. */
	void chat(Component message, String id);

	/** See {@link Myriad#runCommand(String)}. */
	void runCommand(String line);

	/** See {@link Myriad#devSteps()}. */
	@ApiStatus.Experimental
	@ApiStatus.AvailableSince("2.0")
	default Optional<DevSteps> devSteps() {
		return Optional.empty();
	}
}
