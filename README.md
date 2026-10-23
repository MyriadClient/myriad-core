<p align="center"><img src="core/src/main/resources/assets/myriad/icon.png" width="96" alt="Myriad"></p>
<h1 align="center">Myriad</h1>
<p align="center">An addon-first utility client for Minecraft 26.2, with a menu that works like a tiling window manager.</p>
<p align="center"><a href="https://myriadclient.dev/"><b>myriadclient.dev</b></a></p>
<p align="center">
  <a href="https://github.com/MyriadClient/myriad-core/releases"><img src="https://img.shields.io/github/v/release/MyriadClient/myriad-core?label=release" alt="Release"></a>
  <a href="https://github.com/MyriadClient/myriad-core/actions/workflows/build.yml"><img src="https://github.com/MyriadClient/myriad-core/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.2-5c9e4f" alt="Minecraft 26.2">
  <img src="https://img.shields.io/badge/Fabric-Mojang%20names-8e6fd6" alt="Fabric, Mojang names">
  <img src="https://img.shields.io/badge/license-CC0-3b7dd8" alt="CC0">
</p>

![The Myriad menu: Essentials' group windows tiled by the Dwindle layout](docs/screenshots/menu-dwindle.png)

Myriad is built like a desktop, not a click-GUI. The menu is a window manager modelled on
[Hyprland](https://hyprland.org): workspaces, tiled or floating windows, layouts, a launcher, and everything reachable
from the keyboard. The core ships **no modules at all**. Every feature, including the stock set, is an addon written
against the public API, so the client is whatever addons you put next to it.

- **Workspaces and layouts.** Nine workspaces, each tiling its windows with Dwindle or Columns. Lay out a
  combat workspace and a base workspace, merge windows, float the ones you want on top.
- **Launcher.** `Alt+Space` fuzzy-searches modules, panels, themes, profiles and workspaces. Enter toggles, Shift+Enter
  opens settings.
- **Themes.** Gaps, borders, rounding, blur, shadows, animations, fonts and every colour. Presets, your own, and a
  palette that modules follow, so ESP, tracers and the HUD match whatever you pick.
- **Made for 2b2t.** The stock modules pass Grim by default; the services every addon builds on (placing, breaking,
  rotating, inventory) are timed and ordered for it, and switch to faster behaviour on servers that don't check.
- **Cheap by design.** A module costs nothing until it does something, and the built-in profiler shows what each one
  costs.
- **Addon-first.** One API, a template, a reference addon, and a compatibility promise, so an addon built today keeps
  working on the next release.

## Install

1. [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2, and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `myriad-<v>.jar` from the [latest release](https://github.com/MyriadClient/myriad-core/releases/latest) in `mods/`,
   with `myriad-essentials-<v>.jar` from [its releases](https://github.com/MyriadClient/myriad-essentials/releases/latest)
   and any other addons.
3. In game, press **Alt+1** to open the menu (on workspace 1), or **Alt+Space** for the launcher.

### Updates

Myriad checks GitHub for new releases of itself and of each addon when the game starts. When there is one, a pill
with the new version shows on the addon's card in the **Addons** window (and a count in the bar); open the card and
click **Update**, or **Update all**, or use `.update`. The jar is downloaded and checked (the right mod and version,
and that it supports your Minecraft and Myriad), then swapped into `mods/` when you quit, so the next launch runs it.
The jar it replaced is kept in `.minecraft/myriad/updates/old/`. Turn the check off with the toggle at the top of
the Addons window.

## Official addons

| Addon | What it adds | Where |
|---|---|---|
| **Myriad Essentials** | The stock modules: Offhand, Kill Aura, Elytra Fly, Packet Mine, Scaffold, ESP, Nametags and the rest, with the HUD elements. Built like any other addon, on the public API, versioned on its own; remove it and Myriad boots with an empty module list. | [MyriadClient/myriad-essentials](https://github.com/MyriadClient/myriad-essentials) |
| **Myriad Boze** | Every module of the [Boze](https://boze.dev) client in Myriad's menu, with Boze installed alongside: Boze's modules appear in windows named after Boze's own categories, with their settings, keybinds and on/off state in sync both ways, and the two friend lists kept the same. Boze stays the owner of its config and keys; Myriad is a second front end. | [MyriadClient/myriad-boze](https://github.com/MyriadClient/myriad-boze) |
| **Myriad Omarchy** | A theme that follows your [Omarchy](https://omarchy.org) system theme: switch themes on the desktop and the menu follows. | [MyriadClient/myriad-omarchy](https://github.com/MyriadClient/myriad-omarchy) |

For writing your own: [myriad-addon-template](https://github.com/MyriadClient/myriad-addon-template) to start from and
[`example-addon/`](example-addon) to read. See [Writing an addon](#writing-an-addon).

## A look around

| | |
|---|---|
| ![A module's settings in a window of its own](docs/screenshots/settings.png) | ![The launcher](docs/screenshots/launcher.png) |
| A module's settings, inline under the module or in a window of their own, which Dwindle tiles in beside it. | The launcher: type a few letters, Enter toggles. |
| ![In game: ESP, tracers, nametags and the HUD](docs/screenshots/hud.png) | ![Neon Blueprint, a user theme](docs/screenshots/menu-neon-blueprint.png) |
| In game: ESP, tracers and nametags from Essentials, the HUD drawn by the same window manager. | A theme of your own: square corners, wide gaps, a thick gradient border, a mono font, dimmed inactive windows. It's one JSON file ([`docs/themes/neon_blueprint.json`](docs/themes/neon_blueprint.json), drop it in `.minecraft/myriad/themes/`). |
| ![Tokyo Night](docs/screenshots/menu-tokyo-night.png) | ![Gruvbox](docs/screenshots/menu-gruvbox.png) |
| The Theme panel, on the Tokyo Night preset. Every colour, gap and curve is a setting. | Gruvbox. Editing a preset saves your changes on top of it. |
| ![The Columns layout](docs/screenshots/menu-columns.png) | |
| The Columns layout, one window per column; `mod+L` cycles layouts per workspace. | |

## Building and running

Requires JDK 25+.

```bash
./gradlew build                          # core/ and example-addon/ → */build/libs/*.jar
./gradlew :core:test                     # unit tests: event bus, settings, config, layout, the API surface
./gradlew :gradle-plugin:build           # the addon Gradle plugin (dev.myriadclient.addon)
./gradlew :core:runClient                # core only (no modules)
./gradlew :example-addon:runClient       # core + the released Essentials + the example addon
./gradlew :example-addon:runClient -PessentialsDir=../myriad-essentials   # …with the Essentials jar built in that checkout
./gradlew :example-addon:runClient -PopenDesktop   # …and open the menu on the title screen
./gradlew :example-addon:runClient -PquickPlay="New World"   # …or load straight into a singleplayer world
./gradlew :example-addon:runClient -PscreenshotEvery=10   # …saving a screenshot every 10 s (run/screenshots)
./gradlew :example-addon:runClient -PquickPlayServer=localhost:25565 -Pusername=GrimTester   # …or join a server
./gradlew publishToMavenLocal            # dev.myriadclient:myriad in ~/.m2, to build an addon against unreleased changes
```

```
core/           mod id "myriad"             the platform: API, event bus, config, commands, renderer, menu
example-addon/  mod id "myriad-example"     the reference addon: a complete feature set, written to be read (not released)
gradle-plugin/  dev.myriadclient.addon      the Gradle plugin addons build with, released with core
```

The stock modules live in [myriad-essentials](https://github.com/MyriadClient/myriad-essentials), with the Grim test
server and suite that check them against 2b2t's anti-cheat. The dev client takes script steps from
`run/myriad-dev-inbox` (commands, movement, the menu, screenshots); the README's pictures are taken that way.

Releases are tagged: `git tag v0.1.0 && git push origin v0.1.0` builds, tests, attaches the core jar to a GitHub
release and publishes it, with the addon Gradle plugin, to the maven at `https://maven.myriadclient.dev/releases`. Essentials and the other addons
release from their own repositories, to the same maven. Every build checks `dev.myriadclient.api` against the last
release (`api_baseline` in `gradle.properties`, moved once a release is published) and fails on a change that would
break addons built against it; the report is in `core/build/reports/api`.

## The menu

In game or on the title screen, **mod+1–9** opens the Myriad menu on that workspace (the mod key is Alt by default,
changeable in Theme → Preferences), and pressing the number of the workspace that's showing closes it again; mod+S
opens it on the HUD workspace. **mod+Space** opens the launcher on its own over the game: Enter toggles a module or
applies a theme without opening the menu, and what needs the menu (a panel, settings) opens it. The title screen
shows these keys under Myriad's logo, and they're also in Minecraft's Controls → Key Binds, under Myriad. For a
single key, like Right Shift, bind **Open Menu** there or under Keybindings → Global.

- **Workspaces 1–9** each tile their windows with a layout. **Dwindle** is the default, as in Hyprland: each new
  window splits the focused one, so a workspace grows into a grid. **Columns** (one window per column, side
  by side, with each module's settings unfolding inline) is also built in, and addons can register
  more layouts. Columns never get too narrow to read: when they don't all fit, the row scrolls sideways
  (Shift + mouse wheel, or the wheel over a gap), and focusing a window scrolls it into view.
- **Group windows** hold one addon's modules, grouped the way its author thinks best: Essentials has *Combat*,
  *Travel*, *Building & Mining*, *Inventory* and *Visuals*; an addon that declares no groups has one window named
  after it. Each group is a window of its own, so lay them out however suits you, say a combat workspace and a base
  workspace, with any addon's windows side by side. When you install an addon, its windows open together on the first
  workspace with room for them; an addon that doesn't fit gets a workspace of its own (continuing onto the next empty
  one if it has more windows than fit), and a notification says where. A workspace holds as many windows
  as fit side by side at a readable width, so a fresh install with many addons comes up as one tidy workspace per
  addon rather than one crowded one. Only windows the desktop hasn't seen are placed: what you've arranged stays put. A window you close stays closed (the launcher reopens it).
  **Merge** windows by dragging one by its title bar onto another's title bar: each group becomes a section, and the
  icon on a section's header moves it back out to its own window.
- Windows can be **tiled**, **floating** or **fullscreen**. Drag the gaps between tiles to resize them.
- The bar shows workspaces 1–4, and 5–9 only while they have windows (or you're on one). The **HUD workspace** (the icon after
  the numbers) holds the in-game HUD. Its windows are drawn in game without
  window decorations. Drag them to move them; they snap to edges and to the centre, and anchor to the nearest screen
  third. Right-click one for its settings. The **HUD** window there lists every element like a group window lists
  modules: click one to add or remove it, unfold it for its options. Delete removes the hovered element, and the
  launcher can also add and remove them.
- The **launcher** (`Alt+Space`) fuzzy-searches modules (Enter toggles, Shift+Enter or right-click opens settings),
  control panels, macros, wheels, panels, group windows, themes, profiles and workspaces. Narrow it with `@addon`
  (part of an addon's id or name: `@ess`, `@stash`) and `#kind`, `#tag` or `#group` (`#panel`, `#control`, `#macro`,
  `#theme`, `#render`, `#combat`, `#travel`…), alone or with text: `@ess #render` lists Essentials' render modules,
  and `@ess #panel` every window it has (group windows and HUD elements; `#group` and `#hud` narrow it). A module's
  **tags** say what it's about (Combat, Movement, Render, Player, World, Misc, shared by every addon, with their
  colours); a module can have several. Whatever you type can also become a **New macro**, **New control panel** or
  **New wheel** of that name (the last three results); "new wheel travel" makes just the wheel.
- **Control panels** switch a set of modules together: one bind or switch turns them all on, each with settings the
  panel saves for it (open its window, unfold a module, set it up, press Save). While it's on, those lie over the
  module's own settings (tagged with the panel's name; tuning one tunes the panel's), and turning it off turns off only
  the modules it turned on and uncovers their own settings. Make them in the
  launcher or the Control Panels window; `.control on|off|toggle <name>`. Addons can ship ready-made ones.
- **Macros** are modules you make (launcher → New macro, or `.macro new <name>`) whose Commands run when pressed: one
  per line, a Myriad command (`.toggle esp`), a server command (`/home`), `wait <ticks>`, or chat. Run mode runs them
  once per press; Toggle runs Commands when turned on and Off Commands when turned off. Being modules, they take a
  bind, hold, the launcher and control panels like any other. `.macro [run|new|delete <name>]`.
- **Wheels**: hold a wheel's bind in game and a ring of what you put on it appears, in your theme; let go over one to
  run it (a click works too, Escape or right-click closes). A wheel takes modules and macros (toggled), control panels
  (switched), addons' actions, panels (opened in the menu) and command lines (`.toggle esp`, `/home`). Make as many as
  you like, each with its own bind, in the Wheels window or the launcher (New wheel); search to add, arrows to order
  them clockwise from the top.
- **Sharing**: the **Share** window (or `.share`) turns modules, menu windows, control panels and the theme into a
  code like `myriad_JS3k4d`. Anyone can look at it on [myriadclient.dev/config/…](https://myriadclient.dev/docs/use/sharing),
  or take it in: as their own settings, or as a control panel to try without losing theirs. Settings addons mark
  private (webhooks, coordinates, macro commands) never leave your computer.
- **Themes**: a theme holds the whole look: gaps, borders (gradient, optional rotation), rounding, blur, shadows,
  inactive dimming and opacity, animation curves and speeds (Hyprland-style), the bar, fonts, and every colour. Pick
  one in the Theme panel and edit it there; edits save into the active theme as you make them.
  - **Presets**: Myriad (the logo's mint on near-black), Tokyo Night, Gruvbox and Catppuccin Mocha. Editing a preset saves your changes on top of it (it shows
    *Edited*), and **Reset** brings the original back.
  - **Your themes**: **New** or **Duplicate** makes a theme you can rename and give an author. Each one is a file in
    `.minecraft/myriad/themes/`. To share it, send the `.json`; the other person drops it in that folder and presses
    **Reload**.
  - **Theme roles**: colours come from a palette (accent, secondary, red, green, yellow, blue, magenta, cyan) that
    modules use as defaults, so ESP, tracers, tags, notifications and the module list follow the theme. A
    colour you change on a module is saved with that module's settings as an override.
  - **Preferences** (UI scale, pausing singleplayer, mod key, vim keys) are yours, not the theme's, and stay put when
    you switch.
- **Profiler** (a window from the launcher): milliseconds per second spent by every module, service and listener in
  tick, render and other handlers, so a slow addon is a glance away. Timing runs only while it's open.

Default shortcuts follow [Omarchy](https://omarchy.org)'s tiling bindings. The mod key stands in for Super, which
Hyprland keeps for itself, and defaults to **Alt** (change it in Theme → Preferences):

| Keys | Action |
|---|---|
| `mod+Space` | launcher (Ctrl+j/k to move, Enter to run, Shift+Enter for the alternate action) |
| `mod+Return` / `mod+K` | console / keybindings |
| `mod+W` / `mod+T` / `mod+F` | close / toggle floating / full screen |
| `mod+J` / `mod+L` | toggle split / toggle workspace layout (Columns, Dwindle) |
| `mod+1..9` / `mod+Shift+1..9` | switch workspace / move window there |
| `mod+Tab`, `mod+Shift+Tab`, `mod+Ctrl+Tab`, `mod+scroll` | next / previous / former workspace |
| `mod+S` / `mod+Shift+S` | toggle the HUD workspace (Omarchy's scratchpad) / move window to the HUD |
| `mod+Arrows` / `mod+Shift+Arrows` | focus / swap in that direction |
| `mod+-` / `mod+=` | shrink / grow window |
| `mod+Shift+Space` / `mod+Shift+Backspace` | toggle the bar / toggle gaps |
| `mod+LMB drag` / `mod+RMB drag` | move (floating) or swap (tiled) / resize: tiled windows move their nearest edges, floating windows their nearest corner |

With Omarchy, Hyprland itself takes `Alt+Tab`. Use `mod+scroll` instead, or pick another mod key.

**Inside a window** everything is reachable from the keyboard. `j`/`k` (or ↓/↑) move the selection, and `h`/`l` (or
←/→) adjust a value or fold/unfold a section. `Enter`/`Space` activate, `o` unfolds a module's settings, `gg`/`G`
jump to the top/bottom, `Ctrl+d`/`Ctrl+u` move half a page, and `/` focuses the filter. Esc leaves a text field, or
closes the menu.

Every binding can be changed in the Keybinds panel.

## Essentials

The stock modules are the [myriad-essentials](https://github.com/MyriadClient/myriad-essentials) addon, released on its
own. In its five windows (each module's settings explain its options):

| | |
|---|---|
| Combat | **Kill Aura** (Grim-safe: faces the target with move fix and hits when this tick's or the last sent rotation lands on its hitbox within reach, so the turn and the hit share a tick; full charge, vanilla packet order; Switch holds the best weapon on the server only, from the hotbar or inventory, charging it before the target is in reach, or attacks only while you hold one), **Offhand** (totem, crystal, golden apple or shield, falling back to a totem when it matters; sword gap), **Auto Armor** (best armour; a bind flips chestplate and elytra), **Auto Eat** (pauses Baritone), **Velocity**, **Auto Disconnect** (health, totem pops, totems left, armour, players, beds, anchors, crystals, creepers, falls, the void, chosen entities; turns off or waits for the reason to clear) |
| Travel | **Elytra Fly** (highway bouncing, or Altitude: pitch 40 style flight without fireworks, about 27 b/s; with Elytra Swap's No Durability, long glides are cut with a chestplate so the elytra never wears; mines or, with Baritone, walks round obstacles), **Elytra Swap** (No Durability: the elytra never wears out, swapped with a chestplate from your off hand or hotbar, the swap sounds muted), **Rocket Boost** (rockets push as hard as Grim allows, full speed at once, about 34 b/s straight and 42 on diagonals, holding height), **Lock Rotation**, **Auto Walk**, **Inventory Move** (walk with screens open, arrow keys to look) |
| Building & Mining | **Scaffold** (blocks under you as you walk, clicking faces you can see; sprints without breaking stride, unaimed as 2b2t clients place, or Rotate to face each block for stricter Grim; bridges corners, towers when you jump), **Air Place** (place blocks in mid-air where you look, through the off hand as 2b2t clients do; current Grim builds refuse it), **Packet Mine** (hit once and the block is mined for you with your hand free, timed to Grim's own allowances so it finishes early without flags; Fast mode finishes every block early with decoy starts, as 2b2t clients do, with double break; hold and drag to queue blocks, auto rebreak, tools borrowed from your inventory and put back), **Auto Tool** (silent; borrows tools from your inventory and puts them back), **Reach** (starts at vanilla; Grim flags more), **Wall Interact**, **Baritone** (Baritone's own settings, when it's installed) |
| Inventory | **Inventory Tweaks** (right-click a shulker box or ender chest in your inventory to open it on the spot), **Stack Replenish** (tops up hotbar stacks and refills used-up slots), **X Carry**, **Middle Click** (friend, experience, rocket or pearl by what you point at) |
| Visuals | **ESP**, **Tracers**, **Nametags** (players, mobs, items, pearl owners), **Storage**, **Blocks** (highlight chosen blocks, like spawners or beds, in their own colours), **Block Highlight**, **Full Bright** (gamma or night vision), **No Render**, **Time of Day**, **View Model**, **Zoom**, **Free Look**, **Freecam**, **Tooltips** (shulker, ender chest and map previews, durability, food), **Baritone Render** (Baritone's path and goals in the theme's colours) |

HUD elements: Watermark (with the logo), Module List, Coordinates, Armor, Binds, Chest Count, Direction, Effects,
FPS, HP, Player Count, Speed, Totems, TPS. Baritone options do nothing when Baritone isn't installed.

## Commands

Prefix `.` (change with `.prefix`). Commands autocomplete in chat and in the console panel.
`.toggle <module> [on|off]`, `.bind <module> <key|none>` (any modifiers: `ctrl+alt+t`, `ctrl+alt+pgdown`), `.set <module> [setting] [value]`, `.reset <module>`,
`.profile [load|delete|save]`, `.friend add|remove|list`, `.theme <preset>`, `.addons`, `.update [check|all|<addon>]`, `.diagnostics` (copies versions,
addons, mods and enabled modules' changed settings for a bug report), `.panic`, `.help`, `.menu`,
`.modules` (click one to toggle it), `.binds`, `.say <message>` (sends text starting with the prefix as chat),
`.reload` (re-read the profile from disk), `.anticheat [auto|grim|vanilla]` and `.anticheat known add|remove <host>` (what the
server checks; see below), `.profiler [on|off]` (where the time goes, by module, with hints), `.jobs` and `.job pause|resume|cancel <id|module|all>`
(what modules are doing at length; the Jobs window and HUD element show it too), `.packets log on|off` (every packet both ways to a file),
`.cape [on|off]` (Myriad's cape, below), `.disconnect`, and
`.fakeplayer add|remove|clear|list` (client-side dummies for testing).

Setting ids are scoped to their group. When two groups share a name, use `group.setting`, for example
`.set esp colors.players #FF00FFAA`.

### Myriad's cape

Myriad players wear Myriad's cape and a matching elytra, in place of any other, and see it on each other. There's no
server of Myriad's own behind it: the skin parts a client reports (cape, jacket, sleeves, hat) are one byte with a
spare bit, which servers pass on to the players near you and vanilla clients ignore. Myriad sets that bit while the
cape is on and draws the cape on players who have it, so only players already in range ever see it, and nothing about
where anyone is goes anywhere. To take it off, turn off Theme → Preferences → Myriad Cape or run `.cape off`: players
near you see your own cape again (you still see theirs).

## Anti-cheat profile

Modules behave differently on a server running Grim (2b2t) and on one that only has the vanilla server's own checks:
on Grim they rotate before acting, click only faces you can see, move along the sent rotation, mine at the times it
allows and click in the inventory only while you stand still; elsewhere they act as fast as the vanilla server lets
them. **Auto** (the default) picks per server: Grim once it sends the steady pings anti-cheats like it use to keep in
step with the client (vanilla servers never send them), or at once on servers known to run it (2b2t by default;
`.anticheat known add <host>` adds more). Pick it yourself in the Profiles panel or with `.anticheat`. Essentials is
made for 2b2t and stays Grim-safe either way.

## Config

Config lives in `.minecraft/myriad/`. Module state and the menu layout belong to the active **profile**
(`profiles/<name>/modules.json`, `ui.json`). `myriad.json` holds global options and key actions, and `friends.json`
holds friends. Entries for modules or windows whose addon isn't installed are kept on save, so removing an addon and
adding it back later loses nothing.

Updating Myriad or an addon, or going back to an older version, loses nothing:

- When an update renames or reworks a module's settings, the module bumps `settingsVersion()` and moves old saved
  values over in `migrateSettings(fromVersion, saved)` (`SavedSettings.rename`, `move`, `map`, `renameGroup`). A
  renamed module lists its old names with `formerNames(...)`.
- Saved settings a module doesn't know (from a newer or older version of it) are kept on save, and settings saved at a
  newer `settingsVersion` keep that version, so going back and forth never migrates them twice.
- Only values that differ from their defaults are saved, so a better default in an update reaches everyone who never
  changed it.
- Each file records its `_format`; a file written by a newer Myriad is copied aside (`.format<N>.bak`) before an
  older one saves over it.
- Every file is written atomically. The first save of each session keeps the file before it as `.bak`, and a file
  that can't be read is kept as `.broken` and replaced by that backup.

---

## Writing an addon

An addon is an ordinary Fabric mod with a `myriad` entrypoint, built against `dev.myriadclient:myriad` from Myriad's
maven (`https://maven.myriadclient.dev/releases`, with sources and javadoc). Two projects get you going:

- [**myriad-addon-template**](https://github.com/MyriadClient/myriad-addon-template) is the project to start from: a
  module, a mixin-driven module and a HUD element, wired up and building. Its build is a dozen lines on Myriad's
  Gradle plugin (`id 'dev.myriadclient.addon'`, versioned with core), which brings Loom, Minecraft, Fabric and Myriad
  at the versions that release was built with, a dev client with Essentials, the check that you use only the API, and
  publishing. `./gradlew runClient` starts the game with Myriad, Essentials and your addon.
- [`example-addon/`](example-addon) is the reference: a complete feature set (waypoints with a module, command, window,
  HUD element and versioned saved data, plus Auto Tool, Tunnel, Block Search, Trajectories, Hole ESP, Chat Timestamps
  and a settings showcase) written to be read. Its [README](example-addon/README.md) maps each file to the pattern it
  shows.

```java
public final class MyAddon implements MyriadAddon {
    @Override
    public void initialize(AddonContext ctx) {
        ModuleGroup travel = ctx.registerGroup("Travel", Icons.PLANE);   // a window in the menu
        ctx.registerModules(travel, new MyModule(), new Other());       // menu order
        ctx.registerCommand(new MyCommand());
        ctx.registerHud("My Element", Icons.INFO, MyHud::new);
    }
}
```

The guide is on the site, [myriadclient.dev/docs/develop](https://myriadclient.dev/docs/develop):
[setting up](https://myriadclient.dev/docs/develop/setup),
[the addon and its conventions](https://myriadclient.dev/docs/develop/addon),
[modules](https://myriadclient.dev/docs/develop/modules), [settings](https://myriadclient.dev/docs/develop/settings),
[events](https://myriadclient.dev/docs/develop/events), [threads](https://myriadclient.dev/docs/develop/threads),
[windows and the HUD](https://myriadclient.dev/docs/develop/ui),
[drawing in the world and highlights](https://myriadclient.dev/docs/develop/rendering),
[services and helpers](https://myriadclient.dev/docs/develop/services), [jobs](https://myriadclient.dev/docs/develop/jobs),
[building bigger addons](https://myriadclient.dev/docs/develop/recipes), [hooks](https://myriadclient.dev/docs/develop/hooks),
[compatibility and versions](https://myriadclient.dev/docs/develop/compatibility),
[performance](https://myriadclient.dev/docs/develop/performance), [testing](https://myriadclient.dev/docs/develop/testing)
(against Grim too) and [publishing](https://myriadclient.dev/docs/develop/publishing). The
[API reference map](https://myriadclient.dev/docs/develop/reference) lists every package.

In short, the contract: addons use only `dev.myriadclient.api` (`dev.myriadclient.impl` is internal; Myriad's own
addons fail their build if they touch it). From 1.0, 1.x releases are source- and binary-compatible for the same
Minecraft version, a new Minecraft version is a minor release that may deprecate but removes nothing, and removals
need a major; until 1.0 a minor may also remove what an earlier minor deprecated. Core's build checks the API against
the last release on every change.

## Credits

The code layout borrows ideas from [Meteor Client](https://github.com/MeteorDevelopment). The UI
borrows from Hyprland.

Bundled fonts: Noto Sans (SIL OFL 1.1) and JetBrains Mono Nerd Font (SIL OFL 1.1). The license texts are in
`core/src/main/resources/assets/myriad/fonts/`.
