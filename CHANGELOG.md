# Changelog

Myriad follows [semantic versioning](https://semver.org) for the public API (`dev.myriad.api`): while the major
version is 0, a **minor** release may change the API, and anything removed is deprecated for at least one minor
release first with its replacement in the javadoc; a **patch** release never changes it. `dev.myriad.impl` is internal
and may change in any release. Addons declare the oldest core they support in `fabric.mod.json`
(`"depends": {"myriad": ">=0.1.0"}`) and can check `Myriad.isAtLeast("0.2.0")` for newer features.

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
- Essentials has moved to its own repository, [myriad-essentials](https://github.com/fyzz-dev/myriad-essentials),
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

- [myriad-boze](https://github.com/fyzz-dev/myriad-boze), a separate addon: every module of the Boze client in
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
