# Changelog

Myriad follows [semantic versioning](https://semver.org) for the public API (`dev.myriadclient.api`): while the major
version is 0, a **minor** release may change the API, and anything removed is deprecated for at least one minor
release first with its replacement in the javadoc; a **patch** release never changes it. `dev.myriadclient.impl` is internal
and may change in any release. Addons declare the oldest core they support in `fabric.mod.json`
(`"depends": {"myriad": ">=0.1.0"}`) and can check `Myriad.isAtLeast("0.2.0")` for newer features.

## 0.8.0 (in progress)

- **Anti-cheat profiles are values, and addons can add their own.** `AntiCheat.Profile` was an enum of two (Vanilla,
  Grim), so the services could only ask "is it Grim?" and an addon for another server had nowhere to say what that
  server checks. A profile is now a set of answers the services act on: `simulatesMovement()`,
  `checksLookOnActions()`, `visibleFacesOnly()`, `clicksOnlyWhileStill()`, `simulatesGlide()`, `breakTiming()`
  (`SERVER` or `CHECKED`) and `interactionBudget()`. The `forServer()` presets of placement, breaking, rotations and
  building are built from those answers rather than from "is it Grim", as are the inventory's wait for a still moment,
  No Durability's choice between a chestplate swap and restarting the glide, and the interaction budget; on Grim and
  on a vanilla server everything comes out exactly as before (a test compares each preset).
  - `Profile.VANILLA`, `GRIM` and the new `NCP` (NoCheatPlus, which 6b6t runs a fork of). NCP is treated as a vanilla
    server, as 6b6t was until now, except that it says the glide isn't simulated, so modules can tell it apart without
    naming the server. Auto picks it on 6b6t; Grim's pings still win if a server shows them.
  - `Profile.GRIM.named("my-addon:my_server", "My server").withSimulatesGlide(false)` makes a profile of your own;
    `ctx.registerAntiCheatProfile(profile)` lists it for the player (Profiles window, `.anticheat`), and
    `Match.on(profile)` matches it. Profiles are the same profile when their ids are. The `AntiCheat.DETECT` hook
    (`ServerSeen(host, singleplayer, pings, protocol)` → a profile) lets an addon recognise its server so Auto picks
    its profile; core's own guess is the fallback, and detection runs on the render thread.
  - `AntiCheat.inEffect()` is the profile in effect from anywhere (Grim before Myriad has started), beside `strict()`;
    `isStrict()` is now `simulatesMovement()`. `choice()`/`choose(profile)` (null for Auto) and `profiles()` replace
    `Mode`, `mode()` and `setMode()`, which are **deprecated, still working**, and go in 1.0. The saved choice is the
    profile's id ("grim" as before, an addon's as "my-addon:my_server"), kept while the addon that registers it loads.
  - **Source-breaking:** a `switch` over `AntiCheat.Profile` no longer compiles, and `Profile.name()` is the display
    name ("Grim", was "GRIM"). Compiled addons that only read `Profile.GRIM`/`VANILLA` or call `strict()` keep
    working. Essentials switches its elytra checks from `strict()` to `simulatesGlide()`.
- **Setting builders named like the rest:** `integer(name)`, `decimal(name)` and `oneOf(name, default)` beside
  `bool`, `string` and `color`. `intSetting`, `doubleSetting` and `enumSetting` are **deprecated, still working**, and
  go in 1.0; core, the example addon, Essentials, Automation, Stash Hunter and the Boze bridge use the new names.

## 0.7.0

- **Groups replace category windows.** With several addons, every addon in the same six categories made a wall of
  near-identical windows ("Combat · Essentials", "Combat · Boze", ...). Now each addon groups its modules as its author
  thinks best, and each group is a window of its own, placed, merged and moved like any other (nothing ties an
  addon's windows together):
  - `ctx.registerGroup("Travel", Icons.PLANE)` declares one; `ctx.registerModule(group, module)` and
    `registerModules(group, ...)` put modules in it. Modules registered without one go in the addon's own window,
    named after it, so an addon that declares nothing gets one window.
  - Window titles are the group's name, with the addon beside it; `Module.group()` says where a module shows,
    `Myriad.groups()` and `Myriad.modules().groups()` / `inGroup(group)` list them.
  - Layouts saved with category windows carry over on first load: each old window takes the group that replaced it
    (one named like the category, else the one with most of its modules), windows left with nothing close, and new
    groups open beside the rest. The Modules window and `.modules` list by group too.
- **Categories are tags.** `Tag` and `Tags.COMBAT`... are what `Category` and `Categories` were: what a module is about,
  its colour and icon, and `#combat` in the launcher (which also finds group windows, and modules by group:
  `#travel`). A module can have more than one (`alsoTagged(Tags.COMBAT)` in its constructor); `Module.tag()`,
  `tags()`, `hasTag()`, `Myriad.tags()`, `ctx.registerTag`, `MyriadAddon.registerTags`,
  `ModuleRegistry.withTag`, `ConfigShares.Builder.tag/group`. **Deprecated, still working:** `Category`,
  `Categories`, `Module(Category, ...)`, `category()`, `registerCategory`, `registerCategories`,
  `Myriad.categories()`, `inCategory`, `ConfigShares.Builder.category/addonCategory`; addons built against 0.6.0 load
  unchanged (checked with Essentials 0.6.0).
- **Icons**: `Icons` holds about 190 ready-made glyphs from the bundled icon font (Font Awesome in the Nerd Font),
  named and searchable, for groups, tags and panels; a test checks every one is in the font. `IconSetting`
  (`sg.icon("Icon")`) gives players an inline picker: a search box over a grid, Default to clear. Wheels have an icon
  (in the middle of the wheel, on their window and in the launcher), and each slot on a wheel can have its own icon and
  name from the button at its left (a command line reads better as "Home" with a house); macros and control panels
  have an icon too, used on wheels and in the launcher.
- **For addons, more from Myriad Automation's notes:**
  - `Placement.use(owner, hit, slot, options)`: a right click that uses a block (opens, toggles, uses the held item
    on it), made the way placements are: with `rotate` the look turns onto the block first and the click goes next
    tick where the look lands on it, sharing placements' one rotation a tick; unsneaked for the server even while you
    sneak (or sneaked, with `withSneak`), so it uses the block rather than placing on it. Completes once the server
    answered.
  - `Containers.open(owner, pos, ticks, Placement.Options)`: opens through `use`, so on strict servers the look
    turns onto the container first (Grim checks a click on a block as a placement).
  - `View.arrange(Containers.Arrangement)`: makes slots hold an exact layout (item and count each, or empty): sorting,
    kits, hotbar layouts. Planned one click at a time from the menu as it is, the cheapest move each time (a shift
    click that lands exactly there, a number key, two clicks, or the fewest clicks for part of a stack), slots put
    right in order so a full container sorts with no free slot of yours, checked against the server after, three
    rounds at most. `from`, `dumpTo`, `match(ItemMatch)`, `limit` (only what was gathered) and `reserve` (your own
    stacks stay). `View.run(Containers.Planner)` runs your own click planner the same way. Both queue with
    `take`/`put` on the container. `ItemMatch` (item / loose / exact) is API now. Myriad Automation's tests pass
    through core, in singleplayer with and without Grim's rules: a shuffled double chest sorted 7 times of 7, kits,
    shulker loading and unloading.
  - `Blueprint.of` stores positions the way chunks store blocks and visits only the cube in reach: 702 positions in
    reach of a 19,000-position blueprint, 74.7 → 3.3 µs a tick. The build planner no longer builds two maps per tick.
- The launcher's "New wheel …", "New macro …" and "New control panel …" make what's typed: "new wheel travel" made a
  macro called "new wheel travel" before.
- Windows of addons that aren't installed keep their ids, so a new window can't take one and confuse the tiling when
  the addon is back.

## 0.6.0

- **For addons, from Myriad Automation's notes** (what it did itself, now core's for everyone):
  - `Placement.place(owner, hit, …)` puts the block where vanilla does: into the clicked block when it's replaceable
    (air with `airPlace`, through the off hand, so an air placement takes any face and point; grass, water) or when
    the item adds to it (candles, sea pickles, snow layers, a slab's other half), otherwise beside it as before.
  - `Placement.Options.withSneak(true)` clicks as if sneaking, without crouching (the sneak key goes to the server for
    just the click), so chests, doors, repeaters and note blocks can be placed against.
  - `Placement.offHandSlot()` and `canPlaceWith(slot)` say whose block an air placement has in your off hand this
    tick and whether a placement can go now; an air placement no longer starts a swap after a click in the same tick.
  - A sign a module places doesn't leave its editor open over the game.
  - `Containers.open(owner, pos, ticks)` and `View.opener()`/`byHand()`: who opened a container, so modules that act on
    the ones you open (sorting) leave other modules' alone. `Containers.close()` waits for a moment Grim takes a close
    in (it flags one made while you move, as it does clicks).
  - `Inventory.giveBack` goes through the menu of a container you opened yourself instead of waiting for it to close
    (one a module opened is left alone while it works).
  - Placements into a replaceable block or onto one they add to (snow layers, candles) report as kept when they
    were: the result was judged by whether the block is replaceable, which a snow layer always is.
  - `AntiCheat.joinedProtocol()`, `joinedVersion()` and `isTranslated()`: the version the server sees you as
    (through ViaFabricPlus), for rules that differ by version.
- **Wheels**: hold a bind in game to show a ring of modules, macros, control panels, addons' actions, panels and
  command lines, drawn in the theme over a blur of the game; let go over one to run it (a click works too; Escape or a
  right click closes it). As many wheels as you like, each with its own bind, saved per profile (`wheels.json`): the
  Wheels window and each wheel's own window (search to add, arrows to order them clockwise from the top, name and
  bind under Settings), New wheel in the launcher, and a Wheels section in Keybinds. A slot follows its module or
  panel as it is now and shows as missing while its addon isn't installed.
- **Focus follows the mouse** on the desktop: the window under the cursor is the focused one (keys go to it, its
  border and the bar's title show it). Only the focus moves, so nothing shifts under the cursor; a click still raises
  a floating window and scrolls a column into view.
- **Binds with several modifiers** (Ctrl+Alt+T, Ctrl+Alt+Page Down): typed binds take short key names too
  (`.bind esp ctrl+alt+pgdown`; also pgup, del, ins, esc, prtsc), a mouse button set while holding modifiers keeps
  them (Ctrl+Mouse5), and a module bound to a combination takes the key for itself, so Ctrl+Alt+E toggles it without
  also opening the inventory (a plain key still reaches the game as before). Caps Lock and Num Lock are never part of
  a bind, so one set with either on still works with them off.
- **A modifier on its own as a bind**: press Left Alt (or Ctrl, Shift, Super) in a bind box and let go without another
  key, and that key alone is the bind; `.bind esp alt` (also `ralt`, `ctrl`, `rshift`, `super`, `ctrl+alt` for Ctrl
  held with Left Alt).
- The launcher's `#panel` takes in every window: category windows and HUD elements too, so `@ess #panel` lists all of
  an addon's (`#category` and `#hud` still narrow it).
- The Theme window's and the launcher's theme icon showed as "?": its glyph isn't in Myriad's icon font. It's a paint
  brush now.
- **Air placement in bursts, at 30 a second**: an air placement swaps the block to the off hand, and the swap back goes
  out first thing next tick, as Grim (PacketOrderG) cancels any off hand swap sent after a placement in the same tick.
  Every other placement of that block in the tick (against air or a block) now goes from the off hand on that one swap,
  so builds run as fast as the server keeps placements. A placement with another item waits for the swap back
  (queued ones stay queued; one without a turn is refused with `RATE_LIMITED`, try again next tick). The swap back
  goes out on the block's own slot whatever moved the server's slot meanwhile (your scroll, another module's swap),
  instead of being dropped or a second swap fighting it, and the slot and off hand show what they held throughout.
  Before, a second air placement in a tick, or another slot's silent swap, sent a swap Grim cancelled, leaving items in
  the wrong hands. Measured in singleplayer on a floating wall of two items, as fast as core allowed: 30/30 placed,
  30 to 31.6 a second, 9 at most in a tick, off hand and hotbar untouched.
- **The interaction budget is Paper's own 9 in 300 ms** (was 330 ms, which held builds to about 27 a second). Packets
  held over to the next tick (sent after the movement packet) now count when they're held, not when they go out:
  a burst made late in a tick used to pass the budget whole (16 in one tick in a test) and go out together next tick,
  past what the server keeps.
- **Pitch exactly ±90**: a turn that reached straight up or down could come out a hair past it in floats
  (38.01° → -90.0000076°), a packet Grim refuses (BadPacketsD), so the server kept the look before and a block placed
  then faced the wrong way. Pitch now lands on its target exactly.
- **Faces seen through another face**: `Reach.hitFor` and placements with `visibleFaces` counted a face as visible when
  the ray reached the block at all; from above, a ray to the top of a side face enters through the top, where the click
  lands. The ray now has to reach the face itself.
- **`BlockInfo.isClickable`** also counts every block whose class handles a right click itself (repeaters, redstone
  dust, cakes, composters, candles, looms, modded blocks), worked out once per class; fences, redstone ore, TNT and
  pumpkins, which let a held block through, still count as placeable against. Placements no longer click a repeater's
  delay instead of placing against it, and `Target.solid()` no longer picks such blocks as filler.
- **Fast Grim breaking**: the decoy start goes just above the build limit over the block (the server ignores any
  break up there, and Grim sees air), at most one a tick.
- `Rotations.serverYaw()` says how teleports count: vanilla's answer to one carries its look, but a turn sent while a
  teleport is on its way never lands, so work that clicks on an earlier look should check again after `TeleportEvent`.

## 0.5.0

- **Waypoints from Xaero's Minimap**: `Myriad.maps()` reads the waypoints of the server you're on, in every dimension,
  when Xaero's Minimap is installed (empty when it isn't): `waypoints()`, `waypoint(name)`, and `positionHere(waypoint)`
  scaled across dimensions (a Nether waypoint at 8 times its coordinates from the Overworld). For features that take a
  place by name rather than coordinates; later, more of what the maps know.
- **Baritone**: `.b <command>` (or `.baritone`) runs a Baritone command with Myriad's prefix, as Baritone's `#` does
  (`.b goto 0 64 0`, `.b mine diamond_ore`, `.b stop`), so anything that runs Myriad commands can drive Baritone.
  `Baritone.settings()` lists every setting Baritone has with its type and default, and `settingText`/`setSettingText`
  read and change any of them as text, as `#set` takes it. `Baritone.path()` and `Baritone.goalSpots()` say where it's
  walking and where it's going, for drawing them.
- **Ghosts**: `Ghosts.submit(event, state, feet, alpha)` draws a living entity's render state again anywhere, as a
  see-through copy of itself: skin, armour, cape and elytra faded (held items stay solid). With a `HighlightStyle` and
  colour it's highlighted like an entity, traced around its exact silhouette.
- **Placements no longer lost to Paper's spam limiter**: Paper (2b2t too) keeps only 9 use and place packets in any
  300 ms and drops the rest without a word, so 30 a second at most. The shared interaction budget allowed 15 per 500 ms,
  which let a burst of 10 to 15 through and lost the rest. It's now 9 in any 330 ms (the margin covers lag bunching
  packets together): a surround's 9 still go at once, and a steady stream keeps to about 27 a second. Measured on Paper
  1.21.4: everything kept up to 30 a second, about 29 a second kept whatever was sent above it, with or without Grim.
  `PacketLimits.windowMs(Kind)` gives each kind's window; `windowMs()` is deprecated.
- **Typed numbers**: `IntSetting`/`DoubleSetting` builders take `typed()` for a box to type the number in instead of a
  slider, for coordinates and other numbers no slider range suits.

## 0.4.2

- **No Durability with two flights**: with two No Durability engines enabled at once (Essentials' Elytra Swap for
  you, and an addon flight's own, such as Stash Hunter's Search), both broke the glide, often in the same tick, where
  the two starts cancel out, so the elytra wore. Now one runs at a time: the last one enabled for a pilot's flight,
  else the last one enabled. Seen live on 6b6t: every glide start went out twice; after it, one each and no wear.
- **Leaving in mid-glide**: with No Durability's chestplate on, leaving (Auto Disconnect, a flight's own disconnect,
  the pause menu) had you rejoin falling in the chestplate. Now the elytra goes back on just before the client leaves,
  and the glide is started again if the server had stopped it, so the server saves you with the elytra on (and often
  gliding). Where nothing can be sent first (the connection drops, a kick), rejoining in the air, falling with no glide
  and no flight running, puts the elytra on and opens the glide (measured: open about 13 ticks after joining, 2.5
  blocks down, no Grim flags).
- **Settings groups fold per window**: opening a module's settings group (a sub-section) in one window opened it
  everywhere the module shows (its category window, every control panel with it). Each window now keeps its own.

## 0.4.1

- A control panel's module picker stays open while you pick: it closed after every module.
- **Binds with modifiers**: a press runs only the most specific Myriad binds that match it. With T and Ctrl+T both
  bound, Ctrl+T runs only the Ctrl+T one (both used to); Shift+T, bound to nothing, still runs T. Hold binds also
  check the modifiers when pressed.
- `.bind` and `.set` take modifiers and mouse buttons as the menu shows them: `ctrl+t`, `shift+alt+f6`, `mouse5`,
  `rmb` (`Keybind.parse`).
- The dev console's `cursor <x> <y>` moves the mouse (window pixels), for clicking through the menu in tests.

## 0.4.0

- **Control panels**: a set of modules switched together by one bind or switch, each with settings the panel
  saves for it (Save in the module's settings in its window). On lays those over the modules' own; off turns off only
  what it turned on and takes its settings off again. Saved per profile; made in the launcher or the Control Panels window;
  `.control on|off|toggle <name>`. Addons ship ready-made ones with `AddonContext.registerControlPanel(ControlPreset)`;
  players can edit, delete or reset them. New API: `dev.myriadclient.api.control`, `Myriad.controls()`.
- **Layered settings**: a setting's value is the player's own, with overrides laid on top while something needs them
  (`Setting.override(owner, layer, label, value)`, `release(owner)`; layers: server profile, control panel, module).
  `get()` is the top one; only the player's value is saved, so releasing an override puts nothing back and a crash
  leaves nothing changed. Overridden settings show who set them. UI edits and `.set` go to the override in effect if
  it takes edits (a control panel saving what you tune while it's on), otherwise to the player's value. Control panels
  now work this way. `decode`/`encode` read and write values without touching the setting.
- **Hooks**: addons change how core works without changing core. A service can be replaced (`ctx.replace(Service.ROTATIONS)`,
  `INVENTORY`, `PLACEMENT`, `BREAKING`), for everyone or only an addon's own modules (`Match.addon`), by extending the
  service's new `Forwarding` class; `Myriad.rotations()` and the rest route each call to the implementation for its
  owner. One decision can be hooked instead (`ctx.hook(Rotations.TURN)`, `Placement.AIM`, `Inventory.CLICK_READY`,
  `HighlightEvent.ENTITY_HIGHLIGHT`), most specific match first, each able to hand over to the next; addons declare
  hooks of their own with `Hook.of`. `Match` narrows either to a module, an addon, an anti-cheat profile or a server.
  The Hooks window and `.hooks` (`on|off <name>`, `why <module>`) show what's in effect; the player can turn any off, and
  one that throws is turned off for the session. New API: `dev.myriadclient.api.hook`, `Myriad.hooks()`.
- **Shared configs**: share modules, whole menu windows, an addon's modules, control panels and the theme as a code
  (`myriad_JS3k4d`) from the new Share window or `.share`; anyone can view it at `myriadclient.dev/config/<code>` or
  take it in, as their settings or as a control panel to try (layered over theirs, nothing lost). Settings marked
  private never leave the client: `Setting.Builder.protect()` for addons (coordinates, file paths and macro commands
  are protected by default). Keybinds only go in when asked. Shares live on Myriad's new API
  ([myriad-api](https://github.com/MyriadClient/myriad-api)); a share can be deleted from the computer that made it.
  New API: `dev.myriadclient.api.share` (`Myriad.shares()`: `builder()`, `publish`, `fetch`, `apply`, `unpublish`),
  `Http.delete`.
- **Typed control panel presets**: `ControlPreset.builder(name).module(tunnel, m -> m.set(tunnel.width, 3))` takes your
  own modules and their settings typed; modules of other addons still go in by id.
- `Notifications.toast(title, message)` builds a notification (`level`, `lasting`, `replacing`, `show`);
  `Desktop.openPanel(PanelType)` and `applyTheme(Theme)` take what you registered.
- **Macros**: modules you make, whose Commands (Myriad commands, server commands, `wait <ticks>`, chat) run when
  pressed, once (Run) or on and off (Toggle, with Off Commands). In the Macros category; `.macro`.
- **Launcher filters**: `@addon` and `#kind` / `#category` narrow the results (`@ess #render`, `#control`, `#panel`);
  New macro and New control panel take what's typed as their name.
- Internal: the window manager is split into its painter, binds and category windows; imports tidied across core.

## 0.3.4

- A hold-bound module let go while a screen was up (a portal's loading screen, the disconnect screen, the menu) now
  turns off; it stayed on before (Free Look kept the mouse from turning you).

## 0.3.3

- **Myriad's cape**: Myriad players wear Myriad's cape (the logo on dark, speckled cloth with a torn hem) and elytra
  (Minecraft's own wings, or a resource pack's, in Myriad's dark colours with the logo), in place of any other, and see
  it on each other, with no server of Myriad's own. The skin parts a client reports (cape, jacket, sleeves, hat) are one byte
  with a spare bit: servers pass it to the players near you and vanilla clients ignore it, so Myriad sets it while
  the cape is on, and a Myriad client draws the cape on players who have it. Only players already in range ever see
  it. Theme → Preferences → Myriad Cape (new setting, `ThemeSettings.myriadCape`) or `.cape off` takes it off: players
  near you see your own cape again, and you still see theirs.

## 0.3.2

- **Opening the menu**: mod+1–9 (Alt by default) opens the menu on that workspace, in game and on the title screen,
  and pressing the number of the workspace that's showing closes it; mod+S opens it on the HUD. Right Shift no longer
  opens it: **Open Menu** is unbound by default (bind it under Keybindings → Global for a single key). A Right Shift
  saved by an earlier version is taken as the old default and dropped; one you bind from now on is kept. The keys are
  consumed, so mod+3 doesn't also pick hotbar slot 3.
- **Launcher anywhere**: mod+Space opens the launcher on its own, over the game or the title screen, without the
  menu. Toggling a module, applying a theme or switching profile happens there and you're back where you were; a
  panel, a category or settings open the menu.
- **Title screen**: Myriad's own, in the theme's colours: the logo, the usual buttons plus one for the Myriad menu (and
  Mod Menu's when it's installed), and the keys that open the menu. Arrow keys and Enter work. Theme → Preferences →
  Title Screen turns it off (new setting, `ThemeSettings.titleScreen`); Minecraft's then shows Myriad's logo and keys
  in a corner.
- **Minecraft's Controls**: a Myriad category in Key Binds with the mod key and Open Menu, so they're where players
  look. Changing them there changes Myriad's (the mod key can be Alt, Super or Ctrl) and the other way round.
- The launcher no longer fades out over a workspace opened after it closed.

## 0.3.1

- The default theme takes the logo's colours: its mint accent on a neutral near-black, with the window greys between.
  The previous default is still a preset, Catppuccin Mocha.
- The example addon has an icon.

## 0.3.0

The packages and the maven coordinates move to `dev.myriadclient`, after Myriad's domain. Nothing else changes, but
every addon has to be rebuilt: the one exception to deprecating before removing.

- Packages: `dev.myriad.api` is now `dev.myriadclient.api` (and `dev.myriad.impl` is `dev.myriadclient.impl`). In an
  addon, replace `dev.myriad.` with `dev.myriadclient.` in imports and in the mixin config's `"plugin"`.
- Maven: `dev.myriadclient:myriad` and `dev.myriadclient:myriad-essentials`, on Myriad's own maven at
  `https://maven.myriadclient.dev/releases` (the old GitHub Pages maven isn't updated any more). The template's
  `build.gradle` declares it as the only source of `dev.myriadclient`, so nothing of that name can come from anywhere else.
- **Elytra flight in core**: `Myriad.elytra()` (package `dev.myriadclient.api.elytra`) with No Durability, chest
  swaps, the glide hold and pilots, and `GlideEvent` for the glide's movement. Every flight module now shares one engine
  and one set of hooks: before, Essentials and Stash Hunter each carried a copy (about 1,400 lines and five mixins), and
  two of them would have held back the same pings. A pilot that `steers` moves the local player by the rotation sent to
  the server (glide, walking, jumps) while the camera stays free, and setbacks no longer turn the camera to it.
- `BlockRenderEvent.reloadChunks()` meshes every chunk again the way 26.2 wants it (F3+A's path), with vanilla and
  Sodium alike. The old advice, `mc.levelRenderer.allChanged()`, no longer exists, and calling the renderer directly
  left the terrain undrawn.
- Removed what was only there for older versions: `Registry.freeze()` and `isFrozen()` (deprecated since 0.1.0), the
  updater's special case for Essentials 0.1.0 (which named core's repository), and two settings migrations from before
  the first release (the Open Desktop bind, themes saved per profile).
- Core refuses to start next to the addons built for 0.2 (Essentials before 0.3.0, Boze before 0.3.0, Omarchy before
  0.2.0) with Fabric's error naming them, instead of crashing; update them together.

## 0.2.12

A fix for shader packs. No API changes.

- Lines and shapes drawn in the world (Tracers, boxes) now show with Iris and a shader pack on. The pack's passes
  painted over them, as Iris drew them with vanilla's shader into its buffers; they're now assigned the pack's own
  line and basic programs (Iris's API, found reflectively), the way vanilla's lines are drawn.
- Dev console: `hud <panel id>` puts a HUD element on the HUD, for scripted screenshots and tests.

## 0.2.11

Moving items in containers in bulk, checked with the server. Additions to the API; nothing changes or goes away.

- `Containers.View.take(what, how)` and `put(what, how)` move items between a container and your inventory, as many as
  `Containers.Transfer` allows (`all()`, `upTo(n)`, `withoutHotbar()`) and there's room for, and complete with how many
  went by the server's count. Whole stacks go with a shift click; part of one is picked up and shared out (never more
  than half the stack plus two clicks), onto stacks of the same item first, the cursor empty again after each stack.
  The clicks wait for ticks Grim takes them on, within the packet budget. Once they're made the server is asked what it
  has; a click it never took, or a slot someone else changed, gets another round (up to three).
- `Inventory.refresh()` and `Containers.View.refresh()` ask the server for the whole menu and complete once it's here:
  a click that changes nothing, sent with a state id the server doesn't have, which it answers with everything. For when
  the client may show items the server doesn't have (Grim drops a click without a word).
- `Containers.open` fails when something other than that block's container opens (a server plugin's menu), and closes
  a container that's already open first.
- Inventory clicks no longer slow you down while you sprint. Grim refuses a click while it has you sprinting, and takes
  your sprint state from the sprint packets as they arrive, so core now tells the server you stopped just before the
  click, and vanilla tells it you started again with that tick's movement: you sprint on throughout. Before, sprint
  (and for newer versions your movement keys and jump) was let go of for a whole tick. Every click core makes goes
  this way (refills, armour, totems, tools and weapons from the inventory, container moves, your own clicks while
  moving), and `Inventory.safeToClick()` is true while you sprint.
- On 2b2t, clicks no longer wait for you to let go of movement keys or jump: its Grim doesn't judge them (live
  captures, joined as 26.2: clicks over held forward and jump were all applied, where current Grim cancels them).
  `Inventory.clickWaiting()` only reports clicks that need a still tick.

## 0.2.10

Packet mining that waits for the server and sends fewer packets, and display masks for swaps you needn't see.
Additions to the API (`Breaking.Options`, `Inventory.mask`, `Inventory.clickWaiting`/`keepKeys`); nothing changes or
goes, so addons built for 0.2.x keep working.

- `Inventory.clickWaiting()` and `keepKeys()`: a click waiting for a tick the server takes it on (yours from a screen,
  or a module's) can be given one by the module holding your sprint or keys, where that costs it least, and that
  module can keep its keys on a tick it can't spare (a jump) instead of core letting go of them.
- `Inventory.mask(owner, holdMs, slots...)`, `unmask`, `shown(slot)` and `shownArmor()`: while a module moves items
  among a few slots (a chestplate in and out of the chest slot several times a second), each keeps showing the item it
  held: in the hotbar, both hands (without the hand dipping for a new item), the selected item's name, the armour
  bar, inventory screens (and their tooltips) and your own model's armour, wings and held items. Anything from outside
  the group (a pickup) shows as it is. It shows a copy, kept through the tick a swap's two slot updates can come
  apart. `shownInHotbar` (borrowed items) now goes through `shown`.
- `Breaking.Options.withWaitForServer`: in packet modes the block stays on screen until the server's update says it's
  gone, instead of turning to air as the packet goes out. A finish the server refuses no longer flashes the block away
  and back (a ghost block you could walk into); you see the break a round trip later.
- A tool swapped in for a break packet goes back at the end of the break step, in the same tick (before the movement
  packet), instead of first thing next tick. Grim's PacketOrderE, the reason for waiting a tick, only looks at slot
  changes after attacks, uses, sneaking, sprinting and glide starts, not digging (read in Grim 2.3.74's source).
  Another module's swap still waiting to go back is left to go back as before.
- The breaking service's unseen swing with a break packet is skipped when a swing already went out that tick (vanilla's
  for your click, a module's): one a tick, as vanilla sends while you hold the button.
- Slot holds hand over: when a module's hold ends while another module asked for one this tick or last (Kill Aura
  readying your weapon while you mine with it), the server goes straight to that slot, instead of to the one you see
  and back again in the same tick.
- Tested on the local Grim (2.3.74, experimental checks on, joined as 1.20.4) and on 2b2t: no flags, setbacks or
  blocks coming back; every finish was confirmed by the server 2 to 3 ticks later. Finishes keep their unseen swing:
  without it Grim's experimental NoSwingBreak flags them.

## 0.2.9

Latency that's right where the tab list isn't, and quieter notifications. No API changes, so addons built for 0.2.x
keep working.

- `Myriad.server().ping()` is measured from the server's answers to what you do: the time from an action that carries a
  sequence number (using an item, on a block or not; digging) to the server's acknowledgement of it, the median of the
  last few, its tick included. Where the tab list shows more, that's used. On 2b2t the tab list shows everyone at 0,
  so ping read 0 there and whatever waited a round trip on it (Elytra Fly's chestplate swaps, block placement
  timeouts) waited too little; it now reads the real thing. No packets of its
  own: a ping request a second (as vanilla sends with its network chart open) was tried, and the server's instant
  answer got Grim's next ping to the client ahead of what came with it, which Elytra Tweaks' glide hold needs in order.
- Notifications: the same one again (title, message, level) while it's still on screen is counted on it ("×3") and
  stays up longer, instead of stacking a copy. One sent with a key that's showing replaces it, as before, and counts
  up when it's the same.
- Dev console: `status` shows the ping.

## 0.2.8

Inventory handling, rotations and placements for Grim (2b2t) by the version the server sees you as. Two additions to
the API (`Baritone.getSetting`/`setSetting`); nothing changes or goes, so addons built for 0.2.x keep working.

- Inventory clicks wait for a tick Grim takes them on, everywhere: core's click methods (`move`, `quickMove`,
  `swapWithOffhand`, `drop`, `moveToHotbar` and the `Containers.View` clicks) return false (or, for `View`, do
  nothing) while you'd be refused and release your keys for the next tick, so asking again then works. A refused click
  is cancelled without the client being told, which showed items the server didn't have (a totem in your off hand
  that wasn't there). Your own clicks in a screen while you move (Inventory Move) wait the same way and are made then.
- What counts as moving follows the version the server sees, ViaFabricPlus's when it translates (read by
  reflection, so it stays optional): sprinting always; movement keys and jump from 1.21.2; sneaking from 1.21.9. As
  1.20.4, most 2b2t players' version, a click only drops sprint for a tick instead of stopping you. `.anticheat`
  shows the version you joined as. A version that can't be told gets the strictest rules.
- A still tick now really stops sprinting under ViaFabricPlus (it starts sprint from the held key, as older versions
  did, after the input was read).
- `silentSwap` keeps the server on the slot until the start of the next tick instead of switching back right after the
  action, which Grim's PacketOrderE flags (experimental checks). Another swap to the same slot meanwhile sends nothing.
  The client holds the slot too while the action runs, also when the server already held it, so placements and items
  used are predicted with the right item.
- `swapWithOffhand` from the hotbar while a click would have to wait uses the swap-hands key with a silent swap: no
  click, no waiting.
- `move` puts armour into its own empty slot with one shift click instead of two or three clicks.
- Air placement swaps the block out of the off hand at the start of the next tick, not right after placing (Grim's
  PacketOrderG cancelled that, leaving the block in the off hand); meanwhile the off hand's totem is in the hand the
  server holds.
- The inventory packet budget counts clicks as they're made: ViaFabricPlus sends clicks to 1.21.4 and older servers
  past the connection, so they weren't counted.

Rotations and placements, checked against Grim's source and the test server:

- The rotation move fix walked 2% slow diagonally as 1.20.4 (any version up to 1.21.4): those versions don't normalise
  a diagonal's input, and ViaFabricPlus keeps it that way, but the rewritten keys were normalised. Grim flagged
  Simulation every tick a module turned you while you walked (Kill Aura beside you: dozens of flags a run, now none).
- Placement reach is measured as Grim does: to the nearest point of the block clicked, from your eyes or from any eye
  height Grim can't rule out (standing, sneaking, swimming or gliding), within your block reach, and within the
  server's own limit (reach + 1 from your real eyes). It was the eyes to the centre of the space, which refused
  placements Grim takes (up to 0.87 blocks more, more below you); and a click's ray was allowed a block past Grim's
  reach, which could be refused (RotationPlace) at the edge. `Placement.Options.range()` is now that distance.
- Clicks aim near the point of the face nearest you (not its centre), a little off it at random each time: a straight
  scaffold turned into every block by exactly the same yaw, which Grim's DuplicateRotPlace flags (experimental checks).
- Rotated air placements were never clicked (the check that the block clicked against is still there failed for
  air); unrotated ones only click where your look enters the space.
- Joined as 1.17 to 1.20.5, ViaFabricPlus sends your position and rotation before each item use, with the camera's
  rotation; it now carries the use's, so the server doesn't turn to the camera and back while a module holds a
  rotation.
- Baritone's movement input is built the vanilla way for the version you joined as: Baritone's own slowed sneaking
  twice (the player slows it again) and never normalised a diagonal, so its sneaking to an edge to bridge (and, joined
  natively, its diagonal walking) was flagged (Simulation).
- `Baritone.getSetting` and `Baritone.setSetting`: read or change one of Baritone's settings by name for now (not saved
  to its settings file), e.g. to let it place blocks for a while and put it back after.

## 0.2.7

An addition to the inventory API; addons built for 0.2.x keep working.

- `Inventory.spare(owner, predicate)` and `Inventory.isSpared(stack)`: a module can ask for items to be left alone, such
  as gear about to break that it put away to be mended. `bestInHotbar`, `bestInInventory` and `Mining.fastestSlot`
  (the tools breaks pick, and borrow from the inventory) pass them over.

## 0.2.6

No API changes.

- Bed and respawn anchor damage (`Damage.bed`, `Damage.anchor`, and so `Threats.beds`, `Threats.anchors`,
  `Threats.worst`) count the exploding block as gone, as it is when it goes off. The blast was traced from inside the
  block, which blocked every line to you: a charged anchor 3 blocks away came out at 1 damage, not the 30-odd it does.
  Auto Disconnect's Beds and Anchors never left, and Offhand's switch to a totem when something could kill you didn't
  count them.
- Dev console: `connect <host:port>` joins a server, from a disconnect screen too.

## 0.2.5

An addition to the highlight API; addons built for 0.2.x keep working.

- `HighlightEvent.Shapes#below()`: shapes on a layer of their own, drawn under everyone else's shapes (entities stay
  on top of both). For a highlight that mustn't hide others where it overlaps them on screen, like the block you're
  looking at in front of highlighted chests. The layer has its own mask and passes, only over the area its shapes
  cover, and costs nothing while unused.

## 0.2.4

No API changes.

- Rotations: the yaw sent while a module holds one (an aura, Elytra Fly's lane) turns from the last yaw sent, never
  more than half a turn. It used to be kept near the camera's, so looking round past the opposite way jumped it a
  whole turn in one packet, which Grim flags (AimModulo360); handing back to the camera afterwards could too.
- Rotations after a setback: whether a movement packet carries the rotation is now judged against the rotation the
  server last got. A setback's reply goes out on its own with the camera's rotation, which vanilla doesn't count, so a
  rotation a module held (Elytra Fly's pitch) wasn't sent again: the server kept simulating the camera's, set you back
  again, and so on every round trip. At 2b2t-like ping one setback turned Altitude flight into a setback every
  quarter second (and, with No Durability, a fall); Grim's BadPacketsJ flags on No Durability's swaps were the same
  thing. Rotations in packets another module cancelled no longer count as sent.
- Dev console: `mouse <button> press|release|click` and `scroll <notches>`, sent through vanilla's input handler like
  real input.

## 0.2.3

Additions to the highlight API only; addons built for 0.2.x keep working.

- Addons can bring their own highlight fills: `HighlightStyle.Fill.custom(shader)` takes a fragment shader from the
  addon's assets that imports `myriad:highlight_fill.glsl` and defines `customFill`, which returns the colour of a pixel
  inside a highlight (it gets the pixel's position in the highlight, the highlight's colour with any gradient, the
  style's opacity and dot settings as a scale, the GUI scale and time). Outlines, glow and shared edges are drawn
  around it as for the built-in fills; each custom fill in use adds one pass over its highlights' insides. The
  example addon's Logo ESP fills entities with a drifting grid of Myriad logos this way.
- Highlights come in two layers, each with its own silhouette mask: shapes first, entities drawn over them. Before,
  one mask held only the nearest thing per pixel, so a wall of highlighted chests hid the highlighted entities behind
  it.
- Shapes that look alike share one highlight per frame, however many there are, and retained meshes can be
  highlighted without re-sending them: `HighlightEvent.Shapes.mesh(mesh, style, palette...)` draws a `WorldMesh`'s
  faces into the mask with the highlight id applied at draw time, each vertex picking its colour from the palette
  (`Shapes.paletteColor(i)`). `ChunkCache.highlight(event, style, palette...)` does that for every chunk mesh, and
  `ChunkCache.setMeshesVisible(false)` keeps them from being drawn as boxes. Measured: ~590,000 highlighted stone blocks
  at about 1,000 FPS, the same as with nothing highlighted.
- `HighlightEvent.Shapes.block(pos, style, color, grow)` grows the shape a little, so a highlight of the block under the
  crosshair sits in front of other highlights of the same block instead of flickering against them.

## 0.2.2

No API changes.

- Highlights only connect when they look alike (same style and colour): touching ones still share one outline (a vein
  of ores, a double chest, a portal), but where highlights that look different touch, the one added later outlines
  itself across the boundary. Before, any two touching highlights merged, so a block highlight next to a Blocks or
  Storage highlight lost the edge they shared, and two touching ESP targets in different colours had no line between
  them. A highlight added by a low-priority listener draws over the others. Up to 65,536 highlights a frame.

## 0.2.1

Desktop placement for many addons, and sideways scrolling. The API only gains additions: two default methods and two
constructors, so addons built for 0.2.0 keep working.

- New addons no longer crowd one workspace. A workspace holds as many windows as fit side by side at a readable
  width; an addon's windows go beside the ones of the same categories while there's room, and otherwise onto a
  workspace of their own (continuing onto the next empty one), with one notification per addon ("Boze added 7
  windows to workspaces 2 and 3"). The first-run desktop is laid out the same way. Only windows the desktop hasn't
  seen are placed, so your arrangement is never moved.
- Columns never get narrower than a readable width: when they don't all fit, the row scrolls sideways (Shift + mouse
  wheel, or the wheel over a gap), and focusing or opening a window scrolls it into view.
- `LayoutState` has `scroll(amount)` and `reveal(window)` (default methods: layouts that don't scroll ignore them).
- `HighlightSettings` and `BoxStyle` take whether Through Walls starts on, for highlighting things you can already see
  (like the block you're looking at).

## 0.2.0

Adds highlights to the API (a minor release: addons built for 0.1.x keep working).

- Highlights (new API): `HighlightEvent.Entity` and `HighlightEvent.Shapes` outline the exact silhouette of entities
  (armour, held items and cut-outs included) and of boxes or block shapes, with an optional glow, a solid or dotted
  fill, a gradient, and through walls or only where visible; `HighlightStyle` describes the look and
  `HighlightSettings` adds the standard settings for one. Silhouettes come from vanilla's entity outline target, so
  they cost no extra geometry, and the shader runs only over the screen area the highlights cover, in two passes whose
  work grows with the width in pixels. Measured with 20 creepers on screen (1261×1030, uncapped): ~1130 FPS without,
  ~1230 with a 2 px outline (no difference within noise), ~1025 with a 24 px glow and dot fill (about +0.09 ms a frame).
  Nothing runs while nothing listens. While highlights are in use, vanilla's glowing effect is drawn by the same shader,
  and vanilla's entity outlines now depth-test against each other (the nearest wins where two overlap).
- Highlight outlines and glows get thinner with distance (`HighlightStyle.distanceScaling()`, on by default): full
  width within 10 blocks, down to half width at 40 blocks and beyond, never below a pixel.
- Through-walls highlights work with occlusion culling: entities that Sodium (hidden chunk sections) or EntityCulling
  (traced as hidden) would skip are still drawn while a through-walls highlight wants them, and only those. Tested with
  Sodium 0.9.2, Iris 1.11.4 and EntityCulling 1.11.2 together, with and without the Complementary Unbound shader
  pack (through walls and depth-tested both draw correctly under it; Iris logs once that it has no program for the
  highlight passes, which is what lets them run unchanged).
- `EntityRenderEvent.Nametag` now fires for players and mobs: it hooked `shouldShowName`, which living entities
  override without calling up, so cancelling it only ever hid tags on non-living entities. Cancelling also hides the
  score line under the name.
- `BoxStyle` takes a visibility condition, for modules that only show box options in some modes.
- The Master layout is gone; Dwindle and Columns cover it. Workspaces saved with Master open as Dwindle.
- Updates: an addon whose `contact.sources` names Myriad's own repository is now looked for in a repository named
  after its mod id under the same owner, so Essentials 0.1.0 (which shipped pointing at Myriad's repository) finds
  its releases at `fyzz-dev/myriad-essentials`. Before, it was silently skipped.
- Check now only says "Up to date" for what it actually checked, and names any addon it couldn't check and why;
  an addon with no GitHub repository says so on its card.

## 0.1.2

No API changes.

- Updates: Myriad checks GitHub for new releases of itself and every addon whose `contact.sources` is its GitHub
  repository (or that names one with `"custom": {"myriad": {"updates": "owner/repo"}}`). The Addons window shows the
  new version on the card, with **Update** and **Update all**, the bar shows a count, and a toast says so once per
  launch; `.update` does the same from chat. The download is checked (size and SHA-256 against GitHub, mod id,
  version, and its Minecraft and Myriad dependencies) and swapped into `mods/` when the game exits, keeping the jar
  it replaced in `myriad/updates/old/`; the next launch confirms it went in. Turn the launch check off at the top
  of the Addons window. On Windows, where a running jar can't be moved, a hidden PowerShell does the swap once the
  game has exited; that path has not been tried on a Windows install yet.

## 0.1.1

Fixes and the repository split; no API changes.

- Dwindle is the default layout for the first workspace too (it already was for the others); the first-run desktop
  splits the largest window each time, so the stock windows come up as a grid rather than ever thinner slivers.
- Essentials has moved to its own repository, [myriad-essentials](https://github.com/MyriadClient/myriad-essentials),
  with its own versions and releases; the Grim test server and suite went with it. This repository is core and the
  example addon, and `example-addon/` is a code sample now: it still builds and runs, but isn't released.
- Desktop shortcuts (`mod+1`, `mod+K`, `mod+W`, ...) work while the console window is focused; it only keeps the keys
  it types with.
- Windows of an addon that registers its modules after startup (the Boze bridge) come back where they were: a saved
  window whose modules aren't there yet at load is kept aside and revived, on its workspace and in its place in the
  tiling, once they arrive.

## 0.1.0

First release: the window manager, modules and settings, the event bus, the services (rotations, inventory, placing,
breaking, building, containers, tasks, packet limits, tick speed), themes, HUD, commands, profiles, Essentials and
the example addon.

Everything below is in the README's *Writing an addon* section.

### Boze bridge

- [myriad-boze](https://github.com/MyriadClient/myriad-boze), a separate addon: every module of the Boze client in
  Myriad's menu, in the shared categories, with settings, keybinds and on/off state in sync both ways. Boze keeps its
  config and handles the keys. The API changes below are for it.

### API

- `Module.isMirror()`: a module that stands in for another mod's feature. Myriad neither saves it with the profile nor
  toggles it on its keybind. `Module.setEnabledSilently` is public API now (it was marked internal).
- Registries are no longer frozen after startup, so an addon can register once something it waits for is ready; the
  menu places windows for modules registered late when it next opens. `Registry.freeze()` and `isFrozen()` are
  deprecated and unused.

### Compatibility and the API contract

- Service options (`Placement.Options`, `Breaking.Options`, `Rotations.Options`, `Building.Options`) are built from
  presets with `with…` methods instead of positional constructors, so new options can't break addons.
- Interfaces only core implements are `@ApiStatus.NonExtendable`; event constructors and lifecycle hooks are
  `@ApiStatus.Internal`. A build test fails if any public API signature exposes `dev.myriad.impl`.
- `Myriad.version()` and `Myriad.isAtLeast(...)`.
- Releases are published to a public maven, `https://fyzz-dev.github.io/myriad`, so addons build without
  `publishToMavenLocal`.

### Anti-cheat profile

- `Myriad.antiCheat()`: Auto (detects Grim's pings, or servers in the known list), Grim or Vanilla, set in the
  Profiles panel or with `.anticheat`. The services' `forServer()` presets and inventory click timing follow it.
  `.anticheat known add|remove` edits the known servers.

### Events

- `CollisionShapeEvent`, `PlaySoundEvent`, `TeleportEvent`, `HealthEvent`, `DisconnectEvent`,
  `EntityEvent.TotemPopped/Died`.
- `BlockRenderEvent`, `EntityRenderEvent.Visible/Nametag/Model` and `ContainerScreenEvent.SlotDrawn/Tooltip/Click`,
  so X-ray, chams, entity hiding, custom nametags, slot icons, content previews and inventory tricks need no mixins.
- `@Subscribe(inGame = true)` handlers only run with a player in a world; `@Subscribe(packets = ...)` handlers are
  dispatched only for those packet classes.

### Services and helpers

- `combat.Threats`, `combat.Crystals`, `combat.Trajectory` (`simulate`, `aimAt`), `world.Holes` (`around`, `scan`,
  `surround`, `city`), `render.BoxStyle`, `render.EntityGroups` (from Essentials), `ItemInfo` armour and elytra
  helpers, `Movement.baseSpeed`, `util.Ticks`, `util.TickCached`.
- `ServerStats.reconnect()` and `lastAddress()`, `Myriad.runCommand`, `AddonContext.settings` (addon-wide settings,
  saved per profile, shown in the Addons panel), `Module.conflictsWith`.
- Setting types: `enumSet`, `intList`, `sound`. `.toggle <module> on|off`.
- `ChunkCache.async()` computes chunks on the worker pool.

### Performance

- The event bus dispatches packet events through a per-packet-class table, keeps `ListenerFlag`s for the hottest
  hooks, and has a handler Profiler (Profiler panel, `.profiler`) that shows milliseconds per module per second.
- Packet counters are lock-free; attack charge, threats and friend lookups are cached; collision shapes cost one
  volatile read unless a module listens.
- Measured on the Grim test server with twelve mobs nearby: with ESP, Tracers, Nametags, Storage, Blocks, Kill Aura,
  Offhand, Hole ESP, Trajectories and Block Search all on, the Profiler shows about 8 ms per second in handlers in
  total (under half a millisecond per tick), Offhand's threat check the largest at 3.7 ms/s. A JFR recording of the
  self-test puts Myriad frames in 7% of samples (9% before this release), nearly all of it HUD text rendering.

### Config

- Settings a module doesn't know are kept on save, a settings version is never lowered, files record their format,
  each session keeps a `.bak`, and broken files are restored from it. `AddonStorage` reads and writes versioned data.

### Dev tooling

- `selftest` and `respawn` script steps, the Essentials Grim suite (`tools/grim-test/suite`), API-only javadoc.

### Essentials

- Simpler settings (Offhand, Auto Armor, Auto Eat), no Grim flag when turning No Durability off mid-flight, modules
  rebuilt on the events above.
