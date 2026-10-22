package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Priority;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.PacketEvent;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayDeque;

/**
 * Your own clicks in a screen made while the server would refuse them (walking with Inventory Move, say), in order:
 * Grim cancels such a click without telling the client, which then shows items where the server has none. They're made
 * at the start of the first tick after a still one ({@link ClickGate#prepareClick()}) instead. Modules' clicks don't come
 * here: core's click methods wait for a safe tick themselves ({@link InventoryClicks#canClick}).
 */
final class ScreenClicks {
	/** Ticks a click waits for a still moment (sneaking at an edge never lets go) before it's made anyway. */
	private static final int MAX_CLICK_WAIT = 40;

	private record ScreenClick(int containerId, int slotId, int button, ContainerInput input) {
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final ClickGate gate;
	private final ArrayDeque<ScreenClick> waiting = new ArrayDeque<>();
	private boolean replaying;
	private int waitedTicks;

	ScreenClicks(ClickGate gate) {
		this.gate = gate;
	}

	boolean hasWaiting() {
		return !waiting.isEmpty();
	}

	/** Your own click in a container screen (from the mixin on it): true if it has to wait, and was kept for later. */
	boolean hold(int containerId, int slotId, int button, ContainerInput input) {
		if (replaying || mc.player == null || mc.gameMode == null) return false;
		if (gate.safeToClick() && gate.prepareClick()) {
			// A still moment came between ticks: what waited goes first.
			if (!waiting.isEmpty()) replay();
			return false;
		}
		waiting.add(new ScreenClick(containerId, slotId, button, input));
		gate.prepareClick();
		return true;
	}

	/** Early in the tick, before anything else clicks, so what waited stays first. */
	@Subscribe(priority = 960)
	private void clickWaiting(TickEvent.Pre e) {
		if (waiting.isEmpty()) return;
		if (mc.player == null || mc.gameMode == null) {
			waiting.clear();
			return;
		}
		if (!gate.prepareClick() && ++waitedTicks < MAX_CLICK_WAIT) return;
		replay();
	}

	/** Closing the screen: what waited is clicked first, while it's still open. */
	@Subscribe(priority = Priority.HIGHEST, packets = ServerboundContainerClosePacket.class)
	private void beforeClose(PacketEvent.Send e) {
		if (!waiting.isEmpty() && mc.isSameThread() && mc.player != null) replay();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		waiting.clear();
	}

	private void replay() {
		waitedTicks = 0;
		replaying = true;
		try {
			while (!waiting.isEmpty()) {
				ScreenClick c = waiting.poll();
				// Clicks for a screen that has closed since are dropped: the client never showed them.
				if (mc.player.containerMenu.containerId == c.containerId) mc.gameMode.handleContainerInput(c.containerId, c.slotId, c.button, c.input, mc.player);
			}
		} finally {
			replaying = false;
		}
	}
}
