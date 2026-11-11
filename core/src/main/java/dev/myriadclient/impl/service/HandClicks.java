package dev.myriadclient.impl.service;

import dev.myriadclient.api.event.Subscribe;
import dev.myriadclient.api.event.events.TickEvent;
import dev.myriadclient.api.event.events.WorldEvent;
import dev.myriadclient.api.util.Ticks;
import dev.myriadclient.impl.MyriadImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps what your own clicks did on screen while the server's answers to earlier ones are still arriving. A server that
 * answers every click with the whole menu (2b2t) sends, for each click, the menu as it stood after that click; with
 * lag, those arrive after you've clicked again, and each one undid your newer clicks on screen for a moment: a stack
 * jumped back onto the cursor, or seemed not to move, and a click made then was predicted on that old view.
 * <p>
 * So after a click of yours (from a container screen) the menu as it predicted is kept, and for a round trip any
 * answer only updates a copy of what the server says: the screen, and your next click's prediction, stay on what the
 * server will have once it has your clicks. When a round trip passes without one and what it said disagrees, the
 * server is asked for the whole menu, so a click it refused still shows. Core's own work in the menu (a sort, a transfer, asking for the menu) checks what
 * the server really has, so while that runs answers go in as they come.
 */
public final class HandClicks {
	private final Minecraft mc = Minecraft.getInstance();

	/** The menu your last click was in, what it predicted (slots and cursor), and the tick it was made. */
	private @Nullable AbstractContainerMenu menu;
	private @Nullable ItemStack[] shown;
	private ItemStack shownCarried = ItemStack.EMPTY;
	private long clickedAt = Long.MIN_VALUE / 2;
	/** What the server last said, while answers are kept off the screen; null when none came. */
	private @Nullable ItemStack[] server;
	private ItemStack serverCarried = ItemStack.EMPTY;

	/** You clicked in {@code menu} (after the game predicted the click). */
	public void clicked(AbstractContainerMenu menu) {
		if (busy(menu) || mc.player == null || mc.player.isCreative()) {
			forget();
			return;
		}
		if (this.menu != menu) forget();
		this.menu = menu;
		shown = copy(menu);
		shownCarried = menu.getCarried().copy();
		clickedAt = Ticks.current();
	}

	/** The server sent {@code containerId}'s whole menu (after the game applied it). */
	public void contents(int containerId) {
		if (!keeping(containerId)) return;
		server = copy(menu);
		serverCarried = menu.getCarried().copy();
		restore();
	}

	/** The server sent one slot of {@code containerId} (after the game applied it). */
	public void slot(int containerId, int slot) {
		if (!keeping(containerId) || slot < 0 || slot >= shown.length) return;
		if (server == null) {
			server = shown.clone();
			serverCarried = shownCarried;
		}
		server[slot] = menu.getSlot(slot).getItem().copy();
		menu.getSlot(slot).set(shown[slot].copy());
	}

	/** The server set the cursor of the open menu (after the game applied it). */
	public void cursor() {
		if (mc.player == null || !keeping(mc.player.containerMenu.containerId)) return;
		if (server == null) server = shown.clone();
		serverCarried = menu.getCarried().copy();
		menu.setCarried(shownCarried.copy());
	}

	/**
	 * Whether answers for {@code containerId} are kept off the screen now: your clicks there are still on their way.
	 * Otherwise what the game just put in is the server's latest word, and nothing older is kept to put back.
	 */
	private boolean keeping(int containerId) {
		if (menu == null || shown == null || mc.player == null || menu.containerId != containerId) return false;
		if (mc.player.containerMenu == menu && !busy(menu) && Ticks.current() - clickedAt <= window()) return true;
		forget();
		return false;
	}

	/** About a round trip, and a tick for answers split over two. */
	private static long window() {
		return SlotsInFlight.roundTrip() + 1L;
	}

	/** Core is working in {@code menu} itself, checking what the server has. */
	private static boolean busy(AbstractContainerMenu menu) {
		MyriadImpl impl = MyriadImpl.get();
		return impl.containerTracker().working(menu) || impl.menuSync().asking(menu);
	}

	private void restore() {
		for (int i = 0; i < shown.length && i < menu.slots.size(); i++) {
			if (!ItemStack.matches(menu.getSlot(i).getItem(), shown[i])) menu.getSlot(i).set(shown[i].copy());
		}
		if (!ItemStack.matches(menu.getCarried(), shownCarried)) menu.setCarried(shownCarried.copy());
	}

	/**
	 * Stops keeping. If what the server said meanwhile disagrees with the screen, the server is asked for the whole menu
	 * (MenuSync, once the cursor is empty): its answer comes after every click before it, so it's what the server has.
	 * What it said can't simply go in: a server answering every click (2b2t) answers each click of a burst with the menu
	 * as it stood then, the last answer needn't be about the last click (one that came out as predicted, a double
	 * click's gathering or a drag's end may get none), and putting it in brought back a stack already put down.
	 */
	private void settle() {
		if (menu != null && server != null && mc.player != null && mc.player.containerMenu == menu && disagrees()) {
			MyriadImpl.get().menuSync().refresh(menu);
		}
		forget();
	}

	private boolean disagrees() {
		if (!ItemStack.matches(menu.getCarried(), serverCarried)) return true;
		for (int i = 0; i < server.length && i < menu.slots.size(); i++) if (!ItemStack.matches(menu.getSlot(i).getItem(), server[i])) return true;
		return false;
	}

	private void forget() {
		menu = null;
		shown = null;
		server = null;
		shownCarried = serverCarried = ItemStack.EMPTY;
	}

	private static ItemStack[] copy(AbstractContainerMenu menu) {
		ItemStack[] items = new ItemStack[menu.slots.size()];
		for (int i = 0; i < items.length; i++) items[i] = menu.getSlot(i).getItem().copy();
		return items;
	}

	/** Before core's inventory work, so a job starting this tick sees what the server says. */
	@Subscribe(priority = 960)
	private void onTick(TickEvent.Pre e) {
		if (menu == null) return;
		if (mc.player == null || mc.player.containerMenu != menu || Ticks.current() - clickedAt > window() || busy(menu)) settle();
	}

	@Subscribe
	private void onLeave(WorldEvent.Leave e) {
		forget();
	}
}
