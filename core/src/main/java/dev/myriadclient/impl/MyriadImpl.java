package dev.myriadclient.impl;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.MyriadApi;
import dev.myriadclient.api.addon.Addon;
import dev.myriadclient.api.addon.AddonSettings;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.config.ConfigManager;
import dev.myriadclient.api.control.Controls;
import dev.myriadclient.api.elytra.Elytra;
import dev.myriadclient.api.event.EventBus;
import dev.myriadclient.api.event.events.ScreenOpenEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.hook.Hooks;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.job.Jobs;
import dev.myriadclient.api.map.Maps;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.module.Module;
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
import dev.myriadclient.api.service.Tasks;
import dev.myriadclient.api.service.TickSpeed;
import dev.myriadclient.api.setting.Setting;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.share.ConfigShares;
import dev.myriadclient.api.ui.BarWidget;
import dev.myriadclient.api.ui.Desktop;
import dev.myriadclient.api.ui.Panel;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.layout.Layout;
import dev.myriadclient.api.util.PacketChannel;
import dev.myriadclient.api.util.Threads;
import dev.myriadclient.impl.addon.AddonLoader;
import dev.myriadclient.impl.command.CommandManager;
import dev.myriadclient.impl.config.ConfigManagerImpl;
import dev.myriadclient.impl.control.ControlManager;
import dev.myriadclient.impl.elytra.ElytraManager;
import dev.myriadclient.impl.elytra.LeavingGlide;
import dev.myriadclient.impl.event.MyriadEventBus;
import dev.myriadclient.impl.hook.HookManager;
import dev.myriadclient.impl.keybind.KeybindManager;
import dev.myriadclient.impl.map.MapsImpl;
import dev.myriadclient.impl.network.ActionTiming;
import dev.myriadclient.impl.network.BlockAckTracker;
import dev.myriadclient.impl.network.CorePacketChannel;
import dev.myriadclient.impl.render.CoreRendering;
import dev.myriadclient.impl.service.AntiCheatTracker;
import dev.myriadclient.impl.service.BreakManager;
import dev.myriadclient.impl.service.BuildManager;
import dev.myriadclient.impl.service.ContainerTracker;
import dev.myriadclient.impl.service.FriendsManager;
import dev.myriadclient.impl.service.InventoryManager;
import dev.myriadclient.impl.service.JobRunner;
import dev.myriadclient.impl.service.MenuSync;
import dev.myriadclient.impl.service.NotificationManager;
import dev.myriadclient.impl.service.PacketLimiter;
import dev.myriadclient.impl.service.PlacementManager;
import dev.myriadclient.impl.service.RotationManager;
import dev.myriadclient.impl.service.ServerStatsTracker;
import dev.myriadclient.impl.service.TaskScheduler;
import dev.myriadclient.impl.service.TickSpeedManager;
import dev.myriadclient.impl.share.ShareManager;
import dev.myriadclient.impl.ui.MyriadTitleScreen;
import dev.myriadclient.impl.ui.WindowManager;
import dev.myriadclient.impl.ui.panels.ConsoleLog;
import dev.myriadclient.impl.update.UpdateManager;
import dev.myriadclient.impl.wheel.WheelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Owns every Myriad service and drives startup. */
public final class MyriadImpl implements MyriadApi {
	public static final Logger LOG = LoggerFactory.getLogger("Myriad");
	private static MyriadImpl instance;

	private final Minecraft mc = Minecraft.getInstance();
	private final MyriadEventBus events = new MyriadEventBus();
	private final Registry<Tag> tags = new Registry<>("tag");
	/** The tags under their old name, for addons built before 0.7.0; kept in step with {@link #tags}. */
	private final Registry<Category> categories = new Registry<>("category");
	private final Registry<ModuleGroup> groups = new Registry<>("group");
	private final ModuleRegistry modules = new ModuleRegistry();
	private final Registry<Command> commands = new Registry<>("command");
	private final Registry<PanelType> panels = new Registry<>("panel");
	private final Registry<Layout> layouts = new Registry<>("layout");
	private final Registry<Theme> themes = new Registry<>("theme");
	private final Registry<BarWidget> barWidgets = new Registry<>("bar widget");
	private final Registry<KeyAction> keyActions = new Registry<>("key action");
	private final Registry<AddonSettings> addonSettings = new Registry<>("addon settings");
	private final ConfigManagerImpl config = new ConfigManagerImpl(this);
	/** Null in the test harness, which never starts. */
	private SafeMode safeMode;
	private final NotificationManager notifications = new NotificationManager();
	private final RotationManager rotations = new RotationManager();
	private final InventoryManager inventory = new InventoryManager();
	private final BlockAckTracker blockAcks = new BlockAckTracker();
	private final PacketLimiter packetLimiter = new PacketLimiter();
	private final TickSpeedManager tickSpeed = new TickSpeedManager();
	private final AntiCheatTracker antiCheat = new AntiCheatTracker();
	private final PlacementManager placement = new PlacementManager(blockAcks);
	private final BreakManager breaking = new BreakManager();
	private final ElytraManager elytra = new ElytraManager();
	private final CoreRendering render = new CoreRendering();
	private final CorePacketChannel packets = new CorePacketChannel(blockAcks);
	private final MapsImpl maps = new MapsImpl();
	private final ControlManager controls = new ControlManager();
	private final WheelManager wheels = new WheelManager();
	private final BuildManager building = new BuildManager(placement);
	private final FriendsManager friends = new FriendsManager(config.root());
	private final ServerStatsTracker serverStats = new ServerStatsTracker();
	private final MenuSync menuSync = new MenuSync();
	private final ContainerTracker containers = new ContainerTracker(menuSync);
	private final TaskScheduler tasks = new TaskScheduler();
	private final JobRunner jobs = new JobRunner();
	private final WindowManager windowManager = new WindowManager();
	private final CommandManager commandManager = new CommandManager(commands);
	private final KeybindManager keybindManager = new KeybindManager();
	private final ShareManager shares = new ShareManager();
	private final HookManager hooks = new HookManager(events);
	private final AddonLoader addonLoader = new AddonLoader(this);
	private final UpdateManager updates = new UpdateManager(config.root());

	private MyriadImpl() {
		tags.onAdd(t -> categories.register(Category.of(t), tags.ownerOf(t)));
		tags.onRemove(t -> categories.unregister(t.id()));
	}

	public static MyriadImpl get() {
		return instance;
	}

	/** Creates and installs Myriad, then loads every addon. */
	public static MyriadImpl bootstrap() {
		// Addon classes can load before the client exists (e.g. from an early mixin); make sure they see it.
		Minecraft client = Minecraft.getInstance();
		dev.myriadclient.api.module.Module.bindClient(client);
		Panel.bindClient(client);
		Command.bindClient(client);
		instance = new MyriadImpl();
		Myriad.install(instance);
		instance.start();
		return instance;
	}

	/**
	 * Myriad with its services wired as {@link #bootstrap()} wires them, but no addons, config, UI or updates: for the
	 * test harness, which supplies its own client.
	 */
	public static MyriadImpl forHarness() {
		Minecraft client = Minecraft.getInstance();
		dev.myriadclient.api.module.Module.bindClient(client);
		Panel.bindClient(client);
		Command.bindClient(client);
		instance = new MyriadImpl();
		Myriad.install(instance);
		instance.wireServices();
		return instance;
	}

	/** Forgets the harness's Myriad. */
	public static void uninstall() {
		instance = null;
		Myriad.install(null);
	}

	private void start() {
		long t0 = System.nanoTime();
		// First, so a crash while addons start counts.
		safeMode = new SafeMode(config.root().resolve(".running"), System.currentTimeMillis());
		wireServices();

		addonLoader.discover();
		addonLoader.registerTags();
		addonLoader.initialize();
		config.load();
		if (safeMode.active()) startSafe();
		addonLoader.postInitialize();
		updates.start(events);
		// Registries stay open: an addon that bridges another mod registers once that mod is ready, which may be later.

		LOG.info("Myriad ready: {} addons, {} modules, {} panels, {} commands in {} ms", addonLoader.addons().size(), modules.size(),
			panels.size(), commands.size(), (System.nanoTime() - t0) / 1_000_000);
	}

	/**
	 * Safe mode ({@link SafeMode}): every module off, saved so, and once the game is up a dialog listing which were on,
	 * offering to turn them back on.
	 */
	private void startSafe() {
		List<Module> were = SafeMode.turnOff(modules.values());
		String names = String.join(", ", were.stream().map(Module::name).toList());
		LOG.warn("The game crashed {} times in a row within a minute of starting: every module is off (on before: {})", safeMode.earlyCrashes(),
			names.isEmpty() ? "none" : names);
		if (were.isEmpty()) return;
		// Over the first title screen (opened before it, the desktop would be replaced by it, cancelling the dialog), or
		// the first world when quick play skips the title screen.
		boolean[] shown = {false};
		Runnable show = () -> {
			if (shown[0]) return;
			shown[0] = true;
			Minecraft.getInstance().schedule(() -> windowManager.confirm("Safe mode",
				"The game crashed twice in a row within a minute of starting, so every module is off. These were on: " + names + ".",
				"Turn them back on", () -> were.forEach(m -> m.setEnabled(true)), null));
		};
		events.listen(ScreenOpenEvent.class, e -> {
			if (e.screen() instanceof TitleScreen || e.screen() instanceof MyriadTitleScreen) show.run();
		});
		events.listen(WorldEvent.Join.class, e -> show.run());
	}

	/** Each client tick (not in the harness). */
	public void tickSafeMode() {
		if (safeMode != null) safeMode.tick(System.currentTimeMillis());
	}

	/** The game is stopping cleanly: this run wasn't a crash. */
	public void stoppedCleanly() {
		if (safeMode != null) safeMode.stopped();
	}

	/** The services on the event bus and behind their hooks, and the bus's links to the rest. */
	private void wireServices() {
		events.setOwnerResolver(addonLoader::ownerOf);
		events.setInGameCheck(() -> mc.level != null && mc.player != null);
		events.setRenderThreadCheck(Threads::isRender);
		Setting.setGlobalChangeHook(config::markDirty);
		SettingColor.setPaletteProvider(windowManager.theme()::roleColor);
		windowManager.setDirtyHook(config::markDirty);
		modules.onRemove(m -> m.setEnabled(false));
		// Modules registered after startup (a bridge whose other mod came up later) get their windows when the menu opens.
		modules.onAdd(m -> windowManager.modulesChanged());
		modules.onRemove(m -> windowManager.modulesChanged());

		events.subscribe(ActionTiming.get());
		events.subscribe(LeavingGlide.get());
		hooks.registerCore(Service.ROTATIONS, rotations);
		hooks.registerCore(Service.INVENTORY, inventory);
		hooks.registerCore(Service.PLACEMENT, placement);
		hooks.registerCore(Service.BREAKING, breaking);
		hooks.setOwnerResolver(addonLoader::ownerOf);
		events.subscribe(hooks);
		events.subscribe(rotations);
		events.subscribe(inventory);
		for (Object part : inventory.parts()) events.subscribe(part);
		events.subscribe(placement);
		for (Object part : placement.parts()) events.subscribe(part);
		events.subscribe(breaking);
		for (Object part : breaking.parts()) events.subscribe(part);
		events.subscribe(building);
		events.subscribe(blockAcks);
		events.subscribe(serverStats);
		events.subscribe(antiCheat);
		events.subscribe(menuSync);
		events.subscribe(containers);
		events.subscribe(tasks);
		events.subscribe(jobs);
		events.subscribe(keybindManager);
		events.subscribe(commandManager);
	}

	public List<Registry<?>> registries() {
		return List.of(tags, groups, modules, commands, panels, layouts, themes, barWidgets, keyActions, addonSettings, controls.presets(), hooks.registry(), antiCheat.profiles());
	}

	public ConfigManagerImpl configImpl() {
		return config;
	}

	public WindowManager windowManager() {
		return windowManager;
	}

	public CommandManager commandManager() {
		return commandManager;
	}

	public UpdateManager updates() {
		return updates;
	}

	@Override
	public MyriadEventBus events() {
		return events;
	}

	@Override
	public List<Addon> addons() {
		return addonLoader.addons();
	}

	public AddonLoader addonLoader() {
		return addonLoader;
	}

	@Override
	public Registry<Category> categories() {
		return categories;
	}

	@Override
	public Registry<Tag> tags() {
		return tags;
	}

	@Override
	public Registry<ModuleGroup> groups() {
		return groups;
	}

	@Override
	public ModuleRegistry modules() {
		return modules;
	}

	@Override
	public Registry<Command> commands() {
		return commands;
	}

	@Override
	public Registry<PanelType> panels() {
		return panels;
	}

	@Override
	public Registry<Layout> layouts() {
		return layouts;
	}

	@Override
	public Registry<Theme> themes() {
		return themes;
	}

	@Override
	public Registry<BarWidget> barWidgets() {
		return barWidgets;
	}

	@Override
	public Registry<KeyAction> keyActions() {
		return keyActions;
	}

	@Override
	public ConfigManager config() {
		return config;
	}

	@Override
	public Notifications notifications() {
		return notifications;
	}

	@Override
	public Rotations rotations() {
		return hooks.router(Service.ROTATIONS);
	}

	@Override
	public Inventory inventory() {
		return hooks.router(Service.INVENTORY);
	}

	/** Core's own, whatever replacement is in use: for core's internals. */
	public InventoryManager inventoryManager() {
		return inventory;
	}

	@Override
	public Placement placement() {
		return hooks.router(Service.PLACEMENT);
	}

	@Override
	public Friends friends() {
		return friends;
	}

	@Override
	public ServerStats server() {
		return serverStats;
	}

	@Override
	public Containers containers() {
		return containers;
	}

	@Override
	public Elytra elytra() {
		return elytra;
	}

	@Override
	public Rendering render() {
		return render;
	}

	@Override
	public PacketChannel packets() {
		return packets;
	}

	@Override
	public Maps maps() {
		return maps;
	}

	@Override
	public Controls controls() {
		return controls;
	}

	public ControlManager controlManager() {
		return controls;
	}

	public WheelManager wheelManager() {
		return wheels;
	}

	@Override
	@SuppressWarnings("deprecation")
	public Tasks tasks() {
		return tasks;
	}

	@Override
	public Jobs jobs() {
		return jobs.engine();
	}

	/** The engine's tie to the game, with what the Jobs window lists beyond the running jobs. */
	public JobRunner jobRunner() {
		return jobs;
	}

	@Override
	public Breaking breaking() {
		return hooks.router(Service.BREAKING);
	}

	@Override
	public Hooks hooks() {
		return hooks;
	}

	public HookManager hookManager() {
		return hooks;
	}

	@Override
	public ConfigShares shares() {
		return shares;
	}

	public ShareManager shareManager() {
		return shares;
	}

	@Override
	public Building building() {
		return building;
	}

	@Override
	public PacketLimits limits() {
		return packetLimiter;
	}

	@Override
	public TickSpeed tickSpeed() {
		return tickSpeed;
	}

	@Override
	public AntiCheat antiCheat() {
		return antiCheat;
	}

	public ServerStatsTracker serverStatsTracker() {
		return serverStats;
	}

	public Registry<AddonSettings> addonSettings() {
		return addonSettings;
	}

	public BlockAckTracker blockAcks() {
		return blockAcks;
	}

	public PacketLimiter packetLimiter() {
		return packetLimiter;
	}

	public RotationManager rotationManager() {
		return rotations;
	}

	public TickSpeedManager tickSpeedManager() {
		return tickSpeed;
	}

	public ContainerTracker containerTracker() {
		return containers;
	}

	public MenuSync menuSync() {
		return menuSync;
	}

	@Override
	public Desktop ui() {
		return windowManager;
	}

	@Override
	public void chat(Component message) {
		ConsoleLog.add(message.getString());
		if (!mc.isSameThread()) {
			mc.execute(() -> sendChat(message));
			return;
		}
		sendChat(message);
	}

	@Override
	public void runCommand(String line) {
		String prefix = config.commandPrefix();
		commandManager.execute(line.startsWith(prefix) ? line.substring(prefix.length()) : line);
	}

	@Override
	public void chat(Component message, String id) {
		ConsoleLog.add(message.getString());
		if (!mc.isSameThread()) {
			mc.execute(() -> sendChat(message, id));
			return;
		}
		sendChat(message, id);
	}

	private void sendChat(Component message) {
		sendChat(message, null);
	}

	private void sendChat(Component message, String id) {
		if (mc.gui == null || mc.player == null) return;
		int accent = windowManager.theme().accent.get().color() & 0xFFFFFF;
		MutableComponent line = Component.literal("[Myriad] ").withStyle(s -> s.withColor(TextColor.fromRgb(accent))).append(message);
		if (id == null) mc.gui.hud.getChat().addClientSystemMessage(line);
		else ChatLines.add(line, id);
	}
}
