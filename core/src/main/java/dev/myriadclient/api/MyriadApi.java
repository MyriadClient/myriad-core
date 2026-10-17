package dev.myriadclient.api;

import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.config.ConfigManager;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.ModuleRegistry;
import dev.myriadclient.api.registry.Registry;
import dev.myriadclient.api.service.AntiCheat;
import dev.myriadclient.api.service.Breaking;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.service.Containers;
import dev.myriadclient.api.service.Friends;
import dev.myriadclient.api.service.Inventory;
import dev.myriadclient.api.service.KeyAction;
import dev.myriadclient.api.service.Notifications;
import dev.myriadclient.api.service.Placement;
import dev.myriadclient.api.service.Rotations;
import dev.myriadclient.api.service.ServerStats;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.service.Tasks;
import dev.myriadclient.api.service.TickSpeed;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.layout.Layout;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/** The services behind the {@link Myriad} facade. */
@ApiStatus.NonExtendable
public interface MyriadApi {
	EventBus events();

	List<Addon> addons();

	Registry<Category> categories();

	ModuleRegistry modules();

	Registry<Command> commands();

	Registry<PanelType> panels();

	Registry<Layout> layouts();

	Registry<Theme> themes();

	Registry<BarWidget> barWidgets();

	Registry<KeyAction> keyActions();

	Registry<AddonSettings> addonSettings();

	ConfigManager config();

	Notifications notifications();

	Rotations rotations();

	Inventory inventory();

	Placement placement();

	Breaking breaking();

	Building building();

	Friends friends();

	ServerStats server();

	Containers containers();

	Elytra elytra();

	Tasks tasks();

	PacketLimits limits();

	TickSpeed tickSpeed();

	AntiCheat antiCheat();

	Desktop ui();

	void chat(Component message);

	void chat(Component message, String id);

	void runCommand(String line);
}
