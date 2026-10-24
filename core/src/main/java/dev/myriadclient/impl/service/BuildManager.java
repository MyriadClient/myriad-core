package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.build.Blueprint;
import dev.myriadclient.api.build.Build;
import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.BlockUpdateEvent;
import dev.myriadclient.api.event.events.TeleportEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.Building;
import dev.myriadclient.api.util.Threads;
import dev.myriadclient.impl.MyriadImpl;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runs builds ({@link BuildRun}, one each): ticks them before the placement and breaking services act this tick, so
 * what's planned now goes out now, and tells them when the server changes a block or moves you. What all builds share
 * lives here: one inventory move a tick between them, and each item's hotbar slot worked out once a tick.
 */
public final class BuildManager implements Building {
	private final Minecraft mc = Minecraft.getInstance();
	private final PlacementManager placement;
	private final List<BuildRun> builds = new ArrayList<>();
	/** Each item's hotbar slot this tick (or why there's none), asked once however many positions want it. */
	private final Reference2IntOpenHashMap<Item> slots = new Reference2IntOpenHashMap<>();
	/**
	 * Inventory moves for builds this tick, and the most: each block moved in is placed in the same tick, so a few a tick
	 * keep a build of more kinds than the hotbar holds going at about the pace of one whose blocks are all there, well
	 * inside the inventory click budget.
	 */
	private int materialMoves;
	private static final int MATERIAL_MOVES = 3;

	public BuildManager(PlacementManager placement) {
		this.placement = placement;
	}

	PlacementManager placement() {
		return placement;
	}

	@Override
	public Build start(Object owner, Blueprint blueprint, Options options) {
		Threads.assertRender();
		BuildRun b = new BuildRun(this, owner, blueprint, options);
		builds.add(b);
		return b;
	}

	@Override
	public void stop(Object owner) {
		for (BuildRun b : new ArrayList<>(builds)) if (b.owner == owner) b.stop();
	}

	@Override
	public List<Build> running() {
		return builds.stream().filter(Build::isRunning).map(Build.class::cast).toList();
	}

	@Subscribe(priority = Priority.HIGH)
	private void onTick(TickEvent.Pre e) {
		builds.removeIf(b -> !b.isRunning());
		if (builds.isEmpty() && MyriadImpl.get().inventoryManager() instanceof InventoryManager inventory) inventory.buildsDone();
		if (builds.isEmpty() || mc.player == null || mc.level == null) return;
		materialMoves = 0;
		slots.clear();
		for (BuildRun b : new ArrayList<>(builds)) {
			try {
				b.tick();
			} catch (RuntimeException | LinkageError ex) {
				// A linkage error is another mod (a schematic mod) at a version whose methods changed: stop rather than crash.
				MyriadImpl.LOG.error("Build for {} failed", b.owner, ex);
				b.stop();
			}
		}
	}

	@Subscribe
	private void onBlockUpdate(BlockUpdateEvent e) {
		for (BuildRun b : builds) b.blockUpdated(e.pos());
	}

	@Subscribe
	private void onTeleport(TeleportEvent e) {
		for (BuildRun b : builds) b.teleported();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		for (BuildRun b : new ArrayList<>(builds)) b.stop();
		builds.clear();
	}

	/** {@code item}'s hotbar slot (or why there's none) if a build already worked it out this tick, else {@link Integer#MIN_VALUE}. */
	int slotThisTick(Item item) {
		return slots.getOrDefault(item, Integer.MIN_VALUE);
	}

	/** Keeps {@code item}'s hotbar slot (or why there's none) for the rest of this tick, and returns it. */
	int rememberSlot(Item item, int slot) {
		slots.put(item, slot);
		return slot;
	}

	/**
	 * Makes an inventory move for a build if fewer than {@link #MATERIAL_MOVES} have been made this tick and a click can
	 * go (Grim cancels inventory clicks while you move: keys are released for a tick first if needed). Returns what
	 * {@code move} did.
	 */
	boolean moveMaterial(BooleanSupplier move) {
		if (materialMoves >= MATERIAL_MOVES || !Myriad.inventory().prepareClick()) return false;
		materialMoves++;
		return move.getAsBoolean();
	}
}
