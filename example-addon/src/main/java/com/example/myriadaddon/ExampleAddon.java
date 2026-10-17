package com.example.myriadaddon;

import com.example.myriadaddon.bar.TpsBarWidget;
import com.example.myriadaddon.commands.WaypointCommand;
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
import dev.myriadclient.api.control.ControlPreset;
import dev.myriadclient.api.addon.MyriadAddon;
import dev.myriadclient.api.module.Category;
import dev.myriadclient.api.render.HighlightStyle;
import dev.myriadclient.api.setting.SettingColor;
import dev.myriadclient.api.ui.PanelType;
import dev.myriadclient.api.ui.Theme;
import dev.myriadclient.api.ui.widget.HBox;
import dev.myriadclient.api.ui.widget.Slider;
import dev.myriadclient.api.util.Keybind;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The example addon: a small but complete feature (waypoints) plus a few standalone modules, written the way a real
 * addon should be. Read this class top to bottom, then follow the links. The README next to it has a file-by-file map.
 *
 * <p>Conventions it follows:
 * <ul>
 *   <li>Shared state lives in plain objects created here and passed to whatever needs it (constructor injection),
 *       not in static fields.</li>
 *   <li>Modules go in Myriad's shared categories ({@code Categories.WORLD} etc.) so they sit next to other addons'
 *       modules; a custom category is only for things that fit nowhere else.</li>
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

	private Category showcase;
	private WaypointStore waypoints;

	/** Runs for every addon before any {@link #initialize}, so other addons could use this category too. */
	@Override
	public void registerCategories(AddonContext ctx) {
		showcase = ctx.registerCategory("Showcase", "", SettingColor.role(SettingColor.Mode.MAGENTA));
	}

	@Override
	public void initialize(AddonContext ctx) {
		// 1. State shared by several features. AddonStorage is this addon's own folder under .minecraft/myriad/addons/.
		waypoints = new WaypointStore(ctx.storage());
		waypoints.load();
		SessionStats stats = new SessionStats();

		// 2. Modules, in menu order. Each gets what it needs through its constructor.
		ctx.registerModules(
			new Waypoints(waypoints),
			new BlockSearch(),
			new AutoTool(),
			new Tunnel(),
			new Trajectories(),
			new HoleEsp(),
			new LogoEsp(),
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
			Myriad.ui().openPanel(waypointsWindow.id());
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
		// given here while it's on. Players find it in their Control Panels and can change it or delete it.
		ctx.registerControlPanel(ControlPreset.builder("Digging")
			.module("myriad-example:tunnel").setting("myriad-example:tunnel", "width", "3").setting("myriad-example:tunnel", "height", "3")
			.module("myriad-example:auto_tool")
			.module("myriad-example:hole_esp")
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
	}

	/** Every addon has initialised and config is loaded: the place to look for optional integrations. */
	@Override
	public void postInitialize(AddonContext ctx) {
		if (FabricLoader.getInstance().isModLoaded("myriad-essentials")) {
			ctx.logger().info("Myriad Essentials found: Auto Tool will defer to its Packet Mine while that's on");
		}
	}
}
