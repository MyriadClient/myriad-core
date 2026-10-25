package dev.myriadclient.impl.service;

import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.service.PacketLimits;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;
import dev.myriadclient.impl.mixin.AbstractContainerMenuAccessor;
import dev.myriadclient.impl.network.JoinedVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Asks the server for everything in the open menu (your own inventory when none is open), so the client's copy is the
 * server's again. The server only answers a click with the slots that came out differently from what the client
 * said, and a click an anti-cheat drops gets no answer at all: the client then shows items where the server has none,
 * until something else updates those slots.
 * <p>
 * The ask is one click that changes nothing (outside the window with an empty cursor), carrying a state id the server
 * doesn't have; it answers any such click with the whole menu. Its packets arrive in the order it sent them, so once
 * that answer is in, and a tick has passed with nothing newer, the client holds what the server has after every click
 * made before it. Like any click it waits for a tick the server takes one on.
 */
public final class MenuSync {
	/** Servers before 1.17 (through ViaFabricPlus) have no state ids to ask with. */
	private static final int FIRST_STATE_ID_PROTOCOL = 755;
	/** Ticks to wait for a still moment before releasing your keys for one, as borrowed items do. */
	private static final int WAIT_FOR_STILL = 20;
	/** Ticks the cursor may hold something before giving up: the ask would throw it out. */
	private static final int WAIT_FOR_CURSOR = 100;
	private static final int ATTEMPTS = 2;

	private static final class Request {
		final AbstractContainerMenu menu;
		final CompletableFuture<Void> done = new CompletableFuture<>();
		boolean sent;
		int waited, attempts;
		long sentAt, answeredAt = -1;

		Request(AbstractContainerMenu menu) {
			this.menu = menu;
		}
	}

	private final Minecraft mc = Minecraft.getInstance();
	private final List<Request> requests = new ArrayList<>();

	/** Asks for {@code menu}'s contents; completes once they're here, or fails if it closes or no answer comes. */
	public CompletableFuture<Void> refresh(AbstractContainerMenu menu) {
		int protocol = JoinedVersion.get().protocol();
		if (protocol >= 0 && protocol < FIRST_STATE_ID_PROTOCOL) return CompletableFuture.completedFuture(null);
		// Your inventory's contents come with a held container's (the server ignores asks on any other while it's open).
		AbstractContainerMenu held = MyriadImpl.get().containerTracker().heldMenu();
		if (held != null && mc.player != null && menu == mc.player.inventoryMenu) menu = held;
		// One that hasn't gone out yet covers this ask too.
		for (Request r : requests) if (r.menu == menu && !r.sent) return r.done;
		Request r = new Request(menu);
		requests.add(r);
		return r.done;
	}

	/** The server sent a menu's whole contents (from ClientPlayNetworkHandlerMixin, after vanilla applied them). */
	public void onContents(int containerId) {
		for (Request r : requests) if (r.sent && r.menu.containerId == containerId) r.answeredAt = Ticks.current();
	}

	/** Early, with the other inventory work, before modules click. */
	@Subscribe(priority = 955)
	private void onTick(TickEvent.Pre e) {
		if (requests.isEmpty()) return;
		for (Request r : List.copyOf(requests)) {
			if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != r.menu) {
				finish(r, new IllegalStateException("The menu closed"));
			} else if (r.sent) {
				if (r.answeredAt >= 0 && Ticks.current() > r.answeredAt) finish(r, null);
				else if (r.answeredAt < 0 && Ticks.since(r.sentAt) > timeoutTicks()) {
					if (r.attempts < ATTEMPTS) r.sent = false;
					else finish(r, new TimeoutException("The server didn't send the menu"));
				}
			} else {
				trySend(r);
			}
		}
	}

	/** Twice a round trip and a second, held off while the server is lagging. */
	private long timeoutTicks() {
		if (Myriad.server().isLagging(1000)) return Long.MAX_VALUE;
		return 20 + Myriad.server().ping() / 25L;
	}

	private void trySend(Request r) {
		if (!r.menu.getCarried().isEmpty()) {
			if (++r.waited > WAIT_FOR_CURSOR) finish(r, new IllegalStateException("Something stayed on the cursor"));
			return;
		}
		if (!Myriad.limits().canSend(PacketLimits.Kind.INVENTORY, 1)) return;
		var inventory = Myriad.inventory();
		if (!inventory.safeToClick() && ++r.waited <= WAIT_FOR_STILL) return;
		// Sprint stopped for the click (safeToClick only says it can be), or a still tick asked for.
		if (!inventory.prepareClick()) return;
		// Half the id range away: never one the server has, even with its own updates on the way.
		((AbstractContainerMenuAccessor) r.menu).myriad$setStateId((r.menu.getStateId() + 16384) & 32767);
		mc.gameMode.handleContainerInput(r.menu.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
		r.sent = true;
		r.attempts++;
		r.sentAt = Ticks.current();
		r.answeredAt = -1;
	}

	private void finish(Request r, Throwable failure) {
		requests.remove(r);
		if (failure == null) r.done.complete(null);
		else r.done.completeExceptionally(failure);
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		for (Request r : List.copyOf(requests)) finish(r, new IllegalStateException("Left the world"));
	}
}
