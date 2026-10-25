# Changelog

Myriad follows [semantic versioning](https://semver.org) for the public API (`dev.myriadclient.api`). The API names
Minecraft's own types, so addons are rebuilt for each Minecraft version whatever Myriad does; within one:

- Every 1.x release is **source- and binary-compatible** with the 1.x releases before it for the same
  Minecraft version: an addon built against 1.2 runs on 1.5. Core's build checks this against the last release.
- A move to a **new Minecraft version is a minor release**. It may deprecate what the new version made wrong, with the
  replacement in the javadoc, but removes nothing.
- **Removals need a major release**, and only of what a minor release deprecated first.
- A **patch** release never changes the API.
- What a 1.x release adds carries `@ApiStatus.AvailableSince` with that release.

Before 1.0 a minor release could also remove what an earlier one had deprecated.

`dev.myriadclient.impl` is internal and may change in any release. Addons declare the oldest core they support in `fabric.mod.json`
(`"depends": {"myriad": ">=1.0.0"}`) and can check `Myriad.isAtLeast("1.1.0")` for newer features.

## 1.2.0 (unreleased)

- **A 2b2t profile** (`AntiCheat.Profile.GRIM_2B2T`, picked for 2b2t.org): Grim's answers, but placements and item
  uses go without a swing, and a slot swapped in for one goes back in the same tick, the off hand's for an air placement
  too, so the item never shows in your hand and a tick can air place blocks of several kinds. Every module that places
  or uses through core follows it. Two new answers on every profile say so, `checksSwings()` and
  `checksPacketOrder()` (both true for the other presets: an unrecognised server gets the vanilla profile, and might
  check either). Measured on 2b2t with the Printer: no swings, the slot back within the tick, nothing refused. Swings
  stay with breaks and attacks. A hook matching `on(Profile.GRIM)` no longer matches on 2b2t; add `GRIM_2B2T`.
- **Builds place blocks from the inventory as fast as from the hotbar**: a block moved into the hotbar is placed in the
  tick of the click (the server takes the click first, and nothing it says about the slot can reach the client before
  the tick is over), where it waited a round trip, and up to three are moved a tick, where it was one. On 2b2t, a build
  whose blocks were all in the inventory went from 7 to 10 blocks a second to 20 (15 in 15 ticks), about what one
  with its blocks in the hotbar managed before.
- **Clicks against a block before air placement**: the click into the empty space itself is always nearer than its
  neighbours, so with Air Place on every block that could be air placed was, through the off hand, one kind of block
  a tick (and Grim refuses it). Now a click against a block comes first; with every block in reach in the hotbar, a
  burst of 9 goes out in one tick (it took 5).
- **A placement the server drops is placed again**: the server answers every action it handled from a tick's packets
  at once, so one sent in the same tick as an answered one and still unanswered was dropped (Paper's limit of 9 in
  300 ms, live on 2b2t). After a short grace the client's guess there (a block shown where the server has none) is put
  back to what the server last had, as its answer would have, and the placement counts as failed; the same when an
  action times out. A dropped placement at the end of a burst used to stay a ghost, and builds took it as placed.
- **A shulker box or ender chest from the inventory opens a round trip sooner** (`Containers.openItem`): it goes
  above your head where it can, a block up if there's room (a shulker box's lid opening into your head's space pushed
  you down), into the empty space with Air Place, else under a block over it (an ender chest only the first way, as it
  stays shut under a solid block), so it falls back on you when it's broken; else beside you as before. It's opened
  with the next click instead of once the server has answered the placement. Where looks are checked, that click
  waits for the movement packet after the placement: Grim takes a placement into its world only then, and a click on
  the block before it is a click on air there (AirLiquidPlace). One from the main inventory is brought into the hotbar
  in the tick it's placed, after the look there has gone out (a slot a click changed may be placed from only in that
  tick, else a round trip later), and what made room for it goes back through the box's own screen once it's open; an
  ender chest from a stack no longer waits half a second for its slot to empty. An ender chest is picked back up with
  a Silk Touch pickaxe from anywhere in the inventory, swapped in for the break, where one had to be in the hotbar and
  was held in view. On the Grim test server through 150 ms of lag, every kind opens in 270 to 300 ms (500 from the
  hotbar before, 1000 for an ender chest from a stack, 700 from the main inventory), no flags; on 2b2t, 150 to 220 ms
  after the click. `Placement.use` may now follow a placement at the same block that hasn't been answered; it goes
  after it.
- **A container can be held open after its screen closes** (`Containers.hold`, `held`, `reopen`, `release`, new): no
  close goes out when you close its screen, so the server keeps it open, and `reopen()` (or opening your inventory)
  shows it again at once with no packets. Your inventory's clicks, yours and modules', go through it meanwhile (the
  server only takes clicks in the container it has open), on its slot for the same item; a click on armour lets it go
  first. It ends when another container opens, the server closes it, or it's released.
- **Themed screens and hotbar** (Theme → Preferences → Cosmetics, both on by default): your inventory, chests,
  barrels, ender chests, shulker boxes, hoppers, dispensers, the crafting table, furnaces, smokers, blast furnaces, the
  anvil, smithing table, grindstone, brewing stand, crafter, stonecutter, enchanting table, villager trading, beacon,
  loom, cartography table, creative inventory and horse, donkey and llama inventories are drawn as a theme window, its surface, border and
  title with a cell for each slot and the slot under the mouse lit in the accent, with every tooltip (items, buttons)
  drawn as the desktop's are, the inventory's effect list as theme cards and the recipe book as a theme window (its
  recipe slots, tabs, filter, page arrows, search box and the button opening it); the hotbar, the off hand's slot and
  the experience bar a thin pill as the desktop's progress bars are (its level in the accent), hearts, food and armour
  the theme font's heart, drumstick and shield in their colours (a heart's by its kind, food green under Hunger), the
  hotbar and what sits on it lifted 3 pixels off the screen's edge. Each takes the place of the texture or sprite Minecraft draws,
  in its GUI pixels, so the items, slots, clicks and other mods' slot work stay as they were.
  The pause menu's buttons are theme buttons too. A third, Theme Font, sets chat and item counts in the theme's font:
  it's registered with Minecraft as `myriad:ui` (the theme's semibold sans, falling back to Minecraft's own, rasterised
  at the GUI scale and again when that changes), so Minecraft wraps chat and finds clicks on its links at the
  widths it draws. `ThemeSettings.themedScreens`, `themedHotbar` and `themeFont` are new; the bundled fonts moved from
  `assets/myriad/fonts/` to `assets/myriad/font/`, where Minecraft's font loader looks.
- **A box in an open chest opens too** (`Containers.openItem(owner, view, screenSlot, ticks, options)`, new): one of
  the chest's own (a barrel's, shulker box's, ender chest's, hopper's, dispenser's) is swapped into the hotbar through
  its screen and the chest closed in the tick it's placed; one of yours goes as before, the chest closed first. The
  session's `finish()` puts it back and opens the chest again, leaving it open: a box from the chest goes back into its
  slot there. With the hotbar full, what makes room for it waits in an empty slot of your inventory (two swaps in the
  tick), only with that full too in the box's place in the chest, and never the Silk Touch pickaxe an ender chest is
  picked back up with; an ender chest is left standing, saying where, rather than broken without one. A shulker box
  goes only a block above your head (its lid right over it pushes you down), else beside you. 300 ms on the Grim test
  server through 150 ms of lag, as from the hotbar. The dev console's `openitem c<slot>` opens one from the open
  container.
- **A block broken again by a finish alone waits for its tool from the inventory**, as a first break does: it broke
  with whatever was held, so an ender chest put down twice at one spot came back as obsidian (seen on 2b2t). And
  opening a container in the tick another closed no longer sends your inventory's close as well.
- **Trying a shared config turns on only what was on for the sharer**: its control panel took every module in the
  share, and a whole config lists every module, most of them off, so turning it on turned on everything you have. A
  share with none on (settings for modules you turn on yourself) still brings them all.
- **A panel keeps its place when it's rebuilt**: editing or resetting a theme value scrolled the Theme panel back to the
  top, as the new view clamped the scroll before its contents had been laid out.
- **High rounding keeps everything inside the window's corners**: title bars follow the window's curve (they stuck
  out past it, their own corners being no more than half their height), their icon and buttons start past it, and a
  panel's contents stay clear of the curve instead of drawing over it.

## 1.1.1

- **Item uses go out in their own tick when joined as 1.17 to 1.20.5**: ViaFabricPlus sends your position and look
  just before each use packet, as those clients did, and core took that for the tick's movement packet, so it held
  the use itself until the next tick (actions after the movement packet wait for the next tick, for Grim's sake). A
  use then reached the server after the movement it was made before: a water bucket under a fall arrived after the
  landing. Every right-click use (eating, a bucket, a rocket, a pearl) was a tick late this way.

## 1.1.0

- **`Tags.AUTOMATION`**, a shared tag beside Combat, Movement, Render, Player, World and Misc: modules that do a job
  for you on their own (eating, picking tools, walking, mending, sorting, building), in the theme's second accent.
- **`ItemHud.Durability.NUMBER`**: an item's durability as the uses left (1561 for a new diamond pickaxe), drawn by
  `ItemHud.numberText`, or `durabilityText` for whichever text a mode shows. `showsPercent` is true for it too.
- **A group window whose group its addon no longer declares is dropped** (a group renamed in an update) rather than
  kept forever as an orphan; the group under its new name gets a window of its own. A window whose addon is missing
  still waits for it to come back.

## 1.0.2

- **Shares carry wheels.** Pick them in the Share window or use `.share wheel <name>`: a wheel goes in with its name, icon
  and slots (its keybind only with keybinds). Taken in, it's added as a new wheel (its name made unique). A control
  panel's slot points at the panel the share brought, or one of yours with that name. A command's slot shows its
  command rather than a name the sharer gave it, since a share comes from anyone and a slot must say what it runs. The
  website's share page lists each wheel's slots.
- **The Share window reads as a few lines until opened.** Modules, Control panels, Wheels and Also (theme, keybinds)
  fold, each showing how much of it is picked; a code you look up shows its title and what it holds in one line
  ("12 modules · 2 wheels · a theme"), in yellow the addons it needs that you don't have, and for a share with no
  modules a single **Add it** button (there's nothing to try as a control panel, and none is made for one).

## 1.0.1

- **The Player preview stands as a player does.** Its cape hangs straight down and a worn elytra's wings sit slightly
  apart, as vanilla shows a player standing still: preview entities are never ticked, so the cape and wings kept their
  animation's starting values (the cape flung up, the wings shut). No preview shows a name tag any more (the stand-in
  had yours, drawn back to front).
- **Tooltips take the theme's look**: its frosted window background, rounding and border, instead of a fixed dark blue.
- **Scrolling over a closed dropdown no longer changes its value**, so scrolling down a module's open settings leaves
  them as they were.
- **Clicking a switch's name flips the switch**, as clicking the switch does.
- **Reset to Default** at the bottom of a module's settings (unfolded in a list, or in its own window) puts every
  setting back to its default after asking; the keybind and hold mode stay.

## 1.0.0

**The API addons can build on and keep.** From here every 1.x release is source- and binary-compatible with the ones
before it for the same Minecraft version: an addon built against 1.0 runs on every 1.x, and core's build fails on any
change that would break it. A new Minecraft version is a minor release that may deprecate but removes nothing; removals
wait for 2.0. What 1.0 is: modules, settings, events, hooks and jobs as the kernel; services that share one player
fairly between addons (rotations, inventory, placement, breaking, building, containers, flight), each Grim-safe from
`forServer()`; one render mode for every module; the window manager and its widgets; and the libraries addons would
otherwise each write. The docs site has the guide, the API map, the javadoc and the jobs pages.

- **Removed what earlier releases deprecated**, each after at least one minor release with its replacement:
  - `Category`, `Categories`, `Module(Category, …)`, `Module.category()`, `AddonContext.registerCategory`,
    `MyriadAddon.registerCategories`, `Myriad.categories()`, `ModuleRegistry.inCategory` and
    `ConfigShares.Builder.category`/`addonCategory`: modules have tags and addons declare groups (since 0.7).
  - `SettingGroup.intSetting`, `doubleSetting` and `enumSetting`: `integer`, `decimal` and `oneOf` (since 0.8).
  - `Tasks` and `Myriad.tasks()`: `Myriad.jobs().later` and `every`, and steps (since 0.8).
  - `AntiCheat.Mode`, `mode()` and `setMode()`: `AntiCheat.Profile` (since 0.8). `PacketLimits.windowMs()` (since 0.5).
  - `BoxStyle`, `HighlightSettings`, `HighlightEvent.Entity`, `HighlightEvent.Shapes` and `ChunkCache.highlight`:
    `RenderEvent`, `RenderSettings` and `ChunkCache.Builder.boxes` (since 0.11). `ChunkCache.setMeshesVisible`, which
    only served the old outline mode, is private: a cache built with `boxes` shows its meshes by the render mode.
    `HighlightEvent` keeps `Hand` and the `ENTITY_HIGHLIGHT` hook.

## 0.11.0

- **One render mode for every module: Shader or Box.** The new **Render** window (and `.render shader|box`, and a
  *Switch Render Mode* key) picks how all modules draw what they show: Shader traces each thing's exact shape with an
  outline, glow and fill; Box draws a box around it, edges and faces, cheaper on a slow GPU. Modules no longer have a
  Mode option of their own. Each module's Render options show the ones for the mode that's on (Fill, Fill Opacity and
  Through Walls in both; Outline Width, Glow, Gradient and the pattern's size for Shader; Line Width for Box), and its
  colours stay its own.
- **`RenderEvent`, `RenderSettings`, `RenderLook`, `RenderMode`** (in `api.event.events` and `api.render`). A module
  hands what it shows to `RenderEvent` once a frame (`entity`, `box`, `block`, `below()`, and `chunks` for a cache)
  with a look and a colour, and core draws it in the mode that's on: in Shader mode as highlights (an entity's exact
  shape, touching shapes of one look merged), in Box mode as boxes (an entity's hitbox, a block's shape bounds).
  `RenderSettings.builder(group)` adds the standard Render options (`defaults`, `lineWidth`, `throughWalls`, `named`,
  `noThroughWalls`, `alwaysShader`, `visible`); `RenderLook.of(style)` is a fixed look; `RenderSettings.migrate` carries
  a module's saved Box and Highlight options over. `ChunkCache.Builder.boxes(boxes, look)` keeps a cache's boxes on the
  GPU in either mode, made again by itself when the look or the mode changes. `PreviewEvent.render(look, color)` shows a
  preview's subject the same way. The `ENTITY_HIGHLIGHT` hook decides entities in Box mode too.
- **Fills are listed by name and shared.** `HighlightStyle.Fill.all()`, `byName(name)` and `name()`, and
  `Fill.custom(shader, name)`: an addon's fill shows in every module's Fill option, not only its own (a name already
  taken gets the addon's namespace after it). In Box mode every fill draws flat.
- **Boxes are simpler and sit cleanly on blocks.** World shapes are pulled towards the camera by a depth bias in the
  pipeline (about what vanilla gives block cracks), so a box face lying on a block's face doesn't flicker against it
  and nobody nudges boxes by hand. A box's faces are drawn from outside only, so a see-through box shows one layer of
  fill instead of its front and back faces stacked; `ShapeBuilder.quad` stays two-sided.
- **Deprecated, removed in 1.0:** `BoxStyle`, `HighlightSettings`, `HighlightEvent.Entity`, `HighlightEvent.Shapes` and
  `ChunkCache.Builder.highlight`, for the above. `HighlightEvent.Hand` and `ENTITY_HIGHLIGHT` stay.

- **Highlights cost the GPU less, most with a wide glow.** The outline and glow searched every pixel around the
  highlights out to the glow's reach, twice (along rows, then columns), at full size. Now the full-size search only
  reaches as far as the outline, and the glow comes from a distance field of its own at half size, or a quarter for a
  wide glow (radius past 16 pixels), whose soft falloff hides the difference: screenshots side by side, zoomed four
  times, show none. A frame with no glow skips the glow's passes. Measured with `.bench render compare` (old way, then
  new, alternated) on an RTX 3080 Ti at about 2 megapixels, 30 zombies and 40 chests in Outline mode: GPU time a frame
  from 0.84 to 0.33 ms with glow 24, 0.36 to 0.27 ms with glow 6, 0.22 to 0.18 ms without; a sparse scene (seven
  highlights) 0.62 to 0.26 ms with glow 24. With glow 24 the full-size searching (spread and composite) went from 0.80
  to 0.16 ms, and the glow's own passes take 0.14 ms, much of it the fixed cost every pass has on a fast GPU; a weaker
  GPU pays more for pixels than for passes, so it gains more. A tile map that skipped empty space was tried and left
  out: on this GPU its own passes cost more than it saved.
- `.bench render` (dev runs): GPU time a frame in each highlight pass, from timestamps (masks, spread, glow,
  composite, hand), with the frame rate uncapped while it measures; `.bench render compare` measures the old way and
  the new one back to back, and `.bench render old` switches to the old way for screenshots.

- **No Simulation flag when sprinting starts again just after a placement** (strict Grim, rotating placements while
  sprinting or sprint-jumping). Grim predicts the movement of a sprint start sent right after a block placement at
  walking speed; on a diagonal, which the movement fix presses while the look is turned to place, sprinting goes 0.03
  further than that, one flag that decays over the next few ticks. Such a start now waits one tick after a placement
  (only when the server doesn't have you sprinting already). Measured with Grim's per-tick debug against the packet
  log: every flagged tick in the printer's sprinting and jumping passes was such a start; after the change, six passes
  in a row with no flags (two to three flags a pass before).

- **The Keybinds window sets every bind, Minecraft's included, and has a search.** Global holds Minecraft's own controls
  (other mods' too) by its categories, changed and saved as in its Controls screen (one key each, so a bind's modifiers
  are dropped there); Myriad holds its actions, the menu's keys, core's modules (macros) and the wheels; each addon has
  a section of its own with its actions and modules. The search filters by name, a section's name (from three letters)
  shows all of it, and a key's name shows what's bound to it. Which sections are open is kept with the window.
- **The cursor shows a resize.** Over a gap between tiled windows, or a floating window's corner, and while dragging
  one (with the mod key and the right button too), the cursor is the resize arrow for that direction, through the
  game's own cursors (diagonals from GLFW's, the four-way arrow where the system has none).

- **Previews.** `Preview` (in `api.ui.widget`) shows something in 3D so changing a render setting shows what it does:
  `Preview.entity(type)`, `player()` (you, with your armour and what you hold), `block(block)` (chests and shulker
  boxes as they stand), `item(stack)` and `viewModel()` (your first-person hands and held items, as the camera sees
  them, View Model's changes included). Drag to turn it, right-drag or Shift-drag to move it, scroll to zoom,
  double-click to put it back. `decorate((shapes, box, entity) -> ...)` draws on it with the same `ShapeBuilder` calls
  as in the world, in the subject's coordinates. A module shows one at the top of its
  settings window through `Module.preview()` (null by default); any window can hold one. Drawn through a
  picture-in-picture renderer of its own (registered with Fabric's), entities made once per world and never added to
  it; a subject the game can't draw out of the world shows nothing rather than failing the frame. `Canvas.preview`
  draws one by hand.
- **Modules draw on previews as in the world, shader highlights included.** `PreviewEvent` (posted each frame for
  each preview drawn) gives the subject (`entity()`, `block()`, `viewModel()`), its `box()` and `shapes()` for boxes
  and lines; `render(look, color)` shows it in the render mode that's on, as `RenderEvent` does in the world (the
  preview's outline pass draws into a mask of its own and the highlight passes composite onto its texture), and
  `shown()` says whether a handler before yours took it. Your hands are highlighted through `HighlightEvent.Hand` as
  in the world. Preview entities stand where you are, so distance checks count them as near. A preview shows what the
  modules that are on draw, the module's own settings window included (it says to turn the module on when it's off).
- **Previews sit on the window's background** instead of a layer of their own.
- An open dropdown's list keeps the dropdown's own colour, with only its header lighting up on hover (the whole list
  changed colour with the header), and a searchable list (a mob, a block) opens on one panel in that colour behind its
  search and its entries, rather than a fainter box behind the entries alone.
- **Preview windows.** Add a Preview from the launcher to any workspace to watch a mob, you, a container, a block or
  your hands while tuning how things are drawn, without closing the menu to look at the world. The picker at the top
  chooses what it shows (and which mob, container or block), saved with the window; the preview fills the rest, and the
  picker's lists open over it, on the window's own background, so it neither shrinks nor disappears while you choose.
- The Player preview shows a stand-in with your look (your skin and its layers, armour, what you hold, main arm) rather
  than you: modules leave you yourself out (ESP colours you only as Self, in third person), so they now draw on it as
  on any player.
- A block is shown as it stands in the world, from its block model (a hopper's item is a flat icon), chests and shulker
  boxes through their own renderers; other items still show as their item model. The view model is a peek at what
  you'd see without the menu in the way: your items, or your arm when your hand is empty, with your field of view and
  lit as in the world.
- Several previews on screen at once each keep their own picture: the GUI gives a kind of picture one renderer and a
  renderer one texture, so the preview renderer hands each preview a picture of its own (closed two seconds after its
  preview stops being drawn). Turning and moving a preview by dragging works in every window (the window passes no
  movement with a drag; the preview measures it from where the mouse was).

## 0.10.1

- **A sort never stops on a click you didn't make.** Letting go of a mouse button in a container screen made vanilla
  click the slot under the mouse whenever the cursor held something, to put it down, and while a sort runs the cursor
  holds the sort's stack (shown empty): the release of the right-click that opened the chest, or of a middle-click
  sort, dropped that stack there and counted as your click, stopping the sort ("You clicked in the container").
  Releases now do nothing in a screen while a sort runs in it.
- **No stack left showing on the cursor after a stopped sort.** An answer the server sent before the sort put its stack
  down could arrive after and show it back on the cursor, with nothing to correct it; a stopped sort now asks the
  server for the menu once it's put down and ends only once that shows the cursor empty (putting it down again if not).
- **Inventory clicks wait for the server's answer to the last click on the same slots.** With lag, an answer to an
  earlier click can still be on its way and show a slot's old contents; a click made on that undoes what the server
  already did. With the test server answering every click as 2b2t does, Offhand swapped the totem in, saw the off hand
  empty again, and swapped it back out, over and over. Moves, swaps, the off hand swap, shift-clicks and drops now wait
  as borrows and builds already did, and that wait is the slowest of the last few round trips rather than the median,
  which jitter often beat.
- Tested with shulker boxes in the mix (they don't stack and each carries its contents): sorting a double chest of 20
  boxes and part stacks with Auto Sort and by middle-click, with boxes in your inventory, and your own inventory, every
  box coming out with its own contents and nothing on the cursor, through a 200 ms (each way, 60 ms jitter) proxy with
  the server answering every click: all pass.
- Dev console: `closescreen` closes whatever screen is open.

## 0.10.0

- **Building leaves your hotbar as it was.** A build (the Printer, Portal Builder, any `Steps.build`) moved blocks into
  the hotbar for good, so a print ended with the hotbar full of building blocks. It now remembers the inventory when it
  first moves something, and once no build is running puts every hotbar slot it used back, one click a tick, each
  block to the inventory slot it came from where that's free. It also never puts a block in the slot you have
  selected or the one the server holds, so what's in your hand doesn't change, and with a full hotbar it no longer
  falls back to your selected slot. In the suite's Printer runs, the hotbar and off hand come out as they went in
  every time.
- **No misplaced blocks with lag.** With a round trip of a few hundred milliseconds, a placement could put the wrong
  block down (stone where glass belonged, a log for a slab): the server's answer to an earlier placement arrived after
  a block had been swapped into that hotbar slot and showed the slot's old contents again, the placement went out on
  that view, and the server, finding the client's inventory wrong, sent the whole inventory back, which arrived late in
  turn. Now nothing is placed from a hotbar slot a click changed less than a round trip ago (the time the server takes
  to answer your actions, `Server.ping`), and nothing is moved into a slot clicked or placed from less than a round trip
  ago. Without lag that's a tick, as before. Printing a row of 24 mixed blocks with 2b2t's settings through a 120 ms
  (each way) proxy: walking 15 right and 7 wrong before, 23 right and none wrong now; sprinting 14/6 before, 20/0 now;
  sprint-jumping 14/8 before, 15/0 now (the rest weren't placed in one pass, as a slot now waits for the server).
- **Builds work with a full hotbar, and you don't see them use it.** With every hotbar slot holding something of yours,
  a build stopped with "no slot" (Portal Builder said to free one). It now borrows any slot but the one you hold, and
  what was there goes back when the build is done. While a build has a block in a slot, the hotbar shows what was there
  before, so its moves don't flicker across your hotbar. Things a module borrowed (Middle Click's pearl) and a build's
  blocks keep out of each other's slots.
- **Air placement leaves your off hand as it was, and it doesn't show.** Each air placement swaps the block into the off
  hand and back the next tick. With lag, the server's answers to the swap arrive after the swap back and showed the
  block in your off hand again (on servers that answer at odd moments, for good); and a swap back an anti-cheat drops
  would leave the block there on the server while you see your totem. The hands now show what they held until the
  server has answered, and once a run of air placements is over the inventory is asked for once (a click that changes
  nothing) and the block, if it really is in the off hand, is swapped back with what was there. Through a 120 ms
  (each way) proxy, sprint-jumping past the suite's 24-block row with 2b2t's settings left a block in the off hand in
  3 of 3 runs; now 0 of 3, with every block right.
- **Clicks made on a view the server hasn't answered yet wait.** Borrows, builds and the hotbar put-back now wait a round
  trip after a click changed a slot before acting on it, anywhere in the inventory (not only the hotbar): with lag, an
  item could be found where it no longer was and borrowed twice, swapping two of your hotbar items. Without lag that's
  a tick.
- **The ask for the whole inventory no longer goes out mid-sprint.** It checked that a click could go (with a sprint
  stop first) but didn't send the stop, so Grim's MultiActionsC flagged it while sprinting.
- **Sorting shows its result at once, with nothing jumping about.** While an arrangement that only rearranges a
  container (Auto Sort) runs, the container shows the finished layout and the cursor shows nothing, and the clicks go
  out behind it. 2b2t answers every click, so the later clicks of a burst carry an old state id and the server sends
  the whole container after each; those arrived a few clicks behind and replayed the sort back and forth with items
  on the cursor. A click of yours stops the sort and shows what's really there (the click is dropped: it was aimed at
  the sorted look). Grim and Paper have no click-rate check, but the click budget stays at 16 a second until there's
  word on 2b2t's: a scrambled double chest is sorted on the server 5.2 s after opening, and looks sorted from the first
  frames.
- **`HighlightSettings.Builder.sharing(BoxStyle)`**, so a module that draws boxes or outlines by mode keeps both
  styles in one Render group: the outline uses the box's Fill Opacity and Through Walls instead of a second pair (two
  settings of one name can't share a group), each shown while either style needs it. The site's Rendering page says
  how modules lay out their Render group.
- **`Building.Options.withOrder(Building.Order)`**: `BOTTOM_UP` (as before: lowest first, then nearest) or `NEAREST`
  (nearest first whatever the height, for building what's beside you as you walk past). Either way, blocks the click
  doesn't shape go before the ones it does, so those have neighbours to go against.
- **Sorting never leaves an item on your cursor, with lag or a click of yours.** Through a 120 ms (each way) proxy a
  sort lost 3 ender pearls in one of 6 runs: the server answers every click of a burst with the whole container a few
  clicks behind, and a click planned on that view acted on slots that weren't what it showed, leaving a stack on the
  cursor. An arrangement now waits a round trip after each burst before planning the next (the click budget spaces
  bursts about that far apart anyway, so a double chest still takes 5.5 s). A click of yours in the container stops it
  after it puts down what it holds on the cursor (where it picked it up, else the first empty slot), and is dropped;
  a sort that ends with something on the cursor puts it down too. Through the proxy: 21 of 21 sorts with nothing lost
  and the cursor empty, a click mid-sort included.
- **`Inventory.arrange(Containers.Arrangement)`** arranges your own inventory as a container's view does (sorting it,
  say), with its screen open or none: the finished layout shown while it runs, stopped by your click, paced for lag.
- Dev console: `cursor slot <n>` moves the mouse over a slot of the open container screen, whatever the window size;
  `status` says what's on the cursor and which screen is open.
- **`.packets log` shows inventory clicks** when you join through ViaFabricPlus as 1.21.4 or older: it sends those
  clicks itself, past the connection, so they were missing. They're logged as the game makes them.
- **The colour picker's hue bar works on white and black.** With the square's marker in its top-left corner (white)
  or along its bottom (black), a hue you dragged snapped back to red as soon as you let go, since those colours have
  no hue to read back; the picker now keeps its own hue and saturation while they still make the colour.
- **How long each addon took to start, in the Addons window.** Each card says "Started in 55 ms (initialize 55 ms,
  postInitialize 0.0 ms)", registering its tags counted with `initialize`; a failed addon shows how long it ran before
  failing. In the example addon's dev client: core 21 ms, Essentials 55 ms. The log's warning for a phase over 250 ms
  stays.
- **A Gradle plugin for addons: `id 'dev.myriadclient.addon' version '0.10.0'`.** Released with core, under its
  version, from Myriad's maven. It applies Loom and sets up what every addon's build repeated: Myriad's maven (for
  `dev.myriadclient` only); Minecraft, Fabric and Myriad at the versions that release was built with (each overridable
  in `gradle.properties`); Essentials on the dev client when `essentials_version` is set; the dev client's switches
  (`-PopenDesktop`, `-Photswap`, `-PrunDir`, `-PquickPlay`, `-PquickPlayServer`, `-Pusername`, `-PscreenshotEvery`,
  `-PauditMixins`) with dev checks and the dev console on; `checkApiOnly` before every compile; Java 25, a sources
  jar, `fabric.mod.json`'s `${version}`, `${myriad_version}` and `${minecraft_version}`; a maven publication, and
  `publishAddon` (build and test, then publish to Myriad's maven).
  `-PcoreDir=../myriad` takes the plugin from a core checkout as well as core's jar, so unreleased changes need no
  publishing step. The template's build file is now 14 lines (from 70); Essentials, Automation and Stash Hunter build
  on it with the same jars as before. Core itself builds with Loom 1.18.4, pinned, which the plugin brings.
- **The developer guide moved to the site.** The README keeps the product, install and building, with a short
  "Writing an addon" and links; everything its 475-line guide said is on myriadclient.dev/docs/develop, including the
  conventions (on the addon page), the full helpers table, and a new page on how the pieces fit bigger addons. The
  latest release's javadoc is at myriadclient.dev/api, refreshed by the site's rebuild after each release, and the API
  reference map shows the four tiers (kernel, systems, UI, libraries). Examples that still used categories now use tags
  and groups.
- **The versioning policy for 1.0, written down** (above, in the README and on the Compatibility page): 1.x releases
  are source- and binary-compatible for the same Minecraft version, a new Minecraft version is a minor release that may
  deprecate but removes nothing, removals need a major, patches never change the API; until 1.0 a minor may also remove
  what an earlier minor deprecated.
- **The API is checked against the last release on every build.** `checkApi` (part of `check`) compares
  `dev.myriadclient.api` with the last published core (`api_baseline` in `gradle.properties`) using japicmp, fails on
  a binary-incompatible change (a removed or changed method, a class made final...) unless the version is a new major,
  and writes the full report of what was added and changed to `core/build/reports/api` (CI keeps it as an artifact).
  Checked: removing a method fails the build and names the type; 0.9.0 against 0.8.0 reports 8 types added, 7 changed,
  nothing removed.

## 0.9.0

- **Highlights for your first-person hand and held items.** `HighlightEvent.Hand` asks about each part of the view
  model as it's drawn: `part()` (`ARM` or `ITEM`), `hand()`, `side()`, `item()`; `highlight(style, color)` as for
  entities. Hand highlights are drawn over the hand, after the world's (so the hand never hides them nor they it), with
  every `HighlightStyle` option: outline, glow, fill (custom fills too), gradient. An arm and the item it holds
  highlighted alike get one outline around both. Vanilla only draws a bare arm while the main hand is empty.
  - How: the hand's parts are submitted with their highlight id as the outline colour, and the outline pass vanilla
    skips for the hand runs into a mask of the hand's own (vanilla's entity outline target is still in use then).
    Spread and composite are scissored to the hand. While nothing listens, a hand part costs one flag check. Uncapped
    in the dev client, a sword and shield highlighted with an 8 px glow: 479 fps against 476 fps off (about 2.1 ms a
    frame either way, within the noise).
  - With an Iris shader pack on, Iris draws the hand inside the level in passes of its own and skips vanilla's: hand
    highlights are asked about in those passes (with Iris's hand projection) and drawn where vanilla's hand would be,
    over the pack's finished image. Tested with Iris 1.11.4, Sodium 0.9.2 and Complementary Unbound r5.9.3. The hook
    (`compat.iris.IrisHandRendererMixin`, by class name, so core still doesn't need Iris) only applies with Iris
    installed.
- **`HighlightSettings.builder(group)`**: `.defaults(style)` starts the options from another look (Packet Mine's
  solid fill with no outline, say), `.noThroughWalls()` leaves out an option that means nothing for what's
  highlighted (the hand), `.visible(...)` as before. The constructors are unchanged.

- **Settings keep their values through every round trip.** A table test (`SettingRoundTripTest`) puts each setting
  type through JSON both ways, `encode`/`decode`, `.set` with what it shows, bad JSON, and a save that keeps keys it
  doesn't know. It found five bugs, now fixed:
  - `enumSet(name, type)` with no default crashed when built (the empty default was copied before the type was known).
  - Decimals showed in the player's locale ("2,50" in German), which `.set` didn't take back. They show with a dot
    everywhere.
  - An empty enum set showed "none" and refused it. `.set` now takes "none".
  - A string list showed "a, b" but only took `add`, `remove` and `clear`. It now also takes the whole list.
  - Block positions, module lists and registry lists threw on JSON of the wrong shape (an object where a string
    belongs) instead of keeping their value. A profile still loaded, since `Settings.fromJson` catches per setting,
    but `decode` passed the exception on.
- **A test harness for core's services** (tests only, nothing ships): core's tests run the real client code in the
  game's own classes, with a fake level and connection, and check the packets the services send against what Grim
  checks: placement, rotations, inventory clicks and swaps, breaking, attacks, containers, No Durability, jobs and the
  deprecated `Tasks`. The Grim suite in Myriad Essentials retries its known flakes once, alone, at the end.
- **Core's largest classes split along what they already did** (internal, no API change): the inventory, breaking,
  placement and No Durability services, the commands, the desktop, building, and the highlight renderer. `./gradlew
  check` warns about impl files over 600 lines and methods over 60; no file is over now without a comment saying why.
  Behaviour is unchanged: core's tests and the Grim suite (40/40) pass on the split code, and the desktop and every
  kind of highlight (entities, shapes, retained meshes, the hand) were checked in a dev client.

- **`Modules.guard` for code mixins run.** An event handler that keeps throwing is turned off by the bus, but a mixin
  calls straight into its module from the middle of vanilla's code, where an exception crashed the game. Now
  `Modules.guard(module, () -> ...)`, or `Modules.guard(module, () -> answer, fallback)` for code that answers, runs
  it, logs the first few failures with their stack trace, and after 50 turns the module off with a notice. The count
  starts over when it's turned back on. Out-of-memory errors still go through. The dev self-test counts these
  failures against the module under test. The mixin pattern in the docs and the addon template now uses it:
  `if (m != null) Modules.guard(m, () -> ...)`.

- **Safe mode after a crash loop.** When the game crashes twice in a row within a minute of starting, the next start
  has every module off, as `.panic` leaves them, and a window lists the ones that were on with **Turn them back on**.
  A `.running` file in `.minecraft/myriad/` tracks it: written at start, marked once the game has run a minute, and
  removed when the game closes normally. A game closed by force in its first minute counts as a crash. Mirrors of
  other mods' modules are left as they are.
- `-PopenDesktop` (and `-Dmyriad.openDesktop`) open the desktop again with Myriad's own title screen on.

- **Which thread your code runs on, written down.** A new Threads page in the docs lists where each event fires. Every
  service is render thread only and marked `@RenderThread`. `Modules`, `Packets`, `Notifications` and the event bus
  are safe from any thread and marked `@AnyThread`, as are the events that can fire elsewhere: `PacketEvent.Send`
  and `.Receive`, `BlockRenderEvent`, `ItemTooltipEvent`.
  - `@Subscribe(thread = HandlerThread.RENDER)` runs a handler on the render thread. An event posted elsewhere (a
    received packet) reaches it at the start of the next tick, too late to cancel and skipped if cancelled meanwhile.
    For packet handlers that act on the game. Posting costs the same as before: 26.1–26.4 ns to five handlers before,
    25.2–27.2 ns after, within the noise.
  - With `-Dmyriad.dev` (set by the dev clients and core's tests), the services' entry points log a call from another
    thread, with the stack, once per call site (`Threads.assertRender()`). The Grim suite logged none.

- **Jobs you can see.** Every running job, from any addon, in one place:
  - The **Jobs** window lists them by the module that started them, with the step each is on, its live status, why
    it's waiting when it's stuck, a progress bar and how long it has run, and the steps under it; **Pause**/**Resume**,
    **Cancel** and **Cancel all**. A job that ended stays listed for ten seconds with how it ended.
  - The **Jobs** HUD element: a line per job ("Printer Printing · 142 left · stuck: no item: cobblestone"), with how
    many to list and whether to keep ended ones a moment.
  - `#job` in the launcher lists the running jobs (picking one opens the window on it) and the window.
  - `.jobs` lists them with their ids; `.job pause|resume|cancel <id|module>` and `.job cancel all`.
  - A job stuck for five seconds says so once, in a notice that replaces itself; again only after it got going and
    stuck again. Not for being out of reach, which moving puts right and a printer waits on whenever you're away.
- **More steps**: `Steps.walkTo(pos)` and `walkNear(pos, radius)` walk there with Baritone (failing as `NO_PATHING`
  without it, `BLOCKED` when it finds no way; pausing the job pauses Baritone). `Steps.fly(flight, f -> keepFlying)`
  flies a `Flight`, steered once a tick until it should land, and is done once it's down; the landing is heard as it
  happens and the flight stopped there (left running, a flight that lands in water takes off again in the same tick).
  `Steps.acquire(what, count, sources)` makes sure you hold enough, trying each `MaterialSource` in turn (a container
  at a place, the shulker boxes you carry, or your own) and stuck on `NO_ITEM` once they're spent.
  `Baritone.pathNear(pos, range)`.
- **`Build.owner()`**: who started a build. A build from `Steps.build`/`Steps.keepUp` belongs to the step's job, so
  the module that started the job finds it in `Myriad.building().running()` (to draw its plan).

- **`.packets log on [all | words]`** writes every packet both ways, with its fields, to
  `.minecraft/myriad/packets/<time>.log` until `.packets log off`: sent ones as they leave (a cancelled one isn't
  sent, so isn't logged), received ones as they arrive, before any module sees them. Chunk, entity-movement, sound and
  similar received packets are left out unless you add `all`; words keep only packets whose names contain them.
  Nothing listens while it's off. The developer docs' Testing page has the recipe for checking an addon against a local
  Grim server, as Essentials does; the addon template's dev client takes `-PquickPlayServer` and `-Pusername` for it.
- **Profiler hints.** The Profiler window marks an owner with `*` and lists a hint under the table when its numbers
  point at one of core's tools, and `.profiler` prints the hints too:
  - a handler reading over 5,000 blocks a second through the level (250 a tick): a `ChunkCache`;
  - over 2 ms a second in render handlers: a `TickCached`;
  - a job whose tick ran past its 2 ms budget: its steps stopping when `ctx.budget()` runs out.
  Running jobs have rows of their own ("Job Owner › job"), and `.profiler` shows the blocks a second each owner reads.
  Reads are counted on the render thread, inside the handler that made them, only while profiling. The count is a
  return-value hook on `Level.getBlockState`: a block read measured 24.9–26.5 ns without it and 25.9–26.8 ns with it
  (three runs each, within the noise).
- **Hook calls allocate less.** A hooked call worked out who it was for (the caller's id, class, addon, the profile and
  server) on every call; recent callers' answers are now kept (weakly, so a job as caller isn't held). A call from a
  module: 48–63 ns and 88 bytes before, 38–43 ns and 40 bytes after (the call handed to the contribution); from any
  other object: 36–57 ns, 88 bytes before, 23–26 ns, 40 bytes after. Sending a service call to an addon's scoped
  replacement allocated 80 bytes per call and now none, in about the same time (`HookCallsTest`).
- `ChunkCache` spreads its work over ticks with `TickBudget`, as jobs do. Its budget and order (nearest chunks first)
  are unchanged.
- **`.bench`** (dev runs, `-Dmyriad.dev`): what core's hot paths cost on your machine, each run warm (the second of two
  passes), as the mean time and bytes per call. The addon template's dev client sets `-Dmyriad.dev` now. 0.9.0's
  numbers on the development machine, three runs:

  | | time | allocated |
  |---|---|---|
  | post to 5 handlers | 21.0–26.8 ns | 0 B |
  | hook call, not hooked | 2.8 ns | 0 B |
  | hook call, hooked | 20.8–22.0 ns | 40 B |
  | a block read through the level | 25.9–26.8 ns | 0 B |
  | `BlockScan` over a chunk (stone) | 245–247 µs | 16 B |
  | `Blueprint` positions in reach (18,432 positions) | 1.60–1.61 µs | 16 B |
  | placement: solve a 5 by 3 wall (15 solves) | 23.9–26.4 µs | 36 KB |
  | job tick, 50 steps | 453–704 ns | 16 B |

## 0.8.0

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
- **`Client`: the game's player, world, connection and interaction manager, never null.** `Client.player()`,
  `level()`, `connection()` and `interaction()` return the game's or throw an `IllegalStateException` naming the
  method that asked outside a world ("No player outside a world: KitBuilder.round asked for it"), so a handler that
  forgot `@Subscribe(inGame = true)` fails with a reason instead of a `NullPointerException` somewhere later;
  `Client.inGame()` for code that also runs on the title screen. Automation's tick handlers moved onto
  `inGame = true` and lost their null checks.
- **Holding a module off while you work.** `Modules.suspend(owner, module)` (also by class, or by id for another
  addon's module) turns a module off and keeps it off while any owner holds it; `Modules.resume(owner)`, or the owner
  module turning off, lets go, and the module comes back on with its last suspender if the player has it on. The menu
  shows "suspended by Search" on its row, a player turning it on meanwhile only takes effect afterwards, and the
  profile saves it as the player has it, so a crash mid-search no longer leaves Elytra Fly saved as off.
  `Module.isSuspended()`/`suspendedBy()` for UIs; Essentials' Module List keeps suspended modules listed, faded.
  Stash Hunter's Search holds Elytra Fly and Auto Armor this way instead of turning them off and on by hand.
- **Jobs: long-running work you can see.** Opening a chest and taking what's needed, building until done, filling a
  box: every addon was writing these as state machines on tick or chains of futures, each a different shape, none of
  them able to say what it was doing. Now the work is a `Step`, a value that describes it, and `Myriad.jobs().start(this,
  step)` runs it as a `Job` with a state, a reason when it's waiting, progress, and children.
  - **Stuck is not failed.** A step reports `running`, `stuck(why)`, `done(value)` or `failed(why)` each tick. A build
    waiting for cobblestone is stuck: it carries on by itself when cobblestone arrives, and a `whenStuck` remedy can
    fetch some meanwhile. `Why` is an open set of codes (`Why.NO_ITEM`, `OUT_OF_REACH`, `NO_ROOM`... or an addon's own)
    with a detail and a subject; `Why.of(Placement.Check, pos)` and `Why.of(Build.Status, pos)` translate the
    services' answers, and `Why.of(throwable)` reads a failed job's reason back from a future.
  - **Composing:** `then` (passing the result on), `sequence`, `all`, `race`, `forEach`, `when`, `choose`, `repeat`;
    and policies that cover a step and everything under it: `timeout`, `stuckFor`, `whenStuck`, `recover`, `retry`,
    `require`, `suspending` (holds modules off while it runs), `finallyDo`, `onDone`, `onFailed`, `peek`. A step's
    `toString()` is its tree; policies show as pills on the job they wrap, and a retry starts over inside the same job.
  - **The services as steps** (`Steps`): `place`, `use`, `breakBlock`, `lookAt`/`face`, `selectSlot`,
    `ensureInHotbar`, `borrow`, `openContainer`, `take`, `put`, `arrange`, `closeContainer`, `untilClosed`, `build`,
    `keepUp`, `suspend`. Each passes the job as the owner, so cancelling a job cancels what it asked for and two jobs
    of one module don't cancel each other's. Your own steps implement `Step.Run` (one method, `tick`); `JobContext`
    gives them the job, a `TickBudget`, events delivered on their tick (`ctx.on`), progress and a status line.
  - **The runner** ticks every job once a tick in `TickEvent.Pre` at the new `Priority.JOBS` (990): after actions held
    from the last tick go out, before the services act, so what a step asks for goes out that tick. A sequence moves
    through steps that finish at once in the same tick. Turning a module off cancels its jobs (`OWNER_OFF`), leaving the
    world cancels all of them (`LEFT_WORLD`), and a root job that fails with nobody listening shows a notice.
    `JobEvent.Started`/`Changed`/`Finished` go on the bus. Measured (unit level, no game): an idle runner 11 ns a tick,
    one running job about 70 ns a tick, a 50-step sequence that runs to the end each tick 4.4 µs (86 ns a step).
  - `TickBudget` (`api.util`): "as much as fits in 2 ms, then carry on next tick", for jobs and loops of your own.
  - **`Tasks` is deprecated** (still working, gone in 1.0): `Myriad.jobs().later/every` replace its delays and
    repeats (they run at a tick's start, tasks at its end) and a step replaces a task sequence. Core's macros run as
    jobs; Automation's Kit Builder gathers, fills and delivers as one (`.invtest kit` 2/2 kits in 347-459 ticks,
    as before).
- **Shulker boxes and ender chests from the inventory.** `Containers.openItem(owner, index, ticks, options)` puts the
  box down beside you (sneaking, so a chest underneath isn't opened instead; turned first where the server checks
  looks), opens it, and completes with an `ItemSession`: its `view()`, `where()`, and `finish()`, which closes it,
  breaks it (with the Silk Touch pickaxe in your hotbar for an ender chest, the slot you had selected coming back
  after), picks it up (stepping towards it if it bounced away) and puts it back in the slot it came from. Getting it
  out is a job of the owner's; putting it back is Myriad's own, so a box is never left in the world because a module
  turned off or its work failed, and one put down but not opened is picked back up too. Failures say why: `NO_ITEM`
  (no box, or no Silk Touch pickaxe), `NO_ROOM` (nowhere beside you), `SERVER_REFUSED`, `TIMEOUT`.
  `Steps.inItem(index, view -> step)` does the whole round trip as one step. Essentials' Inventory Tweaks opens boxes
  with it (its own copy went: 161 lines to 55), and Automation's Shulker Load, Shulker Unload and Kit Builder too, its
  `ShulkerSession` deleted.
- **Fixed:** a crash ("Texture view Sampler1 has been closed") when the game window was resized while a Myriad window
  with a blurred backdrop was showing: the blur's textures were resized in place between the UI recording its draws
  and drawing them. They're replaced and the old ones freed a few frames later. (Seen as a dev client crashing when a
  tiling window manager made room for another window.)
- Every root job that fails or is cancelled says why in the log ("Kit Builder › Kit 1 ended: no room: the output is
  full"): its notice is a toast, gone a moment later.
- **Blocks placed facing the way they're meant to.** `Placement.solve(pos, wanted, slot, options)` works out the click
  that makes `wanted` come out at `pos`: the face, the point on it, the look, and whether the server is to see you
  sneaking (placing against a chest or a repeater without opening or clicking it). It tries every face at a few points
  the way the game itself would place the item there, so facing, half, axis, hinge, attachment and rotation come out
  as asked, and on a block that already has fewer of itself (candles, layers, a slab's other half) it adds one. The
  answer is a `Placement.Solution`: a `Placement.Click` to hand to `place(owner, click, slot, options)`, or a `Check`
  saying why not, with `WRONG_ANGLE` new for "no click from where you stand". Where the server checks looks the click
  turns first and is checked once more against the look the server really has before it goes, so a look that came out
  a hair different can't place the block the wrong way. Click points follow blocks' real outlines (`Reach.hitFor`,
  `clickTargets`, and the new `Reach.clickPoints` and `Reach.canSee(hit)`): a slab's top is half way up.
  - **Aimed clicks.** Some facings no look on the block gives (an observer facing you from below, say): those are
    clicked with a clean rotation and looked at in the next movement packet (`Click.aimed()`), since Grim checks a
    placement at the next movement packet, against that packet's look and the one before. Grim decides at once, with
    only the look before, if a slot change or a reply to one of its pings reaches it first (it did when a build broke
    something in the same tick, and Grim then flagged every placement after). So after an aimed click the rest of that
    tick's actions wait for the next tick, the breaking service stays out of that tick (a break started then would be
    counted a tick early: FastBreak), and an aimed click the server refuses, or a slot change or ping reply that got
    out anyway, turns aimed clicks off until Grim's own count has run down.
  - **Blocks that face where you look wait for your head.** The server takes an observer's, piston's, dispenser's or
    crafter's facing from your head's yaw, and turns your head to your look only on its own tick of you: a turned
    click that reached it in the same server tick as its look came out facing the way you looked before (in
    singleplayer every such block of a session, or none, depending on how the client's and server's ticks line up).
    Such a click now waits a tick longer unless the look before gives the same block.
  - **`Building` places every block through `solve`.** `Target.exact(state)` asks for a state as near as clicks make
    it: placed the way it faces, as many of it, and clicked into its setting by hand (a repeater's delay, a
    comparator's mode, a note, open or shut, a lever), for printing schematics; `Building.Options.withClickSettings(false)`
    leaves settings be. Blocks the click doesn't shape go first, so oriented ones have more to be placed against; then
    bottom up and nearest first. `Build.Status` gains `NO_SLOT` (every hotbar slot holds something the build doesn't
    use), `REFUSED` (the server refused it a few times; tried again later) and `UNSUPPORTED`.
  - `Steps.placeState(pos, state, slot, options)` makes one block a state as a step: solved placement, one more where
    it takes several, then its setting.
  - Myriad Automation's Printer and Portal Builder build on this, and its own engine (`BuildJob`, `ClickSolver`,
    `StateRules`, `BlockShapes`, `TargetWorld`, `Clicker`) is gone. Its acceptance run places 236 states of 31 tricky
    blocks, standing still. In singleplayer, strict 141 to 143 right and 1 wrong over three fresh sessions (the old
    engine 133 and 4), lenient 210 and 1 (193 and 4), air placement 197 and 3 (160 and 13), lenient air placement
    235 and 1 (236 and 0); the one wrong is a fence gate that would swing round if opened from where you stand. On
    the Grim test server with strict options: 140 to 141 right, 1 wrong (the old engine 138 and 1), as 1.20.4 143
    right, on crafting tables (every click sneaking) 141 to 142 right, all with no flags. Planning a 40x12x40 blueprint with 658
    positions in reach (`.buildbench`, warm): 33 µs a tick with everything built, 47 µs with everything blocked, 114 µs
    with nothing to build with (the old engine 20, 58 and 104), allocating 144 to 240 bytes a tick (13 KB with
    nothing to build with before); finding clicks stays capped at 1 ms a tick.
- **Fixed:** `Placement.Forwarding` didn't hand on `use`, so while an addon's replacement of placement was in effect
  every hand click (a lever, a repeater's delay) was refused as unavailable. A test now checks that every service
  router and forwarding class hands on every method.
- **Standard settings for acting.** `RotationSettings` (Turn Speed), `PlacementSettings` (Range, capped at what the
  server allows, and Air Place), `BreakingSettings` (Mode: Packet, or Fast for every block at 70% of its time, which
  past Grim means the decoy starts; Double Break with Fast) and `BuildingSettings` (those four, Actions Per Tick and
  Break Wrong Blocks, the break settings shown while it's on) add the same settings with the same names to any
  module and give the service's options built on `forServer()`, as `TargetSettings` and `BoxStyle` already do for
  targets and looks. Essentials' Kill Aura, Packet Mine, and Automation's Printer and Portal Builder use them; their
  saved values carry over (Packet Mine's Fast switch becomes Mode Fast).
- **Flights that fly themselves.** `Myriad.elytra().altitude(owner)` and `control(owner)` give a `Flight` steered by
  a heading: Altitude's dive-and-climb cycle that holds its height without fireworks (about 27 blocks a second, 18
  with No Durability on Grim), and Control's straight flight at a set speed where the glide isn't simulated, which
  takes off by itself. Both keep No Durability (as the owner says), the mid-air catch, Preserve, a spare before the
  worn elytra can't last the way down, slowing for unloaded chunks and riding out setbacks; `land()`, `onLanded(why)`,
  and `state()`/`why()` (`CLIMBING`, `CRUISING`, `LANDING`, `DOWN`, `STUCK`) say how it's going. Essentials' Altitude
  and Control are now settings over them, and Stash Hunter's Search flies them instead of its own copy (518 lines
  gone). On the Grim test server Altitude flies as before: 605 blocks in 30 s with No Durability (606 before), 1722
  blocks before landing on a wearing elytra (1722).
- **Fresh and saved chunks.** `world.ChunkAge` says which chunks the server generated as you came near (so nobody had
  been there) and which it had saved, from the order of each section's block palette as it was sent:
  `ChunkAge.use(owner)` starts remembering, `isFresh(chunkX, chunkZ)` asks, and `ChunkEvent.Loaded.isFresh()` reads the
  chunk arriving. A chunk with no section that can tell counts as saved, so nothing is skipped on a guess. Lifted from
  Stash Hunter, whose Old Chunks Only now asks it. In singleplayer, ground visited in an earlier session read 0 of 208
  chunks fresh, and new ground 178 of 178.
- **The API loads without core's internals.** Nine API classes (`Renderer3D`, `WorldMesh`, `HighlightStyle`,
  `HighlightSettings`, `HighlightEvent`, `Ghosts`, `RenderStates`, `RenderLayers`, `Packets`) reached into
  `dev.myriadclient.impl` directly; they now go through `Myriad.render()` and `Myriad.packets()` (`Rendering`,
  `PacketChannel`, marked internal: keep using the helpers), which a test harness can fill with its own.
  `ApiSurfaceTest` fails on any API class file that refers to an `impl` class, imports and fully qualified names
  alike. Nothing changes for addons.
- **Registry reads are free.** `Registry.values()`, a for-each and `size()` read a snapshot taken at the last change
  instead of copying under a lock, and `ModuleRegistry.groups()` is kept until a module or group is added or removed
  (the menu asks every frame; key binds walk every module on each key press). With 70 modules: `values()` 162 ns and
  640 bytes a call before, 16 ns and none now; `groups()` 2.3 µs and 1.6 KB before, 26 ns and none; a for-each
  421 ns and 648 bytes before, 49 ns and none. `values()` and `groups()` return the same unmodifiable instance until
  something changes.

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
