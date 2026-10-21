package com.example.myriadaddon;

import com.example.myriadaddon.bar.TpsBarWidget;
import com.example.myriadaddon.commands.WaypointCommand;
import com.example.myriadaddon.hooks.CountingPlacement;
import com.example.myriadaddon.hud.NearestWaypointHud;
import com.example.myriadaddon.hud.SessionStatsHud;
import com.example.myriadaddon.modules.AutoTool;
import com.example.myriadaddon.modules.BlockSearch;
import com.example.myriadaddon.modules.ChatTimestamps;
import com.example.myriadaddon.modules.HoleEsp;
import com.example.myriadaddon.modules.LogoEsp;
import com.example.myriadaddon.modules.SettingsShowcase;
import com.example.myriadaddon.modules.Trajectories;
import com.example.myriadaddon.modules.Tunnel;
import com.example.myriadaddon.modules.Waypoints;
import com.example.myriadaddon.panels.WaypointsPanel;
import com.example.myriadaddon.settings.RangeSetting;
import com.example.myriadaddon.stats.SessionStats;
import com.example.myriadaddon.waypoints.WaypointStore;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.addon.AddonContext;
import dev.myriadclient.api.addon.MyriadAddon;
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.event.events.HighlightEvent;
import dev.myriadclient.api.hook.Match;
import dev.myriadclient.api.hook.Service;
import dev.myriadclient.api.module.ModuleGroup;
import dev.myriadclient.api.module.Tag;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.Icons;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Slider;
import dev.myriadclient.api.util.Keybind;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

/**
 * The example addon: a small but complete feature (waypoints) plus a few standalone modules, written the way a real
 * addon should be. Read this class top to bottom, then follow the links. The README next to it has a file-by-file map.
 *
 * <p>Conventions it follows:
 * <ul>
 *   <li>Shared state lives in plain objects created here and passed to whatever needs it (constructor injection),
 *       not in static fields.</li>
 *   <li>Modules are tagged with Myriad's shared tags ({@code Tags.WORLD} etc.) so {@code #world} finds them beside
 *       other addons'; a custom tag is only for things none of them fits.</li>
 *   <li>Modules are grouped the way you think best: each group is one window in the menu. A small addon can skip
 *       groups and get one window named after it.</li>
 *   <li>Colours default to theme roles so everything matches the player's theme.</li>
 *   <li>Mixins stay one-liners that ask the module ({@code Modules.active(...)}) what to do.</li>
 *   <li>Nothing here depends on Myriad Essentials: players can remove it. Talk to other addons by id instead.</li>
 * </ul>
 */
public final class ExampleAddon implements MyriadAddon {
	/**
	 * A highlight fill of this addon's own: the shader at {@code assets/myriad-example/shaders/fill/logo_grid.fsh}.
	 * Created once, here, while the addon class loads (before the game compiles its shaders); any module can use it.
	 */
	public static final HighlightStyle.Fill LOGO_FILL = HighlightStyle.Fill.custom(Identifier.fromNamespaceAndPath("myriad-example", "fill/logo_grid"));

	private Tag showcase;
	private WaypointStore waypoints;

	/** Runs for every addon before any {@link #initialize}, so other addons could use this tag too. */
	@Override
	public void registerTags(AddonContext ctx) {
		showcase = ctx.registerTag("Showcase", "", SettingColor.role(SettingColor.Mode.MAGENTA));
	}

	@Override
	public void initialize(AddonContext ctx) {
		// 1. State shared by several features. AddonStorage is this addon's own folder under .minecraft/myriad/addons/.
		waypoints = new WaypointStore(ctx.storage());
		waypoints.load();
		SessionStats stats = new SessionStats();

		// 2. Modules, in menu order, in two groups: each is a window of its own in the menu. Each module gets what it
		// needs through its constructor.
		Tunnel tunnel = new Tunnel();
		AutoTool autoTool = new AutoTool();
		HoleEsp holeEsp = new HoleEsp();
		ModuleGroup world = ctx.registerGroup("World Tools", Icons.WRENCH);
		ModuleGroup visuals = ctx.registerGroup("Visuals", Icons.EYE);
		ctx.registerModules(world,
			new Waypoints(waypoints),
			new BlockSearch(),
			autoTool,
			tunnel
		);
		ctx.registerModules(visuals,
			new Trajectories(),
			holeEsp,
			new LogoEsp()
		);
		// Modules registered without a group go in the addon's own window, named after it.
		ctx.registerModules(
			new ChatTimestamps(),
			new SettingsShowcase(showcase)
		);

		// 3. A chat command with sub-commands and its own argument type.
		ctx.registerCommand(new WaypointCommand(waypoints));

		// 4. A window (found in the launcher) and two HUD elements (added from the HUD workspace).
		PanelType waypointsWindow = ctx.registerPanel("Waypoints", "", () -> new WaypointsPanel(waypoints));
		ctx.registerHud("Nearest Waypoint", "", () -> new NearestWaypointHud(waypoints));
		ctx.registerHud("Session Stats", "", () -> new SessionStatsHud(stats));

		// 5. Global key actions: work in game, rebindable in the Keybinds panel (mod+K).
		ctx.registerKeyAction("Add Waypoint", Keybind.key(GLFW.GLFW_KEY_B, GLFW.GLFW_MOD_ALT), () -> {
			var player = Minecraft.getInstance().player;
			if (player == null) return;
			String name = waypoints.nextName("Waypoint");
			if (waypoints.put(name, player.blockPosition())) Myriad.notifications().success("Waypoints", "Added " + name);
		});
		ctx.registerKeyAction("Open Waypoints", Keybind.NONE, () -> {
			Myriad.ui().open();
			Myriad.ui().openPanel(waypointsWindow);
		});

		// 6. Something on the menu's top bar.
		ctx.registerBarWidget(new TpsBarWidget(ctx.id("tps")));

		// 7. A theme preset, listed with the built-in ones in the Theme panel. Unset values come from Myriad's default.
		ctx.registerTheme(new Theme(ctx.id("sunset"), "Example Sunset", t -> {
			t.accent.set(SettingColor.of(0xFFF5A97F));
			t.secondary.set(SettingColor.of(0xFFF5C2E7));
			t.windowBackground.set(SettingColor.of(0xD8241F31));
		}));

		// 8. A ready-made control panel: one bind (or switch) turns these modules on together, each with the settings
		// given here while it's on. Players find it in their Control Panels and can change it or delete it. Your own
		// modules' settings are typed; another addon's module goes in by id, its settings as .set takes them.
		ctx.registerControlPanel(ControlPreset.builder("Digging")
			.module(tunnel, m -> m.set(tunnel.width, 3).set(tunnel.height, 3))
			.module(autoTool)
			.module(holeEsp)
			.build());

		// 9. A custom setting type needs an editor widget; every settings view then uses it (see SettingsShowcase).
		ctx.settingWidgets().register(RangeSetting.class, s -> {
			HBox row = new HBox(3);
			row.add(new Slider(() -> s.get().min(), v -> s.set(new RangeSetting.Range(v, s.get().max())), s.lower(), s.upper(), 1));
			row.add(new Slider(() -> s.get().max(), v -> s.set(new RangeSetting.Range(s.get().min(), v)), s.lower(), s.upper(), 1));
			return row;
		}, false);

		// 10. An always-on listener that isn't a module. Its @Subscribe methods run until the game closes.
		ctx.events().subscribe(stats);

		// 11. Hooks: changing how Myriad itself works. A hook is one decision others can make (here: how each entity is
		// highlighted, after every module had its say); a replacement takes over a whole service (rotations, inventory,
		// placement, breaking) for everyone. Both show in the Hooks window, where players can turn them off, and a
		// Match narrows them to a module, an anti-cheat or a server.
		ctx.hook(HighlightEvent.ENTITY_HIGHLIGHT).named("Friends in green").handle(call -> {
			HighlightEvent.EntityHighlight h = call.proceed();
			boolean friend = h.style() != null && h.entity() instanceof Player p && Myriad.friends().isFriend(p);
			// Keep the alpha: it fades the highlight in and out.
			int green = SettingColor.role(SettingColor.Mode.GREEN).argb() & 0xFFFFFF;
			return friend ? new HighlightEvent.EntityHighlight(h.entity(), h.style(), h.color() & 0xFF000000 | green) : h;
		});
		// Scoped to this addon: Tunnel and the other example modules place through it, while Essentials and every other
		// addon keep core's placement. Leave out .when(...) to replace it for everyone.
		ctx.replace(Service.PLACEMENT)
			.named("Counting placement")
			.when(Match.addon(ctx.namespace()))
			.with(CountingPlacement::new);
	}

	/** Every addon has initialised and config is loaded: the place to look for optional integrations. */
	@Override
	public void postInitialize(AddonContext ctx) {
		if (FabricLoader.getInstance().isModLoaded("myriad-essentials")) {
			ctx.logger().info("Myriad Essentials found: Auto Tool will defer to its Packet Mine while that's on");
		}
	}
}
