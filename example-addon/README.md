# Myriad example addon

A small but complete addon, written as a reference for the Myriad API. It's meant to be read: every file explains
the pattern it shows. To start your own addon, use the [myriad-addon-template](https://github.com/MyriadClient/myriad-addon-template) repository instead, and come back
here to see how things are done.

Start at [`ExampleAddon.java`](src/main/java/com/example/myriadaddon/ExampleAddon.java), which registers everything in
numbered steps.

## What it adds

| Feature | Where to find it in game |
|---|---|
| **Waypoints** module: boxes, beams and labels for saved positions; a "Death" waypoint when you die | World category |
| `.waypoint` / `.wp` command: `list`, `add <name> [x y z]`, `remove`, `hide`, `show`, `clear` | chat or the console (`mod+Return`) |
| **Waypoints** window: add, hide and delete waypoints | launcher (`mod+Space`) |
| **Nearest Waypoint** and **Session Stats** HUD elements | HUD workspace → HUD window |
| **Add Waypoint** (`Alt+B`) and **Open Waypoints** key actions | Keybinds panel (`mod+K`) |
| **Auto Tool** module: best tool while mining (brought in from the inventory when you stand still), defers to Essentials' Packet Mine | Player category |
| **Tunnel** module: digs a tunnel the way you face through the building planner | World category |
| **Block Search** module: highlights the blocks you pick in the chunks around you | World category |
| **Trajectories** module: where the item in your hand and every projectile in the air will land | Render category |
| **Hole ESP** module: holes you can stand in safely, green for bedrock | Combat category |
| **Chat Timestamps** module, driven by a mixin | Misc category |
| **Settings Showcase** module: every setting type and a custom one | its own "Showcase" category |
| Server TPS on the top bar, and an "Example Sunset" theme | bar / Theme panel |

## File map

| File | Shows |
|---|---|
| `ExampleAddon.java` | the entrypoint: categories, then everything else; `postInitialize` for optional integrations |
| `waypoints/WaypointStore.java` | addon state shared by several features, saved with `AddonStorage` |
| `waypoints/Waypoint.java` | an immutable record with its own JSON form |
| `modules/Waypoints.java` | setting groups, theme-role colours, `Render3DEvent` boxes and lines, `Render2DEvent` world labels, reacting to vanilla with `ScreenOpenEvent` |
| `modules/AutoTool.java` | the `Myriad.inventory()` service (`select`, `pullToHotbar`), `Mining` helpers, soft integration with another addon by id |
| `modules/BlockSearch.java` | finding blocks cheaply: `ChunkCache` (once per chunk, again on changes), `BlockScan` palette skipping, cached meshes instead of a render handler |
| `modules/Trajectories.java` | `Trajectory.launch`/`simulate` for the held item and projectiles in flight; `TickCached` so paths cost once per tick, not per frame |
| `modules/HoleEsp.java` | `Holes.scan` through a `ChunkCache` with `neighbours()` and no mesher; `Holes.holeOf` for the module list |
| `modules/Tunnel.java` | the planner: a `Blueprint` of `Target.air()` handed to `Myriad.building()`, drawing `Build.steps()`, `Building.Options.forServer()` instead of a "Strict" option |
| `modules/ChatTimestamps.java` + `mixin/ChatHudMixin.java` | the standard mixin-driven module: a static hook using `Modules.active(...)` |
| `modules/SettingsShowcase.java` | every setting type, `visible`, `onChanged`, `sliderRange`, keybinds, action buttons |
| `settings/RangeSetting.java` | a custom setting type (its widget is registered in `ExampleAddon`) |
| `commands/WaypointCommand.java` | Brigadier sub-commands, `Arguments.blockPos()` (with `~`), and clickable chat from `Texts` |
| `commands/WaypointArgumentType.java` | a custom argument type with suggestions and error messages |
| `panels/WaypointsPanel.java` | a `WidgetPanel` window: text fields, toggles, buttons, collapsible sections, rebuilding when data changes |
| `hud/SessionStatsHud.java`, `hud/NearestWaypointHud.java` | `TextHudPanel` HUD elements with the standard style options and a title-screen preview |
| `stats/SessionStats.java` | an always-on `@Subscribe` listener that isn't a module |
| `bar/TpsBarWidget.java` | a top-bar widget using `Myriad.server()` and the theme palette |

Run it from the repository root with `./gradlew :example-addon:runClient -PopenDesktop`. That starts the game with
Myriad, Essentials and this addon.
